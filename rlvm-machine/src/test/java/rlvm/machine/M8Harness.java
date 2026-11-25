package rlvm.machine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import rlvm.admit.Admission;
import rlvm.admit.EnvBuilder;
import rlvm.admit.Policy;
import rlvm.api.Budget;
import rlvm.api.Engine;
import rlvm.api.EngineConfig;
import rlvm.api.KernelOps;
import rlvm.api.Outcome;
import rlvm.api.Reason;
import rlvm.api.RunSignals;
import rlvm.api.Session;
import rlvm.api.WorkerContext;
import rlvm.api.bind.Bindings;
import rlvm.load.ExportLoader;
import rlvm.load.LevelStoreInterner;
import rlvm.load.MalformedExportException;
import rlvm.load.NatStoreDecoder;
import rlvm.load.UnsupportedExportException;
import rlvm.ref.RefEngine;
import rlvm.store.DeclKind;
import rlvm.store.DeclTable;
import rlvm.store.Store;

/**
 * One loaded export with the reference engine and any number of machine configurations ("lanes") that
 * are run over its units in lockstep, one declaration at a time (ISA section 11: differential testing,
 * convention equivalence, tier equivalence; *agentic-note*: rep-stepper-convention, H12). Owner: M8.
 *
 * <p>The wiring is the one of {@link Fixture#load} (real loader, environment pass through
 * {@code rlvm-admit} / {@code rlvm-inductive} with the reference kernel, admission per unit), with three
 * additions that the reports and the equivalence tests need and the shared fixture does not offer:
 * every lane has its own worker context over the same store, so that two machines never see each
 * other's overlay ids; every engine check is observed (outcome, ticks, call events, frames written,
 * and, after an ACCEPT, digests of the records, runs and pairs the declaration left behind); every
 * check has a deadline that the machine sees through its 4096-tick poll (ISA 14.4), so that a looping
 * judgment ends as a DECLINE(CANCELLED) of that declaration and not as a hung report.
 *
 * <p>The comparison rule is {@link Fixture#diffAgainstReference()}'s: every unit is admitted by the
 * reference engine and by each lane; the run stops after the first unit the reference rejects (the
 * verdict of the file is decided there); a machine failure does not stop the comparison.
 * {@code M8HarnessTest} holds the two implementations against each other. One difference: a unit the
 * reference engine DECLINES (its byte budget, a limit of its own) decides nothing, so the run goes on
 * behind it, and {@link Diff} files it under "reference declined" instead of counting a machine verdict
 * there as wrong (NOTES-M8 D8; the fixture has the same rule since M6's publication of 09:31Z).
 *
 * <pre>
 * try (M8Harness h = M8Harness.load("work/arena-site/tests/good/tutorial/001_basicDef.ndjson")) {
 *     M8Harness.Lane d64 = h.lane(new M8Harness.Config("D64"));
 *     M8Harness.Lane d0 = h.lane(new M8Harness.Config("D0").hostDepth(0));
 *     while (h.next()) { ... h.unit, h.oracle, d64.obs, d0.obs, d64.m, d0.m ... }
 * }
 * </pre>
 */
final class M8Harness implements AutoCloseable {

    /** One machine configuration. */
    static final class Config {
        final String label;
        int hostDepth = Op.HOST_DEPTH;
        /** Staging switches ({@code Op.SW_*}); 0 = v0. */
        int switches;
        /** Debug modes ({@code Op.DBG_*}); the trace bit is managed by {@link #trace}. */
        int debug;
        boolean strict;
        /** Keep the frame trace (the ops of the call events) of every declaration. */
        boolean trace;
        /** Digest the records, runs and pairs after every accepted declaration. */
        boolean digest = true;
        long maxSteps = 2_000_000_000L;
        /** Byte budget of one declaration: what the driver gives a unit at one worker ({@link M8Harness#REF_BYTES}), as for the reference engine. */
        long maxBytes = REF_BYTES;
        /** Wall-clock limit of one engine check; the machine notices it at its next poll. */
        long checkMillis = 120_000;
        /**
         * Session cap between units (ISA 1.6; the driver's {@code -X session-mib}): a lane whose session
         * footprint passes it after a unit is reset. Only a warm lane ({@code Op.SW_WARM}) keeps a session
         * across declarations at all.
         */
        long sessionBytes = SESSION_BYTES;
        /**
         * With {@link #trace}: keep only the number of call events and a digest of their ops
         * ({@link Obs#traceLen}, {@link Obs#traceDigest}), not the ops themselves (4 bytes per call event:
         * 400 MB for one declaration of perf/app-lam, per lane).
         */
        boolean traceDigestOnly;
        /**
         * With {@link #digest}: digest after an ACCEPT only what the declaration added to the session (records,
         * run words, pair-log entries since the previous digest) and the whole session once, when the lane is
         * about to reset it ({@link Lane#sessionDigest}). In v0 a session is one declaration and the two are
         * the same; a warm session of 4 MiB digested as a whole after each of Init's 58,000 declarations would
         * cost more than the checks. Memo words that a later declaration writes into earlier records are then
         * compared at the reset instead of at once.
         */
        boolean incrementalDigest;
        /** Test seam: the engine check with this index (0 = the lane's first) ends as if the Java heap were exhausted; -1 = never. */
        long simulateHeapExhaustionAtCheck = -1;

