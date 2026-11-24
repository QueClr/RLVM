#!/usr/bin/env python3
"""bench/track/ladder.py - bench/track/track.py over ALL corpora, for any engine and launcher.

    bench/track/ladder.py [options]                         (run on the host; starts containers)

    bench/track/ladder.py                                   # every tier, JVM + native launcher, reference engine
    bench/track/ladder.py --tier small                      # init, std, cedar, reachability, cedar-hot-ax: 3 runs each
    bench/track/ladder.py --tier small --engine machine --launcher jvm --extra-cp rlvm-machine/target/classes
    bench/track/ladder.py --only cedar-hot-ax,init --launcher jvm --threads 1
    bench/track/ladder.py --tier small --launcher jvm --passes 5      # WARM entries: 5 passes per process (OWNER-NOTES 12)
    bench/track/ladder.py --baselines gruszka,nano-lean,official --no-rlvm     # only the baselines that are missing
    bench/track/ladder.py --list                            # the ladder and what is recorded for it; measures nothing

Tiers (ARCHITECTURE 9.4; owner note 9: Cedar-level workloads are the yardstick):

    small   init, std, cedar, reachability, cedar-hot-ax (the Cedar slice of docs/research/13)   3 runs each
    perf    the evaluation-heavy perf/ tests of docs/research/13, section 4 (four good, four bad)  3 runs each
    large   con-leche, navier-stokes-euler, cslib, mathlib                                         1 run each

Per corpus and launcher one call of track.py (history work/perf-history/CORPUS.jsonl, comparison with
the baselines of this host, wall-clock regression rule: warm wall clock with --passes N, else the
cold wall clock), then, for every checker named by --baselines
that has no measurement on this host for the corpus yet, `track.py CORPUS --checker NAME`.

Only one measurement runs in the container at a time: the ladder takes the lock
work/perf-history/.ladder.lock around every single track.py call (two measurements in the same eight
cores would spoil each other's CPU time), and releases it between calls so that several queues
interleave (--batch N: around N consecutive calls). Other tools that measure there should do the same (`flock work/perf-history/.ladder.lock CMD`).

Exit status: 0 everything measured and accepted; 1 at least one wall-clock regression; 2 at least one
invalid measurement (wrong verdict, timeout, no counters); 70 nothing to run.

Python stdlib only.
"""

from __future__ import annotations

import argparse
import datetime
import fcntl
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.realpath(__file__))
ROOT = os.path.realpath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, HERE)
import track  # noqa: E402

# (corpus argument of track.py, tier)
LADDER = [
    ("init", "small"), ("std", "small"), ("cedar", "small"), ("reachability", "small"), ("cedar-hot-ax", "small"),
    ("perf/unfold-before-literal-fallback", "perf"), ("perf/magma-list-pair-n21", "perf"),
    ("perf/gf3-matrix-product", "perf"), ("perf/magma-list-deep-n36", "perf"),
    ("perf/proj-applied-struct-arg-bad", "perf"), ("perf/proj-applied-struct-bad", "perf"),
    ("perf/unfold-before-literal-bad", "perf"), ("perf/ite-taken-branch-bad", "perf"),
    ("con-leche", "large"), ("navier-stokes-euler", "large"), ("cslib", "large"), ("mathlib", "large"),
]
TIER_RUNS = {"small": 3, "perf": 3, "large": 1}
TIERS = ("small", "perf", "large")


def config_name(launcher: str, threads: int | None, engine: str | None, extra_cp: str | None, passes: int = 1) -> str:
    """The configuration string track.py gives such a run (track.py: config)."""
    name = f"rlvm/{launcher}/t{threads if threads is not None else 4}"
    settings = ([f"-X engine={engine}"] if engine else []) + ([f"-X passes={passes}"] if passes > 1 else [])
    if settings:
        name += "/" + " ".join(settings)
    if extra_cp:
        name += "/+cp"
    return name


