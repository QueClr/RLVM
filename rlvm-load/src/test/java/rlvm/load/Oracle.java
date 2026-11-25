package rlvm.load;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import rlvm.export.ColumnarStore;
import rlvm.export.ExportException;
import rlvm.export.ExportReader;
import rlvm.export.ExportSink;
import rlvm.level.IntListStore;
import rlvm.level.LevelStore;
import rlvm.level.NameOracle;
import rlvm.store.DeclKind;
import rlvm.store.Kind;

/**
 * End-to-end oracle of the loader's tests: the same export is read into the reader module's reference
 * sink ({@link ColumnarStore}: every record kept as written) and through the {@link Loader} into a
 * target (the in-memory {@link FakeTarget}, or the real store: {@link StoreOracle}); both sides are
 * reduced to structural 64-bit hashes (names, levels and literals by content, never by id) and
 * compared: every constant's kind, name, universe parameters, type and value, every block's claims,
 * and the location of every expression record.
 */
class Oracle {
    /** What the comparison reads from the loaded side. */
    abstract static class View {
        abstract boolean finished();

        /** Number of shared nodes, of block records, of blocks. */
        abstract int sharedNodes();

        abstract int blockRecords();

        abstract int blocks();

        abstract int consts();

        /** Units, including the constant-less units of dropped records if the target makes them. */
        abstract int units();

        abstract boolean droppedRecordsHaveUnits();

        /** Distinct names the loader interned (the anonymous name not counted). */
        abstract int distinctNames();

        /** cid (&gt; 0), 0, or negative for a dropped name. */
        abstract int constOf(int name);

        abstract int cKind(int cid);

        abstract int cName(int cid);

        abstract int cLparams(int cid);

        abstract int cType(int cid);

        abstract int cValue(int cid);

        abstract int cHint(int cid);

        abstract int cUnit(int cid);

        abstract int uFirstCid(int unit);

        abstract int uSize(int unit);

        abstract int uKind(int unit);

        abstract int[] uClaims(int unit);

        /** The unit that {@code unit} repeats according to the loader ({@code LoadTarget.repeatOf}), or -1. */
        abstract int uRepeatOf(int unit);

        abstract int blockFirst(int block);

        abstract int blockRoot(int block);

        /** Fills {@code fn, fl, fs, fb} of the oracle. */
        abstract void hash(Oracle o);
    }

    final byte[] data;
    final java.nio.file.Path file;
    final ColumnarStore ref;
    final FakeTarget fake = new FakeTarget();
    LevelStore ls = new LevelStore(NameOracle.BY_ID);
    View view = new FakeView();
    Loader loader;
    LoadReport report;

    // hashes of the reference side
    long[] rn;
    long[] rl;
    long[] re;
    // hashes of the loaded side
    long[] fn;
    long[] fl;
    long[] fs; // shared nodes
    long[] fb; // block records (global numbers)

    Oracle(byte[] data) {
        this.data = data;
        this.file = null;
        this.ref = new ColumnarStore(data.length, false, false);
    }

    Oracle(java.nio.file.Path file) throws java.io.IOException {
        this.data = null;
        this.file = file;
        this.ref = new ColumnarStore(java.nio.file.Files.size(file), false, false);
    }

    long size() throws java.io.IOException {
        return data != null ? data.length : java.nio.file.Files.size(file);
    }

    /** Loads both sides; rethrows the loader's exception after checking that the reference sink agrees on malformed input. */
    /** The loader under test; overridden for other targets. */
    Loader newLoader(LoadOptions options) {
        return new Loader(fake, options, new LevelStoreInterner(ls), new NatStoreDecoder());
    }

    Oracle load(LoadOptions options) throws java.io.IOException {
        loader = newLoader(options);
        report = data != null ? ExportLoader.read(data, loader) : ExportLoader.read(file, loader);
        try {
            if (data != null) {
                ExportReader.read(data, ref);
            } else {
                ExportReader.read(file, ref);
            }
        } catch (ExportException e) {
            throw new AssertionError("reference sink failed where the loader did not: " + e.getMessage());
        }
        return this;
    }

    Oracle load() throws java.io.IOException {
        return load(LoadOptions.batch(size()));
    }

    // ---------------------------------------------------------------------------------------- hashing

    static long mix(long h, long x) {
        h ^= x * 0x9E3779B97F4A7C15L;
        h = Long.rotateLeft(h, 27) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }

    static long hashBytes(byte[] b, int off, int len) {
        long h = 0xCBF29CE484222325L;
        for (int i = off; i < off + len; i++) {
            h = (h ^ (b[i] & 0xFF)) * 0x100000001B3L;
        }
        return mix(h, len);
    }

    static long hashString(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        return hashBytes(b, 0, b.length);
    }

    private static final long H_ANON = 0x1234567;
    private static final long H_ZERO = 0x7654321;

