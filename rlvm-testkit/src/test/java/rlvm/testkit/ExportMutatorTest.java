package rlvm.testkit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rlvm.testkit.ExportMutator.Mutant;
import rlvm.testkit.ExportMutator.Mutation;

class ExportMutatorTest {

    @TempDir
    Path tmp;

    private static String read(Path p) throws Exception {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    /** Every line is exactly one JSON value; no blank lines; the file is not empty. */
    private static void assertWellFormedNdjson(String content, String what) {
        assertFalse(content.isEmpty(), what);
        String[] lines = content.split("\n", -1);
        int n = lines.length;
        if (lines[n - 1].isEmpty()) {
            n--; // final newline
        }
        assertTrue(n >= 1, what);
        for (int i = 0; i < n; i++) {
            String line = lines[i];
            assertFalse(line.trim().isEmpty(), what + ": blank line " + (i + 1));
            assertTrue(Json.isValid(line), what + ": line " + (i + 1) + " is not JSON: " + line);
        }
    }

    // ---- every kind, on the fixtures ---------------------------------------------------------------------

    @Test
    void mutantsAreWellFormedDistinctAndDifferentFromTheSource() throws Exception {
        Map<Mutation, Integer> produced = new HashMap<>();
        for (String name : Fixtures.NAMES) {
            Path src = Fixtures.copy(name, tmp.resolve("src"));
            String source = read(src);
            for (Mutation kind : Mutation.values()) {
                List<Mutant> ms = new ExportMutator(42).mutants(src, kind, 6, tmp.resolve("out-" + name));
                Set<String> contents = new HashSet<>();
                for (Mutant m : ms) {
                    String c = read(m.path);
                    assertWellFormedNdjson(c, m.path.getFileName().toString());
                    assertNotEquals(source, c, m.path + " equals its source");
                    assertTrue(contents.add(c), m.path + " duplicates another mutant");
                    assertEquals(Leanchecker.sha256(m.path), m.sha256);
                    assertEquals(Files.size(m.path), m.size);
                    assertEquals(kind, m.kind);
                    assertNotNull(m.description);
                    assertFalse(m.description.isEmpty());
                }
                produced.merge(kind, ms.size(), Integer::sum);
            }
        }
        // Across the fixtures every kind applies somewhere.
        for (Mutation kind : Mutation.values()) {
            assertTrue(produced.getOrDefault(kind, 0) > 0, "no mutant of kind " + kind);
        }
    }

    @Test
    void sameSeedSameBytesAndPrefixProperty() throws Exception {
        Path src = Fixtures.copy("070_nRec", tmp.resolve("src"));
        for (Mutation kind : Mutation.values()) {
            List<Path> a = new ExportMutator(7).mutate(src, kind, 5, tmp.resolve("a"));
            List<Path> b = new ExportMutator(7).mutate(src, kind, 5, tmp.resolve("b"));
            List<Path> small = new ExportMutator(7).mutate(src, kind, 2, tmp.resolve("c"));
            assertEquals(a.size(), b.size(), kind.toString());
            for (int i = 0; i < a.size(); i++) {
                assertEquals(a.get(i).getFileName(), b.get(i).getFileName());
                assertArrayEquals(Files.readAllBytes(a.get(i)), Files.readAllBytes(b.get(i)), kind + " mutant " + i);
            }
            for (int i = 0; i < small.size(); i++) {
                assertArrayEquals(Files.readAllBytes(a.get(i)), Files.readAllBytes(small.get(i)), kind + " prefix " + i);
            }
        }
    }

    @Test
    void differentSeedsGiveDifferentCampaigns() throws Exception {
        Path src = Fixtures.copy("070_nRec", tmp.resolve("src"));
        List<Path> a = new ExportMutator(1).mutate(src, Mutation.SWAP_CHILD, 8, tmp.resolve("a"));
        List<Path> b = new ExportMutator(2).mutate(src, Mutation.SWAP_CHILD, 8, tmp.resolve("b"));
        Set<String> sa = new HashSet<>();
        for (Path p : a) {
            sa.add(read(p));
        }
        int common = 0;
        for (Path p : b) {
            if (sa.contains(read(p))) {
                common++;
            }
        }
        assertTrue(common < 8, "two seeds produced the same eight mutants");
    }

    @Test
    void kindsThatDoNotApplyGiveNothing() throws Exception {
        Path src = Fixtures.copy("001_basicDef", tmp.resolve("src"));
        // no recursor, no literal, no binder in this file
        assertTrue(new ExportMutator(1).mutate(src, Mutation.FORGE_RULE, 3, tmp.resolve("o")).isEmpty());
        assertTrue(new ExportMutator(1).mutate(src, Mutation.CHANGE_LITERAL, 3, tmp.resolve("o")).isEmpty());
        assertTrue(new ExportMutator(1).mutate(src, Mutation.CHANGE_BINDER_TYPE, 3, tmp.resolve("o")).isEmpty());
        // a single declaration cannot be reordered
        assertTrue(new ExportMutator(1).mutate(src, Mutation.REORDER_DECL, 3, tmp.resolve("o")).isEmpty());
    }

    // ---- what each kind does ------------------------------------------------------------------------------

    /** Lines of the source and of the mutant that differ, as (index, source line, mutant line); same length required. */
    private static List<String[]> changedLines(String source, String mutant) {
        String[] s = source.split("\n");
        String[] m = mutant.split("\n");
        assertEquals(s.length, m.length, "line count changed");
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < s.length; i++) {
            if (!s[i].equals(m[i])) {
                out.add(new String[] {String.valueOf(i + 1), s[i], m[i]});
            }
        }
        return out;
    }

