#!/usr/bin/env python3
"""Hostile exports, batch 6: further non-congruence attacks (B4 unread positions; transitivity through a regular
definition) and their accepted twins."""
from common import *
from batch2 import and_rec, and_intro


@case('facts-unread-noncongruent-bad', 'reject',
      'B4 (unread position under SAMEDEF): d (u : Nat) (x : And True True) : Nat := And.rec f x; d 0 (And.intro ..) against '
      'd 1 h. The official argument comparison fails on 0 / 1, both sides unfold, and 5 against a stuck recursor is false; '
      'skipping the unread position and comparing the proofs answers T')
def _(C):
    A = app(C.c('And'), C.true, C.true)
    h = C.axiom('S.h', A)
    C.defn('S.d', arrow(C.nat, arrow(A, C.nat)), lam(C.nat, lambda u: lam(A, lambda x: and_rec(C, x))), hints=1)
    d = C.k('S.d')
    for i in range(12):
        C.thm('S.ok%d' % i, C.eqnat(app(d, C.lit(i), h()), app(d, C.lit(i + 1), h())), C.reflnat(app(d, C.lit(i), h())))
    lhs = app(d, C.lit(0), and_intro(C)); rhs = app(d, C.lit(1), h())
    C.thm('S.bad', C.eqnat(lhs, rhs), C.reflnat(lhs))

@case('facts-unread-congruent-ok', 'accept', 'twin: d 0 h against d 1 h: the unread position differs, both sides unfold to the same stuck term')
def _(C):
    A = app(C.c('And'), C.true, C.true)
    h = C.axiom('S.h', A)
    C.defn('S.d', arrow(C.nat, arrow(A, C.nat)), lam(C.nat, lambda u: lam(A, lambda x: and_rec(C, x))), hints=1)
    d = C.k('S.d')
    for i in range(12):
        C.thm('S.ok%d' % i, C.eqnat(app(d, C.lit(i), h()), app(d, C.lit(i + 1), h())), C.reflnat(app(d, C.lit(i), h())))

@case('nontransitive-regular-def-ok', 'accept',
      'the official same-head argument comparison for a REGULAR definition: d (And.intro ..) against d h is accepted, and '
      'd (And.intro ..) against 5 is accepted (unfolding)')
def _(C):
    A = app(C.c('And'), C.true, C.true)
    h = C.axiom('S.h', A)
    C.defn('S.d', arrow(A, C.nat), lam(A, lambda x: and_rec(C, x)), hints=1)
    d = C.k('S.d')
    C.thm('S.t1', C.eqnat(app(d, and_intro(C)), app(d, h())), C.reflnat(app(d, and_intro(C))))
    C.thm('S.t2', C.eqnat(app(d, and_intro(C)), C.lit(5)), C.reflnat(C.lit(5)))

@case('nontransitive-regular-def-bad', 'reject',
      'the official relation is not transitive: d h against 5 is rejected although d h = d (And.intro ..) = 5 are both accepted; '
      'a pair table or a union of accepted pairs must not close them transitively (warm sessions keep the pairs)')
def _(C):
    A = app(C.c('And'), C.true, C.true)
    h = C.axiom('S.h', A)
    C.defn('S.d', arrow(A, C.nat), lam(A, lambda x: and_rec(C, x)), hints=1)
    d = C.k('S.d')
    for i in range(3):
        C.thm('S.t1_%d' % i, C.eqnat(app(d, and_intro(C)), app(d, h())), C.reflnat(app(d, and_intro(C))))
        C.thm('S.t2_%d' % i, C.eqnat(app(d, and_intro(C)), C.lit(5)), C.reflnat(C.lit(5)))
        C.thm('S.t3_%d' % i, C.eqnat(app(d, h()), app(d, and_intro(C))), C.reflnat(app(d, h())))
    C.thm('S.bad', C.eqnat(app(d, h()), C.lit(5)), C.reflnat(C.lit(5)))

@case('nontransitive-same-decl-bad', 'reject',
      'the same inside ONE declaration: a pair of proofs first makes the kernel accept d (And.intro ..) = d h and '
      'd (And.intro ..) = 5, then d h = 5 is needed')
def _(C):
    A = app(C.c('And'), C.true, C.true)
    h = C.axiom('S.h', A)
    C.defn('S.d', arrow(A, C.nat), lam(A, lambda x: and_rec(C, x)), hints=1)
    d = C.k('S.d')
    e1 = C.eqnat(app(d, and_intro(C)), app(d, h())); p1 = C.reflnat(app(d, and_intro(C)))
    e2 = C.eqnat(app(d, and_intro(C)), C.lit(5)); p2 = C.reflnat(C.lit(5))
    e3 = C.eqnat(app(d, h()), C.lit(5)); p3 = C.reflnat(C.lit(5))
    andc = C.c('And'); intro = C.c('And.intro')
    ty = app(andc, e1, app(andc, e2, e3))
    val = app(intro, e1, app(andc, e2, e3), p1, app(intro, e2, e3, p2, p3))
    C.thm('S.bad', ty, val)

@case('nontransitive-same-decl-ok', 'accept', 'twin: only the two accepted equations in one declaration')
def _(C):
    A = app(C.c('And'), C.true, C.true)
    h = C.axiom('S.h', A)
    C.defn('S.d', arrow(A, C.nat), lam(A, lambda x: and_rec(C, x)), hints=1)
    d = C.k('S.d')
    e1 = C.eqnat(app(d, and_intro(C)), app(d, h())); p1 = C.reflnat(app(d, and_intro(C)))
    e2 = C.eqnat(app(d, and_intro(C)), C.lit(5)); p2 = C.reflnat(C.lit(5))
    andc = C.c('And'); intro = C.c('And.intro')
    C.thm('S.ok', app(andc, e1, e2), app(intro, e1, e2, p1, p2))


if __name__ == '__main__':
    rows = emit_all(['facts-unread-noncongruent', 'facts-unread-congruent', 'nontransitive-'])
    write_manifest(rows, 'batch6.tsv')
    print(len(rows), 'exports written to', OUT)
