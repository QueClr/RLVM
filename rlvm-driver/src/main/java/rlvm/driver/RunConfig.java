package rlvm.driver;

import java.nio.file.Path;

import rlvm.admit.Policy;
import rlvm.api.Budget;
import rlvm.api.EngineConfig;
import rlvm.load.LoadOptions;

/** Everything a run needs to know. Mutable builder-style fields with the arena defaults. */
public final class RunConfig {

    /** The export file ({@code $IN}). May be null when the caller hands a loaded store to {@link Run}. */
    public Path input;

    /** Checking threads. 0 = automatic: {@link Run#resolveWorkers(int)} (the runner's CPUs minus two, at most 6). */
    public int workers = 0;

    /**
     * How the store is filled. {@code BATCH}: the whole file is loaded on the calling thread, then
     * checked. {@code PIPELINED} (ARCHITECTURE.md 3.5): the calling thread loads while the environment
     * thread and the workers check what was published so far; it needs a {@link Wiring} whose
     * {@link Wiring#load} honours the mode of the options (publishes while it reads). With a wiring
     * that publishes once, at the end, and for inputs below {@link #pipelineMinBytes}, a pipelined
     * run is a batch run.
     */
    public LoadOptions.Mode loadMode = LoadOptions.Mode.BATCH;

    /** {@code PIPELINED}: the loader publishes after this many units ... */
    public int publishEveryUnits = 4096;

    /** ... or this many input bytes, whichever comes first (ARCHITECTURE.md 3.5). */
    public long publishEveryBytes = 32L << 20;

    /**
     * {@code PIPELINED}: an input smaller than this is loaded in batch mode (threads that wait for a
     * loader which finishes within milliseconds cost more than they save). Never affects the verdict.
     */
    public long pipelineMinBytes = 8L << 20;

    /** Helper threads that tokenise file ranges for the loader ({@code rlvm.export.ParallelScan}); 0 = none. */
    public int scanThreads = 0;

    /** Keep theorem and opaque values as packed proof blocks (false: every record becomes a shared term). */
    public boolean proofBlocks = true;

    /** Order of ready units. Only {@code FILE} is implemented. */
    public OrderPolicy.Kind order = OrderPolicy.Kind.FILE;

    /** Window of the namespace-grouping order, in units. */
    public int orderWindow = 16384;

    /** Largest run of units one worker claims at a time. */
    public int chunkUnits = 32;

    /**
     * A session is reset when its footprint ({@code Session.footprintBytes()}: bytes in use, counted
     * once) exceeds this many bytes. 0 = automatic: the default of the engine that owns the session,
     * {@link #defaultSessionBytes(String)} of its {@code Engine.name()} (the machine's warm sessions
     * want a small cap, the reference kernel's keep nothing between units). A positive value
     * ({@code -X session-mib=N}) applies to every engine. Never affects the verdict.
     */
    public long sessionBytes = 0;

    /** Session cap of an engine without an entry in {@link #defaultSessionBytes(String)}: 16 MiB (RECONCILIATION.md section 3). */
    public static final long DEFAULT_SESSION_BYTES = 16L << 20;

    /**
     * Session cap of the machine ({@code Engine.name()} = "machine"): 4 MiB. Measured with its warm
     * sessions ({@code -X machine.switches=warm,gated,expect}; native image, one worker; module
     * README, "Session cap"): 16 MiB is slower than no warm session at all, 2 MiB to 4 MiB are the
     * fastest on Init, Std and the Cedar slice.
     */
    public static final long MACHINE_SESSION_BYTES = 4L << 20;

    /**
     * The session cap the driver uses for an engine when {@link #sessionBytes} is 0: a default per
     * engine, looked up by {@code Engine.name()}. ({@code rlvm.api.Engine} has no method through
     * which an engine could state the cap itself; the table lives here until it has one.)
     *
     * @param engineName {@code Engine.name()}; null or an unknown name gives {@link #DEFAULT_SESSION_BYTES}
     */
    public static long defaultSessionBytes(String engineName) {
        if ("machine".equals(engineName)) {
            return MACHINE_SESSION_BYTES;
        }
        return DEFAULT_SESSION_BYTES;
    }

