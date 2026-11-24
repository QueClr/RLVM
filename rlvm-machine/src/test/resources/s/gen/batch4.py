#!/usr/bin/env python3
"""Hostile exports, batch 4: Nat literals across the representation boundaries (immediate below 2^31, two-word
NBIG below 2^63, NatStore above), Nat.succ offsets, literal identity by content, string literals."""
from common import *

P31, P32, P63, P64 = 2 ** 31, 2 ** 32, 2 ** 63, 2 ** 64


def op(C, name, a, b):
    return app(C.c('Nat.' + name), a if not isinstance(a, int) or a < 0 else a, b)

def L(C, n): return C.lit(n)

def nat_claims(C):
    """(lhs tree, rhs tree) of TRUE claims; the false ones are derived by replacing the right side"""
    add = lambda a, b: app(C.c('Nat.add'), L(C, a), L(C, b))
    sub = lambda a, b: app(C.c('Nat.sub'), L(C, a), L(C, b))
    mul = lambda a, b: app(C.c('Nat.mul'), L(C, a), L(C, b))
    pw = lambda a, b: app(C.c('Nat.pow'), L(C, a), L(C, b))
    dv = lambda a, b: app(C.c('Nat.div'), L(C, a), L(C, b))
    md = lambda a, b: app(C.c('Nat.mod'), L(C, a), L(C, b))
    sc = lambda a: app(C.succ, L(C, a))
    return {
        'add-imm-edge': (lambda: add(P31 - 1, 1), P31, [0, P31 - 1, P32]),
        'add-u32-edge': (lambda: add(P32 - 1, 1), P32, [0, P32 - 1]),
        'mul-u32': (lambda: mul(65536, 65536), P32, [0]),
        'mul-u64': (lambda: mul(P32, P32), P64, [0, P64 - 1]),
        'add-i64-edge': (lambda: add(P63 - 1, 1), P63, [0, P63 - 1]),
        'add-u64-edge': (lambda: add(P64 - 1, 1), P64, [0, P64 - 1]),
        'sub-trunc': (lambda: sub(5, 7), 0, [P64 - 2, P32 - 2, P31 - 2, 2]),
        'sub-zero': (lambda: sub(0, 1), 0, [P31 - 1, P32 - 1, P64 - 1]),
        'sub-big': (lambda: sub(P64, 1), P64 - 1, [P64, 0]),
        'sub-imm-edge': (lambda: sub(P31, 1), P31 - 1, [P31]),
        'pow-64': (lambda: pw(2, 64), P64, [0]),
        'pow-32': (lambda: pw(2, 32), P32, [0]),
        'pow-31': (lambda: pw(2, 31), P31, [0, P31 - 1]),
        'pow-0-0': (lambda: pw(0, 0), 1, [0]),
        'div-zero': (lambda: dv(7, 0), 0, [7]),
        'mod-zero': (lambda: md(7, 0), 7, [0]),
        'div-big': (lambda: dv(P64, P32), P32, [0]),
        'mod-big': (lambda: md(P64 + 1, P32), 1, [0]),
        'succ-imm-edge': (lambda: sc(P31 - 1), P31, [0, P31 - 1]),
        'succ-u32-edge': (lambda: sc(P32 - 1), P32, [0]),
        'succ-i64-edge': (lambda: sc(P63 - 1), P63, [0]),
        'succ-u64-edge': (lambda: sc(P64 - 1), P64, [0, P64 - 1]),
        'succ-succ-zero': (lambda: app(C.succ, app(C.succ, C.zero)), 2, [1, 3]),
        'zero-ctor': (lambda: C.zero, 0, [1]),
        'succ-zero': (lambda: app(C.succ, C.zero), 1, [0, 2]),
    }

