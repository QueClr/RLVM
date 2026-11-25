package rlvm.driver;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.locks.LockSupport;

import rlvm.admit.EnvBuilder;
import rlvm.admit.Policy;
import rlvm.api.Budget;
import rlvm.api.Engine;
import rlvm.api.EngineConfig;
import rlvm.api.EngineFactory;
import rlvm.api.KernelOps;
import rlvm.api.Outcome;
import rlvm.api.Reason;
import rlvm.api.RunSignals;
import rlvm.api.Session;
import rlvm.api.StatsSink;
import rlvm.api.WorkerContext;
import rlvm.store.Store;

/**
 * Fakes for the driver's tests: a scripted unit table, a fake admission, fake engines, and an oracle
 * that states the expected verdict independently of the driver.
 */
final class Fakes {
    private Fakes() {}

    // ---- scripted behaviours of a session on a unit --------------------------------------------------------
    /** Accept. */
    static final int A = 0;
    /** Reject. */
    static final int R = 1;
    /** Decline. */
    static final int D = 2;
    /** Throw a RuntimeException. */
    static final int T = 3;
    /** Throw an Error. */
    static final int ERR = 4;
    /** Never finish: spin until the unit lies above the first failure, then decline. */
    static final int LOOP = 5;
    /** Reject unless the session is fresh (a cache that produces a wrong rejection). */
    static final int WARMTH = 6;
    /** Grow by {@code footprint} bytes, poll the memory signal: decline if exhausted, else accept. */
    static final int MEM = 7;
    /** Return an outcome code that does not exist. */
    static final int BAD = 8;
    /**
     * ENV only: never finish, and, like a kernel used through {@code KernelOps}, poll only the memory
     * signal (it does not know its unit, so it cannot compare it with the first failure).
     */
    static final int LOOP_BLIND = 9;
    /**
     * A heavy unit: accept, but not before unit {@code Unit.holdFor} has a final status. Meanwhile
     * it polls the signals like a session does. If {@code holdFor} lies in the rest of the claim
     * this unit is part of, it only ends when its worker gave that rest back while the unit was
     * still running; a timeout is reported as a violation.
     */
    static final int HOLD = 10;

    static final int ROLE_PRIMARY = 0;
    static final int ROLE_STRICT = 1;
    static final int ROLE_REFERENCE = 2;

    /** The script of one unit. */
    static final class Unit {
        int cls = RunHost.CHECK;
        /** CHECK: the primary engine in a warm session. */
        int warm = A;
        /** CHECK: the primary engine, fresh session, cold budget. */
        int cold = A;
        /** CHECK: the strict primary engine. */
        int strict = A;
        /** CHECK: the reference kernel. */
        int ref = A;
        /** ENV: outcome of the environment pass: A, R, D or LOOP. */
        int env = A;
        Reason rejectReason = Reason.DECL_TYPE_MISMATCH;
        /**
         * Reason of a scripted decline. {@code BUDGET_MEMORY} makes the driver treat the unit as a
         * memory retry: the following stages run while the worker holds the heavy gate.
         */
        Reason declineReason = Reason.BUDGET_STEPS;
        long footprint = 1000;
        int delayMicros;
        /** {@link #HOLD}: the unit that must be decided before this one's check returns. */
        int holdFor = -1;
        /**
         * Pipelined loading: every stage of this unit that starts while the file is not completely
         * loaded declines with FORWARD_REFERENCE, whatever the script says (a reference to a name
         * that is declared later, or nowhere); the script applies once the load is complete.
         */
        boolean late;
        /**
         * Pipelined loading, a CHECK unit that is out of file order in a way only the unit's proof
         * shows (the store's "something in the block was unresolved"): the environment pass, going
         * through the file in file order while it loads, does not see it. If the pass moves its
         * frontier past the unit before the file is complete, every stage of the unit declines with
         * FORWARD_REFERENCE for good (the admission of the real host: the unit is neither in file
         * order nor decided by the pass); if the pass meets the unit when the file is complete, it
         * decides it itself in dependency order, as in a batch run, and the script applies. The
         * driver's parking rule keeps the frontier below such a unit until the load has ended.
         */
        boolean outOfOrder;
        /** Bytes of unused capacity the lane holds after this unit was checked (until its next reset). */
        long slack;

