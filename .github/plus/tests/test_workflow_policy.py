"""Security rules of the Nuvio Plus workflows, checked on the files themselves.

These keep later edits (or a Nuvio merge) from quietly undoing the isolation of secrets and write
access. Run: python3 -m unittest discover -s .github/plus/tests
"""
import json
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[3]
WORKFLOWS = ROOT / ".github" / "workflows"
PLUS = ["plus-build.yml", "plus-ci.yml", "plus-sync.yml", "plus-pipeline.yml", "custom-android-build.yml"]
PINNED = re.compile(r"^[\w.-]+/[\w./-]+@[0-9a-f]{40}$")
OWN_REUSABLE = re.compile(r"^(\./\.github/workflows/[\w-]+\.yml|Mohammed4421/NuvioMobile/\.github/workflows/[\w-]+\.yml@custom-subtitles)$")
# Jobs allowed to write, and why.
WRITERS = {
    ("plus-sync.yml", "prepare"): "pushes the review branch; runs no Nuvio code",
    ("plus-sync.yml", "pull-request"): "opens the review pull request",
    ("custom-android-build.yml", "publish"): "publishes the signed, tested APKs",
    ("custom-android-build.yml", "report-failure"): "publishes the failure report",
}
LEVEL = {"none": 0, "read": 1, "write": 2}


def load(name):
    text = (WORKFLOWS / name).read_text()
    data = yaml.safe_load(text)
    data["on"] = data.pop(True, data.get("on"))  # YAML reads the bare key `on` as True
    return data


def steps(job):
    return job.get("steps", [])


def permissions(workflow, job):
    """Effective permissions of a job: its own block, else the workflow's."""
    value = job.get("permissions", workflow.get("permissions"))
    if value is None:
        return {"*": "write"}  # GitHub's default token may write
    if isinstance(value, str):
        return {"*": {"read-all": "read", "write-all": "write"}.get(value, value)}
    return value


def writes(perms):
    return any(level == "write" for level in perms.values())


