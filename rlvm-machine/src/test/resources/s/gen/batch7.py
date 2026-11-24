#!/usr/bin/env python3
"""Hostile exports, batch 7: what survives a declaration in a warm session. The official kernel's caches live for
one declaration, and its answers depend on the mode (eagerReduce) and on the history inside the declaration."""
from common import *
from batch2 import and_rec, and_intro


def env(C):
    X_ = C.X
    u = X_.lp('u')
    # eagerReduce.{u} {α : Sort u} (a : α) : α := a   (as Init declares it; the kernel recognises the name)
    C.defn('eagerReduce', pi(X_.sort(u), lambda al: arrow(al, al)), lam(X_.sort(u), lambda al: lam(al, lambda a: a)), ['u'], hints=1)
    A = app(C.c('And'), C.true, C.true)
    # G x := And.rec (fun _ _ => 0) x   (regular: the official same-head argument comparison applies)
    C.defn('S.G', arrow(A, C.nat), lam(A, lambda x: app(C.c('And.rec', [C.l1]), C.true, C.true, lam(A, lambda t: C.nat),
           lam(C.true, lambda a: lam(C.true, lambda b: C.lit(0))), x)), hints=1)
    G = C.k('S.G')
    X = app(C.c('Nat.add'), app(G, and_intro(C)), C.lit(0))
    Y = lambda p: app(C.c('Nat.add'), app(G, p), C.lit(0))
    return A, G, X, Y

def under_eager(C, expected, stated, proof):
    """(fun (h : expected) => h) (eagerReduce stated proof): the official infer_app compares `stated` with the domain
    `expected` in eager-reduction mode, because the ARGUMENT has the form eagerReduce _ _"""
    er = C.k('eagerReduce', [0])
    return app(lam(expected, lambda h: h), app(er, stated, proof))

def plain(C, A, X, Y, name):
    """theorem name : forall p, X = Y p := fun p => Eq.refl X    (plain mode: no Nat folding, a free variable is
    present; Nat.add and G compare their arguments; the proofs are equal by proof irrelevance)"""
    C.thm(name, pi(A, lambda p: C.eqnat(X, Y(p))), lam(A, lambda p: C.reflnat(X)))

def eager(C, A, X, Y, name):
    """the same statement, the proof wrapped in eagerReduce: under eager reduction the closed side X folds to the
    literal 0 first, and 0 against Nat.add (G p) 0 is false"""
    C.thm(name, pi(A, lambda p: C.eqnat(X, Y(p))), lam(A, lambda p: under_eager(C, C.eqnat(X, Y(p)), C.eqnat(X, X), C.reflnat(X))))


@case('warm-plain-pair-ok', 'accept', 'X = Y p in plain mode, in two declarations (same-head arguments, proof irrelevance)')
def _(C):
    A, G, X, Y = env(C)
    plain(C, A, X, Y, 'S.a')
    plain(C, A, X, Y, 'S.b')

@case('warm-eager-pair-alone-bad', 'reject', 'X = Y p under eagerReduce alone: the closed side folds first, the comparison fails')
def _(C):
    A, G, X, Y = env(C)
    eager(C, A, X, Y, 'S.bad')

@case('warm-pos-across-declarations-eager-bad', 'reject',
      'WARM: declaration a proves X = Y p in plain mode (the pair enters POS); declaration bad needs the same pair under '
      'eagerReduce, where the official kernel (whose caches live for one declaration) answers false. A pair table that '
      'survives the declaration answers T')
def _(C):
    A, G, X, Y = env(C)
    plain(C, A, X, Y, 'S.a')
    eager(C, A, X, Y, 'S.bad')

@case('warm-pos-same-declaration-eager-ok', 'accept',
      'twin (to be confirmed by the official verdict): ONE declaration that needs the pair first in plain mode and then '
      'under eagerReduce: the official success cache answers the second')
