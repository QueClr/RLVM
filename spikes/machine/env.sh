# Source this file:  . spikes/machine/env.sh
# Toolchain and output locations for the machine-tier spike (same pins as spikes/platform/env.sh).
RLVM_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export RLVM_ROOT
export JAVA_HOME="$RLVM_ROOT/.toolchain/graalvm-ce-25"
export GRAAL_CE="$JAVA_HOME"
export GRAAL_EE="$RLVM_ROOT/.toolchain/oracle-graalvm-25"      # Oracle GraalVM 25.0.4 (GFTC), optional
export M2_REPO="$RLVM_ROOT/.toolchain/m2"
export MVN="$RLVM_ROOT/.toolchain/apache-maven-3.9.16/bin/mvn -Dmaven.repo.local=$M2_REPO -B -ntp"
export SPIKE="$RLVM_ROOT/spikes/machine"
export OUT="$RLVM_ROOT/work/machine-out"                       # build products, caches, logs (gitignored)
export DATA="$RLVM_ROOT/work/arena-site/generated"
mkdir -p "$OUT"