        Unit copy() {
            Unit u = new Unit();
            u.cls = cls;
            u.warm = warm;
            u.cold = cold;
            u.strict = strict;
            u.ref = ref;
            u.env = env;
            u.rejectReason = rejectReason;
            u.declineReason = declineReason;
            u.footprint = footprint;
            u.delayMicros = delayMicros;
            u.holdFor = holdFor;
            u.late = late;
            u.outOfOrder = outOfOrder;
            u.slack = slack;
            return u;
        }

        @Override
        public String toString() {
            if (cls == RunHost.ENV) {
                return "ENV(" + env + ")";
            }
            if (cls != RunHost.CHECK) {
                return cls == RunHost.SKIP ? "SKIP" : "UNKNOWN";
            }
            return "CHECK(w" + warm + " c" + cold + " s" + strict + " r" + ref + ")";
        }
    }

    static Unit[] units(int n) {
        Unit[] u = new Unit[n];
        for (int i = 0; i < n; i++) {
            u[i] = new Unit();
        }
        return u;
    }

    static Unit[] copy(Unit[] script) {
        Unit[] u = new Unit[script.length];
        for (int i = 0; i < u.length; i++) {
            u[i] = script[i].copy();
        }
        return u;
    }

    /** A configuration for tests: small stacks, no stderr, statistics off. */
    static RunConfig config(int workers) {
        RunConfig c = new RunConfig();
        c.workers = workers;
        c.unitBytes = 1L << 30; // distinct from the cold budget (memoryLimitBytes) whatever the worker count
        c.workerStackBytes = 1L << 20;
        c.deepStackBytes = 1L << 20;
        c.explain = false;
        c.logAnomalies = false;
        return c;
    }

    // ---- the host -------------------------------------------------------------------------------------------

    static final class FakeHost implements RunHost {
        final Unit[] script;
        final int[] rank;
        volatile int loaded;
        volatile boolean complete = true;
        final AtomicInteger env = new AtomicInteger();
        volatile long shared;
        /** Bytes of the cold budget (= RunConfig.memoryLimitBytes), by which a fake session recognises a cold stage. */
        volatile long coldBytes = new RunConfig().memoryLimitBytes;
        final ConcurrentLinkedQueue<String> violations = new ConcurrentLinkedQueue<>();
        final AtomicIntegerArray warmChecks;
        final AtomicIntegerArray coldChecks;
        final AtomicIntegerArray strictChecks;
        final AtomicIntegerArray refChecks;
        final AtomicIntegerArray decided;
        final AtomicInteger lanesOpen = new AtomicInteger();
        final AtomicInteger lanesCreated = new AtomicInteger();
        final AtomicInteger sessionsOpen = new AtomicInteger();
        final AtomicInteger sessionsCreated = new AtomicInteger();
        final AtomicInteger sessionResets = new AtomicInteger();
        final AtomicInteger enginesOpen = new AtomicInteger();
        final AtomicInteger enginesCreated = new AtomicInteger();
        final AtomicInteger envPasses = new AtomicInteger();
        /** Budget of the last warm attempt. */
        volatile long warmBudgetBytes;
        volatile int warmBudgetDepth;
        /** Makes {@code newLane} throw from this many calls on (0 = never). */
        volatile int failLaneFrom;
        /** The environment frontier was moved by somebody else before the run (no listener calls). */
        volatile boolean preAdvanced;
        /** Makes {@code unitDecided} throw. */
        volatile boolean failMirror;
        /** Upper bound for a LOOP behaviour before it is reported as a hang. */
        volatile long loopTimeoutNanos = 20_000_000_000L;
        /** Pipelined loading: whether a unit the environment pass declined while loading can be decided again. */
        volatile boolean envRetryable = true;
        /** Pipelined loading: the failed attempt of the environment pass had recorded failures of other units. */
        volatile boolean envCollateral;
        /**
         * Pipelined loading: an environment unit that needs the rest of the file is not reported at
         * all; the pass returns short and waits ({@code EnvBuilder.waitingForLoad()}), instead of
         * reporting a decline that the driver withholds.
         */
        volatile boolean envSilentWait;
        final AtomicInteger envRetries = new AtomicInteger();
        /** Out-of-order units ({@code Unit.outOfOrder}) the environment pass moved past while the file was loading. */
        final AtomicInteger passedOutOfOrder = new AtomicInteger();
        /** Set per unit when that happened: the unit's stages then decline for good. */
        final java.util.concurrent.atomic.AtomicIntegerArray lostToFileOrder;
        /** False: the host does not tell the driver which units need the complete file (no parking). */
        volatile boolean tellsOutOfOrder = true;
        final AtomicInteger lateDeclines = new AtomicInteger();
        final AtomicInteger laneResets = new AtomicInteger();
        /** The lane of every context handed out, so that a session can find the snapshot it runs on. */
        final java.util.concurrent.ConcurrentHashMap<WorkerContext, FakeLane> lanes = new java.util.concurrent.ConcurrentHashMap<>();

