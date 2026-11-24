package rlvm.export;

import java.util.Arrays;

/**
 * A recording of {@link RecordOut} calls, used by the parallel mode: scanner threads fill one tape per
 * chunk of the file, the reading thread replays the tapes in file order into the {@link Linker}. The
 * replay makes exactly the calls the scanner made, so the sink sees the same sequence as in the
 * single-threaded mode.
 *
 * <p>Layout: {@code int} records {@code [opcode | flags << 8, operands...]}; offsets refer to the buffer
 * of the chunk the tape was recorded from.
 */
final class Tape implements RecordOut {
    private static final int APP = 1;
    private static final int LAM = 2;
    private static final int PI = 3;
    private static final int LET = 4;
    private static final int PROJ = 5;
    private static final int CONST = 6;
    private static final int NAME_STR = 7;
    private static final int NAME_NUM = 8;
    private static final int SLOW = 9;
    private static final int THM = 10;
    private static final int DEF = 11;
    /**
     * Header of a packed run ({@link RunPacker}): {@code [RUN, firstId, count, minLocal, maxName,
     * numLam, numPi, numLet, packedOff, packedLen, extOff, extCount, skip]}, followed by the run's
     * ordinary entries ({@code skip} ints), which the replay skips when the receiver takes the run.
     */
    static final int RUN = 12;
    static final int RUN_INTS = 13;

    static final int OP_APP = APP;
    static final int OP_LAM = LAM;
    static final int OP_PI = PI;
    static final int OP_LET = LET;
    static final int OP_PROJ = PROJ;
    static final int OP_CONST = CONST;
    static final int OP_NAME_STR = NAME_STR;
    static final int OP_NAME_NUM = NAME_NUM;
    static final int OP_SLOW = SLOW;

    int[] buf;
    int len;
    private int[] scratch = new int[64];
    /** Packed runs of this tape: record bytes and external children (see {@link RunPacker}). */
    byte[] packed = new byte[0];
    int packedLen;
    int[] ext = new int[0];
    int extLen;

    Tape(int capacity) {
        buf = new int[Math.max(capacity, 64)];
    }

    void reset() {
        len = 0;
        packedLen = 0;
        extLen = 0;
    }

    /** Appends the entries {@code src[from .. to)} of another tape as they are. */
    void appendEntries(int[] src, int from, int to) {
        int n = to - from;
        int[] b = room(n);
        System.arraycopy(src, from, b, len, n);
        len += n;
    }

    /** Appends the header of a packed run whose ordinary entries ({@code skip} ints) follow. */
    void runHeader(int firstId, int count, int minLocal, int maxName, int numLam, int numPi, int numLet,
            int packedOff, int packedBytes, int extOff, int extCount, int skip) {
        int[] b = room(RUN_INTS);
        int n = len;
        b[n] = RUN;
        b[n + 1] = firstId;
        b[n + 2] = count;
        b[n + 3] = minLocal;
        b[n + 4] = maxName;
        b[n + 5] = numLam;
        b[n + 6] = numPi;
        b[n + 7] = numLet;
        b[n + 8] = packedOff;
        b[n + 9] = packedBytes;
        b[n + 10] = extOff;
        b[n + 11] = extCount;
        b[n + 12] = skip;
        len = n + RUN_INTS;
    }

    /** Room for {@code n} more packed bytes; returns the array. */
    byte[] packedRoom(int n) {
        if (packedLen + n > packed.length) {
            long cap = Math.max(Math.max((long) packed.length * 2, 1 << 12), (long) packedLen + n);
            if (cap > Integer.MAX_VALUE - 16) {
                throw new IllegalStateException("tape overflow");
            }
            packed = Arrays.copyOf(packed, (int) cap);
        }
        return packed;
    }

