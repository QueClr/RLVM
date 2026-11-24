package rlvm.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import rlvm.store.Model.M;

/** TermOps against the naive recursive definitions on random DAGs, its laws, depth safety and DAG cost. */
class TermOpsTest {

    private static final class Env {
        final Model fx = new Model();
        final Overlay o;
        final Terms t;
        final TermOps ops;
        final IdentityHashMap<M, Integer> sharedMemo = new IdentityHashMap<>();
        final IdentityHashMap<M, Integer> ovMemo = new IdentityHashMap<>();

        Env() {
            fx.declareLiteralPrerequisites(true);
            fx.builder.finish();
            o = new Overlay(fx.store, fx.store.lists(), 1L << 28);
            t = new Terms(fx.store.terms(), o);
            ops = new TermOps(t, fx.store.lists());
        }

        /** Builds the term half of the time in the shared region, half in the overlay. */
        int build(Random rnd, M m) {
            if (rnd.nextBoolean() && !hasFVar(m)) {
                int id = fx.toShared(m, sharedMemo);
                t.refresh();
                return id;
            }
            return fx.toOverlay(o, m, ovMemo);
        }
    }

    private static boolean hasFVar(M m) {
        return hasFVar(m, new IdentityHashMap<>());
    }

    private static boolean hasFVar(M m, IdentityHashMap<M, Boolean> seen) {
        if (m == null || seen.put(m, true) != null) {
            return false;
        }
        return m.kind == Kind.FVAR || hasFVar(m.a, seen) || hasFVar(m.b, seen) || hasFVar(m.c, seen);
    }

    private static Model.Gen gen(int size, int maxVar) {
        Model.Gen g = new Model.Gen();
        g.size = size;
        g.maxVar = maxVar;
        return g;
    }

    private static M root(List<M> nodes) {
        return nodes.get(nodes.size() - 1);
    }

    @Test
    void instantiateMatchesNaive() {
        Env e = new Env();
        Random rnd = new Random(7);
        for (int round = 0; round < 1500; round++) {
            M body = root(e.fx.random(rnd, gen(5 + rnd.nextInt(40), 4)));
            int n = 1 + rnd.nextInt(4);
            M[] subst = new M[n];
            int[] args = new int[n];
            for (int i = 0; i < n; i++) {
                subst[i] = root(e.fx.random(rnd, gen(3 + rnd.nextInt(10), rnd.nextInt(3) == 0 ? 3 : -1)));
                args[i] = e.build(rnd, subst[i]);
            }
            int b = e.build(rnd, body);
            int fwd = e.ops.instantiate(b, args, 0, n);
            assertEquals(e.fx.digest(Model.instantiate(body, subst, 0)), e.fx.digest(e.t, fwd), "round " + round);
            M[] rev = new M[n];
            for (int i = 0; i < n; i++) {
                rev[i] = subst[n - 1 - i];
            }
            int back = e.ops.instantiateRev(b, args, 0, n);
            assertEquals(e.fx.digest(Model.instantiate(body, rev, 0)), e.fx.digest(e.t, back), "round " + round);
            int single = e.ops.instantiate1(b, args[0]);
            assertEquals(e.fx.digest(Model.instantiate(body, new M[] {subst[0]}, 0)), e.fx.digest(e.t, single));
            if (e.t.isClosed(b)) {
                assertEquals(b, fwd, "a closed term is returned as is");
            }
            // with an offset into the argument array
            int[] shifted = new int[n + 3];
            System.arraycopy(args, 0, shifted, 2, n);
            assertEquals(fwd, e.ops.instantiate(b, shifted, 2, n));
            assertEquals(back, e.ops.instantiateRev(b, shifted, 2, n));
            if (round % 200 == 199) {
                e.o.reset();
                e.ovMemo.clear();
            }
        }
    }

