"""P2-01 / P2-02: structure changes stay decisions with a reason.

- Every decision in docs/plus/build15/architecture.md names the spec §5 criterion it rests on.
- Every Kotlin or Swift file of the upstream base that Plus no longer has is listed there with
  the decision behind it, so no Nuvio file is moved, renamed or dropped without one.
"""
import json
import os
import re
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
ARCHITECTURE = ROOT / "docs/plus/build15/architecture.md"
CRITERIA = {
    "repeated defect", "harmful coupling", "measured performance", "concurrency/state simplification",
    "testability", "upstream merge cost", "PortKit enablement",
}
SOURCE = re.compile(r"\.(kt|kts|swift)$")


def git(*args):
    return subprocess.run(["git", "-C", str(ROOT), *args], capture_output=True, check=True, text=True).stdout


class Architecture(unittest.TestCase):
    def test_every_decision_names_a_section_5_criterion(self):
        rows = re.findall(r"^\| (A-\d{2}) \| [^|]+ \| ([^|]+) \| ([^|]+) \| ([^|]+) \|", ARCHITECTURE.read_text(encoding="utf-8"), re.M)
        self.assertGreaterEqual(len(rows), 7)
        for decision_id, decision, criterion, evidence in rows:
            with self.subTest(decision=decision_id):
                self.assertTrue(decision.strip(), "no decision")
                self.assertIn(criterion.strip(), CRITERIA, "not a §5 criterion")
                self.assertTrue(evidence.strip(), "no evidence")

    def test_no_upstream_source_file_is_dropped_without_a_decision(self):
        base = json.loads((ROOT / "portkit/compatibility.json").read_text())["upstream_base"]["commit"]
        present = subprocess.run(["git", "-C", str(ROOT), "cat-file", "-e", f"{base}^{{commit}}"]).returncode == 0
        if not present:
            if os.environ.get("CI"):
                self.fail(f"the upstream base {base} was not fetched for this check")
            self.skipTest("upstream base not fetched")
        upstream = {f for f in git("ls-tree", "-r", "--name-only", base).splitlines() if SOURCE.search(f)}
        ours = {f for f in git("ls-files").splitlines() if SOURCE.search(f)}
        section = ARCHITECTURE.read_text(encoding="utf-8").split("### Nuvio files Plus removes", 1)[1].split("\n## ", 1)[0]
        recorded = set(re.findall(r"^\| `([^`]+)` \|", section, re.M))
        self.assertEqual(sorted(recorded), sorted(upstream - ours), "upstream files removed here vs those recorded")


if __name__ == "__main__":
    unittest.main()
