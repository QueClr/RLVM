package rlvm.testkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import rlvm.api.Budget;
import rlvm.api.Engine;
import rlvm.api.EngineConfig;
import rlvm.api.EngineFactory;
import rlvm.api.KernelOps;
import rlvm.api.Outcome;
import rlvm.api.Reason;
import rlvm.api.Session;
import rlvm.api.StatsSink;
import rlvm.api.WorkerContext;
import rlvm.store.Store;

/**
 * Differential engine: every {@code Session.check} runs the engine under test and the oracle on the
 * same constant and compares the outcomes.
 *
 * <pre>
 * under test   oracle     recorded as                    outcome, DECLINE policy     outcome, FOLLOW_ORACLE policy
 * ACCEPT       ACCEPT     agreement                      ACCEPT                      ACCEPT
 * REJECT       REJECT     agreement                      REJECT (oracle's reason)    REJECT (oracle's reason)
 * ACCEPT       REJECT     SOUNDNESS disagreement         DECLINE(ENGINE_DISAGREEMENT) REJECT (oracle's reason)
 * REJECT       ACCEPT     COMPLETENESS disagreement      DECLINE(ENGINE_DISAGREEMENT) ACCEPT
 * DECLINE      x          decline of the engine under test    x                      x
 * x            DECLINE    decline of the oracle               x                      x
 * DECLINE      DECLINE    both                           DECLINE (reason of the engine under test)
 * </pre>
 * The default policy is {@link Policy#DECLINE}: a run in differential mode never ships a guess
 * (ARCHITECTURE.md 8.3), and the driver stops at the first disagreement. {@link Policy#FOLLOW_ORACLE}
 * (the table of the first design) lets a campaign continue past a disagreement on the oracle's word
 * and collect all of them in one run. In both policies the disagreements are collected
 * ({@link #disagreements()}, {@link #disagreementRecords()}, {@link Options#found}, optionally a
 * JSON-lines report file) and must make the test run fail. Note that the driver re-checks a DECLINE
 * with the reference kernel, so the verdict of a whole run is the reference's under either policy; the
 * collected disagreements, not the exit status, are the result of a differential run.
 *
 * <p>Enough to reproduce: each record carries the constant (id, unit, name), both outcomes with reason
 * and message, the engines' names, the budget, how warm the session of the engine under test was
 * (checks since its last reset), and the outcomes of a cold re-run of both sides (fresh sessions), which
 * tells a cache-dependent difference from a persistent one.
 *
 * <p>Contexts. Each side should get its own {@code WorkerContext} so that neither can see the
 * other's overlay: pass a {@link ContextFork} (typically
 * {@code ctx -> wiring.newWorkerContext(ctx.workerId, ctx.store, ctx.signals, cap)}). Without one the
 * two sessions share the caller's context; they run strictly one after the other on the caller's
 * thread, so this is safe, but an overlay corrupted by one side is then visible to the other.
 *
 * <p>Session lifecycle. The driver resets a session after every outcome other than ACCEPT. A side
 * whose own outcome was not ACCEPT is therefore reset here before the next unit, also when the
 * combined outcome is ACCEPT and the driver will not do it.
 *
 * <p>An exception thrown by a side is recorded as a decline of that side with reason INTERNAL_ERROR
 * (and its stack head as message); {@code Error}s are not caught.
 *
 * <p>The {@code KernelOps} sampler ({@link Options#sampleEvery}, {@link KernelSampler}). On about one
 * unit in {@code sampleEvery} that both engines accepted, the two engines' answers to {@code infer},
 * {@code whnf} and {@code isDefEq} are compared on sub-terms of the constant's type and value. The
 * judgments of the engine under test are {@code Session.ops()} or, for an engine whose sessions offer
 * none (the machine in its release configuration), what {@link Options#underTestOps} opens. Both
 * {@code KernelOps} work on the caller's context; with a forked oracle context a second oracle session
 * on the caller's context serves the sampler. A sampled unit ends with a reset of both sessions (the
 * sampler cuts the overlay back), so sampling makes the engine under test a little colder; the verdict
 * of the unit is not changed by what the sampler finds. Differences go to {@link Options#foundSamples}
 * and to the report file ({@code "kind": "SAMPLE_..."}).
 *
 * <p>The report file ends with one {@code "kind": "SUMMARY"} line per engine instance (the driver
 * makes a warm one and, for re-checks, a strict one): the counters of {@link #dumpStats} and of the
 * sampler. A harness reads "how many units were compared" from it.
 */
public final class DiffEngine implements Engine {

    /** What a disagreement turns into. */
    public enum Policy {
        /** DECLINE(ENGINE_DISAGREEMENT): fail closed (default). */
        DECLINE,
        /** The oracle's verdict: REJECT for a soundness disagreement, ACCEPT for a completeness disagreement. */
        FOLLOW_ORACLE
    }

    /** Creates the oracle's context from the caller's. */
    @FunctionalInterface
    public interface ContextFork {
        WorkerContext fork(WorkerContext primary);
    }

