"""PortKit evidence: verified only with a successful transfer-test run on the commit it names, a
workflow that transferred the feature into every target, and no change since; and the per-class
check of the transferred tests."""
import copy
import importlib.util
import json
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("portkit", ROOT / "portkit/portkit.py")
portkit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(portkit)
spec = importlib.util.spec_from_file_location("check_test_classes", ROOT / "portkit/check-test-classes.py")
classes = importlib.util.module_from_spec(spec)
spec.loader.exec_module(classes)

LAZY = "composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/DuplicateSafeLazyKeys.kt"


def git(repo, *args):
    return subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True, check=True).stdout.strip()


class Evidence(unittest.TestCase):
    """A small repository with PortKit's files and the lazy-key feature, at two commits."""

    def setUp(self):
        self.repo = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.repo)
        registry = json.loads((ROOT / ".github/plus/features.json").read_text())
        paths = {".github/plus/features.json", ".github/workflows/portkit.yml", *portkit.TRANSFER_MACHINERY,
                 "portkit/compatibility.json"}
        for feature in registry["features"]:
            paths.update(portkit.feature_paths(feature))
        for path in paths:
            source = ROOT / path
            if source.is_dir():
                shutil.copytree(source, self.repo / path, dirs_exist_ok=True)
            elif source.is_file():
                (self.repo / path).parent.mkdir(parents=True, exist_ok=True)
                shutil.copy(source, self.repo / path)
        overlay = json.loads((self.repo / "portkit/compatibility.json").read_text())
        for entry in overlay["features"].values():  # start from nothing verified
            entry["verified"] = False
            entry.pop("evidence", None)
        (self.repo / "portkit/compatibility.json").write_text(json.dumps(overlay))
        git(self.repo, "init", "-q", "-b", "main")
        self.commit("tested")
        self.tested = git(self.repo, "rev-parse", "HEAD")
        overlay = json.loads((self.repo / "portkit/compatibility.json").read_text())
        overlay["features"]["lazy-key-collisions"].update(
            verified=True, evidence={"run": "https://github.com/Mohammed4421/NuvioMobile/actions/runs/42", "commit": self.tested})
        (self.repo / "portkit/compatibility.json").write_text(json.dumps(overlay))
        self.commit("verified")
        self.run_data = {"name": portkit.TRANSFER_WORKFLOW, "status": "completed", "conclusion": "success",
                         "head_sha": self.tested}

    def commit(self, message):
        git(self.repo, "add", "-A")
        git(self.repo, "-c", "user.name=t", "-c", "user.email=t@x", "commit", "-q", "-m", message)

    def problems(self, **run):
        data = dict(self.run_data, **run)
        return portkit.check_evidence(self.repo, fetch_run=lambda run_id: data)

    def test_good_evidence_holds(self):
        self.assertEqual([], self.problems())
        self.assertEqual([], portkit.validate(self.repo) and [p for p in portkit.validate(self.repo) if "evidence" in p])

    def test_a_failed_or_unfinished_run_is_not_evidence(self):
        self.assertTrue(any("did not succeed" in p for p in self.problems(conclusion="failure")))
        self.assertTrue(any("did not succeed" in p for p in self.problems(status="in_progress", conclusion=None)))

    def test_another_workflow_is_not_evidence(self):
        self.assertTrue(any("not 'PortKit transfer tests'" in p for p in self.problems(name="Nuvio Plus checks")))

    def test_a_run_of_another_commit_is_not_evidence(self):
        self.assertTrue(any("tested" in p and "not" in p for p in self.problems(head_sha="f" * 40)))

    def test_a_change_to_the_feature_after_the_run_makes_the_evidence_stale(self):
        (self.repo / LAZY).write_text((self.repo / LAZY).read_text() + "\n// later change\n")
        self.commit("later")
        self.assertTrue(any("changed since the tested commit" in p and LAZY in p for p in self.problems()))

    def test_a_change_to_portkit_after_the_run_makes_the_evidence_stale(self):
        (self.repo / "portkit/portkit.py").write_text((self.repo / "portkit/portkit.py").read_text() + "\n# later\n")
        self.commit("later")
        self.assertTrue(any("portkit/portkit.py" in p for p in self.problems()))

    def test_a_feature_the_workflow_did_not_transfer_is_not_verified(self):
        overlay = json.loads((self.repo / "portkit/compatibility.json").read_text())
        overlay["features"]["subtitle-style"].update(
            level="portable", verified=True, evidence={"run": "https://github.com/Mohammed4421/NuvioMobile/actions/runs/42", "commit": self.tested})
        (self.repo / "portkit/compatibility.json").write_text(json.dumps(overlay))
        self.commit("claims more")
        self.assertTrue(any("subtitle-style: the transfer-test workflow" in p for p in self.problems()))

    def test_an_unreachable_github_is_not_evidence(self):
        def broken(run_id):
            raise OSError("no network")
        self.assertTrue(any("could not read run" in p for p in portkit.check_evidence(self.repo, fetch_run=broken)))

    def test_evidence_of_another_repository_is_refused_by_validate(self):
        overlay = json.loads((self.repo / "portkit/compatibility.json").read_text())
        overlay["features"]["lazy-key-collisions"]["evidence"]["run"] = "https://github.com/someone/else/actions/runs/1"
        (self.repo / "portkit/compatibility.json").write_text(json.dumps(overlay))
        self.assertTrue(any("verified without 'evidence'" in p for p in portkit.validate(self.repo)))