    /** Room for {@code n} more external children; returns the array. */
    int[] extRoom(int n) {
        if (extLen + n > ext.length) {
            long cap = Math.max(Math.max((long) ext.length * 2, 1 << 10), (long) extLen + n);
            if (cap > Integer.MAX_VALUE - 16) {
                throw new IllegalStateException("tape overflow");
            }
            ext = Arrays.copyOf(ext, (int) cap);
        }
        return ext;
    }

    private int[] room(int n) {
        if (len + n > buf.length) {
            long cap = Math.max((long) buf.length * 2, (long) len + n);
            if (cap > Integer.MAX_VALUE - 16) {
                throw new IllegalStateException("tape overflow");
            }
            buf = Arrays.copyOf(buf, (int) cap);
        }
        return buf;
    }

    @Override
    public void app(long id, long fn, long arg) {
        int[] b = room(4);
        int n = len;
        b[n] = APP;
        b[n + 1] = (int) id;
        b[n + 2] = (int) fn;
        b[n + 3] = (int) arg;
        len = n + 4;
    }

    @Override
    public void lam(long id, int binderInfo, long name, long type, long body) {
        int[] b = room(5);
        int n = len;
        b[n] = LAM | (binderInfo << 8);
        b[n + 1] = (int) id;
        b[n + 2] = (int) name;
        b[n + 3] = (int) type;
        b[n + 4] = (int) body;
        len = n + 5;
    }

    @Override
    public void pi(long id, int binderInfo, long name, long type, long body) {
        int[] b = room(5);
        int n = len;
        b[n] = PI | (binderInfo << 8);
        b[n + 1] = (int) id;
        b[n + 2] = (int) name;
        b[n + 3] = (int) type;
        b[n + 4] = (int) body;
        len = n + 5;
    }

    @Override
    public void let(long id, long name, boolean nondep, long type, long value, long body) {
        int[] b = room(6);
        int n = len;
        b[n] = LET | (nondep ? 1 << 8 : 0);
        b[n + 1] = (int) id;
        b[n + 2] = (int) name;
        b[n + 3] = (int) type;
        b[n + 4] = (int) value;
        b[n + 5] = (int) body;
        len = n + 6;
    }

    @Override
    public void proj(long id, long idx, long struct, long typeName) {
        int[] b = room(5);
        int n = len;
        b[n] = PROJ;
        b[n + 1] = (int) id;
        b[n + 2] = (int) idx;
        b[n + 3] = (int) struct;
        b[n + 4] = (int) typeName;
        len = n + 5;
    }

    @Override
    public void constant(long id, long name, int[] us, int off, int count) {
        int[] b = room(4 + count);
        int n = len;
        b[n] = CONST;
        b[n + 1] = (int) id;
        b[n + 2] = (int) name;
        b[n + 3] = count;
        System.arraycopy(us, off, b, n + 4, count);
        len = n + 4 + count;
    }

    @Override
    public void nameStr(long id, long pre, int off, int strLen) {
        int[] b = room(5);
        int n = len;
        b[n] = NAME_STR;
        b[n + 1] = (int) id;
        b[n + 2] = (int) pre;
        b[n + 3] = off;
        b[n + 4] = strLen;
        len = n + 5;
    }

    @Override
    public void nameNum(long id, long pre, long i) {
        int[] b = room(5);
        int n = len;
        b[n] = NAME_NUM;
        b[n + 1] = (int) id;
        b[n + 2] = (int) pre;
        b[n + 3] = (int) i;
        b[n + 4] = (int) (i >>> 32);
        len = n + 5;
    }

    @Override
    public void thm(long name, long type, long value, int[] ids, int numAll, int numParams) {
        int count = numAll + numParams;
        int[] b = room(6 + count);
        int n = len;
        b[n] = THM;
        b[n + 1] = (int) name;
        b[n + 2] = (int) type;
        b[n + 3] = (int) value;
        b[n + 4] = numAll;
        b[n + 5] = numParams;
        System.arraycopy(ids, 0, b, n + 6, count);
        len = n + 6 + count;
    }

