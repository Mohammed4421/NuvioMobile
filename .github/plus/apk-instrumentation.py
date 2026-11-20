#!/usr/bin/env python3
"""Reads `aapt2 dump xmltree --file AndroidManifest.xml <test apk>` on stdin and prints
"<test package> <runner class> <target package>" for its single <instrumentation>.

The device test host is found from the APKs themselves, so a change of the debug application id
(it is com.nuviodebug.com, not com.nuvio.app) cannot silently point the update checks elsewhere.
Exits non-zero when the manifest has no package or not exactly one instrumentation."""
import re
import sys

VALUE = re.compile(r'="([^"]*)"')


def element_tree(lines):
    """Yields (depth, kind, text) where kind is E (element) or A (attribute)."""
    for line in lines:
        stripped = line.lstrip(" ")
        if stripped[:3] in ("E: ", "A: "):
            yield len(line) - len(stripped), stripped[0], stripped[3:]


def parse(text):
    package = None
    instrumentations = []
    current = None
    current_depth = -1
    for depth, kind, body in element_tree(text.splitlines()):
        if kind == "E":
            current = {} if body.split(" ")[0] == "instrumentation" else None
            current_depth = depth
            if current is not None:
                instrumentations.append(current)
            continue
        value = VALUE.search(body)
        if value is None:
            continue
        name = body.split("=", 1)[0]
        if name == "package" and package is None:
            package = value.group(1)
        elif current is not None and depth > current_depth:
            if name.endswith(":name(0x01010003)") or name == "android:name":
                current["runner"] = value.group(1)
            elif name.endswith(":targetPackage(0x01010021)") or name == "android:targetPackage":
                current["target"] = value.group(1)
    if not package:
        raise ValueError("the test APK's manifest has no package")
    if len(instrumentations) != 1 or not {"runner", "target"} <= instrumentations[0].keys():
        raise ValueError(f"expected one instrumentation with a runner and a target, found {instrumentations}")
    runner = instrumentations[0]["runner"]
    if runner.startswith("."):
        runner = package + runner
    return package, runner, instrumentations[0]["target"]


if __name__ == "__main__":
    try:
        print(*parse(sys.stdin.read()))
    except ValueError as error:
        print(f"::error::{error}", file=sys.stderr)
        sys.exit(1)
