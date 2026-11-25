package rlvm.level;

import java.util.Arrays;

/**
 * Hash-consed sequences of ints in flat arrays: equal contents give equal ids.
 *
 * <p>Id 0 is the empty list (and "none"); other ids are dense, starting at 1, in creation order.
 *
 * <p><b>Two layouts.</b> A <em>flat</em> store (a root store, or the private copy made by
 * {@link #IntListStore(IntListStore)}) holds every list in its own arrays: list {@code id} occupies
 * {@code pool[off[id] .. off[id+1])}. A <em>shared overlay</em> ({@link #sharedOverlayOf}) holds only the
 * lists it created: ids below {@link #split} are read from the arrays of a frozen flat store, which this
 * object never writes, and list {@code id >= split} occupies {@code pool[off[id-split] .. off[id-split+1])}
 * of its own arrays. A flat store is the case {@code split == 0}, so both layouts run the same code.
 * A shared overlay has two hash tables, the base's (read only) and its own (own ids only).
 *
 * <p>Not thread-safe for writers. A store that is no longer written to may be read by any number of
 * threads; the base arrays of a shared overlay are such a store's. Instances are created and owned by
 * {@link LevelStore}, which also implements freezing and the per-worker overlays on top of the two
 * overlay constructors, {@link #truncate(int)} and {@link #rebase}.
 *
 * <p>Invariant used by {@link #truncate(int)}: ids are inserted into the current own hash table in
 * ascending order (also after a rehash), so the probe path of an entry only crosses slots of smaller
 * ids, and removing all ids above a bound never cuts the probe path of a remaining entry.
 */
public final class IntListStore {
    /** Initial own capacity (lists) of a shared overlay; its arrays grow and are kept across resets. */
    private static final int OVERLAY_LISTS = 1024;

    // ---- the shared base part: ids < split (all null / 0 in a flat store); never written here ----
    private int[] bPool;
    private int[] bOff;
    private int[] bHash;
    private int[] bTable;
    /** First own id: lists below it live in the base arrays. 0 in a flat store. */
    private int split;

    // ---- the own part: list id >= split has the own index id - split ----
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

    /** Private flat copy of {@code base} (same ids, same contents) with room to grow. */
    IntListStore(IntListStore base) {
        if (base.split != 0) {
            throw new IllegalStateException("a flat copy is made of a flat store");
        }
        int n = base.top;
        int cap = n + Math.max(64, n >> 2);
        int used = base.off[n];
        pool = Arrays.copyOf(base.pool, Math.max(16, used + Math.max(256, used >> 2)));
        off = Arrays.copyOf(base.off, cap + 1);
        hash = Arrays.copyOf(base.hash, cap);
        table = base.table.clone();
        top = n;
    }

    private IntListStore() {
        pool = new int[4 * OVERLAY_LISTS];
        off = new int[OVERLAY_LISTS + 1];
        hash = new int[OVERLAY_LISTS];
        table = new int[tableSizeFor(OVERLAY_LISTS)];
    }

    /**
     * A shared overlay of the flat store {@code base}, which must not be written any more: nothing of
     * the base is copied, the new store reads the base's arrays and keeps what it creates in its own.
     */
    static IntListStore sharedOverlayOf(IntListStore base) {
        IntListStore s = new IntListStore();
        s.adopt(base);
        return s;
    }

    private void adopt(IntListStore base) {
        if (base.split != 0) {
            throw new IllegalStateException("the base of a shared overlay is a flat store");
        }
        bPool = base.pool;
        bOff = base.off;
        bHash = base.hash;
        bTable = base.table;
        split = base.top;
        top = base.top;
    }

    /**
     * Shared overlay with no list of its own (after {@code truncate(split)}): moves it onto {@code base},
     * a flat store that extends the current base with the same ids. Costs nothing per list.
     */
    void rebase(IntListStore base) {
        if (split == 0) {
            throw new IllegalStateException("rebase is for shared overlays");
        }
        if (top != split) {
            throw new IllegalStateException("rebase needs an empty overlay");
        }
        if (base.top < split) {
            throw new IllegalArgumentException("rebase onto a smaller base");
        }
        adopt(base);
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
        int j = id - split;
        return j >= 0 ? off[j + 1] - off[j] : bOff[id + 1] - bOff[id];
    }

    public int get(int id, int index) {
        int j = id - split;
        return j >= 0 ? pool[off[j] + index] : bPool[bOff[id] + index];
    }

    /** Index of the first element of list {@code id} in {@link #poolOf poolOf(id)}. */
    public int start(int id) {
        int j = id - split;
        return j >= 0 ? off[j] : bOff[id];
    }

