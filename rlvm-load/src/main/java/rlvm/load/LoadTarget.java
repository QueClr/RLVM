package rlvm.load;

/**
 * Everything the {@link Loader} writes, as one narrow interface: the loader-side view of
 * {@code rlvm.store} (names, literals, shared term nodes, proof blocks, declaration rows). The
 * production implementation is {@link StoreTarget} over {@code rlvm.store.Store}; the loader's own
 * tests use an in-memory implementation, so that the segment logic is testable without the store.
 *
 * <p>All ids are STORE ids: name ids, level and list ids of the level store, shared term ids
 * (positive, export region), constant ids. One thread (the loader thread) calls everything.
 *
 * <p>Protocol per declaration unit:
 * <pre>
 * beginUnit(kind)                       opens the unit
 * reserve(kind, name, lparams) ...      claims the name of every constant of the unit: from now on a
 *                                       CONST/PROJ node written with that name binds to the new cid
 * shared nodes, at most one block       children first; the block is closed before complete()
 * complete(cid, type, value, hint) ...  fills the rows
 * unitClaims(blob, len)                 inductive units only
 * endUnit()
 * </pre>
 * Shared nodes and block records of one unit interleave (the segment is written in one forward pass);
 * {@link #promote} is never called while a block is open.
 */
public interface LoadTarget {

    /**
     * A hint, never required: shared term {@code sharedId} is about to be named as a child of a
     * shared node of the unit being written. A target whose node writer reads the child's node may
     * start loading it now. The default does nothing.
     */
    default void touch(int sharedId) {
    }

    /** Node flag: two or more referrers inside the declaration's segment (RECONCILIATION section 3). */
    int F_SHARED = 1 << 13;
    /** Node flag: body of a binder, or a root of a declaration. */
    int F_BODY = 1 << 14;
    /** Node flag: exactly one referrer, whose free-variable mask is a strict superset of the node's. */
    int F_INVARIANT = 1 << 15;

    // ---- names -----------------------------------------------------------------------------------

    int nameAnonymous();

    int nameStr(int prefix, byte[] utf8, int off, int len);

    /** Numeric component below 2^64, as an unsigned long. */
    int nameNum(int prefix, long unsignedValue);

    /** Numeric component of 2^64 or more, as canonical decimal digits. */
    int nameNumBig(int prefix, byte[] digits, int off, int len);

    // ---- literals --------------------------------------------------------------------------------

    /** A natural number of 2^64 or more as little-endian limbs without a leading zero limb; returns its literal index. */
    int addNat(long[] limbs, int from, int len);

    /** A string literal (well-formed UTF-8); returns its literal index. */
    int addString(byte[] utf8, int off, int len);

    // ---- shared term nodes; each returns the new shared id; flags = F_SHARED | F_BODY bits -----------

    int bvar(int index, int flags);

    int sort(int level, int flags);

    /** A constant reference by name; the target binds it to the constant currently declared under {@code name}. */
    int constant(int name, int levelList, int flags);

    int app(int fn, int arg, int flags);

    int lam(int domain, int body, int flags);

    int pi(int domain, int body, int flags);

    int let(int type, int value, int body, int flags);

    int proj(int structName, int fieldIndex, int struct, int flags);

    int natSmall(long unsignedValue, int flags);

    int natBig(int litIndex, int flags);

    int str(int litIndex, int flags);

    /**
     * {@code child} (a shared node of the unit being written, of any kind) has exactly one referrer, the
     * shared node {@code parent}, which uses it in a non-binding position (so both live in the same de
     * Bruijn frame). The target sets {@link #F_INVARIANT} on {@code child} if its free-variable mask
     * differs from the parent's.
     */
    void singleReferrer(int child, int parent);

    // ---- one proof block at a time; refs are encoded with blockLocal / blockShared ---------------------

    /** Reference to an earlier record of the open block, {@code backDistance >= 1}. */
    static int blockLocal(int backDistance) {
        return backDistance << 1;
    }

    /** Reference to a shared term. */
    static int blockShared(int termId) {
        return (termId << 1) | 1;
    }

    /** Opens a block; returns the global number its first record will have. */
    int blockBegin();

    void blockBVar(int index);

    void blockSort(int level);

    /** The target resolves {@code name} to a constant id (or keeps it unbound) as {@link #constant} does. */
    void blockConstant(int name, int levelList);

    void blockApp(int refFn, int refArg);

    void blockLam(int refDomain, int refBody);

    void blockPi(int refDomain, int refBody);

    void blockLet(int refType, int refValue, int refBody);

    void blockProj(int structName, int fieldIndex, int refStruct);

    void blockNatSmall(long unsignedValue);

    void blockNatBig(int litIndex);

    void blockStr(int litIndex);

    /**
     * {@code count} APP / LAM / PI / LET records of the open block, four ints each from
     * {@code recs[off]} on: the kind in the low four bits of the first (its other bits are ignored),
     * then the references of {@link #blockApp}, {@link #blockLam}, {@link #blockPi} or
     * {@link #blockLet} in their order (the fourth int of a record with two references is ignored).
     * Equivalent to those {@code count} calls, which is what the default makes; a target may take the
     * whole stretch in one loop ({@code rlvm.store.ProofBlocks.Writer.records}).
     */
    default void blockRecords(int[] recs, int off, int count) {
        for (int i = 0, o = off; i < count; i++, o += 4) {
            switch (recs[o] & 0xF) {
                case rlvm.store.Kind.APP -> blockApp(recs[o + 1], recs[o + 2]);
                case rlvm.store.Kind.LAM -> blockLam(recs[o + 1], recs[o + 2]);
                case rlvm.store.Kind.PI -> blockPi(recs[o + 1], recs[o + 2]);
                case rlvm.store.Kind.LET -> blockLet(recs[o + 1], recs[o + 2], recs[o + 3]);
                default -> throw new IllegalArgumentException("not an APP / LAM / PI / LET record: kind " + (recs[o] & 0xF));
            }
        }
    }

