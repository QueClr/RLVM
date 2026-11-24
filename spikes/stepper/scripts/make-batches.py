"""Writes the measurement batches into $OUT/batches (one container per batch, see pbatch.sh).

Label of a run:  <batch>.<runtime>.<corpus>.<variant>.<rep>
Runtimes:   nce = native image, GraalVM CE 25, default inlining
            nfi = native image, GraalVM CE 25, continuation steps of H force-inlined (scripts/build-images.sh)
            jvm = HotSpot with the Graal JIT (the default of the GraalVM JDK)
            c2  = HotSpot with C2 (-XX:-UseJVMCICompiler)
            jfi, cfi = jvm, c2 with -XX:CompileCommandFile=$OUT/inline.cmd (continuation steps of H inlined)
Batches:    duel-<rt>   CPU time: one process per corpus and workload in which the variants take turns pass by pass
            duel2-<rt>  a second series of duels for the decisive variants
            proc-<rt>   whole processes, one variant each: CPU of all threads (JIT included), wall, RSS, instructions
            deep-<rt>   1,000,000-deep synthetic terms
"""
import os

OUT = os.environ["OUT"]
JAVA = os.environ["JAVA_HOME"] + "/bin/java"
JAR = OUT + "/stepper.jar"
RT = {
    "nce": [OUT + "/ni/stepper-ce", "-Xmx12g"],
    "nfi": [OUT + "/ni/stepper-ce-fi", "-Xmx12g"],
    "jvm": [JAVA, "-XX:+UseSerialGC", "-Xmx12g", "-cp", JAR],
    "c2": [JAVA, "-XX:+UseSerialGC", "-XX:-UseJVMCICompiler", "-Xmx12g", "-cp", JAR],
    "jfi": [JAVA, "-XX:+UseSerialGC", "-XX:CompileCommandFile=" + OUT + "/inline.cmd", "-Xmx12g", "-cp", JAR],
    "cfi": [JAVA, "-XX:+UseSerialGC", "-XX:-UseJVMCICompiler", "-XX:CompileCommandFile=" + OUT + "/inline.cmd", "-Xmx12g", "-cp", JAR],
}
FORCED = ("nfi", "jfi", "cfi")
MAIN = "rlvm.spike.stepper.Main"
# a 16 MiB session (RECONCILIATION section 3): small tables that are refilled after every reset
SESSION = ["-Drlvm.session=16777216", "-Drlvm.tbits=20", "-Drlvm.hcap=4000000", "-Drlvm.ttbits=20", "-Drlvm.textra=1000000"]
# no reset: tables sized for the whole pass, so nothing grows inside the timed region
WHOLE = {
    "init": ["-Drlvm.tbits=24", "-Drlvm.hcap=20000000", "-Drlvm.ttbits=24", "-Drlvm.textra=5000000"],
    "cedar": ["-Drlvm.tbits=25", "-Drlvm.hcap=40000000", "-Drlvm.ttbits=25", "-Drlvm.textra=9000000"],
}
DUEL = ["R", "Ri", "W0", "W1n", "W1", "H8", "H32", "H64", "H256", "Hinf", "Hi64", "HT64"]
DUEL_FI = ["R", "W1", "H8", "H32", "H64", "H256", "Hinf", "Hi64", "HT64"]
PROC = ["none", "R", "Ri", "W0", "W1n", "W1", "H0", "H8", "H32", "H64", "H256", "Hinf", "Hi64", "HT64"]
PROC_FI = ["none", "R", "W1", "H0", "H32", "H64", "H256", "Hinf", "HT64"]
CORE = ["none", "R", "W0", "W1", "H64", "H256"]


def cmd(rt, props, args):
    base = RT[rt]
    return base + props + args if rt.startswith("n") else base + props + [MAIN] + args


def batch(name, runs):
    os.makedirs(OUT + "/batches", exist_ok=True)
    with open(OUT + "/batches/" + name + ".batch", "w") as f:
        for label, c in runs:
            f.write(label + "\t" + " ".join(c) + "\n")
    print("%-28s %4d runs" % (name, len(runs)))


def duel(rt, corpus, roots, rounds, variants, rep=1):
    props = SESSION + ["-Drlvm.roots=" + roots]
    return ("duel%s.%s.%s.all.%d" % (roots, rt, corpus, rep), cmd(rt, props, ["duel", "%s/%s.bin" % (OUT, corpus), ",".join(variants), str(rounds)]))


def proc(name, rt, corpus, variants, reps, passes, size, roots="all"):
    runs = []
    for rep in range(1, reps + 1):
        for v in variants:
            props = size + ["-Drlvm.passes=%d" % passes, "-Drlvm.roots=" + roots]
            runs.append(("%s.%s.%s.%s.%d" % (name, rt, corpus, v, rep), cmd(rt, props, ["run", "%s/%s.bin" % (OUT, corpus), v])))
    return runs


for rt in RT:
    fi = rt in FORCED
    dv = DUEL_FI if fi else DUEL
    # two processes per workload (the second with the variants in reverse order)
    batch("duel-%s" % rt, [
        duel(rt, "init", "types", 20, dv), duel(rt, "init", "all", 5, dv),
        duel(rt, "cedar", "types", 10, dv), duel(rt, "cedar", "all", 3, dv),
    ])
    # a second series for the decisive variants (more rounds on the acceptance workload, pooled by tables.py)
    key = ["R", "W0", "W1", "H64", "Hi64", "HT64"] if not fi else ["R", "W1", "H64", "Hi64", "HT64"]
    batch("duel2-%s" % rt, [duel(rt, "init", "types", 30, key, 2), duel(rt, "cedar", "types", 15, key, 2), duel(rt, "cedar", "all", 4, key, 2)])
    pv = PROC_FI if fi else PROC
    reps = 1
    batch("proc-%s" % rt,
          proc("all", rt, "init", pv, reps, 2, SESSION)
          + proc("types", rt, "init", [v for v in pv if v != "H0"], 1, 10, SESSION, "types")
          + proc("all", rt, "cedar", [v for v in pv if v != "H0"], 1, 1, SESSION)
          + ([] if fi else proc("whole", rt, "init", CORE, 1, 1, WHOLE["init"])))
    if not fi:
        # deep terms: 1,000,000-deep nests; R gets a 4 GiB stack, everything else 512 KiB
        runs = []
        for v in ["R", "W0", "W1", "W1n", "H0", "H32", "H64", "H256", "Hi64", "HT64", "Hinf"]:
            props = ["-Drlvm.stackmb=4096"] if v == "R" else ["-Drlvm.stackkb=512"]
            runs.append(("deep.%s.synthetic.%s.1" % (rt, v), cmd(rt, props, ["deep", v, "1000000"])))
        runs.append(("deep.%s.synthetic.R-512k.1" % rt, cmd(rt, ["-Drlvm.stackkb=512"], ["deep", "R", "1000000"])))
        batch("deep-%s" % rt, runs)
