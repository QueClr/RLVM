#!/usr/bin/env python3
"""bench/warm.py - warm wall clock of RLVM (and of the baseline checkers) on the corpora.

OWNER-NOTES 12: the goal is the wall clock of checking with parallel workers in steady state.
"Warm" = the same process checks the same export again (rlvm -X passes=N, rlvm-main Passes).

    bench/warm.sh [options] CORPUS...          CORPUS = a name (work/exports/NAME.ndjson) or a file

One row per (corpus, worker count). For RLVM a row is two processes:
  1. cold:  a fresh process, a plain run (no passes)           -> cold wall / CPU / peak RSS
  2. warm:  one process with -X passes=N -X passes-mode=MODE   -> pass 1, best later pass, median of
            the later passes, CPU and collector time of the median pass, peak RSS of that process
For a baseline (--baseline gruszka|nano-lean; Rust, no JIT) a row is two runs of the checker in the
arena container (bench/arena-docker.sh --arena-limits --no-network bench/measure.sh): the second run
is reported as "warm" (page cache), the first as "cold".

Everything runs inside ONE bench/quiet.py window (the script re-executes itself under it): the host
is serialised for measurements and the rest of the session stays off the 8 measurement cores, on
which the checker's process is pinned. Keep a window short: a few corpora per call. --no-quiet runs
without it and marks the rows "loaded" (not evidence).

Options (RLVM):
  --dist DIR            distribution to measure (default work/dist)
  --launcher jvm|native bin/rlvm-jvm or bin/rlvm (default jvm)
  --engine NAME         -X engine=NAME (default: none = the distribution's default engine)
  --switches S          -X machine.switches=S
  --workers LIST        worker counts, e.g. 8,1 (default 8,1)
  --passes N            passes of the warm process (default 5)
  --mode reload|recheck -X passes-mode (default reload: every pass loads and checks; recheck: the
                        driver's check-only passes over the loaded store)
  --load pipelined|batch  -X load (default pipelined)
  --heap SIZE           JVM: -Xms SIZE -Xmx SIZE -Xmn1g in place of the launcher's 13g / 13g / 1g;
                        native image: -Xmx SIZE (e.g. 6g)
  --java-opts 'FLAGS'   JVM: replaces ALL default VM flags of the launcher (RLVM_JAVA_OPTS), for
                        variants such as the Graal JIT or another collector; give the heap here too
  --arg ARG             extra argument for the checker (repeatable), e.g. --arg -X --arg chunk=8
  --label TEXT          free text for the row (default: derived from the options)
  --no-cold             no separate cold process
Options (baselines):
  --baseline NAME       gruszka | nano-lean, with --workers as its thread counts
Common:
  --out FILE            TSV to append rows to (default work/perf-history/warm.tsv)
  --logs DIR            raw output of every process (default work/perf-history/warm-logs)
  --no-quiet            do not wrap in bench/quiet.py
  --timeout SECONDS     per process (default 3600)

Output: a markdown table on stdout, the same rows appended to the TSV. Times in seconds.
Columns: pass1 = wall of pass 1 in the warm process; best / median = of passes 2..N;
cpu(med) / gc(med) = process CPU time and collector time of the median pass; rss = peak resident
set of the warm process (all passes); cold = wall clock of the fresh process from exec to exit
(the launcher's own start-up included), cold cpu, cold rss; walls = every pass.
"""
import argparse
import json
import os
import re
import shlex
import subprocess
import sys
import threading
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PASS = re.compile(r"^rlvm: pass (\d+)/(\d+) (.*)$")
BASELINES = {
    # name -> (build directory, command with {n} = threads; the arena's own run line at 4 threads)
    "gruszka": ("work/arena-checkers/_build/checkers/gruszka/src", 'scripts/arena-run "$IN" {n}'),
    "nano-lean": ("work/arena-checkers/_build/checkers/nano-lean/src", 'target/release/nl-fast -q -j {n} "$IN"'),
}
COLUMNS = ["utc", "quiet", "corpus", "what", "launcher", "workers", "load", "mode", "passes", "verdict",
           "best_later_s", "median_later_s", "pass1_s", "cold_wall_s", "cpu_median_s", "gc_median_ms",
           "peak_rss_mib", "cold_cpu_s", "cold_rss_mib", "walls_s", "loadavg", "label"]


