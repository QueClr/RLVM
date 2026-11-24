#!/usr/bin/env bash
# bench/baselines/big.sh - measure checkers on the big corpora (init, std,
# mathlib, ...) the way the arena does, one run per container.
#
#   bench/baselines/big.sh [options] CHECKER [CHECKER...]                (run on the host)
#   bench/baselines/big.sh [options] --label NAME --cmd 'COMMAND "$IN"' [--cwd DIR]
#
# CHECKER is a baseline with a wrapper bench/baselines/CHECKER.sh (nano-lean,
# gruszka, sokonanoda, pestka, rpylean, mathgraph, nanoda, official,
# tenet-jvm, tenet-jvm-c2). With --label/--cmd any command is measured the
# same way, e.g. RLVM:
#
#   bench/baselines/big.sh --runs 3 --label rlvm --cmd 'java -jar build/rlvm.jar "$IN"'
#
# Every run is one fresh container sized like the arena runner and without
# network, with nothing else in it:
#
#   bench/arena-docker.sh --arena-limits --no-network bench/measure.sh \
#       --timeout T --in work/exports/CORPUS.ndjson -o RAW.json -c 'bench/baselines/CHECKER.sh "$IN"'
#
# Runs are strictly sequential (one checker at a time on the 8 performance
# cores that --arena-limits selects).
#
# Options:
#   --corpora 'A B ...'   exports work/exports/NAME.ndjson (default: init std mathlib)
#   --runs N              runs per (checker, corpus) (default 1)
#   --first-run K         number the runs from K (default 1), to add runs later
#   --timeout-factor F    timeout = F x the arena's timeout of the corpus
#                         (default 2; the arena's: init 370 s, std 600 s,
#                         mathlib 4120 s, from work/exports/all/manifest.json)
#   --variant NAME        tag for runs with a non-default configuration
#                         (default: "arena"); kept in the record
#   --out DIR             default work/baselines/big
#   --label NAME --cmd CMD [--cwd DIR]
#                         measure CMD (sh -c, export path in $IN) under the
#                         name NAME instead of a baseline wrapper
#   --env VAR=VALUE       extra environment variable for the command (repeatable)
#   --cpuset LIST         instead of --arena-limits: these CPUs and 16 GB. Only for
#                         a single-threaded native checker that does not size
#                         itself by the machine (used once: the official kernel
#                         on mathlib, 30 min, on two cores of its own next to
#                         the series); recorded in the "container" field
#
# Output: one JSON line per run appended to DIR/runs.jsonl (the fields of
# measure.sh plus checker, variant, corpus, run, command, timeout_s,
# arena_timeout_s, started_utc, host_loadavg_start/end, image), the raw
# measurement in DIR/raw/, and the checker's own output in DIR/logs/.
# bench/baselines/big-summary.py turns runs.jsonl into tables and
# bench/baselines.json.
#
# Instruction counts do not depend on what else the host is doing; wall and
# CPU times do (other containers may share the same 8 cores), which is why
# the load average is recorded with every run.
set -uo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT="$(cd "$SELF_DIR/../.." && pwd -P)"
DOCKER="$ROOT/bench/arena-docker.sh"

corpora="init std mathlib"
runs=1
first_run=1
factor=2
variant="arena"
out="$ROOT/work/baselines/big"
label=""
cmd=""
cwd=""
envs=()
limits=(--arena-limits)

usage() { awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; }

while [ "$#" -gt 0 ]; do
  case "$1" in
    --corpora) corpora="${2:?}"; shift 2 ;;
    --runs) runs="${2:?}"; shift 2 ;;
    --first-run) first_run="${2:?}"; shift 2 ;;
    --timeout-factor) factor="${2:?}"; shift 2 ;;
    --variant) variant="${2:?}"; shift 2 ;;
    --out) out="$(realpath -m "${2:?}")"; shift 2 ;;
    --label) label="${2:?}"; shift 2 ;;
    --cmd) cmd="${2:?}"; shift 2 ;;
    --cwd) cwd="$(realpath -m "${2:?}")"; shift 2 ;;
    --env) envs+=("${2:?}"); shift 2 ;;
    --cpuset) limits=(--cpuset-cpus "${2:?}" --memory 16g --docker-arg --memory-swap=16g); shift 2 ;;
    -h|--help) usage; exit 0 ;;
    --) shift; break ;;
    -*) echo "big.sh: unknown option $1" >&2; exit 2 ;;
    *) break ;;
  esac
done

if [ -n "$cmd" ]; then
  [ -n "$label" ] || { echo "big.sh: --cmd needs --label" >&2; exit 2; }
  [ "$#" -eq 0 ] || { echo "big.sh: give either checkers or --label/--cmd" >&2; exit 2; }
  checkers=("$label")
else
  [ "$#" -gt 0 ] || { usage; exit 2; }
  checkers=("$@")
  for c in "${checkers[@]}"; do
    [ -x "$SELF_DIR/$c.sh" ] || { echo "big.sh: no wrapper bench/baselines/$c.sh" >&2; exit 2; }
  done
fi
case "$out/" in "$ROOT/work/"*) ;; *) echo "big.sh: --out must be below work/" >&2; exit 2 ;; esac

