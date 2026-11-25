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
import rlvm.driver.RunStats;
import rlvm.load.LoadReport;

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
 *
 * <p>{@code -X passes=N} (N &gt; 1) checks the export N times in this process and reports every pass
 * ({@link Passes}: warm wall clock); without it nothing of that class runs.
 *
 * <p><b>The safety net of the practical policy</b> ({@link #strictRecheck}; OWNER-NOTES 13 (c): the
 * default never rejects what the official checker accepts). The practical loader drops metadata
 * nodes, which the official kernel keeps as terms of their own, and some of its syntactic tests look
 * at such a node, not through it (finding R2-F4: {@code cheap_beta_reduce} behind a node). So a
 * REJECT of a practical run on a file in which the loader dropped a metadata node is not reported:
 * the file is checked once more under {@code policy=strict}, where such a declaration is declined
 * instead of normalised, and THAT verdict is the run's. An ACCEPT or a DECLINE of the practical run
 * and every file without a metadata record are untouched.
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
        return "RLVM kernel: reader, loader, environment pass, workers; engines: " + engines + " (* = reference)"
                + defaultEngineNote();
    }

    /**
     * "; default engine: NAME (policy practical; ...)" for {@code --version}. The words
     * "default engine: NAME" are read by scripts/build.sh, which gives the JVM launcher the heap of
     * the engine a run without {@code -X engine} checks with: keep them.
     */
    private static String defaultEngineNote() {
        try {
            return "; default engine: " + Engines.primary(null, Engines.providers()).name()
                    + " (policy practical; -X policy=strict = the reference engine)";
        } catch (RuntimeException | java.util.ServiceConfigurationError e) {
            return "";
        }
    }

    /**
     * The checker's settings for {@code rlvm --help} (the complete list: rlvm-main/README.md):
     * {@code rlvm.cli.Checker.usage()} of rlvm-cli. Deliberately without {@code @Override}: this
     * module is compiled from the working tree into every distribution anybody builds
     * ({@code scripts/dev.sh dist}), against the launcher jar that is published at that moment, and
     * must compile against the one without the method too (it is then simply not called).
     */
    public String usage() {
        return String.join("\n",
                "  policy=practical|strict   what a run promises (default practical). practical: the official verdict on",
                "                            every real input and never an accept of a statement that is not derivable;",
                "                            a derivable statement that the official checker rejects only through its",
                "                            own implementation (cache order, object identity) may be accepted.",
                "                            strict: the reference engine and every exactness-only check, as close to",
                "                            the official checker as RLVM gets (slower)",
                "  engine=NAME               reference | machine | diff (default: by the engines' priorities, see",
                "                            --version; a rejection is always confirmed by the reference engine)",
                "  load=auto|batch|pipelined how the file is loaded (default auto: pipelined with checking when two or",
                "                            more workers run and the file has 8 MiB or more)",
                "  scan-threads=K|auto, io=buffered|mapped|auto",
                "                            the reader (default: 1 scanner thread on mapped input from 256 MiB)",
                "  passes=N, passes-mode=reload|recheck",
                "                            check the export N times in this process and report every pass",
                "  stats-file=F, stop-after=load|env, session-mib=N, memory-mib=N, profile=4.36|4.37, no-recheck, ...",
                "                            and the engines' and the driver's own settings: rlvm-main/README.md",
                "");
    }

    /**
     * What a run without any option does, for {@code rlvm --version} ({@code rlvm.cli.Checker.defaults()};
     * without {@code @Override} for the same reason as {@link #usage()}): the engine by priority, the
     * policy, the automatic worker count and the load rule, from the constants that decide them, and
     * the default engine's own defaults as it states them ({@code EngineProvider.defaultsNote()}). The
     * release is one executable whose plain run is the fastest configuration (OWNER-NOTES 16); this is
     * the line that says which one that is.
     */
    public String defaults() {
        String engine;
        try {
            EngineProvider primary = Engines.primary(null, Engines.providers());
            engine = primary.name();
            // The engine's own defaults in its own words (the machine: its switch set), if it says any.
            String note = primary.defaultsNote();
            if (note != null && !note.isBlank()) {
                engine += " (" + note.strip() + ")";
            }
        } catch (RuntimeException | java.util.ServiceConfigurationError e) {
            engine = "unavailable";
        }
        long pipelineMib = new RunConfig().pipelineMinBytes >> 20;
        return "engine " + engine + ", policy practical; one worker per CPU, at most " + AUTO_MAX_WORKERS
                + "; a file of " + pipelineMib + " MiB or more is checked while it is read (pipelined), with "
                + MainOptions.DEFAULT_SCAN_THREADS + (MainOptions.DEFAULT_SCAN_THREADS == 1 ? " scanner thread from " : " scanner threads from ")
                + (MainOptions.SCAN_THREADS_MIN_BYTES >> 20) + " MiB";
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
        Setup s = setup(export, threads, stats, stackBytes, extra, all, err, quiet);
        if (s == null) {
            return Outcome.DECLINE; // a part of the pipeline is not implemented; said on the diagnostics stream
        }
        // The safety net exists for the practical policy only: a strict run is its own last word.
        final boolean practical = !s.mo.strict;
        if (s.mo.passes > 1) {
            // -X passes=N: the same export N times in this process (warm passes). Never on a plain run.
            Passes passes = new Passes(this, export, threads, stats, stackBytes, extra, all, err, quiet);
            long t0 = System.nanoTime();
            int outcome = passes.run(s);
            s = null;
            long dropped = passes.droppedMetadata();
            passes = null;
            if (practical && outcome == Outcome.REJECT && dropped != 0) {
                return strictRecheck(export, threads, stats, stackBytes, extra, all, err, quiet, dropped,
                        System.nanoTime() - t0, null);
            }
            return outcome;
        }
        // The driver prints its lines (anomaly events, the explanation of a failure) when the run ends.
        // Those of a practical run are held until it is known whether the verdict they explain is the
        // one this run reports; everything else goes straight to the diagnostics stream.
        java.io.ByteArrayOutputStream held = practical && !quiet ? new java.io.ByteArrayOutputStream() : null;
        PrintStream diagnostics = quiet ? null
                : held == null ? err : new PrintStream(held, true, java.nio.charset.StandardCharsets.UTF_8);
        Done d;
        try {
            d = once(s, export, diagnostics, err, quiet);
        } catch (Throwable t) {
            release(held, err); // an Error ends the process: what the driver said is not lost
            throw t;
        }
        if (d.notImplemented) {
            release(held, err);
            return Outcome.DECLINE;
        }
        int outcome = d.outcome;
        long dropped = droppedMetadata(d.report);
        if (practical && outcome == Outcome.REJECT && dropped != 0) {
            long nanos = d.nanos;
            d = null; // nothing of the practical run is referenced while the strict one runs
            s = null;
            String explanation = held == null ? null : held.toString(java.nio.charset.StandardCharsets.UTF_8);
            return strictRecheck(export, threads, stats, stackBytes, extra, all, err, quiet, dropped, nanos, explanation);
        }
        release(held, err);
        if (stats) {
            err.println(statsLine(s, outcome, d.run.workerCount(), d.nanos));
        }
        return outcome;
    }

    /** What {@link #once} leaves: the outcome, the run, the loader's counters, the wall clock. */
    private static final class Done {
        int outcome;
        /** Null if a part of the pipeline was not implemented ({@link #notImplemented}). */
        Run run;
        boolean notImplemented;
        /** The loader's report; null if the load never got as far as a loader. */
        LoadReport report;
        long nanos;
    }

    /**
     * One complete run of a setup: store, load, environment pass, workers.
     *
     * @param diagnostics where the driver's lines go; null = nowhere ({@code --quiet})
     */
    private static Done once(Setup s, Path export, PrintStream diagnostics, PrintStream err, boolean quiet) {
        Done d = new Done();
        MainWiring wiring = new MainWiring(export, s.mo);
        long t0 = System.nanoTime();
        try {
            d.run = Pipeline.newRun(s.config, wiring, s.primaryFactory, s.referenceFactory, diagnostics);
            d.outcome = d.run.execute();
        } catch (UnsupportedOperationException e) {
            d.run = null;
            d.notImplemented = true;
            d.outcome = declineNotImplemented(e, err, quiet);
            return d;
        }
        d.nanos = System.nanoTime() - t0;
        d.report = wiring.loadReport();
        if (d.outcome != Outcome.ACCEPT && diagnostics != null && d.run.verdicts().explanation() == null) {
            // The driver explains every failure it knows the cause of; never end silently otherwise.
            diagnostics.println("rlvm: " + Outcome.name(d.outcome) + " (no explanation recorded)");
        }
        return d;
    }

    /** Prints what was held back of a run whose verdict is reported after all. */
    private static void release(java.io.ByteArrayOutputStream held, PrintStream err) {
        if (held != null && held.size() > 0) {
            err.print(held.toString(java.nio.charset.StandardCharsets.UTF_8));
            err.flush();
            held.reset();
        }
    }

    /**
     * The number of metadata records the loader read and dropped (the wrapped expression stands for
     * such a record; {@code LoadReport.exprMData}), counted up to the record at which the load ended;
     * -1 if there is no report, which the safety net takes for "some" (fail closed).
     *
     * <p>The count is of RECORDS READ, whatever became of them: a superset of "a declaration was
     * loaded without its node". The surplus (a node in an inductive record, which both policies
     * decline; a record that no declaration uses) costs a second run and no verdict: the strict load
     * treats those records as the practical one does. A record that the load never reached - a
     * pipelined run that ended at a rejection before it - cannot be part of the rejected declaration,
     * whose records all precede its line.
     */
    static long droppedMetadata(LoadReport report) {
        return report == null ? -1 : report.exprMData;
    }

    /** The settings of the command line that the second run of the safety net does not take over. */
    private static final java.util.Set<String> NOT_IN_RECHECK = java.util.Set.of("policy", "engine", "passes", "passes-mode");

    /**
     * The settings of the safety net's second run: the command line's, with {@code policy=strict} and
     * without what contradicts or weakens it - the engine (strict IS the reference engine), the
     * passes (one plain run) and the exactness-only engine settings ({@link MainOptions#STRICT_ENGINE_SETTINGS}:
     * a value given for the practical run must not switch the strict one's exactness off).
     */
    static Map<String, String> recheckSettings(Map<String, String> extra) {
        Map<String, String> again = new java.util.LinkedHashMap<>();
        if (extra != null) {
            for (Map.Entry<String, String> e : extra.entrySet()) {
                if (!NOT_IN_RECHECK.contains(e.getKey()) && !MainOptions.STRICT_ENGINE_SETTINGS.containsKey(e.getKey())) {
                    again.put(e.getKey(), e.getValue());
                }
            }
        }
        again.put("policy", MainOptions.STRICT);
        return again;
    }

    /**
     * THE SAFETY NET (class comment). The practical run ended with REJECT and the loader had dropped
     * metadata: the file is checked again, as a plain run with fresh stores, under the strict policy
     * (the reference engine, its exactness-only checks, the loader's metadata decline), and the
     * outcome of that run is returned - accept, reject or decline.
     *
     * <p>Why that closes the class: a wrong reject of this checker on such a file now needs a wrong
     * reject of the strict mode, which never normalises a metadata node away. The price: a correct
     * reject becomes a decline wherever the strict load declines a declaration with a node before
     * the rejected one is reached. No accepted file and no file without a metadata record ever comes
     * here.
     *
     * @param dropped metadata records the practical load dropped; -1 = unknown
     * @param practicalNanos wall clock of the practical run
     * @param explanation what the driver said about the practical run (held back), or null
     */
    private int strictRecheck(Path export, int threads, boolean stats, long stackBytes, Map<String, String> extra,
            java.util.List<EngineProvider> all, PrintStream err, boolean quiet, long dropped, long practicalNanos,
            String explanation) {
        String count = dropped < 0 ? "an unknown number of metadata nodes" : dropped == 1 ? "1 metadata node"
                : dropped + " metadata nodes";
        if (!quiet) {
            err.println("rlvm: the practical check ended with reject on a file in which the loader dropped " + count
                    + " (the official checker keeps such a node as part of the term): that verdict is NOT REPORTED, "
                    + "the file is checked again under -X policy=strict");
            if (explanation != null) {
                for (String line : explanation.split("\\R")) {
                    if (!line.isBlank()) {
                        err.println("rlvm:   practical, not reported: " + (line.startsWith("rlvm: ") ? line.substring(6) : line));
                    }
                }
            }
        }
        // The practical run's stores are garbage now; collected here, not during the second run.
        System.gc();
        Setup s = setup(export, threads, stats, stackBytes, recheckSettings(extra), all, err, quiet);
        if (s == null) {
            return Outcome.DECLINE;
        }
        Done d = once(s, export, quiet ? null : err, err, quiet);
        if (d.notImplemented) {
            return Outcome.DECLINE;
        }
        String net = "strict-recheck(practical=reject,metadata=" + dropped + ",practical_wall_ms="
                + practicalNanos / 1_000_000 + ")";
        RunStats file = d.run.stats();
        if (s.mo.statsFile != null && file != null && file.enabled()) {
            // The statistics file is the second run's (the driver wrote it); it says that it is one.
            file.put("net", net);
            try {
                file.writeJson(s.mo.statsFile);
            } catch (java.io.IOException | RuntimeException e) {
                if (!quiet) {
                    err.println("rlvm: cannot write statistics to " + s.mo.statsFile + ": " + e);
                }
            }
        }
        if (stats) {
            err.println(statsLine(s, d.outcome, d.run.workerCount(), d.nanos) + " net=" + net);
        }
        return d.outcome;
    }

    /** The {@code --stats} line of a run (without the part {@link Passes} appends). */
    static String statsLine(Setup s, int outcome, int workerCount, long wallNanos) {
        // '(auto)' = decided by this checker's defaults, not given: the worker count (--threads auto),
        // the load mode (MainOptions.resolveLoadDefaults), the reader's scanner threads.
        return "rlvm: stats: outcome=" + Outcome.name(outcome) + " engine=" + s.primary.name()
                + " reference=" + s.reference.name() + " policy=" + s.mo.policyName() + strictNote(s.mo)
                + " workers=" + workerCount
                + (s.autoWorkers ? "(auto)" : "") + " load="
                + s.config.loadMode.name().toLowerCase(java.util.Locale.ROOT) + (s.mo.loadGiven ? "" : "(auto)")
                + " scan-threads=" + s.mo.scanThreads + (s.mo.scanThreadsGiven ? "" : "(auto)")
                + " io=" + s.mo.io.name().toLowerCase(java.util.Locale.ROOT)
                + (s.mo.appliedRunDefaults.isEmpty() ? "" : " engine-defaults=" + s.mo.appliedRunDefaults)
                + " wall_ms=" + wallNanos / 1_000_000;
    }

    /**
     * "[ref.exact=official,metadata]" behind {@code policy=strict}: the exactness-only options of the
     * other modules that this run really has (the engines' settings that some provider declares, the
     * loader's metadata decline if the loader has it); "[]" = none exists in these jars, the strict
     * mode is then the reference engine alone. Empty for the practical policy.
     */
    static String strictNote(MainOptions mo) {
        if (!mo.strict) {
            return "";
        }
        java.util.List<String> on = new java.util.ArrayList<>();
        for (Map.Entry<String, String> e : mo.strictEngineSettings.entrySet()) {
            on.add(e.getKey() + "=" + e.getValue());
        }
        if (MainWiring.strictLoadAvailable()) {
            on.add("metadata");
        }
        return "[" + String.join(",", on) + "]";
    }

    /**
     * What a run is made of besides its wiring: the settings, the run configuration, the engines'
     * providers and their factories. Built anew for every run (a run may write into its
     * configuration): once for a plain run, once per pass by {@link Passes}.
     */
    static final class Setup {
        MainOptions mo;
        RunConfig config;
        /** True if the worker count is this checker's automatic one ({@code --threads auto}). */
        boolean autoWorkers;
        EngineProvider primary;
        EngineProvider reference;
        EngineFactory primaryFactory;
        EngineFactory referenceFactory;
    }

    /**
     * Settings, configuration and engine factories of one run.
     *
     * @return null if a part of the pipeline that the settings ask for is not implemented (the run
     *     declines; the reason was printed unless {@code quiet})
     * @throws UsageError a setting this checker does not know or cannot parse: nothing has been read yet
     */
    Setup setup(Path export, int threads, boolean stats, long stackBytes, Map<String, String> extra,
            java.util.List<EngineProvider> all, PrintStream err, boolean quiet) {
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
                primary = mo.strict ? strictEngine(mo, reference, all) : Engines.primary(mo.engine, all);
                // The driver recognises "the reference kernel is the primary engine" by the SAME factory
                // object and then confirms a rejection with one fresh cold run instead of two.
                primaryFactory = primary == reference ? referenceFactory : Engines.factory(primary, mo.engineOptions);
            }
        } catch (UnsupportedOperationException e) {
            declineNotImplemented(e, err, quiet);
            return null;
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
        Setup s = new Setup();
        s.mo = mo;
        s.config = config;
        s.autoWorkers = threads == 0;
        s.primary = primary;
        s.reference = reference;
        s.primaryFactory = primaryFactory;
        s.referenceFactory = referenceFactory;
        return s;
    }

    /**
     * The engine of the strict mode (OWNER-NOTES 13): the reference kernel, whatever the priorities
     * say. Naming it is allowed ({@code -X policy=strict -X engine=reference}); naming another engine
     * contradicts the policy.
     *
     * @throws UsageError {@code -X engine=NAME} names an engine that is not the reference kernel
     */
    static EngineProvider strictEngine(MainOptions mo, EngineProvider reference, java.util.List<EngineProvider> all) {
        if (mo.engine != null && Engines.primary(mo.engine, all) != reference) {
            throw new UsageError("-X policy=strict checks with the reference engine ('" + reference.name()
                    + "'): -X engine=" + mo.engine + " contradicts it (drop one of the two)");
        }
        return reference;
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
    static int declineNotImplemented(UnsupportedOperationException e, PrintStream err, boolean quiet) {
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
        // 0 = automatic: this checker decides (autoWorkers) and passes the count on like an explicit
        // -j N; the driver still caps it by the number of units (one worker per 128).
        int workers = threads == 0 ? autoWorkers(Runtime.getRuntime().availableProcessors()) : threads;
        config.workers = workers;
        if (stackBytes > 0) {
            // --stack-size is the stack of the threads that run the reference kernel's recursion.
            config.deepStackBytes = stackBytes;
            config.workerStackBytes = Math.min(config.workerStackBytes, stackBytes);
        }
        mo.applyTo(config);
        // What the command line left open about the load (mode, scanner threads, I/O) is decided for
        // this input and this worker count; after applyTo, which sets the driver's pipeline-min-mib.
        mo.resolveLoadDefaults(inputBytes(export), workers, config);
        return config;
    }

    /** The most workers {@code --threads auto} ever uses (the launcher's help: "up to 8"). */
    static final int AUTO_MAX_WORKERS = 8;

    /**
     * The worker count of {@code --threads auto} on a machine with {@code processors} CPUs: one worker
     * per CPU, at most {@link #AUTO_MAX_WORKERS}. Decided by wall clock (OWNER-NOTES 12), warm and
     * cold, on 8 cores with the load pipelined and the reader's scanner threads: the measurements are
     * in rlvm-main/README.md ("Automatic worker count") and docs/PLATFORM.md. The driver's own
     * automatic rule (processors - 2, at most 6: by CPU time, wave 4) is no longer consulted.
     */
    static int autoWorkers(int processors) {
        return Math.max(1, Math.min(processors, AUTO_MAX_WORKERS));
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
