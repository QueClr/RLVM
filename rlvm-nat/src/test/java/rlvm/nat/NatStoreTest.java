package rlvm.nat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Hash-consing, layering (frozen base + overlays), release and concurrency of {@link NatStore}. */
class NatStoreTest {

    @Test
    void smallValuesNeverTouchTheStore() {
        NatStore s = NatStore.newBase();
        assertEquals(1, s.firstId());
        assertEquals(1, s.nextId());
        for (long v : new long[] {0, 1, 42, Long.MAX_VALUE}) {
            assertEquals(v, Nat.ofBigInteger(s, BigInteger.valueOf(v)));
            assertEquals(v, Nat.ofUnsignedLong(s, v));
            assertTrue(Nat.isSmall(v) && Nat.isValue(v) && !Nat.isBig(v) && !Nat.isError(v));
        }
        assertEquals(5, Nat.sub(s, Nat.add(s, Long.MAX_VALUE, 5), Long.MAX_VALUE)); // big intermediate, small result
        assertEquals(Long.MAX_VALUE, Nat.pred(s, Nat.succ(s, Long.MAX_VALUE)));
        assertEquals(2, s.count(), "only the two big intermediates 2^63 + 4 and 2^63 were stored");
    }

    @Test
    void idsStartAtOneAndAreDense() {
        NatStore s = NatStore.newBase();
        long a = Nat.ofBigInteger(s, TestSupport.TWO63);
        long b = Nat.ofBigInteger(s, TestSupport.TWO64);
        long c = Nat.ofUnsignedLong(s, -1L);
        assertEquals(Nat.ofBigId(1), a);
        assertEquals(Nat.ofBigId(2), b);
        assertEquals(Nat.ofBigId(3), c);
        assertEquals(1, Nat.bigId(a));
        assertTrue(Nat.isBig(a) && Nat.isValue(a) && !Nat.isSmall(a) && !Nat.isError(a));
        assertEquals(3, s.count());
        assertEquals(4, s.nextId());
        assertEquals(a, Nat.ofBigInteger(s, TestSupport.TWO63), "interning again gives the same handle");
        assertEquals(c, Nat.sub(s, b, 1));
        assertEquals(3, s.count());
        assertEquals(1, s.limbCount(1));
        assertEquals(Long.MIN_VALUE, s.limb(1, 0));
        assertEquals(2, s.limbCount(2));
        assertEquals(0, s.limb(2, 0));
        assertEquals(1, s.limb(2, 1));
        assertEquals(8 * 4, s.bytesUsed());
        assertEquals(s.hashOf(1), s.hashOf(Nat.bigId(Nat.ofBigInteger(s, TestSupport.TWO63))));
        assertThrows(IllegalArgumentException.class, () -> s.limbCount(0));
        assertThrows(IllegalArgumentException.class, () -> s.limbCount(4));
        assertThrows(IndexOutOfBoundsException.class, () -> s.limb(2, 2));
    }

    @Test
    void baseAndOverlays() {
        NatStore base = NatStore.newBase();
        assertNull(base.base());
        long inBase = Nat.ofBigInteger(base, TestSupport.TWO64);
        assertThrows(IllegalStateException.class, base::newOverlay, "the base must be frozen first");
        base.freeze();
        assertTrue(base.isFrozen());
        NatStore w1 = base.newOverlay();
        NatStore w2 = base.newOverlay();
        assertSame(base, w1.base());
        assertEquals(base.nextId(), w1.firstId());
        assertEquals(0, w1.count());
        assertThrows(IllegalStateException.class, w1::newOverlay, "overlays do not stack");

        // a base value is found from every overlay under its base handle, without storing anything
        assertEquals(inBase, Nat.ofBigInteger(w1, TestSupport.TWO64));
        assertEquals(inBase, Nat.add(w1, Nat.ofBigInteger(w1, TestSupport.TWO64.subtract(BigInteger.ONE)), 1));
        assertEquals(1, w1.count(), "only 2^64 - 1 is new");
        assertEquals(0, w2.count());

        // new values get ids above the base, per overlay
        long x1 = Nat.mul(w1, inBase, inBase);
        long x2 = Nat.mul(w2, inBase, inBase);
        assertTrue(Nat.bigId(x1) >= base.nextId());
        assertEquals(TestSupport.pow2(128), Nat.toBigInteger(w1, x1));
        assertEquals(TestSupport.pow2(128), Nat.toBigInteger(w2, x2));
        assertEquals(1, w2.count());
        assertEquals(x2, Nat.transfer(w1, x1, w2), "transfer re-interns by value");
        assertEquals(5, Nat.transfer(w1, 5, w2));
        assertEquals(Nat.TOO_BIG, Nat.transfer(w1, Nat.TOO_BIG, w2));
        assertEquals(inBase, Nat.transfer(w1, inBase, w2));

        // the frozen base refuses new values but answers every read-only question
        assertThrows(IllegalStateException.class, () -> Nat.ofBigInteger(base, TestSupport.pow2(70)));
        assertThrows(IllegalStateException.class, () -> Nat.add(base, inBase, 1));
        assertThrows(IllegalStateException.class, () -> Nat.succ(base, Long.MAX_VALUE));
        assertThrows(IllegalStateException.class, base::reset);
        assertThrows(IllegalStateException.class, () -> base.truncate(1));
        assertEquals(3, Nat.add(base, 1, 2));
        // ... including the lookup of a value that exists: building limbs needs a writable layer (an overlay)
        assertThrows(IllegalStateException.class, () -> Nat.ofBigInteger(base, TestSupport.TWO64));
    }

