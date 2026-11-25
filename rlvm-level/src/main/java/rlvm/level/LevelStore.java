package rlvm.level;

import java.util.Arrays;

/**
 * Universe levels of the Lean 4 kernel as hash-consed {@code int} ids in flat arrays, with the
 * official level algebra ({@code refs/lean4/src/kernel/level.cpp}) and a complete comparison.
 *
 * <h2>Ids</h2>
 * Id 0 is "none". {@link #ZERO} (1) and {@link #ONE} (2) exist in every store. Two levels are
 * structurally equal (the official {@code ==}) iff their ids are equal. Level lists, parameter name
 * lists and substitutions are ids into one {@link IntListStore} (0 = empty list = identity
 * substitution).
 *
 * <h2>Two families of operations</h2>
 * <ul>
 *   <li><b>Official</b>, bit-for-bit ports: {@link #mkMax}, {@link #mkIMax}, {@link #normalize},
 *       {@link #isEquivOfficial}, {@link #isGeqOfficial}, {@link #isNeverZero}, {@link #isAlwaysZero},
 *       {@link #instantiate}. The official normal form is not canonical under {@code imax}, so
 *       {@code isEquivOfficial} is sound but incomplete.
 *   <li><b>Complete</b>: {@link #canonId} is a canonical form of the denotation (Géran's sublevel
 *       form): equal ids iff the levels are equal under every assignment. {@link #isEquiv} and
 *       {@link #isGeq} decide the semantic relations and therefore accept a superset of what the
 *       official functions accept. If a level is too big for the canonical form (see
 *       {@link #CANON_UNKNOWN}) they fall back to the official procedure for that question.
 * </ul>
 *
 * <h2>Frozen base and per-worker overlays</h2>
 * A store is built by one thread. {@link #freeze()} computes the official normal form and the
 * canonical form of every level and makes the store immutable; a frozen store may be read by any number of threads through the
 * accessors and the memoised fast paths ({@link #kind}, {@link #lhs}, lists, {@link #isEquiv} ...);
 * operations that would have to create something throw {@link IllegalStateException}.
 * An overlay is a writable store on top of a frozen one whose ids below {@link #baseSize()} are the
 * base ids and whose new ids start there; {@link #reset()} forgets everything it created, in time
 * proportional to what it created. An overlay is confined to one thread. There are two kinds, with the
 * same ids and the same answers operation by operation:
 * <ul>
 *   <li>{@link #newSharedOverlay()}, the overlay of a worker: it reads the frozen base in place - the
 *       node arrays, the lists, the memoised forms and the two hash tables - and holds only what its
 *       session adds, in arrays of its own. Nothing is copied when it is made, and {@link #rebase}
 *       moves it to a later, larger snapshot for the price of a {@code reset()}. A lookup that the base
 *       does not answer probes a second, small table. Any number of overlays share one base; none of
 *       them writes it (the publication rule: a store is immutable once {@code freeze()} has returned,
 *       and {@link #isFrozen()}, a volatile read, is the first thing an overlay asks of its base).
 *   <li>{@link #newOverlay()}, a private flat copy of the whole base (a few MB for Mathlib): one array
 *       per field and no base. It is the only kind that can be frozen in turn, so the snapshots of a
 *       pipelined load are such copies, made by the loader, once per publication.
 * </ul>
 * A flat store is a shared overlay whose base is empty: the code is the same, the bound {@code split}
 * below which an id is read from the base is 0.
 *
 * <p>Operations recurse on the {@code max}/{@code imax} nesting depth (never on {@code succ} chains).
 * A hostile input nested hundreds of thousands deep ends in a {@link StackOverflowError}: the
 * caller must treat any {@link Throwable} from this class as "reject".
 */
public final class LevelStore {

    public static final int NONE = 0;
    /** The level {@code zero}. */
    public static final int ZERO = 1;
    /** The level {@code succ zero}. */
    public static final int ONE = 2;

    /** Kinds, numbered as the official {@code level_kind} (the order matters for the normal form). */
    public static final int KIND_ZERO = 0;
    public static final int KIND_SUCC = 1;
    public static final int KIND_MAX = 2;
    public static final int KIND_IMAX = 3;
    public static final int KIND_PARAM = 4;

    /** Largest depth the official kernel can represent; deeper levels throw {@link LevelLimitException}. */
    public static final int MAX_DEPTH = 16777215;

    /**
     * {@link #canonId} of a level whose canonical form was not computed because it exceeded a size
     * budget (more than 64 distinct parameters, or more than {@value #CANON_MAX_ENTRIES} sublevels
     * or {@value #CANON_MAX_STEPS} visited nodes).
     */
    public static final int CANON_UNKNOWN = -1;

    static final int CANON_MAX_ENTRIES = 256;
    static final int CANON_MAX_STEPS = 4096;

    private static final int KIND_MASK = 7;
    private static final int F_HAS_PARAM = 8;
    private static final int F_NEVER_ZERO = 16;
    private static final int F_ALWAYS_ZERO = 32;
    private static final int F_EXPLICIT = 64;
    private static final int F_HAS_IMAX = 128;
    private static final int DEPTH_SHIFT = 8;

    private static final int CACHE_BITS = 12;

    private final NameOracle names;

    // ---- level nodes: own arrays; level id l >= split has the own index l - split ----
    private int[] info;     // kind | flags | depth << 8
    private int[] a;        // succ: predecessor; max/imax: lhs; param: name id
    private int[] b;        // max/imax: rhs
    private int[] offBase;  // l = succ^offK[l](offBase[l]), offBase[l] not a succ
    private int[] offK;
    private int[] norm;     // official normal form, 0 = not computed
    private int[] canon;    // canonical form id, 0 = not computed, -1 = unknown
    private int[] chk;      // last parameter list this level was checked against, -1 = none
    private int top;
    private int[] table;    // own ids only

    // ---- the shared base of a shared overlay: level ids < split (null / 0 in a flat store) ----
    // The arrays of the frozen store this overlay sits on. They are only read here, by any number of
    // overlays at once; the three "own" flags say that an array was replaced by a private copy because
    // this overlay had to write a memo into it (never on a store that freeze() completed).
    private int[] bInfo;
    private int[] bA;
    private int[] bB;
    private int[] bOffBase;
    private int[] bOffK;
    private int[] bNorm;
    private int[] bCanon;
    private int[] bCanonRep;
    private int[] bTable;
    private boolean bNormOwn;
    private boolean bCanonOwn;
    private boolean bCanonRepOwn;
    /** Private to this overlay: base level -> 1 + the parameter list it was last checked against (0 = none). */
    private int[] bChk = NO_INTS;
    /** First own level id: 0 in a flat store (a root store or a private copy), {@link #baseTop} in a shared overlay. */
    private int split;
    /** First own canonical form id, as {@link #split}. */
    private int canonSplit;

    // ---- lists ----
    private final IntListStore lists;
    private final IntListStore canons;
    private int[] canonRep; // canonical form id -> a level with that form (own index: id - canonSplit)

    // ---- overlay bookkeeping (not final: a shared overlay moves to a larger base, see rebase) ----
    private int baseTop;      // 0 for a root store
    private int baseListTop;
    private int baseCanonTop;
    private volatile boolean frozen;

    // ---- instantiation caches (direct mapped). An entry is valid if its stamp is the current epoch (it was
    // made in this session) or the current "forever" stamp (it is made of base ids only, BY CONTENT: see
    // substIsBase and isBaseLevelList; it survives reset()). epoch is positive, forever negative, 0 marks an
    // empty slot. ----
    private final long[] icKey = new long[1 << CACHE_BITS];
    private final int[] icVal = new int[1 << CACHE_BITS];
    private final int[] icStamp = new int[1 << CACHE_BITS];
    private final long[] lcKey = new long[1 << CACHE_BITS];
    private final int[] lcVal = new int[1 << CACHE_BITS];
    private final int[] lcStamp = new int[1 << CACHE_BITS];
    private int epoch = 1;
    private int forever = -1;

    private static final int[] NO_INTS = new int[0];

    /** Initial own capacity (levels) of a shared overlay; its arrays grow and are kept across resets. */
    private static final int OVERLAY_LEVELS = 1024;

