#!/usr/bin/env bash
# Runs on an Android emulator before a release is published (docs/plus/signing-migration.md):
#   Android 9+, from the previous release (same release key): the app's own update check accepts the
#     release and refuses an APK signed with another key, one signed with the old public key and a
#     damaged download; Android refuses the foreign APK too; the release installs as an update with
#     the data kept and opens.
#   Android 9+, from build 12 (old public key): the app's update check accepts the release (pinned key
#     rotation), it installs as an update with the data kept, and afterwards an APK signed with the
#     old key alone is refused.
#   Android 8 and older: the release is refused (it needs Android 9) and build 12 stays as it was.
# Usage: release-upgrade-test.sh <build-12 apk> <previous-release apk or ""> <release apk>
#        <old-key apk> <foreign-key apk> <debug app apk> <debug test apk>   (all x86_64)
set -uo pipefail
exec > >(tee -a release-upgrade.log) 2>&1
build12="$1"; previous="$2"; release="$3"; old_key="$4"; foreign="$5"; debug_app="$6"; debug_test="$7"
here="$(cd "$(dirname "$0")" && pwd)"
package=com.nuvio.app.plus

fail() { echo "::error::$*"; exit 1; }
run() { echo "+ $*"; "$@"; }
# The installer's verdict: "Success" or "Failure [INSTALL_…".
install() { adb install -r "$1" 2>&1 | tr -d '\r' | grep -oE "Success|Failure \[[A-Z_]+[^]]*\]" | tail -n 1; }
first_install() { adb shell dumpsys package "${package}" | tr -d '\r' | sed -n 's/^ *firstInstallTime=//p' | head -n 1; }
version_code() { adb shell dumpsys package "${package}" | tr -d '\r' | sed -n 's/^ *versionCode=\([0-9]*\).*/\1/p' | head -n 1; }
marker() { adb shell "cat /data/data/${package}/files/plus-upgrade-marker 2>/dev/null" | tr -d '\r'; }
prefs() { adb shell "ls /data/data/${package}/shared_prefs 2>/dev/null" | tr -d '\r' | sort; }
check() { bash "${here}/update-device-check.sh" "$@" || fail "The app's update check gave the wrong verdict ($3)"; }
launch() {
  adb logcat -c || true
  adb shell monkey -p "${package}" -c android.intent.category.LAUNCHER 1 > /dev/null || fail "$1 could not be launched"
  sleep 20
  [[ -n "$(adb shell pidof "${package}" | tr -d '\r')" ]] || fail "$1 is not running after launch"
  ! adb logcat -d -b crash 2>/dev/null | grep -q "${package}" || fail "$1 crashed on launch"
  adb shell am force-stop "${package}"
}
# Installs <apk> fresh, opens it once and leaves a data marker.
prepare() {
  adb uninstall "${package}" > /dev/null 2>&1 || true
  run adb install "$1" > /dev/null || fail "$2 could not be installed"
  launch "$2"
  adb shell "mkdir -p /data/data/${package}/files && echo kept > /data/data/${package}/files/plus-upgrade-marker" \
    || fail "Could not write the data marker"
  [[ "$(marker)" == kept && -n "$(first_install)" ]] || fail "$2's data could not be prepared"
}
# Installs the release over what is installed: an update, data kept, version higher, it opens.
update_to_release() {
  local before_time before_prefs before_version verdict missing after_version
  before_time="$(first_install)"; before_prefs="$(prefs)"; before_version="$(version_code)"
  verdict="$(install "${release}")"
  echo "Release over $1: ${verdict}"
  [[ "${verdict}" == Success ]] || fail "The release did not install over $1: ${verdict}"
  [[ "$(first_install)" == "${before_time}" ]] || fail "The release reinstalled the app instead of updating $1"
  [[ "$(marker)" == kept ]] || fail "App data lost in the update from $1"
  missing="$(comm -23 <(echo "${before_prefs}") <(prefs) | grep . || true)"
  [[ -z "${missing}" ]] || fail "Settings lost in the update from $1: ${missing}"
  after_version="$(version_code)"
  (( after_version > before_version )) || fail "Version code did not rise from $1: ${before_version} -> ${after_version}"
  launch "The release (from $1)"
  UPDATED="version ${before_version} -> ${after_version}, data kept ($(echo "${before_prefs}" | grep -c . || true) settings files and a marker)"
}
refused_by_android() {
  local verdict; verdict="$(install "$1")"
  echo "$2 over the release: ${verdict}"
  [[ "${verdict}" == Failure* && "${verdict}" == *INCOMPATIBLE* ]] || fail "$2 was not refused by Android: ${verdict:-no verdict}"
}

for apk in "${build12}" "${release}" "${old_key}" "${foreign}" "${debug_app}" "${debug_test}"; do
  [[ -f "${apk}" ]] || fail "Missing APK: ${apk}"
done
adb wait-for-device
adb root > /dev/null 2>&1 || true
sleep 3
adb wait-for-device
sdk="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
android="$(adb shell getprop ro.build.version.release | tr -d '\r')"
result="checks-release-upgrade-api${sdk}.md"
echo "Android ${android} (API ${sdk})"

if (( sdk < 28 )); then
  prepare "${build12}" "Build 12"
  before_time="$(first_install)"; before_version="$(version_code)"
  verdict="$(install "${release}")"
  echo "Release over build 12: ${verdict}"
  [[ "${verdict}" == Failure* ]] || fail "Android ${android} accepted the release; it must be refused (v3 only, needs Android 9)"
  [[ "$(first_install)" == "${before_time}" && "$(version_code)" == "${before_version}" && "$(marker)" == kept ]] \
    || fail "Build 12 or its data changed after the refused install"
  line="- Release on Android ${android}: refused as intended (${verdict}); build 12 and its data untouched."
  echo "${line}" >> "${result}"; echo "::notice::${line#- }"
  exit 0
fi

device_host="$(bash "${here}/device-test-host.sh" "${debug_app}" "${debug_test}")" || fail "The device test host could not be set up"
eval "${device_host}"
damaged="$(mktemp --suffix=.apk)"
head -c "$(( $(stat -c %s "${release}") / 2 ))" "${release}" > "${damaged}"

if [[ -n "${previous}" ]]; then
  [[ -f "${previous}" ]] || fail "Missing APK: ${previous}"
  prepare "${previous}" "The previous release"
  check "${release}" accepted "App check, release over the previous release"
  check "${foreign}" OtherSigner "App check, APK with a foreign key"
  check "${old_key}" OtherSigner "App check, APK with the old public key"
  check "${damaged}" "Unreadable|Unsigned" "App check, damaged download"
  refused_by_android "${foreign}" "APK with a foreign key"
  refused_by_android "${old_key}" "APK with the old public key"
  update_to_release "the previous release"
  line="- Update from the previous release (Android ${android}): the app's own check accepts the release and refuses foreign-key, old-key and damaged APKs; Android refuses the foreign and old-key APKs; ${UPDATED}."
  echo "${line}" >> "${result}"; echo "::notice::${line#- }"
fi

prepare "${build12}" "Build 12"
check "${release}" accepted "App check, release over build 12 (pinned key rotation)"
update_to_release "build 12"
refused_by_android "${old_key}" "APK with the old public key, after the move"
[[ "$(marker)" == kept ]] || fail "The refused install changed the app"
launch "The release after the refused install"
line="- Move from build 12 (Android ${android}): the app's check accepts the release (pinned rotation), ${UPDATED}; then an APK signed with the old key alone is refused."
echo "${line}" >> "${result}"; echo "::notice::${line#- }"
