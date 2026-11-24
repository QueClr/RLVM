#!/usr/bin/env python3
"""bench/track/selftest.py - checks of bench/track/track.py, ladder.py and benchmarks.py.

    bench/track/selftest.py             the rule, the summaries, phase differencing, baseline lookup (host, no Docker)
    bench/track/selftest.py --docker    additionally the whole command with a fake checker in the arena-shaped
                                        container (about a minute; history in work/perf-history-selftest/)

Exit status 0 if everything passes.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.realpath(__file__))
ROOT = os.path.realpath(os.path.join(HERE, "..", ".."))
sys.dont_write_bytecode = True
sys.path.insert(0, HERE)
import track_b2 as track  # noqa: E402

failures = 0


def check(what: str, condition: bool, detail: str = "") -> None:
    global failures
    print(("  ok    " if condition else "  FAIL  ") + what + ("" if condition or not detail else "   " + detail))
    if not condition:
        failures += 1


def entry(wall, instr=None, reference=True, config="c", utc="t0", cpu=None):
    """A history entry of a cold run: the rule's figure is its wall clock (OWNER-NOTES 12)."""
    return {"config": config, "wall_time_s": wall, "cpu_time_s": cpu, "instructions": instr, "reference": reference,
            "accepted": True, "utc": utc}


def test_rule() -> None:
    print("regression rule (wall clock)")
    j = track.judge(10.0, True, None, 3.0, None)
    check("first valid entry is accepted and becomes the reference", j["accepted"] and j["reference"] and not j["regression"])
    ref = entry(10.0, 100.0)
    j = track.judge(10.29, True, ref, 3.0, None, 100.0)
    check("+2.9 % is accepted and leaves the reference", j["accepted"] and not j["reference"] and not j["regression"], str(j))
    j = track.judge(10.31, True, ref, 3.0, None, 100.5)
    check("+3.1 % is a regression, not accepted", j["regression"] and not j["accepted"] and not j["reference"], str(j))
    check("deltas are reported", abs(j["delta_pct"] - 3.1) < 0.01 and abs(j["instructions_delta_pct"] - 0.5) < 0.01
          and j["metric"] == "wall_time_s" and j["reference_value"] == 10.0, str(j))
    # CPU time is a diagnostic beside the rule: 40 % more CPU at the same wall clock is no regression ...
    j = track.judge(10.0, True, entry(10.0, cpu=50.0), 3.0, None, None, "wall_time_s", 70.0)
    check("CPU time is reported and is not the rule", j["accepted"] and not j["regression"]
          and abs(j["cpu_delta_pct"] - 40.0) < 0.01, str(j))
    # ... and the same CPU time at 10 % more wall clock is one.
    j = track.judge(11.0, True, entry(10.0, cpu=50.0), 3.0, None, None, "wall_time_s", 50.0)
    check("more wall clock at the same CPU time is a regression", j["regression"] and j["cpu_delta_pct"] == 0.0, str(j))
    j = track.judge(10.31, True, ref, 3.0, "new feature costs it")
    check("a regression accepted on purpose becomes the reference", j["regression"] and j["accepted"] and j["reference"]
          and j["accept_reason"] == "new feature costs it", str(j))
    j = track.judge(9.6, True, ref, 3.0, None)
    check("-4 % is an improvement and becomes the reference", j["improvement"] and j["accepted"] and j["reference"], str(j))
    j = track.judge(9.8, True, ref, 3.0, None)
    check("-2 % is accepted and leaves the reference", j["accepted"] and not j["reference"] and not j["improvement"], str(j))
    j = track.judge(9.8, False, ref, 3.0, "whatever")
    check("an invalid measurement is never accepted", not j["accepted"] and not j["reference"], str(j))
    j = track.judge(10.2, True, ref, 1.0, None)
    check("the threshold is a parameter", j["regression"], str(j))
    # drift: +2 % steps against a reference that stays
    history = [dict(entry(10.0), utc="t0")]
    flagged_at = None
    cpu = 10.0
    for step in range(1, 6):
        cpu *= 1.02
        j = track.judge(cpu, True, track.last_reference(history, "c"), 3.0, None)
        history.append(dict(entry(cpu, reference=j["reference"]), utc=f"t{step}"))
        if j["regression"] and flagged_at is None:
            flagged_at = step
    check("a drift of 2 % per step is flagged at the second step", flagged_at == 2, f"flagged at step {flagged_at}")
    other = [entry(5.0, config="other"), entry(10.0, config="c", utc="mine"), entry(7.0, reference=False, config="c")]
    check("the reference is per configuration and skips non-reference entries",
          track.last_reference(other, "c")["utc"] == "mine")
    check("no reference for an unknown configuration", track.last_reference(other, "x") is None)
    warm = [dict(entry(3.0, config="w"), warm_wall_s=1.0, utc="warm"), dict(entry(2.5, config="w"), utc="cold-only")]
    check("a warm entry is compared with the last reference that has a warm wall clock",
          track.last_reference(warm, "w", "warm_wall_s")["utc"] == "warm" and track.last_reference(warm, "w")["utc"] == "cold-only")
    check("the rule's figure: warm wall clock when the entry has passes, else the cold wall clock",
          track.metric_of({"warm_wall_s": 1.2, "wall_time_s": 9.0}) == ("warm_wall_s", 1.2)
          and track.metric_of({"warm_wall_s": None, "wall_time_s": 9.0}) == ("wall_time_s", 9.0))
    j = track.judge(1.2, True, warm[0], 5.0, None, None, "warm_wall_s")
    check("warm wall +20 % is a regression at the default threshold", j["regression"] and j["metric"] == "warm_wall_s", str(j))


