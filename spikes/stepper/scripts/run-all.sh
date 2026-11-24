#!/usr/bin/env bash
# Runs the measurement batches one after the other and regenerates results/tables.md. Each batch is one
# arena-shaped container (bench/arena-docker.sh --arena-limits: 8 performance cores, 16 GB); every run inside it
# goes through bench/measure.sh (perf stat over the process tree + GNU time, as the arena measures).
#   scripts/run-all.sh [batch ...]      default: the batches of the recorded campaign, the CPU-time duels first (about 2 hours)
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/../env.sh"
cd "$SPIKE"
python3 -I scripts/make-batches.py > /dev/null
if [ $# -eq 0 ]; then
  set -- duel-nce duel-jvm duel-nfi duel-c2 duel-jfi duel-cfi proc-nce proc-jvm proc-c2 proc-nfi deep-nce deep-jvm deep-c2 \
         duel2-nce duel2-jvm duel2-c2 duel2-nfi
fi
for b in "$@"; do
  scripts/pbatch.sh "$OUT/batches/$b.batch" "results/$b.txt"
  echo "done $b $(date +%T)"
done
python3 -I scripts/tables.py > results/tables.md
