package rlvm.ref;

import java.util.Map;
import java.util.Set;
import rlvm.api.Budget;
import rlvm.api.Engine;
import rlvm.api.EngineConfig;
import rlvm.api.EngineFactory;
import rlvm.api.EngineProvider;
import rlvm.api.KernelException;
import rlvm.api.KernelOps;
import rlvm.api.Outcome;
import rlvm.api.Reason;
import rlvm.api.ResourceException;
import rlvm.api.Session;
import rlvm.api.StatsSink;
import rlvm.api.WorkerContext;
import rlvm.store.DeclKind;
import rlvm.store.LitPool;
import rlvm.store.Store;

/**
 * The reference kernel as an {@link Engine}: every session is a {@link RefKernel} whose caches are
 * cleared for each declaration (as the official checker creates one {@code type_checker} per
 * declaration). Between units a session keeps no fact about the input; the only thing that outlives
 * a declaration is the session's table of imported constant terms (canonical copies of stored terms,
 * see {@link RefKernel}), and not even that in strict mode. The engine itself holds nothing but
 * its settings and statistics.
 */
public final class RefEngine implements Engine {

    /** Factory for the CLI and the tests: the default settings (as modified by system properties). */
    public static final EngineFactory FACTORY = RefEngine::new;

    /** The factory for the given settings (see {@link RefOptions}). */
    public static EngineFactory factory(RefOptions options) {
        if (options == null) {
            throw new NullPointerException("options");
        }
        return (store, config) -> new RefEngine(store, config, options);
    }

    private final Store store;
    private final EngineConfig config;
    private final RefOptions options;

    private long statSessions;
    private long statUnits;
    private long statAccepted;
    private long statRejected;
    private long statDeclined;
    private long statSteps;
    private long statDeepRuns;
    private int statMaxDepth;
    private long statMaxUnitBytes;
    private final long[] statImports = new long[6];
    private final long[] statIdentity = new long[6];
    private long statIdentityUnits;
    private long statIdentityForks;

    public RefEngine(Store store, EngineConfig config) {
        this(store, config, RefOptions.fromSystemProperties());
    }

    public RefEngine(Store store, EngineConfig config, RefOptions options) {
        if (config != null && config.profile != EngineConfig.KernelProfile.LEAN_4_36) {
            throw new UnsupportedOperationException(
                    "rlvm-ref implements the v4.36 kernel only; profile " + config.profile + " is deferred");
        }
        this.store = store;
        this.config = config == null ? EngineConfig.defaults() : config;
        this.options = options;
    }

    /** The settings this engine was created with. */
    public RefOptions options() {
        return options;
    }

    @Override
    public String name() {
        return "reference";
    }

    @Override
    public Session newSession(WorkerContext ctx) {
        if (ctx.store != store) {
            throw new IllegalArgumentException("worker context of another store");
        }
        return new RefSession(this, ctx);
    }

    @Override
    public void dumpStats(StatsSink sink) {
        if (!config.stats) {
            return;
        }
        synchronized (this) {
            sink.put("reference.sessions", statSessions);
            sink.put("reference.units", statUnits);
            sink.put("reference.accepted", statAccepted);
            sink.put("reference.rejected", statRejected);
            sink.put("reference.declined", statDeclined);
            sink.put("reference.steps", statSteps);
            sink.put("reference.deepStackRuns", statDeepRuns);
            sink.put("reference.maxDepth", (long) statMaxDepth);
            sink.put("reference.maxUnitBytes", statMaxUnitBytes);
            sink.put("reference.levels", options.officialLevels ? "official" : "complete");
            sink.put("reference.importHits", statImports[0]);
            sink.put("reference.importCopies", statImports[1]);
            sink.put("reference.importPromoted", statImports[2]);
            sink.put("reference.importDrops", statImports[3]);
            sink.put("reference.importNodes", statImports[5]);
            sink.put("reference.exact", options.exact() ? "official" : "off");
            sink.put("reference.identity", RefOptions.identityName(options.identity));
            sink.put("reference.identityEvents", statIdentity[0]);
            sink.put("reference.identityHarmless", statIdentity[1]);
            sink.put("reference.identityHazardK", statIdentity[2]);
            sink.put("reference.identityHazardInfer", statIdentity[3]);
            sink.put("reference.identityHazardProj", statIdentity[4]);
            sink.put("reference.identityNested", statIdentity[5]);
            sink.put("reference.identityUnits", statIdentityUnits);
            sink.put("reference.identityForks", statIdentityForks);
        }
    }

