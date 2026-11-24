package rlvm.nat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Randomised and boundary differential tests of every operation of {@link Nat} against {@link RefNat}.
 * Operands live partly in a frozen base store and partly in the overlay under test.
 */
class NatOpsDifferentialTest {
    private static final int[] BINARY = {NatOp.ADD, NatOp.SUB, NatOp.MUL, NatOp.GCD, NatOp.MOD, NatOp.DIV, NatOp.BEQ,
        NatOp.BLE, NatOp.LAND, NatOp.LOR, NatOp.XOR, NatOp.SHIFT_RIGHT};
    private static final long[] LIMITS = {Nat.OFFICIAL_MAX_BYTES, 8, 16, 20, 24, 64, 100, 4096};

    /** A frozen base holding every second boundary value, and an overlay with the given limit. */
    private static NatStore overlay(long maxBytes) {
        NatStore base = NatStore.newBase(maxBytes);
        BigInteger[] b = TestSupport.boundaries();
        for (int i = 0; i < b.length; i += 2) {
            Nat.ofBigInteger(base, b[i]);
        }
        base.freeze();
        return base.newOverlay();
    }

    private static void checkBinary(NatStore s, int op, BigInteger x, BigInteger y) {
        long a = TestSupport.h(s, x);
        long b = TestSupport.h(s, y);
        Object expected = RefNat.apply2(op, x, y, s.maxBytes());
        String what = NatOp.name(op) + " " + TestSupport.abbreviate(x) + " " + TestSupport.abbreviate(y) + " [max " + s.maxBytes() + "]";
        long r = Nat.apply2(s, op, a, b);
        if (expected instanceof Boolean) {
            assertEquals((Boolean) expected ? 1 : 0, r, what);
        } else {
            TestSupport.assertOutcome(s, expected, r, what);
        }
        // the operands are unchanged and still canonical
        assertEquals(x, Nat.toBigInteger(s, a), what);
        assertEquals(y, Nat.toBigInteger(s, b), what);
    }

    private static void checkAllBinary(NatStore s, BigInteger x, BigInteger y) {
        for (int op : BINARY) {
            checkBinary(s, op, x, y);
        }
        long a = TestSupport.h(s, x);
        long b = TestSupport.h(s, y);
        int c = x.compareTo(y);
        assertEquals(Integer.signum(c), Integer.signum(Nat.compare(s, a, b)));
        assertEquals(c < 0, Nat.blt(s, a, b));
        assertEquals(c <= 0, Nat.ble(s, a, b));
        assertEquals(c == 0, Nat.beq(s, a, b));
        assertEquals(c == 0, a == b, "handle equality is value equality");
    }

    private static void checkUnary(NatStore s, BigInteger x) {
        long a = TestSupport.h(s, x);
        String what = TestSupport.abbreviate(x) + " [max " + s.maxBytes() + "]";
        TestSupport.assertOutcome(s, RefNat.apply1(NatOp.SUCC, x, s.maxBytes()), Nat.succ(s, a), "succ " + what);
        TestSupport.assertOutcome(s, RefNat.apply1(NatOp.SUCC, x, s.maxBytes()), Nat.apply1(s, NatOp.SUCC, a), "succ " + what);
        TestSupport.assertOutcome(s, RefNat.apply1(NatOp.LOG2, x, s.maxBytes()), Nat.log2(s, a), "log2 " + what);
        BigInteger pred = x.signum() == 0 ? x : x.subtract(BigInteger.ONE);
        TestSupport.assertOutcome(s, pred, Nat.pred(s, a), "pred " + what);
        assertEquals(x.bitLength(), Nat.bitLength(s, a), "bitLength " + what);
        assertEquals(RefNat.sizeInBytes(x), Nat.sizeInBytes(s, a), "sizeInBytes " + what);
        assertEquals(x.signum() == 0, Nat.isZero(a));
        for (long limit : new long[] {0, 7, 8, 9, 15, 16, 17, 24, 4096, Nat.OFFICIAL_MAX_BYTES}) {
            assertEquals(RefNat.sizeInBytes(x) <= limit, Nat.fitsLimit(s, a, limit));
        }
    }

