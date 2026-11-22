#!/usr/bin/env python3
"""PortKit: moves Nuvio Plus features into a compatible Nuvio project, safely.

This file holds the checks every later step relies on. Standard library only.

  portkit.py validate  [--repo DIR]                 registry, overlay and policy are consistent
  portkit.py inventory [--repo DIR] [--base SHA]    what each feature changes against upstream
  portkit.py plan --target DIR FEATURE...           dry run: what a transfer would do, changes nothing
  portkit.py apply --target REPO FEATURE...         transfer on a new branch of the target (worktree)
  portkit.py rollback --target REPO --branch B      undo PortKit's own changes on that branch

The feature registry is .github/plus/features.json (one registry, also used by the upstream
review); portkit/compatibility.json adds PortKit's level for each feature and the owners of files
that more than one feature changes; portkit/policy.json lists what is never transferred.
Exit status: 0 when consistent, 1 with one line per problem otherwise.
"""
import argparse
import difflib
import hashlib
import shutil
import tempfile
import fnmatch
import json
import os
import re
import urllib.error
import urllib.request
import subprocess
import sys
from pathlib import Path

LEVELS = ("portable", "adapter-required", "manual", "unsupported")
RUN_URL = re.compile(r"^https://github\.com/Mohammed4421/NuvioMobile/actions/runs/([0-9]+)$")
TRANSFER_WORKFLOW = "PortKit transfer tests"
# What a transfer test exercises besides the feature's own files: if any of it changed since the
# tested commit, the evidence no longer covers the current code.
TRANSFER_MACHINERY = ["portkit/portkit.py", "portkit/transfer-test.sh", "portkit/check-test-classes.py",
                      "portkit/fixtures", "portkit/policy.json", ".github/workflows/portkit.yml"]
# Registry paths are relative to composeApp/src unless they start with a top-level folder.
# PortKit's own version; the Build 15 release scope (docs/plus/build15/release-scope.json) pins it.
VERSION = "1.0.0"

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
        if entry.get("verified") is True:
            evidence = entry.get("evidence") if isinstance(entry.get("evidence"), dict) else {}
            if not RUN_URL.match(str(evidence.get("run", ""))) or not re.fullmatch(r"[0-9a-f]{40}", str(evidence.get("commit", ""))):
                problems.append(f"compatibility: {fid} is verified without 'evidence' "
                                "({run: transfer-test run URL of this repository, commit: the commit it tested})")
            if entry.get("level") == "unsupported":
                problems.append(f"compatibility: {fid} is unsupported and cannot be verified")

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


# ---------------------------------------------------------------------------------------------
# Planning (dry run). Reads the source repository's Git objects and the target's files; never
# writes to the target.

def blob(repo, ref, path):
    """Bytes of path at ref in the source repository, or None when it does not exist there."""
    result = subprocess.run(["git", "-C", str(repo), "show", f"{ref}:{path}"], capture_output=True)
    return result.stdout if result.returncode == 0 else None


def read(path):
    return path.read_bytes() if path.is_file() else None


def sha256(data):
    return hashlib.sha256(data).hexdigest() if data is not None else None


def apply_patch_in_scratch(path, current, base, plus):
    """Applies the base->plus change of one file to `current` (the target's version) in a scratch
    directory, by context (like git apply). Returns the result, or None when it does not apply."""
    with tempfile.TemporaryDirectory() as scratch:
        scratch = Path(scratch)
        for side, data in (("a", base), ("b", plus)):
            (scratch / side / path).parent.mkdir(parents=True, exist_ok=True)
            (scratch / side / path).write_bytes(data)
        diff = subprocess.run(["git", "diff", "--no-index", "--no-color", "--binary", f"a/{path}", f"b/{path}"],
                              cwd=scratch, capture_output=True)
        patch = diff.stdout.replace(f"a/a/{path}".encode(), f"a/{path}".encode()).replace(
            f"b/b/{path}".encode(), f"b/{path}".encode())
        work = scratch / "work"
        (work / path).parent.mkdir(parents=True, exist_ok=True)
        (work / path).write_bytes(current)
        (scratch / "change.patch").write_bytes(patch)
        applied = subprocess.run(["git", "apply", "--whitespace=nowarn", str(scratch / "change.patch")],
                                 cwd=work, capture_output=True)
        return (work / path).read_bytes() if applied.returncode == 0 else None