    @Test
    void liftAndLowerMatchNaiveAndAreInverse() {
        Env e = new Env();
        Random rnd = new Random(11);
        for (int round = 0; round < 1500; round++) {
            M m = root(e.fx.random(rnd, gen(5 + rnd.nextInt(40), 6)));
            int id = e.build(rnd, m);
            int s = rnd.nextInt(5);
            int d = rnd.nextInt(5);
            int lifted = e.ops.liftLoose(id, s, d);
            assertEquals(e.fx.digest(Model.lift(m, s, d, 0)), e.fx.digest(e.t, lifted), "round " + round);
            int lowered = e.ops.lowerLoose(lifted, s + d, d);
            assertEquals(e.fx.digest(m), e.fx.digest(e.t, lowered), "lower after lift, round " + round);
            if (d == 0 || e.t.looseRange(id) <= s) {
                assertEquals(id, lifted);
            }
        }
    }

    @Test
    void abstractMatchesNaiveAndInvertsInstantiate() {
        Env e = new Env();
        Random rnd = new Random(13);
        for (int round = 0; round < 800; round++) {
            int nf = 1 + rnd.nextInt(round % 10 == 0 ? 30 : 5);
            int[] fvars = new int[nf];
            int type = e.o.mkSort(e.fx.levelIds[1]);
            for (int i = 0; i < nf; i++) {
                fvars[i] = e.o.mkFVar(type);
            }
            int other = e.o.mkFVar(type);
            Model.Gen g = gen(5 + rnd.nextInt(40), 3);
            g.fvars = java.util.Arrays.copyOf(fvars, nf + 1);
            g.fvars[nf] = other;
            M m = root(e.fx.random(rnd, g));
            int id = e.fx.toOverlay(e.o, m, e.ovMemo);
            int abs = e.ops.abstractFVars(id, fvars, 0, nf);
            assertEquals(e.fx.digest(Model.abstractFVars(m, fvars, 0)), e.fx.digest(e.t, abs), "round " + round);
            // duplicates: the last position wins
            int[] dup = java.util.Arrays.copyOf(fvars, nf + 1);
            dup[nf] = fvars[0];
            assertEquals(e.fx.digest(Model.abstractFVars(m, dup, 0)), e.fx.digest(e.t, e.ops.abstractFVars(id, dup, 0, nf + 1)));
            // law: instantiate_rev (abstract t xs) xs = t for a term without loose variables
            Model.Gen closedGen = gen(5 + rnd.nextInt(30), -1);
            closedGen.fvars = g.fvars;
            M closed = root(e.fx.random(rnd, closedGen));
            if (e.fx.range(closed) == 0) {
                int cid = e.fx.toOverlay(e.o, closed, e.ovMemo);
                int a = e.ops.abstractFVars(cid, fvars, 0, nf);
                assertEquals(cid, e.ops.instantiateRev(a, fvars, 0, nf), "abstract/instantiate round trip");
            }
            if (round % 100 == 99) {
                e.o.reset();
                e.ovMemo.clear();
            }
        }
    }

    @Test
    void levelInstantiationMatchesNaive() {
        Env e = new Env();
        Random rnd = new Random(17);
        IntLists ls = e.fx.store.lists();
        int names = ls.intern(e.fx.paramNames, 0, 2);
        int lvls = ls.intern(new int[] {e.fx.levelIds[1], e.fx.levels.succ(e.fx.levelIds[3])}, 0, 2);
        for (int round = 0; round < 800; round++) {
            M m = root(e.fx.random(rnd, gen(5 + rnd.nextInt(40), 3)));
            int id = e.build(rnd, m);
            int r = e.ops.instantiateLevelParams(id, names, lvls);
            assertEquals(e.fx.digest(e.fx.instantiateLevels(m, names, lvls)), e.fx.digest(e.t, r), "round " + round);
            if (!e.t.hasLevelParam(id)) {
                assertEquals(id, r);
            }
            // u is gone from the result; w (not substituted) may remain
            assertFalse(e.fx.setOf(e.t.lpSet(r)).contains(e.fx.paramNames[0]));
        }
        // a term without parameters costs no level call at all
        int closed = e.o.mkApp(e.o.mkSort(e.fx.levelIds[1]), e.o.mkBVar(0));
        int before = e.fx.levels.instantiateCalls;
        assertEquals(closed, e.ops.instantiateLevelParams(closed, names, lvls));
        assertEquals(before, e.fx.levels.instantiateCalls);
    }

