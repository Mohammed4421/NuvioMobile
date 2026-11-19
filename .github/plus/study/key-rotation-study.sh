#!/usr/bin/env bash
# Key-rotation study (phase 5): how Android treats APK Signature Scheme v3 key rotation, on the
# emulator this script runs on. Only throwaway keys made by prepare-keys (never the repository's
# keystore); the app body is the custom-12 x86_64 APK, re-signed with those keys.
#   prepare-keys <work>            make keys k1 (old), k2 (new), k3 (stranger) and the signed APKs
#   run <work> <api-level>         install/update scenarios on the running emulator, write results
set -uo pipefail
cmd="$1"; work="$2"
pkg=com.nuvio.app.plus
pass=study-only
tools="$(ls -d "${ANDROID_HOME}"/build-tools/* | sort -V | tail -n 1)"
signer() { echo --ks "${work}/$1.p12" --ks-key-alias "$1" --ks-pass "pass:${pass}" --key-pass "pass:${pass}"; }

if [[ "${cmd}" == prepare-keys ]]; then
  set -e
  for key in k1 k2 k3; do
    keytool -genkeypair -noprompt -storetype PKCS12 -keystore "${work}/${key}.p12" -storepass "${pass}" -keypass "${pass}" \
      -alias "${key}" -keyalg RSA -keysize 2048 -validity 2 -dname "CN=Rotation study ${key} (throwaway)"
  done
  base="${work}/base.apk"
  common=(--v1-signing-enabled false --v4-signing-enabled false)
  # A: what users have today, v2 only, old key.
  "${tools}/apksigner" sign $(signer k1) "${common[@]}" --v3-signing-enabled false --out "${work}/A-k1.apk" "${base}"
  # Lineage k1 -> k2 in two forms: the old key may still update (rollback allowed), or not.
  "${tools}/apksigner" rotate --out "${work}/lineage-strict" --old-signer $(signer k1) --set-rollback false --new-signer $(signer k2)
  "${tools}/apksigner" rotate --out "${work}/lineage-lenient" --old-signer $(signer k1) --set-rollback true --new-signer $(signer k2)
  for kind in strict lenient; do
    # B: rotated, for Android 9 and later (v3.0); older Android sees the v2 block signed by k1.
    "${tools}/apksigner" sign $(signer k1) --next-signer $(signer k2) --lineage "${work}/lineage-${kind}" \
      --rotation-min-sdk-version 28 "${common[@]}" --out "${work}/B-${kind}.apk" "${base}"
  done
  # C: the old (exposed) key alone, after the rotation: what an attacker holding it could make.
  cp "${work}/A-k1.apk" "${work}/C-k1-only.apk"
  # D: a stranger's key. E: the new key alone, without the lineage.
  "${tools}/apksigner" sign $(signer k3) "${common[@]}" --out "${work}/D-k3.apk" "${base}"
  "${tools}/apksigner" sign $(signer k2) "${common[@]}" --out "${work}/E-k2-only.apk" "${base}"
  for apk in "${work}"/[A-E]-*.apk; do
    echo "== $(basename "${apk}")"; "${tools}/apksigner" verify -v --print-certs "${apk}" | grep -E "Verified using|Signer|Number of signers" || true
  done > "${work}/apks.txt"
  exit 0
fi

api="$3"
results="${work}/results-api${api}.md"
: > "${results}"
note() { echo "$1" | tee -a "${results}"; echo "::notice::API ${api}: $1"; }
# The installer's own verdict ("Success" or "Failure [INSTALL_FAILED_…: reason]"), never a blank line.
install() { adb install -r "$1" 2>&1 | tr -d '\r' | grep -E "Success|Failure|failed|Error" | tail -n 1 | cut -c1-220; }
first_install() { adb shell dumpsys package "${pkg}" | sed -n 's/.*firstInstallTime=//p' | head -n 1; }
signers() { adb shell dumpsys package "${pkg}" | grep -iE "signatures=|apkSigningVersion|PackageSignatures" | head -n 3 | tr -s ' ' | tr '\n' ' '; }
fresh() { adb uninstall "${pkg}" > /dev/null 2>&1 || true; install "${work}/A-k1.apk" > /dev/null; adb root > /dev/null 2>&1; sleep 2
  adb shell "mkdir -p /data/data/${pkg}/files && echo kept > /data/data/${pkg}/files/rotation-marker" > /dev/null 2>&1; }
marker() { adb shell "cat /data/data/${pkg}/files/rotation-marker 2>/dev/null" | tr -d '\r'; }

adb root > /dev/null 2>&1; sleep 3
for kind in strict lenient; do
  fresh
  before="$(first_install)"
  b="$(install "${work}/B-${kind}.apk")"
  kept="$(marker)"; after="$(first_install)"
  same_install=no; [[ -n "${before}" && "${before}" == "${after}" ]] && same_install=yes
  note "rotate (${kind}) over k1: ${b}; data kept: ${kept:-no}; same install: ${same_install}"
  echo "  signing after B-${kind}: $(signers)" >> "${results}"
  c="$(install "${work}/C-k1-only.apk")"
  note "old key alone after rotation (${kind}): ${c}"
done
fresh
note "stranger key over k1: $(install "${work}/D-k3.apk")"
fresh
note "new key without lineage over k1: $(install "${work}/E-k2-only.apk")"
note "data marker after the controls: $(marker || echo none)"
exit 0
