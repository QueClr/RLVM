package rlvm.main;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rlvm.api.Budget;
import rlvm.api.Engine;
import rlvm.api.EngineConfig;
import rlvm.api.EngineFactory;
import rlvm.api.EngineProvider;
import rlvm.api.KernelOps;
import rlvm.api.Outcome;
import rlvm.api.Reason;
import rlvm.api.Session;
import rlvm.api.StatsSink;
import rlvm.api.WorkerContext;
import rlvm.cli.LauncherAccess;
import rlvm.store.Store;

/**
 * {@code -X passes=N} / {@code -X passes-mode=reload|recheck} ({@link Passes}) through the real
 * launcher and the real pipeline: one pass is the plain run; N passes give N lines and the verdict of
 * the first, for an accepted, a rejected and a declined export, in both modes (reload: this module's
 * complete runs; recheck: the driver's {@code RunConfig.passes}), loaded in batch and pipelined mode,
 * with 1 and 4 workers; the statistics; a later pass with another verdict.
 */
class PassesTest {

    private static final String META = "{\"meta\":{\"exporter\":{\"name\":\"lean4export\",\"version\":\"3.1.0\"},"
            + "\"format\":{\"version\":\"3.1.0\"},\"lean\":{\"githash\":\"611c117bfaf7b7ae1cfcf246ce6fba31e8f509e9\","
            + "\"version\":\"4.36.0\"}}}\n";

    /** Unit {@code i}: {@code def dI : Sort 2 := Sort 1} (well-typed) or {@code def dI : Sort 0 := Sort 1} (ill-typed). */
    private static String def(int i, boolean good) {
        int name = i + 1;
        int l1 = 2 * i + 1;
        int l2 = 2 * i + 2;
        int e = 2 * i;
        return "{\"in\":" + name + ",\"str\":{\"pre\":0,\"str\":\"d" + i + "\"}}\n"
                + "{\"il\":" + l1 + ",\"succ\":0}\n"
                + "{\"il\":" + l2 + ",\"succ\":" + l1 + "}\n"
                + "{\"ie\":" + e + ",\"sort\":" + (good ? l2 : 0) + "}\n"
                + "{\"ie\":" + (e + 1) + ",\"sort\":" + l1 + "}\n"
                + "{\"def\":{\"all\":[" + name + "],\"hints\":\"opaque\",\"levelParams\":[],\"name\":" + name
                + ",\"safety\":\"safe\",\"type\":" + e + ",\"value\":" + (e + 1) + "}}\n";
    }

    /** An inductive type whose constructor type is wrapped in metadata: the loader declines it (LoadDeclineTest). */
    private static String inductiveWithMetadata(int nameBase, int exprBase) {
        int t = nameBase;
        int mk = nameBase + 1;
        return "{\"in\":" + t + ",\"str\":{\"pre\":0,\"str\":\"T\"}}\n"
                + "{\"in\":" + mk + ",\"str\":{\"pre\":" + t + ",\"str\":\"mk\"}}\n"
                + "{\"ie\":" + exprBase + ",\"sort\":0}\n"
                + "{\"const\":{\"name\":" + t + ",\"us\":[]},\"ie\":" + (exprBase + 1) + "}\n"
                + "{\"ie\":" + (exprBase + 2) + ",\"mdata\":{\"data\":{},\"expr\":" + (exprBase + 1) + "}}\n"
                + "{\"inductive\":{\"ctors\":[{\"cidx\":0,\"induct\":" + t + ",\"isUnsafe\":false,\"levelParams\":[],\"name\":"
                + mk + ",\"numFields\":0,\"numParams\":0,\"type\":" + (exprBase + 2) + "}],\"recs\":[],\"types\":[{\"all\":["
                + t + "],\"ctors\":[" + mk + "],\"isRec\":false,\"isReflexive\":false,\"isUnsafe\":false,\"levelParams\":[],"
                + "\"name\":" + t + ",\"numIndices\":0,\"numNested\":0,\"numParams\":0,\"type\":" + exprBase + "}]}}\n";
    }

