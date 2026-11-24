package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Random;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import rlvm.store.DeclKind;

/**
 * Work package M3, staging step 4 (behind {@code SW_CODE} / {@code SW_CASETREES}, off by default): the
 * reduction family enters M7's code objects at three places - {@code DELTA} through BODY(row),
 * {@code IOTA} through RULE(rec, j), the delta step of {@code WHNF} through the case tree RULES(c). The
 * acceptance of ISA 13.6: case trees equal DELTA + IOTA on random arguments; more generally every
 * reduction gives the same value with the switches on and off (ISA 0.2 decision 6, section 11 "tier
 * equivalence").
 *
 * <p>Every role is elaborated up front ({@code Elab.elaborate}), so that the hooks fire at the first use
 * and not at the {@code ELAB_AT}-th. Each row is reduced on a freshly reset machine with all switches off
 * and again with {@code code,casetrees}; the printed values must be equal. Within one session the ids
 * themselves must be equal (the DELTA and IOTA rows).
 */
@Tag("fast")
class M3TierTest {

    static Fixture f;
    static boolean ready;
    static int k1;

    @BeforeAll
    static void setUp() {
        f = Fixture.prelude();
        f.def("dbl", "", "(-> Nat Nat)", "(fun (x : Nat) (Nat.add x x))", Fixture.regular(2));
        f.def("zeroDef", "", "Nat", "Nat.zero", Fixture.regular(1));
        f.axiom("fb", "", "(-> Nat Bool)");
        // I.casesOn as Lean generates it: the minor premises are the eta-expansions of parameters (shape CASESON)
        f.def("Nat.casesOn", "", "(pi (motive : (-> Nat Type)) (t : Nat) (zero : (motive Nat.zero)) (succ : (pi (n : Nat) (motive (Nat.succ n)))) (motive t))",
                "(fun (motive : (-> Nat Type)) (t : Nat) (zero : (motive Nat.zero)) (succ : (pi (n : Nat) (motive (Nat.succ n))))"
                        + " ((@ Nat.rec 1) motive zero (fun (n : Nat) (ih : (motive n)) (succ n)) t))", Fixture.ABBREV);
        f.def("Bool.casesOn", "", "(pi (motive : (-> Bool Type)) (t : Bool) (false : (motive Bool.false)) (true : (motive Bool.true)) (motive t))",
                "(fun (motive : (-> Bool Type)) (t : Bool) (false : (motive Bool.false)) (true : (motive Bool.true))"
                        + " ((@ Bool.rec 1) motive false true t))", Fixture.ABBREV);
        // matchers: case trees over casesOn with constant and field-reading leaves, nested
        f.def("isZero", "", "(-> Nat Bool)", "(fun (n : Nat) (Nat.casesOn (fun (x : Nat) Bool) n Bool.true (fun (k : Nat) Bool.false)))", Fixture.regular(2));
        f.def("npred", "", "(-> Nat Nat)", "(fun (n : Nat) (Nat.casesOn (fun (x : Nat) Nat) n Nat.zero (fun (k : Nat) k)))", Fixture.regular(2));
        f.def("npred2", "", "(-> Nat Nat)",
                "(fun (n : Nat) (Nat.casesOn (fun (x : Nat) Nat) n (lit 100) (fun (k : Nat) (Nat.casesOn (fun (x : Nat) Nat) k (lit 200) (fun (j : Nat) j)))))",
                Fixture.regular(3));
        f.def("bnot", "", "(-> Bool Bool)", "(fun (b : Bool) (Bool.casesOn (fun (x : Bool) Bool) b Bool.true Bool.false))", Fixture.regular(2));
        f.def("pick", "", "(-> Bool Nat Nat Nat)", "(fun (b : Bool) (x : Nat) (y : Nat) ((@ Bool.rec 1) (fun (c : Bool) Nat) y x b))", Fixture.regular(2));
        f.seal();
        ready = works(() -> f.conv(f.eval(f.term("two")), f.eval(f.term("four"))));
        if (!ready) {
            return;
        }
        k1 = Debug.parseSwitches("code,casetrees");
        int n = f.store.decls().constantCount();
        for (int row = 1; row <= n; row++) {
            int kind = f.store.decls().kind(row);
            if (kind == DeclKind.DEFN) {
                Elab.elaborate(f.m, row, Op.ROLE_BODY);
                Elab.elaborate(f.m, row, Op.ROLE_RULES);
            } else if (kind == DeclKind.REC) {
                Elab.elaborate(f.m, row, Op.ROLE_RULE);
            }
        }
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

    /** WHNF of the term with the given switches on a freshly reset machine; a failure is part of the answer. */
    private static String whnf(String sexp, int switches) {
        int t = f.term(sexp);
        f.m.reset();
        // `n0 + 2^64` is unary recursion on the literal (the test prelude's Nat.add, as in the official kernel): such a
        // row ends as E_STEPS, in both tiers
        f.m.beginDecl(Integer.MAX_VALUE, -1, 200_000, Long.MAX_VALUE);
        f.m.sw = switches;
        try {
            return f.show(f.whnf(f.eval(t)));
        } catch (Fixture.MachineFailure e) {
            return "fails with " + rlvm.api.MachineErrors.name(e.code);
        } finally {
            f.m.sw = 0;
        }
    }

    private static void same(String sexp) {
        assertEquals(whnf(sexp, 0), whnf(sexp, k1), "K0 and K1 differ on " + sexp);
    }

    @Test
    void theRolesAreElaborated() {
        assumeTrue(ready, "the machine is not complete in this tree");
        assertTrue(f.m.x7.elaborated[Op.ROLE_BODY] > 10, "bodies elaborated: " + f.m.x7.elaborated[Op.ROLE_BODY]);
        assertTrue(f.m.x7.elaborated[Op.ROLE_RULE] > 5, "recursors elaborated: " + f.m.x7.elaborated[Op.ROLE_RULE]);
        assertTrue(f.m.x7.elaborated[Op.ROLE_RULES] >= 6, "case trees elaborated: " + f.m.x7.elaborated[Op.ROLE_RULES]);
    }

    private static final String NATREC = "((@ Nat.rec 1) (fun (x : Nat) Nat)";
    private static final String[] NATS = {"(lit 0)", "(lit 1)", "(lit 7)", "Nat.zero", "(Nat.succ n0)", "(Nat.succ (Nat.succ n0))", "n0",
        "(f n0)", "(dbl (lit 2))", "(Nat.pow (lit 2) (lit 64))", "two", "zeroDef", "(Nat.succ zeroDef)", "(npred (lit 5))", "(lit 2147483648)",
        "(Nat.add n0 (lit 2))", "(Nat.sub (lit 3) (lit 5))"};
    private static final String[] BOOLS = {"Bool.true", "Bool.false", "(Nat.beq (lit 1) (lit 1))", "(Nat.ble (lit 2) (lit 1))", "(fb n0)",
        "(isZero (lit 0))", "(isZero (Nat.succ n0))", "(isZero n0)", "(bnot (fb n0))", "(bnot Bool.false)"};

    /** ISA 13.6 M3: "case trees equal DELTA + IOTA on random arguments". */
    @Test
    void caseTreesEqualDeltaPlusIotaOnRandomArguments() {
        assumeTrue(ready, "the machine is not complete in this tree");
        Random rnd = new Random(20261124);
        String[] shapes = {
            "(isZero N)", "(npred N)", "(npred2 N)", "(bnot B)", "(pick B N N)", "(npred (npred2 N))", "(pick (isZero N) N (npred N))",
            "(Nat.casesOn (fun (x : Nat) Nat) N (lit 100) (fun (k : Nat) (Nat.succ k)))",
            "(Nat.casesOn (fun (x : Nat) Nat) N N (fun (k : Nat) (Nat.add k N)))",
            // over-application: the alternative returns a function
            "(Nat.casesOn (fun (x : Nat) (-> Nat Nat)) N (fun (y : Nat) y) (fun (k : Nat) (y : Nat) (Nat.add k y)) N)",
            "(Bool.casesOn (fun (x : Bool) Nat) B N N)",
            "(Bool.casesOn (fun (x : Bool) (-> Nat Bool)) B isZero (fun (y : Nat) (bnot (isZero y))) N)",
            // too few arguments for the tree: plain DELTA
            "(Nat.casesOn (fun (x : Nat) Nat) N)", "(pick B)",
        };
        int rows = 0;
        for (int i = 0; i < 80; i++) {
            String s = shapes[rnd.nextInt(shapes.length)];
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < s.length(); j++) {
                char c = s.charAt(j);
                boolean hole = (c == 'N' || c == 'B') && s.charAt(j - 1) == ' ' && (j + 1 == s.length() || s.charAt(j + 1) == ' ' || s.charAt(j + 1) == ')');
                sb.append(!hole ? String.valueOf(c) : c == 'N' ? NATS[rnd.nextInt(NATS.length)] : BOOLS[rnd.nextInt(BOOLS.length)]);
            }
            same(sb.toString());
            rows++;
        }
        assertEquals(80, rows);
    }

