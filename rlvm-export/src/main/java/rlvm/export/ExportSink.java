package rlvm.export;

/**
 * Receiver of the records of one lean4export NDJSON file, in file order.
 *
 * <h2>Ids</h2>
 * Every method that creates a name, level or expression <em>returns the id the sink assigns to it</em>;
 * the reader keeps the translation "id in the file" to "sink id" and passes only sink ids back in.
 * A sink is therefore free to number densely, to hash-cons (return an existing id), or to drop a node
 * (e.g. return the wrapped expression for {@code mdata}). Rules:
 * <ul>
 *   <li>ids are {@code int}s other than {@link Integer#MIN_VALUE}; non-negative ids are recommended;</li>
 *   <li>the reader guarantees "defined before use": every id passed in was returned by an earlier call
 *       (or by {@link #nameAnonymous()} / {@link #levelZero()});</li>
 *   <li>if the sink numbers each id space consecutively in call order (id = first id + number of
 *       earlier calls) and the file's ids are dense and in order - true for every file written by
 *       lean4export - the reader needs no translation table at all. Any other behaviour is handled
 *       transparently with tables.</li>
 * </ul>
 *
 * <h2>Obligations of the sink</h2>
 * The reader rejects everything the official reader rejects <em>except</em> what needs structural name
 * equality: a sink must reject (throw {@link ExportException} of kind
 * {@link ExportException.Kind#MALFORMED}) a second declaration of a constant name (all of
 * {@code decl*}, {@code inductType}, {@code inductCtor}, {@code inductRec} declare one constant each;
 * names are equal when they are structurally equal, whatever their ids in the file).
 *
 * <h2>Data passed by reference</h2>
 * {@code byte[]} and {@code int[]} arguments are scratch buffers owned by the reader. They are valid
 * only during the call and must not be modified. Strings are well-formed UTF-8 with all JSON escapes
 * resolved (they may contain U+0000). Decimal digit ranges are canonical: ASCII digits, no leading
 * zero unless the number is 0, at least one digit.
 *
 * <h2>Numbers</h2>
 * Fields that are natural numbers in the format and may legitimately be large are {@code long}s in
 * {@code [0, 2^63)}; the reader declines ({@link ExportException.Kind#UNSUPPORTED}) larger values,
 * except for the two cases that have their own "big" form: numeric name components and Nat literals.
 *
 * <p>All methods are called from the single thread that called the reader, also in parallel mode.
 */
public interface ExportSink {
    int BINDER_DEFAULT = 0;
    int BINDER_IMPLICIT = 1;
    int BINDER_STRICT_IMPLICIT = 2;
    int BINDER_INST_IMPLICIT = 3;

    int HINT_OPAQUE = 0;
    int HINT_ABBREV = 1;
    int HINT_REGULAR = 2;

    int SAFETY_UNSAFE = 0;
    int SAFETY_SAFE = 1;
    int SAFETY_PARTIAL = 2;

    int QUOT_TYPE = 0;
    int QUOT_CTOR = 1;
    int QUOT_LIFT = 2;
    int QUOT_IND = 3;

    // ------------------------------------------------------------------------------------------ header

    /**
     * Line 1 of the file. The official reader discards it unread, so its content never decides
     * between accept and reject by itself; a checker may use it to decline unknown format versions.
     *
     * @param wellFormed whether line 1 is a JSON object of the form {@code {"meta":{...}}}; if not, all
     *     strings are {@code null}
     * @param exporterName {@code meta.exporter.name} or {@code null} if absent or not a string
     * @param exporterVersion {@code meta.exporter.version}
     * @param formatVersion {@code meta.format.version}
     * @param leanGithash {@code meta.lean.githash}
     * @param leanVersion {@code meta.lean.version}
     */
    void meta(
            boolean wellFormed,
            String exporterName,
            String exporterVersion,
            String formatVersion,
            String leanGithash,
            String leanVersion)
            throws ExportException;

    // ------------------------------------------------------------------------------------------- names

    /** The id of {@code Name.anonymous} (id 0 of the file). Called once, before any record. */
    int nameAnonymous() throws ExportException;

    /** {@code Name.str prefix s}. */
    int nameStr(int prefix, byte[] utf8, int off, int len) throws ExportException;

    /** {@code Name.num prefix value} with {@code 0 <= value < 2^63}. */
    int nameNum(int prefix, long value) throws ExportException;

    /** {@code Name.num prefix value} with {@code value >= 2^63}, as canonical decimal digits. */
    int nameNumBig(int prefix, byte[] digits, int off, int len) throws ExportException;

