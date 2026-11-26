#!/usr/bin/env python3
"""bench/track/track.py - one command per corpus: measure RLVM in the arena-shaped
container, keep a history, compare with the baselines measured on this host, and
flag WALL-CLOCK regressions (OWNER-NOTES 12: warm wall clock is the target).

    bench/track/track.py CORPUS [options]                 (run on the host; starts containers)

    bench/track/track.py init                             # work/dist/bin/rlvm, the arena's run line, 3 runs (cold)
    bench/track/track.py init --passes 5 --threads 8 --launcher jvm   # WARM: 5 passes per process, the target metric
    bench/track/track.py init --launcher jvm --threads 1
    bench/track/track.py std --rlvm-arg -X --rlvm-arg engine=reference
    bench/track/track.py init --phases                    # + per-phase table (load / env / check)
    bench/track/track.py mathlib --runs 1
    bench/track/track.py init --show                      # history and comparison, no measurement
    bench/track/track.py init-prelude --checker gruszka   # a baseline through the same machinery

CORPUS is the name of an export: work/exports/NAME.ndjson (init-prelude, init,
std, cedar, con-leche, cslib, reachability, navier-stokes-euler, mathlib), a
slice work/exports/slices/good/NAME.ndjson, or a path to an .ndjson file below
the project root.

What is measured. Every run is one fresh container sized like the arena runner
(8 performance cores, 16 GB, no network) with nothing else in it:

    bench/arena-docker.sh --arena-limits --no-network bench/measure.sh --require-perf \\
        --timeout T --cwd ROOT --in EXPORT -o RAW.json -c 'RUN LINE'

RUN LINE is the arena's own run line of RLVM (scripts/arena/rlvm.yaml, through
bench/rlvm/run.sh --print), so the numbers are what the arena would see; this
is also exactly how bench/baselines/big.sh measured the baselines. Recorded
per run, in the order of OWNER-NOTES 12: warm wall clock (with --passes), wall
clock of the fresh process (cold), CPU time (perf task-clock over all threads
and children), peak RSS, user-space instructions.

Warm passes. With --passes N (N >= 2) the run line gets `-X passes=N`: the same
process checks the export N times (rlvm-main, Passes) and prints one line per
pass, `rlvm: pass K/N mode=.. wall_ms=.. cpu_ms=.. gc_ms=.. .. verdict=..`.
The tracker reads these lines from the run's log (it also reads them when the
run line has -X passes=N through --rlvm-arg): per run the wall clock of every
pass, pass 1, the best later pass and the MEDIAN LATER PASS = the run's warm
wall clock, with the CPU time of that pass. A run whose passes do not all end
with the expected verdict is invalid. Take such an entry inside a
bench/quiet.py window (bench/quiet.py -- bench/track/track.py ...).

History. One JSON line per invocation is appended to
work/perf-history/CORPUS.jsonl: the runs, their medians, the configuration
(checker / launcher / threads / extra arguments), the build (BUILD-INFO and
the binary's hash), the comparison and the verdict of the regression rule.

The figures of an entry are MINIMA over its runs (default 3): other containers
share the eight performance cores and can only add time, never remove any
(measured here: gruszka on mathlib 62.4 / 75.9 / 81.9 s CPU in three runs
whose instruction counts are 0.3 % apart). That holds for the warm wall clock
(the minimum over the runs of each run's median later pass), the cold wall
clock and CPU time; peak RSS is the maximum, instructions the median.
Baselines get the same treatment.

Regression rule. It is on WALL CLOCK: the warm wall clock when the entry has
passes, else the wall clock of the fresh process (which OWNER-NOTES 12 reports
"for information": a cold entry is judged by it because it has nothing else).
Until 2026-11-24 the rule was on cold CPU time, which note 12 no longer makes
the target; CPU time and instructions are printed beside the verdict as
diagnostics. The entry's figure is compared with the reference of its
configuration (the pass count is part of the configuration), which is its
last accepted entry that was made the reference:

    wall > reference * (1 + threshold)   ->  REGRESSION: recorded with "accepted": false, exit status 1
    wall < reference * (1 - threshold)   ->  improvement: accepted, becomes the new reference
    otherwise                            ->  accepted; the reference stays where it is

with threshold = 5 % (--threshold; two identical 8-worker rows of one quiet
window differ by up to 5 %, *agentic-note*: rep-J1, section 1). The first valid entry of a
configuration is its first reference. A regression can be accepted on purpose
with --accept "REASON" (the reason is stored; the entry becomes the reference).
Because entries inside the band do not move the reference, a slow drift
cannot creep in three percent at a time.
An entry is only eligible when every run gave the expected verdict (exit
status 0: all corpora are accept tests), did not time out and had full counter
coverage; otherwise it is recorded as invalid and the exit status is 2.

Baselines. bench/baselines.json (else work/baselines/big/runs.jsonl), for the
small tests work/baselines/small/NAME.json, and entries of this history made
with --checker NAME; per checker the runs that accepted, summarised as above.

Exit status: 0 measured and accepted (or --show); 1 wall-clock regression;
2 invalid measurement (wrong verdict, timeout, no counters); 64 usage;
70 nothing to run (distribution or wrapper missing).

Python stdlib only.
"""

from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import os
import re
import shlex
import signal
import statistics
import subprocess
import sys

HERE = os.path.dirname(os.path.realpath(__file__))
ROOT = os.path.realpath(os.path.join(HERE, "..", ".."))
DOCKER = os.path.join(ROOT, "bench", "arena-docker.sh")
RUN_SH = os.path.join(ROOT, "bench", "rlvm", "run.sh")
DEFAULT_HISTORY = os.path.join(ROOT, "work", "perf-history")
THRESHOLD_PCT = 5.0
PASS_LINE = re.compile(r"^rlvm: pass (\d+)/(\d+) (.*)$")
PHASES = (("load", ["-X", "stop-after=load"]), ("env", ["-X", "stop-after=env"]))
# "pgo" = the executable of the OPTIONAL Oracle GraalVM distribution (scripts/build.sh --oracle-pgo): the
# same single executable bin/rlvm, in work/dist-oracle-pgo unless --dist names another directory. No
# build makes a second binary bin/rlvm-pgo any more.
BINARIES = {"native": "rlvm", "jvm": "rlvm-jvm", "pgo": "rlvm"}
ORACLE_DIST = "work/dist-oracle-pgo"