def _(C):
    A, G, X, Y = env(C)
    er = C.k('eagerReduce', [0])
    andc = C.c('And'); intro = C.c('And.intro')
    def st(p): return C.eqnat(X, Y(p))
    ty = pi(A, lambda p: app(andc, st(p), st(p)))
    val = lam(A, lambda p: app(intro, st(p), st(p), C.reflnat(X), under_eager(C, st(p), C.eqnat(X, X), C.reflnat(X))))
    C.thm('S.t', ty, val)

@case('warm-pos-same-declaration-eager-first-bad', 'reject',
      'ONE declaration that needs the pair first under eagerReduce (false) and then in plain mode')
def _(C):
    A, G, X, Y = env(C)
    er = C.k('eagerReduce', [0])
    andc = C.c('And'); intro = C.c('And.intro')
    def st(p): return C.eqnat(X, Y(p))
    ty = pi(A, lambda p: app(andc, st(p), st(p)))
    val = lam(A, lambda p: app(intro, st(p), st(p), under_eager(C, st(p), C.eqnat(X, X), C.reflnat(X)), C.reflnat(X)))
    C.thm('S.bad', ty, val)

@case('warm-whnf-memo-across-declarations-ok', 'accept',
      'WARM, the K rule (accepted by the official checker): declaration a reduces Eq.rec .. (e p) in plain mode (K fires: the indices X and Y p are convertible); '
      'declaration bad needs the same reduction under eagerReduce, where the indices are not convertible and the recursor '
      'stays stuck. A WHNF memo or a POS pair that survives the declaration reduces it')
def _(C):
    A, G, X, Y = env(C)
    er = C.k('eagerReduce', [0])
    # e : forall p, X = Y p  (an opaque theorem); R p := Eq.rec (motive := fun _ _ => Nat) 7 (e p) : Nat
    plain(C, A, X, Y, 'S.e')
    e = C.k('S.e')
    def R(p):
        return app(C.c('Eq.rec', [C.l1, C.l1]), C.nat, X, lam(C.nat, lambda b: lam(C.eqnat(X, b), lambda t: C.nat)), C.lit(7), Y(p), app(e, p))
    def st(p): return C.eqnat(R(p), C.lit(7))
    C.thm('S.a', pi(A, lambda p: st(p)), lam(A, lambda p: C.reflnat(C.lit(7))))
    C.thm('S.bad', pi(A, lambda p: st(p)), lam(A, lambda p: under_eager(C, st(p), C.eqnat(C.lit(7), C.lit(7)), C.reflnat(C.lit(7)))))

@case('warm-whnf-memo-eager-alone-ok', 'accept', 'the K reduction under eagerReduce alone (no earlier plain declaration): accepted by the official checker')
def _(C):
    A, G, X, Y = env(C)
    er = C.k('eagerReduce', [0])
    plain(C, A, X, Y, 'S.e')
    e = C.k('S.e')
    def R(p):
        return app(C.c('Eq.rec', [C.l1, C.l1]), C.nat, X, lam(C.nat, lambda b: lam(C.eqnat(X, b), lambda t: C.nat)), C.lit(7), Y(p), app(e, p))
    def st(p): return C.eqnat(R(p), C.lit(7))
    C.thm('S.bad', pi(A, lambda p: st(p)), lam(A, lambda p: under_eager(C, st(p), C.eqnat(C.lit(7), C.lit(7)), C.reflnat(C.lit(7)))))


def kenv(C):
    """e : forall p, X = Y p, OPAQUE (a theorem would unfold to Eq.refl); R p := Eq.rec (motive := fun _ _ => Nat) 7 (e p)"""
    A, G, X, Y = env(C)
    C.opaque('S.e', pi(A, lambda p: C.eqnat(X, Y(p))), lam(A, lambda p: C.reflnat(X)))
    e = C.k('S.e')
    def R(p):
        return app(C.c('Eq.rec', [C.l1, C.l1]), C.nat, X, lam(C.nat, lambda b: lam(C.eqnat(X, b), lambda t: C.nat)), C.lit(7), Y(p), app(e, p))
    def st(p): return C.eqnat(R(p), C.lit(7))
    plain = lam(A, lambda p: C.reflnat(C.lit(7)))
    eagr = lam(A, lambda p: under_eager(C, st(p), C.eqnat(C.lit(7), C.lit(7)), C.reflnat(C.lit(7))))
    return A, pi(A, lambda p: st(p)), plain, eagr

