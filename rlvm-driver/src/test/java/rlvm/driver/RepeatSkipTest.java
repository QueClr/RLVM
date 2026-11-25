package rlvm.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import rlvm.api.Outcome;
import rlvm.driver.Fakes.FakeHost;
import rlvm.driver.Fakes.Unit;

/**
 * A unit that repeats an ACCEPTED unit word for word is admitted and not checked again
 * ({@code RunConfig.skipRepeats}, {@code RunHost.unitRepeatOf}); in every other case it is checked as
 * before, and the verdict is the same either way.
 */
class RepeatSkipTest {

    private static final int A = Fakes.A;
    private static final int R = Fakes.R;
    private static final int D = Fakes.D;

    private static FakeHost host(Unit[] script, int... repeatAndOriginal) {
        FakeHost host = new FakeHost(script);
        int[] r = new int[script.length];
        Arrays.fill(r, -1);
        for (int i = 0; i < repeatAndOriginal.length; i += 2) {
            r[repeatAndOriginal[i]] = repeatAndOriginal[i + 1];
        }
        host.repeatOf = r;
        return host;
    }

    private static int engineRuns(FakeHost host, int unit) {
        return host.warmChecks.get(unit) + host.coldChecks.get(unit) + host.strictChecks.get(unit) + host.refChecks.get(unit);
    }

    @Test
    void aRepeatOfAnAcceptedUnitIsAdmittedAndNotChecked() {
        for (int workers : new int[] {1, 2, 4}) {
            Unit[] script = Fakes.units(200);
            // units 60, 61 and 150 repeat unit 3; unit 199 repeats unit 150's original as well
            FakeHost host = host(script, 60, 3, 61, 3, 150, 3, 199, 3);
            Run run = host.run(Fakes.config(workers), false);
            assertEquals(Outcome.ACCEPT, run.execute(), "workers " + workers);
            assertEquals("", host.describeViolations());
            VerdictBoard b = run.verdicts();
            for (int u = 0; u < script.length; u++) {
                assertEquals(VerdictBoard.ACCEPTED, b.status(u), "unit " + u);
                assertEquals(VerdictBoard.ACCEPTED, host.decided.get(u), "the host was told about unit " + u);
            }
            assertEquals(1, host.warmChecks.get(3));
            if (workers == 1) {
                // file order: the original is decided before every repeat
                for (int u : new int[] {60, 61, 150, 199}) {
                    assertEquals(0, engineRuns(host, u), "unit " + u + " repeats an accepted unit");
                }
            } else {
                // a repeat is checked at most once, and only if its original was not decided yet
                for (int u : new int[] {60, 61, 150, 199}) {
                    assertTrue(host.warmChecks.get(u) <= 1 && host.refChecks.get(u) == 0, "unit " + u);
                }
            }
            for (int u = 0; u < script.length; u++) {
                if (u != 60 && u != 61 && u != 150 && u != 199) {
                    assertEquals(1, host.warmChecks.get(u), "unit " + u + " repeats nothing");
                }
            }
        }
    }

    @Test
    void aUnitAcceptedOnTheReferenceEnginesWordCountsAsAccepted() {
        Unit[] script = Fakes.units(20);
        script[3].warm = D;
        script[3].cold = D; // the reference engine accepts it
        FakeHost host = host(script, 9, 3);
        Run run = host.run(Fakes.config(1), false);
        assertEquals(Outcome.ACCEPT, run.execute());
        assertEquals("", host.describeViolations());
        assertEquals(1, host.refChecks.get(3));
        assertEquals(0, engineRuns(host, 9), "the repeat is decided by its original's final status");
        assertEquals(VerdictBoard.ACCEPTED, run.verdicts().status(9));
    }

    @Test
    void aRepeatOfAUnitThatFailedIsNeverAcceptedWithoutACheck() {
        for (int how : new int[] {R, D}) {
            Unit[] script = Fakes.units(20);
            script[3].warm = how;
            script[3].cold = how;
            script[3].strict = how;
            script[3].ref = how;
            // the repeat says what its original says, as it must
            script[9] = script[3].copy();
            FakeHost host = host(script, 9, 3);
            Run run = host.run(Fakes.config(1), false);
            assertEquals(how == R ? Outcome.REJECT : Outcome.DECLINE, run.execute());
            assertEquals("", host.describeViolations());
            VerdictBoard b = run.verdicts();
            assertEquals(3, b.decidingUnit());
            assertEquals(VerdictBoard.NONE, b.status(9), "a unit behind the first failure has no status");
        }
    }

    @Test
    void aRepeatWhoseOriginalIsStillRunningIsCheckedAndNobodyWaits() {
        Unit[] script = Fakes.units(40);
        // Unit 3 ends only when unit 9, its repeat, has a status: unit 9 cannot have waited for it.
        script[3].warm = Fakes.HOLD;
        script[3].holdFor = 9;
        FakeHost host = host(script, 9, 3);
        RunConfig c = Fakes.config(2);
        c.chunkUnits = 4;
        c.sliceUnits = 4;
        Run run = host.run(c, false);
        assertEquals(Outcome.ACCEPT, run.execute());
        assertEquals("", host.describeViolations());
        assertEquals(1, host.warmChecks.get(9), "the original had no status: the repeat was checked");
        assertEquals(VerdictBoard.ACCEPTED, run.verdicts().status(9));
        assertEquals(VerdictBoard.ACCEPTED, run.verdicts().status(3));
    }

