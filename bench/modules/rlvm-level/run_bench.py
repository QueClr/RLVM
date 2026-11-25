#!/usr/bin/env python3
"""Instruction counts per level operation, measured in the arena container.

    . ./env.sh && $MVN -o -q -pl rlvm-level test-compile        # classes must exist
    bench/modules/rlvm-level/run_bench.py [--only GLOB] [--json FILE] [--overlay shared|flat]

For every scenario of rlvm.level.LevelBench two JVM runs are measured with bench/measure.sh inside
`bench/arena-docker.sh --arena-limits` (8 CPUs, 16 GB, default JVM flags), with N1 and N2 iterations:

    per-iteration instructions = (instr(N2) - instr(N1)) / (N2 - N1)

JVM start-up, set-up and JIT compilation are equal in both runs and cancel. "over ref" subtracts the
same quantity of a reference scenario. For most scenarios that is `baseline`: the same loop with its
two array loads, which ADDS the two loaded ids into the accumulator where a scenario calls the
operation. So "over ref" is the cost of the operation beyond one integer add (about 2 instructions);
a value near 0 means "as cheap as that add", not "free". The cold-* scenarios are differences to the
scenario that does the same work without the measured call.

Repeatability (JVM noise of about 1e8 instructions per run, divided by N2 - N1): about +-0.1
instructions per iteration for the cheap group, +-0.3 for the medium group, +-1.5 for the cold group. All user-space threads are counted (JIT and GC included), as on the
arena. Takes about four minutes; containers are run one at a time and removed when they exit.
"""
import argparse
import fnmatch
import json
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
CP = "rlvm-level/target/classes:rlvm-level/target/test-classes"

CHEAP = (500_000_000, 2_500_000_000)
MEDIUM = (100_000_000, 500_000_000)
COLD = (20_000_000, 100_000_000)

# name, iteration counts, what is measured, scenario to subtract ("baseline" = loop only)
SCENARIOS = [
    ("baseline", CHEAP, "loop only: two array loads and an add", None),
    ("equiv-same", CHEAP, "isEquiv(l, l): identical ids", "baseline"),
    ("equiv-canon", CHEAP, "isEquiv, different ids, same class (canonical forms memoised)", "baseline"),
    ("equiv-diff", CHEAP, "isEquiv, different classes (canonical forms memoised)", "baseline"),
    ("equiv-official-memo", CHEAP, "isEquivOfficial, different ids (normal forms memoised)", "baseline"),
    ("normalize-memo", CHEAP, "normalize, memo hit", "baseline"),
    ("canon-memo", CHEAP, "canonId, memo hit", "baseline"),
    ("inst-ident", CHEAP, "instantiate(l, identity substitution)", "baseline"),
    ("inst-noparam", CHEAP, "instantiate(closed level, substitution)", "baseline"),
    ("inst-hit", CHEAP, "instantiate(l, substitution): cache hit", "baseline"),
    ("instlist-hit", CHEAP, "instantiateList(list, substitution): cache hit", "baseline"),
    ("params-declared", CHEAP, "undeclaredParam(l, names): memo hit", "baseline"),
    ("mkmax", MEDIUM, "mkMax(a+1, b): result exists (hash-cons hit)", "baseline"),
    ("mkimax", MEDIUM, "mkIMax(a, b+1) = mkMax: result exists (hash-cons hit)", "baseline"),
    ("geq", MEDIUM, "isGeq((max a b)+1, max (a+1) b): compares two stored canonical forms", "baseline"),
    ("cold-intern", COLD, "build a new small level in the overlay (1-2 new nodes), reset every 16", "baseline"),
    ("cold-normalize", COLD, "normalize of a new small level", "cold-intern"),
    ("cold-canon", COLD, "canonId of a new small level", "cold-intern"),
    ("cold-equiv", COLD, "isEquiv of two new small levels (one more node interned, two canonical forms)", "cold-intern"),
    ("cold-subst", COLD, "intern a new 3-level list and substitution, reset every 16", "baseline"),
    ("cold-instantiate", COLD, "two instantiate cache misses (max (u+1) v, imax u w; results new)", "cold-subst"),
]


