package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Random;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import rlvm.api.MachineErrors;
import rlvm.nat.Nat;

/**
 * Work package M3: nests of the binary Nat functions evaluated on handles (NOTES-M3 D16; {@code NatRed.nestRootSlow}).
 * In a session that holds many big numbers the fold of the WHNF loop computes a whole literal nest with
 * {@code rlvm.nat} in a scratch store and keeps only the root's result. The rows compare that path
 * ({@code nestMinBytes = 0}: always) with the judgment ({@code nestMinBytes = MAX}: never) on the same terms:
 * same number, same error, same normal form when the nest is not literal; and they show what it is for (the
 * session's number store stays small).
 */
@Tag("fast")
class M3NestTest {

    static Fixture f;
    static boolean ready;

    @BeforeAll
    static void setUp() {
        f = Fixture.prelude();
        f.def("zeroDef", "", "Nat", "Nat.zero", Fixture.regular(1));
        f.def("big", "", "Nat", "(Nat.pow (lit 3) (lit 300))", Fixture.regular(1));
        f.seal();
        try {
            f.eval(f.term("two"));
            ready = true;
        } catch (UnsupportedOperationException e) {
            ready = false; // Eval (M2) is still a stub in this tree
        }
        f.m.reset();
    }

    /** WHNF of the term on a freshly reset machine, with the nest path always on or never on. */
    private static String whnf(String sexp, boolean nests) {
        int t = f.term(sexp);
        f.m.reset();
        f.m.x3.nestMinBytes = nests ? 0 : Long.MAX_VALUE;
        try {
            int v = f.whnf(f.eval(t));
            long h = NatRed.litExt(f.m, v);
            boolean lit = v < 0 || Values.kind(f.m, v) == Layout.NBIG;
            return lit ? Nat.toDecimalString(f.m.nats, h) : f.show(v);
        } catch (Fixture.MachineFailure e) {
            return "fails with " + MachineErrors.name(e.code);
        } finally {
            f.m.x3.nestMinBytes = 16L << 20;
        }
    }

    private static void same(String sexp) {
        assertEquals(whnf(sexp, false), whnf(sexp, true), "the nest on handles and the judgment differ on " + sexp);
    }

    private static final String[] OPS = {"Nat.add", "Nat.sub", "Nat.mul", "Nat.div", "Nat.mod", "Nat.gcd", "Nat.land", "Nat.lor", "Nat.xor",
        "Nat.shiftLeft", "Nat.shiftRight", "Nat.pow"};
    private static final String[] LEAVES = {"(lit 0)", "(lit 1)", "(lit 2)", "(lit 3)", "(lit 7)", "(lit 64)", "(lit 100)", "(lit 2147483647)",
        "(lit 2147483648)", "(lit 9223372036854775807)", "(lit 9223372036854775808)", "(lit 340282366920938463463374607431768211456)",
        "(lit 123456789012345678901234567890123456789012345678901234567890)", "Nat.zero", "zeroDef", "two", "big", "(Nat.succ zeroDef)"};

    private static String nest(Random rnd, int depth, String[] shared) {
        int k = rnd.nextInt(10);
        if (depth == 0 || k < 2) {
            return LEAVES[rnd.nextInt(LEAVES.length)];
        }
        if (k == 2 && shared[0] != null) {
            return shared[0]; // a shared sub-nest: one value, several occurrences
        }
        String op = OPS[rnd.nextInt(OPS.length)];
        String a = nest(rnd, depth - 1, shared);
        // keep exponents and shift counts small, so that the rows compute numbers instead of only testing the guards
        String b = op.equals("Nat.pow") || op.equals("Nat.shiftLeft") ? "(lit " + rnd.nextInt(70) + ")" : nest(rnd, depth - 1, shared);
        String s = "(" + op + " " + a + " " + b + ")";
        if (shared[0] == null || rnd.nextInt(4) == 0) {
            shared[0] = s;
        }
        return s;
    }

