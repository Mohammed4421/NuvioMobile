"""Tests of osv-check.py: which libraries are read, and when the build stops."""
import datetime
import importlib.util
import unittest
from pathlib import Path

PLUS = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("osv_check", PLUS / "osv-check.py")
osv = importlib.util.module_from_spec(spec)
spec.loader.exec_module(osv)

TODAY = datetime.date(2026, 11, 20)
REPORT = "\n".join([
    "fullReleaseRuntimeClasspath - Runtime classpath of '/fullRelease'.",
    "+--- project :composeApp",
    "|    +--- io.ktor:ktor-client-core:3.0.0 -> 3.1.2",
    "|    |    \\--- org.jetbrains.kotlin:kotlin-stdlib:2.0.0 -> 2.4.10 (*)",
    "|    +--- com.squareup.okhttp3:okhttp:4.9.0",
    "|    +--- androidx.core:core:{strictly 1.13.0} -> 1.13.0 (c)",
    "|    \\--- com.example:missing:1.0 FAILED",
    "\\--- org.jetbrains.kotlinx:kotlinx-coroutines-bom -> 1.10.2",
] + [f"+--- com.example:lib{i}:1.{i}" for i in range(20)])


def fake(vulns_by_package, details):
    def fetch(url, body=None):
        if url.endswith("/querybatch"):
            return {"results": [{"vulns": [{"id": v} for v in vulns_by_package.get(q["package"]["name"], [])]}
                                for q in body["queries"]]}
        return details[url.rsplit("/", 1)[1]]
    return fetch


def vuln(vid, level, package, fixed="9.9.9"):
    return {"id": vid, "aliases": ["CVE-1"], "summary": "Something bad",
            "database_specific": {"severity": level} if level else {},
            "affected": [{"package": {"name": package}, "ranges": [{"events": [{"introduced": "0"}, {"fixed": fixed}]}]}]}


class OsvCheckTest(unittest.TestCase):
    def test_reads_resolved_versions_and_skips_constraints_and_projects(self):
        libs = osv.libraries(REPORT)
        self.assertEqual("3.1.2", libs["io.ktor:ktor-client-core"])
        self.assertEqual("2.4.10", libs["org.jetbrains.kotlin:kotlin-stdlib"])
        self.assertEqual("4.9.0", libs["com.squareup.okhttp3:okhttp"])
        self.assertEqual("1.10.2", libs["org.jetbrains.kotlinx:kotlinx-coroutines-bom"])
        self.assertNotIn("androidx.core:core", libs)
        self.assertNotIn("com.example:missing", libs)

    def test_a_high_vulnerability_blocks(self):
        fetch = fake({"com.squareup.okhttp3:okhttp": ["GHSA-1"]}, {"GHSA-1": vuln("GHSA-1", "HIGH", "com.squareup.okhttp3:okhttp")})
        blocking, summary = osv.run(REPORT, [], TODAY, fetch)
        self.assertEqual(1, len(blocking))
        self.assertIn("1 blocking", summary)
        self.assertIn("fixed in 9.9.9", summary)

    def test_unknown_severity_blocks_and_moderate_does_not(self):
        fetch = fake({"com.squareup.okhttp3:okhttp": ["A"], "io.ktor:ktor-client-core": ["B"]},
                     {"A": vuln("A", None, "com.squareup.okhttp3:okhttp"), "B": vuln("B", "MODERATE", "io.ktor:ktor-client-core")})
        blocking, summary = osv.run(REPORT, [], TODAY, fetch)
        self.assertEqual(["A"], [row[1] for row in blocking])
        self.assertIn("1 low or moderate", summary)

    def test_a_valid_review_unblocks_and_is_shown(self):
        fetch = fake({"com.squareup.okhttp3:okhttp": ["GHSA-1"]}, {"GHSA-1": vuln("GHSA-1", "CRITICAL", "com.squareup.okhttp3:okhttp")})
        review = {"id": "GHSA-1", "package": "com.squareup.okhttp3:okhttp", "reason": "not reachable", "until": "2027-01-01"}
        blocking, summary = osv.run(REPORT, [review], TODAY, fetch)
        self.assertEqual([], blocking)
        self.assertIn("Reviewed until 2027-01-01: not reachable", summary)

    def test_a_review_is_matched_by_alias_but_only_for_its_package(self):
        fetch = fake({"com.squareup.okhttp3:okhttp": ["GHSA-1"]}, {"GHSA-1": vuln("GHSA-1", "HIGH", "com.squareup.okhttp3:okhttp")})
        alias = {"id": "CVE-1", "package": "com.squareup.okhttp3:okhttp", "reason": "r", "until": "2027-01-01"}
        self.assertEqual([], osv.run(REPORT, [alias], TODAY, fetch)[0])
        other = {"id": "GHSA-1", "package": "io.ktor:ktor-client-core", "reason": "r", "until": "2027-01-01"}
        self.assertEqual(1, len(osv.run(REPORT, [other], TODAY, fetch)[0]))

    def test_expired_endless_or_empty_reviews_fail(self):
        fetch = fake({}, {})
        for review in ({"id": "X", "package": "p", "reason": "r", "until": "2026-11-19"},
                       {"id": "X", "package": "p", "reason": "r", "until": "2027-12-31"},
                       {"id": "X", "package": "p", "reason": "", "until": "2027-01-01"}):
            with self.assertRaises(ValueError):
                osv.run(REPORT, [review], TODAY, fetch)

    def test_a_stale_review_is_reported(self):
        review = {"id": "OLD", "package": "p", "reason": "r", "until": "2027-01-01"}
        _, summary = osv.run(REPORT, [review], TODAY, fake({}, {}))
        self.assertIn("matches nothing any more", summary)

    def test_a_report_without_libraries_fails(self):
        with self.assertRaises(ValueError):
            osv.run("BUILD SUCCESSFUL", [], TODAY, fake({}, {}))

    def test_the_reviewed_file_in_the_repository_is_valid(self):
        import json
        osv.check_reviews(json.loads((PLUS / "osv-reviewed.json").read_text()), datetime.date.today())


if __name__ == "__main__":
    unittest.main()
