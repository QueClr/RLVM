package rlvm.store;

import java.util.Arrays;

/**
 * Packed proof blocks: the expression records reachable only from one theorem's (or opaque's) value,
 * kept as bytes until a worker needs them and then decoded into that worker's {@link Overlay}.
 *
 * <pre>
 * storage  byte[][] chunks of 1 MiB (the first one 64 KiB); a block never straddles a chunk; one
 *          larger than a chunk gets a dedicated array. Per block, 6 ints: chunk, offset, bytes, nodes,
 *          root (index of the record that is the owner's value; -1 for an ownerless block),
 *          firstRecord (prefix sum of nodes: the block's records are numbered globally); and, for
 *          the loader only, the byte offset of every 16th record (0.25 bytes per record), so that
 *          promotion reaches a record without scanning the block.
 * record   tag:u8  payload            tag bits 0..3 = {@link Kind}, bits 4..7 = small field
 * ref      LEB128 varint r:  r even -&gt; node (k - r/2) of this block (k = index of the record, r/2 &gt;= 1)
 *                            r odd  -&gt; shared term id r &gt;&gt;&gt; 1
 * APP      ref fn, ref arg                         LAM / PI  ref domain, ref body
 * LET      ref type, ref value, ref body           PROJ      varint cid-or-~name (zigzag), varint index, ref struct
 * BVAR     index in the tag's small field if &lt; 15, else 15 and a varint
 * SORT     varint level id
 * CONST    varint cid-or-~name (zigzag), varint level-list id, varint name id;
 *          small field bit 0: the level list has the wrong length for the constant
 * NATLIT   varint value (&lt; 2^64), or with small field bit 0 a varint LitPool index;
 *          small field bit 1: the literal's prerequisite constants were not all declared when the
 *          record was written (the decoded node gets depMax = UNRESOLVED)
 * STRLIT   varint LitPool index; small field bit 1 as for NATLIT
 * </pre>
 * Decoding is one forward pass, no recursion, and derives the node facts (looseRange, fvMask, depMax,
 * lpSet, flags) exactly as the shared regions do; a constant's rank is read from the declaration
 * table, a literal's rank from {@link Specials} (it cannot change once the literal was resolved).
 * The decoder also sets the sharing flags of {@link Node} from a "seen" byte per record: a record
 * referenced a second time becomes {@link Node#F_SHARED}, a binder body and the root
 * {@link Node#F_BODY}, a record whose first referrer has a different free-variable mask
 * {@link Node#F_INVARIANT}.
 *
 * <p>Written by the loader thread, immutable once the owning declaration is published.
 */
public final class ProofBlocks {

    private static final int CHUNK = 1 << 20;
    private static final int FIRST_CHUNK = 1 << 16;
    private static final int ROW = 8;
    /** One byte offset is kept for every {@code 1 << SPARSE_SHIFT} records of a block (promotion seeks). */
    private static final int SPARSE_SHIFT = 4;
    private static final int T_BIT0 = 0x10;
    private static final int T_BIT1 = 0x20;

    private byte[][] chunks = new byte[8][];
    private volatile byte[][] chunksPub = chunks;
    private int chunkCount;
    /** Bytes used in the last chunk; at its capacity when the next block must open a new chunk. */
    private int fill;
    private int fillCap;

    private int[] rows = new int[ROW * 64];
    private volatile int[] rowsPub = rows;
    private int count;
    private volatile int countPub;
    private int records;

    private TermStore terms;
    private NameTable names;
    private DeclTable decls;
    private LitPool lits;
    private Specials specials;

    /**
     * Promotion memo: global record number -> shared id (0 = not promoted), a direct map in chunks of
     * {@code 1 << PROMO_SHIFT} records that are allocated at the first promotion inside them. The
     * records a promotion asks about are neighbours (a record and the earlier records of its block),
     * so their answers share cache lines; a hash table scattered them over the whole memo.
     */
    private static final int PROMO_SHIFT = 12;
    private static final int PROMO_MASK = (1 << PROMO_SHIFT) - 1;
    private int[][] promo = new int[16][];
    private long promoInts;
    private int promoCount;
    private int[] promoStack = new int[64];
    /** Byte offsets (relative to the block) of the records 0, 16, 32, ... of every block. Loader thread only. */
    private int[] sparse = new int[64];
    private int sparseUsed;
    /**
     * {@code coarse[i]} = the block that holds global record {@code i << COARSE_SHIFT}: promotion finds
     * the block of a record from here with a search over a handful of rows. Loader thread only.
     */
    private static final int COARSE_SHIFT = 9;
    private int[] coarse = new int[64];
    private int coarseUsed;
    /**
     * Per global block record: its byte offset from the sparse offset of its group of
     * {@code 1 << SPARSE_SHIFT} records (at most 15 records of at most 16 bytes: it fits a byte), in
     * chunks of {@code 1 << WITHIN_SHIFT} records. With it promotion finds a record with two loads
     * instead of skipping up to 15 records. Loader thread only; given back with the sparse index.
     */
    private static final int WITHIN_SHIFT = 16;
    private static final int WITHIN_MASK = (1 << WITHIN_SHIFT) - 1;
    private byte[][] within = new byte[16][];
    private long withinBytes;
    private long payloadBytes;
    private boolean released;

    // scratch of parse()
    private int pKind;
    private int pTag;
    private int p1;
    private int p2;
    private int p3;
    private long pNat;

    private WriterImpl writer;

    public ProofBlocks() {}

    void bind(Store store) {
        this.terms = store.terms();
        this.names = store.names();
        this.decls = store.decls();
        this.lits = store.lits();
        this.specials = store.specials();
    }

    /** Number of blocks. Volatile read; a block is readable once its owner is published. */
    public int count() {
        return countPub;
    }

    public int nodeCount(int block) {
        return rowsPub[block * ROW + 3];
    }

    public int byteLength(int block) {
        return rowsPub[block * ROW + 2];
    }

