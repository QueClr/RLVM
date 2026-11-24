package rlvm.driver;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import rlvm.api.Engine;
import rlvm.api.EngineConfig;
import rlvm.api.EngineFactory;
import rlvm.api.Outcome;
import rlvm.api.Reason;
import rlvm.api.StatsSink;
import rlvm.load.LoadOptions;
import rlvm.load.LoadReport;
import rlvm.load.MalformedExportException;
import rlvm.load.UnsupportedExportException;
import rlvm.store.Store;

/**
 * One check of one export file.
 *
 * <pre>
 * execute:
 *   verdict := DECLINE                                     -- fail closed: only a complete run yields ACCEPT
 *   load (on the calling thread, through the Wiring) unless the caller loaded the store
 *       MalformedExportException    -> REJECT at once
 *       UnsupportedExportException  -> the published units are still checked; never ACCEPT
 *   batch:     the whole file is loaded, then checked
 *       workers = 1: one worker thread; it drives the environment pass lazily, in strict file order
 *       workers &gt; 1: an environment thread admits inductive blocks and quot ahead of the workers and
 *                    announces the frontier in batches; the workers claim runs from the Scheduler
 *   pipelined: at the loader's first publication the environment thread and the workers are started;
 *              they check what is published while the calling thread goes on loading
 *              (ARCHITECTURE.md 3.5, 6.1). A file that is published in one piece is a batch run.
 *   a unit the primary engine does not ACCEPT goes through the re-check protocol at once (Worker)
 *   join; VerdictBoard.complete(units) if the load was complete; VerdictBoard.exitCode()
 * </pre>
 * A {@code RuntimeException} that reaches this class becomes a decline. An {@code Error} on any
 * thread of the run cancels the run and is rethrown by {@link #execute()} on the calling thread (the
 * launcher turns it into status 3); the board's verdict is DECLINE in that case, never ACCEPT. This
 * class never calls {@code System.exit}.
 */
public final class Run {

    private final RunConfig config;
    private final Wiring wiring;
    private final Store presetStore;
    private final EngineFactory primaryFactory;
    private final EngineFactory referenceFactory;
    private final VerdictBoard board = new VerdictBoard();
    private final RunStats stats;
    private final AtomicReference<Throwable> fatal = new AtomicReference<>();

    private RunHost host;
    private MemoryAccount account;
    private volatile Scheduler scheduler;
    private EngineConfig engineConfig;
    private Engine primary;
    private Engine strict;
    private Engine reference;
    private Worker[] workers;
    private int workerCount = 1;
    private boolean lazyEnv = true;
    private boolean executed;
    private int minUnitsPerWorker = 128;
    private long warmUnitBytes = 1L << 30;
    /** Reset threshold of the workers' warm sessions: the configured cap, or the primary engine's default. */
    private long sessionBytes = RunConfig.DEFAULT_SESSION_BYTES;
    private long loaderUnits = -1;
    private PrintStream err = System.err;

    private HeavyGate gate;
    private final AtomicLong givenBack = new AtomicLong();
    private final AtomicLong gcRequests = new AtomicLong();
    private volatile boolean collecting;
    /** Heap bytes beyond the memory account right after the last requested collection. */
    private volatile long unaccountedFloor;

    // ---- pipelined loading ----------------------------------------------------------------------------------
    /** Tests: a load over a host that exists before loading starts. */
    private final Loading testLoading;
    /** Non-null while and after a pipelined load; the monitor of the loaded frontier. */
    private volatile LoadFrontier frontier;
    /** False only while a pipelined load is running. */
    private volatile boolean loadEnded = true;
    /** The pipelined checking threads were started (at the first publication of a load still in progress). */
    private boolean pipelineStarted;
    /** See {@link #taintRejections()}. */
    private volatile boolean rejectionsTainted;
    private Thread envThread;
    private Thread[] workerThreads;
    private int workersStarted;
    private boolean envStarted;
    private long checkPhase;

    /** A load that publishes into a host the run already has (the driver's tests). */
    interface Loading {
        void load(LoadProgress progress) throws Exception;
    }

    /**
     * The skeleton's constructor. The driver cannot see a reader ({@code rlvm-export}), so a run needs
     * a {@link Wiring}: use {@link #Run(RunConfig, Wiring, EngineFactory, EngineFactory)}.
     *
     * @throws UnsupportedOperationException always
     */
    public Run(RunConfig config, EngineFactory primary, EngineFactory reference) {
        throw new UnsupportedOperationException(
                "rlvm-driver: Run needs a Wiring to create and load the store; use Run(RunConfig, Wiring, EngineFactory, EngineFactory)");
    }

    /**
     * A complete run: the store is created and loaded through {@code wiring}, then checked.
     *
     * @param primary the engine that checks axiom/def/thm/opaque units (the machine; the reference
     *     kernel until the machine exists)
     * @param reference the reference kernel: environment pass, re-checks, differential oracle. Pass
     *     the same factory object as {@code primary} when the reference kernel is the primary engine.
     */
    public Run(RunConfig config, Wiring wiring, EngineFactory primary, EngineFactory reference) {
        this(config, wiring, null, null, null, primary, reference);
    }

