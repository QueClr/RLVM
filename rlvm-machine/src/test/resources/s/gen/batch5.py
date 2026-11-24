#!/usr/bin/env python3
"""Hostile exports, batch 5: K1 roles (case trees, BODY blocks, TELE with domain patterns) and iota on Nat
literals / offsets / structures / quotients: every definition is used more than ten times before the false claim,
so that the rent-to-own threshold passes also without -Drlvm.machine.elabAt=1."""
from common import *

P31, P32, P64 = 2 ** 31, 2 ** 32, 2 ** 64


def natrec(C, z, s, major, lvl=None):
    return app(C.c('Nat.rec', [lvl or C.l1]), lam(C.nat, lambda _: C.nat), z, s, major)

def boolrec(C, f, t, major, T=None, lvl=None):
    return app(C.c('Bool.rec', [lvl or C.l1]), lam(C.bool, lambda _: T or C.nat), f, t, major)

def defs(C):
    """the definitions under test; returns name -> constant tree"""
    NN = arrow(C.nat, C.nat); NNN = arrow(C.nat, NN)
    L = C.lit
    C.defn('S.pred', NN, lam(C.nat, lambda n: natrec(C, L(0), lam(C.nat, lambda k: lam(C.nat, lambda ih: k)), n)), hints=1)
    C.defn('S.isZero', arrow(C.nat, C.bool), lam(C.nat, lambda n: app(C.c('Nat.rec', [C.l1]), lam(C.nat, lambda _: C.bool),
           C.btrue, lam(C.nat, lambda k: lam(C.bool, lambda ih: C.bfalse)), n)), hints=1)
    # two levels, both majors parameters
    C.defn('S.two', NNN, lam(C.nat, lambda a: lam(C.nat, lambda b:
           natrec(C, natrec(C, L(10), lam(C.nat, lambda m: lam(C.nat, lambda _: L(11))), b),
                  lam(C.nat, lambda n: lam(C.nat, lambda _: natrec(C, L(12), lam(C.nat, lambda m: lam(C.nat, lambda _: app(C.c('Nat.add'), n, m))), b))), a))), hints=1)
    # three levels, the inner majors are fields of the enclosing arms: g 0 = 0, g 1 = 1, g 2 = 2, g (n + 3) = n
    C.defn('S.three', NN, lam(C.nat, lambda a: natrec(C, L(0), lam(C.nat, lambda n: lam(C.nat, lambda _:
           natrec(C, L(1), lam(C.nat, lambda k: lam(C.nat, lambda _:
           natrec(C, L(2), lam(C.nat, lambda j: lam(C.nat, lambda _: j)), k))), n))), a)), hints=1)
    # through Nat.casesOn (a definition of shape CASESON)
    C.defn('S.viaCases', NN, lam(C.nat, lambda a: app(C.c('Nat.casesOn', [C.l1]), lam(C.nat, lambda _: C.nat), a, L(7), lam(C.nat, lambda n: n))), hints=1)
    # a minor that reads its induction hypothesis: not a K1 shape
    C.defn('S.double', NN, lam(C.nat, lambda a: natrec(C, L(0), lam(C.nat, lambda n: lam(C.nat, lambda ih: app(C.succ, app(C.succ, ih)))), a)), hints=1)
    # leaf with a let and outer parameters
    C.defn('S.leaf', NNN, lam(C.nat, lambda a: lam(C.nat, lambda c: natrec(C, c, lam(C.nat, lambda n: lam(C.nat, lambda _:
           let_(C.nat, app(C.succ, n), lambda m: app(C.c('Nat.add'), m, c)))), a))), hints=1)
    # over-application: the tree returns a function
    C.defn('S.over', arrow(C.bool, NN), lam(C.bool, lambda b: app(C.c('Bool.rec', [C.l1]), lam(C.bool, lambda _: NN),
           lam(C.nat, lambda x: x), lam(C.nat, lambda x: app(C.succ, x)), b)), hints=1)
    # LEAF shapes
    PNN = app(C.c('Prod', [0, 0]), C.nat, C.nat)
    C.defn('S.fst', arrow(PNN, C.nat), lam(PNN, lambda p: proj('Prod', 0, p)), hints=1)
    C.defn('S.snd', arrow(PNN, C.nat), lam(PNN, lambda p: proj('Prod', 1, p)), hints=1)
    C.defn('S.pick', NNN, lam(C.nat, lambda a: lam(C.nat, lambda b: a)), hints=1)
    C.defn('S.mk', arrow(C.nat, arrow(C.nat, PNN)), lam(C.nat, lambda a: lam(C.nat, lambda b: app(C.c('Prod.mk', [0, 0]), C.nat, C.nat, b, a))), hints=1)
    # structure-like major (Prod.rec): not a case tree; generic iota with structure eta
    C.defn('S.swap', arrow(PNN, PNN), lam(PNN, lambda p: app(C.c('Prod.rec', [C.l1, 0, 0]), C.nat, C.nat, lam(PNN, lambda _: PNN),
           lam(C.nat, lambda a: lam(C.nat, lambda b: app(C.c('Prod.mk', [0, 0]), C.nat, C.nat, b, a))), p)), hints=1)
    return {k: C.k('S.' + k) for k in ['pred', 'isZero', 'two', 'three', 'viaCases', 'double', 'leaf', 'over', 'fst', 'snd', 'pick', 'mk', 'swap']}

