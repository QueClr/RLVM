#!/usr/bin/env python3
"""work/soundness/tools/mutate.py - mutation kinds aimed at the machine's shortcuts (package S).

    mutate.py OUTDIR [--per N] [--seed S] SOURCE.ndjson...

Every mutant edits ONE OCCURRENCE inside ONE declaration (def / thm / axiom / opaque): the nodes on the path
from the declaration's root to the occurrence are copied (new expression ids), so that a sub-term shared with
other declarations keeps its meaning everywhere else.  The existing kinds of rlvm-testkit edit records in place.

Kinds:
  LEVEL_ARG        one level argument of one constant occurrence is replaced (succ of it, zero, or another level)
                   - attacks closure identity / the level substitution id (B1), memo keys without the level list
  SWAP_UNIV_PARAMS two universe parameters of a declaration are exchanged in its levelParams list (uses are positional)
  SIBLING_ARG      the argument of one application is replaced by the argument of another application of the SAME
                   function expression (so it very likely has the right type): attacks argument masks, skipped proof
                   positions, unread-argument skipping, congruence shortcuts
  LIT_SUCC_EQ      a Nat literal n is replaced by Nat.succ (n-1) / Nat.zero: an equal literal built differently
  LIT_SUCC_NE      a Nat literal n is replaced by Nat.succ n: a different number
  BETA_WRAP        a sub-term e becomes (fun (_ : Sort 1) => e) (Sort 0): beta on the way, an unread binder (HOLE)
  LET_WRAP         a sub-term e becomes let _ : Sort 1 := Sort 0; e
The official verdict of every mutant is asked from the official checker (judge.py does that); nothing is assumed.
"""
import json, os, random, sys

def dumps(o):
    return json.dumps(o, sort_keys=True, separators=(',', ':'), ensure_ascii=False)

KINDS = ['LEVEL_ARG', 'SWAP_UNIV_PARAMS', 'SIBLING_ARG', 'LIT_SUCC_EQ', 'LIT_SUCC_NE', 'BETA_WRAP', 'LET_WRAP']


