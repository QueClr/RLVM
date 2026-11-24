#!/usr/bin/env python3
"""rlvm-machine/src/test/resources/m8/big.py - the machine against the reference engine on the LARGE corpora
(cslib, navier-stokes-euler, con-leche, cedar, mathlib), per switch set (owner: M8).

    big.py [--sets 'v0;warm,gated,expect'] [--corpora cslib,nse,conleche,cedar,mathlib] [--threads 4]
           [--dist DIR] [--with-reference] [--label NAME]

Every corpus is one run of the native launcher (the image the scoreboard built: work/m8/ladder/dist-*, or
--dist) with `-X engine=machine [-X machine.switches=.. -X session-mib=4] -X stats-file=..`, 4 workers, inside a
host-wide slot (bench/slot.sh), pinned to the CPUs outside the measurement container. Not a measurement: the
times are for orientation only.

Why this is the differential on these corpora: each of them is ACCEPTED by the reference engine (every unit an
engine checks is accepted; re-established here with --with-reference, else by the pipeline check of wave 5,
work/status/pipeline-wave5.done), admission decides for both engines alike which units reach the engine, and the
driver's statistics say what the machine itself answered. So

    machine accepted == engine checks, rejected == 0, declined == 0, MACHINE-INCOMPLETE == WARM-ANOMALY == 0

is "the machine agrees with the reference engine on every declaration", and any other row is a difference:
a rejection (MACHINE-INCOMPLETE: the reference kernel accepts) or a decline (the reference kernel decided).

Report: work/status/machine-big[-LABEL].md, rows appended to work/status/machine-big.tsv. Exit 1 if any row differs.
"""
import argparse, datetime, glob, json, os, subprocess, sys, tempfile

ROOT = os.path.dirname(os.path.abspath(__file__))
while ROOT != "/" and not (os.path.isfile(os.path.join(ROOT, "env.sh")) and os.path.isfile(os.path.join(ROOT, "rlvm-machine", "OWNERS.tsv"))):
    ROOT = os.path.dirname(ROOT)
CORPORA = {"cslib": "work/exports/cslib.ndjson", "nse": "work/exports/navier-stokes-euler.ndjson", "conleche": "work/exports/con-leche.ndjson",
           "cedar": "work/exports/cedar.ndjson", "mathlib": "work/exports/mathlib.ndjson"}
TSV = os.path.join(ROOT, "work/status/machine-big.tsv")
COLS = ["utc", "image", "engine", "set", "corpus", "threads", "exit", "units", "engine_checks", "accepted", "rejected", "declined",
        "incomplete", "warm_anomalies", "disagreements", "cpu_s", "wall_s", "rss_mib", "result"]


def now():
    return datetime.datetime.now(datetime.timezone.utc)


def other_cpus():
    try:
        out = subprocess.run([os.path.join(ROOT, "bench/arena-docker.sh"), "--arena-limits", "--print", "true"], capture_output=True, text=True).stdout
        import re
        m = re.search(r"--cpuset-cpus ([0-9,]+)", out.replace("\\", ""))
        if not m:
            return None
        arena = set(m.group(1).split(","))
        return ",".join(str(i) for i in range(os.cpu_count()) if str(i) not in arena)
    except Exception:
        return None


