#!/usr/bin/env python3
"""work/soundness/tools/lk.py - a small builder of lean4export NDJSON files for hostile exports.

    from lk import *
    B = Base('work/arena-site/tests/good/init-prelude.ndjson')    # a real export to take inductives from
    X = Export(B)                                                 # empty export; constants are imported on demand
    nat = X.c('Nat')                                              # imports the cone of Nat, returns the const expr
    X.defn('foo', [], nat, X.nat(3))                              # adds a definition (no check, of course)
    X.write('out.ndjson')

Terms are expression ids of the export under construction; every constructor interns structurally, so
equal terms get equal ids (as the exporter does).  Names are dotted strings ('Nat.add'); a component that
is all digits is a numeric component.  Levels: X.lz (0), X.ls(l), X.lmax(a, b), X.limax(a, b), X.lp('u').
"""
import json, sys


def dumps(o):
    return json.dumps(o, sort_keys=True, separators=(',', ':'), ensure_ascii=False)


class Base:
    """A parsed export: names, levels, expressions by id; declarations; constant name -> declaration."""

    def __init__(self, path):
        self.names = {0: None}
        self.levels = {0: ('zero',)}
        self.exprs = {}
        self.decls = []          # (kind, obj)
        self.by_const = {}       # dotted name -> decl index
        self.quot = []           # indices of quot decls
        with open(path, encoding='utf-8') as f:
            first = True
            for line in f:
                if first:
                    first = False
                    self.meta = line.rstrip('\n')
                    continue
                o = json.loads(line)
                if 'in' in o:
                    if 'str' in o:
                        self.names[o['in']] = ('str', o['str']['pre'], o['str']['str'])
                    else:
                        self.names[o['in']] = ('num', o['num']['pre'], o['num']['i'])
                elif 'il' in o:
                    k = [x for x in o if x != 'il'][0]
                    self.levels[o['il']] = (k, o[k])
                elif 'ie' in o:
                    k = [x for x in o if x != 'ie'][0]
                    self.exprs[o['ie']] = (k, o[k])
                else:
                    k = list(o.keys())[0]
                    idx = len(self.decls)
                    self.decls.append((k, o[k]))
                    if k == 'inductive':
                        for part in ('types', 'ctors', 'recs'):
                            for t in o[k][part]:
                                self.by_const[self.dotted(t['name'])] = idx
                    else:
                        self.by_const[self.dotted(o[k]['name'])] = idx
                        if k == 'quot':
                            self.quot.append(idx)

    def dotted(self, nid):
        parts = []
        while nid != 0:
            kind, pre, x = self.names[nid]
            parts.append(str(x))
            nid = pre
        return '.'.join(reversed(parts))

    def comps(self, nid):
        parts = []
        while nid != 0:
            kind, pre, x = self.names[nid]
            parts.append((kind, x))
            nid = pre
        return list(reversed(parts))