    /**
     * {@code count} APP / LAM / PI / LET records of the open block in the packed form of
     * {@link Loader#RUN_FORMAT} (see {@code rlvm.store.ProofBlocks.Writer.run}): the record bytes
     * {@code packed[off .. off+len)} - a kind byte and the references that are already encoded
     * ({@link #blockLocal} or {@link #blockShared} as LEB128) -, one length byte per record, and the
     * open references {@code open[openOff ..]}, two ints each: the offset in the record bytes at
     * which the reference belongs (bit 31: body of a binder), and its value: {@code > 0} a shared
     * term id, {@code < 0} {@code -(i + 1)} for record {@code i} of the open block. Equivalent to the
     * {@code count} calls of {@link #blockApp}, {@link #blockLam}, {@link #blockPi}, {@link #blockLet},
     * which is what the default makes.
     *
     * @param blockIndex number of records of the open block before the run
     * @param maxEarly the largest shared term id encoded in the record bytes (0: none)
     */
    default void blockRun(int blockIndex, byte[] packed, int off, int len, byte[] lengths, int lengthsOff, int count,
            int[] open, int openOff, int openCount, int maxEarly) {
        int p = off;
        int e = openOff;
        final int openEnd = openOff + 2 * openCount;
        int[] ref = new int[3];
        for (int i = 0; i < count; i++) {
            int recordEnd = p + (lengths[lengthsOff + i] & 0xFF);
            int kind = packed[p++];
            int children = kind == rlvm.store.Kind.LET ? 3 : 2;
            for (int c = 0; c < children; c++) {
                if (e < openEnd && (open[e] & 0x7FFF_FFFF) == p - off) {
                    int v = open[e + 1];
                    e += 2;
                    ref[c] = v > 0 ? blockShared(v) : blockLocal(blockIndex + i + v + 1);
                } else {
                    int v = 0;
                    int shift = 0;
                    int b;
                    do {
                        b = packed[p++];
                        v |= (b & 0x7F) << shift;
                        shift += 7;
                    } while (b < 0);
                    ref[c] = v;
                }
            }
            if (p != recordEnd) {
                throw new IllegalArgumentException("corrupt packed run");
            }
            switch (kind) {
                case rlvm.store.Kind.APP -> blockApp(ref[0], ref[1]);
                case rlvm.store.Kind.LAM -> blockLam(ref[0], ref[1]);
                case rlvm.store.Kind.PI -> blockPi(ref[0], ref[1]);
                case rlvm.store.Kind.LET -> blockLet(ref[0], ref[1], ref[2]);
                default -> throw new IllegalArgumentException("corrupt packed run: kind " + kind);
            }
        }
        if (p != off + len || e != openEnd) {
            throw new IllegalArgumentException("corrupt packed run");
        }
    }

    /**
     * Closes the open block and returns its index.
     *
     * @param rootLocal index (0-based, in the block) of the record that is the owner's value, or -1 for
     *     an ownerless block
     */
    int blockEnd(int rootLocal);

    /** Copies global block record {@code record} and what it reaches in its block into the shared region; returns the shared id. */
    int promote(int record);

    // ---- declarations ----------------------------------------------------------------------------

    /** Opens a unit of the given record kind (a {@code DeclKind} constant kind; INDUCT for a block); returns its index. */
    int beginUnit(int recordKind);

    /** Claims {@code name} for a new constant of the open unit; returns its cid, or 0 if the name is already declared. */
    int reserve(int kind, int name, int lparams);

    /**
     * Fills the row of a reserved constant.
     *
     * @param value shared term id, {@code -(block + 1)}, or 0
     * @param hint the {@code DeclKind} hint encoding for definitions, else 0
     */
    void complete(int cid, int type, int value, int hint);

    /** The claims of an inductive unit ({@link BlockClaims}); the array is the loader's scratch. */
    void unitClaims(int[] blob, int len);

    void endUnit();

    /**
     * An unsafe or partial declaration record that is dropped: only the name is remembered. False if
     * the name is already declared (the caller rejects the file).
     */
    boolean dropUnsafe(int name);

    /**
     * A further name of the record dropped by the preceding {@link #dropUnsafe} (the other members of
     * an unsafe inductive block). False if the name is already declared.
     */
    boolean dropName(int name);

    /** Makes every unit written so far visible to the other threads. */
    void publish();

    /**
     * Number of complete units the target holds (admitted declarations and dropped records), or -1 if
     * it does not count them. The loader cross-checks its own count before every publication.
     */
    default int unitCount() {
        return -1;
    }

    /** End of input without an error: final publication. */
    void finish();

    /**
     * Whether this target wants the loader's sharing flags (the {@code flags} arguments and
     * {@link #singleReferrer}). A target that derives them itself answers false; the loader then
     * skips the referrer counting and passes 0.
     */
    boolean wantsSharingFlags();

    // ---- statistics ------------------------------------------------------------------------------

    /** Nodes of the shared (export) region written so far. */
    long sharedNodes();

    /** Bytes of all proof blocks written so far. */
    long blockBytes();

    /** CONST / PROJ shared nodes written so far (promoted copies not included) that are unbound: no constant of that name, or the wrong number of levels. */
    long unresolvedRefs();

    /** CONST / PROJ block records whose name had no constant when the record was written (a later promotion binds again). */
    long unresolvedBlockRefs();

    /** Diagnostics only: a printable form of a name. */
    String nameToString(int name);
}
