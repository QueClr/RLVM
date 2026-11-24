#!/usr/bin/env python3
"""Hostile exports of package E1 (eager reduction ON the machine, Op.SW_EAGER): deferred eager comparisons,
recorded mode-dependent points of the plain phase, the K rule, binder scopes, sessions across declarations.

Built with package S's builder (work/e1/tools/lk.py is a copy of rlvm-machine/src/test/resources/s/gen/lk.py;
common.py, batch2.py and batch7.py likewise).  Run:  python3 work/e1/gen/batchE.py   -> work/e1/exports/*.ndjson and
work/e1/exports/batchE.tsv (name, EXPECTED verdict, attack).  The manifest work/e1/manifest.tsv holds the OFFICIAL
verdicts (work/e1/official.sh)."""
from common import *
from batch2 import and_intro
from batch7 import env, plain, kenv
from batch7 import under_eager as under_eager_redex
from batch7 import eager as eager_redex


def eager(C, A, X, Y, name):
    """theorem name : forall p, X = Y p := fun p => S.use (X = Y p) (eagerReduce (X = X) (Eq.refl X))"""
    C.thm(name, pi(A, lambda p: C.eqnat(X, Y(p))), lam(A, lambda p: under_eager(C, C.eqnat(X, Y(p)), C.eqnat(X, X), C.reflnat(X))))


def under_eager(C, expected, stated, proof):
    """S.use expected (eagerReduce stated proof), S.use : forall (P : Prop), P -> P an axiom: the form real proofs have (the
    eagerReduce application is an argument of a constant); the official infer_app compares `stated` with the domain
    `expected` in eager-reduction mode and the type of the application is `expected`. (batch7.under_eager uses a beta-redex
    (fun (h : expected) => h) (eagerReduce stated proof) instead; the cases named *-redex-* keep that form.)"""
    if not getattr(C, '_use', None):
        C._use = C.axiom('S.use', pi(C.prop, lambda P: arrow(P, P)))
    return app(C._use(), expected, app(C.k('eagerReduce', [0]), stated, proof))


def and_rec5(C, major):
    A = app(C.c('And'), C.true, C.true)
    return app(C.c('And.rec', [C.l1]), C.true, C.true, lam(A, lambda t: C.nat),
               lam(C.true, lambda a: lam(C.true, lambda b: C.lit(5))), major)


def both(C, P1, P2, p1, p2):
    """And.intro P1 P2 p1 p2 : And P1 P2 - the official kernel checks p1, then p2 (arguments from left to right)"""
    return app(C.c('And'), P1, P2), app(C.c('And.intro'), P1, P2, p1, p2)


# ------------------------------------------------------------------------------------------------------------
# 0. Not about eager reduction: the type the machine gives a beta-redex whose body is the bound variable
# ------------------------------------------------------------------------------------------------------------

def triple(C):
    A = app(C.c('And'), C.true, C.true)
    h = C.axiom('S.h', A)
    C.defn('S.d', arrow(A, C.nat), lam(A, lambda x: and_rec5(C, x)), hints=1)
    d = C.k('S.d')
    return A, h, d

@case('e1-app-type-is-domain-bad', 'reject',
      'PLAIN mode, no eagerReduce. (fun (q : d h = d h) => q) (Eq.refl (d intro)) : d h = 5. The official infer_app gives the '
      'application the type of the lambda body under a variable of the DOMAIN type, d h = d h, and d h against 5 is false. An '
      'engine that types the body under the actual argument gets d intro = d intro, and d intro against d h and against 5 are '
      'both true (the official relation is not transitive)')
def _(C):
    A, h, d = triple(C)
    di = app(d, and_intro(C)); dh = app(d, h())
    C.thm('S.bad', C.eqnat(dh, C.lit(5)), app(lam(C.eqnat(dh, dh), lambda q: q), C.reflnat(di)))

@case('e1-app-type-is-domain-ok', 'accept', 'twin: the same redex against d h = d h (the domain itself)')
def _(C):
    A, h, d = triple(C)
    di = app(d, and_intro(C)); dh = app(d, h())
    C.thm('S.ok', C.eqnat(dh, dh), app(lam(C.eqnat(dh, dh), lambda q: q), C.reflnat(di)))

@case('e1-app-type-is-domain-let-bad', 'reject', 'the same through a let: let q : d h = d h := Eq.refl (d intro); q : d h = 5')
def _(C):
    A, h, d = triple(C)
    di = app(d, and_intro(C)); dh = app(d, h())
    C.thm('S.bad', C.eqnat(dh, C.lit(5)), let_(C.eqnat(dh, dh), C.reflnat(di), lambda q: q))

@case('e1-app-type-is-domain-under-lambda-bad', 'reject',
      'the same redex as the body of a lambda: fun (u : Nat) => (fun (q : d h = d h) => q) (Eq.refl (d intro)) : forall u, d h = 5 '
      '(the type of the lambda body is asked for again without checking: an engine that then types the variable q by the '
      'value it is bound to gets d intro = d intro)')
def _(C):
    A, h, d = triple(C)
    di = app(d, and_intro(C)); dh = app(d, h())
    C.thm('S.bad', pi(C.nat, lambda u: C.eqnat(dh, C.lit(5))), lam(C.nat, lambda u: app(lam(C.eqnat(dh, dh), lambda q: q), C.reflnat(di))))

@case('e1-app-type-is-domain-let-ok', 'accept', 'twin of the let form: let q : d h = d h := Eq.refl (d intro); q : d h = d h')
def _(C):
    A, h, d = triple(C)
    di = app(d, and_intro(C)); dh = app(d, h())
    C.thm('S.ok', C.eqnat(dh, dh), let_(C.eqnat(dh, dh), C.reflnat(di), lambda q: q))

@case('e1-app-type-is-domain-let-under-lambda-bad', 'reject', 'the let form as the body of a lambda')
def _(C):
    A, h, d = triple(C)
    di = app(d, and_intro(C)); dh = app(d, h())
    C.thm('S.bad', pi(C.nat, lambda u: C.eqnat(dh, C.lit(5))), lam(C.nat, lambda u: let_(C.eqnat(dh, dh), C.reflnat(di), lambda q: q)))

@case('e1-app-type-is-domain-defarg-bad', 'reject',
      'the redex with an argument that is the application of a DEFINITION (the shape of eagerReduce _ _): '
      '(fun (q : d h = d h) => q) (S.idv (d intro = d intro) (Eq.refl (d intro))) : d h = 5, S.idv P p := p')
def _(C):
    A, h, d = triple(C)
    di = app(d, and_intro(C)); dh = app(d, h())
    C.defn('S.idv', pi(C.prop, lambda P: arrow(P, P)), lam(C.prop, lambda P: lam(P, lambda p: p)), hints=1)
    idv = C.k('S.idv')
    C.thm('S.bad', C.eqnat(dh, C.lit(5)), app(lam(C.eqnat(dh, dh), lambda q: q), app(idv, C.eqnat(di, di), C.reflnat(di))))

@case('e1-app-type-is-domain-defarg-under-lambda-bad', 'reject', 'the same as the body of a lambda')
def _(C):
    A, h, d = triple(C)
    di = app(d, and_intro(C)); dh = app(d, h())
    C.defn('S.idv', pi(C.prop, lambda P: arrow(P, P)), lam(C.prop, lambda P: lam(P, lambda p: p)), hints=1)
    idv = C.k('S.idv')
    C.thm('S.bad', pi(C.nat, lambda u: C.eqnat(dh, C.lit(5))),
          lam(C.nat, lambda u: app(lam(C.eqnat(dh, dh), lambda q: q), app(idv, C.eqnat(di, di), C.reflnat(di)))))

@case('e1-app-type-is-domain-axarg-bad', 'reject', 'the redex with an argument that is an axiom constant of type d intro = d intro')
def _(C):
    A, h, d = triple(C)
    di = app(d, and_intro(C)); dh = app(d, h())
    ax = C.axiom('S.ax', C.eqnat(di, di))
    C.thm('S.bad', C.eqnat(dh, C.lit(5)), app(lam(C.eqnat(dh, dh), lambda q: q), ax()))

@case('e1-app-type-is-domain-axarg-under-lambda-bad', 'reject', 'the same as the body of a lambda')
def _(C):
    A, h, d = triple(C)
    di = app(d, and_intro(C)); dh = app(d, h())
    ax = C.axiom('S.ax', C.eqnat(di, di))
    C.thm('S.bad', pi(C.nat, lambda u: C.eqnat(dh, C.lit(5))), lam(C.nat, lambda u: app(lam(C.eqnat(dh, dh), lambda q: q), ax())))

@case('e1-app-type-is-domain-fvararg-bad', 'reject',
      'the argument is a lambda-bound variable: fun (w : d intro = d intro) => (fun (q : d h = d h) => q) w : (d intro = d intro) -> d h = 5')
def _(C):
    A, h, d = triple(C)
    di = app(d, and_intro(C)); dh = app(d, h())
    C.thm('S.bad', arrow(C.eqnat(di, di), C.eqnat(dh, C.lit(5))), lam(C.eqnat(di, di), lambda w: app(lam(C.eqnat(dh, dh), lambda q: q), w)))