    void hashReference() {
        ColumnarStore s = ref;
        byte[] pool = s.bytesPool();
        rn = new long[s.nameCount() + 1];
        for (int i = 1; i <= s.nameCount(); i++) {
            long d = s.nameDataColumn()[i];
            switch (s.nameKind(i)) {
                case ColumnarStore.NAME_ANONYMOUS -> rn[i] = H_ANON;
                case ColumnarStore.NAME_STR -> rn[i] = mix(mix(1, rn[s.namePrefix(i)]), hashBytes(pool, (int) (d >>> 32), (int) d));
                case ColumnarStore.NAME_NUM -> rn[i] = mix(mix(2, rn[s.namePrefix(i)]), hashString(Long.toString(d)));
                default -> rn[i] = mix(mix(2, rn[s.namePrefix(i)]), hashBytes(pool, (int) (d >>> 32), (int) d));
            }
        }
        rl = new long[s.levelCount() + 1];
        for (int i = 1; i <= s.levelCount(); i++) {
            switch (s.levelKind(i)) {
                case ColumnarStore.LEVEL_ZERO -> rl[i] = H_ZERO;
                case ColumnarStore.LEVEL_SUCC -> rl[i] = mix(3, rl[s.levelA(i)]);
                case ColumnarStore.LEVEL_MAX -> rl[i] = mix(mix(4, rl[s.levelA(i)]), rl[s.levelB(i)]);
                case ColumnarStore.LEVEL_IMAX -> rl[i] = mix(mix(5, rl[s.levelA(i)]), rl[s.levelB(i)]);
                default -> rl[i] = mix(6, rn[s.levelA(i)]);
            }
        }
        re = new long[s.exprCount() + 1];
        for (int i = 1; i <= s.exprCount(); i++) {
            int a = s.exprA(i);
            int b = s.exprB(i);
            switch (s.exprKind(i)) {
                case ColumnarStore.EXPR_BVAR -> re[i] = mix(10, a);
                case ColumnarStore.EXPR_SORT -> re[i] = mix(11, rl[a]);
                case ColumnarStore.EXPR_CONST -> {
                    long h = mix(12, rn[a]);
                    int n = s.constLevelCount(i);
                    for (int k = 0; k < n; k++) {
                        h = mix(h, rl[s.constLevel(i, k)]);
                    }
                    re[i] = mix(h, n);
                }
                case ColumnarStore.EXPR_APP -> re[i] = mix(mix(13, re[a]), re[b]);
                case ColumnarStore.EXPR_LAM -> re[i] = mix(mix(14, re[a]), re[b]);
                case ColumnarStore.EXPR_PI -> re[i] = mix(mix(15, re[a]), re[b]);
                case ColumnarStore.EXPR_LET -> re[i] = mix(mix(mix(16, re[a]), re[s.letValue(i)]), re[s.letBody(i)]);
                case ColumnarStore.EXPR_PROJ -> re[i] = mix(mix(mix(17, rn[s.projTypeName(i)]), s.projIndex(i)), re[a]);
                case ColumnarStore.EXPR_NATLIT -> re[i] = mix(18, hashBytes(pool, a, b));
                case ColumnarStore.EXPR_STRLIT -> re[i] = mix(19, hashBytes(pool, a, b));
                case ColumnarStore.EXPR_MDATA -> re[i] = re[a];
                default -> throw new AssertionError("reference expr kind " + s.exprKind(i));
            }
        }
    }

    long listHash(long tag, int list, long[] elementHash) {
        IntListStore lists = ls.lists();
        long h = tag;
        int n = lists.length(list);
        for (int k = 0; k < n; k++) {
            h = mix(h, elementHash[lists.get(list, k)]);
        }
        return mix(h, n);
    }

    static long natHashSmall(long unsignedValue) {
        return mix(18, hashString(Long.toUnsignedString(unsignedValue)));
    }

    static long natHashBig(long[] limbs) {
        BigInteger v = BigInteger.ZERO;
        for (int i = limbs.length - 1; i >= 0; i--) {
            v = v.shiftLeft(64).or(new BigInteger(Long.toUnsignedString(limbs[i])));
        }
        return mix(18, hashString(v.toString()));
    }

    static long strHash(byte[] utf8) {
        return mix(19, hashBytes(utf8, 0, utf8.length));
    }

    private long natHash(int a, int b, boolean big) {
        return big ? natHashBig(fake.nats.get(a - 1)) : natHashSmall((a & 0xFFFF_FFFFL) | ((long) b << 32));
    }

    private long strHash(int lit) {
        return strHash(fake.strings.get(lit - 1));
    }

