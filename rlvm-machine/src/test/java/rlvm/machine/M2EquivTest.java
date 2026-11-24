package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Convention equivalence for family EV (ISA 1.3 "what is observable", 8.2; convention H12 item 1): one workload of
 * EVAL, FORCE, APPLY, INST, DOM and ARGV gives the same value ids, the same records and runs word by word, the same
 * memo words, the same tick count and the same frame trace (call events) under the depth bounds 64, 0, 1, 2, 7 and
 * unbounded, and with the inert-nest loop on or off (SW_INERT_LOOP changes nothing observable).
 */
@Tag("fast")
class M2EquivTest {

    private static final String[] TERMS = {
        "n", "Nat", "(lit 5)", "(sort 1)", "(fun (x : Nat) (g x n))", "(pi (x : Nat) ((@ Eq 1) Nat x k))",
        "(g n (lit 2))", "(g (f n) (f (f n)))", "(Nat.succ (Nat.succ n))", "(h (f n))", "(h (h (h k)))",
        "((fun (x : Nat) (g x x)) n)", "((fun (x : Nat) (y : Nat) (g y x)) n n0)", "((fun (x : Nat) (y : Nat) (g y x)) n)",
        "((fun (x : Nat) f) n n0)", "((fun (x : Nat) ((fun (q : (-> Nat Nat Nat)) q) g)) n n0 two)",
        "((fun (q : (-> Nat Nat)) (q n)) (fun (x : Nat) (g x x)))", "((fun (q : (-> Nat Nat)) (q n0)) (g n))",
        "(g ((fun (x : Nat) x) n) (f n))", "(f ((fun (x : Nat) x) n))",
        "(g (let (x : Nat := n) x) (proj Prod 0 pr))", "(let (x : Nat := two) (g x x))",
        "(let (x : Nat := two) (let (y : Nat := (f x)) (g x y)))", "(let (x : Nat := ((fun (z : Nat) z) two)) n0)",
        "(let (x : Nat := (f n0)) x)", "(let (x : Nat := (g (f n) (f (f k)))) (let (y : Nat := (h x)) (g y (g x y))))",
        "(proj Prod 0 ((@ Prod.mk 0 0) Nat Bool n0 Bool.true))", "(proj Prod 1 pr)",
        "(proj Prod 0 ((@ Prod.mk 0 0) Nat Bool ((fun (x : Nat) x) n0) Bool.true))", "((proj Prod 0 pr) n0)",
        "((fun (x : Nat) (y : Nat) y) ((fun (z : Nat) (g z z)) n0) n)",
        "((fun (q : (-> Nat Nat Nat)) (q ((fun (z : Nat) (f z)) n0) n)) (fun (x : Nat) (y : Nat) y))",
        "((fun (a : Nat) (b : Nat) (g ((fun (z : Nat) z) a) (fun (y : Nat) a))) n k)",
        "(let (p : Nat := (f k)) ((fun (a : Nat) (fun (y : Nat) (g y a))) n))",
        "(g (f (f n)) (g (f n0) ((@ Prod.mk 0 0) Nat Bool (f n) Bool.true)))",
        "((fun (x : Nat) ((fun (y : Nat) ((fun (z : Nat) (g x (g y z))) (f y))) (f x))) (f n))",
        "(proj Prod 0 (proj Prod 0 (proj Prod 0 ((fun (x : Nat) (let (y : Nat := x) (h y))) n))))",
    };

    /** What a configuration produced. */
    private static final class Run {
        final List<Integer> ids = new ArrayList<>();
        int[] records;
        int[] runs;
        int[] trace;
        long ticks;
        long frames;
    }

    /** Forces v and every argument below it (to a depth of 6), recording each value. */
    private static void deepForce(Fixture f, int v, int depth, List<Integer> out) {
        v = f.force(v);
        out.add(v);
        if (depth > 0 && v > Layout.HOLE && Values.kind(f.m, v) == Layout.NEU) {
            for (int a : f.run2slots(Values.b(f.m, v))) {
                deepForce(f, a, depth - 1, out);
            }
        }
    }

    private static Run workload(Fixture f, int hostDepth, String switches, int[] nests) {
        M2.reset(f, hostDepth, switches);
        return workloadBody(f, nests);
    }

