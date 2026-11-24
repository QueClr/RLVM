package rlvm.main;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import rlvm.cli.LauncherAccess;

/**
 * The published arena tests through the real launcher and the real pipeline, in-process:
 * {@code rlvm -j 1 -X engine=reference -X load=batch FILE} must end with status 0 for a good test
 * and 1 for a bad one.
 *
 * <p>Opt-in ({@code -Drlvm.e2e=tutorial} or {@code -Drlvm.e2e=all}): the outcome depends on every
 * other module, so it is the steward's gate (milestones M1 / M2), not a unit test of this module.
 * The table for owners is written by {@code scripts/slice.sh}; this test is the same run inside
 * one JVM, which additionally shows that consecutive runs do not disturb each other.
 */
class SliceE2ETest {

    private static Path testsRoot() {
        Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("work/arena-site/tests"))) {
            p = p.getParent();
        }
        return p == null ? null : p.resolve("work/arena-site/tests");
    }

    private static List<Path> files(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(f -> f.toString().endsWith(".ndjson")).sorted().toList();
        }
    }

    @Test
    void publishedTestsEndToEnd() throws Exception {
        String which = System.getProperty("rlvm.e2e", "");
        assumeTrue(which.equals("tutorial") || which.equals("all"), "set -Drlvm.e2e=tutorial|all to run");
        Path root = testsRoot();
        assumeTrue(root != null, "work/arena-site/tests not found");
        String sub = which.equals("tutorial") ? "tutorial" : "";
        List<String> failures = new ArrayList<>();
        int n = 0;
        for (String bucket : new String[] {"good", "bad"}) {
            int want = bucket.equals("good") ? 0 : 1;
            for (Path f : files(root.resolve(bucket).resolve(sub))) {
                n++;
                LauncherAccess.Result r = LauncherAccess.run(new HashMap<>(), "-j", "1", "-X", "engine=reference", "-X",
                        "load=batch", f.toString());
                if (r.status != want) {
                    String line = r.err.lines().filter(l -> l.startsWith("rlvm:")).findFirst().orElse("");
                    failures.add(root.relativize(f) + ": status " + r.status + ", expected " + want + " | " + line);
                }
            }
        }
        assertTrue(n > 0, "no test files");
        assertEquals(List.of(), failures, failures.size() + " of " + n + " tests failed");
    }
}