    @Test
    void boundaryMatrix() {
        BigInteger[] b = TestSupport.boundaries();
        for (long limit : new long[] {Nat.OFFICIAL_MAX_BYTES, 16, 64}) {
            NatStore s = overlay(limit);
            for (BigInteger x : b) {
                checkUnary(s, x);
                for (BigInteger y : b) {
                    checkAllBinary(s, x, y);
                }
            }
        }
    }

    @Test
    void randomOperands() {
        Random r = new Random(11);
        for (long limit : LIMITS) {
            NatStore s = overlay(limit);
            int mark = s.mark();
            for (int i = 0; i < 6000; i++) {
                int maxBits = r.nextInt(16) == 0 ? 6000 : r.nextInt(4) == 0 ? 1000 : 300;
                BigInteger x = TestSupport.randomNat(r, maxBits);
                BigInteger y;
                switch (r.nextInt(8)) {
                    case 0: y = x; break;
                    case 1: y = x.add(BigInteger.valueOf(r.nextInt(5) - 2)).abs(); break;
                    case 2: y = TestSupport.randomNat(r, 70); break;
                    case 3: y = x.shiftRight(r.nextInt(x.bitLength() + 1)); break;
                    default: y = TestSupport.randomNat(r, maxBits); break;
                }
                if (r.nextBoolean()) {
                    BigInteger t = x;
                    x = y;
                    y = t;
                }
                checkUnary(s, x);
                checkAllBinary(s, x, y);
                if (i % 500 == 499) {
                    s.truncate(mark); // exercise release and re-interning along the way
                }
            }
        }
    }

    @Test
    void wordSizedOperandsAroundOverflow() {
        // every pair from a dense set around 2^31, 2^32, 2^62, 2^63, 2^64: the long fast paths and their exits
        long[] near = new long[80];
        int n = 0;
        for (int sh : new int[] {31, 32, 62}) {
            for (int d = -3; d <= 3; d++) {
                near[n++] = (1L << sh) + d;
            }
        }
        for (int d = 0; d < 6; d++) {
            near[n++] = Long.MAX_VALUE - d;
            near[n++] = d;
        }
        near[n++] = 3037000499L;
        near[n++] = 3037000500L;
        near[n++] = 6074000999L;
        near[n++] = 4611686018427387904L / 3;
        BigInteger[] vals = new BigInteger[n + 14];
        for (int i = 0; i < n; i++) {
            vals[i] = BigInteger.valueOf(near[i]);
        }
        for (int d = 0; d < 7; d++) {
            vals[n + d] = TestSupport.TWO63.add(BigInteger.valueOf(d));
            vals[n + 7 + d] = TestSupport.TWO64.add(BigInteger.valueOf(d - 3));
        }
        NatStore s = overlay(Nat.OFFICIAL_MAX_BYTES);
        for (BigInteger x : vals) {
            checkUnary(s, x);
            for (BigInteger y : vals) {
                checkAllBinary(s, x, y);
            }
        }
    }

    @Test
    void randomLongPairsUseTheFastPathsExactly() {
        Random r = new Random(12);
        NatStore s = overlay(Nat.OFFICIAL_MAX_BYTES);
        for (int i = 0; i < 300000; i++) {
            long a = r.nextLong() >>> (1 + r.nextInt(63));
            long b = r.nextLong() >>> (1 + r.nextInt(63));
            BigInteger x = BigInteger.valueOf(a);
            BigInteger y = BigInteger.valueOf(b);
            for (int op : BINARY) {
                Object expected = RefNat.apply2(op, x, y, s.maxBytes());
                long res = Nat.apply2(s, op, a, b);
                if (expected instanceof Boolean) {
                    assertEquals((Boolean) expected ? 1 : 0, res);
                } else {
                    BigInteger e = (BigInteger) expected;
                    if (e.bitLength() < 64) {
                        assertEquals(e.longValue(), res, NatOp.name(op) + " " + a + " " + b);
                    } else {
                        TestSupport.assertCanonical(s, res, e);
                    }
                }
            }
            if (i % 4096 == 0) {
                s.reset();
            }
        }
    }