    /**
     * The array that holds list {@code id}, from {@link #start start(id)} on. For a list this store
     * created the array is replaced when the store grows: re-read it after any call that can intern a
     * list.
     */
    public int[] poolOf(int id) {
        return id >= split ? pool : bPool;
    }

    /**
     * The backing array of a flat store (every list is in it). It is replaced when the store grows:
     * re-read it after any call that can intern a list. A shared overlay has two arrays and no answer
     * here: use {@link #poolOf}.
     */
    public int[] pool() {
        if (split != 0) {
            throw new IllegalStateException("a shared overlay has no single backing array: use poolOf(id)");
        }
        return pool;
    }

    public void copyTo(int id, int[] dst, int dstOff) {
        int j = id - split;
        if (j >= 0) {
            int s = off[j];
            System.arraycopy(pool, s, dst, dstOff, off[j + 1] - s);
        } else {
            int s = bOff[id];
            System.arraycopy(bPool, s, dst, dstOff, bOff[id + 1] - s);
        }
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

    /**
     * Returns the id of the list {@code buf[from .. from+len)}, creating it if needed. A shared overlay
     * looks in its base first (the order for level lists: nine in ten are lists of the base).
     */
    public int intern(int[] buf, int from, int len) {
        if (len == 0) {
            return 0;
        }
        int h = hashOf(buf, from, len);
        if (split != 0) {
            int id = findInBase(buf, from, len, h);
            if (id != 0) {
                return id;
            }
        }
        int[] t = table;
        int mask = t.length - 1;
        int s = h & mask;
        for (;;) {
            int id = t[s];
            if (id == 0) {
                break;
            }
            int j = id - split;
            if (hash[j] == h) {
                int st = off[j];
                if (off[j + 1] - st == len && sameContent(pool, st, buf, from, len)) {
                    return id;
                }
            }
            s = (s + 1) & mask;
        }
        return add(buf, from, len, h, s);
    }

    /**
     * {@link #intern} with the other order of a shared overlay: its own lists first, then the base (the
     * order for substitution pairs: three in four are pairs this overlay made). Same result.
     */
    int internOwnFirst(int[] buf, int from, int len) {
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
            int j = id - split;
            if (hash[j] == h) {
                int st = off[j];
                if (off[j + 1] - st == len && sameContent(pool, st, buf, from, len)) {
                    return id;
                }
            }
            s = (s + 1) & mask;
        }
        if (split != 0) {
            int id = findInBase(buf, from, len, h);
            if (id != 0) {
                return id;
            }
        }
        return add(buf, from, len, h, s);
    }

    /** The base list with this content and hash, or 0. */
    private int findInBase(int[] buf, int from, int len, int h) {
        int[] t = bTable;
        int[] bh = bHash;
        int[] bo = bOff;
        int mask = t.length - 1;
        int s = h & mask;
        for (;;) {
            int id = t[s];
            if (id == 0) {
                return 0;
            }
            if (bh[id] == h) {
                int st = bo[id];
                if (bo[id + 1] - st == len && sameContent(bPool, st, buf, from, len)) {
                    return id;
                }
            }
            s = (s + 1) & mask;
        }
    }

    /** Appends a new list whose free slot in the own table is {@code slot}. */
    private int add(int[] buf, int from, int len, int h, int slot) {
        if (readOnly) {
            throw new IllegalStateException("store is frozen; intern through an overlay");
        }
        int id = top;
        int j = id - split;
        if (j == hash.length) {
            int ncap = j + (j >> 1) + 16;
            hash = Arrays.copyOf(hash, ncap);
            off = Arrays.copyOf(off, ncap + 1);
        }
        int st = off[j];
        if (st + len > pool.length) {
            pool = Arrays.copyOf(pool, Math.max(st + len, pool.length + (pool.length >> 1)));
        }
        System.arraycopy(buf, from, pool, st, len);
        off[j + 1] = st + len;
        hash[j] = h;
        table[slot] = id;
        top = id + 1;
        if (2 * (j + 1) > table.length) {
            rehash(table.length << 1);
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
        int sp = split;
        for (int id = Math.max(1, sp); id < top; id++) {
            int s = hash[id - sp] & mask;
            while (t[s] != 0) {
                s = (s + 1) & mask;
            }
            t[s] = id;
        }
        table = t;
    }

    /** Forgets all ids {@code >= newTop}; costs time proportional to the number of forgotten lists. */
    void truncate(int newTop) {
        if (newTop < 1 || newTop < split || newTop > top) {
            throw new IllegalArgumentException("bad truncation bound " + newTop);
        }
        int[] t = table;
        int mask = t.length - 1;
        int sp = split;
        for (int id = top - 1; id >= newTop; id--) {
            int s = hash[id - sp] & mask;
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
