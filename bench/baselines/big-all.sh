#!/usr/bin/env bash
# bench/baselines/big-all.sh - the complete series of baseline measurements on
# the big corpora, as run for bench/baselines.json and *agentic-note*: study-baselines.
#
#   bench/baselines/big-all.sh [STAGE...]            (run on the host; default: all stages)
#
# Stages (each is a list of bench/baselines/big.sh calls; runs are sequential,
# one container per run, --arena-limits --no-network):
#
#   mathlib-fast-1   mathlib, run 1 of the six checkers that need < 5 min
#   small            init and std, 3 runs of all ten baselines
#   mathlib-fast-23  mathlib, runs 2 and 3 of the six fast checkers
#   threads          mathlib with 1, 2 and 8 worker threads for nano-lean,
#                    gruszka and sokonanoda (the arena's setting is 4)
#   mathlib-slow     mathlib, one run of nanoda, tenet-jvm-c2, tenet-jvm (about 45 min)
#   mathlib-official mathlib, one run of the official kernel (about 30 min). It is
#                    single-threaded and deterministic to 0.05 %, so on
#                    2026-11-24 it ran next to the other stages on CPUs 18,19
#                    (two performance cores outside the --arena-limits set):
#                    OFFICIAL_CPUSET=18,19 bench/baselines/big-all.sh mathlib-official
#
# Results accumulate in work/baselines/big/runs.jsonl; afterwards
#   bench/baselines/big-summary.py        writes bench/baselines.json and prints the tables.
set -u
cd "$(dirname "${BASH_SOURCE[0]}")/../.." || exit 1
B=bench/baselines/big.sh
C=work/arena-checkers/_build/checkers
FAST="nano-lean gruszka sokonanoda pestka rpylean mathgraph"
SLOW="nanoda official tenet-jvm-c2 tenet-jvm"
SLOW_MATHLIB="nanoda tenet-jvm-c2 tenet-jvm"

stage() {
  case "$1" in
    mathlib-fast-1)  $B --corpora mathlib --runs 1 $FAST ;;
    small)           $B --corpora "init std" --runs 3 $FAST $SLOW ;;
    mathlib-fast-23) $B --corpora mathlib --runs 2 --first-run 2 $FAST ;;
    threads)
      for n in 1 2 8; do
        $B --corpora mathlib --label nano-lean --variant "threads-$n" --cwd $C/nano-lean/src \
           --cmd "target/release/nl-fast -q -j $n \"\$IN\""
        $B --corpora mathlib --label gruszka --variant "threads-$n" --cwd $C/gruszka/src \
           --cmd "scripts/arena-run \"\$IN\" $n"
        $B --corpora mathlib --label sokonanoda --variant "threads-$n" --cwd $C/sokonanoda/src \
           --cmd "target/release/sokonanoda --stdin --nat-extension --string-extension --axiom-allow-all --threads $n < \"\$IN\""
      done ;;
    mathlib-slow)    $B --corpora mathlib --runs 1 $SLOW_MATHLIB ;;
    mathlib-official)
      if [ -n "${OFFICIAL_CPUSET:-}" ]; then $B --corpora mathlib --runs 1 --cpuset "$OFFICIAL_CPUSET" official
      else $B --corpora mathlib --runs 1 official; fi ;;
    *) echo "big-all.sh: unknown stage $1" >&2; return 2 ;;
  esac
}

[ "$#" -gt 0 ] || set -- mathlib-fast-1 small mathlib-fast-23 threads mathlib-slow mathlib-official
rc=0
for s in "$@"; do
  echo "=== stage $s  $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  stage "$s" || rc=1
done
echo "=== done  $(date -u +%Y-%m-%dT%H:%M:%SZ)  rc=$rc"
exit "$rc"