        Config(String label) {
            this.label = label;
        }

        Config hostDepth(int d) {
            hostDepth = d;
            return this;
        }

        Config switches(String names) {
            switches = Debug.parseSwitches(names);
            return this;
        }

        Config debug(String names) {
            debug = Debug.parseModes(names);
            return this;
        }

        Config trace(boolean on) {
            trace = on;
            return this;
        }

        Config digest(boolean on) {
            digest = on;
            return this;
        }

        Config maxSteps(long steps) {
            maxSteps = steps;
            return this;
        }

        Config checkMillis(long ms) {
            checkMillis = ms;
            return this;
        }

        Config sessionBytes(long bytes) {
            sessionBytes = bytes;
            return this;
        }

        Config traceDigestOnly(boolean on) {
            traceDigestOnly = on;
            return this;
        }

        Config incrementalDigest(boolean on) {
            incrementalDigest = on;
            return this;
        }

        /** A copy with another label (the lanes of a gate share their limits and differ in bound, switches, debug modes). */
        Config copy(String newLabel) {
            Config c = new Config(newLabel);
            c.hostDepth = hostDepth;
            c.switches = switches;
            c.debug = debug;
            c.strict = strict;
            c.trace = trace;
            c.digest = digest;
            c.maxSteps = maxSteps;
            c.maxBytes = maxBytes;
            c.checkMillis = checkMillis;
            c.sessionBytes = sessionBytes;
            c.traceDigestOnly = traceDigestOnly;
            c.incrementalDigest = incrementalDigest;
            return c;
        }

        @Override
        public String toString() {
            return label + " (hostDepth " + (hostDepth == Op.HOST_DEPTH_UNBOUNDED ? "unbounded" : Integer.toString(hostDepth))
                    + ", switches [" + Debug.switchNames(switches) + "]" + (debug != 0 ? ", debug [" + Debug.modeNames(debug) + "]" : "")
                    + (strict ? ", strict" : "") + ((switches & Op.SW_WARM) != 0 ? ", session cap " + (sessionBytes >> 20) + " MiB" : "") + ")";
        }
    }

    /** What one engine check of one constant showed (ISA 1.3 "what is observable"). */
    static final class Obs {
        int cid;
        int outcome;
        Reason reason;
        /** Ticks the check used (ISA 1.4). */
        long ticks;
        /** Call events (H4 step 2b), in total and by op. */
        long calls;
        long[] callsByOp;
        /** Frames written to the side buffer and judgments that became a frame before they started (H3, H4). */
        long framesWritten;
        long spills;
        long nanos;
        /** The ops of the call events in order ({@link Config#trace} without {@link Config#traceDigestOnly}), else null. */
        int[] trace;
        /** With {@link Config#trace}: the number of call events recorded and a digest of their ops in order. */
        int traceLen;
        long traceDigest;
        /** Machine error code of an outcome other than ACCEPT ({@code MachineErrors.*}); 0 after an ACCEPT. */
        int code;
        /** The Java heap ended this check (an {@code OutOfMemoryError}, or the machine's own heap test refused a growth): no verdict of the machine. */
        boolean memory;
        /** After an ACCEPT with {@link Config#digest}: sizes and digests of what the declaration left in the session. */
        boolean hasDigest;
        int records;
        int runWords;
        int pairs;
        long recordDigest;
        long runDigest;
        long pairDigest;
        /** Order-independent digest of the pair entries. */
        long pairSetDigest;
        /** The host stack overflowed in a lane without a depth bound: the declaration is not comparable. */
        boolean overflow;
    }

