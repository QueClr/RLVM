#!/usr/bin/env python3
"""bench/compare-published.py - compare a run-suite.py report with the results
the arena published for one of its checkers.

    bench/compare-published.py REPORT.json --checker official

For a checker that we built ourselves from the arena's definition this shows
how our aarch64 numbers relate to the arena's x86-64 numbers: the verdicts
should be identical, and the ratio of instruction counts tells how to read
our measurements against the published table. Python stdlib only; reads
work/arena-site/results.json unless --results says otherwise.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import statistics
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("report", help="JSON report written by run-suite.py --perf")
    parser.add_argument("--checker", required=True, help="checker name in the arena's results.json")
    parser.add_argument("--results", default=os.path.join(ROOT, "work", "arena-site", "results.json"))
    parser.add_argument("--min-instructions", type=float, default=3e9,
                        help="tests above this published count are 'heavy' and listed (default 3e9)")
    args = parser.parse_args()

    with open(args.report) as handle:
        ours = json.load(handle)
    with open(args.results) as handle:
        published = {r["test"]: r for r in json.load(handle)["results"] if r["checker"] == args.checker}
    if not published:
        print(f"no results for checker {args.checker!r} in {args.results}", file=sys.stderr)
        return 2

    rows = []
    disagree = []
    for r in ours["results"]:
        p = published.get(r["test"])
        if p is None:
            continue
        if r["status"] != p["status"]:
            disagree.append((r["test"], r["status"], p["status"]))
        if r.get("instructions") and p.get("instructions"):
            rows.append((r["test"], r["instructions"], p["instructions"],
                         r.get("max_rss") or 0, p.get("max_rss") or 0))
    common = sum(1 for r in ours["results"] if r["test"] in published)
    print(f"report: {ours['meta']['label']} ({ours['meta']['command']})")
    print(f"published: checker {args.checker}, {len(published)} results; {common} tests in common")
    print(f"verdicts: {common - len(disagree)} of {common} equal")
    for test, mine, theirs in disagree[:20]:
        print(f"  differs: {test}: ours {mine}, arena {theirs}")
    if not rows:
        print("no instruction counts to compare (was the report made with --perf?)")
        return 1 if disagree else 0

    def describe(label: str, selection: list) -> None:
        if not selection:
            return
        ratios = [o / p for _, o, p, _, _ in selection]
        total_ours = sum(o for _, o, _, _, _ in selection)
        total_pub = sum(p for _, _, p, _, _ in selection)
        geomean = math.exp(statistics.fmean(math.log(x) for x in ratios))
        print(f"{label}: {len(selection)} tests, instructions ours/arena: "
              f"total {total_ours / total_pub:.3f}, median {statistics.median(ratios):.3f}, "
              f"geomean {geomean:.3f}, min {min(ratios):.3f}, max {max(ratios):.3f}")

    heavy = [row for row in rows if row[2] >= args.min_instructions]
    describe("all tests  ", rows)
    describe("heavy tests", heavy)
    if heavy:
        print(f"\n{'heavy test':42} {'ours':>11} {'arena':>11} {'ratio':>6} {'rss ours':>9} {'rss arena':>9}")
        for test, o, p, rss_o, rss_p in sorted(heavy, key=lambda row: -row[2]):
            print(f"{test:42} {o:11.4g} {p:11.4g} {o / p:6.3f} {rss_o / 2**20:8.0f}M {rss_p / 2**20:8.0f}M")
    return 1 if disagree else 0


if __name__ == "__main__":
    sys.exit(main())
