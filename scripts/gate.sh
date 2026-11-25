#!/usr/bin/env bash
# scripts/gate.sh - the end-to-end guards every owner runs before publishing a behavioural change
# and before finishing, as one command:
#
#   1. the distribution: `scripts/dev.sh dist` (published jars + rlvm-main), unless --dist / --no-build
#   2. all 230 published arena tests through the JVM launcher: scripts/slice.sh --jvm --all
#      (with --native: through the executable bin/rlvm, scripts/slice.sh --native --all)
#      -> every test correct, 0 wrong, 0 declined (pinned gates check the verdicts without the
#      arena's per-test time limits: on the slow cores of a loaded host a 10 s test can take minutes)
#   3. the mutant campaign(s): rlvm-testkit/bin/testkit judge --corpus DIR -- scripts/check-light.sh
#      -> SOUNDNESS 0 (no mutant accepted that the official checker rejects) and no good mutant rejected
#   4. the self-test of the differential path (2 s): rlvm-testkit/bin/testkit diff --engine broken
#      --expect-differences on bad/tutorial/002_badDef - an engine that accepts everything must be
#      caught by -X engine=diff through the real launcher and driver (suggested by rlvm-testkit)
#   5. with --hostile: the hostile exports (scripts/gate-hostile.py; default manifest
#      rlvm-machine/src/test/resources/s/manifest.tsv) in the gate's configuration, judged by the
#      official verdict stored with each row: a row of kind T (OWNER-NOTES 13 (d)) may be accepted,
#      every other row must get the official verdict
#
# The oracle of every step is an OFFICIAL verdict (the arena's expected outcome of a published test,
# the test kit's stored verdicts of leanchecker for the mutants, the manifest's column for a hostile
# row) - never the opinion of one of our engines. So the same command gates a distribution whose
# default engine is the reference engine and one whose default engine is the machine.
#
#   scripts/gate.sh [options]
#
#   --dist DIR        guard this distribution instead of work/dist (implies --no-build). To guard a
#                     module BEFORE publishing it:
#                       scripts/build.sh --published --jvm-only --modules MODULE,rlvm-main --dist work/ME/dist-pre
#                       scripts/gate.sh --dist work/ME/dist-pre --corpus tutorial
#   --no-build        use work/dist as it is (the version its link names when the gate starts: a
#                     rebuild during the gate does not change what is guarded)
#   --corpus NAME     mutant corpus (repeatable; default: tutorial): tutorial (work/mutants, 12,260),
#                     bugs (work/mutants-bugs-other, 2,692), mdata (work/mutants-mdata, 1,976),
#                     prelude (work/mutants-prelude, 847), none, or a directory; `all` = the four in
#                     one call of `testkit judge --all`, which also appends one summary table per
#                     run to work/status/mutants.md (counts as rebuilt by rlvm-testkit at 08:27Z)
#   --native          guard THE EXECUTABLE (bin/rlvm, the native image: what a release ships and the
#                     arena runs) instead of the jars on the JVM: steps 2, 3 and 5 run bin/rlvm (the
#                     mutants and the hostile exports with a 4 GB heap cap, as on the JVM). It needs
#                     no bin/rlvm-jvm and no lib/, so it guards a release-layout distribution
#                     (scripts/build.sh without --dev). Step 4 needs the test kit on a class path:
#                     it runs only if the distribution also has bin/rlvm-jvm, and is skipped otherwise
#   --no-slice        skip step 2
#   --hostile [FILE]  also step 5; FILE = a manifest (see scripts/gate-hostile.py --help), default the
#                     machine's suite. --kind-mark REGEX names the marks of a kind T row
#   --no-diff-selftest  skip step 4
#   --threads N       worker threads of both steps (default 1; 0 = automatic)
#   --engine NAME     -X engine=NAME (default reference; `default` = no -X engine at all: the
#                     distribution's own default engine)
#   --load MODE       -X load=MODE (default batch; `default` = no -X load at all: the checker's
#                     own rule, pipelined for two or more workers on inputs of 8 MiB and more)
#   --defaults        the distribution exactly as a user runs it: --threads 0 --engine default
#                     --load default (automatic workers, default engine, default load mode and
#                     reader). The gate of a change of a DEFAULT (rlvm-main, an engine's priority)
#   -X KEY=VALUE      further checker setting for both steps (repeatable)
#   --extra-cp PATH   class path entries after the distribution's jars (an engine that is not in it)
#   -j JOBS           files in parallel (default 4 for both steps: the host rule of 2026-11-24 allows
#                     a slot four lanes; it was 8 for the slice and 10 for the mutants). Whatever is
#                     asked for, a step never starts more lanes than the memory that is available
#                     at its start can hold beside a reserve of 14 GB: a lane of the mutant step is
#                     counted with the 4 GB heap cap of scripts/check-light.sh, a lane of the
#                     230-test step with 8 GB (the largest published test through the JVM launcher)
#   --pin CPUS        pin the guard to these CPUs instead (taskset list, e.g. 0-4,10-14,18,19)
#   The gate holds one host-wide slot (bench/slot.sh) while it runs and waits for one if all are
#   taken; RLVM_NO_SLOT=1 turns that off (for a caller that accounts for the load itself).
#   --no-pin          do not pin the guard to the efficiency cores. By default (when taskset
#                     exists) a gate stays off the performance cores, where measurements run (the
#                     arena-shaped container on eight of them), so that it does not spoil somebody's
#                     numbers; it is slower there (the tutorial corpus: 10-15 minutes instead of 5)
#
# Output: the slice table work/status/gate/slice-all-<path of the distribution, / as ->.md (never work/status/slice-all.md,
# which is the steward's record), the judge reports in work/mutants-reports/, one summary line per step.
# Exit status: 0 if every step passed, 1 if not, 2 usage, 70 nothing built.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
# Host rule of 2026-11-24 (orchestrator, work/status/requests.log 14:32Z; the host ran out of memory
# under thirty agents): every heavy job holds one of the host-wide slots of bench/slot.sh. A gate is
# heavy (hundreds of JVMs), so it takes a slot itself unless its caller already holds one (the slot
# is a file lock that this process inherited: an open descriptor on work/build/slots/heavy.N).
for a in "$@"; do  # the help text needs no slot
  case "$a" in -h|--help) sed -n '2,74p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;; esac