    // ---- scratch ----
    private int[] scr = new int[64];
    private int sp;
    private final int[] pair = new int[2];
    private long[] cMask = new long[32];
    private int[] cVar = new int[32];
    private int[] cK = new int[32];
    private int cSp;
    private long[] gMask = new long[16];
    private int gSp;
    private final int[] cNames = new int[64];
    private final int[] cRank = new int[64];
    private final int[] cSorted = new int[64];
    private int cNumNames;
    private int cSteps;
    private boolean cFail;

    // =====================================================================================
    // Construction, freezing, overlays
    // =====================================================================================

    /** A root store whose parameter names are ordered by id (see {@link NameOracle#BY_ID}). */
    public LevelStore() {
        this(NameOracle.BY_ID, 1024);
    }

    public LevelStore(NameOracle names) {
        this(names, 1024);
    }

    public LevelStore(NameOracle names, int expectedLevels) {
        if (names == null) {
            throw new NullPointerException("names");
        }
        this.names = names;
        int cap = Math.max(16, expectedLevels + 3);
        info = new int[cap];
        a = new int[cap];
        b = new int[cap];
        offBase = new int[cap];
        offK = new int[cap];
        norm = new int[cap];
        canon = new int[cap];
        chk = new int[cap];
        int ts = 16;
        while (ts < 2 * cap) {
            ts <<= 1;
        }
        table = new int[ts];
        top = 1;
        lists = new IntListStore(cap, 4 * cap);
        canons = new IntListStore(cap, 6 * cap);
        canonRep = new int[cap];
        baseTop = 0;
        baseListTop = 0;
        baseCanonTop = 0;
        int z = intern(KIND_ZERO, 0, 0);
        int o = intern(KIND_SUCC, z, 0);
        if (z != ZERO || o != ONE) {
            throw new IllegalStateException();
        }
    }

    /** The private flat copy of a frozen flat store ({@link #newOverlay()}). */
    private LevelStore(LevelStore base) {
        names = base.names;
        int n = base.top;
        int cap = n + Math.max(1024, n >> 2);
        info = Arrays.copyOf(base.info, cap);
        a = Arrays.copyOf(base.a, cap);
        b = Arrays.copyOf(base.b, cap);
        offBase = Arrays.copyOf(base.offBase, cap);
        offK = Arrays.copyOf(base.offK, cap);
        norm = Arrays.copyOf(base.norm, cap);
        canon = Arrays.copyOf(base.canon, cap);
        chk = Arrays.copyOf(base.chk, cap);
        table = base.table.clone();
        top = n;
        lists = new IntListStore(base.lists);
        canons = new IntListStore(base.canons);
        canonRep = Arrays.copyOf(base.canonRep, Math.max(base.canonRep.length, canons.size() + 64));
        baseTop = n;
        baseListTop = lists.size();
        baseCanonTop = canons.size();
    }

    /** The shared overlay of a frozen flat store ({@link #newSharedOverlay()}): nothing of the base is copied. */
    private LevelStore(LevelStore base, boolean shared) {
        names = base.names;
        int cap = OVERLAY_LEVELS;
        info = new int[cap];
        a = new int[cap];
        b = new int[cap];
        offBase = new int[cap];
        offK = new int[cap];
        norm = new int[cap];
        canon = new int[cap];
        chk = new int[cap];
        table = new int[2 * cap];
        lists = IntListStore.sharedOverlayOf(base.lists);
        canons = IntListStore.sharedOverlayOf(base.canons);
        canonRep = new int[64];
        adopt(base);
    }

    /** Points the base part of this shared overlay (which holds nothing of its own) at the frozen flat store {@code base}. */
    private void adopt(LevelStore base) {
        bInfo = base.info;
        bA = base.a;
        bB = base.b;
        bOffBase = base.offBase;
        bOffK = base.offK;
        bNorm = base.norm;
        bCanon = base.canon;
        bCanonRep = base.canonRep;
        bTable = base.table;
        bNormOwn = false;
        bCanonOwn = false;
        bCanonRepOwn = false;
        int n = base.top;
        split = n;
        top = n;
        baseTop = n;
        baseListTop = lists.size();
        baseCanonTop = canons.size();
        canonSplit = baseCanonTop;
    }

    /**
     * Computes the official normal form and the canonical form of every level and makes this store
     * immutable. Idempotent. After this call the store may be shared between threads (accessors and
     * memoised answers only) and {@link #newOverlay()} / {@link #newSharedOverlay()} may be called from
     * any thread: nothing in the store is written after this method has returned, and its last action
     * is the volatile write that every reader of the store sees first ({@link #isFrozen()}).
     *
     * <p>The normal forms are added to the store (and normalised in turn), so that in an overlay
     * {@link #normalize} of a base level is always a memo hit; {@link #size()} can therefore grow
     * here. The closure is bounded (four times the size plus 1024 levels): beyond that, which no
     * real export comes near, an overlay recomputes the normal form of the remaining base levels on
     * every call. Every level gets its canonical form in any case.
     *
     * @throws IllegalStateException on a shared overlay: only a flat store can become a base
     */
    public void freeze() {
        if (frozen) {
            return;
        }
        if (split != 0) {
            throw new IllegalStateException("a shared overlay cannot be frozen: freeze a root store or a newOverlay() copy");
        }
        int cap = 4 * top + 1024;
        for (int l = 1; l < top && top <= cap; l++) {
            if (norm[l] == 0) {
                normalizeSlow(l);
            }
        }
        for (int l = 1; l < top; l++) {
            if (canon[l] == 0) {
                canonSlow(l);
            }
        }
        lists.setReadOnly();
        canons.setReadOnly();
        frozen = true;
    }

    public boolean isFrozen() {
        return frozen;
    }

    /**
     * A private, writable flat store on top of this frozen store: a copy of all of it, which can be
     * frozen in turn (the snapshots of a pipelined load are such copies). For a worker's session use
     * {@link #newSharedOverlay()}, which copies nothing.
     */
    public LevelStore newOverlay() {
        if (!frozen) {
            throw new IllegalStateException("freeze() the store before creating overlays");
        }
        return new LevelStore(this);
    }

    /**
     * A private, writable store on top of this frozen store that <em>shares</em> it: the overlay reads
     * the levels, lists, memoised forms and hash tables of this store in place and holds only what it
     * creates itself. Same ids and same answers as {@link #newOverlay()}, operation by operation. Make
     * one per worker thread, {@link #reset()} it between sessions and {@link #rebase} it when a larger
     * snapshot is published. The overlay keeps this store's arrays alive while it sits on it; it never
     * writes them. It cannot be frozen.
     */
    public LevelStore newSharedOverlay() {
        if (!frozen) {
            throw new IllegalStateException("freeze() the store before creating overlays");
        }
        return new LevelStore(this, true);
    }

    /** True for a store made by {@link #newOverlay()} or {@link #newSharedOverlay()}. */
    public boolean isOverlay() {
        return baseTop != 0;
    }

    /** True for a store made by {@link #newSharedOverlay()}. */
    public boolean isSharedOverlay() {
        return split != 0;
    }

    /**
     * Overlay only: forgets every level, list, substitution and cache entry created through this
     * overlay. Ids below {@link #baseSize()} / {@link #baseListCount()} stay valid; all other ids
     * obtained earlier must not be used again (they will be reissued for other objects).
     */
    public void reset() {
        if (baseTop == 0) {
            throw new IllegalStateException("reset() is only available on overlays");
        }
        if (frozen) {
            throw frozenError();
        }
        int[] t = table;
        int mask = t.length - 1;
        int sp0 = split;
        for (int id = top - 1; id >= baseTop; id--) {
            int j = id - sp0;
            int s = hashNode(info[j] & KIND_MASK, a[j], b[j]) & mask;
            int guard = t.length;
            while (t[s] != id) {
                s = (s + 1) & mask;
                if (--guard < 0) {
                    throw new IllegalStateException("level " + id + " missing from its hash table");
                }
            }
            t[s] = 0;
        }
        top = baseTop;
        lists.truncate(baseListTop);
        Arrays.fill(canonRep, baseCanonTop - canonSplit, canons.size() - canonSplit, 0);
        canons.truncate(baseCanonTop);
        sp = 0;
        if (++epoch == Integer.MAX_VALUE) {
            clearCaches();
        }
    }

    private void clearCaches() {
        Arrays.fill(icKey, 0L);
        Arrays.fill(lcKey, 0L);
        Arrays.fill(icStamp, 0);
        Arrays.fill(lcStamp, 0);
        epoch = 1;
        forever = -1;
    }

