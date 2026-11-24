package rlvm.nat;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Arithmetic kernels on natural numbers stored as little-endian 64-bit limbs in a range of a {@code long[]}.
 *
 * <p>Conventions shared by all kernels:
 * <ul>
 *   <li>an operand is {@code (array, offset, length)}; length 0 denotes zero; operands are <em>normalised</em>
 *       (the top limb is non-zero) unless a kernel says otherwise;</li>
 *   <li>the result is written to {@code r[ro ..]}; the result range never overlaps an operand range (the
 *       store always writes above its allocation mark, scratch operands live in separate arrays);</li>
 *   <li>the returned length may include zero top limbs only where stated; {@link NatStore} strips them.</li>
 * </ul>
 * Package-private: the public API is {@link Nat} and {@link NatStore}. No allocation except in the two
 * {@code BigInteger} bridge methods.
 */
final class Limbs {
    private Limbs() {}

    private static final long K = 0x9E3779B97F4A7C15L;
    private static final long K2 = 0xD6E8FEB86659FD93L;

    // ------------------------------------------------------------------ comparison, hashing

    /** Three-way comparison of two normalised operands. */
    static int cmp(long[] a, int ao, int an, long[] b, int bo, int bn) {
        if (an != bn) {
            return an < bn ? -1 : 1;
        }
        for (int i = an - 1; i >= 0; i--) {
            long x = a[ao + i];
            long y = b[bo + i];
            if (x != y) {
                return Long.compareUnsigned(x, y) < 0 ? -1 : 1;
            }
        }
        return 0;
    }

