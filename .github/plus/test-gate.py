#!/usr/bin/env python3
"""Requires every Nuvio Plus unit test to pass.

The results of the Nuvio release the build is based on are only used to label each failure:
"also fails in Nuvio" (inherited) or "only in Nuvio Plus". Both block publishing. They are produced
only when a Nuvio Plus test fails; without failures the file may be missing. Failures without them
still block (labelled "not compared").
Writes a summary to checks-tests.md.
Usage: test-gate.py <plus.json> <nuvio.json> <nuvio-tag>
"""
import json
import os
import sys

with open(sys.argv[1]) as file:
    plus = json.load(file)
tag = sys.argv[3]
compared = os.path.exists(sys.argv[2])
if compared:
    with open(sys.argv[2]) as file:
        nuvio = json.load(file)
else:
    nuvio = {"total": 0, "failed": []}
failed = set(plus["failed"])
inherited = sorted(failed & set(nuvio["failed"]))
new = sorted(failed - set(nuvio["failed"]))
baseline = (f"Nuvio {tag} itself: {nuvio['total'] - len(nuvio['failed'])} of {nuvio['total']}" if compared
            else "Nuvio baseline not run: no Nuvio Plus test failed" if not failed
            else "Nuvio baseline missing: failures not compared")
skipped = sorted(set(plus.get("skipped", [])))
lines = [f"- Unit tests: {plus['total'] - len(failed) - len(skipped)} of {plus['total']} passed"
         + (f", {len(skipped)} skipped (not run, not passed)" if skipped else "") + f" ({baseline})."]
new_title = "Failing only in Nuvio Plus" if compared else "Failing (not compared with Nuvio)"
for title, names in ((new_title, new), (f"Failing in Nuvio Plus and in Nuvio {tag}", inherited)):
    if names:
        lines.append(f"  {title}:")
        lines += [f"  - `{name}`" for name in names]
if skipped:
    lines.append("  Skipped (an assumption or @Ignore; not counted as passed):")
    lines += [f"  - `{name}`" for name in skipped[:50]]
with open("checks-tests.md", "w") as file:
    file.write("\n".join(lines) + "\n")
print("\n".join(lines))
print(f"::notice::{lines[0][2:]}")
if plus["total"] == 0:
    sys.exit("No unit tests ran.")
for name in new[:20]:
    print(f"::error::{'Fails only in Nuvio Plus' if compared else 'Fails'}: {name}")
for name in inherited[:20]:
    print(f"::error::Fails in Nuvio Plus and in Nuvio {tag}: {name}")
if failed:
    sys.exit(f"{len(failed)} unit tests failed; nothing is published until all pass.")