    /** Options of a differential run. Mutable builder-style fields; read once when the engine is created. */
    public static final class Options {
        public Policy policy = Policy.DECLINE;
        /** null = both sides share the caller's context. */
        public ContextFork fork;
        /** Re-run both sides in fresh sessions when they disagree, and record the outcomes. */
        public boolean recheckCold = true;
        /** Budget of the oracle; null = the budget the caller passes to {@code check}. */
        public Budget oracleBudget;
        /**
         * Retry of an oracle that ran out of a budget. When the oracle declines for a reason that says
         * nothing about the unit ({@code Reason.isRetryable()}: bytes, steps, depth) while the engine under
         * test gave a verdict, the oracle is run once more in a fresh session with no step or depth limit
         * and at least this many bytes. A warm attempt's byte budget is a share of the run's memory (the
         * driver divides it by the number of workers), and a unit that needs more than its share (the
         * arena's perf/app-lam: 1.2 GB) would otherwise go uncompared whenever several workers run.
         * 0 = no retry (the default of this class; {@link #fromSettings} sets 6 GiB).
         */
        public long oracleRetryBytes = 0;
        /** At most this many disagreement records are kept in memory (all are counted and reported to the file). */
        public int maxRecorded = 1000;
        /**
         * At most this many declines of each side are written to the report file per engine instance
         * ({@code "kind": "DECLINED"}: side, unit, constant, reason, message); all are counted, by reason,
         * in the SUMMARY line. A decline is not a difference, but an engine under test that declines is
         * not being tested on that unit: the campaign's gate is "no difference and no decline".
         */
        public int maxDeclines = 200;
        /** Free text copied into every record, e.g. the export's path. */
        public String label;
        /** If not null, every disagreement is appended to this file as one JSON line when it is found. */
        public Path reportFile;
        /**
         * Command that repeats the run, without the export: every record gets it with the export (the
         * {@link #label}) appended, and with the sampler's unit and seed for a sampler difference.
         * null = {@code rlvm-testkit/bin/testkit diff --engine NAME --threads 1}.
         */
        public String reproduce;
        /** Compare {@code KernelOps} answers on about one accepted unit in this many (1 = every unit; 0 = never). */
        public int sampleEvery = 0;
        /** Sample only this unit (the reproduction of a sampler difference); -1 = by {@link #sampleEvery}. */
        public int sampleUnit = -1;
        /** What the sampler draws (seed, sub-terms per constant, judgments). */
        public final KernelSampler.Options sampler = new KernelSampler.Options();
        /** Where the judgments of the engine under test come from; null = {@code Session.ops()}. */
        public OpsFactory underTestOps;
        /** Every sampler difference found by every engine created with these options, in the order found. */
        public final List<KernelSampler.Difference> foundSamples = java.util.Collections.synchronizedList(new ArrayList<>());
        /** The sampler's counters, summed over the sessions that were closed. */
        public final KernelSampler.Tally sampled = new KernelSampler.Tally();
        /**
         * Every disagreement found by every engine created with these options, in the order found. The
         * driver creates engines itself (one for warm sessions, another, strict one for re-checks), so a
         * harness has no handle on them; this list and {@link #reportFile} are how it learns the result.
         * A disagreement that the driver re-checks appears once per engine that saw it (the record says
         * whether the engine was strict).
         */
        public final List<Disagreement> found = java.util.Collections.synchronizedList(new ArrayList<>());

        /**
         * Options for launchers that cannot pass an object. Each setting is read, in this order, from
         * the launcher's {@code -X} settings (the key without the {@code rlvm.} prefix:
         * {@code -X diff.sample=20}; {@link DiffProvider} declares the keys), from the system property
         * ({@code -Drlvm.diff.sample=20}) and from the environment variable (upper case, dots as
         * underscores: {@code RLVM_DIFF_SAMPLE}), which survives a launcher script that splits its
         * arguments at spaces.
         * <pre>
         * rlvm.diff.policy         decline | follow-oracle
         * rlvm.diff.report         file: one JSON line per difference, then the SUMMARY lines
         * rlvm.diff.label          free text of every record: the export's path
         * rlvm.diff.recheck        true | false: cold re-run of both sides on a disagreement
         * rlvm.diff.repro          the command that repeats the run, without the export
         * rlvm.diff.sample         N: KernelOps sampler on about one accepted unit in N
         * rlvm.diff.sample.count   sub-terms per sampled constant (default 4)
         * rlvm.diff.sample.unit    sample only this unit
         * rlvm.diff.seed           seed of the sampler (default 1)
         * rlvm.diff.sample.proofs  true: also compare weak head normal forms of proofs
         * rlvm.diff.sample.more    false: only infer, whnf and isDefEq (no isProp, whnfCore, ensurePi)
         * rlvm.diff.ops            class implementing rlvm.testkit.OpsFactory (public no-argument
         *                          constructor): the KernelOps of the engine under test
         * rlvm.diff.declines       how many declines of a side are written to the report (default 200)
         * rlvm.diff.oracle-retry-mib  an oracle that ran out of a budget is retried once, fresh, with at
         *                          least this many MiB and no step limit (default 6144; 0 = never)
         * </pre>
         */
        public static Options fromSystemProperties() {
            return fromSettings(null);
        }

        /** The keys of {@link #fromSettings} as {@code -X} keys. */
        static final String[] KEYS = {"diff.engine", "diff.policy", "diff.report", "diff.label", "diff.recheck", "diff.repro",
            "diff.sample", "diff.sample.count", "diff.sample.unit", "diff.seed", "diff.sample.proofs", "diff.sample.more", "diff.ops",
            "diff.declines", "diff.oracle-retry-mib"};

