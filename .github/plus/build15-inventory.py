#!/usr/bin/env python3
"""Build 15 inventory (P1-01): every area of the repository, how much of it Nuvio Plus changed against
the Nuvio release it is based on, and what the Build 15 audit has reviewed there.

Reproducible: the counts come from git (this commit against portkit/compatibility.json's
upstream_base, which must be fetched first). The review states come from REVIEW below and name the
audit rows behind them; an area that Plus changed and REVIEW does not list is reported NOT REVIEWED,
so a new area can never look covered by default.
Usage: build15-inventory.py [--repo DIR] [--markdown FILE] [--json FILE]
"""
import argparse
import collections
import json
import re
import subprocess
import sys
from pathlib import Path

AREA = re.compile(r"^(?:composeApp|androidApp)/src/[^/]+/kotlin/com/nuvio/(?:app|android)/(core|features)/([^/]+)/")
TEST_SET = re.compile(r"^(?:composeApp|androidApp)/src/[^/]*[Tt]est[^/]*/")

# What Build 15 has reviewed in each area, and the audit rows (docs/plus/build15/audit.md) or
# earlier release tests behind it. PARTIAL names what is still open.
REVIEW = {
    "core/ui": ("PARTIAL", ["B15-UI-01"], "lazy-list keys; the rest is Nuvio's (initial pass)"),
    "core/i18n": ("PARTIAL", ["B15-NUV-02"], "runBlocking resource reads (Nuvio's pattern, deferred)"),
    "features/player": ("PARTIAL", ["B15-SUB-01..08", "B15-PLY-01..04", "B15-SEC-03", "B15-SEC-04", "B15-UI-05", "B15-UI-09"],
                        "subtitles, engine lifecycle, network drop, redirects and TLS, gestures reviewed and tested on "
                        "emulators; HDR/Dolby Vision output and hardware decoding need a device"),
    "features/autosync": ("PARTIAL", ["B15-SUB-01"], "subtitle parser and AutoSync error state; the rest is Nuvio's"),
    "features/downloads": ("PARTIAL", ["B15-DL-01..05", "B15-UI-09"],
                           "season queue (harness r1-r23), range resume and integrity, interruptions, SAF export "
                           "reviewed; SD card and the notification tap on Android 14+ need a device"),
    "features/updater": ("PARTIAL", ["Build 14 release tests", "release run 44989102295"],
                         "update over Build 14 and 12, Android 8 refused (release run); not re-audited line by line"),
    "features/addons": ("PARTIAL", ["Build 14 link confirmation", "B15-ADD-01"], "add-on link confirmation; the rest is Nuvio's"),
    "signing": ("REVIEWED", ["B15-SEC-01"], "old rotation key only; refused alone in the Build 14 release test"),
    "androidApp": ("REVIEWED", ["B15-AND-01", "B15-BLD-01"],
                   "manifests and build file reviewed; install, update and launch tested on an emulator in every PR"),
    "composeApp": ("REVIEWED", ["B15-BLD-01", "B15-AR-01", "B15-ADD-01", "visual-review.md"],
                   "build file, Arabic strings, app shell and the look of the Plus screens"),
    "features/watchprogress": ("REVIEWED", ["B15-WP-01"], "one test fixture, a cherry-pick of Nuvio's fix"),
    "features/library": ("REVIEWED", ["B15-LIB-01", "B15-UI-06", "B15-UI-08", "visual-review.md"],
                         "logic, tests, look, RTL and screen-reader labels"),
    "features/streams": ("PARTIAL", ["B15-STR-01"], "season download sheet logic; its look has no reference screenshot"),
    "features/settings": ("PARTIAL", ["B15-SET-01", "B15-UI-02", "B15-UI-04", "B15-UI-07"],
                          "subtitle appearance and playback pages reviewed; brightness needs a device"),
    ".github/plus": ("REVIEWED", ["policy and script tests"], "every script has tests in .github/plus/tests"),
    ".github/workflows": ("REVIEWED", ["test_workflow_policy.py"], "security policy tested on every pull request"),
    ".github": ("REVIEWED", ["dependabot.yml"], "GitHub Actions updates as pull requests only, weekly"),
    "portkit": ("REVIEWED", ["P7 tests"], "PortKit's own tests"),
    "docs/plus": ("REVIEWED", ["check-requirements.py", "test_audit_findings.py"],
                  "registers checked by the requirements gate and the audit tests on every PR"),
    "(root files)": ("REVIEWED", ["README.md", "CHANGELOG.md", ".gitattributes", ".gitignore"],
                     "merge=ours keeps README/CHANGELOG only when both sides changed them (a sync must check)"),
}

