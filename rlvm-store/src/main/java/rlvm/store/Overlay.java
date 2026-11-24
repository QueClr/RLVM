package rlvm.store;

import java.util.Arrays;

/**
 * A worker's private term scratch: same node layout as the shared regions, ids carry
 * {@link Node#OVERLAY_BIT}. Confined to one thread. One flat {@code int[]} that lives as long as the
 * worker (it starts small, doubles by reallocation up to the cap, and is cut back to 32 MiB as soon
 * as a {@link #resetTo} or {@link #reset()} leaves it at most a quarter full); {@link #reset()} is a
 * pointer reset plus an O(used) clear of the hash-consing index.
 *
 * <p><b>Giving memory back.</b> What one declaration built is released when the session returns to
 * a mark taken before it ({@link #mark()} / {@link #resetTo}): the node array and the hash-consing
 * index go back to their base sizes when they are much larger than what is left, and the literals
 * the worker's {@link LitPool} received since the mark are freed ({@link LitPool#truncateTo}). The
 * index is kept proportional to the nodes it holds, never to the largest declaration the session
 * has seen: a probe of a small declaration stays in the first-level cache.
 *
 * <p>Nodes made by the {@code mk*} methods are hash-consed among the overlay's own {@code mk*} nodes
 * on {@code (kind, a, b, c)}: equal children ids give the same id. A constructed term that happens to
 * be structurally equal to a shared node, or to a node of a decoded proof block, gets its own id (the
 * shared regions and decoded blocks carry no hash index), so id equality implies structural equality
 * and never the converse. Hash-consed nodes carry {@link Node#F_SHARED}: their number of referrers is
 * unknown.
 *
 * <p>Proof blocks are decoded into the overlay on demand ({@link #blockRoot(int)}) and stay until the
 * next reset; decoded nodes carry exact sharing flags ({@link Node}).
 *
 * <p>The overlay must be reset whenever the worker's list or level overlay is reset: {@code lpSet}
 * words of overlay nodes and level ids in SORT / CONST nodes are ids of those overlays.
 *
 * <p>Growing replaces the node array. Every {@link Terms} created over this overlay is updated at
 * once, so its accessors stay valid; a caller that holds the array from {@link #array()} itself must
 * re-read it after any {@code mk*} or {@link #blockRoot}.
 */
public final class Overlay {

    private static final int MIN_INTS = 1 << 13;
    private static final int KEEP_INTS = 1 << 23;
    private static final int MIN_TABLE = 1 << 10;
    /**
     * The hash-consing index is cut back to this many slots (128 KiB) when a reset leaves it nearly
     * empty. Measured on Init and on the Cedar slice with the reference engine (one declaration per
     * mark): 2^13 .. 2^17 are within noise of each other, 2^20 is as slow as never shrinking.
     */
    static final int KEEP_TABLE = 1 << 15;
    private static final int MARK_LOG = 32;
    private static final int KEY_MASK = 0xF | Node.F_NAT_BIG;

    private final Store store;
    private final TermStore shared;
    private final DeclTable decls;
    private final Specials specials;
    private final LitPool lits;
    final NodeCtx ctx;
    private final long maxInts;

    /** The node array; node index i occupies {@code [i << 3, (i << 3) + 8)}. Index 0 is unused. */
    int[] ov;
    /** Next node index. */
    private int n = 1;
    int[] ex;
    int[] gen;

    /**
     * Open addressing over node indices; 0 = empty. An entry is the node index (bits 0..27) with the
     * top four bits of the node's hash in bits 28..31: a probe loads the node of an entry only when
     * these bits agree, i.e. for the node it looks for and for one foreign entry in sixteen. (With an
     * index of the right size a probe passes one foreign entry on average; loading each of their
     * nodes cost 30 % of the CPU time of perf/app-lam and 11 % of the Cedar slice.) Entries are
     * inserted in ascending index order.
     */
    private int[] table = new int[MIN_TABLE];
    private static final int TAG_MASK = 0xF000_0000;
    private int hashed;
    private int serial;