    /** Hashes of the levels of {@link #ls}, given the name hashes {@link #fn}. */
    void hashLevels() {
        fl = new long[ls.size()];
        for (int i = 1; i < ls.size(); i++) {
            switch (ls.kind(i)) {
                case LevelStore.KIND_ZERO -> fl[i] = H_ZERO;
                case LevelStore.KIND_SUCC -> fl[i] = mix(3, fl[ls.succOf(i)]);
                case LevelStore.KIND_MAX -> fl[i] = mix(mix(4, fl[ls.lhs(i)]), fl[ls.rhs(i)]);
                case LevelStore.KIND_IMAX -> fl[i] = mix(mix(5, fl[ls.lhs(i)]), fl[ls.rhs(i)]);
                default -> fl[i] = mix(6, fn[ls.paramName(i)]);
            }
        }
    }

    static long hBVar(int index) {
        return mix(10, index);
    }

    static long hSort(long level) {
        return mix(11, level);
    }

    static long hApp(long f, long a) {
        return mix(mix(13, f), a);
    }

    static long hLam(long t, long b) {
        return mix(mix(14, t), b);
    }

    static long hPi(long t, long b) {
        return mix(mix(15, t), b);
    }

    static long hLet(long t, long v, long b) {
        return mix(mix(mix(16, t), v), b);
    }

    static long hProj(long name, int index, long struct) {
        return mix(mix(mix(17, name), index), struct);
    }

    static final long H_CONST = 12;
    static final long H_ANONYMOUS = H_ANON;

    static long hNameStr(long prefix, byte[] utf8, int off, int len) {
        return mix(mix(1, prefix), hashBytes(utf8, off, len));
    }

    static long hNameNum(long prefix, String digits) {
        return mix(mix(2, prefix), hashString(digits));
    }

    private void hashFake() {
        FakeTarget f = fake;
        fn = new long[f.names];
        fn[0] = H_ANON;
        for (int i = 1; i < f.names; i++) {
            byte[] d = f.nameData[i];
            fn[i] = mix(mix(f.nameKind[i] == FakeTarget.NAME_STR ? 1 : 2, fn[f.namePrefix[i]]), hashBytes(d, 0, d.length));
        }
        hashLevels();
        fs = new long[f.nodes];
        for (int i = 1; i < f.nodes; i++) {
            int a = f.nA[i];
            int b = f.nB[i];
            switch (f.nKind[i]) {
                case Kind.BVAR -> fs[i] = mix(10, a);
                case Kind.SORT -> fs[i] = mix(11, fl[a]);
                case Kind.CONST -> fs[i] = listHash(mix(12, fn[f.nC[i]]), b, fl);
                case Kind.APP -> fs[i] = mix(mix(13, fs[a]), fs[b]);
                case Kind.LAM -> fs[i] = mix(mix(14, fs[a]), fs[b]);
                case Kind.PI -> fs[i] = mix(mix(15, fs[a]), fs[b]);
                case Kind.LET -> fs[i] = mix(mix(mix(16, fs[a]), fs[b]), fs[f.nC[i]]);
                case Kind.PROJ -> fs[i] = mix(mix(mix(17, fn[f.projName.get(i)]), b), fs[a]);
                case Kind.NATLIT -> fs[i] = natHash(a, b, f.nBig[i]);
                case Kind.STRLIT -> fs[i] = strHash(a);
                default -> throw new AssertionError("shared node kind " + f.nKind[i]);
            }
        }
        fb = new long[f.bRecords];
        for (int blk = 0; blk < f.blocks; blk++) {
            int first = f.blockFirst[blk];
            for (int k = 0; k < f.blockCount[blk]; k++) {
                int g = first + k;
                int p = g * 4;
                int w = f.bRec[p];
                int a = f.bRec[p + 1];
                int b = f.bRec[p + 2];
                int c = f.bRec[p + 3];
                switch (w & 0xF) {
                    case Kind.BVAR -> fb[g] = mix(10, a);
                    case Kind.SORT -> fb[g] = mix(11, fl[a]);
                    case Kind.CONST -> fb[g] = listHash(mix(12, fn[c]), b, fl);
                    case Kind.APP -> fb[g] = mix(mix(13, refHash(g, a)), refHash(g, b));
                    case Kind.LAM -> fb[g] = mix(mix(14, refHash(g, a)), refHash(g, b));
                    case Kind.PI -> fb[g] = mix(mix(15, refHash(g, a)), refHash(g, b));
                    case Kind.LET -> fb[g] = mix(mix(mix(16, refHash(g, a)), refHash(g, b)), refHash(g, c));
                    case Kind.PROJ -> fb[g] = mix(mix(mix(17, fn[c]), b), refHash(g, a));
                    case Kind.NATLIT -> fb[g] = natHash(a, b, (w & FakeTarget.BIG) != 0);
                    case Kind.STRLIT -> fb[g] = strHash(a);
                    default -> throw new AssertionError("block record kind " + (w & 0xF));
                }
            }
        }
    }

