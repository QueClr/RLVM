package rlvm.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import rlvm.admit.Policy;
import rlvm.api.Outcome;
import rlvm.api.Reason;
import rlvm.driver.Fakes.FakeHost;
import rlvm.driver.Fakes.Unit;

/**
 * The re-check protocol table of ARCHITECTURE.md 8.2 / 6.5 / 6.6, by enumeration of what every engine
 * says about one unit: which engines are consulted, in fresh sessions with the cold budget, and what
 * the unit's final status is.
 */
class RecheckProtocolTest {

    private static final int A = Fakes.A;
    private static final int R = Fakes.R;
    private static final int D = Fakes.D;
    private static final int T = Fakes.T;

    private static RunConfig config(boolean recheck, Policy.Unconfirmed policy) {
        RunConfig c = Fakes.config(1);
        c.recheckRejections = recheck;
        c.policy = new Policy(true, Policy.ForwardRefs.DECLINE, policy, true, false);
        return c;
    }

    @Test
    void protocolTableByEnumeration() {
        int[] outcomes = {A, R, D, T};
        int cases = 0;
        for (int sameI = 0; sameI < 2; sameI++) {
            boolean same = sameI == 1;
            for (int recheckI = 0; recheckI < 2; recheckI++) {
                boolean recheck = recheckI == 1;
                for (Policy.Unconfirmed policy : Policy.Unconfirmed.values()) {
                    for (int warm : outcomes) {
                        for (int cold : outcomes) {
                            for (int strict : outcomes) {
                                for (int ref : outcomes) {
                                    one(same, recheck, policy, warm, cold, strict, ref);
                                    cases++;
                                }
                            }
                        }
                    }
                }
            }
        }
        assertEquals(2 * 2 * 2 * 256, cases);
    }

    private static void one(boolean same, boolean recheck, Policy.Unconfirmed policy, int warm, int cold, int strict,
            int ref) {
        Unit[] script = Fakes.units(3);
        Unit u = script[1];
        u.warm = warm;
        u.cold = cold;
        u.strict = strict;
        u.ref = ref;
        u.rejectReason = Reason.APP_TYPE_MISMATCH;
        u.declineReason = Reason.STACK_DEPTH;
        FakeHost host = new FakeHost(script);
        Run run = host.run(config(recheck, policy), same);
        int verdict = run.execute();
        String where = "same=" + same + " recheck=" + recheck + " policy=" + policy + " " + u;
        assertEquals("", host.describeViolations(), where);

        int expected = Fakes.finalOf(u, warm, same, recheck, policy);
        assertEquals(expected, verdict, where);
        VerdictBoard b = run.verdicts();
        assertEquals(expected == Outcome.ACCEPT ? -1 : 1, b.decidingUnit(), where);
        assertEquals(VerdictBoard.ACCEPTED, b.status(0), where);
        assertEquals(expected == Outcome.ACCEPT ? VerdictBoard.ACCEPTED
                : expected == Outcome.REJECT ? VerdictBoard.REJECTED : VerdictBoard.DECLINED, b.status(1), where);
        // the unit behind a failure is never checked with one worker; behind an accepted unit it is
        assertEquals(expected == Outcome.ACCEPT ? VerdictBoard.ACCEPTED : VerdictBoard.NONE, b.status(2), where);

        // which engines were consulted
        assertEquals(1, host.warmChecks.get(1), where);
        boolean coldRun = !same && warm == D;
        boolean confirm = (warm == R && recheck) || (coldRun && cold == R && recheck);
        boolean strictRun = !same && confirm;
        boolean refRun = warm == T || (warm == D && (same || (cold != A && cold != R)))
                || (confirm && (same || strict != A));
        assertEquals(coldRun ? 1 : 0, host.coldChecks.get(1), "cold " + where);
        assertEquals(strictRun ? 1 : 0, host.strictChecks.get(1), "strict " + where);
        assertEquals(refRun ? 1 : 0, host.refChecks.get(1), "reference " + where);

        // events
        boolean anomaly = confirm && ((!same && strict == A) || (same && ref == A));
        boolean incomplete = confirm && !same && strict != A && ref == A;
        assertEquals(anomaly ? 1 : 0, b.warmAnomalies(), "WARM-ANOMALY " + where);
        assertEquals(incomplete ? 1 : 0, b.machineIncomplete(), "MACHINE-INCOMPLETE " + where);

        // reasons: a confirmed rejection carries the reference kernel's reason; an unconfirmed one says so
        String line = b.explanation();
        if (expected == Outcome.ACCEPT) {
            assertEquals(null, line, where);
        } else if (expected == Outcome.REJECT) {
            assertTrue(line.startsWith("reject: unit 1 'decl1' rejected (APP_TYPE_MISMATCH)"), line + " " + where);
            if (confirm && ref == R) {
                assertTrue(line.contains("role " + Fakes.ROLE_REFERENCE), "the reference kernel's message: " + line);
            }
        } else if (confirm && ref != R && ref != A) {
            assertTrue(line.contains("UNCONFIRMED_REJECT"), line + " " + where);
        } else if (ref == T) {
            assertTrue(line.contains("INTERNAL_ERROR"), line + " " + where);
        } else {
            assertTrue(line.contains("STACK_DEPTH"), line + " " + where);
        }
        // everything is closed again
        assertEquals(0, host.sessionsOpen.get(), where);
        assertEquals(0, host.lanesOpen.get(), where);
        assertEquals(0, host.enginesOpen.get(), where);
    }

