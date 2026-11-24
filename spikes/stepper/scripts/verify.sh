#!/usr/bin/env bash
# Correctness of the comparison (JVM mode, host; no timing):
#   1. every variant against R on every root of both corpora in ONE arena: identical normal form, value and verdict
#   2. the TRY experiment: CONV of every root of Init under a budget; outcomes and tick counts for D = 0, 64, inf
# Output: results/verify.txt.   CPUS=<list> pins the JVM (default: no pinning).
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/../env.sh"
pin=(); [ -n "${CPUS:-}" ] && pin=(taskset -c "$CPUS")
J=("${pin[@]}" "$JAVA_HOME/bin/java" -XX:+UseSerialGC -Xmx24g -cp "$OUT/stepper.jar")
M=rlvm.spike.stepper.Main
ALL=Ri,W0,W1,W1n,H0,H4,H8,H32,H64,H256,Hinf,Hi64,HT0,HT64,HTinf
{
  "${J[@]}" -Drlvm.tbits=24 -Drlvm.hcap=20000000 -Drlvm.ttbits=24 -Drlvm.textra=5000000 $M verify "$OUT/init.bin" $ALL
  "${J[@]}" -Drlvm.tbits=25 -Drlvm.hcap=40000000 -Drlvm.ttbits=25 -Drlvm.textra=9000000 $M verify "$OUT/cedar.bin" $ALL
  for b in 10 100 1000 100000; do
    for v in HT0 HT64 HTinf; do
      "${J[@]}" -Drlvm.tbits=24 -Drlvm.hcap=20000000 -Drlvm.ttbits=24 -Drlvm.textra=5000000 $M try "$OUT/init.bin" $v $b | grep '^TRY'
    done
  done
} > "$SPIKE/results/verify.txt" 2>&1
grep -c 'mismatches=0' "$SPIKE/results/verify.txt"; grep -E 'VERIFY (OK|FAILED)|MISMATCH' "$SPIKE/results/verify.txt"
