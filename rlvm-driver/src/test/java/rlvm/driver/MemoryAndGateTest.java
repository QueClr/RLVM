package rlvm.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import rlvm.api.Outcome;
import rlvm.api.Reason;
import rlvm.driver.Fakes.FakeHost;
import rlvm.driver.Fakes.Unit;

/**
 * Memory: who gives way when the run passes its limit ({@link MemoryAccount}), memory retries one
 * at a time in file order ({@link HeavyGate}), give-back after a heavy unit, and the run-level
 * behaviour of all three.
 */
class MemoryAndGateTest {

    private static final long MIB = 1L << 20;

    // ---- the account ------------------------------------------------------------------------------------------------

    @Test
    void theThreadThatHoldsTheMostGivesWay() {
        MemoryAccount a = new MemoryAccount(100 * MIB, 4);
        a.setShared(10 * MIB);
        a.report(0, 20 * MIB);
        a.report(1, 30 * MIB);
        a.report(2, 25 * MIB);
        for (int s = 0; s < 4; s++) {
            assertFalse(a.exhausted(s), "under the limit nobody gives way");
        }
        a.report(2, 45 * MIB); // 10 + 20 + 30 + 45 = 105
        assertTrue(a.exhausted());
        assertTrue(a.exhausted(2), "the largest holder is the victim");
        assertFalse(a.exhausted(0));
        assertFalse(a.exhausted(1));
        assertFalse(a.exhausted(3));
        a.report(2, 0); // the victim reset its session
        assertFalse(a.exhausted());
        assertFalse(a.exhausted(2));
        assertEquals(1, a.victimCount());
        assertEquals(105 * MIB, a.peakBytes());
        assertEquals(95 * MIB, a.peakSessionBytes());
    }

    @Test
    void theNextLargestFollowsAndEverybodyGivesWayAboveTheMargin() {
        MemoryAccount a = new MemoryAccount(80 * MIB, 3); // margin = 10 MiB
        a.report(0, 30 * MIB);
        a.report(1, 29 * MIB);
        a.report(2, 28 * MIB);
        assertTrue(a.exhausted(0) && !a.exhausted(1) && !a.exhausted(2));
        a.report(0, 0);
        assertFalse(a.exhausted());
        a.report(0, 27 * MIB);
        assertTrue(!a.exhausted(0) && a.exhausted(1) && !a.exhausted(2), "now slot 1 holds the most");
        // the victim does not react and the others grow: above limit + margin everybody gives way
        a.report(2, 40 * MIB); // 27 + 29 + 40 = 96 > 90
        assertTrue(a.exhausted(0) && a.exhausted(1) && a.exhausted(2));
    }

    @Test
    void aStoreAboveTheLimitMakesEverybodyGiveWay() {
        MemoryAccount a = new MemoryAccount(50 * MIB, 2);
        a.setShared(60 * MIB);
        assertTrue(a.exhausted(0) && a.exhausted(1), "nobody has anything to give back");
        assertTrue(a.exhaustedAlone(0));
        assertTrue(a.exhaustedLastResort(1));
    }

    @Test
    void theGateHolderIsExemptAndTheEnvironmentThreadIsTheLastResort() {
        MemoryAccount a = new MemoryAccount(100 * MIB, 4);
        a.setLastResort(3);
        a.setShared(20 * MIB);
        a.report(0, 50 * MIB);
        a.report(1, 20 * MIB);
        a.report(3, 15 * MIB); // 105: over the limit, within the margin
        assertTrue(a.exhausted(0));
        a.setExempt(0); // slot 0 holds the heavy gate
        assertFalse(a.exhausted(0) && a.exhaustedAlone(0), "50 + 20 fits by itself");
        assertFalse(a.exhaustedAlone(0));
        assertTrue(a.exhausted(1), "the largest of the others gives way; never the environment thread");
        assertFalse(a.exhaustedLastResort(3));
        a.report(1, 0); // 85
        assertFalse(a.exhausted());
        a.report(0, 90 * MIB); // 20 + 90 = 110 alone
        assertTrue(a.exhaustedAlone(0), "the holder does not fit by itself");
        a.setExempt(-1);
        a.report(0, 0);
        a.report(3, 85 * MIB); // 20 + 85: the environment thread alone is over
        assertTrue(a.exhaustedLastResort(3));
    }

