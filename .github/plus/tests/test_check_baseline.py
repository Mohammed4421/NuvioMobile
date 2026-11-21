"""check-baseline.py finds a baseline value that differs from its source (offline part)."""
import copy
import importlib.util
import json
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("cb", Path(__file__).resolve().parents[1] / "check-baseline.py")
cb = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cb)
BASELINE = json.loads((cb.root / "docs/plus/build15/baseline.json").read_text(encoding="utf-8"))


def has_history():
    try:
        cb.git_show(BASELINE["nuvio_plus"]["commit"], "gradle/libs.versions.toml")
        return True
    except Exception:
        return False


@unittest.skipUnless(has_history(), "needs the Build 14 commit in the Git history (the gate fetches it)")
class Offline(unittest.TestCase):
    def run_offline(self, b):
        cb.problems.clear()
        cb.offline(b)
        return list(cb.problems)

    def test_the_recorded_baseline_matches(self):
        self.assertEqual([], self.run_offline(BASELINE))

    def test_a_wrong_version_is_found(self):
        b = copy.deepcopy(BASELINE)
        b["toolchain"]["kotlin"] = "2.0.0"
        b["toolchain"]["gradle"] = "8.0"
        self.assertEqual(["kotlin", "gradle"], self.run_offline(b))


if __name__ == "__main__":
    unittest.main()