    /** A run over a store the caller has already loaded; {@code wiring} only creates worker contexts. */
    public Run(RunConfig config, Wiring wiring, Store loaded, EngineFactory primary, EngineFactory reference) {
        this(config, wiring, loaded, null, null, primary, reference);
        if (loaded == null) {
            throw new IllegalArgumentException("store");
        }
    }

    /** A run over any {@link RunHost} (tests with fake units, engines and admission). */
    public Run(RunConfig config, RunHost host, EngineFactory primary, EngineFactory reference) {
        this(config, null, null, host, null, primary, reference);
        if (host == null) {
            throw new IllegalArgumentException("host");
        }
    }

    /**
     * A pipelined run over a {@link RunHost} whose units appear while {@code loading} runs on the
     * thread that calls {@link #execute()} (tests: a fake loader over fake or real units).
     */
    Run(RunConfig config, RunHost host, Loading loading, EngineFactory primary, EngineFactory reference) {
        this(config, null, null, host, loading, primary, reference);
        if (host == null || loading == null) {
            throw new IllegalArgumentException("host and loading");
        }
    }

    private Run(RunConfig config, Wiring wiring, Store loaded, RunHost host, Loading loading, EngineFactory primary,
            EngineFactory reference) {
        if (config == null || primary == null || reference == null || (wiring == null && host == null)) {
            throw new IllegalArgumentException("config, engines and a wiring or host are required");
        }
        this.config = config;
        this.wiring = wiring;
        this.presetStore = loaded;
        this.host = host;
        this.testLoading = loading;
        this.primaryFactory = primary;
        this.referenceFactory = reference;
        this.stats = new RunStats(config.statsFile != null);
    }

    /**
     * Applies the settings of the environment variable {@code RLVM_DRIVER} (see
     * {@link RunConfig#setAll}) to the configuration: tuning knobs for measurements, none of which
     * can change a verdict. A faulty value is reported on the diagnostics stream and otherwise ignored.
     */
    private void applyEnvironment() {
        String list;
        try {
            list = System.getenv(RunConfig.ENV);
        } catch (RuntimeException e) {
            return;
        }
        if (list == null || list.isEmpty()) {
            return;
        }
        try {
            config.setAll(list);
            stats.put("driver.environment", list);
        } catch (IllegalArgumentException e) {
            if (err != null) {
                err.println("rlvm: " + RunConfig.ENV + ": " + e.getMessage());
            }
        }
    }

    /** Workers the automatic rule never exceeds (ARCHITECTURE.md 6.8). */
    public static final int AUTO_MAX_WORKERS = 6;

    /** The requested number, or for 0 the automatic choice for this machine: {@link #autoWorkers(int)}. */
    public static int resolveWorkers(int requested) {
        if (requested > 0) {
            return requested;
        }
        return autoWorkers(Runtime.getRuntime().availableProcessors());
    }

    /**
     * The automatic worker count for a machine with {@code processors} CPUs (ARCHITECTURE.md 6.8):
     * two CPUs are left to the loader, the environment thread and the runtime's compiler and
     * collector threads, and no more than {@link #AUTO_MAX_WORKERS} workers are used:
     * {@code max(1, min(processors - 2, 6))}, i.e. 6 on the arena's 8-vCPU runner. The gate behind
     * the number (total CPU time against one worker) is measured by
     * {@code rlvm-driver/bin/threads-gate.sh}; the table is in the module README.
     */
    public static int autoWorkers(int processors) {
        return Math.max(1, Math.min(processors - 2, AUTO_MAX_WORKERS));
    }

    /**
     * Records a failure of the input found by a caller that loads the store itself, before
     * {@link #execute()}: a reject reason ends the run with REJECT without checking anything.
     */
    public void inputFailure(Reason reason, String message) {
        board.inputFailure(reason, message);
    }

    /**
     * A run uses fewer workers than configured when the input has fewer than this many units per
     * worker (default 128): threads and sessions for a ten-unit file cost more than they save. The
     * verdict does not depend on it.
     */
    public void setMinUnitsPerWorker(int units) {
        this.minUnitsPerWorker = Math.max(1, units);
    }

    /** Where the explanation line and anomaly events go (default {@code System.err}). */
    public void setDiagnostics(PrintStream stream) {
        this.err = stream;
    }

