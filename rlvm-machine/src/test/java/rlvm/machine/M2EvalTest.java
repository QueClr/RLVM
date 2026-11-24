package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import rlvm.api.MachineErrors;

/**
 * The per-instruction rows of ISA 4.2 (EVAL, ARGV) and 4.3 / 4.3.1 (FORCE, APPLY, INST) and DOM, on the
 * hand-built prelude. Every row runs under the depth bounds 64 and 0 (convention H12: with D = 0 every
 * non-leaf call is a suspension, so every {@code sigN} / {@code resume} case of {@link Eval} runs).
 */
@Tag("fast")
class M2EvalTest {

    private static final int[] DEPTHS = {Op.HOST_DEPTH, 0, 1, 3};

    interface Row {
        void run(Fixture f);
    }

    /** Runs a row on a fresh session of the prelude under each depth bound. */
    static void rows(Row row) {
        for (int d : DEPTHS) {
            Fixture f = M2.fresh(d);
            row.run(f);
            assertTrue(M2.settled(f), "machine stacks restored (I-STACKS) under D = " + d);
        }
    }

    // ---- EVAL: leaves (ISA 4.2 fast path) ---------------------------------------------------------------

    @Test
    void variableIsGet() {
        rows(f -> {
            int n = f.fvar("n", "Nat");
            int k = f.fvar("k", "Nat");
            assertEquals(n, f.eval(f.term("n")));
            assertEquals(k, f.eval(f.term("k")));
            assertEquals("?1:Nat", f.show(k));
            long ticks = f.m.ticks();
            f.eval(f.term("n"));
            assertEquals(ticks, f.m.ticks(), "a leaf costs no tick");
        });
    }

    @Test
    void sortConstLiteral() {
        rows(f -> {
            Machine m = f.m;
            assertEquals("(sort 1)", f.show(f.eval(f.term("Type"))));
            assertEquals("Nat", f.show(f.eval(f.term("Nat"))));
            assertEquals(Layout.CONST, Values.kind(m, f.eval(f.term("Nat"))));
            assertEquals(f.eval(f.term("Nat")), f.eval(f.term("Nat")), "CHEAD: one record per (row, levels)");
            assertEquals(Layout.imm(5), f.eval(f.term("(lit 5)")));
            int big = f.eval(f.term("(lit 4294967296)"));
            assertEquals(Layout.NBIG, Values.kind(m, big));
            assertEquals("4294967296", f.show(big));
            assertEquals(Layout.STR, Values.kind(m, f.eval(f.term("(str \"ab\")"))));
            // level instantiation at sort and const nodes only (ISA 2.7): the substitution of c1.{1, 2}
            int c1 = f.eval(f.term("(@ c1 1 2)"));
            int ls = Levels.substOf(m, c1);
            assertNotEquals(0, ls);
            assertEquals("(sort 1)", f.show(f.eval(f.term("(sort u)"), ls)));
            assertEquals("(sort 2)", f.show(f.eval(f.term("(sort v)"), ls)));
            assertEquals("id.{2}", f.show(f.eval(f.term("(@ id v)"), ls)));
            assertEquals(f.eval(f.term("(@ id 2)")), f.eval(f.term("(@ id v)"), ls));
        });
    }

    @Test
    void lambdaAndPiAreClosuresOverThePrunedFrame() {
        rows(f -> {
            Machine m = f.m;
            int n = f.fvar("n", "Nat");
            int k = f.fvar("k", "Nat");
            int closed = f.eval(f.term("(fun (x : Nat) x)"));
            assertEquals(Layout.LAM, Values.kind(m, closed));
            assertEquals(Layout.RUN_EMPTY, Values.b(m, closed), "a closed term has the empty frame");
            int t = f.term("(fun (x : Nat) (g x n))");
            int lam = f.eval(t);
            assertEquals(t, Values.a(m, lam));
            // the mask of the term is {1} (n; k is index 0): the frame is [n, HOLE]
            assertEquals(M2.list(n, Layout.HOLE), M2.list(f.run2slots(Values.b(m, lam))));
            int pi = f.eval(f.term("(pi (x : Nat) ((@ Eq 1) Nat x k))"));
            assertEquals(Layout.PI, Values.kind(m, pi));
            assertEquals(M2.list(k), M2.list(f.run2slots(Values.b(m, pi))));
            long ticks = m.ticks();
            assertEquals(lam, f.eval(t), "hash-consed");
            assertEquals(ticks, m.ticks(), "a closure costs no tick");
            // ls is kept only for a term with level parameters (ISA 2.7)
            int ls = Levels.substOf(m, f.eval(f.term("(@ c1 1 2)")));
            assertEquals(0, Values.c(m, f.eval(f.term("(fun (x : Nat) x)"), ls)));
            assertEquals(closed, f.eval(f.term("(fun (x : Nat) x)"), ls));
            assertEquals(ls, Values.c(m, f.eval(f.term("(fun (x : (sort u)) x)"), ls)));
        });
    }

    // ---- EVAL: application --------------------------------------------------------------------------------

