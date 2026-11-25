#!/usr/bin/env python3
"""bench/track/benchmarks.py - the benchmark tables: one current table per metric, in the maintainer's
order (warm wall clock first), written as a TREE of the journal.

    bench/track/benchmarks.py --tsv work/perf-history/warm.tsv --labels bench --tree-author ADDRESS [--since UTC]
                                                     # -> journal/trees/report/rep-benchmarks.tree
    bench/track/benchmarks.py ... --headline         # print ONE table (the root README's) and write nothing
    bench/track/benchmarks.py ... --stdout           # print the document as Markdown and write nothing
    bench/track/benchmarks.py ... --markdown FILE    # also write the document as a Markdown file (on request only)

The notes of the project live in the journal (root AGENTS.md: a table that a tool generates is emitted
as a tree, not as a Markdown file). The result of a run is therefore the tree `rep-benchmarks` (taxon
Report, journal/trees/report/rep-benchmarks.tree; --tree FILE names another file, its name is the
address): the journal's frontmatter, sections as subtrees, \\table{\\tr{\\th{..}}..} from the journal's
base-macros (bench/track/foresttree.py). --tree-author is the address of the identity tree of whoever
generates it (journal/trees/people/). The creation date of an existing tree is kept, \\meta{updated} is
the day of the run. The text around the tables - what the numbers are, how to read them - is NOT in
this tool: it is the hand-written tree `rep-benchmarks-notes`, which the generated tree includes
(--notes-tree ADDRESS; '' = none). There is no docs/BENCHMARKS.md any more.

Tables, in this order, one column per corpus and one row per checker and platform. THE FIRST ROWS
ARE THE DEFAULT CONFIGURATION (the engine a run without -X engine checks with: --default-engine, the
machine since 2026-11-25T03:05Z; policy practical): the executable (the release is one executable,
the native image), the optional Oracle GraalVM build of it, the JVM launcher of a development
distribution; then the other RLVM engine on the same; then gruszka and nano-lean; then the ratios:

    1. warm wall clock, 8 workers, load pipelined      median of the later passes (best later pass);
                                                       later = passes 4..N of 8 or more, else 2..N
    2. warm wall clock, 1 worker
    3. cold wall clock of a fresh process, 8 workers   (for information)
    4. CPU time                                        of the median warm pass, 8 workers (and of the cold process)
    5. peak RSS                                        of the warm process, 8 workers (and of the cold process)
    6. instructions                                    of a cold process in the arena-shaped container (information:
                                                       the arena's metric, not ours)

and a provenance section that says for EVERY cell which row it is: time, passes, the bench/quiet.py
window it was taken in, the distribution and its jars.

Where the numbers come from. Tables 1 to 5: rows of bench/warm.py (bench/warm.sh; TSV files given
with --tsv, repeatable). A row measured without --engine (warm.py's "default") is a row of the
default engine: it IS the default configuration; a row with -X engine=<the default engine> is the
same run and counts for the same cell. Only rows taken inside a bench/quiet.py window count ("quiet"), only rows
whose label is one of --labels, only rows not older than --since; of several rows for the same cell
the LATEST one is used (so re-measuring replaces a cell). A row with passes gives the warm figures of
its worker count and, if it has a cold process, the cold ones; a `--cold-only` row gives cold figures.
Table 6: the latest valid entry of bench/track/track.py's history (work/perf-history/CORPUS.jsonl)
for the configuration rlvm/native/t<--instr-threads>/-X engine=NAME that is not older than --since,
and the baselines of this host (bench/baselines.json), as track.py reads them.

The tables of 2026-11-24 and before (cold CPU time first, from the tracker's history; the first warm
table) are superseded: the journal has them as the report rep-warm-wall-clock.

Nothing is measured here. Python stdlib only.
"""

from __future__ import annotations

import argparse
import datetime
import os
import re
import sys

HERE = os.path.dirname(os.path.realpath(__file__))
ROOT = os.path.realpath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, HERE)
import foresttree  # noqa: E402
import track  # noqa: E402

