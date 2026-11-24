#!/usr/bin/env python3
"""scripts/platform-report.py - tables of the platform comparison (docs/PLATFORM.md).

    scripts/platform-report.py [TAG...]                 print the tables of these result files
    scripts/platform-report.py --update docs/PLATFORM.md
                                                         rewrite every block
                                                             <!-- BEGIN table:TAG[:corpus,...][:threads][:config,...] -->
                                                             <!-- END table:... -->       (same text after END)
                                                         of the document from work/steward/platform/results/TAG.jsonl;
                                                         also  ratio:TAG:CONTROL[:corpora][:threads]  (paired
                                                         ratios against one configuration) and
                                                         legend:TAG[:config,...]  (what each configuration is),
                                                         scaling:TAG[:config,...][:BASE]  (CPU time and wall
                                                         clock by worker count, relative to BASE workers)

Input: the JSON lines written by scripts/platform-matrix.py. One table row per (corpus, workers,
configuration), columns in the project's order: CPU time, wall clock, peak RSS, instructions.

The figure of a row with several runs is the MINIMUM CPU time and wall clock (other jobs on the host
can only add time, never remove any - bench/track/README.md), with the median in parentheses; peak
RSS is the maximum, instructions the median. `load` is the largest 1-minute host load average seen
at the end of a run of the row: a value far above the worker count means the row was disturbed.
Runs that did not end with status 0 are listed as such and never enter a figure.

Python stdlib only.
"""

from __future__ import annotations

import json
import os
import re
import statistics
import sys

ROOT = os.path.realpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
RESULTS = os.path.join(ROOT, "work/steward/platform/results")
CORPUS_ORDER = ["init", "std", "cedar-hot-ax", "cedar", "reachability", "cslib", "con-leche", "navier-stokes-euler", "mathlib"]


def load(tag: str) -> list[dict]:
    path = os.path.join(RESULTS, tag + ".jsonl")
    rows = []
    try:
        with open(path) as f:
            for line in f:
                line = line.strip()
                if line:
                    rows.append(json.loads(line))
    except OSError:
        pass
    return rows


def fmt_s(x: float) -> str:
    return "%.1f" % x if x >= 100 else "%.2f" % x


def fmt_rss(b: float) -> str:
    return "%.2f GB" % (b / 2**30) if b >= 2**30 else "%.0f MB" % (b / 2**20)


def fmt_instr(n: float | None) -> str:
    if n is None:
        return "n/a"
    return "{:,.0f} G".format(n / 1e9) if n >= 1e12 else "%.1f G" % (n / 1e9)


