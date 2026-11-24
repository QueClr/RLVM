#!/usr/bin/env python3
"""bench/run-suite.py - run a Lean kernel checker over an arena test directory
and score it exactly like the Lean Kernel Arena does.

    bench/run-suite.py [options] [--] CHECKER COMMAND ...

The checker command is a shell command, run with `sh -c` once per test, as the
arena runs a checker's `run:` entry: the export file's path is in the
environment variable IN, so write '"$IN"' (quoted from your own shell) where
the path belongs, or use the placeholder {IN}, which is replaced by the quoted
path. Exit status 0 = accept, 1 = reject, 2 = decline, anything else = error.

    bench/run-suite.py -- ./my-checker '"$IN"'
    bench/run-suite.py -j 8 --label rlvm -- java -jar build/rlvm.jar {IN}
    bench/arena-docker.sh bench/run-suite.py --perf -- work/checkers/nanoda/run.sh

The tests directory (default work/arena-site/tests, the published arena
tarball) contains good/**/*.ndjson (must be accepted), bad/**/*.ndjson (must
be rejected) and optionally either/**/*.ndjson (accept and reject are both
fine; the arena's corner cases with `outcome: either`, which the published
tarball leaves out). A test's name is its path below good/, bad/ or either/
without the extension, e.g. "perf/app-lam", the same name the arena uses.

Scoring (lka.py run_checker_on_test / compute_checker_stats):
  status       accepted | rejected | declined | error | timeout
  correctness  correct | incorrect | either | declined | error
               (error and timeout both count as "error", and like declines
               they are neither right nor wrong)
The arena ranks by (bad tests accepted, good tests rejected, mathlib
instructions, number of declined/error/timeout tests).

Works on the host (no perf: wall time, and CPU time / max RSS via GNU time)
and inside the container, where --perf adds instruction counts through
measure.py (the code behind measure.sh). Python stdlib only.

Exit status: 0 if no test is incorrect, in error or timed out; 1 otherwise;
2 for usage errors; 3 if --perf was requested but counters are unavailable.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import datetime
import fnmatch
import json
import os
import platform
import re
import shlex
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
sys.dont_write_bytecode = True  # keep bench/ free of __pycache__
import measure as measure_mod  # noqa: E402

BUCKETS = (("good", "accept"), ("bad", "reject"), ("either", "either"))


# ----------------------------------------------------------------- tests ----
def discover_tests(tests_dir: str) -> list[dict]:
    tests = []
    for bucket, outcome in BUCKETS:
        base = os.path.join(tests_dir, bucket)
        if not os.path.isdir(base):
            continue
        for dirpath, _dirnames, filenames in os.walk(base):
            for filename in filenames:
                if not filename.endswith(".ndjson"):
                    continue
                path = os.path.join(dirpath, filename)
                name = os.path.relpath(path, base)[: -len(".ndjson")].replace(os.sep, "/")
                tests.append({"name": name, "bucket": bucket, "outcome": outcome,
                              "file": path, "size": os.path.getsize(path)})
    tests.sort(key=lambda t: t["name"])
    return tests


def load_meta(path: str) -> dict[str, dict]:
    """Per-test metadata {name: {"outcome": ..., "timeout": ...}} from either
    the arena's published results.json or a checkout of its tests/ directory
    (YAML files; only the top-level `outcome:` and `timeout:` keys are read,
    so no YAML library is needed)."""
    meta: dict[str, dict] = {}
    if os.path.isfile(path):
        with open(path) as handle:
            data = json.load(handle)
        for test in data.get("tests", []):
            entry = {}
            if test.get("outcome"):
                entry["outcome"] = test["outcome"]
            if test.get("timeout"):
                entry["timeout"] = float(test["timeout"])
            meta[test["name"]] = entry
    elif os.path.isdir(path):
        for dirpath, _dirnames, filenames in os.walk(path):
            for filename in filenames:
                if not filename.endswith(".yaml"):
                    continue
                full = os.path.join(dirpath, filename)
                name = os.path.relpath(full, path)[: -len(".yaml")].replace(os.sep, "/")
                entry = {}
                with open(full, errors="replace") as handle:
                    for line in handle:
                        match = re.match(r"^(outcome|timeout):\s*['\"]?([A-Za-z0-9.]+)", line)
                        if match and match.group(1) == "outcome":
                            entry["outcome"] = match.group(2)
                        elif match:
                            try:
                                entry["timeout"] = float(match.group(2))
                            except ValueError:
                                pass
                meta[name] = entry
    return meta


def default_meta_path(tests_dir: str) -> str | None:
    candidates = [
        # written by collect-exports.py for work/exports/all (the complete test set)
        os.path.join(os.path.abspath(tests_dir), "manifest.json"),
        os.path.join(os.path.dirname(os.path.abspath(tests_dir)), "results.json"),
        os.path.join(ROOT, "work", "arena-site", "results.json"),
        os.path.join(ROOT, "refs", "lean-kernel-arena", "tests"),
    ]
    for candidate in candidates:
        if os.path.exists(candidate):
            return candidate
    return None


# --------------------------------------------------------------- scoring ----
def classify(exit_code: int, timed_out: bool, expected: str) -> tuple[str, str]:
    """(status, correctness), as in lka.py run_checker_on_test."""
    if timed_out:
        status = "timeout"
    elif exit_code == 0:
        status = "accepted"
    elif exit_code == 1:
        status = "rejected"
    elif exit_code == 2:
        status = "declined"
    else:
        status = "error"

    if status == "declined":
        correctness = "declined"
    elif status in ("error", "timeout"):
        correctness = "error"
    elif expected == "either":
        correctness = "either"
    elif expected == "accept" and status == "accepted":
        correctness = "correct"
    elif expected == "reject" and status == "rejected":
        correctness = "correct"
    else:
        correctness = "incorrect"
    return status, correctness


def summarize(results: list[dict]) -> dict:
    """The arena's per-checker statistics (lka.py compute_checker_stats)."""
    s = {key: 0 for key in (
        "accept_correct", "accept_wrong", "accept_declined", "accept_total",
        "reject_correct", "reject_wrong", "reject_declined", "reject_total",
        "either_correct", "either_declined", "declined_count")}
    by_status: dict[str, int] = {}
    by_correctness: dict[str, int] = {}
    for r in results:
        status, expected = r["status"], r["expected"]
        by_status[status] = by_status.get(status, 0) + 1
        by_correctness[r["correctness"]] = by_correctness.get(r["correctness"], 0) + 1
        if status in ("declined", "error", "timeout"):
            s["declined_count"] += 1
            if expected in ("accept", "reject", "either"):
                s[f"{expected}_declined"] += 1
            continue
        if expected == "either":
            s["either_correct"] += 1
        elif expected == "accept":
            s["accept_total"] += 1
            s["accept_correct" if status == "accepted" else "accept_wrong"] += 1
        elif expected == "reject":
            s["reject_total"] += 1
            s["reject_correct" if status == "rejected" else "reject_wrong"] += 1
    s["by_status"] = by_status
    s["by_correctness"] = by_correctness
    s["tests"] = len(results)
    s["good_tests"] = sum(1 for r in results if r["expected"] == "accept")
    s["bad_tests"] = sum(1 for r in results if r["expected"] == "reject")
    s["either_tests"] = sum(1 for r in results if r["expected"] == "either")
    # The arena's ranking key without the mathlib term: fewer is better.
    s["bad_accepted"] = s["reject_wrong"]
    s["good_rejected"] = s["accept_wrong"]
    s["arena_sort_key"] = [s["reject_wrong"], s["accept_wrong"], s["declined_count"]]
    s["wall_time"] = sum(r["wall_time"] or 0 for r in results)
    s["cpu_time"] = sum(r["cpu_time"] or 0 for r in results)
    instructions = [r["instructions"] for r in results if r.get("instructions")]
    s["instructions"] = sum(instructions) if instructions else None
    s["virtual_time"] = (s["instructions"] / measure_mod.INSTRUCTIONS_PER_SECOND
                         if s["instructions"] else None)
    rss = [r["max_rss"] for r in results if r.get("max_rss")]
    s["max_rss"] = max(rss) if rss else None
    # Self-check of the counters (see measure.py); below about 0.99 a count is too low.
    coverage = [r["counter_coverage"] for r in results if r.get("counter_coverage") is not None]
    s["min_counter_coverage"] = min(coverage) if coverage else None
    return s