OVERLAY = "shared"


def measure(scenario, n):
    cmd = [os.path.join(ROOT, "bench/arena-docker.sh"), "--arena-limits", "bench/measure.sh", "--",
           "java", "-Drlvm.level.overlay=" + OVERLAY, "-cp", CP, "rlvm.level.LevelBench", scenario, str(n)]
    p = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True)
    for line in p.stdout.splitlines():
        line = line.strip()
        if line.startswith('{"exit_code"'):
            d = json.loads(line)
            if d["exit_code"] != 0 or d.get("instructions") is None:
                raise RuntimeError("measurement of %s failed: %s\n%s" % (scenario, line, p.stderr[-2000:]))
            if d.get("counter_coverage", 1.0) != 1.0:
                raise RuntimeError("counters were multiplexed for %s: %s" % (scenario, line))
            return d
    raise RuntimeError("no measurement for %s:\n%s\n%s" % (scenario, p.stdout[-2000:], p.stderr[-2000:]))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--only", default="*", help="glob over scenario names (baseline and references are added)")
    ap.add_argument("--json", help="write the raw measurements to this file")
    ap.add_argument("--overlay", choices=("shared", "flat"), default="shared",
                    help="the overlay the scenarios work on: shared (a worker's, the default) or flat (the private copy)")
    a = ap.parse_args()
    global OVERLAY
    OVERLAY = a.overlay
    if not os.path.isdir(os.path.join(ROOT, "rlvm-level/target/test-classes/rlvm/level")):
        sys.exit("build first:  . ./env.sh && $MVN -o -q -pl rlvm-level test-compile")
    wanted = set()
    by_name = {s[0]: s for s in SCENARIOS}
    for name, _, _, ref in SCENARIOS:
        if fnmatch.fnmatch(name, a.only):
            wanted.add(name)
            while ref:
                wanted.add(ref)
                ref = by_name[ref][3]
    per = {}
    raw = {}
    for name, (n1, n2), _, _ in SCENARIOS:
        if name not in wanted:
            continue
        d1 = measure(name, n1)
        d2 = measure(name, n2)
        per[name] = (d2["instructions"] - d1["instructions"]) / (n2 - n1)
        raw[name] = {"n1": n1, "n2": n2, "run1": d1, "run2": d2, "per_iteration": per[name]}
        print("  measured %-20s %8.2f instr/iteration  (N2 run: %.2f s wall, %.2f s cpu, %.0f MB rss)"
              % (name, per[name], d2["wall_time_s"], d2["cpu_time_s"], d2["max_rss_bytes"] / 1e6), flush=True)
    # repeat the baseline pair: how far two measurements of the same thing are apart
    n1, n2 = CHEAP
    again = (measure("baseline", n2)["instructions"] - measure("baseline", n1)["instructions"]) / (n2 - n1)
    raw["baseline-repeat"] = {"per_iteration": again}
    print()
    print("%-20s %12s %10s   %s" % ("scenario", "instr/iter", "over ref", "what"))
    for name, _, what, ref in SCENARIOS:
        if name not in per:
            continue
        net = "" if ref is None else "%.2f" % (per[name] - per[ref])
        ref_txt = "" if ref in (None, "baseline") else "  [minus %s]" % ref
        print("%-20s %12.2f %10s   %s%s" % (name, per[name], net, what, ref_txt))
    print("baseline measured twice: %.3f and %.3f instr/iteration" % (per["baseline"], again))
    print("over ref: minus `baseline` (whose loop body adds the two ids: about 2 instructions that the operation"
          " replaces) unless another reference is named")
    if a.json:
        with open(a.json, "w") as f:
            json.dump(raw, f, indent=1)


if __name__ == "__main__":
    main()
