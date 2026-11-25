package rlvm.driver;

import rlvm.admit.Policy;
import rlvm.api.Budget;
import rlvm.api.Engine;
import rlvm.api.Outcome;
import rlvm.api.Reason;
import rlvm.api.Session;

/**
 * One checking thread: owns its lanes (a {@code WorkerContext} with an admission each) and the
 * sessions on them, and loops over {@link Scheduler#claim(int, int[])}.
 *
 * <pre>
 * for each claimed unit u (ascending within a claim):
 *   if u &gt; board.firstFailure(): skip                              -- cancelled
 *   -j 1 only: advance the environment pass to u + 1                  -- strict file order
 *   inductive / quot unit: decided by the environment pass; skipped record: counts as accepted
 *   if rank(u) &lt;= lastRank: reset the warm session                   -- sessions only move forward (I5)
 *   o := admit(u) in the warm session, warm budget (no step limit)
 *   ACCEPT  -> board.accept(u); reset the session if its footprint exceeds the session cap
 *              (RunConfig.sessionBytes, or the primary engine's default)
 *   other   -> reset the session (nothing of a failed unit is kept); if u is still at or below the
 *              first failure, run the re-check protocol at once, on this thread, in fresh sessions
 * </pre>
 *
 * <p>Re-check protocol (*agentic-note*: design-architecture-document, 8.2 and 6.5; "primary" is the engine under test, "reference"
 * the reference kernel; every stage runs in a freshly reset session with the cold budget):
 * <pre>
 * warm REJECT:   1. strict primary            ACCEPT -> accepted, WARM-ANOMALY
 *                2. reference                 REJECT -> REJECTED (confirmed, the reference's reason)
 *                                             ACCEPT -> accepted, MACHINE-INCOMPLETE
 *                                             other  -> Policy.unconfirmedReject: DECLINED(UNCONFIRMED_REJECT) or REJECTED
 * warm DECLINE:  1. primary, cold budget      ACCEPT -> accepted;  REJECT -> as "warm REJECT"
 *                2. reference                 ACCEPT -> accepted;  REJECT -> REJECTED;  DECLINE -> DECLINED
 * RuntimeException out of the warm session: session discarded;
 *                   reference                 as step 2 above; a second exception -> DECLINED(INTERNAL_ERROR)
 * primary == reference: the steps "strict primary" and "primary, cold budget" are skipped; the single
 *                cold run of the reference kernel decides.
 * </pre>
 * After every stage the unit is abandoned without a record if it now lies above the first failure:
 * its stage may have been cancelled, and its outcome can no longer matter.
 *
 * <p><b>Memory.</b> A stage that ends with {@code BUDGET_MEMORY} is repeated while this worker holds
 * the {@link HeavyGate}: alone, exempt from the run's watermark, so that the unit is declined for
 * memory only if it does not fit by itself. A warm decline for memory takes the gate before its
 * first cold stage. After any unit the lane is reset when its unused capacity grew by more than
 * {@code RunConfig.keepBytes} (give-back after a heavy declaration).
 *
 * <p><b>Slow units.</b> A unit that runs longer than {@code RunConfig.spillNanos} returns the
 * unstarted rest of its worker's claim to the scheduler: at the next poll of its session while it is
 * still running ({@link #sample()}), before the worker waits for the heavy gate, and at the latest
 * when the unit has ended. Who checks a unit depends on that; the verdict does not.
 *
 * <p><b>Pipelined loading.</b> A lane sees the levels, lists and literals published when it was
 * created or last reset; before a unit beyond that frontier the warm session is reset
 * (*agentic-note*: design-architecture-document, 3.5). Nothing but an ACCEPT is recorded for a unit that started while the file
 * was still loading: a decline may only say "not loaded yet" (a forward reference that will turn
 * out to be an unknown constant), and a rejection may rest on a name that a later record provides.
 * The worker returns the rest of its claim, waits for the end of the load and runs the unit's
 * protocol once more; that outcome counts.
 *
 * <p>An {@code Error} (and any throwable outside a unit) is not handled here: it ends this thread's
 * loop, cancels the run and is rethrown by {@link Run#execute()} on the calling thread.
 */
public final class Worker implements Runnable, LaneSignals.Probe {

    /** Pseudo outcome of a stage that threw a {@code RuntimeException}. */
    static final int THROWN = 3;

    private final int id;
    private final Run run;
    private final Engine primary;
    private final Scheduler scheduler;
    private final VerdictBoard board;
    private final RunHost host;
    private final RunConfig config;
    private final RunStats stats;
    private final MemoryAccount account;
    private final boolean statsOn;
    private final boolean same;
    /** {@code RunConfig.skipRepeats}, and the primary engine is not the reference engine. */
    private final boolean skipRepeats;
    private final Budget warm;
    private final Budget cold;
    private final int shardCount;
    private final int shardIndex;

    private LaneSignals signals;
    private RunHost.Lane lane;
    private Session session;
    private RunHost.Lane strictLane;
    private Session strictSession;
    private RunHost.Lane refLane;
    private Session refSession;
    private EnvLane env;

