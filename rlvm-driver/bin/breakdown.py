#!/usr/bin/env python3
"""rlvm-driver/bin/breakdown.py STATS.json [...] - where the wall clock of a run went, from the driver's statistics
(the time accounting P7 added: driver.workerTimes, driver.env.*, driver.time.*, driver.series.*).

  --series   also print the progress over time (frontiers, workers running) in one-second steps
  --tsv      one machine-readable summary line per file
"""
import json, sys


def s(n):
    return "%7.2f" % (n / 1e9) if n is not None and n >= 0 else "    n/a"


def table(j, key):
    t = j.get("tables", {}).get(key)
    if not t:
        return None, []
    return t["columns"], [(r.get("label"), r["values"]) for r in t["rows"]]


def summary(path, series=False, tsv=False):
    j = json.load(open(path))
    v = j.get("values", {})
    ph = {k: x["wallNanos"] for k, x in j.get("phases", {}).items()}
    th = j.get("threads", {})
    W = v.get("driver.workers", 0)
    total, load, check = ph.get("total", -1), ph.get("load", -1), ph.get("check", -1)
    t = lambda k: v.get("driver.time." + k, -1)
    cols, rows = table(j, "driver.workerTimes")
    out = []
    out.append("== %s" % path)
    out.append("workers %s  pipelined %s  verdict %s  units %s  session cap %s" % (
        W, v.get("driver.pipelined"), v.get("driver.verdict"), v.get("driver.units"), v.get("driver.sessionBytes")))
    out.append("phases (wall s): total %s  load %s  check %s" % (s(total), s(load), s(check)))
    out.append("time line (s since start): load %s..%s  first publication %s  check %s..%s  env thread %s..%s (frontier complete)" % (
        s(t("loadStartNanos")), s(t("loadEndNanos")), s(t("firstPublicationNanos")), s(t("checkStartNanos")),
        s(t("checkEndNanos")), s(t("envStartNanos")), s(t("envClosedNanos"))))
    res = {"file": path, "workers": W, "total": total, "load": load, "check": check}
    if rows:
        ix = {c: i for i, c in enumerate(cols)}
        g = lambda r, c: r[ix[c]] if c in ix else 0
        out.append("")
        out.append("per worker (s)    wall     cpu    runq  blocked | parked: frontier(env) frontier(loader) tail |   gate loadEnd | other-blocked |  in-units  claim()  open  resets | first-claim last-unit-end")
        tot = dict(wall=0, cpu=0, runq=0, blocked=0, env=0, loader=0, tail=0, gate=0, lend=0, other=0, unit=0, claim=0)
        last_end = 0
        for label, r in rows:
            wall, cpu, runq = g(r, "wallNanos"), g(r, "cpuNanos"), g(r, "runQueueNanos")
            blocked = wall - cpu - runq
            env, loader, tail = g(r, "waitEnvNanos"), g(r, "waitLoaderNanos"), g(r, "waitTailNanos")
            gate, lend = g(r, "gateWaitNanos"), g(r, "loadEndWaitNanos")
            other = blocked - env - loader - tail - gate - lend
            out.append("%-13s %s %s %s %s |        %s          %s %s | %s %s |       %s | %s %s %s %s |      %s %s" % (
                label, s(wall), s(cpu), s(runq), s(blocked), s(env), s(loader), s(tail), s(gate), s(lend), s(other),
                s(g(r, "unitNanos")), s(g(r, "claimNanos")), s(g(r, "openNanos")), s(g(r, "resetNanos")),
                s(g(r, "firstClaimAtNanos")), s(g(r, "lastUnitEndAtNanos"))))
            for k, x in (("wall", wall), ("cpu", cpu), ("runq", runq), ("blocked", blocked), ("env", env), ("loader", loader),
                         ("tail", tail), ("gate", gate), ("lend", lend), ("other", other), ("unit", g(r, "unitNanos")),
                         ("claim", g(r, "claimNanos"))):
                tot[k] += x
            last_end = max(last_end, g(r, "lastUnitEndAtNanos"))
        n = len(rows)
        out.append("%-13s %s %s %s %s |        %s          %s %s | %s %s |       %s | %s %s" % (
            "mean", s(tot["wall"] / n), s(tot["cpu"] / n), s(tot["runq"] / n), s(tot["blocked"] / n), s(tot["env"] / n),
            s(tot["loader"] / n), s(tot["tail"] / n), s(tot["gate"] / n), s(tot["lend"] / n), s(tot["other"] / n),
            s(tot["unit"] / n), s(tot["claim"] / n)))
        if check > 0:
            cap = n * check
            out.append("share of workers x check wall (%d x %.2f s = %.1f s): on CPU %.1f %%, run queue %.1f %%, parked frontier(env) %.1f %%, "
                       "frontier(loader) %.1f %%, tail %.1f %%, gate+loadEnd %.1f %%, other blocked (collections, locks, faults) %.1f %%, "
                       "before start / after end %.1f %%" % (
                           n, check / 1e9, cap / 1e9, 100 * tot["cpu"] / cap, 100 * tot["runq"] / cap, 100 * tot["env"] / cap,
                           100 * tot["loader"] / cap, 100 * tot["tail"] / cap, 100 * (tot["gate"] + tot["lend"]) / cap,
                           100 * tot["other"] / cap, 100 * (cap - tot["wall"]) / cap))
        res.update(worker_cpu=tot["cpu"], worker_runq=tot["runq"], park_env=tot["env"], park_loader=tot["loader"],
                   park_tail=tot["tail"], other_blocked=tot["other"], unit=tot["unit"])
        closed = t("envClosedNanos")
        out.append("")
        out.append("tail: frontier complete at %s s, last unit ended at %s s, check ended at %s s  ->  last-published to last-checked %s s" % (
            s(closed), s(last_end), s(t("checkEndNanos")), s(last_end - closed) if closed >= 0 else "n/a"))
    e = th.get("rlvm-env")
    if e:
        out.append("")
        out.append("environment thread (s): wall %s  cpu %s  runq %s | advancing the pass %s (%s steps)  announcing %s  waiting for the loader %s  "
                   "for the end of the load %s  open %s" % (
                       s(v.get("driver.env.wallNanos", -1)), s(e["cpuNanos"]), s(e["runQueueNanos"]), s(v.get("driver.env.advanceNanos", -1)),
                       v.get("driver.env.advances"), s(v.get("driver.env.announceNanos", -1)), s(v.get("driver.env.waitLoaderNanos", -1)),
                       s(v.get("driver.env.waitLoadEndNanos", -1)), s(v.get("driver.env.openNanos", -1))))
        c2, r2 = table(j, "driver.env.slowest")
        if r2:
            sl = sorted((x[1] for x in r2), reverse=True)[:6]
            out.append("  slowest steps of the pass: " + ", ".join("%.0f ms at unit %d (%d units)" % (x[0] / 1e6, x[1], x[2]) for x in sl))
        res.update(env_cpu=e["cpuNanos"], env_advance=v.get("driver.env.advanceNanos", -1))
    u = j.get("units", {})
    if u.get("count"):
        out.append("")
        out.append("units: %d checked, %.1f s inside units, %d steps" % (u["count"], u["nanos"] / 1e9, u["steps"]))
        if u.get("slow"):
            out.append("  slowest: " + ", ".join("%d: %.0f ms (w%d)" % (x["unit"], x["nanos"] / 1e6, x["worker"]) for x in u["slow"][:8]))
        if u.get("histogram"):
            tn = sum(h["nanos"] for h in u["histogram"]) or 1
            out.append("  time by unit duration: " + ", ".join("<%s: %d units %.1f %%" % (
                ("%d us" % h["belowMicros"]) if h["belowMicros"] < 1000 else ("%d ms" % (h["belowMicros"] // 1000)) if h["belowMicros"] < 1000000
                else ("%d s" % (h["belowMicros"] // 1000000)), h["units"], 100.0 * h["nanos"] / tn) for h in u["histogram"]))
    sch = {k[len("driver.scheduler."):]: x for k, x in v.items() if k.startswith("driver.scheduler.")}
    if sch:
        out.append("scheduler: " + ", ".join("%s %s" % kv for kv in sorted(sch.items())))
    if series:
        out.append("")
        out.extend(series_lines(j, W))
    if tsv:
        return "\t".join("%s=%s" % (k, ("%.3f" % (x / 1e9)) if isinstance(x, (int, float)) and k not in ("workers",) else x) for k, x in res.items())
    return "\n".join(out)


def series_lines(j, W):
    """One line per second: loaded / environment frontier / units accepted, and the fraction of time the workers spent in units."""
    out = []
    lc, lr = table(j, "driver.series.loader")
    ec, er = table(j, "driver.series.env")
    ws = [table(j, "driver.series.worker-%d" % i)[1] for i in range(W)]
    end = 0
    for rows in ws + [er, lr]:
        for _, r in rows:
            end = max(end, r[0])

    def at(rows, tt, col):
        best = 0
        for _, r in rows:
            if r[0] <= tt:
                best = r[col]
            else:
                break
        return best
    out.append("   t(s)   loaded  env-frontier  accepted  accepted/s  workers-in-units(of %d)" % W)
    step = 1_000_000_000
    prev_acc, prev_unit = 0, 0
    tt = step
    while tt <= end + step:
        acc = sum(at(r, tt, 1) for r in ws)
        unit = sum(at(r, tt, 2) for r in ws)
        out.append("%7.1f %8d %13d %9d %11d %10.2f" % (tt / 1e9, at(lr, tt, 1) if lr else -1, at(er, tt, 1) if er else -1, acc,
                                                     acc - prev_acc, (unit - prev_unit) / step))
        prev_acc, prev_unit = acc, unit
        tt += step
    return out


if __name__ == "__main__":
    args = [x for x in sys.argv[1:] if not x.startswith("--")]
    for p in args:
        print(summary(p, "--series" in sys.argv, "--tsv" in sys.argv))
