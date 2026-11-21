#!/usr/bin/env bash
# Owner's download contract (Build 15), condition 9: pause and resume from the notification while
# the app is in the background. On the emulator, with the debug app:
#   1. start a real download from a slow local server (BackgroundDownloadTestActivity), press Home;
#   2. open the notification shade and tap the download's Pause button;
#   3. the download is Paused, its .part file stops growing and keeps its bytes;
#   4. tap Resume: it continues from where it stopped (the server sees a Range request from that
#      offset, not a new download from zero);
#   5. it completes and the file's SHA-256 equals the served file's.
# Prints PASS or the failing step with the evidence. Usage: notification-pause-test.sh <debug apk> <label>
set -uo pipefail
apk="$1"; label="$2"
here="$(cd "$(dirname "$0")" && pwd)"
pkg=com.nuviodebug.com
id=nuvio-background-download-fixture
work="$(mktemp -d)"
log="notification-pause-${label}.log"
exec > >(tee -a "${log}") 2>&1

say() { echo "${label}: $*"; }
fail() {
  echo "::error::${label}: $*"
  say "server requests:"; sed 's/^/  /' "${work}/requests.log" 2>/dev/null
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 && adb shell cat /sdcard/ui.xml > "notification-pause-${label}-ui.xml" 2>/dev/null
  { echo "== stored downloads"; adb shell run-as "${pkg}" cat shared_prefs/nuvio_downloads.xml
    echo "== files"; adb shell run-as "${pkg}" ls -la files/downloads
    echo "== jobs"; adb shell dumpsys jobscheduler | grep -A12 "${pkg}" | head -n 60
    echo "== connectivity"; adb shell dumpsys connectivity | grep -iE "NetworkAgentInfo|Active default network" | head -n 10
    echo "== crashes"; adb logcat -d -b crash 2>/dev/null | tail -n 60
    echo "== app log"; adb logcat -d 2>/dev/null | grep -E "com\.nuviodebug|nuvio|AndroidRuntime|WM-|Download" | grep -viE "SuperDelight|gstatic|ZappDownloader| SP  " | tail -n 80
    echo "== server"; cat "${work}/server.log" 2>/dev/null; } > "notification-pause-${label}-state.txt" 2>&1
  sed 's/^/  /' "notification-pause-${label}-state.txt"
  exit 1
}

# The download's stored state: status, file name, downloaded bytes (from the app's own storage).
state() {
  adb shell run-as "${pkg}" cat shared_prefs/nuvio_downloads.xml 2>/dev/null | python3 -I -c '
import html, json, re, sys
xml = sys.stdin.read()
# The payload key is scoped to the profile: downloads_payload_<profile id>.
for value in re.findall(r"<string name=\"downloads_payload_[0-9]+\">(.*?)</string>", xml, re.S):
    payload = json.loads(html.unescape(value))
    items = payload.get("items", payload) if isinstance(payload, dict) else payload
    for item in items:
        if item.get("videoId") == sys.argv[1]:
            print(item.get("status"), item.get("fileName"), item.get("downloadedBytes", 0), sep="\t")
            sys.exit(0)
print("missing\t-\t0")' "${id}"
}
# File names can contain spaces: quoted for the device shell.
part_size() { adb shell "run-as ${pkg} stat -c %s 'files/downloads/$1.part'" 2>/dev/null | tr -d '\r' || true; }

# Point (x y) of the first node of the current screen whose text or description is $1
# (case-insensitive; Android 9 shows action labels in capitals).
find_node() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  adb shell cat /sdcard/ui.xml | python3 -I -c '
import re, sys
ui = sys.stdin.read()
for node in re.findall(r"<node [^>]*>", ui):
    text = re.search(r" text=\"([^\"]*)\"", node)
    desc = re.search(r" content-desc=\"([^\"]*)\"", node)
    label = (text.group(1) if text else "") or (desc.group(1) if desc else "")
    if label.strip().lower() == sys.argv[1].lower():
        b = [int(v) for v in re.findall(r"\d+", re.search(r"bounds=\"([^\"]*)\"", node).group(1))]
        print((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)
        break' "$1"
}
shade_texts() {
  adb shell cat /sdcard/ui.xml | python3 -I -c '
import re, sys
print(" | ".join(t for t in re.findall(r" text=\"([^\"]+)\"", sys.stdin.read())))'
}

# Taps the action $1 of the download's notification in the notification shade. A low-priority
# notification is shown collapsed (no actions) on newer Android: it is expanded first by a swipe
# down on its title.
tap_notification_button() {
  adb shell cmd statusbar expand-notifications; sleep 2
  local point title
  point="$(find_node "$1")"
  if [[ -z "${point}" ]]; then
    title="$(find_node "Background download test")"
    [[ -n "${title}" ]] || fail "no download notification in the shade (texts: $(shade_texts))"
    read -r x y <<< "${title}"
    say "expanding the notification at ${title}"
    adb shell input swipe "${x}" "${y}" "${x}" "$((y + 400))" 300; sleep 2
    point="$(find_node "$1")"
  fi
  [[ -n "${point}" ]] || fail "no '$1' button in the notification shade (texts: $(shade_texts))"
  say "tapping '$1' at ${point}"
  adb shell input tap ${point}
  sleep 1; adb shell cmd statusbar collapse
}