    /**
     * The status of a repeat does not depend on the schedule: checked while its original was still running,
     * and NOT accepted by that check (on input that only a shortcut of the primary engine accepts, the engine
     * may say something else about the copy), it waits for the original and takes its ACCEPT, as it would
     * have without a check a moment later. No re-check of the unit runs.
     */
    @Test
    void aRepeatThatWasCheckedAndNotAcceptedWhileItsOriginalRanTakesTheOriginalsStatus() {
        for (int how : new int[] {R, D, Fakes.T}) {
            Unit[] script = Fakes.units(40);
            // Unit 3 ends only once the primary engine has checked unit 9, its repeat, which every engine refuses.
            script[3].warm = Fakes.HOLD;
            script[3].holdFor = 9;
            script[3].holdUntilChecked = true;
            script[9].warm = how;
            script[9].cold = R;
            script[9].strict = R;
            script[9].ref = R;
            FakeHost host = host(script, 9, 3);
            RunConfig c = Fakes.config(2);
            c.chunkUnits = 4;
            c.sliceUnits = 4;
            Run run = host.run(c, false);
            assertEquals(Outcome.ACCEPT, run.execute(), "warm outcome " + how);
            assertEquals("", host.describeViolations());
            assertEquals(1, host.warmChecks.get(9), "checked once, while the original ran");
            assertEquals(0, host.coldChecks.get(9) + host.strictChecks.get(9) + host.refChecks.get(9), "and not re-checked");
            assertEquals(VerdictBoard.ACCEPTED, run.verdicts().status(9));
            assertEquals(VerdictBoard.ACCEPTED, run.verdicts().status(3));

            // the same file with every unit checked: the repeat is rejected
            host = host(Fakes.copy(script), 9, 3);
            c = Fakes.config(2);
            c.chunkUnits = 4;
            c.sliceUnits = 4;
            c.set("repeats", "check");
            run = host.run(c, false);
            assertEquals(Outcome.REJECT, run.execute());
            assertEquals(9, run.verdicts().decidingUnit());
        }
    }

    /** If the original fails in the end, the repeat that waited for it is not accepted: the first failure is the original. */
    @Test
    void aRepeatThatWaitedForAnOriginalThatFailsIsNotAccepted() {
        for (int how : new int[] {R, D}) {
            Unit[] script = Fakes.units(40);
            script[3].warm = Fakes.HOLD;
            script[3].holdFor = 9;
            script[3].holdUntilChecked = true;
            script[3].afterHold = how;
            script[3].cold = how;
            script[3].strict = how;
            script[3].ref = how;
            script[9] = script[3].copy();
            script[9].warm = how;
            FakeHost host = host(script, 9, 3);
            RunConfig c = Fakes.config(2);
            c.chunkUnits = 4;
            c.sliceUnits = 4;
            Run run = host.run(c, false);
            assertEquals(how == R ? Outcome.REJECT : Outcome.DECLINE, run.execute());
            assertEquals("", host.describeViolations());
            assertEquals(3, run.verdicts().decidingUnit());
            assertTrue(run.verdicts().status(9) != VerdictBoard.ACCEPTED);
            assertEquals(0, host.refChecks.get(9), "nothing above the first failure is re-checked");
        }
    }

    @Test
    void anOriginalThatIsNotEarlierInAdmissionOrderDoesNotCount() {
        Unit[] script = Fakes.units(20);
        FakeHost host = host(script, 9, 3);
        // the loader reordered: unit 3 is admitted after unit 9 (a rank is a position in admission order)
        host.rank[3] = 9;
        host.rank[9] = 3;
        Run run = host.run(Fakes.config(1), false);
        assertEquals(Outcome.ACCEPT, run.execute());
        assertEquals(1, host.warmChecks.get(9));
        // and a "repeat" of a later unit is no repeat
        host = host(Fakes.units(20), 3, 9);
        run = host.run(Fakes.config(1), false);
        assertEquals(Outcome.ACCEPT, run.execute());
        assertEquals(1, host.warmChecks.get(3));
        assertEquals(1, host.warmChecks.get(9));
    }

    @Test
    void theReferenceEngineAsPrimaryChecksEveryUnit() {
        Unit[] script = Fakes.units(20);
        FakeHost host = host(script, 9, 3, 10, 3);
        Run run = host.run(Fakes.config(1), true);
        assertEquals(Outcome.ACCEPT, run.execute());
        assertEquals("", host.describeViolations());
        assertEquals(1, host.warmChecks.get(9));
        assertEquals(1, host.warmChecks.get(10));
    }

    @Test
    void theSettingRepeatsCheckChecksEveryUnit() {
        Unit[] script = Fakes.units(20);
        FakeHost host = host(script, 9, 3, 10, 3);
        RunConfig c = Fakes.config(1);
        assertTrue(c.skipRepeats, "the default");
        c.set("repeats", "check");
        Run run = host.run(c, false);
        assertEquals(Outcome.ACCEPT, run.execute());
        assertEquals(1, host.warmChecks.get(9));
        assertEquals(1, host.warmChecks.get(10));
        c.set("repeats", "skip");
        assertTrue(c.skipRepeats);
        try {
            c.set("repeats", "maybe");
            throw new AssertionError("accepted repeats=maybe");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("skip or check"), expected.getMessage());
        }
    }
}
