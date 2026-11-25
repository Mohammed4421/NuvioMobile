"""S15-01 / S24-02: progress is reported as separate figures, also weighted by risk, with the
critical path of open pre-release items, critical ones first."""
import importlib.util
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("check_requirements", ROOT / ".github/plus/check-requirements.py")
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


def item(rid, phase, status, release_class="PRE-RELEASE REQUIRED"):
    return {"id": rid, "phase": phase, "status": status, "release_class": release_class}


class Progress(unittest.TestCase):
    def test_progress_is_also_weighted_by_risk_with_the_critical_path(self):
        items = [
            item("P6-14", "P6", "SPECIFIED"),       # critical, weight 3, open
            item("P3-01", "P3", "VERIFIED"),        # journey, weight 2
            item("S16-01", "P9", "IMPLEMENTED"),    # weight 1, open
            item("D-10", "D", "ACCEPTED"),          # critical, weight 3
            item("PRV-01", "P9", "SPECIFIED", "POST-RELEASE VALIDATION"),  # weight 1, not pre-release
        ]
        result = gate.weighted_progress(items)
        self.assertEqual(3, gate.weight(items[0]))
        self.assertEqual(2, gate.weight(items[1]))
        self.assertEqual(1, gate.weight(items[2]))
        # weights 3 + 2 + 1 + 3 + 1 = 10
        self.assertEqual(60, result["implemented"])   # 2 + 1 + 3
        self.assertEqual(50, result["verified"])      # 2 + 3
        self.assertEqual(30, result["accepted"])      # 3
        self.assertEqual(55, result["ready"])         # (2 + 3) of 9 pre-release
        self.assertEqual(["P6-14", "S16-01"], result["critical_path"])


if __name__ == "__main__":
    unittest.main()
