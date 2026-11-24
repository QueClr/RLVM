#!/usr/bin/env python3
"""Cut a lean4export NDJSON file down to the dependency cone of some declarations, as a standalone
export with dense ids (names and levels from 1, expressions from 0), records in the original order.

    python3 -I bench/workloads/slice-export.py [--axiomatize] EXPORT.ndjson NAMES.txt OUT.ndjson

NAMES.txt: one declaration name per line. The cone is everything those declarations refer to,
transitively: types, values and proofs of every constant mentioned, whole inductive blocks, the four
Quot constants together, and the constants the kernel's literal extensions expect (Nat, String, ...).
With --axiomatize, a theorem that is not one of the named declarations is written as an axiom with the
same name and type and its proof is not followed: the file then checks the named declarations against
their statements-only library (a benchmark, not a proof of anything).
Nothing is checked here; validate OUT with `leanchecker --from-export OUT`.
Reads data only. Python stdlib; two passes; about 2 GB of memory for a 1 GB export.
"""
import json
import sys
from array import array

BUILTIN = """Nat Nat.zero Nat.succ Nat.add Nat.sub Nat.mul Nat.div Nat.mod Nat.gcd Nat.beq Nat.ble Nat.land Nat.lor
Nat.xor Nat.shiftLeft Nat.shiftRight Nat.pow Nat.log2 Nat.pred String String.mk String.ofList Char Char.ofNat
List List.nil List.cons Bool Bool.true Bool.false Eq Eq.refl Quot Quot.mk Quot.lift Quot.ind""".split()

