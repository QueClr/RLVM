#!/usr/bin/env python3
"""scripts/slice-report.py REPORT.json OUT.md [BUILD-INFO.txt]

Renders a bench/run-suite.py report as the steward's end-to-end table (work/status/slice.md): one
summary line, the failures grouped by the checker's own last message (so that an owner finds every
test that fails for "his" reason in one place), then every test. Exit status 0 if all selected
tests are correct, 1 otherwise.
"""
import datetime
import json
import re
import sys


def last_line(result):
    for stream in ("stderr", "stdout"):
        lines = [ln.strip() for ln in (result.get(stream) or "").splitlines() if ln.strip()]
        if lines:
            return lines[-1]
    return ""


def first_rlvm_line(result):
    """The checker's own verdict line ('rlvm: ...'), else the last line of output."""
    for ln in (result.get("stderr") or "").splitlines():
        ln = ln.strip()
        if ln.startswith("rlvm: "):
            return ln
    return last_line(result)


def normalise(message):
    """Group key: the message without the parts that differ from test to test."""
    m = re.sub(r"'[^']*'", "'_'", message)
    m = re.sub(r"`[^`]*`", "`_`", m)
    m = re.sub(r"\b(unit|line|cid|term|rank|constant|declaration) #?\d+", r"\1 N", m)
    m = re.sub(r"\b\d+\b", "N", m)
    return m[:160]


def area_of(message):
    """Best guess at the owning module from the words of a message (a hint, not a verdict)."""
    low = message.lower()
    table = (
        ("not implemented", "skeleton / deferred part"),
        ("nosuchmethod", "published jars out of step: a downstream module must republish"),
        ("nosuchfield", "published jars out of step: a downstream module must republish"),
        ("outofmemory", "memory: engine arenas / literal pool (rlvm-ref, rlvm-store, rlvm-api bindings)"),
        ("stackoverflow", "rlvm-ref (recursion depth) or stack size in the driver"),
        ("budget_memory", "rlvm-ref memory use / driver budgets"),
        ("budget_steps", "rlvm-ref / driver budgets"),
        ("stack_depth", "rlvm-ref (recursion depth limit)"),
        ("unbound reference", "rlvm-load / rlvm-store (name binding inside a unit), reported by rlvm-admit"),
        ("gave up", "rlvm-admit / rlvm-inductive (exact-or-decline)"),
        ("timeout", "engine speed (rlvm-ref)"),
        ("malformed export", "rlvm-export / rlvm-load (input)"),
        ("unsupported export", "rlvm-export / rlvm-load (input)"),
        ("loader", "rlvm-load"),
        ("inductive", "rlvm-inductive / rlvm-admit"),
        ("recursor", "rlvm-inductive / rlvm-admit"),
        ("record_mismatch", "rlvm-inductive / rlvm-admit"),
        ("quot", "rlvm-inductive / rlvm-admit"),
        ("structural", "rlvm-admit"),
        ("forward_reference", "rlvm-admit / rlvm-load"),
        ("prelude", "rlvm-admit"),
        ("internal_error", "engine (rlvm-ref) or driver"),
        ("exception", "see message"),
        ("mismatch", "rlvm-ref (kernel judgment)"),
        ("expected", "rlvm-ref (kernel judgment)"),
    )
    for word, area in table:
        if word in low:
            return area
    return "?"


def esc(text):
    return text.replace("|", "\\|").replace("\n", " ")


def main():
    report_path, out_path = sys.argv[1], sys.argv[2]
    info_path = sys.argv[3] if len(sys.argv) > 3 else None
    with open(report_path) as handle:
        report = json.load(handle)
    results = sorted(report["results"], key=lambda r: (r["expected"], r["test"]))
    summary = report["summary"]
    meta = report["meta"]
    correct = [r for r in results if r["correctness"] == "correct"]
    wrong = [r for r in results if r["correctness"] == "incorrect"]
    other = [r for r in results if r["correctness"] not in ("correct", "incorrect")]

    out = []
    now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    out.append("# End-to-end slice (steward)")
    out.append("")
    out.append(f"Generated {now} by `scripts/slice.sh`; raw report `{report_path}`.")
    out.append("")
    out.append(f"Command per test: `{meta['command']}`")
    out.append("")
    out.append(f"**{len(correct)} / {len(results)} correct** | WRONG VERDICTS {len(wrong)} "
               f"(bad accepted {summary['reject_wrong']}, good rejected {summary['accept_wrong']}) | "
               f"declined {summary['by_status'].get('declined', 0)}, error {summary['by_status'].get('error', 0)}, "
               f"timeout {summary['by_status'].get('timeout', 0)} | good tests {summary['good_tests']}, "
               f"bad tests {summary['bad_tests']} | cpu {summary['cpu_time']:.1f} s, wall {meta['elapsed']:.1f} s "
               f"({meta['jobs']} at a time)")
    out.append("")
    if info_path:
        try:
            with open(info_path) as handle:
                lines = handle.read().splitlines()
            jars = [ln for ln in lines if ln.startswith("  rlvm-")]
            if jars:
                out.append("Jars in the distribution (module, sha256 prefix, when its owner published it):")
                out.append("")
                out.append("```")
                out.extend(jars)
                out.append("```")
                out.append("")
        except OSError:
            pass

    if wrong:
        out.append("## WRONG VERDICTS (soundness or completeness bugs: fix first)")
        out.append("")
        out.append("| test | expected | got | checker's message |")
        out.append("|---|---|---|---|")
        for r in wrong:
            out.append(f"| {r["test"]} | {r['expected']} | {r['status']} | {esc(first_rlvm_line(r))[:200]} |")
        out.append("")

    failing = wrong + other
    if failing:
        groups = {}
        for r in failing:
            groups.setdefault(normalise(first_rlvm_line(r)), []).append(r)
        out.append("## Failures grouped by message")
        out.append("")
        out.append("| count | status | area (guess) | message | tests |")
        out.append("|---:|---|---|---|---|")
        for key, rs in sorted(groups.items(), key=lambda kv: -len(kv[1])):
            statuses = ",".join(sorted({r["status"] for r in rs}))
            names = ", ".join(r["test"] for r in rs[:12]) + (f", ... (+{len(rs) - 12})" if len(rs) > 12 else "")
            out.append(f"| {len(rs)} | {statuses} | {area_of(key)} | {esc(key)} | {esc(names)} |")
        out.append("")

    out.append("## Every test")
    out.append("")
    out.append("| test | expected | status | result | wall | message |")
    out.append("|---|---|---|---|---:|---|")
    order = {"incorrect": 0, "error": 1, "declined": 2, "either": 3, "correct": 4}
    for r in sorted(results, key=lambda r: (order.get(r["correctness"], 1), r["expected"], r["test"])):
        wall = r.get("wall_time")
        wall_s = "-" if wall is None else (f"{wall * 1000:.0f} ms" if wall < 1 else f"{wall:.2f} s")
        msg = "" if r["correctness"] == "correct" else esc(first_rlvm_line(r))[:160]
        mark = {"correct": "ok", "incorrect": "**WRONG**"}.get(r["correctness"], r["correctness"])
        out.append(f"| {r["test"]} | {r['expected']} | {r['status']} | {mark} | {wall_s} | {msg} |")
    out.append("")
    with open(out_path, "w") as handle:
        handle.write("\n".join(out))
    print(f"slice: {len(correct)}/{len(results)} correct, {len(wrong)} wrong, {len(other)} declined/error/timeout"
          f" -> {out_path}")
    return 0 if len(correct) == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
