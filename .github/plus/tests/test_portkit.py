"""PortKit foundation: the registry, the compatibility overlay and the policy agree, and every Nuvio
Plus change is owned by a feature."""
import copy
import importlib.util
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("portkit", ROOT / "portkit/portkit.py")
portkit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(portkit)


class RealRepository(unittest.TestCase):
    def test_the_registry_overlay_and_policy_are_consistent(self):
        self.assertEqual([], portkit.validate(ROOT))

    def test_every_plus_change_is_owned_by_a_feature(self):
        base = json.loads((ROOT / "portkit/compatibility.json").read_text())["upstream_base"]["commit"]
        present = subprocess.run(["git", "-C", str(ROOT), "cat-file", "-e", f"{base}^{{commit}}"]).returncode == 0
        if not present:
            if os.environ.get("CI"):
                self.fail(f"the upstream base {base} was not fetched for this check")
            self.skipTest("upstream base not fetched")
        data = portkit.inventory(ROOT, base)
        self.assertEqual([], data["unowned_changes"])

    def test_identity_and_signing_are_never_portable(self):
        overlay = json.loads((ROOT / "portkit/compatibility.json").read_text())["features"]
        self.assertEqual("unsupported", overlay["identity"]["level"])
        self.assertEqual("unsupported", overlay["updater"]["level"])
        policy = json.loads((ROOT / "portkit/policy.json").read_text())["forbidden"]
        for path in ("signing/nuvio-plus.p12", "app/release.jks", "local.properties", "androidApp/local.properties",
                     ".github/workflows/custom-android-build.yml", "x/google-services.json"):
            self.assertIsNotNone(portkit.forbidden_match(path, policy), path)
        self.assertIsNone(portkit.forbidden_match("composeApp/src/commonMain/kotlin/A.kt", policy))

    def test_only_portable_features_with_evidence_are_verified(self):
        overlay = json.loads((ROOT / "portkit/compatibility.json").read_text())["features"]
        for fid, entry in overlay.items():
            if entry["verified"]:
                self.assertEqual("portable", entry["level"], fid)
                self.assertIn("/actions/runs/", entry["evidence"]["run"], fid)


