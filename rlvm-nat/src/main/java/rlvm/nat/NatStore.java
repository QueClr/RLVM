package rlvm.nat;

import java.math.BigInteger;
import java.util.Arrays;

/**
 * Hash-consed store of the natural numbers that do not fit a non-negative {@code long}, i.e. values
 * {@code >= 2^63}. Smaller values are never stored: they are their own handle (see {@link Nat}).
 *
 * <h2>Representation</h2>
 * A value is a run of little-endian 64-bit limbs in one flat {@code long[]} slab; an id indexes parallel
 * {@code int[]} arrays (offset, length, hash). Id 0 is reserved as "none". A value is stored at most once
 * per view (base + overlay), so within one view <em>two big handles are equal iff the values are equal</em>.
 * No Java object exists per value and nothing is allocated per operation except when the slab or the
 * tables grow (and inside the documented {@code BigInteger} delegations: large products, general
 * division, gcd, general powers, long decimal conversion).
 *
 * <h2>Layers</h2>
 * A <em>base</em> store is filled by one thread (the loader) and then {@link #freeze() frozen}; from then on
 * it is immutable and may be read by any number of threads without synchronisation. Each worker creates its
 * own <em>overlay</em> with {@link #newOverlay()}: its ids start at the base's {@link #nextId()}, lookups
 * probe the base table first (read-only) and then the overlay's table, and new values go to the overlay.
 * An overlay is confined to one thread. {@link #mark()} / {@link #truncate(int)} release the most recent
 * values in LIFO order, {@link #reset()} drops the whole overlay; both are cheap (no per-value object, the
 * slab is reused). Handles of released values must not be used any more.
 *
 * <h2>Limits</h2>
 * No stored value is longer than {@link #HARD_MAX_BYTES} = 128 MiB, the default of the official kernel's
 * {@code LEAN_NAT_MAX_SIZE}. {@link #maxBytes()} (at most that) is the limit the size-checked operations
 * of {@link Nat} enforce by default.
 */
public final class NatStore {
    /** Default numeral size limit of the official kernel (v4.35+): 128 MiB. */
    public static final long OFFICIAL_MAX_BYTES = 128L << 20;
    /** Largest value this store can hold, in bytes (8 per limb). */
    public static final long HARD_MAX_BYTES = OFFICIAL_MAX_BYTES;
    static final int HARD_MAX_LIMBS = (int) (HARD_MAX_BYTES >>> 3);

    private static final int INIT_IDS = 16;
    private static final int INIT_LIMBS = 64;
    private static final int INIT_TABLE = 32;
    /** Capacities above which {@link #reset()} gives the memory back instead of keeping it. */
    private static final int KEEP_LIMBS = 1 << 16;
    private static final int KEEP_IDS = 1 << 12;
    private static final int MAX_ARRAY = Integer.MAX_VALUE - 16;

    private final NatStore base;
    /** First id owned by this layer: 1 for a base store, {@code base.nextId} for an overlay. */
    private final int firstId;
    private final long maxBytes;
    private final int maxLimbs;

    private int nextId;
    private boolean frozen;

    /** Per-id data, indexed by {@code id - firstId}. */
    private int[] offs;
    private int[] lens;
    private int[] hashes;

    /** Limb slab; {@code top} is the first free limb. Results are built at {@code top} before interning. */
    private long[] limbs;
    private int top;

    /** Open addressing, linear probing, load at most 1/2; entries are ids of this layer, 0 = empty. */
    private int[] table;
    private int mask;

    /** One-limb scratch operands for small values taking part in a big operation. */
    private final long[] scratchA = new long[1];
    private final long[] scratchB = new long[1];

    private NatStore(NatStore base, long maxBytes) {
        if (maxBytes < 8) {
            throw new IllegalArgumentException("maxBytes must be at least 8 (one machine word)");
        }
        this.base = base;
        this.firstId = base == null ? 1 : base.nextId;
        this.maxBytes = Math.min(maxBytes, HARD_MAX_BYTES);
        this.maxLimbs = (int) (this.maxBytes >>> 3);
        this.nextId = firstId;
        this.offs = new int[INIT_IDS];
        this.lens = new int[INIT_IDS];
        this.hashes = new int[INIT_IDS];
        this.limbs = new long[INIT_LIMBS];
        this.table = new int[INIT_TABLE];
        this.mask = INIT_TABLE - 1;
    }

    /** A new, empty, unfrozen base store with the official size limit. */
    public static NatStore newBase() {
        return new NatStore(null, OFFICIAL_MAX_BYTES);
    }