    @Test
    void frozenBaseAnswersReadOnlyQueries() {
        NatStore base = NatStore.newBase();
        BigInteger x = TestSupport.pow2(200).add(BigInteger.valueOf(77));
        BigInteger y = TestSupport.pow2(100);
        long a = Nat.ofBigInteger(base, x);
        long b = Nat.ofBigInteger(base, y);
        base.freeze();
        assertTrue(Nat.blt(base, b, a));
        assertTrue(Nat.ble(base, 7, a));
        assertTrue(!Nat.beq(base, a, b));
        assertTrue(Nat.compare(base, a, 7) > 0);
        assertEquals(201, Nat.bitLength(base, a));
        assertEquals(32, Nat.sizeInBytes(base, a));
        assertEquals(200, Nat.log2(base, a));
        assertEquals(x.and(BigInteger.valueOf(0xFF)).longValue(), Nat.land(base, a, 0xFF));
        assertEquals(x.and(BigInteger.valueOf(0xFF)).longValue(), Nat.land(base, 0xFF, a));
        assertEquals(x.gcd(BigInteger.valueOf(77000)).longValue(), Nat.gcd(base, a, 77000));
        assertEquals(x.gcd(BigInteger.valueOf(1155)).longValue(), Nat.gcd(base, 1155, a));
        assertEquals(1L << 20, Nat.gcd(base, b, 1L << 20));
        assertEquals(x.mod(BigInteger.TEN).longValue(), Nat.mod(base, a, 10));
        assertEquals(x.mod(BigInteger.valueOf(1L << 40)).longValue(), Nat.mod(base, a, 1L << 40));
        assertEquals(7, Nat.mod(base, 7, a));
        assertEquals(a, Nat.mod(base, a, 0));
        assertEquals(0, Nat.div(base, 7, a));
        assertEquals(0, Nat.div(base, a, 0));
        assertEquals(1, Nat.div(base, a, a));
        assertEquals(0, Nat.mod(base, a, a));
        assertEquals(0, Nat.sub(base, 7, a));
        assertEquals(0, Nat.sub(base, b, a));
        assertEquals(0, Nat.sub(base, a, a));
        assertEquals(a, Nat.land(base, a, a));
        assertEquals(a, Nat.lor(base, a, a));
        assertEquals(0, Nat.xor(base, a, a));
        assertEquals(0, Nat.shiftRight(base, a, 201));
        assertEquals(a, Nat.shiftRight(base, a, 0));
        assertEquals(Nat.TOO_BIG, Nat.pow(base, a, 1 << 23));
        assertEquals(1, Nat.pow(base, a, 0));
        assertEquals(y, Nat.toBigInteger(base, b));
        assertEquals(y.toString(), Nat.toDecimalString(base, b));
    }