@case('krule-plain-ok', 'accept', 'K rule on an opaque proof of X = Y p in plain mode: the indices are convertible (same-head arguments, proof irrelevance), Eq.rec reduces')
def _(C):
    A, S, plain, eagr = kenv(C)
    C.thm('S.a', S, plain)
    C.thm('S.b', S, plain)

@case('krule-eager-alone-bad', 'reject', 'the same reduction needed under eagerReduce: the indices are not convertible there (the closed one folds first), Eq.rec stays stuck')
def _(C):
    A, S, plain, eagr = kenv(C)
    C.thm('S.bad', S, eagr)

@case('krule-memo-across-declarations-eager-bad', 'reject',
      'WARM, K rule: declaration a reduces Eq.rec .. (e p) in plain mode; declaration bad needs it under eagerReduce. A WHNF / CORE '
      'memo or a POS pair that survives the declaration reduces it')
def _(C):
    A, S, plain, eagr = kenv(C)
    C.thm('S.a', S, plain)
    C.thm('S.bad', S, eagr)

@case('krule-memo-across-binders-eager-bad', 'reject',
      'the same inside ONE declaration with two binders (canonical variables): first lambda plain, second lambda under eagerReduce')
def _(C):
    A, S, plain, eagr = kenv(C)
    andc = C.c('And'); intro = C.c('And.intro')
    C.thm('S.bad', app(andc, S, S), app(intro, S, S, plain, eagr))

@case('canon-fvar-pos-across-binders-eager-bad', 'reject',
      'CANONICAL FREE VARIABLES + PAIR TABLE, inside ONE declaration: the first lambda proves X = Y p in plain mode, the '
      'second lambda (another binder, for the official kernel another free variable, so its success cache has no entry) '
      'needs X = Y p under eagerReduce, which is false. Both binders are the canonical variable FVAR(And True True, 0), '
      'so the pair of the first scope answers in the second')
def _(C):
    A, G, X, Y = env(C)
    andc = C.c('And'); intro = C.c('And.intro')
    S = pi(A, lambda p: C.eqnat(X, Y(p)))
    first = lam(A, lambda p: C.reflnat(X))
    second = lam(A, lambda p: under_eager(C, C.eqnat(X, Y(p)), C.eqnat(X, X), C.reflnat(X)))
    C.thm('S.bad', app(andc, S, S), app(intro, S, S, first, second))

@case('canon-fvar-pos-across-binders-eager-first-bad', 'reject', 'the same with the eager component first')
def _(C):
    A, G, X, Y = env(C)
    andc = C.c('And'); intro = C.c('And.intro')
    S = pi(A, lambda p: C.eqnat(X, Y(p)))
    first = lam(A, lambda p: C.reflnat(X))
    second = lam(A, lambda p: under_eager(C, C.eqnat(X, Y(p)), C.eqnat(X, X), C.reflnat(X)))
    C.thm('S.bad', app(andc, S, S), app(intro, S, S, second, first))

@case('canon-fvar-pos-across-binders-plain-ok', 'accept', 'twin: both components in plain mode')
def _(C):
    A, G, X, Y = env(C)
    andc = C.c('And'); intro = C.c('And.intro')
    S = pi(A, lambda p: C.eqnat(X, Y(p)))
    first = lam(A, lambda p: C.reflnat(X))
    C.thm('S.ok', app(andc, S, S), app(intro, S, S, first, first))


if __name__ == '__main__':
    rows = emit_all(['warm-', 'canon-', 'krule-'])
    write_manifest(rows, 'batch7.tsv')
    print(len(rows), 'exports written to', OUT)
