package rlvm.api.bind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import rlvm.api.KernelException;
import rlvm.api.NatOps;
import rlvm.api.Reason;
import rlvm.api.ResourceException;
import rlvm.nat.Nat;
import rlvm.nat.NatStore;

/**
 * {@link NatBinding} against a BigInteger model of {@code reduce_nat} (type_checker.cpp, v4.36), on an
 * array-backed {@link FakeLits}. The arithmetic itself is rlvm-nat's (tested there against the
 * official kernel's own results); what is tested here is the bridge: operand import, result
 * representation, the mapping of guard results to exceptions, literal identity and lifetime.
 */
class NatBindingTest {
    private static final BigInteger TWO_63 = BigInteger.ONE.shiftLeft(63);
    private static final BigInteger TWO_64 = BigInteger.ONE.shiftLeft(64);
    private static final BigInteger UINT_MAX = BigInteger.valueOf(0xFFFFFFFFL);

    private static final int[] BINARY = {NatOps.ADD, NatOps.SUB, NatOps.MUL, NatOps.POW, NatOps.GCD, NatOps.MOD,
        NatOps.DIV, NatOps.LAND, NatOps.LOR, NatOps.XOR, NatOps.SHIFT_LEFT, NatOps.SHIFT_RIGHT};

    private static NatStore overlay(long maxBytes) {
        NatStore base = NatStore.newBase();
        base.freeze();
        return maxBytes > 0 ? base.newOverlay(maxBytes) : base.newOverlay();
    }

    private static long size(BigInteger n) {
        return n.compareTo(TWO_63) < 0 ? 8 : 8L * ((n.bitLength() + 63) / 64);
    }

    /** The official result, or null where the official kernel throws. */
    private static BigInteger model(int op, BigInteger a, BigInteger b, long limit) {
        BigInteger r;
        switch (op) {
            case NatOps.ADD:
                r = a.add(b);
                return size(r) > limit ? null : r;
            case NatOps.SUB:
                r = a.compareTo(b) <= 0 ? BigInteger.ZERO : a.subtract(b);
                return size(r) > limit ? null : r;
            case NatOps.MUL:
                r = a.multiply(b);
                return size(r) > limit ? null : r;
            case NatOps.POW: {
                if (b.compareTo(UINT_MAX) > 0) {
                    return null;
                }
                long k = b.longValue();
                if (a.compareTo(BigInteger.ONE) > 0 && k != 0 && size(a) > limit / k) {
                    return null;
                }
                if (a.compareTo(BigInteger.ONE) <= 0) {
                    return k == 0 ? BigInteger.ONE : a;
                }
                return a.pow((int) k);
            }
            case NatOps.GCD:
                return a.gcd(b);
            case NatOps.MOD:
                return b.signum() == 0 ? a : a.mod(b);
            case NatOps.DIV:
                return b.signum() == 0 ? BigInteger.ZERO : a.divide(b);
            case NatOps.LAND:
                return a.and(b);
            case NatOps.LOR:
                return a.or(b);
            case NatOps.XOR:
                return a.xor(b);
            case NatOps.SHIFT_LEFT: {
                if (a.signum() == 0) {
                    return BigInteger.ZERO;
                }
                if (b.compareTo(UINT_MAX) > 0) {
                    return null;
                }
                long k = b.longValue();
                if (size(a) + k / 8 + 1 > limit) {
                    return null;
                }
                return a.shiftLeft((int) k);
            }
            case NatOps.SHIFT_RIGHT:
                return b.compareTo(BigInteger.valueOf(a.bitLength())) >= 0 ? BigInteger.ZERO : a.shiftRight(b.intValue());
            default:
                throw new IllegalArgumentException();
        }
    }

    private static List<BigInteger> matrix(Random rnd, int maxBits, int randoms) {
        List<BigInteger> v = new ArrayList<>();
        long[] small = {0, 1, 2, 3, 7, 8, 9, 63, 64, 65, 255, 0x7FFFFFFFL, 0x80000000L, 0xFFFFFFFFL, 0x100000000L,
            0x100000001L, 1L << 62, Long.MAX_VALUE};
        for (long s : small) {
            v.add(BigInteger.valueOf(s));
        }
        for (int bits : new int[] {63, 64, 65, 127, 128, 129, 191, 192}) {
            if (bits + 1 <= maxBits) {
                BigInteger p = BigInteger.ONE.shiftLeft(bits);
                v.add(p.subtract(BigInteger.ONE));
                v.add(p);
                v.add(p.add(BigInteger.ONE));
            }
        }
        for (int i = 0; i < randoms; i++) {
            v.add(new BigInteger(1 + rnd.nextInt(maxBits), rnd));
        }
        return v;
    }

