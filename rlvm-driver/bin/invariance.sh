#!/usr/bin/env bash
# rlvm-driver/bin/invariance.sh - the driver's invariance suite on real data, as one command:
# the verdict and the unit that decides it are the same for 1, 2, 4 and 8 workers, for a 1-byte
# session cap (a reset after every unit) and for pipelined loading (a publication after every unit,
# every 7 and every 64 units, with 1, 4 and 8 workers), and the verdict is the expected one.
# Run it before every publication of rlvm-driver.
#
#   rlvm-driver/bin/invariance.sh [options] [CORPUS...]
#
#   CORPUS (default: published tutorial):
#     published   the published arena tests below work/exports/all/{good,bad}/*/        (231 files)
#     tutorial    mutants of the tutorial tests, work/mutants/suite                     (11,605)
#     bugs        mutants of bugs/other/corner-cases, work/mutants-bugs-other/suite     (2,561)
#     mdata       WRAP_MDATA mutants, work/mutants-mdata/suite                          (1,976)
#     prelude     mutants of init-prelude, work/mutants-prelude/suite                   (807; 3 GB, slow)
#     big         init-prelude, Init, Std, cedar (work/exports)                         (4 files)
#     all         all of the above
#     a directory or a .ndjson file
#   -P N          JVMs in parallel (default 6)
#   --no-pin      do not pin the run to the CPUs outside the arena-shaped container's cpuset
#   --no-compile  use rlvm-driver/target as it is (default: mvn -o -q -pl rlvm-driver test-compile)
#   --set K=V,..  driver settings for every run (RunConfig.setAll). The suite means the VERIFIED protocol
#                 (verify=reference: a rejection of the primary engine is re-checked by its strict run and
#                 by the reference kernel), under which the verdict is schedule-invariant for any engine,
#                 and asks for it by name on every run; --set verify=none runs the default protocol
#                 instead (a rejection of the primary engine is final: on input where a warm session of
#                 the machine rejects by its history, schedule differences are then expected)
#   --engine NAME primary engine (default: reference). "machine" = the published rlvm-machine jar
#                 (.toolchain/m2) as primary engine, the reference kernel for re-checks and the
#                 environment pass: the launcher's "-X engine=machine"
#   -X KEY=VALUE  a setting of that engine (repeatable), e.g. -X machine.switches=warm,gated,expect
#   --tag TAG     name of the output directory work/rlvm-driver/invariance-TAG (default: UTC time)
#
# Every file is run 9 times in one JVM per shard by rlvm.driver.DriverBench --compare (reference
# kernel as primary engine unless --engine, real reader / loader / store / admission, a Wiring equal to
# rlvm.main.MainWiring; the class path is frozen first, so owners may publish meanwhile). The
# expected verdict is taken from the path (good = accept, bad = reject; for mutant corpora that is
# the official checker's verdict): "wrong" = a verdict other than the expected one or DECLINE.
# "pipelined declines": a pipelined run DECLINED what the batch run decides (a file that is not in
# declaration order: the admission's dependency order needs the complete file). Less complete, never
# another verdict; counted, not a failure - it is the gap that keeps pipelined loading from being the
# default. An ACCEPT / REJECT difference between any two runs of a file is a failure.
# Output: one line per corpus and a total; details in work/rlvm-driver/invariance-TAG/*.out.
# Exit status: 0 if there is no wrong verdict and no schedule difference, 1 otherwise, 2 usage.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
jobs=6
pin=1
compile=1
settings=""
engine=""
engine_opts=()
tag="$(date -u +%H%M%S)"
corpora=()

while [ "$#" -gt 0 ]; do
  case "$1" in
    -P) jobs="${2:?-P needs a number}"; shift 2 ;;
    --no-pin) pin=0; shift ;;
    --no-compile) compile=0; shift ;;
    --set) settings="${2:?--set needs KEY=VALUE}"; shift 2 ;;
    --engine) engine="${2:?--engine needs a name}"; shift 2 ;;
    -X) engine_opts+=(--engine-opt "${2:?-X needs KEY=VALUE}"); shift 2 ;;
    --tag) tag="${2:?--tag needs a value}"; shift 2 ;;
    -h|--help) sed -n '2,37p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*) echo "invariance.sh: unknown option $1 (see --help)" >&2; exit 2 ;;
    *) corpora+=("$1"); shift ;;
  esac
done
[ "${#corpora[@]}" -gt 0 ] || corpora=(published tutorial)
# The verified protocol by name, unless the caller names the protocol (see --set above).
case ",$settings," in *,verify=*) ;; *) settings="verify=reference${settings:+,$settings}" ;; esac
if [ "${corpora[0]}" = all ]; then corpora=(published tutorial bugs mdata prelude big); fi

cd "$ROOT" || exit 2
# shellcheck source=../../env.sh
. "$ROOT/env.sh"
if [ "$compile" -eq 1 ]; then
  $MVN -o -q -pl rlvm-driver test-compile || { echo "invariance.sh: rlvm-driver does not compile" >&2; exit 2; }
fi
extra_modules=()
[ -n "$engine" ] && [ "$engine" != reference ] && extra_modules+=("$engine")
cp="$("$ROOT/rlvm-driver/bin/freeze-cp.sh" "inv-$tag" "${extra_modules[@]}")" || exit 2
out="$ROOT/work/rlvm-driver/invariance-$tag"
mkdir -p "$out"

