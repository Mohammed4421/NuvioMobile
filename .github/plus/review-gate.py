#!/usr/bin/env python3
"""Nuvio merge review gate.

Two checks, run separately:

  review-gate.py records
      The review records of the Nuvio releases this commit brings in are complete. The required
      records are worked out from Git, not from the files present, so deleting a record does not
      skip it: the newest Nuvio release merged since BASELINE needs .github/plus/reviews/nuvio-<tag>.md
      with `status: approved` and every box ticked. A record marked `rejected` blocks while its
      release is part of the commit. Runs on every pull request as the `nuvio-plus/review` status.

  review-gate.py publish
      Everything above, plus proof from GitHub (not from text in the repository) that the commit
      being published arrived through a reviewed pull request:
        * HEAD is the merge commit of a pull request into this branch, merged by an approver;
        * the pull request head passed `nuvio-plus/checks` and `nuvio-plus/review`, statuses that
          only this repository's GitHub Actions can set;
        * for each required record, the pull request that added it was merged the same way and
          the record has not changed since.
      Anything that cannot be confirmed blocks publishing.

Environment (publish): GH_TOKEN, GITHUB_REPOSITORY, GITHUB_REF_NAME, PLUS_APPROVERS (comma-separated
GitHub logins, default: the repository owner). Upstream tags must be fetched (git fetch upstream --tags).
"""
import glob
import json
import os
import re
import subprocess
import sys
import urllib.request

# custom-12, the last commit published before reviews were required. Nuvio releases it already
# contains need no record.
BASELINE = "28a3e4dd6e615d2ce8665ff8469ec0be6aea1ab9"
REVIEWS = ".github/plus/reviews"
REQUIRED_STATUSES = ("nuvio-plus/checks", "nuvio-plus/review")
STATUS_CREATOR = "github-actions[bot]"
TAG = re.compile(r"^v?(\d+)\.(\d+)\.(\d+)(?:-(beta|rc)\.?(\d*))?$")


def git(*args, check=True):
    return subprocess.run(["git", *args], check=check, capture_output=True, text=True).stdout.strip()


def is_ancestor(commit, of="HEAD"):
    return subprocess.run(["git", "merge-base", "--is-ancestor", commit, of], capture_output=True).returncode == 0


def version_key(tag):
    major, minor, patch, kind, number = TAG.match(tag).groups()
    return int(major), int(minor), int(patch), {"beta": 0, "rc": 1, None: 2}[kind], int(number or 0)


def merged_upstream_tags():
    """Nuvio release tags contained in HEAD but not in BASELINE, newest first."""
    tags = [t for t in git("tag", "--merged", "HEAD").split() if TAG.match(t)]
    new = [t for t in tags if not is_ancestor(f"{t}^{{commit}}", BASELINE)]
    return sorted(new, key=version_key, reverse=True)


def read_record(tag):
    path = f"{REVIEWS}/nuvio-{tag}.md"
    if not os.path.exists(path):
        return path, None
    with open(path, encoding="utf-8") as file:
        return path, file.read()


def field(text, name):
    match = re.search(rf"^{name}:[ \t]*(\S.*)?$", text, re.M)
    return (match.group(1) or "").strip() if match else ""


def check_records():
    """Returns (problems, required records as (tag, path))."""
    problems, required = [], []
    if not any(TAG.match(t) for t in git("tag", "--merged", BASELINE).split()):
        return ["Nuvio release tags are missing (fetch them first): nothing can be checked"], required
    tags = merged_upstream_tags()
    if not tags:
        return problems, required
    newest = tags[0]
    for tag in tags:
        path, text = read_record(tag)
        if text is None:
            if tag == newest:
                problems.append(f"{path}: missing (Nuvio {tag} is part of this commit and has no review)")
            continue
        status = field(text, "status")
        if status == "rejected":
            problems.append(f"{path}: Nuvio {tag} was rejected, but it is part of this commit")
            continue
        # Older releases may have no record (the newest covers them); one that exists must be finished.
        if status != "approved":
            problems.append(f"{path}: status is '{status or 'missing'}'")
            continue
        open_boxes = re.findall(r"^\s*- \[ \]", text, re.M)
        if open_boxes:
            problems.append(f"{path}: {len(open_boxes)} review items are not ticked")
        required.append((tag, path))
    # Records of releases that are not part of this commit are ignored, whatever they say.
    return problems, required


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


