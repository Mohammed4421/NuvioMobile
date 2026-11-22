import importlib.util
import json
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("initial_pass", ROOT / ".github/plus/initial-pass.py")
initial = importlib.util.module_from_spec(spec)
spec.loader.exec_module(initial)


class InitialPassTest(unittest.TestCase):
    """S19-03: every source area has an initial-pass result; the committed table is current."""

    def test_the_committed_table_is_current(self):
        expected = initial.table(*initial.scan(str(ROOT)))
        self.assertEqual(expected, (ROOT / initial.OUT).read_text(),
                         "docs/plus/build15/initial-pass.md is out of date: run initial-pass.py --write")

    def test_every_source_file_is_in_an_area_with_a_result(self):
        files, _ = initial.scan(str(ROOT))
        listed = subprocess.run(["git", "-C", str(ROOT), "ls-files", *initial.ROOTS], capture_output=True,
                                text=True, check=True).stdout.split()
        sources = [p for p in listed if initial.SOURCE.search(p) and not initial.TEST.search(p)]
        self.assertGreater(len(sources), 1000)
        self.assertEqual(len(sources), sum(files.values()), "a source file is in no area")
        table = (ROOT / initial.OUT).read_text()
        for area in files:
            self.assertIn(f"| {area} |", table)

    def test_every_finding_named_exists(self):
        findings = {f["id"] for f in json.loads((ROOT / "docs/plus/build15/findings.json").read_text())["findings"]}
        for name, (_, verdict, ids) in initial.CATEGORIES.items():
            self.assertTrue(verdict.strip(), name)
            for finding in ids:
                self.assertIn(finding, findings, f"{name} names {finding}, which is not in findings.json")

    def test_a_new_hit_category_in_an_area_changes_its_result(self):
        files = {"features/x": 1, "features/y": 1}
        text = initial.table(files, {"features/x": {"catch-throwable": 2}})
        self.assertIn("| features/x | 1 | catch-throwable 2 | B15-NUV-01 |", text)
        self.assertIn("| features/y | 1 | — | reviewed, none |", text)


if __name__ == "__main__":
    unittest.main()