# ------------------------------------------------------------- formatting ---
def fmt_seconds(value) -> str:
    if value is None:
        return "-"
    if value < 1:
        return f"{value * 1000:.0f}ms"
    return f"{value:.2f}s"


def fmt_bytes(value) -> str:
    if not value:
        return "-"
    for unit in ("B", "KiB", "MiB", "GiB"):
        if value < 1024 or unit == "GiB":
            return f"{value:.0f}{unit}" if unit == "B" else f"{value:.1f}{unit}"
        value /= 1024
    return "-"


def last_line(result: dict) -> str:
    for stream in ("stderr", "stdout"):
        lines = [ln.strip() for ln in (result.get(stream) or "").splitlines() if ln.strip()]
        if lines:
            return lines[-1]
    return ""


def print_table(rows: list[list[str]], header: list[str]) -> None:
    widths = [max(len(str(row[i])) for row in [header] + rows) for i in range(len(header))]
    line = "  ".join(str(h).ljust(w) for h, w in zip(header, widths))
    print(line.rstrip())
    print("  ".join("-" * w for w in widths))
    for row in rows:
        print("  ".join(str(c).ljust(w) for c, w in zip(row, widths)).rstrip())


# ------------------------------------------------------------------ main ----
def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--tests", default=os.path.join(ROOT, "work", "arena-site", "tests"),
                        help="directory with good/ and bad/ (and optionally either/) "
                             "(default: work/arena-site/tests)")
    parser.add_argument("--timeout", type=float, default=120.0,
                        help="per-test timeout in seconds; the whole process group is killed "
                             "(default: 120; 0 = none)")
    parser.add_argument("--arena-timeouts", action="store_true",
                        help="use the arena's own per-test timeout where the metadata has one "
                             "(--timeout applies to the rest)")
    parser.add_argument("-j", "--jobs", type=int, default=1, help="tests to run in parallel (default: 1)")
    parser.add_argument("--perf", action="store_true",
                        help="measure instructions with perf stat like the arena (inside the "
                             "container only); without it only time and max RSS are recorded")
    parser.add_argument("--report", metavar="FILE",
                        help="JSON report (default: work/suite-reports/<label>-<timestamp>.json)")
    parser.add_argument("--label", help="name of the checker in the report (default: from the command)")
    parser.add_argument("--cwd", help="working directory for the checker (default: current directory)")
    parser.add_argument("--env", action="append", default=[], metavar="VAR=VALUE",
                        help="extra environment variable for the checker (repeatable)")
    parser.add_argument("--only", action="append", default=[], metavar="GLOB",
                        help="run only tests whose name matches (repeatable), e.g. 'tutorial/*'")
    parser.add_argument("--exclude", action="append", default=[], metavar="GLOB",
                        help="skip tests whose name matches (repeatable)")
    parser.add_argument("--meta", metavar="PATH",
                        help="arena results.json, or a checkout of the arena's tests/ directory; "
                             "marks tests with outcome 'either' and supplies --arena-timeouts "
                             "(default: manifest.json in the tests directory, as written by "
                             "collect-exports.py, else results.json next to the tests directory, else "
                             "work/arena-site/results.json, else refs/lean-kernel-arena/tests)")
    parser.add_argument("--list", action="store_true", help="list the selected tests and exit")
    parser.add_argument("-v", "--verbose", action="store_true", help="print every result as it arrives")
    parser.add_argument("-q", "--quiet", action="store_true", help="print only the summary line")
    parser.add_argument("checker", nargs=argparse.REMAINDER,
                        help="checker command (shell syntax; $IN or {IN} is the export file)")
    args = parser.parse_args()

    words = args.checker
    if words and words[0] == "--":
        words = words[1:]
    if not words and not args.list:
        parser.error("no checker command given")
    template = " ".join(words)

    tests_dir = os.path.abspath(args.tests)
    tests = discover_tests(tests_dir)
    if not tests:
        print(f"run-suite: no tests found: {tests_dir} has no good/, bad/ or either/ "
              f"directory with *.ndjson files", file=sys.stderr)
        return 2

    warnings: list[str] = []
    meta_path = args.meta or default_meta_path(tests_dir)
    meta = load_meta(meta_path) if meta_path else {}
    for test in tests:
        entry = meta.get(test["name"], {})
        test["arena_timeout"] = entry.get("timeout")
        known = entry.get("outcome")
        if known == "either" and test["outcome"] != "either":
            test["outcome"] = "either"
        elif known and known != test["outcome"] and test["outcome"] != "either":
            warnings.append(f"{test['name']}: in {test['bucket']}/ but the metadata says "
                            f"outcome={known}; scoring by directory")

    if args.only:
        tests = [t for t in tests if any(fnmatch.fnmatch(t["name"], g) for g in args.only)]
    if args.exclude:
        tests = [t for t in tests if not any(fnmatch.fnmatch(t["name"], g) for g in args.exclude)]
    default_timeout = args.timeout if args.timeout and args.timeout > 0 else None
    for test in tests:
        if args.arena_timeouts and test["arena_timeout"]:
            test["timeout"] = test["arena_timeout"]
        else:
            test["timeout"] = default_timeout

    if args.list:
        for test in tests:
            print(f"{test['outcome']:7} {test['size']:>10}  {test['name']}")
        print(f"{len(tests)} tests in {tests_dir}", file=sys.stderr)
        return 0
    if not tests:
        print("run-suite: no test matches the selection", file=sys.stderr)
        return 2

    use_perf = False
    perf_detail = "not requested"
    if args.perf:
        use_perf, perf_detail = measure_mod.probe_perf()
        if not use_perf:
            print(f"run-suite: --perf: {perf_detail}\n"
                  f"run-suite: run inside the container: bench/arena-docker.sh bench/run-suite.py --perf ...",
                  file=sys.stderr)
            return 3

    base_env = dict(os.environ)
    for item in args.env:
        if "=" not in item:
            parser.error(f"--env needs VAR=VALUE, got {item!r}")
        key, value = item.split("=", 1)
        base_env[key] = value

    if not re.search(r"\$IN\b|\$\{IN\}|\{IN\}", template) and not args.quiet:
        print("run-suite: note: the command mentions neither $IN nor {IN}; "
              "the checker has to read the IN environment variable itself", file=sys.stderr)

    label = args.label or re.sub(r"[^A-Za-z0-9._-]+", "-", os.path.basename(words[0])).strip("-") or "checker"
    started = datetime.datetime.now(datetime.timezone.utc)

    def run_one(test: dict) -> dict:
        env = dict(base_env)
        env["IN"] = test["file"]
        command = template.replace("{IN}", shlex.quote(test["file"]))
        m = measure_mod.measure(command, shell=True, cwd=args.cwd, env=env,
                                timeout=test["timeout"], capture=True, use_perf=use_perf)
        status, correctness = classify(m["exit_code"], m["timed_out"], test["outcome"])
        stderr = m.get("stderr", "")
        if m["timed_out"]:
            stderr = f"Killed after exceeding the timeout of {test['timeout']:g}s.\n{stderr}"
        return {
            "test": test["name"],
            "expected": test["outcome"],
            "status": status,
            "correctness": correctness,
            "exit_code": m["exit_code"],
            "wall_time": m["wall_time_s"],
            "cpu_time": m["cpu_time_s"],
            "max_rss": m["max_rss_bytes"],
            "instructions": m["instructions"],
            "instructions_kernel": m["instructions_kernel"],
            "virtual_time": m["virtual_time_s"],
            "counter_coverage": m.get("counter_coverage"),
            "timeout": test["timeout"],
            "size": test["size"],
            "file": test["file"],
            "stdout": m.get("stdout", ""),
            "stderr": stderr,
        }

    marks = {"correct": "ok", "incorrect": "WRONG", "either": "either",
             "declined": "declined", "error": "ERROR"}
    results: list[dict] = []
    wall_start = time.time()
    with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, args.jobs)) as pool:
        futures = {pool.submit(run_one, test): test for test in tests}
        try:
            for done, future in enumerate(concurrent.futures.as_completed(futures), 1):
                result = future.result()
                results.append(result)
                if args.verbose:
                    print(f"[{done:>{len(str(len(tests)))}}/{len(tests)}] "
                          f"{marks[result['correctness']]:8} {result['test']:45} "
                          f"expected {result['expected']:6} got {result['status']:8} "
                          f"{fmt_seconds(result['wall_time']):>8}", flush=True)
        except KeyboardInterrupt:
            pool.shutdown(wait=False, cancel_futures=True)
            print("run-suite: interrupted", file=sys.stderr)
            return 130
    elapsed = time.time() - wall_start
    results.sort(key=lambda r: r["test"])
    summary = summarize(results)
    summary["elapsed"] = elapsed

    # ---- failures table ----
    failures = [r for r in results if r["correctness"] not in ("correct", "either")]
    if not args.quiet:
        for warning in warnings:
            print(f"run-suite: warning: {warning}", file=sys.stderr)
        if failures:
            print(f"\n{len(failures)} of {len(results)} tests not correct:")
            rows = []
            for r in failures:
                detail = last_line(r)
                rows.append([r["test"], r["expected"], r["status"],
                             marks[r["correctness"]], r["exit_code"], fmt_seconds(r["wall_time"]),
                             detail[:90] + ("..." if len(detail) > 90 else "")])
            print_table(rows, ["test", "expected", "status", "verdict", "exit", "wall", "last output line"])
            print()
        else:
            print(f"\nall {len(results)} tests correct"
                  + (f" ({summary['either_correct']} of them 'either')" if summary["either_tests"] else ""))

    perf_part = ""
    if summary["instructions"]:
        perf_part = (f" | instructions {summary['instructions']:.4g}"
                     f" = {summary['virtual_time']:.2f} virtual s")
        low = summary["min_counter_coverage"]
        if low is not None and low < 0.99:
            print(f"run-suite: WARNING: instruction counters covered only {low:.1%} of the CPU time "
                  f"in at least one run (multiplexed?); those counts are too low", file=sys.stderr)
    print(f"SUMMARY {label}: {summary['tests']} tests"
          f" | bad accepted {summary['reject_wrong']}/{summary['bad_tests']}"
          f" | good rejected {summary['accept_wrong']}/{summary['good_tests']}"
          f" | declined {summary['by_status'].get('declined', 0)}"
          f" error {summary['by_status'].get('error', 0)}"
          f" timeout {summary['by_status'].get('timeout', 0)}"
          f" | either {summary['either_correct']}/{summary['either_tests']}"
          f" | correct {summary['by_correctness'].get('correct', 0)}"
          f" | cpu {summary['cpu_time']:.1f}s wall {elapsed:.1f}s"
          f" max rss {fmt_bytes(summary['max_rss'])}{perf_part}")

    # ---- report ----
    report_path = args.report
    if not report_path:
        stamp = started.strftime("%Y%m%d-%H%M%S")
        report_path = os.path.join(ROOT, "work", "suite-reports", f"{label}-{stamp}.json")
    report_path = os.path.abspath(report_path)
    os.makedirs(os.path.dirname(report_path), exist_ok=True)
    report = {
        "meta": {
            "label": label,
            "command": template,
            "cwd": os.path.abspath(args.cwd) if args.cwd else os.getcwd(),
            "tests_dir": tests_dir,
            "meta_source": meta_path,
            "default_timeout": default_timeout,
            "arena_timeouts": args.arena_timeouts,
            "jobs": args.jobs,
            "perf": use_perf,
            "perf_detail": perf_detail,
            "instructions_per_second": measure_mod.INSTRUCTIONS_PER_SECOND,
            "started": started.isoformat(timespec="seconds"),
            "elapsed": elapsed,
            "host": platform.node(),
            "machine": platform.machine(),
            "cpus": len(os.sched_getaffinity(0)),
            "in_container": os.path.exists("/.dockerenv"),
            "warnings": warnings,
        },
        "summary": summary,
        "results": results,
    }
    with open(report_path, "w") as handle:
        json.dump(report, handle, indent=1)
    if not args.quiet:
        print(f"report: {report_path}")

    bad = (summary["by_correctness"].get("incorrect", 0) + summary["by_correctness"].get("error", 0))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
