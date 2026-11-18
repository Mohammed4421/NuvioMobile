"""Security rules of the Nuvio Plus workflows, checked on the files themselves.

These keep later edits (or a Nuvio merge) from quietly undoing the isolation of secrets and write
access. Run: python3 -m unittest discover -s .github/plus/tests
"""
import re
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

    def test_the_key_is_only_in_the_sign_job(self):
        for name, _, job_id, job in self.each_job():
            text = yaml.safe_dump(job)
            if "CUSTOM_KEYSTORE_BASE64" in text and "uses" not in job:
                self.assertEqual(("plus-build.yml", "sign"), (name, job_id))

    def test_checkouts_keep_no_credentials_unless_the_job_pushes(self):
        for name, _, job_id, job in self.each_job():
            for step in steps(job):
                if str(step.get("uses", "")).startswith("actions/checkout@"):
                    persisted = (step.get("with") or {}).get("persist-credentials", True)
                    if (name, job_id) == ("plus-sync.yml", "prepare"):
                        continue
                    self.assertIs(False, persisted, f"{name}/{job_id} keeps Git credentials")

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
        self.assertEqual({"checks", "review-gate"}, set(publish["needs"]))
        gate = self.workflows["custom-android-build.yml"]["jobs"]["review-gate"]
        self.assertIn("review-gate.py publish", "\n".join(s.get("run", "") for s in steps(gate)))
        build = self.workflows["plus-build.yml"]["jobs"]
        self.assertEqual({"sign", "update-check", "test-gate"}, set(build["emulator"]["needs"]))
        report = set(build["report"]["needs"])
        self.assertEqual(set(build) - {"report"}, report, "every check must count in the reported status")

    def test_the_build_writes_no_signing_settings(self):
        script = (ROOT / ".github/plus/prepare-build.sh").read_text()
        self.assertNotIn("echo \"NUVIO_RELEASE_", script)


if __name__ == "__main__":
    unittest.main()