def die(message: str, code: int = 64) -> "NoReturn":  # noqa: F821
    print(f"track: {message}", file=sys.stderr)
    sys.exit(code)


# ------------------------------------------------------------ corpus --------
def resolve_corpus(arg: str) -> tuple[str, str]:
    """(name, absolute path of the export). See resolve() for the arena name and the expected verdict."""
    name, path, _arena, _exit = resolve(arg)
    return name, path


def resolve(arg: str) -> tuple[str, str, str, int]:
    """(history name, absolute path of the export, the arena's test name, expected exit status).

    NAME is looked up as work/exports/NAME.ndjson (the big corpora), a slice
    (work/exports/slices/good/NAME.ndjson, work/workloads/slices/NAME.ndjson), a published good test
    (work/arena-site/tests/good/NAME.ndjson) or a published bad test (tests/bad/NAME.ndjson: the valid
    verdict is then REJECT, exit status 1). The history name is NAME with '/' replaced by '_'.
    """
    candidates = [
        (os.path.join(ROOT, "work", "exports", arg + ".ndjson"), 0),
        (os.path.join(ROOT, "work", "exports", "slices", "good", arg + ".ndjson"), 0),
        (os.path.join(ROOT, "work", "workloads", "slices", arg + ".ndjson"), 0),
        (os.path.join(ROOT, "work", "arena-site", "tests", "good", arg + ".ndjson"), 0),
        (os.path.join(ROOT, "work", "arena-site", "tests", "bad", arg + ".ndjson"), 1),
    ]
    for path, expected in candidates:
        if os.path.isfile(path):
            return arg.replace("/", "_"), path, arg, expected
    if os.path.isfile(arg):
        path = os.path.abspath(arg)
        real_root = ROOT + os.sep
        if not (path.startswith(real_root) or os.path.realpath(path).startswith(real_root)):
            die(f"{path} is outside the project root; the container cannot see it")
        name = os.path.basename(path)
        name = name[:-len(".ndjson")] if name.endswith(".ndjson") else name
        tests = os.path.join(ROOT, "work", "arena-site", "tests") + os.sep
        expected, arena = 0, name
        if path.startswith(tests):
            bucket, _, rest = path[len(tests):].partition(os.sep)
            arena = rest[:-len(".ndjson")] if rest.endswith(".ndjson") else rest
            name = arena.replace("/", "_")
            expected = 1 if bucket == "bad" else 0
        return name, path, arena, expected
    known = sorted(f[:-len(".ndjson")] for f in os.listdir(os.path.join(ROOT, "work", "exports"))
                   if f.endswith(".ndjson")) if os.path.isdir(os.path.join(ROOT, "work", "exports")) else []
    die(f"no export for corpus '{arg}' (known: {' '.join(known) or 'none'}; also slices, and published tests "
        f"such as perf/magma-list-pair-n21)")


def arena_timeout(corpus: str) -> float | None:
    """The arena's timeout of a corpus in seconds, if it has one."""
    try:
        with open(os.path.join(ROOT, "work", "exports", "all", "manifest.json")) as handle:
            for test in json.load(handle).get("tests", []):
                if test.get("name") == corpus and test.get("timeout"):
                    return float(test["timeout"])
    except (OSError, ValueError):
        pass
    return None


# ------------------------------------------------------------ baselines -----
def median_of(runs: list[dict], key: str):
    values = [r[key] for r in runs if r.get(key) is not None]
    return statistics.median(values) if values else None


def summarise_runs(runs: list[dict]) -> dict:
    """The figures of one configuration from its runs.

    CPU time and wall clock: the MINIMUM over the runs (median and maximum are kept next to it).
    Other containers share the eight performance cores, and whatever they do can only add CPU time
    (frequency, caches, memory bandwidth), never remove any: on this host gruszka on mathlib took
    62.4, 75.9 and 81.9 s of CPU in three runs with instruction counts 0.3 % apart. The minimum is
    therefore the estimate of the undisturbed cost, for RLVM and for the baselines alike.
    Peak RSS: the maximum. Instructions: the median (they do not depend on the load).
    """
    rss = [r["max_rss_bytes"] for r in runs if r.get("max_rss_bytes")]
    cpu = [r["cpu_time_s"] for r in runs if r.get("cpu_time_s") is not None]
    wall = [r["wall_time_s"] for r in runs if r.get("wall_time_s") is not None]
    # Warm passes (rlvm -X passes=N): the run with the lowest median later pass speaks for the entry.
    warm = sorted((r for r in runs if r.get("warm_wall_s") is not None), key=lambda r: r["warm_wall_s"])
    best = warm[0] if warm else {}
    return {
        "warm_wall_s": best.get("warm_wall_s"),
        "warm_wall_s_best_pass": min((r["warm_best_s"] for r in warm), default=None),
        "warm_cpu_s": best.get("warm_cpu_s"),
        "pass1_wall_s": min((r["pass1_wall_s"] for r in warm), default=None),
        "passes": best.get("n_passes"),
        "n_runs": len(runs),
        "cpu_time_s": min(cpu) if cpu else None,
        "cpu_time_s_median": statistics.median(cpu) if cpu else None,
        "cpu_time_s_max": max(cpu) if cpu else None,
        "wall_time_s": min(wall) if wall else None,
        "wall_time_s_median": statistics.median(wall) if wall else None,
        "max_rss_bytes": max(rss) if rss else None,
        "instructions": median_of(runs, "instructions"),
        "instructions_kernel": median_of(runs, "instructions_kernel"),
    }


def good_run(run: dict, expected_exit: int = 0) -> bool:
    return run.get("exit_code") == expected_exit and not run.get("timed_out") and run.get("cpu_time_s") is not None


