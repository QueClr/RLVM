package rlvm.admit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import rlvm.api.Budget;
import rlvm.api.KernelException;
import rlvm.api.Outcome;
import rlvm.api.Reason;
import rlvm.store.DeclKind;

/**
 * The per-unit flow with a fake engine: structural failures never reach the engine, engine outcomes
 * pass through, inductive and quot units report what the environment pass recorded.
 */
class AdmissionTest {

    private static final Budget BUDGET = Budget.cold(1L << 30);

    /** Runs the whole environment pass first (as an environment thread would), then admits with the 2-argument form. */
    private static Admission afterEnv(Fixture f) {
        EnvBuilderTest.Pass p = new EnvBuilderTest.Pass(f);
        p.all();
        return new Admission(f.ctx, Policy.arena());
    }

    @Test
    void acceptedByTheEngine() {
        Fixture f = new Fixture();
        int type1 = f.sort(f.succ(f.zero()));
        int a = f.axiom(f.name("A"), f.names(), type1);
        int d = f.def(f.name("d"), f.names(), type1, f.cnst(f.name("A")));
        int t = f.thm(f.name("t"), f.names(), f.sort(f.zero()), f.cnst(f.name("A")));
        int o = f.opaque(f.name("o"), f.names(), type1, f.cnst(f.name("d")));
        f.finish();
        Admission adm = afterEnv(f);
        FakeSession s = new FakeSession();
        for (int unit = 0; unit < 4; unit++) {
            assertEquals(Outcome.ACCEPT, adm.admit(unit, s, BUDGET));
            assertNull(adm.lastReason());
        }
        assertEquals(4, s.checks);
        assertEquals(a, s.checked[0]);
        assertEquals(d, s.checked[1]);
        assertEquals(t, s.checked[2]);
        assertEquals(o, s.checked[3]);
    }

    @Test
    void engineOutcomesPassThrough() {
        Fixture f = new Fixture();
        f.def(f.name("badDef"), f.names(), f.sort(f.zero()), f.sort(f.succ(f.zero())));
        f.finish();
        Admission adm = afterEnv(f);
        FakeSession s = new FakeSession();
        s.outcome = Outcome.REJECT;
        s.reason = Reason.DECL_TYPE_MISMATCH;
        s.message = "declaration type mismatch";
        assertEquals(Outcome.REJECT, adm.admit(0, s, BUDGET));
        assertEquals(Reason.DECL_TYPE_MISMATCH, adm.lastReason());
        assertEquals("declaration type mismatch", adm.lastMessage());

        s.outcome = Outcome.DECLINE;
        s.reason = Reason.BUDGET_STEPS;
        assertEquals(Outcome.DECLINE, adm.admit(0, s, BUDGET));
        assertEquals(Reason.BUDGET_STEPS, adm.lastReason());

        // an engine whose reason does not match its outcome is not trusted with a verdict
        s.outcome = Outcome.REJECT;
        s.reason = Reason.BUDGET_STEPS;
        assertEquals(Outcome.DECLINE, adm.admit(0, s, BUDGET));
        assertEquals(Reason.INTERNAL_ERROR, adm.lastReason());
        s.outcome = 7;
        s.reason = null;
        assertEquals(Outcome.DECLINE, adm.admit(0, s, BUDGET));
        assertEquals(Reason.INTERNAL_ERROR, adm.lastReason());
    }