    private int[] blkId = new int[4];
    private int[] blkRoot = new int[4];
    private int[] blkStart = new int[4];
    private int[] blkEnd = new int[4];
    private int blkCount;
    /** Open addressing over the decoded blocks: slot = position in {@link #blkId} + 1. */
    private int[] blkMap = new int[64];
    private byte[] decodeRefs = new byte[256];

    private Terms[] readers = new Terms[2];
    private int readerCount;

    /**
     * The worker literal pool's position at the most recent marks: entry k is the node count, the
     * block count and the {@link LitPool#mark()} of one {@link #mark()} call, oldest first. A
     * {@link #resetTo} that finds its mark here truncates the pool with it.
     */
    private final int[] markNodes = new int[MARK_LOG];
    private final int[] markBlocks = new int[MARK_LOG];
    private final long[] markLits = new long[MARK_LOG];
    private int markCount;

    /**
     * @param store the shared store (child facts are read from it)
     * @param lists the worker's list overlay, used for lpSet unions
     * @param maxBytes hard cap; exceeding it throws {@link OverlayFullException}
     */
    public Overlay(Store store, IntLists lists, long maxBytes) {
        this(store, lists, store.levelInfo(), store.lits(), maxBytes);
    }

    /**
     * @param levels the worker's level adapter (over its level overlay); needed as soon as the worker
     *     makes SORT or CONST nodes with levels it created itself
     * @param lits the worker's literal pool (or the shared one if the worker computes no literals)
     */
    public Overlay(Store store, IntLists lists, LevelInfo levels, LitPool lits, long maxBytes) {
        this.store = store;
        this.shared = store.terms();
        this.decls = store.decls();
        this.specials = store.specials();
        this.lits = lits;
        this.ctx = new NodeCtx(decls, specials, lists, levels);
        this.maxInts = Math.min(Math.max(maxBytes, 64) >> 2, (long) (Node.INDEX_MASK + 1) << 3);
        this.ov = new int[(int) Math.min(MIN_INTS, Math.min(maxInts, Integer.MAX_VALUE - 15))];
        refresh();
    }

    public Store store() {
        return store;
    }

    /** Replaces the level adapter (e.g. after the worker rebuilt its level overlay on a new snapshot). */
    public void setLevels(LevelInfo levels) {
        ctx.setLevels(levels);
    }

    void attach(Terms reader) {
        if (readerCount == readers.length) {
            readers = Arrays.copyOf(readers, readerCount * 2);
        }
        readers[readerCount++] = reader;
    }

    /** Re-reads the shared region arrays (the loader may have reallocated them). */
    public void refresh() {
        ex = shared.exportArray();
        gen = shared.generatedArray();
    }

    private void notifyReaders() {
        for (int i = 0; i < readerCount; i++) {
            readers[i].ov = ov;
        }
    }

    // ---- allocation ------------------------------------------------------------------------------

    private int alloc() {
        int idx = n;
        if (((long) idx + 1) << 3 > ov.length) {
            grow(idx + 1);
        }
        n = idx + 1;
        return idx;
    }

    private void grow(int nodes) {
        long need = ((long) nodes + 1) << 3;
        if (need > maxInts) {
            throw new OverlayFullException();
        }
        long cap = Math.max((long) ov.length * 2, need);
        cap = Math.min(cap, Math.min(maxInts, Integer.MAX_VALUE - 15));
        if (cap < need) {
            throw new OverlayFullException();
        }
        ov = Arrays.copyOf(ov, (int) cap);
        notifyReaders();
    }

    /** Validates a child id (before a node is allocated). */
    private void check(int id) {
        if (id >= Node.OVERLAY_BIT) {
            if ((id & Node.INDEX_MASK) >= n) {
                throw new IllegalArgumentException("stale or foreign overlay term " + Integer.toHexString(id));
            }
        } else if (id <= 0) {
            throw new IllegalArgumentException("not a term id: " + id);
        }
    }

    private int[] arr(int id) {
        return id >= Node.OVERLAY_BIT ? ov : (id < Node.GEN_BASE ? ex : gen);
    }