    // ------------------------------------------------------------------ pow and shiftLeft

    private static void checkPow(NatStore s, BigInteger x, BigInteger y, long limit) {
        long a = TestSupport.h(s, x);
        long b = TestSupport.h(s, y);
        long eff = Math.min(limit, NatStore.HARD_MAX_BYTES);
        Object expected = guardOnly(NatOp.POW, x, y, eff);
        String what = "Nat.pow " + TestSupport.abbreviate(x) + " " + TestSupport.abbreviate(y) + " [limit " + limit + "]";
        long g = Nat.powGuard(s, a, b, limit);
        long res;
        if (expected == null) {
            assertEquals(Nat.OK, g, what);
            // only compute what is affordable for the reference
            if (x.bitLength() * y.doubleValue() > 1 << 21) {
                return;
            }
            res = Nat.pow(s, a, b, limit);
            TestSupport.assertOutcome(s, RefNat.apply2(NatOp.POW, x, y, eff), res, what);
        } else {
            assertEquals(expected == RefNat.TOO_BIG ? Nat.TOO_BIG : Nat.COUNT_TOO_BIG, g, what);
            res = Nat.pow(s, a, b, limit);
            assertEquals(g, res, what);
        }
        if (limit == s.maxBytes()) {
            assertEquals(res, Nat.pow(s, a, b), what);
            assertEquals(res, Nat.apply2(s, NatOp.POW, a, b), what);
        }
    }

    private static void checkShiftLeft(NatStore s, BigInteger x, BigInteger y, long limit) {
        long a = TestSupport.h(s, x);
        long b = TestSupport.h(s, y);
        long eff = Math.min(limit, NatStore.HARD_MAX_BYTES);
        Object expected = guardOnly(NatOp.SHIFT_LEFT, x, y, eff);
        String what = "Nat.shiftLeft " + TestSupport.abbreviate(x) + " " + TestSupport.abbreviate(y) + " [limit " + limit + "]";
        long g = Nat.shiftLeftGuard(s, a, b, limit);
        long res;
        if (expected == null) {
            assertEquals(Nat.OK, g, what);
            if (x.signum() != 0 && y.bitLength() > 22) {
                return; // affordable results only
            }
            res = Nat.shiftLeft(s, a, b, limit);
            TestSupport.assertOutcome(s, RefNat.apply2(NatOp.SHIFT_LEFT, x, y, eff), res, what);
        } else {
            assertEquals(expected == RefNat.TOO_BIG ? Nat.TOO_BIG : Nat.COUNT_TOO_BIG, g, what);
            res = Nat.shiftLeft(s, a, b, limit);
            assertEquals(g, res, what);
        }
        if (limit == s.maxBytes()) {
            assertEquals(res, Nat.shiftLeft(s, a, b), what);
            assertEquals(res, Nat.apply2(s, NatOp.SHIFT_LEFT, a, b), what);
        }
    }

    /** The reference guard without the computation: a marker, or null if the operation is to be computed. */
    private static Object guardOnly(int op, BigInteger x, BigInteger y, long max) {
        if (op == NatOp.SHIFT_LEFT) {
            if (x.signum() == 0) {
                return null;
            }
            if (y.compareTo(RefNat.UINT_MAX) > 0) {
                return RefNat.COUNT_TOO_BIG;
            }
            return RefNat.sizeInBytes(x) + y.longValue() / 8 + 1 > max ? RefNat.TOO_BIG : null;
        }
        if (y.compareTo(RefNat.UINT_MAX) > 0) {
            return RefNat.COUNT_TOO_BIG;
        }
        long k = y.longValue();
        return x.compareTo(BigInteger.ONE) > 0 && k != 0 && RefNat.sizeInBytes(x) > max / k ? RefNat.TOO_BIG : null;
    }