    private long refHash(int g, int r) {
        return (r & 1) != 0 ? fs[r >>> 1] : fb[g - (r >> 1)];
    }

    /** Hash of the term at a location word (shared id, or -(global block record + 1)). */
    long locHash(int location) {
        return location > 0 ? fs[location] : fb[-location - 1];
    }

    /** Hash of a declaration value word (shared id, or -(block + 1)). */
    long valueHash(int value) {
        if (value > 0) {
            return fs[value];
        }
        int blk = -value - 1;
        return fb[view.blockFirst(blk) + view.blockRoot(blk)];
    }

    // --------------------------------------------------------------------------------------- comparing

    private long refNameListHash(long tag, int list) {
        ColumnarStore s = ref;
        long h = tag;
        int n = s.listSize(list);
        for (int k = 0; k < n; k++) {
            h = mix(h, rn[s.listGet(list, k)]);
        }
        return mix(h, n);
    }

    /** Whether reference constant {@code c} belongs to a record the loader drops (unsafe / partial). */
    boolean dropped(int c) {
        ColumnarStore s = ref;
        switch (s.constKind(c)) {
            case ColumnarStore.CONST_AXIOM, ColumnarStore.CONST_OPAQUE:
                return s.constField(c, 0) != 0;
            case ColumnarStore.CONST_DEF:
                return s.constField(c, 2) != ExportSink.SAFETY_SAFE;
            case ColumnarStore.CONST_INDUCT, ColumnarStore.CONST_CTOR, ColumnarStore.CONST_REC: {
                // An inductive record whose types are unsafe: the types are dropped, and so is every
                // unsafe constructor and recursor record; the safe ones are loaded (a unit without types).
                int first = s.declFirstConstColumn()[s.constDeclColumn()[c]];
                if (s.constKind(first) != ColumnarStore.CONST_INDUCT || (s.constField(first, 3) & 2) == 0) {
                    return false;
                }
                switch (s.constKind(c)) {
                    case ColumnarStore.CONST_CTOR:
                        return s.constField(c, 4) != 0;
                    case ColumnarStore.CONST_REC:
                        return (s.constField(c, 4) & 2) != 0;
                    default:
                        return true;
                }
            }
            default:
                return false;
        }
    }

    /** Whether declaration record {@code d} keeps at least one constant (it then is one ordinary unit). */
    boolean keepsAConstant(int d) {
        ColumnarStore s = ref;
        int first = s.declFirstConstColumn()[d];
        int end = d < s.declCount() ? s.declFirstConstColumn()[d + 1] : s.constCount() + 1;
        for (int c = first; c < end; c++) {
            if (!dropped(c)) {
                return true;
            }
        }
        return false;
    }

