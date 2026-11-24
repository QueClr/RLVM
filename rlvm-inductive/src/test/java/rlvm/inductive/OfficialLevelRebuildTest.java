package rlvm.inductive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import rlvm.api.KernelException;
import rlvm.api.ResourceException;
import rlvm.store.DeclKind;

/**
 * Finding L-F1, against the official checker: the official {@code add_inductive} refers to the
 * block's own constants with level-parameter objects it makes itself, and the official
 * {@code instantiate} rebuilds every level node above such a parameter ({@code imax u 1} becomes
 * {@code max u 1}), so the type the kernel infers for these references is not always equivalent,
 * for the official relation, to the declared spelling ({@code InductiveAdmission}, "the official
 * rebuild of levels").
 *
 * <p>Twelve declaration shapes, each over the levels of {@code level-rebuild-pool.tsv} (taken from the
 * level questions of the finding; 85 of the 125 single levels are respelled inequivalently). Every
 * shape puts the level where another comparison of the official kernel meets a rebuilt type:
 * <pre>
 *   SORT      inductive T : Sort L | mk : T                        (the result sort is never compared)
 *   PROP      inductive P (a : Sort L) : Prop | mk : P a           (parameter; K-like predicate)
 *   IDX       inductive T : Sort L -> Type | mk : T A              (index domain; A : Sort L an axiom)
 *   IDXREC    ... | base : T A | step : T A -> T A                 (the export's own reference to T inside a minor premise)
 *   MUTREC    mutual A (a : Sort L) | mk : B a -> A a,  B (a : Sort L) | nil | mk : A a -> B a
 *   RES       inductive T : G -> Type | mk : T g.{L}               (constructor result against the major premise)
 *   RESD      the same with a definition gd.{u} : G := g0          (equal after unfolding: accepted)
 *   RECF      inductive T : G -> Type | base | step : T g.{L} -> T g0   (recursive field)
 *   NEST      inductive T (a : Sort L) : Type | mk : List (T a) -> T a  (auxiliary type of a nested block)
 *   NESTIDX   inductive T : Type | mk : (i : F.{L}) -> J.{L} T i -> T   (index domain of an auxiliary type)
 *   NESTIDXD  the same with a definition Fd.{u} : Type := G        (accepted)
 *   MUT       mutual A (a : Sort L1), B (a : Sort L2)              (second type against the first one's parameters)
 * </pre>
 * Each fixture is written out TWICE-derived: the export carries the recursor records a kernel that
 * keeps the declared spelling derives ({@code keepDeclaredLevels}; they are the official records
 * whenever the official kernel admits the block), so the official answer on the file is the official
 * kernel's answer on the block; the admission under test must give the same answer: accept iff the
 * official checker accepts, reject iff the official kernel rejects the block, never a decline. The
 * official answers are stored ({@link OfficialGolden}).
 */
class OfficialLevelRebuildTest {

    // ---- the level notation of the finding ---------------------------------------------------------------

    /** {@code (s X)}, {@code (m A B)}, {@code (i A B)}, {@code 0}, {@code u} to a level atom of {@link TermParser}. */
    static String level(String s) {
        int[] pos = {0};
        String r = level(s, pos);
        if (pos[0] != s.length()) {
            throw new IllegalArgumentException(s);
        }
        return r;
    }

    private static String level(String s, int[] pos) {
        if (s.charAt(pos[0]) != '(') {
            int from = pos[0];
            while (pos[0] < s.length() && s.charAt(pos[0]) != ' ' && s.charAt(pos[0]) != ')') {
                pos[0]++;
            }
            return s.substring(from, pos[0]);
        }
        char op = s.charAt(pos[0] + 1);
        pos[0] += 3;
        String a = level(s, pos);
        String r;
        if (op == 's') {
            r = "(" + a + "+1)";
        } else {
            pos[0]++;
            String b = level(s, pos);
            r = "(" + (op == 'm' ? "max " : "imax ") + a + " " + b + ")";
        }
        if (s.charAt(pos[0]) != ')') {
            throw new IllegalArgumentException(s);
        }
        pos[0]++;
        return r;
    }

    // ---- the shapes ----------------------------------------------------------------------------------------

    private static final String U = "u v w";
    private static final String REC = "u_1 u v w";
    private static final String T = "T.{u,v,w}";

    private static final class Fixture {
        final String name;
        final Consumer<Env> build;

        Fixture(String name, Consumer<Env> build) {
            this.name = name;
            this.build = build;
        }
    }

