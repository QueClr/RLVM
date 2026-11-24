package rlvm.load;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import rlvm.export.ColumnarStore;
import rlvm.export.ExportException;
import rlvm.export.ExportReader;

/**
 * Every published arena test through the loader (into the in-memory target): exact record counts and
 * the structure of everything that was stored, against the reader module's reference sink; referrer
 * flags against a naive recount. Files with a duplicate declaration name must be rejected, everything
 * else must load.
 */
class ArenaFilesTest {
    static final Path ROOT = TestFiles.root();
    static final Path TESTS = ROOT.resolve("work/arena-site/tests");

    static List<Path> files() throws IOException {
        Assumptions.assumeTrue(Files.isDirectory(TESTS), "arena tests not present");
        try (Stream<Path> s = Files.walk(TESTS)) {
            return s.filter(p -> p.toString().endsWith(".ndjson")).sorted().toList();
        }
    }

    @Test
    void all230FilesLoadWithExactCountsAndStructure() throws IOException {
        List<Path> files = files();
        assertEquals(230, files.size(), "published tests");
        TreeSet<String> rejected = new TreeSet<>();
        long records = 0;
        long blockNodes = 0;
        long promotions = 0;
        for (Path file : files) {
            String rel = TESTS.relativize(file).toString();
            byte[] data = Files.readAllBytes(file);
            Oracle o = new Oracle(data);
            try {
                o.load();
            } catch (MalformedExportException e) {
                // must be a duplicate declaration, and the reference sink must reject the file too
                assertTrue(e.getMessage().startsWith("Duplicate declaration"), rel + ": " + e.getMessage());
                assertTrue(e.line() > 1, rel);
                try {
                    ExportReader.read(data, new ColumnarStore());
                    fail(rel + ": the reference sink accepts what the loader rejected");
                } catch (ExportException expected) {
                    assertEquals(ExportException.Kind.MALFORMED, expected.kind(), rel);
                    assertEquals(expected.line(), e.line(), rel + ": line of the duplicate");
                }
                assertTrue(rel.startsWith("bad/"), rel + " is a good test but was rejected at load");
                rejected.add(rel);
                continue;
            } catch (UnsupportedExportException e) {
                fail(rel + " declined at load: " + e.getMessage());
                return;
            }
            try {
                o.verify();
                o.verifySharingFlags();
            } catch (AssertionError e) {
                throw new AssertionError(rel + ": " + e.getMessage(), e);
            }
            LoadReport r = o.report;
            assertTrue(r.complete, rel);
            assertEquals(data.length, r.inputBytes, rel);
            assertEquals(lineCount(data), r.lines, rel + ": lines");
            assertEquals(r.lines, 1 + r.nameRecords + r.levelRecords + r.exprRecords + r.declRecords,
                    rel + ": one record per line");
            assertFalse(r.formatVersion == null, rel);
            records += r.exprRecords;
            blockNodes += r.blockNodes;
            promotions += r.promotions;
        }
        // Exactly the files on which `leanchecker --from-export` (v4.36.0) stops with "uncaught exception:
        // Duplicate declaration" (checked by hand; tutorial 136 and 137 get past the official reader and are
        // rejected by the kernel, so they must load here).
        List<String> expected = List.of("bad/tutorial/132_dup_defs.ndjson", "bad/tutorial/133_dup_ind_def.ndjson",
                "bad/tutorial/134_dup_ctor_def.ndjson", "bad/tutorial/135_dup_rec_def.ndjson",
                "bad/tutorial/138_dup_ctor_rec.ndjson", "bad/tutorial/139_DupConCon.ndjson");
        assertEquals(expected, new ArrayList<>(rejected), "files rejected at load (duplicate names)");
        assertTrue(records > 500_000 && blockNodes > 0 && promotions > 0,
                "the corpus exercises blocks and promotion: " + records + " records, " + blockNodes + " block nodes, "
                        + promotions + " promotions");
    }

    @Test
    void all230FilesWithoutProofBlocks() throws IOException {
        for (Path file : files()) {
            byte[] data = Files.readAllBytes(file);
            Oracle o = new Oracle(data);
            try {
                o.load(LoadOptions.batch(data.length).withProofBlocks(false));
            } catch (MalformedExportException e) {
                continue;
            }
            try {
                o.verify();
                o.verifySharingFlags();
            } catch (AssertionError e) {
                throw new AssertionError(TESTS.relativize(file) + ": " + e.getMessage(), e);
            }
            // only records that no root of their segment reaches are in (ownerless) blocks
            assertEquals(0, o.report.blockValues);
        }
    }

    static long lineCount(byte[] data) {
        long n = 0;
        for (byte b : data) {
            if (b == '\n') {
                n++;
            }
        }
        if (data.length > 0 && data[data.length - 1] != '\n') {
            n++;
        }
        return n;
    }
}
