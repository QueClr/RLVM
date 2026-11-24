#!/usr/bin/env python3
"""bench/perf-validate.py - check that perf instruction counts in the container
are trustworthy: that they include threads and child processes, that they do
not depend on which core type a thread runs on, and how much they vary from
run to run.

    bench/arena-docker.sh bench/perf-validate.py [--runs 5] [--out DIR]

Builds bench/perf-validate/{burn.c,Burn.java} into work/perf-validate/ and
writes work/perf-validate/report.json. Must run inside the container.
"""

from __future__ import annotations

import argparse
import glob
import json
import os
import statistics
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
sys.dont_write_bytecode = True  # keep bench/ free of __pycache__
from measure import measure, probe_perf  # noqa: E402

PY_WORKLOAD = (
    "import hashlib\n"
    "d = {}\n"
    "h = b'x'\n"
    "for i in range(300000):\n"
    "    h = hashlib.sha256(h).digest()\n"
    "    d[h[:4]] = d.get(h[:4], 0) + i\n"
    "print(len(d))\n"
)


def pmu_cpus() -> dict[str, str]:
    """CPU lists per CPU PMU, e.g. {"armv8_pmuv3_0": "0-4,10-14", ...}."""
    result = {}
    for path in sorted(glob.glob("/sys/bus/event_source/devices/*/cpus")):
        name = path.split("/")[-2]
        if os.path.exists(os.path.join(os.path.dirname(path), "events", "instructions")) \
           or name.startswith(("armv8_pmuv3", "cpu")):
            try:
                with open(path) as handle:
                    result[name] = handle.read().strip()
            except OSError:
                pass
    return result


def run(cmd, shell=False) -> dict:
    result = measure(cmd, shell=shell, capture=True, use_perf=True)
    if result["exit_code"] != 0 or result["instructions"] is None:
        raise SystemExit(f"measurement failed for {cmd!r}: {result}")
    return result