    /** Full comparison. {@code proofBlocks}: whether theorem and opaque values may be blocks. */
    void verify() {
        hashReference();
        view.hash(this);
        ColumnarStore s = ref;
        View f = view;
        assertTrue(f.finished(), "target finished");
        // record counts
        assertEquals(s.exprCount(), report.exprRecords, "expression records");
        assertEquals(s.declCount(), report.declRecords, "declaration records");
        assertEquals(s.levelCount() - 1, report.levelRecords, "level records");
        assertEquals(s.nameCount() - 1, f.distinctNames(), "distinct names");
        assertEquals(loader.exprCount() + report.exprMData, report.exprRecords);
        assertEquals(report.sharedNodes + report.promotedNodes, f.sharedNodes(), "shared nodes");
        assertEquals(report.blockNodes, f.blockRecords(), "block records");
        assertEquals(report.blocks, f.blocks());
        assertEquals(report.sharedNodes + report.blockNodes + report.trailingExprRecords, loader.exprCount(),
                "every record is shared, in a block, or trailing");
        // constants
        int cid = 0;
        int unit = -1;
        int lastDecl = 0;
        long droppedCount = 0;
        // Repeated declarations, found again here from the reference records: (kind, universe parameters, hint,
        // type record, value record) -> the first unit. A metadata record has an id of its own in the reference
        // store and none in the loader (the wrapped record stands for it), so the two agree on files without one.
        java.util.Map<java.util.List<Long>, Integer> firstUnitOf = new java.util.HashMap<>();
        long repeatsExpected = 0;
        long repeatsSaid = 0;
        int[] declFirst = s.declFirstConstColumn();
        for (int c = 1; c <= s.constCount(); c++) {
            int d = s.constDeclColumn()[c];
            if (dropped(c)) {
                droppedCount++;
                assertTrue(f.constOf(findFakeName(rn[s.constName(c)])) < 0, "dropped name is remembered");
                if (d != lastDecl && !keepsAConstant(d) && f.droppedRecordsHaveUnits()) {
                    unit++;
                    lastDecl = d;
                    assertEquals(0, f.uSize(unit), "the unit of a dropped record holds no constant");
                }
                continue;
            }
            cid++;
            boolean firstOfUnit = d != lastDecl;
            if (firstOfUnit) {
                unit++;
                lastDecl = d;
                assertEquals(cid, f.uFirstCid(unit), "first constant of unit " + unit);
            }
            String what = "constant " + cid + " (" + s.nameToString(s.constName(c)) + ")";
            assertEquals(unit, f.cUnit(cid), what + ": unit");
            assertEquals(rn[s.constName(c)], fn[f.cName(cid)], what + ": name");
            assertEquals(cid, f.constOf(f.cName(cid)), what + ": name claimed");
            assertEquals(refNameListHash(30, s.constLevelParamsColumn()[c]), listHash(30, f.cLparams(cid), fn),
                    what + ": universe parameters");
            assertEquals(re[s.constType(c)], fs[f.cType(cid)], what + ": type");
            int kind = s.constKind(c);
            switch (kind) {
                case ColumnarStore.CONST_AXIOM -> {
                    assertEquals(DeclKind.AXIOM, f.cKind(cid), what);
                    assertEquals(0, f.cValue(cid), what);
                }
                case ColumnarStore.CONST_DEF -> {
                    assertEquals(DeclKind.DEFN, f.cKind(cid), what);
                    assertTrue(f.cValue(cid) > 0, what + ": a definition's value is shared");
                    assertEquals(re[s.constValue(c)], fs[f.cValue(cid)], what + ": value");
                    int hk = s.constField(c, 0);
                    int expect = hk == ExportSink.HINT_OPAQUE ? DeclKind.HINT_OPAQUE
                            : hk == ExportSink.HINT_ABBREV ? DeclKind.HINT_ABBREV
                                    : DeclKind.hintRegular(Integer.toUnsignedLong(s.constField(c, 1)));
                    assertEquals(expect, f.cHint(cid), what + ": hint");
                }
                case ColumnarStore.CONST_THM, ColumnarStore.CONST_OPAQUE -> {
                    assertEquals(kind == ColumnarStore.CONST_THM ? DeclKind.THM : DeclKind.OPAQUE, f.cKind(cid), what);
                    assertTrue(f.cValue(cid) != 0, what + ": value");
                    assertEquals(re[s.constValue(c)], valueHash(f.cValue(cid)), what + ": value");
                    assertEquals(0, f.cHint(cid));
                }
                case ColumnarStore.CONST_QUOT -> {
                    assertEquals(DeclKind.QUOT_TYPE + s.constField(c, 0), f.cKind(cid), what);
                    assertEquals(0, f.cValue(cid), what);
                }
                case ColumnarStore.CONST_INDUCT -> assertEquals(DeclKind.INDUCT, f.cKind(cid), what);
                case ColumnarStore.CONST_CTOR -> assertEquals(DeclKind.CTOR, f.cKind(cid), what);
                case ColumnarStore.CONST_REC -> assertEquals(DeclKind.REC, f.cKind(cid), what);
                default -> fail("reference constant kind " + kind);
            }
            if (kind >= ColumnarStore.CONST_INDUCT && firstOfUnit) {
                verifyClaims(d, unit, cid);
            }
            int said = firstOfUnit ? f.uRepeatOf(unit) : -1;
            if (said >= 0) {
                repeatsSaid++;
                assertTrue(said < unit, what + ": repeats the later unit " + said);
                assertEquals(-1, f.uRepeatOf(said), what + ": repeats unit " + said + ", which is a repeat itself");
                int first = f.uFirstCid(said);
                assertEquals(1, f.uSize(said), what);
                assertEquals(f.cKind(first), f.cKind(cid), what + ": kind of the unit it repeats");
                assertEquals(f.cLparams(first), f.cLparams(cid), what + ": universe parameters of the unit it repeats");
                assertEquals(f.cHint(first), f.cHint(cid), what + ": hint of the unit it repeats");
                assertEquals(f.cType(first), f.cType(cid), what + ": type of the unit it repeats");
                assertEquals(valueHash(f.cValue(first)), valueHash(f.cValue(cid)), what + ": value of the unit it repeats");
            }
            if (kind == ColumnarStore.CONST_DEF || kind == ColumnarStore.CONST_THM || kind == ColumnarStore.CONST_OPAQUE) {
                java.util.List<Long> key = java.util.List.of((long) kind, refNameListHash(30, s.constLevelParamsColumn()[c]),
                        kind == ColumnarStore.CONST_DEF ? (long) f.cHint(cid) : -1L, (long) s.constType(c), (long) s.constValue(c));
                Integer first = firstUnitOf.putIfAbsent(key, unit);
                if (first != null) {
                    repeatsExpected++;
                }
                if (report.exprMData == 0) {
                    assertEquals(first == null ? -1 : first.intValue(), said, what + ": the unit it repeats");
                }
            } else {
                assertEquals(-1, said, what + ": only a definition, theorem or opaque repeats a unit");
            }
        }
        assertEquals(repeatsSaid, report.repeatUnits, "repeated declarations");
        if (report.exprMData == 0) {
            assertEquals(repeatsExpected, report.repeatUnits, "repeated declarations");
        }
        assertEquals(cid, f.consts(), "constants");
        assertEquals(cid, report.constants);
        assertEquals(unit + 1, f.units(), "units");
        if (!f.droppedRecordsHaveUnits()) {
            assertEquals(unit + 1, report.units);
        }
        assertEquals(droppedCount, report.droppedUnsafe);
        // locations of all expression records (ordinal = reference id when there is no mdata record)
        if (report.exprMData == 0) {
            int n = loader.exprCount();
            long shared = 0;
            long inBlocks = 0;
            for (int o = 1; o <= n; o++) {
                int l = loader.locationOf(o);
                if (l == 0) {
                    continue;
                }
                if (re[o] != locHash(l)) {
                    fail("expression record " + o + " differs from its copy at location " + l);
                }
                if (l > 0) {
                    shared++;
                } else {
                    inBlocks++;
                }
            }
            assertEquals(n - report.trailingExprRecords, shared + inBlocks);
        }
    }

