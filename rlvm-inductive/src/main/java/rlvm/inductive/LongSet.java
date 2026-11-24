package rlvm.inductive;

/** Open-addressing set of {@code long} keys with O(1) {@link #clear()}; admission-private scratch. */
final class LongSet {

    private long[] keys;
    private int[] stamps;
    private int epoch = 1;
    private int size;
    private int mask;

    LongSet(int capacityPow2) {
        keys = new long[capacityPow2];
        stamps = new int[capacityPow2];
        mask = capacityPow2 - 1;
    }

    void clear() {
        size = 0;
        if (++epoch == 0) {
            java.util.Arrays.fill(stamps, 0);
            epoch = 1;
        }
    }

    private static int mix(long k) {
        long h = k * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 32));
    }

    /** True if {@code key} was absent (and is now present). */
    boolean add(long key) {
        int i = mix(key) & mask;
        while (stamps[i] == epoch) {
            if (keys[i] == key) {
                return false;
            }
            i = (i + 1) & mask;
        }
        stamps[i] = epoch;
        keys[i] = key;
        if (++size * 2 > mask) {
            grow();
        }
        return true;
    }

    private void grow() {
        long[] ok = keys;
        int[] os = stamps;
        int oe = epoch;
        int n = ok.length * 2;
        keys = new long[n];
        stamps = new int[n];
        mask = n - 1;
        epoch = 1;
        for (int j = 0; j < ok.length; j++) {
            if (os[j] == oe) {
                int i = mix(ok[j]) & mask;
                while (stamps[i] == epoch) {
                    i = (i + 1) & mask;
                }
                stamps[i] = epoch;
                keys[i] = ok[j];
            }
        }
    }
}
