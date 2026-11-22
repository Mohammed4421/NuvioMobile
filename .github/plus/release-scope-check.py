#!/usr/bin/env python3
"""Build 15 release scope check (spec v1.1 §12, S12-02).

Compares docs/plus/build15/release-scope.json with GitHub and the commit being published (HEAD) and
blocks on any mismatch:
  * the branch is the manifest's, and its head on GitHub is still HEAD (it has not moved);
  * every required pull request is merged into that branch and its merge commit is part of HEAD;
  * no excluded pull request is merged or part of HEAD;
  * each approval test of the release commit (a workflow run on HEAD) concluded success;
  * the mandatory requirement ids equal those the requirements gate enforces;
  * PortKit is the pinned one (SHA-256 of portkit/portkit.py).
Anything that cannot be confirmed blocks. Prints the problems; exit 1 when there are any.

Usage: release-scope-check.py   (environment: GH_TOKEN, GITHUB_REPOSITORY, GITHUB_REF_NAME)
"""
import hashlib
import json
import os
import re
import subprocess
import sys
import urllib.request
from pathlib import Path

MANIFEST = "docs/plus/build15/release-scope.json"


def git(*args):
    return subprocess.run(["git", *args], capture_output=True, text=True)


def is_ancestor(commit, of="HEAD"):
    return git("merge-base", "--is-ancestor", commit, of).returncode == 0


class GitHub:
    def __init__(self, repository, token, base="https://api.github.com"):
        self.repository, self.token, self.base = repository, token, base

    def get(self, path):
        request = urllib.request.Request(
            f"{self.base}/repos/{self.repository}/{path}",
            headers={"Authorization": f"Bearer {self.token}", "Accept": "application/vnd.github+json",
                     "X-GitHub-Api-Version": "2022-11-28"},
        )
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)


def gate_mandatory(root):
    """MANDATORY of .github/plus/check-requirements.py, read without running the gate."""
    source = (Path(root) / ".github/plus/check-requirements.py").read_text()
    match = re.search(r"^MANDATORY = \[(.*?)^\]", source, re.S | re.M)
    if not match:
        return None
    return re.findall(r'"([A-Z0-9]+-\d+)"', match.group(1))


def portkit_version(root):
    """PortKit's version: the SHA-256 of portkit/portkit.py (a version line in it would change the file
    that PortKit's verified features were tested with)."""
    return hashlib.sha256((Path(root) / "portkit/portkit.py").read_bytes()).hexdigest()


def check(manifest, api, head, branch, root=".", ancestor=is_ancestor):
    problems = []
    if manifest.get("branch") != branch:
        problems.append(f"the manifest is for '{manifest.get('branch')}', this run is on '{branch}'")
    try:
        tip = api.get(f"branches/{branch}")["commit"]["sha"]
        if tip != head:
            problems.append(f"{branch} moved: its head is {tip[:8]}, this run publishes {head[:8]}")
    except Exception as error:  # noqa: BLE001 - unreadable means unconfirmed, which blocks
        problems.append(f"could not read the head of {branch} ({error})")

    for number in manifest.get("required_pull_requests", []):
        try:
            pull = api.get(f"pulls/{number}")
        except Exception as error:  # noqa: BLE001
            problems.append(f"#{number}: could not read it ({error})")
            continue
        if pull.get("base", {}).get("ref") != branch:
            problems.append(f"#{number}: targets '{pull.get('base', {}).get('ref')}', not {branch}")
        elif not pull.get("merged"):
            problems.append(f"#{number}: not merged")
        elif not pull.get("merge_commit_sha") or not ancestor(pull["merge_commit_sha"], head):
            problems.append(f"#{number}: merged, but its merge commit is not part of {head[:8]}")

    for number in manifest.get("excluded_pull_requests", {}):
        try:
            pull = api.get(f"pulls/{number}")
        except Exception as error:  # noqa: BLE001
            problems.append(f"#{number} (excluded): could not read it ({error})")
            continue
        if pull.get("merged"):
            problems.append(f"#{number} (excluded) is merged")
        elif pull.get("head", {}).get("sha") and ancestor(pull["head"]["sha"], head):
            problems.append(f"#{number} (excluded): its commits are part of {head[:8]}")

    wanted = manifest.get("approval_tests", {}).get("on_release_commit", [])
    if wanted:
        try:
            runs = api.get(f"actions/runs?head_sha={head}&per_page=100")["workflow_runs"]
        except Exception as error:  # noqa: BLE001
            runs = None
            problems.append(f"could not read the workflow runs of {head[:8]} ({error})")
        if runs is not None:
            for name in wanted:
                found = [r for r in runs if r.get("name") == name]
                if not found:
                    problems.append(f"'{name}' has not run on {head[:8]}")
                elif not any(r.get("conclusion") == "success" for r in found):
                    states = ", ".join(sorted({str(r.get("conclusion") or r.get("status")) for r in found}))
                    problems.append(f"'{name}' did not pass on {head[:8]} ({states})")

    enforced = gate_mandatory(root)
    listed = manifest.get("mandatory_requirements", [])
    if enforced is None:
        problems.append("the requirements gate's MANDATORY list could not be read")
    elif sorted(enforced) != sorted(listed):
        missing = sorted(set(enforced) - set(listed))
        extra = sorted(set(listed) - set(enforced))
        problems.append(f"mandatory requirements differ from the gate (missing {missing}, extra {extra})")

    version = portkit_version(root)
    if manifest.get("portkit_sha256") != version:
        problems.append(f"PortKit is {version[:12]}, the manifest pins {str(manifest.get('portkit_sha256'))[:12]}")
    return problems


def main():
    repository = os.environ.get("GITHUB_REPOSITORY", "")
    branch = os.environ.get("GITHUB_REF_NAME", "")
    token = os.environ.get("GH_TOKEN", "")
    if not (repository and branch and token):
        print("Blocked: GITHUB_REPOSITORY, GITHUB_REF_NAME and GH_TOKEN are required.")
        return 1
    head = git("rev-parse", "HEAD").stdout.strip()
    manifest = json.loads(Path(MANIFEST).read_text())
    problems = check(manifest, GitHub(repository, token), head, branch)
    for problem in problems:
        print(f"::error::Release scope: {problem}")
    if problems:
        print(f"Blocked: {len(problems)} release scope problem(s). Nothing is published.")
        return 1
    print(f"Release scope of Build {manifest.get('build')}: {len(manifest.get('required_pull_requests', []))} pull "
          f"requests merged and part of {head[:8]}, branch not moved, approval tests passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