@case('e1-app-type-is-domain-open-domain-bad', 'reject',
      'the redex under a binder that its DOMAIN mentions: fun (w : A) => (fun (q : d w = d w) => q) (Eq.refl (d intro)) : '
      'forall w, d w = 5. Official: the application has the type d w = d w, and d w against 5 is false (w is free, the '
      'recursor is stuck). An engine that re-infers the type of the lambda body without checking and then types q by the '
      'argument gets d intro = d intro, which is accepted against d w = 5')
def _(C):
    A, h, d = triple(C)
    di = app(d, and_intro(C))
    dw = lambda w: app(d, w)
    C.thm('S.bad', pi(A, lambda w: C.eqnat(dw(w), C.lit(5))), lam(A, lambda w: app(lam(C.eqnat(dw(w), dw(w)), lambda q: q), C.reflnat(di))))

@case('e1-app-type-is-domain-open-domain-ok', 'accept', 'twin: the same redex against forall w, d w = d w')
def _(C):
    A, h, d = triple(C)
    di = app(d, and_intro(C))
    dw = lambda w: app(d, w)
    C.thm('S.ok', pi(A, lambda w: C.eqnat(dw(w), dw(w))), lam(A, lambda w: app(lam(C.eqnat(dw(w), dw(w)), lambda q: q), C.reflnat(di))))

@case('e1-app-type-is-domain-open-domain-let-bad', 'reject', 'the let form under the binder: fun w => let q : d w = d w := Eq.refl (d intro); q : forall w, d w = 5')
def _(C):
    A, h, d = triple(C)
    di = app(d, and_intro(C))
    dw = lambda w: app(d, w)
    C.thm('S.bad', pi(A, lambda w: C.eqnat(dw(w), C.lit(5))), lam(A, lambda w: let_(C.eqnat(dw(w), dw(w)), C.reflnat(di), lambda q: q)))

@case('e1-lam-own-domain-vs-declared-bad', 'reject',
      'a LAMBDA against a declared Pi type with another (convertible) domain: theorem : (q : d intro = d intro) -> d intro = 5 := '
      'fun (q : d h = d h) => q. Official: the lambda has the type (q : d h = d h) -> d h = d h (the body is typed under ITS OWN '
      'domain); the domains are convertible, the bodies d h = d h against d intro = 5 are not. An engine that types the body '
      'under a variable of the DECLARED domain gets d intro = d intro against d intro = 5: true')
def _(C):
    A, h, d = triple(C)
    di = app(d, and_intro(C)); dh = app(d, h())
    C.thm('S.bad', arrow(C.eqnat(di, di), C.eqnat(di, C.lit(5))), lam(C.eqnat(dh, dh), lambda q: q))

@case('e1-lam-own-domain-vs-declared-ok', 'accept', 'twin: fun (q : d h = d h) => q : (q : d intro = d intro) -> d h = d h')
def _(C):
    A, h, d = triple(C)
    di = app(d, and_intro(C)); dh = app(d, h())
    C.thm('S.ok', arrow(C.eqnat(di, di), C.eqnat(dh, dh)), lam(C.eqnat(dh, dh), lambda q: q))

@case('e1-lam-own-domain-second-binder-bad', 'reject', 'the same behind a first binder with identical domains (the joint telescope check stops there)')
def _(C):
    A, h, d = triple(C)
    di = app(d, and_intro(C)); dh = app(d, h())
    C.thm('S.bad', pi(C.nat, lambda u: arrow(C.eqnat(di, di), C.eqnat(di, C.lit(5)))), lam(C.nat, lambda u: lam(C.eqnat(dh, dh), lambda q: q)))

@case('e1-lam-own-domain-argument-bad', 'reject',
      'the same lambda as an ARGUMENT (expected-type propagation into a lambda argument): g (fun (q : d h = d h) => q) with '
      'g : ((q : d intro = d intro) -> d intro = 5) -> True an axiom')
def _(C):
    A, h, d = triple(C)
    di = app(d, and_intro(C)); dh = app(d, h())
    g = C.axiom('S.g', arrow(arrow(C.eqnat(di, di), C.eqnat(di, C.lit(5))), C.true))
    C.thm('S.bad', C.true, app(g(), lam(C.eqnat(dh, dh), lambda q: q)))

@case('e1-let-function-applied-bad', 'reject',
      'a let variable of function type, applied: let f : Unit -> d h = d h := fun u => Eq.refl (d intro); f Unit.unit : d h = 5')
def _(C):
    A, h, d = triple(C)
    di = app(d, and_intro(C)); dh = app(d, h())
    unit = C.c('Unit'); star = C.c('Unit.unit')
    C.thm('S.bad', C.eqnat(dh, C.lit(5)), let_(arrow(unit, C.eqnat(dh, dh)), lam(unit, lambda u: C.reflnat(di)), lambda f: app(f, star)))

@case('e1-let-two-binders-one-value-bad', 'reject',
      'two let binders with ONE value term and different declared types: let q : d h = d h := Eq.refl (d intro); let r : d intro = '
      'd intro := Eq.refl (d intro); q : d h = 5 (the two variables must not share a declared type)')
def _(C):
    A, h, d = triple(C)
    di = app(d, and_intro(C)); dh = app(d, h())
    C.thm('S.bad', C.eqnat(dh, C.lit(5)), let_(C.eqnat(dh, dh), C.reflnat(di), lambda q: let_(C.eqnat(di, di), C.reflnat(di), lambda r: q)))

@case('e1-let-two-binders-one-value-ok', 'accept', 'twin: .. ; r : d intro = 5 (the second variable has the type d intro = d intro)')
def _(C):
    A, h, d = triple(C)
    di = app(d, and_intro(C)); dh = app(d, h())
    C.thm('S.ok', C.eqnat(di, C.lit(5)), let_(C.eqnat(dh, dh), C.reflnat(di), lambda q: let_(C.eqnat(di, di), C.reflnat(di), lambda r: r)))

def genv(C):
    A, h, d = triple(C)
    di = app(d, and_intro(C)); dh = app(d, h())
    G = C.axiom('S.G', pi(C.prop, lambda P: arrow(P, C.prop)))
    gp = C.axiom('S.gp', pi(C.prop, lambda P: pi(P, lambda p: app(G(), P, p))))
    r0 = C.axiom('S.r0', C.eqnat(di, C.lit(5)))
    return di, dh, G, gp, r0

@case('e1-lam-fvar-type-in-conv-bad', 'reject',
      'the type of the COMPARISON variable of two binders: theorem : (q : d h = d h) -> G (d intro = 5) r0 := fun (q : d intro = d intro) '
      '=> gp (d intro = d intro) q, with G : forall (P : Prop), P -> Prop, gp : forall P p, G P p, r0 : d intro = 5 axioms. Official: the '
      'bodies G (d intro = d intro) x and G (d intro = 5) r0 are compared under ONE variable x typed by the DECLARED domain '
      'd h = d h; x against r0 is proof irrelevance on d h = d h against d intro = 5: false. Typed by the lambda\'s own domain it is true')
def _(C):
    di, dh, G, gp, r0 = genv(C)
    C.thm('S.bad', arrow(C.eqnat(dh, dh), app(G(), C.eqnat(di, C.lit(5)), r0())), lam(C.eqnat(di, di), lambda q: app(gp(), C.eqnat(di, di), q)))

@case('e1-lam-fvar-type-in-conv-ok', 'accept', 'twin: the declared domain is d intro = d intro as well')
def _(C):
    di, dh, G, gp, r0 = genv(C)
    C.thm('S.ok', arrow(C.eqnat(di, di), app(G(), C.eqnat(di, C.lit(5)), r0())), lam(C.eqnat(di, di), lambda q: app(gp(), C.eqnat(di, di), q)))

@case('e1-lam-fvar-type-in-conv-second-binder-bad', 'reject', 'the same behind a first binder with identical domains')
def _(C):
    di, dh, G, gp, r0 = genv(C)
    C.thm('S.bad', pi(C.nat, lambda u: arrow(C.eqnat(dh, dh), app(G(), C.eqnat(di, C.lit(5)), r0()))),
          lam(C.nat, lambda u: lam(C.eqnat(di, di), lambda q: app(gp(), C.eqnat(di, di), q))))

@case('e1-lam-fvar-type-in-conv-argument-bad', 'reject', 'the same lambda as an argument of an axiom that expects the Pi type')
def _(C):
    di, dh, G, gp, r0 = genv(C)
    g = C.axiom('S.g', arrow(arrow(C.eqnat(dh, dh), app(G(), C.eqnat(di, C.lit(5)), r0())), C.true))
    C.thm('S.bad', C.true, app(g(), lam(C.eqnat(di, di), lambda q: app(gp(), C.eqnat(di, di), q))))

@case('e1-let-proj-bad', 'reject',
      'a projection of a let variable: let p : (d h = d h) /\\ True := And.intro (Eq.refl (d intro)) True.intro; p.1 : d h = 5')
def _(C):
    A, h, d = triple(C)
    di = app(d, and_intro(C)); dh = app(d, h())
    andc = C.c('And'); intro = C.c('And.intro')
    C.thm('S.bad', C.eqnat(dh, C.lit(5)),
          let_(app(andc, C.eqnat(dh, dh), C.true), app(intro, C.eqnat(di, di), C.true, C.reflnat(di), C.trivial), lambda p: proj('And', 0, p)))