        /** @param x the launcher's {@code -X} settings (keys such as {@code diff.sample}), or null */
        public static Options fromSettings(java.util.Map<String, String> x) {
            Options o = new Options();
            String p = setting(x, "rlvm.diff.policy");
            if (p != null && (p.equalsIgnoreCase("follow-oracle") || p.equalsIgnoreCase("FOLLOW_ORACLE"))) {
                o.policy = Policy.FOLLOW_ORACLE;
            }
            String r = setting(x, "rlvm.diff.report");
            if (r != null) {
                o.reportFile = Paths.get(r);
            }
            o.label = setting(x, "rlvm.diff.label");
            String c = setting(x, "rlvm.diff.recheck");
            if (c != null) {
                o.recheckCold = Boolean.parseBoolean(c);
            }
            o.reproduce = setting(x, "rlvm.diff.repro");
            o.sampleEvery = (int) number(x, "rlvm.diff.sample", 0);
            o.sampleUnit = (int) number(x, "rlvm.diff.sample.unit", -1);
            o.sampler.perConstant = (int) number(x, "rlvm.diff.sample.count", o.sampler.perConstant);
            o.sampler.seed = number(x, "rlvm.diff.seed", o.sampler.seed);
            o.maxDeclines = (int) number(x, "rlvm.diff.declines", o.maxDeclines);
            o.oracleRetryBytes = number(x, "rlvm.diff.oracle-retry-mib", 6144) << 20;
            String wp = setting(x, "rlvm.diff.sample.proofs");
            if (wp != null) {
                o.sampler.whnfProofs = Boolean.parseBoolean(wp);
            }
            String more = setting(x, "rlvm.diff.sample.more");
            if (more != null) {
                o.sampler.more = Boolean.parseBoolean(more);
            }
            String ops = setting(x, "rlvm.diff.ops");
            if (ops != null) {
                try {
                    o.underTestOps = (OpsFactory) Class.forName(ops).getDeclaredConstructor().newInstance();
                } catch (ReflectiveOperationException | ClassCastException e) {
                    throw new IllegalArgumentException("rlvm.diff.ops=" + ops + ": not an rlvm.testkit.OpsFactory with a public"
                            + " no-argument constructor on the class path (" + e + ")");
                }
            }
            return o;
        }

        /**
         * The {@code -X} setting ({@code key} without its {@code rlvm.} prefix), else the system property
         * {@code key}, else the environment variable of the same name in upper case with underscores;
         * null if none or empty.
         */
        static String setting(java.util.Map<String, String> x, String key) {
            String v = x == null ? null : x.get(key.startsWith("rlvm.") ? key.substring(5) : key);
            if (v == null || v.isEmpty()) {
                v = System.getProperty(key);
            }
            if (v == null || v.isEmpty()) {
                v = System.getenv(key.toUpperCase(java.util.Locale.ROOT).replace('.', '_').replace('-', '_'));
            }
            return v == null || v.isEmpty() ? null : v;
        }

        private static long number(java.util.Map<String, String> x, String key, long fallback) {
            String v = setting(x, key);
            if (v == null) {
                return fallback;
            }
            try {
                return Long.parseLong(v.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(key + "=" + v + ": not a number");
            }
        }
    }

    /** One difference in verdict between the two engines. */
    public static final class Disagreement {
        /** "SOUNDNESS": the engine under test accepted what the oracle rejects. "COMPLETENESS": the reverse. */
        public final String kind;
        public final int cid;
        /** Declaration record index of the constant, or -1 if the store does not say. */
        public final int unit;
        /** Name of the constant, or {@code cid N} if the store does not say. */
        public final String constant;
        public final String underTestEngine;
        public final String oracleEngine;
        /** {@code Outcome} codes. */
        public final int underTest;
        public final int oracle;
        public final Reason underTestReason;
        public final Reason oracleReason;
        public final String underTestMessage;
        public final String oracleMessage;
        /** Outcomes of the cold re-run (fresh sessions), or -1 if it was not made. */
        public final int coldUnderTest;
        public final int coldOracle;
        public final int workerId;
        /** Checks the session of the engine under test had made since its last reset, this one included. */
        public final long warmChecks;
        public final String budget;
        public final boolean strict;
        public final String label;
        /** A command that repeats the run in which this was found, or null if the export is not known. */
        public final String reproduce;

        Disagreement(String kind, int cid, int unit, String constant, String underTestEngine, String oracleEngine, int underTest,
                int oracle, Reason underTestReason, Reason oracleReason, String underTestMessage, String oracleMessage,
                int coldUnderTest, int coldOracle, int workerId, long warmChecks, String budget, boolean strict, String label,
                String reproduce) {
            this.reproduce = reproduce;
            this.kind = kind;
            this.cid = cid;
            this.unit = unit;
            this.constant = constant;
            this.underTestEngine = underTestEngine;
            this.oracleEngine = oracleEngine;
            this.underTest = underTest;
            this.oracle = oracle;
            this.underTestReason = underTestReason;
            this.oracleReason = oracleReason;
            this.underTestMessage = underTestMessage;
            this.oracleMessage = oracleMessage;
            this.coldUnderTest = coldUnderTest;
            this.coldOracle = coldOracle;
            this.workerId = workerId;
            this.warmChecks = warmChecks;
            this.budget = budget;
            this.strict = strict;
            this.label = label;
        }

