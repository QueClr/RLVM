package rlvm.ref;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import rlvm.api.EngineConfig;

/**
 * The answers of the official kernel (leanchecker v4.36.0) to universe-level questions, replayed
 * through the engine's {@code is_def_eq}: the golden set of {@code rlvm-level}
 * ({@code official-golden.tsv}, made by {@code bench/modules/rlvm-level/gen_golden.py} with one tiny
 * export per question). With the default settings the engine must give the official answer to every
 * one of them, in each place where it compares levels:
 *
 * <ul>
 *   <li>{@code E l1 l2 r}: two sorts ({@code Sort l1 =?= Sort l2}), the level lists of two references
 *       to one constant, as the whole terms ({@code F.{l1} =?= F.{l2}}: the constant case of
 *       {@code is_def_eq_core}) and as the heads of two applications of one regular definition
 *       ({@code G.{l1} a =?= G.{l2} a} with {@code G.{u} x := F.{u}}: lazy delta reduction with equal
 *       heads, then the unfolded bodies);
 *   <li>{@code I L A B C M r}: the type of {@code c.{A,B,C}} for {@code c.{u,v,w} : Sort L} against
 *       {@code Sort M}: universe instantiation of an imported constant followed by the comparison.
 * </ul>
 *
 * With {@code levels=complete} the engine answers a superset: never "different" where the official
 * kernel says "equal".
 */
class LevelsGoldenTest {

    private static final Path GOLDEN = Paths.get("..", "rlvm-level", "src", "test", "resources", "rlvm", "level",
            "official-golden.tsv");

    private static final List<String[]> EQUIV = new ArrayList<>();
    private static final List<String[]> INST = new ArrayList<>();
    private static Fixture fx;

    /** {@code 0, 1, u, (s X), (m X Y), (i X Y)} of the golden set in the fixture's notation. */
    private static String level(String golden) {
        return golden.replace("(s ", "(succ ").replace("(m ", "(max ").replace("(i ", "(imax ");
    }

    @BeforeAll
    static void readTheGoldenSet() throws IOException {
        if (!Files.exists(GOLDEN)) {
            return;
        }
        for (String line : Files.readAllLines(GOLDEN)) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] c = line.split("\t");
            if (c[0].equals("E")) {
                EQUIV.add(new String[] {level(c[1]), level(c[2]), c[3]});
            } else if (c[0].equals("I")) {
                INST.add(new String[] {level(c[1]), level(c[2]), level(c[3]), level(c[4]), level(c[5]), c[6]});
            }
        }
        fx = Fixture.standard(f -> {
            f.axiom("F", "u", "Type");
            f.axiom("A", "", "Type");
            f.axiom("a", "", "A");
            f.def("G", "u", "(-> A Type)", "(fun (x : A) (@ F u))", Fixture.regular(1));
            for (int i = 0; i < INST.size(); i++) {
                f.axiom("c" + i, "u v w", "(sort " + INST.get(i)[0] + ")");
            }
        });
    }

    private static RefKernel kernel(boolean official) {
        return new RefKernel(fx.ctx, EngineConfig.defaults(), RefOptions.parse(null).withOfficialLevels(official));
    }

    @Test
    void sortsAndLevelListsAreComparedAsTheOfficialKernelAnswered() {
        assumeTrue(fx != null, "the golden set of rlvm-level is not present");
        assertTrue(EQUIV.size() > 4000, "is_equivalent questions: " + EQUIV.size());
        RefKernel byDefault = new RefKernel(fx.ctx, EngineConfig.defaults());
        RefKernel complete = kernel(false);
        int lp = fx.names("u v w");
        int equal = 0;
        int onlyComplete = 0;
        for (String[] q : EQUIV) {
            boolean expected = q[2].equals("1");
            String[][] forms = {
                {"(sort " + q[0] + ")", "(sort " + q[1] + ")"},
                {"(@ F " + q[0] + ")", "(@ F " + q[1] + ")"},
            };
            for (String[] f : forms) {
                byDefault.begin(Integer.MAX_VALUE, lp);
                int t = fx.t(f[0]);
                int s = fx.t(f[1]);
                assertEquals(expected, byDefault.isDefEq(t, s), () -> f[0] + " =?= " + f[1]);
                byDefault.begin(Integer.MAX_VALUE, lp);
                assertEquals(expected, byDefault.isDefEq(s, t), () -> f[1] + " =?= " + f[0]);
                complete.begin(Integer.MAX_VALUE, lp);
                boolean c = complete.isDefEq(t, s);
                assertTrue(c || !expected, () -> "complete says different, official says equal: " + f[0] + ", " + f[1]);
                if (c && !expected) {
                    onlyComplete++;
                }
            }
            // the same regular definition on both sides: the level lists are compared before anything is
            // unfolded; when they differ both sides are unfolded and F.{l1} =?= F.{l2} asks again
            byDefault.begin(Integer.MAX_VALUE, lp);
            assertEquals(expected, byDefault.isDefEq(fx.t("((@ G " + q[0] + ") a)"), fx.t("((@ G " + q[1] + ") a)")),
                    () -> "G." + q[0] + " a =?= G." + q[1] + " a");
            if (expected) {
                equal++;
            }
        }
        assertTrue(equal > 1000, "questions the official kernel answers with equal: " + equal);
        assertTrue(onlyComplete > 1000, "comparisons only the complete relation accepts: " + onlyComplete);
    }

    @Test
    void instantiatedConstantTypesAreComparedAsTheOfficialKernelAnswered() {
        assumeTrue(fx != null, "the golden set of rlvm-level is not present");
        assertTrue(INST.size() > 2000, "instantiate questions: " + INST.size());
        RefKernel byDefault = new RefKernel(fx.ctx, EngineConfig.defaults());
        RefKernel complete = kernel(false);
        int lp = fx.names("u v w");
        int equal = 0;
        for (int i = 0; i < INST.size(); i++) {
            String[] q = INST.get(i);
            boolean expected = q[5].equals("1");
            String c = "(@ c" + i + " " + q[1] + " " + q[2] + " " + q[3] + ")";
            String m = "(sort " + q[4] + ")";
            byDefault.begin(Integer.MAX_VALUE, lp);
            assertEquals(expected, byDefault.isDefEq(byDefault.infer(fx.t(c)), fx.t(m)), () -> "type of " + c + " =?= " + m);
            complete.begin(Integer.MAX_VALUE, lp);
            boolean viaComplete = complete.isDefEq(complete.infer(fx.t(c)), fx.t(m));
            assertTrue(viaComplete || !expected, () -> "complete says different, official says equal: " + c + ", " + m);
            if (expected) {
                equal++;
            }
        }
        assertTrue(equal > 100, "questions the official kernel answers with equal: " + equal);
    }
}
