#!/usr/bin/env bash
# Runs every measurement batch (about 45 minutes) and regenerates results/tables.md.
# Each batch is one arena-shaped container (bench/arena-docker.sh --arena-limits: 8 performance cores, 16 GB),
# every run inside it goes through bench/measure.sh (perf stat -e instructions:u, whole process tree).
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/../env.sh"
cd "$SPIKE"
python3 -I scripts/make-batches.py
J="$JAVA_HOME/bin/java -XX:+UseSerialGC -Xmx12g -cp $OUT/target/machine.jar rlvm.spike.machine.Main"
$J heat "$OUT/init.bin" > results/heat-init.txt
$J heat "$OUT/std.bin" > results/heat-std.txt
for b in exp1-nce hot-nce exp1-nee hot-nee exp1-jvm hot-jvm exp2 exp3 exp4-std-nce exp4-std-jvm final-images micro steady-jvm long-jvm jvm-c2; do
  scripts/pbatch.sh "$OUT/batches/$b.batch" "results/$b.txt" > /dev/null
  echo "done $b $(date +%T)"
done
python3 -I scripts/tables.py > results/tables.md
