#!/usr/bin/env bash
# bench/rlvm/measure.sh - instructions, CPU time, wall time and max RSS of RLVM on one export, for
# several worker-thread counts, measured like the arena measures (perf stat + GNU time) in the
# rlvm-arena container limited to 8 performance cores and 16 GB like the arena runner.
#
#   bench/rlvm/measure.sh [options] EXPORT.ndjson                                  (run on the host)
#
#   --threads LIST   comma-separated worker counts (default: 1,4,8)
#   --native | --jvm | --pgo     launcher(s) to measure; repeatable (default: --native)
#   --all            every launcher present in work/dist
#   --repeat K       measure each configuration K times (default 1; a JVM varies by about 2% per run,
#                    a native image far less; see bench/README.md)
#   --timeout S      per-run timeout in seconds (default 4200, a little over the arena's for mathlib)
#   --tag TAG        suffix for the output file
#   --no-limits      do not restrict the container to 8 CPUs / 16 GB (not comparable with the arena)
#
# One container runs the whole matrix. Each run executes the arena's run line (scripts/arena/rlvm.yaml,
# see bench/rlvm/run.sh --print) under bench/measure.sh; the JSON lines go to
# work/rlvm/measure/<export>-<UTC time>[-TAG].jsonl and a table is printed:
#
#   mode    thr  exit  instr G  virtual s  cpu s  wall s  cpu/wall  RSS MB
#
# virtual s = instructions / 6e9 is what the leaderboard ranks; wall s with 4 or 8 threads is what a
# user waits for. The export must lie below the project root (e.g. in work/), which is all the
# container can see.
# Exit status: 0 if every run produced a measurement, 1 otherwise; 70 if nothing is built.
set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT="$(cd "$SELF_DIR/../.." && pwd -P)"

threads="1,4,8"
modes=()
repeat=1
timeout=4200
tag=""
limits=1
inside=0
out=""
export_file=""

while [ "$#" -gt 0 ]; do
  case "$1" in
    --threads) threads="${2:?--threads needs a list}"; shift 2 ;;
    --native|--jvm|--pgo) modes+=("${1#--}"); shift ;;
    --all) modes=(all); shift ;;
    --repeat) repeat="${2:?--repeat needs a number}"; shift 2 ;;
    --timeout) timeout="${2:?--timeout needs seconds}"; shift 2 ;;
    --tag) tag="-${2:?--tag needs a value}"; shift 2 ;;
    --no-limits) limits=0; shift ;;
    --in-container) inside=1; out="${2:?}"; shift 2 ;;   # internal: the part that runs in the container
    -h|--help) sed -n '2,26p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*) echo "measure.sh: unknown option: $1 (see --help)" >&2; exit 2 ;;
    *)
      [ -z "$export_file" ] || { echo "measure.sh: more than one export given" >&2; exit 2; }
      export_file="$1"; shift ;;
  esac
