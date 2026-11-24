"""Shared helpers for the workload-characterization scripts (bench/workloads/*.py).

Python stdlib only; reads data files only. Run the scripts with `python3 -I`.
"""
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
WL = os.path.join(ROOT, "work", "workloads")
CORPORA = ["init", "std", "cedar", "reachability", "con-leche", "navier-stokes-euler", "cslib", "mathlib"]
SHORT = {"navier-stokes-euler": "NSE", "con-leche": "con-leche", "reachability": "reach"}

_MATCH = re.compile(r"^match_\d+$")
_AUX = {
    "casesOn": "casesOn", "recOn": "recOn", "brecOn": "brecOn/below", "below": "brecOn/below",
    "binductionOn": "brecOn/below", "ibelow": "brecOn/below", "noConfusion": "noConfusion",
    "noConfusionType": "noConfusion", "ctorIdx": "ctorIdx/ctorElim", "ctorElim": "ctorIdx/ctorElim",
    "ctorElimType": "ctorIdx/ctorElim", "toCtorIdx": "ctorIdx/ctorElim",
}


def role(name, kind, flags=""):
    """Coarse role of a constant, from its kind, name and the static flags of static-shape."""
    parts = name.split(".")
    last = parts[-1]
    if kind == "rec":
        return "recursor"
    if kind == "ctor":
        return "constructor"
    if kind == "ind":
        return "inductive type"
    if kind in ("axiom", "quot", "opaque"):
        return kind
    if kind == "thm":
        return "theorem"
    if _MATCH.match(last) or (len(parts) > 1 and _MATCH.match(parts[-2]) is not None):
        return "matcher"
    if last in _AUX:
        return _AUX[last]
    if len(parts) > 1 and parts[-2] in ("brecOn", "below", "noConfusion", "casesOn") :
        return _AUX.get(parts[-2], "definition")
    if "P" in flags:
        return "structure projection fn"
    if last.startswith("inst") or last.startswith("_inst"):
        return "instance"
    if last in ("_f", "_unary", "_mutual", "_sunfold", "_unsafe_rec", "go", "loop") or last.startswith("_"):
        return "definition (aux)"
    return "definition"


def load_consts(corpus):
    """idx -> dict(name, kind, hint, nparams, lam_arity, flags, heat, heat_thm, seg, bare)."""
    path = os.path.join(WL, "static", corpus, "consts.tsv")
    out = []
    with open(path, encoding="utf-8", errors="replace") as f:
        next(f)
        for line in f:
            p = line.rstrip("\n").split("\t")
            out.append({
                "name": p[1], "kind": p[2], "hint": p[3], "nparams": int(p[4]), "lam_arity": int(p[5]),
                "flags": p[6], "heat": int(p[7]), "heat_thm": int(p[8]), "seg": int(p[9]), "bare": int(p[10]),
            })
    return out


_name_sets = {}


def name_set(corpus):
    if corpus not in _name_sets:
        s = set()
        path = os.path.join(WL, "static", corpus, "consts.tsv")
        with open(path, encoding="utf-8", errors="replace") as f:
            next(f)
            for line in f:
                s.add(line.split("\t", 2)[1])
        _name_sets[corpus] = s
    return _name_sets[corpus]


def origin_fn():
    """name -> 'Init' | 'Std' | 'Mathlib' | 'project' by membership in the init/std/mathlib exports."""
    init, std, ml = name_set("init"), name_set("std"), name_set("mathlib")

    def origin(name):
        if name in init:
            return "Init"
        if name in std:
            return "Std"
        if name in ml:
            return "Mathlib"
        return "project"
    return origin


def coverage(counts, tops=(1, 10, 30, 100, 300, 1000, 3000, 10000), shares=(0.5, 0.8, 0.9, 0.95, 0.99)):
    """counts: iterable of non-negative ints. Returns (total, used, {topN: share}, {share: N})."""
    v = sorted((c for c in counts if c > 0), reverse=True)
    total = sum(v)
    top = {}
    need = {}
    acc = 0
    si = 0
    shares = list(shares)
    for r, c in enumerate(v):
        acc += c
        if r + 1 in tops:
            top[r + 1] = acc / total
        while si < len(shares) and acc >= shares[si] * total:
            need[shares[si]] = r + 1
            si += 1
    for t in tops:
        if t not in top and total > 0 and t >= len(v):
            top[t] = 1.0
    return total, len(v), top, need


def thresholds(counts, ths=(10**3, 10**4, 10**5, 10**6, 10**7)):
    """For each threshold: (#items with count >= th, share of total they receive, share after crossing)."""
    v = [c for c in counts if c > 0]
    total = sum(v) or 1
    out = {}
    for th in ths:
        hot = [c for c in v if c >= th]
        out[th] = (len(hot), sum(hot) / total, sum(c - th for c in hot) / total)
    return out


def fmt_n(x):
    """Compact integer: 1234567 -> 1.23M."""
    x = float(x)
    for unit, div in (("G", 1e9), ("M", 1e6), ("k", 1e3)):
        if abs(x) >= div:
            v = x / div
            return f"{v:.2f}{unit}" if v < 10 else (f"{v:.1f}{unit}" if v < 100 else f"{v:.0f}{unit}")
    return f"{int(x)}"


def pct(x, digits=1):
    return f"{100 * x:.{digits}f}%"


def md_table(header, rows, align=None):
    align = align or ["l"] + ["r"] * (len(header) - 1)
    line = "|" + "|".join(" " + h + " " for h in header) + "|"
    sep = "|" + "|".join("---" if a == "l" else "--:" for a in align) + "|"
    out = [line, sep]
    for r in rows:
        out.append("|" + "|".join(" " + str(c) + " " for c in r) + "|")
    return "\n".join(out)
