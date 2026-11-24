package rlvm.export;

import java.nio.charset.StandardCharsets;

/**
 * Test sink: numbers names (from 2), levels (from 2) and expressions (from 1) consecutively and records
 * every call, as text (for small inputs) and as a running 64-bit hash (always).
 */
class TraceSink implements ExportSink {
    private final StringBuilder text;
    long hash = 0x243F6A8885A308D3L;
    long events;
    int names = 1;
    int levels = 1;
    int exprs;
    long constants;

    TraceSink(boolean keepText) {
        this.text = keepText ? new StringBuilder() : null;
    }

    String text() {
        return text.toString();
    }

    private void mix(long v) {
        long h = (hash ^ v) * 0x9E3779B97F4A7C15L;
        hash = h ^ (h >>> 29);
    }

    private void bytes(byte[] b, int off, int len) {
        mix(len);
        for (int i = 0; i < len; i++) {
            mix(b[off + i]);
        }
    }

    private void ev(String name, long... args) {
        events++;
        mix(name.hashCode());
        for (long a : args) {
            mix(a);
        }
        if (text != null) {
            text.append(name);
            for (long a : args) {
                text.append(' ').append(a);
            }
            text.append('\n');
        }
    }

    private void evBytes(String name, byte[] b, int off, int len, long... args) {
        ev(name, args);
        bytes(b, off, len);
        if (text != null) {
            text.setLength(text.length() - 1);
            text.append(" \"");
            String s = new String(b, off, len, StandardCharsets.UTF_8);
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c < 0x20 || c == '"' || c == '\\' || c > 0x7E) {
                    text.append(String.format("\\u%04x", (int) c));
                } else {
                    text.append(c);
                }
            }
            text.append("\"\n");
        }
    }

    private void evList(String name, int[] ids, int off, int count) {
        ev(name, count);
        for (int i = 0; i < count; i++) {
            mix(ids[off + i]);
        }
        if (text != null) {
            text.setLength(text.length() - 1);
            text.append(" [");
            for (int i = 0; i < count; i++) {
                text.append(i == 0 ? "" : ",").append(ids[off + i]);
            }
            text.append("]\n");
        }
    }

    private static long b(boolean x) {
        return x ? 1 : 0;
    }

    @Override
    public void meta(boolean wellFormed, String a, String b, String c, String d, String e) {
        ev("meta", b(wellFormed));
        if (text != null) {
            text.setLength(text.length() - 1);
            text.append(' ').append(a).append('|').append(b).append('|').append(c).append('|').append(d)
                    .append('|').append(e).append('\n');
        }
        mix((a + "|" + b + "|" + c + "|" + d + "|" + e).hashCode());
    }

    @Override
    public int nameAnonymous() {
        return 1;
    }

    @Override
    public int nameStr(int prefix, byte[] utf8, int off, int len) {
        evBytes("nameStr", utf8, off, len, prefix, names + 1);
        return ++names;
    }

    @Override
    public int nameNum(int prefix, long value) {
        ev("nameNum", prefix, value, names + 1);
        return ++names;
    }

    @Override
    public int nameNumBig(int prefix, byte[] digits, int off, int len) {
        evBytes("nameNumBig", digits, off, len, prefix, names + 1);
        return ++names;
    }

    @Override
    public int levelZero() {
        return 1;
    }

    @Override
    public int levelSucc(int level) {
        ev("succ", level, levels + 1);
        return ++levels;
    }

    @Override
    public int levelMax(int lhs, int rhs) {
        ev("max", lhs, rhs, levels + 1);
        return ++levels;
    }

    @Override
    public int levelIMax(int lhs, int rhs) {
        ev("imax", lhs, rhs, levels + 1);
        return ++levels;
    }

    @Override
    public int levelParam(int name) {
        ev("param", name, levels + 1);
        return ++levels;
    }

    @Override
    public int exprBVar(int index) {
        ev("bvar", index, exprs + 1);
        return ++exprs;
    }

    @Override
    public int exprSort(int level) throws ExportException {
        ev("sort", level, exprs + 1);
        return ++exprs;
    }

    @Override
    public int exprConst(int name, int[] ls, int off, int count) {
        ev("const", name, exprs + 1);
        evList("  us", ls, off, count);
        return ++exprs;
    }

    @Override
    public int exprApp(int fn, int arg) {
        ev("app", fn, arg, exprs + 1);
        return ++exprs;
    }

    @Override
    public int exprLam(int binderName, int binderInfo, int type, int body) {
        ev("lam", binderName, binderInfo, type, body, exprs + 1);
        return ++exprs;
    }

    @Override
    public int exprForall(int binderName, int binderInfo, int type, int body) {
        ev("pi", binderName, binderInfo, type, body, exprs + 1);
        return ++exprs;
    }

    @Override
    public int exprLet(int binderName, int type, int value, int body, boolean nondep) {
        ev("let", binderName, type, value, body, b(nondep), exprs + 1);
        return ++exprs;
    }

    @Override
    public int exprProj(int typeName, long index, int struct) {
        ev("proj", typeName, index, struct, exprs + 1);
        return ++exprs;
    }

    @Override
    public int exprNatLit(byte[] digits, int off, int len) {
        evBytes("natLit", digits, off, len, exprs + 1);
        return ++exprs;
    }

    @Override
    public int exprStrLit(byte[] utf8, int off, int len) {
        evBytes("strLit", utf8, off, len, exprs + 1);
        return ++exprs;
    }

    @Override
    public int exprMData(int expr) {
        ev("mdata", expr, exprs + 1);
        return ++exprs;
    }

    @Override
    public void declAxiom(int name, int[] ids, int lpOff, int lpCount, int type, boolean isUnsafe) {
        constants++;
        ev("axiom", name, type, b(isUnsafe));
        evList("  lp", ids, lpOff, lpCount);
    }

    @Override
    public void declDef(int name, int[] ids, int lpOff, int lpCount, int type, int value, int hintKind,
            int hintHeight, int safety, int allOff, int allCount) {
        constants++;
        ev("def", name, type, value, hintKind, hintHeight & 0xFFFF_FFFFL, safety);
        evList("  lp", ids, lpOff, lpCount);
        evList("  all", ids, allOff, allCount);
    }

    @Override
    public void declThm(int name, int[] ids, int lpOff, int lpCount, int type, int value, int allOff, int allCount) {
        constants++;
        ev("thm", name, type, value);
        evList("  lp", ids, lpOff, lpCount);
        evList("  all", ids, allOff, allCount);
    }

    @Override
    public void declOpaque(int name, int[] ids, int lpOff, int lpCount, int type, int value, boolean isUnsafe,
            int allOff, int allCount) {
        constants++;
        ev("opaque", name, type, value, b(isUnsafe));
        evList("  lp", ids, lpOff, lpCount);
        evList("  all", ids, allOff, allCount);
    }

    @Override
    public void declQuot(int name, int[] ids, int lpOff, int lpCount, int type, int kind) {
        constants++;
        ev("quot", name, type, kind);
        evList("  lp", ids, lpOff, lpCount);
    }

    @Override
    public void inductBegin(int numTypes, int numCtors, int numRecs) {
        ev("inductive", numTypes, numCtors, numRecs);
    }

    @Override
    public void inductType(int name, int[] ids, int lpOff, int lpCount, int type, long numParams, long numIndices,
            int allOff, int allCount, int ctorsOff, int ctorsCount, long numNested, boolean isRec,
            boolean isUnsafe, boolean isReflexive) {
        constants++;
        ev("  type", name, type, numParams, numIndices, numNested, b(isRec), b(isUnsafe), b(isReflexive));
        evList("    lp", ids, lpOff, lpCount);
        evList("    all", ids, allOff, allCount);
        evList("    ctors", ids, ctorsOff, ctorsCount);
    }

    @Override
    public void inductCtor(int name, int[] ids, int lpOff, int lpCount, int type, int induct, long cidx,
            long numParams, long numFields, boolean isUnsafe) {
        constants++;
        ev("  ctor", name, type, induct, cidx, numParams, numFields, b(isUnsafe));
        evList("    lp", ids, lpOff, lpCount);
    }

    @Override
    public void inductRec(int name, int[] ids, int lpOff, int lpCount, int type, int allOff, int allCount,
            long numParams, long numIndices, long numMotives, long numMinors, boolean k, boolean isUnsafe,
            int numRules) {
        constants++;
        ev("  rec", name, type, numParams, numIndices, numMotives, numMinors, b(k), b(isUnsafe), numRules);
        evList("    lp", ids, lpOff, lpCount);
        evList("    all", ids, allOff, allCount);
    }

    @Override
    public void inductRecRule(int ctor, long numFields, int rhs) {
        ev("    rule", ctor, numFields, rhs);
    }

    @Override
    public void inductEnd() {
        ev("end");
    }
}