class Model:
    def __init__(self, path):
        self.lines = open(path, encoding='utf-8').read().split('\n')
        if self.lines and self.lines[-1] == '':
            self.lines.pop()
        self.exprs = {}       # id -> (kind, value, line)
        self.levels = {0: ('zero', None, 0)}
        self.names = {0: None}
        self.decls = []       # (line, kind, obj)
        for i, line in enumerate(self.lines):
            if i == 0: continue
            try:
                o = json.loads(line)
            except Exception:
                raise ValueError('unparsable line %d' % i)
            if 'in' in o:
                self.names[o['in']] = ('str', o['str']['pre'], o['str']['str']) if 'str' in o else ('num', o['num']['pre'], o['num']['i'])
            elif 'il' in o:
                k = [x for x in o if x != 'il'][0]
                self.levels[o['il']] = (k, o[k], i)
            elif 'ie' in o:
                k = [x for x in o if x != 'ie'][0]
                self.exprs[o['ie']] = (k, o[k], i)
            else:
                k = list(o.keys())[0]
                self.decls.append((i, k, o[k]))
        self.ne = max(self.exprs) + 1 if self.exprs else 0
        self.nl = max(self.levels) + 1
        self.nn = max(self.names) + 1
        self.new = []         # new record lines (inserted before the mutated declaration)
        self._loose = {}

    def name_id(self, comps):
        """id of a name by structure, or None"""
        cur = 0
        for c in comps:
            found = None
            for nid, n in self.names.items():
                if n and n[1] == cur and n[2] == c and n[0] == ('num' if isinstance(c, int) else 'str'):
                    found = nid; break
            if found is None: return None
            cur = found
        return cur

    def add_expr(self, kind, value):
        i = self.ne; self.ne += 1
        self.exprs[i] = (kind, value, 10 ** 9)
        self.new.append(dumps({'ie': i, kind: value}))
        return i

    def add_level(self, kind, value):
        for lid, l in self.levels.items():
            if l[0] == kind and l[1] == value and l[2] < 10 ** 8 and False:
                return lid
        i = self.nl; self.nl += 1
        self.levels[i] = (kind, value, 10 ** 9)
        self.new.append(dumps({'il': i, kind: value}))
        return i

    def kids(self, e):
        k, v, _ = self.exprs[e]
        if k == 'app': return [('fn', v['fn']), ('arg', v['arg'])]
        if k in ('lam', 'forallE'): return [('type', v['type']), ('body', v['body'])]
        if k == 'letE': return [('type', v['type']), ('value', v['value']), ('body', v['body'])]
        if k == 'proj': return [('struct', v['struct'])]
        if k == 'mdata': return [('expr', v['expr'])]
        return []

    def loose(self, e):
        """1 + the largest loose de Bruijn index of e (0 = closed); iterative, memoised"""
        memo = self._loose
        stack = [e]
        while stack:
            x = stack[-1]
            if x in memo: stack.pop(); continue
            k, v, _ = self.exprs[x]
            ks = self.kids(x)
            todo = [c for _, c in ks if c not in memo]
            if todo: stack.extend(todo); continue
            stack.pop()
            if k == 'bvar': r = v + 1
            elif k == 'app': r = max(memo[v['fn']], memo[v['arg']])
            elif k in ('lam', 'forallE'): r = max(memo[v['type']], memo[v['body']] - 1, 0)
            elif k == 'letE': r = max(memo[v['type']], memo[v['value']], memo[v['body']] - 1, 0)
            elif k == 'proj': r = memo[v['struct']]
            elif k == 'mdata': r = memo[v['expr']]
            else: r = 0
            memo[x] = r
        return memo[e]

    def shift(self, e, cutoff, memo=None):
        """a copy of e with the loose indices >= cutoff raised by one (e itself where nothing is loose)"""
        if memo is None: memo = {}
        if self.loose(e) <= cutoff: return e
        key = (e, cutoff)
        if key in memo: return memo[key]
        k, v, _ = self.exprs[e]
        if k == 'bvar': r = self.add_expr('bvar', v + 1)
        elif k == 'app': r = self.add_expr('app', {'fn': self.shift(v['fn'], cutoff, memo), 'arg': self.shift(v['arg'], cutoff, memo)})
        elif k in ('lam', 'forallE'):
            r = self.add_expr(k, dict(v, type=self.shift(v['type'], cutoff, memo), body=self.shift(v['body'], cutoff + 1, memo)))
        elif k == 'letE':
            r = self.add_expr(k, dict(v, type=self.shift(v['type'], cutoff, memo), value=self.shift(v['value'], cutoff, memo), body=self.shift(v['body'], cutoff + 1, memo)))
        elif k == 'proj': r = self.add_expr(k, dict(v, struct=self.shift(v['struct'], cutoff, memo)))
        elif k == 'mdata': r = self.add_expr(k, dict(v, expr=self.shift(v['expr'], cutoff, memo)))
        else: r = e
        memo[key] = r
        return r

    def paths(self, root, pred, rng, limit=4000):
        """paths (lists of (expr, slot)) from root to occurrences satisfying pred; a bounded random tree walk"""
        found = []
        stack = [(root, [])]
        visited = 0
        while stack and visited < limit:
            e, path = stack.pop(rng.randrange(len(stack)) if len(stack) < 64 else -1)
            visited += 1
            if pred(e):
                found.append(path + [(e, None)])
            if len(path) > 200: continue
            for slot, c in self.kids(e):
                stack.append((c, path + [(e, slot)]))
        return found

    def rebuild(self, path, new_target):
        """copies the nodes of the path with the target replaced; returns the new root id"""
        cur = new_target
        for e, slot in reversed(path[:-1]):
            k, v, _ = self.exprs[e]
            cur = self.add_expr(k, dict(v, **{slot: cur}))
        return cur


