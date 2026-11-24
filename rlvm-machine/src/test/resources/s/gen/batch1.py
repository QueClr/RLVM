#!/usr/bin/env python3
"""Hostile exports, batch 1: levels, theorem-type, expected-type propagation, ill-typed terms that
evaluation erases, eta / unit-like / structure eta / K, projections, proof irrelevance and masks."""
from common import *


# ---------------------------------------------------------------------------------------------------------
# A. level substitution, closure identity (RECONCILIATION B1)
# ---------------------------------------------------------------------------------------------------------
def _swapped(C, good):
    X = C.X
    u, v = X.lp('u'), X.lp('v')
    ty = X.sort(X.ls(u, 2)); val = X.sort(X.ls(u))
    X.defn('S.A', ['u', 'v'], ty, val)
    X.defn('S.B', ['v', 'u'], ty, val)
    one, two = X.lnum(1), X.lnum(2)
    A12 = X.const('S.A', [one, two]); B12 = X.const('S.B', [one, two]); B21 = X.const('S.B', [two, one])
    s2 = X.sort(two)
    C.defn('S.okA', arrow(A12, A12), lam(s2, lambda x: x))
    C.defn('S.okB', arrow(A12, B21), lam(s2, lambda x: x))
    if not good:
        C.defn('S.bad', arrow(A12, B12), lam(s2, lambda x: x))

@case('lvl-swapped-params-ok', 'accept', 'B1 twin: A.{u,v} and B.{v,u} share type and value terms; B.{2,1} = A.{1,2}')
def _(C): _swapped(C, True)

@case('lvl-swapped-params-bad', 'reject', 'B1: A.{u,v} and B.{v,u} share type and value terms; B.{1,2} is Sort 3, not A.{1,2} = Sort 2')
def _(C): _swapped(C, False)


def _shared(C, good):
    X = C.X
    u = X.lp('u')
    X.defn('S.T', ['u'], X.sort(X.ls(u, 2)), X.sort(X.ls(u)))
    T0 = X.const('S.T', [0]); T1 = X.const('S.T', [X.lnum(1)])
    s1 = X.sort(X.lnum(1))
    C.defn('S.ok', arrow(T0, T0), lam(s1, lambda x: x))
    if not good:
        C.defn('S.bad', arrow(T0, T1), lam(s1, lambda x: x))

@case('lvl-shared-closed-subterm-ok', 'accept', 'B1 twin: T.{0} against T.{0}')
def _(C): _shared(C, True)

@case('lvl-shared-closed-subterm-bad', 'reject', 'B1: the closed body of T.{u} under two level arguments: T.{0} is not T.{1}')
def _(C): _shared(C, False)


def _simul(C, good):
    X = C.X
    u, v = X.lp('u'), X.lp('v')
    m = X.lmax(u, X.ls(v))
    X.defn('S.Sw', ['u', 'v'], X.sort(X.ls(m, 2)), X.sort(X.ls(m)))
    Swvu = X.const('S.Sw', [v, u])
    X.defn('S.good', ['u', 'v'], Swvu, X.sort(X.lmax(v, X.ls(u))))
    if not good:
        X.defn('S.bad', ['u', 'v'], Swvu, X.sort(X.lmax(u, X.ls(u))))

@case('lvl-simultaneous-subst-ok', 'accept', 'B1 twin: Sw.{v,u} instantiated simultaneously')
def _(C): _simul(C, True)

@case('lvl-simultaneous-subst-bad', 'reject', 'B1: Sw.{v,u} must be instantiated simultaneously (a sequential substitution gives max u (u+1))')
def _(C): _simul(C, False)


def _shadow(C, good):
    X = C.X
    u = X.lp('u')
    X.defn('S.T', ['u'], X.sort(X.ls(u, 2)), X.sort(X.ls(u)))
    Tu1 = X.const('S.T', [X.ls(u)])
    X.defn('S.good', ['u'], Tu1, X.sort(X.ls(u)))
    if not good:
        X.defn('S.bad', ['u'], Tu1, X.sort(u))

@case('lvl-param-shadow-ok', 'accept', 'B1 twin: T.{u+1} inside a declaration whose own parameter is u')
def _(C): _shadow(C, True)

@case('lvl-param-shadow-bad', 'reject', 'B1: T.{u+1} inside a declaration whose own parameter is u: the identity substitution must not be used')
def _(C): _shadow(C, False)


@case('lvl-arity-in-proof-bad', 'reject', 'level arity of a constant inside a theorem proof (proof block): id.{0,0}')
def _(C):
    X = C.X
    C.thm('S.ok', C.true, app(C.c('id', [0]), C.true, C.trivial))
    C.thm('S.bad', C.true, app(C.c('id', [0, 0]), C.true, C.trivial))

@case('lvl-undeclared-in-proof-bad', 'reject', 'an undeclared universe parameter inside a theorem proof')
def _(C):
    X = C.X
    w = X.lp('w')
    C.thm('S.bad', C.true, app(lam(X.sort(X.ls(w, 2)), lambda x: C.trivial), X.sort(X.ls(w))))

@case('lvl-warm-lparam-reuse-bad', 'reject', 'warm: a term with parameter u checked in a declaration with u, then reused by a declaration without u')
def _(C):
    X = C.X
    u = X.lp('u')
    t = lam(X.sort(X.ls(u, 2)), lambda x: C.trivial)
    a = X.sort(X.ls(u))
    C.thm('S.ok', C.true, app(t, a), ['u'])
    C.thm('S.bad', C.true, app(t, a), [])


# ---------------------------------------------------------------------------------------------------------
# B. theorem type must be a proposition
# ---------------------------------------------------------------------------------------------------------
@case('thm-type-nat-bad', 'reject', 'theorem whose type is Nat')
def _(C):
    C.thm('S.bad', C.nat, C.zero)

