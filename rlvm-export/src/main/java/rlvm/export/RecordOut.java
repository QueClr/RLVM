package rlvm.export;

/**
 * What the template fast path ({@link Scanner}) produces: one call per line, in file order, with the
 * ids exactly as written in the file (all in {@code [0, 10^9)}, so they fit an {@code int}).
 *
 * <p>Two implementations: {@link Linker} (translate ids and call the sink at once) and {@link Tape}
 * (record the calls so that another thread can replay them into a {@link Linker} later). Offsets are
 * positions in the buffer that is being scanned.
 */
interface RecordOut {
    void app(long id, long fn, long arg) throws ExportException;

    void lam(long id, int binderInfo, long name, long type, long body) throws ExportException;

    void pi(long id, int binderInfo, long name, long type, long body) throws ExportException;

    void let(long id, long name, boolean nondep, long type, long value, long body) throws ExportException;

    void proj(long id, long idx, long struct, long typeName) throws ExportException;

    /** {@code us[off .. off+n-1]} are level ids; the array is scratch owned by the caller. */
    void constant(long id, long name, int[] us, int off, int n) throws ExportException;

    /** A name component without escapes, bytes {@code 0x20 .. 0x7F} only, at {@code [off, off+len)}. */
    void nameStr(long id, long pre, int off, int len) throws ExportException;

    /** {@code 0 <= i < 10^15}. */
    void nameNum(long id, long pre, long i) throws ExportException;

    /**
     * A theorem. {@code ids[0 .. numAll)} is the {@code all} list, {@code ids[numAll .. numAll+numParams)}
     * the {@code levelParams} list (name ids; scratch owned by the caller).
     */
    void thm(long name, long type, long value, int[] ids, int numAll, int numParams) throws ExportException;

    /** A definition with {@code safety = safe}; lists as for {@link #thm}; {@code hintHeight < 10^9}. */
    void def(long name, long type, long value, int hintKind, long hintHeight, int[] ids, int numAll, int numParams)
            throws ExportException;

    /**
     * A line the fast path does not handle, at {@code [off, off+len)} including its final newline:
     * to be parsed by the general JSON path.
     */
    void slow(int off, int len) throws ExportException;

    /**
     * Only made by the replay of a {@link Tape}: the next {@code count} records, which follow on the
     * tape as ordinary {@code app} / {@code lam} / {@code pi} / {@code let} calls with the consecutive
     * ids {@code firstId ..}, are also available in packed form ({@link ExportReader.RunFormat}; ids
     * as written in the file). Returns true if the receiver has consumed the run: the tape then skips
     * the {@code count} calls. The default takes nothing.
     *
     * @param minLocal every child encoded in the packed bytes has an id of at least this
     * @param maxName the largest binder name id of the run, -1 if it has no binder
     */
    default boolean run(int firstId, int count, int minLocal, int maxName, int numLam, int numPi, int numLet,
            byte[] packed, int off, int len, int[] ext, int extOff, int extCount) throws ExportException {
        return false;
    }
}
