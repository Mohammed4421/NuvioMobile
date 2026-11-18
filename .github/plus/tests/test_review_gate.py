"""Tests of review-gate.py on real Git repositories with a fake GitHub API.

Run: python3 -m unittest discover -s .github/plus/tests
"""
import importlib.util
import io
import os
import subprocess
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

GATE = Path(__file__).resolve().parents[1] / "review-gate.py"
OWNER = "owner"
ACTIONS = "github-actions[bot]"
APPROVED = "status: approved\n\n- [x] `composeApp/a.kt` — kept both\n"


def load_gate():
    spec = importlib.util.spec_from_file_location("review_gate", GATE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class Repo:
    """A Nuvio history (tags 1.0.0, 1.1.0-beta.1, 1.1.0) and a Nuvio Plus branch made from 1.0.0."""

    def __init__(self, path):
        self.path = path
        env = dict(os.environ, GIT_AUTHOR_NAME="t", GIT_AUTHOR_EMAIL="t@t", GIT_COMMITTER_NAME="t",
                   GIT_COMMITTER_EMAIL="t@t", GIT_CONFIG_GLOBAL="/dev/null", GIT_CONFIG_NOSYSTEM="1")
        self.env = env
        self.git("init", "-q", "-b", "nuvio")
        self.commit("a.kt", "1.0.0", "Nuvio 1.0.0")
        self.git("tag", "1.0.0")
        self.git("switch", "-q", "-c", "plus")
        self.baseline = self.commit("plus.kt", "plus", "Nuvio Plus (custom-12)")
        self.git("switch", "-q", "nuvio")
        self.commit("a.kt", "beta", "Nuvio beta")
        self.git("tag", "1.1.0-beta.1")
        self.commit("a.kt", "1.1.0", "Nuvio 1.1.0")
        self.git("tag", "1.1.0")
        self.git("switch", "-q", "plus")

    def git(self, *args):
        return subprocess.run(["git", *args], cwd=self.path, env=self.env, check=True,
                              capture_output=True, text=True).stdout.strip()

    def commit(self, name, content, message):
        file = Path(self.path, name)
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(content)
        self.git("add", name)
        self.git("commit", "-q", "-m", message)
        return self.git("rev-parse", "HEAD")

    def merge(self, tag):
        self.git("merge", "-q", "--no-edit", "-X", "theirs", tag)
        return self.git("rev-parse", "HEAD")

    def record(self, tag, text, message="Review record"):
        return self.commit(f".github/plus/reviews/nuvio-{tag}.md", text, message)

    def head(self):
        return self.git("rev-parse", "HEAD")


class FakeGitHub:
    def __init__(self):
        self.responses = {}
        self.offline = False

    def get(self, path):
        if path not in self.responses and path.startswith("commits/") and not self.offline:
            return []  # GitHub answers an empty list for a commit without pull requests or statuses.
        if path not in self.responses:
            raise RuntimeError(f"HTTP 404 {path}")
        return self.responses[path]

    def pull(self, number, head, merge_commit, merged_by=OWNER, base="custom-subtitles", associated=()):
        pull = {"number": number, "base": {"ref": base}, "merged_at": "2026-11-18T00:00:00Z",
                "merge_commit_sha": merge_commit, "head": {"sha": head}}
        self.responses[f"pulls/{number}"] = dict(pull, merged=True, merged_by={"login": merged_by})
        for commit in (merge_commit, head, *associated):
            self.responses.setdefault(f"commits/{commit}/pulls", []).append(pull)

    def statuses(self, sha, state="success", creator=ACTIONS, contexts=("nuvio-plus/checks", "nuvio-plus/review")):
        self.responses[f"commits/{sha}/statuses?per_page=100"] = [
            {"context": c, "state": state, "creator": {"login": creator}} for c in contexts
        ]


class ReviewGateTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = Repo(self.tmp.name)
        self.gate = load_gate()
        self.gate.BASELINE = self.repo.baseline
        self.api = FakeGitHub()
        self.gate.GitHub = lambda repository, token: self.api
        self.cwd = os.getcwd()
        os.chdir(self.tmp.name)
        self.env = dict(os.environ)
        os.environ.update(GITHUB_REPOSITORY=f"{OWNER}/NuvioMobile", GITHUB_REF_NAME="custom-subtitles", GH_TOKEN="t")
        os.environ.pop("PLUS_APPROVERS", None)

    def tearDown(self):
        os.chdir(self.cwd)
        os.environ.clear()
        os.environ.update(self.env)
        self.tmp.cleanup()

    def run_gate(self, mode):
        out = io.StringIO()
        with redirect_stdout(out):
            code = self.gate.main(["review-gate.py", mode])
        return code, out.getvalue()

    def assertBlocked(self, mode, reason):
        code, out = self.run_gate(mode)
        self.assertEqual(1, code, out)
        self.assertIn(reason, out)

    def assertPasses(self, mode):
        code, out = self.run_gate(mode)
        self.assertEqual(0, code, out)

    # --- records -------------------------------------------------------------------------------

    def test_no_nuvio_release_since_the_baseline_needs_no_record(self):
        self.assertPasses("records")

    def test_a_merged_release_without_a_record_is_blocked(self):
        self.repo.merge("1.1.0")
        self.assertBlocked("records", "nuvio-1.1.0.md: missing")

    def test_deleting_the_record_does_not_skip_the_review(self):
        self.repo.merge("1.1.0")
        self.repo.record("1.1.0", APPROVED)
        self.repo.git("rm", "-q", ".github/plus/reviews/nuvio-1.1.0.md")
        self.repo.git("commit", "-q", "-m", "Drop the record")
        self.assertBlocked("records", "nuvio-1.1.0.md: missing")

    def test_a_pending_record_is_blocked(self):
        self.repo.merge("1.1.0")
        self.repo.record("1.1.0", "status: pending\n")
        self.assertBlocked("records", "status is 'pending'")

    def test_an_open_box_is_blocked(self):
        self.repo.merge("1.1.0")
        self.repo.record("1.1.0", APPROVED + "- [ ] `b.kt` — undecided\n")
        self.assertBlocked("records", "1 review items are not ticked")

    def test_a_rejected_release_that_is_merged_is_blocked(self):
        self.repo.merge("1.1.0")
        self.repo.record("1.1.0", "status: rejected\n")
        self.assertBlocked("records", "was rejected, but it is part of this commit")

    def test_a_rejected_release_that_is_not_merged_is_ignored(self):
        self.repo.record("1.1.0", "status: rejected\n")
        self.assertPasses("records")

    def test_an_unfinished_record_of_an_older_merged_release_is_blocked(self):
        self.repo.merge("1.1.0")
        self.repo.record("1.1.0-beta.1", "status: pending\n")
        self.repo.record("1.1.0", APPROVED)
        self.assertBlocked("records", "nuvio-1.1.0-beta.1.md: status is 'pending'")

    def test_an_approved_complete_record_passes(self):
        self.repo.merge("1.1.0")
        self.repo.record("1.1.0", APPROVED)
        self.assertPasses("records")

    def test_missing_nuvio_tags_block_instead_of_passing(self):
        self.repo.merge("1.1.0")
        for tag in ("1.0.0", "1.1.0-beta.1", "1.1.0"):
            self.repo.git("tag", "-d", tag)
        self.assertBlocked("records", "Nuvio release tags are missing")

    def test_a_commit_without_the_baseline_is_blocked(self):
        self.repo.git("switch", "-q", "nuvio")
        self.assertBlocked("records", "does not contain the baseline")

    # --- publish -------------------------------------------------------------------------------

    def reviewed_sync(self, merged_by=OWNER):
        """Nuvio 1.1.0 merged and recorded on a review branch, then merged by a pull request."""
        self.repo.git("switch", "-q", "-c", "plus-review/nuvio-1.1.0")
        self.repo.merge("1.1.0")
        pr_head = self.repo.record("1.1.0", APPROVED)
        self.repo.git("switch", "-q", "plus")
        self.repo.git("merge", "-q", "--no-ff", "--no-edit", "plus-review/nuvio-1.1.0")
        merge = self.repo.head()
        self.api.pull(7, pr_head, merge, merged_by=merged_by)
        return pr_head, merge

    def test_a_reviewed_and_tested_pull_request_can_be_published(self):
        pr_head, _ = self.reviewed_sync()
        self.api.statuses(pr_head)
        self.assertPasses("publish")

    def test_a_direct_push_is_not_published(self):
        pr_head, _ = self.reviewed_sync()
        self.api.statuses(pr_head)
        self.repo.commit("plus.kt", "pushed directly", "Unreviewed change")
        self.assertBlocked("publish", "no merged pull request into custom-subtitles")

    def test_a_pull_request_merged_by_someone_else_is_not_published(self):
        pr_head, _ = self.reviewed_sync(merged_by="mallory")
        self.api.statuses(pr_head)
        self.assertBlocked("publish", "merged by 'mallory', not an approver")

    def test_untested_or_failing_checks_are_not_published(self):
        pr_head, _ = self.reviewed_sync()
        self.assertBlocked("publish", "has no 'nuvio-plus/checks' status")
        self.api.statuses(pr_head, state="failure")
        self.assertBlocked("publish", "'nuvio-plus/checks' is failure")

    def test_a_status_not_set_by_github_actions_is_not_trusted(self):
        pr_head, _ = self.reviewed_sync()
        self.api.statuses(pr_head, creator=OWNER)
        self.assertBlocked("publish", "was not set by GitHub Actions")

    def test_a_record_changed_after_the_pull_request_is_not_published(self):
        pr_head, _ = self.reviewed_sync()
        self.api.statuses(pr_head)
        self.repo.git("switch", "-q", "-c", "plus-fix/edit")
        fix_head = self.repo.record("1.1.0", APPROVED + "- [x] `c.kt` — added later\n", "Edit record")
        self.repo.git("switch", "-q", "plus")
        self.repo.git("merge", "-q", "--no-ff", "--no-edit", "plus-fix/edit")
        merge = self.repo.head()
        self.api.pull(8, fix_head, merge)
        self.api.statuses(fix_head)
        self.assertBlocked("publish", "changed after pull request #7 was approved")

    def test_an_unanswered_github_api_blocks_publishing(self):
        self.reviewed_sync()
        self.api.responses.clear()
        self.api.offline = True
        self.assertBlocked("publish", "GitHub could not confirm the review")

    def test_publishing_needs_the_records_too(self):
        self.repo.merge("1.1.0")
        self.assertBlocked("publish", "nuvio-1.1.0.md: missing")

    def test_a_change_without_a_nuvio_release_still_needs_a_tested_pull_request(self):
        self.repo.git("switch", "-q", "-c", "plus-fix/x")
        pr_head = self.repo.commit("plus.kt", "fix", "Fix")
        self.repo.git("switch", "-q", "plus")
        self.repo.git("merge", "-q", "--no-ff", "--no-edit", "plus-fix/x")
        self.api.pull(9, pr_head, self.repo.head())
        self.assertBlocked("publish", "has no 'nuvio-plus/checks' status")
        self.api.statuses(pr_head)
        self.assertPasses("publish")


if __name__ == "__main__":
    unittest.main()