    /** Invariants of every literal the binding returns. */
    private static void checkResultShape(FakeLits lits, int term, BigInteger expected) {
        assertEquals(expected, lits.value(term));
        if (expected.compareTo(TWO_64) < 0) {
            assertTrue(lits.natIsWord(term), "a value below 2^64 must be a word literal: " + expected);
        } else {
            assertFalse(lits.natIsWord(term), "a value of 2^64 or more is pooled: " + expected);
            assertTrue(lits.bigLimbCount(lits.natRef(term)) >= 2);
        }
    }

    private static void differential(long limitBytes, int maxBits, int randoms, int[] ops, long seed) {
        Random rnd = new Random(seed);
        NatStore nats = overlay(limitBytes);
        long limit = limitBytes > 0 ? limitBytes : Nat.OFFICIAL_MAX_BYTES;
        FakeLits lits = new FakeLits();
        NatBinding nb = new NatBinding(lits, nats);
        List<BigInteger> vals = matrix(rnd, maxBits, randoms);
        int computed = 0;
        int rejected = 0;
        for (BigInteger a : vals) {
            for (BigInteger b : vals) {
                int la = lits.lit(a);
                int lb = lits.lit(b);
                for (int op : ops) {
                    if (limitBytes <= 0 && (op == NatOps.POW || op == NatOps.SHIFT_LEFT)
                            && b.compareTo(BigInteger.valueOf(200)) > 0 && b.compareTo(UINT_MAX) <= 0
                            && a.signum() != 0) {
                        continue; // admitted by the official limit but far too large for a unit test
                    }
                    BigInteger want = model(op, a, b, limit);
                    if (want == null) {
                        KernelException e = assertThrows(KernelException.class, () -> nb.binary(op, la, lb),
                                () -> "op " + op + " on " + a + ", " + b);
                        assertEquals(Reason.NAT_LITERAL_TOO_BIG, e.reason());
                        rejected++;
                    } else {
                        int r;
                        try {
                            r = nb.binary(op, la, lb);
                        } catch (RuntimeException e) {
                            fail("op " + op + " on " + a + ", " + b + " threw " + e);
                            return;
                        }
                        checkResultShape(lits, r, want);
                        computed++;
                    }
                }
                assertEquals(a.equals(b), nb.test(NatOps.BEQ, la, lb));
                assertEquals(a.compareTo(b) <= 0, nb.test(NatOps.BLE, la, lb));
                assertEquals(a.equals(b), nb.natEquals(la, lb));
            }
            // a session boundary every operand row: the worker pool and the number cache are emptied
            nb.reset();
            lits.resetWorker();
            assertEquals(0, nats.count());
        }
        assertTrue(computed > 1000, "computed " + computed);
        if (limitBytes > 0) {
            assertTrue(rejected > 100, "rejected " + rejected);
        }
    }

    @Test
    void allOperationsAgainstTheModelOfficialLimit() {
        differential(0, 400, 40, BINARY, 1);
    }

    @Test
    void allOperationsAgainstTheModelSmallLimit() {
        // LEAN_NAT_MAX_SIZE = 64 bytes: every guard fires on small operands.
        differential(64, 520, 40, BINARY, 2);
        differential(8, 130, 20, BINARY, 3);
        differential(24, 300, 20, BINARY, 4);
    }

