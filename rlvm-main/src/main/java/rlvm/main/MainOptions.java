package rlvm.main;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import rlvm.api.EngineConfig;
import rlvm.driver.OrderPolicy;
import rlvm.driver.RunConfig;
import rlvm.export.ExportReader;
import rlvm.load.LoadOptions;

/**
 * The checker's own settings, parsed from the launcher's {@code -X KEY[=VALUE]} map
 * (ARCHITECTURE.md 7.4). Every key is known here or is a usage error; nothing is ignored.
 *
 * <pre>
 * policy=practical|strict  what the run promises (OWNER-NOTES 13; default practical). practical: the default
 *                        engine by priority and none of the exactness-only checks - the official verdict on
 *                        every real input, never an accept of a statement that is not derivable, and a
 *                        derivable statement that the official checker rejects only through its own
 *                        implementation (cache order, non-transitivity, object identity) may be accepted.
 *                        strict: the reference engine (as -X engine=reference; naming another engine or
 *                        engine=diff with it is a usage error) and every exactness-only option of the
 *                        other modules that the jars of the run have ({@link #STRICT_ENGINE_SETTINGS},
 *                        {@code MainWiring.strictLoad})
 * engine=NAME            reference | machine | diff | any registered EngineProvider name
 *                        (default: the non-reference engine with the highest priority, else reference)
 * load=batch|pipelined|auto  how units are published. Default auto: pipelined (the loader publishes while
 *                        workers check; OWNER-NOTES 12) when two or more workers run and the input is at
 *                        least the driver's pipeline-min-mib (8 MiB), else batch ({@link #resolveLoadDefaults})
 * order=file|namespace   order of ready units (default file)
 * session-mib=N          session reset threshold in MiB (default: the driver's, 16)
 * memory-mib=N           memory account limit in MiB above which the run declines (default: the driver's)
 * scan-threads=K|auto    scanner threads of the reader (they tokenise and pack file ranges; mapped input
 *                        only). Default auto: {@link #DEFAULT_SCAN_THREADS} for inputs of
 *                        {@link #SCAN_THREADS_MIN_BYTES} and more, none below
 * io=buffered|mapped|auto  how the reader brings the file into memory. Default: buffered (plain read)
 *                        without scanner threads, auto (mapped if a regular file) with them
 * stop-after=load|env    stop after a phase (per-phase measurements); the verdict is then DECLINE
 * stats-file=F           write statistics as JSON
 * no-recheck             do not re-check a rejection of the primary engine
 * no-truffle             no Truffle tier
 * no-proof-blocks        every record becomes a shared term
 * profile=4.36|4.37      which official kernel release to imitate (default 4.36)
 * passes=N               check the export N times in this process (default 1; {@link Passes}): one line per
 *                        pass on the diagnostics stream; the verdict is the first pass's, and a later pass
 *                        with another verdict makes the run DECLINE
 * passes-mode=reload|recheck  what a later pass is (default reload: a complete run; recheck: the loaded
 *                        store and the engines are kept, every unit is checked again)
 * engine.KEY=VALUE       Truffle engine options, kept for rlvm-truffle
 * KEY=VALUE              any key an engine on the class path declares (rlvm.api.EngineProvider.optionKeys(),
 *                        e.g. the reference kernel's levels=...): handed to the engines of the run
 * KEY[=VALUE]            any key the driver says it knows ({@link DriverSettings}: its own tuning knobs,
 *                        e.g. unit-mib, publish-units, no-fallback): the driver parses the value
 * </pre>
 *
 * <p>The primary engine may prefer other defaults for the wiring's and the driver's keys
 * ({@code EngineProvider.runDefaults}, e.g. the machine's session cap); they are parsed here like
 * settings typed before the command line's, which therefore always win.
 *
 * <p>{@code engine=diff} runs the engine under test and the reference kernel side by side: through the
 * test kit's provider named "diff" when it is on the class path ({@code scripts/dev.sh diff}), else
 * through the driver's own differential mode ({@code RunConfig.differential}).
 */
final class MainOptions {