def test_passes() -> None:
    print("pass lines (rlvm -X passes=N)")
    line = ("rlvm: pass {k}/4 mode=reload wall_ms={w} cpu_ms={c} gc_ms=12 gc_count=1 load_ms=300 check_ms=500 units=10 "
            "accepted=10 workers=8 pipelined=1 heap_kb=1 rss_kb=2 peak_rss_kb=3 settle_ms=0 verdict={v}")
    text = "noise\n" + "\n".join(line.format(k=k, w=w, c=c, v="ACCEPT") for k, w, c in
                                  ((1, 4000, 9000), (2, 1300, 7000), (3, 1100, 6500), (4, 1200, 6800))) + "\nrlvm: stats: x\n"
    run = track.read_passes(text)
    check("four passes are read", run.get("n_passes") == 4 and [p["wall_s"] for p in run["passes"]] == [4.0, 1.3, 1.1, 1.2], str(run))
    check("pass 1, the median later pass (a real pass) and the best later pass",
          run["pass1_wall_s"] == 4.0 and run["warm_wall_s"] == 1.2 and run["warm_best_s"] == 1.1 and run["warm_cpu_s"] == 6.8, str(run))
    check("a plain run has no passes", track.read_passes("rlvm: stats: outcome=accept\n") == {})
    one = track.read_passes(line.format(k=1, w=900, c=1000, v="ACCEPT").replace("/4", "/1"))
    check("one pass: no warm figure", one.get("n_passes") == 1 and "warm_wall_s" not in one, str(one))
    ok, why = track.run_valid({"exit_code": 0, "cpu_time_s": 1.0, "instructions": 5, **run}, 0)
    check("a run whose passes all accept is valid", ok, why)
    bad = track.read_passes(text.replace("wall_ms=1100 cpu_ms=6500 gc_ms=12 gc_count=1 load_ms=300 check_ms=500 units=10 "
                                         "accepted=10 workers=8 pipelined=1 heap_kb=1 rss_kb=2 peak_rss_kb=3 settle_ms=0 verdict=ACCEPT",
                                         "wall_ms=1100 cpu_ms=6500 gc_ms=12 verdict=DECLINE"))
    ok, why = track.run_valid({"exit_code": 0, "cpu_time_s": 1.0, "instructions": 5, **bad}, 0)
    check("a later pass with another verdict makes the run invalid", not ok and "pass 3" in why, why)
    runs = [dict(cpu_time_s=30.0, wall_time_s=9.0, max_rss_bytes=1, instructions=1, **run),
            dict(cpu_time_s=29.0, wall_time_s=8.0, max_rss_bytes=1, instructions=1, n_passes=4, pass1_wall_s=3.5,
                 warm_wall_s=1.5, warm_cpu_s=7.5, warm_best_s=1.0)]
    s = track.summarise_runs(runs)
    check("warm wall clock of an entry: the lowest median later pass over its runs, with that pass's CPU time",
          s["warm_wall_s"] == 1.2 and s["warm_cpu_s"] == 6.8 and s["warm_wall_s_best_pass"] == 1.0 and s["pass1_wall_s"] == 3.5
          and s["passes"] == 4, str(s))
    check("an entry without passes has no warm figure", track.summarise_runs([{"cpu_time_s": 1.0, "wall_time_s": 1.0}])["warm_wall_s"] is None)


