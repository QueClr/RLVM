#!/usr/bin/env bash
# GC comparison on plain/GcBench.java measured in user-space INSTRUCTIONS (all threads), inside the
# arena-shaped container (8 CPUs, 16 GB): this is what the arena charges.
#   scripts/gc-matrix-perf.sh [live=40] [work=200]
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
. ./env.sh
LIVE="${1:-40}"; WORK="${2:-200}"
H="-Xms8g -Xmx8g"
run() {
  # shellcheck disable=SC2086
  scripts/perf.sh --limits "[$1]" -- "$JAVA_HOME/bin/java" $1 -cp "$OUT/plain" GcBench "$LIVE" "$WORK" | sed 's/live [0-9]*M nodes (table [0-9]* slots) //; s/ | sum [0-9a-f]* | final interned [0-9]*//'
}
run "$H -XX:+UseSerialGC"
run "$H -XX:+UseSerialGC -Xmn1g"
run "$H -XX:+UseSerialGC -Xmn4g"
run "$H -XX:+UseSerialGC -Xmn4g -XX:+UseCompactObjectHeaders"
run "$H -XX:+UseParallelGC"
run "$H -XX:+UseParallelGC -XX:ParallelGCThreads=1"
run "$H -XX:+UseParallelGC -XX:ParallelGCThreads=2"
run "$H -XX:+UseParallelGC -XX:ParallelGCThreads=4"
run "$H -XX:+UseParallelGC -XX:ParallelGCThreads=4 -Xmn4g"
run "$H -XX:+UseG1GC"
run "$H -XX:+UseG1GC -XX:ParallelGCThreads=1 -XX:ConcGCThreads=1"
run "$H -XX:+UseG1GC -XX:ParallelGCThreads=4 -XX:ConcGCThreads=1"
run "$H -XX:+UseZGC"
run "-Xmx14g -XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC"
run "-XX:+UseSerialGC"
run ""