@case('e1-redex-function-applied-open-bad', 'reject',
      'a redex whose bound variable is APPLIED, under a binder its domain mentions: fun (w : A) => (fun (f : Unit -> d w = d w) => f '
      'Unit.unit) (fun u => Eq.refl (d intro)) : forall w, d w = 5')
def _(C):
    A, h, d = triple(C)
    di = app(d, and_intro(C))
    dw = lambda w: app(d, w)
    unit = C.c('Unit'); star = C.c('Unit.unit')
    C.thm('S.bad', pi(A, lambda w: C.eqnat(dw(w), C.lit(5))),
          lam(A, lambda w: app(lam(arrow(unit, C.eqnat(dw(w), dw(w))), lambda f: app(f, star)), lam(unit, lambda u: C.reflnat(di)))))

@case('e1-expect-cod-memo-bad', 'reject',
      'ONE lambda term L = fun (u : Unit) => Eq.refl (d h) twice in one declaration: And.intro .. L L : (Unit -> d intro = d intro) /\\ '
      '(Unit -> d intro = 5). Official: L has the type Unit -> d h = d h (inferred once, cached); against Unit -> d intro = d intro: '
      'true; against Unit -> d intro = 5: false. An engine that keeps the first EXPECTED body type d intro = d intro as the '
      'codomain of the lambda\'s type answers the second comparison with it: d intro = d intro against d intro = 5 is true')
def _(C):
    A, h, d = triple(C)
    di = app(d, and_intro(C)); dh = app(d, h())
    unit = C.c('Unit')
    L = lam(unit, lambda u: C.reflnat(dh))
    P1 = arrow(unit, C.eqnat(di, di)); P2 = arrow(unit, C.eqnat(di, C.lit(5)))
    C.thm('S.bad', app(C.c('And'), P1, P2), app(C.c('And.intro'), P1, P2, L, L))

@case('e1-expect-cod-memo-ok', 'accept', 'twin: the second component is Unit -> d h = d h')
def _(C):
    A, h, d = triple(C)
    di = app(d, and_intro(C)); dh = app(d, h())
    unit = C.c('Unit')
    L = lam(unit, lambda u: C.reflnat(dh))
    P1 = arrow(unit, C.eqnat(di, di)); P2 = arrow(unit, C.eqnat(dh, dh))
    C.thm('S.ok', app(C.c('And'), P1, P2), app(C.c('And.intro'), P1, P2, L, L))

@case('e1-expect-cod-memo-reversed-ok', 'accept',
      'the reverse order of the first: L against Unit -> d h = d h first, then against Unit -> d intro = d intro (both true officially)')
def _(C):
    A, h, d = triple(C)
    di = app(d, and_intro(C)); dh = app(d, h())
    unit = C.c('Unit')
    L = lam(unit, lambda u: C.reflnat(di))
    P1 = arrow(unit, C.eqnat(dh, dh)); P2 = arrow(unit, C.eqnat(di, C.lit(5)))
    C.thm('S.ok', app(C.c('And'), P1, P2), app(C.c('And.intro'), P1, P2, L, L))

# ---- the systematic search (orchestrator, 21:0xZ): the triple planted where a rule could take a convertible stand-in ----

@case('e1-sys-redex-function-applied-closed-bad', 'reject',
      'application, FUNCTION type: a redex whose bound variable is applied, closed domain: (fun (f : Unit -> d h = d h) => f Unit.unit) '
      '(fun u => Eq.refl (d intro)) : d h = 5')
def _(C):
    A, h, d = triple(C)
    di = app(d, and_intro(C)); dh = app(d, h())
    unit = C.c('Unit'); star = C.c('Unit.unit')
    C.thm('S.bad', C.eqnat(dh, C.lit(5)), app(lam(arrow(unit, C.eqnat(dh, dh)), lambda f: app(f, star)), lam(unit, lambda u: C.reflnat(di))))

@case('e1-sys-redex-proj-open-bad', 'reject',
      'PROJECTION of a redex variable under a binder its domain mentions: fun (w : A) => (fun (p : (d w = d w) /\\ True) => p.1) '
      '(And.intro (Eq.refl (d intro)) True.intro) : forall w, d w = 5')
def _(C):
    A, h, d = triple(C)
    di = app(d, and_intro(C))
    dw = lambda w: app(d, w)
    andc = C.c('And'); intro = C.c('And.intro')
    C.thm('S.bad', pi(A, lambda w: C.eqnat(dw(w), C.lit(5))),
          lam(A, lambda w: app(lam(app(andc, C.eqnat(dw(w), dw(w)), C.true), lambda p: proj('And', 0, p)),
                               app(intro, C.eqnat(di, di), C.true, C.reflnat(di), C.trivial))))

@case('e1-sys-lam-two-binders-bad', 'reject',
      'LAMBDA binders, two levels: fun (q : d h = d h) (u : Unit) => q : (d intro = d intro) -> Unit -> d intro = 5 (the inner body is '
      'typed under the outer variable\'s OWN domain)')
def _(C):
    A, h, d = triple(C)
    di = app(d, and_intro(C)); dh = app(d, h())
    unit = C.c('Unit')
    C.thm('S.bad', arrow(C.eqnat(di, di), arrow(unit, C.eqnat(di, C.lit(5)))), lam(C.eqnat(dh, dh), lambda q: lam(unit, lambda u: q)))

@case('e1-sys-lam-two-binders-ok', 'accept', 'twin: .. : (d intro = d intro) -> Unit -> d h = d h')
def _(C):
    A, h, d = triple(C)
    di = app(d, and_intro(C)); dh = app(d, h())
    unit = C.c('Unit')
    C.thm('S.ok', arrow(C.eqnat(di, di), arrow(unit, C.eqnat(dh, dh))), lam(C.eqnat(dh, dh), lambda q: lam(unit, lambda u: q)))

@case('e1-sys-let-chain-bad', 'reject',
      'LET binders in a chain: let q : d h = d h := Eq.refl (d intro); let r : d intro = 5 := q; True.intro : True (the value q of '
      'the second let has the declared type of the first, d h = d h, which is not convertible with d intro = 5)')
def _(C):
    A, h, d = triple(C)
    di = app(d, and_intro(C)); dh = app(d, h())
    C.thm('S.bad', C.true, let_(C.eqnat(dh, dh), C.reflnat(di), lambda q: let_(C.eqnat(di, C.lit(5)), q, lambda r: C.trivial)))

@case('e1-sys-let-chain-ok', 'accept', 'twin: let q : d h = d h := ..; let r : d intro = d intro := q; True.intro')
def _(C):
    A, h, d = triple(C)
    di = app(d, and_intro(C)); dh = app(d, h())
    C.thm('S.ok', C.true, let_(C.eqnat(dh, dh), C.reflnat(di), lambda q: let_(C.eqnat(di, di), q, lambda r: C.trivial)))

@case('e1-sys-defn-value-let-bad', 'reject', 'the expected type of a DEFINITION value: def S.bad : d h = 5 := let q : d h = d h := Eq.refl (d intro); q')
def _(C):
    A, h, d = triple(C)
    di = app(d, and_intro(C)); dh = app(d, h())
    C.defn('S.bad', C.eqnat(dh, C.lit(5)), let_(C.eqnat(dh, dh), C.reflnat(di), lambda q: q), hints=1)

@case('e1-sys-two-declarations-one-lambda-bad', 'reject',
      'SESSIONS / shared check facts: the closed lambda L = fun (u : Unit) => Eq.refl (d h) is the value of declaration a : Unit -> d '
      'intro = d intro and of declaration bad : Unit -> d intro = 5 (whatever the first check left behind must not answer the second)')
def _(C):
    A, h, d = triple(C)
    di = app(d, and_intro(C)); dh = app(d, h())
    unit = C.c('Unit')
    L = lam(unit, lambda u: C.reflnat(dh))
    C.thm('S.a', arrow(unit, C.eqnat(di, di)), L)
    C.thm('S.bad', arrow(unit, C.eqnat(di, C.lit(5))), L)

@case('e1-sys-let-krule-major-applied-bad', 'reject',
      'the K RULE on a major premise that is the APPLICATION of a let variable: let f : Unit -> d h = 5 := fun u => r0 (r0 : d intro = 5 '
      'an axiom); (fun (_ : Eq.rec (motive := fun _ _ => Nat) 7 (f Unit.unit) = 7) => True.intro) (Eq.refl 7) : True. Official: '
      'to_cnstr_when_K infers the type of the major TERM f Unit.unit through the let variable\'s DECLARED type, d h = 5: against d h = d h '
      'false, K does not fire, and the major reduces to the axiom r0: the recursor is stuck and 7 against it is false. An engine '
      'whose values have the let variable reduced away sees the major r0 : d intro = 5, K fires (d intro unfolds to 5)')