def run(dist, export, engine, sset, threads, mib, timeout):
    tmp = tempfile.mkdtemp(prefix="big-", dir=os.path.join(ROOT, "work/m8/tmp"))
    stats = os.path.join(tmp, "stats.json")
    timef = os.path.join(tmp, "time")
    cmd = [os.path.join(dist, "bin/rlvm"), "--threads", str(threads), export, "-X", "engine=" + engine, "-X", "stats-file=" + stats]
    if engine == "machine":
        # always explicit: without the setting the engine runs its default set (warm,gated,expect since 16:24Z)
        cmd += ["-X", "machine.switches=" + (sset or "none")]
        if "warm" in sset.split(","):
            cmd += ["-X", "session-mib=%d" % mib]
    full = ["/usr/bin/time", "-f", "%e %U %S %M", "-o", timef] + cmd
    cpus = other_cpus()
    if cpus:
        full = ["taskset", "-c", cpus] + full
    slot = os.path.join(ROOT, "bench/slot.sh")
    if os.access(slot, os.X_OK):
        full = [slot, "--"] + full
    try:
        p = subprocess.run(full, cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=timeout)
        code = p.returncode
    except subprocess.TimeoutExpired:
        code = -1
    r = dict(exit=code, units="-", engine_checks="-", accepted="-", rejected="-", declined="-", incomplete="-", warm_anomalies="-", disagreements="-",
             cpu_s="-", wall_s="-", rss_mib="-")
    try:
        e, u, s, m = open(timef).read().split()[-4:]
        r.update(cpu_s="%.1f" % (float(u) + float(s)), wall_s="%.1f" % float(e), rss_mib="%.0f" % (float(m) / 1024.0))
    except Exception:
        pass
    try:
        j = json.load(open(stats))
        v = j.get("values", {})
        r["units"] = v.get("driver.units", "-")
        r["engine_checks"] = j.get("units", {}).get("count", "-")
        if engine == "machine":
            p = "engine.primary.machine."
            r.update(accepted=v.get(p + "accepted", "-"), rejected=v.get(p + "rejected", "-"), declined=v.get(p + "declined", "-"))
        else:
            u = j.get("units", {})
            r.update(accepted=u.get("accepted", "-"), rejected=u.get("rejected", "-"), declined=u.get("declined", "-"))
        r.update(incomplete=v.get("driver.machineIncomplete", "-"), warm_anomalies=v.get("driver.warmAnomalies", "-"),
                 disagreements=v.get("driver.disagreements", "-"))
    except Exception:
        pass
    subprocess.run(["rm", "-rf", tmp])
    return r