    private int lastRank = -1;
    private long lastReported;
    /** Units below this bound are covered by what the warm lane saw at its creation or last reset. */
    private int laneUnits;
    /** Slack of the warm lane right after its last reset (what a reset cannot give back). */
    private long laneBaseSlack;
    /** Largest footprint of the warm session sampled during the unit in progress. */
    private long unitPeak;
    /** Consecutive accepted units that did not need the capacity the warm lane holds. */
    private int idleUnits;
    private boolean opened;
    /** The claim in progress: units {@code claim[0 .. claimLen)} are this worker's, {@code claim[claimPos]} is running. */
    private int[] claim;
    private int claimLen;
    private int claimPos;
    /** {@code System.nanoTime()} at the start of the unit in progress (when {@link #spill} is positive). */
    private long unitStart;
    /** {@code RunConfig.spillNanos}, or 0 with one worker (nobody to give units back to). */
    private long spill;
    /** More than one worker: units given back are taken by somebody else. */
    private boolean shared;
    /** Reset threshold of the warm session (the configured cap, or the primary engine's default). */
    private long sessionBytes = RunConfig.DEFAULT_SESSION_BYTES;
    private boolean gateHeld;
    private int gateUnit = -1;
    /** The unit in progress started while the file was still loading: only an ACCEPT is recorded. */
    private boolean loading;
    /** A rejection or decline of the unit in progress was withheld (see {@link #loading}). */
    private boolean withheld;
    private boolean mirror = true;
    private Reason stageReason;
    private String stageMessage;

    private long accepted;
    private long resets;
    private long cancelled;
    private long rechecks;
    private long mirrorFailures;
    private long unitSteps;
    private long giveBacks;
    private long givenBackBytes;
    private long maxSlack;
    private long snapshotResets;
    private long deferred;
    private long gated;
    private long restsReleasedInUnit;
    private long shardSkipped;
    private long repeatsSkipped;
    /** Repeats that were checked, not accepted, and then admitted on the word of their original ({@link #awaitOriginal}). */
    private long repeatsAwaited;

    // ---- time accounting (statistics only: all zero unless statsOn) ------------------------------------------
    /** Columns of {@link #times()}. */
    static final String[] TIME_COLUMNS = {"wallNanos", "cpuNanos", "runQueueNanos", "unitNanos", "claimNanos",
        "waitEnvNanos", "waitLoaderNanos", "waitTailNanos", "gateWaitNanos", "loadEndWaitNanos", "openNanos",
        "resetNanos", "claims", "parks", "startAtNanos", "firstClaimAtNanos", "lastUnitEndAtNanos", "endAtNanos",
        "lazyEnvNanos", "laneResetNanos"};
    /** A point of the worker's time series is recorded at a claim boundary at most this often. */
    private static final long SERIES_NANOS = 100_000_000L;
    static final String[] SERIES_COLUMNS = {"atNanos", "accepted", "unitNanos", "claimNanos"};
    private UnitStats unitStats;
    private long startAt;
    private long endAt;
    private long firstClaimAt;
    private long lastUnitEndAt;
    /** Inside {@code Scheduler.claim}: parked, or waiting for the scheduler's lock. */
    private long claimNanos;
    /** Inside units: admission, re-checks, session resets; the two waits below included. */
    private long unitNanos;
    private long gateWaitNanos;
    private long loadEndWaitNanos;
    private long openNanos;
    /** Resets of the warm session and its lane; {@link #laneResetNanos} is the lane's part (the worker context). */
    private long resetNanos;
    private long laneResetNanos;
    /** One worker, batch: the environment pass, which this worker advances itself (part of {@code unitNanos}). */
    private long lazyEnvNanos;
    private long claimsTaken;
    private long cpuNanos = -1;
    private long runQueueNanos = -1;
    private long[] series;
    private int seriesLen;
    private long seriesNext;
    /** {@code RunConfig.unitTrace}: four words per checked unit (unit, start, nanos, steps * 4 + outcome), or null. */
    private long[] trace;
    private int traceLen;

    public Worker(int id, Run run, Engine primary, Scheduler scheduler, VerdictBoard board) {
        this.id = id;
        this.run = run;
        this.primary = primary;
        this.scheduler = scheduler;
        this.board = board;
        this.host = run.host();
        this.config = run.config();
        this.stats = run.stats();
        this.account = run.account();
        this.statsOn = stats.enabled();
        this.same = run.primaryIsReference();
        this.skipRepeats = config.skipRepeats && !same;
        this.warm = new Budget(config.warmSteps, run.warmUnitBytes(), config.warmDepth);
        this.cold = Budget.cold(config.memoryLimitBytes);
        this.shardCount = config.shardCount;
        this.shardIndex = config.shardIndex;
    }

    /** Pseudo outcome of {@link #unit(int)}: a decline was withheld until the file is completely loaded. */
    private static final int DEFERRED = -2;

    /**
     * The "session" of a unit that repeats an ACCEPTED unit word for word ({@code RunConfig.skipRepeats}):
     * the lane's admission runs with it exactly as with the warm session - the environment frontier, the
     * name, the universe parameters, the roots, a recorded outcome of the environment pass - and where it
     * would hand the constant to the engine, the answer is the one the engine gave for the same type and
     * value under the earlier name. Whatever the admission itself finds is reported as for any unit.
     */
    private static final Session ADMIT_ONLY = new Session() {
        @Override
        public int check(int cid, Budget budget) {
            return Outcome.ACCEPT;
        }

        @Override
        public Reason lastReason() {
            return null;
        }

        @Override
        public String lastMessage() {
            return null;
        }

        @Override
        public rlvm.api.KernelOps ops() {
            return null;
        }

        @Override
        public void reset() {
        }

        @Override
        public long footprintBytes() {
            return 0;
        }

        @Override
        public long steps() {
            return 0;
        }

        @Override
        public void close() {
        }
    };