def load_baselines(corpus: str, history_dir: str, arena_name: str | None = None, expected_exit: int = 0) -> dict[str, dict]:
    """{checker: {cpu_time_s, wall_time_s, max_rss_bytes, instructions, n_runs, source}} for one corpus.

    corpus is the history name; arena_name the arena's test name where it differs (perf/NAME); only runs
    that ended with the expected verdict count.
    """
    out: dict[str, dict] = {}
    arena_name = arena_name or corpus

    def add(checker: str, runs: list[dict], source: str) -> None:
        runs = [r for r in runs if good_run(r, expected_exit)]
        if runs and checker not in out:
            out[checker] = dict(summarise_runs(runs), source=source)

    # 1. bench/baselines.json
    try:
        with open(os.path.join(ROOT, "bench", "baselines.json")) as handle:
            doc = json.load(handle)
        for checker, entry in (doc.get("results", {}).get(arena_name) or doc.get("results", {}).get(corpus) or {}).items():
            add(checker, entry.get("runs") or [], "bench/baselines.json")
    except (OSError, ValueError):
        pass
    # 2. work/baselines/big/runs.jsonl (arena configuration only)
    by_checker: dict[str, list[dict]] = {}
    try:
        with open(os.path.join(ROOT, "work", "baselines", "big", "runs.jsonl")) as handle:
            for line in handle:
                try:
                    run = json.loads(line)
                except ValueError:
                    continue
                if run.get("corpus") in (corpus, arena_name) and run.get("variant", "arena") == "arena":
                    by_checker.setdefault(run.get("checker", "?"), []).append(run)
    except OSError:
        pass
    for checker, runs in by_checker.items():
        add(checker, runs, "work/baselines/big/runs.jsonl")
    # 3. the baselines' run-suite reports over the published tests (one run each)
    small_dir = os.path.join(ROOT, "work", "baselines", "small")
    if os.path.isdir(small_dir):
        for name in sorted(os.listdir(small_dir)):
            if not name.endswith(".json"):
                continue
            try:
                with open(os.path.join(small_dir, name)) as handle:
                    report = json.load(handle)
            except (OSError, ValueError):
                continue
            for result in report.get("results", []):
                if result.get("test") not in (corpus, arena_name):
                    continue
                try:
                    run = {"exit_code": int(result.get("exit_code")), "timed_out": result.get("status") == "timeout",
                           "cpu_time_s": float(result["cpu_time"]), "wall_time_s": float(result["wall_time"]),
                           "max_rss_bytes": int(float(result["max_rss"])),
                           "instructions": int(float(result["instructions"])) if result.get("instructions") else None}
                except (KeyError, TypeError, ValueError):
                    continue
                add(name[:-len(".json")], [run], f"work/baselines/small/{name}")
    # 4. this history: entries of other checkers made with --checker
    for entry in reversed(read_history(history_dir, corpus)):  # newest first: the latest valid entry per checker
        if entry.get("checker") != "rlvm" and entry.get("valid"):
            add(entry["checker"], entry.get("runs") or [], "work/perf-history (track --checker)")
    return out


# ------------------------------------------------------------ history -------
def history_file(history_dir: str, corpus: str) -> str:
    return os.path.join(history_dir, corpus + ".jsonl")


def read_history(history_dir: str, corpus: str) -> list[dict]:
    entries = []
    try:
        with open(history_file(history_dir, corpus)) as handle:
            for line in handle:
                line = line.strip()
                if line:
                    try:
                        entries.append(json.loads(line))
                    except ValueError:
                        print(f"track: WARNING: unreadable line in {history_file(history_dir, corpus)}", file=sys.stderr)
    except OSError:
        pass
    return entries


