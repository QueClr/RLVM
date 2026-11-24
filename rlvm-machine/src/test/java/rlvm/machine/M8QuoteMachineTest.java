package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import rlvm.store.IntLists;

/**
 * {@link Quote} on values the machine made itself: the constructors of M1, then {@code EVAL} (M2) and
 * {@code INFER} (M5). A row whose judgment is not published yet skips itself (it never asserts that a
 * stub still throws); the rows on hand-made records are in {@link M8QuoteTest}.
 */
@Tag("fast")
class M8QuoteMachineTest {

    private final Fixture f = Fixture.prelude0();
    private final Machine m = f.m;

    /** Skips the test if a judgment it needs is still a stub. */
    private static int need(java.util.function.IntSupplier judgment) {
        try {
            return judgment.getAsInt();
        } catch (UnsupportedOperationException e) {
            Assumptions.abort("not published yet: " + e.getMessage());
            return 0;
        }
    }

    private int ok(int r) {
        assertTrue(r < Op.SIG, () -> "signal " + r + " with error " + m.err);
        return r;
    }

    private int constant(String name) {
        return ok(need(() -> Values.mkConst(m, f.cid(name), IntLists.EMPTY)));
    }

    private int app(int head, int... args) {
        int xb = m.xp;
        assertTrue(m.ensureScratch(args.length));
        System.arraycopy(args, 0, m.X, xb, args.length);
        m.xp = xb + args.length;
        try {
            return ok(need(() -> Values.mkApp(m, head, xb, args.length)));
        } finally {
            m.xp = xb;
        }
    }

    private void same(String expected, int value) {
        int t = f.quote(value);
        assertEquals(expected, f.showTerm(t));
        assertEquals(f.term(expected), t);
    }

    @Test
    void valuesOfTheConstructors() {
        same("Nat.zero", constant("Nat.zero"));
        same("(@ Eq 1)", ok(need(() -> Values.mkConst(m, f.cid("Eq"), f.levels("1")))));
        same("Type", ok(need(() -> Values.mkSort(m, f.level("1")))));
        same("(g (f n0) n0)", app(constant("g"), app(constant("f"), constant("n0")), constant("n0")));
        // over-application extends the spine of a NEU head
        same("(g n0 n0)", app(app(constant("g"), constant("n0")), constant("n0")));
        same("(lit 7)", ok(need(() -> Values.mkNat(m, 7))));
        same("(lit 4294967296)", ok(need(() -> Values.mkNat(m, 1L << 32))));
        // Nat.succ: a literal and Nat.zero fold, anything else is one NSUC cell (ISA 2.1, 4.2 NSUCC)
        same("(lit 8)", ok(need(() -> Values.nsucc(m, Layout.imm(7)))));
        same("(lit 1)", app(constant("Nat.succ"), constant("Nat.zero")));
        same("(Nat.succ (Nat.succ (f n0)))", app(constant("Nat.succ"), app(constant("Nat.succ"), app(constant("f"), constant("n0")))));
        // a projection of a syntactic constructor is its field, of anything else a PROJ cell (4.2 MKPROJ)
        same("(proj Prod 1 pr)", ok(need(() -> Values.mkProj(m, f.cid("Prod"), 1, constant("pr")))));
        int abc = f.term("(str \"abc\")");
        same("(str \"abc\")", ok(need(() -> Values.mkStr(m, f.ctx.terms.a(abc)))));
    }

    @Test
    void aLiteralAbove2To63KeepsItsNumber() {
        int lit = f.term("(lit 340282366920938463463374607431768211456)"); // 2^128
        int v = ok(need(() -> Lits.klit(m, lit)));
        int t = f.quote(v);
        assertTrue(f.ctx.nats.natEquals(lit, t), () -> f.showTerm(t));
        assertEquals("(lit 340282366920938463463374607431768211456)", f.showTerm(t));
    }

