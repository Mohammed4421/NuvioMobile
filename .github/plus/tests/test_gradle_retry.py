"""Tests of gradle-retry.sh: retries only failed downloads, never hides a real failure."""
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "gradle-retry.sh"


class GradleRetryTest(unittest.TestCase):
    def run_with(self, outputs, env=None):
        """A fake Gradle that prints outputs[n] and exits with its status on the n-th call."""
        with tempfile.TemporaryDirectory() as temp:
            fake = Path(temp, "gradle")
            calls = Path(temp, "calls")
            lines = ["#!/usr/bin/env bash", f'n=$(($(cat "{calls}" 2>/dev/null || echo 0) + 1)); echo $n > "{calls}"']
            for index, (text, status) in enumerate(outputs, start=1):
                lines.append(f'if [ $n -eq {index} ]; then printf "%s\\n" {text!r}; exit {status}; fi')
            fake.write_text("\n".join(lines) + "\n")
            fake.chmod(0o755)
            result = subprocess.run(["bash", str(SCRIPT), str(Path(temp, "out.log")), str(fake)], capture_output=True,
                                    text=True, env={**os.environ, "GRADLE_RETRY_WAIT": "0", **(env or {})})
            return result.returncode, int(calls.read_text()), result.stderr

    def test_success_runs_once(self):
        self.assertEqual((0, 1), self.run_with([("BUILD SUCCESSFUL", 0)])[:2])

    def test_a_failed_download_is_retried(self):
        sentry = "Plugin [id: 'io.sentry.android.gradle'] could not resolve plugin artifact"
        code, calls, stderr = self.run_with([(sentry, 1), ("Could not download x.jar", 1), ("BUILD SUCCESSFUL", 0)])
        self.assertEqual((0, 3), (code, calls))
        self.assertIn("A download failed", stderr)

    def test_downloads_failing_five_times_fail(self):
        code, calls, stderr = self.run_with([("Could not GET https://repo", 1)] * 6)
        self.assertEqual((1, 5), (code, calls))
        self.assertIn("kept failing after 5 attempts", stderr)

    def test_rate_limits_are_retried_with_growing_waits(self):
        too_many = "Could not GET 'https://repo.maven.apache.org/x.pom'. Received status code 429 from server: Too Many Requests"
        code, calls, stderr = self.run_with([(too_many, 1)] * 4 + [("BUILD SUCCESSFUL", 0)],
                                            env={"GRADLE_RETRY_WAIT": "10", "GRADLE_RETRY_SLEEP": "true"})
        self.assertEqual((0, 5), (code, calls))
        waits = [int(line.rsplit(" in ", 1)[1].split(" ")[0]) for line in stderr.splitlines() if "running Gradle again" in line]
        self.assertEqual(4, len(waits))
        for wait, low in zip(waits, (10, 20, 40, 80)):
            self.assertTrue(low <= wait <= low + 5, waits)
        self.assertIn("(status code 429)", stderr)

    def test_a_compile_error_is_not_retried(self):
        self.assertEqual((1, 1), self.run_with([("e: Unresolved reference\nCould not resolve x", 1)])[:2])

    def test_a_failing_test_is_not_retried(self):
        self.assertEqual((1, 1), self.run_with([("There were failing tests. Could not download", 1)])[:2])

    def test_a_timeout_or_kill_is_not_retried_even_after_a_download_error(self):
        for status in (124, 137, 143):
            self.assertEqual((status, 1), self.run_with([("Could not download x.jar", status)])[:2], status)

    def test_a_missing_command_is_not_retried(self):
        self.assertEqual((127, 1), self.run_with([("Could not resolve x", 127)])[:2])

    def test_an_unrecognised_message_is_not_retried(self):
        self.assertEqual((1, 1), self.run_with([("FAILURE: Build failed with an exception.\nboom", 1)])[:2])

    def test_any_other_failure_is_not_retried(self):
        self.assertEqual((7, 1), self.run_with([("Something else broke", 7)])[:2])


if __name__ == "__main__":
    unittest.main()