def check_target(target, overlay):
    """Problems that make the target unsupported as a whole (it is not a Nuvio project PortKit knows)."""
    contract = overlay.get("target_contract", {})
    problems = []
    for path in contract.get("required_paths", []):
        if not (Path(target) / path).exists():
            problems.append(f"target has no {path}")
    for path, needle in contract.get("required_text", {}).items():
        data = read(Path(target) / path)
        if data is None or needle.encode() not in data:
            problems.append(f"target {path} does not contain {needle!r}")
    return problems


def production_refusal(fid, entry):
    """Why a feature may not be transferred for real (None when it may): only portable features whose
    transfer test passed (verified, with evidence) are transferred outside a trial."""
    if entry.get("level") != "portable":
        return f"{fid} is {entry.get('level')}: only portable features are transferred; use a trial to test it"
    if not entry.get("verified"):
        return f"{fid} is not verified (no passing transfer test yet); use a trial to test it"
    return None


def plan(source, target, wanted, source_ref="HEAD", trial=False):
    """What transferring `wanted` (and their dependencies) into `target` would do.

    Outside a trial only portable, verified features are planned. A trial (CI transfer tests,
    isolated branches) may plan any feature that is not unsupported; its result is not a transfer
    to keep.

    Every action is one of: create, patch-exact (the target file is the upstream file),
    patch-context (the target changed the file but the change still applies), delete, already
    (the target already has the Plus result) or conflict. Nothing is written."""
    source, target = Path(source), Path(target)
    registry, overlay, policy = load(source)
    base = overlay["upstream_base"]["commit"]
    by_id = {f["id"]: f for f in registry["features"]}
    levels = overlay["features"]
    report = {"source_ref": subprocess.run(["git", "-C", str(source), "rev-parse", source_ref],
                                           capture_output=True, text=True).stdout.strip(),
              "upstream_base": base, "target": str(target), "requested": list(wanted),
              "refused": [], "features": {}, "status": "ready", "trial": bool(trial)}

    target_problems = check_target(target, overlay)
    if target_problems:
        report["status"] = "unsupported-target"
        report["refused"] = target_problems
        return report
    try:
        order = dependency_order(registry["features"], wanted)
    except ValueError as error:
        report["status"] = "refused"
        report["refused"].append(str(error))
        return report
    for fid in order:
        if levels.get(fid, {}).get("level") == "unsupported":
            report["refused"].append(f"{fid} is unsupported: {levels[fid]['reason']}")
        elif not trial:
            why = production_refusal(fid, levels.get(fid, {}))
            if why:
                report["refused"].append(why)
    shared = overlay.get("shared_files", {})
    for path, entry in shared.items():
        owners = entry["owners"]
        chosen = [o for o in owners if o in order]
        if chosen and len(chosen) != len(owners):
            missing = [o for o in owners if o not in order]
            report["refused"].append(f"{path} is changed by {', '.join(owners)} together; add {', '.join(missing)}")
    if report["refused"]:
        report["status"] = "refused"
        return report

    patterns = policy.get("forbidden", [])
    for fid in order:
        feature = by_id[fid]
        actions = []
        targets = [(p, "keep") for p in feature_paths(feature)] + \
                  [(resolve(p), "remove") for p in feature.get("removed_files", [])]
        for path, kind in targets:
            hit = forbidden_match(path, patterns)
            if hit:
                actions.append({"path": path, "action": "conflict", "why": f"forbidden by {hit}"})
                continue
            base_data, plus_data = blob(source, base, path), blob(source, source_ref, path)
            current = read(target / path)
            action = {"path": path, "upstream": sha256(base_data), "plus": sha256(plus_data), "target": sha256(current)}
            if kind == "remove":
                if current is None:
                    action["action"] = "already"
                elif current == base_data:
                    action["action"] = "delete"
                else:
                    action.update(action="conflict", why="the target changed a file this feature deletes")
            elif plus_data is None:
                action.update(action="conflict", why="the source has no such file")
            elif current == plus_data:
                action["action"] = "already"
            elif base_data is None:
                if current is None:
                    action["action"] = "create"
                else:
                    action.update(action="conflict", why="the target has its own file at this path")
            elif current is None:
                action.update(action="conflict", why="the upstream file this feature changes is missing in the target")
            elif current == base_data:
                action["action"] = "patch-exact"
            else:
                result = apply_patch_in_scratch(path, current, base_data, plus_data)
                if result is None:
                    action.update(action="conflict", why="the target changed this file where the feature changes it")
                else:
                    action["action"] = "patch-context"
                    action["result"] = sha256(result)
            actions.append(action)
        conflicts = [a for a in actions if a["action"] == "conflict"]
        status = "conflict" if conflicts else ("already" if all(a["action"] == "already" for a in actions) else "ready")
        report["features"][fid] = {"level": levels[fid]["level"], "status": status, "actions": actions}
    if any(f["status"] == "conflict" for f in report["features"].values()):
        report["status"] = "conflict"
    elif all(f["status"] == "already" for f in report["features"].values()):
        report["status"] = "already"
    return report


