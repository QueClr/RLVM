#!/usr/bin/env python3
"""Cross-checks from the leaders' own statistics: rpylean `--stats --print jsonl` and gruszka (knobs
build) `SK_DECL_INSTR=1`, against the instrumented nano-lean numbers.

    python3 -I bench/workloads/cross-report.py > work/workloads/tables/cross.md
"""
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wlcommon import CORPORA, WL, coverage, fmt_n, md_table, pct  # noqa: E402


def rpylean_stats(c):
    p = os.path.join(WL, "dyn", "rpylean", f"{c}.stats.txt")
    out = {"iota": {}, "delta": {}, "nat": {}}
    if not os.path.exists(p):
        return None
    sec = None
    with open(p, encoding="utf-8", errors="replace") as f:
        for line in f:
            if line.startswith("iota fires"):
                sec = "iota"
                continue
            if line.startswith("delta unfolds"):
                sec = "delta"
                continue
            if line.startswith("native nat"):
                sec = "nat"
                continue
            if line.startswith("def_eq calls by argument check"):
                sec = "args"
                continue
            if sec in ("iota", "delta", "nat") and line.startswith("  "):
                q = line.strip().split("\t")
                if len(q) == 2 and q[0].isdigit():
                    out[sec][q[1]] = out[sec].get(q[1], 0) + int(q[0])
                continue
            m = re.match(r"^(def_eq calls|def_eq false|def_eq eqv hits|def_eq proof-irrelevance hits|def_eq identity hits|beta reductions|whnf steps):\s+(\d+)", line)
            if m:
                out[m.group(1)] = int(m.group(2))
            m = re.match(r"^k-like fired/bail head/mutual/ctors/defeq: (\d+)/", line)
            if m:
                out["klike"] = int(m.group(1))
    return out


def rpylean_decls(c):
    p = os.path.join(WL, "dyn", "rpylean", f"{c}.decls.jsonl")
    out = []
    if not os.path.exists(p):
        return out
    with open(p, encoding="utf-8", errors="replace") as f:
        for line in f:
            try:
                d = json.loads(line)
            except ValueError:
                continue
            out.append((d.get("name", "?"), d.get("elapsed", 0.0)))
    return out


def gruszka_decls(c):
    p = os.path.join(WL, "dyn", "gruszka", f"{c}.di.tsv")
    out = []
    if not os.path.exists(p):
        return out
    with open(p, encoding="utf-8", errors="replace") as f:
        for line in f:
            q = line.rstrip("\n").split("\t")
            if len(q) < 13:
                continue
            try:
                nums = [int(x) for x in q[2:12]]
            except ValueError:
                continue
            gr = [int(x) for x in re.findall(r"\d+", q[12])]
            out.append((q[1], nums, gr))
    return out


def nl_decls(c):
    p = os.path.join(WL, "dyn", "nl", f"{c}.di.tsv")
    out = []
    if not os.path.exists(p):
        return out
    with open(p, encoding="utf-8", errors="replace") as f:
        next(f)
        for line in f:
            q = line.rstrip("\n").split("\t")
            out.append((q[1], int(q[4])))
    return out


