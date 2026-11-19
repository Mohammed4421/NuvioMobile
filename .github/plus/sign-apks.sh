#!/usr/bin/env bash
# Signs the APKs built by Gradle (which signs them with a throwaway build-only key) with the current
# Nuvio Plus identity: the public key kept in the repository (signing/nuvio-plus.p12), the
# certificate of every build since build 8, APK Signature Scheme v2 only, so the result installs as
# an update. apksigner replaces the APK Signing Block; APKs with v1 (JAR) signatures are refused,
# since a stale v1 signature would survive re-signing with v1 disabled.
#
# This script is part of the commit being built, so it must never receive a secret: it only knows the
# public key. Signing with a private key is not possible today (phase 5 decides how, in a protected
# job that runs no file of the commit).
# Usage: sign-apks.sh <built-dir> <signed-dir> <public-keystore>
# Prints the SHA-256 digest of the signing certificate.
set -euo pipefail
trap 'echo "::error::sign-apks.sh line ${LINENO}: ${BASH_COMMAND}" >&2' ERR
in="$1"; out="$2"; public_keystore="$3"
tools="$(ls -d "${ANDROID_HOME}"/build-tools/* | sort -V | tail -n 1)"
mkdir -p "${out}"
key_dir="$(mktemp -d)"
trap 'rm -rf "${key_dir}"' EXIT
keystore="${public_keystore}"
export SIGNING_PASSWORD="nuvio-plus-public"
alias="nuvio-plus"
shopt -s nullglob
apks=("${in}"/*.apk)
(( ${#apks[@]} > 0 )) || { echo "::error::No APKs to sign in ${in}" >&2; exit 1; }
for apk in "${apks[@]}"; do
  name="$(basename "${apk}")"
  if unzip -Z1 "${apk}" | grep -qiE '^META-INF/[^/]+\.(SF|RSA|DSA|EC)$'; then
    echo "::error::${name} carries a v1 (JAR) signature; refusing to re-sign it" >&2; exit 1
  fi
  aligned="${key_dir}/${name}"
  if "${tools}/zipalign" -c 4 "${apk}" > /dev/null; then cp "${apk}" "${aligned}"; else "${tools}/zipalign" -f 4 "${apk}" "${aligned}"; fi
  "${tools}/apksigner" sign --ks "${keystore}" --ks-key-alias "${alias}" \
    --ks-pass env:SIGNING_PASSWORD --key-pass env:SIGNING_PASSWORD \
    --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled false --v4-signing-enabled false \
    --out "${out}/${name}" "${aligned}"
  "${tools}/apksigner" verify "${out}/${name}" >&2
  # The certificate is read from the APK Signing Block itself, not from apksigner's text output.
  digest="$(python3 -I "$(dirname "$0")/apk-certs.py" --current "${out}/${name}")"
  schemes="$(python3 -I "$(dirname "$0")/apk-certs.py" "${out}/${name}" | cut -d' ' -f1 | sort -u | paste -sd,)"
  [[ "${schemes}" == v2 ]] || { echo "::error::${name}: signed with ${schemes}, expected v2 only" >&2; exit 1; }
  [[ -z "${certificate:-}" || "${certificate}" == "${digest}" ]] \
    || { echo "::error::${name} is signed with another certificate than the other APKs" >&2; exit 1; }
  certificate="${digest}"
done
# Kept as evidence in the run: how this build-tools version prints certificates.
{ "${tools}/apksigner" verify --print-certs "${out}/${name}" || true; } | head -n 4 | sed 's/^/::notice::apksigner --print-certs: /' >&2

echo "${certificate}"