def planned_content(source, target, report, action):
    """The bytes the target file would have after the action (None: deleted or unchanged)."""
    path, kind = action["path"], action["action"]
    if kind in ("create", "patch-exact"):
        return blob(source, report["source_ref"], path)
    if kind == "patch-context":
        _, overlay, _ = load(source)
        base = overlay["upstream_base"]["commit"]
        return apply_patch_in_scratch(path, read(Path(target) / path), blob(source, base, path),
                                      blob(source, report["source_ref"], path))
    return None


def plan_diff(source, target, report):
    """Unified diff of everything the plan would change in the target."""
    out = []
    for feature in report["features"].values():
        for action in feature["actions"]:
            if action["action"] not in ("create", "patch-exact", "patch-context", "delete"):
                continue
            before = read(Path(target) / action["path"]) or b""
            after = b"" if action["action"] == "delete" else planned_content(source, target, report, action)
            out += difflib.unified_diff(before.decode("utf-8", "replace").splitlines(keepends=True),
                                        after.decode("utf-8", "replace").splitlines(keepends=True),
                                        f"a/{action['path']}", f"b/{action['path']}")
    return "".join(out)


def plan_markdown(report):
    lines = [f"# PortKit plan ({report['status']}{', trial' if report.get('trial') else ''})", "",
             f"- Source: `{report['source_ref'][:12]}`, upstream base `{report['upstream_base'][:8]}`",
             f"- Target: `{report['target']}`", f"- Requested: {', '.join(report['requested'])}",
             "- `ready` means every change applies to the target's files; whether the result builds and passes",
             "  the feature's tests is proven only by a transfer test (`compatibility.json` `verified`).", ""]
    if report["refused"]:
        lines += ["## Refused", ""] + [f"- {r}" for r in report["refused"]] + [""]
    for fid, feature in report["features"].items():
        lines += [f"## {fid} ({feature['level']}): {feature['status']}", "", "| File | Action | Why |", "|---|---|---|"]
        lines += [f"| `{a['path']}` | {a['action']} | {a.get('why', '')} |" for a in feature["actions"]]
        lines.append("")
    return "\n".join(lines)


# ---------------------------------------------------------------------------------------------
# Applying. The target's checkout is never touched: PortKit adds a Git worktree on a new branch
# from the target's HEAD, plans against it, writes there, and commits with a ledger of what it
# wrote. If anything fails before the commit, the worktree and the branch it created are removed.

LEDGER = ".portkit/transfers.json"
TRAILER = "PortKit-Transfer"


class TransferError(Exception):
    pass


def run_git(directory, *args, check=True):
    result = subprocess.run(["git", "-C", str(directory), *args], capture_output=True, text=True)
    if check and result.returncode != 0:
        raise TransferError(f"git {' '.join(args)}: {result.stderr.strip()}")
    return result


TRIAL_PREFIX = "portkit-trial/"


