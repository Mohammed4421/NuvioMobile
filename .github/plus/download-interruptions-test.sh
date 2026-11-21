#!/usr/bin/env bash
# Downloads in daily use (Build 15, owner's priority: real behaviour, features together). On the
# emulator, with the debug app and a slow local server, two interruptions of a running download:
#   A. Network lost: Wi-Fi and mobile data off while the app is in the background. The download must
#      not complete or fail for good while offline, its partial file must be kept, and when the
#      network comes back it must continue from the kept bytes (a Range request, not from zero) and
#      finish with the exact bytes.
#   B. App process killed (as Android does under memory pressure): the partial file is kept, and
#      after the user opens the app again the download continues from the kept bytes and finishes
#      with the exact bytes.
# A step the emulator cannot produce (the network cannot be cut) is reported NOT TESTED, never PASS.
# Usage: download-interruptions-test.sh <debug apk> <label>
set -uo pipefail
apk="$1"; label="$2"
here="$(cd "$(dirname "$0")" && pwd)"
pkg=com.nuviodebug.com
id=nuvio-background-download-fixture
work="$(mktemp -d)"
log="download-interruptions-${label}.log"
exec > >(tee -a "${log}") 2>&1

say() { echo "${label}: $*"; }
network_on() { adb shell svc wifi enable > /dev/null 2>&1; adb shell svc data enable > /dev/null 2>&1; }
fail() {
  echo "::error::${label}: $*"
  network_on
  say "server requests:"; sed 's/^/  /' "${work}/requests.log" 2>/dev/null
  { echo "== stored downloads"; adb shell run-as "${pkg}" cat shared_prefs/nuvio_downloads.xml
    echo "== files"; adb shell run-as "${pkg}" ls -la files/downloads
    echo "== jobs"; adb shell dumpsys jobscheduler | grep -A12 "${pkg}" | head -n 60
    echo "== crashes"; adb logcat -d -b crash 2>/dev/null | tail -n 60
    echo "== app log"; adb logcat -d 2>/dev/null | grep -E "com\.nuviodebug|nuvio|AndroidRuntime|WM-|Download" | tail -n 80
  } 2>&1 | sed 's/^/  /'
  exit 1
}
state() {
  adb shell run-as "${pkg}" cat shared_prefs/nuvio_downloads.xml 2>/dev/null | python3 -I -c '
import html, json, re, sys
xml = sys.stdin.read()
for value in re.findall(r"<string name=\"downloads_payload_[0-9]+\">(.*?)</string>", xml, re.S):
    payload = json.loads(html.unescape(value))
    items = payload.get("items", payload) if isinstance(payload, dict) else payload
    for item in items:
        if item.get("videoId") == sys.argv[1]:
            print(item.get("status"), item.get("fileName"), item.get("downloadedBytes", 0), item.get("errorMessage") or "", sep="\t")
            sys.exit(0)
print("missing\t-\t0\t")' "${id}"
}
part_size() { adb shell "run-as ${pkg} ls -l 'files/downloads/$1.part'" 2>/dev/null | tr -d '\r' | awk 'NF >= 5 && $1 ~ /^-/ {print $5; exit}' || true; }
wait_for() {  # wait_for <seconds> <description> <command...>
  local seconds="$1" what="$2"; shift 2
  for _ in $(seq 1 "${seconds}"); do "$@" && return 0; sleep 1; done
  fail "timed out after ${seconds}s waiting for ${what} (state: $(state))"
}
status_is() { [[ "$(state | cut -f1)" == "$1" ]]; }
start_download() {
  adb shell run-as "${pkg}" rm -f cache/download-test-ready
  : > "${work}/requests.log"
  adb shell am start -W -n "${pkg}/com.nuvio.android.BackgroundDownloadTestActivity" \
    --es downloadUrl "http://10.0.2.2:8765/fixture.bin" > /dev/null || fail "could not start the download activity"
  started() { status_is Downloading && adb shell run-as "${pkg}" ls cache/download-test-ready > /dev/null 2>&1; }
  wait_for 60 "the download to start" started
  file="$(state | cut -f2)"
  adb shell input keyevent KEYCODE_HOME
  growing() { local size; size="$(part_size "${file}")"; [[ -n "${size}" && "${size}" -gt 2000000 ]]; }
  wait_for 60 "2 MB downloaded in the background" growing
}
# The first request after a mark in the server log must ask for a byte range starting within the kept bytes.
check_resumed_from() {  # check_resumed_from <requests before> <kept bytes>
  local request offset
  request="$(tail -n +"$(( $1 + 1 ))" "${work}/requests.log" | grep '^GET' | head -1)"
  offset="$(sed -nE 's/.*range=bytes=([0-9]+)-.*-> 206 .*/\1/p' <<< "${request}")"
  [[ -n "${offset}" ]] || fail "continued without a byte range (restarted from zero?): ${request:-no request}"
  (( offset > 0 && offset <= $2 )) || fail "continued from byte ${offset}, expected 1..$2"
  say "continued from byte ${offset} of the kept $2"
}
check_complete() {
  wait_for 300 "Completed" status_is Completed
  local actual
  actual="$(adb exec-out "run-as ${pkg} cat 'files/downloads/${file}'" 2>/dev/null | sha256sum | cut -d' ' -f1)"
  [[ "${actual}" == "${expected}" ]] || fail "completed file differs: ${actual:-missing} != ${expected}"
  say "completed; SHA-256 matches the served file"
}

