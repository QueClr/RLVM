package rlvm.ref.corpus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import rlvm.api.Outcome;
import rlvm.api.Reason;
import rlvm.ref.RefEngine;
import rlvm.ref.RefOptions;

/**
 * The reference kernel on the published arena tests at kernel level ({@link CorpusRunner}: inductive
 * records are trusted, no admission). Every good test must be accepted; every bad test must be
 * rejected, except those whose fault is invisible to a type checker that trusts inductive records
 * and performs no structural checks (they are listed here and rejected by admission in the real
 * pipeline). The perf group runs only with {@code -Drlvm.ref.corpus.perf=true} (about two minutes).
 */
class CorpusTest {

    private static final Path TESTS = Paths.get("..", "work", "arena-site", "tests");
    /** The arena's tests with outcome "either" (not in the published tarball; collected by bench/collect-exports.py). */
    private static final Path EITHER = Paths.get("..", "work", "exports", "all", "either", "corner-cases");

    /** Bad tests the kernel alone cannot see: forged or ill-formed inductive / quot records, duplicate universe names. */
    private static final Set<String> NOT_A_KERNEL_MATTER = new HashSet<>(Arrays.asList(
            "bad/bugs/ctor-num-fields", "bad/bugs/large-elim-param", "bad/bugs/large-elim-prop-bool",
            "bad/bugs/nat-rec-rules", "bad/bugs/nested-rec-rule-nfields", "bad/bugs/quot-bad-type",
            "bad/bugs/rec-k-lie", "bad/bugs/rec-missing-ih", "bad/bugs/rec-rule-proof-irrel-nested",
            "bad/bugs/rec-rule-proof-irrel", "bad/bugs/rec-rule-wrong-arg", "bad/bugs/rec-type-proof-irrel",
            "bad/other/inductive-undeclared-universe", "bad/tutorial/019_tut06_bad01",
            "bad/tutorial/046_inductBadNonSort", "bad/tutorial/047_inductBadNonSort2",
            "bad/tutorial/048_inductLevelParam", "bad/tutorial/049_inductTooFewParams",
            "bad/tutorial/136_misnamed_rec_user", "bad/tutorial/137_dup_rec_def2"));

    private static String name(Path file) {
        String s = TESTS.relativize(file).toString().replace('\\', '/');
        return s.substring(0, s.length() - ".ndjson".length());
    }

    private void run(String... groups) throws IOException {
        assumeTrue(Files.isDirectory(TESTS), "arena tests not present");
        List<String> dirs = new ArrayList<>();
        for (String g : groups) {
            for (String side : new String[] {"good", "bad"}) {
                Path p = TESTS.resolve(side).resolve(g);
                if (Files.exists(p)) {
                    dirs.add(p.toString());
                }
            }
        }
        List<Path> files = CorpusRunner.expand(dirs);
        assertTrue(!files.isEmpty());
        List<String> problems = new ArrayList<>();
        for (Path f : files) {
            String n = name(f);
            CorpusRunner.Result r = CorpusRunner.run(f, false, false);
            int expected = n.startsWith("good/") || NOT_A_KERNEL_MATTER.contains(n) ? Outcome.ACCEPT : Outcome.REJECT;
            if (r.verdict != expected) {
                problems.add(n + ": expected " + Outcome.name(expected) + ", got " + Outcome.name(r.verdict) + " "
                        + r.message);
            }
        }
        assertEquals(List.of(), problems);
    }

    @Test
    void tutorial() throws IOException {
        run("tutorial");
    }

    @Test
    void bugsOtherCornerCases() throws IOException {
        run("bugs", "other", "corner-cases");
    }

    /**
     * The two arena corner cases on which the official level comparison and the complete one differ
     * (outcome "either"; the official checker rejects both): rejected by default, accepted with the
     * opt-in {@code levels=complete}. Neither file declares an inductive type, so the kernel-level
     * verdict is the pipeline's.
     */
    @Test
    void imaxCornerCasesFollowTheOfficialKernelByDefault() {
        for (String n : new String[] {"imax-right-successor", "imax-nested-absorption"}) {
            Path f = EITHER.resolve(n + ".ndjson");
            assumeTrue(Files.exists(f), "the arena's 'either' tests are not present");
            CorpusRunner.Result byDefault = CorpusRunner.run(f, true, false);
            assertEquals(Outcome.REJECT, byDefault.verdict, n + " by default: " + byDefault.message);
            assertEquals(Reason.DECL_TYPE_MISMATCH, byDefault.reason, n);
            CorpusRunner.Result official = CorpusRunner.run(f, true, false,
                    RefEngine.factory(RefOptions.parse(Map.of("levels", "official"))));
            assertEquals(Outcome.REJECT, official.verdict, n + " with levels=official");
            assertEquals(byDefault.failures, official.failures, n);
            CorpusRunner.Result complete = CorpusRunner.run(f, true, false,
                    RefEngine.factory(RefOptions.parse(Map.of("levels", "complete"))));
            assertEquals(Outcome.ACCEPT, complete.verdict, n + " with levels=complete: " + complete.message);
            assertEquals(0, complete.failures, n);
        }
    }

    @Test
    void initPrelude() {
        Path f = TESTS.resolve("good").resolve("init-prelude.ndjson");
        assumeTrue(Files.exists(f), "arena tests not present");
        CorpusRunner.Result r = CorpusRunner.run(f, false, false);
        assertEquals(Outcome.ACCEPT, r.verdict, r.message);
        assertEquals(1710, r.checked);
    }

    @Test
    void perf() throws IOException {
        assumeTrue(Boolean.getBoolean("rlvm.ref.corpus.perf"), "enable with -Drlvm.ref.corpus.perf=true");
        run("perf");
    }
}