@case('thm-type-sort-u-bad', 'reject', 'theorem whose type lives in Sort u (not always Prop)')
def _(C):
    X = C.X; u = X.lp('u')
    C.thm('S.bad', C.c('PUnit', [u]), C.c('PUnit.unit', [u]), ['u'])

@case('thm-type-imax-ok', 'accept', 'theorem whose type lives in Sort (imax (u+1) 0): a proposition')
def _(C):
    X = C.X; u = X.lp('u')
    C.thm('S.ok', arrow(C.c('PUnit', [X.ls(u)]), C.true), lam(C.c('PUnit', [X.ls(u)]), lambda x: C.trivial), ['u'])

@case('thm-type-max-u-bad', 'reject', 'theorem whose type lives in Sort (max 0 u)')
def _(C):
    X = C.X; u = X.lp('u')
    # PProd.{0,u}? keep it simple: (PUnit.{u} -> PUnit.{u}) : Sort (imax u u)
    C.thm('S.bad', arrow(C.c('PUnit', [u]), C.c('PUnit', [u])), lam(C.c('PUnit', [u]), lambda x: x), ['u'])

@case('thm-type-via-def-ok', 'accept', 'theorem whose type is an axiom of type MyProp := Prop (ensure_sort needs whnf)')
def _(C):
    X = C.X
    C.defn('S.MyProp', C.type, C.prop)
    P = C.axiom('S.P', X.const('S.MyProp'))
    p = C.axiom('S.p', P())
    C.thm('S.ok', P(), p())

@case('thm-type-via-def-bad', 'reject', 'theorem whose type is an axiom of type MyType := Type')
def _(C):
    X = C.X
    C.defn('S.MyType', C.type1, C.type)
    P = C.axiom('S.P', X.const('S.MyType'))
    p = C.axiom('S.p', P())
    C.thm('S.bad', P(), p())


# ---------------------------------------------------------------------------------------------------------
# C. expected-type propagation (B9) and ill-typed terms that evaluation erases
# ---------------------------------------------------------------------------------------------------------
def illtyped_nat(C):
    """(fun (y : Bool) => Nat) Nat.zero: ill-typed, evaluates to Nat"""
    return app(lam(C.bool, lambda y: C.nat), C.zero)

@case('chk-lam-domain-bad', 'reject', 'def f : Nat -> Nat := fun (x : Bool) => Nat.zero')
def _(C):
    C.defn('S.bad', arrow(C.nat, C.nat), lam(C.bool, lambda x: C.zero))

@case('chk-lam-body-bad', 'reject', 'def f : Nat -> Nat := fun (x : Nat) => Bool.true')
def _(C):
    C.defn('S.bad', arrow(C.nat, C.nat), lam(C.nat, lambda x: C.btrue))

@case('chk-lam-second-domain-bad', 'reject', 'def f : Nat -> Nat -> Nat := fun (x : Nat) (y : Bool) => x')
def _(C):
    C.defn('S.bad', arrow(C.nat, arrow(C.nat, C.nat)), lam(C.nat, lambda x: lam(C.bool, lambda y: x)))

@case('chk-lam-expected-via-def-bad', 'reject', 'def T := Nat -> Nat; def f : T := fun (x : Bool) => Nat.zero')
def _(C):
    C.defn('S.T', C.type, arrow(C.nat, C.nat))
    C.defn('S.bad', C.k('S.T'), lam(C.bool, lambda x: C.zero))

def _apparg(C, arg):
    C.defn('S.ap', arrow(arrow(C.nat, C.nat), C.nat), lam(arrow(C.nat, C.nat), lambda f: app(f, C.zero)))
    C.defn('S.ok', C.nat, app(C.k('S.ap'), lam(C.nat, lambda x: x)))
    C.defn('S.bad', C.nat, app(C.k('S.ap'), arg))

@case('chk-arg-lam-domain-bad', 'reject', 'B9: a lambda argument with the wrong binder type')
def _(C): _apparg(C, lam(C.bool, lambda x: C.zero))

@case('chk-arg-lam-body-bad', 'reject', 'B9: a lambda argument with the wrong body type')
def _(C): _apparg(C, lam(C.nat, lambda x: C.btrue))

@case('chk-arg-lam-extra-binder-bad', 'reject', 'B9: a lambda argument that returns a function where Nat is expected')
def _(C): _apparg(C, lam(C.nat, lambda x: lam(C.nat, lambda y: x)))

@case('chk-arg-lam-illtyped-domain-bad', 'reject', 'B9: the binder type of a lambda argument is ill-typed but evaluates to the expected domain')
def _(C): _apparg(C, lam(illtyped_nat(C), lambda x: x))

@case('chk-arg-lam-illtyped-body-bad', 'reject', 'B9: the body of a lambda argument is ill-typed but evaluates to a Nat')
def _(C): _apparg(C, lam(C.nat, lambda x: app(lam(C.bool, lambda y: x), C.zero)))

@case('chk-arg-lam-nonsort-domain-bad', 'reject', 'B9: the binder type of a lambda argument is not a type (Nat.zero)')
def _(C): _apparg(C, lam(C.zero, lambda x: C.zero))

@case('chk-def-lam-illtyped-domain-bad', 'reject', 'the binder type of the value lambda is ill-typed but evaluates to Nat')
def _(C):
    C.defn('S.bad', arrow(C.nat, C.nat), lam(illtyped_nat(C), lambda x: x))

@case('chk-let-illtyped-type-bad', 'reject', 'let x : <ill-typed, evaluates to Nat> := Nat.zero; x')
def _(C):
    C.defn('S.bad', C.nat, let_(illtyped_nat(C), C.zero, lambda x: x))

@case('chk-let-unused-value-bad', 'reject', 'let x : Nat := Bool.true; Nat.zero (the variable is not used)')
def _(C):
    C.defn('S.bad', C.nat, let_(C.nat, C.btrue, lambda x: C.zero))

