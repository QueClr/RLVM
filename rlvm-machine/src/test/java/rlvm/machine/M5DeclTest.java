package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import rlvm.api.Budget;
import rlvm.api.Outcome;
import rlvm.api.Reason;

/**
 * The DECL driver (ISA 4.5.6, 14.1; official {@code add_axiom / add_definition / add_theorem / add_opaque},
 * R03 4.2) on hand-built stores, through the facade ({@code Session.check}). The structural checks of
 * admission are not run here, so the rows also show that the machine fails closed without them.
 */
@Tag("fast")
class M5DeclTest {

    static final Budget BUDGET = new Budget(50_000_000L, 1L << 30, Integer.MAX_VALUE);

    @BeforeAll
    static void ready() {
        assumeTrue(M5Support.ready(), "EVAL / WHNF / CONV are not implemented in this tree yet");
    }

    static Fixture fixture() {
        Fixture f = Fixture.prelude();
        // accepted
        f.axiom("okAx", "u", "(pi (α : (sort u)) (a : α) ((@ Eq u) α a a))");
        f.def("okDef", "", "(-> Nat Nat)", "(fun (x : Nat) (Nat.succ x))", Fixture.regular(1));
        f.def("okDefConv", "", "NatAlias", "(Nat.succ Nat.zero)", Fixture.regular(2));
        f.def("okPoly", "u", "(pi (α : (sort u)) (a : α) ((@ Eq u) α a a))", "(fun (α : (sort u)) (a : α) ((@ Eq.refl u) α a))",
                Fixture.regular(1));
        f.thm("okThm", "", "((@ Eq 1) Nat (Nat.succ Nat.zero) (Nat.succ Nat.zero))", "((@ Eq.refl 1) Nat (Nat.succ Nat.zero))");
        f.thm("okThmPi", "", "(pi (n : Nat) ((@ Eq 1) Nat n n))", "(fun (n : Nat) ((@ Eq.refl 1) Nat n))");
        f.opaque("okOpaque", "", "Nat", "(Nat.succ two)");
        f.def("okLet", "", "Nat", "(let (x : Nat := two) (Nat.succ x))", Fixture.regular(2));
        f.def("okProj", "", "Nat", "(proj Prod 0 pr)", Fixture.regular(1));
        // rejected, one per throw site of check_constant_val / add_definition / add_theorem
        f.axiom("badAxNotType", "", "Nat.zero");
        f.axiom("badAxIllTyped", "", "(Nat.zero Nat.zero)");
        f.axiom("badAxArg", "", "((@ Eq 1) Nat Nat.zero Bool.true)");
        f.def("badDefType", "", "Nat", "Bool.true", Fixture.regular(1));
        f.def("badDefValue", "", "Nat", "(Nat.succ Bool.true)", Fixture.regular(1));
        f.def("badDefLam", "", "(-> Nat Bool)", "(fun (x : Nat) x)", Fixture.regular(1));
        f.def("badDefLet", "", "Nat", "(let (x : Nat := Bool.true) Nat.zero)", Fixture.regular(1));
        f.def("badDefProj", "", "Nat", "(proj Prod 2 pr)", Fixture.regular(1));
        f.thm("badThmNotProp", "", "Nat", "Nat.zero");
        f.thm("badThmNotPropPi", "", "(-> Nat Nat)", "(fun (x : Nat) x)");
        f.thm("badThmProof", "", "((@ Eq 1) Nat Nat.zero (Nat.succ Nat.zero))", "((@ Eq.refl 1) Nat Nat.zero)");
        f.opaque("badOpaque", "", "Bool", "Nat.zero");
        // what the structural checks would have stopped: the machine alone must not accept any of it
        f.def("badLoose", "", "Nat", "#0", Fixture.regular(1));
        f.def("badLevel", "u", "(sort (succ v))", "(sort v)", Fixture.regular(1));
        f.def("usesLate", "", "Nat", "lateAx", Fixture.regular(1));
        f.axiom("lateAx", "", "Nat");
        return f;
    }

    static int check(Fixture f, String name) {
        return f.session.check(f.cid(name), BUDGET);
    }

    static void accepts(Fixture f, String name) {
        int o = check(f, name);
        assertEquals(Outcome.ACCEPT, o, () -> name + ": " + f.session.lastReason() + " " + f.session.lastMessage());
        assertTrue(f.m.idle(), name + ": the machine is idle after ACCEPT");
    }

    static void rejects(Fixture f, Reason reason, String name) {
        int o = check(f, name);
        assertEquals(Outcome.REJECT, o, () -> name + ": " + Outcome.name(o) + " " + f.session.lastMessage());
        assertEquals(reason, f.session.lastReason(), name);
        assertTrue(f.m.idle(), name + ": the machine is idle after REJECT");
    }

    @Test
    void acceptedDeclarations() {
        for (int depth : new int[] {Op.HOST_DEPTH, 0}) {
            Fixture f = fixture();
            f.hostDepth(depth);
            for (String name : new String[] {"okAx", "okDef", "okDefConv", "okPoly", "okThm", "okThmPi", "okOpaque", "okLet",
                "okProj", "two", "four", "id", "pThm", "opaqueTwo", "Nat.add", "prDef", "c1", "c2", "hr", "qm"}) {
                accepts(f, name);
            }
            f.close();
        }
    }