        /** "persistent" (the cold re-run disagrees too), "warm-only" (it does not), or "not-rechecked". */
        public String persistence() {
            if (coldUnderTest < 0 || coldOracle < 0) {
                return "not-rechecked";
            }
            return coldUnderTest == underTest && coldOracle == oracle ? "persistent" : "warm-only";
        }

        /** One line: constant name, both outcomes, both reasons, and the context needed to reproduce. */
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(kind).append(" '").append(constant).append("' (cid ").append(cid);
            if (unit >= 0) {
                sb.append(", unit ").append(unit);
            }
            sb.append("): ").append(underTestEngine).append(' ').append(describe(underTest, underTestReason, underTestMessage));
            sb.append(" vs ").append(oracleEngine).append(' ').append(describe(oracle, oracleReason, oracleMessage));
            sb.append(" [").append(persistence());
            if (coldUnderTest >= 0) {
                sb.append(": cold ").append(Outcome.name(coldUnderTest)).append(" vs ").append(Outcome.name(coldOracle));
            }
            sb.append("; worker ").append(workerId).append(", check ").append(warmChecks).append(" of its session, budget ")
                    .append(budget).append(strict ? ", strict" : "");
            if (label != null) {
                sb.append(", ").append(label);
            }
            sb.append(']');
            if (reproduce != null) {
                sb.append(" reproduce: ").append(reproduce);
            }
            return sb.toString();
        }

        Json toJson() {
            Json o = Json.object();
            o.put("kind", kind);
            o.put("constant", constant);
            o.put("cid", cid);
            o.put("unit", unit);
            o.put("under_test_engine", underTestEngine);
            o.put("under_test", Outcome.name(underTest));
            o.put("under_test_reason", underTestReason == null ? null : underTestReason.name());
            o.put("under_test_message", underTestMessage);
            o.put("oracle_engine", oracleEngine);
            o.put("oracle", Outcome.name(oracle));
            o.put("oracle_reason", oracleReason == null ? null : oracleReason.name());
            o.put("oracle_message", oracleMessage);
            o.put("persistence", persistence());
            o.put("cold_under_test", coldUnderTest < 0 ? null : Outcome.name(coldUnderTest));
            o.put("cold_oracle", coldOracle < 0 ? null : Outcome.name(coldOracle));
            o.put("worker", workerId);
            o.put("warm_checks", warmChecks);
            o.put("budget", budget);
            o.put("strict", strict);
            o.put("label", label);
            o.put("reproduce", reproduce);
            return o;
        }

        private static String describe(int outcome, Reason reason, String message) {
            StringBuilder sb = new StringBuilder(Outcome.name(outcome).toUpperCase(java.util.Locale.ROOT));
            if (outcome != Outcome.ACCEPT) {
                sb.append('(').append(reason == null ? "no reason" : reason.name());
                if (message != null && !message.isEmpty()) {
                    sb.append(": ").append(message.length() > 300 ? message.substring(0, 297) + "..." : message);
                }
                sb.append(')');
            }
            return sb.toString();
        }
    }

    private final Store store;
    private final EngineConfig config;
    private final Engine underTest;
    private final Engine oracle;
    private final Options options;

    private final AtomicLong checks = new AtomicLong();
    private final AtomicLong agreeAccept = new AtomicLong();
    private final AtomicLong agreeReject = new AtomicLong();
    private final AtomicLong soundness = new AtomicLong();
    private final AtomicLong completeness = new AtomicLong();
    private final AtomicLong declinedUnderTest = new AtomicLong();
    private final AtomicLong declinedOracle = new AtomicLong();
    private final AtomicLong warmOnly = new AtomicLong();
    private final java.util.Map<String, Long> declineReasonsUnderTest = new java.util.TreeMap<>();
    private final java.util.Map<String, Long> declineReasonsOracle = new java.util.TreeMap<>();
    private final AtomicLong declineRecords = new AtomicLong();
    private final AtomicLong oracleRetries = new AtomicLong();
    private final AtomicLong sampledUnits = new AtomicLong();
    private final AtomicLong sampleDifferences = new AtomicLong();
    private final KernelSampler.Tally sampleTally = new KernelSampler.Tally();
    private final java.util.concurrent.atomic.AtomicReference<String> sampleUnavailable =
            new java.util.concurrent.atomic.AtomicReference<>();
    private final List<Disagreement> records = new ArrayList<>();
    private boolean closed;

    public DiffEngine(Store store, EngineConfig config, EngineFactory underTest, EngineFactory oracle) {
        this(store, config, underTest, oracle, new Options());
    }

    public DiffEngine(Store store, EngineConfig config, EngineFactory underTest, EngineFactory oracle, Options options) {
        this.store = store;
        this.config = config;
        this.options = options == null ? new Options() : options;
        this.underTest = underTest.create(store, config);
        Engine o;
        try {
            o = oracle.create(store, config);
        } catch (RuntimeException | Error e) {
            this.underTest.close();
            throw e;
        }
        this.oracle = o;
    }

    /** A factory that pairs two factories (policy DECLINE, shared context; see {@link Options}). */
    public static EngineFactory of(EngineFactory underTest, EngineFactory oracle) {
        return (store, config) -> new DiffEngine(store, config, underTest, oracle);
    }