def true_claims(C, D):
    """(lhs, rhs, type) triples that hold"""
    L = C.lit
    n = C.axiom('S.n', C.nat)
    s = lambda t, k=1: (app(C.succ, s(t, k - 1)) if k else t)
    PNN = app(C.c('Prod', [0, 0]), C.nat, C.nat)
    p = C.axiom('S.p', PNN)
    N = C.nat
    cl = [
        (app(D['pred'], L(5)), L(4), N), (app(D['pred'], L(0)), L(0), N), (app(D['pred'], L(2 ** 40)), L(2 ** 40 - 1), N),
        (app(D['pred'], L(P31)), L(P31 - 1), N), (app(D['pred'], L(P32)), L(P32 - 1), N), (app(D['pred'], L(P64)), L(P64 - 1), N),
        (app(D['pred'], s(n())), n(), N), (app(D['pred'], s(n(), 2)), s(n()), N), (app(D['pred'], C.zero), L(0), N),
        (app(D['pred'], app(D['pred'], L(P31 + 1))), L(P31 - 1), N),
        (app(D['isZero'], L(0)), C.btrue, C.bool), (app(D['isZero'], L(P32)), C.bfalse, C.bool), (app(D['isZero'], s(n())), C.bfalse, C.bool),
        (app(D['two'], L(0), L(0)), L(10), N), (app(D['two'], L(0), L(5)), L(11), N), (app(D['two'], L(3), L(0)), L(12), N),
        (app(D['two'], L(3), L(5)), L(6), N), (app(D['two'], s(n()), s(n())), app(C.c('Nat.add'), n(), n()), N),
        (app(D['three'], L(0)), L(0), N), (app(D['three'], L(1)), L(1), N), (app(D['three'], L(2)), L(2), N),
        (app(D['three'], L(3)), L(0), N), (app(D['three'], L(10)), L(7), N), (app(D['three'], s(n(), 3)), n(), N),
        (app(D['three'], L(P32 + 3)), L(P32), N),
        (app(D['viaCases'], L(0)), L(7), N), (app(D['viaCases'], L(9)), L(8), N), (app(D['viaCases'], s(n())), n(), N),
        (app(D['double'], L(4)), L(8), N), (app(D['double'], L(0)), L(0), N),
        (app(D['leaf'], L(0), L(9)), L(9), N), (app(D['leaf'], L(3), L(9)), L(12), N),
        (app(D['over'], C.btrue, L(5)), L(6), N), (app(D['over'], C.bfalse, L(5)), L(5), N),
        (app(D['fst'], app(D['mk'], L(1), L(2))), L(2), N), (app(D['snd'], app(D['mk'], L(1), L(2))), L(1), N),
        (app(D['pick'], L(1), L(2)), L(1), N), (app(D['fst'], app(D['swap'], app(D['mk'], L(1), L(2)))), L(1), N),
        (app(D['fst'], app(D['swap'], p())), app(D['snd'], p()), N),
        (app(D['swap'], app(D['swap'], p())), p(), PNN),
    ]
    return cl