def table(rows: list[dict], corpora: list[str] | None = None, threads: list[int] | None = None,
          configs: list[str] | None = None, mark_best: bool = True) -> str:
    groups: dict[tuple, list[dict]] = {}
    order: list[str] = []
    for r in rows:
        if corpora and r["corpus"] not in corpora:
            continue
        if threads and r["threads"] not in threads:
            continue
        if configs and r["config"] not in configs:
            continue
        if r["config"] not in order:
            order.append(r["config"])
        groups.setdefault((r["corpus"], r["threads"], r["config"]), []).append(r)
    if not groups:
        return "(no measurements yet)\n"
    if configs:
        order = [c for c in configs if c in order]
    show_gc = any("gc_young" in r for g in groups.values() for r in g)
    head = ["Corpus", "Workers", "Configuration", "CPU s", "Wall s", "Peak RSS", "Instructions"]
    if show_gc:
        head.append("Collections (young / full; pause)")
    head += ["Runs", "Load"]
    out = ["| " + " | ".join(head) + " |", "|---|--:|---|--:|--:|--:|--:|" + ("---|" if show_gc else "") + "--:|--:|"]
    cs = sorted({k[0] for k in groups}, key=lambda c: (CORPUS_ORDER.index(c) if c in CORPUS_ORDER else 99, c))
    ts = sorted({k[1] for k in groups})
    for corpus in cs:
        for t in ts:
            best_cpu = None
            cells = []
            for c in order:
                g = groups.get((corpus, t, c))
                if not g:
                    continue
                ok = [r for r in g if r.get("exit_code") == 0 and not r.get("timed_out")]
                bad = len(g) - len(ok)
                if not ok:
                    cells.append((c, None, g, bad))
                    continue
                cpu = min(r["cpu_time_s"] for r in ok)
                if best_cpu is None or cpu < best_cpu:
                    best_cpu = cpu
                cells.append((c, ok, g, bad))
            for c, ok, g, bad in cells:
                if ok is None:
                    codes = ",".join(str("t/o" if r.get("timed_out") else r.get("exit_code")) for r in g)
                    rss = max((r.get("max_rss_bytes") or 0) for r in g)
                    row = [corpus, str(t), "`%s`" % c, "FAILED (exit %s)" % codes,
                           fmt_s(min(r.get("wall_time_s") or 0 for r in g)), fmt_rss(rss), "-"]
                    if show_gc:
                        row.append("-")
                    row += [str(len(g)), "%.0f" % max(r.get("loadavg_after", 0) for r in g)]
                    out.append("| " + " | ".join(row) + " |")
                    continue
                cpus = [r["cpu_time_s"] for r in ok]
                walls = [r["wall_time_s"] for r in ok]
                cpu = min(cpus)
                cpu_txt = fmt_s(cpu)
                wall_txt = fmt_s(min(walls))
                if len(ok) > 1:
                    cpu_txt += " (%s)" % fmt_s(statistics.median(cpus))
                    wall_txt += " (%s)" % fmt_s(statistics.median(walls))
                if mark_best and best_cpu is not None and cpu == best_cpu and len(cells) > 1:
                    cpu_txt = "**" + cpu_txt + "**"
                ins = [r["instructions"] for r in ok if r.get("instructions") is not None]
                row = [corpus, str(t), "`%s`" % c, cpu_txt, wall_txt,
                       fmt_rss(max(r.get("max_rss_bytes") or 0 for r in ok)),
                       fmt_instr(statistics.median(ins) if ins else None)]
                if show_gc:
                    if "gc_young" in ok[0]:
                        y = statistics.median(r.get("gc_young", 0) for r in ok)
                        f = statistics.median(r.get("gc_full", 0) for r in ok)
                        p = statistics.median(r.get("gc_pause_ms", 0) for r in ok)
                        o = statistics.median(r.get("gc_other_pauses", 0) for r in ok)
                        row.append("%d / %d%s; %s" % (y, f, " (+%d)" % o if o else "",
                                                     "%.1f s" % (p / 1000) if p >= 1000 else "%.0f ms" % p))
                    else:
                        row.append("n.r.")
                row += ["%d%s" % (len(ok), " (+%d failed)" % bad if bad else ""),
                        "%.0f" % max(r.get("loadavg_after", 0) for r in ok)]
                out.append("| " + " | ".join(row) + " |")
    return "\n".join(out) + "\n"


