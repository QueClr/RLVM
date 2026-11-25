package rlvm.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Random;

import org.junit.jupiter.api.Test;

import rlvm.admit.Policy;
import rlvm.api.Outcome;
import rlvm.driver.Fakes.Expect;
import rlvm.driver.Fakes.FakeHost;
import rlvm.driver.Fakes.Unit;

/**
 * The invariance suite of *agentic-note*: design-architecture-document, 9.1 / 9.2 for the driver: on randomized fake workloads with
 * injected failures the verdict and the unit that decides it are the same for 1, 2, 4 and 8 workers,
 * for every chunk size, environment batch size and session cap, and equal to what the verdict rule
 * says independently of any schedule.
 */
class InvarianceTest {

    private static final int A = Fakes.A;
    private static final int R = Fakes.R;
    private static final int D = Fakes.D;
    private static final int T = Fakes.T;

    private static final int[] WORKERS = {1, 2, 4, 8};

    /** A random workload. {@code failures}: how many units get a non-trivial script. */
    static Unit[] randomScript(Random rnd, int n, int failures, boolean same) {
        Unit[] s = Fakes.units(n);
        for (Unit u : s) {
            int k = rnd.nextInt(100);
            u.cls = k < 86 ? RunHost.CHECK : k < 96 ? RunHost.ENV : RunHost.SKIP;
            u.footprint = 1 + rnd.nextInt(4000);
            if (rnd.nextInt(40) == 0) {
                u.delayMicros = rnd.nextInt(300);
            }
        }
        int firstReal = Integer.MAX_VALUE;
        for (int f = 0; f < failures; f++) {
            int i = rnd.nextInt(n);
            Unit u = s[i];
            u.delayMicros = rnd.nextInt(4) == 0 ? rnd.nextInt(500) : 0;
            if (u.cls == RunHost.SKIP) {
                u.cls = RunHost.UNKNOWN;
            } else if (u.cls == RunHost.ENV) {
                u.env = rnd.nextBoolean() ? R : D;
            } else {
                u.warm = A; // a unit may be scripted twice: start from a clean script
                u.cold = A;
                u.strict = A;
                u.ref = A;
                switch (rnd.nextInt(12)) {
                    case 0: // a true rejection
                        u.warm = R;
                        u.strict = R;
                        u.ref = R;
                        break;
                    case 1: // wrong rejection out of a warm cache
                        u.warm = R;
                        u.strict = A;
                        u.ref = A;
                        break;
                    case 2: // the machine cannot prove a true equation
                        u.warm = R;
                        u.strict = R;
                        u.ref = A;
                        break;
                    case 3: // rejection the reference kernel cannot confirm
                        u.warm = R;
                        u.strict = rnd.nextBoolean() ? R : D;
                        u.ref = rnd.nextBoolean() ? D : T;
                        break;
                    case 4: // heavy unit: declined warm, accepted cold
                        u.warm = D;
                        u.cold = A;
                        break;
                    case 5: // declined by the machine, decided by the reference kernel
                        u.warm = D;
                        u.cold = D;
                        u.ref = rnd.nextInt(3);
                        break;
                    case 6: // declined warm, rejected cold
                        u.warm = D;
                        u.cold = R;
                        u.strict = R;
                        u.ref = rnd.nextBoolean() ? R : A;
                        break;
                    case 7: // exception in the engine
                        u.warm = T;
                        u.ref = rnd.nextInt(4);
                        break;
                    case 8: // session-state dependent rejection (accepted when fresh)
                        u.warm = Fakes.WARMTH;
                        break;
                    case 9: // exception everywhere
                        u.warm = T;
                        u.cold = T;
                        u.strict = T;
                        u.ref = T;
                        break;
                    case 10: // invalid outcome code
                        u.warm = Fakes.BAD;
                        u.cold = rnd.nextBoolean() ? A : Fakes.BAD;
                        u.ref = rnd.nextBoolean() ? A : D;
                        break;
                    default: // plain decline everywhere
                        u.warm = D;
                        u.cold = D;
                        u.ref = D;
                        break;
                }
            }
        }
        // Declines for memory are retried while the worker holds the heavy gate; the status is the same.
        for (Unit u : s) {
            if (rnd.nextInt(3) == 0) {
                u.declineReason = rlvm.api.Reason.BUDGET_MEMORY;
            }
            // Pipelined runs only: the unit cannot be decided before the file is completely loaded.
            u.late = rnd.nextInt(failures == 0 ? 60 : 12) == 0;
        }
        // Pipelined runs only, one workload in four: units that are out of file order in a way the environment
        // pass does not see while the file loads (the parking rule keeps the frontier below them until the end).
        if (rnd.nextInt(4) == 0) {
            for (Unit u : s) {
                if (u.cls == RunHost.CHECK && rnd.nextInt(40) == 0) {
                    u.outOfOrder = true;
                }
            }
        }
        // (a unit may have been scripted twice; only the final scripts count)
        for (int i = n - 1; i >= 0; i--) {
            if (Fakes.finalOf(s[i], same, true, Policy.Unconfirmed.DECLINE) != Outcome.ACCEPT) {
                firstReal = i;
            }
        }
        // units that never terminate may only exist behind a real failure (an ill-typed earlier unit)
        if (firstReal < n - 1 && failures > 0) {
            int loops = rnd.nextInt(4);
            for (int k = 0; k < loops; k++) {
                int i = firstReal + 1 + rnd.nextInt(n - firstReal - 1);
                Unit u = s[i];
                if (u.cls == RunHost.ENV) {
                    u.env = Fakes.LOOP;
                } else if (u.cls == RunHost.CHECK) {
                    int which = rnd.nextInt(3);
                    u.warm = which == 0 ? Fakes.LOOP : rnd.nextBoolean() ? R : D;
                    u.cold = Fakes.LOOP;
                    u.strict = Fakes.LOOP;
                    u.ref = Fakes.LOOP;
                }
            }
        }
        return s;
    }