    /**
     * The arena reject tests whose reason is structural, as hand-built stores: tutorial 014, 019, 140,
     * 141, bugs/loose-bvar-value, bugs/constlevels. None of them reaches the engine.
     */
    @Test
    void structuralRejectionsNeverReachTheEngine() {
        Fixture f = new Fixture();
        int u = f.name("u");
        int prop = f.sort(f.zero());
        int type1 = f.sort(f.succ(f.zero()));
        int sortU = f.sort(f.param(u));
        // unit 0: False (an axiom here), unit 1: an unsafe definition that is dropped
        int falseName = f.name("False");
        f.axiom(falseName, f.names(), prop);
        int loop = f.name("unsafeLoop");
        int loopRef = f.cnst(loop);
        f.dropUnsafe(loop);
        int first = f.store.decls().unitCount();
        // 014 selfProof
        int selfName = f.name("selfProof");
        int self = f.open(DeclKind.THM, selfName, f.names());
        f.close(self, f.pi(prop, f.bvar(0)), f.cnst(selfName));
        // 019 tut06_bad01
        f.def(f.name("tut06_bad01"), f.names(u, u), type1, prop);
        // 140 / 141
        f.thm(f.name("falseFromUnsafe"), f.names(), f.cnst(falseName), loopRef);
        // bugs/loose-bvar-value
        f.def(f.name("f"), f.names(u), f.pi(sortU, sortU), f.bvar(0));
        // bugs/constlevels
        f.def(f.name("levels"), f.names(), prop, f.let(prop, f.cnst(falseName, f.zero()), f.bvar(0)));
        f.finish();
        Admission adm = afterEnv(f);
        FakeSession s = new FakeSession();
        Reason[] expected = {Reason.UNKNOWN_CONSTANT, Reason.DUPLICATE_LEVEL_PARAM, Reason.UNKNOWN_CONSTANT,
            Reason.LOOSE_BVAR, Reason.LEVEL_ARITY_MISMATCH};
        String[] mention = {"selfProof", "tut06_bad01", "unsafeLoop", "'f'", "False"};
        for (int k = 0; k < expected.length; k++) {
            assertEquals(Outcome.REJECT, adm.admit(first + k, s, BUDGET), "unit " + (first + k));
            assertEquals(expected[k], adm.lastReason());
            assertTrue(adm.lastMessage().contains(mention[k]), adm.lastMessage());
        }
        assertEquals(0, s.checks, "the engine was never asked");
        if (first == 2) { // the dropped record occupies a unit of its own: skipped, not checked
            assertEquals(Outcome.ACCEPT, adm.admit(1, s, BUDGET));
            assertEquals(0, s.checks);
        }
    }

    @Test
    void proofBlockIsCheckedBeforeTheEngine() {
        Fixture f = new Fixture();
        int prop = f.sort(f.zero());
        int p = f.name("P");
        f.axiom(p, f.names(), prop);
        int refP = f.cnst(p);
        int selfName = f.name("self");
        f.thmBlock(selfName, f.names(), refP, w -> {
            w.constant(f.store.names().constOf(selfName), 0, selfName);
            return 0;
        });
        int h = f.name("h");
        f.axiom(h, f.names(), refP);
        int ok = f.thmBlock(f.name("ok"), f.names(), refP, w -> {
            w.constant(f.store.names().constOf(h), 0, h);
            return 0;
        });
        f.finish();
        Admission adm = afterEnv(f);
        FakeSession s = new FakeSession();
        assertEquals(Outcome.REJECT, adm.admit(1, s, BUDGET));
        assertEquals(Reason.UNKNOWN_CONSTANT, adm.lastReason());
        assertEquals(0, s.checks);
        assertEquals(Outcome.ACCEPT, adm.admit(3, s, BUDGET));
        assertEquals(ok, s.lastCid);
    }

    @Test
    void unitsBeyondTheFrontierAreNeverChecked() {
        Fixture f = new Fixture();
        f.axiom(f.name("A"), f.names(), f.sort(f.zero()));
        f.axiom(f.name("B"), f.names(), f.sort(f.zero()));
        f.finish();
        EnvBuilderTest.Pass p = new EnvBuilderTest.Pass(f);
        p.env.advanceTo(1);
        Admission adm = new Admission(f.ctx, Policy.arena());
        FakeSession s = new FakeSession();
        assertEquals(Outcome.ACCEPT, adm.admit(0, s, BUDGET));
        assertEquals(Outcome.DECLINE, adm.admit(1, s, BUDGET));
        assertEquals(Reason.INTERNAL_ERROR, adm.lastReason());
        assertEquals(1, s.checks);
    }

    @Test
    void inductiveAndQuotUnitsReportTheRecordedOutcome() {
        Fixture f = new Fixture();
        EnvBuilderTest.unitLike(f, "Eq").end();
        f.quot(f.name("Quot"), f.names(f.name("u")), f.sort(f.zero()), DeclKind.QUOT_TYPE);
        EnvBuilderTest.unitLike(f, "Bad").end();
        f.axiom(f.name("after"), f.names(), f.sort(f.zero()));
        f.finish();
        EnvBuilderTest.Pass p = new EnvBuilderTest.Pass(f);
        int bad = f.cid("Bad");
        p.blocks.script = (in, out) -> {
            if (in.typeCid[0] == bad) {
                throw new KernelException(Reason.INDUCTIVE_INVALID);
            }
            EnvBuilderTest.derive(f, in, out);
        };
        assertEquals(2, p.all());
        Admission adm = new Admission(f.ctx, Policy.arena());
        FakeSession s = new FakeSession();
        assertEquals(Outcome.ACCEPT, adm.admit(0, s, BUDGET));
        assertEquals(Outcome.ACCEPT, adm.admit(1, s, BUDGET));
        assertEquals(Outcome.DECLINE, adm.admit(2, s, BUDGET), "the failed unit is not below the frontier");
        assertEquals(0, s.checks);
    }

