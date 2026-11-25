package rlvm.api.bind;

import java.util.function.Supplier;

import rlvm.api.RunSignals;
import rlvm.api.WorkerContext;
import rlvm.level.LevelStore;
import rlvm.nat.NatStore;
import rlvm.store.IntLists;
import rlvm.store.LevelInfo;
import rlvm.store.LitPool;
import rlvm.store.NameTable;
import rlvm.store.Overlay;
import rlvm.store.Store;
import rlvm.store.Terms;

/**
 * The level and number stores of one run and the factory of per-worker views: what ties
 * {@code rlvm-level} and {@code rlvm-nat} to the store and to {@link WorkerContext}.
 *
 * <pre>
 * Bindings b = new Bindings(expectedLevels);        // base level store (+ its list store), empty number base
 * Store store = new Store(inputBytes, b.baseLists(), b.baseLevelInfo());   // the run's ONE list store
 * b.bindNames(store.names());                       // Lean's name order for the official level normal form
 * ... loader thread: b.baseLevels() interns the export's levels and lists ...
 * b.freeze();                                       // batch mode: once, at end of file, before any worker starts
 * WorkerContext ctx = b.newWorkerContext(id, store, signals, overlayMaxBytes);   // on (or handed to) the worker thread
 * </pre>
 *
 * <p>Threading: everything before {@link #freeze()} belongs to the loader thread. {@code freeze()}
 * must happen-before the creation of worker views (thread start, a join, or a volatile publication;
 * {@link #isFrozen()} is volatile). {@link #newWorkerContext} may be called from any thread; the
 * context then belongs to one thread.
 *
 * <p><b>Pipelined loading</b> (wave 4). The loader publishes units while it reads, and with them
 * frozen <em>snapshots</em> of the level store: a chain base, overlay of the base, overlay of that, ...
 * in which every store extends its predecessor with the same ids ({@code rlvm.load.LevelChain}). The
 * run tells this factory where the newest snapshot is ({@link #bindLevelSnapshots}); {@link #freeze()}
 * is then never called. A worker context is built on the snapshot that is current when it is created
 * and moves to the newest one at every {@code WorkerContext.reset()}. It shares the snapshot with every
 * other worker (a snapshot is immutable from its publication on) and copies none of it:
 *
 * <pre>
 * Bindings b = new Bindings(expectedLevels);
 * LevelChain chain = new LevelChain(b.levelBase());                 // rlvm-load; the loader interns through it
 * Store store = new Store(inputBytes, chain.lists(), chain.info()); // the chain's adapters, not baseLists()
 * b.bindNames(store.names());
 * b.bindLevelSnapshots(() -> { var s = chain.current(); return s == null ? null : s.levels; });
 * ... loader thread: Loader(store, options, chain, ...) publishes; other threads, after the first publication:
 * WorkerContext ctx = b.newWorkerContext(id, store, signals, overlayMaxBytes);   // covers [0, ctx.coveredUnits())
 * ... before a unit at or above ctx.coveredUnits():  session.reset(); ctx.reset();
 * </pre>
 */
public final class Bindings {

    private final NameOracles.Late oracle = NameOracles.late();
    private final LevelStore levelBase;
    private final LevelBinding baseBinding;
    private final NatStore natBase;
    private volatile boolean frozen;
    /** Pipelined mode: the newest frozen level snapshot (null before the first publication). */
    private volatile Supplier<LevelStore> snapshots;

    public Bindings() {
        this(1024);
    }

    /** @param expectedLevels sizing hint for the level store (number of level records of the export) */
    public Bindings(int expectedLevels) {
        this.levelBase = new LevelStore(oracle, Math.max(16, expectedLevels));
        this.baseBinding = new LevelBinding(levelBase);
        this.natBase = NatStore.newBase();
    }

    /** Supplies the name table whose order ({@code NameTable.compare}) the level normal form uses. Once. */
    public void bindNames(NameTable names) {
        oracle.bind(names);
    }

    /** {@link #bindNames(NameTable)} for tools and tests that have no name table: the order itself. Once. */
    public void bindNameOrder(rlvm.level.NameOracle order) {
        oracle.bind(order);
    }

    /** True if the name order was used before it was bound (a wiring error; see {@link NameOracles.Late}). */
    public boolean nameOrderUsedUnbound() {
        return oracle.usedUnbound();
    }

    /** The run's base level store. */
    public LevelStore levelBase() {
        return levelBase;
    }

    /** {@code LevelOps} + {@code IntLists} over the base store: for the loader thread until {@link #freeze()}. */
    public LevelBinding baseLevels() {
        return baseBinding;
    }

    /** The run's base list store (level lists, name lists, name sets): what {@code Store.lists()} returns. */
    public IntLists baseLists() {
        return baseBinding;
    }

    /** The loader thread's level view for the store's node writer ({@code new Store(bytes, baseLists(), baseLevelInfo())}). */
    public LevelInfo baseLevelInfo() {
        return baseBinding;
    }

    /**
     * The base number store. It stays empty: export literals of 2^64 and above live in the store's
     * {@code LitPool} and are imported into a worker's overlay on first use (*agentic-note*: design-reconciliation-document,
     * "Literal identity").
     */
    public NatStore natBase() {
        return natBase;
    }

