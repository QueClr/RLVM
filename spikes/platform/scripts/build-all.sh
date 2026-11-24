#!/usr/bin/env bash
# Rebuild everything the measurement scripts need (JVM-mode artifacts only; native images are built
# on demand with scripts/build-native.sh). Takes ~15 s; offline after the first Maven run.
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/../env.sh"
mkdir -p "$OUT/plain"
"$JAVA_HOME/bin/javac" -nowarn -d "$OUT/plain" "$SPIKE"/plain/*.java 2>&1 | grep -v "sun.misc.Unsafe\|^Note:\|warning\|\^$" || true
"$JAVA_HOME/bin/jar" --create --file "$OUT/plain.jar" -C "$OUT/plain" .
offline=""
[ -d "$M2_REPO/org/graalvm/truffle/truffle-api/25.5.5.0.3" ] && offline="-o"
$MVN -q $offline -f "$SPIKE/truffle-lc/pom.xml" package
# source tree for JavacBench: spike sources + generated Truffle sources (21k lines)
rm -rf "$OUT/javac-src" && mkdir -p "$OUT/javac-src"
cp -r "$SPIKE/truffle-lc/src/main/java/rlvm" "$OUT/javac-src/"
cp -r "$SPIKE/truffle-lc/target/generated-sources/annotations/rlvm/spike/lc/." "$OUT/javac-src/rlvm/spike/lc/"
echo "built: $OUT/plain (classes), $OUT/plain.jar, $SPIKE/truffle-lc/target/lc.jar (+ target/lib), $OUT/javac-src"
