package rlvm.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The general-path JSON parser against the independent {@link MiniJson} on random texts. */
class JsonLineTest {
    /** The tree of {@link JsonLine} as the objects {@link MiniJson} produces (numbers as naturals or "not a natural"). */
    private static Object convert(JsonLine j, int node) {
        switch (j.type[node]) {
            case JsonLine.OBJ: {
                Map<String, Object> m = new LinkedHashMap<>();
                for (int c = j.child[node]; c >= 0; c = j.next[c]) {
                    m.put(new String(j.text, j.keyOff[c], j.keyLen[c], StandardCharsets.UTF_8), convert(j, c));
                }
                assertEquals(j.size[node], m.size());
                return m;
            }
            case JsonLine.ARR: {
                List<Object> l = new ArrayList<>();
                for (int c = j.child[node]; c >= 0; c = j.next[c]) {
                    l.add(convert(j, c));
                }
                assertEquals(j.size[node], l.size());
                return l;
            }
            case JsonLine.STR:
                return new String(j.text, j.valOff[node], j.valLen[node], StandardCharsets.UTF_8);
            case JsonLine.NUM:
                if (j.natural[node] == JsonLine.NOT_NAT) {
                    return "not a natural";
                }
                if (j.natural[node] == JsonLine.BIG) {
                    return new BigInteger(new String(j.text, j.valOff[node], j.valLen[node], StandardCharsets.US_ASCII));
                }
                return BigInteger.valueOf(j.natural[node]);
            case JsonLine.TRUE:
                return Boolean.TRUE;
            case JsonLine.FALSE:
                return Boolean.FALSE;
            default:
                return MiniJson.NULL;
        }
    }