    // ---- hash-consing ------------------------------------------------------------------------------

    private static int hash(int key, int a, int b, int c) {
        int h = (a * 0x9E3779B1) ^ (b * 0x85EBCA6B) ^ (c * 0xC2B2AE35) ^ (key * 0x27D4EB2F);
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        return h ^ (h >>> 13);
    }

    /** Index of the node {@code (key, a, b)}, or {@code -1 - slot} of the free slot where it belongs. */
    private int find(int key, int a, int b, int h) {
        int[] t = table;
        int[] o = ov;
        int mask = t.length - 1;
        int i = h & mask;
        for (;;) {
            int s = t[i];
            if (s == 0) {
                return -1 - i;
            }
            if (((s ^ h) & TAG_MASK) == 0) {
                int idx = s & Node.INDEX_MASK;
                int w = idx << 3;
                if (o[w + 1] == a && o[w + 2] == b && (o[w] & KEY_MASK) == key) {
                    return idx;
                }
            }
            i = (i + 1) & mask;
        }
    }

    private int find3(int key, int a, int b, int c, int h) {
        int[] t = table;
        int[] o = ov;
        int mask = t.length - 1;
        int i = h & mask;
        for (;;) {
            int s = t[i];
            if (s == 0) {
                return -1 - i;
            }
            if (((s ^ h) & TAG_MASK) == 0) {
                int idx = s & Node.INDEX_MASK;
                int w = idx << 3;
                if (o[w + 1] == a && o[w + 2] == b && o[w + 3] == c && (o[w] & KEY_MASK) == key) {
                    return idx;
                }
            }
            i = (i + 1) & mask;
        }
    }

    private void insert(int idx, int slot, int h) {
        table[slot] = idx | (h & TAG_MASK);
        if (++hashed * 2 > table.length) {
            rehash(table.length * 2);
        }
    }

    /** Smallest index capacity for {@code nodes} hash-consed nodes at a load of at most one half. */
    private static int tableFor(int nodes) {
        return Math.max(MIN_TABLE, Integer.highestOneBit(Math.max(nodes, 1)) << 2);
    }

    private int hashOfNode(int w) {
        int[] o = ov;
        int key = o[w] & KEY_MASK;
        int k = key & 0xF;
        return hash(key, o[w + 1], o[w + 2], k == Kind.LET || k == Kind.PROJ ? o[w + 3] : 0);
    }

    /**
     * Rebuilds the index with {@code cap} slots, inserting the hash-consed nodes in ascending order,
     * and recounts them.
     */
    private void rehash(int cap) {
        int[] t = takeIndex(cap);
        int mask = cap - 1;
        int[] o = ov;
        int b = 0;
        int count = 0;
        for (int idx = 1; idx < n; idx++) {
            while (b < blkCount && blkEnd[b] <= idx) {
                b++;
            }
            if (b < blkCount && idx >= blkStart[b]) {
                idx = blkEnd[b] - 1;
                b++;
                continue;
            }
            int w = idx << 3;
            if ((o[w] & 0xF) == Kind.FVAR) {
                continue;
            }
            int h = hashOfNode(w);
            int i = h & mask;
            while (t[i] != 0) {
                i = (i + 1) & mask;
            }
            t[i] = idx | (h & TAG_MASK);
            count++;
        }
        int[] old = table;
        table = t;
        hashed = count;
        giveIndex(old);
    }

    /**
     * Retired index tables by log2 size, EVERY ONE ALL ZERO (V1 finding F-V1-S1). The index is cut back to
     * KEEP_TABLE slots whenever a reset leaves it nearly empty (it must fit the cache for the small
     * declarations) and doubles again under the next larger one; each of those steps allocated a new table:
     * 2.4 GB of the 13.8 GB that 8 workers allocate in one pass over mathlib, nearly all of it in the lanes of
     * the reference kernel (one {@code resetTo} per declaration). With the pool the same tables go round.
     * Tables of up to 2^POOL_MAX slots (1 MiB) are kept: 2 MiB per overlay at most. (A first version kept
     * tables up to 4 MiB here and up to 8 MiB per map in rlvm-ref: with 8 workers, three lanes each, the image
     * ran out of a 4 GB heap on mathlib.)
     */
    private static final int POOL_MAX = 18;
    private final int[][] indexPool = new int[POOL_MAX + 1][];