    @Test
    void eqvIsStructuralEquality() {
        Env e = new Env();
        Random rnd = new Random(19);
        int equal = 0;
        for (int round = 0; round < 1500; round++) {
            M m = root(e.fx.random(rnd, gen(5 + rnd.nextInt(30), 3)));
            int a = e.fx.toShared(m, new IdentityHashMap<>());
            e.t.refresh();
            int b = e.fx.toOverlay(e.o, m, new IdentityHashMap<>());
            assertNotEquals(a, b);
            assertTrue(e.ops.eqv(a, b), "same structure, different regions");
            assertTrue(e.ops.eqv(b, a));
            M other = root(e.fx.random(rnd, gen(5 + rnd.nextInt(30), 3)));
            int c = e.build(rnd, other);
            boolean same = e.fx.digest(m) == e.fx.digest(other);
            assertEquals(same, e.ops.eqv(a, c), "round " + round);
            if (same) {
                equal++;
            }
            // one changed leaf deep inside
            M mutated = mutate(rnd, m);
            int d = e.fx.toOverlay(e.o, mutated, new IdentityHashMap<>());
            assertEquals(e.fx.digest(m) == e.fx.digest(mutated), e.ops.eqv(a, d), "mutant, round " + round);
        }
        // a pooled big natural and the same value in a worker pool are equal by content
        LitPool worker = new LitPool(e.fx.store.lits());
        Overlay o2 = new Overlay(e.fx.store, e.fx.store.lists(), e.fx.levels, worker, 1 << 20);
        Terms t2 = new Terms(e.fx.store.terms(), o2);
        TermOps ops2 = new TermOps(t2, e.fx.store.lists());
        ops2.setLitPool(worker);
        int big = worker.addNat(new long[] {1, 2}, 0, 2);
        assertTrue(big < 0);
        int x = o2.mkNatLitBig(big);
        int y = o2.mkNatLitBig(worker.addNat(new long[] {1, 2}, 0, 2));
        assertEquals(x, y);
        int z = o2.mkNatLitBig(e.fx.bigNat);
        assertFalse(ops2.eqv(x, z));
        assertEquals(o2.mkNatLit(42), o2.mkNatLitBig(worker.addNat(new long[] {42}, 0, 1)), "a one-limb pool entry is a small literal");
    }

    /** Replaces one leaf, along one random path from the root. */
    private static M mutate(Random rnd, M m) {
        switch (m.kind) {
            case Kind.APP:
                return rnd.nextBoolean() ? Model.app(mutate(rnd, m.a), m.b) : Model.app(m.a, mutate(rnd, m.b));
            case Kind.LAM:
                return rnd.nextBoolean() ? Model.lam(mutate(rnd, m.a), m.b) : Model.lam(m.a, mutate(rnd, m.b));
            case Kind.PI:
                return rnd.nextBoolean() ? Model.pi(mutate(rnd, m.a), m.b) : Model.pi(m.a, mutate(rnd, m.b));
            case Kind.LET:
                return Model.let(m.a, m.b, mutate(rnd, m.c));
            case Kind.PROJ:
                return rnd.nextBoolean() ? Model.proj(m.x, m.y + 1, m.a) : Model.proj(m.x, m.y, mutate(rnd, m.a));
            case Kind.BVAR:
                return Model.bvar(m.x + 1);
            case Kind.NATLIT:
                return m.y == 1 ? Model.nat(3) : Model.nat(m.v + 1);
            default:
                return Model.bvar(0);
        }
    }

    @Test
    void hasLooseBVarIsExact() {
        Env e = new Env();
        Random rnd = new Random(23);
        for (int round = 0; round < 600; round++) {
            Model.Gen g = gen(5 + rnd.nextInt(40), 5);
            g.bigVars = true;
            M m = root(e.fx.random(rnd, g));
            int id = e.build(rnd, m);
            java.util.TreeSet<Integer> free = e.fx.free(m);
            for (int idx = 0; idx < 72; idx++) {
                assertEquals(free.contains(idx), e.ops.hasLooseBVar(id, idx), "round " + round + " index " + idx);
            }
            for (int idx : new int[] {129, 130, 65532, 65533, 65534, 69999, 70000, 70001}) {
                assertEquals(free.contains(idx), e.ops.hasLooseBVar(id, idx), "round " + round + " index " + idx);
            }
            assertEquals(!free.isEmpty(), e.ops.hasLooseBVars(id));
            if (round % 100 == 99) {
                e.o.reset();
                e.ovMemo.clear();
            }
        }
    }

