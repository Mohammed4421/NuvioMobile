"""Tests of lint-compare.py: moved findings are not new; new errors and security findings block."""
import importlib.util
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("lc", Path(__file__).resolve().parents[1] / "lint-compare.py")
lc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lc)


def report(root, *issues):
    body = "".join(f'<issue id="{i}" severity="{s}" category="{c}" message="{m}"><location file="{root}/{f}" line="{l}"/></issue>'
                   for i, s, c, m, f, l in issues)
    tmp = tempfile.NamedTemporaryFile("w", suffix=".xml", delete=False)
    tmp.write(f'<?xml version="1.0"?><issues format="6">{body}</issues>')
    tmp.close()
    return lc.findings(tmp.name, root)


OLD = ("UnusedResources", "Warning", "Performance", "R.string.x appears unused", "a/res.xml", 3)


class Compare(unittest.TestCase):
    def test_a_finding_that_moved_lines_is_not_new(self):
        base = report("/b14", OLD, ("NewApi", "Error", "Correctness", "Call requires API level 29", "A.kt", 10))
        head = report("/head", OLD, ("NewApi", "Error", "Correctness", "Call requires API level 29", "A.kt", 42))
        new, fixed, blocking = lc.compare(base, head)
        self.assertEqual(([], [], []), (new, fixed, blocking))

    def test_a_new_error_blocks(self):
        new, _, blocking = lc.compare(report("/b14", OLD), report("/h", OLD, ("NewApi", "Error", "Correctness", "Call requires API level 29", "B.kt", 1)))
        self.assertEqual(["NewApi"], [f["id"] for f in blocking])

    def test_a_new_security_warning_blocks(self):
        _, _, blocking = lc.compare(report("/b14"), report("/h", ("SetJavaScriptEnabled", "Warning", "Security", "js", "W.kt", 5)))
        self.assertEqual(1, len(blocking))

    def test_a_new_ordinary_warning_is_reported_but_does_not_block(self):
        new, _, blocking = lc.compare(report("/b14"), report("/h", OLD))
        self.assertEqual((1, 0), (len(new), len(blocking)))

    def test_a_second_copy_of_a_known_finding_is_new(self):
        new, _, _ = lc.compare(report("/b14", OLD), report("/h", OLD, OLD))
        self.assertEqual(1, len(new))

    def test_a_library_finding_in_a_new_cache_directory_is_not_new(self):
        jar = lambda h: ("TrustAllX509TrustManager", "Warning", "Security", "checkServerTrusted is empty",
                         f"/home/runner/.gradle/caches/9.4.1/transforms/{h}/transformed/out/jars/classes.jar", "")
        new, _, blocking = lc.compare(report("/b14", jar("2d6295ec")), report("/h", jar("be50dd54")))
        self.assertEqual(([], []), (new, blocking))

    def test_fixed_findings_are_listed(self):
        _, fixed, _ = lc.compare(report("/b14", OLD), report("/h"))
        self.assertEqual(1, len(fixed))


if __name__ == "__main__":
    unittest.main()
