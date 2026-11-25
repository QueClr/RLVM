package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import rlvm.api.EngineConfig;
import rlvm.api.MachineErrors;
import rlvm.nat.Nat;
import rlvm.ref.RefKernel;
import rlvm.store.Kind;

/**
 * Family RD, per-instruction rows of ISA 4.3 against the official rules of R02 section 7 (work package M3).
 *
 * <p>Every row runs twice on a machine that was reset in between: under the default host depth bound (64)
 * and under bound 0, where every non-leaf call of a judgment is a suspension, so that every {@code sigN}
 * call site and every {@code resume} case of the family runs (*agentic-note*: rep-stepper-convention, H12). The two runs
 * must give the same value id, the same printed value and the same number of ticks.
 *
 * <p>The rows need EVAL / APPLY (M2); the K and structure-eta rows also CONV (M4) and VTYPE / ISPROP
 * (M5). While a callee is still a stub in the tree that runs the test, the rows that need it are skipped
 * (an assumption, not a failure).
 */
@Tag("fast")
class M3ReduceTest {

    static Fixture f;
    static boolean haveEval;
    static boolean haveConvInfer;

    @BeforeAll
    static void setUp() {
        f = Fixture.prelude();
        f.def("dbl", "", "(-> Nat Nat)", "(fun (x : Nat) (Nat.add x x))", Fixture.regular(2));
        f.def("zeroDef", "", "Nat", "Nat.zero", Fixture.regular(1));
        f.inductive("Wrap", "Wrap.mk : (-> (-> Nat Nat) Wrap)");
        f.def("wrapF", "", "Wrap", "(Wrap.mk f)", Fixture.regular(1));
        f.axiom("q0", "", "((@ Quot 1) Nat r)");
        f.axiom("g2", "", "(-> Nat Nat Nat)");
        f.seal();
        haveEval = works(() -> f.eval(f.term("two")));
        haveConvInfer = haveEval && works(() -> f.conv(f.eval(f.term("two")), f.eval(f.term("four"))))
                && works(() -> f.vtype(f.eval(f.term("n0")))) && works(() -> f.isProp(f.eval(f.term("Nat"))));
        f.m.reset();
    }