        FakeHost(Unit[] script) {
            this.lostToFileOrder = new java.util.concurrent.atomic.AtomicIntegerArray(Math.max(1, script.length));
            this.script = script;
            this.loaded = script.length;
            this.rank = new int[script.length];
            for (int i = 0; i < rank.length; i++) {
                rank[i] = i;
            }
            int n = Math.max(1, script.length);
            warmChecks = new AtomicIntegerArray(n);
            coldChecks = new AtomicIntegerArray(n);
            strictChecks = new AtomicIntegerArray(n);
            refChecks = new AtomicIntegerArray(n);
            decided = new AtomicIntegerArray(n);
        }

        void violation(String s) {
            violations.add(s);
        }

        @Override
        public Store store() {
            return null;
        }

        @Override
        public int loadedUnits() {
            return loaded;
        }

        @Override
        public boolean loadComplete() {
            return complete;
        }

        @Override
        public int envUnits() {
            return env.get();
        }

        @Override
        public int unitClass(int unit) {
            return script[unit].cls;
        }

        @Override
        public int unitRank(int unit) {
            return rank[unit];
        }

        @Override
        public String unitName(int unit) {
            return "decl" + unit;
        }

        @Override
        public long sharedBytes() {
            return shared;
        }

        @Override
        public void unitDecided(int unit, int boardStatus) {
            if (failMirror) {
                throw new IllegalStateException("scripted mirror failure");
            }
            if (!decided.compareAndSet(unit, 0, boardStatus)) {
                violation("unit " + unit + " decided twice");
            }
        }

        @Override
        public Lane newLane(int workerId, RunSignals signals, long overlayMaxBytes) {
            int k = lanesCreated.incrementAndGet();
            if (failLaneFrom > 0 && k >= failLaneFrom) {
                throw new IllegalStateException("scripted lane failure");
            }
            lanesOpen.incrementAndGet();
            FakeLane lane = new FakeLane(this, workerId, signals);
            lanes.put(lane.ctx, lane);
            return lane;
        }

        @Override
        public int retryEnvUnit(int unit) {
            if (!envRetryable || unit != env.get() || !complete) {
                return RETRY_NO;
            }
            envRetries.incrementAndGet();
            return envCollateral ? RETRY_TAINTED : RETRY_CLEAN;
        }

        /** What a lane created or reset now can see: units below this bound. */
        int snapshotNow() {
            // complete is read first: once it is true the loaded frontier is final
            return complete ? Integer.MAX_VALUE : loaded;
        }

        /**
         * A pipelined run: the returned run's {@code execute()} publishes the script in the given
         * steps on the calling thread ({@code publications} ascending; the last step is the end of
         * the file) while the driver's threads check.
         *
         * @param pauseMicros pause before each publication
         * @param failure thrown by the load after the last publication (null: the load is complete)
         */
        Run runPipelined(RunConfig config, boolean same, final int[] publications, final int pauseMicros,
                final Exception failure) {
            return runPipelined(config, same, publications, pauseMicros, failure, null);
        }

