"""S25-03 (secret scan): no private key, keystore or service token is committed, except the files
listed here with the reason they are public by design."""
import re
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
KEY_FILES = re.compile(r"\.(jks|keystore|p12|pfx|pem|key)$", re.I)
ALLOWED = {
    # The key of builds 8-12. It has been public in this repository since then and is treated as
    # compromised (docs/plus/signing-migration.md, key-rotation-study.md); it stays for the v3
    # lineage proof and for the tests that check builds signed with it are refused.
    "signing/nuvio-plus.p12": "old public key of builds 8-12",
    # Public root certificates bundled for TLS on old Android versions.
    "composeApp/src/androidMain/res/raw/isrg_root_x1.pem": "public root certificate",
    "composeApp/src/androidMain/res/raw/isrg_root_x2.pem": "public root certificate",
}
SECRETS = (
    r"-----BEGIN [A-Z ]*PRIVATE KEY-----",
    r"ghp_[A-Za-z0-9]{36}", r"gho_[A-Za-z0-9]{36}", r"github_pat_[A-Za-z0-9_]{40,}",
    r"AKIA[0-9A-Z]{16}", r"AIza[0-9A-Za-z_-]{35}", r"xox[baprs]-[A-Za-z0-9-]{10,}", r"sk_live_[A-Za-z0-9]{20,}",
)


def git(*args):
    return subprocess.run(["git", "-C", str(ROOT), *args], capture_output=True, text=True).stdout


class NoCommittedSecrets(unittest.TestCase):
    def test_only_the_listed_key_files_are_tracked(self):
        tracked = {f for f in git("ls-files").splitlines() if KEY_FILES.search(f)}
        self.assertEqual(sorted(ALLOWED), sorted(tracked), "key or keystore files tracked")
        for name in ("composeApp/src/androidMain/res/raw/isrg_root_x1.pem", "composeApp/src/androidMain/res/raw/isrg_root_x2.pem"):
            text = (ROOT / name).read_text(encoding="ascii")
            self.assertIn("BEGIN CERTIFICATE", text)
            self.assertNotIn("PRIVATE KEY", text)

    def test_no_private_key_or_token_in_tracked_text(self):
        found = git("grep", "-nIE", "|".join(SECRETS), "--", ".", ":!.github/plus/tests/test_no_committed_secrets.py")
        self.assertEqual("", found.strip(), "secret-looking content committed")


if __name__ == "__main__":
    unittest.main()
