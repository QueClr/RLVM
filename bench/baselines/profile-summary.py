#!/usr/bin/env python3
"""bench/baselines/profile-summary.py - per-declaration cost profiles of the
baselines on mathlib, from the outputs of bench/baselines/profile-mathlib.sh.

    bench/baselines/profile-summary.py [--dir work/baselines/profile]
                                       [--out work/baselines/mathlib-profile] [--top 200]

Writes
    OUT.json                 summary: per profile the totals, the cost split between
                             reading the export and checking, the cumulative
                             distribution, the cost by kind and by namespace, the
                             TOP most expensive declarations; agreement between
                             checkers; thread scaling (from work/baselines/big/runs.jsonl)
    OUT.PROFILE.tsv.gz       every declaration of that profile, most expensive first
    OUT.top200.tsv           the TOP declarations of every profile side by side
    OUT.by-name.tsv.gz       one row per declaration name: its cost in every single-worker
                             profile (join your own per-declaration numbers against this)

Profiles (whichever of them exist in --dir):
    nano-lean.j1, nano-lean.j4    nl-fast --trace + instrtap: instructions between the
                                  "start" and "end" line of a declaration
    gruszka.w1, gruszka.w4        SK_DECL_INSTR=1 + instrtap: instructions between two
                                  consecutive "DI" lines of a worker (this includes
                                  printing the previous line, see overhead_per_declaration)
    rpylean                       --print jsonl + instrtap: instructions between two
                                  consecutive lines (reading the declaration + checking it)
    sokonanoda.t1, sokonanoda.t4  --verbose: wall time per declaration only

Python stdlib only.
"""

from __future__ import annotations

import argparse
import collections
import gzip
import json
import os
import re
import sys

ROOT = os.path.realpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
IPS = 6_000_000_000

_DUR = re.compile(r"^([0-9.]+)(ns|µs|us|ms|s)$")
_UNIT = {"ns": 1, "µs": 1_000, "us": 1_000, "ms": 1_000_000, "s": 1_000_000_000}


def duration_ns(text: str) -> int | None:
    """Rust's Debug format of a Duration ("175.809µs", "5.23s") in ns."""
    m = _DUR.match(text)
    return int(float(m.group(1)) * _UNIT[m.group(2)]) if m else None


def load_measure(directory: str, name: str) -> dict | None:
    try:
        with open(os.path.join(directory, name + ".measure.json")) as handle:
            return json.load(handle)
    except (OSError, ValueError):
        return None


# ---- parsers: each returns (rows, info); a row is a dict with at least name and cost -------------

def parse_nano_lean(path: str):
    rows, started, last = [], {}, {}
    info: dict = {"skipped_lines": 0}
    with open(path, errors="replace") as handle:
        pending = None  # (tid, count) of the "#I" line just read
        for line in handle:
            if line.startswith("#I "):
                parts = line.split()
                pending = (parts[1], int(parts[2]))
                continue
            if line.startswith("start ") and pending:
                _, idx, name = line.rstrip("\n").split(" ", 2)
                started[idx] = (pending[0], pending[1], name)
            elif line.startswith("end ") and pending:
                parts = line.split()
                st = started.pop(parts[1], None)
                if st and st[0] == pending[0] and len(parts) >= 4:
                    rows.append({"name": st[2], "instructions": pending[1] - st[1],
                                 "wall_ns": duration_ns(parts[3]), "thread": st[0]})
                    last[st[0]] = pending[1]
                else:
                    info["skipped_lines"] += 1
            elif line.startswith("import "):
                info["import_line"] = line.strip()
                info["import_wall_ns"] = duration_ns(line.split()[1])
            elif line.startswith("experimental checks "):
                info["checks_line"] = line.strip()
                info["checks_wall_ns"] = duration_ns(line.split()[2].rstrip(":"))
            elif line.strip():
                info["skipped_lines"] += 1
            pending = None
    info["threads"] = len(last)
    info["marked_span_instructions"] = sum(last.values())  # first start .. last end, per worker
    return rows, info


GRUSZKA_COUNTERS = ("ticks", "whnf", "unfold", "deq", "deq_hit", "infer", "infer_calls", "eval", "interns")


def parse_gruszka(path: str):
    rows, prev = [], {}
    info: dict = {"skipped_lines": 0, "first_lines_without_count": 0}
    with open(path, errors="replace") as handle:
        pending = None
        for line in handle:
            if line.startswith("#I "):
                parts = line.split()
                pending = (parts[1], int(parts[2]))
                continue
            if line.startswith("DI\t") and pending:
                f = line.rstrip("\n").split("\t", 14)
                row = {"name": f[2], "wall_ns": int(f[4]), "thread": pending[0]}
                for field in f[5:14]:
                    key, _, value = field.rpartition(" ")
                    if key in GRUSZKA_COUNTERS:
                        row[key] = int(value)
                if pending[0] in prev:
                    row["instructions"] = pending[1] - prev[pending[0]]
                else:  # the worker's counter starts at its first line
                    row["instructions"] = None
                    info["first_lines_without_count"] += 1
                prev[pending[0]] = pending[1]
                rows.append(row)
            elif line.strip():
                info["skipped_lines"] += 1
            pending = None
    info["threads"] = len(prev)
    info["marked_span_instructions"] = sum(prev.values())
    return rows, info


