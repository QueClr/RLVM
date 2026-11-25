package rlvm.store;

import java.util.Arrays;

/**
 * The shared term regions: append-only, progressively frozen.
 *
 * <p>Writers: exactly one thread per region (the loader for the export region, the environment thread
 * for the generated region), through {@link StoreBuilder} / {@link #appendGenerated}. Readers: any
 * thread, but only ids it obtained from a published declaration ({@link Store#loadedUnits()},
 * {@link Store#envUnits()}); the volatile frontier write is the happens-before edge. A node of a
 * published unit is never modified. (While a unit is still being written, the writer sets the sharing
 * flags of {@link Node} in the headers of that unit's own nodes; nothing else is ever rewritten.)
 *
 * <p>Each region is one flat {@code int[]}, sized from the input length before filling. If a region
 * must grow it is reallocated (1.25x) and copied by its writer; the new array is published through a
 * volatile field before any id that needs it, and the old array stays valid for every id published
 * before. A reader therefore re-reads {@link #exportArray()} once per unit, never per access.
 *
 * <p>Sharing flags (*agentic-note*: design-reconciliation-document, section 3): the writer counts, per node of the unit being
 * written (ids from {@link #segmentStart()} on), the references made by later nodes and by proof-block
 * records of the same unit, and sets {@link Node#F_SHARED} at the second reference, {@link Node#F_BODY}
 * on binder bodies and declaration roots, {@link Node#F_INVARIANT} when the first referrer has a
 * different free-variable mask. Nodes of earlier units are never touched: the machine treats every
 * term below {@code DeclTable.unitFirstTerm(unit)} as shared.
 *
 * <p>The generated region holds the terms of constants that admission generates itself (a recursor
 * without an export record, the quotient constants). It is written by the environment thread once
 * the load is complete ({@link GeneratedBuilder}), nodes are appended and never modified, and it is
 * published like everything of the environment pass, by {@link Store#publishEnv}. A reader picks up
 * the current array with {@link Terms#refresh()} at the start of a unit.
 */
public final class TermStore {

    private static final int[] NO_INTS = new int[0];
    /** Largest number of ints in a region: 2^28 - 1 nodes plus the unused node 0. */
    private static final long MAX_INTS = (long) (Node.INDEX_MASK + 1) << 3;

    /** Writer's view of the export region; equals {@link #exPub} except during a growth. */
    private int[] ex;
    private volatile int[] exPub;
    /** Next export id. */
    private int exEnd = 1;
    /** First id of the unit being written. */
    private int segStart = 1;
    /** Smallest export id whose {@code depMax} is {@link Node#DEP_UNRESOLVED}; MAX_VALUE while there is none. */
    private int firstUnresolved = Integer.MAX_VALUE;
    /** Sink of {@link #touch}. */
    private int touched;
    /** Reference counts (saturating at 2) of the nodes {@code segStart ..}. */
    private byte[] refs = new byte[1024];
    private NodeCtx ctx;
    private LitPool lits;

    /** Writer's view of the generated region; node index i (id {@code GEN_BASE | i}) at {@code i << 3}. */
    private int[] gen = NO_INTS;
    private volatile int[] genPub = NO_INTS;
    /** Number of generated nodes. */
    private int genEnd;
    // scratch of appendGenerated: overlay index -> generated id (open addressing), and the walk stack
    private int[] copyKey = new int[64];
    private int[] copyVal = new int[64];
    private int copyCount;
    private int[] copyStack = new int[64];

    /** @param expectedNodes initial capacity of the export region (*agentic-note*: design-architecture-document, 2.2: input bytes / 144) */
    public TermStore(int expectedNodes) {
        long ints = ((long) Math.max(expectedNodes, 15) + 1) << 3;
        if (ints > MAX_INTS) {
            ints = MAX_INTS;
        }
        // An int[] cannot have 2^31 elements: the last node of a full region is given up.
        this.ex = new int[(int) Math.min(ints, Integer.MAX_VALUE - 15)];
        this.exPub = ex;
    }