    /** The check deadline as the machine's poll sees it: an earlier unit "failed" once the time is up. */
    static final class Deadline implements RunSignals {
        volatile long atNanos = Long.MAX_VALUE;
        volatile boolean cancelled;
        volatile boolean fired;

        void arm(long millis) {
            fired = false;
            atNanos = millis <= 0 ? Long.MAX_VALUE : System.nanoTime() + millis * 1_000_000L;
        }

        void disarm() {
            atNanos = Long.MAX_VALUE;
        }

        @Override
        public int firstFailure() {
            if (cancelled || System.nanoTime() - atNanos > 0) {
                fired = true;
                return Integer.MIN_VALUE; // below every unit, also the -1 of a judgment run outside a declaration
            }
            return Integer.MAX_VALUE;
        }

        @Override
        public boolean memoryExhausted() {
            return false;
        }
    }

    /** A machine session run in lockstep with the reference engine. It is the {@link Session} admission talks to. */
    final class Lane implements Session {
        final Config config;
        final int workerId;
        // not final: a lane whose check ran out of Java heap is rebuilt (recycle), a finished lane lets go (release)
        WorkerContext ctx;
        MachineEngine engine;
        MachineSession session;
        Machine m;
        Admission admission;
        final Deadline deadline = new Deadline();

        /** Of the unit run last: the admission outcome, and the engine check if admission reached the engine. */
        int outcome;
        Reason reason;
        String message = "";
        Obs obs;

        // totals over the run
        long units;
        long checks;
        long accepted;
        long rejected;
        long declined;
        long ticks;
        long calls;
        long framesWritten;
        long spills;
        long nanos;
        long cancelled;
        long exceptions;
        long overflows;
        private Reason overflowReason;
        private String overflowMessage;
        final long[] callsByOp = new long[Op.OP_COUNT];

        /** Units whose check the Java heap ended (see {@link Obs#memory}): the lane's answers there are no verdicts of the machine. */
        long memoryFailures;
        /** Largest session footprint seen after a unit. */
        long peakFootprint;
        private boolean recycleAfterUnit;

        Lane(Config config, int workerId) {
            this.config = config;
            this.workerId = workerId;
            build();
        }

        private void build() {
            synchronized (M8Harness.this) {
                // one at a time: a lane takes its worker context from the bindings (level and number overlays)
                this.ctx = bindings.newWorkerContext(workerId, store, deadline, REF_BYTES);
            }
            this.engine = new MachineEngine(store, config.strict ? EngineConfig.defaults().withStrict(true) : EngineConfig.defaults());
            this.session = (MachineSession) engine.newSession(ctx);
            this.m = session.m;
            m.hostDepth = config.hostDepth;
            m.sw = config.strict ? 0 : config.switches;
            m.debug = config.debug | (config.trace ? Op.DBG_TRACE : 0);
            m.stats = true;
            // PCHK (switch pchk): the store's shared check facts would leak from one lane into the next (a lane that
            // runs after another would find every closed term checked and tick less, which is no convention
            // difference). Every lane therefore has a table of its own and starts from none, as a run of one worker
            // does; the sharing between workers is the pipeline part's and the mutant campaign's to test.
            m.x5.shared = new rlvm.store.SharedFacts(store.terms());
            this.admission = new Admission(ctx, Policy.arena(), env);
        }

        /**
         * A new machine, session and worker context for this lane; the old ones (and every array they grew)
         * become garbage. After an {@code OutOfMemoryError} inside a check nothing of the old machine is trusted.
         */
        void recycle() {
            try {
                session.close();
            } catch (RuntimeException | OutOfMemoryError e) {
                // the old session is dropped whatever it says
            }
            ctx = null;
            engine = null;
            session = null;
            m = null;
            admission = null;
            build();
            recycleAfterUnit = false;
            resetAfterUnit = false;
            digResets = -1;
        }

        /** The lane is finished: its machine, session and context are let go (the totals stay). */
        void release() {
            if (session != null) {
                try {
                    session.close();
                } catch (RuntimeException | OutOfMemoryError e) {
                    // nothing to report: the lane is over
                }
            }
            ctx = null;
            engine = null;
            session = null;
            m = null;
            admission = null;
        }