done
if [ -x "$ROOT/bench/slot.sh" ] && [ -z "${RLVM_NO_SLOT:-}" ] \
    && ! ls -l "/proc/$$/fd" 2>/dev/null | grep -q 'work/build/slots/heavy\.'; then
  exec "$ROOT/bench/slot.sh" -- "$0" "$@"
fi
dist="$ROOT/work/dist"
build=1
corpora=()
slice=1
threads=1
engine=reference
load=batch
extra=()
extra_cp=""
jobs=""
pin=1
pin_list=""
diffself=1
hostile=0
hostile_manifest=""
kind_mark=""
launcher=jvm

while [ "$#" -gt 0 ]; do
  case "$1" in
    --dist) dist="$(realpath -m "${2:?--dist needs a directory}")"; build=0; shift 2 ;;
    --no-build) build=0; shift ;;
    --corpus) corpora+=("${2:?--corpus needs a name}"); shift 2 ;;
    --no-slice) slice=0; shift ;;
    --native) launcher=native; shift ;;
    --jvm) launcher=jvm; shift ;;
    --threads) threads="${2:?--threads needs a number}"; shift 2 ;;
    --engine) engine="${2:?--engine needs a name}"; shift 2 ;;
    --load) load="${2:?--load needs a mode}"; shift 2 ;;
    --defaults) threads=0; engine=default; load=default; shift ;;
    -X) extra+=("${2:?-X needs KEY=VALUE}"); shift 2 ;;
    --extra-cp) extra_cp="${2:?--extra-cp needs a path}"; shift 2 ;;
    -j|--jobs) jobs="${2:?-j needs a number}"; shift 2 ;;
    --no-pin) pin=0; shift ;;
    --pin) pin_list="${2:?--pin needs a CPU list}"; shift 2 ;;
    --no-diff-selftest) diffself=0; shift ;;
    --hostile) hostile=1
      case "${2:-}" in ''|-*) shift ;; *) hostile_manifest="$(realpath -m "$2")"; shift 2 ;; esac ;;
    --kind-mark) kind_mark="${2:?--kind-mark needs a regular expression}"; shift 2 ;;
    -h|--help) sed -n '2,74p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "gate.sh: unknown option: $1 (see --help)" >&2; exit 2 ;;
  esac