    @Override
    public void close() {
        // Nothing engine-wide to release: sessions own everything.
    }

    private synchronized void account(RefSession s) {
        statSessions++;
        statUnits += s.units;
        statAccepted += s.accepted;
        statRejected += s.rejected;
        statDeclined += s.declined;
        statSteps += s.kernel.steps();
        statDeepRuns += s.deepRuns;
        statMaxDepth = Math.max(statMaxDepth, s.kernel.maxDepthSeen());
        statMaxUnitBytes = Math.max(statMaxUnitBytes, s.maxUnitBytes);
        long[] identity = s.kernel.identityStats();
        for (int i = 0; i < statIdentity.length; i++) {
            statIdentity[i] += identity[i];
        }
        statIdentityUnits += s.identityUnits;
        statIdentityForks += s.identityForks;
        long[] imports = s.kernel.importStats();
        for (int i = 0; i < statImports.length; i++) {
            statImports[i] += imports[i];
        }
    }

    /** Registered in {@code META-INF/services/rlvm.api.EngineProvider}. */
    public static final class Provider implements EngineProvider {
        public Provider() {}

        @Override
        public String name() {
            return "reference";
        }

        @Override
        public int priority() {
            return 0;
        }

        @Override
        public boolean isReference() {
            return true;
        }

        @Override
        public EngineFactory factory() {
            return FACTORY;
        }

        /**
         * {@code -X levels=official|complete} (default official), {@code -X ref.imports=on|off|verify},
         * {@code -X ref.exact=off|official} (default off; official = the strict mode; the launcher's
         * {@code policy=strict} implies it: this provider declares the key {@code policy}),
         * {@code -X ref.identity=decline|same|other|count} (experiments; see {@link RefOptions}).
         */
        @Override
        public Set<String> optionKeys() {
            return Set.of(RefOptions.KEY_LEVELS, RefOptions.KEY_IMPORTS, RefOptions.KEY_EXACT, RefOptions.KEY_IDENTITY,
                    RefOptions.KEY_POLICY);
        }

        @Override
        public EngineFactory factory(Map<String, String> options) {
            return RefEngine.factory(RefOptions.parse(options));
        }
    }

    /**
     * Session of the reference engine. {@link #check} runs the declaration on the calling thread; if
     * that thread's stack turns out to be too small for the declaration (a {@code StackOverflowError}
     * caught by the kernel), the worker context is reset and the declaration is run once more on a
     * helper thread with a deep stack ({@link DeepStack}). The outcome is therefore independent of
     * the caller's stack size; only {@link Budget#maxDepth} and the kernel's own depth limit decline.
     */
    public static final class RefSession implements Session, Runnable {

        private final RefEngine engine;
        private final WorkerContext ctx;
        final RefKernel kernel;
        private DeepStack deep;
        private boolean marksSupported = true;
        private boolean closed;

        private Reason lastReason;
        private String lastMessage;

        private int taskCid;
        private Budget taskBudget;
        private int taskOutcome;

        long units;
        long accepted;
        long rejected;
        long declined;
        long deepRuns;
        /** Largest footprint (overlay terms, literals, kernel tables) a single declaration reached. */
        long maxUnitBytes;
        /**
         * Declarations with an object-identity hazard (strict mode: the second pass was not shown to
         * repeat nothing; otherwise: every event, nothing is probed).
         */
        long identityUnits;
        /** Strict mode: hazards that were decided both ways. */
        long identityForks;
        /** Checks of the current declaration so far (strict mode). */
        private int identityRuns;
        /** Strict mode: most checks of one declaration (a full tree of five hazards, or less of a deeper one). */
        static final int MAX_IDENTITY_RUNS = 63;

        RefSession(RefEngine engine, WorkerContext ctx) {
            this.engine = engine;
            this.ctx = ctx;
            this.kernel = new RefKernel(ctx, engine.config, engine.options);
        }

