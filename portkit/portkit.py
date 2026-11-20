#!/usr/bin/env python3
"""PortKit: moves Nuvio Plus features into a compatible Nuvio project, safely.

This file holds the checks every later step relies on. Standard library only.

  portkit.py validate  [--repo DIR]                 registry, overlay and policy are consistent
  portkit.py inventory [--repo DIR] [--base SHA]    what each feature changes against upstream

The feature registry is .github/plus/features.json (one registry, also used by the upstream
review); portkit/compatibility.json adds PortKit's level for each feature and the owners of files
that more than one feature changes; portkit/policy.json lists what is never transferred.
Exit status: 0 when consistent, 1 with one line per problem otherwise.
"""
import argparse
import fnmatch
import json
import subprocess
import sys
from pathlib import Path

LEVELS = ("portable", "adapter-required", "manual", "unsupported")
# Registry paths are relative to composeApp/src unless they start with a top-level folder.
TOP_LEVEL = ("composeApp/", "androidApp/", "iosApp/", ".github/", "docs/", "gradle/", "signing/")


def resolve(path):
    return path if path.startswith(TOP_LEVEL) or "/" not in path else "composeApp/src/" + path


def forbidden_match(path, patterns):
    for pattern in patterns:
        if fnmatch.fnmatch(path, pattern):
            return pattern
        if pattern.startswith("**/") and fnmatch.fnmatch(path, pattern[3:]):
            return pattern
        if pattern.endswith("/**") and (path + "/").startswith(pattern[:-2]):
            return pattern
    return None


def load(repo):
    repo = Path(repo)
    registry = json.loads((repo / ".github/plus/features.json").read_text())
    overlay = json.loads((repo / "portkit/compatibility.json").read_text())
    policy = json.loads((repo / "portkit/policy.json").read_text())
    return registry, overlay, policy


def feature_paths(feature):
    """Source and test files of a feature, resolved to repository paths."""
    return [resolve(p) for p in feature.get("files", []) + feature.get("test_files", [])]


def owners_by_file(registry, with_removed=True):
    owners = {}
    for feature in registry["features"]:
        removed = [resolve(p) for p in feature.get("removed_files", [])] if with_removed else []
        for path in feature_paths(feature) + removed:
            owners.setdefault(path, []).append(feature["id"])
    return owners


def dependency_order(features, wanted):
    """Features in `wanted` plus everything they depend on, dependencies first."""
    by_id = {f["id"]: f for f in features}
    order, state = [], {}

    def visit(fid, chain):
        if fid not in by_id:
            raise ValueError(f"unknown feature '{fid}'" + (f" (needed by {chain[-1]})" if chain else ""))
        if state.get(fid) == "done":
            return
        if state.get(fid) == "visiting":
            raise ValueError("dependency cycle: " + " -> ".join(chain + [fid]))
        state[fid] = "visiting"
        for dep in by_id[fid].get("depends", []):
            visit(dep, chain + [fid])
        state[fid] = "done"
        order.append(fid)

    for fid in wanted:
        visit(fid, [])
    return order


def validate(repo):
    """Returns the list of problems (empty when consistent)."""
    repo = Path(repo)
    problems = []
    registry, overlay, policy = load(repo)
    features = registry.get("features", [])
    ids = [f.get("id") for f in features]
    if len(ids) != len(set(ids)):
        problems.append("registry: duplicate feature ids")
    for feature in features:
        fid = feature.get("id", "?")
        for key in ("id", "title", "files", "depends", "tests"):
            if key not in feature:
                problems.append(f"registry: {fid} has no '{key}'")
        for path in feature_paths(feature):
            if not (repo / path).exists():
                problems.append(f"registry: {fid} lists {path}, which does not exist")
        for path in feature.get("removed_files", []):
            if (repo / resolve(path)).exists():
                problems.append(f"registry: {fid} removes {resolve(path)}, which still exists")
    try:
        dependency_order(features, ids)
    except ValueError as error:
        problems.append(f"registry: {error}")

    levels = overlay.get("features", {})
    for fid in ids:
        if fid not in levels:
            problems.append(f"compatibility: no level for registry feature '{fid}'")
    for fid, entry in levels.items():
        if fid not in ids:
            problems.append(f"compatibility: '{fid}' is not in the registry")
            continue
        if entry.get("level") not in LEVELS:
            problems.append(f"compatibility: {fid} has level {entry.get('level')!r}; expected one of {', '.join(LEVELS)}")
        if not isinstance(entry.get("verified"), bool) or not entry.get("reason"):
            problems.append(f"compatibility: {fid} needs 'verified' (true/false) and a 'reason'")

    by_id = {f["id"]: f for f in features if "id" in f}
    patterns = policy.get("forbidden", [])
    for fid, feature in by_id.items():
        level = levels.get(fid, {}).get("level")
        for path in feature_paths(feature):
            hit = forbidden_match(path, patterns)
            if hit and level != "unsupported":
                problems.append(f"policy: {fid} ({level}) lists {path}, forbidden by {hit}; "
                                "such a feature must be 'unsupported' or drop the file")
        # A feature can only be as portable as what it needs.
        for dep in feature.get("depends", []):
            if levels.get(dep, {}).get("level") == "unsupported" and level not in (None, "unsupported"):
                problems.append(f"compatibility: {fid} is {level} but depends on unsupported '{dep}'")

    owners = owners_by_file(registry)
    declared = overlay.get("shared_files", {})
    for path, who in sorted(owners.items()):
        if len(who) > 1:
            entry = declared.get(path)
            if entry is None:
                problems.append(f"compatibility: {path} is changed by {', '.join(who)} but has no shared_files entry")
            elif sorted(entry.get("owners", [])) != sorted(who):
                problems.append(f"compatibility: shared_files owners of {path} are {entry.get('owners')}, registry says {who}")
            elif not entry.get("policy"):
                problems.append(f"compatibility: shared_files entry of {path} needs a 'policy'")
    for path in policy.get("not_features", {}):
        if path in owners:
            problems.append(f"policy: {path} is in not_features but {', '.join(owners[path])} owns it")
    for path in declared:
        if len(owners.get(path, [])) < 2:
            problems.append(f"compatibility: shared_files lists {path}, which fewer than two features change")
    return problems