@case('chk-let-unused-type-bad', 'reject', 'let x : Bool := Nat.zero; Nat.zero')
def _(C):
    C.defn('S.bad', C.nat, let_(C.bool, C.zero, lambda x: C.zero))

@case('chk-let-type-not-sort-bad', 'reject', 'let x : Nat.zero := Nat.zero; Nat.zero')
def _(C):
    C.defn('S.bad', C.nat, let_(C.zero, C.zero, lambda x: C.zero))

@case('chk-unused-arg-bad', 'reject', '(fun (x : Nat) => Nat.zero) Bool.true: the unused argument is ill-typed')
def _(C):
    C.defn('S.bad', C.nat, app(lam(C.nat, lambda x: C.zero), C.btrue))

@case('chk-proof-arg-bad', 'reject', '(fun (h : True) => True.intro) Nat.zero inside a theorem proof')
def _(C):
    C.thm('S.bad', C.true, app(lam(C.true, lambda h: C.trivial), C.zero))

@case('chk-decl-type-illtyped-bad', 'reject', 'the declared type is ill-typed but evaluates to Nat')
def _(C):
    C.defn('S.bad', illtyped_nat(C), C.zero)

@case('chk-decl-pi-domain-illtyped-bad', 'reject', 'a Pi domain of the declared type is ill-typed but evaluates to Nat')
def _(C):
    C.defn('S.bad', arrow(illtyped_nat(C), C.nat), lam(C.nat, lambda x: x))

@case('chk-axiom-type-illtyped-bad', 'reject', 'an axiom whose type is ill-typed but evaluates to Nat')
def _(C):
    C.axiom('S.bad', illtyped_nat(C))

@case('chk-axiom-type-not-sort-bad', 'reject', 'an axiom whose type is Nat.zero')
def _(C):
    C.axiom('S.bad', C.zero)

@case('chk-thm-illtyped-in-irrelevant-bad', 'reject', 'a proof that contains an ill-typed application in a position proof irrelevance never looks at')
def _(C):
    p = C.axiom('S.p', C.true)
    f = C.axiom('S.f', arrow(C.true, C.true))
    C.thm('S.ok', C.eq(0, C.true, app(f(), p()), p()), C.refl(0, C.true, p()))
    C.thm('S.bad', C.eq(0, C.true, app(f(), app(f(), C.zero)), p()), C.refl(0, C.true, p()))

@case('chk-same-lambda-two-types-bad', 'reject', 'CHECK memo: the same lambda term at two argument positions with different expected types')
def _(C):
    X = C.X
    NN = arrow(C.nat, C.nat); NB = arrow(C.nat, C.bool)
    idn = lam(C.nat, lambda x: x)
    pr = C.c('Prod', [0, 0]); mk = C.c('Prod.mk', [0, 0])
    C.defn('S.ok', app(pr, NN, NN), app(mk, NN, NN, idn, idn))
    C.defn('S.bad', app(pr, NN, NB), app(mk, NN, NB, idn, idn))

@case('chk-warm-same-lambda-two-types-bad', 'reject', 'warm CHECK memo: the same lambda term checked against Nat -> Nat, then against Nat -> Bool in the next declaration')
def _(C):
    idn = lam(C.nat, lambda x: x)
    C.defn('S.ok', arrow(C.nat, C.nat), idn)
    C.defn('S.bad', arrow(C.nat, C.bool), idn)

@case('chk-open-term-shared-body-bad', 'reject', 'the open body term Nat.succ #0 under a Nat binder and then under a Bool binder')
def _(C):
    X = C.X
    NN = arrow(C.nat, C.nat); BN = arrow(C.bool, C.nat)
    pr = C.c('Prod', [0, 0]); mk = C.c('Prod.mk', [0, 0])
    f1 = lam(C.nat, lambda x: app(C.succ, x)); f2 = lam(C.bool, lambda x: app(C.succ, x))
    C.defn('S.bad', app(pr, NN, BN), app(mk, NN, BN, f1, f2))

@case('chk-open-term-shared-let-bad', 'reject', 'the open body term Nat.succ #0 under let x : Nat := 0 and then under let x : Bool := true')
def _(C):
    pr = C.c('Prod', [0, 0]); mk = C.c('Prod.mk', [0, 0])
    a = let_(C.nat, C.zero, lambda x: app(C.succ, x)); b = let_(C.bool, C.btrue, lambda x: app(C.succ, x))
    C.defn('S.bad', app(pr, C.nat, C.nat), app(mk, C.nat, C.nat, a, b))

@case('chk-proj-wrong-struct-bad', 'reject', 'projection Prod.0 of Nat.zero')
def _(C):
    C.c('Prod', [0, 0])
    C.defn('S.bad', C.nat, proj('Prod', 0, C.zero))

@case('chk-proj-wrong-name-bad', 'reject', 'projection named Prod applied to a value of another structure with the same shape')
def _(C):
    X = C.X
    C.c('Prod', [0, 0])
    mk_inductive(X, 'S.P2', [], [], [], X.lnum(1), [('S.P2.mk', [C.nat, C.nat], lambda *a: [])])
    x = C.axiom('S.x', C.k('S.P2'))
    C.defn('S.ok', C.nat, proj('S.P2', 0, x()))
    C.defn('S.bad', C.nat, proj('Prod', 0, x()))

@case('chk-proj-index-range-bad', 'reject', 'projection index 2 of a structure with two fields')
def _(C):
    p = C.axiom('S.p', app(C.c('Prod', [0, 0]), C.nat, C.nat))
    C.defn('S.ok', C.nat, proj('Prod', 1, p()))
    C.defn('S.bad', C.nat, proj('Prod', 2, p()))

@case('chk-proj-field-type-bad', 'reject', 'projection 1 of Prod Nat Bool claimed to be a Nat')
def _(C):
    p = C.axiom('S.p', app(C.c('Prod', [0, 0]), C.nat, C.bool))
    C.defn('S.ok', C.bool, proj('Prod', 1, p()))
    C.defn('S.bad', C.nat, proj('Prod', 1, p()))


