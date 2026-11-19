"""Tests of release-verify.py: what must hold before a release APK is published.

APKs are made here: real ZIP files with an APK Signing Block holding made-up certificates (enough for
apk-certs.py to read), and apksigner is a stub that answers like apksigner for those files.
"""
import hashlib
import io
import os
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

PLUS = Path(__file__).resolve().parents[1]
SCRIPT = PLUS / "release-verify.py"
IDS = {"v2": 0x7109871A, "v3": 0xF05368C0}
OLD_CERT, NEW_CERT, OTHER_CERT = b"old-certificate", b"release-certificate", b"other-certificate"
OLD, NEW = hashlib.sha256(OLD_CERT).hexdigest(), hashlib.sha256(NEW_CERT).hexdigest()


def prefixed(data):
    return struct.pack("<I", len(data)) + data


def signing_block(signers):
    """signers: list of (scheme, certificate bytes)."""
    pairs = b""
    for scheme, certificate in signers:
        signed_data = prefixed(b"") + prefixed(prefixed(certificate))
        signer = prefixed(signed_data)
        value = prefixed(prefixed(signer))
        pairs += struct.pack("<Q", 4 + len(value)) + struct.pack("<I", IDS[scheme]) + value
    size = len(pairs) + 8 + 16
    return struct.pack("<Q", size) + pairs + struct.pack("<Q", size) + b"APK Sig Block 42"


def make_apk(path, contents, signers):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        for name, data in contents.items():
            archive.writestr(name, data)
    data = buffer.getvalue()
    eocd = data.rfind(b"PK\x05\x06")
    central = struct.unpack_from("<I", data, eocd + 16)[0]
    block = signing_block(signers)
    data = data[:central] + block + data[central:]
    eocd += len(block)
    data = data[:eocd + 16] + struct.pack("<I", central + len(block)) + data[eocd + 20:]
    Path(path).write_bytes(data)


STUB = r'''#!/usr/bin/env python3
import sys, os, json
args = sys.argv[1:]
lineages = json.load(open(os.environ["STUB_LINEAGES"]))
schemes = json.load(open(os.environ["STUB_SCHEMES"]))
if args[0] == "lineage":
    path = args[args.index("--in") + 1]
    for digest in lineages.get(os.path.basename(path), []):
        print(f"Signer #1 in lineage: certificate SHA-256 digest: {digest}")
    sys.exit(0 if os.path.basename(path) in lineages else 1)
if args[0] == "verify":
    path = args[-1]
    for scheme, on in schemes.get(os.path.basename(path), {"v3": True}).items():
        print(f"Verified using {scheme} scheme (APK Signature Scheme {scheme}): {'true' if on else 'false'}")
    sys.exit(0)
sys.exit(2)
'''

ABIS = ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"]


class ReleaseVerifyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        self.release, self.tested = root / "release", root / "tested"
        self.release.mkdir()
        self.tested.mkdir()
        tools = root / "sdk" / "build-tools" / "36.0.0"
        tools.mkdir(parents=True)
        (tools / "apksigner").write_text(STUB)
        (tools / "apksigner").chmod(0o755)
        self.lineage = root / "release-lineage.bin"
        self.lineage.write_bytes(b"lineage")
        self.lineages = {"release-lineage.bin": [OLD, NEW]}
        self.schemes = {}
        self.env = dict(os.environ, ANDROID_HOME=str(root / "sdk"),
                        STUB_LINEAGES=str(root / "lineages.json"), STUB_SCHEMES=str(root / "schemes.json"))
        for abi in ABIS:
            name = f"NuvioPlus-build13-{abi}.apk"
            contents = {"classes.dex": f"code {abi}".encode(), "AndroidManifest.xml": b"manifest"}
            make_apk(self.release / name, contents, [("v3", NEW_CERT)])
            make_apk(self.tested / name, contents, [("v2", OLD_CERT)])
            self.lineages[name] = [OLD, NEW]

    def tearDown(self):
        self.temp.cleanup()

    def manifest(self, directory, list_name):
        lines = [f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n" for p in sorted(directory.glob("*.apk"))]
        (directory / list_name).write_text("".join(lines))
        return hashlib.sha256((directory / list_name).read_bytes()).hexdigest()

    def run_verify(self, identity=NEW, old=OLD):
        import json
        Path(self.env["STUB_LINEAGES"]).write_text(json.dumps(self.lineages))
        Path(self.env["STUB_SCHEMES"]).write_text(json.dumps(self.schemes))
        release_manifest = self.manifest(self.release, "release.sha256")
        tested_manifest = self.manifest(self.tested, "signed.sha256")
        return subprocess.run([sys.executable, "-I", str(SCRIPT), "--release", str(self.release), "--tested", str(self.tested),
                               "--release-manifest", release_manifest, "--tested-manifest", tested_manifest,
                               "--identity", identity, "--old", old],
                              env=self.env, capture_output=True, text=True)

    def test_a_correct_release_passes(self):
        result = self.run_verify()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("v3 only", result.stdout)

    def test_the_public_key_is_never_the_release_identity(self):
        self.assertNotEqual(0, self.run_verify(identity=OLD).returncode)

    def test_a_v2_signature_is_refused(self):
        name = "NuvioPlus-build13-x86.apk"
        make_apk(self.release / name, {"classes.dex": b"code x86", "AndroidManifest.xml": b"manifest"},
                 [("v2", OLD_CERT), ("v3", NEW_CERT)])
        result = self.run_verify()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("not only the release key in v3", result.stdout)

    def test_another_key_is_refused(self):
        make_apk(self.release / "NuvioPlus-build13-x86.apk", {"classes.dex": b"code x86", "AndroidManifest.xml": b"manifest"},
                 [("v3", OTHER_CERT)])
        self.assertNotEqual(0, self.run_verify().returncode)

    def test_a_wrong_lineage_is_refused(self):
        self.lineages["NuvioPlus-build13-arm64-v8a.apk"] = [OLD, hashlib.sha256(OTHER_CERT).hexdigest(), NEW]
        result = self.run_verify()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("lineage", result.stdout)

    def test_changed_contents_are_refused(self):
        make_apk(self.release / "NuvioPlus-build13-x86_64.apk", {"classes.dex": b"other code", "AndroidManifest.xml": b"manifest"},
                 [("v3", NEW_CERT)])
        result = self.run_verify()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("differs from the tested APK", result.stdout)

    def test_apksigner_reporting_v2_is_refused(self):
        self.schemes["NuvioPlus-build13-x86.apk"] = {"v2": True, "v3": True}
        self.assertNotEqual(0, self.run_verify().returncode)

    def test_a_list_that_is_not_the_signing_jobs_is_refused(self):
        import json
        Path(self.env["STUB_LINEAGES"]).write_text(json.dumps(self.lineages))
        Path(self.env["STUB_SCHEMES"]).write_text(json.dumps(self.schemes))
        self.manifest(self.release, "release.sha256")
        tested_manifest = self.manifest(self.tested, "signed.sha256")
        result = subprocess.run([sys.executable, "-I", str(SCRIPT), "--release", str(self.release), "--tested", str(self.tested),
                                 "--release-manifest", "ab" * 32, "--tested-manifest", tested_manifest,
                                 "--identity", NEW, "--old", OLD],
                                env=self.env, capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)


if __name__ == "__main__":
    unittest.main()
