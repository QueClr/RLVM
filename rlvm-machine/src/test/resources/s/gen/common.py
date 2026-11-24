"""Common helpers of the hostile-export generators (work/soundness/gen/*.py)."""
import os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), 'tools'))
from lk import *  # noqa

OUT = os.path.join(os.path.dirname(HERE), 'exports')
os.makedirs(OUT, exist_ok=True)
CASES = []   # (name, expected, attack, builder)


def case(name, expected, attack):
    def deco(f):
        CASES.append((name, expected, attack, f))
        return f
    return deco


class Ctx:
    """An export under construction with the usual prelude constants at hand."""

    def __init__(self, base=None):
        self.X = X = Export(base or prelude())
        self.l1 = X.lnum(1)
        self.l2 = X.lnum(2)
        self.prop = X.sort(0)
        self.type = X.sort(self.l1)
        self.type1 = X.sort(self.l2)

    @property
    def nat(self): return self.X.c('Nat')
    @property
    def bool(self): return self.X.c('Bool')
    @property
    def true(self): return self.X.c('True')
    @property
    def trivial(self): return self.X.c('True.intro')
    @property
    def false(self): return self.X.c('False')
    @property
    def zero(self): return self.X.c('Nat.zero')
    @property
    def succ(self): return self.X.c('Nat.succ')
    @property
    def btrue(self): return self.X.c('Bool.true')
    @property
    def bfalse(self): return self.X.c('Bool.false')

    def eq(self, lvl, A, a, b):
        return app(self.X.c('Eq', [lvl]), A, a, b)

    def refl(self, lvl, A, a):
        return app(self.X.c('Eq.refl', [lvl]), A, a)

    def eqnat(self, a, b): return self.eq(self.l1, self.nat, a, b)
    def reflnat(self, a): return self.refl(self.l1, self.nat, a)
    def lit(self, n): return self.X.nat(n)

    def thm(self, name, ty, val, lparams=()):
        self.X.thm(name, list(lparams), self.X.t(ty), self.X.t(val))

    def defn(self, name, ty, val, lparams=(), hints='abbrev'):
        self.X.defn(name, list(lparams), self.X.t(ty), self.X.t(val), hints)

    def axiom(self, name, ty, lparams=()):
        self.X.axiom(name, list(lparams), self.X.t(ty))
        return lambda *us: self.X.const(name, list(us))

    def opaque(self, name, ty, val, lparams=()):
        self.X.opaque(name, list(lparams), self.X.t(ty), self.X.t(val))

    def k(self, name, us=()):
        return self.X.const(name, list(us))

    def c(self, name, us=()):
        return self.X.c(name, list(us))


def emit_all(prefix_filter=None):
    import json
    rows = []
    for name, expected, attack, f in CASES:
        if prefix_filter and not any(name.startswith(p) for p in prefix_filter):
            continue
        C = Ctx()
        r = f(C)
        if r is not None:
            C = r
        path = os.path.join(OUT, name + '.ndjson')
        C.X.write(path)
        rows.append((name, expected, attack))
    return rows


def write_manifest(rows, fname):
    with open(os.path.join(OUT, fname), 'w') as f:
        for name, expected, attack in rows:
            f.write('%s\t%s\t%s\n' % (name, expected, attack))