def main():
    print("## X1. rpylean `--stats` (substitution-based kernel with a CEK machine for closed scalars; its own counters)\n")
    rows, rows2 = [], []
    tops = {}
    for c in CORPORA:
        s = rpylean_stats(c)
        if not s:
            continue
        de = s.get("def_eq calls", 0)
        rows.append([c, fmt_n(de), pct(s.get("def_eq identity hits", 0) / max(de, 1)), pct(s.get("def_eq eqv hits", 0) / max(de, 1)),
                     fmt_n(s.get("def_eq false", 0)), fmt_n(s.get("beta reductions", 0)), fmt_n(sum(s["delta"].values())), len(s["delta"]),
                     fmt_n(sum(s["iota"].values())), len(s["iota"]), fmt_n(sum(s["nat"].values())), fmt_n(s.get("klike", 0))])
        _, _, dt, dn = coverage(s["delta"].values(), (1, 10, 30, 100, 1000))
        _, _, it, inn = coverage(s["iota"].values(), (1, 10, 30, 100, 1000))
        rows2.append([c, pct(dt.get(10, 1)), pct(dt.get(100, 1)), pct(dt.get(1000, 1)), dn.get(0.5, "-"), dn.get(0.9, "-"),
                      pct(it.get(1, 1)), pct(it.get(10, 1)), pct(it.get(100, 1)), inn.get(0.5, "-"), inn.get(0.9, "-")])
        tops[c] = s
    print(md_table(["corpus", "def_eq calls", "identity hits", "eqv hits", "false", "beta", "delta unfolds", "constants unfolded", "iota fires",
                    "recursors fired", "native Nat ops", "K-like"], rows))
    print("\nCoverage of rpylean's delta and iota counts (same form as the heat tables):\n")
    print(md_table(["corpus", "delta top10", "top100", "top1000", "N50%", "N90%", "iota top1", "top10", "top100", "N50%", "N90%"], rows2))
    print("\nTop names (rpylean):\n")
    for c, s in tops.items():
        d = sorted(s["delta"].items(), key=lambda kv: -kv[1])[:12]
        i = sorted(s["iota"].items(), key=lambda kv: -kv[1])[:8]
        n = sorted(s["nat"].items(), key=lambda kv: -kv[1])[:6]
        print(f"- **{c}** delta: " + ", ".join(f"`{k}` {fmt_n(v)}" for k, v in d))
        print("  iota: " + ", ".join(f"`{k}` {fmt_n(v)}" for k, v in i) + "; Nat: " + ", ".join(f"`{k}` {fmt_n(v)}" for k, v in n))

    print("\n## X2. Per-declaration cost under three checkers: concentration and agreement\n")
    rows = []
    agree = []
    for c in CORPORA:
        nl = nl_decls(c)
        rp = rpylean_decls(c)
        gr = gruszka_decls(c)
        row = [c]
        for label, v in (("nl", [x[1] for x in nl]), ("rp", [x[1] for x in rp]), ("gr", [x[1][0] for x in gr])):
            if not v:
                row += ["-", "-", "-"]
                continue
            tot, _, top, _ = coverage([int(x * 1e9) if label == "rp" else x for x in v], (10, 100, 1000))
            row += [pct(top.get(10, 1)), pct(top.get(100, 1)), pct(top.get(1000, 1))]
        rows.append(row)
        if nl and rp and gr:
            t_nl = [n for n, _ in sorted(nl, key=lambda x: -x[1])[:50]]
            t_rp = [n for n, _ in sorted(rp, key=lambda x: -x[1])[:50]]
            t_gr = [x[0] for x in sorted(gr, key=lambda x: -x[1][0])[:50]]
            agree.append([c, len(set(t_nl) & set(t_gr)), len(set(t_nl) & set(t_rp)), len(set(t_gr) & set(t_rp)),
                          len(set(t_nl[:10]) & set(t_gr[:10])), len(set(t_nl[:10]) & set(t_rp[:10])),
                          ", ".join(f"`{n}`" for n in t_gr[:3]), ", ".join(f"`{n}`" for n in t_rp[:3])])
    print(md_table(["corpus", "nano-lean instr top10", "top100", "top1000", "rpylean time top10", "top100", "top1000", "gruszka time top10", "top100", "top1000"], rows))
    print("\n(nano-lean: instructions, exact. rpylean and gruszka: wall time per declaration on a loaded host, one thread; indicative only.)\n")
    print(md_table(["corpus", "top50 nl∩gruszka", "top50 nl∩rpylean", "top50 gruszka∩rpylean", "top10 nl∩gruszka", "top10 nl∩rpylean", "gruszka's top 3", "rpylean's top 3"],
                   agree, ["l", "r", "r", "r", "r", "r", "l", "l"]))

    print("\n## X3. gruszka (knobs build): its counters, and its ground machine (closed call-by-need computation past 2^26 values)\n")
    rows = []
    for c in CORPORA:
        gr = gruszka_decls(c)
        if not gr:
            continue
        s = [sum(x[1][i] for x in gr) for i in range(10)]
        g_runs = sum(x[2][0] for x in gr if x[2])
        g_decls = [x[0] for x in gr if x[2] and x[2][0] > 0]
        g_steps = sum(x[2][4] for x in gr if len(x[2]) > 4)
        rows.append([c, len(gr), f"{s[0] / 1e9:.1f}", fmt_n(s[1]), fmt_n(s[2]), fmt_n(s[3]), fmt_n(s[4]), pct(s[5] / max(s[4], 1)), fmt_n(s[6]), fmt_n(s[7]),
                     fmt_n(s[8]), fmt_n(s[9]), g_runs, len(g_decls), fmt_n(g_steps), ", ".join(f"`{n}`" for n in g_decls[:4])])
    print(md_table(["corpus", "decl records", "sum of decl time s", "ticks", "whnf misses", "unfolds", "def_eq calls", "eq-cache hits", "infer misses", "infer calls",
                    "evals", "interns", "ground runs", "decls using it", "machine steps", "which"], rows, ["l"] + ["r"] * 14 + ["l"]))


if __name__ == "__main__":
    main()
