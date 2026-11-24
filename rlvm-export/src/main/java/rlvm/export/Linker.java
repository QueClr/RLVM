package rlvm.export;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The sequential heart of the reader: receives records with the ids of the file, enforces "bound once,
 * defined before use", translates the ids and calls the sink. Records come from the fast path (the
 * {@link RecordOut} methods) or from the general path ({@link #slow}: a strict JSON parse followed by
 * the dispatch of the official reader's {@code parseItem}, {@code src/LeanExport/Parse.lean} of Lean
 * v4.36.0).
 *
 * <p>Every sink call for a record is made after all ids of the record have been translated, so a
 * malformed record never reaches the sink (except the already delivered part of an inductive block).
 */
final class Linker implements RecordOut {
    /** Largest bound variable index the official reader survives (loose bvar range limit 2^20 - 1). */
    static final long MAX_BVAR = (1L << 20) - 2;

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static final byte[] K_IN = ascii("in");
    private static final byte[] K_IL = ascii("il");
    private static final byte[] K_IE = ascii("ie");
    private static final byte[] K_STR = ascii("str");
    private static final byte[] K_NUM = ascii("num");
    private static final byte[] K_PRE = ascii("pre");
    private static final byte[] K_I = ascii("i");
    private static final byte[] K_SUCC = ascii("succ");
    private static final byte[] K_MAX = ascii("max");
    private static final byte[] K_IMAX = ascii("imax");
    private static final byte[] K_PARAM = ascii("param");
    private static final byte[] K_BVAR = ascii("bvar");
    private static final byte[] K_SORT = ascii("sort");
    private static final byte[] K_CONST = ascii("const");
    private static final byte[] K_APP = ascii("app");
    private static final byte[] K_LAM = ascii("lam");
    private static final byte[] K_FORALLE = ascii("forallE");
    private static final byte[] K_LETE = ascii("letE");
    private static final byte[] K_PROJ = ascii("proj");
    private static final byte[] K_NATVAL = ascii("natVal");
    private static final byte[] K_STRVAL = ascii("strVal");
    private static final byte[] K_MDATA = ascii("mdata");
    private static final byte[] K_NAME = ascii("name");
    private static final byte[] K_US = ascii("us");
    private static final byte[] K_FN = ascii("fn");
    private static final byte[] K_ARG = ascii("arg");
    private static final byte[] K_TYPE = ascii("type");
    private static final byte[] K_BODY = ascii("body");
    private static final byte[] K_BINDERINFO = ascii("binderInfo");
    private static final byte[] K_VALUE = ascii("value");
    private static final byte[] K_NONDEP = ascii("nondep");
    private static final byte[] K_TYPENAME = ascii("typeName");
    private static final byte[] K_IDX = ascii("idx");
    private static final byte[] K_STRUCT = ascii("struct");
    private static final byte[] K_EXPR = ascii("expr");
    private static final byte[] K_DATA = ascii("data");
    private static final byte[] K_AXIOM = ascii("axiom");
    private static final byte[] K_DEF = ascii("def");
    private static final byte[] K_THM = ascii("thm");
    private static final byte[] K_OPAQUE = ascii("opaque");
    private static final byte[] K_QUOT = ascii("quot");
    private static final byte[] K_INDUCTIVE = ascii("inductive");
    private static final byte[] K_LEVELPARAMS = ascii("levelParams");
    private static final byte[] K_ISUNSAFE = ascii("isUnsafe");
    private static final byte[] K_HINTS = ascii("hints");
    private static final byte[] K_SAFETY = ascii("safety");
    private static final byte[] K_ALL = ascii("all");
    private static final byte[] K_REGULAR = ascii("regular");
    private static final byte[] K_KIND = ascii("kind");
    private static final byte[] K_TYPES = ascii("types");
    private static final byte[] K_CTORS = ascii("ctors");
    private static final byte[] K_RECS = ascii("recs");
    private static final byte[] K_NUMPARAMS = ascii("numParams");
    private static final byte[] K_NUMINDICES = ascii("numIndices");
    private static final byte[] K_NUMNESTED = ascii("numNested");
    private static final byte[] K_ISREC = ascii("isRec");
    private static final byte[] K_ISREFLEXIVE = ascii("isReflexive");
    private static final byte[] K_INDUCT = ascii("induct");
    private static final byte[] K_CIDX = ascii("cidx");
    private static final byte[] K_NUMFIELDS = ascii("numFields");
    private static final byte[] K_NUMMOTIVES = ascii("numMotives");
    private static final byte[] K_NUMMINORS = ascii("numMinors");
    private static final byte[] K_K = ascii("k");
    private static final byte[] K_RULES = ascii("rules");
    private static final byte[] K_CTOR = ascii("ctor");
    private static final byte[] K_NFIELDS = ascii("nfields");
    private static final byte[] K_RHS = ascii("rhs");
    private static final byte[] K_META = ascii("meta");
    private static final byte[] K_EXPORTER = ascii("exporter");
    private static final byte[] K_VERSION = ascii("version");
    private static final byte[] K_FORMAT = ascii("format");
    private static final byte[] K_LEAN = ascii("lean");
    private static final byte[] K_GITHASH = ascii("githash");

    private static final byte[] V_DEFAULT = ascii("default");
    private static final byte[] V_IMPLICIT = ascii("implicit");
    private static final byte[] V_STRICT_IMPLICIT = ascii("strictImplicit");
    private static final byte[] V_INST_IMPLICIT = ascii("instImplicit");
    private static final byte[] V_OPAQUE = ascii("opaque");
    private static final byte[] V_ABBREV = ascii("abbrev");
    private static final byte[] V_UNSAFE = ascii("unsafe");
    private static final byte[] V_SAFE = ascii("safe");
    private static final byte[] V_PARTIAL = ascii("partial");
    private static final byte[] V_TYPE = ascii("type");
    private static final byte[] V_CTOR = ascii("ctor");
    private static final byte[] V_LIFT = ascii("lift");
    private static final byte[] V_IND = ascii("ind");

    /** Line 1 is not parsed as metadata when it is longer than this. */
    static final int MAX_META_LINE = 1 << 20;

    private final ExportSink sink;
    final IdSpace names = new IdSpace("Name");
    final IdSpace levels = new IdSpace("Level");
    final IdSpace exprs = new IdSpace("Expr");

    /** The buffer that the offsets of {@link #nameStr} and {@link #slow} refer to. */
    byte[] src;
    /** 1-based number of the line being processed (the last one handed over). */
    long line;
    /** Lines that went through the general path. */
    long slowLines;

    private byte[] buf = new byte[256];
    private int[] ids = new int[64];
    private int idsLen;
    private long[] ruleFields = new long[16];
    private final JsonLine json = new JsonLine();
    /**
     * Ids of 2^63 and above, by their canonical decimal digits: each gets a key of
     * {@link IdSpace#BIG_KEY_MAX} or below, the same in all three id spaces. Null until the first one
     * (no file written by the exporter has any).
     */
    private java.util.HashMap<String, Long> bigIds;

    Linker(ExportSink sink) {
        this.sink = sink;
    }

    /** Binds the implicit entries (anonymous name, level zero). */
    void begin() throws ExportException {
        names.bindImplicitZero(sink.nameAnonymous());
        levels.bindImplicitZero(sink.levelZero());
    }

    // ===================================================================================== fast path

    @Override
    public void app(long id, long fn, long arg) throws ExportException {
        line++;
        IdSpace e = exprs;
        e.fresh(id);
        int f = e.ref(fn);
        int a = e.ref(arg);
        e.bind(id, sink.exprApp(f, a));
    }

    @Override
    public void lam(long id, int binderInfo, long name, long type, long body) throws ExportException {
        line++;
        IdSpace e = exprs;
        e.fresh(id);
        int n = names.ref(name);
        int t = e.ref(type);
        int b = e.ref(body);
        e.bind(id, sink.exprLam(n, binderInfo, t, b));
    }

    @Override
    public void pi(long id, int binderInfo, long name, long type, long body) throws ExportException {
        line++;
        IdSpace e = exprs;
        e.fresh(id);
        int n = names.ref(name);
        int t = e.ref(type);
        int b = e.ref(body);
        e.bind(id, sink.exprForall(n, binderInfo, t, b));
    }

    @Override
    public void let(long id, long name, boolean nondep, long type, long value, long body) throws ExportException {
        line++;
        IdSpace e = exprs;
        e.fresh(id);
        int n = names.ref(name);
        int t = e.ref(type);
        int v = e.ref(value);
        int b = e.ref(body);
        e.bind(id, sink.exprLet(n, t, v, b, nondep));
    }

    @Override
    public void proj(long id, long idx, long struct, long typeName) throws ExportException {
        line++;
        exprs.fresh(id);
        int n = names.ref(typeName);
        int s = exprs.ref(struct);
        exprs.bind(id, sink.exprProj(n, idx, s));
    }

    @Override
    public void constant(long id, long name, int[] us, int off, int n) throws ExportException {
        line++;
        exprs.fresh(id);
        int nm = names.ref(name);
        int[] out = idsRoom(n);
        IdSpace l = levels;
        for (int i = 0; i < n; i++) {
            out[i] = l.ref(us[off + i]);
        }
        exprs.bind(id, sink.exprConst(nm, out, 0, n));
    }

    @Override
    public void nameStr(long id, long pre, int off, int len) throws ExportException {
        line++;
        names.fresh(id);
        names.bind(id, sink.nameStr(names.ref(pre), src, off, len));
    }

    @Override
    public void nameNum(long id, long pre, long i) throws ExportException {
        line++;
        names.fresh(id);
        names.bind(id, sink.nameNum(names.ref(pre), i));
    }

    @Override
    public void thm(long name, long type, long value, int[] list, int numAll, int numParams) throws ExportException {
        line++;
        IdSpace nm = names;
        int n = nm.ref(name);
        int[] out = idsRoom(numAll + numParams);
        for (int i = 0; i < numParams; i++) {
            out[i] = nm.ref(list[numAll + i]);
        }
        int t = exprs.ref(type);
        int v = exprs.ref(value);
        for (int i = 0; i < numAll; i++) {
            out[numParams + i] = nm.ref(list[i]);
        }
        sink.declThm(n, out, 0, numParams, t, v, numParams, numAll);
    }

    @Override
    public void def(long name, long type, long value, int hintKind, long hintHeight, int[] list, int numAll,
            int numParams) throws ExportException {
        line++;
        IdSpace nm = names;
        int n = nm.ref(name);
        int[] out = idsRoom(numAll + numParams);
        for (int i = 0; i < numParams; i++) {
            out[i] = nm.ref(list[numAll + i]);
        }
        int t = exprs.ref(type);
        int v = exprs.ref(value);
        for (int i = 0; i < numAll; i++) {
            out[numParams + i] = nm.ref(list[i]);
        }
        sink.declDef(n, out, 0, numParams, t, v, hintKind, (int) hintHeight, ExportSink.SAFETY_SAFE, numParams, numAll);
    }

    /** A run was taken by the sink since the last {@link #runsEnd()}. */
    private boolean runsOpen;

    /**
     * A packed run offered by a tape ({@link RunPacker}). It is passed on to the sink only if the
     * records could not fail here: the expression ids are dense with a known delta and the run's
     * first id is the next one (so every child, which the packer has checked to be smaller than its
     * record, is bound), and every binder name is bound. Otherwise, and if the sink refuses, the
     * tape replays the records one by one.
     */
    @Override
    public boolean run(int firstId, int count, int minLocal, int maxName, int numLam, int numPi, int numLet,
            byte[] packed, int off, int len, int[] ext, int extOff, int extCount) throws ExportException {
        IdSpace e = exprs;
        if (!e.nextDense(firstId, count) || (maxName >= 0 && !names.allBoundUpTo(maxName))) {
            return false;
        }
        int first = sink.exprRun(firstId, count, e.delta, minLocal, packed, off, len, ext, extOff, extCount, numLam, numPi,
                numLet);
        if (first == ExportSink.RUN_REFUSED) {
            return false;
        }
        if (first - firstId != e.delta) {
            throw new IllegalStateException("the sink numbered the run it took from " + first + " instead of "
                    + (firstId + e.delta));
        }
        e.bindRun(count);
        line += count;
        runsOpen = true;
        return true;
    }

    /** The arrays of the runs offered so far are about to be reused. */
    void runsEnd() throws ExportException {
        if (runsOpen) {
            runsOpen = false;
            sink.exprRunsEnd();
        }
    }

    // ================================================================================== general path

    @Override
    public void slow(int off, int len) throws ExportException {
        slowLine(src, off, len);
    }

    /** The line {@code b[off .. off+len)} (with or without its final newline) through the general path. */
    void slowLine(byte[] b, int off, int len) throws ExportException {
        line++;
        slowLines++;
        JsonLine j = json;
        try {
            j.parse(b, off, off + len);
        } catch (ExportException e) {
            // The official reader does not validate UTF-8, and what its parser sees in ill-formed
            // bytes is unspecified: a line containing any is declined, whatever else is wrong with it.
            if (e.kind() == ExportException.Kind.MALFORMED && !Utf8.isValid(b, off, len)) {
                throw ExportException.unsupported("ill-formed UTF-8");
            }
            throw e;
        }
        idsLen = 0;
        if (j.type[0] != JsonLine.OBJ) {
            throw ExportException.malformed("Expected JSON object");
        }
        int count = j.size[0];
        if (count == 2) {
            int first = j.child[0];
            int second = j.next[first];
            int idNode;
            if ((idNode = j.member(0, K_IN)) >= 0) {
                if (j.isNat(idNode)) {
                    int data = idNode == first ? second : first;
                    if (j.keyIs(data, K_STR)) {
                        long id = idOf(idNode);
                        names.fresh(id);
                        nameStrGeneral(id, data);
                        return;
                    }
                    if (j.keyIs(data, K_NUM)) {
                        long id = idOf(idNode);
                        names.fresh(id);
                        nameNumGeneral(id, data);
                        return;
                    }
                }
            } else if ((idNode = j.member(0, K_IL)) >= 0) {
                if (j.isNat(idNode)) {
                    int data = idNode == first ? second : first;
                    if (level(idOf(idNode), data)) {
                        return;
                    }
                }
            } else if ((idNode = j.member(0, K_IE)) >= 0) {
                if (j.isNat(idNode)) {
                    int data = idNode == first ? second : first;
                    if (expr(idOf(idNode), data)) {
                        return;
                    }
                }
            }
        } else if (count == 1) {
            int data = j.child[0];
            if (j.type[data] == JsonLine.OBJ) {
                if (j.keyIs(data, K_THM)) {
                    thm(data);
                    return;
                }
                if (j.keyIs(data, K_DEF)) {
                    def(data);
                    return;
                }
                if (j.keyIs(data, K_INDUCTIVE)) {
                    inductive(data);
                    return;
                }
                if (j.keyIs(data, K_OPAQUE)) {
                    opaque(data);
                    return;
                }
                if (j.keyIs(data, K_AXIOM)) {
                    axiom(data);
                    return;
                }
                if (j.keyIs(data, K_QUOT)) {
                    quot(data);
                    return;
                }
            }
        }
        throw ExportException.malformed("Unknown export object with keys " + j.keysForMessage(0));
    }

    // ---- field access (the official reader's `let some (.num (x : Nat)) := data["key"]? | fail msg`)

    private static ExportException invalid(String what) {
        return ExportException.malformed(what + " invalid");
    }

    /**
     * The natural number value of member {@code key}: the value itself below 2^63, else a negative
     * number, which is at the same time the {@link IdSpace} key of that number ({@link #idOf}). So a
     * caller that wants a count or an index tests for "negative", and a caller that wants an id passes
     * the result on.
     */
    private long nat(int obj, byte[] key, String what) throws ExportException {
        JsonLine j = json;
        int m = j.member(obj, key);
        if (m < 0 || !j.isNat(m)) {
            throw invalid(what);
        }
        return idOf(m);
    }

    /**
     * The id written in number node {@code node}, as an {@link IdSpace} key: the number itself below
     * 2^63; for larger numbers (the official reader's ids are arbitrary naturals) a key of
     * {@link IdSpace#BIG_KEY_MAX} or below that stands for exactly that number in every id space.
     */
    private long idOf(int node) {
        JsonLine j = json;
        long v = j.natural[node];
        if (v != JsonLine.BIG) {
            return v;
        }
        if (bigIds == null) {
            bigIds = new java.util.HashMap<>();
        }
        String digits = new String(j.text, j.valOff[node], j.valLen[node], StandardCharsets.ISO_8859_1);
        Long key = bigIds.get(digits);
        if (key == null) {
            key = IdSpace.BIG_KEY_MAX - bigIds.size();
            bigIds.put(digits, key);
        }
        return key;
    }

    private boolean bool(int obj, byte[] key, String what) throws ExportException {
        JsonLine j = json;
        int m = j.member(obj, key);
        if (m >= 0) {
            if (j.type[m] == JsonLine.TRUE) {
                return true;
            }
            if (j.type[m] == JsonLine.FALSE) {
                return false;
            }
        }
        throw invalid(what);
    }

    private int array(int obj, byte[] key, String what) throws ExportException {
        JsonLine j = json;
        int m = j.member(obj, key);
        if (m < 0 || j.type[m] != JsonLine.ARR) {
            throw invalid(what);
        }
        return m;
    }

    private int string(int obj, byte[] key, String what) throws ExportException {
        JsonLine j = json;
        int m = j.member(obj, key);
        if (m < 0 || j.type[m] != JsonLine.STR) {
            throw invalid(what);
        }
        return m;
    }

    private int[] idsRoom(int n) {
        if (idsLen + n > ids.length) {
            ids = Arrays.copyOf(ids, Math.max(ids.length * 2, idsLen + n));
        }
        return ids;
    }

    /** Translates the name-id array {@code arr} into the id scratch; returns its offset there. */
    private int nameList(int arr) throws ExportException {
        JsonLine j = json;
        int n = j.size[arr];
        int[] out = idsRoom(n);
        int off = idsLen;
        int i = off;
        for (int c = j.child[arr]; c >= 0; c = j.next[c]) {
            if (!j.isNat(c)) {
                throw ExportException.malformed("failed to convert to name idx");
            }
            out[i++] = names.ref(idOf(c));
        }
        idsLen = i;
        return off;
    }

    // ---- names and levels

    private void nameStrGeneral(long id, int data) throws ExportException {
        JsonLine j = json;
        if (j.type[data] != JsonLine.OBJ) {
            throw invalid("Name.str");
        }
        long pre = nat(data, K_PRE, "Name.str");
        int s = string(data, K_STR, "Name.str");
        int p = names.ref(pre);
        names.bind(id, sink.nameStr(p, j.text, j.valOff[s], j.valLen[s]));
    }

    private void nameNumGeneral(long id, int data) throws ExportException {
        JsonLine j = json;
        if (j.type[data] != JsonLine.OBJ) {
            throw invalid("Name.num");
        }
        long pre = nat(data, K_PRE, "Name.num");
        int im = j.member(data, K_I);
        if (im < 0 || !j.isNat(im)) {
            throw invalid("Name.num");
        }
        int p = names.ref(pre);
        long i = j.natural[im];
        int r = i >= 0 ? sink.nameNum(p, i) : sink.nameNumBig(p, j.text, j.valOff[im], j.valLen[im]);
        names.bind(id, r);
    }

    private boolean level(long id, int data) throws ExportException {
        JsonLine j = json;
        int r;
        if (j.keyIs(data, K_SUCC) || j.keyIs(data, K_MAX) || j.keyIs(data, K_IMAX) || j.keyIs(data, K_PARAM)) {
            levels.fresh(id);
        }
        if (j.keyIs(data, K_SUCC)) {
            if (!j.isNat(data)) {
                throw invalid("Level.succ");
            }
            r = sink.levelSucc(levels.ref(idOf(data)));
        } else if (j.keyIs(data, K_MAX) || j.keyIs(data, K_IMAX)) {
            boolean isMax = j.keyIs(data, K_MAX);
            int a;
            int b;
            if (j.type[data] != JsonLine.ARR
                    || j.size[data] != 2
                    || !j.isNat(a = j.child[data])
                    || !j.isNat(b = j.next[a])) {
                throw invalid(isMax ? "Level.max" : "Level.imax");
            }
            int l = levels.ref(idOf(a));
            int rr = levels.ref(idOf(b));
            r = isMax ? sink.levelMax(l, rr) : sink.levelIMax(l, rr);
        } else if (j.keyIs(data, K_PARAM)) {
            if (!j.isNat(data)) {
                throw invalid("Level.param");
            }
            r = sink.levelParam(names.ref(idOf(data)));
        } else {
            return false;
        }
        levels.bind(id, r);
        return true;
    }

    // ---- expressions

    private int binderInfo(int obj, String what) throws ExportException {
        JsonLine j = json;
        int m = string(obj, K_BINDERINFO, what);
        if (j.stringIs(m, V_DEFAULT)) {
            return ExportSink.BINDER_DEFAULT;
        }
        if (j.stringIs(m, V_IMPLICIT)) {
            return ExportSink.BINDER_IMPLICIT;
        }
        if (j.stringIs(m, V_STRICT_IMPLICIT)) {
            return ExportSink.BINDER_STRICT_IMPLICIT;
        }
        if (j.stringIs(m, V_INST_IMPLICIT)) {
            return ExportSink.BINDER_INST_IMPLICIT;
        }
        throw ExportException.malformed("Invalid binder info: "
                + new String(j.text, j.valOff[m], Math.min(j.valLen[m], 60), StandardCharsets.UTF_8));
    }

    private boolean isExprKey(int data) {
        JsonLine j = json;
        return j.keyIs(data, K_APP) || j.keyIs(data, K_LAM) || j.keyIs(data, K_FORALLE) || j.keyIs(data, K_CONST)
                || j.keyIs(data, K_LETE) || j.keyIs(data, K_PROJ) || j.keyIs(data, K_BVAR) || j.keyIs(data, K_SORT)
                || j.keyIs(data, K_NATVAL) || j.keyIs(data, K_STRVAL) || j.keyIs(data, K_MDATA);
    }

    private boolean expr(long id, int data) throws ExportException {
        JsonLine j = json;
        IdSpace e = exprs;
        int r;
        if (!isExprKey(data)) {
            return false;
        }
        e.fresh(id);
        if (j.keyIs(data, K_APP)) {
            if (j.type[data] != JsonLine.OBJ) {
                throw invalid("Expr.app");
            }
            long fn = nat(data, K_FN, "Expr.app");
            long arg = nat(data, K_ARG, "Expr.app");
            int f = e.ref(fn);
            int a = e.ref(arg);
            r = sink.exprApp(f, a);
        } else if (j.keyIs(data, K_LAM) || j.keyIs(data, K_FORALLE)) {
            boolean isLam = j.keyIs(data, K_LAM);
            String what = isLam ? "Expr.lam" : "Expr.forallE";
            if (j.type[data] != JsonLine.OBJ) {
                throw invalid(what);
            }
            long name = nat(data, K_NAME, what);
            long type = nat(data, K_TYPE, what);
            long body = nat(data, K_BODY, what);
            string(data, K_BINDERINFO, what);
            int n = names.ref(name);
            int t = e.ref(type);
            int b = e.ref(body);
            int bi = binderInfo(data, what);
            r = isLam ? sink.exprLam(n, bi, t, b) : sink.exprForall(n, bi, t, b);
        } else if (j.keyIs(data, K_CONST)) {
            if (j.type[data] != JsonLine.OBJ) {
                throw invalid("Expr.const");
            }
            long name = nat(data, K_NAME, "Expr.const");
            int us = array(data, K_US, "Expr.const");
            int n = names.ref(name);
            int cnt = j.size[us];
            int[] out = idsRoom(cnt);
            int i = 0;
            for (int c = j.child[us]; c >= 0; c = j.next[c]) {
                if (!j.isNat(c)) {
                    throw invalid("Expr.const");
                }
                out[i++] = levels.ref(idOf(c));
            }
            r = sink.exprConst(n, out, 0, cnt);
        } else if (j.keyIs(data, K_LETE)) {
            if (j.type[data] != JsonLine.OBJ) {
                throw invalid("Expr.letE");
            }
            long name = nat(data, K_NAME, "Expr.letE");
            long type = nat(data, K_TYPE, "Expr.letE");
            long value = nat(data, K_VALUE, "Expr.letE");
            long body = nat(data, K_BODY, "Expr.letE");
            boolean nondep = bool(data, K_NONDEP, "Expr.letE");
            int n = names.ref(name);
            int t = e.ref(type);
            int v = e.ref(value);
            int b = e.ref(body);
            r = sink.exprLet(n, t, v, b, nondep);
        } else if (j.keyIs(data, K_PROJ)) {
            if (j.type[data] != JsonLine.OBJ) {
                throw invalid("Expr.proj");
            }
            long typeName = nat(data, K_TYPENAME, "Expr.proj");
            long idx = nat(data, K_IDX, "Expr.proj");
            long struct = nat(data, K_STRUCT, "Expr.proj");
            int n = names.ref(typeName);
            int s = e.ref(struct);
            if (idx < 0) {
                throw ExportException.unsupported("Expr.proj: field index is 2^63 or more");
            }
            r = sink.exprProj(n, idx, s);
        } else if (j.keyIs(data, K_BVAR)) {
            if (!j.isNat(data)) {
                throw invalid("Expr.bvar");
            }
            long idx = j.natural[data];
            if (idx < 0 || idx > MAX_BVAR) {
                // INTERNAL PANIC: too many bound variables (exit status 1) in the official reader
                throw ExportException.malformed("Expr.bvar: index exceeds " + MAX_BVAR
                        + " (the official reader aborts: too many bound variables)");
            }
            r = sink.exprBVar((int) idx);
        } else if (j.keyIs(data, K_SORT)) {
            if (!j.isNat(data)) {
                throw invalid("Expr.sort");
            }
            r = sink.exprSort(levels.ref(idOf(data)));
        } else if (j.keyIs(data, K_NATVAL)) {
            if (j.type[data] != JsonLine.STR) {
                throw invalid("Expr.lit natVal");
            }
            int len = natDigits(j.text, j.valOff[data], j.valLen[data]);
            r = sink.exprNatLit(buf, 0, len);
        } else if (j.keyIs(data, K_STRVAL)) {
            if (j.type[data] != JsonLine.STR) {
                throw invalid("Expr.lit strVal");
            }
            r = sink.exprStrLit(j.text, j.valOff[data], j.valLen[data]);
        } else if (j.keyIs(data, K_MDATA)) {
            if (j.type[data] != JsonLine.OBJ) {
                throw invalid("Expr.mdata");
            }
            long inner = nat(data, K_EXPR, "Expr.mdata");
            int d = j.member(data, K_DATA);
            if (d < 0 || j.type[d] != JsonLine.OBJ) {
                throw invalid("Expr.mdata");
            }
            r = sink.exprMData(e.ref(inner));
        } else {
            throw new IllegalStateException();
        }
        e.bind(id, r);
        return true;
    }

    /**
     * {@code String.toNat?} of Lean v4.36.0: decimal digits, optionally separated by single underscores
     * (an underscore must follow a digit, the last character must be a digit). Writes the canonical
     * digits into {@link #buf}; returns their number.
     */
    private int natDigits(byte[] t, int off, int len) throws ExportException {
        byte[] out = buf;
        if (len > out.length) {
            out = buf = new byte[len + (len >> 1)];
        }
        int n = 0;
        boolean lastWasDigit = false;
        for (int i = 0; i < len; i++) {
            int c = t[off + i];
            if (c == '_') {
                if (!lastWasDigit) {
                    throw invalid("Expr.lit natVal");
                }
                lastWasDigit = false;
            } else if (c >= '0' && c <= '9') {
                lastWasDigit = true;
                if (n != 0 || c != '0') {
                    out[n++] = (byte) c;
                }
            } else {
                throw invalid("Expr.lit natVal");
            }
        }
        if (!lastWasDigit) {
            throw invalid("Expr.lit natVal");
        }
        if (n == 0) {
            out[n++] = '0';
        }
        return n;
    }

    // ---- declarations

    private void axiom(int d) throws ExportException {
        long name = nat(d, K_NAME, "axiomInfo");
        int lp = array(d, K_LEVELPARAMS, "axiomInfo");
        long type = nat(d, K_TYPE, "axiomInfo");
        boolean isUnsafe = bool(d, K_ISUNSAFE, "axiomInfo");
        int n = names.ref(name);
        int lpOff = nameList(lp);
        int t = exprs.ref(type);
        sink.declAxiom(n, ids, lpOff, json.size[lp], t, isUnsafe);
    }

    private void def(int d) throws ExportException {
        JsonLine j = json;
        long name = nat(d, K_NAME, "defnInfo");
        int lp = array(d, K_LEVELPARAMS, "defnInfo");
        long type = nat(d, K_TYPE, "defnInfo");
        long value = nat(d, K_VALUE, "defnInfo");
        int hints = j.member(d, K_HINTS);
        if (hints < 0) {
            throw invalid("defnInfo");
        }
        int safetyNode = string(d, K_SAFETY, "defnInfo");
        int all = array(d, K_ALL, "defnInfo");
        int n = names.ref(name);
        int lpOff = nameList(lp);
        int t = exprs.ref(type);
        int v = exprs.ref(value);
        int hintKind;
        int height = 0;
        if (j.type[hints] == JsonLine.STR && j.stringIs(hints, V_OPAQUE)) {
            hintKind = ExportSink.HINT_OPAQUE;
        } else if (j.type[hints] == JsonLine.STR && j.stringIs(hints, V_ABBREV)) {
            hintKind = ExportSink.HINT_ABBREV;
        } else if (j.type[hints] == JsonLine.OBJ) {
            int reg = j.member(hints, K_REGULAR);
            if (reg < 0 || !j.isNat(reg)) {
                throw invalid("defnInfo");
            }
            hintKind = ExportSink.HINT_REGULAR;
            long h = j.natural[reg];
            if (h >= 0) {
                height = (int) h; // Nat.toUInt32: modulo 2^32
            } else {
                int acc = 0;
                int o = j.valOff[reg];
                for (int i = 0; i < j.valLen[reg]; i++) {
                    acc = acc * 10 + (j.text[o + i] - '0');
                }
                height = acc;
            }
        } else {
            throw invalid("defnInfo");
        }
        int safety;
        if (j.stringIs(safetyNode, V_SAFE)) {
            safety = ExportSink.SAFETY_SAFE;
        } else if (j.stringIs(safetyNode, V_UNSAFE)) {
            safety = ExportSink.SAFETY_UNSAFE;
        } else if (j.stringIs(safetyNode, V_PARTIAL)) {
            safety = ExportSink.SAFETY_PARTIAL;
        } else {
            throw ExportException.malformed("Unknown safety parameter: "
                    + new String(j.text, j.valOff[safetyNode], Math.min(j.valLen[safetyNode], 60), StandardCharsets.UTF_8));
        }
        int allOff = nameList(all);
        sink.declDef(n, ids, lpOff, j.size[lp], t, v, hintKind, height, safety, allOff, j.size[all]);
    }

    private void thm(int d) throws ExportException {
        long name = nat(d, K_NAME, "thmInfo");
        int lp = array(d, K_LEVELPARAMS, "thmInfo");
        long type = nat(d, K_TYPE, "thmInfo");
        long value = nat(d, K_VALUE, "thmInfo");
        int all = array(d, K_ALL, "thmInfo");
        int n = names.ref(name);
        int lpOff = nameList(lp);
        int t = exprs.ref(type);
        int v = exprs.ref(value);
        int allOff = nameList(all);
        sink.declThm(n, ids, lpOff, json.size[lp], t, v, allOff, json.size[all]);
    }

    private void opaque(int d) throws ExportException {
        JsonLine j = json;
        long name = nat(d, K_NAME, "opaqueInfo");
        int lp = array(d, K_LEVELPARAMS, "opaqueInfo");
        long type = nat(d, K_TYPE, "opaqueInfo");
        long value = nat(d, K_VALUE, "opaqueInfo");
        boolean isUnsafe = false; // the official reader tolerates a missing isUnsafe
        int u = j.member(d, K_ISUNSAFE);
        if (u >= 0) {
            if (j.type[u] == JsonLine.TRUE) {
                isUnsafe = true;
            } else if (j.type[u] != JsonLine.FALSE) {
                throw invalid("opaqueInfo");
            }
        }
        int all = array(d, K_ALL, "opaqueInfo");
        int n = names.ref(name);
        int lpOff = nameList(lp);
        int t = exprs.ref(type);
        int v = exprs.ref(value);
        int allOff = nameList(all);
        sink.declOpaque(n, ids, lpOff, j.size[lp], t, v, isUnsafe, allOff, j.size[all]);
    }

    private void quot(int d) throws ExportException {
        JsonLine j = json;
        long name = nat(d, K_NAME, "quotInfo");
        int lp = array(d, K_LEVELPARAMS, "quotInfo");
        long type = nat(d, K_TYPE, "quotInfo");
        int kindNode = string(d, K_KIND, "quotInfo");
        int n = names.ref(name);
        int lpOff = nameList(lp);
        int t = exprs.ref(type);
        int kind;
        if (j.stringIs(kindNode, V_TYPE)) {
            kind = ExportSink.QUOT_TYPE;
        } else if (j.stringIs(kindNode, V_CTOR)) {
            kind = ExportSink.QUOT_CTOR;
        } else if (j.stringIs(kindNode, V_LIFT)) {
            kind = ExportSink.QUOT_LIFT;
        } else if (j.stringIs(kindNode, V_IND)) {
            kind = ExportSink.QUOT_IND;
        } else {
            throw ExportException.malformed("unknown quot kind: "
                    + new String(j.text, j.valOff[kindNode], Math.min(j.valLen[kindNode], 60), StandardCharsets.UTF_8));
        }
        sink.declQuot(n, ids, lpOff, j.size[lp], t, kind);
    }

    private void inductive(int d) throws ExportException {
        JsonLine j = json;
        int types = j.member(d, K_TYPES);
        if (types < 0 || j.type[types] != JsonLine.ARR) {
            throw ExportException.malformed("Inductive invalid, no `types`");
        }
        int ctors = j.member(d, K_CTORS);
        if (ctors < 0 || j.type[ctors] != JsonLine.ARR) {
            throw ExportException.malformed("Inductive invalid, no `ctors`");
        }
        int recs = j.member(d, K_RECS);
        if (recs < 0 || j.type[recs] != JsonLine.ARR) {
            throw ExportException.malformed("Inductive invalid, no `recs`");
        }
        sink.inductBegin(j.size[types], j.size[ctors], j.size[recs]);
        for (int c = j.child[types]; c >= 0; c = j.next[c]) {
            idsLen = 0;
            inductType(c);
        }
        for (int c = j.child[ctors]; c >= 0; c = j.next[c]) {
            idsLen = 0;
            inductCtor(c);
        }
        for (int c = j.child[recs]; c >= 0; c = j.next[c]) {
            idsLen = 0;
            inductRec(c);
        }
        sink.inductEnd();
    }

    private void inductType(int d) throws ExportException {
        JsonLine j = json;
        if (j.type[d] != JsonLine.OBJ) {
            throw ExportException.malformed("inductInfo invalid: Expected JSON object");
        }
        long name = nat(d, K_NAME, "inductInfo");
        int lp = array(d, K_LEVELPARAMS, "inductInfo");
        long type = nat(d, K_TYPE, "inductInfo");
        long numParams = nat(d, K_NUMPARAMS, "inductInfo");
        long numIndices = nat(d, K_NUMINDICES, "inductInfo");
        int all = array(d, K_ALL, "inductInfo");
        int ctors = array(d, K_CTORS, "inductInfo");
        long numNested = nat(d, K_NUMNESTED, "inductInfo");
        boolean isRec = bool(d, K_ISREC, "inductInfo");
        boolean isUnsafe = bool(d, K_ISUNSAFE, "inductInfo");
        boolean isReflexive = bool(d, K_ISREFLEXIVE, "inductInfo");
        int n = names.ref(name);
        int lpOff = nameList(lp);
        int t = exprs.ref(type);
        int allOff = nameList(all);
        int ctorsOff = nameList(ctors);
        if (numParams < 0 || numIndices < 0 || numNested < 0) {
            throw ExportException.unsupported("inductInfo: a count is 2^63 or more");
        }
        sink.inductType(n, ids, lpOff, j.size[lp], t, numParams, numIndices, allOff, j.size[all],
                ctorsOff, j.size[ctors], numNested, isRec, isUnsafe, isReflexive);
    }

    private void inductCtor(int d) throws ExportException {
        JsonLine j = json;
        if (j.type[d] != JsonLine.OBJ) {
            throw invalid("ctorInfo");
        }
        long name = nat(d, K_NAME, "ctorInfo");
        int lp = array(d, K_LEVELPARAMS, "ctorInfo");
        long type = nat(d, K_TYPE, "ctorInfo");
        long induct = nat(d, K_INDUCT, "ctorInfo");
        long cidx = nat(d, K_CIDX, "ctorInfo");
        long numParams = nat(d, K_NUMPARAMS, "ctorInfo");
        long numFields = nat(d, K_NUMFIELDS, "ctorInfo");
        boolean isUnsafe = bool(d, K_ISUNSAFE, "ctorInfo");
        int n = names.ref(name);
        int lpOff = nameList(lp);
        int t = exprs.ref(type);
        int ind = names.ref(induct);
        if (cidx < 0 || numParams < 0 || numFields < 0) {
            throw ExportException.unsupported("ctorInfo: a count is 2^63 or more");
        }
        sink.inductCtor(n, ids, lpOff, j.size[lp], t, ind, cidx, numParams, numFields, isUnsafe);
    }

    private void inductRec(int d) throws ExportException {
        JsonLine j = json;
        if (j.type[d] != JsonLine.OBJ) {
            throw invalid("recInfo");
        }
        long name = nat(d, K_NAME, "recInfo");
        int lp = array(d, K_LEVELPARAMS, "recInfo");
        long type = nat(d, K_TYPE, "recInfo");
        int all = array(d, K_ALL, "recInfo");
        long numParams = nat(d, K_NUMPARAMS, "recInfo");
        long numIndices = nat(d, K_NUMINDICES, "recInfo");
        long numMotives = nat(d, K_NUMMOTIVES, "recInfo");
        long numMinors = nat(d, K_NUMMINORS, "recInfo");
        boolean k = bool(d, K_K, "recInfo");
        int rules = array(d, K_RULES, "recInfo");
        boolean isUnsafe = bool(d, K_ISUNSAFE, "recInfo");
        int n = names.ref(name);
        int lpOff = nameList(lp);
        int t = exprs.ref(type);
        int allOff = nameList(all);
        int numRules = j.size[rules];
        idsRoom(2 * numRules);
        int rulesOff = idsLen;
        if (ruleFields.length < numRules) {
            ruleFields = new long[numRules + (numRules >> 1)];
        }
        boolean bigCount = numParams < 0 || numIndices < 0 || numMotives < 0 || numMinors < 0;
        int i = 0;
        for (int c = j.child[rules]; c >= 0; c = j.next[c], i++) {
            if (j.type[c] != JsonLine.OBJ) {
                throw invalid("recInfo");
            }
            long ctor = nat(c, K_CTOR, "recInfo");
            long nfields = nat(c, K_NFIELDS, "recInfo");
            long rhs = nat(c, K_RHS, "recInfo");
            ids[rulesOff + 2 * i] = names.ref(ctor);
            ids[rulesOff + 2 * i + 1] = exprs.ref(rhs);
            ruleFields[i] = nfields;
            bigCount |= nfields < 0;
        }
        idsLen = rulesOff + 2 * numRules;
        if (bigCount) {
            throw ExportException.unsupported("recInfo: a count is 2^63 or more");
        }
        sink.inductRec(n, ids, lpOff, j.size[lp], t, allOff, j.size[all], numParams, numIndices, numMotives,
                numMinors, k, isUnsafe, numRules);
        for (i = 0; i < numRules; i++) {
            sink.inductRecRule(ids[rulesOff + 2 * i], ruleFields[i], ids[rulesOff + 2 * i + 1]);
        }
    }

    // ---- header

    /**
     * Line 1 ({@code b == null}: no such line). Never fails because of its content: the official reader
     * does not look at it.
     */
    void metaLine(byte[] b, int off, int len) throws ExportException {
        line = 1;
        String[] v = new String[5];
        boolean ok = false;
        if (b != null && len <= MAX_META_LINE) {
            try {
                JsonLine j = json;
                j.parse(b, off, off + len);
                int meta;
                if (j.type[0] == JsonLine.OBJ && (meta = j.member(0, K_META)) >= 0 && j.type[meta] == JsonLine.OBJ) {
                    ok = true;
                    int exporter = j.member(meta, K_EXPORTER);
                    if (exporter >= 0 && j.type[exporter] == JsonLine.OBJ) {
                        v[0] = optString(exporter, K_NAME);
                        v[1] = optString(exporter, K_VERSION);
                    }
                    int format = j.member(meta, K_FORMAT);
                    if (format >= 0 && j.type[format] == JsonLine.OBJ) {
                        v[2] = optString(format, K_VERSION);
                    }
                    int lean = j.member(meta, K_LEAN);
                    if (lean >= 0 && j.type[lean] == JsonLine.OBJ) {
                        v[3] = optString(lean, K_GITHASH);
                        v[4] = optString(lean, K_VERSION);
                    }
                }
            } catch (ExportException e) {
                ok = false;
            }
        }
        if (!ok) {
            Arrays.fill(v, null);
        }
        sink.meta(ok, v[0], v[1], v[2], v[3], v[4]);
    }

    private String optString(int obj, byte[] key) {
        JsonLine j = json;
        int m = j.member(obj, key);
        if (m < 0 || j.type[m] != JsonLine.STR) {
            return null;
        }
        return new String(j.text, j.valOff[m], j.valLen[m], StandardCharsets.UTF_8);
    }
}