    /**
     * {@code -X policy=strict}: the strict mode of OWNER-NOTES 13 - the reference engine, kept as close
     * to the official implementation as RLVM can make it. False = {@code practical}, the default.
     */
    boolean strict;
    /** The name the policy has on the command line and in the statistics. */
    static final String PRACTICAL = "practical";
    static final String STRICT = "strict";
    /**
     * The engine setting through which an engine learns the policy of the run: a provider that
     * declares this key ({@code EngineProvider.optionKeys()}) receives {@code policy=strict} or
     * {@code policy=practical} in {@code factory(Map)} on EVERY run, typed or not, and switches its
     * exactness-only behaviour (what exists only for bug-for-bug agreement with the official checker
     * on adversarial files) on in the strict mode. A provider that does not declare it sees nothing.
     * The key itself is the wiring's: no engine can give it another meaning.
     */
    static final String POLICY_KEY = "policy";
    /**
     * The exactness-only settings of the engines, by the names their owners gave them: under
     * {@code policy=strict} each is set to the value here if a provider on the class path declares
     * its key and the command line did not set it (a given setting is never changed); under
     * {@code practical} nothing is passed and each engine's own default (off) holds.
     * {@code ref.exact=official} (rlvm-ref, owner R2): where the official verdict depends on object
     * identity, the reference kernel probes the official second pass and declines the declaration if
     * the two continuations can differ. A jar without the key simply does not have the behaviour yet.
     */
    static final Map<String, String> STRICT_ENGINE_SETTINGS = Map.of("ref.exact", "official");
    /** The exactness-only engine settings this run switched on (strict mode), for the statistics. */
    final Map<String, String> strictEngineSettings = new TreeMap<>();

    /** {@code -X engine=...}, or null for the default engine. */
    String engine;
    boolean differential;
    /**
     * {@code -X load}. Without the setting (or with {@code load=auto}) this is BATCH until
     * {@link #resolveLoadDefaults} has seen the input and the worker count ({@link #loadGiven} false).
     */
    LoadOptions.Mode loadMode = LoadOptions.Mode.BATCH;
    /** True if {@code -X load=batch|pipelined} was given: the default rule then decides nothing. */
    boolean loadGiven;
    OrderPolicy.Kind order;
    long sessionBytes = -1;
    long memoryLimitBytes = -1;
    /** {@code -X scan-threads}; 0 until {@link #resolveLoadDefaults} when not given ({@link #scanThreadsGiven}). */
    int scanThreads = 0;
    boolean scanThreadsGiven;
    /** {@code -X io}; BUFFERED until {@link #resolveLoadDefaults} when not given ({@link #ioGiven}). */
    ExportReader.Io io = ExportReader.Io.BUFFERED;
    boolean ioGiven;

    /**
     * Inputs of at least this size are read with {@link #DEFAULT_SCAN_THREADS} scanner threads by
     * default (proposal of the load path's owner, work/status/requests.log 2026-11-24T14:35Z, with the
     * packed runs of rlvm-load {@code bb5734f612c3}: the load alone of Std 0.92 -> 0.5 s, cedar 1.36 ->
     * 0.73 s, mathlib 9.4 -> 6.0 s with two scanner threads; three are no better than two beside eight
     * workers). Below it the file is read on the loader thread as before: every published arena test
     * and every mutant is far below, so nothing changes for them.
     */
    static final long SCAN_THREADS_MIN_BYTES = 256L << 20;
    /** The default number of scanner threads for inputs of {@link #SCAN_THREADS_MIN_BYTES} and more. */
    static final int DEFAULT_SCAN_THREADS = 2;
    String stopAfter;
    Path statsFile;
    boolean recheck = true;
    boolean truffle = true;
    boolean proofBlocks = true;
    EngineConfig.KernelProfile profile = EngineConfig.KernelProfile.LEAN_4_36;
    /** {@code -X passes=N}: how often the export is checked in this process; 1 = the plain run. */
    int passes = 1;
    /** {@code -X passes-mode=recheck}: later passes keep the loaded store and the engines; false = reload. */
    boolean passesRecheck;
    /** {@code engine.*} keys, for the Truffle tier. */
    final Map<String, String> truffleOptions = new LinkedHashMap<>();
    /**
     * Settings for the engines: every key declared by a provider ({@code EngineProvider.optionKeys()})
     * and the {@code engine.*} keys; passed to {@code EngineProvider.factory(Map)}.
     */
    final Map<String, String> engineOptions = new LinkedHashMap<>();
    /**
     * Run defaults of the primary engine that are in effect ({@code EngineProvider.runDefaults}: the
     * ones the command line did not override), for the statistics line; filled by
     * {@code RlvmChecker.withRunDefaults}.
     */
    final Map<String, String> appliedRunDefaults = new TreeMap<>();
    /** The keys the engines on the class path declare; anything else unknown is a usage error. */
    private Set<String> engineKeys = Set.of();