    @Override
    public void run() {
        long[] sched0 = statsOn ? RunStats.currentThreadSched() : null;
        if (statsOn) {
            unitStats = new UnitStats();
            series = new long[64 * SERIES_COLUMNS.length];
            if (config.unitTrace) {
                trace = new long[4 * 1024];
            }
            startAt = System.nanoTime();
        }
        try {
            claim = new int[config.chunkUnits];
            // With one worker there is nobody to give units back to.
            shared = run.workerCount() > 1;
            spill = shared ? config.spillNanos : 0;
            final boolean timed = spill > 0 || statsOn;
            final boolean collect = config.gcAfterBytes > 0;
            for (;;) {
                claimLen = 0;
                final long c0 = statsOn ? System.nanoTime() : 0;
                int n = scheduler.claim(id, claim);
                if (statsOn) {
                    long c1 = System.nanoTime();
                    claimNanos += c1 - c0;
                    if (n > 0) {
                        claimsTaken++;
                        if (firstClaimAt == 0) {
                            firstClaimAt = c1;
                        }
                        if (c1 >= seriesNext) {
                            seriesPoint(c1);
                        }
                    }
                }
                if (n == 0) {
                    break;
                }
                if (collect) {
                    run.maybeCollect();
                }
                if (!opened) {
                    // Not before the first claim: in a pipelined run nothing is published before it,
                    // and a context needs a published frontier to look at.
                    final long o0 = statsOn ? System.nanoTime() : 0;
                    open();
                    if (statsOn) {
                        openNanos += System.nanoTime() - o0;
                    }
                }
                long t0 = timed ? System.nanoTime() : 0;
                claimLen = n;
                // claimLen shrinks when the rest of the claim is given back, which can happen inside unit().
                for (int i = 0; i < claimLen; i++) {
                    claimPos = i;
                    unitStart = t0;
                    int u = claim[i];
                    int o = unit(u);
                    if (o == DEFERRED) {
                        // The rest of the claim must not wait with this unit for the end of the load.
                        releaseRest();
                        o = again(u);
                    }
                    if (timed) {
                        long t1 = System.nanoTime();
                        if (statsOn) {
                            unitNanos += t1 - t0;
                            lastUnitEndAt = t1;
                            if (o >= 0) {
                                unitStats.unit(u, id, unitSteps, t1 - t0, o);
                                if (trace != null) {
                                    if (traceLen + 4 > trace.length) {
                                        trace = java.util.Arrays.copyOf(trace, trace.length * 2);
                                    }
                                    trace[traceLen] = u;
                                    trace[traceLen + 1] = t0 - run.startNanos();
                                    trace[traceLen + 2] = t1 - t0;
                                    trace[traceLen + 3] = unitSteps * 4 + o;
                                    traceLen += 4;
                                }
                            }
                        }
                        if (spill > 0 && t1 - t0 > spill) {
                            releaseRest();
                        }
                        t0 = t1;
                    }
                }
            }
        } catch (Throwable t) {
            run.fatal(t);
        } finally {
            scheduler.retire(id);
            close();
            if (statsOn) {
                long[] sched1 = RunStats.currentThreadSched();
                cpuNanos = sched0[0] < 0 || sched1[0] < 0 ? -1 : sched1[0] - sched0[0];
                runQueueNanos = sched0[1] < 0 || sched1[1] < 0 ? -1 : sched1[1] - sched0[1];
                stats.threadTimes(Thread.currentThread().getName(), cpuNanos, runQueueNanos);
                stats.mergeUnits(unitStats);
                endAt = System.nanoTime();
                seriesPoint(endAt);
            }
        }
    }

    /**
     * Hands the unstarted rest of the claim in progress back to the scheduler, so that it does not
     * wait behind the unit this worker is busy with. No effect with one worker or without a rest.
     */
    private void releaseRest() {
        int from = claimPos + 1;
        int rest = claimLen - from;
        if (rest > 0 && shared) {
            claimLen = from;
            scheduler.giveBack(claim, from, rest);
        }
    }

    private void open() {
        opened = true;
        sessionBytes = run.sessionBytes();
        signals = new LaneSignals(board, account, id);
        signals.setProbe(this);
        int frontier = frontierNow();
        lane = host.newLane(id, signals, config.memoryLimitBytes);
        laneUnits = Math.min(frontier, lane.coveredUnits());
        session = primary.newSession(lane.context());
        laneBaseSlack = lane.slackBytes();
        if (run.lazyEnv()) {
            env = new EnvLane(run, signals);
        }
    }

    /** The bound below which a lane created or reset after this call sees everything units mention. */
    private int frontierNow() {
        // The end of the load is read first: after it the loaded frontier no longer moves.
        return run.loadEnded() ? Integer.MAX_VALUE : host.loadedUnits();
    }

    /**
     * Processes one claimed unit.
     *
     * @return the outcome code of the warm attempt for the statistics, -1 if no engine ran, or
     *     {@link #DEFERRED} if a decline was withheld because the file was still loading
     */
    private int unit(int u) {
        // Read before anything of this unit runs: what the unit's stages saw of the file was at
        // least this much.
        loading = !run.loadEnded();
        withheld = false;
        try {
            int o = unit0(u);
            return withheld ? DEFERRED : o;
        } finally {
            if (gateHeld) {
                leaveGate();
            }
            loading = false;
        }
    }

    /**
     * A decline of {@code u} was withheld while the file was loading: waits for the end of the load
     * and runs the unit's protocol again; whatever it says then is recorded.
     */
    private int again(int u) {
        deferred++;
        final long w0 = statsOn ? System.nanoTime() : 0;
        final boolean alive = run.awaitLoadEnd(u);
        if (statsOn) {
            loadEndWaitNanos += System.nanoTime() - w0;
        }
        if (!alive) {
            cancelled++;
            return -1;
        }
        resetWarm(); // the lane moves to the final snapshot
        loading = false;
        withheld = false;
        try {
            return unit0(u);
        } finally {
            if (gateHeld) {
                leaveGate();
            }
        }
    }