        /**
         * @param afterFirst if not null, the loader waits after its first publication until this
         *     answers true (a test that needs something to happen while the file is still loading)
         */
        Run runPipelined(RunConfig config, boolean same, final int[] publications, final int pauseMicros,
                final Exception failure, final java.util.function.BooleanSupplier afterFirst) {
            coldBytes = config.memoryLimitBytes;
            loaded = 0;
            complete = false;
            final FakeHost h = this;
            Run.Loading loading = new Run.Loading() {
                @Override
                public void load(LoadProgress progress) throws Exception {
                    for (int i = 0; i < publications.length; i++) {
                        if (pauseMicros > 0) {
                            LockSupport.parkNanos(pauseMicros * 1000L);
                        }
                        boolean last = i == publications.length - 1;
                        if (last && failure == null) {
                            h.loaded = publications[i];
                            h.complete = true;
                        } else {
                            h.loaded = publications[i];
                        }
                        progress.published(publications[i]);
                        if (i == 0 && afterFirst != null) {
                            long t0 = System.nanoTime();
                            while (!afterFirst.getAsBoolean()) {
                                LockSupport.parkNanos(200_000);
                                if (System.nanoTime() - t0 > loopTimeoutNanos) {
                                    violation("the loader waited in vain for the checking threads");
                                    break;
                                }
                            }
                        }
                    }
                    if (failure != null) {
                        throw failure;
                    }
                }
            };
            Run run;
            if (same) {
                EngineFactory f = soloFactory();
                run = new Run(config, this, loading, f, f);
            } else {
                run = new Run(config, this, loading, primaryFactory(), referenceFactory());
            }
            run.setMinUnitsPerWorker(1);
            run.setDiagnostics(null);
            return run;
        }

        @Override
        public boolean unitNeedsCompleteFile(int unit) {
            if (unit >= loaded) {
                violation("unitNeedsCompleteFile asked for unit " + unit + " beyond the loaded frontier " + loaded);
            }
            return tellsOutOfOrder && script[unit].outOfOrder;
        }

        @Override
        public EnvPass newEnvPass(Lane lane, Session referenceSession, EnvBuilder.Listener listener) {
            envPasses.incrementAndGet();
            if (!(referenceSession instanceof FakeSession) || ((FakeSession) referenceSession).role != ROLE_REFERENCE) {
                violation("environment pass over a session that is not the reference engine's");
            }
            return new FakeEnvPass(this, ((FakeLane) lane).signals, listener);
        }

        EngineFactory primaryFactory() {
            final FakeHost h = this;
            return new EngineFactory() {
                @Override
                public Engine create(Store store, EngineConfig config) {
                    return new FakeEngine(h, config.strict ? ROLE_STRICT : ROLE_PRIMARY, config);
                }
            };
        }

        EngineFactory referenceFactory() {
            final FakeHost h = this;
            return new EngineFactory() {
                @Override
                public Engine create(Store store, EngineConfig config) {
                    return new FakeEngine(h, ROLE_REFERENCE, config);
                }
            };
        }

        /** One factory for both roles (the reference kernel as primary engine): strict = the re-check instance. */
        EngineFactory soloFactory() {
            final FakeHost h = this;
            return new EngineFactory() {
                @Override
                public Engine create(Store store, EngineConfig config) {
                    return new FakeEngine(h, config.strict ? ROLE_REFERENCE : ROLE_PRIMARY, config);
                }
            };
        }

        /** A run over this host; {@code same}: the reference kernel is the primary engine. */
        Run run(RunConfig config, boolean same) {
            coldBytes = config.memoryLimitBytes;
            Run run;
            if (same) {
                EngineFactory f = soloFactory();
                run = new Run(config, this, f, f);
            } else {
                run = new Run(config, this, primaryFactory(), referenceFactory());
            }
            run.setMinUnitsPerWorker(1);
            run.setDiagnostics(null);
            return run;
        }

        String describeViolations() {
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (String v : violations) {
                if (n++ < 10) {
                    sb.append(v).append("; ");
                }
            }
            return n == 0 ? "" : n + " violation(s): " + sb;
        }
    }

    static final class FakeLane implements RunHost.Lane {
        final FakeHost host;
        final RunSignals signals;
        final WorkerContext ctx;
        boolean closed;
        int resets;
        /** Units below this bound were published when the lane was created or last reset. */
        volatile int snapshot;
        volatile long slack;

