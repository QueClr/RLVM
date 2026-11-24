#!/usr/bin/env python3
"""work/soundness/tools/judge.py - judge exports with the official checker, the reference engine and the
machine under every switch set.  Usage: judge.py [--sets a;b;c] [--quick] [--dist DIR] [-v] FILE...
One line per file: official verdict, reference exit, then per switch set the process exit and the
machine's own accepted/rejected/declined counts.  A line is flagged
  WRONG-ACCEPT   the process accepts (exit 0) what the official checker rejects
  WRONG-REJECT   the process rejects what the official checker accepts
  M-ACCEPT-BAD   the machine's units were all accepted but official rejects (same as WRONG-ACCEPT, engine level)
  M-INCOMPLETE   the machine rejected a unit that the reference accepts (driver.machineIncomplete)
  M-DECLINE      the machine declined a unit
"""
import json, os, subprocess, sys, tempfile, hashlib

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
DIST = os.path.join(ROOT, 'work/soundness/dist')
SETS_FULL = ['', 'gated', 'expect', 'warm', 'facts', 'tele', 'code', 'casetrees', 'probes', 'inert',
             'warm,gated,expect', 'warm,gated,expect,probes', 'facts,tele,code,casetrees@1', 'all', 'all@1', 'all@1!d0',
             'warm,gated,expect,probes#t4']
SETS_QUICK = ['', 'warm,gated,expect,probes', 'all@1']
SETS_MID = ['', 'warm,gated,expect', 'warm,gated,expect,probes', 'facts', 'tele@1', 'code@1', 'code,casetrees@1', 'inert', 'all@1']

def official(path):
    r = subprocess.run([os.path.join(ROOT, 'rlvm-testkit/bin/testkit'), 'verdict', path],
                       capture_output=True, text=True)
    out = r.stdout.strip().split()
    return out[0] if out else 'ERR:' + r.stderr.strip()[:80]

def run(path, switches=None, engine='machine', extra=(), dist=None, timeout=300):
    dist = dist or DIST
    fd, sf = tempfile.mkstemp(suffix='.json', prefix='snd-'); os.close(fd)
    cmd = [os.path.join(dist, 'bin/rlvm-jvm'), '-Xmx2g', '-XX:TieredStopAtLevel=1', '-X', 'stats-file=' + sf]
    if engine != 'reference':
        cmd += ['-X', 'engine=' + engine]
        # suffixes of a switch set: @N = elaboration threshold N, !d0 = host depth 0, #tN = N worker threads
        if switches and '#t' in switches:
            switches, th = switches.split('#t'); cmd += ['--threads', th]
        if switches and '!d0' in switches:
            switches = switches.replace('!d0', ''); cmd += ['-X', 'machine.hostDepth=0']
        if switches and '@' in switches:
            switches, at = switches.split('@'); cmd.insert(1, '-Drlvm.machine.elabAt=' + at)
        if switches: cmd += ['-X', 'machine.switches=' + switches]
    cmd += list(extra) + [path]
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        code = r.returncode
    except subprocess.TimeoutExpired:
        code = 'TIMEOUT'; r = None
    st = {}
    try:
        st = json.load(open(sf))
    except Exception:
        pass
    finally:
        try: os.unlink(sf)
        except OSError: pass
    v = st.get('values', {})
    flat = {}
    def walk(x, pre):
        if isinstance(x, dict):
            for k, y in x.items(): walk(y, pre + [k])
        else:
            flat['.'.join(pre)] = x
    walk(v, [])
    def g(*ks):
        return flat.get('.'.join(ks))
    pm = 'engine.primary.machine.'
    m = {k[len(pm):]: x for k, x in flat.items() if k.startswith(pm)}
    res = dict(code=code, acc=m.get('accepted'), rej=m.get('rejected'), dec=m.get('declined'),
               incomplete=g('driver', 'machineIncomplete'), anomalies=g('driver', 'warmAnomalies'),
               verdict=g('driver', 'verdict'), ticks=m.get('ticks'),
               first=[m.get('firstFailures.%d' % i) for i in range(4) if m.get('firstFailures.%d' % i)],
               declinedBy={k[11:]: x for k, x in m.items() if k.startswith('declinedBy.')}, rejectedBy={k[11:]: x for k, x in m.items() if k.startswith('rejectedBy.')},
               stderr=(r.stderr[-400:] if r is not None else ''), stdout=(r.stdout[-300:] if r is not None else ''))
    return res