    @Test
    void constantHeadIsMkAppOfArgv() {
        rows(f -> {
            Machine m = f.m;
            int n = f.fvar("n", "Nat");
            int v = f.eval(f.term("(g n (lit 2))"));
            assertEquals(Layout.NEU, Values.kind(m, v));
            assertEquals("(g ?0:Nat 2)", f.show(v));
            assertEquals(M2.list(n, Layout.imm(2)), M2.list(f.run2slots(Values.b(m, v))));
            assertEquals("(g (f ?0:Nat) (f (f ?0:Nat)))", f.show(f.eval(f.term("(g (f n) (f (f n)))"))));
            // the Nat.succ rule of MKAPP (ISA 4.2 NSUCC)
            assertEquals(Layout.imm(3), f.eval(f.term("(Nat.succ (lit 2))")));
            assertEquals(Layout.imm(1), f.eval(f.term("(Nat.succ Nat.zero)")));
            assertEquals("(succ^2 ?0:Nat)", f.show(f.eval(f.term("(Nat.succ (Nat.succ n))"))));
            // constants stay folded: no delta, no iota (ISA decision 7)
            assertEquals("(Nat.add two two)", f.show(f.eval(f.term("(Nat.add two two)"))));
            assertEquals("(Nat.rec.{1} Nat Nat.zero)", f.show(f.eval(f.term("((@ Nat.rec 1) Nat Nat.zero)"))));
        });
    }

    @Test
    void lambdaHeadIsBeta() {
        rows(f -> {
            Machine m = f.m;
            int n = f.fvar("n", "Nat");
            assertEquals("(g ?0:Nat ?0:Nat)", f.show(f.eval(f.term("((fun (x : Nat) (g x x)) n)"))));
            // several arguments at once (R02 section 4, head beta)
            assertEquals("(g n0 ?0:Nat)", f.show(f.eval(f.term("((fun (x : Nat) (y : Nat) (g y x)) n n0)"))));
            // fewer arguments than lambdas: the closure of the rest
            int partial = f.eval(f.term("((fun (x : Nat) (y : Nat) (g y x)) n)"));
            assertEquals(Layout.LAM, Values.kind(m, partial));
            assertEquals(M2.list(n), M2.list(f.run2slots(Values.b(m, partial))));
            assertEquals("(g n0 ?0:Nat)", f.show(f.apply(partial, f.eval(f.term("n0")))));
            // more arguments than lambdas: the body's value is applied to the rest
            assertEquals("(f n0)", f.show(f.eval(f.term("((fun (x : Nat) f) n n0)"))));
            assertEquals("(g ?0:Nat n0)", f.show(f.eval(f.term("((fun (x : Nat) (fun (y : Nat) (g x y))) n n0)"))));
            // a body that returns a closure which takes the next group
            assertEquals("(g n0 two)",
                    f.show(f.eval(f.term("((fun (x : Nat) ((fun (h : (-> Nat Nat Nat)) h) g)) n n0 two)"))));
        });
    }

    @Test
    void variableHead() {
        rows(f -> {
            Machine m = f.m;
            int n = f.fvar("n", "Nat");
            int h = f.fvar("h", "(-> Nat Nat)");
            // bound to a neutral: MKAPP
            int v = f.eval(f.term("(h n)"));
            assertEquals(Layout.NEU, Values.kind(m, v));
            assertEquals(h, Values.a(m, v));
            // bound to a closure: beta
            assertEquals("(g ?0:Nat ?0:Nat)", f.show(f.eval(f.term("((fun (k : (-> Nat Nat)) (k n)) (fun (x : Nat) (g x x)))"))));
            // bound to a neutral application: the spine is extended
            assertEquals("(g ?0:Nat n0)", f.show(f.eval(f.term("((fun (k : (-> Nat Nat)) (k n0)) (g n))"))));
        });
    }

    @Test
    void activeArgumentsAreDelayedAndInertOnesBuilt() {
        rows(f -> {
            Machine m = f.m;
            int n = f.fvar("n", "Nat");
            int v = f.eval(f.term("(g ((fun (x : Nat) x) n) (f n))"));
            int[] args = f.run2slots(Values.b(m, v));
            assertEquals(Layout.SUSP, Values.kind(m, args[0]), "a lambda-headed argument is a suspension (decision 8)");
            assertEquals(0, Values.memo(m, args[0], 0), "and it was not evaluated");
            assertEquals(Layout.NEU, Values.kind(m, args[1]), "an inert argument is built at once");
            assertEquals(n, f.force(args[0]));
            assertEquals(n, Values.memo(m, args[0], 0), "VAL");
            // MKSUSP of a suspension whose value is known is the value (ISA 4.2); the first term keeps its memo
            int w = f.eval(f.term("(f ((fun (x : Nat) x) n))"));
            assertEquals(n, f.run2slots(Values.b(m, w))[0]);
            assertEquals(v, f.eval(f.term("(g ((fun (x : Nat) x) n) (f n))")));
            // let and proj arguments are active too
            int u = f.eval(f.term("(g (let (x : Nat := n) x) (proj Prod 0 pr))"));
            int[] a2 = f.run2slots(Values.b(m, u));
            assertEquals(Layout.SUSP, Values.kind(m, a2[0]));
            assertEquals(Layout.SUSP, Values.kind(m, a2[1]));
            assertEquals("(proj Prod 0 pr)", f.show(f.force(a2[1])));
        });
    }