def _(C):
    A, h, d = triple(C)
    di = app(d, and_intro(C)); dh = app(d, h())
    unit = C.c('Unit'); star = C.c('Unit.unit')
    r0 = C.axiom('S.r0', C.eqnat(di, C.lit(5)))
    mot = lam(C.nat, lambda b: lam(C.eqnat(dh, b), lambda t: C.nat))
    def R(f): return app(C.c('Eq.rec', [C.l1, C.l1]), C.nat, dh, mot, C.lit(7), C.lit(5), app(f, star))
    body = lambda f: app(lam(C.eqnat(R(f), C.lit(7)), lambda _: C.trivial), C.reflnat(C.lit(7)))
    C.thm('S.bad', C.true, let_(arrow(unit, C.eqnat(dh, C.lit(5))), lam(unit, lambda u: r0()), body))

@case('e1-sys-let-krule-major-applied-ok', 'accept', 'twin: the declared type of f is Unit -> d intro = 5 and the recursor is at d intro: K fires for everybody')
def _(C):
    A, h, d = triple(C)
    di = app(d, and_intro(C)); dh = app(d, h())
    unit = C.c('Unit'); star = C.c('Unit.unit')
    r0 = C.axiom('S.r0', C.eqnat(di, C.lit(5)))
    mot = lam(C.nat, lambda b: lam(C.eqnat(di, b), lambda t: C.nat))
    def R(f): return app(C.c('Eq.rec', [C.l1, C.l1]), C.nat, di, mot, C.lit(7), C.lit(5), app(f, star))
    body = lambda f: app(lam(C.eqnat(R(f), C.lit(7)), lambda _: C.trivial), C.reflnat(C.lit(7)))
    C.thm('S.ok', C.true, let_(arrow(unit, C.eqnat(di, C.lit(5))), lam(unit, lambda u: r0()), body))

@case('e1-sys-let-krule-major-variable-bad', 'reject',
      'the K rule on a major premise that IS a let variable: let e : d h = 5 := r0; (fun (_ : Eq.rec 7 e = 7) => True.intro) (Eq.refl 7)')
def _(C):
    A, h, d = triple(C)
    di = app(d, and_intro(C)); dh = app(d, h())
    r0 = C.axiom('S.r0', C.eqnat(di, C.lit(5)))
    mot = lam(C.nat, lambda b: lam(C.eqnat(dh, b), lambda t: C.nat))
    def R(e): return app(C.c('Eq.rec', [C.l1, C.l1]), C.nat, dh, mot, C.lit(7), C.lit(5), e)
    body = lambda e: app(lam(C.eqnat(R(e), C.lit(7)), lambda _: C.trivial), C.reflnat(C.lit(7)))
    C.thm('S.bad', C.true, let_(C.eqnat(dh, C.lit(5)), r0(), body))

# -- siblings of E1-F6: the official kernel infers the type of a STUCK PROJECTION of a let variable (applied or not) through the
# -- variable's DECLARED type (the projection term keeps the variable: reduce_proj fails and returns the term as it was)

def sibenv(C, dx):
    """dx = the index the DECLARED type of the let variable uses; the value is an axiom at d intro"""
    di, dh, G, gp, r0 = genv(C)
    unit = C.c('Unit'); star = C.c('Unit.unit')
    andc = C.c('And')
    return di, dh, G, gp, r0, unit, star, andc

@case('e1-sib-proj-applied-let-pi-bad', 'reject',
      'PROOF IRRELEVANCE on a stuck projection of an APPLIED let variable: let q : Unit -> (d h = d h) /\\ True := ax (ax : Unit -> '
      '(d intro = d intro) /\\ True an axiom); (fun (_ : G (d intro = 5) r0) => True.intro) (gp (d intro = d intro) (q Unit.unit).1). '
      'Official: (q Unit.unit).1 stays as it is (ax Unit.unit is no constructor), its type is read through the DECLARED type of q, '
      'd h = d h, and against d intro = 5 that is false. An engine on values has the projection of ax Unit.unit : d intro = d intro')
def _(C):
    di, dh, G, gp, r0, unit, star, andc = sibenv(C, None)
    ax = C.axiom('S.ax', arrow(unit, app(andc, C.eqnat(di, di), C.true)))
    body = lambda q: app(lam(app(G(), C.eqnat(di, C.lit(5)), r0()), lambda _: C.trivial), app(gp(), C.eqnat(di, di), proj('And', 0, app(q, star))))
    C.thm('S.bad', C.true, let_(arrow(unit, app(andc, C.eqnat(dh, dh), C.true)), ax(), body))

@case('e1-sib-proj-applied-let-pi-ok', 'accept', 'twin: the declared type of q is the type of ax')
def _(C):
    di, dh, G, gp, r0, unit, star, andc = sibenv(C, None)
    ax = C.axiom('S.ax', arrow(unit, app(andc, C.eqnat(di, di), C.true)))
    body = lambda q: app(lam(app(G(), C.eqnat(di, C.lit(5)), r0()), lambda _: C.trivial), app(gp(), C.eqnat(di, di), proj('And', 0, app(q, star))))
    C.thm('S.ok', C.true, let_(arrow(unit, app(andc, C.eqnat(di, di), C.true)), ax(), body))

@case('e1-sib-proj-let-pi-bad', 'reject',
      'the same with the let variable projected DIRECTLY: let p : (d h = d h) /\\ True := ax0 (ax0 : (d intro = d intro) /\\ True); '
      '(fun (_ : G (d intro = 5) r0) => True.intro) (gp (d intro = d intro) p.1)')
def _(C):
    di, dh, G, gp, r0, unit, star, andc = sibenv(C, None)
    ax0 = C.axiom('S.ax0', app(andc, C.eqnat(di, di), C.true))
    body = lambda p: app(lam(app(G(), C.eqnat(di, C.lit(5)), r0()), lambda _: C.trivial), app(gp(), C.eqnat(di, di), proj('And', 0, p)))
    C.thm('S.bad', C.true, let_(app(andc, C.eqnat(dh, dh), C.true), ax0(), body))

@case('e1-sib-proj-let-pi-ok', 'accept', 'twin: the declared type of p is the type of ax0')
def _(C):
    di, dh, G, gp, r0, unit, star, andc = sibenv(C, None)
    ax0 = C.axiom('S.ax0', app(andc, C.eqnat(di, di), C.true))
    body = lambda p: app(lam(app(G(), C.eqnat(di, C.lit(5)), r0()), lambda _: C.trivial), app(gp(), C.eqnat(di, di), proj('And', 0, p)))
    C.thm('S.ok', C.true, let_(app(andc, C.eqnat(di, di), C.true), ax0(), body))

def sibk(C, declared_at_h, applied):
    """the K rule on a major premise that is a stuck projection of a let variable (applied to Unit.unit or not)"""
    di, dh, G, gp, r0, unit, star, andc = sibenv(C, None)
    at = dh if declared_at_h else di
    T0 = app(andc, C.eqnat(di, C.lit(5)), C.true)      # the type of the axiom
    T1 = app(andc, C.eqnat(at, C.lit(5)), C.true)      # the declared type
    mot = lam(C.nat, lambda b: lam(C.eqnat(at, b), lambda t: C.nat))
    def R(e): return app(C.c('Eq.rec', [C.l1, C.l1]), C.nat, at, mot, C.lit(7), C.lit(5), e)
    body = lambda e: app(lam(C.eqnat(R(e), C.lit(7)), lambda _: C.trivial), C.reflnat(C.lit(7)))
    if applied:
        ax = C.axiom('S.ax', arrow(unit, T0))
        return let_(arrow(unit, T1), ax(), lambda q: body(proj('And', 0, app(q, star))))
    ax0 = C.axiom('S.ax0', T0)
    return let_(T1, ax0(), lambda p: body(proj('And', 0, p)))

@case('e1-sib-krule-major-proj-let-bad', 'reject',
      'the K RULE on a major premise that is a stuck projection of a let variable: let p : (d h = 5) /\\ True := ax0 (ax0 : (d intro '
      '= 5) /\\ True); (fun (_ : Eq.rec (motive := fun _ _ => Nat) 7 p.1 = 7) => True.intro) (Eq.refl 7), the recursor at d h. Official: '
      'the type of the major p.1 is d h = 5 (declared), against d h = d h false: K does not fire, the recursor is stuck')
def _(C):
    C.thm('S.bad', C.true, sibk(C, True, False))

@case('e1-sib-krule-major-proj-let-ok', 'accept', 'twin: declared at d intro, the recursor at d intro: K fires for everybody')
def _(C):
    C.thm('S.ok', C.true, sibk(C, False, False))

@case('e1-sib-krule-major-proj-applied-let-bad', 'reject', 'the same with p.1 replaced by (q Unit.unit).1, q : Unit -> (d h = 5) /\\ True := ax')
def _(C):
    C.thm('S.bad', C.true, sibk(C, True, True))

@case('e1-sib-krule-major-proj-applied-let-ok', 'accept', 'twin: declared at d intro, the recursor at d intro')
def _(C):
    C.thm('S.ok', C.true, sibk(C, False, True))

# -- the same WITHOUT a let: a beta-redex that the official kernel leaves uncontracted (the struct of a stuck projection, the raw
# -- major premise of the K rule) is typed through its binder's DECLARED domain; a value has the redex contracted

