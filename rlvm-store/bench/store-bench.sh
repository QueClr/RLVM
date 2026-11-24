#!/usr/bin/env bash
# rlvm-store/bench/store-bench.sh - cost per operation of the store's write paths, measured like the
# arena measures a checker (perf stat + GNU time, bench/measure.sh) in the rlvm-arena container.
#
#   bench/arena-docker.sh --arena-limits rlvm-store/bench/store-bench.sh [--c2] [MODE[:SIZE] ...]
#
# Every mode of rlvm.store.StoreBench runs twice: a warm-up of SIZE operations, then SIZE and
# 2 x SIZE operations. The cost per operation is the difference of the two runs divided by SIZE
# (JVM start-up, the warm-up and the fixture cancel out; what remains is steady-state code).
# Needs the compiled classes: `$MVN -o -q -pl rlvm-store test-compile` on the host first.
# Output: one line per mode: CPU ns/op, wall ns/op, instructions/op, peak RSS of the larger run.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
M2="$ROOT/.toolchain/m2/rlvm"
CP="$ROOT/rlvm-store/target/classes:$ROOT/rlvm-store/target/test-classes:$M2/rlvm-export/0.1.0-SNAPSHOT/rlvm-export-0.1.0-SNAPSHOT.jar:$M2/rlvm-level/0.1.0-SNAPSHOT/rlvm-level-0.1.0-SNAPSHOT.jar"
JAVA="${JAVA_HOME:-$ROOT/.toolchain/graalvm-ce-25}/bin/java"
JOPTS="${RLVM_BENCH_JOPTS:--Xmx8g -XX:+UseSerialGC}"

declare -A SMALL=( [append]=20000000 [append-far]=20000000 [mk]=100000000 [mk-hit]=400000000 [decode]=100000000
                   [session]=4000000 [overlay-new]=100000 [names]=2135000 [names-gen]=2135000 [instantiate]=1000000 )
if [ "${1:-}" = "--c2" ]; then JOPTS="$JOPTS -XX:-UseJVMCICompiler"; shift; fi   # HotSpot C2 instead of the Graal JIT
MODES=("$@")
[ "${#MODES[@]}" -gt 0 ] || MODES=(append append-far mk mk-hit decode session overlay-new names names-gen instantiate)

field() { python3 -c 'import json,sys; print(json.loads(sys.argv[1])[sys.argv[2]])' "$1" "$2"; }

printf '%-12s %12s %12s %12s %10s\n' mode cpu_ns/op wall_ns/op instr/op rss_MB
for spec in "${MODES[@]}"; do
  mode="${spec%%:*}"                       # MODE or MODE:SMALLER_SIZE
  n1="${SMALL[$mode]}"; [ "$spec" = "$mode" ] || n1="${spec#*:}"
  n2=$((n1 * 2))
  j1="$("$ROOT/bench/measure.sh" --require-perf -- "$JAVA" $JOPTS -Dwarm="$n1" -cp "$CP" rlvm.store.StoreBench "$mode" "$n1" 2>/dev/null)"
  j2="$("$ROOT/bench/measure.sh" --require-perf -- "$JAVA" $JOPTS -Dwarm="$n1" -cp "$CP" rlvm.store.StoreBench "$mode" "$n2" 2>/dev/null)"
  python3 - "$mode" "$n1" "$n2" "$j1" "$j2" <<'PY'
import json, sys
mode, n1, n2 = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
a, b = json.loads(sys.argv[4]), json.loads(sys.argv[5])
d = n2 - n1
cpu = (b["cpu_time_s"] - a["cpu_time_s"]) * 1e9 / d
wall = (b["wall_time_s"] - a["wall_time_s"]) * 1e9 / d
ins = (b["instructions"] - a["instructions"]) / d
print("%-12s %12.1f %12.1f %12.1f %10.0f" % (mode, cpu, wall, ins, b["max_rss_bytes"] / 2**20))
PY
done