    /**
     * The driver's own settings by name: which keys it knows and how one is set on a run
     * configuration (rlvm-driver's {@code RunConfig.knows(key)} / {@code config.set(key, value)},
     * request of the driver's owner). The wiring has no code per knob: a key that is neither the
     * wiring's nor an engine's is offered to the driver before it is called a usage error.
     */
    interface DriverSettings {
        /** No driver settings (a driver jar without {@code RunConfig.knows}). */
        DriverSettings NONE = new DriverSettings() {
            @Override
            public boolean knows(String key) {
                return false;
            }

            @Override
            public void set(RunConfig config, String key, String value) {
                throw new IllegalArgumentException("unknown driver setting '" + key + "'");
            }
        };

        boolean knows(String key);

        /** @throws IllegalArgumentException with a message for the user: malformed value, out of range */
        void set(RunConfig config, String key, String value);
    }

    /** {@code -X} settings the driver knows, in sorted key order; applied by {@link #applyTo}. */
    final Map<String, String> driverOptions = new LinkedHashMap<>();
    private DriverSettings driver = DriverSettings.NONE;

    /** No engine settings are known: only the wiring's own keys (and {@code engine.*}) are accepted. */
    static MainOptions parse(Map<String, String> extra) {
        return parse(extra, Set.of());
    }

    /**
     * @param engineKeys the {@code -X} keys declared by the engine providers on the class path
     *     ({@link Engines#optionKeys}); a key that is neither the wiring's nor one of these is a
     *     usage error
     */
    static MainOptions parse(Map<String, String> extra, Set<String> engineKeys) {
        return parse(extra, engineKeys, DriverSettings.NONE);
    }

    /**
     * @param driver the driver's settings by name; a key that is neither the wiring's nor an engine's
     *     and that {@code driver.knows} is kept for {@link #applyTo}
     */
    static MainOptions parse(Map<String, String> extra, Set<String> engineKeys, DriverSettings driver) {
        MainOptions o = new MainOptions();
        o.engineKeys = engineKeys == null ? Set.of() : engineKeys;
        o.driver = driver == null ? DriverSettings.NONE : driver;
        if (extra == null) {
            o.applyPolicy();
            return o;
        }
        // Sorted, so that the first error reported does not depend on the map's iteration order.
        for (Map.Entry<String, String> e : new TreeMap<>(extra).entrySet()) {
            o.set(e.getKey(), e.getValue());
        }
        o.applyPolicy();
        return o;
    }

    /**
     * What the policy means for the other settings, once all of them are read. The strict mode IS
     * the reference engine: comparing engines under it is a contradiction, said as a usage error
     * (that the named engine really is the reference kernel is checked where the providers are
     * known, {@code RlvmChecker.setup}). Engines that declare {@link #POLICY_KEY} are told the policy.
     */
    private void applyPolicy() {
        if (strict && differential) {
            throw new UsageError("-X policy=strict checks with the reference engine alone: -X engine=diff "
                    + "(a comparison of two engines) contradicts it");
        }
        if (engineKeys.contains(POLICY_KEY)) {
            engineOptions.put(POLICY_KEY, policyName());
        }
        if (strict) {
            for (Map.Entry<String, String> e : new TreeMap<>(STRICT_ENGINE_SETTINGS).entrySet()) {
                if (engineKeys.contains(e.getKey()) && !engineOptions.containsKey(e.getKey())) {
                    engineOptions.put(e.getKey(), e.getValue());
                    strictEngineSettings.put(e.getKey(), e.getValue());
                }
            }
        }
    }

    /** {@code "strict"} or {@code "practical"}. */
    String policyName() {
        return strict ? STRICT : PRACTICAL;
    }

