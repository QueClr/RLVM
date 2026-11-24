"""Builds results/tables.md from the formatted batch logs (results/*.txt), results/stats.txt and results/verify.txt.

CPU time. The host is shared with other jobs: the same pass of the same binary varies by 10-20% from minute to
minute, more than the differences under study. The CPU-time tables therefore come from the "duel" runs, in which
all variants take turns pass by pass inside one process (Main.duel), and use two estimators:
  min     minimum CPU time of a pass over all rounds (disturbance only ever adds time); ratio = min(variant) / min(R)
  paired  median over the rounds of  pass(variant, round) / pass(R, round)   (both passes are seconds apart)
In JVM mode the first rounds, which contain the interpreter and the JIT, are left out of both.
Pass CPU is the CPU time of the worker thread (/proc/thread-self/schedstat).

Process tables: whole processes measured like the arena does (bench/measure.sh: perf stat on the process tree,
GNU time): CPU of all threads (JIT compiler threads included), wall clock, peak RSS, user-space instructions,
net of the set-up-only run ("none") and divided by the number of passes where stated. One or two processes per
variant: the CPU and wall columns carry the 10-20% noise, the instruction counts do not (native) .
"""
import glob
import math
import os
import re
import statistics

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "results")

RTS = ["nce", "nfi", "jvm", "jfi", "c2", "cfi"]
RT_NAME = {
    "nce": "native image, GraalVM CE 25 (-O2, serial GC), default inlining",
    "nfi": "native image, GraalVM CE 25, continuation steps of H force-inlined",
    "jvm": "JVM, Graal JIT (default of the GraalVM CE 25 JDK), SerialGC",
    "jfi": "JVM, Graal JIT, continuation steps of H force-inlined (-XX:CompileCommandFile)",
    "c2": "JVM, C2 (-XX:-UseJVMCICompiler), SerialGC",
    "cfi": "JVM, C2, continuation steps of H force-inlined (-XX:CompileCommandFile)",
}
RT_SHORT = {"nce": "native CE", "nfi": "native CE, forced inlining", "jvm": "JVM Graal JIT", "jfi": "JVM Graal JIT, forced inlining", "c2": "JVM C2", "cfi": "JVM C2, forced inlining"}
ORDER = ["R", "Ri", "W0", "W1n", "W1", "H0", "H8", "H32", "H64", "H256", "Hinf", "Hi64", "HT64"]
WHAT = {
    "R": "plain host recursion (the baseline)",
    "Ri": "R with inert application nests evaluated by the scratch-buffer loop of W1",
    "W0": "ISA 1.3 as written: run() -> X.step(m) -> switch on st, registers in machine fields, header + 5 payload words",
    "W1n": "W1 without the inert-nest loop",
    "W1": "fused loop per family, W / wp / A in locals, dense dispatch, same-family push = continue, inert nests in one loop",
    "H0": "H with D = 0: every non-leaf sub-judgment becomes a frame (the specification mode)",
    "H8": "bounded host recursion, D = 8",
    "H32": "bounded host recursion, D = 32",
    "H64": "bounded host recursion, D = 64",
    "H256": "bounded host recursion, D = 256",
    "Hinf": "H that never materialises (cost of the resumable form alone)",
    "Hi64": "H64 with the inert-nest loop",
    "HT64": "H64 charging one tick per judgment (budgets, TRY)",
}
WORK = {"types": "declaration types", "all": "declaration types and definition values"}


def load():
    runs = {}
    for path in sorted(glob.glob(os.path.join(RES, "*.txt"))):
        for line in open(path):
            m = re.match(r"(\S+)\s+\| cpu\s+([\d.]+) s \| wall\s+([\d.]+) s \| rss\s+(\d+) MB \| instr\s+(\S+) G \| exit (\d+) \| (.*)$", line)
            if not m:
                continue
            label = m.group(1).split(".")
            if len(label) != 5:
                continue
            batch, rt, corpus, variant, _rep = label
            rest = m.group(7)
            d = {"cpu": float(m.group(2)), "wall": float(m.group(3)), "rss": int(m.group(4)), "instr": None if m.group(5) == "n/a" else float(m.group(5)), "exit": int(m.group(6)),
                 "rest": rest}
            pm = re.search(r"pass_cpu_ms=\[([\d,]+)\]", rest)
            if pm:
                d["pass"] = [int(x) for x in pm.group(1).split(",")]
            for key in ("checksum", "fails"):
                km = re.search(key + r"=(\w+)", rest)
                if km:
                    d[key] = km.group(1)
            runs.setdefault((batch, rt, corpus, variant), []).append(d)
    return runs