def apply(source, target, wanted, branch, source_ref="HEAD", trial=False, from_head=False):
    """Transfers `wanted` onto a new `branch` of the target repository. Returns a result dict:
    status applied | already | refused | conflict | unsupported-target, the plan, and the commit.

    The transfer starts from the target's last commit. Uncommitted changes in the target are not
    part of it, so a target with any (staged, unstaged or untracked) is refused unless `from_head`
    says to use HEAD anyway. A trial (unverified features, for transfer tests) must use a branch
    under portkit-trial/ and is marked as a trial in the ledger and the commit."""
    source, target = Path(source).resolve(), Path(target).resolve()
    if run_git(target, "rev-parse", "--is-inside-work-tree", check=False).returncode != 0:
        raise TransferError(f"{target} is not a Git repository")
    if trial and not branch.startswith(TRIAL_PREFIX):
        raise TransferError(f"a trial transfer must use a branch under {TRIAL_PREFIX}")
    if not trial and branch.startswith(TRIAL_PREFIX):
        raise TransferError(f"{TRIAL_PREFIX} branches are for trials only")
    pending = run_git(target, "status", "--porcelain", "--untracked-files=normal").stdout.strip()
    if pending and not from_head:
        count = len(pending.splitlines())
        raise TransferError(f"the target has {count} uncommitted change(s); commit or stash them, "
                            "or pass --from-head to transfer onto the last commit without them")
    if run_git(target, "rev-parse", "--verify", "--quiet", f"refs/heads/{branch}", check=False).returncode == 0:
        raise TransferError(f"branch {branch} already exists in the target; choose another")
    head = run_git(target, "rev-parse", "HEAD").stdout.strip()
    work = Path(tempfile.mkdtemp(prefix="portkit-")) / "work"
    created = False
    try:
        run_git(target, "worktree", "add", "-q", "-b", branch, str(work), head)
        created = True
        report = plan(source, work, wanted, source_ref, trial=trial)
        if report["status"] != "ready":
            return {"status": report["status"], "plan": report, "branch": None, "commit": None}
        ledger_path = work / LEDGER
        ledger = json.loads(ledger_path.read_text()) if ledger_path.is_file() else {"transfers": []}
        entry = {"source": report["source_ref"], "upstream_base": report["upstream_base"], "target_head": head,
                 "trial": bool(trial), "uncommitted_left_out": bool(pending),
                 "features": list(report["features"]), "files": {}}
        # Compute every result before writing anything.
        writes = []
        for feature in report["features"].values():
            for action in feature["actions"]:
                kind = action["action"]
                if kind == "already":
                    continue
                path = work / action["path"]
                before = read(path)
                if sha256(before) != action["target"]:
                    raise TransferError(f"{action['path']} changed after planning")
                after = None if kind == "delete" else planned_content(source, work, report, action)
                if kind != "delete" and after is None:
                    raise TransferError(f"{action['path']} could not be computed")
                writes.append((action["path"], after))
                entry["files"][action["path"]] = {"action": kind, "before": sha256(before), "after": sha256(after)}
        for relative, after in writes:
            path = work / relative
            if after is None:
                path.unlink()
            else:
                path.parent.mkdir(parents=True, exist_ok=True)
                temporary = path.with_name(path.name + ".portkit-tmp")
                temporary.write_bytes(after)
                temporary.replace(path)
        ledger["transfers"].append(entry)
        ledger_path.parent.mkdir(parents=True, exist_ok=True)
        ledger_path.write_text(json.dumps(ledger, indent=2) + "\n")
        # Every written file must now hold exactly what was planned.
        for relative, info in entry["files"].items():
            if sha256(read(work / relative)) != info["after"]:
                raise TransferError(f"{relative} does not hold the planned content")
        run_git(work, "add", "-A")
        message = (f"PortKit{' trial' if trial else ''}: transfer {', '.join(entry['features'])} from Nuvio Plus\n\n"
                   f"Source {entry['source']} (upstream base {entry['upstream_base']}).\n"
                   f"Files: {len(entry['files'])}. Ledger: {LEDGER}.\n\n{TRAILER}: {entry['source']}")
        run_git(work, "-c", "user.name=PortKit", "-c", "user.email=portkit@localhost", "commit", "-q", "-m", message)
        commit = run_git(work, "rev-parse", "HEAD").stdout.strip()
        return {"status": "applied", "plan": report, "branch": branch, "commit": commit,
                "uncommitted_left_out": bool(pending)}
    except BaseException:
        if created:
            run_git(target, "worktree", "remove", "--force", str(work), check=False)
            run_git(target, "branch", "-D", branch, check=False)
            created = False
        raise
    finally:
        if created:
            run_git(target, "worktree", "remove", "--force", str(work), check=False)
            # Keep the branch only when it carries the transfer commit.
            if run_git(target, "rev-parse", branch, check=False).stdout.strip() == head:
                run_git(target, "branch", "-D", branch, check=False)
        shutil.rmtree(work.parent, ignore_errors=True)