    @Test
    void argvRows() {
        rows(f -> {
            Machine m = f.m;
            int n = f.fvar("n", "Nat");
            int h = f.fvar("h", "(-> Nat Nat)");
            assertEquals(n, M2.argv(f, f.term("n")));
            assertEquals("Nat", f.show(M2.argv(f, f.term("Nat"))));
            assertEquals(Layout.imm(7), M2.argv(f, f.term("(lit 7)")));
            assertEquals(Layout.LAM, Values.kind(m, M2.argv(f, f.term("(fun (x : Nat) x)"))));
            assertEquals(Layout.PI, Values.kind(m, M2.argv(f, f.term("(-> Nat Nat)"))));
            assertEquals("(f ?0:Nat)", f.show(M2.argv(f, f.term("(f n)"))), "constant head: EVAL now");
            assertEquals("(?0:(PI (pi (_ : Nat) Nat) {}) ?0:Nat)", f.show(M2.argv(f, f.term("(h n)"))),
                    "variable head bound to a neutral: EVAL now");
            assertEquals(Layout.SUSP, Values.kind(m, M2.argv(f, f.term("((fun (x : Nat) x) n)"))), "lambda head: MKSUSP");
            assertEquals(Layout.SUSP, Values.kind(m, M2.argv(f, f.term("(let (x : Nat := n) (f x))"))), "let: MKSUSP");
            assertEquals(Layout.SUSP, Values.kind(m, M2.argv(f, f.term("(proj Prod 0 pr)"))), "proj: MKSUSP");
        });
        rows(f -> {
            // a variable head bound to a closure: MKSUSP; evaluated inside a body where k is a closure
            Machine m = f.m;
            int v = f.eval(f.term("((fun (k : (-> Nat Nat)) (g (k n0) n0)) (fun (x : Nat) x))"));
            int[] args = f.run2slots(Values.b(m, v));
            assertEquals(Layout.SUSP, Values.kind(m, args[0]));
            assertEquals("n0", f.show(f.force(args[0])));
        });
    }

    // ---- EVAL: let, proj ------------------------------------------------------------------------------------

    @Test
    void letIsZetaBySharing() {
        rows(f -> {
            Machine m = f.m;
            int n = f.fvar("n", "Nat");
            // the official whnf_core substitutes the value (R02 7.1): an atom or an inert value is bound as it is
            int v = f.eval(f.term("(let (x : Nat := two) (g x x))"));
            assertEquals("(g two two)", f.show(v));
            assertEquals(0, Values.flags(m, v) & Layout.FL_OPEN, "a closed let makes a closed value: Nat folding stays on (R02 8.4)");
            assertEquals("(g (f n0) (f n0))", f.show(f.eval(f.term("(let (x : Nat := (f n0)) (g x x))"))));
            assertEquals("(f n0)", f.show(f.eval(f.term("(let (x : Nat := (f n0)) x)"))));
            // an active value is a plain suspension, shared by every use and not flagged LET
            int w = f.eval(f.term("(let (x : Nat := ((fun (z : Nat) (f z)) n0)) (g x x))"));
            int[] args = f.run2slots(Values.b(m, w));
            assertEquals(args[0], args[1]);
            assertEquals(Layout.SUSP, Values.kind(m, args[0]));
            assertEquals(0, Values.flags(m, args[0]) & Layout.FL_LET);
            assertEquals(0, Values.memo(m, args[0], 0), "not evaluated (zeta is lazy)");
            assertEquals("(f n0)", f.show(f.force(args[0])));
            // a value over a free variable is OPEN because of the variable, as in the official term
            int open = f.eval(f.term("(let (x : Nat := (f n)) (g x x))"));
            assertTrue((Values.flags(m, open) & Layout.FL_OPEN) != 0);
            // a let chain; the body's value passes through
            assertEquals("(g two (f two))", M2.forced(f, f.eval(f.term("(let (x : Nat := two) (let (y : Nat := (f x)) (g x y)))"))));
            assertEquals("(g (f (f n0)) n0)",
                    f.show(f.eval(f.term("(let (x : Nat := (f n0)) (let (y : Nat := (f x)) (let (z : Nat := n0) (g y z))))"))));
            // an unread let variable is never built (B6)
            int before = M2.count(m, Layout.SUSP);
            assertEquals("n0", f.show(f.eval(f.term("(let (x : Nat := ((fun (z : Nat) z) two)) n0)"))));
            assertEquals(before + 1, M2.count(m, Layout.SUSP), "only the tabling record of the let node itself");
        });
    }

    /** The let variable of INFER (ISA 2.8; official infer_let, R02 6.6) is a LET suspension: EVAL keeps it and what is built from it is OPEN. */
    @Test
    void letSuspensionsAreKept() {
        rows(f -> {
            Machine m = f.m;
            int value = f.term("two");
            int let = Values.mkSusp(m, value, Layout.RUN_EMPTY, 0, true); // what INFER pushes for `let d := two`
            assertEquals(Layout.SUSP, Values.kind(m, let));
            assertTrue((Values.flags(m, let) & Layout.FL_LET) != 0);
            int body = f.eval(f.term("(fun (d : Nat) (g d (f d)))"));
            int v = f.apply(body, let);
            int[] args = f.run2slots(Values.b(m, v));
            assertEquals(let, args[0], "the variable yields the LET suspension, also for an inert value");
            assertTrue((Values.flags(m, v) & Layout.FL_OPEN) != 0, "OPEN on what is built from it (official hasFVar; perf/let-fold)");
            assertEquals("two", f.show(f.force(let)));
            // the value is known now; the LET suspension is still what the variable yields (PEEK keeps it)
            int again = f.apply(f.eval(f.term("(fun (d : Nat) (f d))")), let);
            assertEquals(let, f.run2slots(Values.b(m, again))[0]);
            assertEquals(args[1], again);
        });
    }

