#!/usr/bin/env bash
# rlvm-machine/src/test/resources/m8/report.sh - the reports of work package M8, one command each.
#
# Every command measures the MAIN TREE of rlvm-machine as it is published now: the script copies the
# module (in a moment without a publication, checked against work/sandbox/.lock.holder and publish.log),
# compiles the copy and runs rlvm.machine.M8Main in a JVM of its own. It never runs Maven on the main tree and
# never writes into it. The reports go to work/status/.
#
#   report.sh tutorial | bugs | all | init | std | cedar | reachability
#                          the machine against the reference engine, per declaration (in-process):
#                          work/status/machine-diff-NAME.txt. The first lines say whether the gate is
#                          passed (DIFF-CLEAN), what stopped the machine how often, and how to reproduce
#                          the first difference of every file.            exit 1 = a WRONG verdict
#   report.sh gates        tutorial, bugs, all, init, one after the other (the v0 gate of ISA section 11)
#   report.sh diff NAME FILE_OR_DIR..     the same for any corpus
#   report.sh conv NAME [FILE_OR_DIR..]   convention equivalence (stepper-convention H12 item 1): every
#                          declaration under host depth bounds 128 (Op.HOST_DEPTH), 64, 0 and unbounded: outcome, ticks, call
#                          trace, records with memo words, runs, pairs: work/status/machine-conv-NAME.txt
#   report.sh frames NAME [FILE_OR_DIR..] call events and frames written under Op.HOST_DEPTH (H12 item 2; the
#                          bound is 0.1% of the calls on Init): work/status/machine-frames-NAME.txt
#   report.sh sample NAME [FILE_OR_DIR..] whnf and infer of the machine on sampled sub-terms, read back
#                          and compared by the reference kernel up to its isDefEq:
#                          work/status/machine-sample-NAME.txt
#   report.sh tiers NAME [FILE_OR_DIR..]  K0 against K1 (-Dm8.k1=facts,tele,code,casetrees): DELTA, WHNF, VTYPE and
#                          CONV of every constant applied to generated arguments give the same VALUE
#                          (structural fingerprint incl. closure identity): work/status/machine-tiers-NAME.txt
#   report.sh launcher [tutorial|bugs|all|FILE_OR_DIR..]
#                          the gate's own form: every test through the launcher with -X engine=machine,
#                          verdict against the arena's classification and the statistics
#                          (machine.declined, units.declined): work/status/machine-launcher-NAME.txt
#   report.sh cost [--stage LABEL] [--runs N] [--host] [CORPUS..]
#                          the per-stage cost table, CPU time first: CPU / wall / RSS / instructions of
#                          the machine and of the reference engine through the launcher, in the
#                          arena-shaped container (bench/measure.sh; --host: on the host with
#                          /usr/bin/time, no instruction counts). CORPUS = init | std | cedar |
#                          reachability | tutorial | a file (default: init std cedar reachability).
#                          Appends to work/status/machine-cost.tsv and rewrites work/status/machine-cost.md
#
# Options (before or after the command):
#   --tree DIR             measure another copy of the module instead of the main tree, e.g. a sandbox:
#                          --tree work/sandbox/M4/rlvm-machine. The report then says so in its header and
#                          is named NAME-M4 (or NAME plus -Dm8.suffix): the plain names are the main tree's
#   --heap SIZE            heap of the report JVM (default 24g; Init and Std need about 8g)
#   -Dkey=value            passed to the JVM: -Dm8.hostDepth=0 -Dm8.switches=warm,gated -Dm8.suffix=-d0
#                          -Dm8.checkSeconds=N -Dm8.fileSeconds=N -Dm8.maxSteps=N -Dm8.seed=N ...
#   --keep                 keep the compiled copy (work/m8/tree-PID) for inspection
#
# Exit status: 0 the report was written and nothing is wrong; 1 a wrong verdict, a convention
# difference, a sampled difference or a bound exceeded; 2 usage or build error.
{ # the whole script is one compound command: bash has read all of it before it runs any of it, so a
  # publication that replaces this file while it runs (it runs for minutes) cannot derail it
set -euo pipefail

SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
# the project root: the first directory above this script that holds env.sh and the main tree of the module
# (the script also runs from a sandbox copy, which lies deeper)
ROOT="$SELF"
while [ "$ROOT" != "/" ] && ! { [ -f "$ROOT/env.sh" ] && [ -f "$ROOT/rlvm-machine/OWNERS.tsv" ]; }; do ROOT="$(dirname "$ROOT")"; done
[ "$ROOT" != "/" ] || ROOT="${RLVM_ROOT:?report.sh: cannot find the project root (set RLVM_ROOT)}"
MAIN="$ROOT/rlvm-machine"
tree="$MAIN"
heap="24g"
keep=0
jvmargs=()
args=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    --tree) tree="$(cd "${2:?--tree needs a directory}" && pwd -P)"; shift 2 ;;
    --heap) heap="${2:?--heap needs a size}"; shift 2 ;;
    --keep) keep=1; shift ;;
    -D*) jvmargs+=("$1"); shift ;;
    -h|--help) sed -n '2,/^{ #/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) args+=("$1"); shift ;;
  esac
