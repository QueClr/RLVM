package rlvm.export;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * Streaming reader for lean4export NDJSON files (format 3.1.0): turns a file into calls on an
 * {@link ExportSink}, in file order, from the calling thread.
 *
 * <p>Per line there are two code paths: a template fast path for the byte-canonical shapes written by
 * the exporter ({@link Scanner}), and a strict general JSON path for everything else. The reader
 * accepts a file if and only if the official reader ({@code leanchecker --from-export}) parses it
 * without error, with these exceptions: a second declaration of a constant name must be rejected by
 * the sink (see {@link ExportSink}), and inputs outside the supported range are declined with
 * {@link ExportException.Kind#UNSUPPORTED} instead of being accepted.
 *
 * <p>Like the official reader, line 1 is taken as the metadata header whatever it contains, lines end
 * with {@code '\n'} (a missing final newline is fine, CR before LF is whitespace), and an empty line is
 * an error.
 */
public final class ExportReader {
    private ExportReader() {}

    /** How a file is brought into memory. */
    public enum Io {
        /** {@link #MAPPED} for regular files, else {@link #BUFFERED}. */
        AUTO,
        /** Map the file and copy it chunk-wise into the parse buffer; processed pages are released. */
        MAPPED,
        /** {@code read} chunks into the parse buffer. */
        BUFFERED
    }

    /** Tuning knobs; the defaults are the measured best (see the module README). */
    public static final class Options {
        public Io io = Io.AUTO;
        /**
         * Number of scanner threads. 1 (default): everything happens on the calling thread. N &gt; 1:
         * N threads run the fast path on chunks of the file while the calling thread feeds the sink in
         * file order (only with mapped or in-memory input; the result is identical to the 1-thread
         * mode).
         */
        public int threads = 1;
        /** Size of the read buffer of the single-threaded mode (it grows if a line is longer). */
        public int bufferBytes = 1 << 20;
        /** Size of the chunk handed to one scanner thread in parallel mode. */
        public int parallelChunkBytes = 4 << 20;
        /** Release processed pages of the mapping ({@code madvise(DONTNEED)}), keeping RSS flat. */
        public boolean unloadMapped = true;
        /** Testing: send every line through the general JSON path. */
        public boolean generalPathOnly;
    }

    /** What was read. */
    public static final class Result {
        /** Lines including the header (an unterminated last line counts). */
        public long lines;
        public long bytes;
        /** Lines that were parsed by the general JSON path (the header is not counted). */
        public long generalPathLines;
        /** Bound ids per id space, including the implicit name 0 and level 0. */
        public long names;
        public long levels;
        public long exprs;
        /** Largest id of the file per id space (-1: none). */
        public long maxNameId;
        public long maxLevelId;
        public long maxExprId;
        /** Whether the ids of the space were 0, 1, 2, ... in file order. */
        public boolean namesDense;
        public boolean levelsDense;
        public boolean exprsDense;
        /** Whether no id translation table was needed (dense file ids and a consecutively numbering sink). */
        public boolean tableFree;
        public Io io;
        public int threads;
    }

    // ------------------------------------------------------------------------------------- entry points

    public static Result read(Path file, ExportSink sink) throws IOException, ExportException {
        return read(file, sink, new Options());
    }

    public static Result read(Path file, ExportSink sink, Options options) throws IOException, ExportException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = Files.isRegularFile(file) ? ch.size() : -1;
            MemorySegment seg = null;
            if (size > 0 && (options.io == Io.MAPPED || options.io == Io.AUTO)) {
                try {
                    seg = ch.map(FileChannel.MapMode.READ_ONLY, 0, size, Arena.ofAuto());
                } catch (IOException | UnsupportedOperationException e) {
                    if (options.io == Io.MAPPED) {
                        throw new IOException("cannot map " + file + ": " + e.getMessage(), e);
                    }
                }
            }
            try {
                return seg != null
                        ? run(new Source(seg, options.unloadMapped), sink, options, Io.MAPPED)
                        : run(new Source(ch), sink, options, Io.BUFFERED);
            } catch (InternalError e) {
                // SIGBUS on the mapping: the file was truncated while it was being read
                throw new IOException("mapped file became inaccessible: " + file, e);
            }
        }
    }

    /** Reads an export held in memory ({@code data} is not modified). */
    public static Result read(byte[] data, ExportSink sink) throws ExportException {
        return read(data, sink, new Options());
    }

    public static Result read(byte[] data, ExportSink sink, Options options) throws ExportException {
        try {
            return run(new Source(MemorySegment.ofArray(data), false), sink, options, Io.MAPPED);
        } catch (IOException e) {
            throw new IllegalStateException(e); // no I/O on this path
        }
    }

    /** Reads an export from a (blocking) channel in buffered mode; the channel is not closed. */
    public static Result read(ReadableByteChannel channel, ExportSink sink, Options options)
            throws IOException, ExportException {
        return run(new Source(channel), sink, options, Io.BUFFERED);
    }

    private static Result run(Source src, ExportSink sink, Options o, Io io) throws IOException, ExportException {
        Linker lk = new Linker(sink);
        Result res = new Result();
        res.io = io;
        res.threads = 1;
        try {
            lk.begin();
            if (src.seg != null && o.threads > 1 && !o.generalPathOnly
                    && src.size >= 2L * Math.max(o.parallelChunkBytes, ParallelScan.MIN_CHUNK)) {
                res.threads = o.threads;
                ParallelScan.run(src, lk, o);
            } else {
                sequential(src, lk, o);
            }
        } catch (ExportException e) {
            throw e.atLine(lk.line);
        }
        res.lines = lk.line;
        res.bytes = src.pos;
        res.generalPathLines = lk.slowLines;
        res.names = lk.names.boundCount();
        res.levels = lk.levels.boundCount();
        res.exprs = lk.exprs.boundCount();
        res.maxNameId = lk.names.maxBoundId();
        res.maxLevelId = lk.levels.maxBoundId();
        res.maxExprId = lk.exprs.maxBoundId();
        res.namesDense = lk.names.denseInOrder();
        res.levelsDense = lk.levels.denseInOrder();
        res.exprsDense = lk.exprs.denseInOrder();
        res.tableFree = lk.names.tableFree() && lk.levels.tableFree() && lk.exprs.tableFree();
        return res;
    }

    // ------------------------------------------------------------------------------------------ input

    /** Where the bytes come from: a segment (mapped file or heap array) or a channel. */
    static final class Source {
        private static final long PAGE = 1 << 16; // a multiple of every page size in use (4K, 16K, 64K)

        final MemorySegment seg;
        final long size;
        private final ReadableByteChannel channel;
        private final boolean unload;
        /** Bytes delivered so far. */
        long pos;
        private long unloaded;

        Source(MemorySegment seg, boolean unload) {
            this.seg = seg;
            this.size = seg.byteSize();
            this.channel = null;
            this.unload = unload && seg.isMapped();
        }

        Source(ReadableByteChannel channel) {
            this.seg = null;
            this.size = -1;
            this.channel = channel;
            this.unload = false;
        }

        /** Reads up to {@code len > 0} bytes into {@code dst[off ..]}; the number read (at least 1) or -1 at the end. */
        int read(byte[] dst, int off, int len) throws IOException {
            if (seg != null) {
                long n = Math.min(len, size - pos);
                if (n <= 0) {
                    return -1;
                }
                MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, pos, dst, off, (int) n);
                pos += n;
                released(pos);
                return (int) n;
            }
            ByteBuffer bb = ByteBuffer.wrap(dst, off, len);
            while (true) {
                int n = channel.read(bb);
                if (n != 0) {
                    if (n > 0) {
                        pos += n;
                    }
                    return n;
                }
                Thread.onSpinWait(); // a non-blocking channel with nothing to deliver yet
            }
        }

        /** Everything before {@code upTo} has been copied out: its pages may leave the resident set. */
        void released(long upTo) {
            if (unload) {
                long end = upTo & -PAGE;
                if (end - unloaded >= 16 * PAGE) {
                    seg.asSlice(unloaded, end - unloaded).unload();
                    unloaded = end;
                }
            }
        }
    }

    /** Everything on the calling thread: fill the buffer, scan the complete lines in place, carry the rest. */
    private static void sequential(Source src, Linker lk, Options o) throws IOException, ExportException {
        int cap = Math.max(o.bufferBytes, 64);
        byte[] buf = new byte[cap + Scanner.PAD];
        int[] list = new int[64];
        int filled = 0;
        boolean eof = false;
        boolean metaDone = false;
        while (true) {
            while (filled < cap && !eof) {
                int n = src.read(buf, filled, cap - filled);
                if (n < 0) {
                    eof = true;
                } else {
                    filled += n;
                }
            }
            int lastNl = filled - 1;
            while (lastNl >= 0 && buf[lastNl] != '\n') {
                lastNl--;
            }
            if (lastNl < 0 && !eof) {
                // a line longer than the buffer
                if (cap >= Scanner.MAX_LINE) {
                    throw ExportException.unsupported("line longer than " + Scanner.MAX_LINE + " bytes");
                }
                cap *= 2;
                buf = Arrays.copyOf(buf, cap + Scanner.PAD);
                continue;
            }
            int pos = 0;
            int end = lastNl + 1;
            lk.src = buf;
            if (!metaDone && end > 0) {
                int metaEnd = 0;
                while (buf[metaEnd] != '\n') {
                    metaEnd++;
                }
                metaEnd++;
                lk.metaLine(buf, 0, metaEnd);
                metaDone = true;
                pos = metaEnd;
            }
            if (pos < end) {
                if (o.generalPathOnly) {
                    allSlow(buf, pos, end, lk);
                } else {
                    Scanner.scan(buf, pos, end, lk, list);
                }
            }
            int rest = filled - end;
            if (eof) {
                if (rest > 0) {
                    if (metaDone) {
                        lk.slowLine(buf, end, rest);
                    } else {
                        lk.metaLine(buf, end, rest);
                    }
                } else if (!metaDone) {
                    lk.metaLine(null, 0, 0);
                    lk.line = 0; // an empty input has no lines
                }
                return;
            }
            if (rest > 0) {
                System.arraycopy(buf, end, buf, 0, rest);
            }
            filled = rest;
        }
    }

    /** Every line of {@code [pos, end)} through the general path. */
    static void allSlow(byte[] buf, int pos, int end, Linker lk) throws ExportException {
        while (pos < end) {
            int nl = Scanner.newline(buf, pos, end);
            lk.slow(pos, nl + 1 - pos);
            pos = nl + 1;
        }
    }
}