    @Test
    void projectionOfAConstructorIsTheFieldElseAProjCell() {
        rows(f -> {
            Machine m = f.m;
            assertEquals("n0", f.show(f.eval(f.term("(proj Prod 0 ((@ Prod.mk 0 0) Nat Bool n0 Bool.true))"))));
            assertEquals("Bool.true", f.show(f.eval(f.term("(proj Prod 1 ((@ Prod.mk 0 0) Nat Bool n0 Bool.true))"))));
            int stuck = f.eval(f.term("(proj Prod 1 pr)"));
            assertEquals(Layout.PROJ, Values.kind(m, stuck));
            assertEquals("(proj Prod 1 pr)", f.show(stuck));
            // the struct is not reduced (R02 7.2): prDef is a definition whose value is a constructor application
            assertEquals("(proj Prod 0 prDef)", f.show(f.eval(f.term("(proj Prod 0 prDef)"))));
            // a field that is a delayed argument comes out as the suspension; the projection is not tabled then
            int field = f.eval(f.term("(proj Prod 0 ((@ Prod.mk 0 0) Nat Bool ((fun (x : Nat) x) n0) Bool.true))"));
            assertEquals(Layout.SUSP, Values.kind(m, field));
            assertEquals("n0", f.show(f.force(field)));
            assertEquals("n0", f.show(f.eval(f.term("(proj Prod 0 ((@ Prod.mk 0 0) Nat Bool ((fun (x : Nat) x) n0) Bool.true))"))),
                    "PEEK: the value is known now");
            // a projection applied (head of a spine)
            assertEquals("((proj Prod 0 pr) n0)", f.show(f.eval(f.term("((proj Prod 0 pr) n0)"))));
        });
    }

    // ---- tabling --------------------------------------------------------------------------------------------

    @Test
    void evalIsTabledOnTheSuspRecord() {
        rows(f -> {
            Machine m = f.m;
            int n = f.fvar("n", "Nat");
            int k = f.fvar("k", "Nat");
            int t = f.term("((fun (x : Nat) (g x x)) n)");
            int v = f.eval(t);
            int s = Values.suspRecord(m, t, Runs.prune(m, 0, 2, m.terms.fvMask(t)), 0);
            assertEquals(Layout.SUSP, Values.kind(m, s));
            assertEquals(v, Values.memo(m, s, 0), "VAL of the SUSP record of (t, PRUNE(env, mask t), ls')");
            assertEquals(M2.list(n, Layout.HOLE), M2.list(f.run2slots(Values.b(m, s))));
            long ticks = m.ticks();
            int records = m.vp;
            assertEquals(v, f.eval(t));
            assertEquals(ticks, m.ticks(), "a memo hit costs no tick");
            assertEquals(records, m.vp, "and makes no record");
            // the record is the delayed argument of the same term under the same bindings (ISA 5.1)
            int w = f.eval(f.term("(f ((fun (x : Nat) (g x x)) n))"));
            assertEquals(v, f.run2slots(Values.b(m, w))[0], "MKSUSP yields the known VAL");
            assertNotEquals(0, k);
        });
    }

    // ---- closure identity ---------------------------------------------------------------------------------

    @Test
    void closureIdentityIsIndependentOfThePathToTheEnvironment() {
        rows(f -> {
            Machine m = f.m;
            int n = f.fvar("n", "Nat");
            int k = f.fvar("k", "Nat");
            // the same inner lambda term, reached with its variable bound to n in four different environments
            int inner = f.term("(fun (a : Nat) (fun (y : Nat) (g y a)))");
            int viaContext = f.eval(f.term("((fun (a : Nat) (fun (y : Nat) (g y a))) n)"));
            int viaApply = f.apply(f.eval(inner), n);
            int viaPadding = f.eval(f.term("((fun (p : Nat) (q : Nat) ((fun (a : Nat) (fun (y : Nat) (g y a))) n)) k n0)"));
            int viaLetPadding = f.eval(f.term("(let (p : Nat := (f k)) ((fun (a : Nat) (fun (y : Nat) (g y a))) n))"));
            assertEquals(Layout.LAM, Values.kind(m, viaContext));
            assertEquals(viaContext, viaApply);
            assertEquals(viaContext, viaPadding);
            assertEquals(viaContext, viaLetPadding);
            assertEquals(M2.list(n), M2.list(f.run2slots(Values.b(m, viaContext))));
            // another binding at the position the term reads: another closure
            assertNotEquals(viaContext, f.apply(f.eval(inner), k));
            // bindings that differ only where the term does not look: the same suspension and the same closure
            int t2 = f.term("(fun (a : Nat) (b : Nat) (g ((fun (z : Nat) z) a) (fun (y : Nat) a)))");
            int r1 = f.apply(f.eval(t2), n, k);
            int r2 = f.apply(f.eval(t2), n, f.eval(f.term("n0")));
            assertEquals(r1, r2);
            int[] args = f.run2slots(Values.b(m, r1));
            assertEquals(M2.list(n, Layout.HOLE), M2.list(f.run2slots(Values.b(m, args[0]))), "HOLE where the term does not read");
        });
    }