# ---------------------------------------------------------------------------------------------------------
# D. eta, unit-like, structure eta, K, iota
# ---------------------------------------------------------------------------------------------------------
@case('eta-ok', 'accept', 'fun x => Nat.succ x against Nat.succ, and against an axiom of a type that needs whnf to be a Pi')
def _(C):
    NN = arrow(C.nat, C.nat)
    C.thm('S.e1', C.eq(C.l1, NN, lam(C.nat, lambda x: app(C.succ, x)), C.succ), C.refl(C.l1, NN, C.succ))
    C.defn('S.T', C.type, NN)
    f = C.axiom('S.f', C.k('S.T'))
    C.thm('S.e2', C.eq(C.l1, NN, lam(C.nat, lambda x: app(f(), x)), f()), C.refl(C.l1, NN, f()))

@case('eta-wrong-fn-bad', 'reject', 'fun x => f x against g')
def _(C):
    NN = arrow(C.nat, C.nat)
    f = C.axiom('S.f', NN); g = C.axiom('S.g', NN)
    C.thm('S.bad', C.eq(C.l1, NN, lam(C.nat, lambda x: app(f(), x)), g()), C.refl(C.l1, NN, g()))

@case('eta-arg-swap-bad', 'reject', 'fun x y => f y x against f')
def _(C):
    NNN = arrow(C.nat, arrow(C.nat, C.nat))
    f = C.axiom('S.f', NNN)
    C.thm('S.bad', C.eq(C.l1, NNN, lam(C.nat, lambda x: lam(C.nat, lambda y: app(f(), y, x))), f()), C.refl(C.l1, NNN, f()))

def _unit(C, kind, good):
    X = C.X
    if kind == 'plain':
        mk_inductive(X, 'S.U', [], [], [], X.lnum(1), [('S.U.mk', [], lambda *a: [])])
        T = C.k('S.U')
    elif kind == 'param':
        mk_inductive(X, 'S.U', [], [C.nat], [], X.lnum(1), [('S.U.mk', [], lambda *a: [])])
        T = app(C.k('S.U'), C.zero)
    elif kind == 'indexed':
        mk_inductive(X, 'S.U', [], [], [C.nat], X.lnum(1), [('S.U.mk', [], lambda *a: [C.zero])])
        T = app(C.k('S.U'), C.zero)
    elif kind == 'two':
        mk_inductive(X, 'S.U', [], [], [], X.lnum(1), [('S.U.a', [], lambda *a: []), ('S.U.b', [], lambda *a: [])])
        T = C.k('S.U')
    elif kind == 'field':
        mk_inductive(X, 'S.U', [], [], [], X.lnum(1), [('S.U.mk', [C.nat], lambda *a: [])])
        T = C.k('S.U')
    x = C.axiom('S.x', T); y = C.axiom('S.y', T)
    C.thm('S.t', C.eq(C.l1, T, x(), y()), C.refl(C.l1, T, x()))

@case('unitlike-ok', 'accept', 'two axioms of a structure without fields are definitionally equal')
def _(C): _unit(C, 'plain', True)

@case('unitlike-param-ok', 'accept', 'unit-like with a parameter')
def _(C): _unit(C, 'param', True)

@case('unitlike-indexed-bad', 'reject', 'one constructor without fields but an index: not unit-like')
def _(C): _unit(C, 'indexed', False)

@case('unitlike-two-ctors-bad', 'reject', 'two constructors without fields: not unit-like')
def _(C): _unit(C, 'two', False)

@case('unitlike-field-bad', 'reject', 'one constructor with a field: not unit-like')
def _(C): _unit(C, 'field', False)

@case('unitlike-param-mismatch-bad', 'reject', 'unit-like values of U 0 and U 1 compared (the types differ)')
def _(C):
    X = C.X
    mk_inductive(X, 'S.U', [], [C.nat], [], X.lnum(1), [('S.U.mk', [], lambda *a: [])])
    U = C.k('S.U')
    f = C.axiom('S.f', pi(C.nat, lambda n: app(U, n)))
    g = C.axiom('S.g', arrow(app(U, C.zero), C.nat))
    C.thm('S.ok', C.eqnat(app(g(), app(f(), C.zero)), app(g(), app(C.k('S.U.mk'), C.zero))), C.reflnat(app(g(), app(f(), C.zero))))
    # heterogeneous comparison forced through an ill-typed congruence is impossible; compare U-values of
    # different parameters under a function that accepts any of them
    h = C.axiom('S.h', pi(C.nat, lambda n: arrow(app(U, n), C.nat)))
    C.thm('S.bad', C.eqnat(app(h(), C.zero, app(f(), C.zero)), app(h(), C.lit(1), app(f(), C.lit(1)))),
          C.reflnat(app(h(), C.zero, app(f(), C.zero))))