    /** Units of the generated exports: enough for four workers in a batch run (128 units per worker). */
    private static final int UNITS = 640;

    private static String accepted() {
        StringBuilder sb = new StringBuilder(META);
        for (int i = 0; i < UNITS; i++) {
            sb.append(def(i, true));
        }
        return sb.toString();
    }

    /** As {@link #accepted()} with one ill-typed definition in the middle. */
    private static String rejected() {
        StringBuilder sb = new StringBuilder(META);
        for (int i = 0; i < UNITS; i++) {
            sb.append(def(i, i != UNITS / 2));
        }
        return sb.toString();
    }

    /** As {@link #accepted()} with a record the loader cannot represent at the end: never accepted. */
    private static String declined() {
        return accepted() + inductiveWithMetadata(UNITS + 1, 2 * UNITS);
    }

    @TempDir
    Path dir;

    private Path file(String name, String content) throws Exception {
        Path f = dir.resolve(name + ".ndjson");
        if (!Files.exists(f)) {
            Files.writeString(f, content);
        }
        return f;
    }

    private static LauncherAccess.Result launch(Path export, String... options) {
        String[] args = new String[options.length + 1];
        System.arraycopy(options, 0, args, 0, options.length);
        args[options.length] = export.toString();
        return LauncherAccess.run(new HashMap<>(), args);
    }

