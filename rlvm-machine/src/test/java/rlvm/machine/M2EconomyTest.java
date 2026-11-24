package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import rlvm.api.MachineErrors;

/**
 * The evaluation economy of family EV (NOTES-M2 section 6): the argument picker and the closed-term memo, which
 * change no result and act in v0; the depth rule of inert nests under {@code SW_GATED_TABLING}; untabled bodies and
 * the projection selector ({@code SW_M2A}); the APPLY memo ({@code SW_M2B}).
 */
@Tag("fast")
class M2EconomyTest {

    private static final int[] DEPTHS = {Op.HOST_DEPTH, 0, 1};
    private static final String NEST = "rlvm-machine/src/test/resources/m2/depnest-open-300.ndjson";

    private static Fixture fresh(int d, String switches, int xsw) {
        Fixture f = M2.fresh(d);
        M2.reset(f, d, switches);
        f.m.x2.xsw = xsw;
        return f;
    }

    /** A body that is one of the bound variables is its argument, PEEKed; a HOLE argument that is read is fault(E_HOLE). */
    @Test
    void anArgumentPickerIsAnsweredFromTheArguments() {
        for (int d : DEPTHS) {
            Fixture f = fresh(d, "", 0);
            Machine m = f.m;
            int n = f.fvar("n", "Nat");
            int k = f.fvar("k", "Nat");
            int first = f.eval(f.term("(fun (a : Nat) (fun (b : Nat) a))"));
            int second = f.eval(f.term("(fun (a : Nat) (fun (b : Nat) b))"));
            int records = m.vp;
            int runWords = m.rsp;
            long ticks = m.ticks();
            assertEquals(n, f.apply(first, n, k));
            assertEquals(k, f.apply(second, n, k));
            assertEquals(k, f.apply(second, Layout.HOLE, k), "an unread binder may be HOLE");
            assertEquals(2 + 1, m.ticks() - ticks, "one tick per APPLY, none for the body");
            assertEquals(records, m.vp, "no record");
            assertEquals(runWords, m.rsp, "no run");
            assertEquals(MachineErrors.E_HOLE, M2.failure(() -> f.apply(first, Layout.HOLE, k)).code);
            // over-application: the picked argument is applied to the rest
            int fn = f.eval(f.term("f"));
            assertEquals(f.eval(f.term("(f k)")), f.apply(first, fn, n, k));
            // a suspension whose value is known is replaced by the value (D8)
            int susp = M2.argv(f, f.term("((fun (x : Nat) (g x x)) n)"));
            assertEquals(Layout.SUSP, Values.kind(m, susp));
            assertEquals(susp, f.apply(first, susp, k), "pending: the suspension itself");
            int val = f.force(susp);
            assertEquals(val, f.apply(first, susp, k), "known: its value");
            // the walk gives the same answers (a debug hook switches the picker off)
            m.debug = Op.DBG_SCOPEAUDIT;
            assertEquals(n, f.apply(first, n, k));
            assertEquals(val, f.apply(first, susp, k));
            m.debug = 0;
            assertTrue(M2.settled(f));
        }
    }

    /** With SW_M2A the body of a beta step has no tabling record; the value is the one v0 computes. */
    @Test
    void bodiesAreNotTabledWhenTablingIsGated() {
        for (int d : DEPTHS) {
            Fixture f = fresh(d, "", 0);
            Machine m = f.m;
            int lam = f.term("(fun (x : Nat) (g (f x) (f (f x))))");
            int body = m.terms.b(lam);
            int n = f.fvar("n", "Nat");
            int v0 = f.apply(f.eval(lam), n);
            String shown = f.show(v0);
            assertTrue(M2.find(m, Layout.SUSP, body) != 0, "v0 tables the body");
            f = fresh(d, "gated,m2a", 0);
            m = f.m;
            n = f.fvar("n", "Nat");
            int clo = f.eval(lam);
            int v = f.apply(clo, n);
            assertEquals(shown, f.show(v));
            assertEquals(0, M2.find(m, Layout.SUSP, body), "no tabling record for the body");
            long ticks = m.ticks();
            assertEquals(v, f.apply(clo, n), "the same value again");
            assertTrue(m.ticks() - ticks >= 2, "the second application evaluates the body again (APPLY + EVAL)");
            // without the switch the body is tabled as before
            f = fresh(d, "gated", 0);
            m = f.m;
            n = f.fvar("n", "Nat");
            assertEquals(shown, f.show(f.apply(f.eval(lam), n)));
            assertTrue(M2.find(m, Layout.SUSP, body) != 0);
            assertTrue(M2.settled(f));
        }
    }

