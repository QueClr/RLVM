#!/usr/bin/env python3
"""Generates rlvm-level/src/test/resources/rlvm/level/official-golden.tsv: answers of the OFFICIAL
Lean kernel (leanchecker --from-export) to level questions, one tiny export file per question.

  E  l1  l2            is_equivalent(l1, l2)
                       def t.{u,v,w} : Sort l1 -> Sort l1 := fun (x : Sort l2) => x
  G  l1  l2            is_geq(l1, l2) || normalizes_to_zero(l1)
                       inductive T.{u,v,w} (a : Sort l2) : Sort l1 | mk : a -> T a
                       (inductive.cpp: constructor field universe <= result universe)
  I  L  A  B  C  M     is_equivalent(instantiate(L, [u,v,w], [A,B,C]), M)
                       axiom c.{u,v,w} : Sort L ;  def d.{u,v,w} : Sort M := c.{A,B,C}

Levels are written 0, 1, u, (s X), (m X Y), (i X Y). Level records in the generated exports are
deduplicated structurally, as lean4export does (so "same structure" is "same object" for the kernel).

Usage (host, from the repository root; about one minute on 20 cores):
    bench/modules/rlvm-level/gen_golden.py [--seed N] [--out FILE] [--checker PATH] [--jobs N]
The output is deterministic for a given seed and checker version.
"""
import argparse
import concurrent.futures
import itertools
import os
import random
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
DEFAULT_OUT = os.path.join(ROOT, "rlvm-level/src/test/resources/rlvm/level/official-golden.tsv")
DEFAULT_CHECKER = os.path.expanduser("~/.elan/toolchains/leanprover--lean4---v4.36.0/bin/leanchecker")

Z = ("z",)
ONE = ("s", Z)
PARAMS = ["u", "v", "w"]


def P(n):
    return ("p", n)


def S(x):
    return ("s", x)


def M(x, y):
    return ("m", x, y)


def I(x, y):
    return ("i", x, y)


def text(l):
    k = l[0]
    if k == "z":
        return "0"
    if k == "p":
        return l[1]
    if k == "s":
        return "(s %s)" % text(l[1])
    return "(%s %s %s)" % (k, text(l[1]), text(l[2]))


def ev(l, env):
    k = l[0]
    if k == "z":
        return 0
    if k == "p":
        return env[l[1]]
    if k == "s":
        return ev(l[1], env) + 1
    if k == "m":
        return max(ev(l[1], env), ev(l[2], env))
    y = ev(l[2], env)
    return 0 if y == 0 else max(ev(l[1], env), y)


GRID = [dict(zip(PARAMS, vals)) for vals in itertools.product([0, 1, 2, 3, 9], repeat=3)]


def table(l):
    return tuple(ev(l, env) for env in GRID)


def subst(l, env):
    """Plain substitution, no simplification."""
    k = l[0]
    if k == "z":
        return l
    if k == "p":
        return env.get(l[1], l)
    if k == "s":
        return S(subst(l[1], env))
    return (k, subst(l[1], env), subst(l[2], env))


def rand_level(rnd, depth, nparams=3):
    r = rnd.randrange(100)
    if depth <= 0 or r < 22:
        if rnd.randrange(100) < 25:
            return ONE if rnd.randrange(4) == 0 else Z
        return P(PARAMS[rnd.randrange(nparams)])
    if r < 44:
        return S(rand_level(rnd, depth - 1, nparams))
    if r < 72:
        return M(rand_level(rnd, depth - 1, nparams), rand_level(rnd, depth - 1, nparams))
    return I(rand_level(rnd, depth - 1, nparams), rand_level(rnd, depth - 1, nparams))


