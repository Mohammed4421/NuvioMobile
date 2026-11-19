#!/bin/bash
# Compiles the real SeasonDownloadQueue.kt unchanged with the test doubles here and runs the
# profile-race scenarios r1-r8, each in a fresh class loader. Exits non-zero if any scenario fails.
#
# Jars: by default the app's own Kotlin and kotlinx-coroutines versions (gradle/libs.versions.toml),
# taken from the Gradle cache a Gradle build has filled (CI runs this after the unit tests).
# Offline elsewhere: KLIB=<folder with the jars> uses whatever versions that folder holds
# (for example KLIB=/opt/gradle-8.14.3/lib).
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../../.." && pwd)
J=${JAVA:-java}

version() { sed -n "s/^$1 *= *\"\(.*\)\"/\1/p" "$ROOT/gradle/libs.versions.toml" | head -n1; }
if [[ -n "${KLIB:-}" ]]; then
  SEARCH=("$KLIB"); KOTLIN='[0-9]*'; COROUTINES='[0-9]*'
else
  SEARCH=("${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1")
  KOTLIN=$(version kotlin); COROUTINES=$(version kotlinx-coroutines)
fi
# jar <artifact> <version glob> [optional]: newest matching jar (sources and javadoc jars excluded).
jar() {
  local found
  found=$(find "${SEARCH[@]}" -name "$1-$2.jar" ! -name '*-sources.jar' ! -name '*-javadoc.jar' 2>/dev/null | sort -V | tail -n1)
  if [[ -z "$found" && "${3:-}" != optional ]]; then echo "::error::$1 $2 not found in ${SEARCH[*]}" >&2; exit 1; fi
  echo "$found"
}
STDLIB=$(jar kotlin-stdlib "$KOTLIN")
COMPILER=$(jar kotlin-compiler-embeddable "$KOTLIN")
CORO=$(jar kotlinx-coroutines-core-jvm "$COROUTINES")
SER=$(jar kotlinx-serialization-core-jvm '[0-9]*')
# What the embeddable compiler loads besides; absent ones are not needed by the version found.
EXTRA=$(for a in kotlin-reflect kotlin-script-runtime kotlin-daemon-embeddable trove4j annotations; do jar "$a" '[0-9]*' optional; done | paste -sd: -)
echo "Kotlin $(basename "$COMPILER" .jar | sed 's/.*-//'), coroutines $(basename "$CORO" .jar | sed 's/.*-//')"

OUT=$(mktemp -d); mkdir -p "$OUT/app" "$OUT/runner"
KC=("$J" -cp "$COMPILER:$STDLIB:$CORO:$EXTRA" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -nowarn)
CP="$STDLIB:$CORO:$SER"
"${KC[@]}" -cp "$CP" "$ROOT/composeApp/src/commonMain/kotlin/com/nuvio/app/features/downloads/SeasonDownloadQueue.kt" "$HERE/stubs" "$HERE/test" -d "$OUT/app"
cp -r "$HERE/res/META-INF" "$OUT/app/"
"${KC[@]}" -cp "$STDLIB" "$HERE/runner" -d "$OUT/runner"
"$J" -cp "$OUT/runner:$STDLIB" runner.RunnerKt "$OUT/app:$CP"
