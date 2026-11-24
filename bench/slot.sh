#!/usr/bin/env bash
# bench/slot.sh [-n SLOTS] [-c CLASS] [-m MIN_GB] -- CMD...
#
# Host-wide limiter for heavy jobs: at most SLOTS (default 8) commands of a CLASS (default "heavy")
# run at a time on this host; the others wait. Native-image builds: -c ni -n 2.
# Memory guard: a job does not START while less than MIN_GB (default 14) of memory is available
# (it waits, at most 30 minutes, then starts anyway and says so): the host went into swap thrash
# twice when many agents started large JVMs together.
# This file is replaced by rename, never edited in place (running copies keep reading the old one).
set -u
slots=8; class=heavy; min_gb=14
while [ $# -gt 0 ]; do
  case "$1" in
    -n) slots="$2"; shift 2 ;;
    -c) class="$2"; shift 2 ;;
    -m) min_gb="$2"; shift 2 ;;
    --) shift; break ;;
    *) break ;;
  esac
done
[ $# -gt 0 ] || { echo "usage: bench/slot.sh [-n SLOTS] [-c CLASS] [-m MIN_GB] -- CMD..." >&2; exit 64; }
dir="$(cd "$(dirname "$0")/.." && pwd)/work/build/slots"
mkdir -p "$dir"
avail_gb() { awk '/MemAvailable/{printf "%d", $2/1048576}' /proc/meminfo; }
t0=$SECONDS; told=0
while :; do
  if [ "$(avail_gb)" -lt "$min_gb" ] && [ $((SECONDS - t0)) -lt 1800 ]; then
    if [ "$told" = 0 ]; then echo "slot.sh: waiting for memory ($(avail_gb) GB available, need $min_gb)" >&2; told=1; fi
    sleep $((5 + RANDOM % 10)); continue
  fi
  for i in $(seq 1 "$slots"); do
    exec {fd}>"$dir/$class.$i"
    if flock -n "$fd"; then
      if [ "$told" = 1 ]; then echo "slot.sh: starting after $((SECONDS - t0)) s ($(avail_gb) GB available)" >&2; fi
      "$@"; exit $?
    fi
    exec {fd}>&-
  done
  sleep $((2 + RANDOM % 5))
done
