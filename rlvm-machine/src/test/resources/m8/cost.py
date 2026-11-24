#!/usr/bin/env python3
"""rlvm-machine/src/test/resources/m8/cost.py - the per-stage cost table of the machine (ISA section 11:
"after each step: CPU time, wall, RSS and instructions"; RECONCILIATION section 0: CPU time first).

    report.sh cost [--stage LABEL] [--runs N] [--host] [--engines machine,reference] [--threads T]
                   [--prop k=v ...] [--jar JAR --name NAME] [--timeout S] [--show] [CORPUS ...]

CORPUS = init | std | cedar | reachability | tutorial | bugs | a file (default: init std cedar reachability).
For every corpus and engine one measured run (or the best of N: minimum CPU time) of

    RLVM_EXTRA_CP=<machine jar> work/dist/bin/rlvm-jvm --threads T "$IN" -X engine=ENGINE -X stats-file=F

in the arena-shaped container (bench/arena-docker.sh --arena-limits --no-network bench/measure.sh: perf stat
task-clock and instructions, GNU time for the peak RSS), exactly as bench/track measures. `--host` measures
on the host with /usr/bin/time instead (no instruction counts; not comparable with the arena numbers).
A directory corpus (tutorial, bugs) is measured as the sum over its files (host mode only).

Every measurement is appended to work/status/machine-cost.tsv; work/status/machine-cost.md is rewritten
from it: per corpus, the newest row of every (stage, engine, configuration), CPU time first, with the
ratio to the reference engine and to gruszka (RECONCILIATION section 0: Init 2.8 s, Std 5.7 s CPU).

A machine row is only a measurement of the machine if it declined nothing: the column "machine declined"
says how many engine checks fell back to the reference kernel; such a row is marked FALLBACK.
"""
import argparse
import datetime
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.realpath(__file__))


def find_root():
    d = HERE
    while d != "/":
        if os.path.isfile(os.path.join(d, "env.sh")) and os.path.isfile(os.path.join(d, "rlvm-machine", "OWNERS.tsv")):
            return d
        d = os.path.dirname(d)
    return os.environ.get("RLVM_ROOT") or sys.exit("cost.py: cannot find the project root (set RLVM_ROOT)")


ROOT = find_root()
TESTS = "work/arena-site/tests/"
CORPORA = {
    "init": "work/exports/init.ndjson",
    "std": "work/exports/std.ndjson",
    "cedar": "work/workloads/slices/cedar-hot-ax.ndjson",
    "reachability": "work/exports/reachability.ndjson",
    "init-prelude": "work/arena-site/tests/good/init-prelude.ndjson",
    "tutorial": TESTS + "good/tutorial",
    "bugs": TESTS + "good/bugs",
}
GRUSZKA_CPU = {"init": 2.8, "std": 5.7}
JAR = ".toolchain/m2/rlvm/rlvm-machine/0.1.0-SNAPSHOT/rlvm-machine-0.1.0-SNAPSHOT.jar"
TSV = os.path.join(ROOT, "work/status/machine-cost.tsv")
MD = os.path.join(ROOT, "work/status/machine-cost.md")
COLUMNS = ["utc", "stage", "corpus", "engine", "config", "mode", "exit", "verdict", "cpu_s", "wall_s", "rss_mib", "instr_g",
           "machine_accepted", "machine_rejected", "machine_declined", "units_declined", "ticks", "runs", "publication"]


def last_publication():
    try:
        with open(os.path.join(ROOT, "work/sandbox/publish.log")) as f:
            lines = [l.strip() for l in f if "fast tests green" in l]
        return lines[-1].split(";")[0] if lines else "-"
    except OSError:
        return "-"


def files_of(corpus):
    p = os.path.join(ROOT, CORPORA.get(corpus, corpus))
    if os.path.isdir(p):
        out = []
        for d, _, fs in os.walk(p):
            out += [os.path.join(d, f) for f in fs if f.endswith(".ndjson")]
        return sorted(out)
    return [os.path.realpath(p)] if os.path.isfile(p) else []


