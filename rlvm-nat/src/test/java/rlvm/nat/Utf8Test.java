package rlvm.nat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Strict UTF-8: exhaustive and randomised comparison with two independent references. */
class Utf8Test {

    // ------------------------------------------------------------------ references

    /**
     * Reference decoder written from table 3-7 of the Unicode standard (well-formed UTF-8 byte sequences) as
     * byte ranges. Returns the scalar values, or null with {@code errorAt[0]} set to the offending index.
     */
    static int[] refDecode(byte[] b, int from, int to, int[] errorAt) {
        int[] out = new int[to - from];
        int n = 0;
        int i = from;
        while (i < to) {
            int b0 = b[i] & 0xFF;
            int len;
            int lo = 0x80;
            int hi = 0xBF;
            if (b0 <= 0x7F) {
                len = 1;
            } else if (b0 >= 0xC2 && b0 <= 0xDF) {
                len = 2;
            } else if (b0 == 0xE0) {
                len = 3;
                lo = 0xA0;
            } else if ((b0 >= 0xE1 && b0 <= 0xEC) || b0 == 0xEE || b0 == 0xEF) {
                len = 3;
            } else if (b0 == 0xED) {
                len = 3;
                hi = 0x9F;
            } else if (b0 == 0xF0) {
                len = 4;
                lo = 0x90;
            } else if (b0 >= 0xF1 && b0 <= 0xF3) {
                len = 4;
            } else if (b0 == 0xF4) {
                len = 4;
                hi = 0x8F;
            } else {
                errorAt[0] = i;
                return null;
            }
            if (i + len > to) {
                errorAt[0] = i;
                return null;
            }
            int cp = len == 1 ? b0 : b0 & (0xFF >> (len + 1));
            for (int k = 1; k < len; k++) {
                int c = b[i + k] & 0xFF;
                int l = k == 1 ? lo : 0x80;
                int h = k == 1 ? hi : 0xBF;
                if (c < l || c > h) {
                    errorAt[0] = i;
                    return null;
                }
                cp = (cp << 6) | (c & 0x3F);
            }
            out[n++] = cp;
            i += len;
        }
        return Arrays.copyOf(out, n);
    }

