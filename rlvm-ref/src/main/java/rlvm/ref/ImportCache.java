package rlvm.ref;

import rlvm.store.Node;

/**
 * Session-level table of the constants' terms a worker has imported: {@code (constant, which term,
 * universe levels)} maps either to the canonical overlay id of the instantiated copy that outlives
 * declarations, or to the number of declarations that had to make that copy themselves so far (see
 * {@link RefKernel}, "Imports that outlive a declaration"). Open addressing, four ints per slot
 * (constant id, sub-term selector, level-list id, value), so that a probe touches one cache line.
 *
 * <p>The table is bounded: at {@link #MAX_SLOTS} slots the counters are dropped and only the cached
 * ids stay; if those alone fill the table, new keys are no longer tracked. Nothing here is a fact
 * about the input: an entry is only ever a copy that {@code RefKernel.copy} would build again, node
 * for node.
 */
final class ImportCache {

    /** Sub-term selectors: the constant's type, its value, or recursor rule {@code sub - RULE}. */
    static final int TYPE = 0;
    static final int VALUE = 1;
    static final int RULE = 2;

    private static final int MIN_SLOTS = 1 << 10;
    static final int MAX_SLOTS = 1 << 16;

    private int[] tab = new int[MIN_SLOTS << 2];
    private int mask = MIN_SLOTS - 1;
    /** Keys in the table (cached ids and counters). */
    private int size;
    /** Keys whose value is a cached id. */
    private int cached;
    /** Set when the cached ids alone fill the table: no new key is entered any more. */
    private boolean full;

    private static int hash(int cid, int sub, int levels) {
        int h = (cid * 0x9E3779B1) ^ (levels * 0x85EBCA6B) ^ (sub * 0xC2B2AE35);
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        return h ^ (h >>> 13);
    }

    /** Slot of the key, or {@code -1 - slot} of the free slot where it belongs. */
    private int find(int cid, int sub, int levels) {
        int[] t = tab;
        int m = mask;
        int i = hash(cid, sub, levels) & m;
        for (;;) {
            int w = i << 2;
            int k = t[w];
            if (k == 0) {
                return -1 - i;
            }
            if (k == cid && t[w + 1] == sub && t[w + 2] == levels) {
                return i;
            }
            i = (i + 1) & m;
        }
    }

    /** The cached canonical id of the key, or 0. */
    int get(int cid, int sub, int levels) {
        if (cached == 0) {
            return 0;
        }
        int s = find(cid, sub, levels);
        if (s < 0) {
            return 0;
        }
        int v = tab[(s << 2) + 3];
        return v >= Node.OVERLAY_BIT ? v : 0;
    }

    /**
     * One more declaration copied the key's term itself.
     *
     * @return how many did so far (this one included); 0 if the key is not tracked (table full) or
     *     already has a cached id
     */
    int miss(int cid, int sub, int levels) {
        int s = find(cid, sub, levels);
        if (s >= 0) {
            int w = (s << 2) + 3;
            int v = tab[w];
            if (v >= Node.OVERLAY_BIT) {
                return 0;
            }
            if (v < Node.OVERLAY_BIT - 1) {
                tab[w] = ++v;
            }
            return v;
        }
        if (full) {
            return 0;
        }
        if ((size + 1) * 2 > mask + 1) {
            if (!makeRoom()) {
                return 0;
            }
            s = find(cid, sub, levels);
        }
        int w = (-1 - s) << 2;
        tab[w] = cid;
        tab[w + 1] = sub;
        tab[w + 2] = levels;
        tab[w + 3] = 1;
        size++;
        return 1;
    }

    /** Records the canonical id of the key's copy. Returns false if the key is not tracked. */
    boolean put(int cid, int sub, int levels, int id) {
        if (id < Node.OVERLAY_BIT) {
            throw new IllegalArgumentException("not an overlay term: " + id);
        }
        int s = find(cid, sub, levels);
        if (s < 0) {
            return false;
        }
        int w = (s << 2) + 3;
        if (tab[w] < Node.OVERLAY_BIT) {
            cached++;
        }
        tab[w] = id;
        return true;
    }

    /** Doubles the table, or drops the counters when it cannot grow any more. */
    private boolean makeRoom() {
        int slots = mask + 1;
        if (slots < MAX_SLOTS) {
            rebuild(slots << 1, false);
            return true;
        }
        rebuild(slots, true);
        if ((size + 1) * 2 > slots) {
            full = true;
            return false;
        }
        return true;
    }

    private void rebuild(int slots, boolean cachedOnly) {
        int[] old = tab;
        int[] t = new int[slots << 2];
        int m = slots - 1;
        int n = 0;
        for (int w = 0; w < old.length; w += 4) {
            int k = old[w];
            if (k == 0 || (cachedOnly && old[w + 3] < Node.OVERLAY_BIT)) {
                continue;
            }
            int i = hash(k, old[w + 1], old[w + 2]) & m;
            while (t[i << 2] != 0) {
                i = (i + 1) & m;
            }
            int d = i << 2;
            t[d] = k;
            t[d + 1] = old[w + 1];
            t[d + 2] = old[w + 2];
            t[d + 3] = old[w + 3];
            n++;
        }
        tab = t;
        mask = m;
        size = n;
    }

    /** Forgets everything (the overlay, or the level overlay, was reset). */
    void clear() {
        if (size == 0 && !full) {
            return;
        }
        if (mask + 1 > MIN_SLOTS) {
            tab = new int[MIN_SLOTS << 2];
            mask = MIN_SLOTS - 1;
        } else {
            java.util.Arrays.fill(tab, 0);
        }
        size = 0;
        cached = 0;
        full = false;
    }

    /** Keys with a cached id. */
    int cached() {
        return cached;
    }

    int size() {
        return size;
    }

    long bytes() {
        return 16L + ((long) tab.length << 2);
    }
}
