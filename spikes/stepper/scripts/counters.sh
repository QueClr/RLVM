#!/usr/bin/env bash
# Hardware counters of the native images on the Init types workload (5 passes, 16 MiB sessions), in the arena
# container: instructions, branches, branch misses, cycles (user space). Output: results/counters.txt.
# The counts include the set-up (0.78 G instructions, variant "none").
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/../env.sh"
S="-Drlvm.passes=5 -Drlvm.roots=types -Drlvm.session=16777216 -Drlvm.tbits=20 -Drlvm.hcap=4000000 -Drlvm.ttbits=20 -Drlvm.textra=1000000"
cat > "$OUT/counters-in.sh" <<INNER
#!/usr/bin/env bash
for img in stepper-ce stepper-ce-fi; do
  for v in none R Ri W0 W1n W1 H8 H64 Hinf Hi64 HT64; do
    perf stat -e instructions:u,branches:u,branch-misses:u,cycles:u -x, -o /tmp/p.txt $OUT/ni/\$img -Xmx12g $S run $OUT/init.bin \$v > /dev/null
    echo "\$img \$v \$(grep -v 'not counted\|not supported' /tmp/p.txt | awk -F, 'NF > 3 {n = split(\$3, a, "/"); printf "%s=%.3fG ", a[n-1], \$1 / 1e9}')"
  done
done
INNER
"$RLVM_ROOT/bench/arena-docker.sh" --arena-limits --no-network bash "$OUT/counters-in.sh" 2>&1 | grep '^stepper' | tee "$SPIKE/results/counters.txt"
