package rlvm.ref.corpus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rlvm.api.EngineFactory;
import rlvm.api.Outcome;
import rlvm.api.Reason;
import rlvm.ref.RefEngine;
import rlvm.ref.RefOptions;

/**
 * The exports of the cache audit (*agentic-note*: rep-reference-caches) with the answers of the
 * official checker (leanchecker v4.36.0 in the arena's Docker image): {@code src/test/resources/r2/},
 * one row per export in {@code manifest.tsv}. They show what the official kernel's caches take for
 * the same term (binder names and infos: yes; two records with one content: yes; literals by value;
 * levels by spelling; an {@code mdata} node and the {@code nondep} flag of a let: other terms) and
 * that its verdict can depend on whether two occurrences of a term are one OBJECT.
 *
 * <p>Kernel level ({@link CorpusRunner}: inductive records are trusted). The practical engine (the
 * default) gives the official verdict except on the rows of kind T, where it accepts a derivable
 * statement that the official checker rejects for a reason of its implementation, and on the one
 * row of kind T-reject; the strict engine ({@code ref.exact=official}, with the strict loader's
 * decline of mdata and nondep) never gives the opposite verdict: the official one or none.
 */
class ExactnessGoldenTest {

    private static final class Row {
        String name;
        int official;
        int practical;
        int strict;
        String kind;
        String cls;
        Path file;
    }

    @TempDir
    static Path dir;

    private static final List<Row> ROWS = new ArrayList<>();

    private static int outcome(String word) {
        switch (word) {
            case "accept":
                return Outcome.ACCEPT;
            case "reject":
                return Outcome.REJECT;
            case "decline":
                return Outcome.DECLINE;
            default:
                throw new IllegalArgumentException(word);
        }
    }