    @Test
    void rejectedDeclarations() {
        for (int depth : new int[] {Op.HOST_DEPTH, 0}) {
            Fixture f = fixture();
            f.hostDepth(depth);
            rejects(f, Reason.TYPE_EXPECTED, "badAxNotType");
            rejects(f, Reason.FUNCTION_EXPECTED, "badAxIllTyped");
            rejects(f, Reason.APP_TYPE_MISMATCH, "badAxArg");
            rejects(f, Reason.DECL_TYPE_MISMATCH, "badDefType");
            rejects(f, Reason.APP_TYPE_MISMATCH, "badDefValue");
            rejects(f, Reason.DECL_TYPE_MISMATCH, "badDefLam");
            rejects(f, Reason.LET_TYPE_MISMATCH, "badDefLet");
            rejects(f, Reason.INVALID_PROJECTION, "badDefProj");
            // theorem_type_is_not_prop: before the value is looked at
            rejects(f, Reason.THEOREM_NOT_PROP, "badThmNotProp");
            rejects(f, Reason.THEOREM_NOT_PROP, "badThmNotPropPi");
            rejects(f, Reason.DECL_TYPE_MISMATCH, "badThmProof");
            rejects(f, Reason.DECL_TYPE_MISMATCH, "badOpaque");
            // an accept after rejections: nothing of a failed unit survives
            accepts(f, "okThm");
            f.close();
        }
    }

    @Test
    void withoutTheStructuralChecksTheMachineStillFailsClosed() {
        Fixture f = fixture();
        f.seal();
        // a loose bound variable in a root
        rejects(f, Reason.LOOSE_BVAR, "badLoose");
        // an undeclared universe parameter: the root summaries do not pass, CHKLVL runs per node
        rejects(f, Reason.UNDECLARED_LEVEL_PARAM, "badLevel");
        // a reference to a constant that is declared later (an unbound node when it was written): a
        // decline, never a verdict of the machine (ISA section 9)
        assertEquals(Outcome.DECLINE, check(f, "usesLate"));
        assertTrue(f.store.decls().rank(f.cid("lateAx")) > f.store.decls().rank(f.cid("usesLate")));
        // a row that an engine does not check
        assertEquals(Outcome.DECLINE, f.session.check(f.cid("Nat"), BUDGET));
        assertEquals(Outcome.DECLINE, f.session.check(f.cid("Nat.rec"), BUDGET));
        f.close();
    }

    @Test
    void aStepBudgetDeclinesAndNeverAccepts() {
        Fixture f = fixture();
        f.seal();
        int o = f.session.check(f.cid("okThmPi"), new Budget(3L, 1L << 30, Integer.MAX_VALUE));
        assertEquals(Outcome.DECLINE, o);
        assertEquals(Reason.BUDGET_STEPS, f.session.lastReason());
        assertTrue(f.m.idle());
        accepts(f, "okThmPi");
        f.close();
    }

    /**
     * Warm sessions (staging step 1, switch {@code warm}): the session survives an accepted declaration, and
     * the check generation of ISA 5.3 stays while consecutive declarations pass the root summaries. A checked
     * type of an earlier declaration then answers a checking query of a later one; after a failure the
     * facade resets the session and the generation starts again at 1.
     */
    @Test
    void theGenerationRuleInAWarmSession() {
        Fixture f = fixture();
        f.switches("warm");
        accepts(f, "okDef");
        int gen = f.m.gen;
        long records = f.m.vp;
        accepts(f, "okPoly"); // declares u; the previous declaration declared nothing: contained, and both trusted
        assertEquals(gen, f.m.gen);
        assertTrue(f.m.vp > records, "the session was not reset between the two declarations");
        accepts(f, "okThmPi"); // declares nothing, okPoly declared u: still the same generation, both pass the summaries
        assertEquals(gen, f.m.gen);
        // the same declaration again is not later in file order: the facade starts a new session (I-SESSION)
        accepts(f, "okThmPi");
        assertEquals(1, f.m.gen);
        // a rejection resets the session; the next declaration starts at generation 1 with nothing trusted before it
        rejects(f, Reason.DECL_TYPE_MISMATCH, "badDefType");
        assertEquals(1, f.m.gen);
        assertTrue(!f.m.x5.prevTrusted, "nothing of the failed session survives");
        accepts(f, "okThm");
        f.close();
    }

    @Test
    void theRegimeOfIsa53IsFixedPerDeclaration() {
        Fixture f = fixture();
        f.seal();
        accepts(f, "okPoly");
        assertTrue(f.m.x5.trusted, "the roots of okPoly pass the summaries: CHKCONST / CHKLVL are no-ops");
        assertEquals(f.store.decls().levelParams(f.cid("okPoly")), f.m.x5.declLparams);
        check(f, "badLevel");
        // the facade resets the session after a failure; the regime of the next declaration is its own
        accepts(f, "okDef");
        assertTrue(f.m.x5.trusted);
        f.close();
    }
}
