#!/usr/bin/env bash
# Build 15, P5-05/06/08: playback stability, UI frames and CPU of Build 14 against the head, on the
# same emulator, in alternating blocks (14, head, 14, head, ...) so drift affects both alike.
# Each block installs one build's debug app and instrumentation APK (built from its own source with
# the head's PlaybackSmoothnessTest, see playback-compare.yml), runs that test (ExoPlayer and libmpv,
# 12 s each), and prints its measurements as "MEASURE <label> <engine>_<metric> <value>" for
# measure-compare.py. The test's own checks (playback keeps up, no stall, no error) must pass for
# both builds. Played time is also printed as time behind the clock (1000 - played ms per wall s),
# so a slower playback reads as an increase like every other metric. Dropped video frames are only
# counted by the head (Build 14 has no counter) and are printed apart, not compared.
# Usage: playback-compare.sh <blocks> <build14 app> <build14 test> <head app> <head test>
set -uo pipefail
blocks="$1"; b14_app="$2"; b14_test="$3"; head_app="$4"; head_test="$5"
here="$(cd "$(dirname "$0")" && pwd)"
exec > >(tee -a playback-compare.log) 2>&1
adb wait-for-device

block() {  # block <label> <app> <test>
  local label="$1" app="$2" test_apk="$3" out status=0 env
  env="$(bash "${here}/device-test-host.sh" "${app}" "${test_apk}")" || { echo "::error::${label}: the device test host could not be set up"; exit 1; }
  eval "${env}"
  adb shell cmd package compile -m speed -f "${NUVIO_TEST_HOST}" > /dev/null 2>&1 || true
  adb shell cmd package compile -m speed -f "${NUVIO_TEST_RUNNER%%/*}" > /dev/null 2>&1 || true
  adb logcat -c || true
  out="$(adb shell "am instrument -w -r -e class com.nuvio.android.PlaybackSmoothnessTest '${NUVIO_TEST_RUNNER}'" 2>&1 | tr -d '\r')"
  python3 -I "${here}/instrumentation-results.py" "${label}" <<< "${out}" >&2 || status=$?
  adb logcat -d -s NuvioMeasure:I | tr -d '\r' | sed -nE 's/.*MEASURE ([A-Za-z]+) ([a-z0-9_]+) (-?[0-9.]+).*/\1 \2 \3/p' \
    | while read -r engine metric value; do
        case "${metric}" in
          dropped_video_frames) echo "DROPPED ${label} ${engine} ${value}" ;;
          ui_frames) ;;
          played_ms_per_wall_s)
            echo "MEASURE ${label} ${engine}_${metric} ${value}"
            echo "MEASURE ${label} ${engine}_behind_ms_per_wall_s $(( value < 1000 ? 1000 - value : 0 ))" ;;
          *) echo "MEASURE ${label} ${engine}_${metric} ${value}" ;;
        esac
      done
  # A different build with the same package and a lower version cannot be installed over this one.
  adb uninstall "${NUVIO_TEST_RUNNER%%/*}" > /dev/null 2>&1 || true
  adb uninstall "${NUVIO_TEST_HOST}" > /dev/null 2>&1 || true
  (( status == 0 )) || { echo "::error::${label}: PlaybackSmoothnessTest failed"; exit 1; }
}

for _ in $(seq 1 "${blocks}"); do
  block build14 "${b14_app}" "${b14_test}"
  block head "${head_app}" "${head_test}"
done
