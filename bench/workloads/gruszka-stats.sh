#!/bin/sh
# bench/workloads/gruszka-stats.sh - gruszka's own statistics (knobs build, SK_DECL_INSTR=1) on one export,
# one worker, INSIDE the container. Writes work/workloads/dyn/gruszka/NAME.di.tsv:
#   idx name ns ticks whnf_misses unfolds deq_calls deq_cache_hits infer_misses infer_calls evals interns gr[...]
# (gr = ground machine: runs, answered true, handed to the kernel, kernel steps, machine steps, pairs compared,
#  values imported, values exported) and NAME.tail.txt (the summary lines).
set -u
root="$(cd "$(dirname "$0")/../.." && pwd)"
name="$1"; export_file="${2:-$root/work/exports/$name.ndjson}"
out="$root/work/workloads/dyn/gruszka"; mkdir -p "$out"
cd "$root/work/workloads/build/gruszka-knobs" || exit 70
start=$(date +%s.%N)
SK_DECL_INSTR=1 target/release/gruszka "$export_file" 1 2>&1 >"$out/$name.stdout.txt" | awk -F'\t' -v tail="$out/$name.tail.txt" '
  /^DI/ { match($15, /gr \[[^]]*\]/); g = substr($15, RSTART, RLENGTH);
          for (i = 6; i <= 14; i++) sub(/^[a-z_]+ /, "", $i);
          print $2 "\t" $3 "\t" $5 "\t" $6 "\t" $7 "\t" $8 "\t" $9 "\t" $10 "\t" $11 "\t" $12 "\t" $13 "\t" $14 "\t" g; next }
  { print > tail }' > "$out/$name.di.tsv"
end=$(date +%s.%N)
echo "gruszka name=$name wall_s=$(echo "$end - $start" | bc) verdict=$(cat "$out/$name.stdout.txt" | tail -1)"
