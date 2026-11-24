#!/bin/sh
# bench/workloads/build-gruszka-knobs.sh - build an UNMODIFIED copy of gruszka with its own `knobs`
# feature (work/workloads/build/gruszka-knobs), which turns its statistics on: SK_DECL_INSTR=1 then
# prints per declaration ticks, ns and counters (whnf, unfold, def_eq, infer, eval, ground machine `gr`).
# Runs INSIDE the rlvm-arena container.
set -eu
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root/work/workloads/build/gruszka-knobs"
RUSTFLAGS="-C target-cpu=native" cargo build --release --locked --offline --features knobs
ls -la target/release/gruszka
