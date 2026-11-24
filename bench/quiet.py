#!/usr/bin/env python3
"""bench/quiet.py [options] -- CMD [ARGS...]

Run CMD as a TIMING measurement on quiet cores.

  * One measurement at a time on this host (lock work/perf-history/.quiet.lock),
    first come, first served (tickets in work/perf-history/quiet.queue/).
  * While CMD runs, every other process of this session (all processes of the
    session's cgroup: agents' shells, builds, tests, the orchestrator) and every
    other container is kept OFF the measurement cores; their old affinity is
    restored when CMD ends. Nothing is stopped or paused: the rest of the team
    keeps working on the remaining cores.
  * CMD is started with its affinity set to the measurement cores. A container
    that CMD starts must go through `bench/arena-docker.sh --arena-limits` (it
    pins the container to the same 8 performance cores and labels it as the
    measurement's own; a raw `docker run` needs --cpuset-cpus "$RLVM_QUIET_CPUS"
    and --label "rlvm-quiet=$RLVM_QUIET_NAME", else it is parked like the others).
  * Containers of other agents that are pinned on the measurement cores are
    parked on the spare performance cores (18,19 here; same PMU, so their
    instruction counts stay valid) for the window and put back afterwards.

Options:
  --cpus LIST     measurement cores (default: the 8 performance cores that
                  bench/arena-docker.sh --arena-limits uses)
  --wait SECONDS  accepted and ignored (older callers)
  --restore       undo a herd that a killed quiet.py left behind, then exit
  --status        print the measurement cores, the other cores, who holds the lock

Wall-clock numbers of a run that did not go through this wrapper are not to be
reported while the team is working: the host is oversubscribed several times.
Memory bandwidth and the shared cache are still shared with the other cores.
"""
import fcntl
import json
import os
import signal
import subprocess
import sys
import threading
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HIST = os.environ.get("RLVM_QUIET_DIR") or os.path.join(ROOT, "work", "perf-history")
LOCK = os.path.join(HIST, ".quiet.lock")
STATE = os.path.join(HIST, "quiet.state.json")
LOG = os.path.join(HIST, "quiet.log")


def parse_list(s):
    out = set()
    for part in s.split(","):
        part = part.strip()
        if not part:
            continue
        if "-" in part:
            a, b = part.split("-", 1)
            out.update(range(int(a), int(b) + 1))
        else:
            out.add(int(part))
    return out


def fmt_list(cpus):
    return ",".join(str(c) for c in sorted(cpus))


def online():
    with open("/sys/devices/system/cpu/online") as f:
        return parse_list(f.read().strip())


def big_cpus(n):
    """The first n CPUs of the fastest core type (same rule as arena-docker.sh)."""
    freqs = {}
    for c in online():
        try:
            with open(f"/sys/devices/system/cpu/cpu{c}/cpufreq/cpuinfo_max_freq") as f:
                freqs[c] = int(f.read())
        except OSError:
            pass
    if not freqs:
        return set(sorted(online())[:n])
    best = max(freqs.values())
    return set(sorted(c for c, f in freqs.items() if f == best)[:n])


def session_pids():
    with open("/proc/self/cgroup") as f:
        path = f.read().strip().split(":", 2)[2]
    try:
        with open(f"/sys/fs/cgroup{path}/cgroup.procs") as f:
            return [int(x) for x in f.read().split()]
    except OSError:
        return []


def ppid_of(pid):
    try:
        with open(f"/proc/{pid}/stat") as f:
            s = f.read()
        return int(s[s.rindex(")") + 2:].split()[1])
    except (OSError, ValueError, IndexError):
        return 0


def is_mine(pid, me, cache):
    """True when pid is this wrapper or one of its descendants."""
    chain = []
    p = pid
    while p > 1 and p not in cache:
        if p == me:
            break
        chain.append(p)
        p = ppid_of(p)
    res = True if p == me else cache.get(p, False)
    for c in chain:
        cache[c] = res
    return res


def tids_of(pid):
    try:
        return [int(t) for t in os.listdir(f"/proc/{pid}/task")]
    except OSError:
        return []


def docker(*args):
    try:
        r = subprocess.run(("docker",) + args, capture_output=True, text=True, timeout=30)
        return r.stdout if r.returncode == 0 else ""
    except (OSError, subprocess.SubprocessError):
        return ""