    // ------------------------------------------------------------------------------------------ levels

    /** The id of {@code Level.zero} (id 0 of the file). Called once, before any record. */
    int levelZero() throws ExportException;

    int levelSucc(int level) throws ExportException;

    int levelMax(int lhs, int rhs) throws ExportException;

    int levelIMax(int lhs, int rhs) throws ExportException;

    int levelParam(int name) throws ExportException;

    // ------------------------------------------------------------------------------------- expressions

    /**
     * {@code Expr.bvar index}. {@code 0 <= index <= 2^20 - 2}: the official reader aborts on larger
     * indices (its loose-bound-variable range is limited to 2^20 - 1), and so does this reader.
     */
    int exprBVar(int index) throws ExportException;

    int exprSort(int level) throws ExportException;

    /** {@code Expr.const name [levels[off], ..., levels[off + count - 1]]}. */
    int exprConst(int name, int[] levels, int off, int count) throws ExportException;

    int exprApp(int fn, int arg) throws ExportException;

    /** {@code binderInfo} is one of the {@code BINDER_*} constants. */
    int exprLam(int binderName, int binderInfo, int type, int body) throws ExportException;

    int exprForall(int binderName, int binderInfo, int type, int body) throws ExportException;

    int exprLet(int binderName, int type, int value, int body, boolean nondep) throws ExportException;

    /** {@code Expr.proj typeName index struct}. */
    int exprProj(int typeName, long index, int struct) throws ExportException;

    /** A Nat literal as canonical decimal digits (any length). */
    int exprNatLit(byte[] digits, int off, int len) throws ExportException;

    /** A String literal as UTF-8. */
    int exprStrLit(byte[] utf8, int off, int len) throws ExportException;

    /**
     * {@code Expr.mdata} around {@code expr}; the metadata itself is not part of the format's meaning
     * (the official reader builds an empty-metadata node). Only produced by {@code --export-mdata}.
     */
    int exprMData(int expr) throws ExportException;

    /** {@link #exprRun}: the sink does not take the run; the reader delivers its records one by one. */
    int RUN_REFUSED = Integer.MIN_VALUE;

    /**
     * Optional fast path of the reader for a stretch of consecutive {@code app} records, in place of
     * {@code count} calls of {@link #exprApp}: record {@code i} has the file id
     * {@code firstFileId + i}, its function is the expression with file id {@code recs[off + 3 * i + 1]}
     * and its argument the one with file id {@code recs[off + 3 * i + 2]} (the ints at
     * {@code off + 3 * i} are the file ids of the records themselves).
     *
     * <p>As for {@link #exprRun}, the reader offers a stretch only while the expression ids of the
     * file are dense, in order and numbered consecutively by the sink (sink id = file id +
     * {@code delta} for every expression so far, so the sink ids of the children are the file ids
     * plus {@code delta}), and only if both children of every record are earlier expressions. The
     * sink either takes the stretch - then it must behave exactly as if it had received the
     * {@code count} calls, and return the id of the first record, the others being the following
     * ids - or returns {@link #RUN_REFUSED} without any other effect, and the reader makes the
     * {@code count} calls. The array is the reader's scratch: valid during the call only.
     *
     * @return the sink id of the first record ({@code firstFileId + delta}), or {@link #RUN_REFUSED}
     */
    default int exprApps(int firstFileId, int count, int delta, int[] recs, int off) throws ExportException {
        return RUN_REFUSED;
    }