        FakeLane(FakeHost host, int workerId, RunSignals signals) {
            this.host = host;
            this.signals = signals;
            this.ctx = new WorkerContext(workerId, null, null, null, null, null, null, null, signals);
            this.snapshot = host.snapshotNow();
        }

        @Override
        public long slackBytes() {
            return slack;
        }

        @Override
        public WorkerContext context() {
            return ctx;
        }

        @Override
        public void beginUnit() {
            if (closed) {
                host.violation("lane used after close");
            }
        }

        @Override
        public int admit(int unit, Session session, Budget budget) {
            if (closed) {
                host.violation("lane used after close");
            }
            Unit s = host.script[unit];
            if (s.cls == RunHost.ENV && host.preAdvanced && unit < host.env.get()) {
                // like rlvm.admit.Admission: the outcome the environment pass recorded
                last = null;
                recordedReason = s.env == R ? Reason.INDUCTIVE_INVALID : s.declineReason;
                return s.env == A ? Outcome.ACCEPT : s.env == R ? Outcome.REJECT : Outcome.DECLINE;
            }
            if (s.cls != RunHost.CHECK) {
                host.violation("unit " + unit + " admitted by a worker although it is not a CHECK unit");
            }
            last = session;
            if (unit >= snapshot) {
                host.violation("unit " + unit + " admitted on a lane whose snapshot ends at " + snapshot);
            }
            int o = session.check(unit, budget);
            if (s.slack > slack) {
                slack = s.slack;
            }
            return o;
        }

        private Session last;
        private Reason recordedReason;

        @Override
        public Reason lastReason() {
            return last == null ? recordedReason : last.lastReason();
        }

        @Override
        public String lastMessage() {
            return last == null ? "recorded by the environment pass" : last.lastMessage();
        }

        @Override
        public void reset() {
            resets++;
            host.laneResets.incrementAndGet();
            snapshot = host.snapshotNow();
            slack = 0;
        }

        @Override
        public void close() {
            if (closed) {
                host.violation("lane closed twice");
            }
            closed = true;
            host.lanes.remove(ctx);
            host.lanesOpen.decrementAndGet();
        }
    }

    static final class FakeEnvPass implements RunHost.EnvPass {
        final FakeHost host;
        final RunSignals signals;
        final EnvBuilder.Listener listener;
        final Thread owner = Thread.currentThread();
        boolean stuck;

        FakeEnvPass(FakeHost host, RunSignals signals, EnvBuilder.Listener listener) {
            this.host = host;
            this.signals = signals;
            this.listener = listener;
        }

        @Override
        public int advanceTo(int unit) {
            if (Thread.currentThread() != owner) {
                host.violation("environment pass used from two threads");
            }
            int u = host.env.get();
            int end = Math.min(unit, host.loaded);
            while (u < end && !stuck) {
                Unit s = host.script[u];
                if (s.cls == RunHost.ENV) {
                    int o = s.env;
                    if (s.late && !host.complete) {
                        host.lateDeclines.incrementAndGet();
                        if (host.envSilentWait) {
                            break; // nothing recorded, nothing reported: the next call decides
                        }
                        listener.unitFailed(u, Reason.FORWARD_REFERENCE, "environment unit " + u + " needs the rest of the file");
                        stuck = true;
                        break;
                    }
                    if (o == LOOP) {
                        spin(host, signals, u);
                        o = D;
                    } else if (o == LOOP_BLIND) {
                        long t0 = System.nanoTime();
                        while (!signals.memoryExhausted()) {
                            LockSupport.parkNanos(20_000);
                            if (System.nanoTime() - t0 > host.loopTimeoutNanos) {
                                host.violation("environment unit " + u + " looped without being cancelled");
                                break;
                            }
                        }
                        o = D;
                    }
                    if (o == A) {
                        listener.unitAdmitted(u);
                    } else {
                        listener.unitFailed(u, o == R ? Reason.INDUCTIVE_INVALID : s.declineReason,
                                "scripted environment failure of unit " + u);
                        stuck = true;
                        break;
                    }
                } else if (s.outOfOrder && !host.complete) {
                    // file order while the file loads: the pass does not see what the unit's proof needs
                    host.passedOutOfOrder.incrementAndGet();
                    host.lostToFileOrder.set(u, 1);
                }
                u++;
                host.env.set(u);
                listener.frontierAdvanced(u);
            }
            return host.env.get();
        }