FALSE = {
    'pred-5': lambda C, D, n, p: (app(D['pred'], C.lit(5)), C.lit(5), C.nat),
    'pred-0': lambda C, D, n, p: (app(D['pred'], C.lit(0)), C.lit(1), C.nat),
    'pred-big': lambda C, D, n, p: (app(D['pred'], C.lit(2 ** 40)), C.lit(2 ** 40), C.nat),
    'pred-p31': lambda C, D, n, p: (app(D['pred'], C.lit(P31)), C.lit(P31), C.nat),
    'pred-p32': lambda C, D, n, p: (app(D['pred'], C.lit(P32)), C.lit(0), C.nat),
    'pred-p64': lambda C, D, n, p: (app(D['pred'], C.lit(P64)), C.lit(P64), C.nat),
    'pred-succ-n': lambda C, D, n, p: (app(D['pred'], app(C.succ, n)), app(C.succ, n), C.nat),
    'pred-n': lambda C, D, n, p: (app(D['pred'], n), n, C.nat),
    'iszero-big': lambda C, D, n, p: (app(D['isZero'], C.lit(P32)), C.btrue, C.bool),
    'iszero-zero': lambda C, D, n, p: (app(D['isZero'], C.lit(0)), C.bfalse, C.bool),
    'iszero-n': lambda C, D, n, p: (app(D['isZero'], n), C.bfalse, C.bool),
    'two-00': lambda C, D, n, p: (app(D['two'], C.lit(0), C.lit(0)), C.lit(11), C.nat),
    'two-05': lambda C, D, n, p: (app(D['two'], C.lit(0), C.lit(5)), C.lit(10), C.nat),
    'two-30': lambda C, D, n, p: (app(D['two'], C.lit(3), C.lit(0)), C.lit(11), C.nat),
    'two-35': lambda C, D, n, p: (app(D['two'], C.lit(3), C.lit(5)), C.lit(8), C.nat),
    'two-swapped': lambda C, D, n, p: (app(D['two'], C.lit(0), C.lit(5)), app(D['two'], C.lit(5), C.lit(0)), C.nat),
    'three-3': lambda C, D, n, p: (app(D['three'], C.lit(3)), C.lit(3), C.nat),
    'three-10': lambda C, D, n, p: (app(D['three'], C.lit(10)), C.lit(8), C.nat),
    'three-2': lambda C, D, n, p: (app(D['three'], C.lit(2)), C.lit(1), C.nat),
    'three-big': lambda C, D, n, p: (app(D['three'], C.lit(P32 + 3)), C.lit(P32 + 1), C.nat),
    'cases-0': lambda C, D, n, p: (app(D['viaCases'], C.lit(0)), C.lit(0), C.nat),
    'cases-9': lambda C, D, n, p: (app(D['viaCases'], C.lit(9)), C.lit(9), C.nat),
    'double-4': lambda C, D, n, p: (app(D['double'], C.lit(4)), C.lit(4), C.nat),
    'leaf-3': lambda C, D, n, p: (app(D['leaf'], C.lit(3), C.lit(9)), C.lit(13), C.nat),
    'leaf-0': lambda C, D, n, p: (app(D['leaf'], C.lit(0), C.lit(9)), C.lit(0), C.nat),
    'over-true': lambda C, D, n, p: (app(D['over'], C.btrue, C.lit(5)), C.lit(5), C.nat),
    'over-false': lambda C, D, n, p: (app(D['over'], C.bfalse, C.lit(5)), C.lit(6), C.nat),
    'fst-mk': lambda C, D, n, p: (app(D['fst'], app(D['mk'], C.lit(1), C.lit(2))), C.lit(1), C.nat),
    'snd-mk': lambda C, D, n, p: (app(D['snd'], app(D['mk'], C.lit(1), C.lit(2))), C.lit(2), C.nat),
    'pick': lambda C, D, n, p: (app(D['pick'], C.lit(1), C.lit(2)), C.lit(2), C.nat),
    'swap-once': lambda C, D, n, p: (app(D['swap'], p), p, app(C.c('Prod', [0, 0]), C.nat, C.nat)),
    'fst-swap': lambda C, D, n, p: (app(D['fst'], app(D['swap'], p)), app(D['fst'], p), C.nat),
}

