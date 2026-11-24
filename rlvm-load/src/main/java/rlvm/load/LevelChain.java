package rlvm.load;

import rlvm.level.IntListStore;
import rlvm.level.LevelLimitException;
import rlvm.level.LevelStore;
import rlvm.store.IntLists;
import rlvm.store.LevelInfo;

/**
 * The level store of a pipelined load: a {@link LevelInterner} that hands out <b>snapshots</b>, so that
 * worker threads can read (and build their private overlays on) the levels and lists of the units
 * already published while the loader goes on creating new ones.
 *
 * <p>{@code rlvm.level.LevelStore} is single-threaded until it is frozen, and a frozen store accepts
 * nothing new. The chain uses exactly these two facts and nothing else of the level module:
 *
 * <pre>
 *   open store W0 (the root given to the constructor): the loader interns into it
 *   publish(units):  W0.freeze()                      -> snapshot 1 = W0, immutable, readable by all
 *                    W1 = W0.newOverlay()             -> the loader goes on in W1 (ids continue)
 *   publish(units):  W1.freeze()                      -> snapshot 2 = W1 ...
 * </pre>
 *
 * Every store of the chain extends its predecessor with the same ids: a level or list id means the
 * same thing in every snapshot that contains it, and in every worker overlay made from one
 * ({@code snapshot.levels.newOverlay()}). A snapshot is published through one volatile field
 * <em>before</em> the store frontier that needs it ({@link LoadListener}), so the protocol of a reader
 * is: read {@code Store.loadedUnits()} = n, then {@link #current()}; that snapshot covers units
 * {@code [0, n)} (its {@link Snapshot#units} is at least n). A view built on a snapshot serves units
 * below that snapshot's {@code units}; before the reader touches a later unit it reads the frontier
 * and {@link #current()} again and builds a new view only if {@link Snapshot#levels} is another store
 * (a publication that created no level and no list keeps the store and only raises {@code units}).
 *
 * <p>Cost: one {@code freeze()} of the levels added since the previous snapshot and one copy of the
 * level arrays per publication that added a level or a list (mathlib: 48 k levels, 184 k lists, about
 * 0.7 ms per copy); a publication that added none reuses the store of the previous snapshot.
 *
 * <p>Difference from a batch load: {@code freeze()} adds the official normal forms of the levels to
 * the store it freezes. In a batch load they all follow the export's levels; here they are
 * interleaved with them, so the <em>numbering</em> of levels differs from a batch load of the same
 * file (list ids, term ids, name ids and constant ids do not). The two stores are equal up to that
 * renaming, level by level (tested record by record: {@code PipelinedLoadTest}).
 *
 * <p>Threads: the interning methods and {@link #publish} belong to the loader thread (the thread that
 * created the chain, or the one that called {@link #attach()} last). {@link #current()} may be
 * called by anyone. The two store adapters {@link #lists()} and {@link #info()} serve the loader
 * thread from the open store; called from any other thread they read the current snapshot and fail
 * ({@link IllegalStateException}) for an id it does not contain or for an operation that would
 * create something: an unpublished datum is never returned.
 */
public final class LevelChain implements LevelInterner {

    /** An immutable state of the chain. */
    public static final class Snapshot {
        /** The frozen level store: read it from any thread, or take a private {@code newOverlay()}. */
        public final LevelStore levels;
        /** Units {@code [0, units)} of the store mention only levels and lists of {@link #levels}. */
        public final int units;
        /** Counts the distinct stores published so far (1, 2, ...): a new value means new levels or lists. */
        public final int generation;
        /** True for the snapshot made at the end of a load that reached the end of the file. */
        public final boolean last;
        final IntListStore lists;
        final int levelCount;
        final int listCount;

        Snapshot(LevelStore levels, int units, int generation, boolean last) {
            this.levels = levels;
            this.units = units;
            this.generation = generation;
            this.last = last;
            this.lists = levels.lists();
            this.levelCount = levels.size();
            this.listCount = levels.listCount();
        }
    }

    /** Entries of the direct-mapped memo of {@link IntLists#unionSorted}. */
    private static final int UNION_CACHE = 512;

    private volatile Thread owner;
    /** The store the loader interns into; null once the chain is closed. Loader thread only. */
    private LevelStore open;
    private IntListStore openLists;
    private volatile Snapshot current;
    private int generation;
    private int lastUnits;
    private long freezeNanos;
    private long copyNanos;

    private final Lists listsView = new Lists();
    private final Info infoView = new Info();