    @Test
    void sameVerdictForAnyNumberOfWorkers() {
        assertTimeoutPreemptively(Duration.ofSeconds(3000), () -> {
            // -Drlvm.driver.invariance=N: number of random workloads (soak runs); -Drlvm.driver.seed=S
            final int iterations = Integer.getInteger("rlvm.driver.invariance", 400);
            Random rnd = new Random(Long.getLong("rlvm.driver.seed", 20261124L));
            int runs = 0;
            int[] verdicts = new int[3];
            for (int iter = 0; iter < iterations; iter++) {
                boolean same = rnd.nextBoolean();
                int n = 1 + rnd.nextInt(iter % 10 == 0 ? 1500 : 220);
                int failures = iter % 3 == 0 ? 0 : 1 + rnd.nextInt(6);
                Unit[] script = randomScript(rnd, n, failures, same);
                Policy.Unconfirmed policy = rnd.nextInt(5) == 0 ? Policy.Unconfirmed.REJECT : Policy.Unconfirmed.DECLINE;
                Expect expect = Fakes.expect(script, same, true, policy, true);
                verdicts[expect.verdict]++;
                for (int w : WORKERS) {
                    RunConfig c = Fakes.config(w);
                    c.policy = new Policy(true, Policy.ForwardRefs.DECLINE, policy, true, false);
                    c.chunkUnits = 1 + rnd.nextInt(rnd.nextBoolean() ? 4 : 40);
                    c.envBatchUnits = 1 + rnd.nextInt(rnd.nextBoolean() ? 8 : 400);
                    c.sessionBytes = rnd.nextBoolean() ? 1 + rnd.nextInt(3000) : 16L << 20;
                    c.spillNanos = rnd.nextInt(3) == 0 ? 1 : 20_000_000L;
                    FakeHost host = new FakeHost(Fakes.copy(script));
                    Run run = host.run(c, same);
                    int verdict = run.execute();
                    runs++;
                    String where = "iteration " + iter + ", " + w + " workers, " + n + " units, same=" + same + ", chunk "
                            + c.chunkUnits + ", envBatch " + c.envBatchUnits + ", expected " + expect;
                    assertEquals("", host.describeViolations(), where);
                    assertEquals(expect.verdict, verdict, where + ": " + run.verdicts().explanation());
                    assertEquals(expect.unit, run.verdicts().decidingUnit(), where);
                    assertEquals(Math.min(w, n), run.workerCount(), where);
                    check(host, run, script, expect, where);
                }
            }
            assertEquals(iterations * 4, runs);
            assertTrue(iterations < 400 || (verdicts[0] > 60 && verdicts[1] > 20 && verdicts[2] > 20),
                    "the workloads cover all verdicts: " + java.util.Arrays.toString(verdicts));
        });
    }