def claim(C, name, a, b, T):
    lvl = C.l1
    C.thm(name, C.eq(lvl, T, a, b), C.refl(lvl, T, b))

@case('k1-trees-ok', 'accept', 'case trees, CASESON, leaves, over-application, LEAF shapes, structure iota: 40 true claims, twice')
def _(C):
    D = defs(C)
    cl = true_claims(C, D)
    for rnd in range(2):
        for i, (a, b, T) in enumerate(cl):
            claim(C, 'S.t%d_%d' % (rnd, i), a, b, T)

def _mk_false(key):
    @case('k1-%s-bad' % key, 'reject', 'K1 roles: false claim %s after 40 true uses of the same definitions' % key)
    def _(C):
        D = defs(C)
        cl = true_claims(C, D)
        for i, (a, b, T) in enumerate(cl):
            claim(C, 'S.t%d' % i, a, b, T)
        a, b, T = FALSE[key](C, D, C.k('S.n'), C.k('S.p'))
        claim(C, 'S.bad', a, b, T)

for _k in FALSE:
    _mk_false(_k)


# ---- an enumeration and a family with parameters and an index
def fam(C):
    X = C.X
    mk_inductive(X, 'S.Col', [], [], [], X.lnum(1), [('S.Col.r', [], lambda *a: []), ('S.Col.g', [], lambda *a: []), ('S.Col.b', [], lambda *a: [])])
    Col = C.k('S.Col'); r, g, b = C.k('S.Col.r'), C.k('S.Col.g'), C.k('S.Col.b')
    C.defn('S.rot', arrow(Col, Col), lam(Col, lambda c: app(C.k('S.Col.rec', [C.l1]), lam(Col, lambda _: Col), g, b, r, c)), hints=1)
    # V (A : Type) : Nat -> Type | nil : V A 0 | cons : (n : Nat) -> A -> V A n -> V A (n + 1)
    V, Vrec = mk_inductive(X, 'S.V', [], [C.type], [lambda A: C.nat], X.lnum(1),
                           [('S.V.nil', [], lambda A: [C.zero]),
                            ('S.V.cons', [lambda A: C.nat, lambda A, n: A, ('rec', lambda A, n, a: [n])], lambda A, n, a, t: [app(C.succ, n)])])
    Vc = C.k('S.V')
    # hd A d n v : A := V.rec (motive := fun _ _ => A) d (fun n a t ih => a) n v     (ih unread)
    C.defn('S.hd', pi(C.type, lambda A: arrow(A, pi(C.nat, lambda n: arrow(app(Vc, A, n), A)))),
           lam(C.type, lambda A: lam(A, lambda d: lam(C.nat, lambda n: lam(app(Vc, A, n), lambda v:
               app(C.k('S.V.rec', [C.l1]), A, lam(C.nat, lambda i: lam(app(Vc, A, i), lambda _: A)), d,
                   lam(C.nat, lambda k: lam(A, lambda a: lam(app(Vc, A, k), lambda t: lam(A, lambda ih: a)))), n, v))))), hints=1)
    # len A n v : Nat (reads the index field)
    C.defn('S.len', pi(C.type, lambda A: pi(C.nat, lambda n: arrow(app(Vc, A, n), C.nat))),
           lam(C.type, lambda A: lam(C.nat, lambda n: lam(app(Vc, A, n), lambda v:
               app(C.k('S.V.rec', [C.l1]), A, lam(C.nat, lambda i: lam(app(Vc, A, i), lambda _: C.nat)), C.lit(0),
                   lam(C.nat, lambda k: lam(A, lambda a: lam(app(Vc, A, k), lambda t: lam(C.nat, lambda ih: app(C.succ, k))))), n, v)))), hints=1)
    return Col, (r, g, b), Vc

