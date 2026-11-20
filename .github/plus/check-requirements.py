#!/usr/bin/env python3
"""Build 15 requirements gate: checks docs/plus/build15/requirements.json against the repository and,
with --online, against GitHub itself.

The register says, for every requirement, what was done and what proves it. This script refuses a
register that claims more than its evidence shows:

  1. Every mandatory requirement (MANDATORY below) is present, marked mandatory, and every phase
     P0-P9 has at least one requirement.
  2. VERIFIED / ACCEPTED need evidence that can be repeated: a CI run, a test, a measurement, a
     screenshot or a device result. A review alone is not enough.
  3. A requirement that needs a physical device is VERIFIED / ACCEPTED only with device evidence.
  4. ACCEPTED needs the owner's acceptance (who, when, where).
  5. No evidence of a VERIFIED / ACCEPTED requirement records a failed, pending, cancelled or
     skipped result.
  6. A cited test exists: its file is in the repository and contains the test's name.
  7. Evidence is not stale: no file of the requirement changed after the newest commit its CI
     evidence names, and that commit is part of this branch's history. A change to a verified area
     therefore fails the gate until the register is updated (new evidence, or the status lowered).
  8. With --online, every cited CI run is read from GitHub: it must exist, have succeeded, and have
     run on exactly the commit the register names.

A green gate shows that the register's claims match its evidence; it does not replace the evidence.
Usage: check-requirements.py [--repo DIR] [--online]          Exit status 1 on any problem.
"""
import argparse
import fnmatch
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

REGISTER = "docs/plus/build15/requirements.json"
STATUSES = ["NOT STARTED", "IN PROGRESS", "BLOCKED", "VERIFIED", "ACCEPTED"]
PHASES = [f"P{n}" for n in range(10)]
GROUPS = PHASES + ["D", "G"]
STRONG = {"ci-run", "test", "measurement", "screenshot", "device"}
KINDS = STRONG | {"review"}
BAD_RESULTS = {"fail", "failed", "failure", "pending", "queued", "in_progress", "cancelled", "skipped",
               "timed_out", "action_required", "unknown"}
FIELDS = {"id": str, "phase": str, "mandatory": bool, "requirement": str, "source": str, "paths": list,
          "status": str, "evidence": list, "untested": list, "needs_physical_device": bool,
          "acceptance": str, "next": str}
# The requirements of the Build 15 specification and the owner's later decisions. Removing one from
# the register, or marking it optional, fails the gate; adding new ones is always allowed.
MANDATORY = [
    "P0-01", "P0-02",
    "P1-01", "P1-02", "P1-03", "P1-04", "P1-05", "P1-06",
    "P2-01", "P2-02",
    "P3-01", "P3-02", "P3-03",
    "P4-01", "P4-02", "P4-03", "P4-04", "P4-05",
    "P5-01", "P5-02",
    "P6-01", "P6-02", "P6-03", "P6-04",
    "P7-01", "P7-02", "P7-03",
    "P8-01", "P8-02",
    "P9-01",
    "D-01", "D-02",
    "G-01", "G-02", "G-05", "G-06", "G-07",
]
SHA = re.compile(r"^[0-9a-f]{40}$")


def git(repo, *args):
    return subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True)


def matches(path, patterns):
    return any(fnmatch.fnmatch(path, p) or path == p or path.startswith(p.rstrip("/") + "/") for p in patterns)


def check(register, repo, fetch_run=None):
    """Problems in [register] (empty when it holds), against the repository at [repo]."""
    problems = []
    items = register.get("items")
    if not isinstance(items, list):
        return ["the register has no list of items"]
    seen = {}
    for index, item in enumerate(items):
        where = item.get("id", f"item {index + 1}") if isinstance(item, dict) else f"item {index + 1}"
        if not isinstance(item, dict):
            problems.append(f"{where}: not an object")
            continue
        for field, kind in FIELDS.items():
            if not isinstance(item.get(field), kind):
                problems.append(f"{where}: field '{field}' missing or not {kind.__name__}")
        if where in seen:
            problems.append(f"{where}: listed twice")
        seen[where] = item
        if item.get("phase") not in GROUPS:
            problems.append(f"{where}: unknown phase {item.get('phase')!r}")
        if item.get("status") not in STATUSES:
            problems.append(f"{where}: unknown status {item.get('status')!r}")
    for required in MANDATORY:
        if required not in seen:
            problems.append(f"{required}: mandatory requirement missing from the register")
        elif seen[required].get("mandatory") is not True:
            problems.append(f"{required}: mandatory requirement marked optional")
    for phase in PHASES:
        if not any(isinstance(i, dict) and i.get("phase") == phase for i in items):
            problems.append(f"{phase}: no requirement")
    for item in items:
        if isinstance(item, dict) and all(isinstance(item.get(f), k) for f, k in FIELDS.items()):
            problems += check_item(item, repo, fetch_run)
    return problems


