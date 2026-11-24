#!/usr/bin/env bash
# bench/modules/rlvm-export/m.sh LABEL -- command...
# Runs the command under bench/measure.sh inside the arena container (8 CPUs / 16 GB) and prints a
# one-line summary: user instructions, kernel instructions, CPU s, wall s, max RSS.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd -P)"
label="$1"; shift
[ "$1" = "--" ] && shift
"$ROOT/bench/arena-docker.sh" --arena-limits bench/measure.sh -- "$@" 2>&1 | python3 -c "
import sys, json
label = sys.argv[1]
for l in sys.stdin:
    l = l.strip()
    if l.startswith('{\"exit_code\"'):
        d = json.loads(l)
        print('%-28s exit=%d instr=%.3fG kernel=%.3fG cpu=%.3fs wall=%.3fs rss=%.0fMB' % (label, d['exit_code'], d['instructions']/1e9, d['instructions_kernel']/1e9, d['cpu_time_s'], d['wall_time_s'], d['max_rss_bytes']/1e6))
    elif l and '--quiet' not in sys.argv:
        print('   | ' + l)
" "$label" ${RLVM_M_QUIET:+--quiet}