    @Test
    void officialGuardsBecomeKernelExceptions() {
        NatStore nats = overlay(0);
        FakeLits lits = new FakeLits();
        NatBinding nb = new NatBinding(lits, nats);
        int zero = nb.literal(0);
        int one = nb.literal(1);
        int two = nb.literal(2);
        int three = nb.literal(3);
        int c32 = lits.lit(BigInteger.ONE.shiftLeft(32)); // 4294967296: does not fit unsigned int
        int cMax = lits.lit(UINT_MAX);
        int huge = lits.lit(BigInteger.ONE.shiftLeft(200));

        // Nat.pow: the count is checked before the base (official: kernel exception even for 0 and 1).
        for (int base : new int[] {zero, one, two, huge}) {
            assertEquals(Reason.NAT_LITERAL_TOO_BIG,
                    assertThrows(KernelException.class, () -> nb.binary(NatOps.POW, base, c32)).reason());
            assertEquals(Reason.NAT_LITERAL_TOO_BIG,
                    assertThrows(KernelException.class, () -> nb.binary(NatOps.POW, base, huge)).reason());
        }
        // 0 ^ (2^32 - 1) and 1 ^ (2^32 - 1) are computed: the size guard only applies to bases above 1.
        assertEquals(BigInteger.ZERO, lits.value(nb.binary(NatOps.POW, zero, cMax)));
        assertEquals(BigInteger.ONE, lits.value(nb.binary(NatOps.POW, one, cMax)));
        // 2 ^ (2^32 - 1): size(2) = 8 > 128 MiB / (2^32 - 1) = 0.
        assertThrows(KernelException.class, () -> nb.binary(NatOps.POW, two, cMax));
        // 3 ^ (2^24 + 1): 8 > 2^27 / (2^24 + 1) = 7.
        int e24p1 = nb.literal((1L << 24) + 1);
        assertThrows(KernelException.class, () -> nb.binary(NatOps.POW, three, e24p1));

        // Nat.shiftLeft: 0 <<< anything = 0 (the count is not even looked at); else the count must fit 32 bits.
        assertEquals(BigInteger.ZERO, lits.value(nb.binary(NatOps.SHIFT_LEFT, zero, huge)));
        assertEquals(BigInteger.ZERO, lits.value(nb.binary(NatOps.SHIFT_LEFT, zero, c32)));
        assertThrows(KernelException.class, () -> nb.binary(NatOps.SHIFT_LEFT, one, c32));
        assertThrows(KernelException.class, () -> nb.binary(NatOps.SHIFT_LEFT, one, huge));
        // 1 <<< 1073741760: 8 + 134217720 + 1 > 134217728.
        int tooFar = nb.literal(1073741760L);
        assertThrows(KernelException.class, () -> nb.binary(NatOps.SHIFT_LEFT, one, tooFar));
        // Nat.shiftRight has no guard at all.
        assertEquals(BigInteger.ZERO, lits.value(nb.binary(NatOps.SHIFT_RIGHT, huge, huge)));
        assertEquals(BigInteger.ONE, lits.value(nb.binary(NatOps.SHIFT_RIGHT, huge, nb.literal(200))));

        // The exception names the operands.
        KernelException e = assertThrows(KernelException.class, () -> nb.binary(NatOps.POW, two, c32));
        assertEquals(two, e.term1());
        assertEquals(c32, e.term2());
    }

    @Test
    void succPredAndViews() {
        NatStore nats = overlay(16); // limit: two limbs
        FakeLits lits = new FakeLits();
        NatBinding nb = new NatBinding(lits, nats);

        assertTrue(nb.isZero(nb.literal(0)));
        assertFalse(nb.isZero(nb.literal(1)));
        assertFalse(nb.isZero(lits.poolLit(TWO_64)));
        assertTrue(nb.isSmall(nb.literal(Long.MAX_VALUE)));
        assertEquals(Long.MAX_VALUE, nb.smallValue(nb.literal(Long.MAX_VALUE)));
        assertFalse(nb.isSmall(nb.literal(Long.MIN_VALUE)), "2^63 is not small");
        assertFalse(nb.isSmall(lits.poolLit(TWO_64)));

        // succ across every representation boundary
        BigInteger[] vals = {BigInteger.ZERO, BigInteger.valueOf(41), TWO_63.subtract(BigInteger.ONE), TWO_63,
            TWO_64.subtract(BigInteger.TWO), TWO_64.subtract(BigInteger.ONE), TWO_64,
            BigInteger.ONE.shiftLeft(128).subtract(BigInteger.TWO)};
        for (BigInteger v : vals) {
            int s = nb.succ(lits.lit(v));
            checkResultShape(lits, s, v.add(BigInteger.ONE));
            int p = nb.pred(s);
            checkResultShape(lits, p, v);
            assertTrue(nb.natEquals(p, lits.lit(v)));
        }
        // 2^128 - 1 + 1 needs three limbs: above the 16-byte limit.
        int top = lits.lit(BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE));
        KernelException e = assertThrows(KernelException.class, () -> nb.succ(top));
        assertEquals(Reason.NAT_LITERAL_TOO_BIG, e.reason());
        assertThrows(IllegalArgumentException.class, () -> nb.pred(nb.literal(0)));

