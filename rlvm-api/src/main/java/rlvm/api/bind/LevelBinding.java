package rlvm.api.bind;

import rlvm.api.KernelException;
import rlvm.api.LevelOps;
import rlvm.api.Reason;
import rlvm.level.IntListStore;
import rlvm.level.LevelStore;
import rlvm.store.IntLists;
import rlvm.store.LevelInfo;

/**
 * {@link LevelOps} and {@link IntLists} over one {@code rlvm.level.LevelStore}: the run's base store
 * (written by the loader thread until it is frozen, read-only afterwards) or one worker's overlay.
 * One object serves both interfaces because a level store and its list store are one id space: a
 * level-list id means the same thing to the term store, to the level algebra and to an engine.
 *
 * <p>It is also the store's {@link LevelInfo} (the structural view the term store needs to derive
 * {@code lpSet} and to instantiate universe parameters): {@link #lhs}, {@link #rhs} and
 * {@link #paramName} are total here - {@code lhs} of a successor is its operand, operands that do
 * not exist are 0, {@code paramName} of a non-parameter is -1 - which agrees with {@link LevelOps}
 * wherever that interface defines them.
 *
 * <p>Thread confinement is the level store's: a base binding is used by the loader thread until
 * {@code freeze()}, afterwards only its read-only methods may be called (from any thread); an overlay
 * binding belongs to one worker. A worker's overlay is a shared overlay: it reads the frozen base (or
 * the frozen snapshot of a pipelined load) in place and holds only what its session adds.
 *
 * <p>Errors: {@link #instantiate(int, int, int)} and {@link #instantiateList(int, int, int)} throw
 * {@link KernelException} ({@link Reason#LEVEL_ARITY_MISMATCH}) on lists of different length;
 * {@link #mkSubst} returns -1 there. {@code rlvm.level.LevelLimitException} (nesting depth above
 * 2^24 - 1, where the official kernel panics) and {@code IllegalStateException} (creating an object
 * in a frozen store) propagate unchanged: an engine turns any such exception into a decline.
 */
public final class LevelBinding implements LevelOps, IntLists, LevelInfo {

    /** Entries of the direct-mapped {@link #unionSorted} memo. */
    private static final int UNION_CACHE = 512;

    // Not final: a worker's binding is re-based on a newer snapshot during a pipelined load (rebase).
    private LevelStore store;
    private IntListStore lists;

    private int[] scratch = new int[32];
    private final long[] unionKey = new long[UNION_CACHE];
    private final int[] unionVal = new int[UNION_CACHE];
    private final int[] unionStamp = new int[UNION_CACHE];
    private int epoch = 1;

    public LevelBinding(LevelStore store) {
        this.store = store;
        this.lists = store.lists();
    }

    /**
     * The level store behind this binding (for code that needs more of its API, e.g. the machine).
     * Do not keep the result across {@code WorkerContext.reset()}: a pipelined load replaces it there
     * ({@link #rebase}).
     */
    public LevelStore store() {
        return store;
    }

    /**
     * Whether ids of this binding's base may be called shared ({@link #isSharedLevel},
     * {@link #isSharedList}). True for the frozen base of a batch run and its overlays. False for
     * the views of a pipelined load ({@code Bindings} says so): there the base of a worker is a
     * snapshot, and an id of a newer snapshot is unknown to a worker that still sits on an older one.
     */
    private boolean baseIsShared = true;

    /** For {@code Bindings}: see {@link #baseIsShared}. */
    void baseIsShared(boolean value) {
        this.baseIsShared = value;
    }

    /**
     * True if {@code level} is an id of the run's shared level store: it denotes the same level for
     * every thread and for the rest of the run ({@code rlvm.store.LevelInfo.isSharedLevel}, which
     * the store's generated region asks before it accepts a level). An overlay answers true exactly
     * for the ids of its frozen base; the base binding for every id it holds. In a pipelined run the
     * answer is always false (conservative: generated terms with levels are declined there).
     */
    @Override
    public boolean isSharedLevel(int level) {
        if (!baseIsShared || level <= 0) {
            return false;
        }
        return store.isOverlay() ? level < store.baseSize() : level < store.size();
    }

    /**
     * True if {@code list} is an id of the run's shared list store ({@code rlvm.store.IntLists
     * .isSharedList}); the empty list (0) always is. Same rule as {@link #isSharedLevel}.
     */
    @Override
    public boolean isSharedList(int list) {
        if (list == 0) {
            return true;
        }
        if (!baseIsShared || list < 0) {
            return false;
        }
        return store.isOverlay() ? list < store.baseListCount() : list < store.listCount();
    }

