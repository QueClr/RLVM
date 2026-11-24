#!/usr/bin/env python3
"""Turns results/*.txt (output of scripts/pbatch.sh) into the tables of README.md.

Usage: python3 -I scripts/tables.py [results-dir]

Every number is the MEDIAN over the repetitions of a run (labels end in .<repetition>). Instructions are whole
process totals (perf stat instructions:u, all threads); "net" columns subtract another run of the same binary.
"""
import collections
import os
import re
import statistics
import sys

RES = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "results")

runs = collections.defaultdict(list)
for fn in sorted(os.listdir(RES)):
    if not fn.endswith(".txt") or fn.startswith(("heat", "native-build", "tables", "exp2-frozen")):
        continue
    for line in open(os.path.join(RES, fn), encoding="utf-8"):
        m = re.match(r"(\S+)\s+\| instr\s+([\d.]+) G \| cpu\s+([\d.]+) s \| wall\s+([\d.]+) s \| rss\s+(\d+) MB \| exit (\d+) \| (.*)", line)
        if not m:
            continue
        label, rep = m.group(1).rsplit(".", 1)
        if fn.startswith("exp4-std"):
            label = "std:" + label
        rest = m.group(7)
        d = {"instr": float(m.group(2)), "cpu": float(m.group(3)), "wall": float(m.group(4)), "rss": int(m.group(5)), "exit": int(m.group(6))}
        pm = re.search(r"pass_ms=\[([\d, ]*)\]", rest)
        d["pass"] = [int(x) for x in pm.group(1).split(",")] if pm and pm.group(1).strip() else []
        for key in ("promoted", "compiled_calls", "valid_at_loop_end", "finish_ms", "values", "record_ms", "unprofiled_arity_fallbacks"):
            km = re.search(key.replace("(", r"\(") + r"[= ](\d+)", rest)
            if km:
                d[key] = int(km.group(1))
        runs[label].append(d)


def med(label, key="instr"):
    if label not in runs:
        return None
    vals = [r[key] for r in runs[label] if key in r]
    return statistics.median(vals) if vals else None


def spread(label):
    vals = [r["instr"] for r in runs.get(label, [])]
    return (max(vals) - min(vals)) if len(vals) > 1 else 0.0


def pass1(label):
    vals = [r["pass"][0] for r in runs.get(label, []) if r["pass"]]
    return statistics.median(vals) if vals else None


def f(x, fmt="%.3f"):
    return "-" if x is None else fmt % x


SPINES = {"init": 2088996, "std": 3115533, "hot": None}
NAMES = {"jvm": "JVM mode (GraalVM CE 25 JDK, HotSpot + Graal JIT, SerialGC)", "nce": "native image, GraalVM CE 25.5.5.0.3", "nee": "native image, Oracle GraalVM 25.0.4"}