    @Test
    void singleFieldKindsEditExactlyOneLine() throws Exception {
        Path src = Fixtures.copy("070_nRec", tmp.resolve("src"));
        String source = read(src);
        Mutation[] kinds = {Mutation.SWAP_CHILD, Mutation.SWAP_LEVEL, Mutation.FORGE_COUNT, Mutation.FORGE_FLAG, Mutation.FORGE_RULE,
                Mutation.CHANGE_HINT, Mutation.CHANGE_BINDER_TYPE, Mutation.SWAP_NAME, Mutation.SWAP_DECL_ROOT, Mutation.CHANGE_INDEX,
                Mutation.CHANGE_DECL_KIND, Mutation.CHANGE_BINDER_INFO};
        for (Mutation kind : kinds) {
            List<Mutant> ms = new ExportMutator(3).mutants(src, kind, 6, tmp.resolve("o"));
            assertFalse(ms.isEmpty(), kind.toString());
            for (Mutant m : ms) {
                List<String[]> diff = changedLines(source, read(m.path));
                assertEquals(1, diff.size(), kind + ": " + m.description);
                assertEquals(String.valueOf(m.line), diff.get(0)[0], kind + ": line of " + m.description);
                assertTrue(m.description.startsWith("line " + m.line + " "), m.description);
            }
        }
    }

    @Test
    void swapChildRedirectsToAnEarlierExpression() throws Exception {
        Path src = Fixtures.copy("070_nRec", tmp.resolve("src"));
        ExportModel model = ExportModel.read(src);
        for (Mutant m : new ExportMutator(5).mutants(src, Mutation.SWAP_CHILD, 12, tmp.resolve("o"))) {
            ExportModel mm = ExportModel.read(m.path);
            int line = m.line - 1;
            assertEquals(ExportModel.RecordKind.EXPR, mm.recs[line].kind);
            assertEquals(model.recs[line].id, mm.recs[line].id, "the defined id must stay");
            int changed = 0;
            for (int f = 0; f < model.recs[line].fields.size(); f++) {
                ExportModel.Field before = model.recs[line].fields.get(f);
                ExportModel.Field after = mm.recs[line].fields.get(f);
                assertEquals(before.path, after.path);
                if (!before.value().text().equals(after.value().text())) {
                    changed++;
                    assertEquals(ExportModel.Role.REF_EXPR, before.role, m.description);
                    long target = after.value().asLong();
                    boolean definedEarlier = false;
                    for (int k = 0; k < model.definedBefore(ExportModel.Space.EXPR, line); k++) {
                        definedEarlier |= model.idAt(ExportModel.Space.EXPR, k) == target;
                    }
                    assertTrue(definedEarlier, m.description + ": target is not defined on an earlier line");
                }
            }
            assertEquals(1, changed, m.description);
        }
    }

