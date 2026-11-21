#!/usr/bin/env python3
"""Build 15, P6-01/P6-03/P6-05: the release APK's attack surface against the reviewed list.
Reads the APK (apk-manifest.py) and reduces it to lines: application flags, permissions requested,
exported components (with the permission guarding them and their intent filters), content providers,
FileProvider paths and the network security config. Each line must be in
docs/plus/build15/security-surface.json with the reason it was accepted; a new line (new exported
component, permission, shared path, cleartext rule) or an approved line that disappeared fails, so
every change of the surface is reviewed. Fixed rules, whatever the list says: not debuggable, no
backup of app data, no FileProvider root-path or whole-directory share, and no exported provider.
Usage: security-surface.py <apk> [<approved json>]   (exit 1 on any difference)"""
import importlib.util
import json
import sys
import zipfile
from pathlib import Path

here = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("apk_manifest", here / "apk-manifest.py")
apk_manifest = importlib.util.module_from_spec(spec)
spec.loader.exec_module(apk_manifest)


def surface(m):
    lines = [f"package {m['package']}"]
    for k, v in sorted(m["application"].items()):
        if k != "networkSecurityConfig":
            lines.append(f"application {k}={str(v).lower()}")
    lines += [f"permission {p}" for p in sorted(set(m["uses-permission"]))]
    for c in m["components"]:
        if c["type"] == "provider":
            lines.append(f"provider {c['name']} authorities={c.get('authorities')} exported={str(c['effective_exported']).lower()}"
                         f" grantUriPermissions={str(c.get('grantUriPermissions', False)).lower()}")
        elif c["effective_exported"]:
            filters = sorted({",".join(f["actions"]) + (" " + ",".join(sorted(set(f["schemes"]))) if f["schemes"] else "")
                              + (" " + ",".join(sorted(set(f["hosts"]))) if f["hosts"] else "") for f in c["intent_filters"]})
            lines.append(f"exported {c['type']} {c['name']} permission={c['permission']}"
                         + (f" filters=[{'; '.join(filters)}]" if filters else ""))
    for p in m.get("file_provider_paths", []):
        lines += [f"fileprovider-path {e}" for e in p["entries"]]
    for n in m.get("network_security_config", []):
        lines += [f"network {e}" for e in n["entries"]]
    return sorted(set(lines))


def rule_problems(m):
    problems = []
    if m["application"].get("debuggable") is True:
        problems.append("the APK is debuggable")
    if m["application"].get("allowBackup") is not False:
        problems.append("allowBackup is not false (app data could be copied by backup)")
    for c in m["components"]:
        if c["type"] == "provider" and c["effective_exported"]:
            problems.append(f"exported provider {c['name']}")
    for p in m.get("file_provider_paths", []):
        for e in p["entries"]:
            tag, _, path = e.partition(" ")
            if tag == "root-path" or path.strip("/. ") == "":
                problems.append(f"FileProvider shares a whole directory: {e} ({p['file']})")
    return problems


def compare(m, approved):
    found = surface(m)
    listed = {item["line"]: item for item in approved["surface"]}
    problems = rule_problems(m)
    problems += [f"not reviewed: {line}" for line in found if line not in listed]
    problems += [f"reviewed but no longer in the APK (update the list): {line}" for line in listed if line not in found]
    problems += [f"no reason given: {line}" for line, item in listed.items() if not item.get("reason", "").strip()]
    return found, problems


def main():
    approved_path = Path(sys.argv[2]) if len(sys.argv) > 2 else here.parents[1] / "docs/plus/build15/security-surface.json"
    with zipfile.ZipFile(sys.argv[1]) as apk:
        m = apk_manifest.read_manifest(apk.read("AndroidManifest.xml"))
        m["file_provider_paths"], m["network_security_config"] = apk_manifest.read_resource_xml(apk)
    found, problems = compare(m, json.loads(approved_path.read_text(encoding="utf-8")))
    print(f"{len(found)} surface lines")
    for line in found:
        print(f"  {line}")
    for p in problems:
        print(f"::error::Security surface: {p}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