    @Test
    void reductionRowsAgreeAcrossTiers() {
        assumeTrue(ready, "the machine is not complete in this tree");
        String list = "((@ List 0) Nat)";
        String listRec = "((@ List.rec 1 0) Nat (fun (l : " + list + ") Nat) (lit 0) (fun (h : Nat) (t : " + list + ") (ih : Nat) (Nat.add h ih))";
        String prod = "((@ Prod 0 0) Nat Bool)";
        String[] rows = {
            "two", "four", "twoAlias", "pThm", "opaqueTwo", "((@ id 1) Nat n0)", "((@ id 1) (-> Nat Nat) f n0)", "(dbl (dbl (lit 2)))",
            "(Nat.add n0 (lit 2))", "(Nat.add (lit 2) n0)", "(Nat.succ (dbl (lit 2)))", "(Nat.ble (Nat.succ (dbl (lit 2))) (lit 7))",
            NATREC + " Nat.zero (fun (x : Nat) (ih : Nat) (Nat.succ ih)) (lit 3))", NATREC + " (lit 7) (fun (x : Nat) (ih : Nat) ih) (lit 50))",
            NATREC + " (lit 0) (fun (x : Nat) (ih : Nat) x) (Nat.succ (Nat.succ n0)))", NATREC + " (lit 0) (fun (x : Nat) (ih : Nat) x) (Nat.pow (lit 2) (lit 64)))",
            NATREC + " Nat.zero (fun (x : Nat) (ih : Nat) (Nat.succ ih)) n0)",
            "((@ Nat.rec 1) (fun (x : Nat) (-> Nat Nat)) (fun (y : Nat) y) (fun (x : Nat) (ih : (-> Nat Nat)) (y : Nat) (Nat.succ y)) (lit 1) (lit 5))",
            "((@ Bool.rec 1) (fun (b : Bool) Nat) (lit 10) (lit 20) (Nat.beq (lit 1) (lit 2)))",
            listRec + " ((@ List.cons 0) Nat (lit 7) ((@ List.cons 0) Nat (lit 5) ((@ List.nil 0) Nat))))",
            "((@ Eq.rec 1 1) Nat n0 (fun (b : Nat) (h : ((@ Eq 1) Nat n0 b)) Nat) (lit 5) n0 eqn)",
            "((@ Eq.rec 1 1) Nat n0 (fun (b : Nat) (h : ((@ Eq 1) Nat n0 b)) Nat) (lit 5) (f n0) eqn2)",
            "((@ Prod.rec 1 0 0) Nat Bool (fun (p : " + prod + ") Nat) (fun (a : Nat) (b : Bool) a) pr)",
            "((@ Prod.rec 1 0 0) Nat Bool (fun (p : " + prod + ") Nat) (fun (a : Nat) (b : Bool) a) prDef)",
            "((@ Unit.rec 1) (fun (u : Unit) Nat) (lit 3) u0)", "(proj Prod 0 prDef2)", "(proj Prod 0 pr)",
            "((@ Quot.lift 1 1) Nat r Nat f hr ((@ Quot.mk 1) Nat r n0))", "(Nat.pow (lit 2) (lit 4294967296))",
        };
        for (String row : rows) {
            same(row);
        }
    }

