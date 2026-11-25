"""C-01 / C-03: Build 16 adds no standalone feature and changes no build file, dependency version
or application id of Build 14 without an owner decision (docs/plus/build15/scope-guard.json)."""
import json
import re
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
DOCS = ROOT / "docs/plus/build15"
BUILD = re.compile(r"(^|/)(build\.gradle\.kts|settings\.gradle\.kts|gradle\.properties|libs\.versions\.toml|gradle-wrapper\.properties)$")


class ScopeGuard(unittest.TestCase):
    def setUp(self):
        self.guard = json.loads((DOCS / "scope-guard.json").read_text(encoding="utf-8"))

    def test_build_files_and_dependency_versions_are_those_of_build_14(self):
        index = subprocess.run(["git", "-C", str(ROOT), "ls-files", "-s"], capture_output=True, text=True, check=True).stdout
        now = {}
        for line in index.splitlines():
            meta, path = line.split("\t", 1)
            if BUILD.search(path):
                now[path] = meta.split()[1]
        self.assertEqual(self.guard["build_files"], now, "a build file or dependency version changed since Build 14")

    def test_the_application_id_is_unchanged(self):
        gradle = (ROOT / "androidApp/build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn(f'applicationId = "{self.guard["application_id"]}"', gradle)

    def test_every_feature_is_from_build_14_or_a_recorded_fix(self):
        registry = json.loads((ROOT / ".github/plus/features.json").read_text(encoding="utf-8"))
        features = {f["id"] for f in (registry["features"] if isinstance(registry, dict) else registry)}
        additions = self.guard["build15_additions"]
        self.assertEqual(set(self.guard["build14_features"]) | set(additions), features)
        findings = {f["id"] for f in json.loads((DOCS / "findings.json").read_text(encoding="utf-8"))["findings"]}
        changes = {c["id"] for c in json.loads((DOCS / "change-requests.json").read_text(encoding="utf-8"))["changes"]}
        for fid, entry in additions.items():
            with self.subTest(feature=fid):
                self.assertIn(entry["kind"], {"fix", "security fix", "test", "tool"})
                self.assertIn(entry["record"], findings | changes, "no finding or owner decision")


if __name__ == "__main__":
    unittest.main()
