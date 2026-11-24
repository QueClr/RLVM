package rlvm.main;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import rlvm.api.EngineProvider;
import rlvm.api.Outcome;
import rlvm.api.RunSignals;
import rlvm.api.WorkerContext;
import rlvm.driver.Run;
import rlvm.driver.RunStats;
import rlvm.driver.Wiring;
import rlvm.load.LoadOptions;
import rlvm.load.LoadReport;
import rlvm.store.Store;

/**
 * {@code -X passes=N} with N &gt; 1: the same export is checked N times in this process, and every
 * pass is reported (OWNER-NOTES 12: the goal is the wall clock of checking in steady state, after
 * JIT warm-up, heap growth and the page faults of the first seconds; "warm" = the same process checks
 * the same export again). A measuring mode: it never runs for a plain check ({@code passes} absent or
 * 1 takes the path of {@link RlvmChecker} it always took).
 *
 * <p><b>{@code -X passes-mode=reload}</b> (the default: the owner's metric is a warm pass that loads
 * and checks, with the load pipelined). Every pass is a complete run, exactly the code path of a
 * plain run: new settings and configuration, new engine factories, a new {@link MainWiring} (store,
 * loader, bindings), a new {@code Run} with its own engines, threads, lanes and sessions. Only the
 * process is warm. Nothing of a pass is referenced when the next one starts, and a
 * {@code System.gc()} is made between two passes, outside the timed region, so the heap does not
 * grow with the number of passes. This mode lives here.
 *
 * <p><b>{@code -X passes-mode=recheck}</b> (pure checking in steady state). This is the DRIVER's
 * {@code RunConfig.passes} (rlvm-driver/README.md, "Passes"), to which this class only delegates: one
 * {@code Run}; pass 1 is the run itself; if it accepts, each later pass is a check-only run of the
 * driver over the same loaded store - new engine instances, sessions, scheduler and verdict board,
 * every def / thm / axiom / opaque unit checked again, inductive blocks and quot records with the
 * outcome the environment pass of pass 1 recorded, no load, no collection between passes. What is
 * kept is therefore the store and the process, not what an engine instance holds (the machine's
 * shared code objects and facts are rebuilt in every pass). If pass 1 does not accept, the driver
 * makes no later pass (the store of a failed run is incomplete); the remaining passes are then reload
 * passes, and one line says so.
 *
 * <p><b>Report.</b> One line per pass on the diagnostics stream (not with {@code --quiet}), the same
 * shape in both modes; a number that the mode cannot know is -1:
 * <pre>
 * rlvm: pass K/N mode=reload|recheck wall_ms=.. cpu_ms=.. gc_ms=.. gc_count=.. load_ms=.. check_ms=..
 *       units=.. accepted=.. workers=.. pipelined=0|1 heap_kb=.. rss_kb=.. peak_rss_kb=.. settle_ms=.. verdict=ACCEPT|REJECT|DECLINE
 * </pre>
 * A reload pass (measured here): {@code wall_ms} from before its configuration is built to the
 * return of {@code Run.execute()}; {@code cpu_ms}: CPU time of the whole process in that interval (all
 * threads: workers, loader, JIT compiler, collector; 10 ms steps); {@code gc_ms} / {@code gc_count}:
 * collector time and collections in that interval (-1 where the runtime has no collector beans);
 * {@code load_ms}: the wiring's load (reader and loader, on the calling thread); {@code check_ms}:
 * from the first worker context of the pass (the start of the checking threads) to the end of the
 * run - in a pipelined pass it overlaps {@code load_ms}; {@code units}: units of the store;
 * {@code accepted}: units accepted or skipped on the board; {@code heap_kb}: heap in use when the
 * pass starts (from pass 2 on: right after the collection between the passes, i.e. what the previous
 * passes left reachable); {@code rss_kb} / {@code peak_rss_kb}: resident set after the pass and its
 * peak since the process started; {@code settle_ms}: the collection before the pass (not part of any
 * pass's time). A pass the driver made (recheck mode; its lines are printed when the run has
 * returned, after the driver's own {@code rlvm: pass K of N: ..} lines): {@code wall_ms},
 * {@code check_ms}, {@code cpu_ms} and the verdict are the driver's ({@code Run.passResults()});
 * {@code load_ms} is the wiring's for pass 1 and 0 for a later pass; {@code gc_ms}, {@code gc_count},
 * {@code accepted} of a later pass and {@code heap_kb} are -1; {@code rss_kb} / {@code peak_rss_kb}
 * are read once, after the last pass, and stand in its line only.
 *
 * <p>Statistics ({@code -X stats-file=F}). Reload: pass K writes {@code F.passK} exactly as a plain
 * run writes F, and F is the last pass's statistics plus the values {@code passes.*} and the table
 * {@code passes} (one row per pass, the columns of the line above). Recheck: F is the run's file
 * (with the driver's {@code driver.pass.K.*}) plus the same values and table. {@code --stats}: the
 * checker's line gains {@code passes=done/N passes-mode=..}; its {@code wall_ms} is the sum of
 * everything.
 *
 * <p><b>Verdict.</b> The verdict of the run is the first pass's. A later pass with another verdict
 * is a determinism bug: a loud line is printed (also with {@code --quiet}) and the run DECLINES; in
 * reload mode the remaining passes are not made.
 */
