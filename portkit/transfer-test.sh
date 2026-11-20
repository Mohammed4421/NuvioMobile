#!/usr/bin/env bash
# PortKit transfer test: builds a Git repository of the upstream base (optionally changed by a fork
# fixture patch), transfers features into it with portkit.py apply, checks out the transfer branch,
# runs the transferred tests with Gradle and requires every one to run and pass.
# Usage: transfer-test.sh <label> <fixture patch or -> <features (comma-separated)> <test classes (comma-separated)>
# Needs: the upstream base commit in this repository, Java 21, Gradle setup (CI), gradle-retry.sh.
# Appends one line per result to portkit-transfer.md.
set -uo pipefail
label="$1"; fixture="$2"; features="$3"; tests="$4"
here="$(cd "$(dirname "$0")" && pwd)"; repo="$(cd "${here}/.." && pwd)"
base="$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["upstream_base"]["commit"])' "${here}/compatibility.json")"
report="${repo}/portkit-transfer.md"
fail() { echo "::error::${label}: $*"; echo "| ${label} | ${features} | FAIL | $* |" >> "${report}"; exit 1; }

target="$(mktemp -d)/target"
mkdir -p "${target}"
git -C "${repo}" archive "${base}" | tar -x -C "${target}" || fail "could not extract upstream ${base}"
git -C "${target}" init -q -b main
if [[ "${fixture}" != "-" ]]; then
  git -C "${target}" apply "${repo}/${fixture}" || fail "fixture ${fixture} does not apply to upstream"
fi
git -C "${target}" add -A
git -C "${target}" -c user.name=fixture -c user.email=fixture@localhost commit -q -m "${label}"

IFS=',' read -r -a feature_list <<< "${features}"
python3 -I "${here}/portkit.py" apply --repo "${repo}" --target "${target}" --branch portkit/transfer "${feature_list[@]}" \
  > "${target}.apply.log" 2>&1 || { cat "${target}.apply.log"; fail "portkit apply did not apply"; }
grep -E "^\| \`|Applied on branch" "${target}.apply.log"
git -C "${target}" checkout -q portkit/transfer || fail "transfer branch missing"

filters=()
IFS=',' read -r -a test_list <<< "${tests}"
for test in "${test_list[@]}"; do filters+=(--tests "${test}"); done
(
  cd "${target}" && touch local.properties
  bash "${repo}/.github/plus/gradle-retry.sh" "${target}.gradle.log" timeout 40m ./gradlew :composeApp:testAndroidHostTest \
    -Pnuvio.android.distribution=full -Pnuvio.ios.distribution=appstore "${filters[@]}" > /dev/null
) || { tail -n 40 "${target}.gradle.log"; fail "Gradle failed (build or tests); log above"; }
python3 -I "${repo}/.github/plus/test-results.py" "${target}/composeApp/build/test-results" "${target}.results.json"
total="$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["total"])' "${target}.results.json")"
failed="$(python3 -I -c 'import json,sys; print(len(json.load(open(sys.argv[1]))["failed"]))' "${target}.results.json")"
(( total > 0 )) || fail "no transferred test ran"
(( failed == 0 )) || fail "${failed} of ${total} transferred tests failed"
echo "| ${label} | ${features} | PASS | builds; ${total} transferred tests pass |" >> "${report}"
echo "::notice::${label}: ${features} transferred, built, ${total} tests pass"
