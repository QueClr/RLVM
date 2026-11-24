#!/bin/bash
# bench/baselines/profile-mathlib.sh - per-declaration cost profiles of the
# baseline checkers, from the profiling switches the checkers themselves
# offer, plus bench/baselines/instrtap.c for exact instruction counts.
#
#   bench/arena-docker.sh --arena-limits --no-network \
#       bench/baselines/profile-mathlib.sh [--export FILE] [--out DIR] STEP [STEP...]
#
# To be run inside the container. Default export: work/exports/mathlib.ndjson;
# default output directory: work/baselines/profile. Every step runs its
# command under bench/measure.sh, so NAME.measure.json holds the instruction
# count of the (instrumented) run as the arena would measure it.
#
# Steps:
#   build              compile libinstrtap.so and instrtap-test
#   selftest           check libinstrtap.so against a loop of known length
#   selftest-java      the same with a JVM program (bench/baselines/InstrtapTest.java):
#                      the count is that of the Java thread, without JIT and GC threads
#   nano-lean-import   nl-fast -q -j 4 --import-only            (cost of parsing alone)
#   nano-lean-j1       nl-fast --trace -j 1, with instrtap      -> nano-lean.j1.trace
#   nano-lean-j4       nl-fast --trace -j 4, with instrtap      -> nano-lean.j4.trace
#   gruszka-pack       susz pack --no-index (what scripts/arena-run does first) -> export.susz
#   gruszka-w1         SK_DECL_INSTR=1 gruszka export.susz 1, with instrtap -> gruszka.w1.di
#   gruszka-w4         the same with 4 workers                  -> gruszka.w4.di
#   gruszka-check-w1 / gruszka-check-w4
#                      gruszka export.susz N without any instrumentation (cost of
#                      everything after packing)
#   sokonanoda-parse   sokonanoda --parse-only --threads 4      (cost of parsing alone)
#   sokonanoda-v1      sokonanoda --verbose --threads 1         -> sokonanoda.t1.verbose
#   sokonanoda-v4      sokonanoda --verbose --threads 4         -> sokonanoda.t4.verbose
#   rpylean-jsonl      rpylean-c check --print jsonl, with instrtap on stdout -> rpylean.jsonl
#
# What the switches are (all found in the checkers' sources):
#   nano-lean   --trace: "start IDX NAME" / "end IDX elapsed T arena N bytes" on
#               stderr per declaration; --import-only stops after reading the export
#   gruszka     SK_DECL_INSTR=1: one "DI<TAB>index<TAB>name<TAB>instructions<TAB>ns<TAB>counters"
#               line per declaration on stderr (the instruction field is 0 on
#               Linux: the checker reads it from a macOS-only API)
#   sokonanoda  --verbose: one line per declaration with its wall time in ms;
#               --parse-only
#   rpylean     check --print jsonl: one JSON object per declaration with
#               elapsed, gc_elapsed, bytes_allocated, live_memory, peak_growth
#
# bench/baselines/profile-summary.py turns the outputs into
# work/baselines/mathlib-profile.json and per-checker .tsv files.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
cd "$ROOT" || exit 1
EXPORT="$ROOT/work/exports/mathlib.ndjson"
OUT="$ROOT/work/baselines/profile"
C="$ROOT/work/arena-checkers/_build/checkers"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --export) EXPORT="$(realpath "${2:?}")"; shift 2 ;;
    --out) OUT="$(realpath -m "${2:?}")"; shift 2 ;;
    -h|--help) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 0 ;;
    *) break ;;
  esac
done
[ "$#" -gt 0 ] || { echo "profile-mathlib.sh: no step given (see --help)" >&2; exit 2; }
mkdir -p "$OUT"
TAP="$OUT/libinstrtap.so"
M="$ROOT/bench/measure.sh"

# run NAME CWD COMMAND: COMMAND through sh -c under measure.sh, export in $IN
run() {
  local name="$1" cwd="$2" cmd="$3"
  echo "--- $name  $(date -u +%H:%M:%S)"
  "$M" --cwd "$cwd" --in "$EXPORT" -o "$OUT/$name.measure.json" -c "$cmd" 2> "$OUT/$name.measure.log"
  python3 -I -c '
import json, sys
r = json.load(open(sys.argv[1]))
print("    exit %s  %.4e instr  wall %.1f s  cpu %.1f s  rss %.0f MiB" % (
    r["exit_code"], r["instructions"] or 0, r["wall_time_s"], r["cpu_time_s"], (r["max_rss_bytes"] or 0) / 2**20))' "$OUT/$name.measure.json"
}

need_tap() { [ -f "$TAP" ] || { echo "run the build step first" >&2; exit 1; }; }