    private static final long[] GUARD_LIMITS = {-1, 0, 1, 7, 8, 9, 15, 16, 17, 23, 24, 25, 63, 64, 65, 100, 1000, 4096,
        65536, Nat.OFFICIAL_MAX_BYTES - 1, Nat.OFFICIAL_MAX_BYTES, Nat.OFFICIAL_MAX_BYTES + 1, Long.MAX_VALUE};

    @Test
    void powGuardsAndValues() {
        Random r = new Random(13);
        NatStore s = overlay(Nat.OFFICIAL_MAX_BYTES);
        BigInteger[] bases = TestSupport.boundaries();
        long[] exps = {0, 1, 2, 3, 4, 5, 7, 8, 9, 15, 16, 17, 31, 32, 33, 62, 63, 64, 65, 127, 128, 129, 511, 512, 513,
            4095, 4096, 4097, 65535, 65536, (1 << 24) - 1, 1 << 24, (1 << 24) + 1, Integer.MAX_VALUE, 1L << 31,
            (1L << 32) - 2, (1L << 32) - 1, 1L << 32, (1L << 32) + 1, Long.MAX_VALUE};
        for (long limit : GUARD_LIMITS) {
            for (BigInteger x : bases) {
                for (long e : exps) {
                    checkPow(s, x, BigInteger.valueOf(e), limit);
                }
                checkPow(s, x, TestSupport.TWO63, limit);
                checkPow(s, x, TestSupport.TWO64, limit);
                checkPow(s, x, TestSupport.pow2(200), limit);
                // the exponents around the guard's threshold for this base and limit
                long eff = Math.min(limit, NatStore.HARD_MAX_BYTES);
                if (eff > 0) {
                    long k0 = eff / RefNat.sizeInBytes(x);
                    for (long k = Math.max(0, k0 - 2); k <= k0 + 2; k++) {
                        checkPow(s, x, BigInteger.valueOf(k), limit);
                    }
                }
            }
            s.reset();
        }
        for (int i = 0; i < 20000; i++) {
            BigInteger x = TestSupport.randomNat(r, r.nextInt(4) == 0 ? 400 : 70);
            BigInteger y = r.nextInt(10) == 0 ? TestSupport.randomNat(r, 80) : BigInteger.valueOf(r.nextInt(r.nextInt(4) == 0 ? 2000 : 40));
            checkPow(s, x, y, GUARD_LIMITS[r.nextInt(GUARD_LIMITS.length)]);
            if (i % 1000 == 999) {
                s.reset();
            }
        }
    }

    @Test
    void smallPowersAgreeWithRepeatedMultiplication() {
        NatStore s = overlay(Nat.OFFICIAL_MAX_BYTES);
        for (long a = 0; a <= 300; a++) {
            long acc = 1;
            BigInteger big = BigInteger.ONE;
            for (long e = 0; e <= 70; e++) {
                long p = Nat.pow(s, a, e);
                TestSupport.assertCanonical(s, p, big);
                assertEquals(acc, p, "handles are canonical");
                acc = Nat.mul(s, acc, a);
                big = big.multiply(BigInteger.valueOf(a));
            }
        }
    }

