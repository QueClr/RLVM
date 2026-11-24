package rlvm.inductive;

/**
 * Open-addressing {@code int -> int} map with O(1) {@link #clear()} (epoch stamps). Keys are any
 * {@code int}; a missing key reads as {@link #NONE}. Admission-private scratch: memo tables of term
 * rewrites, visited sets, stand-in lookup. Not thread-safe.
 */
final class IntIntMap {

    static final int NONE = 0;

    private int[] keys;
    private int[] vals;
    private int[] stamps;
    private int epoch = 1;
    private int size;
    private int mask;

    IntIntMap(int capacityPow2) {
        keys = new int[capacityPow2];
        vals = new int[capacityPow2];
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

    int size() {
        return size;
    }

    private static int mix(int k) {
        int h = k * 0x9E3779B1;
        return h ^ (h >>> 15);
    }

    /** Value of {@code key}, or {@link #NONE}. */
    int get(int key) {
        int i = mix(key) & mask;
        while (stamps[i] == epoch) {
            if (keys[i] == key) {
                return vals[i];
            }
            i = (i + 1) & mask;
        }
        return NONE;
    }

    boolean contains(int key) {
        int i = mix(key) & mask;
        while (stamps[i] == epoch) {
            if (keys[i] == key) {
                return true;
            }
            i = (i + 1) & mask;
        }
        return false;
    }

    /** Sets {@code key -> value}; returns the previous value or {@link #NONE}. */
    int put(int key, int value) {
        int i = mix(key) & mask;
        while (stamps[i] == epoch) {
            if (keys[i] == key) {
                int old = vals[i];
                vals[i] = value;
                return old;
            }
            i = (i + 1) & mask;
        }
        stamps[i] = epoch;
        keys[i] = key;
        vals[i] = value;
        if (++size * 2 > mask) {
            grow();
        }
        return NONE;
    }

    /** Adds {@code key} to the set view; true if it was absent. */
    boolean add(int key) {
        int i = mix(key) & mask;
        while (stamps[i] == epoch) {
            if (keys[i] == key) {
                return false;
            }
            i = (i + 1) & mask;
        }
        stamps[i] = epoch;
        keys[i] = key;
        vals[i] = 1;
        if (++size * 2 > mask) {
            grow();
        }
        return true;
    }

    private void grow() {
        int[] ok = keys;
        int[] ov = vals;
        int[] os = stamps;
        int oe = epoch;
        int n = ok.length * 2;
        keys = new int[n];
        vals = new int[n];
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
                vals[i] = ov[j];
            }
        }
    }
}
