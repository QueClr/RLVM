#!/bin/sh
# bench/workloads/nl-run.sh - run the instrumented nano-lean copy on one export (INSIDE the container).
#   nl-run.sh VARIANT NAME [EXPORT.ndjson]
# VARIANT: di        plain build + per-declaration instructions/ns          -> NAME.di.tsv
#          stats     vstats build, derived rules on (as nano-lean runs)     -> NAME.stats.tsv, NAME.dump.tsv
#          nod       vstats build, derived rules off (NL_NO_DERIVE=1)       -> NAME.nod.tsv, NAME.nod.dump.tsv
# One checking thread (-j 1) so that one session's caches see every declaration; the whole process is
# measured with bench/measure.sh (perf stat instructions:u) -> NAME.VARIANT.measure.json
set -u
root="$(cd "$(dirname "$0")/../.." && pwd)"
variant="$1"; name="$2"; export_file="${3:-$root/work/exports/$name.ndjson}"
out="${NL_OUT:-$root/work/workloads/dyn/nl}"
mkdir -p "$out"
b="$root/work/workloads/build/nano-lean-instrumented"
case "$variant" in
  di)    bin="$b/target/release/nl-fast"; export NL_DECL_LOG="$out/$name.di.tsv" ;;
  stats) bin="$b/target-vstats/release/nl-fast"; export NL_DECL_LOG="$out/$name.stats.tsv" NL_WL_DUMP="$out/$name.dump.tsv" ;;
  nod)   bin="$b/target-vstats/release/nl-fast"; export NL_DECL_LOG="$out/$name.nod.tsv" NL_WL_DUMP="$out/$name.nod.dump.tsv" NL_NO_DERIVE=1 ;;
  *) echo "unknown variant $variant" >&2; exit 64 ;;
esac
"$root/bench/measure.sh" -- "$bin" -q -j 1 ${NL_ARGS:-} "$export_file" > "$out/$name.$variant.measure.json" 2> "$out/$name.$variant.err"
rc=$?
echo "variant=$variant name=$name rc=$rc $(cat "$out/$name.$variant.measure.json" | cut -c1-300)"
tail -2 "$out/$name.$variant.err"
