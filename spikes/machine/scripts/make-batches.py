#!/usr/bin/env python3
"""Writes the batch files for scripts/pbatch.sh into work/machine-out/batches/ (all, or only those named as arguments).

A batch line is "<label>\t<command>"; labels are <runtime>.<variant>[.<detail>].<repetition>.
Two builds of the same sources are used (see README "Builds"): "frozen" (jar frozen/, images machine-ce,
machine-ee, machine-ce-m0..3, machine-ee-m0) and "final" (jar frozen2/, images machine-*-fin); the classes on the
hot paths of experiment 1 (a), (b), (c) are byte-identical in both.

Runtimes:  jvm = GraalVM CE 25 JDK (HotSpot + libgraal), SerialGC
           nce = native image built by GraalVM CE 25.5.5.0.3        (machine-ce, plain: machine-ce-m<mode>)
           nee = native image built by Oracle GraalVM 25.0.4        (machine-ee, plain: machine-ee-m0)
"""
import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
OUT = ROOT + "/work/machine-out"
JAVA = ROOT + "/.toolchain/graalvm-ce-25/bin/java"
CP = "%s/frozen/machine.jar:%s/frozen/lib/*" % (OUT, OUT)
JVM = JAVA + " -XX:+UseSerialGC -Xmx6g --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow -cp " + CP


def cmd(rt, props, args, corpus="init", plain_mode=None):
    """One command line. props: list of -D settings; args: program arguments after the cache path."""
    cache = "%s/%s.bin" % (OUT, corpus)
    a = args.split()
    engine = [x for x in a if x.startswith("--")]
    rest = [x for x in a if not x.startswith("--")]
    tail = " ".join(engine + [rest[0], cache] + rest[1:])
    p = " ".join(props)
    if rt == "jvm":
        main = "rlvm.spike.machine.truffle.MainT"
        return "%s %s %s %s" % (JVM, p, main, tail)
    if plain_mode is not None:
        return "%s/ni/machine-%s-m%d %s %s" % (OUT, rt[1:], plain_mode, p, tail)
    return "%s/ni/machine-%s %s %s" % (OUT, rt[1:], p, tail)


ONLY = sys.argv[1:]


def write(name, lines):
    if ONLY and name not in ONLY:
        return
    os.makedirs(OUT + "/batches", exist_ok=True)
    with open("%s/batches/%s.batch" % (OUT, name), "w") as f:
        for label, c in lines:
            f.write("%s\t%s\n" % (label, c))
    print("%s: %d runs" % (name, len(lines)))


# Arena pre-sized for every experiment-1 run on Init (4.2 M values, 12.9 M words): no growth in any variant.
SIZE = {"init": ["-Drlvm.tbits=24", "-Drlvm.hcap=14000000"], "std": ["-Drlvm.tbits=25", "-Drlvm.hcap=30000000"]}
REPS = {"jvm": 3, "nce": 2, "nee": 2}


def exp1(rt, corpus="init", thresholds=("1000", "10000", "100000", "1000000"), inl=("2", "0"), short=False):
    L = []
    S = SIZE[corpus]
    for r in range(1, REPS[rt] + 1):
        def add(label, props, args):
            L.append(("%s.%s.%d" % (rt, label, r), cmd(rt, S + props, args, corpus)))
        add("none", [], "exp1 none")
        add("null", [], "exp1 null")
        add("a0", [], "exp1 a0")
        add("a1", [], "exp1 a1")
        add("elab", [], "exp1 elab")
        add("b", [], "exp1 b")
        if not short:
            add("brec", [], "exp1 brec")
            add("bm", [], "exp1 bm")
            add("a1.p3", ["-Drlvm.passes=3"], "exp1 a1")
            add("b.p3", ["-Drlvm.passes=3"], "exp1 b")
        add("c.never", ["-Drlvm.threshold=never"], "exp1 c")
        for t in thresholds:
            for i in inl:
                add("c.T%s.i%s" % (t, i), ["-Drlvm.threshold=" + t, "-Drlvm.inline=" + i], "exp1 c")
                add("cc.T%s.i%s" % (t, i), ["-Drlvm.threshold=" + t, "-Drlvm.inline=" + i, "-Drlvm.use=false"], "exp1 c")
    return L