def git(repo, *args):
    return subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True)


def inventory(repo, base):
    """Per feature: each file new or changed against the upstream base; and Plus changes no feature owns."""
    repo = Path(repo)
    registry, overlay, policy = load(repo)
    if git(repo, "cat-file", "-e", f"{base}^{{commit}}").returncode != 0:
        raise SystemExit(f"upstream base {base} is not in this repository (fetch NuvioMedia/NuvioMobile)")
    owners = owners_by_file(registry)
    result = {"base": base, "features": {}, "unowned_changes": []}
    for feature in registry["features"]:
        files = []
        for full in feature_paths(feature):
            in_base = git(repo, "cat-file", "-e", f"{base}:{full}").returncode == 0
            changed = in_base and git(repo, "diff", "--quiet", base, "--", full).returncode != 0
            files.append({"path": full, "state": "changed" if changed else ("same" if in_base else "new"),
                          "owners": owners[full]})
        for full in (resolve(p) for p in feature.get("removed_files", [])):
            files.append({"path": full, "state": "removed", "owners": owners[full]})
        result["features"][feature["id"]] = {
            "level": overlay["features"].get(feature["id"], {}).get("level"),
            "depends": feature.get("depends", []),
            "files": files,
        }
    changed = git(repo, "diff", "--name-only", base, "HEAD").stdout.split()
    skipped = policy.get("not_features", {})
    result["unowned_changes"] = sorted(p for p in changed if p not in owners and p not in skipped
                                       and not p.startswith((".github/", "docs/", "portkit/"))
                                       and p not in ("CHANGELOG.md", "README.md"))
    return result


def inventory_markdown(data):
    lines = [f"# PortKit inventory against upstream `{data['base'][:8]}`", "",
             "Generated by `portkit/portkit.py inventory`. `new`: the file does not exist upstream; "
             "`changed`: Plus changes an upstream file; `same`: identical to upstream.", "",
             "| Feature | Level | Depends | Files (new / changed / same / removed) | Shared files |", "|---|---|---|---|---|"]
    for fid, entry in data["features"].items():
        states = [f["state"] for f in entry["files"]]
        shared = [f["path"].rsplit("/", 1)[-1] for f in entry["files"] if len(f["owners"]) > 1]
        lines.append(f"| {fid} | {entry['level']} | {', '.join(entry['depends']) or '—'} | "
                     f"{states.count('new')} / {states.count('changed')} / {states.count('same')} / {states.count('removed')} | "
                     f"{', '.join(shared) or '—'} |")
    lines += ["", f"## Plus changes owned by no feature ({len(data['unowned_changes'])})", "",
              "Changed against upstream but listed in no feature, so PortKit cannot transfer them until "
              "the registry assigns them (tests and shared resources are the usual case):", ""]
    lines += [f"- `{p}`" for p in data["unowned_changes"]] or ["- none"]
    return "\n".join(lines) + "\n"


def main(argv=None):
    parser = argparse.ArgumentParser(prog="portkit")
    sub = parser.add_subparsers(dest="command", required=True)
    check = sub.add_parser("validate", help="registry, overlay and policy are consistent")
    check.add_argument("--repo", default=".")
    inv = sub.add_parser("inventory", help="what each feature changes against the upstream base")
    inv.add_argument("--repo", default=".")
    inv.add_argument("--base", help="upstream commit (default: compatibility.json upstream_base)")
    inv.add_argument("--markdown", help="also write a Markdown report here")
    args = parser.parse_args(argv)

    if args.command == "validate":
        problems = validate(args.repo)
        for problem in problems:
            print(f"::error::{problem}")
        if not problems:
            registry, overlay, _ = load(args.repo)
            print(f"PortKit: {len(registry['features'])} features consistent "
                  f"({', '.join(f'{sum(1 for e in overlay['features'].values() if e['level'] == lv)} {lv}' for lv in LEVELS)}).")
        return 1 if problems else 0
    if args.command == "inventory":
        _, overlay, _ = load(args.repo)
        data = inventory(args.repo, args.base or overlay["upstream_base"]["commit"])
        print(json.dumps(data, indent=2))
        if args.markdown:
            Path(args.markdown).write_text(inventory_markdown(data))
        return 0
    return 2


if __name__ == "__main__":
    sys.exit(main())