    @Test
    void shiftLeftGuardsAndValues() {
        Random r = new Random(14);
        NatStore s = overlay(Nat.OFFICIAL_MAX_BYTES);
        BigInteger[] vals = TestSupport.boundaries();
        long[] counts = {0, 1, 2, 7, 8, 9, 31, 32, 33, 61, 62, 63, 64, 65, 66, 127, 128, 129, 1000, 65536, 1 << 22,
            Integer.MAX_VALUE, 1L << 31, (1L << 32) - 1, 1L << 32, (1L << 32) + 1, Long.MAX_VALUE};
        for (long limit : GUARD_LIMITS) {
            for (BigInteger x : vals) {
                for (long k : counts) {
                    checkShiftLeft(s, x, BigInteger.valueOf(k), limit);
                }
                checkShiftLeft(s, x, TestSupport.TWO63, limit);
                checkShiftLeft(s, x, TestSupport.TWO64, limit);
                checkShiftLeft(s, x, TestSupport.pow2(200), limit);
                long eff = Math.min(limit, NatStore.HARD_MAX_BYTES);
                long k0 = (eff - 1 - RefNat.sizeInBytes(x)) * 8;
                for (long k = k0 - 9; k <= k0 + 9; k++) {
                    if (k >= 0) {
                        checkShiftLeft(s, x, BigInteger.valueOf(k), limit);
                    }
                }
            }
            s.reset();
        }
        for (int i = 0; i < 30000; i++) {
            BigInteger x = TestSupport.randomNat(r, r.nextInt(4) == 0 ? 600 : 130);
            BigInteger y = r.nextInt(10) == 0 ? TestSupport.randomNat(r, 80) : BigInteger.valueOf(r.nextInt(r.nextInt(4) == 0 ? 3000 : 200));
            checkShiftLeft(s, x, y, GUARD_LIMITS[r.nextInt(GUARD_LIMITS.length)]);
            if (i % 1000 == 999) {
                s.reset();
            }
        }
    }

    @Test
    void shiftLeftFastPathIsExactForEveryCount() {
        NatStore s = overlay(Nat.OFFICIAL_MAX_BYTES);
        long[] vals = {1, 2, 3, 5, 0x7F, 0xFF, 0x100, 0xFFFF, 0x7FFFFFFFL, 0xFFFFFFFFL, 0x100000000L, 1L << 61, (1L << 62) - 1,
            1L << 62, Long.MAX_VALUE};
        for (long a : vals) {
            for (int k = 0; k <= 130; k++) {
                for (long limit : new long[] {8, 9, 15, 16, 17, 24, 32, Nat.OFFICIAL_MAX_BYTES}) {
                    checkShiftLeft(s, BigInteger.valueOf(a), BigInteger.valueOf(k), limit);
                }
            }
        }
    }

    @Test
    void hugeShiftsNeverAllocate() {
        // bugs/nat-shift-huge: counts of 2^64 and beyond on zero, and right shifts by them
        NatStore s = overlay(Nat.OFFICIAL_MAX_BYTES);
        long two64 = TestSupport.h(s, TestSupport.TWO64);
        long huge = TestSupport.h(s, TestSupport.pow2(5000));
        long big = TestSupport.h(s, TestSupport.pow2(300).subtract(BigInteger.ONE));
        long before = s.bytesUsed();
        assertEquals(0, Nat.shiftLeft(s, 0, two64));
        assertEquals(0, Nat.shiftLeft(s, 0, huge));
        assertEquals(0, Nat.shiftRight(s, 42, two64));
        assertEquals(0, Nat.shiftRight(s, 42, huge));
        assertEquals(0, Nat.shiftRight(s, big, two64));
        assertEquals(0, Nat.shiftRight(s, big, huge));
        assertEquals(0, Nat.shiftRight(s, big, 1L << 32));
        assertEquals(0, Nat.shiftRight(s, big, Long.MAX_VALUE));
        assertEquals(0, Nat.shiftRight(s, big, 300));
        assertEquals(1, Nat.shiftRight(s, big, 299));
        assertEquals(Nat.COUNT_TOO_BIG, Nat.shiftLeft(s, 1, two64));
        assertEquals(Nat.COUNT_TOO_BIG, Nat.shiftLeft(s, big, 1L << 32));
        assertEquals(Nat.TOO_BIG, Nat.shiftLeft(s, 1, (1L << 32) - 1));
        assertEquals(Nat.COUNT_TOO_BIG, Nat.pow(s, 0, two64));
        assertEquals(Nat.COUNT_TOO_BIG, Nat.pow(s, 1, 1L << 32));
        assertEquals(1, Nat.pow(s, 1, (1L << 32) - 1));
        assertEquals(0, Nat.pow(s, 0, (1L << 32) - 1));
        assertEquals(1, Nat.pow(s, 0, 0));
        assertEquals(Nat.TOO_BIG, Nat.pow(s, 2, (1L << 24) + 1));
        assertEquals(before, s.bytesUsed(), "nothing was stored");
    }

