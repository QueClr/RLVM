package rlvm.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rlvm.api.Engine;
import rlvm.api.EngineConfig;
import rlvm.api.EngineFactory;
import rlvm.api.Outcome;
import rlvm.api.Reason;
import rlvm.api.RunSignals;
import rlvm.api.WorkerContext;
import rlvm.driver.Fakes.FakeHost;
import rlvm.driver.Fakes.Unit;
import rlvm.load.LoadOptions;
import rlvm.load.LoadReport;
import rlvm.load.MalformedExportException;
import rlvm.load.UnsupportedExportException;
import rlvm.store.Store;

/** The run as a whole over fake units and engines: fail-closed behaviour, sessions, memory, statistics. */
class RunTest {

    private static final int[] WORKERS = {1, 2, 4, 8};

    // ---- fail closed ------------------------------------------------------------------------------------------

    @Test
    void anAcceptingRunAccepts() {
        for (int w : WORKERS) {
            FakeHost host = new FakeHost(Fakes.units(500));
            Run run = host.run(Fakes.config(w), true);
            assertEquals(Outcome.ACCEPT, run.execute());
            assertNull(run.verdicts().explanation());
            assertEquals("", host.describeViolations());
            assertThrows(IllegalStateException.class, run::execute, "execute may be called once");
        }
    }

    @Test
    void anEmptyFileIsAcceptedOnlyWhenTheLoadWasComplete() {
        FakeHost host = new FakeHost(Fakes.units(0));
        assertEquals(Outcome.ACCEPT, host.run(Fakes.config(4), true).execute());
        FakeHost partial = new FakeHost(Fakes.units(0));
        partial.complete = false;
        assertEquals(Outcome.DECLINE, partial.run(Fakes.config(4), true).execute());
    }

    @Test
    void anIncompleteLoadIsNeverAccepted() {
        for (int w : WORKERS) {
            Unit[] script = Fakes.units(300);
            FakeHost host = new FakeHost(script);
            host.complete = false;
            Run run = host.run(Fakes.config(w), false);
            assertEquals(Outcome.DECLINE, run.execute());
            assertEquals(300, run.verdicts().acceptedCount(), "the published units are still checked");
            assertTrue(run.verdicts().explanation().contains("end of input was not reached"), run.verdicts().explanation());

            // ... and a confirmed rejection among the published units still decides
            Unit[] bad = Fakes.units(300);
            bad[120].warm = Fakes.R;
            bad[120].strict = Fakes.R;
            bad[120].ref = Fakes.R;
            FakeHost host2 = new FakeHost(bad);
            host2.complete = false;
            assertEquals(Outcome.REJECT, host2.run(Fakes.config(w), false).execute());
        }
    }

    @Test
    void aUnitCountThatDiffersFromTheReadersIsNeverAccepted() {
        FakeHost host = new FakeHost(Fakes.units(50));
        RunConfig c = Fakes.config(2);
        c.expectedUnits = 51;
        Run run = host.run(c, true);
        assertEquals(Outcome.DECLINE, run.execute());
        assertTrue(run.verdicts().explanation().contains("the reader counted 51 declaration records"), run.verdicts().explanation());
        c.expectedUnits = 50;
        assertEquals(Outcome.ACCEPT, new FakeHost(Fakes.units(50)).run(c, true).execute());
    }

    @Test
    void anErrorInAnEngineIsRethrownAndNeverAccepted() {
        for (int w : WORKERS) {
            Unit[] script = Fakes.units(400);
            script[57].warm = Fakes.ERR;
            FakeHost host = new FakeHost(script);
            Run run = host.run(Fakes.config(w), false);
            AssertionError e = assertThrows(AssertionError.class, run::execute);
            assertTrue(e.getMessage().contains("scripted error at unit 57"));
            assertEquals(Outcome.EXIT_DECLINE, run.verdicts().exitCode());
            assertTrue(run.verdicts().hasInternalFailure());
            assertEquals(-1, run.verdicts().firstFailure(), "everything is cancelled");
            assertEquals(0, host.sessionsOpen.get());
            assertEquals(0, host.enginesOpen.get());
        }
    }

    @Test
    void anErrorInTheReferenceKernelDuringARecheckIsRethrown() {
        Unit[] script = Fakes.units(40);
        script[7].warm = Fakes.R;
        script[7].strict = Fakes.R;
        script[7].ref = Fakes.ERR;
        for (int w : new int[] {1, 4}) {
            FakeHost host = new FakeHost(Fakes.copy(script));
            Run run = host.run(Fakes.config(w), false);
            assertThrows(AssertionError.class, run::execute);
            assertEquals(Outcome.EXIT_DECLINE, run.verdicts().exitCode());
        }
    }

    @Test
    void aRuntimeExceptionOutsideAUnitBecomesADecline() {
        for (int w : WORKERS) {
            // the first lane cannot be created: no worker can run
            FakeHost host = new FakeHost(Fakes.units(200));
            host.failLaneFrom = 1;
            Run run = host.run(Fakes.config(w), true);
            assertEquals(Outcome.DECLINE, run.execute());
            assertTrue(run.verdicts().hasInternalFailure());
            assertTrue(run.verdicts().explanation().contains("internal error"), run.verdicts().explanation());
            assertEquals(0, host.enginesOpen.get());
        }
        // an engine factory that throws
        FakeHost host = new FakeHost(Fakes.units(10));
        EngineFactory broken = new EngineFactory() {
            @Override
            public Engine create(Store store, EngineConfig config) {
                throw new IllegalStateException("no engine");
            }
        };
        Run run = new Run(Fakes.config(1), host, broken, broken);
        run.setDiagnostics(null);
        assertEquals(Outcome.DECLINE, run.execute());
        assertTrue(run.verdicts().explanation().contains("no engine"), run.verdicts().explanation());
    }

    @Test
    void aRecheckThatCannotGetALaneDeclinesTheUnit() {
        // lanes: warm (1), environment (2); the third (reference re-check lane) fails
        Unit[] script = Fakes.units(20);
        script[5].warm = Fakes.R;
        FakeHost host = new FakeHost(script);
        host.failLaneFrom = 3;
        Run run = host.run(Fakes.config(1), true);
        assertEquals(Outcome.DECLINE, run.execute());
        assertEquals(5, run.verdicts().decidingUnit());
        assertTrue(run.verdicts().explanation().contains("re-check failed"), run.verdicts().explanation());
        assertFalse(run.verdicts().hasInternalFailure());
    }