    /**
     * A new base store whose size-checked operations refuse results larger than {@code maxBytes} (the
     * official kernel's {@code LEAN_NAT_MAX_SIZE}; clamped to {@link #HARD_MAX_BYTES}, at least 8).
     */
    public static NatStore newBase(long maxBytes) {
        return new NatStore(null, maxBytes);
    }

    /** A new overlay on this frozen base store, with the same size limit. */
    public NatStore newOverlay() {
        return newOverlay(maxBytes);
    }

    /** A new overlay on this frozen base store with its own size limit. */
    public NatStore newOverlay(long maxBytes) {
        if (base != null) {
            throw new IllegalStateException("overlays cannot be stacked");
        }
        if (!frozen) {
            throw new IllegalStateException("the base store must be frozen before overlays are created");
        }
        return new NatStore(this, maxBytes);
    }

    /**
     * Freezes this store: any later attempt to add a value throws. Reads need no synchronisation afterwards,
     * provided the store is handed to the reading threads through a safe publication (thread start, final
     * field, volatile, lock).
     */
    public void freeze() {
        frozen = true;
    }

    public boolean isFrozen() {
        return frozen;
    }

    /** The base of an overlay, or null. */
    public NatStore base() {
        return base;
    }

    /** First id owned by this layer. */
    public int firstId() {
        return firstId;
    }

    /** One above the largest id in use; ids {@code 1 .. nextId() - 1} are valid in this view. */
    public int nextId() {
        return nextId;
    }

    /** Number of values owned by this layer. */
    public int count() {
        return nextId - firstId;
    }

    /** Size limit in bytes enforced by the size-checked operations of {@link Nat} on this store. */
    public long maxBytes() {
        return maxBytes;
    }

    /** Bytes of limb data owned by this layer (8 per limb); a measure of what the overlay retains. */
    public long bytesUsed() {
        return 8L * top;
    }

    // ------------------------------------------------------------------ release

    /** A mark for {@link #truncate(int)}: everything interned from now on can be released again. */
    public int mark() {
        return nextId;
    }

    /**
     * Releases every value of this layer with an id {@code >= mark}; the ids are reused by later values.
     * The caller guarantees that no handle of a released value is used afterwards.
     */
    public void truncate(int mark) {
        checkWritable();
        if (mark < firstId || mark > nextId) {
            throw new IllegalArgumentException("mark out of range: " + mark);
        }
        if (mark == nextId) {
            return;
        }
        // The table is always exactly what inserting the ids in ascending order produces (growTable re-inserts
        // in that order), so removing the youngest entries in descending order needs no tombstones.
        int[] t = table;
        int m = mask;
        for (int id = nextId - 1; id >= mark; id--) {
            int i = hashes[id - firstId] & m;
            while (t[i] != id) {
                i = (i + 1) & m;
            }
            t[i] = 0;
        }
        top = offs[mark - firstId];
        nextId = mark;
    }

    /** Releases every value of this layer. Large slabs and tables are given back to the garbage collector. */
    public void reset() {
        checkWritable();
        if (offs.length > KEEP_IDS) {
            offs = new int[INIT_IDS];
            lens = new int[INIT_IDS];
            hashes = new int[INIT_IDS];
            table = new int[INIT_TABLE];
            mask = INIT_TABLE - 1;
        } else if (8L * (nextId - firstId) < table.length) {
            truncate(firstId); // few entries in a larger table: clear just their slots
        } else {
            Arrays.fill(table, 0);
        }
        nextId = firstId;
        top = 0;
        if (limbs.length > KEEP_LIMBS) {
            limbs = new long[INIT_LIMBS];
        }
    }

    // ------------------------------------------------------------------ raw read access

    /** Number of 64-bit limbs of the value with the given id. */
    public int limbCount(int id) {
        checkId(id);
        return id < firstId ? base.lens[id - 1] : lens[id - firstId];
    }

    /** Limb {@code i} (little-endian, {@code 0 <= i < limbCount(id)}) of the value with the given id. */
    public long limb(int id, int i) {
        int n = limbCount(id);
        if (i < 0 || i >= n) {
            throw new IndexOutOfBoundsException(i);
        }
        return id < firstId ? base.limbs[base.offs[id - 1] + i] : limbs[offs[id - firstId] + i];
    }

    /** The hash under which the value with the given id is interned. */
    public int hashOf(int id) {
        checkId(id);
        return id < firstId ? base.hashes[id - 1] : hashes[id - firstId];
    }

    private void checkId(int id) {
        if (id <= 0 || id >= nextId) {
            throw new IllegalArgumentException("not a live NatStore id: " + id);
        }
    }

