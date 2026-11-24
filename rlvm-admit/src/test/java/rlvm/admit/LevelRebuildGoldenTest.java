package rlvm.admit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import rlvm.api.Outcome;

/**
 * Finding L-F1 end to end (real loader, store, environment pass with the real {@code rlvm-inductive},
 * reference kernel): generated inductive declarations whose types mention a universe level that the
 * official kernel respells when it refers to the block's own constants ({@code imax u 1} becomes
 * {@code max u 1}; {@code rlvm.inductive.InductiveAdmission}, "the official rebuild of levels"). The
 * verdict of every file must be the one of the official checker ({@code leanchecker --from-export},
 * Lean v4.36.0), never a decline.
 *
 * <p>The test resources hold a sample of 123 of the 2,200 files of the finding with the official
 * answers (53 of them were accepted by RLVM before the fix and are rejected officially); the opt-in
 * test reads all 2,200 from {@code work/prof/L} ({@code -Drlvm.admit.levelsFull=true}).
 */
class LevelRebuildGoldenTest {

    private static final class Tally {
        int files;
        int accept;
        int reject;
        final List<String> wrong = new ArrayList<>();
    }

    /** Runs every file of {@code official} (rows: relative path, accept|reject, ...) below {@code root}. */
    private static Tally run(Path root, Path official) throws IOException {
        Tally t = new Tally();
        for (String line : Files.readAllLines(official, StandardCharsets.UTF_8)) {
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            String[] c = line.split("\t");
            int want = c[1].equals("accept") ? Outcome.ACCEPT : Outcome.REJECT;
            assertTrue(want == Outcome.ACCEPT || c[1].equals("reject"), line);
            ArenaPipelineTest.Result r = ArenaPipelineTest.run(root.resolve(c[0]));
            t.files++;
            if (r.outcome == Outcome.ACCEPT) {
                t.accept++;
            } else if (r.outcome == Outcome.REJECT) {
                t.reject++;
            }
            if (r.outcome != want) {
                t.wrong.add(c[0] + ": official " + c[1] + ", here " + Outcome.name(r.outcome) + " at unit " + r.unit + ": "
                        + r.message);
            }
        }
        return t;
    }

    @Test
    void theSampleGetsTheOfficialVerdicts() throws IOException, URISyntaxException {
        java.net.URL url = LevelRebuildGoldenTest.class.getResource("levels-golden/official.tsv");
        assertNotNull(url, "levels-golden/official.tsv missing from the test resources");
        Path official = Path.of(url.toURI());
        Tally t = run(official.getParent(), official);
        System.out.println("level rebuild golden (sample): " + t.files + " files, " + t.accept + " accepted, " + t.reject
                + " rejected, " + t.wrong.size() + " not the official verdict");
        assertTrue(t.wrong.isEmpty(), String.join("\n", t.wrong));
        assertTrue(t.files >= 120 && t.reject >= 60 && t.accept >= 50, t.files + " files, " + t.reject + " rejected");
    }

    @Test
    void allFilesOfTheFindingGetTheOfficialVerdicts() throws IOException {
        assumeTrue(Boolean.getBoolean("rlvm.admit.levelsFull"), "all 2,200 files only with -Drlvm.admit.levelsFull=true");
        Path base = Path.of("..", "work", "prof", "L").toAbsolutePath().normalize();
        assumeTrue(Files.isDirectory(base), "work/prof/L is not available");
        int files = 0;
        List<String> wrong = new ArrayList<>();
        for (String corpus : new String[] {"levels-r", "levels-n"}) {
            Tally t = run(base.resolve(corpus), base.resolve(corpus).resolve("official.tsv"));
            System.out.println("level rebuild golden (" + corpus + "): " + t.files + " files, " + t.accept + " accepted, "
                    + t.reject + " rejected, " + t.wrong.size() + " not the official verdict");
            files += t.files;
            wrong.addAll(t.wrong);
        }
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
        assertTrue(files >= 2200, files + " files");
    }
}