    // ---- the gate -----------------------------------------------------------------------------------------------------

    @Test
    void theGateGoesToTheLowestWaitingUnitAndAHolderGivesWayToALowerOne() throws Exception {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            final VerdictBoard board = new VerdictBoard();
            final MemoryAccount account = new MemoryAccount(Long.MAX_VALUE, 8);
            final HeavyGate gate = new HeavyGate(board, account);
            assertTrue(gate.enter(50, 0));
            assertFalse(gate.shouldYield(50));
            final List<Integer> order = Collections.synchronizedList(new ArrayList<Integer>());
            final CountDownLatch queued = new CountDownLatch(3);
            Thread[] threads = new Thread[3];
            final int[] units = {70, 60, 90};
            for (int i = 0; i < 3; i++) {
                final int u = units[i];
                final int slot = i + 1;
                threads[i] = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        queued.countDown();
                        if (gate.enter(u, slot)) {
                            order.add(u);
                            gate.leave(u);
                        }
                    }
                });
                threads[i].start();
            }
            queued.await();
            waitFor(gate, 3);
            assertFalse(gate.shouldYield(50), "only higher units wait");
            gate.leave(50);
            for (Thread t : threads) {
                t.join();
            }
            assertEquals(java.util.Arrays.asList(60, 70, 90), order, "lowest unit first");

            // a holder gives way to a lower unit that starts waiting
            assertTrue(gate.enter(40, 0));
            final CountDownLatch done = new CountDownLatch(1);
            Thread lower = new Thread(new Runnable() {
                @Override
                public void run() {
                    if (gate.enter(10, 1)) {
                        order.add(10);
                        gate.leave(10);
                    }
                    done.countDown();
                }
            });
            lower.start();
            waitFor(gate, 4); // the three waiters above and this one
            assertTrue(gate.shouldYield(40));
            gate.leave(40);
            assertTrue(done.await(30, TimeUnit.SECONDS));
            assertTrue(gate.enter(40, 0), "the former holder enters again");
            gate.leave(40);
            assertEquals(7, gate.entryCount(), "50, 60, 70, 90, 40, 10, 40");
            assertTrue(gate.yieldCount() >= 1);
        });
    }

    @Test
    void aWaitingUnitAboveTheFirstFailureLeavesTheQueue() throws Exception {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            final VerdictBoard board = new VerdictBoard();
            final HeavyGate gate = new HeavyGate(board, new MemoryAccount(Long.MAX_VALUE, 4));
            assertTrue(gate.enter(5, 0));
            final AtomicInteger result = new AtomicInteger(-1);
            Thread waiter = new Thread(new Runnable() {
                @Override
                public void run() {
                    result.set(gate.enter(30, 1) ? 1 : 0);
                }
            });
            waiter.start();
            waitFor(gate, 1);
            board.decline(20, Reason.BUDGET_STEPS, "x"); // the first failure now lies below unit 30
            gate.wake();
            waiter.join();
            assertEquals(0, result.get(), "cancelled while waiting");
            assertFalse(gate.enter(30, 1), "and not admitted later either");
            gate.leave(5);
            assertTrue(gate.enter(7, 2));
            gate.leave(7);
        });
    }

    /** Waits until {@code waiters} threads have started to wait at the gate since it was created. */
    private static void waitFor(HeavyGate gate, int waiters) throws InterruptedException {
        long t0 = System.nanoTime();
        while (gate.waitCount() < waiters) {
            Thread.sleep(1);
            if (System.nanoTime() - t0 > 30_000_000_000L) {
                throw new AssertionError("waiters did not arrive");
            }
        }
        Thread.sleep(5);
    }

    // ---- runs -----------------------------------------------------------------------------------------------------------

    private static final int[] WORKERS = {1, 2, 4, 8};

    @Test
    void aUnitDeclinedForMemoryIsCheckedAgainAloneAndAccepted() {
        for (int w : WORKERS) {
            for (boolean same : new boolean[] {false, true}) {
                Unit[] script = Fakes.units(120);
                for (int i : new int[] {17, 18, 60}) {
                    script[i].warm = Fakes.D;
                    script[i].declineReason = Reason.BUDGET_MEMORY;
                }
                FakeHost host = new FakeHost(script);
                Run run = host.run(Fakes.config(w), same);
                assertEquals(Outcome.ACCEPT, run.execute(), w + " workers");
                assertEquals("", host.describeViolations());
                long gated = 0;
                for (Worker worker : run.workers()) {
                    gated += worker.gatedStages();
                }
                assertEquals(3, gated, "one gate entry per memory retry");
                assertEquals(same ? 0 : 1, host.coldChecks.get(17));
                assertEquals(same ? 1 : 0, host.refChecks.get(17));
            }
        }
    }

    @Test
    void aStageThatIsDeclinedForMemoryIsRepeatedUnderTheGate() {
        // warm REJECT, strict stage declined for memory both times, reference kernel rejects
        Unit[] script = Fakes.units(40);
        script[11].warm = Fakes.R;
        script[11].strict = Fakes.D;
        script[11].declineReason = Reason.BUDGET_MEMORY;
        script[11].ref = Fakes.R;
        FakeHost host = new FakeHost(script);
        Run run = host.run(Fakes.config(2), false);
        assertEquals(Outcome.REJECT, run.execute());
        assertEquals(11, run.verdicts().decidingUnit());
        assertEquals(2, host.strictChecks.get(11), "once in the open, once holding the gate");
        assertEquals(1, host.refChecks.get(11));
        assertEquals("", host.describeViolations());
    }

    /**
     * A unit behind an ill-typed unit never terminates and holds the gate; the ill-typed unit needs
     * the gate too. The holder gives way, the failure is recorded, the run ends.
     */
    @Test
    void aNonTerminatingHolderGivesWayToTheLowerUnit() {
        assertTimeoutPreemptively(Duration.ofSeconds(120), () -> {
            for (int round = 0; round < 20; round++) {
                Unit[] script = Fakes.units(64);
                // unit 40: declined for memory warm, never terminates cold (it is behind unit 9)
                script[40].warm = Fakes.D;
                script[40].declineReason = Reason.BUDGET_MEMORY;
                script[40].cold = Fakes.LOOP;
                script[40].ref = Fakes.LOOP;
                // unit 9: slow, declined for memory warm, rejected cold
                script[9].delayMicros = 20_000;
                script[9].warm = Fakes.D;
                script[9].declineReason = Reason.BUDGET_MEMORY;
                script[9].cold = Fakes.R;
                script[9].strict = Fakes.R;
                script[9].ref = Fakes.R;
                FakeHost host = new FakeHost(script);
                host.loopTimeoutNanos = 60_000_000_000L;
                RunConfig c = Fakes.config(4);
                c.chunkUnits = 2;
                Run run = host.run(c, false);
                assertEquals(Outcome.REJECT, run.execute());
                assertEquals(9, run.verdicts().decidingUnit());
                assertEquals("", host.describeViolations());
            }
        });
    }

    @Test
    void aLaneIsResetWhenItsUnusedCapacityGrew() {
        for (int w : new int[] {1, 4}) {
            Unit[] script = Fakes.units(200);
            script[50].slack = 300 * MIB; // a heavy declaration leaves a large overlay behind
            script[51].slack = 10 * MIB;
            script[120].slack = 600 * MIB;
            // (the capacity goes back once 8 units in a row did not need it: after units 58 and 128)
            FakeHost host = new FakeHost(script);
            RunConfig c = Fakes.config(w);
            c.keepBytes = 256 * MIB;
            Run run = host.run(c, true);
            assertEquals(Outcome.ACCEPT, run.execute());
            long giveBacks = 0;
            long bytes = 0;
            long max = 0;
            for (Worker worker : run.workers()) {
                giveBacks += worker.giveBacks();
                bytes += worker.givenBackBytes();
                max = Math.max(max, worker.maxSlackBytes());
            }
            if (w == 1) {
                assertEquals(2, giveBacks, "after units 58 and 128");
                assertEquals(900 * MIB, bytes);
            } else {
                // which worker draws the units behind a heavy one depends on the schedule: a worker
                // that gets fewer than 8 more units keeps its capacity to the end
                assertTrue(giveBacks <= 2 && bytes <= 900 * MIB, giveBacks + " give-backs, " + bytes + " bytes");
            }
            assertEquals(600 * MIB, max);
            assertEquals("", host.describeViolations());

            FakeHost keep = new FakeHost(Fakes.copy(script));
            RunConfig never = Fakes.config(w);
            never.keepBytes = 0;
            Run run2 = keep.run(never, true);
            assertEquals(Outcome.ACCEPT, run2.execute());
            for (Worker worker : run2.workers()) {
                assertEquals(0, worker.giveBacks());
            }
        }
    }

    /** A run of heavy declarations keeps the capacity instead of growing it again for each of them. */
    @Test
    void aLaneKeepsItsCapacityWhileUnitsNeedIt() {
        Unit[] script = Fakes.units(120);
        for (int i = 20; i < 80; i += 3) {
            // every third unit is heavy: it holds 400 MiB while it runs (the memory poll samples it)
            script[i].warm = Fakes.MEM;
            script[i].footprint = 400 * MIB;
            script[i].slack = 500 * MIB;
        }
        FakeHost host = new FakeHost(script);
        RunConfig c = Fakes.config(1);
        c.keepBytes = 256 * MIB;
        c.sessionBytes = Long.MAX_VALUE; // only the give-back rule resets the lane
        Run run = host.run(c, true);
        assertEquals(Outcome.ACCEPT, run.execute());
        Worker worker = run.workers()[0];
        assertEquals(1, worker.giveBacks(), "once, 8 units after the last heavy unit");
        assertEquals(500 * MIB, worker.givenBackBytes());
        assertEquals("", host.describeViolations());
    }

    @Test
    void theDriversSettingsByName() {
        RunConfig c = new RunConfig();
        assertTrue(RunConfig.knows("unit-mib") && RunConfig.knows("keep-mib") && !RunConfig.knows("engine"));
        c.setAll("unit-mib=2048, keep-mib=64,gc-after-mib=512,chunk=16,env-batch=100,spill-ms=5,publish-units=1000,"
                + "publish-mib=8,pipeline-min-mib=0");
        assertEquals(2048 * MIB, c.unitBytes);
        assertEquals(64 * MIB, c.keepBytes);
        assertEquals(512 * MIB, c.gcAfterBytes);
        assertEquals(16, c.chunkUnits);
        assertEquals(100, c.envBatchUnits);
        assertEquals(5_000_000L, c.spillNanos);
        assertEquals(1000, c.publishEveryUnits);
        assertEquals(8 * MIB, c.publishEveryBytes);
        assertEquals(0, c.pipelineMinBytes);
        for (String bad : new String[] {"nonsense=1", "chunk=0", "chunk", "keep-mib=x", "unit-mib=-1", "chunk=99999999999"}) {
            try {
                new RunConfig().setAll(bad);
                throw new AssertionError("accepted " + bad);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().length() > 5);
            }
        }
        for (String k : RunConfig.keys()) {
            assertTrue(RunConfig.knows(k));
        }
    }

    @Test
    void theAutomaticWorkerCount() {
        assertEquals(1, Run.autoWorkers(1));
        assertEquals(1, Run.autoWorkers(2));
        assertEquals(2, Run.autoWorkers(4));
        assertEquals(6, Run.autoWorkers(8));
        assertEquals(6, Run.autoWorkers(64));
        assertEquals(Run.autoWorkers(Runtime.getRuntime().availableProcessors()), Run.resolveWorkers(0));
        assertEquals(11, Run.resolveWorkers(11));
    }
}