    private int unit0(int u) {
        if (u > board.firstFailure()) {
            cancelled++;
            return -1;
        }
        if (env != null && u >= host.envUnits()) {
            final long e0 = statsOn ? System.nanoTime() : 0;
            int n = env.advanceTo(u + 1);
            if (statsOn) {
                lazyEnvNanos += System.nanoTime() - e0;
            }
            if (n <= u) {
                // The environment pass stopped at or before u; its listener recorded why.
                if (u <= board.firstFailure() && board.status(u) == VerdictBoard.NONE) {
                    fail(u, VerdictBoard.DECLINED, Reason.INTERNAL_ERROR,
                            "the environment pass stopped at unit " + n + " without reporting a failure");
                }
                return -1;
            }
        }
        int cls = host.unitClass(u);
        if (cls != RunHost.CHECK) {
            other(u, cls);
            return -1;
        }
        if (shardCount > 0 && (u >>> RunConfig.SHARD_BLOCK_LOG2) % shardCount != shardIndex) {
            // A measurement run over one shard of the units (the board was stopped at the start of the
            // run: its verdict is DECLINE whatever happens here). The unit is passed over.
            board.accept(u);
            shardSkipped++;
            return -1;
        }
        if (u >= host.envUnits()) {
            if (u <= board.firstFailure()) {
                fail(u, VerdictBoard.DECLINED, Reason.INTERNAL_ERROR,
                        "unit handed out before the environment pass reached it");
            }
            return -1;
        }
        int rank = host.unitRank(u);
        if (u >= laneUnits) {
            // Beyond the snapshot of levels, lists and literals the lane was built on (pipelined load).
            snapshotResets++;
            resetWarm();
        } else if (rank <= lastRank) {
            resetWarm();
        }
        lastRank = rank;
        lane.beginUnit();
        signals.clearMemoryDeclined();
        unitPeak = 0;
        long s0 = statsOn ? session.steps() : 0;
        // A unit that repeats an accepted unit is admitted and not checked again (RunConfig.skipRepeats).
        final int original = skipRepeats ? originalOf(u, rank) : -1;
        final boolean repeat = original >= 0 && board.status(original) == VerdictBoard.ACCEPTED;
        int o;
        Reason reason = null;
        boolean thrown = false;
        try {
            o = lane.admit(u, repeat ? ADMIT_ONLY : session, warm);
        } catch (RuntimeException e) {
            o = Outcome.DECLINE;
            thrown = true;
            reason = Reason.INTERNAL_ERROR;
            stageMessage = e.toString();
        }
        if (o == Outcome.ACCEPT) {
            if (repeat) {
                repeatsSkipped++;
            }
            if (config.differential && !same && !agrees(u, Outcome.ACCEPT)) {
                resetWarm();
                return Outcome.ACCEPT;
            }
            accept(u);
            if (statsOn) {
                unitSteps = session.steps() - s0;
            }
            if (session.footprintBytes() > sessionBytes) {
                resetWarm();
            } else if (!giveBack()) {
                sample();
            }
            return Outcome.ACCEPT;
        }
        // Anything but ACCEPT: nothing of this unit is kept, and its outcome is not final yet.
        String message = null;
        if (thrown) {
            if (statsOn) {
                unitSteps = 0;
            }
            message = stageMessage;
            discardWarm();
        } else {
            if (o != Outcome.REJECT && o != Outcome.DECLINE) {
                reason = Reason.INTERNAL_ERROR;
                message = "the primary engine returned the invalid outcome code " + o;
                o = Outcome.DECLINE;
            } else {
                reason = lane.lastReason();
                if (o == Outcome.REJECT && !config.recheckRejections) {
                    message = lane.lastMessage();
                }
            }
            if (statsOn) {
                unitSteps = session.steps() - s0;
            }
            resetWarm();
        }
        if (u > board.firstFailure()) {
            cancelled++;
            return o;
        }
        if (original >= 0 && !repeat) {
            // The unit repeats a unit that had no status when this one came up, so it was checked - and the
            // check did not accept it. Had its original been accepted a moment earlier, the unit would have
            // been admitted on the original's word; the status of a unit must not depend on that moment
            // (on input that only a shortcut of the primary engine accepts the two can differ). So the
            // original's status is waited for: ACCEPTED, and the unit goes through the path of a repeat;
            // anything else, and the first failure lies at or before the original.
            releaseRest();
            if (awaitOriginal(u, original)) {
                repeatsAwaited++;
                return unit0(u);
            }
            if (u > board.firstFailure()) {
                cancelled++;
                return o;
            }
        }
        if (o == Outcome.REJECT && config.differential && !same && !agrees(u, Outcome.REJECT)) {
            return o;
        }
        resolve(u, o, reason, message, thrown);
        return o;
    }

    /**
     * The unit that {@code u} repeats word for word ({@code RunHost.unitRepeatOf}), or -1: an earlier unit of
     * the file that also comes earlier in admission order, so that every constant it could see is visible to
     * {@code u}. If that unit is ACCEPTED on the board when {@code u} comes up - a status is written once, so
     * an ACCEPTED read is final - {@code u} is admitted and not checked. Otherwise (not decided yet: in flight
     * on another worker; rejected or declined: the verdict is decided before {@code u}) it is checked, and
     * nobody waits unless that check fails ({@link #awaitOriginal}).
     */
    private int originalOf(int u, int rank) {
        int first = host.unitRepeatOf(u);
        return first >= 0 && first < u && host.unitRank(first) < rank ? first : -1;
    }

    /**
     * Waits until {@code original}, a smaller unit that another worker holds, has its final status or
     * {@code u} lies above the first failure (which a cancelled run sets below every unit).
     *
     * <p>Rare: the unit was checked because its original was still in flight, and the check did not
     * accept it. The worker holds no gate and its warm session is reset; the rest of its claim was given
     * back. Whoever holds the original does not wait for this worker: a wait is always for a smaller unit,
     * and the holder of the smallest undecided unit waits for none.
     *
     * @return true if the original is ACCEPTED
     */
    private boolean awaitOriginal(int u, int original) {
        for (;;) {
            int st = board.status(original);
            if (st == VerdictBoard.ACCEPTED) {
                return true;
            }
            if (st != VerdictBoard.NONE || u > board.firstFailure()) {
                return false;
            }
            java.util.concurrent.locks.LockSupport.parkNanos(100_000L);
        }
    }

