package rlvm.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import rlvm.api.Outcome;
import rlvm.ref.RefEngine;

/**
 * Integration: real exports through the real reader, loader, store, admission and reference kernel,
 * driven by {@link Run}. The expected verdict of an arena test is its directory ({@code good} =
 * ACCEPT, {@code bad} = REJECT).
 *
 * <p>The tutorial tests (the vertical slice) always run. {@code -Drlvm.it=all} adds the other
 * published tests, the comparison of 1 / 2 / 4 / 8 workers and of session caps on every file, and
 * {@code init-prelude}; {@code -Drlvm.it.tests=DIR} points at another copy of the arena's tests.
 */
class ArenaIntegrationTest {

    private static final Path TESTS = Path.of(System.getProperty("rlvm.it.tests",
            "../work/arena-site/tests")).toAbsolutePath().normalize();
    private static final boolean ALL = "all".equals(System.getProperty("rlvm.it"));

    /** One result. */
    private static final class Result {
        final int verdict;
        final int unit;
        final int workers;
        final String explanation;

        Result(int verdict, int unit, int workers, String explanation) {
            this.verdict = verdict;
            this.unit = unit;
            this.workers = workers;
            this.explanation = explanation;
        }
    }

    private static Result run(Path file, int workers, long sessionBytes, boolean forceWorkers) {
        return run(file, workers, sessionBytes, forceWorkers, 0);
    }

    /** @param publishUnits 0: batch; N: pipelined, a publication after every N units */
    private static Result run(Path file, int workers, long sessionBytes, boolean forceWorkers, int publishUnits) {
        RunConfig c = new RunConfig();
        c.input = file;
        c.workers = workers;
        c.explain = false;
        c.logAnomalies = false;
        // The reference kernel is the primary engine here: its rejection is confirmed by a fresh cold run of
        // it, as in the launcher (verify=reference; the driver's default is no verification).
        c.recheckRejections = true;
        c.deepStackBytes = 256L << 20;
        if (sessionBytes > 0) {
            c.sessionBytes = sessionBytes;
        }
        if (publishUnits > 0) {
            c.loadMode = rlvm.load.LoadOptions.Mode.PIPELINED;
            c.pipelineMinBytes = 0;
            c.publishEveryUnits = publishUnits;
        }
        Run run = new Run(c, new TestWiring(file, publishUnits > 0), RefEngine.FACTORY, RefEngine.FACTORY);
        run.setDiagnostics(null);
        if (forceWorkers) {
            run.setMinUnitsPerWorker(1);
        }
        int verdict = run.execute();
        VerdictBoard b = run.verdicts();
        assertEquals(0, b.warmAnomalies(), "WARM-ANOMALY in " + file);
        return new Result(verdict, b.decidingUnit(), run.workerCount(), b.explanation());
    }

