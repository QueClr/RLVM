#!/usr/bin/env python3
"""gcsum.py FILE.err ... - collections of a native-image run started with -XX:+PrintGC: count and pause time,
total and per kind; with --phase A B only the pauses between A and B seconds after the start."""
import re, sys
args = [a for a in sys.argv[1:] if not a.startswith("--")]
lo, hi = 0.0, 1e18
if "--phase" in sys.argv:
    i = sys.argv.index("--phase"); lo, hi = float(sys.argv[i + 1]), float(sys.argv[i + 2]); args = [a for a in args if a not in sys.argv[i + 1:i + 3]]
pat = re.compile(r"\[([0-9.]+)s\] GC\((\d+)\) Pause (Full|Incremental) GC \(([^)]*)\) ([0-9.]+)M->([0-9.]+)M ([0-9.]+)ms")
for f in args:
    n = {"Full": 0, "Incremental": 0}; ms = {"Full": 0.0, "Incremental": 0.0}; mx = 0.0; freed = 0.0; last = 0
    for line in open(f, errors="replace"):
        m = pat.search(line)
        if not m:
            continue
        t = float(m.group(1))
        if t < lo or t > hi:
            continue
        k = m.group(3); n[k] += 1; ms[k] += float(m.group(7)); mx = max(mx, float(m.group(7)))
        freed += float(m.group(5)) - float(m.group(6)); last = float(m.group(6))
    print("%s: %d young (%.0f ms) + %d full (%.0f ms) = %.3f s paused, longest %.0f ms, %.0f MB freed, heap after the last %.0f MB" % (
        f, n["Incremental"], ms["Incremental"], n["Full"], ms["Full"], (ms["Full"] + ms["Incremental"]) / 1000, mx, freed, last))
