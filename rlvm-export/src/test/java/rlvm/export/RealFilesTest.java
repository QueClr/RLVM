package rlvm.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The real corpora {@code init.ndjson} (348 MB) and {@code std.ndjson} (584 MB): record counts as
 * published by the arena, and differential runs fast path / general path / parallel / buffered /
 * reference reader.
 */
class RealFilesTest {
    private static T.Run hashRun(T.Reader reader) {
        T.Run r = T.run(false, reader);
        T.assertOk(r);
        return r;
    }

    private static void differential(Path file, long lines, long names, long levels, long exprs, long constants)
            throws IOException, ExportException {
        differential(file, lines, names, levels, exprs, constants, true);
    }

    private static void differential(Path file, long lines, long names, long levels, long exprs, long constants,
            boolean store) throws IOException, ExportException {
        assumeTrue(Files.exists(file), "not present: " + file);
        T.Run fast = hashRun(sink -> ExportReader.read(file, sink));
        assertEquals(lines, fast.result.lines);
        assertEquals(Files.size(file), fast.result.bytes);
        assertEquals(names + 1, fast.result.names);
        assertEquals(levels + 1, fast.result.levels);
        assertEquals(exprs, fast.result.exprs);
        assertEquals(names, fast.result.maxNameId);
        assertEquals(levels, fast.result.maxLevelId);
        assertEquals(exprs - 1, fast.result.maxExprId);
        assertTrue(fast.result.namesDense && fast.result.levelsDense && fast.result.exprsDense);
        assertTrue(fast.result.tableFree);
        assertEquals(ExportReader.Io.MAPPED, fast.result.io);
        // 99.9 % of the lines of a real export are handled by the templates
        assertTrue(fast.result.generalPathLines < lines / 1000, "general path lines " + fast.result.generalPathLines);

        T.Run general = hashRun(sink -> ExportReader.read(file, sink, T.options(1, 1 << 20, true)));
        T.same("general path only", fast, general, true);
        assertEquals(lines - 1, general.result.generalPathLines);

        T.Run parallel = hashRun(sink -> ExportReader.read(file, sink, T.options(4, 1 << 20, false)));
        T.same("parallel, 4 scanner threads", fast, parallel, true);
        assertEquals(4, parallel.result.threads);
        assertEquals(fast.result.generalPathLines, parallel.result.generalPathLines);

        ExportReader.Options buffered = T.options(1, 300_000, false);
        buffered.io = ExportReader.Io.BUFFERED;
        T.Run buf = hashRun(sink -> ExportReader.read(file, sink, buffered));
        T.same("buffered", fast, buf, true);
        assertEquals(ExportReader.Io.BUFFERED, buf.result.io);

        if (!store) {
            return;
        }
        ColumnarStore s = new ColumnarStore(Files.size(file), true, false);
        ExportReader.read(file, s);
        assertEquals(exprs, s.exprCount());
        assertEquals(names + 1, s.nameCount());
        assertEquals(levels + 1, s.levelCount());
        assertEquals(constants, s.constCount());
        StoreChecks.checkInvariants(s);
    }

    /**
     * The 6.3 GB mathlib export (the arena's ranking test). Takes about half a minute; run with
     * {@code -Drlvm.export.big=true} when {@code work/exports/mathlib.ndjson} exists.
     */
    @Test
    void mathlibExport() throws IOException, ExportException {
        assumeTrue(Boolean.getBoolean("rlvm.export.big"), "enable with -Drlvm.export.big=true");
        differential(T.ROOT.resolve("work/exports/mathlib.ndjson"), 112_081_718, 4_273_914, 48_246, 107_037_712, 739_167, false);
    }

    @Test
    void initExport() throws IOException, ExportException {
        differential(T.INIT, 6_495_296, 299_085, 571, 6_137_092, 59_979);
    }

    @Test
    void stdExport() throws IOException, ExportException {
        differential(T.STD, 10_611_507, 580_893, 953, 9_928_514, 103_885);
    }

    /** The first {@code limit} bytes of the file, cut after a line end. */
    private static byte[] prefix(Path file, int limit) throws IOException {
        byte[] b;
        try (InputStream in = Files.newInputStream(file)) {
            b = in.readNBytes(limit);
        }
        int n = b.length;
        while (n > 0 && b[n - 1] != '\n') {
            n--;
        }
        return java.util.Arrays.copyOf(b, n);
    }

    private static void againstReference(Path file) throws IOException {
        assumeTrue(Files.exists(file), "not present: " + file);
        byte[] data = prefix(file, 40 << 20);
        T.Run fast = T.run(false, sink -> ExportReader.read(data, sink));
        T.assertOk(fast);
        TraceSink sink = new TraceSink(false);
        RefReader.Outcome o = RefReader.read(data, sink);
        assertTrue(o.ok, o.toString());
        assertEquals(fast.hash, sink.hash);
        assertEquals(fast.events, sink.events);
    }

    @Test
    void initPrefixAgainstTheReferenceReader() throws IOException {
        againstReference(T.INIT);
    }

    @Test
    void stdPrefixAgainstTheReferenceReader() throws IOException {
        againstReference(T.STD);
    }
}