# The columns of a bench/warm.py row (rows written before 2026-11-24T22Z end with "label").
COLUMNS = ["utc", "quiet", "corpus", "what", "launcher", "workers", "load", "mode", "passes", "verdict",
           "best_later_s", "median_later_s", "pass1_s", "cold_wall_s", "cpu_median_s", "gc_median_ms",
           "peak_rss_mib", "cold_cpu_s", "cold_rss_mib", "walls_s", "loadavg", "label", "dist", "build"]
CORPORA = ["init", "std", "cedar", "reachability", "con-leche", "navier-stokes-euler", "cslib", "mathlib"]
ENGINES = ("reference", "machine")


ORACLE = "native-oracle-pgo"   # launcher key of rows whose label starts with "oracle-pgo" (see select)


def checkers(default_engine: str) -> list:
    """(key, heading) per table row: key = (checker as warm.py's "what" up to the first '+', launcher).
    The executable in its default configuration first (OWNER-NOTES 16: the release is one executable and
    a run without options is the fastest configuration; OWNER-NOTES 13: what that configuration promises),
    then the OPTIONAL build of the same executable with Oracle GraalVM and the shipped profile, then the
    JVM launcher of a development distribution, then the other engine, then the baselines."""
    other = [e for e in ENGINES if e != default_engine][0]
    return [((default_engine, "native"), f"**RLVM, the executable** (`rlvm FILE`: default configuration, {default_engine} engine)"),
            ((default_engine, ORACLE), "RLVM, the OPTIONAL executable built with Oracle GraalVM and the shipped profile (`--oracle-pgo`; not the default build)"),
            ((default_engine, "jvm"), f"RLVM default configuration ({default_engine} engine) on the JVM launcher (development tool)"),
            ((other, "native"), f"RLVM `-X engine={other}`, the executable"),
            ((other, ORACLE), f"RLVM `-X engine={other}`, the OPTIONAL Oracle GraalVM executable"),
            ((other, "jvm"), f"RLVM `-X engine={other}`, JVM launcher (development tool)"),
            (("gruszka", "rust"), "gruszka"), (("nano-lean", "rust"), "nano-lean")]


TREE = os.path.join(ROOT, "journal", "trees", "report", "rep-benchmarks.tree")
NOTES_TREE = "rep-benchmarks-notes"


def num(text):
    try:
        return float(text)
    except (TypeError, ValueError):
        return None


def read_rows(paths: list[str]) -> list[dict]:
    rows = []
    for path in paths:
        try:
            with open(path) as handle:
                lines = handle.read().splitlines()
        except OSError as exc:
            print(f"benchmarks: {exc}", file=sys.stderr)
            continue
        for line in lines:
            fields = line.split("\t")
            if not fields or fields[0] == "utc" or len(fields) < 22:
                continue
            row = dict(zip(COLUMNS, fields + [""] * (len(COLUMNS) - len(fields))))
            row["source"] = os.path.relpath(path, ROOT) if os.path.abspath(path).startswith(ROOT + os.sep) else path
            rows.append(row)
    return rows


def select(rows: list[dict], labels: list[str], since: str | None, default_engine: str = "machine", default_since: str | None = None,
           baseline_labels: list[str] | None = None) -> dict:
    """cells[(corpus, checker, launcher)] = {"warm8": row, "warm1": row, "cold8": row}: the latest eligible row each.
    A row measured without -X engine (warm.py's "default") is a row of the default engine OF ITS TIME:
    default_engine if it is not older than default_since (the time the default engine changed), the
    other engine before that."""
    previous = [e for e in ENGINES if e != default_engine][0]
    cells: dict = {}
    for row in sorted(rows, key=lambda r: r["utc"]):
        allowed = baseline_labels if (baseline_labels and row["launcher"] == "rust") else labels
        if row["quiet"] != "quiet" or (allowed and row["label"] not in allowed and not row["label"].startswith("oracle-pgo")) or (since and row["utc"] < since):
            continue
        if not row["verdict"].startswith("ACCEPT"):
            continue
        what = row["what"].split("+")[0]
        if what == "default":
            # measured without -X engine: the default configuration itself, of the time of the row
            what = default_engine if not default_since or row["utc"] >= default_since else previous
        # A row of the optional Oracle GraalVM build is a native-image row of another distribution: its
        # label starts with "oracle-pgo" (bench/warm.sh --launcher native --dist DIR --label oracle-pgo...).
        launcher = ORACLE if row["launcher"] == "native" and row["label"].startswith("oracle-pgo") else row["launcher"]
        key = (row["corpus"], what, launcher)
        cell = cells.setdefault(key, {})
        workers = row["workers"]
        if num(row["median_later_s"]) is not None and workers in ("8", "1"):
            cell["warm" + workers] = row
        if num(row["cold_wall_s"]) is not None and workers in ("8", "1"):
            cell["cold" + workers] = row
        elif launcher in ("jvm", "native", ORACLE) and workers == "8" and num(row["pass1_s"]) is not None and "cold8" not in cell:
            cell["pass1_8"] = row   # no separate cold process (mathlib): pass 1 of the warm process stands for it
    return cells