def ratios(rows: list[dict], control: str, corpora: list[str] | None = None, threads: list[int] | None = None) -> str:
    """Paired comparison with `control`: the configurations of one group (corpus, workers, repeat) ran
    back to back under the same host load, so the ratio of two runs of a group is far less sensitive
    to other jobs than the absolute figures. Per configuration: the median over all groups of
    (configuration / control), for CPU time, wall clock, peak RSS and instructions, and the range of
    the CPU-time ratio."""
    groups: dict[tuple, dict[str, dict]] = {}
    order: list[str] = []
    for r in rows:
        if r.get("exit_code") != 0 or r.get("timed_out"):
            continue
        if corpora and r["corpus"] not in corpora:
            continue
        if threads and r["threads"] not in threads:
            continue
        if r["config"] not in order:
            order.append(r["config"])
        groups.setdefault((r["corpus"], r["threads"], r["run"]), {})[r["config"]] = r
    out = ["| Configuration | CPU time / `%s` (median; range) | Wall / `%s` | Peak RSS / `%s` | Instructions / `%s` | Pairs |"
           % (control, control, control, control), "|---|--:|--:|--:|--:|--:|"]
    for c in order:
        if c == control:
            continue
        cpu, wall, rss, ins = [], [], [], []
        for g in groups.values():
            if c in g and control in g:
                a, b = g[c], g[control]
                cpu.append(a["cpu_time_s"] / b["cpu_time_s"])
                wall.append(a["wall_time_s"] / b["wall_time_s"])
                rss.append(a["max_rss_bytes"] / b["max_rss_bytes"])
                if a.get("instructions") and b.get("instructions"):
                    ins.append(a["instructions"] / b["instructions"])
        if not cpu:
            continue
        out.append("| `%s` | %.3f (%.3f - %.3f) | %.3f | %.2f | %s | %d |" % (
            c, statistics.median(cpu), min(cpu), max(cpu), statistics.median(wall), statistics.median(rss),
            "%.3f" % statistics.median(ins) if ins else "n/a", len(cpu)))
    if len(out) == 2:
        return "(no pairs yet)\n"
    return "\n".join(out) + "\n"


def scaling(rows: list[dict], configs: list[str] | None = None, base: int = 1) -> str:
    """Worker scaling per (corpus, configuration): CPU time and wall clock at every measured worker
    count (minimum over the repeats), and what more workers cost and buy against `base` workers:
    CPU time relative to `base` (the price: above 1.00 = the workers disturb each other or idle
    threads are billed) and the speed-up in wall clock. The question behind the run line's
    `--threads`."""
    groups: dict[tuple, list[dict]] = {}
    order: list[str] = []
    for r in rows:
        if r.get("exit_code") != 0 or r.get("timed_out"):
            continue
        if configs and r["config"] not in configs:
            continue
        if r["config"] not in order:
            order.append(r["config"])
        groups.setdefault((r["corpus"], r["config"], r["threads"]), []).append(r)
    if not groups:
        return "(no measurements yet)\n"
    if configs:
        order = [c for c in configs if c in order]
    ts = sorted({k[2] for k in groups})
    cs = sorted({k[0] for k in groups}, key=lambda c: (CORPUS_ORDER.index(c) if c in CORPUS_ORDER else 99, c))
    head = ["Corpus", "Configuration"] + ["CPU s, %d w" % t for t in ts] + ["Wall s, %d w" % t for t in ts] \
        + ["CPU %d w / %d w" % (t, base) for t in ts if t != base] + ["Wall %d w / %d w" % (base, t) for t in ts if t != base] \
        + ["Peak RSS, %d w" % ts[-1], "Runs"]
    out = ["| " + " | ".join(head) + " |", "|---|---|" + "--:|" * (len(head) - 2)]
    for corpus in cs:
        for c in order:
            cpu = {t: min(r["cpu_time_s"] for r in groups[(corpus, c, t)]) for t in ts if (corpus, c, t) in groups}
            wall = {t: min(r["wall_time_s"] for r in groups[(corpus, c, t)]) for t in ts if (corpus, c, t) in groups}
            if not cpu:
                continue
            row = [corpus, "`%s`" % c] + [fmt_s(cpu[t]) if t in cpu else "-" for t in ts] \
                + [fmt_s(wall[t]) if t in wall else "-" for t in ts]
            row += ["%.2f" % (cpu[t] / cpu[base]) if t in cpu and base in cpu else "-" for t in ts if t != base]
            row += ["%.2f" % (wall[base] / wall[t]) if t in wall and base in wall else "-" for t in ts if t != base]
            top = max(t for t in ts if (corpus, c, t) in groups)
            row.append(fmt_rss(max(r.get("max_rss_bytes") or 0 for r in groups[(corpus, c, top)])))
            row.append("/".join(str(len(groups.get((corpus, c, t), []))) for t in ts))
            out.append("| " + " | ".join(row) + " |")
    return "\n".join(out) + "\n"


