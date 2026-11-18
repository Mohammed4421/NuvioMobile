#!/usr/bin/env bash
# Runs on an Android emulator: installs the previous build, opens it, updates it in place with the
# new build and opens that. Fails if the update reinstalls, loses app data or the app crashes.
# Usage: emulator-update-test.sh <previous-x86_64-apk or ""> <new-x86_64-apk>
set -euo pipefail
previous="$1"
new="$2"
package=com.nuvio.app.plus
adb wait-for-device
adb root > /dev/null 2>&1 || true
adb wait-for-device

launch_and_check() {
  adb logcat -c
  adb shell monkey -p "${package}" -c android.intent.category.LAUNCHER 1 > /dev/null
  sleep 25
  local pid
  pid="$(adb shell pidof "${package}" | tr -d '\r' || true)"
  adb logcat -d -b crash > crash.log || true
  if grep -q "${package}" crash.log; then
    echo "::error::$1 crashed on launch"; cat crash.log; return 1
  fi
  [[ -n "${pid}" ]] || { echo "::error::$1 is not running after launch"; return 1; }
  echo "$1 launched (pid ${pid})."
}

first_install() { adb shell dumpsys package "${package}" | tr -d '\r' | sed -n 's/^ *firstInstallTime=//p' | head -n 1; }
data_files() { adb shell "ls /data/data/${package}/shared_prefs 2>/dev/null" | tr -d '\r' | sort; }

summary=""
if [[ -n "${previous}" && -f "${previous}" ]]; then
  adb install "${previous}"
  launch_and_check "Previous build"
  adb shell am force-stop "${package}"
  before_time="$(first_install)"
  before_files="$(data_files)"
  adb install -r "${new}"
  after_time="$(first_install)"
  [[ "${before_time}" == "${after_time}" ]] || { echo "::error::The new build reinstalled the app instead of updating it"; exit 1; }
  missing="$(comm -23 <(echo "${before_files}") <(data_files))"
  [[ -z "${missing}" ]] || { echo "::error::App data lost in the update: ${missing}"; exit 1; }
  summary="installed over the previous build, app data kept ($(echo "${before_files}" | grep -c . || true) settings files), "
else
  adb install "${new}"
fi
launch_and_check "New build"
echo "- Emulator (Android $(adb shell getprop ro.build.version.release | tr -d '\r')): ${summary}opened without a crash." >> checks-emulator.md
