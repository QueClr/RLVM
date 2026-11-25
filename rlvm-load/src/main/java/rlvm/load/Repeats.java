package rlvm.load;

import java.util.Arrays;

/**
 * The definitions, theorems and opaques read so far, by what their records say: finds the first unit of
 * the file that a declaration repeats word for word.
 *
 * <p>The exporter writes many declarations more than once under different names (auxiliary proofs,
 * equation lemmas, matchers that the elaborator emits again): the record names the SAME type and value
 * expression ids as an earlier record. The key is exact, never a hash: the kind (definition, theorem,
 * opaque), the id of the list of universe-parameter names (lists are interned: one id per list), the
 * reducibility hint of a definition, and the two record ordinals. Two ordinals are equal only for one
 * expression record of the input, so a repeat is the same pair of objects to the official checker too,
 * not merely two equal terms. Unsafe and partial records never get here (they are dropped), so the
 * safety is the same by construction. What the loader does with the result is one call,
 * {@link LoadTarget#repeatOf}; nothing it writes depends on it.
 *
 * <p>Cost: one entry (6 ints) per declaration with a value and one probe of the bucket array. A
 * declaration whose type or value record lies in its own segment cannot repeat anything, because no
 * earlier declaration could name a record that did not exist: it is added without a search.
 *
 * <p>Loader thread only.
 */
final class Repeats {

    private static final int E_TYPE = 0;
    private static final int E_VALUE = 1;
    private static final int E_LPARAMS = 2;
    /** The hint of a definition (never negative), or minus the kind of a theorem or an opaque. */
    private static final int E_KIND_HINT = 3;
    private static final int E_UNIT = 4;
    /** 1 + the next entry of the bucket, 0 = end. */
    private static final int E_NEXT = 5;
    private static final int E_WORDS = 6;

    /** Per bucket: 1 + its first entry, 0 = empty. The length is a power of two. */
    private int[] heads = new int[1 << 10];
    private int[] entries = new int[E_WORDS << 10];
    private int count;
    private long found;

    /**
     * The first unit that was added with this key, or -1 after adding {@code unit} under it.
     *
     * @param defn the declaration is a definition ({@code hint} is its reducibility hint, not negative);
     *     otherwise {@code kind} tells a theorem from an opaque and the hint is ignored
     * @param type ordinal of the type record
     * @param value ordinal of the value record
     * @param mayRepeat false if the type or the value record belongs to the segment of this very
     *     declaration: no earlier declaration can have named it
     */
    int firstOrAdd(boolean defn, int kind, int lparams, int hint, int type, int value, int unit, boolean mayRepeat) {
        final int kindHint = defn ? hint : -kind;
        if (defn ? hint < 0 : kind <= 0) {
            throw new IllegalArgumentException("kind " + kind + ", hint " + hint);
        }
        int[] h = heads;
        int b = bucket(type, value, h.length);
        if (mayRepeat) {
            final int[] e = entries;
            for (int i = h[b]; i != 0; ) {
                int p = (i - 1) * E_WORDS;
                if (e[p + E_VALUE] == value && e[p + E_TYPE] == type && e[p + E_LPARAMS] == lparams
                        && e[p + E_KIND_HINT] == kindHint) {
                    found++;
                    return e[p + E_UNIT];
                }
                i = e[p + E_NEXT];
            }
        }
        if (count == h.length) {
            h = grow();
            b = bucket(type, value, h.length);
        }
        int p = count * E_WORDS;
        if (p + E_WORDS > entries.length) {
            entries = Arrays.copyOf(entries, entries.length << 1);
        }
        final int[] e = entries;
        e[p + E_TYPE] = type;
        e[p + E_VALUE] = value;
        e[p + E_LPARAMS] = lparams;
        e[p + E_KIND_HINT] = kindHint;
        e[p + E_UNIT] = unit;
        e[p + E_NEXT] = h[b];
        h[b] = ++count;
        return -1;
    }

    /** Declarations added (the first of each key). */
    int size() {
        return count;
    }

    /** Declarations that repeated an earlier one. */
    long found() {
        return found;
    }

    private static int bucket(int type, int value, int buckets) {
        int x = type * 0x9E3779B1 + value * 0x85EBCA77;
        x ^= x >>> 15;
        return x & (buckets - 1);
    }

    /** Twice the buckets; the entries stay where they are and are linked again in their order. */
    private int[] grow() {
        final int[] h = new int[heads.length << 1];
        final int[] e = entries;
        for (int i = 1; i <= count; i++) {
            int p = (i - 1) * E_WORDS;
            int b = bucket(e[p + E_TYPE], e[p + E_VALUE], h.length);
            e[p + E_NEXT] = h[b];
            h[b] = i;
        }
        heads = h;
        return h;
    }
}