done
[ "${#corpora[@]}" -gt 0 ] || corpora=(tutorial)

corpus_dir() {
  case "$1" in
    tutorial) echo "$ROOT/work/mutants" ;;
    bugs) echo "$ROOT/work/mutants-bugs-other" ;;
    mdata) echo "$ROOT/work/mutants-mdata" ;;
    prelude) echo "$ROOT/work/mutants-prelude" ;;
    *) realpath -m "$1" ;;
  esac
}
list=()
judge_all=0
for c in "${corpora[@]}"; do
  case "$c" in
    none) ;;
    all) judge_all=1 ;;
    *) list+=("$c") ;;
  esac
done

# Measurements run on the performance cores (the fastest cores: the arena-shaped container takes
# eight of them, one-worker measurements the rest). A gate runs on the other cores; on a machine
# whose cores are all alike it runs on those outside the container's cpuset.
prefix=()
if [ "$pin" = 1 ] && command -v taskset >/dev/null 2>&1; then
  best=0
  for f in /sys/devices/system/cpu/cpu[0-9]*/cpufreq/cpuinfo_max_freq; do
    [ -r "$f" ] || continue
    v="$(cat "$f")"; [ "$v" -le "$best" ] || best="$v"
  done
  others=""
  if [ "$best" -gt 0 ]; then
    others="$(for f in /sys/devices/system/cpu/cpu[0-9]*/cpufreq/cpuinfo_max_freq; do
                if [ "$(cat "$f")" -lt "$best" ]; then c="${f%/cpufreq/cpuinfo_max_freq}"; echo "${c##*/cpu}"; fi
              done | sort -n | paste -sd, -)"
  fi
  if [ -z "$others" ]; then
    arena="$("$ROOT/bench/arena-docker.sh" --arena-limits --print true 2>/dev/null | tr -d '\\' | sed -n 's/.*--cpuset-cpus \([0-9,]*\).*/\1/p' | head -n 1)"
    if [ -n "$arena" ]; then
      others="$(for ((i = 0; i < $(nproc --all); i++)); do case ",$arena," in *",$i,"*) ;; *) echo "$i" ;; esac; done | paste -sd, -)"
    fi
  fi
  [ -z "$pin_list" ] || others="$pin_list"
  if [ -n "$others" ]; then prefix=(taskset -c "$others"); fi
fi
[ "${#prefix[@]}" -eq 0 ] || echo "gate: pinned to CPUs ${prefix[2]} (not the cores measurements run on; --no-pin to use all)" >&2

if [ "$build" = 1 ]; then
  mkdir -p "$ROOT/work/logs/gate"
  "$ROOT/scripts/dev.sh" dist >"$ROOT/work/logs/gate/dist.log" 2>&1 || {
    tail -n 20 "$ROOT/work/logs/gate/dist.log" >&2; echo "gate: building the distribution failed" >&2; exit 70; }
fi
# The shared distribution is a symbolic link into work/dist.d/ that every build switches
# (scripts/build.sh): resolve it ONCE, so that all steps of this gate check the same build.
if [ -L "$dist" ]; then dist="$(realpath -e "$dist" 2>/dev/null || echo "$dist")"; fi
if [ "$launcher" = native ]; then
  [ -x "$dist/bin/rlvm" ] || { echo "gate: --native: $dist/bin/rlvm not built (scripts/build.sh, scripts/dev.sh dist-native)" >&2; exit 70; }
  [ -z "$extra_cp" ] || { echo "gate: --extra-cp needs the JVM launcher (the executable has no class path)" >&2; exit 2; }
else
  [ -x "$dist/bin/rlvm-jvm" ] || { echo "gate: $dist/bin/rlvm-jvm not built (a development distribution: scripts/dev.sh dist; or guard the executable with --native)" >&2; exit 70; }
fi
export RLVM_LAUNCHER="$launcher"   # scripts/check-light.sh (mutants, hostile exports): which launcher
echo "gate: distribution $dist ($(sed -n 's/^built: *//p' "$dist/BUILD-INFO.txt" | cut -d' ' -f1)); launcher $launcher; engine $engine, load $load, threads $threads${extra[*]:+, -X ${extra[*]}}" >&2

