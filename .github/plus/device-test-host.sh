#!/usr/bin/env bash
# Installs the debug app and its instrumentation test APK on the emulator and prints, for eval, the
# test host they really are: NUVIO_TEST_HOST (the debug app's package, used for run-as) and
# NUVIO_TEST_RUNNER (<test package>/<runner>). Both come from the APKs' own manifests and must be
# listed by the device's package manager, so a wrong or changed application id fails here, loudly.
# Usage: eval "$(device-test-host.sh <debug app apk> <debug test apk>)"
set -uo pipefail
app="$1"; test_apk="$2"
here="$(cd "$(dirname "$0")" && pwd)"
fail() { echo "::error::$*" >&2; exit 1; }
aapt2="$(ls -d "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/nonexistent}}"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -n 1)"
[[ -x "${aapt2}" ]] || fail "aapt2 not found in the Android SDK build-tools"
host="$("${aapt2}" dump packagename "${app}" 2>/dev/null | tr -d '\r' | head -n 1)"
[[ -n "${host}" ]] || fail "Could not read the debug app's package"
read -r test_package runner target < <("${aapt2}" dump xmltree --file AndroidManifest.xml "${test_apk}" \
  | python3 -I "${here}/apk-instrumentation.py") || fail "Could not read the device test's instrumentation"
[[ "${target}" == "${host}" ]] || fail "The device test targets ${target}, but the debug app is ${host}"
[[ "${host}" != com.nuvio.app.plus ]] || fail "The device test host must not be the release package"
out="$(bash "${here}/adb-install.sh" -r "${app}" 2>&1)" || fail "The debug app (device test host ${host}) could not be installed: $(grep -m 3 -E "Failure|Exception|Error" <<< "${out}" | tr '\n' ' ')"
out="$(bash "${here}/adb-install.sh" -r "${test_apk}" 2>&1)" || fail "The device test (${test_package}) could not be installed: $(grep -m 3 -E "Failure|Exception|Error" <<< "${out}" | tr '\n' ' ')"
listed="$(adb shell pm list instrumentation | tr -d '\r')"
grep -qxF "instrumentation:${test_package}/${runner} (target=${host})" <<< "${listed}" \
  || fail "The device does not list ${test_package}/${runner} for ${host}: ${listed}"
adb shell run-as "${host}" true > /dev/null 2>&1 || fail "run-as does not work for ${host} (not debuggable?)"
echo "Device test host: ${host}, runner ${test_package}/${runner}" >&2
printf 'export NUVIO_TEST_HOST=%q NUVIO_TEST_RUNNER=%q\n' "${host}" "${test_package}/${runner}"