        @Override
        public int check(int cid, Budget budget) {
            units++;
            lastReason = null;
            lastMessage = null;
            int kind = ctx.store.decls().kind(cid);
            if (kind != DeclKind.AXIOM && kind != DeclKind.DEFN && kind != DeclKind.THM && kind != DeclKind.OPAQUE) {
                lastReason = Reason.UNSUPPORTED_INPUT;
                lastMessage = "constant " + cid + " of kind " + kind + " is not checked by an engine";
                declined++;
                return Outcome.DECLINE;
            }
            long hazards = kernel.identityHazards();
            int outcome;
            if (engine.options.identity == RefOptions.IDENTITY_DECLINE) {
                identityRuns = 0;
                outcome = decide(cid, budget, 0L, 0);
                kernel.setIdentityScript(0L, -1);
            } else {
                outcome = attemptOnAnyStack(cid, budget);
            }
            if (kernel.identityHazards() != hazards) {
                identityUnits++;
            }
            if (outcome == Outcome.ACCEPT) {
                accepted++;
            } else if (outcome == Outcome.REJECT) {
                rejected++;
            } else {
                declined++;
            }
            return outcome;
        }

        /**
         * Strict mode. The declaration is checked with the given decisions for its first
         * {@code length} object-identity hazards (README, "Object identity"). If the check meets a
         * further hazard, it is run again with that hazard decided each way: whether two occurrences
         * of a term are one object for the official kernel is not known here, but if every decision
         * leads to the same verdict, that verdict is the official one whichever it is. If the two
         * verdicts differ, or the tree of decisions grows beyond {@link #MAX_IDENTITY_RUNS} checks,
         * the declaration is declined.
         */
        private int decide(int cid, Budget budget, long decisions, int length) {
            identityRuns++;
            lastReason = null;
            lastMessage = null;
            kernel.setIdentityScript(decisions, length);
            int outcome = attemptOnAnyStack(cid, budget);
            if (outcome != Outcome.DECLINE || lastReason != Reason.UNSUPPORTED_INPUT || !kernel.takeIdentityFork()) {
                return outcome;
            }
            if (identityRuns + 2 > MAX_IDENTITY_RUNS) {
                lastMessage = lastMessage + "; more than " + MAX_IDENTITY_RUNS + " checks would be needed to try every case";
                return Outcome.DECLINE;
            }
            identityForks++;
            int same = decide(cid, budget, decisions, length + 1);
            if (same == Outcome.DECLINE) {
                return same;
            }
            int other = decide(cid, budget, decisions | 1L << length, length + 1);
            if (other == Outcome.DECLINE || other == same) {
                return other;
            }
            lastReason = Reason.UNSUPPORTED_INPUT;
            lastMessage = "UNSUPPORTED_INPUT at " + nameOf(cid) + ": the official verdict depends on object identity: "
                    + Outcome.name(same) + " if the two occurrences of a stuck projection that is_def_eq_core meets are one"
                    + " object for the official kernel, " + Outcome.name(other) + " if they are two (the second whnf_core"
                    + " answers with the cache entry of the other occurrence and the comparison runs once more); whether"
                    + " they are is decided by the sharing of the export's records and the kernel's own allocation,"
                    + " not by the terms";
            return Outcome.DECLINE;
        }

        private String nameOf(int cid) {
            StringBuilder sb = new StringBuilder("'");
            try {
                ctx.store.names().appendTo(sb, ctx.store.decls().name(cid));
            } catch (RuntimeException e) {
                sb.append("constant ").append(cid);
            }
            return sb.append('\'').toString();
        }

        /** One check of the declaration, repeated on the deep stack if the caller's stack is too small. */
        private int attemptOnAnyStack(int cid, Budget budget) {
            int outcome = attempt(cid, budget);
            if (outcome == Outcome.DECLINE && lastReason == Reason.STACK_DEPTH && kernel.takeOverflowed()) {
                // The calling thread's stack was too small. Whatever was being written when it
                // overflowed is abandoned, so everything worker-local starts afresh.
                ctx.reset();
                kernel.reset();
                if (deep == null) {
                    deep = new DeepStack("rlvm-ref-deep-" + ctx.workerId);
                }
                deepRuns++;
                taskCid = cid;
                taskBudget = budget;
                deep.run(this);
                outcome = taskOutcome;
                if (outcome != Outcome.ACCEPT && kernel.takeOverflowed()) {
                    ctx.reset();
                    kernel.reset();
                }
            }
            return outcome;
        }

        /** The body of a deep-stack run. */
        @Override
        public void run() {
            taskOutcome = attempt(taskCid, taskBudget);
        }