    @Test
    void changeHintTouchesOnlyTheHint() throws Exception {
        Path src = Fixtures.copy("070_nRec", tmp.resolve("src"));
        String source = read(src);
        for (Mutant m : new ExportMutator(5).mutants(src, Mutation.CHANGE_HINT, 5, tmp.resolve("o"))) {
            String[] d = changedLines(source, read(m.path)).get(0);
            Json before = Json.parse(d[1]).get("def");
            Json after = Json.parse(d[2]).get("def");
            assertNotEquals(Json.write(before.get("hints")), Json.write(after.get("hints")));
            before.remove("hints");
            after.remove("hints");
            assertEquals(Json.write(before), Json.write(after));
        }
    }

    /** Ids renamed in order of first definition: equal for two files that differ only in numbering. */
    private static String normalised(ExportModel m) {
        List<Map<Long, Long>> maps = new ArrayList<>();
        for (ExportModel.Space s : ExportModel.Space.values()) {
            Map<Long, Long> map = new HashMap<>();
            for (int k = 0; k < m.idCount(s); k++) {
                map.put(m.idAt(s, k), (long) k + (s == ExportModel.Space.EXPR ? 0 : 1));
            }
            maps.add(map);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < m.lines.size(); i++) {
            Json tree = m.json[i].copy();
            for (ExportModel.Field f : ExportModel.analyse(tree, false).fields) {
                ExportModel.Space s = f.space();
                if (s != null) {
                    long old = f.value().asLong();
                    Long repl = old >= 0 ? maps.get(s.ordinal()).get(old) : null;
                    if (repl != null) {
                        f.set(Json.number(repl));
                    } else if (old != 0 || s == ExportModel.Space.EXPR) {
                        // ids too large for a long: look them up by text
                        f.set(Json.string("?" + f.value().text()));
                    }
                }
            }
            sb.append(Json.write(tree)).append('\n');
        }
        return sb.toString();
    }

    @Test
    void renumberingIsAnIsomorphism() throws Exception {
        for (String name : new String[] {"070_nRec", "103_natLitEq", "synthetic_literals"}) {
            Path src = Fixtures.copy(name, tmp.resolve("src"));
            ExportModel model = ExportModel.read(src);
            int small = 0;
            for (Mutant m : new ExportMutator(11).mutants(src, Mutation.RENUMBER_IDS, 8, tmp.resolve("o-" + name))) {
                ExportModel mm = ExportModel.read(m.path);
                assertEquals(model.lines.size(), mm.lines.size());
                assertEquals(model.lines.get(0), mm.lines.get(0), "the meta line is not touched");
                // each id is still bound once (read from the JSON directly: ids above 2^63 are beyond the model)
                for (String key : new String[] {"in", "il", "ie"}) {
                    Set<String> ids = new HashSet<>();
                    int defs = 0;
                    for (int i = 1; i < mm.lines.size(); i++) {
                        Json line = Json.parse(mm.lines.get(i));
                        if (line.has(key)) {
                            defs++;
                            assertTrue(line.get(key).isNatural());
                            assertTrue(ids.add(line.get(key).text()), "id bound twice: " + line.get(key).text());
                        }
                    }
                    ExportModel.Space space = key.equals("in") ? ExportModel.Space.NAME
                            : key.equals("il") ? ExportModel.Space.LEVEL : ExportModel.Space.EXPR;
                    assertEquals(model.idCount(space), defs);
                }
                if (!m.description.contains("2^64")) {
                    for (int i = 1; i < model.recs.length; i++) {
                        assertEquals(model.recs[i].kind, mm.recs[i].kind);
                    }
                    assertEquals(normalised(model), normalised(mm), m.description);
                    small++;
                }
            }
            assertTrue(small > 0, name);
        }
    }