def fam_true(C):
    Col, (r, g, b), Vc = fam(C)
    rot = C.k('S.rot'); hd = C.k('S.hd'); ln = C.k('S.len')
    nil = app(C.k('S.V.nil'), C.nat)
    v1 = app(C.k('S.V.cons'), C.nat, C.zero, C.lit(41), nil)
    v2 = app(C.k('S.V.cons'), C.nat, C.lit(1), C.lit(42), v1)
    return [
        (app(rot, r), g, Col), (app(rot, g), b, Col), (app(rot, b), r, Col), (app(rot, app(rot, app(rot, r))), r, Col),
        (app(hd, C.nat, C.lit(7), C.zero, nil), C.lit(7), C.nat), (app(hd, C.nat, C.lit(7), C.lit(1), v1), C.lit(41), C.nat),
        (app(hd, C.nat, C.lit(7), C.lit(2), v2), C.lit(42), C.nat),
        (app(ln, C.nat, C.zero, nil), C.lit(0), C.nat), (app(ln, C.nat, C.lit(1), v1), C.lit(1), C.nat), (app(ln, C.nat, C.lit(2), v2), C.lit(2), C.nat),
    ], (Col, r, g, b, rot, hd, ln, nil, v1, v2)

@case('k1-families-ok', 'accept', 'case trees on an enumeration and on an indexed family with a parameter: true claims, three times')
def _(C):
    cl, _ = fam_true(C)
    for rnd in range(3):
        for i, (a, b, T) in enumerate(cl):
            claim(C, 'S.t%d_%d' % (rnd, i), a, b, T)

FAM_FALSE = {
    'rot-r': lambda C, E: (app(E[4], E[1]), E[1], E[0]),
    'rot-g': lambda C, E: (app(E[4], E[2]), E[1], E[0]),
    'rot-b': lambda C, E: (app(E[4], E[3]), E[2], E[0]),
    'rot-twice': lambda C, E: (app(E[4], app(E[4], E[1])), E[1], E[0]),
    'hd-nil': lambda C, E: (app(E[5], C.nat, C.lit(7), C.zero, E[7]), C.lit(41), C.nat),
    'hd-v1': lambda C, E: (app(E[5], C.nat, C.lit(7), C.lit(1), E[8]), C.lit(7), C.nat),
    'hd-v2': lambda C, E: (app(E[5], C.nat, C.lit(7), C.lit(2), E[9]), C.lit(41), C.nat),
    'len-v2': lambda C, E: (app(E[6], C.nat, C.lit(2), E[9]), C.lit(1), C.nat),
    'len-nil': lambda C, E: (app(E[6], C.nat, C.zero, E[7]), C.lit(1), C.nat),
}

def _mk_fam(key):
    @case('k1-fam-%s-bad' % key, 'reject', 'K1 roles on families: false claim ' + key)
    def _(C):
        cl, E = fam_true(C)
        for rnd in range(2):
            for i, (a, b, T) in enumerate(cl):
                claim(C, 'S.t%d_%d' % (rnd, i), a, b, T)
        a, b, T = FAM_FALSE[key](C, E)
        claim(C, 'S.bad', a, b, T)

for _k in FAM_FALSE:
    _mk_fam(_k)


