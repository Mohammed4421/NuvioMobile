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

    def apply(self, branch, *features):
        return portkit.apply(ROOT, self.target, list(features), branch)

    def file_on(self, branch, path):
        return subprocess.run(["git", "-C", str(self.target), "show", f"{branch}:{path}"], capture_output=True).stdout

    def test_a_transfer_lands_on_a_new_branch_and_leaves_the_checkout_alone(self):
        before = fixtures.tree_digest(self.target)
        result = self.apply("portkit/portable", "lazy-key-collisions", "subtitle-parser-worst-case")
        self.assertEqual("applied", result["status"], result["plan"])
        self.assertEqual(before, fixtures.tree_digest(self.target), "the checkout changed")
        self.assertEqual(self.head, git(self.target, "rev-parse", "main"))
        self.assertEqual((ROOT / LAZY).read_bytes(), self.file_on("portkit/portable", LAZY))
        self.assertEqual((ROOT / PARSER_TEST).read_bytes(), self.file_on("portkit/portable", PARSER_TEST))
        ledger = json.loads(self.file_on("portkit/portable", portkit.LEDGER))
        self.assertEqual(["lazy-key-collisions", "subtitle-parser-worst-case"], ledger["transfers"][0]["features"])
        self.assertIn("PortKit-Transfer:", git(self.target, "log", "-1", "--format=%B", "portkit/portable"))
        self.assertEqual(1, git(self.target, "worktree", "list", "--porcelain").count("worktree "), "a worktree was left")

    def test_applying_again_changes_nothing(self):
        self.apply("portkit/first", "lazy-key-collisions")
        git(self.target, "checkout", "-q", "portkit/first")
        result = self.apply("portkit/second", "lazy-key-collisions")
        self.assertEqual("already", result["status"])
        self.assertNotIn("portkit/second", git(self.target, "branch", "--list"))

    def test_a_conflict_writes_nothing_and_leaves_no_branch(self):
        file = self.target / LAZY
        file.write_text(file.read_text().replace("val keyCounts = groupingBy(key).eachCount()",
                                                 "val keyCounts = groupingBy(key).eachCount() // fork", 1))
        git(self.target, "-c", "user.name=fork", "-c", "user.email=f@x", "commit", "-q", "-am", "fork change")
        result = self.apply("portkit/conflict", "lazy-key-collisions", "subtitle-parser-worst-case")
        self.assertEqual("conflict", result["status"])
        self.assertNotIn("portkit/conflict", git(self.target, "branch", "--list"))
        self.assertIn("// fork", file.read_text())

    def test_a_failure_while_writing_removes_the_branch(self):
        original = portkit.planned_content
        portkit.planned_content = lambda *args: None
        try:
            with self.assertRaises(portkit.TransferError):
                self.apply("portkit/broken", "lazy-key-collisions")
        finally:
            portkit.planned_content = original
        self.assertNotIn("portkit/broken", git(self.target, "branch", "--list"))

    def test_an_existing_branch_is_not_reused(self):
        git(self.target, "branch", "portkit/taken")
        with self.assertRaises(portkit.TransferError):
            self.apply("portkit/taken", "lazy-key-collisions")

    def test_refused_features_leave_no_branch(self):
        self.assertEqual("refused", self.apply("portkit/identity", "identity")["status"])
        self.assertNotIn("portkit/identity", git(self.target, "branch", "--list"))

    def test_rollback_undoes_only_what_portkit_wrote(self):
        self.apply("portkit/both", "lazy-key-collisions", "subtitle-parser-worst-case")
        # After the transfer the fork edits one of the transferred files.
        git(self.target, "checkout", "-q", "portkit/both")
        edited = self.target / LAZY_TEST
        edited.write_text(edited.read_text() + "\n// fork addition\n")
        git(self.target, "-c", "user.name=fork", "-c", "user.email=f@x", "commit", "-q", "-am", "fork edit")
        git(self.target, "checkout", "-q", "main")
        result = portkit.rollback(self.target, "portkit/both")
        self.assertEqual("rolled-back", result["status"])
        self.assertEqual([LAZY_TEST], result["kept"])
        upstream = subprocess.run(["git", "-C", str(ROOT), "show", f"{fixtures.BASE}:{LAZY}"], capture_output=True).stdout
        self.assertEqual(upstream, self.file_on("portkit/both", LAZY))
        self.assertEqual(b"", self.file_on("portkit/both", PARSER_TEST), "a created file is removed")
        self.assertIn(b"// fork addition", self.file_on("portkit/both", LAZY_TEST))

    def test_the_command_line(self):
        out = subprocess.run(["python3", "-I", str(ROOT / "portkit/portkit.py"), "apply", "--repo", str(ROOT),
                              "--target", str(self.target), "--branch", "portkit/cli", "lazy-key-collisions"],
                             capture_output=True, text=True)
        self.assertEqual(0, out.returncode, out.stdout + out.stderr)
        self.assertIn("Applied on branch portkit/cli", out.stdout)
        refused = subprocess.run(["python3", "-I", str(ROOT / "portkit/portkit.py"), "apply", "--repo", str(ROOT),
                                  "--target", str(self.target), "--branch", "portkit/id", "identity"],
                                 capture_output=True, text=True)
        self.assertEqual(3, refused.returncode)


if __name__ == "__main__":
    unittest.main()