    /**
     * Batch mode: freezes the level store (computes normal and canonical forms of every base level)
     * and the number base. Idempotent. After it, the base accepts no new level or list.
     */
    public void freeze() {
        if (frozen) {
            return;
        }
        if (snapshots != null) {
            throw new IllegalStateException("a pipelined run has no freeze(): its level chain freezes the snapshots");
        }
        if (!oracle.isBound()) {
            throw new IllegalStateException("bindNames(store.names()) must be called before freeze()");
        }
        levelBase.freeze();
        natBase.freeze();
        frozen = true;
    }

    public boolean isFrozen() {
        return frozen;
    }

    /**
     * Pipelined mode: tells the factory where the newest frozen snapshot of the level store is. The
     * supplier is called from worker threads; it returns null before the first publication, and
     * every store it returns extends the previous one with the same ids. The (empty) number base is
     * frozen here, so that worker number stores can be made before the load ends. Once; not after
     * {@link #freeze()}.
     */
    public void bindLevelSnapshots(Supplier<LevelStore> newestFrozenSnapshot) {
        if (newestFrozenSnapshot == null) {
            throw new NullPointerException("newestFrozenSnapshot");
        }
        if (frozen) {
            throw new IllegalStateException("the base is frozen: this is a batch run");
        }
        if (snapshots != null) {
            throw new IllegalStateException("the snapshot source is bound once");
        }
        natBase.freeze();
        baseBinding.baseIsShared(false);
        snapshots = newestFrozenSnapshot;
    }

    /** True once {@link #bindLevelSnapshots} was called: worker views are built on snapshots. */
    public boolean isPipelined() {
        return snapshots != null;
    }

    /** The newest snapshot, or an {@code IllegalStateException} saying why there is none. */
    private LevelStore currentSnapshot() {
        LevelStore s = snapshots.get();
        if (s == null) {
            throw new IllegalStateException(
                    "no level snapshot yet: worker views of a pipelined load exist only after the first publication");
        }
        if (!s.isFrozen()) {
            throw new IllegalStateException("the level snapshot source returned a store that is not frozen");
        }
        return s;
    }

    /**
     * A new worker view of the levels: a shared overlay of the frozen base (batch), or of the newest
     * snapshot (pipelined). Nothing of the base is copied: every worker reads the one frozen store and
     * holds only what its session adds ({@code LevelStore.newSharedOverlay()}).
     */
    public LevelBinding newWorkerLevels() {
        if (snapshots != null) {
            LevelBinding b = new LevelBinding(currentSnapshot().newSharedOverlay());
            b.baseIsShared(false); // a snapshot is not the run's final base
            return b;
        }
        requireFrozen();
        return new LevelBinding(levelBase.newSharedOverlay());
    }

    /** A new worker number store (an overlay of the frozen, empty base). */
    public NatStore newWorkerNats() {
        if (snapshots == null) {
            requireFrozen();
        }
        return natBase.newOverlay();
    }

    /**
     * Everything a worker owns outside its engine session: term overlay, reader, level / list overlay,
     * number overlay and the {@code NatOps} over them.
     *
     * @param overlayMaxBytes hard cap of the term overlay ({@code Overlay.OverlayFullException} beyond)
     */
    public WorkerContext newWorkerContext(int workerId, Store store, RunSignals signals, long overlayMaxBytes) {
        final Supplier<LevelStore> source = snapshots;
        if (source == null) {
            requireFrozen();
        }
        // Pipelined: the frontier is read BEFORE the snapshot, so the snapshot covers at least it
        // (the loader publishes the snapshot before the store frontier that needs it).
        final int covered = source == null ? Integer.MAX_VALUE : store.loadedUnits();
        final LevelStore snapshot = source == null ? null : currentSnapshot();
        final LevelBinding levels = new LevelBinding(source == null ? levelBase.newSharedOverlay() : snapshot.newSharedOverlay());
        if (source != null) {
            levels.baseIsShared(false); // a snapshot is not the run's final base
        }
        LitPool lits = new LitPool(store.lits()); // the worker's own pool: computed numbers, negative indices
        Overlay overlay = new Overlay(store, levels, levels, lits, overlayMaxBytes);
        Terms terms = new Terms(store.terms(), overlay);
        NatBinding nats = new NatBinding(new StoreLitAccess(terms, overlay, lits), newWorkerNats());
        WorkerContext ctx = new WorkerContext(workerId, store, terms, overlay, levels, lits, levels, nats,
                signals == null ? RunSignals.NONE : signals);
        if (source != null) {
            final Store st = store;
            ctx.setRefresher(new WorkerContext.Refresher() {
                private LevelStore on = snapshot;

                @Override
                public int refresh() {
                    int units = st.loadedUnits(); // before the snapshot, as above
                    LevelStore newest = currentSnapshot();
                    if (newest != on) {
                        levels.rebaseOnto(newest); // in place: the worker reads the snapshot, it copies nothing
                        on = newest;
                    } else {
                        levels.reset();
                    }
                    return units;
                }
            }, covered);
        }
        return ctx;
    }

    /**
     * Kept for callers of the first skeleton. Pipelined loading does not snapshot through this
     * factory: the loader's level chain makes the snapshots and {@link #bindLevelSnapshots} tells the
     * factory where they are.
     */
    public void snapshot() {
        throw new UnsupportedOperationException(
                "pipelined loading takes its level snapshots from the loader (rlvm.load.LevelChain): call "
                        + "bindLevelSnapshots(() -> chain.current().levels) instead of snapshot()");
    }

    private void requireFrozen() {
        if (!frozen) {
            throw new IllegalStateException(
                    "worker views need the frozen base: call freeze() at end of load (batch mode), or "
                            + "bindLevelSnapshots(...) before a pipelined load");
        }
    }
}
