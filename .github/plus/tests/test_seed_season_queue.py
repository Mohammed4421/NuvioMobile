"""The emulator's download-data check (seed-season-queue.py) passes on kept data and fails on lost data."""
import importlib.util
import json
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("seed", ROOT / ".github/plus/seed-season-queue.py")
seed = importlib.util.module_from_spec(spec)
spec.loader.exec_module(seed)


class SeedSeasonQueue(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.prefs = self.dir / "prefs.xml"
        (self.dir / "before.xml").write_text('<?xml version="1.0"?><map><string name="other">x &amp; y</string></map>')
        seed.seed(str(self.dir / "before.xml"), str(self.prefs))

    def edit(self, key, change):
        root = ET.parse(self.prefs).getroot()
        element = next(e for e in root.findall("string") if e.get("name") == key)
        element.text = json.dumps(change(json.loads(element.text)))
        ET.ElementTree(root).write(self.prefs)

    def test_seeded_data_passes_and_other_settings_are_kept(self):
        self.assertEqual(0, seed.check(str(self.prefs)))
        self.assertIn("other", seed.strings(seed.read(str(self.prefs))))

    def test_a_queue_rewritten_by_build_15_still_passes(self):
        def rewrite(queue):
            for e in queue["entries"]:
                e.update(pausedBy="Episode" if e.get("paused") else None, resuming=False)
            queue["entries"][1]["state"] = "NeedsChoice"
            return queue
        self.edit("extra_season_queue", rewrite)
        self.assertEqual(0, seed.check(str(self.prefs)))

    def test_a_restarted_paused_download_fails(self):
        self.edit("downloads_payload_1", lambda d: {"items": [dict(i, status="Downloading") for i in d["items"]]})
        self.assertEqual(1, seed.check(str(self.prefs)))

    def test_a_replaced_paused_download_fails(self):
        self.edit("downloads_payload_1", lambda d: {"items": [i for i in d["items"] if i["id"] != "seedd1"] +
                                                    [dict(d["items"][1], id="new", downloadedBytes=0)]})
        self.assertEqual(1, seed.check(str(self.prefs)))

    def test_a_paused_episode_dropped_from_the_queue_fails(self):
        self.edit("extra_season_queue", lambda q: {"entries": q["entries"][1:]})
        self.assertEqual(1, seed.check(str(self.prefs)))

    def test_missing_settings_fail(self):
        self.assertEqual(1, seed.check(str(self.dir / "missing.xml")))


if __name__ == "__main__":
    unittest.main()