    // ------------------------------------------------------------------ algorithm switches inside mul / div / mod

    @Test
    void multiplicationAcrossAlgorithmThresholds() {
        Random r = new Random(15);
        NatStore s = overlay(Nat.OFFICIAL_MAX_BYTES);
        int t = NatStore.MUL_DELEGATE_MIN; // schoolbook kernel below, BigInteger from here on
        int[] sizes = {1, 2, 3, 4, 5, 6, 7, 8, 9, 12, 16, 23, 31, 32, 33, t - 2, t - 1, t, t + 1, t + 2, 64, 79, 80, 81, 90, 130};
        for (int an : sizes) {
            for (int bn : sizes) {
                for (int rep = 0; rep < 4; rep++) {
                    BigInteger x = new BigInteger(64 * an, r).setBit(64 * an - 1 - r.nextInt(3));
                    BigInteger y = new BigInteger(64 * bn, r).setBit(64 * bn - 1 - r.nextInt(3));
                    if (rep == 1) {
                        x = TestSupport.pow2(64 * an).subtract(BigInteger.ONE);
                        y = TestSupport.pow2(64 * bn).subtract(BigInteger.ONE);
                    }
                    checkBinary(s, NatOp.MUL, x, y);
                }
            }
            s.reset();
        }
        // long sparse operands (row-skipping schoolbook) against short and long dense ones
        for (int i = 0; i < 400; i++) {
            int bits = 64 * (64 + r.nextInt(400));
            BigInteger x = BigInteger.ZERO.setBit(bits - 1);
            int set = r.nextInt(4) == 0 ? bits / 64 : 1 + r.nextInt(bits / 300);
            for (int k = 0; k < set; k++) {
                x = x.setBit(r.nextInt(bits));
            }
            BigInteger y = new BigInteger(64 * (1 + r.nextInt(r.nextInt(3) == 0 ? 120 : 20)), r).setBit(0);
            if (i % 7 == 0) {
                y = y.setBit(64 * (t - 1 + i % 3) - 1); // exactly t - 1, t, t + 1 limbs
            }
            checkBinary(s, NatOp.MUL, x, y);
            checkBinary(s, NatOp.MUL, y, x);
            if (i % 50 == 49) {
                s.reset();
            }
        }
    }

    @Test
    void divisionAndRemainderSpecialCases() {
        Random r = new Random(16);
        NatStore s = overlay(Nat.OFFICIAL_MAX_BYTES);
        for (int i = 0; i < 30000; i++) {
            BigInteger x = TestSupport.randomNat(r, r.nextInt(8) == 0 ? 3000 : 400);
            BigInteger y;
            switch (r.nextInt(7)) {
                case 0: y = TestSupport.pow2(r.nextInt(500)); break; // power of two: shift / mask path
                case 1: y = BigInteger.valueOf(r.nextLong() >>> 1); break; // one limb, below 2^63
                case 2: y = BigInteger.valueOf(r.nextLong()).and(TestSupport.TWO64.subtract(BigInteger.ONE)); break; // one limb, any
                case 3: y = x; break;
                case 4: y = x.add(BigInteger.ONE); break;
                case 5: y = x.shiftRight(r.nextInt(x.bitLength() + 1)).add(BigInteger.valueOf(r.nextInt(3))); break;
                default: y = TestSupport.randomNat(r, 400); break;
            }
            checkBinary(s, NatOp.DIV, x, y);
            checkBinary(s, NatOp.MOD, x, y);
            checkBinary(s, NatOp.GCD, x, y);
            if (y.signum() != 0) { // q * y + r = x with r < y, through our own operations
                long a = TestSupport.h(s, x);
                long b = TestSupport.h(s, y);
                long q = Nat.div(s, a, b);
                long m = Nat.mod(s, a, b);
                assertEquals(a, Nat.add(s, Nat.mul(s, q, b), m));
                assertTrue(Nat.blt(s, m, b));
            }
            if (i % 1000 == 999) {
                s.reset();
            }
        }
    }

