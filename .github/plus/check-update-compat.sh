#!/usr/bin/env bash
# Checks that a new APK can install as an update over the latest published Nuvio Plus build:
# same package, same signing certificate, higher version code. Writes the result to checks.md.
# Usage: check-update-compat.sh <new-arm64-apk>
set -euo pipefail
new="$1"
tools="$(ls -d "${ANDROID_HOME}"/build-tools/* | sort -V | tail -n 1)"
previous_tag="$(bash "$(dirname "$0")/previous-release.sh")"
if [[ -z "${previous_tag}" ]]; then
  echo "- Update check: skipped, no earlier build published." >> checks.md
  exit 0
fi
mkdir -p previous
gh release download "${previous_tag}" --repo "${GITHUB_REPOSITORY}" --dir previous --pattern '*arm64-v8a*.apk' --clobber
old="$(ls previous/*.apk | head -n 1)"

package_of() { "${tools}/aapt2" dump badging "$1" | sed -n "s/^package: name='\([^']*\)'.*/\1/p"; }
code_of() { "${tools}/aapt2" dump badging "$1" | sed -n "s/^package: .*versionCode='\([0-9]*\)'.*/\1/p"; }
# Read from the APK Signing Block; an empty result fails the check instead of comparing two blanks.
cert_of() { python3 -I "$(dirname "$0")/apk-certs.py" --current "$1"; }

"${tools}/apksigner" verify "${new}"
new_package="$(package_of "${new}")"; old_package="$(package_of "${old}")"
new_code="$(code_of "${new}")"; old_code="$(code_of "${old}")"
new_cert="$(cert_of "${new}")"; old_cert="$(cert_of "${old}")"
[[ "${new_cert}" =~ ^[0-9a-f]{64}$ && "${old_cert}" =~ ^[0-9a-f]{64}$ ]] \
  || { echo "::error::Could not read the signing certificates (new '${new_cert}', previous '${old_cert}')"; exit 1; }
[[ -n "${new_package}" && -n "${old_package}" && -n "${new_code}" && -n "${old_code}" ]] \
  || { echo "::error::Could not read the package or version code of the APKs"; exit 1; }
echo "previous ${previous_tag}: ${old_package} ${old_code} ${old_cert}"
echo "new: ${new_package} ${new_code} ${new_cert}"

status=0
[[ "${new_package}" == "${old_package}" ]] || { echo "::error::Package changed: ${old_package} -> ${new_package}"; status=1; }
(( new_code > old_code )) || { echo "::error::Version code ${new_code} is not higher than ${old_code}"; status=1; }
if [[ "${new_cert}" != "${old_cert}" ]]; then
  if [[ "${ALLOW_KEY_CHANGE:-false}" == "true" ]]; then
    echo "::warning::Signing certificate changed on purpose; this build needs a reinstall."
  else
    echo "::error::Signing certificate changed; the build would not install over ${previous_tag}"
    status=1
  fi
fi
if (( status == 0 )); then
  echo "- Update over ${previous_tag}: same package and signing certificate (SHA-256 ${new_cert::16}…), version code ${old_code} → ${new_code}." >> checks.md
  echo "::notice::Update over ${previous_tag}: same package and signature, version code ${old_code} → ${new_code}."
fi
exit "${status}"