    /** SW_M2A: a body that is a projection of a bound variable is MKPROJ of the argument: same values, no EVAL tick. */
    @Test
    void aProjectionOfAnArgumentIsASelector() {
        for (int d : DEPTHS) {
            List<String> plain = new ArrayList<>();
            List<String> selected = new ArrayList<>();
            long[] ticks = new long[2];
            for (int round = 0; round < 2; round++) {
                Fixture f = fresh(d, round == 0 ? "gated" : "gated,m2a", 0);
                Machine m = f.m;
                List<String> out = round == 0 ? plain : selected;
                int n = f.fvar("n", "Nat");
                int p = f.fvar("p", "((@ Prod 0 0) Nat Bool)");
                int fst = f.eval(f.term("(fun (q : ((@ Prod 0 0) Nat Bool)) (proj Prod 0 q))"));
                int snd2 = f.eval(f.term("(fun (a : Nat) (fun (q : ((@ Prod 0 0) Nat Bool)) (proj Prod 1 q)))"));
                int pair = f.eval(f.term("((@ Prod.mk 0 0) Nat Bool (f n) Bool.true)"));
                long t0 = m.ticks();
                out.add(f.show(f.apply(fst, pair)));
                out.add(f.show(f.apply(fst, p)));
                out.add(f.show(f.apply(snd2, n, pair)));
                out.add(f.show(f.apply(snd2, Layout.HOLE, p)));
                ticks[round] = m.ticks() - t0;
                assertEquals(MachineErrors.E_HOLE, M2.failure(() -> f.apply(fst, Layout.HOLE)).code);
                assertTrue(M2.settled(f));
            }
            assertEquals(plain, selected);
            assertEquals(4, selected.size());
            assertEquals("Bool.true", selected.get(2));
            assertTrue(selected.get(1).startsWith("(proj Prod 0 ") && selected.get(3).startsWith("(proj Prod 1 "), selected.toString());
            assertEquals(8, ticks[0], "APPLY and EVAL of the projection node");
            assertEquals(4, ticks[1], "APPLY only");
        }
    }

    /** SW_M2B: the second application of a closure to the same read arguments is answered by the memo, under every D. */
    @Test
    void theApplyMemoAnswersTheSecondApplication() {
        for (int d : DEPTHS) {
            Fixture f = fresh(d, "gated,m2a,m2b", 0);
            Machine m = f.m;
            int n = f.fvar("n", "Nat");
            int k = f.fvar("k", "Nat");
            int clo = f.eval(f.term("(fun (x : Nat) (fun (unused : Nat) (g (f x) (f (f x)))))"));
            int v = f.apply(clo, n, k);
            assertEquals(f.eval(f.term("(g (f n) (f (f n)))")), v);
            long ticks = m.ticks();
            int records = m.vp;
            assertEquals(v, f.apply(clo, n, k));
            assertEquals(v, f.apply(clo, n, n), "the unread argument is not part of the key");
            assertEquals(v, f.apply(clo, n, Layout.HOLE));
            assertEquals(3, m.ticks() - ticks, "one tick per APPLY; the bodies are not evaluated");
            assertEquals(records, m.vp);
            assertNotEquals(v, f.apply(clo, k, k), "another read argument is another key");
            assertEquals(f.eval(f.term("(g (f k) (f (f k)))")), f.apply(clo, k, n));
            // partial application and the application of its result are steps of their own
            int partial = f.apply(clo, n);
            assertEquals(Layout.LAM, Values.kind(m, partial));
            assertEquals(v, f.apply(partial, k));
            // the memo does not survive the session
            m.reset();
            m.beginDecl(Integer.MAX_VALUE, -1, Op.FUEL_UNLIMITED, Long.MAX_VALUE);
            f.closeContext();
            int n2 = f.fvar("n", "Nat");
            int clo2 = f.eval(f.term("(fun (x : Nat) (fun (unused : Nat) (g (f x) (f (f x)))))"));
            long t2 = m.ticks();
            int again = f.apply(clo2, n2, n2);
            assertTrue(m.ticks() - t2 > 1, "evaluated again in the new session");
            assertEquals(f.eval(f.term("(g (f n) (f (f n)))")), again);
            assertTrue(M2.settled(f));
        }
    }

    /** SW_M2B: a session whose beta steps do not repeat stops paying for the lookups (the governor), and the values stay the same. */
    @Test
    void theApplyMemoIsPassedByWhenStepsDoNotRepeat() {
        Fixture f = fresh(Op.HOST_DEPTH, "gated,m2a,m2b", 0);
        Machine m = f.m;
        int clo = f.eval(f.term("(fun (x : Nat) (g (f x) (f (f x))))"));
        assertEquals(0, m.x2.amOff);
        int first = f.apply(clo, Layout.imm(0));
        for (int i = 1; i < Eval.AM_WINDOW; i++) {
            f.apply(clo, Layout.imm(i));
        }
        assertEquals(Eval.AM_PAUSE, m.x2.amOff, "no hit in a window of " + Eval.AM_WINDOW + " lookups: the memo is passed by");
        long ticks = m.ticks();
        assertEquals(first, f.apply(clo, Layout.imm(0)), "the same value, by evaluation");
        assertTrue(m.ticks() - ticks > 1);
        assertEquals(Eval.AM_PAUSE - 1, m.x2.amOff);
        m.reset();
        assertEquals(0, m.x2.amOff, "a new session starts with the memo");
    }