    @Test
    void aFailingStatusMirrorDoesNotChangeTheVerdict() {
        FakeHost host = new FakeHost(Fakes.units(100));
        host.failMirror = true;
        Run run = host.run(Fakes.config(2), true);
        assertEquals(Outcome.ACCEPT, run.execute());
        long failures = 0;
        for (Worker w : run.workers()) {
            failures += w.mirrorFailures();
        }
        // each worker stops mirroring after its first failure (a worker may have had no unit at all)
        assertTrue(failures >= 1 && failures <= 2, "mirror failures: " + failures);
    }

    @Test
    void unknownAndUndecidedUnitsDecline() {
        Unit[] script = Fakes.units(30);
        script[11].cls = RunHost.UNKNOWN;
        for (int w : WORKERS) {
            FakeHost host = new FakeHost(Fakes.copy(script));
            Run run = host.run(Fakes.config(w), true);
            assertEquals(Outcome.DECLINE, run.execute());
            assertEquals(11, run.verdicts().decidingUnit());
            assertTrue(run.verdicts().explanation().contains("UNSUPPORTED_INPUT"), run.verdicts().explanation());
        }
    }

    @Test
    void skippedRecordsCountAsAccepted() {
        Unit[] script = Fakes.units(64);
        for (int i = 0; i < script.length; i += 3) {
            script[i].cls = RunHost.SKIP;
        }
        for (int w : WORKERS) {
            FakeHost host = new FakeHost(Fakes.copy(script));
            Run run = host.run(Fakes.config(w), false);
            assertEquals(Outcome.ACCEPT, run.execute());
            assertEquals(VerdictBoard.SKIPPED, run.verdicts().status(0));
            assertEquals(0, host.warmChecks.get(0));
            assertEquals(64, run.verdicts().acceptedCount());
        }
    }

    // ---- environment pass ---------------------------------------------------------------------------------------

    @Test
    void theEnvironmentPassStopsAtItsFirstFailureAndUnitsBelowAreStillChecked() {
        for (int w : WORKERS) {
            Unit[] script = Fakes.units(200);
            script[90].cls = RunHost.ENV;
            script[90].env = Fakes.R;
            script[150].cls = RunHost.ENV;
            script[150].env = Fakes.D;
            FakeHost host = new FakeHost(script);
            Run run = host.run(Fakes.config(w), true);
            assertEquals(Outcome.REJECT, run.execute(), w + " workers");
            assertEquals(90, run.verdicts().decidingUnit());
            assertEquals(90, host.envUnits(), "the frontier stays at the failed unit for good");
            assertTrue(run.verdicts().explanation().contains("INDUCTIVE_INVALID"), run.verdicts().explanation());
            for (int u = 0; u < 90; u++) {
                assertEquals(VerdictBoard.ACCEPTED, run.verdicts().status(u));
            }
            for (int u = 91; u < 200; u++) {
                assertEquals(VerdictBoard.NONE, run.verdicts().status(u));
                assertEquals(0, host.warmChecks.get(u));
            }
            assertEquals("", host.describeViolations());
        }
    }

    @Test
    void anEnvironmentPassRunBeforeTheDriverIsReadFromTheAdmission() {
        for (int w : WORKERS) {
            for (int outcome : new int[] {Fakes.A, Fakes.R, Fakes.D}) {
                Unit[] script = Fakes.units(60);
                script[20].cls = RunHost.ENV;
                script[20].env = outcome;
                script[40].cls = RunHost.ENV;
                FakeHost host = new FakeHost(script);
                host.preAdvanced = true;
                host.env.set(outcome == Fakes.A ? 45 : 20); // the earlier pass stopped at unit 45, or at its failure
                Run run = host.run(Fakes.config(w), true);
                // Accepted units below the frontier are read from the admission; the driver's own pass resumes
                // at the frontier and decides the failing unit itself.
                int expected = outcome == Fakes.A ? Outcome.ACCEPT : outcome == Fakes.R ? Outcome.REJECT : Outcome.DECLINE;
                assertEquals(expected, run.execute(), w + " workers, outcome " + outcome);
                assertEquals(outcome == Fakes.A ? -1 : 20, run.verdicts().decidingUnit());
                assertEquals("", host.describeViolations());
            }
        }
    }

    @Test
    void aRejectionBelowAnEnvironmentFailureWins() {
        for (int w : WORKERS) {
            Unit[] script = Fakes.units(200);
            script[90].cls = RunHost.ENV;
            script[90].env = Fakes.D;
            script[40].warm = Fakes.R;
            script[40].strict = Fakes.R;
            script[40].ref = Fakes.R;
            script[40].delayMicros = 2000; // found after the environment thread failed at 90
            FakeHost host = new FakeHost(script);
            Run run = host.run(Fakes.config(w), false);
            assertEquals(Outcome.REJECT, run.execute(), w + " workers");
            assertEquals(40, run.verdicts().decidingUnit());
        }
    }