class Export:
    def __init__(self, base=None, meta=None):
        self.base = base
        self.lines = [meta or '{"meta":{"exporter":{"name":"handcrafted","version":"0.1.0"},"format":{"version":"3.1.0"},"lean":{"githash":"generated","version":"4.36.0"}}}']
        self.nmap = {}      # structural key -> id
        self.lmap = {}
        self.emap = {}
        self.nn = 1
        self.nl = 1
        self.ne = 0
        self.imported = set()   # base decl indices already emitted
        self.bn = {0: 0}        # base name id -> my id
        self.bl = {0: 0}
        self.be = {}
        self.lz = 0
        self.declared = []

    # ---- names
    def _name(self, key, rec):
        i = self.nmap.get(key)
        if i is None:
            i = self.nn; self.nn += 1
            self.nmap[key] = i
            rec['in'] = i
            self.lines.append(dumps(rec))
        return i

    def nstr(self, pre, s):
        return self._name(('str', pre, s), {'str': {'pre': pre, 'str': s}})

    def nnum(self, pre, i):
        return self._name(('num', pre, i), {'num': {'pre': pre, 'i': i}})

    def n(self, dotted):
        if isinstance(dotted, int):
            return dotted
        cur = 0
        if dotted == '':
            return 0
        for comp in dotted.split('.'):
            cur = self.nnum(cur, int(comp)) if comp.isdigit() else self.nstr(cur, comp)
        return cur

    # ---- levels
    def _lvl(self, key, rec):
        i = self.lmap.get(key)
        if i is None:
            i = self.nl; self.nl += 1
            self.lmap[key] = i
            rec['il'] = i
            self.lines.append(dumps(rec))
        return i

    def ls(self, l, k=1):
        for _ in range(k):
            l = self._lvl(('succ', l), {'succ': l})
        return l

    def lmax(self, a, b): return self._lvl(('max', a, b), {'max': [a, b]})
    def limax(self, a, b): return self._lvl(('imax', a, b), {'imax': [a, b]})
    def lp(self, name):
        nid = self.n(name)
        return self._lvl(('param', nid), {'param': nid})

    def lnum(self, k): return self.ls(0, k)

    # ---- expressions
    def _expr(self, key, rec, fresh=False):
        i = None if fresh else self.emap.get(key)
        if i is None:
            i = self.ne; self.ne += 1
            if not fresh:
                self.emap[key] = i
            rec['ie'] = i
            self.lines.append(dumps(rec))
        return i

    def bvar(self, i): return self._expr(('bvar', i), {'bvar': i})
    def sort(self, l): return self._expr(('sort', l), {'sort': l})
    @property
    def prop(self): return self.sort(0)
    def type(self, k=0): return self.sort(self.lnum(k + 1))

    def const(self, name, us=()):
        nid = self.n(name)
        us = list(us)
        return self._expr(('const', nid, tuple(us)), {'const': {'name': nid, 'us': us}})

    def c(self, name, us=()):
        """constant expression; imports the constant's cone from the base first"""
        self.imp(name)
        return self.const(name, us)

    def app(self, f, *args, fresh=False):
        for a in args:
            f = self._expr(('app', f, a), {'app': {'arg': a, 'fn': f}}, fresh)
        return f

    def lam(self, ty, body, name='x', bi='default'):
        return self._expr(('lam', ty, body), {'lam': {'binderInfo': bi, 'body': body, 'name': self.n(name), 'type': ty}})

    def pi(self, ty, body, name='x', bi='default'):
        return self._expr(('pi', ty, body), {'forallE': {'binderInfo': bi, 'body': body, 'name': self.n(name), 'type': ty}})

    def arrow(self, a, b):
        return self.pi(a, self.lift(b, 1))

    def let(self, ty, val, body, name='x'):
        return self._expr(('let', ty, val, body), {'letE': {'body': body, 'name': self.n(name), 'nondep': False, 'type': ty, 'value': val}})

    def nat(self, v): return self._expr(('nat', str(v)), {'natVal': str(v)})
    def str_(self, s): return self._expr(('strv', s), {'strVal': s})
    def proj(self, tname, idx, e):
        nid = self.n(tname)
        return self._expr(('proj', nid, idx, e), {'proj': {'idx': idx, 'struct': e, 'typeName': nid}})
    def mdata(self, e):
        return self._expr(('mdata', e), {'mdata': {'data': {}, 'expr': e}})

    # structural view of my own expressions (needed by lift): keep a reverse table
    def _rev(self):
        return {v: k for k, v in self.emap.items()}

    def lift(self, e, k, cutoff=0):
        """shift loose bvars >= cutoff by k"""
        rev = self._rev()
        memo = {}
        def go(e, c):
            key = (e, c)
            if key in memo: return memo[key]
            t = rev[e]
            kind = t[0]
            if kind == 'bvar':
                r = self.bvar(t[1] + k) if t[1] >= c else e
            elif kind in ('sort', 'const', 'nat', 'strv'):
                r = e
            elif kind == 'app':
                r = self.app(go(t[1], c), go(t[2], c))
            elif kind == 'lam':
                r = self.lam(go(t[1], c), go(t[2], c + 1))
            elif kind == 'pi':
                r = self.pi(go(t[1], c), go(t[2], c + 1))
            elif kind == 'let':
                r = self.let(go(t[1], c), go(t[2], c), go(t[3], c + 1))
            elif kind == 'proj':
                r = self.proj(t[1], t[2], go(t[3], c))
            elif kind == 'mdata':
                r = self.mdata(go(t[1], c))
            else:
                raise ValueError(kind)
            memo[key] = r
            return r
        return go(e, cutoff)

    # ---- import from the base
    def _imp_name(self, nid):
        r = self.bn.get(nid)
        if r is None:
            kind, pre, x = self.base.names[nid]
            p = self._imp_name(pre)
            r = self.nstr(p, x) if kind == 'str' else self.nnum(p, x)
            self.bn[nid] = r
        return r

    def _imp_level(self, lid):
        r = self.bl.get(lid)
        if r is None:
            k, v = self.base.levels[lid]
            if k == 'succ': r = self.ls(self._imp_level(v))
            elif k == 'max': r = self.lmax(self._imp_level(v[0]), self._imp_level(v[1]))
            elif k == 'imax': r = self.limax(self._imp_level(v[0]), self._imp_level(v[1]))
            elif k == 'param':
                nid = self._imp_name(v)
                r = self._lvl(('param', nid), {'param': nid})
            else: raise ValueError(k)
            self.bl[lid] = r
        return r

    def _imp_expr(self, eid):
        """iterative import of a base expression (constants referenced are imported first)"""
        if eid in self.be: return self.be[eid]
        stack = [eid]
        B = self.base
        while stack:
            e = stack[-1]
            if e in self.be:
                stack.pop(); continue
            k, v = B.exprs[e]
            kids = []
            if k == 'app': kids = [v['fn'], v['arg']]
            elif k in ('lam', 'forallE'): kids = [v['type'], v['body']]
            elif k == 'letE': kids = [v['type'], v['value'], v['body']]
            elif k == 'proj': kids = [v['struct']]
            elif k == 'mdata': kids = [v['expr']]
            todo = [x for x in kids if x not in self.be]
            if todo:
                stack.extend(todo); continue
            stack.pop()
            if k == 'bvar': r = self.bvar(v)
            elif k == 'sort': r = self.sort(self._imp_level(v))
            elif k == 'const':
                r = self.const(self._imp_name(v['name']), [self._imp_level(l) for l in v['us']])
            elif k == 'app': r = self.app(self.be[v['fn']], self.be[v['arg']])
            elif k == 'lam': r = self._expr(('lam', self.be[v['type']], self.be[v['body']]), {'lam': {'binderInfo': v['binderInfo'], 'body': self.be[v['body']], 'name': self._imp_name(v['name']), 'type': self.be[v['type']]}})
            elif k == 'forallE': r = self._expr(('pi', self.be[v['type']], self.be[v['body']]), {'forallE': {'binderInfo': v['binderInfo'], 'body': self.be[v['body']], 'name': self._imp_name(v['name']), 'type': self.be[v['type']]}})
            elif k == 'letE': r = self._expr(('let', self.be[v['type']], self.be[v['value']], self.be[v['body']]), {'letE': {'body': self.be[v['body']], 'name': self._imp_name(v['name']), 'nondep': False, 'type': self.be[v['type']], 'value': self.be[v['value']]}})
            elif k == 'natVal': r = self.nat(v)
            elif k == 'strVal': r = self.str_(v)
            elif k == 'proj': r = self.proj(self._imp_name(v['typeName']), v['idx'], self.be[v['struct']])
            elif k == 'mdata': r = self.mdata(self.be[v['expr']])
            else: raise ValueError(k)
            self.be[e] = r
        return self.be[eid]

    def _consts_of(self, roots):
        """constant names (dotted) referenced from base expressions"""
        B = self.base
        seen = set(); out = set(); stack = list(roots)
        lit_nat = lit_str = False
        while stack:
            e = stack.pop()
            if e in seen: continue
            seen.add(e)
            k, v = B.exprs[e]
            if k == 'const': out.add(B.dotted(v['name']))
            elif k == 'app': stack += [v['fn'], v['arg']]
            elif k in ('lam', 'forallE'): stack += [v['type'], v['body']]
            elif k == 'letE': stack += [v['type'], v['value'], v['body']]
            elif k == 'proj':
                stack.append(v['struct']); out.add(B.dotted(v['typeName']))
            elif k == 'mdata': stack.append(v['expr'])
            elif k == 'natVal': lit_nat = True
            elif k == 'strVal': lit_str = True
        if lit_nat: out.add('Nat')
        if lit_str: out |= {'String', 'Char.ofNat', 'String.ofList', 'Nat'}
        return out

    def imp(self, name, _stack=None):
        """import the declaration of constant `name` (and its dependency cone) from the base"""
        B = self.base
        if B is None or name not in B.by_const:
            return
        idx = B.by_const[name]
        if idx in self.imported: return
        self.imported.add(idx)
        kind, d = B.decls[idx]
        roots = []
        own = set()
        if kind == 'inductive':
            for t in d['types']: roots.append(t['type']); own.add(B.dotted(t['name']))
            for c in d['ctors']: roots.append(c['type']); own.add(B.dotted(c['name']))
            for r in d['recs']:
                roots.append(r['type']); own.add(B.dotted(r['name']))
                roots += [x['rhs'] for x in r['rules']]
        elif kind == 'quot':
            # the four quot records travel together, after Eq
            self.imp('Eq')
            for qi in B.quot:
                self.imported.add(qi)
            for qi in B.quot:
                roots.append(B.decls[qi][1]['type'])
        else:
            roots.append(d['type'])
            if 'value' in d: roots.append(d['value'])
        for cn in sorted(self._consts_of(roots)):
            if cn not in own: self.imp(cn)
        if kind == 'quot':
            for qi in B.quot:
                q = B.decls[qi][1]
                self.lines.append(dumps({'quot': {'kind': q['kind'], 'levelParams': [self._imp_name(x) for x in q['levelParams']], 'name': self._imp_name(q['name']), 'type': self._imp_expr(q['type'])}}))
            return
        if kind == 'inductive':
            o = {'types': [], 'ctors': [], 'recs': []}
            for t in d['types']:
                t2 = dict(t)
                t2['all'] = [self._imp_name(x) for x in t['all']]
                t2['ctors'] = [self._imp_name(x) for x in t['ctors']]
                t2['levelParams'] = [self._imp_name(x) for x in t['levelParams']]
                t2['name'] = self._imp_name(t['name'])
                t2['type'] = self._imp_expr(t['type'])
                o['types'].append(t2)
            for c in d['ctors']:
                c2 = dict(c)
                c2['induct'] = self._imp_name(c['induct'])
                c2['levelParams'] = [self._imp_name(x) for x in c['levelParams']]
                c2['name'] = self._imp_name(c['name'])
                c2['type'] = self._imp_expr(c['type'])
                o['ctors'].append(c2)
            for r in d['recs']:
                r2 = dict(r)
                r2['all'] = [self._imp_name(x) for x in r['all']]
                r2['levelParams'] = [self._imp_name(x) for x in r['levelParams']]
                r2['name'] = self._imp_name(r['name'])
                r2['type'] = self._imp_expr(r['type'])
                r2['rules'] = [{'ctor': self._imp_name(x['ctor']), 'nfields': x['nfields'], 'rhs': self._imp_expr(x['rhs'])} for x in r['rules']]
                o['recs'].append(r2)
            self.lines.append(dumps({'inductive': o}))
            return
        d2 = dict(d)
        d2['levelParams'] = [self._imp_name(x) for x in d['levelParams']]
        d2['name'] = self._imp_name(d['name'])
        d2['type'] = self._imp_expr(d['type'])
        if 'value' in d: d2['value'] = self._imp_expr(d['value'])
        if 'all' in d: d2['all'] = [self._imp_name(x) for x in d['all']]
        self.lines.append(dumps({kind: d2}))

    def base_decl(self, name):
        B = self.base
        return B.decls[B.by_const[name]]

    def base_value(self, name):
        """the (imported) value expression of a base definition"""
        kind, d = self.base_decl(name)
        return self._imp_expr(d['value'])

    def base_type(self, name):
        kind, d = self.base_decl(name)
        return self._imp_expr(d['type'])

    # ---- declarations
    def defn(self, name, lparams, ty, val, hints='abbrev', safety='safe'):
        nid = self.n(name)
        if isinstance(hints, int): hints = {'regular': hints}
        self.lines.append(dumps({'def': {'all': [nid], 'hints': hints, 'levelParams': [self.n(x) for x in lparams], 'name': nid, 'safety': safety, 'type': ty, 'value': val}}))
        self.declared.append(name)

    def thm(self, name, lparams, ty, val):
        nid = self.n(name)
        self.lines.append(dumps({'thm': {'all': [nid], 'levelParams': [self.n(x) for x in lparams], 'name': nid, 'type': ty, 'value': val}}))
        self.declared.append(name)

    def axiom(self, name, lparams, ty):
        nid = self.n(name)
        self.lines.append(dumps({'axiom': {'isUnsafe': False, 'levelParams': [self.n(x) for x in lparams], 'name': nid, 'type': ty}}))
        self.declared.append(name)

    def opaque(self, name, lparams, ty, val):
        nid = self.n(name)
        self.lines.append(dumps({'opaque': {'all': [nid], 'isUnsafe': False, 'levelParams': [self.n(x) for x in lparams], 'name': nid, 'type': ty, 'value': val}}))
        self.declared.append(name)

    def raw(self, obj):
        self.lines.append(dumps(obj))

    def write(self, path):
        with open(path, 'w', encoding='utf-8') as f:
            f.write('\n'.join(self.lines) + '\n')
        return path