        // infer_lit: a numeral above the limit is refused, whatever its spelling
        nb.checkLiteralSize(nb.literal(-1L));
        nb.checkLiteralSize(lits.poolLit(BigInteger.ONE.shiftLeft(127)));
        int big = lits.poolLit(BigInteger.ONE.shiftLeft(128));
        assertEquals(Reason.NAT_LITERAL_TOO_BIG,
                assertThrows(KernelException.class, () -> nb.checkLiteralSize(big)).reason());

        assertEquals("18446744073709551616", nb.natToString(lits.poolLit(TWO_64)));
        assertEquals("7", nb.natToString(nb.literal(7)));
    }

    @Test
    void predicatesAreNotBinaryOperations() {
        NatBinding nb = new NatBinding(new FakeLits(), overlay(0));
        int a = nb.literal(1);
        assertThrows(IllegalArgumentException.class, () -> nb.binary(NatOps.BEQ, a, a));
        assertThrows(IllegalArgumentException.class, () -> nb.binary(NatOps.BLE, a, a));
        assertThrows(IllegalArgumentException.class, () -> nb.binary(99, a, a));
        assertThrows(IllegalArgumentException.class, () -> nb.test(NatOps.ADD, a, a));
    }

    @Test
    void literalIdentityAcrossSpellings() {
        NatStore nats = overlay(0);
        FakeLits lits = new FakeLits();
        NatBinding nb = new NatBinding(lits, nats);
        BigInteger v = BigInteger.ONE.shiftLeft(100).add(BigInteger.valueOf(12345));

        int export1 = lits.poolLit(v); // two pool entries with the same value (a pool without content interning)
        int export2 = lits.poolLit(v);
        assertNotEquals(export1, export2);
        assertNotEquals(lits.natRef(export1), lits.natRef(export2));
        int computed = nb.binary(NatOps.ADD, lits.lit(BigInteger.ONE.shiftLeft(100)), nb.literal(12345));
        assertTrue(nb.natEquals(export1, export2), "different references, same content");
        assertTrue(nb.natEquals(export1, computed));
        assertTrue(nb.natEquals(computed, export2));
        assertTrue(nb.test(NatOps.BEQ, export2, computed));
        int next = nb.succ(computed);
        assertFalse(nb.natEquals(export1, next));
        assertFalse(nb.natEquals(export2, next));
        assertFalse(nb.test(NatOps.BEQ, export2, next));

        // A computed number that no export literal equals is interned once in the worker pool:
        // computing it again gives the same term.
        int again = nb.binary(NatOps.ADD, export2, nb.literal(1));
        assertEquals(next, again);
        assertTrue(lits.natRef(next) < 0, "a worker-pool entry");
        assertEquals(1, lits.workerEntries());

        // word against big: different by representation, decided without reading the pool
        int reads = lits.poolReads;
        assertFalse(nb.natEquals(export1, nb.literal(5)));
        assertFalse(nb.natEquals(nb.literal(-1L), export1));
        assertEquals(reads, lits.poolReads);

        // A pool entry below 2^64 (a writer that did not canonicalise) still compares by value,
        // and results never keep that spelling.
        int odd = lits.poolLit(BigInteger.valueOf(77));
        assertTrue(nb.natEquals(odd, nb.literal(77)));
        assertTrue(nb.natEquals(nb.literal(77), odd));
        assertFalse(nb.natEquals(odd, nb.literal(78)));
        assertTrue(lits.natIsWord(nb.binary(NatOps.ADD, odd, nb.literal(0))));
        assertTrue(nb.natEquals(lits.poolLit(BigInteger.ZERO), nb.literal(0)));
    }

    /**
     * Imports are counted with {@code NatBinding.imports()} (pool entries copied into the number
     * store), not with the fake's read counter: with assertions enabled the binding also reads the
     * pool to compare every cache hit with the pool's content, which is not an import.
     */
    @Test
    void theNumberStoreIsACacheOfThePool() {
        NatStore nats = overlay(0);
        FakeLits lits = new FakeLits();
        NatBinding nb = new NatBinding(lits, nats);
        BigInteger v = BigInteger.ONE.shiftLeft(300).subtract(BigInteger.ONE);
        int a = lits.lit(v);
        int one = nb.literal(1);

        // an export literal is imported once per session; a result is cached under its pool reference
        int r1 = nb.binary(NatOps.ADD, a, one);
        assertEquals(1, lits.poolReads);
        assertEquals(1, nb.imports());
        nb.binary(NatOps.SUB, a, one);
        nb.test(NatOps.BLE, a, a);
        int r2 = nb.binary(NatOps.MUL, r1, r1); // r1 = 2^300 is used as an operand: no pool read
        assertEquals(1, nb.imports(), "operands seen before must come from the cache");
        assertEquals(BigInteger.ONE.shiftLeft(600), lits.value(r2));
        assertTrue(nb.footprintBytes() > 0);

        // session reset: pool references are reissued, a stale cache entry would name another number
        nb.reset();
        lits.resetWorker();
        assertEquals(0, nb.footprintBytes());
        assertEquals(0, nats.count());
        int b = lits.lit(BigInteger.ONE.shiftLeft(90));
        int s1 = nb.binary(NatOps.ADD, b, one); // first worker entry of the new session
        assertEquals(BigInteger.ONE.shiftLeft(90).add(BigInteger.ONE), lits.value(s1));
        int s2 = nb.binary(NatOps.ADD, a, one);
        assertEquals(v.add(BigInteger.ONE), lits.value(s2));
        assertEquals(3, nb.imports());

        // a cache limit of zero: the store is emptied after every big result and everything still works
        nb.setCacheLimitBytes(0);
        BigInteger want = v;
        int x = a;
        for (int i = 0; i < 6; i++) {
            x = nb.binary(NatOps.ADD, x, a);
            want = want.add(v);
            assertEquals(want, lits.value(x));
            assertEquals(0, nats.count(), "the cache is emptied once the result is in the pool");
        }
        assertTrue(nb.imports() >= 3 + 2 * 5, "operands are re-imported after the cache was emptied");
        // back to a roomy cache: imports stop growing for repeated operands
        nb.setCacheLimitBytes(1 << 20);
        nb.binary(NatOps.ADD, x, a);
        long readsNow = nb.imports();
        nb.binary(NatOps.SUB, x, a);
        nb.binary(NatOps.LOR, x, a);
        assertEquals(readsNow, nb.imports());
    }

    @Test
    void ownLimitDeclinesAndNeverRejects() {
        NatStore nats = overlay(0);
        FakeLits lits = new FakeLits();
        NatBinding nb = new NatBinding(lits, nats);
        nb.setWorkLimitBytes(64);
        int a = lits.lit(BigInteger.ONE.shiftLeft(400));
        int two = nb.literal(2);

        // 2^400 * 2^400: 800 bits, above the work limit of 512 bits but far below the official limit.
        assertEquals(Reason.UNSUPPORTED_INPUT,
                assertThrows(ResourceException.class, () -> nb.binary(NatOps.MUL, a, a)).reason());
        assertEquals(Reason.UNSUPPORTED_INPUT,
                assertThrows(ResourceException.class, () -> nb.binary(NatOps.POW, two, nb.literal(1000))).reason());
        assertEquals(Reason.UNSUPPORTED_INPUT, assertThrows(ResourceException.class,
                () -> nb.binary(NatOps.SHIFT_LEFT, two, nb.literal(1000))).reason());
        // Cheap operations are unaffected, and x * 0 is not estimated at all.
        assertEquals(BigInteger.ZERO, lits.value(nb.binary(NatOps.MUL, a, nb.literal(0))));
        assertEquals(BigInteger.ONE.shiftLeft(401), lits.value(nb.binary(NatOps.ADD, a, a)));
        // What the official kernel rejects is still a rejection, not a decline.
        assertThrows(KernelException.class, () -> nb.binary(NatOps.POW, two, lits.lit(BigInteger.ONE.shiftLeft(32))));

        nb.setWorkLimitBytes(0); // back to "no own limit"
        assertEquals(BigInteger.ONE.shiftLeft(800), lits.value(nb.binary(NatOps.MUL, a, a)));
    }

    @Test
    void stringsDelegateToTheBridge() {
        FakeLits lits = new FakeLits();
        NatBinding nb = new NatBinding(lits, overlay(0));
        int s = lits.str("a😀é");
        int t = lits.str("a😀é");
        int u = lits.str("a");
        assertEquals(3, nb.stringLength(s));
        assertEquals('a', nb.stringCodePoint(s, 0));
        assertEquals(0x1F600, nb.stringCodePoint(s, 1));
        assertEquals(0xE9, nb.stringCodePoint(s, 2));
        assertTrue(nb.stringEquals(s, s));
        assertTrue(nb.stringEquals(s, t));
        assertFalse(nb.stringEquals(s, u));
    }

    @Test
    void needsAWritableStore() {
        NatStore base = NatStore.newBase();
        base.freeze();
        assertThrows(IllegalArgumentException.class, () -> new NatBinding(new FakeLits(), base));
    }
}