def legend(rows: list[dict], configs: list[str] | None = None) -> str:
    seen: dict[str, dict] = {}
    for r in rows:
        if configs and r["config"] not in configs:
            continue
        seen.setdefault(r["config"], r)
    out = []
    for name, r in seen.items():
        flags = r.get("java_opts") or r.get("args") or ""
        if r["binary"] == "rlvm-jvm" and not r.get("java_opts"):
            flags = "the launcher's defaults (`work/steward/platform/dist/BUILD-INFO.txt`, line `jvm flags`)"
        elif flags:
            flags = "`" + flags.replace("-XX:+ExitOnOutOfMemoryError --enable-native-access=ALL-UNNAMED "
                                        "--sun-misc-unsafe-memory-access=allow ", "") + "`"
        out.append("- `%s`: %s%s" % (name, r.get("what", ""), (" - " + flags) if flags else ""))
    return "\n".join(out) + "\n"


def render(spec: str) -> str:
    """spec = TAG[+TAG][:corpus,corpus][:threads,threads][:config,config][:plain] ('-' = no restriction;
    `plain` = do not mark the smallest CPU time, for tables whose rows were not measured back to back)."""
    parts = spec.split(":")
    plain = parts[-1] == "plain"
    if plain:
        parts = parts[:-1]
    tag = parts[0]
    corpora = parts[1].split(",") if len(parts) > 1 and parts[1] not in ("", "-") else None
    threads = [int(t) for t in parts[2].split(",")] if len(parts) > 2 and parts[2] not in ("", "-") else None
    configs = parts[3].split(",") if len(parts) > 3 and parts[3] not in ("", "-") else None
    rows = []
    for t in tag.split("+"):
        rows += load(t)
    return table(rows, corpora, threads, configs, not plain)


def main() -> int:
    args = sys.argv[1:]
    if args and args[0] == "--update":
        path = args[1]
        with open(path) as f:
            text = f.read()

        def repl(m: re.Match) -> str:
            kind, spec = m.group(1), m.group(2)
            if kind == "table":
                body = render(spec)
            elif kind == "ratio":
                # ratio:TAG:CONTROL[:corpus,...][:threads,...]
                parts = spec.split(":")
                rows = []
                for t in parts[0].split("+"):
                    rows += load(t)
                body = ratios(rows, parts[1],
                              parts[2].split(",") if len(parts) > 2 and parts[2] not in ("", "-") else None,
                              [int(t) for t in parts[3].split(",")] if len(parts) > 3 and parts[3] not in ("", "-") else None)
            elif kind == "scaling":
                # scaling:TAG[:config,...][:base workers]
                parts = spec.split(":")
                rows = []
                for t in parts[0].split("+"):
                    rows += load(t)
                body = scaling(rows, parts[1].split(",") if len(parts) > 1 and parts[1] not in ("", "-") else None,
                               int(parts[2]) if len(parts) > 2 and parts[2] not in ("", "-") else 1)
            else:
                parts = spec.split(":")
                rows = []
                for t in parts[0].split("+"):
                    rows += load(t)
                body = legend(rows, parts[1].split(",") if len(parts) > 1 and parts[1] not in ("", "-") else None)
            return "<!-- BEGIN %s:%s -->\n%s<!-- END %s:%s -->" % (kind, spec, body, kind, spec)

        new = re.sub(r"<!-- BEGIN (table|legend|ratio|scaling):(\S+) -->\n.*?<!-- END \1:\2 -->", repl, text, flags=re.S)
        if new != text:
            with open(path, "w") as f:
                f.write(new)
        print("platform-report: %s %s" % (path, "updated" if new != text else "unchanged"))
        return 0
    if not args:
        args = sorted(f[:-6] for f in os.listdir(RESULTS) if f.endswith(".jsonl")) if os.path.isdir(RESULTS) else []
    for spec in args:
        print("### %s\n" % spec)
        print(render(spec))
    return 0


if __name__ == "__main__":
    sys.exit(main())
