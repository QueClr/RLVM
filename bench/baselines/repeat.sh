#!/bin/bash
# bench/baselines/repeat.sh - how repeatable is a baseline's instruction count?
#
#   bench/arena-docker.sh --arena-limits --no-network \
#       bench/baselines/repeat.sh N OUT.jsonl CHECKER [CHECKER...] -- TEST [TEST...]
#
# Runs bench/baselines/CHECKER.sh N times on each TEST (a test name of the
# published tarball such as perf/app-lam, or a path to an export) under
# bench/measure.sh and appends one JSON line per run to OUT.jsonl (the fields
# of measure.sh plus checker, test, run). Prints mean, CV and spread per
# (test, checker) at the end. To be run inside the container, from the
# project root.
set -euo pipefail
[ "$#" -ge 5 ] || { awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 2; }
n=$1; out=$2; shift 2
checkers=()
while [ "$#" -gt 0 ] && [ "$1" != "--" ]; do checkers+=("$1"); shift; done
[ "${1:-}" = "--" ] || { echo "repeat.sh: missing -- before the tests" >&2; exit 2; }
shift
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
cd "$root"
: > "$out"
for t in "$@"; do
  if [ -f "$t" ]; then f=$t; else f=$(ls work/arena-site/tests/*/"$t".ndjson | head -n 1); fi
  for c in "${checkers[@]}"; do
    for i in $(seq "$n"); do
      bench/measure.sh --in "$f" -c "bench/baselines/$c.sh \"\$IN\"" 2>/dev/null \
        | CHECKER="$c" TEST="$t" RUN="$i" python3 -c '
import json, os, sys
d = json.load(sys.stdin)
d.update(checker=os.environ["CHECKER"], test=os.environ["TEST"], run=int(os.environ["RUN"]))
print(json.dumps(d))' >> "$out"
    done
  done
done
python3 - "$out" <<'PY'
import collections, json, statistics, sys
groups = collections.defaultdict(list)
for line in open(sys.argv[1]):
    r = json.loads(line)
    groups[(r["test"], r["checker"])].append(r)
print(f"{'test':32s} {'checker':13s} runs  mean instr    CV %  spread %  rss MiB      exit")
for (test, checker), rs in groups.items():
    xs = [r["instructions"] for r in rs if r.get("instructions")]
    if not xs:
        print(f"{test:32s} {checker:13s} {len(rs):4d}  no instruction counts")
        continue
    m = statistics.mean(xs)
    rss = [r["max_rss_bytes"] / 2**20 for r in rs if r.get("max_rss_bytes")]
    print(f"{test:32s} {checker:13s} {len(xs):4d}  {m:.4e}  {100 * statistics.pstdev(xs) / m:6.2f}  {100 * (max(xs) - min(xs)) / m:8.2f}  "
          f"{min(rss):.0f}..{max(rss):.0f}  {sorted(set(r['exit_code'] for r in rs))}")
PY
