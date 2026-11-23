"""PortKit per-feature records (P7-03, P7-09): every feature has every registry field, and the
generated record file is current."""
import importlib.util
import os
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("feature_records", ROOT / "portkit/feature-records.py")
records = importlib.util.module_from_spec(spec)
spec.loader.exec_module(records)
spec = importlib.util.spec_from_file_location("test_portkit_plan", Path(__file__).with_name("test_portkit_plan.py"))
fixtures = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixtures)

FIELDS = ["Title", "Level", "Supported versions", "Source sets", "Integration points (Nuvio files it changes)",
          "Plus files", "Depends on", "Data formats", "Tests", "Not transferable", "Limits", "Known conflicts"]


@unittest.skipUnless(fixtures.HAVE_BASE or os.environ.get("CI"), "upstream base not fetched")
class Records(unittest.TestCase):
    def setUp(self):
        if not fixtures.HAVE_BASE:
            self.fail("the upstream base was not fetched for this check")

    def test_every_feature_has_every_field(self):
        text = records.records()
        sections = text.split("\n## ")[1:]
        self.assertEqual(15, len(sections))
        for section in sections:
            name = section.split("\n", 1)[0]
            for field in FIELDS:
                self.assertIn(f"- **{field}:** ", section, f"{name}: {field}")
            self.assertTrue("`" in section.split("Integration points")[1].split("\n")[0]
                            or "`" in section.split("- **Plus files:**")[1].split("\n")[0], f"{name}: no files")

    def test_the_record_file_is_current(self):
        result = subprocess.run(["python3", str(ROOT / "portkit/feature-records.py"), "--check"],
                                capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stdout)


if __name__ == "__main__":
    unittest.main()
