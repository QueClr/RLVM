"""Formats the output of bench/measure.sh (program output + one JSON line) into a compact summary."""
import json
import sys

label = sys.argv[1]
lines = [l for l in sys.stdin.read().splitlines() if l.strip()]
js = None
out = []
for l in lines:
    if l.startswith('{"exit_code"'):
        js = json.loads(l)
    elif not l.startswith(("[To redirect", "* ", "WARNING:")):
        out.append(l)
tail = out[-1][:330] if out else ""
if js is None:
    print("%s | NO MEASUREMENT | %s" % (label, tail))
else:
    ins = js.get("instructions")
    g = "n/a" if ins is None else "%9.3f G" % (ins / 1e9)
    vt = "n/a" if ins is None else "%7.2f" % (ins / 6e9)
    print("%s | instr %s (virtual %s s) | cpu %7.2f s | wall %7.2f s | rss %6.0f MB | exit %d"
          % (label, g, vt, js["cpu_time_s"], js["wall_time_s"], js["max_rss_bytes"] / 2 ** 20, js["exit_code"]))
    if tail:
        print("    " + tail)