    private int findFakeName(long hash) {
        for (int i = 1; i < fn.length; i++) {
            if (fn[i] == hash) {
                return i;
            }
        }
        throw new AssertionError("name not interned by the loader");
    }

    private static long sat(long v) {
        return Math.min(v, Integer.MAX_VALUE);
    }

    private void verifyClaims(int d, int unit, int firstCid) {
        ColumnarStore s = ref;
        View f = view;
        int[] bl = f.uClaims(unit);
        assertTrue(bl != null, "claims of unit " + unit);
        assertEquals(DeclKind.INDUCT, f.uKind(unit));
        assertEquals(BlockClaims.VERSION, bl[BlockClaims.H_VERSION]);
        int first = s.declFirstConstColumn()[d];
        int end = d < s.declCount() ? s.declFirstConstColumn()[d + 1] : s.constCount() + 1;
        int nt = 0;
        int nc = 0;
        int nr = 0;
        int cid = firstCid - 1;
        int recAt = BlockClaims.firstRec(bl);
        for (int c = first; c < end; c++) {
            if (dropped(c)) {
                continue; // an unsafe record of a record whose types are unsafe: not in the unit
            }
            cid++;
            switch (s.constKind(c)) {
                case ColumnarStore.CONST_INDUCT -> {
                    int q = BlockClaims.typeAt(bl, nt++);
                    assertEquals(cid, bl[q + BlockClaims.T_CID]);
                    assertEquals(s.constField(c, 0), sat(BlockClaims.getLong(bl, q + BlockClaims.T_NUM_PARAMS)));
                    assertEquals(s.constField(c, 1), sat(BlockClaims.getLong(bl, q + BlockClaims.T_NUM_INDICES)));
                    assertEquals(s.constField(c, 2), sat(BlockClaims.getLong(bl, q + BlockClaims.T_NUM_NESTED)));
                    int rf = s.constField(c, 3);
                    int expect = ((rf & 1) != 0 ? BlockClaims.F_REC : 0) | ((rf & 4) != 0 ? BlockClaims.F_REFLEXIVE : 0)
                            | ((rf & 2) != 0 ? BlockClaims.F_UNSAFE : 0);
                    assertEquals(expect, bl[q + BlockClaims.T_FLAGS]);
                    assertEquals(refNameListHash(31, s.constField(c, 4)), listHash(31, bl[q + BlockClaims.T_ALL], fn));
                    assertEquals(refNameListHash(32, s.constField(c, 5)), listHash(32, bl[q + BlockClaims.T_CTORS], fn));
                }
                case ColumnarStore.CONST_CTOR -> {
                    int q = BlockClaims.ctorAt(bl, nc++);
                    assertEquals(cid, bl[q + BlockClaims.C_CID]);
                    assertEquals(rn[s.constField(c, 0)], fn[bl[q + BlockClaims.C_INDUCT]]);
                    assertEquals(s.constField(c, 1), sat(BlockClaims.getLong(bl, q + BlockClaims.C_CIDX)));
                    assertEquals(s.constField(c, 2), sat(BlockClaims.getLong(bl, q + BlockClaims.C_NUM_PARAMS)));
                    assertEquals(s.constField(c, 3), sat(BlockClaims.getLong(bl, q + BlockClaims.C_NUM_FIELDS)));
                    assertEquals(s.constField(c, 4) != 0 ? BlockClaims.F_UNSAFE : 0, bl[q + BlockClaims.C_FLAGS]);
                }
                case ColumnarStore.CONST_REC -> {
                    int q = recAt;
                    nr++;
                    assertEquals(cid, bl[q + BlockClaims.R_CID]);
                    assertEquals(s.constField(c, 0), sat(BlockClaims.getLong(bl, q + BlockClaims.R_NUM_PARAMS)));
                    assertEquals(s.constField(c, 1), sat(BlockClaims.getLong(bl, q + BlockClaims.R_NUM_INDICES)));
                    assertEquals(s.constField(c, 2), sat(BlockClaims.getLong(bl, q + BlockClaims.R_NUM_MOTIVES)));
                    assertEquals(s.constField(c, 3), sat(BlockClaims.getLong(bl, q + BlockClaims.R_NUM_MINORS)));
                    int rf = s.constField(c, 4);
                    assertEquals(((rf & 1) != 0 ? BlockClaims.F_K : 0) | ((rf & 2) != 0 ? BlockClaims.F_UNSAFE : 0),
                            bl[q + BlockClaims.R_FLAGS]);
                    assertEquals(refNameListHash(33, s.constField(c, 5)), listHash(33, bl[q + BlockClaims.R_ALL], fn));
                    int rules = s.constField(c, 6);
                    assertEquals(rules, bl[q + BlockClaims.R_NUM_RULES]);
                    for (int r = 0; r < rules; r++) {
                        int rq = BlockClaims.ruleAt(q, r);
                        assertEquals(rn[s.constField(c, 7 + 3 * r)], fn[bl[rq + BlockClaims.RULE_CTOR]]);
                        assertEquals(s.constField(c, 8 + 3 * r), sat(BlockClaims.getLong(bl, rq + BlockClaims.RULE_NFIELDS)));
                        assertEquals(re[s.constField(c, 9 + 3 * r)], fs[bl[rq + BlockClaims.RULE_RHS]], "rule rhs");
                    }
                    recAt = BlockClaims.nextRec(bl, q);
                }
                default -> fail("non-inductive constant in an inductive declaration");
            }
        }
        assertEquals(nt, BlockClaims.numTypes(bl));
        assertEquals(nc, BlockClaims.numCtors(bl));
        assertEquals(nr, BlockClaims.numRecs(bl));
        assertEquals(recAt, bl.length, "claims blob length");
        assertEquals(nt + nc + nr, f.uSize(unit));
    }