def lit_pairs():
    """pairs of DIFFERENT literals that a truncating representation could identify"""
    return [(P32, 0), (P31, 0), (P64, 0), (P64 + 1, 1), (P63, 0), (P31 - 1, P32 - 1), (P32 - 1, P64 - 1),
            (P31 + 5, 5), (P32 + 5, 5), (P63 + 5, 5), (P64 + P32, P32), (2 ** 70, 2 ** 70 + 1), (2 ** 200, 2 ** 200 + 2 ** 100),
            (P31 - 1, P31), (P63 - 1, P63)]


@case('nat-boundaries-ok', 'accept', 'true arithmetic and literal identities across the 2^31 / 2^63 / 2^64 representation boundaries')
def _(C):
    cl = nat_claims(C)
    for i, (name, (lhs, val, wrong)) in enumerate(cl.items()):
        C.thm('S.t%d' % i, C.eqnat(lhs(), L(C, val)), C.reflnat(L(C, val)))
        C.thm('S.r%d' % i, C.eqnat(L(C, val), lhs()), C.reflnat(L(C, val)))
    X = C.X
    # equal literals with different expression ids (identity by content)
    for i, v in enumerate([5, P31 - 1, P31, P32, P63 - 1, P63, P64, 2 ** 70, 2 ** 200]):
        a = X._expr(None, {'natVal': str(v)}, fresh=True); b = X._expr(None, {'natVal': str(v)}, fresh=True)
        C.thm('S.d%d' % i, C.eqnat(a, b), C.reflnat(a))
    bt = C.btrue; bf = C.bfalse; B = C.bool
    def bclaim(name, t, v):
        C.thm(name, C.eq(C.l1, B, t, v), C.refl(C.l1, B, v))
    bclaim('S.b0', app(C.c('Nat.beq'), L(C, P32), L(C, P32)), bt)
    bclaim('S.b1', app(C.c('Nat.beq'), L(C, P32), L(C, 0)), bf)
    bclaim('S.b2', app(C.c('Nat.ble'), L(C, P32), L(C, 5)), bf)
    bclaim('S.b3', app(C.c('Nat.ble'), L(C, 5), L(C, P32)), bt)
    bclaim('S.b4', app(C.c('Nat.beq'), L(C, P64), L(C, P64)), bt)
    bclaim('S.b5', app(C.c('Nat.ble'), L(C, P31), L(C, P31 - 1)), bf)
    bclaim('S.b6', app(C.c('Nat.beq'), L(C, P31), L(C, 0)), bf)

def _mk_bad(name, k, w):
    @case('nat-%s-%d-bad' % (name, k), 'reject', 'false arithmetic claim %s = %d' % (name, w))
    def _(C):
        lhs, val, wrong = nat_claims(C)[name]
        C.thm('S.ok', C.eqnat(lhs(), L(C, val)), C.reflnat(L(C, val)))
        C.thm('S.bad', C.eqnat(lhs(), L(C, w)), C.reflnat(L(C, w)))

for _name, (_l, _v, _wrong) in nat_claims(Ctx()).items():
    for _k, _w in enumerate(_wrong):
        _mk_bad(_name, _k, _w)

def _mk_lit(i, a, b):
    @case('nat-lit-distinct-%d-bad' % i, 'reject', 'two different literals claimed equal: %d = %d' % (a, b))
    def _(C):
        C.thm('S.bad', C.eqnat(L(C, a), L(C, b)), C.reflnat(L(C, a)))
    @case('nat-lit-distinct-%d-flipped-bad' % i, 'reject', 'two different literals claimed equal: %d = %d' % (b, a))
    def _(C):
        C.thm('S.bad', C.eqnat(L(C, b), L(C, a)), C.reflnat(L(C, b)))

for _i, (_a, _b) in enumerate(lit_pairs()):
    _mk_lit(_i, _a, _b)

def _mk_bool(i, opname, a, b, claim):
    @case('nat-bool-%d-bad' % i, 'reject', 'false claim Nat.%s %d %d = %s' % (opname, a, b, claim))
    def _(C):
        v = C.btrue if claim == 'true' else C.bfalse
        C.thm('S.bad', C.eq(C.l1, C.bool, app(C.c('Nat.' + opname), L(C, a), L(C, b)), v), C.refl(C.l1, C.bool, v))