    /** Index of the block's root record within the block, or -1 for an ownerless block. */
    public int rootRecord(int block) {
        return rowsPub[block * ROW + 4];
    }

    /**
     * True if something in the block was unresolved when the loader wrote it: a CONST or PROJ record
     * whose name had no constant then (written as {@code ~name}), a CONST record with the wrong
     * number of levels, a literal record whose prerequisite constants were not all declared, or a
     * reference to a shared term whose {@code depMax} is {@link Node#DEP_UNRESOLVED}. It is the test
     * "may the decoded block need the slow path of the structural checks" without decoding:
     * <b>false</b> guarantees that the decoded root has {@code depMax != DEP_UNRESOLVED} and no
     * {@link Node#F_UNRESOLVED}; <b>true</b> may be caused by a record that the root does not reach.
     */
    public boolean hasUnresolved(int block) {
        return rowsPub[block * ROW + 7] != 0;
    }

    /** Global number of the block's first record (records of all blocks are numbered consecutively). */
    public int firstRecord(int block) {
        return rowsPub[block * ROW + 5];
    }

    /** Total number of block records. Loader thread. */
    public int recordCount() {
        return records;
    }

    /** The block that contains global record number {@code record} (binary search). */
    public int blockOfRecord(int record) {
        int[] r = rowsPub;
        int lo = 0;
        int hi = countPub - 1;
        if (record < 0 || hi < 0 || record >= r[hi * ROW + 5] + r[hi * ROW + 3]) {
            throw new IndexOutOfBoundsException("block record " + record);
        }
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (r[mid * ROW + 5] <= record) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        // Blocks without records share their firstRecord with the next block: take the one that holds it.
        while (r[lo * ROW + 3] == 0 || record >= r[lo * ROW + 5] + r[lo * ROW + 3]) {
            lo++;
        }
        return lo;
    }

    public long bytes() {
        return storageBytes() + loaderBytes();
    }

    /** Bytes allocated for the packed records and the block table (what readers need). */
    long storageBytes() {
        long b = 4L * rowsPub.length;
        byte[][] c = chunksPub;
        for (byte[] chunk : c) {
            if (chunk != null) {
                b += chunk.length;
            }
        }
        return b;
    }

    /** Bytes of the packed records and block rows in use. Loader thread. */
    long usedBytes() {
        return payloadBytes + 4L * ROW * count;
    }

    /** Bytes of what only promotion needs: the promotion memo and the sparse record index. */
    long loaderBytes() {
        return 4L * sparse.length + 4L * promoInts + 8L * promo.length + 4L * promoStack.length + 4L * coarse.length
                + withinBytes + 8L * within.length;
    }

    /** True once {@link #releaseLoaderState()} ran: nothing can be promoted any more. */
    public boolean loaderStateReleased() {
        return released;
    }

    /**
     * Loader thread, after the last promotion: gives back the promotion memo and the sparse record
     * index (mathlib: 0.29 GB), which only {@link #promote} reads. Decoding is unaffected.
     * {@link #promote} and {@link #promotedId} throw {@link IllegalStateException} afterwards;
     * {@link #promotedCount()} keeps its value.
     */
    void releaseLoaderState() {
        released = true;
        promo = new int[1][];
        promoInts = 0;
        promoStack = new int[2];
        sparse = new int[0];
        sparseUsed = 0;
        coarse = new int[0];
        coarseUsed = 0;
        within = new byte[0][];
        withinBytes = 0;
    }

    /** The chunk of {@link #within} that holds global record {@code record}, allocated on demand. */
    private byte[] withinChunk(int record) {
        int ci = record >>> WITHIN_SHIFT;
        byte[][] dir = within;
        if (ci >= dir.length) {
            dir = within = Arrays.copyOf(dir, Math.max(dir.length * 2, ci + 1));
        }
        byte[] c = dir[ci];
        if (c == null) {
            c = dir[ci] = new byte[1 << WITHIN_SHIFT];
            withinBytes += 1 << WITHIN_SHIFT;
        }
        return c;
    }

    /** Loader thread: makes the blocks written so far visible to other threads. */
    void publish() {
        countPub = count;
    }

    // ---- decoding ----------------------------------------------------------------------------------

    /** Reads an unsigned LEB128 varint of at most 32 bits: value in the low word, next position in the high word. */
    private static long rd(byte[] s, int p) {
        int b = s[p++];
        if (b >= 0) {
            return ((long) p << 32) | b;
        }
        int v = b & 0x7F;
        int shift = 7;
        for (;;) {
            b = s[p++];
            v |= (b & 0x7F) << shift;
            if (b >= 0) {
                break;
            }
            shift += 7;
        }
        return ((long) p << 32) | (v & 0xFFFFFFFFL);
    }

