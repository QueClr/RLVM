#!/bin/sh
# Exact user-space instruction counts (the arena metric: perf stat -e instructions:u, all threads)
# for the spikes/memory micro-benchmarks, using the project's measurement container
# (bench/arena-docker.sh --arena-limits = 8 performance cores, 16 GB; bench/measure.sh = perf stat + GNU time).
# Each run is a whole JVM process, so every number includes JVM start-up and JIT compilation;
# compare runs against the matching baseline run.
#   usage: sh run_instr.sh [section ...]   sections: gc scale store spine reuse deep hashcons extra
# Output: results/instr-<section>.txt, one line per run:
#   <instructions G> | cpu s | wall s | rss MB | kernel-instr G | [jvm flags] main args
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
JAVA=$ROOT/.toolchain/graalvm-ce-25/bin/java
OUT=$HERE/results
mkdir -p "$OUT"

m() { # m <outfile> <jvm flags> <main + args>
  f=$1; shift; flags=$1; shift
  raw=$("$ROOT/bench/arena-docker.sh" --arena-limits "$ROOT/bench/measure.sh" -- "$JAVA" $flags -cp "$HERE/build" "$@" 2>&1)
  printf '%s\n' "$raw" | python3 -I -c '
import sys, json
label = sys.argv[1]
lines = sys.stdin.read().splitlines()
js = None
for l in lines:
    if l.startswith("{") and "instructions" in l:
        try: js = json.loads(l)
        except Exception: pass
if js is None:
    print("FAILED | " + label + " | " + " / ".join(lines[-3:]))
else:
    print("%8.3f G | cpu %6.2f s | wall %6.2f s | rss %5d MB | kernel %6.3f G | exit %s | %s" % (
        js["instructions"] / 1e9, js["cpu_time_s"], js["wall_time_s"], js["max_rss_bytes"] / 1e6,
        js.get("instructions_kernel", 0) / 1e9, js["exit_code"], label))
    for l in lines:
        if l.startswith("alloc ") or l.startswith("depth ") or l.startswith("old  ") or l.startswith("retained"):
            print("             " + l.strip())
' "[$flags] $*" >> "$OUT/$f"
}

sections=${*:-gc scale store spine reuse deep hashcons}
for s in $sections; do case $s in
gc)
  f=instr-gc.txt; : > "$OUT/$f"
  echo "##### baseline: JVM start + 512 MB warm-up only (GcCost 0.5 GB)" >> "$OUT/$f"
  m $f "-XX:+UseSerialGC -Xms8g -Xmx8g -Xmn4g" GcCost 0.5 16 0 0
  echo "##### S1: pure churn (16 GB allocated, nothing retained)" >> "$OUT/$f"
  A="16 16 0 0"
  for mn in 64m 512m 4g; do m $f "-XX:+UseSerialGC -Xms8g -Xmx8g -Xmn$mn" GcCost $A; done
  m $f "-XX:+UseParallelGC -Xms8g -Xmx8g -Xmn512m" GcCost $A
  m $f "-XX:+UseParallelGC -Xms8g -Xmx8g -Xmn512m -XX:ParallelGCThreads=1" GcCost $A
  m $f "-XX:+UseG1GC -Xms8g -Xmx8g" GcCost $A
  m $f "-XX:+UseZGC -Xms8g -Xmx8g" GcCost $A
  echo "##### S2: declaration = 64 MB allocated, 10% of nodes retained until the declaration ends" >> "$OUT/$f"
  A="16 64 0.1 0"
  for mn in 64m 256m 1g 4g; do m $f "-XX:+UseSerialGC -Xms8g -Xmx8g -Xmn$mn" GcCost $A; done
  m $f "-XX:+UseSerialGC -Xms8g -Xmx8g -Xmn1g -XX:+UseCompactObjectHeaders" GcCost $A
  m $f "-XX:+UseParallelGC -Xms8g -Xmx8g -Xmn1g" GcCost $A
  m $f "-XX:+UseParallelGC -Xms8g -Xmx8g -Xmn1g -XX:ParallelGCThreads=2" GcCost $A
  m $f "-XX:+UseParallelGC -Xms8g -Xmx8g -Xmn1g -XX:ParallelGCThreads=1" GcCost $A
  m $f "-XX:+UseG1GC -Xms8g -Xmx8g" GcCost $A
  m $f "-XX:+UseZGC -Xms8g -Xmx8g" GcCost $A
  echo "##### S3: S2 plus a static 1 GB old-generation object graph (36 M objects); baseline = same with 0.5 GB of work" >> "$OUT/$f"
  m $f "-XX:+UseSerialGC -Xms8g -Xmx8g -Xmn1g" GcCost 0.5 64 0.1 1000
  A="16 64 0.1 1000"
  m $f "-XX:+UseSerialGC -Xms8g -Xmx8g -Xmn1g" GcCost $A
  m $f "-XX:+UseParallelGC -Xms8g -Xmx8g -Xmn1g" GcCost $A
  m $f "-XX:+UseParallelGC -Xms8g -Xmx8g -Xmn1g -XX:ParallelGCThreads=2" GcCost $A
  m $f "-XX:+UseG1GC -Xms8g -Xmx8g" GcCost $A
  ;;