def exp1(rt, spines, title, thresholds=("1000", "10000", "100000", "1000000"), inl=("2", "0"), prefix=""):
    name = NAMES[rt]
    rt = prefix + rt
    if med(rt + ".none") is None:
        return
    print("\n### %s - %s, %d spines\n" % (title, name, spines))
    none, null = med(rt + ".none"), med(rt + ".null")
    print("| variant | process instr (G) | spread over reps (G) | net of `null` (G) | instr / spine | first pass wall (ms) | process wall (s) | max RSS (MB) |")
    print("|---|---|---|---|---|---|---|---|")
    rows = [("none", "setup only: load cache, derive masks, find spines"), ("null", "driver loop with an empty instantiator"), ("a0", "(a) generic interpretation"),
            ("a1", "(a) generic + closed-term memo"), ("elab", "elaboration of all used constants, nothing executed"), ("b", "(b) code objects, plain stepper (includes elaboration)"),
            ("c.never", "(c) promotion machinery present, threshold never reached")]
    for t in thresholds:
        for i in inl:
            rows.append(("c.T%s.i%s" % (t, i), "(c) promote at heat %s, %s" % (t, "probes inlined" if i == "2" else "call-threaded")))
    for key, desc in rows:
        lab = rt + "." + key
        v = med(lab)
        if v is None:
            continue
        net = None if key in ("none", "null") else v - null
        print("| %s: %s | %.3f | %.3f | %s | %s | %s | %s | %s |" % (key, desc, v, spread(lab), f(net), f(None if net is None else net * 1e9 / spines, "%.0f"), f(pass1(lab), "%.0f"),
                                                                   f(med(lab, "wall"), "%.2f"), f(med(lab, "rss"), "%.0f")))
    b, elab = med(rt + ".b"), med(rt + ".elab")
    a0, a1 = med(rt + ".a0"), med(rt + ".a1")
    if b and elab and a1:
        print("\nDerived: driver = %.0f instr/spine; elaboration = %.3f G (%.0f instr per elaborated constant); (b) without elaboration = %.0f instr/spine; "
              "(a0)/(b) = %.2fx, (a1)/(b) = %.2fx, (a1)/(b without elaboration) = %.2fx." % (
                  (null - none) * 1e9 / spines, elab - null, (elab - null) * 1e9 / (39783 if spines == SPINES["init"] else 69712), (b - elab) * 1e9 / spines,
                  (a0 - null) / (b - null), (a1 - null) / (b - null), (a1 - null) / (b - elab)))
    p3b, p3a = med(rt + ".b.p3"), med(rt + ".a1.p3")
    if p3b and p3a:
        drv = (null - none)
        print("Steady state (passes 2 and 3: every value already interned): (a1) %.0f, (b) %.0f instr/spine net of the driver." % (
            ((p3a - a1) / 2 - drv) * 1e9 / spines, ((p3b - b) / 2 - drv) * 1e9 / spines))
    brec, bm = med(rt + ".brec"), med(rt + ".bm")
    if brec and bm and p3b:
        drv = (null - none)
        print("Match mode: one pass that matches every domain against an existing value and builds only the result type = %.0f instr/spine net of the driver "
              "(build pass in steady state: %.0f)." % ((bm - brec - drv) * 1e9 / spines, ((p3b - b) / 2 - drv) * 1e9 / spines))
    # threshold sweep
    never = med(rt + ".c.never")
    if never is None:
        return
    print("\n| threshold | inlining | roots compiled | spines run in compiled code | process instr (G) | extra over (b) (G) | compile-only run `cc` (G) | compile cost per root (M instr) | saved by using the code (G) |")
    print("|---|---|---|---|---|---|---|---|---|")
    for t in thresholds:
        for i in inl:
            c, cc = med(rt + ".c.T%s.i%s" % (t, i)), med(rt + ".cc.T%s.i%s" % (t, i))
            if c is None:
                continue
            prom = med(rt + ".c.T%s.i%s" % (t, i), "promoted") or 0
            calls = med(rt + ".c.T%s.i%s" % (t, i), "compiled_calls") or 0
            per = (cc - never) * 1e3 / prom if prom and cc else None
            print("| %s | %s | %d | %d (%.1f%%) | %.3f | %+.3f | %s | %s | %s |" % (t, "probes inlined" if i == "2" else "call-threaded", prom, calls, 100.0 * calls / spines, c, c - b,
                                                                              f(cc), f(per, "%.0f"), f(None if cc is None else cc - c)))