    @Test
    void aNonTerminatingInductiveBlockBehindAnIllTypedDefinitionIsCancelled() {
        // The inductive block at 30 never finishes because unit 12 (rejected) is ill-typed. With several
        // workers the environment thread runs ahead: unit 12 must still reach a worker.
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            for (int w : WORKERS) {
                for (int batch : new int[] {1, 7, 4096}) {
                    Unit[] script = Fakes.units(120);
                    script[12].warm = Fakes.R;
                    script[12].strict = Fakes.R;
                    script[12].ref = Fakes.R;
                    script[30].cls = RunHost.ENV;
                    script[30].env = Fakes.LOOP;
                    FakeHost host = new FakeHost(script);
                    host.loopTimeoutNanos = 5_000_000_000L;
                    RunConfig c = Fakes.config(w);
                    c.envBatchUnits = batch;
                    Run run = host.run(c, false);
                    assertEquals(Outcome.REJECT, run.execute(), w + " workers, batch " + batch);
                    assertEquals(12, run.verdicts().decidingUnit());
                    assertEquals("", host.describeViolations(), w + " workers, batch " + batch);
                }
            }
        });
    }

    @Test
    void anEnvironmentPassThatOnlyPollsTheMemorySignalIsCancelledToo() {
        // The reference kernel, used through KernelOps by the environment pass, does not know its unit and
        // polls only memoryExhausted(): the environment lane's signal answers for it.
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            for (int w : new int[] {2, 4, 8}) {
                Unit[] script = Fakes.units(120);
                script[12].warm = Fakes.R;
                script[12].strict = Fakes.R;
                script[12].ref = Fakes.R;
                script[12].delayMicros = 2000;
                script[30].cls = RunHost.ENV;
                script[30].env = Fakes.LOOP_BLIND;
                FakeHost host = new FakeHost(script);
                host.loopTimeoutNanos = 5_000_000_000L;
                Run run = host.run(Fakes.config(w), false);
                assertEquals(Outcome.REJECT, run.execute(), w + " workers");
                assertEquals(12, run.verdicts().decidingUnit());
                assertEquals(VerdictBoard.NONE, run.verdicts().status(30), "the abandoned unit is not recorded");
                assertEquals("", host.describeViolations(), w + " workers");
                assertFalse(run.account().exhausted());
            }
        });
    }

    @Test
    void nonTerminatingUnitsBehindAFailureDoNotStarveIt() {
        // Every worker but one can get stuck in a unit that never finishes; the failing unit 3 is still
        // reached, because a worker's units ascend and given-back units never jump ahead of its own run.
        assertTimeoutPreemptively(Duration.ofSeconds(120), () -> {
            for (int w : WORKERS) {
                for (int chunk : new int[] {1, 2, 32}) {
                    Unit[] script = Fakes.units(400);
                    script[3].warm = Fakes.D;
                    script[3].cold = Fakes.D;
                    script[3].ref = Fakes.D;
                    script[3].delayMicros = 3000;
                    for (int u = 4; u < 400; u += 7) {
                        script[u].warm = Fakes.LOOP;
                    }
                    script[2].delayMicros = 1500; // slow: its worker gives the rest of the claim back
                    FakeHost host = new FakeHost(script);
                    host.loopTimeoutNanos = 5_000_000_000L;
                    RunConfig c = Fakes.config(w);
                    c.chunkUnits = chunk;
                    c.spillNanos = 1000;
                    Run run = host.run(c, false);
                    assertEquals(Outcome.DECLINE, run.execute(), w + " workers, chunk " + chunk);
                    assertEquals(3, run.verdicts().decidingUnit());
                    assertEquals("", host.describeViolations(), w + " workers, chunk " + chunk);
                }
            }
        });
    }

    // ---- sessions -------------------------------------------------------------------------------------------------

    @Test
    void sessionsAreResetByFootprint() {
        Unit[] script = Fakes.units(100);
        for (Unit u : script) {
            u.footprint = 1000;
        }
        FakeHost host = new FakeHost(script);
        RunConfig c = Fakes.config(1);
        c.sessionBytes = 9500; // exceeded after every 10th unit
        Run run = host.run(c, true);
        assertEquals(Outcome.ACCEPT, run.execute());
        assertEquals(10, run.workers()[0].sessionResets());
        assertEquals("", host.describeViolations());

        FakeHost big = new FakeHost(Fakes.copy(script));
        Run run2 = big.run(Fakes.config(1), true); // 16 MiB: never reached
        assertEquals(Outcome.ACCEPT, run2.execute());
        assertEquals(0, run2.workers()[0].sessionResets());
    }

    /** A primary engine that calls itself {@code name}: the driver looks the session cap up by that name. */
    private static EngineFactory named(final EngineFactory factory, final String name) {
        return new EngineFactory() {
            @Override
            public Engine create(Store store, EngineConfig config) {
                final Engine e = factory.create(store, config);
                return new Engine() {
                    @Override
                    public String name() {
                        return name;
                    }

                    @Override
                    public rlvm.api.Session newSession(WorkerContext ctx) {
                        return e.newSession(ctx);
                    }

                    @Override
                    public void environmentAdvanced(int units) {
                        e.environmentAdvanced(units);
                    }

                    @Override
                    public void dumpStats(rlvm.api.StatsSink sink) {
                        e.dumpStats(sink);
                    }

                    @Override
                    public void close() {
                        e.close();
                    }
                };
            }
        };
    }

    @Test
    void theSessionCapIsTheEnginesDefaultUnlessConfigured() {
        assertEquals(0, new RunConfig().sessionBytes, "automatic by default");
        assertEquals(4L << 20, RunConfig.defaultSessionBytes("machine"));
        assertEquals(16L << 20, RunConfig.defaultSessionBytes("reference"));
        assertEquals(16L << 20, RunConfig.defaultSessionBytes("fake-machine"));
        assertEquals(16L << 20, RunConfig.defaultSessionBytes(null));
        RunConfig explicit = new RunConfig();
        explicit.sessionBytes = 8L << 20;
        assertEquals(8L << 20, explicit.sessionBytesFor("machine"));
        assertEquals(8L << 20, explicit.sessionBytesFor("reference"));

        // 100 units of 1 MiB each: the cap decides how often the warm session is reset.
        long[] caps = {0, 0, 2L << 20};
        String[] names = {"machine", "some-engine", "machine"};
        long[] expectCap = {4L << 20, 16L << 20, 2L << 20};
        int[] expectResets = {20, 5, 33}; // after every 5th, 17th, 3rd unit
        for (int k = 0; k < caps.length; k++) {
            Unit[] script = Fakes.units(100);
            for (Unit u : script) {
                u.footprint = 1L << 20;
            }
            FakeHost host = new FakeHost(script);
            RunConfig c = Fakes.config(1);
            c.sessionBytes = caps[k];
            host.coldBytes = c.memoryLimitBytes;
            Run run = new Run(c, host, named(host.primaryFactory(), names[k]), host.referenceFactory());
            run.setMinUnitsPerWorker(1);
            run.setDiagnostics(null);
            assertEquals(Outcome.ACCEPT, run.execute(), names[k]);
            assertEquals(expectCap[k], run.sessionBytes(), names[k]);
            assertEquals(expectResets[k], run.workers()[0].sessionResets(), names[k] + ", cap " + caps[k]);
            assertEquals("", host.describeViolations());
        }
    }

    // ---- passes -----------------------------------------------------------------------------------------------------

    @Test
    void laterPassesCheckEveryUnitAgainAndNeverChangeTheVerdict() {
        for (int w : new int[] {1, 4}) {
            Unit[] script = Fakes.units(300);
            FakeHost host = new FakeHost(script);
            host.failMirror = true; // the fake host's status column takes one write per unit; the driver tolerates the refusal
            RunConfig c = Fakes.config(w);
            c.set("passes", "3");
            Run run = host.run(c, false);
            assertEquals(Outcome.ACCEPT, run.execute(), w + " workers");
            long[][] passes = run.passResults();
            assertEquals(3, passes.length);
            for (long[] p : passes) {
                assertEquals(Outcome.ACCEPT, p[0]);
                assertTrue(p[1] >= p[2] && p[2] >= 0, "wall clock of the pass and of its checking phase");
            }
            for (int u = 0; u < script.length; u++) {
                assertEquals(3, host.warmChecks.get(u), "unit " + u + " is checked once per pass");
            }
            assertEquals("", host.describeViolations());
            assertEquals(0, host.sessionsOpen.get());
            assertEquals(0, host.enginesOpen.get());
        }
        // The verdict is the first pass's, and a run that does not accept the whole export makes no further pass.
        Unit[] script = Fakes.units(50);
        script[7].warm = Fakes.R;
        script[7].strict = Fakes.R;
        script[7].ref = Fakes.R;
        FakeHost host = new FakeHost(script);
        RunConfig c = Fakes.config(4);
        c.passes = 3;
        Run run = host.run(c, false);
        assertEquals(Outcome.REJECT, run.execute());
        assertEquals(7, run.verdicts().decidingUnit());
        assertEquals(1, run.passResults().length);
        assertEquals(Outcome.REJECT, run.passResults()[0][0]);
        assertEquals(1, host.warmChecks.get(7));
        assertEquals("", host.describeViolations());
        // One pass by default: nothing but the run itself.
        FakeHost plain = new FakeHost(Fakes.units(20));
        Run one = plain.run(Fakes.config(1), true);
        assertEquals(Outcome.ACCEPT, one.execute());
        assertEquals(1, one.passResults().length);
        assertEquals(1, plain.warmChecks.get(3));
        assertThrows(IllegalArgumentException.class, () -> new RunConfig().set("passes", "0"));
    }

    @Test
    void keptEnginesServeEveryPass() {
        for (int w : new int[] {1, 4}) {
            for (boolean same : new boolean[] {false, true}) {
                Unit[] script = Fakes.units(300);
                script[50].warm = Fakes.D; // the cold stage accepts: the strict / reference instances exist too
                script[60].warm = Fakes.R;
                script[60].strict = Fakes.A; // a warm anomaly: strict primary engine
                // how many engine instances one pass makes
                FakeHost once = new FakeHost(Fakes.copy(script));
                once.failMirror = true;
                assertEquals(Outcome.ACCEPT, once.run(Fakes.config(w), same).execute());
                int perPass = once.enginesCreated.get();
                assertTrue(perPass >= 1);
                // three passes, new engines each (the default)
                FakeHost fresh = new FakeHost(Fakes.copy(script));
                fresh.failMirror = true;
                RunConfig c = Fakes.config(w);
                c.passes = 3;
                assertEquals(Outcome.ACCEPT, fresh.run(c, same).execute());
                assertEquals(3 * perPass, fresh.enginesCreated.get(), w + " workers, same " + same);
                // three passes over the engines of the first
                FakeHost host = new FakeHost(Fakes.copy(script));
                host.failMirror = true;
                RunConfig k = Fakes.config(w);
                k.set("passes", "3");
                k.set("passes-keep", "engines");
                Run run = host.run(k, same);
                assertEquals(Outcome.ACCEPT, run.execute(), w + " workers, same " + same);
                assertEquals(3, run.passResults().length);
                assertEquals(perPass, host.enginesCreated.get(), "one set of engines for all passes");
                assertEquals(0, host.enginesOpen.get(), "closed after the last pass");
                assertEquals(0, host.sessionsOpen.get());
                for (int u = 0; u < script.length; u++) {
                    assertEquals(3, host.warmChecks.get(u), "unit " + u + " is checked once per pass");
                }
                assertEquals("", host.describeViolations());
            }
        }
        RunConfig bad = new RunConfig();
        assertThrows(IllegalArgumentException.class, () -> bad.set("passes-keep", "yes"));
        bad.set("passes-keep", "engines");
        assertTrue(bad.passesKeepEngines);
        bad.set("passes-keep", "none");
        assertFalse(bad.passesKeepEngines);
    }

    // ---- shards (measurement) -----------------------------------------------------------------------------------------

    @Test
    void aShardRunChecksItsShardOnlyAndNeverAccepts() {
        for (int w : new int[] {1, 4}) {
            Unit[] script = Fakes.units(3000);
            FakeHost host = new FakeHost(script);
            host.failMirror = true;
            RunConfig c = Fakes.config(w);
            c.set("shard", "1/2");
            c.set("passes", "2");
            c.explain = true;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            Run run = host.run(c, false);
            run.setDiagnostics(new PrintStream(bytes, true, StandardCharsets.UTF_8));
            assertEquals(Outcome.DECLINE, run.execute(), "a shard run never accepts (" + w + " workers)");
            String err = bytes.toString(StandardCharsets.UTF_8);
            assertTrue(err.contains("only shard 1 of 2 was checked"), err);
            assertTrue(err.contains("rlvm: pass 2 of 2: accept (shard 1/2 only)"), err);
            assertEquals(2, run.passResults().length);
            for (int u = 0; u < script.length; u++) {
                boolean mine = (u >> RunConfig.SHARD_BLOCK_LOG2) % 2 == 1;
                assertEquals(mine ? 2 : 0, host.warmChecks.get(u), "unit " + u);
            }
            assertEquals("", host.describeViolations());
        }
        // a failure inside the shard is the verdict's as in any run, and ends the passes
        Unit[] script = Fakes.units(3000);
        script[1500].warm = Fakes.R;
        script[1500].strict = Fakes.R;
        script[1500].ref = Fakes.R;
        FakeHost host = new FakeHost(script);
        RunConfig c = Fakes.config(4);
        c.set("shard", "1/2");
        c.passes = 3;
        Run run = host.run(c, false);
        assertEquals(Outcome.REJECT, run.execute());
        assertEquals(1500, run.verdicts().decidingUnit());
        assertEquals(1, run.passResults().length);
        RunConfig bad = new RunConfig();
        assertThrows(IllegalArgumentException.class, () -> bad.set("shard", "2/2"));
        assertThrows(IllegalArgumentException.class, () -> bad.set("shard", "x"));
        assertThrows(IllegalArgumentException.class, () -> bad.set("shard", null));
        assertEquals(0, bad.shardCount);
    }

    // ---- slow units -------------------------------------------------------------------------------------------------

    @Test
    void aSlowUnitReturnsTheRestOfItsClaimWhileItIsStillRunning() {
        // Unit 1 is heavy: it does not end before unit 31, the last unit of the claim it is part of, has
        // been checked. Its worker therefore has to hand units 2..31 to the other workers while unit 1
        // runs (at a poll of the session), not after it; else nobody checks unit 31 and the run hangs.
        assertTimeoutPreemptively(Duration.ofSeconds(120), () -> {
            for (int w : new int[] {2, 4, 8}) {
                for (boolean same : new boolean[] {true, false}) {
                    Unit[] script = Fakes.units(32 * w);
                    script[1].warm = Fakes.HOLD;
                    script[1].holdFor = 31;
                    FakeHost host = new FakeHost(script);
                    host.loopTimeoutNanos = 10_000_000_000L;
                    RunConfig c = Fakes.config(w);
                    c.spillNanos = 1_000_000L;
                    Run run = host.run(c, same);
                    assertEquals(Outcome.ACCEPT, run.execute(), w + " workers");
                    assertEquals("", host.describeViolations(), w + " workers");
                    long released = 0;
                    for (Worker worker : run.workers()) {
                        released += worker.restsReleasedInUnit();
                    }
                    assertTrue(released >= 1, "the rest of the claim was given back inside the unit");
                    assertTrue(run.scheduler().spilledUnits() >= 30, "spilled " + run.scheduler().spilledUnits());
                    for (int u = 0; u < script.length; u++) {
                        assertEquals(1, host.warmChecks.get(u), "unit " + u + " checked once");
                    }
                }
            }
        });
    }

    @Test
    void twoHeavyUnitsOfOneClaimRunOnTwoWorkers() {
        // The case of the reachability corpus: units 1 and 2 are both heavy and lie in one claim. Each
        // ends only when unit 31 was checked, so they must overlap on two workers while a third one
        // checks the rest of the claim. Before the in-unit give-back they ran one after the other.
        assertTimeoutPreemptively(Duration.ofSeconds(120), () -> {
            for (int w : new int[] {3, 4, 8}) {
                Unit[] script = Fakes.units(32 * w);
                script[1].warm = Fakes.HOLD;
                script[1].holdFor = 31;
                script[2].warm = Fakes.HOLD;
                script[2].holdFor = 31;
                FakeHost host = new FakeHost(script);
                host.loopTimeoutNanos = 10_000_000_000L;
                RunConfig c = Fakes.config(w);
                c.spillNanos = 1_000_000L;
                Run run = host.run(c, false);
                assertEquals(Outcome.ACCEPT, run.execute(), w + " workers");
                assertEquals("", host.describeViolations(), w + " workers");
                long released = 0;
                for (Worker worker : run.workers()) {
                    released += worker.restsReleasedInUnit();
                }
                assertTrue(released >= 2, "both heavy units gave the rest of their claim back: " + released);
            }
        });
    }

    @Test
    void aSlowUnitKeepsItsClaimWithOneWorkerAndWithSpillOff() {
        // Nobody to give units back to (one worker), or the rule is switched off: the claim stays whole.
        for (int w : new int[] {1, 4}) {
            Unit[] script = Fakes.units(128);
            script[5].warm = Fakes.HOLD; // slow (3 ms), then one poll; unit 0 is decided long before
            script[5].holdFor = 0;
            script[5].delayMicros = 3000;
            FakeHost host = new FakeHost(script);
            RunConfig c = Fakes.config(w);
            c.spillNanos = w == 1 ? 1 : 0;
            Run run = host.run(c, false);
            assertEquals(Outcome.ACCEPT, run.execute());
            assertEquals(0, run.scheduler().spilledUnits(), w + " workers");
            assertEquals(0, run.workers()[0].restsReleasedInUnit());
            assertEquals("", host.describeViolations());
        }
    }

    @Test
    void sessionsAreResetAfterAnyOutcomeButAccept() {
        Unit[] script = Fakes.units(10);
        script[4].warm = Fakes.R; // strict accepts: WARM-ANOMALY, the unit is accepted
        script[7].warm = Fakes.D; // cold accepts
        FakeHost host = new FakeHost(script);
        Run run = host.run(Fakes.config(1), false);
        assertEquals(Outcome.ACCEPT, run.execute());
        assertEquals(2, run.workers()[0].sessionResets());
        assertEquals(2, run.workers()[0].rechecks());
        assertEquals("", host.describeViolations());
    }

    @Test
    void sessionsOnlyMoveForwardInRank() {
        Unit[] script = Fakes.units(12);
        FakeHost host = new FakeHost(script);
        // ranks step back at units 4 and 9 (the fake session reports a backward move without reset as a violation)
        int[] ranks = {0, 1, 2, 3, 3, 4, 5, 6, 7, 2, 8, 9};
        System.arraycopy(ranks, 0, host.rank, 0, ranks.length);
        Run run = host.run(Fakes.config(1), true);
        assertEquals(Outcome.ACCEPT, run.execute());
        assertEquals("", host.describeViolations());
        assertEquals(2, run.workers()[0].sessionResets());
    }

    @Test
    void aSessionThatThrewIsDiscardedNotReused() {
        Unit[] script = Fakes.units(10);
        script[3].warm = Fakes.T; // reference accepts
        FakeHost host = new FakeHost(script);
        Run run = host.run(Fakes.config(1), false);
        assertEquals(Outcome.ACCEPT, run.execute());
        // warm + environment + reference re-check session, and one replacement for the discarded warm session
        assertEquals(4, host.sessionsCreated.get());
        assertEquals(0, host.sessionsOpen.get());
        assertEquals("", host.describeViolations());
    }

    // ---- memory account ---------------------------------------------------------------------------------------------

    @Test
    void memoryAccountSumsSlotsAndTripsTheWatermark() {
        MemoryAccount a = new MemoryAccount(1000, 3);
        assertFalse(a.exhausted());
        a.setShared(400);
        a.report(0, 300);
        a.report(1, 300);
        assertFalse(a.exhausted());
        assertEquals(1000, a.accountedBytes());
        a.report(2, 1);
        assertTrue(a.exhausted());
        assertEquals(1001, a.peakBytes());
        a.report(1, 0);
        assertFalse(a.exhausted(), "back under the limit after a reset");
        assertEquals(1001, a.peakBytes());
        a.setShared(2000);
        assertTrue(a.exhausted());
        a.report(0, Long.MAX_VALUE);
        assertTrue(a.exhausted(), "no overflow");
    }

    @Test
    void aUnitThatExhaustsTheRunsMemoryIsDeclinedNotKilled() {
        for (int w : WORKERS) {
            Unit[] script = Fakes.units(60);
            script[20].warm = Fakes.MEM;
            script[20].cold = Fakes.MEM;
            script[20].ref = Fakes.MEM;
            script[20].footprint = 3L << 20; // above the limit below
            FakeHost host = new FakeHost(script);
            RunConfig c = Fakes.config(w);
            c.memoryLimitBytes = 2L << 20;
            Run run = host.run(c, false);
            assertEquals(Outcome.DECLINE, run.execute(), w + " workers");
            assertEquals(20, run.verdicts().decidingUnit());
            assertTrue(run.verdicts().explanation().contains("BUDGET_MEMORY"), run.verdicts().explanation());
            assertTrue(run.account().peakBytes() > c.memoryLimitBytes);
            assertFalse(run.account().exhausted(), "sessions were reset");
            assertEquals(1, host.coldChecks.get(20), "retried cold by the primary engine");
            assertEquals(1, host.refChecks.get(20), "then by the reference kernel");
            assertEquals("", host.describeViolations());
        }
    }

    @Test
    void aStoreAboveTheMemoryLimitDeclines() {
        Unit[] script = Fakes.units(10);
        for (Unit u : script) {
            u.warm = Fakes.MEM;
            u.cold = Fakes.MEM;
            u.ref = Fakes.MEM;
        }
        FakeHost host = new FakeHost(script);
        host.shared = 100L << 20;
        RunConfig c = Fakes.config(1);
        c.memoryLimitBytes = 50L << 20;
        Run run = host.run(c, true);
        assertEquals(Outcome.DECLINE, run.execute());
        assertEquals(0, run.verdicts().decidingUnit());
    }

    @Test
    void aMemoryHeavyUnitUnderTheLimitIsAccepted() {
        Unit[] script = Fakes.units(30);
        script[9].warm = Fakes.MEM;
        script[9].footprint = 5L << 20;
        FakeHost host = new FakeHost(script);
        RunConfig c = Fakes.config(2);
        Run run = host.run(c, true);
        assertEquals(Outcome.ACCEPT, run.execute());
        assertTrue(run.account().peakBytes() >= 5L << 20, "the account saw the unit while it ran: " + run.account().peakBytes());
    }

    @Test
    void theWarmBudgetIsTheHeadroomAndHasNoStepOrDepthLimit() {
        long gib = 1L << 30;
        long[][] cases = {
            // workers, shared bytes, expected warm bytes per unit: the whole headroom, whatever the worker count
            {1, gib, 11 * gib}, {2, gib, 11 * gib}, {8, gib, 11 * gib}, {4, 4 * gib, 8 * gib}, {8, 11 * gib, gib},
            {8, 12 * gib - 1, 256L << 20},
        };
        for (long[] c : cases) {
            FakeHost host = new FakeHost(Fakes.units(64));
            host.shared = c[1];
            RunConfig config = Fakes.config((int) c[0]);
            config.unitBytes = 0; // automatic
            Run run = host.run(config, true);
            assertEquals(Outcome.ACCEPT, run.execute());
            assertEquals(c[2], host.warmBudgetBytes, c[0] + " workers, " + c[1] + " shared bytes");
            assertEquals(Integer.MAX_VALUE, host.warmBudgetDepth);
            assertEquals("", host.describeViolations(), "no step limit in warm sessions");
        }
    }

    // ---- configuration ----------------------------------------------------------------------------------------------

    @Test
    void stopAfterDeclines() {
        FakeHost host = new FakeHost(Fakes.units(40));
        RunConfig c = Fakes.config(2);
        c.stopAfter = "load";
        Run run = host.run(c, true);
        assertEquals(Outcome.DECLINE, run.execute());
        assertEquals(0, host.enginesCreated.get(), "nothing is checked");
        assertTrue(run.verdicts().explanation().contains("stop-after=load"), run.verdicts().explanation());

        Unit[] script = Fakes.units(40);
        script[10].cls = RunHost.ENV;
        FakeHost host2 = new FakeHost(script);
        RunConfig c2 = Fakes.config(2);
        c2.stopAfter = "env";
        Run run2 = host2.run(c2, true);
        assertEquals(Outcome.DECLINE, run2.execute());
        assertEquals(40, host2.envUnits(), "the environment pass ran to the end");
        assertEquals(0, host2.warmChecks.get(0), "no unit was checked");
        assertTrue(run2.verdicts().explanation().contains("stop-after=env"), run2.verdicts().explanation());

        RunConfig c3 = Fakes.config(1);
        c3.stopAfter = "nonsense";
        Run run3 = new FakeHost(Fakes.units(3)).run(c3, true);
        assertEquals(Outcome.DECLINE, run3.execute(), "an unknown phase is not ignored");
    }

    @Test
    void deferredFeaturesFailLoudly() {
        EngineFactory f = new FakeHost(Fakes.units(1)).soloFactory();
        assertThrows(UnsupportedOperationException.class, () -> new Run(new RunConfig(), f, f));
        RunConfig c = Fakes.config(1);
        c.order = OrderPolicy.Kind.NAMESPACE_WINDOW;
        assertThrows(UnsupportedOperationException.class, () -> new FakeHost(Fakes.units(1)).run(c, true).execute());
        assertThrows(IllegalArgumentException.class, () -> new Run(new RunConfig(), (Wiring) null, f, f));
    }

    /** {@code -X no-fallback}: the engine under test alone; the reference kernel never rescues it. */
    @Test
    void withoutFallbackTheReferenceKernelNeverTurnsANonAcceptIntoAnAccept() {
        // {warm, cold, strict, ref} -> expected verdict with fallback, without
        int a = Fakes.A;
        int r = Fakes.R;
        int d = Fakes.D;
        int t = Fakes.T;
        int[][] cases = {
            {d, a, a, a, Outcome.ACCEPT, Outcome.ACCEPT}, // heavy unit: the engine's own cold attempt accepts
            {d, d, a, a, Outcome.ACCEPT, Outcome.DECLINE}, // declined twice: no reference
            {t, a, a, a, Outcome.ACCEPT, Outcome.DECLINE}, // exception: no reference
            {r, r, r, a, Outcome.ACCEPT, Outcome.DECLINE}, // machine incomplete: not papered over
            {r, r, a, a, Outcome.ACCEPT, Outcome.ACCEPT}, // warm anomaly: the engine's own strict run accepts
            {r, r, r, r, Outcome.REJECT, Outcome.REJECT}, // a rejection is still confirmed
            {d, r, r, r, Outcome.REJECT, Outcome.REJECT},
            {d, r, r, a, Outcome.ACCEPT, Outcome.DECLINE},
        };
        for (int[] c : cases) {
            for (int fallback = 0; fallback < 2; fallback++) {
                Unit[] script = Fakes.units(12);
                script[5].warm = c[0];
                script[5].cold = c[1];
                script[5].strict = c[2];
                script[5].ref = c[3];
                FakeHost host = new FakeHost(script);
                RunConfig config = Fakes.config(2);
                config.referenceFallback = fallback == 1;
                Run run = host.run(config, false);
                String where = java.util.Arrays.toString(c) + (fallback == 1 ? " with" : " without") + " fallback";
                assertEquals(fallback == 1 ? c[4] : c[5], run.execute(), where + ": " + run.verdicts().explanation());
                if (fallback == 0 && c[5] == Outcome.DECLINE) {
                    assertEquals(5, run.verdicts().decidingUnit(), where);
                    if (c[0] != r && c[1] != r) {
                        assertEquals(0, host.refChecks.get(5), "the reference kernel was not asked: " + where);
                    }
                }
                assertEquals("", host.describeViolations(), where);
            }
        }
        // no effect when the reference kernel is the primary engine
        Unit[] script = Fakes.units(12);
        script[5].warm = d;
        FakeHost host = new FakeHost(script);
        RunConfig config = Fakes.config(1);
        config.referenceFallback = false;
        assertEquals(Outcome.ACCEPT, host.run(config, true).execute());
        RunConfig byName = new RunConfig();
        byName.set("no-fallback", null);
        assertFalse(byName.referenceFallback);
        byName.set("no-fallback", "false");
        assertTrue(byName.referenceFallback);
    }

    @Test
    void withoutRecheckTheprimaryRejectionIsFinal() {
        Unit[] script = Fakes.units(10);
        script[6].warm = Fakes.R;
        script[6].strict = Fakes.A; // would be a WARM-ANOMALY
        FakeHost host = new FakeHost(script);
        RunConfig c = Fakes.config(1);
        c.recheckRejections = false;
        Run run = host.run(c, false);
        assertEquals(Outcome.REJECT, run.execute());
        assertEquals(0, host.strictChecks.get(6) + host.refChecks.get(6));
    }

    // ---- input failures (through a Wiring) ----------------------------------------------------------------------------

    /** A wiring whose load fails; it never produces a store. */
    private static final class FailingWiring implements Wiring {
        private final Exception failure;
        int loads;

        FailingWiring(Exception failure) {
            this.failure = failure;
        }

        @Override
        public Store newStore(long inputBytes) {
            return null;
        }

        @Override
        public void load(Store store, LoadOptions options) throws Exception {
            loads++;
            if (options.mode != LoadOptions.Mode.BATCH) {
                throw new AssertionError("the driver loads in batch mode");
            }
            throw failure;
        }

        @Override
        public LoadReport loadReport() {
            return null;
        }

        @Override
        public WorkerContext newWorkerContext(int workerId, Store store, RunSignals signals, long overlayMaxBytes) {
            throw new AssertionError("no context without a store");
        }
    }

    private static int runWith(Exception failure, String expectInExplanation) {
        EngineFactory f = new FakeHost(Fakes.units(1)).soloFactory();
        FailingWiring wiring = new FailingWiring(failure);
        RunConfig c = Fakes.config(4);
        Run run = new Run(c, wiring, f, f);
        run.setDiagnostics(null);
        int code = run.execute();
        assertEquals(1, wiring.loads);
        assertTrue(run.verdicts().explanation().contains(expectInExplanation), run.verdicts().explanation());
        return code;
    }

    @Test
    void malformedInputIsRejectedAndEverythingElseDeclined() {
        assertEquals(Outcome.REJECT, runWith(new MalformedExportException("invalid JSON", 17), "invalid JSON (line 17)"));
        assertEquals(Outcome.DECLINE, runWith(new UnsupportedExportException("id beyond 63 bits"), "id beyond 63 bits"));
        assertEquals(Outcome.DECLINE, runWith(new IOException("no such file"), "no such file"));
        assertEquals(Outcome.DECLINE, runWith(new IllegalStateException("loader bug"), "loader bug"));
        assertEquals(Outcome.DECLINE, runWith(new UnsupportedOperationException("TODO"), "TODO"));
    }

    @Test
    void aCallerCanReportTheInputFailureItself() {
        FakeHost host = new FakeHost(Fakes.units(20));
        Run run = host.run(Fakes.config(2), true);
        run.inputFailure(Reason.MALFORMED_EXPORT, "blank line");
        assertEquals(Outcome.REJECT, run.execute());
        assertEquals(0, host.enginesCreated.get(), "nothing is checked after a malformed input");

        FakeHost host2 = new FakeHost(Fakes.units(20));
        Run run2 = host2.run(Fakes.config(2), true);
        run2.inputFailure(Reason.UNSUPPORTED_INPUT, "too large");
        assertEquals(Outcome.DECLINE, run2.execute());
        assertEquals(20, run2.verdicts().acceptedCount(), "the published units are still checked");
    }

    // ---- diagnostics and statistics -----------------------------------------------------------------------------------

    @Test
    void oneLineOnStderrForAnythingButAccept() {
        Unit[] script = Fakes.units(10);
        script[4].warm = Fakes.R;
        script[4].strict = Fakes.R;
        script[4].ref = Fakes.R;
        FakeHost host = new FakeHost(script);
        RunConfig c = Fakes.config(1);
        c.explain = true;
        c.logAnomalies = true;
        Run run = host.run(c, false);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        run.setDiagnostics(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        assertEquals(Outcome.REJECT, run.execute());
        String err = bytes.toString(StandardCharsets.UTF_8);
        assertEquals(1, err.split("\n").length, err);
        assertTrue(err.startsWith("rlvm: reject: unit 4 'decl4' rejected (DECL_TYPE_MISMATCH): scripted rejection of unit 4"), err);

        FakeHost ok = new FakeHost(Fakes.units(10));
        Run run2 = ok.run(c, false);
        ByteArrayOutputStream bytes2 = new ByteArrayOutputStream();
        run2.setDiagnostics(new PrintStream(bytes2, true, StandardCharsets.UTF_8));
        assertEquals(Outcome.ACCEPT, run2.execute());
        assertEquals("", bytes2.toString(StandardCharsets.UTF_8), "nothing on accept");

        Unit[] anomaly = Fakes.units(10);
        anomaly[2].warm = Fakes.WARMTH;
        Run run3 = new FakeHost(anomaly).run(c, false);
        ByteArrayOutputStream bytes3 = new ByteArrayOutputStream();
        run3.setDiagnostics(new PrintStream(bytes3, true, StandardCharsets.UTF_8));
        assertEquals(Outcome.ACCEPT, run3.execute());
        assertTrue(bytes3.toString(StandardCharsets.UTF_8).startsWith("rlvm: WARM-ANOMALY unit 2 'decl2'"));
    }

    @Test
    void statisticsAreWrittenAsJson(@TempDir Path dir) throws IOException {
        Unit[] script = Fakes.units(300);
        script[100].warm = Fakes.D; // cold accepts
        script[200].cls = RunHost.ENV;
        for (int w : new int[] {1, 4}) {
            FakeHost host = new FakeHost(Fakes.copy(script));
            RunConfig c = Fakes.config(w);
            c.statsFile = dir.resolve("stats-" + w + ".json");
            Run run = host.run(c, false);
            assertEquals(Outcome.ACCEPT, run.execute());
            assertEquals("", host.describeViolations());
            String json = Files.readString(c.statsFile);
            assertTrue(json.startsWith("{") && json.trim().endsWith("}"), json);
            assertBalanced(json);
            assertTrue(json.contains("\"driver.verdict\": \"accept\""), json);
            assertTrue(json.contains("\"driver.units\": 300"), json);
            assertTrue(json.contains("\"driver.workers\": " + w), json);
            assertTrue(json.contains("\"engine.primary.checks\": 1"), json);
            assertTrue(json.contains("\"engine.reference.name\": \"fake-reference\""), json);
            assertTrue(json.contains("\"check\": {\"wallNanos\": "), json);
            assertTrue(json.contains("\"rlvm-worker-0\": {\"cpuNanos\": "), json);
            assertTrue(json.contains("\"count\": 299"), json);
            assertTrue(json.contains("\"declined\": 1"), json);
            assertNotNull(run.stats().value("driver.memory.peakAccountedBytes"));
            assertTrue(run.stats().phaseNanos("total") >= run.stats().phaseNanos("check"));
            // where the wall clock went: one row per worker, the frontier's time line, the time stamps
            assertTrue(json.contains("\"driver.workerTimes\": {\"columns\": [\"wallNanos\", \"cpuNanos\", \"runQueueNanos\", "
                    + "\"unitNanos\", \"claimNanos\", \"waitEnvNanos\", \"waitLoaderNanos\", \"waitTailNanos\""), json);
            assertTrue(json.contains("\"driver.series.worker-" + (run.workerCount() - 1) + "\": {\"columns\": [\"atNanos\", \"accepted\""), json);
            assertNotNull(run.stats().value("driver.time.checkStartNanos"));
            assertNotNull(run.stats().value("driver.time.checkEndNanos"));
            assertEquals(w > 1, run.stats().value("driver.env.advanceNanos") != null, "the environment thread's account");
            assertEquals(w > 1, json.contains("\"driver.series.env\""));
            long units = 0;
            for (Worker worker : run.workers()) {
                long[] t = worker.times();
                assertEquals(Worker.TIME_COLUMNS.length, t.length);
                assertTrue(t[0] >= t[3] && t[3] >= 0, "a worker's wall clock covers its units: " + t[0] + " / " + t[3]);
                assertTrue(t[4] >= t[5] + t[6] + t[7], "the parks are part of the time inside claim()");
                assertTrue(t[15] < 0 || (t[17] >= t[16] && t[16] >= t[15] && t[15] >= t[14]), "start, first claim, last unit, end");
                units += worker.series()[worker.series().length - 1][1];
            }
            assertEquals(299, units, "the last point of the series holds every unit the workers accepted");
        }
        // every later pass writes statistics of its own, beside the run's
        FakeHost again = new FakeHost(Fakes.copy(script));
        again.failMirror = true;
        RunConfig c2 = Fakes.config(4);
        c2.passes = 3;
        c2.statsFile = dir.resolve("passes.json");
        assertEquals(Outcome.ACCEPT, again.run(c2, false).execute());
        assertTrue(Files.readString(c2.statsFile).contains("\"driver.pass.3.verdict\": \"accept\""));
        for (int k = 2; k <= 3; k++) {
            String pass = Files.readString(dir.resolve("passes.json.pass" + k));
            assertBalanced(pass);
            assertTrue(pass.contains("\"driver.workerTimes\""), pass);
            assertTrue(pass.contains("\"count\": 299"), pass);
        }
        // disabled statistics record nothing
        RunStats off = new RunStats(false);
        assertEquals(0, off.phaseStart("x"));
        off.unit(1, 1, 1, 1, 0);
        off.put("k", 1L);
        assertNull(off.value("k"));
        assertEquals("{}", off.toJson());
    }

    @Test
    void statsKeepTheHeaviestUnits() {
        RunStats s = new RunStats(true);
        for (int u = 0; u < 500; u++) {
            s.unit(u, u % 4, (u * 7919L) % 1000, u, 0);
        }
        s.put("a.b", "x\"y\n");
        s.put("n", 1.5);
        s.table("t", new String[] {"c1", "c2"}, new long[][] {{1, 2}, {3, 4}}, new String[] {"r1", null});
        String json = s.toJson();
        assertBalanced(json);
        assertTrue(json.contains("\"a.b\": \"x\\\"y\\n\""), json);
        assertTrue(json.contains("{\"label\": \"r1\", \"values\": [1, 2]}"), json);
        // two lists of 50: the heaviest by steps ("top") and the slowest by wall time ("slow")
        String topList = json.substring(json.indexOf("\"top\""), json.indexOf("\"slow\""));
        String slowList = json.substring(json.indexOf("\"slow\""), json.indexOf("\"histogram\""));
        assertEquals(50, topList.split("\"unit\": ").length - 1);
        assertEquals(50, slowList.split("\"unit\": ").length - 1);
        // the slowest of all (unit 499 took 499 ns) is listed first, and the histogram counts every unit
        assertTrue(slowList.startsWith("{\"unit\": 499,", slowList.indexOf("{\"unit\"")), slowList.substring(0, 60));
        assertTrue(json.contains("\"histogram\": [{\"belowMicros\": 1, \"units\": 500,"), json);
        // a worker's own statistics merge into the run's
        UnitStats own = new UnitStats();
        own.unit(9000, 1, 5_000_000L, 7_000_000L, 0);
        s.mergeUnits(own);
        String merged = s.toJson();
        assertTrue(merged.contains("\"units\": {\"count\": 501,"), merged);
        int firstTop = merged.indexOf("{\"unit\"", merged.indexOf("\"top\""));
        assertTrue(merged.startsWith("{\"unit\": 9000, \"worker\": 1, \"steps\": 5000000, \"nanos\": 7000000,", firstTop));
        int firstSlow = merged.indexOf("{\"unit\"", merged.indexOf("\"slow\""));
        assertTrue(merged.startsWith("{\"unit\": 9000, \"worker\": 1, \"steps\": 5000000, \"nanos\": 7000000,", firstSlow));
        // the heaviest of all is listed first
        long max = 0;
        for (int u = 0; u < 500; u++) {
            max = Math.max(max, (u * 7919L) % 1000);
        }
        int first = json.indexOf("\"steps\": ", json.indexOf("\"top\""));
        assertTrue(json.startsWith("\"steps\": " + max + ",", first), json.substring(first, first + 30));
        assertTrue(RunStats.currentThreadCpuNanos() >= -1);
    }

    private static void assertBalanced(String json) {
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (inString) {
                if (ch == '\\') {
                    i++;
                } else if (ch == '"') {
                    inString = false;
                }
            } else if (ch == '"') {
                inString = true;
            } else if (ch == '{' || ch == '[') {
                depth++;
            } else if (ch == '}' || ch == ']') {
                depth--;
                assertTrue(depth >= 0, "unbalanced JSON");
            }
        }
        assertEquals(0, depth, "unbalanced JSON");
        assertFalse(inString);
    }
}
