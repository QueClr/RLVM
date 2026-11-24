#!/usr/bin/env python3
"""rlvm-machine/src/test/resources/m8/ladder.py - THE SCOREBOARD of engine "machine" (owner: M8).

    ladder.py measure [--sets 'v0;warm,gated,expect;warm,gated,expect,probes'] [--corpora init,std,cedar,reach]
                      [--threads 1,4] [--runs 1] [--no-gruszka] [--dist DIR] [--note TEXT] [--no-announce]
    ladder.py render
    ladder.py status          the published machine jar and whether it has rows

`measure` takes the machine jar that is PUBLISHED now (.toolchain/m2), builds a CE native image -O3 of the
published jars (scripts/build.sh --published; cached per jar set in work/m8/ladder/), and under ONE hold of the
measurement lock (work/perf-history/.ladder.lock) runs, in the arena-shaped container (bench/arena-docker.sh
--arena-limits, bench/measure.sh: perf stat task-clock + instructions, GNU time peak RSS):

    the reference engine, gruszka (its arena run line), and the machine with every switch set
    on Init, Std, the Cedar slice (work/workloads/slices/cedar-hot-ax.ndjson) and reachability, with 1 and 4 workers.

Every run is one row of work/status/machine-ladder.tsv (CPU s first, then wall s, peak RSS MiB, instructions G,
the verdict, what the machine accepted / declined). Rows of one invocation form a BATCH: compare inside a batch
only (the host is shared; CPU time moves with its load, instructions do not). `render` rewrites
work/status/machine-ladder.md from the TSV: per batch one table for 1 worker and one for 4, with the ratio to
the reference engine and to gruszka measured in the same batch; then the history of every (jar, switch set).
Each new batch is announced in work/status/machine-M8.log (one line per switch set), so that the owners see
the effect of what they published. A machine row whose engine checks fell back to the reference kernel is
marked FALLBACK; a run with a wrong exit status is marked WRONG and never enters a ratio.

A warm switch set runs with `-X session-mib=4` (the integrator's measured cap; --session-mib N to change it).
"""
import argparse, datetime, fcntl, hashlib, json, os, re, subprocess, sys, tempfile

ROOT = os.path.dirname(os.path.abspath(__file__))
while ROOT != "/" and not (os.path.isfile(os.path.join(ROOT, "env.sh")) and os.path.isfile(os.path.join(ROOT, "rlvm-machine", "OWNERS.tsv"))):
    ROOT = os.path.dirname(ROOT)
if ROOT == "/":
    ROOT = os.environ.get("RLVM_ROOT") or sys.exit("ladder.py: cannot find the project root (set RLVM_ROOT)")

TSV = os.path.join(ROOT, "work/status/machine-ladder.tsv")
MD = os.path.join(ROOT, "work/status/machine-ladder.md")
LOG = os.path.join(ROOT, "work/status/machine-M8.log")
LOCK = os.path.join(ROOT, "work/perf-history/.ladder.lock")
WORK = os.path.join(ROOT, "work/m8/ladder")
JAR = os.path.join(ROOT, ".toolchain/m2/rlvm/rlvm-machine/0.1.0-SNAPSHOT/rlvm-machine-0.1.0-SNAPSHOT.jar")
CORPORA = {"init": "work/exports/init.ndjson", "std": "work/exports/std.ndjson",
           "cedar": "work/workloads/slices/cedar-hot-ax.ndjson", "reach": "work/exports/reachability.ndjson"}
TITLE = {"init": "Init", "std": "Std", "cedar": "Cedar slice", "reach": "reachability"}
# The target (the task statement of wave 6; measured on this host when it was quiet, CPU s at the checker's
# arena run line): used for the ratio only when gruszka was not measured in the batch itself.
GRUSZKA_QUIET = {"init": 2.63, "std": 5.62, "cedar": 1.91, "reach": 3.97}
NANO_QUIET = {"init": 4.46, "std": 8.86, "cedar": 2.95, "reach": 2.08}
COLS = ["utc", "batch", "jar", "published", "engine", "set", "session_mib", "corpus", "threads", "cpu_s", "wall_s", "rss_mib",
        "instr_g", "exit", "accepted", "declined", "rejected", "ticks", "loadavg", "note"]