def hot(rt):
    if med(rt + ".hot.null") is None:
        return
    sp = 1619782
    print("\n### Hot subset - %s\n" % NAMES[rt])
    null, b1, b3 = med(rt + ".hot.null"), med(rt + ".hot.b.p1"), med(rt + ".hot.b.p3")
    a1, a3 = med(rt + ".hot.a1.p1"), med(rt + ".hot.a1.p3")
    # driver per pass, estimated from the whole-Init runs of the same binary
    drv = (med(rt + ".null") - med(rt + ".none")) / SPINES["init"] * sp if med(rt + ".null") else 0.0
    print("| configuration | 1 pass (G) | 3 passes (G) | first pass, instr/spine net of driver | steady state, instr/spine net of driver | compile cost per root (M instr) | saving per spine vs (b), steady | break-even heat |")
    print("|---|---|---|---|---|---|---|---|")

    def per(x):
        return x * 1e9 / sp

    bs = per((b3 - b1) / 2 - drv)
    print("| (a1) generic + memo | %.3f | %.3f | %.0f | %.0f | - | - | - |" % (a1, a3, per(a1 - null), per((a3 - a1) / 2 - drv)))
    print("| (b) stepper | %.3f | %.3f | %.0f | %.0f | - | - | - |" % (b1, b3, per(b1 - null), bs))
    for key, desc in (("i0", "(c) call-threaded"), ("i1", "(c) APP probes inlined"), ("i2", "(c) all probes inlined"), ("i2.call1", "(c) all probes inlined, entered with callIndirect"),
                      ("i2.tier1", "(c) all probes inlined, first-tier compilation requested")):
        c1, c3 = med("%s.hot.c.%s.p1" % (rt, key)), med("%s.hot.c.%s.p3" % (rt, key))
        cck = key.replace(".call1", "")
        cc = med("%s.hot.cc.%s.p1" % (rt, cck))
        if c1 is None or c3 is None:
            continue
        prom = med("%s.hot.c.%s.p1" % (rt, key), "promoted") or 184
        comp = (cc - b1) * 1e3 / prom if cc else None
        ss = per((c3 - c1) / 2 - drv)
        first = per(c1 - (cc - b1) - null) if cc else None
        save = bs - ss
        be = comp * 1e6 / save if comp and save > 0 else None
        print("| %s | %.3f | %.3f | %s | %.0f | %s | %.0f | %s |" % (desc, c1, c3, f(first, "%.0f"), ss, f(comp, "%.0f"), save, "never" if be is None else "%.0f" % be))


def exp2():
    print("\n### Experiment 2 - recursive vs worklist NbE (59,979 declaration types of Init, one pass)\n")
    print("| runtime | values | none (G) | recursive (G) | worklist `wl` (G) | worklist `wl2` (G) | recursive net (G) | wl / recursive | wl2 / recursive | pass wall rec / wl / wl2 (ms) |")
    print("|---|---|---|---|---|---|---|---|---|---|")
    for rt in ("jvm", "nce", "nee"):
        for intern in ("true", "false"):
            none, rec, wl, wl2 = (med("%s.exp2.%s.intern-%s" % (rt, v, intern)) for v in ("none", "rec", "wl", "wl2"))
            if rec is None:
                continue
            print("| %s | %s | %.3f | %.3f | %.3f | %s | %.3f | **%.3f** | %s | %s / %s / %s |" % (
                rt, "hash-consed" if intern == "true" else "bump-allocated, no interning", none, rec, wl, f(wl2), rec - none, (wl - none) / (rec - none),
                "-" if wl2 is None else "**%.3f**" % ((wl2 - none) / (rec - none)), f(pass1("%s.exp2.rec.intern-%s" % (rt, intern)), "%.0f"),
                f(pass1("%s.exp2.wl.intern-%s" % (rt, intern)), "%.0f"), f(pass1("%s.exp2.wl2.intern-%s" % (rt, intern)), "%.0f")))