# ---- TELE: argument checking against domain patterns
def tele_env(C):
    X = C.X
    u, v = X.lp('u'), X.lp('v')
    T0 = C.type
    P = lambda us, A, B: app(C.c('Prod', us), A, B)
    c1 = C.axiom('S.c1', pi(T0, lambda A: pi(A, lambda x: pi(arrow(A, C.prop), lambda Q: arrow(app(Q, x), C.nat)))))
    c2 = C.axiom('S.c2', pi(X.sort(u), lambda A: arrow(A, C.nat)), ['u'])
    c3 = C.axiom('S.c3', arrow(C.c('PUnit', [u]), C.nat), ['u'])
    c4 = C.axiom('S.c4', pi(X.sort(X.ls(u)), lambda A: pi(X.sort(X.ls(v)), lambda B: arrow(P([u, v], A, B), C.nat))), ['u', 'v'])
    c5 = C.axiom('S.c5', pi(arrow(C.nat, C.nat), lambda f: pi(C.nat, lambda n: arrow(C.eqnat(app(f, n), n), C.nat))))
    Q = C.axiom('S.Q', arrow(C.nat, C.prop))
    q5 = C.axiom('S.q5', app(Q(), C.lit(5))); q6 = C.axiom('S.q6', app(Q(), C.lit(6)))
    pnb = C.axiom('S.pnb', P([0, 0], C.nat, C.bool)); pbn = C.axiom('S.pbn', P([0, 0], C.bool, C.nat))
    return dict(c1=c1, c2=c2, c3=c3, c4=c4, c5=c5, Q=Q, q5=q5, q6=q6, pnb=pnb, pbn=pbn, P=P)

def tele_good(C, E):
    one = C.l1
    idn = lam(C.nat, lambda x: x)
    return [
        app(E['c1'](), C.nat, C.lit(5), E['Q'](), E['q5']()),
        app(E['c2'](one), C.nat, C.lit(1)),
        app(E['c2'](0), C.true, C.trivial),
        app(E['c3'](one), C.c('PUnit.unit', [one])),
        app(E['c4'](0, 0), C.nat, C.bool, E['pnb']()),
        app(E['c4'](0, 0), C.bool, C.nat, E['pbn']()),
        app(E['c5'](), idn, C.lit(3), C.reflnat(C.lit(3))),
    ]

TELE_BAD = {
    'dep-proof-wrong-index': lambda C, E: app(E['c1'](), C.nat, C.lit(5), E['Q'](), E['q6']()),
    'dep-value-wrong-type': lambda C, E: app(E['c1'](), C.nat, C.btrue, E['Q'](), E['q5']()),
    'dep-pred-wrong-domain': lambda C, E: app(E['c1'](), C.bool, C.btrue, E['Q'](), E['q5']()),
    'level-arg-type': lambda C, E: app(E['c2'](C.l1), C.nat, C.btrue),
    'level-sort-mismatch': lambda C, E: app(E['c2'](0), C.nat, C.lit(1)),
    'const-level-pattern': lambda C, E: app(E['c3'](C.l1), C.c('PUnit.unit', [C.l2])),
    'const-level-pattern-2': lambda C, E: app(E['c3'](C.l2), C.c('PUnit.unit', [C.l1])),
    'prod-args-swapped': lambda C, E: app(E['c4'](0, 0), C.nat, C.bool, E['pbn']()),
    'prod-args-swapped-2': lambda C, E: app(E['c4'](0, 0), C.bool, C.nat, E['pnb']()),
    'prod-level-pattern': lambda C, E: app(E['c4'](0, C.l1), C.nat, C.bool, E['pnb']()),
    'closure-pattern': lambda C, E: app(E['c5'](), lam(C.nat, lambda x: app(C.succ, x)), C.lit(3), C.reflnat(C.lit(3))),
    'closure-pattern-2': lambda C, E: app(E['c5'](), lam(C.nat, lambda x: x), C.lit(3), C.reflnat(C.lit(4))),
    'too-many-args': lambda C, E: app(E['c3'](C.l1), C.c('PUnit.unit', [C.l1]), C.lit(1)),
}