for _i, (_o, _a, _b, _c) in enumerate([('beq', P32, 0, 'true'), ('ble', P32, 5, 'true'), ('beq', P31, 0, 'true'),
                                       ('ble', P31, P31 - 1, 'true'), ('beq', P64, P64, 'false'), ('ble', 5, P32, 'false'),
                                       ('beq', P64 + 1, 1, 'true'), ('ble', P64, P64 - 1, 'true'), ('beq', P31 - 1, P32 - 1, 'true')]):
    _mk_bool(_i, _o, _a, _b, _c)


def _succs(C, t, k):
    for _ in range(k):
        t = app(C.succ, t)
    return t

@case('nat-succ-offsets-ok', 'accept', 'Nat.succ offsets over a variable: reflexive and literal-view identities')
def _(C):
    n = C.axiom('S.n', C.nat)
    C.thm('S.t0', C.eqnat(_succs(C, n(), 3), _succs(C, n(), 3)), C.reflnat(_succs(C, n(), 3)))
    C.thm('S.t1', C.eqnat(app(C.c('Nat.add'), n(), L(C, 2)), _succs(C, n(), 2)), C.reflnat(_succs(C, n(), 2)))
    C.thm('S.t2', C.eqnat(app(C.c('Nat.add'), L(C, 3), L(C, 2)), _succs(C, L(C, 3), 2)), C.reflnat(L(C, 5)))
    C.thm('S.t3', C.eqnat(_succs(C, L(C, P31 - 2), 3), L(C, P31 + 1)), C.reflnat(L(C, P31 + 1)))
    C.thm('S.t4', C.eqnat(_succs(C, C.zero, 4), L(C, 4)), C.reflnat(L(C, 4)))

_SUCC_BAD = {
    'n-vs-succ-n': lambda C, n, k: (n, _succs(C, n, 1)),
    'succ2-vs-succ1': lambda C, n, k: (_succs(C, n, 2), _succs(C, n, 1)),
    'succ3-vs-succ2': lambda C, n, k: (_succs(C, n, 3), _succs(C, n, 2)),
    'succ-n-vs-one': lambda C, n, k: (_succs(C, n, 1), L(C, 1)),
    'succ-n-vs-zero': lambda C, n, k: (_succs(C, n, 1), C.zero),
    'succ-n-vs-lit-zero': lambda C, n, k: (_succs(C, n, 1), L(C, 0)),
    'succ-n-vs-succ-k': lambda C, n, k: (_succs(C, n, 1), _succs(C, k, 1)),
    'succ2-n-vs-succ2-k': lambda C, n, k: (_succs(C, n, 2), _succs(C, k, 2)),
    'n-vs-zero': lambda C, n, k: (n, L(C, 0)),
    'add-n-1-vs-n': lambda C, n, k: (app(C.c('Nat.add'), n, L(C, 1)), n),
    'add-n-2-vs-succ-n': lambda C, n, k: (app(C.c('Nat.add'), n, L(C, 2)), _succs(C, n, 1)),
    'succ-n-vs-big': lambda C, n, k: (_succs(C, n, 1), L(C, P64)),
    'one-vs-zero-ctor': lambda C, n, k: (L(C, 1), C.zero),
    'succ-zero-vs-two': lambda C, n, k: (app(C.succ, C.zero), L(C, 2)),
    'zero-lit-vs-succ-zero': lambda C, n, k: (L(C, 0), app(C.succ, C.zero)),
    'succ-u64max-vs-u64max': lambda C, n, k: (app(C.succ, L(C, P64 - 1)), L(C, P64 - 1)),
    'succ3-lit-vs-wrong': lambda C, n, k: (_succs(C, L(C, P31 - 2), 3), L(C, P31)),
}