@case('e1-sib-proj-redex-pi-bad', 'reject',
      'PROOF IRRELEVANCE on a stuck projection of a BETA-REDEX, no let: (fun (_ : G (d intro = 5) r0) => True.intro) (gp (d intro = '
      'd intro) ((fun (z : Unit -> (d h = d h) /\\ True) => z Unit.unit) ax).1). Official: the struct of the stuck projection stays the '
      'redex; its type is the body\'s under z of the binder domain: (d h = d h) /\\ True, so the projection has type d h = d h, '
      'against d intro = 5 false. An engine on values has the projection of ax Unit.unit : d intro = d intro')
def _(C):
    di, dh, G, gp, r0, unit, star, andc = sibenv(C, None)
    ax = C.axiom('S.ax', arrow(unit, app(andc, C.eqnat(di, di), C.true)))
    redex = app(lam(arrow(unit, app(andc, C.eqnat(dh, dh), C.true)), lambda z: app(z, star)), ax())
    C.thm('S.bad', C.true, app(lam(app(G(), C.eqnat(di, C.lit(5)), r0()), lambda _: C.trivial), app(gp(), C.eqnat(di, di), proj('And', 0, redex))))

@case('e1-sib-proj-redex-pi-ok', 'accept', 'twin: the binder domain is the type of ax')
def _(C):
    di, dh, G, gp, r0, unit, star, andc = sibenv(C, None)
    ax = C.axiom('S.ax', arrow(unit, app(andc, C.eqnat(di, di), C.true)))
    redex = app(lam(arrow(unit, app(andc, C.eqnat(di, di), C.true)), lambda z: app(z, star)), ax())
    C.thm('S.ok', C.true, app(lam(app(G(), C.eqnat(di, C.lit(5)), r0()), lambda _: C.trivial), app(gp(), C.eqnat(di, di), proj('And', 0, redex))))

def sibkr(C, at_h):
    di, dh, G, gp, r0, unit, star, andc = sibenv(C, None)
    at = dh if at_h else di
    mot = lam(C.nat, lambda b: lam(C.eqnat(at, b), lambda t: C.nat))
    major = app(lam(C.eqnat(at, C.lit(5)), lambda z: z), r0())
    R = app(C.c('Eq.rec', [C.l1, C.l1]), C.nat, at, mot, C.lit(7), C.lit(5), major)
    return app(lam(C.eqnat(R, C.lit(7)), lambda _: C.trivial), C.reflnat(C.lit(7)))

@case('e1-sib-krule-major-redex-bad', 'reject',
      'the K RULE on a major premise that is a BETA-REDEX, no let: (fun (_ : Eq.rec (motive := fun _ _ => Nat) 7 ((fun (z : d h = 5) => z) '
      'r0) = 7) => True.intro) (Eq.refl 7), the recursor at d h, r0 : d intro = 5. Official: to_cnstr_when_K types the raw major through '
      'the binder domain, d h = 5, against d h = d h false: K does not fire; the major reduces to the axiom r0, the recursor is stuck')
def _(C):
    C.thm('S.bad', C.true, sibkr(C, True))

@case('e1-sib-krule-major-redex-ok', 'accept', 'twin: everything at d intro, K fires for everybody')
def _(C):
    C.thm('S.ok', C.true, sibkr(C, False))

# -- kind T certificate (orchestrator 21:50Z): the statement the machine accepted in E1-F1 .. F5 is derivable; the official
# -- checker accepts it when the missing conversion step is written out

@case('e1-cert-dh-eq-5-ok', 'accept',
      'CERTIFICATE that d h = 5 is derivable (the findings E1-F1 .. F5 are of kind T): theorem S.cert : d h = 5 := Eq.rec (motive := '
      'fun b _ => d h = b) (Eq.refl (d intro) : d h = d intro) (Eq.refl 5 : d intro = 5): each of the two steps is one the official '
      'checker accepts (d intro against d h, d intro against 5); only their composition by transitivity is what it does not find')
def _(C):
    A, h, d = triple(C)
    di = app(d, and_intro(C)); dh = app(d, h())
    mot = lam(C.nat, lambda b: lam(C.eqnat(di, b), lambda t: C.eqnat(dh, b)))
    C.thm('S.cert', C.eqnat(dh, C.lit(5)), app(C.c('Eq.rec', [C.X.lnum(0) if hasattr(C, 'X') else 0, C.l1]), C.nat, di, mot, C.reflnat(di), C.lit(5), C.reflnat(C.lit(5))))

@case('e1-cert-lam-fvar-ok', 'accept',
      'CERTIFICATE that the statement of e1-lam-fvar-type-in-conv-bad is derivable (E1-F4 is of kind T): S.ok : (q : d intro = d intro) '
      '-> G (d intro = 5) r0 is the accepted twin; theorem S.cert : (q : d h = d h) -> G (d intro = 5) r0 := fun q => S.ok q (the '
      'argument q : d h = d h against the domain d intro = d intro is a step the official checker accepts)')
def _(C):
    di, dh, G, gp, r0 = genv(C)
    tgt = app(G(), C.eqnat(di, C.lit(5)), r0())
    C.thm('S.ok', arrow(C.eqnat(di, di), tgt), lam(C.eqnat(di, di), lambda q: app(gp(), C.eqnat(di, di), q)))
    C.thm('S.cert', arrow(C.eqnat(dh, dh), tgt), lam(C.eqnat(dh, dh), lambda q: app(C.k('S.ok'), q)))

@case('e1-app-type-is-domain-dep-bad', 'reject',
      'the same where the body is not the variable but its type depends on the binder type: '
      '(fun (q : d h = d h) => id q) (Eq.refl (d intro)) : d h = 5, id an axiom forall (P : Prop), P -> P')
def _(C):
    A, h, d = triple(C)
    di = app(d, and_intro(C)); dh = app(d, h())
    idp = C.axiom('S.idp', pi(C.prop, lambda P: arrow(P, P)))
    C.thm('S.bad', C.eqnat(dh, C.lit(5)), app(lam(C.eqnat(dh, dh), lambda q: app(idp(), C.eqnat(dh, dh), q)), C.reflnat(di)))


# ------------------------------------------------------------------------------------------------------------
# 1. Deferred comparisons: order, scopes
# ------------------------------------------------------------------------------------------------------------

def benv(C):
    """A pair whose two modes AGREE: X1 = Nat.add 0 1 (closed, folds under eager reduction although p is free),
    K1 p = 1 for every p (a regular definition that ignores its argument)."""
    A, G, X, Y = env(C)
    C.defn('S.K1', arrow(A, C.nat), lam(A, lambda x: C.lit(1)), hints=1)
    K1 = C.k('S.K1')
    X1 = app(C.c('Nat.add'), C.lit(0), C.lit(1))
    return A, G, X, Y, X1, (lambda p: app(K1, p))

@case('e1-redex-benign-plain-then-eager-ok', 'accept',
      'the beta-redex form (fun (h : D) => h) (eagerReduce S a) of the harmless pair, plain first, then eager, one binder')
def _(C):
    A, G, X, Y, X1, K1 = benv(C)
    st = lambda p: C.eqnat(X1, K1(p))
    ty = pi(A, lambda p: app(C.c('And'), st(p), st(p)))
    val = lam(A, lambda p: app(C.c('And.intro'), st(p), st(p), C.reflnat(X1), under_eager_redex(C, st(p), C.eqnat(X1, X1), C.reflnat(X1))))
    C.thm('S.ok', ty, val)

@case('e1-eager-pair-alone-bad', 'reject', 'X = Y p under eagerReduce alone, as the argument of a constant: the closed side folds first, false')
def _(C):
    A, G, X, Y = env(C)
    eager(C, A, X, Y, 'S.bad')

@case('e1-benign-eager-alone-ok', 'accept', 'Nat.add 0 1 = K1 p under eagerReduce alone (K1 p unfolds to 1): both modes answer T')
def _(C):
    A, G, X, Y, X1, K1 = benv(C)
    C.thm('S.ok', pi(A, lambda p: C.eqnat(X1, K1(p))), lam(A, lambda p: under_eager(C, C.eqnat(X1, K1(p)), C.eqnat(X1, X1), C.reflnat(X1))))

@case('e1-benign-plain-then-eager-ok', 'accept',
      'ONE declaration, one binder: Nat.add 0 1 = K1 p first in plain mode (the fold is skipped: p is free; the definitions '
      'unfold), then under eagerReduce (the fold is taken). The answers agree: a recorded mode-dependent point that is harmless')
def _(C):
    A, G, X, Y, X1, K1 = benv(C)
    st = lambda p: C.eqnat(X1, K1(p))
    ty = pi(A, lambda p: app(C.c('And'), st(p), st(p)))
    val = lam(A, lambda p: app(C.c('And.intro'), st(p), st(p), C.reflnat(X1), under_eager(C, st(p), C.eqnat(X1, X1), C.reflnat(X1))))
    C.thm('S.ok', ty, val)

@case('e1-benign-eager-then-plain-ok', 'accept', 'the same with the eager component first')
def _(C):
    A, G, X, Y, X1, K1 = benv(C)
    st = lambda p: C.eqnat(X1, K1(p))
    ty = pi(A, lambda p: app(C.c('And'), st(p), st(p)))
    val = lam(A, lambda p: app(C.c('And.intro'), st(p), st(p), under_eager(C, st(p), C.eqnat(X1, X1), C.reflnat(X1)), C.reflnat(X1)))
    C.thm('S.ok', ty, val)