    /** A factory that pairs two factories with the given options. */
    public static EngineFactory of(EngineFactory underTest, EngineFactory oracle, Options options) {
        return (store, config) -> new DiffEngine(store, config, underTest, oracle, options);
    }

    @Override
    public String name() {
        return "diff";
    }

    @Override
    public Session newSession(WorkerContext ctx) {
        WorkerContext oracleCtx = options.fork == null ? null : options.fork.fork(ctx);
        Session a = underTest.newSession(ctx);
        Session b;
        try {
            b = oracle.newSession(oracleCtx == null ? ctx : oracleCtx);
        } catch (RuntimeException | Error e) {
            a.close();
            throw e;
        }
        return new DiffSession(ctx, oracleCtx, a, b);
    }

    @Override
    public void environmentAdvanced(int units) {
        underTest.environmentAdvanced(units);
        oracle.environmentAdvanced(units);
    }

    @Override
    public void dumpStats(StatsSink sink) {
        sink.put("diff.under_test", underTest.name());
        sink.put("diff.oracle", oracle.name());
        sink.put("diff.policy", options.policy.name());
        sink.put("diff.context", options.fork == null ? "shared" : "forked");
        sink.put("diff.checks", checks.get());
        sink.put("diff.agree.accept", agreeAccept.get());
        sink.put("diff.agree.reject", agreeReject.get());
        sink.put("diff.disagree.soundness", soundness.get());
        sink.put("diff.disagree.completeness", completeness.get());
        sink.put("diff.disagree.warm_only", warmOnly.get());
        sink.put("diff.declined.under_test", declinedUnderTest.get());
        sink.put("diff.declined.oracle", declinedOracle.get());
        sink.put("diff.oracle.retries", oracleRetries.get());
        if (options.sampleEvery > 0 || options.sampleUnit >= 0) {
            synchronized (sampleTally) {
                sink.put("diff.sample.constants", sampleTally.constants);
                sink.put("diff.sample.problems", sampleTally.problems);
                sink.put("diff.sample.infer", sampleTally.infer);
                sink.put("diff.sample.whnf", sampleTally.whnf);
                sink.put("diff.sample.def_eq", sampleTally.defEq);
                sink.put("diff.sample.more", sampleTally.more);
                sink.put("diff.sample.differences", sampleTally.differences);
                sink.put("diff.sample.undecided", sampleTally.undecided);
                sink.put("diff.sample.oracle_errors", sampleTally.oracleErrors);
            }
            String why = sampleUnavailable.get();
            if (why != null) {
                sink.put("diff.sample.unavailable", why);
            }
        }
        List<String> lines = disagreements();
        for (int i = 0; i < lines.size() && i < 100; i++) {
            sink.put("diff.disagreement." + i, lines.get(i));
        }
        underTest.dumpStats(new PrefixSink(sink, "diff.under_test."));
        oracle.dumpStats(new PrefixSink(sink, "diff.oracle."));
    }

    @Override
    public void close() {
        synchronized (records) {
            if (closed) {
                return;
            }
            closed = true;
        }
        try {
            underTest.close();
        } finally {
            try {
                oracle.close();
            } finally {
                append(summaryJson());
            }
        }
    }

    /** The counters of this engine instance as the {@code "kind": "SUMMARY"} line of the report file. */
    Json summaryJson() {
        Json o = Json.object();
        o.put("kind", "SUMMARY");
        o.put("label", options.label);
        o.put("under_test_engine", underTest.name());
        o.put("oracle_engine", oracle.name());
        o.put("strict", config != null && config.strict);
        o.put("policy", options.policy.name());
        o.put("context", options.fork == null ? "shared" : "forked");
        o.put("checks", checks.get());
        o.put("agree_accept", agreeAccept.get());
        o.put("agree_reject", agreeReject.get());
        o.put("soundness", soundness.get());
        o.put("completeness", completeness.get());
        o.put("warm_only", warmOnly.get());
        o.put("declined_under_test", declinedUnderTest.get());
        o.put("declined_oracle", declinedOracle.get());
        o.put("oracle_retries", oracleRetries.get());
        o.put("declined_under_test_by_reason", reasons(declineReasonsUnderTest));
        o.put("declined_oracle_by_reason", reasons(declineReasonsOracle));
        o.put("sample_every", options.sampleEvery);
        o.put("sampled_units", sampledUnits.get());
        o.put("sample_differences", sampleDifferences.get());
        synchronized (sampleTally) {
            o.put("sample", sampleTally.toJson());
        }
        o.put("sample_unavailable", sampleUnavailable.get());
        return o;
    }

    private static Json reasons(java.util.Map<String, Long> counts) {
        Json o = Json.object();
        synchronized (counts) {
            for (java.util.Map.Entry<String, Long> e : counts.entrySet()) {
                o.put(e.getKey(), e.getValue());
            }
        }
        return o;
    }