def stats(values: list[float]) -> dict:
    mean = statistics.fmean(values)
    stdev = statistics.stdev(values) if len(values) > 1 else 0.0
    return {
        "n": len(values), "min": min(values), "max": max(values), "mean": mean,
        "stdev": stdev,
        "cv_pct": 100.0 * stdev / mean if mean else 0.0,
        "spread_pct": 100.0 * (max(values) - min(values)) / mean if mean else 0.0,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--runs", type=int, default=5, help="repetitions for the stability test")
    parser.add_argument("--out", default=os.path.join(ROOT, "work", "perf-validate"),
                        help="build and report directory")
    parser.add_argument("--iterations", type=int, default=200_000_000,
                        help="loop iterations per worker of the C workload")
    parser.add_argument("--tag", default="", help="suffix for the report file name")
    args = parser.parse_args()

    usable, detail = probe_perf()
    if not usable:
        print(f"perf hardware counters are NOT available here: {detail}", file=sys.stderr)
        return 3
    print(f"perf: {detail}")
    affinity = sorted(os.sched_getaffinity(0))
    print(f"CPUs available to this container: {len(affinity)} ({affinity[0]}..{affinity[-1]})")
    pmus = pmu_cpus()
    for name, cpus in pmus.items():
        print(f"PMU {name}: cpus {cpus}")

    os.makedirs(args.out, exist_ok=True)
    burn = os.path.join(args.out, "burn")
    subprocess.run(["gcc", "-O2", "-pthread", "-o", burn,
                    os.path.join(HERE, "perf-validate", "burn.c")], check=True)
    classes = os.path.join(args.out, "classes")
    have_java = bool(os.environ.get("JAVA_HOME"))
    if have_java:
        subprocess.run(["javac", "-d", classes, os.path.join(HERE, "perf-validate", "Burn.java")], check=True)

    n = str(args.iterations)
    report: dict = {"perf": detail, "pmus": pmus, "cpus_available": len(affinity),
                    "iterations": args.iterations, "runs": args.runs}

    # ---- 1. threads and children are included --------------------------------
    print("\n== 1. Are threads and child processes counted? ==")
    baseline = run([burn, "single", "1"])["instructions"]           # process overhead only
    single = run([burn, "single", n])["instructions"]
    per_worker = single - baseline
    cases = {
        "4 threads (pthread)": ([burn, "threads", "4", n], False, 4),
        "4 forked children": ([burn, "fork", "4", n], False, 4),
        "3 processes spawned by sh -c": (f"{burn} single {n} >/dev/null & {burn} single {n} >/dev/null & "
                                         f"{burn} single {n} >/dev/null; wait", True, 3),
    }
    print(f"{'workload':34} {'instructions:u':>16} {'expected':>16} {'ratio':>9}")
    print(f"{'1 thread':34} {single:16d} {'(reference)':>16} {'':>9}")
    inclusion = {"single": single, "startup_overhead": baseline, "cases": {}}
    ok = True
    for label, (cmd, shell, workers) in cases.items():
        got = run(cmd, shell=shell)["instructions"]
        expected = workers * per_worker
        ratio = got / expected
        ok = ok and abs(ratio - 1.0) < 0.01
        inclusion["cases"][label] = {"instructions": got, "expected": expected, "ratio": ratio}
        print(f"{label:34} {got:16d} {expected:16d} {ratio:9.5f}")
    inclusion["all_within_1pct"] = ok
    report["inclusion"] = inclusion
    print("=> threads and children ARE included" if ok else "=> MISMATCH: counts do not scale with workers")

    # ---- 2. independence from the core type ----------------------------------
    print("\n== 2. Does the count depend on the core type / CPU placement? ==")
    placement = {"unpinned": run([burn, "single", n])["instructions"]}
    for name, cpus in pmus.items():
        allowed = [c for c in _expand(cpus) if c in affinity]
        if not allowed:
            continue
        cpu_list = ",".join(str(c) for c in allowed)
        placement[f"taskset {name} ({cpus})"] = run(["taskset", "-c", cpu_list, burn, "single", n])["instructions"]
    reference = placement["unpinned"]
    for label, value in placement.items():
        print(f"{label:44} {value:16d}  {100.0 * (value - reference) / reference:+.6f}%")
    report["placement"] = placement

    # ---- 3. run-to-run stability ---------------------------------------------
    print(f"\n== 3. Run-to-run stability over {args.runs} runs ==")
    workloads = {
        "C, 1 thread (deterministic)": ([burn, "single", n], False),
        "C, 4 threads (deterministic)": ([burn, "threads", "4", n], False),
        # PYTHONHASHSEED fixed: otherwise the dict layout, and with it the
        # instruction count, differs from run to run by about 1%.
        "CPython, 1 thread (hash seed 0)": (["env", "PYTHONHASHSEED=0", "python3", "-c", PY_WORKLOAD], False),
        "CPython, 1 thread (random seed)": (["python3", "-c", PY_WORKLOAD], False),
    }
    if have_java:
        java = ["java", "-cp", classes, "Burn"]
        workloads["JVM hello (java -version)"] = (["java", "-version"], False)
        workloads["JVM, 1 thread"] = (java + ["1", "30000000"], False)
        workloads["JVM, 4 threads"] = (java + ["4", "30000000"], False)
    header = (f"{'workload':32} {'mean instr:u':>15} {'min':>15} {'max':>15} "
              f"{'stdev':>12} {'CV %':>8} {'spread %':>9} {'kernel %':>9} {'cpu s':>7}")
    print(header)
    stability = {}
    for label, (cmd, shell) in workloads.items():
        runs = [run(cmd, shell=shell) for _ in range(args.runs)]
        user = [r["instructions"] for r in runs]
        kernel = [r["instructions_kernel"] or 0 for r in runs]
        cpu = [r["cpu_time_s"] for r in runs]
        s = stats(user)
        kernel_pct = 100.0 * statistics.fmean(kernel) / (s["mean"] + statistics.fmean(kernel))
        stability[label] = {
            "instructions": user, "instructions_kernel": kernel, "cpu_time_s": cpu,
            "wall_time_s": [r["wall_time_s"] for r in runs],
            "max_rss_bytes": [r["max_rss_bytes"] for r in runs],
            "stats": s, "cpu_time_stats": stats(cpu), "kernel_share_pct": kernel_pct,
            "counter_coverage": [r["counter_coverage"] for r in runs],
        }
        print(f"{label:32} {s['mean']:15.0f} {s['min']:15d} {s['max']:15d} "
              f"{s['stdev']:12.0f} {s['cv_pct']:8.4f} {s['spread_pct']:9.4f} {kernel_pct:9.3f} "
              f"{statistics.fmean(cpu):7.3f}")
    report["stability"] = stability

    out_file = os.path.join(args.out, f"report{('-' + args.tag) if args.tag else ''}.json")
    with open(out_file, "w") as handle:
        json.dump(report, handle, indent=1)
    print(f"\nreport: {out_file}")
    return 0 if ok else 1


def _expand(cpu_list: str) -> list[int]:
    cpus: list[int] = []
    for part in cpu_list.split(","):
        if "-" in part:
            lo, hi = part.split("-")
            cpus.extend(range(int(lo), int(hi) + 1))
        elif part:
            cpus.append(int(part))
    return cpus


if __name__ == "__main__":
    sys.exit(main())
