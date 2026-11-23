#!/usr/bin/env python3
"""Representative user data for the release update test (owner directive 2026-11-22: upgrade from
Builds 12, 13 and 14 with representative data; spec P6-14..16, D-10).

Puts what a user has after using an earlier build into the app's settings files, for two profiles
(spec P6-12, its six kinds of data):
  1. subtitle and playback settings (language, size, engine, brightness);
  2. several profiles: profile 2 has its own add-ons, subtitle language, theme and library;
  3. download history and the season queue (seed-season-queue.py: a finished download, a paused
     episode with its partial file, a waiting one), and the download preferences;
  4. the library (saved titles per profile);
  5. other local settings (the theme per profile) and installed add-ons with their on/off state
     (one address carries a token);
  6. the download folder picked through the system picker (its content:// tree address and label).
After the update it checks every value (P6-13: the content, not only that the file is there). The
system's own grant for the folder cannot be made by adb (it needs the picker); keeping the address
is checked here, the grant on a phone is the owner's check.

  seed-user-data.py seed  <pulled shared_prefs dir> <out dir>   writes the files to push back
  seed-user-data.py check <pulled shared_prefs dir>             exit 1 if anything was lost or changed
  seed-user-data.py files                                       the settings files it uses

Only standard Python; the XML is the SharedPreferences format Android writes.
"""
import importlib.util
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("seed_season_queue", HERE / "seed-season-queue.py")
queue = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(queue)

ADDON_WITH_TOKEN = "https://seed-addon.example.invalid/user-token-123/manifest.json"
ADDON_PUBLIC = "https://v3-cinemeta.strem.io/manifest.json"
ADDON_PROFILE_2 = "https://second-profile-addon.example.invalid/manifest.json"
FOLDER = "content://com.android.externalstorage.documents/tree/primary%3ADownload%2FNuvio"
PREFERENCES = {"folderUri": FOLDER, "folderLabel": "Download/Nuvio", "wifiOnly": True}
PROFILE_CACHE = {
    "userId": "upgrade-fixture-user", "activeProfileIndex": 2,
    "hasEverSelectedProfile": True, "rememberLastProfileEnabled": True,
    "profiles": [
        {"id": "seed-primary", "user_id": "upgrade-fixture-user", "profile_index": 1, "name": "Primary"},
        {"id": "seed-second", "user_id": "upgrade-fixture-user", "profile_index": 2, "name": "Second"},
    ],
}


def library(*titles):
    return {"items": [{"id": i, "type": t, "name": n, "savedAtEpochMs": 1700000000000 + k}
                      for k, (i, t, n) in enumerate(titles)]}


LIBRARY = {1: library(("tt0111161", "movie", "The Shawshank Redemption"), ("tt0903747", "series", "Breaking Bad")),
           2: library(("tt0068646", "movie", "The Godfather"))}
# file -> [(type, key, value)]; keys are the app's own (profile 1).
SETTINGS = {
    "nuvio_addons.xml": [
        ("string", "installed_manifest_urls_1", f"{ADDON_WITH_TOKEN}\n{ADDON_PUBLIC}"),
        ("string", "installed_manifest_enabled_states_1", f"{ADDON_WITH_TOKEN}\ttrue\n{ADDON_PUBLIC}\tfalse"),
        ("string", "installed_manifest_urls_2", ADDON_PROFILE_2),
    ],
    "nuvio_player_settings.xml": [
        ("string", "preferred_subtitle_language_1", "ara"),
        ("int", "subtitle_font_size_sp_1", "26"),
        ("string", "android_playback_engine_1", "Libmpv"),
        ("float", "playback_brightness_1", "0.42"),
        ("string", "preferred_subtitle_language_2", "eng"),
    ],
    "nuvio_theme_settings.xml": [
        ("string", "selected_theme_1", "OCEAN"),
        ("string", "selected_theme_2", "JADE"),
    ],
}
# file -> {key: (json value, check)}: JSON the app reads and may write back in its own form, so the
# check compares what it means, not the text.
JSON_VALUES = {
    "nuvio_profile_cache.xml": {"profile_payload": PROFILE_CACHE},
    "nuvio_library.xml": {f"library_payload_{p}": v for p, v in LIBRARY.items()},
    "nuvio_downloads.xml": {"extra_download_preferences": PREFERENCES},
}
DOWNLOADS = "nuvio_downloads.xml"
FILES = sorted({*SETTINGS, *JSON_VALUES, DOWNLOADS})


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


