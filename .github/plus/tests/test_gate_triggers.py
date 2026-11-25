"""S25-02: the requirements gate runs on every pull request to and every push of custom-subtitles,
with its own tests, against GitHub (--online)."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
WORKFLOW = ROOT / ".github/workflows/requirements-gate.yml"


class GateTriggers(unittest.TestCase):
    def test_the_gate_runs_on_every_pull_request_and_push_online(self):
        text = WORKFLOW.read_text(encoding="utf-8")
        on = text.split("\non:", 1)[1].split("\n\n", 1)[0]
        for event in ("pull_request", "push"):
            block = re.search(rf"^  {event}:\n((?:    .*\n)+)", on + "\n", re.M)
            self.assertIsNotNone(block, f"{event} trigger missing")
            self.assertIn("custom-subtitles", block.group(1), f"{event} does not cover custom-subtitles")
            self.assertNotIn("paths", block.group(1), f"{event} is filtered by paths, so it would not run on every change")
        self.assertIn("python3 -I -m unittest discover -s .github/plus/tests -p 'test_check_*.py'", text)
        self.assertIn("python3 -I .github/plus/check-requirements.py --online", text)


if __name__ == "__main__":
    unittest.main()