    private static String[] concat(String[] a, String... b) {
        String[] out = new String[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static final String[] BATCH = {"-X", "load=batch"};
    /** Pipelined for real on a small file: no minimum size, a publication every 64 units. */
    private static final String[] PIPELINED = {"-X", "load=pipelined", "-X", "pipeline-min-mib=0", "-X", "publish-units=64"};

    private static final Pattern LINE = Pattern.compile("^rlvm: pass (\\d+)/(\\d+) mode=(reload|recheck) wall_ms=(\\d+)"
            + " cpu_ms=(-?\\d+) gc_ms=(-?\\d+) gc_count=(-?\\d+) load_ms=(\\d+) check_ms=(\\d+) units=(\\d+)"
            + " accepted=(-?\\d+) workers=(\\d+) pipelined=([01]) heap_kb=(-?\\d+) rss_kb=(-?\\d+) peak_rss_kb=(-?\\d+)"
            + " settle_ms=(\\d+) verdict=(ACCEPT|REJECT|DECLINE)$");
    /** The driver's own line of a pass (recheck mode), which stands beside this module's. */
    private static final Pattern DRIVER_LINE = Pattern.compile("^rlvm: pass \\d+ of \\d+: .*$");

    private static List<Matcher> passLines(String err) {
        List<Matcher> out = new ArrayList<>();
        for (String line : err.split("\n")) {
            if (line.startsWith("rlvm: pass ") && !DRIVER_LINE.matcher(line).matches()) {
                Matcher m = LINE.matcher(line);
                assertTrue(m.matches(), "a pass line of another shape: " + line);
                out.add(m);
            }
        }
        return out;
    }

    /** Numbers that differ between two runs of the same thing. */
    private static String masked(String err) {
        return err.replaceAll("(wall_ms|cpu_user_ms|cpu_sys_ms|peak_rss_kb|heap_used_kb|heap_committed_kb|os_threads)=-?\\d+",
                "$1=N");
    }

    // ---- one pass is the plain run ----------------------------------------------------------------------------

    @Test
    void onePassIsThePlainRun() throws Exception {
        Path[] files = {file("accepted", accepted()), file("rejected", rejected()), file("declined", declined())};
        int[] status = {0, 1, 2};
        for (int f = 0; f < files.length; f++) {
            for (String[] load : new String[][] {BATCH, PIPELINED}) {
                for (String workers : new String[] {"1", "4"}) {
                    String[] base = concat(load, "--threads", workers, "--stats");
                    LauncherAccess.Result plain = launch(files[f], base);
                    LauncherAccess.Result one = launch(files[f], concat(base, "-X", "passes=1"));
                    LauncherAccess.Result oneRecheck = launch(files[f], concat(base, "-X", "passes=1", "-X", "passes-mode=recheck"));
                    String what = files[f].getFileName() + " " + String.join(" ", base);
                    assertEquals(status[f], plain.status, what + ": " + plain);
                    assertEquals(plain.status, one.status, what);
                    assertEquals(plain.status, oneRecheck.status, what);
                    assertEquals(plain.out, one.out, what);
                    assertEquals(masked(plain.err), masked(one.err), what);
                    assertEquals(masked(plain.err), masked(oneRecheck.err), what);
                    assertFalse(one.err.contains("rlvm: pass "), one.err);
                    assertFalse(one.err.contains("passes="), one.err);
                }
            }
        }
    }

    // ---- N passes ---------------------------------------------------------------------------------------------

    @Test
    void threePassesGiveThreeLinesAndTheVerdictOfTheFirst() throws Exception {
        Path[] files = {file("accepted", accepted()), file("rejected", rejected()), file("declined", declined())};
        int[] status = {0, 1, 2};
        String[] verdict = {"ACCEPT", "REJECT", "DECLINE"};
        for (int f = 0; f < files.length; f++) {
            for (String mode : new String[] {"reload", "recheck"}) {
                for (String[] load : new String[][] {BATCH, PIPELINED}) {
                    for (int workers : new int[] {1, 4}) {
                        String[] args = concat(load, "--threads", Integer.toString(workers), "-X", "passes=3", "-X",
                                "passes-mode=" + mode);
                        String what = files[f].getFileName() + " " + String.join(" ", args);
                        LauncherAccess.Result r = launch(files[f], args);
                        assertEquals(status[f], r.status, what + ": " + r);
                        List<Matcher> lines = passLines(r.err);
                        assertEquals(3, lines.size(), what + ": " + r.err);
                        boolean keeps = mode.equals("recheck") && f == 0;
                        for (int k = 0; k < 3; k++) {
                            Matcher m = lines.get(k);
                            assertEquals(Integer.toString(k + 1), m.group(1), what);
                            assertEquals("3", m.group(2), what);
                            assertEquals(verdict[f], m.group(18), what + ", pass " + (k + 1) + ": " + r.err);
                            assertEquals(keeps && k > 0 ? "recheck" : "reload", m.group(3), what + ", pass " + (k + 1));
                            assertEquals(Integer.toString(workers), m.group(12), what + ": workers of pass " + (k + 1));
                            assertEquals(Integer.toString(UNITS), m.group(10), what + ": units");
                            if (f == 0 && !(keeps && k > 0)) {
                                assertEquals(m.group(10), m.group(11), what + ": every unit accepted in pass " + (k + 1));
                            }
                            if (keeps && k > 0) {
                                assertEquals("0", m.group(8), what + ": a recheck pass loads nothing");
                                assertEquals("0", m.group(13), what + ": a recheck pass is a batch run");
                            }
                            if (load == PIPELINED && !(keeps && k > 0)) {
                                assertEquals("1", m.group(13), what + ": pass " + (k + 1) + " is pipelined");
                            }
                        }
                        assertEquals(mode.equals("recheck") && f != 0, r.err.contains("the later passes reload"), what + ": " + r.err);
                        // The driver's own lines stand beside ours exactly when it made the passes.
                        assertEquals(keeps ? 3 : 0, r.err.lines().filter(l -> DRIVER_LINE.matcher(l).matches()
                                && !l.contains("no further pass")).count(), what + ": " + r.err);
                        assertFalse(r.err.contains("PASSES DISAGREE"), r.err);
                        if (f == 1) {
                            // The explanation of a rejection is printed by every pass, as by a plain run.
                            assertEquals(3, r.err.lines().filter(l -> l.startsWith("rlvm: reject: ") && l.contains("'d"
                                    + (UNITS / 2) + "'")).count(), what + ": " + r.err);
                        }
                    }
                }
            }
        }
    }

    @Test
    void quietPrintsNoPassLinesAndTheStatsLineNamesThePasses() throws Exception {
        Path f = file("accepted", accepted());
        LauncherAccess.Result quiet = launch(f, "--threads", "1", "-q", "-X", "passes=2");
        assertEquals(0, quiet.status, quiet.toString());
        assertEquals("", quiet.err);
        LauncherAccess.Result stats = launch(f, "--threads", "1", "--stats", "-X", "passes=2", "-X", "passes-mode=recheck");
        assertEquals(0, stats.status, stats.toString());
        assertEquals(2, passLines(stats.err).size());
        assertTrue(stats.err.contains("rlvm: stats: outcome=accept engine=reference"), stats.err);
        assertTrue(stats.err.contains(" passes=2/2 passes-mode=recheck"), stats.err);
    }

    @Test
    void aMalformedExportIsRejectedInEveryPass() throws Exception {
        // Line 1 is the header whatever it says (as for the official reader); line 2 is not JSON.
        Path f = file("malformed", META + "this is not json\n");
        for (String mode : new String[] {"reload", "recheck"}) {
            LauncherAccess.Result plain = launch(f, "--threads", "1");
            assertEquals(1, plain.status, plain.toString());
            LauncherAccess.Result r = launch(f, "--threads", "1", "-X", "passes=2", "-X", "passes-mode=" + mode);
            assertEquals(1, r.status, r.toString());
            List<Matcher> lines = passLines(r.err);
            assertEquals(2, lines.size(), r.err);
            assertEquals("REJECT", lines.get(0).group(18));
            assertEquals("REJECT", lines.get(1).group(18));
            assertEquals("reload", lines.get(1).group(3), "nothing to keep after a load that failed");
            assertEquals("0", lines.get(1).group(10), "no unit was loaded");
        }
    }

    /** An export without declarations (only the header line) is accepted; recheck keeps its empty store. */
    @Test
    void anExportWithoutDeclarationsInBothModes() throws Exception {
        Path f = file("empty", META);
        for (String mode : new String[] {"reload", "recheck"}) {
            LauncherAccess.Result r = launch(f, "--threads", "4", "-X", "passes=3", "-X", "passes-mode=" + mode);
            assertEquals(launch(f, "--threads", "4").status, r.status, r.toString());
            List<Matcher> lines = passLines(r.err);
            assertEquals(3, lines.size(), r.err);
            for (Matcher m : lines) {
                assertEquals(lines.get(0).group(18), m.group(18), r.err);
                assertEquals("0", m.group(10), r.err);
            }
        }
    }

    // ---- settings ---------------------------------------------------------------------------------------------

    @Test
    void aFaultyPassesSettingIsAUsageError() throws Exception {
        Path f = file("malformed", "this is not an export\n");
        for (String[] bad : new String[][] {{"passes=0"}, {"passes=many"}, {"passes=1001"}, {"passes"}, {"passes-mode=again"},
            {"passes-mode"}}) {
            LauncherAccess.Result r = launch(f, "-X", bad[0]);
            assertEquals(64, r.status, bad[0] + ": " + r);
            assertTrue(r.err.contains("-X " + bad[0].replaceAll("=.*", "")), r.err);
        }
        MainOptions o = MainOptions.parse(Map.of("passes", "5", "passes-mode", "recheck"));
        assertEquals(5, o.passes);
        assertTrue(o.passesRecheck);
        assertEquals(1, MainOptions.parse(Map.of()).passes);
        assertFalse(MainOptions.parse(Map.of("passes-mode", "reload")).passesRecheck);
    }

    // ---- statistics -------------------------------------------------------------------------------------------

    @Test
    void theStatisticsFileHasATableOfThePasses() throws Exception {
        Path f = file("accepted", accepted());
        for (String mode : new String[] {"reload", "recheck"}) {
            Path stats = dir.resolve("stats-" + mode + ".json");
            LauncherAccess.Result r = launch(f, "--threads", "4", "-X", "passes=3", "-X", "passes-mode=" + mode, "-X",
                    "stats-file=" + stats);
            assertEquals(0, r.status, r.toString());
            for (int k = 1; k <= 3; k++) {
                Path own = Path.of(stats + ".pass" + k);
                if (mode.equals("reload")) {
                    // Every reload pass writes its own file, exactly as a plain run writes its statistics.
                    String pass = Files.readString(own);
                    assertTrue(pass.contains("\"driver.verdict\": \"accept\""), mode + " pass " + k + ": " + pass);
                    assertFalse(pass.contains("\"passes\""), "a pass's own file is a plain run's");
                    assertTrue(pass.contains("\"engine.primary.name\": \"reference\""), mode + " pass " + k + ": " + pass);
                } else {
                    // The driver's passes are one run: its file is F. Since rlvm-driver 150aed4bf1bf (18:03Z, "statistics
                    // per later pass") a LATER pass also leaves its own statistics in F.passK; pass 1 never does.
                    assertEquals(k > 1, Files.exists(own), "recheck: pass 1 is the run's file, pass " + k + " of the driver");
                }
            }
            String all = Files.readString(stats);
            assertTrue(all.contains("\"passes.requested\": 3"), all);
            assertTrue(all.contains("\"passes.done\": 3"), all);
            assertTrue(all.contains("\"passes.mode\": \"" + mode + "\""), all);
            assertTrue(all.contains("\"passes.verdict\": \"accept\""), all);
            assertTrue(all.contains("\"passes\": {\"columns\": [\"outcome\", \"wallMs\", \"cpuMs\""), all);
            for (int k = 1; k <= 3; k++) {
                assertTrue(all.contains("{\"label\": \"pass-" + k + "\", \"values\": [0, "), all);
            }
            assertTrue(all.contains("\"driver.verdict\": \"accept\""), "the last run's statistics are in the file: " + all);
            // Recheck: the driver's own per-pass values are there as well.
            assertEquals(mode.equals("recheck"), all.contains("\"driver.pass.3.verdict\": \"accept\""), all);
            // The plain run writes the file itself and nothing else.
            Path plain = dir.resolve("plain-" + mode + ".json");
            assertEquals(0, launch(f, "--threads", "4", "-X", "stats-file=" + plain).status);
            String plainStats = Files.readString(plain);
            assertFalse(plainStats.contains("\"passes.") || plainStats.contains("\"passes\""), plainStats);
            assertFalse(Files.exists(Path.of(plain + ".pass1")));
        }
    }

    // ---- engines per pass; a later pass with another verdict -----------------------------------------------------

    /** The reference kernel behind a provider that counts engines, sessions and checks, and can turn sour. */
    static final class Counting implements EngineProvider {
        private final EngineFactory inner;
        final AtomicInteger primaryEngines = new AtomicInteger();
        final AtomicInteger sessions = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();
        final AtomicInteger dumps = new AtomicInteger();
        final ConcurrentHashMap<Integer, AtomicInteger> checks = new ConcurrentHashMap<>();
        /** A constant is rejected from its this-many-th check on (0 = never). */
        volatile int rejectFrom;

        Counting(EngineFactory inner) {
            this.inner = inner;
        }

        @Override
        public String name() {
            return "counting";
        }

        @Override
        public int priority() {
            return 10;
        }

        @Override
        public boolean isReference() {
            return false;
        }

        @Override
        public EngineFactory factory() {
            return (store, config) -> {
                if (!config.strict) {
                    primaryEngines.incrementAndGet();
                }
                return new CountingEngine(this, inner.create(store, config));
            };
        }

        int checksOfEveryConstant() {
            int n = -1;
            for (AtomicInteger c : checks.values()) {
                if (n >= 0 && n != c.get()) {
                    return -1;
                }
                n = c.get();
            }
            return n;
        }
    }

    private static final class CountingEngine implements Engine {
        private final Counting owner;
        private final Engine real;

        CountingEngine(Counting owner, Engine real) {
            this.owner = owner;
            this.real = real;
        }

        @Override
        public String name() {
            return "counting";
        }

        @Override
        public Session newSession(WorkerContext ctx) {
            owner.sessions.incrementAndGet();
            final Session s = real.newSession(ctx);
            return new Session() {
                private boolean sour;

                @Override
                public int check(int cid, Budget budget) {
                    int n = owner.checks.computeIfAbsent(cid, c -> new AtomicInteger()).incrementAndGet();
                    int o = s.check(cid, budget);
                    sour = owner.rejectFrom > 0 && n >= owner.rejectFrom && o == Outcome.ACCEPT;
                    return sour ? Outcome.REJECT : o;
                }

                @Override
                public Reason lastReason() {
                    return sour ? Reason.DECL_TYPE_MISMATCH : s.lastReason();
                }

                @Override
                public String lastMessage() {
                    return sour ? "turned sour" : s.lastMessage();
                }

                @Override
                public KernelOps ops() {
                    return s.ops();
                }

                @Override
                public void reset() {
                    s.reset();
                }

                @Override
                public long footprintBytes() {
                    return s.footprintBytes();
                }

                @Override
                public long steps() {
                    return s.steps();
                }

                @Override
                public void close() {
                    s.close();
                }
            };
        }

        @Override
        public void dumpStats(StatsSink sink) {
            owner.dumps.incrementAndGet();
            real.dumpStats(sink);
        }

        @Override
        public void close() {
            owner.closed.incrementAndGet();
            real.close();
        }
    }

    private static final class Result {
        int outcome;
        String err;
        Counting counting;
    }

    /** The passes with the counting engine as primary engine (it is not on the class path: no launcher). */
    private static Result passes(Path export, int threads, int rejectFrom, String... settings) {
        List<EngineProvider> all = new ArrayList<>(Engines.providers());
        Counting counting = new Counting(Engines.reference(all).factory());
        counting.rejectFrom = rejectFrom;
        all.add(counting);
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("engine", "counting");
        for (String s : settings) {
            int eq = s.indexOf('=');
            extra.put(eq < 0 ? s : s.substring(0, eq), eq < 0 ? "true" : s.substring(eq + 1));
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Result r = new Result();
        r.counting = counting;
        try (PrintStream err = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            RlvmChecker checker = new RlvmChecker();
            RlvmChecker.Setup s = checker.setup(export, threads, false, 0, extra, all, err, false);
            assertNotNull(s);
            assertTrue(s.mo.passes > 1, "this helper is for more than one pass");
            r.outcome = new Passes(checker, export, threads, false, 0, extra, all, err, false).run(s);
        }
        r.err = bytes.toString(StandardCharsets.UTF_8);
        return r;
    }

    @Test
    void everyPassChecksEveryConstantWithItsOwnEngineInBothModes() throws Exception {
        Path f = file("accepted", accepted());
        for (int workers : new int[] {1, 4}) {
            for (String mode : new String[] {"reload", "recheck"}) {
                Result r = passes(f, workers, 0, "passes=3", "passes-mode=" + mode);
                assertEquals(Outcome.ACCEPT, r.outcome, r.err);
                // Reload: a complete run per pass. Recheck: the driver gives every later pass new engine
                // instances too (rlvm-driver/README.md, "Passes": only the store and the process are kept).
                assertEquals(3, r.counting.primaryEngines.get(), mode + ": an engine per pass");
                assertEquals(3, r.counting.closed.get(), mode + ": and each is closed");
                assertEquals(UNITS, r.counting.checks.size());
                assertEquals(3, r.counting.checksOfEveryConstant(), mode + ": every constant is checked once per pass");
                // (A worker that never gets a claim on so small a file opens no session.)
                int sessions = r.counting.sessions.get();
                assertTrue(sessions >= 3 && sessions <= 3 * workers, mode + ": a warm session per worker and pass at most: "
                        + sessions);
                List<Matcher> lines = passLines(r.err);
                assertEquals(3, lines.size(), r.err);
                assertEquals(mode, lines.get(2).group(3));
            }
        }
    }

    @Test
    void aLaterPassWithAnotherVerdictMakesTheRunDeclineLoudly() throws Exception {
        Path f = file("accepted", accepted());
        for (String mode : new String[] {"reload", "recheck"}) {
            // Sour from the second check of a constant on; without the reference kernel's help (no-fallback)
            // the second pass declines what the first accepted.
            Result r = passes(f, 1, 2, "passes=4", "passes-mode=" + mode, "no-fallback");
            assertEquals(Outcome.DECLINE, r.outcome, r.err);
            assertTrue(r.err.contains("rlvm: PASSES DISAGREE: pass 1 ended with ACCEPT, pass 2 (" + mode + ") with DECLINE"), r.err);
            assertEquals(1, r.err.split("PASSES DISAGREE", -1).length - 1, "said once: " + r.err);
            List<Matcher> lines = passLines(r.err);
            // Reload stops at the disagreement; the driver's series (recheck) is one run and is reported whole.
            assertEquals(mode.equals("reload") ? 2 : 4, lines.size(), r.err);
            assertEquals("ACCEPT", lines.get(0).group(18));
            assertEquals("DECLINE", lines.get(1).group(18));
        }
        // The same sour engine with the reference kernel's fallback: every pass accepts, nothing is said.
        for (String mode : new String[] {"reload", "recheck"}) {
            Result ok = passes(f, 1, 2, "passes=3", "passes-mode=" + mode);
            assertEquals(Outcome.ACCEPT, ok.outcome, ok.err);
            assertFalse(ok.err.contains("PASSES DISAGREE"), ok.err);
        }
    }

    // ---- real exports -------------------------------------------------------------------------------------------

    private static Path arenaTests() {
        Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("work/arena-site/tests"))) {
            p = p.getParent();
        }
        return p == null ? null : p.resolve("work/arena-site/tests");
    }

    /**
     * The arena's prelude (inductive blocks, quot, recursors: units the environment pass decides) in
     * both modes, batch and pipelined, 1 and 4 workers: every pass accepts every unit.
     */
    @Test
    void thePreludeWithItsInductiveBlocksInBothModes() throws Exception {
        Path root = arenaTests();
        assumeTrue(root != null && Files.isRegularFile(root.resolve("good/init-prelude.ndjson")),
                "work/arena-site/tests/good/init-prelude.ndjson not found");
        Path prelude = root.resolve("good/init-prelude.ndjson");
        String[] pipelined = {"-X", "load=pipelined", "-X", "pipeline-min-mib=0", "-X", "publish-units=256"};
        for (String mode : new String[] {"reload", "recheck"}) {
            for (String[] load : new String[][] {BATCH, pipelined}) {
                for (String workers : new String[] {"1", "4"}) {
                    String[] args = concat(load, "--threads", workers, "-X", "passes=3", "-X", "passes-mode=" + mode);
                    LauncherAccess.Result r = launch(prelude, args);
                    String what = mode + " " + String.join(" ", args);
                    assertEquals(0, r.status, what + ": " + r);
                    List<Matcher> lines = passLines(r.err);
                    assertEquals(3, lines.size(), what + ": " + r.err);
                    for (Matcher m : lines) {
                        assertEquals("ACCEPT", m.group(18), what);
                        assertEquals(lines.get(0).group(10), m.group(10), what + ": units");
                        if (!m.group(11).equals("-1")) {
                            assertEquals(m.group(10), m.group(11), what + ": every unit is accepted");
                        }
                    }
                    assertEquals(mode, lines.get(2).group(3), what);
                }
            }
        }
    }

    /**
     * An export that is not in declaration order ({@code b := a} before {@code a}): the environment
     * pass admits it in dependency order and records the outcomes. A reload pass repeats all of that.
     * A recheck pass (the driver's) reads what pass 1 recorded - or, if the driver's later pass lets a
     * new environment pass discard those records, reaches another verdict, which must then be said
     * loudly and end in DECLINE; a silent second verdict is the one thing that may not happen.
     */
    @Test
    void anExportThatIsNotInDeclarationOrder() throws Exception {
        String export = META
                + "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"a\"}}\n"
                + "{\"in\":2,\"str\":{\"pre\":0,\"str\":\"b\"}}\n"
                + "{\"il\":1,\"succ\":0}\n"
                + "{\"il\":2,\"succ\":1}\n"
                + "{\"ie\":0,\"sort\":2}\n"
                + "{\"ie\":1,\"sort\":1}\n"
                + "{\"const\":{\"name\":1,\"us\":[]},\"ie\":2}\n"
                + "{\"def\":{\"all\":[2],\"hints\":\"opaque\",\"levelParams\":[],\"name\":2,\"safety\":\"safe\",\"type\":0,\"value\":2}}\n"
                + "{\"def\":{\"all\":[1],\"hints\":\"opaque\",\"levelParams\":[],\"name\":1,\"safety\":\"safe\",\"type\":0,\"value\":1}}\n";
        Path f = file("forward", export);
        LauncherAccess.Result plain = launch(f, "--threads", "1");
        assumeTrue(plain.status == 0, "the pipeline does not accept the forward reference: " + plain);
        LauncherAccess.Result reload = launch(f, "--threads", "1", "-X", "passes=3");
        assertEquals(0, reload.status, reload.toString());
        assertEquals(3, passLines(reload.err).size());
        LauncherAccess.Result recheck = launch(f, "--threads", "1", "-X", "passes=3", "-X", "passes-mode=recheck");
        List<Matcher> lines = passLines(recheck.err);
        assertEquals(3, lines.size(), recheck.err);
        boolean same = lines.stream().allMatch(m -> m.group(18).equals("ACCEPT"));
        if (same) {
            assertEquals(0, recheck.status, recheck.toString());
        } else {
            assertEquals(2, recheck.status, "a later pass with another verdict declines: " + recheck);
            assertTrue(recheck.err.contains("PASSES DISAGREE"), recheck.err);
        }
    }

    /** Small published tests, good and bad: N passes give the verdict of the plain run. */
    @Test
    void publishedTutorialTestsKeepTheirVerdict() throws Exception {
        Path root = arenaTests();
        assumeTrue(root != null && Files.isDirectory(root.resolve("good/tutorial")) && Files.isDirectory(root.resolve("bad/tutorial")),
                "work/arena-site/tests/{good,bad}/tutorial not found");
        int n = 0;
        for (String bucket : new String[] {"good", "bad"}) {
            List<Path> files;
            try (java.util.stream.Stream<Path> s = Files.list(root.resolve(bucket).resolve("tutorial"))) {
                files = s.filter(p -> p.toString().endsWith(".ndjson")).sorted().limit(12).toList();
            }
            for (Path f : files) {
                LauncherAccess.Result plain = launch(f, "--threads", "1");
                for (String mode : new String[] {"reload", "recheck"}) {
                    LauncherAccess.Result r = launch(f, "--threads", "1", "-X", "passes=3", "-X", "passes-mode=" + mode);
                    assertEquals(plain.status, r.status, f + " " + mode + ": " + r);
                    assertEquals(3, passLines(r.err).size(), f + " " + mode + ": " + r.err);
                    assertFalse(r.err.contains("PASSES DISAGREE"), r.err);
                    n++;
                }
            }
        }
        assertTrue(n > 0);
    }
}
