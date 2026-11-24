#!/usr/bin/env bash
# GC comparison on plain/GcBench.java (40M live hash-consed nodes ~2.5 GB incl. table, 200M short-lived
# nodes ~6.4 GB of garbage). Prints one line per configuration.  Usage: scripts/gc-matrix.sh [live] [work]
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
LIVE="${1:-40}"; WORK="${2:-200}"
H="-Xms8g -Xmx8g"
run() { scripts/jrun.sh "$1" GcBench "$LIVE" "$WORK"; }
run "$H -XX:+UseSerialGC"
run "$H -XX:+UseSerialGC -Xmn1g"
run "$H -XX:+UseSerialGC -Xmn4g"
run "$H -XX:+UseSerialGC -Xmn4g -XX:+AlwaysPreTouch"
run "$H -XX:+UseSerialGC -Xmn4g -XX:+UseCompactObjectHeaders"
run "$H -XX:+UseSerialGC -Xmn4g -XX:+UseTransparentHugePages"
run "$H -XX:+UseParallelGC"
run "$H -XX:+UseParallelGC -XX:ParallelGCThreads=1"
run "$H -XX:+UseParallelGC -XX:ParallelGCThreads=2"
run "$H -XX:+UseParallelGC -XX:ParallelGCThreads=4"
run "$H -XX:+UseParallelGC -XX:ParallelGCThreads=4 -XX:-UseAdaptiveSizePolicy -Xmn4g"
run "$H -XX:+UseParallelGC -XX:ParallelGCThreads=2 -Xmn4g -XX:+UseCompactObjectHeaders -XX:+UseTransparentHugePages"
run "$H -XX:+UseG1GC"
run "$H -XX:+UseG1GC -XX:ParallelGCThreads=1 -XX:ConcGCThreads=1"
run "$H -XX:+UseG1GC -XX:ParallelGCThreads=4 -XX:ConcGCThreads=1"
run "$H -XX:+UseZGC"
run "-Xmx16g -XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC"