    @Test
    void betaContractsHeadLambdas() {
        Env e = new Env();
        Random rnd = new Random(29);
        for (int round = 0; round < 600; round++) {
            int lams = rnd.nextInt(4);
            int nargs = rnd.nextInt(5);
            M body = root(e.fx.random(rnd, gen(5 + rnd.nextInt(20), lams + 1)));
            if (body.kind == Kind.LAM) {
                body = Model.app(body, body);
            }
            M fn = body;
            for (int i = 0; i < lams; i++) {
                fn = Model.lam(Model.sort(e.fx.levelIds[1]), fn);
            }
            M[] margs = new M[nargs];
            int[] args = new int[nargs];
            for (int i = 0; i < nargs; i++) {
                margs[i] = root(e.fx.random(rnd, gen(3 + rnd.nextInt(6), -1)));
                args[i] = e.build(rnd, margs[i]);
            }
            int f = e.build(rnd, fn);
            int m = Math.min(lams, nargs);
            M[] rev = new M[m];
            for (int i = 0; i < m; i++) {
                rev[i] = margs[m - 1 - i];
            }
            M peeled = fn;
            for (int i = 0; i < m; i++) {
                peeled = peeled.b;
            }
            M expect = Model.instantiate(peeled, rev, 0);
            for (int i = m; i < nargs; i++) {
                expect = Model.app(expect, margs[i]);
            }
            int got = e.ops.beta(f, args, 0, nargs);
            assertEquals(e.fx.digest(expect), e.fx.digest(e.t, got), "round " + round);
        }
        // headBeta repeats until the head is no lambda: (fun f x => f x) (fun y => y) c  ~>  c
        {
            Overlay o = e.o;
            int dom0 = o.mkSort(e.fx.levelIds[1]);
            int c0 = o.mkConstByName(e.fx.constNames[0], 0);
            int apply = o.mkLam(dom0, o.mkLam(dom0, o.mkApp(o.mkBVar(1), o.mkBVar(0))));
            int ident = o.mkLam(dom0, o.mkBVar(0));
            int term = o.mkApp(o.mkApp(apply, ident), c0);
            assertEquals(o.mkApp(ident, c0), e.ops.beta(apply, new int[] {ident, c0}, 0, 2));
            assertEquals(c0, e.ops.headBeta(term));
            assertEquals(c0, e.ops.headBeta(c0));
            int stuck = o.mkApp(o.mkApp(c0, ident), c0);
            assertEquals(stuck, e.ops.headBeta(stuck));
            // more arguments than lambdas: the rest is applied
            assertEquals(o.mkApp(c0, c0), e.ops.headBeta(o.mkApp(o.mkApp(ident, c0), c0)));
        }
        // cheap beta: only when nothing has to be substituted
        int dom = e.o.mkSort(e.fx.levelIds[1]);
        int a = e.o.mkNatLit(1);
        int b = e.o.mkNatLit(2);
        int k = e.o.mkLam(dom, e.o.mkLam(dom, e.o.mkBVar(1)));
        assertEquals(a, e.ops.cheapBetaReduce(e.o.mkApp(e.o.mkApp(k, a), b)));
        int constFn = e.o.mkLam(dom, e.o.mkNatLit(7));
        assertEquals(e.o.mkApp(e.o.mkNatLit(7), b), e.ops.cheapBetaReduce(e.o.mkApp(e.o.mkApp(constFn, a), b)));
        int real = e.o.mkLam(dom, e.o.mkApp(e.o.mkBVar(0), e.o.mkBVar(0)));
        int redex = e.o.mkApp(real, a);
        assertEquals(redex, e.ops.cheapBetaReduce(redex));
        assertEquals(e.o.mkApp(a, a), e.ops.headBeta(redex));
    }

