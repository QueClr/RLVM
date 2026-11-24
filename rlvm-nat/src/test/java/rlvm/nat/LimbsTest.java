package rlvm.nat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Differential tests of the limb kernels against {@code BigInteger}, with independent conversions. */
class LimbsTest {
    private static final BigInteger MASK64 = TestSupport.TWO64.subtract(BigInteger.ONE);
    private static final long SENTINEL = 0x5EA7_1E55_C0FF_EE00L;

    /** Limbs of v by shifting, independent of Limbs.fromBigInteger. */
    static long[] limbs(BigInteger v) {
        int n = (v.bitLength() + 63) / 64;
        long[] r = new long[n];
        for (int i = 0; i < n; i++) {
            r[i] = v.shiftRight(64 * i).longValue();
        }
        return r;
    }

    /** Value of a limb range by summation, independent of Limbs.toBigInteger. */
    static BigInteger value(long[] a, int off, int len) {
        BigInteger v = BigInteger.ZERO;
        for (int i = len - 1; i >= 0; i--) {
            v = v.shiftLeft(64).or(BigInteger.valueOf(a[off + i]).and(MASK64));
        }
        return v;
    }

    /** An operand embedded at a random offset in an array filled with sentinels. */
    private static final class Op {
        final long[] a;
        final int off;
        final int len;
        final BigInteger v;

        Op(BigInteger v, Random r) {
            long[] l = limbs(v);
            this.v = v;
            this.len = l.length;
            this.off = r.nextInt(4);
            this.a = new long[off + len + r.nextInt(3)];
            Arrays.fill(a, SENTINEL);
            System.arraycopy(l, 0, a, off, len);
        }
    }

    /** A result buffer with sentinels around the expected extent. */
    private static final class Res {
        final long[] r;
        final int ro;
        final int cap;

        Res(int cap, Random rnd) {
            this.cap = cap;
            this.ro = rnd.nextInt(4);
            this.r = new long[ro + cap + 2];
            Arrays.fill(r, SENTINEL);
        }

        void assertUntouchedOutside() {
            for (int i = 0; i < ro; i++) {
                assertEquals(SENTINEL, r[i], "write below the result");
            }
            for (int i = ro + cap; i < r.length; i++) {
                assertEquals(SENTINEL, r[i], "write above the reserved result");
            }
        }
    }

    private static BigInteger rnd(Random r) {
        return TestSupport.randomNat(r, r.nextInt(8) == 0 ? 3000 : 400);
    }

    @Test
    void bridgeRoundTrips() {
        Random r = new Random(1);
        for (int i = 0; i < 20000; i++) {
            BigInteger v = rnd(r);
            Op a = new Op(v, r);
            assertEquals(v, Limbs.toBigInteger(a.a, a.off, a.len));
            assertEquals(a.len, Limbs.limbCount(v));
            Res res = new Res(a.len, r);
            int n = Limbs.fromBigInteger(v, res.r, res.ro);
            assertEquals(a.len, n);
            assertEquals(v, value(res.r, res.ro, n));
            res.assertUntouchedOutside();
            assertEquals(v.bitLength(), Limbs.bitLength(a.a, a.off, a.len));
            if (v.signum() > 0) {
                assertEquals(v.bitCount() == 1, Limbs.isPow2(a.a, a.off, a.len), v.toString(16));
            }
        }
        for (BigInteger v : TestSupport.boundaries()) {
            long[] l = limbs(v);
            assertEquals(v, Limbs.toBigInteger(l, 0, l.length));
            long[] back = new long[l.length];
            assertEquals(l.length, Limbs.fromBigInteger(v, back, 0));
            assertArrayEquals(l, back);
        }
    }

    @Test
    void compareAndHash() {
        Random r = new Random(2);
        for (int i = 0; i < 20000; i++) {
            BigInteger x = rnd(r);
            BigInteger y = r.nextInt(4) == 0 ? x.add(BigInteger.valueOf(r.nextInt(3) - 1)).abs() : rnd(r);
            Op a = new Op(x, r);
            Op b = new Op(y, r);
            assertEquals(Integer.signum(x.compareTo(y)), Limbs.cmp(a.a, a.off, a.len, b.a, b.off, b.len));
            if (a.len == b.len) {
                assertEquals(x.equals(y), Limbs.eq(a.a, a.off, b.a, b.off, a.len));
            }
            Op a2 = new Op(x, r);
            assertEquals(Limbs.hash(a.a, a.off, a.len), Limbs.hash(a2.a, a2.off, a2.len), "hash depends on the value only");
        }
    }