class TestClasses(unittest.TestCase):
    def results(self, cases):
        directory = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, directory)
        body = "".join(f'<testcase classname="{c}" name="t{i}">{"<failure/>" if bad else ""}</testcase>'
                       for i, (c, bad) in enumerate(cases))
        (directory / "TEST-x.xml").write_text(f"<testsuite>{body}</testsuite>")
        return directory

    def test_every_named_class_must_run_and_pass(self):
        directory = self.results([("a.A", False), ("a.A", False), ("b.B", False)])
        self.assertEqual((True, ["a.A: 2 passed", "b.B: 1 passed"]), classes.check(directory, ["a.A", "b.B"]))

    def test_a_class_that_did_not_run_fails_even_if_others_pass(self):
        ok, lines = classes.check(self.results([("a.A", False)] * 5), ["a.A", "b.B"])
        self.assertFalse(ok)
        self.assertIn("b.B: did not run", lines)

    def test_a_failing_test_fails_its_class(self):
        ok, lines = classes.check(self.results([("a.A", False), ("a.A", True)]), ["a.A"])
        self.assertFalse(ok)
        self.assertIn("a.A: 1 of 2 failed", lines)


if __name__ == "__main__":
    unittest.main()


class FetchRun(unittest.TestCase):
    """How check-evidence reads a run from GitHub: token first, the public run without it when the
    token may not read Actions data, retries only for rate limits and server errors."""

    def fetch(self, answers, token="t0ken"):
        import io
        import os
        import urllib.error
        calls = []

        def urlopen(request, timeout):
            calls.append(request.get_header("Authorization"))
            answer = answers[len(calls) - 1]
            if isinstance(answer, int):
                headers = {"X-RateLimit-Remaining": "0"} if answer == -403 else {}
                raise urllib.error.HTTPError(request.full_url, abs(answer), "error", headers, None)
            return io.BytesIO(json.dumps(answer).encode())

        saved = {k: os.environ.pop(k, None) for k in ("GH_TOKEN", "GITHUB_TOKEN")}
        if token:
            os.environ["GH_TOKEN"] = token
        try:
            try:
                return portkit.github_run(7, urlopen=urlopen, sleep=lambda s: None), calls
            except urllib.error.HTTPError as error:
                return error.code, calls
        finally:
            os.environ.pop("GH_TOKEN", None)
            for k, v in saved.items():
                if v is not None:
                    os.environ[k] = v

    def test_reads_with_the_token_first(self):
        self.assertEqual(({"id": 7}, ["Bearer t0ken"]), self.fetch([{"id": 7}]))

    def test_a_token_without_access_falls_back_to_the_public_run(self):
        for code in (401, 403, 404):
            self.assertEqual(({"id": 7}, ["Bearer t0ken", None]), self.fetch([code, {"id": 7}]), code)

    def test_without_a_token_reads_anonymously(self):
        self.assertEqual(({"id": 7}, [None]), self.fetch([{"id": 7}], token=None))

    def test_rate_limits_are_retried_not_mistaken_for_missing_access(self):
        result, calls = self.fetch([-403, 429, {"id": 7}])
        self.assertEqual(({"id": 7}, ["Bearer t0ken"] * 3), (result, calls))

    def test_a_missing_run_is_not_reported_as_found(self):
        self.assertEqual((404, ["Bearer t0ken", None]), self.fetch([404, 404]))

    def test_rate_limits_that_never_clear_fail(self):
        result, calls = self.fetch([-403] * 4 + [-403] * 4, token=None)
        self.assertEqual((403, 4), (result, len(calls)))