    /** What must hold after any run, whatever the schedule was. */
    static void check(FakeHost host, Run run, Unit[] script, Expect expect, String where) {
        check(host, run, script, expect, where, false);
    }

    /**
     * @param pipelined the file was published in pieces: a unit marked {@code late} may have been
     *     tried once while the file was loading and once more afterwards
     */
    static void check(FakeHost host, Run run, Unit[] script, Expect expect, String where, boolean pipelined) {
        VerdictBoard b = run.verdicts();
        int through = expect.unit < 0 ? script.length : expect.unit;
        for (int u = 0; u < script.length; u++) {
            int st = b.status(u);
            // A decline obtained while the file was loading is not recorded; the unit is tried again.
            boolean twice = pipelined && (script[u].late || u >= through);
            if (u < through) {
                // everything below the first failure ran to completion and was accepted
                assertTrue(st == VerdictBoard.ACCEPTED || st == VerdictBoard.SKIPPED, "unit " + u + " is "
                        + VerdictBoard.statusName(st) + ": " + where);
                if (script[u].cls == RunHost.CHECK) {
                    int warm = host.warmChecks.get(u);
                    assertTrue(twice ? warm == 1 || warm == 2 : warm == 1, "warm checks of unit " + u + ": " + warm + ": " + where);
                }
            }
            // no unit is ever checked warm twice, and nothing a worker should not see is admitted
            assertTrue(host.warmChecks.get(u) <= (twice ? 2 : 1), "unit " + u + " checked warm " + host.warmChecks.get(u) + " times: " + where);
            if (script[u].cls == RunHost.CHECK && st == VerdictBoard.ACCEPTED && script[u].warm == Fakes.A && !twice) {
                assertEquals(0, host.refChecks.get(u) + host.strictChecks.get(u) + host.coldChecks.get(u),
                        "an ACCEPT of the primary engine is never re-checked: unit " + u + ": " + where);
            }
        }
        if (expect.unit >= 0) {
            assertEquals(expect.unit, b.firstFailure(), where);
            assertEquals(expect.verdict == Outcome.REJECT ? VerdictBoard.REJECTED : VerdictBoard.DECLINED,
                    b.status(expect.unit), where);
        } else {
            assertEquals(Integer.MAX_VALUE, b.firstFailure(), where);
            assertEquals(script.length, b.acceptedCount(), where);
        }
        assertEquals(0, host.sessionsOpen.get(), "sessions left open: " + where);
        assertEquals(0, host.lanesOpen.get(), "lanes left open: " + where);
        assertEquals(0, host.enginesOpen.get(), "engines left open: " + where);
    }