    void bind(NodeCtx ctx) {
        this.ctx = ctx;
    }

    void bind(NodeCtx ctx, LitPool lits) {
        this.ctx = ctx;
        this.lits = lits;
    }

    NodeCtx ctx() {
        return ctx;
    }

    /** The export region; node {@code id} occupies {@code [wordOf(id), wordOf(id) + 8)}. Volatile read. */
    public int[] exportArray() {
        return exPub;
    }

    /** The generated region (empty array if nothing was generated). Volatile read. */
    public int[] generatedArray() {
        return genPub;
    }

    /** One past the last node id of the export region (writer-side counter; readers use the frontiers). */
    public int exportEnd() {
        return exEnd;
    }

    /** One past the last node id of the generated region ({@link Node#GEN_BASE} when empty). */
    public int generatedEnd() {
        return Node.GEN_BASE + genEnd;
    }

    /** First export id of the unit being written (writer thread). */
    public int segmentStart() {
        return segStart;
    }

    /**
     * Writer thread: every export node with {@code depMax == DEP_UNRESOLVED} has an id at or above
     * this (MAX_VALUE while no such node exists). Lets the block writer skip the load of a
     * referenced node when it only wants to know whether that node is unresolved.
     */
    int firstUnresolved() {
        return firstUnresolved;
    }

    /** Bytes allocated for both regions. */
    public long bytes() {
        return 4L * exPub.length + 4L * genPub.length;
    }

    /** Bytes of the export region in use: 32 per node, node 0 included. Writer-side counter. */
    public long usedBytes() {
        return 32L * exEnd;
    }

    /** Bytes of the referrer counters of the open unit (loader state). */
    long loaderBytes() {
        return refs.length;
    }

    /** Writer: the array to write into. */
    int[] writerArray() {
        return ex;
    }

    /** Reserves the next export id and returns it; the caller writes its 8 words into {@link #writerArray()}. */
    int alloc() {
        int id = exEnd;
        int need = (id + 1) << 3;
        if (need > ex.length || need < 0) {
            grow(id);
        }
        int k = id - segStart;
        if (k >= refs.length) {
            refs = Arrays.copyOf(refs, refs.length * 2);
        }
        exEnd = id + 1;
        return id;
    }

    private void grow(int id) {
        if (id >= Node.INDEX_MASK) {
            throw new StoreLimitException("more than 2^28 - 2 shared term nodes");
        }
        long want = Math.max((long) ex.length + (ex.length >> 2), ((long) id + 2) << 3);
        want = Math.min(want, Math.min(MAX_INTS, Integer.MAX_VALUE - 15));
        if (want < ((long) id + 1) << 3) {
            throw new StoreLimitException("export region exceeds the largest int[]");
        }
        int[] bigger = Arrays.copyOf(ex, (int) want);
        ex = bigger;
        exPub = bigger;
    }

    /**
     * Appends a node to the export region and returns its id. {@code flags}, looseRange, fvMask,
     * depMax and lpSet are derived here from the children (which must already exist), following
     * *agentic-note*: design-architecture-document, section 2.1. Loader thread only.
     *
     * <p>Payload by kind as in {@link Kind}; for CONST {@code a} is the constant id or {@code ~nameId}
     * and {@code c} the name id, for PROJ {@code c} is the constant id or {@code ~nameId}; a small
     * NATLIT has its value in {@code a} (low) and {@code b} (high), a big one ({@code extraFlags}
     * containing {@link Node#F_NAT_BIG}) its pool index in {@code a}. The sharing counters of the
     * children are updated.
     */
    int appendExport(int kind, int a, int b, int c) {
        return appendExport(kind, a, b, c, 0);
    }

