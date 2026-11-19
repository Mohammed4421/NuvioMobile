#!/usr/bin/env bash
# Publishes tested APKs as release custom-<build>. Usage: publish-release.sh <build> <sha> <dir> [note]
# The release notes come from the top section of CHANGELOG.md, which must be the "## Next build"
# section (what changed since the last published build); it is published as "Build <build>".
# Refused, so a build never goes out with wrong notes: no "## Next build" section on top, or the
# same section the previous release was published with (its SHA-256 is kept in the notes).
# After publishing, rename the section to "## Build <build> — …" and start a new "## Next build".
set -euo pipefail
build="$1"; sha="$2"; dir="$3"; note="${4:-}"
[[ "${build}" =~ ^[0-9]+$ ]] || { echo "::error::Build number '${build}' is not a number"; exit 1; }
channel="${PLUS_CHANNEL:-stable}"
tag="custom-${build}"
[[ "${channel}" == beta ]] && tag="custom-${build}-beta"
if gh release view "${tag}" --repo "${GITHUB_REPOSITORY}" > /dev/null 2>&1; then
  echo "${tag} is already published."
  exit 0
fi
section="$(awk '/^## /{n++} n==1' CHANGELOG.md)"
heading="$(head -n 1 <<< "${section}")"
if [[ "${heading}" != "## Next build"* ]]; then
  echo "::error::CHANGELOG.md must start with a '## Next build' section describing this build (found: '${heading:-nothing}'). Nothing is published."
  exit 1
fi
fingerprint="$(printf '%s' "${section}" | sha256sum | cut -c1-64)"
previous="$(bash "$(dirname "$0")/previous-release.sh")" \
  || { echo "::error::Could not read the published releases to compare the notes. Nothing is published."; exit 1; }
if [[ -n "${previous}" ]]; then
  previous_body="$(gh release view "${previous}" --repo "${GITHUB_REPOSITORY}" --json body --jq .body)"
  if [[ "${previous_body}" == *"changelog-section: ${fingerprint}"* ]]; then
    echo "::error::The '## Next build' section of CHANGELOG.md is the one ${previous} was published with. Rename it to '## Build …' and describe what changed since. Nothing is published."
    exit 1
  fi
fi
{
  echo "Unofficial build of Nuvio Mobile with subtitle and download improvements."
  echo
  echo "**Install:** \`NuvioPlus-build${build}-arm64-v8a.apk\` fits most phones and tablets and installs as an update over the previous build (build 8 or newer)."
  [[ "${channel}" == beta ]] && echo && echo "**Beta:** built from the \`plus-beta\` branch with a Nuvio beta. Offered in the app only on the Beta update channel."
  if [[ -n "${note}" ]]; then echo; echo "${note}"; fi
  echo
  # "## Next build (anything) — title" becomes "### Build <build> — title".
  sed -E "1s/^## Next build( \([^)]*\))?/### Build ${build}/" <<< "${section}"
  echo
  echo "### Checks passed before publishing"
  cat "${dir}"/checks*.md 2>/dev/null || true
  echo
  echo "Full list: [CHANGELOG.md](https://github.com/${GITHUB_REPOSITORY}/blob/${sha}/CHANGELOG.md) · Commit: ${sha::7}"
  echo
  echo "<!-- changelog-section: ${fingerprint} -->"
} > release-notes.md
if [[ "${channel}" == beta ]]; then
  # Beta builds are pre-releases: only the Beta update channel offers them.
  gh release create "${tag}" "${dir}"/*.apk --repo "${GITHUB_REPOSITORY}" --target "${sha}" \
    --title "Nuvio Plus · build ${build} (beta)" --notes-file release-notes.md --prerelease --latest=false
else
  gh release create "${tag}" "${dir}"/*.apk --repo "${GITHUB_REPOSITORY}" --target "${sha}" \
    --title "Nuvio Plus · build ${build}" --notes-file release-notes.md --latest
fi