_BASES = {}
def prelude():
    import os
    root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
    p = os.path.join(root, 'work/arena-site/tests/good/init-prelude.ndjson')
    if p not in _BASES: _BASES[p] = Base(p)
    return _BASES[p]


# ---- a named layer: trees with Python variables, converted to de Bruijn by Export.t ---------------------
class Var:
    __slots__ = ('name',)
    def __init__(self, name='x'): self.name = name

def lam(ty, f, name='x'):
    v = Var(name); return ('lam', v, ty, f(v))
def pi(ty, f, name='x'):
    v = Var(name); return ('pi', v, ty, f(v))
def arrow(a, b):
    return ('pi', Var('a'), a, b)
def let_(ty, val, f, name='x'):
    v = Var(name); return ('let', v, ty, val, f(v))
def app(f, *args):
    return ('app', f, args) if args else f
def proj(tname, idx, e):
    return ('proj', tname, idx, e)
def lams(tys, f):
    """lams([A, lambda a: B, ...], lambda a, b: body): a telescope"""
    def go(i, vs):
        if i == len(tys): return f(*vs)
        ty = tys[i](*vs) if callable(tys[i]) else tys[i]
        return lam(ty, lambda v: go(i + 1, vs + [v]))
    return go(0, [])
def pis(tys, f):
    def go(i, vs):
        if i == len(tys): return f(*vs)
        ty = tys[i](*vs) if callable(tys[i]) else tys[i]
        return pi(ty, lambda v: go(i + 1, vs + [v]))
    return go(0, [])

