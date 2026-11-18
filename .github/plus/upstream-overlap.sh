#!/usr/bin/env bash
# Lists where a Nuvio update touches what Nuvio Plus changed, so a person compares the two before
# the update reaches the stable branch. Prints a Markdown report; exits 1 when review is needed.
# Usage: upstream-overlap.sh <nuvio-plus-ref> <nuvio-target-ref>
set -euo pipefail
plus="$1"; target="$2"
base="$(git merge-base "${plus}" "${target}")"
ignore='^(README\.md|CHANGELOG\.md|\.gitattributes|signing/|\.github/)'
plus_files="$(git diff --name-only "${base}" "${plus}" | grep -vE "${ignore}" | sort -u || true)"
nuvio_files="$(git diff --name-only "${base}" "${target}" | sort -u || true)"
shared="$(comm -12 <(echo "${plus_files}") <(echo "${nuvio_files}") | grep . || true)"
# Nuvio adding something that looks like a Nuvio Plus feature (a possible duplicate).
features='subtitle.?brightness|brightnessFactor|SUBTITLE_BOTTOM_OFFSET|bottomOffset.*(600|1000)|OpenDocumentTree|takePersistableUriPermission|targetFolderUri|wifiOnly|NETWORK_TYPE_UNMETERED|season.?download|downloadSeason|SeasonDownload|LibraryViewMode\.Downloads|library_source_downloads|detectTransformGestures|SubtitleTransform|outlineWidth|LocalSubtitlePicker|subtitle.*(from|load).*(device|file)|PlusUpdate'
hits="$(git diff "${base}" "${target}" -- '*.kt' '*.kts' '*.xml' | grep -E '^\+[^+]' | grep -iE "${features}" | head -n 40 || true)"

echo "Nuvio update: \`$(git rev-parse --short "${target}")\` · common base: \`$(git rev-parse --short "${base}")\`"
echo
echo "Changed by Nuvio: $(echo "${nuvio_files}" | grep -c . || true) files. Changed by Nuvio Plus: $(echo "${plus_files}" | grep -c . || true) files."
echo
if [[ -n "${shared}" ]]; then
  echo "#### Files changed by both"
  echo "${shared}" | sed 's/^/- `/; s/$/`/'
  echo
fi
if [[ -n "${hits}" ]]; then
  echo "#### Nuvio code that may duplicate a Nuvio Plus feature"
  echo '```'
  echo "${hits}" | cut -c1-200
  echo '```'
  echo
fi
if [[ -z "${shared}" && -z "${hits}" ]]; then
  echo "No overlap with Nuvio Plus changes."
  exit 0
fi
exit 1
