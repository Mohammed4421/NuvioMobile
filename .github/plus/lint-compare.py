#!/usr/bin/env python3
"""Build 15, P8-01: Android lint of the head against Build 14. Reads both lint XML reports and
matches findings by (issue id, file relative to its checkout, message with numbers removed), so a
finding that only moved lines is not new. Prints counts per severity, the new and the fixed findings
(Markdown) and a JSON line. Exit 1 if the head adds a finding of severity Fatal or Error, or any
finding in the Security category: those must be fixed or reviewed before merging.
With --detekt the reports are detekt's (checkstyle XML) and nothing blocks: the new findings are
listed for review (detekt has no severity that means "wrong", only rule sets).
Usage: lint-compare.py [--detekt] <build 14 xml> <build 14 root> <head xml> <head root>"""
import collections
import json
import re
import sys
import xml.etree.ElementTree as ET

BLOCKING = {"Fatal", "Error"}


def findings(path, root):
    root = root.rstrip("/") + "/"
    out = []
    for issue in ET.parse(path).getroot().iter("issue"):
        loc = issue.find("location")
        file = (loc.get("file", "") if loc is not None else "").replace(root, "")
        # Library jars sit in Gradle's transform cache under a hash that changes between builds.
        file = re.sub(r"^.*/\.gradle/caches/[^/]+/transforms/[0-9a-f]+/", "<gradle-cache>/", file)
        line = loc.get("line") if loc is not None else None
        out.append({"id": issue.get("id"), "severity": issue.get("severity"), "category": issue.get("category", ""),
                    "message": issue.get("message", ""), "file": file, "line": line,
                    "key": (issue.get("id"), file, re.sub(r"\d+", "#", issue.get("message", "")))})
    return out


def detekt_findings(path, root):
    root = root.rstrip("/") + "/"
    out = []
    for file in ET.parse(path).getroot().iter("file"):
        name = file.get("name", "").replace(root, "")
        for error in file.iter("error"):
            rule = error.get("source", "").removeprefix("detekt.")
            message = error.get("message", "")
            out.append({"id": rule, "severity": error.get("severity", ""), "category": "detekt", "message": message,
                        "file": name, "line": error.get("line"), "key": (rule, name, re.sub(r"\d+", "#", message))})
    return out


def compare(base, head):
    remaining = collections.Counter(f["key"] for f in base)
    new = []
    for f in head:
        if remaining[f["key"]] > 0:
            remaining[f["key"]] -= 1
        else:
            new.append(f)
    head_left = collections.Counter(f["key"] for f in head)
    fixed = []
    for f in base:
        if head_left[f["key"]] > 0:
            head_left[f["key"]] -= 1
        else:
            fixed.append(f)
    blocking = [f for f in new if f["severity"] in BLOCKING or "Security" in f["category"]]
    return new, fixed, blocking


def main():
    args = sys.argv[1:]
    detekt = args[0] == "--detekt"
    if detekt:
        args = args[1:]
    read = detekt_findings if detekt else findings
    base, head = read(args[0], args[1]), read(args[2], args[3])
    new, fixed, blocking = compare(base, head)
    if detekt:
        blocking = []
    counts = lambda fs: dict(sorted(collections.Counter(f["id" if detekt else "severity"] for f in fs).most_common(15)))
    print(f"Build 14: {len(base)} findings {counts(base)}; head: {len(head)} {counts(head)}; "
          f"new {len(new)}, fixed {len(fixed)}, blocking {len(blocking)}")
    # Where the findings are (top two path parts), so the report shows which modules lint covered.
    where = collections.Counter("/".join(f["file"].split("/")[:2]) or "(no file)" for f in head)
    print("\n#### Head findings by location\n")
    for place, n in sorted(where.items()):
        print(f"- {place}: {n}")
    print("\n#### All head findings\n")
    for f in ([] if detekt else head):
        print(f"- {f['severity']} `{f['id']}` ({f['category']}) {f['file']}:{f['line']}: {f['message']}")
    for title, items in (("New in the head", new), ("Fixed since Build 14", fixed)):
        print(f"\n#### {title} ({len(items)})\n")
        for f in items[:200]:
            print(f"- {f['severity']} `{f['id']}` ({f['category']}) {f['file']}:{f['line']}: {f['message']}")
    print("LINT " + json.dumps({"base": len(base), "head": len(head), "new": len(new), "fixed": len(fixed),
                                "blocking": [{k: f[k] for k in ("id", "severity", "file", "line", "message")} for f in blocking]}))
    for f in blocking:
        print(f"::error file={f['file']},line={f['line'] or 1}::lint {f['id']} ({f['severity']}, {f['category']}): {f['message']}")
    return 1 if blocking else 0


if __name__ == "__main__":
    sys.exit(main())
