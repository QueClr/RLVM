package rlvm.store;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;

/**
 * Literal payloads that do not fit in a node: natural numbers of 2^64 and above (little-endian
 * 64-bit limbs) and string literals (UTF-8 bytes plus a lazily built code-point index). Naturals
 * are stored once, in chunks that are never copied or moved (a declaration can compute gigabytes of
 * them: {@code perf/gf3-matrix-product}); a number of 1,024 limbs or more has an array of its own.
 *
 * <p>Both kinds are interned BY CONTENT (*agentic-note*: design-reconciliation-document, section 2, "Literal identity"): equal
 * numbers, and equal strings, have equal indices. Naturals and strings have separate index spaces,
 * each starting at 1; index 0 is "none".
 *
 * <p>Shared pool ({@link #LitPool()}): holds the export's literals; written by the loader thread,
 * an entry is immutable and visible to other threads once {@link #publish()} ran after it (the
 * {@link StoreBuilder} does that at every publication).
 *
 * <p>Worker pool ({@link #LitPool(LitPool)}): one per worker, for numbers and strings it computes.
 * Its own entries have NEGATIVE indices, so that one {@code int} names an entry of either pool; a
 * value that the shared pool already holds (and has published) gets the shared, positive index. A
 * worker pool resolves both signs; the shared pool only positive ones. Until the reference kernel
 * and the machine route every literal through one pool, a consumer that sees two different indices
 * must still compare contents before answering "different" ({@link #natEquals},
 * {@link #stringEquals}); equal indices always mean equal contents.
 *
 * <p><b>Giving memory back</b> (worker pools). {@link #truncateTo} releases every entry made after
 * a {@link #mark()}: the limbs and bytes are freed at once. The INDEX of a released entry is not
 * handed out again before the next {@link #reset()}: whoever still holds it (a cache keyed by
 * index, a stale term) can never be given another value under the same index, and reading a
 * released entry throws {@link IllegalStateException} instead of answering. A released entry costs
 * 12 bytes (naturals) or 8 bytes (strings) until the reset.
 */
public final class LitPool {

    private static final VarHandle INT_ARRAYS = MethodHandles.arrayElementVarHandle(int[][].class);
    private static final int[] ASCII = new int[0];

    private final LitPool shared;

    // naturals: entry k (index k, from 1) has natLoc[3k+2] limbs at offset natLoc[3k+1] of chunk natLoc[3k].
    // Chunks never move and are never copied: a number of BIG_LIMBS limbs or more has an array of its
    // own, smaller ones are packed into chunks of CHUNK_LIMBS (the first one FIRST_LIMBS).
    private static final int BIG_LIMBS = 1024;
    private static final int FIRST_LIMBS = 1024;
    private static final int CHUNK_LIMBS = 1 << 16;
    private long[][] chunks = new long[4][];
    private volatile long[][] chunksPub = chunks;
    private int chunkCount;
    /** The chunk small numbers are packed into, or -1; and its fill. */
    private int cur = -1;
    private int fill;
    private long limbsAllocated;
    private int[] natLoc = new int[3 * 18];
    private volatile int[] natLocPub = natLoc;
    /** Highest index handed out (released entries included: indices are not reused before a reset). */
    private int natCount;
    private int natLive;
    private volatile int natCountPub;
    private int[] natTable = new int[32];
    private volatile int[] natTablePub = natTable;

    // strings: entry k has bytes strBytes[strOff[k] .. strOff[k+1]) and strCp[k] code points
    private byte[] bytes = new byte[256];
    private volatile byte[] bytesPub = bytes;
    private int[] strOff = new int[18];
    private volatile int[] strOffPub = strOff;
    private int[] strCp = new int[18];
    private volatile int[] strCpPub = strCp;
    private int strCount;
    private int strLive;
    private volatile int strCountPub;
    private int[] strTable = new int[32];
    private volatile int[] strTablePub = strTable;
    /** Byte offset of every code point, per non-ASCII string that was indexed; built by any reader. */
    private volatile int[][] cpIndex = new int[18][];

    /** A shared pool. */
    public LitPool() {
        this.shared = null;
    }

