#!/usr/bin/env bash
# bench/baselines/validate.sh - run baseline checkers over the published arena
# tests (work/arena-site/tests, 230 exports) with instruction counts.
#
#   bench/baselines/validate.sh [-j N] [--tag TAG] [--detach] NAME [NAME...]     (run on the host)
#
# For each NAME (a wrapper bench/baselines/NAME.sh) one container is started,
# sized like the arena runner and without network:
#
#   bench/arena-docker.sh --arena-limits --no-network \
#     bench/run-suite.py --perf --arena-timeouts --timeout 900 -j N --label NAME \
#       --report work/baselines/small/NAME[-TAG].json -- bench/baselines/NAME.sh '"$IN"'
#
# Timeouts are the arena's own (--arena-timeouts: perf/* 40-360 s, init-prelude
# 40 s). The arena has none for the other tests; --timeout 900 is only a safety
# net for those (run-suite's default of 120 s would cut off tenet-jvm on
# bugs/nat-shift-huge, which the arena lets run for 366 s until it gives up).
#
# The checkers run one after the other (they share the 8 performance cores
# that --arena-limits selects). Output: work/logs/baselines/validate-NAME[-TAG].log
# and the report work/baselines/small/NAME[-TAG].json; afterwards
# bench/baselines/summary.py prints the table of all reports.
#
#   -j N       tests in parallel inside the container (default 1). Instruction
#              counts do not depend on it, but memory does: the container
#              has the arena's 16 GB, and several baselines need 8-12 GB on
#              single tests (tenet-jvm and nano-lean on bugs/nat-shift-huge,
#              mathgraph on perf/magma-list-pair-n21), so two of those at
#              once are killed by the kernel. Keep 1 unless you know the
#              checker is small (gruszka, pestka: about 1 GB).
#   --tag TAG  suffix for report and log, e.g. to keep a second run
#   --detach   start the first NAME detached and return (for one long job;
#              wait with bench/arena-docker.sh --wait LOG)
#
# Exit status: 0 if every run-suite run exited 0 (nothing incorrect, in error
# or timed out), 1 otherwise. A baseline that fails tests still gets a report.
set -uo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT="$(cd "$SELF_DIR/../.." && pwd -P)"
DOCKER="$ROOT/bench/arena-docker.sh"
LOGS="$ROOT/work/logs/baselines"
OUT="$ROOT/work/baselines/small"

jobs=1
tag=""
detach=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    -j) jobs="${2:?-j needs a number}"; shift 2 ;;
    --tag) tag="-${2:?--tag needs a value}"; shift 2 ;;
    --detach) detach=1; shift ;;
    -h|--help) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 0 ;;
    --) shift; break ;;
    -*) echo "unknown option: $1" >&2; exit 2 ;;
    *) break ;;
  esac
done
[ "$#" -gt 0 ] || { echo "usage: $0 [-j N] [--tag TAG] [--detach] NAME [NAME...]" >&2; exit 2; }

mkdir -p "$LOGS" "$OUT"
rc=0
for name in "$@"; do
  wrapper="$ROOT/bench/baselines/$name.sh"
  [ -x "$wrapper" ] || { echo "$name: no wrapper $wrapper" >&2; rc=1; continue; }
  log="$LOGS/validate-$name$tag.log"
  cmd=("$ROOT/bench/run-suite.py" --perf --arena-timeouts --timeout 900 -j "$jobs" --label "$name"
       --report "$OUT/$name$tag.json" -- "$wrapper" '"$IN"')
  if [ "$detach" = 1 ]; then
    "$DOCKER" --arena-limits --no-network --detach "$log" --name "rlvm-baseline-validate-$name$tag" -w "$ROOT" "${cmd[@]}"
    exit $?
  fi
  echo "== $name (log: $log)"
  "$DOCKER" --arena-limits --no-network --name "rlvm-baseline-validate-$name$tag" -w "$ROOT" "${cmd[@]}" > "$log" 2>&1 < /dev/null
  status=$?
  grep -E '^SUMMARY' "$log" || tail -n 3 "$log"
  [ "$status" = 0 ] || rc=1
done
exit "$rc"
