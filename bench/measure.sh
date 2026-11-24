#!/bin/sh
# bench/measure.sh - run one command the way the Lean Kernel Arena measures a
# checker (perf stat + GNU time) and print one JSON line:
#   {"exit_code", "instructions", "virtual_time_s", "cpu_time_s", "wall_time_s", "max_rss_bytes", ...}
#
#   bench/arena-docker.sh bench/measure.sh [options] -- command [args...]
#   bench/arena-docker.sh bench/measure.sh [options] -c 'shell command using "$IN"' --in FILE
#
# Meant to run inside the rlvm-arena container (perf needs CAP_PERFMON there).
# The implementation, and the full documentation, is in measure.py.
exec python3 "$(dirname "$0")/measure.py" "$@"