    /**
     * Decodes {@code block} by appending its nodes to {@code into}; returns the root id (0 for a block
     * without a root). Called by {@link Overlay#blockRoot(int)}. Budget: 60 instructions per node
     * (*agentic-note*: design-architecture-document, 3.6).
     */
    int decode(int block, Overlay into) {
        int[] rw = rowsPub;
        int o = block * ROW;
        byte[] src = chunksPub[rw[o]];
        int p = rw[o + 1];
        int nodes = rw[o + 3];
        int root = rw[o + 4];
        int base = into.reserve(nodes);
        byte[] seen = into.decodeRefs(nodes);
        int[] ov = into.ov;
        int[] ex = into.ex;
        int[] gen = into.gen;
        NodeCtx cx = into.ctx;
        for (int k = 0; k < nodes; k++) {
            int idx = base + k;
            int w = idx << 3;
            int tag = src[p++] & 0xFF;
            switch (tag & 0xF) {
                case Kind.APP: {
                    long r = rd(src, p);
                    int r1 = (int) r;
                    r = rd(src, (int) (r >>> 32));
                    int r2 = (int) r;
                    p = (int) (r >>> 32);
                    int f;
                    int x;
                    int[] fa;
                    int[] xa;
                    if ((r1 & 1) == 0) {
                        f = (idx - (r1 >>> 1)) | Node.OVERLAY_BIT;
                        fa = ov;
                    } else {
                        f = r1 >>> 1;
                        fa = f < Node.GEN_BASE ? ex : gen;
                    }
                    if ((r2 & 1) == 0) {
                        x = (idx - (r2 >>> 1)) | Node.OVERLAY_BIT;
                        xa = ov;
                    } else {
                        x = r2 >>> 1;
                        xa = x < Node.GEN_BASE ? ex : gen;
                    }
                    cx.writeApp(ov, w, f, x, fa, xa, 0);
                    if ((r1 & 1) == 0) {
                        localRef(ov, seen, base, f, w, false);
                    }
                    if ((r2 & 1) == 0) {
                        localRef(ov, seen, base, x, w, false);
                    }
                    break;
                }
                case Kind.LAM:
                case Kind.PI: {
                    long r = rd(src, p);
                    int r1 = (int) r;
                    r = rd(src, (int) (r >>> 32));
                    int r2 = (int) r;
                    p = (int) (r >>> 32);
                    int d = (r1 & 1) == 0 ? (idx - (r1 >>> 1)) | Node.OVERLAY_BIT : r1 >>> 1;
                    int b = (r2 & 1) == 0 ? (idx - (r2 >>> 1)) | Node.OVERLAY_BIT : r2 >>> 1;
                    cx.writeBinder(ov, w, tag & 0xF, d, b, (r1 & 1) == 0 ? ov : (d < Node.GEN_BASE ? ex : gen),
                            (r2 & 1) == 0 ? ov : (b < Node.GEN_BASE ? ex : gen), 0);
                    if ((r1 & 1) == 0) {
                        localRef(ov, seen, base, d, w, false);
                    }
                    if ((r2 & 1) == 0) {
                        localRef(ov, seen, base, b, w, true);
                    }
                    break;
                }
                case Kind.LET: {
                    long r = rd(src, p);
                    int r1 = (int) r;
                    r = rd(src, (int) (r >>> 32));
                    int r2 = (int) r;
                    r = rd(src, (int) (r >>> 32));
                    int r3 = (int) r;
                    p = (int) (r >>> 32);
                    int t = (r1 & 1) == 0 ? (idx - (r1 >>> 1)) | Node.OVERLAY_BIT : r1 >>> 1;
                    int v = (r2 & 1) == 0 ? (idx - (r2 >>> 1)) | Node.OVERLAY_BIT : r2 >>> 1;
                    int b = (r3 & 1) == 0 ? (idx - (r3 >>> 1)) | Node.OVERLAY_BIT : r3 >>> 1;
                    cx.writeLet(ov, w, t, v, b, (r1 & 1) == 0 ? ov : (t < Node.GEN_BASE ? ex : gen),
                            (r2 & 1) == 0 ? ov : (v < Node.GEN_BASE ? ex : gen),
                            (r3 & 1) == 0 ? ov : (b < Node.GEN_BASE ? ex : gen), 0);
                    if ((r1 & 1) == 0) {
                        localRef(ov, seen, base, t, w, false);
                    }
                    if ((r2 & 1) == 0) {
                        localRef(ov, seen, base, v, w, false);
                    }
                    if ((r3 & 1) == 0) {
                        localRef(ov, seen, base, b, w, true);
                    }
                    break;
                }
                case Kind.PROJ: {
                    long r = rd(src, p);
                    int z = (int) r;
                    r = rd(src, (int) (r >>> 32));
                    int index = (int) r;
                    r = rd(src, (int) (r >>> 32));
                    int r1 = (int) r;
                    p = (int) (r >>> 32);
                    int s = (r1 & 1) == 0 ? (idx - (r1 >>> 1)) | Node.OVERLAY_BIT : r1 >>> 1;
                    cx.writeProj(ov, w, (z >>> 1) ^ -(z & 1), index, s,
                            (r1 & 1) == 0 ? ov : (s < Node.GEN_BASE ? ex : gen), 0);
                    if ((r1 & 1) == 0) {
                        localRef(ov, seen, base, s, w, false);
                    }
                    break;
                }
                case Kind.BVAR: {
                    int index = tag >>> 4;
                    if (index == 15) {
                        long r = rd(src, p);
                        index = (int) r;
                        p = (int) (r >>> 32);
                    }
                    NodeCtx.writeBVar(ov, w, index, 0);
                    break;
                }
                case Kind.SORT: {
                    long r = rd(src, p);
                    p = (int) (r >>> 32);
                    cx.writeSort(ov, w, (int) r, 0);
                    break;
                }
                case Kind.CONST: {
                    long r = rd(src, p);
                    int z = (int) r;
                    r = rd(src, (int) (r >>> 32));
                    int list = (int) r;
                    r = rd(src, (int) (r >>> 32));
                    p = (int) (r >>> 32);
                    cx.writeConst(ov, w, (z >>> 1) ^ -(z & 1), list, (int) r, (tag & T_BIT0) != 0, 0);
                    break;
                }
                case Kind.NATLIT: {
                    int dep = (tag & T_BIT1) != 0 ? Node.DEP_UNRESOLVED : cx.specials.natLiteralRank();
                    if ((tag & T_BIT0) != 0) {
                        long r = rd(src, p);
                        p = (int) (r >>> 32);
                        NodeCtx.writeLit(ov, w, Kind.NATLIT, (int) r, 0, Node.F_NAT_BIG, dep);
                    } else {
                        long v = 0;
                        int shift = 0;
                        for (;;) {
                            int b = src[p++];
                            v |= (long) (b & 0x7F) << shift;
                            if (b >= 0) {
                                break;
                            }
                            shift += 7;
                        }
                        NodeCtx.writeLit(ov, w, Kind.NATLIT, (int) v, (int) (v >>> 32), 0, dep);
                    }
                    break;
                }
                case Kind.STRLIT: {
                    long r = rd(src, p);
                    p = (int) (r >>> 32);
                    int dep = (tag & T_BIT1) != 0 ? Node.DEP_UNRESOLVED : cx.specials.stringLiteralRank();
                    NodeCtx.writeLit(ov, w, Kind.STRLIT, (int) r, lits.stringCodePointCount((int) r), 0, dep);
                    break;
                }
                default:
                    throw new IllegalStateException("corrupt proof block " + block + ": tag " + tag);
            }
        }
        int rootId = 0;
        if (root >= 0) {
            ov[(base + root) << 3] |= Node.F_BODY;
            rootId = (base + root) | Node.OVERLAY_BIT;
        }
        into.commitBlock(block, base, nodes, rootId);
        return rootId;
    }