    /**
     * Pipelined loading: puts this worker's binding on {@code overlay}, a fresh overlay of a NEWER
     * frozen snapshot of the same level chain (every id of the old base means the same thing in the
     * new one; the new base has more). Everything created through the old overlay is forgotten,
     * exactly as by {@link #reset()}; holders of this object (the term overlay's list store and
     * level view, the worker context) see the new store without being told.
     *
     * @throws IllegalArgumentException if {@code overlay} is not a writable overlay, or its base is
     *     smaller than the current one (a snapshot never shrinks)
     */
    public void rebase(LevelStore overlay) {
        if (overlay == null || !overlay.isOverlay() || overlay.isFrozen()) {
            throw new IllegalArgumentException("rebase needs a writable overlay of a frozen snapshot");
        }
        if (store.isOverlay() && (overlay.baseSize() < store.baseSize() || overlay.baseListCount() < store.baseListCount())) {
            throw new IllegalArgumentException("rebase onto an older snapshot: base " + overlay.baseSize() + " levels / "
                    + overlay.baseListCount() + " lists, current " + store.baseSize() + " / " + store.baseListCount());
        }
        this.store = overlay;
        this.lists = overlay.lists();
        if (++epoch == Integer.MAX_VALUE) {
            java.util.Arrays.fill(unionStamp, 0);
            epoch = 1;
        }
    }

    /**
     * Pipelined loading, the way a worker's binding moves: puts this binding on {@code snapshot}, a
     * NEWER frozen snapshot of the same level chain, and forgets everything created through it, exactly
     * as {@link #rebase rebase(snapshot.newSharedOverlay())} would. If this binding's store is a shared
     * overlay ({@code LevelStore.newSharedOverlay()}) it stays the same object and is moved in place:
     * nothing of the snapshot is copied, the worker reads it where the loader published it.
     *
     * @throws IllegalArgumentException if {@code snapshot} is not a frozen store, or is smaller than the
     *     current base (a snapshot never shrinks)
     */
    public void rebaseOnto(LevelStore snapshot) {
        if (snapshot == null || !snapshot.isFrozen()) {
            throw new IllegalArgumentException("rebaseOnto needs a frozen snapshot");
        }
        if (!store.isSharedOverlay()) {
            rebase(snapshot.newSharedOverlay());
            return;
        }
        store.rebase(snapshot);
        if (++epoch == Integer.MAX_VALUE) {
            java.util.Arrays.fill(unionStamp, 0);
            epoch = 1;
        }
    }

    // ---- LevelOps: constructors ----------------------------------------------------------------------

    @Override
    public int zero() {
        return LevelStore.ZERO;
    }

    @Override
    public int succ(int level) {
        return store.succ(level);
    }

    @Override
    public int max(int a, int b) {
        return store.max(a, b);
    }

    @Override
    public int imax(int a, int b) {
        return store.imax(a, b);
    }

    @Override
    public int param(int nameId) {
        return store.param(nameId);
    }

    @Override
    public int mkMax(int a, int b) {
        return store.mkMax(a, b);
    }

    @Override
    public int mkIMax(int a, int b) {
        return store.mkIMax(a, b);
    }

    // ---- LevelOps: relations -------------------------------------------------------------------------

    @Override
    public boolean isEquiv(int a, int b) {
        return store.isEquiv(a, b);
    }

    @Override
    public boolean isGeq(int a, int b) {
        return store.isGeq(a, b);
    }

    @Override
    public boolean isEquivOfficial(int a, int b) {
        return store.isEquivOfficial(a, b);
    }

    @Override
    public boolean isGeqOfficial(int a, int b) {
        return store.isGeqOfficial(a, b);
    }

    @Override
    public boolean isAlwaysZero(int level) {
        return store.isAlwaysZero(level);
    }

    @Override
    public boolean isNeverZero(int level) {
        return store.isNeverZero(level);
    }

    @Override
    public boolean listsEquiv(int listA, int listB) {
        return store.listsEquiv(listA, listB);
    }

    // ---- LevelOps: instantiation ---------------------------------------------------------------------

    @Override
    public int mkSubst(int paramNames, int levels) {
        return store.mkSubst(paramNames, levels);
    }

    @Override
    public int substParams(int subst) {
        return store.substParams(subst);
    }

    @Override
    public int substLevels(int subst) {
        return store.substLevels(subst);
    }

    @Override
    public int instantiate(int level, int subst) {
        return store.instantiate(level, subst);
    }

    @Override
    public int instantiateList(int levelList, int subst) {
        return store.instantiateList(levelList, subst);
    }

    @Override
    public int instantiate(int level, int paramNames, int levels) {
        int subst = store.mkSubst(paramNames, levels);
        if (subst < 0) {
            throw new KernelException(Reason.LEVEL_ARITY_MISMATCH);
        }
        return store.instantiate(level, subst);
    }

    @Override
    public int instantiateList(int levelList, int paramNames, int levels) {
        int subst = store.mkSubst(paramNames, levels);
        if (subst < 0) {
            throw new KernelException(Reason.LEVEL_ARITY_MISMATCH);
        }
        return store.instantiateList(levelList, subst);
    }

    @Override
    public boolean paramsDeclared(int level, int paramNames) {
        return store.paramsDeclared(level, paramNames);
    }

    @Override
    public int undeclaredParam(int level, int paramNames) {
        return store.undeclaredParam(level, paramNames);
    }

