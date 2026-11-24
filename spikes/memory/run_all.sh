#!/bin/sh
# Runs every micro-benchmark of spikes/memory and writes results/<name>.txt.
# The host is big.LITTLE (cpus 5-9,15-19 = Cortex-X925, the rest Cortex-A725): all runs are pinned
# to 8 big cores so that CPU time is comparable between runs and the JVM sees 8 CPUs like the arena runner.
#   usage: sh run_all.sh [section ...]     sections: layout spine store gc scale deep reuse hashcons
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
J=${JAVA_HOME:-$HERE/../../.toolchain/graalvm-ce-25}/bin
CPUS=${CPUS:-5-9,15-17}
OUT=$HERE/results
mkdir -p "$OUT" "$HERE/build"
"$J/javac" -d "$HERE/build" "$HERE"/src/*.java || exit 1

run() { # run <outfile> <jvm flags> <main + args>
  f=$1; shift; flags=$1; shift
  echo "--- [$flags] $*" >> "$OUT/$f"
  taskset -c "$CPUS" timeout 300 /usr/bin/time -f "    TOTAL user %U s sys %S s wall %e s maxrss %M KB" \
    "$J/java" $flags -cp "$HERE/build" "$@" 2>&1 | grep -v '^\[' >> "$OUT/$f"
}

sections=${*:-layout spine store gc scale deep reuse hashcons}
for s in $sections; do case $s in
layout)
  : > "$OUT/layout.txt"
  run layout.txt "-Xmx2g -XX:+UseSerialGC -XX:-DoEscapeAnalysis" Layout
  run layout.txt "-Xmx2g -XX:+UseSerialGC -XX:-DoEscapeAnalysis -XX:+UseCompactObjectHeaders" Layout
  run layout.txt "-Xmx40g -XX:+UseSerialGC -XX:-DoEscapeAnalysis" Layout   # > 32 GB: no compressed oops
  ;;
spine)
  : > "$OUT/spine.txt"
  for ml in 2.5 4 8; do
    run spine.txt "-Xms4g -Xmx4g -Xmn2g -XX:+UseSerialGC" SpineAppend 4000000 $ml 0.05 10 escape
    run spine.txt "-Xms4g -Xmx4g -Xmn2g -XX:+UseSerialGC -XX:+UseCompactObjectHeaders" SpineAppend 4000000 $ml 0.05 10 escape
  done
  ;;
store)
  : > "$OUT/store.txt"
  for m in obj col aos chunk seg; do run store.txt "-Xms6g -Xmx6g -Xmn1g -XX:+UseSerialGC" TermStore $m 20000000; done
  run store.txt "-Xms6g -Xmx6g -Xmn1g -XX:+UseSerialGC -XX:+UseCompactObjectHeaders" TermStore obj 20000000
  run store.txt "-Xms6g -Xmx6g -Xmn256m -XX:+UseSerialGC" TermStore obj 20000000
  run store.txt "-Xms6g -Xmx6g -Xmn256m -XX:+UseSerialGC" TermStore col 20000000
  run store.txt "-Xms6g -Xmx6g -Xmn1g -XX:+UseParallelGC" TermStore obj 20000000
  run store.txt "-Xms6g -Xmx6g -XX:+UseG1GC" TermStore obj 20000000
  for m in obj col aos chunk seg; do run store.txt "-Xms6g -Xmx6g -Xmn1g -XX:+UseSerialGC" TermStore $m 100000; done
  ;;
gc)
  : > "$OUT/gc.txt"
  echo "##### S1: pure churn (16 GB allocated, nothing retained)" >> "$OUT/gc.txt"
  A="16 16 0 0"
  run gc.txt "-XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC -Xms20g -Xmx20g" GcCost $A
  for mn in 64m 512m 4g; do run gc.txt "-XX:+UseSerialGC -Xms8g -Xmx8g -Xmn$mn" GcCost $A; done
  echo "##### S2: declaration = 64 MB allocated, 10% of nodes retained until the declaration ends" >> "$OUT/gc.txt"
  A="16 64 0.1 0"
  for mn in 64m 256m 1g 4g; do run gc.txt "-XX:+UseSerialGC -Xms8g -Xmx8g -Xmn$mn" GcCost $A; done
  run gc.txt "-XX:+UseParallelGC -Xms8g -Xmx8g -Xmn1g" GcCost $A
  run gc.txt "-XX:+UseParallelGC -Xms8g -Xmx8g -Xmn1g -XX:ParallelGCThreads=2" GcCost $A
  run gc.txt "-XX:+UseParallelGC -Xms8g -Xmx8g -Xmn1g -XX:ParallelGCThreads=1" GcCost $A
  run gc.txt "-XX:+UseG1GC -Xms8g -Xmx8g" GcCost $A
  run gc.txt "-XX:+UseZGC -Xms8g -Xmx8g" GcCost $A
  echo "##### S3: S2 plus a static 1 GB old-generation object graph (36 M objects)" >> "$OUT/gc.txt"
  A="16 64 0.1 1000"
  run gc.txt "-XX:+UseSerialGC -Xms8g -Xmx8g -Xmn1g" GcCost $A
  run gc.txt "-XX:+UseParallelGC -Xms8g -Xmx8g -Xmn1g" GcCost $A
  run gc.txt "-XX:+UseParallelGC -Xms8g -Xmx8g -Xmn1g -XX:ParallelGCThreads=2" GcCost $A
  run gc.txt "-XX:+UseG1GC -Xms8g -Xmx8g" GcCost $A
  ;;
scale)
  : > "$OUT/scale.txt"
  echo "##### fixed total work (16 GB, decl 64 MB, keep 10%) split over T mutator threads" >> "$OUT/scale.txt"
  for T in 1 2 4 8; do run scale.txt "-XX:+UseSerialGC -Xms10g -Xmx10g -Xmn4g" GcCost 16 64 0.1 0 $T; done
  for T in 1 2 4 8; do run scale.txt "-XX:+UseParallelGC -Xms10g -Xmx10g -Xmn4g" GcCost 16 64 0.1 0 $T; done
  run scale.txt "-XX:+UseParallelGC -Xms10g -Xmx10g -Xmn4g -XX:ParallelGCThreads=2" GcCost 16 64 0.1 0 4
  run scale.txt "-XX:+UseG1GC -Xms10g -Xmx10g" GcCost 16 64 0.1 0 4
  run scale.txt "-XX:+UseZGC -Xms10g -Xmx10g" GcCost 16 64 0.1 0 4
  echo "##### small young generation (1 GB) with 4 mutators: promotion of per-thread hot reference arrays" >> "$OUT/scale.txt"
  run scale.txt "-XX:+UseSerialGC -Xms10g -Xmx10g -Xmn1g" GcCost 16 64 0.1 0 4
  run scale.txt "-XX:+UseSerialGC -Xms10g -Xmx10g -Xmn1g -XX:+UseCondCardMark" GcCost 16 64 0.1 0 4
  run scale.txt "-XX:-UseJVMCICompiler -XX:+UseSerialGC -Xms10g -Xmx10g -Xmn1g" GcCost 16 64 0.1 0 4
  run scale.txt "-XX:-UseJVMCICompiler -XX:+UseSerialGC -Xms10g -Xmx10g -Xmn1g -XX:+UseCondCardMark" GcCost 16 64 0.1 0 4
  run scale.txt "-XX:+UseParallelGC -Xms10g -Xmx10g -Xmn1g" GcCost 16 64 0.1 0 4
  run scale.txt "-XX:+UseParallelGC -Xms10g -Xmx10g -Xmn1g -XX:ParallelGCThreads=2" GcCost 16 64 0.1 0 4
  run scale.txt "-XX:+UseSerialGC -Xms10g -Xmx10g -Xmn1g" GcCost 16 64 0.1 0 1
  ;;
deep)
  : > "$OUT/deep.txt"
  for cfg in "-XX:+UseSerialGC -Xmn256m" "-XX:+UseParallelGC -Xmn256m" "-XX:+UseParallelGC -Xmn256m -XX:ParallelGCThreads=1" "-XX:+UseG1GC" "-XX:+UseZGC"; do
    for d in 1000 100000 1000000 5000000; do run deep.txt "-Xms4g -Xmx4g $cfg" DeepStackGc $d 8; done
  done
  ;;
reuse)
  : > "$OUT/reuse.txt"
  for cfg in "-XX:+UseSerialGC -Xmn512m" "-XX:+UseSerialGC -Xmn512m -XX:+UseCompactObjectHeaders" "-XX:+UseParallelGC -Xmn512m -XX:ParallelGCThreads=2" "-XX:+UseG1GC"; do
    run reuse.txt "-Xms6g -Xmx6g $cfg" CtorReuse 1000 20000 10000000 10
  done
  ;;
hashcons)
  : > "$OUT/hashcons.txt"
  for m in ids objopen hashmap weak; do run hashcons.txt "-Xms8g -Xmx8g -Xmn2g -XX:+UseSerialGC" HashCons $m 10000000; done
  ;;
esac; done
echo done
