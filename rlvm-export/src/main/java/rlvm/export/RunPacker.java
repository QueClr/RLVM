package rlvm.export;

/**
 * Scanner-thread side of the parallel packing ({@link ExportReader.Options#runs}): sits between the
 * {@link Scanner} and the {@link Tape} of a chunk and, where the chunk shows the shape lean4export
 * gives a theorem, adds the PACKED form of the proof's records to the tape.
 *
 * <p>lean4export writes the records a declaration needs right before its declaration line, children
 * first, the type's records before the value's. So at a {@code thm} line with type id T the records
 * since the previous declaration line with an id above T are the proof (finding F1 of scout X5). The
 * packer keeps the entries since the last declaration line in a window; at a template {@code thm} line
 * it copies the window to the tape and puts a run header ({@link Tape#RUN}) in front of every stretch
 * of at least {@link ExportReader.RunFormat#minRecords} consecutive {@code app} / {@code lam} /
 * {@code forallE} / {@code letE} entries with consecutive ids above the bound
 * {@code max(T, first expression id of the window - 1)}, whose children are all earlier records. The
 * packed form of such a run ({@link ExportReader.RunFormat}) has every child above the bound encoded
 * as the distance to it and lists the others as external children. At any other declaration line,
 * and at the end of the chunk, the window is copied as it is.
 *
 * <p><b>Nothing here decides anything.</b> A run header is an offer: the ordinary entries of the run
 * follow it on the tape, and the replay ({@link Tape#replay}) makes exactly the calls of the
 * single-threaded mode unless the {@link Linker} and the sink take the run, which they do only when
 * they have verified, on the reading thread and with the file's real state, what the packer merely
 * guessed (the ids are dense and in order, every binder name is bound; the sink checks what the bound
 * means for it). A file that is not exporter output, or a chunk border in an unlucky place, costs
 * offers that are refused, never a different result.
 */
final class RunPacker implements RecordOut {
    private static final long AXIOM_0 = Scanner.lit("{\"axiom\"");
    private static final long INDUCT_0 = Scanner.lit("{\"induct");
    private static final long OPAQUE_0 = Scanner.lit("{\"opaque");
    private static final long QUOT_0 = Scanner.lit("{\"quot\":");
    private static final long THM_0 = Scanner.lit("{\"thm\":{");
    private static final long DEF_0 = Scanner.lit("{\"def\":{");

    /** Whether the 8 bytes {@code w} are the start of a declaration line as lean4export writes it. */
    static boolean declarationStart(long w) {
        return w == THM_0 || w == DEF_0 || w == AXIOM_0 || w == INDUCT_0 || w == OPAQUE_0 || w == QUOT_0;
    }

    private final int tagApp;
    private final int tagLam;
    private final int tagPi;
    private final int tagLet;
    private final int minRecords;
    /** The entries since the last declaration line. */
    private final Tape win = new Tape(1 << 12);
    private Tape out;
    private byte[] src;

    RunPacker(ExportReader.RunFormat format) {
        this.tagApp = format.app;
        this.tagLam = format.lam;
        this.tagPi = format.pi;
        this.tagLet = format.let;
        this.minRecords = format.minRecords;
    }

    /** Starts a chunk: entries go to {@code tape}; {@code chunk} is the buffer the scanner reads. */
    void begin(Tape tape, byte[] chunk) {
        out = tape;
        src = chunk;
        win.reset();
    }

    /** Ends the chunk: what is left in the window is copied as it is. */
    void end() {
        flushPlain();
        out = null;
        src = null;
    }

    // ---- records: into the window ---------------------------------------------------------------------

    @Override
    public void app(long id, long fn, long arg) {
        win.app(id, fn, arg);
    }

    @Override
    public void lam(long id, int binderInfo, long name, long type, long body) {
        win.lam(id, binderInfo, name, type, body);
    }

    @Override
    public void pi(long id, int binderInfo, long name, long type, long body) {
        win.pi(id, binderInfo, name, type, body);
    }

    @Override
    public void let(long id, long name, boolean nondep, long type, long value, long body) {
        win.let(id, name, nondep, type, value, body);
    }

    @Override
    public void proj(long id, long idx, long struct, long typeName) {
        win.proj(id, idx, struct, typeName);
    }

    @Override
    public void constant(long id, long name, int[] us, int off, int n) {
        win.constant(id, name, us, off, n);
    }

    @Override
    public void nameStr(long id, long pre, int off, int len) {
        win.nameStr(id, pre, off, len);
    }

    @Override
    public void nameNum(long id, long pre, long i) {
        win.nameNum(id, pre, i);
    }

    // ---- declaration lines: the window ends -------------------------------------------------------------

    @Override
    public void thm(long name, long type, long value, int[] ids, int numAll, int numParams) {
        flushPacked((int) type);
        out.thm(name, type, value, ids, numAll, numParams);
    }

    @Override
    public void def(long name, long type, long value, int hintKind, long hintHeight, int[] ids, int numAll,
            int numParams) {
        flushPlain();
        out.def(name, type, value, hintKind, hintHeight, ids, numAll, numParams);
    }

    @Override
    public void slow(int off, int len) {
        // a line the templates do not cover: a level, a leaf, ... or a declaration (inductive, axiom,
        // opaque, quot, a thm or def in another form), which ends the window
        if (len >= 8 && declarationStart(Mem.u64(src, off))) {
            flushPlain();
            out.slow(off, len);
        } else {
            win.slow(off, len);
        }
    }

