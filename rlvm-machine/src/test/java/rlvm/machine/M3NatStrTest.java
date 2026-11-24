package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import rlvm.api.MachineErrors;
import rlvm.store.Specials;

/**
 * Work package M3: the prelude guards of the Nat and String extensions (ISA 2.9), string literals as
 * recursor majors and projection structs (R02 7.2, 7.4, 7.6), {@code OFFSET} (ISA 4.4 B10; R02 8.4), deep
 * reduction chains without host recursion (ISA section 9) and the step budget (ISA 1.4).
 */
@Tag("fast")
class M3NatStrTest {

    static boolean haveEval;
    static boolean haveConv;

    @BeforeAll
    static void probe() {
        Fixture f = Fixture.prelude();
        haveEval = works(() -> f.eval(f.term("two")));
        haveConv = haveEval && works(() -> f.conv(f.eval(f.term("two")), f.eval(f.term("four"))));
    }

    private static boolean works(Runnable r) {
        try {
            r.run();
            return true;
        } catch (UnsupportedOperationException e) {
            return false; // a callee of another package is still a stub
        }
    }

    private static String whnf(Fixture f, String sexp) {
        return f.show(f.whnf(f.eval(f.term(sexp))));
    }

    // ---- prelude guards --------------------------------------------------------------------------------------

    /** Nat with VALID guards, {@code Nat.mul} INVALID, {@code Nat.gcd} named but never judged (ABSENT). */
    private static Fixture guards() {
        Fixture f = Fixture.empty();
        f.inductive("Nat", "Nat.zero : Nat", "Nat.succ : (-> Nat Nat)");
        f.special(Specials.NAT, "Nat");
        f.special(Specials.NAT_ZERO, "Nat.zero");
        f.special(Specials.NAT_SUCC, "Nat.succ");
        f.def("Nat.add", "", "(-> Nat Nat Nat)", "(fun (n : Nat) (m : Nat) n)", Fixture.regular(1));
        f.special(Specials.NAT_ADD, "Nat.add");
        f.def("Nat.mul", "", "(-> Nat Nat Nat)", "(fun (n : Nat) (m : Nat) n)", Fixture.regular(1));
        f.specialState(Specials.NAT_MUL, "Nat.mul", Specials.INVALID);
        f.def("Nat.gcd", "", "(-> Nat Nat Nat)", "(fun (n : Nat) (m : Nat) n)", Fixture.regular(1));
        f.inductive("Bool", "Bool.false : Bool", "Bool.true : Bool");
        f.def("Nat.beq", "", "(-> Nat Nat Bool)", "(fun (n : Nat) (m : Nat) Bool.false)", Fixture.regular(1));
        f.special(Specials.NAT_BEQ, "Nat.beq");
        f.axiom("x0", "", "Nat");
        return f;
    }

    @Test
    void validGuardFolds() {
        assumeTrue(haveEval);
        Fixture f = guards();
        assertEquals("5", whnf(f, "(Nat.add (lit 2) (lit 3))")); // the real sum, not the body's first argument
    }

    @Test
    void invalidGuardDeclinesWhereTheExtensionWouldFire() {
        assumeTrue(haveEval);
        for (int depth : new int[] {Op.HOST_DEPTH, 0}) {
            Fixture f = guards();
            f.hostDepth(depth);
            // ISA 2.9: INVALID is fault(E_PRELUDE): neither the fold nor the unfolding may decide
            f.expectFail(MachineErrors.E_PRELUDE, () -> whnf(f, "(Nat.mul (lit 2) (lit 3))"));
            assertTrue(f.m.idle());
            // operands that are no literals: the extension does not fire, the definition unfolds
            assertEquals("x0", whnf(f, "(Nat.mul x0 (lit 3))"));
            assertEquals("(LAM (fun (_ : Nat) #1) {2})", whnf(f, "(Nat.mul (lit 2))")); // one argument: plain delta + beta
        }
    }

    @Test
    void absentGuardDoesNotFire() {
        assumeTrue(haveEval);
        Fixture f = guards();
        // Nat.gcd is declared but its guard was never judged: no fold; delta gives the body's answer
        assertEquals("4", whnf(f, "(Nat.gcd (lit 4) (lit 6))"));
        // Nat.beq is VALID but Bool.true / Bool.false carry no guard: the fold has no result to give
        assertEquals("Bool.false", whnf(f, "(Nat.beq (lit 3) (lit 3))"));
    }

