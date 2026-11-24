#!/usr/bin/env python3
"""bench/baselines/big-summary.py - tables and bench/baselines.json from the
runs recorded by bench/baselines/big.sh.

    bench/baselines/big-summary.py [--runs work/baselines/big/runs.jsonl]...
                                   [--json bench/baselines.json] [--markdown] [--no-json]

    # RLVM next to the baselines, without touching bench/baselines.json:
    bench/baselines/big-summary.py --no-json --runs work/baselines/big/runs.jsonl --runs work/rlvm/big/runs.jsonl

Reads work/baselines/big/runs.jsonl (one line per measured run), the arena's
published results (work/arena-site/results.json), the corpus manifest
(work/exports/all/manifest.json) and the baselines' manifest
(bench/baselines/MANIFEST.json). Prints one table per corpus and a
thread-scaling table, and writes bench/baselines.json.

Per (checker, corpus) the representative figure is the MEDIAN over the runs
with the arena's configuration (variant "arena"); minimum, maximum and spread
are kept next to it. Runs of other variants (e.g. "threads-1") go to
"variants" and to the thread-scaling table.

Python stdlib only; runs on the host or in the container.
"""

from __future__ import annotations

import argparse
import collections
import datetime
import json
import os
import platform
import statistics
import sys

ROOT = os.path.realpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
IPS = 6_000_000_000

# what the arena calls the checker that a baseline of ours corresponds to
PUBLISHED_NAME = {"tenet-jvm-c2": "tenet-jvm"}
ORDER = ["nano-lean", "gruszka", "sokonanoda", "rpylean", "mathgraph", "pestka", "nanoda",
         "tenet-jvm-c2", "tenet-jvm", "official"]
NOTES = {
    "tenet-jvm": "project GraalVM CE 25 with the Graal JIT (not the arena's JVM setup)",
    "tenet-jvm-c2": "same JVM with -XX:-UseJVMCICompiler (C2), the closest to the arena's stock OpenJDK",
    "mathgraph": "accepts 8 bad proofs on the small tests; not a ranking competitor",
}


def load_json(path):
    with open(path) as handle:
        return json.load(handle)


def status_of(run: dict) -> str:
    if run.get("timed_out"):
        return "timeout"
    code = run.get("exit_code")
    return {0: "accepted", 1: "rejected", 2: "declined"}.get(code, "error")


def summarise(runs: list[dict]) -> dict:
    """Median figures of a list of runs of one configuration."""
    good = [r for r in runs if r.get("instructions")]
    out: dict = {
        "n_runs": len(runs),
        "status": sorted({status_of(r) for r in runs}),
        "exit_codes": sorted({r.get("exit_code") for r in runs}, key=lambda c: (c is None, c)),
        "runs": [{k: r.get(k) for k in (
            "run", "exit_code", "timed_out", "instructions", "virtual_time_s", "cpu_time_s", "wall_time_s",
            "max_rss_bytes", "instructions_kernel", "user_time_s", "sys_time_s", "counter_coverage",
            "started_utc", "host_loadavg_start", "host_loadavg_end", "timeout_s", "container")} for r in runs],
    }
    if len(out["status"]) == 1:
        out["status"] = out["status"][0]
    if good:
        ins = [r["instructions"] for r in good]
        med = statistics.median(ins)
        out.update({
            "instructions": int(med),
            "instructions_min": min(ins),
            "instructions_max": max(ins),
            "instructions_spread_pct": round(100.0 * (max(ins) - min(ins)) / med, 3),
            "virtual_time_s": round(med / IPS, 3),
            "cpu_time_s": round(statistics.median(r["cpu_time_s"] for r in good), 2),
            "wall_time_s": round(statistics.median(r["wall_time_s"] for r in good), 2),
            "wall_time_s_min": round(min(r["wall_time_s"] for r in good), 2),
            "max_rss_bytes": max(r["max_rss_bytes"] or 0 for r in good),
            "instructions_kernel": int(statistics.median(r.get("instructions_kernel") or 0 for r in good)),
        })
    return out


def fmt_instr(n) -> str:
    if not n:
        return "-"
    if n >= 1e12:
        return f"{n / 1e12:.3f}e12"
    return f"{n / 1e9:.1f}e9"