def stats_of(path):
    try:
        with open(path) as f:
            s = json.load(f)
    except (OSError, ValueError):
        return {}
    v = s.get("values", {})
    return {
        "verdict": v.get("driver.verdict", "?"),
        "machine_accepted": v.get("engine.primary.machine.accepted", 0),
        "machine_rejected": v.get("engine.primary.machine.rejected", 0),
        "machine_declined": v.get("engine.primary.machine.declined", 0),
        "units_declined": s.get("units", {}).get("declined", 0),
        "ticks": v.get("engine.primary.machine.ticks", 0),
    }


def run_line(engine, threads, stats, jar, props):
    env = "RLVM_EXTRA_CP=%s" % os.path.join(ROOT, jar)
    if props:
        env += " JAVA_TOOL_OPTIONS='%s'" % " ".join("-D" + p for p in props)
    return '%s %s --threads %d "$IN" -X engine=%s -X stats-file=%s' % (env, os.path.join(ROOT, "work/dist/bin/rlvm-jvm"), threads,
                                                                         engine, stats)


def measure(export, engine, a, tmp):
    """One measured run: dict with exit, cpu_s, wall_s, rss_mib, instr_g and the statistics."""
    stats = os.path.join(tmp, "stats.json")
    raw = os.path.join(tmp, "raw.json")
    for f in (stats, raw):
        if os.path.exists(f):
            os.remove(f)
    line = run_line(engine, a.threads, stats, a.jar, a.prop)
    r = {}
    if a.host:
        cmd = ["/usr/bin/time", "-f", "%e %U %S %M", "-o", raw, "sh", "-c", line]
        env = dict(os.environ, IN=export)
        try:
            p = subprocess.run(cmd, cwd=ROOT, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=a.timeout)
            r["exit"] = p.returncode
            with open(raw) as f:
                e, u, s, m = f.read().split()[-4:]
            r.update(cpu_s=float(u) + float(s), wall_s=float(e), rss_mib=float(m) / 1024.0, instr_g=None)
        except (subprocess.TimeoutExpired, OSError, ValueError) as ex:
            r.update(exit=None, error=str(ex))
    else:
        cmd = [os.path.join(ROOT, "bench/arena-docker.sh"), "--arena-limits", "--no-network", "bench/measure.sh", "--require-perf",
               "--timeout", str(a.timeout), "--cwd", ROOT, "--in", export, "-o", raw, "-c", line]
        p = subprocess.run(cmd, cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        try:
            with open(raw) as f:
                j = json.load(f)
            r.update(exit=j.get("exit_code"), cpu_s=j.get("cpu_time_s"), wall_s=j.get("wall_time_s"),
                     rss_mib=(j.get("max_rss_bytes") or 0) / 1048576.0,
                     instr_g=(j.get("instructions") or 0) / 1e9 if j.get("instructions") else None)
        except (OSError, ValueError):
            r.update(exit=None, error=p.stderr.decode("utf-8", "replace")[-300:])
    r.update(stats_of(stats))
    return r


def measure_corpus(corpus, engine, a, tmp):
    files = files_of(corpus)
    if not files:
        return None
    if len(files) > 1 and not a.host:
        print("cost.py: %s is a directory of %d files: measured on the host (sum over the files)" % (corpus, len(files)), file=sys.stderr)
    best = None
    for _ in range(a.runs):
        total = None
        for f in files:
            if len(files) > 1:
                saved, a.host = a.host, True
                r = measure(f, engine, a, tmp)
                a.host = saved
            else:
                r = measure(f, engine, a, tmp)
            if r.get("exit") is None:
                return {"error": r.get("error", "no measurement"), "exit": None}
            if total is None:
                total = dict(r)
                total["verdict"] = r.get("verdict", "?") if len(files) == 1 else "sum of %d files" % len(files)
            else:
                for k in ("cpu_s", "wall_s", "machine_accepted", "machine_rejected", "machine_declined", "units_declined", "ticks"):
                    total[k] = (total.get(k) or 0) + (r.get(k) or 0)
                total["rss_mib"] = max(total.get("rss_mib") or 0, r.get("rss_mib") or 0)
                total["exit"] = max(total["exit"], r["exit"])
        if best is None or total["cpu_s"] < best["cpu_s"]:
            best = total
    return best


def fmt(x, digits=2):
    if x is None or x == "":
        return "-"
    try:
        return ("%." + str(digits) + "f") % float(x)
    except ValueError:
        return str(x)


def render():
    rows = []
    if os.path.isfile(TSV):
        with open(TSV) as f:
            for line in f:
                p = line.rstrip("\n").split("\t")
                if line.startswith("#") or len(p) < len(COLUMNS):
                    continue
                rows.append(dict(zip(COLUMNS, p)))
    out = ["# Machine cost per stage (work package M8)", "",
           "Regenerate: `rlvm-machine/src/test/resources/m8/report.sh cost --stage LABEL [--runs 3] [CORPUS..]` (appends to",
           "`work/status/machine-cost.tsv`, rewrites this file). Launcher `work/dist/bin/rlvm-jvm --threads 1`, machine jar on",
           "`RLVM_EXTRA_CP`; mode `arena` = the arena-shaped container (8 cores, 16 GB; perf stat + GNU time, as `bench/track`),",
           "mode `host` = /usr/bin/time on the host (no instruction counts). CPU time is the metric (owner note 10); columns in",
           "the order CPU, wall, RSS, instructions. A machine row marked FALLBACK measured the machine's attempt PLUS the",
           "reference kernel's re-check of everything it declined: it is not a measurement of the machine.",
           "",
           "The host is shared: other load only ever adds CPU time (the reference engine on Init was measured at 23.6 s and at",
           "15.9 s within an hour, with the same 207.8 G instructions). Each row shows the newest measurement of its key (stage,",
           "engine, configuration, mode) and the BEST CPU time over all measurements of that key with their number; the ratios",
           "use the best figures. A stage label names a state of the machine: re-measure under a new label after a change.", ""]
    corpora = []
    for r in rows:
        if r["corpus"] not in corpora:
            corpora.append(r["corpus"])
    def num(x):
        try:
            return float(x)
        except (TypeError, ValueError):
            return None

    for c in corpora:
        newest = {}
        best = {}   # the minimum CPU time of every measurement of a row's key: other load can only add CPU time
        count = {}
        for r in rows:
            if r["corpus"] == c:
                key = (r["stage"], r["engine"], r["config"], r["mode"])
                newest[key] = r
                cpu = num(r["cpu_s"])
                if cpu is not None and r["exit"] in ("0", "1"):
                    best[key] = min(best.get(key, cpu), cpu)
                count[key] = count.get(key, 0) + 1
        ref_best = {}
        for (stage, engine, config, mode), b in best.items():
            if engine == "reference":
                ref_best[mode] = min(ref_best.get(mode, b), b)
        out += ["## %s" % c, "",
                "| stage | engine | configuration | mode | CPU s (newest) | best CPU s (measurements) | wall s | RSS MiB | instr G | verdict | machine accepted / declined | best CPU vs reference's best | best CPU vs gruszka | measured | main tree at |",
                "|---|---|---|---|--:|--:|--:|--:|--:|---|---|--:|--:|---|---|"]
        for key, r in newest.items():
            stage, engine, config, mode = key
            b = best.get(key)
            ref = ref_best.get(mode)
            vs_ref = "%.2fx" % (b / ref) if b is not None and ref and engine != "reference" else "-"
            vs_g = "%.1fx" % (b / GRUSZKA_CPU[c]) if b is not None and c in GRUSZKA_CPU and mode == "arena" else "-"
            declined = r["machine_declined"]
            fallback = engine != "reference" and declined not in ("0", "", "-")
            out.append("| %s | %s%s | %s | %s | %s | %s (%d) | %s | %s | %s | %s (exit %s) | %s / %s | %s | %s | %s | %s |" % (
                stage, engine, " **FALLBACK**" if fallback else "", config or "-", mode, fmt(r["cpu_s"]),
                fmt(b) if b is not None else "-", count[key], fmt(r["wall_s"]),
                fmt(r["rss_mib"], 0), fmt(r["instr_g"]), r["verdict"], r["exit"],
                r["machine_accepted"] if engine != "reference" else "-", declined if engine != "reference" else "-", vs_ref, vs_g,
                r["utc"], r["publication"].replace("|", "/")))
        out.append("")
    with open(MD, "w") as f:
        f.write("\n".join(out) + "\n")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("corpora", nargs="*")
    ap.add_argument("--stage", default="wip")
    ap.add_argument("--runs", type=int, default=1)
    ap.add_argument("--host", action="store_true")
    ap.add_argument("--engines", default="machine,reference")
    ap.add_argument("--threads", type=int, default=1)
    ap.add_argument("--prop", action="append", default=[], help="system property of the launcher JVM (rlvm.machine.hostDepth=0, rlvm.machine.switches=warm,gated)")
    ap.add_argument("--jar", default=JAR)
    ap.add_argument("--name", default="", help="label of a non-default jar or configuration")
    ap.add_argument("--timeout", type=int, default=3600)
    ap.add_argument("--show", action="store_true", help="only rewrite and print machine-cost.md")
    a = ap.parse_args()
    if a.show:
        render()
        print(open(MD).read())
        return
    if not os.path.isfile(os.path.join(ROOT, "work/dist/bin/rlvm-jvm")):
        sys.exit("cost.py: no work/dist/bin/rlvm-jvm (run: scripts/dev.sh dist)")
    if not os.path.isfile(os.path.join(ROOT, a.jar)):
        sys.exit("cost.py: no machine jar " + a.jar)
    corpora = a.corpora or ["init", "std", "cedar", "reachability"]
    config = " ".join(([a.name] if a.name else []) + a.prop + (["threads=%d" % a.threads] if a.threads != 1 else []))
    pub = last_publication()
    os.makedirs(os.path.dirname(TSV), exist_ok=True)
    if not os.path.isfile(TSV):
        with open(TSV, "w") as f:
            f.write("# " + "\t".join(COLUMNS) + "\n")
    status = 0
    with tempfile.TemporaryDirectory(prefix="m8-cost-", dir=os.path.join(ROOT, "work")) as tmp:
        for c in corpora:
            for engine in a.engines.split(","):
                r = measure_corpus(c, engine, a, tmp)
                if r is None:
                    print("cost.py: no export for corpus %s" % c, file=sys.stderr)
                    status = 2
                    continue
                if r.get("exit") is None:
                    print("cost.py: %s / %s: no measurement: %s" % (c, engine, r.get("error")), file=sys.stderr)
                    status = 2
                    continue
                mode = "host" if (a.host or len(files_of(c)) > 1) else "arena"
                row = [datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"), a.stage, c, engine,
                       config if engine != "reference" else ("threads=%d" % a.threads if a.threads != 1 else ""), mode,
                       str(r["exit"]), str(r.get("verdict", "?")), fmt(r["cpu_s"], 3), fmt(r["wall_s"], 3), fmt(r["rss_mib"], 1),
                       fmt(r["instr_g"], 3) if r.get("instr_g") is not None else "-", str(r.get("machine_accepted", 0)),
                       str(r.get("machine_rejected", 0)), str(r.get("machine_declined", 0)), str(r.get("units_declined", 0)),
                       str(r.get("ticks", 0)), str(a.runs), pub]
                with open(TSV, "a") as f:
                    f.write("\t".join(row) + "\n")
                print("%-13s %-10s %-6s CPU %8s s  wall %8s s  RSS %6s MiB  instr %8s G  exit %s  machine accepted %s declined %s" % (
                    c, engine, mode, row[8], row[9], row[10], row[11], row[6], row[12], row[14]))
    render()
    print("table: " + MD)
    sys.exit(status)


if __name__ == "__main__":
    main()
