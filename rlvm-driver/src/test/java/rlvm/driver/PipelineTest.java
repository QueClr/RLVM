package rlvm.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import rlvm.api.Outcome;
import rlvm.api.Reason;
import rlvm.driver.Fakes.FakeHost;
import rlvm.driver.Fakes.Unit;
import rlvm.load.MalformedExportException;
import rlvm.load.UnsupportedExportException;

/**
 * Pipelined loading (*agentic-note*: design-architecture-document, 3.5, 6.1) over fake units and a fake loader: the calling thread
 * publishes the file in pieces while the environment thread and the workers check. What a batch run
 * says is what a pipelined run says; see also {@code InvarianceTest.sameVerdictWhateverThePipeline}.
 */
class PipelineTest {

    private static final int[] WORKERS = {1, 2, 4, 8};

    private static Unit[] mixed(int n) {
        Unit[] script = Fakes.units(n);
        for (int i = 0; i < n; i++) {
            if (i % 17 == 5) {
                script[i].cls = RunHost.ENV;
            } else if (i % 41 == 7) {
                script[i].cls = RunHost.SKIP;
            }
        }
        return script;
    }

    @Test
    void aFilePublishedInPiecesIsCheckedWhileItLoads() {
        for (int w : WORKERS) {
            Unit[] script = mixed(3000);
            FakeHost host = new FakeHost(script);
            RunConfig c = Fakes.config(w);
            Run run = host.runPipelined(c, true, new int[] {0, 400, 400, 1300, 2999, 3000}, 300, null);
            assertEquals(Outcome.ACCEPT, run.execute(), w + " workers");
            assertTrue(run.pipelined());
            assertEquals(w, run.workerCount(), "a file that arrives in pieces gets the configured workers");
            assertEquals(3000, run.verdicts().acceptedCount());
            assertEquals("", host.describeViolations());
            assertEquals(0, host.sessionsOpen.get() + host.lanesOpen.get() + host.enginesOpen.get());
            for (Worker worker : run.workers()) {
                assertEquals(0, worker.deferredUnits());
            }
        }
    }

    /** A lane sees what was published when it was built; before a unit beyond that it is reset (3.5). */
    @Test
    void aLaneMovesToTheNewestSnapshotBeforeAUnitBeyondItsOwn() {
        assertTimeoutPreemptively(Duration.ofSeconds(120), () -> {
            final FakeHost host = new FakeHost(mixed(2000));
            host.loaded = 0;
            host.complete = false;
            final Run[] holder = new Run[1];
            Run.Loading loading = new Run.Loading() {
                @Override
                public void load(LoadProgress progress) throws InterruptedException {
                    host.loaded = 400;
                    progress.published(400);
                    // the single worker has built its lane on the frontier 400 once unit 0 is decided
                    while (holder[0].verdicts().status(0) == VerdictBoard.NONE) {
                        Thread.sleep(1);
                    }
                    host.loaded = 1500;
                    progress.published(1500);
                    while (holder[0].verdicts().status(1499) == VerdictBoard.NONE) {
                        Thread.sleep(1);
                    }
                    host.loaded = 2000;
                    host.complete = true;
                    progress.published(2000);
                }
            };
            rlvm.api.EngineFactory f = host.soloFactory();
            Run run = new Run(Fakes.config(1), host, loading, f, f);
            holder[0] = run;
            run.setMinUnitsPerWorker(1);
            run.setDiagnostics(null);
            assertEquals(Outcome.ACCEPT, run.execute());
            assertTrue(run.pipelined());
            assertEquals(2, run.workers()[0].snapshotResets(), "before unit 400 and before unit 1500");
            assertEquals("", host.describeViolations(), "no unit was admitted beyond its lane's snapshot");
        });
    }

    @Test
    void aFilePublishedInOnePieceIsABatchRun() {
        Unit[] script = mixed(300);
        FakeHost host = new FakeHost(script);
        Run run = host.runPipelined(Fakes.config(8), true, new int[] {300}, 0, null);
        run.setMinUnitsPerWorker(128);
        assertEquals(Outcome.ACCEPT, run.execute());
        assertFalse(run.pipelined());
        assertEquals(2, run.workerCount(), "300 units / 128 per worker, as in batch mode");
        assertEquals("", host.describeViolations());

        FakeHost empty = new FakeHost(Fakes.units(0));
        Run run2 = empty.runPipelined(Fakes.config(4), true, new int[] {0}, 0, null);
        assertEquals(Outcome.ACCEPT, run2.execute(), "an export without declarations");
    }

