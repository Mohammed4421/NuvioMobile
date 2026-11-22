import importlib.util
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("release_scope_check", ROOT / ".github/plus/release-scope-check.py")
scope = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scope)

HEAD = "a" * 40


class FakeGitHub:
    def __init__(self, pulls, tip=HEAD, runs=None):
        self.pulls, self.tip = pulls, tip
        self.runs = runs if runs is not None else [{"name": "Build 15 requirements gate", "conclusion": "success"}]

    def get(self, path):
        if path.startswith("branches/"):
            return {"commit": {"sha": self.tip}}
        if path.startswith("pulls/"):
            number = int(path.split("/")[1])
            if number not in self.pulls:
                raise RuntimeError("404")
            return self.pulls[number]
        if path.startswith("actions/runs"):
            return {"workflow_runs": self.runs}
        raise AssertionError(path)


def merged(number, sha=None, base="custom-subtitles"):
    return {"number": number, "merged": True, "merge_commit_sha": sha or f"{number:040d}", "base": {"ref": base},
            "head": {"sha": "f" * 40}}


class ReleaseScopeTest(unittest.TestCase):
    def manifest(self, **changes):
        data = json.loads((ROOT / "docs/plus/build15/release-scope.json").read_text())
        data.update(required_pull_requests=[10, 11], excluded_pull_requests={"2": "trial"})
        data.update(changes)
        return data

    def run_check(self, manifest, api, ancestors=None):
        ancestors = ancestors if ancestors is not None else {f"{10:040d}", f"{11:040d}"}
        return scope.check(manifest, api, HEAD, "custom-subtitles", root=ROOT,
                           ancestor=lambda commit, of: commit in ancestors)

    def open_trial(self):
        return {"number": 2, "merged": False, "base": {"ref": "plus-beta"}, "head": {"sha": "e" * 40}}

    def test_the_repository_manifest_matches_the_gate_and_portkit(self):
        manifest = json.loads((ROOT / "docs/plus/build15/release-scope.json").read_text())
        self.assertEqual(sorted(scope.gate_mandatory(ROOT)), sorted(manifest["mandatory_requirements"]))
        self.assertEqual(scope.portkit_version(ROOT), manifest["portkit_version"])
        self.assertEqual("custom-subtitles", manifest["branch"])
        self.assertIn("2", manifest["excluded_pull_requests"])
        self.assertNotIn(2, manifest["required_pull_requests"])

    def test_complete_scope_passes(self):
        api = FakeGitHub({10: merged(10), 11: merged(11), 2: self.open_trial()})
        self.assertEqual([], self.run_check(self.manifest(), api))

    def test_moved_branch_blocks(self):
        api = FakeGitHub({10: merged(10), 11: merged(11), 2: self.open_trial()}, tip="b" * 40)
        self.assertTrue(any("moved" in p for p in self.run_check(self.manifest(), api)))

    def test_unmerged_or_missing_pull_request_blocks(self):
        api = FakeGitHub({10: merged(10), 11: dict(merged(11), merged=False), 2: self.open_trial()})
        self.assertTrue(any("#11: not merged" in p for p in self.run_check(self.manifest(), api)))
        api = FakeGitHub({10: merged(10), 2: self.open_trial()})
        self.assertTrue(any("#11: could not read" in p for p in self.run_check(self.manifest(), api)))

    def test_merge_commit_outside_the_release_commit_blocks(self):
        api = FakeGitHub({10: merged(10), 11: merged(11), 2: self.open_trial()})
        problems = self.run_check(self.manifest(), api, ancestors={f"{10:040d}"})
        self.assertTrue(any("#11: merged, but its merge commit is not part" in p for p in problems))

    def test_pull_request_into_another_branch_blocks(self):
        api = FakeGitHub({10: merged(10), 11: merged(11, base="plus-beta"), 2: self.open_trial()})
        self.assertTrue(any("#11: targets 'plus-beta'" in p for p in self.run_check(self.manifest(), api)))

    def test_excluded_pull_request_merged_or_contained_blocks(self):
        api = FakeGitHub({10: merged(10), 11: merged(11), 2: dict(merged(2), base={"ref": "plus-beta"})})
        self.assertTrue(any("#2 (excluded) is merged" in p for p in self.run_check(self.manifest(), api)))
        api = FakeGitHub({10: merged(10), 11: merged(11), 2: self.open_trial()})
        problems = self.run_check(self.manifest(), api, ancestors={f"{10:040d}", f"{11:040d}", "e" * 40})
        self.assertTrue(any("#2 (excluded): its commits are part" in p for p in problems))

    def test_approval_test_not_passed_on_the_commit_blocks(self):
        pulls = {10: merged(10), 11: merged(11), 2: self.open_trial()}
        api = FakeGitHub(pulls, runs=[{"name": "Build 15 requirements gate", "conclusion": "failure"}])
        self.assertTrue(any("did not pass" in p for p in self.run_check(self.manifest(), api)))
        api = FakeGitHub(pulls, runs=[])
        self.assertTrue(any("has not run" in p for p in self.run_check(self.manifest(), api)))

    def test_changed_mandatory_list_or_portkit_version_blocks(self):
        api = FakeGitHub({10: merged(10), 11: merged(11), 2: self.open_trial()})
        manifest = self.manifest()
        manifest["mandatory_requirements"] = manifest["mandatory_requirements"][1:] + ["X-99"]
        self.assertTrue(any("mandatory requirements differ" in p for p in self.run_check(manifest, api)))
        self.assertTrue(any("PortKit is" in p for p in self.run_check(self.manifest(portkit_version="0.9"), api)))

    def test_other_branch_blocks(self):
        api = FakeGitHub({10: merged(10), 11: merged(11), 2: self.open_trial()})
        self.assertTrue(any("manifest is for" in p for p in self.run_check(self.manifest(branch="plus-beta"), api)))


if __name__ == "__main__":
    unittest.main()