    @Test
    void algebraicIdentitiesOnBigOperands() {
        Random r = new Random(17);
        NatStore s = overlay(Nat.OFFICIAL_MAX_BYTES);
        for (int i = 0; i < 4000; i++) {
            long a = TestSupport.h(s, TestSupport.randomNat(r, 900));
            long b = TestSupport.h(s, TestSupport.randomNat(r, 900));
            long c = TestSupport.h(s, TestSupport.randomNat(r, 200));
            assertEquals(Nat.add(s, a, b), Nat.add(s, b, a));
            assertEquals(Nat.mul(s, a, b), Nat.mul(s, b, a));
            assertEquals(a, Nat.sub(s, Nat.add(s, a, b), b));
            assertEquals(Nat.mul(s, Nat.add(s, a, b), c), Nat.add(s, Nat.mul(s, a, c), Nat.mul(s, b, c)));
            assertEquals(Nat.xor(s, a, b), Nat.sub(s, Nat.lor(s, a, b), Nat.land(s, a, b)));
            assertEquals(a, Nat.xor(s, Nat.xor(s, a, b), b));
            int k = r.nextInt(300);
            assertEquals(a, Nat.shiftRight(s, Nat.shiftLeft(s, a, k), k));
            assertEquals(Nat.shiftLeft(s, a, k), Nat.mul(s, a, Nat.pow(s, 2, k)));
            assertEquals(Nat.shiftRight(s, a, k), Nat.div(s, a, Nat.pow(s, 2, k)));
            assertEquals(Nat.succ(s, Nat.pred(s, Nat.succ(s, a))), Nat.succ(s, a));
            if (i % 500 == 499) {
                s.reset();
            }
        }
    }

    // ------------------------------------------------------------------ error handles