    @Override
    public void def(long name, long type, long value, int hintKind, long hintHeight, int[] ids, int numAll,
            int numParams) {
        int count = numAll + numParams;
        int[] b = room(7 + count);
        int n = len;
        b[n] = DEF | (hintKind << 8);
        b[n + 1] = (int) name;
        b[n + 2] = (int) type;
        b[n + 3] = (int) value;
        b[n + 4] = (int) hintHeight;
        b[n + 5] = numAll;
        b[n + 6] = numParams;
        System.arraycopy(ids, 0, b, n + 7, count);
        len = n + 7 + count;
    }

    @Override
    public void slow(int off, int lineLen) {
        int[] b = room(3);
        int n = len;
        b[n] = SLOW;
        b[n + 1] = off;
        b[n + 2] = lineLen;
        len = n + 3;
    }

    /** Makes the recorded calls, in order, on {@code out}. */
    void replay(RecordOut out) throws ExportException {
        int[] b = buf;
        int end = len;
        int p = 0;
        while (p < end) {
            int h = b[p];
            switch (h & 0xFF) {
                case APP -> {
                    out.app(b[p + 1], b[p + 2], b[p + 3]);
                    p += 4;
                }
                case LAM -> {
                    out.lam(b[p + 1], h >>> 8, b[p + 2], b[p + 3], b[p + 4]);
                    p += 5;
                }
                case PI -> {
                    out.pi(b[p + 1], h >>> 8, b[p + 2], b[p + 3], b[p + 4]);
                    p += 5;
                }
                case LET -> {
                    out.let(b[p + 1], b[p + 2], (h >>> 8) != 0, b[p + 3], b[p + 4], b[p + 5]);
                    p += 6;
                }
                case PROJ -> {
                    out.proj(b[p + 1], b[p + 2], b[p + 3], b[p + 4]);
                    p += 5;
                }
                case CONST -> {
                    int count = b[p + 3];
                    out.constant(b[p + 1], b[p + 2], b, p + 4, count);
                    p += 4 + count;
                }
                case NAME_STR -> {
                    out.nameStr(b[p + 1], b[p + 2], b[p + 3], b[p + 4]);
                    p += 5;
                }
                case NAME_NUM -> {
                    out.nameNum(b[p + 1], b[p + 2], (b[p + 3] & 0xFFFF_FFFFL) | ((long) b[p + 4] << 32));
                    p += 5;
                }
                case SLOW -> {
                    out.slow(b[p + 1], b[p + 2]);
                    p += 3;
                }
                case THM -> {
                    int numAll = b[p + 4];
                    int numParams = b[p + 5];
                    if (scratch.length < numAll + numParams) {
                        scratch = new int[2 * (numAll + numParams)];
                    }
                    System.arraycopy(b, p + 6, scratch, 0, numAll + numParams);
                    out.thm(b[p + 1], b[p + 2], b[p + 3], scratch, numAll, numParams);
                    p += 6 + numAll + numParams;
                }
                case DEF -> {
                    int numAll = b[p + 5];
                    int numParams = b[p + 6];
                    if (scratch.length < numAll + numParams) {
                        scratch = new int[2 * (numAll + numParams)];
                    }
                    System.arraycopy(b, p + 7, scratch, 0, numAll + numParams);
                    out.def(b[p + 1], b[p + 2], b[p + 3], h >>> 8, b[p + 4], scratch, numAll, numParams);
                    p += 7 + numAll + numParams;
                }
                case RUN -> {
                    boolean taken = out.run(b[p + 1], b[p + 2], b[p + 3], b[p + 4], b[p + 5], b[p + 6], b[p + 7],
                            packed, b[p + 8], b[p + 9], ext, b[p + 10], b[p + 11]);
                    // not taken: the run's ordinary entries follow and are replayed like any others
                    p += taken ? RUN_INTS + b[p + 12] : RUN_INTS;
                }
                default -> throw new IllegalStateException("corrupt tape");
            }
        }
    }
}
