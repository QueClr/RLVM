#!/usr/bin/env python3
"""Split each corpus's declarations by where they are declared (Init / Std / Mathlib / the project
itself) and report count, instructions and reduction counters per part; write the project's own
declaration names to work/workloads/dyn/only/NAME.project.txt (input of `nl-fast --only`).

    python3 -I bench/workloads/origin-split.py > work/workloads/tables/origin.md
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wlcommon import CORPORA, WL, coverage, fmt_n, md_table, origin_fn, pct  # noqa: E402


def main():
    origin = origin_fn()
    os.makedirs(os.path.join(WL, "dyn", "only"), exist_ok=True)
    rows = []
    proj_rows = []
    for c in CORPORA:
        di = {}
        with open(os.path.join(WL, "dyn", "nl", f"{c}.di.tsv"), encoding="utf-8", errors="replace") as f:
            next(f)
            for line in f:
                q = line.rstrip("\n").split("\t")
                di[q[1]] = int(q[4])
        st = {}
        with open(os.path.join(WL, "dyn", "nl", f"{c}.stats.tsv"), encoding="utf-8", errors="replace") as f:
            hdr = f.readline().rstrip("\n").split("\t")
            ix = {k: i for i, k in enumerate(hdr)}
            for line in f:
                q = line.rstrip("\n").split("\t")
                st[q[1]] = q
        tot = sum(di.values()) or 1
        parts = {}
        for n, ins in di.items():
            o = origin(n) if c not in ("init",) else "Init"
            if c == "std" and o != "Init":
                o = "Std"
            if c == "mathlib" and o == "project":
                o = "Mathlib"
            p = parts.setdefault(o, {"n": 0, "instr": 0, "delta": 0, "fired": 0, "iota": 0, "proj": 0, "infer": 0, "conv": 0, "names": [], "instrs": []})
            p["n"] += 1
            p["instr"] += ins
            p["names"].append(n)
            p["instrs"].append(ins)
            q = st.get(n)
            if q:
                p["delta"] += int(q[ix["unfold_whnf"]]) + int(q[ix["unfold_lazy"]])
                p["fired"] += int(q[ix["fired"]])
                p["iota"] += int(q[ix["iota"]])
                p["proj"] += int(q[ix["proj_whnf"]])
                p["infer"] += int(q[ix["infer_slow"]])
                p["conv"] += int(q[ix["deq_calls"]]) - int(q[ix["deq_ptr_eq"]])
        for o in ("Init", "Std", "Mathlib", "project"):
            p = parts.get(o)
            if not p:
                continue
            red = p["delta"] + p["fired"] + p["iota"] + p["proj"]
            rows.append([c, o, p["n"], pct(p["instr"] / tot), fmt_n(p["instr"]), fmt_n(p["instr"] / p["n"]), fmt_n(p["infer"]), fmt_n(p["conv"]),
                         fmt_n(p["delta"]), fmt_n(p["fired"]), fmt_n(p["iota"]), fmt_n(p["proj"]), f"{red / max(p['infer'], 1):.2f}"])
        p = parts.get("project")
        if p and c not in ("init", "std", "mathlib"):
            with open(os.path.join(WL, "dyn", "only", f"{c}.project.txt"), "w") as f:
                for n in p["names"]:
                    f.write(n + "\n")
            _, _, top, need = coverage(p["instrs"], (1, 10, 100, 1000))
            proj_rows.append([c, p["n"], fmt_n(p["instr"]), pct(top.get(1, 1)), pct(top.get(10, 1)), pct(top.get(100, 1)), pct(top.get(1000, 1)), need.get(0.5), need.get(0.9)])
    print("## O1. Declarations by where they are declared: count, instructions, counters (instrumented nano-lean)\n")
    print(md_table(["corpus", "declared in", "decls", "share of checking instr", "instr", "instr/decl", "infer misses", "conversions", "delta", "rule firings", "iota",
                    "proj", "reductions per infer miss"], rows, ["l", "l"] + ["r"] * 11))
    print("\n## O2. The project's own declarations only: concentration of cost\n")
    print(md_table(["corpus", "project decls", "instr", "top1", "top10", "top100", "top1000", "N50%", "N90%"], proj_rows))


if __name__ == "__main__":
    main()