    /** A worker pool on top of {@code shared}. */
    public LitPool(LitPool shared) {
        if (shared != null && shared.shared != null) {
            throw new IllegalArgumentException("worker pools do not nest");
        }
        this.shared = shared;
    }

    /** True if string {@code index} was released by {@link #truncateTo} (worker entries only). */
    public boolean stringReleased(int index) {
        return index < 0 && strCp[-index] < 0;
    }

    /**
     * Shared pool: true if natural ({@code natural}) or string {@code index} is an entry that every
     * thread can read ({@link #publish()} ran after it was added).
     */
    public boolean isPublished(int index, boolean natural) {
        if (shared != null) {
            return shared.isPublished(index, natural);
        }
        return index > 0 && index <= (natural ? natCountPub : strCountPub);
    }

    /** True for a worker pool. */
    public boolean isWorkerPool() {
        return shared != null;
    }

    /** Shared pool, loader thread: makes every entry added so far visible to other threads. */
    public void publish() {
        natCountPub = natCount;
        strCountPub = strCount;
    }

    // ---- naturals ----------------------------------------------------------------------------------

    /**
     * Interns a natural number given as little-endian limbs (no leading zero limb); returns its index.
     * Numbers below 2^64 belong in the node, not here; they are accepted all the same.
     */
    public int addNat(long[] src, int from, int len) {
        if (len <= 0 || src[from + len - 1] == 0) {
            throw new IllegalArgumentException("a pooled natural has at least one limb and no leading zero limb");
        }
        int h = hashLimbs(src, from, len);
        // A worker looks in its OWN table first (R2, 2026-11-24): a value it has already interned keeps
        // its index for the rest of the declaration, whatever the loader thread publishes meanwhile
        // (looking in the shared pool first gave one literal two indices, hence two node ids, inside
        // one declaration when the loader published that value between two uses: timing-dependent).
        int k = findNat(src, from, len, h, natTable, natCount);
        if (k != 0) {
            return shared != null ? -k : k;
        }
        if (shared != null) {
            // Count first, then the table: a table read later holds every entry up to that count.
            int limit = shared.natCountPub;
            int s = shared.findNat(src, from, len, h, shared.natTablePub, limit);
            if (s != 0) {
                return s;
            }
        }
        k = natCount + 1;
        if (3 * k + 3 > natLoc.length) {
            int[] bigger = Arrays.copyOf(natLoc, natLoc.length * 2);
            natLoc = bigger;
            natLocPub = bigger;
        }
        int c;
        int at;
        if (len >= BIG_LIMBS) {
            c = addChunk(new long[len]);
            at = 0;
        } else {
            if (cur < 0 || fill + len > chunks[cur].length) {
                cur = addChunk(new long[chunkCount == 0 ? FIRST_LIMBS : CHUNK_LIMBS]);
                fill = 0;
            }
            c = cur;
            at = fill;
            fill += len;
        }
        System.arraycopy(src, from, chunks[c], at, len);
        natLoc[3 * k] = c;
        natLoc[3 * k + 1] = at;
        natLoc[3 * k + 2] = len;
        natCount = k;
        natLive++;
        if (natLive * 2 > natTable.length) {
            // live entries only, in ascending order (released entries have no limbs and no slot)
            int[] t = new int[natTable.length * 2];
            for (int e = 1; e <= k; e++) {
                if (natLoc[3 * e + 2] != 0) {
                    put(t, e, hashLimbs(chunks[natLoc[3 * e]], natLoc[3 * e + 1], natLoc[3 * e + 2]));
                }
            }
            natTable = t;
            natTablePub = t;
        } else {
            put(natTable, k, h);
        }
        return shared != null ? -k : k;
    }

    private int addChunk(long[] chunk) {
        if (chunkCount == chunks.length) {
            long[][] bigger = Arrays.copyOf(chunks, chunkCount * 2);
            chunks = bigger;
            chunksPub = bigger;
        }
        chunks[chunkCount] = chunk;
        limbsAllocated += chunk.length;
        return chunkCount++;
    }

    private static void put(int[] t, int entry, int h) {
        int mask = t.length - 1;
        int i = h & mask;
        while (t[i] != 0) {
            i = (i + 1) & mask;
        }
        t[i] = entry;
    }

