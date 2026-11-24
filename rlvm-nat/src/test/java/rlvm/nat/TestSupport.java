package rlvm.nat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.math.BigInteger;
import java.util.Random;

/** Shared helpers of the rlvm-nat tests. */
final class TestSupport {
    private TestSupport() {}

    static final BigInteger TWO63 = BigInteger.ONE.shiftLeft(63);
    static final BigInteger TWO64 = BigInteger.ONE.shiftLeft(64);

    static BigInteger pow2(int n) {
        return BigInteger.ONE.shiftLeft(n);
    }

    /** Values at which the representation or an algorithm changes. */
    static BigInteger[] boundaries() {
        java.util.ArrayList<BigInteger> l = new java.util.ArrayList<>();
        long[] small = {0, 1, 2, 3, 4, 5, 7, 8, 9, 10, 15, 16, 17, 63, 64, 65, 255, 256, 65535, 65536,
            (1L << 31) - 1, 1L << 31, (1L << 31) + 1, (1L << 32) - 1, 1L << 32, (1L << 32) + 1,
            3037000499L, 3037000500L, 1L << 62, (1L << 62) + 1, Long.MAX_VALUE - 1, Long.MAX_VALUE};
        for (long v : small) {
            l.add(BigInteger.valueOf(v));
        }
        int[] bits = {63, 64, 65, 126, 127, 128, 129, 191, 192, 193, 255, 256, 257, 320, 384, 512, 1024};
        for (int b : bits) {
            BigInteger p = pow2(b);
            l.add(p.subtract(BigInteger.TWO));
            l.add(p.subtract(BigInteger.ONE));
            l.add(p);
            l.add(p.add(BigInteger.ONE));
            l.add(p.add(BigInteger.TWO));
        }
        l.add(BigInteger.TEN.pow(19));
        l.add(BigInteger.TEN.pow(40));
        l.add(pow2(64).multiply(BigInteger.valueOf(3)));
        l.add(pow2(128).subtract(pow2(64)));
        l.add(pow2(192).add(pow2(64)));
        return l.toArray(new BigInteger[0]);
    }

    /** A random natural biased towards word boundaries, sparse and dense bit patterns. */
    static BigInteger randomNat(Random r, int maxBits) {
        int bits;
        switch (r.nextInt(10)) {
            case 0: bits = r.nextInt(8); break;
            case 1: bits = 60 + r.nextInt(10); break;
            case 2: bits = 120 + r.nextInt(16); break;
            case 3: bits = 64 * (1 + r.nextInt(Math.max(1, maxBits / 64))) + r.nextInt(3) - 1; break;
            default: bits = r.nextInt(maxBits + 1); break;
        }
        bits = Math.max(0, Math.min(bits, maxBits));
        BigInteger v = new BigInteger(bits, r);
        switch (r.nextInt(12)) {
            case 0: return bits == 0 ? v : pow2(bits).subtract(BigInteger.ONE);
            case 1: return bits == 0 ? v : pow2(bits - 1);
            case 2: return bits == 0 ? v : pow2(bits - 1).add(BigInteger.valueOf(r.nextInt(3)));
            case 3: { // zero limbs in the middle / low part
                int z = r.nextInt(bits + 1);
                return v.shiftRight(z).shiftLeft(z);
            }
            case 4: { // sparse: few bits set
                BigInteger s = BigInteger.ZERO;
                for (int i = 0; i < 3 && bits > 0; i++) {
                    s = s.setBit(r.nextInt(bits));
                }
                return s;
            }
            case 5: { // all-ones limbs
                BigInteger s = v;
                for (int i = 0; i + 64 <= bits; i += 64) {
                    if (r.nextBoolean()) {
                        s = s.or(pow2(64).subtract(BigInteger.ONE).shiftLeft(i));
                    }
                }
                return s;
            }
            default: return v;
        }
    }

    /** Converts to a handle and checks canonicity of the conversion. */
    static long h(NatStore s, BigInteger v) {
        long h = Nat.ofBigInteger(s, v);
        assertCanonical(s, h, v);
        return h;
    }

    /** Checks that {@code h} is the canonical handle of {@code expected} in {@code s}. */
    static void assertCanonical(NatStore s, long h, BigInteger expected) {
        if (!Nat.isValue(h)) {
            fail("expected the number " + abbreviate(expected) + " but got " + Nat.describe(s, h));
        }
        BigInteger actual = Nat.toBigInteger(s, h);
        if (!actual.equals(expected)) {
            fail("expected " + abbreviate(expected) + " but got " + abbreviate(actual));
        }
        assertEquals(expected.compareTo(TWO63) < 0, Nat.isSmall(h), "small/big split");
        assertEquals(!Nat.isSmall(h), Nat.isBig(h));
        if (Nat.isBig(h)) {
            int id = Nat.bigId(h);
            assertTrue(id >= 1 && id < s.nextId(), "id in range");
            int n = s.limbCount(id);
            assertEquals((expected.bitLength() + 63) / 64, n, "limb count");
            assertTrue(s.limb(id, n - 1) != 0, "normalised");
            if (!s.isFrozen()) {
                assertEquals(h, Nat.ofBigInteger(s, expected), "hash-consing: re-interning gives the same handle");
            }
        }
    }

    static String abbreviate(BigInteger v) {
        String t = v.toString();
        return t.length() <= 80 ? t : t.substring(0, 30) + "..(" + t.length() + " digits).." + t.substring(t.length() - 30);
    }

    /** Compares the outcome of an operation with the reference outcome. */
    static void assertOutcome(NatStore s, Object expected, long actual, String what) {
        if (expected == RefNat.TOO_BIG) {
            assertEquals(Nat.TOO_BIG, actual, what);
        } else if (expected == RefNat.COUNT_TOO_BIG) {
            assertEquals(Nat.COUNT_TOO_BIG, actual, what);
        } else {
            try {
                assertCanonical(s, actual, (BigInteger) expected);
            } catch (AssertionError e) {
                throw new AssertionError(what + ": " + e.getMessage(), e);
            }
        }
    }
}