    /** An all-zero index of {@code cap} slots: the pooled one, or a new array. */
    private int[] takeIndex(int cap) {
        int k = Integer.numberOfTrailingZeros(cap);
        if (k <= POOL_MAX && cap == 1 << k) {
            int[] t = indexPool[k];
            if (t != null) {
                indexPool[k] = null;
                return t;
            }
        }
        return new int[cap];
    }

    /** Zeroes the retired index {@code t} and keeps it (unless it is too large or its size is already kept). */
    private void giveIndex(int[] t) {
        int k = Integer.numberOfTrailingZeros(t.length);
        if (k <= POOL_MAX && t.length == 1 << k && indexPool[k] == null) {
            Arrays.fill(t, 0);
            indexPool[k] = t;
        }
    }

    /** Replaces the index by an all-zero one of {@code cap} slots. */
    private void freshIndex(int cap) {
        int[] old = table;
        table = takeIndex(cap);
        giveIndex(old);
    }

    // ---- constructors ------------------------------------------------------------------------------

    public int mkBVar(int index) {
        int h = hash(Kind.BVAR, index, 0, 0);
        int r = find(Kind.BVAR, index, 0, h);
        if (r > 0) {
            return r | Node.OVERLAY_BIT;
        }
        int idx = alloc();
        NodeCtx.writeBVar(ov, idx << 3, index, Node.F_SHARED);
        insert(idx, -1 - r, h);
        return idx | Node.OVERLAY_BIT;
    }

    public int mkSort(int level) {
        int h = hash(Kind.SORT, level, 0, 0);
        int r = find(Kind.SORT, level, 0, h);
        if (r > 0) {
            return r | Node.OVERLAY_BIT;
        }
        int idx = alloc();
        ctx.writeSort(ov, idx << 3, level, Node.F_SHARED);
        insert(idx, -1 - r, h);
        return idx | Node.OVERLAY_BIT;
    }

    /** A reference to constant {@code cid} (which must be a constant of the store) at the given levels. */
    public int mkConst(int cid, int levelList) {
        if (cid <= 0) {
            throw new IllegalArgumentException("not a constant id: " + cid);
        }
        return mkConstRaw(cid, levelList, decls.name(cid));
    }

    /**
     * A reference to the constant currently declared under {@code name}; unbound ({@code ~name},
     * flagged {@link Node#F_UNRESOLVED}) if there is none.
     */
    public int mkConstByName(int name, int levelList) {
        int cid = store.names().constOf(name);
        return mkConstRaw(cid > 0 ? cid : ~name, levelList, name);
    }

    /** CONST with the payload words as given ({@code a} = cid or {@code ~nameId}); used to rebuild nodes. */
    int mkConstRaw(int a, int levelList, int name) {
        int h = hash(Kind.CONST, a, levelList, 0);
        int r = find(Kind.CONST, a, levelList, h);
        if (r > 0) {
            return r | Node.OVERLAY_BIT;
        }
        int idx = alloc();
        ctx.writeConst(ov, idx << 3, a, levelList, name, a > 0 && ctx.arityMismatch(a, levelList), Node.F_SHARED);
        insert(idx, -1 - r, h);
        return idx | Node.OVERLAY_BIT;
    }

    public int mkApp(int fn, int arg) {
        int h = hash(Kind.APP, fn, arg, 0);
        int r = find(Kind.APP, fn, arg, h);
        if (r > 0) {
            return r | Node.OVERLAY_BIT;
        }
        check(fn);
        check(arg);
        int idx = alloc();
        ctx.writeApp(ov, idx << 3, fn, arg, arr(fn), arr(arg), Node.F_SHARED);
        insert(idx, -1 - r, h);
        return idx | Node.OVERLAY_BIT;
    }

