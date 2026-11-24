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
 * engine=NAME            reference | machine | diff | any registered EngineProvider name
 *                        (default: the non-reference engine with the highest priority, else reference)
 * load=batch|pipelined   default batch (pipelined loading is deferred; the driver refuses it for now)
 * order=file|namespace   order of ready units (default file)
 * session-mib=N          session reset threshold in MiB (default: the driver's, 16)
 * memory-mib=N           memory account limit in MiB above which the run declines (default: the driver's)
 * scan-threads=K         helper threads of the reader (default 0 = none; deferred in the loader)
 * io=buffered|mapped|auto  how the reader brings the file into memory (default buffered: plain read)
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

    /** {@code -X engine=...}, or null for the default engine. */
    String engine;
    boolean differential;
    LoadOptions.Mode loadMode = LoadOptions.Mode.BATCH;
    OrderPolicy.Kind order;
    long sessionBytes = -1;
    long memoryLimitBytes = -1;
    int scanThreads = 0;
    ExportReader.Io io = ExportReader.Io.BUFFERED;
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
            return o;
        }
        // Sorted, so that the first error reported does not depend on the map's iteration order.
        for (Map.Entry<String, String> e : new TreeMap<>(extra).entrySet()) {
            o.set(e.getKey(), e.getValue());
        }
        return o;
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
            case "load":
                loadMode = choose(key, value, "batch", LoadOptions.Mode.BATCH, "pipelined", LoadOptions.Mode.PIPELINED);
                break;
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
                scanThreads = (int) number(key, value, 0, 64);
                break;
            case "io": {
                String v = need(key, value);
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
                        : "=" + value) + " (known: engine, load, order, session-mib, memory-mib, scan-threads, io, "
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