def _t(self, tree, env=()):
    """tree -> expression id; ints are closed expression ids of this export"""
    if isinstance(tree, int): return tree
    if isinstance(tree, Var):
        for i, v in enumerate(reversed(env)):
            if v is tree: return self.bvar(i)
        raise ValueError('unbound variable ' + tree.name)
    k = tree[0]
    if k == 'app':
        f = _t(self, tree[1], env)
        for a in tree[2]: f = self.app(f, _t(self, a, env))
        return f
    if k == 'lam': return self.lam(_t(self, tree[2], env), _t(self, tree[3], env + (tree[1],)), tree[1].name)
    if k == 'pi': return self.pi(_t(self, tree[2], env), _t(self, tree[3], env + (tree[1],)), tree[1].name)
    if k == 'let': return self.let(_t(self, tree[2], env), _t(self, tree[3], env), _t(self, tree[4], env + (tree[1],)), tree[1].name)
    if k == 'proj': return self.proj(tree[1], tree[2], _t(self, tree[3], env))
    if k == 'bvar': return self.bvar(tree[1])
    raise ValueError(k)
Export.t = _t


def simple_inductive(X, name, lparams, ty, ctors, rec_lparams, rec_ty, rules, num_params=0, num_indices=0,
                     is_rec=False, k=False, num_motives=1, num_minors=None, with_rec=True, reflexive=False):
    """one non-mutual, non-nested inductive: ctors = [(name, type, numFields)], rules = [(ctor, nfields, rhs)]"""
    nid = X.n(name)
    lps = [X.n(x) for x in lparams]
    o = {'types': [{'all': [nid], 'ctors': [X.n(c[0]) for c in ctors], 'isRec': is_rec, 'isReflexive': reflexive,
                    'isUnsafe': False, 'levelParams': lps, 'name': nid, 'numIndices': num_indices, 'numNested': 0,
                    'numParams': num_params, 'type': ty}],
         'ctors': [{'cidx': i, 'induct': nid, 'isUnsafe': False, 'levelParams': lps, 'name': X.n(c[0]),
                    'numFields': c[2], 'numParams': num_params, 'type': c[1]} for i, c in enumerate(ctors)],
         'recs': []}
    if with_rec:
        o['recs'].append({'all': [nid], 'isUnsafe': False, 'k': k, 'levelParams': [X.n(x) for x in rec_lparams],
                          'name': X.n(name + '.rec'), 'numIndices': num_indices,
                          'numMinors': len(ctors) if num_minors is None else num_minors, 'numMotives': num_motives,
                          'numParams': num_params, 'rules': [{'ctor': X.n(r[0]), 'nfields': r[1], 'rhs': r[2]} for r in rules],
                          'type': rec_ty})
    X.raw({'inductive': o})