    /** Second reference: the JDK's decoder in strict (reporting) mode. */
    static boolean jdkAccepts(byte[] b, int from, int to) {
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b, from, to - from));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    /** Reference of the official lossy repair, written from lean_mk_string_lossy_recover. */
    static byte[] refRepair(byte[] b, int from, int to) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int pos = from;
        int[] err = new int[1];
        while (pos < to) {
            // validate_utf8_one at pos: find the length of a well-formed sequence starting here
            int len = 0;
            for (int l = 1; l <= 4 && pos + l <= to; l++) {
                if (refDecode(b, pos, pos + l, err) != null) {
                    len = l;
                    break;
                }
            }
            if (len > 0) {
                out.write(b, pos, len);
                pos += len;
            } else {
                out.write(0xEF);
                out.write(0xBF);
                out.write(0xBD);
                do {
                    pos++;
                } while (pos < to && (b[pos] & 0xC0) == 0x80);
            }
        }
        return out.toByteArray();
    }

    private static void checkAgainstReferences(byte[] b, int from, int to) {
        int[] err = new int[1];
        int[] expected = refDecode(b, from, to, err);
        int v = Utf8.validate(b, from, to);
        int[] dst = new int[to - from + 1];
        Arrays.fill(dst, -7);
        int d = Utf8.decode(b, from, to, dst, 1);
        assertEquals(expected != null, jdkAccepts(b, from, to), () -> "references disagree on " + HexFormat.of().formatHex(b, from, to));
        if (expected == null) {
            assertEquals(~err[0], v, () -> "error offset for " + HexFormat.of().formatHex(b, from, to));
            assertEquals(~err[0], d);
            assertTrue(v < 0);
        } else {
            assertEquals(expected.length, v, () -> "count for " + HexFormat.of().formatHex(b, from, to));
            assertEquals(expected.length, d);
            assertArrayEquals(expected, Arrays.copyOfRange(dst, 1, 1 + d));
            assertEquals(-7, dst[0]);
            assertEquals(new String(b, from, to - from, StandardCharsets.UTF_8).codePointCount(0, new String(b, from, to - from, StandardCharsets.UTF_8).length()), v);
            assertEquals(v, StrLit.length(b, from, to));
        }
        byte[] rep = refRepair(b, from, to);
        assertEquals(rep.length, Utf8.repairedLength(b, from, to));
        byte[] out = new byte[rep.length + 2];
        out[0] = 5;
        out[out.length - 1] = 6;
        assertEquals(rep.length, Utf8.repairLossy(b, from, to, out, 1));
        assertArrayEquals(rep, Arrays.copyOfRange(out, 1, 1 + rep.length));
        assertEquals(5, out[0]);
        assertEquals(6, out[out.length - 1]);
        assertEquals(expected != null, rep.length == to - from && Arrays.equals(rep, 0, rep.length, b, from, to));
        assertTrue(Utf8.validate(rep, 0, rep.length) >= 0, "the repaired text is well-formed");
    }

    // ------------------------------------------------------------------ exhaustive tests

    @Test
    void everyScalarValueRoundTrips() {
        byte[] buf = new byte[6];
        for (int cp = 0; cp <= 0x10FFFF; cp++) {
            boolean scalar = cp < 0xD800 || cp > 0xDFFF;
            assertEquals(scalar, Utf8.isScalarValue(cp));
            assertEquals(scalar, StrLit.isValidChar(cp));
            assertEquals(scalar ? cp : 0, StrLit.charOfNat(cp));
            if (!scalar) {
                final int c = cp;
                assertThrows(IllegalArgumentException.class, () -> Utf8.encode(c, buf, 0));
                continue;
            }
            Arrays.fill(buf, (byte) 0x55);
            int n = Utf8.encode(cp, buf, 1);
            byte[] jdk = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
            assertEquals(jdk.length, n);
            assertEquals(n, Utf8.encodedLength(cp));
            assertTrue(Arrays.equals(jdk, 0, n, buf, 1, 1 + n));
            assertEquals(0x55, buf[0]);
            assertEquals(0x55, buf[1 + n]);
            assertEquals(cp, Utf8.codePointAt(buf, 1, 1 + n));
            assertEquals(cp, Utf8.codePointAt(buf, 1, 6), "bytes after the sequence are not read as part of it");
            if (n > 1) {
                assertEquals(-1, Utf8.codePointAt(buf, 1, n), "truncated");
            }
            assertEquals(1, Utf8.validate(buf, 1, 1 + n));
            assertEquals(1, Utf8.startOfPrevious(buf, 1, 1 + n));
        }
        assertTrue(!Utf8.isScalarValue(-1) && !Utf8.isScalarValue(0x110000) && !Utf8.isScalarValue(Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> Utf8.encode(0x110000, buf, 0));
        assertThrows(IllegalArgumentException.class, () -> Utf8.encode(-1, buf, 0));
    }

    @Test
    void everyOneTwoAndThreeByteSequence() {
        int[] err = new int[1];
        byte[] b = new byte[3];
        int valid = 0;
        for (int b0 = 0; b0 < 256; b0++) {
            b[0] = (byte) b0;
            checkAgainstReferences(b, 0, 1);
            for (int b1 = 0; b1 < 256; b1++) {
                b[1] = (byte) b1;
                checkAgainstReferences(b, 0, 2);
                for (int b2 = 0; b2 < 256; b2++) {
                    b[2] = (byte) b2;
                    // the full check allocates; use the cheap comparison for the 16.7M triples
                    int[] expected = refDecode(b, 0, 3, err);
                    int v = Utf8.validate(b, 0, 3);
                    if (expected == null) {
                        assertEquals(~err[0], v);
                    } else {
                        assertEquals(expected.length, v);
                        valid++;
                        int p = 0;
                        for (int e : expected) {
                            assertEquals(e, Utf8.codePointAt(b, p, 3));
                            p += Utf8.encodedLength(e);
                        }
                    }
                }
            }
        }
        // 128^3 ASCII triples + (ASCII, 2-byte) and (2-byte, ASCII) pairs + 3-byte scalars
        assertEquals(128 * 128 * 128 + 2 * 128 * 1920 + (0x10000 - 0x800 - 0x800), valid);
    }

    @Test
    void fourByteSequences() {
        byte[] b = new byte[4];
        int[] interesting = {0x00, 0x41, 0x7F, 0x80, 0x8F, 0x90, 0x9F, 0xA0, 0xBF, 0xC0, 0xC2, 0xE0, 0xED, 0xF0, 0xF4, 0xFF};
        int[] err = new int[1];
        int valid = 0;
        for (int b0 = 0xE0; b0 < 256; b0++) {
            b[0] = (byte) b0;
            for (int b1 = 0; b1 < 256; b1++) {
                b[1] = (byte) b1;
                for (int b2 = 0; b2 < 256; b2++) {
                    b[2] = (byte) b2;
                    for (int b3 : interesting) {
                        b[3] = (byte) b3;
                        int[] expected = refDecode(b, 0, 4, err);
                        int v = Utf8.validate(b, 0, 4);
                        if (expected == null) {
                            assertEquals(~err[0], v);
                        } else {
                            assertEquals(expected.length, v);
                            if (expected.length == 1) {
                                assertEquals(expected[0], Utf8.codePointAt(b, 0, 4));
                                valid++;
                            }
                        }
                    }
                }
            }
        }
        // 4-byte scalars whose last byte is one of the 6 continuation bytes in the list
        assertEquals((0x110000 - 0x10000) / 64 * 6, valid);
        for (int cp = 0x10000; cp <= 0x10FFFF; cp += 1) { // all four-byte scalars, and their first-byte neighbours
            Utf8.encode(cp, b, 0);
            assertEquals(cp, Utf8.codePointAt(b, 0, 4));
        }
    }

    @Test
    void namedIllFormedSequences() {
        String[] bad = {
            "c0 80", "c1 bf", "e0 80 80", "e0 9f bf", "f0 80 80 80", "f0 8f bf bf",   // overlong
            "ed a0 80", "ed bf bf", "ed a0 bd ed b8 80",                               // surrogates (CESU-8)
            "f4 90 80 80", "f5 80 80 80", "f7 bf bf bf", "f8 88 80 80 80", "fc 84 80 80 80 80", // beyond Unicode
            "80", "bf", "c3", "e2 82", "f0 9f 98", "c3 28", "e2 28 a1", "e2 82 28", "f0 28 8c bc", "f0 90 28 bc",
            "f0 90 8c 28", "fe", "ff", "ef bf", "41 80", "c3 a9 80", "c3 c3 a9",
        };
        for (String hex : bad) {
            byte[] b = HexFormat.ofDelimiter(" ").parseHex(hex);
            assertTrue(Utf8.validate(b, 0, b.length) < 0, hex);
            checkAgainstReferences(b, 0, b.length);
        }
        String[] good = {"", "00", "7f", "c2 80", "df bf", "e0 a0 80", "ed 9f bf", "ee 80 80", "ef bf bf", "f0 90 80 80",
            "f4 8f bf bf", "c3 a9 e2 82 ac f0 9f 98 80", "ef bb bf 41", "ef bf bd"};
        for (String hex : good) {
            byte[] b = HexFormat.ofDelimiter(" ").parseHex(hex);
            assertTrue(Utf8.validate(b, 0, b.length) >= 0, hex);
            checkAgainstReferences(b, 0, b.length);
        }
    }

    // ------------------------------------------------------------------ randomised tests

    private static byte[] randomBytes(Random r) {
        int n = r.nextInt(40);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] tmp = new byte[4];
        for (int i = 0; i < n; i++) {
            int kind = r.nextInt(20);
            if (kind < 12) { // a scalar value of a random size class
                int cp;
                switch (r.nextInt(4)) {
                    case 0: cp = r.nextInt(0x80); break;
                    case 1: cp = 0x80 + r.nextInt(0x780); break;
                    case 2: cp = 0x800 + r.nextInt(0xF800); break;
                    default: cp = 0x10000 + r.nextInt(0x100000); break;
                }
                if (cp >= 0xD800 && cp <= 0xDFFF) {
                    cp = 0xFFFD;
                }
                out.write(tmp, 0, Utf8.encode(cp, tmp, 0));
            } else if (kind < 15) { // a truncated sequence
                int len = Utf8.encode(r.nextBoolean() ? 0xE000 + r.nextInt(0x1000) : 0x10000 + r.nextInt(0x100000), tmp, 0);
                out.write(tmp, 0, 1 + r.nextInt(len - 1));
            } else if (kind < 17) {
                out.write(0x80 + r.nextInt(0x40)); // stray continuation
            } else if (kind < 19) {
                out.write(r.nextInt(256)); // anything
            } else {
                out.write(new byte[] {(byte) 0xED, (byte) (0xA0 + r.nextInt(0x20)), (byte) (0x80 + r.nextInt(0x40))}, 0, 3); // surrogate
            }
        }
        return out.toByteArray();
    }

    @Test
    void randomByteStringsAgreeWithTheReferences() {
        Random r = new Random(41);
        for (int i = 0; i < 300000; i++) {
            byte[] body = randomBytes(r);
            int pre = r.nextInt(4);
            byte[] b = new byte[pre + body.length + r.nextInt(4)];
            Arrays.fill(b, (byte) 0xBF); // continuation bytes around the range must not be read
            System.arraycopy(body, 0, b, pre, body.length);
            checkAgainstReferences(b, pre, pre + body.length);
        }
    }

    @Test
    void backwardWalkEqualsForwardDecode() {
        Random r = new Random(42);
        for (int i = 0; i < 50000; i++) {
            int n = r.nextInt(30);
            int[] cps = new int[n];
            byte[] b = new byte[4 * n + 3];
            int len = 3;
            b[0] = (byte) 0xF0; // junk before the range
            b[1] = (byte) 0x9F;
            b[2] = (byte) 0x98;
            for (int k = 0; k < n; k++) {
                int cp = r.nextInt(4) == 0 ? 0x10000 + r.nextInt(0x100000) : r.nextInt(3) == 0 ? r.nextInt(0x80) : r.nextInt(0xD800);
                cps[k] = cp;
                len += Utf8.encode(cp, b, len);
            }
            int pos = len;
            for (int k = n - 1; k >= 0; k--) {
                int start = Utf8.startOfPrevious(b, 3, pos);
                assertEquals(cps[k], Utf8.codePointAt(b, start, pos));
                assertEquals(pos - start, Utf8.encodedLength(cps[k]));
                pos = start;
            }
            assertEquals(3, pos);
        }
    }

    @Test
    void comparisonAndHashFollowTheScalarValues() {
        Random r = new Random(43);
        for (int i = 0; i < 100000; i++) {
            int[] x = randomScalars(r);
            int[] y = r.nextInt(5) == 0 ? x.clone() : randomScalars(r);
            if (r.nextInt(5) == 0 && y.length > 0) {
                y = Arrays.copyOf(x, Math.min(x.length, y.length)); // common prefix
            }
            int ox = r.nextInt(3);
            int oy = r.nextInt(3);
            byte[] bx = embed(x, ox);
            byte[] by = embed(y, oy);
            int ex = bx.length - 1;
            int ey = by.length - 1;
            int expected = Integer.signum(Arrays.compare(x, y));
            assertEquals(expected, Integer.signum(Utf8.compare(bx, ox, ex, by, oy, ey)));
            assertEquals(expected == 0, Utf8.equal(bx, ox, ex, by, oy, ey));
            assertEquals(expected == 0, StrLit.equal(bx, ox, ex, by, oy, ey));
            if (expected == 0) {
                assertEquals(Utf8.hash(bx, ox, ex), Utf8.hash(by, oy, ey));
            }
        }
        // the hash separates near-identical short strings
        java.util.HashSet<Integer> seen = new java.util.HashSet<>();
        byte[] b = new byte[3];
        for (int i = 0; i < 128; i++) {
            for (int j = 0; j < 128; j++) {
                b[0] = (byte) i;
                b[1] = (byte) j;
                seen.add(Utf8.hash(b, 0, 2));
            }
            b[0] = (byte) i;
            seen.add(Utf8.hash(b, 0, 1));
            seen.add(Utf8.hash(b, 0, 3));
        }
        seen.add(Utf8.hash(b, 0, 0));
        assertTrue(seen.size() >= 128 * 128 + 2 * 128, "collisions: " + (128 * 130 + 1 - seen.size()));
    }

    private static int[] randomScalars(Random r) {
        int n = r.nextInt(8);
        int[] x = new int[n];
        for (int k = 0; k < n; k++) {
            int cp = r.nextInt(3) == 0 ? r.nextInt(0x110000) : r.nextInt(0x100);
            x[k] = cp >= 0xD800 && cp <= 0xDFFF ? 0xE000 : cp;
        }
        return x;
    }

    /** Encodes the scalar values at offset {@code pre}, followed by one junk byte. */
    private static byte[] embed(int[] cps, int pre) {
        byte[] b = new byte[pre + 4 * cps.length + 1];
        Arrays.fill(b, 0, pre, (byte) 0xC3);
        int len = pre;
        for (int cp : cps) {
            len += Utf8.encode(cp, b, len);
        }
        byte[] out = Arrays.copyOf(b, len + 1);
        out[len] = (byte) 0x80;
        return out;
    }

    // ------------------------------------------------------------------ ground truth from the official checker

    /**
     * What {@code leanchecker --from-export} (4.36.0) turned the raw bytes of a {@code strVal} into, observed
     * in its error message for variants of {@code bugs/str-lit-utf8}; see the module README.
     */
    @Test
    void lossyRepairMatchesTheOfficialReader() {
        String[][] observed = {
            {"c3 a9 e2 82 ac f0 9f 98", "c3 a9 e2 82 ac ef bf bd"},                       // truncated 4-byte sequence
            {"c1 a9 e2 82 ac f0 9f 98 80", "ef bf bd e2 82 ac f0 9f 98 80"},              // overlong lead + continuation
            {"c3 a9 e2 82 ac ed a0 bd ed b8 80", "c3 a9 e2 82 ac ef bf bd ef bf bd"},     // encoded surrogate pair
            {"c3 a9 e2 82 ac f0 9f 98 80 80", "c3 a9 e2 82 ac f0 9f 98 80 ef bf bd"},     // stray continuation
            {"e9", "ef bf bd"},                                                             // Latin-1 byte
        };
        for (String[] c : observed) {
            byte[] in = HexFormat.ofDelimiter(" ").parseHex(c[0]);
            byte[] expected = HexFormat.ofDelimiter(" ").parseHex(c[1]);
            byte[] out = new byte[Utf8.repairedLength(in, 0, in.length)];
            assertEquals(out.length, Utf8.repairLossy(in, 0, in.length, out, 0));
            assertArrayEquals(expected, out, c[0]);
            assertTrue(Utf8.validate(in, 0, in.length) < 0);
        }
    }

    /**
     * The official kernel's own verdicts ({@code Kernel.isDefEq}) on "literal = String.ofList [Char.ofNat ..]":
     * true exactly for the list of scalar values, up to {@code Char.ofNat} mapping invalid numbers to 0.
     */
    @Test
    void scalarValuesAreWhatTheOfficialKernelExpandsTo() throws Exception {
        java.io.InputStream in = getClass().getResourceAsStream("official-str-vectors.txt");
        assertNotNull(in);
        NatStore base = NatStore.newBase();
        base.freeze();
        NatStore s = base.newOverlay();
        int lines = 0;
        int accepted = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("#")) {
                    continue;
                }
                String[] t = line.split(" ");
                assertEquals(5, t.length, line);
                byte[] bytes = t[0].equals("-") ? new byte[0] : HexFormat.of().parseHex(t[0]);
                int[] ours = new int[bytes.length];
                int n = StrLit.chars(bytes, 0, bytes.length, ours, 0);
                assertTrue(n >= 0, line);
                ours = Arrays.copyOf(ours, n);
                for (int k = 1; k <= 2; k++) {
                    boolean kernelSaysEqual = Boolean.parseBoolean(t[2 + k]);
                    assertTrue(t[2 + k].equals("true") || t[2 + k].equals("false"), line);
                    String[] elems = t[k].equals("-") ? new String[0] : t[k].split(",");
                    int[] chars = new int[elems.length];
                    for (int i = 0; i < elems.length; i++) {
                        chars[i] = StrLit.charOfNat(Nat.parseDecimal(s, elems[i]));
                    }
                    assertEquals(kernelSaysEqual, Arrays.equals(ours, chars), line);
                    if (kernelSaysEqual) {
                        accepted++;
                    }
                }
                lines++;
            }
        }
        assertTrue(lines > 450 && accepted > lines, lines + " " + accepted);
    }

    @Test
    void charValidity() {
        NatStore base = NatStore.newBase();
        base.freeze();
        NatStore s = base.newOverlay();
        long[] valid = {0, 1, 0x7F, 0xD7FF, 0xE000, 0xFFFF, 0x10000, 0x10FFFF};
        long[] invalid = {0xD800, 0xDBFF, 0xDC00, 0xDFFF, 0x110000, 0x7FFFFFFF, 0xFFFFFFFFL, 0x100000041L, Long.MAX_VALUE,
            Nat.ofBigInteger(s, TestSupport.TWO63), Nat.ofBigInteger(s, TestSupport.TWO64.add(java.math.BigInteger.valueOf(0x41)))};
        for (long v : valid) {
            assertTrue(StrLit.isValidChar(v));
            assertEquals(v, StrLit.charOfNat(v));
        }
        for (long v : invalid) {
            assertTrue(!StrLit.isValidChar(v));
            assertEquals(0, StrLit.charOfNat(v));
        }
        assertTrue(!StrLit.isValidChar(Nat.NONE) && !StrLit.isValidChar(Nat.TOO_BIG));
        assertEquals("String.ofList", StrLit.STRING_OF_LIST);
        assertEquals("Char.ofNat", StrLit.CHAR_OF_NAT);
        assertEquals("List.cons", StrLit.LIST_CONS);
        assertEquals("List.nil", StrLit.LIST_NIL);
        assertEquals("Char", StrLit.CHAR);
        assertEquals("String", StrLit.STRING);
        assertEquals(0xFFFD, Utf8.REPLACEMENT);
    }
}
