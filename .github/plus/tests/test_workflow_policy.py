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
PLUS = ["plus-build.yml", "plus-ci.yml", "plus-sync.yml", "plus-pipeline.yml", "custom-android-build.yml",
        "plus-release-key.yml"]
PINNED = re.compile(r"^[\w.-]+/[\w./-]+@[0-9a-f]{40}$")
OWN_REUSABLE = re.compile(r"^(\./\.github/workflows/[\w-]+\.yml|Mohammed4421/NuvioMobile/\.github/workflows/[\w-]+\.yml@custom-subtitles)$")
# Jobs allowed to write, and why.
WRITERS = {
    ("plus-sync.yml", "prepare"): "pushes the review branch; runs no Nuvio code",
    ("plus-sync.yml", "pull-request"): "opens the review pull request",
    ("custom-android-build.yml", "publish"): "publishes the signed, tested APKs",
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
        self.assertEqual("40335e3135578dd858dc434717b9344015b62872dcb861d52474c38a1f99f48a", job["env"]["COMPROMISED"])

    PUBLIC = "40335e3135578dd858dc434717b9344015b62872dcb861d52474c38a1f99f48a"

    HEAD = "0123456789abcdef0123456789abcdef01234567"

    def run_identity_check(self, certificate, private_key, release_identity=None,
                           event="workflow_dispatch", release_commit=HEAD):
        job = self.workflows["custom-android-build.yml"]["jobs"]["release-identity"]
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp, "output")
            env = {"CERTIFICATE": certificate, "PRIVATE_KEY_CONFIGURED": private_key,
                   "COMPROMISED": job["env"]["COMPROMISED"],
                   "RELEASE_IDENTITY": job["env"]["RELEASE_IDENTITY"] if release_identity is None else release_identity,
                   "EVENT": event, "RELEASE_COMMIT": release_commit, "GITHUB_SHA": self.HEAD,
                   "GITHUB_REF_NAME": "custom-subtitles",
                   "GITHUB_OUTPUT": str(output), "PATH": "/usr/bin:/bin"}
            result = subprocess.run(["bash", "-e", "-c", steps(job)[0]["run"]], env=env, capture_output=True, text=True)
            return result, output.read_text() if output.exists() else ""

    def test_the_public_key_never_publishes(self):
        # Without a pinned release key: checked, not published, and not a failure.
        result, output = self.run_identity_check(self.PUBLIC, "false", "")
        self.assertEqual(0, result.returncode, "a tested build without a release key is not a failure")
        self.assertEqual("publish=false\n", output)
        self.assertIn("never publishes", result.stdout)
        # Not even if the public key's certificate were entered as the release identity.
        self.assertEqual("publish=false\n", self.run_identity_check(self.PUBLIC, "false", self.PUBLIC)[1])

    def test_the_release_key_is_pinned(self):
        self.assertEqual("99c0a98cc1700bd1186018e480eab670daaf55d4d8c5316d7cf03d788d431e35",
                         self.workflows["custom-android-build.yml"]["jobs"]["release-identity"]["env"]["RELEASE_IDENTITY"])
        result, output = self.run_identity_check(self.PUBLIC, "false")
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertIn("publish=true", output)

    def test_a_pinned_release_key_moves_on_to_release_signing(self):
        result, output = self.run_identity_check(self.PUBLIC, "false", "cd" * 32)
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertIn("publish=true", output)
        self.assertIn("identity=" + "cd" * 32, output)
        self.assertIn("compromised=" + self.PUBLIC, output)

    def test_the_checks_must_have_used_the_test_key(self):
        for certificate in ("ab" * 32, ""):
            result, output = self.run_identity_check(certificate, "false", "cd" * 32)
            self.assertNotEqual(0, result.returncode)
            self.assertNotIn("publish=true", output)
        result, _ = self.run_identity_check(self.PUBLIC, "true", "cd" * 32)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("legacy private key", result.stdout)

    def test_a_merge_never_publishes(self):
        # A push (a merged pull request) is checked, not published: a series of merges cannot release
        # a partial build. Not a failure either.
        result, output = self.run_identity_check(self.PUBLIC, "false", event="push", release_commit="")
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertEqual("publish=false\n", output)
        self.assertIn(f"release_commit={self.HEAD}", result.stdout)
        job = self.workflows["custom-android-build.yml"]["jobs"]["release-identity"]
        self.assertEqual("${{ github.event_name }}", job["env"]["EVENT"])
        self.assertEqual("${{ inputs.release_commit }}", job["env"]["RELEASE_COMMIT"])

    def test_a_release_names_the_exact_commit_it_publishes(self):
        for wrong in ("", "abc", "f" * 40, self.HEAD.upper(), self.HEAD[:39] + "8"):
            result, output = self.run_identity_check(self.PUBLIC, "false", event="workflow_dispatch", release_commit=wrong)
            self.assertNotEqual(0, result.returncode, wrong)
            self.assertNotIn("publish=true", output, wrong)
        dispatch = self.workflows["custom-android-build.yml"]["on"]["workflow_dispatch"]["inputs"]["release_commit"]
        self.assertIs(True, dispatch["required"])

    def test_merges_and_releases_queue_separately_and_never_cancel(self):
        workflow = self.workflows["custom-android-build.yml"]
        self.assertIs(False, workflow["concurrency"]["cancel-in-progress"])
        group = workflow["concurrency"]["group"]
        self.assertIn("'nuvio-plus-publish'", group)
        self.assertIn("workflow_dispatch", group)

    def run_head_check(self, job_id, head, gh_fails=False):
        """Runs the branch-head step of job_id with a fake gh that reports `head` as the branch head."""
        job = self.workflows["custom-android-build.yml"]["jobs"][job_id]
        step = next(s for s in steps(job) if s.get("name") == "The branch head is still this commit")
        with tempfile.TemporaryDirectory() as temp:
            fake = Path(temp, "gh")
            fake.write_text("#!/bin/sh\n" + ("exit 1\n" if gh_fails else f'[ "$2" = "repos/o/r/git/ref/heads/custom-subtitles" ] && echo {head}\n'))
            fake.chmod(0o755)
            env = {"PATH": f"{temp}:/usr/bin:/bin", "GITHUB_REPOSITORY": "o/r", "GITHUB_REF_NAME": "custom-subtitles",
                   "GITHUB_SHA": self.HEAD, "GH_TOKEN": "x"}
            return subprocess.run(["bash", "-e", "-c", step["run"]], env=env, capture_output=True, text=True)

    def test_a_branch_that_moved_is_neither_signed_nor_published(self):
        # The branch moves after the run started (checks running) or while an approval is pending:
        # release signing (after approval 1) and publishing (after approval 2) read the head again.
        for job_id in ("release-sign", "publish"):
            self.assertEqual(0, self.run_head_check(job_id, self.HEAD).returncode, job_id)
            moved = self.run_head_check(job_id, "f" * 40)
            self.assertNotEqual(0, moved.returncode, job_id)
            self.assertIn("moved after this run started", moved.stdout)
            self.assertNotEqual(0, self.run_head_check(job_id, "").returncode, job_id)
            self.assertNotEqual(0, self.run_head_check(job_id, self.HEAD, gh_fails=True).returncode, job_id)

    def test_the_head_is_read_again_right_before_signing_and_publishing(self):
        jobs = self.workflows["custom-android-build.yml"]["jobs"]
        sign = [s.get("name") for s in steps(jobs["release-sign"])]
        self.assertEqual("The branch head is still this commit", sign[0], "before the key is used")
        publish = [s.get("name") for s in steps(jobs["publish"])]
        self.assertEqual("The branch head is still this commit", publish[publish.index("Publish release") - 1])

    def test_failures_are_reported_in_the_run_never_as_a_release(self):
        report = self.workflows["custom-android-build.yml"]["jobs"]["report-failure"]
        self.assertEqual({}, report["permissions"])
        self.assertIn("nuvio-plus-failure-report", yaml.safe_dump(report))
        # The only way the publishing workflow makes a GitHub release is publish-release.sh in the publish job.
        for job_id, job in self.workflows["custom-android-build.yml"]["jobs"].items():
            name = "custom-android-build.yml"
            for step in steps(job):
                self.assertNotRegex(step.get("run", ""), r"gh release (create|edit|upload)|/releases", f"{name}/{job_id}")
        publish = "\n".join(s.get("run", "") for s in steps(self.workflows["custom-android-build.yml"]["jobs"]["publish"]))
        self.assertIn("publish-release.sh", publish)

    def test_every_release_job_waits_for_the_identity_decision(self):
        jobs = self.workflows["custom-android-build.yml"]["jobs"]
        for job_id in ("release-sign", "release-verify", "release-upgrade", "publish"):
            self.assertEqual("needs.release-identity.outputs.publish == 'true'", jobs[job_id]["if"], job_id)

    def test_only_release_signing_reads_the_release_key(self):
        for name, _, job_id, job in self.each_job():
            text = yaml.safe_dump(job)
            if not re.search(r"secrets\.RELEASE_KEY(STORE|_PASSWORD)\b", text):
                continue
            self.assertIn((name, job_id), {("custom-android-build.yml", "release-sign"), ("plus-release-key.yml", "make-key")})
            self.assertEqual("plus-release", job["environment"], f"{name}/{job_id} must use the protected environment")
            self.assertFalse(writes(permissions(self.workflows[name], job)), f"{name}/{job_id} must not write")
            with_secrets = [s for s in steps(job) if "secrets." in yaml.safe_dump(s)]
            self.assertEqual(1, len(with_secrets), f"{name}/{job_id}: exactly one step sees the key")
            for step in steps(job):
                run = step.get("run", "")
                self.assertNotRegex(run, r"\.github/|bash |python|\./gradlew", f"{name}/{job_id} must run no file of the commit")
                self.assertNotRegex(run, r"set -x|echo [^\n]*\$\{?RELEASE_KEY|printenv|cat [^|\n]*\.p12", f"{name}/{job_id} must not print the key")
                if step.get("uses", "").startswith("actions/checkout@"):
                    self.assertIn("sparse-checkout", step.get("with", {}), "only the files it needs")

    def test_publishing_waits_for_the_owner_after_every_test(self):
        publish = self.workflows["custom-android-build.yml"]["jobs"]["publish"]
        self.assertEqual("plus-release", publish["environment"])
        self.assertNotIn("secrets.", yaml.safe_dump(publish), "publishing reads no secret of the environment")

    def test_release_signing_needs_the_review_gate_and_signs_v3_only(self):
        job = self.workflows["custom-android-build.yml"]["jobs"]["release-sign"]
        self.assertEqual({"checks", "review-gate", "release-identity"}, set(job["needs"]))
        sign = next(s for s in steps(job) if "secrets." in yaml.safe_dump(s))["run"]
        for flag in ("--v1-signing-enabled false", "--v2-signing-enabled false", "--v3-signing-enabled true",
                     "--lineage \"${lineage}\"", "--set-rollback false", "--min-sdk-version 28", "--rotation-min-sdk-version 28"):
            self.assertIn(flag, sign)
        self.assertIn("built_manifest", yaml.safe_dump(job), "it signs only the APKs the checks built")

    def test_the_key_ceremony_runs_once_with_the_owners_approval(self):
        workflow = self.workflows["plus-release-key.yml"]
        self.assertEqual({"push": {"branches": ["plus-release/key-ceremony"]}}, workflow["on"])
        job = workflow["jobs"]["make-key"]
        run = next(s for s in steps(job) if "secrets." in yaml.safe_dump(s))["run"]
        self.assertIn("already has a RELEASE_KEYSTORE", run)
        self.assertIn("shorter than 24 characters", run)
        self.assertIn("--set-rollback false", run)
        upload = next(s for s in steps(job) if s.get("uses", "").startswith("actions/upload-artifact@"))
        self.assertEqual(1, upload["with"]["retention-days"])

    def test_no_workflow_reads_the_old_publishing_variable(self):
        for path in (ROOT / ".github/workflows").glob("*.yml"):
            self.assertNotIn("PLUS_PUBLIC_KEY_RELEASES", path.read_text(), path.name)

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
        self.assertEqual({"checks", "review-gate", "release-identity", "release-sign", "release-verify", "release-upgrade"},
                         set(publish["needs"]))
        upgrade = self.workflows["custom-android-build.yml"]["jobs"]["release-upgrade"]
        self.assertEqual([26, 28, 34], upgrade["strategy"]["matrix"]["api"], "Android 8 refused; 9 and 14 keep data")
        report = set(self.workflows["custom-android-build.yml"]["jobs"]["report-failure"]["needs"])
        self.assertTrue({"release-sign", "release-verify", "release-upgrade", "publish"} <= report)
        gate = self.workflows["custom-android-build.yml"]["jobs"]["review-gate"]
        self.assertIn("review-gate.py publish", "\n".join(s.get("run", "") for s in steps(gate)))
        build = self.workflows["plus-build.yml"]["jobs"]
        self.assertEqual({"sign", "update-check", "test-gate", "device-test-build"}, set(build["emulator"]["needs"]))
        report = set(build["report"]["needs"])
        self.assertEqual(set(build) - {"report"}, report, "every check must count in the reported status")

    def test_every_release_is_tested_as_an_update_with_the_apps_own_check(self):
        upgrade = self.workflows["custom-android-build.yml"]["jobs"]["release-upgrade"]
        script = next(s for s in steps(upgrade) if "release-upgrade-test.sh" in str(s.get("with", {}).get("script", "")))["with"]["script"]
        for argument in ("$BUILD12_APK", "$PREVIOUS_APK", "$RELEASE_APK", "$OLD_KEY_APK", "$FOREIGN_APK", "debug-app.apk", "debug-test.apk"):
            self.assertIn(argument, script)
        setup = "\n".join(s.get("run", "") for s in steps(upgrade))
        self.assertIn("is not signed with the release key", setup, "the previous release must carry the release key")
        emulator = self.workflows["plus-build.yml"]["jobs"]["emulator"]
        runner = next(s for s in steps(emulator) if "emulator-update-test.sh" in str(s.get("with", {}).get("script", "")))
        self.assertIn("update-check-device-test.sh", runner["with"]["script"])
        device = self.workflows["plus-build.yml"]["jobs"]["device-test-build"]
        self.assertNotIn("secrets.", yaml.safe_dump(device))

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

    def test_jobs_below_an_optional_job_state_their_own_condition(self):
        # GitHub skips a job when any job it depends on, directly or not, was skipped, unless the job's
        # condition starts with always(). The Nuvio baseline is skipped whenever no test fails.
        jobs = self.workflows["plus-build.yml"]["jobs"]
        optional = {job_id for job_id, job in jobs.items() if "needs." in str(job.get("if", ""))
                    and "always()" not in str(job.get("if", ""))}
        self.assertEqual({"nuvio-tests"}, optional)

        def ancestors(job_id):
            needs = jobs[job_id].get("needs", [])
            needs = [needs] if isinstance(needs, str) else needs
            found = set(needs)
            for need in needs:
                found |= ancestors(need)
            return found

        for job_id, job in jobs.items():
            if ancestors(job_id) & optional:
                condition = str(job.get("if", ""))
                self.assertTrue(condition.startswith("always()"), f"{job_id} would be skipped with the baseline")
                if job_id != "report":
                    for need in ([job["needs"]] if isinstance(job["needs"], str) else job["needs"]):
                        if need not in optional:
                            self.assertIn(f"needs.{need}.result == 'success'", condition, f"{job_id} must require {need}")

    def test_every_gradle_job_takes_the_sentry_plugin_from_maven_central(self):
        for job_id, job in self.workflows["plus-build.yml"]["jobs"].items():
            scripts = [step.get("run", "") for step in steps(job)]
            if any("./gradlew " in script for script in scripts):
                gradle_at = next(i for i, script in enumerate(scripts) if "./gradlew " in script)
                installed = [i for i, script in enumerate(scripts) if "plugins-from-central.init.gradle" in script
                             and ".gradle/init.d" in script]
                self.assertTrue(installed and installed[0] < gradle_at, f"{job_id} runs Gradle without the init script")
        init = (ROOT / ".github/plus/plugins-from-central.init.gradle").read_text()
        self.assertIn("useModule(\"io.sentry:sentry-android-gradle-plugin:${requested.version}\")", init)
        # Google's Maven Central mirror only as a fallback: added after the project's repositories
        # (settingsEvaluated), HTTPS, releases only.
        self.assertIn("settingsEvaluated { settings ->", init)
        self.assertIn("url = 'https://maven-central.storage-download.googleapis.com/maven2/'", init)
        self.assertIn("releasesOnly()", init)
        self.assertNotIn("beforeSettings { settings ->\n    settings.dependencyResolutionManagement", init)

    def test_the_build_writes_no_signing_settings(self):
        script = (ROOT / ".github/plus/prepare-build.sh").read_text()
        self.assertNotIn("echo \"NUVIO_RELEASE_", script)


if __name__ == "__main__":
    unittest.main()
