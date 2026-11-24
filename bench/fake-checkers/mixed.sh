#!/bin/sh
# Fake checker that exercises every outcome run-suite.py distinguishes.
# Takes the export path as $1 if given, else from $IN. The verdict depends
# only on the length of the test's file name (without .ndjson) modulo 8, so
# it is stable and bench/selftest.py can predict it:
#   0-2 : the right answer for the bucket (good -> accept, bad -> reject)
#   3   : accept
#   4   : reject
#   5   : decline
#   6   : crash (exit 139)
#   7   : hang until killed by the timeout
file="${1:-$IN}"
[ -r "$file" ] || { echo "mixed: cannot read '$file'" >&2; exit 3; }
name="$(basename "$file" .ndjson)"
case $(( ${#name} % 8 )) in
  0|1|2) case "$file" in */bad/*) echo "mixed: type error (simulated)" >&2; exit 1 ;; *) exit 0 ;; esac ;;
  3) exit 0 ;;
  4) echo "mixed: rejected (simulated)" >&2; exit 1 ;;
  5) exit 2 ;;
  6) echo "mixed: simulated crash" >&2; exit 139 ;;
  *) sleep 86399.25 ;;  # odd duration: lets selftest.py find survivors by command line
esac
