#!/usr/bin/env python3
"""Dynamic profile tables for docs/research/13 from the instrumented nano-lean runs
(work/workloads/dyn/nl/NAME.{di,stats,nod}.tsv, NAME.dump.tsv, NAME.nod.dump.tsv).

    python3 -I bench/workloads/dyn-report.py [--dir DIR] [--out DIR] NAME ...

Writes, per NAME, OUT/top50-NAME.md and OUT/heat-NAME.md, and OUT/dyn-summary.md for all names given.
NAME is a corpus (static data in work/workloads/static/NAME is used when present) or any export run
through bench/workloads/nl-run.sh.
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wlcommon import WL, coverage, fmt_n, md_table, origin_fn, pct, role, thresholds  # noqa: E402

TOPS = (1, 10, 30, 100, 300, 1000, 3000)
THS = (10**3, 10**4, 10**5, 10**6, 10**7)


def load_decl(path):
    rows = []
    if not os.path.exists(path):
        return rows
    with open(path, encoding="utf-8", errors="replace") as f:
        hdr = f.readline().rstrip("\n").split("\t")
        for line in f:
            p = line.rstrip("\n").split("\t")
            d = {}
            for k, v in zip(hdr, p):
                if k in ("name", "kind", "top"):
                    d[k] = v
                else:
                    try:
                        d[k] = int(v)
                    except ValueError:
                        d[k] = 0
            rows.append(d)
    return rows


def load_dump(path):
    out = {"C": {}, "I": [], "PW": [], "PE": [], "N": {}, "S": None, "W": None}
    if not os.path.exists(path):
        return out
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            if line.startswith("#"):
                continue
            p = line.rstrip("\n").split("\t")
            t = p[0]
            if t == "C":
                out["C"][p[2]] = dict(zip(("unfold_whnf", "unfold_lazy", "unfold_closed", "fired", "derive_unfold", "derived", "tele_check",
                                           "tele_only", "tele_val", "const_ty_calls", "const_ty_miss"), map(int, p[3:14])))
            elif t == "I":
                out["I"].append((p[3], int(p[2]), int(p[4])))
            elif t in ("PW", "PE"):
                out[t].append((p[3], int(p[2]), int(p[4])))
            elif t == "N":
                out["N"][p[1]] = list(map(int, p[2:9]))
            elif t == "S":
                out["S"] = list(map(int, p[1:]))
            elif t == "W":
                out["W"] = list(map(int, p[1:]))
    return out


def load_static(name):
    """name -> (kind, flags, role) and seg sizes by first declaration name."""
    consts, nodes = {}, {}
    p = os.path.join(WL, "static", name, "consts.tsv")
    if os.path.exists(p):
        with open(p, encoding="utf-8", errors="replace") as f:
            next(f)
            for line in f:
                q = line.rstrip("\n").split("\t")
                consts[q[1]] = (q[2], q[6])
    p = os.path.join(WL, "static", name, "segs.tsv")
    if os.path.exists(p):
        with open(p, encoding="utf-8", errors="replace") as f:
            next(f)
            for line in f:
                q = line.rstrip("\n").split("\t")
                nodes[q[2]] = int(q[3])
    return consts, nodes


def heat_rows(label, counts):
    """One row of the HEAT-form coverage table."""
    total, used, top, need = coverage(counts, TOPS)
    return [label, fmt_n(total), used] + [pct(top[t]) if t in top else "100%" for t in (1, 10, 30, 100, 1000)] + \
           [need.get(s, "-") for s in (0.5, 0.8, 0.9, 0.95, 0.99)]


def th_row(label, counts):
    th = thresholds(counts, THS)
    return [label] + [f"{th[t][0]} ({pct(th[t][1])}, {pct(th[t][2])})" for t in THS]


def describe(d, nodes):
    """One-line reason why a declaration is expensive, from its counters (heuristic)."""
    delta = d.get("unfold_whnf", 0) + d.get("unfold_lazy", 0)
    fired = d.get("fired", 0)
    iota = d.get("iota", 0)
    proj = d.get("proj_whnf", 0)
    nat = d.get("natops", 0)
    red = delta + fired + iota + proj + nat
    infer = d.get("infer_slow", 0)
    evals = d.get("evals", 0)
    deq_slow = d.get("deq_calls", 0) - d.get("deq_ptr_eq", 0)
    parts = []
    n = max(nodes, 1)
    if d["kind"] in ("ind", "ctor", "rec", "quot"):
        parts.append("inductive block: positivity/universe checks and recursor regeneration (term core)")
    elif red >= 20 * n and red >= 20000:
        parts.append(f"kernel evaluation on a small term ({nodes} nodes): {fmt_n(delta)} delta + {fmt_n(fired)} derived-rule firings + {fmt_n(iota)} iota"
                     + (f" + {fmt_n(nat)} Nat ops" if nat else ""))
    elif red >= 2 * max(infer, 1) and red >= 20000:
        parts.append(f"reduction-heavy ({fmt_n(delta)} delta, {fmt_n(fired)} fired, {fmt_n(iota)} iota, {fmt_n(proj)} proj) inside a {nodes}-node term")
    elif nodes >= 3000 and red < nodes:
        parts.append(f"large term ({nodes} nodes), inference-dominated: {fmt_n(infer)} infer misses, {fmt_n(evals)} evals, {fmt_n(deq_slow)} conversions, {fmt_n(red)} reductions")
    else:
        parts.append(f"{nodes} nodes; {fmt_n(infer)} infer misses, {fmt_n(evals)} evals, {fmt_n(deq_slow)} conversions, {fmt_n(delta)} delta, {fmt_n(iota)} iota")
    if d.get("lazy_delta_iters", 0) >= 5000 and d.get("unfold_lazy", 0) >= 0.3 * max(delta, 1):
        parts.append(f"lazy-delta driven ({fmt_n(d['lazy_delta_iters'])} iterations)")
    if d.get("natops_big", 0) > 0:
        parts.append(f"{fmt_n(d['natops_big'])} Nat ops on operands >= 2^63")
    if d.get("str2ctor", 0) > 0:
        parts.append(f"{d['str2ctor']} string literal expansions ({d['str2ctor_chars']} chars)")
    if d.get("iota_natlit", 0) >= 1000:
        parts.append(f"{fmt_n(d['iota_natlit'])} iota on Nat literals")
    if d.get("max_chain", 0) >= 200:
        parts.append(f"whnf chain up to {d['max_chain']} steps")
    if d.get("max_stack", 0) >= (1 << 20):
        parts.append(f"stack {d['max_stack'] >> 20} MiB")
    top = d.get("top", "")
    if top:
        parts.append(top)
    return "; ".join(parts)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default=os.path.join(WL, "dyn", "nl"))
    ap.add_argument("--out", default=os.path.join(WL, "tables"))
    ap.add_argument("--summary", default="dyn-summary.md")
    ap.add_argument("--who", action="store_true", help="add role/origin breakdowns (needs the static data of init, std, mathlib)")
    ap.add_argument("names", nargs="+")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    S = []  # summary sections: list of (title, header, rows)
    tot_rows, conc_rows, split_rows, hit_rows, nored_rows, eval_rows, chain_rows = [], [], [], [], [], [], []
    cov = {k: [] for k in ("BODY (delta, derived rules off)", "BODY (delta, derived rules on)", "K2 rule firings", "RULE (iota, rules off)",
                           "RULE (iota, rules on)", "PROJ (ctor.field)", "TELE (application inference)", "TELE static (for comparison)")}
    thr = {k: [] for k in cov}
    nat_rows, k2_rows, kind_rows = [], [], []
    who = {}  # (series, 'role'|'origin') -> {name: {label: share}}
    origin = origin_fn() if a.who else None
    for name in a.names:
        di = load_decl(os.path.join(a.dir, f"{name}.di.tsv"))
        st = load_decl(os.path.join(a.dir, f"{name}.stats.tsv"))
        nod = load_decl(os.path.join(a.dir, f"{name}.nod.tsv"))
        dump = load_dump(os.path.join(a.dir, f"{name}.dump.tsv"))
        ndump = load_dump(os.path.join(a.dir, f"{name}.nod.dump.tsv"))
        consts, nodes = load_static(name)
        if not di:
            print(f"{name}: no di data", file=sys.stderr)
            continue
        meas = {}
        for v in ("di", "stats", "nod"):
            p = os.path.join(a.dir, f"{name}.{v}.measure.json")
            if os.path.exists(p):
                try:
                    meas[v] = json.load(open(p))
                except ValueError:
                    meas[v] = {}
        total_proc = meas.get("di", {}).get("instructions") or 0
        size = 0
        ex = os.path.join(WL, "..", "exports", f"{name}.ndjson")
        if os.path.exists(ex):
            size = os.path.getsize(ex)
        instr = sorted((d["instr"] for d in di), reverse=True)
        check = sum(instr)
        # ---- totals
        tot_rows.append([name, len(di), fmt_n(total_proc), f"{total_proc / size:.0f}" if size else "-", fmt_n(check), pct(check / total_proc) if total_proc else "-",
                         fmt_n(meas.get("stats", {}).get("instructions") or 0), fmt_n(meas.get("nod", {}).get("instructions") or 0),
                         f"{(meas.get('nod', {}).get('instructions') or 0) / (meas.get('stats', {}).get('instructions') or 1):.2f}",
                         f"{meas.get('di', {}).get('wall_time_s', 0):.1f}"])
        # ---- concentration
        _, _, top, need = coverage(instr, (1, 10, 100, 1000, 10000))
        med = instr[len(instr) // 2]
        conc_rows.append([name, pct(top.get(1, 1)), pct(top.get(10, 1)), pct(top.get(100, 1)), pct(top.get(1000, 1)), pct(top.get(10000, 1)),
                          need.get(0.5), need.get(0.8), need.get(0.9), fmt_n(med), fmt_n(instr[len(instr) // 100]), fmt_n(instr[0])])
        bykind = {}
        for d in di:
            k = d["kind"]
            bykind.setdefault(k, [0, 0])
            bykind[k][0] += 1
            bykind[k][1] += d["instr"]
        kind_rows.append([name] + [f"{pct(bykind.get(k, [0, 0])[1] / check)} ({bykind.get(k, [0, 0])[0]})" for k in ("thm", "def", "ind", "ctor", "rec", "opaque", "axiom")])
        # ---- per-declaration join
        stn = {d["name"]: d for d in st}
        nodn = {d["name"]: d for d in nod}
        # ---- top 50
        top50 = sorted(di, key=lambda d: -d["instr"])[:50]
        with open(os.path.join(a.out, f"top50-{name}.md"), "w") as f:
            f.write(f"# {name}: the 50 most expensive declarations\n\n")
            f.write("Instructions: user-mode, worker thread, uninstrumented build of nano-lean v0.4.2 (no PGO), one thread. Counters: vstats build, derived rules on. "
                    "`nodes` = expression records first written for the declaration. `nod x` = instructions with derived rules off / on (vstats builds).\n\n")
            rows = []
            acc = 0
            for r, d in enumerate(top50):
                s = stn.get(d["name"], {"kind": d["kind"]})
                s.setdefault("kind", d["kind"])
                acc += d["instr"]
                nd = nodn.get(d["name"])
                ratio = f"{nd['instr'] / s['instr']:.2f}" if nd and s.get("instr") else "-"
                rows.append([r + 1, f"`{d['name']}`", d["kind"], fmt_n(d["instr"]), pct(d["instr"] / check, 2), pct(acc / check), nodes.get(d["name"], "-"),
                             fmt_n(s.get("infer_slow", 0)), fmt_n(s.get("evals", 0)), fmt_n(s.get("deq_calls", 0) - s.get("deq_ptr_eq", 0)),
                             fmt_n(s.get("unfold_whnf", 0) + s.get("unfold_lazy", 0)), fmt_n(s.get("fired", 0)), fmt_n(s.get("iota", 0)),
                             fmt_n(s.get("proj_whnf", 0)), fmt_n(s.get("natops", 0)), s.get("max_chain", 0), s.get("max_stack", 0) >> 10, ratio,
                             describe(s, nodes.get(d["name"], 0)) if "instr" in s else "(no counters)"])
            f.write(md_table(["#", "declaration", "kind", "instr", "share", "cum", "nodes", "infer miss", "evals", "conv", "delta", "fired", "iota", "proj", "natops",
                              "chain", "stack KiB", "nod x", "what makes it expensive"], rows,
                             ["r", "l", "l"] + ["r"] * 15 + ["l"]))
            f.write("\n")
        # ---- work split (calls) from the stats run
        if st:
            def tot(k):
                return sum(d.get(k, 0) for d in st)
            split_rows.append([name, fmt_n(tot("infer_calls")), fmt_n(tot("infer_slow")), fmt_n(tot("evals") + tot("eval_hits")), fmt_n(tot("evals")),
                               fmt_n(tot("deq_calls")), fmt_n(tot("deq_calls") - tot("deq_ptr_eq")), fmt_n(tot("whnf_calls")), fmt_n(tot("whnfs")),
                               fmt_n(tot("whnf_core_miss")), fmt_n(tot("unfold_whnf") + tot("unfold_lazy")), fmt_n(tot("fired")), fmt_n(tot("iota")),
                               fmt_n(tot("proj_whnf") + tot("proj_eval")), fmt_n(tot("natops")), fmt_n(tot("vals"))])
            hit_rows.append([name,
                             pct(1 - tot("infer_slow") / max(tot("infer_calls"), 1)),
                             pct(tot("eval_hits") / max(tot("evals") + tot("eval_hits"), 1)),
                             pct(tot("deq_ptr_eq") / max(tot("deq_calls"), 1)),
                             pct(tot("neq_hit") / max(tot("deq_calls") - tot("deq_ptr_eq"), 1)),
                             pct(1 - tot("whnfs") / max(tot("whnf_calls"), 1)),
                             pct(1 - tot("whnf_core_miss") / max(tot("whnf_core_calls"), 1)),
                             pct(tot("unfold_memo_hit") / max(tot("unfold_memo_hit") + tot("unfolds"), 1)),
                             pct(tot("inst_hit") / max(tot("inst_hit") + tot("inst_miss"), 1)),
                             pct(1 - tot("const_ty_miss") / max(tot("const_ty_calls"), 1), 2)])
            # declarations with essentially no reduction
            dinstr = {d["name"]: d["instr"] for d in di}
            n0 = n10 = n100 = 0
            i0 = i10 = i100 = 0
            heavy = 0
            iheavy = 0
            checked = [d for d in st if d["kind"] in ("thm", "def", "opaque", "axiom")]
            csum = sum(dinstr.get(d["name"], 0) for d in checked) or 1
            for d in checked:
                red = d.get("unfold_whnf", 0) + d.get("unfold_lazy", 0) + d.get("fired", 0) + d.get("iota", 0) + d.get("proj_whnf", 0) + d.get("natops", 0) + d.get("quot", 0)
                ins = dinstr.get(d["name"], 0)
                if red == 0:
                    n0 += 1
                    i0 += ins
                if red <= 10:
                    n10 += 1
                    i10 += ins
                if red <= 100:
                    n100 += 1
                    i100 += ins
                if red >= 10000:
                    heavy += 1
                    iheavy += ins
            nored_rows.append([name, len(checked), f"{pct(n0 / len(checked))} / {pct(i0 / csum)}", f"{pct(n10 / len(checked))} / {pct(i10 / csum)}",
                               f"{pct(n100 / len(checked))} / {pct(i100 / csum)}", f"{heavy} ({pct(heavy / len(checked), 2)}) / {pct(iheavy / csum)}"])
            # evaluation-style work
            mc = max(st, key=lambda d: d.get("max_chain", 0))
            ms = max(st, key=lambda d: d.get("max_stack", 0))
            eval_rows.append([name, fmt_n(tot("natops")), fmt_n(tot("natops_big")), fmt_n(tot("iota_natlit")), fmt_n(tot("nat2ctor")), fmt_n(tot("str2ctor")),
                              fmt_n(tot("str2ctor_chars")), dump["S"][-1] if dump["S"] else "-", fmt_n(tot("krule")), fmt_n(tot("struct_eta_expose")), fmt_n(tot("quot")),
                              pct(tot("unfold_closed") / max(tot("unfold_whnf") + tot("unfold_lazy"), 1)),
                              pct(tot("whnf_core_red_closed") / max(tot("whnf_core_red"), 1))])
            w = dump["W"] or []
            wn = (ndump["W"] or [])
            nz = [i for i, v in enumerate(w) if v]
            chain_rows.append([name, f"{mc.get('max_chain', 0)} (`{mc['name']}`)", f"{max((d.get('max_chain', 0) for d in nod), default=0)}",
                               fmt_n(sum(w[1:2])), fmt_n(sum(w[2:4])), fmt_n(sum(w[4:7])), fmt_n(sum(w[7:11])), fmt_n(sum(w[11:])),
                               f"{ms.get('max_stack', 0) >> 10} KiB (`{ms['name']}`)", sum(1 for d in st if d.get("max_stack", 0) >= (1 << 20))])
            nat_rows.append((name, dump["N"]))
            k2_rows.append([name, sum(1 for c in dump["C"].values() if c["derived"]), fmt_n(tot("fired")), fmt_n(tot("fire_misses")),
                            fmt_n(tot("unfold_whnf") + tot("unfold_lazy")),
                            fmt_n(sum(d.get("unfold_whnf", 0) + d.get("unfold_lazy", 0) for d in nod)), fmt_n(tot("iota")), fmt_n(sum(d.get("iota", 0) for d in nod)),
                            fmt_n(tot("evals")), fmt_n(sum(d.get("evals", 0) for d in nod))])
        # ---- heat tables
        C, NC = dump["C"], ndump["C"]
        series = {
            "BODY (delta, derived rules off)": {n: c["unfold_whnf"] + c["unfold_lazy"] for n, c in NC.items()},
            "BODY (delta, derived rules on)": {n: c["unfold_whnf"] + c["unfold_lazy"] for n, c in C.items()},
            "K2 rule firings": {n: c["fired"] for n, c in C.items()},
            "RULE (iota, rules off)": {f"{n}#{k}": v for n, k, v in ndump["I"]},
            "RULE (iota, rules on)": {f"{n}#{k}": v for n, k, v in dump["I"]},
            "PROJ (ctor.field)": {},
            "TELE (application inference)": {n: c["tele_check"] + c["tele_only"] + c["tele_val"] for n, c in C.items()},
        }
        pj = series["PROJ (ctor.field)"]
        for n, k, v in dump["PW"] + dump["PE"]:
            pj[f"{n}.{k}"] = pj.get(f"{n}.{k}", 0) + v
        p = os.path.join(WL, "static", name, "consts.tsv")
        if os.path.exists(p):
            sh = {}
            with open(p, encoding="utf-8", errors="replace") as f:
                next(f)
                for line in f:
                    q = line.split("\t")
                    sh[q[1]] = int(q[7])
            series["TELE static (for comparison)"] = sh
        for k, m in series.items():
            cov[k].append(heat_rows(name, m.values()))
            thr[k].append(th_row(name, m.values()))
            if origin is None or k.startswith("TELE static") or "rules on" in k:
                continue
            total = sum(m.values()) or 1
            byrole, byorig = {}, {}
            for n, v in m.items():
                if not v:
                    continue
                base = n.split("#")[0] if "#" in n else (n.rsplit(".", 1)[0] if k.startswith("PROJ") else n)
                kd, fl = consts.get(base, ("?", ""))
                r = role(base, kd, fl) if kd != "?" else "?"
                byrole[r] = byrole.get(r, 0) + v / total
                o = origin(base)
                byorig[o] = byorig.get(o, 0) + v / total
            who.setdefault((k, "role"), {})[name] = byrole
            who.setdefault((k, "origin"), {})[name] = byorig
        with open(os.path.join(a.out, f"heat-{name}.md"), "w") as f:
            f.write(f"# {name}: dynamic heat per constant (instrumented nano-lean, one thread)\n\n")
            for k, m in series.items():
                if k.startswith("TELE static"):
                    continue
                total = sum(m.values()) or 1
                f.write(f"## {k}: top 30 of {sum(1 for v in m.values() if v)} (total {fmt_n(total)})\n\n")
                rows = []
                for r, (n, v) in enumerate(sorted(m.items(), key=lambda kv: -kv[1])[:30]):
                    base = n.split("#")[0] if "#" in n else (n.rsplit(".", 1)[0] if k.startswith("PROJ") else n)
                    kd, fl = consts.get(base, ("?", ""))
                    extra = ""
                    if k.startswith("BODY") and n in C:
                        extra = f"closed {pct(C[n]['unfold_closed'] / max(C[n]['unfold_whnf'] + C[n]['unfold_lazy'], 1), 0)}" if "on" in k else ""
                    if k.startswith("TELE") and n in C:
                        c = C[n]
                        extra = f"check {fmt_n(c['tele_check'])} / infer-only {fmt_n(c['tele_only'])} / value {fmt_n(c['tele_val'])}"
                    rows.append([r + 1, f"`{n}`", fmt_n(v), pct(v / total), role(base, kd, fl) if kd != "?" else "?", extra])
                f.write(md_table(["#", "name", "count", "share", "role", "note"], rows, ["r", "l", "r", "r", "l", "l"]))
                f.write("\n\n")
    # ---------------- summary file
    with open(os.path.join(a.out, a.summary), "w") as f:
        f.write("## D1. Totals (instrumented nano-lean v0.4.2 copy, no PGO, `-j 1`)\n\n")
        f.write(md_table(["corpus", "decls", "process instr", "instr/byte", "sum over decls", "share", "vstats run", "vstats, rules off", "off/on", "wall s (loaded host)"], tot_rows))
        f.write("\n\n## D2. Concentration of per-declaration cost (share of the summed per-declaration instructions)\n\n")
        f.write(md_table(["corpus", "top1", "top10", "top100", "top1000", "top10000", "N50%", "N80%", "N90%", "median decl", "p99 decl", "max decl"], conc_rows))
        f.write("\n\nShare of checking instructions by declaration kind (count):\n\n")
        f.write(md_table(["corpus", "thm", "def", "ind", "ctor", "rec", "opaque", "axiom"], kind_rows))
        f.write("\n\n## D3. Calls (vstats build, derived rules on)\n\n")
        f.write(md_table(["corpus", "infer calls", "infer misses", "eval calls", "eval misses", "def_eq calls", "def_eq slow", "whnf calls", "whnf misses",
                          "whnf_core misses", "delta", "rule firings", "iota", "proj-of-ctor", "Nat ops", "values made"], split_rows))
        f.write("\n\nCache hit rates:\n\n")
        f.write(md_table(["corpus", "infer memo", "eval memo", "def_eq ptr/eq-cache", "neq cache (of slow)", "whnf memo", "whnf_core memo", "unfold memo",
                          "Pi-inst memo", "const type table"], hit_rows))
        f.write("\n\nDeclarations (thm/def/opaque/axiom) by number of reduction steps (delta + rule firings + iota + proj + Nat ops + quot); `a / b` = share of declarations / share of their instructions:\n\n")
        f.write(md_table(["corpus", "checked", "0 steps", "<= 10", "<= 100", ">= 10000 steps"], nored_rows))
        f.write("\n\n## D4. Dynamic heat coverage, in the form of the static HEAT table\n\n")
        hdr = ["corpus", "executions", "objects used", "top1", "top10", "top30", "top100", "top1000", "N50%", "N80%", "N90%", "N95%", "N99%"]
        for k in cov:
            f.write(f"### {k}\n\n")
            f.write(md_table(hdr, cov[k]))
            f.write("\n\nObjects with at least N executions (share of executions they receive, share that happens after crossing N):\n\n")
            f.write(md_table(["corpus", ">=1e3", ">=1e4", ">=1e5", ">=1e6", ">=1e7"], thr[k]))
            f.write("\n\n")
        if who:
            f.write("## D4b. Who receives the dynamic heat (share of executions)\n\n")
            names = [n for n in a.names]
            for (k, what), tab in who.items():
                labels = {}
                for n in names:
                    for lab, v in tab.get(n, {}).items():
                        labels[lab] = max(labels.get(lab, 0), v)
                labs = [lab for lab, v in sorted(labels.items(), key=lambda kv: -kv[1]) if v >= 0.005]
                f.write(f"### {k}, by {what}\n\n")
                f.write(md_table([what] + names, [[lab] + [pct(tab.get(n, {}).get(lab, 0)) for n in names] for lab in labs]))
                f.write("\n\n")
        f.write("## D5. Derived rules (nano-lean's K2 analogue): on versus off\n\n")
        f.write(md_table(["corpus", "rules derived", "firings", "fire misses", "delta (on)", "delta (off)", "iota (on)", "iota (off)", "eval misses (on)", "eval misses (off)"], k2_rows))
        f.write("\n\n## D6. Evaluation-style work\n\n")
        f.write(md_table(["corpus", "Nat ops", "operands >= 2^63", "iota on Nat literal", "Nat lit -> ctor", "String lit -> ctor", "chars expanded", "longest string",
                          "K-rule", "struct-eta in iota", "Quot.lift/ind", "delta on closed terms", "whnf_core steps on closed terms"], eval_rows))
        f.write("\n\nwhnf chains (delta/Nat steps per non-memoised whnf call) and recursion depth:\n\n")
        f.write(md_table(["corpus", "longest chain, rules on", "rules off", "calls with 1 step", "2-7", "8-63", "64-1023", ">= 1024", "deepest stack", "decls >= 1 MiB stack"], chain_rows,
                         ["l", "l", "r", "r", "r", "r", "r", "r", "l", "r"]))
        f.write("\n\nNat literal operations by operation and size of the larger operand (bits):\n\n")
        ops = ["succ", "add", "sub", "mul", "div", "mod", "gcd", "beq", "ble", "land", "lor", "xor", "shl", "shr", "pow", "log2"]
        rows = []
        for name, N in nat_rows:
            if not N:
                continue
            tot = [sum(N[o][i] for o in ops if o in N) for i in range(6)]
            alln = sum(tot) or 1
            byop = sorted(((sum(N[o][:6]), o) for o in ops if o in N), reverse=True)
            rows.append([name, fmt_n(alln)] + [pct(t / alln) for t in tot] + [max(N[o][6] for o in N), ", ".join(f"{o} {pct(v / alln, 0)}" for v, o in byop[:5] if v)])
        f.write(md_table(["corpus", "ops", "<=31", "32-63", "64", "65-256", "257-4096", ">4096", "max bits", "by operation"], rows, ["l"] + ["r"] * 8 + ["l"]))
        f.write("\n")


if __name__ == "__main__":
    main()
