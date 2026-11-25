"""P2-01/P2-02 (upstream merge cost): Nuvio Plus never changes the line endings a file has upstream.

A file stored with CRLF where Nuvio stores LF (or the other way round) makes every line differ from
Nuvio's, so the next upstream merge conflicts on the whole file instead of on Plus's few lines.
This happened twice in Build 15/16: the iOS download implementation (#113) and ProfileRepository.kt,
where a fix of 79 lines showed as a rewrite of all 606. New Plus files use LF, like most of Nuvio.
"""
import json
import os
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]


def git(*args):
    return subprocess.run(["git", "-C", str(ROOT), *args], capture_output=True, check=True).stdout


def crlf_files(tree):
    """Text files of a tree (commit, or None for the index) stored with CRLF line endings."""
    if tree is None:
        listing = git("ls-files", "--eol").decode()
        return {line.split("\t", 1)[1] for line in listing.splitlines()
                if line.split()[0] in ("i/crlf", "i/mixed")}
    blobs = {}
    for entry in filter(None, git("ls-tree", "-r", "-z", tree).split(b"\0")):
        meta, name = entry.split(b"\t", 1)
        _, kind, sha = meta.split()
        if kind == b"blob":  # submodules (gitlinks) have no content here
            blobs[sha.decode()] = name.decode()
    out = subprocess.run(["git", "-C", str(ROOT), "cat-file", "--batch"], input="\n".join(blobs).encode() + b"\n",
                         capture_output=True, check=True).stdout
    found, pos = set(), 0
    while pos < len(out):
        header_end = out.index(b"\n", pos)
        sha, _, size = out[pos:header_end].split()
        start = header_end + 1
        blob = out[start:start + int(size)]
        pos = start + int(size) + 1
        if b"\0" not in blob[:8000] and b"\r\n" in blob:
            found.add(blobs[sha.decode()])
    return found


class LineEndings(unittest.TestCase):
    def test_plus_keeps_the_line_endings_of_upstream(self):
        base = json.loads((ROOT / "portkit/compatibility.json").read_text())["upstream_base"]["commit"]
        present = subprocess.run(["git", "-C", str(ROOT), "cat-file", "-e", f"{base}^{{commit}}"]).returncode == 0
        if not present:
            if os.environ.get("CI"):
                self.fail(f"the upstream base {base} was not fetched for this check")
            self.skipTest("upstream base not fetched")
        upstream = crlf_files(base)
        ours = crlf_files(None)
        upstream_files = set(filter(None, git("ls-tree", "-r", "-z", "--name-only", base).decode().split("\0")))
        # A CRLF file here must be CRLF upstream; an upstream CRLF file Plus still has must stay CRLF.
        self.assertEqual([], sorted(ours - upstream), "stored with CRLF here but not upstream (use LF)")
        tracked = set(filter(None, git("ls-files", "-z").decode().split("\0")))
        self.assertEqual([], sorted((upstream & tracked & upstream_files) - ours),
                         "CRLF upstream but converted to LF here")


if __name__ == "__main__":
    unittest.main()