def fmt_time(seconds) -> str:
    if seconds is None:
        return "-"
    if seconds >= 600:
        return f"{seconds:.0f} s ({seconds / 60:.1f} min)"
    return f"{seconds:.2f} s" if seconds < 100 else f"{seconds:.1f} s"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--runs", action="append", metavar="FILE",
                        help="runs.jsonl of big.sh; repeatable, e.g. to put RLVM's runs next to the "
                             "baselines' (default: work/baselines/big/runs.jsonl)")
    parser.add_argument("--json", default=os.path.join(ROOT, "bench/baselines.json"))
    parser.add_argument("--no-json", action="store_true", help="only print the tables")
    parser.add_argument("--markdown", action="store_true", help="tables in markdown")
    parser.add_argument("--section", choices=["corpora", "arch", "threads"], action="append",
                        help="print only these tables (default: all)")
    args = parser.parse_args()

    run_files = args.runs or [os.path.join(ROOT, "work/baselines/big/runs.jsonl")]
    runs = [json.loads(line) for path in run_files for line in open(path) if line.strip()]
    published_all = load_json(os.path.join(ROOT, "work/arena-site/results.json"))
    published = {(r["checker"], r["test"]): r for r in published_all["results"]}
    manifest = {t["name"]: t for t in load_json(os.path.join(ROOT, "work/exports/all/manifest.json"))["tests"]}
    checkers_meta = load_json(os.path.join(ROOT, "bench/baselines/MANIFEST.json"))

    grouped: dict = collections.defaultdict(list)
    for r in runs:
        grouped[(r["corpus"], r["checker"], r.get("variant", "arena"))].append(r)

    corpora = sorted({c for c, _, _ in grouped}, key=lambda c: manifest.get(c, {}).get("size", 0))
    results: dict = {}
    variants: dict = {}
    for (corpus, checker, variant), rs in grouped.items():
        rs.sort(key=lambda r: r.get("run", 0))
        entry = summarise(rs)
        entry["command"] = rs[0].get("command")
        if variant == "arena":
            pub = published.get((PUBLISHED_NAME.get(checker, checker), corpus))
            if pub and pub.get("instructions"):
                entry["published_x86_64"] = {
                    "checker": pub["checker"], "status": pub["status"], "instructions": pub["instructions"],
                    "virtual_time_s": round(pub["instructions"] / IPS, 3), "wall_time_s": pub.get("wall_time"),
                    "cpu_time_s": pub.get("cpu_time"), "max_rss_bytes": pub.get("max_rss"), "date": pub.get("date"),
                }
                if entry.get("instructions"):
                    entry["aarch64_over_x86_64"] = round(entry["instructions"] / pub["instructions"], 4)
            if checker in NOTES:
                entry["note"] = NOTES[checker]
            results.setdefault(corpus, {})[checker] = entry
        else:
            variants.setdefault(corpus, {}).setdefault(checker, {})[variant] = entry

    # ratio to the fastest correct checker of each corpus
    targets = {}
    for corpus, by_checker in results.items():
        ok = {c: e for c, e in by_checker.items() if e.get("status") == "accepted" and e.get("instructions")}
        if not ok:
            continue
        best = min(ok, key=lambda c: ok[c]["instructions"])
        for c, e in ok.items():
            e["ratio_to_fastest"] = round(e["instructions"] / ok[best]["instructions"], 3)
        targets[corpus] = {"fastest_checker": best, "instructions": ok[best]["instructions"],
                           "virtual_time_s": ok[best]["virtual_time_s"],
                           "top": [{"checker": c, "instructions": ok[c]["instructions"],
                                    "virtual_time_s": ok[c]["virtual_time_s"]}
                                   for c in sorted(ok, key=lambda c: ok[c]["instructions"])[:4]]}

    def order_key(name: str):
        return ORDER.index(name) if name in ORDER else len(ORDER)

    # ---- tables ---------------------------------------------------------------
    md = args.markdown
    sections = args.section or ["corpora", "arch", "threads"]
    for corpus in corpora if "corpora" in sections else []:
        by_checker = results.get(corpus, {})
        info = manifest.get(corpus, {})
        print(f"\n### {corpus}  ({info.get('size', 0) / 1e6:,.0f} MB, {info.get('lines', 0):,} lines, "
              f"arena timeout {info.get('timeout')} s)\n" if md else f"\n== {corpus}")
        head = ["checker", "runs", "status", "instructions", "virtual time", "x fastest", "spread",
                "CPU s", "wall s (min / median)", "max RSS MiB", "arena x86-64 virtual", "aarch64/x86-64"]
        rows = []
        for checker in sorted(by_checker, key=lambda c: (by_checker[c].get("status") != "accepted",
                                                         by_checker[c].get("instructions") or float("inf"), order_key(c))):
            e = by_checker[checker]
            pub = e.get("published_x86_64")
            rows.append([
                checker, str(e["n_runs"]), str(e["status"]), fmt_instr(e.get("instructions")),
                fmt_time(e.get("virtual_time_s")),
                f"{e['ratio_to_fastest']:.2f}" if e.get("ratio_to_fastest") else "-",
                f"{e['instructions_spread_pct']:.2f} %" if e.get("n_runs", 0) > 1 and "instructions_spread_pct" in e else "-",
                f"{e['cpu_time_s']:.1f}" if e.get("cpu_time_s") is not None else "-",
                (f"{e['wall_time_s_min']:.1f} / {e['wall_time_s']:.1f}" if e.get("n_runs", 0) > 1 else f"{e['wall_time_s']:.1f}")
                if e.get("wall_time_s") is not None else "-",
                f"{e['max_rss_bytes'] / 2**20:,.0f}" if e.get("max_rss_bytes") else "-",
                fmt_time(pub["virtual_time_s"]) if pub else "-",
                f"{e['aarch64_over_x86_64']:.3f}" if e.get("aarch64_over_x86_64") else "-",
            ])
        print_table(head, rows, md)

    # aarch64 / x86-64 per checker and corpus, with the small tests of validate.sh for comparison
    small = {}
    try:
        for entry in load_json(os.path.join(ROOT, "work/baselines/small-summary.json")):
            small[entry["checker"]] = entry.get("ours_over_arena")
    except (OSError, ValueError):
        pass
    arch = {}
    for corpus in corpora:
        for checker, e in results.get(corpus, {}).items():
            if e.get("aarch64_over_x86_64"):
                arch.setdefault(checker, {})[corpus] = e["aarch64_over_x86_64"]
    if arch and "arch" in sections:
        print("\n### aarch64 (this host) / x86-64 (arena) instruction counts\n" if md else "\n== aarch64 / x86-64")
        head = ["checker", "230 small tests", *corpora]
        rows = [[checker, f"{small[checker]:.3f}" if small.get(checker) else "-",
                 *(f"{arch[checker][c]:.3f}" if c in arch[checker] else "-" for c in corpora)]
                for checker in sorted(arch, key=order_key)]
        print_table(head, rows, md)

    scaling = {}
    for corpus, by_checker in variants.items():
        for checker, vs in by_checker.items():
            per = {}
            for variant, e in vs.items():
                if variant.startswith("threads-"):
                    per[int(variant.split("-", 1)[1])] = e
            base = results.get(corpus, {}).get(checker)
            yaml_threads = (checkers_meta.get(checker) or {}).get("yaml_threads") or 4
            if base and per:
                per.setdefault(yaml_threads, base)
            if per:
                scaling.setdefault(corpus, {})[checker] = {str(n): {k: per[n].get(k) for k in (
                    "n_runs", "status", "instructions", "virtual_time_s", "cpu_time_s", "wall_time_s",
                    "max_rss_bytes", "instructions_spread_pct")} for n in sorted(per)}
    for corpus, by_checker in scaling.items() if "threads" in sections else []:
        print(f"\n### thread scaling on {corpus}\n" if md else f"\n== thread scaling on {corpus}")
        head = ["checker", "threads", "instructions", "virtual time", "vs 1 thread", "CPU s", "wall s", "max RSS MiB"]
        rows = []
        for checker in sorted(by_checker, key=order_key):
            one = by_checker[checker].get("1", {}).get("instructions")
            for n, e in by_checker[checker].items():
                rows.append([checker, n, fmt_instr(e.get("instructions")), fmt_time(e.get("virtual_time_s")),
                             f"{e['instructions'] / one:.3f}" if one and e.get("instructions") else "-",
                             f"{e['cpu_time_s']:.1f}" if e.get("cpu_time_s") is not None else "-",
                             f"{e['wall_time_s']:.1f}" if e.get("wall_time_s") is not None else "-",
                             f"{e['max_rss_bytes'] / 2**20:,.0f}" if e.get("max_rss_bytes") else "-"])
        print_table(head, rows, md)

    if args.no_json:
        return 0

    images = sorted({r.get("image") for r in runs if r.get("image")})
    loads = [r["host_loadavg_start"][0] for r in runs if r.get("host_loadavg_start")]
    doc = {
        "meta": {
            "generated_utc": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "what": "Baseline arena checkers measured on this machine on the big corpora, the way the "
                    "Lean Kernel Arena measures (perf stat -e instructions:u over the whole process tree, "
                    "GNU time max RSS). The ranking metric is virtual_time_s = instructions / 6e9 on mathlib.",
            "host": {"arch": platform.machine(), "cores": "Cortex-X925 (CPUs 5-9,15-19) + Cortex-A725 (0-4,10-14)",
                     "note": "the arena runner is x86-64, 8 vCPU, 16 GB; absolute counts differ, see aarch64_over_x86_64"},
            "container": "bench/arena-docker.sh --arena-limits --no-network (8 performance cores, 16 GB, one run per "
                         "container); a run made otherwise says so in its own \"container\" field",
            "images": images,
            "measure": "bench/measure.sh (perf stat --no-scale -e instructions:u summed over PMUs, task-clock, duration_time; GNU time %M)",
            "instructions_per_virtual_second": IPS,
            "representative_value": "median over the runs of the arena configuration; *_min/_max/_spread_pct next to it",
            "timeout": "2 x the arena's timeout of the corpus (init 370 s, std 600 s, mathlib 4120 s)",
            "arena_revision": open(os.path.join(ROOT, "bench/baselines/ARENA_REV")).read().strip(),
            "published_results": {"file": "work/arena-site/results.json", "timestamp": published_all["meta"].get("timestamp")},
            "runs_file": [os.path.relpath(os.path.abspath(path), ROOT) for path in run_files],
            "n_runs": len(runs),
            "first_run_utc": min(r["started_utc"] for r in runs),
            "last_run_utc": max(r["started_utc"] for r in runs),
            "host_loadavg_1min_during_runs": {"min": min(loads), "median": statistics.median(loads), "max": max(loads)} if loads else None,
            "caveat": "wall and CPU times were taken while other containers shared the host (and at times the same "
                      "8 cores); instruction counts do not depend on that",
            "reproduce": "bench/baselines/big-all.sh && bench/baselines/big-summary.py",
        },
        "corpora": {c: {k: manifest[c].get(k) for k in ("size", "lines", "sha256", "timeout", "lean_version")}
                    for c in corpora if c in manifest},
        "checkers": {name: dict(checkers_meta.get(PUBLISHED_NAME.get(name, name), {}),
                                **({"note": NOTES[name]} if name in NOTES else {}))
                     for name in sorted({c for _, c, _ in grouped}, key=order_key)},
        "target": targets,
        "aarch64_over_x86_64": {c: dict(arch[c], **({"small_tests_230": round(small[c], 4)} if small.get(c) else {}))
                                for c in sorted(arch, key=order_key)},
        "results": {c: {k: results[c][k] for k in sorted(results[c], key=lambda k: (
                            results[c][k].get("status") != "accepted", results[c][k].get("instructions") or float("inf")))}
                    for c in corpora if c in results},
        "thread_scaling": scaling,
        "variants": variants,
    }
    with open(args.json, "w") as handle:
        json.dump(doc, handle, indent=1)
        handle.write("\n")
    print(f"\nwrote {os.path.relpath(args.json, ROOT)}", file=sys.stderr)
    return 0


def print_table(head: list[str], rows: list[list[str]], markdown: bool) -> None:
    if markdown:
        print("| " + " | ".join(head) + " |")
        print("|" + "|".join("---" if i == 0 else "---:" for i in range(len(head))) + "|")
        for row in rows:
            print("| " + " | ".join(row) + " |")
        return
    widths = [max(len(head[i]), *(len(r[i]) for r in rows)) if rows else len(head[i]) for i in range(len(head))]
    print("  ".join(h.ljust(w) for h, w in zip(head, widths)))
    for row in rows:
        print("  ".join(c.ljust(w) if i == 0 else c.rjust(w) for i, (c, w) in enumerate(zip(row, widths))))


if __name__ == "__main__":
    sys.exit(main())