@case('e1-benign-then-bad-pair-eager-bad', 'reject',
      'the harmless pair in plain mode and under eagerReduce, and in the same declaration X = Y p under eagerReduce only '
      '(false there). The deferred comparisons are independent: one false one rejects')
def _(C):
    A, G, X, Y, X1, K1 = benv(C)
    st = lambda p: C.eqnat(X1, K1(p)); bad = lambda p: C.eqnat(X, Y(p))
    ty = pi(A, lambda p: app(C.c('And'), st(p), app(C.c('And'), st(p), bad(p))))
    val = lam(A, lambda p: app(C.c('And.intro'), st(p), app(C.c('And'), st(p), bad(p)), C.reflnat(X1),
              app(C.c('And.intro'), st(p), bad(p), under_eager(C, st(p), C.eqnat(X1, X1), C.reflnat(X1)),
                  under_eager(C, bad(p), C.eqnat(X, X), C.reflnat(X)))))
    C.thm('S.bad', ty, val)

@case('e1-defer-then-plain-failure-bad', 'reject',
      'a TRUE comparison under eagerReduce, then a plain application type mismatch (Eq.refl 1 : 2 = 2): the declaration is '
      'rejected for the plain error although every deferred comparison holds')
def _(C):
    A, G, X, Y, X1, K1 = benv(C)
    st = lambda p: C.eqnat(X1, K1(p)); wrong = C.eqnat(C.lit(2), C.lit(2))
    ty = pi(A, lambda p: app(C.c('And'), st(p), wrong))
    val = lam(A, lambda p: app(C.c('And.intro'), st(p), wrong, under_eager(C, st(p), C.eqnat(X1, X1), C.reflnat(X1)),
              app(lam(C.eqnat(C.lit(2), C.lit(3)), lambda q: C.reflnat(C.lit(2))), C.reflnat(C.lit(1)))))
    C.thm('S.bad', ty, val)

@case('e1-defer-false-then-plain-ok-bad', 'reject', 'a FALSE comparison under eagerReduce first, then correct plain components')
def _(C):
    A, G, X, Y, X1, K1 = benv(C)
    bad = lambda p: C.eqnat(X, Y(p)); fine = C.eqnat(C.lit(2), C.lit(2))
    ty = pi(A, lambda p: app(C.c('And'), bad(p), fine))
    val = lam(A, lambda p: app(C.c('And.intro'), bad(p), fine, under_eager(C, bad(p), C.eqnat(X, X), C.reflnat(X)), C.reflnat(C.lit(2))))
    C.thm('S.bad', ty, val)

@case('e1-scope-capture-bad', 'reject',
      'SCOPE of a deferred comparison. Under the binder n : Nat the argument eagerReduce has the type forall x : Nat, R x n and '
      'the domain is forall x : Nat, R n x (R an axiom Nat -> Nat -> Prop). They differ. A deferred comparison that runs with '
      'the binder n closed opens x as the first variable of type Nat, which IS n: both bodies become R n n')
def _(C):
    R = C.axiom('S.R', arrow(C.nat, arrow(C.nat, C.prop)))
    w = C.axiom('S.w', pi(C.nat, lambda n: pi(C.nat, lambda x: app(R(), x, n))))
    stated = lambda n: pi(C.nat, lambda x: app(R(), x, n))
    expected = lambda n: pi(C.nat, lambda x: app(R(), n, x))
    env(C)
    C.thm('S.bad', pi(C.nat, lambda n: expected(n)), lam(C.nat, lambda n: under_eager(C, expected(n), stated(n), app(w(), n))))

@case('e1-scope-capture-ok', 'accept', 'twin: the argument has the type of the domain up to the unfolding of an abbreviation')
def _(C):
    R = C.axiom('S.R', arrow(C.nat, arrow(C.nat, C.prop)))
    C.defn('S.R2', arrow(C.nat, arrow(C.nat, C.prop)), lam(C.nat, lambda a: lam(C.nat, lambda b: app(R(), a, b))), hints='abbrev')
    R2 = C.k('S.R2')
    w = C.axiom('S.w', pi(C.nat, lambda n: pi(C.nat, lambda x: app(R(), x, n))))
    stated = lambda n: pi(C.nat, lambda x: app(R(), x, n))
    expected = lambda n: pi(C.nat, lambda x: app(R2, x, n))
    env(C)
    C.thm('S.ok', pi(C.nat, lambda n: expected(n)), lam(C.nat, lambda n: under_eager(C, expected(n), stated(n), app(w(), n))))

@case('e1-scope-two-binders-swapped-bad', 'reject',
      'two binders of ONE type (canonical variables 0 and 1): under n m : Nat the argument has the type R n m and the domain is R m n')
def _(C):
    R = C.axiom('S.R', arrow(C.nat, arrow(C.nat, C.prop)))
    w = C.axiom('S.w', pi(C.nat, lambda n: pi(C.nat, lambda m: app(R(), n, m))))
    env(C)
    C.thm('S.bad', pi(C.nat, lambda n: pi(C.nat, lambda m: app(R(), m, n))),
          lam(C.nat, lambda n: lam(C.nat, lambda m: under_eager(C, app(R(), m, n), app(R(), n, m), app(w(), n, m)))))

@case('e1-scope-two-binders-ok', 'accept', 'twin: R n m against R2 n m (an abbreviation) under the two binders')
def _(C):
    R = C.axiom('S.R', arrow(C.nat, arrow(C.nat, C.prop)))
    C.defn('S.R2', arrow(C.nat, arrow(C.nat, C.prop)), lam(C.nat, lambda a: lam(C.nat, lambda b: app(R(), a, b))), hints='abbrev')
    R2 = C.k('S.R2')
    w = C.axiom('S.w', pi(C.nat, lambda n: pi(C.nat, lambda m: app(R(), n, m))))
    env(C)
    C.thm('S.ok', pi(C.nat, lambda n: pi(C.nat, lambda m: app(R2, n, m))),
          lam(C.nat, lambda n: lam(C.nat, lambda m: under_eager(C, app(R2, n, m), app(R(), n, m), app(w(), n, m)))))

@case('e1-scope-nested-mixed-ok', 'accept',
      'deferred comparisons at three binder depths and with binders of two types in between (p : A, n : Nat, q : A): '
      'Nat.add 0 1 = K1 p, = K1 q, and Nat.add n 0 = n under eagerReduce (true in both modes); each runs with its own scope re-opened')
def _(C):
    A, G, X, Y, X1, K1 = benv(C)
    st = lambda p: C.eqnat(X1, K1(p))
    e = lambda p: under_eager(C, st(p), C.eqnat(X1, X1), C.reflnat(X1))
    nn = lambda n: C.eqnat(app(C.c('Nat.add'), n, C.lit(0)), n)
    en = lambda n: under_eager(C, nn(n), C.eqnat(n, n), C.reflnat(n))
    andc = C.c('And'); intro = C.c('And.intro')
    ty = pi(A, lambda p: app(andc, st(p), pi(C.nat, lambda n: app(andc, nn(n), pi(A, lambda q: app(andc, st(q), st(p)))))))
    val = lam(A, lambda p: app(intro, st(p), pi(C.nat, lambda n: app(andc, nn(n), pi(A, lambda q: app(andc, st(q), st(p))))), e(p),
              lam(C.nat, lambda n: app(intro, nn(n), pi(A, lambda q: app(andc, st(q), st(p))), en(n),
                  lam(A, lambda q: app(intro, st(q), st(p), e(q), e(p)))))))
    C.thm('S.ok', ty, val)

@case('e1-nested-eager-inside-eager-bad', 'reject',
      'NESTED: the proof inside an eagerReduce argument contains another eagerReduce application whose comparison is false '
      '(X = Y p), the outer one is true')
def _(C):
    A, G, X, Y, X1, K1 = benv(C)
    st = lambda p: C.eqnat(X1, K1(p)); bad = lambda p: C.eqnat(X, Y(p))
    # inner : X1 = X1  :=  (fun (_ : X = Y p) => Eq.refl X1) (the false eager application)
    inner = lambda p: app(lam(bad(p), lambda q: C.reflnat(X1)), under_eager(C, bad(p), C.eqnat(X, X), C.reflnat(X)))
    C.thm('S.bad', pi(A, lambda p: st(p)), lam(A, lambda p: under_eager(C, st(p), C.eqnat(X1, X1), inner(p))))

@case('e1-nested-eager-inside-eager-ok', 'accept', 'twin: both comparisons are true')
def _(C):
    A, G, X, Y, X1, K1 = benv(C)
    st = lambda p: C.eqnat(X1, K1(p))
    inner = lambda p: app(lam(st(p), lambda q: C.reflnat(X1)), under_eager(C, st(p), C.eqnat(X1, X1), C.reflnat(X1)))
    C.thm('S.ok', pi(A, lambda p: st(p)), lam(A, lambda p: under_eager(C, st(p), C.eqnat(X1, X1), inner(p))))


