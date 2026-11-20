#!/usr/bin/env python3
"""Every named test class ran (at least one test case in the JUnit XML results) and none failed.
Usage: check-test-classes.py <results dir> <class,class,...>. Prints one line per class; exit 1 if any
class did not run or has a failing test."""
import glob
import sys
import xml.etree.ElementTree as ET


def check(results, classes):
    ran, failed = {}, {}
    for path in glob.glob(f"{results}/**/*.xml", recursive=True):
        for case in ET.parse(path).getroot().iter("testcase"):
            name = case.get("classname", "")
            ran[name] = ran.get(name, 0) + 1
            if case.find("failure") is not None or case.find("error") is not None:
                failed[name] = failed.get(name, 0) + 1
    lines, ok = [], True
    for name in classes:
        if ran.get(name, 0) == 0:
            lines.append(f"{name}: did not run")
            ok = False
        elif failed.get(name):
            lines.append(f"{name}: {failed[name]} of {ran[name]} failed")
            ok = False
        else:
            lines.append(f"{name}: {ran[name]} passed")
    return ok, lines


if __name__ == "__main__":
    ok, lines = check(sys.argv[1], [c for c in sys.argv[2].split(",") if c])
    print("\n".join(lines))
    sys.exit(0 if ok else 1)