    @Test
    void anInvalidOutcomeCodeIsADecline() {
        for (int sameI = 0; sameI < 2; sameI++) {
            Unit[] script = Fakes.units(2);
            script[0].warm = Fakes.BAD;
            script[0].cold = Fakes.BAD;
            script[0].ref = Fakes.BAD;
            FakeHost host = new FakeHost(script);
            Run run = host.run(Fakes.config(1), sameI == 1);
            assertEquals(Outcome.DECLINE, run.execute());
            assertEquals(VerdictBoard.DECLINED, run.verdicts().status(0));
            assertTrue(run.verdicts().explanation().contains("INTERNAL_ERROR"), run.verdicts().explanation());
            assertEquals("", host.describeViolations());
        }
    }

    @Test
    void aWarmCacheAnomalyIsAcceptedAndCounted() {
        // unit 3 is rejected only by a session that already checked something: the cold strict run accepts
        Unit[] script = Fakes.units(6);
        script[3].warm = Fakes.WARMTH;
        for (int sameI = 0; sameI < 2; sameI++) {
            FakeHost host = new FakeHost(Fakes.copy(script));
            Run run = host.run(Fakes.config(1), sameI == 1);
            assertEquals(Outcome.ACCEPT, run.execute());
            assertEquals(1, run.verdicts().warmAnomalies());
            assertEquals(0, run.verdicts().machineIncomplete());
            assertTrue(run.verdicts().events()[0].contains("WARM-ANOMALY unit 3 'decl3'"), run.verdicts().events()[0]);
            assertEquals("", host.describeViolations());
        }
    }

    @Test
    void differentialModeDeclinesOnDisagreement() {
        // primary accepts what the reference rejects (a soundness bug of the machine)
        Unit[] script = Fakes.units(4);
        script[2].ref = R;
        FakeHost host = new FakeHost(script);
        RunConfig c = Fakes.config(1);
        c.differential = true;
        Run run = host.run(c, false);
        assertEquals(Outcome.DECLINE, run.execute());
        assertEquals(2, run.verdicts().decidingUnit());
        assertEquals(1, run.verdicts().disagreements());
        assertTrue(run.verdicts().explanation().contains("ENGINE_DISAGREEMENT"), run.verdicts().explanation());
        assertEquals("", host.describeViolations());
        // every accepted unit was also checked by the reference kernel
        assertEquals(1, host.refChecks.get(0));
        assertEquals(1, host.refChecks.get(1));

        // primary rejects what the reference accepts
        Unit[] script2 = Fakes.units(4);
        script2[1].warm = R;
        script2[1].strict = R;
        FakeHost host2 = new FakeHost(script2);
        Run run2 = host2.run(c, false);
        assertEquals(Outcome.DECLINE, run2.execute());
        assertEquals(1, run2.verdicts().decidingUnit());
        assertEquals(1, run2.verdicts().disagreements());

        // agreement on a rejection: REJECT as usual
        Unit[] script3 = Fakes.units(4);
        script3[1].warm = R;
        script3[1].strict = R;
        script3[1].ref = R;
        FakeHost host3 = new FakeHost(script3);
        Run run3 = host3.run(c, false);
        assertEquals(Outcome.REJECT, run3.execute());
        assertEquals(0, run3.verdicts().disagreements());

        // without the flag the unsound acceptance goes unnoticed: an ACCEPT of the primary engine is never re-checked
        FakeHost host4 = new FakeHost(Fakes.copy(script));
        Run run4 = host4.run(Fakes.config(1), false);
        assertEquals(Outcome.ACCEPT, run4.execute());
        assertEquals(0, host4.refChecks.get(2));
    }
}