def main():
    args = sys.argv[1:]
    sets = SETS_FULL; verbose = False; dist = None; extra = []; manifest = None; jobs = 4; compact = False; only = []; noref = False
    files = []
    i = 0
    while i < len(args):
        a = args[i]
        if a == '--sets': sets = args[i + 1].split(';'); i += 1
        elif a == '--quick': sets = SETS_QUICK
        elif a == '--mid': sets = SETS_MID
        elif a == '--dist': dist = args[i + 1]; i += 1
        elif a == '-X': extra += ['-X', args[i + 1]]; i += 1
        elif a == '-v': verbose = True
        elif a == '--manifest': manifest = args[i + 1]; i += 1
        elif a == '-j': jobs = int(args[i + 1]); i += 1
        elif a == '--compact': compact = True
        elif a == '--no-ref': noref = True
        elif a == '--only': only.append(args[i + 1]); i += 1
        else: files.append(a)
        i += 1
    from concurrent.futures import ThreadPoolExecutor
    expected = {}
    if manifest:
        d = os.path.dirname(os.path.abspath(manifest))
        for line in open(manifest):
            name, exp, attack = line.rstrip('\n').split('\t')
            if only and not any(o in name for o in only): continue
            p = os.path.join(d, name + '.ndjson')
            files.append(p); expected[p] = exp

    def one(f):
        off = official(f)
        ref = run(f, engine='reference', dist=dist) if not noref else {'code': {'accept': 0, 'reject': 1}.get(off, 2)}
        flags = set()
        cells = []
        detail = []
        refv = {0: 'accept', 1: 'reject', 2: 'decline'}.get(ref['code'], str(ref['code']))
        if off in ('accept', 'reject') and refv != off: flags.add('REF-DIFFERS(' + refv + ')')
        if f in expected and expected[f] != '?' and expected[f] != off: flags.add('EXPECTED-' + expected[f].upper() + '-BUT-OFFICIAL-' + off.upper())
        for s in sets:
            r = run(f, s, dist=dist, extra=extra)
            c = r['code']
            cell = '%s:%s[%s/%s/%s]' % (s or 'v0', {0: 'A', 1: 'R', 2: 'D'}.get(c, str(c)), r['acc'], r['rej'], r['dec'])
            if off == 'reject' and c == 0: flags.add('WRONG-ACCEPT(' + (s or 'v0') + ')')
            if off == 'accept' and c == 1: flags.add('WRONG-REJECT(' + (s or 'v0') + ')')
            if r['incomplete']: flags.add('M-INCOMPLETE(' + (s or 'v0') + ')')
            if r['anomalies']: flags.add('WARM-ANOMALY(' + (s or 'v0') + ')')
            if r['dec']: flags.add('M-DECLINE(' + (s or 'v0') + ')')
            if c not in (0, 1, 2): flags.add('CRASH(' + (s or 'v0') + ':' + str(c) + ')')
            cells.append(cell)
            if verbose:
                detail.append('    %s declinedBy %s rejectedBy %s first %s ticks %s' % (cell, r['declinedBy'], r['rejectedBy'], r['first'], r['ticks']))
                if c not in (0, 1): detail.append('    stderr: ' + r['stderr'].strip()[-300:])
        short = ' '.join(cells)
        if compact and not flags:
            vs = set(c.split(':')[-1][0] for c in cells)
            short = 'all %d sets: %s' % (len(cells), ','.join(sorted(vs)))
        line = '%-44s official=%s ref=%s | %s%s' % (os.path.basename(f), off, refv, short,
              ('  <<< ' + ' '.join(sorted(flags))) if flags else '')
        return line, detail, bool(flags)

    bad = 0
    with ThreadPoolExecutor(max_workers=jobs) as ex:
        for line, detail, flagged in ex.map(one, files):
            for dl in detail: print(dl)
            print(line)
            sys.stdout.flush()
            if flagged: bad += 1
    print('judged %d exports, %d flagged' % (len(files), bad))
    return 1 if bad else 0

if __name__ == '__main__':
    sys.exit(main())
