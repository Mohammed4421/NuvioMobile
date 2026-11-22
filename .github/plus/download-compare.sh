#!/usr/bin/env bash
# Build 15, P5-07: a background download of Build 14 against the head, on the same emulator, in
# alternating blocks (14, head, 14, head, ...) so drift affects both alike. Each block installs one
# build's debug app (each built from its own source, see download-compare.yml; Build 14's debug
# BackgroundDownloadTestActivity is the same file as the head's, so no harness patch is needed),
# starts the same download from a local server with that activity, presses Home and lets the app's
# own download engine finish it in the background. Printed as "MEASURE <label> <metric> <value>"
# for measure-compare.py:
#   download_wall_ms         from the activity's "download started" marker to the finished file
#                            (the server sends at most RATE bytes/s, so the floor is SIZE/RATE)
#   download_cpu_ms_per_mib  the app process's CPU time (utime+stime, /proc/<pid>/stat) while in the
#                            background, divided by the MiB received in that time
#   download_pss_kb          the highest TOTAL PSS (dumpsys meminfo) of 3 samples taken at 4, 8 and
#                            12 s in the background (the same samples for both builds; dumpsys asks the
#                            app for its memory, which costs both builds the same little CPU)
# Every run must also: keep one app process, make exactly one request from byte 0 (no restart),
# reach Completed, and finish with the exact bytes (SHA-256); otherwise the script fails, so a
# broken download is never scored. Debug apps on an emulator: relative numbers only.
# Usage: download-compare.sh <blocks> <build14 debug apk> <head debug apk>
set -uo pipefail
blocks="$1"; b14_apk="$2"; head_apk="$3"
here="$(cd "$(dirname "$0")" && pwd)"
pkg=com.nuviodebug.com
id=nuvio-background-download-fixture
size=$(( 64 * 1048576 ))
rate=$(( 4 * 1048576 ))
work="$(mktemp -d)"
exec > >(tee -a download-compare.log) 2>&1

now_ms() { echo $(( $(date +%s%N) / 1000000 )); }
fail() {
  echo "::error::$*"
  echo "server requests:"; sed 's/^/  /' "${work}/requests.log" 2>/dev/null
  { echo "== stored downloads"; adb shell run-as "${pkg}" cat shared_prefs/nuvio_downloads.xml
    echo "== files"; adb shell run-as "${pkg}" ls -la files/downloads
    echo "== crashes"; adb logcat -d -b crash 2>/dev/null | tail -n 60
    echo "== app log"; adb logcat -d 2>/dev/null | grep -E "com\.nuviodebug|nuvio|AndroidRuntime|WM-|Download" | tail -n 80
  } 2>&1 | sed 's/^/  /'
  exit 1
}
# status <tab> file name of the fixture download in the app's stored downloads list.
state() {
  adb shell run-as "${pkg}" cat shared_prefs/nuvio_downloads.xml 2>/dev/null | python3 -I -c '
import html, json, re, sys
xml = sys.stdin.read()
for value in re.findall(r"<string name=\"downloads_payload_[0-9]+\">(.*?)</string>", xml, re.S):
    payload = json.loads(html.unescape(value))
    items = payload.get("items", payload) if isinstance(payload, dict) else payload
    for item in items:
        if item.get("videoId") == sys.argv[1]:
            print(item.get("status"), item.get("fileName"), sep="\t")
            sys.exit(0)
print("missing\t-")' "${id}"
}
# utime+stime in clock ticks of /proc/<pid>/stat (fields 14 and 15; the name field may hold spaces).
cpu_ticks() { python3 -I -c 'import sys; f = sys.stdin.read().rsplit(")", 1)[1].split(); print(int(f[11]) + int(f[12]))'; }
pss_kb() {
  adb shell dumpsys meminfo "${pkg}" | sed -nE 's/^ *TOTAL PSS: *([0-9]+).*/\1/p; s/^ *TOTAL +([0-9]+) .*/\1/p' | head -1 | tr -d '\r'
}

head -c "${size}" /dev/urandom > "${work}/fixture.bin"
expected="$(sha256sum "${work}/fixture.bin" | cut -d' ' -f1)"
: > "${work}/requests.log"
python3 -I "${here}/slow-fixture-server.py" 8765 "${work}/fixture.bin" "${rate}" "${work}/requests.log" > "${work}/server.log" 2>&1 &
server=$!
trap 'kill ${server} 2>/dev/null' EXIT
for _ in $(seq 1 20); do curl -s -o /dev/null -r 0-0 http://127.0.0.1:8765/fixture.bin && break; sleep 0.5; done
adb wait-for-device
tck="$(adb shell getconf CLK_TCK 2>/dev/null | tr -d '\r')"
[[ "${tck}" =~ ^[0-9]+$ ]] || tck=100
echo "fixture: ${size} bytes at most ${rate} bytes/s (floor $(( size * 1000 / rate )) ms), SHA-256 ${expected}; clock ticks ${tck}/s"
echo "Android $(adb shell getprop ro.build.version.release | tr -d '\r') (API $(adb shell getprop ro.build.version.sdk | tr -d '\r'))"

