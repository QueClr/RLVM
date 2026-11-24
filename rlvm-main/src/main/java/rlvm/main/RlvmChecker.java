package rlvm.main;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import rlvm.api.EngineFactory;
import rlvm.api.EngineProvider;
import rlvm.api.Outcome;
import rlvm.cli.Checker;
import rlvm.cli.Options;
import rlvm.cli.UsageException;
import rlvm.cli.Verdict;
import rlvm.driver.Run;
import rlvm.driver.RunConfig;

/**
 * The RLVM kernel behind the launcher's {@link Checker} interface: launcher options to
 * {@link RunConfig}, engine lookup ({@link Engines}), reader + loader + admission + driver
 * ({@link MainWiring}, {@code rlvm.driver.Run}), outcome to {@link Verdict}.
 *
 * <p>Contract inherited from the launcher: return ACCEPT only after the whole export was processed;
 * never catch {@link Error} to carry on (an {@code Error} on any thread ends the process with status
 * 3); never call {@code System.exit}; create threads with explicit stack sizes.
 *
 * <p>Fail closed: {@link Verdict#ACCEPT} is returned in exactly one place, for the driver's exact
 * {@link Outcome#ACCEPT}; any other number is an internal error. A part of the pipeline that is not
 * implemented yet ({@link UnsupportedOperationException}) makes the run DECLINE with the message on
 * stderr.
 */
public final class RlvmChecker implements Checker {

    public RlvmChecker() {}

    /** {@code --checker rlvm}. */
    @Override
    public String name() {
        return "rlvm";
    }

    @Override
    public String description() {
        String engines;
        try {
            engines = Engines.names(Engines.providers());
        } catch (RuntimeException | java.util.ServiceConfigurationError e) {
            engines = "unavailable (" + e + ")";
        }
        return "RLVM kernel: reader, loader, environment pass, workers; engines: " + engines + " (* = reference)";
    }

    /** Above the launcher's placeholder; the highest priority is the default checker. */
    @Override
    public int priority() {
        return 100;
    }

    @Override
    public Verdict check(Path export, Options options) throws Exception {
        int outcome;
        try {
            outcome = run(export, options.threads(), options.stats(), options.stackBytes(), options.extra(),
                    options.err(), options.quiet());
        } catch (UsageError e) {
            // A setting this checker does not know (-X key, engine name): the launcher's status 64.
            // Every UsageError is thrown while the run is being configured, before the export is opened.
            throw new UsageException(e.getMessage());
        }
        switch (outcome) {
            case Outcome.ACCEPT:
                return Verdict.ACCEPT;
            case Outcome.REJECT:
                return Verdict.REJECT;
            case Outcome.DECLINE:
                return Verdict.DECLINE;
            default:
                throw new IllegalStateException("driver returned the invalid outcome " + outcome);
        }
    }

    /**
     * Checks one export.
     *
     * @param export the NDJSON file
     * @param threads requested worker threads, 0 = automatic
     * @param stats print statistics when done
     * @param stackBytes stack size the launcher was asked to use for deep threads
     * @param extra the launcher's {@code -X key=value} settings (see {@link #configure})
     * @return an {@code rlvm.api.Outcome} code: ACCEPT, REJECT or DECLINE
     */
    public int checkExport(Path export, int threads, boolean stats, long stackBytes, Map<String, String> extra) {
        return run(export, threads, stats, stackBytes, extra, System.err, false);
    }