    /** {@code fn a[0] .. a[n-1]}. */
    public int mkApps(int fn, int[] args, int from, int n) {
        int r = fn;
        for (int i = 0; i < n; i++) {
            r = mkApp(r, args[from + i]);
        }
        return r;
    }

    public int mkLam(int domain, int body) {
        return mkBinder(Kind.LAM, domain, body);
    }

    public int mkPi(int domain, int body) {
        return mkBinder(Kind.PI, domain, body);
    }

    private int mkBinder(int kind, int domain, int body) {
        int h = hash(kind, domain, body, 0);
        int r = find(kind, domain, body, h);
        if (r > 0) {
            return r | Node.OVERLAY_BIT;
        }
        check(domain);
        check(body);
        int idx = alloc();
        ctx.writeBinder(ov, idx << 3, kind, domain, body, arr(domain), arr(body), Node.F_SHARED);
        insert(idx, -1 - r, h);
        return idx | Node.OVERLAY_BIT;
    }

    public int mkLet(int type, int value, int body) {
        int h = hash(Kind.LET, type, value, body);
        int r = find3(Kind.LET, type, value, body, h);
        if (r > 0) {
            return r | Node.OVERLAY_BIT;
        }
        check(type);
        check(value);
        check(body);
        int idx = alloc();
        ctx.writeLet(ov, idx << 3, type, value, body, arr(type), arr(value), arr(body), Node.F_SHARED);
        insert(idx, -1 - r, h);
        return idx | Node.OVERLAY_BIT;
    }

    /** @param structCid constant id of the structure type (or {@code ~nameId} for an unbound name) */
    public int mkProj(int structCid, int fieldIndex, int struct) {
        int h = hash(Kind.PROJ, struct, fieldIndex, structCid);
        int r = find3(Kind.PROJ, struct, fieldIndex, structCid, h);
        if (r > 0) {
            return r | Node.OVERLAY_BIT;
        }
        check(struct);
        int idx = alloc();
        ctx.writeProj(ov, idx << 3, structCid, fieldIndex, struct, arr(struct), Node.F_SHARED);
        insert(idx, -1 - r, h);
        return idx | Node.OVERLAY_BIT;
    }

    /** Literal with an unsigned 64-bit value. */
    public int mkNatLit(long unsignedValue) {
        int lo = (int) unsignedValue;
        int hi = (int) (unsignedValue >>> 32);
        int h = hash(Kind.NATLIT, lo, hi, 0);
        int r = find(Kind.NATLIT, lo, hi, h);
        if (r > 0) {
            return r | Node.OVERLAY_BIT;
        }
        int idx = alloc();
        NodeCtx.writeLit(ov, idx << 3, Kind.NATLIT, lo, hi, Node.F_SHARED, specials.natLiteralRank());
        insert(idx, -1 - r, h);
        return idx | Node.OVERLAY_BIT;
    }

    /**
     * Literal whose value is entry {@code litIndex} of the worker's or the shared {@link LitPool}. A
     * pooled value below 2^64 gives the same node as {@link #mkNatLit}.
     */
    public int mkNatLitBig(int litIndex) {
        if (lits.natLimbCount(litIndex) == 1) {
            return mkNatLit(lits.natLimb(litIndex, 0));
        }
        int key = Kind.NATLIT | Node.F_NAT_BIG;
        int h = hash(key, litIndex, 0, 0);
        int r = find(key, litIndex, 0, h);
        if (r > 0) {
            return r | Node.OVERLAY_BIT;
        }
        int idx = alloc();
        NodeCtx.writeLit(ov, idx << 3, Kind.NATLIT, litIndex, 0, Node.F_SHARED | Node.F_NAT_BIG,
                specials.natLiteralRank());
        insert(idx, -1 - r, h);
        return idx | Node.OVERLAY_BIT;
    }