def check_item(item, repo, fetch_run):
    problems = []
    rid, status = item["id"], item["status"]
    claimed = status in ("VERIFIED", "ACCEPTED")
    evidence = item["evidence"]
    kinds = set()
    runs = []
    for number, proof in enumerate(evidence, start=1):
        label = f"{rid} evidence {number}"
        if not isinstance(proof, dict) or proof.get("kind") not in KINDS:
            problems.append(f"{label}: unknown kind {proof.get('kind') if isinstance(proof, dict) else proof!r}")
            continue
        kind = proof["kind"]
        kinds.add(kind)
        result = str(proof.get("result", "")).strip().lower()
        if claimed and result in BAD_RESULTS:
            problems.append(f"{label}: result '{result}' cannot support {status}")
        if kind == "ci-run":
            if not isinstance(proof.get("run"), int) or not SHA.match(str(proof.get("commit", ""))):
                problems.append(f"{label}: a CI run needs a numeric 'run' and the full 40-character 'commit'")
            else:
                runs.append(proof)
        elif kind == "test":
            path, name = proof.get("file"), proof.get("name")
            file = Path(repo, str(path))
            if not path or not name or not file.is_file():
                problems.append(f"{label}: test file {path!r} not in the repository")
            elif str(name) not in file.read_text(errors="replace"):
                problems.append(f"{label}: test '{name}' not found in {path}")
        else:
            path = proof.get("file")
            if not path or not Path(repo, str(path)).exists():
                problems.append(f"{label}: {kind} file {path!r} not in the repository")
    if claimed:
        if not kinds & STRONG:
            problems.append(f"{rid}: {status} without repeatable evidence (a review alone is not enough)")
        if item["needs_physical_device"] and "device" not in kinds:
            problems.append(f"{rid}: needs a physical device; {status} without device evidence")
    if status == "ACCEPTED":
        accepted = item.get("accepted")
        if not (isinstance(accepted, dict) and accepted.get("by") == "owner" and accepted.get("on") and accepted.get("ref")):
            problems.append(f"{rid}: ACCEPTED without the owner's acceptance (by, on, ref)")
    if claimed and runs and item["paths"]:
        problems += stale(rid, item["paths"], runs, repo)
    if fetch_run is not None:
        for proof in runs:
            problems += check_run(rid, proof, fetch_run)
    return problems


def stale(rid, paths, runs, repo):
    """Files of the requirement changed after the newest commit its CI evidence names."""
    known = [p["commit"] for p in runs if git(repo, "merge-base", "--is-ancestor", p["commit"], "HEAD").returncode == 0]
    missing = [p["commit"][:8] for p in runs if p["commit"] not in known]
    if not known:
        return [f"{rid}: evidence commit(s) {', '.join(missing)} not in this branch's history"]
    newest = max(known, key=lambda c: int(git(repo, "rev-list", "--count", c).stdout.strip() or 0))
    changed = git(repo, "diff", "--name-only", newest, "HEAD").stdout.split()
    touched = sorted(p for p in changed if matches(p, paths))
    if touched:
        return [f"{rid}: changed after its evidence ({newest[:8]}): {', '.join(touched[:5])}"
                f"{' …' if len(touched) > 5 else ''}; cite new evidence or lower the status"]
    return []


def check_run(rid, proof, fetch_run):
    try:
        run = fetch_run(proof["run"])
    except Exception as error:  # noqa: BLE001 - any failure to read is reported, never skipped
        return [f"{rid}: could not read run {proof['run']} from GitHub ({error})"]
    problems = []
    if run.get("conclusion") != "success":
        problems.append(f"{rid}: run {proof['run']} concluded {run.get('conclusion') or run.get('status')!r}, not success")
    if run.get("head_sha") != proof["commit"]:
        problems.append(f"{rid}: run {proof['run']} ran on {str(run.get('head_sha'))[:8]}, not {proof['commit'][:8]}")
    return problems


def github_run(run_id, urlopen=urllib.request.urlopen):
    """The run from GitHub's API: with the job's token first, without it if the token is refused."""
    url = f"https://api.github.com/repos/Mohammed4421/NuvioMobile/actions/runs/{run_id}"
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    error = None
    for use_token in ([True, False] if token else [False]):
        request = urllib.request.Request(url, headers={"Accept": "application/vnd.github+json"})
        if use_token:
            request.add_header("Authorization", f"Bearer {token}")
        try:
            with urlopen(request, timeout=30) as response:
                return json.load(response)
        except urllib.error.HTTPError as failure:
            error = failure
            if not (use_token and failure.code in (401, 403)):
                raise
    raise error


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", default=".")
    parser.add_argument("--online", action="store_true", help="also read every cited run from GitHub")
    args = parser.parse_args()
    path = Path(args.repo, REGISTER)
    try:
        register = json.loads(path.read_text())
    except (OSError, ValueError) as error:
        print(f"::error::{REGISTER}: {error}")
        return 1
    problems = check(register, args.repo, github_run if args.online else None)
    for problem in problems:
        print(f"::error::{problem}")
    counts = {s: sum(1 for i in register["items"] if isinstance(i, dict) and i.get("status") == s) for s in STATUSES}
    device = sum(1 for i in register["items"] if isinstance(i, dict) and i.get("needs_physical_device"))
    print("Register: " + ", ".join(f"{s} {n}" for s, n in counts.items()) +
          f"; {device} need a physical device; progress counts ACCEPTED only: {counts['ACCEPTED']} of {len(register['items'])}")
    print("OK" if not problems else f"{len(problems)} problem(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
