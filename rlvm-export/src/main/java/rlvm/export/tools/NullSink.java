package rlvm.export.tools;

import rlvm.export.ExportSink;

/**
 * A sink that stores nothing: it only numbers names, levels and expressions consecutively. Used to
 * measure the reader alone. (It does not detect duplicate declarations, so it is not a valid sink for
 * checking.)
 */
public final class NullSink implements ExportSink {
    private int names = 1;
    private int levels = 1;
    private int exprs;
    private long decls;

    public long declarations() {
        return decls;
    }

    @Override
    public void meta(boolean wellFormed, String a, String b, String c, String d, String e) {}

    @Override
    public int nameAnonymous() {
        return 1;
    }

    @Override
    public int nameStr(int prefix, byte[] utf8, int off, int len) {
        return ++names;
    }

    @Override
    public int nameNum(int prefix, long value) {
        return ++names;
    }

    @Override
    public int nameNumBig(int prefix, byte[] digits, int off, int len) {
        return ++names;
    }

    @Override
    public int levelZero() {
        return 1;
    }

    @Override
    public int levelSucc(int level) {
        return ++levels;
    }

    @Override
    public int levelMax(int lhs, int rhs) {
        return ++levels;
    }

    @Override
    public int levelIMax(int lhs, int rhs) {
        return ++levels;
    }

    @Override
    public int levelParam(int name) {
        return ++levels;
    }

    @Override
    public int exprBVar(int index) {
        return ++exprs;
    }

    @Override
    public int exprSort(int level) {
        return ++exprs;
    }

    @Override
    public int exprConst(int name, int[] ls, int off, int count) {
        return ++exprs;
    }

    @Override
    public int exprApp(int fn, int arg) {
        return ++exprs;
    }

    @Override
    public int exprLam(int binderName, int binderInfo, int type, int body) {
        return ++exprs;
    }

    @Override
    public int exprForall(int binderName, int binderInfo, int type, int body) {
        return ++exprs;
    }

    @Override
    public int exprLet(int binderName, int type, int value, int body, boolean nondep) {
        return ++exprs;
    }

    @Override
    public int exprProj(int typeName, long index, int struct) {
        return ++exprs;
    }

    @Override
    public int exprNatLit(byte[] digits, int off, int len) {
        return ++exprs;
    }

    @Override
    public int exprStrLit(byte[] utf8, int off, int len) {
        return ++exprs;
    }

    @Override
    public int exprMData(int expr) {
        return ++exprs;
    }

    @Override
    public void declAxiom(int name, int[] ids, int lpOff, int lpCount, int type, boolean isUnsafe) {
        decls++;
    }

    @Override
    public void declDef(int name, int[] ids, int lpOff, int lpCount, int type, int value, int hintKind,
            int hintHeight, int safety, int allOff, int allCount) {
        decls++;
    }

    @Override
    public void declThm(int name, int[] ids, int lpOff, int lpCount, int type, int value, int allOff, int allCount) {
        decls++;
    }

    @Override
    public void declOpaque(int name, int[] ids, int lpOff, int lpCount, int type, int value, boolean isUnsafe,
            int allOff, int allCount) {
        decls++;
    }

    @Override
    public void declQuot(int name, int[] ids, int lpOff, int lpCount, int type, int kind) {
        decls++;
    }

    @Override
    public void inductBegin(int numTypes, int numCtors, int numRecs) {
        decls++;
    }

    @Override
    public void inductType(int name, int[] ids, int lpOff, int lpCount, int type, long numParams, long numIndices,
            int allOff, int allCount, int ctorsOff, int ctorsCount, long numNested, boolean isRec,
            boolean isUnsafe, boolean isReflexive) {}

    @Override
    public void inductCtor(int name, int[] ids, int lpOff, int lpCount, int type, int induct, long cidx,
            long numParams, long numFields, boolean isUnsafe) {}

    @Override
    public void inductRec(int name, int[] ids, int lpOff, int lpCount, int type, int allOff, int allCount,
            long numParams, long numIndices, long numMotives, long numMinors, boolean k, boolean isUnsafe,
            int numRules) {}

    @Override
    public void inductRecRule(int ctor, long numFields, int rhs) {}

    @Override
    public void inductEnd() {}
}