    /**
     * @param root the run's unfrozen base level store (it becomes the first snapshot at the first
     *     publication; everything created through this chain afterwards lives in overlays of it)
     */
    public LevelChain(LevelStore root) {
        if (root.isFrozen()) {
            throw new IllegalArgumentException("the chain starts from an unfrozen level store");
        }
        this.open = root;
        this.openLists = root.lists();
        this.owner = Thread.currentThread();
    }

    /** Makes the calling thread the loader thread of this chain (the loader does it at the first record). */
    @Override
    public void attach() {
        owner = Thread.currentThread();
    }

    // ---- snapshots ---------------------------------------------------------------------------------

    /** The newest snapshot; null before the first publication. Any thread. */
    public Snapshot current() {
        return current;
    }

    /** True once {@link #finish(int)} ran: the chain accepts nothing new, {@link #current()} is final. */
    public boolean isClosed() {
        Snapshot s = current;
        return s != null && s.last;
    }

    /** Loader thread: the store being written (null once closed). For tools and tests. */
    public LevelStore open() {
        return writer();
    }

    /** Nanoseconds spent freezing and copying, for the load report. */
    public long snapshotNanos() {
        return freezeNanos + copyNanos;
    }

    /** Number of distinct stores published so far. */
    public int generations() {
        return generation;
    }

    @Override
    public void publish() {
        publish(lastUnits);
    }

    @Override
    public void publish(int units) {
        snapshot(units, false);
    }

    @Override
    public void finish(int units) {
        snapshot(units, true);
    }

    private void snapshot(int units, boolean last) {
        LevelStore w = writer();
        if (w == null) {
            throw new IllegalStateException("the level chain is closed");
        }
        if (units < lastUnits) {
            throw new IllegalArgumentException("publications are monotone: " + units + " < " + lastUnits);
        }
        lastUnits = units;
        Snapshot s = current;
        if (s != null && w.size() == s.levelCount && w.listCount() == s.listCount) {
            // Nothing was created since the previous snapshot: its store covers these units too, and
            // the open store is still an untouched copy of it.
            if (units != s.units || last) {
                current = new Snapshot(s.levels, units, s.generation, last);
            }
            if (last) {
                open = null;
                openLists = null;
            }
            return;
        }
        long t0 = System.nanoTime();
        w.freeze();
        long t1 = System.nanoTime();
        Snapshot n = new Snapshot(w, units, ++generation, last);
        if (last) {
            open = null;
            openLists = null;
        } else {
            LevelStore next = w.newOverlay();
            open = next;
            openLists = next.lists();
        }
        freezeNanos += t1 - t0;
        copyNanos += System.nanoTime() - t1;
        current = n; // the volatile write that publishes the snapshot
    }