# Nuvio's areas that hold no app source: examined (INITIAL) or left out (EXCLUDED), with the reason (S19-03).
OTHER = {
    "gradle": ("INITIAL", ["baseline-dependencies.txt", "OSV check"],
               "version catalog and wrapper, unchanged since Build 14; 353 release libraries checked by OSV"),
    "vendor": ("INITIAL", ["git submodule status"], "QuickJS and TorrServer pinned as gitlinks; built by Nuvio's code"),
    "scripts": ("INITIAL", ["android-release.yml"],
                "Nuvio's developer and release scripts; used only by Nuvio's hand-started release workflow, "
                "which needs Nuvio's secrets and never runs for Plus"),
    "baselineprofile": ("INITIAL", ["initial-pass.md"], "startup profile generator (initial pass)"),
    "iosApp": ("EXCLUDED", ["initial-pass.md"], "iOS app: not part of the Android Build 15"),
    "Docs": ("EXCLUDED", ["—"], "Nuvio's documentation for add-on authors; no code"),
    "docs": ("EXCLUDED", ["—"], "Nuvio's documentation (subtitle AutoSync); no code"),
    "assets": ("EXCLUDED", ["—"], "images (rating icons); no code"),
    "testFixtures": ("EXCLUDED", ["—"], "test media and subtitles used by the tests"),
    ".github/ISSUE_TEMPLATE": ("EXCLUDED", ["—"], "issue forms; no code"),
}
INITIAL_PASS = "docs/plus/build15/initial-pass.md"


def initial_pass_areas(repo):
    """Areas with a result in the initial pass (initial-pass.py), other than EXCLUDED ones."""
    path = Path(repo, INITIAL_PASS)
    if not path.exists():
        return set()
    return {m.group(1) for m in re.finditer(r"^\| ([a-z]+/[a-z0-9]+) \| \d+ \| [^|]+ \| (?!EXCLUDED)", path.read_text(), re.M)}


def git(repo, *args):
    result = subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True)
    if result.returncode != 0:
        sys.exit(f"git {' '.join(args)}: {result.stderr.strip()}")
    return result.stdout


def area_of(path):
    match = AREA.match(path)
    if match:
        return f"{match.group(1)}/{match.group(2)}"
    parts = path.split("/")
    if parts[0] in (".github", "docs") and len(parts) > 2:
        return "/".join(parts[:2])
    return parts[0] if len(parts) > 1 else "(root files)"


def inventory(repo):
    base = json.loads(Path(repo, "portkit/compatibility.json").read_text())["upstream_base"]["commit"]
    files = git(repo, "ls-files").split("\n")
    changed = {}
    for line in git(repo, "diff", "--name-status", "--no-renames", base, "HEAD").splitlines():
        status, path = line.split("\t", 1)
        changed[path] = status[0]
    initial = initial_pass_areas(repo)
    areas = collections.defaultdict(lambda: {"files": 0, "kotlin": 0, "tests": 0, "added": 0, "modified": 0, "deleted": 0})
    for path in filter(None, files):
        a = areas[area_of(path)]
        a["files"] += 1
        a["kotlin"] += path.endswith(".kt")
        a["tests"] += bool(path.endswith(".kt") and TEST_SET.match(path))
    for path, status in changed.items():
        a = areas[area_of(path)]
        a[{"A": "added", "M": "modified", "D": "deleted"}.get(status, "modified")] += 1
    rows = []
    for name, a in areas.items():
        plus = a["added"] + a["modified"] + a["deleted"]
        default = ("NOT REVIEWED", [], "") if plus else OTHER.get(name) or (
            ("INITIAL", ["initial-pass.md"], "initial pass (S19-03)") if name in initial else
            ("NUVIO (not reviewed)", [], ""))
        state, refs, note = REVIEW.get(name, default)
        rows.append({"area": name, **a, "plus_changes": plus, "review": state, "refs": refs, "note": note})
    rows.sort(key=lambda r: (-r["plus_changes"], -r["kotlin"], r["area"]))
    return {"base": base, "head": git(repo, "rev-parse", "HEAD").strip(), "areas": rows,
            "dependencies": dependencies(repo),
            "totals": {k: sum(r[k] for r in rows) for k in ("files", "kotlin", "tests", "plus_changes")}}