def rollback(target, branch):
    """Undoes, on `branch` (as a new commit), the files of PortKit transfers that still hold exactly
    what PortKit wrote. Files changed since are kept and reported. Returns the result dict."""
    target = Path(target).resolve()
    if run_git(target, "rev-parse", "--verify", "--quiet", f"refs/heads/{branch}", check=False).returncode != 0:
        raise TransferError(f"the target has no branch {branch}")
    # A branch checked out in a worktree (the main checkout included) is not changed behind that
    # worktree's back: its files would no longer match its branch.
    listing = run_git(target, "worktree", "list", "--porcelain").stdout
    for block in listing.strip().split("\n\n"):
        fields = dict(line.split(" ", 1) for line in block.splitlines() if " " in line)
        if fields.get("branch") == f"refs/heads/{branch}":
            raise TransferError(f"{branch} is checked out at {fields.get('worktree')}; switch that worktree to "
                                "another branch first. Nothing was changed")
    work = Path(tempfile.mkdtemp(prefix="portkit-")) / "work"
    run_git(target, "worktree", "add", "-q", str(work), branch)
    try:
        commits = run_git(work, "log", "--format=%H", f"--grep=^{TRAILER}: ", branch).stdout.split()
        if not commits:
            return {"status": "nothing", "reverted": [], "kept": []}
        reverted, kept = [], []
        for commit in commits:  # newest first
            ledger = json.loads(run_git(work, "show", f"{commit}:{LEDGER}").stdout)
            entry = ledger["transfers"][-1]
            for relative, info in entry["files"].items():
                path = work / relative
                if sha256(read(path)) != info["after"]:
                    kept.append(relative)
                    continue
                previous = subprocess.run(["git", "-C", str(work), "show", f"{commit}^:{relative}"], capture_output=True)
                if info["before"] is None:
                    path.unlink(missing_ok=True)
                elif previous.returncode == 0 and sha256(previous.stdout) == info["before"]:
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(previous.stdout)
                else:
                    kept.append(relative)
                    continue
                reverted.append(relative)
        ledger_path = work / LEDGER
        if ledger_path.is_file() and not kept:
            ledger_path.unlink()
        if reverted:
            run_git(work, "add", "-A")
            run_git(work, "-c", "user.name=PortKit", "-c", "user.email=portkit@localhost", "commit", "-q", "-m",
                    f"PortKit: roll back {len(reverted)} file(s) PortKit wrote" +
                    (f"; kept {len(kept)} changed since" if kept else ""))
        return {"status": "rolled-back" if reverted else "nothing", "reverted": reverted, "kept": kept}
    finally:
        run_git(target, "worktree", "remove", "--force", str(work), check=False)
        shutil.rmtree(work.parent, ignore_errors=True)


# ---------------------------------------------------------------------------------------------
# Evidence. A verified feature names the transfer-test run that proved it and the commit that run
# tested. check_evidence asks GitHub that the run is the transfer-test workflow, succeeded and ran
# on that commit; that the workflow at that commit transferred the feature into every target; and
# that neither the feature's files nor PortKit's transfer machinery changed since.

