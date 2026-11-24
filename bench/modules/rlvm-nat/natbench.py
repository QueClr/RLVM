#!/usr/bin/env python3
"""bench/modules/rlvm-nat/natbench.py - instruction counts per Nat operation.

Runs rlvm.nat.bench.NatBench (test tree of rlvm-nat) under bench/measure.py for each case with two
iteration counts and reports the slope: instructions, CPU nanoseconds and wall nanoseconds per
operation. JVM start-up, JIT compilation and an identical warm-up phase are in both runs and cancel.
With --repeat N the median slope of N pairs of runs is reported, and a spread above 5% is flagged
(the JIT does not always produce the same code). Must run where perf counters
work, i.e. inside the container:

    . ./env.sh && $MVN -o -q -pl rlvm-nat test-compile          # host: build the classes first
    bench/arena-docker.sh --arena-limits --no-network bench/modules/rlvm-nat/natbench.py \
        [--cases REGEX] [--repeat N] [--jvm "-XX:+UseSerialGC"] [--out work/rlvm-nat/natbench.json]

Each output row: case, iterations of the short run, instructions/op, CPU ns/op, wall ns/op, and for
the fast-path cases the same minus the `baseline` case (empty loop: two array loads and a checksum).
"""
import argparse, json, os, re, shlex, statistics, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "bench"))
from measure import measure  # noqa: E402

OPS_ALL = ["add", "sub", "mul", "div", "mod", "gcd", "land", "lor", "xor", "shl", "shr", "beq", "ble", "succ"]

def cases():
    out = [("baseline", 400_000_000)]
    for op in OPS_ALL + ["pow", "log2", "mulw"]:
        out.append((f"nat.{op}.small", 60_000_000 if op in ("gcd", "pow") else 400_000_000))
    out += [("nat.add.ovf", 20_000_000), ("big.add.ovf", 20_000_000), ("nat.mul.ovf", 20_000_000), ("big.mul.ovf", 20_000_000),
            ("nat.mod.u64", 20_000_000), ("big.mod.u64", 20_000_000), ("nat.div.u64", 20_000_000), ("big.div.u64", 20_000_000)]
    for op in ["add", "mul", "div", "mod", "gcd", "land"]:
        out.append((f"nat.{op}.256s", 10_000_000))
        out.append((f"big.{op}.256s", 5_000_000))
    for bits, n in [(256, 10_000_000), (4096, 1_000_000)]:
        for op in OPS_ALL + ["pow"]:
            k = n
            if op in ("mul", "div", "mod"):
                k = n // 4 if bits == 4096 else n // 2
            if op == "gcd":
                k = n // 50 if bits == 4096 else n // 10
            if op == "pow":
                k = n // 20 if bits == 4096 else n // 4
            out.append((f"nat.{op}.{bits}", k))
            out.append((f"big.{op}.{bits}", k))
    for op in ["add", "sub", "mul", "land", "lor", "xor", "shl", "shr", "succ", "beq"]:
        out.append((f"nat.{op}.gf3", 20_480 if op != "beq" else 10_000_000))
        out.append((f"big.{op}.gf3", 2_560 if op == "mul" else 20_480 if op != "beq" else 10_000_000))
    for op in ["add", "mul", "xor"]:
        for bits in (256, 4096):
            out.append((f"jdk.{op}.{bits}", 10_000_000 if bits == 256 else 250_000 if op == "mul" else 1_000_000))
    return out