    /** One reference from the node at word {@code pw} to the block-local node {@code child}. */
    private static void localRef(int[] ov, byte[] seen, int base, int child, int pw, boolean bodyPosition) {
        int ci = child & Node.INDEX_MASK;
        int cw = ci << 3;
        int h = ov[cw];
        if (seen[ci - base] == 0) {
            seen[ci - base] = 1;
            if (bodyPosition) {
                h |= Node.F_BODY;
            } else if (ov[cw + 4] != ov[pw + 4] || ov[cw + 5] != ov[pw + 5]) {
                h |= Node.F_INVARIANT;
            }
        } else {
            h |= bodyPosition ? Node.F_SHARED | Node.F_BODY : Node.F_SHARED;
        }
        ov[cw] = h;
    }

    // ---- promotion ---------------------------------------------------------------------------------

    /** Parses the record at {@code p} into the scratch fields; returns the position after it. */
    private int parse(byte[] s, int p) {
        int tag = s[p++] & 0xFF;
        pTag = tag;
        pKind = tag & 0xF;
        long r;
        switch (pKind) {
            case Kind.APP:
            case Kind.LAM:
            case Kind.PI:
                r = rd(s, p);
                p1 = (int) r;
                r = rd(s, (int) (r >>> 32));
                p2 = (int) r;
                return (int) (r >>> 32);
            case Kind.LET:
            case Kind.PROJ:
            case Kind.CONST:
                r = rd(s, p);
                p1 = (int) r;
                r = rd(s, (int) (r >>> 32));
                p2 = (int) r;
                r = rd(s, (int) (r >>> 32));
                p3 = (int) r;
                return (int) (r >>> 32);
            case Kind.BVAR:
                p1 = tag >>> 4;
                if (p1 == 15) {
                    r = rd(s, p);
                    p1 = (int) r;
                    p = (int) (r >>> 32);
                }
                return p;
            case Kind.SORT:
            case Kind.STRLIT:
                r = rd(s, p);
                p1 = (int) r;
                return (int) (r >>> 32);
            case Kind.NATLIT: {
                long v = 0;
                int shift = 0;
                for (;;) {
                    int b = s[p++];
                    v |= (long) (b & 0x7F) << shift;
                    if (b >= 0) {
                        break;
                    }
                    shift += 7;
                }
                pNat = v;
                p1 = (int) v;
                return p;
            }
            default:
                throw new IllegalStateException("corrupt proof block: tag " + tag);
        }
    }

    private int promoGet(int record) {
        int ci = record >>> PROMO_SHIFT;
        int[][] dir = promo;
        if (ci >= dir.length) {
            return 0;
        }
        int[] c = dir[ci];
        return c == null ? 0 : c[record & PROMO_MASK];
    }

    private void promoPut(int record, int id) {
        int ci = record >>> PROMO_SHIFT;
        int[][] dir = promo;
        if (ci >= dir.length) {
            dir = promo = Arrays.copyOf(dir, Math.max(dir.length * 2, ci + 1));
        }
        int[] c = dir[ci];
        if (c == null) {
            c = dir[ci] = new int[1 << PROMO_SHIFT];
            promoInts += 1 << PROMO_SHIFT;
        }
        c[record & PROMO_MASK] = id;
        promoCount++;
    }

    /** {@link #blockOfRecord} for the loader thread: the coarse index narrows the search to a few rows. */
    int blockOfRecordLoader(int record) {
        int[] r = rows;
        int m = record >>> COARSE_SHIFT;
        if (record < 0 || m >= coarseUsed || record >= records) {
            throw new IndexOutOfBoundsException("block record " + record);
        }
        int lo = coarse[m];
        int hi = m + 1 < coarseUsed ? coarse[m + 1] : count - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (r[mid * ROW + 5] <= record) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        // Blocks without records share their firstRecord with the next block: take the one that holds it.
        while (r[lo * ROW + 3] == 0 || record >= r[lo * ROW + 5] + r[lo * ROW + 3]) {
            lo++;
        }
        return lo;
    }

    /** Shared id of a promoted block record, or 0 if it was not promoted. Loader thread. */
    int promotedId(int record) {
        if (released) {
            throw new IllegalStateException("the loader state of the proof blocks was released");
        }
        return promoGet(record);
    }

    /** Number of block records that were promoted so far. Loader thread. */
    public int promotedCount() {
        return promoCount;
    }