final class Passes {

    private final RlvmChecker checker;
    private final Path export;
    private final int threads;
    private final boolean stats;
    private final long stackBytes;
    private final Map<String, String> extra;
    private final List<EngineProvider> providers;
    private final PrintStream err;
    private final boolean quiet;

    /** The statistics of the pass that ran last (small: no reference to the store or the run). */
    private RunStats lastStats;

    Passes(RlvmChecker checker, Path export, int threads, boolean stats, long stackBytes, Map<String, String> extra,
            List<EngineProvider> providers, PrintStream err, boolean quiet) {
        this.checker = checker;
        this.export = export;
        this.threads = threads;
        this.stats = stats;
        this.stackBytes = stackBytes;
        this.extra = extra;
        this.providers = providers;
        this.err = err;
        this.quiet = quiet;
    }

    /** One finished pass: the numbers of its line. */
    static final class Pass {
        final int number;
        /** A recheck pass over the kept store; false: a complete run. */
        boolean recheck;
        int outcome = Outcome.DECLINE;
        long wallNanos;
        long cpuMillis = -1;
        long gcMillis = -1;
        long gcCount = -1;
        long loadNanos;
        long checkNanos;
        long units;
        long accepted;
        int workers;
        boolean pipelined;
        long heapStartKb;
        long rssKb = -1;
        long peakRssKb = -1;
        long settleNanos;

        Pass(int number) {
            this.number = number;
        }

        String line(int of) {
            return "rlvm: pass " + number + "/" + of + " mode=" + (recheck ? "recheck" : "reload")
                    + " wall_ms=" + ms(wallNanos) + " cpu_ms=" + cpuMillis + " gc_ms=" + gcMillis
                    + " gc_count=" + gcCount + " load_ms=" + ms(loadNanos) + " check_ms=" + ms(checkNanos)
                    + " units=" + units + " accepted=" + accepted + " workers=" + workers
                    + " pipelined=" + (pipelined ? 1 : 0) + " heap_kb=" + heapStartKb + " rss_kb=" + rssKb
                    + " peak_rss_kb=" + peakRssKb + " settle_ms=" + ms(settleNanos)
                    + " verdict=" + verdict(outcome);
        }

        static final String[] COLUMNS = {"outcome", "wallMs", "cpuMs", "gcMs", "gcCount", "loadMs", "checkMs", "units",
            "accepted", "workers", "pipelined", "recheck", "heapStartKb", "rssKb", "peakRssKb", "settleMs"};

        long[] row() {
            return new long[] {outcome, ms(wallNanos), cpuMillis, gcMillis, gcCount, ms(loadNanos), ms(checkNanos), units,
                accepted, workers, pipelined ? 1 : 0, recheck ? 1 : 0, heapStartKb, rssKb, peakRssKb, ms(settleNanos)};
        }