def verdict(r):
    if r["exit"] != 0:
        return "WRONG VERDICT (exit %s)" % r["exit"]
    try:
        if int(r["rejected"]) or int(r["incomplete"]) or int(r["warm_anomalies"]) or int(r["disagreements"]):
            return "DIFFERENT"
        if int(r["declined"]):
            return "DECLINES"
        if int(r["accepted"]) != int(r["engine_checks"]):
            return "DIFFERENT (accepted != engine checks)"
    except (TypeError, ValueError):
        return "NO STATISTICS"
    return "agree"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--sets", default="v0;warm,gated,expect")
    ap.add_argument("--corpora", default="conleche,cedar,cslib,nse,mathlib")
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--session-mib", type=int, default=4)
    ap.add_argument("--dist", default="")
    ap.add_argument("--with-reference", action="store_true")
    ap.add_argument("--timeout", type=int, default=7200)
    ap.add_argument("--label", default="")
    a = ap.parse_args()
    dist = os.path.join(ROOT, a.dist) if a.dist else None
    if dist is None:
        cands = [d for d in glob.glob(os.path.join(ROOT, "work/m8/ladder/dist-*")) if os.access(os.path.join(d, "bin/rlvm"), os.X_OK)]
        if not cands:
            sys.exit("big.py: no native image (run ladder.py measure first, or give --dist)")
        dist = max(cands, key=os.path.getmtime)
    image = os.path.relpath(dist, ROOT)
    info = ""
    try:
        for l in open(os.path.join(dist, "BUILD-INFO.txt")):
            if "rlvm-machine" in l:
                info = " ".join(l.split())
    except OSError:
        pass
    os.makedirs(os.path.join(ROOT, "work/m8/tmp"), exist_ok=True)
    sets = ["" if s.strip() in ("v0", "generic") else s.strip() for s in a.sets.split(";")]
    rows = []
    for c in [x for x in a.corpora.split(",") if x]:
        export = os.path.join(ROOT, CORPORA.get(c, c))
        if not os.path.isfile(export):
            print("big: no corpus %s" % export, flush=True)
            continue
        plan = ([("reference", "-")] if a.with_reference else []) + [("machine", s) for s in sets]
        for engine, s in plan:
            r = run(dist, export, engine, s if engine == "machine" else "", a.threads, a.session_mib, a.timeout)
            r.update(utc=now().strftime("%Y-%m-%dT%H:%MZ"), image=image, engine=engine, set=(s or "v0") if engine == "machine" else "-", corpus=c, threads=a.threads)
            r["result"] = verdict(r) if engine == "machine" else ("accepts every unit" if r["exit"] == 0 and str(r["declined"]) == "0" and str(r["rejected"]) == "0" else "NOT ALL ACCEPTED")
            rows.append(r)
            new = not os.path.isfile(TSV)
            with open(TSV, "a") as f:
                if new:
                    f.write("\t".join(COLS) + "\n")
                f.write("\t".join(str(r.get(k, "")) for k in COLS) + "\n")
            print("big: %-9s %-26s %-9s exit %s  units %s  engine checks %s  accepted %s rejected %s declined %s  incomplete %s  %s s CPU / %s s wall / %s MiB  -> %s" % (
                engine, r["set"], c, r["exit"], r["units"], r["engine_checks"], r["accepted"], r["rejected"], r["declined"], r["incomplete"],
                r["cpu_s"], r["wall_s"], r["rss_mib"], r["result"]), flush=True)
    bad = [r for r in rows if r["engine"] == "machine" and r["result"] != "agree"]
    out = []
    out.append("# machine-big: the machine against the reference engine on the large corpora")
    out.append("")
    out.append("**RESULT: %s**" % ("AGREE on every declaration of every run" if rows and not bad else "%d of %d machine runs differ or decline" % (len(bad), len([r for r in rows if r["engine"] == "machine"]))))
    out.append("")
    out.append("- generated %s by `rlvm-machine/src/test/resources/m8/big.py %s`" % (now().strftime("%Y-%m-%dT%H:%MZ"), " ".join(sys.argv[1:])))
    out.append("- image: `%s` (%s), `--threads %d`, one host-wide slot per run, CPUs outside the measurement container" % (image, info, a.threads))
    out.append("- reading: each corpus is accepted by the reference engine with every engine-checked unit accepted (%s); admission sends the same units to" % (
        "re-established in this run, rows `reference`" if a.with_reference else "pipeline check of wave 5, `work/status/pipeline-wave5.done`; M6's rows of 13:30Z"))
    out.append("  either engine. A machine row `agree` = machine accepted == engine checks, rejected 0, declined 0, MACHINE-INCOMPLETE 0, WARM-ANOMALY 0, disagreements 0,")
    out.append("  i.e. the same outcome as the reference engine on every declaration. Times are for orientation (a shared host, no measurement lock).")
    out.append("")
    out.append("| corpus | engine, switch set | exit | units | engine checks | accepted | rejected | declined | MACHINE-INCOMPLETE | WARM-ANOMALY | disagreements | CPU s | wall s | RSS MiB | result |")
    out.append("|---|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|---|")
    for r in rows:
        out.append("| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |" % (
            r["corpus"], "reference" if r["engine"] == "reference" else "machine `%s`" % r["set"], r["exit"], r["units"], r["engine_checks"], r["accepted"],
            r["rejected"], r["declined"], r["incomplete"], r["warm_anomalies"], r["disagreements"], r["cpu_s"], r["wall_s"], r["rss_mib"],
            r["result"] if r["result"] in ("agree", "accepts every unit") else "**%s**" % r["result"]))
    md = os.path.join(ROOT, "work/status/machine-big%s.md" % (("-" + a.label) if a.label else ""))
    with open(md, "w") as f:
        f.write("\n".join(out) + "\n")
    print("big: wrote %s" % os.path.relpath(md, ROOT))
    sys.exit(1 if bad or not rows else 0)


if __name__ == "__main__":
    main()
