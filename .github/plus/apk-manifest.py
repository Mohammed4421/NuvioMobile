#!/usr/bin/env python3
"""Reads the compiled AndroidManifest.xml of an APK (binary XML; no Android tools needed) and prints
its components as JSON: for each activity, activity-alias, service, receiver and provider its name,
android:exported as declared, whether it has intent filters (and their actions and data schemes),
the permission guarding it, and for providers the authorities, grantUriPermissions and FileProvider
paths resource. Also the package, permissions requested, and application flags (debuggable,
allowBackup, usesCleartextTraffic, networkSecurityConfig).
Usage: apk-manifest.py <apk>"""
import json
import struct
import sys
import zipfile

ANDROID = "http://schemas.android.com/apk/res/android"
# Framework attribute resource ids, for manifests whose attribute names were stripped.
ATTR_IDS = {0x01010003: "name", 0x01010010: "exported", 0x01010006: "permission", 0x01010018: "authorities",
            0x0101001b: "grantUriPermissions", 0x01010025: "resource", 0x0101000f: "debuggable",
            0x01010280: "allowBackup", 0x010104ec: "usesCleartextTraffic", 0x01010527: "networkSecurityConfig",
            0x01010024: "value", 0x01010027: "scheme", 0x01010028: "host", 0x0101000e: "enabled",
            0x01010009: "readPermission", 0x0101000a: "writePermission", 0x01010021: "package"}
TYPE_REFERENCE, TYPE_STRING, TYPE_BOOLEAN = 0x01, 0x03, 0x12


class Malformed(Exception):
    pass


def _strings(data, at):
    _, header, _size, count, _styles, flags, start, _ = struct.unpack_from("<HHIIIIII", data, at)
    utf8 = flags & 0x100
    offsets = struct.unpack_from(f"<{count}I", data, at + header)
    out = []
    for off in offsets:
        p = at + start + off
        if utf8:
            n = data[p]; p += 2 if n & 0x80 else 1
            n = data[p]; m = n & 0x7f
            if n & 0x80:
                m = (m << 8) | data[p + 1]; p += 2
            else:
                p += 1
            out.append(data[p:p + m].decode("utf-8", "replace"))
        else:
            (n,) = struct.unpack_from("<H", data, p); p += 2
            if n & 0x8000:
                (lo,) = struct.unpack_from("<H", data, p); n = ((n & 0x7fff) << 16) | lo; p += 2
            out.append(data[p:p + 2 * n].decode("utf-16-le", "replace"))
    return out


def parse_xml(data):
    """Yields ("start", tag, {attribute: value}) and ("end", tag, None) events."""
    if len(data) < 8 or struct.unpack_from("<H", data, 0)[0] != 0x0003:
        raise Malformed("not a binary XML file")
    strings, res_ids, at = [], [], 8
    while at + 8 <= len(data):
        kind, header, size = struct.unpack_from("<HHI", data, at)
        if size < 8 or at + size > len(data):
            raise Malformed(f"bad chunk at {at}")
        if kind == 0x0001:
            strings = _strings(data, at)
        elif kind == 0x0180:
            res_ids = list(struct.unpack_from(f"<{(size - header) // 4}I", data, at + header))
        elif kind == 0x0102:
            _ns, name, attr_start, attr_size, count = struct.unpack_from("<IIHHH", data, at + 16)
            attrs = {}
            for i in range(count):
                a = at + 16 + attr_start + i * attr_size
                ns, aname, raw, _s, _r, dtype, value = struct.unpack_from("<IIIHBBI", data, a)
                key = strings[aname] if aname < len(strings) else ""
                if aname < len(res_ids) and res_ids[aname] in ATTR_IDS:
                    key = ATTR_IDS[res_ids[aname]]
                if raw != 0xFFFFFFFF and raw < len(strings):
                    val = strings[raw]
                elif dtype == TYPE_BOOLEAN:
                    val = value != 0
                elif dtype == TYPE_REFERENCE:
                    val = f"@0x{value:08x}"
                elif dtype == TYPE_STRING:
                    val = strings[value]
                else:
                    val = value
                attrs[key] = val
            yield "start", strings[name], attrs
        elif kind == 0x0103:
            (name,) = struct.unpack_from("<I", data, at + 20)
            yield "end", strings[name], None
        at += size