        @Override
        public void cancel() {
            stuck = true;
        }
    }

    /**
     * Spins until {@code unit} lies above the first failure or the memory signal says "give up" (a
     * session polls both, {@code RunSignals}); a timeout is reported as a violation.
     *
     * @return true if the memory signal ended it
     */
    static boolean spin(FakeHost host, RunSignals signals, int unit) {
        long t0 = System.nanoTime();
        while (unit <= signals.firstFailure()) {
            if (signals.memoryExhausted()) {
                return true;
            }
            LockSupport.parkNanos(20_000);
            if (System.nanoTime() - t0 > host.loopTimeoutNanos) {
                host.violation("unit " + unit + " looped without being cancelled");
                return false;
            }
        }
        return false;
    }

    // ---- engines ----------------------------------------------------------------------------------------------

    static final class FakeEngine implements Engine {
        final FakeHost host;
        final int role;
        final EngineConfig config;
        boolean closed;
        boolean dumped;
        volatile int advanced;

        FakeEngine(FakeHost host, int role, EngineConfig config) {
            this.host = host;
            this.role = role;
            this.config = config;
            host.enginesCreated.incrementAndGet();
            host.enginesOpen.incrementAndGet();
            if ((role == ROLE_STRICT) != (config.strict && role != ROLE_REFERENCE)) {
                host.violation("strict flag does not match the engine's role");
            }
        }

        @Override
        public String name() {
            return role == ROLE_REFERENCE ? "fake-reference" : "fake-machine";
        }

        @Override
        public Session newSession(WorkerContext ctx) {
            if (closed) {
                host.violation("session requested from a closed engine");
            }
            if (ctx.workerId < 0 || ctx.workerId >= config.workers) {
                host.violation("workerId " + ctx.workerId + " outside the " + config.workers
                        + " sessions the engine was told to expect (role " + role + ")");
            }
            host.sessionsCreated.incrementAndGet();
            host.sessionsOpen.incrementAndGet();
            return new FakeSession(host, role, ctx.signals);
        }

        @Override
        public void environmentAdvanced(int units) {
            advanced = units;
        }

        @Override
        public void dumpStats(StatsSink sink) {
            if (host.sessionsOpen.get() != 0) {
                host.violation("dumpStats before the last session was closed");
            }
            dumped = true;
            sink.put("checks", 1L);
        }

        @Override
        public void close() {
            if (closed) {
                host.violation("engine closed twice");
            }
            closed = true;
            host.enginesOpen.decrementAndGet();
        }
    }

    static final class FakeSession implements Session {
        final FakeHost host;
        final int role;
        final RunSignals signals;
        final Thread owner = Thread.currentThread();
        long footprint;
        long steps;
        int sinceReset;
        int lastRank = -1;
        boolean closed;
        Reason reason;
        String message;

        FakeSession(FakeHost host, int role, RunSignals signals) {
            this.host = host;
            this.role = role;
            this.signals = signals;
        }

