#!/usr/bin/env bash
# Installs an APK on the emulator. Android 17 images can still be bringing the package service up
# after sys.boot_completed, and a streamed install then dies with "cmd: Failure calling service
# package: Broken pipe". So: wait until the package service answers, install, and only if the
# streamed transfer itself broke (never on an INSTALL_FAILED_* verdict) try once more without
# streaming. Prints adb's output; exits non-zero if the APK is not installed.
# Usage: adb-install.sh [adb install flags...] <apk>
set -uo pipefail
for _ in $(seq 1 90); do
  [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]] \
    && adb shell cmd package path android 2>/dev/null | grep -q '^package:' && break
  sleep 2
done
out="$(adb install "$@" 2>&1)"; status=$?
if (( status != 0 )) && grep -qE "Broken pipe|Can't find service|Failure calling service" <<< "${out}" \
    && ! grep -q "INSTALL_FAILED" <<< "${out}"; then
  echo "adb-install: the package service dropped the streamed install (${out//$'\n'/ }); retrying once without streaming" >&2
  sleep 5
  out="$(adb install --no-streaming "$@" 2>&1)"; status=$?
fi
printf '%s\n' "${out}"
exit "${status}"
