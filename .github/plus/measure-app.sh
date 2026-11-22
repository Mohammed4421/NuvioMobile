#!/usr/bin/env bash
# Build 15, P5 / P0-04: repeatable start-up and memory measurements of one APK on the emulator it
# runs on. Prints one line per value: "MEASURE <label> <metric> <value>", so runs on two builds in
# the same emulator can be compared (measure-compare.py). Values from the system itself:
#   cold_start_ms  am start -S -W TotalTime, app force-stopped and caches dropped before each launch
#   warm_start_ms  am start -W TotalTime after Home, process still alive
#   pss_kb         dumpsys meminfo TOTAL PSS 10 s after a cold start
#   apk_bytes      size of the APK file
#   scroll_jank_percent, scroll_frame_p90_ms
#                  dumpsys gfxinfo after the same 8 swipes on the first screen (P5-03); only when
#                  the swipes drew at least 30 frames, so a screen that did not scroll is not scored
#   input_frame_ms median time from the vsync of a frame that handled a touch to that frame being
#                  done (gfxinfo framestats, HANDLE_INPUT_START set), over the same swipes (P5-02)
# Usage: measure-app.sh <apk> <label> <rounds> <package>
set -uo pipefail
apk="$1"; label="$2"; rounds="$3"; pkg="$4"
activity="com.nuvio.app.MainActivity"
say() { echo "MEASURE ${label} $1 $2"; }

adb wait-for-device
adb uninstall "${pkg}" > /dev/null 2>&1 || true
adb install -r -g "${apk}" > /dev/null || { echo "::error::${label}: install failed"; exit 1; }
say apk_bytes "$(stat -c %s "${apk}")"
# First launch creates the app's data (onboarding state, databases); not measured.
adb shell am start -W -n "${pkg}/${activity}" > /dev/null; sleep 10
adb shell input keyevent KEYCODE_HOME; sleep 2

total_time() { sed -nE 's/^TotalTime: ([0-9]+).*/\1/p' | tr -d '\r'; }
# The same swipes on whatever the first screen shows, then the frames they drew (P5-02, P5-03).
scroll() {
  local size w h info frames jank p90 input
  size="$(adb shell wm size | sed -nE 's/.*: ([0-9]+)x([0-9]+).*/\1 \2/p' | tail -1 | tr -d '\r')"
  w="${size% *}"; h="${size#* }"
  adb shell dumpsys gfxinfo "${pkg}" reset > /dev/null
  for _ in 1 2 3 4; do
    adb shell input swipe $(( w / 2 )) $(( h * 3 / 4 )) $(( w / 2 )) $(( h / 4 )) 250; sleep 0.6
    adb shell input swipe $(( w / 2 )) $(( h / 4 )) $(( w / 2 )) $(( h * 3 / 4 )) 250; sleep 0.6
  done
  sleep 1
  info="$(adb shell dumpsys gfxinfo "${pkg}" framestats | tr -d '\r')"
  frames="$(sed -nE 's/^Total frames rendered: ([0-9]+).*/\1/p' <<< "${info}" | head -1)"
  jank="$(sed -nE 's/^Janky frames: [0-9]+ \(([0-9.]+)%\).*/\1/p' <<< "${info}" | head -1)"
  p90="$(sed -nE 's/^90th percentile: ([0-9]+)ms.*/\1/p' <<< "${info}" | head -1)"
  if [[ -n "${frames}" ]] && (( frames >= 30 )); then
    [[ -n "${jank}" ]] && say scroll_jank_percent "${jank}"
    [[ -n "${p90}" ]] && say scroll_frame_p90_ms "${p90}"
    input="$(python3 -I "$(dirname "$0")/framestats-input.py" <<< "${info}")"
    [[ -n "${input}" ]] && say input_frame_ms "${input}"
  else
    echo "${label}: the swipes drew ${frames:-no} frames; scrolling not scored this round"
  fi
}
for round in $(seq 1 "${rounds}"); do
  adb shell am force-stop "${pkg}"; sleep 2
  adb shell "echo 3 > /proc/sys/vm/drop_caches" > /dev/null 2>&1 || true
  cold="$(adb shell am start -S -W -n "${pkg}/${activity}" | total_time)"
  [[ -n "${cold}" ]] && say cold_start_ms "${cold}"
  sleep 10
  pss="$(adb shell dumpsys meminfo "${pkg}" | sed -nE 's/^ *TOTAL PSS: *([0-9]+).*/\1/p; s/^ *TOTAL +([0-9]+) .*/\1/p' | head -1 | tr -d '\r')"
  [[ -n "${pss}" ]] && say pss_kb "${pss}"
  adb shell input keyevent KEYCODE_HOME; sleep 3
  warm="$(adb shell am start -W -n "${pkg}/${activity}" | total_time)"
  [[ -n "${warm}" ]] && say warm_start_ms "${warm}"
  sleep 3
  scroll
  adb shell input keyevent KEYCODE_HOME; sleep 2
done
adb uninstall "${pkg}" > /dev/null 2>&1 || true