    public int mkStrLit(int litIndex) {
        int scalars = lits.stringCodePointCount(litIndex);
        int h = hash(Kind.STRLIT, litIndex, scalars, 0);
        int r = find(Kind.STRLIT, litIndex, scalars, h);
        if (r > 0) {
            return r | Node.OVERLAY_BIT;
        }
        int idx = alloc();
        NodeCtx.writeLit(ov, idx << 3, Kind.STRLIT, litIndex, scalars, Node.F_SHARED, specials.stringLiteralRank());
        insert(idx, -1 - r, h);
        return idx | Node.OVERLAY_BIT;
    }

    /** A fresh free variable of the given type (never hash-consed). */
    public int mkFVar(int type) {
        return mkFVarLet(type, 0);
    }

    /**
     * A fresh free variable that stands for a let-bound value ({@code value} = 0: an ordinary one).
     * {@link Terms#b} is its type, {@link Terms#c} its value; {@link TermOps#mkPiOver} /
     * {@link TermOps#mkLambdaOver} turn it back into a LET, as the official {@code mk_binding} does.
     */
    public int mkFVarLet(int type, int value) {
        check(type);
        if (value != 0) {
            check(value);
        }
        int idx = alloc();
        NodeCtx.writeFVar(ov, idx << 3, ++serial, type, value);
        return idx | Node.OVERLAY_BIT;
    }

    // ---- proof blocks ------------------------------------------------------------------------------

    /**
     * Root of proof block {@code block}, decoding the block into this overlay the first time
     * (ARCHITECTURE.md section 2.6). Valid until the next {@link #reset()} (or a {@link #resetTo} below
     * it). Returns 0 for a block without a root.
     */
    public int blockRoot(int block) {
        int[] m = blkMap;
        int mask = m.length - 1;
        int i = (block * 0x9E3779B1) >>> 12 & mask;
        for (;;) {
            int s = m[i];
            if (s == 0) {
                break;
            }
            if (blkId[s - 1] == block) {
                return blkRoot[s - 1];
            }
            i = (i + 1) & mask;
        }
        return store.blocks().decode(block, this);
    }

    /** Enters decoded block number {@code k} (of {@link #blkId}) into the block map. */
    private void mapBlock(int k) {
        if ((k + 1) * 2 > blkMap.length) {
            blkMap = new int[blkMap.length * 2];
            for (int j = 0; j < k; j++) {
                putBlock(j);
            }
        }
        putBlock(k);
    }

    private void putBlock(int k) {
        int[] m = blkMap;
        int mask = m.length - 1;
        int i = (blkId[k] * 0x9E3779B1) >>> 12 & mask;
        while (m[i] != 0) {
            i = (i + 1) & mask;
        }
        m[i] = k + 1;
    }

    /**
     * Rebuilds the block map for the first {@code count} decoded blocks. A map that is much too large
     * is replaced by one sized for {@code count} at a load of at most one half, like {@link #mapBlock}
     * keeps it (it used to be 64 slots whatever {@code count} was: with 64 or more blocks below a mark
     * the probe of {@link #putBlock} never ended; reported by rlvm-admit, 2026-11-24).
     */
    private void remapBlocks(int count) {
        if (blkMap.length > 64 && (long) count * 8 < blkMap.length) {
            blkMap = new int[Math.max(64, Integer.highestOneBit(Math.max(1, count)) << 2)];
        } else {
            Arrays.fill(blkMap, 0);
        }
        for (int j = 0; j < count; j++) {
            putBlock(j);
        }
    }

    /** Decoder: makes room for {@code nodes} nodes and returns the index of the first. */
    int reserve(int nodes) {
        if (((long) n + nodes) << 3 > ov.length) {
            grow(n + nodes);
        }
        return n;
    }

    /** Decoder: zeroed reference counters for {@code nodes} block records. */
    byte[] decodeRefs(int nodes) {
        if (decodeRefs.length < nodes) {
            decodeRefs = new byte[Math.max(nodes, decodeRefs.length * 2)];
        } else {
            Arrays.fill(decodeRefs, 0, nodes, (byte) 0);
        }
        return decodeRefs;
    }

