"""Formats the raw log of scripts/in-container.sh: one line per run with the perf numbers and the RESULT line."""
import json
import re
import sys

label = None
result = ""
for line in sys.stdin:
    line = line.rstrip("\n")
    if line.startswith("RUN "):
        label = line[4:].split(" :: ")[0]
        result = ""
    elif line.startswith("RESULT ") or line.startswith("VERIFY OK") or line.startswith("VERIFY FAILED"):
        result = line
    elif line.startswith("JSON "):
        m = re.match(r"JSON (.*?) (\{.*\})$", line)
        if not m:
            print("%-44s | NO MEASUREMENT" % (label or "?"))
            continue
        js = json.loads(m.group(2))
        ins = js.get("instructions")
        g = "n/a" if ins is None else "%9.3f" % (ins / 1e9)
        short = re.sub(r" \| elaborated .*", "", result)
        short = re.sub(r"RESULT exp\d variant=\S+ mem=\d spines=\d+ args=\d+ ", "", short)
        print("%-44s | instr %s G | cpu %6.2f s | wall %6.2f s | rss %5.0f MB | exit %d | %s"
              % (m.group(1), g, js["cpu_time_s"], js["wall_time_s"], js["max_rss_bytes"] / 2 ** 20, js["exit_code"], short[:600]))
    elif "Exception" in line or "Error" in line:
        print("    !! " + line[:300])