    /**
     * Shared overlay only: {@link #reset()}, and then moves this overlay onto {@code newBase}, a frozen
     * flat store that extends the current base with the same ids (a later snapshot of the same chain of
     * {@link #newOverlay()} copies; the caller guarantees that). Afterwards this store answers exactly
     * like {@code newBase.newSharedOverlay()}, id by id: no answer that was remembered on the old base
     * is given again, not even the cache entries that survive a {@code reset()} (a cached instantiation
     * skips the levels its computation would create, so keeping one would shift the ids that follow).
     * Costs what {@code reset()} costs; nothing of the base is copied.
     *
     * @throws IllegalArgumentException if {@code newBase} is not a frozen flat store over the same names, or
     *     is smaller than the current base (a snapshot never shrinks)
     */
    public void rebase(LevelStore newBase) {
        if (split == 0) {
            throw new IllegalStateException("rebase() is only available on shared overlays");
        }
        if (newBase == null || !newBase.frozen || newBase.split != 0) {
            throw new IllegalArgumentException("rebase needs a frozen flat store");
        }
        if (newBase.names != names) {
            throw new IllegalArgumentException("rebase onto a store with another name order");
        }
        if (newBase.top < baseTop || newBase.lists.size() < baseListTop || newBase.canons.size() < baseCanonTop) {
            throw new IllegalArgumentException("rebase onto an older snapshot: base " + newBase.top + " levels / "
                    + newBase.lists.size() + " lists, current " + baseTop + " / " + baseListTop);
        }
        reset();
        lists.rebase(newBase.lists);
        canons.rebase(newBase.canons);
        adopt(newBase);
        // A fresh overlay has empty caches: the entries that survive a reset end here.
        if (--forever == Integer.MIN_VALUE) {
            clearCaches();
        }
    }

    /** Number of level ids in use including id 0: valid ids are {@code 1 .. size()-1}. */
    public int size() {
        return top;
    }

    /** Ids below this bound belong to the frozen base (0 for a root store). */
    public int baseSize() {
        return baseTop;
    }

    public int listCount() {
        return lists.size();
    }

    /** List ids below this bound belong to the frozen base (0 for a root store). */
    public int baseListCount() {
        return baseListTop;
    }

    public NameOracle names() {
        return names;
    }

    private static IllegalStateException frozenError() {
        return new IllegalStateException("LevelStore is frozen; use an overlay (newOverlay()) for this operation");
    }

    // =====================================================================================
    // The node arrays: a level below split is read from the shared base, any other from the own arrays
    // =====================================================================================

    private int info(int l) {
        int j = l - split;
        return j >= 0 ? info[j] : bInfo[l];
    }

    private int a(int l) {
        int j = l - split;
        return j >= 0 ? a[j] : bA[l];
    }

    private int b(int l) {
        int j = l - split;
        return j >= 0 ? b[j] : bB[l];
    }

    private int offBase(int l) {
        int j = l - split;
        return j >= 0 ? offBase[j] : bOffBase[l];
    }

    private int offK(int l) {
        int j = l - split;
        return j >= 0 ? offK[j] : bOffK[l];
    }

    private int norm(int l) {
        int j = l - split;
        return j >= 0 ? norm[j] : bNorm[l];
    }

    private int canon(int l) {
        int j = l - split;
        return j >= 0 ? canon[j] : bCanon[l];
    }

    private int repOf(int c) {
        int j = c - canonSplit;
        return j >= 0 ? canonRep[j] : bCanonRep[c];
    }

    // Memo writes. A write below split goes to a private copy of the base array, made at the first such
    // write: the base itself is never written. (It does not happen on a store that freeze() completed,
    // whose levels all have their forms; the copy keeps the answers exact where the closure of freeze()
    // was cut.)

    private void setNorm(int l, int r) {
        int j = l - split;
        if (j >= 0) {
            norm[j] = r;
        } else {
            if (!bNormOwn) {
                bNorm = bNorm.clone();
                bNormOwn = true;
            }
            bNorm[l] = r;
        }
    }

    private void setCanon(int l, int c) {
        int j = l - split;
        if (j >= 0) {
            canon[j] = c;
        } else {
            if (!bCanonOwn) {
                bCanon = bCanon.clone();
                bCanonOwn = true;
            }
            bCanon[l] = c;
        }
    }

    private void setCanonRep(int c, int l) {
        int j = c - canonSplit;
        if (j >= 0) {
            canonRep[j] = l;
        } else {
            if (!bCanonRepOwn) {
                bCanonRep = bCanonRep.clone();
                bCanonRepOwn = true;
            }
            bCanonRep[c] = l;
        }
    }

    // =====================================================================================
    // Hash-consing
    // =====================================================================================

    private static int hashNode(int kind, int x, int y) {
        int h = x * 0x9E3779B1 + y * 0x85EBCA6B + kind * 0xC2B2AE35;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 13;
        return h;
    }

    /** The node (kind, x, y): the base's if it has one (nine in ten lookups of a worker end there), else this store's, else a new one. */
    private int intern(int kind, int x, int y) {
        int h = hashNode(kind, x, y);
        int sp0 = split;
        if (sp0 != 0) {
            int[] t = bTable;
            int[] ba = bA;
            int[] bb = bB;
            int mask = t.length - 1;
            int s = h & mask;
            for (;;) {
                int id = t[s];
                if (id == 0) {
                    break;
                }
                if (ba[id] == x && bb[id] == y && (bInfo[id] & KIND_MASK) == kind) {
                    return id;
                }
                s = (s + 1) & mask;
            }
        }
        int[] t = table;
        int mask = t.length - 1;
        int s = h & mask;
        for (;;) {
            int id = t[s];
            if (id == 0) {
                break;
            }
            int j = id - sp0;
            if (a[j] == x && b[j] == y && (info[j] & KIND_MASK) == kind) {
                return id;
            }
            s = (s + 1) & mask;
        }
        return newNode(kind, x, y, s);
    }

    private int newNode(int kind, int x, int y, int slot) {
        if (frozen) {
            throw frozenError();
        }
        int id = top;
        int j = id - split;
        if (j == info.length) {
            growNodes();
        }
        int inf;
        int ob = id;
        int ok = 0;
        switch (kind) {
            case KIND_ZERO:
                inf = KIND_ZERO | F_ALWAYS_ZERO | F_EXPLICIT;
                break;
            case KIND_SUCC: {
                int ia = info(x);
                int d = (ia >>> DEPTH_SHIFT) + 1;
                if (d > MAX_DEPTH) {
                    throw new LevelLimitException("universe level depth is too big");
                }
                inf = KIND_SUCC | (ia & (F_HAS_PARAM | F_EXPLICIT | F_HAS_IMAX)) | F_NEVER_ZERO | (d << DEPTH_SHIFT);
                ob = offBase(x);
                ok = offK(x) + 1;
                break;
            }
            case KIND_MAX: {
                int ia = info(x);
                int ib = info(y);
                int d = Math.max(ia >>> DEPTH_SHIFT, ib >>> DEPTH_SHIFT) + 1;
                if (d > MAX_DEPTH) {
                    throw new LevelLimitException("universe level depth is too big");
                }
                inf = KIND_MAX | ((ia | ib) & (F_HAS_PARAM | F_NEVER_ZERO | F_HAS_IMAX)) | (ia & ib & F_ALWAYS_ZERO)
                        | (d << DEPTH_SHIFT);
                break;
            }
            case KIND_IMAX: {
                int ia = info(x);
                int ib = info(y);
                int d = Math.max(ia >>> DEPTH_SHIFT, ib >>> DEPTH_SHIFT) + 1;
                if (d > MAX_DEPTH) {
                    throw new LevelLimitException("universe level depth is too big");
                }
                inf = KIND_IMAX | ((ia | ib) & F_HAS_PARAM) | (ib & (F_NEVER_ZERO | F_ALWAYS_ZERO)) | F_HAS_IMAX
                        | (d << DEPTH_SHIFT);
                break;
            }
            default:
                inf = KIND_PARAM | F_HAS_PARAM;
                break;
        }
        info[j] = inf;
        a[j] = x;
        b[j] = y;
        offBase[j] = ob;
        offK[j] = ok;
        norm[j] = 0;
        canon[j] = 0;
        chk[j] = -1;
        top = id + 1;
        table[slot] = id;
        if (2 * (j + 1) > table.length) {
            rehash(table.length << 1);
        }
        return id;
    }

