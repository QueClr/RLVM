#!/usr/bin/env python3
"""scripts/platform-matrix.py - the platform comparison behind *agentic-note*: design-platform-document.

Runs a matrix  configurations x corpora x worker counts x repeats  of the RLVM launchers, each run
measured exactly like the arena measures a checker (bench/measure.sh: perf stat + GNU time around
`sh -c RUN_LINE`), and appends one JSON line per run to a results file.

    scripts/platform-matrix.py [options] --configs A,B,... --corpora init,std,... --tag NAME   (host)

    --dist DIR        distribution to measure (default work/steward/platform/dist: a FROZEN copy,
                      so that work/dist can be rebuilt while a matrix runs)
    --configs LIST    configurations (see CONFIGS below; `--list` prints them)
    --corpora LIST    names of work/exports/NAME.ndjson (or work/workloads/slices/NAME.ndjson: cedar-hot-ax)
    --threads LIST    worker counts (default 1,8)
    --repeat K        repeats (default 1). Runs are interleaved: for each repeat, for each corpus,
                      for each worker count, every configuration once - so that a disturbance of the
                      host hits all configurations alike
    --tag NAME        results go to work/steward/platform/results/NAME.jsonl (appended)
    --timeout S       per run (default 900; use 4200 for mathlib)
    --engine NAME     -X engine=NAME (default reference)
    --no-limits       do not restrict the container to 8 CPUs / 16 GB
    --cpuset LIST     run in a container with exactly these CPUs and 16 GB instead of the arena's
                      eight (e.g. 18,19: the two performance cores of this host that the arena-shaped
                      container does not use - for ONE-worker runs, which can then be measured while
                      somebody else measures in the eight; implies --no-lock). Recorded per run
    --list            print the configurations and exit

The host part starts one container (bench/arena-docker.sh --arena-limits --no-network: 8 performance
cores, 16 GB, like the arena runner) per group (repeat, corpus, worker count) and runs the group's
configurations in it, one run at a time. While a group runs it holds the measurement lock of
bench/track/ladder.py (work/perf-history/.ladder.lock): two measurements in the same eight cores
would spoil each other's CPU time. The lock is released between groups, so that a ladder and a
matrix interleave instead of one waiting for the whole of the other.

What a run is: the arena run line of scripts/arena/rlvm.yaml with the binary replaced,
    RLVM_STATUS_BASE=10 [RLVM_JAVA_OPTS='...'] DIST/bin/BINARY [-J...] --threads N "$IN" -X engine=E -X load=batch; case ...
cwd = project root, IN = the export, LC_ALL=C. A JVM configuration without `java_opts` uses the
launcher's own default flags (what `bin/rlvm-jvm` ships with); one with `java_opts` replaces ALL of
them through RLVM_JAVA_OPTS, so every flag of such a run is in its record. Every JVM run also gets
-Xlog:gc (to a file under work/steward/platform/gclogs/), from which the collection counts and pause
totals are added to the record; the log costs a few lines of output per collection.

Each record: config, binary, java_opts, corpus, threads, run, exit_code, cpu_time_s, wall_time_s,
max_rss_bytes, instructions (and the other fields of bench/measure.py), gc_young, gc_full,
gc_pause_ms, loadavg_before / loadavg_after (1-minute load average of the HOST, to spot disturbed
runs), other_containers_at_group_start (containers of other people on the same CPUs; not everybody
takes the lock), utc. TAG.groups.jsonl has one line per group with the names of those containers
at the start and at the end.

Python stdlib only.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time

ROOT = os.path.realpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

# Flags every JVM configuration needs (fail-closed exit on OOM; silence JDK 25 warnings of Truffle).
BASE = "-XX:+ExitOnOutOfMemoryError --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow"
# Two words: a stock OpenJDK refuses -XX:-UseJVMCICompiler without the unlock (*agentic-note*: rep-OPT-J).
C2 = "-XX:+UnlockExperimentalVMOptions -XX:-UseJVMCICompiler"
GRAAL = "-XX:+UseJVMCICompiler"
CI2 = "-XX:CICompilerCount=2"
SERIAL = "-XX:+UseSerialGC"
HEAP13 = "-Xms13g -Xmx13g -Xmn1g"


def jvm(*parts: str) -> dict:
    return {"binary": "rlvm-jvm", "java_opts": " ".join((BASE,) + parts)}


CONFIGS: dict[str, dict] = {
    # --- the three launchers as built -----------------------------------------------------------
    "jvm": {"binary": "rlvm-jvm", "java_opts": None,
            "what": "JVM launcher, its default flags"},
    "native": {"binary": "rlvm", "java_opts": None,
               "what": "native image, GraalVM CE, serial GC"},
    # The optional Oracle GraalVM build is the same single executable in a distribution of its own
    # (scripts/build.sh --oracle-pgo -> work/dist-oracle-pgo/bin/rlvm): run the matrix with
    # --dist work/dist-oracle-pgo --configs native to measure it. No build makes bin/rlvm-pgo any more.
    # --- host JIT ---------------------------------------------------------------------------------
    "jvm-c2": dict(jvm(SERIAL, C2, CI2, HEAP13), what="C2 host JIT, 2 compiler threads (= launcher default, spelled out)"),
    "jvm-c2-auto": dict(jvm(SERIAL, C2, HEAP13), what="C2 host JIT, HotSpot's own compiler thread count"),
    "jvm-graal": dict(jvm(SERIAL, GRAAL, CI2, HEAP13), what="Graal (libgraal) host JIT, 2 compiler threads"),
    "jvm-graal-auto": dict(jvm(SERIAL, GRAAL, HEAP13), what="Graal host JIT, HotSpot's own compiler thread count"),
    "jvm-c1": dict(jvm(SERIAL, "-XX:TieredStopAtLevel=1", HEAP13), what="C1 only (no optimising host JIT)"),
    # --- collectors (C2, 2 compiler threads, same heap) -------------------------------------------
    "jvm-parallel-2": dict(jvm("-XX:+UseParallelGC -XX:ParallelGCThreads=2", C2, CI2, HEAP13),
                           what="Parallel GC capped at 2 collector threads"),
    "jvm-parallel-4": dict(jvm("-XX:+UseParallelGC -XX:ParallelGCThreads=4", C2, CI2, HEAP13),
                           what="Parallel GC capped at 4 collector threads"),
    "jvm-parallel-auto": dict(jvm("-XX:+UseParallelGC", C2, CI2, HEAP13),
                              what="Parallel GC, HotSpot's own thread count (8 on 8 vCPUs)"),
    "jvm-g1-2": dict(jvm("-XX:+UseG1GC -XX:ParallelGCThreads=2 -XX:ConcGCThreads=1", C2, CI2, HEAP13),
                     what="G1 capped at 2 parallel + 1 concurrent collector threads"),
    "jvm-g1-4": dict(jvm("-XX:+UseG1GC -XX:ParallelGCThreads=4 -XX:ConcGCThreads=1", C2, CI2, HEAP13),
                     what="G1 capped at 4 parallel + 1 concurrent collector threads"),
    "jvm-g1-auto": dict(jvm("-XX:+UseG1GC", C2, CI2, HEAP13),
                        what="G1, HotSpot's own thread counts (8 + 2 on 8 vCPUs)"),
}


def add_heap_configs() -> None:
    """Heap variants of the default JVM configuration (Serial, C2, 2 compiler threads)."""
    for gb in (13, 12, 11, 10, 9, 8, 7, 6, 5, 4):
        # Fixed heap (committed at once, as today) and a growing one (HotSpot commits as it needs).
        CONFIGS["jvm-heap-fixed-%dg" % gb] = dict(
            jvm(SERIAL, C2, CI2, "-Xms%dg -Xmx%dg -Xmn1g" % (gb, gb)),
            what="Serial, fixed %d GB heap, 1 GB nursery" % gb)
        CONFIGS["jvm-heap-max-%dg" % gb] = dict(
            jvm(SERIAL, C2, CI2, "-Xmx%dg -Xmn1g" % gb),
            what="Serial, heap grows up to %d GB, 1 GB nursery" % gb)
    for mn in ("256m", "512m", "2g", "4g"):
        CONFIGS["jvm-heap-fixed-13g-mn%s" % mn] = dict(
            jvm(SERIAL, C2, CI2, "-Xms13g -Xmx13g -Xmn%s" % mn),
            what="Serial, fixed 13 GB heap, %s nursery" % mn)
    for gb in (13, 10, 8):
        CONFIGS["jvm-parallel-2-max-%dg" % gb] = dict(
            jvm("-XX:+UseParallelGC -XX:ParallelGCThreads=2", C2, CI2, "-Xmx%dg -Xmn1g" % gb),
            what="Parallel GC (2 threads), heap grows up to %d GB" % gb)
        CONFIGS["jvm-g1-2-max-%dg" % gb] = dict(
            jvm("-XX:+UseG1GC -XX:ParallelGCThreads=2 -XX:ConcGCThreads=1", C2, CI2, "-Xmx%dg" % gb),
            what="G1 (2 + 1 threads), heap grows up to %d GB" % gb)
    # A heap that follows the live set: committed lazily from 8 GB up to the same 13 GB limit, kept at
    # live / (1 - MinHeapFreeRatio) after each full collection (HotSpot's default keeps 40 % free,
    # which on mathlib is the whole 13 GB again).
    for free in (15, 25):
        CONFIGS["jvm-heap-track-%d" % free] = dict(
            jvm(SERIAL, C2, CI2, "-Xms8g -Xmx13g -Xmn1g -XX:MinHeapFreeRatio=%d -XX:MaxHeapFreeRatio=%d" % (free, free + 15)),
            what="Serial, 8 GB initial heap growing to 13 GB, %d %% kept free after a full collection" % free)
    CONFIGS["jvm-heap-track-15-mn512m"] = dict(
        jvm(SERIAL, C2, CI2, "-Xms8g -Xmx13g -Xmn512m -XX:MinHeapFreeRatio=15 -XX:MaxHeapFreeRatio=30"),
        what="Serial, 8 GB initial heap growing to 13 GB, 15 % kept free, 512 MB nursery")
    # The native images take -Xmx / -Xmn themselves.
    for gb in (13, 10, 8, 6):
        CONFIGS["native-max-%dg" % gb] = {"binary": "rlvm", "java_opts": None, "args": "-Xmx%dg" % gb,
                                          "what": "native image CE, -Xmx%dg" % gb}
    for mn in ("256m", "1g", "2g"):
        CONFIGS["native-mn%s" % mn] = {"binary": "rlvm", "java_opts": None, "args": "-Xmn%s" % mn,
                                       "what": "native image CE, -Xmn%s" % mn}


add_heap_configs()


def export_of(corpus: str) -> str | None:
    """work/exports/NAME.ndjson (the arena's corpora), else work/workloads/slices/NAME.ndjson (the
    slices of docs/research/13, e.g. cedar-hot-ax = "the Cedar slice"); None if neither exists."""
    for rel in ("work/exports/%s.ndjson", "work/workloads/slices/%s.ndjson"):
        path = os.path.join(ROOT, rel % corpus)
        if os.path.isfile(path):
            return path
    return None


def loadavg() -> float:
    try:
        with open("/proc/loadavg") as f:
            return float(f.read().split()[0])
    except (OSError, ValueError):
        return -1.0


def gc_summary(path: str) -> dict:
    """Collection counts and the sum of pauses from a HotSpot -Xlog:gc file."""
    young = full = other = 0
    pause_ms = 0.0
    collector = None
    try:
        with open(path, errors="replace") as f:
            for line in f:
                m = re.search(r"Using (\w+)", line)
                if m and collector is None:
                    collector = m.group(1)
                if "Pause" not in line:
                    continue
                m = re.search(r"([0-9.]+)ms\s*$", line)
                if m:
                    pause_ms += float(m.group(1))
                if "Pause Young" in line:
                    young += 1
                elif "Pause Full" in line:
                    full += 1
                else:
                    other += 1
    except OSError:
        return {}
    return {"gc_collector": collector, "gc_young": young, "gc_full": full, "gc_other_pauses": other,
            "gc_pause_ms": round(pause_ms, 1)}


def run_line(dist: str, cfg: dict, threads: int, engine: str, gclog: str | None) -> str:
    env = "RLVM_STATUS_BASE=10"
    args = cfg.get("args", "")
    if cfg["binary"] == "rlvm-jvm":
        if cfg.get("java_opts"):
            opts = cfg["java_opts"] + (" -Xlog:gc:file=%s" % gclog if gclog else "")
            env += " RLVM_JAVA_OPTS='%s'" % opts
        elif gclog:
            args = (args + " -J-Xlog:gc:file=%s" % gclog).strip()
    return ('%s %s/bin/%s %s--threads %d "$IN" -X engine=%s -X load=batch; '
            'case $? in 10) exit 0;; 11) exit 1;; 12) exit 2;; *) exit 3;; esac'
            % (env, dist, cfg["binary"], args + " " if args else "", threads, engine))


def inside(a: argparse.Namespace) -> int:
    configs = a.configs.split(",")
    corpora = a.corpora.split(",")
    threads = [int(t) for t in a.threads.split(",")]
    for c in configs:
        if c not in CONFIGS:
            print("platform-matrix: unknown configuration %r (see --list)" % c, file=sys.stderr)
            return 2
        binary = os.path.join(a.dist, "bin", CONFIGS[c]["binary"])
        if not os.access(binary, os.X_OK):
            print("platform-matrix: %s is not built" % binary, file=sys.stderr)
            return 70
    for corpus in corpora:
        if export_of(corpus) is None:
            print("platform-matrix: no work/exports/%s.ndjson and no work/workloads/slices/%s.ndjson"
                  % (corpus, corpus), file=sys.stderr)
            return 2
    out_dir = os.path.join(ROOT, "work/steward/platform/results")
    gc_dir = os.path.join(ROOT, "work/steward/platform/gclogs")
    os.makedirs(out_dir, exist_ok=True)
    os.makedirs(gc_dir, exist_ok=True)
    out = os.path.join(out_dir, a.tag + ".jsonl")
    build_info = ""
    try:
        with open(os.path.join(a.dist, "BUILD-INFO.txt")) as f:
            build_info = f.readline().strip() + " | " + f.readline().strip()
    except OSError:
        pass
    total = len(corpora) * len(threads) * len(configs)
    done = 0
    failed = 0
    for rep in (a.rep,):
        for corpus in corpora:
            export = export_of(corpus)
            for t in threads:
                for c in configs:
                    cfg = CONFIGS[c]
                    done += 1
                    gclog = None
                    if cfg["binary"] == "rlvm-jvm":
                        gclog = os.path.join(gc_dir, "%s-%s-%s-t%d-r%d.log" % (a.tag, c, corpus, t, rep))
                        try:
                            os.remove(gclog)
                        except OSError:
                            pass
                    cmd = run_line(a.dist, cfg, t, a.engine, gclog)
                    before = loadavg()
                    utc = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
                    p = subprocess.run(
                        [os.path.join(ROOT, "bench/measure.sh"), "--require-perf", "--timeout", str(a.timeout),
                         "--cwd", ROOT, "--in", export, "-c", cmd],
                        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
                    after = loadavg()
                    line = p.stdout.strip().splitlines()[-1] if p.stdout.strip() else ""
                    try:
                        m = json.loads(line)
                    except ValueError:
                        failed += 1
                        print("%-26s %-13s %3d %3d  NO MEASUREMENT (perf unavailable?)" % (c, corpus, t, rep), flush=True)
                        continue
                    rec = {"config": c, "what": cfg.get("what", ""), "binary": cfg["binary"],
                           "java_opts": cfg.get("java_opts"), "args": cfg.get("args", ""), "corpus": corpus,
                           "threads": t, "run": rep, "engine": a.engine, "tag": a.tag, "utc": utc,
                           "loadavg_before": before, "loadavg_after": after, "dist": build_info,
                           "other_containers_at_group_start": a.others,
                           "limits": ("none" if a.no_limits else
                                      "cpus %s / 16 GB" % a.cpuset if a.cpuset else "8 cpus / 16 GB"),
                           "command": cmd}
                    rec.update(m)
                    if gclog:
                        rec.update(gc_summary(gclog))
                    with open(out, "a") as f:
                        f.write(json.dumps(rec) + "\n")
                    ins = m.get("instructions")
                    gc = ""
                    if "gc_young" in rec:
                        gc = "%dy %df %.0f ms" % (rec["gc_young"], rec["gc_full"], rec["gc_pause_ms"])
                    exit_code = "t/o" if m.get("timed_out") else m.get("exit_code")
                    if exit_code != 0:
                        failed += 1
                    print("%-26s %-13s %3d %3d %5s %9.2f %9.2f %9.0f %10s %6.1f  %s   [%d/%d]" % (
                        c, corpus, t, rep, exit_code, m.get("cpu_time_s") or 0.0, m.get("wall_time_s") or 0.0,
                        (m.get("max_rss_bytes") or 0) / 2**20, "n/a" if ins is None else "%.1f" % (ins / 1e9),
                        after, gc, done, total), flush=True)
    return 1 if failed else 0


# The cpuset of `bench/arena-docker.sh --arena-limits` on this host (the first eight of the fastest cores).
ARENA_CPUS = "5,6,7,8,9,15,16,17"


def other_containers(cpus: str) -> list[str]:
    """Names of running containers whose cpuset overlaps `cpus` (other people's measurements)."""
    want = set(cpus.split(","))
    try:
        names = subprocess.run(["docker", "ps", "--format", "{{.Names}}"], capture_output=True, text=True,
                               timeout=20).stdout.split()
        out = []
        for n in names:
            cs = subprocess.run(["docker", "inspect", "-f", "{{.HostConfig.CpusetCpus}}", n], capture_output=True,
                                text=True, timeout=20).stdout.strip()
            if not cs or want & set(cs.split(",")):
                out.append(n)
        return out
    except (OSError, subprocess.SubprocessError):
        return []


def run_group(cmd: list[str]) -> int:
    """Runs one container; a SIGTERM / SIGINT to this process stops the container too (arena-docker.sh
    forwards the signal), so that a killed matrix never leaves a measurement running without its lock."""
    import signal
    child = subprocess.Popen(cmd)

    def stop(signum, frame):
        try:
            child.send_signal(signal.SIGTERM)
        except OSError:
            pass
        try:
            child.wait(timeout=60)
        except subprocess.TimeoutExpired:
            child.kill()
        sys.exit(128 + signum)

    old_term = signal.signal(signal.SIGTERM, stop)
    old_int = signal.signal(signal.SIGINT, stop)
    try:
        return child.wait()
    finally:
        signal.signal(signal.SIGTERM, old_term)
        signal.signal(signal.SIGINT, old_int)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dist", default=os.path.join(ROOT, "work/steward/platform/dist"))
    ap.add_argument("--configs", default="jvm,native")
    ap.add_argument("--corpora", default="init")
    ap.add_argument("--threads", default="1,8")
    ap.add_argument("--repeat", type=int, default=1)
    ap.add_argument("--tag", default="adhoc")
    ap.add_argument("--timeout", type=int, default=900)
    ap.add_argument("--engine", default="reference")
    ap.add_argument("--no-limits", action="store_true")
    ap.add_argument("--cpuset", default="")
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--inside", action="store_true", help=argparse.SUPPRESS)
    ap.add_argument("--rep", type=int, default=1, help=argparse.SUPPRESS)
    ap.add_argument("--others", type=int, default=0, help=argparse.SUPPRESS)
    ap.add_argument("--first-run", type=int, default=1, help="number of the first repeat (to add repeats to a tag)")
    ap.add_argument("--no-lock", action="store_true", help="do not take the measurement lock")
    a = ap.parse_args()
    if a.list:
        for name, cfg in CONFIGS.items():
            print("%-26s %-9s %s" % (name, cfg["binary"], cfg.get("what", "")))
            if cfg.get("java_opts"):
                print("%-26s           %s" % ("", cfg["java_opts"]))
        return 0
    a.dist = os.path.realpath(a.dist)
    if not a.dist.startswith(ROOT + os.sep):
        print("platform-matrix: the distribution must lie below %s" % ROOT, file=sys.stderr)
        return 2
    if a.inside:
        return inside(a)
    docker = [os.path.join(ROOT, "bench/arena-docker.sh"), "--no-network"]
    if a.cpuset:
        docker += ["--cpuset-cpus", a.cpuset, "--memory", "16g", "--docker-arg", "--memory-swap", "--docker-arg", "16g"]
        a.no_lock = True
    elif not a.no_limits:
        docker.append("--arena-limits")
    lock_path = os.path.join(ROOT, "work/perf-history/.ladder.lock")
    os.makedirs(os.path.dirname(lock_path), exist_ok=True)
    print("%-26s %-13s %3s %3s %5s %9s %9s %9s %10s %6s  %s" % (
        "config", "corpus", "thr", "run", "exit", "cpu s", "wall s", "RSS MB", "instr G", "load", "gc"), flush=True)
    worst = 0
    for rep in range(a.first_run, a.first_run + a.repeat):
        for corpus in a.corpora.split(","):
            for t in a.threads.split(","):
                cmd = docker + ["python3", "-I", os.path.abspath(__file__), "--inside", "--dist", a.dist,
                                "--configs", a.configs, "--corpora", corpus, "--threads", t, "--rep", str(rep),
                                "--tag", a.tag, "--timeout", str(a.timeout), "--engine", a.engine]
                if a.no_limits:
                    cmd.append("--no-limits")
                if a.cpuset:
                    cmd += ["--cpuset", a.cpuset]
                lock = None
                if not a.no_lock:
                    import fcntl
                    lock = open(lock_path, "w")
                    try:
                        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    except OSError:
                        print("platform-matrix: another measurement holds %s; waiting" % lock_path, file=sys.stderr,
                              flush=True)
                        fcntl.flock(lock, fcntl.LOCK_EX)
                others = other_containers(a.cpuset or ARENA_CPUS)
                cmd += ["--others", str(len(others))]
                try:
                    rc = run_group(cmd)
                finally:
                    if lock is not None:
                        lock.close()
                after = other_containers(a.cpuset or ARENA_CPUS)
                if others or after:
                    print("platform-matrix: NOTE: other containers on the same CPUs: %s at the start, %s at the end "
                          "of the group (rep %d, %s, %s workers)" % (", ".join(others) or "none",
                                                                     ", ".join(after) or "none", rep, corpus, t),
                          flush=True)
                with open(os.path.join(ROOT, "work/steward/platform/results", a.tag + ".groups.jsonl"), "a") as gf:
                    gf.write(json.dumps({"tag": a.tag, "run": rep, "corpus": corpus, "threads": int(t),
                                         "configs": a.configs, "others_before": others, "others_after": after,
                                         "exit": rc}) + "\n")
                worst = max(worst, rc)
                if rc not in (0, 1):
                    return rc
    print("platform-matrix: done; results: %s" % os.path.join(ROOT, "work/steward/platform/results", a.tag + ".jsonl"),
          flush=True)
    return worst


if __name__ == "__main__":
    sys.exit(main())
