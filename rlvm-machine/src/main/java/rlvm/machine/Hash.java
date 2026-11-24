package rlvm.machine;

/**
 * The hash functions of the session tables (ISA 1.6, 2.5). They are part of the frozen table format:
 * {@link Machine} uses them to grow and to reset the tables in O(used), and the interning code of M1
 * ({@code Values}, {@code Runs}) and the pair table of M4 ({@code Pairs}) must use the same functions.
 *
 * <p>Shared file, owner M6. Pure functions of their arguments; no state.
 */
public final class Hash {
    private Hash() {}

    /** The bits of w0 that belong to a record's identity: kind and aux (flags are not identity, I-FLAG). */
    public static final int W0_KEY_MASK = ~(Layout.FLAGS_MASK << Layout.FLAG_SHIFT);

    /** Final avalanche; the top 4 bits of the result become the tag, the low bits the slot. */
    public static int fin(int h) {
        // One multiplication (integrator, NOTES-INT I4; M1's request): every caller has multiplied its input
        // at least once already, and the result is used as low bits (slot) and top four bits (tag) only.
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 13;
        return h;
    }

    /**
     * Hash of a record key {@code (kind, aux, a, b, c)} for every kind except NEU (ISA 2.5). A NEU is
     * keyed by {@code (head, len, argument refs)}: its hash is {@link #neu} over the spine and is stored
     * in word c of the record.
     */
    public static int record(int w0, int a, int b, int c) {
        // Three multiplications instead of six (NOTES-INT I4): the form M1 measured in M1ProbeBench.hashAlt
        // (67 -> 46 instructions and 3.8 -> 2.3 ns per probe on a hit).
        int h = (a ^ (w0 & W0_KEY_MASK)) * 0x9E3779B1 + b;
        h = (h ^ (h >>> 15)) * 0x85EBCA6B + c;
        h = (h ^ (h >>> 13)) * 0xC2B2AE35;
        return h ^ (h >>> 16);
    }

    /** Start of a sequence hash (spines, frames): fold the elements with {@link #step}, end with {@link #neu} or {@link #run}. */
    public static int seed(int len) {
        return len * 0x9E3779B1 + 0x165667B1;
    }

    public static int step(int h, int ref) {
        return (Integer.rotateLeft(h, 5) ^ ref) * 0x9E3779B1;
    }

    /** Hash of a NEU from its head and the folded hash of its arguments ({@code seed(len)}, then {@code step} per argument). */
    public static int neu(int head, int folded) {
        return fin((Integer.rotateLeft(folded, 13) ^ head) * 0x85EBCA6B ^ (Layout.NEU << Layout.KIND_SHIFT));
    }

    /** Content hash of a FRAME or REST run: the folded slots, the run kind and the REST run id (0 if not far). */
    public static int run(int folded, int kind, int rest) {
        return fin((Integer.rotateLeft(folded, 11) ^ rest) * 0xC2B2AE35 + kind);
    }

    /** Hash of a pair key; {@code lo}, {@code hi} already ordered as unsigned numbers. */
    public static int pair(int lo, int hi) {
        return fin((lo * 0x9E3779B1) ^ (Integer.rotateLeft(hi, 15) * 0x85EBCA6B));
    }

    /** Hash for a direct-mapped cache with one or two key words. */
    public static int cell(int k1, int k2) {
        return fin(k1 * 0x9E3779B1 + k2 * 0x85EBCA6B);
    }

    /** The hash a record was interned under, recomputed from the record (reset and growth of vtab). */
    public static int ofRecord(int[] vh, int r) {
        int o = r << Layout.REC_SHIFT;
        int w0 = vh[o];
        if ((w0 >>> Layout.KIND_SHIFT) == Layout.NEU) {
            return vh[o + Layout.C];
        }
        return record(w0, vh[o + Layout.A], vh[o + Layout.B], vh[o + Layout.C]);
    }
}
