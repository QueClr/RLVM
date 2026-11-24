#!/usr/bin/env bash
# Measures the loader like the arena measures a checker (perf stat in the rlvm-arena container, 8 cores,
# 16 GB): CPU time, wall clock, peak RSS, user-space instructions, instructions per input byte.
#
#   rlvm-load/bench/measure-load.sh LABEL FILE [LoadTool options...]
#   e.g.  rlvm-load/bench/measure-load.sh store work/exports/init.ndjson --target store
#         rlvm-load/bench/measure-load.sh reader-only work/exports/init.ndjson --reader-only
#
# One JSON line per run is appended to work/rlvm-load/measure/runs.jsonl and printed.
# The JVM flags are the launcher's (scripts/build.sh: Serial GC, C2 without JVMCI); the heap is 4 GiB
# unless RLVM_LOAD_HEAP is set. The jars are the published ones in .toolchain/m2.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
label="$1"; file="$2"; shift 2
m2="$root/.toolchain/m2/rlvm"
cp=""
for m in rlvm-load rlvm-store rlvm-export rlvm-level rlvm-nat; do
  cp="$cp:$m2/$m/0.1.0-SNAPSHOT/$m-0.1.0-SNAPSHOT.jar"
done
cp="${RLVM_LOAD_CP:-${cp#:}}"
heap="${RLVM_LOAD_HEAP:--Xms4g -Xmx4g -Xmn1g}"
flags="-XX:+UseSerialGC -XX:-UseJVMCICompiler -XX:CICompilerCount=2 -XX:+ExitOnOutOfMemoryError $heap -Xss64m"
if [ "${1:-}" = "--reader-only" ]; then
  main="rlvm.export.tools.ExportLoad --sink null"
  shift
else
  main="rlvm.load.tools.LoadTool --quiet"
fi
out="$root/work/rlvm-load/measure"
mkdir -p "$out"
bytes=$(stat -L -c %s "$root/$file" 2>/dev/null || stat -L -c %s "$file")
json=$("$root/bench/arena-docker.sh" --arena-limits --no-network bench/measure.sh --in "$file" \
        -c "java $flags -cp $cp $main $* \"\$IN\"" 2> "$out/last.stderr")
python3 - "$label" "$file" "$bytes" "$json" "$*" <<'PY' | tee -a "$out/runs.jsonl"
import json, sys
label, file, size, raw, opts = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4], sys.argv[5]
r = json.loads(raw)
o = {"label": label, "file": file, "options": opts, "bytes": size, "exit_code": r["exit_code"],
     "cpu_time_s": r["cpu_time_s"], "wall_time_s": r["wall_time_s"],
     "max_rss_mb": round(r["max_rss_bytes"] / 1e6, 1), "instructions": r["instructions"],
     "instr_per_byte": round(r["instructions"] / size, 2) if r.get("instructions") else None,
     "instructions_kernel": r.get("instructions_kernel"), "counter_coverage": r.get("counter_coverage")}
print(json.dumps(o))
PY
