#!/usr/bin/env python3
"""Hostile exports, batch 3: static facts (step 2): proof positions skipped by the agreement rule where the
official kernel cannot show the two propositions convertible; the Prop mask of structure eta; the static proof filter."""
from common import *
from batch2 import and_rec, and_intro


def _setup(C):
    A = app(C.c('And'), C.true, C.true)
    h = C.axiom('S.h', A)
    Q = C.axiom('S.Q', arrow(C.nat, C.prop))
    return A, h, Q


@case('facts-agreement-noncongruent-app-bad', 'reject',
      'B3 (agreement rule): w : (x : And True True) -> Q (And.rec f x) -> Nat; w (And.intro ..) p1 against w h p2. '
      'Both positions are proof positions; the official kernel compares p1 and p2 by their types, which it cannot '
      'show convertible (And.rec f (And.intro ..) reduces to 5, And.rec f h is stuck)')
def _(C):
    A, h, Q = _setup(C)
    w = C.axiom('S.w', pi(A, lambda x: arrow(app(Q(), and_rec(C, x)), C.nat)))
    p1 = C.axiom('S.p1', app(Q(), and_rec(C, and_intro(C))))
    p2 = C.axiom('S.p2', app(Q(), and_rec(C, h())))
    for i in range(12):   # warm-up: let facts be derived and used
        C.thm('S.ok%d' % i, C.eqnat(app(w(), h(), p2()), app(w(), h(), p2())), C.reflnat(app(w(), h(), p2())))
    lhs = app(w(), and_intro(C), p1()); rhs = app(w(), h(), p2())
    C.thm('S.bad', C.eqnat(lhs, rhs), C.reflnat(lhs))

@case('facts-agreement-congruent-ok', 'accept', 'twin: w h p2 against w h2 p3 with both recursors stuck: the official kernel compares the types argument-wise')
def _(C):
    A, h, Q = _setup(C)
    h2 = C.axiom('S.h2', A)
    w = C.axiom('S.w', pi(A, lambda x: arrow(app(Q(), and_rec(C, x)), C.nat)))
    p2 = C.axiom('S.p2', app(Q(), and_rec(C, h())))
    p3 = C.axiom('S.p3', app(Q(), and_rec(C, h2())))
    lhs = app(w(), h(), p2()); rhs = app(w(), h2(), p3())
    C.thm('S.ok', C.eqnat(lhs, rhs), C.reflnat(lhs))

@case('facts-agreement-noncongruent-samedef-bad', 'reject',
      'B3 under SAMEDEF: the same with w an opaque-bodied definition whose unfolding does not help (it calls an axiom with both arguments)')
def _(C):
    A, h, Q = _setup(C)
    wty = pi(A, lambda x: arrow(app(Q(), and_rec(C, x)), C.nat))
    w0 = C.axiom('S.w0', wty)
    C.defn('S.w', wty, lam(A, lambda x: lam(app(Q(), and_rec(C, x)), lambda p: app(w0(), x, p))), hints=1)
    w = C.k('S.w')
    p1 = C.axiom('S.p1', app(Q(), and_rec(C, and_intro(C))))
    p2 = C.axiom('S.p2', app(Q(), and_rec(C, h())))
    for i in range(12):
        C.thm('S.ok%d' % i, C.eqnat(app(w, h(), p2()), app(w, h(), p2())), C.reflnat(app(w, h(), p2())))
    lhs = app(w, and_intro(C), p1()); rhs = app(w, h(), p2())
    C.thm('S.bad', C.eqnat(lhs, rhs), C.reflnat(lhs))

@case('facts-etas-prop-mask-noncongruent-bad', 'reject',
      'the Prop mask of structure eta: S.mk (And.intro ..) p1 against an axiom s : S, where the proof field of s has a '
      'type the official kernel cannot show convertible to the type of p1')
