#!/bin/sh
# bench/warm.sh - warm wall clock (OWNER-NOTES 12): rlvm -X passes=N per corpus and worker count,
# inside one bench/quiet.py window; baselines (gruszka, nano-lean) in the arena container.
#
#   bench/warm.sh --dist work/dist --launcher jvm --engine machine --workers 8,1 --passes 5 init std
#   bench/warm.sh --baseline gruszka --workers 8,1 init std
#
# The implementation and the full documentation are in warm.py (bench/warm.sh --help).
exec python3 "$(dirname "$0")/warm.py" "$@"