@case('e1-site-in-axiom-type-bad', 'reject',
      'the eagerReduce application is in the TYPE of an axiom: forall p, (fun (h : X = Y p) => True) (S.use (X = Y p) (eagerReduce '
      '(X = X) (Eq.refl X))); the comparison under eager reduction is false. An engine that defers the comparison must still run '
      'it for a declaration that has no value')
def _(C):
    A, G, X, Y = env(C)
    bad = lambda p: C.eqnat(X, Y(p))
    ty = pi(A, lambda p: app(lam(bad(p), lambda h: C.true), under_eager(C, bad(p), C.eqnat(X, X), C.reflnat(X))))
    C.X.axiom('S.bad', [], C.X.t(ty))

@case('e1-site-in-axiom-type-ok', 'accept', 'twin with the pair whose two modes agree')
def _(C):
    A, G, X, Y, X1, K1 = benv(C)
    st = lambda p: C.eqnat(X1, K1(p))
    ty = pi(A, lambda p: app(lam(st(p), lambda h: C.true), under_eager(C, st(p), C.eqnat(X1, X1), C.reflnat(X1))))
    C.X.axiom('S.ok', [], C.X.t(ty))

@case('e1-site-in-definition-type-and-value-bad', 'reject',
      'a DEFINITION whose type holds a true eagerReduce application and whose value holds a false one')
def _(C):
    A, G, X, Y, X1, K1 = benv(C)
    st = lambda p: C.eqnat(X1, K1(p)); bad = lambda p: C.eqnat(X, Y(p))
    ty = pi(A, lambda p: app(lam(st(p), lambda h: bad(p)), under_eager(C, st(p), C.eqnat(X1, X1), C.reflnat(X1))))
    val = lam(A, lambda p: under_eager(C, bad(p), C.eqnat(X, X), C.reflnat(X)))
    C.thm('S.bad', ty, val)

@case('e1-let-variable-eager-ok', 'accept',
      'the variable is LET-bound: let q : A := And.intro ..; S.use (X = Y q) (eagerReduce (X = X) (Eq.refl X)). Under eager '
      'reduction X folds to 0 and Y q folds too (the let variable reduces to its value, the recursor fires): true. With a '
      'lambda-bound p the same comparison is false (e1-eager-pair-alone-bad)')
def _(C):
    A, G, X, Y = env(C)
    val = let_(A, and_intro(C), lambda q: under_eager(C, C.eqnat(X, Y(q)), C.eqnat(X, X), C.reflnat(X)))
    ty = C.eqnat(X, Y(and_intro(C)))
    C.thm('S.ok', ty, val)

@case('e1-let-variable-under-binder-eager-bad', 'reject',
      'a let-bound variable whose value is the lambda-bound p: fun p => let q : A := p; S.use (X = Y q) (eagerReduce (X = X) '
      '(Eq.refl X)): Y q does not fold (q reduces to p, the recursor is stuck): false')
def _(C):
    A, G, X, Y = env(C)
    val = lam(A, lambda p: let_(A, p, lambda q: under_eager(C, C.eqnat(X, Y(q)), C.eqnat(X, X), C.reflnat(X))))
    C.thm('S.bad', pi(A, lambda p: C.eqnat(X, Y(p))), val)

def tenv(C):
    """T1 p = Bool.true for every p (a regular definition that ignores its argument); the Bool.true test of is_def_eq_core"""
    A, G, X, Y = env(C)
    C.defn('S.T1', arrow(A, C.bool), lam(A, lambda x: C.btrue), hints=1)
    T1 = C.k('S.T1')
    eqb = lambda a, b: C.eq(C.l1, C.bool, a, b)
    return A, (lambda p: app(T1, p)), eqb

@case('e1-booltrue-eager-alone-ok', 'accept',
      'the Bool.true test: T1 p = Bool.true under eagerReduce (whnf (T1 p) is Bool.true although p is free)')
def _(C):
    A, T1, eqb = tenv(C)
    st = lambda p: eqb(T1(p), C.btrue)
    C.thm('S.ok', pi(A, lambda p: st(p)), lam(A, lambda p: under_eager(C, st(p), eqb(C.btrue, C.btrue), C.refl(C.l1, C.bool, C.btrue))))

@case('e1-booltrue-plain-then-eager-ok', 'accept', 'the same pair first in plain mode (T1 p unfolds), then under eagerReduce, one binder')
def _(C):
    A, T1, eqb = tenv(C)
    st = lambda p: eqb(T1(p), C.btrue)
    ty = pi(A, lambda p: app(C.c('And'), st(p), st(p)))
    val = lam(A, lambda p: app(C.c('And.intro'), st(p), st(p), C.refl(C.l1, C.bool, C.btrue),
              under_eager(C, st(p), eqb(C.btrue, C.btrue), C.refl(C.l1, C.bool, C.btrue))))
    C.thm('S.ok', ty, val)

@case('e1-booltrue-false-eager-bad', 'reject', 'T1 p = Bool.false under eagerReduce: false in both modes')
def _(C):
    A, T1, eqb = tenv(C)
    st = lambda p: eqb(T1(p), C.bfalse)
    C.thm('S.bad', pi(A, lambda p: st(p)), lam(A, lambda p: under_eager(C, st(p), eqb(C.bfalse, C.bfalse), C.refl(C.l1, C.bool, C.bfalse))))


# ------------------------------------------------------------------------------------------------------------
# 2. The pair X = Y p (plain T, eager F) in every order with a deferred comparison
# ------------------------------------------------------------------------------------------------------------

@case('e1-bad-pair-plain-only-with-other-eager-ok', 'accept',
      'X = Y p ONLY in plain mode (true there) and an unrelated true comparison under eagerReduce in the same declaration: '
      'the official kernel accepts. The recorded point X = Y p has two answers, but nothing asks it under eager reduction')
def _(C):
    A, G, X, Y, X1, K1 = benv(C)
    st = lambda p: C.eqnat(X1, K1(p)); xy = lambda p: C.eqnat(X, Y(p))
    ty = pi(A, lambda p: app(C.c('And'), xy(p), st(p)))
    val = lam(A, lambda p: app(C.c('And.intro'), xy(p), st(p), C.reflnat(X), under_eager(C, st(p), C.eqnat(X1, X1), C.reflnat(X1))))
    C.thm('S.ok', ty, val)

@case('e1-bad-pair-eager-then-plain-other-binder-bad', 'reject',
      'first lambda: X = Y p under eagerReduce (false); second lambda: the same pair in plain mode (true)')
def _(C):
    A, G, X, Y = env(C)
    S = pi(A, lambda p: C.eqnat(X, Y(p)))
    first = lam(A, lambda p: C.reflnat(X))
    second = lam(A, lambda p: under_eager(C, C.eqnat(X, Y(p)), C.eqnat(X, X), C.reflnat(X)))
    ty, val = both(C, S, S, second, first)
    C.thm('S.bad', ty, val)

@case('e1-bad-pair-subterm-eager-bad', 'reject',
      'the pair as SUBTERMS: plain mode proves W X = W (Y p) for an axiom W : Nat -> Nat (arguments compared: T), then '
      'eagerReduce needs W X = W (Y p) in another binder: the arguments are compared under eager reduction: false')
def _(C):
    A, G, X, Y = env(C)
    W = C.axiom('S.W', arrow(C.nat, C.nat))
    wx = app(W(), X); wy = lambda p: app(W(), Y(p))
    S = pi(A, lambda p: C.eqnat(wx, wy(p)))
    first = lam(A, lambda p: C.reflnat(wx))
    second = lam(A, lambda p: under_eager(C, C.eqnat(wx, wy(p)), C.eqnat(wx, wx), C.reflnat(wx)))
    ty, val = both(C, S, S, first, second)
    C.thm('S.bad', ty, val)

@case('e1-bad-pair-subterm-same-binder-ok', 'accept',
      'the same in ONE binder: the official success cache holds W X = W (Y p) (and X = Y p) from the plain component')
def _(C):
    A, G, X, Y = env(C)
    W = C.axiom('S.W', arrow(C.nat, C.nat))
    wx = app(W(), X); wy = lambda p: app(W(), Y(p))
    st = lambda p: C.eqnat(wx, wy(p))
    ty = pi(A, lambda p: app(C.c('And'), st(p), st(p)))
    val = lam(A, lambda p: app(C.c('And.intro'), st(p), st(p), C.reflnat(wx), under_eager(C, st(p), C.eqnat(wx, wx), C.reflnat(wx))))
    C.thm('S.ok', ty, val)


# ------------------------------------------------------------------------------------------------------------
# 3. Sessions across declarations
# ------------------------------------------------------------------------------------------------------------

@case('e1-warm-eager-then-plain-declaration-ok', 'accept',
      'WARM: declaration a proves Nat.add 0 1 = K1 p under eagerReduce, declaration b proves X = Y p in plain mode, declaration c '
      'the first again: nothing of a declaration that ran under eager reduction may answer in the next one, and the reverse')
