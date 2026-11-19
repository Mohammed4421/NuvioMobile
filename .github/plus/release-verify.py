#!/usr/bin/env python3
"""Verifies the release APKs before anything is published (docs/plus/signing-migration.md).

For every release APK:
- it is in the digest list the release signing job recorded, and the list itself is that job's;
- `apksigner verify --min-sdk-version 28` passes, with APK Signature Scheme v3 only (no v1, no v2:
  nothing in it is signed with the old, public key);
- its one signing certificate is the pinned release identity;
- its v3 lineage is exactly old key -> release key, the same as signing/release-lineage.bin;
- its contents (every ZIP entry) equal the tested APK of the same ABI, the one the checks signed
  with the test key and tested; only the signature differs.
Prints a Markdown summary line; exits non-zero on the first failure.

Usage: release-verify.py --release <dir> --tested <dir> --release-manifest <sha256>
       --tested-manifest <sha256> --identity <sha256> --old <sha256> --lineage <file>
"""
import argparse
import glob
import hashlib
import os
import re
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import importlib.util  # noqa: E402

_spec = importlib.util.spec_from_file_location("apk_certs", os.path.join(HERE, "apk-certs.py"))
apk_certs = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(apk_certs)

DIGEST = re.compile(r"^[0-9a-f]{64}$")


class Failure(Exception):
    pass


def apksigner():
    home = os.environ.get("ANDROID_HOME", "")
    tools = sorted(glob.glob(os.path.join(home, "build-tools", "*")), key=lambda p: [int(x) if x.isdigit() else x for x in re.split(r"[.-]", os.path.basename(p))])
    if not tools:
        raise Failure("apksigner not found (ANDROID_HOME/build-tools)")
    return os.path.join(tools[-1], "apksigner")


def sha256_file(path):
    with open(path, "rb") as file:
        return hashlib.sha256(file.read()).hexdigest()


def check_manifest(directory, list_name, expected):
    listing = os.path.join(directory, list_name)
    if not DIGEST.match(expected or "") or sha256_file(listing) != expected:
        raise Failure(f"{listing} is not the digest list the job recorded")
    apks = {}
    with open(listing) as file:
        for line in file:
            digest, name = line.split()
            path = os.path.join(directory, name)
            if sha256_file(path) != digest:
                raise Failure(f"{name} does not match its recorded digest")
            apks[name] = path
    if len(apks) != 4 or set(apks) != {os.path.basename(p) for p in glob.glob(os.path.join(directory, "*.apk"))}:
        raise Failure(f"expected the 4 listed APKs in {directory}, found {sorted(apks)}")
    return apks


def lineage_certs(signer, path):
    out = subprocess.run([signer, "lineage", "--in", path, "--print-certs"], capture_output=True, text=True)
    if out.returncode != 0:
        raise Failure(f"apksigner lineage failed for {path}: {out.stderr.strip()[:300]}")
    return re.findall(r"certificate SHA-256 digest: ([0-9a-f]{64})", out.stdout)


def entries(path):
    with zipfile.ZipFile(path) as archive:
        return {info.filename: hashlib.sha256(archive.read(info)).hexdigest() for info in archive.infolist()}


def verify(args):
    for name, value in (("identity", args.identity), ("old", args.old)):
        if not DIGEST.match(value or ""):
            raise Failure(f"--{name} is not a SHA-256 digest")
    if args.identity == args.old:
        raise Failure("the release identity is the old, public key")
    signer = apksigner()
    release = check_manifest(args.release, "release.sha256", args.release_manifest)
    tested = check_manifest(args.tested, "signed.sha256", args.tested_manifest)
    expected_lineage = [args.old, args.identity]
    if lineage_certs(signer, args.lineage) != expected_lineage:
        raise Failure(f"{args.lineage} is not the lineage old key -> release key")
    for name, path in sorted(release.items()):
        out = subprocess.run([signer, "verify", "--min-sdk-version", "28", "--verbose", path], capture_output=True, text=True)
        if out.returncode != 0:
            raise Failure(f"apksigner verify failed for {name}: {(out.stdout + out.stderr).strip()[:300]}")
        schemes = dict(re.findall(r"Verified using (v[0-9.]+) scheme \([^)]*\): (true|false)", out.stdout))
        if schemes.get("v3") != "true" or schemes.get("v1") == "true" or schemes.get("v2") == "true":
            raise Failure(f"{name} must be signed with v3 only, got {schemes}")
        found = apk_certs.certificates(path)
        if {scheme for scheme, _ in found} - {"v3"} or {digest for _, digest in found} != {args.identity}:
            raise Failure(f"{name} carries {found}, not only the release key in v3")
        if lineage_certs(signer, path) != expected_lineage:
            raise Failure(f"{name} does not carry the lineage old key -> release key")
        if name not in tested:
            raise Failure(f"no tested APK named {name}")
        if entries(path) != entries(tested[name]):
            raise Failure(f"{name} differs from the tested APK beyond its signature")
    return (f"- Release signing: 4 APKs signed with APK Signature Scheme v3 only by `{args.identity[:8]}…`, "
            f"lineage `{args.old[:8]}…` → `{args.identity[:8]}…`, contents identical to the tested APKs.")


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    for option in ("--release", "--tested", "--release-manifest", "--tested-manifest", "--identity", "--old", "--lineage"):
        parser.add_argument(option, required=True)
    args = parser.parse_args(argv)
    try:
        print(verify(args))
    except (Failure, OSError, ValueError, zipfile.BadZipFile) as error:
        print(f"::error::{error}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
