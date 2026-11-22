import importlib.util
import io
import contextlib
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("seed_user_data", ROOT / ".github/plus/seed-user-data.py")
seed = importlib.util.module_from_spec(spec)
spec.loader.exec_module(seed)


class SeedUserDataTest(unittest.TestCase):
    """The release update test's representative data: seeded values must come back unchanged."""

    def setUp(self):
        self.before = Path(tempfile.mkdtemp())
        self.after = Path(tempfile.mkdtemp())
        # What an earlier build already wrote: an unrelated setting that must survive the seeding.
        root = ET.Element("map")
        ET.SubElement(root, "boolean", name="show_loading_overlay_1", value="true")
        ET.ElementTree(root).write(self.before / "nuvio_player_settings.xml", encoding="utf-8", xml_declaration=True)
        seed.seed(self.before, self.after)

    def quiet_check(self):
        with contextlib.redirect_stdout(io.StringIO()) as out:
            code = seed.check(self.after)
        return code, out.getvalue()

    def test_seeded_data_checks_clean_and_keeps_what_was_there(self):
        self.assertEqual(0, self.quiet_check()[0])
        kept = seed.entries(seed.read(self.after / "nuvio_player_settings.xml"))
        self.assertEqual("true", kept[("boolean", "show_loading_overlay_1")])
        self.assertIn(seed.ADDON_WITH_TOKEN, kept.get(("string", "installed_manifest_urls_1"), "") +
                      seed.entries(seed.read(self.after / "nuvio_addons.xml"))[("string", "installed_manifest_urls_1")])

    def test_a_changed_value_is_reported(self):
        file = self.after / "nuvio_player_settings.xml"
        file.write_text(file.read_text().replace('value="26"', 'value="18"'))
        code, out = self.quiet_check()
        self.assertEqual(1, code)
        self.assertIn("subtitle_font_size_sp_1 changed in the update", out)

    def test_a_removed_addon_list_is_reported(self):
        (self.after / "nuvio_addons.xml").unlink()
        code, out = self.quiet_check()
        self.assertEqual(1, code)
        self.assertIn("nuvio_addons.xml is gone after the update", out)

    def test_lost_download_data_is_reported(self):
        (self.after / "nuvio_downloads.xml").unlink()
        self.assertEqual(1, self.quiet_check()[0])


if __name__ == "__main__":
    unittest.main()