def steady(row: dict):
    """The warm wall clock of a row: the median of passes 4..N when the process made 8 or more passes
    (the first two or three later passes of a JVM are still warm-up: J1.md 3.2), else of passes 2..N."""
    walls = [num(x) for x in (row.get("walls_s") or "").split()]
    walls = [w for w in walls if w is not None]
    later = walls[3:] if len(walls) >= 8 else walls[1:]
    if not later:
        return num(row.get("median_later_s"))
    later = sorted(later)
    mid = len(later) // 2
    return later[mid] if len(later) % 2 else (later[mid - 1] + later[mid]) / 2


def f2(value, digits=2) -> str:
    return "-" if value is None else f"{value:,.{digits}f}"


def ratio(ours, theirs) -> str:
    if ours is None or not theirs:
        return "-"
    return f"{ours / theirs:.2f}x"


def corpus_title(name: str) -> str:
    path = os.path.join(ROOT, "work", "exports", name + ".ndjson")
    try:
        size = os.path.getsize(path)
    except OSError:
        return name
    return f"{name} ({size / 1e9:.2f} GB)" if size >= 1e9 else f"{name} ({size / 1e6:.0f} MB)"


def table(title: str, lead: str, cells: dict, corpora: list[str], rows_of: list, value, ratios: list[tuple[str, tuple, tuple]] = (),
          kinds: tuple = ()) -> list[str]:
    """One row per checker (rows_of: (key, heading)), one column per corpus; value(cell) -> (text, number).
    ratios: (heading, key of ours, key of theirs), one row each behind the checkers. A ratio between two
    builds of RLVM (the optional Oracle executable against the CE executable) is only formed when both
    cells measured the same configuration (warm.py's "what": engine and switch set); kinds names the rows
    of a cell this table reads ("warm8", "cold8", ...), for that comparison."""
    out = [f"## {title}", "", lead, ""]
    used = [c for c in corpora if any(value(cells.get((c,) + key, {}))[0] != "-" for key, _h in rows_of)]
    if not used:
        return out + ["Not measured for the current build.", ""]
    out.append("| Checker, platform | " + " | ".join(corpus_title(c) for c in used) + " |")
    out.append("|---|" + "--:|" * len(used))
    nums: dict = {}
    for key, heading in rows_of:
        line = []
        for corpus in used:
            text, number = value(cells.get((corpus,) + key, {}))
            nums[(corpus,) + key] = number
            line.append(text)
        if any(x != "-" for x in line):
            out.append(f"| {heading} | " + " | ".join(line) + " |")
    def same_config(c, a, b):
        if ORACLE not in (a[1], b[1]):
            return True
        ra = next((cells.get((c,) + a, {}).get(k) for k in kinds if cells.get((c,) + a, {}).get(k)), None)
        rb = next((cells.get((c,) + b, {}).get(k) for k in kinds if cells.get((c,) + b, {}).get(k)), None)
        return bool(ra and rb and ra["what"] == rb["what"])

    for heading, a, b in ratios:
        line = [ratio(nums.get((c,) + a), nums.get((c,) + b)) if same_config(c, a, b) else "-" for c in used]
        if any(x != "-" for x in line):
            out.append(f"| *{heading}* | " + " | ".join(line) + " |")
    out.append("")
    return out