    private void growNodes() {
        int ncap = info.length + (info.length >> 1) + 16;
        info = Arrays.copyOf(info, ncap);
        a = Arrays.copyOf(a, ncap);
        b = Arrays.copyOf(b, ncap);
        offBase = Arrays.copyOf(offBase, ncap);
        offK = Arrays.copyOf(offK, ncap);
        norm = Arrays.copyOf(norm, ncap);
        canon = Arrays.copyOf(canon, ncap);
        chk = Arrays.copyOf(chk, ncap);
    }

    private void rehash(int newSize) {
        int[] t = new int[newSize];
        int mask = newSize - 1;
        int sp0 = split;
        for (int id = Math.max(1, sp0); id < top; id++) {
            int j = id - sp0;
            int s = hashNode(info[j] & KIND_MASK, a[j], b[j]) & mask;
            while (t[s] != 0) {
                s = (s + 1) & mask;
            }
            t[s] = id;
        }
        table = t;
    }

    private void checkId(int l) {
        if (l <= 0 || l >= top) {
            throw new IllegalArgumentException("not a level id: " + l);
        }
    }

    // =====================================================================================
    // Raw constructors: exactly the given structure, no simplification (what the export contains)
    // =====================================================================================

    public int zero() {
        return ZERO;
    }

    public int succ(int l) {
        checkId(l);
        return intern(KIND_SUCC, l, 0);
    }

    public int max(int l1, int l2) {
        checkId(l1);
        checkId(l2);
        return intern(KIND_MAX, l1, l2);
    }

    public int imax(int l1, int l2) {
        checkId(l1);
        checkId(l2);
        return intern(KIND_IMAX, l1, l2);
    }

    /** {@code nameId} is opaque here; see {@link NameOracle} for what the caller must guarantee. */
    public int param(int nameId) {
        return intern(KIND_PARAM, nameId, 0);
    }

    /** {@code succ^k(l)} (official {@code mk_succ(l, k)}). */
    public int succN(int l, int k) {
        checkId(l);
        for (int i = 0; i < k; i++) {
            l = intern(KIND_SUCC, l, 0);
        }
        return l;
    }

    /** The explicit level {@code n}. */
    public int ofNat(int n) {
        return succN(ZERO, n);
    }

    // =====================================================================================
    // Accessors (no id validation: hot paths)
    // =====================================================================================

    public int kind(int l) {
        return info(l) & KIND_MASK;
    }

    /** Left operand of a max/imax. */
    public int lhs(int l) {
        return a(l);
    }

    /** Right operand of a max/imax. */
    public int rhs(int l) {
        return b(l);
    }

    /** Predecessor of a succ. */
    public int succOf(int l) {
        return a(l);
    }

    /** Name id of a param. */
    public int paramName(int l) {
        return a(l);
    }

    public boolean hasParam(int l) {
        return (info(l) & F_HAS_PARAM) != 0;
    }

    public boolean hasIMax(int l) {
        return (info(l) & F_HAS_IMAX) != 0;
    }

    /** Official depth: zero/param 0, succ d+1, max/imax max(d1,d2)+1. */
    public int depth(int l) {
        return info(l) >>> DEPTH_SHIFT;
    }

    /** Official {@code is_explicit}: {@code l = succ^k zero}; then {@link #offset(int)} is its value. */
    public boolean isExplicit(int l) {
        return (info(l) & F_EXPLICIT) != 0;
    }

    /** Official {@code to_offset(l).first}: {@code l} without its leading succs. */
    public int offsetBase(int l) {
        return offBase(l);
    }

    /** Official {@code to_offset(l).second}: the number of leading succs. */
    public int offset(int l) {
        return offK(l);
    }

    /** Official {@code is_zero}: syntactically zero. */
    public boolean isZero(int l) {
        return l == ZERO;
    }

    /**
     * Official {@code is_not_zero}: the level is nonzero under every assignment. This syntactic test is
     * exact (levels are monotone, so "never zero" means "nonzero when all parameters are zero").
     * False for a parameter.
     */
    public boolean isNeverZero(int l) {
        return (info(l) & F_NEVER_ZERO) != 0;
    }

    /**
     * Official {@code normalizes_to_zero}: the level is zero under every assignment (also exact).
     * False for a parameter. This is the kernel's "is a Prop" test for {@code Sort l}.
     */
    public boolean isAlwaysZero(int l) {
        return (info(l) & F_ALWAYS_ZERO) != 0;
    }

    // =====================================================================================
    // Official smart constructors (level.cpp:74-116)
    // =====================================================================================

    /** Official {@code mk_max}. */
    public int mkMax(int l1, int l2) {
        int i1 = info(l1);
        int i2 = info(l2);
        if ((i1 & i2 & F_EXPLICIT) != 0) {
            return (i1 >>> DEPTH_SHIFT) >= (i2 >>> DEPTH_SHIFT) ? l1 : l2;
        }
        if (l1 == l2) {
            return l1;
        }
        if (l1 == ZERO) {
            return l2;
        }
        if (l2 == ZERO) {
            return l1;
        }
        if ((i2 & KIND_MASK) == KIND_MAX && (a(l2) == l1 || b(l2) == l1)) {
            return l2;
        }
        if ((i1 & KIND_MASK) == KIND_MAX && (a(l1) == l2 || b(l1) == l2)) {
            return l1;
        }
        if (offBase(l1) == offBase(l2)) {
            return offK(l1) > offK(l2) ? l1 : l2;
        }
        return intern(KIND_MAX, l1, l2);
    }

    /** Official {@code mk_imax}. */
    public int mkIMax(int l1, int l2) {
        if ((info(l2) & F_NEVER_ZERO) != 0) {
            return mkMax(l1, l2);
        }
        if (l2 == ZERO) {
            return l2;
        }
        if (l1 == ZERO || l1 == ONE) {
            return l2;
        }
        if (l1 == l2) {
            return l1;
        }
        return intern(KIND_IMAX, l1, l2);
    }

    // =====================================================================================
    // Official normalize / is_equivalent / is_geq (level.cpp:355-504)
    // =====================================================================================

    /** Official {@code normalize}; memoised per level. */
    public int normalize(int l) {
        int r = norm(l);
        return r != 0 ? r : normalizeSlow(l);
    }

    private int normalizeSlow(int l) {
        if (frozen) {
            throw frozenError();
        }
        int base = offBase(l);
        int k = offK(l);
        int kind = info(base) & KIND_MASK;
        int r;
        if (kind == KIND_ZERO || kind == KIND_PARAM) {
            r = l;
        } else if (kind == KIND_IMAX) {
            int l1 = normalize(a(base));
            int l2 = normalize(b(base));
            r = succN(mkIMax(l1, l2), k);
        } else {
            r = normalizeMax(base, k);
        }
        // A base level of an overlay may only remember results that survive reset().
        if (l >= baseTop || r < baseTop) {
            setNorm(l, r);
        }
        return r;
    }

    private void push(int v) {
        if (sp == scr.length) {
            scr = Arrays.copyOf(scr, sp << 1);
        }
        scr[sp++] = v;
    }

    /** Official {@code push_max_args}: the leaves of nested max nodes, left to right. */
    private void pushMaxArgs(int l) {
        while ((info(l) & KIND_MASK) == KIND_MAX) {
            pushMaxArgs(a(l));
            l = b(l);
        }
        push(l);
    }

