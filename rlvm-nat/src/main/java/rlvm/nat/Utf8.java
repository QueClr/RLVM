package rlvm.nat;

import java.util.Arrays;

/**
 * Strict UTF-8 on byte ranges, as needed for Lean string literals.
 *
 * <p>A Lean {@code String} is a sequence of Unicode scalar values (0 .. 0x10FFFF without the surrogates
 * 0xD800 .. 0xDFFF), stored as its UTF-8 encoding. "Strict" means exactly the well-formed byte sequences of
 * the Unicode standard (table 3-7): no overlong forms, no encoded surrogates, nothing above 0x10FFFF, no
 * truncated sequence, no stray continuation byte. This is also what the official runtime's
 * {@code validate_utf8} accepts. All methods are allocation-free and never throw on malformed input.
 */
public final class Utf8 {
    private Utf8() {}

    /** Largest Unicode scalar value. */
    public static final int MAX_SCALAR = 0x10FFFF;
    /** U+FFFD REPLACEMENT CHARACTER, what the official reader substitutes for malformed input. */
    public static final int REPLACEMENT = 0xFFFD;

    /** True iff {@code cp} is a Unicode scalar value. */
    public static boolean isScalarValue(int cp) {
        return (cp >= 0 && cp < 0xD800) || (cp > 0xDFFF && cp <= MAX_SCALAR);
    }

    /** Number of bytes (1 to 4) of the UTF-8 encoding of the scalar value {@code cp}. */
    public static int encodedLength(int cp) {
        if (cp < 0x80) {
            return 1;
        }
        if (cp < 0x800) {
            return 2;
        }
        return cp < 0x10000 ? 3 : 4;
    }

    /**
     * Writes the UTF-8 encoding of the scalar value {@code cp} to {@code dst[off ..]} and returns its
     * length. Throws {@link IllegalArgumentException} if {@code cp} is not a scalar value.
     */
    public static int encode(int cp, byte[] dst, int off) {
        if (!isScalarValue(cp)) {
            throw new IllegalArgumentException("not a Unicode scalar value: " + cp);
        }
        if (cp < 0x80) {
            dst[off] = (byte) cp;
            return 1;
        }
        if (cp < 0x800) {
            dst[off] = (byte) (0xC0 | (cp >> 6));
            dst[off + 1] = (byte) (0x80 | (cp & 0x3F));
            return 2;
        }
        if (cp < 0x10000) {
            dst[off] = (byte) (0xE0 | (cp >> 12));
            dst[off + 1] = (byte) (0x80 | ((cp >> 6) & 0x3F));
            dst[off + 2] = (byte) (0x80 | (cp & 0x3F));
            return 3;
        }
        dst[off] = (byte) (0xF0 | (cp >> 18));
        dst[off + 1] = (byte) (0x80 | ((cp >> 12) & 0x3F));
        dst[off + 2] = (byte) (0x80 | ((cp >> 6) & 0x3F));
        dst[off + 3] = (byte) (0x80 | (cp & 0x3F));
        return 4;
    }

    /**
     * Strictly decodes the scalar value whose encoding starts at {@code b[pos]} and ends before {@code to}
     * ({@code pos < to}). Returns the scalar value, or -1 if the bytes at {@code pos} are not a well-formed
     * sequence. The sequence occupies {@link #encodedLength} bytes of the returned value.
     */
    public static int codePointAt(byte[] b, int pos, int to) {
        int c = b[pos];
        if (c >= 0) {
            return c;
        }
        c &= 0xFF;
        if (c < 0xC2) {
            return -1; // continuation byte, or C0 / C1 (overlong two-byte forms)
        }
        if (c < 0xE0) {
            if (pos + 1 >= to) {
                return -1;
            }
            int c1 = b[pos + 1];
            if ((c1 & 0xC0) != 0x80) {
                return -1;
            }
            return ((c & 0x1F) << 6) | (c1 & 0x3F);
        }
        if (c < 0xF0) {
            if (pos + 2 >= to) {
                return -1;
            }
            int c1 = b[pos + 1];
            int c2 = b[pos + 2];
            if ((c1 & 0xC0) != 0x80 || (c2 & 0xC0) != 0x80) {
                return -1;
            }
            int r = ((c & 0x0F) << 12) | ((c1 & 0x3F) << 6) | (c2 & 0x3F);
            if (r < 0x800 || (r >= 0xD800 && r <= 0xDFFF)) {
                return -1; // overlong, or an encoded surrogate
            }
            return r;
        }
        if (c < 0xF5) {
            if (pos + 3 >= to) {
                return -1;
            }
            int c1 = b[pos + 1];
            int c2 = b[pos + 2];
            int c3 = b[pos + 3];
            if ((c1 & 0xC0) != 0x80 || (c2 & 0xC0) != 0x80 || (c3 & 0xC0) != 0x80) {
                return -1;
            }
            int r = ((c & 0x07) << 18) | ((c1 & 0x3F) << 12) | ((c2 & 0x3F) << 6) | (c3 & 0x3F);
            if (r < 0x10000 || r > MAX_SCALAR) {
                return -1; // overlong, or beyond Unicode
            }
            return r;
        }
        return -1; // F5 .. FF never occur
    }

