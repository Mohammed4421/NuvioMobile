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
# Spec §15 states, in order.
STATUSES = ["DISCOVERED", "SPECIFIED", "IMPLEMENTED", "VERIFIED", "ACCEPTED"]
PHASES = [f"P{n}" for n in range(10)]
GROUPS = PHASES + ["D", "G"]
STRONG = {"ci-run", "test", "measurement", "screenshot", "device"}
KINDS = STRONG | {"review"}
# Spec P3: the user-journey requirements (P3-07 applies to them).
JOURNEYS = {"P3-01", "P3-02", "P3-03", "P3-04", "P3-05", "P3-06"}
# Spec §11 result states other than PASS, and CI conclusions that are not a pass.
BAD_RESULTS = {"fail", "failed", "failure", "blocked", "not tested", "needs physical device", "pending", "queued",
               "in_progress", "cancelled", "skipped", "timed_out", "action_required", "unknown"}
FIELDS = {"id": str, "spec": str, "phase": str, "mandatory": bool, "requirement": str, "source": str, "paths": list,
          "status": str, "evidence": list, "untested": list, "needs_physical_device": bool,
          "acceptance": str, "next": str, "release_class": str}
# Owner directive 2026-11-22 (NVP15-CHG-0005): when each requirement must hold. Personal checks on the
# owner's phone wait until the release is installed; nothing critical to data, signing or upgrade may.
RELEASE_CLASSES = {"PRE-RELEASE REQUIRED", "POST-RELEASE VALIDATION", "BLOCKED"}
# Data protection, signing and upgrade: always proven before the release (the directive's rule).
CRITICAL = {"P3-06", "P6-05", "P6-08", "P6-09", "P6-14", "P6-15", "P6-16", "P6-17", "P8-10", "S16-04", "S16-05",
            "S17-08", "D-10", "P2-02"}
# Every requirement of the Build 15 specification v1.1 (docs/plus/build15/requirements.json, by section)
# and the owner's later decisions. Removing one from
# the register, or marking it optional, fails the gate; adding new ones is always allowed.
MANDATORY = [
    "C-01", "C-02", "C-03", "P0-01", "P0-02", "P0-03", "P0-04", "P0-05", "P0-06", "P0-07",
    "P1-01", "P1-02", "P1-03", "P1-04", "P1-05", "P1-06", "P2-01", "P2-02", "P2-03", "P2-04",
    "P3-01", "P3-02", "P3-03", "P3-04", "P3-05", "P3-06", "P3-07", "P4-01", "P4-02", "P4-03",
    "P4-04", "P4-05", "P4-06", "P4-07", "P4-08", "P4-09", "P4-10", "P4-11", "P4-12", "P4-13",
    "P5-01", "P5-02", "P5-03", "P5-04", "P5-05", "P5-06", "P5-07", "P5-08", "P5-09", "P5-10",
    "P5-11", "P6-01", "P6-02", "P6-03", "P6-04", "P6-05", "P6-06", "P6-07", "P6-08", "P6-09",
    "P6-10", "P6-11", "P6-12", "P6-13", "P6-14", "P6-15", "P6-16", "P6-17", "P6-18", "P7-01",
    "P7-02", "P7-03", "P7-04", "P7-05", "P7-06", "P7-07", "P7-08", "P7-09", "P7-10", "P8-01",
    "P8-02", "P8-03", "P8-04", "P8-05", "P8-06", "P8-07", "P8-08", "P8-09", "P8-10", "P8-11",
    "P8-12", "P8-13", "S12-01", "S12-02", "S12-03", "S13-01", "S14-01", "S15-01", "S16-01", "S16-02",
    "S16-03", "S16-04", "S16-05", "S16-06", "S16-07", "S16-08", "S16-09", "S16-10", "S16-11", "S16-12",
    "S17-01", "S17-02", "S17-03", "S17-04", "S17-05", "S17-06", "S17-07", "S17-08", "S17-09", "S17-10",
    "S17-11", "S17-12", "S17-13", "D-01", "D-02", "D-03", "D-04", "D-05", "D-06", "D-07",
    "D-08", "D-09", "D-10",
    # Spec v1.1 §19–25 and later owner decisions.
    "S19-01", "S19-02", "S19-03", "S19-04", "S20-01", "S20-02", "S21-01", "S21-02", "S22-01", "S22-02",
    "S23-01", "S23-02", "S24-01", "S24-02", "S25-01", "S25-02", "S25-03", "D-11", "D-12", "D-13",
    # Owner directive 2026-11-22 (NVP15-CHG-0005): personal checks after the release is installed.
    "PRV-01", "PRV-02", "PRV-03", "PRV-04", "PRV-05", "PRV-06", "PRV-07",
    # Owner decision 2026-11-25 (NVP15-CHG-0009): TalkBack and focus order on the owner's phone.
    "PRV-08",
]
SHA = re.compile(r"^[0-9a-f]{40}$")
# Spec v1.1 §19.2: stable NVP15 identifiers, never reused.
NVP15_REQ = re.compile(r"^NVP15-REQ-\d{4}$")
# Spec v1.1 §19.4 counterparts kept next to the register (one fact, one file).
SIDE = {"stage_gates": "docs/plus/build15/stage-gates.json", "carryover": "docs/plus/build15/carryover.json",
        "risks": "docs/plus/build15/risks.json", "changes": "docs/plus/build15/change-requests.json"}