def parse_rpylean(path: str):
    rows, prev = [], None
    info: dict = {"skipped_lines": 0}
    with open(path, errors="replace") as handle:
        pending = None
        for line in handle:
            if line.startswith("#I "):
                pending = int(line.split()[2])
                continue
            if line.startswith('{"name":') and pending is not None:
                try:
                    d = json.loads(line)
                except ValueError:
                    info["skipped_lines"] += 1
                    pending = None
                    continue
                rows.append({"name": d["name"], "instructions": None if prev is None else pending - prev,
                             "wall_ns": int(d["elapsed"] * 1e9), "gc_ns": int(d["gc_elapsed"] * 1e9),
                             "bytes_allocated": d["bytes_allocated"], "peak_growth": d["peak_growth"],
                             "ok": d["ok"]})
                prev = pending
            elif line.strip():
                info["skipped_lines"] += 1
            pending = None
    info["threads"] = 1
    info["marked_span_instructions"] = prev
    return rows, info


_SOKO = re.compile(r"^\s*\d+/\d+\s+(\S+)\s+(\S+)\s+([0-9.]+)ms\s+(.*)$")


def parse_sokonanoda(path: str):
    rows = []
    info: dict = {"skipped_lines": 0}
    with open(path, errors="replace") as handle:
        for line in handle:
            m = _SOKO.match(line)
            if m:
                rows.append({"name": m.group(4).rstrip("\n"), "kind": m.group(2), "status": m.group(1),
                             "wall_ns": int(float(m.group(3)) * 1e6), "instructions": None})
            elif line.startswith(("checked ", "parsed ")):
                info["summary_line"] = line.strip()
            elif line.strip():
                info["skipped_lines"] += 1
    return rows, info


PROFILES = [
    # name, file, parser, checker, threads, description of the cost
    ("nano-lean.j1", "nano-lean.j1.trace", parse_nano_lean, "nano-lean", 1,
     "nl-fast --trace -j 1; instructions:u of the worker between the start and end line of a declaration"),
    ("nano-lean.j4", "nano-lean.j4.trace", parse_nano_lean, "nano-lean", 4,
     "nl-fast --trace -j 4; as above, per worker thread"),
    ("gruszka.w1", "gruszka.w1.di", parse_gruszka, "gruszka", 1,
     "SK_DECL_INSTR=1 gruszka export.susz 1; instructions:u of the worker between consecutive DI lines"),
    ("gruszka.w4", "gruszka.w4.di", parse_gruszka, "gruszka", 4,
     "SK_DECL_INSTR=1 gruszka export.susz 4; as above, per worker thread"),
    ("rpylean", "rpylean.jsonl", parse_rpylean, "rpylean", 1,
     "rpylean-c check --print jsonl; instructions:u between consecutive lines (reading + checking the declaration)"),
    ("sokonanoda.t1", "sokonanoda.t1.verbose", parse_sokonanoda, "sokonanoda", 1,
     "sokonanoda --verbose --threads 1; wall time per declaration (no instruction counts)"),
    ("sokonanoda.t4", "sokonanoda.t4.verbose", parse_sokonanoda, "sokonanoda", 4,
     "sokonanoda --verbose --threads 4; wall time per declaration (no instruction counts)"),
]

CLASSES = [(0, 1e3), (1e3, 1e4), (1e4, 1e5), (1e5, 1e6), (1e6, 1e7), (1e7, 1e8), (1e8, 1e9), (1e9, 1e10), (1e10, float("inf"))]


_DIGITS = re.compile(r"[0-9]+")
_GENERATED = ("_proof_N", "_simp_N", "match_N", "eq_N", "eq_def", "eq_unfold", "injEq", "inj", "sizeOf_spec",
              "noConfusion", "noConfusionType", "casesOn", "recOn", "rec", "below", "brecOn", "ctorIdx",
              "congr_simp", "_sunfold", "_unary", "_mutual", "induct", "induct_unfolding",
              "fun_cases", "fun_cases_unfolding", "_unsafe_rec", "_cstage1", "_cstage2",
              "proof_N", "_eq_N", "_nary", "_lemma_N", "splitter", "toCtorIdx")


def shape_of(name: str) -> str:
    """The last component of a name with numbers replaced by N if it is one of
    the suffixes Lean generates (`_proof_N`, `match_N`, `eq_N`, `injEq`, ...),
    else "(other)"."""
    last = _DIGITS.sub("N", name.rsplit(".", 1)[-1])
    return last if last in _GENERATED else "(other)"


def namespace_of(name: str) -> str:
    head = name.split(".", 1)[0]
    return head if head else name


