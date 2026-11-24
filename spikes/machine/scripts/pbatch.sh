#!/usr/bin/env bash
# Measure a batch of commands in ONE arena-shaped container (8 performance cores, 16 GB) and format the result.
#   scripts/pbatch.sh <batch file below work/> [<output file>]
# Batch format: see scripts/in-container.sh. All paths in the commands must be absolute host paths below the
# project root (mounted at the same path in the container). The raw log is kept next to the output file.
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/../env.sh"
batch="$(realpath "$1")"
out="${2:-${batch%.batch}.txt}"
raw="$OUT/raw/$(basename "${out%.txt}").raw"
mkdir -p "$OUT/raw"
"$RLVM_ROOT/bench/arena-docker.sh" --arena-limits --no-network -e JAVA_HOME="$JAVA_HOME" \
    bash "$SPIKE/scripts/in-container.sh" "$batch" > "$raw" 2>&1 || true
python3 -I "$SPIKE/scripts/fmt.py" < "$raw" | tee "$out"