def _mk_succ(name):
    @case('nat-succ-%s-bad' % name, 'reject', 'false claim about Nat.succ offsets: ' + name)
    def _(C):
        n = C.axiom('S.n', C.nat); k = C.axiom('S.k', C.nat)
        a, b = _SUCC_BAD[name](C, n(), k())
        C.thm('S.bad', C.eqnat(a, b), C.reflnat(a))
    @case('nat-succ-%s-flipped-bad' % name, 'reject', 'the same, sides exchanged: ' + name)
    def _(C):
        n = C.axiom('S.n', C.nat); k = C.axiom('S.k', C.nat)
        a, b = _SUCC_BAD[name](C, n(), k())
        C.thm('S.bad', C.eqnat(b, a), C.reflnat(b))

for _n in _SUCC_BAD:
    _mk_succ(_n)


# ---- strings
def chars(C, cps):
    lst = app(C.c('List.nil', [0]), C.c('Char'))
    for cp in reversed(cps):
        lst = app(C.c('List.cons', [0]), C.c('Char'), app(C.c('Char.ofNat'), L(C, cp)), lst)
    return app(C.c('String.ofList'), lst)

def streq(C, a, b):
    return C.eq(C.l1, C.c('String'), a, b)

@case('str-lit-ok', 'accept', 'string literals against String.ofList of their code points; equal literals with different ids')
def _(C):
    X = C.X
    S = C.c('String')
    for i, s in enumerate(['', 'a', 'ab', 'é', '😀', 'a😀b', 'line\nbreak']):
        lit = X.str_(s)
        C.thm('S.t%d' % i, streq(C, lit, chars(C, [ord(ch) for ch in s])), C.refl(C.l1, S, lit))
        C.thm('S.r%d' % i, streq(C, chars(C, [ord(ch) for ch in s]), lit), C.refl(C.l1, S, lit))
        a = X._expr(None, {'strVal': s}, fresh=True); b = X._expr(None, {'strVal': s}, fresh=True)
        C.thm('S.d%d' % i, streq(C, a, b), C.refl(C.l1, S, a))

_STR_BAD = [('a', 'b'), ('ab', 'a'), ('', 'a'), ('a', 'A'), ('é', 'e'), ('😀', '😁'), ('ab', 'ba'), ('a\u0000', 'a')]

def _mk_str(i, a, b):
    @case('str-lit-distinct-%d-bad' % i, 'reject', 'two different string literals claimed equal: %r = %r' % (a, b))
    def _(C):
        X = C.X
        C.c('String.ofList'); C.c('Char.ofNat')   # the prerequisites of string literals (else the pipeline declines)
        C.thm('S.bad', streq(C, X.str_(a), X.str_(b)), C.refl(C.l1, C.c('String'), X.str_(a)))
    if i == 0:
        @case('str-lit-missing-prerequisites-bad', 'reject', 'two different string literals claimed equal in an export that does not declare String.ofList / Char.ofNat: the official checker rejects (literals differ); the pipeline declines by design (UNSUPPORTED_PRELUDE)')
        def _(C):
            X = C.X
            C.thm('S.bad', streq(C, X.str_(a), X.str_(b)), C.refl(C.l1, C.c('String'), X.str_(a)))
    @case('str-lit-expand-%d-bad' % i, 'reject', 'string literal %r against String.ofList of the code points of %r' % (a, b))
    def _(C):
        X = C.X
        C.thm('S.bad', streq(C, X.str_(a), chars(C, [ord(ch) for ch in b])), C.refl(C.l1, C.c('String'), X.str_(a)))
    @case('str-lit-expand-%d-flipped-bad' % i, 'reject', 'String.ofList of the code points of %r against the literal %r' % (b, a))
    def _(C):
        X = C.X
        C.thm('S.bad', streq(C, chars(C, [ord(ch) for ch in b]), X.str_(a)), C.refl(C.l1, C.c('String'), X.str_(a)))

for _i, (_a, _b) in enumerate(_STR_BAD):
    _mk_str(_i, _a, _b)


if __name__ == '__main__':
    rows = emit_all(['nat-', 'str-'])
    write_manifest(rows, 'batch4.tsv')
    print(len(rows), 'exports written to', OUT)