        @Override
        public int check(int cid, Budget budget) {
            final int unit = cid;
            final Unit s = host.script[unit];
            if (closed) {
                host.violation("check on a closed session");
            }
            if (Thread.currentThread() != owner) {
                host.violation("session used from a thread that did not create it");
            }
            if (unit >= host.env.get()) {
                host.violation("unit " + unit + " checked before the environment pass reached it");
            }
            boolean coldBudget = budget.maxBytes == host.coldBytes;
            int behaviour;
            if (role == ROLE_PRIMARY && !coldBudget) {
                host.warmChecks.incrementAndGet(unit);
                host.warmBudgetBytes = budget.maxBytes;
                host.warmBudgetDepth = budget.maxDepth;
                if (budget.maxSteps != Budget.UNLIMITED) {
                    host.violation("a warm attempt has a step limit");
                }
                if (host.rank[unit] <= lastRank) {
                    host.violation("warm session moved from rank " + lastRank + " to " + host.rank[unit] + " without a reset");
                }
                lastRank = host.rank[unit];
                behaviour = s.warm;
            } else {
                if (sinceReset != 0) {
                    host.violation("re-check of unit " + unit + " in a session that is not fresh (role " + role + ")");
                }
                if (!coldBudget) {
                    host.violation("re-check of unit " + unit + " without the cold budget (role " + role + ")");
                }
                if (role == ROLE_PRIMARY) {
                    host.coldChecks.incrementAndGet(unit);
                    behaviour = s.cold;
                } else if (role == ROLE_STRICT) {
                    host.strictChecks.incrementAndGet(unit);
                    behaviour = s.strict;
                } else {
                    host.refChecks.incrementAndGet(unit);
                    behaviour = s.ref;
                }
            }
            if (host.lostToFileOrder.get(unit) != 0) {
                // The environment pass went past this out-of-order unit in file order: no stage decides it.
                reason = Reason.FORWARD_REFERENCE;
                message = "unit " + unit + " is out of order and was passed in file order";
                sinceReset++;
                return Outcome.DECLINE;
            }
            if (s.late && !host.complete) {
                // The stage was started before the end of the load; role bookkeeping above still applies.
                host.lateDeclines.incrementAndGet();
                reason = Reason.FORWARD_REFERENCE;
                message = "unit " + unit + " needs the rest of the file (role " + role + ")";
                sinceReset++;
                return Outcome.DECLINE;
            }
            if (s.delayMicros > 0) {
                LockSupport.parkNanos(s.delayMicros * 1000L);
            }
            steps += 10;
            if (behaviour == WARMTH) {
                behaviour = sinceReset > 0 ? R : A;
            }
            if (behaviour == HOLD) {
                long t0 = System.nanoTime();
                for (;;) {
                    signals.memoryExhausted(); // at least one poll, after the scripted delay
                    if (host.decided.get(s.holdFor) != 0 || unit > signals.firstFailure()) {
                        break;
                    }
                    LockSupport.parkNanos(20_000);
                    if (System.nanoTime() - t0 > host.loopTimeoutNanos) {
                        host.violation("unit " + unit + " waited for unit " + s.holdFor + ", which nobody checked meanwhile");
                        break;
                    }
                }
                behaviour = A;
            }
            if (behaviour == LOOP) {
                if (spin(host, signals, unit)) {
                    reason = Reason.BUDGET_MEMORY;
                    message = "unit " + unit + " gave way to memory pressure (role " + role + ")";
                    sinceReset++;
                    return Outcome.DECLINE;
                }
                behaviour = D;
            }
            if (behaviour == MEM) {
                footprint += s.footprint;
                boolean exhausted = false;
                for (int i = 0; i < 4 && !exhausted; i++) {
                    exhausted = signals.memoryExhausted();
                }
                if (exhausted) {
                    reason = Reason.BUDGET_MEMORY;
                    message = "scripted memory exhaustion at unit " + unit;
                    sinceReset++;
                    return Outcome.DECLINE;
                }
                sinceReset++;
                return Outcome.ACCEPT;
            }
            sinceReset++;
            switch (behaviour) {
                case A:
                    footprint += s.footprint;
                    return Outcome.ACCEPT;
                case R:
                    reason = s.rejectReason;
                    message = "scripted rejection of unit " + unit + " (role " + role + ")";
                    return Outcome.REJECT;
                case D:
                    reason = s.declineReason;
                    message = "scripted decline of unit " + unit + " (role " + role + ")";
                    return Outcome.DECLINE;
                case T:
                    throw new IllegalStateException("scripted exception at unit " + unit + " (role " + role + ")");
                case ERR:
                    throw new AssertionError("scripted error at unit " + unit);
                case BAD:
                    return 7;
                default:
                    throw new IllegalArgumentException("behaviour " + behaviour);
            }
        }

        @Override
        public Reason lastReason() {
            return reason;
        }

        @Override
        public String lastMessage() {
            return message;
        }

        @Override
        public KernelOps ops() {
            return null;
        }

        @Override
        public void reset() {
            if (closed) {
                host.violation("reset of a closed session");
            }
            footprint = 0;
            sinceReset = 0;
            lastRank = -1;
            host.sessionResets.incrementAndGet();
        }

