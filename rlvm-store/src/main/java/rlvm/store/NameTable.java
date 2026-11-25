package rlvm.store;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Hierarchical Lean names, interned structurally: two ids are equal iff they denote the same name,
 * whatever ids the export used. Id 0 is the anonymous name.
 *
 * <pre>
 * per name, 5 ints (20 bytes):
 *   w0 prefix name id
 *   w1 bit 0: 0 = string component, 1 = numeric | bit 1: root component is "_nested"
 *      | bit 2: root component is "_private" | bit 3: numeric component of 2^64 or more, kept as
 *      decimal digits | bits 8..31: byte length of a string component (or of the digits)
 *   w2 string: offset into the UTF-8 pool        numeric: low 32 bits of the value
 *   w3 string: 32-bit hash of the component      numeric: high 32 bits of the value
 *   w4 constOf: constant id declared under this name; 0 = none; negative = {@link #DROPPED_UNSAFE} etc.
 * </pre>
 *
 * One writer at a time: the loader thread until the load is complete, then the environment thread
 * (late names and claims of generated constants: {@link GeneratedBuilder}). Published name records
 * are immutable. The {@code constOf} word is the flat-namespace guard: a second declaration of a
 * name fails (*agentic-note*: design-architecture-document, 4.2); with one writer at a time {@link #claim} is a plain test-and-set.
 *
 * <p>Readers on other threads go through a volatile array reference per call; the intern index is
 * the writer's alone ({@link #findStr} is for the thread that currently writes).
 */
public final class NameTable {

    public static final int ANONYMOUS = 0;
    /** {@code constOf} marker: an unsafe or partial record with this name was dropped at load time. */
    public static final int DROPPED_UNSAFE = -1;

    private static final int W = 5;
    private static final int NUMERIC = 1;
    private static final int NESTED_ROOT = 2;
    private static final int PRIVATE_ROOT = 4;
    private static final int BIG = 8;
    private static final int ROOT_BITS = NESTED_ROOT | PRIVATE_ROOT;
    private static final int MAX_COMPONENT = (1 << 24) - 1;
    private static final byte[] NESTED = "_nested".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PRIVATE = "_private".getBytes(StandardCharsets.US_ASCII);

    private int[] rows;
    private volatile int[] rowsPub;
    /** Next id. */
    private int count = 1;
    private byte[] pool;
    private volatile byte[] poolPub;
    private int poolUsed;
    /** Open addressing over name ids; 0 = empty. Load at most 0.5. */
    private int[] table;

    public NameTable(int expectedNames) {
        int n = Math.max(expectedNames, 64);
        this.rows = new int[(int) Math.min((long) (n + 1) * W, Integer.MAX_VALUE - 15)];
        this.rowsPub = rows;
        this.pool = new byte[(int) Math.min((long) n * 8, 1 << 28)];
        this.poolPub = pool;
        int cap = Integer.highestOneBit(Math.max(n, 32) - 1) << 2;
        this.table = new int[cap > 0 ? cap : 1 << 30];
    }

    // ---- interning (loader thread) -----------------------------------------------------------------

    /** Interns {@code prefix.str}; the component is the raw UTF-8 of the decoded JSON string. */
    public int internStr(int prefix, byte[] utf8, int off, int len) {
        return internBytes(prefix, utf8, off, len, 0, true);
    }

    /** Interns {@code prefix.num} for an unsigned 64-bit component. */
    public int internNum(int prefix, long unsignedValue) {
        checkName(prefix);
        int lo = (int) unsignedValue;
        int hi = (int) (unsignedValue >>> 32);
        int h = mix(prefix, lo * 0x9E3779B1 ^ hi * 0x85EBCA6B ^ 0x5bd1e995);
        int[] t = table;
        int mask = t.length - 1;
        int slot = h & mask;
        int[] r = rows;
        for (;;) {
            int id = t[slot];
            if (id == 0) {
                break;
            }
            int w = id * W;
            if (r[w] == prefix && (r[w + 1] & (NUMERIC | BIG)) == NUMERIC && r[w + 2] == lo && r[w + 3] == hi) {
                return id;
            }
            slot = (slot + 1) & mask;
        }
        int id = newRow(prefix, NUMERIC | rootBits(prefix, null, 0, 0), lo, hi);
        addToTable(id, slot);
        return id;
    }

    /**
     * Interns {@code prefix.num} for a numeric component given as decimal digits. A value below 2^64
     * (after dropping leading zeros) is the same name as {@link #internNum} would give.
     */
    public int internNumBig(int prefix, byte[] digits, int off, int len) {
        while (len > 1 && digits[off] == '0') {
            off++;
            len--;
        }
        if (len == 0) {
            throw new IllegalArgumentException("empty numeral");
        }
        for (int i = 0; i < len; i++) {
            if (digits[off + i] < '0' || digits[off + i] > '9') {
                throw new IllegalArgumentException("not a decimal numeral");
            }
        }
        if (len <= 20) {
            long v = 0;
            boolean fits = true;
            for (int i = 0; i < len; i++) {
                int dgt = digits[off + i] - '0';
                // v * 10 + dgt <= 2^64 - 1, in unsigned arithmetic
                if (Long.compareUnsigned(v, 1844674407370955161L) > 0
                        || (v == 1844674407370955161L && dgt > 5)) {
                    fits = false;
                    break;
                }
                v = v * 10 + dgt;
            }
            if (fits) {
                return internNum(prefix, v);
            }
        }
        return internBytes(prefix, digits, off, len, NUMERIC | BIG, true);
    }

    /** Lookup without insertion; 0 if the name was never interned. Only for the thread that currently writes names. */
    public int findStr(int prefix, byte[] utf8, int off, int len) {
        return internBytes(prefix, utf8, off, len, 0, false);
    }

    private int internBytes(int prefix, byte[] b, int off, int len, int kindBits, boolean insert) {
        checkName(prefix);
        if (len > MAX_COMPONENT) {
            throw new StoreLimitException("name component longer than 2^24 - 1 bytes");
        }
        int ch = hashBytes(b, off, len);
        int h = mix(prefix, ch + kindBits);
        int[] t = table;
        int mask = t.length - 1;
        int slot = h & mask;
        int[] r = rows;
        byte[] p = pool;
        int want = kindBits | (len << 8);
        for (;;) {
            int id = t[slot];
            if (id == 0) {
                break;
            }
            int w = id * W;
            if (r[w] == prefix && r[w + 3] == ch && (r[w + 1] & ~ROOT_BITS) == want) {
                int at = r[w + 2];
                int i = 0;
                while (i < len && p[at + i] == b[off + i]) {
                    i++;
                }
                if (i == len) {
                    return id;
                }
            }
            slot = (slot + 1) & mask;
        }
        if (!insert) {
            return 0;
        }
        if ((long) poolUsed + len > pool.length) {
            long cap = Math.max((long) pool.length * 2, (long) poolUsed + len);
            if (cap > Integer.MAX_VALUE - 15) {
                throw new StoreLimitException("name strings exceed 2 GiB");
            }
            byte[] bigger = Arrays.copyOf(pool, (int) cap);
            pool = bigger;
            poolPub = bigger;
        }
        System.arraycopy(b, off, pool, poolUsed, len);
        int at = poolUsed;
        poolUsed += len;
        int bits = kindBits | (kindBits == 0 ? rootBits(prefix, b, off, len) : rootBits(prefix, null, 0, 0));
        int id = newRow(prefix, bits | (len << 8), at, ch);
        addToTable(id, slot);
        return id;
    }

    private int rootBits(int prefix, byte[] b, int off, int len) {
        if (prefix != ANONYMOUS) {
            return rows[prefix * W + 1] & ROOT_BITS;
        }
        if (b == null) {
            return 0;
        }
        if (equalsAscii(b, off, len, NESTED)) {
            return NESTED_ROOT;
        }
        if (equalsAscii(b, off, len, PRIVATE)) {
            return PRIVATE_ROOT;
        }
        return 0;
    }

    private static boolean equalsAscii(byte[] b, int off, int len, byte[] s) {
        if (len != s.length) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            if (b[off + i] != s[i]) {
                return false;
            }
        }
        return true;
    }

    private int newRow(int prefix, int w1, int w2, int w3) {
        int id = count;
        int w = id * W;
        if (w + W > rows.length) {
            long cap = Math.max((long) rows.length * 2, w + W);
            if (cap > Integer.MAX_VALUE - 15) {
                cap = Integer.MAX_VALUE - 15;
                if (cap < w + W) {
                    throw new StoreLimitException("too many names");
                }
            }
            int[] bigger = Arrays.copyOf(rows, (int) cap);
            rows = bigger;
            rowsPub = bigger;
        }
        int[] r = rows;
        r[w] = prefix;
        r[w + 1] = w1;
        r[w + 2] = w2;
        r[w + 3] = w3;
        r[w + 4] = 0;
        count = id + 1;
        return id;
    }

    private void addToTable(int id, int slot) {
        if ((long) id * 2 > table.length) {
            if (table.length >= 1 << 30) {
                throw new StoreLimitException("too many names");
            }
            int[] t = new int[table.length * 2];
            int mask = t.length - 1;
            int[] r = rows;
            for (int k = 1; k <= id; k++) {
                int w = k * W;
                int h;
                if ((r[w + 1] & (NUMERIC | BIG)) == NUMERIC) {
                    h = mix(r[w], r[w + 2] * 0x9E3779B1 ^ r[w + 3] * 0x85EBCA6B ^ 0x5bd1e995);
                } else {
                    h = mix(r[w], r[w + 3] + (r[w + 1] & (NUMERIC | BIG)));
                }
                int s = h & mask;
                while (t[s] != 0) {
                    s = (s + 1) & mask;
                }
                t[s] = k;
            }
            table = t;
        } else {
            table[slot] = id;
        }
    }

    private static int hashBytes(byte[] b, int off, int len) {
        int h = 0x811C9DC5 ^ len;
        for (int i = 0; i < len; i++) {
            h = (h ^ b[off + i]) * 0x01000193;
        }
        return h ^ (h >>> 15);
    }

    private static int mix(int prefix, int h) {
        int x = prefix * 0x9E3779B1 + h;
        x ^= x >>> 16;
        x *= 0x7FEB352D;
        x ^= x >>> 15;
        return x;
    }

    private void checkName(int name) {
        if (name < 0 || name >= count) {
            throw new IllegalArgumentException("unknown name id " + name);
        }
    }

    // ---- readers (any thread, for published names) -------------------------------------------------

    /** Number of name ids in use, including the anonymous name: valid ids are {@code 0 .. size() - 1}. */
    public int size() {
        return count;
    }

    public int prefix(int name) {
        return rowsPub[name * W];
    }

    public boolean isNumeric(int name) {
        return (rowsPub[name * W + 1] & NUMERIC) != 0;
    }

    /** True for a numeric component of 2^64 or more ({@link #numericValue} is then undefined). */
    public boolean isBigNumeric(int name) {
        return (rowsPub[name * W + 1] & BIG) != 0;
    }

    /** Unsigned 64-bit value of a numeric last component. */
    public long numericValue(int name) {
        int[] r = rowsPub;
        int w = name * W;
        if ((r[w + 1] & (NUMERIC | BIG)) != NUMERIC) {
            throw new IllegalArgumentException("name " + name + " has no 64-bit numeric last component");
        }
        return (r[w + 2] & 0xFFFFFFFFL) | ((long) r[w + 3] << 32);
    }

    /** True if the last component is the given ASCII string (e.g. "rec"). */
    public boolean lastComponentIs(int name, String ascii) {
        if (name == ANONYMOUS) {
            return false;
        }
        int[] r = rowsPub;
        int w = name * W;
        int w1 = r[w + 1];
        int len = ascii.length();
        if ((w1 & (NUMERIC | BIG)) != 0 || (w1 >>> 8) != len) {
            return false;
        }
        byte[] p = poolPub;
        int at = r[w + 2];
        for (int i = 0; i < len; i++) {
            if (p[at + i] != (byte) ascii.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** True if the root component is the string "_nested" (reserved inside inductive declarations). */
    public boolean hasNestedRoot(int name) {
        return (rowsPub[name * W + 1] & NESTED_ROOT) != 0;
    }

    /** True if the root component is the string "_private". */
    public boolean hasPrivateRoot(int name) {
        return (rowsPub[name * W + 1] & PRIVATE_ROOT) != 0;
    }

    /** Number of components. */
    public int depth(int name) {
        int[] r = rowsPub;
        int d = 0;
        while (name != ANONYMOUS) {
            name = r[name * W];
            d++;
        }
        return d;
    }

    /** Ancestor with {@code depth} components (the name itself if it is shorter); for namespace grouping. */
    public int ancestor(int name, int depth) {
        int[] r = rowsPub;
        int d = depth(name);
        while (d > depth) {
            name = r[name * W];
            d--;
        }
        return name;
    }

    /**
     * Lean's {@code name::cmp}: components from the root, numeric before string, strings by byte order,
     * a proper prefix first. This is the order {@code rlvm.level.NameOracle} requires. Returns 0 only
     * for equal ids. Safe for concurrent calls.
     */
    public int compare(int nameA, int nameB) {
        if (nameA == nameB) {
            return 0;
        }
        int[] r = rowsPub;
        int da = 0;
        for (int n = nameA; n != ANONYMOUS; n = r[n * W]) {
            da++;
        }
        int db = 0;
        for (int n = nameB; n != ANONYMOUS; n = r[n * W]) {
            db++;
        }
        // Bring both to the same depth, then find the outermost level at which the ids differ.
        int a = nameA;
        int b = nameB;
        for (int i = da; i > db; i--) {
            a = r[a * W];
        }
        for (int i = db; i > da; i--) {
            b = r[b * W];
        }
        int diffA = 0;
        int diffB = 0;
        boolean differ = false;
        while (a != b) {
            diffA = a;
            diffB = b;
            differ = true;
            a = r[a * W];
            b = r[b * W];
        }
        if (!differ) {
            return da < db ? -1 : 1;
        }
        return compareComponents(r, diffA, diffB);
    }

    /** Two names with the same prefix and different last components. */
    private int compareComponents(int[] r, int a, int b) {
        int wa = a * W;
        int wb = b * W;
        int fa = r[wa + 1];
        int fb = r[wb + 1];
        boolean na = (fa & NUMERIC) != 0;
        boolean nb = (fb & NUMERIC) != 0;
        if (na != nb) {
            return na ? -1 : 1;
        }
        if (na) {
            boolean ba = (fa & BIG) != 0;
            boolean bb = (fb & BIG) != 0;
            if (ba != bb) {
                return ba ? 1 : -1;
            }
            if (!ba) {
                long va = (r[wa + 2] & 0xFFFFFFFFL) | ((long) r[wa + 3] << 32);
                long vb = (r[wb + 2] & 0xFFFFFFFFL) | ((long) r[wb + 3] << 32);
                return Long.compareUnsigned(va, vb);
            }
            int la = fa >>> 8;
            int lb = fb >>> 8;
            if (la != lb) {
                return la < lb ? -1 : 1;
            }
        }
        byte[] p = poolPub;
        int la = fa >>> 8;
        int lb = fb >>> 8;
        int oa = r[wa + 2];
        int ob = r[wb + 2];
        int n = Math.min(la, lb);
        for (int i = 0; i < n; i++) {
            int x = p[oa + i] & 0xFF;
            int y = p[ob + i] & 0xFF;
            if (x != y) {
                return x < y ? -1 : 1;
            }
        }
        return la < lb ? -1 : (la > lb ? 1 : Integer.compare(a, b));
    }

    /** Constant declared under this name: positive constant id, 0, or a negative marker. */
    public int constOf(int name) {
        return rowsPub[name * W + 4];
    }

    /**
     * Claims the name for {@code value}: a constant id, or a negative marker such as
     * {@link #DROPPED_UNSAFE}. Returns false if the name was already claimed, by a constant or by a
     * marker: the official reader puts every declaration record, unsafe ones included, into one map
     * and fails on a name it has seen ("Duplicate declaration"). Loader thread.
     */
    public boolean claim(int name, int value) {
        checkName(name);
        if (value == 0) {
            throw new IllegalArgumentException("claim with value 0");
        }
        int w = name * W + 4;
        if (rows[w] != 0) {
            return false;
        }
        rows[w] = value;
        return true;
    }

    /** Diagnostics only: dotted rendering (not an identity; components may contain dots). */
    public String toString(int name) {
        StringBuilder sb = new StringBuilder();
        appendTo(sb, name);
        return sb.toString();
    }

    public void appendTo(StringBuilder sb, int name) {
        if (name == ANONYMOUS) {
            sb.append("[anonymous]");
            return;
        }
        int[] r = rowsPub;
        byte[] p = poolPub;
        int d = depth(name);
        int[] chain = new int[d];
        for (int i = d - 1, n = name; i >= 0; i--, n = r[n * W]) {
            chain[i] = n;
        }
        for (int i = 0; i < d; i++) {
            if (i > 0) {
                sb.append('.');
            }
            int w = chain[i] * W;
            int w1 = r[w + 1];
            if ((w1 & (NUMERIC | BIG)) == NUMERIC) {
                sb.append(Long.toUnsignedString((r[w + 2] & 0xFFFFFFFFL) | ((long) r[w + 3] << 32)));
            } else {
                sb.append(new String(p, r[w + 2], w1 >>> 8, StandardCharsets.UTF_8));
            }
        }
    }

    public long bytes() {
        return 4L * rows.length + pool.length + 4L * table.length;
    }

    /** Bytes in use: rows and component strings of the names interned so far, and the whole intern index. */
    public long usedBytes() {
        return 4L * W * count + poolUsed + 4L * table.length;
    }
}