def corpus_path(name):
    if os.path.isfile(name):
        return os.path.abspath(name), os.path.basename(name).replace(".ndjson", "")
    p = os.path.join(ROOT, "work", "exports", name + ".ndjson")
    if not os.path.isfile(p):
        sys.exit(f"warm.py: no corpus '{name}' ({p})")
    return p, name


def run(cmd, env, log, timeout):
    """Runs cmd; returns (exit status, wall s, cpu s, peak rss kB, stderr text). Output goes to log."""
    t0 = time.monotonic()
    with open(log, "wb") as out:
        p = subprocess.Popen(cmd, env=env, stdout=out, stderr=subprocess.STDOUT)
        killer = threading.Timer(timeout, p.kill)
        killer.start()
        try:
            _, status, ru = os.wait4(p.pid, 0)  # the child's own rusage (CPU, peak RSS)
        finally:
            killer.cancel()
    wall = time.monotonic() - t0
    p.returncode = os.waitstatus_to_exitcode(status)
    with open(log, "r", errors="replace") as f:
        text = f.read()
    return p.returncode, wall, ru.ru_utime + ru.ru_stime, ru.ru_maxrss, text


def kv(rest):
    out = {}
    for item in rest.split():
        if "=" in item:
            k, v = item.split("=", 1)
            out[k] = v
    return out


def passes_of(text):
    """The per-pass lines of rlvm -X passes=N: [{number, mode, wall_ms, cpu_ms, ...}]."""
    out = []
    for line in text.splitlines():
        m = PASS.match(line)
        if m:
            d = kv(m.group(3))
            d["number"] = int(m.group(1))
            d["of"] = int(m.group(2))
            out.append(d)
    return out


