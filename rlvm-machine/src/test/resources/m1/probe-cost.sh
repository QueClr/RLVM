#!/usr/bin/env bash
# The probe cost of ISA section 11: CPU time and instructions per operation of the value constructors
# (M1ProbeBench), measured like the spike's micro benchmark (ISA 0.3): every mode is run twice, with N1 and
# N2 iterations, under bench/measure.sh (perf stat -e instructions:u, task-clock) in the arena-shaped
# container; per-operation cost = (run(N2) - run(N1)) / (N2 - N1), minus the same quotient of the bare loop.
#
#   rlvm-machine/src/test/resources/m1/probe-cost.sh [--tree DIR] [--c2] [--small] [--out FILE] [MODE ...]
#
#   --tree DIR   the module tree whose target/classes and target/test-classes are measured
#                (default: the main tree rlvm-machine; a sandbox: work/sandbox/M1/rlvm-machine)
#   --c2         HotSpot C2 instead of the Graal JIT
#   --small      tables that start small and grow to fit the 4096 values (everything in the first-level cache:
#                the setting of the spike's 45 / 38 instructions); default: the session sizes of ISA 1.6
#   --unpinned   do not wait for the ladder lock and do not use the arena's reserved CPUs: a container without
#                --arena-limits, started at once. Instruction counts are as good as in the pinned container;
#                CPU times are those of a host shared with whatever else runs (the table says so)
#   --out FILE   also write the table to FILE (default work/status/machine-M1-probe.txt)
# The classes must be compiled (scripts/machine-sandbox.sh test M1 -Dtest=M1ValuesTest does it); they are
# copied first, so a rebuild during the measurement does not disturb it. ONE container, started under the
# ladder lock (work/perf-history/.ladder.lock): all runs of the table are measured back to back in it.
set -euo pipefail
if [ -z "${M1_PROBE_COPY:-}" ]; then
  # run from a private copy: the script may be edited or republished while a measurement waits for the lock
  copy="$(mktemp /tmp/m1-probe-cost.XXXXXX.sh)"
  cp "${BASH_SOURCE[0]}" "$copy"
  M1_PROBE_COPY="$copy" M1_PROBE_SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)" exec bash "$copy" "$@"
fi
trap 'rm -f "$M1_PROBE_COPY"' EXIT
ROOT="$(cd "$M1_PROBE_SELF/../../../../.." && pwd -P)"
case "$ROOT" in */work/sandbox/*) ROOT="${ROOT%%/work/sandbox/*}" ;; esac
case "$ROOT" in */work/logs/*) ROOT="${ROOT%%/work/logs/*}" ;; esac
TREE="$ROOT/rlvm-machine"
OUT="$ROOT/work/status/machine-M1-probe.txt"
JIT=""
JITNAME="graal-jit"
SMALL=""
INSIDE=""
UNPINNED=""
MODES=()
while [ $# -gt 0 ]; do
  case "$1" in
    --tree) TREE="$(cd "$2" && pwd -P)"; shift 2 ;;
    --c2) JIT="-XX:-UseJVMCICompiler"; JITNAME="c2"; shift ;;
    --small) SMALL="small"; shift ;;
    --unpinned) UNPINNED=1; shift ;;
    --out) OUT="$2"; shift 2 ;;
    --inside) INSIDE="$2"; ROOT="$3"; shift 3 ;;
    *) MODES+=("$1"); shift ;;
  esac
done
[ ${#MODES[@]} -gt 0 ] || MODES=(app1 app2 app3 ext2 lam prune3 clo3 sort miss2)
N1=200000000
N2=800000000
JAVA="$ROOT/.toolchain/graalvm-ce-25/bin/java"

if [ -n "$INSIDE" ]; then
  # inside the container: INSIDE is the scratch directory with the copied classes
  CP="$INSIDE/classes:$INSIDE/test-classes"
  for j in rlvm-api rlvm-store rlvm-level rlvm-nat; do
    CP="$CP:$(ls "$ROOT/.toolchain/m2/rlvm/$j"/*/"$j"-*.jar | grep -v sources | head -1)"
  done
  for mode in loop "${MODES[@]}"; do
    n1=$N1; n2=$N2
    if [ "$mode" = miss2 ]; then n1=$((N1 / 10)); n2=$((N2 / 10)); fi
    for n in $n1 $n2; do
      "$ROOT/bench/measure.sh" --require-perf -o "$INSIDE/$mode.$n.json" -- \
        "$JAVA" -XX:+UseSerialGC -Xms1g -Xmx3g $JIT -cp "$CP" rlvm.machine.M1ProbeBench "$mode" "$n" $SMALL \
        > "$INSIDE/$mode.$n.out" 2>&1 || echo "FAILED $mode $n" >&2
    done
  done
  exit 0