def exp3():
    print("\n### Experiment 3 - arena access (whole Init, arena and table pre-sized)\n")
    print("| runtime | access | none (G) | (b) (G) | (b) net (G) | vs int[] | (a1) net (G) | vs int[] | (b) pass wall (ms) |")
    print("|---|---|---|---|---|---|---|---|---|")
    modes = ["int[] (bounds-checked)", "sun.misc.Unsafe", "MemorySegment.getAtIndex(JAVA_INT)", "MemorySegment.get(JAVA_INT_UNALIGNED)"]
    for rt in ("jvm", "nce", "nee"):
        base_b = base_a = None
        for m in range(4):
            none, b, a1 = (med("%s.mem%d.%s" % (rt, m, v)) for v in ("none", "b", "a1"))
            if b is None:
                continue
            nb, na = b - none, a1 - none
            if m == 0:
                base_b, base_a = nb, na
            print("| %s | %s | %.3f | %.3f | %.3f | %.2fx | %.3f | %.2fx | %s |" % (rt, modes[m], none, b, nb, nb / base_b, na, na / base_a, f(pass1("%s.mem%d.b" % (rt, m)), "%.0f")))


def steady_jvm():
    if med("jvm.steady.null.p11") is None:
        return
    sp, hsp = SPINES["init"], 1619861

    def per_pass(key):
        a, b = med("jvm.steady.%s.p11" % key), med("jvm.steady.%s.p21" % key)
        return None if a is None or b is None else (b - a) / 10

    print("\n### JVM mode, steady state: (21 passes - 11 passes) / 10, instructions per spine net of the driver\n")
    print("One process pair per row. Unreliable to about +-40% (see the long-run table below): the two processes do not JIT-compile alike.\n")
    drv, hdrv = per_pass("null"), per_pass("hot.null")
    print("Driver: %.0f instr/spine (whole Init), %.0f (hot subset).\n" % (drv * 1e9 / sp, hdrv * 1e9 / hsp))
    print("| whole Init (2,088,996 spines) | instr / spine |\n|---|---|")
    for key, desc in (("a0", "(a) generic"), ("a1", "(a) generic + closed-term memo"), ("b", "(b) stepper"), ("bm", "(b) match mode: domains matched, result built")):
        v = per_pass(key)
        if v is not None:
            print("| %s | %.0f |" % (desc, (v - drv) * 1e9 / sp))
    for m, name in enumerate(("int[]", "sun.misc.Unsafe", "MemorySegment.getAtIndex(JAVA_INT)", "MemorySegment.get(JAVA_INT_UNALIGNED)")):
        v = per_pass("mem%d.b" % m)
        if v is not None:
            print("| (b) stepper, arena access = %s | %.0f |" % (name, (v - drv) * 1e9 / sp))
    print("\n| hot subset (184 constants, 1,619,861 spines) | instr / spine | saving vs (b) | compile cost per root (M instr, from the hot-jvm batch) | break-even heat |\n|---|---|---|---|---|")
    bs = (per_pass("hot.b") - hdrv) * 1e9 / hsp
    b1 = med("jvm.hot.b.p1")
    for key, desc, cck in (("hot.a1", "(a1) generic + memo", None), ("hot.b", "(b) stepper", None), ("hot.c.i0", "(c) call-threaded", "i0"), ("hot.c.i1", "(c) APP probes inlined", "i1"),
                           ("hot.c.i2", "(c) all probes inlined", "i2"), ("hot.c.i2.call1", "(c) all probes inlined, callIndirect", "i2")):
        v = per_pass(key)
        if v is None:
            continue
        x = (v - hdrv) * 1e9 / hsp
        comp = None
        if cck and b1 and med("jvm.hot.cc.%s.p1" % cck):
            comp = (med("jvm.hot.cc.%s.p1" % cck) - b1) * 1e3 / 184
        be = comp * 1e6 / (bs - x) if comp and bs - x > 0 else None
        print("| %s | %.0f | %s | %s | %s |" % (desc, x, "-" if cck is None else "%.0f" % (bs - x), f(comp, "%.0f"), "-" if cck is None else ("never" if be is None else "%.0f" % be)))
    print("\n| experiment 2, JVM steady state | recursive (G / pass) | worklist `wl` | worklist `wl2` | wl / rec | wl2 / rec |\n|---|---|---|---|---|---|")
    for intern in ("true", "false"):
        r, w, w2 = (per_pass("exp2.%s.intern-%s" % (v, intern)) for v in ("rec", "wl", "wl2"))
        if r:
            print("| %s | %.3f | %.3f | %s | **%.3f** | %s |" % ("hash-consed" if intern == "true" else "bump-allocated, no interning", r, w, f(w2), w / r, "-" if w2 is None else "**%.3f**" % (w2 / r)))


