package rlvm.main;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rlvm.cli.LauncherAccess;
import rlvm.driver.RunConfig;
import rlvm.export.ExportReader;
import rlvm.load.LoadOptions;

/**
 * What a run does when the command line says nothing about the load and the worker count (OWNER-NOTES
 * 12: the target configuration is parallel workers with the load pipelined):
 *
 * <ul>
 * <li>{@code load}: pipelined when two or more workers run and the input is at least the driver's
 *     {@code pipeline-min-mib}; batch for one worker, for a small file, for a {@code stop-after} run;
 * <li>{@code scan-threads} / {@code io}: scanner threads on mapped input for large files only;
 * <li>{@code --threads auto}: this checker's count, passed on like an explicit one;
 * <li>a setting that is given always wins, and the verdicts are those of the batch run.
 * </ul>
 */
class LoadDefaultsTest {

    @TempDir
    Path dir;

    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    /** The settings after {@code parse}, {@code applyTo} and {@code resolveLoadDefaults}, as a run does it. */
    private static MainOptions resolved(long inputBytes, int workers, String... kv) {
        MainOptions o = MainOptions.parse(map(kv), java.util.Set.of(), RlvmChecker.DRIVER);
        RunConfig c = new RunConfig();
        o.applyTo(c);
        o.resolveLoadDefaults(inputBytes, workers, c);
        assertEquals(o.loadMode, c.loadMode, "the run configuration gets the resolved mode");
        assertEquals(o.scanThreads, c.scanThreads, "and the resolved scanner threads");
        return o;
    }

    @Test
    void theLoadIsPipelinedForTwoOrMoreWorkersOnAnInputOfThePipelineMinimum() {
        long min = new RunConfig().pipelineMinBytes;
        assertEquals(8L << 20, min, "the driver's pipeline-min-mib this default was gated with");
        assertEquals(LoadOptions.Mode.PIPELINED, resolved(min, 2).loadMode);
        assertEquals(LoadOptions.Mode.PIPELINED, resolved(6L << 30, 8).loadMode);
        // One worker, a small file, an input of unknown size (not a regular file): batch, as it always was.
        assertEquals(LoadOptions.Mode.BATCH, resolved(6L << 30, 1).loadMode);
        assertEquals(LoadOptions.Mode.BATCH, resolved(min - 1, 8).loadMode);
        assertEquals(LoadOptions.Mode.BATCH, resolved(0, 8).loadMode);
        // The driver's own minimum is the border (-X pipeline-min-mib).
        assertEquals(LoadOptions.Mode.PIPELINED, resolved(1 << 20, 4, "pipeline-min-mib", "1").loadMode);
        assertEquals(LoadOptions.Mode.BATCH, resolved(100L << 20, 4, "pipeline-min-mib", "101").loadMode);
        assertEquals(LoadOptions.Mode.BATCH, resolved(0, 4, "pipeline-min-mib", "0").loadMode,
                "an input of unknown size is never pipelined by default");
        // A phase measurement is a batch run in the driver; the store is the batch store then.
        assertEquals(LoadOptions.Mode.BATCH, resolved(6L << 30, 8, "stop-after", "load").loadMode);
        // load=auto is the default spelled out.
        assertEquals(LoadOptions.Mode.PIPELINED, resolved(6L << 30, 8, "load", "auto").loadMode);
        assertFalse(resolved(6L << 30, 8, "load", "auto").loadGiven);
    }

    @Test
    void aLoadModeThatIsGivenWins() {
        assertEquals(LoadOptions.Mode.BATCH, resolved(6L << 30, 8, "load", "batch").loadMode);
        assertTrue(resolved(6L << 30, 8, "load", "batch").loadGiven);
        // An explicit pipelined stays what it was: the store is created for it whatever the input,
        // and the driver decides (one worker pipelines too; a small file is loaded in one piece).
        assertEquals(LoadOptions.Mode.PIPELINED, resolved(1000, 1, "load", "pipelined").loadMode);
        assertEquals(LoadOptions.Mode.PIPELINED, resolved(0, 8, "load", "pipelined").loadMode);
    }

