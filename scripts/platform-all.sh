#!/usr/bin/env bash
# scripts/platform-all.sh - the measurement series behind *agentic-note*: design-platform-document, as it was run on
# 2026-11-24 (each step appends to its tag; a step can be repeated to add runs).
#
#   scripts/platform-all.sh STEP...                                  (host)
#   RLVM_PLATFORM_ENGINE=machine scripts/platform-all.sh STEP...     (another engine: -X engine=NAME)
#
# Needs the frozen distribution with all three launchers:
#   scripts/build.sh --published --pgo --opt 3 --dist work/steward/platform/dist
# scripts/platform-matrix.py does the work; results in work/steward/platform/results/TAG.jsonl;
# tables: scripts/platform-report.py TAG... (the tables of the series are in *agentic-note*: design-platform-document).
#
# EIGHT workers, in the arena-shaped container (8 performance cores, 16 GB), holding the measurement
# lock of bench/track/ladder.py per group of runs (one group = the configurations of one repeat,
# corpus and worker count, back to back):
#   small8         JVM / native CE / native Oracle PGO on init, std, cedar, reachability; 3 repeats,
#                  and a 4th in the opposite order (pgo, native, jvm)                        -> platforms
#   mathlib8       the three launchers on mathlib, one group                                 -> mathlib-paired
#   heap           JVM heap sizes and collectors with a growing heap, native image -Xmx10g   -> heap
#   heap2          the default, heaps that keep less free after a full collection, 10 GB again -> heap2
#   jit8           C2 / Graal as host JIT, 2 compiler threads / HotSpot's choice; 2 repeats   -> jit
#   gc8            Serial / Parallel / G1, capped and uncapped collector threads; 2 repeats   -> gc
#   mathlib4       JVM and native CE on mathlib with 4 workers (the arena run line's count)   -> mathlib-t4
# Prepared and NOT run on 2026-11-24 (the host had no room left); for the platform gate:
#   jit8-more      3 more repeats of the two C2 configurations, in the opposite order         -> jit
#   jit8-1w        the two C2 configurations with ONE worker in the 8-core container; 2 repeats -> jit-ct1
#   jvm-mathlib    mathlib: default against Graal as host JIT; HotSpot's compiler threads
#                  against the default                                                        -> jvm-mathlib
# (On the day, mathlib was also run once per launcher at different hours and loads: tag
# platforms-mathlib. Those three runs are not a comparison.)
#
# ONE worker, in a container with the two performance cores the arena-shaped container does not use
# (CPUs 18,19 of this host; 16 GB; no lock needed), so that they could run while other people
# measured in the eight. One worker thread, the loader before it, two JIT threads: two cores hold them.
#   small1         the three launchers on the four corpora; 3 repeats                         -> platforms-1w
#   jit1           C2 / Graal / C1 only as host JIT; 2 repeats                                -> jit-1w
#   mathlib1       the three launchers on mathlib, once each                                  -> platforms-mathlib-1w
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
SMALL=init,std,cedar,reachability
SPARE="${RLVM_SPARE_CPUS:-18,19}"
ENGINE="${RLVM_PLATFORM_ENGINE:-reference}"
M() { "$ROOT/scripts/platform-matrix.py" --engine "$ENGINE" "$@"; }
[ "$#" -gt 0 ] || { sed -n '2,37p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }
for step in "$@"; do
  echo "=== $step  [$(date -u +%H:%M:%SZ), load $(cut -d' ' -f1 /proc/loadavg)]"
  case "$step" in
    small8)       M --configs jvm,native,pgo --corpora "$SMALL" --threads 8 --repeat 3 --tag platforms
                  M --configs pgo,native,jvm --corpora "$SMALL" --threads 8 --repeat 1 --first-run 4 --tag platforms ;;
    mathlib8)     M --configs jvm,native,pgo --corpora mathlib --threads 8 --tag mathlib-paired --timeout 4200 ;;
    heap)         M --configs jvm-heap-max-13g,jvm-heap-fixed-10g,jvm-heap-fixed-9g,jvm-g1-2-max-13g,jvm-parallel-2-max-13g,native-max-10g \
                    --corpora mathlib --threads 8 --tag heap --timeout 4200 ;;
    heap2)        M --configs jvm,jvm-heap-track-15,jvm-heap-track-25,jvm-heap-fixed-10g --corpora mathlib --threads 8 --tag heap2 --timeout 4200 ;;
    jit8)         M --configs jvm-c2,jvm-graal,jvm-c2-auto,jvm-graal-auto --corpora "$SMALL" --threads 8 --repeat 2 --tag jit ;;
    jit8-more)    M --configs jvm-c2-auto,jvm-c2 --corpora "$SMALL" --threads 8 --repeat 3 --first-run 3 --tag jit ;;
    jit8-1w)      M --configs jvm-c2-auto,jvm-c2 --corpora "$SMALL" --threads 1 --repeat 2 --tag jit-ct1 ;;
    gc8)          M --configs jvm-c2,jvm-parallel-2,jvm-g1-2,jvm-parallel-auto,jvm-g1-auto --corpora "$SMALL" --threads 8 --repeat 2 --tag gc ;;
    mathlib4)     M --configs jvm,native --corpora mathlib --threads 4 --tag mathlib-t4 --timeout 4200 ;;
    jvm-mathlib)  M --configs jvm,jvm-graal --corpora mathlib --threads 8 --tag jvm-mathlib --timeout 4200
                  M --configs jvm-c2-auto,jvm --corpora mathlib --threads 8 --first-run 2 --tag jvm-mathlib --timeout 4200 ;;
    small1)       M --cpuset "$SPARE" --configs jvm,native,pgo --corpora "$SMALL" --threads 1 --repeat 3 --tag platforms-1w ;;
    jit1)         M --cpuset "$SPARE" --configs jvm-c2,jvm-graal,jvm-c1 --corpora "$SMALL" --threads 1 --repeat 2 --tag jit-1w ;;
    mathlib1)     M --cpuset "$SPARE" --configs jvm,native,pgo --corpora mathlib --threads 1 --tag platforms-mathlib-1w --timeout 4200 ;;
    *) echo "platform-all.sh: unknown step '$step'" >&2; exit 2 ;;
  esac
done
