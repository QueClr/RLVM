package rlvm.ref;

/**
 * Open-addressing map from non-zero {@code int} keys to non-zero {@code int} values (term ids);
 * 0 means "absent". Keys and values are interleaved in one array so that a probe touches one cache
 * line. Cleared once per declaration: the slots in use are remembered, so that clearing costs the
 * number of entries, not the capacity, and a table keeps its size from one declaration to the next
 * unless it grew very large or was nearly empty (then it is replaced by a small one).
 */
final class IntIntMap {

    private static final int MIN_CAPACITY = 256;
    /** A table above this capacity is reallocated at {@link #clear()}. */
    private static final int SHRINK_ABOVE = 1 << 16;
    /**
     * A table that held fewer than 1 / SPARSE of its capacity is replaced by one of 4 to 8 times its
     * entries at {@link #clear()}: after one large declaration the tables would otherwise stay large
     * for all the small ones that follow, and every probe of theirs would miss the cache.
     */
    static final int SPARSE = 16;

    private int[] tab;
    /** Slot indices of the entries, in insertion order. */
    private int[] used;
    private int mask;
    private int shift;
    private int size;

    IntIntMap() {
        allocate(MIN_CAPACITY);
    }

    private void allocate(int capacity) {
        tab = new int[capacity << 1];
        used = new int[(capacity >>> 1) + 1];
        mask = capacity - 1;
        shift = 32 - Integer.numberOfTrailingZeros(capacity);
        size = 0;
    }

    int get(int key) {
        int[] t = tab;
        int m = mask;
        int i = (key * 0x9E3779B9) >>> shift;
        for (;;) {
            int k = t[i << 1];
            if (k == key) {
                return t[(i << 1) + 1];
            }
            if (k == 0) {
                return 0;
            }
            i = (i + 1) & m;
        }
    }

    void put(int key, int value) {
        int[] t = tab;
        int m = mask;
        int i = (key * 0x9E3779B9) >>> shift;
        for (;;) {
            int k = t[i << 1];
            if (k == key) {
                t[(i << 1) + 1] = value;
                return;
            }
            if (k == 0) {
                t[i << 1] = key;
                t[(i << 1) + 1] = value;
                used[size] = i;
                if (++size > (m + 1) >>> 1) {
                    grow();
                }
                return;
            }
            i = (i + 1) & m;
        }
    }

    private void grow() {
        int[] old = tab;
        int[] oldUsed = used;
        int n = size;
        allocate((mask + 1) << 1);
        int[] t = tab;
        int m = mask;
        for (int j = 0; j < n; j++) {
            int s = oldUsed[j] << 1;
            int k = old[s];
            int i = (k * 0x9E3779B9) >>> shift;
            while (t[i << 1] != 0) {
                i = (i + 1) & m;
            }
            t[i << 1] = k;
            t[(i << 1) + 1] = old[s + 1];
            used[j] = i;
        }
        size = n;
    }

    void clear() {
        int n = size;
        int capacity = mask + 1;
        if (capacity > MIN_CAPACITY && (capacity > SHRINK_ABOVE || n * SPARSE < capacity)) {
            // Very large, or far larger than this declaration needed (possibly not needed at all): the
            // next declaration probes a table that fits the cache.
            allocate(n == 0 || capacity > SHRINK_ABOVE ? MIN_CAPACITY
                    : Math.max(MIN_CAPACITY, Integer.highestOneBit(n) << 3));
            return;
        }
        if (n == 0) {
            return;
        }
        int[] t = tab;
        int[] u = used;
        for (int j = 0; j < n; j++) {
            t[u[j] << 1] = 0;
        }
        size = 0;
    }

    int size() {
        return size;
    }

    /** Key of the {@code j}-th entry in insertion order (diagnostics). */
    int keyAt(int j) {
        return tab[used[j] << 1];
    }

    /** Value of the {@code j}-th entry in insertion order (diagnostics). */
    int valueAt(int j) {
        return tab[(used[j] << 1) + 1];
    }

    long bytes() {
        return 16L + ((long) tab.length << 2) + ((long) used.length << 2);
    }
}