    @Test
    void hashSpreadsStructuredValues() {
        // Distinct values that differ in one or two bits, or only in length: with a 32-bit hash a handful of
        // collisions among ~170k values is chance (expected 3.4), dozens would be a structural weakness.
        java.util.HashSet<Integer> seen = new java.util.HashSet<>();
        int n = 0;
        for (int len : new int[] {2, 5, 8, 9, 13, 16, 17, 23, 40}) {
            long[] a = new long[len];
            for (int i = 0; i < len * 64 - 1; i++) { // one bit besides the top bit
                Arrays.fill(a, 0);
                a[len - 1] = Long.MIN_VALUE;
                a[i / 64] |= 1L << (i % 64);
                seen.add(Limbs.hash(a, 0, len));
                n++;
            }
        }
        for (int len : new int[] {12, 24}) { // two bits besides the top bit (short and long hashing path)
            long[] a = new long[len];
            for (int i = 0; i < len * 64 - 1; i += len == 12 ? 3 : 7) {
                for (int j = i + 1; j < len * 64 - 1; j += len == 12 ? 5 : 9) {
                    Arrays.fill(a, 0);
                    a[len - 1] = Long.MIN_VALUE;
                    a[i / 64] |= 1L << (i % 64);
                    a[j / 64] |= 1L << (j % 64);
                    seen.add(Limbs.hash(a, 0, len));
                    n++;
                }
            }
        }
        long[] dense = new long[32]; // dense values that differ in one bit, or in two bits of one limb pair
        java.util.Random rnd = new java.util.Random(99);
        for (int k = 0; k < 32; k++) {
            dense[k] = rnd.nextLong() | 1;
        }
        seen.add(Limbs.hash(dense, 0, 32));
        n++;
        for (int i = 0; i < 32 * 64; i++) {
            dense[i / 64] ^= 1L << (i % 64);
            seen.add(Limbs.hash(dense, 0, 32));
            n++;
            int j = (i / 128) * 128 + (i * 37 + 11) % 128; // another bit of the same pair of limbs
            if (j != i) {
                dense[j / 64] ^= 1L << (j % 64);
                seen.add(Limbs.hash(dense, 0, 32));
                n++;
                dense[j / 64] ^= 1L << (j % 64);
            }
            dense[i / 64] ^= 1L << (i % 64);
        }
        for (int len = 1; len <= 300; len++) { // same limb, different lengths
            long[] b = new long[len];
            Arrays.fill(b, 0x0123456789ABCDEFL);
            seen.add(Limbs.hash(b, 0, len));
            n++;
        }
        for (int len = 1; len <= 300; len++) { // zero low limbs, different lengths
            long[] b = new long[len];
            b[len - 1] = 1;
            seen.add(Limbs.hash(b, 0, len));
            n++;
        }
        // expected by chance: n^2 / 2^33
        long chance = (long) n * n >> 33;
        assertTrue(n - seen.size() <= 3 * chance + 8, "hash collisions among " + n + " structured values: " + (n - seen.size())
                + " (chance: " + chance + ")");
        System.out.println("LimbsTest hash: " + (n - seen.size()) + " collisions among " + n + " structured values (chance: " + chance + ")");
    }

    @Test
    void addAndSub() {
        Random r = new Random(3);
        for (int i = 0; i < 40000; i++) {
            BigInteger x = rnd(r);
            BigInteger y = rnd(r);
            if (r.nextInt(6) == 0) { // long carry / borrow chains
                x = TestSupport.pow2(64 * (1 + r.nextInt(20))).subtract(BigInteger.valueOf(1 + r.nextInt(2)));
                y = BigInteger.valueOf(r.nextInt(4));
            }
            if (x.compareTo(y) < 0) {
                BigInteger t = x;
                x = y;
                y = t;
            }
            Op a = new Op(x, r);
            Op b = new Op(y, r);
            Res s = new Res(a.len + 1, r);
            int n = Limbs.add(a.a, a.off, a.len, b.a, b.off, b.len, s.r, s.ro);
            BigInteger sum = x.add(y);
            assertEquals(sum, value(s.r, s.ro, n));
            assertEquals(Limbs.limbCount(sum), n, "add returns the exact length");
            s.assertUntouchedOutside();

            Res d = new Res(a.len, r);
            n = Limbs.sub(a.a, a.off, a.len, b.a, b.off, b.len, d.r, d.ro);
            assertEquals(a.len, n);
            assertEquals(x.subtract(y), value(d.r, d.ro, n));
            d.assertUntouchedOutside();
        }
    }

