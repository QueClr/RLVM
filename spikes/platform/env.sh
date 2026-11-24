# Source this file:  . spikes/platform/env.sh
# Pins the toolchain used by every spike script. Nothing here touches ~/.m2 or the system JDK.
RLVM_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export RLVM_ROOT
export JAVA_HOME="$RLVM_ROOT/.toolchain/graalvm-ce-25"
export GRAAL_CE="$JAVA_HOME"
# Oracle GraalVM 25 (GFTC licence) - only present if scripts/fetch-oracle-graalvm.sh was run.
export GRAAL_EE="$RLVM_ROOT/.toolchain/oracle-graalvm-25"
export M2_REPO="$RLVM_ROOT/.toolchain/m2"
export MVN="$RLVM_ROOT/.toolchain/apache-maven-3.9.16/bin/mvn -Dmaven.repo.local=$M2_REPO -B -ntp"
export SPIKE="$RLVM_ROOT/spikes/platform"
export OUT="$RLVM_ROOT/work/platform-out"   # all build products / large temp files (gitignored)
mkdir -p "$OUT"