    /**
     * Optional fast path of the parallel mode with {@link ExportReader.Options#runs}: {@code count}
     * consecutive {@code app} / {@code lam} / {@code forallE} / {@code letE} records that a scanner
     * thread has already put into the PACKED form of {@link ExportReader.RunFormat}, in place of the
     * {@code count} calls {@link #exprApp}, {@link #exprLam}, {@link #exprForall}, {@link #exprLet}.
     *
     * <p>The reader offers a run only while the expression ids of the file are dense, in order and
     * numbered consecutively by the sink (sink id = file id + {@code delta} for every expression so
     * far), and only if every record of the run is well formed with respect to everything read
     * before it (all its references and binder names are bound). The sink either takes the run -
     * then it must behave exactly as if it had received the {@code count} calls, and return the id of
     * the first record, the others being the following ids - or returns {@link #RUN_REFUSED} without
     * any other effect, and the reader makes the {@code count} calls. Binder names, binder infos and
     * the {@code nondep} flag of the records are not part of the packed form: a sink that needs them
     * refuses.
     *
     * <p>{@code packed[off .. off+len)} holds the record bytes, {@code lengths[lengthsOff ..]} one
     * length byte per record, {@code open[openOff .. openOff + 2 * openCount)} the open children (two
     * ints each; the child is a FILE id: add {@code delta}); every LOCAL child is a record with a file
     * id of at least {@code minLocal}; every EARLY child has a value of at most {@code maxEarly} (0 if
     * the run has none). Unlike all other array arguments these stay valid, and {@code open} may be
     * overwritten by the sink, until {@link #exprRunsEnd()} is called or the read ends.
     *
     * @param firstFileId file id of the first record of the run
     * @param numLam number of {@code lam} records, {@code numPi} of {@code forallE}, {@code numLet} of
     *     {@code letE} records in the run (the others are {@code app} records)
     * @param numEarly number of EARLY children in the run
     * @return the sink id of the first record ({@code firstFileId + delta}), or {@link #RUN_REFUSED}
     */
    default int exprRun(int firstFileId, int count, int delta, int minLocal, byte[] packed, int off, int len,
            byte[] lengths, int lengthsOff, int[] open, int openOff, int openCount, int numLam, int numPi, int numLet,
            int numEarly, int maxEarly) throws ExportException {
        return RUN_REFUSED;
    }

    /**
     * The arrays of every run taken so far ({@link #exprRun}) are about to be reused by the reader: a
     * sink that still needs their content copies it now. The default does nothing.
     */
    default void exprRunsEnd() throws ExportException {
    }

    // ------------------------------------------------------------------------------------ declarations
    // Name lists (levelParams, all, ctors) are ranges of the scratch array `ids`.

    void declAxiom(int name, int[] ids, int lpOff, int lpCount, int type, boolean isUnsafe)
            throws ExportException;

    /**
     * @param hintKind one of the {@code HINT_*} constants
     * @param hintHeight the height of a {@code regular} hint as an unsigned 32-bit value (the official
     *     reader reduces the number modulo 2^32); 0 for the other kinds
     * @param safety one of the {@code SAFETY_*} constants
     */
    void declDef(
            int name,
            int[] ids,
            int lpOff,
            int lpCount,
            int type,
            int value,
            int hintKind,
            int hintHeight,
            int safety,
            int allOff,
            int allCount)
            throws ExportException;

    void declThm(int name, int[] ids, int lpOff, int lpCount, int type, int value, int allOff, int allCount)
            throws ExportException;

    void declOpaque(
            int name,
            int[] ids,
            int lpOff,
            int lpCount,
            int type,
            int value,
            boolean isUnsafe,
            int allOff,
            int allCount)
            throws ExportException;

    /** {@code kind} is one of the {@code QUOT_*} constants. */
    void declQuot(int name, int[] ids, int lpOff, int lpCount, int type, int kind) throws ExportException;

    /**
     * Start of one {@code inductive} line (a mutual block). It is followed by exactly
     * {@code numTypes} calls of {@link #inductType}, then {@code numCtors} calls of {@link #inductCtor},
     * then {@code numRecs} times ({@link #inductRec} followed by its {@link #inductRecRule}s), then
     * {@link #inductEnd()} - the order in which the official reader registers the constants.
     */
    void inductBegin(int numTypes, int numCtors, int numRecs) throws ExportException;

    void inductType(
            int name,
            int[] ids,
            int lpOff,
            int lpCount,
            int type,
            long numParams,
            long numIndices,
            int allOff,
            int allCount,
            int ctorsOff,
            int ctorsCount,
            long numNested,
            boolean isRec,
            boolean isUnsafe,
            boolean isReflexive)
            throws ExportException;

    void inductCtor(
            int name,
            int[] ids,
            int lpOff,
            int lpCount,
            int type,
            int induct,
            long cidx,
            long numParams,
            long numFields,
            boolean isUnsafe)
            throws ExportException;

    /** Followed by {@code numRules} calls of {@link #inductRecRule}. */
    void inductRec(
            int name,
            int[] ids,
            int lpOff,
            int lpCount,
            int type,
            int allOff,
            int allCount,
            long numParams,
            long numIndices,
            long numMotives,
            long numMinors,
            boolean k,
            boolean isUnsafe,
            int numRules)
            throws ExportException;

    /** One rule of the recursor announced by the preceding {@link #inductRec}. */
    void inductRecRule(int ctor, long numFields, int rhs) throws ExportException;

    void inductEnd() throws ExportException;
}