# Lanes of a step: the number asked for (-j, default 4), but never more than the memory available
# now can hold: (MemAvailable - 14 GB reserve) / the bound of one lane, at least 1. bench/slot.sh
# guards the START of the gate (14 GB available); this guards each step, so that a larger -j is
# safe to ask for: it is granted only while the host has the memory.
lanes() { # per-lane bound in GB -> lanes
  local want="${jobs:-4}" avail fit
  avail="$(awk '/MemAvailable/ { printf "%d", $2 / 1048576 }' /proc/meminfo 2>/dev/null || echo 0)"
  fit=$(( (avail - 14) / $1 ))
  [ "$fit" -ge 1 ] || fit=1
  if [ "$fit" -lt "$want" ]; then
    echo "gate: $want lanes asked for, $fit started: ${avail} GB of memory available, $1 GB per lane beside a 14 GB reserve" >&2
    want="$fit"
  fi
  echo "$want"
}

fail=0
if [ "$slice" = 1 ]; then
  args=(--dist "$dist" "--$launcher" --all --engine "$engine" --load "$load" --threads "$threads" -j "$(lanes 8)")
  # A gate never writes work/status/slice-all.md (the steward's record of the published state, made
  # with scripts/slice.sh --all): every gate has its own table under work/status/gate/.
  mkdir -p "$ROOT/work/status/gate"
  # The name carries the distribution's whole path below the project root (work/steward/w7/dist-gate
  # -> work-steward-w7-dist-gate): two directory names were not enough, work/steward/w7/dist-gate and
  # work/rlvm-driver/w7/dist-gate wrote the same table and the same raw report on 2026-11-24.
  gate_name="$(realpath -m --relative-to="$ROOT" "$dist" 2>/dev/null | tr -c 'A-Za-z0-9._\n-' '-')"
  [ -n "$gate_name" ] || gate_name="$(basename "$(dirname "$dist")")-$(basename "$dist")"
  # ... and the configuration when it is not the plain one (reference, batch, 1 worker): three gates of
  # one distribution (defaults, -X engine=machine, plain) wrote the same table on 2026-11-24.
  case "$engine/$load/$threads" in reference/batch/1) ;; *) gate_name="$gate_name-$engine-$load-t$threads" ;; esac
  [ "$launcher" = jvm ] || gate_name="$gate_name-$launcher"
  args+=(--out "$ROOT/work/status/gate/slice-all-$gate_name.md" --tag "gate-$gate_name")
  # A pinned gate runs on the slow cores, usually beside other people's jobs: it guards verdicts,
  # not the arena's time limits (those are read from scripts/slice.sh on a quiet machine).
  [ "${#prefix[@]}" -eq 0 ] || args+=(--no-arena-timeouts)
  for x in "${extra[@]}"; do args+=(-X "$x"); done
  if [ -n "$extra_cp" ]; then export RLVM_EXTRA_CP="$extra_cp"; fi
  line="$("${prefix[@]}" "$ROOT/scripts/slice.sh" "${args[@]}" 2>&1 | tail -n 1)"
  echo "gate: $line"
  case "$line" in "slice: 230/230 correct, 0 wrong, 0 declined"*) ;; *) fail=1; echo "gate: FAILED: the 230 published tests" >&2 ;; esac
fi

check_args=""
for x in "${extra[@]}"; do check_args="$check_args -X $x"; done
for c in "${list[@]}"; do
  dir="$(corpus_dir "$c")"
  [ -d "$dir" ] || { echo "gate: no mutant corpus at $dir" >&2; fail=1; continue; }
  mkdir -p "$ROOT/work/logs/gate"
  log="$ROOT/work/logs/gate/judge-$(basename "$dir")-$(date -u +%Y%m%dT%H%M%SZ).log"
  rc=0
  RLVM_DIST="$dist" RLVM_THREADS="$threads" RLVM_ENGINE="$engine" RLVM_LOAD="$load" RLVM_EXTRA_CP="$extra_cp" \
    RLVM_CHECK_ARGS="$check_args" "${prefix[@]}" "$ROOT/rlvm-testkit/bin/testkit" judge --corpus "$dir" -j "$(lanes 4)" -- \
    "$ROOT/scripts/check-light.sh" '"$IN"' >"$log" 2>&1 || rc=$?
  # The judge's table ends with:  total  SOUNDNESS COMPLETENESS agree declined error undecided
  summary="$(awk '$1 == "total" { printf "SOUNDNESS %s, COMPLETENESS %s, agree %s, declined %s, errors %s, undecided %s", $2, $3, $4, $5, $6, $7 }' "$log")"
  echo "gate: mutants $c ($dir): exit $rc | ${summary:-no summary} | log $log"
  [ "$rc" = 0 ] || { fail=1; echo "gate: FAILED: mutant corpus $c" >&2; }