    private void checkWritable() {
        if (frozen) {
            throw new IllegalStateException("NatStore is frozen");
        }
    }

    /** Id of a big handle, validated. */
    private int idOf(long h) {
        int id = (int) h;
        if (id <= 0 || id >= nextId || (h & Nat.STATUS_MASK) != 0) {
            throw new IllegalArgumentException("not a live Nat handle: 0x" + Long.toHexString(h));
        }
        return id;
    }

    private long[] arrOf(int id) {
        return id < firstId ? base.limbs : limbs;
    }

    private int offOf(int id) {
        return id < firstId ? base.offs[id - 1] : offs[id - firstId];
    }

    private int lenOf(int id) {
        return id < firstId ? base.lens[id - 1] : lens[id - firstId];
    }

    /** Limb count of any value handle: 0 for zero, 1 for other small values. */
    private int limbLen(long h) {
        return h >= 0 ? (h == 0 ? 0 : 1) : lenOf(idOf(h));
    }

    // ------------------------------------------------------------------ allocation and interning

    /** Makes room for {@code n} limbs at {@code top}. Call before taking operand views. */
    private void reserve(int n) {
        checkWritable();
        long need = (long) top + n;
        if (need > limbs.length) {
            if (need > MAX_ARRAY) {
                throw new OutOfMemoryError("NatStore slab limit exceeded");
            }
            long cap = Math.max(need, Math.min(2L * limbs.length, MAX_ARRAY));
            limbs = Arrays.copyOf(limbs, (int) cap);
        }
    }

    /**
     * Turns the {@code len} limbs at {@code top} into a handle: strips zero top limbs, returns a small handle
     * if the value is below 2^63, otherwise the id of the equal stored value or of a newly committed one.
     */
    private long commit(int len) {
        long[] l = limbs;
        int t = top;
        while (len > 0 && l[t + len - 1] == 0) {
            len--;
        }
        if (len == 0) {
            return 0;
        }
        if (len == 1 && l[t] >= 0) {
            return l[t];
        }
        if (len > HARD_MAX_LIMBS) {
            throw new IllegalStateException("internal error: result exceeds the hard size limit");
        }
        int h = Limbs.hash(l, t, len);
        if (base != null) {
            int id = base.find(l, t, len, h);
            if (id != 0) {
                return Nat.BIG | id;
            }
        }
        int[] tab = table;
        int m = mask;
        int i = h & m;
        for (;;) {
            int id = tab[i];
            if (id == 0) {
                break;
            }
            int k = id - firstId;
            if (hashes[k] == h && lens[k] == len && Limbs.eq(l, offs[k], l, t, len)) {
                return Nat.BIG | id;
            }
            i = (i + 1) & m;
        }
        int id = nextId;
        if (id == Integer.MAX_VALUE) {
            throw new OutOfMemoryError("NatStore id space exhausted");
        }
        int k = id - firstId;
        if (k == offs.length) {
            int cap = k < (MAX_ARRAY >> 1) ? 2 * k : MAX_ARRAY;
            offs = Arrays.copyOf(offs, cap);
            lens = Arrays.copyOf(lens, cap);
            hashes = Arrays.copyOf(hashes, cap);
        }
        offs[k] = t;
        lens[k] = len;
        hashes[k] = h;
        top = t + len;
        nextId = id + 1;
        tab[i] = id;
        if (2 * (k + 1) > tab.length) {
            growTable();
        }
        return Nat.BIG | id;
    }

    /** {@link #commit} for the operations whose result the official kernel size-checks. */
    private long commitChecked(int len) {
        long[] l = limbs;
        int t = top;
        while (len > 0 && l[t + len - 1] == 0) {
            len--;
        }
        if (len > maxLimbs) {
            return Nat.TOO_BIG;
        }
        return commit(len);
    }

    /** Read-only lookup in this layer's table; safe on a frozen store from any thread. */
    private int find(long[] a, int off, int len, int h) {
        int[] tab = table;
        int m = mask;
        int i = h & m;
        for (;;) {
            int id = tab[i];
            if (id == 0) {
                return 0;
            }
            int k = id - firstId;
            if (hashes[k] == h && lens[k] == len && Limbs.eq(limbs, offs[k], a, off, len)) {
                return id;
            }
            i = (i + 1) & m;
        }
    }

