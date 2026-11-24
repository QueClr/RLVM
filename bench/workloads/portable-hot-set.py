#!/usr/bin/env python3
"""How much of each corpus's dynamic heat falls on the objects that are hottest on Init (a hot set that
could be prepared ahead of time): share of executions received by Init's top-K objects, per series.

    python3 -I bench/workloads/portable-hot-set.py > work/workloads/tables/portable.md
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wlcommon import CORPORA, WL, md_table, pct  # noqa: E402


def series(c):
    out = {"BODY": {}, "RULE": {}, "PROJ": {}, "TELE": {}}
    p = os.path.join(WL, "dyn", "nl", f"{c}.nod.dump.tsv")
    with open(p, encoding="utf-8", errors="replace") as f:
        for line in f:
            q = line.rstrip("\n").split("\t")
            if q[0] == "C":
                out["BODY"][q[2]] = int(q[3]) + int(q[4])
                out["TELE"][q[2]] = int(q[9]) + int(q[10]) + int(q[11])
            elif q[0] == "I":
                out["RULE"][f"{q[3]}#{q[2]}"] = int(q[4])
            elif q[0] in ("PW", "PE"):
                k = f"{q[3]}.{q[2]}"
                out["PROJ"][k] = out["PROJ"].get(k, 0) + int(q[4])
    return out


def main():
    data = {c: series(c) for c in CORPORA}
    for ref in ("init", "std"):
        print(f"### Share of executions received by the K objects that are hottest on `{ref}` (derived rules off)\n")
        rows = []
        for s, ks in (("BODY", (30, 100, 300, 1000)), ("RULE", (10, 30, 100)), ("PROJ", (10, 30, 100)), ("TELE", (100, 1000, 10000))):
            rank = [n for n, v in sorted(data[ref][s].items(), key=lambda kv: -kv[1]) if v]
            for k in ks:
                top = set(rank[:k])
                row = [f"{s} top {k}"]
                for c in CORPORA:
                    tot = sum(data[c][s].values()) or 1
                    row.append(pct(sum(v for n, v in data[c][s].items() if n in top) / tot))
                rows.append(row)
        print(md_table(["hot set"] + CORPORA, rows))
        print()


if __name__ == "__main__":
    main()