    // ---- string literals -----------------------------------------------------------------------------------

    /** String and Char as inductive types whose constructors are the constants of the literal's constructor form. */
    private static Fixture strings() {
        Fixture f = Fixture.empty();
        f.inductive("Nat", "Nat.zero : Nat", "Nat.succ : (-> Nat Nat)");
        f.special(Specials.NAT, "Nat");
        f.special(Specials.NAT_ZERO, "Nat.zero");
        f.special(Specials.NAT_SUCC, "Nat.succ");
        String listParam = "(α : (sort (succ u)))";
        f.inductive("List", "u", 1, "(pi " + listParam + " (sort (succ u)))", "List.nil : (pi " + listParam + " ((@ List u) α))",
                "List.cons : (pi " + listParam + " (head : α) (tail : ((@ List u) α)) ((@ List u) α))");
        f.inductive("Char", "Char.ofNat : (-> Nat Char)");
        f.inductive("String", "String.ofList : (-> ((@ List 0) Char) String)");
        f.special(Specials.LIST, "List");
        f.special(Specials.LIST_NIL, "List.nil");
        f.special(Specials.LIST_CONS, "List.cons");
        f.special(Specials.CHAR, "Char");
        f.special(Specials.CHAR_OF_NAT, "Char.ofNat");
        f.special(Specials.STRING, "String");
        f.special(Specials.STRING_OF_LIST, "String.ofList");
        for (String s : new String[] {"", "ab", "é", "a😀b"}) {
            f.string(s);
        }
        return f;
    }

    private static final String LEN = "((@ List.rec 1 0) Char (fun (l : ((@ List 0) Char)) Nat) Nat.zero"
            + " (fun (h : Char) (t : ((@ List 0) Char)) (ih : Nat) (Nat.succ ih))";

    private static String strLen(String lit) {
        return LEN + " ((@ String.rec 1) (fun (s : String) ((@ List 0) Char)) (fun (l : ((@ List 0) Char)) l) (str \"" + lit + "\")))";
    }

    @Test
    void stringLiteralAsRecursorMajor() {
        assumeTrue(haveEval);
        for (int depth : new int[] {Op.HOST_DEPTH, 0}) {
            Fixture f = strings();
            f.hostDepth(depth);
            // R02 7.4: major = whnf(string_lit_to_constructor(major)); the rule of String.ofList gets the list
            assertEquals("(List.cons.{0} Char (Char.ofNat 97) (List.cons.{0} Char (Char.ofNat 98) (List.nil.{0} Char)))",
                    whnf(f, "((@ String.rec 1) (fun (s : String) ((@ List 0) Char)) (fun (l : ((@ List 0) Char)) l) (str \"ab\"))"));
            assertEquals("2", whnf(f, strLen("ab")));
            assertEquals("Nat.zero", whnf(f, strLen(""))); // the minor premise itself: Nat.zero is not rewritten to 0
            // one list element per Unicode scalar value: "é" is 2 bytes, U+1F600 is 4 bytes (bugs/str-lit-utf8)
            assertEquals("1", whnf(f, strLen("é")));
            assertEquals("3", whnf(f, strLen("a😀b")));
            assertEquals("(Char.ofNat 128512)", whnf(f, "((@ List.rec 1 0) Char (fun (l : ((@ List 0) Char)) Char) (Char.ofNat (lit 0))"
                    + " (fun (h : Char) (t : ((@ List 0) Char)) (ih : Char) h)"
                    + " ((@ List.rec 1 0) Char (fun (l : ((@ List 0) Char)) ((@ List 0) Char)) ((@ List.nil 0) Char)"
                    + " (fun (h : Char) (t : ((@ List 0) Char)) (ih : ((@ List 0) Char)) t)"
                    + " ((@ String.rec 1) (fun (s : String) ((@ List 0) Char)) (fun (l : ((@ List 0) Char)) l) (str \"a😀b\"))))"));
            assertTrue(f.m.idle());
        }
    }