    private static List<Path> files(String sub) throws IOException {
        List<Path> out = new ArrayList<>();
        for (String kind : new String[] {"good", "bad"}) {
            Path dir = TESTS.resolve(kind).resolve(sub);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> s = Files.walk(dir)) {
                s.filter(p -> p.toString().endsWith(".ndjson")).forEach(out::add);
            }
        }
        Collections.sort(out);
        return out;
    }

    private static int expected(Path file) {
        return TESTS.relativize(file).getName(0).toString().equals("good") ? Outcome.ACCEPT : Outcome.REJECT;
    }

    @Test
    void tutorialTestsPassWithOneWorker() throws IOException {
        List<Path> files = files("tutorial");
        assumeTrue(!files.isEmpty(), "no arena tests under " + TESTS);
        assertEquals(141, files.size());
        StringBuilder wrong = new StringBuilder();
        int correct = 0;
        for (Path f : files) {
            Result r = run(f, 1, 0, false);
            if (r.verdict == expected(f)) {
                correct++;
            } else {
                wrong.append('\n').append(TESTS.relativize(f)).append(": expected ").append(Outcome.name(expected(f)))
                        .append(", got ").append(r.explanation);
            }
        }
        assertEquals("", wrong.toString(), correct + " / " + files.size() + " correct");
    }

    /**
     * Pipelined loading with the real loader (level snapshots of {@code rlvm.load.LevelChain}, worker
     * contexts that follow them): a publication after every unit and after every third unit, one and
     * four workers; the verdict and the deciding unit are those of the batch run.
     */
    @Test
    void tutorialTestsGiveTheSameVerdictWhenTheFileIsCheckedWhileItLoads() throws IOException {
        List<Path> files = files("tutorial");
        assumeTrue(!files.isEmpty(), "no arena tests under " + TESTS);
        StringBuilder wrong = new StringBuilder();
        int failClosed = 0;
        for (Path f : files) {
            Result base = run(f, 1, 0, false);
            int[][] variants = {{1, 1}, {4, 1}, {1, 3}, {4, 3}};
            for (int[] v : variants) {
                Result r = run(f, v[0], 0, true, v[1]);
                if (r.verdict == Outcome.DECLINE && base.verdict != Outcome.DECLINE) {
                    // Less complete, never another verdict (none expected on the published tests).
                    failClosed++;
                    continue;
                }
                if (r.verdict != base.verdict || r.unit != base.unit) {
                    wrong.append('\n').append(TESTS.relativize(f)).append(": batch ").append(Outcome.name(base.verdict))
                            .append('@').append(base.unit).append(", pipelined (").append(v[0]).append(" workers, every ")
                            .append(v[1]).append(" units) ").append(Outcome.name(r.verdict)).append('@').append(r.unit)
                            .append(": ").append(r.explanation);
                }
            }
        }
        assertEquals("", wrong.toString());
        assertEquals(0, failClosed, "pipelined runs that declined what the batch run decides");
    }

    /**
     * Two shapes the published tests do not contain, from the test kit's mutants of
     * {@code tutorial/001_basicDef} (both accepted by the official checker): the only declaration
     * dropped, and the only declaration turned unsafe (the loader drops it; the store keeps an empty
     * unit, so the store's unit count must be compared with the reader's count of declaration records).
     */
    @Test
    void anExportWithoutDeclarationsAndOneWithADroppedRecordAreAccepted() {
        Path mutants = TESTS.resolve("../../mutants/suite/good/tutorial/001_basicDef").normalize();
        Path empty = mutants.resolve("001_basicDef.drop_decl.s1.0000.ndjson");
        Path dropped = mutants.resolve("001_basicDef.forge_flag.s1.0000.ndjson");
        assumeTrue(Files.isRegularFile(empty) && Files.isRegularFile(dropped), "no mutants under " + mutants);
        for (int w : new int[] {1, 4}) {
            Result e = run(empty, w, 0, true);
            assertEquals(Outcome.ACCEPT, e.verdict, String.valueOf(e.explanation));
            Result d = run(dropped, w, 0, true);
            assertEquals(Outcome.ACCEPT, d.verdict, String.valueOf(d.explanation));
        }
    }

    @Test
    void tutorialTestsGiveTheSameVerdictAndFailingUnitForAnyWorkerCountAndSessionCap() throws IOException {
        List<Path> files = files("tutorial");
        assumeTrue(!files.isEmpty(), "no arena tests under " + TESTS);
        compare(files);
    }

    @Test
    void allPublishedTestsNoWrongVerdictAndInvariant() throws IOException {
        assumeTrue(ALL, "run with -Drlvm.it=all");
        List<Path> files = files("");
        assertEquals(230, files.size());
        StringBuilder wrong = new StringBuilder();
        StringBuilder declined = new StringBuilder();
        int correct = 0;
        int declines = 0;
        for (Path f : files) {
            Result r = run(f, 1, 0, false);
            if (r.verdict == expected(f)) {
                correct++;
            } else if (r.verdict == Outcome.DECLINE) {
                declines++;
                declined.append('\n').append(TESTS.relativize(f)).append(": ").append(r.explanation);
            } else {
                wrong.append('\n').append(TESTS.relativize(f)).append(": expected ").append(Outcome.name(expected(f)))
                        .append(", got ").append(r.explanation);
            }
        }
        System.out.println("ArenaIntegrationTest: " + correct + " / " + files.size() + " correct, " + declines
                + " declined" + declined);
        assertEquals("", wrong.toString(), "wrong verdicts");
        compare(files);
    }

    @Test
    void initPreludeIsAcceptedWithOneAndWithFourWorkers() {
        assumeTrue(ALL, "run with -Drlvm.it=all");
        Path f = TESTS.resolve("good/init-prelude.ndjson");
        assumeTrue(Files.isRegularFile(f));
        Result one = run(f, 1, 0, false);
        assertEquals(Outcome.ACCEPT, one.verdict, String.valueOf(one.explanation));
        Result four = run(f, 4, 0, false);
        assertEquals(Outcome.ACCEPT, four.verdict, String.valueOf(four.explanation));
        assertEquals(4, four.workers);
    }

    /** Same verdict and same deciding unit for 1, 2, 4, 8 workers and for a tiny session cap. */
    private static void compare(List<Path> files) {
        StringBuilder diff = new StringBuilder();
        int multi = 0;
        for (Path f : files) {
            Result base = run(f, 1, 0, false);
            int[] workers = {2, 4, 8, 1, 4};
            long[] caps = {0, 0, 0, 1, 1};
            for (int i = 0; i < workers.length; i++) {
                Result r = run(f, workers[i], caps[i], true);
                multi += r.workers > 1 ? 1 : 0;
                if (r.verdict != base.verdict || r.unit != base.unit) {
                    diff.append('\n').append(TESTS.relativize(f)).append(": 1 worker ").append(Outcome.name(base.verdict))
                            .append('@').append(base.unit).append(", ").append(workers[i]).append(" workers")
                            .append(caps[i] > 0 ? " (session cap " + caps[i] + " B)" : "").append(' ')
                            .append(Outcome.name(r.verdict)).append('@').append(r.unit).append(": ").append(r.explanation);
                }
            }
        }
        assertEquals("", diff.toString(), "verdict depends on the schedule");
        assertTrue(multi > files.size(), "the comparison really used several workers (" + multi + " runs)");
    }
}