def test_summary() -> None:
    print("summaries")
    runs = [{"cpu_time_s": 62.4, "wall_time_s": 18.1, "max_rss_bytes": 100, "instructions": 418},
            {"cpu_time_s": 81.9, "wall_time_s": 35.3, "max_rss_bytes": 300, "instructions": 420},
            {"cpu_time_s": 75.9, "wall_time_s": 22.3, "max_rss_bytes": 200, "instructions": 419}]
    s = track.summarise_runs(runs)
    check("CPU time and wall clock: the minimum over the runs", s["cpu_time_s"] == 62.4 and s["wall_time_s"] == 18.1, str(s))
    check("median and maximum are kept", s["cpu_time_s_median"] == 75.9 and s["cpu_time_s_max"] == 81.9)
    check("peak RSS: the maximum; instructions: the median", s["max_rss_bytes"] == 300 and s["instructions"] == 419)
    ok, why = track.run_valid({"exit_code": 0, "cpu_time_s": 1.0, "instructions": 5, "counter_coverage": 1.0}, 0)
    check("a clean run is valid", ok)
    for bad, label in (({"exit_code": 2, "cpu_time_s": 1.0, "instructions": 5}, "a decline"),
                       ({"exit_code": 0, "timed_out": True, "cpu_time_s": 1.0, "instructions": 5}, "a timeout"),
                       ({"exit_code": 0, "cpu_time_s": 1.0, "instructions": None}, "a run without counters"),
                       ({"exit_code": 0, "cpu_time_s": 1.0, "instructions": 5, "counter_coverage": 0.9}, "partial counter coverage"),
                       ({"measure_error": "no file"}, "a failed measurement")):
        ok, why = track.run_valid(bad, 0)
        check(f"{label} is invalid", not ok and bool(why), why)


def test_phases() -> None:
    print("phase differencing")
    total = {"cpu_time_s": 10.0, "wall_time_s": 4.0, "instructions": 100.0}
    stops = {"load": {"cpu_time_s": 2.0, "wall_time_s": 1.0, "instructions": 30.0},
             "env": {"cpu_time_s": 3.5, "wall_time_s": 1.5, "instructions": 45.0}}
    table = track.phase_table(total, stops)
    check("load = the run that stops after loading", table["load"]["cpu_time_s"] == 2.0)
    check("env = stop-after=env minus stop-after=load", table["env"]["cpu_time_s"] == 1.5 and table["env"]["instructions"] == 15.0)
    check("check = complete run minus stop-after=env", table["check"]["cpu_time_s"] == 6.5 and table["check"]["wall_time_s"] == 2.5)
    check("the phases add up to the total", abs(sum(table[p]["cpu_time_s"] for p in ("load", "env", "check")) - 10.0) < 1e-9)
    only_load = track.phase_table(total, {"load": stops["load"]})
    check("without the env run there is no env or check row", set(only_load) == {"load", "total"})