def duel_of(runs, roots, rt, corpus):
    """list of duel processes, each a dict variant -> pass CPU times in ms per round; and the set of checksums"""
    rs = runs.get(("duel" + roots, rt, corpus, "all"))
    if not rs:
        return None, None
    out = []
    cs = set()
    for r in rs:
        d = {}
        for m in re.finditer(r"DUEL variant=(\S+) corpus=\S+ roots=(\d+) rounds=\d+ checksum=(\w+) cpu_us=\[([\d,]+)\]", r["rest"]):
            d[m.group(1)] = [int(x) / 1000.0 for x in m.group(4).split(",")]
            cs.add(m.group(3))
            d["#roots"] = m.group(2)
        if "R" in d:
            out.append(d)
    return (out or None), cs


def estimators(procs, rt):
    """variant -> (min ms, ratio of minima, median paired ratio, rounds used, median ms), pooled over the duel processes"""
    if not procs:
        return None
    res = {}
    variants = []
    for d in procs:
        for v in d:
            if v != "#roots" and v not in variants:
                variants.append(v)
    for v in variants:
        tv, tr = [], []
        for d in procs:
            if v not in d:
                continue
            rounds = len(d["R"])
            skip = 0
            if rt in ("jvm", "c2", "jfi", "cfi"):
                skip = 2 if rounds >= 8 else 1
            tv += d[v][skip:]
            tr += d["R"][skip:]
        paired = statistics.median(a / b for a, b in zip(tv, tr))
        res[v] = (min(tv), min(tv) / min(tr), paired, len(tv), statistics.median(tv))
    return res


def geomean(xs):
    xs = [x for x in xs if x]
    return math.exp(sum(math.log(x) for x in xs) / len(xs)) if xs else None


