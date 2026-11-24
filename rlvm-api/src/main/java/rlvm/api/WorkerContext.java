package rlvm.api;

import rlvm.store.IntLists;
import rlvm.store.LitPool;
import rlvm.store.Overlay;
import rlvm.store.Store;
import rlvm.store.Terms;

/**
 * Everything one worker thread owns outside the engine: its reader over the store, its term overlay
 * and its list, level and literal overlays. Created by the driver (through
 * {@code rlvm.api.bind.Bindings#newWorkerContext}), handed to {@link Engine#newSession(WorkerContext)},
 * reset together with the session.
 *
 * <p>{@link #lists} is the list view of the same level overlay that backs {@link #levels} (one list
 * store per worker), so resetting the levels resets the lists. {@link #lits} is the shared literal
 * pool of the store unless the store hands workers a pool of their own; a worker-owned pool is reset
 * here, the shared one never.
 *
 * <p><b>Pipelined loading.</b> While the loader is still running, a context sees the levels and lists
 * of the units published when it was created or last {@link #reset()}: units {@code [0,
 * coveredUnits())}. The driver must reset the context before it hands it a unit at or above
 * {@link #coveredUnits()}; {@code reset()} then moves the level view to the newest snapshot
 * ({@link Refresher}). In a batch run everything is covered ({@code Integer.MAX_VALUE}).
 */
public final class WorkerContext {

    public final int workerId;
    public final Store store;
    public final Terms terms;
    public final Overlay overlay;
    public final IntLists lists;
    public final LitPool lits;
    public final LevelOps levels;
    public final NatOps nats;
    public final RunSignals signals;

    /**
     * Installed by the factory of a pipelined run: at every {@link #reset()} it puts the context's
     * level and list view on the newest published snapshot when there is a newer one.
     */
    public interface Refresher {
        /**
         * @return the number of units the context covers from now on (see {@link #coveredUnits()}):
         *     the refresher has then emptied the level and list overlay itself, by replacing it with
         *     a fresh overlay of a newer snapshot or by resetting it in place; or -1 if it did
         *     nothing, and the caller resets the level overlay in place
         */
        int refresh();
    }

    private Refresher refresher;
    private int coveredUnits = Integer.MAX_VALUE;

    public WorkerContext(int workerId, Store store, Terms terms, Overlay overlay, IntLists lists, LitPool lits,
            LevelOps levels, NatOps nats, RunSignals signals) {
        this.workerId = workerId;
        this.store = store;
        this.terms = terms;
        this.overlay = overlay;
        this.lists = lists;
        this.lits = lits;
        this.levels = levels;
        this.nats = nats;
        this.signals = signals;
    }

    /**
     * Resets every overlay of this context (called by the driver together with {@link Session#reset()}):
     * overlay term ids, level ids, list ids, substitution ids and computed literals handed out before
     * become invalid. Shared ids keep their meaning. Ends with {@code terms.refresh()}.
     */
    public void reset() {
        overlay.reset();
        int covered = refresher == null ? -1 : refresher.refresh();
        if (covered >= 0) {
            coveredUnits = covered; // the refresher emptied (or replaced) the level and list overlay
        } else {
            levels.reset();
            if (lists != levels.lists() && lists instanceof Resettable) {
                ((Resettable) lists).reset();
            }
        }
        // The pool first: the number binding then looks at the pool it will work with (NatBinding
        // watches the pool's entry count to notice truncations).
        if (lits != null && !isSharedPool()) {
            lits.reset();
        }
        nats.reset();
        terms.refresh();
    }

    /**
     * Units {@code [0, coveredUnits())} may be checked with this context: its level and list view
     * contains everything they mention. {@code Integer.MAX_VALUE} in a batch run (the base is frozen
     * and complete before any context exists). In a pipelined run it is the store's frontier
     * ({@code Store.loadedUnits()}) as read when the context was created or last reset, read
     * <em>before</em> the level snapshot was taken, so the snapshot covers at least these units.
     */
    public int coveredUnits() {
        return coveredUnits;
    }

    /**
     * For the factory of a pipelined run ({@code rlvm.api.bind.Bindings}): installs the refresher and
     * the number of units covered at creation. Not for engines.
     */
    public void setRefresher(Refresher refresher, int coveredUnits) {
        this.refresher = refresher;
        this.coveredUnits = coveredUnits;
    }

    /** Bytes held by the overlays of this context. */
    public long footprintBytes() {
        long n = overlay.usedBytes() + levels.footprintBytes() + nats.footprintBytes();
        if (lits != null && !isSharedPool()) {
            n += lits.bytes();
        }
        return n;
    }

    private boolean isSharedPool() {
        return store != null && lits == store.lits();
    }

    /** Implemented by a worker-owned {@link IntLists} that is not the list view of {@link #levels}. */
    public interface Resettable {
        void reset();
    }
}