def test_baselines() -> None:
    print("baselines")
    with tempfile.TemporaryDirectory() as tmp:
        for corpus in ("mathlib", "init", "std", "init-prelude"):
            base = track.load_baselines(corpus, tmp)
            have = os.path.exists(os.path.join(ROOT, "bench", "baselines.json")) or \
                os.path.exists(os.path.join(ROOT, "work", "baselines", "big", "runs.jsonl")) or \
                os.path.isdir(os.path.join(ROOT, "work", "baselines", "small"))
            if not have:
                print("  skip  no baseline measurements in this tree")
                return
            check(f"{corpus}: baselines found ({', '.join(sorted(base)) or 'none'})", len(base) > 0)
            for name, row in base.items():
                if row.get("cpu_time_s") is None or row["cpu_time_s"] <= 0 or row["cpu_time_s"] > (row.get("cpu_time_s_median") or 0) + 1e-9:
                    check(f"{corpus}/{name}: sane CPU time", False, str(row))
        # entries made with --checker are baselines too, and the newest valid one wins
        with open(os.path.join(tmp, "x.jsonl"), "w") as handle:
            for cpu, valid in ((5.0, True), (4.0, True), (1.0, False)):
                handle.write(json.dumps({"checker": "gruszka", "valid": valid, "config": "gruszka",
                                         "runs": [{"exit_code": 0, "cpu_time_s": cpu, "wall_time_s": cpu}]}) + "\n")
            handle.write(json.dumps({"checker": "rlvm", "valid": True, "config": "rlvm/native/t4",
                                     "runs": [{"exit_code": 0, "cpu_time_s": 9.0, "wall_time_s": 9.0}]}) + "\n")
        base = track.load_baselines("x", tmp)
        check("history entries of other checkers serve as baselines (newest valid)", base.get("gruszka", {}).get("cpu_time_s") == 4.0, str(base))
        check("RLVM's own entries are not baselines", "rlvm" not in base)


def test_benchmarks() -> None:
    """docs/BENCHMARKS.md from rows of bench/warm.py: the owner's order, the latest quiet row per cell, provenance."""
    print("benchmarks document (OWNER-NOTES 12 order)")
    cols = ["utc", "quiet", "corpus", "what", "launcher", "workers", "load", "mode", "passes", "verdict", "best_later_s",
            "median_later_s", "pass1_s", "cold_wall_s", "cpu_median_s", "gc_median_ms", "peak_rss_mib", "cold_cpu_s",
            "cold_rss_mib", "walls_s", "loadavg", "label", "dist", "build"]

    def row(**kw):
        base = dict(utc="2026-11-24T22:00:00Z", quiet="quiet", corpus="init", what="machine", launcher="jvm", workers="8",
                    load="default", mode="reload", passes="4", verdict="ACCEPT", best_later_s="1.10", median_later_s="1.20",
                    pass1_s="3.00", cold_wall_s="2.90", cpu_median_s="8.00", gc_median_ms="5", peak_rss_mib="900", cold_cpu_s="12.00",
                    cold_rss_mib="700", walls_s="3.00 1.30 1.10 1.20", loadavg="9", label="bench", dist="work/x/dist", build="t/abc")
        base.update(kw)
        return "\t".join(base[c] for c in cols)

    with tempfile.TemporaryDirectory() as tmp:
        tsv = os.path.join(tmp, "warm.tsv")
        with open(tsv, "w") as handle:
            handle.write("\t".join(cols) + "\n")
            handle.write(row() + "\n")
            handle.write(row(utc="2026-11-24T22:10:00Z", median_later_s="1.00", best_later_s="0.90") + "\n")   # later: replaces the cell
            handle.write(row(utc="2026-11-24T22:20:00Z", quiet="loaded", median_later_s="0.10") + "\n")        # not in a window
            handle.write(row(utc="2026-11-24T22:30:00Z", label="experiment", median_later_s="0.20") + "\n")    # another label
            handle.write(row(utc="2026-11-24T22:40:00Z", verdict="DECLINE exit 2", median_later_s="0.30") + "\n")
            handle.write(row(workers="1", median_later_s="6.00", best_later_s="5.90", cold_wall_s="") + "\n")
            handle.write(row(launcher="native", median_later_s="1.50", best_later_s="1.40", cold_wall_s="1.60") + "\n")
            handle.write(row(what="gruszka", launcher="rust", median_later_s="0.50", best_later_s="0.50", cold_wall_s="0.55",
                             dist="", build="") + "\n")
            # a row of the tool before the dist / build columns
            handle.write("\t".join(row(corpus="std", what="reference").split("\t")[:22]) + "\n")
        result = subprocess.run([sys.executable, os.path.join(HERE, "benchmarks_b2.py"), "--tsv", tsv, "--labels", "bench", "--stdout",
                                 "--history", "/nonexistent", "--notes", "/nonexistent"], capture_output=True, text=True)
        text = result.stdout
        order = [text.find(h) for h in ("## 1. Warm wall clock, 8 workers", "## 2. Warm wall clock, 1 worker", "## 3. Cold wall clock",
                                        "## 4. CPU time", "## 5. Peak RSS", "## 6. Instructions", "## Provenance")]
        check("the tables are in the owner's order: warm 8, warm 1, cold, CPU, RSS, instructions",
              result.returncode == 0 and all(i >= 0 for i in order) and order == sorted(order), result.stderr[-300:] + str(order))
        first = text.split("## 2.")[0]
        check("a cell is the LATEST quiet row with an allowed label and an accepting verdict",
              "| **1.00** (0.90) | **1.50** (1.40) | - | - | **0.50** (0.50) | - |" in first, first[-500:])
        check("ratios against gruszka and between the platforms", "| 2.00x | 3.00x | - | 0.67x | - |" in first, first[-300:])
        check("one worker has its own table", "**6.00** (5.90)" in text.split("## 2.")[1].split("## 3.")[0])
        check("a corpus without a row of a checker shows '-', and every measured corpus has a row",
              "| std" in first and "| init" in first)
        prov = text.split("## Provenance")[1]
        check("the provenance names the row, its passes and its build", "2026-11-24T22:10:00Z" in prov
              and "3.00 1.30 1.10 1.20 / 2.90" in prov and "`work/x/dist`" in prov and "sha256 `abc`" in prov, prov[:600])
        check("without history entries the instruction table says so", "Not measured for the current build" in text)