def _etastruct(C, kind):
    X = C.X
    if kind == 'ok':
        PNN = app(C.c('Prod', [0, 0]), C.nat, C.nat)
        p = C.axiom('S.p', PNN)
        lhs = app(C.c('Prod.mk', [0, 0]), C.nat, C.nat, proj('Prod', 0, p()), proj('Prod', 1, p()))
        C.thm('S.t', C.eq(C.l1, PNN, lhs, p()), C.refl(C.l1, PNN, p()))
        C.thm('S.t2', C.eq(C.l1, PNN, p(), lhs), C.refl(C.l1, PNN, p()))
    elif kind == 'swap':
        PNN = app(C.c('Prod', [0, 0]), C.nat, C.nat)
        p = C.axiom('S.p', PNN)
        lhs = app(C.c('Prod.mk', [0, 0]), C.nat, C.nat, proj('Prod', 1, p()), proj('Prod', 0, p()))
        C.thm('S.t', C.eq(C.l1, PNN, lhs, p()), C.refl(C.l1, PNN, p()))
    elif kind == 'other':
        PNN = app(C.c('Prod', [0, 0]), C.nat, C.nat)
        p = C.axiom('S.p', PNN); q = C.axiom('S.q', PNN)
        lhs = app(C.c('Prod.mk', [0, 0]), C.nat, C.nat, proj('Prod', 0, p()), proj('Prod', 1, q()))
        C.thm('S.t', C.eq(C.l1, PNN, lhs, p()), C.refl(C.l1, PNN, p()))
    elif kind in ('rec', 'rec-lie'):
        # R : Type, mk : Nat -> R -> R (recursive: not structure-like)
        mk_inductive(X, 'S.R', [], [], [], X.lnum(1), [('S.R.mk', [C.nat, ('rec', lambda *a: [])], lambda *a: [])])
        if kind == 'rec-lie':
            # forge isRec = false in the record just written
            import json
            o = json.loads(X.lines[-1]); o['inductive']['types'][0]['isRec'] = False
            X.lines[-1] = dumps(o)
        R = C.k('S.R')
        r = C.axiom('S.r', R)
        lhs = app(C.k('S.R.mk'), proj('S.R', 0, r()), proj('S.R', 1, r()))
        C.thm('S.t', C.eq(C.l1, R, lhs, r()), C.refl(C.l1, R, r()))
    elif kind == 'indexed':
        # V : Nat -> Type, mk : Nat -> V 0  (an index: not structure-like)
        mk_inductive(X, 'S.V', [], [], [C.nat], X.lnum(1), [('S.V.mk', [C.nat], lambda *a: [C.zero])])
        V0 = app(C.k('S.V'), C.zero)
        r = C.axiom('S.r', V0)
        lhs = app(C.k('S.V.mk'), proj('S.V', 0, r()))
        C.thm('S.t', C.eq(C.l1, V0, lhs, r()), C.refl(C.l1, V0, r()))

@case('eta-struct-ok', 'accept', 'Prod.mk p.1 p.2 against p, both directions')
def _(C): _etastruct(C, 'ok')

@case('eta-struct-swapped-bad', 'reject', 'Prod.mk p.2 p.1 against p')
def _(C): _etastruct(C, 'swap')

@case('eta-struct-other-bad', 'reject', 'Prod.mk p.1 q.2 against p')
def _(C): _etastruct(C, 'other')

@case('eta-struct-recursive-bad', 'reject', 'structure eta on a recursive one-constructor type (not structure-like)')
def _(C): _etastruct(C, 'rec')

@case('eta-struct-recursive-forged-flag-bad', 'reject', 'the same with isRec forged to false in the export (the flag must be recomputed)')
def _(C): _etastruct(C, 'rec-lie')

@case('eta-struct-indexed-bad', 'reject', 'structure eta on a one-constructor family with an index (not structure-like)')
def _(C): _etastruct(C, 'indexed')


def _iota_struct(C, kind):
    X = C.X
    if kind == 'type':
        PNN = app(C.c('Prod', [0, 0]), C.nat, C.nat)
        p = C.axiom('S.p', PNN)
        r = app(C.c('Prod.rec', [C.l1, 0, 0]), C.nat, C.nat, lam(PNN, lambda t: C.nat),
                lam(C.nat, lambda a: lam(C.nat, lambda b: C.lit(5))), p())
        C.thm('S.t', C.eqnat(r, C.lit(5)), C.reflnat(C.lit(5)))
    elif kind == 'prop':
        A = app(C.c('And'), C.true, C.true)
        h = C.axiom('S.h', A)
        r = app(C.c('And.rec', [C.l1]), C.true, C.true, lam(A, lambda t: C.nat),
                lam(C.true, lambda a: lam(C.true, lambda b: C.lit(5))), h())
        C.thm('S.t', C.eqnat(r, C.lit(5)), C.reflnat(C.lit(5)))
    elif kind == 'prop-ctor':
        A = app(C.c('And'), C.true, C.true)
        r = app(C.c('And.rec', [C.l1]), C.true, C.true, lam(A, lambda t: C.nat),
                lam(C.true, lambda a: lam(C.true, lambda b: C.lit(5))), app(C.c('And.intro'), C.true, C.true, C.trivial, C.trivial))
        C.thm('S.t', C.eqnat(r, C.lit(5)), C.reflnat(C.lit(5)))

@case('iota-struct-eta-type-ok', 'accept', 'Prod.rec on an axiom: structure eta in iota')
def _(C): _iota_struct(C, 'type')

@case('iota-struct-eta-prop-bad', 'reject', 'And.rec into Nat on an axiom: no structure eta for a proposition')
def _(C): _iota_struct(C, 'prop')

@case('iota-struct-prop-ctor-ok', 'accept', 'And.rec into Nat on And.intro: plain iota')
def _(C): _iota_struct(C, 'prop-ctor')


def _k(C, kind):
    X = C.X
    eqrec = C.c('Eq.rec', [C.l1, C.l1])
    def rec(a, b, h):
        return app(eqrec, C.nat, a, lam(C.nat, lambda x: lam(C.eq(C.l1, C.nat, a, x), lambda t: C.nat)), C.lit(5), b, h)
    if kind == 'ok':
        h = C.axiom('S.h', C.eqnat(C.zero, C.zero))
        C.thm('S.t', C.eqnat(rec(C.zero, C.zero, h()), C.lit(5)), C.reflnat(C.lit(5)))
    elif kind == 'ok-defeq':
        h = C.axiom('S.h', C.eqnat(C.lit(2), app(C.c('Nat.add'), C.lit(1), C.lit(1))))
        C.thm('S.t', C.eqnat(rec(C.lit(2), app(C.c('Nat.add'), C.lit(1), C.lit(1)), h()), C.lit(5)), C.reflnat(C.lit(5)))
    elif kind == 'bad':
        h = C.axiom('S.h', C.eqnat(C.zero, C.lit(1)))
        C.thm('S.t', C.eqnat(rec(C.zero, C.lit(1), h()), C.lit(5)), C.reflnat(C.lit(5)))
    elif kind == 'bad-var':
        n = C.axiom('S.n', C.nat)
        h = C.axiom('S.h', C.eqnat(C.zero, n()))
        C.thm('S.t', C.eqnat(rec(C.zero, n(), h()), C.lit(5)), C.reflnat(C.lit(5)))

