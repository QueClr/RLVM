package rlvm.nat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Literals of real lean4export files: the 230 published arena tests and the init / std exports (and the
 * mathlib export when {@code -Drlvm.nat.mathlib=true} and the file exists). Every line is validated as
 * UTF-8, every {@code natVal} is parsed and printed back, every {@code strVal} is decoded and compared with
 * the JDK's decoding.
 */
class RealFilesTest {
    private static final Path ROOT = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
    private static final byte[] NAT_KEY = "\"natVal\":\"".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] STR_KEY = "\"strVal\":\"".getBytes(StandardCharsets.US_ASCII);

    private long lines;
    private long natLits;
    private long bigNatLits;
    private long strLits;
    private long strChars;
    private long nonAsciiStrLits;
    private int longestNat;

    private static int indexOf(byte[] b, int from, int to, byte[] key) {
        outer:
        for (int i = from; i <= to - key.length; i++) {
            for (int k = 0; k < key.length; k++) {
                if (b[i + k] != key[k]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** Checks one line {@code b[from .. to)} (without the newline). */
    private void checkLine(NatStore s, byte[] b, int from, int to, String where) {
        lines++;
        int count = Utf8.validate(b, from, to);
        assertTrue(count >= 0, () -> "ill-formed UTF-8 in " + where);
        int nonContinuation = 0;
        for (int i = from; i < to; i++) {
            if ((b[i] & 0xC0) != 0x80) {
                nonContinuation++;
            }
        }
        assertEquals(nonContinuation, count, where);
        if (to - from < 12 || b[from] != '{' || b[from + 1] != '"') {
            return;
        }
        byte k = b[from + 2];
        if (k != 'i' && k != 'n' && k != 's') {
            return; // literal records start with "ie", or with the literal key itself
        }
        int p = indexOf(b, from, to, NAT_KEY);
        if (p >= 0) {
            int start = p + NAT_KEY.length;
            int end = start;
            while (end < to && b[end] != '"') {
                end++;
            }
            long h = Nat.parseDecimal(s, b, start, end);
            String text = new String(b, start, end - start, StandardCharsets.US_ASCII);
            BigInteger expected = new BigInteger(text);
            TestSupport.assertCanonical(s, h, expected);
            assertEquals(text, Nat.toDecimalString(s, h), where);
            natLits++;
            longestNat = Math.max(longestNat, end - start);
            if (Nat.isBig(h)) {
                bigNatLits++;
            }
        }
        p = indexOf(b, from, to, STR_KEY);
        if (p >= 0) {
            checkString(b, p + STR_KEY.length, to, where);
        }
    }

    /** Decodes the JSON string starting at {@code start} twice: bytes + Utf8, and chars + JDK. */
    private void checkString(byte[] b, int start, int to, String where) {
        // reference: decode the rest of the line with the JDK, then unescape on chars
        String rest = new String(b, start, to - start, StandardCharsets.UTF_8);
        StringBuilder ref = new StringBuilder();
        int i = 0;
        while (rest.charAt(i) != '"') {
            char c = rest.charAt(i++);
            if (c == '\\') {
                char e = rest.charAt(i++);
                switch (e) {
                    case 'n': ref.append('\n'); break;
                    case 'r': ref.append('\r'); break;
                    case 't': ref.append('\t'); break;
                    case 'b': ref.append('\b'); break;
                    case 'f': ref.append('\f'); break;
                    case 'u': ref.append((char) Integer.parseInt(rest.substring(i, i + 4), 16)); i += 4; break;
                    default: ref.append(e); break;
                }
            } else {
                ref.append(c);
            }
        }
        int[] expected = ref.codePoints().toArray();
        // ours: unescape on bytes (escapes re-encoded by the JDK), then decode strictly
        byte[] raw = new byte[to - start];
        int n = 0;
        int p = start;
        while (b[p] != '"') {
            byte c = b[p++];
            if (c == '\\') {
                byte e = b[p++];
                if (e == 'u') {
                    int unit = Integer.parseInt(new String(b, p, 4, StandardCharsets.US_ASCII), 16);
                    p += 4;
                    String chars;
                    if (Character.isHighSurrogate((char) unit) && b[p] == '\\' && b[p + 1] == 'u') {
                        int low = Integer.parseInt(new String(b, p + 2, 4, StandardCharsets.US_ASCII), 16);
                        p += 6;
                        chars = new String(new char[] {(char) unit, (char) low});
                    } else {
                        chars = String.valueOf((char) unit);
                    }
                    for (byte x : chars.getBytes(StandardCharsets.UTF_8)) {
                        raw[n++] = x;
                    }
                } else {
                    raw[n++] = (byte) (e == 'n' ? '\n' : e == 'r' ? '\r' : e == 't' ? '\t' : e == 'b' ? '\b' : e == 'f' ? '\f' : e);
                }
            } else {
                raw[n++] = c;
            }
        }
        int[] ours = new int[n];
        int count = StrLit.chars(raw, 0, n, ours, 0);
        assertEquals(expected.length, count, where);
        assertArrayEquals(expected, java.util.Arrays.copyOf(ours, count), where);
        // rebuild the list from the tail, the way the kernel conses it
        int pos = n;
        for (int k = count - 1; k >= 0; k--) {
            int st = Utf8.startOfPrevious(raw, 0, pos);
            assertEquals(expected[k], Utf8.codePointAt(raw, st, pos));
            pos = st;
        }
        assertEquals(0, pos);
        strLits++;
        strChars += count;
        if (count != n) {
            nonAsciiStrLits++;
        }
    }

    private void checkFile(NatStore s, Path file) throws IOException {
        byte[] buf = new byte[1 << 22];
        int len = 0;
        long lineNo = 0;
        try (InputStream in = Files.newInputStream(file)) {
            for (;;) {
                int n = in.read(buf, len, buf.length - len);
                if (n < 0) {
                    break;
                }
                len += n;
                int lineStart = 0;
                for (int i = len - n; i < len; i++) {
                    if (buf[i] == '\n') {
                        lineNo++;
                        final long ln = lineNo;
                        checkLine(s, buf, lineStart, i, file.getFileName() + ":" + ln);
                        lineStart = i + 1;
                    }
                }
                if (lineStart == 0 && len == buf.length) {
                    buf = java.util.Arrays.copyOf(buf, buf.length * 2); // a line longer than the buffer
                } else {
                    System.arraycopy(buf, lineStart, buf, 0, len - lineStart);
                    len -= lineStart;
                }
            }
        }
        if (len > 0) {
            checkLine(s, buf, 0, len, file.getFileName() + ":last");
        }
        if (s.count() > 100_000) {
            s.reset();
        }
    }

    private static NatStore store() {
        NatStore base = NatStore.newBase();
        base.freeze();
        return base.newOverlay();
    }

    @Test
    void arenaTests() throws IOException {
        Path tests = ROOT.resolve("work/arena-site/tests");
        assumeTrue(Files.isDirectory(tests), "arena tests not present");
        List<Path> files = new ArrayList<>();
        try (Stream<Path> st = Files.walk(tests)) {
            st.filter(p -> p.toString().endsWith(".ndjson")).sorted().forEach(files::add);
        }
        NatStore s = store();
        for (Path f : files) {
            checkFile(s, f);
        }
        System.out.printf("RealFilesTest arena tests: %d files, %d lines, %d natVal (%d >= 2^63, longest %d digits), "
                + "%d strVal (%d non-ASCII, %d chars)%n", files.size(), lines, natLits, bigNatLits, longestNat, strLits, nonAsciiStrLits, strChars);
        assertTrue(files.size() >= 230, "files: " + files.size());
        assertTrue(natLits > 500 && strLits > 100 && bigNatLits >= 2 && nonAsciiStrLits >= 2);
    }

    @Test
    void initAndStdExports() throws IOException {
        Path init = ROOT.resolve("work/arena-site/generated/init.ndjson");
        Path std = ROOT.resolve("work/arena-site/generated/std.ndjson");
        assumeTrue(Files.isRegularFile(init) && Files.isRegularFile(std), "init/std exports not present");
        NatStore s = store();
        checkFile(s, init);
        assertEquals(285, natLits, "natVal records of init.ndjson");
        assertEquals(1837, strLits, "strVal records of init.ndjson");
        checkFile(s, std);
        System.out.printf("RealFilesTest init+std: %d lines, %d natVal (%d >= 2^63, longest %d digits), %d strVal "
                + "(%d non-ASCII, %d chars)%n", lines, natLits, bigNatLits, longestNat, strLits, nonAsciiStrLits, strChars);
        assertEquals(285 + 453, natLits);
        assertEquals(1837 + 3055, strLits);
    }

    /** The large corpora (mathlib 6.3 GB, cslib, cedar, ...): run with {@code -Drlvm.nat.corpora=true}. */
    @Test
    void largeCorpora() throws IOException {
        Path exports = ROOT.resolve("work/exports");
        assumeTrue(Boolean.getBoolean("rlvm.nat.corpora") && Files.isDirectory(exports), "large corpora not requested");
        List<Path> files = new ArrayList<>();
        try (Stream<Path> st = Files.list(exports)) {
            st.filter(p -> p.toString().endsWith(".ndjson") && Files.isRegularFile(p)).sorted().forEach(files::add);
        }
        NatStore s = store();
        for (Path f : files) {
            long l0 = lines;
            long n0 = natLits;
            long b0 = bigNatLits;
            long s0 = strLits;
            longestNat = 0;
            checkFile(s, f);
            System.out.printf("RealFilesTest %s: %d lines, %d natVal (%d >= 2^63, longest %d digits), %d strVal%n",
                    f.getFileName(), lines - l0, natLits - n0, bigNatLits - b0, longestNat, strLits - s0);
        }
        assertTrue(natLits > 1000);
    }
}