def containers():
    """[(id, name, cpuset as a set or None)] of the running containers."""
    ids = docker("ps", "-q").split()
    out = []
    if not ids:
        return out
    text = docker("inspect", "-f",
                  '{{.Id}}|{{.Name}}|{{index .Config.Labels "rlvm-quiet"}}|{{.HostConfig.CpusetCpus}}', *ids)
    for line in text.splitlines():
        parts = line.split("|")
        if len(parts) != 4:
            continue
        cid, name, label, cs = (x.strip() for x in parts)
        if label.startswith("<"):   # "<no value>": the container has no such label
            label = ""
        try:
            cpus = parse_list(cs) if cs else None
        except ValueError:
            continue
        out.append((cid, name.lstrip("/"), cpus, label))
    return out


class Herd:
    def __init__(self, measure):
        self.measure = set(measure)
        self.all = online()
        self.others = self.all - self.measure
        self.spare = big_cpus(len(self.all)) - self.measure   # fast cores outside the measurement set
        self.name = f"quiet{os.getpid()}"
        self.me = os.getpid()
        self.tid_rec = {}    # tid -> old mask (sorted list)
        self.pid_rec = {}    # pid -> old mask of its main thread
        self.cont_rec = {}   # container id -> old cpuset string ("" = none)
        self.contended = set()
        self.lock = threading.Lock()

    def save(self):
        tmp = STATE + ".tmp"
        with open(tmp, "w") as f:
            json.dump({"measure": sorted(self.measure), "tids": self.tid_rec, "pids": self.pid_rec,
                       "containers": self.cont_rec, "wrapper": self.me}, f)
        os.replace(tmp, STATE)

    def herd(self):
        with self.lock:
            cache = {}
            for pid in session_pids():
                if is_mine(pid, self.me, cache):
                    continue
                for tid in tids_of(pid):
                    try:
                        cur = os.sched_getaffinity(tid)
                    except OSError:
                        continue
                    new = cur - self.measure
                    if not new or new == cur:
                        continue
                    try:
                        os.sched_setaffinity(tid, new)
                    except OSError:
                        continue
                    self.tid_rec.setdefault(str(tid), sorted(cur))
                    if tid == pid:
                        self.pid_rec.setdefault(str(pid), sorted(cur))
            for cid, name, cs, label in containers():
                if cid in self.cont_rec or label == self.name:
                    continue   # already moved, or the measurement's own container
                cur = cs if cs is not None else self.all
                if not (cur & self.measure):
                    continue
                new = cur - self.measure
                if not new:
                    # pinned inside the measurement cores (an --arena-limits run that did not go through
                    # this wrapper): park it on the spare cores of the same core type, which share the
                    # PMU, so that its instruction counts stay valid; its timings are not evidence anyway
                    new = self.spare if self.spare else self.others
                    self.contended.add(name)
                if docker("update", "--cpuset-cpus", fmt_list(new), cid):
                    self.cont_rec[cid] = fmt_list(cs) if cs is not None else ""
            self.save()

    def restore(self):
        with self.lock:
            for tid, mask in self.tid_rec.items():
                try:
                    os.sched_setaffinity(int(tid), set(mask))
                except OSError:
                    pass
            # threads born during the window inherited a herded mask
            for pid in session_pids():
                for tid in tids_of(pid):
                    if str(tid) in self.tid_rec:
                        continue
                    try:
                        cur = os.sched_getaffinity(tid)
                    except OSError:
                        continue
                    want = None
                    p = pid
                    for _ in range(8):
                        rec = self.pid_rec.get(str(p))
                        if rec is not None:
                            if cur == set(rec) - self.measure:
                                want = set(rec)
                            break
                        p = ppid_of(p)
                        if p <= 1:
                            break
                    if want is None and cur == self.others:
                        want = self.all
                    if want and want != cur:
                        try:
                            os.sched_setaffinity(tid, want)
                        except OSError:
                            pass
            for cid, old in self.cont_rec.items():
                docker("update", "--cpuset-cpus", old if old else fmt_list(self.all), cid)
            try:
                os.remove(STATE)
            except OSError:
                pass


def restore_leftover():
    try:
        with open(STATE) as f:
            st = json.load(f)
    except (OSError, ValueError):
        st = None
    h = Herd(set(st["measure"]) if st else big_cpus(8))
    if st:
        w = st.get("wrapper", 0)
        if w and os.path.exists(f"/proc/{w}"):
            print(f"quiet.py: a measurement is running (pid {w}); nothing restored", file=sys.stderr)
            return 1
        h.tid_rec, h.pid_rec, h.cont_rec = st.get("tids", {}), st.get("pids", {}), st.get("containers", {})
    h.restore()
    print("quiet.py: restored", file=sys.stderr)
    return 0


def log(line):
    try:
        with open(LOG, "a") as f:
            f.write(time.strftime("%Y-%m-%dT%H:%M:%SZ ", time.gmtime()) + line + "\n")
    except OSError:
        pass


