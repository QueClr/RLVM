#!/usr/bin/env python3
"""bench/selftest.py - smoke test of run-suite.py and measure.py with fake checkers.

    bench/selftest.py                       # on the host: scoring only
    bench/arena-docker.sh bench/selftest.py # in the container: also perf

Checks, against the tests in work/arena-site/tests (or --tests):
  1. always-accept / always-reject / always-decline give exactly the scores
     that follow from the number of good and bad tests;
  2. a checker with mixed behaviour (accept, reject, decline, crash, hang) is
     classified test by test as the arena would;
  3. replaying the published exit status of every checker in the arena's
     results.json through run-suite.py reproduces the published status and
     correctness of every (checker, test) pair;
  4. `$IN` and `{IN}` both reach the checker;
  5. with perf available: every run has an instruction count, and measure.sh
     prints the documented JSON keys.
Exit status 0 if everything passed.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
RUN_SUITE = os.path.join(HERE, "run-suite.py")
FAKE = os.path.join(HERE, "fake-checkers")
sys.path.insert(0, HERE)
sys.dont_write_bytecode = True  # keep bench/ free of __pycache__
from measure import probe_perf  # noqa: E402

failures: list[str] = []


def check(condition: bool, what: str) -> None:
    print(f"  {'ok  ' if condition else 'FAIL'} {what}")
    if not condition:
        failures.append(what)


def suite(out_dir: str, name: str, command: list[str], *extra: str) -> tuple[int, dict]:
    report = os.path.join(out_dir, f"{name}.json")
    proc = subprocess.run(
        [sys.executable, RUN_SUITE, "-q", "--report", report, "--label", name, *extra, "--", *command],
        capture_output=True, text=True)
    if not os.path.exists(report):
        print(proc.stdout, proc.stderr, sep="\n", file=sys.stderr)
        raise SystemExit(f"run-suite.py produced no report for {name}")
    with open(report) as handle:
        return proc.returncode, json.load(handle)


def mixed_expectation(name: str, expected: str) -> str:
    """What bench/fake-checkers/mixed.sh does for a test, as an arena status."""
    kind = len(os.path.basename(name)) % 8
    if kind <= 2:
        return "accepted" if expected != "reject" else "rejected"
    return {3: "accepted", 4: "rejected", 5: "declined", 6: "error", 7: "timeout"}[kind]


def arena_correctness(status: str, expected: str) -> str:
    if status == "declined":
        return "declined"
    if status in ("error", "timeout"):
        return "error"
    if expected == "either":
        return "either"
    if (expected, status) in (("accept", "accepted"), ("reject", "rejected")):
        return "correct"
    return "incorrect"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--tests", default=os.path.join(ROOT, "work", "arena-site", "tests"))
    parser.add_argument("--results-json", default=os.path.join(ROOT, "work", "arena-site", "results.json"),
                        help="published arena results, for the replay check")
    parser.add_argument("--out", default=os.path.join(ROOT, "work", "selftest"))
    parser.add_argument("-j", "--jobs", default="8")
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)
    common = ["--tests", args.tests, "-j", args.jobs]
    perf_ok, perf_detail = probe_perf()
    print(f"perf: {'available (' + perf_detail + ')' if perf_ok else 'NOT available: ' + perf_detail}")

    # ---- 1. constant checkers ----
    print("1. constant fake checkers")
    rc, accept = suite(args.out, "always-accept", [os.path.join(FAKE, "always-accept.sh")], *common)
    s = accept["summary"]
    good, bad, either = s["good_tests"], s["bad_tests"], s["either_tests"]
    print(f"  ({s['tests']} tests: {good} good, {bad} bad, {either} either)")
    check(good > 0 and bad > 0, "the tests directory has good and bad tests")
    check(s["reject_wrong"] == bad and s["accept_wrong"] == 0 and s["accept_correct"] == good,
          f"always-accept: {bad} bad accepted, 0 good rejected")
    check(rc == 1, "always-accept: run-suite exits 1")
    rc, reject = suite(args.out, "always-reject", [os.path.join(FAKE, "always-reject.sh")], *common)
    s = reject["summary"]
    check(s["accept_wrong"] == good and s["reject_wrong"] == 0 and s["reject_correct"] == bad,
          f"always-reject: 0 bad accepted, {good} good rejected")
    check(all("always-reject: rejecting" in r["stderr"] for r in reject["results"]),
          "always-reject: stderr captured for every test")
    rc, decline = suite(args.out, "always-decline", [os.path.join(FAKE, "always-decline.sh")], *common)
    s = decline["summary"]
    check(s["declined_count"] == s["tests"] and s["reject_wrong"] == 0 and s["accept_wrong"] == 0
          and s["arena_sort_key"] == [0, 0, s["tests"]],
          "always-decline: everything declined, nothing wrong")
    check(rc == 0, "always-decline: run-suite exits 0 (declines are not failures)")

    # ---- 2. mixed checker, with timeouts ----
    print("2. mixed fake checker (accept / reject / decline / crash / hang), timeout 2 s")
    rc, mixed = suite(args.out, "mixed", [os.path.join(FAKE, "mixed.sh")], *common, "--timeout", "2",
                      "-j", "64")
    wrong = [r["test"] for r in mixed["results"]
             if r["status"] != mixed_expectation(r["test"], r["expected"])
             or r["correctness"] != arena_correctness(r["status"], r["expected"])]
    statuses = mixed["summary"]["by_status"]
    check(not wrong, f"every test classified as expected {statuses}" + (f"; wrong: {wrong[:5]}" if wrong else ""))
    check(set(statuses) == {"accepted", "rejected", "declined", "error", "timeout"},
          "all five statuses occur")
    check(all(r["wall_time"] < 10 for r in mixed["results"]), "hanging checkers were killed at the timeout")
    leftover = []
    for pid in filter(str.isdigit, os.listdir("/proc")):
        try:
            with open(f"/proc/{pid}/cmdline", "rb") as handle:
                if handle.read() == b"sleep\x0086399.25\x00":
                    leftover.append(pid)
        except OSError:
            pass
    check(not leftover, "no process of a timed-out checker survives" + (f": {leftover}" if leftover else ""))

    # ---- 3. replay the published results ----
    print("3. replay of the published arena results")
    if os.path.exists(args.results_json):
        with open(args.results_json) as handle:
            published = json.load(handle)
        names = {r["test"] for r in accept["results"]}
        per_checker: dict[str, dict] = {}
        for r in published["results"]:
            if r["test"] in names:
                per_checker.setdefault(r["checker"], {})[r["test"]] = r
        compared = mismatches = 0
        skipped = []
        for checker, rows in sorted(per_checker.items()):
            if set(rows) != names:
                skipped.append(checker)
                continue
            verdicts = {t: ("timeout" if r["status"] == "timeout" else r["exit_code"]) for t, r in rows.items()}
            map_file = os.path.join(args.out, f"replay-{checker}.map.json")
            with open(map_file, "w") as handle:
                json.dump(verdicts, handle)
            _, replay = suite(args.out, f"replay-{checker}",
                              [sys.executable, os.path.join(FAKE, "replay.py"), map_file],
                              *common, "--timeout", "2", "-j", "64")
            for r in replay["results"]:
                compared += 1
                want = rows[r["test"]]
                if (r["status"], r["correctness"]) != (want["status"], want["correctness"]):
                    mismatches += 1
                    if mismatches <= 5:
                        print(f"    mismatch {checker} {r['test']}: ours {r['status']}/{r['correctness']}, "
                              f"arena {want['status']}/{want['correctness']}")
        check(compared > 0 and mismatches == 0,
              f"{compared} (checker, test) pairs of {len(per_checker) - len(skipped)} checkers: "
              f"status and correctness equal the published ones ({mismatches} mismatches)")
        if skipped:
            print(f"  (skipped, published results do not cover exactly these tests: {', '.join(skipped)})")
    else:
        print(f"  skipped: {args.results_json} not found")

    # ---- 4. passing the export path ----
    print("4. $IN and {IN}")
    _, env_report = suite(args.out, "in-env", ['test -r "$IN"'], *common)
    check(env_report["summary"]["accept_correct"] == good, '"$IN": the path reaches the checker')
    _, placeholder_report = suite(args.out, "in-placeholder", ["test -r {IN}"], *common)
    check(placeholder_report["summary"]["accept_correct"] == good, "{IN}: the path reaches the checker")
    _, missing_report = suite(args.out, "in-missing", ['test -r "$IN.does-not-exist"'], *common)
    check(missing_report["summary"]["accept_wrong"] == good, "a wrong path is noticed (control)")

    # ---- 5. perf ----
    print("5. perf measurements")
    if perf_ok:
        rc, perf = suite(args.out, "always-accept-perf", [os.path.join(FAKE, "always-accept.sh")],
                         *common, "--perf")
        counts = [r["instructions"] for r in perf["results"]]
        check(all(isinstance(c, int) and c > 0 for c in counts),
              f"--perf: every run has an instruction count (min {min(counts)}, max {max(counts)})")
        check(perf["summary"]["instructions"] == sum(counts) and perf["meta"]["perf"] is True,
              "--perf: summary carries the total and virtual time")
        proc = subprocess.run([os.path.join(HERE, "measure.sh"), "--", "true"], capture_output=True, text=True)
        try:
            line = json.loads(proc.stdout)
        except json.JSONDecodeError:
            line = {}
        keys = {"exit_code", "instructions", "virtual_time_s", "cpu_time_s", "wall_time_s", "max_rss_bytes"}
        check(keys <= set(line) and line.get("instructions", 0) > 0
              and abs(line["virtual_time_s"] - line["instructions"] / 6e9) < 1e-12,
              "measure.sh prints one JSON line with the documented keys")
    else:
        rc = subprocess.run([sys.executable, RUN_SUITE, "-q", "--perf", "--", "true"],
                            capture_output=True, text=True).returncode
        check(rc == 3, "--perf without counters is refused with exit status 3 (no silent fallback)")
        print("  (perf part skipped: run this inside the container for it)")

    print(f"\n{'SELFTEST PASSED' if not failures else 'SELFTEST FAILED: ' + '; '.join(failures)}")
    return 0 if not failures else 1


if __name__ == "__main__":
    sys.exit(main())
