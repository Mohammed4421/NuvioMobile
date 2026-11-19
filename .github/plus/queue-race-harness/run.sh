#!/bin/bash
# Compiles the real SeasonDownloadQueue.kt unchanged with the test doubles here and runs the
# profile-race scenarios r1-r8, each in a fresh class loader. Exits non-zero if any scenario fails
# or if any jar the compiler or the scenarios need is missing.
#
# Jars: by default the app's own Kotlin and kotlinx-coroutines versions (gradle/libs.versions.toml),
# taken from the Gradle cache a Gradle build has filled (CI runs this after the unit tests), then from
# the Gradle distribution's own lib folder. Everything kotlin-compiler-embeddable declares in its POM
# (compile and runtime scope) is required; the list of jars used is printed first.
# Offline elsewhere: KLIB=<folder with the jars> uses whatever versions that folder holds
# (for example KLIB=/opt/gradle-8.14.3/lib).
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../../.." && pwd)
J=${JAVA:-java}
GUH=${GRADLE_USER_HOME:-$HOME/.gradle}

version() { sed -n "s/^$1 *= *\"\(.*\)\"/\1/p" "$ROOT/gradle/libs.versions.toml" | head -n1; }
if [[ -n "${KLIB:-}" ]]; then
  SEARCH=("$KLIB"); KOTLIN='[0-9]*'; COROUTINES='[0-9]*'
else
  SEARCH=("$GUH/caches/modules-2/files-2.1")
  # Second place: the lib folders of the Gradle distributions the wrapper installed.
  while IFS= read -r lib; do SEARCH+=("$lib"); done < <(find "$GUH/wrapper/dists" -maxdepth 4 -type d -name lib 2>/dev/null)
  KOTLIN=$(version kotlin); COROUTINES=$(version kotlinx-coroutines)
  [[ -n "$KOTLIN" && -n "$COROUTINES" ]] || { echo "::error::Kotlin or kotlinx-coroutines version not found in gradle/libs.versions.toml"; exit 1; }
fi
echo "Searching: ${SEARCH[*]}"

MISSING=()
# find_jar <artifact> <version glob>: newest matching jar (sources and javadoc jars excluded), or nothing.
find_jar() {
  find "${SEARCH[@]}" -name "$1-$2.jar" ! -name '*-sources.jar' ! -name '*-javadoc.jar' 2>/dev/null | sort -V | tail -n1
}
# need <artifact> <exact version glob> [any]: sets JAR to the jar, or records it as missing (in this
# shell, not a subshell, so nothing missing is lost). With "any", another version is accepted when the
# exact one is absent (a library the compiler only needs at all, such as annotations).
need() {
  JAR=$(find_jar "$1" "$2")
  if [[ -z "$JAR" && "${3:-}" == any ]]; then
    JAR=$(find_jar "$1" '[0-9]*')
    [[ -z "$JAR" ]] || echo "note: $1 $2 not found, using $(basename "$JAR")"
  fi
  [[ -n "$JAR" ]] || MISSING+=("$1 $2")
}

need kotlin-stdlib "$KOTLIN"; STDLIB=$JAR
need kotlin-compiler-embeddable "$KOTLIN"; COMPILER=$JAR
need kotlinx-coroutines-core-jvm "$COROUTINES"; CORO=$JAR
need kotlinx-serialization-core-jvm '[0-9]*'; SER=$JAR
need annotations '[0-9]*'; ANNOTATIONS=$JAR

# What the compiler declares it needs: its POM (Gradle keeps it next to the jar in the cache).
COMPILER_DEPS=()
POM=$(find "${SEARCH[@]}" -name "kotlin-compiler-embeddable-${KOTLIN}.pom" 2>/dev/null | sort -V | tail -n1)
if [[ -n "$POM" ]]; then
  echo "Compiler dependencies from $(basename "$POM"):"
  while read -r artifact ver; do
    [[ -n "$artifact" ]] || continue
    case "$artifact" in kotlin-stdlib|kotlinx-coroutines-core-jvm|annotations) continue;; esac  # required above
    echo "  $artifact ${ver:-any}"
    need "$artifact" "${ver:-[0-9]*}" any; [[ -z "$JAR" ]] || COMPILER_DEPS+=("$JAR")
  done < <(python3 -I - "$POM" <<'EOF'
import sys, xml.etree.ElementTree as ET
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
root = ET.parse(sys.argv[1]).getroot()
for d in root.findall("m:dependencies/m:dependency", ns):
    scope = (d.findtext("m:scope", "compile", ns) or "compile").strip()
    if scope not in ("compile", "runtime") or (d.findtext("m:optional", "false", ns) or "").strip() == "true":
        continue
    version = (d.findtext("m:version", "", ns) or "").strip()
    print(d.findtext("m:artifactId", "", ns).strip(), "" if version.startswith("${") or version.startswith("[") else version)
EOF
)
else
  # No POM (a KLIB folder): what kotlin-compiler-embeddable has declared since Kotlin 2.0.
  echo "Compiler dependencies: no POM found, using the known list"
  for artifact in kotlin-script-runtime kotlin-reflect kotlin-daemon-embeddable; do
    need "$artifact" '[0-9]*'; [[ -z "$JAR" ]] || COMPILER_DEPS+=("$JAR")
  done
  jar=$(find_jar trove4j '[0-9]*'); [[ -z "$jar" ]] || COMPILER_DEPS+=("$jar")  # dropped by newer compilers
fi

if (( ${#MISSING[@]} )); then
  for m in "${MISSING[@]}"; do echo "::error::Required jar not found: $m (searched ${SEARCH[*]})"; done
  exit 1
fi

echo "Jars used:"
for jar in "$COMPILER" "$STDLIB" "$CORO" "$ANNOTATIONS" "${COMPILER_DEPS[@]}" "$SER"; do echo "  $jar"; done
echo "Kotlin $(basename "$COMPILER" .jar | sed 's/.*-//'), coroutines $(basename "$CORO" .jar | sed 's/.*-//')"

OUT=$(mktemp -d); mkdir -p "$OUT/app" "$OUT/runner"
COMPILER_CP=$(IFS=:; echo "$COMPILER:$STDLIB:$CORO:$ANNOTATIONS${COMPILER_DEPS[*]:+:${COMPILER_DEPS[*]}}")
KC=("$J" -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -nowarn)
CP="$STDLIB:$CORO:$SER"
"${KC[@]}" -cp "$CP" "$ROOT/composeApp/src/commonMain/kotlin/com/nuvio/app/features/downloads/SeasonDownloadQueue.kt" "$HERE/stubs" "$HERE/test" -d "$OUT/app"
cp -r "$HERE/res/META-INF" "$OUT/app/"
"${KC[@]}" -cp "$STDLIB" "$HERE/runner" -d "$OUT/runner"
"$J" -cp "$OUT/runner:$STDLIB" runner.RunnerKt "$OUT/app:$CP"