    /** A unit that no worker checks: decided by the environment pass, skipped, or of unknown kind. */
    private void other(int u, int cls) {
        if (cls == RunHost.SKIP) {
            board.skip(u);
            accepted++;
        } else if (cls == RunHost.ENV) {
            if (board.status(u) == VerdictBoard.NONE && u <= board.firstFailure()) {
                recorded(u);
            }
        } else if (u <= board.firstFailure()) {
            fail(u, VerdictBoard.DECLINED, Reason.UNSUPPORTED_INPUT, "declaration record of unknown kind");
        }
    }

    /**
     * An inductive or quot unit without a status on the board: the environment pass decided it before
     * this run drove the pass (a caller that advanced the frontier itself). The lane's admission
     * returns the outcome the pass recorded; a unit the pass has not reached is declined.
     */
    private void recorded(int u) {
        if (u >= host.envUnits()) {
            fail(u, VerdictBoard.DECLINED, Reason.INTERNAL_ERROR, "the environment pass reported no outcome for this unit");
            return;
        }
        int o;
        try {
            lane.beginUnit();
            o = lane.admit(u, session, warm);
        } catch (RuntimeException e) {
            fail(u, VerdictBoard.DECLINED, Reason.INTERNAL_ERROR, "no recorded outcome of the environment pass: " + e);
            return;
        }
        if (o == Outcome.ACCEPT) {
            board.accept(u);
        } else if (o == Outcome.REJECT && lane.lastReason() != null && lane.lastReason().isReject()) {
            // the environment pass is the reference kernel: no confirmation needed
            fail(u, VerdictBoard.REJECTED, lane.lastReason(), lane.lastMessage());
        } else {
            Reason r = lane.lastReason();
            fail(u, VerdictBoard.DECLINED, r == null || r.isReject() ? Reason.INTERNAL_ERROR : r, lane.lastMessage());
        }
    }

    // ---- re-check protocol ----------------------------------------------------------------------------------

    private void resolve(int u, int warmOutcome, Reason warmReason, String warmMessage, boolean thrown) {
        rechecks++;
        stageReason = null;
        try {
            if (warmOutcome == Outcome.DECLINE && !thrown
                    && (warmReason == Reason.BUDGET_MEMORY || signals.memoryDeclined())) {
                // A memory retry (the heavy lane of *agentic-note*: design-reconciliation-document, section 3): alone from the start.
                if (!enterGate(u)) {
                    cancelled++;
                    return;
                }
            }
            if (warmOutcome == Outcome.REJECT) {
                if (!config.recheckRejections) {
                    fail(u, VerdictBoard.REJECTED, warmReason, warmMessage);
                } else {
                    rejected(u, warmReason);
                }
                return;
            }
            if (!same && !thrown) {
                int o = stage(lane, session, u, !config.recheckRejections);
                lastRank = -1;
                if (o == THROWN) {
                    discardWarm();
                }
                if (o == Outcome.ACCEPT) {
                    accept(u);
                    return;
                }
                if (u > board.firstFailure()) {
                    cancelled++;
                    return;
                }
                if (o == Outcome.REJECT) {
                    if (!config.recheckRejections) {
                        fail(u, VerdictBoard.REJECTED, stageReason, stageMessage);
                    } else {
                        rejected(u, stageReason);
                    }
                    return;
                }
            }
            if (!config.referenceFallback && !same) {
                // The engine under test alone: what it declines (or throws on) is declined.
                Reason r = thrown ? Reason.INTERNAL_ERROR : stageReason != null && !stageReason.isReject() ? stageReason
                        : warmReason != null && !warmReason.isReject() ? warmReason : Reason.INTERNAL_ERROR;
                fail(u, VerdictBoard.DECLINED, r, "the primary engine "
                        + (thrown ? "failed (" + warmMessage + ")" : "declined (" + r + ")")
                        + "; the reference kernel was not asked (no-fallback)");
                return;
            }
            int o = reference(u);
            if (o == Outcome.ACCEPT) {
                accept(u);
            } else if (u > board.firstFailure()) {
                cancelled++;
            } else if (o == Outcome.REJECT) {
                fail(u, VerdictBoard.REJECTED, stageReason, stageMessage);
            } else if (o == Outcome.DECLINE) {
                fail(u, VerdictBoard.DECLINED, stageReason, stageMessage);
            } else {
                fail(u, VerdictBoard.DECLINED, Reason.INTERNAL_ERROR, thrown
                        ? "primary engine failed (" + warmMessage + "), reference kernel failed (" + stageMessage + ")"
                        : "reference kernel failed (" + stageMessage + ")");
            }
        } catch (RuntimeException e) {
            // A lane or a session could not be created or reset: no verdict for this unit.
            if (u <= board.firstFailure() && board.status(u) == VerdictBoard.NONE) {
                fail(u, VerdictBoard.DECLINED, Reason.INTERNAL_ERROR, "re-check failed: " + e);
            }
        }
    }

