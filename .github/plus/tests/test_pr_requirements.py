"""S19-04: the requirement ids recorded for pull requests whose description names none are real
requirement ids, and each such pull request is part of the release scope."""
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
DOCS = ROOT / "docs/plus/build15"


class PullRequestRequirements(unittest.TestCase):
    def test_ids_exist_and_pull_requests_are_in_scope(self):
        mapping = json.loads((DOCS / "pr-requirements.json").read_text(encoding="utf-8"))["pull_requests"]
        known = {item["id"] for item in json.loads((DOCS / "requirements.json").read_text(encoding="utf-8"))["items"]}
        scope = set(json.loads((DOCS / "release-scope.json").read_text(encoding="utf-8"))["required_pull_requests"])
        for number, entry in mapping.items():
            with self.subTest(pull_request=number):
                self.assertTrue(entry["requirements"], "no requirement id")
                self.assertEqual([], sorted(set(entry["requirements"]) - known), "unknown requirement id")
                self.assertIn(int(number), scope, "not in release-scope.json")


if __name__ == "__main__":
    unittest.main()