    /** The session cap for sessions of the engine named {@code engineName}: {@link #sessionBytes}, or the engine's default when that is 0. */
    public long sessionBytesFor(String engineName) {
        return sessionBytes > 0 ? sessionBytes : defaultSessionBytes(engineName);
    }

    /** Sum of all store, overlay and engine arena memory above which the run declines instead of risking the OOM killer. */
    public long memoryLimitBytes = 12L << 30;

    /**
     * Lower bound of a worker thread's stack. Workers are in fact created with
     * {@code max(workerStackBytes, deepStackBytes)}: re-checks by the reference kernel run on the
     * worker's own thread (a reservation, pages are committed when touched).
     */
    public long workerStackBytes = 64L << 20;

    /** Stack of the threads that run the reference kernel (environment pass, re-checks, workers). */
    public long deepStackBytes = 1L << 30;

    public Policy policy = Policy.arena();

    public EngineConfig.KernelProfile profile = EngineConfig.KernelProfile.LEAN_4_36;

    /**
     * Re-check a rejection of the primary engine before it becomes the verdict: cold strict run of the
     * primary engine, then the reference kernel (ARCHITECTURE.md 8.2). False: the primary engine's
     * REJECT is final (measurement and debugging only).
     */
    public boolean recheckRejections = true;

    /**
     * The reference kernel decides what the primary engine cannot (ARCHITECTURE.md 8.2): a unit the
     * primary engine declines or throws on, and a rejection of the primary engine that the reference
     * kernel does not confirm. False ({@code -X no-fallback}; gate runs of an engine under test): the
     * reference kernel never turns a non-ACCEPT of the primary engine into an ACCEPT. Such a unit is
     * DECLINED (the primary engine's reason; {@code ENGINE_DISAGREEMENT} for a rejection the
     * reference kernel accepts), so the run is accepted only if the primary engine accepted every
     * unit by itself. Rejections are still confirmed by the reference kernel, and the environment
     * pass (inductive blocks, quot) is the reference kernel's in any case. No effect when the
     * reference kernel is the primary engine.
     */
    public boolean referenceFallback = true;

    /**
     * Run the reference kernel on every unit beside the primary engine and decline
     * ({@code ENGINE_DISAGREEMENT}) when one accepts what the other rejects. Slow; test runs only.
     */
    public boolean differential = false;

    public boolean truffleTier = true;

    /** Write statistics as JSON to this file; null = no statistics, no cost. */
    public Path statsFile;

    /** Stop after this phase (for per-phase instruction counts): "load", "env", or null. The run then declines. */
    public String stopAfter;

    /** Print the failing declaration and the reason to stderr (one line; bounded). */
    public boolean explain = true;

    // ---- additions of work package D2 ---------------------------------------------------------------

    /**
     * Number of declaration units the reader counted, or -1 if the caller has no independent count. When
     * set, the run is accepted only if the store holds exactly this many units (ARCHITECTURE.md 8.1).
     */
    public int expectedUnits = -1;

    /** Step limit of a warm attempt. Unlimited by RECONCILIATION.md section 3. */
    public long warmSteps = Budget.UNLIMITED;

    /**
     * Bytes of session memory one unit may add in a warm attempt before it is retried cold with the
     * whole memory limit. 0 = automatic: the whole headroom,
     * {@code max(256 MiB, memoryLimitBytes - shared store bytes)}: a unit is cut short only when the
     * run as a whole passes the limit, and then the thread that holds the most gives way
     * ({@link MemoryAccount}); a smaller budget only makes units run twice (measured: two units of
     * mathlib and one of con-leche with the former share of {@code headroom / (2 * workers)}). The
     * verdict does not depend on it, because a warm decline is never final.
     */
    public long unitBytes = 0;

