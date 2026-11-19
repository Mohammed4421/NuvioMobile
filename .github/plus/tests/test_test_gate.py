"""Tests of test-gate.py: every Nuvio Plus test must pass, with or without the Nuvio baseline."""
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

GATE = Path(__file__).resolve().parents[1] / "test-gate.py"


class TestGateTest(unittest.TestCase):
    def run_gate(self, plus, nuvio=None):
        with tempfile.TemporaryDirectory() as temp:
            Path(temp, "plus.json").write_text(json.dumps(plus))
            if nuvio is not None:
                Path(temp, "nuvio.json").write_text(json.dumps(nuvio))
            result = subprocess.run([sys.executable, "-I", str(GATE), "plus.json", "nuvio.json", "1.0.0"],
                                    cwd=temp, capture_output=True, text=True)
            summary = Path(temp, "checks-tests.md").read_text() if Path(temp, "checks-tests.md").exists() else ""
        return result.returncode, result.stdout + result.stderr, summary

    def test_all_passing_without_the_baseline_passes(self):
        code, _, summary = self.run_gate({"total": 10, "failed": []})
        self.assertEqual(0, code)
        self.assertIn("10 of 10 passed (Nuvio baseline not run", summary)

    def test_a_failure_blocks_with_the_baseline(self):
        code, out, _ = self.run_gate({"total": 10, "failed": ["A", "B"]}, {"total": 9, "failed": ["B"]})
        self.assertEqual(1, code)
        self.assertIn("Fails only in Nuvio Plus: A", out)
        self.assertIn("Fails in Nuvio Plus and in Nuvio 1.0.0: B", out)

    def test_a_failure_blocks_even_without_the_baseline(self):
        code, out, summary = self.run_gate({"total": 10, "failed": ["A"]})
        self.assertEqual(1, code)
        self.assertIn("Fails: A", out)
        self.assertIn("failures not compared", summary)

    def test_no_tests_at_all_blocks(self):
        self.assertEqual(1, self.run_gate({"total": 0, "failed": []})[0])


if __name__ == "__main__":
    unittest.main()
