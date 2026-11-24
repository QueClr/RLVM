package rlvm.ref;

/**
 * Open-addressing set of unordered pairs of non-zero term ids (the official {@code m_success} and
 * {@code m_failure} sets). A pair is stored as one {@code long} with the smaller id in the high half.
 * Clearing costs the number of entries (see {@link IntIntMap}).
 */
final class PairSet {

    private static final int MIN_CAPACITY = 256;
    private static final int SHRINK_ABOVE = 1 << 16;

    private long[] tab;
    private int[] used;
    private int mask;
    private int shift;
    private int size;

    PairSet() {
        allocate(MIN_CAPACITY);
    }

    private void allocate(int capacity) {
        tab = new long[capacity];
        used = new int[(capacity >>> 1) + 1];
        mask = capacity - 1;
        shift = 64 - Long.numberOfTrailingZeros(capacity);
        size = 0;
    }

    private static long key(int a, int b) {
        return a < b ? ((long) a << 32) | (b & 0xFFFFFFFFL) : ((long) b << 32) | (a & 0xFFFFFFFFL);
    }

    boolean contains(int a, int b) {
        if (size == 0) {
            return false;
        }
        long key = key(a, b);
        long[] t = tab;
        int m = mask;
        int i = (int) ((key * 0x9E3779B97F4A7C15L) >>> shift);
        for (;;) {
            long k = t[i];
            if (k == key) {
                return true;
            }
            if (k == 0L) {
                return false;
            }
            i = (i + 1) & m;
        }
    }

    void add(int a, int b) {
        long key = key(a, b);
        long[] t = tab;
        int m = mask;
        int i = (int) ((key * 0x9E3779B97F4A7C15L) >>> shift);
        for (;;) {
            long k = t[i];
            if (k == key) {
                return;
            }
            if (k == 0L) {
                t[i] = key;
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
        long[] old = tab;
        int[] oldUsed = used;
        int n = size;
        allocate((mask + 1) << 1);
        long[] t = tab;
        int m = mask;
        for (int j = 0; j < n; j++) {
            long k = old[oldUsed[j]];
            int i = (int) ((k * 0x9E3779B97F4A7C15L) >>> shift);
            while (t[i] != 0L) {
                i = (i + 1) & m;
            }
            t[i] = k;
            used[j] = i;
        }
        size = n;
    }

    void clear() {
        int n = size;
        int capacity = mask + 1;
        if (capacity > MIN_CAPACITY && (capacity > SHRINK_ABOVE || n * IntIntMap.SPARSE < capacity)) {
            // Very large, or far larger than this declaration needed (possibly not needed at all): the
            // next declaration probes a table that fits the cache.
            allocate(n == 0 || capacity > SHRINK_ABOVE ? MIN_CAPACITY
                    : Math.max(MIN_CAPACITY, Integer.highestOneBit(n) << 3));
            return;
        }
        if (n == 0) {
            return;
        }
        long[] t = tab;
        int[] u = used;
        for (int j = 0; j < n; j++) {
            t[u[j]] = 0L;
        }
        size = 0;
    }

    int size() {
        return size;
    }

    /** The {@code j}-th pair in insertion order, smaller id in the high half (diagnostics). */
    long pairAt(int j) {
        return tab[used[j]];
    }

    long bytes() {
        return 16L + ((long) tab.length << 3) + ((long) used.length << 2);
    }
}
