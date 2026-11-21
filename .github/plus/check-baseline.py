#!/usr/bin/env python3
"""Build 15, P0-01/02/03/05: docs/plus/build15/baseline.json must state what its sources show.
Offline (needs the full Git history): the Plus commit exists; Kotlin, Compose Multiplatform, AGP,
compile/target/min SDK equal gradle/libs.versions.toml at that commit and Gradle equals its wrapper;
the library list names that commit and holds as many libraries as the baseline says.
Online (--online, GitHub REST through gh): the release tag points at that commit; every APK's size
and SHA-256 equal the release asset's; the v3 certificate of the published arm64-v8a APK equals the
release certificate. Prints each check; exit 1 on any mismatch."""
import hashlib
import importlib.util
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path

root = Path(__file__).resolve().parents[2]
REPO = "Mohammed4421/NuvioMobile"
problems = []


def check(what, expected, actual):
    ok = expected == actual
    print(f"{'ok ' if ok else 'BAD'} {what}: baseline {expected!r}, source {actual!r}")
    if not ok:
        problems.append(what)


def git_show(commit, path):
    return subprocess.run(["git", "-C", str(root), "show", f"{commit}:{path}"], check=True,
                          capture_output=True, text=True).stdout


def offline(b):
    commit = b["nuvio_plus"]["commit"]
    versions = dict(re.findall(r'^([\w-]+)\s*=\s*"([^"]+)"', git_show(commit, "gradle/libs.versions.toml"), re.M))
    t = b["toolchain"]
    check("kotlin", t["kotlin"], versions.get("kotlin"))
    check("compose_multiplatform", t["compose_multiplatform"], versions.get("composeMultiplatform"))
    check("agp", t["agp"], versions.get("agp"))
    check("compileSdk", t["compileSdk"], f"{versions.get('android-compileSdk')}.{versions.get('android-compileSdkMinor')}")
    check("targetSdk", str(t["targetSdk"]), versions.get("android-targetSdk"))
    check("minSdk_source", str(t["minSdk_source"]), versions.get("android-minSdk"))
    wrapper = re.search(r"gradle-([\d.]+)-(?:bin|all)\.zip", git_show(commit, "gradle/wrapper/gradle-wrapper.properties"))
    check("gradle", t["gradle"], wrapper.group(1) if wrapper else None)
    libs = (root / b["reproducibility"]["libraries"]).read_text(encoding="utf-8").splitlines()
    check("library list commit", True, any(commit in line for line in libs if line.startswith("#")))
    count = sum(1 for line in libs if line.strip() and not line.startswith("#"))
    stated = int(re.search(r"(\d+) resolved release libraries", b["reproducibility"]["status"]).group(1))
    check("library count", stated, count)


def gh(path, accept=None):
    cmd = ["gh", "api", path] + (["-H", f"Accept: {accept}"] if accept else [])
    return subprocess.run(cmd, check=True, capture_output=True).stdout


def online(b):
    release = json.loads(gh(f"repos/{REPO}/releases/tags/{b['nuvio_plus']['tag']}"))
    check("release commit", b["nuvio_plus"]["commit"], release["target_commitish"])
    check("published", b["nuvio_plus"]["published"], release["published_at"][:10])
    assets = {a["name"]: a for a in release["assets"]}
    for abi, size in b["apk_sizes_bytes"].items():
        name = f"NuvioPlus-build{b['nuvio_plus']['build']}-{abi}.apk"
        check(f"{abi} size", size, assets.get(name, {}).get("size"))
        check(f"{abi} sha256", b["apk_sha256"].get(name), (assets.get(name, {}).get("digest") or "").removeprefix("sha256:"))
    name = f"NuvioPlus-build{b['nuvio_plus']['build']}-arm64-v8a.apk"
    data = gh(f"repos/{REPO}/releases/assets/{assets[name]['id']}", "application/octet-stream")
    check("downloaded arm64-v8a sha256", b["apk_sha256"][name], hashlib.sha256(data).hexdigest())
    spec = importlib.util.spec_from_file_location("apk_certs", root / ".github/plus/apk-certs.py")
    certs = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(certs)
    with tempfile.NamedTemporaryFile(suffix=".apk") as tmp:
        tmp.write(data)
        tmp.flush()
        found = certs.certificates(tmp.name)
    v3 = sorted({digest for scheme, digest in found if scheme == "v3"})
    check("v3 certificate", [b["identity"]["release_certificate_sha256"]], v3)


def main():
    b = json.loads((root / "docs/plus/build15/baseline.json").read_text(encoding="utf-8"))
    offline(b)
    if "--online" in sys.argv:
        online(b)
    for p in problems:
        print(f"::error::baseline.json does not match its source: {p}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
