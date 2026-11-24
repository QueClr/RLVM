#!/usr/bin/env bash
# Builds everything the measurements need (about 10 minutes on this host):
#   jars for GraalVM CE 25.5.5.0.3 ($OUT/target) and for Oracle GraalVM 25.0.4 ($OUT/target-25.0.4),
#   the jar copies the batch files run ($OUT/frozen, $OUT/frozen2),
#   the binary caches init.bin / std.bin,
#   Truffle images machine-ce / machine-ee,
#   plain images machine-ce-m0..m3, machine-ee-m0 (arena access mode baked in), machine-ce-fin, machine-ee-fin,
#   machine-ce-m0-O3 and the PGO image machine-ee-m0-pgo.
# In the recorded campaign "frozen"/machine-ce*/machine-ee/-m* were built before the match-mode refinement, the
# calibration tools and the second worklist machine were added, "frozen2"/-fin after; built from the final
# sources they are the same binaries under two names (README, "Builds").
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/../env.sh"
cd "$SPIKE"
$MVN -o -q -f pom.xml package
for d in frozen frozen2; do
  rm -rf "$OUT/$d"; mkdir -p "$OUT/$d"; cp -r "$OUT/target/machine.jar" "$OUT/target/lib" "$OUT/$d/"
done
J="$JAVA_HOME/bin/java -XX:+UseSerialGC -Xmx12g -cp $OUT/target/machine.jar rlvm.spike.machine.Main"
[ -f "$OUT/init.bin" ] || $J parse "$DATA/init.ndjson" "$OUT/init.bin"
[ -f "$OUT/std.bin" ] || $J parse "$DATA/std.ndjson" "$OUT/std.bin"
B=scripts/build-native.sh
$B ce machine-ce truffle
for m in 0 1 2 3; do
  $B ce machine-ce-m$m plain -Drlvm.mem=$m
done
$B ce machine-ce-fin plain -Drlvm.mem=0
$B ce machine-ce-m0-O3 plain -Drlvm.mem=0 -O3
if [ -x "$GRAAL_EE/bin/native-image" ]; then
  $MVN -o -q -f pom.xml -Dgraalvm.version=25.0.4 -Dmachine.out="$OUT/target-25.0.4" package
  $B ee machine-ee truffle
  $B ee machine-ee-m0 plain -Drlvm.mem=0
  $B ee machine-ee-fin plain -Drlvm.mem=0
  $B ee machine-ee-m0-instr plain -Drlvm.mem=0 --pgo-instrument
  (cd "$OUT/ni-work" && "$OUT/ni/machine-ee-m0-instr" -Drlvm.tbits=24 -Drlvm.hcap=14000000 \
      -XX:ProfilesDumpFile="$OUT/ni-work/b.iprof" exp1 "$OUT/init.bin" b)
  $B ee machine-ee-m0-pgo plain -Drlvm.mem=0 --pgo="$OUT/ni-work/b.iprof"
fi
