package rlvm.export;

/**
 * Translation of the ids of one id space of the file (names, levels or expressions) to sink ids,
 * enforcing the two rules of the official reader: an id must be bound before it is used, and it is
 * bound once.
 *
 * <p>Three representations, switched automatically and never back:
 * <ol>
 *   <li><b>dense</b> (every file written by lean4export, as long as the sink numbers consecutively):
 *       the ids {@code 0 .. limit-1} are bound, in order, to {@code id + delta}. No table; a reference
 *       costs one comparison and one addition.</li>
 *   <li><b>table</b>: {@code table[id]} for ids below a bound that grows with the number of bindings;</li>
 *   <li><b>hash</b>: an open-addressing map for the remaining (far-away) ids.</li>
 * </ol>
 * Ids are arbitrary naturals in the official reader. An id below 2^63 is its own key here; a larger
 * one is represented by a key of {@link #BIG_KEY_MAX} or below that the caller ({@link Linker})
 * assigns per distinct number, and lives in the hash map like any other far-away id. The key -1 is
 * not an id (it is the parser's "2^63 or more" without the digits).
 */
final class IdSpace {
    static final int UNDEF = Integer.MIN_VALUE;
    /** Largest key that stands for an id of 2^63 or more (keys of such ids are this or smaller). */
    static final long BIG_KEY_MAX = -2;

    private final String what;

    /** Dense mode: number of bound ids ({@code 0 .. limit-1}); 0 in the other modes. */
    long limit;
    /** Dense mode: sink id minus file id. */
    int delta;
    /** Whether dense mode is active. */
    boolean dense = true;
    /** Dense mode with nothing bound yet and no delta known (expressions before the first record). */
    private boolean deltaOpen;

    private int[] table;
    private long[] hashKeys;
    private int[] hashVals;
    private int hashCount;

    /** Number of bindings, including an implicit id 0. */
    long count;
    /** Largest bound id, -1 if none. */
    long maxId = -1;
    /** Whether the ids were dense and in order up to now (independent of what the sink returned). */
    boolean inOrder = true;

    /** @param what "Name", "Level" or "Expr", as in the official reader's messages */
    IdSpace(String what) {
        this.what = what;
        this.deltaOpen = true;
    }

    /** Binds the implicit id 0 (anonymous name, level zero). */
    void bindImplicitZero(int sinkId) {
        delta = sinkId;
        deltaOpen = false;
        limit = 1;
        count = 1;
        maxId = 0;
    }

    /** Sink id of the id with key {@code id}. */
    int ref(long id) throws ExportException {
        if (id >= 0 && id < limit) {
            return (int) id + delta;
        }
        return refSlow(id);
    }

    private int refSlow(long id) throws ExportException {
        if (!dense && id != -1) {
            int v = lookup(id);
            if (v != UNDEF) {
                return v;
            }
        }
        throw ExportException.malformed(what + " not found " + (id < 0 ? "(index of 2^63 or more)" : Long.toString(id)));
    }

    /**
     * Fails if {@code id} is already bound. Called before the sink sees the record, so that a record
     * that rebinds an id never reaches the sink, and so that "bound twice" (malformed) wins over
     * "unsupported" conditions of the same line.
     */
    void fresh(long id) throws ExportException {
        if (id == limit && dense) {
            return;
        }
        requireUnbound(id);
    }

    private void requireUnbound(long id) throws ExportException {
        if (id == -1) {
            return; // not an id: never bound
        }
        if (dense ? id >= 0 && id < limit : lookup(id) != UNDEF) {
            throw twice(id);
        }
    }

    private ExportException twice(long id) {
        return ExportException.malformed(what + " index " + (id < 0 ? "(of 2^63 or more)" : Long.toString(id))
                + " bound twice");
    }

    /** Binds file id {@code id} to {@code sinkId}. */
    void bind(long id, int sinkId) throws ExportException {
        if (id == limit && dense && sinkId - (int) id == delta) {
            limit = id + 1;
            return;
        }
        bindSlow(id, sinkId);
    }

    private void bindSlow(long id, int sinkId) throws ExportException {
        if (sinkId == UNDEF) {
            throw new IllegalStateException("sink returned the reserved id Integer.MIN_VALUE");
        }
        if (id == -1) {
            throw new IllegalStateException("an id of 2^63 or more needs a key (IdSpace.BIG_KEY_MAX or below)");
        }
        if (dense) {
            if (id == 0 && limit == 0 && deltaOpen) {
                delta = sinkId;
                deltaOpen = false;
                limit = 1;
                return;
            }
            if (id >= 0 && id < limit) {
                throw twice(id);
            }
            leaveDenseMode();
        } else if (lookup(id) != UNDEF) {
            throw twice(id);
        }
        if (id != count) {
            inOrder = false;
        }
        count++;
        if (id < 0) {
            maxId = Long.MAX_VALUE; // 2^63 or more: reported saturated
        } else if (id > maxId) {
            maxId = id;
        }
        // Direct table while the id is "near" (at most a constant factor above the number of bindings).
        if (id >= 0 && id < table.length) {
            table[(int) id] = sinkId;
        } else if (id >= 0 && id < 2 * count + 4096 && id < Integer.MAX_VALUE - 16) {
            growTable(id);
            table[(int) id] = sinkId;
        } else {
            hashPut(id, sinkId);
        }
    }

