"""Tests of apk-certs.py on synthetic APK Signing Blocks.

The real build 12 APK gives `v2 40335e31…f48a`, the certificate of signing/nuvio-plus.p12; these
tests pin the parsing without a 47 MB file.
"""
import hashlib
import importlib.util
import struct
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "apk-certs.py"
spec = importlib.util.spec_from_file_location("apk_certs", SCRIPT)
apk_certs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(apk_certs)

V2, V3 = 0x7109871A, 0xF05368C0


def lp(data):
    return struct.pack("<I", len(data)) + data


def signer(certificate):
    signed_data = lp(b"digests") + lp(lp(certificate))
    return lp(lp(signed_data) + lp(b"signatures") + lp(b"public key"))


def apk(pairs):
    """ZIP data + APK Signing Block + empty central directory + EOCD."""
    body = b"PK\x03\x04 local entries"
    pairs_bytes = b"".join(struct.pack("<Q", len(value) + 4) + struct.pack("<I", pid) + value for pid, value in pairs)
    size = len(pairs_bytes) + 8 + 16
    block = struct.pack("<Q", size) + pairs_bytes + struct.pack("<Q", size) + b"APK Sig Block 42"
    central_directory = len(body) + len(block)
    eocd = b"PK\x05\x06" + b"\0" * 12 + struct.pack("<I", central_directory) + b"\0\0"
    return body + block + eocd


class ApkCertsTest(unittest.TestCase):
    def write(self, data):
        file = tempfile.NamedTemporaryFile(suffix=".apk", delete=False)
        file.write(data)
        file.close()
        self.addCleanup(Path(file.name).unlink)
        return file.name

    def test_reads_the_v2_certificate(self):
        path = self.write(apk([(V2, lp(signer(b"cert-a")))]))
        self.assertEqual([("v2", hashlib.sha256(b"cert-a").hexdigest())], apk_certs.certificates(path))

    def test_reads_v2_and_v3(self):
        path = self.write(apk([(V2, lp(signer(b"a"))), (0x42726577, b"padding"), (V3, lp(signer(b"a")))]))
        self.assertEqual(["v2", "v3"], [s for s, _ in apk_certs.certificates(path)])

    def test_current_needs_exactly_one_certificate(self):
        same = self.write(apk([(V2, lp(signer(b"a"))), (V3, lp(signer(b"a")))]))
        self.assertEqual(0, apk_certs.main(["apk-certs.py", "--current", same]))
        rotated = self.write(apk([(V2, lp(signer(b"old"))), (V3, lp(signer(b"new")))]))
        self.assertEqual(1, apk_certs.main(["apk-certs.py", "--current", rotated]))
        two_signers = self.write(apk([(V2, lp(signer(b"a") + signer(b"b")))]))
        self.assertEqual(1, apk_certs.main(["apk-certs.py", "--current", two_signers]))

    def test_unsigned_or_damaged_files_fail(self):
        self.assertEqual(1, apk_certs.main(["apk-certs.py", "--current", self.write(b"not a zip")]))
        unsigned = b"PK\x03\x04" + b"PK\x05\x06" + b"\0" * 12 + struct.pack("<I", 4) + b"\0\0"
        self.assertEqual(1, apk_certs.main(["apk-certs.py", "--current", self.write(unsigned)]))
        self.assertEqual(1, apk_certs.main(["apk-certs.py", "--current", self.write(apk([(0x42726577, b"x")]))]))
        truncated = apk([(V2, lp(signer(b"a")))])
        broken = truncated.replace(lp(lp(b"a")), struct.pack("<I", 999) + lp(b"a"))
        self.assertEqual(1, apk_certs.main(["apk-certs.py", "--current", self.write(broken)]))


if __name__ == "__main__":
    unittest.main()
