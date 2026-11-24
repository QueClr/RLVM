#!/bin/sh
# bench/workloads/nl-perf-tests.sh - the three instrumented nano-lean variants on arena perf/ tests
# (INSIDE the container). Output: work/workloads/dyn/nl-perf/TEST.{di,stats,nod}.tsv etc.
set -u
root="$(cd "$(dirname "$0")/../.." && pwd)"
export NL_OUT="$root/work/workloads/dyn/nl-perf"
for t in "$@"; do
  f="$root/work/arena-site/tests/good/perf/$t.ndjson"
  [ -f "$f" ] || f="$root/work/arena-site/tests/bad/perf/$t.ndjson"
  for v in di stats nod; do
    "$root/bench/workloads/nl-run.sh" "$v" "$t" "$f"
  done
done
