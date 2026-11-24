#!/usr/bin/env bash
# Exact user-space instruction counts (the arena's ranking metric) for one command, using the infra
# team's container wrapper (bench/arena-docker.sh + bench/measure.sh: perf stat -e instructions:u,
# all threads, plus GNU time). Prints the command's last output line and a one-line summary.
#
#   scripts/perf.sh [--limits] <label> -- <command> [args...]
#     --limits   run with the arena runner's shape: 8 CPUs, 16 GB RAM (changes JVM ergonomics)
#
# All paths must be absolute host paths below the project root (mounted at the same path).
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/../env.sh"
limits=()
if [ "${1:-}" = "--limits" ]; then limits=(--arena-limits); shift; fi
label="$1"; shift
[ "$1" = "--" ] && shift
raw="$("$RLVM_ROOT/bench/arena-docker.sh" "${limits[@]}" -e JAVA_HOME="$JAVA_HOME" "$RLVM_ROOT/bench/measure.sh" -- "$@" 2>&1 || true)"
printf '%s\n' "$raw" | python3 -I "$SPIKE/scripts/perf_fmt.py" "$label"
