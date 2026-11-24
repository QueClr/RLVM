#!/usr/bin/env bash
# bench/baselines/build-checker.sh - build arena checkers inside the rlvm-arena
# container with the arena's own `lka.py build-checker`.
#
#   bench/baselines/build-checker.sh [--wait] [--bash-sh] NAME [NAME...]        (run on the host)
#
# NAME is a checker of the arena (work/arena-checkers/checkers/NAME.yaml), e.g.
# nano-lean gruszka sokonanoda official rpylean tenet-jvm nanoda pestka mathgraph.
# Each checker is built in its own detached container (name
# rlvm-baseline-build-NAME), which runs
#
#   cd work/arena-checkers && ./lka.py -v build-checker NAME
#
# so the build tree is work/arena-checkers/_build/checkers/NAME[/src], exactly
# where lka.py puts it, and the log is work/logs/baselines/build-NAME.log
# (exit status in build-NAME.log.exit). With --wait the script blocks until all
# builds have ended and exits non-zero if one of them failed.
#
# --bash-sh runs lka.py through bench/arena-lka.py, which makes bash (as `sh`)
# the shell of the yaml's build commands, as it is on the arena (nixpkgs'
# Python); plain lka.py uses the image's /bin/sh, which is dash. The nine
# baselines were built without it: their recipes contain nothing bash-only.
#
# The working copy of the arena is created on first use:
#   git clone https://github.com/leanprover/lean-kernel-arena work/arena-checkers
# pinned to the revision in bench/baselines/ARENA_REV (the revision of the
# published results in work/arena-site/results.json).
#
# Builds are third-party code: they run only in the container, as the host
# uid, with network (crates.io, GitHub, huggingface) and a low CPU weight so
# that measurements of other users of the machine keep priority.
set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT="$(cd "$SELF_DIR/../.." && pwd -P)"
DOCKER="$ROOT/bench/arena-docker.sh"
ARENA="$ROOT/work/arena-checkers"
ARENA_REV="$(cat "$SELF_DIR/ARENA_REV")"
LOGS="$ROOT/work/logs/baselines"

wait_for=0
lka=(./lka.py)
while [ "$#" -gt 0 ]; do
  case "$1" in
    --wait) wait_for=1; shift ;;
    --bash-sh) lka=("$ROOT/bench/arena-lka.py"); shift ;;
    *) break ;;
  esac
done
if [ "$#" -eq 0 ] || [ "$1" = "-h" ] || [ "$1" = "--help" ]; then
  awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"
  exit 0
fi

mkdir -p "$LOGS"

if [ ! -f "$ARENA/lka.py" ]; then
  "$DOCKER" bash -c "set -e; git clone --quiet https://github.com/leanprover/lean-kernel-arena '$ARENA'; git -C '$ARENA' checkout --quiet '$ARENA_REV'"
fi
# Read the detached HEAD directly: no git command is run on the host inside a
# tree that container builds can write to.
have="$(cat "$ARENA/.git/HEAD" 2>/dev/null || true)"
[ "$have" = "$ARENA_REV" ] || echo "warning: $ARENA is at '$have', not at the pinned $ARENA_REV" >&2

for name in "$@"; do
  [ -f "$ARENA/checkers/$name.yaml" ] || { echo "no such checker: $name" >&2; exit 2; }
done

for name in "$@"; do
  "$DOCKER" --detach "$LOGS/build-$name.log" --name "rlvm-baseline-build-$name" \
      --docker-arg --cpu-shares=256 -w "$ARENA" \
      "${lka[@]}" -v build-checker "$name"
done

if [ "$wait_for" = 1 ]; then
  rc=0
  for name in "$@"; do
    "$DOCKER" --wait "$LOGS/build-$name.log" || rc=1
  done
  exit "$rc"
fi
