#!/usr/bin/env bash
# scripts/slice.sh - the end-to-end table: run the published arena tests through the distribution
# and write a pass/fail table that module owners can read (work/status/slice.md).
#
#   scripts/slice.sh [options]
#
#   --probe             do not use the distribution and the driver at all: run the steward's
#                       in-process probe (rlvm-main test ProbeSliceTest: wiring load + environment
#                       pass + admission + reference engine on one thread, jars as published in
#                       .toolchain/m2) and write work/status/slice-probe[-all].md. Only the owner of
#                       rlvm-main may use it (it runs that module's test tree). With --all: 230 tests
#   --all               every published test (230) instead of the tutorial tests (141)
#   --only GLOB         tests to run (repeatable; default 'tutorial/*'; names as in run-suite.py)
#   --engine NAME       -X engine=NAME (default reference; `default` = no -X engine: the
#                       distribution's own default engine)
#   --load MODE         -X load=MODE (default batch; `default` = no -X load: the checker's own rule,
#                       pipelined for two or more workers on inputs of 8 MiB and more)
#   --threads N         worker threads (default 1: the vertical slice)
#   --native | --jvm    launcher of work/dist (default: native if built, else JVM)
#   -j JOBS             tests in parallel (default 8)
#   --no-build          use work/dist as it is (default: rebuild it from the published jars first,
#                       scripts/dev.sh dist, a few seconds)
#   --dist DIR          another distribution directory (implies --no-build), e.g. one built before
#                       publication with scripts/build.sh --published --modules A,B --dist DIR
#   --out FILE          the markdown table (default work/status/slice.md; slice-all.md with --all)
#   --tag TAG           suffix of the report name
#   --no-arena-timeouts do not kill a test at its arena time limit (one hour instead): for a VERDICT
#                       guard on a loaded machine or on the efficiency cores, where a test that
#                       needs 10 s of its 70 s can take minutes. The table then says so; the margins
#                       to the arena's limits must be read from a run on a quiet machine
#   -X KEY=VALUE        extra checker setting (repeatable)
#
# The checker command is the arena's run line (scripts/arena/rlvm.yaml) with the thread count
# replaced and `-X engine=... -X load=...` appended (nothing with `default`); bench/run-suite.py
# scores it like the arena.
# JSON report: work/rlvm/slice/<label>.json. Exit status: 0 if every selected test is correct, 1 if
# not (declines included: the slice's goal is "pass"), 2 for usage errors, 70 if nothing is built.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
only=()
engine=reference
load=batch
threads=1
mode=""
jobs=8
build=1
out="$ROOT/work/status/slice.md"
tag=""
extra=()
all=0
probe=0
dist="$ROOT/work/dist"
arena_timeouts=1

while [ "$#" -gt 0 ]; do
  case "$1" in
    --all) all=1; shift ;;
    --probe) probe=1; shift ;;
    --only) only+=("${2:?--only needs a glob}"); shift 2 ;;
    --engine) engine="${2:?--engine needs a name}"; shift 2 ;;
    --load) load="${2:?--load needs a mode}"; shift 2 ;;
    --threads) threads="${2:?--threads needs a number}"; shift 2 ;;
    --native|--jvm) mode="${1#--}"; shift ;;
    -j|--jobs) jobs="${2:?-j needs a number}"; shift 2 ;;
    --no-build) build=0; shift ;;
    --dist) dist="$(realpath -m "${2:?--dist needs a directory}")"; build=0; shift 2 ;;
    --out) out="$(realpath -m "${2:?--out needs a file}")"; shift 2 ;;
    --tag) tag="-${2:?--tag needs a value}"; shift 2 ;;
    --no-arena-timeouts) arena_timeouts=0; shift ;;
    -X) extra+=(-X "${2:?-X needs KEY=VALUE}"); shift 2 ;;
    -h|--help) sed -n '2,37p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "slice.sh: unknown option: $1 (see --help)" >&2; exit 2 ;;
  esac
done
if [ "$all" = 0 ] && [ "${#only[@]}" -eq 0 ]; then only=('tutorial/*'); fi

if [ "$all" = 1 ] && [ "$out" = "$ROOT/work/status/slice.md" ]; then out="$ROOT/work/status/slice-all.md"; fi

