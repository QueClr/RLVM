package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import rlvm.api.Budget;
import rlvm.api.EngineConfig;
import rlvm.api.MachineErrors;
import rlvm.api.Outcome;
import rlvm.api.Reason;
import rlvm.api.RunSignals;

/**
 * The facade rules of ISA section 14 on REAL declarations: the judgments of M1-M5 through
 * {@code Decl.check} (the toy-driver form of every rule is in {@link M6FacadeTest}). Not in the fast
 * group, so that it never blocks another package's publication; a judgment that is still a stub skips
 * the test (assumption), it never passes it.
 */
class M6RealDeclTest {

    private static final long BIG = M6FacadeTest.BIG;

    /**
     * A prelude with a declaration that needs tens of thousands of ticks: {@code slow : slowG 30000}, where
     * {@code slowG n : SlowF n} and {@code SlowF n} reduces to {@code Type} by n iota steps; ENSORT of the type of
     * {@code slow}'s type runs them.
     */
    private static Fixture slowDeclaration() {
        Fixture f = Fixture.prelude();
        f.def("SlowF", "", "(-> Nat (sort 2))",
                "(fun (n : Nat) ((@ Nat.rec 3) (fun (x : Nat) (sort 2)) Type (fun (x : Nat) (ih : (sort 2)) ih) n))", Fixture.regular(1));
        f.axiom("slowG", "(pi (n : Nat) (SlowF n))");
        f.axiom("quick", "(slowG (lit 3))");
        f.axiom("slow", "(slowG (lit 30000))");
        f.axiom("after", "Nat");
        f.seal();
        return f;
    }

    @Test
    void cancellationWithin4096TicksOnARealDeclaration() {
        for (int depth : new int[] {Op.HOST_DEPTH, 0}) {
            Fixture f = slowDeclaration();
            f.hostDepth(depth);
            MachineSession s = f.session;
            int slow = f.cid("slow");
            int unit = f.store.decls().unit(slow);
            assumeTrue(s.check(f.cid("quick"), Budget.warm()) == Outcome.ACCEPT,
                    () -> "the judgments do not check this declaration yet: " + s.lastMessage());
            // without a failure below it the declaration is accepted, after a lot of work
            M6FacadeTest.Signals quiet = new M6FacadeTest.Signals(f.m, Integer.MAX_VALUE, 0);
            f.m.signals = quiet;
            long t0 = s.steps();
            assertEquals(Outcome.ACCEPT, s.check(slow, Budget.warm()), () -> s.lastMessage());
            long work = s.steps() - t0;
            assertTrue(work > 5 * 4096, "enough work to be polled several times: " + work + " ticks");
            assertTrue(quiet.polls >= work / 4096, quiet.polls + " polls in " + work + " ticks");
            assertTrue(quiet.maxGap <= 4096, "never more than 4096 ticks between two polls: " + quiet.maxGap);
            // a unit below it fails while it runs: DECLINE(CANCELLED) at the first poll that sees it
            M6FacadeTest.Signals failing = new M6FacadeTest.Signals(f.m, 3, unit - 1);
            f.m.signals = failing;
            t0 = s.steps();
            assertEquals(Outcome.DECLINE, s.check(slow, Budget.warm()));
            assertEquals(Reason.CANCELLED, s.lastReason());
            assertEquals(MachineErrors.E_CANCEL, s.lastCode());
            assertEquals(failing.ticksAtFailure, s.steps(), "no tick after the poll that saw the failure");
            assertTrue(s.steps() - t0 <= 3 * 4096, "within 4096 ticks of the third poll: " + (s.steps() - t0));
            assertTrue(s.steps() - t0 < work);
            assertTrue(f.m.pristine() && f.m.idle(), "the session is reset");
            // the session is usable afterwards, and a failure at or above the unit does not cancel it
            f.m.signals = new M6FacadeTest.Signals(f.m, 2, unit);
            assertEquals(Outcome.ACCEPT, s.check(slow, Budget.warm()), () -> s.lastMessage());
            f.m.signals = RunSignals.NONE;
            assertEquals(Outcome.ACCEPT, s.check(f.cid("after"), Budget.warm()), () -> s.lastMessage());
            // a step budget ends it deterministically: exactly maxSteps steps
            t0 = s.steps();
            assertEquals(Outcome.DECLINE, s.check(slow, Budget.strict(10_000, BIG)));
            assertEquals(Reason.BUDGET_STEPS, s.lastReason());
            assertEquals(10_000, s.steps() - t0);
            f.close();
        }
    }

    @Test
    void theStrictEngineAndTheWarmEngineGiveTheSameOutcomeAndTicksOnRealDeclarations() {
        Fixture f = slowDeclaration();
        MachineEngine strict = new MachineEngine(f.store, EngineConfig.defaults().withStrict(true));
        MachineSession cold = (MachineSession) strict.newSession(f.ctx);
        MachineSession warm = f.session;
        assumeTrue(warm.check(f.cid("quick"), Budget.warm()) == Outcome.ACCEPT,
                () -> "the judgments do not check this declaration yet: " + warm.lastMessage());
        for (String name : new String[] {"two", "SlowF", "slowG", "quick", "slow", "after", "c1", "c2"}) {
            int cid = f.cid(name);
            long w0 = warm.steps();
            int wo = warm.check(cid, Budget.warm());
            long c0 = cold.steps();
            int co = cold.check(cid, Budget.cold(BIG));
            assertEquals(wo, co, () -> name + ": v0 is rule 3 of ISA 6.3, so is strict mode");
            assertEquals(warm.steps() - w0, cold.steps() - c0, () -> name + ": the same ticks (no shortcut exists in v0)");
            assertTrue(cold.m.pristine(), "strict: nothing survives a declaration");
        }
        cold.close();
        f.close();
    }
    /**
     * NOTES-M6 F26: the arena test bad/bugs/rec-of-subst-prop. Unit 112 (an axiom) leaves the pair
     * POS(Nat.add (modeG modeQ) 0, Nat.add (modeG ?m) 0), which is T in plain mode (same-head congruence +
     * proof irrelevance) and F under eager reduction (Nat folding first: 0 against a stuck Acc.rec). Unit 128
     * compares the pair under {@code eagerReduce}; a warm session that kept the entry went PAST the point
     * where the official kernel rejects (TYPE_EXPECTED at {@code Issue.resultSort Issue.gateWitness}) and
     * rejected only later, for another reason. With the rule of F26 a warm session answers what a cold one answers.
     */
    @Test
    void aWarmSessionRejectsRecOfSubstPropWhereTheColdOneDoes() throws java.io.IOException {
        String file = "work/arena-site/tests/bad/bugs/rec-of-subst-prop.ndjson";
        assumeTrue(Fixture.exists(file), "the arena tests are not installed");
        Fixture cold = Fixture.load(file);
        Fixture.Verdict c = cold.checkAll();
        assertEquals(Outcome.REJECT, c.outcome, () -> c.toString());
        for (String switches : new String[] {"warm", "warm,gated,expect"}) {
            Fixture warm = Fixture.load(file);
            warm.switches(switches);
            Fixture.Verdict w = warm.checkAll();
            assertEquals(Outcome.REJECT, w.outcome, () -> switches + ": " + w);
            assertEquals(c.unit, w.unit, () -> switches + ": " + w);
            assertEquals(c.reason, w.reason, () -> switches + ": the warm session must fail where the cold one fails: " + w + " against " + c);
            assertTrue(warm.session.erRestarts >= 1, switches + ": the declaration enters eager reduction in a warm session");
        }
    }
}