    @Test
    void malformedInputRejectsWhateverWasChecked() {
        assertTimeoutPreemptively(Duration.ofSeconds(120), () -> {
            for (int w : WORKERS) {
                Unit[] script = mixed(800);
                script[30].warm = Fakes.D; // would be a decline
                script[30].cold = Fakes.D;
                script[30].ref = Fakes.D;
                script[700].warm = Fakes.LOOP; // never finishes by itself
                script[700].cold = Fakes.LOOP;
                script[700].ref = Fakes.LOOP;
                FakeHost host = new FakeHost(script);
                Run run = host.runPipelined(Fakes.config(w), true, new int[] {200, 750}, 500,
                        new MalformedExportException("unexpected token", 4711));
                assertEquals(Outcome.REJECT, run.execute(), w + " workers");
                assertTrue(run.verdicts().explanation().contains("4711"), run.verdicts().explanation());
                assertEquals("", host.describeViolations());
                assertEquals(0, host.sessionsOpen.get() + host.lanesOpen.get() + host.enginesOpen.get());
            }
        });
    }

    @Test
    void anUnsupportedRecordDeclinesUnlessAPublishedUnitIsRejected() {
        for (int w : WORKERS) {
            // all published units are fine: the verdict is the loader's decline
            FakeHost ok = new FakeHost(mixed(500));
            Run run = ok.runPipelined(Fakes.config(w), false, new int[] {100, 320}, 200,
                    new UnsupportedExportException("expression id of 2^32 or more"));
            assertEquals(Outcome.DECLINE, run.execute(), w + " workers");
            assertTrue(run.verdicts().explanation().contains("2^32"), run.verdicts().explanation());
            for (int u = 0; u < 320; u++) {
                int st = run.verdicts().status(u);
                assertTrue(st == VerdictBoard.ACCEPTED || st == VerdictBoard.SKIPPED, "unit " + u + " was checked");
            }
            assertEquals("", ok.describeViolations());

            // a published unit is ill-typed: REJECT (the official checker rejects it whatever follows)
            Unit[] script = mixed(500);
            script[250].warm = Fakes.R;
            script[250].strict = Fakes.R;
            script[250].ref = Fakes.R;
            FakeHost bad = new FakeHost(script);
            Run run2 = bad.runPipelined(Fakes.config(w), false, new int[] {100, 320}, 200,
                    new UnsupportedExportException("expression id of 2^32 or more"));
            assertEquals(Outcome.REJECT, run2.execute(), w + " workers");
            assertEquals(250, run2.verdicts().decidingUnit());

            // the loader cannot read on: decline
            FakeHost io = new FakeHost(mixed(500));
            Run run3 = io.runPipelined(Fakes.config(w), false, new int[] {100, 320}, 200, new IOException("disk"));
            assertEquals(Outcome.DECLINE, run3.execute());
            assertEquals("", io.describeViolations());
        }
    }

    /** A decline that only says "not loaded yet" is decided again when the file is complete. */
    @Test
    void aUnitThatNeedsTheRestOfTheFileIsDecidedAfterTheLoad() {
        assertTimeoutPreemptively(Duration.ofSeconds(120), () -> {
            for (int w : WORKERS) {
                for (int variant = 0; variant < 3; variant++) {
                    Unit[] script = mixed(600);
                    Unit u = script[100];
                    u.late = true; // e.g. a reference to a name that is not declared in the loaded part
                    if (variant == 1) { // ... and nowhere in the file: unknown constant, a rejection
                        u.warm = Fakes.R;
                        u.strict = Fakes.R;
                        u.ref = Fakes.R;
                    } else if (variant == 2) { // ... declared later: a forward reference, a decline
                        u.warm = Fakes.D;
                        u.cold = Fakes.D;
                        u.ref = Fakes.D;
                        u.declineReason = Reason.FORWARD_REFERENCE;
                    }
                    final FakeHost host = new FakeHost(script);
                    // the loader goes on only after every stage of the unit (warm, cold, reference) was
                    // tried while the file was loading
                    Run run = host.runPipelined(Fakes.config(w), false, new int[] {300, 450, 600}, 300, null,
                            () -> host.lateDeclines.get() >= 3);
                    int verdict = run.execute();
                    String where = w + " workers, variant " + variant;
                    assertEquals(variant == 0 ? Outcome.ACCEPT : variant == 1 ? Outcome.REJECT : Outcome.DECLINE, verdict, where);
                    assertEquals(variant == 0 ? -1 : 100, run.verdicts().decidingUnit(), where);
                    assertTrue(host.lateDeclines.get() >= 1, "the unit was tried while the file was loading: " + where);
                    long deferred = 0;
                    for (Worker worker : run.workers()) {
                        deferred += worker.deferredUnits();
                    }
                    assertEquals(1, deferred, where);
                    assertEquals("", host.describeViolations(), where);
                }
            }
        });
    }