def run_track(args: list[str]) -> tuple[int, str]:
    result = subprocess.run([sys.executable, "-I", os.path.join(HERE, "track_b2.py")] + args, capture_output=True, text=True, cwd=ROOT)
    return result.returncode, result.stdout + result.stderr


def test_command_line() -> None:
    print("command line (no measurement)")
    code, out = run_track(["no-such-corpus"])
    check("unknown corpus: usage error 64", code == 64, out.strip()[-200:])
    code, out = run_track(["init-prelude", "--dry-run", "--runs", "2"])
    check("--dry-run prints the container commands", code == 0 and out.count("arena-docker.sh --arena-limits --no-network") == 2
          and "bench/measure.sh --require-perf" in out, out[-300:])
    # work/dist is a symbolic link into work/dist.d/ once scripts/build.sh has versioned it: track.py
    # then measures (and prints) the version the link names, e.g. work/dist.d/20261124T140000Z-ab12-native.
    import re
    check("the measured command is the arena's run line",
          re.search(r"RLVM_STATUS_BASE=10 work/dist(\.d/[^/ ]+)?/bin/rlvm", out) is not None, out[-300:])
    code, out = run_track(["init-prelude", "--dry-run", "--runs", "1", "--launcher", "jvm", "--threads", "1", "--phases"])
    check("--phases adds the stop-after runs", code == 0 and "stop-after=load" in out and "stop-after=env" in out
          and "rlvm-jvm --threads 1" in out, out[-300:])
    code, out = run_track(["init-prelude", "--dry-run", "--runs", "1", "--launcher", "jvm", "--threads", "8", "--passes", "5"])
    check("--passes N puts -X passes=N on the run line", code == 0 and "-X passes=5" in out and "rlvm-jvm --threads 8" in out
          and "t8/-X passes=5" in out, out[-300:])
    code, out = run_track(["init-prelude", "--checker", "gruszka", "--passes", "3", "--dry-run"])
    check("--passes with a baseline: usage error", code == 64, out.strip()[-200:])
    code, out = run_track(["init-prelude", "--checker", "no-such-baseline"])
    check("unknown baseline: 70", code == 70, out.strip()[-200:])
    # These must not depend on which launchers happen to be in work/dist (a JVM-only build has no bin/rlvm).
    code, out = run_track(["init-prelude", "--dist", "work/no-such-dist", "--runs", "1"])
    check("a launcher that is not built: 70, nothing measured", code == 70 and "not found" in out and "run 1/1" not in out,
          out.strip()[-200:])
    code, out = run_track(["init-prelude", "--dist", "work/no-such-dist", "--runs", "1", "--dry-run"])
    check("--dry-run with a missing launcher still prints the commands, with a warning",
          code == 0 and "WARNING" in out and "arena-docker.sh --arena-limits" in out, out.strip()[-300:])
    code, out = run_track(["init-prelude", "--cmd", "true"])
    check("--cmd without --label: usage error", code == 64, out.strip()[-200:])
    code, out = run_track(["init-prelude", "--history", "/tmp/elsewhere", "--show"])
    check("history outside work/: usage error", code == 64, out.strip()[-200:])
    code, out = run_track(["init-prelude", "--show", "--history", os.path.join(ROOT, "work", "perf-history-selftest-empty")])
    check("--show works without a history", code == 0 and "(no history)" in out, out[-300:])


