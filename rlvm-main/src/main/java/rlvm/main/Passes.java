package rlvm.main;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import rlvm.admit.EnvBuilder;
import rlvm.api.Engine;
import rlvm.api.EngineConfig;
import rlvm.api.EngineFactory;
import rlvm.api.EngineProvider;
import rlvm.api.Outcome;
import rlvm.api.RunSignals;
import rlvm.api.Session;
import rlvm.api.StatsSink;
import rlvm.api.WorkerContext;
import rlvm.driver.Run;
import rlvm.driver.RunConfig;
import rlvm.driver.RunHost;
import rlvm.driver.RunStats;
import rlvm.driver.StoreHost;
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
 * <p><b>{@code -X passes-mode=reload}</b> (default). Every pass is a complete run, exactly the code
 * path of a plain run: new settings and configuration, new engine factories, a new
 * {@link MainWiring} (store, loader, bindings), a new {@code Run} with its own engines, threads,
 * lanes and sessions. Only the process is warm. Nothing of a pass is referenced when the next one
 * starts, and a {@code System.gc()} is made between two passes, outside the timed region, so the heap
 * does not grow with the number of passes.
 *
 * <p><b>{@code -X passes-mode=recheck}</b>. Pass 1 is a complete run. If it ACCEPTS, every later
 * pass keeps
 * <ul>
 *   <li>the loaded store, with the environment the environment pass built (inductive blocks, quot
 *       records, and their recorded outcomes) and the wiring's bindings (levels, lists, literals);
 *   <li>the engine instances of pass 1 (primary; the strict and the reference instance once a pass
 *       created them), i.e. whatever an engine keeps per store across units and sessions - for the
 *       machine its shared code objects and shared facts;
 *   <li>the worker count of pass 1;
 * </ul>
 * and makes everything else anew: verdict board, scheduler, memory account, threads, worker
 * contexts, sessions. It is the driver's batch path over a store whose environment is complete: all
 * units are ready at once and are claimed in file order; an axiom, definition, theorem or opaque unit
 * goes through the admission and the primary engine as in any run; a unit the environment pass
 * decided (an inductive block, a quot record, and, in a file that is not in declaration order, the
 * units it admitted in dependency order) is not decided again, its recorded outcome is read, as a
 * worker of any run reads it. No environment pass is created (a new {@code rlvm.admit.EnvBuilder}
 * would discard the dependency-order outcomes of pass 1). This is the state of a long-running
 * checker: it measures checking alone. If pass 1 does not accept, the store of the failed run is
 * incomplete and is not kept: the later passes are reload passes (one line says so).
 *
 * <p><b>Report.</b> One line per pass on the diagnostics stream (not with {@code --quiet}):
 * <pre>
 * rlvm: pass K/N mode=reload|recheck wall_ms=.. cpu_ms=.. gc_ms=.. gc_count=.. load_ms=.. check_ms=..
 *       units=.. accepted=.. workers=.. pipelined=0|1 heap_kb=.. rss_kb=.. peak_rss_kb=.. settle_ms=.. verdict=ACCEPT|REJECT|DECLINE
 * </pre>
 * {@code wall_ms}: the pass, from before its configuration is built to the return of
 * {@code Run.execute()}; {@code cpu_ms}: CPU time of the whole process in that interval (all threads:
 * workers, loader, JIT compiler, collector; 10 ms steps); {@code gc_ms} / {@code gc_count}: collector
 * time and collections in that interval (-1 where the runtime has no collector beans);
 * {@code load_ms}: the wiring's load (reader and loader, on the calling thread; 0 in a recheck pass);
 * {@code check_ms}: from the first worker context of the pass (the start of the checking threads) to
 * the end of the run - in a pipelined pass it overlaps {@code load_ms}; {@code units}: units of the
 * store; {@code accepted}: units accepted or skipped on the board; {@code heap_kb}: heap in use when
 * the pass starts (from pass 2 on: right after the collection between the passes, i.e. what the
 * previous passes left reachable); {@code rss_kb} / {@code peak_rss_kb}: resident set after the pass
 * and its peak since the process started; {@code settle_ms}: the collection before the pass (not
 * part of any pass's time).
 *
 * <p>Statistics: with {@code -X stats-file=F} pass K writes {@code F.passK} exactly as a plain run
 * writes F, and F is the last pass's statistics plus the values {@code passes.*} and the table
 * {@code passes} (one row per pass, the columns of the line above). In recheck mode the engines' own
 * statistics ({@code engine.*}; written once, when an engine is closed) appear in the last pass only
 * and are sums over all passes. {@code --stats}: the checker's line gains {@code passes=done/N
 * passes-mode=..}; its {@code wall_ms} is the sum of everything.
 *
 * <p><b>Verdict.</b> The verdict of the run is the first pass's. A later pass with another verdict
 * is a determinism bug: a loud line is printed (also with {@code --quiet}), the remaining passes are
 * not made and the run DECLINES.
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

    /** Recheck mode: what pass 1 left for the later passes; null in reload mode and after a pass 1 that failed. */
    private Kept kept;
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
        int verdict = Outcome.DECLINE;
        int result = Outcome.DECLINE;
        int workers = 0;
        RlvmChecker.Setup s = first;
        first = null;
        try {
            for (int k = 1; k <= n; k++) {
                Pass p = new Pass(k);
                if (k > 1) {
                    // Between two passes, outside the timed region: what the previous pass left is
                    // garbage by now (this frame holds only numbers, `kept` in recheck mode and the
                    // previous pass's statistics) and is collected here, not during the next pass.
                    long g0 = System.nanoTime();
                    System.gc();
                    p.settleNanos = System.nanoTime() - g0;
                }
                p.heapStartKb = ProcessClock.heapUsedKb();
                Meter meter = new Meter();
                if (k > 1) {
                    // New settings, configuration and factories for every pass: a run may write into
                    // its configuration, and a reload pass is a complete run in every respect.
                    s = checker.setup(export, threads, stats, stackBytes, extra, providers, err, quiet);
                }
                Path passStats = statsFile == null ? null : Path.of(statsFile + ".pass" + k);
                if (s == null) {
                    meter.stop(); // not reachable after a first pass that was set up; DECLINE if it ever is
                } else if (kept != null) {
                    further(p, s, passStats, k == n, meter);
                } else {
                    full(p, s, passStats, recheck && k == 1, meter);
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
                if (k == 1) {
                    verdict = p.outcome;
                    result = verdict;
                    workers = p.workers;
                    if (recheck && kept == null && !quiet) {
                        err.println("rlvm: passes-mode=recheck: pass 1 ended with " + verdict(verdict)
                                + " (only a store that was loaded, admitted and accepted completely is kept): "
                                + "the later passes reload");
                    }
                } else if (p.outcome != verdict) {
                    // Loud, and never silenced: the same export got two verdicts in one process.
                    err.println("rlvm: PASSES DISAGREE: pass 1 ended with " + verdict(verdict) + ", pass " + k
                            + " (" + (p.recheck ? "recheck" : "reload") + ") with " + verdict(p.outcome)
                            + " on the same export in the same process. This is a determinism bug; the run DECLINES.");
                    result = Outcome.DECLINE;
                    break;
                }
            }
        } finally {
            closeKept();
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
                err.println(RlvmChecker.statsLine(again, result, workers, System.nanoTime() - t0) + " passes=" + done.size()
                        + "/" + n + " passes-mode=" + (recheck ? "recheck" : "reload"));
            }
        }
        return result;
    }

    // ---- a complete run ---------------------------------------------------------------------------------------

    /**
     * One pass as a complete run (pass 1, and every pass of reload mode).
     *
     * @param keep recheck mode, pass 1: the engines are created through {@link KeptEngines} and, if
     *     the pass accepts, they and the store stay for the later passes ({@link #kept})
     */
    private void full(Pass p, RlvmChecker.Setup s, Path passStats, boolean keep, Meter meter) {
        EngineFactory primary = s.primaryFactory;
        EngineFactory reference = s.referenceFactory;
        KeptEngines keptPrimary = null;
        KeptEngines keptReference = null;
        if (keep) {
            keptPrimary = new KeptEngines(primary);
            // The driver recognises "the reference kernel is the primary engine" by the SAME factory object.
            keptReference = primary == reference ? keptPrimary : new KeptEngines(reference);
            primary = keptPrimary;
            reference = keptReference;
        }
        s.config.statsFile = passStats;
        TimedWiring wiring = new TimedWiring(new MainWiring(export, s.mo));
        Run run = null;
        int outcome;
        try {
            run = Pipeline.newRun(s.config, wiring, primary, reference, quiet ? null : err);
            outcome = run.execute();
        } catch (UnsupportedOperationException e) {
            outcome = RlvmChecker.declineNotImplemented(e, err, quiet);
            run = null;
        }
        meter.stop();
        finish(p, run, outcome, wiring, meter);
        if (!keep) {
            return;
        }
        Store store = wiring.store();
        boolean whole = false;
        if (run != null && outcome == Outcome.ACCEPT && store != null) {
            try {
                whole = store.loadComplete() && store.envUnits() >= store.loadedUnits();
            } catch (RuntimeException e) {
                whole = false;
            }
        }
        if (whole) {
            LoadReport report = wiring.loadReport();
            kept = new Kept(wiring, store, keptPrimary, keptReference, run.workerCount(),
                    report == null ? -1 : report.declRecords);
        } else {
            keptPrimary.closeAll();
            keptReference.closeAll();
        }
    }

    // ---- a recheck pass over the kept store ---------------------------------------------------------------------

    private void further(Pass p, RlvmChecker.Setup s, Path passStats, boolean last, Meter meter) {
        final Kept k = kept;
        p.recheck = true;
        RunConfig config = s.config;
        config.statsFile = passStats;
        // A run over a host does not read the driver's environment variable (the driver reads it for a
        // run that loads): the tuning of pass 1 applies to the later passes too.
        String tuning = null;
        try {
            tuning = System.getenv(RunConfig.ENV);
            if (tuning != null && !tuning.isEmpty()) {
                config.setAll(tuning);
            }
        } catch (RuntimeException e) {
            tuning = null; // pass 1 reported a faulty value
        }
        // The independent unit count of pass 1's load (ARCHITECTURE.md 8.1), as in a run that loads.
        if (k.declRecords >= 0 && k.declRecords <= Integer.MAX_VALUE) {
            config.expectedUnits = (int) k.declRecords;
        }
        k.wiring.restartClock();
        k.primary.lastPass = last;
        k.reference.lastPass = last;
        RunHost host = new KeptHost(new StoreHost(k.store, config.policy, k.wiring), k.store);
        Run run = Pipeline.newRun(config, host, k.workers, k.primary, k.reference, quiet ? null : err);
        if (tuning != null && !tuning.isEmpty()) {
            run.stats().put("driver.environment", tuning);
        }
        int outcome = run.execute();
        meter.stop();
        finish(p, run, outcome, k.wiring, meter);
        p.loadNanos = 0; // nothing was loaded in this pass
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

    private void closeKept() {
        Kept k = kept;
        kept = null;
        if (k != null) {
            k.primary.closeAll();
            k.reference.closeAll();
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
     * report and for recheck passes.
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

        /** Before a further pass over the same wiring: its first context starts the clock again. */
        synchronized void restartClock() {
            contextSeen = false;
            loadNanos = 0;
        }
    }

    // ---- recheck: what is kept ----------------------------------------------------------------------------------

    /** What pass 1 of recheck mode left for the later passes. */
    private static final class Kept {
        final TimedWiring wiring;
        final Store store;
        final KeptEngines primary;
        final KeptEngines reference;
        final int workers;
        /** Declaration records the loader counted, or -1. */
        final long declRecords;

        Kept(TimedWiring wiring, Store store, KeptEngines primary, KeptEngines reference, int workers, long declRecords) {
            this.wiring = wiring;
            this.store = store;
            this.primary = primary;
            this.reference = reference;
            this.workers = workers;
            this.declRecords = declRecords;
        }
    }

    /**
     * An engine factory whose engines outlive the run that asked for them: {@code create} answers
     * with the engine it made before for the same store and configuration, and an engine's
     * {@code close()} does nothing until {@link #closeAll()}. So every pass of recheck mode works
     * with the engine instances of pass 1 (the driver asks for the primary engine, for its strict
     * twin and for the reference kernel with one configuration each).
     */
    static final class KeptEngines implements EngineFactory {
        private final EngineFactory inner;
        private final List<KeptEngine> engines = new ArrayList<>();
        /** The pass in progress is the last one: the engines' statistics are written now. */
        volatile boolean lastPass;

        KeptEngines(EngineFactory inner) {
            this.inner = inner;
        }

        @Override
        public synchronized Engine create(Store store, EngineConfig config) {
            for (KeptEngine e : engines) {
                if (e.store == store && same(e.config, config)) {
                    return e;
                }
            }
            KeptEngine e = new KeptEngine(this, inner.create(store, config), store, config);
            engines.add(e);
            return e;
        }

        private static boolean same(EngineConfig a, EngineConfig b) {
            if (a == null || b == null) {
                return a == b;
            }
            return a.profile == b.profile && a.strict == b.strict && a.memoCheck == b.memoCheck
                    && a.truffleTier == b.truffleTier && a.stats == b.stats && a.workers == b.workers;
        }

        /** Closes the engines for good. */
        synchronized void closeAll() {
            for (KeptEngine e : engines) {
                try {
                    e.real.close();
                } catch (RuntimeException ex) {
                    // the process is about to end; a failing close changes nothing
                }
            }
            engines.clear();
        }
    }

    private static final class KeptEngine implements Engine {
        private final KeptEngines owner;
        final Engine real;
        final Store store;
        final EngineConfig config;

        KeptEngine(KeptEngines owner, Engine real, Store store, EngineConfig config) {
            this.owner = owner;
            this.real = real;
            this.store = store;
            this.config = config;
        }

        @Override
        public String name() {
            return real.name();
        }

        @Override
        public Session newSession(WorkerContext ctx) {
            return real.newSession(ctx);
        }

        @Override
        public void environmentAdvanced(int units) {
            real.environmentAdvanced(units);
        }

        @Override
        public void dumpStats(StatsSink sink) {
            // Engine.dumpStats is called once per engine: in the pass that is the last one.
            if (owner.lastPass) {
                real.dumpStats(sink);
            }
        }

        @Override
        public void close() {
            // The engine outlives the run: KeptEngines.closeAll() closes it after the last pass.
        }
    }

    /**
     * The host of a recheck pass: the kept store through the driver's {@link StoreHost}, with an
     * environment pass that has nothing to do. The store's environment is complete (checked before
     * the store was kept), so the driver never needs to advance it; and a new
     * {@code rlvm.admit.EnvBuilder}, which {@code StoreHost.newEnvPass} would create, discards the
     * outcomes that the pass of the first run recorded for units it admitted in dependency order -
     * the workers would then check those units in file order and reach another verdict.
     */
    static final class KeptHost implements RunHost {
        private final StoreHost host;
        private final Store store;

        KeptHost(StoreHost host, Store store) {
            this.host = host;
            this.store = store;
        }

        @Override
        public Store store() {
            return host.store();
        }

        @Override
        public int loadedUnits() {
            return host.loadedUnits();
        }

        @Override
        public boolean loadComplete() {
            return host.loadComplete();
        }

        @Override
        public int envUnits() {
            return host.envUnits();
        }

        @Override
        public int unitClass(int unit) {
            return host.unitClass(unit);
        }

        @Override
        public int unitRank(int unit) {
            return host.unitRank(unit);
        }

        @Override
        public String unitName(int unit) {
            return host.unitName(unit);
        }

        @Override
        public long sharedBytes() {
            return host.sharedBytes();
        }

        @Override
        public void unitDecided(int unit, int boardStatus) {
            // The status column keeps what pass 1 wrote (a final status is never overwritten).
            host.unitDecided(unit, boardStatus);
        }

        @Override
        public Lane newLane(int workerId, RunSignals signals, long overlayMaxBytes) {
            return host.newLane(workerId, signals, overlayMaxBytes);
        }

        @Override
        public EnvPass newEnvPass(Lane lane, Session referenceSession, EnvBuilder.Listener listener) {
            return new EnvPass() {
                @Override
                public int advanceTo(int unit) {
                    return store.envUnits(); // complete: nothing is admitted in a recheck pass
                }

                @Override
                public void cancel() {
                    // nothing runs
                }
            };
        }
    }
}
