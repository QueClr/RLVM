package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@link Same}: bounded structural identity of two values (NOTES-M4 D28; experiment switch {@code m4a}), the
 * machine's counterpart of the official {@code is_equiv} at the start of {@code quick_is_def_eq}. Each row builds
 * two DIFFERENT records that stand for one expression (closures of different lambda terms, suspensions of
 * different argument terms, a variable bound to a closure against the closure's own term), or two records that are
 * convertible without being one expression, and checks (a) what the walk sees, (b) that CONV with the switch
 * answers T by it without instantiating, forcing or unfolding anything, and (c) that a walk which sees nothing
 * hands over to the complete procedure, whose answer is the answer.
 */
@Tag("fast")
class M4SameTest {

    private static final int T = Layout.T;
    private static final int F = Layout.F;

    private static Fixture fixture(int depth, boolean on) {
        Fixture f = Fixture.prelude();
        f.def("idn", "(-> Nat Nat)", "(fun (a : Nat) a)", Fixture.regular(3));
        f.seal();
        f.hostDepth(depth);
        if (on) {
            f.switches("m4a");
        }
        f.m.stats = true;
        return f;
    }

    private static int val(Fixture f, String text) {
        return f.eval(f.term(text));
    }

    /** {@code fun y => g y n0} twice: once as a closed lambda, once as the inner lambda of a function applied to n0. */
    private static int[] twoClosures(Fixture f, String slot) {
        int direct = val(f, "(fun (y : Nat) (g y " + slot + "))");
        int inner = f.apply(val(f, "(fun (w : Nat) (fun (y : Nat) (g y w)))"), val(f, slot));
        return new int[] {direct, inner};
    }

    /** {@code f ((fun z => z) n0)} twice: the argument is a suspension of two different terms (index 0 and index 1). */
    private static int[] twoSuspendedArguments(Fixture f) {
        int n0 = val(f, "n0");
        int a = f.apply(val(f, "(fun (w : Nat) (f ((fun (z : Nat) z) w)))"), n0);
        int b = f.apply(val(f, "(fun (w : Nat) (u : Nat) (f ((fun (z : Nat) z) w)))"), n0, n0);
        return new int[] {a, b};
    }

    /** {@code f ((fun z => g z z) n0)} twice: the function of the redex is a variable bound to a closure / the lambda itself. */
    private static int[] variableAgainstLambda(Fixture f) {
        int c = val(f, "(fun (z : Nat) (g z z))");
        int a = f.apply(val(f, "(fun (h : (-> Nat Nat)) (f (h n0)))"), c);
        int b = val(f, "(f ((fun (z : Nat) (g z z)) n0))");
        return new int[] {a, b};
    }

    @Test
    void differentRecordsOfOneExpressionAreSeen() {
        Fixture f = fixture(Op.HOST_DEPTH, false);
        Machine m = f.m;
        int[][] pairs = {twoClosures(f, "n0"), twoSuspendedArguments(f), variableAgainstLambda(f)};
        for (int[] p : pairs) {
            assertNotEquals(p[0], p[1], "the rows are two records: " + f.show(p[0]) + " / " + f.show(p[1]));
            assertTrue(Same.same(m, p[0], p[1]), f.show(p[0]) + " and " + f.show(p[1]) + " are one expression");
            assertTrue(Same.same(m, p[1], p[0]), "symmetric");
        }
        int v = val(f, "(g n0 two)");
        assertTrue(Same.same(m, v, v), "identity");
    }

    @Test
    void convertibleIsNotTheSame() {
        Fixture f = fixture(Op.HOST_DEPTH, false);
        Machine m = f.m;
        // slots that are convertible (two unfolds to the literal 2) but not one expression
        int a = twoClosures(f, "two")[1];
        int b = twoClosures(f, "(lit 2)")[1];
        assertFalse(Same.same(m, a, b), "a constant and its value are two expressions");
        assertEquals(T, f.conv(a, b), "the complete procedure answers");
        // different expressions that are not convertible either
        int c = twoClosures(f, "n0")[1];
        assertFalse(Same.same(m, a, c));
        assertEquals(F, f.conv(a, c));
        // a redex against its reduct: (fun z => z) n0 is not the expression n0
        int redex = f.apply(val(f, "(fun (w : Nat) (f ((fun (z : Nat) z) w)))"), val(f, "n0"));
        int reduct = val(f, "(f n0)");
        assertFalse(Same.same(m, redex, reduct), "beta is a reduction, not identity");
        assertEquals(T, f.conv(redex, reduct));
        // immediates and placeholders
        assertFalse(Same.same(m, val(f, "(lit 2)"), val(f, "(lit 3)")));
        assertFalse(Same.same(m, Layout.HOLE, val(f, "n0")));
    }

    /** {@code idn ((fun z => z) n0)} twice (a definition at the head): the argument is a suspension of two different terms. */
    private static int[] twoApplicationsOfADefinition(Fixture f) {
        int n0 = val(f, "n0");
        int a = f.apply(val(f, "(fun (w : Nat) (idn ((fun (z : Nat) z) w)))"), n0);
        int b = f.apply(val(f, "(fun (w : Nat) (u : Nat) (idn ((fun (z : Nat) z) w)))"), n0, n0);
        return new int[] {a, b};
    }

    @Test
    void convAnswersByIdentityWithoutInstantiatingOrForcing() {
        for (int depth : new int[] {Op.HOST_DEPTH, 0}) {
            Fixture on = fixture(depth, true);
            Fixture off = fixture(depth, false);
            for (Fixture f : new Fixture[] {on, off}) {
                Machine m = f.m;
                // closures, and applications of a head that may reduce: the walk answers for the pair itself.
                // Applications of a rigid head (the axiom f): c8 compares the arguments (one CARGS), and the walk
                // answers for the pair of suspended arguments.
                int[][] pairs = {twoClosures(f, "n0"), twoApplicationsOfADefinition(f), twoSuspendedArguments(f), variableAgainstLambda(f)};
                for (int i = 0; i < pairs.length; i++) {
                    int[] p = pairs[i];
                    long cbind = m.calls[Op.CBIND];
                    long apply = m.calls[Op.APPLY];
                    long force = m.calls[Op.FORCE];
                    long cargs = m.calls[Op.CARGS];
                    long delta = m.calls[Op.DELTA];
                    long seen = m.probes[3 * Op.B12 + T];
                    assertEquals(T, f.conv(p[0], p[1]), f.show(p[0]) + " =?= " + f.show(p[1]));
                    assertEquals(Layout.POS, f.pair(p[0], p[1]) & Layout.POS, "the pair is recorded");
                    if (f == on) {
                        assertEquals(1, m.probes[3 * Op.B12 + T] - seen, "answered by one identity walk (row " + i + ")");
                        assertEquals(0, m.calls[Op.CBIND] - cbind, "no binder was opened");
                        assertEquals(0, m.calls[Op.APPLY] - apply, "no body was instantiated");
                        assertEquals(0, m.calls[Op.FORCE] - force, "no suspension was forced");
                        assertEquals(0, m.calls[Op.DELTA] - delta, "nothing was unfolded");
                        assertEquals(i < 2 ? 0 : 1, m.calls[Op.CARGS] - cargs, "arguments compared only under a rigid head (row " + i + ")");
                    } else {
                        assertEquals(0, m.probes[3 * Op.B12 + T] - seen, "v0 has no identity walk");
                        assertTrue(m.calls[Op.CBIND] - cbind + m.calls[Op.CARGS] - cargs > 0, "v0 compares the parts");
                    }
                }
            }
        }
    }

    @Test
    void aWalkThatSeesNothingHandsOverToTheCompleteProcedure() {
        for (int depth : new int[] {Op.HOST_DEPTH, 0}) {
            Fixture on = fixture(depth, true);
            Fixture off = fixture(depth, false);
            String[] terms = {"two", "twoAlias", "(lit 2)", "(lit 3)", "(Nat.add two two)", "(idn two)", "(f two)", "(f (lit 2))",
                "(g n0 two)", "(g n0 (lit 2))", "(g two n0)", "(fun (y : Nat) (g y two))", "(fun (y : Nat) (g y (lit 2)))",
                "(fun (y : Nat) (g two y))", "(pi (y : Nat) Nat)", "(pi (y : Nat) (pi (z : Nat) Nat))", "p1", "p2", "u0", "u1"};
            for (String s : terms) {
                for (String t : terms) {
                    int want = off.conv(val(off, s), val(off, t));
                    assertEquals(want, on.conv(val(on, s), val(on, t)), s + " =?= " + t + " with the identity walk (D = " + depth + ")");
                }
            }
            // closures over these slots: the walk fails on the slots, CBIND decides
            for (String s : new String[] {"two", "twoAlias", "(lit 2)", "(lit 3)", "n0"}) {
                for (String t : new String[] {"two", "(lit 2)", "n0", "(idn two)"}) {
                    int want = off.conv(twoClosures(off, s)[1], twoClosures(off, t)[0]);
                    assertEquals(want, on.conv(twoClosures(on, s)[1], twoClosures(on, t)[0]), "closures over " + s + " / " + t);
                }
            }
        }
    }

    /** {@code fun y => g y a} as the inner lambda of a function of {@code n} parameters applied to a, then n - 1 times n0. */
    private static int deepClosure(Fixture f, int n, String a) {
        StringBuilder t = new StringBuilder();
        for (int i = 0; i < n; i++) {
            t.append("(fun (x").append(i).append(" : Nat) ");
        }
        t.append("(fun (y : Nat) (g y x0))");
        for (int i = 0; i < n; i++) {
            t.append(")");
        }
        int[] args = new int[n];
        args[0] = val(f, a);
        for (int i = 1; i < n; i++) {
            args[i] = val(f, "n0");
        }
        return f.apply(val(f, t.toString()), args);
    }

    /**
     * Far frames (ISA 2.4): the inner lambda reads a variable 70 or 72 binders out, so its frame has 63 near slots
     * and a REST chain. The two closures are different terms (index 70 / index 72) over environments of different
     * lengths; the variable they read is looked up in the REST chains.
     */
    @Test
    void farFramesAreWalked() {
        for (int depth : new int[] {Op.HOST_DEPTH, 0}) {
            Fixture f = fixture(depth, true);
            Machine m = f.m;
            int a = deepClosure(f, 70, "(f n0)");
            int b = deepClosure(f, 72, "(f n0)");
            int direct = val(f, "(fun (y : Nat) (g y (f n0)))");
            assertNotEquals(a, b);
            assertTrue((m.rs[Values.b(m, a)] & Layout.RUN_FAR) != 0, "the frame of the first closure is far: " + f.show(a));
            assertTrue(Same.same(m, a, b), "one expression under two far frames");
            assertTrue(Same.same(m, a, direct) && Same.same(m, direct, b), "and the closed lambda is the same expression");
            long cbind = m.calls[Op.CBIND];
            assertEquals(T, f.conv(a, b));
            assertEquals(0, m.calls[Op.CBIND] - cbind, "answered by the walk");
            // another value in the far slot: not the same; convertible (two / 2) or not (n0)
            int c = deepClosure(f, 70, "(f two)");
            int d = deepClosure(f, 72, "(f (lit 2))");
            assertFalse(Same.same(m, c, d));
            assertEquals(T, f.conv(c, d), "the complete procedure answers");
            assertFalse(Same.same(m, a, c));
            assertEquals(F, f.conv(a, c));
            // one term under two far frames that differ in a slot the term does not read: not seen (the REST run
            // holds every outer binding), and the complete procedure answers T
            int e = f.apply(val(f, farTerm(70)), farArgs(f, 70, "n0"));
            int g = f.apply(val(f, farTerm(70)), farArgs(f, 70, "two"));
            assertNotEquals(e, g);
            assertEquals(T, f.conv(e, g));
        }
    }

    /** A function of n parameters whose inner lambda reads the first one; the second one is not read. */
    private static String farTerm(int n) {
        StringBuilder t = new StringBuilder();
        for (int i = 0; i < n; i++) {
            t.append("(fun (x").append(i).append(" : Nat) ");
        }
        t.append("(fun (y : Nat) (g y x0))");
        for (int i = 0; i < n; i++) {
            t.append(")");
        }
        return t.toString();
    }

    private static int[] farArgs(Fixture f, int n, String second) {
        int[] args = new int[n];
        args[0] = val(f, "(f n0)");
        args[1] = val(f, second);
        for (int i = 2; i < n; i++) {
            args[i] = val(f, "n0");
        }
        return args;
    }

    @Test
    void theWalkIsBounded() {
        Fixture f = fixture(Op.HOST_DEPTH, true);
        Machine m = f.m;
        // one expression, but larger than the fuel of a query: a nest of 2 * FUEL applications in the slot
        StringBuilder a = new StringBuilder("n0");
        for (int i = 0; i < 2 * Same.FUEL; i++) {
            a.insert(0, "(g ((fun (z : Nat) z) n0) ").append(")");
        }
        int x = f.apply(val(f, "(fun (w : Nat) (f ((fun (z : Nat) z) w)))"), val(f, a.toString()));
        int y = f.apply(val(f, "(fun (w : Nat) (u : Nat) (f ((fun (z : Nat) z) w)))"), val(f, a.toString()), val(f, "n0"));
        // the two big slots are one record here, so the walk ends at once: still inside the fuel
        assertTrue(Same.same(m, x, y));
        assertTrue(m.x4.sameFuel > Same.FUEL - 32, "identical parts are not walked: " + (Same.FUEL - m.x4.sameFuel) + " nodes");
        // after the waste bound of a declaration no further walk is started: CONV still answers
        int[] p = twoClosures(f, "n0");
        m.x4.sameWaste = Conv.Ext.SAME_WASTE_MAX;
        long events = m.probes[3 * Op.B12 + T] + m.probes[3 * Op.B12 + Layout.U];
        long cbind = m.calls[Op.CBIND];
        assertEquals(T, f.conv(p[0], p[1]));
        assertEquals(events, m.probes[3 * Op.B12 + T] + m.probes[3 * Op.B12 + Layout.U], "no walk after the bound");
        assertTrue(m.calls[Op.CBIND] - cbind > 0, "the complete procedure compared the binders");
    }
}