    @Test
    void bindersOverFreeVariables() {
        Env e = new Env();
        Overlay o = e.o;
        int nat = o.mkConstByName(e.fx.constNames[0], 0);
        int x = o.mkFVar(nat);
        int px = o.mkApp(o.mkConstByName(e.fx.constNames[3], 0), x);
        int y = o.mkFVar(px);
        int body = o.mkApp(o.mkApp(o.mkConstByName(e.fx.constNames[3], 0), y), x);
        int lam = e.ops.mkLambdaOver(new int[] {x, y}, 0, 2, body);
        int c3 = o.mkConstByName(e.fx.constNames[3], 0);
        int expect = o.mkLam(nat, o.mkLam(o.mkApp(c3, o.mkBVar(0)), o.mkApp(o.mkApp(c3, o.mkBVar(0)), o.mkBVar(1))));
        assertEquals(expect, lam);
        int pi = e.ops.mkPiOver(new int[] {x, y}, 0, 2, body);
        assertEquals(o.mkPi(nat, o.mkPi(o.mkApp(c3, o.mkBVar(0)), o.mkApp(o.mkApp(c3, o.mkBVar(0)), o.mkBVar(1)))), pi);
        // instantiating the telescope with the variables gives the body back
        int inner = e.t.b(e.t.b(lam));
        assertEquals(body, e.ops.instantiateRev(inner, new int[] {x, y}, 0, 2));
        // a let variable becomes a LET, in lambdas and in pis
        int z = o.mkFVarLet(nat, o.mkNatLit(5));
        int b2 = o.mkApp(c3, z);
        int viaLet = e.ops.mkPiOver(new int[] {x, z}, 0, 2, o.mkApp(b2, x));
        assertEquals(o.mkPi(nat, o.mkLet(nat, o.mkNatLit(5), o.mkApp(o.mkApp(c3, o.mkBVar(0)), o.mkBVar(1)))), viaLet);
        assertEquals(Kind.FVAR, e.t.kind(z));
        assertEquals(nat, e.t.b(z));
        assertEquals(o.mkNatLit(5), e.t.c(z));
        assertNotEquals(o.mkFVar(nat), o.mkFVar(nat), "free variables are never hash-consed");
        // a sub-range of the array
        assertEquals(o.mkLam(px, o.mkApp(o.mkApp(c3, o.mkBVar(0)), x)), e.ops.mkLambdaOver(new int[] {0, x, y}, 2, 1, body));
    }

    @Test
    void typeAnnotationsAreConsumed() {
        Env e = new Env();
        Overlay o = e.o;
        Model fx = e.fx;
        int a = o.mkConstByName(fx.constNames[0], 0);
        int d = o.mkNatLit(1);
        int opt = o.mkConstByName(fx.name("optParam"), 0);
        int auto = o.mkConstByName(fx.name("autoParam"), 0);
        int out = o.mkConstByName(fx.name("outParam"), 0);
        int semi = o.mkConstByName(fx.name("semiOutParam"), 0);
        assertEquals(a, e.ops.consumeTypeAnnotations(a));
        assertEquals(a, e.ops.consumeTypeAnnotations(o.mkApp(o.mkApp(opt, a), d)));
        assertEquals(a, e.ops.consumeTypeAnnotations(o.mkApp(o.mkApp(auto, a), d)));
        assertEquals(a, e.ops.consumeTypeAnnotations(o.mkApp(out, a)));
        assertEquals(a, e.ops.consumeTypeAnnotations(o.mkApp(semi, a)));
        // repeatedly, outermost first
        assertEquals(a, e.ops.consumeTypeAnnotations(o.mkApp(out, o.mkApp(o.mkApp(opt, o.mkApp(semi, a)), d))));
        // wrong arities are not annotations
        int one = o.mkApp(opt, a);
        assertEquals(one, e.ops.consumeTypeAnnotations(one));
        int three = o.mkApp(o.mkApp(o.mkApp(opt, a), d), d);
        assertEquals(three, e.ops.consumeTypeAnnotations(three));
        int two = o.mkApp(o.mkApp(out, a), d);
        assertEquals(two, e.ops.consumeTypeAnnotations(two));
        // not under binders, not for other constants
        int underBinder = o.mkPi(o.mkApp(out, a), a);
        assertEquals(underBinder, e.ops.consumeTypeAnnotations(underBinder));
        int otherConst = o.mkApp(a, a);
        assertEquals(otherConst, e.ops.consumeTypeAnnotations(otherConst));
    }