def mutate(rnd, x):
    c = rnd.randrange(12)
    if c == 0:
        return M(x, x)
    if c == 1:
        return M(x, Z)
    if c == 2:
        return M(x[2], x[1]) if x[0] == "m" else I(x, x)
    if c == 3:
        if x[0] == "s" and x[1][0] == "m":
            return M(S(x[1][1]), S(x[1][2]))
        return S(x)
    if c == 4:
        return M(x[1], x[2]) if x[0] == "i" else I(ONE, x)
    if c == 5:
        if x[0] == "m" and x[2][0] == "m":
            return M(M(x[1], x[2][1]), x[2][2])
        return M(Z, x)
    if c == 6:
        return I(I(x[1], x[2]), x[2]) if x[0] == "i" else I(x, ONE)
    if c == 7:
        if x[0] == "i" and x[2][0] == "m":
            return M(I(x[1], x[2][1]), I(x[1], x[2][2]))
        return I(Z, x)
    if c == 8:
        if x[0] == "i" and x[2][0] == "i":
            return M(I(x[1], x[2][2]), I(x[2][1], x[2][2]))
        return M(x, I(x, x))
    if c == 9:
        if x[0] in ("m", "i"):
            return (x[0], mutate(rnd, x[1]), x[2])
        return M(ONE, x)
    if c == 10:
        if x[0] in ("m", "i"):
            return (x[0], x[1], mutate(rnd, x[2]))
        return S(M(x, Z))
    if x[0] == "s":
        return S(mutate(rnd, x[1]))
    return I(x, S(x))


CURATED_E = [
    # tutorial 015-027 and docs/research/02 section 3.6
    ("(i 1 0)", "0"), ("(i 0 1)", "1"), ("(i 2 1)", "2"), ("(i u 0)", "0"), ("(i u u)", "u"),
    ("(m u v)", "(m v u)"), ("(m (m u v) w)", "(m u (m v w))"), ("(m u u)", "u"), ("(m u (m u v))", "(m u v)"),
    # bugs/level-imax-normalization, bugs/level-imax-leq
    ("(i 0 v)", "(s (i 0 v))"), ("(i u v)", "(s (i u v))"), ("(s (i 0 v))", "(s v)"), ("(i 0 v)", "v"),
    # corner-cases/imax-right-successor, imax-nested-absorption (official rejects both)
    ("(i u (s v))", "(m u (s v))"), ("(i u v)", "(i (i u v) v)"),
    ("(s (i u 1))", "(s (m u 1))"), ("(s (m u 1))", "(m 2 (s u))"), ("(s (i u 1))", "(m 2 (s u))"),
    ("(i u 1)", "(m u 1)"), ("(i u 1)", "(m 1 u)"), ("(i 1 u)", "u"), ("(i 2 u)", "u"), ("(i 2 u)", "(m 2 u)"),
    ("(s (m u v))", "(m (s u) (s v))"), ("(m 1 (s u))", "(s u)"), ("(m 2 (s u))", "(s u)"), ("(m 0 u)", "u"),
    ("(m (s u) u)", "(s u)"), ("(m u (s u))", "(s u)"), ("(m (s (s u)) (s u))", "(s (s u))"),
    ("(i u (m v w))", "(m (i u v) (i u w))"), ("(i u (i v w))", "(m (i u w) (i v w))"),
    ("(i (m u v) w)", "(m (i u w) (i v w))"), ("(i u (i v w))", "(i (i u v) w)"),
    ("(m u v)", "(i u v)"), ("u", "v"), ("(s u)", "u"), ("0", "(i u 0)"), ("(m (i u v) v)", "(i u v)"),
    ("(m (i u v) u)", "(m u v)"), ("(m (s (i u v)) v)", "(s (i u v))"), ("(s (i u (s v)))", "(m (s u) (s (s v)))"),
    ("(m w (m v u))", "(m u (m v w))"), ("(m (s w) (m v (s (s u))))", "(m (s (s u)) (m v (s w)))"),
    ("(i (s u) (s v))", "(m (s u) (s v))"), ("(i (i u v) (i u v))", "(i u v)"),
    ("(s (i u v))", "(i (s u) (s v))"), ("(m 1 (i u v))", "(i u (m 1 v))"),
]

