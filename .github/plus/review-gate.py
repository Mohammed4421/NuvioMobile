#!/usr/bin/env python3
"""Blocks publishing while a Nuvio merge review in .github/plus/reviews/ is not approved.

An approved record has `status: approved`, `approved_by:` filled in, every box ticked and a
`tested_commit:` that is part of the commit being published (the approval belongs to code whose
checks passed). Usage: review-gate.py
"""
import glob
import re
import subprocess
import sys

problems = []
for path in sorted(glob.glob(".github/plus/reviews/nuvio-*.md")):
    text = open(path).read()
    field = lambda name: (re.search(rf"^{name}:[ \t]*(\S.*)?$", text, re.M) or [None, None])[1] or ""
    status, tested, approver = field("status").strip(), field("tested_commit").strip(), field("approved_by").strip()
    if status == "rejected":
        continue
    if status != "approved":
        problems.append(f"{path}: status is '{status or 'missing'}'")
        continue
    if not approver:
        problems.append(f"{path}: approved_by is empty")
    open_boxes = re.findall(r"^\s*- \[ \]", text, re.M)
    if open_boxes:
        problems.append(f"{path}: {len(open_boxes)} review items are not ticked")
    if not re.fullmatch(r"[0-9a-f]{7,40}", tested):
        problems.append(f"{path}: tested_commit is not a commit id")
    elif subprocess.run(["git", "merge-base", "--is-ancestor", tested, "HEAD"]).returncode != 0:
        problems.append(f"{path}: tested_commit {tested} is not part of this commit")

if problems:
    print("Publishing is blocked by unfinished Nuvio reviews:")
    print("\n".join(f"  - {p}" for p in problems))
    sys.exit(1)
print("All Nuvio reviews are approved.")
