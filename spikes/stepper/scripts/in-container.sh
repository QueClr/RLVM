#!/usr/bin/env bash
# Runs INSIDE the rlvm-arena container (see pbatch.sh). Reads a batch file with one run per line:
#     <label><TAB><command with arguments, split on blanks>
# and measures every command with bench/measure.sh (perf stat over the whole process tree, GNU time, as the arena
# does). Prints, per run, the program's RESULT / STATS / DEEP / DUEL lines and one "JSON <label> {...}" line.
set -uo pipefail
batch="$1"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
while IFS=$'\t' read -r label cmd; do
  [ -z "${label:-}" ] && continue
  case "$label" in \#*) continue ;; esac
  echo "RUN $label :: $cmd"
  # shellcheck disable=SC2086
  json="$("$root/bench/measure.sh" -- $cmd 2> >(grep -E '^(RESULT|STATS|DEEP|DUEL|VERIFY|MISMATCH)|Exception|Error' | cut -c1-4000 >&2))"
  sleep 0.05
  echo "JSON $label $json"
done < "$batch"
