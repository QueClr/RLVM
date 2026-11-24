package rlvm.ref;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ServiceLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import rlvm.api.Budget;
import rlvm.api.EngineConfig;
import rlvm.api.EngineProvider;
import rlvm.api.Outcome;
import rlvm.api.Reason;
import rlvm.api.Session;

/** {@code checkConstant} / {@code Session.check}: the official add_axiom / add_definition / add_theorem / add_opaque. */
class EngineTest {

    /** Deeper than the kernel's own limit ({@code RefKernel.DEEP_DEPTH}). */
    private static final int TOO_DEEP = RefKernel.DEEP_DEPTH + 1000;

    private Fixture fx;
    private Session session;

    @BeforeEach
    void setUp() {
        fx = Fixture.standard(f -> {
            f.def("badDef", "", "Nat", "Bool.true", Fixture.regular(1));
            f.def("illTypedValue", "", "Nat", "(Nat.succ Bool.true)", Fixture.regular(1));
            f.thm("nonPropThm", "", "Nat", "Nat.zero");
            f.axiom("nonTypeAxiom", "", "Nat.zero");
            f.axiom("typeAxiomNeedsWhnf", "", "((@ id 2) Type Nat)");
            f.def("undeclaredLevel", "", "(sort (succ v))", "(sort v)", Fixture.regular(1));
            f.def("polyOk", "u v", "(sort (succ (max u v)))", "(sort (max v u))", Fixture.regular(1));
            f.def("selfRef", "", "Nat", "selfRef", Fixture.regular(1));
            f.thm("goodThm", "", "((@ Eq 1) Nat four (lit 4))", "((@ Eq.refl 1) Nat (lit 4))");
            f.thm("badThm", "", "((@ Eq 1) Nat four (lit 5))", "((@ Eq.refl 1) Nat (lit 4))");
            f.opaque("goodOpaque", "", "Nat", "(Nat.add two two)");
            f.opaque("badOpaque", "", "Bool", "(Nat.add two two)");
            f.def("looseValue", "", "Nat", "#0", Fixture.regular(1));
            f.thm("stuckSortThm", "", "((@ id 2) Type Nat)", "Nat.zero");
            f.deepSucc("deep", 100_000);
            f.deepSucc("tooDeep", TOO_DEEP);
        });
        session = fx.engine.newSession(fx.ctx);
    }

    @AfterEach
    void tearDown() {
        session.close();
        fx.engine.close();
    }

    private void accept(String name) {
        int out = session.check(fx.cid(name), Budget.cold(1L << 32));
        assertEquals(Outcome.ACCEPT, out, () -> name + ": " + session.lastReason() + " " + session.lastMessage());
    }

    private void reject(String name, Reason reason) {
        int out = session.check(fx.cid(name), Budget.cold(1L << 32));
        assertEquals(Outcome.REJECT, out, () -> name + ": " + session.lastReason() + " " + session.lastMessage());
        assertEquals(reason, session.lastReason(), () -> session.lastMessage());
        assertNotNull(session.lastMessage());
    }

    @Test
    void everyDeclarationOfThePreludeIsAccepted() {
        for (String name : fx.checkableNames()) {
            if (fx.isExtra(name)) {
                continue;
            }
            accept(name);
        }
    }

    @Test
    void definitions() {
        accept("two");
        accept("four");
        accept("id");
        accept("Nat.add");
        accept("polyOk");
        reject("badDef", Reason.DECL_TYPE_MISMATCH);
        reject("illTypedValue", Reason.APP_TYPE_MISMATCH);
        reject("undeclaredLevel", Reason.UNDECLARED_LEVEL_PARAM);
        reject("looseValue", Reason.LOOSE_BVAR);
    }

    @Test
    void aDefinitionIsNotVisibleInItsOwnValue() {
        reject("selfRef", Reason.UNKNOWN_CONSTANT);
    }

    @Test
    void theorems() {
        accept("pThm");
        accept("goodThm");
        reject("badThm", Reason.DECL_TYPE_MISMATCH);
        reject("nonPropThm", Reason.THEOREM_NOT_PROP);
        // ((id Type Nat) : Type): a type, but not a proposition
        reject("stuckSortThm", Reason.THEOREM_NOT_PROP);
    }

    @Test
    void axiomsAndOpaques() {
        accept("P");
        accept("typeAxiomNeedsWhnf");
        reject("nonTypeAxiom", Reason.TYPE_EXPECTED);
        accept("goodOpaque");
        reject("badOpaque", Reason.DECL_TYPE_MISMATCH);
    }

    @Test
    void verdictsDoNotDependOnOrderOrRepetition() {
        reject("badDef", Reason.DECL_TYPE_MISMATCH);
        accept("four");
        reject("badThm", Reason.DECL_TYPE_MISMATCH);
        accept("goodThm");
        accept("four");
        reject("badDef", Reason.DECL_TYPE_MISMATCH);
    }

    @Test
    void inductiveMembersAreNotForTheEngine() {
        assertEquals(Outcome.DECLINE, session.check(fx.cid("Nat"), Budget.warm()));
        assertEquals(Reason.UNSUPPORTED_INPUT, session.lastReason());
        assertEquals(Outcome.DECLINE, session.check(fx.cid("Nat.rec"), Budget.warm()));
    }

    @Test
    void deepTermsAreCheckedOnTheDeepStack() {
        accept("deep");
        // the session is still usable afterwards
        accept("four");
        reject("badDef", Reason.DECL_TYPE_MISMATCH);
    }

    @Test
    void depthBeyondTheLimitIsADeclineNeverAnError() {
        int out = session.check(fx.cid("tooDeep"), Budget.cold(1L << 32));
        assertEquals(Outcome.DECLINE, out);
        assertEquals(Reason.STACK_DEPTH, session.lastReason());
        accept("four");
    }

    @Test
    void depthBudgetIsHonoured() {
        int out = session.check(fx.cid("deep"), Budget.cold(1L << 32).withMaxDepth(1000));
        assertEquals(Outcome.DECLINE, out);
        assertEquals(Reason.STACK_DEPTH, session.lastReason());
    }

    @Test
    void stepBudgetIsHonoured() {
        int out = session.check(fx.cid("deep"), Budget.strict(5_000L, 1L << 32));
        assertEquals(Outcome.DECLINE, out);
        assertEquals(Reason.BUDGET_STEPS, session.lastReason());
        accept("four");
    }

    @Test
    void sessionAccounting() {
        accept("four");
        assertTrue(session.steps() > 0);
        assertTrue(session.footprintBytes() > 0);
        session.reset();
        accept("four");
    }

    @Test
    void providerIsRegistered() {
        EngineProvider found = null;
        for (EngineProvider p : ServiceLoader.load(EngineProvider.class)) {
            if (p.name().equals("reference")) {
                found = p;
            }
        }
        assertNotNull(found);
        assertTrue(found.isReference());
        assertEquals("reference", found.factory().create(fx.store, EngineConfig.defaults()).name());
    }
}