def main():
    runs = load()
    print("# Stepper-convention spike: result tables\n")
    print("Generated by `scripts/tables.py` from `results/*.txt`; the estimators are described at the head of that script")
    print("and in README.md. CPU time comes first in every table; every ratio is against R of the same runtime, corpus")
    print("and workload. Host: aarch64 (Cortex-X925 performance cores), Docker with the arena limits (8 cores, 16 GB).\n")
    print("| variant | what it is |\n|---|---|")
    for v in ORDER:
        print("| %s | %s |" % (v, WHAT[v]))

    # ------------------------------------------------------------------ CPU time from the duels
    est = {}  # (roots, corpus, rt) -> estimators
    checks = []
    for roots in ("types", "all"):
        for corpus in ("init", "cedar"):
            for rt in RTS:
                d, cs = duel_of(runs, roots, rt, corpus)
                e = estimators(d, rt)
                if e:
                    est[(roots, corpus, rt)] = e
                    checks.append((roots, corpus, rt, len(cs) == 1, d[0]["#roots"], e["R"][3]))

    print("\n## 1. CPU time relative to R (summary)\n")
    print("Geometric mean over the four workloads (Init and Cedar, types only and types + values; 16 MiB sessions) of")
    print("min(variant) / min(R); in brackets the same mean of the median paired ratio. Target of RECONCILIATION B4: at most 1.15.\n")
    present = [rt for rt in RTS if any(k[2] == rt for k in est)]
    print("| variant | " + " | ".join(RT_SHORT[rt] for rt in present) + " |")
    print("|---|" + "--:|" * len(present))
    for v in ORDER:
        cells = []
        for rt in present:
            a = [est[k][v][1] for k in est if k[2] == rt and v in est[k]]
            b = [est[k][v][2] for k in est if k[2] == rt and v in est[k]]
            cells.append("-" if not a else "%.3f (%.3f)" % (geomean(a), geomean(b)))
        if any(c != "-" for c in cells):
            print("| %s | " % v + " | ".join(cells) + " |")

    print("\n## 2. CPU time per workload (duels)\n")
    print("Per runtime: minimum CPU time of one pass in ms, min(variant) / min(R), and the median over the rounds of the")
    print("paired ratio pass(variant) / pass(R).\n")
    for roots in ("types", "all"):
        for corpus in ("init", "cedar"):
            have = [rt for rt in RTS if (roots, corpus, rt) in est]
            if not have:
                continue
            info = [c for c in checks if c[0] == roots and c[1] == corpus]
            print("\n### %s: %s (%s roots, 16 MiB sessions)\n" % (corpus, WORK[roots], info[0][4]))
            print("| variant | " + " | ".join("%s: min ms | ratio of minima | paired median" % RT_SHORT[rt] for rt in have) + " |")
            print("|---|" + "--:|--:|--:|" * len(have))
            for v in ORDER:
                cells = []
                for rt in have:
                    e = est[(roots, corpus, rt)].get(v)
                    cells.append("- | - | -" if not e else "%.0f | %.3f | %.3f" % (e[0], e[1], e[2]))
                if any(not c.startswith("-") for c in cells):
                    print("| %s | " % v + " | ".join(cells) + " |")
            print("\nRounds used: " + ", ".join("%s %d" % (RT_SHORT[c[2]], c[5]) for c in info) + ". Result checksums identical for all variants: "
                  + ", ".join("%s %s" % (RT_SHORT[c[2]], "yes" if c[3] else "NO") for c in info) + ".")

    # ------------------------------------------------------------------ whole processes
    print("\n## 3. Whole processes (CPU of all threads, wall clock, peak RSS, instructions)\n")
    print("One process per variant, measured with `bench/measure.sh`.")
    print("\"per pass\" = (process - set-up-only process) / passes. CPU and wall clock of single processes carry the host's")
    print("10-20% noise; use section 2 for CPU ratios. In JVM mode the process CPU includes the JIT compiler threads.\n")
    for batch, corpus, title in (("all", "init", "Init, types and values, 2 passes"), ("types", "init", "Init, types only, 10 passes"),
                                 ("all", "cedar", "Cedar, types and values, 1 pass"), ("whole", "init", "Init, types and values, one arena for the whole pass (no session reset), 1 pass")):
        for rt in RTS:
            none = runs.get((batch, rt, corpus, "none"))
            if not runs.get((batch, rt, corpus, "R")):
                continue
            none_cpu = min(r["cpu"] for r in none) if none else 0.0
            none_ins = min(r["instr"] for r in none if r["instr"] is not None) if none else 0.0
            rows = {}
            for v in ORDER:
                rs = [r for r in runs.get((batch, rt, corpus, v), []) if r["exit"] == 0 and "pass" in r]
                if not rs:
                    continue
                passes = len(rs[0]["pass"])
                ins = [r["instr"] for r in rs if r["instr"] is not None]
                rows[v] = {
                    "cpu": (min(r["cpu"] for r in rs) - none_cpu) / passes, "wall": min(r["wall"] for r in rs), "rss": max(r["rss"] for r in rs),
                    "ins": (min(ins) - none_ins) / passes if ins else None, "first": min(r["pass"][0] for r in rs), "last": min(r["pass"][-1] for r in rs),
                    "cs": {r.get("checksum") for r in rs}, "fails": {r.get("fails") for r in rs},
                }
            base = rows["R"]
            print("\n#### %s - %s\n" % (title, RT_NAME[rt]))
            print("| variant | process CPU per pass (s) | vs R | process wall (s) | peak RSS (MB) | instructions per pass (G) | vs R | first pass, worker CPU (ms) | last pass, worker CPU (ms) |")
            print("|---|--:|--:|--:|--:|--:|--:|--:|--:|")
            for v in ORDER:
                if v not in rows:
                    continue
                r = rows[v]
                print("| %s | %.3f | %.3f | %.2f | %d | %s | %s | %d | %d |" % (
                    v, r["cpu"], r["cpu"] / base["cpu"], r["wall"], r["rss"], "-" if r["ins"] is None else "%.3f" % r["ins"],
                    "-" if r["ins"] is None or not base["ins"] else "%.3f" % (r["ins"] / base["ins"]), r["first"], r["last"]))
            cs = set()
            fails = set()
            for v in rows:
                cs |= rows[v]["cs"]
                fails |= rows[v]["fails"]
            print("\nSet-up-only process: %.2f s CPU, %.3f G instructions. Result checksum %s; failed conversions: %s." % (
                none_cpu, none_ins or 0.0, "identical for all variants" if len(cs) == 1 else "DIFFERENT %s" % cs, ", ".join(sorted(str(f) for f in fails))))

    # ------------------------------------------------------------------ frames
    path = os.path.join(RES, "stats.txt")
    if os.path.exists(path):
        print("\n## 4. Frames\n")
        print("Counters of one pass (JVM, `-Drlvm.stats=true`, 16 MiB sessions; `scripts/stats.sh`). Judgments = term nodes")
        print("evaluated + values read back + value pairs compared, counted under R; every variant performs exactly these,")
        print("and its hash-consing probes are identical to R's (last column). A frame is anything written to the worklist W;")
        print("\"fresh\" frames carry a judgment that has not started, the others the continuation of a suspended one.\n")
        stats = {}
        for line in open(path):
            m = re.match(r"(all|types) STATS variant=(\S+) corpus=(\S+) (.*)$", line)
            if m:
                stats[(m.group(3), m.group(1), m.group(2))] = {k: int(x) for k, x in re.findall(r"(\w+)=(\d+)", m.group(4))}
        for corpus in ("init", "cedar"):
            for roots in ("all", "types"):
                base = stats.get((corpus, roots, "R"))
                if not base:
                    continue
                judg = base["evals"] + base["readbacks"] + base["convs"]
                print("\n#### %s, %s: %d judgments = %d term nodes evaluated + %d values read back + %d pairs compared; %d value probes, %d term probes\n" % (
                    corpus, WORK[roots], judg, base["evals"], base["readbacks"], base["convs"], base["value_probes"], base["term_probes"]))
                print("| variant | frames | frames per term node evaluated | frames per judgment | fresh frames | words per frame | materialisations | term nodes in inert-nest loops | probes identical to R |")
                print("|---|--:|--:|--:|--:|--:|--:|--:|---|")
                for v in ["R", "Ri", "W0", "W1n", "W1", "H0", "H4", "H8", "H32", "H64", "H256", "Hinf", "Hi64"]:
                    d = stats.get((corpus, roots, v))
                    if not d:
                        continue
                    same = d["value_probes"] == base["value_probes"] and d["term_probes"] == base["term_probes"]
                    print("| %s | %d | %.5f | %.5f | %d | %s | %s | %d | %s |" % (
                        v, d["frames"], d["frames"] / base["evals"], d["frames"] / judg, d["fresh_frames"], "-" if d["frames"] == 0 else "%.1f" % (d["frame_words"] / d["frames"]),
                        d["spills"] if v.startswith("H") else "-", d["inert_nodes"], "yes" if same else "NO"))

    # ------------------------------------------------------------------ deep terms
    deep = {}
    for (batch, rt, corpus, variant), rs in runs.items():
        if batch == "deep":
            deep[(rt, variant)] = rs[0]
    if deep:
        print("\n## 5. Deep terms: 1,000,000-deep nests\n")
        print("`deep VARIANT 1000000` (Deep.java): five synthetic closed terms through the same four judgments. R runs on a")
        print("4 GiB thread stack (and once on 512 KiB, to show what plain recursion does there); every other variant runs on")
        print("a 512 KiB stack. Milliseconds are wall clock of the worker for one term, cold (first execution in the process).\n")
        terms = ["app-arg", "app-fn", "lam", "nest", "inert"]
        for rt in RTS:
            if not any(k[0] == rt for k in deep):
                continue
            print("\n#### %s\n" % RT_NAME[rt])
            print("| variant | thread stack | " + " | ".join("%s (ms)" % t for t in terms) + " | process CPU (s) | peak RSS (MB) | worklist capacity at the end (words) | results equal to R |")
            print("|---|---|" + "--:|" * (len(terms) + 3) + "---|")
            ref = None
            for v in ["R", "R-512k", "W0", "W1n", "W1", "H0", "H32", "H64", "H256", "Hinf", "Hi64", "HT64"]:
                r = deep.get((rt, v))
                if not r:
                    continue
                res = {}
                for m in re.finditer(r"term=(\S+) (STACKOVERFLOW|nf=\d+ w=\d+ ok=\w+) ms=(\d+) values=\d+ nf_terms=\d+ worklist_words=(\d+)", r["rest"]):
                    res[m.group(1)] = (m.group(2), int(m.group(3)), int(m.group(4)))
                if v == "R":
                    ref = {t: res[t][0] for t in res}
                cells = []
                for t in terms:
                    if t not in res:
                        cells.append("not run")
                    elif res[t][0] == "STACKOVERFLOW":
                        cells.append("StackOverflowError")
                    else:
                        cells.append(str(res[t][1]))
                ww = max((x[2] for x in res.values()), default=0)
                eq = "-" if v == "R" else ("yes" if ref and all(t in res and res[t][0] == ref.get(t) and "ok=true" in res[t][0] for t in terms) else "no")
                print("| %s | %s | %s | %.2f | %d | %d | %s |" % (v, "4 GiB" if v == "R" else "512 KiB", " | ".join(cells), r["cpu"], r["rss"], ww, eq))

    # ------------------------------------------------------------------ verification
    path = os.path.join(RES, "verify.txt")
    if os.path.exists(path):
        print("\n## 6. Verification\n")
        print("`scripts/verify.sh`: R and every variant on every root in one arena (identical normal form id, value id and")
        print("verdict required), then the TRY experiment: CONV of every Init root under a budget of ticks, with D = 0 (every")
        print("frame materialised, W cut to the TRY frame), D = 64 and D = infinity (host unwinding only).\n")
        print("```")
        for line in open(path):
            if line.startswith(("VERIFY", "TRY", "MISMATCH")):
                print(line.rstrip())
        print("```")


if __name__ == "__main__":
    main()