    @Test
    void errorHandlesPropagateAndPredicatesRefuseThem() {
        NatStore s = overlay(Nat.OFFICIAL_MAX_BYTES);
        long big = TestSupport.h(s, TestSupport.pow2(100));
        long[] errors = {Nat.NONE, Nat.TOO_BIG, Nat.COUNT_TOO_BIG, Nat.MALFORMED};
        long[] values = {0, 1, 5, Long.MAX_VALUE, big};
        for (long e : errors) {
            assertTrue(Nat.isError(e));
            assertTrue(!Nat.isValue(e) && !Nat.isBig(e) && !Nat.isSmall(e));
            for (long v : values) {
                for (int op = NatOp.ADD; op <= NatOp.SHIFT_RIGHT; op++) {
                    if (NatOp.isPredicate(op)) {
                        final int o = op;
                        assertThrows(IllegalArgumentException.class, () -> Nat.apply2(s, o, e, v));
                        assertThrows(IllegalArgumentException.class, () -> Nat.apply2(s, o, v, e));
                    } else {
                        assertEquals(e, Nat.apply2(s, op, e, v), NatOp.name(op));
                        assertEquals(e, Nat.apply2(s, op, v, e), NatOp.name(op));
                        assertEquals(e, Nat.apply2(s, op, e, Nat.MALFORMED == e ? Nat.NONE : Nat.MALFORMED), "first error wins");
                    }
                }
                assertThrows(IllegalArgumentException.class, () -> Nat.compare(s, e, v));
                assertThrows(IllegalArgumentException.class, () -> Nat.blt(s, v, e));
                assertEquals(e, Nat.powGuard(s, e, v, 100));
                assertEquals(e, Nat.shiftLeftGuard(s, v, e, 100));
            }
            assertEquals(e, Nat.succ(s, e));
            assertEquals(e, Nat.pred(s, e));
            assertEquals(e, Nat.log2(s, e));
            assertThrows(IllegalArgumentException.class, () -> Nat.bitLength(s, e));
            assertThrows(IllegalArgumentException.class, () -> Nat.sizeInBytes(s, e));
            assertThrows(IllegalArgumentException.class, () -> Nat.toBigInteger(s, e));
            assertThrows(IllegalArgumentException.class, () -> StrLit.charOfNat(e));
            assertTrue(Nat.describe(s, e).startsWith("<"));
        }
        // a handle that is neither a value nor one of the named errors, and ids that are not live
        long junk = Nat.BIG | (5L << 32) | 7;
        long stale = Nat.ofBigId(s.nextId());
        assertTrue(Nat.isError(junk));
        assertEquals(junk, Nat.add(s, junk, 1));
        assertThrows(IllegalArgumentException.class, () -> Nat.add(s, stale, 1));
        assertThrows(IllegalArgumentException.class, () -> Nat.add(s, 1, stale));
        assertThrows(IllegalArgumentException.class, () -> Nat.mul(s, stale, big));
        assertThrows(IllegalArgumentException.class, () -> Nat.land(s, big, stale));
        assertThrows(IllegalArgumentException.class, () -> Nat.beq(s, stale, big));
        assertThrows(IllegalArgumentException.class, () -> Nat.sizeInBytes(s, stale));
        assertThrows(IllegalArgumentException.class, () -> Nat.shiftRight(s, stale, 3));
        assertThrows(IllegalArgumentException.class, () -> Nat.pow(s, stale, 3));
        assertThrows(IllegalArgumentException.class, () -> Nat.apply2(s, NatOp.SUCC, 1, 2));
        assertThrows(IllegalArgumentException.class, () -> Nat.apply1(s, NatOp.ADD, 1));
        assertThrows(IllegalArgumentException.class, () -> Nat.ofBigInteger(s, BigInteger.valueOf(-1)));
    }

    @Test
    void opTableIsConsistent() {
        int official = 0;
        for (int op = NatOp.FIRST; op <= NatOp.LAST; op++) {
            assertEquals(op, NatOp.ofShortName(NatOp.shortName(op)));
            assertEquals("Nat." + NatOp.shortName(op), NatOp.name(op));
            assertTrue(NatOp.arity(op) == 1 || NatOp.arity(op) == 2);
            if (NatOp.isOfficial(op)) {
                official++;
            }
        }
        assertEquals(15, official, "the v4.36 kernel accelerates exactly 15 functions");
        assertEquals(0, NatOp.ofShortName("log"));
        assertEquals(0, NatOp.ofShortName("blt"));
        assertTrue(!NatOp.isOfficial(NatOp.LOG2));
        assertTrue(NatOp.checksResultSize(NatOp.SUCC) && NatOp.checksResultSize(NatOp.MUL) && !NatOp.checksResultSize(NatOp.POW));
        assertTrue(NatOp.hasCountGuard(NatOp.POW) && NatOp.hasCountGuard(NatOp.SHIFT_LEFT) && !NatOp.hasCountGuard(NatOp.SHIFT_RIGHT));
        assertThrows(IllegalArgumentException.class, () -> NatOp.name(0));
        assertThrows(IllegalArgumentException.class, () -> NatOp.arity(17));
    }
}
