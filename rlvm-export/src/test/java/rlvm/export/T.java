package rlvm.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/** Test helpers: run a reader over bytes and capture what the sink saw and how it ended. */
final class T {
    private T() {}

    static final Path ROOT = findRoot();
    static final Path TESTS = ROOT.resolve("work/arena-site/tests");
    static final Path INIT = ROOT.resolve("work/arena-site/generated/init.ndjson");
    static final Path STD = ROOT.resolve("work/arena-site/generated/std.ndjson");
    static final Path LEANCHECKER =
            Path.of(System.getProperty("user.home"), ".elan/toolchains/leanprover--lean4---v4.36.0/bin/leanchecker");

    static final String META = "{\"meta\":{\"exporter\":{\"name\":\"lean4export\",\"version\":\"3.1.0\"},"
            + "\"format\":{\"version\":\"3.1.0\"},\"lean\":{\"githash\":\"abc\",\"version\":\"4.36.0\"}}}\n";

    private static Path findRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("env.sh"))) {
            p = p.getParent();
        }
        return p == null ? Path.of("").toAbsolutePath() : p;
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Bytes of a string whose chars are all below 256 (to write arbitrary bytes in test tables). */
    static byte[] latin1(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** How a run ended plus the trace of sink calls. */
    static final class Run {
        boolean ok;
        ExportException.Kind kind;
        long line;
        String message;
        String trace;
        long hash;
        long events;
        ExportReader.Result result;
        /** {@link #struct}: runs taken by the sink, and the records in them. */
        long runs;
        long runRecords;
        long earlyChildren;
        /** {@link #struct}: letE records with nondep = true that the sink received as single records. */
        long nondepLets;

        String outcome() {
            return ok ? "ok" : kind + "@" + line;
        }

        @Override
        public String toString() {
            return ok ? "ok" : kind + " at line " + line + ": " + message;
        }
    }

    interface Reader {
        ExportReader.Result read(TraceSink sink) throws ExportException, IOException;
    }

    static Run run(boolean keepText, Reader reader) {
        TraceSink sink = new TraceSink(keepText);
        Run r = new Run();
        try {
            r.result = reader.read(sink);
            r.ok = true;
        } catch (ExportException e) {
            r.kind = e.kind();
            r.line = e.line();
            r.message = e.reason();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        r.trace = keepText ? sink.text() : null;
        r.hash = sink.hash;
        r.events = sink.events;
        return r;
    }

    static ExportReader.Options options(int threads, int bufferBytes, boolean generalOnly) {
        ExportReader.Options o = new ExportReader.Options();
        o.threads = threads;
        o.bufferBytes = bufferBytes;
        o.parallelChunkBytes = bufferBytes;
        o.generalPathOnly = generalOnly;
        return o;
    }

    /** The default configuration: template fast path plus general path, one thread. */
    static Run fast(byte[] data) {
        return run(true, sink -> ExportReader.read(data, sink));
    }

    static Run general(byte[] data) {
        return run(true, sink -> ExportReader.read(data, sink, options(1, 1 << 20, true)));
    }

    static Run smallBuffer(byte[] data, int bufferBytes) {
        return run(true, sink -> ExportReader.read(data, sink, options(1, bufferBytes, false)));
    }

    static Run parallel(byte[] data, int threads, int chunkBytes) {
        return run(true, sink -> ExportReader.read(data, sink, options(threads, chunkBytes, false)));
    }

    /** The packed form the tests ask for: tags 4 - 7 (app, lam, forallE, letE), runs of two records and more. */
    static final ExportReader.RunFormat RUNS = new ExportReader.RunFormat(4, 5, 6, 7, 2);

    static ExportReader.Options packing(int threads, int chunkBytes) {
        ExportReader.Options o = options(threads, chunkBytes, false);
        o.runs = RUNS;
        return o;
    }

    /**
     * A table of finished expressions for the scanner threads ({@link ExportReader.EarlyRefs}) as a sink
     * that numbers expressions from 1 would keep it: entry {@code file id + 1} is the sink id of the
     * expression, for every expression ({@code every} 1) or every {@code every}-th one.
     */
    static ExportReader.EarlyRefs earlyRefs(int every) {
        int[] table = new int[1 << 21];
        for (int i = 1; i < table.length; i += every) {
            table[i] = i;
        }
        return new ExportReader.EarlyRefs() {
            @Override
            public int[] table() {
                return table;
            }

            @Override
            public int delta() {
                return 1;
            }
        };
    }

    /** {@link #packing} with a table of finished expressions. */
    static ExportReader.Options packingEarly(int threads, int chunkBytes, int every) {
        ExportReader.Options o = options(threads, chunkBytes, false);
        o.runs = RUNS.withEarly(earlyRefs(every));
        return o;
    }

    /** Parallel mode with packed runs offered to a sink that refuses them (every {@link TraceSink}). */
    static Run packed(byte[] data, int threads, int chunkBytes) {
        return run(true, sink -> ExportReader.read(data, sink, packing(threads, chunkBytes)));
    }

    /**
     * The structural trace ({@link StructSink}: binder names, binder infos and the nondep flag left
     * out) of a read with {@code options} by a sink that takes the runs offered to it never
     * ({@code take} 0), always (1) or at random (2).
     */
    static Run struct(byte[] data, ExportReader.Options options, int take) {
        StructSink sink = new StructSink(take, data.length);
        Run r = new Run();
        try {
            r.result = ExportReader.read(data, sink, options);
            r.ok = true;
        } catch (ExportException e) {
            r.kind = e.kind();
            r.line = e.line();
            r.message = e.reason();
        }
        r.trace = sink.text();
        r.hash = sink.hash;
        r.events = sink.events;
        r.runs = sink.runs;
        r.runRecords = sink.runRecords;
        r.earlyChildren = sink.earlyChildren;
        r.nondepLets = sink.nondepLets;
        return r;
    }

    /** Through a channel that delivers the data in pieces of random size. */
    static Run channel(byte[] data, int bufferBytes, long seed) {
        return run(true, sink -> ExportReader.read(dribble(data, seed), sink, options(1, bufferBytes, false)));
    }

    static Run ref(byte[] data) {
        TraceSink sink = new TraceSink(true);
        RefReader.Outcome o = RefReader.read(data, sink);
        Run r = new Run();
        r.ok = o.ok;
        r.kind = o.kind;
        r.line = o.line;
        r.message = o.message;
        r.trace = sink.text();
        r.hash = sink.hash;
        r.events = sink.events;
        return r;
    }

    static ReadableByteChannel dribble(byte[] data, long seed) {
        Random rnd = new Random(seed);
        ReadableByteChannel inner = Channels.newChannel(new ByteArrayInputStream(data));
        return new ReadableByteChannel() {
            @Override
            public int read(ByteBuffer dst) throws IOException {
                int want = 1 + rnd.nextInt(rnd.nextBoolean() ? 7 : 300);
                if (dst.remaining() <= want) {
                    return inner.read(dst);
                }
                int limit = dst.limit();
                dst.limit(dst.position() + want);
                int n = inner.read(dst);
                dst.limit(limit);
                return n;
            }

            @Override
            public boolean isOpen() {
                return true;
            }

            @Override
            public void close() {}
        };
    }

    /**
     * Runs every configuration of {@link ExportReader} and the reference reader over {@code data} and
     * asserts that they agree on the outcome (kind and line) and on the complete trace of sink calls.
     * Returns the run of the default configuration.
     */
    static Run agree(String what, byte[] data) {
        Run f = fast(data);
        same(what + ": general path only", f, general(data), true);
        same(what + ": reference reader", f, ref(data), false);
        same(what + ": buffer 64", f, smallBuffer(data, 64), true);
        same(what + ": buffer 257", f, smallBuffer(data, 257), true);
        same(what + ": channel", f, channel(data, 100, data.length), true);
        // stretches of app / lam / forallE records (ExportSink.exprStretch): refused (the runs above), taken, taken at random
        Run s0 = struct(data, options(1, 1 << 20, false), 0);
        same(what + ": app stretches taken", s0, struct(data, options(1, 1 << 20, false), 1), true);
        same(what + ": app stretches taken at random, buffer 257", s0, struct(data, options(1, 257, false), 2), true);
        if (data.length >= 2 * 256) {
            same(what + ": parallel 256", f, parallel(data, 3, 256), true);
            same(what + ": parallel 1000", f, parallel(data, 2, 1000), true);
            // packed runs: offered and refused (the complete trace), taken, taken at random
            same(what + ": packing 256, refused", f, packed(data, 3, 256), true);
            same(what + ": packing 3000, refused", f, packed(data, 2, 3000), true);
            Run s = struct(data, options(1, 1 << 20, false), 0);
            same(what + ": packing 256, taken", s, struct(data, packing(3, 256), 1), true);
            same(what + ": packing 1000, taken", s, struct(data, packing(2, 1000), 1), true);
            same(what + ": packing 5000, taken at random", s, struct(data, packing(2, 5000), 2), true);
            // and with a table of finished expressions: complete, and with holes
            same(what + ": packing 256, early", s, struct(data, packingEarly(3, 256, 1), 1), true);
            same(what + ": packing 2000, some early, taken at random", s, struct(data, packingEarly(2, 2000, 3), 2), true);
            same(what + ": packing 3000, early, refused", f, run(true, sink -> ExportReader.read(data, sink, packingEarly(2, 3000, 1))),
                    true);
        }
        return f;
    }

    static void same(String what, Run expected, Run actual, boolean sameMessage) {
        if (!expected.outcome().equals(actual.outcome())) {
            fail(what + ": outcome " + actual + " but expected " + expected);
        }
        if (sameMessage && !expected.ok) {
            assertEquals(expected.message, actual.message, what + ": message");
        }
        if (expected.trace != null && actual.trace != null) {
            assertEquals(expected.trace, actual.trace, what + ": trace of sink calls");
        }
        assertEquals(expected.hash, actual.hash, what + ": hash of sink calls");
        assertEquals(expected.events, actual.events, what + ": number of sink calls");
    }

    static void assertOk(Run r) {
        if (!r.ok) {
            fail("expected the export to be read, but: " + r);
        }
    }

    static void assertFails(Run r, ExportException.Kind kind, long line, String messagePart) {
        if (r.ok) {
            fail("expected " + kind + " at line " + line + " but the export was read");
        }
        assertEquals(kind + "@" + line, r.outcome(), r.toString());
        if (messagePart != null && !r.message.contains(messagePart)) {
            fail("message \"" + r.message + "\" does not contain \"" + messagePart + "\"");
        }
    }
}