def test_docker() -> None:
    print("whole command in the container (fake checker)")
    history = os.path.join(ROOT, "work", "perf-history-selftest")
    shutil.rmtree(history, ignore_errors=True)
    burner = "bench/track/fake-burner.sh"
    common = ["init-prelude", "--label", "fake-burner", "--history", history, "--runs", "2"]
    code, out = run_track(common + ["--cmd", f"{burner} 20000000"])
    check("first entry: accepted, exit 0", code == 0 and "first reference" in out, out[-400:])
    code, out = run_track(common + ["--cmd", f"{burner} 26000000"])
    check("+30 % work: REGRESSION, exit 1", code == 1 and "REGRESSION" in out, out[-400:])
    code, out = run_track(common + ["--cmd", f"{burner} 26000000", "--accept", "self-test"])
    check("the same with --accept: exit 0, new reference", code == 0 and "accepted on purpose" in out, out[-400:])
    code, out = run_track(common + ["--cmd", f"{burner} 20000000"])
    check("back to the old cost: improvement", code == 0 and "improvement" in out, out[-400:])
    code, out = run_track(common + ["--cmd", f"{burner} 1000000 2"])
    check("a declining checker: invalid, exit 2", code == 2 and "INVALID" in out, out[-400:])
    code, out = run_track(common + ["--cmd", f"{burner} 20000000", "--phase-cmd", f"load={burner} 4000000",
                                    "--phase-cmd", f"env={burner} 10000000", "--no-record"])
    check("phases are measured and differenced", code in (0, 1) and "check" in out and "stop-after=env minus" in out, out[-600:])
    with open(os.path.join(history, "init-prelude.jsonl")) as handle:
        entries = [json.loads(line) for line in handle]
    check("five entries in the history (the --no-record run is not there)", len(entries) == 5, str(len(entries)))
    keys = ("cpu_time_s", "wall_time_s", "max_rss_bytes", "instructions")
    check("CPU time, wall clock, peak RSS and instructions are recorded",
          all(entries[0].get(k) for k in keys) and all(entries[0]["runs"][0].get(k.replace("max_rss_bytes", "max_rss_bytes")) for k in keys))
    check("flags in the history", [e["accepted"] for e in entries] == [True, False, True, True, False]
          and [e["reference"] for e in entries] == [True, False, True, True, False], str([(e["accepted"], e["reference"]) for e in entries]))
    left = subprocess.run(["docker", "ps", "--format", "{{.Names}}"], capture_output=True, text=True).stdout
    check("no container left behind", "rlvm-track-" not in left, left)


