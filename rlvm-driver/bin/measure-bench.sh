#!/usr/bin/env bash
# rlvm-driver/bin/measure-bench.sh - one measured run of rlvm.driver.DriverBench (the driver with a
# Wiring equal to rlvm.main.MainWiring and the reference kernel as primary engine) in the
# arena-shaped container: CPU time, wall clock, peak RSS, instructions, like bench/rlvm/measure.sh
# measures the launcher. For what the launcher cannot run yet (a driver that is not published, a
# load mode rlvm-main does not wire).
#
#   rlvm-driver/bin/measure-bench.sh [--cp TAG] [--label TEXT] [--out FILE.jsonl] EXPORT.ndjson [DriverBench options]
#
#   --cp TAG      frozen class path work/rlvm-driver/cp-TAG (default: freeze the current target/ now)
#   --label TEXT  copied into the JSON line
#   --out FILE    append the JSON line to FILE (default work/rlvm-driver/gate/bench.jsonl)
#   e.g.  measure-bench.sh work/exports/init.ndjson -j 8 --pipelined
#
# JVM flags are the launcher's (work/dist/bin/rlvm-jvm). The run takes the ladder lock
# (work/perf-history/.ladder.lock). Prints the JSON line and a one-line summary.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
tag=""
label=""
out="$ROOT/work/rlvm-driver/gate/bench.jsonl"
while [ "$#" -gt 0 ]; do
  case "$1" in
    --cp) tag="${2:?}"; shift 2 ;;
    --label) label="${2:?}"; shift 2 ;;
    --out) out="$(realpath -m "${2:?}")"; shift 2 ;;
    -h|--help) sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) break ;;
  esac
done
[ "$#" -ge 1 ] || { echo "measure-bench.sh: no export given" >&2; exit 2; }
export_abs="$(realpath -s "$1")"; shift
case "$export_abs" in "$ROOT"/*) ;; *) echo "measure-bench.sh: $export_abs is outside $ROOT" >&2; exit 2 ;; esac
if [ -z "$tag" ]; then
  tag="mb-$(date -u +%H%M%S)"
  "$ROOT/rlvm-driver/bin/freeze-cp.sh" "$tag" > /dev/null
fi
cp="$(cat "$ROOT/work/rlvm-driver/cp-$tag/cp.txt")"
java="$ROOT/.toolchain/graalvm-ce-25/bin/java"
flags="-XX:+UseSerialGC -XX:-UseJVMCICompiler -XX:CICompilerCount=2 -XX:+ExitOnOutOfMemoryError -Xms13g -Xmx13g -Xmn1g"
cmd="$java $flags -cp $cp rlvm.driver.DriverBench $* \"\$IN\" > /dev/null"
mkdir -p "$(dirname "$out")" "$ROOT/work/perf-history"
line="$(flock "$ROOT/work/perf-history/.ladder.lock" "$ROOT/bench/arena-docker.sh" --no-network --arena-limits \
  "$ROOT/bench/measure.sh" --require-perf --timeout 4200 --cwd "$ROOT" --in "$export_abs" -c "$cmd" 2>/dev/null | tail -n 1)"
case "$line" in
  '{'*) ;;
  *) echo "measure-bench.sh: no measurement (perf unavailable?)" >&2; exit 1 ;;
esac
printf '{"corpus": "%s", "bench": "%s", "label": "%s", %s\n' "$(basename "$export_abs" .ndjson)" "$*" "$label" "${line#\{}" | tee -a "$out"
printf '%s' "$line" | python3 -I -c 'import json,sys; r=json.loads(sys.stdin.read()); print("measure-bench: exit %s, cpu %.2f s, wall %.2f s, rss %.0f MB, instr %.1f G" % (r.get("exit_code"), r.get("cpu_time_s") or 0, r.get("wall_time_s") or 0, (r.get("max_rss_bytes") or 0)/2**20, (r.get("instructions") or 0)/1e9))' >&2
