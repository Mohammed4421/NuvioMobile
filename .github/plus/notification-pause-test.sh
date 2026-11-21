#!/usr/bin/env bash
# Owner's download contract (Build 15), condition 9: pause and resume from the notification while
# the app is in the background. On the emulator, with the debug app:
#   1. start a real download from a slow local server (BackgroundDownloadTestActivity), press Home;
#   2. send the download notification's Pause action: its own PendingIntent, taken from the posted
#      notification by the debug-only NotificationActionTestReceiver (emulator shades did not
#      reliably show the notification to UI automation; the screen tap itself is a device check);
#   3. the download is Paused, its .part file stops growing and keeps its bytes;
#   4. tap Resume: it continues from where it stopped (the server sees a Range request from that
#      offset, not a new download from zero);
#   5. it completes and the file's SHA-256 equals the served file's.
# Android 14 (third argument "pause-only"): a download started by Resume is a user-initiated job,
# which Android 14 lets an app schedule from the background only with the exemption a real tap on
# the notification gives; the receiver's own send has none ("Android could not start the
# download", run of 6013f429). So on Android 14 the test checks Pause and stops; Resume from the
# notification there is a device check (it passed once with a real tap, run 44864116050).
# Prints PASS or the failing step with the evidence. Usage: notification-pause-test.sh <debug apk> <label> [pause-only]
set -uo pipefail
apk="$1"; label="$2"; mode="${3:-full}"
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
    echo "== notifications"; adb shell dumpsys notification --noredact | grep -A8 "pkg=${pkg}" | head -n 40
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
# Only commands that run-as can start on every Android version (Android 9 refuses 'test').
part_size() { adb shell "run-as ${pkg} ls -l 'files/downloads/$1.part'" 2>/dev/null | tr -d '\r' | awk 'NF >= 5 && $1 ~ /^-/ {print $5; exit}' || true; }

