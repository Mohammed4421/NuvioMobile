#!/usr/bin/env bash
# Runs on an Android emulator: the move of an installed build 12 (old, public key) to the release APK
# (new key, v3 lineage from the old key). See docs/plus/signing-migration.md.
#   Android 9+: the release installs over build 12 as an update and keeps its data; afterwards an APK
#               signed with the old key alone (the tested build of the same commit) is refused.
#   Android 8 and older: the release is refused (it needs Android 9) and build 12 stays as it was.
# Usage: release-upgrade-test.sh <build-12-x86_64-apk> <release-x86_64-apk> <old-key-x86_64-apk>
set -uo pipefail
exec > >(tee -a release-upgrade.log) 2>&1
previous="$1"; release="$2"; old_key="$3"
package=com.nuvio.app.plus

fail() { echo "::error::$*"; exit 1; }
run() { echo "+ $*"; "$@"; }
# The installer's verdict: "Success" or "Failure [INSTALL_…]".
install() { adb install -r "$1" 2>&1 | tr -d '\r' | grep -oE "Success|Failure \[[A-Z_]+[^]]*\]" | tail -n 1; }
first_install() { adb shell dumpsys package "${package}" | tr -d '\r' | sed -n 's/^ *firstInstallTime=//p' | head -n 1; }
version_code() { adb shell dumpsys package "${package}" | tr -d '\r' | sed -n 's/^ *versionCode=\([0-9]*\).*/\1/p' | head -n 1; }
marker() { adb shell "cat /data/data/${package}/files/plus-upgrade-marker 2>/dev/null" | tr -d '\r'; }
prefs() { adb shell "ls /data/data/${package}/shared_prefs 2>/dev/null" | tr -d '\r' | sort; }
launch() {
  adb logcat -c || true
  adb shell monkey -p "${package}" -c android.intent.category.LAUNCHER 1 > /dev/null || fail "$1 could not be launched"
  sleep 20
  [[ -n "$(adb shell pidof "${package}" | tr -d '\r')" ]] || fail "$1 is not running after launch"
  ! adb logcat -d -b crash 2>/dev/null | grep -q "${package}" || fail "$1 crashed on launch"
}

for apk in "${previous}" "${release}" "${old_key}"; do [[ -f "${apk}" ]] || fail "Missing APK: ${apk}"; done
adb wait-for-device
adb root > /dev/null 2>&1 || true
sleep 3
adb wait-for-device
sdk="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
android="$(adb shell getprop ro.build.version.release | tr -d '\r')"
echo "Android ${android} (API ${sdk})"

adb uninstall "${package}" > /dev/null 2>&1 || true
run adb install "${previous}" || fail "Build 12 could not be installed"
launch "Build 12"
adb shell am force-stop "${package}"
adb shell "mkdir -p /data/data/${package}/files && echo kept > /data/data/${package}/files/plus-upgrade-marker" \
  || fail "Could not write the data marker"
before_time="$(first_install)"; before_prefs="$(prefs)"; before_version="$(version_code)"
[[ "$(marker)" == kept && -n "${before_time}" ]] || fail "Build 12's data could not be prepared"

verdict="$(install "${release}")"
echo "Release over build 12: ${verdict}"
if (( sdk < 28 )); then
  [[ "${verdict}" == Failure* ]] || fail "Android ${android} accepted the release; it must be refused (v3 only, needs Android 9)"
  [[ "$(first_install)" == "${before_time}" && "$(version_code)" == "${before_version}" && "$(marker)" == kept ]] \
    || fail "Build 12 or its data changed after the refused install"
  line="- Release move (Android ${android}): refused as intended (${verdict}); build 12 and its data untouched."
else
  [[ "${verdict}" == Success ]] || fail "The release did not install over build 12: ${verdict}"
  [[ "$(first_install)" == "${before_time}" ]] || fail "The release reinstalled the app instead of updating it"
  [[ "$(marker)" == kept ]] || fail "App data lost in the move"
  missing="$(comm -23 <(echo "${before_prefs}") <(prefs) | grep . || true)"
  [[ -z "${missing}" ]] || fail "Settings lost in the move: ${missing}"
  after_version="$(version_code)"
  (( after_version > before_version )) || fail "Version code did not rise: ${before_version} -> ${after_version}"
  launch "The release"
  adb shell am force-stop "${package}"
  # What the holder of the public key can make: the same app signed with the old key alone.
  attack="$(install "${old_key}")"
  echo "Old key alone after the move: ${attack}"
  [[ "${attack}" == Failure* && "${attack}" == *INCOMPATIBLE* ]] \
    || fail "An APK signed with the old key alone was not refused after the move: ${attack:-no verdict}"
  [[ "$(version_code)" == "${after_version}" && "$(marker)" == kept ]] || fail "The refused install changed the app"
  launch "The release after the refused install"
  line="- Release move (Android ${android}): update over build 12, data kept ($(echo "${before_prefs}" | grep -c . || true) settings files and a marker), version ${before_version} -> ${after_version}; then an APK signed with the old key alone refused (${attack})."
fi
echo "${line}" >> "checks-release-upgrade-api${sdk}.md"
echo "::notice::${line#- }"