mkdir -p "$out/raw" "$out/logs"
image="$(docker image inspect --format '{{.Id}}' "${RLVM_ARENA_IMAGE:-rlvm-arena:latest}" 2>/dev/null || echo unknown)"

arena_timeout() {
  python3 -I - "$ROOT/work/exports/all/manifest.json" "$1" <<'PY'
import json, sys
try:
    tests = json.load(open(sys.argv[1]))["tests"]
except Exception:
    tests = []
for t in tests:
    if t.get("name") == sys.argv[2] and t.get("timeout"):
        print(t["timeout"]); break
else:
    print(0)
PY
}

current=""
cleanup() { [ -z "$current" ] || docker kill "$current" >/dev/null 2>&1; }
trap cleanup EXIT
trap 'exit 130' INT TERM

rc_all=0
for corpus in $corpora; do
  export_file="$ROOT/work/exports/$corpus.ndjson"
  [ -f "$export_file" ] || { echo "big.sh: no such export: $export_file" >&2; rc_all=1; continue; }
  at="$(arena_timeout "$corpus")"
  if [ "$at" -gt 0 ]; then timeout="$(python3 -I -c "print(int($at * $factor))")"; else timeout=0; fi
  for c in "${checkers[@]}"; do
    if [ -n "$cmd" ]; then run_cmd="$cmd"; else run_cmd="bench/baselines/$c.sh \"\$IN\""; fi
    for ((i = first_run; i < first_run + runs; i++)); do
      tag="$c.$variant.$corpus.$i"
      raw="$out/raw/$tag.json"
      log="$out/logs/$tag.log"
      rm -f "$raw"
      current="rlvm-big-$$-${tag//[^A-Za-z0-9_.-]/_}"
      margs=(--in "$export_file" -o "$raw")
      [ "$timeout" -gt 0 ] && margs+=(--timeout "$timeout")
      [ -n "$cwd" ] && margs+=(--cwd "$cwd")
      for e in "${envs[@]}"; do margs+=(--env "$e"); done
      started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
      load0="$(cut -d' ' -f1-3 /proc/loadavg)"
      printf '%s  %-14s %-10s %-8s run %d ... ' "$started" "$c" "$variant" "$corpus" "$i"
      # in the background and waited for, so that a signal to this script is
      # handled at once (the EXIT trap then kills the container)
      "$DOCKER" "${limits[@]}" --no-network --name "$current" \
          bench/measure.sh "${margs[@]}" -c "$run_cmd" > "$log" 2>&1 < /dev/null &
      wait $!
      drc=$?
      current=""
      load1="$(cut -d' ' -f1-3 /proc/loadavg)"
      CHECKER="$c" VARIANT="$variant" CORPUS="$corpus" RUN="$i" CMD="$run_cmd" TIMEOUT="$timeout" \
      ARENA_TIMEOUT="$at" STARTED="$started" LOAD0="$load0" LOAD1="$load1" IMAGE="$image" DRC="$drc" \
      EXPORT="$export_file" CWD="$cwd" ENVS="${envs[*]:-}" LOG="$log" LIMITS="${limits[*]}" \
      python3 -I - "$raw" "$out/runs.jsonl" <<'PY' || rc_all=1
import json, os, sys
raw, runs = sys.argv[1:3]
e = os.environ
rec = {"checker": e["CHECKER"], "variant": e["VARIANT"], "corpus": e["CORPUS"], "run": int(e["RUN"]),
       "command": e["CMD"], "cwd": e["CWD"] or None, "env": e["ENVS"].split() if e["ENVS"] else [],
       "export": os.path.relpath(e["EXPORT"]), "export_bytes": os.path.getsize(e["EXPORT"]),
       "timeout_s": int(e["TIMEOUT"]) or None, "arena_timeout_s": int(e["ARENA_TIMEOUT"]) or None,
       "started_utc": e["STARTED"], "host_loadavg_start": [float(x) for x in e["LOAD0"].split()],
       "host_loadavg_end": [float(x) for x in e["LOAD1"].split()],
       "container": "bench/arena-docker.sh %s --no-network" % e["LIMITS"], "image": e["IMAGE"],
       "docker_exit": int(e["DRC"]), "log": os.path.relpath(e["LOG"])}
try:
    rec.update(json.load(open(raw)))
except Exception as exc:
    rec["measure_error"] = f"no measurement: {exc}"
with open(runs, "a") as handle:
    handle.write(json.dumps(rec) + "\n")
ins = rec.get("instructions")
print("exit %s  %s instr  %s virt s  wall %s s  cpu %s s  rss %s MiB%s" % (
    rec.get("exit_code"), "%.4e" % ins if ins else "-", "%.2f" % (ins / 6e9) if ins else "-",
    "%.1f" % rec["wall_time_s"] if rec.get("wall_time_s") is not None else "-",
    "%.1f" % rec["cpu_time_s"] if rec.get("cpu_time_s") is not None else "-",
    "%.0f" % (rec["max_rss_bytes"] / 2**20) if rec.get("max_rss_bytes") else "-",
    "  TIMEOUT" if rec.get("timed_out") else ""))
sys.exit(0 if "measure_error" not in rec else 1)
PY
    done
  done
done
exit "$rc_all"