def _(C):
    X = C.X
    A, h, Q = _setup(C)
    # S : Type, mk : (x : And True True) -> Q (And.rec f x) -> Nat -> S
    mk_inductive(X, 'S.S', [], [], [], X.lnum(1),
                 [('S.S.mk', [A, lambda x: app(Q(), and_rec(C, x)), lambda x, p: C.nat], lambda *a: [])])
    S = C.k('S.S')
    s = C.axiom('S.s', S)
    p1 = C.axiom('S.p1', app(Q(), and_rec(C, and_intro(C))))
    for i in range(12):
        v = app(C.k('S.S.mk'), proj('S.S', 0, s()), proj('S.S', 1, s()), proj('S.S', 2, s()))
        C.thm('S.ok%d' % i, C.eq(C.l1, S, v, s()), C.refl(C.l1, S, s()))
    lhs = app(C.k('S.S.mk'), and_intro(C), p1(), proj('S.S', 2, s()))
    C.thm('S.bad', C.eq(C.l1, S, lhs, s()), C.refl(C.l1, S, s()))

@case('facts-etas-prop-mask-data-bad', 'reject', 'the Prop mask of structure eta must not skip the data field: S.mk s.1 s.2 7 against s')
def _(C):
    X = C.X
    A, h, Q = _setup(C)
    mk_inductive(X, 'S.S', [], [], [], X.lnum(1),
                 [('S.S.mk', [A, lambda x: app(Q(), and_rec(C, x)), lambda x, p: C.nat], lambda *a: [])])
    S = C.k('S.S')
    s = C.axiom('S.s', S)
    for i in range(12):
        v = app(C.k('S.S.mk'), proj('S.S', 0, s()), proj('S.S', 1, s()), proj('S.S', 2, s()))
        C.thm('S.ok%d' % i, C.eq(C.l1, S, v, s()), C.refl(C.l1, S, s()))
    lhs = app(C.k('S.S.mk'), proj('S.S', 0, s()), proj('S.S', 1, s()), C.lit(7))
    C.thm('S.bad', C.eq(C.l1, S, lhs, s()), C.refl(C.l1, S, s()))

@case('facts-static-proof-wrong-type-bad', 'reject',
      'B7 (static PROOF): two proofs of DIFFERENT propositions below a function that takes any proposition: g P p against g Q q')
def _(C):
    X = C.X
    g = C.axiom('S.g', pi(C.prop, lambda P: arrow(P, C.nat)))
    P = C.axiom('S.P', C.prop); Q = C.axiom('S.Q', C.prop)
    p = C.axiom('S.p', P()); q = C.axiom('S.q', Q())
    p2 = C.axiom('S.p2', P())
    for i in range(12):
        C.thm('S.ok%d' % i, C.eqnat(app(g(), P(), p()), app(g(), P(), p2())), C.reflnat(app(g(), P(), p())))
    C.thm('S.bad', C.eqnat(app(g(), P(), p()), app(g(), Q(), q())), C.reflnat(app(g(), P(), p())))

@case('facts-prop-position-dependent-on-data-bad', 'reject',
      'B3: a proof position whose proposition depends on a DATA position that differs: v 1 (pf 1) against v 2 (pf 2), '
      'v : (n : Nat) -> Q n -> Nat')
def _(C):
    Q = C.axiom('S.Q', arrow(C.nat, C.prop))
    v = C.axiom('S.v', pi(C.nat, lambda n: arrow(app(Q(), n), C.nat)))
    pf = C.axiom('S.pf', pi(C.nat, lambda n: app(Q(), n)))
    for i in range(12):
        a = app(v(), C.lit(i), app(pf(), C.lit(i)))
        C.thm('S.ok%d' % i, C.eqnat(a, a), C.reflnat(a))
    a = app(v(), C.lit(1), app(pf(), C.lit(1))); b = app(v(), C.lit(2), app(pf(), C.lit(2)))
    C.thm('S.bad', C.eqnat(a, b), C.reflnat(a))

@case('facts-unread-then-prop-bad', 'reject',
      'B4 + B3: a definition that does not read its first parameter but whose proof parameter depends on it; the '
      'body keeps the second and third: d 1 p 5 against d 2 q 6')
