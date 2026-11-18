#!/usr/bin/env bash
# Prints the next Nuvio Plus build number: one more than the highest published custom-N release.
set -euo pipefail
highest="$(gh api --paginate "repos/${GITHUB_REPOSITORY}/releases?per_page=100" --jq '.[].tag_name' \
  | sed -n 's/^custom-\([0-9][0-9]*\)\(-beta\)\{0,1\}$/\1/p' | sort -n | tail -n 1)"
echo $(( ${highest:-0} + 1 ))
