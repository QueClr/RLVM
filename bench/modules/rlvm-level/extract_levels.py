#!/usr/bin/env python3
"""Extracts the universe level records of lean4export NDJSON files, with the name records they need.

    bench/modules/rlvm-level/extract_levels.py [--out DIR] FILE.ndjson ...

writes DIR/<basename>.levels.ndjson (default DIR: work/rlvm-level) containing, in file order, every
{"il":...} record, every {"in":...} record reachable from a level parameter (prefixes included), and
one line {"levelParams":[...]} per distinct declared parameter list (name ids), which the rlvm-level
tests and benchmarks use as realistic data. A 6.3 GB export takes about 40 s (grep does the scan).
"""
import argparse
import json
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))


def extract(path, out_dir):
    names = {}
    order = []
    levels = []
    lparams = {}
    # grep keeps the scan of the big files out of Python: name records, level records and every
    # levelParams list (the list itself is cut out with -o).
    cmd = ["grep", "-a", "-o", "-E", r'^\{"i[ln]":.*|"levelParams":\[[0-9,]*\]', path]
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE, env={"LC_ALL": "C"})
    num = re.compile(rb'"in":(\d+)')
    pre = re.compile(rb'"pre":(\d+)')
    par = re.compile(rb'"param":(\d+)')
    for line in p.stdout:
        line = line.rstrip(b"\n")
        if line.startswith(b'{"in":'):
            i = int(num.search(line).group(1))
            names[i] = (int(pre.search(line).group(1)), line)
            order.append(i)
        elif line.startswith(b'{"il":'):
            levels.append(line)
        elif line.startswith(b'"levelParams":'):
            lparams[line] = lparams.get(line, 0) + 1
    p.wait()
    if p.returncode not in (0, 1):
        raise RuntimeError("grep failed on " + path)
    need = set()
    for line in levels:
        m = par.search(line)
        if m:
            i = int(m.group(1))
            while i != 0 and i not in need:
                need.add(i)
                i = names[i][0]
    for line in lparams:
        for i in json.loads(line.split(b":", 1)[1]):
            while i != 0 and i not in need:
                need.add(i)
                i = names[i][0]
    os.makedirs(out_dir, exist_ok=True)
    out = os.path.join(out_dir, os.path.basename(path).replace(".ndjson", "") + ".levels.ndjson")
    with open(out, "wb") as f:
        for i in order:
            if i in need:
                f.write(names[i][1] + b"\n")
        for line in levels:
            f.write(line + b"\n")
        for line, count in sorted(lparams.items(), key=lambda kv: -kv[1]):
            f.write(b'{' + line + b',"count":%d}\n' % count)
    print("%s: %d level records, %d of %d names, %d distinct levelParams lists -> %s"
          % (os.path.basename(path), len(levels), len(need), len(names), len(lparams), out))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=os.path.join(ROOT, "work/rlvm-level"))
    ap.add_argument("files", nargs="+")
    a = ap.parse_args()
    for f in a.files:
        extract(f, a.out)


if __name__ == "__main__":
    sys.exit(main())
