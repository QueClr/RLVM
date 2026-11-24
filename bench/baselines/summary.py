#!/usr/bin/env python3
"""bench/baselines/summary.py - one table over run-suite.py reports of the baselines.

    bench/baselines/summary.py [--dir work/baselines/small] [--results work/arena-site/results.json]
                               [--markdown] [--json FILE] [REPORT.json ...]

Reads the reports written by bench/baselines/validate.sh (default: every
*.json in work/baselines/small) and prints, per checker:

  correct / tests, bad accepted, good rejected, declined, error, timeout
      the arena's scoring, as run-suite.py computed it
  instr total, virt s
      user-space instructions summed over all tests, and / 6e9
  startup
      the smallest instruction count of any test: what the checker costs
      before it does any work (process start, runtime start-up, wrapper)
  median
      the median over the tests
  rss max
      the largest max RSS of any test
  = arena
      tests on which our status equals the status the arena published for the
      checker of the same name (for a variant such as "tenet-jvm-c2": of the
      checker it is a variant of), of those in common
  ours/arena
      our instruction total / the arena's, over the tests in common on which
      both runs have a count (aarch64 here, x86-64 there)

Python stdlib only; runs on the host or in the container.
"""
import argparse
import json
import statistics
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent


def fmt_count(n):
    if n is None:
        return "-"
    for unit, div in (("T", 1e12), ("G", 1e9), ("M", 1e6), ("k", 1e3)):
        if n >= div:
            return f"{n / div:.4g}{unit}"
    return str(int(n))


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("reports", nargs="*")
    ap.add_argument("--dir", default=str(ROOT / "work" / "baselines" / "small"))
    ap.add_argument("--results", default=str(ROOT / "work" / "arena-site" / "results.json"))
    ap.add_argument("--markdown", action="store_true", help="print a markdown table")
    ap.add_argument("--json", metavar="FILE", help="also write the rows as JSON")
    args = ap.parse_args()

    paths = [Path(p) for p in args.reports] or sorted(Path(args.dir).glob("*.json"))
    if not paths:
        print(f"no reports in {args.dir}", file=sys.stderr)
        return 1

    published = {}
    try:
        with open(args.results) as f:
            for r in json.load(f)["results"]:
                published.setdefault(r["checker"], {})[r["test"]] = r
    except (OSError, ValueError, KeyError) as e:
        print(f"warning: cannot read published results ({e})", file=sys.stderr)

    rows = []
    for path in paths:
        with open(path) as f:
            rep = json.load(f)
        if "summary" not in rep or "results" not in rep:
            continue
        s, meta, results = rep["summary"], rep["meta"], rep["results"]
        label = meta.get("label") or path.stem
        by_status = s.get("by_status", {})
        counts = [r["instructions"] for r in results if r.get("instructions")]
        # "tenet-jvm-c2" is a variant of the arena's "tenet-jvm": drop trailing
        # "-part"s (and anything after '@') until a published checker matches
        pub_name = label.split("@")[0]
        while pub_name not in published and "-" in pub_name:
            pub_name = pub_name.rsplit("-", 1)[0]
        pub = published.get(pub_name, {})
        common = [r for r in results if r["test"] in pub]
        same = sum(1 for r in common if pub[r["test"]].get("status") == r["status"])
        both = [(r["instructions"], pub[r["test"]]["instructions"]) for r in common
                if r.get("instructions") and pub[r["test"]].get("instructions")]
        rows.append({
            "checker": label,
            "report": str(path),
            "tests": s["tests"],
            "correct": s.get("by_correctness", {}).get("correct", 0),
            "bad_accepted": s["bad_accepted"],
            "good_rejected": s["good_rejected"],
            "declined": by_status.get("declined", 0),
            "error": by_status.get("error", 0),
            "timeout": by_status.get("timeout", 0),
            "instructions": s.get("instructions"),
            "virtual_time": s.get("virtual_time"),
            "startup_instructions": min(counts) if counts else None,
            "median_instructions": int(statistics.median(counts)) if counts else None,
            "max_rss": s.get("max_rss"),
            "jobs": meta.get("jobs"),
            "published_common": len(common),
            "published_same_status": same,
            "ours_over_arena": (sum(a for a, _ in both) / sum(b for _, b in both)) if both else None,
        })

    rows.sort(key=lambda r: (r["bad_accepted"], r["good_rejected"], r["instructions"] or 0))
    head = ["checker", "correct", "bad acc", "good rej", "declined", "error", "timeout",
            "instr total", "virt s", "startup", "median", "rss max", "= arena", "ours/arena"]
    table = []
    for r in rows:
        table.append([
            r["checker"], f'{r["correct"]}/{r["tests"]}', str(r["bad_accepted"]), str(r["good_rejected"]),
            str(r["declined"]), str(r["error"]), str(r["timeout"]),
            fmt_count(r["instructions"]),
            "-" if r["virtual_time"] is None else f'{r["virtual_time"]:.2f}',
            fmt_count(r["startup_instructions"]), fmt_count(r["median_instructions"]),
            "-" if not r["max_rss"] else f'{r["max_rss"] / 2**20:.0f}M',
            f'{r["published_same_status"]}/{r["published_common"]}' if r["published_common"] else "-",
            "-" if r["ours_over_arena"] is None else f'{r["ours_over_arena"]:.3f}',
        ])
    if args.markdown:
        print("| " + " | ".join(head) + " |")
        print("|" + "|".join(["---"] * len(head)) + "|")
        for row in table:
            print("| " + " | ".join(row) + " |")
    else:
        widths = [max(len(head[i]), *(len(row[i]) for row in table)) for i in range(len(head))]
        print("  ".join(h.ljust(w) for h, w in zip(head, widths)))
        print("  ".join("-" * w for w in widths))
        for row in table:
            print("  ".join(c.ljust(w) for c, w in zip(row, widths)))
    if args.json:
        with open(args.json, "w") as f:
            json.dump(rows, f, indent=2)
            f.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