    @Test
    void markTruncateAndReset() {
        NatStore base = NatStore.newBase();
        long b0 = Nat.ofBigInteger(base, TestSupport.TWO64);
        base.freeze();
        NatStore s = base.newOverlay();
        long keep = Nat.ofBigInteger(s, TestSupport.pow2(100));
        int mark = s.mark();
        long used = s.bytesUsed();
        long t1 = Nat.ofBigInteger(s, TestSupport.pow2(200));
        long t2 = Nat.ofBigInteger(s, TestSupport.pow2(300));
        assertEquals(3, s.count());
        s.truncate(mark);
        assertEquals(1, s.count());
        assertEquals(used, s.bytesUsed());
        assertEquals(TestSupport.pow2(100), Nat.toBigInteger(s, keep));
        assertThrows(IllegalArgumentException.class, () -> Nat.toBigInteger(s, t2));
        // ids are reused, values are re-interned correctly
        long u = Nat.ofBigInteger(s, TestSupport.pow2(300));
        assertEquals(t1, u, "the first released id is reused");
        assertEquals(TestSupport.pow2(300), Nat.toBigInteger(s, u));
        assertEquals(keep, Nat.ofBigInteger(s, TestSupport.pow2(100)));
        assertEquals(b0, Nat.ofBigInteger(s, TestSupport.TWO64));
        s.truncate(s.mark());
        assertThrows(IllegalArgumentException.class, () -> s.truncate(s.nextId() + 1));
        assertThrows(IllegalArgumentException.class, () -> s.truncate(s.firstId() - 1));
        s.reset();
        assertEquals(0, s.count());
        assertEquals(0, s.bytesUsed());
        assertEquals(s.firstId(), s.nextId());
        assertEquals(b0, Nat.ofBigInteger(s, TestSupport.TWO64));
        assertEquals(Nat.ofBigId(s.firstId()), Nat.ofBigInteger(s, TestSupport.pow2(100)));
    }

    /** Model-based test: random interning, release and reset against a list of the live values. */
    @Test
    void randomInternTruncateResetAgainstModel() {
        Random r = new Random(21);
        NatStore base = NatStore.newBase();
        ArrayList<BigInteger> baseVals = new ArrayList<>();
        HashMap<BigInteger, Long> baseHandles = new HashMap<>();
        for (int i = 0; i < 3000; i++) {
            BigInteger v = TestSupport.randomNat(r, 400).add(TestSupport.TWO63);
            long h = Nat.ofBigInteger(base, v);
            Long old = baseHandles.put(v, h);
            assertTrue(old == null || old == h);
            baseVals.add(v);
        }
        base.freeze();
        for (int round = 0; round < 3; round++) {
            NatStore s = base.newOverlay();
            ArrayList<BigInteger> live = new ArrayList<>(); // overlay values in id order
            HashMap<BigInteger, Long> handles = new HashMap<>();
            ArrayList<Integer> marks = new ArrayList<>();
            ArrayList<Integer> markSizes = new ArrayList<>();
            for (int step = 0; step < 60000; step++) {
                int what = r.nextInt(100);
                if (what < 70) { // intern a new or an existing value
                    BigInteger v;
                    int k = r.nextInt(10);
                    if (k == 0 && !live.isEmpty()) {
                        v = live.get(r.nextInt(live.size()));
                    } else if (k == 1) {
                        v = baseVals.get(r.nextInt(baseVals.size()));
                    } else {
                        v = TestSupport.randomNat(r, r.nextInt(50) == 0 ? 5000 : 300).add(TestSupport.TWO63);
                    }
                    long h = Nat.ofBigInteger(s, v);
                    Long inBase = baseHandles.get(v);
                    if (inBase != null) {
                        assertEquals(inBase.longValue(), h, "base values keep their base handle");
                    } else {
                        Long known = handles.get(v);
                        if (known != null) {
                            assertEquals(known.longValue(), h, "one handle per value");
                        } else {
                            assertEquals(Nat.ofBigId(s.firstId() + live.size()), h, "ids are dense and ascending");
                            handles.put(v, h);
                            live.add(v);
                        }
                    }
                    assertEquals(v, Nat.toBigInteger(s, h));
                } else if (what < 80) {
                    marks.add(s.mark());
                    markSizes.add(live.size());
                } else if (what < 90 && !marks.isEmpty()) {
                    int i = r.nextInt(marks.size());
                    s.truncate(marks.get(i));
                    int keep = markSizes.get(i);
                    while (live.size() > keep) {
                        handles.remove(live.remove(live.size() - 1));
                    }
                    while (marks.size() > i) {
                        marks.remove(marks.size() - 1);
                        markSizes.remove(markSizes.size() - 1);
                    }
                } else if (what == 99 && r.nextInt(40) == 0) {
                    s.reset();
                    live.clear();
                    handles.clear();
                    marks.clear();
                    markSizes.clear();
                } else if (!live.isEmpty()) { // spot-check a live value and the accounting
                    int i = r.nextInt(live.size());
                    assertEquals(live.get(i), Nat.toBigInteger(s, Nat.ofBigId(s.firstId() + i)));
                }
                assertEquals(live.size(), s.count());
            }
            long bytes = 0;
            for (int i = 0; i < live.size(); i++) {
                BigInteger v = live.get(i);
                assertEquals(v, Nat.toBigInteger(s, Nat.ofBigId(s.firstId() + i)));
                assertEquals(handles.get(v).longValue(), Nat.ofBigInteger(s, v));
                bytes += 8L * ((v.bitLength() + 63) / 64);
            }
            assertEquals(bytes, s.bytesUsed());
        }
    }