run() {  # run <label> <apk>
  local label="$1" apk="$2" out t0 t1 pid probe cpu0 cpu1 part0 file listing status pss peak=0 next gets actual
  adb uninstall "${pkg}" > /dev/null 2>&1 || true
  out="$(bash "${here}/adb-install.sh" -r -g "${apk}" 2>&1)" || fail "${label}: install failed: $(grep -m 3 -E "Failure|Exception|Error" <<< "${out}" | tr '\n' ' ')"
  # Ahead-of-time compiled, as an installed release would be after optimisation (both builds alike).
  adb shell cmd package compile -m speed -f "${pkg}" > /dev/null 2>&1 || true
  adb logcat -c || true
  : > "${work}/requests.log"
  adb shell am start -W -n "${pkg}/com.nuvio.android.BackgroundDownloadTestActivity" \
    --es downloadUrl "http://10.0.2.2:8765/fixture.bin" > /dev/null || fail "${label}: could not start the download activity"
  for _ in $(seq 1 240); do
    adb shell run-as "${pkg}" ls cache/download-test-ready > /dev/null 2>&1 && break
    sleep 0.25
  done
  t0="$(now_ms)"
  IFS=$'\t' read -r status file <<< "$(state)"
  [[ "${status}" == Downloading ]] || fail "${label}: the download did not start (status ${status})"
  # "Downloading" in the store is not yet a transfer: from Android 14 the download runs as a
  # user-initiated job that the system starts a moment later. Leave the foreground only once bytes
  # flow (a request reached the server and the partial file exists), so the background phase
  # measures a running download. A download that never starts still fails, here, by name.
  for _ in $(seq 1 120); do
    [[ -s "${work}/requests.log" ]] && adb shell run-as "${pkg}" ls "files/downloads/${file}.part" > /dev/null 2>&1 && break
    sleep 0.25
  done
  [[ -s "${work}/requests.log" ]] || fail "${label}: no request reached the server within 30 s of the download being queued"
  echo "${label}: transfer running $(( $(now_ms) - t0 )) ms after the download was queued"
  adb shell input keyevent KEYCODE_HOME
  sleep 1
  pid="$(adb shell pidof "${pkg}" | tr -d '\r')"
  [[ "${pid}" =~ ^[0-9]+$ ]] || fail "${label}: no single app process (${pid:-none})"
  # CPU and bytes at the start of the background phase, in one shell call.
  probe="$(adb shell "run-as ${pkg} cat /proc/${pid}/stat; run-as ${pkg} ls -l 'files/downloads/${file}.part'" | tr -d '\r')"
  cpu0="$(head -1 <<< "${probe}" | cpu_ticks)" || fail "${label}: cannot read the app's CPU time"
  part0="$(tail -n +2 <<< "${probe}" | awk 'NF >= 5 && $1 ~ /^-/ {print $5; exit}')"
  [[ "${part0}" =~ ^[0-9]+$ ]] || fail "${label}: no partial file at the start of the background phase"
  (( part0 * 2 < size )) || fail "${label}: ${part0} of ${size} bytes already received in the foreground; too little left to measure"
  local tb; tb="$(now_ms)"
  next=4000
  for _ in $(seq 1 1200); do
    listing="$(adb shell run-as "${pkg}" ls files/downloads 2>/dev/null | tr -d '\r')"
    if grep -qxF "${file}" <<< "${listing}" && ! grep -qxF "${file}.part" <<< "${listing}"; then break; fi
    if (( next <= 12000 && $(now_ms) - tb >= next )); then
      pss="$(pss_kb)"; [[ "${pss}" =~ ^[0-9]+$ ]] && (( pss > peak )) && peak="${pss}"
      next=$(( next + 4000 ))
    fi
    sleep 0.25
  done
  t1="$(now_ms)"
  probe="$(adb shell "run-as ${pkg} cat /proc/${pid}/stat" | tr -d '\r')"
  cpu1="$(cpu_ticks <<< "${probe}")" || fail "${label}: the app process ${pid} ended during the download"
  grep -qxF "${file}" <<< "${listing}" || fail "${label}: the download did not finish within 300 s"
  [[ "$(adb shell pidof "${pkg}" | tr -d '\r')" == "${pid}" ]] || fail "${label}: the app process changed during the download"
  for _ in $(seq 1 60); do status="$(state | cut -f1)"; [[ "${status}" == Completed || "${status}" == Failed ]] && break; sleep 1; done
  [[ "${status}" == Completed ]] || fail "${label}: status ${status} after the file was finished"
  gets="$(grep -c '^GET ' "${work}/requests.log" || true)"
  [[ "${gets}" == 1 ]] && grep -q '^GET range=- ' "${work}/requests.log" || fail "${label}: expected one request from byte 0, the server saw ${gets}"
  actual="$(adb exec-out "run-as ${pkg} cat 'files/downloads/${file}'" 2>/dev/null | sha256sum | cut -d' ' -f1)"
  [[ "${actual}" == "${expected}" ]] || fail "${label}: the finished file differs: ${actual:-missing} != ${expected}"
  (( peak > 0 )) || fail "${label}: no memory sample during the transfer (finished in under 4 s of background?)"
  local cpu_ms=$(( (cpu1 - cpu0) * 1000 / tck )) bytes=$(( size - part0 ))
  echo "${label}: ${bytes} bytes in the background in $(( t1 - tb )) ms, app CPU ${cpu_ms} ms, PSS peak ${peak} kB; one request; Completed; SHA-256 matches"
  echo "MEASURE ${label} download_wall_ms $(( t1 - t0 ))"
  echo "MEASURE ${label} download_cpu_ms_per_mib $(python3 -I -c 'import sys; print(round(int(sys.argv[1]) * 1048576 / int(sys.argv[2]), 1))' "${cpu_ms}" "${bytes}")"
  echo "MEASURE ${label} download_pss_kb ${peak}"
  adb uninstall "${pkg}" > /dev/null 2>&1 || true
}

for _ in $(seq 1 "${blocks}"); do
  run build14 "${b14_apk}"
  run head "${head_apk}"
done