    private int run(Path export, int threads, boolean stats, long stackBytes, Map<String, String> extra,
            PrintStream err, boolean quiet) {
        // One ServiceLoader pass, so that "the primary engine is the reference engine" is an identity.
        java.util.List<EngineProvider> all = Engines.providers();
        // Everything from here to the construction of the run can end in a UsageError (status 64):
        // nothing has been read yet.
        java.util.Set<String> engineKeys = Engines.optionKeys(all);
        MainOptions mo = MainOptions.parse(extra, engineKeys, DRIVER);
        RunConfig config = configure(export, threads, stats, stackBytes, mo);
        EngineProvider reference = Engines.reference(all);
        EngineFactory referenceFactory = Engines.factory(reference, mo.engineOptions);
        EngineProvider primary;
        EngineFactory primaryFactory;
        try {
            if (mo.differential) {
                EngineProvider diff = Engines.find(Engines.DIFF, all);
                if (diff != null) {
                    // The test kit's differential engine (its spi directory and jar are on the class
                    // path): it runs the engine under test and the oracle itself, so the driver's own
                    // differential mode stays off (the reference kernel would run a second time).
                    primary = diff;
                    config.differential = false;
                } else {
                    // The driver's differential mode: the reference kernel beside the engine under test.
                    primary = Engines.underTest(all);
                    config.differential = true;
                }
                primaryFactory = Engines.factory(primary, mo.engineOptions);
            } else {
                primary = Engines.primary(mo.engine, all);
                // The driver recognises "the reference kernel is the primary engine" by the SAME factory
                // object and then confirms a rejection with one fresh cold run instead of two.
                primaryFactory = primary == reference ? referenceFactory : Engines.factory(primary, mo.engineOptions);
            }
        } catch (UnsupportedOperationException e) {
            return declineNotImplemented(e, err, quiet);
        }
        // The primary engine's own run defaults (EngineProvider.runDefaults, e.g. the machine's session
        // cap): applied under the command line, so an explicit -X KEY=VALUE wins.
        Map<String, String> preferred = Engines.runDefaults(primary, mo.engineOptions, engineKeys);
        if (!preferred.isEmpty()) {
            boolean driverDifferential = config.differential;
            mo = withRunDefaults(mo, primary, preferred, extra, engineKeys, DRIVER);
            config = configure(export, threads, stats, stackBytes, mo);
            config.differential = driverDifferential;
        }
        config.explain = !quiet;
        MainWiring wiring = new MainWiring(export, mo);
        long t0 = System.nanoTime();
        int outcome;
        Run run;
        try {
            run = Pipeline.newRun(config, wiring, primaryFactory, referenceFactory, quiet ? null : err);
            outcome = run.execute();
        } catch (UnsupportedOperationException e) {
            return declineNotImplemented(e, err, quiet);
        }
        if (outcome != Outcome.ACCEPT && !quiet && run.verdicts().explanation() == null) {
            // The driver explains every failure it knows the cause of; never end silently otherwise.
            err.println("rlvm: " + Outcome.name(outcome) + " (no explanation recorded)");
        }
        if (stats) {
            err.println("rlvm: stats: outcome=" + Outcome.name(outcome) + " engine=" + primary.name()
                    + " reference=" + reference.name() + " workers=" + run.workerCount()
                    + (config.workers == 0 ? "(auto)" : "") + " load="
                    + config.loadMode.name().toLowerCase(java.util.Locale.ROOT)
                    + (mo.appliedRunDefaults.isEmpty() ? "" : " engine-defaults=" + mo.appliedRunDefaults)
                    + " wall_ms=" + (System.nanoTime() - t0) / 1_000_000);
        }
        return outcome;
    }

    /**
     * The run's settings with the primary engine's preferred defaults under them: the defaults as if
     * typed first, every {@code -X} of the command line after them (the command line wins).
     *
     * @param parsed the settings of the command line alone (returned unchanged when nothing is preferred)
     * @param preferred {@link Engines#runDefaults} of the primary engine
     * @throws IllegalStateException the provider prefers a setting the checker refuses (unknown key,
     *     malformed value): the provider is broken; internal error, not a usage error of the caller
     */
    static MainOptions withRunDefaults(MainOptions parsed, EngineProvider primary, Map<String, String> preferred,
            Map<String, String> extra, java.util.Set<String> engineKeys, MainOptions.DriverSettings driver) {
        if (preferred == null || preferred.isEmpty()) {
            return parsed;
        }
        try {
            // The defaults alone must be acceptable, values included (the driver parses its own in applyTo).
            MainOptions.parse(preferred, engineKeys, driver).applyTo(new RunConfig());
        } catch (UsageError e) {
            throw new IllegalStateException("engine provider '" + primary.name() + "' ("
                    + primary.getClass().getName() + ") prefers a run setting the checker refuses: " + e.getMessage());
        }
        Map<String, String> merged = new java.util.LinkedHashMap<>(preferred);
        if (extra != null) {
            merged.putAll(extra);
        }
        MainOptions mo = MainOptions.parse(merged, engineKeys, driver);
        for (Map.Entry<String, String> e : preferred.entrySet()) {
            if (extra == null || !extra.containsKey(e.getKey())) {
                mo.appliedRunDefaults.put(e.getKey(), e.getValue());
            }
        }
        return mo;
    }