def run(java_cmd, case, iters, warm, timeout):
    m = measure(java_cmd + ["rlvm.nat.bench.NatBench", case, str(iters), str(warm)], timeout=timeout)
    if m.get("exit_code") != 0 or not m.get("instructions"):
        raise SystemExit(f"{case} {iters}: failed or no perf counters: {m}")
    if m.get("counter_coverage", 1.0) < 0.999:
        print(f"warning: counter coverage {m['counter_coverage']} for {case}", file=sys.stderr)
    return m

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cases", default=".*", help="regex on the built-in case names, or a list name:iters,name:iters,...")
    ap.add_argument("--repeat", type=int, default=1)
    ap.add_argument("--jvm", default="-Xbatch",
                    help="JVM options (default -Xbatch: synchronous JIT compilation, which makes the total instruction "
                         "count of a run reproducible to about 0.1%%; with background compilation it varies by 1e9 and more)")
    ap.add_argument("--scale", type=float, default=1.0, help="multiply all iteration counts")
    ap.add_argument("--factor", type=int, default=3, help="long run = factor x short run")
    ap.add_argument("--iters", type=int, default=0, help="iterations of the short run for cases not in the built-in list")
    ap.add_argument("--cp", default="", help="class path (default: rlvm-nat/target/classes and test-classes)")
    ap.add_argument("--out", default="")
    ap.add_argument("--timeout", type=float, default=600)
    a = ap.parse_args()
    java = os.path.join(os.environ.get("JAVA_HOME", os.path.join(ROOT, ".toolchain/graalvm-ce-25")), "bin", "java")
    cp = a.cp or os.pathsep.join(os.path.join(ROOT, "rlvm-nat", "target", d) for d in ("classes", "test-classes"))
    java_cmd = [java] + shlex.split(a.jvm) + ["-cp", cp]
    rx = re.compile(a.cases)
    selected = [(c, n) for c, n in cases() if rx.search(c)]
    if not selected:  # ad hoc cases: name[:iters],name[:iters],...
        selected = []
        for c in a.cases.split(","):
            name, _, n = c.partition(":")
            if not (n or a.iters):
                raise SystemExit(f"no built-in case matches and no iteration count given for {name}")
            selected.append((name, int(n) if n else a.iters))
    rows = []
    base = None
    print(f"# java: {' '.join(java_cmd)}", flush=True)
    print(f"{'case':<18}{'iters':>12}{'instr/op':>14}{'cpu ns/op':>12}{'wall ns/op':>12}{'net instr/op':>14}", flush=True)
    for case, n in selected:
        n = max(256, int(n * a.scale) // 256 * 256)
        slopes = []
        for _ in range(a.repeat):
            warm = max(256 * 20 * 4, n // 2 // 5120 * 5120)  # same warm-up in both runs: it cancels
            m1 = run(java_cmd, case, n, warm, a.timeout)
            m2 = run(java_cmd, case, a.factor * n, warm, a.timeout)
            d = (a.factor - 1) * n
            slopes.append(((m2["instructions"] - m1["instructions"]) / d,
                           (m2["cpu_time_s"] - m1["cpu_time_s"]) * 1e9 / d,
                           (m2["wall_time_s"] - m1["wall_time_s"]) * 1e9 / d,
                           m2["max_rss_bytes"]))
        row = {"case": case, "iters": n,
               "instr_per_op": statistics.median(s[0] for s in slopes),
               "cpu_ns_per_op": statistics.median(s[1] for s in slopes),
               "wall_ns_per_op": statistics.median(s[2] for s in slopes),
               "max_rss_bytes": max(s[3] for s in slopes),
               "instr_per_op_runs": [s[0] for s in slopes]}
        spread = (max(s[0] for s in slopes) - min(s[0] for s in slopes)) / max(1e-9, abs(row["instr_per_op"]))
        row["spread"] = spread
        if case == "baseline":
            base = row["instr_per_op"]
        net = ""
        if base is not None and case.endswith(".small"):
            row["net_instr_per_op"] = row["instr_per_op"] - base
            net = f"{row['net_instr_per_op']:14.2f}"
        rows.append(row)
        flag = f"   spread {100 * spread:.0f}%" if a.repeat > 1 and spread > 0.05 else ""
        print(f"{case:<18}{n:>12}{row['instr_per_op']:>14.2f}{row['cpu_ns_per_op']:>12.2f}{row['wall_ns_per_op']:>12.2f}{net:>14}{flag}", flush=True)
    if a.out:
        with open(a.out, "w") as f:
            json.dump({"java": java_cmd, "rows": rows}, f, indent=1)

if __name__ == "__main__":
    main()
