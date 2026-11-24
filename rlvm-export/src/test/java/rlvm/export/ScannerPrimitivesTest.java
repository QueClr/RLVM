package rlvm.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** The word-at-a-time primitives of the fast path against byte-at-a-time definitions. */
class ScannerPrimitivesTest {
    private static byte[] padded(String s, int before, Random rnd) {
        byte[] b = new byte[before + s.length() + Scanner.PAD];
        rnd.nextBytes(b);
        byte[] t = T.latin1(s);
        System.arraycopy(t, 0, b, before, t.length);
        return b;
    }

    /** Expected result of num / numWide: (value << 5) | digits, or -1. */
    private static long expectedNum(String s, int maxDigits) {
        int n = 0;
        while (n < s.length() && s.charAt(n) >= '0' && s.charAt(n) <= '9') {
            n++;
        }
        if (n == 0 || n > maxDigits || (n > 1 && s.charAt(0) == '0')) {
            return -1;
        }
        return (Long.parseLong(s.substring(0, n)) << 5) | n;
    }

    @Test
    void lit() {
        assertEquals(0x7B, Scanner.lit("{"));
        assertEquals(0x227BL, Scanner.lit("{\""));
        assertEquals(0x3837363534333231L, Scanner.lit("12345678"));
        assertThrows(IllegalArgumentException.class, () -> Scanner.lit("123456789"));
    }

    @Test
    void numbers() {
        Random rnd = new Random(3);
        String terminators = ",}]\" \n\r\t:x-.e/_\u0000\u00ff\u0080";
        for (int round = 0; round < 200_000; round++) {
            int digits = rnd.nextInt(20);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < digits; i++) {
                sb.append((char) ('0' + (rnd.nextInt(4) == 0 ? 0 : rnd.nextInt(10))));
            }
            sb.append(terminators.charAt(rnd.nextInt(terminators.length())));
            // followed by anything, including more digits
            for (int i = rnd.nextInt(12); i > 0; i--) {
                sb.append((char) (rnd.nextBoolean() ? '0' + rnd.nextInt(10) : rnd.nextInt(256)));
            }
            sb.append('\n');
            String s = sb.toString();
            int before = rnd.nextInt(9);
            byte[] b = padded(s, before, rnd);
            assertEquals(expectedNum(s, 9), Scanner.num(b, before), s);
            assertEquals(expectedNum(s, 15), Scanner.numWide(b, before), s);
        }
    }

    @Test
    void numberBoundaries() {
        Random rnd = new Random(4);
        for (String s : new String[] {"0,", "9,", "10,", "99999999,", "100000000,", "999999999,", "1000000000,",
            "00,", "01,", "000000000,", "012345678,", "999999999999999,", "1000000000000000,", "0123456789012,", ",",
            "12345678\n", "123456789\n", "1234567890\n", "123456789012345\n", "1234567890123456\n"}) {
            byte[] b = padded(s, 3, rnd);
            assertEquals(expectedNum(s, 9), Scanner.num(b, 3), s);
            assertEquals(expectedNum(s, 15), Scanner.numWide(b, 3), s);
        }
    }

    @Test
    void plainStrings() {
        Random rnd = new Random(5);
        for (int round = 0; round < 200_000; round++) {
            int len = rnd.nextInt(40);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < len; i++) {
                int c = switch (rnd.nextInt(30)) {
                    case 0 -> rnd.nextInt(256);
                    case 1 -> '\\';
                    case 2 -> 0x1F;
                    case 3 -> 0x7F;
                    case 4 -> 0x80;
                    case 5 -> ' ';
                    case 6 -> '!';
                    case 7 -> '#';
                    case 8 -> '[';
                    case 9 -> ']';
                    default -> 'a' + rnd.nextInt(26);
                };
                sb.append((char) c);
            }
            sb.append(rnd.nextInt(8) == 0 ? "\n" : "\"").append("}}\n");
            String s = sb.toString();
            int expected = -1;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '"') {
                    expected = i;
                    break;
                }
                if (c < 0x20 || c >= 0x80 || c == '\\') {
                    break;
                }
            }
            int before = rnd.nextInt(9);
            assertEquals(expected, Scanner.plainString(padded(s, before, rnd), before), s);
        }
    }

    @Test
    void newlines() {
        Random rnd = new Random(6);
        for (int round = 0; round < 50_000; round++) {
            int len = rnd.nextInt(60);
            byte[] b = new byte[len + 1 + Scanner.PAD];
            rnd.nextBytes(b);
            for (int i = 0; i < len; i++) {
                if (b[i] == '\n') {
                    b[i] = 'x';
                }
            }
            b[len] = '\n';
            int from = rnd.nextInt(len + 1);
            assertEquals(len, Scanner.newline(b, from, len + 1));
        }
        byte[] none = new byte[40];
        assertThrows(IllegalStateException.class, () -> Scanner.newline(none, 0, 20));
    }
}