    /** Limb-wise equality of two ranges of the same length. */
    static boolean eq(long[] a, int ao, long[] b, int bo, int n) {
        for (int i = 0; i < n; i++) {
            if (a[ao + i] != b[bo + i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Hash of all limbs (never a sample: structured big literals that differ in few limbs must not collide
     * systematically). Limbs are absorbed by folded 64x64 -> 128 bit multiplications, whose output difference
     * for a single-bit input difference depends on the data; a plain rotate-xor-multiply step lets a top-bit
     * difference in one limb be cancelled by a fixed one-bit difference in the next (found by
     * LimbsTest.hashSpreadsStructuredValues). Long inputs use four independent lanes that take two limbs per
     * multiplication (3.5 instead of 5.5 instructions per limb, measured). The function is not keyed: a
     * limb equal to a lane's current state or to K2 resets that lane, which only an input crafted against
     * these constants does; the cost of such collisions is time, never a wrong result (lookups compare limbs).
     */
    static int hash(long[] a, int off, int len) {
        long h = len * K + K2;
        int i = 0;
        if (len >= 16) {
            long h1 = h ^ K2;
            long h2 = h + K;
            long h3 = ~h;
            int end8 = len & ~7;
            for (; i < end8; i += 8) {
                long p0 = a[off + i] ^ h;
                long q0 = a[off + i + 1] ^ K2;
                long p1 = a[off + i + 2] ^ h1;
                long q1 = a[off + i + 3] ^ K2;
                long p2 = a[off + i + 4] ^ h2;
                long q2 = a[off + i + 5] ^ K2;
                long p3 = a[off + i + 6] ^ h3;
                long q3 = a[off + i + 7] ^ K2;
                h = (p0 * q0) ^ Math.unsignedMultiplyHigh(p0, q0);
                h1 = (p1 * q1) ^ Math.unsignedMultiplyHigh(p1, q1);
                h2 = (p2 * q2) ^ Math.unsignedMultiplyHigh(p2, q2);
                h3 = (p3 * q3) ^ Math.unsignedMultiplyHigh(p3, q3);
            }
            h = mix(h, h1);
            h = mix(h, h2);
            h = mix(h, h3);
        }
        for (; i < len; i++) {
            h = mix(h, a[off + i]);
        }
        h = mix(h, K2);
        return (int) (h ^ (h >>> 32));
    }

    private static long mix(long h, long x) {
        long y = h ^ x;
        return (y * K) ^ Math.unsignedMultiplyHigh(y, K);
    }

    /** Number of significant bits of a normalised operand (0 for zero). */
    static long bitLength(long[] a, int off, int len) {
        if (len == 0) {
            return 0;
        }
        return 64L * len - Long.numberOfLeadingZeros(a[off + len - 1]);
    }

    /** True iff the normalised, non-zero operand is a power of two. */
    static boolean isPow2(long[] a, int off, int len) {
        long top = a[off + len - 1];
        if ((top & (top - 1)) != 0) {
            return false;
        }
        for (int i = len - 2; i >= 0; i--) {
            if (a[off + i] != 0) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ addition, subtraction

    /** {@code r = a + b}; requires {@code an >= bn}. Returns the exact length ({@code an} or {@code an + 1}). */
    static int add(long[] a, int ao, int an, long[] b, int bo, int bn, long[] r, int ro) {
        long carry = 0;
        int i = 0;
        for (; i < bn; i++) {
            long x = a[ao + i];
            long y = b[bo + i];
            long t = x + y + carry;
            r[ro + i] = t;
            carry = ((x & y) | ((x | y) & ~t)) >>> 63;
        }
        // Propagate the carry into the rest of a; once it has died the rest is a plain copy (succ, big + small).
        while (i < an && carry != 0) {
            long t = a[ao + i] + 1;
            r[ro + i] = t;
            carry = t == 0 ? 1 : 0;
            i++;
        }
        if (i < an) {
            System.arraycopy(a, ao + i, r, ro + i, an - i);
            return an;
        }
        if (carry != 0) {
            r[ro + an] = 1;
            return an + 1;
        }
        return an;
    }

    /**
     * {@code r = a - b}; requires {@code a >= b} as numbers (hence {@code an >= bn}). Returns {@code an}; the
     * top limbs of the result may be zero.
     */
    static int sub(long[] a, int ao, int an, long[] b, int bo, int bn, long[] r, int ro) {
        long borrow = 0;
        int i = 0;
        for (; i < bn; i++) {
            long x = a[ao + i];
            long y = b[bo + i];
            long t = x - y - borrow;
            r[ro + i] = t;
            borrow = ((~x & y) | ((~x | y) & t)) >>> 63;
        }
        while (i < an && borrow != 0) {
            long x = a[ao + i];
            r[ro + i] = x - 1;
            borrow = x == 0 ? 1 : 0;
            i++;
        }
        if (i < an) {
            System.arraycopy(a, ao + i, r, ro + i, an - i);
        }
        return an;
    }

    // ------------------------------------------------------------------ multiplication

    /** Carry out (0 or 1) of the 64-bit addition {@code a + b} whose wrapped sum is {@code sum}. */
    private static long carryOut(long a, long b, long sum) {
        return Long.compareUnsigned(sum, a) < 0 ? 1L : 0L;
    }

    /** {@code r = a * m} for a single limb {@code m} (unsigned). Returns {@code an + 1}; the top limb may be zero. */
    static int mul1(long[] a, int ao, int an, long m, long[] r, int ro) {
        long carry = 0;
        for (int i = 0; i < an; i++) {
            long x = a[ao + i];
            long lo = x * m;
            long t = lo + carry;
            r[ro + i] = t;
            // hi + carry-out(lo + carry), branch-free: the carries are data dependent, a branch on them
            // mispredicts half the time (measured: 3x slower)
            carry = Math.unsignedMultiplyHigh(x, m) + carryOut(lo, carry, t);
        }
        r[ro + an] = carry;
        return an + 1;
    }

    /**
     * Schoolbook product {@code r = a * b}; both lengths at least 1. Rows belonging to zero limbs of {@code a}
     * are skipped, so a long sparse {@code a} times a short {@code b} costs (non-zero limbs of a) x bn.
     * Returns {@code an + bn}; the top limb may be zero.
     */
    static int mul(long[] a, int ao, int an, long[] b, int bo, int bn, long[] r, int ro) {
        int n = an + bn;
        Arrays.fill(r, ro, ro + n, 0L);
        for (int i = 0; i < an; i++) {
            long x = a[ao + i];
            if (x == 0) {
                continue;
            }
            long carry = 0;
            int k = ro + i;
            for (int j = 0; j < bn; j++, k++) {
                long y = b[bo + j];
                long lo = x * y;
                long z = r[k];
                long t = z + lo;
                long u = t + carry;
                r[k] = u;
                // hi + carry(z + lo) + carry(t + carry) cannot overflow: x * y + z + carry < 2^128.
                // Branch-free on purpose: the carries are data dependent, a branch mispredicts half the time.
                carry = Math.unsignedMultiplyHigh(x, y) + (carryOut(z, lo, t) + carryOut(t, carry, u));
            }
            // r[ro + i + bn] has not been written by an earlier row: rows < i end at index i - 1 + bn.
            r[k] = carry;
        }
        return n;
    }

    /**
     * In place {@code r = r * m + add} over {@code n} limbs; returns the carry limb (to be stored at index
     * {@code n} by the caller if non-zero). {@code m} and {@code add} are unsigned.
     */
    static long mulAdd1InPlace(long[] r, int ro, int n, long m, long add) {
        long carry = add;
        for (int i = 0; i < n; i++) {
            long x = r[ro + i];
            long lo = x * m;
            long t = lo + carry;
            r[ro + i] = t;
            carry = Math.unsignedMultiplyHigh(x, m) + carryOut(lo, carry, t);
        }
        return carry;
    }

    // ------------------------------------------------------------------ division by one limb

    /**
     * Divides the normalised operand by the single non-zero limb {@code d} (unsigned). The quotient is
     * written to {@code q[qo .. qo + an)} (top limb may be zero) unless {@code q} is null. Returns the
     * remainder (unsigned).
     */
    static long divRem1(long[] a, int ao, int an, long d, long[] q, int qo) {
        long rem = 0;
        if ((d >>> 32) == 0) {
            // 32-bit divisor: two exact 64/32 steps per limb, the running remainder stays below 2^32.
            for (int i = an - 1; i >= 0; i--) {
                long x = a[ao + i];
                long u1 = (rem << 32) | (x >>> 32);
                long q1 = Long.divideUnsigned(u1, d);
                rem = u1 - q1 * d;
                long u0 = (rem << 32) | (x & 0xFFFFFFFFL);
                long q0 = Long.divideUnsigned(u0, d);
                rem = u0 - q0 * d;
                if (q != null) {
                    q[qo + i] = (q1 << 32) | q0;
                }
            }
            return rem;
        }
        // General divisor: Knuth D with the divisor normalised by s bits, two 32-bit quotient digits per
        // limb (Hacker's Delight divlu). The dividend is shifted on the fly.
        int s = Long.numberOfLeadingZeros(d);
        long v = d << s;
        long vn1 = v >>> 32;
        long vn0 = v & 0xFFFFFFFFL;
        // rem holds the running remainder in normalised scale (rem < v).
        long prev = a[ao + an - 1];
        rem = s == 0 ? 0 : prev >>> (64 - s);
        for (int i = an - 1; i >= 0; i--) {
            long x = prev;
            long next = i > 0 ? a[ao + i - 1] : 0;
            long un10 = s == 0 ? x : (x << s) | (next >>> (64 - s));
            prev = next;
            long un1 = un10 >>> 32;
            long un0 = un10 & 0xFFFFFFFFL;
            // first quotient digit: (rem * 2^32 + un1) / v
            long q1 = Long.divideUnsigned(rem, vn1);
            long rhat = rem - q1 * vn1;
            while ((q1 >>> 32) != 0 || Long.compareUnsigned(q1 * vn0, (rhat << 32) | un1) > 0) {
                q1--;
                rhat += vn1;
                if ((rhat >>> 32) != 0) {
                    break;
                }
            }
            long un21 = (rem << 32) + un1 - q1 * v;
            // second quotient digit: (un21 * 2^32 + un0) / v
            long q0 = Long.divideUnsigned(un21, vn1);
            rhat = un21 - q0 * vn1;
            while ((q0 >>> 32) != 0 || Long.compareUnsigned(q0 * vn0, (rhat << 32) | un0) > 0) {
                q0--;
                rhat += vn1;
                if ((rhat >>> 32) != 0) {
                    break;
                }
            }
            rem = (un21 << 32) + un0 - q0 * v;
            if (q != null) {
                q[qo + i] = (q1 << 32) + q0;
            }
        }
        return rem >>> s;
    }

    // ------------------------------------------------------------------ shifts

    /**
     * {@code r = a << (64 * ls + bs)} with {@code 0 <= bs < 64}; {@code an >= 1}. Returns the exact length.
     */
    static int shl(long[] a, int ao, int an, int ls, int bs, long[] r, int ro) {
        for (int i = 0; i < ls; i++) {
            r[ro + i] = 0;
        }
        int p = ro + ls;
        if (bs == 0) {
            System.arraycopy(a, ao, r, p, an);
            return ls + an;
        }
        int inv = 64 - bs;
        long carry = 0;
        for (int i = 0; i < an; i++) {
            long x = a[ao + i];
            r[p + i] = (x << bs) | carry;
            carry = x >>> inv;
        }
        if (carry != 0) {
            r[p + an] = carry;
            return ls + an + 1;
        }
        return ls + an;
    }

    /**
     * {@code r = a >> (64 * ls + bs)} with {@code 0 <= bs < 64} and {@code ls < an}. Returns {@code an - ls};
     * the top limb may be zero.
     */
    static int shr(long[] a, int ao, int an, int ls, int bs, long[] r, int ro) {
        int n = an - ls;
        int p = ao + ls;
        if (bs == 0) {
            System.arraycopy(a, p, r, ro, n);
            return n;
        }
        int inv = 64 - bs;
        long cur = a[p];
        for (int i = 0; i < n - 1; i++) {
            long next = a[p + i + 1];
            r[ro + i] = (cur >>> bs) | (next << inv);
            cur = next;
        }
        r[ro + n - 1] = cur >>> bs;
        return n;
    }

    // ------------------------------------------------------------------ bitwise

    /** {@code r = a & b}. Returns {@code min(an, bn)}; top limbs may be zero. */
    static int and(long[] a, int ao, int an, long[] b, int bo, int bn, long[] r, int ro) {
        int n = Math.min(an, bn);
        for (int i = 0; i < n; i++) {
            r[ro + i] = a[ao + i] & b[bo + i];
        }
        return n;
    }

    /** {@code r = a | b}; requires {@code an >= bn}. Returns {@code an} (exact). */
    static int or(long[] a, int ao, int an, long[] b, int bo, int bn, long[] r, int ro) {
        for (int i = 0; i < bn; i++) {
            r[ro + i] = a[ao + i] | b[bo + i];
        }
        if (an > bn) {
            System.arraycopy(a, ao + bn, r, ro + bn, an - bn);
        }
        return an;
    }

    /** {@code r = a ^ b}; requires {@code an >= bn}. Returns {@code an}; top limbs may be zero. */
    static int xor(long[] a, int ao, int an, long[] b, int bo, int bn, long[] r, int ro) {
        for (int i = 0; i < bn; i++) {
            r[ro + i] = a[ao + i] ^ b[bo + i];
        }
        if (an > bn) {
            System.arraycopy(a, ao + bn, r, ro + bn, an - bn);
        }
        return an;
    }

    // ------------------------------------------------------------------ BigInteger bridge

    /** The value of an operand as a {@code BigInteger} (allocates). */
    static BigInteger toBigInteger(long[] a, int off, int len) {
        if (len == 0) {
            return BigInteger.ZERO;
        }
        byte[] bytes = new byte[len * 8];
        ByteBuffer bb = ByteBuffer.wrap(bytes); // big-endian
        for (int i = 0; i < len; i++) {
            bb.putLong(8 * i, a[off + len - 1 - i]);
        }
        return new BigInteger(1, bytes);
    }

    /** Number of limbs of a non-negative {@code BigInteger}. */
    static int limbCount(BigInteger v) {
        return (v.bitLength() + 63) >>> 6;
    }

    /**
     * Writes the limbs of the non-negative {@code v} to {@code r[ro ..]} and returns their number
     * ({@link #limbCount}); the result is normalised.
     */
    static int fromBigInteger(BigInteger v, long[] r, int ro) {
        int n = limbCount(v);
        if (n == 0) {
            return 0;
        }
        byte[] bytes = v.toByteArray(); // big-endian two's complement: 8n - 7 .. 8n + 1 bytes
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        int p = bytes.length;
        int i = 0;
        for (; i < n && p >= 8; i++) {
            p -= 8;
            r[ro + i] = bb.getLong(p);
        }
        if (i < n) { // the top limb has fewer than 8 bytes
            long x = 0;
            for (int j = 0; j < p; j++) {
                x = (x << 8) | (bytes[j] & 0xFFL);
            }
            r[ro + i] = x;
        }
        return n;
    }
}