done
[ -n "$export_file" ] || { echo "measure.sh: no export given (see --help)" >&2; exit 2; }
[ -f "$export_file" ] || { echo "measure.sh: no such file: $export_file" >&2; exit 2; }
export_abs="$(realpath -s "$export_file")"
case "$export_abs" in
  "$ROOT"/*) ;;
  *) echo "measure.sh: $export_abs is outside $ROOT; the container cannot see it (copy or link it into work/)" >&2; exit 2 ;;
esac

if [ "${#modes[@]}" -eq 0 ]; then modes=(native); fi
if [ "${modes[0]}" = all ]; then
  modes=()
  [ ! -x "$ROOT/work/dist/bin/rlvm" ] || modes+=(native)
  [ ! -x "$ROOT/work/dist/bin/rlvm-pgo" ] || modes+=(pgo)
  [ ! -x "$ROOT/work/dist/bin/rlvm-jvm" ] || modes+=(jvm)
fi
[ "${#modes[@]}" -gt 0 ] || { echo "measure.sh: nothing built in work/dist; run scripts/build.sh" >&2; exit 70; }
for m in "${modes[@]}"; do
  case "$m" in native) b=rlvm ;; jvm) b=rlvm-jvm ;; pgo) b=rlvm-pgo ;; esac
  [ -x "$ROOT/work/dist/bin/$b" ] || { echo "measure.sh: work/dist/bin/$b not found; build it with scripts/build.sh" >&2; exit 70; }
done

# ------------------------------------------------------------------------------------------------
if [ "$inside" = 0 ]; then
  name="$(basename "$export_abs" .ndjson)"
  out="$ROOT/work/rlvm/measure/$name-$(date -u +%Y%m%dT%H%M%SZ)$tag.jsonl"
  mkdir -p "$(dirname "$out")"
  docker=("$ROOT/bench/arena-docker.sh" --no-network)
  [ "$limits" = 0 ] || docker+=(--arena-limits)
  args=(--in-container "$out" --threads "$threads" --repeat "$repeat" --timeout "$timeout")
  for m in "${modes[@]}"; do args+=("--$m"); done
  echo "measure.sh: $export_abs ($(stat -L -c %s "$export_abs") bytes); threads $threads; modes ${modes[*]}; $([ "$limits" = 1 ] && echo '8 CPUs / 16 GB' || echo 'no limits')" >&2
  rc=0
  "${docker[@]}" "$SELF_DIR/measure.sh" "${args[@]}" "$export_abs" || rc=$?
  echo "measure.sh: raw measurements: $out" >&2
  exit "$rc"
fi

# ------------------------------------------------------------------------------------------------
# Inside the container.
: > "$out"
failed=0
IFS=',' read -r -a thread_list <<< "$threads"
for m in "${modes[@]}"; do
  for t in "${thread_list[@]}"; do
    cmd="$("$SELF_DIR/run.sh" "--$m" --threads "$t" --print)"
    for ((k = 1; k <= repeat; k++)); do
      line="$("$ROOT/bench/measure.sh" --require-perf --timeout "$timeout" --cwd "$ROOT" --in "$export_abs" -c "$cmd" 2>/dev/null | tail -n 1)" || true
      case "$line" in
        '{'*) printf '{"mode": "%s", "threads": %s, "run": %s, "command": "%s", %s\n' \
                "$m" "$t" "$k" "$(printf '%s' "$cmd" | sed 's/\\/\\\\/g; s/"/\\"/g')" "${line#\{}" >> "$out" ;;
        *) failed=1; echo "measure.sh: no measurement for mode $m, $t thread(s) (perf unavailable?)" >&2 ;;
      esac
    done
  done
done

python3 -I - "$out" <<'EOF'
import json, sys
rows = [json.loads(l) for l in open(sys.argv[1]) if l.strip()]
print("%-7s %4s %5s %10s %10s %8s %8s %9s %8s" % ("mode", "thr", "exit", "instr G", "virtual s", "cpu s", "wall s", "cpu/wall", "RSS MB"))
for r in rows:
    ins = r.get("instructions")
    wall = r.get("wall_time_s") or 0.0
    cpu = r.get("cpu_time_s") or 0.0
    print("%-7s %4d %5s %10s %10s %8.2f %8.2f %9s %8.0f" % (
        r["mode"], r["threads"], "t/o" if r.get("timed_out") else r["exit_code"],
        "n/a" if ins is None else ("%.3f" if ins >= 1e9 else "%.6f") % (ins / 1e9),
        "n/a" if ins is None else ("%.3f" if ins >= 6e9 else "%.6f") % (ins / 6e9),
        cpu, wall, "%.2f" % (cpu / wall) if wall > 0 else "-",
        (r.get("max_rss_bytes") or 0) / 2**20))
    if r.get("counter_coverage", 1.0) < 0.999:
        print("        WARNING: counter coverage %.3f, the instruction count is too low" % r["counter_coverage"])
EOF
exit "$failed"