    @Test
    void tableAndSlabGrowAndShrink() {
        NatStore base = NatStore.newBase();
        base.freeze();
        NatStore s = base.newOverlay();
        int n = 200_000;
        for (int i = 0; i < n; i++) {
            long h = Nat.add(s, Long.MAX_VALUE, i + 1);
            assertEquals(Nat.ofBigId(s.firstId() + i), h);
        }
        assertEquals(n, s.count());
        for (int i = 0; i < n; i += 37) {
            assertEquals(TestSupport.TWO63.add(BigInteger.valueOf(i)), Nat.toBigInteger(s, Nat.ofBigId(s.firstId() + i)));
            assertEquals(Nat.ofBigId(s.firstId() + i), Nat.add(s, Long.MAX_VALUE, i + 1));
        }
        s.truncate(s.firstId() + 1000);
        assertEquals(1000, s.count());
        assertEquals(Nat.ofBigId(s.firstId() + 999), Nat.add(s, Long.MAX_VALUE, 1000));
        assertEquals(Nat.ofBigId(s.firstId() + 1000), Nat.add(s, Long.MAX_VALUE, 5_000_000));
        s.reset();
        assertEquals(0, s.count());
        for (int i = 0; i < 100; i++) {
            assertEquals(Nat.ofBigId(s.firstId() + i), Nat.add(s, Long.MAX_VALUE, i + 1));
        }
        // one value much larger than the initial slab, then reset
        long big = Nat.shiftLeft(s, 1, 40_000_000);
        assertEquals(40_000_001, Nat.bitLength(s, big));
        assertTrue(s.bytesUsed() > 5_000_000);
        s.reset();
        assertEquals(0, s.bytesUsed());
        assertEquals(Nat.ofBigId(s.firstId()), Nat.shiftLeft(s, 1, 64));
    }

    @Test
    void sizeLimitOfTheStoreAppliesToCheckedOperationsOnly() {
        assertThrows(IllegalArgumentException.class, () -> NatStore.newBase(7));
        NatStore base = NatStore.newBase(16);
        assertEquals(16, base.maxBytes());
        base.freeze();
        NatStore s = base.newOverlay();
        assertEquals(16, s.maxBytes());
        long two127 = Nat.ofBigInteger(s, TestSupport.pow2(127)); // 16 bytes
        long two128 = Nat.ofBigInteger(s, TestSupport.pow2(128)); // 24 bytes: storable, but no checked result may be
        assertTrue(Nat.isBig(two128));
        assertEquals(Nat.TOO_BIG, Nat.add(s, two127, two127));
        assertEquals(Nat.TOO_BIG, Nat.mul(s, two127, 2));
        assertEquals(Nat.TOO_BIG, Nat.succ(s, Nat.sub(s, two128, 1)));
        assertEquals(Nat.TOO_BIG, Nat.sub(s, Nat.add(s, two128, 0) == Nat.TOO_BIG ? two128 : two128, 0), "even x - 0 is checked");
        assertEquals(two128, Nat.lor(s, two128, 0));
        assertEquals(two128, Nat.div(s, two128, 1));
        assertEquals(two128, Nat.mod(s, two128, Nat.ofBigInteger(s, TestSupport.pow2(129))));
        assertEquals(two128, Nat.gcd(s, two128, 0));
        NatStore wide = base.newOverlay(4096);
        assertEquals(4096, wide.maxBytes());
        assertTrue(Nat.isBig(Nat.add(wide, Nat.ofBigInteger(wide, TestSupport.pow2(127)), Nat.ofBigInteger(wide, TestSupport.pow2(127)))));
        assertEquals(NatStore.HARD_MAX_BYTES, NatStore.newBase(Long.MAX_VALUE).maxBytes());
        assertEquals(Nat.OFFICIAL_MAX_BYTES, NatStore.newBase().maxBytes());
        assertEquals(134217728L, Nat.OFFICIAL_MAX_BYTES);
    }