done
[ "${#args[@]}" -ge 1 ] || { sed -n '2,/^{ #/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'; exit 2; }
cmd="${args[0]}"; rest=("${args[@]:1}")

# shellcheck source=/dev/null
. "$ROOT/env.sh"
mkdir -p "$ROOT/work/m8" "$ROOT/work/status"

# ---- a compiled copy of the module -------------------------------------------------------------------
copy=""
cleanup() { [ "$keep" -eq 1 ] || [ -z "$copy" ] || rm -rf "$copy"; }
trap cleanup EXIT

publishing() { [ -f "$1.holder" ] && grep -q '^publish' "$1.holder" 2>/dev/null; }

# consistent_copy SRC DST: a copy of the module that no publication was changing while it was taken. A
# publication holds work/sandbox/.lock for a minute (it compiles and tests the main tree) and publications
# queue up, so instead of queueing behind them the copy (a fraction of a second) is taken in a moment
# without one and checked: no publication holder before and after, no new line in the publication log.
consistent_copy() {
  local src="$1" dst="$2" lock="$ROOT/work/sandbox/.lock" log="$ROOT/work/sandbox/publish.log" i n0 n1
  for i in $(seq 1 300); do
    if publishing "$lock"; then sleep 1; continue; fi
    n0="$(wc -l < "$log" 2>/dev/null || echo 0)"
    rm -rf "$dst"; mkdir -p "$dst"
    tar -C "$src" --exclude=./target -cf - . | tar -C "$dst" -xf -
    n1="$(wc -l < "$log" 2>/dev/null || echo 0)"
    if [ "$n0" = "$n1" ] && ! publishing "$lock"; then return 0; fi
  done
  # no quiet moment in five minutes: wait for the lock as a publication does
  exec 8<"$lock"
  flock -s -w 900 8 || { echo "report.sh: could not get a shared lock on $lock" >&2; exit 2; }
  rm -rf "$dst"; mkdir -p "$dst"
  tar -C "$src" --exclude=./target -cf - . | tar -C "$dst" -xf -
  flock -u 8 || true; exec 8<&-
}

build_copy() {
  copy="$ROOT/work/m8/tree-$$"
  rm -rf "$copy"; mkdir -p "$copy"
  if [ "$tree" = "$MAIN" ] && [ -e "$ROOT/work/sandbox/.lock" ]; then
    consistent_copy "$tree" "$copy/rlvm-machine"
  else
    mkdir -p "$copy/rlvm-machine"
    tar -C "$tree" --exclude=./target -cf - . | tar -C "$copy/rlvm-machine" -xf -
  fi
  cp -p "$ROOT/pom.xml" "$copy/pom.xml"
  local out="$copy/build.out"
  # shellcheck disable=SC2086
  if ! $MVN -o -q -f "$copy/rlvm-machine/pom.xml" "-Drlvm.root=$ROOT" test-compile org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath \
        -Dmdep.outputFile="$copy/classpath.txt" -Dmdep.includeScope=test > "$out" 2>&1; then
    echo "report.sh: the copy of $tree does not compile:" >&2
    tail -n 30 "$out" >&2
    exit 2
  fi
  CP="$copy/rlvm-machine/target/test-classes:$copy/rlvm-machine/target/classes:$(cat "$copy/classpath.txt")"
}

# a report on another tree than the main tree says so and gets the tree's name as its suffix (Diff.sandbox)
[ "$tree" = "$MAIN" ] || jvmargs+=("-Dm8.tree=$tree")

run_main() {
  # -Xss2m on purpose: the machine must not need a deep host stack (stepper-convention H9)
  "$JAVA_HOME/bin/java" -Xss2m "-Xmx$heap" -XX:+UseSerialGC "-Drlvm.root=$ROOT" ${jvmargs[@]+"${jvmargs[@]}"} \
      -cp "$CP" rlvm.machine.M8Main "$@"
}

case "$cmd" in
  tutorial|bugs|all|init|std|cedar|reachability)
    build_copy; run_main diff "$cmd" ;;
  gates)
    build_copy; st=0
    for c in tutorial bugs all init; do run_main diff "$c" || st=$?; done
    exit "$st" ;;
  diff|conv|frames|sample|tiers)
    [ "${#rest[@]}" -ge 1 ] || { echo "report.sh: $cmd needs a corpus name" >&2; exit 2; }
    build_copy; run_main "$cmd" "${rest[@]}" ;;
  launcher|cost)
    exec python3 "$SELF/$cmd.py" ${rest[@]+"${rest[@]}"} ;;
  *)
    echo "report.sh: unknown command '$cmd' (see --help)" >&2; exit 2 ;;
esac
exit $?
}
