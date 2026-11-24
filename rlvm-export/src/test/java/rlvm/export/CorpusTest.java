package rlvm.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The 230 published arena tests ({@code work/arena-site/tests/{good,bad}}).
 *
 * <p>Parse-level expectations, from docs/research/01 and confirmed with the official
 * {@code leanchecker} 4.36.0: every good file parses; of the 92 bad files, exactly the six
 * "duplicate declaration" tests are rejected by the reader, all others are rejected by the kernel
 * (so they must parse here).
 */
class CorpusTest {
    /** Bad tests that the official reader itself rejects ("Duplicate declaration: ..."). */
    private static final Set<String> READER_REJECTS = Set.of(
            "tutorial/132_dup_defs",
            "tutorial/133_dup_ind_def",
            "tutorial/134_dup_ctor_def",
            "tutorial/135_dup_rec_def",
            "tutorial/138_dup_ctor_rec",
            "tutorial/139_DupConCon");

    private static List<Path> files(String bucket) throws IOException {
        Path dir = T.TESTS.resolve(bucket);
        assumeTrue(Files.isDirectory(dir), "arena tests not present: " + dir);
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.toString().endsWith(".ndjson")).sorted().toList();
        }
    }

    private static String name(String bucket, Path p) {
        String rel = T.TESTS.resolve(bucket).relativize(p).toString().replace('\\', '/');
        return rel.substring(0, rel.length() - ".ndjson".length());
    }

    @Test
    void allPublishedTestsAreReadIdenticallyByAllReaders() throws IOException {
        int count = 0;
        long lines = 0;
        long general = 0;
        for (String bucket : new String[] {"good", "bad"}) {
            for (Path p : files(bucket)) {
                byte[] data = Files.readAllBytes(p);
                T.Run r = T.agree(bucket + "/" + name(bucket, p), data);
                // with a sink that does not look at declaration names, every published test parses
                T.assertOk(r);
                // and the file-based entry points give the same result
                T.same(p + ": mapped file", r, T.run(true, sink -> ExportReader.read(p, sink)), true);
                ExportReader.Options buffered = new ExportReader.Options();
                buffered.io = ExportReader.Io.BUFFERED;
                T.same(p + ": buffered file", r, T.run(true, sink -> ExportReader.read(p, sink, buffered)), true);
                count++;
                lines += r.result.lines;
                general += r.result.generalPathLines;
            }
        }
        assertEquals(230, count);
        assertEquals(1_034_955, lines);
        // the eight hand-formatted bad tests and the rare record kinds go through the general path
        assertTrue(general < lines / 50, general + " general-path lines of " + lines);
    }

    @Test
    void goodTestsLoadIntoTheColumnarStore() throws Exception {
        List<Path> good = files("good");
        assertEquals(138, good.size());
        for (Path p : good) {
            ColumnarStore store = new ColumnarStore(Files.size(p), true, true);
            ExportReader.Result res = ExportReader.read(p, store);
            assertEquals(res.exprs, store.exprCount(), p.toString());
            assertEquals(res.names, store.nameCount(), p.toString());
            assertEquals(res.levels, store.levelCount(), p.toString());
            assertTrue(store.metaWellFormed(), p.toString());
            assertEquals("3.1.0", store.formatVersion(), p.toString());
            StoreChecks.checkInvariants(store);
        }
    }

    @Test
    void badTestsAreRejectedByTheReaderExactlyWhereTheOfficialReaderRejects() throws Exception {
        List<Path> bad = files("bad");
        assertEquals(92, bad.size());
        Set<String> rejected = new TreeSet<>();
        for (Path p : bad) {
            String name = name("bad", p);
            try {
                ColumnarStore store = new ColumnarStore();
                ExportReader.read(p, store);
                StoreChecks.checkInvariants(store);
            } catch (ExportException e) {
                assertEquals(ExportException.Kind.MALFORMED, e.kind(), name + ": " + e.getMessage());
                assertTrue(e.reason().startsWith("Duplicate declaration: "), name + ": " + e.getMessage());
                assertTrue(e.line() > 1, name);
                rejected.add(name);
            }
        }
        assertEquals(new TreeSet<>(READER_REJECTS), rejected);
    }

    @Test
    void duplicateDeclarationMessagesNameTheConstant() throws Exception {
        Path p = T.TESTS.resolve("bad/tutorial/134_dup_ctor_def.ndjson");
        assumeTrue(Files.exists(p));
        try {
            ExportReader.read(p, new ColumnarStore());
            fail("accepted");
        } catch (ExportException e) {
            assertEquals("Duplicate declaration: dup_ctor_def.mk", e.reason());
            assertTrue(e.getMessage().startsWith("malformed export, line "), e.getMessage());
        }
    }

    @Test
    void theOfficialReaderRejectsTheSameBadTests() throws Exception {
        assumeTrue(Files.isExecutable(T.LEANCHECKER), "official leanchecker 4.36.0 not installed");
        List<String> readerRejects = new ArrayList<>();
        for (Path p : files("bad")) {
            Process proc = new ProcessBuilder(T.LEANCHECKER.toString(), "--from-export", p.toString())
                    .redirectErrorStream(true)
                    .start();
            String out = new String(proc.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(1, proc.waitFor(), p + ": " + out);
            if (!out.contains("Lean default kernel rejects")) {
                assertTrue(out.contains("uncaught exception: Duplicate declaration"), p + ": " + out);
                readerRejects.add(name("bad", p));
            }
        }
        assertEquals(new TreeSet<>(READER_REJECTS), new TreeSet<>(readerRejects));
        assertFalse(readerRejects.isEmpty());
    }
}