def _(C):
    A, G, X, Y, X1, K1 = benv(C)
    st = lambda p: C.eqnat(X1, K1(p))
    C.thm('S.a', pi(A, lambda p: st(p)), lam(A, lambda p: under_eager(C, st(p), C.eqnat(X1, X1), C.reflnat(X1))))
    plain(C, A, X, Y, 'S.b')
    C.thm('S.c', pi(A, lambda p: st(p)), lam(A, lambda p: under_eager(C, st(p), C.eqnat(X1, X1), C.reflnat(X1))))
    plain(C, A, X, Y, 'S.d')

@case('e1-warm-plain-benign-then-eager-bad', 'reject',
      'WARM: declarations a, b prove X = Y p and Nat.add 0 1 = K1 p in plain mode; declaration c needs Nat.add 0 1 = K1 p under '
      'eagerReduce (true) and declaration bad needs X = Y p under eagerReduce (false)')
def _(C):
    A, G, X, Y, X1, K1 = benv(C)
    st = lambda p: C.eqnat(X1, K1(p))
    plain(C, A, X, Y, 'S.a')
    C.thm('S.b', pi(A, lambda p: st(p)), lam(A, lambda p: C.reflnat(X1)))
    C.thm('S.c', pi(A, lambda p: st(p)), lam(A, lambda p: under_eager(C, st(p), C.eqnat(X1, X1), C.reflnat(X1))))
    eager(C, A, X, Y, 'S.bad')


# ------------------------------------------------------------------------------------------------------------
# 4. The K rule
# ------------------------------------------------------------------------------------------------------------

def kbenv(C):
    """K with indices whose two modes AGREE: e1 : forall p, X1 = K1 p (opaque); R1 p := Eq.rec (motive := fun _ _ => Nat) 7 (e1 p)"""
    A, G, X, Y, X1, K1 = benv(C)
    C.opaque('S.e1', pi(A, lambda p: C.eqnat(X1, K1(p))), lam(A, lambda p: C.reflnat(X1)))
    e1 = C.k('S.e1')
    def R1(p):
        return app(C.c('Eq.rec', [C.l1, C.l1]), C.nat, X1, lam(C.nat, lambda b: lam(C.eqnat(X1, b), lambda t: C.nat)), C.lit(7), K1(p), app(e1, p))
    return A, R1

@case('e1-krule-benign-eager-alone-ok', 'accept',
      'K rule under eagerReduce where the indices agree in both modes (Nat.add 0 1 against K1 p): Eq.rec reduces to 7 in both')
def _(C):
    A, R1 = kbenv(C)
    st = lambda p: C.eqnat(R1(p), C.lit(7))
    C.thm('S.ok', pi(A, lambda p: st(p)), lam(A, lambda p: under_eager(C, st(p), C.eqnat(C.lit(7), C.lit(7)), C.reflnat(C.lit(7)))))

@case('e1-krule-benign-plain-then-eager-ok', 'accept', 'the same reduction first in plain mode, then under eagerReduce, in one binder')
def _(C):
    A, R1 = kbenv(C)
    st = lambda p: C.eqnat(R1(p), C.lit(7))
    ty = pi(A, lambda p: app(C.c('And'), st(p), st(p)))
    val = lam(A, lambda p: app(C.c('And.intro'), st(p), st(p), C.reflnat(C.lit(7)), under_eager(C, st(p), C.eqnat(C.lit(7), C.lit(7)), C.reflnat(C.lit(7)))))
    C.thm('S.ok', ty, val)

@case('e1-krule-plain-same-binder-then-eager-ok', 'accept',
      'K rule with the indices X, Y p (plain T, eager F): Eq.rec .. (e p) = 7 first in plain mode (K fires, the official WHNF '
      'cache keeps 7), then under eagerReduce in the SAME binder: the official kernel reads its cache and accepts')
def _(C):
    A, S, pl, eagr = kenv(C)
    A2, G, X, Y = A, None, None, None
    # one binder: fun p => And.intro (plain p) (eager p)
    e = C.k('S.e')
    Xc = app(C.c('Nat.add'), app(C.k('S.G'), and_intro(C)), C.lit(0))
    Yc = lambda p: app(C.c('Nat.add'), app(C.k('S.G'), p), C.lit(0))
    def R(p):
        return app(C.c('Eq.rec', [C.l1, C.l1]), C.nat, Xc, lam(C.nat, lambda b: lam(C.eqnat(Xc, b), lambda t: C.nat)), C.lit(7), Yc(p), app(e, p))
    st = lambda p: C.eqnat(R(p), C.lit(7))
    ty = pi(A, lambda p: app(C.c('And'), st(p), st(p)))
    val = lam(A, lambda p: app(C.c('And.intro'), st(p), st(p), C.reflnat(C.lit(7)), under_eager(C, st(p), C.eqnat(C.lit(7), C.lit(7)), C.reflnat(C.lit(7)))))
    C.thm('S.ok', ty, val)


# ------------------------------------------------------------------------------------------------------------
# 5. What the machine does not see: the official kernel computes on terms, the machine on values
# ------------------------------------------------------------------------------------------------------------

def senv(C):
    """R p = Eq.rec 7 (e p) with e : forall p, X = Y p opaque (K: plain T, eager F), and a second form R' p of the same value
    built from beta-redexes: Y'' p := Nat.add ((fun z => z) (G p)) 0,  e' p := (fun (h : X = Y'' p) => h) (e p),
    R' p := Eq.rec 7 (e' p) with index Y'' p."""
    A, G, X, Y = env(C)
    C.opaque('S.e', pi(A, lambda p: C.eqnat(X, Y(p))), lam(A, lambda p: C.reflnat(X)))
    e = C.k('S.e')
    idn = lam(C.nat, lambda z: z)
    Y2 = lambda p: app(C.c('Nat.add'), app(idn, app(G, p)), C.lit(0))
    e2 = lambda p: app(lam(C.eqnat(X, Y2(p)), lambda h: h), app(e, p))
    mot = lam(C.nat, lambda b: lam(C.eqnat(X, b), lambda t: C.nat))
    R = lambda p: app(C.c('Eq.rec', [C.l1, C.l1]), C.nat, X, mot, C.lit(7), Y(p), app(e, p))
    R2 = lambda p: app(C.c('Eq.rec', [C.l1, C.l1]), C.nat, X, mot, C.lit(7), Y2(p), e2(p))
    return A, X, Y, R, R2, idn

@case('e1-skip-krule-plain-redex-then-eager-ok', 'accept',
      'the K reduction of R p happens in plain mode only inside the comparison of R p with its beta-expanded form (fun z => z) (R p) '
      '(for an engine on values the two sides are one value: nothing is compared), then R p = 7 is needed under eagerReduce in '
      'the same binder: the official WHNF cache holds 7 from the plain comparison')
def _(C):
    A, X, Y, R, R2, idn = senv(C)
    p1 = lambda p: C.eqnat(R(p), app(idn, R(p)))
    st = lambda p: C.eqnat(R(p), C.lit(7))
    ty = pi(A, lambda p: app(C.c('And'), p1(p), st(p)))
    val = lam(A, lambda p: app(C.c('And.intro'), p1(p), st(p), C.reflnat(R(p)), under_eager(C, st(p), C.eqnat(C.lit(7), C.lit(7)), C.reflnat(C.lit(7)))))
    C.thm('S.ok', ty, val)

@case('e1-skip-identity-both-bad', 'reject',
      'VALUE IDENTITY. In plain mode R p is compared with (fun z => z) (R p): the official kernel reduces R p by K and caches 7. '
      'Then eagerReduce needs R p = R\' p, where R\' p is the same value written with beta-redexes (another index term, '
      'another major premise). The official kernel reads 7 for R p and computes R\' p under eager reduction, where K does '
      'not fire: 7 against a stuck Eq.rec is false. For an engine on values both comparisons are between identical values')
def _(C):
    A, X, Y, R, R2, idn = senv(C)
    p1 = lambda p: C.eqnat(R(p), app(idn, R(p)))
    st = lambda p: C.eqnat(R(p), R2(p))
    ty = pi(A, lambda p: app(C.c('And'), p1(p), st(p)))
    val = lam(A, lambda p: app(C.c('And.intro'), p1(p), st(p), C.reflnat(R(p)), under_eager(C, st(p), C.eqnat(R(p), R(p)), C.reflnat(R(p)))))
    C.thm('S.bad', ty, val)

@case('e1-skip-identity-eager-alone-ok', 'accept',
      'twin without the plain comparison: under eagerReduce both Eq.rec stay stuck and are compared argument by argument')
def _(C):
    A, X, Y, R, R2, idn = senv(C)
    st = lambda p: C.eqnat(R(p), R2(p))
    C.thm('S.ok', pi(A, lambda p: st(p)), lam(A, lambda p: under_eager(C, st(p), C.eqnat(R(p), R(p)), C.reflnat(R(p)))))

@case('e1-skip-identity-plain-only-ok', 'accept', 'twin in plain mode only: R p = R\' p by Eq.refl (R p); both reduce to 7 by K')
def _(C):
    A, X, Y, R, R2, idn = senv(C)
    st = lambda p: C.eqnat(R(p), R2(p))
    C.thm('S.ok', pi(A, lambda p: st(p)), lam(A, lambda p: C.reflnat(R(p))))


if __name__ == '__main__':
    rows = emit_all(['e1-'])
    write_manifest(rows, 'batchE.tsv')
    print(len(rows), 'exports written to', OUT)