wait_for() {  # wait_for <seconds> <description> <command...>
  local seconds="$1" what="$2"; shift 2
  for _ in $(seq 1 "${seconds}"); do "$@" && return 0; sleep 1; done
  fail "timed out after ${seconds}s waiting for ${what} (state: $(state))"
}

# 1. Fixture: 24 MB served at 300 KB/s (about 80 s), resumable.
head -c 25165824 /dev/urandom > "${work}/fixture.bin"
expected="$(sha256sum "${work}/fixture.bin" | cut -d' ' -f1)"
python3 -I "${here}/slow-fixture-server.py" 8765 "${work}/fixture.bin" 307200 "${work}/requests.log" > "${work}/server.log" 2>&1 &
server=$!
for _ in $(seq 1 20); do curl -s -o /dev/null -r 0-0 http://127.0.0.1:8765/fixture.bin && break; sleep 0.5; done
curl -s -o /dev/null -r 0-0 -w '%{http_code}' http://127.0.0.1:8765/fixture.bin | grep -q 206 || fail "the fixture server does not answer"
: > "${work}/requests.log"
trap 'kill ${server} 2>/dev/null' EXIT
adb wait-for-device
adb install -r -g "${apk}" > /dev/null || fail "install failed"
adb shell run-as "${pkg}" rm -f cache/download-test-ready
adb shell am start -W -n "${pkg}/com.nuvio.android.BackgroundDownloadTestActivity" \
  --es downloadUrl "http://10.0.2.2:8765/fixture.bin" > /dev/null || fail "could not start the download activity"
started() { [[ "$(state | cut -f1)" == Downloading ]] && adb shell run-as "${pkg}" test -f cache/download-test-ready; }
wait_for 60 "the download to start (activity checks done, status Downloading)" started
IFS=$'\t' read -r status file _ <<< "$(state)"
say "download started: ${file}"

# 2. Background, still downloading.
adb shell input keyevent KEYCODE_HOME
sleep 8
s1="$(part_size "${file}")"; sleep 5; s2="$(part_size "${file}")"
[[ -n "${s1}" && -n "${s2}" && "${s2}" -gt "${s1}" ]] || fail "not downloading in the background (.part ${s1:-none} -> ${s2:-none})"
say "downloading in the background: .part ${s1} -> ${s2} bytes"

# 3. Pause from the notification.
tap_notification_button "Pause"
wait_for 20 "Paused" bash -c "[[ \"\$(adb shell run-as ${pkg} cat shared_prefs/nuvio_downloads.xml)\" == *Paused* ]]"
sleep 3; p1="$(part_size "${file}")"; sleep 8; p2="$(part_size "${file}")"
[[ -n "${p1}" && "${p1}" == "${p2}" ]] || fail ".part kept changing after pause (${p1:-none} -> ${p2:-none})"
[[ "${p1}" -ge "${s2}" ]] || fail ".part shrank after pause (${s2} -> ${p1})"
IFS=$'\t' read -r status _ _ <<< "$(state)"
[[ "${status}" == Paused ]] || fail "status after pause: ${status}"
say "paused from the notification: status ${status}, .part kept at ${p1} bytes and not growing"
requests_before="$(wc -l < "${work}/requests.log")"

# 4. Resume from the notification: continues from the kept bytes.
tap_notification_button "Resume"
grown() { local size; size="$(part_size "${file}")"; [[ -n "${size}" && "${size}" -gt "${p1}" ]]; }
wait_for 30 "the .part to grow after resume" grown
resume_request="$(tail -n +"$((requests_before + 1))" "${work}/requests.log" | grep '^GET' | head -1)"
say "request after resume: ${resume_request}"
offset="$(sed -nE 's/.*range=bytes=([0-9]+)-.*-> 206 .*/\1/p' <<< "${resume_request}")"
[[ -n "${offset}" ]] || fail "resume did not ask for a byte range (restarted from zero?): ${resume_request}"
(( offset > 0 && offset <= p1 )) || fail "resumed from byte ${offset}, expected 1..${p1}"
say "resumed from byte ${offset} of the kept ${p1}"

# 5. Completes with the exact bytes.
wait_for 240 "Completed" bash -c "[[ \"\$(adb shell run-as ${pkg} cat shared_prefs/nuvio_downloads.xml)\" == *Completed* ]]"
actual="$(adb shell "run-as ${pkg} sha256sum 'files/downloads/${file}'" 2>/dev/null | cut -d' ' -f1 | tr -d '\r')"
[[ "${actual}" == "${expected}" ]] || fail "completed file differs: ${actual:-missing} != ${expected}"
say "completed; SHA-256 matches the served file"
say "PASS"