    /**
     * Pipelined loading (*agentic-note*: design-architecture-document, 3.5): the file appears in pieces while the environment thread
     * and the workers check; some units cannot be decided before the end of the file. The verdict
     * and the deciding unit are those of the batch run, i.e. of the verdict rule.
     */
    @Test
    void sameVerdictWhateverThePipeline() {
        assertTimeoutPreemptively(Duration.ofSeconds(3000), () -> {
            final int iterations = Integer.getInteger("rlvm.driver.invariance", 400);
            Random rnd = new Random(Long.getLong("rlvm.driver.seed", 20261124L) + 1);
            int[] verdicts = new int[3];
            long deferred = 0;
            long retried = 0;
            long snapshotResets = 0;
            long pipelinedRuns = 0;
            long downgraded = 0;
            for (int iter = 0; iter < iterations; iter++) {
                boolean same = rnd.nextBoolean();
                int n = 1 + rnd.nextInt(iter % 10 == 0 ? 1500 : 220);
                int failures = iter % 3 == 0 ? 0 : 1 + rnd.nextInt(6);
                Unit[] script = randomScript(rnd, n, failures, same);
                Policy.Unconfirmed policy = rnd.nextInt(5) == 0 ? Policy.Unconfirmed.REJECT : Policy.Unconfirmed.DECLINE;
                Expect expect = Fakes.expect(script, same, true, policy, true);
                verdicts[expect.verdict]++;
                for (int w : WORKERS) {
                    RunConfig c = Fakes.config(w);
                    c.policy = new Policy(true, Policy.ForwardRefs.DECLINE, policy, true, false);
                    c.chunkUnits = 1 + rnd.nextInt(rnd.nextBoolean() ? 4 : 40);
                    c.envBatchUnits = 1 + rnd.nextInt(rnd.nextBoolean() ? 8 : 400);
                    c.sessionBytes = rnd.nextBoolean() ? 1 + rnd.nextInt(3000) : 16L << 20;
                    c.spillNanos = rnd.nextInt(3) == 0 ? 1 : 20_000_000L;
                    // 1 to 6 publications; the last one is the end of the file
                    int pieces = 1 + rnd.nextInt(6);
                    int[] publications = new int[pieces];
                    for (int i = 0; i < pieces - 1; i++) {
                        publications[i] = rnd.nextInt(n + 1);
                    }
                    publications[pieces - 1] = n;
                    java.util.Arrays.sort(publications);
                    int pause = rnd.nextInt(3) == 0 ? 0 : rnd.nextInt(400);
                    FakeHost host = new FakeHost(Fakes.copy(script));
                    host.envSilentWait = rnd.nextBoolean();
                    host.envCollateral = rnd.nextInt(4) == 0;
                    Run run = host.runPipelined(c, same, publications, pause, null);
                    int verdict = run.execute();
                    String where = "iteration " + iter + ", " + w + " workers, " + n + " units, same=" + same + ", chunk "
                            + c.chunkUnits + ", envBatch " + c.envBatchUnits + ", publications "
                            + java.util.Arrays.toString(publications) + ", pause " + pause + ", expected " + expect;
                    assertEquals("", host.describeViolations(), where);
                    Expect seen = expect;
                    if (run.rejectionsTainted() && expect.verdict == Outcome.REJECT && verdict == Outcome.DECLINE) {
                        // The failed attempt of the pass on the incomplete file had recorded failures of
                        // other units: from then on the driver records no rejection; a unit that would be
                        // rejected after that is declined (one that was rejected before it stays rejected).
                        seen = new Expect(Outcome.DECLINE, expect.unit);
                        downgraded++;
                    }
                    assertTrue(!run.rejectionsTainted() || host.envCollateral, "only collateral records taint: " + where);
                    assertEquals(seen.verdict, verdict, where + ": " + run.verdicts().explanation());
                    assertEquals(seen.unit, run.verdicts().decidingUnit(), where);
                    assertEquals(0, host.passedOutOfOrder.get(), "the frontier never passed an out-of-order unit while loading: " + where);
                    check(host, run, script, seen, where, true);
                    retried += host.envRetries.get();
                    if (run.workers() != null) {
                        for (Worker worker : run.workers()) {
                            deferred += worker.deferredUnits();
                            snapshotResets += worker.snapshotResets();
                        }
                    }
                    pipelinedRuns += run.pipelined() ? 1 : 0;
                }
            }
            assertTrue(iterations < 400 || (verdicts[0] > 60 && verdicts[1] > 20 && verdicts[2] > 20),
                    "the workloads cover all verdicts: " + java.util.Arrays.toString(verdicts));
            // (slice mode: a worker takes its units when it needs them, so fewer of them lie beyond its lane's snapshot: 170 - 180
            // snapshot resets where one run per worker and batch gave 520)
            assertTrue(iterations < 400 || (deferred > 50 && retried > 20 && snapshotResets > 100 && pipelinedRuns > 800),
                    "the pipeline's paths are covered: " + pipelinedRuns + " pipelined runs, " + deferred
                            + " deferred units, " + retried + " environment retries, " + snapshotResets + " snapshot resets");
            System.out.println("InvarianceTest: pipelined: " + pipelinedRuns + " pipelined runs, " + downgraded
                    + " rejections declined after a failure on the incomplete file, " + deferred + " deferred units, " + retried
                    + " environment retries, " + snapshotResets + " snapshot resets, verdicts "
                    + java.util.Arrays.toString(verdicts));
        });
    }