    private static boolean works(Runnable r) {
        try {
            r.run();
            return true;
        } catch (UnsupportedOperationException e) {
            return false; // a callee of another package is still a stub
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    /** Runs {@code judgment} on the value of {@code sexp} under D = 64 and D = 0 (fresh machine each); returns the result. */
    private static int both(String sexp, IntUnaryOperator judgment) {
        assumeTrue(haveEval, "Eval (M2) is not published yet");
        int t = f.term(sexp);
        f.m.reset();
        f.hostDepth(Op.HOST_DEPTH);
        long t0 = f.m.ticks();
        int r1 = judgment.applyAsInt(f.eval(t));
        long ticks1 = f.m.ticks() - t0;
        String s1 = f.show(r1);
        assertTrue(f.m.idle(), "machine stacks not restored after " + sexp);
        f.m.reset();
        f.hostDepth(0);
        t0 = f.m.ticks();
        int r0 = judgment.applyAsInt(f.eval(t));
        long ticks0 = f.m.ticks() - t0;
        assertTrue(f.m.idle(), "machine stacks not restored after " + sexp + " (D = 0)");
        assertEquals(s1, f.show(r0), "D = 0 and D = 64 differ on " + sexp);
        assertEquals(r1, r0, "value ids differ between D = 0 and D = 64 on " + sexp);
        assertEquals(ticks1, ticks0, "ticks differ between D = 0 and D = 64 on " + sexp);
        f.hostDepth(Op.HOST_DEPTH);
        return r0;
    }

    private static String whnf(String sexp) {
        return f.show(both(sexp, v -> f.whnf(v)));
    }

    /** WHNF of a term whose normal form is a Nat literal, as a decimal string. */
    private static String nat(String sexp) {
        int v = both(sexp, x -> f.whnf(x));
        long h = NatRed.litExt(f.m, v);
        assertTrue(v < 0 || Values.kind(f.m, v) == Layout.NBIG, "not a Nat literal: " + f.show(v) + " for " + sexp);
        return Nat.toDecimalString(f.m.nats, h);
    }

    /** True if WHNF returns the very value it was given (the ORIGINAL cell). */
    private static boolean whnfIsSelf(String sexp) {
        int[] in = new int[1];
        int out = both(sexp, v -> {
            in[0] = v;
            return f.whnf(v);
        });
        return out == in[0];
    }

    private static void failsBoth(int code, String sexp) {
        assumeTrue(haveEval, "Eval (M2) is not published yet");
        int t = f.term(sexp);
        for (int depth : new int[] {Op.HOST_DEPTH, 0}) {
            f.m.reset();
            f.hostDepth(depth);
            f.expectFail(code, () -> f.whnf(f.eval(t)));
            assertTrue(f.m.idle());
        }
        f.hostDepth(Op.HOST_DEPTH);
    }

    private static final String NATREC = "((@ Nat.rec 1) (fun (x : Nat) Nat)";
    private static final String STEP = "(fun (x : Nat) (ih : Nat) (Nat.succ ih))";

    // ---- DELTA (ISA 4.3.2; R02 7.6 is_delta / unfold_definition) --------------------------------------------

    @Test
    void deltaUnfoldsDefinitionsAndTheoremsOnly() {
        assertEquals("2", whnf("two")); // a definition
        assertEquals("4", whnf("four")); // Nat.add two two: reduce_nat after whnf of both arguments
        assertEquals("2", whnf("twoAlias"));
        assertEquals("Nat", whnf("NatAlias"));
        assertEquals("p1", whnf("pThm")); // R02 section 10 row 25: theorems unfold in whnf
        assertEquals("opaqueTwo", whnf("opaqueTwo")); // an opaque constant never unfolds
        assertEquals("n0", whnf("n0")); // an axiom
        assertEquals("n0", whnf("((@ id 1) Nat n0)")); // delta + beta
        assertEquals("(f n0)", whnf("((@ id 1) (-> Nat Nat) f n0)")); // extra argument re-applied
        assertEquals("(f n0)", whnf("(f n0)"));
        assertTrue(whnfIsSelf("(f n0)"));
        assertTrue(whnfIsSelf("opaqueTwo"));
    }

    @Test
    void deltaJudgment() {
        assertEquals(Layout.imm(2), both("two", v -> f.delta(v)));
        assertEquals(Layout.NONE, both("opaqueTwo", v -> f.delta(v)));
        assertEquals(Layout.NONE, both("(f n0)", v -> f.delta(v)));
        assertEquals(Layout.NONE, both("Nat.zero", v -> f.delta(v)));
        assertEquals(Layout.NONE, both("(lit 5)", v -> f.delta(v)));
        // one step only: four -> Nat.add two two (not reduced further)
        assertEquals("(Nat.add two two)", f.show(both("four", v -> f.delta(v))));
        // the memo UNF answers the second query, and deltaFast reads it
        int t = f.term("((@ id 1) Nat n0)");
        f.m.reset();
        int v = f.eval(t);
        assertEquals(Op.MISS, Reduce.deltaFast(f.m, v));
        int d1 = f.delta(v);
        long ticks = f.m.ticks();
        assertEquals(d1, f.delta(v));
        assertEquals(ticks, f.m.ticks(), "a memo hit does not tick");
        assertEquals(d1, Reduce.deltaFast(f.m, v));
        assertEquals(Layout.NONE, Reduce.deltaFast(f.m, f.eval(f.term("(f n0)"))));
    }

    @Test
    void wrongNumberOfLevelsDoesNotUnfold() {
        // R02 section 10 row 14: "not delta" in unfold
        assertTrue(whnfIsSelf("((@ id 1 2) Nat n0)"));
        assertTrue(whnfIsSelf("(@ two 1)"));
    }

    // ---- NATOP / NATFOLD (ISA 4.3; R02 7.6 table) -------------------------------------------------------------

    @Test
    void natTableSmall() {
        assertEquals("5", whnf("(Nat.add (lit 2) (lit 3))"));
        assertEquals("0", whnf("(Nat.sub (lit 2) (lit 5))")); // truncated
        assertEquals("3", whnf("(Nat.sub (lit 5) (lit 2))"));
        assertEquals("42", whnf("(Nat.mul (lit 6) (lit 7))"));
        assertEquals("1024", whnf("(Nat.pow (lit 2) (lit 10))"));
        assertEquals("1", whnf("(Nat.pow (lit 0) (lit 0))"));
        assertEquals("6", whnf("(Nat.gcd (lit 12) (lit 18))"));
        assertEquals("5", whnf("(Nat.gcd (lit 0) (lit 5))"));
        assertEquals("1", whnf("(Nat.mod (lit 7) (lit 3))"));
        assertEquals("7", whnf("(Nat.mod (lit 7) (lit 0))")); // x % 0 = x
        assertEquals("3", whnf("(Nat.div (lit 7) (lit 2))"));
        assertEquals("0", whnf("(Nat.div (lit 7) (lit 0))")); // x / 0 = 0
        assertEquals("Bool.true", whnf("(Nat.beq (lit 3) (lit 3))"));
        assertEquals("Bool.false", whnf("(Nat.beq (lit 3) (lit 4))"));
        assertEquals("Bool.true", whnf("(Nat.ble (lit 3) (lit 4))"));
        assertEquals("Bool.false", whnf("(Nat.ble (lit 4) (lit 3))"));
        assertEquals("8", whnf("(Nat.land (lit 12) (lit 10))"));
        assertEquals("14", whnf("(Nat.lor (lit 12) (lit 10))"));
        assertEquals("6", whnf("(Nat.xor (lit 12) (lit 10))"));
        assertEquals("48", whnf("(Nat.shiftLeft (lit 3) (lit 4))"));
        assertEquals("3", whnf("(Nat.shiftRight (lit 48) (lit 4))"));
        assertEquals("0", whnf("(Nat.shiftRight (lit 48) (lit 40))"));
    }

    @Test
    void natOperandsAreNormalisedAndNatZeroIsALiteralOperand() {
        assertEquals("22", whnf("(Nat.add (Nat.mul (lit 2) (lit 3)) (Nat.pow (lit 2) (lit 4)))"));
        assertEquals("3", whnf("(Nat.add Nat.zero (lit 3))")); // lit_ext: Const Nat.zero
        assertEquals("0", whnf("(Nat.mul Nat.zero Nat.zero)"));
        assertEquals("Bool.true", whnf("(Nat.beq Nat.zero (lit 0))"));
        assertEquals("2", whnf("(Nat.add zeroDef two)")); // operands reduced by delta first
        assertEquals("8", whnf("(dbl (dbl (lit 2)))"));
    }

    @Test
    void natOverflowGoesToTheBigPath() {
        assertEquals("2147483648", nat("(Nat.add (lit 2147483647) (lit 1))")); // 2^31: NBIG, two words
        assertEquals("2147483648", nat("(Nat.pow (lit 2) (lit 31))"));
        assertEquals("4294967296", nat("(Nat.mul (lit 65536) (lit 65536))"));
        assertEquals("4611686014132420609", nat("(Nat.mul (lit 2147483647) (lit 2147483647))"));
        assertEquals("9223372036854775808", nat("(Nat.pow (lit 2) (lit 63))")); // 2^63: the first stored number
        assertEquals("18446744073709551616", nat("(Nat.pow (lit 2) (lit 64))"));
        assertEquals("340282366920938463463374607431768211456", nat("(Nat.mul (Nat.pow (lit 2) (lit 64)) (Nat.pow (lit 2) (lit 64)))"));
        assertEquals("18446744073709551615", nat("(Nat.sub (Nat.pow (lit 2) (lit 64)) (lit 1))"));
        assertEquals("1", nat("(Nat.div (Nat.pow (lit 2) (lit 64)) (Nat.pow (lit 2) (lit 64)))"));
        assertEquals("0", nat("(Nat.sub (lit 5) (Nat.pow (lit 2) (lit 64)))"));
        assertEquals("4294967296", nat("(Nat.shiftLeft (lit 1) (lit 32))"));
        assertEquals("1", nat("(Nat.shiftRight (Nat.shiftLeft (lit 1) (lit 100)) (lit 100))"));
        assertEquals("Bool.true", whnf("(Nat.ble (lit 5) (Nat.pow (lit 2) (lit 64)))"));
        assertEquals("Bool.false", whnf("(Nat.beq (Nat.pow (lit 2) (lit 64)) (Nat.pow (lit 2) (lit 65)))"));
        assertEquals("Bool.true", whnf("(Nat.beq (Nat.pow (lit 2) (lit 64)) (Nat.mul (Nat.pow (lit 2) (lit 32)) (Nat.pow (lit 2) (lit 32))))"));
        assertEquals("123456789012345678901234567890", nat("(Nat.add (lit 123456789012345678901234567889) (lit 1))"));
    }

    @Test
    void natGuardsRejectAsTheOfficialKernel() {
        // R02 7.6: the exponent of Nat.pow above 2^32 - 1 is an error even for a base of 0 or 1
        failsBoth(MachineErrors.E_NATSIZE, "(Nat.pow (lit 2) (lit 4294967296))");
        failsBoth(MachineErrors.E_NATSIZE, "(Nat.pow (lit 0) (lit 4294967296))");
        failsBoth(MachineErrors.E_NATSIZE, "(Nat.pow (lit 1) (lit 4294967296))");
        // a > 1 && b != 0 && bytes(a) > MAX / b: with a word-sized base the exponent limit is 2^24
        failsBoth(MachineErrors.E_NATSIZE, "(Nat.pow (lit 2) (lit 16777217))");
        // shift: count above 2^32 - 1, or bytes(a) + b / 8 + 1 > MAX; but 0 <<< b = 0 for ANY b
        failsBoth(MachineErrors.E_NATSIZE, "(Nat.shiftLeft (lit 1) (lit 4294967296))");
        failsBoth(MachineErrors.E_NATSIZE, "(Nat.shiftLeft (lit 1) (lit 1073741760))");
        assertEquals("0", whnf("(Nat.shiftLeft (lit 0) (lit 99999999999999999999999999))"));
        assertEquals("0", whnf("(Nat.shiftRight (lit 5) (lit 99999999999999999999999999))")); // bugs/nat-shift-huge
        assertEquals("0", whnf("(Nat.shiftRight (Nat.pow (lit 2) (lit 70)) (lit 99999999999999999999999999))"));
    }

    @Test
    void natFunctionOnNonLiteralsIsNotFolded() {
        // opaque stand-ins of the test prelude: stuck applications are their own normal form
        assertTrue(whnfIsSelf("(Nat.mul n0 (lit 3))"));
        assertTrue(whnfIsSelf("(Nat.mul (lit 3) n0)"));
        assertTrue(whnfIsSelf("(Nat.mul (lit 2))")); // exactly two arguments (R02 7.6)
        assertTrue(whnfIsSelf("Nat.mul"));
        // Nat.add is a real definition: not foldable -> delta -> iota on the literal major, one constructor layer
        assertEquals("(succ^1 (Nat.rec.{1} (LAM (fun (_ : Nat) Nat) {}) n0 (LAM (fun (_ : Nat) (fun (_ : Nat) (Nat.succ #0))) {}) 1))",
                whnf("(Nat.add n0 (lit 2))"));
        // a literal on the left and a stuck right operand: the application unfolds; its major n0 is stuck
        assertEquals("(Nat.rec.{1} (LAM (fun (_ : Nat) Nat) {}) 2 (LAM (fun (_ : Nat) (fun (_ : Nat) (Nat.succ #0))) {}) n0)",
                whnf("(Nat.add (lit 2) n0)"));
    }

    @Test
    void natFoldJudgment() {
        assertEquals(Layout.imm(5), both("(Nat.add (lit 2) (lit 3))", v -> f.run(NatRed.natFold(f.m, v, 0))));
        assertEquals(Layout.NONE, both("(Nat.add n0 (lit 3))", v -> f.run(NatRed.natFold(f.m, v, 0))));
        assertEquals(Layout.NONE, both("(Nat.add (lit 3) n0)", v -> f.run(NatRed.natFold(f.m, v, 0))));
        assertEquals(Layout.NONE, both("(g (lit 2) (lit 3))", v -> f.run(NatRed.natFold(f.m, v, 0))));
        assertEquals(Layout.NONE, both("(Nat.add (lit 2))", v -> f.run(NatRed.natFold(f.m, v, 0))));
        assertEquals(Layout.NONE, both("(lit 2)", v -> f.run(NatRed.natFold(f.m, v, 0))));
        assertEquals(Layout.imm(1), both("(Nat.add (Nat.sub (lit 2) (lit 2)) (lit 1))", v -> f.run(NatRed.natFold(f.m, v, 0))));
    }

    @Test
    void natopHandles() {
        Machine m = f.m;
        assertEquals(7, NatRed.natop(m, rlvm.nat.NatOp.ADD, 3, 4));
        assertEquals(0, NatRed.natop(m, rlvm.nat.NatOp.SUB, 3, 4));
        assertEquals(1, NatRed.natop(m, rlvm.nat.NatOp.BLE, 3, 4));
        assertEquals(0, NatRed.natop(m, rlvm.nat.NatOp.BEQ, 3, 4));
        long big = NatRed.natop(m, rlvm.nat.NatOp.MUL, 1L << 62, 4);
        assertTrue(Nat.isBig(big)); // a big handle is negative as a long, but it is a value
        assertEquals("18446744073709551616", Nat.toDecimalString(m.nats, big));
        m.err = 0;
        long bad = NatRed.natop(m, rlvm.nat.NatOp.POW, 2, 1L << 32);
        assertTrue(Nat.isError(bad));
        assertEquals(MachineErrors.E_NATSIZE, m.err);
        m.err = 0;
    }

    // ---- Nat.succ rows (ISA section 11; RECONCILIATION section 2 "Nat.succ"; R02 7.6 row Nat.succ) ---------------

    @Test
    void nsucFoldsExactlyWhenTheBaseReducesToALiteral() {
        assertEquals("5", whnf("(Nat.succ (dbl (lit 2)))")); // NSUC(dbl 2, 1): the base reduces to the literal 4
        assertEquals("7", whnf("(Nat.succ (Nat.succ (Nat.succ (dbl (lit 2)))))")); // one NSUC cell with k = 3
        assertEquals("1", whnf("(Nat.succ zeroDef)")); // the base reduces to the constant Nat.zero: literal 0 + 1
        assertEquals("Bool.true", whnf("(Nat.ble (Nat.succ (dbl (lit 2))) (lit 7))"));
        assertEquals("5", whnf("(Nat.mod (Nat.succ (dbl (lit 2))) (lit 7))"));
        assertEquals("2147483648", nat("(Nat.succ ((@ id 1) Nat (lit 2147483647)))")); // across the immediate range
        // the base does not reduce to a literal: the ORIGINAL cell, the base is not replaced by its normal form
        assertTrue(whnfIsSelf("(Nat.succ (f (lit 2)))"));
        assertTrue(whnfIsSelf("(Nat.succ ((@ id 1) Nat (f (lit 2))))"));
        assertTrue(whnfIsSelf("(Nat.succ ((@ id 1) Nat (Nat.succ n0)))")); // base -> Nat.succ n0, not a literal
        assertTrue(whnfIsSelf("(Nat.ble (Nat.succ (f (lit 2))) (lit 7))")); // first operand not a literal: no fold
        assertTrue(whnfIsSelf("(Nat.mod (Nat.succ (f (lit 2))) (lit 7))"));
        // canonical forms made at construction (M1): Nat.succ of a literal / of Nat.zero is a literal
        assertEquals("5", whnf("(Nat.succ (lit 4))"));
        assertEquals("1", whnf("(Nat.succ Nat.zero)"));
        // Nat.zero itself is a normal form and is NOT rewritten to the literal 0 (R02 7.6 "literal facts")
        assertEquals("Nat.zero", whnf("Nat.zero"));
        assertEquals("Nat.zero", whnf("zeroDef"));
    }

    // ---- IOTA (ISA 4.3.5; R02 7.4) -----------------------------------------------------------------------------

    @Test
    void iotaOnConstructorsAndLiterals() {
        assertEquals("3", whnf(NATREC + " Nat.zero " + STEP + " (lit 3))")); // literal major, one layer at a time
        assertEquals("7", whnf(NATREC + " (lit 7) " + STEP + " Nat.zero)")); // Nat.zero major: the zero rule
        assertEquals("7", whnf(NATREC + " (lit 7) " + STEP + " (lit 0))")); // literal 0 is viewed as Nat.zero
        assertEquals("20", whnf("((@ Bool.rec 1) (fun (b : Bool) Nat) (lit 10) (lit 20) Bool.true)"));
        assertEquals("10", whnf("((@ Bool.rec 1) (fun (b : Bool) Nat) (lit 10) (lit 20) Bool.false)"));
        assertEquals("10", whnf("((@ Bool.rec 1) (fun (b : Bool) Nat) (lit 10) (lit 20) (Nat.beq (lit 1) (lit 2)))")); // major by whnf
        // the predecessor field of a literal: no constructor record is built
        assertEquals("41", whnf(NATREC + " (lit 0) (fun (x : Nat) (ih : Nat) x) (lit 42))"));
        assertEquals("2147483647", whnf(NATREC + " (lit 0) (fun (x : Nat) (ih : Nat) x) (lit 2147483648))"));
        assertEquals("18446744073709551615", nat(NATREC + " (lit 0) (fun (x : Nat) (ih : Nat) x) (Nat.pow (lit 2) (lit 64)))"));
        // a successor cell as major: the field is the predecessor cell or the base
        assertEquals("n0", whnf(NATREC + " (lit 0) (fun (x : Nat) (ih : Nat) x) (Nat.succ n0))"));
        assertEquals("(succ^1 n0)", whnf(NATREC + " (lit 0) (fun (x : Nat) (ih : Nat) x) (Nat.succ (Nat.succ n0)))"));
    }

    @Test
    void iotaTakesTheLastFieldsAndReappliesExtraArguments() {
        String list = "((@ List 0) Nat)";
        String rec = "((@ List.rec 1 0) Nat (fun (l : " + list + ") Nat) (lit 0) (fun (h : Nat) (t : " + list + ") (ih : Nat) (Nat.add h ih))";
        // R02 section 10 row 13: the fields are the LAST nfields arguments (the parameter Nat is skipped)
        assertEquals("0", whnf(rec + " ((@ List.nil 0) Nat))"));
        assertEquals("7", whnf(rec + " ((@ List.cons 0) Nat (lit 7) ((@ List.nil 0) Nat)))"));
        assertEquals("12", whnf(rec + " ((@ List.cons 0) Nat (lit 7) ((@ List.cons 0) Nat (lit 5) ((@ List.nil 0) Nat))))"));
        // R02 section 10 row 34: arguments after the major premise are re-applied
        assertEquals("6", whnf("((@ Nat.rec 1) (fun (x : Nat) (-> Nat Nat)) (fun (y : Nat) y) (fun (x : Nat) (ih : (-> Nat Nat)) (y : Nat) (Nat.succ y)) (lit 1) (lit 5))"));
        assertEquals("5", whnf("((@ Nat.rec 1) (fun (x : Nat) (-> Nat Nat)) (fun (y : Nat) y) (fun (x : Nat) (ih : (-> Nat Nat)) (y : Nat) (Nat.succ y)) (lit 0) (lit 5))"));
    }

    @Test
    void iotaIsStuckWithoutAConstructor() {
        assertTrue(whnfIsSelf(NATREC + " Nat.zero " + STEP + " n0)")); // the ORIGINAL cell
        assertTrue(whnfIsSelf(NATREC + " Nat.zero " + STEP + " (f n0))"));
        assertTrue(whnfIsSelf(NATREC + " Nat.zero " + STEP + ")")); // the major premise is missing
        assertTrue(whnfIsSelf("(@ Nat.rec 1)"));
        assertTrue(whnfIsSelf("((@ Nat.rec 1 2) (fun (x : Nat) Nat) Nat.zero " + STEP + " (lit 3))")); // wrong level count: no reduction
        assertEquals(Layout.NONE, both(NATREC + " Nat.zero " + STEP + " n0)", v -> f.run(Reduce.iota(f.m, v, 0))));
        assertEquals(Layout.NONE, both("(f n0)", v -> f.run(Reduce.iota(f.m, v, 0))));
        // a major of the wrong inductive type has no rule (reduce stays stuck, it does not fault)
        assertTrue(whnfIsSelf(NATREC + " Nat.zero " + STEP + " Bool.true)"));
        assertTrue(whnfIsSelf("((@ Bool.rec 1) (fun (b : Bool) Nat) (lit 10) (lit 20) (lit 3))"));
    }

    @Test
    void kRecursorUsesTheTypeOfTheMajorNotTheMajor() {
        assumeTrue(haveConvInfer, "Conv (M4) / Infer (M5) are not published yet");
        String rec = "((@ Eq.rec 1 1) Nat n0 (fun (b : Nat) (h : ((@ Eq 1) Nat n0 b)) Nat) (lit 5)";
        // R02 section 10 rows 10, 11: eqn is an axiom of type Eq Nat n0 n0: K replaces it by Eq.refl Nat n0
        assertEquals("5", whnf(rec + " n0 eqn)"));
        // the type of eqn2 is Eq Nat n0 (f n0): not the type of Eq.refl Nat n0, so the major stays (bugs/k-rec-conv)
        assertTrue(whnfIsSelf(rec + " (f n0) eqn2)"));
        // a constructor major reduces by the ordinary rule
        assertEquals("5", whnf(rec + " n0 ((@ Eq.refl 1) Nat n0))"));
    }

    @Test
    void structureEtaOnTheMajor() {
        assumeTrue(haveConvInfer, "Conv (M4) / Infer (M5) are not published yet");
        String prod = "((@ Prod 0 0) Nat Bool)";
        // R02 section 10 row 12: a non-constructor major of a non-recursive structure type is eta-expanded
        assertEquals("(proj Prod 0 pr)", whnf("((@ Prod.rec 1 0 0) Nat Bool (fun (p : " + prod + ") Nat) (fun (a : Nat) (b : Bool) a) pr)"));
        assertEquals("(proj Prod 1 pr)", whnf("((@ Prod.rec 1 0 0) Nat Bool (fun (p : " + prod + ") Bool) (fun (a : Nat) (b : Bool) b) pr)"));
        assertEquals("n0", whnf("((@ Prod.rec 1 0 0) Nat Bool (fun (p : " + prod + ") Nat) (fun (a : Nat) (b : Bool) a) prDef)"));
        // a zero-field structure: the major becomes the constructor
        assertEquals("3", whnf("((@ Unit.rec 1) (fun (u : Unit) Nat) (lit 3) u0)"));
        // not for proofs (And P Q : Prop), not for indexed families (IUnit)
        assertTrue(whnfIsSelf("((@ And.rec 1) P Q (fun (h : (And P Q)) Nat) (fun (a : P) (b : Q) (lit 1)) andPQ)"));
        assertTrue(whnfIsSelf("((@ IUnit.rec 1) (fun (i : Nat) (u : (IUnit i)) Nat) (lit 3) Nat.zero iu0)"));
        // not for a recursive type (Nat), whatever the major is
        assertTrue(whnfIsSelf(NATREC + " Nat.zero " + STEP + " n0)"));
    }

    // ---- QUOTR (ISA 4.3; R02 7.5) ---------------------------------------------------------------------------------

    @Test
    void quotientReduction() {
        String mk = "((@ Quot.mk 1) Nat r n0)";
        assertEquals("(f n0)", whnf("((@ Quot.lift 1 1) Nat r Nat f hr " + mk + ")"));
        assertEquals("(qm n0)", whnf("((@ Quot.ind 1) Nat r qb qm " + mk + ")"));
        // extra arguments after the major are re-applied (R02 section 10 row 34)
        assertEquals("(g2 n0 3)", whnf("((@ Quot.lift 1 1) Nat r (-> Nat Nat) g2 hr " + mk + " (lit 3))"));
        // the major is normalised with whnf
        assertEquals("(f n0)", whnf("((@ Quot.lift 1 1) Nat r Nat f hr ((@ id 1) ((@ Quot 1) Nat r) " + mk + "))"));
        // stuck: not Quot.mk, or not enough arguments
        assertTrue(whnfIsSelf("((@ Quot.lift 1 1) Nat r Nat f hr q0)"));
        assertTrue(whnfIsSelf("((@ Quot.lift 1 1) Nat r Nat f hr)"));
        assertTrue(whnfIsSelf("((@ Quot.ind 1) Nat r qb qm)"));
        assertEquals(Layout.NONE, both("((@ Quot.lift 1 1) Nat r Nat f hr q0)", v -> f.run(Reduce.quotr(f.m, v, 0))));
        assertEquals("(f n0)", f.show(both("((@ Quot.lift 1 1) Nat r Nat f hr " + mk + ")", v -> f.run(Reduce.quotr(f.m, v, 0)))));
    }

    // ---- CORE (ISA 4.3.3; R02 7.1-7.3) ------------------------------------------------------------------------------

    @Test
    void coreDoesNoDeltaAndNoNatFolding() {
        assertEquals("two", f.show(both("two", v -> f.core(v, Op.FULL))));
        assertEquals("(Nat.add 2 3)", f.show(both("(Nat.add (lit 2) (lit 3))", v -> f.core(v, Op.FULL))));
        assertEquals("(dbl 2)", f.show(both("(dbl (lit 2))", v -> f.core(v, Op.CHEAP))));
        // iota is part of whnf_core; the Nat.succ cell it produces is not folded by CORE
        assertEquals("(succ^1 (Nat.rec.{1} (LAM (fun (_ : Nat) Nat) {}) Nat.zero (LAM (fun (_ : Nat) (fun (_ : Nat) (Nat.succ #0))) {}) 2))",
                f.show(both(NATREC + " Nat.zero " + STEP + " (lit 3))", v -> f.core(v, Op.FULL))));
        // beta and zeta happen in EVAL; a let-bound value is a suspension that CORE forces
        assertEquals("(Nat.add 1 2)", f.show(both("(let (x : Nat := (Nat.add (lit 1) (lit 2))) x)", v -> f.core(v, Op.FULL))));
        assertEquals("3", whnf("(let (x : Nat := (Nat.add (lit 1) (lit 2))) x)"));
    }

    @Test
    void stuckProjectionReturnsTheOriginalCell() {
        // R02 7.1: "stuck: the ORIGINAL e, struct untouched"
        assertTrue(whnfIsSelf("(proj Prod 0 pr)"));
        assertTrue(whnfIsSelf("(proj Prod 0 ((@ id 1) ((@ Prod 0 0) Nat Bool) pr))")); // the struct reduces to pr, the cell is kept
        int[] in = new int[1];
        int out = both("(proj Prod 0 ((@ id 1) ((@ Prod 0 0) Nat Bool) pr))", v -> {
            in[0] = v;
            return f.core(v, Op.FULL);
        });
        assertEquals(in[0], out);
        assertEquals("(proj Prod 0 (id.{1} (Prod.{0, 0} Nat Bool) pr))", f.show(out));
        // a constructor of another structure is refused (R02 section 10 row 9)
        assertTrue(whnfIsSelf("(proj Prod 0 (Wrap.mk f))"));
        assertTrue(whnfIsSelf("(proj Prod 5 prDef)")); // index beyond the constructor's arguments: stuck
    }

    @Test
    void projectionReducesItsStructWithWhnfInFullModeAndWithoutDeltaInCheapMode() {
        assertEquals("n0", whnf("(proj Prod 0 prDef2)")); // prDef2 -> prDef -> Prod.mk Nat Bool n0 Bool.true
        assertEquals("Bool.true", whnf("(proj Prod 1 prDef2)"));
        assertEquals("n0", f.show(both("(proj Prod 0 prDef2)", v -> f.core(v, Op.FULL))));
        // R02 7.3: cheap_proj normalises the struct with whnf_core only: prDef2 is a definition, so the cell is stuck
        int[] in = new int[1];
        int out = both("(proj Prod 0 prDef2)", v -> {
            in[0] = v;
            return f.core(v, Op.CHEAP);
        });
        assertEquals(in[0], out);
        // a struct that reduces without delta (here: iota) is projected in cheap mode too
        String pt = "((@ Prod 0 0) Nat Bool)";
        assertEquals("n0", f.show(both("(proj Prod 0 ((@ Nat.rec 1) (fun (x : Nat) " + pt + ") ((@ Prod.mk 0 0) Nat Bool n0 Bool.true)"
                + " (fun (x : Nat) (ih : " + pt + ") ih) (lit 0)))", v -> f.core(v, Op.CHEAP))));
    }

    @Test
    void cheapAndFullCoreMemos() {
        assumeTrue(haveEval);
        int t = f.term("(proj Prod 0 prDef2)");
        f.m.reset();
        int v = f.eval(t);
        assertEquals(Op.MISS, Reduce.coreFast(f.m, v, Op.CHEAP));
        int cheap = f.core(v, Op.CHEAP);
        assertEquals(v, cheap);
        assertEquals(0, Values.flags(f.m, v) & Layout.FL_COREFULL);
        assertEquals(v, Reduce.coreFast(f.m, v, Op.CHEAP)); // the cheap memo answers cheap queries
        assertEquals(Op.MISS, Reduce.coreFast(f.m, v, Op.FULL)); // ... never a full one
        int full = f.core(v, Op.FULL);
        assertEquals("n0", f.show(full));
        assertNotEquals(0, Values.flags(f.m, v) & Layout.FL_COREFULL);
        assertEquals(full, Reduce.coreFast(f.m, v, Op.FULL));
        assertEquals(full, Reduce.coreFast(f.m, v, Op.CHEAP)); // a full result is a legal answer to a cheap request
        assertEquals(full, f.core(v, Op.CHEAP)); // and a cheap computation never replaces it
        assertEquals(full, Values.memo(f.m, v, Layout.M_CORE - Layout.M0));
        // WHNF memo and whnfFast
        assertEquals(full, f.whnf(v));
        assertEquals(full, Reduce.whnfFast(f.m, v));
        assertEquals(Layout.imm(7), Reduce.whnfFast(f.m, Layout.imm(7)));
    }

    @Test
    void projectionHeadIsReducedAndReapplied() {
        // R02 7.1 App case: f = whnf_core(head) changed: the arguments are applied to it
        assertEquals("(f n0)", whnf("((proj Wrap 0 wrapF) n0)"));
        assertEquals("(f n0)", f.show(both("((proj Wrap 0 wrapF) n0)", v -> f.core(v, Op.FULL))));
        assertEquals("(f n0)", whnf("((proj Wrap 0 (Wrap.mk f)) n0)")); // projected by EVAL already
        assertEquals("5", whnf("((proj Wrap 0 (Wrap.mk (fun (x : Nat) (Nat.succ x)))) (lit 4))")); // beta after the projection
        // cheap mode: the struct wrapF is a definition: the head is stuck and the application is the ORIGINAL cell
        int[] in = new int[1];
        int out = both("((proj Wrap 0 wrapF) n0)", v -> {
            in[0] = v;
            return f.core(v, Op.CHEAP);
        });
        assertEquals(in[0], out);
    }

    @Test
    void whnfMemoisesIntermediateValues() {
        assumeTrue(haveEval);
        int t = f.term("four");
        f.m.reset();
        int v = f.eval(t);
        int w = f.whnf(v);
        assertEquals(Layout.imm(4), w);
        assertEquals(w, Values.memo(f.m, v, 0));
        long ticks = f.m.ticks();
        assertEquals(w, f.whnf(v));
        assertEquals(ticks, f.m.ticks(), "a memo hit does not tick");
        // the unfolded value Nat.add two two got the memo too
        int mid = f.eval(f.term("(Nat.add two two)"));
        assertEquals(w, Values.memo(f.m, mid, 0));
    }

    // ---- STRVIEW (ISA 4.3; R02 7.6 string_lit_to_constructor) ------------------------------------------------------------

    @Test
    void stringLiteralConstructorForm() {
        assumeTrue(haveEval);
        f.m.reset();
        int s = f.eval(f.term("(str \"ab\")"));
        assertEquals(Layout.STR, Values.kind(f.m, s));
        int v = StrRed.strView(f.m, s);
        assertEquals("(String.ofList (List.cons.{0} Char (Char.ofNat 97) (List.cons.{0} Char (Char.ofNat 98) (List.nil.{0} Char))))", f.show(v));
        assertEquals(v, StrRed.strView(f.m, s)); // memo CTORF
        assertEquals(v, Values.memo(f.m, s, 0));
        assertEquals("(String.ofList (List.nil.{0} Char))", f.show(StrRed.strView(f.m, f.eval(f.term("(str \"\")")))));
        // one element per Unicode scalar value, not per byte (R02 section 10 row 20; bugs/str-lit-utf8)
        assertEquals("(String.ofList (List.cons.{0} Char (Char.ofNat 233) (List.nil.{0} Char)))",
                f.show(StrRed.strView(f.m, f.eval(f.term("(str \"é\")")))));
        assertTrue(f.m.idle());
        // a string literal is a weak head normal form
        assertEquals(s, f.whnf(s));
    }

    // ---- against the reference kernel ----------------------------------------------------------------------------------

    /**
     * WHNF folds to a literal exactly when the official whnf does (ISA 4.3.4): the reference kernel is run on the
     * same terms; both sides must agree on "is a Nat literal" and on the value.
     */
    @Test
    void whnfAgreesWithTheReferenceKernelOnLiterals() {
        assumeTrue(haveEval);
        String[] rows = {
            "two", "four", "(Nat.succ (dbl (lit 2)))", "(Nat.succ (f (lit 2)))", "(Nat.succ zeroDef)", "zeroDef", "Nat.zero",
            "(Nat.add n0 (lit 2))", "(Nat.add (lit 2) n0)", "(Nat.mul n0 (lit 3))", "(Nat.sub (lit 2) (lit 5))",
            "(Nat.ble (Nat.succ (dbl (lit 2))) (lit 7))", "(Nat.mod (Nat.succ (dbl (lit 2))) (lit 7))",
            "(Nat.mod (Nat.succ (f (lit 2))) (lit 7))", NATREC + " Nat.zero " + STEP + " (lit 3))",
            NATREC + " (lit 0) (fun (x : Nat) (ih : Nat) x) (Nat.succ (Nat.succ n0)))", "(Nat.succ ((@ id 1) Nat (Nat.succ n0)))",
            "(Nat.succ (Nat.succ (Nat.succ (dbl (lit 2)))))", "(dbl (dbl (lit 2)))", "(Nat.pow (lit 2) (lit 20))",
            "(Nat.gcd (lit 12) (Nat.succ (lit 17)))", "((proj Wrap 0 wrapF) (lit 4))", "(proj Prod 0 prDef2)",
        };
        RefKernel k = new RefKernel(f.ctx, EngineConfig.defaults());
        for (String row : rows) {
            int t = f.term(row);
            k.begin(Integer.MAX_VALUE, 0);
            int rt = k.whnf(t);
            boolean refLit = f.ctx.terms.kind(rt) == Kind.NATLIT;
            String refText = f.showTerm(rt);
            f.m.reset();
            int v = f.whnf(f.eval(t));
            boolean lit = v < 0 || Values.kind(f.m, v) == Layout.NBIG;
            assertEquals(refLit, lit, "literal-ness of whnf differs on " + row + ": reference " + refText + ", machine " + f.show(v));
            if (lit) {
                assertEquals(refText, "(lit " + f.show(v) + ")", row);
            }
        }
    }
}
