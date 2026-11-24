#!/bin/sh
# bench/workloads/nl-perf-record.sh - sample the frame-pointer build of the nano-lean copy by user
# instructions with call graphs and aggregate by phase (INSIDE the container; one core).
#   nl-perf-record.sh NAME [EXPORT.ndjson]   -> work/workloads/perf/NAME.phases.txt
# NL_ARGS="--only FILE" restricts checking to the declarations named in FILE (the project's own ones).
set -u
root="$(cd "$(dirname "$0")/../.." && pwd)"
name="$1"; export_file="${2:-$root/work/exports/$name.ndjson}"
out="$root/work/workloads/perf"; mkdir -p "$out"
perf record -q -e instructions:u -c "${PERIOD:-2000003}" --call-graph fp -o "$out/$name.perf.data" -- \
  "$root/work/workloads/build/nano-lean-instrumented/target-fp/release/nl-fast" -q -j 1 ${NL_ARGS:-} "$export_file" > "$out/$name.record.log" 2>&1
perf script -i "$out/$name.perf.data" -F ip,sym 2>/dev/null | python3 -I "$root/bench/workloads/perf-phases.py" "$name" "$out/$name.folded" > "$out/$name.phases.txt"
gzip -f "$out/$name.folded"
rm -f "$out/$name.perf.data"
echo "perf-record name=$name $(head -1 "$out/$name.phases.txt")"
