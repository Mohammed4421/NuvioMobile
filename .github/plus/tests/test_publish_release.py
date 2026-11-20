"""Tests of publish-release.sh with a fake gh: release notes come from a fresh "## Next build" section."""
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

PLUS = Path(__file__).resolve().parents[1]
NEXT = "## Next build — safer updates\n\n### Fixed\n- Pausing a season pauses it.\n"
OLD = "## Build 12 — gated updates\n\n- Earlier change.\n"

FAKE_GH = r'''#!/usr/bin/env bash
state="$FAKE_STATE"
echo "$*" >> "$state/calls"
case "$1 $2" in
  "release view")
    tag="$3"
    [[ -f "$state/released-$tag" ]] || exit 1
    if [[ "$*" == *"--json body"* ]]; then cat "$state/released-$tag"; fi
    exit 0 ;;
  "release create")
    notes=""; prev=""
    for arg in "$@"; do [[ "$prev" == --notes-file ]] && notes="$arg"; prev="$arg"; done
    cp "$notes" "$state/created-notes"; echo "$*" > "$state/created"; exit 0 ;;
  "api --paginate")
    [[ -f "$state/api-fails" ]] && exit 1
    cat "$state/releases" 2>/dev/null; exit 0 ;;
esac
exit 2
'''


class PublishReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.dir = Path(self.temp.name)
        self.state = self.dir / "state"
        self.state.mkdir()
        bin_dir = self.dir / "bin"
        bin_dir.mkdir()
        (bin_dir / "gh").write_text(FAKE_GH)
        (bin_dir / "gh").chmod(0o755)
        (self.dir / "dist").mkdir()
        (self.dir / "dist" / "NuvioPlus-build13-arm64-v8a.apk").write_text("apk")
        (self.dir / "dist" / "checks-build.md").write_text("- Build: ok\n")
        self.env = dict(os.environ, PATH=f"{bin_dir}:{os.environ['PATH']}", FAKE_STATE=str(self.state),
                        GITHUB_REPOSITORY="owner/NuvioMobile")

    def tearDown(self):
        self.temp.cleanup()

    def publish(self, changelog, channel="stable"):
        (self.dir / "CHANGELOG.md").write_text("# Changelog\n\nIntro.\n\n" + changelog)
        env = dict(self.env, PLUS_CHANNEL=channel)
        return subprocess.run(["bash", str(PLUS / "publish-release.sh"), "13", "a" * 40, "dist"], cwd=self.dir,
                              env=env, capture_output=True, text=True)

    def released(self, tag, body):
        (self.state / f"released-{tag}").write_text(body)
        with open(self.state / "releases", "a") as releases:
            releases.write(tag + "\n")

    def created_notes(self):
        return (self.state / "created-notes").read_text()

    def test_the_next_build_section_is_published_as_this_build(self):
        result = self.publish(NEXT + "\n" + OLD)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        notes = self.created_notes()
        self.assertIn("### Build 13 — safer updates", notes)
        self.assertNotIn("Next build", notes)
        self.assertNotIn("Earlier change", notes)
        self.assertIn("- Build: ok", notes)
        self.assertRegex(notes, r"<!-- changelog-section: [0-9a-f]{64} -->")
        self.assertIn("--latest", (self.state / "created").read_text())

    def test_the_notes_say_how_to_update_from_build_13_and_from_build_12_in_both_languages(self):
        self.publish(NEXT)
        notes = self.created_notes()
        self.assertIn("**From build 13 or later:**", notes)
        self.assertIn("**From build 12 or earlier:**", notes)
        self.assertIn("Android 7 and 8", notes)
        self.assertIn("التثبيت والتحديث بالعربية", notes)
        self.assertIn("من البناء 12 أو أقدم", notes)

    def test_the_real_changelog_can_be_published_after_build_13(self):
        # Build 13's notes must not be offered again: the top section is a new "Next build".
        changelog = (PLUS.parents[1] / "CHANGELOG.md").read_text()
        top = [line for line in changelog.splitlines() if line.startswith("## ")][:2]
        self.assertTrue(top[0].startswith("## Next build"), top)
        self.assertTrue(top[1].startswith("## Build 13"), top)

    def test_a_pending_label_in_the_heading_is_dropped(self):
        self.publish("## Next build (pending approval) — safer updates\n\n- x\n")
        self.assertIn("### Build 13 — safer updates", self.created_notes())

    def test_without_a_next_build_section_nothing_is_published(self):
        result = self.publish(OLD)
        self.assertEqual(1, result.returncode)
        self.assertIn("must start with a '## Next build' section", result.stdout)
        self.assertFalse((self.state / "created").exists())

    def test_the_notes_of_the_previous_release_are_not_published_again(self):
        self.publish(NEXT)
        first = self.created_notes()
        (self.state / "created").unlink()
        self.released("custom-12", first)
        result = self.publish(NEXT)
        self.assertEqual(1, result.returncode)
        self.assertIn("is the one custom-12 was published with", result.stdout)
        self.assertFalse((self.state / "created").exists())

    def test_new_notes_after_a_release_are_published(self):
        self.released("custom-12", "notes\n<!-- changelog-section: " + "0" * 64 + " -->")
        self.assertEqual(0, self.publish(NEXT).returncode)

    def test_an_unreadable_release_list_blocks(self):
        (self.state / "api-fails").write_text("")
        result = self.publish(NEXT)
        self.assertEqual(1, result.returncode)
        self.assertIn("Could not read the published releases", result.stdout)

    def test_an_already_published_build_is_left_alone(self):
        self.released("custom-13", "published")
        result = self.publish(OLD)
        self.assertEqual(0, result.returncode)
        self.assertIn("already published", result.stdout)
        self.assertFalse((self.state / "created").exists())

    def test_a_beta_is_a_pre_release(self):
        result = self.publish(NEXT, channel="beta")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        created = (self.state / "created").read_text()
        self.assertIn("custom-13-beta", created)
        self.assertIn("--prerelease", created)


if __name__ == "__main__":
    unittest.main()