    private static List<Fixture> fixtures() throws IOException {
        ArrayList<Fixture> out = new ArrayList<>();
        try (InputStream in = OfficialLevelRebuildTest.class.getResourceAsStream("level-rebuild-pool.tsv")) {
            assertNotNull(in, "level-rebuild-pool.tsv missing from the test resources");
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }
                String[] c = line.split("\t");
                if (c[0].equals("L")) {
                    single(out, c[1], level(c[1]));
                } else {
                    String l1 = level(c[1]);
                    String l2 = level(c[2]);
                    out.add(new Fixture("MUT " + c[1] + " / " + c[2], e -> e.inductive(U, 1)
                            .type("A", "Sort " + l1 + " → Type").ctor("A.mk", "∀ (a : Sort " + l1 + "), A.{u,v,w} a")
                            .type("B", "Sort " + l2 + " → Type").ctor("B.mk", "∀ (a : Sort " + l2 + "), B.{u,v,w} a")
                            .rec("A.rec", REC).rec("B.rec", REC).end()));
                }
            }
        }
        return out;
    }

    private static void single(ArrayList<Fixture> out, String shown, String l) {
        out.add(new Fixture("SORT " + shown, e -> e.inductive(U, 0).type("T", "Sort " + l).ctor("T.mk", T)
                .rec("T.rec", REC).end()));
        out.add(new Fixture("PROP " + shown, e -> e.inductive(U, 1).type("T", "Sort " + l + " → Prop")
                .ctor("T.mk", "∀ (a : Sort " + l + "), " + T + " a").rec("T.rec", REC).end()));
        out.add(new Fixture("IDX " + shown, e -> {
            e.axiom("A", U, "Sort " + l);
            e.inductive(U, 0).type("T", "Sort " + l + " → Type").ctor("T.mk", T + " A.{u,v,w}").rec("T.rec", REC).end();
        }));
        out.add(new Fixture("IDXREC " + shown, e -> {
            e.axiom("A", U, "Sort " + l);
            e.inductive(U, 0).type("T", "Sort " + l + " → Type").ctor("T.base", T + " A.{u,v,w}")
                    .ctor("T.step", T + " A.{u,v,w} → " + T + " A.{u,v,w}").rec("T.rec", REC).end();
        }));
        out.add(new Fixture("MUTREC " + shown, e -> e.inductive(U, 1)
                .type("A", "Sort " + l + " → Type").ctor("A.mk", "∀ (a : Sort " + l + "), B.{u,v,w} a → A.{u,v,w} a")
                .type("B", "Sort " + l + " → Type").ctor("B.nil", "∀ (a : Sort " + l + "), B.{u,v,w} a")
                .ctor("B.mk", "∀ (a : Sort " + l + "), A.{u,v,w} a → B.{u,v,w} a")
                .rec("A.rec", REC).rec("B.rec", REC).end()));
        out.add(new Fixture("RES " + shown, e -> {
            e.axiom("G", "", "Type");
            e.axiom("g", "u", "G");
            e.inductive(U, 0).type("T", "G → Type").ctor("T.mk", T + " g.{" + l + "}").rec("T.rec", REC).end();
        }));
        out.add(new Fixture("RESD " + shown, e -> {
            e.axiom("G", "", "Type");
            e.axiom("g0", "", "G");
            e.def("gd", "u", "G", "g0");
            e.inductive(U, 0).type("T", "G → Type").ctor("T.mk", T + " gd.{" + l + "}").rec("T.rec", REC).end();
        }));
        out.add(new Fixture("RECF " + shown, e -> {
            e.axiom("G", "", "Type");
            e.axiom("g0", "", "G");
            e.axiom("g", "u", "G");
            e.inductive(U, 0).type("T", "G → Type").ctor("T.base", T + " g0")
                    .ctor("T.step", T + " g.{" + l + "} → " + T + " g0").rec("T.rec", REC).end();
        }));
        out.add(new Fixture("NEST " + shown, e -> {
            Fixtures.list(e);
            e.inductive(U, 1).type("T", "Sort " + l + " → Type")
                    .ctor("T.mk", "∀ (a : Sort " + l + "), List.{0} (" + T + " a) → " + T + " a")
                    .rec("T.rec", REC).rec("T.rec_1", REC).end();
        }));
        out.add(new Fixture("NESTIDX " + shown, e -> {
            e.axiom("F", "u", "Type");
            e.inductive("u", 1).type("J", "Type → F.{u} → Type")
                    .ctor("J.mk", "∀ (α : Type) (i : F.{u}), α → J.{u} α i").rec("J.rec", "u_1 u").end();
            e.inductive(U, 0).type("T", "Type")
                    .ctor("T.mk", "∀ (i : F.{" + l + "}), J.{" + l + "} " + T + " i → " + T)
                    .rec("T.rec", REC).rec("T.rec_1", REC).end();
        }));
        out.add(new Fixture("NESTIDXD " + shown, e -> {
            e.axiom("G", "", "Type");
            e.def("Fd", "u", "Type", "G");
            e.inductive("u", 1).type("J", "Type → Fd.{u} → Type")
                    .ctor("J.mk", "∀ (α : Type) (i : Fd.{u}), α → J.{u} α i").rec("J.rec", "u_1 u").end();
            e.inductive(U, 0).type("T", "Type")
                    .ctor("T.mk", "∀ (i : Fd.{" + l + "}), J.{" + l + "} " + T + " i → " + T)
                    .rec("T.rec", REC).rec("T.rec_1", REC).end();
        }));
    }

    // ---- one fixture -----------------------------------------------------------------------------------------

    private static final int ACCEPT = 0;
    private static final int REJECT = 1;
    private static final int DECLINE = 2;

    /** Admission of every block of the fixture; the outcome of the first block that is not admitted. */
    private static int admit(Fixture f, boolean referenceKernel, boolean keepDeclared, Ndjson[] export) {
        Env e = new Env(referenceKernel);
        f.build.accept(e);
        e.freeze();
        e.admission.keepDeclaredLevels = keepDeclared;
        Ndjson w = export != null ? new Ndjson(e) : null;
        int outcome = ACCEPT;
        int n = e.decls.unitCount();
        for (int u = 0; u < n; u++) {
            if (e.decls.unitKind(u) != DeclKind.INDUCT) {
                if (w != null) {
                    w.constant(e.decls.unitFirstCid(u));
                }
                continue;
            }
            Env.Block b = null;
            for (Env.Block x : e.blocks) {
                if (x.in.unit == u) {
                    b = x;
                }
            }
            GeneratedBlock g = null;
            if (outcome == ACCEPT) {
                try {
                    g = e.admit(b);
                    if (g.declineReason != null) {
                        outcome = DECLINE;
                    }
                } catch (KernelException x) {
                    outcome = REJECT;
                } catch (ResourceException x) {
                    outcome = DECLINE;
                }
            }
            if (w != null) {
                w.inductive(b, g);
            }
        }
        if (export != null) {
            export[0] = w;
        }
        return outcome;
    }

    @Test
    void theVerdictOfEveryShapeIsTheOfficialOne() throws Exception {
        List<Fixture> fixtures = fixtures();
        ArrayList<String> report = new ArrayList<>();
        ArrayList<String> wrong = new ArrayList<>();
        java.util.TreeMap<String, int[]> table = new java.util.TreeMap<>();
        int respelled = 0;
        for (Fixture f : fixtures) {
            // the export: records derived with the declared spelling kept
            Ndjson[] export = new Ndjson[1];
            int kept = admit(f, true, true, export);
            String text = export[0].text();
            OfficialGolden.Verdict v = OfficialGolden.official(f.name, text);
            if (v == null) {
                continue;
            }
            int mine = admit(f, true, false, null);
            int fake = admit(f, false, false, null);
            int official = v.exit == 0 ? ACCEPT : REJECT;
            String shape = f.name.substring(0, f.name.indexOf(' '));
            int[] row = table.computeIfAbsent(shape, k -> new int[6]);
            row[official == ACCEPT ? 0 : 1]++;
            row[mine == ACCEPT ? 2 : mine == REJECT ? 3 : 4]++;
            if (kept == ACCEPT && official == REJECT) {
                row[5]++; // accepted with the declared spelling kept: finding L-F1
                respelled++;
            }
            String line = f.name + ": official " + (official == ACCEPT ? "accept" : "reject") + ", here "
                    + (mine == ACCEPT ? "accept" : mine == REJECT ? "reject" : "DECLINE") + ", test kernel "
                    + (fake == ACCEPT ? "accept" : fake == REJECT ? "reject" : "DECLINE")
                    + (official == REJECT ? " | " + v.output : "");
            report.add(line);
            if (official == REJECT && !v.kernelRejected()) {
                // the block was admitted officially and the file fell on something else: the fixture is broken
                wrong.add("NOT A KERNEL REJECTION: " + line);
            } else if (mine != official || fake != official) {
                wrong.add(line);
            }
        }
        Path dir = Path.of("target");
        if (Files.isDirectory(dir)) {
            Files.write(dir.resolve("official-level-rebuild-report.txt"), report);
        }
        OfficialGolden.failIfMissing();
        StringBuilder sb = new StringBuilder("official level rebuild: " + report.size() + " fixtures\n");
        sb.append(String.format("  %-9s %9s %9s | %7s %7s %8s | %s%n", "shape", "off.acc", "off.rej", "accept", "reject", "decline",
                "rejected only since L-F1"));
        for (java.util.Map.Entry<String, int[]> en : table.entrySet()) {
            int[] r = en.getValue();
            sb.append(String.format("  %-9s %9d %9d | %7d %7d %8d | %d%n", en.getKey(), r[0], r[1], r[2], r[3], r[4], r[5]));
        }
        System.out.print(sb);
        assertTrue(wrong.isEmpty(), wrong.size() + " verdicts differ from the official checker:\n" + String.join("\n", wrong));
        assertEquals(fixtures.size(), report.size());
        assertTrue(respelled >= 400, "fixtures on which the rebuild decides: " + respelled);
    }
}