    private void growTable() {
        int cap = table.length << 1;
        if (cap <= 0) {
            throw new OutOfMemoryError("NatStore table limit exceeded");
        }
        int[] t = new int[cap];
        int m = cap - 1;
        for (int id = firstId; id < nextId; id++) {
            int i = hashes[id - firstId] & m;
            while (t[i] != 0) {
                i = (i + 1) & m;
            }
            t[i] = id;
        }
        table = t;
        mask = m;
    }

    /** Interns the one-limb value {@code x} read as unsigned (small handle if below 2^63). */
    long internUnsigned(long x) {
        if (x >= 0) {
            return x;
        }
        reserve(1);
        limbs[top] = x;
        return commit(1);
    }

    // ------------------------------------------------------------------ conversions

    /** The value of a handle as a {@code BigInteger} (allocates; for delegations, tests and diagnostics). */
    BigInteger toBigInteger(long h) {
        if (h >= 0) {
            return BigInteger.valueOf(h);
        }
        int id = idOf(h);
        return Limbs.toBigInteger(arrOf(id), offOf(id), lenOf(id));
    }

    /** Handle of a non-negative {@code BigInteger}; {@link Nat#TOO_BIG} above the hard limit. */
    long ofBigInteger(BigInteger v) {
        if (v.signum() < 0) {
            throw new IllegalArgumentException("negative value");
        }
        if (v.bitLength() < 64) {
            return v.longValue();
        }
        int n = Limbs.limbCount(v);
        if (n > HARD_MAX_LIMBS) {
            return Nat.TOO_BIG;
        }
        reserve(n);
        return commit(Limbs.fromBigInteger(v, limbs, top));
    }

    /** Copies a value of another store (any view) into this one. */
    long importFrom(NatStore src, long h) {
        if (h >= 0 || Nat.isError(h)) {
            return h;
        }
        int id = src.idOf(h);
        int n = src.lenOf(id);
        reserve(n);
        System.arraycopy(src.arrOf(id), src.offOf(id), limbs, top, n);
        return commit(n);
    }

    // ------------------------------------------------------------------ read-only queries (frozen-safe)

    int compareSlow(long a, long b) {
        if (a >= 0) {
            if (b >= 0) {
                return Long.compare(a, b);
            }
            idOf(b);
            return -1;
        }
        int ia = idOf(a);
        if (b >= 0) {
            return 1;
        }
        int ib = idOf(b);
        if (ia == ib) {
            return 0;
        }
        return Limbs.cmp(arrOf(ia), offOf(ia), lenOf(ia), arrOf(ib), offOf(ib), lenOf(ib));
    }

    /** Validates that a handle is a number: small, or a live big value of this view (throws otherwise). */
    void requireValue(long h) {
        if (h < 0) {
            idOf(h);
        }
    }

    /** {@link #requireValue} for two handles at least one of which is not small. */
    void requireValues(long a, long b) {
        if (a < 0) {
            idOf(a);
        }
        if (b < 0) {
            idOf(b);
        }
    }

    long bitLengthSlow(long h) {
        int id = idOf(h);
        return Limbs.bitLength(arrOf(id), offOf(id), lenOf(id));
    }

    long sizeInBytesSlow(long h) {
        return 8L * lenOf(idOf(h));
    }

    /** Lowest limb of a big value. */
    private long limb0(long h) {
        int id = idOf(h);
        return arrOf(id)[offOf(id)];
    }

    // ------------------------------------------------------------------ slow paths of the operations
    // Every method accepts any pair of handles (small or big), so it is complete on its own; the callers in
    // Nat only add the inlined long fast paths. Error handles are propagated (first operand first).

    long addSlow(long a, long b) {
        if (Nat.isError(a)) {
            return a;
        }
        if (Nat.isError(b)) {
            return b;
        }
        int an = limbLen(a);
        int bn = limbLen(b);
        if (an < bn) {
            long t = a;
            a = b;
            b = t;
            an = bn;
        }
        reserve(an + 1);
        long[] xa;
        int xo;
        if (a >= 0) {
            scratchA[0] = a;
            xa = scratchA;
            xo = 0;
        } else {
            int id = (int) a;
            xa = arrOf(id);
            xo = offOf(id);
        }
        long[] ya;
        int yo;
        int yn;
        if (b >= 0) {
            scratchB[0] = b;
            ya = scratchB;
            yo = 0;
            yn = b == 0 ? 0 : 1;
        } else {
            int id = (int) b;
            ya = arrOf(id);
            yo = offOf(id);
            yn = lenOf(id);
        }
        return commitChecked(Limbs.add(xa, xo, an, ya, yo, yn, limbs, top));
    }