def merged_pull_request(api, commit, branch, approvers, merge_commit=None):
    """The merged pull request into `branch` associated with `commit`, merged by an approver."""
    candidates = []
    for pull in api.get(f"commits/{commit}/pulls"):
        if pull.get("base", {}).get("ref") != branch or not pull.get("merged_at"):
            continue
        if merge_commit and pull.get("merge_commit_sha") != merge_commit:
            continue
        candidates.append(pull)
    problem = f"no merged pull request into {branch} contains {commit[:8]}"
    for pull in candidates:
        # The list endpoint does not always carry merged_by: read the pull request itself.
        full = api.get(f"pulls/{pull['number']}")
        login = (full.get("merged_by") or {}).get("login", "")
        if full.get("merged") and login in approvers:
            return full, None
        problem = f"pull request #{pull['number']} was merged by '{login or 'unknown'}', not an approver"
    return None, problem


def statuses_ok(api, sha):
    """Problems with the required statuses of `sha` (latest status per context)."""
    latest = {}
    for status in api.get(f"commits/{sha}/statuses?per_page=100"):
        latest.setdefault(status["context"], status)  # newest first
    problems = []
    for context in REQUIRED_STATUSES:
        status = latest.get(context)
        if status is None:
            problems.append(f"{sha[:8]} has no '{context}' status")
        elif status.get("state") != "success":
            problems.append(f"{sha[:8]}: '{context}' is {status.get('state')}")
        elif (status.get("creator") or {}).get("login") != STATUS_CREATOR:
            problems.append(f"{sha[:8]}: '{context}' was not set by GitHub Actions")
    return problems


def check_publish(api, branch, approvers, required):
    problems = []
    head = git("rev-parse", "HEAD")
    pull, problem = merged_pull_request(api, head, branch, approvers, merge_commit=head)
    if problem:
        problems.append(f"HEAD {head[:8]}: {problem}")
    else:
        problems += [f"pull request #{pull['number']}: {p}" for p in statuses_ok(api, pull["head"]["sha"])]
    for tag, path in required:
        added = git("log", "--diff-filter=A", "--format=%H", "-n", "1", "--", path)
        if not added:
            problems.append(f"{path}: not found in history")
            continue
        record_pull, problem = merged_pull_request(api, added, branch, approvers)
        if problem:
            problems.append(f"{path}: {problem}")
            continue
        pr_head = record_pull["head"]["sha"]
        if not is_ancestor(pr_head):
            problems.append(f"{path}: pull request #{record_pull['number']} head is not part of this commit")
            continue
        if subprocess.run(["git", "diff", "--quiet", pr_head, "HEAD", "--", path]).returncode != 0:
            problems.append(f"{path}: changed after pull request #{record_pull['number']} was approved")
        problems += [f"{path}: {p}" for p in statuses_ok(api, pr_head)]
    return problems


def main(argv):
    mode = argv[1] if len(argv) > 1 else ""
    if mode not in ("records", "publish"):
        print(__doc__)
        return 2
    if not is_ancestor(BASELINE):
        print(f"Blocked: this commit does not contain the baseline {BASELINE[:8]}.")
        return 1
    problems, required = check_records()
    if mode == "publish" and not problems:
        repository = os.environ.get("GITHUB_REPOSITORY", "")
        branch = os.environ.get("GITHUB_REF_NAME", "")
        token = os.environ.get("GH_TOKEN", "")
        approvers = {a.strip() for a in os.environ.get("PLUS_APPROVERS", repository.split("/")[0]).split(",") if a.strip()}
        if not (repository and branch and token and approvers):
            problems.append("GITHUB_REPOSITORY, GITHUB_REF_NAME, GH_TOKEN and an approver are required")
        else:
            try:
                problems += check_publish(GitHub(repository, token), branch, approvers, required)
            except Exception as error:  # Unconfirmed means blocked.
                problems.append(f"GitHub could not confirm the review: {error}")
    if problems:
        print("Blocked:")
        print("\n".join(f"  - {p}" for p in problems))
        return 1
    print(f"Reviews complete ({mode}): {', '.join(t for t, _ in required) or 'no Nuvio release since the baseline'}.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
