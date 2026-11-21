"""Tests of measure-compare.py: a change inside the noise is never reported as a difference."""
import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("mc", Path(__file__).resolve().parents[1] / "measure-compare.py")
mc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mc)


def lines(label, metric, values):
    return [f"MEASURE {label} {metric} {v}" for v in values]


class Compare(unittest.TestCase):
    def verdict(self, a, b, metric="cold_start_ms"):
        rows, problems = mc.compare(mc.parse(lines("b14", metric, a) + lines("b15", metric, b)), "b14", "b15")
        self.assertEqual([], problems)
        return rows[0]

    def test_a_change_inside_the_spread_is_noise(self):
        self.assertEqual("within noise", self.verdict([900, 1000, 1100, 950, 1050], [960, 1060, 1010, 980, 1040])["verdict"])

    def test_a_change_smaller_than_five_percent_is_noise_even_with_tight_values(self):
        self.assertEqual("within noise", self.verdict([1000, 1001, 999], [1030, 1031, 1029])["verdict"])

    def test_a_clear_slowdown_is_reported(self):
        r = self.verdict([1000, 1010, 990, 1005], [1300, 1310, 1290, 1295])
        self.assertEqual("higher", r["verdict"])
        self.assertAlmostEqual(30.0, r["change_pct"], delta=1)

    def test_a_clear_improvement_is_reported(self):
        self.assertEqual("lower", self.verdict([1300, 1310, 1290], [1000, 1010, 990])["verdict"])

    def test_too_few_values_are_refused(self):
        rows, problems = mc.compare(mc.parse(lines("b14", "pss_kb", [1, 2]) + lines("b15", "pss_kb", [1, 2, 3])), "b14", "b15")
        self.assertEqual([], rows)
        self.assertTrue(problems)

    def test_apk_size_is_one_exact_value(self):
        r = self.verdict([60000000], [60500000], metric="apk_bytes")
        self.assertEqual("higher", r["verdict"])


if __name__ == "__main__":
    unittest.main()