        private static long ms(long nanos) {
            return (nanos + 500_000L) / 1_000_000L;
        }
    }

    /** ACCEPT, REJECT or DECLINE: the verdict as the pass lines spell it. */
    static String verdict(int outcome) {
        return Outcome.name(outcome).toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * Runs the passes.
     *
     * @param first the setup of pass 1 (built and validated by the caller; {@code first.mo.passes > 1})
     * @return the outcome of pass 1, or DECLINE if a later pass ended with another one
     */
    int run(RlvmChecker.Setup first) {
        final int n = first.mo.passes;
        final boolean recheck = first.mo.passesRecheck;
        final Path statsFile = first.mo.statsFile;
        final long t0 = System.nanoTime();
        final List<Pass> done = new ArrayList<>(n);
        RlvmChecker.Setup s = first;
        first = null;
        if (recheck) {
            // The driver's passes: one run. It makes the later passes itself if pass 1 accepts.
            driverPasses(done, s, n);
            s = null;
            if (done.size() == 1 && n > 1 && done.get(0).outcome != Outcome.ACCEPT && !quiet) {
                err.println("rlvm: passes-mode=recheck: pass 1 ended with " + verdict(done.get(0).outcome)
                        + " (the driver checks only an accepted export again): the later passes reload");
            }
        }
        int result = done.isEmpty() ? Outcome.DECLINE : disagreement(done);
        boolean agreed = done.isEmpty() || result == done.get(0).outcome;
        // Reload passes: all of them in reload mode; in recheck mode the ones after a pass 1 that failed.
        for (int k = done.size() + 1; k <= n && agreed; k++) {
            Pass p = new Pass(k);
            if (k > 1) {
                // Between two passes, outside the timed region: what the previous pass left is garbage
                // by now (this frame holds only numbers and the previous pass's statistics) and is
                // collected here, not during the next pass.
                long g0 = System.nanoTime();
                System.gc();
                p.settleNanos = System.nanoTime() - g0;
            }
            p.heapStartKb = ProcessClock.heapUsedKb();
            Meter meter = new Meter();
            if (k > 1) {
                // New settings, configuration and factories for every pass: a run may write into its
                // configuration, and a reload pass is a complete run in every respect.
                s = checker.setup(export, threads, stats, stackBytes, extra, providers, err, quiet);
            }
            if (s == null) {
                meter.stop(); // not reachable after a first pass that was set up; DECLINE if it ever is
            } else {
                full(p, s, statsFile == null ? null : Path.of(statsFile + ".pass" + k), meter);
            }
            s = null;
            meter.fill(p);
            long[] rss = ProcessClock.rssKb();
            p.rssKb = rss[0];
            p.peakRssKb = rss[1];
            done.add(p);
            if (!quiet) {
                err.println(p.line(n));
            }
            result = disagreement(done);
            agreed = result == done.get(0).outcome;
        }
        if (statsFile != null && lastStats != null && lastStats.enabled()) {
            lastStats.put("passes.requested", n);
            lastStats.put("passes.done", done.size());
            lastStats.put("passes.mode", recheck ? "recheck" : "reload");
            lastStats.put("passes.verdict", Outcome.name(result));
            long[][] rows = new long[done.size()][];
            String[] labels = new String[rows.length];
            for (int i = 0; i < rows.length; i++) {
                rows[i] = done.get(i).row();
                labels[i] = "pass-" + done.get(i).number;
            }
            lastStats.table("passes", Pass.COLUMNS, rows, labels);
            try {
                lastStats.writeJson(statsFile);
            } catch (IOException | RuntimeException e) {
                if (!quiet) {
                    err.println("rlvm: cannot write statistics to " + statsFile + ": " + e);
                }
            }
        }
        if (stats) {
            // The plain run's line, from a setup of the same settings (the passes' own are gone).
            RlvmChecker.Setup again = checker.setup(export, threads, stats, stackBytes, extra, providers, err, true);
            if (again != null) {
                err.println(RlvmChecker.statsLine(again, result, done.isEmpty() ? 0 : done.get(0).workers,
                        System.nanoTime() - t0) + " passes=" + done.size() + "/" + n + " passes-mode="
                        + (recheck ? "recheck" : "reload"));
            }
        }
        return result;
    }

    /**
     * The verdict of the passes made so far: the first pass's, or DECLINE - with the loud line, once -
     * if the newest pass ended with another outcome than the first.
     */
    private int disagreement(List<Pass> done) {
        int verdict = done.get(0).outcome;
        for (int i = 1; i < done.size(); i++) {
            Pass p = done.get(i);
            if (p.outcome != verdict) {
                if (!disagreed) {
                    disagreed = true;
                    // Loud, and never silenced: the same export got two verdicts in one process.
                    err.println("rlvm: PASSES DISAGREE: pass 1 ended with " + verdict(verdict) + ", pass " + p.number
                            + " (" + (p.recheck ? "recheck" : "reload") + ") with " + verdict(p.outcome)
                            + " on the same export in the same process. This is a determinism bug; the run DECLINES.");
                }
                return Outcome.DECLINE;
            }
        }
        return verdict;
    }

    private boolean disagreed;

    // ---- recheck: the driver's passes ---------------------------------------------------------------------------

    /**
     * {@code passes-mode=recheck}: one run with {@code RunConfig.passes = n}. The driver loads and
     * checks (pass 1) and, if that accepted, checks the loaded store again n - 1 times; what it
     * measured per pass ({@code Run.passResults()}) becomes one {@link Pass} each.
     */
    private void driverPasses(List<Pass> done, RlvmChecker.Setup s, int n) {
        s.config.passes = n;
        long heap = ProcessClock.heapUsedKb();
        Meter meter = new Meter();
        TimedWiring wiring = new TimedWiring(new MainWiring(export, s.mo));
        Run run = null;
        int outcome;
        try {
            run = Pipeline.newRun(s.config, wiring, s.primaryFactory, s.referenceFactory, quiet ? null : err);
            outcome = run.execute();
        } catch (UnsupportedOperationException e) {
            outcome = RlvmChecker.declineNotImplemented(e, err, quiet);
            run = null;
        }
        meter.stop();
        Pass first = new Pass(1);
        first.heapStartKb = heap;
        finish(first, run, outcome, wiring, meter);
        long[][] results = run == null ? new long[0][] : run.passResults();
        if (results.length == 0) {
            // No run, or a driver that reports no passes: this class's own clocks for the one pass.
            meter.fill(first);
            results = new long[][] {{outcome, first.wallNanos, first.checkNanos, -1}};
            first.cpuMillis = -1;
        } else {
            first.gcMillis = -1;
            first.gcCount = -1;
        }
        long[] rss = ProcessClock.rssKb();
        for (int k = 1; k <= results.length; k++) {
            long[] r = results[k - 1];
            Pass p = k == 1 ? first : new Pass(k);
            p.recheck = k > 1;
            p.outcome = (int) r[0];
            p.wallNanos = Math.max(0, r[1]);
            p.checkNanos = Math.max(0, r[2]);
            p.cpuMillis = r[3] < 0 ? -1 : r[3] / 1_000_000L;
            if (k > 1) {
                p.units = first.units;
                p.workers = first.workers; // the configured count; the driver does not report a later pass's
                p.accepted = -1;
                p.heapStartKb = -1;
            }
            if (k == results.length) {
                p.rssKb = rss[0];
                p.peakRssKb = rss[1];
            }
            done.add(p);
            if (!quiet) {
                err.println(p.line(n));
            }
        }
    }

    // ---- a complete run ---------------------------------------------------------------------------------------

    /** One pass as a complete run. */
    private void full(Pass p, RlvmChecker.Setup s, Path passStats, Meter meter) {
        s.config.statsFile = passStats;
        TimedWiring wiring = new TimedWiring(new MainWiring(export, s.mo));
        Run run = null;
        int outcome;
        try {
            run = Pipeline.newRun(s.config, wiring, s.primaryFactory, s.referenceFactory, quiet ? null : err);
            outcome = run.execute();
        } catch (UnsupportedOperationException e) {
            outcome = RlvmChecker.declineNotImplemented(e, err, quiet);
            run = null;
        }
        meter.stop();
        finish(p, run, outcome, wiring, meter);
    }

    private void finish(Pass p, Run run, int outcome, TimedWiring wiring, Meter meter) {
        p.outcome = outcome;
        if (run != null && outcome != Outcome.ACCEPT && !quiet && run.verdicts().explanation() == null) {
            // As in a plain run: never end silently.
            err.println("rlvm: " + Outcome.name(outcome) + " (no explanation recorded)");
        }
        p.loadNanos = wiring.loadNanos();
        p.checkNanos = wiring.sinceFirstContext(meter.endNanos);
        Store store = wiring.store();
        if (store != null) {
            try {
                p.units = store.loadedUnits();
            } catch (RuntimeException e) {
                p.units = 0;
            }
        }
        if (run != null) {
            p.accepted = run.verdicts().acceptedCount();
            p.workers = run.workerCount();
            p.pipelined = run.pipelined();
            lastStats = run.stats();
        } else {
            lastStats = null;
        }
    }

    // ---- clocks -----------------------------------------------------------------------------------------------

    /** Wall clock, process CPU time and collector counters over one pass. */
    private static final class Meter {
        private final long cpu0 = ProcessClock.cpuMillis();
        private final long[] gc0 = ProcessClock.gc();
        private final long startNanos = System.nanoTime();
        long endNanos;
        private long cpu1 = -1;
        private long[] gc1;

        void stop() {
            endNanos = System.nanoTime();
            cpu1 = ProcessClock.cpuMillis();
            gc1 = ProcessClock.gc();
        }

        void fill(Pass p) {
            p.wallNanos = endNanos - startNanos;
            p.cpuMillis = cpu0 < 0 || cpu1 < 0 ? -1 : cpu1 - cpu0;
            p.gcCount = gc0[0] < 0 || gc1 == null || gc1[0] < 0 ? -1 : gc1[0] - gc0[0];
            p.gcMillis = gc0[1] < 0 || gc1 == null || gc1[1] < 0 ? -1 : gc1[1] - gc0[1];
        }
    }

    /**
     * The {@link MainWiring} of a pass with two clocks: the time its load took and the moment the
     * first worker context was asked for (the checking threads have started). Keeps the store for the
     * report.
     */
    static final class TimedWiring implements Wiring {
        private final MainWiring inner;
        private volatile Store store;
        private volatile long loadNanos;
        private volatile boolean contextSeen;
        private volatile long firstContextNanos;

        TimedWiring(MainWiring inner) {
            this.inner = inner;
        }

        @Override
        public Store newStore(long inputBytes) {
            Store s = inner.newStore(inputBytes);
            store = s;
            return s;
        }

        @Override
        public void load(Store store, LoadOptions options) throws IOException {
            final long t0 = System.nanoTime();
            try {
                inner.load(store, options);
            } finally {
                loadNanos = System.nanoTime() - t0;
            }
        }

        @Override
        public LoadReport loadReport() {
            return inner.loadReport();
        }

        @Override
        public WorkerContext newWorkerContext(int workerId, Store store, RunSignals signals, long overlayMaxBytes) {
            if (!contextSeen) {
                synchronized (this) {
                    if (!contextSeen) {
                        firstContextNanos = System.nanoTime();
                        contextSeen = true;
                    }
                }
            }
            return inner.newWorkerContext(workerId, store, signals, overlayMaxBytes);
        }

        Store store() {
            return store;
        }

        long loadNanos() {
            return loadNanos;
        }

        /** Nanoseconds from the first worker context to {@code endNanos}; 0 if no context was made. */
        long sinceFirstContext(long endNanos) {
            return contextSeen ? Math.max(0, endNanos - firstContextNanos) : 0;
        }
    }
}