    /**
     * Give-back after a heavy declaration: a lane (worker context) whose allocated but unused
     * capacity grew by more than this many bytes since its last reset is reset after the unit, which
     * returns its arrays to the collector. 0 = never. Never affects the verdict.
     */
    public long keepBytes = 256L << 20;

    /**
     * Ask the runtime for a collection ({@code System.gc()}) when the heap holds this many bytes more
     * than the memory account knows of (measured against what was left unaccounted after the previous
     * collection); 0 = never. Arrays a heavy declaration left behind and tables the engines dropped
     * are otherwise only reclaimed when the heap is full, which is what the peak resident set then
     * shows: with the launcher's 13 GB heap, 13 GB. It trades CPU time and wall clock (a full
     * collection stops every worker) for resident memory; see the measurements in the module README.
     * It should be well above the runtime's young generation.
     */
    public long gcAfterBytes = 0;

    /**
     * Depth limit of a warm attempt, for engines with host recursion. Unlimited by default: workers
     * have deep stacks, and a limit here only makes a deep unit run twice (warm, then cold).
     */
    public int warmDepth = Integer.MAX_VALUE;

    /** Units the environment thread admits between two publications to the scheduler (multi-worker runs). */
    public int envBatchUnits = 4096;

    /**
     * A unit that runs longer than this many nanoseconds makes its worker hand the unstarted rest of
     * its claim back to the scheduler: while the unit is still running, at the first poll of the
     * session after that time ({@code RunSignals.memoryExhausted()}; engines poll every few thousand
     * steps), and at the latest when the unit has ended. 0 = never. Affects who checks a unit, never
     * the verdict.
     */
    public long spillNanos = 20_000_000L;

    /**
     * Check the export this many times in the same process ({@code -X passes=N}; owner note 12: the
     * target is warm wall clock). Pass 1 is the run itself: load and check, and its verdict is the
     * verdict of the run. Each later pass checks every def / thm / axiom / opaque unit of the loaded
     * store again, with new engines, new sessions and a new verdict board (the outcomes of
     * inductive blocks and quot records are the ones the environment pass of pass 1 recorded), and
     * only reports: one line per pass on the diagnostics stream (verdict, wall clock, CPU time) and
     * {@code driver.pass.K.*} in the statistics. Later passes run only when pass 1 accepted the
     * export. 1 = no later pass; nothing else in a run depends on this setting.
     */
    public int passes = 1;

    /** Print WARM-ANOMALY and MACHINE-INCOMPLETE events to stderr (at most 8 lines per run). */
    public boolean logAnomalies = true;

    // ---- settings by name (the launcher's -X KEY=VALUE) ---------------------------------------------

    private static final String[] KEYS = {
        "unit-mib", "keep-mib", "gc-after-mib", "chunk", "env-batch", "spill-ms", "publish-units", "publish-mib",
        "pipeline-min-mib", "no-fallback", "passes"
    };

    /** Environment variable with driver settings for experiments: {@code key=value[,key=value]...}. */
    public static final String ENV = "RLVM_DRIVER";

    /**
     * Applies a list of settings, {@code key=value[,key=value]...} (the format of the environment
     * variable {@link #ENV}, which {@link Run} reads for measurements without a rebuilt launcher).
     *
     * @throws IllegalArgumentException as {@link #set(String, String)}; settings before the faulty
     *     one stay applied
     */
    public void setAll(String list) {
        if (list == null) {
            return;
        }
        for (String item : list.split(",")) {
            String s = item.trim();
            if (s.isEmpty()) {
                continue;
            }
            int eq = s.indexOf('=');
            set(eq < 0 ? s : s.substring(0, eq).trim(), eq < 0 ? null : s.substring(eq + 1).trim());
        }
    }