    @Test
    void multiplication() {
        Random r = new Random(4);
        for (int i = 0; i < 30000; i++) {
            BigInteger x = rnd(r);
            BigInteger y = TestSupport.randomNat(r, r.nextInt(4) == 0 ? 1500 : 200);
            if (x.signum() == 0 || y.signum() == 0) {
                continue;
            }
            Op a = new Op(x, r);
            Op b = new Op(y, r);
            Res p = new Res(a.len + b.len, r);
            int n = Limbs.mul(a.a, a.off, a.len, b.a, b.off, b.len, p.r, p.ro);
            assertEquals(a.len + b.len, n);
            assertEquals(x.multiply(y), value(p.r, p.ro, n));
            p.assertUntouchedOutside();

            long m = r.nextInt(3) == 0 ? -1L - r.nextInt(3) : r.nextLong();
            BigInteger mb = BigInteger.valueOf(m).and(MASK64);
            Res q = new Res(a.len + 1, r);
            n = Limbs.mul1(a.a, a.off, a.len, m, q.r, q.ro);
            assertEquals(a.len + 1, n);
            assertEquals(x.multiply(mb), value(q.r, q.ro, n));
            q.assertUntouchedOutside();

            long add = r.nextInt(3) == 0 ? -1L : r.nextLong();
            long[] inPlace = Arrays.copyOf(limbs(x), a.len + 1);
            long carry = Limbs.mulAdd1InPlace(inPlace, 0, a.len, m, add);
            inPlace[a.len] = carry;
            assertEquals(x.multiply(mb).add(BigInteger.valueOf(add).and(MASK64)), value(inPlace, 0, a.len + 1));
        }
    }

    @Test
    void multiplicationOfAllOnesAndSparse() {
        Random r = new Random(5);
        for (int an = 1; an <= 12; an++) {
            for (int bn = 1; bn <= 12; bn++) {
                BigInteger x = TestSupport.pow2(64 * an).subtract(BigInteger.ONE);
                BigInteger y = TestSupport.pow2(64 * bn).subtract(BigInteger.ONE);
                long[] p = new long[an + bn];
                Limbs.mul(limbs(x), 0, an, limbs(y), 0, bn, p, 0);
                assertEquals(x.multiply(y), value(p, 0, an + bn));
            }
        }
        for (int i = 0; i < 300; i++) { // long sparse times short dense: rows of zero limbs are skipped
            BigInteger x = BigInteger.ZERO;
            int bits = 64 * (100 + r.nextInt(200));
            for (int k = 0; k < 20; k++) {
                x = x.setBit(r.nextInt(bits));
            }
            x = x.setBit(bits - 1);
            BigInteger y = new BigInteger(64 * (1 + r.nextInt(13)), r).setBit(0);
            long[] xa = limbs(x);
            long[] ya = limbs(y);
            long[] p = new long[xa.length + ya.length];
            Arrays.fill(p, SENTINEL); // mul must clear its result range itself
            Limbs.mul(xa, 0, xa.length, ya, 0, ya.length, p, 0);
            assertEquals(x.multiply(y), value(p, 0, p.length));
        }
    }

