#!/usr/bin/env bash
# Runs a Gradle command, writing its output to <log> (and to the job log), and runs it again, at most
# five times in all, only when it failed because a download failed (dependency or plugin repository
# unreachable, rate-limited or cut off). The waits grow (45, 90, 180, 360 s, plus a random part so
# parallel jobs do not retry together): Maven Central answers bursts from shared CI addresses with
# HTTP 429 for minutes, so short fixed waits failed three times in a row (Build 14, run 44663582915). A compile error, a failing test or any other failure is
# returned at once, and so are timeouts and killed or missing commands: retries never hide a real
# failure or stretch a job past its time limit.
# Usage: gradle-retry.sh <log> <command...>        Exit status: the command's last exit status.
set -uo pipefail
log="$1"; shift
attempts="${GRADLE_RETRY_ATTEMPTS:-5}"
base="${GRADLE_RETRY_WAIT:-45}"
pause="${GRADLE_RETRY_SLEEP:-sleep}"
download='Could not resolve|Could not download|could not resolve plugin artifact|Could not GET|Could not HEAD|Read timed out|Connection reset|Remote host terminated the handshake|status code 429|status code 50[0-9]'
real='^e: |There were failing tests|Compilation error|tests completed, [0-9]+ failed'
for attempt in $(seq 1 "${attempts}"); do
  "$@" 2>&1 | tee "${log}"
  status="${PIPESTATUS[0]}"
  (( status == 0 )) && exit 0
  # Timed out (124), killed (137, 143) or not runnable (126, 127): never a download to retry.
  case "${status}" in 124|126|127|137|143) exit "${status}" ;; esac
  if grep -qE "${real}" "${log}" || ! grep -qE "${download}" "${log}"; then
    exit "${status}"
  fi
  if (( attempt < attempts )); then
    wait=$(( base * (1 << (attempt - 1)) + RANDOM % (base / 2 + 1) ))
    reason="$(grep -oE "status code 429|status code 50[0-9]|Read timed out|Connection reset" "${log}" | head -n 1)"
    echo "::warning::A download failed${reason:+ (${reason})} (attempt ${attempt} of ${attempts}); running Gradle again in ${wait} s." >&2
    "${pause}" "${wait}"
  fi
done
echo "::error::Downloads kept failing after ${attempts} attempts." >&2
exit "${status}"
