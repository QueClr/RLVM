package rlvm.driver;

import rlvm.admit.EnvBuilder;
import rlvm.api.Engine;
import rlvm.api.Reason;
import rlvm.api.RunSignals;
import rlvm.api.Session;

/**
 * The environment pass of one run with the session it uses: a session of the reference engine over
 * its own lane, and the host's {@code EnvPass} (production: {@code rlvm.admit.EnvBuilder}). Confined
 * to one thread: the single worker of a batch {@code -j 1} run, which advances it lazily in strict
 * file order, or the environment thread.
 *
 * <p>As the pass's listener it records the units the pass decides itself (inductive blocks, quot):
 * an admitted unit is ACCEPTED; a failed unit is REJECTED (no confirmation needed: the environment
 * pass is the reference kernel, *agentic-note*: design-architecture-document, 8.2) or DECLINED, unless it lies above the first
 * failure, in which case its outcome can no longer matter and may be a cancellation.
 *
 * <p><b>Pipelined loading.</b> The session is reset before the pass moves beyond the frontier its
 * lane was built on (level, list and literal snapshot). A failure of the pass while the file is
 * still loading is withheld ({@link #pendingUnit()}): a decline may only mean "not loaded yet", a
 * rejection may rest on a name a later record provides. Whoever drives the pass waits for the end
 * of the load and calls {@link #retry()}, which lets a new pass decide the unit again; if the host
 * cannot do that, a decline is recorded (never a rejection); if the failed attempt had written down
 * failures of other units as well, the run records no rejection any more ({@code Run.taintRejections}).
 * After the end of the load the lane is reset, so that its level and list view reaches the end of
 * the file; the pass is kept (it is told how far the lane reaches and admits in dependency order
 * only when the lane covers the complete file).
 */
final class EnvLane implements EnvBuilder.Listener {

    private final VerdictBoard board;
    private final RunHost host;
    private final long sessionBytes;
    private final long keepBytes;
    private final Run run;
    private final RunHost.Lane lane;
    private final Session session;
    private RunHost.EnvPass pass;
    /** The current pass was created before the end of a pipelined load. */
    private boolean passWhileLoading;
    private long renewals;
    private long resets;
    private long decided;
    private long retries;
    /** Units below this bound are covered by what the lane saw at its creation or last reset. */
    private int laneUnits;
    private long baseSlack;
    /** The file was still loading when the current {@link #advanceTo} began. */
    private boolean loading;
    /** The pass reported a failure during the current {@link #advanceTo}. */
    private boolean reported;

    private int pendingUnit = -1;
    private Reason pendingReason;
    private String pendingMessage;

    /** Opens the lane, the reference session and the pass on the calling thread. */
    EnvLane(Run run, RunSignals signals) {
        this.board = run.verdicts();
        this.keepBytes = run.config().keepBytes;
        this.run = run;
        this.host = run.host();
        int frontier = frontierNow();
        this.lane = host.newLane(run.workerCount(), signals, run.config().memoryLimitBytes);
        this.laneUnits = Math.min(frontier, lane.coveredUnits());
        Engine reference = run.referenceEngine();
        // The pass runs on the reference kernel: its cap, not the primary engine's.
        this.sessionBytes = run.config().sessionBytesFor(Run.nameOf(reference));
        this.session = reference.newSession(lane.context());
        this.baseSlack = lane.slackBytes();
        this.passWhileLoading = !run.loadEnded();
        this.pass = host.newEnvPass(lane, session, this);
    }

    private int frontierNow() {
        // The end of the load is read first: after it the loaded frontier no longer moves.
        return run.loadEnded() ? Integer.MAX_VALUE : host.loadedUnits();
    }

    /** Processes every loaded unit below {@code target}; returns the new environment frontier. */
    int advanceTo(int target) {
        boolean ended = run.loadEnded();
        if (target > laneUnits || (laneUnits != Integer.MAX_VALUE && ended)) {
            // The pass keeps no overlay id between two calls, so the lane may move to the newest
            // snapshot: before a unit beyond its own, and once the load has ended in any case (the
            // pass may then read the whole file: admission in dependency order).
            reset();
        }
        if (passWhileLoading && ended) {
            // The pass that followed the loader is KEPT over the end of the load. It knows how far its
            // lane reaches (EnvBuilder.setCoverage through the host) and enters dependency order by
            // itself once the file is complete and the lane, reset just above, covers it. Until
            // 16:10Z of 2026-11-24 it was replaced here by a pass created over the complete file; but a
            // pass that had ALREADY left file order (the load ended while it was advancing over a lane
            // that covered the last publication) has decided units ahead of the frontier and dropped
            // their claims, and its replacement, which starts at the frontier, declined them
            // ("the inductive unit has no claims blob": rlvm-admit, requests.log 15:12:24Z).
            passWhileLoading = false;
        }
        loading = !run.loadEnded();
        reported = false;
        lane.beginUnit();
        int n = pass.advanceTo(target);
        if (session.footprintBytes() > sessionBytes
                || (keepBytes > 0 && lane.slackBytes() - baseSlack > keepBytes)) {
            reset();
        }
        return n;
    }

