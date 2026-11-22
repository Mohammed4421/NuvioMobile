"""Tests of check-requirements.py: the Build 15 register may not claim more than its evidence shows."""
import copy
import importlib.util
import json
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("check_requirements", ROOT / ".github/plus/check-requirements.py")
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


def git(repo, *args):
    return subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True, check=True).stdout.strip()


class Gate(unittest.TestCase):
    """A small repository with one verified requirement whose evidence is a CI run on its first commit."""

    def setUp(self):
        self.repo = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.repo)
        git(self.repo, "init", "-q")
        git(self.repo, "config", "user.email", "t@t")
        git(self.repo, "config", "user.name", "t")
        (self.repo / "app").mkdir()
        (self.repo / "app/Feature.kt").write_text("fun feature() = 1\n")
        (self.repo / "app/FeatureTest.kt").write_text("fun featureWorks() {}\n")
        (self.repo / "docs/plus/build15").mkdir(parents=True)
        for rel, key in ((gate.SIDE["stage_gates"], "gates"), (gate.SIDE["carryover"], "entries"),
                         (gate.SIDE["risks"], "risks"), (gate.SIDE["changes"], "changes")):
            (self.repo / rel).write_text(json.dumps({key: []}))
        git(self.repo, "add", "-A")
        git(self.repo, "commit", "-q", "-m", "one")
        self.first = git(self.repo, "rev-parse", "HEAD")
        items = [self.item(rid) for rid in gate.MANDATORY]
        for phase in gate.PHASES:
            if not any(i["phase"] == phase for i in items):
                items.append(dict(self.item(f"{phase}-99"), phase=phase))
        verified = next(i for i in items if i["id"] == "P0-01")
        verified.update(status="VERIFIED", paths=["app/"], evidence=[
            {"kind": "ci-run", "run": 1, "commit": self.first, "result": "PASS"},
            {"kind": "test", "file": "app/FeatureTest.kt", "name": "featureWorks"},
        ])
        for number, entry in enumerate(items, start=1):
            entry["nvp15"] = f"NVP15-REQ-{number:04d}"
        self.register = {"items": items}
        self.runs = {1: {"conclusion": "success", "head_sha": self.first}}

    @staticmethod
    def item(rid):
        return {"id": rid, "spec": "spec", "phase": rid.split("-")[0] if rid.split("-")[0] in gate.PHASES + ["D", "G"] else "G", "mandatory": True, "requirement": "r", "source": "s",
                "paths": [], "status": "DISCOVERED", "evidence": [], "untested": [],
                "needs_physical_device": False, "acceptance": "a", "next": "n"}

    def p001(self, register=None):
        return next(i for i in (register or self.register)["items"] if i["id"] == "P0-01")

    def problems(self, register=None, online=False):
        fetch = (lambda run: copy.deepcopy(self.runs[run])) if online else None
        return gate.check(register or self.register, self.repo, fetch)

    def assertRejects(self, text, register=None, online=False):
        problems = self.problems(register, online)
        self.assertTrue(any(text in p for p in problems), f"expected '{text}' in {problems}")

    def test_a_register_whose_claims_hold_passes(self):
        self.assertEqual([], self.problems())
        self.assertEqual([], self.problems(online=True))

    def test_the_real_register_passes_offline(self):
        register = json.loads((ROOT / gate.REGISTER).read_text())
        # Staleness needs the branch's history (the CI job checks out all of it); here only the
        # register's own content is checked.
        for item in register["items"]:
            item["paths"] = []
        self.assertEqual([], gate.check(register, ROOT))

    # 1. Every mandatory requirement, every phase.
    def test_a_missing_mandatory_requirement_fails(self):
        self.register["items"] = [i for i in self.register["items"] if i["id"] != "P4-01"]
        self.assertRejects("P4-01: mandatory requirement missing")

    def test_a_mandatory_requirement_marked_optional_fails(self):
        next(i for i in self.register["items"] if i["id"] == "P5-01")["mandatory"] = False
        self.assertRejects("P5-01: mandatory requirement marked optional")

    def test_a_phase_without_requirements_fails(self):
        self.register["items"] = [i for i in self.register["items"] if i["phase"] != "P2"]
        self.assertRejects("P2: no requirement")

    def test_unknown_statuses_and_duplicate_ids_fail(self):
        self.p001()["status"] = "DONE"
        self.register["items"].append(self.item("C-01"))
        self.assertRejects("unknown status 'DONE'")
        self.assertRejects("C-01: listed twice")

    # 2. Verified needs repeatable evidence.
    def test_verified_without_evidence_fails(self):
        self.p001()["evidence"] = []
        self.assertRejects("P0-01: VERIFIED without repeatable evidence")

    def test_a_review_alone_does_not_verify(self):
        (self.repo / "notes.md").write_text("looked at it\n")
        self.p001()["evidence"] = [{"kind": "review", "file": "notes.md"}]
        self.assertRejects("P0-01: VERIFIED without repeatable evidence")

    def test_a_journey_is_not_verified_by_unit_tests_alone(self):
        journey = self.item("P3-03")
        journey.update(status="VERIFIED", evidence=[{"kind": "test", "file": "notes.md", "name": "x"}])
        (self.repo / "notes.md").write_text("x\n")
        self.register["items"] = [i for i in self.register["items"] if i["id"] != "P3-03"] + [journey]
        self.assertRejects("P3-03: VERIFIED journey on unit tests alone (P3-07)")

    # 3. Physical device.
    def test_a_device_requirement_is_not_verified_by_ci(self):
        self.p001()["needs_physical_device"] = True
        self.assertRejects("needs a physical device")

    # 4. Acceptance is the owner's.
    def test_accepted_needs_the_owners_acceptance(self):
        self.p001()["status"] = "ACCEPTED"
        self.assertRejects("ACCEPTED without the owner's acceptance")
        self.p001()["accepted"] = {"by": "someone", "on": "2026-11-21", "ref": "https://github.com/x/pull/1"}
        self.assertRejects("ACCEPTED without the owner's acceptance")
        for by in ("owner", "delegated"):
            self.p001()["accepted"]["by"] = by
            self.assertEqual([], self.problems(), by)

    # 5. Failed or pending results presented as passing.
    def test_failed_or_pending_evidence_cannot_verify(self):
        for result in ("FAIL", "BLOCKED", "NOT TESTED", "NEEDS PHYSICAL DEVICE", "pending", "cancelled", "skipped"):
            register = copy.deepcopy(self.register)
            self.p001(register)["evidence"][0]["result"] = result
            self.assertRejects(f"result '{result.lower()}' cannot support VERIFIED", register)

    def test_an_open_item_may_cite_a_failed_run(self):
        self.p001()["status"] = "IMPLEMENTED"
        self.p001()["evidence"][0]["result"] = "failure"
        self.assertEqual([], self.problems())

    # 6. Cited tests exist.
    def test_a_cited_test_that_does_not_exist_fails(self):
        self.p001()["evidence"][1]["name"] = "featureIsFast"
        self.assertRejects("test 'featureIsFast' not found")
        self.p001()["evidence"][1]["file"] = "app/Gone.kt"
        self.assertRejects("test file 'app/Gone.kt' not in the repository")

    def test_a_ci_run_needs_a_run_number_and_a_full_commit(self):
        self.p001()["evidence"][0]["commit"] = self.first[:8]
        self.assertRejects("full 40-character 'commit'")

    # 7. Stale evidence.
    def test_a_change_after_the_evidence_fails_until_the_register_follows(self):
        (self.repo / "app/Feature.kt").write_text("fun feature() = 2\n")
        git(self.repo, "commit", "-qam", "two")
        self.assertRejects("P0-01: changed after its evidence")
        self.p001()["status"] = "IMPLEMENTED"
        self.assertEqual([], self.problems())

    def test_a_change_outside_the_requirement_keeps_it_verified(self):
        (self.repo / "README.md").write_text("x\n")
        git(self.repo, "add", "-A")
        git(self.repo, "commit", "-qm", "docs")
        self.assertEqual([], self.problems())

    def test_evidence_from_another_branch_does_not_verify_this_one(self):
        self.p001()["evidence"][0]["commit"] = "0" * 40
        self.assertRejects("not in this branch's history")

    # 8. Online: the run itself.
    def test_a_run_that_did_not_succeed_fails_online(self):
        self.runs[1]["conclusion"] = "failure"
        self.assertRejects("run 1 concluded 'failure'", online=True)

    def test_a_run_on_another_commit_fails_online(self):
        self.runs[1]["head_sha"] = "1" * 40
        self.assertRejects("run 1 ran on 11111111", online=True)

    def test_a_run_that_cannot_be_read_fails_online(self):
        del self.runs[1]
        self.assertRejects("could not read run 1", online=True)