DEPENDENCIES = "docs/plus/build15/baseline-dependencies.txt"


def dependencies(repo):
    """Resolved release libraries (group:artifact:version), counted by group, from the dependency list."""
    path = Path(repo, DEPENDENCIES)
    if not path.exists():
        return {"total": 0, "groups": {}}
    libraries = [l.strip() for l in path.read_text().splitlines() if l.strip() and not l.startswith("#")]
    groups = collections.Counter(l.split(":")[0] for l in libraries)
    return {"total": len(libraries), "groups": dict(groups.most_common())}

def markdown(inv):
    t = inv["totals"]
    out = [
        "# Build 15 inventory (P1-01)",
        "",
        f"Generated by `.github/plus/build15-inventory.py` at `{inv['head'][:12]}` against Nuvio `{inv['base'][:12]}`.",
        f"{t['files']} tracked files, {t['kotlin']} Kotlin ({t['tests']} in test source sets); "
        f"{t['plus_changes']} files changed by Nuvio Plus.",
        "",
        "Review states: REVIEWED (tested as a whole), PARTIAL (what is open is named), NOT REVIEWED "
        "(Plus changed it, no audit row), INITIAL (Nuvio's code, examined in the initial pass, "
        "initial-pass.md), EXCLUDED (no app code, reason given), NUVIO (Nuvio's code, not examined).",
        "",
        "## Areas Nuvio Plus changed",
        "",
        "| Area | Plus files (A/M/D) | Kotlin | Review | Evidence | Open |",
        "|---|---|---|---|---|---|",
    ]
    for r in inv["areas"]:
        if r["plus_changes"]:
            out.append(f"| `{r['area']}` | {r['plus_changes']} ({r['added']}/{r['modified']}/{r['deleted']}) | "
                       f"{r['kotlin']} | {r['review']} | {', '.join(r['refs']) or '—'} | {r['note'] or '—'} |")
    out += ["", "## Nuvio's areas (untouched by Plus)", "",
            "| Area | Files | Kotlin | Tests | Review | Evidence and reason |", "|---|---|---|---|---|---|"]
    for r in inv["areas"]:
        if not r["plus_changes"]:
            out.append(f"| `{r['area']}` | {r['files']} | {r['kotlin']} | {r['tests']} | {r['review']} | "
                       f"{r['note'] or '—'} |")
    deps = inv.get("dependencies") or {"total": 0, "groups": {}}
    out += ["", "## Dependencies", "",
            f"{deps['total']} resolved release libraries (`{DEPENDENCIES}`, configuration "
            "fullReleaseRuntimeClasspath). Build 15 changes no dependency file against Build 14 (the version catalog, "
            "Gradle files and wrapper are identical); the release run checks every library against OSV.", "",
            "| Group | Libraries |", "|---|---|"]
    out += [f"| `{group}` | {count} |" for group, count in deps["groups"].items()]
    return "\n".join(out) + "\n"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", default=".")
    parser.add_argument("--markdown")
    parser.add_argument("--json")
    args = parser.parse_args()
    inv = inventory(args.repo)
    if args.markdown:
        Path(args.markdown).write_text(markdown(inv))
    if args.json:
        Path(args.json).write_text(json.dumps(inv, indent=2) + "\n")
    t = inv["totals"]
    unreviewed = [r["area"] for r in inv["areas"] if r["review"] == "NOT REVIEWED"]
    print(f"{len(inv['areas'])} areas, {t['kotlin']} Kotlin files, {t['plus_changes']} Plus changes; "
          f"Plus areas not reviewed: {', '.join(unreviewed) or 'none'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
