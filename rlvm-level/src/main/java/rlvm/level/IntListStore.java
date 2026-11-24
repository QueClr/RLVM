package rlvm.level;

import java.util.Arrays;

/**
 * Hash-consed sequences of ints in flat arrays: equal contents give equal ids.
 *
 * <p>Id 0 is the empty list (and "none"); other ids are dense, starting at 1, in creation order. List
 * {@code id} occupies {@code pool[off[id] .. off[id+1])}.
 *
 * <p>Not thread-safe for writers. A store that is no longer written to may be read by any number of
 * threads. Instances are created and owned by {@link LevelStore}, which also implements freezing and
 * the per-worker overlay on top of {@link #IntListStore(IntListStore)} and {@link #truncate(int)}.
 *
 * <p>Invariant used by {@link #truncate(int)}: ids are inserted into the current hash table in
 * ascending order (also after a rehash), so the probe path of an entry only crosses slots of smaller
 * ids, and removing all ids above a bound never cuts the probe path of a remaining entry.
 */
public final class IntListStore {
    private int[] pool;
    private int[] off;
    private int[] hash;
    private int[] table;
    private int top;
    private boolean readOnly;

    IntListStore(int expectedLists, int expectedInts) {
        int cap = Math.max(8, expectedLists + 1);
        pool = new int[Math.max(16, expectedInts)];
        off = new int[cap + 1];
        hash = new int[cap];
        table = new int[tableSizeFor(cap)];
        top = 1;
    }

    /** Private copy of {@code base} (same ids, same contents) with room to grow. */
    IntListStore(IntListStore base) {
        int n = base.top;
        int cap = n + Math.max(64, n >> 2);
        int used = base.off[n];
        pool = Arrays.copyOf(base.pool, Math.max(16, used + Math.max(256, used >> 2)));
        off = Arrays.copyOf(base.off, cap + 1);
        hash = Arrays.copyOf(base.hash, cap);
        table = base.table.clone();
        top = n;
    }

    private static int tableSizeFor(int cap) {
        int n = 16;
        while (n < 2 * cap) {
            n <<= 1;
        }
        return n;
    }

    void setReadOnly() {
        readOnly = true;
    }

    /** Number of ids in use, including id 0; valid ids are {@code 0 .. size()-1}. */
    public int size() {
        return top;
    }

    public int length(int id) {
        return off[id + 1] - off[id];
    }

    public int get(int id, int index) {
        return pool[off[id] + index];
    }

    /** Index of the first element of list {@code id} in {@link #pool()}. */
    public int start(int id) {
        return off[id];
    }

    /**
     * The backing array. It is replaced when the store grows: re-read it after any call that can
     * intern a list.
     */
    public int[] pool() {
        return pool;
    }

    public void copyTo(int id, int[] dst, int dstOff) {
        int s = off[id];
        System.arraycopy(pool, s, dst, dstOff, off[id + 1] - s);
    }

    private static int hashOf(int[] buf, int from, int len) {
        int h = len * 0x9E3779B1;
        for (int i = 0; i < len; i++) {
            h = (h ^ buf[from + i]) * 0x01000193;
            h ^= h >>> 15;
        }
        h *= 0x2C1B3C6D;
        h ^= h >>> 13;
        return h;
    }

    /** Returns the id of the list {@code buf[from .. from+len)}, creating it if needed. */
    public int intern(int[] buf, int from, int len) {
        if (len == 0) {
            return 0;
        }
        int h = hashOf(buf, from, len);
        int[] t = table;
        int mask = t.length - 1;
        int s = h & mask;
        for (;;) {
            int id = t[s];
            if (id == 0) {
                break;
            }
            if (hash[id] == h) {
                int st = off[id];
                if (off[id + 1] - st == len && sameContent(pool, st, buf, from, len)) {
                    return id;
                }
            }
            s = (s + 1) & mask;
        }
        if (readOnly) {
            throw new IllegalStateException("store is frozen; intern through an overlay");
        }
        int id = top;
        if (id == hash.length) {
            int ncap = id + (id >> 1) + 16;
            hash = Arrays.copyOf(hash, ncap);
            off = Arrays.copyOf(off, ncap + 1);
        }
        int st = off[id];
        if (st + len > pool.length) {
            pool = Arrays.copyOf(pool, Math.max(st + len, pool.length + (pool.length >> 1)));
        }
        System.arraycopy(buf, from, pool, st, len);
        off[id + 1] = st + len;
        hash[id] = h;
        t[s] = id;
        top = id + 1;
        if (2 * top > t.length) {
            rehash(t.length << 1);
        }
        return id;
    }

    private static boolean sameContent(int[] x, int xs, int[] y, int ys, int len) {
        for (int i = 0; i < len; i++) {
            if (x[xs + i] != y[ys + i]) {
                return false;
            }
        }
        return true;
    }

    private void rehash(int newSize) {
        int[] t = new int[newSize];
        int mask = newSize - 1;
        for (int id = 1; id < top; id++) {
            int s = hash[id] & mask;
            while (t[s] != 0) {
                s = (s + 1) & mask;
            }
            t[s] = id;
        }
        table = t;
    }

    /** Forgets all ids {@code >= newTop}; costs time proportional to the number of forgotten lists. */
    void truncate(int newTop) {
        if (newTop < 1 || newTop > top) {
            throw new IllegalArgumentException("bad truncation bound " + newTop);
        }
        int[] t = table;
        int mask = t.length - 1;
        for (int id = top - 1; id >= newTop; id--) {
            int s = hash[id] & mask;
            int guard = t.length;
            while (t[s] != id) {
                s = (s + 1) & mask;
                if (--guard < 0) {
                    throw new IllegalStateException("list " + id + " missing from its hash table");
                }
            }
            t[s] = 0;
        }
        top = newTop;
    }
}