        void runUnit(int u) {
            obs = null;
            message = "";
            reason = null;
            try {
                outcome = admission.admit(u, this, new Budget(config.maxSteps, config.maxBytes, Integer.MAX_VALUE));
                if (outcome != Outcome.ACCEPT) {
                    reason = admission.lastReason();
                    message = String.valueOf(admission.lastMessage());
                }
            } catch (RuntimeException e) {
                outcome = Outcome.DECLINE;
                reason = Reason.INTERNAL_ERROR;
                message = "exception in admission: " + e;
                exceptions++;
            } catch (OutOfMemoryError e) {
                // outside Session.check (structural checks, the message of a failure): the harness's heap, not a verdict
                outcome = Outcome.DECLINE;
                reason = Reason.BUDGET_MEMORY;
                message = HEAP_MESSAGE + " in admission";
                memoryFailures++;
                recycleAfterUnit = true;
            }
            if (obs != null && obs.memory && outcome == Outcome.DECLINE && !message.startsWith(HEAP_MESSAGE)) {
                message = HEAP_MESSAGE + ": " + message;
            } else if (outcome == Outcome.DECLINE && message.contains(MACHINE_HEAP_MESSAGE)) {
                // Machine.heapAllows refused a growth: how much heap is free depends on what the other lanes hold
                if (obs != null) {
                    obs.memory = true;
                }
                memoryFailures++;
            }
            units++;
            if (recycleAfterUnit) {
                return;
            }
            long footprint = session.footprintBytes();
            if (footprint > peakFootprint) {
                peakFootprint = footprint;
            }
            sessionDigest = 0;
            if (outcome != Outcome.ACCEPT || footprint > config.sessionBytes) {
                // as the driver does (and Fixture.diffAgainstReference): nothing of a failed unit survives
                resetAfterUnit = true;
                if (outcome == Outcome.ACCEPT && config.digest && config.incrementalDigest) {
                    sessionDigest = sessionDigestNow();
                }
            }
        }

        /** With {@link Config#incrementalDigest}: the digest of the whole session, taken when the unit run last made the lane reset it; else 0. */
        long sessionDigest;
        private long digResets = -1;
        private int digVp;
        private int digRsp;
        private int digPlog;

        /** Every record word, run word and pair entry (as a set) of the live session in one number; never 0. */
        long sessionDigestNow() {
            Obs o = new Obs();
            digest(m, o);
            long d = o.recordDigest * 31 + o.runDigest * 17 + o.pairSetDigest + o.records + ((long) o.runWords << 20) + ((long) o.pairs << 40);
            return d == 0 ? 1 : d;
        }

        /** {@link Config#incrementalDigest}: what the declaration added since the previous digest of this session. */
        private void digestSince(Obs o) {
            if (m.resets != digResets || m.vp < digVp || m.rsp < digRsp || m.plogN < digPlog) {
                digResets = m.resets;
                digVp = m.vbase;
                digRsp = m.rbase;
                digPlog = 0;
            }
            o.hasDigest = true;
            o.records = m.vp - digVp;
            o.runWords = m.rsp - digRsp;
            o.pairs = m.plogN - digPlog;
            o.recordDigest = digest(m.vh, digVp << Layout.REC_SHIFT, m.vp << Layout.REC_SHIFT) ^ digVp;
            o.runDigest = digest(m.rs, digRsp, m.rsp) ^ digRsp;
            long h = 0x9E3779B97F4A7C15L;
            long set = 0;
            int[] pair = m.pair;
            for (int i = digPlog; i < m.plogN; i++) {
                int e = m.plog[i] << Layout.PAIR_SHIFT;
                long x = mix(mix(mix(0x1234567L, pair[e + Layout.PAIR_LO]), pair[e + Layout.PAIR_HI]), pair[e + Layout.PAIR_STATUS]);
                h = mix(h, (int) x) ^ (x >>> 32);
                set += x * 0xD6E8FEB86659FD93L;
            }
            o.pairDigest = h;
            o.pairSetDigest = set;
            digVp = m.vp;
            digRsp = m.rsp;
            digPlog = m.plogN;
        }

        boolean resetAfterUnit;

        /** Called by the harness when every comparison of the unit has looked at the live machines. */
        void endUnit() {
            if (recycleAfterUnit) {
                recycle();
            } else if (resetAfterUnit) {
                session.reset();
                ctx.reset();
                resetAfterUnit = false;
            }
            if (m.trace.length > TRACE_KEEP) {
                m.trace = new int[0]; // the trace of one huge declaration is not kept for the small ones behind it
            }
        }

        // ---- Session: the observed engine check --------------------------------------------------------