    private int normalizeMax(int base, int k) {
        int sp0 = sp;
        pushMaxArgs(base);
        int t1 = sp;
        for (int i = sp0; i < t1; i++) {
            int n = normalize(scr[i]);
            pushMaxArgs(n);
        }
        int n1 = sp;
        sortNormLt(t1, n1);
        int[] s = scr;
        int i = t1;
        if ((info(s[i]) & F_EXPLICIT) != 0) {
            while (i + 1 < n1 && (info(s[i + 1]) & F_EXPLICIT) != 0) {
                i++;
            }
            int kk = offK(s[i]);
            int j = i + 1;
            for (; j < n1; j++) {
                if (offK(s[j]) >= kk) {
                    break;
                }
            }
            if (j < n1) {
                i++;
            }
        }
        int w = t1;
        int first = s[i];
        int prevBase = offBase(first);
        int prevK = offK(first);
        s[w++] = first;
        i++;
        for (; i < n1; i++) {
            int cur = s[i];
            int cb = offBase(cur);
            int ck = offK(cur);
            if (cb == prevBase) {
                if (prevK < ck) {
                    prevK = ck;
                    s[w - 1] = cur;
                }
            } else {
                prevBase = cb;
                prevK = ck;
                s[w++] = cur;
            }
        }
        if (k > 0) {
            for (int j = t1; j < w; j++) {
                s[j] = succN(s[j], k);
            }
        }
        int r;
        if (w - t1 == 1) {
            r = s[t1];
        } else {
            r = mkMax(s[w - 2], s[w - 1]);
            for (int j = w - 3; j >= t1; j--) {
                r = mkMax(s[j], r);
            }
        }
        sp = sp0;
        return r;
    }