    @Test
    void lsIsPartOfClosureIdentity() {
        rows(f -> {
            Machine m = f.m;
            // RECONCILIATION B1 / ISA 2.7: c1.{u,v} and c2.{v,u} have the same value; one term under both substitutions
            int body = f.term("(-> (sort u) (sort v))");
            assertEquals(f.showTerm(body), f.showTerm(f.store.decls().value(f.cid("c1"))));
            assertEquals(f.showTerm(body), f.showTerm(f.store.decls().value(f.cid("c2"))));
            int ls1 = Levels.substOf(m, f.eval(f.term("(@ c1 1 2)")));
            int ls2 = Levels.substOf(m, f.eval(f.term("(@ c2 1 2)")));
            assertNotEquals(ls1, ls2);
            int v1 = f.eval(body, ls1);
            int v2 = f.eval(body, ls2);
            assertEquals(Layout.PI, Values.kind(m, v1));
            assertNotEquals(v1, v2, "same term, same levels, different parameter names: different closures");
            assertEquals("(sort 1)", f.show(M2.dom(f, v1)));
            assertEquals("(sort 2)", f.show(M2.dom(f, v2)));
            assertEquals("(sort 2)", f.show(f.inst(v1, Layout.HOLE)));
            assertEquals("(sort 1)", f.show(f.inst(v2, Layout.HOLE)));
        });
    }

    // ---- FORCE ----------------------------------------------------------------------------------------------

    @Test
    void forceRows() {
        rows(f -> {
            Machine m = f.m;
            int n = f.fvar("n", "Nat");
            assertEquals(n, f.force(n), "not a SUSP: itself");
            assertEquals(Layout.imm(3), f.force(Layout.imm(3)));
            assertEquals(n, Eval.forceFast(m, n));
            int s = M2.argv(f, f.term("((fun (x : Nat) (f x)) n)"));
            assertEquals(Layout.SUSP, Values.kind(m, s));
            assertEquals(Op.MISS, Eval.forceFast(m, s));
            long ticks = m.ticks();
            int v = f.force(s);
            assertEquals("(f ?0:Nat)", f.show(v));
            assertTrue(m.ticks() > ticks, "FORCE of a pending suspension is work");
            assertEquals(v, Values.memo(m, s, 0), "VAL := the value");
            assertEquals(v, Eval.forceFast(m, s));
            ticks = m.ticks();
            assertEquals(v, f.force(s));
            assertEquals(ticks, m.ticks(), "a known VAL costs no tick");
            // a suspension whose term passes on another suspension: VAL is never a SUSP (ISA 2.6)
            int outer = M2.argv(f, f.term("((fun (x : Nat) x) ((fun (y : Nat) (g y y)) n))"));
            assertEquals(Layout.SUSP, Values.kind(m, outer));
            int w = f.force(outer);
            assertEquals("(g ?0:Nat ?0:Nat)", f.show(w));
            assertEquals(w, Values.memo(m, outer, 0));
            assertNotEquals(Layout.SUSP, Values.kind(m, w));
        });
    }

    @Test
    void aLetSuspensionEvaluatesThroughThePlainRecord() {
        rows(f -> {
            Machine m = f.m;
            int n = f.fvar("n", "Nat");
            int value = f.term("((fun (x : Nat) (f x)) n)");
            int frame = Runs.prune(m, 0, 1, m.terms.fvMask(value));
            int let = Values.mkSusp(m, value, frame, 0, true); // INFER's let variable
            int plain = Values.suspRecord(m, value, frame, 0);
            assertNotEquals(plain, let, "a record of its own (I-FLAG)");
            assertEquals(0, Values.memo(m, plain, 0));
            int forced = f.force(let);
            assertEquals("(f ?0:Nat)", f.show(forced));
            assertEquals(forced, Values.memo(m, let, 0));
            assertEquals(forced, Values.memo(m, plain, 0), "tabled on the plain record as usual");
            assertEquals(forced, f.eval(value));
            assertNotEquals(0, n);
        });
    }

    // ---- APPLY (ISA 4.3.1) ----------------------------------------------------------------------------------

    @Test
    void applyRows() {
        rows(f -> {
            Machine m = f.m;
            int n = f.fvar("n", "Nat");
            int k = f.fvar("k", "Nat");
            // neutral heads: MKAPP, inline
            int h = f.fvar("h", "(-> Nat Nat)");
            int gn = f.eval(f.term("(g n)"));
            int prj = f.eval(f.term("(proj Prod 0 pr)"));
            long ticks = m.ticks();
            assertEquals("(f ?0:Nat)", f.show(f.apply(f.eval(f.term("f")), n)));
            assertEquals("(g ?0:Nat ?1:Nat)", f.show(f.apply(gn, k)), "NEU: the spine is extended");
            assertEquals(h, Values.a(m, f.apply(h, n)));
            assertEquals("((proj Prod 0 pr) ?0:Nat)", f.show(f.apply(prj, n)));
            assertEquals(ticks, m.ticks(), "MKAPP is class p: no tick");
            // LAM: exact, partial and over-application
            int lam = f.eval(f.term("(fun (x : Nat) (y : Nat) (g y x))"));
            assertEquals("(g ?1:Nat ?0:Nat)", f.show(f.apply(lam, n, k)));
            int part = f.apply(lam, n);
            assertEquals(Layout.LAM, Values.kind(m, part));
            assertEquals("(g ?1:Nat ?0:Nat)", f.show(f.apply(part, k)));
            int ret = f.eval(f.term("(fun (x : Nat) g)"));
            assertEquals("(g ?1:Nat ?0:Nat)", f.show(f.apply(ret, n, k, n)));
            int two = f.eval(f.term("(fun (x : Nat) (fun (y : Nat) (z : Nat) (g y z)))"));
            assertEquals("(g ?1:Nat ?0:Nat)", f.show(f.apply(two, n, k, n)), "a second group of lambdas");
            // a suspension is forced first
            int s = M2.argv(f, f.term("((fun (z : Nat) (fun (x : Nat) (g z x))) n)"));
            assertEquals(Layout.SUSP, Values.kind(m, s));
            assertEquals("(g ?0:Nat ?1:Nat)", f.show(f.apply(s, k)));
            assertNotEquals(0, Values.memo(m, s, 0));
        });
    }