GATE_STATES = {"PASS", "FAIL", "BLOCKED", "AWAITING OWNER APPROVAL"}  # §22.3; no "PASS WITH CRITICAL GAPS"
CARRYOVER_STATES = {"REUSE VERIFIED", "REUSE REVALIDATION NEEDED", "INCOMPLETE", "CONFLICTING", "NOT APPLICABLE"}
RISK_STATES = {"FAIL", "BLOCKED", "NOT TESTED", "NEEDS PHYSICAL DEVICE", "OPEN DECISION"}


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
        if item.get("release_class") not in RELEASE_CLASSES:
            problems.append(f"{where}: release_class {item.get('release_class')!r} is not one of {sorted(RELEASE_CLASSES)}")
        if item.get("release_class") == "POST-RELEASE VALIDATION" and item.get("needs_physical_device") is not True:
            problems.append(f"{where}: POST-RELEASE VALIDATION is only for checks on the owner's device")
    for required in MANDATORY:
        if required not in seen:
            problems.append(f"{required}: mandatory requirement missing from the register")
        elif seen[required].get("mandatory") is not True:
            problems.append(f"{required}: mandatory requirement marked optional")
    for phase in PHASES:
        if not any(isinstance(i, dict) and i.get("phase") == phase for i in items):
            problems.append(f"{phase}: no requirement")
    nvp = {}
    for item in items:
        if not isinstance(item, dict):
            continue
        code = item.get("nvp15")
        if not isinstance(code, str) or not NVP15_REQ.match(code):
            problems.append(f"{item.get('id')}: no NVP15-REQ-#### identifier (spec v1.1 §19.2)")
        elif code in nvp:
            problems.append(f"{item.get('id')}: {code} also used by {nvp[code]}")
        else:
            nvp[code] = item.get("id")
    for item in items:
        if not isinstance(item, dict):
            continue
        for ref in item.get("post_release", []) or []:
            target = seen.get(ref)
            if not target or target.get("release_class") != "POST-RELEASE VALIDATION":
                problems.append(f"{item.get('id')}: deferred part {ref} is not a POST-RELEASE VALIDATION requirement")
        if item.get("id") in CRITICAL and item.get("release_class") == "POST-RELEASE VALIDATION":
            problems.append(f"{item.get('id')}: data, signing and upgrade checks cannot wait for after the release")
    problems += check_side(repo, seen)
    for item in items:
        if isinstance(item, dict) and all(isinstance(item.get(f), k) for f, k in FIELDS.items()):
            problems += check_item(item, repo, fetch_run)
    return problems


