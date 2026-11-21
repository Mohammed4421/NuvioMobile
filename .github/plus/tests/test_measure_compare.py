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


class Limits(unittest.TestCase):
    def test_a_rise_above_noise_and_limit_fails_and_one_within_passes(self):
        rows, _ = mc.compare(mc.parse(lines("b14", "cold_start_ms", [1000, 1010, 990]) + lines("b15", "cold_start_ms", [1300, 1310, 1290])), "b14", "b15")
        self.assertEqual(1, len(mc.over_limits(rows, {"cold_start_ms": 10})))
        self.assertEqual([], mc.over_limits(rows, {"cold_start_ms": 40}))

    def test_noise_never_breaks_a_limit(self):
        rows, _ = mc.compare(mc.parse(lines("b14", "pss_kb", [900, 1100, 1000]) + lines("b15", "pss_kb", [1000, 1200, 1100])), "b14", "b15")
        self.assertEqual("within noise", rows[0]["verdict"])
        self.assertEqual([], mc.over_limits(rows, {"pss_kb": 1}))

    def test_the_repository_limits_cover_every_measured_metric(self):
        import json
        limits = json.loads((Path(__file__).resolve().parents[1] / "performance-limits.json").read_text())["max_increase_percent"]
        self.assertEqual({"cold_start_ms", "warm_start_ms", "pss_kb", "apk_bytes"}, set(limits))


if __name__ == "__main__":
    unittest.main()
