"""Spec §4: every audit finding carries a stable ID, files, reproducible evidence, root cause, user
impact, severity (Critical/High/Medium/Low), alternatives, the chosen solution and why, its
compatibility/maintenance impact, required tests and its status, with a KEEP/FIX/REFACTOR/REMOVE/
DEFER decision; and findings.json and the audit table list the same findings."""
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
FINDINGS = ROOT / "docs/plus/build15/findings.json"
AUDIT = ROOT / "docs/plus/build15/audit.md"
SEVERITIES = {"Critical", "High", "Medium", "Low"}
DECISIONS = {"KEEP", "FIX", "REFACTOR", "REMOVE", "DEFER"}
STATUSES = {"DISCOVERED", "SPECIFIED", "IMPLEMENTED", "VERIFIED", "ACCEPTED"}
PRIORITIES = {"P0", "P1", "P2", "P3"}  # spec §14
TEXT = ("area", "evidence", "root_cause", "user_impact", "chosen", "rationale", "compatibility")


class AuditFindings(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.findings = json.loads(FINDINGS.read_text())["findings"]

    def test_ids_are_unique_and_stable(self):
        ids = [f["id"] for f in self.findings]
        self.assertEqual(len(ids), len(set(ids)))
        for i in ids:
            self.assertRegex(i, r"^B15-[A-Z]+-\d{2}$")

    def test_every_finding_has_every_field(self):
        for f in self.findings:
            for field in TEXT:
                self.assertTrue(str(f.get(field, "")).strip(), f"{f['id']}: {field} empty")
            self.assertIsInstance(f.get("files"), list, f["id"])
            self.assertIn(f.get("decision"), DECISIONS, f["id"])
            self.assertIn(f.get("status"), STATUSES, f["id"])
            self.assertIn(f.get("kind"), ("defect", "review", "claim"), f["id"])

    def test_defects_are_classified(self):
        for f in self.findings:
            if f["kind"] != "defect":
                self.assertEqual("n/a", f["severity"], f"{f['id']}: a non-defect has no severity")
                continue
            self.assertIn(f["severity"], SEVERITIES, f["id"])
            self.assertIn(f["priority"], PRIORITIES, f["id"])
            self.assertTrue(f["alternatives"], f"{f['id']}: a defect needs the alternatives considered")
            self.assertTrue(f["files"], f"{f['id']}: a defect names its files")

    def test_a_fix_names_its_tests(self):
        for f in self.findings:
            if f["decision"] == "FIX" and f["files"]:
                self.assertTrue(f["tests_required"], f"{f['id']}: a fix names the tests that prove it")

    def test_verified_defects_have_repeatable_evidence(self):
        for f in self.findings:
            if f["kind"] == "defect" and f["status"] in ("VERIFIED", "ACCEPTED"):
                self.assertTrue(any(re.search(r"CI \d+|#\d+", r) for r in f["refs"]), f"{f['id']}: no CI run or PR")

    def test_the_audit_table_and_the_findings_match(self):
        table = set(re.findall(r"^\| (B15-[A-Z]+-\d{2}) \|", AUDIT.read_text(), re.M))
        full = set(re.findall(r"^### (B15-[A-Z]+-\d{2})", AUDIT.read_text(), re.M))
        self.assertEqual(table | full, {f["id"] for f in self.findings})


    def test_every_audit_axis_has_findings_or_says_reviewed_none(self):
        """P1-03: the spec's eleven axes, each with existing findings or an explicit 'reviewed, none'."""
        axes = ["Architecture", "Behaviour", "Performance", "Security", "Concurrency", "UX",
                "Accessibility, localisation, RTL", "Storage and migration", "Testability",
                "Upstream compatibility", "Portability"]
        section = AUDIT.read_text().split("## Audit axes (P1-03)", 1)[1].split("\n## ", 1)[0]
        rows = dict(re.findall(r"^\| ([^|]+?) \| (.+?) \|$", section, re.M))
        known = {f["id"] for f in self.findings}
        for axis in axes:
            self.assertIn(axis, rows, f"axis {axis} missing")
            cell = rows[axis]
            named = re.findall(r"B15-[A-Z]+-\d{2}", cell)
            self.assertTrue(named or cell.startswith("reviewed, none:"), f"{axis}: no finding and no 'reviewed, none'")
            for finding in named:
                self.assertIn(finding, known, f"{axis} names {finding}, which is not in findings.json")

    def test_every_deferred_finding_has_a_low_priority_and_its_risk(self):
        """P1-06 / spec §14: a deferred finding is P2 or P3 and states its impact and why it waits."""
        deferred = [f for f in self.findings if f["decision"] == "DEFER"]
        self.assertTrue(deferred)
        for f in deferred:
            self.assertIn(f["priority"], {"P2", "P3"}, f"{f['id']} is deferred at {f['priority']}")
            self.assertNotEqual("Critical", f["severity"], f["id"])
            self.assertGreater(len(f["user_impact"].strip()), 10, f"{f['id']}: impact not stated")
            self.assertGreater(len(f["rationale"].strip()), 10, f"{f['id']}: reason to wait not stated")

if __name__ == "__main__":
    unittest.main()