    @Test
    void aLargeFileIsPublishedInLargerPieces() {
        // the factor is the input's size in GiB, at least 1 and at most the configured maximum
        assertEquals(1, Run.publishScale(0, 4));
        assertEquals(1, Run.publishScale(584L << 20, 4));        // Std
        assertEquals(1, Run.publishScale((2L << 30) - 1, 4));
        assertEquals(2, Run.publishScale(2500L << 20, 4));       // cslib
        assertEquals(4, Run.publishScale(6326141213L, 4));       // mathlib
        assertEquals(1, Run.publishScale(6326141213L, 1));       // off
        assertEquals(5, Run.publishScale(6326141213L, 8));
        assertEquals(1, Run.publishScale(6326141213L, 0));
        RunConfig c = new RunConfig();
        assertEquals(4, c.publishScaleMax);
        c.set("publish-scale", "1");
        assertEquals(1, c.publishScaleMax);
        assertThrows(IllegalArgumentException.class, () -> c.set("publish-scale", "0"));
    }

    /**
     * The parking rule: the environment thread does not advance past a unit that may depend on the
     * rest of the file in a way the pass cannot see in file order; after the load the pass decides
     * it over the complete file, and the run has the verdict of the batch run.
     */
    @Test
    void theFrontierWaitsBelowAnOutOfOrderUnitUntilTheLoadHasEnded() {
        assertTimeoutPreemptively(Duration.ofSeconds(120), () -> {
            for (int w : WORKERS) {
                for (int variant = 0; variant < 4; variant++) {
                    Unit[] script = mixed(600);
                    int at = variant == 3 ? 0 : 200;
                    script[at].cls = RunHost.CHECK;
                    script[at].outOfOrder = true;
                    if (variant == 1) { // really ill-typed: the batch verdict is REJECT at this unit
                        script[at].warm = Fakes.R;
                        script[at].strict = Fakes.R;
                        script[at].ref = Fakes.R;
                    }
                    if (variant == 2) { // a second one further up, in the last piece of the file
                        script[500].cls = RunHost.CHECK;
                        script[500].outOfOrder = true;
                    }
                    // batch: the pass meets the unit over the complete file
                    FakeHost batch = new FakeHost(Fakes.copy(script));
                    int expected = batch.run(Fakes.config(w), false).execute();
                    assertEquals(variant == 1 ? Outcome.REJECT : Outcome.ACCEPT, expected);
                    assertEquals(0, batch.passedOutOfOrder.get());
                    // pipelined: units 0 .. 299 are published first; the loader goes on only when everything
                    // below the out-of-order unit was checked, i.e. the frontier really stood there
                    final FakeHost host = new FakeHost(Fakes.copy(script));
                    final int below = at;
                    final Run[] holder = new Run[1];
                    Run run = host.runPipelined(Fakes.config(w), false, new int[] {300, 450, 600}, 300, null, () -> {
                        VerdictBoard b = holder[0].verdicts();
                        for (int u = 0; u < below; u++) {
                            if (b.status(u) == VerdictBoard.NONE) {
                                return false;
                            }
                        }
                        return host.env.get() == below;
                    });
                    holder[0] = run;
                    int verdict = run.execute();
                    String where = w + " workers, variant " + variant + ": " + run.verdicts().explanation();
                    assertEquals(expected, verdict, where);
                    assertEquals(variant == 1 ? 200 : -1, run.verdicts().decidingUnit(), where);
                    assertEquals(0, host.passedOutOfOrder.get(), "the pass never went past an out-of-order unit while loading: " + where);
                    assertEquals("", host.describeViolations(), where);
                }
            }
            // Without the host's answer (the rule off) the same file is declined where the batch run accepts:
            // the difference the rule removes.
            Unit[] script = mixed(600);
            script[200].cls = RunHost.CHECK;
            script[200].outOfOrder = true;
            final FakeHost silent = new FakeHost(script);
            silent.tellsOutOfOrder = false;
            Run run = silent.runPipelined(Fakes.config(4), false, new int[] {300, 450, 600}, 300, null,
                    () -> silent.env.get() >= 300);
            assertEquals(Outcome.DECLINE, run.execute());
            assertEquals(200, run.verdicts().decidingUnit());
            assertEquals(1, silent.passedOutOfOrder.get());
            assertTrue(run.verdicts().explanation().contains("FORWARD_REFERENCE"), run.verdicts().explanation());
        });
    }