    /** The driver's own settings that {@link #set(String, String)} understands. */
    public static String[] keys() {
        return KEYS.clone();
    }

    /** True if {@code key} is one of the driver's own settings (see {@link #set(String, String)}). */
    public static boolean knows(String key) {
        for (String k : KEYS) {
            if (k.equals(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sets one of the driver's own settings from text, so that the launcher needs no code per knob:
     * <pre>
     * unit-mib=N          warm byte budget of a unit in MiB (0 = automatic)            {@link #unitBytes}
     * keep-mib=N          give-back threshold of a lane in MiB (0 = never)             {@link #keepBytes}
     * gc-after-mib=N      collect when the heap holds N MiB of unaccounted bytes (0 = never)  {@link #gcAfterBytes}
     * chunk=N             units per claim                                              {@link #chunkUnits}
     * env-batch=N         units per publication of the environment thread             {@link #envBatchUnits}
     * spill-ms=N          a unit running longer than this returns the rest of its claim (0 = never)  {@link #spillNanos}
     * publish-units=N     pipelined loading: publish every N units                     {@link #publishEveryUnits}
     * publish-mib=N       pipelined loading: publish every N MiB of input              {@link #publishEveryBytes}
     * pipeline-min-mib=N  inputs below N MiB are loaded in batch mode                  {@link #pipelineMinBytes}
     * passes=N            check the loaded export N times in one process; the verdict is pass 1's  {@link #passes}
     * no-fallback         the reference kernel does not decide what the primary engine declines
     *                     (a flag: no value, or true / false)                          {@link #referenceFallback}
     * </pre>
     * None of the numeric settings can change a verdict; {@code no-fallback} can only turn an ACCEPT
     * into a DECLINE.
     *
     * @throws IllegalArgumentException with a message for the user: unknown key, missing or malformed
     *     value, value out of range
     */
    public void set(String key, String value) {
        if (!knows(key)) {
            throw new IllegalArgumentException("unknown driver setting '" + key + "' (known: " + String.join(", ", KEYS) + ")");
        }
        if (key.equals("no-fallback")) {
            if (value == null || value.isEmpty() || value.equals("true") || value.equals("1")) {
                referenceFallback = false;
            } else if (value.equals("false") || value.equals("0")) {
                referenceFallback = true;
            } else {
                throw new IllegalArgumentException("no-fallback=" + value + ": expected no value, true or false");
            }
            return;
        }
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(key + " needs a value (" + key + "=N)");
        }
        long n;
        try {
            n = Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + "=" + value + ": not a number");
        }
        long max = key.endsWith("-mib") ? 1L << 24 : key.equals("spill-ms") ? 3_600_000L : 1L << 30;
        long min = key.equals("chunk") || key.equals("env-batch") || key.equals("publish-units")
                || key.equals("publish-mib") || key.equals("passes") ? 1 : 0;
        if (key.equals("passes")) {
            max = 1000;
        }
        if (n < min || n > max) {
            throw new IllegalArgumentException(key + "=" + value + ": out of range [" + min + ", " + max + "]");
        }
        switch (key) {
            case "unit-mib":
                unitBytes = n << 20;
                break;
            case "keep-mib":
                keepBytes = n << 20;
                break;
            case "gc-after-mib":
                gcAfterBytes = n << 20;
                break;
            case "chunk":
                chunkUnits = (int) n;
                break;
            case "env-batch":
                envBatchUnits = (int) n;
                break;
            case "spill-ms":
                spillNanos = n * 1_000_000L;
                break;
            case "publish-units":
                publishEveryUnits = (int) n;
                break;
            case "publish-mib":
                publishEveryBytes = n << 20;
                break;
            case "pipeline-min-mib":
                pipelineMinBytes = n << 20;
                break;
            case "passes":
                passes = (int) n;
                break;
            default:
                throw new IllegalArgumentException("unknown driver setting '" + key + "'");
        }
    }
}