    private static Run workloadBody(Fixture f, int[] nests) {
        Machine m = f.m;
        Run r = new Run();
        long t0 = m.ticks();
        long f0 = m.framesWritten;
        r.trace = M2.trace(f, () -> {
            int n = f.fvar("n", "Nat");
            int k = f.fvar("k", "Nat");
            int h = f.fvar("h", "(-> Nat Nat)");
            for (String s : TERMS) {
                int v = f.eval(f.term(s));
                r.ids.add(v);
                r.ids.add(M2.argv(f, f.term(s)));
            }
            for (String s : TERMS) {
                deepForce(f, f.eval(f.term(s)), 6, r.ids);
            }
            int lam = f.eval(f.term("(fun (x : Nat) (y : Nat) (g y x))"));
            r.ids.add(f.apply(lam, n, k));
            r.ids.add(f.apply(f.apply(lam, n), k));
            r.ids.add(f.apply(f.eval(f.term("(fun (x : Nat) g)")), n, k, n));
            r.ids.add(f.apply(h, f.apply(h, n)));
            int pi = f.eval(f.term("(pi (a : Nat) (b : ((fun (A : Type) A) Nat)) ((@ Eq 1) Nat a b))"));
            r.ids.add(M2.dom(f, pi));
            int rest = f.inst(pi, n);
            r.ids.add(rest);
            r.ids.add(M2.dom(f, rest));
            r.ids.add(f.inst(rest, k));
            int arrow = f.eval(f.term("(-> Nat ((@ Eq 1) Nat k k))"));
            r.ids.add(f.inst(arrow, n));
            r.ids.add(f.inst(arrow, Layout.HOLE));
            for (int t : nests) {
                deepForce(f, f.eval(t), 2, r.ids);
            }
        });
        assertTrue(M2.settled(f), "stacks restored under D = " + m.hostDepth);
        r.ticks = m.ticks() - t0;
        r.frames = m.framesWritten - f0;
        r.records = M2.records(m);
        r.runs = M2.runs(m);
        return r;
    }

    @Test
    void theSameObservablesUnderEveryDepthBoundAndWithTheInertLoop() {
        Fixture f = M2.fresh(Op.HOST_DEPTH);
        int[] nests = {M2DeepTest.listLiteral(f, 300), M2DeepTest.appNest(f, f.term("f"), f.term("n0"), 300),
            M2DeepTest.binderNest(f, 300), M2DeepTest.mixedNest(f, 300), M2DeepTest.lazyNest(f, f.term("n0"), 300)};
        Run base = workload(f, Op.HOST_DEPTH, "", nests);
        assertTrue(base.frames > 0, "the nests of depth 300 pass the bound 64");
        int[] depths = {0, 1, 2, 7, Op.HOST_DEPTH, Op.HOST_DEPTH_UNBOUNDED};
        for (String sw : new String[] {"", "inert"}) {
            for (int d : depths) {
                Run r = workload(f, d, sw, nests);
                String where = "D = " + d + (sw.isEmpty() ? "" : ", " + sw);
                assertEquals(base.ids, r.ids, "value ids, " + where);
                assertEquals(base.ticks, r.ticks, "ticks, " + where);
                assertArrayEquals(base.trace, r.trace, "frame trace (call events), " + where);
                assertArrayEquals(base.records, r.records, "records incl. memo words, " + where);
                assertArrayEquals(base.runs, r.runs, "runs, " + where);
                if (d == Op.HOST_DEPTH_UNBOUNDED) {
                    assertEquals(0, r.frames, "no frame is written without a bound");
                }
            }
        }
    }

    /**
     * The debug modes of ISA 5.5 that have hooks in family EV run clean on the workload: memocheck (VAL, COD, DOM;
     * compared up to PEEK), scopeaudit (every GET), maskaudit (every PRUNE).
     */
    @Test
    void theDebugModesAreCleanOnTheWorkload() {
        Fixture f = M2.fresh(Op.HOST_DEPTH);
        int[] nests = {M2DeepTest.listLiteral(f, 100), M2DeepTest.binderNest(f, 100), M2DeepTest.mixedNest(f, 100),
            M2DeepTest.lazyNest(f, f.term("n0"), 100)};
        Run plain = workload(f, Op.HOST_DEPTH, "", nests);
        for (int d : new int[] {Op.HOST_DEPTH, 0}) {
            for (String sw : new String[] {"", "inert"}) {
                M2.reset(f, d, sw);
                f.m.debug = Debug.parseModes("memocheck,scopeaudit,maskaudit");
                long checks = f.m.memoChecks;
                Run r = workloadBody(f, nests);
                assertEquals(plain.ticks, r.ticks, "a memo check is charged to nobody");
                assertTrue(f.m.memoChecks > checks, "memo hits were re-computed");
            }
        }
    }

