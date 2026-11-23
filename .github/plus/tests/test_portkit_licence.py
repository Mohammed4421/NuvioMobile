"""PortKit and licences (Build 15 P7-04, P7-08 test 10): a target must be GPL-3.0 like Nuvio Plus, and a
transfer keeps the target's LICENSE and its own copyright and licence notices."""
import importlib.util
import json
import os
import shutil
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("test_portkit_plan", Path(__file__).with_name("test_portkit_plan.py"))
fixtures = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixtures)
portkit = fixtures.portkit
LAZY = fixtures.LAZY
NOTICE = "// Copyright (C) 2026 Fork A authors. SPDX-License-Identifier: GPL-3.0-or-later\n"


def git(repo, *args):
    return subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True, check=True).stdout.strip()


def commit(repo, message):
    git(repo, "add", "-A")
    git(repo, "-c", "user.name=fork", "-c", "user.email=f@x", "commit", "-q", "-m", message)


@unittest.skipUnless(fixtures.HAVE_BASE or os.environ.get("CI"), "upstream base not fetched")
class Licence(unittest.TestCase):
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
        commit(self.target, "fork at 1.0.0")
        self.overlay = json.loads((ROOT / "portkit/compatibility.json").read_text())

    def test_the_contract_names_the_licence_of_nuvio_plus(self):
        needle = self.overlay["target_contract"]["required_text"]["LICENSE"]
        self.assertIn(needle, (ROOT / "LICENSE").read_text(), "the contract must match Nuvio Plus's own licence")
        self.assertEqual([], portkit.check_target(self.target, self.overlay), "upstream 1.0.0 is GPL-3.0")

    def test_a_target_without_a_licence_or_under_another_one_is_refused(self):
        (self.target / "LICENSE").unlink()
        self.assertTrue(any("LICENSE" in p for p in portkit.check_target(self.target, self.overlay)))
        for other in ("MIT License\n\nPermission is hereby granted, free of charge\n",
                      "                    GNU GENERAL PUBLIC LICENSE\n                       Version 2, June 1991\n",
                      "                   GNU LESSER GENERAL PUBLIC LICENSE\n                       Version 3, 29 June 2007\n"):
            (self.target / "LICENSE").write_text(other)
            self.assertTrue(any("LICENSE" in p for p in portkit.check_target(self.target, self.overlay)), other[:40])
        commit(self.target, "relicensed")
        report = portkit.plan(ROOT, self.target, ["lazy-key-collisions"], trial=True)
        self.assertEqual("unsupported-target", report["status"])
        self.assertTrue(any("LICENSE" in p for p in report["refused"]), report["refused"])
        self.assertEqual({}, report["features"], "nothing is planned for the target")
        result = portkit.apply(ROOT, self.target, ["lazy-key-collisions"], "portkit-trial/relicensed", trial=True)
        self.assertNotEqual("applied", result["status"])
        self.assertNotIn("portkit-trial/relicensed", git(self.target, "branch", "--list"))

    def test_a_transfer_keeps_the_licence_and_the_targets_own_notices(self):
        licence = (self.target / "LICENSE").read_bytes()
        file = self.target / LAZY
        file.write_text(NOTICE + file.read_text())
        commit(self.target, "fork notice")
        result = portkit.apply(ROOT, self.target, ["lazy-key-collisions"], "portkit-trial/licence", trial=True)
        self.assertEqual("applied", result["status"], result["plan"])
        moved = subprocess.run(["git", "-C", str(self.target), "show", f"portkit-trial/licence:{LAZY}"],
                               capture_output=True, text=True, check=True).stdout
        self.assertTrue(moved.startswith(NOTICE), "the fork's copyright line was lost")
        self.assertEqual(licence, subprocess.run(["git", "-C", str(self.target), "show", "portkit-trial/licence:LICENSE"],
                                                 capture_output=True, check=True).stdout)
        changed = git(self.target, "diff", "--name-only", "main", "portkit-trial/licence").split()
        self.assertNotIn("LICENSE", changed)
        self.assertFalse([p for p in changed if Path(p).name.upper().startswith(("LICENSE", "NOTICE", "COPYING"))])

    def test_no_feature_lists_a_licence_file(self):
        registry = json.loads((ROOT / ".github/plus/features.json").read_text())
        for feature in registry["features"]:
            for path in portkit.feature_paths(feature):
                self.assertFalse(Path(path).name.upper().startswith(("LICENSE", "NOTICE", "COPYING")), path)


if __name__ == "__main__":
    unittest.main()