    @Test
    void applyOfANonFunctionFaults() {
        rows(f -> {
            int n = f.fvar("n", "Nat");
            int sort = f.eval(f.term("Type"));
            int pi = f.eval(f.term("(-> Nat Nat)"));
            f.expectFail(MachineErrors.E_ILLTYPED, () -> f.apply(sort, n));
            f.expectFail(MachineErrors.E_ILLTYPED, () -> f.apply(pi, n));
            f.expectFail(MachineErrors.E_ILLTYPED, () -> f.apply(Layout.imm(3), n));
            f.expectFail(MachineErrors.E_ILLTYPED, () -> f.eval(f.term("((lit 3) n)")));
            f.expectFail(MachineErrors.E_ILLTYPED, () -> f.eval(f.term("((fun (x : Nat) (lit 3)) n n)")));
        });
    }

    // ---- B6: HOLE for unread binders ------------------------------------------------------------------------

    @Test
    void unreadBindersReceiveHoleAndTheirArgumentsAreNotBuilt() {
        rows(f -> {
            Machine m = f.m;
            int n = f.fvar("n", "Nat");
            int konst = f.eval(f.term("(fun (x : Nat) (y : Nat) y)"));
            // the binder is not read: HOLE may be passed, nothing faults
            assertEquals(n, f.apply(konst, Layout.HOLE, n));
            // EVAL does not build the argument of an unread binder: no suspension for the active term
            int active = f.term("((fun (z : Nat) (g z z)) n0)");
            int before = M2.count(m, Layout.SUSP);
            assertEquals(n, f.eval(f.term("((fun (x : Nat) (y : Nat) y) ((fun (z : Nat) (g z z)) n0) n)")));
            assertEquals(before + 1, M2.count(m, Layout.SUSP), "only the tabling record of the application itself");
            assertEquals(0, M2.find(m, Layout.SUSP, active), "no record for the unread argument");
            // the same through a variable bound to a closure
            before = M2.count(m, Layout.SUSP);
            int r = f.eval(f.term("((fun (k : (-> Nat Nat Nat)) (k ((fun (z : Nat) (f z)) n0) n)) (fun (x : Nat) (y : Nat) y))"));
            assertEquals(n, r);
            assertEquals(0, M2.find(m, Layout.SUSP, f.term("((fun (z : Nat) (f z)) n0)")));
            // a read binder does get its argument
            int v = f.eval(f.term("((fun (x : Nat) (y : Nat) (g x y)) ((fun (z : Nat) (g z z)) n0) n)"));
            assertEquals(Layout.SUSP, Values.kind(m, f.run2slots(Values.b(m, v))[0]));
            assertNotEquals(0, M2.find(m, Layout.SUSP, active));
            assertTrue(before > 0);
        });
    }

    @Test
    void readingAHoleIsAFatalFault() {
        rows(f -> {
            int n = f.fvar("n", "Nat");
            int id = f.eval(f.term("(fun (x : Nat) x)"));
            int pair = f.eval(f.term("(fun (x : Nat) (y : Nat) (g x y))"));
            Fixture.MachineFailure e = M2.failure(() -> f.apply(id, Layout.HOLE));
            assertEquals(MachineErrors.E_HOLE, e.code);
            assertEquals(Op.FATAL, e.signal, "E_HOLE is fatal (ISA 1.4): never turned into U");
            f.expectFail(MachineErrors.E_HOLE, () -> f.apply(pair, n, Layout.HOLE));
            // a HOLE must not enter a spine either
            f.expectFail(MachineErrors.E_HOLE, () -> f.apply(f.eval(f.term("f")), Layout.HOLE));
            f.expectFail(MachineErrors.E_HOLE, () -> f.apply(f.eval(f.term("(fun (x : Nat) f)")), n, Layout.HOLE));
            // an environment shorter than the term's variables: fault, not a neighbouring environment (ISA 2.4)
            f.expectFail(MachineErrors.E_INTERNAL, () -> f.eval(f.term("#7")));
            assertEquals(n, f.eval(f.term("n")), "the machine is usable after a fault (context re-opened)");
        });
    }

    // ---- INST, DOM ------------------------------------------------------------------------------------------