    @Test
    void lazyEnvironmentPassDrivenByTheWorker() {
        Fixture f = new Fixture();
        int type1 = f.sort(f.succ(f.zero()));
        f.axiom(f.name("A"), f.names(), type1);
        EnvBuilderTest.unitLike(f, "I").end();
        f.def(f.name("d"), f.names(), type1, f.cnst(f.name("I")));
        EnvBuilderTest.unitLike(f, "Bad").end();
        f.axiom(f.name("after"), f.names(), type1);
        f.finish();
        EnvBuilderTest.Pass p = new EnvBuilderTest.Pass(f);
        int bad = f.cid("Bad");
        p.blocks.script = (in, out) -> {
            if (in.typeCid[0] == bad) {
                throw new KernelException(Reason.INDUCTIVE_INVALID);
            }
            EnvBuilderTest.derive(f, in, out);
        };
        Admission adm = new Admission(f.ctx, Policy.arena(), p.env);
        FakeSession s = new FakeSession();
        assertEquals(0, f.store.envUnits());
        assertEquals(Outcome.ACCEPT, adm.admit(0, s, BUDGET));
        assertEquals(1, f.store.envUnits(), "the pass advances exactly as far as needed");
        assertEquals(Outcome.ACCEPT, adm.admit(1, s, BUDGET));
        assertEquals(DeclKind.ST_ACCEPTED, f.store.decls().status(1));
        assertEquals(Outcome.ACCEPT, adm.admit(2, s, BUDGET));
        assertEquals(2, s.checks);
        // the failing block: the reason of the environment pass
        assertEquals(Outcome.REJECT, adm.admit(3, s, BUDGET));
        assertEquals(Reason.INDUCTIVE_INVALID, adm.lastReason());
        assertTrue(adm.lastMessage().contains("Bad"), adm.lastMessage());
        // a unit behind the failure is never checked
        assertEquals(Outcome.DECLINE, adm.admit(4, s, BUDGET));
        assertEquals(Reason.CANCELLED, adm.lastReason());
        assertEquals(2, s.checks);
        assertEquals(3, f.store.envUnits());
        // units below the failure can still be (re)checked
        assertEquals(Outcome.ACCEPT, adm.admit(2, s, BUDGET));
        assertEquals(3, s.checks);
    }

    @Test
    void axiomPolicy() {
        Fixture f = new Fixture();
        int prop = f.sort(f.zero());
        String[] axioms = {"propext", "Quot.sound", "Classical.choice", "sorryAx", "myAxiom", "Foo.sound", "A.B.choice"};
        for (String name : axioms) {
            f.axiom(f.name(name), f.names(), prop);
        }
        f.def(f.name("d"), f.names(), f.sort(f.succ(f.zero())), prop);
        f.finish();
        EnvBuilderTest.Pass p = new EnvBuilderTest.Pass(f);
        assertEquals(axioms.length + 1, p.all(), p.env.failureMessage());
        FakeSession s = new FakeSession();
        // the arena policy admits every well-typed axiom
        Admission arena = new Admission(f.ctx, Policy.arena());
        for (int unit = 0; unit <= axioms.length; unit++) {
            assertEquals(Outcome.ACCEPT, arena.admit(unit, s, BUDGET));
        }
        // the strict policy declines the non-standard ones, after the engine accepted them
        Policy strict = new Policy(false, Policy.ForwardRefs.DECLINE, Policy.Unconfirmed.DECLINE, true, false);
        Admission adm = new Admission(f.ctx, strict);
        s.checks = 0;
        for (int unit = 0; unit < 4; unit++) {
            assertEquals(Outcome.ACCEPT, adm.admit(unit, s, BUDGET), axioms[unit]);
        }
        for (int unit = 4; unit < axioms.length; unit++) {
            assertEquals(Outcome.DECLINE, adm.admit(unit, s, BUDGET), axioms[unit]);
            assertEquals(Reason.UNSUPPORTED_INPUT, adm.lastReason());
            assertFalse(adm.lastReason().isReject());
        }
        assertEquals(Outcome.ACCEPT, adm.admit(axioms.length, s, BUDGET), "definitions are not affected");
        assertEquals(axioms.length + 1, s.checks);
        // an ill-typed axiom is still a rejection under the strict policy
        s.outcome = Outcome.REJECT;
        s.reason = Reason.TYPE_EXPECTED;
        assertEquals(Outcome.REJECT, adm.admit(4, s, BUDGET));
    }
}