def main(argv):
    cpus = None
    wait = 600.0
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--":
            i += 1
            break
        if a == "--cpus":
            cpus = parse_list(argv[i + 1]); i += 2
        elif a == "--wait":
            wait = float(argv[i + 1]); i += 2
        elif a == "--restore":
            os.makedirs(HIST, exist_ok=True)
            return restore_leftover()
        elif a == "--status":
            m = big_cpus(8)
            print(f"measurement cores: {fmt_list(m)}\nother cores:       {fmt_list(online() - m)}")
            try:
                with open(STATE) as f:
                    st = json.load(f)
                print(f"herd active: wrapper pid {st.get('wrapper')}, {len(st.get('tids', {}))} threads moved")
            except (OSError, ValueError):
                print("herd active: no")
            return 0
        elif a in ("-h", "--help"):
            print(__doc__)
            return 0
        else:
            break
    cmd = argv[i:]
    if not cmd:
        print(__doc__, file=sys.stderr)
        return 64
    measure = cpus if cpus else big_cpus(8)
    os.makedirs(HIST, exist_ok=True)

    # First come, first served: a ticket per waiter (flock alone let late comers overtake, and some
    # windows waited half an hour). Only the oldest live ticket tries the lock.
    qdir = os.path.join(HIST, "quiet.queue")
    os.makedirs(qdir, exist_ok=True)
    ticket = f"{time.time_ns():020d}.{os.getpid()}"
    tpath = os.path.join(qdir, ticket)
    open(tpath, "w").close()
    lockf = open(LOCK, "w")
    t0 = time.time()
    told = 0.0
    try:
        while True:
            live = []
            for e in sorted(os.listdir(qdir)):
                try:
                    pid = int(e.rsplit(".", 1)[1])
                except (ValueError, IndexError):
                    continue
                if os.path.exists(f"/proc/{pid}"):
                    live.append(e)
                else:
                    try:
                        os.remove(os.path.join(qdir, e))
                    except OSError:
                        pass
            if not live or live[0] == ticket:
                try:
                    fcntl.flock(lockf, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    break
                except OSError:
                    pass
            if time.time() - told > 120:
                pos = live.index(ticket) if ticket in live else 0
                print(f"quiet.py: waiting for the measurement in progress ({pos} window(s) ahead in the queue) ...",
                      file=sys.stderr)
                told = time.time()
            time.sleep(1.0)
    finally:
        try:
            os.remove(tpath)
        except OSError:
            pass
    waited_lock = time.time() - t0

    # a herd left behind by a killed wrapper
    if os.path.exists(STATE):
        restore_leftover()

    herd = Herd(measure)
    name = f"quiet{os.getpid()}"
    os.environ["RLVM_QUIET_NAME"] = name
    os.environ["RLVM_QUIET_CPUS"] = fmt_list(measure)

    # Containers of other agents that are pinned on the measurement cores are not waited for: herd()
    # parks them on the spare fast cores for the window (same PMU: their instruction counts stay valid).

    stop = threading.Event()
    child = None

    def keeper():
        while not stop.wait(3.0):
            try:
                herd.herd()
            except Exception:
                pass

    def on_signal(signum, _frame):
        if child is not None and child.poll() is None:
            try:
                os.killpg(child.pid, signum)
            except OSError:
                pass

    rc = 125
    load0 = open("/proc/loadavg").read().split()[0]
    try:
        herd.herd()
        time.sleep(1.0)
        herd.herd()
        th = threading.Thread(target=keeper, daemon=True)
        th.start()
        signal.signal(signal.SIGINT, on_signal)
        signal.signal(signal.SIGTERM, on_signal)
        tstart = time.time()
        child = subprocess.Popen(cmd, preexec_fn=lambda: os.sched_setaffinity(0, measure),
                                 start_new_session=True)
        rc = child.wait()
        dur = time.time() - tstart
    finally:
        stop.set()
        herd.restore()
    cont = sorted(herd.contended)
    log(f"cpus={fmt_list(measure)} rc={rc} wall={dur:.1f}s lock_wait={waited_lock:.0f}s load_before={load0} "
        f"moved_threads={len(herd.tid_rec)} moved_containers={len(herd.cont_rec)} "
        f"parked={','.join(cont) if cont else '-'} cmd={' '.join(cmd)[:300]}")
    if cont:
        print(f"quiet.py: note, containers pinned on the measurement cores were parked on "
              f"{fmt_list(herd.spare if herd.spare else herd.others)} during the run: {' '.join(cont)}", file=sys.stderr)
    return rc if rc >= 0 else 128 - rc


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
