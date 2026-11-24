#!/bin/sh
# bench/workloads/build-nano-lean-instrumented.sh - build the instrumented copy of nano-lean v0.4.2
# (work/workloads/build/nano-lean-instrumented, see docs/research/13) INSIDE the rlvm-arena container:
#   target/release/nl-fast          unmodified checking logic + per-declaration instruction log (NL_DECL_LOG)
#   target-vstats/release/nl-fast   feature vstats: nano-lean's own counters + the RLVM workload counters
#   target-fp/release/nl-fast       as the first, with frame pointers (for perf record --call-graph fp)
# No PGO (the arena build uses scripts/pgo.sh); otherwise the arena's flags.
# usage: build-nano-lean-instrumented.sh [plain] [vstats] [fp]     (default: all three)
set -eu
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root/work/workloads/build/nano-lean-instrumented"
flags="-C target-cpu=native -C llvm-args=-align-all-functions=6"
[ "$#" -gt 0 ] || set -- plain vstats fp
for v in "$@"; do
  case "$v" in
    plain)  RUSTFLAGS="$flags" cargo build --release --locked --offline --bin nl-fast ;;
    vstats) RUSTFLAGS="$flags" cargo build --release --locked --offline --bin nl-fast --features vstats --target-dir target-vstats ;;
    fp)     RUSTFLAGS="$flags -C force-frame-pointers=yes" cargo build --release --locked --offline --bin nl-fast --target-dir target-fp ;;
  esac
done
ls -la target*/release/nl-fast