    /** Decoder: the nodes {@code [base, base + nodes)} now hold block {@code block}. */
    void commitBlock(int block, int base, int nodes, int root) {
        if (blkCount == blkId.length) {
            int cap = blkCount * 2;
            blkId = Arrays.copyOf(blkId, cap);
            blkRoot = Arrays.copyOf(blkRoot, cap);
            blkStart = Arrays.copyOf(blkStart, cap);
            blkEnd = Arrays.copyOf(blkEnd, cap);
        }
        blkId[blkCount] = block;
        blkRoot[blkCount] = root;
        blkStart[blkCount] = base;
        blkEnd[blkCount] = base + nodes;
        mapBlock(blkCount);
        blkCount++;
        n = base + nodes;
    }

    // ---- lifecycle ---------------------------------------------------------------------------------

    /** The overlay's node array; changes identity when the overlay grows. */
    public int[] array() {
        return ov;
    }

    /**
     * Opaque position for {@link #resetTo(long)}. Also remembers where the worker's literal pool
     * stands, so that returning to this mark frees the literals made after it.
     */
    public long mark() {
        if (lits != null && lits.isWorkerPool()) {
            int k = markCount;
            if (k > 0 && markNodes[k - 1] == n && markBlocks[k - 1] == blkCount) {
                // same term position: the later pool position is the one every holder of this mark may rely on
                markLits[k - 1] = lits.mark();
            } else {
                if (k == MARK_LOG) {
                    System.arraycopy(markNodes, 1, markNodes, 0, MARK_LOG - 1);
                    System.arraycopy(markBlocks, 1, markBlocks, 0, MARK_LOG - 1);
                    System.arraycopy(markLits, 1, markLits, 0, MARK_LOG - 1);
                    k--;
                }
                markNodes[k] = n;
                markBlocks[k] = blkCount;
                markLits[k] = lits.mark();
                markCount = k + 1;
            }
        }
        return ((long) blkCount << 32) | n;
    }

    /**
     * Forgets every node made after {@code mark} (cost proportional to what is forgotten, or to what
     * is kept if that is less) and gives the memory back: the literals the worker's pool received
     * since the mark are freed, and the node array and the index return to their base sizes when the
     * rest fills at most a quarter of them. Ids at and above the mark are handed out again
     * afterwards, so the caller must have dropped every reference to them; ids below the mark keep
     * their nodes. A caller that holds {@link #array()} must re-read it.
     */
    public void resetTo(long mark) {
        int newN = (int) mark;
        int newBlk = (int) (mark >>> 32);
        if (newN < 1 || newN > n || newBlk > blkCount) {
            throw new IllegalArgumentException("mark is not from this session");
        }
        int forgotten = n - newN;
        if (newN == 1) {
            // nothing is kept: no node has to be looked up
            if (hashed != 0) {
                clearTable(forgotten);
            }
        } else if (forgotten > 4L * newN && forgotten * 4L > table.length) {
            // far more is forgotten than kept: re-enter the survivors instead of removing the others
            n = newN;
            trimBlocks(newBlk);
            rehash(Math.min(table.length, Math.max(tableFor(newN), KEEP_TABLE)));
        } else if (forgotten != 0) {
            removeFromTable(newN, newBlk);
        }
        if (newBlk != blkCount) {
            trimBlocks(newBlk);
        }
        n = newN;
        if (table.length > KEEP_TABLE && (long) hashed * 8 < table.length) {
            rehash(Math.max(tableFor(hashed), KEEP_TABLE));
        }
        releaseNodes();
        truncateLiterals(newN, newBlk);
    }

    /** Removes the index entries of the hash-consed nodes {@code newN ..}, newest first. */
    private void removeFromTable(int newN, int newBlk) {
        int[] t = table;
        int mask = t.length - 1;
        int[] o = ov;
        int b = blkCount - 1;
        int idx = n - 1;
        while (idx >= newN) {
            while (b >= newBlk && blkStart[b] > idx) {
                b--;
            }
            if (b >= newBlk && idx < blkEnd[b]) {
                idx = blkStart[b] - 1;
                b--;
                continue;
            }
            int w = idx << 3;
            if ((o[w] & 0xF) != Kind.FVAR) {
                int i = hashOfNode(w) & mask;
                for (;;) {
                    int s = t[i];
                    if (s == 0) {
                        break;
                    }
                    if ((s & Node.INDEX_MASK) == idx) {
                        t[i] = 0;
                        hashed--;
                        break;
                    }
                    i = (i + 1) & mask;
                }
            }
            idx--;
        }
    }

