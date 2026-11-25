"""S21-01: carryover.json lists every pull request of the release scope with its state and tests."""
import json
import re
import unittest
from pathlib import Path

DOCS = Path(__file__).resolve().parents[3] / "docs/plus/build15"


def numbers(source):
    found = {int(n) for n in re.findall(r"#(\d+)", source)}
    for first, last in re.findall(r"#(\d+)\s*[–-]\s*#(\d+)", source):
        found |= set(range(int(first), int(last) + 1))
    return found


class CarryoverScope(unittest.TestCase):
    def test_every_pull_request_of_the_scope_is_in_the_carry_over(self):
        entries = json.loads((DOCS / "carryover.json").read_text(encoding="utf-8"))["entries"]
        listed = set().union(*(numbers(e["source"]) for e in entries))
        scope = json.loads((DOCS / "release-scope.json").read_text(encoding="utf-8"))["required_pull_requests"]
        self.assertEqual([], sorted(set(scope) - listed), "pull requests of the scope missing from carryover.json")
        for entry in entries:
            with self.subTest(source=entry["source"]):
                self.assertTrue(str(entry.get("tests_run", "")).strip(), "no tests named")


if __name__ == "__main__":
    unittest.main()