scale)
  f=instr-scale.txt; : > "$OUT/$f"
  echo "##### fixed total work (16 GB, decl 64 MB, keep 10%) split over T mutator threads" >> "$OUT/$f"
  for T in 1 2 4 8; do m $f "-XX:+UseSerialGC -Xms10g -Xmx10g -Xmn4g" GcCost 16 64 0.1 0 $T; done
  for T in 1 4 8; do m $f "-XX:+UseParallelGC -Xms10g -Xmx10g -Xmn4g" GcCost 16 64 0.1 0 $T; done
  m $f "-XX:+UseParallelGC -Xms10g -Xmx10g -Xmn4g -XX:ParallelGCThreads=2" GcCost 16 64 0.1 0 4
  m $f "-XX:+UseG1GC -Xms10g -Xmx10g" GcCost 16 64 0.1 0 4
  m $f "-XX:+UseZGC -Xms10g -Xmx10g" GcCost 16 64 0.1 0 4
  echo "##### young generation 1 GB, T = 1 and 4" >> "$OUT/$f"
  m $f "-XX:+UseSerialGC -Xms10g -Xmx10g -Xmn1g" GcCost 16 64 0.1 0 1
  m $f "-XX:+UseSerialGC -Xms10g -Xmx10g -Xmn1g" GcCost 16 64 0.1 0 4
  m $f "-XX:+UseParallelGC -Xms10g -Xmx10g -Xmn1g" GcCost 16 64 0.1 0 4
  m $f "-XX:+UseParallelGC -Xms10g -Xmx10g -Xmn1g -XX:ParallelGCThreads=2" GcCost 16 64 0.1 0 4
  ;;
store)
  f=instr-store.txt; : > "$OUT/$f"
  for mode in obj col aos chunk seg; do for ph in b bg bd; do
    m $f "-Xms6g -Xmx6g -Xmn1g -XX:+UseSerialGC" TermStore $mode 20000000 $ph
  done; done
  m $f "-Xms6g -Xmx6g -Xmn256m -XX:+UseSerialGC" TermStore obj 20000000 b
  m $f "-Xms6g -Xmx6g -Xmn256m -XX:+UseSerialGC" TermStore col 20000000 b
  m $f "-Xms6g -Xmx6g -Xmn1g -XX:+UseSerialGC -XX:+UseCompactObjectHeaders" TermStore obj 20000000 b
  m $f "-Xms6g -Xmx6g -Xmn1g -XX:+UseSerialGC -XX:+UseCompactObjectHeaders" TermStore obj 20000000 bg
  m $f "-Xms6g -Xmx6g -Xmn1g -XX:+UseParallelGC" TermStore obj 20000000 bg
  m $f "-Xms6g -Xmx6g -XX:+UseG1GC" TermStore obj 20000000 bg
  ;;
spine)
  f=instr-spine.txt; : > "$OUT/$f"
  for ml in 4 8; do for v in none copy cons flag4 claim exact; do
    m $f "-Xms4g -Xmx4g -Xmn2g -XX:+UseSerialGC" SpineAppend 4000000 $ml 0.05 20 escape $v
  done; done
  ;;
reuse)
  f=instr-reuse.txt; : > "$OUT/$f"
  for v in none youngfresh youngreuse oldbuild oldfresh oldreuse; do
    m $f "-Xms6g -Xmx6g -Xmn512m -XX:+UseSerialGC" CtorReuse 1000 20000 10000000 10 $v
  done
  ;;
deep)
  f=instr-deep.txt; : > "$OUT/$f"
  for cfg in "-XX:+UseSerialGC -Xmn256m" "-XX:+UseParallelGC -Xmn256m" "-XX:+UseParallelGC -Xmn256m -XX:ParallelGCThreads=1" "-XX:+UseG1GC" "-XX:+UseZGC"; do
    for d in 1000 1000000; do m $f "-Xms4g -Xmx4g $cfg" DeepStackGc $d 64; done
  done
  ;;
hashcons)
  f=instr-hashcons.txt; : > "$OUT/$f"
  for mode in none ids objopen hashmap weak; do m $f "-Xms8g -Xmx8g -Xmn2g -XX:+UseSerialGC" HashCons $mode 10000000; done
  ;;
extra)
  f=instr-extra.txt; : > "$OUT/$f"
  echo "##### Epsilon vs Serial, 8 GB of pure churn (heap 10 GB)" >> "$OUT/$f"
  m $f "-XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC -Xms10g -Xmx10g" GcCost 8 16 0 0
  m $f "-XX:+UseSerialGC -Xms10g -Xmx10g -Xmn512m" GcCost 8 16 0 0
  echo "##### hash-consing without the final full GCs (10 M interns, 5 M distinct)" >> "$OUT/$f"
  for mode in none ids objopen hashmap weak; do m $f "-Xms8g -Xmx8g -Xmn4g -XX:+UseSerialGC" HashCons $mode 10000000 nogc; done
  echo "##### spine strategies, slope between 10 and 30 repetitions (removes start-up and JIT)" >> "$OUT/$f"
  for v in copy cons flag4 claim exact; do for r in 10 30; do
    m $f "-Xms4g -Xmx4g -Xmn2g -XX:+UseSerialGC" SpineAppend 4000000 4 0.05 $r escape $v
  done; done
  echo "##### constructor reuse, young regime: slope between 20000 and 60000 repetitions" >> "$OUT/$f"
  for v in youngfresh youngreuse; do for r in 20000 60000; do
    m $f "-Xms6g -Xmx6g -Xmn512m -XX:+UseSerialGC" CtorReuse 1000 $r 10000000 10 $v
  done; done
  ;;
esac; done
echo done
