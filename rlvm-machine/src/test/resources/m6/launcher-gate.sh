#!/usr/bin/env bash
# rlvm-machine/src/test/resources/m6/launcher-gate.sh - the acceptance run of work package M6 (and the
# form of the v0 gate, *agentic-note*: rep-machine-work-packages, section 6): the published arena tests through the real launcher
# (rlvm-main, driver, admission) with `-X engine=machine -X verify=reference` (since 2026-11-25 a rejection of the
# machine is final by default: the gate asks for the re-check by name, or its two conditions on
# driver.warmAnomalies and driver.machineIncomplete would be vacuous), judged by the STATISTICS, not by the exit status
# alone: the driver re-checks a unit the machine declines with the reference kernel, so the verdict is
# right even when the machine does nothing.
#
#   launcher-gate.sh [options] [CORPUS | FILE.ndjson | DIR]...
#
#   CORPUS            tutorial (default; 141 tests) | bugs | corner-cases | other | perf | all (230 tests)
#                     = work/arena-site/tests/{good,bad}/CORPUS; a FILE or DIR is run as given, and its
#                     expected verdict is read from its path (/good/ = accept, /bad/ = reject) or, failing
#                     that, taken from a run with -X engine=reference
#   -j N              tests in parallel (default 4)
#   --heap SIZE       Java heap of each test's JVM (default 4g; the perf/ tests always get the launcher's own
#                     default heap, which three of them need); "launcher" = the launcher's default for all
#   -X KEY=VALUE      extra checker setting, repeatable (e.g. -X machine.debug=memocheck,poison,scopeaudit
#                     or -X machine.hostDepth=0)
#   --dist DIR        distribution (default work/dist; build it with scripts/dev.sh dist)
#   --jar PATH        the machine jar or class directory (default: the published jar in .toolchain/m2)
#   --timeout SEC     per test (default 600)
#   --out FILE        report (default work/status/machine-gate-<label>.txt)
#   --label NAME      label of the report
#   --allow-declines  exit 0 although the machine declined units (for work in progress: verdicts only)
#
# Per test: expected status, launcher status, units, machine accepted / rejected / declined, and the
# first machine failure message. Totals at the end. Exit status: 0 = every verdict is the expected one,
# the machine declined nothing but declarations that enter eager reduction (machine.declined == machine.eagerDeclines, driver.declined == 0), no
# WARM-ANOMALY and no MACHINE-INCOMPLETE event; 1 otherwise; 2 usage.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../../.." && pwd -P)"
case "$ROOT" in */work/sandbox/*) ROOT="${ROOT%%/work/sandbox/*}" ;; esac
TESTS="$ROOT/work/arena-site/tests"
dist="$ROOT/work/dist"
jar="$ROOT/.toolchain/m2/rlvm/rlvm-machine/0.1.0-SNAPSHOT/rlvm-machine-0.1.0-SNAPSHOT.jar"
jobs=4
heap=4g
timeout=600
out=""
label=""
allow=0
extra=()
inputs=()

while [ "$#" -gt 0 ]; do
  case "$1" in
    -j) jobs="${2:?}"; shift 2 ;;
    -X) extra+=("-X" "${2:?}"); shift 2 ;;
    --dist) dist="${2:?}"; shift 2 ;;
    --jar) jar="${2:?}"; shift 2 ;;
    --timeout) timeout="${2:?}"; shift 2 ;;
    --heap) heap="${2:?}"; shift 2 ;;
    --out) out="${2:?}"; shift 2 ;;
    --label) label="${2:?}"; shift 2 ;;
    --allow-declines) allow=1; shift ;;
    -h|--help) sed -n '2,29p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*) echo "launcher-gate: unknown option $1" >&2; exit 2 ;;
    *) inputs+=("$1"); shift ;;
  esac
done
[ "${#inputs[@]}" -gt 0 ] || inputs=(tutorial)
[ -x "$dist/bin/rlvm-jvm" ] || { echo "launcher-gate: no $dist/bin/rlvm-jvm (run scripts/dev.sh dist)" >&2; exit 2; }
[ -e "$jar" ] || { echo "launcher-gate: no machine jar $jar" >&2; exit 2; }

files=()
for i in "${inputs[@]}"; do
  case "$i" in
    all) while IFS= read -r f; do files+=("$f"); done < <(find "$TESTS/good" "$TESTS/bad" -name '*.ndjson' | LC_ALL=C sort) ;;
    tutorial|bugs|corner-cases|other|perf)
      while IFS= read -r f; do files+=("$f"); done < <(find "$TESTS/good/$i" "$TESTS/bad/$i" -name '*.ndjson' 2>/dev/null | LC_ALL=C sort) ;;
    *) if [ -d "$i" ]; then
         while IFS= read -r f; do files+=("$f"); done < <(find "$i" -name '*.ndjson' | LC_ALL=C sort)
       elif [ -f "$i" ]; then files+=("$i")
       else echo "launcher-gate: no such corpus, file or directory: $i" >&2; exit 2; fi ;;
  esac
done
[ "${#files[@]}" -gt 0 ] || { echo "launcher-gate: nothing to run" >&2; exit 2; }
[ -n "$label" ] || { label="$(printf '%s' "${inputs[*]}" | tr -c 'A-Za-z0-9_.-' '-' | cut -c1-60)"; }
[ -n "$out" ] || out="$ROOT/work/status/machine-gate-$label.txt"

work="$(mktemp -d "${TMPDIR:-/tmp}/machine-gate.XXXXXX")"
trap 'rm -rf "$work"' EXIT

run_one() { # run_one INDEX FILE
  local idx="$1" f="$2" st hp=()
  # a small heap for small tests (the launcher's default is sized for mathlib: host memory, orchestrator 15:20Z)
  case "$f" in */perf/*) ;; *) [ "$heap" = launcher ] || hp=("-Xmx$heap") ;; esac
  set +e
  RLVM_EXTRA_CP="$jar" timeout "$timeout" "$dist/bin/rlvm-jvm" "${hp[@]}" -X engine=machine -X verify=reference -X stats-file="$work/$idx.json" \
      "${extra[@]}" "$f" > "$work/$idx.out" 2> "$work/$idx.err"
  st=$?
  set -e
  echo "$st" > "$work/$idx.status"
  case "$f" in
    */good/*) echo 0 > "$work/$idx.expect" ;;
    */bad/*) echo 1 > "$work/$idx.expect" ;;
    *) set +e
       timeout "$timeout" "$dist/bin/rlvm-jvm" "${hp[@]}" -X engine=reference "$f" > /dev/null 2>&1
       echo $? > "$work/$idx.expect"
       set -e ;;
  esac
}
export -f run_one
export work dist jar timeout heap
# the extra settings travel through a file (arrays are not exported)
printf '%s\n' "${extra[@]:-}" > "$work/extra"
run_wrapper() {
  local extra=()
  while IFS= read -r line; do [ -n "$line" ] && extra+=("$line"); done < "$work/extra"
  run_one "$@"
}
export -f run_wrapper

i=0
: > "$work/list"
for f in "${files[@]}"; do printf '%d\t%s\n' "$i" "$f" >> "$work/list"; i=$((i + 1)); done
start=$(date +%s)
# shellcheck disable=SC2016
xargs -P "$jobs" -L 1 bash -c 'run_wrapper "$0" "$1"' < <(tr '\t' ' ' < "$work/list") || true
end=$(date +%s)

python3 - "$work" "$out" "$label" "$allow" "$((end - start))" "$jar" "${extra[*]:-}" <<'PY'
import json, os, sys, datetime
work, out, label, allow, secs, jar, extra = sys.argv[1:8]
rows = []
tot = dict(tests=0, right=0, wrong=0, units=0, macc=0, mrej=0, mdec=0, meager=0, udec=0, anomalies=0, incomplete=0, nostats=0, tdec=0)
by_code = {}
first = []
for line in open(os.path.join(work, 'list')):
    idx, path = line.rstrip('\n').split('\t')
    def rd(ext):
        try:
            return open(os.path.join(work, idx + ext)).read().strip()
        except OSError:
            return ''
    status, expect = rd('.status'), rd('.expect')
    name = '/'.join(path.split('/')[-3:])
    vals = {}
    try:
        d = json.load(open(os.path.join(work, idx + '.json')))
        def walk(x, p=''):
            if isinstance(x, dict):
                for k, v in x.items():
                    walk(v, p + '.' + k if p else k)
            else:
                vals[p] = x
        walk(d)
    except (OSError, ValueError):
        tot['nostats'] += 1
    g = lambda k: vals.get(k, 0) or 0
    m = 'values.engine.primary.machine.'
    units, macc, mrej, mdec = g('units.count'), g(m + 'accepted'), g(m + 'rejected'), g(m + 'declined')
    meager = g(m + 'eagerDeclines')  # declines BY DESIGN: the declaration entered eager reduction (NOTES-M6 F26)
    # units the RUN left declined (the driver's final statuses). units.declined counts warm attempts of the primary
    # engine, so a declaration declined for eager reduction and then decided by the reference engine is in it.
    udec = g('values.driver.declined')
    anomalies, incomplete = g('values.driver.warmAnomalies'), g('values.driver.machineIncomplete')
    ok = status == expect and status in ('0', '1')
    tot['tests'] += 1
    tot['right' if ok else 'wrong'] += 1
    tot['units'] += units; tot['macc'] += macc; tot['mrej'] += mrej; tot['mdec'] += mdec; tot['udec'] += udec
    tot['meager'] += meager
    tot['anomalies'] += anomalies; tot['incomplete'] += incomplete
    tot['tdec'] += 1 if mdec - meager > 0 else 0
    for k, v in vals.items():
        if k.startswith(m + 'declinedBy.') or k.startswith(m + 'rejectedBy.'):
            by_code[k[len(m):]] = by_code.get(k[len(m):], 0) + v
    msg = str(vals.get(m + 'firstFailures.0', ''))
    if mdec - meager > 0 and len(first) < 12 and msg:
        first.append((name, msg[:400]))
    if not ok and not msg:
        msg = (rd('.err').splitlines() or [''])[-1][:200]
    rows.append((name, expect, status, 'ok' if ok else 'WRONG', units, macc, mrej, mdec, msg[:160] if (mdec or not ok) else ''))
clean = tot['wrong'] == 0 and tot['mdec'] - tot['meager'] == 0 and tot['udec'] == 0 and tot['anomalies'] == 0 and tot['incomplete'] == 0 and tot['nostats'] == 0
verdicts = tot['wrong'] == 0
with open(out, 'w') as f:
    w = lambda s='': f.write(s + '\n')
    w('machine launcher gate: %s   %s   (%s s wall)' % (label, datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'), secs))
    w('command per test: RLVM_EXTRA_CP=%s work/dist/bin/rlvm-jvm -X engine=machine -X verify=reference -X stats-file=F %s TEST' % (os.path.relpath(jar), extra))
    w()
    w('RESULT: %s' % ('CLEAN (every verdict right, the machine declined nothing but declarations that enter eager reduction)' if clean else
                      'verdicts right, but NOT clean (machine declines or events below)' if verdicts else 'WRONG VERDICTS'))
    w('tests %d   verdict right %d   wrong %d   without statistics %d' % (tot['tests'], tot['right'], tot['wrong'], tot['nostats']))
    w('units %d   machine: accepted %d   rejected %d   DECLINED %d (in %d tests) + %d for eager reduction (by design)   driver.declined %d   WARM-ANOMALY %d   MACHINE-INCOMPLETE %d'
      % (tot['units'], tot['macc'], tot['mrej'], tot['mdec'] - tot['meager'], tot['tdec'], tot['meager'], tot['udec'], tot['anomalies'], tot['incomplete']))
    for k in sorted(by_code):
        w('  %-40s %d' % (k, by_code[k]))
    if first:
        w()
        w('first machine failures:')
        for name, msg in first:
            w('  %s: %s' % (name, msg))
    w()
    w('%-58s %6s %6s %-5s %6s %6s %6s %6s  %s' % ('test', 'expect', 'status', '', 'units', 'm.acc', 'm.rej', 'm.dec', 'first failure'))
    for r in rows:
        w('%-58s %6s %6s %-5s %6d %6d %6d %6d  %s' % r)
print(open(out).read().split('\n\n')[1])
print('report:', out)
sys.exit(0 if clean or (verdicts and allow == '1') else 1)
PY