def json_changes(expected, found):
    """What of [expected] is missing or different in [found]; a library compares its titles by id."""
    if not isinstance(found, dict):
        return ["the stored value is no longer a JSON object"]
    if "profiles" in expected:
        errors = [f"{k} changed" for k, value in expected.items()
                  if k != "profiles" and found.get(k) != value]
        profiles = found.get("profiles")
        if not isinstance(profiles, list) or len(profiles) != len(expected["profiles"]):
            return errors + ["the cached profile count changed"]
        for profile in expected["profiles"]:
            matches = [p for p in profiles if isinstance(p, dict) and p.get("id") == profile["id"]]
            if len(matches) != 1 or any(matches[0].get(k) != v for k, v in profile.items()):
                errors.append(f"profile {profile['id']} lost its identity, index, owner or name")
        return errors
    if isinstance(expected, dict) and "items" in expected:
        have = {i.get("id"): i for i in (found or {}).get("items", []) if isinstance(i, dict)}
        out = []
        for item in expected["items"]:
            got = have.get(item["id"])
            if not got:
                out.append(f"{item['name']} ({item['id']}) left the library")
            elif (got.get("type"), got.get("name")) != (item["type"], item["name"]):
                out.append(f"{item['id']} changed: {got.get('type')}/{got.get('name')}")
        return out
    return [f"{k} changed: {v!r} -> {(found or {}).get(k)!r}" for k, v in expected.items() if (found or {}).get(k) != v]


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
    for file, values in JSON_VALUES.items():
        root = read(out / file if (out / file).exists() else source / file)
        for element in [e for e in root if e.get("name") in values]:
            root.remove(element)
        for key, value in values.items():
            ET.SubElement(root, "string", name=key).text = json.dumps(value, separators=(",", ":"))
        ET.ElementTree(root).write(out / file, encoding="utf-8", xml_declaration=True)
    return FILES


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
    for file, values in JSON_VALUES.items():
        kept = entries(read(pulled / file)) if (pulled / file).exists() else {}
        for key, value in values.items():
            raw = kept.get(("string", key))
            if raw is None:
                problems.append(f"{file}: {key} is gone after the update")
                continue
            try:
                found = json.loads(raw)
            except ValueError:
                problems.append(f"{file}: {key} is no longer readable JSON")
                continue
            problems += [f"{file}: {key}: {p}" for p in json_changes(value, found)]
    for problem in problems:
        print(f"::error::{problem}")
    downloads = queue.check(str(pulled / DOWNLOADS)) if (pulled / DOWNLOADS).exists() else 1
    if not (pulled / DOWNLOADS).exists():
        print(f"::error::{DOWNLOADS} is gone after the update")
    if not problems:
        print("Kept: add-ons (one with a token) and their on/off state for two profiles; subtitle language, "
              "size, engine and brightness; the theme of each profile; the library of each profile; the "
              "download folder (content:// tree) and Wi-Fi only.")
    return 1 if problems or downloads else 0


if __name__ == "__main__":
    if len(sys.argv) == 4 and sys.argv[1] == "seed":
        print(" ".join(seed(sys.argv[2], sys.argv[3])))
    elif len(sys.argv) == 2 and sys.argv[1] == "files":
        print(" ".join(FILES))
    elif len(sys.argv) == 3 and sys.argv[1] == "check":
        sys.exit(check(sys.argv[2]))
    else:
        print(__doc__)
        sys.exit(2)