# Point (x y) of the first node of the current screen whose text or description is $1
# (case-insensitive; Android 9 shows action labels in capitals).
find_node() {
  # uiautomator dump sometimes writes nothing ("could not get idle state"): try again.
  for _ in 1 2 3; do
    adb shell rm -f /sdcard/ui.xml
    adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
    [[ -n "$(adb shell cat /sdcard/ui.xml 2>/dev/null | head -c 20)" ]] && break
    sleep 2
  done
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

# Point of the expand button of the notification whose title is at y=$1 (the nearest
# android:id/expand_button to that title on the current screen), or nothing.
expand_button_near() {
  adb shell cat /sdcard/ui.xml | python3 -I -c '
import re, sys
y0 = int(sys.argv[1]); best = None
for node in re.findall(r"<node [^>]*>", sys.stdin.read()):
    if "resource-id=\"android:id/expand_button\"" not in node:
        continue
    b = [int(v) for v in re.findall(r"\d+", re.search(r"bounds=\"([^\"]*)\"", node).group(1))]
    x, y = (b[0] + b[2]) // 2, (b[1] + b[3]) // 2
    if best is None or abs(y - y0) < abs(best[1] - y0):
        best = (x, y)
if best and abs(best[1] - y0) < 200:
    print(best[0], best[1])' "$1"
}

notification_posted() {
  # Read first, then match: with pipefail, grep -q closing the pipe early fails the pipeline.
  local records
  records="$(adb shell dumpsys notification --noredact 2>/dev/null | tr -d '\r')"
  [[ "${records}" == *"pkg=${pkg}"* ]]
}

# Opens the notification list as a user does: one finger from the top edge (cmd statusbar
# expand-notifications opens the quick settings over the list on some Android 14 images).
open_shade() {
  # Both ways, the system command first: either has failed to open the shade on some runs.
  adb shell cmd statusbar expand-notifications; sleep 3
  [[ -n "$(find_node "Background download test")" ]] && return 0
  local width
  width="$(adb shell wm size | sed -nE 's/.* ([0-9]+)x[0-9]+.*/\1/p' | tail -n 1 | tr -d '\r')"
  adb shell cmd statusbar collapse; sleep 1
  adb shell input swipe "$((width / 2))" 2 "$((width / 2))" "$((height * 2 / 3))" 500; sleep 3
}

# Sends the action labelled $1 of the posted download notification (its own PendingIntent).
send_notification_action() {
  wait_for 30 "the app's download notification to be posted" notification_posted
  local out
  out="$(adb shell am broadcast -n "${pkg}/com.nuvio.android.NotificationActionTestReceiver" --es action "$1" 2>&1 | tr -d '\r')"
  [[ "${out}" == *"result=1"* ]] || fail "the notification has no '$1' action (${out}; $(adb logcat -d -s NuvioNotificationTest | tail -n 3 | tr '\n' ' '))"
  say "sent the notification's '$1' action"
}

# Taps the action $1 of the download's notification. The shade can open with the quick settings
# taking the screen (the notification below the fold) and low-priority notifications are shown
# collapsed (no actions) on newer Android: scroll until the notification is on screen, then use
# its expand button (a swipe on it as the fallback), checking for the action after each step.
tap_notification_button() {
  local point="" title="" height
  height="$(adb shell wm size | sed -nE 's/.* [0-9]+x([0-9]+).*/\1/p' | tail -n 1 | tr -d '\r')"
  # The app must have posted the notification (system record), whatever the shade shows.
  wait_for 30 "the app's download notification to be posted" notification_posted
  open_shade
  for step in 1 2 3 4 5 6; do
    point="$(find_node "$1")"
    [[ -n "${point}" ]] && break
    title="$(find_node "Background download test")"
    if [[ -z "${title}" ]]; then
      say "notification not on screen (step ${step}): scrolling the shade"
      if (( step % 2 == 1 )); then
        adb shell input swipe 300 "$((height * 3 / 4))" 300 "$((height / 4))" 400
      else
        # The swipe may have closed the shade or the quick settings: open the notifications again.
        adb shell cmd statusbar collapse; sleep 1; open_shade
      fi
      sleep 2
      continue
    fi
    read -r x y <<< "${title}"
    expand="$(expand_button_near "${y}")"
    if [[ -n "${expand}" && "${step}" -lt 5 ]]; then
      say "expanding the notification with its expand button at ${expand}"
      adb shell input tap ${expand}
    else
      say "expanding the notification by a swipe at ${title}"
      adb shell input swipe "${x}" "${y}" "${x}" "$((y + 400))" 300
    fi
    sleep 2
  done
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
started() { [[ "$(state | cut -f1)" == Downloading ]] && adb shell run-as "${pkg}" ls cache/download-test-ready > /dev/null 2>&1; }
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
send_notification_action "Pause"
wait_for 20 "Paused" bash -c "[[ \"\$(adb shell run-as ${pkg} cat shared_prefs/nuvio_downloads.xml)\" == *Paused* ]]"
sleep 3; p1="$(part_size "${file}")"; sleep 8; p2="$(part_size "${file}")"
[[ -n "${p1}" && "${p1}" == "${p2}" ]] || fail ".part kept changing after pause (${p1:-none} -> ${p2:-none})"
[[ "${p1}" -ge "${s2}" ]] || fail ".part shrank after pause (${s2} -> ${p1})"
IFS=$'\t' read -r status _ _ <<< "$(state)"
[[ "${status}" == Paused ]] || fail "status after pause: ${status}"
say "paused from the notification: status ${status}, .part kept at ${p1} bytes and not growing"
requests_before="$(wc -l < "${work}/requests.log")"
if [[ "${mode}" == pause-only ]]; then
  say "NOT TESTED here: Resume from the notification on Android 14 (needs a real tap; device check)"
  say "PASS (pause only)"
  exit 0
fi

# 4. Resume from the notification: continues from the kept bytes.
send_notification_action "Resume"
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
actual="$(adb exec-out "run-as ${pkg} cat 'files/downloads/${file}'" 2>/dev/null | sha256sum | cut -d' ' -f1)"
[[ "${actual}" == "${expected}" ]] || fail "completed file differs: ${actual:-missing} != ${expected}"
say "completed; SHA-256 matches the served file"
say "PASS"
