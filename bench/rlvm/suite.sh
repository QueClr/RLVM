#!/usr/bin/env bash
# bench/rlvm/suite.sh - run bench/run-suite.py over the published arena tests with RLVM.
#
#   bench/rlvm/suite.sh [--host|--docker] [--native|--jvm|--pgo] [--threads N] [-j JOBS]
#                       [--tag TAG] [--detach] [-- run-suite.py options...]          (run on the host)
#
#   --host      (default) run on the host: verdicts, wall time, CPU time and max RSS; no instruction
#               counts (the host cannot open perf counters)
#   --docker    run in the rlvm-arena container, sized like the arena runner (8 performance cores,
#               16 GB, no network) with `perf stat`: adds instruction counts = the arena's metric
#   --native | --jvm | --pgo     which launcher of work/dist (default --native; see run.sh)
#   --threads N                  worker threads for RLVM (default: the yaml's run line)
#   -j JOBS     tests in parallel (default: 8 on the host, 1 in the container, where wall time and
#               memory per test should be undisturbed; instruction counts do not depend on it)
#   --tag TAG   suffix for the report name
#   --detach    (--docker only) start the container in the background and return; the log is
#               work/logs/rlvm/suite-<label>.log, wait with bench/arena-docker.sh --wait LOG
#   -- ...      passed to run-suite.py, e.g. -- --only 'tutorial/*'   or   -- --tests work/exports/big
#
# The checker command given to run-suite.py is the arena's own run line (scripts/arena/rlvm.yaml),
# with cwd = the project root, exactly as bench/rlvm/run.sh --print shows it: no wrapper script runs
# inside the measurement.
#
# Timeouts are the arena's (--arena-timeouts); --timeout 900 covers the tests the arena gives none.
# Report: work/rlvm/suite/<label>.json with label = rlvm-<mode>-t<threads>-<host|docker>[-TAG].
# Exit status: run-suite.py's (0 = nothing incorrect, in error or timed out; declines are allowed).
set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT="$(cd "$SELF_DIR/../.." && pwd -P)"

where=host
mode=native
threads=""
jobs=""
tag=""
detach=0
passthrough=()

while [ "$#" -gt 0 ]; do
  case "$1" in
    --host|--docker) where="${1#--}"; shift ;;
    --native|--jvm|--pgo) mode="${1#--}"; shift ;;
    --threads) threads="${2:?--threads needs a number}"; shift 2 ;;
    -j|--jobs) jobs="${2:?-j needs a number}"; shift 2 ;;
    --tag) tag="-${2:?--tag needs a value}"; shift 2 ;;
    --detach) detach=1; shift ;;
    -h|--help) sed -n '2,27p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    --) shift; passthrough=("$@"); break ;;
    *) echo "suite.sh: unknown option: $1 (see --help)" >&2; exit 2 ;;
  esac
done

run_args=("--$mode")
[ -z "$threads" ] || run_args+=(--threads "$threads")
cmd="$("$SELF_DIR/run.sh" "${run_args[@]}" --print)"
# --pgo = the executable of the optional Oracle GraalVM distribution (bench/rlvm/run.sh).
case "$mode" in native) binary=work/dist/bin/rlvm ;; jvm) binary=work/dist/bin/rlvm-jvm ;; pgo) binary=work/dist-oracle-pgo/bin/rlvm ;; esac
[ -x "$ROOT/$binary" ] || { echo "suite.sh: $binary not found; build it with scripts/build.sh (--dev for the JVM launcher, --oracle-pgo for the optional Oracle build)" >&2; exit 70; }

t="$(printf '%s' "$cmd" | sed -n -E 's/.*--threads ([0-9]+).*/\1/p')"
label="rlvm-$mode-t${t:-auto}-$where$tag"
report="$ROOT/work/rlvm/suite/$label.json"
mkdir -p "$ROOT/work/rlvm/suite" "$ROOT/work/logs/rlvm"

suite=("$ROOT/bench/run-suite.py" --arena-timeouts --timeout 900 --cwd "$ROOT" --label "$label" --report "$report")
echo "suite.sh: $where, checker command: $cmd" >&2

if [ "$where" = host ]; then
  [ "$detach" = 0 ] || { echo "suite.sh: --detach needs --docker" >&2; exit 2; }
  exec "${suite[@]}" -j "${jobs:-8}" "${passthrough[@]}" -- "$cmd"
fi

docker=("$ROOT/bench/arena-docker.sh" --arena-limits --no-network)
if [ "$detach" = 1 ]; then
  docker+=(--detach "$ROOT/work/logs/rlvm/suite-$label.log")
fi
exec "${docker[@]}" "${suite[@]}" --perf -j "${jobs:-1}" "${passthrough[@]}" -- "$cmd"