    private void set(String key, String value) {
        if (key == null || key.isEmpty()) {
            throw new UsageError("-X needs a key");
        }
        if (key.startsWith("engine.")) {
            truffleOptions.put(key, value == null ? "" : value);
            engineOptions.put(key, value == null ? "" : value);
            return;
        }
        switch (key) {
            case "policy":
                strict = choose(key, value, PRACTICAL, Boolean.FALSE, STRICT, Boolean.TRUE);
                break;
            case "engine": {
                String v = need(key, value);
                if (v.equals("diff")) {
                    differential = true;
                    engine = null;
                } else {
                    differential = false;
                    engine = v;
                }
                break;
            }
            case "load": {
                String v = need(key, value);
                switch (v) {
                    case "batch":
                        loadMode = LoadOptions.Mode.BATCH;
                        loadGiven = true;
                        break;
                    case "pipelined":
                        loadMode = LoadOptions.Mode.PIPELINED;
                        loadGiven = true;
                        break;
                    case "auto":
                        loadMode = LoadOptions.Mode.BATCH; // until resolveLoadDefaults
                        loadGiven = false;
                        break;
                    default:
                        throw new UsageError("-X load=" + v + ": expected batch, pipelined or auto");
                }
                break;
            }
            case "order":
                order = choose(key, value, "file", OrderPolicy.Kind.FILE, "namespace", OrderPolicy.Kind.NAMESPACE_WINDOW);
                break;
            case "session-mib":
                sessionBytes = number(key, value, 1, 1 << 20) << 20;
                break;
            case "memory-mib":
                memoryLimitBytes = number(key, value, 16, 1 << 24) << 20;
                break;
            case "scan-threads":
                if ("auto".equals(value)) {
                    scanThreads = 0;
                    scanThreadsGiven = false;
                    break;
                }
                scanThreads = (int) number(key, value, 0, 64);
                scanThreadsGiven = true;
                break;
            case "io": {
                String v = need(key, value);
                ioGiven = true;
                switch (v) {
                    case "buffered":
                        io = ExportReader.Io.BUFFERED;
                        break;
                    case "mapped":
                        io = ExportReader.Io.MAPPED;
                        break;
                    case "auto":
                        io = ExportReader.Io.AUTO;
                        break;
                    default:
                        throw new UsageError("-X io=" + v + ": expected buffered, mapped or auto");
                }
                break;
            }
            case "stop-after": {
                String v = need(key, value);
                if (!v.equals("load") && !v.equals("env")) {
                    throw new UsageError("-X stop-after=" + v + ": expected load or env");
                }
                stopAfter = v;
                break;
            }
            case "stats-file":
                statsFile = Path.of(need(key, value));
                break;
            case "no-recheck":
                flag(key, value);
                recheck = false;
                break;
            case "no-truffle":
                flag(key, value);
                truffle = false;
                break;
            case "no-proof-blocks":
                flag(key, value);
                proofBlocks = false;
                break;
            case "profile":
                profile = choose(key, value, "4.36", EngineConfig.KernelProfile.LEAN_4_36, "4.37",
                        EngineConfig.KernelProfile.LEAN_4_37);
                break;
            case "passes":
                passes = (int) number(key, value, 1, MAX_PASSES);
                break;
            case "passes-mode":
                passesRecheck = choose(key, value, "reload", Boolean.FALSE, "recheck", Boolean.TRUE);
                break;
            default:
                if (engineKeys.contains(key)) {
                    // An engine's own setting: the engine parses the value (EngineProvider.factory(Map)).
                    engineOptions.put(key, value == null ? "" : value);
                    break;
                }
                if (driver.knows(key)) {
                    // One of the driver's own knobs: the driver parses the value (applyTo).
                    driverOptions.put(key, value);
                    break;
                }
                throw new UsageError("unknown checker setting -X " + key + (value == null || value.isEmpty() ? ""
                        : "=" + value) + " (known: policy, engine, load, order, session-mib, memory-mib, scan-threads, io, "
                        + "stop-after, stats-file, no-recheck, no-truffle, no-proof-blocks, profile, passes, "
                        + "passes-mode, engine.*"
                        + (engineKeys.isEmpty() ? "" : "; engine settings: " + String.join(", ", new TreeSet<>(engineKeys)))
                        + "; and the driver's settings, see rlvm-driver/README.md)");
        }
    }

    /** Upper bound of {@code -X passes}. */
    static final int MAX_PASSES = 1000;