    /** The primary engine rejected {@code u}: confirm (*agentic-note*: design-architecture-document, 8.2). */
    private void rejected(int u, Reason primaryReason) {
        if (!same) {
            int o = strict(u);
            if (o == Outcome.ACCEPT) {
                board.warmAnomaly(u, "rejected (" + primaryReason + ") in a warm session, accepted cold and strict");
                accept(u);
                return;
            }
            if (u > board.firstFailure()) {
                cancelled++;
                return;
            }
        }
        int o = reference(u);
        if (o == Outcome.REJECT) {
            fail(u, VerdictBoard.REJECTED, stageReason, stageMessage);
            return;
        }
        if (o == Outcome.ACCEPT) {
            if (same) {
                board.warmAnomaly(u, "rejected (" + primaryReason + ") in a warm session, accepted in a fresh one");
            } else {
                board.machineIncomplete(u, "primary engine rejects (" + primaryReason + "), reference kernel accepts");
                if (!config.referenceFallback) {
                    // The engine under test alone: its wrong rejection is not papered over.
                    fail(u, VerdictBoard.DECLINED, Reason.ENGINE_DISAGREEMENT, "primary engine rejects ("
                            + primaryReason + "), reference kernel accepts (no-fallback)");
                    return;
                }
            }
            accept(u);
            return;
        }
        if (u > board.firstFailure()) {
            cancelled++;
            return;
        }
        String message = "primary engine rejected (" + primaryReason + "), reference kernel "
                + (o == THROWN ? "failed" : "declined") + " (" + (o == THROWN ? stageMessage : String.valueOf(stageReason))
                + ")";
        if (config.policy.unconfirmedReject == Policy.Unconfirmed.REJECT && primaryReason != null
                && primaryReason.isReject()) {
            fail(u, VerdictBoard.REJECTED, primaryReason, message);
        } else {
            fail(u, VerdictBoard.DECLINED, Reason.UNCONFIRMED_REJECT, message);
        }
    }

    /**
     * Differential mode: runs the reference kernel beside a verdict of the primary engine.
     *
     * @return false if the engines disagree (the unit is then declined, unless abandoned)
     */
    private boolean agrees(int u, int primaryOutcome) {
        int o;
        try {
            o = reference(u);
        } catch (RuntimeException e) {
            return true; // no second opinion: the normal protocol applies
        }
        if ((o != Outcome.ACCEPT && o != Outcome.REJECT) || o == primaryOutcome) {
            return true;
        }
        if (u <= board.firstFailure()) {
            String detail = "primary engine: " + Outcome.name(primaryOutcome) + ", reference kernel: " + Outcome.name(o)
                    + (o == Outcome.REJECT ? " (" + stageReason + ")" : "");
            board.disagreement(u, detail);
            fail(u, VerdictBoard.DECLINED, Reason.ENGINE_DISAGREEMENT, detail);
        } else {
            cancelled++;
        }
        return false;
    }

    private int strict(int u) {
        if (strictSession == null) {
            strictLane = host.newLane(id, signals, config.memoryLimitBytes);
            strictSession = run.strictEngine().newSession(strictLane.context());
        }
        int o = stage(strictLane, strictSession, u, false);
        if (o == THROWN) {
            Session s = strictSession;
            RunHost.Lane l = strictLane;
            strictSession = null;
            strictLane = null;
            closeQuietly(s, l);
        }
        return o;
    }

    private int reference(int u) {
        if (refSession == null) {
            refLane = host.newLane(id, signals, config.memoryLimitBytes);
            refSession = run.referenceEngine().newSession(refLane.context());
        }
        int o = stage(refLane, refSession, u, true);
        if (o == THROWN) {
            Session s = refSession;
            RunHost.Lane l = refLane;
            refSession = null;
            refLane = null;
            closeQuietly(s, l);
        }
        return o;
    }

    /**
     * One stage of the protocol: admits {@code u} in session {@code s}, freshly reset, with the cold
     * budget, and resets it again afterwards. Sets {@link #stageReason} (and {@link #stageMessage} if
     * wanted) on anything but ACCEPT.
     */
    private int stage(RunHost.Lane l, Session s, int u, boolean wantMessage) {
        for (;;) {
            int o = stageOnce(l, s, u, wantMessage);
            if (o != Outcome.DECLINE || stageReason != Reason.BUDGET_MEMORY) {
                return o;
            }
            if (gateHeld) {
                if (!signals.yielded()) {
                    return o; // alone, and it does not fit: the unit's outcome
                }
                leaveGate(); // a lower unit wants the gate: its turn first, then once more
            }
            if (!enterGate(u)) {
                return o; // the unit lies above the first failure now: the caller abandons it
            }
        }
    }

    private int stageOnce(RunHost.Lane l, Session s, int u, boolean wantMessage) {
        int o;
        try {
            s.reset();
            l.reset();
            l.beginUnit();
            signals.clearMemoryDeclined();
            o = l.admit(u, s, cold);
            if (o != Outcome.ACCEPT) {
                if (o != Outcome.REJECT && o != Outcome.DECLINE) {
                    stageReason = Reason.INTERNAL_ERROR;
                    stageMessage = "invalid outcome code " + o;
                    return THROWN;
                }
                stageReason = l.lastReason();
                stageMessage = wantMessage ? l.lastMessage() : null;
                if (stageReason == null) {
                    stageReason = Reason.INTERNAL_ERROR;
                }
            }
            s.reset();
            l.reset();
        } catch (RuntimeException e) {
            stageReason = Reason.INTERNAL_ERROR;
            stageMessage = e.toString();
            return THROWN;
        }
        sample();
        return o;
    }

    // ---- the heavy gate ---------------------------------------------------------------------------------------

    /** Takes the gate for {@code u}; false if the unit was cancelled while waiting. */
    private boolean enterGate(int u) {
        if (gateHeld) {
            return true;
        }
        // A memory retry is slow, and the gate may be busy: the rest of the claim does not wait for it.
        releaseRest();
        HeavyGate gate = run.gate();
        final long w0 = statsOn ? System.nanoTime() : 0;
        final boolean entered = gate.enter(u, id);
        if (statsOn) {
            gateWaitNanos += System.nanoTime() - w0;
        }
        if (!entered) {
            return false;
        }
        gateHeld = true;
        gateUnit = u;
        gated++;
        signals.holding(gate, u);
        return true;
    }

    private void leaveGate() {
        signals.released();
        gateHeld = false;
        run.gate().leave(gateUnit);
        gateUnit = -1;
    }