class BrokenRegistries(unittest.TestCase):
    """validate() on a copy of the real files, each broken in one way."""

    def setUp(self):
        self.temp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.temp)
        for name in (".github/plus/features.json", "portkit/compatibility.json", "portkit/policy.json"):
            (self.temp / name).parent.mkdir(parents=True, exist_ok=True)
            shutil.copy(ROOT / name, self.temp / name)
        self.registry = json.loads((ROOT / ".github/plus/features.json").read_text())
        self.overlay = json.loads((ROOT / "portkit/compatibility.json").read_text())
        for feature in self.registry["features"]:
            for path in portkit.feature_paths(feature):
                (self.temp / path).parent.mkdir(parents=True, exist_ok=True)
                (self.temp / path).touch()

    def problems(self, registry=None, overlay=None):
        (self.temp / ".github/plus/features.json").write_text(json.dumps(registry or self.registry))
        (self.temp / "portkit/compatibility.json").write_text(json.dumps(overlay or self.overlay))
        return portkit.validate(self.temp)

    def feature(self, registry, fid):
        return next(f for f in registry["features"] if f["id"] == fid)

    def test_the_copy_is_consistent(self):
        self.assertEqual([], self.problems())

    def test_a_missing_file_is_reported(self):
        (self.temp / "composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/DuplicateSafeLazyKeys.kt").unlink()
        self.assertTrue(any("does not exist" in p for p in self.problems()))

    def test_a_dependency_cycle_is_reported(self):
        registry = copy.deepcopy(self.registry)
        self.feature(registry, "player-engines")["depends"] = ["subtitle-style"]
        self.assertTrue(any("cycle" in p for p in self.problems(registry=registry)))

    def test_an_unknown_dependency_is_reported(self):
        registry = copy.deepcopy(self.registry)
        self.feature(registry, "arabic")["depends"] = ["no-such-feature"]
        self.assertTrue(any("unknown feature 'no-such-feature'" in p for p in self.problems(registry=registry)))

    def test_a_signing_file_in_a_portable_feature_is_refused(self):
        registry = copy.deepcopy(self.registry)
        self.feature(registry, "lazy-key-collisions")["files"].append("signing/nuvio-plus.p12")
        (self.temp / "signing").mkdir(exist_ok=True)
        (self.temp / "signing/nuvio-plus.p12").touch()
        self.assertTrue(any("forbidden by signing/**" in p for p in self.problems(registry=registry)))

    def test_a_portable_feature_cannot_depend_on_an_unsupported_one(self):
        registry = copy.deepcopy(self.registry)
        self.feature(registry, "lazy-key-collisions")["depends"] = ["identity"]
        self.assertTrue(any("depends on unsupported 'identity'" in p for p in self.problems(registry=registry)))

    def test_an_undeclared_shared_file_is_reported(self):
        overlay = copy.deepcopy(self.overlay)
        del overlay["shared_files"]["composeApp/src/commonMain/kotlin/com/nuvio/app/MainAppContent.kt"]
        self.assertTrue(any("MainAppContent.kt is changed by" in p for p in self.problems(overlay=overlay)))

    def test_wrong_owners_of_a_shared_file_are_reported(self):
        overlay = copy.deepcopy(self.overlay)
        overlay["shared_files"]["composeApp/src/commonMain/kotlin/com/nuvio/app/MainAppContent.kt"]["owners"] = ["arabic"]
        self.assertTrue(any("shared_files owners" in p for p in self.problems(overlay=overlay)))

    def test_a_feature_without_a_level_or_an_unknown_level_is_reported(self):
        overlay = copy.deepcopy(self.overlay)
        del overlay["features"]["arabic"]
        overlay["features"]["lazy-key-collisions"]["level"] = "easy"
        problems = self.problems(overlay=overlay)
        self.assertTrue(any("no level for registry feature 'arabic'" in p for p in problems))
        self.assertTrue(any("lazy-key-collisions has level 'easy'" in p for p in problems))

    def test_verified_needs_the_evidence_of_a_passing_run(self):
        overlay = copy.deepcopy(self.overlay)
        overlay["features"]["lazy-key-collisions"]["verified"] = True
        overlay["features"]["lazy-key-collisions"].pop("evidence", None)
        self.assertTrue(any("verified without 'evidence'" in p for p in self.problems(overlay=overlay)))
        good = {"run": "https://github.com/Mohammed4421/NuvioMobile/actions/runs/1", "commit": "a" * 40}
        overlay["features"]["lazy-key-collisions"]["evidence"] = good
        self.assertEqual([], self.problems(overlay=overlay))
        overlay["features"]["identity"].update(verified=True, evidence=good)
        self.assertTrue(any("unsupported and cannot be verified" in p for p in self.problems(overlay=overlay)))

    def test_an_overlay_feature_missing_from_the_registry_is_reported(self):
        overlay = copy.deepcopy(self.overlay)
        overlay["features"]["ghost"] = {"level": "portable", "verified": False, "reason": "x"}
        self.assertTrue(any("'ghost' is not in the registry" in p for p in self.problems(overlay=overlay)))


class DependencyOrder(unittest.TestCase):
    def test_dependencies_come_first(self):
        registry = json.loads((ROOT / ".github/plus/features.json").read_text())
        order = portkit.dependency_order(registry["features"], ["library-downloads"])
        self.assertEqual(["download-location", "player-engines", "subtitle-style", "season-downloads", "library-downloads"], order)



class Reports(unittest.TestCase):
    def test_the_committed_matrix_matches_the_overlay(self):
        self.assertEqual(portkit.matrix_markdown(ROOT), (ROOT / "portkit/reports/compatibility-matrix.md").read_text())


if __name__ == "__main__":
    unittest.main()
