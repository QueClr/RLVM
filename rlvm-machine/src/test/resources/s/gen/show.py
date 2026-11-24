#!/usr/bin/env python3
"""show.py NAME... - print a base constant's declaration (type, value) readably. --base FILE"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lk import Base, prelude

def lvl(B, l):
    k = B.levels[l]
    if k[0] == 'zero': return '0'
    if k[0] == 'succ':
        n = 1; x = k[1]
        while B.levels[x][0] == 'succ': n += 1; x = B.levels[x][1]
        return str(n) if B.levels[x][0] == 'zero' else '%s+%d' % (lvl(B, x), n)
    if k[0] in ('max', 'imax'): return '(%s %s %s)' % (k[0], lvl(B, k[1][0]), lvl(B, k[1][1]))
    if k[0] == 'param': return B.dotted(k[1])

def ex(B, e, depth=0, limit=40):
    if depth > limit: return '...'
    k, v = B.exprs[e]
    if k == 'bvar': return '#%d' % v
    if k == 'sort': return 'Sort(%s)' % lvl(B, v)
    if k == 'const': return B.dotted(v['name']) + ('.{%s}' % ','.join(lvl(B, l) for l in v['us']) if v['us'] else '')
    if k == 'app':
        args = []
        while B.exprs[e][0] == 'app':
            args.append(B.exprs[e][1]['arg']); e = B.exprs[e][1]['fn']
        return '(' + ' '.join([ex(B, e, depth + 1)] + [ex(B, a, depth + 1) for a in reversed(args)]) + ')'
    if k == 'lam': return '(fun %s:%s => %s)' % (B.dotted(v['name']), ex(B, v['type'], depth + 1), ex(B, v['body'], depth + 1))
    if k == 'forallE': return '(Pi %s:%s, %s)' % (B.dotted(v['name']), ex(B, v['type'], depth + 1), ex(B, v['body'], depth + 1))
    if k == 'letE': return '(let %s:%s := %s; %s)' % (B.dotted(v['name']), ex(B, v['type'], depth + 1), ex(B, v['value'], depth + 1), ex(B, v['body'], depth + 1))
    if k == 'natVal': return v
    if k == 'strVal': return repr(v)
    if k == 'proj': return '%s.%d(%s)' % (B.dotted(v['typeName']), v['idx'], ex(B, v['struct'], depth + 1))
    if k == 'mdata': return 'mdata(%s)' % ex(B, v['expr'], depth + 1)

def show(B, name):
    if name not in B.by_const: print(name, 'MISSING'); return
    kind, d = B.decls[B.by_const[name]]
    if kind == 'inductive':
        for part in ('types', 'ctors', 'recs'):
            for t in d[part]:
                if B.dotted(t['name']) == name:
                    extra = {k: t[k] for k in t if k not in ('type', 'name', 'levelParams', 'rules', 'all', 'ctors', 'induct')}
                    print('%s %s.{%s} : %s  %s' % (part[:-1], name, ','.join(B.dotted(x) for x in t['levelParams']), ex(B, t['type']), extra))
                    if part == 'types': print('   ctors:', [B.dotted(x) for x in t['ctors']])
                    if part == 'recs':
                        for r in t['rules']: print('   rule', B.dotted(r['ctor']), r['nfields'], ex(B, r['rhs']))
    else:
        print('%s %s.{%s} : %s' % (kind, name, ','.join(B.dotted(x) for x in d['levelParams']), ex(B, d['type'])))
        if 'value' in d: print('   :=', ex(B, d['value'])[:1500], ' hints', d.get('hints'))

if __name__ == '__main__':
    args = sys.argv[1:]
    B = prelude()
    if args and args[0] == '--base':
        B = Base(args[1]); args = args[2:]
    for n in args: show(B, n)
