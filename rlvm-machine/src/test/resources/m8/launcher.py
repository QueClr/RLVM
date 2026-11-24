#!/usr/bin/env python3
"""rlvm-machine/src/test/resources/m8/launcher.py - the gate in its own form: every test through the
launcher with `-X engine=machine` (ISA section 11 v0 gate; WORK-PACKAGES section 6).

    report.sh launcher [tutorial|bugs|all|FILE_OR_DIR ...] [-j N] [--timeout S] [--label NAME]

For every export: `RLVM_EXTRA_CP=<machine jar> work/dist/bin/rlvm-jvm --threads 1 FILE -X engine=machine
-X stats-file=F`, then
  - the exit status against the arena's classification (good/ = accept, bad/ = reject; other files: accept);
  - the statistics: values.engine.primary.machine.{accepted,rejected,declined} and units.declined.
The exit status alone proves nothing (the driver re-checks every machine decline with the reference
kernel): a test passes the gate only if the verdict is right AND the machine declined nothing.
Writes work/status/machine-launcher-NAME.txt. Exit: 0 written and no wrong verdict; 1 a wrong verdict;
2 usage / no launcher.
"""
import argparse
import concurrent.futures
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
    return os.environ.get("RLVM_ROOT") or sys.exit("launcher.py: cannot find the project root (set RLVM_ROOT)")


ROOT = find_root()
TESTS = "work/arena-site/tests/"
CORPORA = {
    "tutorial": [TESTS + "good/tutorial", TESTS + "bad/tutorial"],
    "bugs": [TESTS + "good/bugs", TESTS + "bad/bugs"],
    "all": [TESTS + "good", TESTS + "bad"],
    "init": ["work/exports/init.ndjson"],
    "std": ["work/exports/std.ndjson"],
    "cedar": ["work/workloads/slices/cedar-hot-ax.ndjson"],
    "reachability": ["work/exports/reachability.ndjson"],
}
JAR = ".toolchain/m2/rlvm/rlvm-machine/0.1.0-SNAPSHOT/rlvm-machine-0.1.0-SNAPSHOT.jar"


def exports(roots):
    out = []
    for r in roots:
        p = os.path.join(ROOT, r)
        if os.path.isfile(p):
            out.append(r)
        elif os.path.isdir(p):
            for d, _, files in os.walk(p):
                for f in files:
                    if f.endswith(".ndjson"):
                        out.append(os.path.relpath(os.path.join(d, f), ROOT))
    return sorted(out)


def get(d, path, default=None):
    """values.* are flat dotted keys inside "values"; everything else is nested."""
    if path.startswith("values."):
        return d.get("values", {}).get(path[len("values."):], default)
    for k in path.split("."):
        if not isinstance(d, dict) or k not in d:
            return default
        d = d[k]
    return d