    private void reset() {
        int frontier = frontierNow();
        session.reset();
        lane.reset();
        laneUnits = Math.min(frontier, lane.coveredUnits());
        baseSlack = lane.slackBytes();
        resets++;
    }

    /**
     * True if the last {@link #advanceTo} began while the file was still loading and the pass
     * stopped short without reporting a failure: it waits for the rest of the file
     * ({@code EnvBuilder.waitingForLoad()}); the same pass is advanced again after the load.
     */
    boolean waitingForLoad() {
        return loading && !reported && pendingUnit < 0;
    }

    /** The unit whose decline was withheld because the file was still loading, or -1. */
    int pendingUnit() {
        return pendingUnit;
    }

    /**
     * The load has ended: decides the pending unit for good. If the host can prepare the unit for a
     * second attempt, a new pass is created (it resumes at that unit) and true is returned: the
     * caller goes on advancing. Otherwise the withheld decline is recorded and false is returned.
     */
    boolean retry() {
        int u = pendingUnit;
        if (u < 0) {
            return true;
        }
        Reason reason = pendingReason;
        String message = pendingMessage;
        pendingUnit = -1;
        pendingReason = null;
        pendingMessage = null;
        int again;
        try {
            again = host.retryEnvUnit(u);
        } catch (RuntimeException e) {
            again = RunHost.RETRY_NO;
        }
        if (again != RunHost.RETRY_NO) {
            if (again != RunHost.RETRY_CLEAN) {
                // The failed attempt had written down failures of other units too; they are erased, but
                // the driver cannot know what else rests on them: no rejection is recorded any more.
                run.taintRejections();
            }
            retries++;
            reset();
            passWhileLoading = false;
            pass = host.newEnvPass(lane, session, this);
            return true;
        }
        if (u <= board.firstFailure()) {
            // What was said about an incomplete file is never a rejection.
            board.decline(u, reason == null ? Reason.INTERNAL_ERROR : reason.isReject() ? Reason.UNCONFIRMED_REJECT : reason,
                    message);
        }
        return false;
    }

    long footprintBytes() {
        return session.footprintBytes() + lane.slackBytes();
    }

    long resets() {
        return resets;
    }

    long decided() {
        return decided;
    }

    long retries() {
        return retries;
    }

    /** Passes created after the end of a pipelined load to replace the one that followed the loader (none since the pass is kept). */
    long renewals() {
        return renewals;
    }

    void cancel() {
        pass.cancel();
    }

    void close() {
        try {
            session.close();
        } finally {
            lane.close();
        }
    }

    @Override
    public void unitAdmitted(int unit) {
        decided++;
        if (host.unitClass(unit) != RunHost.ENV) {
            // A plain unit the pass decided itself (admission in dependency order): the worker that
            // draws it reads the recorded outcome from the admission and writes the board.
            return;
        }
        board.accept(unit);
    }

    @Override
    public void unitFailed(int unit, Reason reason, String message) {
        decided++;
        reported = true;
        if (unit > board.firstFailure()) {
            return; // abandoned: behind the first failure
        }
        if (loading) {
            // The pass may have seen an incomplete file: a decline may only mean "not loaded yet", a
            // rejection may rest on a name that a later record provides. Decided again after the load,
            // by a pass that is created over the complete file.
            pendingUnit = unit;
            pendingReason = reason;
            pendingMessage = message;
        } else if (reason != null && reason.isReject()) {
            if (run.rejectionsTainted()) {
                board.decline(unit, Reason.UNCONFIRMED_REJECT, "rejected (" + reason + ") after the environment pass "
                        + "had failed on the incomplete file (pipelined load); not recorded as a rejection: " + message);
            } else {
                board.reject(unit, reason, message);
            }
        } else {
            board.decline(unit, reason == null ? Reason.INTERNAL_ERROR : reason, message);
        }
    }

    @Override
    public void frontierAdvanced(int units) {
        // The scheduler is told once per batch by whoever drives the pass, not once per unit.
    }
}