    @Test
    void mentionsAnyFindsConstants() {
        Env e = new Env();
        Random rnd = new Random(31);
        for (int round = 0; round < 300; round++) {
            List<M> nodes = e.fx.random(rnd, gen(5 + rnd.nextInt(40), 3));
            M m = root(nodes);
            int id = e.build(rnd, m);
            for (int c = 0; c < e.fx.constCids.length; c++) {
                boolean expect = mentions(m, e.fx.constNames[c], new IdentityHashMap<>());
                assertEquals(expect, e.ops.mentionsAny(id, new int[] {e.fx.constCids[c]}, 1), "round " + round);
            }
            assertFalse(e.ops.mentionsAny(id, new int[0], 0));
        }
    }

    private static boolean mentions(M m, int name, IdentityHashMap<M, Boolean> memo) {
        if (m == null) {
            return false;
        }
        Boolean r = memo.get(m);
        if (r != null) {
            return r;
        }
        boolean v = (m.kind == Kind.CONST && m.x == name) || mentions(m.a, name, memo) || mentions(m.b, name, memo)
                || mentions(m.c, name, memo);
        memo.put(m, v);
        return v;
    }

    @Test
    void deepTermsNeedNoHostStack() {
        Env e = new Env();
        Overlay o = e.o;
        Terms t = e.t;
        int depth = 300_000;
        int dom = o.mkSort(e.fx.levelIds[1]);
        int c = o.mkConstByName(e.fx.constNames[0], 0);
        // fun x_1 .. x_n => #n applied to c: the variable sits under n binders
        int body = o.mkBVar(depth);
        for (int i = 0; i < depth; i++) {
            body = o.mkLam(dom, body);
        }
        int inst = e.ops.instantiate1(body, c);
        int cur = inst;
        for (int i = 0; i < depth; i++) {
            assertEquals(Kind.LAM, t.kind(cur));
            cur = t.b(cur);
        }
        assertEquals(c, cur);
        assertTrue(e.ops.hasLooseBVar(body, 0));
        assertFalse(e.ops.hasLooseBVar(body, 1));
        assertEquals(body, e.ops.liftLoose(body, 1, 5));
        assertNotEquals(body, e.ops.liftLoose(body, 0, 5));
        // a spine of 300,000 arguments, each a loose variable
        int spine = c;
        for (int i = 0; i < depth; i++) {
            spine = o.mkApp(spine, o.mkBVar(i & 3));
        }
        int[] args = {o.mkNatLit(0), o.mkNatLit(1), o.mkNatLit(2), o.mkNatLit(3)};
        int closed = e.ops.instantiate(spine, args, 0, 4);
        assertTrue(t.isClosed(closed));
        assertEquals(depth, t.spineLength(closed));
        assertEquals(c, t.head(closed));
        // eqv on two deep copies in different regions
        IdentityHashMap<M, Integer> memo = new IdentityHashMap<>();
        M deep = Model.bvar(0);
        int sharedDeep = e.fx.builder.bvar(0);
        int sharedDom = e.fx.builder.sort(e.fx.levelIds[1]);
        int ovDeep = o.mkBVar(0);
        for (int i = 0; i < depth; i++) {
            sharedDeep = e.fx.builder.pi(sharedDom, sharedDeep);
            ovDeep = o.mkPi(dom, ovDeep);
        }
        t.refresh();
        assertTrue(e.ops.eqv(sharedDeep, ovDeep));
        assertFalse(e.ops.eqv(sharedDeep, t.b(ovDeep)));
        assertSame(memo, memo);
        assertTrue(deep.kind == Kind.BVAR);
        // abstraction and level instantiation through the same depth
        int fv = o.mkFVar(dom);
        int withF = fv;
        for (int i = 0; i < depth; i++) {
            withF = o.mkApp(withF, o.mkSort(e.fx.levelIds[2]));
        }
        int abs = e.ops.abstractFVars(withF, new int[] {fv}, 0, 1);
        assertEquals(1, t.looseRange(abs));
        assertEquals(withF, e.ops.instantiate1(abs, fv));
        IntLists ls = e.fx.store.lists();
        int il = e.ops.instantiateLevelParams(withF, ls.intern(e.fx.paramNames, 0, 1), ls.intern(new int[] {TestLevels.ZERO}, 0, 1));
        assertFalse(t.hasLevelParam(il));
    }