def _(C):
    Q = C.axiom('S.Q', arrow(C.nat, C.prop))
    pf = C.axiom('S.pf', pi(C.nat, lambda n: app(Q(), n)))
    k = C.axiom('S.k', arrow(C.nat, C.nat))
    C.defn('S.d', pi(C.nat, lambda n: arrow(app(Q(), n), arrow(C.nat, C.nat))),
           lam(C.nat, lambda n: lam(app(Q(), n), lambda p: lam(C.nat, lambda m: app(k(), m)))), hints=1)
    d = C.k('S.d')
    for i in range(12):
        a = app(d, C.lit(i), app(pf(), C.lit(i)), C.lit(5)); b = app(d, C.lit(i + 1), app(pf(), C.lit(i + 1)), C.lit(5))
        C.thm('S.ok%d' % i, C.eqnat(a, b), C.reflnat(a))
    a = app(d, C.lit(1), app(pf(), C.lit(1)), C.lit(5)); b = app(d, C.lit(2), app(pf(), C.lit(2)), C.lit(6))
    C.thm('S.bad', C.eqnat(a, b), C.reflnat(a))

@case('facts-unread-opaque-head-bad', 'reject', 'B4: UNREAD must not be used for an opaque constant: o 1 against o 2')
def _(C):
    C.opaque('S.o', arrow(C.nat, C.nat), lam(C.nat, lambda n: C.zero))
    o = C.k('S.o')
    for i in range(12):
        C.thm('S.ok%d' % i, C.eqnat(app(o, C.lit(i)), app(o, C.lit(i))), C.reflnat(app(o, C.lit(i))))
    C.thm('S.bad', C.eqnat(app(o, C.lit(1)), app(o, C.lit(2))), C.reflnat(app(o, C.lit(1))))

@case('facts-unread-partial-app-bad', 'reject', 'B4: an under-applied definition: K2 1 against K2 2 as functions is fine, but an axiom applied to them must compare them; here the bodies differ')
def _(C):
    NN = arrow(C.nat, C.nat)
    C.defn('S.K2', arrow(C.nat, NN), lam(C.nat, lambda a: lam(C.nat, lambda b: a)), hints=1)
    K2 = C.k('S.K2')
    g = C.axiom('S.g', arrow(NN, C.nat))
    for i in range(12):
        C.thm('S.ok%d' % i, C.eqnat(app(g(), app(K2, C.lit(i))), app(g(), app(K2, C.lit(i)))), C.reflnat(app(g(), app(K2, C.lit(i)))))
    C.thm('S.bad', C.eqnat(app(g(), app(K2, C.lit(1))), app(g(), app(K2, C.lit(2)))), C.reflnat(app(g(), app(K2, C.lit(1)))))

@case('facts-unread-second-read-first-bad', 'reject', 'B4: K2 a b := a: K2 1 7 against K2 2 7 (the READ position differs)')
def _(C):
    NN = arrow(C.nat, C.nat)
    C.defn('S.K2', arrow(C.nat, NN), lam(C.nat, lambda a: lam(C.nat, lambda b: a)), hints=1)
    K2 = C.k('S.K2')
    for i in range(12):
        C.thm('S.ok%d' % i, C.eqnat(app(K2, C.lit(i), C.lit(1)), app(K2, C.lit(i), C.lit(2))), C.reflnat(app(K2, C.lit(i), C.lit(1))))
    C.thm('S.bad', C.eqnat(app(K2, C.lit(1), C.lit(7)), app(K2, C.lit(2), C.lit(7))), C.reflnat(app(K2, C.lit(1), C.lit(7))))

@case('facts-unread-overapplied-bad', 'reject', 'B4: K1 a := fun b => b (reads nothing of a) over-applied: K1 0 1 against K1 0 2')
def _(C):
    NN = arrow(C.nat, C.nat)
    C.defn('S.K1', arrow(C.nat, NN), lam(C.nat, lambda a: lam(C.nat, lambda b: b)), hints=1)
    K1 = C.k('S.K1')
    for i in range(12):
        C.thm('S.ok%d' % i, C.eqnat(app(K1, C.lit(i), C.lit(1)), app(K1, C.lit(i + 1), C.lit(1))), C.reflnat(app(K1, C.lit(i), C.lit(1))))
    C.thm('S.bad', C.eqnat(app(K1, C.zero, C.lit(1)), app(K1, C.zero, C.lit(2))), C.reflnat(app(K1, C.zero, C.lit(1))))


if __name__ == '__main__':
    rows = emit_all(['facts-'])
    write_manifest(rows, 'batch3.tsv')
    print(len(rows), 'exports written to', OUT)