    /** The hooks really run code objects, and within one session they return the very same value ids. */
    @Test
    void deltaAndIotaThroughBlocksGiveTheSameIds() {
        assumeTrue(ready, "the machine is not complete in this tree");
        f.m.reset();
        f.m.stats = true;
        try {
            String[] deltas = {"(dbl (lit 3))", "(dbl n0)", "((@ id 1) Nat n0)", "((@ id 1) (-> Nat Nat) f n0)", "(isZero n0)", "(pick Bool.true n0 two)",
                "(Nat.casesOn (fun (x : Nat) Nat) n0 (lit 100) (fun (k : Nat) (Nat.succ k)))", "(Nat.add n0 (lit 2))"};
            for (String s : deltas) {
                int v = f.eval(f.term(s));
                int r0 = f.delta(v);
                Values.setMemo(f.m, v, Layout.M_UNF - Layout.M0, 0);
                long acts = f.m.calls[Op.ACT];
                f.m.sw = Op.SW_CODE;
                int r1 = f.delta(v);
                f.m.sw = 0;
                assertEquals(r0, r1, "DELTA through BODY gives another value on " + s + ": " + f.show(r0) + " / " + f.show(r1));
                assertTrue(f.m.calls[Op.ACT] > acts, "no block ran for " + s);
            }
            String[] iotas = {NATREC + " Nat.zero (fun (x : Nat) (ih : Nat) (Nat.succ ih)) (lit 3))", NATREC + " (lit 7) (fun (x : Nat) (ih : Nat) ih) Nat.zero)",
                NATREC + " (lit 0) (fun (x : Nat) (ih : Nat) x) (Nat.succ (Nat.succ n0)))", "((@ Bool.rec 1) (fun (b : Bool) Nat) (lit 10) (lit 20) Bool.true)",
                "((@ Nat.rec 1) (fun (x : Nat) (-> Nat Nat)) (fun (y : Nat) y) (fun (x : Nat) (ih : (-> Nat Nat)) (y : Nat) (Nat.succ y)) (lit 1) (lit 5))",
                "((@ Prod.rec 1 0 0) Nat Bool (fun (p : ((@ Prod 0 0) Nat Bool)) Nat) (fun (a : Nat) (b : Bool) a) pr)"};
            for (String s : iotas) {
                int v = f.eval(f.term(s));
                int r0 = f.run(Reduce.iota(f.m, v, 0));
                long acts = f.m.calls[Op.ACT];
                f.m.sw = Op.SW_CODE;
                int r1 = f.run(Reduce.iota(f.m, v, 0));
                f.m.sw = 0;
                assertEquals(r0, r1, "IOTA through RULE gives another value on " + s + ": " + f.show(r0) + " / " + f.show(r1));
                assertTrue(f.m.calls[Op.ACT] > acts, "no rule block ran for " + s);
            }
            // a case tree fires in the WHNF loop (and nothing of it in strict mode)
            long acts = f.m.calls[Op.ACT];
            f.m.sw = Op.SW_CASETREES;
            assertEquals("Bool.false", f.show(f.whnf(f.eval(f.term("(isZero (Nat.succ (f n0)))")))));
            assertTrue(f.m.calls[Op.ACT] > acts, "no case tree ran");
            acts = f.m.calls[Op.ACT];
            f.m.strict = true;
            assertEquals("Bool.true", f.show(f.whnf(f.eval(f.term("(isZero (Nat.sub (lit 2) (lit 3)))")))));
            assertEquals(acts, f.m.calls[Op.ACT], "a code object ran in strict mode");
        } finally {
            f.m.strict = false;
            f.m.sw = 0;
            f.m.stats = false;
            f.m.reset();
        }
    }