    int appendExport(int kind, int a, int b, int c, int extraFlags) {
        int id = alloc();
        int[] d = ex;
        int w = id << 3;
        NodeCtx cx = ctx;
        switch (kind) {
            case Kind.BVAR:
                NodeCtx.writeBVar(d, w, a, extraFlags);
                break;
            case Kind.SORT:
                cx.writeSort(d, w, a, extraFlags);
                break;
            case Kind.CONST:
                cx.writeConst(d, w, a, b, c, a > 0 && cx.arityMismatch(a, b), extraFlags);
                break;
            case Kind.APP:
                check(a, id);
                check(b, id);
                cx.writeApp(d, w, a, b, d, d, extraFlags);
                ref(a, w, false);
                ref(b, w, false);
                break;
            case Kind.LAM:
            case Kind.PI:
                check(a, id);
                check(b, id);
                cx.writeBinder(d, w, kind, a, b, d, d, extraFlags);
                ref(a, w, false);
                ref(b, w, true);
                break;
            case Kind.LET:
                check(a, id);
                check(b, id);
                check(c, id);
                cx.writeLet(d, w, a, b, c, d, d, d, extraFlags);
                ref(a, w, false);
                ref(b, w, false);
                ref(c, w, true);
                break;
            case Kind.PROJ:
                check(a, id);
                cx.writeProj(d, w, c, b, a, d, extraFlags);
                ref(a, w, false);
                break;
            case Kind.NATLIT:
                NodeCtx.writeLit(d, w, Kind.NATLIT, a, (extraFlags & Node.F_NAT_BIG) != 0 ? 0 : b, extraFlags,
                        cx.specials.natLiteralRank());
                break;
            case Kind.STRLIT:
                NodeCtx.writeLit(d, w, Kind.STRLIT, a, b, extraFlags, cx.specials.stringLiteralRank());
                break;
            default:
                exEnd = id;
                throw new IllegalArgumentException("kind " + kind + " cannot be stored in the export region");
        }
        if (d[w + Node.W_DEPMAX] == Node.DEP_UNRESOLVED && id < firstUnresolved) {
            firstUnresolved = id;
        }
        return id;
    }

    private static void check(int child, int self) {
        if (child <= 0 || child >= self) {
            throw new IllegalArgumentException("child " + child + " of shared node " + self
                    + " is not an earlier export term");
        }
    }

    /**
     * Records one reference to node {@code n} from the node at word offset {@code pw} (its mask is
     * already written). Nodes of earlier units are left alone.
     */
    private void ref(int n, int pw, boolean bodyPosition) {
        int k = n - segStart;
        if (k < 0) {
            return;
        }
        int[] d = ex;
        int nw = n << 3;
        int h = d[nw];
        byte c = refs[k];
        if (c == 0) {
            refs[k] = 1;
            if (bodyPosition) {
                h |= Node.F_BODY;
            } else if (d[nw + 4] != d[pw + 4] || d[nw + 5] != d[pw + 5]) {
                h |= Node.F_INVARIANT;
            }
        } else {
            refs[k] = 2;
            h |= bodyPosition ? Node.F_SHARED | Node.F_BODY : Node.F_SHARED;
        }
        d[nw] = h;
    }

    /** A reference from a proof-block record of the unit being written to shared node {@code n}. */
    void refFromBlock(int n, boolean bodyPosition) {
        int k = n - segStart;
        if (k < 0 || n >= exEnd) {
            return;
        }
        int nw = n << 3;
        int h = ex[nw];
        if (refs[k] == 0) {
            refs[k] = 1;
        } else {
            refs[k] = 2;
            h |= Node.F_SHARED;
        }
        if (bodyPosition) {
            h |= Node.F_BODY;
        }
        ex[nw] = h;
    }

    /**
     * Writer thread: reads the first and the last word of export node {@code n} (a node may straddle
     * two cache lines) and keeps the sum, so that the loads are really made. See
     * {@link StoreBuilder#touch}.
     */
    void touch(int n) {
        if (n > 0 && n < exEnd) {
            int[] d = ex;
            int w = n << 3;
            touched += d[w] + d[w + Node.W_LPSET];
        }
    }

    /** Marks {@code n} as a declaration root, if it belongs to the unit being written. */
    void markRoot(int n) {
        if (n >= segStart && n < exEnd) {
            ex[n << 3] |= Node.F_BODY;
        }
    }

