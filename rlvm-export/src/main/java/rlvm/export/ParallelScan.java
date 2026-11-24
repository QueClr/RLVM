package rlvm.export;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Parallel mode: the file is cut into chunks at line boundaries; scanner threads copy their chunk out
 * of the mapping, run the fast path over it and record the result on a {@link Tape}; the calling
 * thread replays the tapes in file order into the {@link Linker} (and with it into the sink).
 *
 * <p>Deterministic by construction: chunk boundaries depend only on the file content and the chunk
 * size; scanner threads never interpret anything (a line that matches no template is recorded as
 * "slow" and parsed by the calling thread during the replay); the replay order is the file order. The
 * sink therefore receives exactly the calls of the single-threaded mode, and the error reported is
 * the first error of the file.
 *
 * <p>At most {@code 2 * threads + 2} chunks are scanned ahead of the replay, which bounds the memory
 * held in chunk buffers and tapes.
 */
final class ParallelScan {
    private ParallelScan() {}

    static final int MIN_CHUNK = 256;
    /**
     * With packing, a chunk border is moved behind the next declaration line if that line ends within
     * this many bytes: a declaration's records then lie in one chunk and can be offered as runs.
     */
    static final long ALIGN_MAX = 8L << 20;
    private static final ValueLayout.OfLong LE64 =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN);

    /** One chunk of the file: its bytes, the recording of its scan, and where it ends in the file. */
    private static final class Chunk {
        byte[] buf;
        final Tape tape;
        long fileEnd;

        Chunk(int bytes) {
            buf = new byte[bytes + Scanner.PAD];
            tape = new Tape(bytes / 8 + 1024);
        }
    }

    private static final class Shared {
        final MemorySegment seg;
        final long from;
        final long to;
        final long chunk;
        final int numChunks;
        final int window;
        final Chunk[] done;
        final Chunk[] pool;
        int poolSize;
        int nextChunk;
        int replayed;
        boolean abort;
        Throwable failure;
        /** Packing ({@link ExportReader.Options#runs}), or null. */
        final ExportReader.RunFormat runs;
        final ExportReader.Source source;

        Shared(ExportReader.Source source, long from, long to, long chunk, int threads, ExportReader.RunFormat runs) {
            this.source = source;
            this.seg = source.seg;
            this.from = from;
            this.to = to;
            this.chunk = chunk;
            this.runs = runs;
            this.numChunks = (int) Math.min(Integer.MAX_VALUE - 8, (to - from + chunk - 1) / chunk);
            this.window = 2 * threads + 2;
            this.done = new Chunk[numChunks];
            this.pool = new Chunk[window + threads];
        }

        /** The first line start at or after {@code from + k * chunk}. */
        long lineBoundary(int k) {
            if (k <= 0) {
                return from;
            }
            long x = from + k * chunk;
            if (k >= numChunks || x >= to) {
                return to;
            }
            for (long i = x - 1; i < to; i++) {
                if (seg.get(ValueLayout.JAVA_BYTE, i) == '\n') {
                    return i + 1;
                }
            }
            return to;
        }

        /**
         * Start of chunk {@code k}: {@link #lineBoundary}; with packing, the start of the line after
         * the first declaration line that begins at or after it and ends within {@link #ALIGN_MAX}
         * bytes of it (if there is none: the line boundary). A function of the file content and the
         * chunk size only, and monotone in {@code k}.
         */
        long boundary(int k) {
            long ls = lineBoundary(k);
            if (runs == null || k <= 0 || ls >= to) {
                return ls;
            }
            final long limit = Math.min(to, ls + ALIGN_MAX);
            long p = ls;
            while (p < limit) {
                boolean declaration = p + 8 <= to && RunPacker.declarationStart(seg.get(LE64, p));
                long q = lineEnd(p, limit);
                if (q < 0) {
                    break; // the line does not end within the limit
                }
                if (declaration) {
                    return q;
                }
                p = q;
            }
            return ls;
        }

        /** The position after the first {@code '\n'} in {@code [p, limit)}, or -1 if there is none. */
        private long lineEnd(long p, long limit) {
            long i = p;
            while (i + 8 <= limit) {
                long x = seg.get(LE64, i) ^ 0x0A0A0A0A0A0A0A0AL;
                long m = (x - 0x0101010101010101L) & ~x & 0x8080808080808080L;
                if (m != 0) {
                    return i + (Long.numberOfTrailingZeros(m) >>> 3) + 1;
                }
                i += 8;
            }
            for (; i < limit; i++) {
                if (seg.get(ValueLayout.JAVA_BYTE, i) == '\n') {
                    return i + 1;
                }
            }
            return -1;
        }

        /** Claims the next chunk, waiting while the scan is too far ahead of the replay; -1 when done. */
        synchronized int claim() throws InterruptedException {
            while (!abort && nextChunk < numChunks && nextChunk >= replayed + window) {
                wait();
            }
            if (abort || nextChunk >= numChunks) {
                return -1;
            }
            return nextChunk++;
        }

        synchronized Chunk chunk() {
            return poolSize > 0 ? pool[--poolSize] : null;
        }

        synchronized void publish(int k, Chunk c) {
            done[k] = c;
            notifyAll();
        }

        synchronized void fail(Throwable t) {
            if (failure == null) {
                failure = t;
            }
            abort = true;
            notifyAll();
        }

        synchronized Chunk await(int k) throws InterruptedException {
            while (done[k] == null && failure == null) {
                wait();
            }
            if (failure != null) {
                return null;
            }
            Chunk c = done[k];
            done[k] = null;
            return c;
        }

        synchronized void recycle(Chunk c) {
            if (poolSize < pool.length) {
                pool[poolSize++] = c;
            }
            replayed++;
            notifyAll();
        }

        synchronized void stop() {
            abort = true;
            notifyAll();
        }
    }

    private static final class Worker extends Thread {
        private final Shared sh;

        Worker(Shared sh, int index) {
            super("rlvm-export-scan-" + index);
            this.sh = sh;
            setDaemon(true);
        }

        @Override
        public void run() {
            int[] list = new int[64];
            final RunPacker packer = sh.runs == null ? null : new RunPacker(sh.runs);
            try {
                while (true) {
                    int k = sh.claim();
                    if (k < 0) {
                        return;
                    }
                    // the limit on lines is tested on the line boundaries, with or without packing
                    if (sh.lineBoundary(k + 1) - sh.lineBoundary(k) > Scanner.MAX_LINE + sh.chunk) {
                        throw ExportException.unsupported("line longer than " + Scanner.MAX_LINE + " bytes");
                    }
                    long start = sh.boundary(k);
                    long end = sh.boundary(k + 1);
                    long len = end - start;
                    int n = (int) len;
                    Chunk c = sh.chunk();
                    if (c == null) {
                        c = new Chunk((int) Math.max(n, sh.chunk + 4096));
                    } else if (c.buf.length < n + Scanner.PAD) {
                        c.buf = new byte[n + (n >> 2) + Scanner.PAD];
                    }
                    byte[] buf = c.buf;
                    MemorySegment.copy(sh.seg, ValueLayout.JAVA_BYTE, start, buf, 0, n);
                    sh.source.copied(start, end);
                    c.fileEnd = end;
                    c.tape.reset();
                    int lastNl = n - 1;
                    while (lastNl >= 0 && buf[lastNl] != '\n') {
                        lastNl--;
                    }
                    if (lastNl >= 0) {
                        if (packer == null) {
                            Scanner.scan(buf, 0, lastNl + 1, c.tape, list);
                        } else {
                            packer.begin(c.tape, buf);
                            Scanner.scan(buf, 0, lastNl + 1, packer, list);
                            packer.end();
                        }
                    }
                    if (lastNl + 1 < n) {
                        // the unterminated last line of the file
                        c.tape.slow(lastNl + 1, n - lastNl - 1);
                    }
                    sh.publish(k, c);
                }
            } catch (Throwable t) {
                sh.fail(t);
            }
        }
    }

    static void run(ExportReader.Source src, Linker lk, ExportReader.Options o) throws ExportException {
        MemorySegment seg = src.seg;
        long size = src.size;
        // line 1 on the calling thread
        long metaEnd = size;
        for (long i = 0; i < size; i++) {
            if (seg.get(ValueLayout.JAVA_BYTE, i) == '\n') {
                metaEnd = i + 1;
                break;
            }
        }
        if (metaEnd <= Linker.MAX_META_LINE) {
            byte[] meta = new byte[(int) metaEnd];
            MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, 0, meta, 0, (int) metaEnd);
            lk.metaLine(meta, 0, (int) metaEnd);
        } else {
            lk.metaLine(new byte[0], 0, Linker.MAX_META_LINE + 1);
        }
        src.pos = size;
        if (metaEnd >= size) {
            return;
        }
        long chunkBytes = Math.min(Math.max(o.parallelChunkBytes, MIN_CHUNK), 1 << 28);
        int threads = o.threads;
        Shared sh = new Shared(src, metaEnd, size, chunkBytes, threads, o.runs);
        Worker[] workers = new Worker[threads];
        for (int i = 0; i < threads; i++) {
            workers[i] = new Worker(sh, i);
            workers[i].start();
        }
        ExportException error = null;
        try {
            for (int k = 0; k < sh.numChunks; k++) {
                Chunk c = sh.await(k);
                if (c == null) {
                    break;
                }
                lk.src = c.buf;
                c.tape.replay(lk);
                lk.runsEnd(); // the chunk's arrays go back to the scanner threads
                src.released(c.fileEnd);
                sh.recycle(c);
            }
        } catch (ExportException e) {
            error = e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sh.fail(e);
        } finally {
            sh.stop();
            boolean interrupted = false;
            for (Worker w : workers) {
                while (true) {
                    try {
                        w.join();
                        break;
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (error != null) {
            throw error;
        }
        Throwable f = sh.failure;
        if (f != null) {
            if (f instanceof ExportException e) {
                throw e;
            }
            if (f instanceof Error e) {
                throw e;
            }
            if (f instanceof RuntimeException e) {
                throw e;
            }
            throw new IllegalStateException(f);
        }
    }
}