    @Test
    void divisionByOneLimb() {
        Random r = new Random(6);
        long[] edgeDivisors = {1, 2, 3, 7, 10, 0xFFFFL, 0xFFFFFFFEL, 0xFFFFFFFFL, 0x100000000L, 0x100000001L,
            0x1FFFFFFFFL, 0x7FFFFFFFFFFFFFFFL, 0x8000000000000000L, 0x8000000000000001L, 0xFFFFFFFF00000000L,
            0xFFFFFFFF00000001L, 0xFFFFFFFFFFFFFFFEL, 0xFFFFFFFFFFFFFFFFL, 1_000_000_000_000_000_000L,
            0x00000001_00000000L - 5, 0x80000000L, 0xFFFFFFFF_FFFFFFFFL >>> 1, 0x0123456789ABCDEFL};
        for (int i = 0; i < 200000; i++) {
            BigInteger x = r.nextInt(5) == 0
                    ? TestSupport.pow2(64 * (1 + r.nextInt(6))).subtract(BigInteger.valueOf(r.nextInt(3)))
                    : TestSupport.randomNat(r, r.nextInt(8) == 0 ? 2000 : 320);
            if (x.signum() == 0) {
                continue;
            }
            long d;
            switch (r.nextInt(6)) {
                case 0: d = edgeDivisors[r.nextInt(edgeDivisors.length)]; break;
                case 1: d = r.nextLong() >>> r.nextInt(64); break;
                case 2: d = r.nextInt() & 0xFFFFFFFFL; break;
                case 3: d = r.nextLong() | Long.MIN_VALUE; break;
                case 4: d = (1L << r.nextInt(64)) + r.nextInt(3) - 1; break;
                default: d = r.nextLong(); break;
            }
            if (d == 0) {
                continue;
            }
            BigInteger db = BigInteger.valueOf(d).and(MASK64);
            Op a = new Op(x, r);
            Res q = new Res(a.len, r);
            long rem = Limbs.divRem1(a.a, a.off, a.len, d, q.r, q.ro);
            BigInteger[] qr = x.divideAndRemainder(db);
            String what = x.toString(16) + " / " + db.toString(16);
            assertEquals(qr[1], BigInteger.valueOf(rem).and(MASK64), "remainder of " + what);
            assertEquals(qr[0], value(q.r, q.ro, a.len), "quotient of " + what);
            q.assertUntouchedOutside();
            assertEquals(rem, Limbs.divRem1(a.a, a.off, a.len, d, null, 0), "remainder-only variant");
        }
    }

    @Test
    void shifts() {
        Random r = new Random(7);
        for (int i = 0; i < 6000; i++) {
            BigInteger x = rnd(r);
            if (x.signum() == 0) {
                continue;
            }
            Op a = new Op(x, r);
            for (int bs = 0; bs < 64; bs += 1 + r.nextInt(3)) {
                int ls = r.nextInt(4);
                Res l = new Res(a.len + ls + 1, r);
                int n = Limbs.shl(a.a, a.off, a.len, ls, bs, l.r, l.ro);
                BigInteger e = x.shiftLeft(64 * ls + bs);
                assertEquals(e, value(l.r, l.ro, n));
                assertEquals(Limbs.limbCount(e), n, "shl returns the exact length");
                l.assertUntouchedOutside();

                int rs = r.nextInt(a.len);
                Res s = new Res(a.len - rs, r);
                n = Limbs.shr(a.a, a.off, a.len, rs, bs, s.r, s.ro);
                assertEquals(a.len - rs, n);
                assertEquals(x.shiftRight(64 * rs + bs), value(s.r, s.ro, n));
                s.assertUntouchedOutside();
            }
        }
    }

    @Test
    void bitwise() {
        Random r = new Random(8);
        for (int i = 0; i < 40000; i++) {
            BigInteger x = rnd(r);
            BigInteger y = rnd(r);
            Op a = new Op(x, r);
            Op b = new Op(y, r);
            Res and = new Res(Math.min(a.len, b.len), r);
            int n = Limbs.and(a.a, a.off, a.len, b.a, b.off, b.len, and.r, and.ro);
            assertEquals(x.and(y), value(and.r, and.ro, n));
            and.assertUntouchedOutside();
            if (a.len < b.len) {
                Op t = a;
                a = b;
                b = t;
            }
            Res or = new Res(a.len, r);
            n = Limbs.or(a.a, a.off, a.len, b.a, b.off, b.len, or.r, or.ro);
            assertEquals(x.or(y), value(or.r, or.ro, n));
            or.assertUntouchedOutside();
            Res xor = new Res(a.len, r);
            n = Limbs.xor(a.a, a.off, a.len, b.a, b.off, b.len, xor.r, xor.ro);
            assertEquals(x.xor(y), value(xor.r, xor.ro, n));
            xor.assertUntouchedOutside();
        }
    }
}