    /**
     * Copies global record {@code record} and everything it reaches inside its block into the export
     * region ("promotion", *agentic-note*: design-architecture-document, 3.3); returns the shared id. Records promoted earlier are
     * found in the promotion memo (global record number to shared id) and not copied again.
     * Loader thread only. An explicit-stack walk over the records that are reachable and not yet
     * promoted, children first; a record is found through the block's sparse offset index (at most
     * 15 records are skipped), so the cost is proportional to what is copied, not to the block.
     *
     * <p>A promoted node is written with the environment of the moment of promotion: a CONST / PROJ
     * is bound to the constant its name has now, and a literal gets the current literal rank. All
     * promoted nodes carry {@link Node#F_SHARED}.
     */
    int promote(int record, TermStore shared) {
        if (released) {
            throw new IllegalStateException("the loader state of the proof blocks was released: nothing can be promoted");
        }
        int hit = promoGet(record);
        if (hit != 0) {
            return hit;
        }
        int block = blockOfRecordLoader(record);
        int[] rw = rows;
        int o = block * ROW;
        int first = rw[o + 5];
        byte[] src = chunks[rw[o]];
        int blockAt = rw[o + 1];
        int sparseAt = rw[o + 6];
        int[] st = promoStack;
        int sp = 0;
        st[sp++] = record - first;
        st[sp++] = seek(blockAt, sparseAt, first, record - first);
        while (sp > 0) {
            int k = st[sp - 2];
            parse(src, st[sp - 1]);
            int c;
            switch (pKind) {
                case Kind.APP:
                case Kind.LAM:
                case Kind.PI:
                    c = pending(first, k, p1);
                    if (c < 0) {
                        c = pending(first, k, p2);
                    }
                    break;
                case Kind.LET:
                    c = pending(first, k, p1);
                    if (c < 0) {
                        c = pending(first, k, p2);
                    }
                    if (c < 0) {
                        c = pending(first, k, p3);
                    }
                    break;
                case Kind.PROJ:
                    c = pending(first, k, p3);
                    break;
                default:
                    c = -1;
                    break;
            }
            if (c >= 0) {
                if (sp + 2 > st.length) {
                    st = promoStack = Arrays.copyOf(st, st.length * 2);
                }
                st[sp++] = c;
                st[sp++] = seek(blockAt, sparseAt, first, c);
                continue;
            }
            int id;
            switch (pKind) {
                case Kind.APP:
                case Kind.LAM:
                case Kind.PI:
                    id = shared.appendExport(pKind, child(first, k, p1), child(first, k, p2), 0, Node.F_SHARED);
                    break;
                case Kind.LET:
                    id = shared.appendExport(Kind.LET, child(first, k, p1), child(first, k, p2), child(first, k, p3),
                            Node.F_SHARED);
                    break;
                case Kind.PROJ: {
                    int cn = (p1 >>> 1) ^ -(p1 & 1);
                    int name = cn > 0 ? decls.name(cn) : ~cn;
                    int cid = names.constOf(name);
                    id = shared.appendExport(Kind.PROJ, child(first, k, p3), p2, cid > 0 ? cid : ~name, Node.F_SHARED);
                    break;
                }
                case Kind.CONST: {
                    int cid = names.constOf(p3);
                    id = shared.appendExport(Kind.CONST, cid > 0 ? cid : ~p3, p2, p3, Node.F_SHARED);
                    break;
                }
                case Kind.BVAR:
                case Kind.SORT:
                    id = shared.appendExport(pKind, p1, 0, 0, Node.F_SHARED);
                    break;
                case Kind.NATLIT:
                    if ((pTag & T_BIT0) != 0) {
                        id = shared.appendExport(Kind.NATLIT, p1, 0, 0, Node.F_SHARED | Node.F_NAT_BIG);
                    } else {
                        id = shared.appendExport(Kind.NATLIT, (int) pNat, (int) (pNat >>> 32), 0, Node.F_SHARED);
                    }
                    break;
                default:
                    id = shared.appendExport(Kind.STRLIT, p1, lits.stringCodePointCount(p1), 0, Node.F_SHARED);
                    break;
            }
            promoPut(first + k, id);
            sp -= 2;
        }
        return promoGet(record);
    }

    /**
     * Byte position of record {@code k} of the block that starts at {@code blockAt}, whose first
     * record has the global number {@code first}: the sparse offset of its group plus its offset
     * within the group.
     */
    private int seek(int blockAt, int sparseAt, int first, int k) {
        int g = first + k;
        return blockAt + sparse[sparseAt + (k >>> SPARSE_SHIFT)] + (within[g >>> WITHIN_SHIFT][g & WITHIN_MASK] & 0xFF);
    }

    /** The block-local index of the record that {@code ref} names, if it is local and not promoted yet; else -1. */
    private int pending(int first, int k, int ref) {
        if ((ref & 1) == 0) {
            int c = k - (ref >>> 1);
            if (promoGet(first + c) == 0) {
                return c;
            }
        }
        return -1;
    }

    private int child(int first, int k, int ref) {
        return (ref & 1) == 0 ? promoGet(first + k - (ref >>> 1)) : ref >>> 1;
    }

    // ---- writing -----------------------------------------------------------------------------------

    /** Loader-side writer for one block. Records must be appended children first. */
    public interface Writer {
        /** Reference to an earlier record of the block being written. */
        static int local(int backDistance) {
            return backDistance << 1;
        }

        /** Reference to a shared term. */
        static int shared(int termId) {
            return (termId << 1) | 1;
        }

        void bvar(int index);

        void sort(int level);

        void constant(int cidOrNotName, int levelList, int name);

        void app(int refFn, int refArg);

        void lam(int refDomain, int refBody);

        void pi(int refDomain, int refBody);

        void let(int refType, int refValue, int refBody);

        void proj(int cidOrNotName, int index, int refStruct);

        void natSmall(long unsignedValue);

        void natBig(int litIndex);

        void str(int litIndex);

