#!/usr/bin/env bash
# bench/build-corpus.sh - build the arena's complete test set (all exports,
# above all mathlib) with the arena's own tooling, inside the container, and
# collect it under work/exports/. Run on the host, from anywhere.
#
#   bench/build-corpus.sh [options] [STEP...]
#
# Steps (default: all of them, in this order):
#   clone       clone https://github.com/leanprover/lean-kernel-arena at the
#               pinned revision into work/arena-run (skipped if it is there)
#   toolchains  install the Lean toolchains the tests need, one after the other
#               (two containers installing the same toolchain at once would race)
#   build       `lka.py build-test` for every test, in six containers side by
#               side: core (init-prelude init std), mathlib, cslib,
#               cedar (cedar con-leche), nse (navier-stokes-euler reachability),
#               small (tutorial bugs/* corner-cases/* perf/* other/*)
#   collect     bench/collect-exports.py: work/exports/{NAME.ndjson,all/...}
#   stats       bench/export-stats.py: work/exports/STATS.md
#   slices      bench/export-slice.sh: mid-size parts of mathlib in
#               work/exports/slices/ (needs the mathlib build of this run)
#
# Options:
#   --rev REV          arena revision to check out in `clone` (default: the
#                      revision the published results in
#                      work/arena-site/results.json were made from, else the
#                      default branch)
#   --tests 'PAT ...'  `build` only the tests matching these lka.py patterns,
#                      in one container (e.g. --tests 'init-prelude other/*')
#   -h, --help
#
# lka.py is run through bench/arena-lka.py (bash as its shell, as on the
# arena); logs go to work/logs/corpus-*.log. The containers are named
# rlvm-corpus-*; stop one with `docker kill NAME`. A test that is built again
# starts from scratch (lka.py deletes its work directory first).
set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT="$(cd "$SELF_DIR/.." && pwd -P)"
ARENA="$ROOT/work/arena-run"
LOGS="$ROOT/work/logs"
DOCKER="$SELF_DIR/arena-docker.sh"
ARENA_URL="https://github.com/leanprover/lean-kernel-arena"

# Toolchains of the tests fetched from other repositories, as of arena
# revision 99195b6 (navier-stokes-euler: v4.34.0-rc2, reachability: v4.34.1;
# mathlib, cslib, cedar and con-leche use the v4.36.0 of tests/lean-toolchain).
# Anything missing here is installed by elan when the test is built.
EXTRA_TOOLCHAINS=(leanprover/lean4:v4.34.0-rc2 leanprover/lean4:v4.34.1)

# The mid-size slices of mathlib (see export-slice.sh): "M" = everything the
# module imports, "cone:M" = the constants of the module and what they need.
SLICES=(
  Mathlib.Data.Nat.Basic
  cone:Mathlib.Algebra.Group.Basic
  cone:Mathlib.Data.Nat.Basic
  cone:Mathlib.Topology.Basic
  cone:Mathlib.Data.Fintype.Basic
  cone:Mathlib.Data.List.Basic
  cone:Mathlib.Order.Basic
  cone:Mathlib.GroupTheory.Sylow
  cone:Mathlib.Analysis.SpecialFunctions.Pow.Real
  cone:Mathlib.AlgebraicGeometry.Scheme
  cone:Mathlib.FieldTheory.Galois.Basic
  cone:Mathlib.Probability.StrongLaw
  cone:Mathlib.MeasureTheory.Integral.IntervalIntegral.FundThmCalculus
  cone:Mathlib.NumberTheory.LegendreSymbol.QuadraticReciprocity
  cone:Mathlib.Analysis.Complex.CauchyIntegral
  cone:Mathlib.NumberTheory.LSeries.RiemannZeta
)

# The top-level corpora, smallest first: the columns of STATS.md
CORPORA=(init-prelude reachability init std con-leche cedar navier-stokes-euler cslib mathlib)

rev=""
only_tests=""
steps=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    --rev) rev="${2:?--rev needs a revision}"; shift 2 ;;
    --tests) only_tests="${2:?--tests needs patterns}"; shift 2 ;;
    -h|--help) sed -n '2,/^set -euo/p' "$0" | sed '$d; s/^# \{0,1\}//'; exit 0 ;;
    -*) echo "build-corpus.sh: unknown option $1" >&2; exit 2 ;;
    *) steps+=("$1"); shift ;;
  esac
done
[ "${#steps[@]}" -gt 0 ] || steps=(clone toolchains build collect stats slices)
mkdir -p "$LOGS"

