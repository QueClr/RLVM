"""Formats the raw log of in-container.sh: one line per run with the perf numbers and the program's result lines."""
import json
import re
import sys

label = None
lines = []
for line in sys.stdin:
    line = line.rstrip("\n")
    if line.startswith("RUN "):
        label = line[4:].split(" :: ")[0]
        lines = []
    elif line.startswith(("RESULT ", "STATS ", "DEEP ", "DUEL ", "VERIFY ", "MISMATCH ")):
        lines.append(line)
    elif line.startswith("JSON "):
        m = re.match(r"JSON (.*?) (\{.*\})$", line)
        if not m:
            print("%-40s | NO MEASUREMENT" % (label or "?"))
            continue
        js = json.loads(m.group(2))
        ins = js.get("instructions")
        g = "n/a" if ins is None else "%.3f" % (ins / 1e9)
        print("%-40s | cpu %7.3f s | wall %7.3f s | rss %6.0f MB | instr %9s G | exit %d | %s"
              % (m.group(1), js["cpu_time_s"], js["wall_time_s"], js["max_rss_bytes"] / 2 ** 20, g, js["exit_code"], " ## ".join(lines)))
    elif "Exception" in line or "Error" in line:
        print("    !! " + line[:300])
