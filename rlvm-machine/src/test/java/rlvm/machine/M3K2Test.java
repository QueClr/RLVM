package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Random;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Work package M3: the derived trees K2 of {@code Rules} (ISA 3.6 "K2", behind {@code SW_K2}, off by default).
 * The acceptance: a derived rule gives what DELTA + CORE give. Every row is reduced on a freshly reset machine
 * with all switches off and again with {@code k2}, with {@code m3a} (the direct recursor rules of
 * {@code Reduce.iotaDirect}) and with {@code k2,m3a,code,casetrees}, the hot threshold
 * lowered to the first unfolding and the derivation allowance lifted; the two results are compared by their
 * constructor normal forms (weak head normal form, then the arguments of constructor and stuck applications
 * likewise), which do not depend on how a delayed argument is represented.
 */
@Tag("fast")
class M3K2Test {

    static Fixture f;
    static boolean ready;
    static int k2;
    static int k2k1;
    static int m3a;

    @BeforeAll
    static void setUp() {
        f = Fixture.prelude();
        f.def("Nat.casesOn", "", "(pi (motive : (-> Nat Type)) (t : Nat) (zero : (motive Nat.zero)) (succ : (pi (n : Nat) (motive (Nat.succ n)))) (motive t))",
                "(fun (motive : (-> Nat Type)) (t : Nat) (zero : (motive Nat.zero)) (succ : (pi (n : Nat) (motive (Nat.succ n))))"
                        + " ((@ Nat.rec 1) motive zero (fun (n : Nat) (ih : (motive n)) (succ n)) t))", Fixture.ABBREV);
        f.def("Bool.casesOn", "", "(pi (motive : (-> Bool Type)) (t : Bool) (false : (motive Bool.false)) (true : (motive Bool.true)) (motive t))",
                "(fun (motive : (-> Bool Type)) (t : Bool) (false : (motive Bool.false)) (true : (motive Bool.true))"
                        + " ((@ Bool.rec 1) motive false true t))", Fixture.ABBREV);
        f.axiom("fb", "", "(-> Nat Bool)");
        f.def("isZero", "", "(-> Nat Bool)", "(fun (n : Nat) (Nat.casesOn (fun (x : Nat) Bool) n Bool.true (fun (k : Nat) Bool.false)))", Fixture.regular(2));
        f.def("npred", "", "(-> Nat Nat)", "(fun (n : Nat) (Nat.casesOn (fun (x : Nat) Nat) n Nat.zero (fun (k : Nat) k)))", Fixture.regular(2));
        f.def("npred2", "", "(-> Nat Nat)",
                "(fun (n : Nat) (Nat.casesOn (fun (x : Nat) Nat) n (lit 100) (fun (k : Nat) (Nat.casesOn (fun (x : Nat) Nat) k (lit 200) (fun (j : Nat) j)))))",
                Fixture.regular(3));
        f.def("bnot", "", "(-> Bool Bool)", "(fun (b : Bool) (Bool.casesOn (fun (x : Bool) Bool) b Bool.true Bool.false))", Fixture.regular(2));
        f.def("pick", "", "(-> Bool Nat Nat Nat)", "(fun (b : Bool) (x : Nat) (y : Nat) ((@ Bool.rec 1) (fun (c : Bool) Nat) y x b))", Fixture.regular(2));
        // structure projections: an instance-method call in miniature
        String pnb = "((@ Prod 0 0) Nat Bool)";
        f.def("pfst", "", "(-> " + pnb + " Nat)", "(fun (p : " + pnb + ") (proj Prod 0 p))", Fixture.ABBREV);
        f.def("psnd", "", "(-> " + pnb + " Bool)", "(fun (p : " + pnb + ") (proj Prod 1 p))", Fixture.ABBREV);
        f.def("mkp", "", "(-> Nat " + pnb + ")", "(fun (n : Nat) ((@ Prod.mk 0 0) Nat Bool (Nat.succ n) (isZero n)))", Fixture.regular(3));
        String pff = "((@ Prod 0 0) (-> Nat Nat) Nat)";
        f.def("papp", "", "(-> " + pff + " Nat Nat)", "(fun (p : " + pff + ") (x : Nat) ((proj Prod 0 p) (Nat.add x (proj Prod 1 p))))", Fixture.regular(3));
        // structural recursion with an induction hypothesis, and a table of pairs as brecOn builds it
        f.def("myadd", "", "(-> Nat Nat Nat)",
                "(fun (x : Nat) (y : Nat) ((@ Nat.rec 1) (fun (c : Nat) Nat) x (fun (n : Nat) (ih : Nat) (Nat.succ ih)) y))", Fixture.regular(2));
        String pnn = "((@ Prod 0 0) Nat Nat)";
        f.def("fibp", "", "(-> Nat " + pnn + ")",
                "(fun (n : Nat) ((@ Nat.rec 1) (fun (c : Nat) " + pnn + ") ((@ Prod.mk 0 0) Nat Nat (lit 0) (lit 1))"
                        + " (fun (k : Nat) (ih : " + pnn + ") ((@ Prod.mk 0 0) Nat Nat (proj Prod 1 ih) (Nat.add (proj Prod 0 ih) (proj Prod 1 ih)))) n))",
                Fixture.regular(3));
        f.def("fib", "", "(-> Nat Nat)", "(fun (n : Nat) (proj Prod 0 (fibp n)))", Fixture.regular(4));
        // a list function by recursion, a higher-order one, and leaves that apply a parameter
        String ln = "((@ List 0) Nat)";
        f.def("len", "", "(-> " + ln + " Nat)",
                "(fun (xs : " + ln + ") ((@ List.rec 1 0) Nat (fun (c : " + ln + ") Nat) (lit 0) (fun (h : Nat) (t : " + ln + ") (ih : Nat) (Nat.succ ih)) xs))",
                Fixture.regular(2));
        f.def("lmap", "", "(-> (-> Nat Nat) " + ln + " " + ln + ")",
                "(fun (g : (-> Nat Nat)) (xs : " + ln + ") ((@ List.rec 1 0) Nat (fun (c : " + ln + ") " + ln + ") ((@ List.nil 0) Nat)"
                        + " (fun (h : Nat) (t : " + ln + ") (ih : " + ln + ") ((@ List.cons 0) Nat (g h) ih)) xs))", Fixture.regular(2));
        f.def("lhead", "", "(-> " + ln + " Nat)",
                "(fun (xs : " + ln + ") ((@ List.rec 1 0) Nat (fun (c : " + ln + ") Nat) (lit 77) (fun (h : Nat) (t : " + ln + ") (ih : Nat) h) xs))",
                Fixture.regular(2));
        f.def("applyIf", "", "(-> Bool (-> Nat Nat) Nat Nat)",
                "(fun (b : Bool) (g : (-> Nat Nat)) (x : Nat) ((@ Bool.rec 1) (fun (c : Bool) Nat) (Nat.succ x) (Nat.succ (g x)) b))", Fixture.regular(2));
        f.def("applyTail", "", "(-> Bool (-> Nat Nat) Nat Nat)",
                "(fun (b : Bool) (g : (-> Nat Nat)) (x : Nat) ((@ Bool.rec 1) (fun (c : Bool) Nat) x (g (g x)) b))", Fixture.regular(2));
        f.def("twice", "", "(-> (-> Nat Nat) Nat Nat)", "(fun (g : (-> Nat Nat)) (x : Nat) (g (g x)))", Fixture.regular(2));
        f.def("curried", "", "(-> Bool Nat Nat Nat)",
                "(fun (b : Bool) ((@ Bool.rec 1) (fun (c : Bool) (-> Nat Nat Nat)) (fun (x : Nat) (y : Nat) x) (fun (x : Nat) (y : Nat) (Nat.add x y)) b))",
                Fixture.regular(2));
        // a K-like major is never split
        f.def("castn", "", "(pi (a : Nat) (b : Nat) (h : ((@ Eq 1) Nat a b)) Nat)",
                "(fun (a : Nat) (b : Nat) (h : ((@ Eq 1) Nat a b)) ((@ Eq.rec 1 1) Nat a (fun (c : Nat) (e : ((@ Eq 1) Nat a c)) Nat) (Nat.succ a) b h))",
                Fixture.regular(2));
        f.seal();
        ready = works(() -> f.conv(f.eval(f.term("two")), f.eval(f.term("four"))));
        k2 = Debug.parseSwitches("k2");
        k2k1 = Debug.parseSwitches("k2,m3a,code,casetrees");
        m3a = Debug.parseSwitches("m3a");
    }

