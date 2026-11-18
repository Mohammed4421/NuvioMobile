#!/usr/bin/env python3
"""Fails when Nuvio Plus has a failing unit test that passes in the Nuvio release it is based on.

Tests that also fail in that Nuvio release are reported but do not block: they are not caused by
Nuvio Plus. Writes a summary line to checks-tests.md.
Usage: test-gate.py <plus.json> <nuvio.json> <nuvio-tag>
"""
import json
import sys

plus = json.load(open(sys.argv[1]))
nuvio = json.load(open(sys.argv[2]))
tag = sys.argv[3]
inherited = sorted(set(plus["failed"]) & set(nuvio["failed"]))
new = sorted(set(plus["failed"]) - set(nuvio["failed"]))
lines = [f"- Unit tests: {plus['total'] - len(plus['failed'])} of {plus['total']} passed."]
if inherited:
    lines.append(f"  {len(inherited)} also fail in Nuvio {tag} itself, so they are not caused by Nuvio Plus:")
    lines += [f"  - `{name}`" for name in inherited]
open("checks-tests.md", "w").write("\n".join(lines) + "\n")
print("\n".join(lines))
if plus["total"] == 0:
    sys.exit("No unit tests ran.")
if new:
    print("Failing only in Nuvio Plus:")
    print("\n".join(f"  {name}" for name in new))
    sys.exit(1)