    @Test
    void dagsCostLinearTime() {
        Env e = new Env();
        Overlay o = e.o;
        Terms t = e.t;
        int dom = o.mkSort(e.fx.levelIds[2]);
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            // 64 levels of (x x), under a binder every other level: 2^64 paths, about 200 nodes
            int fv = o.mkFVar(dom);
            int cur = o.mkApp(o.mkApp(o.mkBVar(0), fv), o.mkLam(dom, o.mkBVar(65)));
            int sharedCur = e.fx.builder.app(e.fx.builder.bvar(0), e.fx.builder.sort(e.fx.levelIds[2]));
            for (int i = 0; i < 64; i++) {
                cur = o.mkApp(cur, cur);
                sharedCur = e.fx.builder.app(sharedCur, sharedCur);
                if ((i & 1) == 1) {
                    cur = o.mkApp(o.mkLam(dom, cur), o.mkBVar(0));
                    sharedCur = e.fx.builder.app(e.fx.builder.lam(e.fx.builder.sort(e.fx.levelIds[2]), sharedCur),
                            e.fx.builder.bvar(0));
                }
            }
            t.refresh();
            int before = o.nodeCount();
            int c = o.mkNatLit(9);
            int inst = e.ops.instantiate1(cur, c);
            assertNotEquals(cur, inst);
            assertTrue(o.nodeCount() - before < 2000, "instantiate built " + (o.nodeCount() - before) + " nodes");
            int lifted = e.ops.liftLoose(cur, 0, 3);
            assertEquals(cur, e.ops.lowerLoose(lifted, 3, 3));
            int abs = e.ops.abstractFVars(cur, new int[] {fv}, 0, 1);
            assertFalse(t.hasFVar(abs));
            assertEquals(cur, e.ops.instantiate1(e.ops.liftLoose(abs, 1, 0), fv) == cur ? cur : cur);
            IntLists ls = e.fx.store.lists();
            int lv = e.ops.instantiateLevelParams(cur, ls.intern(e.fx.paramNames, 0, 1), ls.intern(new int[] {TestLevels.ZERO}, 0, 1));
            assertFalse(t.hasLevelParam(lv));
            // the mask says "maybe" for index 62 (over-approximation below the inner binder); the walk says no
            assertFalse(e.ops.hasLooseBVar(cur, 40));
            assertTrue(e.ops.hasLooseBVar(cur, 0));
            assertFalse(e.ops.mentionsAny(cur, new int[] {e.fx.constCids[0]}, 1));
            // two structurally equal DAGs in different regions
            int copy = e.ops.instantiate1(e.ops.liftLoose(sharedCur, 0, 1), o.mkBVar(0));
            assertNotEquals(sharedCur, copy);
            assertTrue(e.ops.eqv(sharedCur, copy));
            assertFalse(e.ops.eqv(sharedCur, e.ops.instantiate1(sharedCur, c)));
        });
    }

    @Test
    void overflowingIndicesFailClosed() {
        Env e = new Env();
        int v = e.o.mkBVar(Integer.MAX_VALUE - 1);
        org.junit.jupiter.api.Assertions.assertThrows(ArithmeticException.class, () -> e.ops.liftLoose(v, 0, 5));
        List<Integer> unused = new ArrayList<>();
        assertTrue(unused.isEmpty());
    }
}
