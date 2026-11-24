#!/usr/bin/env python3
"""Hostile exports, batch 8: the OPEN flag against the official has_fvar. The official test looks at the term as
written; the machine's flag is computed on the value, after beta has discarded an argument."""
from common import *
from batch2 import and_rec, and_intro
from batch7 import env


@case('open-flag-discarded-fvar-ok', 'accept',
      'plain mode, the right side mentions the bound variable only inside a beta-redex that discards it: '
      'Nat.add (G ((fun z => h) p)) 0. Officially has_fvar holds (no Nat folding), the same-head arguments are compared and '
      'the proofs are irrelevant: accepted. A machine that evaluates first sees a closed value, folds the left side to 0 '
      'and fails against the stuck recursor: a wrong REJECT of the machine (the reference re-check refutes it)')
def _(C):
    A, G, X, Y = env(C)
    h = C.axiom('S.h', A)
    def Yd(p): return app(C.c('Nat.add'), app(G, app(lam(A, lambda z: h()), p)), C.lit(0))
    C.thm('S.ok', pi(A, lambda p: C.eqnat(X, Yd(p))), lam(A, lambda p: C.reflnat(X)))

@case('open-flag-closed-bad', 'reject', 'twin without the variable: X against Nat.add (G h) 0, both closed: the official kernel folds and rejects')
def _(C):
    A, G, X, Y = env(C)
    h = C.axiom('S.h', A)
    C.thm('S.bad', C.eqnat(X, app(C.c('Nat.add'), app(G, h()), C.lit(0))), C.reflnat(X))

@case('open-flag-let-variable-ok', 'accept',
      'the let variable of infer_let is a free variable for the official kernel: inside let q := h; .. the pair X, Nat.add (G q) 0 '
      'is compared without folding (to be confirmed by the official verdict)')
def _(C):
    A, G, X, Y = env(C)
    h = C.axiom('S.h', A)
    # (fun (e : X = Y q) => True.intro) (Eq.refl X) under let q := h: the argument check compares X = X with X = Y q
    def body(q):
        st = C.eqnat(X, Y(q))
        return app(lam(st, lambda e: C.trivial), C.reflnat(X))
    C.thm('S.ok', C.true, let_(A, h(), lambda q: body(q)))


if __name__ == '__main__':
    rows = emit_all(['open-flag-'])
    write_manifest(rows, 'batch8.tsv')
    print(len(rows), 'exports written to', OUT)