    /** Ors {@code flags} into the header of a node of the unit being written (promotion). */
    void orFlags(int n, int flags) {
        if (n >= segStart && n < exEnd) {
            ex[n << 3] |= flags;
        }
    }

    /** Closes the unit being written: later nodes belong to the next unit. Returns the new segment start. */
    int endSegment() {
        int used = exEnd - segStart;
        if (used > 0) {
            if (refs.length > (1 << 20) && used < (1 << 16)) {
                refs = new byte[1024];
            } else {
                Arrays.fill(refs, 0, Math.min(used, refs.length), (byte) 0);
            }
        }
        segStart = exEnd;
        return segStart;
    }

    /**
     * Stores {@code value} in the reserved {@code c} word of a published export LAM / PI node: the
     * machine's elaborator keeps a lossy hint there (*agentic-note*: design-isa-document, 3.3). Any thread; a plain racy store that
     * can be lost (for instance while the loader reallocates the region), so the reader must validate
     * what it finds. Nothing in the store reads that word. Ignored for other nodes.
     */
    public void setBinderHint(int exportId, int value) {
        int[] a;
        int w;
        if (exportId > 0 && exportId < Node.GEN_BASE) {
            a = exPub;
            w = exportId << 3;
        } else if (Node.isGenerated(exportId)) {
            a = genPub;
            w = (exportId & Node.INDEX_MASK) << 3;
        } else {
            return;
        }
        if (w + 3 < a.length) {
            int k = a[w] & 0xF;
            if (k == Kind.LAM || k == Kind.PI) {
                a[w + 3] = value;
            }
        }
    }

    /**
     * Copies a closed overlay term (and whatever it reaches in the overlay) into the generated region;
     * returns the shared id ({@code term} itself if it already is a shared term). Environment thread
     * only, after the load is complete; use {@link GeneratedBuilder#term}, which checks that.
     *
     * <p>The copy keeps the sharing of the overlay DAG (one generated node per overlay node reached),
     * is written children first with the derivation every other node gets, and carries
     * {@link Node#F_SHARED} throughout. What a shared term may contain is checked, and the region is
     * left as it was when a check fails ({@link GeneratedTermException}):
     * <ul>
     *   <li>no free variable, and no loose bound variable at the root;
     *   <li>every CONST and PROJ bound to a constant, with the right number of levels;
     *   <li>every level id (SORT) and level-list id (CONST) an id of the run's shared level and list
     *       store ({@link LevelInfo#isSharedLevel}, {@link IntLists#isSharedList}), and every set of universe
     *       parameters that the summaries need obtainable there;
     *   <li>every big natural and string literal an entry of the shared literal pool.
     * </ul>
     * {@code from} is refreshed, so the caller reads the new ids at once.
     */
    public int appendGenerated(Terms from, int overlayTerm) {
        if (!Node.isOverlay(overlayTerm)) {
            boolean export = overlayTerm > 0 && overlayTerm < exEnd;
            boolean generated = Node.isGenerated(overlayTerm) && (overlayTerm & Node.INDEX_MASK) < genEnd;
            if (!export && !generated) {
                throw new IllegalArgumentException("not a term id: " + overlayTerm);
            }
            return overlayTerm;
        }
        if (from.overlay() == null) {
            throw new IllegalArgumentException("the reader has no overlay");
        }
        int start = genEnd;
        if (copyKey.length > (1 << 16)) {
            copyKey = new int[64];
            copyVal = new int[64];
        } else {
            Arrays.fill(copyKey, 0);
        }
        copyCount = 0;
        try {
            int root = copy(from, overlayTerm);
            int[] g = gen;
            int rw = (root & Node.INDEX_MASK) << 3;
            if ((g[rw] >>> 16) != 0) {
                throw new GeneratedTermException("the term has a loose bound variable");
            }
            genPub = gen;
            from.refresh();
            return root;
        } catch (RuntimeException e) {
            // nothing refers to the nodes written so far: forget them
            Arrays.fill(gen, start << 3, Math.min(gen.length, genEnd << 3), 0);
            genEnd = start;
            if (e instanceof GeneratedTermException) {
                throw e;
            }
            if (e instanceof IllegalStateException || e instanceof StoreLimitException) {
                // a frozen list store refused a new set of universe parameters, or a limit was hit
                throw new GeneratedTermException("the term cannot be shared: " + e.getMessage(), e);
            }
            throw e;
        }
    }

