package rlvm.load;

/**
 * Decimal-to-binary conversion of {@code natVal} strings, provided by {@code rlvm-nat}. The loader
 * converts up to 19 digits itself; longer numerals come here (Mathlib: 1,820 of 5,660 literals are
 * 2^64 or more, the longest has 1,085 digits; hostile inputs may be megabytes long, so the conversion
 * must be sub-quadratic).
 */
public interface NatDecoder {

    /**
     * Converts ASCII digits to little-endian 64-bit limbs without a leading zero limb.
     *
     * @return the number of limbs written to {@code dst} (which the callee may grow via {@link Limbs})
     */
    int decode(byte[] digits, int off, int len, Limbs dst);

    /** A growable limb buffer owned by the loader. */
    final class Limbs {
        public long[] words = new long[16];

        public void ensure(int n) {
            if (words.length < n) {
                words = java.util.Arrays.copyOf(words, Math.max(n, words.length * 2));
            }
        }
    }
}
