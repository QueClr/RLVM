#!/usr/bin/env bash
# bench/slot.sh [-n SLOTS] [-c CLASS] -- CMD...
#
# Runs CMD while holding one of SLOTS host-wide slots of class CLASS (default: 8 slots of class
# "heavy"), waiting for a free slot if necessary. Use it for every job that needs more than about
# 2 GB of memory or more than two threads, so that thirty agents cannot exhaust the host together:
#
#   bench/slot.sh -- work/dist/bin/rlvm-jvm --threads 4 FILE          # a checker run, a test JVM, a campaign
#   bench/slot.sh -c ni -n 2 -- scripts/build.sh --published ...       # a native-image build (two at a time)
#
# The slot is a file lock under work/build/slots/ and is released when CMD exits, however it exits.
set -u
slots=8; class=heavy
while [ $# -gt 0 ]; do
  case "$1" in
    -n) slots="$2"; shift 2 ;;
    -c) class="$2"; shift 2 ;;
    --) shift; break ;;
    *) break ;;
  esac
done
[ $# -gt 0 ] || { echo "usage: bench/slot.sh [-n SLOTS] [-c CLASS] -- CMD..." >&2; exit 64; }
dir="$(cd "$(dirname "$0")/.." && pwd)/work/build/slots"
mkdir -p "$dir"
while :; do
  for i in $(seq 1 "$slots"); do
    exec {fd}>"$dir/$class.$i"
    if flock -n "$fd"; then
      "$@"
      exit $?
    fi
    exec {fd}>&-
  done
  sleep $((2 + RANDOM % 5))
done