CURATED_G = [
    ("(i u v)", "(s (i u v))"), ("(s (i u v))", "(i u v)"), ("(i u v)", "v"), ("(i u v)", "u"),
    ("(m u v)", "(i u v)"), ("(i u v)", "(m u v)"), ("(s u)", "u"), ("u", "(s u)"), ("(m u v)", "u"),
    ("(m u v)", "(m v u)"), ("(s (m u v))", "(m (s u) v)"), ("(m (s u) v)", "(s (m u v))"), ("u", "0"),
    ("(m 1 u)", "1"), ("u", "1"), ("(i 1 u)", "u"), ("u", "(i 1 u)"), ("(i 2 u)", "u"), ("u", "(i 2 u)"),
    ("(m 2 u)", "(i 2 u)"), ("(i u (s v))", "(m u (s v))"), ("(m u (s v))", "(i u (s v))"),
    ("(i (i u v) v)", "(i u v)"), ("(i u v)", "(i (i u v) v)"), ("(s (s u))", "(s u)"), ("(s u)", "(s v)"),
    ("(m (s u) (s v))", "(s (m u v))"), ("(s (m u v))", "(m (s u) (s v))"), ("(i u (i v w))", "(i (i u v) w)"),
    ("(i (i u v) w)", "(i u (i v w))"), ("(m u (m v w))", "(i u (i v w))"), ("(s u)", "(i u (s 0))"),
    ("(m u 1)", "(i u 1)"), ("(i u 1)", "(m u 1)"), ("(s (i u v))", "(m (i u v) 1)"), ("(i u v)", "0"),
    ("(i (s u) v)", "v"), ("(i (s u) v)", "(i u v)"), ("(i u v)", "(i (s u) v)"),
]


def parse(t):
    pos = [0]

    def go():
        while t[pos[0]] == " ":
            pos[0] += 1
        if t[pos[0]] == "(":
            op = t[pos[0] + 1]
            pos[0] += 2
            if op == "s":
                r = S(go())
            else:
                x = go()
                r = (op, x, go())
            while t[pos[0]] == " ":
                pos[0] += 1
            assert t[pos[0]] == ")", t
            pos[0] += 1
            return r
        st = pos[0]
        while pos[0] < len(t) and t[pos[0]] not in " ()":
            pos[0] += 1
        tok = t[st:pos[0]]
        if tok.isdigit():
            r = Z
            for _ in range(int(tok)):
                r = S(r)
            return r
        return P(tok)

    r = go()
    assert pos[0] == len(t), t
    return r


# ------------------------------------------------------------------------------------------------
# export files
# ------------------------------------------------------------------------------------------------

META = ('{"meta":{"exporter":{"name":"lean4export","version":"3.1.0"},"format":{"version":"3.1.0"},'
        '"lean":{"githash":"0000000000000000000000000000000000000000","version":"4.36.0"}}}')
NAMES = ["u", "v", "w", "t", "x", "T", None, "a", "c", "d"]  # ids 1..; index 6 (id 7) is T.mk


class Export:
    def __init__(self):
        self.lines = [META]
        for i, n in enumerate(NAMES):
            if n is None:
                self.lines.append('{"in":%d,"str":{"pre":6,"str":"mk"}}' % (i + 1))
            else:
                self.lines.append('{"in":%d,"str":{"pre":0,"str":"%s"}}' % (i + 1, n))
        self.levels = {Z: 0}

    def level(self, l):
        if l in self.levels:
            return self.levels[l]
        k = l[0]
        if k == "p":
            body = '"param":%d' % (PARAMS.index(l[1]) + 1)
        elif k == "s":
            body = '"succ":%d' % self.level(l[1])
        else:
            x = self.level(l[1])
            y = self.level(l[2])
            body = '"%s":[%d,%d]' % ("max" if k == "m" else "imax", x, y)
        i = len(self.levels)
        self.levels[l] = i
        self.lines.append('{"il":%d,%s}' % (i, body))
        return i

    def add(self, line):
        self.lines.append(line)

    def write(self, path):
        with open(path, "w") as f:
            f.write("\n".join(self.lines) + "\n")


def export_E(l1, l2):
    e = Export()
    a = e.level(l1)
    b = e.level(l2)
    e.add('{"ie":0,"sort":%d}' % a)
    e.add('{"ie":1,"sort":%d}' % b)
    e.add('{"ie":2,"forallE":{"binderInfo":"default","body":0,"name":5,"type":0}}')
    e.add('{"ie":3,"bvar":0}')
    e.add('{"ie":4,"lam":{"binderInfo":"default","body":3,"name":5,"type":1}}')
    e.add('{"def":{"all":[4],"hints":"abbrev","levelParams":[1,2,3],"name":4,"safety":"safe","type":2,"value":4}}')
    return e