def quiet_windows() -> list[tuple[str, str, str]]:
    """(start utc, end utc, command) of every bench/quiet.py window in work/perf-history/quiet.log."""
    out = []
    try:
        with open(os.path.join(ROOT, "work", "perf-history", "quiet.log")) as handle:
            for line in handle:
                match = re.match(r"^(\S+Z) .* wall=([0-9.]+)s .*? cmd=(.*)$", line.strip())
                if not match:
                    continue
                end = datetime.datetime.strptime(match.group(1), "%Y-%m-%dT%H:%M:%SZ")
                start = end - datetime.timedelta(seconds=float(match.group(2)) + 2)
                out.append((start.strftime("%Y-%m-%dT%H:%M:%SZ"), match.group(1), match.group(3)))
    except OSError:
        pass
    return out


def window_of(utc: str, windows: list[tuple[str, str, str]]) -> str:
    for start, end, cmd in windows:
        if start <= utc <= end:
            return f"{start[11:19]}-{end[11:20]} `{cmd}`"
    return "not found in work/perf-history/quiet.log"


def jars_of(dist: str) -> list[str]:
    """The jar lines of a distribution's BUILD-INFO.txt ('  rlvm-x  <sha>  published ...'), [] if it is gone."""
    try:
        with open(os.path.join(ROOT, dist, "BUILD-INFO.txt")) as handle:
            return [" ".join(line.split()[:2]) for line in handle if line.startswith("  rlvm-")]
    except OSError:
        return []


def instructions(corpora: list[str], history_dir: str, threads: int, since: str | None, default_engine: str = "machine",
                 default_since: str | None = None) -> tuple[list[str], list[str]]:
    """Table 6. An entry recorded without -X engine (config rlvm/native/tN) is an entry of the default engine
    of ITS time: default_engine from default_since on, the other engine before."""
    lines, prov = [], []
    other = [e for e in ENGINES if e != default_engine][0]
    head = ["Corpus", f"RLVM default configuration ({default_engine} engine), native", f"RLVM `-X engine={other}`, native", "gruszka", "nano-lean",
            "default / gruszka", f"{other} / gruszka"]
    lines.append("| " + " | ".join(head) + " |")
    lines.append("|---|" + "--:|" * (len(head) - 1))
    any_row = False
    for corpus in corpora:
        name, _path, arena, expected = track.resolve(corpus)
        entries = track.read_history(history_dir, name)
        vals = {}
        for engine in ENGINES:
            # the default configuration is recorded without -X engine (rlvm/native/tN) or with its engine named
            named, plain = f"rlvm/native/t{threads}/-X engine={engine}", f"rlvm/native/t{threads}"
            found = None
            for entry in entries:
                config, utc = entry.get("config"), entry.get("utc", "")
                is_default_then = (engine == default_engine) == (not default_since or utc >= default_since)
                if (config == named or (config == plain and is_default_then)) and entry.get("valid") and entry.get("instructions") \
                        and (not since or utc >= since):
                    found = entry
            vals[engine] = found
            if found:
                prov.append(f"| {corpus} | RLVM {engine}, native, {threads} workers | {found['utc']} | {found['instructions'] / 1e9:,.1f} G; "
                            f"cold {track.fmt_s(found.get('wall_time_s'))} s wall, {track.fmt_s(found.get('cpu_time_s'))} s CPU, "
                            f"{track.fmt_mib(found.get('max_rss_bytes'))} MiB | `{(found.get('build') or {}).get('dist', '?')}` | "
                            f"`{found.get('command', '')}` |")
        base = track.load_baselines(name, history_dir, arena, expected)
        g, n = base.get("gruszka", {}).get("instructions"), base.get("nano-lean", {}).get("instructions")
        m = vals[default_engine]["instructions"] if vals[default_engine] else None
        r = vals[other]["instructions"] if vals[other] else None
        if m is None and r is None:
            continue
        any_row = True

        def g_(v):
            return "-" if v is None else f"{v / 1e9:,.1f} G"
        lines.append("| " + " | ".join([corpus_title(corpus), g_(m), g_(r), g_(g), g_(n), ratio(m, g), ratio(r, g)]) + " |")
    if not any_row:
        return ["Not measured for the current build (`bench/track/track.py CORPUS --launcher native --threads N --engine NAME --runs 1`)."], []
    return lines, prov


