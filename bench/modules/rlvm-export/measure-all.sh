#!/usr/bin/env bash
# bench/modules/rlvm-export/measure-all.sh [quick]
# Reproduces the measurements of rlvm-export/README.md: parse (null sink) and parse + ColumnarStore
# on init / std / mathlib, in the arena container (8 CPUs, 16 GB), with user-space instruction counts.
# Requires: `$MVN -o -q -pl rlvm-export compile` done; exports present (missing ones are skipped).
# Optional: rlvm-export/target/native/exportload (see README, "native image").
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd -P)"
cd "$ROOT"
export RLVM_M_QUIET=1
M=bench/modules/rlvm-export/m.sh
CP=rlvm-export/target/classes
TOOL=rlvm.export.tools.ExportLoad
FILES="work/arena-site/generated/init.ndjson work/arena-site/generated/std.ndjson work/exports/mathlib.ndjson"
[ "${1:-}" = quick ] && FILES="work/arena-site/generated/init.ndjson work/arena-site/generated/std.ndjson"
# two words (used unquoted): a stock OpenJDK refuses -XX:-UseJVMCICompiler without the unlock
# (*agentic-note*: rep-OPT-J)
C2="-XX:+UnlockExperimentalVMOptions -XX:-UseJVMCICompiler"
for f in $FILES; do
  [ -e "$f" ] || { echo "skip $f (not present)"; continue; }
  n=$(basename "$f" .ndjson)
  for sink in null store; do
    $M "$n $sink default-jit"    -- java -Xmx10g -cp $CP $TOOL --sink $sink "$f"
    $M "$n $sink c2"             -- java $C2 -Xmx10g -cp $CP $TOOL --sink $sink "$f"
    $M "$n $sink c2 notiered"    -- java $C2 -XX:-TieredCompilation -Xmx10g -cp $CP $TOOL --sink $sink "$f"
    [ -x rlvm-export/target/native/exportload ] && $M "$n $sink native-image" -- rlvm-export/target/native/exportload --sink $sink "$f"
  done
  $M "$n null c2 buffered-io"    -- java $C2 -Xmx10g -cp $CP $TOOL --sink null --io buffered "$f"
  for t in 2 4 8; do
    $M "$n null c2 threads=$t"   -- java $C2 -Xmx10g -cp $CP $TOOL --sink null --threads $t "$f"
  done
  $M "$n store c2 threads=4"     -- java $C2 -Xmx10g -cp $CP $TOOL --sink store --threads 4 "$f"
done