    long subSlow(long a, long b) {
        if (Nat.isError(a)) {
            return a;
        }
        if (Nat.isError(b)) {
            return b;
        }
        if (a >= 0) {
            if (b >= 0) {
                return a > b ? a - b : 0;
            }
            idOf(b);
            return 0;
        }
        int ia = idOf(a);
        int an = lenOf(ia);
        long[] ya;
        int yo;
        int yn;
        if (b >= 0) {
            reserve(an);
            scratchB[0] = b;
            ya = scratchB;
            yo = 0;
            yn = b == 0 ? 0 : 1;
        } else {
            int ib = idOf(b);
            if (ia == ib || Limbs.cmp(arrOf(ia), offOf(ia), an, arrOf(ib), offOf(ib), lenOf(ib)) <= 0) {
                return 0;
            }
            reserve(an);
            ya = arrOf(ib);
            yo = offOf(ib);
            yn = lenOf(ib);
        }
        return commitChecked(Limbs.sub(arrOf(ia), offOf(ia), an, ya, yo, yn, limbs, top));
    }

    /**
     * Length in limbs of the shorter operand from which a product is computed by {@code BigInteger}: its
     * Karatsuba threshold (80 ints). Below it the row-skipping schoolbook kernel is used. Measured
     * (bench/modules/rlvm-nat, instructions per product of two n-limb numbers): the kernel costs about 22 n^2,
     * {@code BigInteger} about 13 n^2 plus roughly 400 n for the conversions through byte arrays; the curves
     * cross near n = 50, and zero limbs make the kernel cheaper still.
     */
    static final int MUL_DELEGATE_MIN = 40;

    long mulSlow(long a, long b) {
        if (Nat.isError(a)) {
            return a;
        }
        if (Nat.isError(b)) {
            return b;
        }
        int an = limbLen(a);
        int bn = limbLen(b);
        if (an == 0 || bn == 0) {
            return 0;
        }
        if (an < bn) {
            long t = a;
            a = b;
            b = t;
            int n = an;
            an = bn;
            bn = n;
        }
        // The product has an + bn or an + bn - 1 limbs: refuse before computing what cannot fit.
        if ((long) an + bn - 1 > maxLimbs) {
            return Nat.TOO_BIG;
        }
        reserve(an + bn);
        long[] xa;
        int xo;
        if (a >= 0) {
            scratchA[0] = a;
            xa = scratchA;
            xo = 0;
        } else {
            int id = (int) a;
            xa = arrOf(id);
            xo = offOf(id);
        }
        if (bn == 1) {
            long m = b >= 0 ? b : arrOf((int) b)[offOf((int) b)];
            return commitChecked(Limbs.mul1(xa, xo, an, m, limbs, top));
        }
        int ib = (int) b;
        long[] ya = arrOf(ib);
        int yo = offOf(ib);
        if (bn >= MUL_DELEGATE_MIN) {
            BigInteger p = Limbs.toBigInteger(xa, xo, an).multiply(Limbs.toBigInteger(ya, yo, bn));
            return commitChecked(Limbs.fromBigInteger(p, limbs, top));
        }
        return commitChecked(Limbs.mul(xa, xo, an, ya, yo, bn, limbs, top));
    }

    long divSlow(long a, long b) {
        if (Nat.isError(a)) {
            return a;
        }
        if (Nat.isError(b)) {
            return b;
        }
        if (b == 0) {
            requireValue(a);
            return 0;
        }
        if (a >= 0) {
            if (b >= 0) {
                return a / b;
            }
            idOf(b);
            return 0;
        }
        int ia = idOf(a);
        int an = lenOf(ia);
        if (b >= 0 || lenOf(idOf(b)) == 1) {
            long d = b >= 0 ? b : limb0(b);
            if ((d & (d - 1)) == 0) {
                return shiftRightSlow(a, Long.numberOfTrailingZeros(d));
            }
            reserve(an);
            Limbs.divRem1(arrOf(ia), offOf(ia), an, d, limbs, top);
            return commit(an);
        }
        int ib = (int) b;
        int bn = lenOf(ib);
        int c = ia == ib ? 0 : Limbs.cmp(arrOf(ia), offOf(ia), an, arrOf(ib), offOf(ib), bn);
        if (c <= 0) {
            return c == 0 ? 1 : 0;
        }
        if (Limbs.isPow2(arrOf(ib), offOf(ib), bn)) {
            return shiftRightSlow(a, Limbs.bitLength(arrOf(ib), offOf(ib), bn) - 1);
        }
        BigInteger q = Limbs.toBigInteger(arrOf(ia), offOf(ia), an).divide(Limbs.toBigInteger(arrOf(ib), offOf(ib), bn));
        return ofBigInteger(q);
    }

