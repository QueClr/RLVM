package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import rlvm.api.Outcome;
import rlvm.testkit.Json;
import rlvm.testkit.Leanchecker;

/**
 * The manifest rule of ISA 3.5 on the exports it was made for (RECONCILIATION section 2 "Static Prop
 * facts"; critic finding C3.3): {@code bugs/proj-of-stuck-prop}, {@code proj-of-subst-prop} and
 * {@code rec-of-subst-prop} have a family {@code Owner : (h : P) -> gate h} whose sort {@code gate h} reduces
 * to {@code Prop} under a generic {@code h} (a K-like recursor) and is stuck for the closed witness the
 * exploit uses. A fact derived after weak-head normalisation under the telescope's variables would call
 * {@code Owner.mk h b} a proof; the official {@code is_prop} throws there.
 *
 * <p>Rows: (1) on the three exports the record of the constructor has NO result level (not manifest), the
 * record of the family has one, and the machine with the switch {@code facts} gives the reference's verdict
 * on every declaration; (2) the mutation tests of ISA 3.5: each export rebuilt (a) with the family as
 * AXIOMS in place of the inductive type with the stuck sort, so that the machine itself checks the
 * family, its constructor and everything that uses them, and derives their records when it accepts them,
 * and (b) where the sort is a definition of its own, with that definition replaced by an axiom, so that
 * the sort is stuck everywhere. Every mutant must be judged as the official checker judges it
 * ({@code leanchecker --from-export}, cached in {@code work/oracle/cache}), with the switch on and off.
 *
 * <p>Slow group: it runs the official checker the first time.
 */
@Tag("slow")
class M5ManifestTest {

    static final String BUGS = "work/arena-site/tests/bad/bugs/";

    @BeforeAll
    static void ready() {
        assumeTrue(M5Support.ready(), "EVAL / WHNF / CONV are not implemented in this tree yet");
    }

    /** An export as lines, with its name table. */
    static final class Export {
        final List<String> lines;
        final Map<Long, String> names = new HashMap<>();

        Export(Path file) throws IOException {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            names.put(0L, "");
            for (String line : lines) {
                if (!line.startsWith("{\"in\"")) {
                    continue;
                }
                Json o = Json.parse(line);
                long id = o.get("in").asLong();
                Json s = o.has("str") ? o.get("str") : o.get("num");
                String pre = names.get(s.get("pre").asLong());
                String part = o.has("str") ? s.get("str").text() : String.valueOf(s.get("i").asLong());
                names.put(id, pre == null || pre.isEmpty() ? part : pre + "." + part);
            }
        }

        String name(Json decl) {
            return names.get(decl.get("name").asLong());
        }
    }

    static String axiom(Json decl) {
        Json d = Json.object();
        d.put("isUnsafe", false);
        d.put("levelParams", decl.get("levelParams").copy());
        d.put("name", decl.get("name").copy());
        d.put("type", decl.get("type").copy());
        Json o = Json.object();
        o.put("axiom", d);
        return Json.write(o);
    }

    /** The export with the inductive block of {@code family} replaced by one axiom per type, constructor and recursor. */
    static List<String> familyAsAxioms(Export e, String family) {
        List<String> out = new ArrayList<>();
        int replaced = 0;
        for (String line : e.lines) {
            if (line.startsWith("{\"inductive\"")) {
                Json ind = Json.parse(line).get("inductive");
                if (family.equals(e.name(ind.get("types").at(0)))) {
                    for (String part : new String[] {"types", "ctors", "recs"}) {
                        Json list = ind.get(part);
                        for (int i = 0; i < list.size(); i++) {
                            out.add(axiom(list.at(i)));
                        }
                    }
                    replaced++;
                    continue;
                }
            }
            out.add(line);
        }
        assertEquals(1, replaced, "the inductive block of " + family);
        return out;
    }

    /** The export with the definition {@code sort} replaced by an axiom of the same type: the sort never reduces. */
    static List<String> sortAsAxiom(Export e, String sort) {
        List<String> out = new ArrayList<>();
        int replaced = 0;
        for (String line : e.lines) {
            if (line.startsWith("{\"def\"")) {
                Json def = Json.parse(line).get("def");
                if (sort.equals(e.name(def))) {
                    out.add(axiom(def));
                    replaced++;
                    continue;
                }
            }
            out.add(line);
        }
        assertEquals(1, replaced, "the definition " + sort);
        return out;
    }

    /** The lines without the definitions and theorems named {@code drop} (the exploit's last steps). */
    static List<String> without(Export e, List<String> lines, String... drop) {
        List<String> out = new ArrayList<>();
        int dropped = 0;
        for (String line : lines) {
            String kind = line.startsWith("{\"def\"") ? "def" : line.startsWith("{\"thm\"") ? "thm" : null;
            if (kind != null) {
                String name = e.name(Json.parse(line).get(kind));
                boolean hit = false;
                for (String d : drop) {
                    hit |= d.equals(name);
                }
                if (hit) {
                    dropped++;
                    continue;
                }
            }
            out.add(line);
        }
        assertEquals(drop.length, dropped, "declarations to drop");
        return out;
    }

    static Leanchecker official() {
        Path home = Paths.get(System.getProperty("user.home"), ".elan", "toolchains",
                "leanprover--lean4---v" + Leanchecker.PINNED_VERSION);
        return new Leanchecker(home, Fixture.root().resolve("work/oracle/cache"));
    }