    // ---- sessions ---------------------------------------------------------------------------------------------

    private void resetWarm() {
        // The driver's own reading of the frontier (before the reset) and the context's: the smaller one.
        int frontier = frontierNow();
        final long r0 = statsOn ? System.nanoTime() : 0;
        session.reset();
        final long r1 = statsOn ? System.nanoTime() : 0;
        lane.reset();
        if (statsOn) {
            final long r2 = System.nanoTime();
            resetNanos += r2 - r0;
            laneResetNanos += r2 - r1;
        }
        laneUnits = Math.min(frontier, lane.coveredUnits());
        laneBaseSlack = lane.slackBytes();
        idleUnits = 0;
        lastRank = -1;
        resets++;
        sample();
    }

    /** A lane gives its capacity back when this many units in a row did not need it. */
    static final int GIVE_BACK_AFTER_UNITS = 8;

    /**
     * Give-back after a heavy declaration: the warm lane is reset when its unused capacity grew by
     * more than {@code keepBytes} since its last reset and {@link #GIVE_BACK_AFTER_UNITS} accepted
     * units in a row did not need it (their sampled peak footprint stayed below a quarter of the growth).
     * So the arrays of one heavy declaration go back to the collector a few units later, while a run
     * of heavy declarations keeps them instead of growing them again for each. Returns true if the
     * lane was reset.
     */
    private boolean giveBack() {
        long slack = lane.slackBytes();
        if (slack > maxSlack) {
            maxSlack = slack;
        }
        long grown = slack - laneBaseSlack;
        if (config.keepBytes <= 0 || grown <= config.keepBytes) {
            idleUnits = 0;
            return false;
        }
        if (unitPeak > grown / 4) {
            // This unit used the capacity (an overlay holds up to about three times the bytes in use:
            // its node array doubles, and the hash index comes on top).
            idleUnits = 0;
            return false;
        }
        if (++idleUnits < GIVE_BACK_AFTER_UNITS) {
            return false;
        }
        resetWarm();
        giveBacks++;
        long freed = slack - laneBaseSlack;
        givenBackBytes += freed;
        run.gaveBack(freed);
        return true;
    }

    /** The warm session threw: it is closed and replaced, with a new lane. */
    private void discardWarm() {
        Session s = session;
        RunHost.Lane l = lane;
        session = null;
        lane = null;
        closeQuietly(s, l);
        int frontier = frontierNow();
        lane = host.newLane(id, signals, config.memoryLimitBytes);
        laneUnits = Math.min(frontier, lane.coveredUnits());
        session = primary.newSession(lane.context());
        laneBaseSlack = lane.slackBytes();
        lastRank = -1;
        resets++;
    }

    private void closeQuietly(Session s, RunHost.Lane l) {
        try {
            if (s != null) {
                s.close();
            }
        } catch (RuntimeException e) {
            // the session is being thrown away
        }
        try {
            if (l != null) {
                l.close();
            }
        } catch (RuntimeException e) {
            // likewise
        }
    }

    private void close() {
        try {
            Session s = session;
            RunHost.Lane l = lane;
            session = null;
            lane = null;
            closeQuietly(s, l);
            closeQuietly(strictSession, strictLane);
            closeQuietly(refSession, refLane);
            strictSession = null;
            refSession = null;
            if (env != null) {
                try {
                    env.close();
                } catch (RuntimeException e) {
                    // closing
                }
            }
            if (gateHeld) {
                leaveGate();
            }
            account.report(id, 0);
        } catch (Throwable t) {
            run.fatal(t);
        }
    }

    /**
     * The poll of this thread's sessions ({@code RunSignals.memoryExhausted()}, every few thousand
     * steps of a unit) and the end of every stage. Reports the thread's session footprint to the
     * memory account when it moved by a granule; and, when the unit in progress has been running for
     * longer than {@code RunConfig.spillNanos}, hands the unstarted rest of the claim back to the
     * scheduler while the unit is still running: a heavy unit must not keep up to {@code chunkUnits - 1}
     * units (possibly the next heavy one) waiting behind it while other workers have nothing to do.
     */
    @Override
    public void sample() {
        if (claimPos + 1 < claimLen && spill > 0 && System.nanoTime() - unitStart > spill) {
            releaseRest();
            restsReleasedInUnit++;
        }
        long total;
        try {
            long warm = session == null ? 0 : session.footprintBytes();
            if (warm > unitPeak) {
                unitPeak = warm;
            }
            total = warm + (session == null || lane == null ? 0 : lane.slackBytes())
                    + footprint(strictSession, strictLane) + footprint(refSession, refLane)
                    + (env == null ? 0 : env.footprintBytes());
        } catch (RuntimeException e) {
            return;
        }
        long d = total - lastReported;
        if (d >= MemoryAccount.GRANULE || d <= -MemoryAccount.GRANULE) {
            lastReported = total;
            account.report(id, total);
        }
    }

    /** What a session holds: its footprint (bytes in use) plus the capacity its lane keeps beyond that. */
    private static long footprint(Session s, RunHost.Lane l) {
        return s == null ? 0 : s.footprintBytes() + (l == null ? 0 : l.slackBytes());
    }

    // ---- records ----------------------------------------------------------------------------------------------

    private void accept(int u) {
        board.accept(u);
        accepted++;
        mirror(u, VerdictBoard.ACCEPTED);
    }