    private void leaveDenseMode() throws ExportException {
        long n = limit;
        if (n > Integer.MAX_VALUE - 1024) {
            throw ExportException.unsupported("more than 2^31 " + what + " records");
        }
        int cap = (int) Math.min(Integer.MAX_VALUE - 16, Math.max(1024, n + (n >> 1) + 16));
        int[] t = new int[cap];
        int d = delta;
        for (int i = 0; i < (int) n; i++) {
            t[i] = i + d;
        }
        java.util.Arrays.fill(t, (int) n, cap, UNDEF);
        table = t;
        count = n;
        maxId = n - 1;
        dense = false;
        limit = 0;
    }

    private void growTable(long id) {
        int old = table.length;
        int cap = (int) Math.min(Integer.MAX_VALUE - 16, Math.max(id + 1, old + (old >> 1) + 16));
        int[] t = java.util.Arrays.copyOf(table, cap);
        java.util.Arrays.fill(t, old, cap, UNDEF);
        table = t;
    }

    private int lookup(long id) {
        if (id >= 0 && id < table.length) {
            int v = table[(int) id];
            if (v != UNDEF) {
                return v;
            }
        }
        return hashCount == 0 ? UNDEF : hashGet(id);
    }

    private static int slot(long key, int mask) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h >>> 32) & mask;
    }

    private int hashGet(long key) {
        long[] keys = hashKeys;
        int mask = keys.length - 1;
        int i = slot(key, mask);
        while (true) {
            long k = keys[i];
            if (k == key) {
                return hashVals[i];
            }
            if (k == -1) {
                return UNDEF;
            }
            i = (i + 1) & mask;
        }
    }

    private void hashPut(long key, int val) {
        if (hashKeys == null) {
            hashKeys = new long[64];
            hashVals = new int[64];
            java.util.Arrays.fill(hashKeys, -1);
        } else if ((hashCount + 1) * 2 > hashKeys.length) {
            long[] oldK = hashKeys;
            int[] oldV = hashVals;
            hashKeys = new long[oldK.length * 2];
            hashVals = new int[oldK.length * 2];
            java.util.Arrays.fill(hashKeys, -1);
            for (int j = 0; j < oldK.length; j++) {
                if (oldK[j] != -1) {
                    insert(oldK[j], oldV[j]);
                }
            }
        }
        insert(key, val);
        hashCount++;
    }

    private void insert(long key, int val) {
        int mask = hashKeys.length - 1;
        int i = slot(key, mask);
        while (hashKeys[i] != -1) {
            i = (i + 1) & mask;
        }
        hashKeys[i] = key;
        hashVals[i] = val;
    }

    /**
     * Whether {@code n} ids starting with {@code id} can be bound in one step ({@link #bindRun}): dense
     * mode with a known delta, {@code id} is the next id, and the run stays below 2^31.
     */
    boolean nextDense(long id, int n) {
        return dense && !deltaOpen && id == limit && n > 0 && id + n <= Integer.MAX_VALUE - 1024;
    }

    /** Binds the next {@code n} ids to consecutive sink ids ({@code id + delta}); only after {@link #nextDense}. */
    void bindRun(int n) {
        limit += n;
    }

    /**
     * Whether every id in {@code [0, maxId]} is bound: true in dense mode and while the ids came in
     * order (then exactly the ids below {@link #boundCount()} are bound). False may be a false alarm.
     */
    boolean allBoundUpTo(long maxId) {
        return (dense || inOrder) && maxId < (dense ? limit : count);
    }

    /** Number of bound ids (including the implicit id 0 of names and levels). */
    long boundCount() {
        return dense ? limit : count;
    }

    /** Largest bound id, -1 if none; {@code Long.MAX_VALUE} if an id of 2^63 or more is bound. */
    long maxBoundId() {
        return dense ? limit - 1 : maxId;
    }

    /** Whether all ids so far were {@code 0, 1, 2, ...} in this order. */
    boolean denseInOrder() {
        return dense || inOrder;
    }

    /** Whether no translation table was needed. */
    boolean tableFree() {
        return dense;
    }
}