step_clone() {
  if [ -z "$rev" ] && [ -f "$ROOT/work/arena-site/results.json" ]; then
    rev="$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["meta"]["git_revision"])' \
           "$ROOT/work/arena-site/results.json")"
  fi
  if [ -d "$ARENA/.git" ]; then
    echo "build-corpus: $ARENA exists; not cloning again"
    return
  fi
  # git runs in the container too: nothing of a third-party repository is
  # touched by host tools. Without a revision, the default branch is used.
  "$DOCKER" bash -c 'set -e; git clone --quiet "$1" "$2"; cd "$2"
                     if [ -n "$3" ]; then git checkout --quiet "$3"; fi
                     echo "build-corpus: cloned the arena at $(git rev-parse HEAD)"' \
            _ "$ARENA_URL" "$ARENA" "$rev"
}

step_toolchains() {
  "$DOCKER" bash -c 'set -e
    for t in "$(cat "$0/tests/lean-toolchain")" "$(cat "$0/tutorial/lean-toolchain")" "$@"; do
      elan toolchain list | grep -qxF "$t" || elan toolchain install "$t" 2>&1 | tail -n 3
    done
    elan toolchain list' "$ARENA" "${EXTRA_TOOLCHAINS[@]}"
}

# start_group NAME TEST-PATTERN...: one detached container running lka.py build-test
start_group() {
  local group="$1"; shift
  local log="$LOGS/corpus-$group.log"
  rm -f "$log" "$log.exit" "$log.container"
  "$DOCKER" --name "rlvm-corpus-$group" --detach "$log" -w "$ARENA" \
      "$SELF_DIR/arena-lka.py" -v build-test "$@" >/dev/null
  echo "build-corpus: started rlvm-corpus-$group ($*), log $log"
}

step_build() {
  [ -f "$ARENA/lka.py" ] || { echo "build-corpus: $ARENA/lka.py not found; run the clone step" >&2; exit 1; }
  local groups=() failed=0 group
  if [ -n "$only_tests" ]; then
    # shellcheck disable=SC2086
    set -f; start_group selected $only_tests; set +f
    groups=(selected)
  else
    start_group mathlib mathlib
    start_group core init-prelude init std
    start_group cslib cslib
    start_group cedar cedar con-leche
    start_group nse navier-stokes-euler reachability
    start_group small tutorial 'bugs/*' 'corner-cases/*' 'perf/*' 'other/*'
    groups=(mathlib core cslib cedar nse small)
  fi
  for group in "${groups[@]}"; do
    if "$DOCKER" --wait "$LOGS/corpus-$group.log"; then
      echo "build-corpus: $group: $(grep '^Results:' "$LOGS/corpus-$group.log" | tail -n 1)"
    else
      echo "build-corpus: $group FAILED, see $LOGS/corpus-$group.log" >&2
      failed=1
    fi
  done
  return "$failed"
}

step_collect() {
  python3 -I "$SELF_DIR/collect-exports.py"
}

step_stats() {
  local files=() name
  for name in "${CORPORA[@]}"; do
    if [ -e "$ROOT/work/exports/$name.ndjson" ]; then files+=("$name=$ROOT/work/exports/$name.ndjson"); fi
  done
  python3 -I "$SELF_DIR/export-stats.py" -j 9 --out-dir "$ROOT/work/exports/stats" \
      --annotate "$ROOT/work/exports/all/manifest.json" --order "$(IFS=,; echo "${CORPORA[*]}")" \
      --md "$ROOT/work/exports/STATS.md" "${files[@]}"
}

step_slices() {
  local project="$ARENA/_build/tests/work/mathlib/src"
  [ -d "$project/.lake" ] || { echo "build-corpus: $project is not built; build mathlib first" >&2; exit 1; }
  "$DOCKER" --no-network bash -c 'set -e; script="$1"; shift
    for slice in "$@"; do
      case "$slice" in
        cone:*) "$script" -m "${slice#cone:}" & ;;
        *) "$script" "$slice" & ;;
      esac
    done
    status=0; for job in $(jobs -p); do wait "$job" || status=1; done; exit "$status"' \
    _ "$SELF_DIR/export-slice.sh" "${SLICES[@]}"
  python3 -I "$SELF_DIR/export-stats.py" -j 8 --out-dir "$ROOT/work/exports/slices/stats" \
      --md "$ROOT/work/exports/slices/STATS.md" "$ROOT"/work/exports/slices/good/*.ndjson
}

for step in "${steps[@]}"; do
  case "$step" in
    clone|toolchains|build|collect|stats|slices)
      echo "build-corpus: === $step ==="
      "step_$step" ;;
    *) echo "build-corpus: unknown step $step (see --help)" >&2; exit 2 ;;
  esac
done
