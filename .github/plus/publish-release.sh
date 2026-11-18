#!/usr/bin/env bash
# Publishes tested APKs as release custom-<build>. Usage: publish-release.sh <build> <sha> <dir> [note]
set -euo pipefail
build="$1"; sha="$2"; dir="$3"; note="${4:-}"
channel="${PLUS_CHANNEL:-stable}"
tag="custom-${build}"
[[ "${channel}" == beta ]] && tag="custom-${build}-beta"
if gh release view "${tag}" --repo "${GITHUB_REPOSITORY}" > /dev/null 2>&1; then
  echo "${tag} is already published."
  exit 0
fi
{
  echo "Unofficial build of Nuvio Mobile with subtitle and download improvements."
  echo
  echo "**Install:** \`NuvioPlus-build${build}-arm64-v8a.apk\` fits most phones and tablets and installs as an update over the previous build (build 8 or newer)."
  [[ "${channel}" == beta ]] && echo && echo "**Beta:** built from the \`plus-beta\` branch with a Nuvio beta. Offered in the app only on the Beta update channel."
  if [[ -n "${note}" ]]; then echo; echo "${note}"; fi
  echo
  awk '/^## /{n++} n==1' CHANGELOG.md | sed '1s/^## /### /'
  echo
  echo "### Checks passed before publishing"
  cat "${dir}"/checks*.md 2>/dev/null || true
  echo
  echo "Full list: [CHANGELOG.md](https://github.com/${GITHUB_REPOSITORY}/blob/${sha}/CHANGELOG.md) · Commit: ${sha::7}"
} > release-notes.md
if [[ "${channel}" == beta ]]; then
  # Beta builds are pre-releases: only the Beta update channel offers them.
  gh release create "${tag}" "${dir}"/*.apk --repo "${GITHUB_REPOSITORY}" --target "${sha}" \
    --title "Nuvio Plus · build ${build} (beta)" --notes-file release-notes.md --prerelease --latest=false
else
  gh release create "${tag}" "${dir}"/*.apk --repo "${GITHUB_REPOSITORY}" --target "${sha}" \
    --title "Nuvio Plus · build ${build}" --notes-file release-notes.md --latest
fi