def check_side(repo, seen):
    """The stage gates, carry-over, risk and change registers: present, well formed, and every
    requirement they name is in the register. A gate is PASS only when every mandatory requirement
    of its phase is VERIFIED or ACCEPTED and the owner's decision is recorded."""
    problems = []
    loaded = {}
    for key, rel in SIDE.items():
        try:
            loaded[key] = json.loads(Path(repo, rel).read_text())
        except (OSError, ValueError) as error:
            problems.append(f"{rel}: {error}")
    def refs(where, ids):
        for rid in ids or []:
            if rid not in seen:
                problems.append(f"{where}: unknown requirement {rid}")
    for gate in loaded.get("stage_gates", {}).get("gates", []):
        where = f"stage gate {gate.get('stage')}"
        if gate.get("stage") not in PHASES:
            problems.append(f"{where}: unknown stage")
        if gate.get("status") not in GATE_STATES:
            problems.append(f"{where}: status {gate.get('status')!r} is not one of {sorted(GATE_STATES)}")
        if gate.get("status") == "PASS":
            # A phase closes when everything it needs before the release holds; its post-release
            # checks stay open (and NOT TESTED) without holding it (NVP15-CHG-0005).
            open_items = [i["id"] for i in seen.values() if i.get("phase") == gate.get("stage") and i.get("mandatory")
                          and i.get("release_class") != "POST-RELEASE VALIDATION"
                          and i.get("status") not in ("VERIFIED", "ACCEPTED")]
            if open_items:
                problems.append(f"{where}: PASS with mandatory requirements not verified: {', '.join(open_items)}")
            if not gate.get("owner_approval"):
                problems.append(f"{where}: PASS without the owner's decision")
        refs(where, gate.get("requirements"))
    for entry in loaded.get("carryover", {}).get("entries", []):
        where = f"carry-over {entry.get('source')}"
        if entry.get("state") not in CARRYOVER_STATES:
            problems.append(f"{where}: state {entry.get('state')!r} is not one of {sorted(CARRYOVER_STATES)}")
        refs(where, entry.get("requirements"))
    for risk in loaded.get("risks", {}).get("risks", []):
        where = f"risk {risk.get('id')}"
        if not re.match(r"^NVP15-RISK-\d{4}$", str(risk.get("id"))):
            problems.append(f"{where}: no NVP15-RISK-#### identifier")
        if risk.get("state") not in RISK_STATES:
            problems.append(f"{where}: state {risk.get('state')!r} is not one of {sorted(RISK_STATES)}")
        for field in ("reason", "needs", "impact", "priority", "decision_owner", "closes_when"):
            if not str(risk.get(field, "")).strip():
                problems.append(f"{where}: field '{field}' empty (spec v1.1 §22.1)")
        refs(where, risk.get("requirements"))
    for change in loaded.get("changes", {}).get("changes", []):
        where = f"change {change.get('id')}"
        if not re.match(r"^NVP15-CHG-\d{4}$", str(change.get("id"))):
            problems.append(f"{where}: no NVP15-CHG-#### identifier")
        for field in ("before", "after", "reason", "impact", "decision"):
            if not str(change.get(field, "")).strip():
                problems.append(f"{where}: field '{field}' empty (spec v1.1 §25.1)")
        refs(where, change.get("requirements"))
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
        # P3-07: a user journey is not proven by unit tests alone; it needs a run of the app
        # (an emulator or device run in CI, a screenshot, a measurement or a device result).
        if rid in JOURNEYS and not kinds & (STRONG - {"test"}):
            problems.append(f"{rid}: {status} journey on unit tests alone (P3-07)")
    if status == "ACCEPTED":
        accepted = item.get("accepted")
        # "delegated": accepted under the owner's written delegation once its acceptance criteria hold.
        if not (isinstance(accepted, dict) and accepted.get("by") in ("owner", "delegated") and accepted.get("on") and accepted.get("ref")):
            problems.append(f"{rid}: ACCEPTED without the owner's acceptance (by, on, ref)")
    if claimed and runs and item["paths"]:
        problems += stale(rid, item["paths"], runs, repo)
    if fetch_run is not None:
        for proof in runs:
            problems += check_run(rid, proof, fetch_run)
    return problems


def weight(item):
    """S15-01 risk weight: the release-critical data, signing and upgrade items 3, user journeys,
    security and test layers (P3, P6, P8) 2, every other requirement 1. Effort is not estimated."""
    if item.get("id") in CRITICAL:
        return 3
    return 2 if item.get("phase") in ("P3", "P6", "P8") else 1


