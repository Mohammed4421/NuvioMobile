#!/usr/bin/env bash
# Prints the newest published Nuvio Plus release a new build must update: the newest stable one for a
# stable build, the newest of either channel for a beta build. Empty when there is none.
set -euo pipefail
# The checks sign test builds with the old public key, so they compare with the last release signed
# with it (PLUS_PREVIOUS_TAG); release builds are compared with build 12 by release-upgrade-test.sh.
if [[ -n "${PLUS_PREVIOUS_TAG:-}" ]]; then echo "${PLUS_PREVIOUS_TAG}"; exit 0; fi
if [[ "${PLUS_CHANNEL:-stable}" == beta ]]; then
  filter='select(.draft | not) | select(.tag_name | test("^custom-[0-9]+(-beta)?$"))'
else
  filter='select((.draft or .prerelease) | not) | select(.tag_name | test("^custom-[0-9]+$"))'
fi
gh api --paginate "repos/${GITHUB_REPOSITORY}/releases?per_page=100" --jq ".[] | ${filter} | .tag_name" \
  | sort -t- -k2,2n | tail -n 1
