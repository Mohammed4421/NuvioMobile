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
# Android 9 has no MediaStore.Downloads and the app may not write shared storage: SubtitleSettingsTest
# expects its font in Download there, put by this runner (the shell may write it).
sdk="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
font="/sdcard/Download/Subtitle test font.ttf"
if (( sdk < 29 )); then
  adb push "${here}/../../composeApp/src/commonMain/composeResources/font/jetbrains_sans_regular.ttf" "${font}" > /dev/null \
    || { echo "::error::${label}: could not put the test font in Download"; exit 1; }
fi
out="$(adb shell "am instrument -w -r -e class '${classes}' '${NUVIO_TEST_RUNNER}'" 2>&1 | tr -d '\r')"
(( sdk < 29 )) && adb shell "rm -f '${font}'" > /dev/null 2>&1
python3 -I "${here}/instrumentation-results.py" "${label}" <<< "${out}"