def github_run(run_id, urlopen=urllib.request.urlopen, sleep=None):
    """The transfer-test run [run_id] as GitHub's API returns it.

    With a token (GH_TOKEN or GITHUB_TOKEN) it is read with that token first: a shared CI address
    reading anonymously ran out of requests ("rate limit exceeded", run 44783639944), and a token has
    its own, much larger allowance. A token that may not read Actions data (a caller that grants only
    `contents: read`) is answered 401, 403 or 404; the run is public, so it is then read without it.
    Rate limits and server errors are retried with growing waits; anything else is raised."""
    import time
    sleep = sleep or time.sleep
    url = f"https://api.github.com/repos/Mohammed4421/NuvioMobile/actions/runs/{run_id}"
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    for use_token in ([True, False] if token else [False]):
        for attempt in range(4):
            request = urllib.request.Request(url, headers={"Accept": "application/vnd.github+json"})
            if use_token:
                request.add_header("Authorization", f"Bearer {token}")
            try:
                with urlopen(request, timeout=30) as response:
                    return json.load(response)
            except urllib.error.HTTPError as error:
                limited = error.code == 429 or (error.code == 403 and (error.headers or {}).get("X-RateLimit-Remaining") == "0")
                if use_token and error.code in (401, 403, 404) and not limited:
                    break  # this token may not read Actions data: read the public run without it
                if not (limited or error.code >= 500) or attempt == 3:
                    raise
            except urllib.error.URLError:
                if attempt == 3:
                    raise
            sleep(10 * (attempt + 1))


def check_evidence(repo, fetch_run=github_run):
    """Problems with the evidence of every verified feature (empty when all of it holds)."""
    repo = Path(repo)
    registry, overlay, _ = load(repo)
    by_id = {f["id"]: f for f in registry["features"]}
    problems = []
    for fid, entry in overlay["features"].items():
        if not entry.get("verified"):
            continue
        evidence = entry.get("evidence") or {}
        match = RUN_URL.match(str(evidence.get("run", "")))
        commit = str(evidence.get("commit", ""))
        if not match or not re.fullmatch(r"[0-9a-f]{40}", commit):
            problems.append(f"{fid}: evidence needs a run URL of this repository and a commit")
            continue
        try:
            run = fetch_run(match.group(1))
        except Exception as error:  # network or API error: the evidence is not confirmed
            problems.append(f"{fid}: could not read run {match.group(1)} from GitHub ({error})")
            continue
        if run.get("name") != TRANSFER_WORKFLOW:
            problems.append(f"{fid}: run {match.group(1)} is '{run.get('name')}', not '{TRANSFER_WORKFLOW}'")
        if run.get("status") != "completed" or run.get("conclusion") != "success":
            problems.append(f"{fid}: run {match.group(1)} did not succeed ({run.get('status')}/{run.get('conclusion')})")
        if run.get("head_sha") != commit:
            problems.append(f"{fid}: run {match.group(1)} tested {run.get('head_sha')}, not {commit}")
        if git(repo, "cat-file", "-e", f"{commit}^{{commit}}").returncode != 0:
            git(repo, "fetch", "--no-tags", "--depth=1", "origin", commit)
        if git(repo, "cat-file", "-e", f"{commit}^{{commit}}").returncode != 0:
            problems.append(f"{fid}: commit {commit} is not available to compare")
            continue
        workflow = git(repo, "show", f"{commit}:.github/workflows/portkit.yml").stdout
        invocations = re.findall(r"transfer-test\.sh[^\n]*\n\s*([a-z0-9,-]+)", workflow)
        if not invocations or not all(fid in inv.split(",") for inv in invocations):
            problems.append(f"{fid}: the transfer-test workflow at {commit[:8]} does not transfer it into every target")
        paths = feature_paths(by_id[fid]) + [resolve(p) for p in by_id[fid].get("removed_files", [])]
        changed = git(repo, "diff", "--name-only", commit, "HEAD", "--", *paths, *TRANSFER_MACHINERY).stdout.split()
        if changed:
            problems.append(f"{fid}: changed since the tested commit {commit[:8]}: {', '.join(changed)}; run the transfer test again")
    return problems