def median_item(items, key):
    """The item whose key is the (lower) median: a real pass, so that its other numbers can be quoted."""
    s = sorted(items, key=key)
    return s[(len(s) - 1) // 2]


def fmt(x, digits=2):
    return "-" if x is None else f"{x:.{digits}f}"


def rlvm_row(args, corpus, name, workers, logs, env):
    launcher = os.path.join(args.dist, "bin", "rlvm-jvm" if args.launcher == "jvm" else "rlvm")
    if not os.access(launcher, os.X_OK):
        sys.exit(f"warm.py: {launcher} is not there (scripts/build.sh --dist ...)")
    base = [launcher]
    if args.heap and not args.java_opts:
        base += ([f"-Xms{args.heap}", f"-Xmx{args.heap}", "-Xmn1g"] if args.launcher == "jvm" else [f"-Xmx{args.heap}"])
    base += ["--threads", str(workers), "--stats", "-X", f"load={args.load}"]
    if args.engine:
        base += ["-X", f"engine={args.engine}"]
    if args.switches:
        base += ["-X", f"machine.switches={args.switches}"]
    base += args.arg
    tag = f"{name}-{args.launcher}-{args.engine or 'default'}-{(args.switches or 'v0').replace(',', '+')}-j{workers}-{args.mode}"
    if args.label:
        tag += "-" + re.sub(r"[^A-Za-z0-9_.+-]", "_", args.label)
    stamp = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
    cold = None
    if not args.no_cold:
        rc, wall, cpu, rss, text = run(base + [corpus], env, os.path.join(logs, f"{stamp}-{tag}-cold.log"), args.timeout)
        cold = {"rc": rc, "wall": wall, "cpu": cpu, "rss_kb": rss}
    warm_cmd = base + ["-X", f"passes={args.passes}", "-X", f"passes-mode={args.mode}", corpus]
    log = os.path.join(logs, f"{stamp}-{tag}-warm.log")
    rc, wall, cpu, rss, text = run(warm_cmd, env, log, args.timeout)
    ps = passes_of(text)
    row = {c: "" for c in COLUMNS}
    row.update(corpus=name, launcher=args.launcher, workers=workers, load=args.load, mode=args.mode, passes=args.passes,
               what=(args.engine or "default") + ("+" + args.switches if args.switches else ""),
               label=args.label or "", verdict=f"exit {rc}")
    if cold:
        row.update(cold_wall_s=fmt(cold["wall"]), cold_cpu_s=fmt(cold["cpu"]), cold_rss_mib=str(cold["rss_kb"] // 1024))
        if cold["rc"] != rc:
            row["verdict"] += f" (cold: exit {cold['rc']})"
    row["peak_rss_mib"] = str(rss // 1024)
    if not ps:
        row["verdict"] += " NO PASS LINES"
        print(f"warm.py: no pass lines in {log}", file=sys.stderr)
        return row
    walls = [int(p["wall_ms"]) / 1000.0 for p in ps]
    row["pass1_s"] = fmt(walls[0])
    row["walls_s"] = " ".join(fmt(w) for w in walls)
    verdicts = {p["verdict"] for p in ps}
    row["verdict"] = "/".join(sorted(verdicts)) + ("" if rc in (0, 1, 2) and len(verdicts) == 1 else f" exit {rc}")
    later = ps[1:]
    if later:
        best = min(later, key=lambda p: int(p["wall_ms"]))
        med = median_item(later, lambda p: int(p["wall_ms"]))
        row["best_later_s"] = fmt(int(best["wall_ms"]) / 1000.0)
        row["median_later_s"] = fmt(int(med["wall_ms"]) / 1000.0)
        row["cpu_median_s"] = fmt(int(med["cpu_ms"]) / 1000.0) if int(med["cpu_ms"]) >= 0 else "-"
        row["gc_median_ms"] = med["gc_ms"] if int(med["gc_ms"]) >= 0 else "-"
        if len(ps) < args.passes:
            row["verdict"] += f" ({len(ps)} of {args.passes} passes)"
    return row


def baseline_row(args, corpus, name, workers, logs, env):
    cwd, cmd = BASELINES[args.baseline]
    cwd = os.path.join(ROOT, cwd)
    if not os.path.isdir(cwd):
        sys.exit(f"warm.py: {cwd} is not built (bench/baselines/build-checker.sh {args.baseline})")
    runs = []
    stamp = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
    for k in (1, 2):
        raw = os.path.join(logs, f"{stamp}-{name}-{args.baseline}-j{workers}-run{k}.json")
        docker = [os.path.join(ROOT, "bench", "arena-docker.sh"), "--arena-limits", "--no-network",
                  os.path.join(ROOT, "bench", "measure.sh"), "--timeout", str(args.timeout), "--cwd", cwd, "--in", corpus,
                  "-o", raw, "-c", cmd.format(n=workers)]
        rc, wall, cpu, rss, text = run(docker, env, raw + ".log", args.timeout + 120)
        try:
            with open(raw) as f:
                runs.append(json.load(f))
        except (OSError, ValueError):
            runs.append({"exit_code": rc, "wall_time_s": None, "cpu_time_s": None, "max_rss_bytes": None})
    first, second = runs
    row = {c: "" for c in COLUMNS}
    code = second.get("exit_code")
    row.update(corpus=name, launcher="rust", workers=workers, load="-", mode="second run", passes=2, what=args.baseline,
               label=args.label or "", verdict={0: "ACCEPT", 1: "REJECT", 2: "DECLINE"}.get(code, f"exit {code}"),
               best_later_s=fmt(second.get("wall_time_s")), median_later_s=fmt(second.get("wall_time_s")),
               pass1_s=fmt(first.get("wall_time_s")), cold_wall_s=fmt(first.get("wall_time_s")),
               cpu_median_s=fmt(second.get("cpu_time_s")), gc_median_ms="-",
               peak_rss_mib=str((second.get("max_rss_bytes") or 0) // (1 << 20)),
               cold_cpu_s=fmt(first.get("cpu_time_s")), cold_rss_mib=str((first.get("max_rss_bytes") or 0) // (1 << 20)),
               walls_s=f"{fmt(first.get('wall_time_s'))} {fmt(second.get('wall_time_s'))}")
    return row


def main():
    ap = argparse.ArgumentParser(add_help=False)
    ap.add_argument("corpora", nargs="*")
    ap.add_argument("--dist", default=os.path.join(ROOT, "work", "dist"))
    ap.add_argument("--launcher", choices=["jvm", "native"], default="jvm")
    ap.add_argument("--engine")
    ap.add_argument("--switches")
    ap.add_argument("--workers", default="8,1")
    ap.add_argument("--passes", type=int, default=5)
    ap.add_argument("--mode", choices=["reload", "recheck"], default="reload")
    ap.add_argument("--load", choices=["pipelined", "batch"], default="pipelined")
    ap.add_argument("--heap")
    ap.add_argument("--java-opts")
    ap.add_argument("--arg", action="append", default=[])
    ap.add_argument("--label")
    ap.add_argument("--no-cold", action="store_true")
    ap.add_argument("--baseline", choices=sorted(BASELINES))
    ap.add_argument("--out", default=os.path.join(ROOT, "work", "perf-history", "warm.tsv"))
    ap.add_argument("--logs", default=os.path.join(ROOT, "work", "perf-history", "warm-logs"))
    ap.add_argument("--no-quiet", action="store_true")
    ap.add_argument("--timeout", type=float, default=3600)
    ap.add_argument("-h", "--help", action="store_true")
    args = ap.parse_args()
    if args.help or not args.corpora:
        print(__doc__)
        return 0 if args.help else 64
    quiet = bool(os.environ.get("RLVM_QUIET_NAME"))
    if not quiet and not args.no_quiet:
        # One window for the whole batch: serialised on the host, the others off the measurement cores.
        os.execv(os.path.join(ROOT, "bench", "quiet.py"),
                 [os.path.join(ROOT, "bench", "quiet.py"), "--", sys.executable, os.path.abspath(__file__)] + sys.argv[1:])
    args.dist = os.path.abspath(args.dist)
    os.makedirs(args.logs, exist_ok=True)
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    env = dict(os.environ)
    if args.java_opts:
        env["RLVM_JAVA_OPTS"] = args.java_opts
    rows = []
    for c in args.corpora:
        path, name = corpus_path(c)
        for w in [int(x) for x in args.workers.split(",") if x]:
            load = open("/proc/loadavg").read().split()[0]
            row = (baseline_row if args.baseline else rlvm_row)(args, path, name, w, args.logs, env)
            row["utc"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
            row["quiet"] = "quiet" if quiet else "loaded"
            row["loadavg"] = load
            rows.append(row)
            new = not os.path.exists(args.out)
            with open(args.out, "a") as f:
                if new:
                    f.write("\t".join(COLUMNS) + "\n")
                f.write("\t".join(str(row[c]) for c in COLUMNS) + "\n")
            print(f"warm.py: {name} {row['what']} {row['launcher']} j{w} {row['mode']}: pass1 {row['pass1_s']} best "
                  f"{row['best_later_s']} median {row['median_later_s']} cold {row['cold_wall_s']} | {row['verdict']}",
                  file=sys.stderr, flush=True)
    show = ["corpus", "what", "launcher", "workers", "load", "mode", "best_later_s", "median_later_s", "pass1_s",
            "cold_wall_s", "cpu_median_s", "gc_median_ms", "peak_rss_mib", "walls_s", "verdict", "quiet"]
    head = ["corpus", "engine", "launcher", "workers", "load", "mode", "best later", "median later", "pass 1", "cold wall",
            "cpu(med)", "gc ms(med)", "peak RSS MiB", "walls", "verdict", "host"]
    print("| " + " | ".join(head) + " |")
    print("|" + "---|" * len(head))
    for r in rows:
        print("| " + " | ".join(str(r[c]) for c in show) + " |")
    return 0


if __name__ == "__main__":
    sys.exit(main())