head -c 25165824 /dev/urandom > "${work}/fixture.bin"
expected="$(sha256sum "${work}/fixture.bin" | cut -d' ' -f1)"
python3 -I "${here}/slow-fixture-server.py" 8765 "${work}/fixture.bin" 307200 "${work}/requests.log" > "${work}/server.log" 2>&1 &
server=$!
trap 'kill ${server} 2>/dev/null; network_on' EXIT
for _ in $(seq 1 20); do curl -s -o /dev/null -r 0-0 http://127.0.0.1:8765/fixture.bin && break; sleep 0.5; done
adb wait-for-device
adb install -r -g "${apk}" > /dev/null || fail "install failed"
result=PASS

# A. Network lost and back.
start_download
adb shell svc wifi disable > /dev/null 2>&1; adb shell svc data disable > /dev/null 2>&1
sleep 15
k1="$(part_size "${file}")"; sleep 10; k2="$(part_size "${file}")"
if [[ -z "${k1}" || "${k1}" != "${k2}" ]]; then
  say "NOT TESTED: the emulator kept downloading with Wi-Fi and data off (.part ${k1:-none} -> ${k2:-none})"
  result="PASS (network loss NOT TESTED)"
  network_on
  wait_for 300 "Completed" status_is Completed
else
  IFS=$'\t' read -r status _ _ error <<< "$(state)"
  [[ "${status}" != Completed ]] || fail "completed while offline"
  [[ "${status}" != Failed ]] || fail "failed for good after the network was lost: ${error}"
  say "offline: status ${status}, .part kept at ${k2} bytes"
  mark="$(wc -l < "${work}/requests.log")"
  network_on
  grown() { local size; size="$(part_size "${file}")"; [[ -n "${size}" && "${size}" -gt "${k2}" ]]; }
  wait_for 180 "the download to continue after the network came back" grown
  check_resumed_from "${mark}" "${k2}"
  check_complete
fi

# B. App process killed during the download, then opened again. A fresh app state first: the
# finished download of A would otherwise make the test activity's enqueue a no-op.
adb shell pm clear "${pkg}" > /dev/null || fail "could not clear the app"
adb install -r -g "${apk}" > /dev/null || fail "reinstall failed"
start_download
pid="$(adb shell pidof "${pkg}" | tr -d '\r')"
[[ -n "${pid}" ]] || fail "no app process while downloading"
mark="$(wc -l < "${work}/requests.log")"
adb shell run-as "${pkg}" kill -9 "${pid}" || fail "could not kill the app process"
sleep 5
kept="$(part_size "${file}")"
[[ -n "${kept}" && "${kept}" -gt 0 ]] || fail "the partial file was lost when the process died"
IFS=$'\t' read -r status _ _ error <<< "$(state)"
[[ "${status}" != Failed ]] || fail "failed for good after the process died: ${error}"
say "process killed: status ${status}, .part kept at ${kept} bytes"
adb shell monkey -p "${pkg}" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1 || fail "could not open the app again"
grown() { local size; size="$(part_size "${file}")"; [[ -n "${size}" && "${size}" -gt "${kept}" ]]; }
wait_for 180 "the download to continue after the app was opened again" grown
check_resumed_from "${mark}" "${kept}"
check_complete
say "${result}"