if [ "$probe" = 1 ]; then
  # shellcheck source=../env.sh
  . "$ROOT/env.sh"
  which=tutorial; [ "$all" = 0 ] || which=all
  report="$ROOT/work/rlvm/slice/probe-$which.json"
  case "$out" in
    "$ROOT/work/status/slice.md") out="$ROOT/work/status/slice-probe.md" ;;
    "$ROOT/work/status/slice-all.md") out="$ROOT/work/status/slice-probe-all.md" ;;
  esac
  mkdir -p "$ROOT/work/rlvm/slice" "$ROOT/work/logs/steward"
  jars="$ROOT/work/rlvm/slice/probe-jars.txt"
  for j in "$M2_REPO"/rlvm/rlvm-*/0.1.0-SNAPSHOT/rlvm-*.jar; do
    printf '  %-18s %s  published %s\n' "$(basename "$j" -0.1.0-SNAPSHOT.jar)" "$(sha256sum "$j" | cut -c1-12)" \
      "$(date -u -r "$j" +%Y-%m-%dT%H:%M:%SZ)"
  done >"$jars"
  (cd "$ROOT" && $MVN -o -q -pl rlvm-main test -Dtest=ProbeSliceTest "-Drlvm.probe=$which" \
      "-Drlvm.probe.report=$report" -Drlvm.probe.timeout=120000 -Dsurefire.failIfNoSpecifiedTests=false) \
      >"$ROOT/work/logs/steward/probe-$which.log" 2>&1 || {
    tail -n 20 "$ROOT/work/logs/steward/probe-$which.log" >&2; echo "slice.sh: the probe failed to run" >&2; exit 70; }
  grep '^probe:' "$ROOT/work/logs/steward/probe-$which.log" >&2 || true
  rc=0
  python3 -I "$ROOT/scripts/slice-report.py" "$report" "$out" "$jars" || rc=$?
  sed -i '1,3s|by `scripts/slice.sh`|by the steward PROBE (`scripts/slice.sh --probe`: no driver, no launcher; jars as published in .toolchain/m2)|' "$out"
  exit "$rc"
fi

# The table of all 230 tests has its own default file; slice.md stays the tutorial slice.
if [ "$all" = 1 ] && [ "$out" = "$ROOT/work/status/slice.md" ]; then out="$ROOT/work/status/slice-all.md"; fi

if [ "$build" = 1 ]; then
  "$ROOT/scripts/dev.sh" dist >"$ROOT/work/logs/steward/slice-dist.log" 2>&1 || {
    tail -n 20 "$ROOT/work/logs/steward/slice-dist.log" >&2
    echo "slice.sh: building the distribution failed" >&2; exit 70; }
fi
# The shared distribution is a symbolic link into work/dist.d/ that every build switches
# (scripts/build.sh): resolve it ONCE, so that all tests of this table run the same build.
if [ -L "$dist" ]; then dist="$(realpath -e "$dist" 2>/dev/null || echo "$dist")"; fi
if [ -z "$mode" ]; then
  if [ -x "$dist/bin/rlvm" ]; then mode=native; else mode=jvm; fi
fi
case "$mode" in native) binary=rlvm ;; jvm) binary=rlvm-jvm ;; esac
[ -x "$dist/bin/$binary" ] || { echo "slice.sh: $dist/bin/$binary not built" >&2; exit 70; }

settings=()
[ "$engine" = default ] || settings+=(-X "engine=$engine")
[ "$load" = default ] || settings+=(-X "load=$load")
cmd="$("$ROOT/bench/rlvm/run.sh" "--$mode" --dist "$dist" --threads "$threads" --print -- "${settings[@]}" "${extra[@]}")"
label="slice-$engine-$mode-t$threads$tag"
report="$ROOT/work/rlvm/slice/$label.json"
mkdir -p "$ROOT/work/rlvm/slice" "$(dirname "$out")"

if [ "$arena_timeouts" = 1 ]; then
  suite=("$ROOT/bench/run-suite.py" --arena-timeouts --timeout 900 --cwd "$ROOT" --label "$label" --report "$report" -q -j "$jobs")
else
  suite=("$ROOT/bench/run-suite.py" --timeout 3600 --cwd "$ROOT" --label "$label" --report "$report" -q -j "$jobs")
fi
for g in "${only[@]}"; do suite+=(--only "$g"); done
echo "slice.sh: $cmd" >&2
rc=0
"${suite[@]}" -- "$cmd" || rc=$?
case "$rc" in 0|1) ;; *) echo "slice.sh: run-suite.py failed with status $rc" >&2; exit "$rc" ;; esac

rc2=0
python3 -I "$ROOT/scripts/slice-report.py" "$report" "$out" "$dist/BUILD-INFO.txt" || rc2=$?
if [ "$arena_timeouts" = 0 ]; then
  # Say it in the table: this run checked verdicts, not the arena's time limits.
  note="**Verdicts only**: the arena's per-test time limits were NOT enforced in this run (\`--no-arena-timeouts\`; host load average $(cut -d' ' -f1 /proc/loadavg) at the end, $(taskset -p $$ 2>/dev/null | sed 's/.*: /affinity mask /')). Margins to the limits: see a run on a quiet machine."
  sed -i "3a\\
\\
$note" "$out"
fi
exit "$rc2"
