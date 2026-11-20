#!/usr/bin/env python3
"""Known vulnerabilities in the libraries of the release APK, from the OSV database (osv.dev).

Reads the output of `./gradlew :androidApp:dependencies --configuration fullReleaseRuntimeClasspath`,
takes every resolved Maven library (group:artifact:version, after Gradle's version resolution), asks
OSV which known vulnerabilities affect those exact versions, and fails when one is HIGH or CRITICAL
(or of unknown severity) and not reviewed.

A review is an entry in the reviewed file (JSON list): {"id", "package", "reason", "until"}. It must
name why the vulnerability does not apply or cannot be fixed yet, and expires (`until`, YYYY-MM-DD,
at most 180 days ahead): there are no silent or permanent exceptions. An expired or malformed review
fails the check; a review that no longer matches anything is reported.

Prints a Markdown summary. Native libraries packaged as .so files (mpv, FFmpeg, libass) are not
Maven libraries and are not covered.

Usage: osv-check.py <dependencies-output> <reviewed.json> [--today YYYY-MM-DD]
"""
import argparse
import datetime
import json
import re
import sys
import time
import urllib.error
import urllib.request

API = "https://api.osv.dev/v1"
BLOCKING = {"HIGH", "CRITICAL", "UNKNOWN"}
MAX_REVIEW_DAYS = 180
LINE = re.compile(
    r"^[|\s\\+-]*--- (?P<group>[\w.\-]+):(?P<artifact>[\w.\-]+)(?::(?P<version>[^\s:]+))?"
    r"(?: -> (?P<resolved>[^\s]+))?(?P<flags>(?: \([*cn]\))*)\s*$")


def libraries(text):
    """{(group:artifact): version} of the resolved libraries in a Gradle dependencies report."""
    found = {}
    for line in text.splitlines():
        match = LINE.match(line.rstrip())
        if not match or "(c)" in match["flags"] or "(n)" in match["flags"]:
            continue  # constraints and unresolvable declarations are not libraries in the APK
        version = match["resolved"] or match["version"]
        if not version or version.startswith("{") or "FAILED" in version:
            continue
        found[f"{match['group']}:{match['artifact']}"] = version
    return found


def request(url, body=None, attempts=3):
    data = json.dumps(body).encode() if body is not None else None
    for attempt in range(1, attempts + 1):
        try:
            req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(req, timeout=60) as response:
                return json.load(response)
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as error:
            if attempt == attempts:
                raise RuntimeError(f"OSV could not be reached ({url}): {error}") from error
            time.sleep(10 * attempt)


def severity(vuln):
    """HIGH, CRITICAL, MODERATE, LOW or UNKNOWN, as the advisory states it."""
    stated = str((vuln.get("database_specific") or {}).get("severity") or "").upper()
    if stated in {"LOW", "MODERATE", "MEDIUM", "HIGH", "CRITICAL"}:
        return "MODERATE" if stated == "MEDIUM" else stated
    for affected in vuln.get("affected") or []:
        stated = str((affected.get("database_specific") or {}).get("severity") or "").upper()
        if stated in {"LOW", "MODERATE", "MEDIUM", "HIGH", "CRITICAL"}:
            return "MODERATE" if stated == "MEDIUM" else stated
    return "UNKNOWN"


def fixed_versions(vuln, package):
    fixed = []
    for affected in vuln.get("affected") or []:
        if (affected.get("package") or {}).get("name") != package:
            continue
        for range_ in affected.get("ranges") or []:
            fixed += [event["fixed"] for event in range_.get("events") or [] if "fixed" in event]
    return sorted(set(fixed))


def query(libs, fetch=request):
    """[(package, version, vuln)] for every known vulnerability affecting these exact versions."""
    names = sorted(libs)
    hits = []
    for start in range(0, len(names), 500):
        chunk = names[start:start + 500]
        answer = fetch(f"{API}/querybatch", {"queries": [
            {"package": {"ecosystem": "Maven", "name": name}, "version": libs[name]} for name in chunk]})
        results = answer.get("results") or []
        if len(results) != len(chunk):
            raise RuntimeError("OSV answered a different number of results than asked")
        for name, result in zip(chunk, results):
            for vuln in result.get("vulns") or []:
                hits.append((name, libs[name], fetch(f"{API}/vulns/{vuln['id']}")))
    return hits


def check_reviews(reviews, today):
    """{id: review} of valid reviews; raises on an expired or malformed one."""
    valid = {}
    if not isinstance(reviews, list):
        raise ValueError("the reviewed file must be a JSON list")
    for review in reviews:
        if not isinstance(review, dict) or not all(str(review.get(k, "")).strip() for k in ("id", "package", "reason", "until")):
            raise ValueError(f"review without id, package, reason and until: {review}")
        until = datetime.date.fromisoformat(review["until"])
        if until < today:
            raise ValueError(f"review of {review['id']} expired on {until}: review it again")
        if until > today + datetime.timedelta(days=MAX_REVIEW_DAYS):
            raise ValueError(f"review of {review['id']} runs past {MAX_REVIEW_DAYS} days ({until})")
        valid[review["id"]] = review
    return valid


def run(report, reviews, today, fetch=request):
    libs = libraries(report)
    if len(libs) < 20:
        raise ValueError(f"only {len(libs)} libraries found: not a dependencies report of the app")
    valid = check_reviews(reviews, today)
    hits = query(libs, fetch)
    blocking, reviewed, other = [], [], []
    seen = set()
    for package, version, vuln in hits:
        level = severity(vuln)
        ids = {vuln["id"], *(vuln.get("aliases") or [])}
        review = next((valid[i] for i in ids if i in valid and valid[i]["package"] == package), None)
        if review:
            seen.add(review["id"])
        row = (level, vuln["id"], package, version, ", ".join(fixed_versions(vuln, package)) or "none known",
               (vuln.get("summary") or "").strip()[:120], review)
        (reviewed if review else blocking if level in BLOCKING else other).append(row)
    lines = [f"- Library vulnerabilities (OSV, {len(libs)} libraries of the release APK): "
             f"{len(blocking)} blocking, {len(reviewed)} reviewed, {len(other)} low or moderate."]
    for title, rows in (("Blocking", blocking), ("Reviewed", reviewed), ("Low or moderate", other)):
        for level, vid, package, version, fixed, summary, review in sorted(rows):
            note = f" Reviewed until {review['until']}: {review['reason']}" if review else ""
            lines.append(f"  - {title}: {level} `{vid}` in `{package}:{version}` (fixed in {fixed}). {summary}{note}")
    for stale in sorted(set(valid) - seen):
        lines.append(f"  - Review of `{stale}` matches nothing any more: remove it.")
    return blocking, "\n".join(lines)


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("report")
    parser.add_argument("reviewed")
    parser.add_argument("--today", default=datetime.date.today().isoformat())
    args = parser.parse_args(argv)
    try:
        with open(args.report, encoding="utf-8", errors="replace") as file:
            report = file.read()
        with open(args.reviewed, encoding="utf-8") as file:
            reviews = json.load(file)
        blocking, summary = run(report, reviews, datetime.date.fromisoformat(args.today))
    except (OSError, ValueError, RuntimeError) as error:
        print(f"::error::Library vulnerability check: {error}")
        return 1
    print(summary)
    for level, vid, package, version, fixed, _, _ in blocking:
        print(f"::error::{level} {vid} in {package}:{version} (fixed in {fixed}). Update the library, or review it in .github/plus/osv-reviewed.json with a reason and an end date.")
    return 1 if blocking else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