    /** Counts a decline of one side by reason and writes the first {@link Options#maxDeclines} to the report. */
    private void declined(boolean underTestSide, int cid, Reason reason, String message, int workerId) {
        java.util.Map<String, Long> counts = underTestSide ? declineReasonsUnderTest : declineReasonsOracle;
        String name = reason == null ? "no reason" : reason.name();
        synchronized (counts) {
            counts.merge(name, 1L, Long::sum);
        }
        if (options.reportFile == null || declineRecords.incrementAndGet() > 2L * options.maxDeclines) {
            return;
        }
        Json o = Json.object();
        o.put("kind", "DECLINED");
        o.put("side", underTestSide ? "under_test" : "oracle");
        o.put("engine", underTestSide ? underTest.name() : oracle.name());
        o.put("constant", constantName(cid));
        o.put("cid", cid);
        o.put("unit", unitOf(cid));
        o.put("reason", name);
        o.put("message", message != null && message.length() > 400 ? message.substring(0, 397) + "..." : message);
        o.put("worker", workerId);
        o.put("strict", config != null && config.strict);
        o.put("label", options.label);
        append(o);
    }

    /** Units on which the oracle was run a second time because it had run out of a budget. */
    public long oracleRetries() {
        return oracleRetries.get();
    }

    /** Units on which the sampler ran. */
    public long sampledUnits() {
        return sampledUnits.get();
    }

    /** Differences the sampler found, recorded or not. */
    public long sampleDifferenceCount() {
        return sampleDifferences.get();
    }

    /** Why the sampler could not run although it was asked to, or null. */
    public String sampleUnavailable() {
        return sampleUnavailable.get();
    }

    /** One line per disagreement: constant name, both outcomes, both reasons (see {@link Disagreement#toString()}). */
    public java.util.List<String> disagreements() {
        List<String> lines = new ArrayList<>();
        synchronized (records) {
            for (Disagreement d : records) {
                lines.add(d.toString());
            }
        }
        return lines;
    }

    /** The disagreements found so far (at most {@link Options#maxRecorded}). */
    public List<Disagreement> disagreementRecords() {
        synchronized (records) {
            return new ArrayList<>(records);
        }
    }

    /** Number of disagreements found so far, recorded or not. */
    public long disagreementCount() {
        return soundness.get() + completeness.get();
    }

    public long soundnessDisagreements() {
        return soundness.get();
    }

    public long completenessDisagreements() {
        return completeness.get();
    }

    /** Units on which both engines gave the same ACCEPT or REJECT. */
    public long agreements() {
        return agreeAccept.get() + agreeReject.get();
    }

    public long checks() {
        return checks.get();
    }

    public long declinedByUnderTest() {
        return declinedUnderTest.get();
    }

    public long declinedByOracle() {
        return declinedOracle.get();
    }

    /** Serialises appends to report files across engine instances. */
    private static final Object REPORT_LOCK = new Object();

    private void record(Disagreement d) {
        synchronized (records) {
            if (records.size() < options.maxRecorded) {
                records.add(d);
            }
        }
        if (options.found.size() < options.maxRecorded) {
            options.found.add(d);
        }
        append(d.toJson());
    }

    private void recordSample(KernelSampler.Difference d) {
        sampleDifferences.incrementAndGet();
        if (options.foundSamples.size() < options.maxRecorded) {
            options.foundSamples.add(d);
        }
        Json o = d.toJson();
        o.put("under_test_engine", underTest.name());
        o.put("oracle_engine", oracle.name());
        o.put("strict", config != null && config.strict);
        o.put("label", options.label);
        o.put("reproduce", reproduce(d.unit, true));
        append(o);
    }