done

if [ "$judge_all" = 1 ]; then
  # The four corpora in one command (rlvm-testkit): one table per run in work/status/mutants.md.
  mkdir -p "$ROOT/work/logs/gate"
  log="$ROOT/work/logs/gate/judge-all-$(date -u +%Y%m%dT%H%M%SZ).log"
  rc=0
  RLVM_DIST="$dist" RLVM_THREADS="$threads" RLVM_ENGINE="$engine" RLVM_LOAD="$load" RLVM_EXTRA_CP="$extra_cp" \
    RLVM_CHECK_ARGS="$check_args" "${prefix[@]}" "$ROOT/rlvm-testkit/bin/testkit" judge --all -j "$(lanes 4)" -- \
    "$ROOT/scripts/check-light.sh" '"$IN"' >"$log" 2>&1 || rc=$?
  echo "gate: mutants, all corpora: exit $rc | $(grep -ciE 'soundness' "$log") summary line(s); table in work/status/mutants.md | log $log"
  tail -n 8 "$log" | sed 's/^/gate:   /'
  [ "$rc" = 0 ] || { fail=1; echo "gate: FAILED: mutant corpora (testkit judge --all)" >&2; }
fi

bad="$ROOT/work/arena-site/tests/bad/tutorial/002_badDef.ndjson"
if [ "$diffself" = 1 ] && [ -f "$bad" ] && [ ! -x "$dist/bin/rlvm-jvm" ]; then
  echo "gate: differential self-test: SKIPPED (it puts the test kit on a class path: no bin/rlvm-jvm in this distribution)"
elif [ "$diffself" = 1 ] && [ -f "$bad" ]; then
  mkdir -p "$ROOT/work/logs/gate"
  log="$ROOT/work/logs/gate/diff-selftest-$(date -u +%Y%m%dT%H%M%SZ).log"
  rc=0
  "${prefix[@]}" "$ROOT/rlvm-testkit/bin/testkit" diff --engine broken --expect-differences \
      --launcher "$dist/bin/rlvm-jvm" "$bad" >"$log" 2>&1 || rc=$?
  echo "gate: differential self-test: exit $rc | $(grep -E '^RESULT' "$log" | tail -n 1) | log $log"
  [ "$rc" = 0 ] || { fail=1; echo "gate: FAILED: an engine that accepts everything was not caught by -X engine=diff" >&2; }
fi

if [ "$hostile" = 1 ]; then
  mkdir -p "$ROOT/work/logs/gate"
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  log="$ROOT/work/logs/gate/hostile-$stamp.log"
  hargs=(-j "$(lanes 4)" --report "$ROOT/work/logs/gate/hostile-$stamp.json")
  [ -z "$hostile_manifest" ] || hargs+=(--manifest "$hostile_manifest")
  [ -z "$kind_mark" ] || hargs+=(--kind-mark "$kind_mark")
  rc=0
  RLVM_DIST="$dist" RLVM_THREADS="$threads" RLVM_ENGINE="$engine" RLVM_LOAD="$load" RLVM_EXTRA_CP="$extra_cp" \
    RLVM_CHECK_ARGS="$check_args" "${prefix[@]}" python3 -I "$ROOT/scripts/gate-hostile.py" "${hargs[@]}" >"$log" 2>&1 || rc=$?
  echo "gate: $(tail -n 1 "$log") | exit $rc | log $log"
  [ "$rc" = 0 ] || { fail=1; grep -E '^  (WRONG|ERROR)' "$log" | head -n 20 >&2; echo "gate: FAILED: hostile exports (a wrong verdict on a row that is not of kind T, or an error)" >&2; }
fi

if [ "$fail" = 0 ]; then echo "gate: PASS"; else echo "gate: FAIL"; fi
exit "$fail"
