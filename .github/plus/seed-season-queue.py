#!/usr/bin/env python3
"""Puts a Build 14 season download with a paused episode into the app's download settings, and checks
it after an update (Build 15 contract: nothing of it is lost, the paused episode is not restarted or
replaced, and its partial file stays).

  seed-season-queue.py seed  <nuvio_downloads.xml or ""> <out.xml>   writes the settings file to push
  seed-season-queue.py check <nuvio_downloads.xml>                   prints what the new build kept

Only standard Python; the XML is the SharedPreferences format Android writes.
"""
import json
import sys
import xml.etree.ElementTree as ET

PROFILE = 1
PARTIAL = "Plus_Seed_Show_S01E01_seedd1.mkv"


def download(id_, episode, status, file_name, downloaded=0, local=None):
    return {
        "id": id_, "contentType": "series", "parentMetaId": "tt-plus-seed", "parentMetaType": "series",
        "videoId": f"tt-plus-seed:1:{episode}", "title": "Plus Seed Show", "seasonNumber": 1,
        "episodeNumber": episode, "streamTitle": "Seed 1080p", "providerName": "Seed",
        "sourceUrl": f"https://example.invalid/seed-e{episode}.mkv", "fileName": file_name, "status": status,
        "downloadedBytes": downloaded, "totalBytes": 1000, "localFileUri": local,
        "createdAtEpochMs": 1700000000000, "updatedAtEpochMs": 1700000000000,
    }


def entry(id_, episode, state, download_id=None, paused=False):
    return {
        "id": id_, "contentType": "series", "parentMetaId": "tt-plus-seed", "parentMetaType": "series",
        "title": "Plus Seed Show", "videoId": f"tt-plus-seed:1:{episode}", "seasonNumber": 1, "episodeNumber": episode,
        "reference": {"addonId": "seed", "addonName": "Seed", "text": "Seed 1080p"},
        "state": state, "downloadId": download_id, "paused": paused, "profileId": PROFILE,
    }


# What Build 14 stores: E1 paused (as Downloading + paused), E2 waiting; E0 finished earlier.
DOWNLOADS = {"items": [
    download("seedd0", 0, "Completed", "Plus_Seed_Show_S01E00_seedd0.mkv", 1000, "file:///seed-e0.mkv"),
    download("seedd1", 1, "Paused", PARTIAL, 400),
]}
QUEUE = {"entries": [entry("seede1", 1, "Downloading", "seedd1", paused=True), entry("seede2", 2, "Waiting")]}
KEYS = {f"downloads_payload_{PROFILE}": DOWNLOADS, "extra_season_queue": QUEUE}


def read(path):
    if not path:
        return ET.Element("map")
    try:
        return ET.parse(path).getroot()
    except (OSError, ET.ParseError):
        return ET.Element("map")


def strings(root):
    return {e.get("name"): e.text or "" for e in root.findall("string")}


def seed(source, out):
    root = read(source)
    for element in [e for e in root.findall("string") if e.get("name") in KEYS]:
        root.remove(element)
    for name, value in KEYS.items():
        ET.SubElement(root, "string", name=name).text = json.dumps(value, separators=(",", ":"))
    ET.ElementTree(root).write(out, encoding="utf-8", xml_declaration=True)


def check(path):
    values = strings(read(path))
    problems = []
    items = {i["id"]: i for i in json.loads(values.get(f"downloads_payload_{PROFILE}", '{"items":[]}')).get("items", [])}
    if items.get("seedd0", {}).get("status") != "Completed":
        problems.append(f"the finished download changed or is gone: {items.get('seedd0')}")
    d1 = items.get("seedd1")
    if not d1:
        problems.append("the paused download is gone")
    elif d1.get("status") != "Paused" or d1.get("fileName") != PARTIAL or d1.get("downloadedBytes") != 400:
        problems.append(f"the paused download was restarted or replaced: {d1}")
    queue = json.loads(values.get("extra_season_queue", '{"entries":[]}')).get("entries", [])
    by_id = {e["id"]: e for e in queue}
    e1 = by_id.get("seede1")
    if not e1 or e1.get("downloadId") != "seedd1" or not e1.get("paused"):
        problems.append(f"the paused episode left the queue or lost its download: {e1}")
    if "seede2" not in by_id:
        problems.append("the waiting episode left the queue")
    duplicates = [i for i in items.values() if i.get("episodeNumber") == 1 and i["id"] != "seedd1"]
    if duplicates:
        problems.append(f"episode 1 was downloaded again: {duplicates}")
    rewritten = any("pausedBy" in e for e in queue)
    for problem in problems:
        print(f"::error::{problem}")
    states = ", ".join(f"E{e['episodeNumber']}:{e['state']}{'+paused' if e.get('paused') else ''}" for e in queue)
    print(f"Season queue after the update: {states}; "
          f"{'read and saved again by the new build' if rewritten else 'not rewritten (the new build did not need to change it)'}")
    return 1 if problems else 0


if __name__ == "__main__":
    if len(sys.argv) == 4 and sys.argv[1] == "seed":
        seed(sys.argv[2], sys.argv[3])
    elif len(sys.argv) == 3 and sys.argv[1] == "check":
        sys.exit(check(sys.argv[2]))
    else:
        sys.exit(__doc__)