def export_G(l1, l2):
    e = Export()
    for p in PARAMS:
        e.level(P(p))
    a = e.level(l1)
    b = e.level(l2)
    us = "[%d,%d,%d]" % tuple(e.level(P(p)) for p in PARAMS)
    e.add('{"ie":0,"sort":%d}' % a)
    e.add('{"ie":1,"sort":%d}' % b)
    e.add('{"ie":2,"forallE":{"binderInfo":"default","body":0,"name":8,"type":1}}')
    e.add('{"ie":3,"bvar":0}')
    e.add('{"ie":4,"bvar":1}')
    e.add('{"ie":5,"const":{"name":6,"us":%s}}' % us)
    e.add('{"ie":6,"app":{"fn":5,"arg":4}}')
    e.add('{"ie":7,"forallE":{"binderInfo":"default","body":6,"name":8,"type":3}}')
    e.add('{"ie":8,"forallE":{"binderInfo":"implicit","body":7,"name":8,"type":1}}')
    e.add('{"inductive":{"ctors":[{"cidx":0,"induct":6,"isUnsafe":false,"levelParams":[1,2,3],"name":7,'
          '"numFields":1,"numParams":1,"type":8}],"recs":[],"types":[{"all":[6],"ctors":[7],"isRec":false,'
          '"isReflexive":false,"isUnsafe":false,"levelParams":[1,2,3],"name":6,"numIndices":0,"numNested":0,'
          '"numParams":1,"type":2}]}}')
    return e


def export_I(L, A, B, C, Mx):
    e = Export()
    l = e.level(L)
    us = "[%d,%d,%d]" % (e.level(A), e.level(B), e.level(C))
    m = e.level(Mx)
    e.add('{"ie":0,"sort":%d}' % l)
    e.add('{"ie":1,"sort":%d}' % m)
    e.add('{"ie":2,"const":{"name":9,"us":%s}}' % us)
    e.add('{"axiom":{"isUnsafe":false,"levelParams":[1,2,3],"name":9,"type":0}}')
    e.add('{"def":{"all":[10],"hints":"abbrev","levelParams":[1,2,3],"name":10,"safety":"safe","type":1,"value":2}}')
    return e


