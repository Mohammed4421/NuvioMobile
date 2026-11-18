#!/usr/bin/env python3
"""Requires every Nuvio Plus unit test to pass.

The results of the Nuvio release the build is based on are only used to label each failure:
"also fails in Nuvio" (inherited) or "only in Nuvio Plus". Both block publishing.
Writes a summary to checks-tests.md.
Usage: test-gate.py <plus.json> <nuvio.json> <nuvio-tag>
"""
import json
import sys

plus = json.load(open(sys.argv[1]))
nuvio = json.load(open(sys.argv[2]))
tag = sys.argv[3]
failed = set(plus["failed"])
inherited = sorted(failed & set(nuvio["failed"]))
new = sorted(failed - set(nuvio["failed"]))
lines = [f"- Unit tests: {plus['total'] - len(failed)} of {plus['total']} passed"
         f" (Nuvio {tag} itself: {nuvio['total'] - len(nuvio['failed'])} of {nuvio['total']})."]
for title, names in (("Failing only in Nuvio Plus", new), (f"Failing in Nuvio Plus and in Nuvio {tag}", inherited)):
    if names:
        lines.append(f"  {title}:")
        lines += [f"  - `{name}`" for name in names]
open("checks-tests.md", "w").write("\n".join(lines) + "\n")
print("\n".join(lines))
if plus["total"] == 0:
    sys.exit("No unit tests ran.")
if failed:
    sys.exit(f"{len(failed)} unit tests failed; nothing is published until all pass.")
