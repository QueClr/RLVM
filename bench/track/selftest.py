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
import track  # noqa: E402

failures = 0


def check(what: str, condition: bool, detail: str = "") -> None:
    global failures
    print(("  ok    " if condition else "  FAIL  ") + what + ("" if condition or not detail else "   " + detail))
    if not condition:
        failures += 1


def entry(cpu, instr=None, reference=True, config="c", utc="t0"):
    return {"config": config, "cpu_time_s": cpu, "instructions": instr, "reference": reference, "accepted": True, "utc": utc}


def test_rule() -> None:
    print("regression rule")
    j = track.judge(10.0, True, None, 3.0, None)
    check("first valid entry is accepted and becomes the reference", j["accepted"] and j["reference"] and not j["regression"])
    ref = entry(10.0, 100.0)
    j = track.judge(10.29, True, ref, 3.0, None, 100.0)
    check("+2.9 % is accepted and leaves the reference", j["accepted"] and not j["reference"] and not j["regression"], str(j))
    j = track.judge(10.31, True, ref, 3.0, None, 100.5)
    check("+3.1 % is a regression, not accepted", j["regression"] and not j["accepted"] and not j["reference"], str(j))
    check("deltas are reported", abs(j["cpu_delta_pct"] - 3.1) < 0.01 and abs(j["instructions_delta_pct"] - 0.5) < 0.01, str(j))
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


def run_track(args: list[str]) -> tuple[int, str]:
    result = subprocess.run([sys.executable, "-I", os.path.join(HERE, "track.py")] + args, capture_output=True, text=True, cwd=ROOT)
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
    result = subprocess.run([sys.executable, os.path.join(HERE, "benchmarks.py"), "--stdout", "--history", "/nonexistent"],
                            capture_output=True, text=True)
    text = result.stdout
    order = [text.find("## CPU time"), text.find("## Wall clock"), text.find("## Peak RSS"), text.find("## Instructions")]
    check("the generated document lists CPU time, wall clock, RSS, instructions in that order",
          result.returncode == 0 and all(i >= 0 for i in order) and order == sorted(order), result.stderr[-300:])
    check("one row per corpus in every table", text.count("| mathlib (") == 4 and text.count("| cedar-hot-ax (") == 4,
          str((text.count("| mathlib ("), text.count("| cedar-hot-ax ("))))
    check("a corpus without a measurement shows '-', never a made-up number", "not measured" in text)
    check("one RLVM column per engine, the machine first, beside the baselines",
          "| Corpus | RLVM machine | RLVM reference | gruszka | nano-lean | official |" in text, text[:200])
    result = subprocess.run([sys.executable, os.path.join(HERE, "benchmarks.py"), "--stdout", "--history", "/nonexistent",
                             "--engine", "reference", "--launcher", "jvm"], capture_output=True, text=True)
    check("--engine NAME gives the one-engine table", result.returncode == 0
          and "| Corpus | RLVM reference | gruszka | nano-lean | official |" in result.stdout, result.stderr[-300:])


def main() -> int:
    test_rule()
    test_summary()
    test_phases()
    test_baselines()
    test_command_line()
    test_ladder()
    if "--docker" in sys.argv[1:]:
        test_docker()
    print("selftest: %s" % ("all passed" if failures == 0 else f"{failures} FAILED"))
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
