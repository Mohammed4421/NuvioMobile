#!/usr/bin/env bash
# Runs Nuvio's own instrumented tests (androidApp/src/androidTest) on the emulator against the debug
# app built from this commit (Build 15, P3-F1: they existed but no workflow ran them). Prints each
# test's result and fails unless every listed test ran and passed; a test skipped by an assumption
# is reported as skipped, never as passed.
# BackgroundDownloadTest is not listed: it checks a fixture download whose hash and size the caller
# must pass (-e sha256, -e bytes); without them it only skips.
# Usage: nuvio-device-tests.sh <debug app apk> <debug test apk> <label>
set -uo pipefail
app="$1"; test_apk="$2"; label="$3"
here="$(cd "$(dirname "$0")" && pwd)"
classes="com.nuvio.android.SubtitlePlaybackTest,com.nuvio.android.SubtitleSettingsTest,com.nuvio.android.MpvSurfaceTest"
exec > >(tee -a "nuvio-device-tests-${label}.log") 2>&1
adb wait-for-device
host_env="$(bash "${here}/device-test-host.sh" "${app}" "${test_apk}")" || { echo "::error::${label}: the device test host could not be set up"; exit 1; }
eval "${host_env}"
sdk="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
if (( sdk < 29 )); then
  # SubtitleSettingsTest puts its font in Download through MediaStore.Downloads (Android 10+). On
  # Android 9 the system picker lists Download from the download manager's records, so no file a
  # test or the shell adds there appears without a storage permission the app does not have. Font
  # import on Android 9 stays NOT TESTED here (recorded in docs/plus/build15/journeys.md).
  classes="${classes/,com.nuvio.android.SubtitleSettingsTest/}"
  echo "${label}: SubtitleSettingsTest.importsPreviewsAndRemovesACustomFontThroughTheSystemPicker: NOT TESTED (needs Android 10+ to place a picker-visible file)"
fi
# Finish the app's ahead-of-time compilation before the tests: in one failed run (PR #51) the system
# compiled the app (dex2oat) during MpvSurfaceTest, and libmpv went quiet for 33 s after start-file.
# Installed apps on a phone are compiled before use, so this is not hiding an app delay.
adb shell cmd package compile -m speed -f "${NUVIO_TEST_HOST}" > /dev/null 2>&1 || true
adb shell cmd package compile -m speed -f "${NUVIO_TEST_RUNNER%%/*}" > /dev/null 2>&1 || true
adb logcat -c || true
out="$(adb shell "am instrument -w -r -e class '${classes}' '${NUVIO_TEST_RUNNER}'" 2>&1 | tr -d '\r')"
status=0
python3 -I "${here}/instrumentation-results.py" "${label}" <<< "${out}" || status=$?
# Every run: how many times libmpv was asked to open its source per test (a second open repeats the
# network request at start). Recorded to compare with the failed run, where it opened twice.
echo "${label}: libmpv source opens across the libmpv tests (one per test expected): $(adb logcat -d -s NuvioPlayerDiag:I | grep -c 'mpv_prepare_begin' || true)"
adb logcat -d -v time -s NuvioPlayerDiag:I TestRunner:I | grep -E "mpv_prepare_begin|mpv_prepare_skipped|started: " | sed "s/^/${label}: /" || true
if (( status != 0 )); then
  # Evidence for telling an app fault from the emulator: the player's and the system's lines.
  echo "::group::${label}: logcat after the failed tests (player, surface, activity, crashes)"
  adb logcat -d -v time | grep -iE "mpv|exoplayer|surface|nuvio|ActivityTaskManager|AndroidRuntime|DocumentsUI|ANR" | tail -n 300
  echo "::endgroup::"
fi
exit "${status}"