    /** With the switches on, every frame of the hooks is exercised under host depth 0 as well. */
    @Test
    void tiersAgreeUnderDepthZero() {
        assumeTrue(ready, "the machine is not complete in this tree");
        f.hostDepth(0);
        try {
            for (String row : new String[] {"(npred2 (dbl (lit 2)))", "(pick (isZero (npred (lit 1))) (dbl two) n0)", "(dbl (dbl (lit 2)))",
                NATREC + " Nat.zero (fun (x : Nat) (ih : Nat) (Nat.succ ih)) (lit 6))",
                "(Nat.casesOn (fun (x : Nat) (-> Nat Nat)) (Nat.succ n0) (fun (y : Nat) y) (fun (k : Nat) (y : Nat) (Nat.add k y)) (lit 4))",
                "((@ Prod.rec 1 0 0) Nat Bool (fun (p : ((@ Prod 0 0) Nat Bool)) Nat) (fun (a : Nat) (b : Bool) a) pr)"}) {
                String d0 = whnf(row, k1);
                f.hostDepth(Op.HOST_DEPTH);
                assertEquals(whnf(row, 0), d0, "K1 under D = 0 differs from K0 on " + row);
                f.hostDepth(0);
            }
        } finally {
            f.hostDepth(Op.HOST_DEPTH);
        }
    }
}