    @Test
    void randomLiteralNestsGiveTheNumberOfTheJudgment() {
        assumeTrue(ready, "Eval (M2) is not published yet");
        Random rnd = new Random(609961);
        long before = f.m.x3.nestRoots;
        for (int i = 0; i < 120; i++) {
            same(nest(rnd, 1 + rnd.nextInt(5), new String[1]));
        }
        assertTrue(f.m.x3.nestRoots - before > 60, "the nest path answered only " + (f.m.x3.nestRoots - before) + " roots");
    }

    @Test
    void predicatesGuardsAndErrors() {
        assumeTrue(ready, "Eval (M2) is not published yet");
        // a predicate at the root; a predicate inside is not a literal nest (its value is no number)
        same("(Nat.beq (Nat.mul big big) (Nat.pow (lit 3) (lit 600)))");
        assertEquals("Bool.true", whnf("(Nat.beq (Nat.mul big big) (Nat.pow (lit 3) (lit 600)))", true));
        same("(Nat.ble (Nat.add big (lit 1)) big)");
        same("(Nat.add (Nat.beq (lit 1) (lit 1)) (lit 2))");
        // the official guards inside a nest: the declaration is rejected on either path (R02 7.6)
        assertEquals("fails with E_NATSIZE", whnf("(Nat.add (lit 1) (Nat.pow (lit 2) (lit 4294967296)))", true));
        assertEquals("fails with E_NATSIZE", whnf("(Nat.mul (Nat.shiftLeft (lit 1) (lit 1073741760)) (lit 0))", true));
        same("(Nat.add (lit 1) (Nat.pow (lit 2) (lit 4294967296)))");
        same("(Nat.sub (Nat.shiftLeft (lit 0) (lit 99999999999999999999)) (lit 1))");
        // not a literal nest: an operand that is stuck, or that reduces by something else than arithmetic
        same("(Nat.add (Nat.mul big n0) (lit 2))");
        same("(Nat.mul (Nat.add (lit 1) (lit 2)) (f (lit 2)))");
        same("(Nat.add (Nat.mul big (lit 2)) ((@ id 1) Nat (lit 5)))");
        same("(Nat.mul four (Nat.add four (lit 1)))");
    }

    @Test
    void innerApplicationsKeepNoMemoAndCanBeAskedAlone() {
        assumeTrue(ready, "Eval (M2) is not published yet");
        f.m.reset();
        f.m.x3.nestMinBytes = 0;
        try {
            f.whnf(f.eval(f.term("big"))); // the constant gets its literal memo: from now on it is a literal operand
            int inner = f.eval(f.term("(Nat.mul big big)"));
            int root = f.eval(f.term("(Nat.add (Nat.mul big big) (lit 1))"));
            int r = f.whnf(root);
            assertEquals(Layout.NBIG, Values.kind(f.m, r));
            assertEquals(r, Values.memo(f.m, root, 0)); // the root is memoised as usual
            assertEquals(0, Values.memo(f.m, inner, 0), "an inner application of a nest got a WHNF memo");
            int ri = f.whnf(inner); // asked alone it is a root of its own
            assertEquals(Nat.toDecimalString(f.m.nats, NatRed.litExt(f.m, r)),
                    Nat.toDecimalString(f.m.nats, Nat.add(f.m.nats, NatRed.litExt(f.m, ri), 1)));
            assertTrue(f.m.idle());
        } finally {
            f.m.x3.nestMinBytes = 16L << 20;
        }
    }

