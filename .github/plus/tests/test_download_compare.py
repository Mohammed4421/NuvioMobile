"""Tests of download-compare.sh (P5-07): every metric it prints has a limit, and the CPU time it
reads from /proc/<pid>/stat is utime + stime even when the process name holds spaces or brackets."""
import json
import re
import subprocess
import unittest
from pathlib import Path

PLUS = Path(__file__).resolve().parents[1]
SCRIPT = (PLUS / "download-compare.sh").read_text()


class DownloadCompare(unittest.TestCase):
    def test_every_printed_metric_has_a_limit(self):
        printed = set(re.findall(r'echo "MEASURE \$\{label\} ([a-z_]+) ', SCRIPT))
        limits = json.loads((PLUS / "download-limits.json").read_text())["max_increase_percent"]
        self.assertEqual({"download_wall_ms", "download_cpu_ms_per_mib", "download_pss_kb"}, printed)
        self.assertEqual(printed, set(limits))

    def cpu_ticks(self, stat_line):
        body = re.search(r"^cpu_ticks\(\) \{ (.*); \}$", SCRIPT, re.M).group(1)
        return subprocess.run(["bash", "-c", body], input=stat_line, capture_output=True, text=True, check=True).stdout.strip()

    def test_cpu_time_is_utime_plus_stime(self):
        # Fields: pid (comm) state ppid pgrp session tty tpgid flags minflt cminflt majflt cmajflt utime stime ...
        line = "4321 (com.nuviodebug.com) S 300 300 0 0 -1 1077952832 9000 0 12 0 1234 567 0 0 20 0 80 0\n"
        self.assertEqual("1801", self.cpu_ticks(line))
        self.assertEqual("1801", self.cpu_ticks(line.replace("(com.nuviodebug.com)", "(a b) c)")))

    def test_a_run_is_scored_only_after_its_checks(self):
        # The MEASURE lines come after the single-request, Completed and SHA-256 checks of the run.
        first_measure = SCRIPT.index('echo "MEASURE ${label} download_wall_ms')
        for check in ("the first request did not start from byte 0", "status ${status} after the file was finished",
                      "the finished file differs", "the app process changed during the download",
                      "not every restart resumed with If-Range and 206", "return 3"):
            self.assertLess(SCRIPT.index(check), first_measure, check)

    def restart_rejected(self, requests):
        rule = re.search(r"tail -n \+2 \"\$\{work\}/requests.log\" \| (grep -vqE '[^']+')", SCRIPT).group(1)
        r = subprocess.run(["bash", "-c", f"tail -n +2 | {rule}"], input=requests, text=True)
        return r.returncode == 0

    def test_a_restart_counts_only_as_a_correct_resume(self):
        first = 'GET range=- if-range=- -> 200 0-67108863\n'
        resume = 'GET range=bytes=38587560- if-range="fixture-67108864-1795363532" -> 206 38587560-67108863\n'
        self.assertFalse(self.restart_rejected(first + resume))
        self.assertFalse(self.restart_rejected(first + resume + resume))
        self.assertTrue(self.restart_rejected(first + first))  # started again from byte 0
        self.assertTrue(self.restart_rejected(first + resume.replace("-> 206", "-> 200")))
        self.assertTrue(self.restart_rejected(first + 'GET range=bytes=38587560- if-range=- -> 206 38587560-67108863\n'))

    def test_a_discarded_sample_is_repeated_at_most_twice(self):
        self.assertIn("for tries in 1 2 3; do", SCRIPT)
        self.assertIn("three samples in a row were disturbed by transfer restarts", SCRIPT)
        self.assertNotIn('\n  run build14 "${b14_apk}"', SCRIPT)


if __name__ == "__main__":
    unittest.main()