    private void flushPlain() {
        out.appendEntries(win.buf, 0, win.len);
        win.reset();
    }

    /** Number of ints of the window entry at {@code p}. */
    private static int size(int[] w, int p) {
        switch (w[p] & 0xFF) {
            case Tape.OP_APP:
                return 4;
            case Tape.OP_LAM:
            case Tape.OP_PI:
            case Tape.OP_PROJ:
            case Tape.OP_NAME_STR:
            case Tape.OP_NAME_NUM:
                return 5;
            case Tape.OP_LET:
                return 6;
            case Tape.OP_CONST:
                return 4 + w[p + 3];
            case Tape.OP_SLOW:
                return 3;
            default:
                throw new IllegalStateException("corrupt window");
        }
    }

    /** The window ends with a theorem whose type has file id {@code type}: offer its proof records as runs. */
    private void flushPacked(int type) {
        final int[] w = win.buf;
        final int end = win.len;
        // the first expression entry of the window: records before it belong to earlier declarations
        int first = -1;
        for (int p = 0; p < end; p += size(w, p)) {
            int op = w[p] & 0xFF;
            if (op == Tape.OP_APP || op == Tape.OP_LAM || op == Tape.OP_PI || op == Tape.OP_LET || op == Tape.OP_PROJ
                    || op == Tape.OP_CONST) {
                first = w[p + 1];
                break;
            }
        }
        if (first < 0) {
            flushPlain();
            return;
        }
        final int bound = Math.max(type, first - 1);
        final Tape o = out;
        int copied = 0;
        int p = 0;
        while (p < end) {
            int op = w[p] & 0xFF;
            if ((op != Tape.OP_APP && op != Tape.OP_LAM && op != Tape.OP_PI && op != Tape.OP_LET) || w[p + 1] <= bound) {
                p += size(w, p);
                continue;
            }
            final int runStart = p;
            final int firstId = w[p + 1];
            final int packed0 = o.packedLen;
            final int ext0 = o.extLen;
            int count = 0;
            int maxName = -1;
            int numLam = 0;
            int numPi = 0;
            int numLet = 0;
            while (p < end) {
                op = w[p] & 0xFF;
                if (op == Tape.OP_APP) {
                    if (w[p + 1] != firstId + count || !pack(o, tagApp, firstId + count, bound, w[p + 2], w[p + 3], -1)) {
                        break;
                    }
                    p += 4;
                } else if (op == Tape.OP_LAM || op == Tape.OP_PI) {
                    if (w[p + 1] != firstId + count
                            || !pack(o, op == Tape.OP_LAM ? tagLam : tagPi, firstId + count, bound, w[p + 3], w[p + 4], -1)) {
                        break;
                    }
                    if (op == Tape.OP_LAM) {
                        numLam++;
                    } else {
                        numPi++;
                    }
                    maxName = Math.max(maxName, w[p + 2]);
                    p += 5;
                } else if (op == Tape.OP_LET) {
                    if (w[p + 1] != firstId + count || !pack(o, tagLet, firstId + count, bound, w[p + 3], w[p + 4], w[p + 5])) {
                        break;
                    }
                    numLet++;
                    maxName = Math.max(maxName, w[p + 2]);
                    p += 6;
                } else {
                    break;
                }
                count++;
            }
            if (count >= minRecords) {
                o.appendEntries(w, copied, runStart);
                o.runHeader(firstId, count, bound + 1, maxName, numLam, numPi, numLet, packed0, o.packedLen - packed0, ext0,
                        o.extLen - ext0, p - runStart);
                o.appendEntries(w, runStart, p);
                copied = p;
            } else {
                o.packedLen = packed0;
                o.extLen = ext0;
                if (count == 0) {
                    p += size(w, p); // the entry cannot be packed: it stays an ordinary entry
                }
            }
        }
        o.appendEntries(w, copied, end);
        win.reset();
    }

    /**
     * Appends the packed form of the record {@code id} with children {@code c0, c1} and, unless it is
     * -1, {@code c2}; false (nothing appended) if a child is not an earlier record.
     */
    private static boolean pack(Tape o, int tag, int id, int bound, int c0, int c1, int c2) {
        if (c0 < 0 || c0 >= id || c1 < 0 || c1 >= id || c2 >= id || c2 < -1) {
            return false;
        }
        byte[] pk = o.packedRoom(16);
        int[] ext = o.extRoom(3);
        int q = o.packedLen;
        int e = o.extLen;
        final int headAt = q++;
        int head = tag;
        if (c0 > bound) {
            q = varint(pk, q, (id - c0) << 1);
        } else {
            head |= 0x10;
            ext[e++] = c0;
        }
        if (c1 > bound) {
            q = varint(pk, q, (id - c1) << 1);
        } else {
            head |= 0x20;
            ext[e++] = c1;
        }
        if (c2 >= 0) {
            if (c2 > bound) {
                q = varint(pk, q, (id - c2) << 1);
            } else {
                head |= 0x40;
                ext[e++] = c2;
            }
        }
        pk[headAt] = (byte) head;
        o.packedLen = q;
        o.extLen = e;
        return true;
    }

    private static int varint(byte[] b, int q, int v) {
        while ((v & ~0x7F) != 0) {
            b[q++] = (byte) (v | 0x80);
            v >>>= 7;
        }
        b[q++] = (byte) v;
        return q;
    }
}
