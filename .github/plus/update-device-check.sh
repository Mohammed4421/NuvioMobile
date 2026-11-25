#!/usr/bin/env bash
# Asks the app's own update check, on the emulator's real Android, what it says about an APK as an
# update of the installed Nuvio Plus (UpdateArchiveDeviceTest, run by instrumentation in the debug
# app). The host and runner come from device-test-host.sh (NUVIO_TEST_HOST, NUVIO_TEST_RUNNER).
# Usage: update-device-check.sh <apk on this machine> <expected: accepted or Problem[|Problem]> <label>
# Prints "<label>: <verdict>" and exits non-zero when the verdict is not one of the expected.
set -uo pipefail
apk="$1"; expect="$2"; label="$3"
host="${NUVIO_TEST_HOST:?run device-test-host.sh first}"
runner="${NUVIO_TEST_RUNNER:?run device-test-host.sh first}"
safe='^[A-Za-z0-9._/|-]+$'
[[ "${expect}" =~ ${safe} && "${host}" =~ ${safe} && "${runner}" =~ ${safe} ]] \
  || { echo "::error::${label}: unexpected characters in the check's arguments"; exit 1; }
name="update-$(date +%s%N).apk"
adb shell run-as "${host}" mkdir -p files/update-check > /dev/null
# exec-in can return before the device has written the whole file (Build 14: 17 of 62 MB right after
# it), so wait for all of it; if it never arrives, copy through adb push and run-as cp instead.
size="$(stat -c %s "${apk}")"
copied_size() { adb shell run-as "${host}" stat -c %s "files/update-check/${name}" 2>/dev/null | tr -d '\r'; }
wait_for_copy() {
  local i
  for i in $(seq 1 "${NUVIO_COPY_WAIT:-60}"); do [[ "$(copied_size)" == "${size}" ]] && return 0; sleep 1; done
  return 1
}
# Bounded: on Android 9 exec-in once never returned (PR #129: 42 minutes until the job's limit),
# so the push fallback below was never reached. After 120 s it is given up and the fallback runs.
timeout 120 adb exec-in run-as "${host}" sh -c "cat > files/update-check/${name}" < "${apk}" > /dev/null 2>&1
if ! wait_for_copy; then
  echo "${label}: the direct copy stopped at $(copied_size) of ${size} bytes; copying through adb push"
  adb push "${apk}" "/data/local/tmp/${name}" > /dev/null \
    && adb shell run-as "${host}" cp "/data/local/tmp/${name}" "files/update-check/${name}" > /dev/null
  adb shell rm -f "/data/local/tmp/${name}" > /dev/null 2>&1 || true
  wait_for_copy || {
    echo "::error::${label}: the APK was not copied completely to the device ($(copied_size) of ${size} bytes)"
    exit 1
  }
fi
path="/data/data/${host}/files/update-check/${name}"
adb logcat -c > /dev/null 2>&1 || true
# adb shell joins its arguments into one command for the device's shell, where "|" would be a pipe:
# every value goes in single quotes (they contain none; checked above).
out="$(adb shell "am instrument -w -r -e class com.nuvio.android.UpdateArchiveDeviceTest \
-e installedPackage com.nuvio.app.plus -e apk '${path}' -e expect '${expect}' '${runner}'" 2>&1 | tr -d '\r')"
adb shell run-as "${host}" rm -f "files/update-check/${name}" > /dev/null 2>&1 || true
verdict="$(adb logcat -d -s UpdateArchiveDeviceTest:I | tr -d '\r' | sed -n "s/.*verdict=\([A-Za-z]*\) .*apk=${path//\//\\/}$/\1/p" | tail -n 1)"
detail="$(adb logcat -d -s UpdateArchiveDeviceTest:I | tr -d '\r' | sed -n 's/.*detail //p' | tail -n 1)"
echo "  ${label} (Android parser): ${detail:-no detail}"
if grep -q "^OK (1 test)" <<< "${out}" && [[ -n "${verdict}" ]]; then
  echo "${label}: ${verdict} (expected ${expect})"
  exit 0
fi
echo "::error::${label}: the app's update check said ${verdict:-nothing}, expected ${expect}"
grep -E "INSTRUMENTATION_|Error|Exception|assumption|OK \(|FAILURES" <<< "${out}" | head -n 8
adb logcat -d 2>/dev/null | tr -d '\r' | grep -iE "PackageParser|PackageManager.*(fail|error)|apk.*(invalid|corrupt)" | tail -n 10 | sed 's/^/  logcat: /'

exit 1