    long modSlow(long a, long b) {
        if (Nat.isError(a)) {
            return a;
        }
        if (Nat.isError(b)) {
            return b;
        }
        if (b == 0) {
            requireValue(a);
            return a;
        }
        if (a >= 0) {
            if (b >= 0) {
                return a % b;
            }
            idOf(b);
            return a;
        }
        int ia = idOf(a);
        int an = lenOf(ia);
        if (b >= 0 || lenOf(idOf(b)) == 1) {
            long d = b >= 0 ? b : limb0(b);
            if ((d & (d - 1)) == 0) {
                return internUnsigned(arrOf(ia)[offOf(ia)] & (d - 1));
            }
            return internUnsigned(Limbs.divRem1(arrOf(ia), offOf(ia), an, d, null, 0));
        }
        int ib = (int) b;
        int bn = lenOf(ib);
        int c = ia == ib ? 0 : Limbs.cmp(arrOf(ia), offOf(ia), an, arrOf(ib), offOf(ib), bn);
        if (c <= 0) {
            return c == 0 ? 0 : a;
        }
        if (Limbs.isPow2(arrOf(ib), offOf(ib), bn)) {
            // a mod 2^k: the low k bits of a; k = 64 * (bn - 1) + tz(top limb of b)
            int tz = Long.numberOfTrailingZeros(arrOf(ib)[offOf(ib) + bn - 1]);
            int n = tz == 0 ? bn - 1 : bn;
            reserve(n);
            System.arraycopy(arrOf(ia), offOf(ia), limbs, top, n);
            if (tz != 0) {
                limbs[top + n - 1] &= (1L << tz) - 1;
            }
            return commit(n);
        }
        BigInteger r = Limbs.toBigInteger(arrOf(ia), offOf(ia), an).mod(Limbs.toBigInteger(arrOf(ib), offOf(ib), bn));
        return ofBigInteger(r);
    }

    long gcdSlow(long a, long b) {
        if (Nat.isError(a)) {
            return a;
        }
        if (Nat.isError(b)) {
            return b;
        }
        requireValue(a);
        requireValue(b);
        if (a == 0) {
            return b;
        }
        if (b == 0) {
            return a;
        }
        if (a >= 0 && b >= 0) {
            return Nat.gcdLong(a, b);
        }
        if (a >= 0 || b >= 0) {
            // gcd(big, small) = gcd(small, big mod small)
            long small = a >= 0 ? a : b;
            int ig = (int) (a >= 0 ? b : a);
            long r = Limbs.divRem1(arrOf(ig), offOf(ig), lenOf(ig), small, null, 0);
            return Nat.gcdLong(small, r);
        }
        int ia = (int) a;
        int ib = (int) b;
        if (ia == ib) {
            return a;
        }
        BigInteger g = Limbs.toBigInteger(arrOf(ia), offOf(ia), lenOf(ia))
                .gcd(Limbs.toBigInteger(arrOf(ib), offOf(ib), lenOf(ib)));
        return ofBigInteger(g);
    }

    long landSlow(long a, long b) {
        if (Nat.isError(a)) {
            return a;
        }
        if (Nat.isError(b)) {
            return b;
        }
        if (a >= 0) {
            return b >= 0 ? a & b : a & limb0(b);
        }
        if (b >= 0) {
            return b & limb0(a);
        }
        int ia = idOf(a);
        int ib = idOf(b);
        if (ia == ib) {
            return a;
        }
        int n = Math.min(lenOf(ia), lenOf(ib));
        reserve(n);
        return commit(Limbs.and(arrOf(ia), offOf(ia), lenOf(ia), arrOf(ib), offOf(ib), lenOf(ib), limbs, top));
    }

    /** Shared code of lor and xor: both copy the longer operand's tail. */
    private long orXorSlow(long a, long b, boolean isXor) {
        if (Nat.isError(a)) {
            return a;
        }
        if (Nat.isError(b)) {
            return b;
        }
        if (a >= 0 && b >= 0) {
            return isXor ? a ^ b : a | b;
        }
        if (a >= 0) {
            long t = a;
            a = b;
            b = t;
        }
        // a is big now
        int an = lenOf(idOf(a));
        int bn = limbLen(b);
        if (an < bn) {
            long t = a;
            a = b;
            b = t;
            int n = an;
            an = bn;
            bn = n;
        }
        if (a == b) {
            return isXor ? 0 : a;
        }
        reserve(an);
        int ia = (int) a;
        long[] ya;
        int yo;
        if (b >= 0) {
            scratchB[0] = b;
            ya = scratchB;
            yo = 0;
        } else {
            ya = arrOf((int) b);
            yo = offOf((int) b);
        }
        int n = isXor
                ? Limbs.xor(arrOf(ia), offOf(ia), an, ya, yo, bn, limbs, top)
                : Limbs.or(arrOf(ia), offOf(ia), an, ya, yo, bn, limbs, top);
        return commit(n);
    }

