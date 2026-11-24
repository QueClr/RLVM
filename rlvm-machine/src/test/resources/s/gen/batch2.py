#!/usr/bin/env python3
"""Hostile exports, batch 2: speculative probes (B1, B12, B2) against the official non-congruences."""
from common import *


def and_rec(C, major):
    A = app(C.c('And'), C.true, C.true)
    return app(C.c('And.rec', [C.l1]), C.true, C.true, lam(A, lambda t: C.nat),
               lam(C.true, lambda a: lam(C.true, lambda b: C.lit(5))), major)

def and_intro(C):
    return app(C.c('And.intro'), C.true, C.true, C.trivial, C.trivial)


@case('probe-b1-rec-proof-major-bad', 'reject',
      'B1 (same-head probe on a recursor): And.rec f (And.intro ..) against And.rec f h, h an axiom. The arguments '
      'are pairwise convertible (proof irrelevance) but the official kernel reduces the left side to 5 and leaves the right stuck')
def _(C):
    A = app(C.c('And'), C.true, C.true)
    h = C.axiom('S.h', A)
    lhs = and_rec(C, and_intro(C)); rhs = and_rec(C, h())
    C.thm('S.bad', C.eqnat(lhs, rhs), C.reflnat(lhs))

@case('probe-b1-rec-proof-major-flipped-bad', 'reject', 'the same with the stuck side on the left')
def _(C):
    A = app(C.c('And'), C.true, C.true)
    h = C.axiom('S.h', A)
    lhs = and_rec(C, h()); rhs = and_rec(C, and_intro(C))
    C.thm('S.bad', C.eqnat(lhs, rhs), C.reflnat(lhs))

@case('probe-b1-rec-proof-major-ok', 'accept', 'twin: And.rec f h against And.rec f h2, both stuck: the official kernel compares the arguments')
def _(C):
    A = app(C.c('And'), C.true, C.true)
    h = C.axiom('S.h', A); h2 = C.axiom('S.h2', A)
    lhs = and_rec(C, h()); rhs = and_rec(C, h2())
    C.thm('S.ok', C.eqnat(lhs, rhs), C.reflnat(lhs))

@case('probe-b12-closure-proof-slot-bad', 'reject',
      'B12 (closure congruence): the same body g (And.rec f #0) under frames [And.intro ..] and [h]: the slots are '
      'convertible by proof irrelevance, the instantiated terms are not convertible for the official kernel')
def _(C):
    A = app(C.c('And'), C.true, C.true)
    h = C.axiom('S.h', A)
    g = C.axiom('S.g', arrow(C.nat, C.nat))
    body = lam(A, lambda x: app(g(), and_rec(C, x)))
    lhs = app(body, and_intro(C)); rhs = app(body, h())
    C.thm('S.bad', C.eqnat(lhs, rhs), C.reflnat(lhs))

@case('probe-b12-let-proof-slot-bad', 'reject', 'B12 with let: let x := And.intro ..; g (And.rec f x) against let x := h; g (And.rec f x)')
def _(C):
    A = app(C.c('And'), C.true, C.true)
    h = C.axiom('S.h', A)
    g = C.axiom('S.g', arrow(C.nat, C.nat))
    lhs = let_(A, and_intro(C), lambda x: app(g(), and_rec(C, x)))
    rhs = let_(A, h(), lambda x: app(g(), and_rec(C, x)))
    C.thm('S.bad', C.eqnat(lhs, rhs), C.reflnat(lhs))

@case('probe-b12-lam-closure-proof-slot-bad', 'reject', 'B12 on LAM closures: fun n => And.rec f x under [And.intro ..] and under [h], below a constructor')
def _(C):
    A = app(C.c('And'), C.true, C.true)
    h = C.axiom('S.h', A)
    NN = arrow(C.nat, C.nat)
    g = C.axiom('S.g', arrow(NN, C.nat))
    body = lam(A, lambda x: app(g(), lam(C.nat, lambda n: and_rec(C, x))))
    lhs = app(body, and_intro(C)); rhs = app(body, h())
    C.thm('S.bad', C.eqnat(lhs, rhs), C.reflnat(lhs))

@case('probe-b1-def-proof-arg-ok', 'accept', 'same-head definitions with convertible arguments: the official kernel compares arguments first')
def _(C):
    A = app(C.c('And'), C.true, C.true)
    h = C.axiom('S.h', A)
    C.defn('S.F', arrow(A, C.nat), lam(A, lambda x: and_rec(C, x)), hints=1)
    F = C.k('S.F')
    lhs = app(F, and_intro(C)); rhs = app(F, h())
    C.thm('S.ok', C.eqnat(lhs, rhs), C.reflnat(lhs))

@case('probe-b1-abbrev-proof-arg', 'reject', 'same-head ABBREV definitions with convertible arguments: no argument comparison in the official lazy delta step for abbrev (to be confirmed by the official verdict)')
def _(C):
    A = app(C.c('And'), C.true, C.true)
    h = C.axiom('S.h', A)
    C.defn('S.F', arrow(A, C.nat), lam(A, lambda x: and_rec(C, x)), hints='abbrev')
    F = C.k('S.F')
    lhs = app(F, and_intro(C)); rhs = app(F, h())
    C.thm('S.t', C.eqnat(lhs, rhs), C.reflnat(lhs))

@case('probe-b1-theorem-head', 'reject', 'same-head probe where the head is a THEOREM-like opaque-hint definition (to be confirmed by the official verdict)')
def _(C):
    A = app(C.c('And'), C.true, C.true)
    h = C.axiom('S.h', A)
    C.defn('S.F', arrow(A, C.nat), lam(A, lambda x: and_rec(C, x)), hints='opaque')
    F = C.k('S.F')
    lhs = app(F, and_intro(C)); rhs = app(F, h())
    C.thm('S.t', C.eqnat(lhs, rhs), C.reflnat(lhs))

@case('probe-b1-rec-under-ctor-bad', 'reject', 'the recursor pair below a constructor: Nat.succ (And.rec f (And.intro ..)) against Nat.succ (And.rec f h)')
def _(C):
    A = app(C.c('And'), C.true, C.true)
    h = C.axiom('S.h', A)
    lhs = app(C.succ, and_rec(C, and_intro(C))); rhs = app(C.succ, and_rec(C, h()))
    C.thm('S.bad', C.eqnat(lhs, rhs), C.reflnat(lhs))

@case('probe-b1-rec-under-axiom-bad', 'reject', 'the recursor pair below an axiom function')
def _(C):
    A = app(C.c('And'), C.true, C.true)
    h = C.axiom('S.h', A)
    g = C.axiom('S.g', arrow(C.nat, C.nat))
    lhs = app(g(), and_rec(C, and_intro(C))); rhs = app(g(), and_rec(C, h()))
    C.thm('S.bad', C.eqnat(lhs, rhs), C.reflnat(lhs))


if __name__ == '__main__':
    rows = emit_all()
    write_manifest(rows, 'batch2.tsv')
    print(len(rows), 'exports written to', OUT)
