"""Tests of build15-inventory.py: counts come from git, and no changed area looks reviewed by default."""
import importlib.util
import json
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("inventory", ROOT / ".github/plus/build15-inventory.py")
inventory = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inventory)


def git(repo, *args):
    return subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True, check=True).stdout.strip()


class Inventory(unittest.TestCase):
    def setUp(self):
        self.repo = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.repo)
        git(self.repo, "init", "-q")
        git(self.repo, "config", "user.email", "t@t")
        git(self.repo, "config", "user.name", "t")
        app = "composeApp/src/commonMain/kotlin/com/nuvio/app"
        for path in (f"{app}/features/player/A.kt", f"{app}/features/brandnew/B.kt", f"{app}/features/untouched/C.kt",
                     "composeApp/src/commonTest/kotlin/com/nuvio/app/features/player/ATest.kt"):
            Path(self.repo, path).parent.mkdir(parents=True, exist_ok=True)
            Path(self.repo, path).write_text("x\n")
        Path(self.repo, "portkit").mkdir()
        git(self.repo, "add", "-A")
        git(self.repo, "commit", "-qm", "base")
        base = git(self.repo, "rev-parse", "HEAD")
        Path(self.repo, "portkit/compatibility.json").write_text(json.dumps({"upstream_base": {"commit": base}}))
        Path(self.repo, f"{app}/features/player/A.kt").write_text("y\n")
        Path(self.repo, f"{app}/features/brandnew/B.kt").write_text("y\n")
        Path(self.repo, f"{app}/features/brandnew/New.kt").write_text("y\n")
        git(self.repo, "add", "-A")
        git(self.repo, "commit", "-qm", "plus")
        self.inv = {r["area"]: r for r in inventory.inventory(self.repo)["areas"]}

    def test_counts_come_from_git(self):
        self.assertEqual((1, 1, 0), tuple(self.inv["features/brandnew"][k] for k in ("added", "modified", "deleted")))
        self.assertEqual(1, self.inv["features/player"]["tests"])

    def test_a_changed_area_without_a_review_entry_is_not_reviewed(self):
        self.assertEqual("NOT REVIEWED", self.inv["features/brandnew"]["review"])

    def test_untouched_nuvio_code_is_not_called_reviewed(self):
        self.assertEqual("NUVIO (not reviewed)", self.inv["features/untouched"]["review"])

    def test_a_listed_area_names_its_evidence(self):
        self.assertEqual("PARTIAL", self.inv["features/player"]["review"])
        self.assertTrue(self.inv["features/player"]["refs"])

    def test_every_review_entry_names_evidence_and_what_is_open(self):
        for area, (state, refs, note) in inventory.REVIEW.items():
            self.assertIn(state, ("REVIEWED", "PARTIAL"), area)
            self.assertTrue(refs and note, area)

    def test_the_committed_report_matches_the_script(self):
        report = (ROOT / "docs/plus/build15/inventory.md").read_text()
        for area, (state, _, _) in inventory.REVIEW.items():
            if f"`{area}`" in report:
                self.assertIn(state, report.split(f"`{area}`", 1)[1].split("\n", 1)[0], area)


if __name__ == "__main__":
    unittest.main()