    /** Sorts {@code scr[from .. to)} by the official {@code is_norm_lt} (binary insertion sort). */
    private void sortNormLt(int from, int to) {
        int[] s = scr;
        for (int i = from + 1; i < to; i++) {
            int x = s[i];
            int lo = from;
            int hi = i;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (isNormLt(x, s[mid])) {
                    hi = mid;
                } else {
                    lo = mid + 1;
                }
            }
            if (lo < i) {
                System.arraycopy(s, lo, s, lo + 1, i - lo);
                s[lo] = x;
            }
        }
    }

    /** Official {@code is_norm_lt}: a strict total order on (structurally distinct) levels. */
    private boolean isNormLt(int x, int y) {
        for (;;) {
            if (x == y) {
                return false;
            }
            int l1 = offBase(x);
            int l2 = offBase(y);
            if (l1 == l2) {
                return offK(x) < offK(y);
            }
            int k1 = info(l1) & KIND_MASK;
            int k2 = info(l2) & KIND_MASK;
            if (k1 != k2) {
                return k1 < k2;
            }
            if (k1 == KIND_PARAM) {
                return names.compareNames(a(l1), a(l2)) < 0;
            }
            // Both max or both imax (bases are never succ; two zeros are the same id).
            if (a(l1) != a(l2)) {
                x = a(l1);
                y = a(l2);
            } else {
                x = b(l1);
                y = b(l2);
            }
        }
    }

    /** Official {@code is_equivalent}: {@code l1 == l2 || normalize(l1) == normalize(l2)}. Sound, incomplete. */
    public boolean isEquivOfficial(int l1, int l2) {
        return l1 == l2 || normalize(l1) == normalize(l2);
    }

    /** Official {@code is_geq}: {@code l1 >= l2} under every assignment. Sound, incomplete. */
    public boolean isGeqOfficial(int l1, int l2) {
        return geqCore(normalize(l1), normalize(l2));
    }

    private boolean geqCore(int l1, int l2) {
        if (l1 == l2 || l2 == ZERO) {
            return true;
        }
        int k2 = info(l2) & KIND_MASK;
        if (k2 == KIND_MAX) {
            return isGeqOfficial(l1, a(l2)) && isGeqOfficial(l1, b(l2));
        }
        int k1 = info(l1) & KIND_MASK;
        if (k1 == KIND_MAX && (isGeqOfficial(a(l1), l2) || isGeqOfficial(b(l1), l2))) {
            return true;
        }
        if (k2 == KIND_IMAX) {
            return isGeqOfficial(l1, a(l2)) && isGeqOfficial(l1, b(l2));
        }
        if (k1 == KIND_IMAX) {
            return isGeqOfficial(b(l1), l2);
        }
        int b1 = offBase(l1);
        int b2 = offBase(l2);
        int o1 = offK(l1);
        int o2 = offK(l2);
        if (b1 == b2 || b2 == ZERO) {
            return o1 >= o2;
        }
        if (o1 == o2 && o1 > 0) {
            return isGeqOfficial(b1, b2);
        }
        return false;
    }

    // =====================================================================================
    // Canonical form of the denotation and the complete comparison
    // =====================================================================================
    //
    // A sublevel is C(E, k) = "k if every parameter in E is nonzero, else 0" (k >= 1) or
    // V(E, x, k) = "x + k if every parameter in E is nonzero, else 0" (x in E). Every level denotes
    // the max of a finite set of sublevels:
    //     zero            {}
    //     param x         { V({x}, x, 0) }
    //     succ^k l        { C({}, k) } + { s shifted by k | s in S(l) }
    //     max a b         S(a) + S(b)
    //     imax a b        S(b) + { s with guard E_s + E_t | s in S(a), t in S(b) }
    // (b is nonzero iff the guard of one of its sublevels holds). Domination between sublevels:
    //     C(E,j) <= C(F,k)      iff F subset E and j <= k
    //     C(E,j) <= V(F,y,k)    iff F subset E and j <= k + 1          (y >= 1 under the guard)
    //     V(E,x,j) <= V(F,y,k)  iff F subset E and x = y and j <= k
    // max S <= max T pointwise iff every element of S is dominated by one element of T (evaluate at
    // "1 on E, 0 elsewhere" for a C and at "N on x, 1 on E-{x}, 0 elsewhere", N large, for a V), and
    // domination is a partial order, so the set of maximal sublevels is a canonical form.
    // (Y. Géran, "A Canonical Form for Universe Levels in Impredicative Type Theory"; lean4lean uses
    // the same form.) Guards are bit sets over the parameters of the level being processed, numbered
    // by ascending name id in the stored form.

    /**
     * Canonical form id of the denotation of {@code l}: for ids {@code > 0},
     * {@code canonId(x) == canonId(y)} iff {@code x} and {@code y} are equal under every assignment
     * of naturals to the parameters. {@link #CANON_UNKNOWN} if the level exceeds the size budget.
     * Memoised per level. The id is only meaningful for comparison (and {@link #canonRep}).
     */
    public int canonId(int l) {
        int c = canon(l);
        return c != 0 ? c : canonSlow(l);
    }

    /**
     * A representative of the equivalence class of {@code l}: {@code canonRep(x) == canonRep(y)} iff
     * {@code x} and {@code y} are semantically equal, provided both have a canonical form; a level
     * without one represents itself. The representative is the first member of the class whose
     * canonical form was computed (for classes with a member in the frozen base: a base level, the
     * same for all overlays).
     */
    public int canonRep(int l) {
        int c = canonId(l);
        return c > 0 ? repOf(c) : l;
    }

    /**
     * Complete level equality: true iff the two levels are equal under every assignment. A superset
     * of {@link #isEquivOfficial} (which is sound), decided by comparing canonical forms; only if a
     * canonical form is unknown the official procedure decides.
     */
    public boolean isEquiv(int l1, int l2) {
        if (l1 == l2) {
            return true;
        }
        int c1 = canon(l1);
        int c2 = canon(l2);
        if (c1 > 0 && c2 > 0) {
            return c1 == c2;
        }
        return isEquivSlow(l1, l2);
    }

    private boolean isEquivSlow(int l1, int l2) {
        int c1 = canonId(l1);
        int c2 = canonId(l2);
        if (c1 > 0 && c2 > 0) {
            return c1 == c2;
        }
        return normalize(l1) == normalize(l2);
    }

    /**
     * Complete {@code l1 >= l2}: true iff it holds under every assignment. A superset of
     * {@link #isGeqOfficial}; falls back to it if a canonical form is unknown.
     */
    public boolean isGeq(int l1, int l2) {
        if (l1 == l2 || l2 == ZERO) {
            return true;
        }
        int c1 = canonId(l1);
        int c2 = canonId(l2);
        if (c1 > 0 && c2 > 0) {
            return c1 == c2 || canonGeq(c1, c2);
        }
        return isGeqOfficial(l1, l2);
    }

    /** {@code isGeq(l2, l1)}. */
    public boolean isLeq(int l1, int l2) {
        return isGeq(l2, l1);
    }

    /** Pairwise {@link #isEquiv} of two level lists of the same length (constant level arguments). */
    public boolean listsEquiv(int list1, int list2) {
        if (list1 == list2) {
            return true;
        }
        int n = lists.length(list1);
        if (lists.length(list2) != n) {
            return false;
        }
        int s1 = lists.start(list1);
        int s2 = lists.start(list2);
        for (int i = 0; i < n; i++) {
            if (!isEquiv(lists.poolOf(list1)[s1 + i], lists.poolOf(list2)[s2 + i])) {
                return false;
            }
        }
        return true;
    }

    private int canonSlow(int l) {
        if (frozen) {
            throw frozenError();
        }
        cSp = 0;
        gSp = 0;
        cNumNames = 0;
        cSteps = 0;
        cFail = false;
        int c;
        if (!canonRec(l) || cFail) {
            c = CANON_UNKNOWN;
        } else {
            c = canonIntern();
            if (repOf(c) == 0 && (c >= baseCanonTop || l < baseTop)) {
                setCanonRep(c, l);
            }
        }
        // In an overlay every base level already has its form (freeze() computed it), so this stores
        // overlay results only; the guard keeps the memo valid across reset() in any case.
        if (l >= baseTop || (c > 0 && c < baseCanonTop)) {
            setCanon(l, c);
        }
        return c;
    }

    private void cPush(long mask, int var, int k) {
        if (cSp >= CANON_MAX_ENTRIES) {
            cFail = true;
            return;
        }
        if (cSp == cMask.length) {
            int n = cSp << 1;
            cMask = Arrays.copyOf(cMask, n);
            cVar = Arrays.copyOf(cVar, n);
            cK = Arrays.copyOf(cK, n);
        }
        cMask[cSp] = mask;
        cVar[cSp] = var;
        cK[cSp] = k;
        cSp++;
    }

    private int localParam(int nameId) {
        int n = cNumNames;
        for (int i = 0; i < n; i++) {
            if (cNames[i] == nameId) {
                return i;
            }
        }
        if (n == 64) {
            cFail = true;
            return 0;
        }
        cNames[n] = nameId;
        cNumNames = n + 1;
        return n;
    }

    /** Appends the pruned sublevel set of {@code l} at the top of the working stack. */
    private boolean canonRec(int l) {
        if (cFail || ++cSteps > CANON_MAX_STEPS) {
            return false;
        }
        int inf = info(l);
        if ((inf & F_ALWAYS_ZERO) != 0) {
            return true;
        }
        int start = cSp;
        switch (inf & KIND_MASK) {
            case KIND_PARAM: {
                int idx = localParam(a(l));
                cPush(1L << idx, idx, 0);
                return !cFail;
            }
            case KIND_SUCC: {
                int k = offK(l);
                if (!canonRec(offBase(l))) {
                    return false;
                }
                for (int i = start; i < cSp; i++) {
                    cK[i] += k;
                }
                cPush(0L, -1, k);
                break;
            }
            case KIND_MAX: {
                if (!canonRec(a(l)) || !canonRec(b(l))) {
                    return false;
                }
                break;
            }
            default: {
                int y = b(l);
                if (!canonRec(y)) {
                    return false;
                }
                if ((info(y) & F_NEVER_ZERO) != 0) {
                    // imax a b = max a b
                    if (!canonRec(a(l))) {
                        return false;
                    }
                    break;
                }
                int bEnd = cSp;
                int g0 = gSp;
                for (int i = start; i < bEnd; i++) {
                    addMinimalGuard(g0, cMask[i]);
                }
                int g1 = gSp;
                if (!canonRec(a(l))) {
                    return false;
                }
                int aEnd = cSp;
                if ((long) (aEnd - bEnd) * (g1 - g0) > CANON_MAX_ENTRIES) {
                    return false;
                }
                for (int i = bEnd; i < aEnd; i++) {
                    for (int g = g0; g < g1; g++) {
                        cPush(cMask[i] | gMask[g], cVar[i], cK[i]);
                    }
                }
                if (cFail) {
                    return false;
                }
                int np = cSp - aEnd;
                System.arraycopy(cMask, aEnd, cMask, bEnd, np);
                System.arraycopy(cVar, aEnd, cVar, bEnd, np);
                System.arraycopy(cK, aEnd, cK, bEnd, np);
                cSp = bEnd + np;
                gSp = g0;
                break;
            }
        }
        if (cFail) {
            return false;
        }
        prune(start);
        return true;
    }

    /** Keeps {@code gMask[g0 .. gSp)} the set of inclusion-minimal guards seen so far. */
    private void addMinimalGuard(int g0, long m) {
        int n = gSp;
        for (int g = g0; g < n; g++) {
            if ((gMask[g] & ~m) == 0) {
                return;
            }
        }
        int w = g0;
        for (int g = g0; g < n; g++) {
            long x = gMask[g];
            if ((m & ~x) != 0) {
                gMask[w++] = x;
            }
        }
        if (w == gMask.length) {
            gMask = Arrays.copyOf(gMask, w << 1);
        }
        gMask[w++] = m;
        gSp = w;
    }

    /** Entry i is dominated by entry j (see the table above); reflexive. */
    private boolean dominated(int i, int j) {
        if ((cMask[j] & ~cMask[i]) != 0) {
            return false;
        }
        int vi = cVar[i];
        int vj = cVar[j];
        if (vi < 0) {
            return vj < 0 ? cK[i] <= cK[j] : cK[i] <= cK[j] + 1;
        }
        return vj == vi && cK[i] <= cK[j];
    }

    /**
     * Removes from {@code [start, cSp)} every entry dominated by another one (of equal entries the
     * first stays). Comparing an entry with the kept earlier entries and all later entries is
     * enough: the first occurrence of a maximal entry above it is in one of the two groups.
     */
    private void prune(int start) {
        int end = cSp;
        int w = start;
        for (int i = start; i < end; i++) {
            boolean dom = false;
            for (int j = start; j < w && !dom; j++) {
                dom = dominated(i, j);
            }
            for (int j = i + 1; j < end && !dom; j++) {
                dom = dominated(i, j)
                        && !(cMask[i] == cMask[j] && cVar[i] == cVar[j] && cK[i] == cK[j]);
            }
            if (!dom) {
                if (w != i) {
                    cMask[w] = cMask[i];
                    cVar[w] = cVar[i];
                    cK[w] = cK[i];
                }
                w++;
            }
        }
        cSp = w;
    }

    /**
     * Serialises the working set {@code [0, cSp)} canonically and interns it: parameters renumbered
     * by ascending name id, entries sorted by (guard as unsigned number, variable, offset), then
     * {@code [n, entry...]} with entry = {@code [2*|E| + isV, names of E ascending, (name of x), k]}.
     */
    private int canonIntern() {
        int n = cSp;
        int np = cNumNames;
        boolean sorted = true;
        for (int i = 1; i < np; i++) {
            if (cNames[i - 1] > cNames[i]) {
                sorted = false;
                break;
            }
        }
        if (sorted) {
            System.arraycopy(cNames, 0, cSorted, 0, np);
        } else {
            for (int i = 0; i < np; i++) {
                int r = 0;
                int x = cNames[i];
                for (int j = 0; j < np; j++) {
                    if (cNames[j] < x) {
                        r++;
                    }
                }
                cRank[i] = r;
                cSorted[r] = x;
            }
            for (int e = 0; e < n; e++) {
                long m = cMask[e];
                long nm = 0;
                while (m != 0) {
                    int bit = Long.numberOfTrailingZeros(m);
                    m &= m - 1;
                    nm |= 1L << cRank[bit];
                }
                cMask[e] = nm;
                if (cVar[e] >= 0) {
                    cVar[e] = cRank[cVar[e]];
                }
            }
        }
        // insertion sort of the entries
        for (int i = 1; i < n; i++) {
            long m = cMask[i];
            int v = cVar[i];
            int k = cK[i];
            int j = i - 1;
            while (j >= 0 && entryLess(m, v, k, j)) {
                cMask[j + 1] = cMask[j];
                cVar[j + 1] = cVar[j];
                cK[j + 1] = cK[j];
                j--;
            }
            cMask[j + 1] = m;
            cVar[j + 1] = v;
            cK[j + 1] = k;
        }
        int sp0 = sp;
        push(n);
        for (int e = 0; e < n; e++) {
            long m = cMask[e];
            int v = cVar[e];
            push((Long.bitCount(m) << 1) | (v >= 0 ? 1 : 0));
            while (m != 0) {
                int bit = Long.numberOfTrailingZeros(m);
                m &= m - 1;
                push(cSorted[bit]);
            }
            if (v >= 0) {
                push(cSorted[v]);
            }
            push(cK[e]);
        }
        int id = canons.intern(scr, sp0, sp - sp0);
        sp = sp0;
        int j = id - canonSplit;
        if (j >= canonRep.length) {
            canonRep = Arrays.copyOf(canonRep, j + (j >> 1) + 16);
        }
        return id;
    }

    private boolean entryLess(long m, int v, int k, int j) {
        int c = Long.compareUnsigned(m, cMask[j]);
        if (c != 0) {
            return c < 0;
        }
        if (v != cVar[j]) {
            return v < cVar[j];
        }
        return k < cK[j];
    }

    /** Every sublevel of canonical form {@code cb} is dominated by a sublevel of {@code ca}. */
    private boolean canonGeq(int ca, int cb) {
        int[] p = canons.poolOf(cb);
        int[] q = canons.poolOf(ca);
        int pb = canons.start(cb);
        int nb = p[pb++];
        int a0 = canons.start(ca);
        int na = q[a0++];
        for (int e = 0; e < nb; e++) {
            int hdr = p[pb];
            int ne = hdr >>> 1;
            boolean isV = (hdr & 1) != 0;
            int eNames = pb + 1;
            int x = isV ? p[eNames + ne] : 0;
            int k = p[eNames + ne + (isV ? 1 : 0)];
            pb = eNames + ne + (isV ? 2 : 1);
            boolean found = false;
            int pa = a0;
            for (int f = 0; f < na && !found; f++) {
                int h2 = q[pa];
                int nf = h2 >>> 1;
                boolean v2 = (h2 & 1) != 0;
                int fNames = pa + 1;
                int y = v2 ? q[fNames + nf] : 0;
                int k2 = q[fNames + nf + (v2 ? 1 : 0)];
                pa = fNames + nf + (v2 ? 2 : 1);
                if (nf > ne) {
                    continue;
                }
                boolean ok = isV ? (v2 && y == x && k <= k2) : (v2 ? k <= k2 + 1 : k <= k2);
                if (ok && sortedSubset(q, fNames, nf, p, eNames, ne)) {
                    found = true;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    /** {@code q[f .. f+nf)} is a subset of {@code p[e .. e+ne)}; both ascending. */
    private static boolean sortedSubset(int[] q, int f, int nf, int[] p, int e, int ne) {
        int i = 0;
        int j = 0;
        while (i < nf) {
            if (j == ne) {
                return false;
            }
            int x = q[f + i];
            int y = p[e + j];
            if (x == y) {
                i++;
                j++;
            } else if (y < x) {
                j++;
            } else {
                return false;
            }
        }
        return true;
    }

    // =====================================================================================
    // Lists
    // =====================================================================================

    /**
     * Interns {@code buf[from .. from+len)} as a list (of level ids, or of parameter name ids).
     * Equal contents give equal ids; the empty list is 0.
     */
    public int internList(int[] buf, int from, int len) {
        return lists.intern(buf, from, len);
    }

    public int listLength(int list) {
        return lists.length(list);
    }

    public int listGet(int list, int index) {
        return lists.get(list, index);
    }

    /** Direct read access to the interned lists (level lists, name lists, substitutions). */
    public IntListStore lists() {
        return lists;
    }

    /** True if some value occurs twice in the list (duplicate universe parameter names). */
    public boolean listHasDuplicates(int list) {
        int n = lists.length(list);
        int s = lists.start(list);
        int[] p = lists.poolOf(list);
        for (int i = 1; i < n; i++) {
            int x = p[s + i];
            for (int j = 0; j < i; j++) {
                if (p[s + j] == x) {
                    return true;
                }
            }
        }
        return false;
    }

    // =====================================================================================
    // Universe parameter instantiation (level.cpp:261-300)
    // =====================================================================================

    /**
     * The substitution {@code paramNames[i] := levels[i]} as an id for {@link #instantiate}.
     *
     * @return 0 for the empty and for the identity substitution ({@code levels[i]} is
     *     {@code param(paramNames[i])} for all i), -1 if the two lists differ in length (the caller
     *     must reject: wrong number of universe levels), otherwise a positive id; equal
     *     (names, levels) pairs give equal ids.
     */
    public int mkSubst(int paramNames, int levels) {
        int n = lists.length(paramNames);
        if (lists.length(levels) != n) {
            return -1;
        }
        if (n == 0) {
            return 0;
        }
        int[] pp = lists.poolOf(paramNames);
        int[] lp = lists.poolOf(levels);
        int ps = lists.start(paramNames);
        int ls = lists.start(levels);
        boolean identity = true;
        for (int i = 0; i < n; i++) {
            int l = lp[ls + i];
            if ((info(l) & KIND_MASK) != KIND_PARAM || a(l) != pp[ps + i]) {
                identity = false;
                break;
            }
        }
        if (identity) {
            return 0;
        }
        if (frozen) {
            throw frozenError();
        }
        pair[0] = paramNames;
        pair[1] = levels;
        // Own lists first: three in four pairs of a worker are pairs its session already made.
        return lists.internOwnFirst(pair, 0, 2);
    }

    /** The parameter name list of a substitution made by {@link #mkSubst} (not for 0). */
    public int substParams(int subst) {
        return lists.get(subst, 0);
    }

    /** The level list of a substitution made by {@link #mkSubst} (not for 0). */
    public int substLevels(int subst) {
        return lists.get(subst, 1);
    }

    /**
     * Official {@code instantiate(l, ps, ls)} for the substitution {@code subst} (an id from
     * {@link #mkSubst}, 0 = identity): parameters are replaced by the level of the first equal name,
     * unknown parameters stay, and every rebuilt max/imax goes through {@link #mkMax}/{@link #mkIMax}
     * (so this is not plain substitution). A node whose operands are unchanged is returned as is.
     * Cached per (level, substitution).
     */
    public int instantiate(int l, int subst) {
        if (subst == 0 || (info(l) & F_HAS_PARAM) == 0) {
            return l;
        }
        long key = ((long) l << 32) | (subst & 0xFFFFFFFFL);
        int i = cacheSlot(key);
        if (icKey[i] == key) {
            int st = icStamp[i];
            if (st == epoch || st == forever) {
                return icVal[i];
            }
        }
        return instantiateSlow(l, subst, key, i);
    }

    /** Slot of a (level or list, substitution) key in the direct-mapped caches (package-private for the benchmark). */
    static int cacheSlot(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h >>> (64 - CACHE_BITS));
    }

    private int instantiateSlow(int l, int subst, long key, int slot) {
        int ps = lists.get(subst, 0);
        int ls = lists.get(subst, 1);
        int n = lists.length(ps);
        // Building levels never interns a list, so the two arrays stay the ones of these lists.
        int r = instRec(l, lists.poolOf(ps), lists.start(ps), lists.poolOf(ls), lists.start(ls), n);
        if (!frozen) {
            icKey[slot] = key;
            icVal[slot] = r;
            icStamp[slot] = (l < baseTop && r < baseTop && substIsBase(subst)) || baseTop == 0 ? forever : epoch;
        }
        return r;
    }

    /**
     * True if the substitution is made of base ids only: its id, its two lists AND the levels in its level list.
     * The id alone does not say so (finding LV1-F1): a substitution is an interned pair of list ids in the store
     * of the level lists and name lists, so the pair of this session can be FOUND as a base list of two ints
     * while its level list is a list of the overlay - and that id names another substitution after
     * {@link #reset()}. Nor does the id of its level list (finding INV-1, see {@link #isBaseLevelList}). Only an
     * entry of base ids may get the stamp that survives a reset. (The list of parameter names holds name ids,
     * which mean the same in every session: its id is enough.)
     */
    private boolean substIsBase(int subst) {
        return subst < baseListTop && lists.get(subst, 0) < baseListTop && isBaseLevelList(lists.get(subst, 1));
    }

    /**
     * True if {@code list}, read as a list of LEVELS, is the same level list in every overlay of this store's
     * base and after every {@link #reset()}: its id is a base id AND each of its elements is a base level. The id
     * alone does not say so (finding INV-1, the family of LV1-F1; *agentic-note*: rep-INV-1): equal contents give
     * equal ids whatever a list holds, and level lists share their store with name lists, name sets and
     * substitution pairs. The level list of a session is therefore FOUND as a base list whenever the base holds a
     * list of the same ints - a list of parameter names whose name ids are, as numbers, the ids this overlay gave
     * to its levels - and that base id then reads as another level list in the next session, or as a list of ids
     * that do not exist. Whatever is kept across a reset on the strength of a list id must ask this for a list of
     * levels. Linear in the length of the list. The empty list qualifies. On a store that is not an overlay the
     * question is whether the list is one of its lists and holds levels of the store.
     */
    boolean isBaseLevelList(int list) {
        boolean root = baseTop == 0;
        if (list < 0 || list >= (root ? lists.size() : baseListTop)) {
            return false;
        }
        int n = lists.length(list);
        int s = lists.start(list);
        int[] p = lists.poolOf(list);
        int bound = root ? top : baseTop;
        for (int i = 0; i < n; i++) {
            int l = p[s + i];
            if (l <= 0 || l >= bound) {
                return false;
            }
        }
        return true;
    }

    private int instRec(int l, int[] pp, int ps, int[] lp, int ls, int n) {
        int inf = info(l);
        if ((inf & F_HAS_PARAM) == 0) {
            return l;
        }
        switch (inf & KIND_MASK) {
            case KIND_PARAM: {
                int nm = a(l);
                for (int i = 0; i < n; i++) {
                    if (pp[ps + i] == nm) {
                        return lp[ls + i];
                    }
                }
                return l;
            }
            case KIND_SUCC: {
                int base = offBase(l);
                int nb = instRec(base, pp, ps, lp, ls, n);
                return nb == base ? l : succN(nb, offK(l));
            }
            case KIND_MAX: {
                int x = a(l);
                int y = b(l);
                int nx = instRec(x, pp, ps, lp, ls, n);
                int ny = instRec(y, pp, ps, lp, ls, n);
                return nx == x && ny == y ? l : mkMax(nx, ny);
            }
            default: {
                int x = a(l);
                int y = b(l);
                int nx = instRec(x, pp, ps, lp, ls, n);
                int ny = instRec(y, pp, ps, lp, ls, n);
                return nx == x && ny == y ? l : mkIMax(nx, ny);
            }
        }
    }

    /** {@link #instantiate(int, int)} with {@code mkSubst(paramNames, levels)}; -1 on a length mismatch. */
    public int instantiate(int l, int paramNames, int levels) {
        int s = mkSubst(paramNames, levels);
        return s < 0 ? -1 : instantiate(l, s);
    }

    /**
     * {@link #instantiate(int, int)} applied to every element of a level list; returns the (interned)
     * result list, the same id if nothing changed. Cached per (list, substitution).
     */
    public int instantiateList(int list, int subst) {
        if (subst == 0 || list == 0) {
            return list;
        }
        long key = ((long) list << 32) | (subst & 0xFFFFFFFFL);
        int i = cacheSlot(key);
        if (lcKey[i] == key) {
            int st = lcStamp[i];
            if (st == epoch || st == forever) {
                return lcVal[i];
            }
        }
        return instantiateListSlow(list, subst, key, i);
    }

    private int instantiateListSlow(int list, int subst, long key, int slot) {
        if (frozen) {
            throw frozenError();
        }
        int n = lists.length(list);
        int st = lists.start(list);
        int sp0 = sp;
        boolean changed = false;
        for (int i = 0; i < n; i++) {
            int e = lists.poolOf(list)[st + i];
            int r = instantiate(e, subst);
            changed |= r != e;
            push(r);
        }
        int res = changed ? lists.intern(scr, sp0, n) : list;
        sp = sp0;
        lcKey[slot] = key;
        lcVal[slot] = res;
        // base ids only, by content: a result of session levels can be FOUND as a base list (isBaseLevelList)
        lcStamp[slot] = (isBaseLevelList(list) && isBaseLevelList(res) && substIsBase(subst)) || baseTop == 0 ? forever : epoch;
        return res;
    }

    // =====================================================================================
    // Declared parameters (level.cpp:249-259)
    // =====================================================================================

    /**
     * Official {@code get_undef_param}: the first parameter of {@code l} (pre-order, lhs first) whose
     * name is not in the name list {@code paramNames}, as the id of that {@code param} level; 0 if
     * all are declared.
     */
    public int undeclaredParam(int l, int paramNames) {
        int inf = info(l);
        if ((inf & F_HAS_PARAM) == 0) {
            return 0;
        }
        int j = l - split;
        if (j >= 0) {
            if (chk[j] == paramNames) {
                return 0;
            }
        } else if (l < bChk.length && bChk[l] == paramNames + 1) {
            return 0;
        }
        int r = undeclaredRec(l, lists.poolOf(paramNames), lists.start(paramNames), lists.length(paramNames));
        // Remember successes; an overlay's base levels may only remember base lists (reset()).
        if (r == 0 && !frozen && (l >= baseTop || paramNames < baseListTop)) {
            if (j >= 0) {
                chk[j] = paramNames;
            } else {
                if (l >= bChk.length) {
                    // The memo of a shared overlay for the levels of its base: private, sized to the base.
                    bChk = Arrays.copyOf(bChk, split + (split >> 3) + 16);
                }
                bChk[l] = paramNames + 1;
            }
        }
        return r;
    }

    private int undeclaredRec(int l, int[] p, int ps, int n) {
        for (;;) {
            int inf = info(l);
            if ((inf & F_HAS_PARAM) == 0) {
                return 0;
            }
            switch (inf & KIND_MASK) {
                case KIND_PARAM: {
                    int nm = a(l);
                    for (int i = 0; i < n; i++) {
                        if (p[ps + i] == nm) {
                            return 0;
                        }
                    }
                    return l;
                }
                case KIND_SUCC:
                    l = offBase(l);
                    break;
                default: {
                    int r = undeclaredRec(a(l), p, ps, n);
                    if (r != 0) {
                        return r;
                    }
                    l = b(l);
                    break;
                }
            }
        }
    }

    /** True iff every parameter of {@code l} is in the name list {@code paramNames}. */
    public boolean paramsDeclared(int l, int paramNames) {
        return undeclaredParam(l, paramNames) == 0;
    }

    /**
     * {@link #undeclaredParam} over a level list: the first offending {@code param} level, or 0.
     */
    public int undeclaredParamInList(int levelList, int paramNames) {
        int n = lists.length(levelList);
        int st = lists.start(levelList);
        for (int i = 0; i < n; i++) {
            int r = undeclaredParam(lists.poolOf(levelList)[st + i], paramNames);
            if (r != 0) {
                return r;
            }
        }
        return 0;
    }

    // =====================================================================================
    // Printing (diagnostics)
    // =====================================================================================

    /** Lean-like rendering: {@code 2}, {@code u}, {@code u+1}, {@code max (u+1) v}, {@code imax u v}. */
    public String toString(int l) {
        StringBuilder sb = new StringBuilder();
        appendTo(sb, l);
        return sb.toString();
    }

    public void appendTo(StringBuilder sb, int l) {
        if (l <= 0 || l >= top) {
            sb.append("<invalid level ").append(l).append('>');
            return;
        }
        int inf = info(l);
        if ((inf & F_EXPLICIT) != 0) {
            sb.append(offK(l));
            return;
        }
        int k = offK(l);
        if (k > 0) {
            appendChild(sb, offBase(l));
            sb.append('+').append(k);
            return;
        }
        int kind = inf & KIND_MASK;
        if (kind == KIND_PARAM) {
            names.appendName(sb, a(l));
            return;
        }
        sb.append(kind == KIND_MAX ? "max " : "imax ");
        appendChild(sb, a(l));
        // max and imax are right associative
        int r = b(l);
        while ((info(r) & KIND_MASK) == kind) {
            sb.append(' ');
            appendChild(sb, a(r));
            r = b(r);
        }
        sb.append(' ');
        appendChild(sb, r);
    }

    private void appendChild(StringBuilder sb, int l) {
        int inf = info(l);
        if ((inf & F_EXPLICIT) != 0 || (inf & KIND_MASK) == KIND_PARAM) {
            appendTo(sb, l);
        } else {
            sb.append('(');
            appendTo(sb, l);
            sb.append(')');
        }
    }

    /** Renders a level list as {@code {l1, l2}}. */
    public String listToString(int levelList) {
        StringBuilder sb = new StringBuilder("{");
        int n = lists.length(levelList);
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            appendTo(sb, lists.get(levelList, i));
        }
        return sb.append('}').toString();
    }
}