def run_one(path, tmp, timeout, jar, props):
    stats = os.path.join(tmp, path.replace("/", "_") + ".json")
    env = dict(os.environ)
    env["RLVM_EXTRA_CP"] = os.path.join(ROOT, jar)
    if props:
        env["JAVA_TOOL_OPTIONS"] = (env.get("JAVA_TOOL_OPTIONS", "") + " " + " ".join("-D" + p for p in props)).strip()
    cmd = [os.path.join(ROOT, "work/dist/bin/rlvm-jvm"), "--threads", "1", os.path.join(ROOT, path), "-X", "engine=machine",
           "-X", "stats-file=" + stats]
    r = {"path": path, "exit": None, "error": ""}
    try:
        p = subprocess.run(cmd, cwd=ROOT, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=timeout)
        r["exit"] = p.returncode
        if p.returncode not in (0, 1, 2):
            r["error"] = p.stderr.decode("utf-8", "replace").strip().splitlines()[-1:] and p.stderr.decode("utf-8", "replace").strip().splitlines()[-1][:200]
    except subprocess.TimeoutExpired:
        r["error"] = "timeout after %d s" % timeout
    try:
        with open(stats) as f:
            s = json.load(f)
        r["engine"] = get(s, "values.engine.primary.name", "?")
        # no engine at all and no unit run: the loader decided the file (malformed export), for every engine alike
        r["loader"] = r["engine"] == "?" and get(s, "units.count", 0) == 0 and get(s, "values.driver.verdict") is not None
        r["accepted"] = get(s, "values.engine.primary.machine.accepted", 0)
        r["rejected"] = get(s, "values.engine.primary.machine.rejected", 0)
        r["declined"] = get(s, "values.engine.primary.machine.declined", -1)
        r["units_declined"] = get(s, "units.declined", -1)
        r["units"] = get(s, "units.count", 0)
        r["ticks"] = get(s, "values.engine.primary.machine.ticks", 0)
        r["incomplete"] = get(s, "values.driver.machineIncomplete", 0)
        r["anomalies"] = get(s, "values.driver.warmAnomalies", 0)
    except (OSError, ValueError):
        r["engine"] = "?"
        r["declined"] = -1
        r["units_declined"] = -1
    if r.get("loader"):
        r["declined"] = 0
    return r


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("roots", nargs="*", default=["tutorial"])
    ap.add_argument("-j", type=int, default=6)
    ap.add_argument("--timeout", type=int, default=900)
    ap.add_argument("--label")
    ap.add_argument("--jar", default=JAR, help="machine jar (default: the one the last publication installed)")
    ap.add_argument("--prop", action="append", default=[], help="system property for the launcher's JVM, e.g. rlvm.machine.hostDepth=0")
    a = ap.parse_args()
    launcher = os.path.join(ROOT, "work/dist/bin/rlvm-jvm")
    if not os.path.isfile(launcher):
        sys.exit("launcher.py: no work/dist/bin/rlvm-jvm (run: scripts/dev.sh dist)")
    if not os.path.isfile(os.path.join(ROOT, a.jar)):
        sys.exit("launcher.py: no machine jar " + a.jar)
    label = a.label or ("-".join(a.roots) if all(r in CORPORA for r in a.roots) else "custom")
    roots = []
    for r in a.roots:
        roots += CORPORA.get(r, [r])
    files = exports(roots)
    if not files:
        sys.exit("launcher.py: no export below " + ", ".join(roots))
    results = []
    with tempfile.TemporaryDirectory(prefix="m8-launcher-", dir=os.path.join(ROOT, "work")) as tmp:
        with concurrent.futures.ThreadPoolExecutor(max_workers=a.j) as ex:
            for r in ex.map(lambda p: run_one(p, tmp, a.timeout, a.jar, a.prop), files):
                results.append(r)
    wrong = clean = declined_files = errors = 0
    acc = rej = dec = 0
    lines = []
    for r in results:
        expected = 1 if "/bad/" in r["path"] else 0
        short = r["path"].replace(TESTS, "").replace("work/", "")
        ex = r["exit"]
        if ex in (0, 1, 2) and r.get("loader") and ex == expected:
            status = "LOADER"    # decided by the loader before any engine ran: clean
            clean += 1
        elif ex is None or ex not in (0, 1, 2) or r["engine"] != "machine":
            status = "ERROR"
            errors += 1
        elif ex == 2:
            status = "DECLINE"   # the run as a whole declined: no verdict, never wrong
            declined_files += 1
        elif ex != expected:
            status = "WRONG"
            wrong += 1
        elif r["declined"] != 0 or r["units_declined"] != 0:
            status = "FALLBACK"  # right verdict, but the reference kernel did (part of) the work
            declined_files += 1
        else:
            status = "CLEAN"
            clean += 1
        acc += max(r.get("accepted", 0), 0)
        rej += max(r.get("rejected", 0), 0)
        dec += max(r.get("declined", 0), 0)
        lines.append("%-8s exit %s expected %d  machine accepted %5d rejected %3d declined %5d  units.declined %5d  %s%s" % (
            status, "-" if ex is None else ex, expected, r.get("accepted", 0), r.get("rejected", 0), r.get("declined", -1),
            r.get("units_declined", -1), short, ("  -- " + str(r["error"])) if r["error"] else ""))
    gate = "PASSED" if clean == len(results) else ("WRONG VERDICTS" if wrong else "NOT PASSED (no wrong verdict)")
    head = [
        "# machine-launcher-%s: every test through work/dist/bin/rlvm-jvm --threads 1 -X engine=machine (the gate's own form)" % label,
        "# generated %s by: rlvm-machine/src/test/resources/m8/report.sh launcher %s%s" % (
            datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"), " ".join(a.roots),
            "".join(" --prop " + p for p in a.prop)),
        "# machine jar: %s (the one the last publication installed unless --jar was given)" % a.jar,
        "#",
        "# GATE %s: %s -- files %d: clean %d (right verdict, machine declined nothing), fallback or decline %d, WRONG %d, error %d" % (
            label, gate, len(results), clean, declined_files, wrong, errors),
        "# engine checks by the machine: accepted %d, rejected %d, declined %d (declined = re-checked by the reference kernel)" % (acc, rej, dec),
        "# CLEAN = verdict right and values.engine.primary.machine.declined == 0 and units.declined == 0; LOADER = the loader decided",
        "# the file before any engine ran (counted as clean); FALLBACK = verdict right",
        "# because the driver re-checked what the machine declined; DECLINE = the run declined; WRONG = verdict differs from the",
        "# arena's classification; ERROR = no statistics, a crash or a timeout",
        "#",
    ]
    out = os.path.join(ROOT, "work/status/machine-launcher-%s.txt" % label)
    with open(out, "w") as f:
        f.write("\n".join(head + lines) + "\n")
    print("\n".join(head[:6]))
    print("report: " + out)
    sys.exit(1 if wrong else 0)


if __name__ == "__main__":
    main()