DUMP = dict(sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def main():
    args = sys.argv[1:]
    axiomatize = "--axiomatize" in args
    args = [a for a in args if a != "--axiomatize"]
    src, names_file, out = args[:3]
    targets = [l.strip() for l in open(names_file, encoding="utf-8") if l.strip()]
    # ---------------- pass 1: index
    pre = array("I", [0])
    comp = [""]
    lv_kids = [()]                     # level id -> child level ids
    lv_name = array("I", [0])          # level id -> param name id
    ek = bytearray()                   # 0 leaf, 1 app, 2 lam/pi, 3 let, 4 proj, 5 const, 6 sort
    e1, e2, e3, en = array("I"), array("I"), array("I"), array("I")
    const_lv = {}
    decls = []                         # (kind, obj)
    decl_of_name = {}                  # name id -> decl index
    with open(src, "rb") as f:
        for line in f:
            o = json.loads(line)
            if "in" in o:
                i = o["in"]
                assert i == len(pre), "name ids are not dense"
                if "str" in o:
                    pre.append(o["str"]["pre"])
                    comp.append(o["str"]["str"])
                else:
                    pre.append(o["num"]["pre"])
                    comp.append(str(o["num"]["i"]))
            elif "il" in o:
                assert o["il"] == len(lv_kids), "level ids are not dense"
                if "succ" in o:
                    lv_kids.append((o["succ"],))
                    lv_name.append(0)
                elif "max" in o:
                    lv_kids.append(tuple(o["max"]))
                    lv_name.append(0)
                elif "imax" in o:
                    lv_kids.append(tuple(o["imax"]))
                    lv_name.append(0)
                else:
                    lv_kids.append(())
                    lv_name.append(o["param"])
            elif "ie" in o:
                assert o["ie"] == len(ek), "expression ids are not dense"
                if "app" in o:
                    ek.append(1); e1.append(o["app"]["fn"]); e2.append(o["app"]["arg"]); e3.append(0); en.append(0)
                elif "lam" in o or "forallE" in o:
                    b = o.get("lam") or o.get("forallE")
                    ek.append(2); e1.append(b["type"]); e2.append(b["body"]); e3.append(0); en.append(b["name"])
                elif "letE" in o:
                    b = o["letE"]
                    ek.append(3); e1.append(b["type"]); e2.append(b["value"]); e3.append(b["body"]); en.append(b["name"])
                elif "proj" in o:
                    b = o["proj"]
                    ek.append(4); e1.append(b["struct"]); e2.append(0); e3.append(0); en.append(b["typeName"])
                elif "const" in o:
                    b = o["const"]
                    ek.append(5); e1.append(0); e2.append(0); e3.append(0); en.append(b["name"])
                    if b["us"]:
                        const_lv[o["ie"]] = b["us"]
                elif "sort" in o:
                    ek.append(6); e1.append(o["sort"]); e2.append(0); e3.append(0); en.append(0)
                elif "mdata" in o:
                    ek.append(7); e1.append(o["mdata"]["expr"]); e2.append(0); e3.append(0); en.append(0)
                else:
                    ek.append(0); e1.append(0); e2.append(0); e3.append(0); en.append(0)
            elif "meta" in o:
                pass
            else:
                kind = next(iter(o))
                d = o[kind]
                k = len(decls)
                decls.append((kind, d))
                if kind == "inductive":
                    for grp in ("types", "ctors", "recs"):
                        for m in d[grp]:
                            decl_of_name[m["name"]] = k
                else:
                    decl_of_name[d["name"]] = k

    def full(i):
        parts = []
        while i:
            parts.append(comp[i])
            i = pre[i]
        return ".".join(reversed(parts))

    by_str = {full(n): k for n, k in decl_of_name.items()}
    need_name = bytearray(len(pre))
    need_level = bytearray(len(lv_kids))
    need_expr = bytearray(len(ek))
    need_decl = bytearray(len(decls))
    work = []
    missing = 0
    is_target = bytearray(len(decls))
    tset = set(targets)
    for t in targets + BUILTIN:
        k = by_str.get(t)
        if k is None:
            if t in tset:
                missing += 1
            continue
        if t in tset:
            is_target[k] = 1
        if not need_decl[k]:
            need_decl[k] = 1
            work.append(k)
    quot = [k for k, (kind, _) in enumerate(decls) if kind == "quot"]

    def mark_name(n):
        while n and not need_name[n]:
            need_name[n] = 1
            n = pre[n]

    def mark_level(l):
        st = [l]
        while st:
            x = st.pop()
            if x == 0 or need_level[x]:
                continue
            need_level[x] = 1
            if lv_name[x]:
                mark_name(lv_name[x])
            st.extend(lv_kids[x])

    def want(n):
        k = decl_of_name.get(n)
        if k is not None and not need_decl[k]:
            need_decl[k] = 1
            work.append(k)

    def mark_expr(e):
        st = [e]
        while st:
            x = st.pop()
            if need_expr[x]:
                continue
            need_expr[x] = 1
            k = ek[x]
            if k == 1:
                st.append(e1[x]); st.append(e2[x])
            elif k == 2:
                mark_name(en[x]); st.append(e1[x]); st.append(e2[x])
            elif k == 3:
                mark_name(en[x]); st.append(e1[x]); st.append(e2[x]); st.append(e3[x])
            elif k == 4:
                mark_name(en[x]); want(en[x]); st.append(e1[x])
            elif k == 5:
                mark_name(en[x]); want(en[x])
                for l in const_lv.get(x, ()):
                    mark_level(l)
            elif k == 6:
                mark_level(e1[x])
            elif k == 7:
                st.append(e1[x])

    while work:
        k = work.pop()
        kind, d = decls[k]
        if kind == "quot":
            for q in quot:
                if not need_decl[q]:
                    need_decl[q] = 1
                    work.append(q)
        if kind == "inductive":
            for grp in ("types", "ctors", "recs"):
                for m in d[grp]:
                    mark_name(m["name"])
                    for n in m.get("levelParams", ()):
                        mark_name(n)
                    for n in m.get("all", ()):
                        mark_name(n)
                    for n in m.get("ctors", ()):
                        mark_name(n)
                    if "induct" in m:
                        mark_name(m["induct"])
                    mark_expr(m["type"])
                    for r in m.get("rules", ()):
                        mark_name(r["ctor"])
                        mark_expr(r["rhs"])
        else:
            mark_name(d["name"])
            for n in d.get("levelParams", ()):
                mark_name(n)
            for n in d.get("all", ()):
                mark_name(n)
            mark_expr(d["type"])
            if "value" in d and not (axiomatize and kind == "thm" and not is_target[k]):
                mark_expr(d["value"])

    # ---------------- pass 2: renumber and write
    new_name = array("I", bytes(4 * len(pre)))
    new_level = array("I", bytes(4 * len(lv_kids)))
    new_expr = array("I", bytes(4 * len(ek)))
    nn = nl = 0
    ne = 0
    ni = li = 0
    ei = -1
    di = -1
    kept_decls = 0
    axioms = 0

    def rn(i):
        return new_name[i] if i else 0

    def rl(i):
        return new_level[i] if i else 0

    with open(src, "rb") as f, open(out, "w", encoding="utf-8") as w:
        for line in f:
            c = line[2:4]
            if line[2:5] == b'in"':
                ni += 1
                if not need_name[ni]:
                    continue
                o = json.loads(line)
                nn += 1
                new_name[ni] = nn
                o["in"] = nn
                b = o.get("str") or o.get("num")
                b["pre"] = rn(b["pre"])
            elif line[2:5] == b'il"':
                li += 1
                if not need_level[li]:
                    continue
                o = json.loads(line)
                nl += 1
                new_level[li] = nl
                o["il"] = nl
                if "succ" in o:
                    o["succ"] = rl(o["succ"])
                elif "max" in o:
                    o["max"] = [rl(x) for x in o["max"]]
                elif "imax" in o:
                    o["imax"] = [rl(x) for x in o["imax"]]
                else:
                    o["param"] = rn(o["param"])
            elif c == b"me":
                w.write(line.decode("utf-8"))
                continue
            elif c in (b"ap", b"ie", b"fo", b"co", b"bv"):
                ei += 1
                if not need_expr[ei]:
                    continue
                o = json.loads(line)
                new_expr[ei] = ne
                o["ie"] = ne
                ne += 1
                if "app" in o:
                    b = o["app"]; b["fn"] = new_expr[b["fn"]]; b["arg"] = new_expr[b["arg"]]
                elif "lam" in o or "forallE" in o:
                    b = o.get("lam") or o.get("forallE")
                    b["type"] = new_expr[b["type"]]; b["body"] = new_expr[b["body"]]; b["name"] = rn(b["name"])
                elif "letE" in o:
                    b = o["letE"]
                    b["type"] = new_expr[b["type"]]; b["value"] = new_expr[b["value"]]; b["body"] = new_expr[b["body"]]; b["name"] = rn(b["name"])
                elif "proj" in o:
                    b = o["proj"]; b["struct"] = new_expr[b["struct"]]; b["typeName"] = rn(b["typeName"])
                elif "const" in o:
                    b = o["const"]; b["name"] = rn(b["name"]); b["us"] = [rl(x) for x in b["us"]]
                elif "sort" in o:
                    o["sort"] = rl(o["sort"])
                elif "mdata" in o:
                    o["mdata"]["expr"] = new_expr[o["mdata"]["expr"]]
            else:
                di += 1
                if not need_decl[di]:
                    continue
                kept_decls += 1
                o = json.loads(line)
                kind = next(iter(o))
                d = o[kind]
                if kind == "inductive":
                    for grp in ("types", "ctors", "recs"):
                        for m in d[grp]:
                            m["name"] = rn(m["name"])
                            m["levelParams"] = [rn(x) for x in m["levelParams"]]
                            if "all" in m:
                                m["all"] = [rn(x) for x in m["all"]]
                            if "ctors" in m:
                                m["ctors"] = [rn(x) for x in m["ctors"]]
                            if "induct" in m:
                                m["induct"] = rn(m["induct"])
                            m["type"] = new_expr[m["type"]]
                            for r in m.get("rules", ()):
                                r["ctor"] = rn(r["ctor"])
                                r["rhs"] = new_expr[r["rhs"]]
                else:
                    d["name"] = rn(d["name"])
                    d["levelParams"] = [rn(x) for x in d["levelParams"]]
                    if "all" in d:
                        d["all"] = [rn(x) for x in d["all"]]
                    d["type"] = new_expr[d["type"]]
                    if axiomatize and kind == "thm" and not is_target[di]:
                        axioms += 1
                        o = {"axiom": {"isUnsafe": False, "levelParams": d["levelParams"], "name": d["name"], "type": d["type"]}}
                    elif "value" in d:
                        d["value"] = new_expr[d["value"]]
            w.write(json.dumps(o, **DUMP))
            w.write("\n")
    print(f"targets {len(targets)} (not found: {missing}); kept {kept_decls} of {len(decls)} declaration records, {ne} of {len(ek)} expressions, "
          f"{nn} of {len(pre) - 1} names, {nl} of {len(lv_kids) - 1} levels; theorems written as axioms: {axioms}", file=sys.stderr)


if __name__ == "__main__":
    main()
