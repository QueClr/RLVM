package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@code lazyeval} (NOTES-M4 D26; {@code Op.SW_LAZY_EVAL}): in a lazy-delta step where exactly one side unfolds and
 * the other side is weak-head normal, the unfolding side is reduced by one WHNF instead of one DELTA + cheap CORE
 * per LSTEP. The answers must be those of the complete procedure (the switch only composes official reduction
 * steps of one side), the steps must be fewer, and a pair in which both sides unfold must not be touched.
 */
@Tag("fast")
class M4LazyEvalTest {

    private static final int T = Layout.T;
    private static final int F = Layout.F;

    private static Fixture fixture(int depth, boolean on) {
        Fixture f = Fixture.prelude();
        f.def("idn", "(-> Nat Nat)", "(fun (a : Nat) a)", Fixture.regular(3));
        f.def("idn2", "(-> Nat Nat)", "(fun (a : Nat) (idn a))", Fixture.regular(4));
        f.def("idn3", "(-> Nat Nat)", "(fun (a : Nat) (idn2 a))", Fixture.regular(5));
        f.def("pairOf", "(-> Nat ((@ Prod 0 0) Nat Bool))", "(fun (a : Nat) ((@ Prod.mk 0 0) Nat Bool a Bool.true))", Fixture.regular(2));
        f.seal();
        f.hostDepth(depth);
        if (on) {
            f.switches("lazyeval");
        }
        f.m.stats = true;
        f.fvar("vx", "Nat");
        return f;
    }

    private static int val(Fixture f, String text) {
        return f.eval(f.term(text));
    }

    @Test
    void oneWhnfInsteadOfOneStepPerUnfolding() {
        for (int depth : new int[] {Op.HOST_DEPTH, 0}) {
            Fixture on = fixture(depth, true);
            Fixture off = fixture(depth, false);
            // idn3 n0 unfolds three times; n0 is an axiom, weak-head normal by its flag
            for (Fixture f : new Fixture[] {on, off}) {
                Machine m = f.m;
                int a = val(f, "(idn3 n0)");
                int b = val(f, "n0");
                long lstep = m.calls[Conv.LSTEP];
                long whnf = m.calls[Op.WHNF];
                assertEquals(T, f.conv(a, b));
                assertEquals(f == on ? 1 : 3, m.calls[Conv.LSTEP] - lstep, "lazy-delta steps (D = " + depth + ")");
                assertEquals(f == on ? 1 : 0, m.calls[Op.WHNF] - whnf, "WHNF calls");
                // the other orientation, and against a variable-headed application and a constructor application
                assertEquals(T, f.conv(val(f, "(f n0)"), val(f, "(idn3 (f n0))")));
                assertEquals(T, f.conv(val(f, "(idn3 (g vx n0))"), val(f, "(g vx n0)")));
                assertEquals(F, f.conv(val(f, "(idn3 (g vx n0))"), val(f, "(g n0 vx)")));
                assertEquals(T, f.conv(val(f, "(pairOf (idn n0))"), val(f, "((@ Prod.mk 0 0) Nat Bool n0 Bool.true)")));
                assertEquals(F, f.conv(val(f, "(pairOf (idn n0))"), val(f, "((@ Prod.mk 0 0) Nat Bool n0 Bool.false)")));
            }
        }
    }

    @Test
    void theAnswersAreThoseOfTheCompleteProcedure() {
        for (int depth : new int[] {Op.HOST_DEPTH, 0}) {
            Fixture on = fixture(depth, true);
            Fixture off = fixture(depth, false);
            String[] terms = {"two", "twoAlias", "(lit 2)", "(lit 3)", "(Nat.add two two)", "(lit 4)", "(idn two)", "(idn3 two)",
                "(idn3 (lit 2))", "(Nat.succ (idn3 (lit 1)))", "(Nat.succ vx)", "(idn3 (Nat.succ vx))", "(Nat.add vx (lit 1))",
                "(f two)", "(f (lit 2))", "(idn2 (f two))", "(g n0 two)", "(idn (g n0 (lit 2)))", "n0", "(idn3 n0)", "vx", "(idn vx)",
                "(pairOf two)", "((@ Prod.mk 0 0) Nat Bool (lit 2) Bool.true)", "(fun (y : Nat) (idn3 y))", "(fun (y : Nat) y)", "idn",
                "p1", "p2", "u0", "u1"};
            for (String s : terms) {
                for (String t : terms) {
                    int want = off.conv(val(off, s), val(off, t));
                    assertEquals(want, on.conv(val(on, s), val(on, t)), s + " =?= " + t + " with lazyeval (D = " + depth + ")");
                }
            }
        }
    }

    @Test
    void aStepInWhichBothSidesUnfoldIsTheOfficialStep() {
        Fixture on = fixture(Op.HOST_DEPTH, true);
        Fixture off = fixture(Op.HOST_DEPTH, false);
        long[] steps = new long[2];
        int i = 0;
        for (Fixture f : new Fixture[] {on, off}) {
            Machine m = f.m;
            // idn3 (f n0) against idn2 (f two): both sides unfold (heights 5 and 4), never one side alone against a normal form
            // until the last step
            long lstep = m.calls[Conv.LSTEP];
            assertEquals(F, f.conv(val(f, "(idn3 (f n0))"), val(f, "(idn2 (f vx))")));
            steps[i++] = m.calls[Conv.LSTEP] - lstep;
        }
        assertTrue(steps[0] <= steps[1], "never more steps with the switch: " + steps[0] + " / " + steps[1]);
        assertTrue(steps[0] >= 2, "the two-sided steps are still taken one by one: " + steps[0]);
    }
}