def mutate(src, kind, rng):
    """one mutant of the export at path src; returns (lines, description) or None"""
    try:
        M = Model(src)
    except ValueError:
        return None
    cand = [(i, d) for i, d in enumerate(M.decls) if d[1] in ('def', 'thm', 'axiom', 'opaque')]
    if not cand: return None
    rng.shuffle(cand)
    for di, (line, dk, d) in cand[:12]:
        if kind == 'SWAP_UNIV_PARAMS':
            lp = d['levelParams']
            if len(lp) < 2: continue
            a, b = rng.sample(range(len(lp)), 2)
            if lp[a] == lp[b]: continue
            lp2 = list(lp); lp2[a], lp2[b] = lp2[b], lp2[a]
            out = list(M.lines)
            out[line] = dumps({dk: dict(d, levelParams=lp2)})
            return out, 'SWAP_UNIV_PARAMS decl line %d params %d,%d' % (line, a, b)
        fields = ['type'] + (['value'] if 'value' in d else [])
        rng.shuffle(fields)
        for field in fields:
            root = d[field]
            if root not in M.exprs: continue
            if kind == 'LEVEL_ARG':
                pred = lambda e: M.exprs[e][0] == 'const' and len(M.exprs[e][1]['us']) > 0
            elif kind == 'SIBLING_ARG':
                pred = lambda e: M.exprs[e][0] == 'app'
            elif kind in ('LIT_SUCC_EQ', 'LIT_SUCC_NE'):
                pred = lambda e: M.exprs[e][0] == 'natVal'
            else:
                pred = lambda e: True
            ps = M.paths(root, pred, rng)
            if not ps: continue
            rng.shuffle(ps)
            for path in ps[:20]:
                tgt = path[-1][0]
                k, v, _ = M.exprs[tgt]
                desc = None
                if kind == 'LEVEL_ARG':
                    j = rng.randrange(len(v['us']))
                    old = v['us'][j]
                    choice = rng.randrange(3)
                    if choice == 0:
                        newl = M.add_level('succ', old)
                    elif choice == 1 and old != 0:
                        newl = 0
                    else:
                        others = [l for l, x in M.levels.items() if l != old and x[2] < line]
                        if not others: newl = M.add_level('succ', old)
                        else: newl = rng.choice(others)
                    us = list(v['us']); us[j] = newl
                    new = M.add_expr('const', dict(v, us=us))
                    desc = 'level %d of const expr %d: %d -> %d' % (j, tgt, old, newl)
                elif kind == 'SIBLING_ARG':
                    sib = [e for e, x in M.exprs.items() if x[0] == 'app' and x[2] < line and x[1]['fn'] == v['fn'] and x[1]['arg'] != v['arg']]
                    closed = [e for e in sib if M.loose(M.exprs[e][1]['arg']) == 0]
                    pool = closed or sib
                    if not pool: continue
                    other = rng.choice(pool)
                    new = M.add_expr('app', {'fn': v['fn'], 'arg': M.exprs[other][1]['arg']})
                    desc = 'arg of app expr %d: %d -> %d (arg of app %d)' % (tgt, v['arg'], M.exprs[other][1]['arg'], other)
                elif kind in ('LIT_SUCC_EQ', 'LIT_SUCC_NE'):
                    n = int(v)
                    succ_name = M.name_id(['Nat', 'succ']); zero_name = M.name_id(['Nat', 'zero'])
                    if succ_name is None or zero_name is None: return None
                    if kind == 'LIT_SUCC_EQ' and n == 0:
                        new = M.add_expr('const', {'name': zero_name, 'us': []})
                    else:
                        inner = M.add_expr('natVal', str(n - 1 if kind == 'LIT_SUCC_EQ' else n))
                        sc = M.add_expr('const', {'name': succ_name, 'us': []})
                        new = M.add_expr('app', {'fn': sc, 'arg': inner})
                    desc = 'literal expr %d (%d) rebuilt' % (tgt, n)
                elif kind in ('BETA_WRAP', 'LET_WRAP'):
                    l1 = M.add_level('succ', 0)
                    s1 = M.add_expr('sort', l1); s0 = M.add_expr('sort', 0)
                    body = M.shift(tgt, 0)
                    if kind == 'BETA_WRAP':
                        lam = M.add_expr('lam', {'binderInfo': 'default', 'body': body, 'name': 0, 'type': s1})
                        new = M.add_expr('app', {'fn': lam, 'arg': s0})
                    else:
                        new = M.add_expr('letE', {'body': body, 'name': 0, 'nondep': False, 'type': s1, 'value': s0})
                    desc = 'expr %d wrapped' % tgt
                if desc is None: continue
                newroot = M.rebuild(path, new)
                out = list(M.lines)
                out[line] = dumps({dk: dict(d, **{field: newroot})})
                out[line:line] = M.new
                return out, '%s decl line %d field %s depth %d: %s' % (kind, line, field, len(path) - 1, desc)
    return None


def main():
    args = sys.argv[1:]
    outdir = args[0]; per = 2; seed = 1; srcs = []
    i = 1
    while i < len(args):
        if args[i] == '--per': per = int(args[i + 1]); i += 1
        elif args[i] == '--seed': seed = int(args[i + 1]); i += 1
        else: srcs.append(args[i])
        i += 1
    os.makedirs(outdir, exist_ok=True)
    rows = []
    for src in srcs:
        base = os.path.basename(src)[:-len('.ndjson')]
        for kind in KINDS:
            made = 0; tries = 0
            while made < per and tries < per * 4:
                tries += 1
                rng = random.Random('%d/%s/%s/%d' % (seed, base, kind, tries))
                r = mutate(src, kind, rng)
                if r is None: continue
                lines, desc = r
                name = '%s--%s-%d' % (base, kind, tries)
                with open(os.path.join(outdir, name + '.ndjson'), 'w', encoding='utf-8') as f:
                    f.write('\n'.join(lines) + '\n')
                rows.append((name, '?', desc))
                made += 1
    with open(os.path.join(outdir, 'mutants.tsv'), 'w') as f:
        for name, exp, desc in rows:
            f.write('%s\t%s\t%s\n' % (name, exp, desc))
    print(len(rows), 'mutants in', outdir)


if __name__ == '__main__':
    main()
