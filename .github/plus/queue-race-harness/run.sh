#!/bin/bash
# Compiles the real SeasonDownloadQueue.kt unchanged with the test doubles here and runs the
# profile-race scenarios, each in a fresh class loader. Offline: needs only Kotlin 2.0.x and
# kotlinx-coroutines-core-jvm jars (KLIB, default: Gradle 8.14.3's lib folder) and a JDK.
set -e
HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../../.." && pwd)
L=${KLIB:-/opt/gradle-8.14.3/lib}; J=${JAVA:-java}
OUT=$(mktemp -d); mkdir -p "$OUT/app" "$OUT/runner"
KC="$J -cp $L/kotlin-compiler-embeddable-2.0.21.jar:$L/kotlin-stdlib-2.0.21.jar:$L/kotlin-reflect-2.0.21.jar:$L/trove4j-1.0.20200330.jar:$L/kotlinx-coroutines-core-jvm-1.6.4.jar:$L/annotations-24.0.1.jar:$L/kotlin-script-runtime-2.0.21.jar org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -nowarn"
CP=$L/kotlin-stdlib-2.0.21.jar:$L/kotlinx-coroutines-core-jvm-1.6.4.jar:$L/kotlinx-serialization-core-jvm-1.6.2.jar
$KC -cp "$CP" "$ROOT/composeApp/src/commonMain/kotlin/com/nuvio/app/features/downloads/SeasonDownloadQueue.kt" "$HERE/stubs" "$HERE/test" -d "$OUT/app"
cp -r "$HERE/res/META-INF" "$OUT/app/"
$KC -cp "$L/kotlin-stdlib-2.0.21.jar" "$HERE/runner" -d "$OUT/runner"
$J -cp "$OUT/runner:$L/kotlin-stdlib-2.0.21.jar" runner.RunnerKt "$OUT/app:$CP"