        /**
         * Appends {@code count} APP / LAM / PI / LET records given in PACKED form: what a scanner thread
         * of the reader prepares for a run of proof-private records, so that the loader thread only
         * has to supply the references it alone can resolve (LP2, parallel packing).
         *
         * <p>{@code packed[off .. off+len)} are the bytes of the records as they will stand in the
         * block, except that some references are missing: a record is its kind byte followed by its
         * references in the order of {@link #app}, {@link #lam}, {@link #pi}, {@link #let}, each either
         * present as its final LEB128 bytes (a {@link #local} reference, or a {@link #shared} reference
         * to a term of an EARLIER unit) or OPEN. {@code lengths[lengthsOff + i]} is the number of bytes
         * of record {@code i} present in {@code packed}. The open references are
         * {@code open[openOff .. openOff + 2 * openCount)} in record and child order, two ints each:
         * the offset from {@code off} at which the reference belongs (bit 31 set: it is the body of a
         * binder), and its value: {@code > 0} a shared term id, {@code < 0} {@code -(i + 1)} for record
         * {@code i} of the block being written (an earlier one). A reference is open exactly if the
         * next entry of the list has the offset reached. The records written are byte for byte the
         * records that the calls {@link #app}, {@link #lam}, {@link #pi}, {@link #let} with the same
         * references write. A reference present in the bytes is not validated beyond what those calls
         * validate; that a local one names an earlier record of this block is the caller's business.
         *
         * @param maxEarly the largest shared term id among the references present in the bytes (0:
         *     none); every such term must belong to a unit before the one being written
         * @throws IllegalArgumentException if the lengths, the list of open references and the bytes
         *     do not fit together (the block under construction is then in an undefined state)
         */
        default void run(byte[] packed, int off, int len, byte[] lengths, int lengthsOff, int count, int[] open,
                int openOff, int openCount, int maxEarly) {
            int p = off;
            int e = openOff;
            final int openEnd = openOff + 2 * openCount;
            int[] r = new int[3];
            for (int i = 0; i < count; i++) {
                int k = size();
                int recordEnd = p + (lengths[lengthsOff + i] & 0xFF);
                int kind = packed[p++];
                int children = runChildren(kind);
                for (int c = 0; c < children; c++) {
                    if (e < openEnd && (open[e] & 0x7FFF_FFFF) == p - off) {
                        int v = open[e + 1];
                        e += 2;
                        r[c] = v > 0 ? shared(v) : local(k + v + 1);
                    } else {
                        int v = 0;
                        int shift = 0;
                        int b;
                        do {
                            b = packed[p++];
                            v |= (b & 0x7F) << shift;
                            shift += 7;
                        } while (b < 0);
                        if ((v & 1) != 0 && (v >>> 1) > maxEarly) {
                            throw new IllegalArgumentException("corrupt packed run: shared term " + (v >>> 1) + " above " + maxEarly);
                        }
                        r[c] = v;
                    }
                }
                if (p != recordEnd) {
                    throw new IllegalArgumentException("corrupt packed run");
                }
                switch (kind) {
                    case Kind.APP -> app(r[0], r[1]);
                    case Kind.LAM -> lam(r[0], r[1]);
                    case Kind.PI -> pi(r[0], r[1]);
                    default -> let(r[0], r[1], r[2]);
                }
            }
            if (p != off + len || e != openEnd) {
                throw new IllegalArgumentException("corrupt packed run");
            }
        }

        /** Number of children of a packed record of kind {@code kind} (APP, LAM, PI: 2; LET: 3). */
        static int runChildren(int kind) {
            if (kind == Kind.APP || kind == Kind.LAM || kind == Kind.PI) {
                return 2;
            }
            if (kind == Kind.LET) {
                return 3;
            }
            throw new IllegalArgumentException("corrupt packed run: kind " + kind);
        }

        /** Number of records written so far. */
        int size();

        /**
         * Closes the block and returns its index.
         *
         * @param rootLocal index of the record that is the owner's value, or -1 for an ownerless block
         */
        int finish(int rootLocal);

        /** Discards the block under construction. */
        void abort();
    }

    /** The writer of this table (one block at a time; the same object every call). Loader thread. */
    public Writer newWriter() {
        if (writer == null) {
            writer = new WriterImpl();
        }
        return writer;
    }

    private final class WriterImpl implements Writer {
        private byte[] buf = new byte[1 << 12];
        private int pos;
        private int n;
        /** A record of the block under construction is unbound, has the wrong arity or an unresolved literal. */
        private boolean unresolved;
        private int[] marks = new int[64];

        /** Start of a record of at most {@code bytes} bytes. */
        private void need(int bytes) {
            int m = n >>> SPARSE_SHIFT;
            if ((n & ((1 << SPARSE_SHIFT) - 1)) == 0) {
                if (m == marks.length) {
                    marks = Arrays.copyOf(marks, m * 2);
                }
                marks[m] = pos;
            }
            // the record's offset within its group, for promotion (records < 2^31: finish checks)
            int g = records + n;
            if (g >= 0) {
                withinChunk(g)[g & WITHIN_MASK] = (byte) (pos - marks[m]);
            }
            if (pos + bytes > buf.length) {
                long cap = Math.max((long) buf.length * 2, (long) pos + bytes);
                if (cap > Integer.MAX_VALUE - 15) {
                    throw new StoreLimitException("proof block larger than 2 GiB");
                }
                buf = Arrays.copyOf(buf, (int) cap);
            }
        }

        private void v32(int v) {
            while ((v & ~0x7F) != 0) {
                buf[pos++] = (byte) (v | 0x80);
                v >>>= 7;
            }
            buf[pos++] = (byte) v;
        }

        private void ref(int r, boolean bodyPosition) {
            if ((r & 1) == 0) {
                int d = r >>> 1;
                if (d < 1 || d > n) {
                    throw new IllegalArgumentException("local reference " + d + " back from record " + n);
                }
            } else {
                sharedRef(r >>> 1, bodyPosition);
            }
            v32(r);
        }

        /** A record of the block under construction refers to shared term {@code id}. */
        private void sharedRef(int id, boolean bodyPosition) {
            if (id <= 0) {
                throw new IllegalArgumentException("not a shared term: " + id);
            }
            TermStore ts = terms;
            if (ts != null) {
                if (id >= ts.exportEnd()) {
                    throw new IllegalArgumentException("shared term " + id + " does not exist");
                }
                // the node is read only if an unresolved export node exists at or below it: in an
                // export whose references all bind, no reference costs a load of the shared region
                if (id >= ts.firstUnresolved() && ts.writerArray()[(id << 3) + Node.W_DEPMAX] == Node.DEP_UNRESOLVED) {
                    unresolved = true;
                }
                ts.refFromBlock(id, bodyPosition);
            }
        }

