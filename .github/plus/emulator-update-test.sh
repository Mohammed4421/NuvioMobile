#!/usr/bin/env bash
# Runs on an Android emulator: installs the previous build, opens it, updates it in place with the
# new build and opens that. Fails if the update reinstalls, loses app data or the app crashes.
# Usage: emulator-update-test.sh <previous-x86_64-apk or ""> <new-x86_64-apk>
set -uo pipefail
exec > >(tee -a emulator.log) 2>&1
previous="$1"
new="$2"
package=com.nuvio.app.plus

fail() { echo "::error::$*"; exit 1; }
run() { echo "+ $*"; "$@"; }

adb wait-for-device
adb root || true
sleep 3
adb wait-for-device
echo "Android $(adb shell getprop ro.build.version.release | tr -d '\r'), ABIs $(adb shell getprop ro.product.cpu.abilist | tr -d '\r')"

launch_and_check() {
  adb logcat -c || true
  run adb shell monkey -p "${package}" -c android.intent.category.LAUNCHER 1 || fail "$1 could not be launched"
  sleep 25
  local pid
  pid="$(adb shell pidof "${package}" | tr -d '\r' || true)"
  adb logcat -d -b crash > crash.log 2>/dev/null || adb logcat -d > crash.log
  if grep -q "FATAL EXCEPTION" crash.log && grep -q "${package}" crash.log; then
    grep -A30 "FATAL EXCEPTION" crash.log | head -60
    fail "$1 crashed on launch"
  fi
  [[ -n "${pid}" ]] || { tail -50 crash.log; fail "$1 is not running after launch"; }
  echo "$1 launched (pid ${pid})."
}

first_install() { adb shell dumpsys package "${package}" | tr -d '\r' | sed -n 's/^ *firstInstallTime=//p' | head -n 1; }
data_files() { adb shell "ls /data/data/${package}/shared_prefs 2>/dev/null" | tr -d '\r' | sort; }

summary=""
if [[ -n "${previous}" && -f "${previous}" ]]; then
  run adb install "${previous}" || fail "The previous build could not be installed"
  launch_and_check "Previous build"
  adb shell am force-stop "${package}"
  before_time="$(first_install)"
  before_files="$(data_files)"
  echo "Settings files before the update: $(echo "${before_files}" | tr '\n' ' ')"
  run adb install -r "${new}" || fail "The new build could not be installed over the previous one"
  after_time="$(first_install)"
  [[ "${before_time}" == "${after_time}" ]] || fail "The new build reinstalled the app instead of updating it"
  missing="$(comm -23 <(echo "${before_files}") <(data_files) | grep . || true)"
  [[ -z "${missing}" ]] || fail "App data lost in the update: ${missing}"
  summary="installed over the previous build with its data kept ($(echo "${before_files}" | grep -c . || true) settings files), "
else
  run adb install "${new}" || fail "The new build could not be installed"
fi
launch_and_check "New build"

# Beyond launching: the app draws its UI, survives Back, rotation (tablet-like landscape) and an
# Arabic (right-to-left) start. Screenshots are kept for a person to look at.
mkdir -p screens
shot() { adb exec-out screencap -p > "screens/$1.png" || true; }
ui_has_app() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 || return 1
  adb shell cat /sdcard/ui.xml | tr -d '\r' > "screens/$1.xml"
  grep -q "package=\"${package}\"" "screens/$1.xml"
}
alive() { [[ -n "$(adb shell pidof "${package}" | tr -d '\r')" ]]; }
crashed() { adb logcat -d -b crash 2>/dev/null | grep -q "${package}"; }

shot portrait
ui_has_app portrait || fail "The new build shows no UI of its own after launch"

adb logcat -c || true
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
sleep 6
shot landscape
crashed && fail "The app crashed when rotated to landscape"
alive || fail "The app closed when rotated to landscape"
adb shell settings put system user_rotation 0

adb shell input keyevent KEYCODE_BACK
sleep 3
crashed && fail "The app crashed on Back"

rtl="skipped (needs Android 13+)"
if (( $(adb shell getprop ro.build.version.sdk | tr -d '\r') >= 33 )); then
  adb shell am force-stop "${package}"
  adb shell cmd locale set-app-locales "${package}" --locales ar
  launch_and_check "New build in Arabic"
  shot arabic
  ui_has_app arabic || fail "No UI in Arabic"
  if grep -qP '[\x{0600}-\x{06FF}]' screens/arabic.xml 2>/dev/null || grep -q '&#x6' screens/arabic.xml; then
    rtl="Arabic texts shown"
  else
    rtl="started in Arabic (no Arabic text found in the first screen)"
  fi
  adb shell cmd locale set-app-locales "${package}" --locales ""
fi

echo "- Emulator (Android $(adb shell getprop ro.build.version.release | tr -d '\r')): ${summary}opened without a crash; draws its UI; survives landscape rotation and Back; Arabic start: ${rtl}. Screenshots are in the run's emulator artifact." >> checks-emulator.md
echo "::notice::$(tail -n 1 checks-emulator.md | sed 's/^- //')"
