"""PortKit dry run on real Nuvio trees: a clean upstream 1.0.0 and forks that changed things."""
import hashlib
import importlib.util
import io
import json
import os
import shutil
import subprocess
import tarfile
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("portkit", ROOT / "portkit/portkit.py")
portkit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(portkit)
BASE = json.loads((ROOT / "portkit/compatibility.json").read_text())["upstream_base"]["commit"]
HAVE_BASE = subprocess.run(["git", "-C", str(ROOT), "cat-file", "-e", f"{BASE}^{{commit}}"]).returncode == 0
LAZY = "composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/DuplicateSafeLazyKeys.kt"
ANCHORS = ["settings.gradle.kts", "composeApp/build.gradle.kts", "composeApp/src/commonMain/kotlin/com/nuvio/app/App.kt"]


def upstream_tree(paths):
    """A target directory holding upstream 1.0.0's version of the given paths (those that exist there)."""
    target = Path(tempfile.mkdtemp())
    present = [p for p in paths if subprocess.run(["git", "-C", str(ROOT), "cat-file", "-e", f"{BASE}:{p}"],
                                                 capture_output=True).returncode == 0]
    archive = subprocess.run(["git", "-C", str(ROOT), "archive", BASE, "--", *present], capture_output=True, check=True).stdout
    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
        tar.extractall(target, filter="data")
    return target


def tree_digest(directory):
    digest = hashlib.sha256()
    for path in sorted(p for p in Path(directory).rglob("*") if p.is_file()):
        digest.update(str(path.relative_to(directory)).encode() + b"\0" + path.read_bytes())
    return digest.hexdigest()


