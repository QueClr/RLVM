package rlvm.inductive;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import rlvm.api.KernelException;
import rlvm.api.ResourceException;
import rlvm.load.MalformedExportException;
import rlvm.load.UnsupportedExportException;

/**
 * The arena's published tests through loader + environment pass (inductive blocks and quot only):
 * every good test must pass the environment pass; the bad tests whose defect is in an inductive
 * block, a constructor / recursor record or a quot record ({@code docs/research/03} section 10) must
 * be rejected there (or already by the loader, for duplicate names).
 */
class ArenaInductiveTest {

    /** Bad tests that inductive admission, record comparison or quot admission must reject. */
    private static final Set<String> MUST_REJECT = new HashSet<>(Arrays.asList(
            "bugs/ctor-num-fields", "bugs/rec-k-lie", "bugs/nat-rec-k-lie", "bugs/nat-rec-rules", "bugs/unit-rec-rule",
            "bugs/rec-rule-wrong-arg", "bugs/nested-rec-rule-nfields", "bugs/rec-rule-proof-irrel",
            "bugs/rec-rule-proof-irrel-nested", "bugs/rec-type-proof-irrel", "bugs/large-elim-param",
            "bugs/large-elim-prop-bool", "bugs/orphan-rec", "bugs/nested-name-capture", "bugs/nested-unused-param",
            "bugs/rec-missing-ih", "bugs/quot-bad-type",
            "other/extra-rec", "other/orphan-ctor", "other/inductive-undeclared-universe",
            "tutorial/046_inductBadNonSort", "tutorial/047_inductBadNonSort2", "tutorial/048_inductLevelParam",
            "tutorial/049_inductTooFewParams", "tutorial/050_inductWrongCtorParams",
            "tutorial/051_inductWrongCtorResParams", "tutorial/052_inductWrongCtorResLevel", "tutorial/053_inductInIndex",
            "tutorial/054_indNeg", "tutorial/056_reduceCtorType.mk", "tutorial/057_indNegReducible",
            "tutorial/061_typeWithTooHighTypeField.mk", "tutorial/073_BogusRecursor", "tutorial/117_reflOccLeft",
            "tutorial/118_reflOccInIndex", "tutorial/136_misnamed_rec_user"));

    private static String outcome(Path file) {
        RealEnv e;
        try {
            e = RealEnv.load(file);
        } catch (MalformedExportException x) {
            return "LOAD_REJECT " + x.getMessage();
        } catch (UnsupportedExportException x) {
            return "LOAD_DECLINE " + x.getMessage();
        } catch (IOException x) {
            return "IO " + x;
        }
        try {
            e.pass();
        } catch (KernelException x) {
            return "ENV_REJECT " + x.reason() + " at " + e.unitName(e.failedUnit);
        } catch (ResourceException x) {
            return "ENV_DECLINE " + x.reason() + " at " + e.unitName(e.failedUnit);
        } catch (RuntimeException | StackOverflowError x) {
            return "ENV_ERROR " + x + " at " + e.unitName(e.failedUnit);
        }
        return "ENV_OK " + e.inductiveUnits + " blocks, " + e.quotUnits + " quot";
    }

    @Test
    void publishedTests() throws Exception {
        Path tests = Data.arenaTests();
        assumeTrue(tests != null && Files.isDirectory(tests), "arena tests not present");
        List<Path> files;
        try (Stream<Path> s = Files.walk(tests)) {
            files = s.filter(p -> p.toString().endsWith(".ndjson")).sorted().collect(Collectors.toList());
        }
        ArrayList<String> report = new ArrayList<>();
        ArrayList<String> wrong = new ArrayList<>();
        int good = 0;
        int bad = 0;
        int rejected = 0;
        for (Path f : files) {
            Path rel = tests.relativize(f);
            boolean isGood = rel.getName(0).toString().equals("good");
            String id = rel.subpath(1, rel.getNameCount()).toString().replace(".ndjson", "");
            if (id.startsWith("perf/") && Files.size(f) > (64L << 20)) {
                continue;
            }
            String out = outcome(f);
            report.add((isGood ? "good " : "bad  ") + id + "  ->  " + out);
            if (isGood) {
                good++;
                if (!out.startsWith("ENV_OK")) {
                    wrong.add("good test not passed: " + id + ": " + out);
                }
            } else {
                bad++;
                boolean rej = out.startsWith("ENV_REJECT") || out.startsWith("LOAD_REJECT");
                if (rej) {
                    rejected++;
                }
                if (MUST_REJECT.contains(id) && !rej) {
                    wrong.add("bad test not rejected: " + id + ": " + out);
                }
                if (out.startsWith("ENV_ERROR")) {
                    wrong.add("internal error: " + id + ": " + out);
                }
            }
        }
        Path dir = Path.of("target");
        if (Files.isDirectory(dir)) {
            Files.write(dir.resolve("arena-inductive-report.txt"), report);
        }
        System.out.printf("arena tests: %d good passed the environment pass, %d of %d bad rejected by loader or environment pass%n",
                good - (int) wrong.stream().filter(w -> w.startsWith("good")).count(), rejected, bad);
        assertTrue(wrong.isEmpty(), String.join("\n", wrong) + "\n--- all outcomes ---\n" + String.join("\n", report));
    }
}