    /**
     * Runs to completion and returns the verdict as an {@code Outcome} code (0 accept, 1 reject, 2
     * decline). May be called once.
     *
     * @throws UnsupportedOperationException for a configuration that is not implemented yet
     *     (pipelined loading, namespace order)
     * @throws Error the first {@code Error} raised on any thread of the run
     */
    public int execute() {
        synchronized (this) {
            if (executed) {
                throw new IllegalStateException("Run.execute() may be called once");
            }
            executed = true;
        }
        if (config.order != OrderPolicy.Kind.FILE) {
            throw new UnsupportedOperationException(
                    "rlvm-driver: order " + config.order + " is deferred until after the vertical slice");
        }
        long total = stats.phaseStart("total");
        try {
            if (wiring != null) {
                applyEnvironment();
            }
            if (config.stopAfter != null && !"load".equals(config.stopAfter) && !"env".equals(config.stopAfter)) {
                // Never ignored: the run declines ("driver: ..." is the explanation).
                throw new UnsupportedOperationException("rlvm-driver: unknown stop-after phase '" + config.stopAfter + "'");
            }
            if (testLoading != null) {
                if (board.firstFailure() >= 0) {
                    pipelined(0);
                }
            } else if (host == null) {
                if (presetStore != null) {
                    host = new StoreHost(presetStore, config.policy, wiring);
                } else if (board.firstFailure() >= 0) {
                    long inputBytes = inputBytes();
                    // A stop-after run measures a phase by itself, and a small file is loaded before
                    // the threads of a pipeline would have started: both are batch runs.
                    if (config.loadMode == LoadOptions.Mode.PIPELINED && config.stopAfter == null
                            && inputBytes >= config.pipelineMinBytes) {
                        pipelined(inputBytes);
                    } else {
                        load(inputBytes);
                    }
                }
            }
            if (host != null && !pipelineStarted && board.firstFailure() >= 0) {
                check();
            }
        } catch (RuntimeException e) {
            board.internalFailure("driver: " + e);
        } finally {
            endLoad();
            closeEngines();
            stats.phaseEnd(total);
        }
        Throwable t = fatal.get();
        if (t instanceof Error) {
            throw (Error) t;
        }
        report();
        return board.exitCode();
    }

    // ---- load -----------------------------------------------------------------------------------------------

    private long inputBytes() {
        if (config.input != null) {
            try {
                return Files.size(config.input);
            } catch (IOException | RuntimeException e) {
                return 0; // the reader reports what is wrong with the file
            }
        }
        return 0;
    }

    /** Batch: the whole file on the calling thread, then {@link #check()}. */
    private void load(long inputBytes) {
        long phase = stats.phaseStart("load");
        Store store = null;
        try {
            store = wiring.newStore(inputBytes);
            wiring.load(store, new LoadOptions(LoadOptions.Mode.BATCH, Integer.MAX_VALUE, Long.MAX_VALUE,
                    config.proofBlocks, inputBytes));
        } catch (Exception e) {
            loadFailed(e, published(store));
        } finally {
            stats.phaseEnd(phase);
        }
        loadReport(store);
        if (store != null && published(store) > 0) {
            host = new StoreHost(store, config.policy, wiring);
        } else if (store != null && board.firstFailure() >= 0 && !board.hasInputFailure()) {
            host = new StoreHost(store, config.policy, wiring); // an export without declarations
        }
    }

