#!/usr/bin/env bash
# official-verdicts.sh DIR OUT.tsv
#
# The answer of the official checker (leanchecker --from-export, Lean v4.36.0) to every DIR/*.ndjson,
# one row per file: <file name without .ndjson> TAB <exit status> TAB <first 600 characters of the
# output, "|" for line ends>. The rows are what OfficialGolden reads (the tests dump the exports
# without a stored answer under their SHA-256 with -Drlvm.inductive.golden.dump=DIR).
#
# The checker runs INSIDE the arena Docker image (bench/arena-docker.sh), one container for the
# whole directory, no network; the toolchain directory is mounted read-only. DIR and OUT.tsv must
# lie below work/ (the only writable mount).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd -P)"
[ $# -eq 2 ] || { echo "usage: official-verdicts.sh DIR OUT.tsv" >&2; exit 64; }
dir="$(realpath -e "$1")"; out="$(realpath "$2")"
tc="${RLVM_LEAN_TOOLCHAIN:-$HOME/.elan/toolchains/leanprover--lean4---v4.36.0}"
[ -x "$tc/bin/leanchecker" ] || { echo "official-verdicts.sh: no leanchecker in $tc" >&2; exit 1; }
"$ROOT/bench/arena-docker.sh" --no-network --perf none --mount "$tc:/opt/lean-official:ro" -- bash -c '
  dir="$1"; out="$2"; : > "$out.tmp"; n=0
  for f in "$dir"/*.ndjson; do
    [ -e "$f" ] || continue
    msg="$(timeout 300 /opt/lean-official/bin/leanchecker --from-export "$f" 2>&1)" && code=0 || code=$?
    msg="$(printf "%s" "$msg" | tr "\t" " " | tr "\n" "|" | cut -c1-600)"
    printf "%s\t%d\t%s\n" "$(basename "$f" .ndjson)" "$code" "$msg" >> "$out.tmp"
    n=$((n + 1))
  done
  mv "$out.tmp" "$out"; echo "official-verdicts.sh: $n files" >&2' _ "$dir" "$out"