    @Test
    void stringLiteralAsProjectionStruct() {
        assumeTrue(haveEval);
        for (int depth : new int[] {Op.HOST_DEPTH, 0}) {
            Fixture f = strings();
            f.hostDepth(depth);
            // R02 7.2: reduce_proj_core views a string literal struct through its constructor form, in both modes
            String list = "(List.cons.{0} Char (Char.ofNat 97) (List.cons.{0} Char (Char.ofNat 98) (List.nil.{0} Char)))";
            assertEquals(list, whnf(f, "(proj String 0 (str \"ab\"))"));
            assertEquals(list, f.show(f.core(f.eval(f.term("(proj String 0 (str \"ab\"))")), Op.CHEAP)));
            // the literal itself is not a constructor of another structure, and is its own normal form
            int p = f.eval(f.term("(proj Char 0 (str \"ab\"))"));
            assertEquals(p, f.whnf(p));
            assertEquals("(str \"ab\")", whnf(f, "(str \"ab\")"));
            assertTrue(f.m.idle());
        }
    }

    @Test
    void stringViewNeedsItsPrelude() {
        assumeTrue(haveEval);
        Fixture f = Fixture.empty();
        f.inductive("Nat", "Nat.zero : Nat", "Nat.succ : (-> Nat Nat)");
        f.axiom("String", "", "Type");
        f.special(Specials.STRING, "String");
        f.string("ab");
        int s = f.eval(f.term("(str \"ab\")"));
        // List.cons, Char, .. are not declared: the constructor form cannot be built: a decline (ISA 2.9, 4.3 STRVIEW)
        f.expectFail(MachineErrors.E_PRELUDE, () -> f.run(StrRed.strView(f.m, s)));
    }

    // ---- OFFSET (ISA 4.4, B10; R02 8.4 is_def_eq_offset) ------------------------------------------------------------

    private static int offset(Fixture f, String a, String b) {
        return f.run(NatRed.offset(f.m, f.eval(f.term(a)), f.eval(f.term(b)), 0));
    }

    @Test
    void offsetWithoutAComparison() {
        assumeTrue(haveEval);
        Fixture f = Fixture.prelude();
        f.seal();
        // zero(t) && zero(s): literal 0 and the constant Nat.zero
        assertEquals(Layout.T, offset(f, "(lit 0)", "Nat.zero"));
        assertEquals(Layout.T, offset(f, "Nat.zero", "(lit 0)"));
        assertEquals(Layout.T, offset(f, "Nat.zero", "Nat.zero"));
        // two positive literals: both have a predecessor; comparing the predecessors is comparing the values
        assertEquals(Layout.T, offset(f, "(lit 5)", "(lit 5)"));
        assertEquals(Layout.F, offset(f, "(lit 5)", "(lit 6)"));
        assertEquals(Layout.F, offset(f, "(lit 5)", "(lit 18446744073709551616)"));
        assertEquals(Layout.T, offset(f, "(lit 18446744073709551616)", "(lit 18446744073709551616)"));
        assertEquals(Layout.F, offset(f, "(lit 18446744073709551616)", "(lit 18446744073709551617)"));
        // one side has no predecessor: undecided
        assertEquals(Layout.UNDEF, offset(f, "(lit 0)", "(lit 5)"));
        assertEquals(Layout.UNDEF, offset(f, "Nat.zero", "(Nat.succ n0)"));
        assertEquals(Layout.UNDEF, offset(f, "n0", "(lit 5)"));
        assertEquals(Layout.UNDEF, offset(f, "(f n0)", "(Nat.succ n0)"));
        assertEquals(Layout.UNDEF, offset(f, "(Nat.succ n0)", "two")); // a definition is not unfolded here
        assertEquals(Layout.UNDEF, offset(f, "Nat", "Nat"));
        // the inline part
        assertEquals(Op.MISS, NatRed.offsetFast(f.m, f.eval(f.term("(Nat.succ n0)")), Layout.imm(3)));
        assertEquals(Layout.UNDEF, NatRed.offsetFast(f.m, f.eval(f.term("(Nat.succ n0)")), Layout.imm(0)));
        assertEquals(Layout.T, NatRed.offsetFast(f.m, Layout.imm(3), Layout.imm(3)));
        assertTrue(f.m.idle());
    }

