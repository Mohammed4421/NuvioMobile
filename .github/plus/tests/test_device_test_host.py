"""The device test host comes from the APKs, and the update check's arguments survive adb shell."""
import importlib.util
import os
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

PLUS = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("apk_instrumentation", PLUS / "apk-instrumentation.py")
apk_instrumentation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(apk_instrumentation)

# What aapt2 dump xmltree prints for the debug test APK (application id com.nuviodebug.com).
TEST_MANIFEST = """N: android=http://schemas.android.com/apk/res/android (line=2)
  E: manifest (line=2)
    A: http://schemas.android.com/apk/res/android:versionCode(0x0101021b)=1
    A: package="com.nuviodebug.com.test" (Raw: "com.nuviodebug.com.test")
    E: uses-sdk (line=5)
      A: http://schemas.android.com/apk/res/android:minSdkVersion(0x0101020c)=26
    E: instrumentation (line=11)
      A: http://schemas.android.com/apk/res/android:label(0x01010001)="Tests for com.nuviodebug.com" (Raw: "Tests for com.nuviodebug.com")
      A: http://schemas.android.com/apk/res/android:name(0x01010003)="androidx.test.runner.AndroidJUnitRunner" (Raw: "androidx.test.runner.AndroidJUnitRunner")
      A: http://schemas.android.com/apk/res/android:targetPackage(0x01010021)="com.nuviodebug.com" (Raw: "com.nuviodebug.com")
    E: application (line=17)
      A: http://schemas.android.com/apk/res/android:debuggable(0x0101000f)=true
      E: activity (line=20)
        A: http://schemas.android.com/apk/res/android:name(0x01010003)="androidx.test.core.app.InstrumentationActivityInvoker$BootstrapActivity" (Raw: "x")
"""


class InstrumentationFromManifest(unittest.TestCase):
    def test_the_host_and_runner_are_read_from_the_test_apk(self):
        self.assertEqual(
            ("com.nuviodebug.com.test", "androidx.test.runner.AndroidJUnitRunner", "com.nuviodebug.com"),
            apk_instrumentation.parse(TEST_MANIFEST),
        )

    def test_an_activity_name_is_not_taken_for_the_runner(self):
        _, runner, _ = apk_instrumentation.parse(TEST_MANIFEST)
        self.assertNotIn("Activity", runner)

    def test_a_manifest_without_instrumentation_is_refused(self):
        without = "\n".join(l for l in TEST_MANIFEST.splitlines() if "instrumentation" not in l and "0x01010021" not in l)
        with self.assertRaises(ValueError):
            apk_instrumentation.parse(without.replace("E: instrumentation", ""))

    def test_no_script_names_the_old_debug_package(self):
        for script in ("update-device-check.sh", "update-check-device-test.sh", "release-upgrade-test.sh"):
            text = (PLUS / script).read_text()
            for line in text.splitlines():
                self.assertNotRegex(line, r"com\.nuvio\.app(?![.\w])(\.test)?\b(?!\.plus)", f"{script}: {line}")


FAKE_ADB = r"""#!/usr/bin/env bash
# adb shell joins its arguments and gives them to the device's sh, like the real one.
case "$1" in
  shell)
    shift
    if [[ "$1" == run-as ]]; then
      [[ "$*" == *" stat "* ]] && cat "$ARGS_OUT.size" 2>/dev/null
      exit 0
    fi
    am() { printf '%s\n' "$@" > "$ARGS_OUT"; echo "OK (1 test)"; }
    pm() { :; }
    export -f am pm
    exec bash -c "$*" ;;
  exec-in) wc -c | tr -d ' ' > "$ARGS_OUT.size" ;;
  logcat)
    if [[ "$2" == -d ]]; then
      path="$(grep -A1 '^apk$' "$ARGS_OUT" | tail -n 1)"
      echo "I UpdateArchiveDeviceTest: verdict=Unreadable expect=x apk=${path}"
    fi ;;
esac
"""


class ArgumentsThroughAdbShell(unittest.TestCase):
    def test_a_list_of_allowed_verdicts_reaches_the_test_as_one_argument(self):
        with tempfile.TemporaryDirectory() as tmp:
            adb = Path(tmp, "adb")
            adb.write_text(FAKE_ADB)
            adb.chmod(adb.stat().st_mode | stat.S_IEXEC)
            apk = Path(tmp, "x.apk")
            apk.write_bytes(b"PK")
            args_out = Path(tmp, "args")
            env = dict(os.environ, PATH=f"{tmp}:{os.environ['PATH']}", ARGS_OUT=str(args_out),
                       NUVIO_TEST_HOST="com.nuviodebug.com", NUVIO_COPY_WAIT="2",
                       NUVIO_TEST_RUNNER="com.nuviodebug.com.test/androidx.test.runner.AndroidJUnitRunner")
            result = subprocess.run(["bash", str(PLUS / "update-device-check.sh"), str(apk), "Unreadable|Unsigned", "damaged"],
                                    env=env, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            received = args_out.read_text().splitlines()
            self.assertEqual("Unreadable|Unsigned", received[received.index("expect") + 1])
            self.assertEqual("com.nuviodebug.com.test/androidx.test.runner.AndroidJUnitRunner", received[-1])
            self.assertTrue(received[received.index("apk") + 1].startswith("/data/data/com.nuviodebug.com/files/update-check/"))
            self.assertIn("damaged: Unreadable", result.stdout)

    def test_an_incomplete_copy_gives_no_verdict(self):
        with tempfile.TemporaryDirectory() as tmp:
            adb = Path(tmp, "adb")
            adb.write_text(FAKE_ADB.replace('wc -c | tr -d \' \' > "$ARGS_OUT.size"', 'head -c 1 > /dev/null; echo 1 > "$ARGS_OUT.size"'))
            adb.chmod(adb.stat().st_mode | stat.S_IEXEC)
            apk = Path(tmp, "x.apk")
            apk.write_bytes(b"PK" * 100)
            env = dict(os.environ, PATH=f"{tmp}:{os.environ['PATH']}", ARGS_OUT=str(Path(tmp, "args")),
                       NUVIO_TEST_HOST="com.nuviodebug.com", NUVIO_COPY_WAIT="2",
                       NUVIO_TEST_RUNNER="com.nuviodebug.com.test/androidx.test.runner.AndroidJUnitRunner")
            result = subprocess.run(["bash", str(PLUS / "update-device-check.sh"), str(apk), "accepted", "cut"],
                                    env=env, capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("not copied completely", result.stdout)
            self.assertFalse(Path(tmp, "args").exists(), "the test ran on a partial file")

    def test_without_the_host_the_check_refuses_to_guess(self):
        result = subprocess.run(["bash", str(PLUS / "update-device-check.sh"), "x.apk", "accepted", "x"],
                                env={k: v for k, v in os.environ.items() if not k.startswith("NUVIO_TEST_")},
                                capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)


if __name__ == "__main__":
    unittest.main()
