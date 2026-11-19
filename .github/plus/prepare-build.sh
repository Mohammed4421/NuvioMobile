#!/usr/bin/env bash
# Writes local.properties for a Nuvio Plus build: account server, updater source and, for publishing
# builds only, the extra properties in the file PLUS_EXTRA_PROPERTIES. That file is written by the
# first step of the build job from the CUSTOM_LOCAL_PROPERTIES secret, after checking every name
# against the list of public app settings; this script never sees the secret itself.
# It writes no signing settings: Gradle builds unsigned APKs, and sign-apks.sh signs them in a
# separate job that never runs Gradle or code from the commit being built.
# Usage: prepare-build.sh <build-number>
set -euo pipefail
build="$1"
[[ "${build}" =~ ^[0-9]+$ ]] || { echo "::error::Build number '${build}' is not a number"; exit 1; }
{
  # Official Nuvio account server, as shipped in the official app (public client key).
  echo "NUVIO_SUPABASE_URL=https://api.nuvio.tv"
  echo "NUVIO_SUPABASE_FALLBACK_URL=https://api-two.nuvioapp.space"
  echo "NUVIO_SUPABASE_ANON_KEY=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJyb2xlIjoiYW5vbiIsImlzcyI6InN1cGFiYXNlIiwiaWF0IjoxNzgxNTIxMzQ2LCJleHAiOjE5MzkyMDEzNDZ9.tmQaj682pwzehpqlgCDMnySOqiUvpgRbrE43T4VJpDI"
  # The in-app updater follows this fork's releases.
  echo "PLUS_UPDATE_REPO=${GITHUB_REPOSITORY}"
  echo "PLUS_BUILD=${build}"
} > local.properties
# Extra lines (Trakt, Simkl client ids and so on) come later and win. They are compiled into the APK,
# so they are public by nature.
if [[ -n "${PLUS_EXTRA_PROPERTIES:-}" ]]; then
  cat "${PLUS_EXTRA_PROPERTIES}" >> local.properties
fi
if grep -q '^NUVIO_RELEASE_' local.properties; then
  echo "::error::Signing settings must not reach the Gradle build"; exit 1
fi