@case('k-eq-rec-ok', 'accept', 'K: Eq.rec on an axiom of 0 = 0 reduces')
def _(C): _k(C, 'ok')

@case('k-eq-rec-defeq-index-ok', 'accept', 'K: Eq.rec on an axiom of 2 = 1 + 1 reduces (indices definitionally equal)')
def _(C): _k(C, 'ok-defeq')

@case('k-eq-rec-wrong-index-bad', 'reject', 'K: Eq.rec on an axiom of 0 = 1 must not reduce')
def _(C): _k(C, 'bad')

@case('k-eq-rec-open-index-bad', 'reject', 'K: Eq.rec on an axiom of 0 = n (n an axiom) must not reduce')
def _(C): _k(C, 'bad-var')


# ---------------------------------------------------------------------------------------------------------
# E. projections of propositions
# ---------------------------------------------------------------------------------------------------------
def _pidx(C, kind):
    X = C.X
    # PI : Nat -> Prop, mk : (n : Nat) -> True -> PI n     (fields: n data, h proof; the result type reads n)
    mk_inductive(X, 'S.PI', [], [], [C.nat], 0, [('S.PI.mk', [C.nat, lambda n: C.true], lambda n, h: [n])],
                 large=False, is_prop=True)
    x = C.axiom('S.x', app(C.k('S.PI'), C.lit(5)))
    if kind == 'h':
        C.thm('S.t', C.true, proj('S.PI', 1, x()))
    elif kind == 'n':
        C.defn('S.t', C.nat, proj('S.PI', 0, x()))

@case('proj-prop-indexed-later-field-bad', 'reject', 'projection of the proof field of a Prop family whose result type reads the data field')
def _(C): _pidx(C, 'h')

@case('proj-prop-indexed-data-field-bad', 'reject', 'projection of the data field of a Prop family')
def _(C): _pidx(C, 'n')

def _ptype(C, kind):
    X = C.X
    # TI : Nat -> Type, mk : (n : Nat) -> Bool -> TI n
    mk_inductive(X, 'S.TI', [], [], [C.nat], X.lnum(1), [('S.TI.mk', [C.nat, lambda n: C.bool], lambda n, b: [n])])
    x = C.axiom('S.x', app(C.k('S.TI'), C.lit(5)))
    if kind == 'ok':
        C.defn('S.t', C.bool, proj('S.TI', 1, x()))
        C.defn('S.t0', C.nat, proj('S.TI', 0, x()))
    elif kind == 'bad':
        C.defn('S.t', C.bool, proj('S.TI', 0, x()))
    elif kind == 'red-bad':
        v = app(C.k('S.TI.mk'), C.lit(5), C.btrue)
        C.thm('S.ok', C.eq(C.l1, C.bool, proj('S.TI', 1, v), C.btrue), C.refl(C.l1, C.bool, C.btrue))
        C.thm('S.t', C.eq(C.l1, C.bool, proj('S.TI', 1, v), C.bfalse), C.refl(C.l1, C.bool, C.bfalse))

@case('proj-indexed-type-ok', 'accept', 'projections of a one-constructor Type family with an index')
def _(C): _ptype(C, 'ok')

@case('proj-indexed-type-field-bad', 'reject', 'projection 0 (a Nat) of the family claimed to be a Bool')
def _(C): _ptype(C, 'bad')

@case('proj-indexed-reduce-bad', 'reject', 'projection 1 of TI.mk 5 true claimed to be false')
def _(C): _ptype(C, 'red-bad')

def _pand(C, kind):
    X = C.X
    # Ex : Prop, mk : (n : Nat) -> (h : n = n) -> Ex     (small elimination; h depends on n)
    mk_inductive(X, 'S.Ex', [], [], [], 0, [('S.Ex.mk', [C.nat, lambda n: C.eqnat(n, n)], lambda *a: [])],
                 large=False, is_prop=True)
    x = C.axiom('S.x', C.k('S.Ex'))
    if kind == 'w':
        C.defn('S.t', C.nat, proj('S.Ex', 0, x()))
    elif kind == 'h':
        C.thm('S.t', C.eqnat(proj('S.Ex', 0, x()), proj('S.Ex', 0, x())), proj('S.Ex', 1, x()))
    elif kind == 'and-ok':
        A = app(C.c('And'), C.true, C.true)
        h = C.axiom('S.h', A)
        C.thm('S.t', C.true, proj('And', 0, h()))
        C.thm('S.t1', C.true, proj('And', 1, h()))

@case('proj-prop-witness-bad', 'reject', 'projection of the witness of an existential-like proposition')
def _(C): _pand(C, 'w')

@case('proj-prop-dependent-proof-bad', 'reject', 'projection of the proof field that depends on the witness')
def _(C): _pand(C, 'h')

@case('proj-prop-and-ok', 'accept', 'projections of And')
def _(C): _pand(C, 'and-ok')


# ---------------------------------------------------------------------------------------------------------
# F. proof irrelevance, the static proof filter and argument masks (step 2, switch facts)
# ---------------------------------------------------------------------------------------------------------
@case('pirr-ok', 'accept', 'two axioms of True are equal; two proofs under a function')
def _(C):
    p = C.axiom('S.p', C.true); q = C.axiom('S.q', C.true)
    C.thm('S.t', C.eq(0, C.true, p(), q()), C.refl(0, C.true, p()))