    private static boolean works(Runnable r) {
        try {
            r.run();
            return true;
        } catch (UnsupportedOperationException e) {
            return false;
        }
    }

    /** The constructor normal form of {@code v} as text (see the class comment); closures and types are not entered. */
    private static String nf(int v, int depth) {
        v = f.whnf(v);
        long succs = 0;
        while (v > Layout.HOLE && (f.m.vh[v << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT) == Layout.NSUC) {
            succs += f.m.vh[(v << Layout.REC_SHIFT) + Layout.B]; // Nat.succ^k of a base that is no literal: one chain, however it is cut
            v = f.whnf(f.m.vh[(v << Layout.REC_SHIFT) + Layout.A]);
        }
        if (v < 0) {
            return Long.toString((v & Layout.IMM_MASK) + succs);
        }
        if (succs != 0) {
            return "(succ^" + succs + " " + nf(v, depth + 1) + ")";
        }
        int[] vh = f.m.vh;
        int o = v << Layout.REC_SHIFT;
        int kind = vh[o] >>> Layout.KIND_SHIFT;
        if (kind == Layout.NEU && depth < 12) {
            int head = vh[o + Layout.A];
            int n = vh[o] & Layout.AUX_MASK;
            int run = vh[o + Layout.B];
            StringBuilder sb = new StringBuilder("(").append(Values.kind(f.m, head) == Layout.PROJ ? "proj:" + nf(f.m.vh[(head << Layout.REC_SHIFT) + Layout.A], depth + 1)
                    : f.show(head));
            for (int i = 0; i < n; i++) {
                sb.append(' ').append(nf(Runs.arg(f.m, run, i), depth + 1));
            }
            return sb.append(')').toString();
        }
        if (kind == Layout.LAM || kind == Layout.PI || kind == Layout.PII) {
            return "<closure>";
        }
        if (kind == Layout.PROJ) {
            return "proj:" + vh[o + Layout.C] + ":" + nf(vh[o + Layout.A], depth + 1);
        }
        return f.show(v);
    }

    /** The normal form of the term under the given switches on a freshly reset machine; a failure is part of the answer. */
    private static String run(String sexp, int switches) {
        int t = f.term(sexp);
        f.m.reset();
        f.m.beginDecl(Integer.MAX_VALUE, -1, 2_000_000, Long.MAX_VALUE);
        f.m.sw = switches;
        f.m.x3.k2Hot = 1;
        f.m.x3.k2Share = 0;
        try {
            return nf(f.eval(t), 0);
        } catch (Fixture.MachineFailure e) {
            return "fails with " + rlvm.api.MachineErrors.name(e.code);
        } finally {
            f.m.sw = 0;
        }
    }

    private static final String[] NATS = {"(lit 0)", "(lit 1)", "(lit 7)", "Nat.zero", "(Nat.succ n0)", "(Nat.succ (Nat.succ n0))", "n0",
        "(f n0)", "two", "(npred (lit 5))", "(Nat.add n0 (lit 2))", "(fib (lit 6))", "(myadd (lit 3) (lit 4))", "(pfst (mkp (lit 3)))",
        "(len L)", "(lhead L)", "(twice f (lit 3))"};
    private static final String[] BOOLS = {"Bool.true", "Bool.false", "(Nat.beq (lit 1) (lit 1))", "(fb n0)", "(isZero (lit 0))",
        "(isZero (Nat.succ n0))", "(isZero n0)", "(bnot (fb n0))", "(bnot Bool.false)", "(psnd (mkp (lit 0)))"};
    private static final String[] FUNS = {"f", "Nat.succ", "(fun (y : Nat) (Nat.add y y))", "(fun (y : Nat) y)", "(g (lit 1))", "npred",
        "(fun (y : Nat) (npred2 y))", "(myadd (lit 2))", "(curried Bool.true (lit 5))"};
    private static final String NIL = "((@ List.nil 0) Nat)";
    private static final String[] LISTS = {NIL, "((@ List.cons 0) Nat N " + NIL + ")", "((@ List.cons 0) Nat (lit 4) ((@ List.cons 0) Nat N " + NIL + "))",
        "(lmap G ((@ List.cons 0) Nat (lit 1) ((@ List.cons 0) Nat (lit 2) " + NIL + ")))"};
    private static final String[] SHAPES = {
        "(isZero N)", "(npred N)", "(npred2 N)", "(bnot B)", "(pick B N N)", "(npred (npred2 N))", "(pick (isZero N) N (npred N))",
        "(pfst (mkp N))", "(psnd (mkp N))", "(pfst pr)", "(pfst prDef)", "(papp ((@ Prod.mk 0 0) (-> Nat Nat) Nat G N) N)",
        "(myadd N N)", "(myadd N (myadd N N))", "(fib N)", "(fibp N)", "(len L)", "(lhead L)", "(lmap G L)", "(len (lmap G L))",
        "(applyIf B G N)", "(applyTail B G N)", "(twice G N)", "(curried B N N)", "(curried B N)", "(castn n0 n0 eqn)",
        "(castn n0 (f n0) eqn2)", "(Nat.casesOn (fun (x : Nat) Nat) N N (fun (k : Nat) (Nat.add k N)))",
        "(Nat.casesOn (fun (x : Nat) (-> Nat Nat)) N (fun (y : Nat) y) (fun (k : Nat) (y : Nat) (Nat.add k y)) N)",
        "(Bool.casesOn (fun (x : Bool) (-> Nat Bool)) B isZero (fun (y : Nat) (bnot (isZero y))) N)", "(pick B)", "(myadd N)",
    };

    private static String fill(String s, Random rnd, int depth) {
        StringBuilder sb = new StringBuilder();
        for (int j = 0; j < s.length(); j++) {
            char c = s.charAt(j);
            boolean hole = (c == 'N' || c == 'B' || c == 'G' || c == 'L') && j > 0 && s.charAt(j - 1) == ' '
                    && (j + 1 == s.length() || s.charAt(j + 1) == ' ' || s.charAt(j + 1) == ')');
            if (!hole) {
                sb.append(c);
                continue;
            }
            String[] pool = c == 'N' ? NATS : c == 'B' ? BOOLS : c == 'G' ? FUNS : LISTS;
            String pickd = pool[rnd.nextInt(pool.length)];
            sb.append(depth < 3 ? fill(pickd, rnd, depth + 1) : c == 'N' ? "(lit 2)" : c == 'B' ? "Bool.true" : c == 'G' ? "f" : NIL);
        }
        return sb.toString();
    }

    /** "A derived rule equals DELTA + CORE on random arguments" (ISA 3.6: a leaf is a reduct of the application). */
    @Test
    void derivedRulesEqualTheGenericReductionOnRandomArguments() {
        assumeTrue(ready, "the machine is not complete in this tree");
        Random rnd = new Random(20261124);
        long fired = 0;
        long missed = 0;
        int derived = 0;
        for (int i = 0; i < 400; i++) {
            String s = fill(SHAPES[i % SHAPES.length], rnd, 0);
            String want = run(s, 0);
            String got = run(s, k2);
            fired += f.m.x3.k2Fired;
            missed += f.m.x3.k2Missed;
            derived += f.m.x3.k2Derivs;
            f.m.x3.k2Fired = 0;
            f.m.x3.k2Missed = 0;
            f.m.x3.k2Derivs = 0;
            assertEquals(want, got, "generic and K2 differ on " + s);
            assertEquals(want, run(s, k2k1), "generic and K2 + direct rules + K1 differ on " + s);
            assertEquals(want, run(s, m3a), "generic and direct recursor rules (m3a) differ on " + s);
        }
        assertTrue(fired > 1000, "rules fired: " + fired + ", missed " + missed + ", nodes derived " + derived);
        assertTrue(missed < fired, "rules fired: " + fired + ", missed " + missed);
    }

    /** The same under the worklist machine (D = 0): every WHNF of a major is a suspension, every frame of K2 is written. */
    @Test
    void derivedRulesUnderTheWorklistMachine() {
        assumeTrue(ready, "the machine is not complete in this tree");
        Random rnd = new Random(7);
        try {
            for (int i = 0; i < 120; i++) {
                String s = fill(SHAPES[i % SHAPES.length], rnd, 0);
                f.hostDepth(Op.HOST_DEPTH);
                String want = run(s, 0);
                f.hostDepth(0);
                assertEquals(want, run(s, k2), "generic and K2 under D = 0 differ on " + s);
            }
        } finally {
            f.hostDepth(Op.HOST_DEPTH);
        }
    }

    /** Nothing of K2 runs with the switch off or in strict mode, and a session reset forgets the rules. */
    @Test
    void offByDefaultAndForgottenAtReset() {
        assumeTrue(ready, "the machine is not complete in this tree");
        f.m.x3.k2Fired = 0;
        f.m.x3.k2Derivs = 0;
        run("(fib (lit 9))", 0);
        assertEquals(0, f.m.x3.k2Fired);
        assertEquals(0, f.m.x3.k2Derivs);
        assertEquals(0, f.m.x3.k2Used);
        String a = run("(fib (lit 9))", k2);
        assertTrue(f.m.x3.k2Fired > 0, "fired " + f.m.x3.k2Fired + " missed " + f.m.x3.k2Missed + " derivs " + f.m.x3.k2Derivs
                + " aborted " + f.m.x3.k2Aborted + " rules " + f.m.x3.k2Rules + " used " + f.m.x3.k2Used + " ticks " + (f.m.ticks() - f.m.x3.k2Ticks0)
                + " err " + f.m.err);
        assertTrue(f.m.x3.k2Used > 0);
        f.m.reset();
        assertEquals(0, f.m.x3.k2Used);
        assertEquals(8, f.m.x3.k2CodeN);
        assertEquals("34", a);
        f.m.x3.k2Fired = 0;
        f.m.strict = true;
        try {
            run("(fib (lit 9))", k2);
        } finally {
            f.m.strict = false;
        }
        assertEquals(0, f.m.x3.k2Fired);
    }

    /** A light declaration (below 16,384 ticks) never touches the rule table when the cap is in force. */
    @Test
    void aLightDeclarationDoesNotCount() {
        assumeTrue(ready, "the machine is not complete in this tree");
        int t = f.term("(fib (lit 9))");
        f.m.reset();
        f.m.beginDecl(Integer.MAX_VALUE, -1, 2_000_000, Long.MAX_VALUE);
        f.m.sw = k2;
        f.m.x3.k2Hot = 1;
        f.m.x3.k2Share = 512;
        try {
            assertEquals("34", nf(f.eval(t), 0));
        } finally {
            f.m.sw = 0;
        }
        assertEquals(0, f.m.x3.k2Used, "heads counted in a light declaration");
    }

    /** The derivation allowance (0.2 % of the session's ticks): a short session derives nothing. */
    @Test
    void aShortSessionDerivesNothing() {
        assumeTrue(ready, "the machine is not complete in this tree");
        int t = f.term("(fib (lit 9))");
        f.m.reset();
        f.m.beginDecl(Integer.MAX_VALUE, -1, 2_000_000, Long.MAX_VALUE);
        f.m.sw = k2;
        f.m.x3.k2Hot = 1;
        f.m.x3.k2Share = 512;
        f.m.x3.k2Derivs = 0;
        try {
            assertEquals("34", nf(f.eval(t), 0));
        } finally {
            f.m.sw = 0;
        }
        assertEquals(0, f.m.x3.k2Derivs);
    }
}