def matrix_markdown(repo):
    """The compatibility matrix: one row per feature, from the registry and compatibility.json."""
    registry, overlay, _ = load(repo)
    by_id = {f["id"]: f for f in registry["features"]}
    shared = overlay.get("shared_files", {})
    lines = ["# PortKit compatibility matrix", "",
             f"Upstream base: Nuvio {overlay['upstream_base']['version']} (`{overlay['upstream_base']['commit'][:8]}`). "
             "Generated by `portkit.py matrix` from `.github/plus/features.json` and `portkit/compatibility.json`.", "",
             "| Feature | Level | Verified | Evidence | Depends on | Shared files | Why |", "|---|---|---|---|---|---|---|"]
    for fid, entry in overlay["features"].items():
        evidence = entry.get("evidence") or {}
        proof = f"[run]({evidence['run']}) on `{evidence['commit'][:8]}`" if entry.get("verified") else "—"
        files = [p.rsplit("/", 1)[-1] for p, e in shared.items() if fid in e["owners"]]
        lines.append(f"| {fid} | {entry['level']} | {'yes' if entry.get('verified') else 'no'} | {proof} | "
                     f"{', '.join(by_id[fid].get('depends', [])) or '—'} | {', '.join(files) or '—'} | {entry['reason']} |")
    lines += ["", "A real transfer (`apply` without `--trial`) takes only rows that are portable **and** verified."]
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
    planner = sub.add_parser("plan", help="dry run: what transferring features would do; changes nothing")
    planner.add_argument("--repo", default=".", help="the Nuvio Plus repository (source)")
    planner.add_argument("--source-ref", default="HEAD")
    planner.add_argument("--target", required=True, help="the Nuvio project to transfer into")
    planner.add_argument("--json", help="write the plan as JSON here")
    planner.add_argument("--diff", help="write the planned changes as a unified diff here")
    planner.add_argument("--trial", action="store_true",
                         help="plan unverified, adapter-required or manual features too (for transfer tests)")
    planner.add_argument("features", nargs="+")
    applier = sub.add_parser("apply", help="transfer features onto a new branch of the target repository")
    applier.add_argument("--repo", default=".", help="the Nuvio Plus repository (source)")
    applier.add_argument("--source-ref", default="HEAD")
    applier.add_argument("--target", required=True, help="the target Git repository (its checkout is not touched)")
    applier.add_argument("--branch", required=True, help="new branch to create in the target")
    applier.add_argument("--trial", action="store_true",
                         help=f"transfer unverified features for a test, on a {TRIAL_PREFIX} branch")
    applier.add_argument("--from-head", action="store_true",
                         help="transfer onto the last commit even though the target has uncommitted changes")
    applier.add_argument("features", nargs="+")
    sub.add_parser("matrix", help="print the compatibility matrix (Markdown)").add_argument("--repo", default=".")
    sub.add_parser("check-evidence", help="the evidence of every verified feature holds (asks GitHub)").add_argument(
        "--repo", default=".")
    undo = sub.add_parser("rollback", help="undo PortKit's own changes on a branch of the target")
    undo.add_argument("--target", required=True)
    undo.add_argument("--branch", required=True)
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
    if args.command == "plan":
        report = plan(args.repo, args.target, args.features, args.source_ref, trial=args.trial)
        print(plan_markdown(report))
        if args.json:
            Path(args.json).write_text(json.dumps(report, indent=2))
        if args.diff:
            Path(args.diff).write_text(plan_diff(args.repo, args.target, report))
        return {"ready": 0, "already": 0}.get(report["status"], 3)
    if args.command == "apply":
        try:
            result = apply(args.repo, args.target, args.features, args.branch, args.source_ref,
                           trial=args.trial, from_head=args.from_head)
        except TransferError as error:
            print(f"::error::{error}. Nothing was changed in the target.")
            return 4
        print(plan_markdown(result["plan"]))
        if result["status"] == "applied":
            print(f"Applied on branch {result['branch']} as {result['commit']}; the target's checkout is unchanged.")
            if result["uncommitted_left_out"]:
                print("Note: the target's uncommitted changes are not part of this branch (--from-head).")
            return 0
        print(f"Nothing applied ({result['status']}); no branch was left in the target.")
        return 0 if result["status"] == "already" else 3
    if args.command == "matrix":
        print(matrix_markdown(args.repo), end="")
        return 0
    if args.command == "check-evidence":
        problems = check_evidence(args.repo)
        for problem in problems:
            print(f"::error::{problem}")
        _, overlay, _ = load(args.repo)
        verified = [fid for fid, e in overlay["features"].items() if e.get("verified")]
        if not problems:
            print(f"PortKit evidence holds for {len(verified)} verified feature(s): {', '.join(verified) or 'none'}.")
        return 1 if problems else 0
    if args.command == "rollback":
        try:
            result = rollback(args.target, args.branch)
        except TransferError as error:
            print(f"::error::{error}")
            return 4
        print(json.dumps(result, indent=2))
        return 0
    return 2


if __name__ == "__main__":
    sys.exit(main())