        @Override
        public int check(int cid, Budget budget) {
            Obs o = new Obs();
            o.cid = cid;
            long ticks0 = session.steps();
            long frames0 = m.framesWritten;
            long spills0 = m.spills;
            long[] calls0 = m.calls.clone();
            m.traceN = 0;
            deadline.arm(config.checkMillis);
            long t0 = System.nanoTime();
            int out;
            overflowReason = null;
            try {
                if (config.simulateHeapExhaustionAtCheck == checks) {
                    throw new OutOfMemoryError("simulated by the test");
                }
                out = session.check(cid, budget);
            } catch (StackOverflowError e) {
                // Only a lane that runs plain host recursion (a test configuration, H9) may run out of host
                // stack; under a bound it is a defect of the machine and is not caught (ISA 12 R11).
                if (config.hostDepth <= 4 * Op.HOST_DEPTH) {
                    throw e;
                }
                session.reset();
                out = Outcome.DECLINE;
                overflowReason = Reason.STACK_DEPTH;
                overflowMessage = "host stack overflow under plain host recursion (hostDepth unbounded)";
                o.overflow = true;
                overflows++;
            } catch (OutOfMemoryError e) {
                // The harness runs several engines in one Java heap (the reference kernel, the other lanes, other
                // files of a parallel report), the launcher one: an exhausted heap here says nothing about the
                // machine. The check counts as a decline for memory that is flagged (Obs.memory), the lane gets a
                // new machine after the unit, and a report runs such a file again on its own.
                m.trace = new int[0];
                out = Outcome.DECLINE;
                overflowReason = Reason.BUDGET_MEMORY;
                overflowMessage = HEAP_MESSAGE;
                o.memory = true;
                memoryFailures++;
                recycleAfterUnit = true;
            } finally {
                o.nanos = System.nanoTime() - t0;
                deadline.disarm();
            }
            o.outcome = out;
            o.reason = lastReason();
            o.code = out == Outcome.ACCEPT || overflowReason != null ? 0 : session.lastCode();
            if (o.memory) {
                // nothing else of this check is observable: the machine is replaced
                o.callsByOp = new long[Op.OP_COUNT];
                checks++;
                declined++;
                this.nanos += o.nanos;
                obs = o;
                return out;
            }
            o.ticks = session.steps() - ticks0;
            o.framesWritten = m.framesWritten - frames0;
            o.spills = m.spills - spills0;
            o.callsByOp = new long[Op.OP_COUNT];
            for (int op = 0; op < Op.OP_COUNT; op++) {
                long n = m.calls[op] - calls0[op];
                o.callsByOp[op] = n;
                o.calls += n;
                callsByOp[op] += n;
            }
            if (config.trace) {
                o.traceLen = m.traceN;
                o.traceDigest = digest(m.trace, 0, m.traceN);
                if (!config.traceDigestOnly) {
                    o.trace = Arrays.copyOf(m.trace, m.traceN);
                }
            }
            if (out == Outcome.ACCEPT && config.digest) {
                if (config.incrementalDigest) {
                    digestSince(o);
                } else {
                    digest(m, o);
                }
            }
            checks++;
            if (out == Outcome.ACCEPT) {
                accepted++;
            } else if (out == Outcome.REJECT) {
                rejected++;
            } else {
                declined++;
                if (o.reason == Reason.CANCELLED) {
                    cancelled++;
                }
            }
            this.ticks += o.ticks;
            this.calls += o.calls;
            this.framesWritten += o.framesWritten;
            this.spills += o.spills;
            this.nanos += o.nanos;
            obs = o;
            return out;
        }

        @Override
        public Reason lastReason() {
            return overflowReason != null ? overflowReason : session.lastReason();
        }

        @Override
        public String lastMessage() {
            return overflowReason != null ? overflowMessage : session.lastMessage();
        }

        @Override
        public KernelOps ops() {
            return session.ops();
        }

        @Override
        public void reset() {
            session.reset();
        }

        @Override
        public long footprintBytes() {
            return session.footprintBytes();
        }

        @Override
        public long steps() {
            return session.steps();
        }

