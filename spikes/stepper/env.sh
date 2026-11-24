# Source this file:  . spikes/stepper/env.sh
# Toolchain and output locations of the stepper-convention spike (same pins as spikes/machine/env.sh).
RLVM_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export RLVM_ROOT
export JAVA_HOME="$RLVM_ROOT/.toolchain/graalvm-ce-25"
export SPIKE="$RLVM_ROOT/spikes/stepper"
export OUT="$RLVM_ROOT/work/stepper-out"          # build products, caches, raw logs (outside the source tree)
export DATA="$RLVM_ROOT/work/exports"
mkdir -p "$OUT"
