#!/usr/bin/env python3
"""Fail closed before a manually dispatched Build 16 release if any mandatory
pre-release requirement or owner-approved phase gate is still open.

The requirements validator checks whether evidence claims are truthful; this
additional release guard checks whether *all required claims are complete*.
The owner's personal POST-RELEASE VALIDATION checks remain explicitly open.
"""
import json
import sys
from pathlib import Path

PHASES = {f"P{n}" for n in range(10)}
COMPLETE = {"VERIFIED", "ACCEPTED"}
POST_RELEASE = "POST-RELEASE VALIDATION"
REGISTER = Path("docs/plus/build15/requirements.json")
GATES = Path("docs/plus/build15/stage-gates.json")


def check(root="."):
    root = Path(root)
    problems = []
    try:
        register = json.loads((root / REGISTER).read_text(encoding="utf-8"))
        phases = json.loads((root / GATES).read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        return [f"required readiness document cannot be read: {exc}"]

    items = register.get("items")
    gates = phases.get("gates")
    if not isinstance(items, list) or not isinstance(gates, list):
        return ["readiness documents are missing their items/gates lists"]

    for item in items:
        if not isinstance(item, dict) or not item.get("id"):
            problems.append("unidentified requirement in the register")
            continue
        if not item.get("mandatory"):
            continue
        if item.get("release_class") == POST_RELEASE:
            # Owner-approved personal device validation is never called PASS.
            continue
        if item.get("release_class") != "PRE-RELEASE REQUIRED":
            problems.append(f"{item['id']}: unresolved release classification {item.get('release_class')!r}")
        if item.get("status") not in COMPLETE:
            problems.append(f"{item['id']}: mandatory pre-release item is {item.get('status')!r}, not VERIFIED/ACCEPTED")

    by_phase = {}
    for gate in gates:
        if not isinstance(gate, dict) or gate.get("stage") not in PHASES:
            problems.append("unknown or malformed phase gate")
            continue
        name = gate["stage"]
        if name in by_phase:
            problems.append(f"{name}: duplicate phase gate")
        by_phase[name] = gate
        if gate.get("status") != "PASS":
            problems.append(f"{name}: phase gate remains {gate.get('status')!r}")
        approval = gate.get("owner_approval")
        if (not isinstance(approval, dict)
                or not all(str(approval.get(k) or "").strip() for k in ("by", "on", "ref"))):
            problems.append(f"{name}: missing explicit owner approval for phase closure")
    for missing in sorted(PHASES - by_phase.keys()):
        problems.append(f"{missing}: no phase gate")

    # No derived percentages are substituted for an individual requirement.
    return problems


def main():
    errors = check()
    for problem in errors:
        print(f"::error::Release readiness: {problem}")
    if errors:
        print(f"BLOCKED: {len(errors)} outstanding readiness condition(s); nothing may be published.")
        return 1
    print("Build 16 release readiness PASS: every mandatory pre-release item is verified/accepted,"
          " every P0-P9 gate is PASS with owner approval; personal post-release checks remain separate.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
