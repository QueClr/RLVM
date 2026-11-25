#!/usr/bin/env bash
# rlvm-driver/bin/threads-gate.sh - the gate behind the automatic worker count (*agentic-note*: design-architecture-document, 6.8,
# *agentic-note*: design-reconciliation-document, section 0: CPU time first), as one command: measure a distribution for several
# worker counts in the arena-shaped container (8 CPUs, 16 GB, perf stat), print the table
#
#   corpus  launcher  workers  CPU s  wall s  peak RSS  instructions  verdict  CPU vs 1 worker
#
# and, per corpus and launcher, the largest worker count N <= 6 whose total CPU time exceeds that of
# one worker by at most the gate. That N is what Run.autoWorkers(8) should return.
#
#   rlvm-driver/bin/threads-gate.sh [options] EXPORT.ndjson...
#
#   --dist DIR        distribution to measure (default work/dist; e.g. one built before publication
#                     with scripts/build.sh --published --modules rlvm-driver,rlvm-main --dist DIR)
#   --jvm | --native  launcher(s), repeatable (default: both, if built)
#   --threads LIST    worker counts (default 1,2,4,6,8)
#   --repeat K        runs per configuration; the table shows the median by CPU time (default 1)
#   --gate PCT        allowed CPU overhead against one worker in percent (default 5)
#   --set K=V,..      driver settings for every run (environment RLVM_DRIVER, see RunConfig.setAll)
#   --engine NAME     primary engine of every run (-X engine=NAME), e.g. machine; default: the launcher's
#   -X KEY=VALUE      checker setting for every run (repeatable), e.g. -X load=pipelined, or an engine
#                     setting: -X machine.switches=warm,gated,expect
#   --timeout S       per run (default 4200)
#   --lock group|run  take the ladder lock once per corpus and launcher (default: all its worker counts
#                     in a row, minutes for Init / Std / cedar) or around every single run (mathlib:
#                     a run takes minutes, others should get their turn in between)
#   --tag TAG         suffix of the output file
#   --table FILE      print the table of an earlier run (a .jsonl file of this script) and exit
#
# The gate for the machine engine (the native image is its platform; mathlib takes about 10 minutes
# of container time for the five worker counts):
#   rlvm-driver/bin/threads-gate.sh --native --lock run --engine machine [-X machine.switches=...] \
#       work/exports/mathlib.ndjson
#
# The runs take the ladder lock (work/perf-history/.ladder.lock, see --lock), so that two measurements
# do not share the container's eight cores; other people's measurements interleave. The exports must
# lie below the project root. Raw measurements: work/rlvm-driver/gate/<UTC time>[-TAG].jsonl.
# Exit status: 0 if every run produced a measurement and the expected verdict (accept), 1 otherwise.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
dist="$ROOT/work/dist"
modes=()
threads="1,2,4,6,8"
repeat=1
gate=5
settings=""
extra=()
timeout=4200
lockmode=group
tag=""
table=""
exports=()

while [ "$#" -gt 0 ]; do
  case "$1" in
    --dist) dist="$(realpath -m "${2:?--dist needs a directory}")"; shift 2 ;;
    --jvm|--native) modes+=("${1#--}"); shift ;;
    --threads) threads="${2:?}"; shift 2 ;;
    --repeat) repeat="${2:?}"; shift 2 ;;
    --gate) gate="${2:?}"; shift 2 ;;
    --set) settings="${2:?}"; shift 2 ;;
    -X) extra+=(-X "${2:?-X needs KEY=VALUE}"); shift 2 ;;
    --engine) extra+=(-X "engine=${2:?--engine needs a name}"); [ -n "$tag" ] || tag="-$2"; shift 2 ;;
    --timeout) timeout="${2:?}"; shift 2 ;;
    --lock) lockmode="${2:?}"; shift 2 ;;
    --tag) tag="-${2:?}"; shift 2 ;;
    --table) table="${2:?}"; shift 2 ;;
    -h|--help) sed -n '2,38p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*) echo "threads-gate.sh: unknown option $1 (see --help)" >&2; exit 2 ;;
    *) exports+=("$1"); shift ;;
  esac
done

