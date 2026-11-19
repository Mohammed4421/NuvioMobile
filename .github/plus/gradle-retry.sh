#!/usr/bin/env bash
# Runs a Gradle command, writing its output to <log> (and to the job log), and runs it again, at most
# three times in all, only when it failed because a download failed (dependency or plugin repository
# unreachable, rate-limited or cut off). A compile error, a failing test or any other failure is
# returned at once: retries never hide a real failure.
# Usage: gradle-retry.sh <log> <command...>        Exit status: the command's last exit status.
set -uo pipefail
log="$1"; shift
attempts=3
wait="${GRADLE_RETRY_WAIT:-60}"
download='Could not resolve|Could not download|could not resolve plugin artifact|Could not GET|Could not HEAD|Read timed out|Connection reset|Remote host terminated the handshake|status code 429|status code 50[0-9]'
real='^e: |There were failing tests|Compilation error|tests completed, [0-9]+ failed'
for attempt in $(seq 1 "${attempts}"); do
  "$@" 2>&1 | tee "${log}"
  status="${PIPESTATUS[0]}"
  (( status == 0 )) && exit 0
  if grep -qE "${real}" "${log}" || ! grep -qE "${download}" "${log}"; then
    exit "${status}"
  fi
  if (( attempt < attempts )); then
    echo "::warning::A download failed (attempt ${attempt} of ${attempts}); running Gradle again in ${wait} s." >&2
    sleep "${wait}"
  fi
done
echo "::error::Downloads kept failing after ${attempts} attempts." >&2
exit "${status}"
