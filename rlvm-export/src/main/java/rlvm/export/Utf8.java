package rlvm.export;

/** Strict UTF-8 (Unicode scalar values only: no overlong forms, no surrogates, at most U+10FFFF). */
final class Utf8 {
    private Utf8() {}

    /**
     * Length (2, 3 or 4) of the well-formed multi-byte sequence that starts at {@code b[p]} and ends
     * before {@code end}; 0 if there is none. {@code b[p]} must be a byte of 0x80 or above.
     */
    static int sequenceLength(byte[] b, int p, int end) {
        int c = b[p] & 0xFF;
        if (c < 0xC2) {
            return 0; // continuation byte or overlong 2-byte lead
        }
        if (c < 0xE0) {
            return p + 1 < end && isCont(b[p + 1]) ? 2 : 0;
        }
        if (c < 0xF0) {
            if (p + 2 >= end) {
                return 0;
            }
            int c1 = b[p + 1] & 0xFF;
            if (c1 < 0x80 || c1 > 0xBF || (c == 0xE0 && c1 < 0xA0) || (c == 0xED && c1 > 0x9F)) {
                return 0;
            }
            return isCont(b[p + 2]) ? 3 : 0;
        }
        if (c < 0xF5) {
            if (p + 3 >= end) {
                return 0;
            }
            int c1 = b[p + 1] & 0xFF;
            if (c1 < 0x80 || c1 > 0xBF || (c == 0xF0 && c1 < 0x90) || (c == 0xF4 && c1 > 0x8F)) {
                return 0;
            }
            return isCont(b[p + 2]) && isCont(b[p + 3]) ? 4 : 0;
        }
        return 0;
    }

    private static boolean isCont(byte x) {
        return (x & 0xC0) == 0x80;
    }

    /** Whether {@code b[off .. off+len)} is well-formed UTF-8. */
    static boolean isValid(byte[] b, int off, int len) {
        int p = off;
        int end = off + len;
        while (p < end) {
            if (b[p] >= 0) {
                p++;
            } else {
                int n = sequenceLength(b, p, end);
                if (n == 0) {
                    return false;
                }
                p += n;
            }
        }
        return true;
    }

    /** Appends the UTF-8 form of the scalar value {@code cp} at {@code out[pos]}; returns the new position. */
    static int encode(int cp, byte[] out, int pos) {
        if (cp < 0x80) {
            out[pos++] = (byte) cp;
        } else if (cp < 0x800) {
            out[pos++] = (byte) (0xC0 | (cp >> 6));
            out[pos++] = (byte) (0x80 | (cp & 0x3F));
        } else if (cp < 0x10000) {
            out[pos++] = (byte) (0xE0 | (cp >> 12));
            out[pos++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
            out[pos++] = (byte) (0x80 | (cp & 0x3F));
        } else {
            out[pos++] = (byte) (0xF0 | (cp >> 18));
            out[pos++] = (byte) (0x80 | ((cp >> 12) & 0x3F));
            out[pos++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
            out[pos++] = (byte) (0x80 | (cp & 0x3F));
        }
        return pos;
    }
}