rc=0
for step in "$@"; do
  case "$step" in
    build)
      gcc -O2 -Wall -Wextra -shared -fPIC -o "$TAP" bench/baselines/instrtap.c &&
      gcc -O2 -Wall -pthread -o "$OUT/instrtap-test" bench/baselines/instrtap-test.c || rc=1 ;;
    selftest)
      need_tap
      LD_PRELOAD="$TAP" INSTRTAP_PREFIXES='start |end ' "$OUT/instrtap-test" 4 200000000 2>&1 | python3 -I -c '
import sys
first, bad = {}, 0
for line in sys.stdin:
    if line.startswith("#I "):
        _, tid, n = line.split()
        if tid in first:
            d = int(n) - first[tid]
            ok = abs(d - 6 * 200000000) < 5000
            bad += not ok
            print("thread %s: %d instructions between start and end (expected 1200000000 + about 540)%s" % (tid, d, "" if ok else "  WRONG"))
        else:
            first[tid] = int(n)
sys.exit(1 if bad or len(first) != 4 else 0)' || rc=1 ;;
    selftest-java)
      need_tap
      mkdir -p "$OUT/java" && javac -d "$OUT/java" bench/baselines/InstrtapTest.java &&
      LD_PRELOAD="$TAP" INSTRTAP_PREFIXES='start |end ' java -cp "$OUT/java" InstrtapTest 200000000 2>&1 | python3 -I -c '
import sys
start, bad, n = {}, 0, 0
for line in sys.stdin:
    if line.startswith("#I "):
        _, tid, count = line.split()
        pending = (tid, int(count))
    elif line.startswith("start "):
        start[pending[0]] = pending[1]
    elif line.startswith("end "):
        d = pending[1] - start[pending[0]]
        n += 1
        ok = 0.5e9 < d < 1.3e9          # 1.2e9 once the loop is compiled
        bad += not ok
        print("thread %s, round %s: %d instructions%s" % (pending[0], line.split()[1], d, "" if ok else "  UNEXPECTED"))
sys.exit(1 if bad or n != 5 else 0)' || rc=1 ;;
    nano-lean-import)
      run nano-lean.import-only "$C/nano-lean/src" 'target/release/nl-fast -q -j 4 --import-only "$IN"' ;;
    nano-lean-j1|nano-lean-j4)
      need_tap; n="${step##*-j}"
      run "nano-lean.j$n" "$C/nano-lean/src" \
        "LD_PRELOAD='$TAP' INSTRTAP_PREFIXES='start |end ' exec target/release/nl-fast --trace -j $n \"\$IN\" > '$OUT/nano-lean.j$n.stdout' 2> '$OUT/nano-lean.j$n.trace'" ;;
    gruszka-pack)
      run gruszka.pack "$C/gruszka/src" ".susz/bin/susz pack --no-index \"\$IN\" '$OUT/export.susz'"
      ls -l "$OUT/export.susz" ;;
    gruszka-w1|gruszka-w4)
      need_tap; n="${step##*-w}"
      [ -f "$OUT/export.susz" ] || { echo "run gruszka-pack first" >&2; rc=1; continue; }
      run "gruszka.w$n" "$C/gruszka/src" \
        "SK_DECL_INSTR=1 LD_PRELOAD='$TAP' INSTRTAP_PREFIXES='DI\\t' exec target/release/gruszka '$OUT/export.susz' $n > '$OUT/gruszka.w$n.stdout' 2> '$OUT/gruszka.w$n.di'" ;;
    gruszka-check-w1|gruszka-check-w4)
      n="${step##*-w}"
      [ -f "$OUT/export.susz" ] || { echo "run gruszka-pack first" >&2; rc=1; continue; }
      run "gruszka.check-w$n" "$C/gruszka/src" "target/release/gruszka '$OUT/export.susz' $n" ;;
    sokonanoda-parse)
      run sokonanoda.parse-only "$C/sokonanoda/src" \
        'target/release/sokonanoda --stdin --nat-extension --string-extension --axiom-allow-all --threads 4 --parse-only < "$IN"' ;;
    sokonanoda-v1|sokonanoda-v4)
      n="${step##*-v}"
      run "sokonanoda.t$n" "$C/sokonanoda/src" \
        "target/release/sokonanoda --stdin --nat-extension --string-extension --axiom-allow-all --threads $n --verbose < \"\$IN\" > '$OUT/sokonanoda.t$n.verbose'" ;;
    rpylean-jsonl)
      need_tap
      run rpylean "$C/rpylean/src" \
        "LD_PRELOAD='$TAP' INSTRTAP_FD=1 INSTRTAP_PREFIXES='{\"name\":' exec ./rpylean-c check --print jsonl \"\$IN\" > '$OUT/rpylean.jsonl' 2> '$OUT/rpylean.stderr'" ;;
    *) echo "profile-mathlib.sh: unknown step $step" >&2; rc=2 ;;
  esac
done
exit "$rc"
