package rlvm.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void canonicalLinesRoundTripByteForByte() throws Exception {
        int lines = 0;
        for (String name : Fixtures.NAMES) {
            for (String line : Fixtures.resource(name).split("\n")) {
                assertEquals(line, Json.write(Json.parse(line)), name);
                lines++;
            }
        }
        assertTrue(lines > 150);
    }

    /** Every line of the exporter-generated tutorial tests is reproduced exactly: the writer is lean4export's. */
    @Test
    void tutorialTestsRoundTripByteForByte() throws Exception {
        List<Path> files = Fixtures.arenaTests("tutorial");
        org.junit.jupiter.api.Assumptions.assumeFalse(files.isEmpty(), "work/arena-site/tests not present");
        int lines = 0;
        for (Path f : files) {
            for (String line : new String(Files.readAllBytes(f), StandardCharsets.UTF_8).split("\n")) {
                assertEquals(line, Json.write(Json.parse(line)), f.toString());
                lines++;
            }
        }
        assertTrue(lines > 10000, "lines: " + lines);
    }

    @Test
    void escapesAreDecodedAndWrittenLikeLean() {
        Json v = Json.parse("{\"s\":\"a\\u03b1\\n\\t\\\"\\\\\\/\\u0001\"}");
        assertEquals("a\u03b1\n\t\"\\/\u0001", v.get("s").text());
        // Lean writes \n and \r by name, other control characters as \\u00xx, everything else raw
        assertEquals("{\"s\":\"a\u03b1\\n\\u0009\\\"\\\\/\\u0001\"}", Json.write(v));
        assertEquals(v.get("s").text(), Json.parse(Json.write(v)).get("s").text());
    }

    @Test
    void numbersKeepTheirText() {
        String big = "123456789012345678901234567890123456789012345678901234567890";
        Json v = Json.parse("{\"n\":" + big + ",\"m\":0,\"x\":-1.5e3}");
        assertEquals(big, v.get("n").text());
        assertTrue(v.get("n").isNatural());
        assertEquals(-1, v.get("n").asLong());
        assertEquals(0, v.get("m").asLong());
        assertFalse(v.get("x").isNatural());
        assertEquals("{\"n\":" + big + ",\"m\":0,\"x\":-1.5e3}", Json.write(v));
    }

    @Test
    void keyOrderIsKept() {
        Json v = Json.parse(" { \"b\" : 1 , \"a\" : [ true , null , \"x\" ] } ");
        assertEquals("{\"b\":1,\"a\":[true,null,\"x\"]}", Json.write(v));
        v.permute(new int[] {1, 0});
        assertEquals("{\"a\":[true,null,\"x\"],\"b\":1}", Json.write(v));
    }

    @Test
    void malformedTextIsRefused() {
        for (String bad : new String[] {"", "{", "{\"a\":}", "[1,]", "{\"a\":1}x", "01", "\"abc", "{\"a\" 1}", "nul",
                "\"\\q\"", "\"\\u12\"", "{\"a\":1,}", "1.", "-", "\"a\nb\""}) {
            assertFalse(Json.isValid(bad), bad);
            assertThrows(Json.ParseException.class, () -> Json.parse(bad), bad);
        }
        for (String good : new String[] {"0", "-0", "[]", "{}", "\"\"", "true", " [ 1 , 2 ] ", "1e5", "{\"a\":{\"b\":[]}}"}) {
            assertTrue(Json.isValid(good), good);
        }
    }

    @Test
    void copyIsDeep() {
        Json v = Json.parse("{\"a\":{\"b\":[1,2]}}");
        Json c = v.copy();
        c.get("a").get("b").set(0, Json.number(9));
        assertEquals("{\"a\":{\"b\":[1,2]}}", Json.write(v));
        assertEquals("{\"a\":{\"b\":[9,2]}}", Json.write(c));
    }

    @Test
    void prettyOutputParsesBack() {
        Json v = Json.parse("{\"a\":{\"b\":[1,2],\"c\":[{\"d\":null}]},\"e\":\"x\"}");
        assertEquals(Json.write(v), Json.write(Json.parse(Json.pretty(v))));
    }
}
