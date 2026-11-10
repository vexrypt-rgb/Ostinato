#!/usr/bin/env bash
# Ostinato 2.0 Phase 09: second-version compile check of the interaction capability's container boundary.
# Compiles the 26.3 tree's ContainerView and OpenVerifier, unchanged, plus the 1.16 adapter in v1161/, at Java 8
# against the 1.16.5 MCP jar (the names origin/1.16.1 builds with). Then shows that ProcessOutcome (a record) is
# not Java 8 source. Compile only: nothing here runs a 1.16 client.
# Usage, from the repo root: bash docs/ostinato2/tools/phase09/compile-1161.sh [mcp-jar]
set -u
JAR="${1:-$HOME/.gradle/caches/fabric-loom/1.16.5-mapped-null.unspecified-1.0-v2/minecraft-1.16.5-mapped-null.unspecified-1.0-v2.jar}"
HERE="docs/ostinato2/tools/phase09"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
[ -f "$JAR" ] || { echo "no MCP jar at $JAR"; exit 2; }
echo "jar: $JAR"
echo "contract sha1: $(git hash-object src/main/java/baritone/process/interact/ContainerView.java src/main/java/baritone/process/interact/OpenVerifier.java | tr '\n' ' ')"
javac --release 8 -Xlint:-options -nowarn -cp "$JAR" -d "$OUT" \
  src/main/java/baritone/process/interact/ContainerView.java \
  src/main/java/baritone/process/interact/OpenVerifier.java \
  "$HERE/v1161/ClientContainers.java"
rc=$?
echo "1.16 adapter + unchanged contract, --release 8: $([ $rc -eq 0 ] && echo COMPILED || echo FAILED rc=$rc)"
[ $rc -eq 0 ] && (cd "$OUT" && find . -name '*.class' | sort)
# negative control: the 26.3 adapter must not compile against 1.16 names (the version-specific part is real)
javac --release 8 -Xlint:-options -nowarn -cp "$JAR" -d "$OUT/neg" \
  src/main/java/baritone/process/interact/ContainerView.java \
  src/main/java/baritone/process/interact/ClientContainers.java >/dev/null 2>&1
echo "26.3 adapter against the 1.16 jar (control): $([ $? -eq 0 ] && echo 'COMPILED (control broken)' || echo 'FAILED (expected)')"
javac --release 8 -Xlint:-options -nowarn -d "$OUT/po" src/api/java/baritone/api/process/ProcessOutcome.java >/dev/null 2>&1
echo "ProcessOutcome (record), --release 8: $([ $? -eq 0 ] && echo COMPILED || echo 'FAILED (expected: records need Java 16)')"
exit $rc