        @Override
        public long footprintBytes() {
            return footprint;
        }

        @Override
        public long steps() {
            return steps;
        }

        @Override
        public void close() {
            if (closed) {
                host.violation("session closed twice");
            }
            closed = true;
            host.sessionsOpen.decrementAndGet();
        }
    }

    // ---- oracle -----------------------------------------------------------------------------------------------

    /** The expected verdict and the unit that decides it (-1 on accept). */
    static final class Expect {
        final int verdict;
        final int unit;

        Expect(int verdict, int unit) {
            this.verdict = verdict;
            this.unit = unit;
        }

        @Override
        public String toString() {
            return Outcome.name(verdict) + "@" + unit;
        }
    }

    /**
     * The protocol table of *agentic-note*: design-architecture-document, 8.2 / 6.5 as a pure function: the final status (an
     * {@code Outcome} code) of a CHECK unit whose warm attempt ended with {@code warm} (A, R, D or T).
     */
    static int finalOf(Unit u, int warm, boolean same, boolean recheck, Policy.Unconfirmed policy) {
        switch (warm) {
            case A:
                return Outcome.ACCEPT;
            case R:
                return recheck ? confirm(u, same, policy) : Outcome.REJECT;
            case D:
            case BAD:
                if (!same) {
                    if (u.cold == A) {
                        return Outcome.ACCEPT;
                    }
                    if (u.cold == R) {
                        return recheck ? confirm(u, same, policy) : Outcome.REJECT;
                    }
                }
                return byReference(u);
            case T:
                return byReference(u);
            default:
                throw new IllegalArgumentException("warm outcome " + warm);
        }
    }

    private static int confirm(Unit u, boolean same, Policy.Unconfirmed policy) {
        if (!same && u.strict == A) {
            return Outcome.ACCEPT;
        }
        if (u.ref == R) {
            return Outcome.REJECT;
        }
        if (u.ref == A) {
            return Outcome.ACCEPT;
        }
        return policy == Policy.Unconfirmed.REJECT ? Outcome.REJECT : Outcome.DECLINE;
    }

    private static int byReference(Unit u) {
        return u.ref == A ? Outcome.ACCEPT : u.ref == R ? Outcome.REJECT : Outcome.DECLINE;
    }

    /**
     * Final status of a unit under the script. For warm behaviours whose outcome depends on the
     * session state (WARMTH, MEM) every possible warm outcome must lead to the same status;
     * otherwise the script is not a legal engine and -1 is returned.
     */
    static int finalOf(Unit u, boolean same, boolean recheck, Policy.Unconfirmed policy) {
        switch (u.cls) {
            case RunHost.SKIP:
                return Outcome.ACCEPT;
            case RunHost.ENV:
                return u.env == A ? Outcome.ACCEPT : u.env == R ? Outcome.REJECT : u.env == D ? Outcome.DECLINE : -1;
            case RunHost.CHECK:
                break;
            default:
                return Outcome.DECLINE;
        }
        switch (u.warm) {
            case A:
            case R:
            case D:
            case T:
            case BAD:
                return finalOf(u, u.warm, same, recheck, policy);
            case WARMTH: {
                int a = finalOf(u, A, same, recheck, policy);
                int r = finalOf(u, R, same, recheck, policy);
                return a == r ? a : -1;
            }
            case MEM: {
                int a = finalOf(u, A, same, recheck, policy);
                int d = finalOf(u, D, same, recheck, policy);
                return a == d ? a : -1;
            }
            default:
                return -1; // LOOP, ERR: never a final status
        }
    }

    /** The verdict rule: the status of the first unit in file order that is not accepted. */
    static Expect expect(Unit[] script, boolean same, boolean recheck, Policy.Unconfirmed policy, boolean complete) {
        for (int i = 0; i < script.length; i++) {
            int f = finalOf(script[i], same, recheck, policy);
            if (f < 0) {
                throw new IllegalArgumentException("unit " + i + " (" + script[i] + ") has no final status below the first failure");
            }
            if (f != Outcome.ACCEPT) {
                return new Expect(f, i);
            }
        }
        return complete ? new Expect(Outcome.ACCEPT, -1) : new Expect(Outcome.DECLINE, -1);
    }
}