def weighted_progress(items):
    """Implementation, verification and acceptance as risk-weighted percentages of all requirements,
    release readiness as the weighted share of PRE-RELEASE REQUIRED items VERIFIED or ACCEPTED, and the
    critical path: open pre-release items, critical ones first. Shown beside, never instead of, the
    equal counts (spec §15/§24.4)."""
    done = {"implemented": ("IMPLEMENTED", "VERIFIED", "ACCEPTED"), "verified": ("VERIFIED", "ACCEPTED"),
            "accepted": ("ACCEPTED",)}
    total = sum(weight(i) for i in items) or 1
    result = {key: 100 * sum(weight(i) for i in items if i.get("status") in states) // total
              for key, states in done.items()}
    pre = [i for i in items if i.get("release_class") == "PRE-RELEASE REQUIRED"]
    pre_total = sum(weight(i) for i in pre) or 1
    result["ready"] = 100 * sum(weight(i) for i in pre if i.get("status") in done["verified"]) // pre_total
    open_items = [i for i in pre if i.get("status") not in done["verified"]]
    result["critical_path"] = [i["id"] for i in sorted(open_items, key=lambda i: (-weight(i), str(i.get("id"))))]
    return result


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
    parser.add_argument("--matrix", action="store_true", help="print the traceability matrix (CSV) and exit")
    args = parser.parse_args()
    path = Path(args.repo, REGISTER)
    try:
        register = json.loads(path.read_text())
    except (OSError, ValueError) as error:
        print(f"::error::{REGISTER}: {error}")
        return 1
    if args.matrix:
        import csv
        writer = csv.writer(sys.stdout)
        writer.writerow(["nvp15", "id", "spec", "mandatory", "status", "evidence_runs", "evidence_commits", "untested", "physical_device"])
        for i in register["items"]:
            ev = [e for e in i.get("evidence", []) if isinstance(e, dict)]
            writer.writerow([i.get("nvp15"), i.get("id"), i.get("spec"), i.get("mandatory"), i.get("status"),
                             " ".join(str(e.get("run", e.get("file", ""))) for e in ev),
                             " ".join(str(e.get("commit", ""))[:12] for e in ev),
                             len(i.get("untested", [])), i.get("needs_physical_device")])
        return 0
    problems = check(register, args.repo, github_run if args.online else None)
    for problem in problems:
        print(f"::error::{problem}")
    items = [i for i in register["items"] if isinstance(i, dict)]
    counts = {st: sum(1 for i in items if i.get("status") == st) for st in STATUSES}
    total = len(items) or 1
    implemented = counts["IMPLEMENTED"] + counts["VERIFIED"] + counts["ACCEPTED"]
    verified = counts["VERIFIED"] + counts["ACCEPTED"]
    untested = sum(len(i.get("untested", [])) for i in items)
    device = sum(1 for i in items if i.get("needs_physical_device") and i.get("status") not in ("VERIFIED", "ACCEPTED"))
    print("Register: " + ", ".join(f"{st} {n}" for st, n in counts.items()))
    # Spec §15/§24.4: three separate figures, each = requirements in that state or beyond / all
    # requirements, counted equally, never one figure; the risk-weighted line below is shown beside it.
    print(f"Implementation {100 * implemented // total}% | verification {100 * verified // total}% | "
          f"acceptance {100 * counts['ACCEPTED'] // total}% (of {len(items)}); "
          f"untested parts listed: {untested}; open items needing a physical device: {device}")
    weighted = weighted_progress(items)
    print("Weighted by risk (S15-01; data, signing and upgrade x3, journeys/security/tests x2, others x1): "
          f"implementation {weighted['implemented']}% | verification {weighted['verified']}% | "
          f"acceptance {weighted['accepted']}%; release readiness {weighted['ready']}% of pre-release items")
    print("Critical path (open pre-release items, data/signing/upgrade first): "
          + (", ".join(weighted["critical_path"]) or "none"))
    print("OK" if not problems else f"{len(problems)} problem(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