        /**
         * The packed run in one pass over the record lengths: the bytes between two open references
         * are copied as they are, an open reference is encoded where it belongs, and every record
         * gets its place in the sparse index. Writes exactly what the record-by-record default writes.
         */
        @Override
        public void run(byte[] packed, int off, int len, byte[] lengths, int lengthsOff, int count, int[] open,
                int openOff, int openCount, int maxEarly) {
            if (count < 0 || len < 0 || openCount < 0 || off < 0 || openOff < 0 || lengthsOff < 0 || maxEarly < 0
                    || off + len > packed.length || openOff + 2 * openCount > open.length
                    || lengthsOff + count > lengths.length) {
                throw new IllegalArgumentException("corrupt packed run");
            }
            final TermStore ts = terms;
            if (ts != null && maxEarly > 0 && (maxEarly >= ts.firstUnresolved() || maxEarly >= ts.segmentStart())) {
                // A reference in the bytes may name an unresolved node, or a node of the open unit
                // (whose sharing flags a reference sets): record by record, with every check.
                Writer.super.run(packed, off, len, lengths, lengthsOff, count, open, openOff, openCount, maxEarly);
                return;
            }
            // room: the bytes are copied, an open reference is at most 5 bytes
            long room = (long) pos + len + 5L * openCount;
            if (room > buf.length) {
                long cap = Math.max((long) buf.length * 2, room);
                if (cap > Integer.MAX_VALUE - 15) {
                    cap = Integer.MAX_VALUE - 15;
                    if (room > cap) {
                        throw new StoreLimitException("proof block larger than 2 GiB");
                    }
                }
                buf = Arrays.copyOf(buf, (int) cap);
            }
            if (count == 0) {
                if (len != 0 || openCount != 0) {
                    throw new IllegalArgumentException("corrupt packed run");
                }
                return;
            }
            if ((long) records + n + count > Integer.MAX_VALUE) {
                throw new StoreLimitException("more than 2^31 - 1 block records");
            }
            int lastMark = (n + count - 1) >>> SPARSE_SHIFT;
            if (lastMark >= marks.length) {
                marks = Arrays.copyOf(marks, Math.max(marks.length * 2, lastMark + 1));
            }
            final byte[] b = buf;
            final int[] mk = marks;
            final int end = off + len;
            final int openEnd = openOff + 2 * openCount;
            int k = n;
            int g = records + n;                 // global number of the record being placed
            byte[] wn = withinChunk(g);
            int in = off;                        // input position of the record being placed
            int p = off;                         // input bytes before p are copied
            int shift = pos - off;               // output position = input position + shift
            int e = openOff;
            int nextOpen = e < openEnd ? off + (open[e] & 0x7FFF_FFFF) : Integer.MAX_VALUE;
            for (int i = 0; i < count; i++, k++, g++) {
                final int at = in + shift;
                if ((k & ((1 << SPARSE_SHIFT) - 1)) == 0) {
                    mk[k >>> SPARSE_SHIFT] = at;
                }
                if ((g & WITHIN_MASK) == 0) {
                    wn = withinChunk(g);
                }
                wn[g & WITHIN_MASK] = (byte) (at - mk[k >>> SPARSE_SHIFT]);
                final int recordEnd = in + (lengths[lengthsOff + i] & 0xFF);
                // the open references of this record stand behind its kind byte, up to its end
                while (nextOpen <= recordEnd) {
                    if (nextOpen <= in || recordEnd > end) {
                        throw new IllegalArgumentException("corrupt packed run");
                    }
                    // the bytes up to the reference
                    int q = p + shift;
                    int m = nextOpen - p;
                    if (m < 16) {
                        for (int j = 0; j < m; j++) {
                            b[q + j] = packed[p + j];
                        }
                    } else {
                        System.arraycopy(packed, p, b, q, m);
                    }
                    q += m;
                    p = nextOpen;
                    // the reference
                    final int v = open[e + 1];
                    int r;
                    if (v > 0) {
                        sharedRef(v, open[e] < 0);
                        r = (v << 1) | 1;
                    } else {
                        int d = k + v + 1;
                        if (d < 1 || d > k) {
                            throw new IllegalArgumentException("local reference " + d + " back from record " + k);
                        }
                        r = d << 1;
                    }
                    final int q0 = q;
                    while ((r & ~0x7F) != 0) {
                        b[q++] = (byte) (r | 0x80);
                        r >>>= 7;
                    }
                    b[q++] = (byte) r;
                    shift += q - q0;
                    e += 2;
                    nextOpen = e < openEnd ? off + (open[e] & 0x7FFF_FFFF) : Integer.MAX_VALUE;
                }
                in = recordEnd;
            }
            if (in != end || e != openEnd) {
                throw new IllegalArgumentException("corrupt packed run");
            }
            System.arraycopy(packed, p, b, p + shift, end - p);
            pos = end + shift;
            n = k;
        }

        @Override
        public void bvar(int index) {
            need(8);
            if (index >= 0 && index < 15) {
                buf[pos++] = (byte) (Kind.BVAR | (index << 4));
            } else {
                buf[pos++] = (byte) (Kind.BVAR | 0xF0);
                v32(index);
            }
            n++;
        }

        @Override
        public void sort(int level) {
            need(8);
            buf[pos++] = Kind.SORT;
            v32(level);
            n++;
        }

        @Override
        public void constant(int cidOrNotName, int levelList, int name) {
            need(20);
            boolean bad = cidOrNotName > 0 && terms != null && terms.ctx() != null
                    && terms.ctx().arityMismatch(cidOrNotName, levelList);
            if (bad || cidOrNotName <= 0) {
                unresolved = true;
            }
            buf[pos++] = (byte) (bad ? Kind.CONST | T_BIT0 : Kind.CONST);
            v32((cidOrNotName << 1) ^ (cidOrNotName >> 31));
            v32(levelList);
            v32(name);
            n++;
        }

        @Override
        public void app(int refFn, int refArg) {
            need(12);
            buf[pos++] = Kind.APP;
            ref(refFn, false);
            ref(refArg, false);
            n++;
        }