    /** The machine's own verdict on a file (reference kernel for the environment pass), with or without facts. */
    static int machineVerdict(Path file, boolean facts, String what) throws IOException {
        Fixture g = Fixture.load(file.toString());
        try {
            if (facts) {
                g.switches("facts");
            }
            Fixture.DiffReport r = g.diffAgainstReference();
            assertEquals(0, r.soundness + r.completeness, () -> what + ": " + r);
            Fixture.Verdict v = g.checkAll();
            assertNotEquals(Outcome.DECLINE, v.outcome, () -> what + ": " + v);
            return v.outcome;
        } finally {
            g.close();
        }
    }

    static void judgedAsOfficial(Path file, String what) throws IOException {
        Leanchecker lc = official();
        assumeTrue(lc.available(), "the official checker is not installed");
        Leanchecker.Result o = lc.check(file, Duration.ofSeconds(300));
        assertTrue(o.decided(), () -> what + ": the official checker did not decide: " + o);
        int expected = o.accepted() ? Outcome.ACCEPT : Outcome.REJECT;
        assertEquals(expected, machineVerdict(file, false, what), what + " (v0) against the official " + o.verdict);
        assertEquals(expected, machineVerdict(file, true, what), what + " (facts) against the official " + o.verdict);
        System.out.println("M5ManifestTest " + what + ": official " + o.verdict + ", machine agrees with and without facts");
    }

    static void mutants(String export, String family, String sort, String... exploit) throws IOException {
        Path original = Fixture.root().resolve(BUGS + export + ".ndjson");
        assumeTrue(Files.isRegularFile(original), export + " is not there");
        Export e = new Export(original);
        Path dir = Files.createTempDirectory("m5-manifest-");
        try {
            judgedAsOfficial(original, export);
            Path a = dir.resolve(export + "-family-axioms.ndjson");
            Files.write(a, familyAsAxioms(e, family), StandardCharsets.UTF_8);
            judgedAsOfficial(a, export + " with the family as axioms");
            if (sort != null) {
                Path b = dir.resolve(export + "-sort-axiom.ndjson");
                Files.write(b, sortAsAxiom(e, sort), StandardCharsets.UTF_8);
                judgedAsOfficial(b, export + " with the sort as an axiom");
            }
            if (exploit.length > 0) {
                // the axiom family and its uses, without the last steps of the exploit: what remains is
                // well typed for the official checker, so the machine must not reject it either
                Path c = dir.resolve(export + "-family-axioms-no-exploit.ndjson");
                Files.write(c, without(e, familyAsAxioms(e, family), exploit), StandardCharsets.UTF_8);
                judgedAsOfficial(c, export + " with the family as axioms, without " + String.join(", ", exploit));
            }
        } finally {
            try (java.util.stream.Stream<Path> s = Files.list(dir)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    Files.deleteIfExists(p);
                }
            }
            Files.deleteIfExists(dir);
        }
    }

    /** On the original export: the constructor's result level is not recorded, the family's is. */
    static void records(String export, String family) throws IOException {
        String path = BUGS + export + ".ndjson";
        assumeTrue(Fixture.exists(path), export + " is not there");
        Fixture g = Fixture.load(path);
        try {
            g.switches("facts");
            g.checkAll(); // admits everything up to the rejected unit, the family included
            g.m.beginDecl(Integer.MAX_VALUE, -1, Op.FUEL_UNLIMITED, Long.MAX_VALUE);
            int owner = g.run(Facts.sigd(g.m, g.cid(family), 0));
            int mk = g.run(Facts.sigd(g.m, g.cid(family + ".mk"), 0));
            assertTrue(Facts.usable(g.m, owner) && Facts.usable(g.m, mk), export);
            // Owner : (h : P) -> gate h.  INFER(gate h) is the SORT record Sort 1: manifest
            assertNotEquals(Layout.LREF_UNKNOWN, Facts.word(g.m, owner, Layout.F_RLVL), export + ": rlvl of the family");
            // Owner.mk : .. -> Owner h.  INFER(Owner h) = gate h, a sort only after reduction: NOT manifest
            assertEquals(Layout.LREF_UNKNOWN, Facts.word(g.m, mk, Layout.F_RLVL), export + ": rlvl of the constructor");
            assertEquals(0, Facts.rlvl(g.m, mk));
            assertTrue(g.m.idle());
        } finally {
            g.close();
        }
    }

    @Test
    void theConstructorOfAFamilyWithAStuckSortHasNoResultLevel() throws IOException {
        records("proj-of-subst-prop", "PR14806Subst.Owner");
        records("rec-of-subst-prop", "Issue.Owner");
        records("proj-of-stuck-prop", "Native64ResultSortOwner");
    }

    @Test
    void projOfSubstProp() throws IOException {
        mutants("proj-of-subst-prop", "PR14806Subst.Owner", "PR14806Subst.gate", "PR14806Subst.observe", "inconsistent");
    }

    @Test
    void recOfSubstProp() throws IOException {
        mutants("rec-of-subst-prop", "Issue.Owner", "Issue.resultSort");
    }

    @Test
    void projOfStuckProp() throws IOException {
        mutants("proj-of-stuck-prop", "Native64ResultSortOwner", null, "Native64ResultSortLeak.observe", "inconsistent");
    }
}