COMPONENTS = {"activity", "activity-alias", "service", "receiver", "provider"}


def read_manifest(data):
    result = {"package": None, "application": {}, "uses-permission": [], "components": []}
    current, stack = None, []
    for event, tag, attrs in parse_xml(data):
        if event == "end":
            if stack:
                stack.pop()
            if tag in COMPONENTS:
                current = None
            continue
        stack.append(tag)
        if tag == "manifest":
            result["package"] = attrs.get("package")
        elif tag == "application":
            result["application"] = {k: attrs[k] for k in ("debuggable", "allowBackup", "usesCleartextTraffic",
                                                           "networkSecurityConfig") if k in attrs}
        elif tag == "uses-permission":
            result["uses-permission"].append(attrs.get("name"))
        elif tag in COMPONENTS:
            current = {"type": tag, "name": attrs.get("name"), "exported": attrs.get("exported"),
                       "permission": attrs.get("permission"), "intent_filters": []}
            if attrs.get("enabled") is False:
                current["enabled"] = False
            if tag == "provider":
                for k in ("authorities", "grantUriPermissions", "readPermission", "writePermission"):
                    if k in attrs:
                        current[k] = attrs[k]
            result["components"].append(current)
        elif current is not None and tag == "intent-filter":
            current["intent_filters"].append({"actions": [], "schemes": [], "hosts": []})
        elif current is not None and current["intent_filters"] and "intent-filter" in stack:
            f = current["intent_filters"][-1]
            if tag == "action":
                f["actions"].append(attrs.get("name"))
            elif tag == "data":
                if "scheme" in attrs: f["schemes"].append(attrs["scheme"])
                if "host" in attrs: f["hosts"].append(attrs["host"])
        elif current is not None and tag == "meta-data" and current["type"] == "provider":
            if attrs.get("name") == "android.support.FILE_PROVIDER_PATHS":
                current["file_provider_paths"] = attrs.get("resource")
    for c in result["components"]:
        # Android 12+ requires exported to be declared on components with intent filters; before,
        # a filter made a component exported by default (providers: exported only when declared).
        c["effective_exported"] = c["exported"] if c["exported"] is not None else (
            bool(c["intent_filters"]) and c["type"] != "provider")
    return result


def read_resource_xml(apk):
    """FileProvider path lists and network security configs among the compiled XML resources (found
    by their root element: resource file names may be shortened by the build)."""
    paths, network = [], []
    for name in sorted(n for n in apk.namelist() if n.startswith("res/") and n.endswith(".xml")):
        try:
            events = list(parse_xml(apk.read(name)))
        except (Malformed, struct.error, IndexError):
            continue
        if not events:
            continue
        root = events[0][1]
        if root == "paths":
            paths.append({"file": name, "entries": sorted(
                f"{tag} {a.get('path', '')}" for e, tag, a in events[1:] if e == "start")})
        elif root == "network-security-config":
            config = []
            for e, tag, a in events[1:]:
                if e == "start" and tag in ("base-config", "domain-config", "domain", "debug-overrides"):
                    entry = tag
                    if "cleartextTrafficPermitted" in a:
                        entry += f" cleartext={str(a['cleartextTrafficPermitted']).lower()}"
                    config.append(entry)
            network.append({"file": name, "entries": config})
    return paths, network


def main():
    with zipfile.ZipFile(sys.argv[1]) as apk:
        result = read_manifest(apk.read("AndroidManifest.xml"))
        result["file_provider_paths"], result["network_security_config"] = read_resource_xml(apk)
        print(json.dumps(result, indent=1, sort_keys=True))


if __name__ == "__main__":
    main()