def run_case(args):
    idx, case, tmp, checker = args
    kind = case[0]
    path = os.path.join(tmp, "c%d.ndjson" % idx)
    if kind == "E":
        export_E(case[1], case[2]).write(path)
        reject_marker = "declaration type mismatch"
    elif kind == "G":
        export_G(case[1], case[2]).write(path)
        reject_marker = "is too big for the corresponding inductive datatype"
    else:
        export_I(*case[1:]).write(path)
        reject_marker = "declaration type mismatch"
    p = subprocess.run([checker, "--from-export", path], capture_output=True, text=True)
    os.unlink(path)
    out = p.stdout + p.stderr
    if p.returncode == 0:
        return 1
    if p.returncode == 1 and reject_marker in out:
        return 0
    if kind == "G" and p.returncode == 1 and "application type mismatch" in out:
        # Not an answer to the question: add_inductive type checks `T a` with T instantiated at fresh
        # parameter objects, which rebuilds the parameter's sort with mk_max/mk_imax (e.g. imax u 1
        # becomes max u 1), and the official is_equivalent then fails on the two spellings.
        return None
    raise RuntimeError("unexpected checker result %d for %s:\n%s" % (p.returncode, case, out))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--seed", type=int, default=20261123)
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--checker", default=DEFAULT_CHECKER)
    ap.add_argument("--jobs", type=int, default=16)
    a = ap.parse_args()
    rnd = random.Random(a.seed)

    cases = []
    seen = set()

    def add(*c):
        if c not in seen:
            seen.add(c)
            cases.append(c)

    for x, y in CURATED_E:
        add("E", parse(x), parse(y))
        add("E", parse(y), parse(x))
    for x, y in CURATED_G:
        add("G", parse(x), parse(y))

    # every pair of levels of height <= 1 over 0, u, v
    atoms = [Z, P("u"), P("v")]
    small = list(atoms) + [S(x) for x in atoms] + [M(x, y) for x in atoms for y in atoms] \
        + [I(x, y) for x in atoms for y in atoms]
    for i, x in enumerate(small):
        for j, y in enumerate(small):
            if i < j:
                add("E", x, y)
            if i != j:
                add("G", x, y)

    # a pool of random levels, bucketed by their value table
    pool = []
    buckets = {}
    for _ in range(40000):
        l = rand_level(rnd, 1 + rnd.randrange(4))
        pool.append(l)
        buckets.setdefault(table(l), set()).add(l)
    big = [sorted(b, key=text) for b in buckets.values() if len(b) >= 2]
    big.sort(key=lambda b: text(b[0]))

    # E: semantically equal pairs (the interesting ones: official may say no), mutants, random pairs
    for _ in range(3000):
        b = big[rnd.randrange(len(big))]
        x, y = rnd.sample(b, 2)
        add("E", x, y)
    for _ in range(1500):
        x = pool[rnd.randrange(len(pool))]
        y = mutate(rnd, x)
        if rnd.randrange(3) == 0:
            y = mutate(rnd, y)
        add("E", x, y) if rnd.randrange(2) else add("E", y, x)
    for _ in range(500):
        add("E", pool[rnd.randrange(len(pool))], pool[rnd.randrange(len(pool))])

    # G: semantically >= pairs, mutants, random pairs
    n = 0
    while n < 2500:
        x = pool[rnd.randrange(len(pool))]
        y = pool[rnd.randrange(len(pool))]
        tx, ty = table(x), table(y)
        if x != y and all(p >= q for p, q in zip(tx, ty)):
            add("G", x, y)
            n += 1
    for _ in range(1000):
        x = pool[rnd.randrange(len(pool))]
        y = mutate(rnd, x)
        add("G", x, y) if rnd.randrange(2) else add("G", y, x)
    for _ in range(700):
        add("G", pool[rnd.randrange(len(pool))], pool[rnd.randrange(len(pool))])

    # I: instantiate then compare
    for _ in range(3000):
        L = rand_level(rnd, 1 + rnd.randrange(4))
        sub = []
        for p in PARAMS:
            r = rnd.randrange(10)
            if r < 2:
                sub.append(P(p))
            elif r < 4:
                sub.append(P(PARAMS[rnd.randrange(3)]))
            else:
                sub.append(rand_level(rnd, rnd.randrange(3)))
        naive = subst(L, dict(zip(PARAMS, sub)))
        r = rnd.randrange(10)
        if r < 5:
            Mx = naive
        elif r < 8:
            b = sorted(buckets.get(table(naive), ()), key=text)
            Mx = b[rnd.randrange(len(b))] if b else mutate(rnd, naive)
        else:
            Mx = mutate(rnd, naive)
        add("I", L, sub[0], sub[1], sub[2], Mx)

    with tempfile.TemporaryDirectory(prefix="rlvm-level-golden-") as tmp:
        jobs = [(i, c, tmp, a.checker) for i, c in enumerate(cases)]
        with concurrent.futures.ThreadPoolExecutor(max_workers=a.jobs) as ex:
            results = list(ex.map(run_case, jobs))

    with open(a.out, "w") as f:
        f.write("# Answers of the official Lean kernel; generated by bench/modules/rlvm-level/gen_golden.py\n")
        f.write("# checker: leanchecker of %s, seed %d\n"
                % (os.path.basename(os.path.dirname(os.path.dirname(a.checker))), a.seed))
        f.write("# E l1 l2 r : is_equivalent(l1,l2) = r\n")
        f.write("# G l1 l2 r : is_geq(l1,l2) || normalizes_to_zero(l1) = r\n")
        f.write("# I L A B C M r : is_equivalent(instantiate(L,[u,v,w],[A,B,C]), M) = r\n")
        for c, r in zip(cases, results):
            if r is not None:
                f.write("\t".join([c[0]] + [text(x) for x in c[1:]] + [str(r)]) + "\n")
    stats = {}
    skipped = 0
    for c, r in zip(cases, results):
        if r is None:
            skipped += 1
            continue
        s = stats.setdefault(c[0], [0, 0])
        s[r] += 1
    print("wrote %d cases to %s: %s; %d G cases skipped (official kernel fails earlier, on the parameter's sort)"
          % (len(cases) - skipped, a.out,
             ", ".join("%s %d no / %d yes" % (k, v[0], v[1]) for k, v in sorted(stats.items())), skipped))


if __name__ == "__main__":
    sys.exit(main())