def read_passes(text: str) -> dict:
    """The per-pass lines of `rlvm -X passes=N` in a run's output, as fields of the run.

    {} when there is none (a plain run). Else: passes = [{pass, wall_s, cpu_s, gc_ms, load_s, check_s,
    verdict, pipelined}], n_passes, pass1_wall_s, and for N >= 2 warm_wall_s (the lower median of the
    later passes: a real pass, so that its CPU time can be quoted as warm_cpu_s) and warm_best_s.
    """
    passes = []
    for line in text.splitlines():
        match = PASS_LINE.match(line.strip())
        if not match:
            continue
        fields = dict(item.split("=", 1) for item in match.group(3).split() if "=" in item)

        def number(key: str, scale: float = 1.0):
            try:
                value = float(fields[key])
            except (KeyError, ValueError):
                return None
            return None if value < 0 else value / scale

        passes.append({"pass": int(match.group(1)), "of": int(match.group(2)), "wall_s": number("wall_ms", 1000.0),
                       "cpu_s": number("cpu_ms", 1000.0), "gc_ms": number("gc_ms"), "load_s": number("load_ms", 1000.0),
                       "check_s": number("check_ms", 1000.0), "verdict": fields.get("verdict"),
                       "pipelined": fields.get("pipelined")})
    passes = [p for p in passes if p["wall_s"] is not None]
    if not passes:
        return {}
    out = {"passes": passes, "n_passes": len(passes), "pass1_wall_s": passes[0]["wall_s"]}
    later = sorted(passes[1:], key=lambda p: p["wall_s"])
    if later:
        median = later[(len(later) - 1) // 2]
        out.update(warm_wall_s=median["wall_s"], warm_cpu_s=median["cpu_s"], warm_best_s=later[0]["wall_s"])
    return out


def passes_valid(run: dict, expected_exit: int) -> tuple[bool, str]:
    """Every pass of a warm run must end with the verdict the run is expected to end with."""
    want = {0: "ACCEPT", 1: "REJECT", 2: "DECLINE"}.get(expected_exit)
    for p in run.get("passes") or []:
        if want and p.get("verdict") != want:
            return False, f"pass {p['pass']} ended with {p.get('verdict')}, expected {want}"
    return True, ""


def metric_of(entry: dict) -> tuple[str, float | None]:
    """What the regression rule compares: the warm wall clock of an entry with passes, else the wall
    clock of the fresh process (OWNER-NOTES 12; until 2026-11-24 the rule was on cold CPU time)."""
    if entry.get("warm_wall_s") is not None:
        return "warm_wall_s", entry["warm_wall_s"]
    return "wall_time_s", entry.get("wall_time_s")


METRIC_NAMES = {"warm_wall_s": "warm wall clock (median later pass)", "wall_time_s": "cold wall clock (fresh process)"}


def last_reference(entries: list[dict], config: str, metric: str = "wall_time_s") -> dict | None:
    """The reference of a configuration: its last accepted entry that was made the reference (and has the metric)."""
    for entry in reversed(entries):
        if entry.get("config") == config and entry.get("reference") and entry.get(metric) is not None:
            return entry
    return None


def judge(value: float | None, valid: bool, reference: dict | None, threshold_pct: float, accept_reason: str | None,
          instructions: float | None = None, metric: str = "wall_time_s", cpu: float | None = None) -> dict:
    """The regression rule on `metric` (a wall clock, see metric_of). Returns the fields added to the entry.

    cpu / instructions: the entry's CPU time and instruction count, compared with the reference's for
    information (cpu_delta_pct, instructions_delta_pct); they are not part of the rule.

    accepted:  the entry passes (no regression beyond the threshold, or one accepted on purpose)
    reference: later entries are compared with this one. An entry becomes the reference when it is
               the first valid one, when it improves on the reference by more than the threshold,
               or when it was accepted on purpose (--accept). Entries inside the band leave the
               reference where it is, so small regressions cannot add up unnoticed.
    """
    out = {"metric": metric, "threshold_pct": threshold_pct, "reference_utc": None, "reference_value": None,
           "delta_pct": None, "reference_cpu_time_s": None, "cpu_delta_pct": None,
           "instructions_delta_pct": None, "regression": False, "improvement": False, "accepted": False,
           "reference": False, "accept_reason": None}
    if not valid or value is None:
        return out
    if reference is None:
        out.update(accepted=True, reference=True, accept_reason=accept_reason or "first valid entry of this configuration")
        return out
    ref_value = reference[metric]
    delta = (value / ref_value - 1.0) * 100.0 if ref_value > 0 else 0.0
    out.update(reference_utc=reference.get("utc"), reference_value=ref_value, delta_pct=round(delta, 3))
    if cpu and reference.get("cpu_time_s"):
        # not part of the rule since OWNER-NOTES 12: how much parallelism and work is behind the wall clock
        out.update(reference_cpu_time_s=reference["cpu_time_s"],
                   cpu_delta_pct=round((cpu / reference["cpu_time_s"] - 1.0) * 100.0, 3))
    if instructions and reference.get("instructions"):
        # not part of the rule; printed next to it, because instruction counts do not depend on the host's load
        out["instructions_delta_pct"] = round((instructions / reference["instructions"] - 1.0) * 100.0, 3)
    if delta > threshold_pct:
        out["regression"] = True
        if accept_reason:
            out.update(accepted=True, reference=True, accept_reason=accept_reason)
    else:
        out["accepted"] = True
        out["improvement"] = delta < -threshold_pct
        if out["improvement"] or accept_reason:
            out.update(reference=True, accept_reason=accept_reason)
    return out


# ------------------------------------------------------------ measuring -----
def loadavg() -> list[float]:
    try:
        with open("/proc/loadavg") as handle:
            return [float(x) for x in handle.read().split()[:3]]
    except (OSError, ValueError):
        return []


def other_containers() -> int | None:
    try:
        result = subprocess.run(["docker", "ps", "-q"], capture_output=True, text=True, timeout=20)
        return len(result.stdout.split()) if result.returncode == 0 else None
    except (OSError, subprocess.TimeoutExpired):
        return None


_current_container: str | None = None


def _kill_current(*_args) -> None:
    if _current_container:
        subprocess.run(["docker", "kill", _current_container], capture_output=True)
    sys.exit(130)


def measure_once(export: str, command: str, cwd: str | None, timeout: float | None, raw: str, log: str, name: str,
                 dry_run: bool) -> dict:
    """One measured run in a fresh container; returns the measurement (or {"measure_error": ...})."""
    global _current_container
    margs = ["bench/measure.sh", "--require-perf", "--in", export, "-o", raw]
    if timeout:
        margs += ["--timeout", str(int(timeout))]
    if cwd:
        margs += ["--cwd", cwd]
    margs += ["-c", command]
    argv = [DOCKER, "--arena-limits", "--no-network", "--name", name] + margs
    if dry_run:
        print("  " + " ".join(shlex.quote(a) for a in argv))
        return {"dry_run": True}
    if os.path.exists(raw):
        os.remove(raw)
    record = {"started_utc": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
              "host_loadavg_start": loadavg(), "other_containers": other_containers()}
    _current_container = name
    try:
        with open(log, "w") as handle:
            rc = subprocess.run(argv, stdout=handle, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, cwd=ROOT).returncode
    finally:
        _current_container = None
    record["host_loadavg_end"] = loadavg()
    record["docker_exit"] = rc
    record["log"] = os.path.relpath(log, ROOT)
    try:
        with open(raw) as handle:
            record.update(json.load(handle))
    except (OSError, ValueError) as exc:
        record["measure_error"] = f"no measurement ({exc}); see {record['log']}"
    try:
        with open(log, errors="replace") as handle:
            record.update(read_passes(handle.read()))
    except OSError:
        pass
    return record


def run_valid(run: dict, expected_exit: int) -> tuple[bool, str]:
    if "measure_error" in run:
        return False, run["measure_error"]
    if run.get("timed_out"):
        return False, "timed out"
    if run.get("exit_code") != expected_exit:
        return False, f"exit status {run.get('exit_code')}, expected {expected_exit}"
    if run.get("cpu_time_s") is None:
        return False, "no CPU time"
    if run.get("instructions") is None:
        return False, "no instruction count (perf counters unavailable)"
    if (run.get("counter_coverage") or 1.0) < 0.999:
        return False, f"counter coverage {run.get('counter_coverage')}"
    return passes_valid(run, expected_exit)


# ------------------------------------------------------------ build info ----
def sha256_of(path: str) -> str | None:
    try:
        digest = hashlib.sha256()
        with open(path, "rb") as handle:
            for chunk in iter(lambda: handle.read(1 << 20), b""):
                digest.update(chunk)
        return digest.hexdigest()
    except OSError:
        return None


def build_info(dist: str, binary: str) -> dict:
    info: dict = {"dist": os.path.relpath(dist, ROOT)}
    path = os.path.join(dist, "bin", binary)
    try:
        stat = os.stat(path)
        info.update(binary=os.path.relpath(path, ROOT), binary_bytes=stat.st_size, binary_sha256=sha256_of(path),
                    binary_mtime_utc=datetime.datetime.fromtimestamp(stat.st_mtime, datetime.timezone.utc)
                    .strftime("%Y-%m-%dT%H:%M:%SZ"))
    except OSError:
        pass
    try:
        with open(os.path.join(dist, "BUILD-INFO.txt")) as handle:
            for line in handle:
                if line.startswith("built:"):
                    info["built"] = line.split(":", 1)[1].strip()
                elif line.startswith("modules:"):
                    info["modules"] = line.split(":", 1)[1].strip()
    except OSError:
        pass
    # jars decide what a JVM launcher runs
    lib = os.path.join(dist, "lib")
    if os.path.isdir(lib):
        digest = hashlib.sha256()
        for name in sorted(os.listdir(lib)):
            if name.startswith("rlvm-") and name.endswith(".jar"):
                digest.update(name.encode())
                digest.update((sha256_of(os.path.join(lib, name)) or "").encode())
        info["rlvm_jars_sha256"] = digest.hexdigest()
    try:
        result = subprocess.run(["git", "-C", ROOT, "rev-parse", "HEAD"], capture_output=True, text=True, timeout=20)
        if result.returncode == 0:
            info["commit"] = result.stdout.strip()
    except (OSError, subprocess.TimeoutExpired):
        pass
    return info


# ------------------------------------------------------------ printing ------
def fmt_s(value) -> str:
    if value is None:
        return "-"
    return f"{value:.1f}" if value >= 100 else f"{value:.2f}" if value >= 1 else f"{value:.3f}"


def fmt_mib(value) -> str:
    return "-" if not value else f"{value / 2**20:.0f}"


def fmt_g(value) -> str:
    if value is None:
        return "-"
    g = value / 1e9
    return f"{g:.1f}" if g >= 100 else f"{g:.2f}" if g >= 1 else f"{g:.3f}"


def print_comparison(label: str, entry: dict | None, baselines: dict[str, dict]) -> None:
    """Warm wall clock (if the entry has passes), cold wall clock, CPU time, peak RSS, instructions (OWNER-NOTES 12)."""
    rows = []
    if entry and entry.get("cpu_time_s") is not None:
        rows.append((label, entry, True))
    for checker, base in sorted(baselines.items(), key=lambda kv: (kv[1].get("cpu_time_s") is None, kv[1].get("cpu_time_s") or 0)):
        rows.append((checker, base, False))
    if not rows:
        print("  (no measurement and no baseline for this corpus)")
        return
    ours = entry.get("cpu_time_s") if entry else None
    ours_wall = entry.get("wall_time_s") if entry else None
    ours_warm = entry.get("warm_wall_s") if entry else None
    print(f"  {'checker':<22} {'warm s':>8} {'wall s':>9} {'CPU s':>9} {'(median)':>9} {'RSS MiB':>8} {'instr G':>9} {'runs':>4}   "
          f"{'ours/their wall':>15} {'warm/their wall':>15} {'ours/their CPU':>14}")
    for name, row, mine in rows:
        ratio = ratio_wall = ratio_warm = ""
        if not mine and ours is not None and row.get("cpu_time_s"):
            ratio = f"{ours / row['cpu_time_s']:.2f}x"
        if not mine and ours_wall is not None and row.get("wall_time_s"):
            ratio_wall = f"{ours_wall / row['wall_time_s']:.2f}x"
        if not mine and ours_warm is not None and row.get("wall_time_s"):
            # a baseline has no warm-up to speak of (Rust): its wall clock stands for its steady state
            ratio_warm = f"{ours_warm / row['wall_time_s']:.2f}x"
        print(f"  {('> ' + name) if mine else name:<22} {fmt_s(row.get('warm_wall_s')):>8} {fmt_s(row.get('wall_time_s')):>9} "
              f"{fmt_s(row.get('cpu_time_s')):>9} {fmt_s(row.get('cpu_time_s_median')):>9} "
              f"{fmt_mib(row.get('max_rss_bytes')):>8} {fmt_g(row.get('instructions')):>9} {row.get('n_runs', ''):>4}   "
              f"{ratio_wall:>15} {ratio_warm:>15} {ratio:>14}")
    if not baselines:
        print("  (no baseline measured on this host for this corpus: bench/baselines/big.sh, or track --checker NAME)")


def print_history(entries: list[dict], limit: int = 12) -> None:
    if not entries:
        print("  (no history)")
        return
    print(f"  {'utc':<20} {'configuration':<28} {'warm s':>8} {'wall s':>9} {'CPU s':>9} {'RSS MiB':>8} {'instr G':>9} {'delta':>8}  status")
    for entry in entries[-limit:]:
        # delta_pct: the rule's own figure (wall clock) since 2026-11-24; older entries have the CPU delta only
        delta = entry.get("delta_pct") if "metric" in entry else entry.get("cpu_delta_pct")
        status = ("INVALID: " + str(entry.get("invalid_reason"))) if not entry.get("valid") else \
            ("REGRESSION" + (" (accepted: " + str(entry.get("accept_reason")) + ")" if entry.get("accepted") else "")) \
            if entry.get("regression") else "accepted" + (" (improvement)" if entry.get("improvement") else "")
        if entry.get("reference"):
            status += "  <- reference"
        print(f"  {entry.get('utc', '?'):<20} {entry.get('config', '?')[:28]:<28} {fmt_s(entry.get('warm_wall_s')):>8} "
              f"{fmt_s(entry.get('wall_time_s')):>9} {fmt_s(entry.get('cpu_time_s')):>9} "
              f"{fmt_mib(entry.get('max_rss_bytes')):>8} {fmt_g(entry.get('instructions')):>9} "
              f"{('%+.1f%%' % delta) if delta is not None else '':>8}  {status}")


def print_phases(phases: dict) -> None:
    print(f"  {'phase':<10} {'CPU s':>9} {'share':>6} {'wall s':>9} {'instr G':>9}   how")
    total = phases.get("total", {}).get("cpu_time_s")
    for name in ("load", "env", "check", "total"):
        row = phases.get(name)
        if not row:
            continue
        share = f"{100 * row['cpu_time_s'] / total:.0f}%" if total and row.get("cpu_time_s") is not None else ""
        print(f"  {name:<10} {fmt_s(row.get('cpu_time_s')):>9} {share:>6} {fmt_s(row.get('wall_time_s')):>9} "
              f"{fmt_g(row.get('instructions')):>9}   {row.get('how', '')}")


def phase_table(total: dict, stops: dict[str, dict]) -> dict:
    """Per-phase figures by differencing runs that stop after a phase (each a median over its runs)."""
    def diff(a: dict, b: dict | None, how: str) -> dict:
        out = {"how": how}
        for key in ("cpu_time_s", "wall_time_s", "instructions"):
            if a.get(key) is None or (b is not None and b.get(key) is None):
                out[key] = None
            else:
                out[key] = a[key] - (b[key] if b is not None else 0)
        return out

    table = {}
    load, env = stops.get("load"), stops.get("env")
    if load:
        table["load"] = diff(load, None, "run with stop-after=load (process start, reading, loading)")
    if load and env:
        table["env"] = diff(env, load, "stop-after=env minus stop-after=load (environment pass)")
    if env:
        table["check"] = diff(total, env, "complete run minus stop-after=env (checking, verdict, exit)")
    table["total"] = diff(total, None, "complete run")
    return table


# ------------------------------------------------------------ main ----------
def main() -> int:
    parser = argparse.ArgumentParser(prog="bench/track/track.py", add_help=True,
                                     description="Measure RLVM on one corpus like the arena, append to the history, "
                                                 "compare with the baselines, apply the CPU-time regression rule.")
    parser.add_argument("corpus")
    parser.add_argument("--launcher", choices=sorted(BINARIES), default="native", help="which binary of the distribution (default native)")
    parser.add_argument("--threads", type=int, help="worker threads (default: the run line of scripts/arena/rlvm.yaml)")
    parser.add_argument("--rlvm-arg", action="append", default=[], metavar="ARG", help="extra argument for rlvm (repeatable), e.g. --rlvm-arg -X --rlvm-arg engine=reference")
    parser.add_argument("--engine", metavar="NAME", help="engine to measure: shorthand for --rlvm-arg -X --rlvm-arg engine=NAME (reference, machine, ...)")
    parser.add_argument("--extra-cp", metavar="PATH", help="class path entries appended for the JVM launcher (RLVM_EXTRA_CP), e.g. an engine that is not in the distribution yet: rlvm-machine/target/classes")
    parser.add_argument("--dist", default="work/dist", help="distribution directory (default work/dist)")
    parser.add_argument("--checker", metavar="NAME", help="measure the baseline bench/baselines/NAME.sh instead of RLVM")
    parser.add_argument("--cmd", metavar="COMMAND", help="measure this shell command (export path in $IN) instead of RLVM; needs --label")
    parser.add_argument("--label", help="name of the --cmd checker in the history")
    parser.add_argument("--cwd", help="working directory of --cmd")
    parser.add_argument("--runs", type=int, default=3, help="measured runs (fresh processes); the entry's times are their minima (default 3)")
    parser.add_argument("--passes", type=int, default=1, metavar="N", help="RLVM: check the export N times per process (-X passes=N) and record the warm wall clock = the median later pass (default 1: a plain cold run)")
    parser.add_argument("--threshold", type=float, default=THRESHOLD_PCT, help="wall-clock regression threshold in percent (default 5)")
    parser.add_argument("--accept", metavar="REASON", help="accept this entry as the new reference even if it regresses")
    parser.add_argument("--expect-exit", type=int, default=None, help="exit status a valid run must have (default: 0 = accept; 1 = reject for a published bad test)")
    parser.add_argument("--timeout-factor", type=float, default=2.0, help="timeout = factor x the arena's timeout of the corpus (default 2; 3600 s when the arena has none)")
    parser.add_argument("--phases", action="store_true", help="also measure runs that stop after loading and after the environment pass (rlvm -X stop-after=...) and print the per-phase table")
    parser.add_argument("--phase-cmd", action="append", default=[], metavar="PHASE=COMMAND", help="with --cmd: the command that stops after PHASE (load | env)")
    parser.add_argument("--history", default=DEFAULT_HISTORY, help="history directory (default work/perf-history)")
    parser.add_argument("--note", help="free text stored with the entry")
    parser.add_argument("--no-record", action="store_true", help="measure and compare, but do not append to the history")
    parser.add_argument("--show", action="store_true", help="print history and comparison; measure nothing")
    parser.add_argument("--dry-run", action="store_true", help="print the container commands; measure nothing")
    args = parser.parse_args()

    corpus, export, arena_name, expected_verdict = resolve(args.corpus)
    if args.expect_exit is None:
        args.expect_exit = expected_verdict
    if args.engine:
        args.rlvm_arg = ["-X", "engine=" + args.engine] + args.rlvm_arg
    if args.passes < 1:
        die("--passes must be at least 1")
    if args.passes > 1:
        if args.cmd or args.checker:
            die("--passes is an option of RLVM (-X passes=N); a baseline has no passes")
        args.rlvm_arg = args.rlvm_arg + ["-X", f"passes={args.passes}"]
    if args.extra_cp and (args.launcher != "jvm" or args.cmd or args.checker):
        die("--extra-cp needs the JVM launcher (--launcher jvm): a native image has a closed class path")
    history_dir = os.path.abspath(args.history)
    if not (history_dir + os.sep).startswith(os.path.join(ROOT, "work") + os.sep):
        die("--history must be below work/ (the container writes there)")
    entries = read_history(history_dir, corpus)

    # ---- what to run ----
    phase_cmds: dict[str, str] = {}
    cwd = None
    threads = None
    if args.cmd:
        if not args.label:
            die("--cmd needs --label")
        checker, command, config = args.label, args.cmd, args.label
        cwd = os.path.abspath(args.cwd) if args.cwd else None
        for item in args.phase_cmd:
            if "=" not in item or item.split("=", 1)[0] not in ("load", "env"):
                die("--phase-cmd needs load=COMMAND or env=COMMAND")
            phase_cmds[item.split("=", 1)[0]] = item.split("=", 1)[1]
        build = {}
    elif args.checker:
        wrapper = os.path.join(ROOT, "bench", "baselines", args.checker + ".sh")
        if not os.access(wrapper, os.X_OK):
            die(f"no wrapper bench/baselines/{args.checker}.sh", 70)
        checker, command, config = args.checker, f'bench/baselines/{args.checker}.sh "$IN"', args.checker
        build = {}
    else:
        if args.launcher == "pgo" and args.dist == "work/dist":
            args.dist = ORACLE_DIST   # the optional Oracle build has a distribution of its own
        dist = args.dist if os.path.isabs(args.dist) else os.path.join(ROOT, args.dist)
        if os.path.islink(dist):
            # A versioned distribution (scripts/build.sh: work/dist -> work/dist.d/<version>): pin the
            # version now, so that every run of this entry measures the same build even if the link
            # is switched by a rebuild meanwhile; the history records the version's directory.
            dist = os.path.realpath(dist)
            args.dist = os.path.relpath(dist, ROOT) if dist.startswith(ROOT + os.sep) else dist
        binary = BINARIES[args.launcher]
        if not args.show and not os.access(os.path.join(dist, "bin", binary), os.X_OK):
            built = [mode for mode, name in sorted(BINARIES.items()) if os.access(os.path.join(dist, "bin", name), os.X_OK)]
            hint = (f"built in {args.dist}: " + ", ".join("--launcher " + m for m in built)) if built else \
                f"nothing is built in {args.dist} (scripts/build.sh; the tracker itself can be exercised with --checker NAME)"
            message = f"{os.path.join(args.dist, 'bin', binary)} not found; {hint}"
            if not args.dry_run:
                die(message, 70)
            # a dry run measures nothing: show the commands anyway, and say that they would not run
            print(f"track: WARNING: {message}", file=sys.stderr)

        def run_line(extra: list[str]) -> str:
            argv = [RUN_SH, "--" + args.launcher, "--dist", args.dist, "--print"]
            if args.threads:
                argv += ["--threads", str(args.threads)]
            if extra:
                argv += ["--"] + extra
            result = subprocess.run(argv, capture_output=True, text=True)
            if result.returncode != 0:
                die(f"bench/rlvm/run.sh --print failed: {result.stderr.strip()}", 70)
            return result.stdout.strip()

        if args.extra_cp:
            extra_cp = ":".join(os.path.abspath(p) for p in args.extra_cp.split(":") if p)
            inner = run_line
            run_line = lambda extra: "RLVM_EXTRA_CP=" + shlex.quote(extra_cp) + " " + inner(extra)  # noqa: E731
        if args.launcher != "jvm" and args.passes > 1:
            # The executable is a hybrid entry (*agentic-note*: rep-OPT-K): a run with several passes is
            # handed over to a JVM when a JDK is found. An entry of the executable measures the executable.
            inner_image = run_line
            run_line = lambda extra: "RLVM_PLATFORM=image " + inner_image(extra)  # noqa: E731
        checker = "rlvm"
        command = run_line(args.rlvm_arg)
        cwd = ROOT
        threads = args.threads
        if threads is None:
            match = re.search(r"--threads (\d+)", command)
            threads = int(match.group(1)) if match else None
        config = f"rlvm/{args.launcher}/t{threads if threads is not None else 'auto'}"
        if args.rlvm_arg:
            config += "/" + " ".join(args.rlvm_arg)
        if args.extra_cp:
            config += "/+cp"
        if args.phases:
            for phase, extra in PHASES:
                phase_cmds[phase] = run_line(args.rlvm_arg + extra)
        build = build_info(dist, binary)

    baselines = load_baselines(corpus, history_dir, arena_name, args.expect_exit)
    size = os.path.getsize(export)
    print(f"corpus {corpus}: {os.path.relpath(export, ROOT)} ({size:,} bytes); configuration {config}")

    if args.show:
        print("history (%s):" % os.path.relpath(history_file(history_dir, corpus), ROOT))
        print_history(entries)
        metric = "warm_wall_s" if re.search(r"passes=([2-9]|[1-9][0-9])", config) else "wall_time_s"
        reference = last_reference(entries, config, metric)
        print(f"comparison (wall clock first; '>' = the reference, i.e. the last accepted entry of {config}):")
        print_comparison(config, reference, baselines)
        if reference and reference.get("phases"):
            print("phases of that entry:")
            print_phases(reference["phases"])
        return 0

    # ---- measure ----
    at = arena_timeout(arena_name) or arena_timeout(corpus)
    timeout = at * args.timeout_factor if at else 3600.0
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    raw_dir = os.path.join(history_dir, "raw", corpus)
    if not args.dry_run:
        os.makedirs(raw_dir, exist_ok=True)
    signal.signal(signal.SIGINT, _kill_current)
    signal.signal(signal.SIGTERM, _kill_current)
    tag = "".join(c if c.isalnum() or c in "._-" else "_" for c in config)

    def series(what: str, cmd: str, expected_exit: int) -> tuple[list[dict], bool, str]:
        runs, ok, why = [], True, ""
        for i in range(1, args.runs + 1):
            base = os.path.join(raw_dir, f"{stamp}.{tag}.{what}.{i}")
            name = f"rlvm-track-{os.getpid()}-{what}-{i}"
            print(f"  {what} run {i}/{args.runs} ... ", end="", flush=True)
            run = measure_once(export, cmd, cwd, timeout, base + ".json", base + ".log", name, args.dry_run)
            if args.dry_run:
                continue
            run["run"] = i
            valid, reason = run_valid(run, expected_exit)
            warm = "" if run.get("warm_wall_s") is None else "warm %s s (passes: %s)  " % (
                fmt_s(run["warm_wall_s"]), " ".join(fmt_s(p["wall_s"]) for p in run["passes"]))
            print("exit %s  %swall %s s  cpu %s s  rss %s MiB  instr %s G%s" % (
                run.get("exit_code"), warm, fmt_s(run.get("wall_time_s")), fmt_s(run.get("cpu_time_s")),
                fmt_mib(run.get("max_rss_bytes")), fmt_g(run.get("instructions")), "" if valid else "   INVALID: " + reason))
            if not valid and ok:
                ok, why = False, reason
            runs.append(run)
        return runs, ok, why

    print(f"measuring: {args.runs} run(s), one fresh container each (--arena-limits --no-network), timeout {int(timeout)} s")
    print(f"  command: {command}")
    runs, valid, invalid_reason = series("total", command, args.expect_exit)
    phase_runs: dict[str, list[dict]] = {}
    phases_ok = True
    for phase, cmd in phase_cmds.items():
        # a run that stops after a phase ends with DECLINE by design (rlvm-main: stop-after)
        expected = 2 if not args.cmd else args.expect_exit
        phase_runs[phase], ok, why = series(phase, cmd, expected)
        if not ok:
            phases_ok = False
            print(f"  WARNING: phase '{phase}' could not be measured ({why}); no per-phase table")
    if args.dry_run:
        return 0

    summary = summarise_runs(runs) if valid else summarise_runs([r for r in runs if r.get("cpu_time_s") is not None])
    metric, value = metric_of(summary)
    reference = last_reference(entries, config, metric)
    verdict = judge(value, valid, reference, args.threshold, args.accept, summary.get("instructions"), metric,
                    summary.get("cpu_time_s"))
    spread = None
    if valid and summary["cpu_time_s"]:
        spread = round(100.0 * (summary["cpu_time_s_max"] - summary["cpu_time_s"]) / summary["cpu_time_s"], 2)
    entry = {
        "utc": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "corpus": corpus, "export": os.path.relpath(export, ROOT), "export_bytes": size,
        "checker": checker, "config": config, "command": command, "cwd": os.path.relpath(cwd, ROOT) if cwd else None,
        "launcher": None if args.cmd or args.checker else args.launcher,
        "threads": None if args.cmd or args.checker else threads,
        "container": "bench/arena-docker.sh --arena-limits --no-network", "timeout_s": int(timeout),
        "valid": valid, "invalid_reason": invalid_reason or None,
        **summary, "cpu_spread_pct": spread, **verdict,
        "build": build, "note": args.note, "runs": runs,
    }
    if phase_cmds and phases_ok and valid:
        stops = {phase: summarise_runs(phase_runs[phase]) for phase in phase_runs}
        entry["phases"] = phase_table(summary, stops)
        entry["phase_runs"] = phase_runs

    if not args.no_record:
        os.makedirs(history_dir, exist_ok=True)
        with open(history_file(history_dir, corpus), "a") as handle:
            handle.write(json.dumps(entry) + "\n")

    # ---- report ----
    print()
    if not valid:
        print(f"INVALID measurement: {invalid_reason}. Recorded as invalid; it is not compared and never becomes a reference.")
    print("comparison with the baselines measured on this host (wall clock first):")
    print_comparison(config, entry if valid else None, baselines if checker == "rlvm" else
                     {k: v for k, v in baselines.items() if k != checker})
    if entry.get("phases"):
        print("phases:")
        print_phases(entry["phases"])
    loads = [x for r in runs for x in (r.get("host_loadavg_start") or [])[:1]]
    busy = max(loads) if loads else 0.0
    if valid:
        if spread is not None and spread > args.threshold:
            print(f"WARNING: CPU time varies by {spread:.1f} % between the runs of this entry "
                  f"(min {fmt_s(summary['cpu_time_s'])} s, max {fmt_s(summary['cpu_time_s_max'])} s); the minimum is used.")
        if busy > 8.0:
            print(f"WARNING: the host was busy (load average {busy:.1f}); other containers share the 8 performance cores, "
                  "which inflates CPU time (frequency, caches) but not instruction counts.")
        print("regression rule (%s, threshold %.1f %%):" % (METRIC_NAMES[metric], args.threshold))
        if metric == "wall_time_s" and checker == "rlvm":
            print("  note: this is a COLD entry; the target of OWNER-NOTES 12 is the warm wall clock (--passes 5 --threads 8).")
        if reference is None:
            print(f"  no accepted entry of {config} before this one: accepted as the first reference "
                  f"({fmt_s(value)} s).")
        else:
            idelta = verdict.get("instructions_delta_pct")
            cdelta = verdict.get("cpu_delta_pct")
            print(f"  last accepted reference {reference.get('utc')}: {fmt_s(reference[metric])} s; now "
                  f"{fmt_s(value)} s: {verdict['delta_pct']:+.2f} %"
                  + (f"   (CPU time {cdelta:+.2f} %)" if cdelta is not None else "")
                  + (f"   (instructions {idelta:+.2f} %)" if idelta is not None else ""))
            if verdict["regression"] and not verdict["accepted"]:
                print(f"  REGRESSION: more than {args.threshold:.1f} % slower. Not accepted; the reference stays. "
                      "Fix it, re-measure if the host was busy, or justify it with --accept \"REASON\".")
                if idelta is not None and abs(idelta) < 1.0:
                    print("  note: the instruction count moved by less than 1 %; with a busy host this smells like "
                          "contention on the shared cores rather than a change in RLVM. Re-measure inside a bench/quiet.py window.")
            elif verdict["regression"]:
                print(f"  REGRESSION accepted on purpose: {verdict['accept_reason']}. This entry is the new reference.")
            elif verdict["improvement"]:
                print(f"  improvement of more than {args.threshold:.1f} %; accepted, this entry is the new reference.")
            elif verdict["reference"]:
                print(f"  within the threshold; made the new reference on request: {verdict['accept_reason']}.")
            else:
                print("  within the threshold; accepted. The reference stays, so small steps cannot add up unnoticed.")
    if not args.no_record:
        print(f"history: {os.path.relpath(history_file(history_dir, corpus), ROOT)} ({len(entries) + 1} entries)")
    if not valid:
        return 2
    return 1 if verdict["regression"] and not verdict["accepted"] else 0


if __name__ == "__main__":
    sys.exit(main())
