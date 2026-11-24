#!/usr/bin/env python3
"""bench/measure.py - run one command and measure it the way the Lean Kernel Arena does.

The arena (refs/lean-kernel-arena/lka.py, measure_perf_with_fallback) runs

    perf stat -j -o TMP -e duration_time -e task-clock -e instructions -- \\
        time -f "real_seconds=%e\\nuser_seconds=%U\\nsys_seconds=%S\\nmax_rss_kb=%M" -o TMP -- \\
        sh -c "$RUN"

with LC_ALL=C and IN=<export file> in the environment, and ranks checkers by
instructions / 6e9 ("virtual time"). This script does the same and prints one
JSON line:

    {"exit_code": 0, "instructions": 123, "virtual_time_s": 2.05e-08,
     "cpu_time_s": ..., "wall_time_s": ..., "max_rss_bytes": ..., ...}

("counter_coverage" is a self-check: the fraction of the CPU time during which
the instruction counters were counting; anything below 1.0 means the count is
too low.)

Two deliberate differences from the arena's command line, both needed to get
the arena's *numbers* on this machine:

* `-e instructions:u` instead of `-e instructions`. The arena runner has
  perf_event_paranoid=2, where perf silently falls back to user-space-only
  counting; here perf has CAP_PERFMON and would include the kernel. The
  kernel-mode count is reported separately as "instructions_kernel".
* `--no-scale`, and the counts of all PMUs are added up. This host is
  big.LITTLE (two PMUs: armv8_pmuv3_0 and armv8_pmuv3_1); perf opens one
  event per PMU, each of which only counts while a thread runs on that core
  type, and by default perf "scales" each by enabled/running time, which
  extrapolates both to the whole run and makes their sum meaningless.

If hardware counters are not available (no CAP_PERFMON, or on the host where
kernel.perf_event_paranoid=4), "instructions" and "virtual_time_s" are null
and "cpu_time_s" is GNU time's user+sys, labelled in "cpu_time_source".

Usage:
    measure.py [options] [--] command [args...]
    measure.py [options] -c 'shell command'      # like the arena: sh -c
Run it inside the container:  bench/arena-docker.sh bench/measure.sh -- cmd ...

Python stdlib only. Importable: `from measure import measure, probe_perf`.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time

INSTRUCTIONS_PER_SECOND = 6_000_000_000  # the arena's fixed conversion rate
TIME_FORMAT = "real_seconds=%e\nuser_seconds=%U\nsys_seconds=%S\nmax_rss_kb=%M"
OUTPUT_KEEP_BYTES = 8 * 1024  # of the head and of the tail of stdout/stderr

_probe_cache: tuple[bool, str] | None = None
_kernel_event_ok = False


def find_gnu_time() -> str | None:
    """Path of GNU time (not the shell builtin), or None."""
    return shutil.which("time")


def _probe_events(events: list[str]) -> tuple[bool, str]:
    """Can perf stat count all of `events` for a child process?"""
    cmd = ["perf", "stat", "--no-scale", "-x", ","]
    for event in events:
        cmd += ["-e", event]
    try:
        result = subprocess.run(cmd + ["--", "true"], capture_output=True, text=True, timeout=30,
                                env=dict(os.environ, LC_ALL="C"))
    except (OSError, subprocess.TimeoutExpired) as exc:
        return False, f"perf probe failed: {exc}"
    # CSV lines: "<count>,,<event>,..." with one line per PMU on big.LITTLE
    # hosts; "<not counted>" for a PMU on whose cores the probe never ran.
    counted = any(line.split(",", 1)[0].strip().isdigit() and "instructions" in line
                  for line in result.stderr.splitlines())
    if result.returncode == 0 and counted:
        return True, ""
    lines = [ln.strip() for ln in result.stderr.splitlines() if ln.strip()]
    return False, "; ".join(lines[:3]) if lines else f"exit status {result.returncode}"


def probe_perf() -> tuple[bool, str]:
    """Whether `perf stat -e instructions:u` can count here.

    Returns (usable, detail). The arena only checks `perf --version`; we also
    check that a hardware counter can actually be opened, because without the
    permission perf stat refuses to run the command at all. Also finds out
    whether the kernel-mode counter (informational) may be opened too; it may
    not be where perf_event_paranoid=2 without CAP_PERFMON, as on the arena.
    """
    global _probe_cache, _kernel_event_ok
    if _probe_cache is not None:
        return _probe_cache
    if shutil.which("perf") is None:
        _probe_cache = (False, "perf not found in PATH")
        return _probe_cache
    usable, reason = _probe_events(["instructions:u", "instructions:k"])
    if usable:
        _kernel_event_ok = True
    else:
        usable, reason = _probe_events(["instructions:u"])
    if usable:
        version = subprocess.run(["perf", "--version"], capture_output=True, text=True).stdout.strip()
        _probe_cache = (True, version)
    else:
        _probe_cache = (False, f"hardware instruction counter unavailable: {reason}")
    return _probe_cache


def _split_event(event: str) -> tuple[str, str, str | None]:
    """Split a perf event name into (base name, modifiers, pmu).

    "instructions:u"                -> ("instructions", "u", None)
    "armv8_pmuv3_0/instructions/u"  -> ("instructions", "u", "armv8_pmuv3_0")
    "cpu_core/instructions:u/"      -> ("instructions", "u", "cpu_core")
    "task-clock"                    -> ("task-clock", "", None)
    """
    pmu = None
    match = re.fullmatch(r"([^/]+)/([^/]+)/([A-Za-z]*)", event)
    if match:
        pmu, base, mods = match.groups()
    else:
        base, mods = event, ""
    if ":" in base:
        base, extra = base.split(":", 1)
        mods += extra
    return base, mods, pmu


def parse_perf_json(path: str) -> dict:
    """Parse `perf stat -j` output into seconds / counts, summing over PMUs."""
    out: dict = {"events": {}}
    user = kernel = None
    user_runtime = 0.0   # ns during which the user-mode instruction events were counting
    clock_runtime = None  # ns of task time, from the task-clock event
    try:
        with open(path, "r", errors="replace") as handle:
            lines = handle.readlines()
    except OSError:
        return out
    for line in lines:
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            data = json.loads(line)
        except json.JSONDecodeError:
            continue
        if "event" not in data or "counter-value" not in data:
            continue
        event = data["event"]
        try:
            value = float(data["counter-value"])
        except (TypeError, ValueError):
            # "<not counted>": no thread ever ran on this PMU's CPUs. That is
            # a count of zero for our purposes; "<not supported>" is not.
            out["events"][event] = data["counter-value"]
            continue
        unit = data.get("unit", "")
        base, mods, _pmu = _split_event(event)
        runtime = data.get("event-runtime")
        if base in ("duration_time", "task-clock"):
            if unit == "msec":
                value *= 1e-3
            else:  # nanoseconds
                value *= 1e-9
            out[base] = value
            out["events"][event] = value
            if base == "task-clock" and isinstance(runtime, (int, float)):
                clock_runtime = float(runtime)
        elif base == "instructions":
            count = int(value)
            out["events"][event] = count
            if "k" in mods and "u" not in mods:
                kernel = (kernel or 0) + count
            else:
                user = (user or 0) + count
                if isinstance(runtime, (int, float)):
                    user_runtime += float(runtime)
    if user is not None:
        out["instructions"] = user
        # With --no-scale a counter that was not scheduled the whole time (too
        # many events for the PMU, i.e. multiplexing) would silently
        # undercount. The per-PMU events together must have been counting for
        # all of the task's CPU time; this is that fraction, 1.0 when all is well.
        if clock_runtime:
            out["counter_coverage"] = user_runtime / clock_runtime
    if kernel is not None:
        out["instructions_kernel"] = kernel
    return out


def parse_time_output(path: str) -> dict:
    """Parse the key=value lines of our GNU time format."""
    values: dict = {}
    try:
        with open(path, "r", errors="replace") as handle:
            for line in handle:
                line = line.strip()
                if "=" in line:
                    key, value = line.split("=", 1)
                    values[key.strip()] = value.strip()
    except OSError:
        pass
    return values


class _Bounded:
    """Keeps the first and the last OUTPUT_KEEP_BYTES of a stream."""

    def __init__(self, keep: int = OUTPUT_KEEP_BYTES):
        self.keep = keep
        self.head = bytearray()
        self.tail = bytearray()
        self.omitted = 0

    def feed(self, chunk: bytes) -> None:
        if len(self.head) < self.keep:
            take = self.keep - len(self.head)
            self.head += chunk[:take]
            chunk = chunk[take:]
        self.tail += chunk
        excess = len(self.tail) - self.keep
        if excess > 0:
            self.omitted += excess
            del self.tail[:excess]

    def text(self) -> str:
        head = self.head.decode(errors="replace")
        tail = self.tail.decode(errors="replace")
        if self.omitted:
            return f"{head}\n[... {self.omitted} bytes omitted ...]\n{tail}"
        return head + tail


def _run(cmd: list[str], cwd, env, timeout, capture: bool, stdout=None, stderr=None):
    """Like lka.py's _run_process: with a timeout the command gets its own
    session and the whole process group is killed when it expires."""
    proc = subprocess.Popen(
        cmd, cwd=cwd, env=env,
        stdin=subprocess.DEVNULL if capture else None,
        stdout=subprocess.PIPE if capture else stdout,
        stderr=subprocess.PIPE if capture else stderr,
        start_new_session=timeout is not None,
    )

    def kill_group() -> None:
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except (ProcessLookupError, PermissionError):
            pass

    outputs = (_Bounded(), _Bounded())
    readers = []
    if capture:
        def drain(pipe, bounded):
            with pipe:
                for chunk in iter(lambda: pipe.read1(65536), b""):
                    bounded.feed(chunk)
        readers = [threading.Thread(target=drain, args=(pipe, bounded), daemon=True)
                   for pipe, bounded in zip((proc.stdout, proc.stderr), outputs)]
        for reader in readers:
            reader.start()

    timed_out = False
    try:
        proc.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        timed_out = True
        kill_group()
        proc.wait()
    except KeyboardInterrupt:
        if timeout is not None:
            kill_group()
        raise
    for reader in readers:
        # A daemonised grandchild may keep the pipe open; do not wait forever.
        reader.join(timeout=5)
    return proc.returncode, timed_out, outputs[0].text(), outputs[1].text()


def measure(cmd, *, shell: bool = False, cwd=None, env: dict | None = None,
            timeout: float | None = None, capture: bool = True, use_perf: bool | None = None,
            stdout=None, stderr=None) -> dict:
    """Run `cmd` once and return the measurements (see the module docstring).

    cmd      list of arguments, or a string run with `sh -c` if shell=True
             (which is what the arena does with a checker's `run` command)
    env      full environment for the command (default: os.environ); LC_ALL=C
             is forced, as on the arena
    timeout  seconds; on expiry the process group is killed (timed_out=True,
             no counters, as on the arena)
    capture  capture stdout/stderr (bounded) into the result; otherwise they
             go to the given `stdout`/`stderr` file objects (default: inherit)
    use_perf None: use perf if the probe succeeds; False: GNU time only
    """
    if isinstance(cmd, str):
        argv = ["sh", "-c", cmd] if shell else cmd.split()
    else:
        argv = list(cmd)

    if use_perf is None:
        use_perf = probe_perf()[0]
    elif use_perf:
        probe_perf()  # also decides whether the kernel-mode counter is usable
    time_bin = find_gnu_time()

    run_env = dict(os.environ if env is None else env)
    run_env["LC_ALL"] = "C"  # as lka.py does: perf must print parseable numbers

    tmpdir = tempfile.mkdtemp(prefix="measure-")
    perf_path = os.path.join(tmpdir, "perf.json")
    time_path = os.path.join(tmpdir, "time.txt")
    full: list[str] = []
    if use_perf:
        full += ["perf", "stat", "--no-scale", "-j", "-o", perf_path,
                 "-e", "duration_time",    # wall-clock time
                 "-e", "task-clock",       # cpu time
                 "-e", "instructions:u"]   # what the arena counts (see module docstring)
        if _kernel_event_ok:
            full += ["-e", "instructions:k"]  # kernel share, informational
        full += ["--"]
    if time_bin:
        full += [time_bin, "-f", TIME_FORMAT, "-o", time_path, "--"]
    full += argv

    start = time.time()
    try:
        returncode, timed_out, out_text, err_text = _run(
            full, cwd, run_env, timeout, capture, stdout=stdout, stderr=stderr)
        elapsed = time.time() - start
        perf = parse_perf_json(perf_path) if use_perf else {"events": {}}
        times = parse_time_output(time_path) if time_bin else {}
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)

    def as_float(key: str) -> float | None:
        try:
            return float(times[key])
        except (KeyError, ValueError):
            return None

    user_s, sys_s = as_float("user_seconds"), as_float("sys_seconds")
    try:
        max_rss = int(times["max_rss_kb"]) * 1024
    except (KeyError, ValueError):
        max_rss = None

    instructions = perf.get("instructions")
    result = {
        "exit_code": returncode,
        "timed_out": timed_out,
        "instructions": instructions,
        "virtual_time_s": (instructions / INSTRUCTIONS_PER_SECOND) if instructions else None,
        "cpu_time_s": None,
        "wall_time_s": perf.get("duration_time", elapsed),
        "max_rss_bytes": max_rss,
        "instructions_kernel": perf.get("instructions_kernel"),
        "counter_coverage": perf.get("counter_coverage"),
        "user_time_s": user_s,
        "sys_time_s": sys_s,
        "instructions_source": "perf stat --no-scale -e instructions:u, summed over PMUs"
                               if instructions is not None else None,
        "cpu_time_source": None,
    }
    if "task-clock" in perf:
        result["cpu_time_s"] = perf["task-clock"]
        result["cpu_time_source"] = "perf task-clock"
    elif user_s is not None and sys_s is not None:
        result["cpu_time_s"] = user_s + sys_s
        result["cpu_time_source"] = "GNU time user+sys (FALLBACK: no perf counters)"
    if capture:
        result["stdout"] = out_text
        result["stderr"] = err_text
    return result


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Run one command under perf stat + GNU time like the Lean Kernel Arena "
                    "and print one JSON line with the measurements.",
        epilog="The command's stdout is sent to stderr so that stdout carries only the JSON "
               "line (use --output to keep them apart instead).")
    parser.add_argument("-c", "--shell", metavar="CMD",
                        help="run CMD with `sh -c`, as the arena runs a checker's `run` command")
    parser.add_argument("--in", dest="in_file", metavar="FILE",
                        help="set IN=FILE in the command's environment (the arena's checker interface)")
    parser.add_argument("--timeout", type=float, default=None,
                        help="kill the command's process group after this many seconds")
    parser.add_argument("--cwd", default=None, help="working directory for the command")
    parser.add_argument("--env", action="append", default=[], metavar="VAR=VALUE",
                        help="extra environment variable (repeatable)")
    parser.add_argument("--no-perf", action="store_true",
                        help="do not use perf; CPU time comes from GNU time (labelled as fallback)")
    parser.add_argument("--require-perf", action="store_true",
                        help="fail (exit 3) instead of falling back if instruction counts are unavailable")
    parser.add_argument("-o", "--output", metavar="FILE",
                        help="write the JSON line to FILE; the command's stdout is then left alone")
    parser.add_argument("--propagate-exit-code", action="store_true",
                        help="exit with the command's exit status instead of 0")
    parser.add_argument("command", nargs=argparse.REMAINDER, help="command and arguments")
    args = parser.parse_args()

    command = args.command
    if command and command[0] == "--":
        command = command[1:]
    if args.shell is not None and command:
        parser.error("give either -c CMD or a command, not both")
    if args.shell is None and not command:
        parser.error("no command given")

    env = dict(os.environ)
    for item in args.env:
        if "=" not in item:
            parser.error(f"--env needs VAR=VALUE, got {item!r}")
        key, value = item.split("=", 1)
        env[key] = value
    if args.in_file:
        env["IN"] = os.path.abspath(args.in_file)

    use_perf = None
    if args.no_perf:
        use_perf = False
    else:
        usable, detail = probe_perf()
        if not usable:
            if args.require_perf:
                print(f"measure: {detail}", file=sys.stderr)
                return 3
            print(f"measure: WARNING: {detail}; falling back to GNU time (no instruction counts)",
                  file=sys.stderr)

    result = measure(
        args.shell if args.shell is not None else command,
        shell=args.shell is not None,
        cwd=args.cwd, env=env, timeout=args.timeout, capture=False, use_perf=use_perf,
        stdout=None if args.output else sys.stderr,
    )
    coverage = result.get("counter_coverage")
    if coverage is not None and coverage < 0.99:
        print(f"measure: WARNING: the instruction counter covered only {coverage:.1%} of the "
              f"CPU time (multiplexed?); the count is too low", file=sys.stderr)
    line = json.dumps(result)
    if args.output:
        with open(args.output, "w") as handle:
            handle.write(line + "\n")
    else:
        sys.stderr.flush()
        print(line, flush=True)
    if args.propagate_exit_code:
        code = result["exit_code"]
        return code if 0 <= code <= 255 else 128 + abs(code)
    return 0


if __name__ == "__main__":
    sys.exit(main())
