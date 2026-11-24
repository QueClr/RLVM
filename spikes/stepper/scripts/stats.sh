#!/usr/bin/env bash
# Counters of every variant (JVM mode with -Drlvm.stats=true, host; no timing): judgments under R, operation
# library probes, frames written to the worklist, frame words, materialisations. Output: results/stats.txt.
#   CPUS=<list> pins the JVM (default: no pinning).
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/../env.sh"
pin=(); [ -n "${CPUS:-}" ] && pin=(taskset -c "$CPUS")
J=("${pin[@]}" "$JAVA_HOME/bin/java" -XX:+UseSerialGC -Xmx24g -Drlvm.stats=true -cp "$OUT/stepper.jar")
M=rlvm.spike.stepper.Main
S="-Drlvm.session=16777216 -Drlvm.tbits=20 -Drlvm.hcap=4000000 -Drlvm.ttbits=20 -Drlvm.textra=1000000"
for corpus in init cedar; do
  for roots in all types; do
    for v in R Ri W0 W1 W1n H0 H4 H8 H32 H64 H256 Hinf Hi64; do
      # shellcheck disable=SC2086
      "${J[@]}" $S -Drlvm.roots=$roots $M run "$OUT/$corpus.bin" $v | grep -E '^(RESULT|STATS)' | sed -e "s/^/$roots /"
    done
  done
done > "$SPIKE/results/stats.txt" 2>&1
wc -l "$SPIKE/results/stats.txt"