def hot(rt):
    """Only the spines of the 184 constants with heat >= 1000; promotion at heat 100, synchronous compilation."""
    L = []
    S = SIZE["init"] + ["-Drlvm.minheat=1000"]
    C = ["-Drlvm.threshold=100", "-Drlvm.bg=false"]
    for r in range(1, REPS[rt] + 1):
        def add(label, props, args):
            L.append(("%s.hot.%s.%d" % (rt, label, r), cmd(rt, S + props, args)))
        add("null", [], "exp1 null")
        add("a1.p1", [], "exp1 a1")
        add("a1.p3", ["-Drlvm.passes=3"], "exp1 a1")
        add("b.p1", [], "exp1 b")
        add("b.p3", ["-Drlvm.passes=3"], "exp1 b")
        for i in ("0", "1", "2"):
            add("c.i%s.p1" % i, C + ["-Drlvm.inline=" + i], "exp1 c")
            add("c.i%s.p3" % i, C + ["-Drlvm.inline=" + i, "-Drlvm.passes=3"], "exp1 c")
            add("cc.i%s.p1" % i, C + ["-Drlvm.inline=" + i, "-Drlvm.use=false"], "exp1 c")
        add("c.i2.call1.p1", C + ["-Drlvm.inline=2", "-Drlvm.call=1"], "exp1 c")
        add("c.i2.call1.p3", C + ["-Drlvm.inline=2", "-Drlvm.call=1", "-Drlvm.passes=3"], "exp1 c")
        add("c.i2.tier1.p1", C + ["-Drlvm.inline=2", "-Drlvm.tier=first"], "exp1 c")
        add("c.i2.tier1.p3", C + ["-Drlvm.inline=2", "-Drlvm.tier=first", "-Drlvm.passes=3"], "exp1 c")
        add("cc.i2.tier1.p1", C + ["-Drlvm.inline=2", "-Drlvm.tier=first", "-Drlvm.use=false"], "exp1 c")
    return L


def exp2(rt):
    """Experiment 2 runs on the final build: frozen2 jar, machine-ce-fin / machine-ee-fin."""
    L = []
    cache = OUT + "/init.bin"
    exe = {"jvm": JVM.replace("frozen", "frozen2") + " %s rlvm.spike.machine.Main", "nce": OUT + "/ni/machine-ce-fin %s", "nee": OUT + "/ni/machine-ee-fin %s"}[rt]
    for r in range(1, REPS[rt] + 1):
        for intern in ("true", "false"):
            for v in ("none", "rec", "wl", "wl2"):
                L.append(("%s.exp2.%s.intern-%s.%d" % (rt, v, intern, r), "%s exp2 %s %s" % (exe % ("-Drlvm.intern=" + intern), cache, v)))
    return L


def exp3():
    L = []
    S = SIZE["init"]
    for r in (1, 2, 3):
        for m in range(4):
            L.append(("jvm.mem%d.none.%d" % (m, r), cmd("jvm", S + ["-Drlvm.mem=%d" % m], "exp1 none")))
            L.append(("jvm.mem%d.b.%d" % (m, r), cmd("jvm", S + ["-Drlvm.mem=%d" % m], "exp1 b")))
            L.append(("jvm.mem%d.a1.%d" % (m, r), cmd("jvm", S + ["-Drlvm.mem=%d" % m], "exp1 a1")))
    for r in (1, 2):
        for m in range(4):
            L.append(("nce.mem%d.none.%d" % (m, r), cmd("nce", S, "exp1 none", plain_mode=m)))
            L.append(("nce.mem%d.b.%d" % (m, r), cmd("nce", S, "exp1 b", plain_mode=m)))
            L.append(("nce.mem%d.a1.%d" % (m, r), cmd("nce", S, "exp1 a1", plain_mode=m)))
        L.append(("nee.mem0.none.%d" % r, cmd("nee", S, "exp1 none", plain_mode=0)))
        L.append(("nee.mem0.b.%d" % r, cmd("nee", S, "exp1 b", plain_mode=0)))
        L.append(("nee.mem0.a1.%d" % r, cmd("nee", S, "exp1 a1", plain_mode=0)))
    return L


