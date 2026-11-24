package rlvm.store;

import java.util.Arrays;

/**
 * The derivation of a term node from its children (ARCHITECTURE.md 2.1), in one place: the loader's
 * {@link StoreBuilder}, the block decoder, promotion and the overlay constructors all write nodes
 * through an instance of this class, so that a term has the same summaries wherever it is written.
 *
 * <p>One instance per writing thread (it holds that thread's list store, level adapter and small
 * caches). {@code write*} methods store the 8 words of a node at {@code d[w .. w+8)}; the arrays of
 * the children are passed by the caller, which knows the regions.
 *
 * <p>Invariants of what is written:
 * <ul>
 *   <li>{@code looseRange} is exact below {@link Node#LOOSE_SAT}; at the limit it is sticky (a node
 *       above a saturated node is saturated) and {@link Node#F_LOOSE_SAT} is set;
 *   <li>{@code fvMask} is a superset of the free de Bruijn indices (bit 63 = some index &gt;= 63); it is
 *       exact for a node none of whose sub-terms has a loose index &gt;= 63;
 *   <li>{@code depMax} is the largest rank of a constant mentioned below, {@link Node#DEP_UNRESOLVED}
 *       if some CONST / PROJ is unbound or has the wrong number of levels, or a literal's
 *       prerequisites are not all declared; {@link Node#F_HAS_CONST} iff {@code depMax != 0};
 *   <li>{@code lpSet} is the exact sorted set of universe-parameter names below;
 *       {@link Node#F_HAS_LPARAM} iff {@code lpSet != 0}.
 * </ul>
 */
final class NodeCtx {

    private static final int CACHE = 1 << 10;

    final DeclTable decls;
    final Specials specials;
    final IntLists lists;
    private LevelInfo levels;

    /** Direct-mapped caches; key in the high word (0 = empty), value in the low word. */
    private final long[] levelSetCache = new long[CACHE];
    private final long[] listSetCache = new long[CACHE];
    private final long[] unionKey = new long[CACHE];
    private final int[] unionVal = new int[CACHE];
    private boolean levelSetDirty;
    private boolean listSetDirty;
    private boolean unionDirty;

    private int[] walk = new int[32];
    private int[] found = new int[16];
    private int[] seen = new int[64];
    private int seenCount;

    NodeCtx(DeclTable decls, Specials specials, IntLists lists, LevelInfo levels) {
        this.decls = decls;
        this.specials = specials;
        this.lists = lists;
        this.levels = levels;
    }

    void setLevels(LevelInfo levels) {
        this.levels = levels;
        clearCaches();
    }

    LevelInfo levels() {
        return levels;
    }

    /** Must be called when the list or level ids this context has seen may be reissued (overlay reset). */
    void clearCaches() {
        if (levelSetDirty) {
            Arrays.fill(levelSetCache, 0L);
            levelSetDirty = false;
        }
        if (listSetDirty) {
            Arrays.fill(listSetCache, 0L);
            listSetDirty = false;
        }
        if (unionDirty) {
            Arrays.fill(unionKey, 0L);
            unionDirty = false;
        }
    }

    // ---- lpSet helpers -----------------------------------------------------------------------------