def long_jvm():
    """Every (shorter run, longer run) pair of the long JVM batches as an independent steady-state estimate."""
    sp = SPINES["init"]
    rows = []
    for v, desc in (("null", "driver"), ("a1", "(a) generic + memo"), ("b", "(b) stepper"), ("b2", "(b2) stepper with the probe expanded in place"), ("bm", "(b) match mode pass"),
                    ("mem1.b", "(b) stepper, Unsafe arena")):
        est = []
        for rep in range(3):
            def get(p):
                r = runs.get("jvm.long.%s.p%d" % (v, p), [])
                return r[rep]["instr"] if rep < len(r) else None
            for lo, hi in ((21, 41), (41, 81)):
                a, b = get(lo), get(hi)
                if a is not None and b is not None:
                    est.append((b - a) / (hi - lo) * 1e9 / sp)
        if est:
            rows.append((desc, est))
    if not rows:
        return
    print("\n### JVM mode, steady state from long runs: (longer run - shorter run) / extra passes, instr/spine INCLUDING the driver\n")
    print("Each estimate compares two separate JVM processes (21 vs 41 or 41 vs 81 passes). The spread between estimates is JIT non-determinism, not measurement error.\n")
    print("| variant | estimates | min | max |\n|---|---|---|---|")
    for desc, est in rows:
        print("| %s | %s | %.0f | %.0f |" % (desc, ", ".join("%.0f" % e for e in est), min(est), max(est)))


def jvm_c2():
    if med("jc2.none") is None:
        return
    sp = SPINES["init"]
    print("\n### JVM mode with C2 as host JIT and no tiered compilation (-XX:-UseJVMCICompiler -XX:-TieredCompilation), whole Init\n")
    print("| variant | process instr (G), median of 3 | min - max | net of `null` (G) | first pass wall (ms) |\n|---|---|---|---|---|")
    null = med("jc2.null")
    for key in ("none", "null", "a0", "a1", "elab", "b", "b2", "c.never", "c.T100000.i0", "c.T100000.i2", "c.T10000.i0", "c.T10000.i2"):
        lab = "jc2." + key
        v = med(lab)
        if v is None:
            continue
        vals = [r["instr"] for r in runs[lab]]
        print("| %s | %.3f | %.3f - %.3f | %s | %s |" % (key, v, min(vals), max(vals), "-" if key in ("none", "null") else "%.3f" % (v - null), f(pass1(lab), "%.0f")))
    print("\nSteady state, (41 passes - 21 passes) / 20, instr/spine INCLUDING the driver; one estimate per repetition:\n")
    print("| variant | estimates |\n|---|---|")
    for v, desc in (("null", "driver"), ("a1", "(a) generic + memo"), ("b", "(b) stepper"), ("b2", "(b2) stepper, probe expanded in place"), ("bm", "(b) match-mode pass")):
        est = []
        for rep in range(3):
            a = runs.get("jc2.long.%s.p21" % v, [])
            b = runs.get("jc2.long.%s.p41" % v, [])
            if rep < len(a) and rep < len(b):
                est.append((b[rep]["instr"] - a[rep]["instr"]) / 20 * 1e9 / sp)
        if est:
            print("| %s | %s |" % (desc, ", ".join("%.0f" % e for e in est)))
    print("\n| experiment 2, steady state (21 passes - 11 passes) / 10 | recursive (G / pass) | wl / rec | wl2 / rec |\n|---|---|---|---|")
    for intern in ("true", "false"):
        def pp(v):
            a, b = med("jc2.long.exp2.%s.intern-%s.p11" % (v, intern)), med("jc2.long.exp2.%s.intern-%s.p21" % (v, intern))
            return None if a is None or b is None else (b - a) / 10
        r, w, w2 = pp("rec"), pp("wl"), pp("wl2")
        if r:
            print("| %s | %.3f | **%.3f** | **%.3f** |" % ("hash-consed" if intern == "true" else "bump-allocated", r, w / r, w2 / r))


