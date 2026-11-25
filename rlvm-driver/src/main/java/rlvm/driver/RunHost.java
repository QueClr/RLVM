package rlvm.driver;

import rlvm.admit.EnvBuilder;
import rlvm.api.Budget;
import rlvm.api.Reason;
import rlvm.api.RunSignals;
import rlvm.api.Session;
import rlvm.api.WorkerContext;
import rlvm.store.Store;

/**
 * The driver's view of everything below it: the unit table, the publication frontiers, and the
 * per-thread resources (contexts, admission, the environment pass). The production implementation
 * is {@link StoreHost}; the driver's tests substitute fakes, which is why this seam exists: the
 * scheduler, the re-check protocol and the verdict rule are independent of what a unit is.
 *
 * <p>All unit-table methods are called from several threads and must be safe for that; they are
 * called once per unit, never per step.
 */
public interface RunHost {

    /** Unit class: an axiom, definition, theorem or opaque; a worker admits it. */
    int CHECK = 0;
    /** Unit class: an inductive block or a quot record; the environment pass decides it. */
    int ENV = 1;
    /** Unit class: a dropped (unsafe or partial) record; counts as accepted, is never checked. */
    int SKIP = 2;
    /** Unit class: anything else. The driver declines such a unit. */
    int UNKNOWN = 3;

    /** The store engines are created over. May be null in tests with fake engines. */
    Store store();

    /** Units {@code [0, n)} are completely loaded. */
    int loadedUnits();

    /** True once the loader reached the end of the file without error. */
    boolean loadComplete();

    /** Units {@code [0, n)} have passed the environment pass; a worker may check a unit below it. */
    int envUnits();

    /** One of {@link #CHECK}, {@link #ENV}, {@link #SKIP}, {@link #UNKNOWN}. */
    int unitClass(int unit);

    /** Position of the unit in admission order (sessions only move forward in it). */
    int unitRank(int unit);

    /** Diagnostics only: the name of the unit's (first) constant. */
    String unitName(int unit);

    /**
     * Pipelined loading, the parking rule: true if {@code unit} (loaded; a {@link #CHECK} unit) may
     * depend on a part of the file that is not loaded yet, in a way that the environment pass, which
     * goes through the file in file order while it loads, cannot see: the pass would move its
     * frontier past the unit, a worker could then only decline it (a forward reference), where a batch
     * run lets the pass decide it over the complete file (admission in dependency order). The
     * environment thread does not advance past such a unit before the end of the load. The answer
     * may err on the side of true (the frontier then waits for the loader at that unit); it is never
     * asked once the load has ended.
     */
    default boolean unitNeedsCompleteFile(int unit) {
        return false;
    }

    /**
     * Statistics only: a size of the unit that is known without checking it (production: the nodes of
     * its proof blocks plus the shared terms its records added); 0 = unknown. Written to the unit
     * trace beside the time the unit took, to see what the size predicts.
     */
    default long unitWeight(int unit) {
        return 0;
    }

    /**
     * The first unit of the file that {@code unit} (a {@link #CHECK} unit) repeats word for word under
     * another name, or -1: a smaller unit with a declaration of the same kind (definition, theorem or
     * opaque), the same universe parameters, the same reducibility hint and the same type and value as
     * records of the input (production: {@code DeclTable.repeatOf}, which the loader fills). Only a hint:
     * a worker does not run the primary engine on such a unit once the earlier one is ACCEPTED
     * ({@code RunConfig.skipRepeats}). The default says that no unit repeats another.
     */
    default int unitRepeatOf(int unit) {
        return -1;
    }

    /** Bytes held by the shared store, for the memory account. */
    long sharedBytes();

    /** {@link #retryEnvUnit}: the unit cannot be processed again. */
    int RETRY_NO = 0;
    /** {@link #retryEnvUnit}: the failed attempt left nothing behind but the unit's own failure. */
    int RETRY_CLEAN = 1;
    /**
     * {@link #retryEnvUnit}: the failed attempt had also written down failures of other units (units it
     * had admitted out of order); they were erased, but what else rests on them is not known.
     */
    int RETRY_TAINTED = 2;

    /**
     * Pipelined loading: the environment pass failed at {@code unit} (decline or rejection) while the
     * file was still being loaded, and the load is complete now. Prepares the unit for a second attempt
     * by a new {@link EnvPass} (see {@link #newEnvPass}), which must resume at this unit.
     *
     * @return {@link #RETRY_NO} (the driver then declines the unit), {@link #RETRY_CLEAN}, or
     *     {@link #RETRY_TAINTED} (the driver then records no rejection for the rest of the run)
     */
    default int retryEnvUnit(int unit) {
        return RETRY_NO;
    }

    /**
     * The final status of a unit was recorded on the board ({@code VerdictBoard.ACCEPTED},
     * {@code REJECTED} or {@code DECLINED}); the host may mirror it into its own table.
     */
    void unitDecided(int unit, int boardStatus);

    /**
     * A new lane for the calling thread.
     *
     * @param workerId the id the lane's {@code WorkerContext} carries (see {@link Wiring#newWorkerContext})
     * @param overlayMaxBytes hard cap of the lane's term overlay
     */
    Lane newLane(int workerId, RunSignals signals, long overlayMaxBytes);

    /**
     * The environment pass over this host's units, using the judgments of {@code referenceSession}
     * (a session of the reference engine created over {@code lane.context()}).
     */
    EnvPass newEnvPass(Lane lane, Session referenceSession, EnvBuilder.Listener listener);

    /**
     * One thread's private resources for one session: the {@code WorkerContext} the session is
     * created over, and the admission flow that runs on it. Confined to the thread that created it.
     */
    interface Lane {

        /** The context to pass to {@code Engine.newSession}. */
        WorkerContext context();

        /** Start of a unit: re-reads whatever the loader or the environment pass may have reallocated. */
        void beginUnit();

        /**
         * Admits one {@link RunHost#CHECK} unit with a session created over {@link #context()}:
         * structural checks, then {@code Session.check}.
         *
         * @return an {@code Outcome} code; on anything but ACCEPT {@link #lastReason()} and
         *     {@link #lastMessage()} describe it
         */
        int admit(int unit, Session session, Budget budget);

        Reason lastReason();

        /** Built on demand; may be slow. */
        String lastMessage();

        /** Resets the context's overlays; called together with {@code Session.reset()}. */
        void reset();

        /**
         * Pipelined loading: units {@code [0, coveredUnits())} may be admitted on this lane; its
         * context was built (or last {@link #reset()}) on a level, list and literal snapshot that
         * covers them ({@code WorkerContext.coveredUnits()}). The worker resets the lane before a
         * unit at or above the bound. {@code Integer.MAX_VALUE}: everything (batch runs).
         */
        default int coveredUnits() {
            return Integer.MAX_VALUE;
        }

        /**
         * Bytes the context holds beyond what its session's {@code footprintBytes()} counts: capacity
         * that is allocated but not in use (a term overlay that a heavy declaration made grow keeps
         * its arrays until {@link #reset()}), and index structures. The memory account adds it to the
         * footprint, and the worker resets a lane whose slack grew too much (give-back).
         */
        default long slackBytes() {
            return 0;
        }

        void close();
    }

    /** The environment pass (production: {@code rlvm.admit.EnvBuilder}). One thread at a time. */
    interface EnvPass {

        /**
         * Processes every loaded unit below {@code unit} in order, reporting the units it decides
         * itself to the listener; stops for good at the first unit that fails.
         *
         * @return the new {@code envUnits}
         */
        int advanceTo(int unit);

        void cancel();
    }
}
