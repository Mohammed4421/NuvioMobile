"""Tests of instrumentation-results.py: only explicitly passed tests count; nothing else passes."""
import subprocess
import sys
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "instrumentation-results.py"


def status(cls, test, code):
    return (f"INSTRUMENTATION_STATUS: class=com.nuvio.android.{cls}\nINSTRUMENTATION_STATUS: test={test}\n"
            f"INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS: class=com.nuvio.android.{cls}\n"
            f"INSTRUMENTATION_STATUS: test={test}\nINSTRUMENTATION_STATUS_CODE: {code}\n")


END = "INSTRUMENTATION_RESULT: stream=\nOK (2 tests)\nINSTRUMENTATION_CODE: -1\n"


def run(text):
    r = subprocess.run([sys.executable, "-I", str(SCRIPT), "t"], input=text, capture_output=True, text=True)
    return r.returncode, r.stdout


class Results(unittest.TestCase):
    def test_all_passed(self):
        code, out = run(status("A", "one", 0) + status("B", "two", 0) + END)
        self.assertEqual(0, code, out)
        self.assertIn("all 2 test(s) passed", out)

    def test_a_failure_fails(self):
        self.assertEqual(1, run(status("A", "one", 0) + status("B", "two", -2) + END)[0])

    def test_an_error_fails(self):
        self.assertEqual(1, run(status("A", "one", -1) + END)[0])

    def test_a_skipped_test_is_not_a_pass(self):
        code, out = run(status("A", "one", 0) + status("B", "two", -4) + END)
        self.assertEqual(1, code)
        self.assertIn("skipped (assumption)", out)

    def test_an_ignored_test_is_not_a_pass(self):
        self.assertEqual(1, run(status("A", "one", -3) + END)[0])

    def test_an_empty_run_fails(self):
        self.assertEqual(1, run("INSTRUMENTATION_CODE: -1\n")[0])

    def test_a_crash_fails_even_after_passes(self):
        text = status("A", "one", 0) + "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n"
        self.assertEqual(1, run(text)[0])

    def test_a_run_that_never_ends_fails(self):
        self.assertEqual(1, run(status("A", "one", 0))[0])


if __name__ == "__main__":
    unittest.main()