def main() -> int:
    parser = argparse.ArgumentParser(prog="bench/track/ladder.py",
                                     description="bench/track/track.py over all corpora for one engine and one or more launchers.")
    parser.add_argument("--tier", default="all", help="small | perf | large | all, or a comma list (default all)")
    parser.add_argument("--only", metavar="NAMES", help="comma list of corpora instead of tiers (names as for track.py)")
    parser.add_argument("--launcher", default="jvm,native", help="comma list of jvm | native | pgo (default jvm,native)")
    parser.add_argument("--threads", type=int, default=8, help="worker threads of RLVM (default 8, the runner's vCPUs; 0 = the run line of scripts/arena/rlvm.yaml)")
    parser.add_argument("--engine", default="reference", help="engine (default reference); 'default' = no -X engine")
    parser.add_argument("--extra-cp", metavar="PATH", help="RLVM_EXTRA_CP for the JVM launcher (an engine outside the distribution)")
    parser.add_argument("--dist", default="work/dist", help="distribution directory (default work/dist)")
    parser.add_argument("--baselines", default="gruszka,nano-lean,official",
                        help="checkers to measure where this host has no measurement of them for a corpus (comma list; 'none')")
    parser.add_argument("--no-rlvm", action="store_true", help="measure only the missing baselines")
    parser.add_argument("--runs", type=int, help="runs per corpus (default: 3 for the small and perf tiers, 1 for the large one)")
    parser.add_argument("--passes", type=int, default=1, metavar="N", help="RLVM: N passes per process (track.py --passes N): warm entries, judged by the warm wall clock (default 1: cold entries)")
    parser.add_argument("--history", default=track.DEFAULT_HISTORY, help="history directory (default work/perf-history)")
    parser.add_argument("--note", help="free text stored with every entry")
    parser.add_argument("--accept", metavar="REASON", help="passed to track.py: accept regressions as new references")
    parser.add_argument("--batch", type=int, default=1, metavar="N",
                        help="hold the measurement lock for N consecutive measurements (default 1: released after every one; "
                             "a small N saves waiting when several queues with long measurements share the lock)")
    parser.add_argument("--list", action="store_true", help="print the ladder with what is recorded; measure nothing")
    parser.add_argument("--dry-run", action="store_true", help="print the track.py commands; measure nothing")
    args = parser.parse_args()

    if args.only:
        wanted = [x for x in args.only.split(",") if x]
        tier_of = dict(LADDER)
        ladder = [(name, tier_of.get(name, "small")) for name in wanted]
    else:
        tiers = TIERS if args.tier == "all" else tuple(x for x in args.tier.split(",") if x)
        for tier in tiers:
            if tier not in TIERS:
                track.die(f"unknown tier '{tier}' (small, perf, large, all)")
        ladder = [(name, tier) for tier in tiers for name, t in LADDER if t == tier]
    launchers = [x for x in args.launcher.split(",") if x]
    for launcher in launchers:
        if launcher not in track.BINARIES:
            track.die(f"unknown launcher '{launcher}'")
    # A versioned distribution (work/dist -> work/dist.d/<version>, scripts/build.sh): the whole ladder
    # measures the version the link names NOW, whatever is built while it runs.
    dist_path = args.dist if os.path.isabs(args.dist) else os.path.join(ROOT, args.dist)
    if os.path.islink(dist_path):
        dist_path = os.path.realpath(dist_path)
        args.dist = os.path.relpath(dist_path, ROOT) if dist_path.startswith(ROOT + os.sep) else dist_path
    engine = None if args.engine == "default" else args.engine
    threads = args.threads or None
    baselines = [] if args.baselines in ("", "none") else [x for x in args.baselines.split(",") if x]
    history_dir = os.path.abspath(args.history)

    if args.list:
        print(f"{'corpus':<38} {'tier':<6} {'runs':>4}  recorded (latest valid entry per configuration; baselines known on this host)")
        for name, tier in ladder:
            corpus, _export, arena_name, expected = track.resolve(name)
            entries = track.read_history(history_dir, corpus)
            have = []
            for launcher in launchers:
                config = config_name(launcher, threads, engine, args.extra_cp if launcher == "jvm" else None, args.passes)
                metric = "warm_wall_s" if args.passes > 1 else "wall_time_s"
                ref = track.last_reference(entries, config, metric)
                what = "s warm wall" if args.passes > 1 else "s cold wall"
                have.append(f"{config}: {track.fmt_s(ref[metric]) + ' ' + what if ref else 'not measured'}")
            known = track.load_baselines(corpus, history_dir, arena_name, expected)
            have.append("baselines: " + (", ".join(c for c in baselines if c in known) or "none")
                        + ("; missing: " + ", ".join(c for c in baselines if c not in known) if any(c not in known for c in baselines) else ""))
            print(f"{name:<38} {tier:<6} {args.runs or TIER_RUNS[tier]:>4}  " + "; ".join(have))
        return 0

    jobs: list[tuple[str, list[str]]] = []  # (label, track.py arguments)
    for name, tier in ladder:
        runs = str(args.runs or TIER_RUNS[tier])
        common = ["--runs", runs, "--history", args.history]
        if args.note:
            common += ["--note", args.note]
        if args.accept:
            common += ["--accept", args.accept]
        if not args.no_rlvm:
            for launcher in launchers:
                argv = [name, "--launcher", launcher, "--dist", args.dist] + common
                if threads:
                    argv += ["--threads", str(threads)]
                if engine:
                    argv += ["--engine", engine]
                if args.passes > 1:
                    argv += ["--passes", str(args.passes)]
                if args.extra_cp and launcher == "jvm":
                    argv += ["--extra-cp", args.extra_cp]
                jobs.append((f"{name} {config_name(launcher, threads, engine, args.extra_cp if launcher == 'jvm' else None, args.passes)}", argv))
        corpus, _export, arena_name, expected = track.resolve(name)
        known = track.load_baselines(corpus, history_dir, arena_name, expected)
        for checker in baselines:
            if checker in known:
                continue
            if not os.access(os.path.join(ROOT, "bench", "baselines", checker + ".sh"), os.X_OK):
                print(f"ladder: WARNING: no wrapper bench/baselines/{checker}.sh; baseline '{checker}' skipped", file=sys.stderr)
                continue
            jobs.append((f"{name} {checker}", [name, "--checker", checker] + common))
    if not jobs:
        print("ladder: nothing to measure (every requested baseline is recorded; --no-rlvm given)")
        return 0

    if args.dry_run:
        for label, argv in jobs:
            print("bench/track/track.py " + " ".join(track.shlex.quote(a) for a in argv))
        return 0

    os.makedirs(history_dir, exist_ok=True)
    lock_path = os.path.join(history_dir, ".ladder.lock")
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    results: list[tuple[str, int]] = []
    print(f"ladder {stamp}: {len(jobs)} measurement(s); host load average {track.loadavg()}", flush=True)
    batch = max(1, args.batch)
    for start in range(0, len(jobs), batch):
        # The lock is taken per measurement (or per --batch of them), not per ladder: several queues (this
        # one, the steward's platform matrix, `flock work/perf-history/.ladder.lock CMD`) then interleave.
        with open(lock_path, "w") as lock:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError:
                print(f"\n(waiting for {os.path.relpath(lock_path, ROOT)}: another measurement runs in the container)", flush=True)
                fcntl.flock(lock, fcntl.LOCK_EX)
            for label, argv in jobs[start:start + batch]:
                print(f"\n=== {label}  [{datetime.datetime.now(datetime.timezone.utc).strftime('%H:%M:%SZ')}, "
                      f"load {track.loadavg()[:1]}]", flush=True)
                try:
                    rc = subprocess.run([sys.executable, os.path.join(HERE, "track.py")] + argv, cwd=ROOT).returncode
                except KeyboardInterrupt:
                    print("ladder: interrupted", file=sys.stderr)
                    return 130
                results.append((label, rc))
    print("\nladder summary:")
    words = {0: "ok", 1: "REGRESSION (CPU time)", 2: "INVALID", 64: "usage error", 70: "not built"}
    for label, rc in results:
        print(f"  {label:<72} {words.get(rc, 'exit ' + str(rc))}")
    codes = {rc for _label, rc in results}
    for worst in (70, 64, 2, 1):
        if worst in codes:
            return worst
    return max(codes) if codes else 0


if __name__ == "__main__":
    sys.exit(main())
