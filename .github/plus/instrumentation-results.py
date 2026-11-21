#!/usr/bin/env python3
"""Reads `am instrument -r` output on stdin and reports each test. Exit 0 only when at least one test
ran, every test passed, and the run finished: a failure, an error, a test skipped by an assumption or
ignored, a crash of the instrumentation, or an empty run all fail (a skipped test is not a pass).
Usage: instrumentation-results.py <label> < output"""
import re
import sys

CODES = {0: "passed", -1: "error", -2: "failed", -3: "ignored", -4: "skipped (assumption)"}


def parse(text):
    results, current, status = {}, {}, None
    for line in text.splitlines():
        m = re.match(r"INSTRUMENTATION_STATUS: (class|test)=(.*)", line)
        if m:
            current[m.group(1)] = m.group(2).strip()
            continue
        m = re.match(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)", line)
        if m:
            code = int(m.group(1))
            name = f"{current.get('class', '?').rsplit('.', 1)[-1]}.{current.get('test', '?')}"
            if code != 1:  # 1 marks a test starting
                results[name] = code
            continue
        m = re.match(r"INSTRUMENTATION_CODE: (-?\d+)", line)
        if m:
            status = int(m.group(1))
    finished = status == -1 and "INSTRUMENTATION_FAILED" not in text and "Process crashed" not in text
    return results, finished


def main():
    label = sys.argv[1] if len(sys.argv) > 1 else "device tests"
    text = sys.stdin.read()
    results, finished = parse(text)
    for name, code in sorted(results.items()):
        print(f"{label}: {name}: {CODES.get(code, f'code {code}')}")
    problems = []
    if not results:
        problems.append("no test ran")
    if not finished:
        problems.append("the instrumentation did not finish normally")
    problems += [f"{n} {CODES.get(c, c)}" for n, c in results.items() if c != 0]
    if problems:
        for p in problems:
            print(f"::error::{label}: {p}")
        for line in text.splitlines():
            if re.search(r"Exception|Error:|at com\.nuvio|INSTRUMENTATION_FAILED|Process crashed", line):
                print(f"  {line[:300]}")
        return 1
    print(f"{label}: all {len(results)} test(s) passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
