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
adb exec-in run-as "${host}" sh -c "cat > files/update-check/${name}" < "${apk}" \
  || { echo "::error::${label}: could not copy the APK for the check"; exit 1; }
path="/data/data/${host}/files/update-check/${name}"
adb logcat -c > /dev/null 2>&1 || true
# adb shell joins its arguments into one command for the device's shell, where "|" would be a pipe:
# every value goes in single quotes (they contain none; checked above).
out="$(adb shell "am instrument -w -r -e class com.nuvio.android.UpdateArchiveDeviceTest \
-e installedPackage com.nuvio.app.plus -e apk '${path}' -e expect '${expect}' '${runner}'" 2>&1 | tr -d '\r')"
adb shell run-as "${host}" rm -f "files/update-check/${name}" > /dev/null 2>&1 || true
verdict="$(adb logcat -d -s UpdateArchiveDeviceTest:I | tr -d '\r' | sed -n "s/.*verdict=\([A-Za-z]*\) .*apk=${path//\//\\/}$/\1/p" | tail -n 1)"
if grep -q "^OK (1 test)" <<< "${out}" && [[ -n "${verdict}" ]]; then
  echo "${label}: ${verdict} (expected ${expect})"
  exit 0
fi
echo "::error::${label}: the app's update check said ${verdict:-nothing}, expected ${expect}"
grep -E "INSTRUMENTATION_|Error|Exception|assumption|OK \(|FAILURES" <<< "${out}" | head -n 8
exit 1
