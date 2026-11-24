#!/usr/bin/env python3
"""Static shape tables for docs/research/13 from the output of static-shape (work/workloads/static/*).

    python3 -I bench/workloads/static-report.py > work/workloads/tables/static.md
"""
import os
import sys
import statistics

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wlcommon import (CORPORA, WL, coverage, fmt_n, load_consts, md_table, origin_fn, pct, role, thresholds)  # noqa: E402


def summary(corpus):
    d = {}
    with open(os.path.join(WL, "static", corpus, "summary.txt")) as f:
        for line in f:
            d[line.split(" ", 1)[0] + (" " + line.split()[1] if line.startswith("seg_kind") else "")] = line.strip()
    return d


def segs(corpus):
    out = []
    with open(os.path.join(WL, "static", corpus, "segs.tsv"), encoding="utf-8", errors="replace") as f:
        next(f)
        for line in f:
            p = line.rstrip("\n").split("\t")
            out.append((p[1], p[2], int(p[3]), int(p[4]), int(p[5]), int(p[8])))
    return out


def quant(v, q):
    if not v:
        return 0
    i = min(len(v) - 1, int(q * len(v)))
    return v[i]


def main():
    origin = origin_fn()
    data = {c: load_consts(c) for c in CORPORA}
    print("## S1. Declarations and constants by kind\n")
    kinds = ["thm", "def", "opaque", "axiom", "quot", "ind", "ctor", "rec"]
    rows = []
    for c in CORPORA:
        cnt = {k: 0 for k in kinds}
        for x in data[c]:
            cnt[x["kind"]] += 1
        proj = sum(1 for x in data[c] if "P" in x["flags"])
        match = sum(1 for x in data[c] if role(x["name"], x["kind"], x["flags"]) == "matcher")
        inst = sum(1 for x in data[c] if role(x["name"], x["kind"], x["flags"]) == "instance")
        rows.append([c, len(data[c])] + [cnt[k] for k in kinds] + [proj, match, inst])
    print(md_table(["corpus", "constants"] + kinds + ["struct proj fns", "matchers", "instances"], rows))

    print("\n## S2. Static heat of constant-headed application spines (the spike's HEAT table)\n")
    rows = []
    for c in CORPORA:
        heat = [x["heat"] for x in data[c]]
        total, used, top, need = coverage(heat)
        rows.append([c, fmt_n(total), f"{used}/{len(heat)}", pct(top.get(1, 0)), pct(top.get(10, 0)), pct(top.get(30, 0)), pct(top.get(100, 0)),
                     pct(top.get(1000, 0)), pct(top.get(10000, 0)), need.get(0.5), need.get(0.8), need.get(0.9), need.get(0.95)])
    print(md_table(["corpus", "spines", "heads used", "top1", "top10", "top30", "top100", "top1000", "top10000", "N50%", "N80%", "N90%", "N95%"], rows))

    print("\nStatic heat thresholds (constants with at least N spines; share of spines; share after crossing):\n")
    rows = []
    for c in CORPORA:
        th = thresholds([x["heat"] for x in data[c]], (10, 100, 1000, 10**4, 10**5, 10**6))
        rows.append([c] + [f"{th[t][0]} ({pct(th[t][1])} / {pct(th[t][2])})" for t in (10, 100, 1000, 10**4, 10**5, 10**6)])
    print(md_table(["corpus", ">=10", ">=100", ">=1e3", ">=1e4", ">=1e5", ">=1e6"], rows))

    print("\n## S3. Who receives the static heat: by role and by origin (share of spines)\n")
    roles = ["inductive type", "theorem", "structure projection fn", "definition", "constructor", "instance", "matcher", "casesOn", "recOn",
             "brecOn/below", "noConfusion", "ctorIdx/ctorElim", "recursor", "definition (aux)", "axiom", "opaque", "quot"]
    rows = []
    for r in roles:
        row = [r]
        for c in CORPORA:
            tot = sum(x["heat"] for x in data[c]) or 1
            row.append(pct(sum(x["heat"] for x in data[c] if role(x["name"], x["kind"], x["flags"]) == r) / tot))
        rows.append(row)
    print(md_table(["role"] + CORPORA, rows))
    print()
    rows = []
    for o in ["Init", "Std", "Mathlib", "project"]:
        row = [o]
        for c in CORPORA:
            tot = sum(x["heat"] for x in data[c]) or 1
            row.append(pct(sum(x["heat"] for x in data[c] if origin(x["name"]) == o) / tot))
        rows.append(row)
    print(md_table(["defined in"] + CORPORA, rows))
    print("\n(origin = the first of the init, std, mathlib exports that declares the name; everything else is `project`.)\n")

    init_top = [x["name"] for x in sorted(data["init"], key=lambda x: -x["heat"])[:100]]
    init_top30 = set(init_top[:30])
    rows = []
    for c in CORPORA:
        top = sorted(data[c], key=lambda x: -x["heat"])
        t30 = [x["name"] for x in top[:30]]
        t100 = [x["name"] for x in top[:100]]
        nonlib100 = [x["name"] for x in top[:100] if origin(x["name"]) != "Init"]
        rows.append([c, len(init_top30 & set(t30)), len(set(init_top) & set(t100)), len(nonlib100), ", ".join(f"`{n}`" for n in nonlib100[:6])])
    print(md_table(["corpus", "top30 shared with Init's top30", "top100 shared with Init's top100", "of top100 not in Init", "first non-Init names"], rows,
                   ["l", "r", "r", "r", "l"]))

    print("\n### Top 20 static heads per corpus\n")
    for c in CORPORA:
        top = sorted(data[c], key=lambda x: -x["heat"])[:20]
        tot = sum(x["heat"] for x in data[c]) or 1
        print(f"- **{c}**: " + ", ".join(f"`{x['name']}` {pct(x['heat'] / tot)}" for x in top))

    print("\n## S4. Occurrences of evaluation-related constants (static spines headed by them)\n")

    def cnt(c, pred):
        return sum(x["heat"] for x in data[c] if pred(x))

    pats = [
        ("recursors (`*.rec`)", lambda x: x["kind"] == "rec"),
        ("`*.casesOn`", lambda x: x["name"].endswith(".casesOn")),
        ("`*.recOn`", lambda x: x["name"].endswith(".recOn")),
        ("matchers (`*.match_N`)", lambda x: role(x["name"], x["kind"], x["flags"]) == "matcher"),
        ("`*.brecOn` / `*.below`", lambda x: role(x["name"], x["kind"], x["flags"]) == "brecOn/below"),
        ("`WellFounded.fix` (+`fixF`, `Acc.rec`)", lambda x: x["name"] in ("WellFounded.fix", "WellFounded.fixF", "Acc.rec", "WellFounded.fix'")),
        ("`Acc.rec`", lambda x: x["name"] == "Acc.rec"),
        ("`Decidable.decide`", lambda x: x["name"] == "Decidable.decide"),
        ("`of_decide_eq_true` / `Nat.decEq` / `decide_eq_true*`", lambda x: x["name"] in ("of_decide_eq_true", "of_decide_eq_false", "Nat.decEq", "decide_eq_true", "eq_true_of_decide", "Lean.ofReduceBool", "Lean.reduceBool")),
        ("`OfNat.ofNat`", lambda x: x["name"] == "OfNat.ofNat"),
        ("`ite` / `dite`", lambda x: x["name"] in ("ite", "dite")),
        ("structure projection fns", lambda x: "P" in x["flags"]),
        ("`Eq.rec`/`Eq.ndrec`/`Eq.mpr`/`Eq.mp`/`cast`", lambda x: x["name"] in ("Eq.rec", "Eq.ndrec", "Eq.mpr", "Eq.mp", "cast", "Eq.recOn", "Eq.casesOn")),
    ]
    rows = []
    for label, pred in pats:
        rows.append([label] + [fmt_n(cnt(c, pred)) for c in CORPORA])
    for key, label in (("nat_literals", "natVal nodes (distinct)"), ("nat_literal_refs", "references to natVal nodes"),
                       ("str_literals", "strVal nodes (distinct)"), ("str_literal_refs", "references to strVal nodes"), ("proj_exprs", "`proj` nodes"),
                       ("let_exprs", "`letE` nodes")):
        row = [label]
        for c in CORPORA:
            s = summary(c)[key]
            if key in ("nat_literals", "str_literals"):
                h = s[s.index("[", s.index("]")) + 1:]
                h = h[:h.index("]")]
                row.append(fmt_n(sum(int(v) for v in h.split(","))))
            else:
                row.append(fmt_n(int(s.split()[1])))
        rows.append(row)
    print(md_table(["pattern"] + CORPORA, rows))
    print("\nLiteral size histograms:\n")
    rows = []
    for c in CORPORA:
        s = summary(c)
        n = s["nat_literals"]
        h = n[n.index("[", n.index("]")) + 1:]
        h = [int(v) for v in h[:h.index("]")].split(",")]
        st = s["str_literals"]
        g = st[st.index("[", st.index("]")) + 1:]
        g = [int(v) for v in g[:g.index("]")].split(",")]
        rows.append([c] + h + [n.split()[-1]] + g)
    print(md_table(["corpus", "nat <=1", "<2^8", "<2^31", "<2^63", "<2^64", ">=2^64", "max digits", "str 0 B", "1-8", "9-64", "65-1024", "1025-64k", ">64k"], rows))

    print("\n## S5. Size of declarations (expression nodes first written for the declaration) and private share\n")
    rows = []
    rows2 = []
    for c in CORPORA:
        sg = segs(c)
        thm = sorted(n for k, _, n, _, _, _ in sg if k == "thm")
        dfn = sorted(n for k, _, n, _, _, _ in sg if k == "def")
        alln = sorted((n for _, _, n, _, _, _ in sg), reverse=True)
        tot = sum(alln) or 1
        rows.append([c, len(thm), int(statistics.median(thm)) if thm else 0, quant(thm, 0.9), quant(thm, 0.99), quant(thm, 0.999), thm[-1] if thm else 0,
                     int(statistics.median(dfn)) if dfn else 0, quant(dfn, 0.99), dfn[-1] if dfn else 0,
                     pct(sum(alln[:10]) / tot), pct(sum(alln[:100]) / tot), pct(sum(alln[:1000]) / tot)])
        s = summary(c)
        allseg = s["all_segments"].split()
        thmseg = s.get("seg_kind thm", "x x x x x x x x x 0").split()
        defseg = s.get("seg_kind def", "x x x x x x x x x 0").split()
        priv_nodes = int(allseg[4])
        nodes = int(allseg[2])
        thm_nodes = int(thmseg[5]) if len(thmseg) > 5 else 0
        rows2.append([c, fmt_n(nodes), pct(thm_nodes / nodes), pct(float(thmseg[-1])), pct(float(defseg[-1])), pct(priv_nodes / nodes)])
    print(md_table(["corpus", "theorems", "thm median", "thm p90", "thm p99", "thm p99.9", "thm max", "def median", "def p99", "def max",
                    "top10 decls", "top100", "top1000"], rows))
    print("\n(nodes; the last three columns are the share of all expression nodes that the largest 10/100/1000 declarations hold.)\n")
    print(md_table(["corpus", "expr nodes", "in theorem segments", "private in thm segments", "private in def segments", "private overall"], rows2))
    print("\n(private = never referenced by a record of a later declaration, i.e. reachable only from the declaration that introduced it.)")


if __name__ == "__main__":
    main()