    long lorSlow(long a, long b) {
        return orXorSlow(a, b, false);
    }

    long xorSlow(long a, long b) {
        return orXorSlow(a, b, true);
    }

    /** Official guard of {@code Nat.shiftLeft}: {@link Nat#OK}, {@link Nat#COUNT_TOO_BIG} or {@link Nat#TOO_BIG}. */
    long shiftLeftGuard(long a, long b, long limitBytes) {
        if (Nat.isError(a)) {
            return a;
        }
        if (Nat.isError(b)) {
            return b;
        }
        requireValue(b);
        int an = limbLen(a);
        if (an == 0) {
            return Nat.OK; // 0 <<< b = 0 for any b, also when b does not fit 32 bits
        }
        if (b < 0 || b > 0xFFFFFFFFL) {
            return Nat.COUNT_TOO_BIG;
        }
        if (8L * an + (b >>> 3) + 1 > Math.min(limitBytes, HARD_MAX_BYTES)) {
            return Nat.TOO_BIG;
        }
        return Nat.OK;
    }

    long shiftLeftSlow(long a, long b, long limitBytes) {
        long g = shiftLeftGuard(a, b, limitBytes);
        if (g != Nat.OK) {
            return g;
        }
        if (a == 0) {
            return 0;
        }
        int an = limbLen(a);
        int ls = (int) (b >>> 6);
        int bs = (int) (b & 63);
        reserve(an + ls + 1);
        long[] xa;
        int xo;
        if (a >= 0) {
            scratchA[0] = a;
            xa = scratchA;
            xo = 0;
        } else {
            xa = arrOf((int) a);
            xo = offOf((int) a);
        }
        return commit(Limbs.shl(xa, xo, an, ls, bs, limbs, top));
    }

    long shiftRightSlow(long a, long b) {
        if (Nat.isError(a)) {
            return a;
        }
        if (Nat.isError(b)) {
            return b;
        }
        requireValue(a);
        requireValue(b);
        if (b < 0) {
            return 0; // a shift count of 2^63 or more clears any representable value
        }
        if (a >= 0) {
            return b < 64 ? a >>> b : 0;
        }
        int ia = (int) a;
        int an = lenOf(ia);
        if (b >= Limbs.bitLength(arrOf(ia), offOf(ia), an)) {
            return 0;
        }
        if (b == 0) {
            return a;
        }
        int ls = (int) (b >>> 6);
        int bs = (int) (b & 63);
        reserve(an - ls);
        return commit(Limbs.shr(arrOf(ia), offOf(ia), an, ls, bs, limbs, top));
    }

    /** Official guard of {@code Nat.pow}: {@link Nat#OK}, {@link Nat#COUNT_TOO_BIG} or {@link Nat#TOO_BIG}. */
    long powGuard(long a, long b, long limitBytes) {
        if (Nat.isError(a)) {
            return a;
        }
        if (Nat.isError(b)) {
            return b;
        }
        requireValue(a);
        requireValue(b);
        if (b < 0 || b > 0xFFFFFFFFL) {
            return Nat.COUNT_TOO_BIG; // official: checked first, also for bases 0 and 1
        }
        if ((a < 0 || a > 1) && b != 0) {
            long bytes = a >= 0 ? 8 : 8L * lenOf((int) a);
            if (bytes > Math.min(limitBytes, HARD_MAX_BYTES) / b) {
                return Nat.TOO_BIG;
            }
        }
        return Nat.OK;
    }

    long powSlow(long a, long b, long limitBytes) {
        long g = powGuard(a, b, limitBytes);
        if (g != Nat.OK) {
            return g;
        }
        if (b == 0) {
            return 1;
        }
        if (b == 1) {
            return a;
        }
        if (a >= 0) {
            if (a <= 1) {
                return a;
            }
            int bits = 64 - Long.numberOfLeadingZeros(a);
            if (bits * b <= 63) {
                long r = 1;
                long x = a;
                for (long e = b;;) {
                    if ((e & 1) != 0) {
                        r *= x;
                    }
                    e >>>= 1;
                    if (e == 0) {
                        return r;
                    }
                    x *= x;
                }
            }
            if ((a & (a - 1)) == 0) {
                // (2^t)^b = 2^(t*b); the guard bounds t*b by 8 * limit
                long shift = Long.numberOfTrailingZeros(a) * b;
                int n = (int) (shift >>> 6) + 1;
                reserve(n);
                Arrays.fill(limbs, top, top + n - 1, 0L);
                limbs[top + n - 1] = 1L << (shift & 63);
                return commit(n);
            }
        }
        // The guard gives b <= limit / 8 <= 2^24 and a result of at most `limit` bytes.
        return ofBigInteger(toBigInteger(a).pow((int) b));
    }