def analyse(rows: list[dict], metric: str, top: int, kinds: dict | None, run_total: int | None = None) -> dict:
    costs = [(r[metric] or 0) for r in rows]
    total = sum(costs)
    order = sorted(range(len(rows)), key=lambda i: -costs[i])
    out: dict = {"metric": metric, "n_declarations": len(rows), "total": total}
    if not total:
        return out
    cum, running = {}, 0
    marks = [1, 10, 100, 200, 1000, 10_000, 100_000]
    cum_at_rank = []
    for rank, i in enumerate(order, 1):
        running += costs[i]
        if rank in marks:
            cum[f"top_{rank}"] = round(running / total, 5)
        if rank <= top:
            cum_at_rank.append(running / total)
    out["cumulative_share"] = cum
    # how many declarations make up a given share of the total
    need, running, targets = {}, 0, [0.5, 0.8, 0.9, 0.95, 0.99]
    for rank, i in enumerate(order, 1):
        running += costs[i]
        while targets and running >= targets[0] * total:
            need[f"{int(targets.pop(0) * 100)}%"] = rank
        if not targets:
            break
    out["declarations_needed_for_share"] = need
    ascending = sorted(costs)
    n = len(ascending)
    out["quantiles"] = {"mean": round(total / n, 1), "p50": ascending[n // 2], "p90": ascending[int(n * 0.9)],
                        "p99": ascending[int(n * 0.99)], "p99.9": ascending[int(n * 0.999)], "max": ascending[-1]}
    if metric == "instructions":
        classes = []
        for lo, hi in CLASSES:
            sel = [c for c in costs if lo <= c < hi]
            classes.append({"instructions": f"{lo:.0e}..{hi:.0e}" if hi != float("inf") else f">={lo:.0e}",
                            "declarations": len(sel), "share_of_declarations": round(len(sel) / n, 5),
                            "share_of_cost": round(sum(sel) / total, 5)})
        out["by_cost_class"] = classes
    spaces: dict = collections.defaultdict(lambda: [0, 0])
    for r, c in zip(rows, costs):
        s = spaces[namespace_of(r["name"])]
        s[0] += 1
        s[1] += c
    out["by_namespace_top25"] = [{"namespace": k, "declarations": v[0], "share_of_cost": round(v[1] / total, 5)}
                                 for k, v in sorted(spaces.items(), key=lambda kv: -kv[1][1])[:25]]
    shapes: dict = collections.defaultdict(lambda: [0, 0])
    for r, c in zip(rows, costs):
        sh = shapes[shape_of(r["name"])]
        sh[0] += 1
        sh[1] += c
    out["by_last_name_component_top30"] = [
        {"last_component": k, "declarations": v[0], "share_of_cost": round(v[1] / total, 5), "mean": round(v[1] / v[0], 1)}
        for k, v in sorted(shapes.items(), key=lambda kv: -kv[1][1])[:30]]
    if kinds:
        by_kind: dict = collections.defaultdict(lambda: [0, 0])
        missing = 0
        for r, c in zip(rows, costs):
            kind = r.get("kind") or kinds.get(r["name"])
            if kind is None:
                missing += 1
                kind = "(unknown)"
            by_kind[kind][0] += 1
            by_kind[kind][1] += c
        out["by_kind"] = [{"kind": k, "declarations": v[0], "share_of_cost": round(v[1] / total, 5),
                           "mean": round(v[1] / v[0], 1)} for k, v in sorted(by_kind.items(), key=lambda kv: -kv[1][1])]
        out["by_kind_source"] = "kind as printed by sokonanoda --verbose, joined by name" + (
            f" ({missing} names not found)" if missing else "")
    out["top"] = []
    for rank, i in enumerate(order[:top], 1):
        r = rows[i]
        item = {"rank": rank, "name": r["name"], metric: costs[i], "share": round(costs[i] / total, 6),
                "cumulative_share": round(cum_at_rank[rank - 1], 5)}
        if metric == "instructions":
            item["virtual_time_s"] = round(costs[i] / IPS, 4)
            if run_total:
                item["share_of_run"] = round(costs[i] / run_total, 6)
        for key in ("instructions", "wall_ns", "kind", *GRUSZKA_COUNTERS, "gc_ns", "bytes_allocated", "peak_growth"):
            if key != metric and r.get(key) is not None:
                item[key] = r[key]
        if kinds and "kind" not in item and r["name"] in kinds:
            item["kind"] = kinds[r["name"]]
        out["top"].append(item)
    return out


def write_tsv(path: str, rows: list[dict], metric: str) -> None:
    costs = [(r[metric] or 0) for r in rows]
    total = sum(costs) or 1
    extra = [k for k in ("instructions", "instructions_raw", "wall_ns", "kind", *GRUSZKA_COUNTERS, "gc_ns", "bytes_allocated",
                         "peak_growth", "thread") if k != metric and any(k in r for r in rows[:50])]
    order = sorted(range(len(rows)), key=lambda i: -costs[i])
    running = 0
    with gzip.open(path, "wt", compresslevel=6) as out:
        out.write("\t".join(["rank", metric, "share", "cumulative_share", *extra, "name"]) + "\n")
        for rank, i in enumerate(order, 1):
            r = rows[i]
            running += costs[i]
            out.write("\t".join([str(rank), str(costs[i]), f"{costs[i] / total:.3e}", f"{running / total:.6f}",
                                 *("" if r.get(k) is None else str(r[k]) for k in extra),
                                 r["name"].replace("\t", " ").replace("\n", " ")]) + "\n")


def g(n) -> str:
    """Instruction count as e9."""
    if n is None:
        return "-"
    return f"{n / 1e9:.2f}e9" if abs(n) >= 1e8 else f"{n / 1e6:.2f}e6" if abs(n) >= 1e5 else f"{round(n):,}"


def pct(x, digits=1) -> str:
    return "-" if x is None else f"{100 * x:.{digits}f} %"


def table(head: list[str], rows: list[list[str]]) -> None:
    print("| " + " | ".join(head) + " |")
    print("|" + "|".join("---" if i == 0 else "---:" for i in range(len(head))) + "|")
    for row in rows:
        print("| " + " | ".join(str(c) for c in row) + " |")
    print()


def print_markdown(doc: dict, sections=None) -> None:
    import contextlib
    import io
    all_sections = ["split", "distribution", "classes", "kinds", "names", "top", "workers", "agreement"]
    wanted = sections or all_sections
    chunks: dict = {}
    current = [None]

    class Router(io.TextIOBase):
        def write(self, text):
            chunks.setdefault(current[0], []).append(text)
            return len(text)

    with contextlib.redirect_stdout(Router()):
        _print_markdown(doc, lambda name: current.__setitem__(0, name))
    for name in all_sections:
        if name in wanted:
            sys.stdout.write("".join(chunks.get(name, [])))


def _print_markdown(doc: dict, section) -> None:
    profiles = doc["profiles"]
    split = doc.get("cost_split", {})

    section("split")
    print("#### Instructions spent reading mathlib.ndjson, and what is left for checking\n")
    rows = []
    x = split.get("nano-lean")
    if x:
        rows.append(["nano-lean", g(x["arena_run"]), g(x["read_export"]), pct(x["read_share"]), g(x.get("checking")),
                     "`nl-fast -q -j 4 --import-only`"])
    x = split.get("gruszka")
    if x:
        load = (x.get("w1_di_run") or {}).get("outside_declarations")
        read = x["read_export_pack"] + (load or 0)
        rows.append(["gruszka", g(x["arena_run"]), f"{g(read)} = pack {g(x['read_export_pack'])} + load {g(load)}",
                     pct(read / x["arena_run"]) if x.get("arena_run") else "-",
                     g(x["arena_run"] - read) if x.get("arena_run") else "-",
                     "`susz pack --no-index` measured alone; load = the 1-worker run outside declarations"])
    x = split.get("sokonanoda")
    if x:
        rows.append(["sokonanoda", g(x["arena_run"]), g(x["read_export"]), pct(x["read_share"]), g(x.get("checking")),
                     "`sokonanoda --parse-only --threads 4`"])
    x = split.get("rpylean")
    if x:
        rows.append(["rpylean", g(x["arena_run"]), "interleaved", pct(x["read_and_other_share_by_time"]) + " of wall time",
                     "-", f"wall {x['run_wall_s']:.0f} s, of which checking {x['checking_wall_s']:.0f} s "
                          f"(GC inside checks {x['gc_wall_s']:.1f} s)"])
    table(["checker", "arena run (4 workers)", "reading the export", "share", "checking", "how measured"], rows)

    views = []
    for name in ("nano-lean.j1", "gruszka.w1", "rpylean", "sokonanoda.t1"):
        if name in profiles:
            views.append((name, profiles[name]))
            if "by_check_wall_time" in profiles[name]:
                views.append((name + " (check wall time)", dict(profiles[name]["by_check_wall_time"],
                                                                n_declarations=profiles[name]["n_declarations"])))

    def cost(view, value):
        return g(value) if view["metric"] == "instructions" else f"{value / 1e6:,.2f} ms" if value < 1e9 else f"{value / 1e9:.1f} s"

    section("distribution")
    print("#### Cumulative distribution: share of the per-declaration total in the N most expensive declarations\n")
    table(["profile", "metric", "declarations", "total", "top 1", "top 10", "top 100", "top 200", "top 1,000", "top 10,000", "top 100,000"],
          [[n, v["metric"].replace("wall_ns", "wall time"), f"{v['n_declarations']:,}", cost(v, v["total"]),
            *(pct(v["cumulative_share"].get(f"top_{k}"), 2 if k <= 10 else 1) for k in (1, 10, 100, 200, 1000, 10_000, 100_000))]
           for n, v in views])
    print("#### Declarations needed for a share of the total, and cost per declaration\n")
    table(["profile", "50 %", "80 %", "90 %", "95 %", "99 %", "mean", "median", "p90", "p99", "p99.9", "max"],
          [[n, *(f"{v['declarations_needed_for_share'].get(k, 0):,}" for k in ("50%", "80%", "90%", "95%", "99%")),
            *(cost(v, v["quantiles"][k]) for k in ("mean", "p50", "p90", "p99", "p99.9", "max"))] for n, v in views])

    section("classes")
    for name in ("nano-lean.j1", "gruszka.w1"):
        p = profiles.get(name)
        if p and "by_cost_class" in p:
            print(f"#### {name}: declarations by cost class\n")
            table(["instructions per declaration", "declarations", "share of declarations", "share of cost"],
                  [[c["instructions"], f"{c['declarations']:,}", pct(c["share_of_declarations"], 2), pct(c["share_of_cost"])]
                   for c in p["by_cost_class"] if c["declarations"]])

    section("kinds")
    names = [n for n in ("nano-lean.j1", "gruszka.w1", "sokonanoda.t1") if n in profiles and "by_kind" in profiles[n]]
    if names:
        print("#### Cost by kind of declaration\n")
        kinds = []
        for n in names:
            for k in profiles[n]["by_kind"]:
                if k["kind"] not in kinds:
                    kinds.append(k["kind"])
        rows = []
        for kind in kinds:
            row = [kind]
            for n in names:
                k = next((k for k in profiles[n]["by_kind"] if k["kind"] == kind), None)
                row += [f"{k['declarations']:,}", pct(k["share_of_cost"], 2), cost(profiles[n], k["mean"])] if k else ["-", "-", "-"]
            rows.append(row)
        table(["kind", *(f"{n}: {c}" for n in names for c in ("declarations", "share", "mean"))], rows)

    section("names")
    p = profiles.get("nano-lean.j1")
    if p:
        print("#### nano-lean.j1: cost by last name component, for the suffixes Lean generates (numbers as N)\n")
        table(["last component", "declarations", "share of cost", "mean instructions"],
              [[f"`{c['last_component']}`", f"{c['declarations']:,}", pct(c["share_of_cost"], 2), g(c["mean"])]
               for c in p["by_last_name_component_top30"][:14]])
        print("#### nano-lean.j1: cost by first name component\n")
        table(["namespace", "declarations", "share of cost"],
              [[f"`{c['namespace']}`", f"{c['declarations']:,}", pct(c["share_of_cost"], 2)] for c in p["by_namespace_top25"][:14]])

    section("top")
    if p:
        others = [n for n in ("gruszka.w1", "sokonanoda.t1", "rpylean") if n in profiles]
        ranks = {n: {t["name"]: t for t in profiles[n]["top"]} for n in others}
        metrics = {n: profiles[n]["metric"] for n in others}
        if "by_check_wall_time" in profiles.get("rpylean", {}):  # the cleaner per-declaration figure of rpylean
            ranks["rpylean"] = {t["name"]: t for t in profiles["rpylean"]["by_check_wall_time"]["top"]}
            metrics["rpylean"] = "wall_ns"
        print("#### The 40 most expensive declarations for nano-lean (1 worker), with their place elsewhere\n")
        rows = []
        for t in p["top"][:40]:
            row = [t["rank"], f"`{t['name']}`", g(t["instructions"]), pct(t["share"], 3), pct(t["cumulative_share"], 2)]
            for n in others:
                o = ranks[n].get(t["name"])
                if o is None:
                    row.append("> 200")
                elif metrics[n] == "instructions":
                    row.append(f"#{o['rank']} {g(o['instructions'])}")
                else:
                    row.append(f"#{o['rank']} {o['wall_ns'] / 1e6:.0f} ms")
            rows.append(row)
        table(["#", "declaration", "instructions", "share", "cumulative",
               *(n + (" (wall)" if metrics[n] == "wall_ns" else "") for n in others)], rows)

    section("workers")
    pairs = [("nano-lean.j1", "nano-lean.j4"), ("gruszka.w1", "gruszka.w4")]
    rows = []
    for a, b in pairs:
        for n in (a, b):
            q = profiles.get(n)
            if q:
                rows.append([n, q["threads"], g((q.get("instrumented_run") or {}).get("instructions")),
                             g(q.get("total_raw_intervals", q["total"])), g(q.get("outside_declarations")),
                             pct(q["cumulative_share"].get("top_200")), g(q["quantiles"]["p50"])])
    if rows:
        print("#### Per-declaration totals with 1 and 4 workers (instrumented runs)\n")
        table(["profile", "workers", "whole run", "inside declarations", "outside", "top 200 share", "median"], rows)

    section("agreement")
    if doc.get("agreement"):
        print("#### Agreement between checkers: declarations in common among the N most expensive\n")
        by: dict = collections.defaultdict(dict)
        for a in doc["agreement"]:
            by[(a["a"], a["b"])][a["top"]] = a["in_common"]
        table(["pair", *(f"top {k}" for k in sorted(next(iter(by.values()))))],
              [[f"{a} / {b}", *(v[k] for k in sorted(v))] for (a, b), v in by.items()])
    x = doc.get("nano_lean_vs_gruszka")
    if x:
        r = x["per_declaration_ratio_for_declarations_over_1e6_in_nano_lean"]
        print(f"gruszka / nano-lean, same declarations, 1 worker each: {x['gruszka_over_nano_lean_total']:.3f} in total; "
              f"per declaration (the {r['n']:,} above 1e6 instructions in nano-lean): p05 {r['p05']}, p25 {r['p25']}, "
              f"median {r['p50']}, p75 {r['p75']}, p95 {r['p95']}.\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dir", default=os.path.join(ROOT, "work/baselines/profile"))
    parser.add_argument("--out", default=os.path.join(ROOT, "work/baselines/mathlib-profile"))
    parser.add_argument("--runs", default=os.path.join(ROOT, "work/baselines/big/runs.jsonl"))
    parser.add_argument("--corpus", default="mathlib")
    parser.add_argument("--top", type=int, default=200)
    parser.add_argument("--markdown", action="store_true",
                        help="print tables (markdown) from an existing OUT.json instead of reading the profiles")
    parser.add_argument("--section", action="append",
                        choices=["split", "distribution", "classes", "kinds", "names", "top", "workers", "agreement"],
                        help="with --markdown: print only these tables (default: all)")
    args = parser.parse_args()
    d = args.dir
    if args.markdown:
        with open(args.out + ".json") as handle:
            print_markdown(json.load(handle), args.section)
        return 0

    parsed = {}
    for name, filename, parse, checker, threads, what in PROFILES:
        path = os.path.join(d, filename)
        if not os.path.exists(path) or os.path.getsize(path) == 0:
            continue
        print(f"reading {filename} ...", file=sys.stderr)
        rows, info = parse(path)
        parsed[name] = (rows, info, checker, threads, what, filename)

    # kinds of declarations, from sokonanoda's listing
    kinds = None
    for key in ("sokonanoda.t1", "sokonanoda.t4"):
        if key in parsed:
            kinds = {r["name"]: r["kind"] for r in parsed[key][0]}
            break

    doc: dict = {
        "meta": {
            "what": "Per-declaration cost of baseline checkers on work/exports/%s.ndjson" % args.corpus,
            "made_by": "bench/baselines/profile-mathlib.sh (in the container) + bench/baselines/profile-summary.py",
            "instruction_counts": "bench/baselines/instrtap.c: perf_event_open INST_RETIRED, user mode, per thread "
                                  "(what perf stat -e instructions:u counts), read whenever the checker prints "
                                  "its per-declaration line",
            "virtual_time": "instructions / 6e9, the arena's unit",
            "shares": "share and cumulative_share are of the sum over all declarations of the profile (the "
                      "checking cost); share_of_run is of the whole instrumented run, reading the export included",
            "caveats": [
                "the instrumented runs print one or two lines per declaration; their totals are 1-3 % above the "
                "uninstrumented runs (see instrumented_run vs the baseline tables)",
                "gruszka: an interval ends when the DI line of the declaration starts to be printed, so it "
                "includes printing the previous declaration's line; overhead_per_declaration estimates that",
                "rpylean reads and checks declaration by declaration; its intervals include reading the "
                "declaration's lines of the export",
                "sokonanoda offers wall time only; wall times were taken on a shared host",
                "a 'declaration' is what each checker calls one: nano-lean and rpylean count every constant "
                "(constructors and recursors separately), gruszka counts an inductive block once",
            ],
        },
        "profiles": {},
    }

    for name, (rows, info, checker, threads, what, filename) in parsed.items():
        metric = "instructions" if any(r.get("instructions") for r in rows[:1000]) else "wall_ns"
        entry = {"checker": checker, "threads": threads, "source": what, "file": os.path.join(os.path.relpath(d, ROOT), filename)}
        entry.update({k: v for k, v in info.items() if v not in (None, 0) or k == "threads"})
        m = load_measure(d, name) or load_measure(d, name.split(".")[0])
        # drop counters the checker's build does not maintain (all zero)
        for key in GRUSZKA_COUNTERS:
            if rows and key in rows[0] and not any(r.get(key) for r in rows):
                for r in rows:
                    r.pop(key, None)
        if checker == "gruszka" and m and m.get("instructions"):
            # Printing a DI line costs about as much as checking a small
            # declaration and lands in the next interval. The same run without
            # SK_DECL_INSTR tells how much that is in total; spread evenly.
            plain = load_measure(d, f"gruszka.check-w{threads}")
            if plain and plain.get("instructions"):
                over = (m["instructions"] - plain["instructions"]) / max(1, len(rows))
                entry["overhead_per_declaration"] = round(over)
                entry["overhead_note"] = ("instructions = raw interval - overhead_per_declaration (not below 0); "
                                          "overhead = (this run - the same run without SK_DECL_INSTR and instrtap) / declarations; "
                                          "the raw count is in the instructions_raw column of the .tsv.gz")
                for r in rows:
                    if r.get("instructions") is not None:
                        r["instructions_raw"] = r["instructions"]
                        r["instructions"] = max(0, int(r["instructions"] - over))
        if m:
            entry["instrumented_run"] = {k: m.get(k) for k in ("exit_code", "instructions", "cpu_time_s", "wall_time_s", "max_rss_bytes")}
        entry.update(analyse(rows, metric, args.top, kinds, (m or {}).get("instructions")))
        if metric == "instructions" and m and m.get("instructions"):
            raw_total = sum(r.get("instructions_raw", r["instructions"]) or 0 for r in rows)
            entry["total_raw_intervals"] = raw_total
            entry["outside_declarations"] = m["instructions"] - raw_total
            entry["share_inside_declarations"] = round(raw_total / m["instructions"], 4)
        if name == "rpylean":
            # second view: the checker's own per-declaration wall time, which
            # excludes reading the export, collections between declarations
            # and table growth; the instruction intervals include them
            by_wall = analyse(rows, "wall_ns", args.top, kinds)
            entry["by_check_wall_time"] = {k: by_wall[k] for k in (
                "metric", "total", "cumulative_share", "declarations_needed_for_share", "quantiles", "top")}
        doc["profiles"][name] = entry
        write_tsv(f"{args.out}.{name}.tsv.gz", rows, metric)
        print(f"  {name}: {len(rows)} declarations, total {entry['total']:.4e} {metric}", file=sys.stderr)

    # ---- cost split: reading the export vs checking ----------------------------------------------
    arena = {}
    scaling: dict = {}
    try:
        import statistics
        by: dict = collections.defaultdict(list)
        for line in open(args.runs):
            r = json.loads(line)
            if r["corpus"] == args.corpus and r.get("instructions"):
                by[(r["checker"], r.get("variant", "arena"))].append(r)
        for (checker, variant), rs in by.items():
            med = int(statistics.median(r["instructions"] for r in rs))
            rec = {"instructions": med, "virtual_time_s": round(med / IPS, 2), "runs": len(rs),
                   "instructions_min": min(r["instructions"] for r in rs),
                   "instructions_max": max(r["instructions"] for r in rs),
                   "cpu_time_s": round(statistics.median(r["cpu_time_s"] for r in rs), 1),
                   "wall_time_s": round(statistics.median(r["wall_time_s"] for r in rs), 1),
                   "max_rss_bytes": max(r["max_rss_bytes"] for r in rs)}
            if variant == "arena":
                arena[checker] = rec
            elif variant.startswith("threads-"):
                scaling.setdefault(checker, {})[variant.split("-", 1)[1]] = rec
        for checker in scaling:
            if checker in arena:
                scaling[checker].setdefault("4", arena[checker])
            one = scaling[checker].get("1", {}).get("instructions")
            for n, rec in scaling[checker].items():
                if one:
                    rec["vs_1_thread"] = round(rec["instructions"] / one, 4)
            scaling[checker] = dict(sorted(scaling[checker].items(), key=lambda kv: int(kv[0])))
    except OSError:
        pass
    doc["thread_scaling"] = scaling

    split: dict = {}

    def instr(name):
        m = load_measure(d, name)
        return m.get("instructions") if m else None

    total = arena.get("nano-lean", {}).get("instructions")
    imp = instr("nano-lean.import-only")
    if imp:
        split["nano-lean"] = {
            "how": "nl-fast -q -j 4 --import-only measured like the arena; checking = arena run - import",
            "read_export": imp, "arena_run": total,
            "checking": total - imp if total else None,
            "read_share": round(imp / total, 4) if total else None,
        }
        p = doc["profiles"].get("nano-lean.j1")
        if p and p.get("instrumented_run"):
            split["nano-lean"]["j1_trace_run"] = {
                "total": p["instrumented_run"]["instructions"], "inside_declarations": p["total"],
                "outside_declarations": p.get("outside_declarations"),
                "note": "outside = import, reordering, start-up, teardown"}
    pack, chk1, chk4 = instr("gruszka.pack"), instr("gruszka.check-w1"), instr("gruszka.check-w4")
    if pack:
        total = arena.get("gruszka", {}).get("instructions")
        split["gruszka"] = {
            "how": "scripts/arena-run = susz pack --no-index (ndjson -> Susz file) + gruszka FILE.susz 4; both "
                   "measured separately like the arena",
            "read_export_pack": pack, "gruszka_1_worker": chk1, "gruszka_4_workers": chk4, "arena_run": total,
            "read_share": round(pack / total, 4) if total else None,
        }
        p = doc["profiles"].get("gruszka.w1")
        if p and p.get("instrumented_run") and chk1:
            over = p["instrumented_run"]["instructions"] - chk1
            split["gruszka"]["w1_di_run"] = {
                "total": p["instrumented_run"]["instructions"], "inside_declarations": p["total"],
                "outside_declarations": p.get("outside_declarations"),
                "instrumentation_overhead_total": over,
                "overhead_per_declaration": round(over / max(1, p["n_declarations"])),
                "note": "outside = loading the Susz file into the environment, ordering, start-up; the overhead "
                        "(printing the DI lines) is inside the per-declaration figures"}
    par = instr("sokonanoda.parse-only")
    if par:
        total = arena.get("sokonanoda", {}).get("instructions")
        split["sokonanoda"] = {
            "how": "sokonanoda --parse-only --threads 4 measured like the arena; checking = arena run - parse",
            "read_export": par, "arena_run": total, "checking": total - par if total else None,
            "read_share": round(par / total, 4) if total else None,
        }
    p = doc["profiles"].get("rpylean")
    if p and "rpylean" in parsed:
        rows = parsed["rpylean"][0]
        check_ns = sum(r["wall_ns"] for r in rows)
        gc_ns = sum(r["gc_ns"] for r in rows)
        wall = (p.get("instrumented_run") or {}).get("wall_time_s")
        split["rpylean"] = {
            "how": "reading and checking are interleaved; by wall time of the jsonl run: sum of the "
                   "per-declaration 'elapsed' (checking) against the whole run",
            "run_wall_s": wall, "checking_wall_s": round(check_ns / 1e9, 2), "gc_wall_s": round(gc_ns / 1e9, 2),
            "read_and_other_share_by_time": round(1 - check_ns / 1e9 / wall, 4) if wall else None,
            "arena_run": arena.get("rpylean", {}).get("instructions"),
        }
    doc["cost_split"] = split

    # ---- agreement between checkers ------------------------------------------------------------
    tops = {name: [t["name"] for t in entry.get("top", [])] for name, entry in doc["profiles"].items()}
    if "by_check_wall_time" in doc["profiles"].get("rpylean", {}):
        tops["rpylean (check wall time)"] = [t["name"] for t in doc["profiles"]["rpylean"]["by_check_wall_time"]["top"]]
    agree = []
    names = [n for n in ("nano-lean.j1", "gruszka.w1", "sokonanoda.t1", "rpylean (check wall time)", "rpylean") if n in tops]
    for i, a in enumerate(names):
        for b in names[i + 1:]:
            for k in (10, 50, args.top):
                agree.append({"a": a, "b": b, "top": k, "in_common": len(set(tops[a][:k]) & set(tops[b][:k]))})
    doc["agreement"] = agree

    # the same declaration in nano-lean and gruszka (by name; gruszka names a block by its first constant)
    if "nano-lean.j1" in parsed and "gruszka.w1" in parsed:
        a = {r["name"]: r["instructions"] for r in parsed["nano-lean.j1"][0] if r.get("instructions") is not None}
        ratios = []
        both_a = both_b = 0
        for r in parsed["gruszka.w1"][0]:
            x = a.get(r["name"])
            if x and r.get("instructions"):
                both_a += x
                both_b += r["instructions"]
                if x >= 1_000_000:
                    ratios.append(r["instructions"] / x)
        ratios.sort()
        if ratios:
            n = len(ratios)
            doc["nano_lean_vs_gruszka"] = {
                "declarations_in_both": "matched by name",
                "instructions_nano_lean": both_a, "instructions_gruszka": both_b,
                "gruszka_over_nano_lean_total": round(both_b / both_a, 4),
                "per_declaration_ratio_for_declarations_over_1e6_in_nano_lean": {
                    "n": n, "p05": round(ratios[int(n * 0.05)], 3), "p25": round(ratios[n // 4], 3),
                    "p50": round(ratios[n // 2], 3), "p75": round(ratios[3 * n // 4], 3),
                    "p95": round(ratios[int(n * 0.95)], 3)},
            }

    with open(args.out + ".json", "w") as handle:
        json.dump(doc, handle, indent=1)
        handle.write("\n")

    # ---- one row per name, every single-worker profile side by side -----------------------------
    columns = [(n, c) for n, c in (("nano-lean.j1", "instructions"), ("gruszka.w1", "instructions"),
                                   ("rpylean", "instructions"), ("rpylean", "wall_ns"), ("sokonanoda.t1", "wall_ns"))
               if n in parsed]
    if columns:
        maps = [{r["name"]: r.get(c) for r in parsed[n][0]} for n, c in columns]
        first = columns[0][0]
        order = sorted(parsed[first][0], key=lambda r: -(r.get(columns[0][1]) or 0))
        seen = {r["name"] for r in order}
        extra = [name for m in maps[1:] for name in m if name not in seen and not seen.add(name)]
        with gzip.open(args.out + ".by-name.tsv.gz", "wt", compresslevel=6) as out:
            out.write("\t".join(["kind", *(f"{n}:{c}" for n, c in columns), "name"]) + "\n")
            for name in [r["name"] for r in order] + extra:
                out.write("\t".join([(kinds or {}).get(name, ""),
                                     *("" if m.get(name) is None else str(m[name]) for m in maps),
                                     name.replace("\t", " ").replace("\n", " ")]) + "\n")

    # ---- the top lists side by side --------------------------------------------------------------
    with open(args.out + ".top200.tsv", "w") as out:
        cols = [n for n in ("nano-lean.j1", "gruszka.w1", "rpylean", "sokonanoda.t1") if n in doc["profiles"]]
        head = ["rank"]
        for c in cols:
            metric = doc["profiles"][c]["metric"]
            head += [f"{c}:name", f"{c}:{metric}", f"{c}:share", f"{c}:cumulative_share"]
        out.write("\t".join(head) + "\n")
        for rank in range(args.top):
            line = [str(rank + 1)]
            for c in cols:
                top = doc["profiles"][c].get("top", [])
                if rank < len(top):
                    t = top[rank]
                    line += [t["name"].replace("\t", " "), str(t[doc["profiles"][c]["metric"]]), f"{t['share']:.5f}",
                             f"{t['cumulative_share']:.4f}"]
                else:
                    line += ["", "", "", ""]
            out.write("\t".join(line) + "\n")
    print(f"wrote {os.path.relpath(args.out, ROOT)}.json, .top200.tsv, .by-name.tsv.gz and {len(parsed)} .tsv.gz files",
          file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