    /** Entry (1-based) with these limbs among the first {@code limit} entries of this pool, or 0. */
    private int findNat(long[] src, int from, int len, int h, int[] t, int limit) {
        int[] loc = natLocPub;
        long[][] ch = chunksPub;
        int mask = t.length - 1;
        int i = h & mask;
        for (;;) {
            int e = t[i];
            if (e == 0) {
                return 0;
            }
            if (e <= limit && loc[3 * e + 2] == len) {
                long[] lb = ch[loc[3 * e]];
                int at = loc[3 * e + 1];
                int j = 0;
                while (j < len && lb[at + j] == src[from + j]) {
                    j++;
                }
                if (j == len) {
                    return e;
                }
            }
            i = (i + 1) & mask;
        }
    }

    private static int hashLimbs(long[] a, int from, int len) {
        long h = len * 0x9E3779B97F4A7C15L;
        for (int i = 0; i < len; i++) {
            h = (h ^ a[from + i]) * 0xFF51AFD7ED558CCDL;
            h ^= h >>> 29;
        }
        return (int) (h ^ (h >>> 32));
    }

    public int natLimbCount(int index) {
        LitPool p = index < 0 || shared == null ? this : shared;
        int n = p.natLocPub[3 * (index < 0 ? -index : index) + 2];
        if (n == 0) {
            throw released("natural", index);
        }
        return n;
    }

    public void natLimbs(int index, long[] dst, int dstOff) {
        LitPool p = index < 0 || shared == null ? this : shared;
        int[] loc = p.natLocPub;
        int k = 3 * (index < 0 ? -index : index);
        if (loc[k + 2] == 0) {
            throw released("natural", index);
        }
        System.arraycopy(p.chunksPub[loc[k]], loc[k + 1], dst, dstOff, loc[k + 2]);
    }

    /** Limb {@code i} (0 = least significant) of natural {@code index}. */
    public long natLimb(int index, int i) {
        LitPool p = index < 0 || shared == null ? this : shared;
        int[] loc = p.natLocPub;
        int k = 3 * (index < 0 ? -index : index);
        if (i < 0 || i >= loc[k + 2]) {
            if (loc[k + 2] == 0) {
                throw released("natural", index);
            }
            throw new IndexOutOfBoundsException("limb " + i + " of a natural with " + loc[k + 2]);
        }
        return p.chunksPub[loc[k]][loc[k + 1] + i];
    }

    /** True if natural {@code index} was released by {@link #truncateTo} (worker entries only). */
    public boolean natReleased(int index) {
        return index < 0 && natLoc[3 * -index + 2] == 0;
    }

    private static IllegalStateException released(String what, int index) {
        return new IllegalStateException("literal pool: " + what + " " + index + " is not an entry (released by"
                + " truncateTo, or never made)");
    }