@unittest.skipUnless(HAVE_BASE or os.environ.get("CI"), "upstream base not fetched")
class Plan(unittest.TestCase):
    def setUp(self):
        if not HAVE_BASE:
            self.fail(f"the upstream base {BASE} was not fetched for this check")
        registry = json.loads((ROOT / ".github/plus/features.json").read_text())
        paths = set(ANCHORS)
        for feature in registry["features"]:
            paths.update(portkit.feature_paths(feature))
            paths.update(portkit.resolve(p) for p in feature.get("removed_files", []))
        self.target = upstream_tree(sorted(p for p in paths if not p.startswith(".github/")))
        self.addCleanup(shutil.rmtree, self.target)

    def plan(self, *features, trial=True):
        return portkit.plan(ROOT, self.target, list(features), trial=trial)

    def test_a_dry_run_changes_nothing_in_the_target(self):
        before = tree_digest(self.target)
        report = self.plan("lazy-key-collisions", "subtitle-parser-worst-case")
        portkit.plan_diff(ROOT, self.target, report)
        self.assertEqual(before, tree_digest(self.target))

    def test_portable_features_are_ready_on_clean_upstream(self):
        report = self.plan("lazy-key-collisions", "subtitle-parser-worst-case")
        self.assertEqual("ready", report["status"], report)
        actions = {a["path"]: a["action"] for f in report["features"].values() for a in f["actions"]}
        self.assertEqual("patch-exact", actions[LAZY])
        self.assertEqual("create", actions["composeApp/src/commonTest/kotlin/com/nuvio/app/features/player/SubtitleParserWorstCaseTest.kt"])
        diff = portkit.plan_diff(ROOT, self.target, report)
        self.assertIn("+++ b/" + LAZY, diff)
        self.assertIn("reservedKeys", diff)

    def test_a_target_that_already_has_the_feature_needs_nothing(self):
        for path in ("composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/DuplicateSafeLazyKeys.kt",
                     "composeApp/src/commonTest/kotlin/com/nuvio/app/core/ui/DuplicateSafeLazyKeysTest.kt"):
            (self.target / path).write_bytes((ROOT / path).read_bytes())
        self.assertEqual("already", self.plan("lazy-key-collisions")["status"])

    def test_a_fork_change_elsewhere_in_the_file_still_applies(self):
        file = self.target / LAZY
        file.write_text(file.read_text().replace("package com.nuvio.app.core.ui\n",
                                                 "package com.nuvio.app.core.ui\n\n// A fork's own note.\n", 1))
        report = self.plan("lazy-key-collisions")
        self.assertEqual("ready", report["status"], report)
        action = next(a for a in report["features"]["lazy-key-collisions"]["actions"] if a["path"] == LAZY)
        self.assertEqual("patch-context", action["action"])
        result = portkit.planned_content(ROOT, self.target, report, action).decode()
        self.assertIn("// A fork's own note.", result)
        self.assertIn("reservedKeys", result)

    def test_a_fork_change_where_the_feature_changes_is_a_conflict(self):
        file = self.target / LAZY
        file.write_text(file.read_text().replace("val keyCounts = groupingBy(key).eachCount()",
                                                 "val keyCounts = groupingBy(key).eachCount() // fork", 1))
        report = self.plan("lazy-key-collisions")
        self.assertEqual("conflict", report["status"])
        action = next(a for a in report["features"]["lazy-key-collisions"]["actions"] if a["path"] == LAZY)
        self.assertEqual("conflict", action["action"])
        self.assertEqual("ready", self.plan("subtitle-parser-worst-case")["status"], "other features are not blocked")

    def test_a_fork_file_at_a_new_path_is_not_overwritten(self):
        path = self.target / "composeApp/src/commonTest/kotlin/com/nuvio/app/features/player/SubtitleParserWorstCaseTest.kt"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("// the fork's own test\n")
        report = self.plan("subtitle-parser-worst-case")
        self.assertEqual("conflict", report["status"])

    def test_identity_and_the_updater_are_refused(self):
        for feature in ("identity", "updater", "account-and-sync"):
            report = self.plan(feature)
            self.assertEqual("refused", report["status"], feature)
            self.assertEqual({}, report["features"])

    def test_one_owner_of_a_shared_file_alone_is_refused(self):
        report = self.plan("addon-link-confirmation")
        self.assertEqual("refused", report["status"])
        self.assertIn("add library-downloads", " ".join(report["refused"]))

    def test_dependencies_are_planned_first(self):
        report = self.plan("subtitle-style")
        self.assertEqual(["player-engines", "subtitle-style"], list(report["features"]))

    def test_an_unknown_feature_is_refused(self):
        self.assertEqual("refused", self.plan("no-such-feature")["status"])

    def test_a_project_that_is_not_nuvio_is_unsupported(self):
        (self.target / "settings.gradle.kts").write_text('rootProject.name = "other"\n')
        report = self.plan("lazy-key-collisions")
        self.assertEqual("unsupported-target", report["status"])
        self.assertEqual({}, report["features"])

    def test_outside_a_trial_only_portable_verified_features_are_planned(self):
        overlay = json.loads((ROOT / "portkit/compatibility.json").read_text())["features"]
        for feature in ("subtitle-style", "player-engines", "arabic"):  # adapter-required, applies cleanly
            self.assertEqual("ready", self.plan(feature)["status"], feature)
            report = self.plan(feature, trial=False)
            self.assertEqual("refused", report["status"], feature)
            self.assertEqual({}, report["features"])
        for feature in ("season-downloads", "download-location"):  # manual
            report = self.plan(feature, trial=False)
            self.assertEqual("refused", report["status"], feature)
            self.assertIn("only portable features are transferred", " ".join(report["refused"]))
        for feature in ("lazy-key-collisions", "subtitle-parser-worst-case"):  # portable
            report = self.plan(feature, trial=False)
            if overlay[feature]["verified"]:
                self.assertEqual("ready", report["status"], feature)
            else:
                self.assertEqual("refused", report["status"], feature)
                self.assertIn("not verified", " ".join(report["refused"]))

    def test_a_trial_never_allows_unsupported_features(self):
        for feature in ("identity", "updater"):
            self.assertEqual("refused", self.plan(feature, trial=True)["status"])

    def test_the_command_line_writes_plan_and_diff_and_signals_conflicts(self):
        with tempfile.TemporaryDirectory() as out:
            ok = subprocess.run(["python3", "-I", str(ROOT / "portkit/portkit.py"), "plan", "--repo", str(ROOT),
                                 "--target", str(self.target), "--json", f"{out}/plan.json", "--diff", f"{out}/plan.diff",
                                 "--trial", "lazy-key-collisions"], capture_output=True, text=True)
            self.assertEqual(0, ok.returncode, ok.stdout + ok.stderr)
            self.assertEqual("ready", json.loads(Path(out, "plan.json").read_text())["status"])
            self.assertIn("reservedKeys", Path(out, "plan.diff").read_text())
            refused = subprocess.run(["python3", "-I", str(ROOT / "portkit/portkit.py"), "plan", "--repo", str(ROOT),
                                      "--target", str(self.target), "identity"], capture_output=True, text=True)
            self.assertEqual(3, refused.returncode)


if __name__ == "__main__":
    unittest.main()