    /** Post-order copy of the overlay part of the DAG below {@code root}; returns the generated id of the root. */
    private int copy(Terms from, int root) {
        int[] st = copyStack;
        int sp = 0;
        st[sp++] = root;
        NodeCtx cx = ctx;
        while (sp > 0) {
            int t = st[sp - 1];
            if (copied(t) != 0) {
                sp--;
                continue;
            }
            int[] r = from.regionOf(t);
            int w = Node.wordOf(t);
            int h = r[w];
            int k = h & 0xF;
            int a = r[w + 1];
            int b = r[w + 2];
            int c = r[w + 3];
            int c1 = 0;
            int c2 = 0;
            int c3 = 0;
            switch (k) {
                case Kind.APP:
                case Kind.LAM:
                case Kind.PI:
                    c1 = a;
                    c2 = b;
                    break;
                case Kind.LET:
                    c1 = a;
                    c2 = b;
                    c3 = c;
                    break;
                case Kind.PROJ:
                    c1 = a;
                    break;
                default:
                    break;
            }
            int m1 = c1 == 0 ? 0 : mapped(c1);
            int m2 = c2 == 0 ? 0 : mapped(c2);
            int m3 = c3 == 0 ? 0 : mapped(c3);
            if ((c1 != 0 && m1 == 0) || (c2 != 0 && m2 == 0) || (c3 != 0 && m3 == 0)) {
                if (sp + 3 > st.length) {
                    st = copyStack = Arrays.copyOf(st, st.length * 2);
                }
                if (c3 != 0 && m3 == 0) {
                    st[sp++] = c3;
                }
                if (c2 != 0 && m2 == 0) {
                    st[sp++] = c2;
                }
                if (c1 != 0 && m1 == 0) {
                    st[sp++] = c1;
                }
                continue;
            }
            int idx = genAlloc();
            int[] d = gen;
            int dw = idx << 3;
            int flags = Node.F_SHARED;
            switch (k) {
                case Kind.BVAR:
                    NodeCtx.writeBVar(d, dw, a, flags);
                    break;
                case Kind.SORT:
                    if (cx.levels() == null || !cx.levels().isSharedLevel(a)) {
                        throw new GeneratedTermException("level " + a + " of a sort is not a shared level");
                    }
                    cx.writeSort(d, dw, a, flags);
                    break;
                case Kind.CONST:
                    if (a <= 0) {
                        throw new GeneratedTermException("a constant reference is not bound");
                    }
                    if (!cx.listIsShared(b)) {
                        throw new GeneratedTermException("level list " + b + " of a constant is not a shared list");
                    }
                    if (cx.arityMismatch(a, b)) {
                        throw new GeneratedTermException("constant " + a + " has the wrong number of levels");
                    }
                    cx.writeConst(d, dw, a, b, c, false, flags);
                    break;
                case Kind.APP:
                    cx.writeApp(d, dw, m1, m2, sharedArray(m1), sharedArray(m2), flags);
                    break;
                case Kind.LAM:
                case Kind.PI:
                    cx.writeBinder(d, dw, k, m1, m2, sharedArray(m1), sharedArray(m2), flags);
                    break;
                case Kind.LET:
                    cx.writeLet(d, dw, m1, m2, m3, sharedArray(m1), sharedArray(m2), sharedArray(m3), flags);
                    break;
                case Kind.PROJ:
                    if (c <= 0) {
                        throw new GeneratedTermException("a projection's structure is not bound");
                    }
                    cx.writeProj(d, dw, c, b, m1, sharedArray(m1), flags);
                    break;
                case Kind.NATLIT:
                    if ((h & Node.F_NAT_BIG) != 0) {
                        if (a <= 0 || lits == null || !lits.isPublished(a, true)) {
                            throw new GeneratedTermException("a natural literal is not in the shared literal pool");
                        }
                        NodeCtx.writeLit(d, dw, Kind.NATLIT, a, 0, flags | Node.F_NAT_BIG, cx.specials.natLiteralRank());
                    } else {
                        NodeCtx.writeLit(d, dw, Kind.NATLIT, a, b, flags, cx.specials.natLiteralRank());
                    }
                    break;
                case Kind.STRLIT:
                    if (a <= 0 || lits == null || !lits.isPublished(a, false)) {
                        throw new GeneratedTermException("a string literal is not in the shared literal pool");
                    }
                    NodeCtx.writeLit(d, dw, Kind.STRLIT, a, b, flags, cx.specials.stringLiteralRank());
                    break;
                case Kind.FVAR:
                    throw new GeneratedTermException("the term has a free variable");
                default:
                    throw new GeneratedTermException("node kind " + k + " cannot be shared");
            }
            if ((d[dw] & Node.F_UNRESOLVED) != 0) {
                throw new GeneratedTermException("the term has an unresolved reference");
            }
            putCopied(t, Node.GEN_BASE | idx);
            sp--;
        }
        return copied(root);
    }