def steady_jvm():
    """JVM mode needs long runs: steady state = (21 passes - 11 passes) / 10, far beyond JIT warm-up."""
    global JVM
    saved = JVM
    JVM = JVM.replace("frozen", "frozen2")  # the final jar (needed for the refined match mode and wl2)
    L = []
    S = SIZE["init"]
    H = S + ["-Drlvm.minheat=1000"]
    C = ["-Drlvm.threshold=100", "-Drlvm.bg=false"]
    for p in ("11", "21"):
        P = ["-Drlvm.passes=" + p]
        L.append(("jvm.steady.null.p%s.1" % p, cmd("jvm", S + P, "exp1 null")))
        L.append(("jvm.steady.a0.p%s.1" % p, cmd("jvm", S + P, "exp1 a0")))
        L.append(("jvm.steady.a1.p%s.1" % p, cmd("jvm", S + P, "exp1 a1")))
        L.append(("jvm.steady.b.p%s.1" % p, cmd("jvm", S + P, "exp1 b")))
        L.append(("jvm.steady.bm.p%s.1" % p, cmd("jvm", S + P, "exp1 bm")))
        L.append(("jvm.steady.hot.null.p%s.1" % p, cmd("jvm", H + P, "exp1 null")))
        L.append(("jvm.steady.hot.a1.p%s.1" % p, cmd("jvm", H + P, "exp1 a1")))
        L.append(("jvm.steady.hot.b.p%s.1" % p, cmd("jvm", H + P, "exp1 b")))
        for i in ("0", "1", "2"):
            L.append(("jvm.steady.hot.c.i%s.p%s.1" % (i, p), cmd("jvm", H + C + P + ["-Drlvm.inline=" + i], "exp1 c")))
        L.append(("jvm.steady.hot.c.i2.call1.p%s.1" % p, cmd("jvm", H + C + P + ["-Drlvm.inline=2", "-Drlvm.call=1"], "exp1 c")))
        for intern in ("true", "false"):
            for v in ("rec", "wl", "wl2"):
                L.append(("jvm.steady.exp2.%s.intern-%s.p%s.1" % (v, intern, p), cmd("jvm", P + ["-Drlvm.intern=" + intern], "exp2 " + v)))
        for m in range(4):
            L.append(("jvm.steady.mem%d.b.p%s.1" % (m, p), cmd("jvm", S + P + ["-Drlvm.mem=%d" % m], "exp1 b")))
    JVM = saved
    return L


def final_images():
    """Plain images built from the final sources (match mode refined, calibration tools), plus -O3 and PGO."""
    L = []
    S = SIZE["init"]
    imgs = (("ce-fin", "nce.fin"), ("ee-fin", "nee.fin"), ("ce-m0-O3", "nce.O3"), ("ee-m0-pgo", "nee.pgo"))
    for r in (1, 2):
        for img, tag in imgs:
            exe = "%s/ni/machine-%s " % (OUT, img) + " ".join(S)
            cache = OUT + "/init.bin"
            for v in ("none", "null", "a1", "b") + (("b2", "brec", "bm") if "fin" in img else ()):
                L.append(("%s.%s.%d" % (tag, v, r), "%s exp1 %s %s" % (exe, cache, v)))
            for v in ("a1", "b") + (("b2",) if "fin" in img else ()):
                L.append(("%s.%s.p3.%d" % (tag, v, r), "%s -Drlvm.passes=3 exp1 %s %s" % (exe, cache, v)))
    return L


def micro():
    L = []
    S = " ".join(SIZE["init"])
    cache = OUT + "/init.bin"
    jvm = JVM.replace("frozen", "frozen2") + " " + S + " rlvm.spike.machine.Main"
    for tag, exe, n in (("nce", "%s/ni/machine-ce-fin %s" % (OUT, S), 50000000), ("nee", "%s/ni/machine-ee-fin %s" % (OUT, S), 50000000), ("jvm", jvm, 400000000)):
        for w in ("loop", "app-hit", "clo2-hit", "app-hit-local"):
            for k in (1, 2):
                L.append(("%s.micro.%s.n%d.1" % (tag, w, k), "%s micro %s %s %d" % (exe, cache, w, n * k)))
        for k in (1, 2):
            L.append(("%s.micro.app-insert.n%d.1" % (tag, k), "%s micro %s app-insert %d" % (exe, cache, 2000000 * k)))
    return L