    int union(int a, int b) {
        if (a == b || b == 0) {
            return a;
        }
        if (a == 0) {
            return b;
        }
        long key = ((long) a << 32) | b;
        int slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> 54);
        if (unionKey[slot] == key) {
            return unionVal[slot];
        }
        int r = lists.unionSorted(a, b);
        unionKey[slot] = key;
        unionVal[slot] = r;
        unionDirty = true;
        return r;
    }

    /** Sorted set of the parameter names of {@code level}; 0 if none. */
    int paramSet(int level) {
        if (level == 0) {
            return 0;
        }
        LevelInfo lv = levels;
        if (lv == null) {
            throw new IllegalStateException(
                    "this store has no LevelInfo: create it with Store(inputBytes, lists, levelInfo) or call "
                            + "Store.bindLevels before the first SORT / CONST node is written");
        }
        if (!lv.hasParam(level)) {
            return 0;
        }
        int slot = (level * 0x9E3779B1) >>> 22;
        long e = levelSetCache[slot];
        if ((int) (e >>> 32) == level) {
            return (int) e;
        }
        int r = computeParamSet(lv, level);
        levelSetCache[slot] = ((long) level << 32) | (r & 0xFFFFFFFFL);
        levelSetDirty = true;
        return r;
    }

    /** Union of {@link #paramSet} over the elements of a level list. */
    int paramSetOfList(int levelList) {
        if (levelList == 0) {
            return 0;
        }
        int slot = (levelList * 0x9E3779B1) >>> 22;
        long e = listSetCache[slot];
        if ((int) (e >>> 32) == levelList) {
            return (int) e;
        }
        int n = lists.length(levelList);
        int acc = 0;
        for (int i = 0; i < n; i++) {
            acc = union(acc, paramSet(lists.get(levelList, i)));
        }
        listSetCache[slot] = ((long) levelList << 32) | (acc & 0xFFFFFFFFL);
        listSetDirty = true;
        return acc;
    }

    /**
     * Iterative walk of the level DAG. A max / imax node is expanded once (visited set), so the work
     * is linear in the size of the level even when it is a DAG; successor chains need no marks.
     */
    private int computeParamSet(LevelInfo lv, int level) {
        int sp = 0;
        int n = 0;
        seenCount = 0;
        walk[sp++] = level;
        while (sp > 0) {
            int l = walk[--sp];
            if (l == 0 || !lv.hasParam(l)) {
                continue;
            }
            int p = lv.paramName(l);
            if (p >= 0) {
                if (n == found.length) {
                    found = Arrays.copyOf(found, n * 2);
                }
                found[n++] = p;
                continue;
            }
            int x = lv.lhs(l);
            int y = lv.rhs(l);
            if (y != 0) {
                if (!markSeen(l)) {
                    continue;
                }
                if (sp + 2 > walk.length) {
                    walk = Arrays.copyOf(walk, walk.length * 2);
                }
                walk[sp++] = y;
            } else if (sp + 1 > walk.length) {
                walk = Arrays.copyOf(walk, walk.length * 2);
            }
            walk[sp++] = x;
        }
        if (seen.length > 4096) {
            seen = new int[64];
        } else if (seenCount != 0) {
            Arrays.fill(seen, 0);
        }
        return IntListOps.internSet(lists, found, n);
    }

    /** Adds {@code l} to the visited set; false if it was there. */
    private boolean markSeen(int l) {
        if ((seenCount + 1) * 2 > seen.length) {
            int[] old = seen;
            seen = new int[old.length * 2];
            for (int v : old) {
                if (v != 0) {
                    int i = (v * 0x9E3779B1) >>> 7 & (seen.length - 1);
                    while (seen[i] != 0) {
                        i = (i + 1) & (seen.length - 1);
                    }
                    seen[i] = v;
                }
            }
        }
        int mask = seen.length - 1;
        int i = (l * 0x9E3779B1) >>> 7 & mask;
        for (;;) {
            int v = seen[i];
            if (v == l) {
                return false;
            }
            if (v == 0) {
                seen[i] = l;
                seenCount++;
                return true;
            }
            i = (i + 1) & mask;
        }
    }

    /** True if a CONST of constant {@code cid} with level list {@code levelList} has the wrong number of levels. */
    /** True if a level list of a CONST node may be stored in a shared term. */
    boolean listIsShared(int levelList) {
        return levelList == 0 || lists.isSharedList(levelList);
    }

    boolean arityMismatch(int cid, int levelList) {
        return lists.length(levelList) != decls.numLevelParams(cid);
    }

    // ---- node writers ------------------------------------------------------------------------------

    static void writeBVar(int[] d, int w, int index, int extra) {
        int range;
        int flags = extra;
        if (index < 0 || index >= Node.LOOSE_SAT - 1) {
            range = Node.LOOSE_SAT;
            flags |= Node.F_LOOSE_SAT;
        } else {
            range = index + 1;
        }
        long mask = 1L << (index < 0 || index > 63 ? 63 : index);
        d[w] = Kind.BVAR | flags | (range << 16);
        d[w + 1] = index;
        d[w + 2] = 0;
        d[w + 3] = 0;
        d[w + 4] = (int) mask;
        d[w + 5] = (int) (mask >>> 32);
        d[w + 6] = 0;
        d[w + 7] = 0;
    }

    void writeSort(int[] d, int w, int level, int extra) {
        int lp = paramSet(level);
        d[w] = Kind.SORT | extra | (lp != 0 ? Node.F_HAS_LPARAM : 0);
        d[w + 1] = level;
        d[w + 2] = 0;
        d[w + 3] = 0;
        d[w + 4] = 0;
        d[w + 5] = 0;
        d[w + 6] = 0;
        d[w + 7] = lp;
    }

    /**
     * @param cidOrNotName constant id, or {@code ~nameId} for an unbound reference
     * @param arityBad the level list has the wrong length for the constant
     */
    void writeConst(int[] d, int w, int cidOrNotName, int levelList, int name, boolean arityBad, int extra) {
        int lp = paramSetOfList(levelList);
        int flags = extra | Node.F_HAS_CONST | (lp != 0 ? Node.F_HAS_LPARAM : 0);
        int dep;
        if (cidOrNotName <= 0 || arityBad) {
            flags |= Node.F_UNRESOLVED;
            dep = Node.DEP_UNRESOLVED;
        } else {
            dep = decls.rank(cidOrNotName);
        }
        d[w] = Kind.CONST | flags;
        d[w + 1] = cidOrNotName;
        d[w + 2] = levelList;
        d[w + 3] = name;
        d[w + 4] = 0;
        d[w + 5] = 0;
        d[w + 6] = dep;
        d[w + 7] = lp;
    }

    void writeApp(int[] d, int w, int f, int x, int[] fa, int[] xa, int extra) {
        int fw = (f & Node.INDEX_MASK) << 3;
        int xw = (x & Node.INDEX_MASK) << 3;
        int fh = fa[fw];
        int xh = xa[xw];
        int fr = fh >>> 16;
        int xr = xh >>> 16;
        int lf = fa[fw + 7];
        int lx = xa[xw + 7];
        int df = fa[fw + 6];
        int dx = xa[xw + 6];
        d[w] = Kind.APP | extra | ((fh | xh) & (Node.F_INHERIT_MASK | Node.F_LOOSE_SAT)) | ((fr > xr ? fr : xr) << 16);
        d[w + 1] = f;
        d[w + 2] = x;
        d[w + 3] = (fh & 0xF) == Kind.APP ? fa[fw + 3] : f;
        d[w + 4] = fa[fw + 4] | xa[xw + 4];
        d[w + 5] = fa[fw + 5] | xa[xw + 5];
        d[w + 6] = df > dx ? df : dx;
        d[w + 7] = lf == lx || lx == 0 ? lf : union(lf, lx);
    }

    /** LAM or PI. */
    void writeBinder(int[] d, int w, int kind, int dom, int body, int[] da, int[] ba, int extra) {
        int dw = (dom & Node.INDEX_MASK) << 3;
        int bw = (body & Node.INDEX_MASK) << 3;
        int dh = da[dw];
        int bh = ba[bw];
        int dr = dh >>> 16;
        int br = bh >>> 16;
        int bru = br == Node.LOOSE_SAT ? Node.LOOSE_SAT : (br > 0 ? br - 1 : 0);
        long bm = (ba[bw + 4] & 0xFFFFFFFFL) | ((long) ba[bw + 5] << 32);
        long m = (da[dw + 4] & 0xFFFFFFFFL) | ((long) da[dw + 5] << 32) | (bm >>> 1) | (br > 64 ? 1L << 63 : 0L);
        int ld = da[dw + 7];
        int lb = ba[bw + 7];
        int dd = da[dw + 6];
        int db = ba[bw + 6];
        d[w] = kind | extra | ((dh | bh) & (Node.F_INHERIT_MASK | Node.F_LOOSE_SAT)) | ((dr > bru ? dr : bru) << 16);
        d[w + 1] = dom;
        d[w + 2] = body;
        d[w + 3] = 0;
        d[w + 4] = (int) m;
        d[w + 5] = (int) (m >>> 32);
        d[w + 6] = dd > db ? dd : db;
        d[w + 7] = ld == lb || lb == 0 ? ld : union(ld, lb);
    }

    void writeLet(int[] d, int w, int type, int value, int body, int[] ta, int[] va, int[] ba, int extra) {
        int tw = (type & Node.INDEX_MASK) << 3;
        int vw = (value & Node.INDEX_MASK) << 3;
        int bw = (body & Node.INDEX_MASK) << 3;
        int th = ta[tw];
        int vh = va[vw];
        int bh = ba[bw];
        int br = bh >>> 16;
        int range = Math.max(Math.max(th >>> 16, vh >>> 16), br == Node.LOOSE_SAT ? Node.LOOSE_SAT : (br > 0 ? br - 1 : 0));
        long bm = (ba[bw + 4] & 0xFFFFFFFFL) | ((long) ba[bw + 5] << 32);
        long m = (ta[tw + 4] & 0xFFFFFFFFL) | ((long) ta[tw + 5] << 32) | (va[vw + 4] & 0xFFFFFFFFL)
                | ((long) va[vw + 5] << 32) | (bm >>> 1) | (br > 64 ? 1L << 63 : 0L);
        d[w] = Kind.LET | extra | Node.F_HAS_LET | ((th | vh | bh) & (Node.F_INHERIT_MASK | Node.F_LOOSE_SAT))
                | (range << 16);
        d[w + 1] = type;
        d[w + 2] = value;
        d[w + 3] = body;
        d[w + 4] = (int) m;
        d[w + 5] = (int) (m >>> 32);
        d[w + 6] = Math.max(Math.max(ta[tw + 6], va[vw + 6]), ba[bw + 6]);
        d[w + 7] = union(union(ta[tw + 7], va[vw + 7]), ba[bw + 7]);
    }

    /** @param cidOrNotName constant id of the structure type, or {@code ~nameId} */
    void writeProj(int[] d, int w, int cidOrNotName, int index, int struct, int[] sa, int extra) {
        int sw = (struct & Node.INDEX_MASK) << 3;
        int sh = sa[sw];
        int flags = extra | Node.F_HAS_PROJ | Node.F_HAS_CONST | (sh & (Node.F_INHERIT_MASK | Node.F_LOOSE_SAT));
        int dep;
        if (cidOrNotName <= 0) {
            flags |= Node.F_UNRESOLVED;
            dep = Node.DEP_UNRESOLVED;
        } else {
            dep = Math.max(sa[sw + 6], decls.rank(cidOrNotName));
        }
        d[w] = Kind.PROJ | flags | (sh & 0xFFFF0000);
        d[w + 1] = struct;
        d[w + 2] = index;
        d[w + 3] = cidOrNotName;
        d[w + 4] = sa[sw + 4];
        d[w + 5] = sa[sw + 5];
        d[w + 6] = dep;
        d[w + 7] = sa[sw + 7];
    }

    /** NATLIT (small: a = low word, b = high word; big: a = pool index) and STRLIT (a = pool index, b = scalars). */
    static void writeLit(int[] d, int w, int kind, int a, int b, int flags, int dep) {
        d[w] = kind | flags | Node.F_HAS_LIT | (dep != 0 ? Node.F_HAS_CONST : 0);
        d[w + 1] = a;
        d[w + 2] = b;
        d[w + 3] = 0;
        d[w + 4] = 0;
        d[w + 5] = 0;
        d[w + 6] = dep;
        d[w + 7] = 0;
    }

    static void writeFVar(int[] d, int w, int serial, int type, int value) {
        d[w] = Kind.FVAR | Node.F_HAS_FVAR;
        d[w + 1] = serial;
        d[w + 2] = type;
        d[w + 3] = value;
        d[w + 4] = 0;
        d[w + 5] = 0;
        d[w + 6] = 0;
        d[w + 7] = 0;
    }
}