    /** The failure classes of a load (ARCHITECTURE.md 6.6); {@code units}: published before the failure. */
    private void loadFailed(Exception e, int units) {
        if (e instanceof MalformedExportException) {
            MalformedExportException m = (MalformedExportException) e;
            board.inputFailure(Reason.MALFORMED_EXPORT, m.getMessage() + (m.line() > 0 ? " (line " + m.line() + ")" : ""));
        } else if (e instanceof UnsupportedExportException) {
            board.inputFailure(units, Reason.UNSUPPORTED_INPUT, e.getMessage());
        } else if (e instanceof IOException) {
            board.inputFailure(units, Reason.INTERNAL_ERROR, "cannot read the export: " + e);
        } else if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            board.inputFailure(units, Reason.INTERNAL_ERROR, "loading was interrupted");
        } else {
            board.inputFailure(units, Reason.INTERNAL_ERROR, "loader failed: " + e);
        }
    }

    private void loadReport(Store store) {
        if (wiring == null) {
            return;
        }
        LoadReport report = null;
        try {
            report = wiring.loadReport();
        } catch (RuntimeException e) {
            report = null; // counters are optional
        }
        if (report != null) {
            // Every declaration record is one unit of the store, dropped (unsafe, partial) records included.
            loaderUnits = report.declRecords;
            if (!report.complete && board.firstFailure() >= 0 && !board.hasInputFailure()) {
                board.inputFailure(published(store), Reason.INTERNAL_ERROR,
                        "the loader returned without reaching the end of the export");
            }
            if (stats.enabled()) {
                stats.put("load.inputBytes", report.inputBytes);
                stats.put("load.lines", report.lines);
                stats.put("load.nameRecords", report.nameRecords);
                stats.put("load.levelRecords", report.levelRecords);
                stats.put("load.exprRecords", report.exprRecords);
                stats.put("load.declRecords", report.declRecords);
                stats.put("load.units", report.units);
                stats.put("load.constants", report.constants);
                stats.put("load.sharedNodes", report.sharedNodes);
                stats.put("load.blockNodes", report.blockNodes);
                stats.put("load.blockBytes", report.blockBytes);
                stats.put("load.promotedNodes", report.promotedNodes);
                stats.put("load.droppedUnsafe", report.droppedUnsafe);
                stats.put("load.unresolvedRefs", report.unresolvedRefs);
                stats.put("load.wallNanos", report.wallNanos);
            }
        }
    }

    // ---- pipelined load ---------------------------------------------------------------------------------------

    /**
     * Loads on the calling thread while the environment thread and the workers check what is
     * published (ARCHITECTURE.md 3.5). The checking threads are started by the loader's first
     * publication that is not the end of the file; a load that publishes only at its end is followed
     * by the batch {@link #check()} (the caller does that when {@link #pipelineStarted} is false).
     */
    private void pipelined(long inputBytes) {
        final LoadFrontier f = new LoadFrontier(board);
        frontier = f;
        loadEnded = false;
        long phase = stats.phaseStart("load");
        Store store = null;
        try {
            LoadProgress progress = new LoadProgress() {
                @Override
                public void published(int units) {
                    onPublished(units);
                }
            };
            if (testLoading != null) {
                testLoading.load(progress);
            } else {
                store = wiring.newStore(inputBytes);
                host = new StoreHost(store, config.policy, wiring);
                // The options carry the loader's listener; the wiring passes them to the loader as they are.
                final LoadProgress p = progress;
                LoadOptions options = LoadOptions.pipelined(inputBytes)
                        .withPublishEvery(Math.max(1, config.publishEveryUnits), Math.max(1, config.publishEveryBytes))
                        .withProofBlocks(config.proofBlocks)
                        .withListener(new rlvm.load.LoadListener() {
                            @Override
                            public void published(int loadedUnits, boolean complete) {
                                p.published(loadedUnits);
                            }
                        });
                wiring.load(store, options);
            }
        } catch (Exception e) {
            loadFailed(e, host == null ? 0 : safeLoadedUnits(host));
        } catch (Error e) {
            // The checking threads may be running: cancel the run, join them below, rethrow at the end.
            fatal(e);
        } finally {
            stats.phaseEnd(phase);
        }
        loadReport(store);
        if (!pipelineStarted) {
            // Nothing was published before the end of the load: a batch run from here on.
            endLoad();
            if (host != null && testLoading == null && safeLoadedUnits(host) == 0
                    && (board.firstFailure() < 0 || board.hasInputFailure())) {
                host = null; // nothing to check, and the verdict is the input failure's
            }
            return;
        }
        final RunHost h = host;
        int units = safeLoadedUnits(h);
        f.published(units);
        endLoad();
        stats.put("driver.pipeline.publications", f.publications());
        finishCheck(units, h.loadComplete(), false);
    }

    /** The load is over, complete or not: waiters are released, outcomes obtained from now on are final. */
    private void endLoad() {
        LoadFrontier f = frontier;
        loadEnded = true;
        if (f != null) {
            f.end();
        }
    }

    private static int safeLoadedUnits(RunHost h) {
        try {
            return h.loadedUnits();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** The loader's callback, on the loading thread. Never throws. */
    private void onPublished(int units) {
        try {
            final RunHost h = host;
            if (h == null) {
                return;
            }
            if (!pipelineStarted) {
                if (h.loadComplete() || units <= 0) {
                    return; // the file in one piece: the batch check follows when the load has returned
                }
                pipelineStarted = true;
                prepare(h, resolveWorkers(config.workers), false);
                startThreads(h, -1, false);
            }
            if (account != null) {
                account.setShared(sharedBytes());
            }
            frontier.published(units);
        } catch (Throwable t) {
            fatal(t);
        }
    }

    /** True unless a pipelined load is still running: outcomes obtained now have seen the whole file. */
    boolean loadEnded() {
        return loadEnded;
    }

    /**
     * The environment pass failed while the file was still loading and had, by then, written down
     * failures of other units too ({@code RunHost.RETRY_TAINTED}; not seen with the published
     * rlvm-admit, which admits out of file order only when the file is complete). The failed unit is
     * decided again after the load and those records are erased, but the driver cannot know what
     * else rests on them. So from here on this run records no rejection at all, neither the pass's
     * nor a worker's (the admission may hand a worker an outcome the pass recorded): what would be
     * REJECTED is DECLINED(UNCONFIRMED_REJECT). Fail closed; a batch run of the same file decides it.
     */
    void taintRejections() {
        rejectionsTainted = true;
    }

    /** See {@link #taintRejections()}. */
    boolean rejectionsTainted() {
        return rejectionsTainted;
    }

    /**
     * Parks the calling worker until the load has ended.
     *
     * @return false if {@code unit} came to lie above the first failure meanwhile
     */
    boolean awaitLoadEnd(int unit) {
        LoadFrontier f = frontier;
        if (f == null || loadEnded) {
            return unit <= board.firstFailure();
        }
        return f.awaitEnd(unit);
    }

    HeavyGate gate() {
        return gate;
    }

    /** A lane returned {@code bytes} of capacity to the collector (statistics). */
    void gaveBack(long bytes) {
        givenBack.addAndGet(bytes);
    }

    /**
     * Called by a worker between two claims. With {@code RunConfig.gcAfterBytes} set, asks the
     * runtime for a collection when the heap holds that much more than the memory account knows of
     * (arrays a heavy declaration left behind, tables the engines dropped), measured against what
     * was left unaccounted after the previous collection. The runtime's own policy collects only
     * when its heap is full, and the peak resident set then shows the whole heap.
     */
    void maybeCollect() {
        long limit = config.gcAfterBytes;
        if (limit <= 0 || collecting) {
            return;
        }
        Runtime rt = Runtime.getRuntime();
        long unaccounted = rt.totalMemory() - rt.freeMemory() - account.accountedBytes();
        if (unaccounted - unaccountedFloor <= limit) {
            return;
        }
        synchronized (givenBack) {
            if (collecting) {
                return;
            }
            collecting = true;
        }
        try {
            System.gc();
            gcRequests.incrementAndGet();
            long after = rt.totalMemory() - rt.freeMemory() - account.accountedBytes();
            unaccountedFloor = Math.max(0, after);
        } finally {
            collecting = false;
        }
    }

    private static int published(Store store) {
        if (store == null) {
            return 0;
        }
        try {
            return store.loadedUnits();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    // ---- check ----------------------------------------------------------------------------------------------

    /** Batch: checks a host whose load has ended. */
    private void check() {
        final RunHost h = host;
        final int units = h.loadedUnits();
        final boolean complete = h.loadComplete();
        board.ensureUnits(units);
        prepare(h, Math.max(1, Math.min(resolveWorkers(config.workers), units / minUnitsPerWorker)), true);
        stats.put("driver.units", units);
        if ("load".equals(config.stopAfter)) {
            board.stopped("stopped after loading (stop-after=load)");
            return;
        }
        final boolean envOnly = "env".equals(config.stopAfter);
        startThreads(h, units, envOnly);
        finishCheck(units, complete, envOnly);
    }

    /**
     * Everything the checking threads share: worker count, memory account, heavy gate, budgets.
     *
     * @param mayBeLazy one worker drives the environment pass itself (batch runs; strict file order)
     */
    private void prepare(final RunHost h, int count, boolean mayBeLazy) {
        board.setNamer(new VerdictBoard.UnitNamer() {
            @Override
            public String name(int unit) {
                return h.unitName(unit);
            }
        });
        workerCount = count;
        lazyEnv = mayBeLazy && workerCount == 1;
        account = new MemoryAccount(config.memoryLimitBytes, workerCount + 1);
        if (!lazyEnv) {
            account.setLastResort(workerCount);
        }
        gate = new HeavyGate(board, account);
        long shared = sharedBytes();
        account.setShared(shared);
        // The whole headroom: the account decides who gives way when the run passes its limit.
        warmUnitBytes = config.unitBytes > 0 ? config.unitBytes
                : Math.max(256L << 20, config.memoryLimitBytes - Math.min(shared, config.memoryLimitBytes));
        engineConfig = new EngineConfig(config.profile, false, false, config.truffleTier, stats.enabled(), workerCount);
        stats.put("driver.workers", workerCount);
        stats.put("driver.memory.sharedBytesAtStart", shared);
        stats.put("driver.memory.warmUnitBytes", warmUnitBytes);
    }

    /**
     * Creates the primary engine and the scheduler and starts the environment thread and the workers.
     *
     * @param units the number of units of a host whose load has ended, or -1 while a pipelined load
     *     is running (the environment thread then follows the loaded frontier)
     */
    private void startThreads(RunHost h, int units, boolean envOnly) {
        primary = primaryFactory.create(h.store(), engineConfig);
        sessionBytes = config.sessionBytesFor(nameOf(primary));
        stats.put("driver.sessionBytes", sessionBytes);
        Scheduler s = new Scheduler(null, OrderPolicy.FILE_ORDER, workerCount, config.chunkUnits, board);
        scheduler = s;
        checkPhase = stats.phaseStart("check");
        final long stack = Math.max(config.workerStackBytes, config.deepStackBytes);
        envThread = null;
        if (envOnly || !lazyEnv) {
            envThread = new Thread(null, new EnvRunner(this, units), "rlvm-env", config.deepStackBytes);
        } else {
            s.unitsReady(units);
            s.close();
        }
        workers = new Worker[envOnly ? 0 : workerCount];
        workerThreads = new Thread[workers.length];
        for (int i = 0; i < workers.length; i++) {
            workers[i] = new Worker(i, this, primary, s, board);
            workerThreads[i] = new Thread(null, workers[i], "rlvm-worker-" + i, stack);
        }
        // A thread that cannot be started (out of memory, out of threads) cancels the run; the threads
        // that did start are still joined before anything is closed.
        envStarted = false;
        workersStarted = 0;
        try {
            if (envThread != null) {
                envThread.start();
                envStarted = true;
            }
            for (Thread t : workerThreads) {
                t.start();
                workersStarted++;
            }
        } catch (Throwable t) {
            fatal(t);
            if (!envStarted) {
                s.close();
            }
        }
    }

    /** Joins the checking threads and marks the run complete if it was. */
    private void finishCheck(int units, boolean complete, boolean envOnly) {
        boolean interrupted = false;
        for (int i = 0; i < workersStarted; i++) {
            interrupted |= join(workerThreads[i]);
        }
        if (envStarted) {
            interrupted |= join(envThread);
        }
        stats.phaseEnd(checkPhase);
        stats.put("driver.units", units);
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (envOnly) {
            board.stopped("stopped after the environment pass (stop-after=env)");
            return;
        }
        if (fatal.get() != null || !complete) {
            return; // never ACCEPT: complete() is not called
        }
        // The count that drove the run (the store's) against an independent one: the caller's, else the
        // loader's count of declaration records (ARCHITECTURE.md 8.1).
        long expected = config.expectedUnits >= 0 ? config.expectedUnits : loaderUnits;
        if (expected >= 0 && expected != units) {
            board.inputFailure(units, Reason.INTERNAL_ERROR, "the reader counted " + expected
                    + " declaration records, the store holds " + units + " units");
            return;
        }
        board.complete(units);
    }

    /** Joins {@code t}; an interrupt cancels the run and the join continues. Returns true if interrupted. */
    private boolean join(Thread t) {
        boolean interrupted = false;
        for (;;) {
            try {
                t.join();
                return interrupted;
            } catch (InterruptedException e) {
                interrupted = true;
                board.internalFailure("the run was interrupted");
                Scheduler s = scheduler;
                if (s != null) {
                    s.wake();
                }
                wakeWaiters();
            }
        }
    }

    /** After a cancellation: threads parked at the heavy gate or for the loader look at the first failure again. */
    private void wakeWaiters() {
        HeavyGate g = gate;
        if (g != null) {
            g.wake();
        }
        LoadFrontier f = frontier;
        if (f != null) {
            f.wake();
        }
    }

    private long sharedBytes() {
        try {
            return host.sharedBytes();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Refreshes the shared part of the memory account (the environment pass may have added terms). */
    void refreshSharedBytes() {
        account.setShared(sharedBytes());
    }

    /** A throwable outside a unit boundary: the run is cancelled; an {@code Error} is rethrown by {@link #execute()}. */
    void fatal(Throwable t) {
        fatal.compareAndSet(null, t);
        board.internalFailure(Thread.currentThread().getName() + ": " + t);
        Scheduler s = scheduler;
        if (s != null) {
            s.wake();
        }
        wakeWaiters();
    }

    // ---- engines --------------------------------------------------------------------------------------------

    boolean primaryIsReference() {
        return primaryFactory == referenceFactory;
    }

    Engine primaryEngine() {
        return primary;
    }

    /** The primary engine in strict mode (step 1 of the re-check protocol); created on first use. */
    synchronized Engine strictEngine() {
        if (strict == null) {
            strict = primaryFactory.create(host.store(), engineConfig.withStrict(true));
        }
        return strict;
    }

    /** The reference kernel (environment pass, re-checks); created on first use. */
    synchronized Engine referenceEngine() {
        if (reference == null) {
            reference = referenceFactory.create(host.store(), new EngineConfig(config.profile, true, false,
                    config.truffleTier, stats.enabled(), workerCount + 1));
        }
        return reference;
    }

    private synchronized void closeEngines() {
        closeEngine(primary, "engine.primary.");
        closeEngine(strict, "engine.strict.");
        closeEngine(reference, "engine.reference.");
        primary = null;
        strict = null;
        reference = null;
    }

    private void closeEngine(Engine e, final String prefix) {
        if (e == null) {
            return;
        }
        try {
            if (stats.enabled()) {
                stats.put(prefix + "name", e.name());
                e.dumpStats(new PrefixSink(stats, prefix));
            }
        } catch (RuntimeException ex) {
            stats.put(prefix + "dumpStats.error", ex.toString());
        }
        try {
            e.close();
        } catch (RuntimeException ex) {
            stats.put(prefix + "close.error", ex.toString());
        }
    }

    private static final class PrefixSink implements StatsSink {
        private final StatsSink sink;
        private final String prefix;

        PrefixSink(StatsSink sink, String prefix) {
            this.sink = sink;
            this.prefix = prefix;
        }

        @Override
        public void put(String key, long value) {
            sink.put(prefix + key, value);
        }

        @Override
        public void put(String key, double value) {
            sink.put(prefix + key, value);
        }

        @Override
        public void put(String key, String value) {
            sink.put(prefix + key, value);
        }

        @Override
        public void table(String key, String[] columns, long[][] rows, String[] rowLabels) {
            sink.table(prefix + key, columns, rows, rowLabels);
        }
    }

    // ---- report ---------------------------------------------------------------------------------------------

    private void report() {
        int code = board.exitCode();
        if (stats.enabled()) {
            stats.put("driver.verdict", Outcome.name(code));
            stats.put("driver.decidingUnit", board.decidingUnit());
            stats.put("driver.accepted", board.acceptedCount());
            stats.put("driver.rejected", board.rejectedCount());
            stats.put("driver.declined", board.declinedCount());
            stats.put("driver.warmAnomalies", board.warmAnomalies());
            stats.put("driver.machineIncomplete", board.machineIncomplete());
            stats.put("driver.disagreements", board.disagreements());
            stats.put("driver.primaryIsReference", primaryIsReference() ? 1 : 0);
            if (account != null) {
                stats.put("driver.memory.peakAccountedBytes", account.peakBytes());
                stats.put("driver.memory.peakSessionBytes", account.peakSessionBytes());
                stats.put("driver.memory.limitBytes", account.limitBytes());
                stats.put("driver.memory.victims", account.victimCount());
                stats.put("driver.memory.gcRequests", gcRequests.get());
                stats.put("driver.memory.givenBackBytes", givenBack.get());
            }
            HeavyGate g = gate;
            if (g != null) {
                stats.put("driver.heavy.entries", g.entryCount());
                stats.put("driver.heavy.waits", g.waitCount());
                stats.put("driver.heavy.yields", g.yieldCount());
            }
            stats.put("driver.pipelined", pipelineStarted ? 1 : 0);
            stats.put("driver.pipeline.rejectionsTainted", rejectionsTainted ? 1 : 0);
            Scheduler s = scheduler;
            if (s != null) {
                stats.put("driver.scheduler.claims", s.claimCount());
                stats.put("driver.scheduler.splits", s.splitCount());
                stats.put("driver.scheduler.steals", s.stealCount());
                stats.put("driver.scheduler.spilledUnits", s.spilledUnits());
                long inUnit = 0;
                if (workers != null) {
                    for (Worker w : workers) {
                        inUnit += w.restsReleasedInUnit();
                    }
                }
                stats.put("driver.scheduler.restsReleasedInUnit", inUnit);
            }
            if (workers != null && workers.length > 0) {
                long[][] rows = new long[workers.length][];
                String[] labels = new String[workers.length];
                for (int i = 0; i < workers.length; i++) {
                    Worker w = workers[i];
                    rows[i] = new long[] {w.unitsDone(), w.sessionResets(), w.unitsCancelled(), w.rechecks(),
                            w.envResets(), w.mirrorFailures(), w.giveBacks(), w.givenBackBytes(), w.maxSlackBytes(),
                            w.snapshotResets(), w.deferredUnits(), w.gatedStages()};
                    labels[i] = "worker-" + i;
                }
                stats.table("driver.workers", new String[] {"accepted", "sessionResets", "cancelled", "rechecks",
                        "envResets", "mirrorFailures", "giveBacks", "givenBackBytes", "maxSlackBytes", "snapshotResets",
                        "deferredUnits", "gatedStages"}, rows, labels);
            }
            String[] events = board.events();
            for (int i = 0; i < events.length; i++) {
                stats.put("driver.event." + i, events[i]);
            }
            try {
                stats.writeJson(config.statsFile);
            } catch (IOException | RuntimeException e) {
                if (err != null) {
                    err.println("rlvm: cannot write statistics to " + config.statsFile + ": " + e);
                }
            }
        }
        if (err == null) {
            return;
        }
        if (config.logAnomalies) {
            String[] events = board.events();
            for (int i = 0; i < events.length && i < 8; i++) {
                err.println("rlvm: " + events[i]);
            }
        }
        if (config.explain && code != Outcome.EXIT_ACCEPT) {
            String line = board.explanation();
            if (line != null) {
                err.println("rlvm: " + line);
            }
        }
    }

    // ---- accessors ------------------------------------------------------------------------------------------

    /** Outcomes and counters of the run. */
    public VerdictBoard verdicts() {
        return board;
    }

    public RunStats stats() {
        return stats;
    }

    /** The memory account of the checking phase; null before {@link #execute()} reached it. */
    public MemoryAccount account() {
        return account;
    }

    /** The workers of the finished run (statistics); null if the checking phase did not start. */
    public Worker[] workers() {
        return workers;
    }

    /** True if the run checked units while the file was still being loaded (a pipelined run). */
    public boolean pipelined() {
        return pipelineStarted;
    }

    /** Number of workers the run used (it may be smaller than configured for a small input). */
    public int workerCount() {
        return workerCount;
    }

    RunConfig config() {
        return config;
    }

    RunHost host() {
        return host;
    }

    boolean lazyEnv() {
        return lazyEnv;
    }

    /**
     * The footprint above which a worker resets its warm session: {@code RunConfig.sessionBytes}, or
     * for 0 the default of the primary engine ({@link RunConfig#defaultSessionBytes(String)}).
     */
    public long sessionBytes() {
        return sessionBytes;
    }

    /** {@code Engine.name()}, or null if the engine cannot say. */
    static String nameOf(Engine e) {
        try {
            return e == null ? null : e.name();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Bytes a unit may add to its session in a warm attempt (see {@code RunConfig.unitBytes}). */
    long warmUnitBytes() {
        return warmUnitBytes;
    }

    Scheduler scheduler() {
        return scheduler;
    }

    // ---- the environment thread ----------------------------------------------------------------------------------

    /**
     * Advances the environment pass ahead of the workers and announces each new frontier to the
     * scheduler: runs of plain units in batches, every inductive block or quot record by itself.
     * Stops at the first unit the pass fails (the frontier then stays there for good) and when the
     * first failure lies below the frontier.
     *
     * <p>During a pipelined load it follows the loaded frontier: it parks when it has caught up with
     * the loader and is woken by the next publication. A decline of the pass while the file is still
     * loading is decided again after the end of the load ({@link EnvLane#retry()}).
     */
    private static final class EnvRunner implements Runnable {
        private final Run run;
        /** The number of units of a completely loaded host, or -1: follow the loader. */
        private final int fixedUnits;

        EnvRunner(Run run, int units) {
            this.run = run;
            this.fixedUnits = units;
        }

        @Override
        public void run() {
            final RunStats stats = run.stats;
            final Scheduler scheduler = run.scheduler;
            final VerdictBoard board = run.board;
            final int slot = run.workerCount;
            final LoadFrontier loader = fixedUnits < 0 ? run.frontier : null;
            long[] sched0 = stats.enabled() ? RunStats.currentThreadSched() : null;
            EnvLane env = null;
            long waits = 0;
            long waitsForEnd = 0;
            try {
                LaneSignals signals = new LaneSignals(board, run.account, slot, true);
                env = new EnvLane(run, signals);
                final EnvLane lane = env;
                final long[] last = new long[1];
                signals.setProbe(new LaneSignals.Probe() {
                    @Override
                    public void sample() {
                        long now;
                        try {
                            now = lane.footprintBytes();
                        } catch (RuntimeException e) {
                            return;
                        }
                        long d = now - last[0];
                        if (d >= MemoryAccount.GRANULE || d <= -MemoryAccount.GRANULE) {
                            last[0] = now;
                            run.account.report(slot, now);
                        }
                    }
                });
                final int batch = Math.max(1, run.config.envBatchUnits);
                final RunHost host = run.host;
                int loaded = loader == null ? fixedUnits : loader.loaded();
                int frontier = Math.min(loaded, host.envUnits());
                scheduler.unitsReady(frontier);
                while (board.firstFailure() >= frontier) {
                    // The end of the load is read before the frontier: after it the frontier is final.
                    boolean ended = loader == null || loader.ended();
                    loaded = loader == null ? fixedUnits : loader.loaded();
                    if (frontier >= loaded) {
                        if (ended) {
                            break;
                        }
                        waits++;
                        loader.awaitBeyond(frontier);
                        continue;
                    }
                    // An inductive block or quot record is advanced alone, after everything below it was
                    // announced: should it never finish because an earlier unit is ill-typed, the workers
                    // can still reach that unit, record the failure and thereby cancel the pass. The
                    // units in between are only bound and guarded (no judgments), in batches.
                    int target = frontier + 1;
                    if (host.unitClass(frontier) == RunHost.CHECK) {
                        int limit = (int) Math.min(loaded, (long) frontier + batch);
                        while (target < limit && host.unitClass(target) == RunHost.CHECK) {
                            target++;
                        }
                    }
                    signals.guard(frontier);
                    int n = env.advanceTo(target);
                    signals.guard(-1);
                    run.refreshSharedBytes();
                    Engine p = run.primary;
                    if (p != null) {
                        p.environmentAdvanced(n);
                    }
                    scheduler.unitsReady(Math.min(n, loaded));
                    if (n > frontier) {
                        frontier = n;
                    }
                    if (n < target) {
                        // The pass failed at unit n (its listener told the board) or was cancelled:
                        // the end. Or it could not decide n while the file was still loading: it said
                        // nothing and waits (EnvBuilder.waitingForLoad), or it reported a decline that
                        // was withheld; either way once more after the end of the load.
                        int pending = env.pendingUnit();
                        if (pending >= 0) {
                            if (!run.awaitLoadEnd(pending) || !env.retry()) {
                                break;
                            }
                        } else if (!env.waitingForLoad() || !run.awaitLoadEnd(n)) {
                            break;
                        } else {
                            waitsForEnd++;
                        }
                    }
                }
            } catch (Throwable t) {
                run.fatal(t);
            } finally {
                scheduler.close();
                if (env != null) {
                    stats.put("driver.env.sessionResets", env.resets());
                    stats.put("driver.env.unitsDecided", env.decided());
                    stats.put("driver.env.retries", env.retries());
                    stats.put("driver.env.renewals", env.renewals());
                    stats.put("driver.env.waitsForLoader", waits);
                    stats.put("driver.env.waitsForLoadEnd", waitsForEnd);
                    try {
                        env.close();
                    } catch (Throwable t) {
                        run.fatal(t);
                    }
                }
                run.account.report(slot, 0);
                if (stats.enabled()) {
                    long[] sched1 = RunStats.currentThreadSched();
                    stats.threadTimes("rlvm-env", sched0[0] < 0 || sched1[0] < 0 ? -1 : sched1[0] - sched0[0],
                            sched0[1] < 0 || sched1[1] < 0 ? -1 : sched1[1] - sched0[1]);
                }
            }
        }
    }
}
