#!/usr/bin/env bash
# Signs the unsigned APKs built by Gradle, without running Gradle or any code of the commit being
# built. The certificate stays the one every Nuvio Plus build since build 8 carries (APK Signature
# Scheme v2 only, as the earlier builds), so the result installs as an update.
#
# Key: the private key in CUSTOM_KEYSTORE_BASE64 when the publishing workflow passes one, otherwise
# the public key kept in the repository (signing/nuvio-plus.p12, the current identity).
# Usage: sign-apks.sh <unsigned-dir> <signed-dir> <public-keystore>
# Prints the SHA-256 digest of the signing certificate.
set -euo pipefail
in="$1"; out="$2"; public_keystore="$3"
tools="$(ls -d "${ANDROID_HOME}"/build-tools/* | sort -V | tail -n 1)"
mkdir -p "${out}"
key_dir="$(mktemp -d)"
trap 'rm -rf "${key_dir}"' EXIT
if [[ -n "${CUSTOM_KEYSTORE_BASE64:-}" ]]; then
  keystore="${key_dir}/release.keystore"
  printf '%s' "${CUSTOM_KEYSTORE_BASE64}" | base64 --decode > "${keystore}"
  export SIGNING_PASSWORD="${CUSTOM_KEYSTORE_PASSWORD:?CUSTOM_KEYSTORE_PASSWORD is missing}"
  alias="${CUSTOM_KEY_ALIAS:-custom}"
else
  keystore="${public_keystore}"
  export SIGNING_PASSWORD="nuvio-plus-public"
  alias="nuvio-plus"
fi
shopt -s nullglob
apks=("${in}"/*.apk)
(( ${#apks[@]} > 0 )) || { echo "::error::No APKs to sign in ${in}" >&2; exit 1; }
for apk in "${apks[@]}"; do
  name="$(basename "${apk}")"
  aligned="${key_dir}/${name}"
  if "${tools}/zipalign" -c 4 "${apk}" > /dev/null; then cp "${apk}" "${aligned}"; else "${tools}/zipalign" -f 4 "${apk}" "${aligned}"; fi
  "${tools}/apksigner" sign --ks "${keystore}" --ks-key-alias "${alias}" \
    --ks-pass env:SIGNING_PASSWORD --key-pass env:SIGNING_PASSWORD \
    --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled false --v4-signing-enabled false \
    --out "${out}/${name}" "${aligned}"
  "${tools}/apksigner" verify "${out}/${name}" >&2
done
"${tools}/apksigner" verify --print-certs "${out}/${name}" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -n 1
