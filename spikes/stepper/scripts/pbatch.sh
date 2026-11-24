#!/usr/bin/env bash
# Measures a batch of commands in ONE arena-shaped container (8 performance cores, 16 GB) and formats the result.
#   scripts/pbatch.sh <batch file below work/> <output file>
# Batch format: see in-container.sh. All paths in the commands are absolute host paths below the project root
# (mounted at the same path in the container). The raw log is kept in $OUT/raw/.
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/../env.sh"
batch="$(realpath "$1")"
out="$2"
raw="$OUT/raw/$(basename "${out%.txt}").raw"
mkdir -p "$OUT/raw"
"$RLVM_ROOT/bench/arena-docker.sh" --arena-limits --no-network -e JAVA_HOME="$JAVA_HOME" \
    bash "$SPIKE/scripts/in-container.sh" "$batch" > "$raw" 2>&1 || true
python3 -I "$SPIKE/scripts/fmt.py" < "$raw" > "$out"
echo "wrote $out ($(wc -l < "$out") lines)"