    /** What it is for: the intermediates of a chain of big operations do not stay in the session. */
    @Test
    void intermediatesDoNotStayInTheSession() {
        assumeTrue(ready, "Eval (M2) is not published yet");
        long[] numbers = new long[2];
        String[] last = new String[2];
        for (int pass = 0; pass < 2; pass++) {
            f.m.reset();
            f.m.x3.nestMinBytes = pass == 0 ? Long.MAX_VALUE : 0;
            f.m.x3.scratchLimit = 1L << 20;
            try {
                for (int i = 1; i <= 60; i++) {
                    // 5 intermediates of about 12,500 bytes each per root, one small result
                    String x = "(Nat.shiftLeft (lit " + (i + 1) + ") (lit 100000))";
                    String t = "(Nat.mod (Nat.add (Nat.mul (Nat.xor " + x + " (Nat.shiftRight " + x + " (lit 7))) (lit 3)) (Nat.lor " + x + " (lit 5))) (lit 1000003))";
                    last[pass] = f.show(f.whnf(f.eval(f.term(t))));
                }
                numbers[pass] = f.m.nats.bytesUsed();
            } finally {
                f.m.x3.nestMinBytes = 16L << 20;
                f.m.x3.scratchLimit = 64L << 20;
            }
        }
        assertEquals(last[0], last[1]);
        assertTrue(numbers[0] > 60 * 5 * 12_000L, "the judgment keeps every intermediate: " + numbers[0] + " bytes");
        assertEquals(0, numbers[1], "the nest path left numbers in the session's store");
        assertTrue(f.m.x3.bytes() <= (1L << 20) + 200_000, "the scratch store is bounded: " + f.m.x3.bytes());
        assertTrue(f.m.x3.scratchResets > 0);
    }

    @Test
    void theNestPathIsOffInASessionOfSmallNumbers() {
        assumeTrue(ready, "Eval (M2) is not published yet");
        f.m.reset();
        long roots = f.m.x3.nestRoots;
        long falls = f.m.x3.nestFallbacks;
        assertEquals("22", f.show(f.whnf(f.eval(f.term("(Nat.add (Nat.mul (lit 2) (lit 3)) (Nat.pow (lit 2) (lit 4)))")))));
        assertEquals(roots, f.m.x3.nestRoots);
        assertEquals(falls, f.m.x3.nestFallbacks);
        int inner = f.eval(f.term("(Nat.mul (lit 2) (lit 3))"));
        assertNotEquals(0, Values.memo(f.m, inner, 0)); // the judgment memoises every application
    }

    @Test
    void strictModeNeverTakesTheNestPath() {
        assumeTrue(ready, "Eval (M2) is not published yet");
        f.m.reset();
        f.m.x3.nestMinBytes = 0;
        f.m.strict = true;
        try {
            long roots = f.m.x3.nestRoots + f.m.x3.nestFallbacks;
            assertEquals(Layout.NBIG, Values.kind(f.m, f.whnf(f.eval(f.term("(Nat.add (Nat.mul big big) (lit 1))")))));
            assertEquals(roots, f.m.x3.nestRoots + f.m.x3.nestFallbacks);
        } finally {
            f.m.strict = false;
            f.m.x3.nestMinBytes = 16L << 20;
        }
    }

    @Test
    void ticksDoNotDependOnTheDepthBound() {
        assumeTrue(ready, "Eval (M2) is not published yet");
        String t = "(Nat.xor (Nat.lor (Nat.mul big big) (Nat.add big two)) (Nat.shiftRight (Nat.mul big big) (lit 3)))";
        long[] ticks = new long[2];
        String[] out = new String[2];
        int[] depths = {Op.HOST_DEPTH, 0};
        for (int i = 0; i < 2; i++) {
            int term = f.term(t);
            f.m.reset();
            f.hostDepth(depths[i]);
            f.m.x3.nestMinBytes = 0;
            try {
                long t0 = f.m.ticks();
                out[i] = f.show(f.whnf(f.eval(term)));
                ticks[i] = f.m.ticks() - t0;
            } finally {
                f.m.x3.nestMinBytes = 16L << 20;
                f.hostDepth(Op.HOST_DEPTH);
            }
        }
        assertEquals(out[0], out[1]);
        assertEquals(ticks[0], ticks[1]);
    }
}