def test_ladder() -> None:
    """Corpus resolution of the ladder, the ladder's dry run and the generated table (nothing is measured)."""
    print("ladder and generated table")
    import ladder
    tests = os.path.join(ROOT, "work", "arena-site", "tests")
    if os.path.isdir(tests):
        name, path, arena, expected = track.resolve("perf/unfold-before-literal-bad")
        check("a published bad test must be rejected (expected exit 1)",
              expected == 1 and arena == "perf/unfold-before-literal-bad" and name == "perf_unfold-before-literal-bad"
              and path.endswith("tests/bad/perf/unfold-before-literal-bad.ndjson"), str((name, path, arena, expected)))
        name, path, arena, expected = track.resolve("perf/magma-list-pair-n21")
        check("a published good test must be accepted", expected == 0 and "/good/" in path, str((path, expected)))
        base = track.load_baselines("perf_unfold-before-literal-bad", "/nonexistent", "perf/unfold-before-literal-bad", 1)
        check("baselines of a bad test are the runs that rejected it", all(b["n_runs"] >= 1 for b in base.values()), str(base)[:200])
    if os.path.isfile(os.path.join(ROOT, "work", "workloads", "slices", "cedar-hot-ax.ndjson")):
        name, path, arena, expected = track.resolve("cedar-hot-ax")
        check("the Cedar slice of docs/research/13 is a corpus", name == "cedar-hot-ax" and expected == 0, path)
    tiers = [t for _n, t in ladder.LADDER]
    check("the ladder has the three tiers, 17 corpora", sorted(set(tiers)) == ["large", "perf", "small"] and len(tiers) == 17,
          str(len(tiers)))
    check("configuration names agree with track.py",
          ladder.config_name("jvm", 8, "reference", None) == "rlvm/jvm/t8/-X engine=reference"
          and ladder.config_name("jvm", 4, "machine", "x") == "rlvm/jvm/t4/-X engine=machine/+cp")
    have_all = all(any(os.path.isfile(c) for c in (
        os.path.join(ROOT, "work", "exports", n + ".ndjson"), os.path.join(ROOT, "work", "workloads", "slices", n + ".ndjson"),
        os.path.join(tests, "good", n + ".ndjson"), os.path.join(tests, "bad", n + ".ndjson"))) for n, _t in ladder.LADDER)
    if not have_all:
        print("  (skipped: not every corpus of the ladder is present)")
        return
    result = subprocess.run([sys.executable, os.path.join(HERE, "ladder.py"), "--tier", "small", "--dry-run", "--launcher", "jvm",
                             "--engine", "machine", "--extra-cp", "x/classes", "--baselines", "none"], capture_output=True, text=True)
    lines = [line for line in result.stdout.splitlines() if line.startswith("bench/track/track.py ")]
    check("ladder --dry-run prints one track.py call per corpus and launcher", result.returncode == 0 and len(lines) == 5
          and all("--engine machine" in line and "--extra-cp x/classes" in line and "--runs 3" in line for line in lines),
          result.stdout[-400:] + result.stderr[-400:])
    result = subprocess.run([sys.executable, os.path.join(HERE, "ladder.py"), "--tier", "large", "--dry-run", "--no-rlvm",
                             "--baselines", "no-such-checker"], capture_output=True, text=True)
    check("a baseline without a wrapper is skipped with a warning", "no wrapper" in result.stderr and "track.py" not in result.stdout,
          result.stdout[-300:] + result.stderr[-300:])
    # The cold tables from the tracker's history (the generator of docs/BENCHMARKS.md until 2026-11-24T23Z).
    result = subprocess.run([sys.executable, os.path.join(HERE, "benchmarks_cold.py"), "--stdout", "--history", "/nonexistent"],
                            capture_output=True, text=True)
    text = result.stdout
    # OWNER-NOTES item 12: wall clock before CPU time (the warm columns do not exist yet).
    order = [text.find("## Wall clock"), text.find("## CPU time"), text.find("## Peak RSS"), text.find("## Instructions")]
    check("the generated document lists wall clock, CPU time, RSS, instructions in that order (owner note 12)",
          result.returncode == 0 and all(i >= 0 for i in order) and order == sorted(order), result.stderr[-300:])
    # (the tables generated from the history: the hand-maintained warm fragment at the end has rows of its own)
    generated = text.split("## Warm wall clock")[0]
    check("one row per corpus in every table", generated.count("| mathlib (") == 4 and generated.count("| cedar-hot-ax (") == 4,
          str((text.count("| mathlib ("), text.count("| cedar-hot-ax ("))))
    check("a corpus without a measurement shows '-', never a made-up number", "not measured" in text)
    check("one RLVM column per engine, the machine first, beside the baselines",
          "| Corpus | RLVM machine | RLVM reference | gruszka | nano-lean | official |" in text, text[:200])
    result = subprocess.run([sys.executable, os.path.join(HERE, "benchmarks_cold.py"), "--stdout", "--history", "/nonexistent",
                             "--engine", "reference", "--launcher", "jvm"], capture_output=True, text=True)
    check("--engine NAME gives the one-engine table", result.returncode == 0
          and "| Corpus | RLVM reference | gruszka | nano-lean | official |" in result.stdout, result.stderr[-300:])


def main() -> int:
    test_rule()
    test_summary()
    test_passes()
    test_phases()
    test_baselines()
    test_command_line()
    test_ladder()
    test_benchmarks()
    if "--docker" in sys.argv[1:]:
        test_docker()
    print("selftest: %s" % ("all passed" if failures == 0 else f"{failures} FAILED"))
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
