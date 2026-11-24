package rlvm.nat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Decimal parsing from byte ranges and printing, against {@code BigInteger}. */
class NatDecimalTest {
    private static NatStore store() {
        NatStore base = NatStore.newBase();
        base.freeze();
        return base.newOverlay();
    }

    /** Parses the text embedded in a larger buffer and checks value, canonicity and the printed form. */
    private static void roundTrip(NatStore s, String digits, Random r) {
        BigInteger expected = new BigInteger(digits);
        int pre = r.nextInt(5);
        int post = r.nextInt(5);
        byte[] buf = new byte[pre + digits.length() + post];
        Arrays.fill(buf, (byte) '7'); // digits around the range must not be read
        byte[] d = digits.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(d, 0, buf, pre, d.length);
        long h = Nat.parseDecimal(s, buf, pre, pre + d.length);
        TestSupport.assertCanonical(s, h, expected);
        assertEquals(expected.toString(), Nat.toDecimalString(s, h));
        assertEquals(h, Nat.parseDecimal(s, digits));
        assertEquals(h, Nat.parseDecimalLenient(s, buf, pre, pre + d.length));
        assertEquals(expected.toString(), Nat.describe(s, h));
    }

    @Test
    void everyLengthUpToTwoThousandDigits() {
        Random r = new Random(31);
        NatStore s = store();
        for (int len = 1; len <= 2000; len += len < 80 ? 1 : 1 + r.nextInt(9)) {
            for (int rep = 0; rep < (len < 80 ? 40 : 3); rep++) {
                StringBuilder sb = new StringBuilder();
                sb.append((char) ('1' + r.nextInt(9)));
                int kind = r.nextInt(6);
                for (int i = 1; i < len; i++) {
                    sb.append(kind == 0 ? '9' : kind == 1 ? '0' : (char) ('0' + r.nextInt(10)));
                }
                roundTrip(s, sb.toString(), r);
            }
            if (len % 50 == 0) {
                s.reset();
            }
        }
    }

    @Test
    void boundariesOfTheWordAndOfTheAlgorithms() {
        Random r = new Random(32);
        NatStore s = store();
        String[] exact = {"0", "1", "9", "10", "999999999999999999", "1000000000000000000", "9223372036854775806",
            "9223372036854775807", "9223372036854775808", "9223372036854775809", "9999999999999999999",
            "10000000000000000000", "18446744073709551615", "18446744073709551616", "18446744073709551617",
            "99999999999999999999", "100000000000000000000", "340282366920938463463374607431768211455",
            "340282366920938463463374607431768211456"};
        for (String t : exact) {
            roundTrip(s, t, r);
        }
        assertEquals(Long.MAX_VALUE, Nat.parseDecimal(s, "9223372036854775807"));
        assertTrue(Nat.isBig(Nat.parseDecimal(s, "9223372036854775808")));
        for (BigInteger v : TestSupport.boundaries()) {
            roundTrip(s, v.toString(), r);
        }
        for (int e = 0; e <= 130; e++) {
            BigInteger p = BigInteger.TEN.pow(e);
            roundTrip(s, p.toString(), r);
            roundTrip(s, p.subtract(BigInteger.ONE).toString(), r);
            roundTrip(s, p.add(BigInteger.ONE).toString(), r);
        }
        // around the switch to divide-and-conquer parsing, and powers of 10^18 (zero chunks)
        for (int len = NatStore.PARSE_DC_DIGITS - 3; len <= NatStore.PARSE_DC_DIGITS + 40; len++) {
            roundTrip(s, new BigInteger(4 * len, r).mod(BigInteger.TEN.pow(len - 1)).add(BigInteger.TEN.pow(len - 1)).toString(), r);
            roundTrip(s, BigInteger.TEN.pow(len - 1).toString(), r);
        }
        for (int k = 1; k <= 80; k++) {
            roundTrip(s, BigInteger.TEN.pow(18 * k).toString(), r);
            roundTrip(s, BigInteger.TEN.pow(18 * k).subtract(BigInteger.ONE).toString(), r);
            roundTrip(s, BigInteger.TEN.pow(18 * k).add(BigInteger.valueOf(r.nextInt(1000))).toString(), r);
        }
    }

    @Test
    void hugeNumerals() {
        Random r = new Random(33);
        NatStore s = store();
        for (int len : new int[] {5000, 20_011, 36_000, 100_003, 300_000}) {
            StringBuilder sb = new StringBuilder(len);
            sb.append('3');
            for (int i = 1; i < len; i++) {
                sb.append((char) ('0' + r.nextInt(10)));
            }
            roundTrip(s, sb.toString(), r);
            s.reset();
        }
        // 2^k printed by BigInteger and parsed back, for sizes around powers of two of the chunk count
        for (int k : new int[] {59_794, 119_589, 239_180, 1_000_000}) {
            roundTrip(s, TestSupport.pow2(k).toString(), r);
            roundTrip(s, TestSupport.pow2(k).subtract(BigInteger.ONE).toString(), r);
            s.reset();
        }
    }