    @Test
    void instOfAPi() {
        rows(f -> {
            Machine m = f.m;
            int n = f.fvar("n", "Nat");
            int k = f.fvar("k", "Nat");
            int dep = f.eval(f.term("(pi (x : Nat) ((@ Eq 1) Nat x k))"));
            assertEquals(Op.MISS, Eval.instFast(m, dep, n));
            assertEquals("(Eq.{1} Nat ?0:Nat ?1:Nat)", f.show(f.inst(dep, n)));
            assertEquals("(Eq.{1} Nat n0 ?1:Nat)", f.show(f.inst(dep, f.eval(f.term("n0")))));
            assertEquals(0, Values.memo(m, dep, 0), "a dependent codomain is not memoised");
            // the body does not read its variable: computed once, kept in COD (ISA 2.6)
            int arrow = f.eval(f.term("(-> Nat ((@ Eq 1) Nat k k))"));
            int cod = f.inst(arrow, n);
            assertEquals("(Eq.{1} Nat ?1:Nat ?1:Nat)", f.show(cod));
            assertEquals(cod, Values.memo(m, arrow, 0), "COD");
            assertEquals(cod, Eval.instFast(m, arrow, k));
            long ticks = m.ticks();
            assertEquals(cod, f.inst(arrow, k));
            assertEquals(cod, f.inst(arrow, Layout.HOLE), "the argument is not read (B6)");
            assertEquals(ticks, m.ticks());
            // a telescope: one binder per INST
            int tele = f.eval(f.term("(pi (a : Nat) (b : Nat) ((@ Eq 1) Nat a b))"));
            int rest = f.inst(tele, n);
            assertEquals(Layout.PI, Values.kind(m, rest));
            assertEquals("(Eq.{1} Nat ?0:Nat ?1:Nat)", f.show(f.inst(rest, k)));
            // not a Pi
            f.expectFail(MachineErrors.E_INTERNAL, () -> f.inst(n, k));
        });
    }

    @Test
    void instOfAPii() {
        rows(f -> {
            Machine m = f.m;
            int n = f.fvar("n", "Nat");
            // the type of a lambda (ISA 2.2 PII): codomain at x = INFER(body, frame + x, ONLY) (R02 6.3)
            int pii = f.inferOnly(f.term("(fun (x : Nat) (g x x))"));
            assertEquals(Layout.PII, Values.kind(m, pii));
            assertEquals("Nat", f.show(M2.dom(f, pii)));
            assertEquals("Nat", f.show(f.force(f.inst(pii, n))));
            int dep = f.inferOnly(f.term("(fun (x : Nat) ((@ Eq.refl 1) Nat x))"));
            assertEquals("(Eq.{1} Nat ?0:Nat ?0:Nat)", M2.forced(f, f.inst(dep, n)));
            assertEquals("(Eq.{1} Nat n0 n0)", M2.forced(f, f.inst(dep, f.eval(f.term("n0")))));
            assertEquals(0, Values.memo(m, dep, 0), "a dependent body type is not memoised");
            // the lambda body does not read its variable: computed once, kept in COD, HOLE passed (B6)
            int konst = f.inferOnly(f.term("(fun (x : Nat) (f n))"));
            int cod = f.inst(konst, Layout.HOLE);
            assertEquals("Nat", f.show(cod));
            assertEquals(cod, Values.memo(m, konst, 0));
            long ticks = m.ticks();
            assertEquals(cod, f.inst(konst, n));
            assertEquals(ticks, m.ticks());
        });
    }

    @Test
    void domRows() {
        rows(f -> {
            Machine m = f.m;
            int n = f.fvar("n", "Nat");
            int lam = f.eval(f.term("(fun (x : ((@ Eq 1) Nat n n)) x)"));
            int d = M2.dom(f, lam);
            assertEquals("(Eq.{1} Nat ?0:Nat ?0:Nat)", f.show(d));
            assertEquals(d, Values.memo(m, lam, Layout.M_DOM - Layout.M0), "memo M_DOM");
            long ticks = m.ticks();
            assertEquals(d, M2.dom(f, lam));
            assertEquals(ticks, m.ticks());
            int pi = f.eval(f.term("(pi (x : Nat) Nat)"));
            assertEquals("Nat", f.show(M2.dom(f, pi)));
            // an active domain is forced: DOM never returns a suspension
            int act = f.eval(f.term("(fun (x : ((fun (A : Type) A) Nat)) x)"));
            assertEquals("Nat", f.show(M2.dom(f, act)));
            // a domain that is a let variable
            int viaLet = f.eval(f.term("(let (T : Type := Nat) (fun (x : T) x))"));
            assertEquals(Layout.LAM, Values.kind(m, viaLet));
            assertEquals("Nat", f.show(M2.dom(f, viaLet)));
            f.expectFail(MachineErrors.E_INTERNAL, () -> M2.dom(f, n));
        });
    }

    // ---- far frames, limits -----------------------------------------------------------------------------------