    /** The closed-term memo changes no id, record, run or tick: with it and without it, tabled and gated, under every D. */
    @Test
    void theClosedMemoChangesNothing() {
        String[] terms = {
            "(g (f n0) (f (f two)))", "(f (g n0 n0))", "((fun (x : Nat) (g x x)) (f n0))", "(proj Prod 0 ((@ Prod.mk 0 0) Nat Bool (f n0) Bool.true))",
            "(let (a : Nat := (f n0)) (g a a))", "(g (f n0) (f (f two)))", "(f (f (f (f (f (f n0))))))", "((@ id 1) Nat (f n0))",
            "(g ((fun (x : Nat) (g x x)) (f n0)) (f (g n0 n0)))", "(f (f (f (f (f (f n0))))))",
        };
        for (String sw : new String[] {"", "gated", "gated,m2a,m2b"}) {
            for (int d : DEPTHS) {
                List<Integer> with = new ArrayList<>();
                List<Integer> without = new ArrayList<>();
                for (int round = 0; round < 2; round++) {
                    Fixture f = fresh(d, sw, round == 0 ? 0 : Eval.X_NOCM);
                    Machine m = f.m;
                    long t0 = m.ticks();
                    List<Integer> out = round == 0 ? with : without;
                    for (int pass = 0; pass < 2; pass++) {
                        for (String s : terms) {
                            int v = f.eval(f.term(s));
                            out.add(v);
                            out.add(f.force(v));
                            out.add(M2.argv(f, f.term(s)));
                        }
                    }
                    out.add(m.vp - m.vbase);
                    out.add(m.rsp - m.rbase);
                    out.add(java.util.Arrays.hashCode(M2.records(m)));
                    out.add(java.util.Arrays.hashCode(M2.runs(m)));
                    if (sw.isEmpty()) {
                        out.add((int) (m.ticks() - t0)); // tabled: a memo hit is a VAL hit. Gated: the memo saves re-evaluations
                    }
                    assertTrue(M2.settled(f));
                }
                assertEquals(with, without, "switches '" + sw + "', D = " + d);
            }
        }
    }

    /** The memo answers a closed untabled node the second time (gated: a flat application is not tabled). */
    @Test
    void theClosedMemoAnswersUntabledClosedNodes() {
        Fixture f = fresh(Op.HOST_DEPTH, "gated", 0);
        Machine m = f.m;
        int flat = f.term("(g n0 two)");
        int v = f.eval(flat);
        assertEquals(0, M2.find(m, Layout.SUSP, flat), "flat: no tabling record");
        long ticks = m.ticks();
        assertEquals(v, f.eval(flat));
        assertEquals(v, M2.argv(f, flat));
        assertEquals(ticks, m.ticks(), "answered by the memo: no tick");
        m.x2.xsw |= Eval.X_NOCM;
        assertEquals(v, f.eval(flat));
        assertEquals(ticks + 1, m.ticks(), "without the memo the node is evaluated again");
        // a term with a level parameter is in the memo only under ls' = 0
        m.x2.xsw &= ~Eval.X_NOCM;
        int poly = f.term("((@ c1 u v) n0)");
        int ls12 = Levels.substOf(m, Values.mkConst(m, f.cid("c1"), f.levels("1", "2")));
        int ls21 = Levels.substOf(m, Values.mkConst(m, f.cid("c1"), f.levels("2", "1")));
        assertTrue(ls12 != 0 && ls21 != 0 && ls12 != ls21);
        int a = f.eval(poly, ls12);
        int b = f.eval(poly, ls21);
        int c = f.eval(poly);
        assertNotEquals(a, b);
        assertNotEquals(a, c);
        assertEquals(a, f.eval(poly, ls12));
        assertEquals(b, f.eval(poly, ls21));
        assertEquals(c, f.eval(poly));
    }

    /**
     * SW_GATED_TABLING, the depth rule: a dependent nest of 300 unshared applications under a binder. INFER evaluates the argument of every
     * level; without the rule each of these evaluations walks the whole untabled nest below it (45,000 node visits),
     * with it at most {@code depthK} levels.
     */
    @Test
    void aDeepUntabledNestIsWalkedALinearNumberOfTimes() throws IOException {
        assumeTrue(Fixture.exists(NEST));
        long[] ticks = new long[3];
        for (int round = 0; round < 3; round++) {
            Fixture f = Fixture.load(NEST);
            try {
                f.switches(round == 0 ? "" : "gated");
                f.m.x2.xsw = round == 2 ? Eval.X_NODEPTH : 0;
                Fixture.DiffReport r = f.diffAgainstReference();
                assertTrue(r.clean(), String.valueOf(r.lines));
                assertEquals(5, r.agree);
                ticks[round] = f.m.ticks();
            } finally {
                f.close();
            }
        }
        assertTrue(ticks[1] < 2 * ticks[0], "gated with the depth rule: " + ticks[1] + " ticks, v0: " + ticks[0]);
        assertTrue(ticks[2] > 10 * ticks[0], "without the rule the walk is quadratic: " + ticks[2] + " ticks");
    }
}