def long_jvm():
    """Longer JVM runs (21, 41, 81 passes): several independent steady-state estimates per variant."""
    L = []
    jvm = JVM.replace("frozen", "frozen2") + " " + " ".join(SIZE["init"])
    cache = OUT + "/init.bin"
    for v, reps in (("null", 1), ("a1", 1), ("b", 3), ("bm", 1), ("b2", 2)):
        for r in range(1, reps + 1):
            for p in (21, 41) + ((81,) if r == 1 else ()):
                L.append(("jvm.long.%s.p%d.%d" % (v, p, r), "%s -Drlvm.passes=%d rlvm.spike.machine.truffle.MainT exp1 %s %s" % (jvm, p, cache, v)))
    for p in (21, 41):
        L.append(("jvm.long.mem1.b.p%d.1" % p, "%s -Drlvm.mem=1 -Drlvm.passes=%d rlvm.spike.machine.truffle.MainT exp1 %s b" % (jvm, p, cache)))
    return L


def jvm_c2():
    """JVM mode as recommended by docs/research/10: C2 as host JIT, no tiered compilation (Truffle keeps libgraal)."""
    L = []
    jvm = JVM.replace("frozen", "frozen2").replace("-XX:+UseSerialGC", "-XX:+UseSerialGC -XX:-UseJVMCICompiler -XX:-TieredCompilation") + " " + " ".join(SIZE["init"])
    cache = OUT + "/init.bin"
    main = "rlvm.spike.machine.truffle.MainT"
    for r in (1, 2, 3):
        for v in ("none", "null", "a0", "a1", "elab", "b", "b2"):
            L.append(("jc2.%s.%d" % (v, r), "%s %s exp1 %s %s" % (jvm, main, cache, v)))
        L.append(("jc2.c.never.%d" % r, "%s -Drlvm.threshold=never %s exp1 %s c" % (jvm, main, cache)))
        for t in ("10000", "100000"):
            for i in ("0", "2"):
                L.append(("jc2.c.T%s.i%s.%d" % (t, i, r), "%s -Drlvm.threshold=%s -Drlvm.inline=%s %s exp1 %s c" % (jvm, t, i, main, cache)))
    for v, reps in (("null", 1), ("a1", 2), ("b", 2), ("b2", 2), ("bm", 1)):
        for r in range(1, reps + 1):
            for p in (21, 41):
                L.append(("jc2.long.%s.p%d.%d" % (v, p, r), "%s -Drlvm.passes=%d %s exp1 %s %s" % (jvm, p, main, cache, v)))
    for intern in ("true", "false"):
        for v in ("rec", "wl", "wl2"):
            for p in (11, 21):
                L.append(("jc2.long.exp2.%s.intern-%s.p%d.1" % (v, intern, p), "%s -Drlvm.intern=%s -Drlvm.passes=%d %s exp2 %s %s" % (jvm, intern, p, main, cache, v)))
    return L


write("jvm-c2", jvm_c2())
write("steady-jvm", steady_jvm())
write("long-jvm", long_jvm())
write("final-images", final_images())
write("micro", micro())
write("exp1-jvm", exp1("jvm"))
write("exp1-nce", exp1("nce"))
write("exp1-nee", exp1("nee"))
write("hot-jvm", hot("jvm"))
write("hot-nce", hot("nce"))
write("hot-nee", hot("nee"))
write("exp2", exp2("nce") + exp2("nee") + exp2("jvm"))
write("exp3", exp3())
write("exp4-std-nce", exp1("nce", corpus="std", short=True))
write("exp4-std-jvm", exp1("jvm", corpus="std", short=True, thresholds=("1000", "10000"), inl=("0",)))