@case('pirr-data-bad', 'reject', 'two axioms of Nat are not equal')
def _(C):
    p = C.axiom('S.p', C.nat); q = C.axiom('S.q', C.nat)
    C.thm('S.t', C.eqnat(p(), q()), C.reflnat(p()))

def _poly(C, good, imax=False):
    X = C.X
    u = X.lp('u'); v = X.lp('v')
    if imax:
        lv = X.limax(u, v)
        ty = pi(X.sort(lv), lambda A: A)
        c = C.axiom('S.c', ty, ['u', 'v']); d = C.axiom('S.d', ty, ['u', 'v'])
        pl = [C.l1, 0]; dl = [0, C.l1]
    else:
        ty = pi(X.sort(u), lambda A: A)
        c = C.axiom('S.c', ty, ['u']); d = C.axiom('S.d', ty, ['u'])
        pl = [0]; dl = [C.l1]
    # at Prop: proofs, equal by proof irrelevance (10 times: let the K1 threshold pass)
    for i in range(12):
        C.thm('S.ok%d' % i, C.eq(0, C.true, app(c(*pl), C.true), app(d(*pl), C.true)), C.refl(0, C.true, app(c(*pl), C.true)))
    if not good:
        C.thm('S.bad', C.eqnat(app(c(*dl), C.nat), app(d(*dl), C.nat)), C.reflnat(app(c(*dl), C.nat)))

@case('pirr-poly-result-ok', 'accept', 'c.{u} : (A : Sort u) -> A at u = 0: a proof')
def _(C): _poly(C, True)

@case('pirr-poly-result-bad', 'reject', 'static proof filter: c.{u} : (A : Sort u) -> A is a proof at u = 0 and data at u = 1')
def _(C): _poly(C, False)

@case('pirr-imax-result-ok', 'accept', 'c.{u,v} : (A : Sort (imax u v)) -> A at (1, 0): a proof')
def _(C): _poly(C, True, True)

@case('pirr-imax-result-bad', 'reject', 'static proof filter: result sort imax u v is Prop at (1, 0) and Type at (0, 1)')
def _(C): _poly(C, False, True)

def _overapp(C, good):
    X = C.X
    # T : Type := Nat -> Nat;  g : Nat -> T;  g 0 1 : Nat is data although g's telescope ends in T
    C.defn('S.T', C.type, arrow(C.nat, C.nat))
    C.defn('S.P', C.prop, arrow(C.nat, C.true))
    g = C.axiom('S.g', arrow(C.nat, C.k('S.T'))); g2 = C.axiom('S.g2', arrow(C.nat, C.k('S.T')))
    f = C.axiom('S.f', arrow(C.nat, C.k('S.P'))); f2 = C.axiom('S.f2', arrow(C.nat, C.k('S.P')))
    for i in range(12):
        C.thm('S.ok%d' % i, C.eq(0, C.true, app(f(), C.zero, C.lit(i)), app(f2(), C.zero, C.lit(i))), C.refl(0, C.true, app(f(), C.zero, C.lit(i))))
    if not good:
        C.thm('S.bad', C.eqnat(app(g(), C.zero, C.lit(1)), app(g2(), C.zero, C.lit(1))), C.reflnat(app(g(), C.zero, C.lit(1))))

@case('pirr-overapplied-ok', 'accept', 'over-applied constants whose telescope ends in a definition that unfolds to Nat -> True')
def _(C): _overapp(C, True)

@case('pirr-overapplied-bad', 'reject', 'static proof filter on an over-applied constant: g 0 1 : Nat is data')
def _(C): _overapp(C, False)

def _mask(C, kind):
    X = C.X
    f = C.axiom('S.f', arrow(C.nat, arrow(C.true, arrow(C.nat, C.nat))))
    h1 = C.axiom('S.h1', C.true); h2 = C.axiom('S.h2', C.true)
    def t(name, a, b):
        C.thm(name, C.eqnat(a, b), C.reflnat(a))
    for i in range(12):
        t('S.ok%d' % i, app(f(), C.lit(1), h1(), C.lit(i)), app(f(), C.lit(1), h2(), C.lit(i)))
    if kind == 'last':
        t('S.bad', app(f(), C.lit(1), h1(), C.lit(2)), app(f(), C.lit(1), h2(), C.lit(3)))
    elif kind == 'first':
        t('S.bad', app(f(), C.lit(1), h1(), C.lit(2)), app(f(), C.lit(2), h2(), C.lit(2)))

@case('mask-prop-position-ok', 'accept', 'f 1 h1 i against f 1 h2 i: the proof position is skipped or equal by irrelevance')
def _(C): _mask(C, 'ok')

@case('mask-after-prop-position-bad', 'reject', 'mask: the argument AFTER a proof position differs')
def _(C): _mask(C, 'last')

@case('mask-before-prop-position-bad', 'reject', 'mask: the argument BEFORE a proof position differs')
def _(C): _mask(C, 'first')

def _maskpoly(C, kind):
    X = C.X
    u = X.lp('u'); v = X.lp('v')
    if kind.startswith('imax'):
        f = C.axiom('S.f', pi(X.sort(X.limax(u, v)), lambda A: arrow(A, C.nat)), ['u', 'v'])
        pl = [C.l1, 0]; dl = [0, C.l1]
    else:
        f = C.axiom('S.f', pi(X.sort(u), lambda A: arrow(A, C.nat)), ['u'])
        pl = [0]; dl = [C.l1]
    h1 = C.axiom('S.h1', C.true); h2 = C.axiom('S.h2', C.true)
    for i in range(12):
        C.thm('S.ok%d' % i, C.eqnat(app(f(*pl), C.true, h1()), app(f(*pl), C.true, h2())), C.reflnat(app(f(*pl), C.true, h1())))
    if kind.endswith('bad'):
        C.thm('S.bad', C.eqnat(app(f(*dl), C.nat, C.lit(1)), app(f(*dl), C.nat, C.lit(2))), C.reflnat(app(f(*dl), C.nat, C.lit(1))))

