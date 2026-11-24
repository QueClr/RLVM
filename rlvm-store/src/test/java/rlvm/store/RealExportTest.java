package rlvm.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import rlvm.export.ExportException;

/**
 * The store on real lean4export files (read with rlvm-export, levels in rlvm-level): every shared
 * node and every record of every decoded proof block must equal the export's record, and every
 * declaration root of a good file must pass the structural checks from its summaries alone.
 *
 * <p>Files are taken from {@code work/} of the project; a test is skipped when they are absent.
 * {@code init.ndjson} (348 MB) runs only with {@code -Drlvm.store.heavy=true}.
 */
class RealExportTest {

    private static Path root() {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("work/arena-site/tests"))) {
            p = p.getParent();
        }
        return p;
    }

    private static void check(Path file, boolean blocks) throws Exception {
        RealExport r = RealExport.load(file, blocks);
        RealExport.Report rep = r.verify(1);
        assertNull(rep.firstError, file + ": " + rep);
        assertEquals(0, rep.rootProblems, file + ": " + rep);
        assertEquals(rep.exprRecords + rep.promoted, rep.sharedNodes + rep.blockRecords, file + ": every record is stored once, promoted ones twice");
        if (!blocks) {
            assertEquals(0, rep.blockRecords);
        }
    }

    @Test
    void initPreludeWithAndWithoutProofBlocks() throws Exception {
        Path root = root();
        Assumptions.assumeTrue(root != null);
        Path file = root.resolve("work/arena-site/tests/good/init-prelude.ndjson");
        Assumptions.assumeTrue(Files.exists(file));
        check(file, true);
        check(file, false);
    }

    @Test
    void everyGoodArenaTest() throws Exception {
        Path root = root();
        Assumptions.assumeTrue(root != null);
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root.resolve("work/arena-site/tests/good"))) {
            s.filter(p -> p.toString().endsWith(".ndjson")).forEach(files::add);
        }
        Assumptions.assumeTrue(!files.isEmpty());
        int checked = 0;
        for (Path f : files) {
            if (Files.size(f) > (64L << 20)) {
                continue;
            }
            check(f, true);
            checked++;
        }
        assertTrue(checked > 100, "good arena tests checked: " + checked);
    }

    @Test
    void badArenaTestsStillRoundTrip() throws Exception {
        Path root = root();
        Assumptions.assumeTrue(root != null);
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root.resolve("work/arena-site/tests/bad"))) {
            s.filter(p -> p.toString().endsWith(".ndjson")).forEach(files::add);
        }
        Assumptions.assumeTrue(!files.isEmpty());
        int loaded = 0;
        int refused = 0;
        for (Path f : files) {
            RealExport r;
            try {
                r = RealExport.load(f, true);
            } catch (ExportException | IOException e) {
                refused++;
                continue;
            }
            // a bad file may have open or unbound roots; the store must still hold exactly what the file says
            RealExport.Report rep = r.verify(1);
            assertNull(rep.firstError, f + ": " + rep);
            loaded++;
        }
        assertTrue(loaded > 50, "bad arena tests loaded: " + loaded + ", refused by the reader: " + refused);
    }

    @Test
    void initWithProofBlocks() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("rlvm.store.heavy"));
        Path root = root();
        Assumptions.assumeTrue(root != null);
        Path file = root.resolve("work/exports/init.ndjson");
        Assumptions.assumeTrue(Files.exists(file));
        RealExport r = RealExport.load(file, true);
        RealExport.Report rep = r.verify(1);
        System.out.println("init.ndjson: " + rep);
        assertNull(rep.firstError, rep.toString());
        assertEquals(0, rep.rootProblems, rep.toString());
        assertTrue(rep.blockBytes <= 6L * rep.blockRecords, "at most 6 bytes per block record");
    }
}
