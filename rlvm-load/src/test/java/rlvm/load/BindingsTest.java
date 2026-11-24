package rlvm.load;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import org.junit.jupiter.api.Test;

import rlvm.level.LevelStore;

/** The adapters over {@code rlvm-level} and {@code rlvm-nat}. */
class BindingsTest {

    private static BigInteger ofLimbs(long[] w, int n) {
        BigInteger v = BigInteger.ZERO;
        for (int i = n - 1; i >= 0; i--) {
            v = v.shiftLeft(64).or(new BigInteger(Long.toUnsignedString(w[i])));
        }
        return v;
    }

    @Test
    void natDecoderAgreesWithBigInteger() {
        NatStoreDecoder d = new NatStoreDecoder();
        NatDecoder.Limbs limbs = new NatDecoder.Limbs();
        Random rnd = new Random(42);
        for (int i = 0; i < 2000; i++) {
            int bits = 64 + rnd.nextInt(i < 1990 ? 600 : 40_000);
            BigInteger v = new BigInteger(bits, rnd).setBit(bits);
            byte[] digits = ("xx" + v + "yy").getBytes(StandardCharsets.US_ASCII);
            int n = d.decode(digits, 2, digits.length - 4, limbs);
            assertEquals((bits + 64) / 64, n, "limb count of a " + (bits + 1) + "-bit number");
            assertTrue(limbs.words[n - 1] != 0);
            assertEquals(v, ofLimbs(limbs.words, n));
        }
        // exact powers of two at limb boundaries
        for (int k = 64; k <= 512; k += 64) {
            BigInteger v = BigInteger.ONE.shiftLeft(k);
            byte[] digits = v.toString().getBytes(StandardCharsets.US_ASCII);
            int n = d.decode(digits, 0, digits.length, limbs);
            assertEquals(k / 64 + 1, n);
            assertEquals(v, ofLimbs(limbs.words, n));
        }
    }

    @Test
    void levelInternerUsesTheRawConstructorsAndFreezesOnPublish() {
        LevelStore ls = new LevelStore();
        LevelStoreInterner li = new LevelStoreInterner(ls);
        int zero = li.zero();
        assertEquals(LevelStore.ZERO, zero);
        int u = li.param(7);
        int m1 = li.max(u, zero);
        int m2 = li.max(zero, u);
        assertTrue(m1 != m2 && m1 != u, "max u 0 is kept as written");
        assertEquals(m1, li.max(u, zero), "hash-consed");
        int im = li.imax(u, li.succ(zero));
        assertEquals(LevelStore.KIND_IMAX, ls.kind(im));
        int list = li.internList(new int[] {9, u, m1, 9}, 1, 2);
        assertEquals(2, ls.listLength(list));
        assertEquals(m1, ls.listGet(list, 1));
        assertEquals(0, li.internList(new int[0], 0, 0));
        assertTrue(li.hasDuplicates(li.internList(new int[] {3, 4, 3}, 0, 3)));
        assertFalse(li.hasDuplicates(li.internList(new int[] {3, 4}, 0, 2)));
        assertFalse(ls.isFrozen());
        li.publish();
        assertTrue(ls.isFrozen());
        li.publish();
        assertThrows(IllegalArgumentException.class, () -> new LevelStoreInterner(ls));

        LevelStore other = new LevelStore();
        LevelStoreInterner noFreeze = new LevelStoreInterner(other, false);
        noFreeze.publish();
        assertFalse(other.isFrozen());
    }

    @Test
    void storeLimitsAreDeclines() {
        UnsupportedExportException e = ExportLoader.translate(new rlvm.store.StoreLimitException("more than 2^28 - 1 nodes"));
        assertTrue(e.getMessage().contains("2^28 - 1"));
        RuntimeException m = ExportLoader.translate(rlvm.export.ExportException.malformed("Name not found 7").atLine(12));
        assertTrue(m instanceof MalformedExportException);
        assertEquals(12, ((MalformedExportException) m).line());
        assertEquals("Name not found 7", m.getMessage());
        RuntimeException u = ExportLoader.translate(rlvm.export.ExportException.unsupported("id of 2^63 or more").atLine(3));
        assertTrue(u instanceof UnsupportedExportException);
        assertTrue(u.getMessage().contains("line 3"));
    }
}