    // ---- LevelOps: structure -------------------------------------------------------------------------

    @Override
    public int kind(int level) {
        return store.kind(level);
    }

    @Override
    public int succOf(int level) {
        return store.succOf(level);
    }

    /** Left operand of a max / imax, the operand of a successor, 0 otherwise (LevelInfo's total form). */
    @Override
    public int lhs(int level) {
        int k = store.kind(level);
        return k == LevelStore.KIND_MAX || k == LevelStore.KIND_IMAX ? store.lhs(level)
                : k == LevelStore.KIND_SUCC ? store.succOf(level) : 0;
    }

    /** Right operand of a max / imax, 0 otherwise. */
    @Override
    public int rhs(int level) {
        int k = store.kind(level);
        return k == LevelStore.KIND_MAX || k == LevelStore.KIND_IMAX ? store.rhs(level) : 0;
    }

    /** Name id of a parameter, -1 for any other level. */
    @Override
    public int paramName(int level) {
        return store.kind(level) == LevelStore.KIND_PARAM ? store.paramName(level) : -1;
    }

    @Override
    public boolean isZero(int level) {
        return level == LevelStore.ZERO;
    }

    @Override
    public boolean hasParam(int level) {
        return store.hasParam(level);
    }

    @Override
    public String toString(int level) {
        return level == 0 ? "<none>" : store.toString(level);
    }

    @Override
    public IntLists lists() {
        return this;
    }

    /**
     * Overlay: forgets every level, list and substitution this worker created. On a base store this
     * is an error of the caller ({@code IllegalStateException} from the level store).
     */
    @Override
    public void reset() {
        store.reset();
        if (++epoch == Integer.MAX_VALUE) {
            java.util.Arrays.fill(unionStamp, 0);
            epoch = 1;
        }
    }

    /** Estimate: 16 bytes per level and per list this overlay created (the level store reports no byte counts). */
    @Override
    public long footprintBytes() {
        long levels = (long) store.size() - store.baseSize();
        long ls = (long) store.listCount() - store.baseListCount();
        return store.isOverlay() ? 16L * (levels + ls) : 0L;
    }

    // ---- IntLists ------------------------------------------------------------------------------------

    @Override
    public int intern(int[] buf, int from, int len) {
        return lists.intern(buf, from, len);
    }

    @Override
    public int length(int list) {
        return lists.length(list);
    }

    @Override
    public int get(int list, int index) {
        return lists.get(list, index);
    }

    @Override
    public void copyTo(int list, int[] dst, int dstOff) {
        lists.copyTo(list, dst, dstOff);
    }

    /**
     * Union of two ascending duplicate-free lists (sets of name ids). Returns one of the arguments
     * when it already is the union, so no list is created for the common case "same set" and
     * "subset"; other results are interned and memoised on the id pair until the next
     * {@link #reset()}.
     */
    @Override
    public int unionSorted(int setA, int setB) {
        if (setA == setB || setB == 0) {
            return setA;
        }
        if (setA == 0) {
            return setB;
        }
        long key = ((long) setA << 32) | (setB & 0xFFFFFFFFL);
        int slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> 55) & (UNION_CACHE - 1);
        if (unionKey[slot] == key && unionStamp[slot] == epoch) {
            return unionVal[slot];
        }
        int na = lists.length(setA);
        int nb = lists.length(setB);
        if (scratch.length < na + nb) {
            scratch = new int[Math.max(na + nb, scratch.length * 2)];
        }
        int[] out = scratch;
        int[] poolA = lists.poolOf(setA);
        int[] poolB = lists.poolOf(setB);
        int i = lists.start(setA);
        int ia = i + na;
        int j = lists.start(setB);
        int jb = j + nb;
        int n = 0;
        while (i < ia && j < jb) {
            int x = poolA[i];
            int y = poolB[j];
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
            out[n++] = poolA[i++];
        }
        while (j < jb) {
            out[n++] = poolB[j++];
        }
        int result = n == na ? setA : n == nb ? setB : lists.intern(out, 0, n);
        unionKey[slot] = key;
        unionVal[slot] = result;
        unionStamp[slot] = epoch;
        return result;
    }

    /** True if every element of {@code sub} occurs in {@code list} (which may be in any order). */
    @Override
    public boolean subsetOf(int sub, int list) {
        if (sub == 0 || sub == list) {
            return true;
        }
        int ns = lists.length(sub);
        int nl = lists.length(list);
        if (nl == 0) {
            return false;
        }
        int[] poolS = lists.poolOf(sub);
        int[] poolL = lists.poolOf(list);
        int s = lists.start(sub);
        int l = lists.start(list);
        outer:
        for (int i = 0; i < ns; i++) {
            int x = poolS[s + i];
            for (int j = 0; j < nl; j++) {
                if (poolL[l + j] == x) {
                    continue outer;
                }
            }
            return false;
        }
        return true;
    }

    /** True if some value occurs twice in the list (duplicate universe parameter names). */
    public boolean hasDuplicates(int list) {
        return store.listHasDuplicates(list);
    }
}