    @Test
    void leadingZerosAreAccepted() {
        NatStore s = store();
        assertEquals(0, Nat.parseDecimal(s, "0"));
        assertEquals(0, Nat.parseDecimal(s, "0000000000000000000000000000000000000000"));
        assertEquals(7, Nat.parseDecimal(s, "007"));
        assertEquals(5, Nat.parseDecimal(s, "05"));
        assertEquals(Long.MAX_VALUE, Nat.parseDecimal(s, "000000000000000000000000009223372036854775807"));
        long big = Nat.parseDecimal(s, "18446744073709551616");
        assertEquals(big, Nat.parseDecimal(s, "0000000000000000000000000000018446744073709551616"));
        assertEquals(big, Nat.parseDecimal(s, "018446744073709551616"));
        assertEquals(Nat.parseDecimal(s, "9223372036854775808"), Nat.parseDecimal(s, "09223372036854775808"));
    }

    @Test
    void malformedNumeralsAreRefused() {
        NatStore s = store();
        String[] bad = {"", "-1", "+1", " 1", "1 ", "1 2", "1_000", "_1", "1_", "0x10", "1e5", "1.0", "1,000", "١٢٣",
            "１２", "12a", "a12", "/", ":", "12345678901234567890x", "x12345678901234567890", "1234567890123456789:",
            "123456789012345678/", "12345678901234567890123456789012345678901234567890 ", "\u00009", "9\u0000"};
        for (String t : bad) {
            byte[] b = t.getBytes(StandardCharsets.UTF_8);
            assertEquals(Nat.MALFORMED, Nat.parseDecimal(s, b, 0, b.length), "'" + t + "'");
            assertEquals(Nat.MALFORMED, Nat.parseDecimal(s, t), "'" + t + "'");
        }
        byte[] b = "123".getBytes(StandardCharsets.US_ASCII);
        assertEquals(Nat.MALFORMED, Nat.parseDecimal(s, b, 1, 1), "empty range");
        assertEquals(Nat.MALFORMED, Nat.parseDecimal(s, b, 2, 1), "inverted range");
        assertEquals(23, Nat.parseDecimal(s, b, 1, 3));
        // every single byte value in every position of numerals of the three size classes
        java.util.HashSet<BigInteger> bigs = new java.util.HashSet<>();
        int before = s.count();
        for (String t : new String[] {"1234", "1234567890123456789", "9234567890123456789", "123456789012345678901234567890"}) {
            byte[] good = t.getBytes(StandardCharsets.US_ASCII);
            for (int pos = 0; pos < good.length; pos++) {
                for (int v = 0; v < 256; v++) {
                    byte[] c = good.clone();
                    c[pos] = (byte) v;
                    long h = Nat.parseDecimal(s, c, 0, c.length);
                    if (v >= '0' && v <= '9') {
                        BigInteger e = new BigInteger(new String(c, StandardCharsets.US_ASCII));
                        TestSupport.assertCanonical(s, h, e);
                        if (e.bitLength() > 63) {
                            bigs.add(e);
                        }
                    } else {
                        assertEquals(Nat.MALFORMED, h);
                    }
                }
            }
        }
        assertEquals(bigs.size(), s.count() - before, "a malformed numeral never leaves anything in the store");
    }

    @Test
    void lenientGrammarIsThatOfStringToNat() {
        NatStore s = store();
        // the examples of the docstring of String.Slice.isNat / toNat? in Lean 4.36
        String[][] ok = {{"0", "0"}, {"5", "5"}, {"05", "5"}, {"587", "587"}, {"1_000", "1000"}, {"100_000_000", "100000000"},
            {"1_2_3", "123"}, {"18_446_744_073_709_551_616", "18446744073709551616"}};
        for (String[] c : ok) {
            byte[] b = c[0].getBytes(StandardCharsets.US_ASCII);
            assertEquals(new BigInteger(c[1]), Nat.toBigInteger(s, Nat.parseDecimalLenient(s, b, 0, b.length)), c[0]);
        }
        for (String t : new String[] {"", "-587", " 5", "2+3", "0xff", "_123", "123_", "12__34", "_", "1_ 2"}) {
            byte[] b = t.getBytes(StandardCharsets.US_ASCII);
            assertEquals(Nat.MALFORMED, Nat.parseDecimalLenient(s, b, 0, b.length), "'" + t + "'");
        }
    }

    @Test
    void numeralsBeyondTheHardLimitAreRefusedWithoutConversion() {
        NatStore s = store();
        // 2^30 bits is the largest storable value; it has 323,228,497 digits
        byte[] digits = new byte[323_228_497 + 40];
        Arrays.fill(digits, (byte) '9');
        long t0 = System.nanoTime();
        assertEquals(Nat.TOO_BIG, Nat.parseDecimal(s, digits, 0, digits.length));
        digits[5] = 'x';
        assertEquals(Nat.MALFORMED, Nat.parseDecimal(s, digits, 0, digits.length));
        assertTrue(System.nanoTime() - t0 < 20_000_000_000L, "no conversion was attempted");
        assertEquals(0, s.count());
        assertEquals("<numeral too big>", Nat.describe(s, Nat.TOO_BIG));
        assertEquals("<malformed numeral>", Nat.describe(s, Nat.MALFORMED));
        assertEquals("<none>", Nat.describe(s, Nat.NONE));
    }
}