    /** Appends one JSON line to the report file, if there is one. */
    private void append(Json line) {
        synchronized (REPORT_LOCK) {
            if (options.reportFile != null) {
                try {
                    Path f = options.reportFile.toAbsolutePath();
                    if (f.getParent() != null) {
                        Files.createDirectories(f.getParent());
                    }
                    Files.write(f, (Json.write(line) + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND);
                } catch (IOException e) {
                    // the report file is a convenience; the record is still in memory and in the counters
                }
            }
        }
    }

    /** The command that repeats this run (null when the export is not known); for a sampler difference, on that unit alone. */
    String reproduce(int unit, boolean sampled) {
        if (options.label == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(options.reproduce != null ? options.reproduce
                : "rlvm-testkit/bin/testkit diff --engine " + underTest.name() + " --threads 1");
        if (sampled) {
            sb.append(" --sample-unit ").append(unit).append(" --sample-count ").append(options.sampler.perConstant)
                    .append(" --seed ").append(options.sampler.seed);
        }
        return sb.append(' ').append(options.label).toString();
    }

    /** The sampler's choice of units: a function of the seed and the constant id alone. */
    private boolean sampled(int cid) {
        if (options.sampleUnit >= 0) {
            return unitOf(cid) == options.sampleUnit;
        }
        if (options.sampleEvery <= 0) {
            return false;
        }
        long z = (options.sampler.seed ^ 0x5DEECE66DL) * 0x9E3779B97F4A7C15L + cid;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return Long.remainderUnsigned(z, options.sampleEvery) == 0;
    }

    private String constantName(int cid) {
        try {
            if (store != null) {
                return store.names().toString(store.decls().name(cid));
            }
        } catch (RuntimeException e) {
            // a store that cannot render the name: fall through
        }
        return "cid " + cid;
    }

    private int unitOf(int cid) {
        try {
            if (store != null) {
                return store.decls().unit(cid);
            }
        } catch (RuntimeException e) {
            // unknown
        }
        return -1;
    }

    private static String budgetText(Budget b) {
        if (b == null) {
            return "none";
        }
        return "steps " + (b.maxSteps == Budget.UNLIMITED ? "unlimited" : Long.toString(b.maxSteps)) + " / bytes "
                + (b.maxBytes == Budget.UNLIMITED ? "unlimited" : Long.toString(b.maxBytes)) + " / depth "
                + (b.maxDepth == Integer.MAX_VALUE ? "unlimited" : Integer.toString(b.maxDepth));
    }

    /** Forwards statistics under a key prefix, so that two engines of the same kind do not collide. */
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

    /** One side of a differential session: the session, its last outcome and why. */
    private static final class Side {
        final Session session;
        /** Context owned by this side alone (reset together with the session), or null. */
        final WorkerContext ownContext;
        int outcome;
        Reason reason;
        String message;
        long checksSinceReset;

        Side(Session session, WorkerContext ownContext) {
            this.session = session;
            this.ownContext = ownContext;
        }

        int run(int cid, Budget budget) {
            if (ownContext != null && ownContext.terms != null) {
                ownContext.terms.refresh();
            }
            checksSinceReset++;
            try {
                outcome = session.check(cid, budget);
                if (outcome == Outcome.ACCEPT) {
                    reason = null;
                    message = null;
                } else if (outcome == Outcome.REJECT || outcome == Outcome.DECLINE) {
                    reason = session.lastReason();
                    message = safeMessage(session);
                } else {
                    message = "invalid outcome code " + outcome;
                    outcome = Outcome.DECLINE;
                    reason = Reason.INTERNAL_ERROR;
                }
            } catch (RuntimeException e) {
                outcome = Outcome.DECLINE;
                reason = Reason.INTERNAL_ERROR;
                StackTraceElement[] st = e.getStackTrace();
                message = "exception " + e + (st.length > 0 ? " at " + st[0] : "");
            }
            return outcome;
        }

        private static String safeMessage(Session s) {
            try {
                return s.lastMessage();
            } catch (RuntimeException e) {
                return "(no message: " + e + ")";
            }
        }

        void reset() {
            session.reset();
            if (ownContext != null && ownContext.overlay != null) {
                ownContext.reset();
            }
            checksSinceReset = 0;
        }
    }

    private final class DiffSession implements Session {
        private final WorkerContext ctx;
        private final Side a;
        private final Side b;
        private Reason lastReason;
        private String lastMessage;
        private final boolean sampling = options.sampleEvery > 0 || options.sampleUnit >= 0;
        private KernelSampler sampler;
        private boolean samplerTried;
        /** With a forked oracle context: a second oracle session on the caller's context, for the sampler alone. */
        private Session samplerOracle;

        DiffSession(WorkerContext ctx, WorkerContext oracleCtx, Session underTestSession, Session oracleSession) {
            this.ctx = ctx;
            this.a = new Side(underTestSession, null);
            this.b = new Side(oracleSession, oracleCtx);
        }

        /** Runs the KernelOps sampler on a constant both sides accepted, then resets both sessions. */
        private void sample(int cid) {
            if (sampler == null) {
                if (samplerTried) {
                    return;
                }
                samplerTried = true;
                try {
                    KernelOps ua = options.underTestOps != null ? options.underTestOps.open(a.session, ctx) : a.session.ops();
                    if (ua == null) {
                        sampleUnavailable.compareAndSet(null, "the sessions of engine '" + underTest.name()
                                + "' offer no KernelOps (Session.ops() is null): name an rlvm.testkit.OpsFactory with"
                                + " rlvm.diff.ops / --ops");
                        return;
                    }
                    Session os = b.session;
                    if (b.ownContext != null) {
                        os = samplerOracle = oracle.newSession(ctx);
                    }
                    KernelOps ob = os.ops();
                    if (ob == null) {
                        sampleUnavailable.compareAndSet(null, "the oracle '" + oracle.name() + "' offers no KernelOps");
                        return;
                    }
                    sampler = new KernelSampler(store, ctx, ua, ob, options.sampler);
                } catch (RuntimeException e) {
                    sampleUnavailable.compareAndSet(null, "the sampler could not be set up: " + e);
                    return;
                }
            }
            sampledUnits.incrementAndGet();
            try {
                for (KernelSampler.Difference d : sampler.sample(cid)) {
                    recordSample(d);
                }
            } catch (RuntimeException e) {
                // the sampler itself failed (not a judgment of a side): say so once, keep the run going
                StackTraceElement[] st = e.getStackTrace();
                sampleUnavailable.compareAndSet(null, "the sampler failed on constant " + constantName(cid) + ": " + e
                        + (st.length > 0 ? " at " + st[0] : ""));
            } finally {
                // overlay ids the sides learned while sampling are gone: both start afresh
                a.reset();
                b.reset();
                if (samplerOracle != null) {
                    samplerOracle.reset();
                }
            }
        }

        @Override
        public int check(int cid, Budget budget) {
            Budget oracleBudget = options.oracleBudget != null ? options.oracleBudget : budget;
            int x = a.run(cid, budget);
            int y = b.run(cid, oracleBudget);
            if (y == Outcome.DECLINE && x != Outcome.DECLINE && options.oracleRetryBytes > 0 && b.reason != null
                    && b.reason != Reason.CANCELLED && b.reason.isRetryable()) {
                // The oracle ran out of a budget on a unit the engine under test decided: once more, fresh and cold.
                oracleRetries.incrementAndGet();
                b.reset();
                y = b.run(cid, Budget.cold(Math.max(oracleBudget == null ? 0L : oracleBudget.maxBytes, options.oracleRetryBytes)));
            }
            checks.incrementAndGet();
            int result;
            if (x == Outcome.ACCEPT && y == Outcome.ACCEPT) {
                agreeAccept.incrementAndGet();
                if (sampling && sampled(cid)) {
                    sample(cid);
                }
                return Outcome.ACCEPT; // the hot path: nothing allocated here
            }
            if (x == Outcome.REJECT && y == Outcome.REJECT) {
                agreeReject.incrementAndGet();
                lastReason = b.reason;
                lastMessage = b.message;
                result = Outcome.REJECT;
            } else if (x == Outcome.DECLINE || y == Outcome.DECLINE) {
                if (x == Outcome.DECLINE) {
                    declinedUnderTest.incrementAndGet();
                    // a cancelled unit lies above the first failure: its outcome cannot matter and says nothing
                    if (a.reason != Reason.CANCELLED) {
                        declined(true, cid, a.reason, a.message, ctx == null ? -1 : ctx.workerId);
                    }
                }
                if (y == Outcome.DECLINE) {
                    declinedOracle.incrementAndGet();
                    if (b.reason != Reason.CANCELLED) {
                        declined(false, cid, b.reason, b.message, ctx == null ? -1 : ctx.workerId);
                    }
                }
                Side other = x == Outcome.DECLINE ? b : a;
                result = other.outcome; // DECLINE when both declined
                Side why = x == Outcome.DECLINE && y == Outcome.DECLINE ? a : other;
                lastReason = why.reason;
                lastMessage = why.message;
            } else {
                result = disagreement(cid, budget, oracleBudget, x, y);
            }
            // Mirror the driver: a session is reset after any outcome of its own other than ACCEPT.
            if (a.outcome != Outcome.ACCEPT) {
                a.reset();
            }
            if (b.outcome != Outcome.ACCEPT) {
                b.reset();
            }
            return result;
        }

        private int disagreement(int cid, Budget budget, Budget oracleBudget, int x, int y) {
            boolean sound = x == Outcome.ACCEPT; // the engine under test accepted, the oracle rejected
            (sound ? soundness : completeness).incrementAndGet();
            Reason ra = a.reason;
            Reason rb = b.reason;
            String ma = a.message;
            String mb = b.message;
            long warm = a.checksSinceReset;
            int coldA = -1;
            int coldB = -1;
            if (options.recheckCold) {
                a.reset();
                coldA = a.run(cid, budget);
                b.reset();
                coldB = b.run(cid, oracleBudget);
                if (coldA != x || coldB != y) {
                    warmOnly.incrementAndGet();
                }
            }
            Disagreement d = new Disagreement(sound ? "SOUNDNESS" : "COMPLETENESS", cid, unitOf(cid), constantName(cid),
                    underTest.name(), oracle.name(), x, y, ra, rb, ma, mb, coldA, coldB, ctx == null ? -1 : ctx.workerId, warm,
                    budgetText(budget), config != null && config.strict, options.label, reproduce(unitOf(cid), false));
            record(d);
            // Make sure the reset rule below sees the outcomes of the first (warm) run.
            a.outcome = options.recheckCold ? worst(x, coldA) : x;
            b.outcome = options.recheckCold ? worst(y, coldB) : y;
            if (options.policy == Policy.FOLLOW_ORACLE) {
                if (sound) {
                    lastReason = rb;
                    lastMessage = mb;
                    return Outcome.REJECT;
                }
                return Outcome.ACCEPT;
            }
            lastReason = Reason.ENGINE_DISAGREEMENT;
            lastMessage = d.toString();
            return Outcome.DECLINE;
        }

        /** ACCEPT only if both runs accepted: any other outcome means the session must be reset. */
        private int worst(int first, int second) {
            return first == Outcome.ACCEPT ? second : first;
        }

        @Override
        public Reason lastReason() {
            return lastReason;
        }

        @Override
        public String lastMessage() {
            return lastMessage;
        }

        /** The judgments of the engine under test: they work on the caller's context, as admission code expects. */
        @Override
        public KernelOps ops() {
            return a.session.ops();
        }

        @Override
        public void reset() {
            a.reset();
            b.reset();
        }

        /**
         * Bytes in use, the shared context counted once: both sessions report the context they work on
         * (the reference session always does), and counting it twice would make the driver reset a
         * differential session about twice as often as the session of the engine under test alone.
         */
        @Override
        public long footprintBytes() {
            long fa = a.session.footprintBytes();
            long fb = b.session.footprintBytes();
            long n = fa + fb;
            if (b.ownContext != null && b.ownContext.overlay != null) {
                n += b.ownContext.footprintBytes();
            } else if (ctx != null && ctx.overlay != null) {
                n = Math.max(Math.max(fa, fb), n - ctx.footprintBytes());
            }
            return n;
        }

        @Override
        public long steps() {
            return a.session.steps() + b.session.steps();
        }

        @Override
        public void close() {
            if (sampler != null) {
                synchronized (sampleTally) {
                    sampleTally.add(sampler.tally());
                }
                options.sampled.add(sampler.tally());
                sampler = null;
            }
            try {
                a.session.close();
            } finally {
                try {
                    b.session.close();
                } finally {
                    if (samplerOracle != null) {
                        samplerOracle.close();
                    }
                }
            }
        }
    }
}
