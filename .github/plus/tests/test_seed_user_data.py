import importlib.util
import io
import json
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

    def test_six_kinds_and_two_profiles_are_seeded(self):
        self.assertEqual(sorted(["nuvio_addons.xml", "nuvio_downloads.xml", "nuvio_library.xml",
                                 "nuvio_player_settings.xml", "nuvio_profile_cache.xml", "nuvio_theme_settings.xml"]), seed.FILES)
        library = seed.entries(seed.read(self.after / "nuvio_library.xml"))
        self.assertIn(("string", "library_payload_1"), library)
        self.assertIn(("string", "library_payload_2"), library)
        downloads = seed.entries(seed.read(self.after / "nuvio_downloads.xml"))
        self.assertIn(seed.FOLDER, downloads[("string", "extra_download_preferences")])
        self.assertIn(("string", "downloads_payload_1"), downloads)

    def test_the_app_rewriting_the_library_in_its_own_form_is_fine(self):
        import json
        file = self.after / "nuvio_library.xml"
        root = seed.read(file)
        element = next(e for e in root if e.get("name") == "library_payload_1")
        payload = json.loads(element.text)
        for item in payload["items"]:
            item.update(poster=None, genres=[], listKeys=[])  # fields the app adds when it saves
        payload["deltaInitialized"] = False
        element.text = json.dumps(payload)
        ET.ElementTree(root).write(file, encoding="utf-8", xml_declaration=True)
        self.assertEqual(0, self.quiet_check()[0])

    def test_a_title_lost_from_a_profiles_library_is_reported(self):
        file = self.after / "nuvio_library.xml"
        file.write_text(file.read_text().replace("tt0068646", "tt0000000"))
        code, out = self.quiet_check()
        self.assertEqual(1, code)
        self.assertIn("The Godfather (tt0068646) left the library", out)

    def test_a_forgotten_download_folder_is_reported(self):
        file = self.after / "nuvio_downloads.xml"
        file.write_text(file.read_text().replace("Download%2FNuvio", "Movies"))
        code, out = self.quiet_check()
        self.assertEqual(1, code)
        self.assertIn("folderUri changed", out)

    def test_cached_profile_index_reuse_and_active_profile_change_are_reported(self):
        file = self.after / "nuvio_profile_cache.xml"
        root = seed.read(file)
        element = next(e for e in root if e.get("name") == "profile_payload")
        cache = json.loads(element.text)
        cache["profiles"][1]["profile_index"] = 1
        cache["activeProfileIndex"] = 1
        element.text = json.dumps(cache)
        ET.ElementTree(root).write(file, encoding="utf-8", xml_declaration=True)
        result, output = self.quiet_check()
        self.assertEqual(1, result)
        self.assertIn("profile seed-second lost its identity", output)
        self.assertIn("activeProfileIndex changed", output)

    def test_finished_download_must_still_point_to_its_file(self):
        file = self.after / "nuvio_downloads.xml"
        file.write_text(file.read_text().replace(seed.queue.FINISHED_URI, "file:///other.mkv"))
        result, output = self.quiet_check()
        self.assertEqual(1, result)
        self.assertIn("finished download changed", output)

    def test_lost_download_data_is_reported(self):
        (self.after / "nuvio_downloads.xml").unlink()
        self.assertEqual(1, self.quiet_check()[0])


if __name__ == "__main__":
    unittest.main()
