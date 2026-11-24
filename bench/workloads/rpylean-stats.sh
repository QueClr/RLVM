#!/bin/sh
# bench/workloads/rpylean-stats.sh - rpylean's own statistics facilities on one export
# (runs INSIDE the rlvm-arena container):
#   --stats        reduction counts: iota per recursor, delta per constant, native Nat ops, def_eq totals
#   --print jsonl  one JSON line per declaration with its wall time
# usage: rpylean-stats.sh CORPUS    (reads work/exports/CORPUS.ndjson, writes work/workloads/dyn/rpylean/)
set -u
root="$(cd "$(dirname "$0")/../.." && pwd)"
c="$1"
out="$root/work/workloads/dyn/rpylean"
mkdir -p "$out"
cd "$root/work/arena-checkers/_build/checkers/rpylean/src" || exit 70
start=$(date +%s.%N)
./rpylean-c check --stats --print jsonl "$root/work/exports/$c.ndjson" > "$out/$c.decls.jsonl" 2> "$out/$c.stats.txt"
rc=$?
end=$(date +%s.%N)
echo "corpus=$c exit=$rc wall_s=$(echo "$end - $start" | bc)" > "$out/$c.run.txt"
cat "$out/$c.run.txt"
