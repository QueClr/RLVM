package rlvm.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The public entry points, I/O modes, error reporting, and sinks that do not number consecutively. */
class ReaderApiTest {
    /** A canonical export of {@code n} app lines after one bvar. */
    private static StringBuilder apps(int n) {
        StringBuilder sb = new StringBuilder(T.META);
        sb.append("{\"bvar\":0,\"ie\":0}\n");
        for (int i = 1; i <= n; i++) {
            sb.append("{\"app\":{\"arg\":").append(i - 1).append(",\"fn\":").append(i / 2).append("},\"ie\":").append(i).append("}\n");
        }
        return sb;
    }

    @Test
    void errorsCarryTheLineNumberInEveryMode(@TempDir Path dir) throws IOException {
        for (int bad : new int[] {3, 4, 1000, 20_000, 49_999, 50_001}) {
            StringBuilder sb = apps(bad - 3); // lines 1 .. bad-1
            sb.append("{\"app\":{\"arg\":0,\"fn\":123456789},\"ie\":").append(bad - 2).append("}\n"); // line `bad`
            sb.append(apps(60_000 - bad).substring(T.META.length()));
            byte[] data = T.utf8(sb.toString());
            Path file = dir.resolve("bad" + bad + ".ndjson");
            Files.write(file, data);
            T.Run fast = T.fast(data);
            T.assertFails(fast, ExportException.Kind.MALFORMED, bad, "Expr not found 123456789");
            T.same("general", fast, T.general(data), true);
            T.same("buffer 4096", fast, T.smallBuffer(data, 4096), true);
            T.same("channel", fast, T.channel(data, 5000, bad), true);
            T.same("parallel 4x1000", fast, T.parallel(data, 4, 1000), true);
            T.same("parallel 2x100000", fast, T.parallel(data, 2, 100_000), true);
            T.same("reference", fast, T.ref(data), false);
            T.same("mapped file", fast, T.run(true, sink -> ExportReader.read(file, sink)), true);
            ExportReader.Options par = T.options(3, 50_000, false);
            T.same("mapped file, parallel", fast, T.run(true, sink -> ExportReader.read(file, sink, par)), true);
            ExportReader.Options buf = T.options(1, 10_000, false);
            buf.io = ExportReader.Io.BUFFERED;
            T.same("buffered file", fast, T.run(true, sink -> ExportReader.read(file, sink, buf)), true);
            try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
                T.same("channel of the file", fast, T.run(true, sink -> ExportReader.read(ch, sink, new ExportReader.Options())), true);
            }
        }
    }

    @Test
    void messagesAreFormatted() {
        ExportException e = assertThrows(ExportException.class,
                () -> ExportReader.read(T.utf8(T.META + "{\"in\":1,\"str\":{\"pre\":4,\"str\":\"a\"}}\n"), new TraceSink(false)));
        assertEquals("malformed export, line 2: Name not found 4", e.getMessage());
        assertEquals("Name not found 4", e.reason());
        assertEquals(2, e.line());
        e = assertThrows(ExportException.class,
                () -> ExportReader.read(T.utf8(T.META + "{\"bvar\":0,\"ie\":0}\n"
                        + "{\"ie\":1,\"proj\":{\"idx\":18446744073709551616,\"struct\":0,\"typeName\":0}}\n"), new TraceSink(false)));
        assertEquals("unsupported export, line 3: Expr.proj: field index is 2^63 or more", e.getMessage());
        assertEquals(ExportException.Kind.UNSUPPORTED, e.kind());
        e = assertThrows(ExportException.class,
                () -> ExportReader.read(T.utf8(T.META + "{\"il\":1,\"succ\":18446744073709551616}\n"), new TraceSink(false)));
        assertEquals("malformed export, line 2: Level not found (index of 2^63 or more)", e.getMessage());
        assertEquals("malformed export: x", ExportException.malformed("x").getMessage());
        assertEquals("unsupported export, line 7: y", ExportException.unsupported("y").atLine(7).atLine(9).getMessage());
    }

    @Test
    void errorsRaisedByTheSinkGetTheLineNumber() {
        TraceSink failing = new TraceSink(false) {
            @Override
            public int exprApp(int fn, int arg) {
                if (exprs == 5) {
                    throw new IllegalStateException("boom");
                }
                return super.exprApp(fn, arg);
            }

            @Override
            public int exprSort(int level) throws ExportException {
                throw ExportException.unsupported("no sorts today");
            }
        };
        byte[] data = T.utf8(apps(3) + "{\"ie\":4,\"sort\":0}\n");
        ExportException e = assertThrows(ExportException.class, () -> ExportReader.read(data, failing));
        assertEquals(6, e.line());
        assertEquals(ExportException.Kind.UNSUPPORTED, e.kind());
        assertEquals("no sorts today", e.reason());
        // runtime exceptions of the sink pass through unchanged
        IllegalStateException ise = assertThrows(IllegalStateException.class,
                () -> ExportReader.read(T.utf8(apps(20).toString()), new TraceSink(false) {
                    @Override
                    public int exprApp(int fn, int arg) {
                        if (exprs == 5) {
                            throw new IllegalStateException("boom");
                        }
                        return super.exprApp(fn, arg);
                    }
                }));
        assertEquals("boom", ise.getMessage());
        // also when the sink fails in parallel mode
        byte[] big = T.utf8(apps(5000) + "{\"ie\":5001,\"sort\":0}\n" + apps(5000).substring(T.META.length()));
        e = assertThrows(ExportException.class, () -> ExportReader.read(big, new TraceSink(false) {
            @Override
            public int exprSort(int level) throws ExportException {
                throw ExportException.malformed("sink says no");
            }
        }, T.options(4, 2000, false)));
        assertEquals(5003, e.line());
        assertEquals("sink says no", e.reason());
    }

    @Test
    void filesAndStreams(@TempDir Path dir) throws Exception {
        assertThrows(NoSuchFileException.class, () -> ExportReader.read(dir.resolve("missing.ndjson"), new TraceSink(false)));
        Path empty = dir.resolve("empty.ndjson");
        Files.write(empty, new byte[0]);
        for (ExportReader.Io io : ExportReader.Io.values()) {
            ExportReader.Options o = new ExportReader.Options();
            o.io = io;
            TraceSink sink = new TraceSink(true);
            ExportReader.Result r = ExportReader.read(empty, sink, o);
            assertEquals(0, r.lines);
            assertEquals(0, r.bytes);
            assertEquals("meta 0 null|null|null|null|null\n", sink.text());
            assertEquals(1, r.names);
            assertEquals(1, r.levels);
            assertEquals(0, r.exprs);
            assertEquals(-1, r.maxExprId);
            assertTrue(r.tableFree);
        }
        TraceSink sink = new TraceSink(true);
        ExportReader.Result r = ExportReader.read(new byte[0], sink);
        assertEquals(0, r.lines);
        assertEquals("meta 0 null|null|null|null|null\n", sink.text());

        byte[] data = T.utf8(apps(30_000).toString());
        Path file = dir.resolve("apps.ndjson");
        Files.write(file, data);
        T.Run expected = T.fast(data);
        T.assertOk(expected);
        assertEquals(30_002, expected.result.lines);
        assertEquals(data.length, expected.result.bytes);
        assertEquals(1, expected.result.threads);
        for (ExportReader.Io io : ExportReader.Io.values()) {
            for (int threads : new int[] {1, 2, 5}) {
                for (boolean unload : new boolean[] {true, false}) {
                    ExportReader.Options o = T.options(threads, 30_000, false);
                    o.io = io;
                    o.unloadMapped = unload;
                    T.Run run = T.run(true, s -> ExportReader.read(file, s, o));
                    T.same(io + " threads " + threads, expected, run, true);
                    assertEquals(io == ExportReader.Io.BUFFERED ? ExportReader.Io.BUFFERED : ExportReader.Io.MAPPED, run.result.io);
                    assertEquals(io == ExportReader.Io.BUFFERED ? 1 : threads, run.result.threads);
                    assertEquals(data.length, run.result.bytes);
                    assertEquals(30_002, run.result.lines);
                }
            }
        }
    }

    @Test
    void linesLongerThanEveryBuffer() {
        // a 3 MB inductive-like line between ordinary lines, read with tiny buffers and tiny chunks
        StringBuilder big = new StringBuilder("{\"in\":1,\"str\":{\"pre\":0,\"str\":\"");
        big.append("x".repeat(3_000_000)).append("\"}}\n");
        byte[] data = T.utf8(T.META + "{\"bvar\":0,\"ie\":0}\n" + big + "{\"app\":{\"arg\":0,\"fn\":0},\"ie\":1}\n"
                + "{\"in\":2,\"str\":{\"pre\":1,\"str\":\"y\"}}");
        T.Run expected = T.fast(data);
        T.assertOk(expected);
        assertEquals(5, expected.result.lines);
        assertEquals(3, expected.result.names);
        T.same("buffer 64", expected, T.smallBuffer(data, 64), true);
        T.same("buffer 1000", expected, T.smallBuffer(data, 1000), true);
        T.same("channel", expected, T.channel(data, 100, 1), true);
        T.same("parallel 256", expected, T.parallel(data, 4, 256), true);
        T.same("parallel 1 MiB", expected, T.parallel(data, 3, 1 << 20), true);
        T.same("general", expected, T.general(data), true);
    }

    @Test
    void parallelModeOnGeneratedExports() {
        Random rnd = new Random(77);
        for (int round = 0; round < 40; round++) {
            byte[] data = Gen.file(rnd, 3000, rnd.nextBoolean(), rnd.nextInt(3) == 0 ? 0.2 : 0.0);
            T.Run expected = T.fast(data);
            T.assertOk(expected);
            for (int threads : new int[] {2, 3, 8}) {
                int chunk = 256 + rnd.nextInt(20_000);
                T.Run p = T.parallel(data, threads, chunk);
                T.same("round " + round + " threads " + threads + " chunk " + chunk, expected, p, true);
                assertEquals(threads, p.result.threads);
                assertEquals(expected.result.generalPathLines, p.result.generalPathLines);
                assertEquals(expected.result.lines, p.result.lines);
            }
        }
    }

    /** A sink whose ids are not consecutive: checks that the reader passes back exactly what it was given. */
    private static final class RemapSink extends TraceSink {
        RemapSink() {
            super(true);
        }

        private static int out(int id) {
            return id * 3 + 1000;
        }

        private static int in(int id) {
            assertEquals(1, (id - 1000) % 3 == 0 ? 1 : 0, "the reader passed an id the sink never returned: " + id);
            return (id - 1000) / 3;
        }

        @Override
        public int nameAnonymous() {
            return out(1);
        }

        @Override
        public int nameStr(int prefix, byte[] utf8, int off, int len) {
            return out(super.nameStr(in(prefix), utf8, off, len));
        }

        @Override
        public int nameNum(int prefix, long value) {
            return out(super.nameNum(in(prefix), value));
        }

        @Override
        public int levelZero() {
            return out(1);
        }

        @Override
        public int levelSucc(int level) {
            return out(super.levelSucc(in(level)));
        }

        @Override
        public int levelParam(int name) {
            return out(super.levelParam(in(name)));
        }

        @Override
        public int exprBVar(int index) {
            return out(super.exprBVar(index));
        }

        @Override
        public int exprSort(int level) throws ExportException {
            return out(super.exprSort(in(level)));
        }

        @Override
        public int exprConst(int name, int[] ls, int off, int count) {
            int[] copy = new int[count];
            for (int i = 0; i < count; i++) {
                copy[i] = in(ls[off + i]);
            }
            return out(super.exprConst(in(name), copy, 0, count));
        }

        @Override
        public int exprApp(int fn, int arg) {
            return out(super.exprApp(in(fn), in(arg)));
        }

        @Override
        public int exprLam(int binderName, int binderInfo, int type, int body) {
            return out(super.exprLam(in(binderName), binderInfo, in(type), in(body)));
        }

        @Override
        public int exprForall(int binderName, int binderInfo, int type, int body) {
            return out(super.exprForall(in(binderName), binderInfo, in(type), in(body)));
        }

        @Override
        public void declThm(int name, int[] ids, int lpOff, int lpCount, int type, int value, int allOff, int allCount) {
            int[] copy = new int[lpCount + allCount];
            for (int i = 0; i < lpCount; i++) {
                copy[i] = in(ids[lpOff + i]);
            }
            for (int i = 0; i < allCount; i++) {
                copy[lpCount + i] = in(ids[allOff + i]);
            }
            super.declThm(in(name), copy, 0, lpCount, in(type), in(value), lpCount, allCount);
        }
    }

    private static final String SMALL = T.META
            + "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"a\"}}\n"
            + "{\"in\":2,\"num\":{\"i\":3,\"pre\":1}}\n"
            + "{\"il\":1,\"succ\":0}\n"
            + "{\"il\":2,\"param\":2}\n"
            + "{\"bvar\":0,\"ie\":0}\n"
            + "{\"ie\":1,\"sort\":2}\n"
            + "{\"const\":{\"name\":2,\"us\":[1,2]},\"ie\":2}\n"
            + "{\"app\":{\"arg\":0,\"fn\":2},\"ie\":3}\n"
            + "{\"ie\":4,\"lam\":{\"binderInfo\":\"default\",\"body\":3,\"name\":1,\"type\":1}}\n"
            + "{\"forallE\":{\"binderInfo\":\"default\",\"body\":1,\"name\":2,\"type\":1},\"ie\":5}\n"
            + "{\"thm\":{\"all\":[1,2],\"levelParams\":[2],\"name\":1,\"type\":5,\"value\":4}}\n";

    @Test
    void sinksThatDoNotNumberConsecutivelyGetTheirOwnIdsBack() throws ExportException {
        T.Run expected = T.fast(T.utf8(SMALL));
        T.assertOk(expected);
        assertTrue(expected.result.tableFree);
        for (boolean general : new boolean[] {false, true}) {
            RemapSink sink = new RemapSink();
            ExportReader.Result r = ExportReader.read(T.utf8(SMALL), sink, T.options(1, 1 << 20, general));
            assertEquals(expected.trace, sink.text());
            assertFalse(r.tableFree);
            assertTrue(r.exprsDense && r.namesDense && r.levelsDense);
            assertEquals(6, r.exprs);
        }
    }

    @Test
    void aSinkMayReturnAnExistingId() throws ExportException {
        // mdata dropped by the sink: later references to the mdata record go to the wrapped expression
        TraceSink dropping = new TraceSink(true) {
            @Override
            public int exprMData(int expr) {
                return expr;
            }
        };
        ExportReader.Result r = ExportReader.read(T.utf8(T.META
                + "{\"bvar\":0,\"ie\":0}\n"
                + "{\"ie\":1,\"mdata\":{\"data\":{},\"expr\":0}}\n"
                + "{\"app\":{\"arg\":1,\"fn\":0},\"ie\":2}\n"
                + "{\"app\":{\"arg\":2,\"fn\":1},\"ie\":3}\n"), dropping);
        assertEquals("meta 1 lean4export|3.1.0|3.1.0|abc|4.36.0\nbvar 0 1\napp 1 1 2\napp 1 2 3\n", dropping.text());
        assertEquals(4, r.exprs);
        assertFalse(r.tableFree);
    }

    @Test
    void headerVariants() throws ExportException {
        String[][] cases = {
            {T.META, "meta 1 lean4export|3.1.0|3.1.0|abc|4.36.0"},
            {"{\"meta\":{}}\n", "meta 1 null|null|null|null|null"},
            {"{\"meta\":{\"format\":{\"version\":\"9.9\"},\"exporter\":{\"name\":7,\"version\":\"v\"},\"lean\":{\"githash\":null}},\"other\":1}\n",
                "meta 1 null|v|9.9|null|null"},
            {" { \"meta\" : { \"lean\" : { \"version\" : \"4.\\u0033\" } } } \r\n", "meta 1 null|null|null|null|4.3"},
            {"{\"meta\":[]}\n", "meta 0 null|null|null|null|null"},
            {"{\"meta\":{\"format\":{\"version\":\"3.1.0\"}}}", "meta 1 null|null|3.1.0|null|null"}, // no newline
            {"{\"meta\":{\"format\":{\"version\":\"3.1.0\"}}} x\n", "meta 0 null|null|null|null|null"},
            {"\n", "meta 0 null|null|null|null|null"},
            {"{\"meta\":{\"a\":1,\"a\":2}}\n", "meta 0 null|null|null|null|null"},
        };
        for (String[] c : cases) {
            T.Run r = T.agree(c[0], T.utf8(c[0]));
            T.assertOk(r);
            assertEquals(c[1] + "\n", r.trace, c[0]);
            assertEquals(1, r.result.lines);
        }
    }
}
