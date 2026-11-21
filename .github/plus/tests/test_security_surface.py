"""Tests of apk-manifest.py and security-surface.py on small compiled manifests made here."""
import importlib.util
import io
import struct
import unittest
import zipfile
from pathlib import Path

here = Path(__file__).resolve().parents[1]


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, here / file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


am = load("apk_manifest", "apk-manifest.py")
ss = load("security_surface", "security-surface.py")
NS = "http://schemas.android.com/apk/res/android"


def axml(tree):
    """Compiles (tag, {attr: value}, [children]) to Android binary XML (UTF-16 string pool).
    Values: str, bool (typed boolean) or ("ref", int)."""
    strings = [NS]
    def s(x):
        if x not in strings:
            strings.append(x)
        return strings.index(x)
    body = []
    def walk(node):
        tag, attrs, children = node
        raw = []
        for k, v in attrs.items():
            if isinstance(v, bool):
                raw.append(struct.pack("<IIIHBBI", 0xFFFFFFFF, s(k), 0xFFFFFFFF, 8, 0, 0x12, 0xFFFFFFFF if v else 0))
            elif isinstance(v, tuple):
                raw.append(struct.pack("<IIIHBBI", 0xFFFFFFFF, s(k), 0xFFFFFFFF, 8, 0, 0x01, v[1]))
            else:
                raw.append(struct.pack("<IIIHBBI", 0xFFFFFFFF, s(k), s(v), 8, 0, 0x03, s(v)))
        start = struct.pack("<IIIIHHHHHH", 0, 0xFFFFFFFF, 0xFFFFFFFF, s(tag), 20, 20, len(raw), 0, 0, 0) + b"".join(raw)
        body.append(struct.pack("<HHI", 0x0102, 16, 8 + len(start)) + start)
        for c in children:
            walk(c)
        end = struct.pack("<IIII", 0, 0xFFFFFFFF, 0xFFFFFFFF, s(tag))
        body.append(struct.pack("<HHI", 0x0103, 16, 8 + len(end)) + end)
    walk(tree)
    data = b""; offsets = []
    for x in strings:
        offsets.append(len(data))
        data += struct.pack("<H", len(x)) + x.encode("utf-16-le") + b"\0\0"
    while len(data) % 4:
        data += b"\0"
    header = 28
    pool_body = struct.pack(f"<{len(strings)}I", *offsets) + data
    pool = struct.pack("<HHIIIIII", 0x0001, header, header + len(pool_body), len(strings), 0, 0, header + 4 * len(strings), 0) + pool_body
    rest = pool + b"".join(body)
    return struct.pack("<HHI", 0x0003, 8, 8 + len(rest)) + rest


def apk(manifest, resources=()):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("AndroidManifest.xml", axml(manifest))
        for name, tree in resources:
            z.writestr(name, axml(tree))
    buf.seek(0)
    return zipfile.ZipFile(buf)


def manifest(app_attrs=None, components=()):
    return ("manifest", {"package": "com.example"}, [
        ("uses-permission", {"name": "android.permission.INTERNET"}, []),
        ("application", app_attrs if app_attrs is not None else {"allowBackup": False}, list(components))])


def read(z):
    m = am.read_manifest(z.read("AndroidManifest.xml"))
    m["file_provider_paths"], m["network_security_config"] = am.read_resource_xml(z)
    return m


VIEW = ("intent-filter", {}, [("action", {"name": "android.intent.action.VIEW"}, []), ("data", {"scheme": "demo"}, [])])
PATHS = ("paths", {}, [("cache-path", {"name": "a", "path": "updates/"}, [])])


class Parse(unittest.TestCase):
    def test_reads_components_filters_and_flags(self):
        m = read(apk(manifest(components=[("activity", {"name": "A", "exported": True}, [VIEW]),
                                          ("service", {"name": "S", "exported": False}, [])])))
        self.assertEqual("com.example", m["package"])
        self.assertEqual(["android.permission.INTERNET"], m["uses-permission"])
        a, s = m["components"]
        self.assertEqual((True, ["demo"]), (a["effective_exported"], a["intent_filters"][0]["schemes"]))
        self.assertFalse(s["effective_exported"])

    def test_an_intent_filter_without_exported_counts_as_exported(self):
        m = read(apk(manifest(components=[("receiver", {"name": "R"}, [VIEW])])))
        self.assertTrue(m["components"][0]["effective_exported"])

    def test_file_provider_paths_are_found_by_their_root_element(self):
        m = read(apk(manifest(), resources=[("res/x1.xml", PATHS)]))
        self.assertEqual(["cache-path updates/"], m["file_provider_paths"][0]["entries"])

    def test_not_binary_xml_is_refused(self):
        with self.assertRaises(am.Malformed):
            list(am.parse_xml(b"<manifest/>"))


class Surface(unittest.TestCase):
    def approved(self, m):
        return {"surface": [{"line": l, "reason": "reviewed"} for l in ss.surface(m)]}

    def test_the_reviewed_surface_passes(self):
        m = read(apk(manifest(components=[("activity", {"name": "A", "exported": True}, [VIEW])]), [("res/x.xml", PATHS)]))
        self.assertEqual([], ss.compare(m, self.approved(m))[1])

    def test_a_new_exported_component_fails(self):
        before = read(apk(manifest()))
        after = read(apk(manifest(components=[("service", {"name": "Leak", "exported": True}, [])])))
        problems = ss.compare(after, self.approved(before))[1]
        self.assertTrue(any("not reviewed: exported service Leak" in p for p in problems), problems)

    def test_a_removed_line_fails_until_the_list_is_updated(self):
        before = read(apk(manifest(components=[("activity", {"name": "A", "exported": True}, [VIEW])])))
        problems = ss.compare(read(apk(manifest())), self.approved(before))[1]
        self.assertTrue(any("no longer in the APK" in p for p in problems), problems)

    def test_fixed_rules_fail_even_when_listed(self):
        m = read(apk(manifest(app_attrs={"debuggable": True, "allowBackup": True},
                              components=[("provider", {"name": "P", "authorities": "x", "exported": True}, [])]),
                     [("res/p.xml", ("paths", {}, [("root-path", {"name": "r", "path": ""}, [])]))]))
        problems = " | ".join(ss.compare(m, self.approved(m))[1])
        for expected in ("debuggable", "allowBackup", "exported provider P", "whole directory"):
            self.assertIn(expected, problems)

    def test_a_listed_line_needs_a_reason(self):
        m = read(apk(manifest()))
        approved = self.approved(m)
        approved["surface"][0]["reason"] = " "
        self.assertTrue(any("no reason" in p for p in ss.compare(m, approved)[1]))


class ReviewedList(unittest.TestCase):
    def test_every_reviewed_line_has_a_reason(self):
        import json
        doc = json.loads((here.parents[1] / "docs/plus/build15/security-surface.json").read_text(encoding="utf-8"))
        self.assertTrue(doc["surface"])
        for item in doc["surface"]:
            self.assertTrue(item["reason"].strip(), item["line"])


if __name__ == "__main__":
    unittest.main()