    private static String need(String key, String value) {
        if (value == null || value.isEmpty()) {
            throw new UsageError("-X " + key + " needs a value (-X " + key + "=VALUE)");
        }
        return value;
    }

    private static void flag(String key, String value) {
        if (value != null && !value.isEmpty() && !value.equals("true")) {
            throw new UsageError("-X " + key + " takes no value");
        }
    }

    private static long number(String key, String value, long min, long max) {
        String v = need(key, value);
        long n;
        try {
            n = Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new UsageError("-X " + key + "=" + v + ": not a number");
        }
        if (n < min || n > max) {
            throw new UsageError("-X " + key + "=" + v + ": out of range [" + min + ", " + max + "]");
        }
        return n;
    }

    private static <T> T choose(String key, String value, String a, T va, String b, T vb) {
        String v = need(key, value);
        if (v.equals(a)) {
            return va;
        }
        if (v.equals(b)) {
            return vb;
        }
        throw new UsageError("-X " + key + "=" + v + ": expected " + a + " or " + b);
    }

    /**
     * Decides what the command line (and the primary engine's run defaults) left open about the load,
     * for this input and this many workers, in these settings and in {@code config} (to which
     * {@link #applyTo} was applied before: the driver's {@code pipeline-min-mib} is read from it):
     *
     * <ul>
     * <li>{@code load}: PIPELINED when two or more workers run, the input is a file of at least the
     *     driver's {@code pipelineMinBytes} and no {@code stop-after} phase is asked for - exactly the
     *     runs in which the driver really loads pipelined - and BATCH otherwise. So the store of a
     *     batch run is the one it always was, and nothing changes for one worker or a small file.
     * <li>{@code scan-threads}: {@link #DEFAULT_SCAN_THREADS} for inputs of
     *     {@link #SCAN_THREADS_MIN_BYTES} and more, in either load mode.
     * <li>{@code io}: AUTO (mapped for a regular file; scanner threads only run on mapped input) when
     *     scanner threads are used, whoever asked for them; BUFFERED otherwise.
     * </ul>
     *
     * A setting that was given is never changed. None of this can change a verdict: both load modes
     * build the same store and decide the same units (the driver's parking rule; gates in
     * rlvm-driver/README.md and rlvm-load/README.md).
     *
     * @param inputBytes size of the export, 0 if unknown (not a regular file)
     * @param workers the worker threads the run asks the driver for (at least 1)
     */
    void resolveLoadDefaults(long inputBytes, int workers, RunConfig config) {
        if (!loadGiven) {
            boolean pipelined = workers >= 2 && inputBytes > 0 && inputBytes >= config.pipelineMinBytes
                    && stopAfter == null;
            loadMode = pipelined ? LoadOptions.Mode.PIPELINED : LoadOptions.Mode.BATCH;
        }
        if (!scanThreadsGiven) {
            scanThreads = inputBytes >= SCAN_THREADS_MIN_BYTES ? DEFAULT_SCAN_THREADS : 0;
        }
        if (!ioGiven) {
            io = scanThreads > 0 ? ExportReader.Io.AUTO : ExportReader.Io.BUFFERED;
        }
        config.loadMode = loadMode;
        config.scanThreads = scanThreads;
    }

    /** Copies the settings into a run configuration (fields this class leaves alone keep the driver's defaults). */
    void applyTo(RunConfig config) {
        config.loadMode = loadMode;
        config.scanThreads = scanThreads;
        config.proofBlocks = proofBlocks;
        if (order != null) {
            config.order = order;
        }
        if (sessionBytes > 0) {
            config.sessionBytes = sessionBytes;
        }
        if (memoryLimitBytes > 0) {
            config.memoryLimitBytes = memoryLimitBytes;
        }
        config.profile = profile;
        config.recheckRejections = recheck;
        config.differential = differential;
        config.truffleTier = truffle;
        config.statsFile = statsFile;
        config.stopAfter = stopAfter;
        for (Map.Entry<String, String> e : driverOptions.entrySet()) {
            // The launcher writes a bare '-X key' as key=true; the driver's flags take no value or true.
            try {
                driver.set(config, e.getKey(), e.getValue());
            } catch (UsageError u) {
                throw u;
            } catch (IllegalArgumentException u) {
                throw new UsageError("-X " + u.getMessage());
            }
        }
    }
}