    /** Content equality of two pooled naturals (indices of either sign on a worker pool). */
    public boolean natEquals(int indexA, int indexB) {
        if (indexA == indexB) {
            return true;
        }
        int n = natLimbCount(indexA);
        if (n != natLimbCount(indexB)) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            if (natLimb(indexA, i) != natLimb(indexB, i)) {
                return false;
            }
        }
        return true;
    }

    // ---- strings -----------------------------------------------------------------------------------

    /** Interns a string literal (decoded, valid UTF-8); returns its index. */
    public int addString(byte[] utf8, int off, int len) {
        int h = hashBytes(utf8, off, len);
        // the worker's own table first, as in addNat
        int k = findString(utf8, off, len, h, strTable, strCount);
        if (k != 0) {
            return shared != null ? -k : k;
        }
        if (shared != null) {
            int limit = shared.strCountPub;
            int s = shared.findString(utf8, off, len, h, shared.strTablePub, limit);
            if (s != 0) {
                return s;
            }
        }
        k = strCount + 1;
        if (k + 2 > strOff.length) {
            int[] bigger = Arrays.copyOf(strOff, strOff.length * 2);
            strOff = bigger;
            strOffPub = bigger;
            int[] cp = Arrays.copyOf(strCp, strCp.length * 2);
            strCp = cp;
            strCpPub = cp;
        }
        int at = strOff[k];
        if ((long) at + len > bytes.length) {
            long cap = Math.max((long) bytes.length * 2, (long) at + len);
            if (cap > Integer.MAX_VALUE - 15) {
                throw new StoreLimitException("string literals exceed 2 GiB");
            }
            byte[] bigger = Arrays.copyOf(bytes, (int) cap);
            bytes = bigger;
            bytesPub = bigger;
        }
        System.arraycopy(utf8, off, bytes, at, len);
        int scalars = 0;
        for (int i = 0; i < len; i++) {
            if ((utf8[off + i] & 0xC0) != 0x80) {
                scalars++;
            }
        }
        strOff[k + 1] = at + len;
        strCp[k] = scalars;
        strCount = k;
        strLive++;
        if (strLive * 2 > strTable.length) {
            int[] t = new int[strTable.length * 2];
            for (int e = 1; e <= k; e++) {
                if (strCp[e] >= 0) {
                    put(t, e, hashBytes(bytes, strOff[e], strOff[e + 1] - strOff[e]));
                }
            }
            strTable = t;
            strTablePub = t;
        } else {
            put(strTable, k, h);
        }
        return shared != null ? -k : k;
    }

    private int findString(byte[] src, int from, int len, int h, int[] t, int limit) {
        int[] off = strOffPub;
        byte[] b = bytesPub;
        int mask = t.length - 1;
        int i = h & mask;
        for (;;) {
            int e = t[i];
            if (e == 0) {
                return 0;
            }
            if (e <= limit) {
                int at = off[e];
                if (off[e + 1] - at == len) {
                    int j = 0;
                    while (j < len && b[at + j] == src[from + j]) {
                        j++;
                    }
                    if (j == len) {
                        return e;
                    }
                }
            }
            i = (i + 1) & mask;
        }
    }

    private static int hashBytes(byte[] b, int off, int len) {
        int h = 0x811C9DC5 ^ len;
        for (int i = 0; i < len; i++) {
            h = (h ^ b[off + i]) * 0x01000193;
        }
        return h ^ (h >>> 15);
    }

    public int stringByteLength(int index) {
        if (index < 0) {
            int k = -index;
            if (strCp[k] < 0) {
                throw released("string", index);
            }
            return strOff[k + 1] - strOff[k];
        }
        LitPool p = shared != null ? shared : this;
        int[] off = p.strOffPub;
        return off[index + 1] - off[index];
    }

    /** Copies the UTF-8 bytes of string {@code index} to {@code dst}. */
    public void stringBytes(int index, byte[] dst, int dstOff) {
        if (index < 0) {
            int k = -index;
            if (strCp[k] < 0) {
                throw released("string", index);
            }
            System.arraycopy(bytes, strOff[k], dst, dstOff, strOff[k + 1] - strOff[k]);
            return;
        }
        LitPool p = shared != null ? shared : this;
        int[] off = p.strOffPub;
        System.arraycopy(p.bytesPub, off[index], dst, dstOff, off[index + 1] - off[index]);
    }

    /** Number of Unicode scalar values (the length of the {@code List Char} the literal denotes). */
    public int stringCodePointCount(int index) {
        if (index < 0) {
            int n = strCp[-index];
            if (n < 0) {
                throw released("string", index);
            }
            return n;
        }
        LitPool p = shared != null ? shared : this;
        return p.strCpPub[index];
    }

    /** The {@code i}-th scalar value; O(1) after the first call for this string built its index. */
    public int stringCodePointAt(int index, int i) {
        LitPool p = index < 0 || shared == null ? this : shared;
        int k = index < 0 ? -index : index;
        int[] off = p.strOffPub;
        int at = off[k];
        int len = off[k + 1] - at;
        int scalars = p.strCpPub[k];
        if (i < 0 || i >= scalars) {
            if (scalars < 0) {
                throw released("string", index);
            }
            throw new IndexOutOfBoundsException("code point " + i + " of a string with " + scalars);
        }
        byte[] b = p.bytesPub;
        if (scalars == len) {
            return b[at + i];
        }
        int[] idx = p.codePointIndex(k, b, at, len, scalars);
        int o = at + idx[i];
        int b0 = b[o] & 0xFF;
        if (b0 < 0x80) {
            return b0;
        }
        if (b0 < 0xE0) {
            return ((b0 & 0x1F) << 6) | (b[o + 1] & 0x3F);
        }
        if (b0 < 0xF0) {
            return ((b0 & 0x0F) << 12) | ((b[o + 1] & 0x3F) << 6) | (b[o + 2] & 0x3F);
        }
        return ((b0 & 0x07) << 18) | ((b[o + 1] & 0x3F) << 12) | ((b[o + 2] & 0x3F) << 6) | (b[o + 3] & 0x3F);
    }

    /** The per-scalar byte offsets of entry {@code k}; built on first use, by whichever thread asks. */
    private int[] codePointIndex(int k, byte[] b, int at, int len, int scalars) {
        int[][] dir = cpIndex;
        if (k < dir.length) {
            int[] idx = (int[]) INT_ARRAYS.getAcquire(dir, k);
            if (idx != null) {
                return idx;
            }
        }
        int[] idx = new int[scalars];
        int n = 0;
        for (int i = 0; i < len; i++) {
            if ((b[at + i] & 0xC0) != 0x80) {
                idx[n++] = i;
            }
        }
        synchronized (this) {
            dir = cpIndex;
            if (k >= dir.length) {
                dir = Arrays.copyOf(dir, Math.max(dir.length * 2, k + 1));
                cpIndex = dir;
            }
            INT_ARRAYS.setRelease(dir, k, idx);
        }
        return idx;
    }

    public boolean stringEquals(int indexA, int indexB) {
        if (indexA == indexB) {
            return true;
        }
        int n = stringByteLength(indexA);
        if (n != stringByteLength(indexB)) {
            return false;
        }
        LitPool pa = indexA < 0 || shared == null ? this : shared;
        LitPool pb = indexB < 0 || shared == null ? this : shared;
        byte[] a = pa.bytesPub;
        byte[] b = pb.bytesPub;
        int oa = pa.strOffPub[Math.abs(indexA)];
        int ob = pb.strOffPub[Math.abs(indexB)];
        for (int i = 0; i < n; i++) {
            if (a[oa + i] != b[ob + i]) {
                return false;
            }
        }
        return true;
    }

    // ---- lifecycle ---------------------------------------------------------------------------------

    /**
     * Worker pools only: forgets every entry (session reset). Indices start again at -1: everything
     * that names a literal of this pool must be dropped with it.
     */
    public void reset() {
        if (shared == null) {
            throw new IllegalStateException("the shared literal pool is never reset");
        }
        if (natCount != 0) {
            natCount = 0;
            natLive = 0;
            if (natTable.length > 4096) {
                natTable = new int[32];
                natTablePub = natTable;
            } else {
                Arrays.fill(natTable, 0);
            }
            if (natLoc.length > 3 * 4096) {
                natLoc = new int[3 * 18];
                natLocPub = natLoc;
            }
            // keep one packing chunk, give everything else back
            long[] keep = cur >= 0 ? chunks[cur] : null;
            Arrays.fill(chunks, 0, chunkCount, null);
            chunkCount = 0;
            limbsAllocated = 0;
            cur = -1;
            fill = 0;
            if (keep != null) {
                cur = addChunk(keep);
            }
        }
        if (strCount != 0) {
            strCount = 0;
            strLive = 0;
            if (strTable.length > 4096) {
                strTable = new int[32];
                strTablePub = strTable;
            } else {
                Arrays.fill(strTable, 0);
            }
            cpIndex = new int[18][];
        }
    }

    /**
     * Worker pools: an opaque position for {@link #truncateTo(long)}: the highest natural and string
     * index handed out so far.
     */
    public long mark() {
        return ((long) strCount << 32) | natCount;
    }

    /**
     * Worker pools only: releases every entry added after {@code mark} and frees its memory; cost
     * proportional to the number of indices handed out since the mark. The indices of the released
     * entries stay retired until {@link #reset()}: a later entry never gets one of them, and reading
     * one throws. The caller should have dropped every term that mentions them (typically with
     * {@link Overlay#resetTo} to a mark taken at the same moment, which calls this method itself).
     * Truncating to a mark twice, or to marks out of order, is harmless.
     */
    public void truncateTo(long mark) {
        if (shared == null) {
            throw new IllegalStateException("the shared literal pool is never truncated");
        }
        int nats = (int) mark;
        int strs = (int) (mark >>> 32);
        if (nats < 0 || nats > natCount || strs < 0 || strs > strCount) {
            throw new IllegalArgumentException("mark is not from this session");
        }
        // Naturals. Live entries were entered into the index in ascending order and are removed in
        // descending order, so a removal never cuts the probe path of an entry that stays. Packed
        // (small) live entries lie in ascending positions, and nothing live lies behind the packing
        // position: it can go back to the first packed entry that is released.
        int mask = natTable.length - 1;
        for (int e = natCount; e > nats; e--) {
            int c = natLoc[3 * e];
            int at = natLoc[3 * e + 1];
            int len = natLoc[3 * e + 2];
            if (len != 0) {
                int i = hashLimbs(chunks[c], at, len) & mask;
                while (natTable[i] != e) {
                    i = (i + 1) & mask;
                }
                natTable[i] = 0;
                natLoc[3 * e + 2] = 0;
                natLive--;
                if (len >= BIG_LIMBS) {
                    limbsAllocated -= chunks[c].length;
                    chunks[c] = null;
                    natLoc[3 * e] = -1;
                    continue;
                }
            } else {
                continue; // released earlier
            }
            // a packed entry that is released now: the packing position goes back to where it starts
            if (c != cur) {
                if (cur >= 0 && chunks[cur] != null) {
                    limbsAllocated -= chunks[cur].length;
                    chunks[cur] = null;
                }
                cur = c;
            }
            fill = at;
            natLoc[3 * e] = -1;
        }
        while (chunkCount > 0 && chunks[chunkCount - 1] == null) {
            chunkCount--;
        }
        if (cur >= chunkCount) {
            cur = -1;
            fill = 0;
        }
        // Strings: a released entry keeps its index and becomes empty; the bytes behind the last
        // live entry are reused.
        mask = strTable.length - 1;
        int[][] dir = cpIndex;
        int end = strOff[strs + 1];
        for (int e = strCount; e > strs; e--) {
            if (strCp[e] >= 0) {
                int i = hashBytes(bytes, strOff[e], strOff[e + 1] - strOff[e]) & mask;
                while (strTable[i] != e) {
                    i = (i + 1) & mask;
                }
                strTable[i] = 0;
                strCp[e] = -1;
                strLive--;
                if (e < dir.length) {
                    dir[e] = null;
                }
            }
        }
        for (int e = strs + 1; e <= strCount; e++) {
            strOff[e + 1] = end;
        }
    }

    /** True if {@code mark} is a position this pool has reached (a mark taken since its last {@link #reset()}). */
    boolean covers(long mark) {
        int nats = (int) mark;
        int strs = (int) (mark >>> 32);
        return nats >= 0 && nats <= natCount && strs >= 0 && strs <= strCount;
    }

    /** Number of live naturals of this pool (released entries are not counted). */
    public int natLiveCount() {
        return natLive;
    }

    /** Number of live strings of this pool (released entries are not counted). */
    public int stringLiveCount() {
        return strLive;
    }

    public int natCount() {
        return natCount;
    }

    public int stringCount() {
        return strCount;
    }

    public long bytes() {
        return 8L * limbsAllocated + bytes.length + 4L * (natLoc.length + natTable.length + strOff.length
                + strCp.length + strTable.length);
    }

    /** Bytes in use: the limbs and string bytes of the entries, their rows, and both intern indices. */
    public long usedBytes() {
        long limbs = 0;
        for (int e = 1; e <= natCount; e++) {
            limbs += natLoc[3 * e + 2];
        }
        return 8L * limbs + strOff[strCount + 1] + 12L * natCount + 8L * strCount
                + 4L * (natTable.length + strTable.length);
    }
}
