#!/usr/bin/env python3
"""Prints the newest Nuvio release tag for a channel.

stable: plain versions only (1.2.0). beta: also -beta/-rc versions (1.2.0-beta.1).
Usage: git ls-remote --tags upstream | select-upstream-tag.py <stable|beta>
       git tag --merged HEAD | select-upstream-tag.py beta
"""
import re
import sys

channel = sys.argv[1]
pattern = re.compile(r"^v?(\d+)\.(\d+)\.(\d+)(?:-(beta|rc)\.?(\d*))?$")
candidates = []
for line in sys.stdin:
    if not line.strip():
        continue
    ref = line.split()[-1]
    if ref.endswith("^{}"):
        continue
    # Accepts `git ls-remote --tags` lines and plain tag names (`git tag --merged`).
    tag = ref[len("refs/tags/"):] if ref.startswith("refs/tags/") else ref
    match = pattern.match(tag)
    if not match:
        continue
    major, minor, patch, kind, number = match.groups()
    if kind and channel != "beta":
        continue
    # A stable version sorts after its betas and release candidates.
    stage = {"beta": 0, "rc": 1, None: 2}[kind]
    candidates.append(((int(major), int(minor), int(patch), stage, int(number or 0)), tag))
if candidates:
    print(max(candidates)[1])