    private static Object normalize(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> r = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                r.put((String) e.getKey(), normalize(e.getValue()));
            }
            return r;
        }
        if (o instanceof List<?> l) {
            List<Object> r = new ArrayList<>();
            for (Object x : l) {
                r.add(normalize(x));
            }
            return r;
        }
        if (o instanceof MiniJson.Num n) {
            return n.isNat() ? n.mantissa() : "not a natural";
        }
        return o;
    }

    private static final JsonLine PARSER = new JsonLine();

    /** Parses with both parsers and compares. Returns 0 accepted, 1 malformed, 2 unsupported. */
    private static int check(byte[] bytes) {
        String text = null;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            // ill-formed UTF-8
        }
        Object expected = null;
        boolean expectedOk = false;
        if (text != null) {
            try {
                expected = normalize(MiniJson.parse(text));
                expectedOk = true;
            } catch (MiniJson.Bad e) {
                // malformed
            }
        }
        // parse from the middle of a larger buffer to check that offsets are honoured
        byte[] buf = new byte[bytes.length + 11];
        System.arraycopy(bytes, 0, buf, 7, bytes.length);
        String shown = new String(bytes, StandardCharsets.ISO_8859_1);
        try {
            PARSER.parse(buf, 7, 7 + bytes.length);
            if (!expectedOk) {
                fail("accepted, but the reference parser rejects: " + shown);
            }
            assertEquals(expected, convert(PARSER, 0), shown);
            return 0;
        } catch (ExportException e) {
            if (expectedOk) {
                fail("rejected (" + e.getMessage() + "), but the reference parser accepts: " + shown);
            }
            if (text != null) {
                assertEquals(ExportException.Kind.MALFORMED, e.kind(), shown);
            }
            return e.kind() == ExportException.Kind.MALFORMED ? 1 : 2;
        }
    }

    private static final String[] ATOMS = {
        "0", "1", "-0", "12", "1.5", "1e2", "1E+2", "1e-2", "0.5e1", "-3", "123456789012345678901234567890",
        "9223372036854775807", "9223372036854775808", "1e19", "1.0", "10e-1", "0e0", "2.50e1", "1e40", "0.0",
        "true", "false", "null", "\"\"", "\"a\"", "\"\\n\\t\\\\\\\"\\/\\b\\f\\r\"", "\"\\u00e9\\ud83d\\ude00\"",
        "\"\\ud83d\"", "\"\\ude00x\"", "\"é€😀\"", "\"\\u0000\"", "\"key\"", "\"in\"", "\" \"", "\"\u007f\""
    };
    private static final String[] BROKEN_ATOMS = {
        "01", "+1", "1.", ".5", "1e", "1e+", "-", "--1", "1e100001", "0x10", "tru", "nul", "False", "\"", "\"\\x\"",
        "\"\\u12\"", "\"\\u12G4\"", "\"a\tb\"", "'a'", "NaN", "1 2", "\"\\ud83d\\udx00\"", "", "\"\\", "1e1.5", "1ee1"
    };

    private static String ws(Random rnd) {
        return switch (rnd.nextInt(8)) {
            case 0 -> " ";
            case 1 -> "\t";
            case 2 -> "\r";
            case 3 -> "\n";
            case 4 -> "  \n ";
            default -> "";
        };
    }

    private static String value(Random rnd, int depth, boolean allowBroken) {
        int k = rnd.nextInt(depth > 3 ? 2 : 6);
        if (k < 2 || depth > 5) {
            if (allowBroken && rnd.nextInt(30) == 0) {
                return BROKEN_ATOMS[rnd.nextInt(BROKEN_ATOMS.length)];
            }
            return ATOMS[rnd.nextInt(ATOMS.length)];
        }
        StringBuilder sb = new StringBuilder();
        int n = rnd.nextInt(5);
        if (k < 4) {
            sb.append('[').append(ws(rnd));
            for (int i = 0; i < n; i++) {
                sb.append(i == 0 ? "" : "," + ws(rnd)).append(value(rnd, depth + 1, allowBroken)).append(ws(rnd));
            }
            sb.append(']');
        } else {
            sb.append('{').append(ws(rnd));
            for (int i = 0; i < n; i++) {
                String key = rnd.nextInt(6) == 0 ? "\"k\"" : "\"k" + rnd.nextInt(allowBroken ? 6 : 1000000) + "\"";
                sb.append(i == 0 ? "" : "," + ws(rnd)).append(key).append(ws(rnd)).append(':').append(ws(rnd))
                        .append(value(rnd, depth + 1, allowBroken)).append(ws(rnd));
            }
            sb.append('}');
        }
        return sb.toString();
    }

    @Test
    void randomDocuments() {
        Random rnd = new Random(11);
        int[] outcomes = new int[3];
        for (int round = 0; round < 60_000; round++) {
            String doc = ws(rnd) + value(rnd, 0, round % 2 == 1) + ws(rnd);
            outcomes[check(doc.getBytes(StandardCharsets.UTF_8))]++;
        }
        assertTrue(outcomes[0] > 20_000, "accepted " + outcomes[0]);
        assertTrue(outcomes[1] > 5_000, "malformed " + outcomes[1]);
    }

    @Test
    void randomDamage() {
        Random rnd = new Random(12);
        int[] outcomes = new int[3];
        for (int round = 0; round < 100_000; round++) {
            byte[] doc = value(rnd, 0, false).getBytes(StandardCharsets.UTF_8);
            for (int i = 1 + rnd.nextInt(2); i > 0; i--) {
                doc = Gen.mutate(rnd, doc);
            }
            outcomes[check(doc)]++;
        }
        assertTrue(outcomes[0] > 5_000, "accepted " + outcomes[0]);
        assertTrue(outcomes[1] > 20_000, "malformed " + outcomes[1]);
        assertTrue(outcomes[2] > 500, "unsupported " + outcomes[2]);
    }

    @Test
    void atoms() {
        for (String a : ATOMS) {
            assertEquals(0, check(a.getBytes(StandardCharsets.UTF_8)), a);
            assertEquals(0, check((" [" + a + " , " + a + "]\n").getBytes(StandardCharsets.UTF_8)), a);
        }
        for (String a : BROKEN_ATOMS) {
            assertEquals(1, check(a.getBytes(StandardCharsets.UTF_8)), a);
            assertEquals(1, check(("{\"k\":" + a + "}").getBytes(StandardCharsets.UTF_8)), a);
        }
    }

    @Test
    void largestExponent() {
        assertEquals(0, check("1e100000".getBytes(StandardCharsets.UTF_8)));
        assertEquals(0, check("[12.5e100000,0e100000,1e-100001]".getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, check("1e100001".getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, check("0e100001".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void wideAndDeepDocuments() {
        StringBuilder wide = new StringBuilder("{");
        for (int i = 0; i < 20000; i++) {
            wide.append(i == 0 ? "" : ",").append("\"key").append(i).append("\":").append(i);
        }
        assertEquals(0, check((wide + "}").getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, check((wide + ",\"key19999\":0}").getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, check((wide + ",\"key0\":0}").getBytes(StandardCharsets.UTF_8)));
        assertEquals(0, check((wide + ",\"key20000\":{\"key1\":1,\"key2\":[{\"key1\":2}]}}").getBytes(StandardCharsets.UTF_8)));
        // nesting far beyond any recursion limit (MiniJson is recursive, so compare only with expectations)
        int depth = 300_000;
        byte[] deep = ("[".repeat(depth) + "{\"a\":" + "[".repeat(depth) + "]".repeat(depth) + "}" + "]".repeat(depth))
                .getBytes(StandardCharsets.UTF_8);
        try {
            PARSER.parse(deep, 0, deep.length);
        } catch (ExportException e) {
            fail(e.getMessage());
        }
        assertEquals(JsonLine.ARR, PARSER.type[0]);
        byte[] unbalanced = ("[".repeat(depth) + "]".repeat(depth - 1)).getBytes(StandardCharsets.UTF_8);
        try {
            PARSER.parse(unbalanced, 0, unbalanced.length);
            fail("accepted");
        } catch (ExportException e) {
            assertEquals(ExportException.Kind.MALFORMED, e.kind());
        }
    }

    @Test
    void utf8() {
        assertTrue(Utf8.isValid(T.utf8("aé€😀\u0000\u007f"), 0, T.utf8("aé€😀\u0000\u007f").length));
        byte[][] bad = {
            {(byte) 0x80}, {(byte) 0xC0, (byte) 0x80}, {(byte) 0xC1, (byte) 0xBF}, {(byte) 0xC3},
            {(byte) 0xE0, (byte) 0x80, (byte) 0x80}, {(byte) 0xE0, (byte) 0x9F, (byte) 0xBF},
            {(byte) 0xED, (byte) 0xA0, (byte) 0x80}, {(byte) 0xED, (byte) 0xBF, (byte) 0xBF}, {(byte) 0xE2, (byte) 0x82},
            {(byte) 0xF0, (byte) 0x80, (byte) 0x80, (byte) 0x80}, {(byte) 0xF0, (byte) 0x8F, (byte) 0xBF, (byte) 0xBF},
            {(byte) 0xF4, (byte) 0x90, (byte) 0x80, (byte) 0x80}, {(byte) 0xF5, (byte) 0x80, (byte) 0x80, (byte) 0x80},
            {(byte) 0xF0, (byte) 0x9F, (byte) 0x98}, {(byte) 0xFF}, {(byte) 0xFE}, {(byte) 0xC3, 'a'},
            {(byte) 0xE2, (byte) 0x82, 'a'}, {(byte) 0xF0, (byte) 0x9F, (byte) 0x98, 'a'},
        };
        for (byte[] b : bad) {
            assertTrue(!Utf8.isValid(b, 0, b.length), java.util.Arrays.toString(b));
        }
        byte[][] good = {
            {(byte) 0xC2, (byte) 0x80}, {(byte) 0xDF, (byte) 0xBF}, {(byte) 0xE0, (byte) 0xA0, (byte) 0x80},
            {(byte) 0xED, (byte) 0x9F, (byte) 0xBF}, {(byte) 0xEE, (byte) 0x80, (byte) 0x80}, {(byte) 0xEF, (byte) 0xBF, (byte) 0xBF},
            {(byte) 0xF0, (byte) 0x90, (byte) 0x80, (byte) 0x80}, {(byte) 0xF4, (byte) 0x8F, (byte) 0xBF, (byte) 0xBF},
        };
        for (byte[] b : good) {
            assertTrue(Utf8.isValid(b, 0, b.length), java.util.Arrays.toString(b));
            assertEquals(new String(b, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8).length, b.length);
        }
        // every scalar value round-trips through encode
        byte[] out = new byte[4];
        for (int cp = 0; cp <= 0x10FFFF; cp += cp < 0x3000 ? 1 : 97) {
            if (cp >= 0xD800 && cp < 0xE000) {
                continue;
            }
            int n = Utf8.encode(cp, out, 0);
            assertEquals(new String(Character.toChars(cp)), new String(out, 0, n, StandardCharsets.UTF_8));
            assertTrue(Utf8.isValid(out, 0, n));
        }
    }
}