@case('tele-ok', 'accept', 'TELE: applications of constants with dependent telescopes, each well-typed argument list 12 times')
def _(C):
    E = tele_env(C)
    for rnd in range(12):
        for i, t in enumerate(tele_good(C, E)):
            C.defn('S.d%d_%d' % (rnd, i), C.nat, t)

def _mk_tele(key):
    @case('tele-%s-bad' % key, 'reject', 'TELE / domain patterns: ill-typed argument list ' + key)
    def _(C):
        E = tele_env(C)
        for rnd in range(12):
            for i, t in enumerate(tele_good(C, E)):
                C.defn('S.d%d_%d' % (rnd, i), C.nat, t)
        C.defn('S.bad', C.nat, TELE_BAD[key](C, E))

for _k in TELE_BAD:
    _mk_tele(_k)


# ---- quotients
def quot_env(C):
    X = C.X
    C.c('Quot', [C.l1])   # imports Eq and the four quot records
    r = C.axiom('S.r', arrow(C.nat, arrow(C.nat, C.prop)))
    f = C.axiom('S.f', arrow(C.nat, C.nat))
    hty = pi(C.nat, lambda a: pi(C.nat, lambda b: arrow(app(r(), a, b), C.eqnat(app(f(), a), app(f(), b)))))
    h = C.axiom('S.h', hty)
    Qt = app(C.k('Quot', [C.l1]), C.nat, r())
    q = C.axiom('S.q', Qt)
    lift = lambda major: app(C.k('Quot.lift', [C.l1, C.l1]), C.nat, r(), C.nat, f(), h(), major)
    # the major mentions the constant Quot itself: the official replay (hash order of names) adds the quotient
    # declarations when it meets Quot, not when it meets Quot.lift / Quot.mk
    mk = lambda a: app(lam(Qt, lambda x: x), app(C.k('Quot.mk', [C.l1]), C.nat, r(), a))
    return r, f, h, q, lift, mk

@case('quot-lift-ok', 'accept', 'Quot.lift f h (Quot.mk r a) reduces to f a')
def _(C):
    r, f, h, q, lift, mk = quot_env(C)
    for i in range(12):
        C.thm('S.t%d' % i, C.eqnat(lift(mk(C.lit(i))), app(f(), C.lit(i))), C.reflnat(app(f(), C.lit(i))))

@case('quot-lift-wrong-arg-bad', 'reject', 'Quot.lift f h (Quot.mk r 1) claimed to be f 2')
def _(C):
    r, f, h, q, lift, mk = quot_env(C)
    for i in range(12):
        C.thm('S.t%d' % i, C.eqnat(lift(mk(C.lit(i))), app(f(), C.lit(i))), C.reflnat(app(f(), C.lit(i))))
    C.thm('S.bad', C.eqnat(lift(mk(C.lit(1))), app(f(), C.lit(2))), C.reflnat(app(f(), C.lit(2))))

@case('quot-lift-stuck-bad', 'reject', 'Quot.lift f h q with q an axiom claimed to be f 0')
def _(C):
    r, f, h, q, lift, mk = quot_env(C)
    C.thm('S.bad', C.eqnat(lift(q()), app(f(), C.lit(0))), C.reflnat(app(f(), C.lit(0))))

@case('quot-mk-distinct-bad', 'reject', 'Quot.mk r 1 claimed equal to Quot.mk r 2 (no Quot.sound by conversion)')
def _(C):
    X = C.X
    r, f, h, q, lift, mk = quot_env(C)
    Qt = app(C.k('Quot', [C.l1]), C.nat, r())
    C.thm('S.bad', C.eq(C.l1, Qt, mk(C.lit(1)), mk(C.lit(2))), C.refl(C.l1, Qt, mk(C.lit(1))))


if __name__ == '__main__':
    rows = emit_all(['k1-', 'tele-', 'quot-'])
    write_manifest(rows, 'batch5.tsv')
    print(len(rows), 'exports written to', OUT)