        @Override
        public void close() {
            if (session != null) {
                session.close();
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------

    final String path;
    Store store;
    private Bindings bindings;
    private EnvBuilder env;
    private Engine refEngine;
    private Session refSession;
    private WorkerContext refCtx;
    private Admission refAdmission;
    private final Deadline refDeadline = new Deadline();
    final List<Lane> lanes = new ArrayList<>();

    /** ACCEPT, or what the loader said about the file (REJECT: malformed; DECLINE: unsupported). */
    int loadOutcome = Outcome.ACCEPT;
    String loadMessage = "";
    long loadNanos;

    /** Default session cap between units (ISA 1.6), {@code -Dm8.sessionMib=N} (16, the driver's default; the machine's warm sessions are measured with 4). */
    static final long SESSION_BYTES = (long) Integer.getInteger("m8.sessionMib", 16) << 20;
    /** Session cap of the reference engine between units. */
    long sessionBytes = SESSION_BYTES;
    /** A trace array longer than this (call events) is given back after the unit. */
    static final int TRACE_KEEP = 1 << 22;
    /** How the message of a check that the Java heap ended starts ({@link Obs#memory}). */
    static final String HEAP_MESSAGE = "harness: the Java heap of the test JVM is exhausted (OutOfMemoryError); not a verdict of the machine";
    /** What {@code Machine.heapAllows} says when it refuses a growth (the limit detail of an E_MEM decline). */
    static final String MACHINE_HEAP_MESSAGE = "the Java heap: a session array";
    /** Largest session footprint of the reference engine after a unit. */
    long refPeakFootprint;
    /** The reference engine ran out of Java heap on some unit (its DECLINE there is the harness's, not its own). */
    boolean refMemory;
    /**
     * Byte budget of the reference engine per unit and cap of its term overlay: what the driver gives it at
     * one worker (6 GiB; perf/app-lam needs 1.2 GB of overlay, perf/magma-list-deep-n36 1.7 GB), heap
     * permitting (at most 60% of the JVM's maximum heap).
     */
    static final long REF_BYTES = Math.min(6L << 30, Runtime.getRuntime().maxMemory() / 10 * 6);
    /** The reference engine's byte budget per unit in this run. */
    long refBytes = REF_BYTES;
    /** Test seam: engine units on which an ACCEPT of the reference engine is reported as a DECLINE (as if its budget had run out). */
    java.util.function.IntPredicate refDeclines;
    /** Wall-clock limit of one unit in the reference engine (0 = none). */
    long refCheckMillis = 600_000;

    // the unit run last
    int unit = -1;
    int units;
    /** Outcome of the reference engine on {@link #unit}. */
    int oracle;
    Reason oracleReason;
    String oracleMessage = "";
    long oracleNanos;
    long oracleTotalNanos;
    private boolean finished;

    private M8Harness(String path) {
        this.path = path;
    }

    /** Loads an export (path relative to the project root) and prepares the reference engine. */
    static M8Harness load(String path) throws IOException {
        M8Harness h = new M8Harness(path);
        Path file = Fixture.root().resolve(path);
        long t0 = System.nanoTime();
        Bindings bindings = new Bindings(256);
        Store st = new Store(Files.size(file), bindings.baseLists(), bindings.baseLevelInfo());
        bindings.bindNames(st.names());
        try {
            ExportLoader.load(file, st, new LevelStoreInterner(bindings.levelBase(), false), new NatStoreDecoder());
        } catch (MalformedExportException e) {
            h.loadOutcome = Outcome.REJECT;
            h.loadMessage = "loader: " + e.getMessage();
            h.finished = true;
            return h;
        } catch (UnsupportedExportException e) {
            h.loadOutcome = Outcome.DECLINE;
            h.loadMessage = "loader: " + e.getMessage();
            h.finished = true;
            return h;
        } finally {
            h.loadNanos = System.nanoTime() - t0;
        }
        bindings.freeze();
        h.bindings = bindings;
        h.store = st;
        h.units = st.loadedUnits();
        h.refCtx = bindings.newWorkerContext(0, st, h.refDeadline, REF_BYTES);
        h.refEngine = RefEngine.FACTORY.create(st, EngineConfig.defaults());
        h.refSession = h.refEngine.newSession(h.refCtx);
        h.env = new EnvBuilder(st, h.refSession.ops(), Policy.arena(), null);
        h.refAdmission = new Admission(h.refCtx, Policy.arena(), h.env);
        return h;
    }

    /** Adds a machine configuration. All lanes are added before the first {@link #next()}. */
    Lane lane(Config config) {
        if (unit >= 0) {
            throw new IllegalStateException("lanes are added before the first unit is run");
        }
        if (loadOutcome != Outcome.ACCEPT) {
            throw new IllegalStateException("the export was not loaded: " + loadMessage);
        }
        Lane lane = new Lane(config, lanes.size() + 1);
        lanes.add(lane);
        return lane;
    }

    // ---- pass mode: the reference engine first, then every lane on its own ------------------------------------

    private int passLanes;

    /**
     * Ends the reference pass of pass mode ({@code while (h.next()) ..} without a lane): the reference session and
     * its context are emptied, so that what the reference kernel held (1.7 GB of overlay after
     * perf/magma-list-deep-n36) is free before the first lane runs. The environment pass stays as it is: it has
     * decided every unit up to {@link #unit}, and {@code EnvBuilder.advanceTo} returns at once for those.
     */
    void endReferencePass() {
        if (!lanes.isEmpty()) {
            throw new IllegalStateException("pass mode has no lockstep lane");
        }
        finished = true;
        if (refSession != null) {
            refSession.reset();
            refCtx.reset();
        }
    }

    /**
     * A lane of pass mode: created after {@link #endReferencePass()}, it runs the units {@code [0, unit]} alone
     * ({@link Lane#runUnit}, the caller's observation, {@link Lane#endUnit}), on any one thread; several pass
     * lanes may run at the same time on threads started after the reference pass (they share the frozen store
     * and the finished environment pass, nothing else). It is not in {@link #lanes}; the caller releases it.
     */
    Lane passLane(Config config) {
        if (!finished) {
            throw new IllegalStateException("pass lanes are created after endReferencePass()");
        }
        if (loadOutcome != Outcome.ACCEPT) {
            throw new IllegalStateException("the export was not loaded: " + loadMessage);
        }
        int id;
        synchronized (this) {
            id = ++passLanes;
        }
        return new Lane(config, id);
    }

    /**
     * Runs the next unit in the reference engine and in every lane. The lanes' machines keep the state
     * the unit left until the next call, so that the caller can compare them.
     *
     * @return false when there is no further unit (the end of the file, or the unit after the first one
     *     the reference did not accept)
     */
    /** Package E1, a measurement aid ({@code -De1.reflog=FILE}): unit, thread CPU ns and wall ns of the reference engine's admission of every unit. */
    private static final String E1_REFLOG = System.getProperty("e1.reflog", "");
    private static java.io.Writer e1RefOut;

    private static synchronized void e1RefLog(int unit, long cpu, long wall) {
        try {
            if (e1RefOut == null) {
                e1RefOut = new java.io.BufferedWriter(new java.io.FileWriter(E1_REFLOG, true), 1 << 16);
                final java.io.Writer w = e1RefOut;
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        w.flush();
                    } catch (java.io.IOException e) {
                        // a measurement aid
                    }
                }));
            }
            e1RefOut.write(unit + "\t" + cpu + "\t" + wall + "\n");
        } catch (java.io.IOException e) {
            // a measurement aid
        }
    }

    boolean next() {
        for (Lane lane : lanes) {
            lane.endUnit();
        }
        if (finished || unit + 1 >= units) {
            finished = true;
            return false;
        }
        int u = ++unit;
        refDeadline.arm(refCheckMillis);
        long c0 = E1_REFLOG.isEmpty() ? 0 : java.lang.management.ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
        long t0 = System.nanoTime();
        try {
            oracle = refAdmission.admit(u, refSession, Budget.cold(refBytes));
            oracleReason = refAdmission.lastReason();
            oracleMessage = oracle == Outcome.ACCEPT ? "" : String.valueOf(refAdmission.lastMessage());
        } catch (RuntimeException e) {
            oracle = Outcome.DECLINE;
            oracleReason = Reason.INTERNAL_ERROR;
            oracleMessage = "exception in the reference engine: " + e;
        } catch (OutOfMemoryError e) {
            oracle = Outcome.DECLINE;
            oracleReason = Reason.BUDGET_MEMORY;
            oracleMessage = HEAP_MESSAGE + " (reference engine)";
            refMemory = true;
        } finally {
            oracleNanos = System.nanoTime() - t0;
            oracleTotalNanos += oracleNanos;
            refDeadline.disarm();
            if (!E1_REFLOG.isEmpty()) {
                e1RefLog(u, java.lang.management.ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime() - c0, oracleNanos);
            }
        }
        if (refDeclines != null && oracle == Outcome.ACCEPT && engineUnit(u) && refDeclines.test(u)) {
            oracle = Outcome.DECLINE;
            oracleReason = Reason.BUDGET_MEMORY;
            oracleMessage = "simulated by the test";
        }
        for (Lane lane : lanes) {
            lane.runUnit(u);
        }
        try {
            refPeakFootprint = Math.max(refPeakFootprint, refSession.footprintBytes());
        } catch (RuntimeException e) {
            // a footprint is only a scheduling hint
        }
        if (oracle != Outcome.ACCEPT) {
            refSession.reset();
            refCtx.reset();
            // A REJECT decides the file: this unit is still reported, the next call returns false. A DECLINE of
            // the reference engine (its own budget, a limit) decides nothing: the comparison goes on, unless the
            // environment pass itself stopped (then no later unit can be admitted by anybody).
            if (oracle == Outcome.REJECT || env.failedUnit() >= 0) {
                finished = true;
            }
        } else if (refSession.footprintBytes() > sessionBytes) {
            refSession.reset();
            refCtx.reset();
        }
        return true;
    }

    /** Asks every engine to give up at its next poll (used by the hard timeout of a report run). */
    void cancel() {
        refDeadline.cancelled = true;
        for (Lane lane : lanes) {
            lane.deadline.cancelled = true;
        }
    }

    // ---- names -------------------------------------------------------------------------------------------

    /** Name of the first constant of a unit, or "-" for an empty unit. */
    String unitName(int u) {
        DeclTable decls = store.decls();
        return decls.unitSize(u) == 0 ? "-" : store.names().toString(decls.name(decls.unitFirstCid(u)));
    }

    String unitKindName(int u) {
        DeclTable decls = store.decls();
        if (decls.unitSize(u) == 0) {
            return "empty";
        }
        return kindName(decls.unitKind(u));
    }

    static String kindName(int kind) {
        switch (kind) {
            case DeclKind.AXIOM:
                return "axiom";
            case DeclKind.DEFN:
                return "def";
            case DeclKind.THM:
                return "theorem";
            case DeclKind.OPAQUE:
                return "opaque";
            case DeclKind.INDUCT:
                return "inductive";
            case DeclKind.CTOR:
                return "constructor";
            case DeclKind.REC:
                return "recursor";
            case DeclKind.QUOT_TYPE:
            case DeclKind.QUOT_MK:
            case DeclKind.QUOT_LIFT:
            case DeclKind.QUOT_IND:
                return "quot";
            default:
                return "kind" + kind;
        }
    }

    /** True if the unit is one the engines check (axiom, definition, theorem, opaque); the others are the environment pass's. */
    boolean engineUnit(int u) {
        DeclTable decls = store.decls();
        if (decls.unitSize(u) == 0) {
            return false;
        }
        int k = decls.unitKind(u);
        return k == DeclKind.AXIOM || k == DeclKind.DEFN || k == DeclKind.THM || k == DeclKind.OPAQUE;
    }

    // ---- digests -------------------------------------------------------------------------------------------

    /**
     * Sizes and digests of the session state a declaration left behind: every word of every record
     * (identity, flags and the four memo words: ISA 2.2, 2.6), every word of the run slab (2.4), every
     * pair entry in insertion order and as a set (1.5).
     */
    static void digest(Machine m, Obs o) {
        o.hasDigest = true;
        // [vbase, vp) and [rbase, rsp): the session's records and run words (the bases move only under DBG_POISON)
        o.records = m.vp - m.vbase;
        o.runWords = m.rsp - m.rbase;
        o.pairs = m.plogN;
        o.recordDigest = digest(m.vh, m.vbase << Layout.REC_SHIFT, m.vp << Layout.REC_SHIFT) ^ m.vbase;
        o.runDigest = digest(m.rs, m.rbase, m.rsp) ^ m.rbase;
        long h = 0x9E3779B97F4A7C15L;
        long set = 0;
        int[] pair = m.pair;
        for (int i = 0; i < m.plogN; i++) {
            int e = m.plog[i] << Layout.PAIR_SHIFT;
            long x = mix(mix(mix(0x1234567L, pair[e + Layout.PAIR_LO]), pair[e + Layout.PAIR_HI]), pair[e + Layout.PAIR_STATUS]);
            h = mix(h, (int) x) ^ (x >>> 32);
            set += x * 0xD6E8FEB86659FD93L;
        }
        o.pairDigest = h;
        o.pairSetDigest = set;
    }

    static long digest(int[] a, int from, int to) {
        long h = 0x9E3779B97F4A7C15L;
        for (int i = from; i < to; i++) {
            h = mix(h, a[i]);
        }
        return h;
    }

    private static long mix(long h, int x) {
        h ^= x & 0xFFFFFFFFL;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 29;
        return h;
    }

    @Override
    public void close() {
        for (Lane lane : lanes) {
            lane.close();
        }
        if (refSession != null) {
            refSession.close();
        }
        if (refEngine != null) {
            refEngine.close();
        }
    }
}