    /**
     * A deferred or not yet published part of the pipeline was reached: never a verdict about the
     * input. The message names where, so that the owner of the missing part can be found.
     */
    private static int declineNotImplemented(UnsupportedOperationException e, PrintStream err, boolean quiet) {
        if (!quiet) {
            StackTraceElement[] st = e.getStackTrace();
            String where = st.length > 0 ? " (at " + st[0].getClassName() + "." + st[0].getMethodName() + ":"
                    + st[0].getLineNumber() + ")" : "";
            err.println("rlvm: declined: not implemented: " + e.getMessage() + where);
        }
        return Outcome.DECLINE;
    }

    /**
     * Builds the run configuration. Recognised {@code -X} keys: {@code engine} (machine | reference |
     * diff), {@code load} (pipelined | batch), {@code order} (file | namespace), {@code session-mib},
     * {@code memory-mib}, {@code scan-threads}, {@code io}, {@code stop-after} (load | env),
     * {@code stats-file}, {@code no-recheck}, {@code no-truffle}, {@code profile} (4.36 | 4.37),
     * {@code no-proof-blocks}; {@code engine.*} keys are kept for the Truffle tier; every key an engine
     * on the class path declares ({@code EngineProvider.optionKeys()}) goes to the engines. An unknown
     * key is a usage error ({@link UsageError}), never ignored.
     */
    public RunConfig configure(Path export, int threads, boolean stats, long stackBytes, Map<String, String> extra) {
        return configure(export, threads, stats, stackBytes,
                MainOptions.parse(extra, Engines.optionKeys(Engines.providers()), DRIVER));
    }

    /**
     * The driver's own settings by name ({@code -X unit-mib=N}, {@code -X publish-units=N},
     * {@code -X no-fallback}, ...; the list is {@code RunConfig.keys()}): the driver says which keys it
     * knows and parses the values, so a new knob of the driver needs no change here. A bare
     * {@code -X key} arrives from the launcher with the value "true", which the driver's flags accept.
     */
    static final MainOptions.DriverSettings DRIVER = new MainOptions.DriverSettings() {
        @Override
        public boolean knows(String key) {
            return RunConfig.knows(key);
        }

        @Override
        public void set(RunConfig config, String key, String value) {
            config.set(key, value);
        }
    };

    private static RunConfig configure(Path export, int threads, boolean stats, long stackBytes, MainOptions mo) {
        if (threads < 0) {
            throw new UsageError("--threads " + threads + ": must be 0 (automatic) or positive");
        }
        RunConfig config = new RunConfig();
        config.input = export;
        // 0 = automatic: the driver decides (Run.resolveWorkers: max(1, min(processors, 8) - 2), capped
        // by the number of units, ARCHITECTURE.md 6.8). An explicit -j N is passed on as given.
        config.workers = threads;
        if (stackBytes > 0) {
            // --stack-size is the stack of the threads that run the reference kernel's recursion.
            config.deepStackBytes = stackBytes;
            config.workerStackBytes = Math.min(config.workerStackBytes, stackBytes);
        }
        mo.applyTo(config);
        return config;
    }

    /** Size of the export in bytes, 0 if unknown (a pipe, an unreadable file). */
    static long inputBytes(Path export) {
        try {
            return Files.isRegularFile(export) ? Files.size(export) : 0L;
        } catch (java.io.IOException e) {
            return 0L;
        }
    }
}