pinned=()
if [ "$pin" -eq 1 ] && command -v taskset >/dev/null 2>&1; then
  # the arena-shaped container measures on CPUs 5-9,15-17, one-worker measurements on 18,19
  # (scripts/gate.sh pins to the same efficiency cores)
  pinned=(taskset -c 0-4,10-14)
fi

list_of() {
  case "$1" in
    published) find -L work/exports/all/good work/exports/all/bad -mindepth 2 -name '*.ndjson' | sort ;;
    tutorial) find work/mutants/suite -name '*.ndjson' | sort ;;
    bugs) find work/mutants-bugs-other/suite -name '*.ndjson' | sort ;;
    mdata) find work/mutants-mdata/suite -name '*.ndjson' | sort ;;
    prelude) find work/mutants-prelude/suite -name '*.ndjson' | sort ;;
    big) printf '%s\n' work/exports/all/good/init-prelude.ndjson work/exports/all/good/init.ndjson \
           work/exports/all/good/std.ndjson work/exports/all/good/cedar.ndjson ;;
    *) if [ -d "$1" ]; then find -L "$1" -name '*.ndjson' | sort; elif [ -f "$1" ]; then echo "$1"; else
         echo "invariance.sh: unknown corpus '$1' (see --help)" >&2; return 2; fi ;;
  esac
}

total_files=0; total_wrong=0; total_diff=0; total_declined=0; total_closed=0; failed=0
for corpus in "${corpora[@]}"; do
  name="$(basename "$corpus" .ndjson)"
  list="$out/$name.list"
  list_of "$corpus" > "$list" || exit 2
  n="$(wc -l < "$list")"
  [ "$n" -gt 0 ] || { echo "invariance.sh: corpus '$corpus' is empty" >&2; exit 2; }
  shards="$jobs"; [ "$n" -lt "$shards" ] && shards="$n"
  # round robin, so that the slow files of a directory are spread over the shards
  rm -f "$out/$name".shard-*
  awk -v k="$shards" -v p="$out/$name.shard-" '{ print > (p ((NR - 1) % k)) }' "$list"
  heap="-Xmx6g"; [ "$corpus" = big ] && heap="-Xmx13g"
  t0="$(date +%s)"
  pids=()
  for s in $(seq 0 $((shards - 1))); do
    args=(--compare --quiet --files-from "$out/$name.shard-$s")
    [ -n "$settings" ] && args+=(--set "$settings")
    [ -n "$engine" ] && args+=(--engine "$engine" "${engine_opts[@]}")
    "${pinned[@]}" "$JAVA_HOME/bin/java" -XX:+UseSerialGC "$heap" -cp "$cp" rlvm.driver.DriverBench "${args[@]}" \
      > "$out/$name.$s.out" 2>&1 &
    pids+=("$!")
  done
  status=0
  for p in "${pids[@]}"; do wait "$p" || status=1; done
  files=0; correct=0; wrong=0; declined=0; diff=0; closed=0; summaries=0
  for s in $(seq 0 $((shards - 1))); do
    line="$(grep -E '^[0-9]+ files: ' "$out/$name.$s.out" | tail -1)"
    if [ -n "$line" ]; then
      summaries=$((summaries + 1))
      set -- $(echo "$line" | sed -E 's/^([0-9]+) files: ([0-9]+) correct, ([0-9]+) wrong, ([0-9]+) declined, ([0-9]+) without expectation, ([0-9]+) schedule differences, ([0-9]+) pipelined declines.*/\1 \2 \3 \4 \6 \7/')
      files=$((files + $1)); correct=$((correct + $2)); wrong=$((wrong + $3)); declined=$((declined + $4)); diff=$((diff + $5)); closed=$((closed + $6))
    fi
  done
  secs=$(( $(date +%s) - t0 ))
  verdict=ok
  if [ "$summaries" -ne "$shards" ] || [ "$files" -ne "$n" ]; then verdict="INCOMPLETE ($summaries of $shards shards reported, $files of $n files)"; failed=1; fi
  if [ "$wrong" -ne 0 ] || [ "$diff" -ne 0 ] || [ "$status" -ne 0 ]; then [ "$verdict" = ok ] && verdict=FAILED; failed=1; fi
  printf 'invariance %-10s %6d files x 9 runs: %d correct, %d wrong, %d declined, %d schedule differences, %d pipelined declines; %ds; %s\n' \
    "$name" "$n" "$correct" "$wrong" "$declined" "$diff" "$closed" "$secs" "$verdict"
  grep -hE '^(DIFFERENT|WRONG)' "$out/$name".*.out | head -20
  total_files=$((total_files + files)); total_wrong=$((total_wrong + wrong)); total_diff=$((total_diff + diff)); total_declined=$((total_declined + declined)); total_closed=$((total_closed + closed))
done
printf 'invariance TOTAL      %6d files: %d wrong, %d declined, %d schedule differences, %d pipelined declines -> %s (details: %s)\n' \
  "$total_files" "$total_wrong" "$total_declined" "$total_diff" "$total_closed" "$([ "$failed" -eq 0 ] && echo PASS || echo FAIL)" "${out#$ROOT/}"
exit "$failed"