    private LevelStore writer() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("the open level store belongs to the loader thread");
        }
        return open;
    }

    private LevelStore writing() {
        LevelStore w = writer();
        if (w == null) {
            throw new IllegalStateException("the level chain is closed: the load has finished");
        }
        return w;
    }

    // ---- LevelInterner (loader thread) ---------------------------------------------------------------

    @Override
    public int zero() {
        return LevelStore.ZERO;
    }

    @Override
    public int succ(int level) {
        try {
            return writing().succ(level);
        } catch (LevelLimitException e) {
            throw new UnsupportedExportException("universe level too deep: " + e.getMessage());
        }
    }

    @Override
    public int max(int a, int b) {
        try {
            return writing().max(a, b);
        } catch (LevelLimitException e) {
            throw new UnsupportedExportException("universe level too deep: " + e.getMessage());
        }
    }

    @Override
    public int imax(int a, int b) {
        try {
            return writing().imax(a, b);
        } catch (LevelLimitException e) {
            throw new UnsupportedExportException("universe level too deep: " + e.getMessage());
        }
    }

    @Override
    public int param(int nameId) {
        return writing().param(nameId);
    }

    @Override
    public int internList(int[] buf, int from, int len) {
        return writing().internList(buf, from, len);
    }

    @Override
    public boolean hasDuplicates(int nameList) {
        return writing().listHasDuplicates(nameList);
    }

    @Override
    public LevelStore structure() {
        return writing();
    }

    // ---- the store's adapters ------------------------------------------------------------------------

    /** The list store for {@code new Store(bytes, lists(), info())}: follows the open store on the loader thread. */
    public IntLists lists() {
        return listsView;
    }

    /** The level view for {@code new Store(bytes, lists(), info())}: follows the open store on the loader thread. */
    public LevelInfo info() {
        return infoView;
    }

    /** The list store that may answer for list {@code id} on the calling thread. */
    private IntListStore listsFor(int id) {
        if (Thread.currentThread() == owner) {
            IntListStore l = openLists;
            if (l != null) {
                return l;
            }
        }
        Snapshot s = current;
        if (s == null || id < 0 || id >= s.listCount) {
            throw new IllegalStateException("list " + id + " is not published to this thread");
        }
        return s.lists;
    }

    /** The level store that may answer for level {@code id} on the calling thread. */
    private LevelStore levelsFor(int id) {
        if (Thread.currentThread() == owner) {
            LevelStore w = open;
            if (w != null) {
                return w;
            }
        }
        Snapshot s = current;
        if (s == null || id <= 0 || id >= s.levelCount) {
            throw new IllegalStateException("level " + id + " is not published to this thread");
        }
        return s.levels;
    }

    private final class Lists implements IntLists {
        private int[] scratch = new int[32];
        private final long[] unionKey = new long[UNION_CACHE];
        private final int[] unionVal = new int[UNION_CACHE];

        // The memo outlives the open store it was filled in: ids are stable along the chain.

        @Override
        public int intern(int[] buf, int from, int len) {
            return writing().internList(buf, from, len);
        }

        @Override
        public int length(int list) {
            return listsFor(list).length(list);
        }

        @Override
        public int get(int list, int index) {
            return listsFor(list).get(list, index);
        }

        @Override
        public void copyTo(int list, int[] dst, int dstOff) {
            listsFor(list).copyTo(list, dst, dstOff);
        }

        /**
         * Union of two ascending duplicate-free lists. Returns one of the arguments when it already is
         * the union; other results are interned and memoised on the id pair (loader thread only: any
         * other thread works on its own overlay).
         */
        @Override
        public int unionSorted(int setA, int setB) {
            if (setA == setB || setB == 0) {
                return setA;
            }
            if (setA == 0) {
                return setB;
            }
            LevelStore w = writing();
            long key = ((long) setA << 32) | (setB & 0xFFFFFFFFL);
            int slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> 55) & (UNION_CACHE - 1);
            if (unionKey[slot] == key) {
                return unionVal[slot];
            }
            IntListStore lists = openLists;
            int na = lists.length(setA);
            int nb = lists.length(setB);
            if (scratch.length < na + nb) {
                scratch = new int[Math.max(na + nb, scratch.length * 2)];
            }
            int[] out = scratch;
            int[] pool = lists.pool();
            int i = lists.start(setA);
            int ia = i + na;
            int j = lists.start(setB);
            int jb = j + nb;
            int n = 0;
            while (i < ia && j < jb) {
                int x = pool[i];
                int y = pool[j];
                if (x == y) {
                    out[n++] = x;
                    i++;
                    j++;
                } else if (x < y) {
                    out[n++] = x;
                    i++;
                } else {
                    out[n++] = y;
                    j++;
                }
            }
            while (i < ia) {
                out[n++] = pool[i++];
            }
            while (j < jb) {
                out[n++] = pool[j++];
            }
            int r = n == na ? setA : n == nb ? setB : w.internList(out, 0, n);
            unionKey[slot] = key;
            unionVal[slot] = r;
            return r;
        }
    }

    private final class Info implements LevelInfo {
        @Override
        public boolean hasParam(int level) {
            return levelsFor(level).hasParam(level);
        }

        @Override
        public int paramName(int level) {
            LevelStore ls = levelsFor(level);
            return ls.kind(level) == LevelStore.KIND_PARAM ? ls.paramName(level) : -1;
        }

        @Override
        public int lhs(int level) {
            LevelStore ls = levelsFor(level);
            switch (ls.kind(level)) {
                case LevelStore.KIND_SUCC:
                    return ls.succOf(level);
                case LevelStore.KIND_MAX:
                case LevelStore.KIND_IMAX:
                    return ls.lhs(level);
                default:
                    return 0;
            }
        }

        @Override
        public int rhs(int level) {
            LevelStore ls = levelsFor(level);
            int k = ls.kind(level);
            return k == LevelStore.KIND_MAX || k == LevelStore.KIND_IMAX ? ls.rhs(level) : 0;
        }

        @Override
        public int instantiate(int level, int paramNames, int levelList) {
            return writing().instantiate(level, paramNames, levelList);
        }

        @Override
        public int instantiateList(int list, int paramNames, int levelList) {
            LevelStore w = writing();
            int subst = w.mkSubst(paramNames, levelList);
            if (subst < 0) {
                throw new IllegalArgumentException("wrong number of universe levels");
            }
            return w.instantiateList(list, subst);
        }
    }
}