        private int attempt(int cid, Budget budget) {
            long mark = 0L;
            long litMark = 0L;
            boolean marked = false;
            boolean accepted = false;
            LitPool pool = ctx.lits != null && ctx.lits.isWorkerPool() ? ctx.lits : null;
            try {
                ctx.terms.refresh();
                if (marksSupported) {
                    try {
                        mark = ctx.overlay.mark();
                        if (pool != null) {
                            litMark = pool.mark();
                        }
                        marked = true;
                    } catch (UnsupportedOperationException e) {
                        marksSupported = false;
                    }
                }
                kernel.setBudget(budget);
                kernel.checkConstant(cid);
                accepted = true;
                return Outcome.ACCEPT;
            } catch (KernelException e) {
                fail(cid, e.reason(), e.term1(), e.term2(), null);
                return Outcome.REJECT;
            } catch (ResourceException e) {
                fail(cid, e.reason(), 0, 0, null);
                return Outcome.DECLINE;
            } catch (RuntimeException e) {
                fail(cid, Reason.INTERNAL_ERROR, 0, 0, e);
                return Outcome.DECLINE;
            } finally {
                // Nothing the declaration learned survives it: the caches go, and so do the terms and the
                // literals it built.
                boolean overflowed = kernel.peekOverflowed();
                if (engine.config.stats && !overflowed) {
                    maxUnitBytes = Math.max(maxUnitBytes, ctx.footprintBytes() + kernel.tableBytes());
                }
                if (overflowed || !marked) {
                    // The overlay may have been abandoned mid-update (check() resets the context), or it
                    // cannot be cut back: the session's imports are not kept either.
                    kernel.reset();
                } else {
                    kernel.endDeclaration();
                    boolean cut = false;
                    try {
                        long litsBefore = pool == null ? 0L : pool.mark();
                        ctx.overlay.resetTo(mark);
                        if (pool != null) {
                            // The literals the declaration computed go with the terms that mention them.
                            // (Overlay.resetTo may already have done it; then this is a no-op.)
                            pool.truncateTo(litMark);
                            if (litMark != litsBefore) {
                                // NatBinding caches rlvm.nat handles by pool reference; a pool that
                                // reissues truncated references (the store before wave 4) must not meet
                                // a cache that still knows them. Cheap, and right for a pool that
                                // retires them as well.
                                ctx.nats.reset();
                            }
                        }
                        ctx.terms.refresh();
                        cut = true;
                    } catch (UnsupportedOperationException e) {
                        marksSupported = false;
                    }
                    if (cut) {
                        kernel.promoteImports(accepted);
                    } else {
                        kernel.reset();
                    }
                }
            }
        }

        private void fail(int cid, Reason reason, int term1, int term2, RuntimeException cause) {
            lastReason = reason;
            StringBuilder sb = new StringBuilder();
            sb.append(reason.name());
            try {
                sb.append(" at '");
                ctx.store.names().appendTo(sb, ctx.store.decls().name(cid));
                sb.append('\'');
            } catch (RuntimeException e) {
                sb.append(" at constant ").append(cid);
            }
            if (term1 != 0) {
                sb.append(": ").append(kernel.describe(term1));
                if (term2 != 0) {
                    sb.append(" vs ").append(kernel.describe(term2));
                }
            }
            if (reason == Reason.BUDGET_STEPS && kernel.takeCycled()) {
                sb.append(": the reduction returns to a term it started from; the official kernel does not"
                        + " terminate here");
            }
            if (reason == Reason.UNSUPPORTED_INPUT && kernel.takeIdentityDeclined()) {
                sb.append(": the official verdict depends on object identity here (is_def_eq_core repeats a"
                        + " comparison when the second whnf_core answers a stuck projection with the cache"
                        + " entry of another occurrence of the same term; whether two occurrences are one"
                        + " object is not a property of the terms)");
            }
            if (cause != null) {
                sb.append(": ").append(cause);
                StackTraceElement[] st = cause.getStackTrace();
                for (int i = 0; i < st.length && i < 6; i++) {
                    sb.append(" < ").append(st[i]);
                }
            }
            lastMessage = sb.toString();
        }

        @Override
        public Reason lastReason() {
            return lastReason;
        }

        @Override
        public String lastMessage() {
            return lastMessage;
        }

        @Override
        public KernelOps ops() {
            return kernel;
        }

        @Override
        public void reset() {
            kernel.reset();
        }

        @Override
        public long footprintBytes() {
            return ctx.footprintBytes() + kernel.tableBytes();
        }

        @Override
        public long steps() {
            return kernel.steps();
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (deep != null) {
                deep.close();
                deep = null;
            }
            engine.account(this);
        }
    }
}
