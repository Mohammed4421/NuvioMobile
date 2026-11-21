#!/usr/bin/env python3
"""Summarises JUnit XML results as JSON: {"total": n, "failed": [...], "skipped": [...]} ("Class > method").
A skipped test (an assumption or @Ignore) is listed, never counted as passed.
Usage: test-results.py <results-dir> <out.json>
"""
import glob
import json
import sys
import xml.etree.ElementTree as ET

total = 0
failed = set()
skipped = set()
for path in glob.glob(f"{sys.argv[1]}/**/*.xml", recursive=True):
    suite = ET.parse(path).getroot()
    for case in suite.iter("testcase"):
        total += 1
        if case.find("failure") is not None or case.find("error") is not None:
            name = case.get("name", "").split("[")[0]
            failed.add(f"{case.get('classname', '')} > {name}")
        elif case.find("skipped") is not None:
            skipped.add(f"{case.get('classname', '')} > {case.get('name', '').split('[')[0]}")
json.dump({"total": total, "failed": sorted(failed), "skipped": sorted(skipped)}, open(sys.argv[2], "w"), indent=1)
print(f"{total} tests, {len(failed)} failing, {len(skipped)} skipped")