    /** JSON equality up to the order of object members. */
    private static String sorted(Json v) {
        if (v.isObject()) {
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < v.size(); i++) {
                StringBuilder sb = new StringBuilder();
                Json.quote(v.key(i), sb);
                parts.add(sb + ":" + sorted(v.at(i)));
            }
            Collections.sort(parts);
            return "{" + String.join(",", parts) + "}";
        }
        if (v.isArray()) {
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < v.size(); i++) {
                parts.add(sorted(v.at(i)));
            }
            return "[" + String.join(",", parts) + "]";
        }
        return Json.write(v);
    }

    @Test
    void reformattingKeepsEveryLineTheSameJsonValue() throws Exception {
        for (String name : new String[] {"070_nRec", "synthetic_literals"}) {
            Path src = Fixtures.copy(name, tmp.resolve("src"));
            String[] sourceLines = read(src).split("\n");
            List<Mutant> ms = new ExportMutator(13).mutants(src, Mutation.REFORMAT_JSON, 12, tmp.resolve("o-" + name));
            assertTrue(ms.size() >= 8);
            boolean sawCrlf = false;
            for (Mutant m : ms) {
                String content = read(m.path);
                sawCrlf |= content.contains("\r\n");
                String[] lines = content.split("\n");
                assertEquals(sourceLines.length, lines.length, m.description);
                for (int i = 0; i < lines.length; i++) {
                    assertEquals(sorted(Json.parse(sourceLines[i])), sorted(Json.parse(lines[i])), m.description + " line " + (i + 1));
                }
            }
            assertTrue(sawCrlf || ms.size() < 12, "no CRLF variant among twelve");
        }
    }

    @Test
    void reformattingNeverEscapesCharactersOutsideTheBasicPlane() throws Exception {
        Path src = Fixtures.copy("synthetic_literals", tmp.resolve("src"));
        for (Mutant m : new ExportMutator(17).mutants(src, Mutation.REFORMAT_JSON, 12, tmp.resolve("o"))) {
            String content = read(m.path);
            assertFalse(content.toLowerCase().contains("\\ud83d"), "surrogate escape in " + m.description);
            assertTrue(content.contains(new String(Character.toChars(0x1F600))), m.description);
        }
    }

    @Test
    void reorderMovesOneDeclarationBehindALaterOne() throws Exception {
        Path src = Fixtures.copy("070_nRec", tmp.resolve("src"));
        List<String> source = Arrays.asList(read(src).split("\n"));
        List<Mutant> ms = new ExportMutator(19).mutants(src, Mutation.REORDER_DECL, 4, tmp.resolve("o"));
        assertFalse(ms.isEmpty());
        for (Mutant m : ms) {
            List<String> lines = Arrays.asList(read(m.path).split("\n"));
            List<String> a = new ArrayList<>(source);
            List<String> b = new ArrayList<>(lines);
            Collections.sort(a);
            Collections.sort(b);
            assertEquals(a, b, "the lines are the same multiset");
            String moved = source.get(m.line - 1);
            assertTrue(lines.indexOf(moved) > m.line - 1, "the declaration moved down");
            // every other line keeps its relative order
            List<String> rest = new ArrayList<>(lines);
            rest.remove(moved);
            List<String> restSource = new ArrayList<>(source);
            restSource.remove(moved);
            assertEquals(restSource, rest);
            // ids are still defined before use: the moved line only went further down
            ExportModel mm = ExportModel.read(m.path);
            assertEquals(ExportModel.RecordKind.DECL, mm.recs[lines.indexOf(moved)].kind);
        }
        // in 070_nRec the definition (line 61) uses the inductive block (line 59)
        assertTrue(ms.get(0).description.contains("a declaration that uses it") || ms.size() > 1);
    }

    @Test
    void dropAndDuplicateChangeTheLineCountByOne() throws Exception {
        Path src = Fixtures.copy("070_nRec", tmp.resolve("src"));
        int n = read(src).split("\n").length;
        for (Mutation kind : new Mutation[] {Mutation.DROP_RECORD, Mutation.DROP_DECL}) {
            for (Path p : new ExportMutator(23).mutate(src, kind, 2, tmp.resolve("o"))) {
                assertEquals(n - 1, read(p).split("\n").length, kind.toString());
            }
        }
        for (Mutation kind : new Mutation[] {Mutation.DUPLICATE_RECORD, Mutation.DUPLICATE_DECL}) {
            for (Path p : new ExportMutator(23).mutate(src, kind, 2, tmp.resolve("o"))) {
                List<String> lines = Arrays.asList(read(p).split("\n"));
                assertEquals(n + 1, lines.size(), kind.toString());
                assertEquals(n, new HashSet<>(lines).size(), "exactly one line occurs twice");
            }
        }
    }

    @Test
    void literalMutationsChangeTheLiteralOnly() throws Exception {
        Path src = Fixtures.copy("synthetic_literals", tmp.resolve("src"));
        String source = read(src);
        List<Mutant> ms = new ExportMutator(29).mutants(src, Mutation.CHANGE_LITERAL, 10, tmp.resolve("o"));
        assertTrue(ms.size() >= 6);
        boolean nat = false;
        boolean str = false;
        for (Mutant m : ms) {
            String[] d = changedLines(source, read(m.path)).get(0);
            Json before = Json.parse(d[1]);
            Json after = Json.parse(d[2]);
            assertEquals(before.get("ie").text(), after.get("ie").text());
            if (before.has("natVal")) {
                nat = true;
                assertTrue(after.get("natVal").isString());
                assertTrue(after.get("natVal").text().chars().allMatch(Character::isDigit), "a natVal stays a digit string");
            } else {
                str = true;
                assertTrue(after.get("strVal").isString());
            }
        }
        assertTrue(nat && str);
    }

    @Test
    void declarationKindChangeKeepsNameTypeAndLevels() throws Exception {
        Path src = Fixtures.copy("synthetic_literals", tmp.resolve("src"));
        String source = read(src);
        for (Mutant m : new ExportMutator(31).mutants(src, Mutation.CHANGE_DECL_KIND, 6, tmp.resolve("o"))) {
            String[] d = changedLines(source, read(m.path)).get(0);
            Json before = Json.parse(d[1]);
            Json after = Json.parse(d[2]);
            assertNotEquals(before.key(0), after.key(0));
            for (String k : new String[] {"name", "type", "levelParams"}) {
                assertEquals(Json.write(before.at(0).get(k)), Json.write(after.at(0).get(k)), k);
            }
            // members in lean4export's sorted order
            List<String> keys = new ArrayList<>();
            for (int i = 0; i < after.at(0).size(); i++) {
                keys.add(after.at(0).key(i));
            }
            List<String> sortedKeys = new ArrayList<>(keys);
            Collections.sort(sortedKeys);
            assertEquals(sortedKeys, keys);
            assertEquals(after.key(0).equals("axiom"), !after.at(0).has("value"));
        }
    }

    @Test
    void verdictPreservingKindsAreTheDocumentedOnes() {
        Set<Mutation> preserving = new HashSet<>();
        for (Mutation m : Mutation.values()) {
            if (m.verdictPreserving) {
                preserving.add(m);
            }
        }
        assertEquals(new HashSet<>(Arrays.asList(Mutation.CHANGE_HINT, Mutation.RENUMBER_IDS, Mutation.REFORMAT_JSON,
                Mutation.REORDER_DECL, Mutation.CHANGE_BINDER_INFO)), preserving);
    }

    // ---- the real tutorial tests, when they are there --------------------------------------------------------

    @Test
    void everyTutorialTestYieldsWellFormedMutantsOfEveryApplicableKind() throws Exception {
        List<Path> files = Fixtures.arenaTests("tutorial");
        org.junit.jupiter.api.Assumptions.assumeFalse(files.isEmpty(), "work/arena-site/tests not present");
        assertEquals(141, files.size());
        int total = 0;
        ExportMutator mutator = new ExportMutator(1);
        for (Path src : files) {
            ExportModel model = ExportModel.read(src);
            assertTrue(model.strict(), "schema does not cover " + src);
            String stem = src.getFileName().toString().replace(".ndjson", "");
            for (Mutation kind : Mutation.values()) {
                for (ExportMutator.Edit e : mutator.edits(model, stem, kind, 2)) {
                    assertWellFormedNdjson(e.content(), stem + " " + kind + " " + e.description);
                    total++;
                }
            }
        }
        assertTrue(total > 4000, "mutants: " + total);
    }
}