    @Test
    void scannerThreadsAndMappedInputAreTheDefaultForLargeFilesOnly() {
        long min = MainOptions.SCAN_THREADS_MIN_BYTES;
        for (int workers : new int[] {1, 2, 8}) {
            MainOptions large = resolved(min, workers);
            assertEquals(MainOptions.DEFAULT_SCAN_THREADS, large.scanThreads);
            assertEquals(ExportReader.Io.AUTO, large.io, "scanner threads only run on mapped input");
            MainOptions small = resolved(min - 1, workers);
            assertEquals(0, small.scanThreads);
            assertEquals(ExportReader.Io.BUFFERED, small.io);
        }
        // Given settings win, each by itself.
        MainOptions none = resolved(min, 8, "scan-threads", "0");
        assertEquals(0, none.scanThreads);
        assertEquals(ExportReader.Io.BUFFERED, none.io);
        MainOptions three = resolved(1000, 8, "scan-threads", "3");
        assertEquals(3, three.scanThreads);
        assertEquals(ExportReader.Io.AUTO, three.io, "scanner threads that were asked for get mapped input");
        MainOptions buffered = resolved(min, 8, "io", "buffered");
        assertEquals(MainOptions.DEFAULT_SCAN_THREADS, buffered.scanThreads);
        assertEquals(ExportReader.Io.BUFFERED, buffered.io, "the reader then stays on the calling thread");
        assertEquals(ExportReader.Io.MAPPED, resolved(1000, 1, "io", "mapped").io);
        assertEquals(MainOptions.DEFAULT_SCAN_THREADS, resolved(min, 8, "scan-threads", "auto").scanThreads);
    }

    @Test
    void automaticWorkersAreOnePerProcessorUpToEight() {
        assertEquals(1, RlvmChecker.autoWorkers(1));
        assertEquals(2, RlvmChecker.autoWorkers(2));
        assertEquals(4, RlvmChecker.autoWorkers(4));
        assertEquals(8, RlvmChecker.autoWorkers(8));
        assertEquals(8, RlvmChecker.autoWorkers(20));
        assertEquals(1, RlvmChecker.autoWorkers(0));
    }

    /** {@code RlvmChecker.configure} sees the real file: its size decides. */
    @Test
    void theRunConfigurationFollowsTheSizeOfTheFile() throws Exception {
        RlvmChecker checker = new RlvmChecker();
        Path small = dir.resolve("small.ndjson");
        Files.writeString(small, "{}\n");
        Path medium = dir.resolve("medium.ndjson");
        Path large = dir.resolve("large.ndjson");
        // Sparse files: only the size matters here, nothing is read.
        try (RandomAccessFile f = new RandomAccessFile(medium.toFile(), "rw")) {
            f.setLength(8L << 20);
        }
        try (RandomAccessFile f = new RandomAccessFile(large.toFile(), "rw")) {
            f.setLength(MainOptions.SCAN_THREADS_MIN_BYTES);
        }
        RunConfig c = checker.configure(small, 8, false, 0, null);
        assertEquals(LoadOptions.Mode.BATCH, c.loadMode);
        assertEquals(0, c.scanThreads);
        c = checker.configure(medium, 8, false, 0, null);
        assertEquals(LoadOptions.Mode.PIPELINED, c.loadMode);
        assertEquals(0, c.scanThreads);
        c = checker.configure(medium, 1, false, 0, null);
        assertEquals(LoadOptions.Mode.BATCH, c.loadMode);
        c = checker.configure(large, 8, false, 0, null);
        assertEquals(LoadOptions.Mode.PIPELINED, c.loadMode);
        assertEquals(MainOptions.DEFAULT_SCAN_THREADS, c.scanThreads);
        c = checker.configure(large, 8, false, 0, map("load", "batch", "scan-threads", "0"));
        assertEquals(LoadOptions.Mode.BATCH, c.loadMode);
        assertEquals(0, c.scanThreads);
        // --threads auto: this checker's count; with two or more processors that is a pipelined run.
        c = checker.configure(medium, 0, false, 0, null);
        int auto = RlvmChecker.autoWorkers(Runtime.getRuntime().availableProcessors());
        assertEquals(auto, c.workers);
        assertEquals(auto >= 2 ? LoadOptions.Mode.PIPELINED : LoadOptions.Mode.BATCH, c.loadMode);
    }

