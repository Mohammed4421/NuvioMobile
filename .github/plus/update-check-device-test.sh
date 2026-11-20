#!/usr/bin/env bash
# Pull request checks, on an emulator: the app's own update check (real Android PackageManager) with
# the published release installed. Test builds are signed with the old public test key, so against a
# release signed with the release key they must be refused, and so must a damaged download and the
# installed version itself. Android's installer must refuse the test build too.
# Usage: update-check-device-test.sh <published-release x86_64 APK> <test build x86_64 APK>
#        <debug app APK> <debug test APK>
set -uo pipefail
exec > >(tee -a update-check-device.log) 2>&1
release="$1"; build="$2"; debug_app="$3"; debug_test="$4"
here="$(cd "$(dirname "$0")" && pwd)"
package=com.nuvio.app.plus
fail() { echo "::error::$*"; exit 1; }
for apk in "${release}" "${build}" "${debug_app}" "${debug_test}"; do [[ -f "${apk}" ]] || fail "Missing APK: ${apk}"; done
adb wait-for-device

adb install -r "${debug_app}" > /dev/null || fail "The debug app could not be installed"
adb install -r "${debug_test}" > /dev/null || fail "The device test could not be installed"
adb uninstall "${package}" > /dev/null 2>&1 || true
adb install "${release}" > /dev/null || fail "The published release could not be installed"

damaged="$(mktemp --suffix=.apk)"
head -c "$(( $(stat -c %s "${release}") / 2 ))" "${release}" > "${damaged}"

status=0
bash "${here}/update-device-check.sh" "${build}" OtherSigner "Test build (old public key) over the release" || status=1
bash "${here}/update-device-check.sh" "${release}" NotNewer "The installed release again" || status=1
bash "${here}/update-device-check.sh" "${damaged}" "Unreadable|Unsigned" "A damaged download" || status=1
verdict="$(adb install -r "${build}" 2>&1 | tr -d '\r' | grep -oE "Success|Failure \[[A-Z_]+" | tail -n 1)"
echo "Android installer, test build over the release: ${verdict}"
[[ "${verdict}" == "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE" ]] || { echo "::error::The installer did not refuse the test build: ${verdict}"; status=1; }
if (( status == 0 )); then
  line="- In-app update check on Android $(adb shell getprop ro.build.version.release | tr -d '\r') with the published release installed: refuses a build with another key, the installed version and a damaged download; the installer agrees."
  echo "${line}" >> checks-emulator.md
  echo "::notice::${line#- }"
fi
exit "${status}"