    @BeforeAll
    static void unpack() throws IOException {
        try (InputStream in = ExactnessGoldenTest.class.getResourceAsStream("/r2/manifest.tsv")) {
            assertNotNull(in, "src/test/resources/r2/manifest.tsv");
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line = r.readLine(); line != null; line = r.readLine()) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split("\t");
                Row row = new Row();
                row.name = f[0];
                row.official = outcome(f[1]);
                row.practical = outcome(f[2]);
                row.strict = outcome(f[3]);
                row.kind = f[4];
                row.cls = f[5];
                row.file = dir.resolve(row.name + ".ndjson");
                try (InputStream gz = ExactnessGoldenTest.class.getResourceAsStream("/r2/" + row.name + ".ndjson.gz")) {
                    assertNotNull(gz, row.name);
                    Files.copy(new GZIPInputStream(gz), row.file);
                }
                ROWS.add(row);
            }
        }
        assertEquals(30, ROWS.size());
    }

    private static EngineFactory factory(String... settings) {
        return RefEngine.factory(RefOptions.parse(settings.length == 0 ? Map.of() : Map.of(settings[0], settings[1])));
    }

    private static String say(Row row, CorpusRunner.Result r) {
        return row.name + ": " + Outcome.name(r.verdict) + " " + r.message;
    }

    /** The default engine: official verdicts, except the documented rows of kind T (and the one T-reject). */
    @Test
    void practicalEngine() {
        List<String> problems = new ArrayList<>();
        for (Row row : ROWS) {
            CorpusRunner.Result r = CorpusRunner.run(row.file, false, false, factory());
            if (r.verdict != row.practical) {
                problems.add(say(row, r) + " (expected " + Outcome.name(row.practical) + ")");
            }
            // Outside the documented kinds the practical verdict IS the official one, and it never declines.
            if (row.kind.equals("-") && row.practical != row.official) {
                problems.add(row.name + ": the manifest gives a divergence without a kind");
            }
            if (r.verdict == Outcome.DECLINE) {
                problems.add(say(row, r) + ": the practical engine declined");
            }
        }
        assertEquals(List.of(), problems);
    }

    /** The strict engine: the official verdict or none, never the opposite one. */
    @Test
    void strictEngine() {
        List<String> problems = new ArrayList<>();
        for (Row row : ROWS) {
            CorpusRunner.Result r = CorpusRunner.run(row.file, false, false, factory("ref.exact", "official"), true);
            if (r.verdict != row.strict) {
                problems.add(say(row, r) + " (expected " + Outcome.name(row.strict) + ")");
            }
            if (r.verdict != Outcome.DECLINE && r.verdict != row.official) {
                problems.add(say(row, r) + ": the opposite of the official verdict");
            }
            if (r.verdict == Outcome.DECLINE && r.reason != Reason.UNSUPPORTED_INPUT) {
                problems.add(say(row, r) + ": declined for " + r.reason);
            }
        }
        assertEquals(List.of(), problems);
    }

    /** The launcher's policy key selects the strict engine; an explicit ref.exact wins. */
    @Test
    void policySelectsTheStrictEngine() {
        assertFalse(RefOptions.parse(Map.of()).exact());
        assertFalse(RefOptions.parse(Map.of("policy", "practical")).exact());
        assertTrue(RefOptions.parse(Map.of("policy", "strict")).exact());
        assertTrue(RefOptions.parse(Map.of("ref.exact", "official")).exact());
        assertFalse(RefOptions.parse(Map.of("policy", "strict", "ref.exact", "off")).exact());
        assertEquals(RefOptions.IDENTITY_SAME, RefOptions.parse(Map.of()).identity);
        assertEquals(RefOptions.IDENTITY_COUNT, RefOptions.parse(Map.of("policy", "strict", "ref.identity", "count")).identity);
        assertThrows(IllegalArgumentException.class, () -> RefOptions.parse(Map.of("policy", "lenient")));
        assertThrows(IllegalArgumentException.class, () -> RefOptions.parse(Map.of("ref.exact", "yes")));
        assertThrows(IllegalArgumentException.class, () -> RefOptions.parse(Map.of("ref.identity", "maybe")));
        assertTrue(new RefEngine.Provider().optionKeys().containsAll(List.of("policy", "ref.exact", "ref.identity")));
        Row row = row("r2-ptr-other-record-bad");
        CorpusRunner.Result r = CorpusRunner.run(row.file, false, false, factory("policy", "strict"));
        assertEquals(Outcome.DECLINE, r.verdict, r.message);
        assertEquals(Reason.UNSUPPORTED_INPUT, r.reason);
        assertTrue(r.message.contains("object identity") && r.message.contains("accept if") && r.message.contains("reject if"),
                r.message);
    }

    /**
     * The model of the official pointer test: taking the two occurrences for two objects gives the
     * official verdict where they are two records of the export, taking them for one where they are
     * one record. (Which it is cannot be read off the terms: that is why the strict engine declines.)
     */
    @Test
    void theTwoOfficialContinuations() {
        for (Row row : ROWS) {
            if (row.cls.equals("ptr-other")) {
                CorpusRunner.Result r = CorpusRunner.run(row.file, false, false, factory("ref.identity", "other"));
                assertEquals(row.official, r.verdict, say(row, r));
            } else if (row.cls.equals("ptr-same")) {
                CorpusRunner.Result other = CorpusRunner.run(row.file, false, false, factory("ref.identity", "other"));
                assertEquals(Outcome.REJECT, other.verdict, say(row, other));
                CorpusRunner.Result same = CorpusRunner.run(row.file, false, false, factory("ref.identity", "same"));
                assertEquals(row.official, same.verdict, say(row, same));
            }
        }
    }

    /**
     * A hazard whose two continuations lead to the same verdict is not declined: the strict engine
     * checks the declaration both ways (r2-ptr-other-record-order-ok: the comparison that could be
     * disturbed has been made before the hazard).
     */
    @Test
    void aHazardThatDoesNotMatterIsDecided() {
        Row row = row("r2-ptr-other-record-order-ok");
        CorpusRunner.Result r = CorpusRunner.run(row.file, false, false, factory("ref.exact", "official"), true);
        assertEquals(Outcome.ACCEPT, r.verdict, r.message);
        assertEquals(row.official, r.verdict);
    }

    private static Row row(String name) {
        for (Row row : ROWS) {
            if (row.name.equals(name)) {
                return row;
            }
        }
        throw new IllegalArgumentException(name);
    }
}