    long succSlow(long a) {
        if (Nat.isError(a)) {
            return a;
        }
        if (a >= 0) {
            return a != Long.MAX_VALUE ? a + 1 : internUnsigned(Long.MIN_VALUE);
        }
        return addSlow(a, 1);
    }

    long predSlow(long a) {
        if (Nat.isError(a)) {
            return a;
        }
        if (a >= 0) {
            return a == 0 ? 0 : a - 1;
        }
        int ia = idOf(a);
        int an = lenOf(ia);
        reserve(an);
        scratchB[0] = 1;
        return commit(Limbs.sub(arrOf(ia), offOf(ia), an, scratchB, 0, 1, limbs, top));
    }

    // ------------------------------------------------------------------ decimal conversion

    /** Decimal digit chunks of 18 digits fit a long: 10^18 &lt; 2^63. */
    private static final long TEN18 = 1_000_000_000_000_000_000L;
    /** Above this many digits parsing switches from the quadratic limb loop to divide and conquer. */
    static final int PARSE_DC_DIGITS = 1200;

    /**
     * Parses the decimal digits {@code buf[from .. to)} (all validated by the caller, no leading zero,
     * at least 19 of them). Returns a handle or {@link Nat#TOO_BIG}.
     */
    long parseDigits(byte[] buf, int from, int to) {
        int digits = to - from;
        // A d-digit numeral is at least 10^(d-1) > 2^(3.3219 (d-1)): refuse what cannot fit before converting.
        if (digits - 1 > (64L * HARD_MAX_LIMBS) * 30103L / 100000L + 1) {
            return Nat.TOO_BIG;
        }
        if (digits > PARSE_DC_DIGITS) {
            return ofBigInteger(parseBig(buf, from, to));
        }
        int maxLimbsNeeded = digits / 19 + 2; // 19 digits always fit one limb
        reserve(maxLimbsNeeded);
        long[] l = limbs;
        int t = top;
        int n = 0;
        int p = from;
        int first = digits % 18;
        if (first == 0) {
            first = 18;
        }
        long chunk = chunk(buf, p, p + first);
        p += first;
        if (chunk != 0) {
            l[t] = chunk;
            n = 1;
        }
        while (p < to) {
            chunk = chunk(buf, p, p + 18);
            p += 18;
            long carry = Limbs.mulAdd1InPlace(l, t, n, TEN18, chunk);
            if (carry != 0) {
                l[t + n] = carry;
                n++;
            }
        }
        return commit(n);
    }

    private static long chunk(byte[] buf, int from, int to) {
        long v = 0;
        for (int i = from; i < to; i++) {
            v = v * 10 + (buf[i] - '0');
        }
        return v;
    }

    /** Divide and conquer: value = high * 10^(18 * 2^k) + low, with BigInteger's sub-quadratic products. */
    private static BigInteger parseBig(byte[] buf, int from, int to) {
        int digits = to - from;
        int levels = 0;
        while ((18L << (levels + 1)) < digits) {
            levels++;
        }
        BigInteger[] pow = new BigInteger[levels + 1]; // pow[i] = 10^(18 * 2^i)
        pow[0] = BigInteger.valueOf(TEN18);
        for (int i = 1; i <= levels; i++) {
            pow[i] = pow[i - 1].multiply(pow[i - 1]);
        }
        return parseBig(buf, from, to, levels, pow);
    }

    private static BigInteger parseBig(byte[] buf, int from, int to, int level, BigInteger[] pow) {
        int digits = to - from;
        if (digits <= 18) {
            return BigInteger.valueOf(chunk(buf, from, to));
        }
        while ((18L << level) >= digits) {
            level--;
        }
        int lowDigits = 18 << level;
        BigInteger hi = parseBig(buf, from, to - lowDigits, level, pow);
        BigInteger lo = parseBig(buf, to - lowDigits, to, level, pow);
        return hi.multiply(pow[level]).add(lo);
    }
}
