# Source this file from the repository root:  . ./env.sh
# Pins the toolchain for RLVM builds. Nothing here touches ~/.m2 or the system JDK.
RLVM_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export RLVM_ROOT
export JAVA_HOME="$RLVM_ROOT/.toolchain/graalvm-ce-25"
export PATH="$JAVA_HOME/bin:$PATH"
export M2_REPO="$RLVM_ROOT/.toolchain/m2"
# Usage: $MVN -q -pl rlvm-level test      (add -o for offline once dependencies are cached)
export MVN="$RLVM_ROOT/.toolchain/apache-maven-3.9.16/bin/mvn -Dmaven.repo.local=$M2_REPO -B -ntp"