    @Test
    void offsetPeelsAllCommonLayersAtOnce() {
        assumeTrue(haveConv, "Conv (M4) is not published yet");
        for (int depth : new int[] {Op.HOST_DEPTH, 0}) {
            Fixture f = Fixture.prelude();
            f.def("one", "", "Nat", "(Nat.succ Nat.zero)", Fixture.regular(1));
            f.hostDepth(depth);
            assertEquals(Layout.T, offset(f, "(Nat.succ n0)", "(Nat.succ n0)"));
            assertEquals(Layout.F, offset(f, "(Nat.succ n0)", "(Nat.succ (f n0))")); // CONV(n0, f n0)
            assertEquals(Layout.T, offset(f, "(Nat.succ (Nat.succ two))", "(lit 4)")); // peel 2: CONV(two, 2)
            assertEquals(Layout.F, offset(f, "(Nat.succ (Nat.succ two))", "(lit 5)"));
            assertEquals(Layout.T, offset(f, "(lit 4)", "(Nat.succ (Nat.succ two))"));
            assertEquals(Layout.T, offset(f, "(Nat.succ (Nat.succ one))", "(Nat.succ two)")); // peel 1: CONV(succ one, two)
            assertEquals(Layout.F, offset(f, "(Nat.succ (Nat.succ (Nat.succ one)))", "(Nat.succ two)")); // CONV(succ^2 one, two)
            assertEquals(Layout.F, offset(f, "(Nat.succ (Nat.succ n0))", "(Nat.succ (Nat.succ (Nat.succ n0)))")); // CONV(n0, succ n0)
            assertEquals(Layout.F, offset(f, "(Nat.succ (Nat.succ (Nat.succ n0)))", "(lit 2)")); // CONV(succ n0, 0)
            assertEquals(Layout.F, offset(f, "(Nat.succ n0)", "(lit 18446744073709551616)")); // CONV(n0, 2^64 - 1)
            assertEquals(Layout.T, offset(f, "(Nat.succ two)", "(lit 3)"));
            assertTrue(f.m.idle());
        }
    }

    // ---- depth and budgets -----------------------------------------------------------------------------------

    private static final String NATREC = "((@ Nat.rec 1) (fun (x : Nat) Nat)";

    /**
     * 50,000 nested {@code Nat.succ} bases (each WHNF of an NSUC needs the WHNF of its base) and a 50,000-step
     * chain of head reductions run on a 512 KiB thread stack: beyond the depth bound the judgments are frames
     * in W (stepper-convention.md H9, H12 item 3), and the chain of {@code whnf_core} steps is a loop.
     */
    @Test
    void deepReductionNeedsNoHostStack() throws InterruptedException {
        assumeTrue(haveEval);
        Throwable[] failure = new Throwable[1];
        String[] out = new String[3];
        Thread t = new Thread(null, () -> {
            try {
                Fixture f = Fixture.prelude();
                out[0] = whnf(f, NATREC + " Nat.zero (fun (x : Nat) (ih : Nat) (Nat.succ ih)) (lit 50000))");
                long frames = f.m.framesWritten;
                out[1] = whnf(f, NATREC + " (lit 7) (fun (x : Nat) (ih : Nat) ih) (lit 50000))");
                out[2] = "frames written by the chain of head reductions: " + (f.m.framesWritten - frames);
                assertTrue(f.m.idle());
            } catch (Throwable e) {
                failure[0] = e;
            }
        }, "m3-deep", 512 << 10);
        t.start();
        t.join();
        assertNull(failure[0], () -> "failed on a 512 KiB stack: " + failure[0]);
        assertEquals("50000", out[0]);
        assertEquals("7", out[1]);
        assertEquals("frames written by the chain of head reductions: 0", out[2]);
    }

    @Test
    void stepBudgetAbortsAndLeavesNoWrongMemo() {
        assumeTrue(haveEval);
        for (int depth : new int[] {Op.HOST_DEPTH, 0}) {
            Fixture f = Fixture.prelude();
            f.hostDepth(depth);
            int v = f.eval(f.term(NATREC + " Nat.zero (fun (x : Nat) (ih : Nat) (Nat.succ ih)) (lit 2000))"));
            f.m.beginDecl(Integer.MAX_VALUE, -1, 500, Long.MAX_VALUE);
            f.expectFail(MachineErrors.E_STEPS, () -> f.whnf(v));
            assertTrue(f.m.idle());
            assertEquals(0, Values.memo(f.m, v, 0), "a judgment cut by the budget wrote its memo");
            // the same value under an unlimited budget: the right answer, whatever the cut run left behind
            f.m.beginDecl(Integer.MAX_VALUE, -1, Op.FUEL_UNLIMITED, Long.MAX_VALUE);
            assertEquals(Layout.imm(2000), f.whnf(v));
        }
    }
}