    @Test
    void anEnvironmentUnitThatNeedsTheRestOfTheFileIsDecidedAfterTheLoad() {
        assertTimeoutPreemptively(Duration.ofSeconds(120), () -> {
            for (int w : WORKERS) {
                for (int variant = 0; variant < 8; variant++) {
                    Unit[] script = mixed(600);
                    Unit u = script[90];
                    u.cls = RunHost.ENV;
                    u.late = true;
                    u.env = variant % 4 == 1 || variant == 7 ? Fakes.R : variant % 4 == 2 ? Fakes.D : Fakes.A;
                    final FakeHost host = new FakeHost(script);
                    host.envRetryable = variant != 3;
                    // variant 7: as variant 1, but the failed attempt had recorded failures of other units
                    host.envCollateral = variant == 7;
                    // variants 4-6: the pass says nothing and waits for the rest of the file itself
                    host.envSilentWait = variant >= 4 && variant < 7;
                    Run run = host.runPipelined(Fakes.config(w), true, new int[] {300, 450, 600}, 300, null,
                            () -> host.lateDeclines.get() >= 1);
                    int verdict = run.execute();
                    String where = w + " workers, variant " + variant;
                    // Variants 1 and 5: rejected again when the file is complete. Variant 7: what the pass
                    // rejects after an attempt that left other units' failures behind is declined.
                    int expected = variant % 4 == 0 ? Outcome.ACCEPT : variant == 1 || variant == 5 ? Outcome.REJECT
                            : Outcome.DECLINE;
                    assertEquals(expected, verdict, where + ": " + run.verdicts().explanation());
                    assertEquals(variant == 7, run.rejectionsTainted(), where);
                    if (variant == 7) {
                        assertTrue(run.verdicts().explanation().contains("UNCONFIRMED_REJECT"), run.verdicts().explanation());
                    }
                    assertEquals(variant % 4 == 0 ? -1 : 90, run.verdicts().decidingUnit(), where);
                    assertEquals(variant >= 3 && variant < 7 ? 0 : 1, host.envRetries.get(), where);
                    assertTrue(host.lateDeclines.get() >= 1, "the unit was tried while the file was loading: " + where);
                    if (variant == 3) {
                        // the host cannot decide the unit again: the decline of the first attempt counts
                        assertTrue(run.verdicts().explanation().contains("FORWARD_REFERENCE"), run.verdicts().explanation());
                    }
                    assertEquals("", host.describeViolations(), where);
                }
            }
        });
    }

    @Test
    void anErrorInTheLoaderIsRethrownAfterTheThreadsWereJoined() {
        assertTimeoutPreemptively(Duration.ofSeconds(120), () -> {
            final FakeHost host = new FakeHost(mixed(2000));
            host.loaded = 0;
            host.complete = false;
            RunConfig c = Fakes.config(4);
            Run.Loading loading = new Run.Loading() {
                @Override
                public void load(LoadProgress progress) {
                    host.loaded = 700;
                    progress.published(700);
                    throw new OutOfMemoryError("scripted");
                }
            };
            rlvm.api.EngineFactory f = host.soloFactory();
            Run run = new Run(c, host, loading, f, f);
            run.setMinUnitsPerWorker(1);
            run.setDiagnostics(null);
            assertThrows(OutOfMemoryError.class, run::execute);
            assertEquals(Outcome.DECLINE, run.verdicts().exitCode());
            assertEquals(0, host.sessionsOpen.get() + host.lanesOpen.get() + host.enginesOpen.get(), "everything was closed");
            assertEquals("", host.describeViolations());
        });
    }

    @Test
    void theRunDoesNotAcceptAFileTheLoaderDidNotFinish() {
        final FakeHost host = new FakeHost(mixed(900));
        host.loaded = 0;
        host.complete = false;
        Run.Loading loading = new Run.Loading() {
            @Override
            public void load(LoadProgress progress) {
                host.loaded = 500;
                progress.published(500);
                host.loaded = 900;
                progress.published(900);
                // returns without having reached the end of the file (complete stays false)
            }
        };
        rlvm.api.EngineFactory f = host.soloFactory();
        Run run = new Run(Fakes.config(3), host, loading, f, f);
        run.setMinUnitsPerWorker(1);
        run.setDiagnostics(null);
        assertEquals(Outcome.DECLINE, run.execute(), "fail closed");
        assertEquals(900, run.verdicts().acceptedCount(), "the published units were checked");
        assertEquals("", host.describeViolations());
    }
}