class Traceability(Gate):
    """Spec v1.1 §19–25: identifiers and the side registers."""

    def side(self, key, entries):
        rel = gate.SIDE[key]
        name = {"stage_gates": "gates", "carryover": "entries", "risks": "risks", "changes": "changes"}[key]
        (self.repo / rel).write_text(json.dumps({name: entries}))

    def test_a_missing_or_repeated_identifier_fails(self):
        items = self.register["items"]
        items[1]["nvp15"] = items[0]["nvp15"]
        del items[2]["nvp15"]
        problems = " | ".join(self.problems())
        self.assertIn("also used by", problems)
        self.assertIn("no NVP15-REQ-#### identifier", problems)

    def test_a_gate_cannot_pass_with_open_requirements_or_without_the_owner(self):
        self.side("stage_gates", [{"stage": "P0", "status": "PASS", "requirements": ["P0-01"], "owner_approval": None}])
        problems = " | ".join(self.problems())
        self.assertIn("PASS with mandatory requirements not verified", problems)
        self.assertIn("PASS without the owner's decision", problems)

    def test_only_the_spec_gate_states_are_allowed(self):
        self.side("stage_gates", [{"stage": "P1", "status": "PASS WITH CRITICAL GAPS"}])
        self.assertTrue(any("is not one of" in p for p in self.problems()))

    def test_a_risk_needs_every_field_and_known_requirements(self):
        self.side("risks", [{"id": "NVP15-RISK-0001", "state": "BLOCKED", "reason": "x", "needs": "", "impact": "i",
                             "priority": "P0", "decision_owner": "owner", "closes_when": "c", "requirements": ["NO-SUCH"]}])
        problems = " | ".join(self.problems())
        self.assertIn("field 'needs' empty", problems)
        self.assertIn("unknown requirement NO-SUCH", problems)

    def test_carryover_states_follow_the_spec(self):
        self.side("carryover", [{"source": "#1", "state": "DONE", "requirements": ["P0-01"]}])
        self.assertTrue(any("carry-over #1" in p for p in self.problems()))

    def test_the_real_side_registers_hold(self):
        register = json.loads((ROOT / gate.REGISTER).read_text())
        self.assertEqual([], [p for p in gate.check_side(ROOT, {i["id"]: i for i in register["items"]})])


if __name__ == "__main__":
    unittest.main()