    /**
     * In <em>validated</em> text, the start of the scalar value that ends just before {@code pos}
     * ({@code from < pos}); used to walk a literal backwards, e.g. to build its {@code List Char} from the
     * tail. On text that is not well-formed the result is some index in {@code [max(from, pos - 4), pos)}.
     */
    public static int startOfPrevious(byte[] b, int from, int pos) {
        int p = pos - 1;
        int lo = Math.max(from, pos - 4);
        while (p > lo && (b[p] & 0xC0) == 0x80) {
            p--;
        }
        return p;
    }

    /**
     * Strictly validates {@code b[from .. to)}. Returns the number of scalar values if the range is
     * well-formed, otherwise {@code ~i} (a negative number) where {@code i} is the index of the first byte
     * that does not start a well-formed sequence.
     */
    public static int validate(byte[] b, int from, int to) {
        int n = 0;
        int p = from;
        while (p < to) {
            if (b[p] >= 0) {
                p++;
                n++;
                continue;
            }
            int cp = codePointAt(b, p, to);
            if (cp < 0) {
                return ~p;
            }
            p += cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            n++;
        }
        return n;
    }

    /**
     * Strictly decodes {@code b[from .. to)} into scalar values written to {@code dst[dstOff ..]}; the
     * destination needs room for at most {@code to - from} values (exactly {@link #validate}'s count).
     * Returns the number of values, or {@code ~i} as {@link #validate} does (the destination then holds the
     * values decoded so far).
     */
    public static int decode(byte[] b, int from, int to, int[] dst, int dstOff) {
        int n = 0;
        int p = from;
        while (p < to) {
            int cp = b[p];
            if (cp >= 0) {
                p++;
            } else {
                cp = codePointAt(b, p, to);
                if (cp < 0) {
                    return ~p;
                }
                p += cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            }
            dst[dstOff + n] = cp;
            n++;
        }
        return n;
    }

    /**
     * Equality of two string literals given as (validated) UTF-8 ranges. Two well-formed UTF-8 sequences
     * denote the same list of scalar values iff they are the same bytes, which is also how the official
     * kernel compares two string literals.
     */
    public static boolean equal(byte[] a, int aFrom, int aTo, byte[] b, int bFrom, int bTo) {
        return Arrays.equals(a, aFrom, aTo, b, bFrom, bTo);
    }

    /**
     * Lexicographic comparison of two (validated) UTF-8 ranges by scalar value: negative, zero or positive.
     * For well-formed UTF-8 this is the unsigned byte order.
     */
    public static int compare(byte[] a, int aFrom, int aTo, byte[] b, int bFrom, int bTo) {
        return Arrays.compareUnsigned(a, aFrom, aTo, b, bFrom, bTo);
    }

    /** A hash of the bytes of a range, for interning string literals; equal ranges hash equally. */
    public static int hash(byte[] b, int from, int to) {
        long h = 0xCBF29CE484222325L ^ (to - from);
        for (int i = from; i < to; i++) {
            h = (h ^ (b[i] & 0xFF)) * 0x100000001B3L;
        }
        h ^= h >>> 32;
        h *= 0xD6E8FEB86659FD93L;
        return (int) (h ^ (h >>> 32));
    }

    /**
     * Length in bytes of what {@link #repairLossy} writes for {@code b[from .. to)}; equal to
     * {@code to - from} iff the range is well-formed.
     */
    public static int repairedLength(byte[] b, int from, int to) {
        int n = 0;
        int p = from;
        while (p < to) {
            if (b[p] >= 0) {
                p++;
                n++;
                continue;
            }
            int cp = codePointAt(b, p, to);
            if (cp >= 0) {
                int len = cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
                p += len;
                n += len;
            } else {
                n += 3;
                p++;
                while (p < to && (b[p] & 0xC0) == 0x80) {
                    p++;
                }
            }
        }
        return n;
    }

    /**
     * The lossy repair the official reader applies to every line it reads
     * ({@code lean_mk_string_lossy_recover}, verified against {@code leanchecker} 4.36.0): at each position
     * that does not start a well-formed sequence, one U+FFFD is emitted and that byte plus all directly
     * following continuation bytes (0x80 .. 0xBF) are skipped. Well-formed input is copied unchanged. Writes
     * {@link #repairedLength} bytes to {@code dst[dstOff ..]} and returns that length.
     *
     * <p>RLVM's default for malformed input is to refuse it ({@link #validate}); this method exists for a
     * reader that wants to reproduce the official verdict on such files instead.
     */
    public static int repairLossy(byte[] b, int from, int to, byte[] dst, int dstOff) {
        int q = dstOff;
        int p = from;
        while (p < to) {
            byte c = b[p];
            if (c >= 0) {
                dst[q++] = c;
                p++;
                continue;
            }
            int cp = codePointAt(b, p, to);
            if (cp >= 0) {
                int len = cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
                for (int i = 0; i < len; i++) {
                    dst[q + i] = b[p + i];
                }
                p += len;
                q += len;
            } else {
                dst[q] = (byte) 0xEF;
                dst[q + 1] = (byte) 0xBF;
                dst[q + 2] = (byte) 0xBD;
                q += 3;
                p++;
                while (p < to && (b[p] & 0xC0) == 0x80) {
                    p++;
                }
            }
        }
        return q - dstOff;
    }
}
