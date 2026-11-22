#!/usr/bin/env python3
"""Representative user data for the release update test (owner directive 2026-11-22: upgrade from
Builds 12, 13 and 14 with representative data; spec P6-14..16, D-10).

Puts, for profile 1, what a user has after using an earlier build — installed add-ons (one with a
token in its address) and their on/off state, playback and subtitle settings, and the download data
of seed-season-queue.py (a finished download, a paused episode with its partial file, a waiting one in
the season queue) — into the app's settings files, and after the update checks that every value is
still there, unchanged.

  seed-user-data.py seed  <pulled shared_prefs dir> <out dir>   writes the files to push back
  seed-user-data.py check <pulled shared_prefs dir>             exit 1 if anything was lost or changed

Only standard Python; the XML is the SharedPreferences format Android writes.
"""
import importlib.util
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("seed_season_queue", HERE / "seed-season-queue.py")
queue = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(queue)

ADDON_WITH_TOKEN = "https://seed-addon.example.invalid/user-token-123/manifest.json"
ADDON_PUBLIC = "https://v3-cinemeta.strem.io/manifest.json"
# file -> [(type, key, value)]; keys are the app's own (profile 1).
SETTINGS = {
    "nuvio_addons.xml": [
        ("string", "installed_manifest_urls_1", f"{ADDON_WITH_TOKEN}\n{ADDON_PUBLIC}"),
        ("string", "installed_manifest_enabled_states_1", f"{ADDON_WITH_TOKEN}\ttrue\n{ADDON_PUBLIC}\tfalse"),
    ],
    "nuvio_player_settings.xml": [
        ("string", "preferred_subtitle_language_1", "ara"),
        ("int", "subtitle_font_size_sp_1", "26"),
        ("string", "android_playback_engine_1", "Libmpv"),
        ("float", "playback_brightness_1", "0.42"),
    ],
}
DOWNLOADS = "nuvio_downloads.xml"


def read(path):
    try:
        return ET.parse(path).getroot()
    except (OSError, ET.ParseError):
        return ET.Element("map")


def entries(root):
    """(type, name) -> value; strings by text, numbers and booleans by their value attribute."""
    out = {}
    for element in root:
        name = element.get("name")
        out[(element.tag, name)] = element.text or "" if element.tag == "string" else element.get("value")
    return out


def seed(source_dir, out_dir):
    source, out = Path(source_dir), Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    for file, values in SETTINGS.items():
        root = read(source / file)
        names = {key for _, key, _ in values}
        for element in [e for e in root if e.get("name") in names]:
            root.remove(element)
        for kind, key, value in values:
            if kind == "string":
                ET.SubElement(root, "string", name=key).text = value
            else:
                ET.SubElement(root, kind, name=key, value=value)
        ET.ElementTree(root).write(out / file, encoding="utf-8", xml_declaration=True)
    queue.seed(str(source / DOWNLOADS) if (source / DOWNLOADS).exists() else "", str(out / DOWNLOADS))
    return sorted([*SETTINGS, DOWNLOADS])


def check(pulled_dir):
    pulled = Path(pulled_dir)
    problems = []
    for file, values in SETTINGS.items():
        if not (pulled / file).exists():
            problems.append(f"{file} is gone after the update")
            continue
        kept = entries(read(pulled / file))
        for kind, key, value in values:
            found = kept.get((kind, key))
            if found is None:
                problems.append(f"{file}: {key} is gone after the update")
            elif (float(found) != float(value)) if kind == "float" else (found != value):
                problems.append(f"{file}: {key} changed in the update: {value!r} -> {found!r}")
    for problem in problems:
        print(f"::error::{problem}")
    downloads = queue.check(str(pulled / DOWNLOADS)) if (pulled / DOWNLOADS).exists() else 1
    if not (pulled / DOWNLOADS).exists():
        print(f"::error::{DOWNLOADS} is gone after the update")
    if not problems:
        print("Kept: 2 add-ons (one with a token) and their on/off state; subtitle language, size, engine "
              "and brightness.")
    return 1 if problems or downloads else 0


if __name__ == "__main__":
    if len(sys.argv) == 4 and sys.argv[1] == "seed":
        print(" ".join(seed(sys.argv[2], sys.argv[3])))
    elif len(sys.argv) == 3 and sys.argv[1] == "check":
        sys.exit(check(sys.argv[2]))
    else:
        print(__doc__)
        sys.exit(2)