    /**
     * More than 63 binders in scope (ISA 2.4: mask bit 63, far frames with a REST run): a closure and a suspension
     * made 80 binders deep read the outermost variable; applied and forced they find it.
     */
    @Test
    void farFrames() {
        rows(f -> {
            Machine m = f.m;
            rlvm.store.Overlay ov = f.ctx.overlay;
            int nat = f.term("Nat");
            int depth = 80;
            // fun x0 .. x79 => g ((fun z => g z x0) x79) (fun y => g x0 y)
            int inner = ov.mkLam(nat, f.term("(g #80 #0)")); // under x0..x79, y: x0 is index 80
            int redex = ov.mkApp(ov.mkLam(nat, ov.mkApp(ov.mkApp(f.term("g"), ov.mkBVar(0)), ov.mkBVar(depth))), ov.mkBVar(0));
            int body = ov.mkApp(ov.mkApp(f.term("g"), redex), inner);
            int t = body;
            for (int i = 0; i < depth; i++) {
                t = ov.mkLam(nat, t);
            }
            int[] args = new int[depth];
            for (int i = 0; i < depth; i++) {
                args[i] = Layout.imm(1000 + i);
            }
            int v = f.apply(f.eval(t), args);
            int[] spine = f.run2slots(Values.b(m, v));
            int susp = spine[0];
            int clo = spine[1];
            assertEquals(Layout.SUSP, Values.kind(m, susp));
            assertEquals(Layout.LAM, Values.kind(m, clo));
            assertTrue((m.rs[Values.b(m, clo)] & Layout.RUN_FAR) != 0, "a far frame");
            assertEquals("(g 1079 1000)", f.show(f.force(susp)), "z := x79, x0 through the REST run");
            assertEquals("(g 1000 7)", f.show(f.apply(clo, Layout.imm(7))));
            // the same closure through an environment that differs where the term does not look
            args[40] = Layout.imm(5);
            int w = f.apply(f.eval(t), args);
            assertEquals("(g 1000 7)", f.show(f.apply(f.run2slots(Values.b(m, w))[1], Layout.imm(7))));
            // a telescope of 80 Pis walked with INST; the last codomain reads the first variable
            int ty = f.term("((@ Eq 1) Nat #79 #0)");
            for (int i = 0; i < depth; i++) {
                ty = ov.mkPi(nat, ty);
            }
            int p = f.eval(ty);
            for (int i = 0; i < depth; i++) {
                assertEquals(Layout.PI, Values.kind(m, p));
                p = f.inst(p, Layout.imm(2000 + i));
            }
            assertEquals("(Eq.{1} Nat 2000 2079)", f.show(p));
        });
    }

    @Test
    void aSpineOfMoreThan65535ArgumentsIsALimit() {
        rows(f -> {
            int n = f.fvar("n", "Nat");
            int[] args = new int[Op.SPINE_MAX + 1];
            java.util.Arrays.fill(args, n);
            Fixture.MachineFailure e = M2.failure(() -> f.apply(f.eval(f.term("g")), args));
            assertEquals(MachineErrors.E_LIMIT, e.code);
            assertEquals(Op.ABORT, e.signal, "a recoverable limit (ISA 1.4): DECLINE, or U inside a TRY");
            int[] ok = new int[Op.SPINE_MAX];
            java.util.Arrays.fill(ok, n);
            assertEquals(Layout.NEU, Values.kind(f.m, f.apply(f.eval(f.term("g")), ok)));
        });
    }

    // ---- frames and ticks -----------------------------------------------------------------------------------

    @Test
    void anInertNestIsOneCallEvent() {
        for (String sw : new String[] {"", "inert"}) {
            rows(f -> {
                f.switches(sw);
                f.fvar("n", "Nat");
                int t = f.term("(g (f (f n)) (g (f n0) ((@ Prod.mk 0 0) Nat Bool (f n) Bool.true)))");
                int[] trace = M2.trace(f, () -> f.eval(t));
                assertEquals(M2.list(Op.EVAL), M2.list(trace), "one EVAL frame for the whole nest (ISA 8.2, section 9)");
                // an active term below a nest is not evaluated at all; forcing it is a FORCE and its own EVAL
                int u = f.term("(g (f ((fun (x : Nat) (g x x)) n)) n0)");
                int[] v = new int[1];
                assertEquals(M2.list(Op.EVAL), M2.list(M2.trace(f, () -> v[0] = f.eval(u))));
                int inner = f.run2slots(Values.b(f.m, f.run2slots(Values.b(f.m, v[0]))[0]))[0];
                assertEquals(M2.list(Op.FORCE, Op.EVAL, Op.APPLY, Op.EVAL), M2.list(M2.trace(f, () -> f.force(inner))));
            });
        }
    }

    @Test
    void ticksCountNodesThatDoWork() {
        rows(f -> {
            Machine m = f.m;
            f.fvar("n", "Nat");
            long t0 = m.ticks();
            f.eval(f.term("(g (f (f n)) (f n0))"));
            assertEquals(4, m.ticks() - t0, "one per application node of the nest; leaves are free");
            t0 = m.ticks();
            f.eval(f.term("(g (f (f n)) (f n0))"));
            assertEquals(0, m.ticks() - t0);
            t0 = m.ticks();
            f.eval(f.term("(g (f (f n)) (f two))"));
            assertEquals(2, m.ticks() - t0, "shared sub-terms are memo hits");
        });
    }

    @Test
    void theMachineStacksAreRestoredAfterASignal() {
        rows(f -> {
            int n = f.fvar("n", "Nat");
            // a fault deep inside nested applications, lets and betas
            f.expectFail(MachineErrors.E_ILLTYPED,
                    () -> f.eval(f.term("(let (a : Nat := n) ((fun (x : Nat) ((fun (y : Nat) ((lit 1) y)) a)) n))")));
            f.expectFail(MachineErrors.E_ILLTYPED,
                    () -> f.eval(f.term("((fun (x : Nat) ((fun (y : Nat) ((lit 1) y)) x)) n)")));
            assertFalse(f.m.wp != 0 || f.m.xp != 0, "W and scratch are empty");
            assertEquals("(f ?0:Nat)", f.show(f.eval(f.term("(f n)"))));
        });
    }
}