print_table() {
  python3 -I - "$1" "$gate" <<'EOF'
import json, sys
rows = [json.loads(l) for l in open(sys.argv[1]) if l.strip()]
gate = float(sys.argv[2])
groups = {}
for r in rows:
    groups.setdefault((r["corpus"], r["mode"], r.get("settings", ""), r["threads"]), []).append(r)
med = {}
for k, rs in groups.items():
    rs = sorted(rs, key=lambda r: r.get("cpu_time_s") or 0.0)
    med[k] = (rs[len(rs) // 2], len(rs))
print("%-22s %-7s %7s %9s %8s %9s %10s %8s %5s  %s" % ("corpus", "launch", "workers", "CPU s", "wall s", "RSS MB", "instr G", "verdict", "runs", "CPU vs 1 worker"))
verdicts = {0: "accept", 1: "reject", 2: "decline"}
ok = True
for (corpus, mode, settings) in sorted({k[:3] for k in med}):
    ns = sorted(k[3] for k in med if k[:3] == (corpus, mode, settings))
    base = med.get((corpus, mode, settings, 1))
    best = None
    for n in ns:
        r, count = med[(corpus, mode, settings, n)]
        cpu = r.get("cpu_time_s") or 0.0
        ins = r.get("instructions")
        over = ""
        if base and base[0].get("cpu_time_s"):
            pct = 100.0 * (cpu / base[0]["cpu_time_s"] - 1.0)
            over = "%+.1f %%" % pct
            if n <= 6 and pct <= gate and r.get("exit_code") == 0:
                best = n
        if r.get("exit_code") != 0 or r.get("timed_out"):
            ok = False
        print("%-22s %-7s %7d %9.2f %8.2f %9.0f %10s %8s %5d  %s" % (
            corpus + (" [" + settings + "]" if settings else ""), mode, n, cpu, r.get("wall_time_s") or 0.0,
            (r.get("max_rss_bytes") or 0) / 2**20, "n/a" if ins is None else "%.1f" % (ins / 1e9),
            "timeout" if r.get("timed_out") else verdicts.get(r.get("exit_code"), "exit %s" % r.get("exit_code")), count, over))
    if base:
        print("%-22s %-7s gate %.0f %% of CPU time against one worker: largest N <= 6 within the gate = %s" % (
            corpus, mode, gate, best if best is not None else "none"))
sys.exit(0 if ok else 1)
EOF
}

if [ -n "$table" ]; then print_table "$table"; exit $?; fi
[ "${#exports[@]}" -gt 0 ] || { echo "threads-gate.sh: no export given (see --help)" >&2; exit 2; }
if [ "${#modes[@]}" -eq 0 ]; then
  [ ! -x "$dist/bin/rlvm-jvm" ] || modes+=(jvm)
  [ ! -x "$dist/bin/rlvm" ] || modes+=(native)
fi
[ "${#modes[@]}" -gt 0 ] || { echo "threads-gate.sh: nothing built in $dist" >&2; exit 70; }
case "$dist" in "$ROOT"/*) ;; *) echo "threads-gate.sh: $dist is outside $ROOT" >&2; exit 2 ;; esac

out="$ROOT/work/rlvm-driver/gate/$(date -u +%Y%m%dT%H%M%SZ)$tag.jsonl"
mkdir -p "$(dirname "$out")" "$ROOT/work/perf-history"
lock="$ROOT/work/perf-history/.ladder.lock"
case "$lockmode" in group|run) ;; *) echo "threads-gate.sh: --lock group|run" >&2; exit 2 ;; esac
exec 8>>"$lock"
: > "$out"
failed=0
IFS=',' read -r -a thread_list <<< "$threads"
env_prefix="RLVM_STATUS_BASE=10"
[ -z "$settings" ] || env_prefix="$env_prefix RLVM_DRIVER=$settings"
for export_file in "${exports[@]}"; do
  [ -f "$export_file" ] || { echo "threads-gate.sh: no such file: $export_file" >&2; exit 2; }
  export_abs="$(realpath -s "$export_file")"
  case "$export_abs" in "$ROOT"/*) ;; *) echo "threads-gate.sh: $export_abs is outside $ROOT" >&2; exit 2 ;; esac
  corpus="$(basename "$export_abs" .ndjson)"
  for m in "${modes[@]}"; do
    case "$m" in jvm) bin="$dist/bin/rlvm-jvm" ;; native) bin="$dist/bin/rlvm" ;; esac
    [ -x "$bin" ] || { echo "threads-gate.sh: $bin not found" >&2; exit 70; }
    [ "$lockmode" = group ] && flock 8
    for t in "${thread_list[@]}"; do
      cmd="$env_prefix ${bin#$ROOT/} --threads $t \"\$IN\" ${extra[*]:-}; case \$? in 10) exit 0;; 11) exit 1;; 12) exit 2;; *) exit 3;; esac"
      for ((k = 1; k <= repeat; k++)); do
        [ "$lockmode" = run ] && flock 8
        line="$("$ROOT/bench/arena-docker.sh" --no-network --arena-limits "$ROOT/bench/measure.sh" \
                  --require-perf --timeout "$timeout" --cwd "$ROOT" --in "$export_abs" -c "$cmd" 2>/dev/null 8>&- | tail -n 1)" || true
        [ "$lockmode" = run ] && flock -u 8
        case "$line" in
          '{'*) printf '{"corpus": "%s", "mode": "%s", "threads": %s, "run": %s, "settings": "%s", "extra": "%s", %s\n' \
                  "$corpus" "$m" "$t" "$k" "$settings" "${extra[*]:-}" "${line#\{}" >> "$out"
                echo "threads-gate.sh: $corpus $m --threads $t run $k: $(printf '%s' "$line" | python3 -I -c 'import json,sys; r=json.loads(sys.stdin.read()); print("exit %s, cpu %.2f s, wall %.2f s, rss %.0f MB" % (r.get("exit_code"), r.get("cpu_time_s") or 0, r.get("wall_time_s") or 0, (r.get("max_rss_bytes") or 0)/2**20))')" >&2 ;;
          *) failed=1; echo "threads-gate.sh: no measurement for $corpus, $m, $t worker(s) (perf unavailable?)" >&2 ;;
        esac
      done
    done
    [ "$lockmode" = group ] && flock -u 8
  done
done
echo "threads-gate.sh: raw measurements: ${out#$ROOT/}" >&2
print_table "$out" || failed=1
exit "$failed"