def final_images():
    if med("nce.fin.none") is None:
        return
    sp = SPINES["init"]
    print("\n### Base tier in different native images (plain images, whole Init)\n")
    print("| image | none (G) | driver instr/spine | (a1) first pass, instr/spine | (b) first pass incl. elaboration | (a1) steady | (b) steady | (b2) first pass | (b2) steady | match mode pass |")
    print("|---|---|---|---|---|---|---|---|---|---|")
    for tag, desc in (("nce.fin", "GraalVM CE, default (-O2)"), ("nce.O3", "GraalVM CE, -O3"), ("nee.fin", "Oracle GraalVM, default (-O2, ML-inferred profile)"),
                      ("nee.pgo", "Oracle GraalVM, --pgo with a profile of this very workload")):
        none, null, a1, b = (med("%s.%s" % (tag, v)) for v in ("none", "null", "a1", "b"))
        if b is None:
            continue
        a3, b3 = med(tag + ".a1.p3"), med(tag + ".b.p3")
        drv = null - none
        brec, bm = med(tag + ".brec"), med(tag + ".bm")
        mm = None if bm is None else (bm - brec - drv) * 1e9 / sp
        b2, b23 = med(tag + ".b2"), med(tag + ".b2.p3")
        print("| %s | %.3f | %.0f | %.0f | %.0f | %.0f | %.0f | %s | %s | %s |" % (desc, none, drv * 1e9 / sp, (a1 - null) * 1e9 / sp, (b - null) * 1e9 / sp, ((a3 - a1) / 2 - drv) * 1e9 / sp,
                                                                              ((b3 - b) / 2 - drv) * 1e9 / sp, f(None if b2 is None else (b2 - null) * 1e9 / sp, "%.0f"),
                                                                              f(None if b23 is None else ((b23 - b2) / 2 - drv) * 1e9 / sp, "%.0f"), f(mm, "%.0f")))


def micro():
    if med("nce.micro.loop.n1") is None:
        return
    print("\n### Calibration: instructions per primitive operation ((2N run - N run) / N, minus the empty loop)\n")
    print("| runtime | empty loop iteration | mkApp, value exists (hit) | the same probe hand-inlined, arrays in locals | mkApp, new value (insert) | closure with 2 captures (hit) |")
    print("|---|---|---|---|---|---|")
    for rt, n in (("jvm", 400e6), ("nce", 50e6), ("nee", 50e6)):
        def per(w, cnt):
            a, b = med("%s.micro.%s.n1" % (rt, w)), med("%s.micro.%s.n2" % (rt, w))
            return None if a is None or b is None else (b - a) * 1e9 / cnt
        loop = per("loop", n)
        if loop is None:
            continue
        loc = per("app-hit-local", n)
        ins = per("app-insert", 2e6)
        print("| %s | %.1f | %.0f | %s | %s | %.0f |" % (rt, loop, per("app-hit", n) - loop, "-" if loc is None else "%.0f" % (loc - loop),
                                                   "n/a (run too short for the JVM)" if rt == "jvm" else "%.0f" % (ins - loop), per("clo2-hit", n) - loop))


for rt in ("jvm", "nce", "nee"):
    exp1(rt, SPINES["init"], "Experiment 1, whole Init")
steady_jvm()
long_jvm()
jvm_c2()
final_images()
micro()
for rt in ("jvm", "nce", "nee"):
    hot(rt)
exp2()
exp3()
for rt in ("nce", "jvm"):
    exp1(rt, SPINES["std"], "Experiment 4, whole Std", prefix="std:")