        @Override
        public void lam(int refDomain, int refBody) {
            need(12);
            buf[pos++] = Kind.LAM;
            ref(refDomain, false);
            ref(refBody, true);
            n++;
        }

        @Override
        public void pi(int refDomain, int refBody) {
            need(12);
            buf[pos++] = Kind.PI;
            ref(refDomain, false);
            ref(refBody, true);
            n++;
        }

        @Override
        public void let(int refType, int refValue, int refBody) {
            need(20);
            buf[pos++] = Kind.LET;
            ref(refType, false);
            ref(refValue, false);
            ref(refBody, true);
            n++;
        }

        @Override
        public void proj(int cidOrNotName, int index, int refStruct) {
            need(20);
            if (cidOrNotName <= 0) {
                unresolved = true;
            }
            buf[pos++] = Kind.PROJ;
            v32((cidOrNotName << 1) ^ (cidOrNotName >> 31));
            v32(index);
            ref(refStruct, false);
            n++;
        }

        @Override
        public void natSmall(long unsignedValue) {
            need(12);
            boolean late = specials != null && specials.natLiteralRank() == Integer.MAX_VALUE;
            if (late) {
                unresolved = true;
            }
            buf[pos++] = (byte) (late ? Kind.NATLIT | T_BIT1 : Kind.NATLIT);
            long v = unsignedValue;
            while ((v & ~0x7FL) != 0) {
                buf[pos++] = (byte) (v | 0x80);
                v >>>= 7;
            }
            buf[pos++] = (byte) v;
            n++;
        }

        @Override
        public void natBig(int litIndex) {
            if (lits != null && lits.natLimbCount(litIndex) == 1) {
                natSmall(lits.natLimb(litIndex, 0));
                return;
            }
            need(8);
            boolean late = specials != null && specials.natLiteralRank() == Integer.MAX_VALUE;
            if (late) {
                unresolved = true;
            }
            buf[pos++] = (byte) (Kind.NATLIT | T_BIT0 | (late ? T_BIT1 : 0));
            v32(litIndex);
            n++;
        }

        @Override
        public void str(int litIndex) {
            need(8);
            boolean late = specials != null && specials.stringLiteralRank() == Integer.MAX_VALUE;
            if (late) {
                unresolved = true;
            }
            buf[pos++] = (byte) (late ? Kind.STRLIT | T_BIT1 : Kind.STRLIT);
            v32(litIndex);
            n++;
        }

        @Override
        public int size() {
            return n;
        }

        @Override
        public int finish(int rootLocal) {
            if (released) {
                throw new IllegalStateException("the loader state of the proof blocks was released: no further block");
            }
            if (rootLocal < -1 || rootLocal >= n) {
                throw new IllegalArgumentException("root record " + rootLocal + " of a block with " + n);
            }
            int len = pos;
            int chunk;
            int at;
            if (len > CHUNK) {
                chunk = addChunk(Arrays.copyOf(buf, len));
                at = 0;
                fill = 0;
                fillCap = 0;
            } else {
                if (chunkCount == 0 || fill + len > fillCap) {
                    int cap = chunkCount == 0 && len <= FIRST_CHUNK ? FIRST_CHUNK : CHUNK;
                    addChunk(new byte[cap]);
                    fill = 0;
                    fillCap = cap;
                }
                chunk = chunkCount - 1;
                at = fill;
                System.arraycopy(buf, 0, chunks[chunk], at, len);
                fill += len;
            }
            int block = count;
            int o = block * ROW;
            if (o + ROW > rows.length) {
                int[] bigger = Arrays.copyOf(rows, rows.length * 2);
                rows = bigger;
                rowsPub = bigger;
            }
            payloadBytes += len;
            rows[o] = chunk;
            rows[o + 1] = at;
            rows[o + 2] = len;
            rows[o + 3] = n;
            rows[o + 4] = rootLocal;
            rows[o + 5] = records;
            int nm = (n + (1 << SPARSE_SHIFT) - 1) >>> SPARSE_SHIFT;
            if ((long) sparseUsed + nm > sparse.length) {
                long cap = Math.max((long) sparse.length * 2, (long) sparseUsed + nm);
                if (cap > Integer.MAX_VALUE - 15) {
                    throw new StoreLimitException("proof block index exceeds 2^31 ints");
                }
                sparse = Arrays.copyOf(sparse, (int) cap);
            }
            System.arraycopy(marks, 0, sparse, sparseUsed, nm);
            rows[o + 6] = sparseUsed;
            rows[o + 7] = unresolved ? 1 : 0;
            unresolved = false;
            sparseUsed += nm;
            if ((long) records + n > Integer.MAX_VALUE) {
                throw new StoreLimitException("more than 2^31 - 1 block records");
            }
            // coarse index: this block holds every multiple of 2^COARSE_SHIFT in [records, records + n)
            int cm = (records + (1 << COARSE_SHIFT) - 1) >>> COARSE_SHIFT;
            int cEnd = (int) (((long) records + n + (1 << COARSE_SHIFT) - 1) >>> COARSE_SHIFT);
            if (cEnd > coarse.length) {
                coarse = Arrays.copyOf(coarse, Math.max(coarse.length * 2, cEnd));
            }
            while (cm < cEnd) {
                coarse[cm++] = block;
            }
            if (cEnd > coarseUsed) {
                coarseUsed = cEnd;
            }
            records += n;
            count = block + 1;
            countPub = count;
            pos = 0;
            n = 0;
            return block;
        }

        private int addChunk(byte[] chunk) {
            if (chunkCount == chunks.length) {
                byte[][] bigger = Arrays.copyOf(chunks, chunkCount * 2);
                chunks = bigger;
                chunksPub = bigger;
            }
            chunks[chunkCount] = chunk;
            return chunkCount++;
        }

        @Override
        public void abort() {
            unresolved = false;
            pos = 0;
            n = 0;
        }
    }
}
