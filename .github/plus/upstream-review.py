#!/usr/bin/env python3
"""Writes the review record for bringing a Nuvio release into Nuvio Plus.

Maps every file Nuvio changed since the common base to the Nuvio Plus features in
.github/plus/features.json, lists Git conflicts, Nuvio CI/build files that were not applied, and
Nuvio code that may duplicate a Plus feature. Each item is a checkbox a reviewer ticks once the
decision is written next to it. Exit status 1 means the merge needs a review.

Usage: upstream-review.py <plus-ref> <nuvio-ref> <nuvio-tag> <target-branch> <conflicts-file> <out.md>
"""
import json
import re
import subprocess
import sys

plus_ref, nuvio_ref, tag, target, conflicts_file, out = sys.argv[1:7]


def git(*args):
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout


def full(path):
    return path if path.split("/")[0] in ("androidApp", "composeApp", "signing", ".github") else "composeApp/src/" + path


base = git("merge-base", plus_ref, nuvio_ref).strip()
nuvio_files = set(git("diff", "--name-only", base, nuvio_ref).split())
plus_files = set(git("diff", "--name-only", base, plus_ref).split())
features = json.load(open(".github/plus/features.json"))["features"]
conflicts = [line.strip() for line in open(conflicts_file) if line.strip()] if conflicts_file != "-" else []
added = [line[1:] for line in git("diff", base, nuvio_ref, "--", "*.kt", "*.kts", "*.xml").splitlines()
         if line.startswith("+") and not line.startswith("+++")]

items = []
lines = [
    f"# Review: Nuvio {tag} into `{target}`",
    "",
    "status: pending",
    "tested_commit: ",
    "approved_by: ",
    "",
    "<!-- Approve by setting status: approved, the commit whose checks passed, your name, and ticking",
    "every box with the decision written after it. Publishing is blocked while any box is open. -->",
    "",
    f"Nuvio `{git('rev-parse', '--short', nuvio_ref).strip()}` · common base `{base[:8]}` · "
    f"{len(nuvio_files)} files changed by Nuvio, {len(plus_files & nuvio_files)} of them also by Nuvio Plus.",
    "",
]

owner = {}
for feature in features:
    for path in feature["files"]:
        owner.setdefault(full(path), []).append(feature["id"])

if conflicts:
    lines += ["## Git conflicts (resolve in this branch)", ""]
    for path in conflicts:
        lines.append(f"- [ ] `{path}` — features: {', '.join(owner.get(path, ['unregistered']))}")
        items.append(path)
    lines.append("")

feature_lines = []
for feature in features:
    files = sorted(full(p) for p in feature["files"] if full(p) in nuvio_files)
    hits = []
    for pattern in feature.get("keywords", []):
        regex = re.compile(pattern, re.IGNORECASE)
        hits += [line.strip()[:160] for line in added if regex.search(line)]
    if not files and not hits:
        continue
    feature_lines += [f"### {feature['id']} — {feature['title']}", ""]
    feature_lines.append(f"- [ ] Decision (keep Plus / adopt Nuvio / combine), data kept ({feature['data']}), tests: {', '.join(feature['tests'])}")
    for path in files:
        feature_lines.append(f"  - changed by Nuvio: `{path}`")
    if hits:
        feature_lines.append("  - Nuvio code that may duplicate or alter this feature:")
        feature_lines += ["    ```", *[f"    {hit}" for hit in sorted(set(hits))[:15]], "    ```"]
    feature_lines.append("")
    items.append(feature["id"])
if feature_lines:
    lines += ["## Nuvio Plus features touched by this release", "", *feature_lines]

ci = sorted(p for p in nuvio_files if p.startswith(".github/"))
build = sorted(p for p in nuvio_files if re.search(r"(\.gradle\.kts|gradle/libs\.versions\.toml|gradle\.properties)$", p))
if ci or build:
    lines += ["## Nuvio CI and build files", ""]
    for path in ci:
        lines.append(f"- [ ] `{path}` — not applied (Nuvio Plus keeps its own CI); confirm nothing here is needed")
        items.append(path)
    for path in build:
        lines.append(f"- [ ] `{path}` — merged; check versions, signing and the Plus build properties")
        items.append(path)
    lines.append("")

gaps = sorted(p for p in plus_files - set(owner) if "/src/" in p and "Test" not in p and p.endswith(".kt"))
if gaps:
    lines += ["## Registry gaps (Plus files without a feature entry)", "", *[f"- `{p}`" for p in gaps], ""]

if not items:
    lines += ["No overlap with Nuvio Plus. Tick nothing; set status and tested_commit after the checks pass.", ""]

open(out, "w").write("\n".join(lines))
print("\n".join(lines))
sys.exit(1 if items else 0)