@case('mask-poly-position-ok', 'accept', 'f.{u} : (A : Sort u) -> A -> Nat at u = 0: the second argument is a proof')
def _(C): _maskpoly(C, 'ok')

@case('mask-poly-position-bad', 'reject', 'mask: the same position is a proof at u = 0 and data at u = 1 (f.{1} Nat 1 against f.{1} Nat 2)')
def _(C): _maskpoly(C, 'bad')

@case('mask-imax-position-bad', 'reject', 'mask: a position of sort imax u v, Prop at (1, 0), Type at (0, 1)')
def _(C): _maskpoly(C, 'imax-bad')

def _maskdep(C, kind):
    X = C.X
    NP = arrow(C.nat, C.prop)
    f = C.axiom('S.f', pi(NP, lambda Q: pi(C.nat, lambda n: arrow(app(Q, n), C.nat))))
    Q = C.axiom('S.Q', NP); Q2 = C.axiom('S.Q2', NP)
    h = C.axiom('S.h', pi(C.nat, lambda n: app(Q(), n)))
    k = C.axiom('S.k', pi(C.nat, lambda n: app(Q(), n)))
    h2 = C.axiom('S.h2', pi(C.nat, lambda n: app(Q2(), n)))
    def t(name, a, b):
        C.thm(name, C.eqnat(a, b), C.reflnat(a))
    for i in range(12):
        t('S.ok%d' % i, app(f(), Q(), C.lit(i), app(h(), C.lit(i))), app(f(), Q(), C.lit(i), app(k(), C.lit(i))))
    if kind == 'n':
        t('S.bad', app(f(), Q(), C.lit(1), app(h(), C.lit(1))), app(f(), Q(), C.lit(2), app(h(), C.lit(2))))
    elif kind == 'Q':
        t('S.bad', app(f(), Q(), C.lit(1), app(h(), C.lit(1))), app(f(), Q2(), C.lit(1), app(h2(), C.lit(1))))

@case('mask-dependent-prop-ok', 'accept', 'f Q n (h n) against f Q n (k n): the proof position depends on earlier arguments')
def _(C): _maskdep(C, 'ok')

@case('mask-dependent-prop-n-bad', 'reject', 'mask: f Q 1 (h 1) against f Q 2 (h 2)')
def _(C): _maskdep(C, 'n')

@case('mask-dependent-prop-q-bad', 'reject', 'mask: f Q 1 _ against f Q2 1 _')
def _(C): _maskdep(C, 'Q')

@case('mask-unread-arg-opaque-bad', 'reject', 'an opaque constant that ignores its argument: K 1 against K 2 must not be equal')
def _(C):
    C.opaque('S.K', arrow(C.nat, C.nat), lam(C.nat, lambda x: C.zero))
    K = C.k('S.K')
    for i in range(12):
        C.thm('S.ok%d' % i, C.eqnat(app(K, C.lit(i)), app(K, C.lit(i))), C.reflnat(app(K, C.lit(i))))
    C.thm('S.bad', C.eqnat(app(K, C.lit(1)), app(K, C.lit(2))), C.reflnat(app(K, C.lit(1))))

@case('mask-unread-arg-def-ok', 'accept', 'a definition that ignores its argument: K 1 against K 2 (delta)')
def _(C):
    C.defn('S.K', arrow(C.nat, C.nat), lam(C.nat, lambda x: C.zero))
    K = C.k('S.K')
    for i in range(12):
        C.thm('S.ok%d' % i, C.eqnat(app(K, C.lit(i)), app(K, C.lit(i + 1))), C.reflnat(app(K, C.lit(i))))

@case('mask-unread-arg-axiom-bad', 'reject', 'an axiom function: K 1 against K 2')
def _(C):
    K = C.axiom('S.K', arrow(C.nat, C.nat))
    C.thm('S.bad', C.eqnat(app(K(), C.lit(1)), app(K(), C.lit(2))), C.reflnat(app(K(), C.lit(1))))

@case('mask-unread-ctor-param-bad', 'reject', 'constructor parameters differ: Prod.mk Nat Nat 1 2 is not compared equal to a pair with other fields')
def _(C):
    mk = C.c('Prod.mk', [0, 0]); PNN = app(C.c('Prod', [0, 0]), C.nat, C.nat)
    a = app(mk, C.nat, C.nat, C.lit(1), C.lit(2)); b = app(mk, C.nat, C.nat, C.lit(1), C.lit(3))
    C.thm('S.bad', C.eq(C.l1, PNN, a, b), C.refl(C.l1, PNN, a))

@case('mask-theorem-args-bad', 'reject', 'two applications of a theorem-valued function to different data compared as data through a wrapper')
def _(C):
    # w : Nat -> True -> Nat (axiom); t : (n : Nat) -> True (theorem). w 1 (t 1) against w 2 (t 2)
    w = C.axiom('S.w', arrow(C.nat, arrow(C.true, C.nat)))
    C.thm('S.t', arrow(C.nat, C.true), lam(C.nat, lambda n: C.trivial))
    t = C.k('S.t')
    for i in range(12):
        C.thm('S.ok%d' % i, C.eqnat(app(w(), C.lit(i), app(t, C.lit(1))), app(w(), C.lit(i), app(t, C.lit(2)))), C.reflnat(app(w(), C.lit(i), app(t, C.lit(1)))))
    C.thm('S.bad', C.eqnat(app(w(), C.lit(1), app(t, C.lit(1))), app(w(), C.lit(2), app(t, C.lit(2)))), C.reflnat(app(w(), C.lit(1), app(t, C.lit(1)))))


if __name__ == '__main__':
    rows = emit_all()
    write_manifest(rows, 'batch1.tsv')
    print(len(rows), 'exports written to', OUT)