fi

mkdir -p "$ROOT/work/logs"
TMP="$(mktemp -d "$ROOT/work/logs/m1-probe.XXXXXX")"
trap 'rm -rf "$TMP" "$M1_PROBE_COPY"' EXIT
cp -r "$TREE/target/classes" "$TMP/classes"
cp -r "$TREE/target/test-classes" "$TMP/test-classes"
cp "$M1_PROBE_COPY" "$TMP/probe-cost.sh"
export M1_PROBE_COPY="$TMP/probe-cost.sh" M1_PROBE_SELF
ARGS=(--inside "$TMP" "$ROOT")
[ -z "$JIT" ] || ARGS+=(--c2)
[ -z "$SMALL" ] || ARGS+=(--small)
if [ -n "$UNPINNED" ]; then
  WHERE="container WITHOUT the arena limits, host load average $(cut -d' ' -f1 /proc/loadavg): instructions exact, CPU times inflated"
  "$ROOT/bench/arena-docker.sh" bash "$TMP/probe-cost.sh" "${ARGS[@]}" "${MODES[@]}"
else
  WHERE="arena container (8 reserved CPUs, 16 GB), under the ladder lock"
  flock -w 3600 "$ROOT/work/perf-history/.ladder.lock" \
    "$ROOT/bench/arena-docker.sh" --arena-limits bash "$TMP/probe-cost.sh" "${ARGS[@]}" "${MODES[@]}"
fi

python3 - "$TMP" "$OUT" "$N1" "$N2" "$TREE" "$JITNAME" "${SMALL:-session sizes of ISA 1.6 (vtab 2 MiB, rtab 1 MiB)}" "$WHERE" loop "${MODES[@]}" <<'PY'
import json, re, sys, time
tmp, out, n1, n2, tree, jit, tables, where = sys.argv[1:9]
modes = sys.argv[9:]
n1, n2 = int(n1), int(n2)
def run(mode, n):
    d = json.loads(open('%s/%s.%d.json' % (tmp, mode, n)).read().strip().splitlines()[-1])
    text = open('%s/%s.%d.out' % (tmp, mode, n)).read()
    m = re.search(r'cpu-ns=([0-9.]+)', text)
    return d['instructions'], d['cpu_time_s'], (float(m.group(1)) if m else float('nan'))
lines = ['# M1 probe cost (%s); tree %s' % (time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()), tree),
         '# JVM %s, SerialGC, one thread; tables: %s; perf stat instructions:u + task-clock' % (jit, tables),
         '# %s' % where,
         '# per operation = (run(N2) - run(N1)) / (N2 - N1), N1 = %d, N2 = %d (miss2: a tenth); net = minus the bare loop' % (n1, n2),
         '%-7s | %13s | %13s | %9s | %13s | %s' % ('mode', 'cpu ns/op', 'net cpu ns/op', 'instr/op', 'net instr/op', 'in-process cpu ns/op of the N2 pass')]
loop = None
for mode in modes:
    a, b = (n1 // 10, n2 // 10) if mode == 'miss2' else (n1, n2)
    try:
        i1, c1, _ = run(mode, a)
        i2, c2, p = run(mode, b)
    except Exception as e:
        lines.append('%-7s | failed: %s' % (mode, e))
        continue
    ins = (i2 - i1) / (b - a)
    cpu = (c2 - c1) * 1e9 / (b - a)
    if mode == 'loop':
        loop = (ins, cpu)
        lines.append('%-7s | %13.3f | %13s | %9.1f | %13s | %.3f' % (mode, cpu, '-', ins, '-', p))
    else:
        lines.append('%-7s | %13.3f | %13.3f | %9.1f | %13.1f | %.3f' % (mode, cpu, cpu - loop[1], ins, ins - loop[0], p))
text = '\n'.join(lines) + '\n'
sys.stdout.write(text)
open(out, 'w').write(text)
PY
