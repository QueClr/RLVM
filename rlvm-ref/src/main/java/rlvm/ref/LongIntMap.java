package rlvm.ref;

/**
 * Open-addressing map from a pair of non-zero ints (packed into a {@code long}) to a non-zero int.
 * Clearing costs the number of entries (see {@link IntIntMap}).
 */
final class LongIntMap {

    private static final int MIN_CAPACITY = 256;
    private static final int SHRINK_ABOVE = 1 << 16;

    private long[] keys;
    private int[] vals;
    private int[] used;
    private int mask;
    private int shift;
    private int size;

    LongIntMap() {
        allocate(MIN_CAPACITY);
    }

    private void allocate(int capacity) {
        keys = new long[capacity];
        vals = new int[capacity];
        used = new int[(capacity >>> 1) + 1];
        mask = capacity - 1;
        shift = 64 - Long.numberOfTrailingZeros(capacity);
        size = 0;
    }

    static long key(int a, int b) {
        return ((long) a << 32) | (b & 0xFFFFFFFFL);
    }

    int get(long key) {
        if (size == 0) {
            return 0;
        }
        long[] t = keys;
        int m = mask;
        int i = (int) ((key * 0x9E3779B97F4A7C15L) >>> shift);
        for (;;) {
            long k = t[i];
            if (k == key) {
                return vals[i];
            }
            if (k == 0L) {
                return 0;
            }
            i = (i + 1) & m;
        }
    }

    void put(long key, int value) {
        long[] t = keys;
        int m = mask;
        int i = (int) ((key * 0x9E3779B97F4A7C15L) >>> shift);
        for (;;) {
            long k = t[i];
            if (k == key) {
                vals[i] = value;
                return;
            }
            if (k == 0L) {
                t[i] = key;
                vals[i] = value;
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
        long[] oldKeys = keys;
        int[] oldVals = vals;
        int[] oldUsed = used;
        int n = size;
        allocate((mask + 1) << 1);
        long[] t = keys;
        int m = mask;
        for (int j = 0; j < n; j++) {
            int s = oldUsed[j];
            long k = oldKeys[s];
            int i = (int) ((k * 0x9E3779B97F4A7C15L) >>> shift);
            while (t[i] != 0L) {
                i = (i + 1) & m;
            }
            t[i] = k;
            vals[i] = oldVals[s];
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
        long[] t = keys;
        int[] u = used;
        for (int j = 0; j < n; j++) {
            t[u[j]] = 0L;
        }
        size = 0;
    }

    int size() {
        return size;
    }

    /** Value of the {@code j}-th entry in insertion order (diagnostics). */
    int valueAt(int j) {
        return vals[used[j]];
    }

    long bytes() {
        return 16L + ((long) keys.length << 3) + ((long) vals.length << 2) + ((long) used.length << 2);
    }
}
