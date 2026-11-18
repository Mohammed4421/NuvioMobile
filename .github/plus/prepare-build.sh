#!/usr/bin/env bash
# Writes local.properties for a Nuvio Plus build: signing, account server, updater source.
# Usage: prepare-build.sh <build-number>
set -euo pipefail
build="$1"
if [[ -n "${CUSTOM_KEYSTORE_BASE64:-}" ]]; then
  keystore="${RUNNER_TEMP}/custom-release.jks"
  printf '%s' "${CUSTOM_KEYSTORE_BASE64}" | base64 --decode > "${keystore}"
  password="${CUSTOM_KEYSTORE_PASSWORD}"
  alias="${CUSTOM_KEY_ALIAS:-custom}"
else
  # Public key kept in the repository so builds install over each other (see README).
  keystore="${GITHUB_WORKSPACE}/signing/nuvio-plus.p12"
  password="nuvio-plus-public"
  alias="nuvio-plus"
fi
{
  # Official Nuvio account server, as shipped in the official app (public client key).
  echo "NUVIO_SUPABASE_URL=https://api.nuvio.tv"
  echo "NUVIO_SUPABASE_FALLBACK_URL=https://api-two.nuvioapp.space"
  echo "NUVIO_SUPABASE_ANON_KEY=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJyb2xlIjoiYW5vbiIsImlzcyI6InN1cGFiYXNlIiwiaWF0IjoxNzgxNTIxMzQ2LCJleHAiOjE5MzkyMDEzNDZ9.tmQaj682pwzehpqlgCDMnySOqiUvpgRbrE43T4VJpDI"
  # The in-app updater follows this fork's releases.
  echo "PLUS_UPDATE_REPO=${GITHUB_REPOSITORY}"
  echo "PLUS_BUILD=${build}"
} > local.properties
# Extra lines (Trakt, Simkl keys and so on) come later and win.
printf '%s\n' "${CUSTOM_LOCAL_PROPERTIES:-}" >> local.properties
{
  echo "NUVIO_RELEASE_STORE_FILE=${keystore}"
  echo "NUVIO_RELEASE_STORE_PASSWORD=${password}"
  echo "NUVIO_RELEASE_KEY_ALIAS=${alias}"
  echo "NUVIO_RELEASE_KEY_PASSWORD=${password}"
} >> local.properties
