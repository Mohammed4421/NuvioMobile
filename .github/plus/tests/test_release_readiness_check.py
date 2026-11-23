import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location(
    "release_readiness_check", ROOT / ".github/plus/release-readiness-check.py")
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


class ReleaseReadinessTest(unittest.TestCase):
    def check(self, items, gates=None):
        if gates is None:
            gates = [{"stage": f"P{n}", "status": "PASS",
                      "owner_approval": {"by": "owner", "on": "2026-11-23", "ref": "fixture"}}
                     for n in range(10)]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "docs/plus/build15"
            path.mkdir(parents=True)
            (path / "requirements.json").write_text(json.dumps({"items": items}), encoding="utf-8")
            (path / "stage-gates.json").write_text(json.dumps({"gates": gates}), encoding="utf-8")
            return guard.check(directory)

    def test_a_complete_pre_release_with_explicit_approvals_passes(self):
        self.assertEqual([], self.check([
            {"id": "P3-01", "mandatory": True,
             "release_class": "PRE-RELEASE REQUIRED", "status": "VERIFIED"},
            {"id": "P5-01", "mandatory": True,
             "release_class": "PRE-RELEASE REQUIRED", "status": "ACCEPTED"},
        ]))

    def test_owner_personal_checks_do_not_block_but_stay_not_tested(self):
        self.assertEqual([], self.check([
            {"id": "PRV-01", "mandatory": True,
             "release_class": "POST-RELEASE VALIDATION", "status": "SPECIFIED"},
            {"id": "P8-06", "mandatory": True,
             "release_class": "POST-RELEASE VALIDATION", "status": "DISCOVERED"},
        ]))

    def test_an_implemented_requirement_blocks_release(self):
        problems = self.check([{"id": "P6-09", "mandatory": True,
                                "release_class": "PRE-RELEASE REQUIRED", "status": "IMPLEMENTED"}])
        self.assertTrue(any("P6-09" in p and "IMPLEMENTED" in p for p in problems))

    def test_unresolved_classification_blocks_even_with_verified_status(self):
        problems = self.check([{"id": "P2-02", "mandatory": True,
                                "release_class": "BLOCKED", "status": "VERIFIED"}])
        self.assertTrue(any("P2-02" in p and "classification" in p for p in problems))

    def test_an_unapproved_or_blocked_phase_blocks_release(self):
        gates = [{"stage": f"P{n}", "status": "PASS",
                  "owner_approval": {"by": "owner", "on": "2026-11-23", "ref": "fixture"}}
                 for n in range(10)]
        gates[5]["status"] = "AWAITING OWNER APPROVAL"
        gates[7]["owner_approval"] = None
        problems = self.check([], gates)
        self.assertTrue(any("P5" in p and "remains" in p for p in problems))
        self.assertTrue(any("P7" in p and "owner approval" in p for p in problems))

    def test_missing_gate_blocks_release(self):
        gates = [{"stage": f"P{n}", "status": "PASS",
                  "owner_approval": {"by": "owner", "on": "2026-11-23", "ref": "fixture"}}
                 for n in range(9)]
        self.assertTrue(any("P9: no phase gate" in p for p in self.check([], gates)))

    def test_current_repository_is_blocked_until_ready(self):
        # The release check must not accidentally treat a structurally valid
        # but still incomplete requirement register as approval to publish.
        problems = guard.check(ROOT)
        register = json.loads((ROOT / "docs/plus/build15/requirements.json").read_text())
        remaining = [i for i in register["items"] if i.get("mandatory")
                     and i.get("release_class") != "POST-RELEASE VALIDATION"
                     and i.get("status") not in guard.COMPLETE]
        if remaining:
            self.assertTrue(problems)


if __name__ == "__main__":
    unittest.main()
