"""PortKit apply and rollback on a Git repository holding real upstream 1.0.0 files."""
import importlib.util
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("test_portkit_plan", Path(__file__).with_name("test_portkit_plan.py"))
fixtures = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixtures)
portkit = fixtures.portkit
LAZY = fixtures.LAZY
LAZY_TEST = "composeApp/src/commonTest/kotlin/com/nuvio/app/core/ui/DuplicateSafeLazyKeysTest.kt"
PARSER_TEST = "composeApp/src/commonTest/kotlin/com/nuvio/app/features/player/SubtitleParserWorstCaseTest.kt"


def git(repo, *args):
    return subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True, check=True).stdout.strip()


@unittest.skipUnless(fixtures.HAVE_BASE or os.environ.get("CI"), "upstream base not fetched")
class Apply(unittest.TestCase):
    def setUp(self):
        if not fixtures.HAVE_BASE:
            self.fail("the upstream base was not fetched for this check")
        registry = json.loads((ROOT / ".github/plus/features.json").read_text())
        paths = set(fixtures.ANCHORS)
        for feature in registry["features"]:
            paths.update(p for p in portkit.feature_paths(feature) if not p.startswith(".github/"))
        self.target = fixtures.upstream_tree(sorted(paths))
        self.addCleanup(shutil.rmtree, self.target)
        git(self.target, "init", "-q", "-b", "main")
        git(self.target, "add", "-A")
        git(self.target, "-c", "user.name=fork", "-c", "user.email=f@x", "commit", "-q", "-m", "fork at 1.0.0")
        self.head = git(self.target, "rev-parse", "HEAD")

    def apply(self, branch, *features, trial=True, from_head=False):
        return portkit.apply(ROOT, self.target, list(features), branch, trial=trial, from_head=from_head)

    def file_on(self, branch, path):
        return subprocess.run(["git", "-C", str(self.target), "show", f"{branch}:{path}"], capture_output=True).stdout

    def test_a_transfer_lands_on_a_new_branch_and_leaves_the_checkout_alone(self):
        before = fixtures.tree_digest(self.target)
        result = self.apply("portkit-trial/portable", "lazy-key-collisions", "subtitle-parser-worst-case")
        self.assertEqual("applied", result["status"], result["plan"])
        self.assertEqual(before, fixtures.tree_digest(self.target), "the checkout changed")
        self.assertEqual(self.head, git(self.target, "rev-parse", "main"))
        self.assertEqual((ROOT / LAZY).read_bytes(), self.file_on("portkit-trial/portable", LAZY))
        self.assertEqual((ROOT / PARSER_TEST).read_bytes(), self.file_on("portkit-trial/portable", PARSER_TEST))
        ledger = json.loads(self.file_on("portkit-trial/portable", portkit.LEDGER))
        self.assertEqual(["lazy-key-collisions", "subtitle-parser-worst-case"], ledger["transfers"][0]["features"])
        self.assertIn("PortKit-Transfer:", git(self.target, "log", "-1", "--format=%B", "portkit-trial/portable"))
        self.assertEqual(1, git(self.target, "worktree", "list", "--porcelain").count("worktree "), "a worktree was left")

    def test_applying_again_changes_nothing(self):
        self.apply("portkit-trial/first", "lazy-key-collisions")
        git(self.target, "checkout", "-q", "portkit-trial/first")
        result = self.apply("portkit-trial/second", "lazy-key-collisions")
        self.assertEqual("already", result["status"])
        self.assertNotIn("portkit-trial/second", git(self.target, "branch", "--list"))

    def test_a_conflict_writes_nothing_and_leaves_no_branch(self):
        file = self.target / LAZY
        file.write_text(file.read_text().replace("val keyCounts = groupingBy(key).eachCount()",
                                                 "val keyCounts = groupingBy(key).eachCount() // fork", 1))
        git(self.target, "-c", "user.name=fork", "-c", "user.email=f@x", "commit", "-q", "-am", "fork change")
        result = self.apply("portkit-trial/conflict", "lazy-key-collisions", "subtitle-parser-worst-case")
        self.assertEqual("conflict", result["status"])
        self.assertNotIn("portkit-trial/conflict", git(self.target, "branch", "--list"))
        self.assertIn("// fork", file.read_text())

    def test_a_failure_while_writing_removes_the_branch(self):
        original = portkit.planned_content
        portkit.planned_content = lambda *args: None
        try:
            with self.assertRaises(portkit.TransferError):
                self.apply("portkit-trial/broken", "lazy-key-collisions")
        finally:
            portkit.planned_content = original
        self.assertNotIn("portkit-trial/broken", git(self.target, "branch", "--list"))

    def test_an_existing_branch_is_not_reused(self):
        git(self.target, "branch", "portkit-trial/taken")
        with self.assertRaises(portkit.TransferError):
            self.apply("portkit-trial/taken", "lazy-key-collisions")

    def test_refused_features_leave_no_branch(self):
        self.assertEqual("refused", self.apply("portkit-trial/identity", "identity")["status"])
        self.assertNotIn("portkit-trial/identity", git(self.target, "branch", "--list"))

    def test_rollback_undoes_only_what_portkit_wrote(self):
        self.apply("portkit-trial/both", "lazy-key-collisions", "subtitle-parser-worst-case")
        # After the transfer the fork edits one of the transferred files.
        git(self.target, "checkout", "-q", "portkit-trial/both")
        edited = self.target / LAZY_TEST
        edited.write_text(edited.read_text() + "\n// fork addition\n")
        git(self.target, "-c", "user.name=fork", "-c", "user.email=f@x", "commit", "-q", "-am", "fork edit")
        git(self.target, "checkout", "-q", "main")
        result = portkit.rollback(self.target, "portkit-trial/both")
        self.assertEqual("rolled-back", result["status"])
        self.assertEqual([LAZY_TEST], result["kept"])
        upstream = subprocess.run(["git", "-C", str(ROOT), "show", f"{fixtures.BASE}:{LAZY}"], capture_output=True).stdout
        self.assertEqual(upstream, self.file_on("portkit-trial/both", LAZY))
        self.assertEqual(b"", self.file_on("portkit-trial/both", PARSER_TEST), "a created file is removed")
        self.assertIn(b"// fork addition", self.file_on("portkit-trial/both", LAZY_TEST))

    def test_a_real_transfer_needs_portable_verified_features(self):
        overlay = json.loads((ROOT / "portkit/compatibility.json").read_text())["features"]
        for feature in ("subtitle-style", "arabic", "season-downloads"):
            result = self.apply("portkit/real-" + feature, feature, trial=False)
            self.assertEqual("refused", result["status"], feature)
        for feature in ("lazy-key-collisions", "subtitle-parser-worst-case"):
            result = self.apply("portkit/real-" + feature, feature, trial=False)
            self.assertEqual("applied" if overlay[feature]["verified"] else "refused", result["status"], feature)
        self.assertNotIn("portkit/real-subtitle-style", git(self.target, "branch", "--list"))

    def test_trials_stay_on_trial_branches(self):
        with self.assertRaises(portkit.TransferError):
            self.apply("portkit/not-a-trial-branch", "lazy-key-collisions", trial=True)
        with self.assertRaises(portkit.TransferError):
            self.apply("portkit-trial/x", "lazy-key-collisions", trial=False)
        self.apply("portkit-trial/marked", "lazy-key-collisions")
        ledger = json.loads(self.file_on("portkit-trial/marked", portkit.LEDGER))
        self.assertTrue(ledger["transfers"][0]["trial"])
        self.assertTrue(git(self.target, "log", "-1", "--format=%s", "portkit-trial/marked").startswith("PortKit trial:"))

    def test_uncommitted_changes_in_the_target_are_refused_unless_from_head(self):
        (self.target / "notes.txt").write_text("work in progress\n")
        file = self.target / LAZY
        file.write_text(file.read_text() + "\n// unsaved\n")
        with self.assertRaises(portkit.TransferError) as refused:
            self.apply("portkit-trial/dirty", "lazy-key-collisions")
        self.assertIn("2 uncommitted change(s)", str(refused.exception))
        self.assertNotIn("portkit-trial/dirty", git(self.target, "branch", "--list"))
        result = self.apply("portkit-trial/from-head", "lazy-key-collisions", from_head=True)
        self.assertEqual("applied", result["status"])
        self.assertTrue(result["uncommitted_left_out"])
        self.assertTrue(json.loads(self.file_on("portkit-trial/from-head", portkit.LEDGER))["transfers"][0]["uncommitted_left_out"])
        self.assertIn("// unsaved", file.read_text(), "the uncommitted work is untouched")
        self.assertEqual(b"", self.file_on("portkit-trial/from-head", "notes.txt"))

    def test_rollback_refuses_a_branch_checked_out_in_a_worktree(self):
        self.apply("portkit-trial/in-use", "lazy-key-collisions")
        other = Path(tempfile.mkdtemp()) / "other"
        self.addCleanup(shutil.rmtree, other.parent, True)
        git(self.target, "worktree", "add", "-q", str(other), "portkit-trial/in-use")
        tip = git(self.target, "rev-parse", "portkit-trial/in-use")
        with self.assertRaises(portkit.TransferError) as refused:
            portkit.rollback(self.target, "portkit-trial/in-use")
        self.assertIn("is checked out at", str(refused.exception))
        self.assertEqual(tip, git(self.target, "rev-parse", "portkit-trial/in-use"))
        git(self.target, "worktree", "remove", "--force", str(other))
        git(self.target, "checkout", "-q", "portkit-trial/in-use")  # the main checkout counts too
        with self.assertRaises(portkit.TransferError):
            portkit.rollback(self.target, "portkit-trial/in-use")
        git(self.target, "checkout", "-q", "main")
        self.assertEqual("rolled-back", portkit.rollback(self.target, "portkit-trial/in-use")["status"])

    def test_the_command_line(self):
        out = subprocess.run(["python3", "-I", str(ROOT / "portkit/portkit.py"), "apply", "--repo", str(ROOT),
                              "--target", str(self.target), "--branch", "portkit-trial/cli", "--trial", "lazy-key-collisions"],
                             capture_output=True, text=True)
        self.assertEqual(0, out.returncode, out.stdout + out.stderr)
        self.assertIn("Applied on branch portkit-trial/cli", out.stdout)
        refused = subprocess.run(["python3", "-I", str(ROOT / "portkit/portkit.py"), "apply", "--repo", str(ROOT),
                                  "--target", str(self.target), "--branch", "portkit-trial/id", "--trial", "identity"],
                                 capture_output=True, text=True)
        self.assertEqual(3, refused.returncode)


if __name__ == "__main__":
    unittest.main()
