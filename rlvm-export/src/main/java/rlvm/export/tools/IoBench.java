package rlvm.export.tools;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * I/O and accessor experiment from the start of the module (kept as evidence for the choice documented
 * in the README; it is not used by the reader).
 *
 * <p>All modes do the same work: recognise {@code app} lines with the word-at-a-time template matcher,
 * parse their three numbers, and skip every other line with a word-at-a-time newline search. They
 * differ in where the bytes come from and how words are loaded:
 * <ul>
 *   <li>{@code read}: {@link FileChannel#read} into a reusable heap {@code byte[]}, words through a
 *       {@code byte[]} view {@link VarHandle};</li>
 *   <li>{@code readseg}: the same buffer, words through a heap {@link MemorySegment};</li>
 *   <li>{@code readbb}: the same buffer, words through a heap {@link ByteBuffer};</li>
 *   <li>{@code mmapcopy}: the file is memory-mapped and copied chunk-wise into a reusable
 *       {@code byte[]} (what the reader does now);</li>
 *   <li>{@code mmap}: the mapping is parsed in place through {@link MemorySegment} accessors;</li>
 *   <li>{@code direct}: {@link FileChannel#read} into a direct buffer, parsed through its segment.</li>
 * </ul>
 * Outcome (std.ndjson, 584 MB, best steady-state pass): {@code mmap} 0.17 s, {@code direct} 0.20 s,
 * {@code readseg} 0.22 s, {@code mmapcopy} 0.28 s, {@code read}/{@code readbb} 0.30 s; instructions
 * per pass equal within noise. The segment variants lost on warm-up: with the complete scanner the
 * JIT warm-up cost 7.8 G instructions against 0.7-2 G for {@code byte[]}.
 *
 * <p>Usage: {@code IoBench MODE FILE [chunkBytes [repeat]]}.
 */
public final class IoBench {
    private IoBench() {}

    private static final VarHandle LE64 =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong SEG64 =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private static long lit(String s) {
        long w = 0;
        for (int i = s.length() - 1; i >= 0; i--) w = (w << 8) | (s.charAt(i) & 0xFF);
        return w;
    }

    private static final long APP0 = lit("{\"app\":{");
    private static final long APP1 = lit(":{\"arg\":");
    private static final long FN = lit(",\"fn\":");
    private static final long IE = lit("},\"ie\":");
    private static final long END = lit("}\n");
    private static final long M2 = 0xFFFFL, M6 = 0xFFFF_FFFF_FFFFL, M7 = 0xFF_FFFF_FFFF_FFFFL;
    private static final long ONES = 0x0101010101010101L, HIGH = 0x8080808080808080L;

    static long checksum, apps, others, lines;

    /** (value << 4) | digits for 1..8 canonical digits, else -1. */
    private static long num(long w) {
        long t = w ^ 0x3030303030303030L;
        long nd = ((t + 0x7676767676767676L) | t) & HIGH;
        if (nd == 0) return -1;
        int n = Long.numberOfTrailingZeros(nd) >>> 3;
        if (n == 0) return -1;
        t <<= 64 - (n << 3);
        t = (t * 2561) >>> 8 & 0x00FF00FF00FF00FFL;
        t = (t * 6553601) >>> 16 & 0x0000FFFF0000FFFFL;
        t = (t * 42949672960001L) >>> 32;
        return (t << 4) | n;
    }

    private static int scanArray(byte[] b, int pos, int end) {
        long sum = 0, na = 0, no = 0;
        while (pos < end) {
            int q = pos;
            slow:
            {
                if ((long) LE64.get(b, q) != APP0 || (long) LE64.get(b, q + 6) != APP1) break slow;
                q += 14;
                long r = num((long) LE64.get(b, q));
                if (r < 0) break slow;
                long arg = r >>> 4;
                q += (int) r & 15;
                if (((long) LE64.get(b, q) & M6) != FN) break slow;
                q += 6;
                r = num((long) LE64.get(b, q));
                if (r < 0) break slow;
                long fn = r >>> 4;
                q += (int) r & 15;
                if (((long) LE64.get(b, q) & M7) != IE) break slow;
                q += 7;
                r = num((long) LE64.get(b, q));
                if (r < 0) break slow;
                long id = r >>> 4;
                q += (int) r & 15;
                if (((long) LE64.get(b, q) & M2) != END) break slow;
                sum += fn + arg + id;
                na++;
                pos = q + 2;
                continue;
            }
            // slow: find the newline
            q = pos;
            while (true) {
                long x = (long) LE64.get(b, q) ^ 0x0A0A0A0A0A0A0A0AL;
                long m = (x - ONES) & ~x & HIGH;
                if (m != 0) {
                    q += (Long.numberOfTrailingZeros(m) >>> 3) + 1;
                    break;
                }
                q += 8;
            }
            no++;
            pos = q;
        }
        checksum += sum;
        apps += na;
        others += no;
        return pos;
    }

    private static int scanBuffer(ByteBuffer b, int pos, int end) {
        long sum = 0, na = 0, no = 0;
        while (pos < end) {
            int q = pos;
            slow:
            {
                if (b.getLong(q) != APP0 || b.getLong(q + 6) != APP1) break slow;
                q += 14;
                long r = num(b.getLong(q));
                if (r < 0) break slow;
                long arg = r >>> 4;
                q += (int) r & 15;
                if ((b.getLong(q) & M6) != FN) break slow;
                q += 6;
                r = num(b.getLong(q));
                if (r < 0) break slow;
                long fn = r >>> 4;
                q += (int) r & 15;
                if ((b.getLong(q) & M7) != IE) break slow;
                q += 7;
                r = num(b.getLong(q));
                if (r < 0) break slow;
                long id = r >>> 4;
                q += (int) r & 15;
                if ((b.getLong(q) & M2) != END) break slow;
                sum += fn + arg + id;
                na++;
                pos = q + 2;
                continue;
            }
            // slow: find the newline
            q = pos;
            while (true) {
                long x = b.getLong(q) ^ 0x0A0A0A0A0A0A0A0AL;
                long m = (x - ONES) & ~x & HIGH;
                if (m != 0) {
                    q += (Long.numberOfTrailingZeros(m) >>> 3) + 1;
                    break;
                }
                q += 8;
            }
            no++;
            pos = q;
        }
        checksum += sum;
        apps += na;
        others += no;
        return pos;
    }

    private static long scanSegment(MemorySegment b, long pos, long end) {
        long sum = 0, na = 0, no = 0;
        while (pos < end) {
            long q = pos;
            slow:
            {
                if (b.get(SEG64, q) != APP0 || b.get(SEG64, q + 6) != APP1) break slow;
                q += 14;
                long r = num(b.get(SEG64, q));
                if (r < 0) break slow;
                long arg = r >>> 4;
                q += r & 15;
                if ((b.get(SEG64, q) & M6) != FN) break slow;
                q += 6;
                r = num(b.get(SEG64, q));
                if (r < 0) break slow;
                long fn = r >>> 4;
                q += r & 15;
                if ((b.get(SEG64, q) & M7) != IE) break slow;
                q += 7;
                r = num(b.get(SEG64, q));
                if (r < 0) break slow;
                long id = r >>> 4;
                q += r & 15;
                if ((b.get(SEG64, q) & M2) != END) break slow;
                sum += fn + arg + id;
                na++;
                pos = q + 2;
                continue;
            }
            q = pos;
            while (true) {
                long x = b.get(SEG64, q) ^ 0x0A0A0A0A0A0A0A0AL;
                long m = (x - ONES) & ~x & HIGH;
                if (m != 0) {
                    q += (Long.numberOfTrailingZeros(m) >>> 3) + 1;
                    break;
                }
                q += 8;
            }
            no++;
            pos = q;
        }
        checksum += sum;
        apps += na;
        others += no;
        return pos;
    }

    private static int lastNewline(byte[] b, int from, int to) {
        for (int i = to - 1; i >= from; i--) if (b[i] == '\n') return i;
        return -1;
    }

    public static void main(String[] args) throws IOException {
        String mode = args[0];
        Path file = Path.of(args[1]);
        int chunk = args.length > 2 ? Integer.parseInt(args[2]) : 1 << 20;
        int repeat = args.length > 3 ? Integer.parseInt(args[3]) : 1;
        for (int i = 0; i < repeat; i++) run(mode, file, chunk);
    }

    private static void run(String mode, Path file, int chunk) throws IOException {
        final int pad = 64;
        checksum = apps = others = 0;
        long t0 = System.nanoTime();
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = ch.size();
            switch (mode) {
                case "read" -> {
                    byte[] buf = new byte[chunk + pad];
                    ByteBuffer bb = ByteBuffer.wrap(buf, 0, chunk);
                    int carry = 0;
                    while (true) {
                        bb.limit(chunk).position(carry);
                        int n = ch.read(bb);
                        if (n < 0) break;
                        int filled = carry + n;
                        int nl = lastNewline(buf, carry, filled);
                        if (nl < 0) throw new IOException("line longer than chunk");
                        scanArray(buf, 0, nl + 1);
                        carry = filled - (nl + 1);
                        System.arraycopy(buf, nl + 1, buf, 0, carry);
                    }
                }
                case "readseg" -> {
                    byte[] buf = new byte[chunk + pad];
                    MemorySegment hs = MemorySegment.ofArray(buf);
                    ByteBuffer bb = ByteBuffer.wrap(buf, 0, chunk);
                    int carry = 0;
                    while (true) {
                        bb.limit(chunk).position(carry);
                        int n = ch.read(bb);
                        if (n < 0) break;
                        int filled = carry + n;
                        int nl = lastNewline(buf, carry, filled);
                        if (nl < 0) throw new IOException("line longer than chunk");
                        scanSegment(hs, 0, nl + 1);
                        carry = filled - (nl + 1);
                        System.arraycopy(buf, nl + 1, buf, 0, carry);
                    }
                }
                case "readbb" -> {
                    byte[] buf = new byte[chunk + pad];
                    ByteBuffer bb = ByteBuffer.wrap(buf, 0, chunk);
                    ByteBuffer view = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
                    int carry = 0;
                    while (true) {
                        bb.limit(chunk).position(carry);
                        int n = ch.read(bb);
                        if (n < 0) break;
                        int filled = carry + n;
                        int nl = lastNewline(buf, carry, filled);
                        if (nl < 0) throw new IOException("line longer than chunk");
                        scanBuffer(view, 0, nl + 1);
                        carry = filled - (nl + 1);
                        System.arraycopy(buf, nl + 1, buf, 0, carry);
                    }
                }
                case "mmapcopy" -> {
                    try (Arena arena = Arena.ofConfined()) {
                        MemorySegment seg = ch.map(FileChannel.MapMode.READ_ONLY, 0, size, arena);
                        byte[] buf = new byte[chunk + pad];
                        long off = 0;
                        while (off < size) {
                            int n = (int) Math.min(chunk, size - off);
                            MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, off, buf, 0, n);
                            int nl = lastNewline(buf, 0, n);
                            if (nl < 0) throw new IOException("line longer than chunk");
                            scanArray(buf, 0, nl + 1);
                            off += nl + 1;
                        }
                    }
                }
                case "mmap" -> {
                    try (Arena arena = Arena.ofConfined()) {
                        MemorySegment seg = ch.map(FileChannel.MapMode.READ_ONLY, 0, size, arena);
                        // the last 8 bytes are handled by the same loop on a padded copy
                        long safeEnd = size - 16;
                        while (safeEnd > 0 && seg.get(ValueLayout.JAVA_BYTE, safeEnd - 1) != '\n') safeEnd--;
                        long pos = 0;
                        while (pos < safeEnd) {
                            long e = Math.min(safeEnd, pos + chunk);
                            while (seg.get(ValueLayout.JAVA_BYTE, e - 1) != '\n') e++;
                            pos = scanSegment(seg, pos, e);
                        }
                        byte[] tail = new byte[(int) (size - safeEnd) + pad];
                        MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, safeEnd, tail, 0, (int) (size - safeEnd));
                        scanArray(tail, 0, (int) (size - safeEnd));
                    }
                }
                case "direct" -> {
                    ByteBuffer bb = ByteBuffer.allocateDirect(chunk + pad).order(ByteOrder.LITTLE_ENDIAN);
                    MemorySegment seg = MemorySegment.ofBuffer(bb.duplicate().clear());
                    int carry = 0;
                    while (true) {
                        bb.limit(chunk).position(carry);
                        int n = ch.read(bb);
                        if (n < 0) break;
                        int filled = carry + n;
                        int nl = filled - 1;
                        while (nl >= 0 && bb.get(nl) != '\n') nl--;
                        if (nl < 0) throw new IOException("line longer than chunk");
                        scanSegment(seg, 0, nl + 1);
                        carry = filled - (nl + 1);
                        MemorySegment.copy(seg, nl + 1, seg, 0, carry);
                    }
                }
                default -> throw new IllegalArgumentException(mode);
            }
            long t1 = System.nanoTime();
            System.out.printf("mode=%s bytes=%d apps=%d others=%d checksum=%d wall=%.3fs %.0f MB/s%n",
                    mode, size, apps, others, checksum, (t1 - t0) / 1e9, size / 1e6 / ((t1 - t0) / 1e9));
        }
    }
}