    @Test
    void aLargeAcceptingWorkloadUsesEveryWorkerAndStaysInFileOrderPerWorker() {
        Unit[] script = Fakes.units(40_000);
        Random rnd = new Random(5);
        for (Unit u : script) {
            u.cls = rnd.nextInt(20) == 0 ? RunHost.ENV : RunHost.CHECK;
            u.footprint = 600;
        }
        for (int w : WORKERS) {
            FakeHost host = new FakeHost(Fakes.copy(script));
            RunConfig c = Fakes.config(w);
            Run run = host.run(c, true);
            assertEquals(Outcome.ACCEPT, run.execute());
            assertEquals("", host.describeViolations());
            long done = 0;
            for (Worker worker : run.workers()) {
                done += worker.unitsDone();
                assertEquals(0, worker.rechecks());
            }
            // CHECK units are accepted by workers; ENV units by the environment pass
            int checks = 0;
            for (Unit u : script) {
                checks += u.cls == RunHost.CHECK ? 1 : 0;
            }
            assertEquals(checks, done);
            assertEquals(script.length, run.verdicts().acceptedCount());
            assertEquals(w, run.workers().length);
            // one environment session, one warm session per worker that got a claim; no re-check lanes
            assertTrue(host.sessionsCreated.get() >= 2 && host.sessionsCreated.get() <= w + 1, "sessions: "
                    + host.sessionsCreated.get());
            assertEquals(w == 1 ? 2 : 3, host.enginesCreated.get() + (w == 1 ? 0 : 1), "primary + reference engine");
        }
    }

    @Test
    void theDefaultWorkerCountShrinksForSmallInputs() {
        Unit[] script = Fakes.units(300);
        FakeHost host = new FakeHost(script);
        RunConfig c = Fakes.config(8);
        Run run = host.run(c, true);
        run.setMinUnitsPerWorker(128);
        assertEquals(Outcome.ACCEPT, run.execute());
        assertEquals(2, run.workerCount(), "300 units / 128 per worker");

        FakeHost tiny = new FakeHost(Fakes.units(5));
        Run run2 = tiny.run(Fakes.config(8), true);
        run2.setMinUnitsPerWorker(128);
        assertEquals(Outcome.ACCEPT, run2.execute());
        assertEquals(1, run2.workerCount());
        assertTrue(Run.resolveWorkers(0) >= 1 && Run.resolveWorkers(0) <= 6);
        assertEquals(3, Run.resolveWorkers(3));
    }
}