def mk_inductive(X, name, lparams, params, indices, sort_lvl, ctors, large=True, is_prop=False, k=None,
                 reflexive=False, bad_rule=None, emit=True):
    """A non-mutual, non-nested inductive with its kernel-shaped recursor.
       params  = [type_fn(prev params...)]                (trees; callables get the earlier variables)
       indices = [type_fn(params..., prev indices...)]
       ctors   = [(cname, [field_fn(params..., prev fields...) or ('rec', idx_fn(params..., prev fields...))], result_idx_fn(params..., fields...) -> list)]
       A field ('rec', f) is a recursive field of type `I params (f(...))`, f returning the index list.
       Returns (I_const_fn(levels), rec_name)."""
    lv = [X.lp(p) for p in lparams]
    I = lambda: X.const(name, lv)
    np_, ni = len(params), len(indices)
    def call(f, vs):
        return f(*vs) if callable(f) else f
    # type
    ty = pis(list(params) + [(lambda *vs, f=f: call(f, vs)) for f in indices], lambda *vs: X.sort(sort_lvl))
    ty_id = X.t(ty)
    def field_ty(f, ps, fs):
        if isinstance(f, tuple) and f[0] == 'rec':
            return app(I(), *ps, *call(f[1], ps + fs))
        return call(f, ps + fs)
    ctor_recs = []
    for cname, fields, ridx in ctors:
        def build(*vs, fields=fields, ridx=ridx):
            ps, fs = list(vs[:np_]), list(vs[np_:])
            return app(I(), *ps, *call(ridx, ps + fs))
        tys = list(params) + [(lambda *vs, f=f: field_ty(f, list(vs[:np_]), list(vs[np_:]))) for f in fields]
        ctor_recs.append((cname, X.t(pis(tys, build)), len(fields)))
    # recursor
    u = 'u'
    i = 0
    while u in lparams:
        i += 1; u = 'u_%d' % i
    rec_lparams = ([u] if large else []) + list(lparams)
    msort = X.sort(X.lp(u)) if large else X.sort(0)
    recname = name + '.rec'
    recc = lambda: X.const(recname, ([X.lp(u)] if large else []) + lv)
    def motive_ty(ps):
        return pis([(lambda *xs, f=f: call(f, ps + list(xs))) for f in indices] + [lambda *xs: app(I(), *ps, *xs)], lambda *xs: msort)
    def minor_ty(ci, ps, mot):
        cname, fields, ridx = ctors[ci]
        ftys = [(lambda *fs, f=f: field_ty(f, ps, list(fs)[:len(fields)])) for f in fields]
        # ih binders follow the fields
        recf = [j for j, f in enumerate(fields) if isinstance(f, tuple) and f[0] == 'rec']
        def ih_ty(j):
            return lambda *xs: app(mot, *call(fields[j][1], ps + list(xs[:j])), xs[j])
        tys = ftys + [ih_ty(j) for j in recf]
        return pis(tys, lambda *xs: app(mot, *call(ridx, ps + list(xs[:len(fields)])), app(X.const(cname, lv), *ps, *xs[:len(fields)])))
    def rec_ty_tree():
        def after_params(*ps):
            ps = list(ps)
            return pi(motive_ty(ps), lambda mot: pis([(lambda *ms, ci=ci: minor_ty(ci, ps, mot)) for ci in range(len(ctors))],
                      lambda *ms: pis([(lambda *xs, f=f: call(f, ps + list(xs))) for f in indices] + [lambda *xs: app(I(), *ps, *xs)],
                                      lambda *xs: app(mot, *xs))), 'motive')
        return pis(list(params), after_params)
    rec_ty = X.t(rec_ty_tree())
    rules = []
    for ci, (cname, fields, ridx) in enumerate(ctors):
        def rhs_tree(ci=ci, fields=fields):
            def after_params(*ps):
                ps = list(ps)
                def after_mot(mot):
                    def after_minors(*ms):
                        ms = list(ms)
                        ftys = [(lambda *fs, f=f: field_ty(f, ps, list(fs))) for f in fields]
                        def body(*fs):
                            fs = list(fs)
                            ihs = [app(recc(), *ps, mot, *ms, *call(f[1], ps + fs[:j]), fs[j])
                                   for j, f in enumerate(fields) if isinstance(f, tuple) and f[0] == 'rec']
                            if bad_rule is not None:
                                return bad_rule(ci, ps, mot, ms, fs, ihs)
                            return app(ms[ci], *fs, *ihs)
                        return lams(ftys, body)
                    return lams([(lambda *xs, c2=c2: minor_ty(c2, ps, mot)) for c2 in range(len(ctors))], after_minors)
                return lam(motive_ty(ps), after_mot, 'motive')
            return lams(list(params), after_params)
        rules.append((cname, len(fields), X.t(rhs_tree())))
    is_rec = any(isinstance(f, tuple) and f[0] == 'rec' for c in ctors for f in c[1])
    if k is None:
        k = is_prop and len(ctors) == 1 and len(ctors[0][1]) == 0
    if emit:
        simple_inductive(X, name, lparams, ty_id, ctor_recs, rec_lparams, rec_ty, rules, num_params=np_, num_indices=ni,
                         is_rec=is_rec, k=k, reflexive=reflexive)
    return I, recname