    private static LauncherAccess.Result launch(Path export, String... options) {
        String[] args = new String[options.length + 1];
        System.arraycopy(options, 0, args, 0, options.length);
        args[options.length] = export.toString();
        return LauncherAccess.run(new HashMap<>(), args);
    }

    /**
     * Through the real launcher and the real pipeline: without {@code -X load} a run with four workers
     * on a file above the driver's minimum (set to 0 here, with a publication every 64 units, so that
     * a 100 kB file is pipelined for real) loads pipelined and gives the verdict of the batch run; one
     * worker and a file below the minimum are batch runs; a given mode wins.
     */
    @Test
    void theDefaultRunIsPipelinedAndDecidesWhatTheBatchRunDecides() throws Exception {
        Path good = dir.resolve("accepted.ndjson");
        Files.writeString(good, PassesTest.accepted());
        Path bad = dir.resolve("rejected.ndjson");
        Files.writeString(bad, PassesTest.rejected());
        String[] small = {"-X", "pipeline-min-mib=0", "-X", "publish-units=64"};

        LauncherAccess.Result r = launch(good, "--threads", "4", "--stats", small[0], small[1], small[2], small[3]);
        assertEquals(0, r.status, r.toString());
        assertTrue(r.err.contains(" workers=4 load=pipelined(auto) scan-threads=0(auto) io=buffered"), r.err);
        r = launch(bad, "--threads", "4", "--stats", small[0], small[1], small[2], small[3]);
        assertEquals(1, r.status, r.toString());
        assertTrue(r.err.contains(" load=pipelined(auto) "), r.err);

        // The pass line says whether the driver really pipelined the load.
        r = launch(good, "--threads", "4", "-X", "passes=2", small[0], small[1], small[2], small[3]);
        assertEquals(0, r.status, r.toString());
        assertTrue(r.err.contains(" pipelined=1 "), r.err);
        assertFalse(r.err.contains(" pipelined=0 "), r.err);

        // One worker: batch. A file below the driver's minimum (8 MiB): batch. Both as before.
        r = launch(good, "--threads", "1", "--stats", small[0], small[1], small[2], small[3]);
        assertEquals(0, r.status, r.toString());
        assertTrue(r.err.contains(" workers=1 load=batch(auto) "), r.err);
        r = launch(good, "--threads", "4", "--stats");
        assertEquals(0, r.status, r.toString());
        assertTrue(r.err.contains(" load=batch(auto) "), r.err);
        r = launch(bad, "--threads", "4", "--stats");
        assertEquals(1, r.status, r.toString());

        // A mode that is given wins and is printed without the mark.
        r = launch(good, "--threads", "4", "--stats", "-X", "load=batch", small[0], small[1], small[2], small[3]);
        assertEquals(0, r.status, r.toString());
        assertTrue(r.err.contains(" load=batch scan-threads=0(auto) "), r.err);
        r = launch(good, "--threads", "4", "-X", "passes=2", "-X", "load=batch", small[0], small[1], small[2], small[3]);
        assertTrue(r.err.contains(" pipelined=0 "), r.err);

        // --threads auto: the count is this checker's (capped by the driver at one worker per 128 units
        // in a batch run) and marked.
        r = launch(good, "--stats");
        assertEquals(0, r.status, r.toString());
        assertTrue(r.err.contains("(auto) load="), r.err);
    }
}