def main() -> int:
    parser = argparse.ArgumentParser(prog="bench/track/benchmarks.py",
                                     description="The benchmark tables as a tree of the journal (journal/trees/report/rep-benchmarks.tree).")
    parser.add_argument("--tsv", action="append", default=[], help="rows of bench/warm.py (repeatable; default work/perf-history/warm.tsv)")
    parser.add_argument("--labels", default="bench", help="comma list of row labels that count (default bench; empty = every label)")
    parser.add_argument("--baseline-labels", default="",
                        help="comma list of labels that count for the rows of the baselines (gruszka, nano-lean); default: the same as --labels. "
                             "The baselines do not change with RLVM's builds, so their rows may be older than RLVM's")
    parser.add_argument("--since", help="ignore rows and history entries older than this UTC time (2026-11-24T21:00:00Z)")
    parser.add_argument("--history", default=track.DEFAULT_HISTORY, help="the tracker's history, for the instruction counts")
    parser.add_argument("--instr-threads", type=int, default=8, help="worker count of the history entries used for table 6 (default 8)")
    parser.add_argument("--default-engine", default="machine", choices=ENGINES,
                        help="the engine a run without -X engine checks with (rlvm --version: 'default engine: NAME'); its rows come first "
                             "(default machine: the default engine since 2026-11-25T03:05Z)")
    parser.add_argument("--default-since", default="2026-11-25T03:05:00Z",
                        help="the UTC time since which --default-engine is the default engine: a row measured WITHOUT -X engine before it "
                             "is a row of the other engine (default 2026-11-25T03:05:00Z, the machine's publication as default; '' = no such time)")
    parser.add_argument("--notes", default=None, help="a Markdown file put in front of and behind the tables of the MARKDOWN output "
                        "(two parts, separated by a line '<!-- tables -->'); default: none. The tree includes --notes-tree instead")
    parser.add_argument("--notes-tree", default=NOTES_TREE,
                        help="address of the hand-written tree that the generated tree includes in front of its tables "
                             "(default rep-benchmarks-notes; '' = none)")
    parser.add_argument("--markdown", "--out", dest="markdown", default=None,
                        help="ALSO write the document as a Markdown file (default: no Markdown file is written)")
    parser.add_argument("--stdout", action="store_true", help="print the document as Markdown and write nothing")
    parser.add_argument("--headline", action="store_true",
                        help="print ONE table instead of the document (the README's): a run of the executable in its default "
                             "configuration - cold wall clock of a fresh process (median warm pass in brackets), 8 workers - "
                             "against gruszka and nano-lean; nothing is written")
    parser.add_argument("--no-markdown", action="store_true", help="accepted and ignored (older command lines: no Markdown file is the default)")
    parser.add_argument("--tree", default=TREE,
                        help="the tree to write (a Forester .tree file; its name is its address; default journal/trees/report/rep-benchmarks.tree)")
    parser.add_argument("--no-tree", action="store_true", help="do not write the tree (with --markdown: the Markdown file only)")
    parser.add_argument("--tree-author", help="address of the identity tree of whoever generates the tree (journal/trees/people/); required to write the tree")
    parser.add_argument("--tree-contributor", help="address of the identity tree of the person who directed the work")
    parser.add_argument("--tree-date", help="the tree's creation date, YYYY-MM-DD (default: the date of the existing tree, else today, local time)")
    parser.add_argument("--tree-title", default="Benchmark tables: the executable in its default configuration, the other engine, the baselines",
                        help="the tree's title")
    args = parser.parse_args()
    write_tree = not (args.no_tree or args.stdout or args.headline)
    if write_tree and not args.tree_author:
        parser.error("writing the tree needs --tree-author (the address of your identity tree in journal/trees/people/); "
                     "--stdout, --headline or --no-tree --markdown FILE write none")
    paths = args.tsv or [os.path.join(ROOT, "work", "perf-history", "warm.tsv")]
    labels = [x for x in args.labels.split(",") if x]
    cells = select(read_rows(paths), labels, args.since, args.default_engine, args.default_since or None,
                   [x for x in args.baseline_labels.split(",") if x] or None)
    CHECKERS = checkers(args.default_engine)
    corpora = [c for c in CORPORA if any(k[0] == c for k in cells)]
    corpora += sorted({k[0] for k in cells} - set(corpora))

    def warm(workers: str):
        def value(cell):
            row = cell.get("warm" + workers)
            if not row:
                return "-", None
            median, best = steady(row), num(row["best_later_s"])
            return f"**{f2(median)}** ({f2(best)})", median
        return value

    def cold(cell):
        row = cell.get("cold8")
        if row:
            return f2(num(row["cold_wall_s"])), num(row["cold_wall_s"])
        row = cell.get("pass1_8")
        if row:
            return f"{f2(num(row['pass1_s']))} (pass 1)", num(row["pass1_s"])
        return "-", None

    def cpu(cell):
        row, crow = cell.get("warm8"), cell.get("cold8")
        w = num(row["cpu_median_s"]) if row else None
        c = num(crow["cold_cpu_s"]) if crow else None
        if w is None and c is None:
            return "-", None
        return f"{f2(w, 1)} / {f2(c, 1)}", w

    def rss(cell):
        row, crow = cell.get("warm8"), cell.get("cold8")
        w = num(row["peak_rss_mib"]) if row else None
        c = num(crow["cold_rss_mib"]) if crow else None
        if w is None and c is None:
            return "-", None
        return f"{f2(w, 0)} / {f2(c, 0)}", w

    dn, dp, dj, on, op, oj, gr, nl = [k for k, _h in CHECKERS]
    other = oj[0]
    if args.headline:
        # The README's one table: what `rlvm FILE` (the executable, no option) costs, per corpus.
        def run(cell):
            c = cell.get("cold8") or cell.get("pass1_8")
            w = cell.get("warm8")
            if c and w and (c.get("dist"), c.get("build"), c.get("what")) != (w.get("dist"), w.get("build"), w.get("what")):
                w = None   # a warm pass of another build or configuration does not belong behind this cold run
            cold_s = num(c["cold_wall_s"]) if c and c is cell.get("cold8") else (num(c["pass1_s"]) if c else None)
            warm_s = steady(w) if w else None
            if cold_s is None and warm_s is None:
                return "-", None
            return (f2(cold_s) if cold_s is not None else "-") + (f" ({f2(warm_s)})" if warm_s is not None else ""), cold_s if cold_s is not None else warm_s
        rows_of = [(dn, f"**`rlvm FILE`** (the executable, default configuration: {args.default_engine} engine)"),
                   (on, f"`rlvm -X engine={other} FILE`"), (gr, "gruszka"), (nl, "nano-lean")]
        lines = table("x", "", cells, corpora, rows_of, run, [("rlvm / gruszka", dn, gr), ("rlvm / nano-lean", dn, nl)])[4:]
        if lines and lines[0].startswith("| Checker, platform"):
            lines[0] = lines[0].replace("| Checker, platform", "| One run, 8 cores: wall clock in s (warm pass)", 1)
        sys.stdout.write("\n".join(l for l in lines if l) + "\n")
        return 0
    now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    head, tail = "", ""
    if args.notes:
        try:
            with open(args.notes) as handle:
                text = handle.read()
            head, _sep, tail = text.partition("<!-- tables -->")
        except OSError:
            pass
    out = ["# RLVM benchmarks", "",
           f"GENERATED by `bench/track/benchmarks.py` on {now} from the rows of `bench/warm.sh` in "
           + ", ".join(f"`{os.path.relpath(p, ROOT) if os.path.abspath(p).startswith(ROOT + os.sep) else p}`" for p in paths)
           + (f" (labels {', '.join(labels)}" + (f"; not older than {args.since}" if args.since else "") + ")" if labels or args.since else "")
           + " and the tracker's history. Do not edit: "
           "re-measure (`bench/warm.sh`, inside `bench/quiet.py` windows) and regenerate with `bench/track/benchmarks.py "
           + " ".join(a if os.path.isabs(a) is False else os.path.relpath(a, ROOT) for a in sys.argv[1:] if a != "--stdout")
           + f"`. Default engine of these tables: **{args.default_engine}** (`rlvm --version`: `default engine: NAME`; when "
           "the default engine changes, re-measure the default rows and pass `--default-engine`).", ""]
    if args.notes_tree:
        out += ["%%tree: \\transclude{" + args.notes_tree + "}", ""]   # the tree includes the hand-written notes; Markdown drops the line
    if head.strip():
        out += [head.strip(), ""]
    out += table("1. Warm wall clock, 8 workers, load pipelined (s)",
                 "The target metric (OWNER-NOTES 12). **Median of the later passes** (best later pass) of one process that checks the "
                 "export N times; every pass is a complete run (load pipelined with checking). Later passes = passes 4..N of a process "
                 "with 8 or more passes (a JVM is still compiling in passes 2 and 3), passes 2..N otherwise; the provenance lists every "
                 "pass. gruszka and nano-lean: the second of two runs with 8 threads (no JIT: their run is their steady state).",
                 cells, corpora, CHECKERS, warm("8"),
                 [("the executable / gruszka", dn, gr), ("optional Oracle build / the executable", dp, dn),
                  (f"{other}: optional Oracle build / the executable", op, on),
                  ("default configuration, JVM / gruszka", dj, gr),
                  (f"{other}, JVM / gruszka", oj, gr), (f"{other}, native / gruszka", on, gr),
                  ("default configuration: JVM / native", dj, dn), (f"{other}: JVM / native", oj, on)], kinds=("warm8",))
    out += table("2. Warm wall clock, 1 worker (s)",
                 "As table 1 with `--threads 1` (one worker; the loader and the environment pass have threads of their own). "
                 "Baselines: one thread.",
                 cells, corpora, CHECKERS, warm("1"),
                 [("default configuration, JVM / gruszka", dj, gr), (f"{other}, JVM / gruszka", oj, gr),
                  ("default configuration: JVM / native", dj, dn), (f"{other}: JVM / native", oj, on)])
    out += table("3. Cold wall clock of a fresh process, 8 workers (s) - for information",
                 "One fresh process, exec to exit, the build's default configuration (what an arena run is, with 8 threads). "
                 "\"(pass 1)\" = no separate cold process was run: the first pass of the warm process, without the launcher's start. "
                 "Baselines: the first of the two runs.",
                 cells, corpora, CHECKERS, cold,
                 [("the executable / gruszka", dn, gr), ("optional Oracle build / the executable", dp, dn),
                  (f"{other}, native / gruszka", on, gr), (f"{other}: optional Oracle build / the executable", op, on),
                  ("default configuration: JVM / native", dj, dn), (f"{other}: JVM / native", oj, on)], kinds=("cold8", "pass1_8"))
    out += table("4. CPU time, 8 workers (s): median warm pass / cold process",
                 "A diagnostic, not the target: process CPU time (all threads: workers, loader, environment thread, JIT compiler, "
                 "collector) of the pass that is the median of table 1, and of the cold process of table 3. CPU / wall says how much "
                 "of the 8 cores a pass uses.",
                 cells, corpora, CHECKERS, cpu)
    out += table("5. Peak RSS, 8 workers (MiB): warm process / cold process",
                 "Peak resident set of the process that made the passes of table 1 (over all its passes), and of the cold process "
                 "of table 3. The JVM launcher's figure is bounded by its heap flags (see the notes), not by what the checker needs.",
                 cells, corpora, CHECKERS, rss, [("default configuration: JVM / native", dj, dn), (f"{other}: JVM / native", oj, on)])
    out += ["## 6. Instructions of a cold process - the arena's metric, for information", "",
            f"User-space instructions of one fresh process in the arena-shaped container (`bench/track/track.py`, `perf stat`), native image, "
            f"`--threads {args.instr_threads}`; baselines with their arena run lines (4 threads) as recorded on this host "
            f"(`bench/baselines.json`). Instruction counts do not depend on the host's load and repeat to 0.2 %.", ""]
    instr_lines, instr_prov = instructions(corpora, os.path.abspath(args.history), args.instr_threads, args.since, args.default_engine,
                                           args.default_since or None)
    out += instr_lines + [""]

    # ---- provenance ----
    windows = quiet_windows()
    out += ["## Provenance: which row, which window, which jars", "",
            "Every cell of tables 1 to 5 is one row below (the latest eligible row of its cell). passes = the wall clock of every "
            "pass of the warm process, in order; window = the `bench/quiet.py` window the row was taken in (start-end UTC and its "
            "command, from `work/perf-history/quiet.log`); build = the distribution the row measured, listed with its jars behind "
            "the table.", "",
            "| Corpus | Checker | Workers | Cells | Row (UTC) | Label | Passes (s) / cold (s) | Window | Build |",
            "|---|---|--:|---|---|---|---|---|---|"]
    builds: dict[str, str] = {}
    seen = set()
    for corpus in corpora:
        for key, heading in CHECKERS:
            cell = cells.get((corpus,) + key, {})
            for kind in ("warm8", "warm1", "cold8", "pass1_8"):
                row = cell.get(kind)
                if not row or id(row) in seen and kind != "warm8":
                    continue
                uses = [k for k in ("warm8", "warm1", "cold8", "pass1_8") if cell.get(k) is row]
                if id(row) in seen:
                    continue
                seen.add(id(row))
                build = row.get("build") or ""
                tag = "-"
                if row.get("dist"):
                    tag = builds.setdefault(row["dist"] + "|" + build, f"D{len(builds) + 1}")
                what = {"warm8": "1, 4, 5", "warm1": "2", "cold8": "3, 4, 5", "pass1_8": "3"}
                cellsused = ", ".join(sorted({x.strip() for u in uses for x in what[u].split(",")}))
                passes = row["walls_s"] or "-"
                coldtxt = row["cold_wall_s"] or "-"
                out.append(f"| {corpus} | {heading} | {row['workers']} | {cellsused} | {row['utc']} | {row['label'] or '-'} | "
                           f"{passes} / {coldtxt} | {window_of(row['utc'], windows)} | {tag} |")
    out.append("")
    for ident, tag in builds.items():
        dist, _sep, build = ident.partition("|")
        jars = jars_of(dist)
        out.append(f"- **{tag}** = `{dist}` (built {build.split('/')[0] or '?'}, RLVM jars sha256 `{build.split('/')[-1] or '?'}`)"
                   + (": " + ", ".join(f"{j.split()[0].replace('rlvm-', '')} `{j.split()[1]}`" for j in jars) if jars
                      else ": the directory is gone; its jars are no longer listed") + ".")
    out.append("")
    if instr_prov:
        out += ["Instruction counts (table 6):", "",
                "| Corpus | Configuration | Entry (UTC) | Figures of that run | Distribution | Command |", "|---|---|---|---|---|---|"]
        out += instr_prov + [""]
    if tail.strip():
        out += [tail.strip(), ""]
    # No path of a machine goes into a tree or a file of the repository: the commands of the quiet
    # windows are shown relative to the repository root, the interpreter by its name.
    out = [line.replace(ROOT + os.sep, "").replace(sys.executable, "python3").replace("/usr/bin/python3", "python3") for line in out]
    text = "\n".join(line for line in out if not line.startswith("%%tree:"))
    if write_tree:
        # The same document as a tree of the journal. Paths under work/ are not part of the repository
        # and a tree never links to them: they stay inline code (foresttree links repository paths only).
        date = args.tree_date
        if not date:
            try:
                with open(args.tree) as handle:
                    found = re.search(r"^\\date\{(\d{4}-\d{2}-\d{2})\}", handle.read(), re.M)
                date = found.group(1) if found else None
            except OSError:
                date = None
        tree_text = foresttree.tree(out, author=args.tree_author, contributor=args.tree_contributor, date=date,
                                    title=args.tree_title, taxon="Report", tags=("benchmark", "performance", "platform"),
                                    meta=(("origin", "docs/BENCHMARKS.md"), ("generated", "bench/track/benchmarks.py"),
                                          ("updated", datetime.date.today().isoformat())))
        os.makedirs(os.path.dirname(args.tree), exist_ok=True)
        tmp = args.tree + ".tmp"
        with open(tmp, "w") as handle:
            handle.write(tree_text)
        os.replace(tmp, args.tree)
        print(f"benchmarks: wrote the tree {os.path.relpath(args.tree, ROOT)} ({len(tree_text.splitlines())} lines; {len(cells)} cells from "
              f"{sum(len(c) for c in cells.values())} rows)")
    if args.stdout:
        sys.stdout.write(text)
    elif args.markdown:
        tmp = args.markdown + ".tmp"
        with open(tmp, "w") as handle:
            handle.write(text)
        os.replace(tmp, args.markdown)
        print(f"benchmarks: wrote the Markdown file {os.path.relpath(args.markdown, ROOT)} ({len(text.splitlines())} lines)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