    private void fail(int u, int status, Reason reason, String message) {
        if (loading) {
            // The file was not completely loaded when this unit started. A decline may only mean "not
            // loaded yet", and a rejection may rest on a name that a later record still provides:
            // nothing but an ACCEPT is recorded before the whole file was seen. The unit is decided
            // again after the end of the load.
            withheld = true;
            return;
        }
        if (status == VerdictBoard.REJECTED && run.rejectionsTainted()) {
            // The environment pass failed on the incomplete file earlier in this run (pipelined load):
            // an outcome the admission recorded then may be behind this rejection. Fail closed.
            status = VerdictBoard.DECLINED;
            message = "rejected (" + reason + ") after the environment pass had failed on the incomplete file "
                    + "(pipelined load); not recorded as a rejection" + (message == null ? "" : ": " + message);
            reason = Reason.UNCONFIRMED_REJECT;
        }
        if (status == VerdictBoard.REJECTED) {
            board.reject(u, reason, message);
        } else {
            board.decline(u, reason, message);
        }
        mirror(u, status);
    }

    private void mirror(int u, int status) {
        if (mirror) {
            try {
                host.unitDecided(u, status);
            } catch (RuntimeException e) {
                // The host's own status column is a convenience for other readers, never the verdict.
                mirror = false;
                mirrorFailures++;
            }
        }
    }

    // ---- statistics -------------------------------------------------------------------------------------------

    private void seriesPoint(long now) {
        int w = SERIES_COLUMNS.length;
        if (seriesLen + w > series.length) {
            series = java.util.Arrays.copyOf(series, series.length * 2);
        }
        series[seriesLen] = now - run.startNanos();
        series[seriesLen + 1] = accepted;
        series[seriesLen + 2] = unitNanos;
        series[seriesLen + 3] = claimNanos;
        seriesLen += w;
        seriesNext = now + SERIES_NANOS;
    }

    /**
     * Where this worker's wall clock went, one value per {@link #TIME_COLUMNS} entry (statistics;
     * read after the thread has ended). {@code wall = cpu + runQueue + blocked}; of the blocked time
     * the driver knows the parks in the scheduler ({@code waitEnv / waitLoader / waitTail}, part of
     * {@code claimNanos}), the heavy gate and the wait for the end of the load; the rest is the
     * runtime's (collections stop every thread) or a lock inside a unit. The {@code ...At} values
     * are nanoseconds since the start of the run.
     */
    long[] times() {
        long base = run.startNanos();
        long parks = 0;
        for (int c = 0; c < Scheduler.WAIT_CLASSES; c++) {
            parks += scheduler.waitCount(id, c);
        }
        return new long[] {endAt - startAt, cpuNanos, runQueueNanos, unitNanos, claimNanos,
            scheduler.waitNanos(id, Scheduler.WAIT_ENV), scheduler.waitNanos(id, Scheduler.WAIT_LOADER),
            scheduler.waitNanos(id, Scheduler.WAIT_TAIL), gateWaitNanos, loadEndWaitNanos, openNanos, resetNanos,
            claimsTaken, parks, startAt - base, firstClaimAt == 0 ? -1 : firstClaimAt - base,
            lastUnitEndAt == 0 ? -1 : lastUnitEndAt - base, endAt - base, lazyEnvNanos, laneResetNanos};
    }

    /** The worker's time series: rows of {@link #SERIES_COLUMNS}, one about every 100 ms at a claim boundary. */
    long[][] series() {
        int w = SERIES_COLUMNS.length;
        long[][] rows = new long[seriesLen / w][];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = java.util.Arrays.copyOfRange(series, i * w, i * w + w);
        }
        return rows;
    }

    /** Appends this worker's unit trace ({@code RunConfig.unitTrace}) as lines "unit worker start nanos steps outcome weight". */
    void appendTrace(StringBuilder sb) {
        for (int i = 0; i < traceLen; i += 4) {
            long weight;
            try {
                weight = host.unitWeight((int) trace[i]);
            } catch (RuntimeException e) {
                weight = 0;
            }
            sb.append(trace[i]).append('\t').append(id).append('\t').append(trace[i + 1]).append('\t')
                    .append(trace[i + 2]).append('\t').append(trace[i + 3] >> 2).append('\t').append(trace[i + 3] & 3)
                    .append('\t').append(weight).append('\n');
        }
    }

    /** Units of other shards that this worker passed over without checking ({@code RunConfig.shardCount}). */
    long shardSkipped() {
        return shardSkipped;
    }

    /** Units admitted without a check because they repeat an accepted unit ({@code RunConfig.skipRepeats}). */
    long repeatsSkipped() {
        return repeatsSkipped;
    }

    /** Of those: units that were checked first, while their original was in flight, and not accepted by that check. */
    long repeatsAwaited() {
        return repeatsAwaited;
    }

    public int id() {
        return id;
    }

    /** Units this worker accepted (statistics). */
    public long unitsDone() {
        return accepted;
    }

    public long sessionResets() {
        return resets;
    }

    /** Units this worker skipped or abandoned because they lay above the first failure. */
    public long unitsCancelled() {
        return cancelled;
    }

    /** Units whose warm attempt did not end with ACCEPT and went through the re-check protocol. */
    public long rechecks() {
        return rechecks;
    }

    long mirrorFailures() {
        return mirrorFailures;
    }

    long envResets() {
        return env == null ? 0 : env.resets();
    }

    /** Resets of the warm lane because its unused capacity had grown (give-back). */
    public long giveBacks() {
        return giveBacks;
    }

    long givenBackBytes() {
        return givenBackBytes;
    }

    /** Largest unused capacity the warm lane held after a unit. */
    long maxSlackBytes() {
        return maxSlack;
    }

    /** Resets of the warm session because a unit lay beyond the lane's snapshot (pipelined loading). */
    long snapshotResets() {
        return snapshotResets;
    }

    /** Units whose decline was withheld until the end of the load and decided again. */
    long deferredUnits() {
        return deferred;
    }

    /** Times the rest of a claim was given back while its slow unit was still running. */
    long restsReleasedInUnit() {
        return restsReleasedInUnit;
    }

    /** Stages run while holding the heavy gate. */
    long gatedStages() {
        return gated;
    }
}
