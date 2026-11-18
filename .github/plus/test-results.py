#!/usr/bin/env python3
"""Summarises JUnit XML results as JSON: {"total": n, "failed": ["Class > method", ...]}.
Usage: test-results.py <results-dir> <out.json>
"""
import glob
import json
import sys
import xml.etree.ElementTree as ET

total = 0
failed = set()
for path in glob.glob(f"{sys.argv[1]}/**/*.xml", recursive=True):
    suite = ET.parse(path).getroot()
    for case in suite.iter("testcase"):
        total += 1
        if case.find("failure") is not None or case.find("error") is not None:
            name = case.get("name", "").split("[")[0]
            failed.add(f"{case.get('classname', '')} > {name}")
json.dump({"total": total, "failed": sorted(failed)}, open(sys.argv[2], "w"), indent=1)
print(f"{total} tests, {len(failed)} failing")