    /** Empties the index after {@code forgotten} nodes (all of them) were dropped. */
    private void clearTable(int forgotten) {
        if (table.length > KEEP_TABLE) {
            freshIndex(KEEP_TABLE);
        } else if (forgotten * 32L < table.length) {
            // few nodes in a larger index: looking each one up is cheaper than clearing all slots
            removeFromTable(1, 0);
        } else {
            Arrays.fill(table, 0);
        }
        hashed = 0;
    }

    private void trimBlocks(int newBlk) {
        if (newBlk != blkCount) {
            remapBlocks(newBlk);
            blkCount = newBlk;
        }
    }

    /** Returns the node array to its base size when the nodes in use fill at most a quarter of it. */
    private void releaseNodes() {
        if (ov.length > KEEP_INTS && ((long) n << 3) <= (ov.length >>> 2)) {
            int[] smaller = new int[Math.max(KEEP_INTS, Integer.highestOneBit(n << 3) << 1)];
            System.arraycopy(ov, 0, smaller, 0, n << 3);
            ov = smaller;
            notifyReaders();
        }
    }

    /** Frees the worker pool's literals made after the mark {@code (newN, newBlk)}, if that mark is remembered. */
    private void truncateLiterals(int newN, int newBlk) {
        int k = markCount;
        while (k > 0 && (markNodes[k - 1] > newN || markBlocks[k - 1] > newBlk)) {
            k--;
        }
        markCount = k;
        if (k > 0 && markNodes[k - 1] == newN && markBlocks[k - 1] == newBlk) {
            if (lits.covers(markLits[k - 1])) {
                lits.truncateTo(markLits[k - 1]);
            } else {
                // the pool was reset without this overlay: its marks mean nothing any more
                markCount = 0;
            }
        }
    }

    /** Forgets everything; ids handed out before are invalid afterwards. */
    public void reset() {
        n = 1;
        serial = 0;
        markCount = 0;
        if (blkCount != 0) {
            remapBlocks(0);
        }
        blkCount = 0;
        int used = hashed;
        hashed = 0;
        if (used != 0) {
            // a session that ends full keeps an index of its size for the next one
            int want = Math.max(KEEP_TABLE, Integer.highestOneBit(used) << 2);
            if (table.length > want * 4) {
                freshIndex(want);
            } else {
                Arrays.fill(table, 0);
            }
        } else if (table.length > KEEP_TABLE) {
            freshIndex(KEEP_TABLE);
        }
        if (ov.length > KEEP_INTS) {
            ov = new int[KEEP_INTS];
        }
        ctx.clearCaches();
        refresh();
        notifyReaders();
        for (int i = 0; i < readerCount; i++) {
            readers[i].ex = ex;
            readers[i].gen = gen;
        }
    }

    /** Number of nodes in the overlay. */
    public int nodeCount() {
        return n - 1;
    }

    /** Bytes of the nodes in use (what a session's footprint counts). */
    public long usedBytes() {
        return 32L * (n - 1);
    }

    /** Bytes allocated: node array and hash index. */
    public long allocatedBytes() {
        return 4L * (ov.length + table.length);
    }

    /** Slots of the hash-consing index (tests and statistics). */
    public int indexSlots() {
        return table.length;
    }

    /** Number of hash-consed nodes (free variables and decoded block nodes are not hash-consed). */
    public int hashConsedCount() {
        return hashed;
    }

    /** Thrown when the overlay would exceed its cap; the driver turns it into a decline. */
    public static final class OverlayFullException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public OverlayFullException() {
            super("overlay full", null, false, false);
        }
    }
}