    /** The case that makes VAL checkable only up to PEEK: an argument suspension is forced between the write and the hit. */
    @Test
    void aMemoCheckOfValIsUpToPeek() {
        Fixture f = M2.fresh(Op.HOST_DEPTH);
        Machine m = f.m;
        int n = f.fvar("n", "Nat");
        // s1 = SUSP((fun y => f y) x) under x := a, a pending suspension
        int lam = f.eval(f.term("(fun (x : Nat) (g ((fun (y : Nat) (f y)) x) x))"));
        int a = M2.argv(f, f.term("((fun (z : Nat) (g z z)) n)"));
        assertEquals(Layout.SUSP, Values.kind(m, a));
        int v = f.apply(lam, a);
        int s1 = f.run2slots(Values.b(m, v))[0];
        assertEquals(Layout.SUSP, Values.kind(m, s1));
        int first = f.force(s1);
        assertEquals(a, f.run2slots(Values.b(m, first))[0], "built while a was pending: NEU(f, [SUSP a])");
        int w = f.force(a);
        // re-evaluating s1 now builds NEU(f, [VAL a]): another record, the same value
        m.vh[(s1 << Layout.REC_SHIFT) + Layout.M_VAL] = 0;
        int second = f.force(s1);
        assertEquals(w, f.run2slots(Values.b(m, second))[0]);
        assertTrue(first != second, "ids differ");
        assertTrue(Eval.samePeekedSlow(m, first, second, 24), "equal up to PEEK");
        assertTrue(!Eval.samePeekedSlow(m, first, f.eval(f.term("(f n)")), 24));
        // and the memo check accepts the hit
        m.vh[(s1 << Layout.REC_SHIFT) + Layout.M_VAL] = first;
        m.debug = Debug.parseModes("memocheck");
        long checks = m.memoChecks;
        assertEquals(first, f.force(s1));
        assertEquals(checks + 1, m.memoChecks);
        assertTrue(n != 0);
    }

    /** SW_GATED_TABLING (staging step 1) makes fewer tabling records and the same values. */
    @Test
    void gatedTablingGivesTheSameValues() {
        Fixture f = M2.fresh(Op.HOST_DEPTH);
        List<String> plain = new ArrayList<>();
        List<String> gated = new ArrayList<>();
        for (int round = 0; round < 2; round++) {
            for (int d : new int[] {Op.HOST_DEPTH, 0}) {
                List<String> out = round == 0 ? plain : gated;
                out.clear();
                M2.reset(f, d, round == 0 ? "" : "gated,inert");
                f.fvar("n", "Nat");
                f.fvar("k", "Nat");
                f.fvar("h", "(-> Nat Nat)");
                for (String s : TERMS) {
                    out.add(M2.forced(f, f.eval(f.term(s))));
                }
            }
        }
        assertEquals(plain, gated);
    }

    /** With SW_GATED_TABLING a flat inert application (constant head, atoms as arguments) has no tabling record. */
    @Test
    void flatInertApplicationsAreNotTabledWhenTablingIsGated() {
        Fixture f = M2.fresh(Op.HOST_DEPTH);
        Machine m = f.m;
        f.fvar("n", "Nat");
        int flat = f.term("(g n (lit 2))");
        int nested = f.term("(g (g n (lit 2)) n0)");
        int v0 = f.eval(nested);
        assertTrue(M2.find(m, Layout.SUSP, flat) != 0 && M2.find(m, Layout.SUSP, nested) != 0, "v0 tables every application node");
        String shown = f.show(v0);
        M2.reset(f, Op.HOST_DEPTH, "gated");
        f.fvar("n", "Nat");
        int v = f.eval(nested);
        assertEquals(shown, f.show(v));
        assertEquals(0, M2.find(m, Layout.SUSP, flat), "the flat application is evaluated, not tabled");
        assertTrue(M2.find(m, Layout.SUSP, nested) != 0, "its parent keeps its record (a hash-consed overlay node is SHARED)");
        long ticks = m.ticks();
        assertEquals(v, f.eval(nested));
        assertEquals(ticks, m.ticks(), "the parent is a memo hit");
        assertEquals(f.run2slots(Values.b(m, v))[0], f.eval(flat), "re-evaluating the flat node gives the same NEU");
    }
}