    @Test
    void closuresOverPrunedFrames() {
        // environment [n0, f n0] (outermost first); the body reads both
        int a = constant("n0");
        int b = app(constant("f"), constant("n0"));
        m.ep = 0;
        assertTrue(m.ensureEnv(2));
        m.es[0] = a;
        m.es[1] = b;
        m.ep = 2;
        int lam = f.term("(fun (z : Nat) (g #1 #2))");
        int fr = ok(need(() -> Runs.prune(m, 0, 2, f.ctx.terms.fvMask(lam))));
        same("(fun (_ : Nat) (g (f n0) n0))", ok(need(() -> Values.mkLam(m, lam, fr, 0))));
        same("(pi (_ : Nat) ((@ Eq 1) Nat #0 (f n0)))", ok(need(() -> {
            int pi = f.term("(pi (z : Nat) ((@ Eq 1) Nat #0 #1))");
            return Values.mkPi(m, pi, Runs.prune(m, 0, 2, f.ctx.terms.fvMask(pi)), 0);
        })));
        // a body that reads only the outer binding: the inner slot is pruned to HOLE and never read
        int outer = f.term("(f #1)");
        int fr2 = ok(need(() -> Runs.prune(m, 0, 2, f.ctx.terms.fvMask(outer))));
        same("(f n0)", ok(need(() -> Values.suspRecord(m, outer, fr2, 0))));
        // a let suspension is another record and means the same term
        same("(f n0)", ok(need(() -> Values.mkSusp(m, outer, fr2, 0, true))));
        m.ep = 0;
    }

    @Test
    void aFarFrameContinuesInItsRestRun() {
        // 70 bindings: the literals 0 .. 69, outermost first; index i is binding 69 - i
        m.ep = 0;
        assertTrue(m.ensureEnv(70));
        for (int i = 0; i < 70; i++) {
            m.es[i] = Layout.imm(i);
        }
        m.ep = 70;
        int t = f.term("(g #0 #65)");
        long mask = f.ctx.terms.fvMask(t);
        assertTrue(mask < 0, "an index above 62 sets the far bit of the node mask");
        int fr = ok(need(() -> Runs.prune(m, 0, 70, mask)));
        assertTrue(Runs.rest(m, fr) != 0, "a far frame");
        same("(g (lit 69) (lit 4))", ok(need(() -> Values.suspRecord(m, t, fr, 0))));
        int t2 = f.term("(g #62 #63)");
        same("(g (lit 7) (lit 6))", ok(need(() -> Values.suspRecord(m, t2, Runs.prune(m, 0, 70, f.ctx.terms.fvMask(t2)), 0))));
        m.ep = 0;
    }

    @Test
    void evalThenQuoteIsTheNormalFormOfTheTerm() {
        // weak-head normal, inert terms come back as themselves
        for (String s : new String[] {"Nat.zero", "(g (f n0) n0)", "(fun (_ : Nat) (f #0))", "(pi (_ : Nat) ((@ Eq 1) Nat #0 n0))",
            "(lit 12)", "Type", "(proj Prod 0 pr)", "((@ Prod.mk 0 0) Nat Bool n0 Bool.true)"}) {
            int t = f.term(s);
            int v = need(() -> f.eval(t));
            assertEquals(s, f.showTerm(f.quote(v)), s);
        }
        // beta, zeta and projection of a constructor are done by EVAL (ISA 0.2 decision 7); delta is not
        same("(f n0)", need(() -> f.eval(f.term("((fun (x : Nat) (f x)) n0)"))));
        same("(g n0 n0)", need(() -> f.eval(f.term("(let (x : Nat := n0) (g x x))"))));
        same("n0", need(() -> f.eval(f.term("(proj Prod 0 ((@ Prod.mk 0 0) Nat Bool n0 Bool.true))"))));
        same("two", need(() -> f.eval(f.term("two"))));
        // under the fixture's context the variables are the loose variables of the text
        int x = need(() -> f.fvar("x", "Nat"));
        int y = f.fvar("y", "Nat");
        assertTrue(x != y);
        same("(g #1 #0)", f.eval(f.term("(g x y)")));
        same("(fun (_ : Nat) (g #2 #0))", f.eval(f.term("(fun (z : Nat) (g x z))")));
        f.closeContext();
    }

    @Test
    void theTypeOfALambdaIsReadBackThroughInst() {
        // INFER of a lambda in ONLY mode is a PII (ISA 4.5.1); its readback opens the binder and asks INST
        int lam = f.term("(fun (x : Nat) (f x))");
        int ty = need(() -> f.inferOnly(lam));
        long before = Quote.of(m).judgments;
        int t = need(() -> f.quote(ty));
        assertEquals("(pi (_ : Nat) Nat)", f.showTerm(t));
        if (Values.kind(m, ty) == Layout.PII) {
            assertTrue(Quote.of(m).judgments > before, "a PII is read back with DOM / OPEN / INST");
        }
        assertEquals(0, m.ssp, "the binder opened for the readback is closed again");
        // dependent: fun (α : Type) (a : α) => a  :  (α : Type) -> α -> α
        int dep = need(() -> f.inferOnly(f.term("(fun (α : Type) (a : α) a)")));
        assertEquals("(pi (_ : Type) (pi (_ : #0) #1))", f.showTerm(f.quote(dep)));
    }
}