    // ---------------------------------------------------------------------------- sharing flags (naive)

    /**
     * Recomputes referrer counts, body marks and single-referrer pairs naively from the reference
     * store and compares them with the flags the loader passed (files without mdata records only).
     */
    void verifySharingFlags() {
        ColumnarStore s = ref;
        FakeTarget f = fake;
        assertEquals(0, report.exprMData);
        int n = s.exprCount();
        int[] cnt = new int[n + 1];
        boolean[] body = new boolean[n + 1];
        long[] expectedSingles = new long[16];
        int expectedSingleCount = 0;
        int segStart = 0; // last ordinal before the segment
        int[] declEnd = s.declExprEndColumn();
        int[] declFirst = s.declFirstConstColumn();
        for (int d = 1; d <= s.declCount(); d++) {
            int first = declFirst[d];
            int end = d < s.declCount() ? declFirst[d + 1] : s.constCount() + 1;
            if (first >= end || !keepsAConstant(d)) {
                continue; // empty inductive line or dropped record: the segment goes on
            }
            int segEnd = declEnd[d];
            // references from records
            for (int r = segStart + 1; r <= segEnd; r++) {
                int a = s.exprA(r);
                switch (s.exprKind(r)) {
                    case ColumnarStore.EXPR_APP -> {
                        count(cnt, a, segStart);
                        count(cnt, s.exprB(r), segStart);
                    }
                    case ColumnarStore.EXPR_LAM, ColumnarStore.EXPR_PI -> {
                        count(cnt, a, segStart);
                        countBody(cnt, body, s.exprB(r), segStart);
                    }
                    case ColumnarStore.EXPR_LET -> {
                        count(cnt, a, segStart);
                        count(cnt, s.letValue(r), segStart);
                        countBody(cnt, body, s.letBody(r), segStart);
                    }
                    case ColumnarStore.EXPR_PROJ -> count(cnt, a, segStart);
                    default -> {
                    }
                }
            }
            // references from the roots of the declaration
            for (int c = first; c < end; c++) {
                if (dropped(c)) {
                    continue;
                }
                countBody(cnt, body, s.constType(c), segStart);
                switch (s.constKind(c)) {
                    case ColumnarStore.CONST_DEF, ColumnarStore.CONST_THM, ColumnarStore.CONST_OPAQUE ->
                            countBody(cnt, body, s.constValue(c), segStart);
                    case ColumnarStore.CONST_REC -> {
                        int rules = s.constField(c, 6);
                        for (int r = 0; r < rules; r++) {
                            countBody(cnt, body, s.constField(c, 9 + 3 * r), segStart);
                        }
                    }
                    default -> {
                    }
                }
            }
            // expectations for the records of this segment that were written as shared nodes
            for (int r = segStart + 1; r <= segEnd; r++) {
                int l = loader.locationOf(r);
                if (l <= 0 || f.nPromoted[l]) {
                    continue;
                }
                int flags = f.nFlags[l];
                assertEquals(cnt[r] >= 2, (flags & LoadTarget.F_SHARED) != 0, "SHARED flag of record " + r + " (count " + cnt[r] + ")");
                assertEquals(body[r], (flags & LoadTarget.F_BODY) != 0, "BODY flag of record " + r);
                int a = s.exprA(r);
                int x = 0;
                int y = 0;
                switch (s.exprKind(r)) {
                    case ColumnarStore.EXPR_APP -> {
                        x = a;
                        y = s.exprB(r);
                    }
                    case ColumnarStore.EXPR_LAM, ColumnarStore.EXPR_PI -> x = a;
                    case ColumnarStore.EXPR_LET -> {
                        x = a;
                        y = s.letValue(r);
                    }
                    default -> {
                    }
                }
                for (int child : new int[] {x, y}) {
                    if (child > segStart && cnt[child] == 1) {
                        if (expectedSingleCount == expectedSingles.length) {
                            expectedSingles = Arrays.copyOf(expectedSingles, expectedSingleCount * 2);
                        }
                        expectedSingles[expectedSingleCount++] = ((long) loader.locationOf(child) << 32) | l;
                    }
                }
            }
            segStart = segEnd;
        }
        long[] got = new long[f.singleCount];
        for (int i = 0; i < f.singleCount; i++) {
            got[i] = ((long) f.singles[2 * i] << 32) | f.singles[2 * i + 1];
        }
        Arrays.sort(got);
        expectedSingles = Arrays.copyOf(expectedSingles, expectedSingleCount);
        Arrays.sort(expectedSingles);
        assertEquals(expectedSingles.length, got.length, "single-referrer pairs");
        assertTrue(Arrays.equals(expectedSingles, got), "single-referrer pairs");
        // promoted nodes are always SHARED
        for (int i = 1; i < f.nodes; i++) {
            if (f.nPromoted[i]) {
                assertTrue((f.nFlags[i] & LoadTarget.F_SHARED) != 0);
            }
        }
    }

