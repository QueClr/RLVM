#!/usr/bin/env bash
# rlvm-driver/bin/freeze-cp.sh - a frozen copy of the driver's test class path, so that a long run
# (DriverBench, the invariance suite) is not disturbed by owners who publish jars meanwhile.
#
#   rlvm-driver/bin/freeze-cp.sh [TAG] [MODULE...]   prints the class path string; the copy lies in
#                                                    work/rlvm-driver/cp-TAG/ (default TAG: UTC time)
#
# MODULE: further published modules to add, by their short name: "machine" puts the published
# rlvm-machine jar on the class path (DriverBench --engine machine; the invariance suite with the
# machine as primary engine).
# Copies rlvm-driver/target/{classes,test-classes} (compile first: $MVN -o -q -pl rlvm-driver
# test-compile) and the published jars of the modules the tests need from .toolchain/m2.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
tag="${1:-$(date -u +%H%M%S)}"
[ "$#" -gt 0 ] && shift
dir="$ROOT/work/rlvm-driver/cp-$tag"
mkdir -p "$dir"
rm -rf "$dir/classes" "$dir/test-classes"
cp -r "$ROOT/rlvm-driver/target/classes" "$dir/classes"
cp -r "$ROOT/rlvm-driver/target/test-classes" "$dir/test-classes"
cp="$dir/classes:$dir/test-classes"
for m in admit api export inductive level load nat ref store "$@"; do
  jar="$ROOT/.toolchain/m2/rlvm/rlvm-$m/0.1.0-SNAPSHOT/rlvm-$m-0.1.0-SNAPSHOT.jar"
  [ -f "$jar" ] || { echo "freeze-cp.sh: missing $jar" >&2; exit 1; }
  cp "$jar" "$dir/"
  cp="$cp:$dir/rlvm-$m-0.1.0-SNAPSHOT.jar"
done
printf '%s\n' "$cp" > "$dir/cp.txt"
printf '%s\n' "$cp"
