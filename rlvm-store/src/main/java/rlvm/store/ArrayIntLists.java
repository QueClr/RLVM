package rlvm.store;

import java.util.Arrays;

/**
 * A self-contained {@link IntLists}: hash-consed {@code int} sequences in flat arrays, with a frozen
 * base and thread-confined overlays. It is the list store of a {@link Store} that was not given one
 * (tests, tools, the store's own benchmarks); a real run uses the list store of {@code rlvm-level}
 * behind the same interface, so that level-list ids mean the same thing everywhere.
 *
 * <p>Id 0 is the empty list. A base store hands out ids 1, 2, ... in creation order; an overlay made
 * by {@link #newOverlay()} continues after the base's last id, finds base lists by content, and
 * forgets its own lists at {@link #reset()}. The base must be {@linkplain #freeze() frozen} before an
 * overlay is created; a frozen store is immutable and may be read from any thread.
 */
public final class ArrayIntLists implements IntLists {

    private final ArrayIntLists base;
    /** First id owned by this store (1 for a base store). */
    private final int firstId;
    /** off[k] .. off[k+1]: elements of own list number k (id = firstId + k). */
    private int[] off;
    private int[] pool;
    private int count;
    /** Open addressing: slot = own list number + 1; 0 = empty. */
    private int[] table;
    private boolean frozen;

    public ArrayIntLists() {
        this(null);
    }

    private ArrayIntLists(ArrayIntLists base) {
        this.base = base;
        this.firstId = base == null ? 1 : base.firstId + base.count;
        this.off = new int[17];
        this.pool = new int[64];
        this.table = new int[32];
    }

    /** Makes this store immutable (interning a new list then throws). Idempotent. */
    public void freeze() {
        frozen = true;
    }

    public boolean isFrozen() {
        return frozen;
    }

    /** A private overlay over this frozen store. */
    public ArrayIntLists newOverlay() {
        if (!frozen) {
            throw new IllegalStateException("the base list store must be frozen before overlays are made");
        }
        if (base != null) {
            throw new IllegalStateException("overlays do not nest");
        }
        return new ArrayIntLists(this);
    }

    /** Number of ids in use, including id 0 and the base's ids. */
    public int size() {
        return firstId + count;
    }

    /** Overlays only: forgets every list this overlay created. */
    public void reset() {
        if (frozen) {
            throw new IllegalStateException("frozen");
        }
        if (count != 0) {
            count = 0;
            if (table.length > 4096) {
                table = new int[32];
            } else {
                Arrays.fill(table, 0);
            }
        }
    }

    @Override
    public int intern(int[] buf, int from, int len) {
        if (len == 0) {
            return EMPTY;
        }
        int h = hash(buf, from, len);
        if (base != null) {
            int id = base.find(buf, from, len, h);
            if (id != 0) {
                return id;
            }
        }
        int id = find(buf, from, len, h);
        if (id != 0) {
            return id;
        }
        if (frozen) {
            throw new IllegalStateException("list store is frozen");
        }
        if (count + 2 > off.length) {
            off = Arrays.copyOf(off, off.length * 2);
        }
        int start = off[count];
        if (start + len > pool.length) {
            long want = Math.max((long) pool.length * 2, (long) start + len);
            if (want > Integer.MAX_VALUE - 8) {
                throw new StoreLimitException("list pool exceeds 2^31 ints");
            }
            pool = Arrays.copyOf(pool, (int) want);
        }
        System.arraycopy(buf, from, pool, start, len);
        off[count + 1] = start + len;
        int k = count++;
        if (count * 2 > table.length) {
            rehash();
        } else {
            insert(k, h);
        }
        return firstId + k;
    }

    private void insert(int k, int h) {
        int mask = table.length - 1;
        int i = h & mask;
        while (table[i] != 0) {
            i = (i + 1) & mask;
        }
        table[i] = k + 1;
    }

    private void rehash() {
        table = new int[table.length * 2];
        for (int k = 0; k < count; k++) {
            insert(k, hash(pool, off[k], off[k + 1] - off[k]));
        }
    }

    private int find(int[] buf, int from, int len, int h) {
        int[] t = table;
        int mask = t.length - 1;
        int i = h & mask;
        for (;;) {
            int s = t[i];
            if (s == 0) {
                return 0;
            }
            int k = s - 1;
            int st = off[k];
            if (off[k + 1] - st == len) {
                int j = 0;
                while (j < len && pool[st + j] == buf[from + j]) {
                    j++;
                }
                if (j == len) {
                    return firstId + k;
                }
            }
            i = (i + 1) & mask;
        }
    }

    private static int hash(int[] buf, int from, int len) {
        int h = len * 0x9E3779B1;
        for (int i = 0; i < len; i++) {
            h = (h ^ buf[from + i]) * 0x85EBCA6B;
            h ^= h >>> 13;
        }
        return h ^ (h >>> 16);
    }

    @Override
    public int length(int list) {
        if (list < firstId) {
            return base == null ? 0 : base.length(list);
        }
        int k = list - firstId;
        return off[k + 1] - off[k];
    }

    @Override
    public int get(int list, int index) {
        if (list < firstId) {
            if (base == null) {
                throw new IndexOutOfBoundsException("empty list");
            }
            return base.get(list, index);
        }
        int k = list - firstId;
        int st = off[k];
        if (index < 0 || st + index >= off[k + 1]) {
            throw new IndexOutOfBoundsException("list " + list + " index " + index);
        }
        return pool[st + index];
    }

    @Override
    public void copyTo(int list, int[] dst, int dstOff) {
        if (list < firstId) {
            if (base != null) {
                base.copyTo(list, dst, dstOff);
            }
            return;
        }
        int k = list - firstId;
        System.arraycopy(pool, off[k], dst, dstOff, off[k + 1] - off[k]);
    }

    /** A base store shares every list it holds; an overlay shares the lists of its base only. */
    @Override
    public boolean isSharedList(int list) {
        if (base != null) {
            return base.isSharedList(list);
        }
        return list >= 0 && list < firstId + count;
    }

    public long bytes() {
        return 4L * (off.length + pool.length + table.length);
    }
}