    private static void count(int[] cnt, int child, int segStart) {
        if (child > segStart) {
            cnt[child]++;
        }
    }

    private static void countBody(int[] cnt, boolean[] body, int child, int segStart) {
        if (child > segStart) {
            cnt[child]++;
            body[child] = true;
        }
    }

    /** The view of the in-memory target. */
    private final class FakeView extends View {
        @Override
        boolean finished() {
            return fake.finished;
        }

        @Override
        int sharedNodes() {
            return fake.nodes - 1;
        }

        @Override
        int blockRecords() {
            return fake.bRecords;
        }

        @Override
        int blocks() {
            return fake.blocks;
        }

        @Override
        int consts() {
            return fake.consts - 1;
        }

        @Override
        int units() {
            return fake.units;
        }

        @Override
        boolean droppedRecordsHaveUnits() {
            return false;
        }

        @Override
        int distinctNames() {
            return fake.names - 1;
        }

        @Override
        int constOf(int name) {
            return fake.constOf[name];
        }

        @Override
        int cKind(int cid) {
            return fake.cKind[cid];
        }

        @Override
        int cName(int cid) {
            return fake.cName[cid];
        }

        @Override
        int cLparams(int cid) {
            return fake.cLparams[cid];
        }

        @Override
        int cType(int cid) {
            return fake.cType[cid];
        }

        @Override
        int cValue(int cid) {
            return fake.cValue[cid];
        }

        @Override
        int cHint(int cid) {
            return fake.cHint[cid];
        }

        @Override
        int cUnit(int cid) {
            return fake.cUnit[cid];
        }

        @Override
        int uFirstCid(int unit) {
            return fake.uFirstCid[unit];
        }

        @Override
        int uSize(int unit) {
            return fake.uSize[unit];
        }

        @Override
        int uKind(int unit) {
            return fake.uKind[unit];
        }

        @Override
        int[] uClaims(int unit) {
            return fake.uClaims[unit];
        }

        @Override
        int uRepeatOf(int unit) {
            return fake.uRepeat[unit] - 1;
        }

        @Override
        int blockFirst(int block) {
            return fake.blockFirst[block];
        }

        @Override
        int blockRoot(int block) {
            return fake.blockRoot[block];
        }

        @Override
        void hash(Oracle o) {
            hashFake();
        }
    }
}
