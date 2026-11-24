#!/usr/bin/env bash
# rlvm-store/bench/real-export.sh - load a real lean4export file into the store with the test sweep
# (reader of rlvm-export, levels of rlvm-level), verify it against the export, print sizes and timings.
#
#   rlvm-store/bench/real-export.sh [java options ...] FILE [--no-blocks] [--decode-rounds N] [--layout]
#
#   --decode-rounds N   decode every proof block N times; the last round is timed (ns per record)
#   --layout            interleaved nodes against parallel columns on the decoded proofs (WP-A1)
# Needs the compiled test classes: `$MVN -o -q -pl rlvm-store test-compile` first.
# Exit status 1 if the store differs from the export or a root fails the structural tests.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
M2="$ROOT/.toolchain/m2/rlvm"
CP="$ROOT/rlvm-store/target/classes:$ROOT/rlvm-store/target/test-classes:$M2/rlvm-export/0.1.0-SNAPSHOT/rlvm-export-0.1.0-SNAPSHOT.jar:$M2/rlvm-level/0.1.0-SNAPSHOT/rlvm-level-0.1.0-SNAPSHOT.jar"
JAVA="${JAVA_HOME:-$ROOT/.toolchain/graalvm-ce-25}/bin/java"
JOPTS=(-Xmx8g -XX:+UseSerialGC)
while [ "$#" -gt 0 ] && [ "${1#-X}" != "$1" ]; do JOPTS+=("$1"); shift; done
exec "$JAVA" "${JOPTS[@]}" -cp "$CP" rlvm.store.RealExport "$@"