    @Test
    void workersShareTheFrozenBaseConcurrently() throws Exception {
        Random seed = new Random(22);
        NatStore base = NatStore.newBase();
        int nBase = 2000;
        BigInteger[] vals = new BigInteger[nBase];
        long[] handles = new long[nBase];
        for (int i = 0; i < nBase; i++) {
            vals[i] = TestSupport.randomNat(seed, 500).add(TestSupport.TWO63);
            handles[i] = Nat.ofBigInteger(base, vals[i]);
        }
        base.freeze();
        int baseCount = base.count();
        long baseBytes = base.bytesUsed();
        int threads = 8;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] ts = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int id = t;
            ts[t] = new Thread(() -> {
                try {
                    Random r = new Random(1000 + id);
                    NatStore s = id < 6 ? base.newOverlay() : null; // two threads read the base directly
                    for (int i = 0; i < 40000 && failure.get() == null; i++) {
                        int x = r.nextInt(nBase);
                        int y = r.nextInt(nBase);
                        if (s == null) {
                            assertEquals(Integer.signum(vals[x].compareTo(vals[y])), Integer.signum(Nat.compare(base, handles[x], handles[y])));
                            assertEquals(vals[x].bitLength(), Nat.bitLength(base, handles[x]));
                            assertEquals(vals[x], Nat.toBigInteger(base, handles[x]));
                            assertEquals(vals[x].mod(BigInteger.valueOf(1000003)).longValue(), Nat.mod(base, handles[x], 1000003));
                            continue;
                        }
                        int op = NatOp.ADD + r.nextInt(NatOp.SHIFT_RIGHT - NatOp.ADD + 1);
                        if (op == NatOp.POW || op == NatOp.SHIFT_LEFT || op == NatOp.SHIFT_RIGHT) {
                            op = NatOp.XOR;
                        }
                        Object expected = RefNat.apply2(op, vals[x], vals[y], s.maxBytes());
                        long res = Nat.apply2(s, op, handles[x], handles[y]);
                        if (expected instanceof Boolean) {
                            assertEquals((Boolean) expected ? 1 : 0, res);
                        } else {
                            TestSupport.assertCanonical(s, res, (BigInteger) expected);
                        }
                        assertEquals(handles[x], Nat.ofBigInteger(s, vals[x]), "base value found from the overlay");
                        if (i % 1500 == 1499) {
                            s.reset();
                        }
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            });
            ts[t].start();
        }
        for (Thread t : ts) {
            t.join();
        }
        if (failure.get() != null) {
            throw new AssertionError("worker failed", failure.get());
        }
        assertEquals(baseCount, base.count(), "the base did not change");
        assertEquals(baseBytes, base.bytesUsed());
        for (int i = 0; i < nBase; i++) {
            assertEquals(vals[i], Nat.toBigInteger(base, handles[i]));
        }
    }

    @Test
    void handleEncoding() {
        assertEquals(Long.MIN_VALUE, Nat.NONE);
        assertTrue(Nat.isError(Nat.NONE) && Nat.isError(Nat.TOO_BIG) && Nat.isError(Nat.COUNT_TOO_BIG) && Nat.isError(Nat.MALFORMED));
        assertNotEquals(Nat.TOO_BIG, Nat.COUNT_TOO_BIG);
        assertTrue(Nat.isBig(Nat.ofBigId(1)) && Nat.isBig(Nat.ofBigId(Integer.MAX_VALUE)));
        assertEquals(Integer.MAX_VALUE, Nat.bigId(Nat.ofBigId(Integer.MAX_VALUE)));
        assertTrue(!Nat.isBig(Nat.ofBigId(0)), "id 0 is none");
        assertEquals(Nat.NONE, Nat.ofBigId(0));
        assertTrue(Nat.isError(-1L) && !Nat.isValue(-1L));
        assertTrue(Nat.isValue(0) && Nat.isValue(Long.MAX_VALUE));
        assertEquals(0, Nat.OK);
    }
}