def now():
    return datetime.datetime.now(datetime.timezone.utc)


def sha12(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()[:12]


def last_publication():
    try:
        lines = open(os.path.join(ROOT, "work/sandbox/publish.log")).read().splitlines()
    except OSError:
        return ""
    for l in reversed(lines):
        if "fast tests green" in l:
            return l.split(";")[0]
    return ""


def read_rows():
    if not os.path.isfile(TSV):
        return []
    rows = []
    for line in open(TSV).read().splitlines():
        if not line or line.startswith("#") or line.startswith("utc\t"):
            continue
        f = line.split("\t")
        f += [""] * (len(COLS) - len(f))
        rows.append(dict(zip(COLS, f)))
    return rows


def append_row(row):
    new = not os.path.isfile(TSV)
    with open(TSV, "a") as f:
        if new:
            f.write("\t".join(COLS) + "\n")
        f.write("\t".join(str(row.get(c, "")) for c in COLS) + "\n")


def build_dist(args):
    """A CE native image -O3 of the jars published now; returns (dist dir, machine jar hash, publication time)."""
    if args.dist:
        dist = os.path.join(ROOT, args.dist)
    else:
        os.makedirs(WORK, exist_ok=True)
        dist = os.path.join(WORK, "dist-" + sha12(JAR))
    info = os.path.join(dist, "BUILD-INFO.txt")
    if not (os.path.isfile(info) and os.access(os.path.join(dist, "bin/rlvm"), os.X_OK)):
        if args.dist:
            sys.exit("ladder.py: %s has no bin/rlvm (build it: scripts/build.sh --published --dist %s)" % (dist, args.dist))
        log = dist + ".build.log"
        print("ladder: building the native image of the published jars -> %s (2-3 minutes; log %s)" % (os.path.relpath(dist, ROOT), os.path.relpath(log, ROOT)), flush=True)
        with open(log, "w") as out:
            p = subprocess.run([os.path.join(ROOT, "scripts/build.sh"), "--published", "--no-pgo", "--opt", "3", "--dist", dist],
                               cwd=ROOT, stdout=out, stderr=subprocess.STDOUT)
        if p.returncode != 0 or not os.path.isfile(info):
            sys.exit("ladder.py: the build failed (%s)" % log)
    text = open(info).read()
    m = re.search(r"rlvm-machine\s+([0-9a-f]{12})\s+published (\S+)", text)
    if not m:
        sys.exit("ladder.py: %s does not name a published rlvm-machine jar" % info)
    return dist, m.group(1), m.group(2)


def measure_one(line, export, timeout):
    """One run in the arena-shaped container; returns the measurement dict or None."""
    tmp = tempfile.mkdtemp(prefix="run-", dir=os.path.join(WORK, "tmp"))
    raw = os.path.join(tmp, "raw.json")
    cmd = [os.path.join(ROOT, "bench/arena-docker.sh"), "--arena-limits", "--no-network", "bench/measure.sh", "--require-perf",
           "--timeout", str(timeout), "--cwd", ROOT, "--in", export, "-o", raw, "-c", line]
    p = subprocess.run(cmd, cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    try:
        j = json.load(open(raw))
    except Exception:
        print("ladder: measurement FAILED: %s\n%s" % (line, p.stderr.decode("utf-8", "replace")[-600:]), flush=True)
        return None, tmp
    return j, tmp


def stats_of(path, engine):
    acc = dec = rej = ticks = "-"
    try:
        s = json.load(open(path))
        v = s.get("values", {})
        if engine == "machine":
            p = "engine.primary.machine."
            acc, dec, rej, ticks = v.get(p + "accepted", "-"), v.get(p + "declined", "-"), v.get(p + "rejected", "-"), v.get(p + "ticks", "-")
        else:
            u = s.get("units", {})
            acc, dec, rej, ticks = u.get("accepted", "-"), u.get("declined", "-"), u.get("rejected", "-"), u.get("steps", "-")
    except Exception:
        pass
    return acc, dec, rej, ticks


def cmd_measure(args):
    sets = [s.strip() for s in args.sets.split(";")]
    sets = ["" if s in ("v0", "generic") else s for s in sets]
    corpora = [c for c in args.corpora.split(",") if c]
    for c in corpora:
        if c not in CORPORA or not os.path.isfile(os.path.join(ROOT, CORPORA[c])):
            sys.exit("ladder.py: no corpus '%s'" % c)
    threads = [int(t) for t in args.threads.split(",")]
    dist, jar, published = build_dist(args)
    os.makedirs(os.path.join(WORK, "tmp"), exist_ok=True)
    rlvm = os.path.join(dist, "bin/rlvm")
    batch = now().strftime("%Y-%m-%dT%H:%MZ")
    print("ladder: batch %s, machine jar %s (published %s), waiting for the measurement lock" % (batch, jar, published), flush=True)
    lock = open(LOCK, "a")
    fcntl.flock(lock, fcntl.LOCK_EX)
    rows = []
    try:
        print("ladder: lock taken %s, load average %s" % (now().strftime("%H:%M:%SZ"), open("/proc/loadavg").read().split()[0]), flush=True)
        for c in corpora:
            export = os.path.join(ROOT, CORPORA[c])
            plan = []
            for t in threads:
                plan.append(("reference", "-", t))
            if not args.no_gruszka and os.path.isdir(os.path.join(ROOT, "work/arena-checkers/_build/checkers/gruszka/src")):
                plan.append(("gruszka", "-", 4))
            for s in sets:
                for t in threads:
                    plan.append(("machine", s, t))
            for engine, s, t in plan:
                for _ in range(args.runs):
                    stats = os.path.join(WORK, "tmp", "stats-%d.json" % os.getpid())
                    if os.path.exists(stats):
                        os.remove(stats)
                    mib = ""
                    if engine == "gruszka":
                        line = '%s "$IN"' % os.path.join(ROOT, "bench/baselines/gruszka.sh")
                    else:
                        x = ["-X engine=%s" % engine, "-X stats-file=%s" % stats]
                        if engine == "machine" and s:
                            x.append("-X machine.switches=%s" % s)
                            if "warm" in s.split(","):
                                mib = str(args.session_mib)
                                x.append("-X session-mib=%s" % mib)
                        line = '%s --threads %d "$IN" %s' % (rlvm, t, " ".join(x))
                    j, tmp = measure_one(line, export, args.timeout)
                    subprocess.run(["rm", "-rf", tmp])
                    if j is None:
                        continue
                    acc, dec, rej, ticks = stats_of(stats, engine) if engine != "gruszka" else ("-", "-", "-", "-")
                    row = dict(utc=now().strftime("%H:%M:%SZ"), batch=batch, jar=jar if engine == "machine" else "-", published=published if engine == "machine" else "-",
                               engine=engine, set=(s or "v0") if engine == "machine" else "-", session_mib=mib, corpus=c, threads=t,
                               cpu_s="%.2f" % j.get("cpu_time_s", 0), wall_s="%.2f" % j.get("wall_time_s", 0),
                               rss_mib="%.0f" % ((j.get("max_rss_bytes") or 0) / 1048576.0), instr_g="%.1f" % ((j.get("instructions") or 0) / 1e9),
                               exit=j.get("exit_code"), accepted=acc, declined=dec, rejected=rej, ticks=ticks,
                               loadavg=open("/proc/loadavg").read().split()[0], note=args.note)
                    append_row(row)
                    rows.append(row)
                    print("ladder: %-9s %-28s %-5s %dw  %6s s CPU  %6s s wall  %5s MiB  %6s G  exit %s  accepted %s declined %s  load %s" % (
                        engine, row["set"], c, t, row["cpu_s"], row["wall_s"], row["rss_mib"], row["instr_g"], row["exit"], acc, dec, row["loadavg"]), flush=True)
    finally:
        fcntl.flock(lock, fcntl.LOCK_UN)
        lock.close()
        print("ladder: lock released %s" % now().strftime("%H:%M:%SZ"), flush=True)
    cmd_render(args)
    if not args.no_announce and rows:
        announce(batch, jar, published, rows)


def best(rows, **key):
    """The row with the least CPU time among the rows that match (a batch may repeat a run)."""
    out = None
    for r in rows:
        if all(str(r.get(k)) == str(v) for k, v in key.items()):
            if out is None or float(r["cpu_s"]) < float(out["cpu_s"]):
                out = r
    return out


def ok_row(r):
    """A machine row that counts: verdict accept and nothing declined to the reference kernel."""
    if r is None or str(r["exit"]) != "0":
        return False
    return r["engine"] != "machine" or str(r["declined"]) in ("0", "-")


def flag(r):
    if str(r["exit"]) != "0":
        return " WRONG(exit %s)" % r["exit"]
    if r["engine"] == "machine" and str(r["declined"]) not in ("0", "-"):
        return " FALLBACK(%s declined)" % r["declined"]
    return ""


def announce(batch, jar, published, rows):
    lines = []
    for s in sorted({r["set"] for r in rows if r["engine"] == "machine"}, key=lambda x: (x != "v0", x)):
        parts = []
        for c in CORPORA:
            m = best(rows, engine="machine", set=s, corpus=c, threads=1)
            ref = best(rows, engine="reference", corpus=c, threads=1)
            g = best(rows, engine="gruszka", corpus=c)
            if m is None:
                continue
            txt = "%s %s s CPU / %s G" % (TITLE[c], m["cpu_s"], m["instr_g"]) + flag(m)
            if ok_row(m) and ref is not None:
                txt += " (%.2fx reference" % (float(m["cpu_s"]) / float(ref["cpu_s"]))
                gc = float(g["cpu_s"]) if g is not None else GRUSZKA_QUIET[c]
                txt += ", %.2fx gruszka%s)" % (float(m["cpu_s"]) / gc, "" if g is not None else " quiet")
            m4 = best(rows, engine="machine", set=s, corpus=c, threads=4)
            if m4 is not None:
                txt += "; 4 workers %s s CPU / %s s wall" % (m4["cpu_s"], m4["wall_s"]) + flag(m4)
            parts.append(txt)
        lines.append("%s M8 LADDER (batch %s, machine jar %s published %s, native -O3, arena container, 1 worker; load %s): [%s] %s. Table: work/status/machine-ladder.md" % (
            now().strftime("%Y-%m-%dT%H:%MZ"), batch, jar, published, rows[-1]["loadavg"], s, "; ".join(parts)))
    with open(LOG, "a") as f:
        for l in lines:
            f.write(l + "\n")
    for l in lines:
        print(l)


def table(out, rows, threads):
    sets = []
    for r in rows:
        if r["engine"] == "machine" and int(r["threads"]) == threads and r["set"] not in sets:
            sets.append(r["set"])
    corpora = [c for c in CORPORA if any(r["corpus"] == c and int(r["threads"]) == threads for r in rows)]
    if not corpora:
        return
    out.append("| engine, switch set | " + " | ".join("%s: CPU s / wall s / RSS MiB / instr G" % TITLE[c] for c in corpora) + " |")
    out.append("|---|" + "---|" * len(corpora))

    def cell(r, c, ratio=True):
        if r is None:
            return "-"
        s = "**%s** / %s / %s / %s" % (r["cpu_s"], r["wall_s"], r["rss_mib"], r["instr_g"]) + flag(r)
        if ratio and ok_row(r):
            ref = best(rows, engine="reference", corpus=c, threads=threads)
            g = best(rows, engine="gruszka", corpus=c)
            bits = []
            if ref is not None:
                bits.append("%.2fx ref" % (float(r["cpu_s"]) / float(ref["cpu_s"])))
            if g is not None:
                bits.append("%.2fx gruszka" % (float(r["cpu_s"]) / float(g["cpu_s"])))
            else:
                bits.append("%.2fx gruszka (quiet)" % (float(r["cpu_s"]) / GRUSZKA_QUIET[c]))
            s += " (" + ", ".join(bits) + ")"
        return s

    out.append("| reference engine | " + " | ".join(cell(best(rows, engine="reference", corpus=c, threads=threads), c, False) for c in corpora) + " |")
    if threads == 1 and any(r["engine"] == "gruszka" for r in rows):
        out.append("| gruszka (its arena run line, 4 threads) | " + " | ".join(cell(best(rows, engine="gruszka", corpus=c), c, False) for c in corpora) + " |")
    for s in sets:
        out.append("| machine `%s` | " % s + " | ".join(cell(best(rows, engine="machine", set=s, corpus=c, threads=threads), c) for c in corpora) + " |")


def cmd_render(args):
    rows = read_rows()
    out = []
    out.append("# machine-ladder: the scoreboard of engine \"machine\"")
    out.append("")
    out.append("Regenerate: `rlvm-machine/src/test/resources/m8/ladder.py measure` (a new batch for the machine jar published now), `ladder.py render` (this page from")
    out.append("`work/status/machine-ladder.tsv`). Written %s; last publication: %s." % (now().strftime("%Y-%m-%dT%H:%MZ"), last_publication() or "?"))
    out.append("")
    out.append("How to read it. Every cell is one run of the CE native image `-O3` of the published jars in the arena-shaped container (8 performance cores, 16 GB),")
    out.append("under the measurement lock: **CPU s** / wall s / peak RSS MiB / instructions G. A batch is one hold of the lock: **compare inside a batch only** - the host")
    out.append("is shared, CPU time moves with its load (the load average of each batch is given), instructions do not. The ratios are CPU time against the reference")
    out.append("engine and against gruszka *in the same batch* (gruszka at its arena run line, 4 threads; where it was not measured, against its CPU time on the quiet")
    out.append("host: Init %.2f s, Std %.2f s, Cedar slice %.2f s, reachability %.2f s; nano-lean there: %.2f / %.2f / %.2f / %.2f s)." % (
        GRUSZKA_QUIET["init"], GRUSZKA_QUIET["std"], GRUSZKA_QUIET["cedar"], GRUSZKA_QUIET["reach"],
        NANO_QUIET["init"], NANO_QUIET["std"], NANO_QUIET["cedar"], NANO_QUIET["reach"]))
    out.append("FALLBACK = the machine declined engine checks, which the reference kernel then decided (the time is not the machine's); WRONG = the run's verdict was not accept.")
    out.append("")
    if not rows:
        out.append("No measurement yet.")
    batches = []
    for r in rows:
        if r["batch"] not in batches:
            batches.append(r["batch"])
    # the target line: the newest batch, best machine row per corpus
    if batches:
        b = [r for r in rows if r["batch"] == batches[-1]]
        out.append("## Where the machine stands (newest batch, 1 worker, best switch set per corpus)")
        out.append("")
        out.append("| corpus | reference CPU s | best machine CPU s | switch set | machine / reference | gruszka CPU s | machine / gruszka | to lose |")
        out.append("|---|--:|--:|---|--:|--:|--:|--:|")
        for c in CORPORA:
            ms = [r for r in b if r["engine"] == "machine" and r["corpus"] == c and int(r["threads"]) == 1 and ok_row(r)]
            ref = best(b, engine="reference", corpus=c, threads=1)
            if not ms or ref is None:
                continue
            m = min(ms, key=lambda r: float(r["cpu_s"]))
            g = best(b, engine="gruszka", corpus=c)
            gc = float(g["cpu_s"]) if g is not None else GRUSZKA_QUIET[c]
            out.append("| %s | %s | **%s** | `%s` | %.2fx | %.2f%s | %.2fx | %.0f%% |" % (TITLE[c], ref["cpu_s"], m["cpu_s"], m["set"], float(m["cpu_s"]) / float(ref["cpu_s"]),
                                                                                   gc, "" if g is not None else " (quiet host)", float(m["cpu_s"]) / gc,
                                                                                   100.0 * (1 - gc / float(m["cpu_s"]))))
        out.append("")
    for bname in reversed(batches):
        b = [r for r in rows if r["batch"] == bname]
        jars = sorted({"%s (published %s)" % (r["jar"], r["published"]) for r in b if r["engine"] == "machine"})
        loads = [float(r["loadavg"]) for r in b if r["loadavg"]]
        notes = sorted({r["note"] for r in b if r["note"]})
        out.append("## Batch %s - machine jar %s" % (bname, ", ".join(jars) or "-"))
        out.append("")
        out.append("Host load average during the batch: %.0f to %.0f.%s" % (min(loads), max(loads), (" " + "; ".join(notes) + ".") if notes else ""))
        out.append("")
        for t in sorted({int(r["threads"]) for r in b if r["engine"] != "gruszka"}):
            out.append("%d worker%s:" % (t, "" if t == 1 else "s"))
            out.append("")
            table(out, b, t)
            out.append("")
    # history per switch set
    if batches:
        out.append("## History: CPU s with 1 worker per (batch, jar, switch set), and the ratio to the reference engine of the same batch")
        out.append("")
        out.append("| batch | load | machine jar | switch set | " + " | ".join(TITLE[c] for c in CORPORA) + " |")
        out.append("|---|--:|---|---|" + "--:|" * len(CORPORA))
        for bname in batches:
            b = [r for r in rows if r["batch"] == bname]
            keys = []
            for r in b:
                if r["engine"] == "machine" and int(r["threads"]) == 1 and (r["jar"], r["set"]) not in keys:
                    keys.append((r["jar"], r["set"]))
            for jar, s in keys:
                cells = []
                for c in CORPORA:
                    m = best(b, engine="machine", jar=jar, set=s, corpus=c, threads=1)
                    ref = best(b, engine="reference", corpus=c, threads=1)
                    if m is None:
                        cells.append("-")
                    elif not ok_row(m):
                        cells.append(m["cpu_s"] + flag(m))
                    else:
                        cells.append("%s (%.2fx)" % (m["cpu_s"], float(m["cpu_s"]) / float(ref["cpu_s"])) if ref is not None else m["cpu_s"])
                loads = [float(r["loadavg"]) for r in b if r["loadavg"]]
                out.append("| %s | %.0f | %s | `%s` | %s |" % (bname, sum(loads) / len(loads), jar, s, " | ".join(cells)))
        out.append("")
    tmp = MD + ".tmp"
    with open(tmp, "w") as f:
        f.write("\n".join(out) + "\n")
    os.replace(tmp, MD)
    print("ladder: wrote %s (%d rows, %d batches)" % (os.path.relpath(MD, ROOT), len(rows), len(batches)))


def cmd_status(args):
    jar = sha12(JAR)
    rows = [r for r in read_rows() if r["jar"] == jar]
    print("published machine jar %s (%s): %d ladder rows%s" % (jar, last_publication(), len(rows), "" if rows else " - run `ladder.py measure`"))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    m = sub.add_parser("measure")
    m.add_argument("--sets", default="v0;warm,gated,expect;warm,gated,expect,probes")
    m.add_argument("--corpora", default="init,std,cedar,reach")
    m.add_argument("--threads", default="1,4")
    m.add_argument("--runs", type=int, default=1)
    m.add_argument("--session-mib", type=int, default=4)
    m.add_argument("--timeout", type=int, default=1800)
    m.add_argument("--dist", default="")
    m.add_argument("--note", default="")
    m.add_argument("--no-gruszka", action="store_true")
    m.add_argument("--no-announce", action="store_true")
    m.set_defaults(fn=cmd_measure)
    r = sub.add_parser("render")
    r.set_defaults(fn=cmd_render)
    s = sub.add_parser("status")
    s.set_defaults(fn=cmd_status)
    a = ap.parse_args()
    a.fn(a)


if __name__ == "__main__":
    main()
