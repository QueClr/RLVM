package rlvm.load;

/**
 * The record stream of one export file, in file order: a method-for-method MIRROR of
 * {@code rlvm.export.ExportSink} (same names, same parameters, same id protocol), declared here when
 * this module could not yet depend on {@code rlvm-export}.
 *
 * <p>Superseded: {@link Loader} now implements {@code rlvm.export.ExportSink} directly (and, for
 * source compatibility, still this interface). The only difference between the two is that the
 * loader's failures are the unchecked {@link MalformedExportException} /
 * {@link UnsupportedExportException} instead of the checked {@code ExportException}. Use
 * {@code ExportSink} in new code; this interface will be removed.
 *
 * <p>Id protocol (from {@code ExportSink}): every method that creates a name, level or expression
 * returns the id the SINK assigns; the reader keeps the translation from the file's ids and passes
 * only sink ids back in, guaranteeing "defined before use". The loader returns: store name ids
 * (structurally interned), level ids of the level store, and for expressions the RECORD ORDINAL
 * (1, 2, 3, ... in call order), so that the reader needs no translation table for a canonical file.
 *
 * <p>{@code byte[]} / {@code int[]} arguments are the reader's scratch buffers, valid during the call.
 * Name lists (levelParams, all, ctors) are ranges of the scratch array {@code ids}.
 */
@Deprecated
public interface RecordSink {

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

    /** Line 1. {@code wellFormed} is false for a line that is not {@code {"meta":{...}}}; strings are then null. */
    void meta(boolean wellFormed, String exporterName, String exporterVersion, String formatVersion,
            String leanGithash, String leanVersion);

    int nameAnonymous();

    int nameStr(int prefix, byte[] utf8, int off, int len);

    int nameNum(int prefix, long value);

    /** Numeric component of 2^63 or more, as canonical decimal digits. */
    int nameNumBig(int prefix, byte[] digits, int off, int len);

    int levelZero();

    int levelSucc(int level);

    int levelMax(int lhs, int rhs);

    int levelIMax(int lhs, int rhs);

    int levelParam(int name);

    int exprBVar(int index);

    int exprSort(int level);

    int exprConst(int name, int[] levels, int off, int count);

    int exprApp(int fn, int arg);

    int exprLam(int binderName, int binderInfo, int type, int body);

    int exprForall(int binderName, int binderInfo, int type, int body);

    int exprLet(int binderName, int type, int value, int body, boolean nondep);

    int exprProj(int typeName, long index, int struct);

    int exprNatLit(byte[] digits, int off, int len);

    int exprStrLit(byte[] utf8, int off, int len);

    /** The loader returns {@code expr} itself: metadata is dropped. */
    int exprMData(int expr);

    void declAxiom(int name, int[] ids, int lpOff, int lpCount, int type, boolean isUnsafe);

    void declDef(int name, int[] ids, int lpOff, int lpCount, int type, int value, int hintKind, int hintHeight,
            int safety, int allOff, int allCount);

    void declThm(int name, int[] ids, int lpOff, int lpCount, int type, int value, int allOff, int allCount);

    void declOpaque(int name, int[] ids, int lpOff, int lpCount, int type, int value, boolean isUnsafe, int allOff,
            int allCount);

    void declQuot(int name, int[] ids, int lpOff, int lpCount, int type, int kind);

    /**
     * Start of one {@code inductive} line: followed by {@code numTypes} x {@link #inductType}, {@code numCtors}
     * x {@link #inductCtor}, {@code numRecs} x ({@link #inductRec} and its rules), {@link #inductEnd()}.
     */
    void inductBegin(int numTypes, int numCtors, int numRecs);

    void inductType(int name, int[] ids, int lpOff, int lpCount, int type, long numParams, long numIndices,
            int allOff, int allCount, int ctorsOff, int ctorsCount, long numNested, boolean isRec, boolean isUnsafe,
            boolean isReflexive);

    void inductCtor(int name, int[] ids, int lpOff, int lpCount, int type, int induct, long cidx, long numParams,
            long numFields, boolean isUnsafe);

    void inductRec(int name, int[] ids, int lpOff, int lpCount, int type, int allOff, int allCount, long numParams,
            long numIndices, long numMotives, long numMinors, boolean k, boolean isUnsafe, int numRules);

    void inductRecRule(int ctor, long numFields, int rhs);

    void inductEnd();
}