class WorkflowPolicyTest(unittest.TestCase):
    def setUp(self):
        self.workflows = {name: load(name) for name in PLUS}

    def each_job(self):
        for name, workflow in self.workflows.items():
            for job_id, job in workflow["jobs"].items():
                yield name, workflow, job_id, job

    def test_external_actions_are_pinned_by_commit(self):
        for name, _, job_id, job in self.each_job():
            if "uses" in job:
                self.assertRegex(job["uses"], OWN_REUSABLE, f"{name}/{job_id}")
            for step in steps(job):
                uses = step.get("uses")
                if uses:
                    self.assertRegex(uses, PINNED, f"{name}/{job_id}: {uses} is not pinned by commit SHA")

    def test_secrets_are_never_inherited(self):
        for name, _, job_id, job in self.each_job():
            self.assertNotEqual("inherit", job.get("secrets"), f"{name}/{job_id} inherits every secret")

    def test_only_the_publishing_workflow_passes_secrets(self):
        for name, _, job_id, job in self.each_job():
            if job.get("secrets"):
                self.assertEqual(("custom-android-build.yml", "checks"), (name, job_id))

    def test_the_workflow_default_token_is_read_only(self):
        for name, workflow in self.workflows.items():
            if name == "plus-pipeline.yml":
                continue  # only calls plus-sync, whose jobs set their own permissions
            perms = workflow.get("permissions")
            self.assertIsInstance(perms, dict, name)
            self.assertFalse(writes(perms), f"{name}: the default token may write")

    def test_only_listed_jobs_may_write(self):
        for name, workflow, job_id, job in self.each_job():
            perms = permissions(workflow, job)
            if "uses" in job or name == "plus-pipeline.yml":
                continue  # reusable calls: their jobs are checked in their own file
            if (name, job_id) in WRITERS:
                continue
            for scope, level in perms.items():
                if level == "write":
                    self.assertIn((name, job_id, scope), {("plus-build.yml", "report", "statuses")},
                                  f"{name}/{job_id} may write {scope}")

    def test_jobs_that_run_project_code_cannot_write_or_sign(self):
        for name, workflow, job_id, job in self.each_job():
            scripts = "\n".join(step.get("run", "") for step in steps(job))
            if "gradlew" not in scripts:
                continue
            self.assertFalse(writes(permissions(workflow, job)), f"{name}/{job_id} runs Gradle with a writing token")
            env = "\n".join(str(step.get("env", "")) for step in steps(job)) + str(job.get("env", ""))
            self.assertNotIn("KEYSTORE", env, f"{name}/{job_id} runs Gradle next to the signing key")

    def test_no_signing_secret_reaches_files_of_the_commit(self):
        # Every job of plus-build.yml runs files of the commit (sign-apks.sh, apk-certs.py, Gradle):
        # no signing secret may reach that workflow or the scripts it runs.
        for path in (".github/workflows/plus-build.yml", ".github/plus/sign-apks.sh", ".github/plus/prepare-build.sh"):
            text = (ROOT / path).read_text()
            for secret in ("CUSTOM_KEYSTORE", "CUSTOM_KEY_ALIAS", "KEYSTORE_PASSWORD"):
                self.assertNotIn(secret, text, f"{path} mentions {secret}")
        passed = self.workflows["custom-android-build.yml"]["jobs"]["checks"]["secrets"]
        self.assertEqual({"CUSTOM_LOCAL_PROPERTIES"}, set(passed))

    def test_the_signing_key_secret_is_only_tested_for_presence(self):
        for name, _, job_id, job in self.each_job():
            text = yaml.safe_dump(job)
            if "CUSTOM_KEYSTORE" in text:
                self.assertEqual(("custom-android-build.yml", "release-identity"), (name, job_id))
                self.assertIn("${{ secrets.CUSTOM_KEYSTORE_BASE64 != '' }}", text)

    def test_the_release_identity_check_runs_no_code_of_the_commit(self):
        job = self.workflows["custom-android-build.yml"]["jobs"]["release-identity"]
        self.assertEqual({}, job["permissions"])
        for step in steps(job):
            self.assertNotIn("uses", step, "the identity check must not check out or run actions")
            self.assertNotRegex(step.get("run", ""), r"\.github/|bash |python", "it must not run files")
        self.assertEqual("40335e3135578dd858dc434717b9344015b62872dcb861d52474c38a1f99f48a", job["env"]["IDENTITY"])
        self.assertEqual("${{ vars.PLUS_PUBLIC_KEY_RELEASES }}", job["env"]["PUBLIC_KEY_RELEASES"])

    def run_identity_check(self, certificate, private_key, decision):
        job = self.workflows["custom-android-build.yml"]["jobs"]["release-identity"]
        env = {"CERTIFICATE": certificate, "PRIVATE_KEY_CONFIGURED": private_key, "PUBLIC_KEY_RELEASES": decision,
               "IDENTITY": job["env"]["IDENTITY"], "PATH": "/usr/bin:/bin"}
        return subprocess.run(["bash", "-e", "-c", steps(job)[0]["run"]], env=env, capture_output=True, text=True)

    def test_publishing_with_the_public_key_needs_the_owners_decision(self):
        identity = "40335e3135578dd858dc434717b9344015b62872dcb861d52474c38a1f99f48a"
        self.assertEqual(0, self.run_identity_check(identity, "false", "allowed").returncode)
        self.assertIn("owner's decision", self.run_identity_check(identity, "false", "").stdout)
        self.assertIn("phase 5", self.run_identity_check(identity, "true", "allowed").stdout)
        self.assertIn("not the certificate", self.run_identity_check("ab" * 32, "false", "allowed").stdout)
        self.assertNotEqual(0, self.run_identity_check("", "false", "allowed").returncode)

    def run_properties_check(self, extra):
        build = self.workflows["plus-build.yml"]["jobs"]["build"]
        step = steps(build)[0]
        with tempfile.TemporaryDirectory() as temp:
            result = subprocess.run(["bash", "-e", "-o", "pipefail", "-c", step["run"]], capture_output=True, text=True,
                                    env={"EXTRA": extra, "RUNNER_TEMP": temp, "PATH": "/usr/bin:/bin"})
            written = Path(temp, "extra.properties").read_text() if Path(temp, "extra.properties").exists() else ""
        return result, written

    def test_the_extra_properties_are_checked_before_any_code_of_the_commit(self):
        build = self.workflows["plus-build.yml"]["jobs"]["build"]
        first = steps(build)[0]
        self.assertNotIn("uses", first)
        self.assertEqual({"EXTRA": "${{ secrets.CUSTOM_LOCAL_PROPERTIES }}"}, first["env"])
        for later in steps(build)[1:]:
            self.assertNotIn("CUSTOM_LOCAL_PROPERTIES", yaml.safe_dump(later), "only the first step sees the secret")

    def test_only_public_app_settings_pass(self):
        result, written = self.run_properties_check("TRAKT_CLIENT_ID=abc\n# note\n\nSENTRY_DSN=https://k@o.ingest/1\r\n")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual("TRAKT_CLIENT_ID=abc\nSENTRY_DSN=https://k@o.ingest/1\n", written)
        self.assertEqual(0, self.run_properties_check("")[0].returncode)
        for bad in ("NUVIO_RELEASE_STORE_PASSWORD=hunter2", "SUPABASE_SERVICE_ROLE_KEY=secret",
                    "NUVIO_SUPABASE_URL=https://evil", "lowercase=x", "NO_EQUALS_SIGN", "=value"):
            result, _ = self.run_properties_check("TRAKT_CLIENT_ID=abc\n" + bad)
            self.assertEqual(1, result.returncode, bad)
            self.assertNotIn("hunter2", result.stdout + result.stderr, "values must never be printed")
            self.assertNotIn("secret\n", result.stdout)

    def test_checkouts_keep_no_credentials_unless_the_job_pushes(self):
        # Every checkout is followed at once by an inline step that removes the stored token and
        # fails if any is left (persist-credentials: false breaks on Nuvio's libass-android gitlink).
        for name, _, job_id, job in self.each_job():
            if (name, job_id) == ("plus-sync.yml", "prepare"):
                continue
            job_steps = steps(job)
            for index, step in enumerate(job_steps):
                if not str(step.get("uses", "")).startswith("actions/checkout@"):
                    continue
                following = job_steps[index + 1] if index + 1 < len(job_steps) else {}
                script = following.get("run", "")
                self.assertIn("extraheader", script, f"{name}/{job_id}: checkout keeps its token")
                self.assertIn("includeif", script, f"{name}/{job_id}: checkout keeps its token")
                self.assertIn("exit 1", script, f"{name}/{job_id}: token removal is not verified")

    def test_no_expressions_inside_scripts(self):
        # ${{ }} in a run: script is pasted into the shell before it runs (script injection).
        for name, _, job_id, job in self.each_job():
            for step in steps(job):
                self.assertNotIn("${{", step.get("run", ""), f"{name}/{job_id}: pass values through env")
                self.assertNotIn("${{", str((step.get("with") or {}).get("script", "")), f"{name}/{job_id}")

    def test_runners_are_pinned(self):
        for name, _, job_id, job in self.each_job():
            if "runs-on" in job:
                self.assertNotIn("latest", job["runs-on"], f"{name}/{job_id}")

    def test_pull_request_target_is_not_used(self):
        for name, workflow in self.workflows.items():
            self.assertNotIn("pull_request_target", workflow["on"] or {}, name)

    def test_callers_grant_what_the_called_jobs_ask(self):
        build = self.workflows["plus-build.yml"]
        needed = {}
        for job in build["jobs"].values():
            for scope, level in permissions(build, job).items():
                needed[scope] = max(needed.get(scope, 0), LEVEL[level])
        for name, workflow, job_id, job in self.each_job():
            if not str(job.get("uses", "")).endswith("plus-build.yml") and "plus-build.yml@" not in str(job.get("uses", "")):
                continue
            granted = permissions(workflow, job)
            for scope, level in needed.items():
                self.assertGreaterEqual(LEVEL[granted.get(scope, "none")], level, f"{name}/{job_id} lacks {scope}")
        pipeline = self.workflows["plus-pipeline.yml"]["permissions"]
        sync = self.workflows["plus-sync.yml"]
        for job in sync["jobs"].values():
            for scope, level in permissions(sync, job).items():
                self.assertGreaterEqual(LEVEL[pipeline.get(scope, "none")], LEVEL[level], f"plus-pipeline lacks {scope}")

    def test_publishing_needs_every_check_and_the_review_gate(self):
        publish = self.workflows["custom-android-build.yml"]["jobs"]["publish"]
        self.assertEqual({"checks", "review-gate", "release-identity"}, set(publish["needs"]))
        gate = self.workflows["custom-android-build.yml"]["jobs"]["review-gate"]
        self.assertIn("review-gate.py publish", "\n".join(s.get("run", "") for s in steps(gate)))
        build = self.workflows["plus-build.yml"]["jobs"]
        self.assertEqual({"sign", "update-check", "test-gate"}, set(build["emulator"]["needs"]))
        report = set(build["report"]["needs"])
        self.assertEqual(set(build) - {"report"}, report, "every check must count in the reported status")

    def test_checks_run_once_per_change(self):
        # A push to a branch with an open pull request must not start a second full run.
        triggers = self.workflows["plus-ci.yml"]["on"]
        self.assertEqual({"pull_request", "workflow_dispatch"}, set(triggers))

    def test_every_gradle_run_retries_only_failed_downloads(self):
        build = self.workflows["plus-build.yml"]["jobs"]
        for job_id, job in build.items():
            for step in steps(job):
                for line in step.get("run", "").splitlines():
                    if "./gradlew " in line:
                        self.assertIn("gradle-retry.sh", line, f"{job_id}: {line.strip()}")

    def test_ready_pull_requests_get_the_full_validation(self):
        ci = self.workflows["plus-ci.yml"]
        self.assertEqual({"opened", "synchronize", "reopened", "ready_for_review"}, set(ci["on"]["pull_request"]["types"]))
        self.assertEqual("${{ github.event_name != 'pull_request' || !github.event.pull_request.draft }}",
                         ci["jobs"]["checks"]["with"]["full"])
        # Publishing and the sync always run everything.
        self.assertNotIn("full", self.workflows["custom-android-build.yml"]["jobs"]["checks"]["with"])
        self.assertNotIn("full", self.workflows["plus-sync.yml"]["jobs"]["checks"]["with"])
        self.assertIs(True, self.workflows["plus-build.yml"]["on"]["workflow_call"]["inputs"]["full"]["default"])

    def run_report(self, results, full):
        report = self.workflows["plus-build.yml"]["jobs"]["report"]
        script = steps(report)[0]["run"]
        with tempfile.TemporaryDirectory() as temp:
            calls = Path(temp, "calls")
            fake = Path(temp, "gh")
            fake.write_text(f'#!/usr/bin/env bash\nprintf "%s\\n" "$*" >> "{calls}"\n')
            fake.chmod(0o755)
            needs = {job: {"result": result} for job, result in results.items()}
            env = {"PATH": f"{temp}:/usr/bin:/bin", "NEEDS": json.dumps(needs), "FULL": full, "SHA": "a" * 40,
                   "PREFIX": "nuvio-plus", "GITHUB_SERVER_URL": "https://github.com", "GITHUB_REPOSITORY": "o/r",
                   "GITHUB_RUN_ID": "1"}
            subprocess.run(["bash", "-e", "-o", "pipefail", "-c", script], env=env, check=True, capture_output=True)
            text = calls.read_text()
        return {context: state for state, context in re.findall(r"state=(\w+) -f context=nuvio-plus/(\w+)", text)}

    def all_jobs(self, result="success", **overrides):
        jobs = [j for j in self.workflows["plus-build.yml"]["jobs"] if j != "report"]
        return {**{job: result for job in jobs}, **overrides}

    def test_the_reported_statuses_follow_the_results(self):
        self.assertEqual({"checks": "success", "review": "success"}, self.run_report(self.all_jobs(), "true"))
        skipped_baseline = self.all_jobs(**{"nuvio-tests": "skipped"})
        self.assertEqual("success", self.run_report(skipped_baseline, "true")["checks"])
        for job in ("build", "sign", "update-check", "plus-tests", "test-gate", "emulator", "policy-tests"):
            self.assertEqual("failure", self.run_report(self.all_jobs(**{job: "failure"}), "true")["checks"], job)
            self.assertEqual("failure", self.run_report(self.all_jobs(**{job: "skipped"}), "true")["checks"], job)
        self.assertEqual("failure", self.run_report(self.all_jobs(**{"review-records": "failure"}), "true")["review"])

    def test_fast_validation_never_reports_success(self):
        fast = self.all_jobs("skipped", **{"policy-tests": "success", "review-records": "success"})
        self.assertEqual({"checks": "pending", "review": "success"}, self.run_report(fast, "false"))
        fast_failed = self.all_jobs("skipped", **{"policy-tests": "failure", "review-records": "success"})
        self.assertEqual("failure", self.run_report(fast_failed, "false")["checks"])

    def test_only_publishing_runs_write_the_gradle_cache(self):
        expected = ("${{ !(github.event_name == 'push' && (github.ref == 'refs/heads/custom-subtitles'"
                    " || github.ref == 'refs/heads/plus-beta')) }}")
        for name, _, job_id, job in self.each_job():
            for step in steps(job):
                if str(step.get("uses", "")).startswith("gradle/actions/setup-gradle@"):
                    self.assertEqual(expected, (step.get("with") or {}).get("cache-read-only"), f"{name}/{job_id}")

    def test_the_build_writes_no_signing_settings(self):
        script = (ROOT / ".github/plus/prepare-build.sh").read_text()
        self.assertNotIn("echo \"NUVIO_RELEASE_", script)


if __name__ == "__main__":
    unittest.main()
