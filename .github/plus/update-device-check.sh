#!/usr/bin/env bash
# Asks the app's own update check, on the emulator's real Android, what it says about an APK as an
# update of the installed Nuvio Plus (UpdateArchiveDeviceTest, run by instrumentation in the debug
# app com.nuvio.app, which must be installed together with its test APK).
# Usage: update-device-check.sh <apk on this machine> <expected: accepted or Problem[|Problem]> <label>
# Prints "<label>: <verdict>" and exits non-zero when the verdict is not one of the expected.
set -uo pipefail
apk="$1"; expect="$2"; label="$3"
host=com.nuvio.app
name="update-$(date +%s%N).apk"
adb shell run-as "${host}" mkdir -p files/update-check > /dev/null
adb exec-in run-as "${host}" sh -c "cat > files/update-check/${name}" < "${apk}" \
  || { echo "::error::${label}: could not copy the APK for the check"; exit 1; }
path="/data/data/${host}/files/update-check/${name}"
out="$(adb shell am instrument -w -r \
  -e class com.nuvio.android.UpdateArchiveDeviceTest \
  -e installedPackage com.nuvio.app.plus -e apk "${path}" -e expect "${expect}" \
  "${host}.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 | tr -d '\r')"
adb shell run-as "${host}" rm -f "files/update-check/${name}" > /dev/null 2>&1 || true
verdict="$(adb logcat -d -s UpdateArchiveDeviceTest:I | tr -d '\r' | sed -n "s/.*verdict=\([A-Za-z]*\) .*apk=${path//\//\\/}$/\1/p" | tail -n 1)"
if grep -q "^OK (1 test)" <<< "${out}"; then
  echo "${label}: ${verdict:-ok} (expected ${expect})"
  exit 0
fi
echo "::error::${label}: the app's update check said ${verdict:-nothing}, expected ${expect}"
grep -E "INSTRUMENTATION_|Error|Exception|assumption|OK \(|FAILURES" <<< "${out}" | head -n 8
exit 1