    /** The shared id that stands for child {@code id} in the copy: itself if shared, its copy if made, else 0. */
    private int mapped(int id) {
        if (!Node.isOverlay(id)) {
            if (id <= 0) {
                throw new GeneratedTermException("a child is not a term");
            }
            return id;
        }
        return copied(id);
    }

    /** The writer's array of the region that holds shared node {@code id}. */
    private int[] sharedArray(int id) {
        return id < Node.GEN_BASE ? ex : gen;
    }

    private int genAlloc() {
        int idx = genEnd;
        if (idx >= Node.INDEX_MASK) {
            throw new StoreLimitException("more than 2^28 - 1 generated term nodes");
        }
        if (((long) idx + 1) << 3 > gen.length) {
            long want = Math.max(Math.max((long) gen.length * 2, 1 << 10), ((long) idx + 1) << 3);
            want = Math.min(want, Integer.MAX_VALUE - 15);
            // the old array stays valid for every id published before; the new one is published by
            // appendGenerated when the copy is complete
            gen = Arrays.copyOf(gen, (int) want);
        }
        genEnd = idx + 1;
        return idx;
    }

    private int copied(int overlayId) {
        int[] ks = copyKey;
        int mask = ks.length - 1;
        int i = (overlayId * 0x9E3779B1) >>> 9 & mask;
        for (;;) {
            int k = ks[i];
            if (k == 0) {
                return 0;
            }
            if (k == overlayId) {
                return copyVal[i];
            }
            i = (i + 1) & mask;
        }
    }

    private void putCopied(int overlayId, int generatedId) {
        if ((copyCount + 1) * 2 > copyKey.length) {
            int[] ok = copyKey;
            int[] ov = copyVal;
            copyKey = new int[ok.length * 2];
            copyVal = new int[ok.length * 2];
            int mask = copyKey.length - 1;
            for (int j = 0; j < ok.length; j++) {
                if (ok[j] != 0) {
                    int i = (ok[j] * 0x9E3779B1) >>> 9 & mask;
                    while (copyKey[i] != 0) {
                        i = (i + 1) & mask;
                    }
                    copyKey[i] = ok[j];
                    copyVal[i] = ov[j];
                }
            }
        }
        int mask = copyKey.length - 1;
        int i = (overlayId * 0x9E3779B1) >>> 9 & mask;
        while (copyKey[i] != 0) {
            i = (i + 1) & mask;
        }
        copyKey[i] = overlayId;
        copyVal[i] = generatedId;
        copyCount++;
    }
}
