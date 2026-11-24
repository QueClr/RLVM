package rlvm.export;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Reference sink: the whole export in flat primitive arrays ("columns"), one row per name, level,
 * expression and constant. No object per record, ids are array indices, id 0 is "none" in every id
 * space. Used by the tests and tools of this module; the layout is the contract.
 *
 * <p><b>Frozen-base friendly:</b> rows are only ever appended, in file order, and never changed after
 * the call that created them, and a row refers only to rows with smaller ids. Once
 * {@link ExportReader} has returned, the store is immutable in effect and can be read by any number
 * of threads without locks (publish it safely, e.g. through a final field or {@code Thread.start}).
 * A per-worker overlay can continue every id space at {@code count + 1}.
 *
 * <h2>Names</h2>
 * Id 1 is {@code Name.anonymous}; the others are numbered from 2 in order of first appearance. Names
 * are hash-consed: structurally equal names have the same id, whatever their ids in the file.
 * <pre>
 * nameKind[id]    NAME_ANONYMOUS | NAME_STR | NAME_NUM | NAME_NUM_BIG
 * namePrefix[id]  id of the prefix
 * nameData[id]    STR:     (offset &lt;&lt; 32) | length of the UTF-8 component in bytes[]
 *                 NUM:     the number (0 .. 2^63-1)
 *                 NUM_BIG: (offset &lt;&lt; 32) | length of the decimal digits in bytes[]
 * nameConst[id]   index of the constant declared with this name, 0 if none
 * </pre>
 *
 * <h2>Levels</h2>
 * Id 1 is {@code Level.zero}; the others are numbered from 2 in file order (not hash-consed).
 * <pre>
 * levelKind[id]   LEVEL_ZERO | LEVEL_SUCC | LEVEL_MAX | LEVEL_IMAX | LEVEL_PARAM, plus LEVEL_HAS_PARAM (0x80)
 * levelA[id]      SUCC: operand; MAX / IMAX: left operand; PARAM: name id
 * levelB[id]      MAX / IMAX: right operand
 * </pre>
 *
 * <h2>Expressions</h2>
 * Numbered from 1 in file order (not hash-consed; lean4export already shares structurally equal
 * expressions). Children have smaller ids than their parents.
 * <pre>
 * exprHead[id]    bits 0-3   kind (EXPR_*)
 *                 bit  4     HAS_LEVEL_PARAM: a universe parameter occurs in the term
 *                 bits 8-9   binder info (LAM, PI);  bit 8: nondep (LET)
 *                 bits 12-31 loose bound variable range: 1 + the largest loose de Bruijn index, 0 if closed
 *                            (at most 2^20 - 1, the limit of the official kernel)
 * exprA[id], exprB[id]
 *     BVAR    A = de Bruijn index
 *     SORT    A = level
 *     CONST   A = name,            B = index in wide[] of the level list [n, level_1, ..., level_n]
 *     APP     A = function,        B = argument
 *     LAM/PI  A = binder type,     B = body
 *     LET     A = binder type,     B = index in wide[] of [value, body]
 *     PROJ    A = structure expr,  B = index in wide[] of [type name, field index (saturated at 2^31-1)]
 *     NATLIT  A = offset,          B = length of the canonical decimal digits in bytes[]
 *     STRLIT  A = offset,          B = length of the UTF-8 text in bytes[]
 *     MDATA   A = wrapped expression
 * exprMask[id]    free-variable mask (docs/research/09, section 1.2): bit i (i &lt; 63) is set if the loose
 *                 index i occurs, bit 63 if some loose index &gt;= 63 occurs. Exact when the loose range
 *                 is at most 63; a superset otherwise (bit 62 of a binder is set whenever the body has
 *                 bit 63). Only present if requested.
 * exprBinderName[id]  LAM / PI / LET: binder name. Only present if requested.
 * </pre>
 *
 * <h2>Constants and declarations</h2>
 * Constants are numbered from 1 in file order; an inductive block contributes its types, then its
 * constructors, then its recursors (the order of the official reader). A second constant with the
 * name of an earlier one is rejected.
 * <pre>
 * constKind[c]    CONST_AXIOM | CONST_DEF | CONST_THM | CONST_OPAQUE | CONST_QUOT | CONST_INDUCT | CONST_CTOR | CONST_REC
 * constName[c], constType[c], constValue[c] (DEF, THM, OPAQUE; else 0)
 * constLevelParams[c]  index in wide[] of the name list [n, name_1, ..., name_n]
 * constDecl[c]    ordinal (from 1) of the declaration line the constant comes from
 * constInfo[c]    index in wide[] of the kind-specific fields (lists are indices of [n, ...] lists;
 *                 counts saturate at 2^31-1):
 *     AXIOM   [isUnsafe]
 *     DEF     [hint kind, hint height, safety, all]
 *     THM     [all]
 *     OPAQUE  [isUnsafe, all]
 *     QUOT    [quot kind]
 *     INDUCT  [numParams, numIndices, numNested, flags (1 isRec, 2 isUnsafe, 4 isReflexive), all, ctors]
 *     CTOR    [induct name, cidx, numParams, numFields, isUnsafe]
 *     REC     [numParams, numIndices, numMotives, numMinors, flags (1 k, 2 isUnsafe), all, numRules,
 *              then per rule: ctor name, nfields, rhs expr]
 * declFirstConst[d]  first constant of declaration line d (ordinals from 1)
 * declExprEnd[d]     number of expressions that existed when line d was read
 * </pre>
 * {@code wide[0]} is 0, so index 0 doubles as the empty list.
 */
public final class ColumnarStore implements ExportSink {
    public static final int NAME_ANONYMOUS = 1;
    public static final int NAME_STR = 2;
    public static final int NAME_NUM = 3;
    public static final int NAME_NUM_BIG = 4;

    public static final int LEVEL_ZERO = 1;
    public static final int LEVEL_SUCC = 2;
    public static final int LEVEL_MAX = 3;
    public static final int LEVEL_IMAX = 4;
    public static final int LEVEL_PARAM = 5;
    public static final int LEVEL_HAS_PARAM = 0x80;

    public static final int EXPR_BVAR = 1;
    public static final int EXPR_SORT = 2;
    public static final int EXPR_CONST = 3;
    public static final int EXPR_APP = 4;
    public static final int EXPR_LAM = 5;
    public static final int EXPR_PI = 6;
    public static final int EXPR_LET = 7;
    public static final int EXPR_PROJ = 8;
    public static final int EXPR_NATLIT = 9;
    public static final int EXPR_STRLIT = 10;
    public static final int EXPR_MDATA = 11;
    public static final int EXPR_KIND_MASK = 0xF;
    public static final int HAS_LEVEL_PARAM = 1 << 4;
    public static final int LOOSE_SHIFT = 12;

    public static final int CONST_AXIOM = 1;
    public static final int CONST_DEF = 2;
    public static final int CONST_THM = 3;
    public static final int CONST_OPAQUE = 4;
    public static final int CONST_QUOT = 5;
    public static final int CONST_INDUCT = 6;
    public static final int CONST_CTOR = 7;
    public static final int CONST_REC = 8;

    /** The anonymous name and the level zero. */
    public static final int ANONYMOUS = 1;

    public static final int ZERO = 1;

    // ---- header
    private boolean metaWellFormed;
    private String exporterName;
    private String exporterVersion;
    private String formatVersion;
    private String leanGithash;
    private String leanVersion;

    // ---- names
    private byte[] nameKind = new byte[1024];
    private int[] namePrefix = new int[1024];
    private long[] nameData = new long[1024];
    private int[] nameConst = new int[1024];
    private int nameCount = ANONYMOUS;
    private int[] nameTable = new int[4096];

    // ---- levels
    private byte[] levelKind = new byte[256];
    private int[] levelA = new int[256];
    private int[] levelB = new int[256];
    private int levelCount = ZERO;

    // ---- expressions
    private int[] exprHead;
    private int[] exprA;
    private int[] exprB;
    private long[] exprMask;
    private int[] exprBinderName;
    private int exprCount;
    private int exprCap;

    // ---- pools
    private int[] wide = new int[4096];
    private int wideLen = 1;
    private byte[] bytes = new byte[1 << 16];
    private int bytesLen;

    // ---- constants and declarations
    private byte[] constKind = new byte[1024];
    private int[] constName = new int[1024];
    private int[] constType = new int[1024];
    private int[] constValue = new int[1024];
    private int[] constLevelParams = new int[1024];
    private int[] constInfo = new int[1024];
    private int[] constDecl = new int[1024];
    private int constCount;
    private int[] declFirstConst = new int[1024];
    private int[] declExprEnd = new int[1024];
    private int declCount;

    /** A store with free-variable masks, without binder names, sized on demand. */
    public ColumnarStore() {
        this(0, true, false);
    }

    /**
     * @param expectedFileBytes size of the export to be read, to size the columns up front (0: unknown)
     * @param masks whether to compute {@code exprMask}
     * @param binderNames whether to keep {@code exprBinderName}
     */
    public ColumnarStore(long expectedFileBytes, boolean masks, boolean binderNames) {
        // real exports have 56-60 bytes per expression record and about 1000 bytes per name
        long exprs = Math.min(expectedFileBytes / 54 + 1024, Integer.MAX_VALUE - 64);
        long names = Math.min(expectedFileBytes / 900 + 1024, Integer.MAX_VALUE - 64);
        exprCap = (int) exprs;
        exprHead = new int[exprCap];
        exprA = new int[exprCap];
        exprB = new int[exprCap];
        exprMask = masks ? new long[exprCap] : null;
        exprBinderName = binderNames ? new int[exprCap] : null;
        if (names > nameKind.length) {
            nameKind = new byte[(int) names];
            namePrefix = new int[(int) names];
            nameData = new long[(int) names];
            nameConst = new int[(int) names];
            nameTable = new int[Integer.highestOneBit((int) Math.min(names, 1 << 28)) * 4];
        }
        nameKind[ANONYMOUS] = NAME_ANONYMOUS;
        levelKind[ZERO] = LEVEL_ZERO;
    }

    // ============================================================================================ sink

    @Override
    public void meta(boolean wellFormed, String name, String version, String format, String githash, String lean) {
        metaWellFormed = wellFormed;
        exporterName = name;
        exporterVersion = version;
        formatVersion = format;
        leanGithash = githash;
        leanVersion = lean;
    }

    // ------------------------------------------------------------------------------------------- names

    @Override
    public int nameAnonymous() {
        return ANONYMOUS;
    }

    private static int mix(int h) {
        h ^= h >>> 16;
        h *= 0x7FEB352D;
        h ^= h >>> 15;
        h *= 0x846CA68B;
        return h ^ (h >>> 16);
    }

    @Override
    public int nameStr(int prefix, byte[] utf8, int off, int len) throws ExportException {
        int h = prefix * 0x9E3779B9 + len;
        for (int i = 0; i < len; i++) {
            h = (h ^ utf8[off + i]) * 0x01000193;
        }
        h = mix(h);
        int[] table = nameTable;
        int mask = table.length - 1;
        int slot = h & mask;
        byte[] pool = bytes;
        while (true) {
            int id = table[slot];
            if (id == 0) {
                break;
            }
            if (namePrefix[id] == prefix && nameKind[id] == NAME_STR) {
                long d = nameData[id];
                if ((int) d == len) {
                    int o = (int) (d >>> 32);
                    int i = 0;
                    while (i < len && pool[o + i] == utf8[off + i]) {
                        i++;
                    }
                    if (i == len) {
                        return id;
                    }
                }
            }
            slot = (slot + 1) & mask;
        }
        int o = appendBytes(utf8, off, len);
        return newName(slot, NAME_STR, prefix, ((long) o << 32) | len);
    }

    @Override
    public int nameNum(int prefix, long value) throws ExportException {
        int h = mix(prefix * 0x9E3779B9 + (int) value * 0x85EBCA6B + (int) (value >>> 32) + 0x5bd1e995);
        int[] table = nameTable;
        int mask = table.length - 1;
        int slot = h & mask;
        while (true) {
            int id = table[slot];
            if (id == 0) {
                break;
            }
            if (namePrefix[id] == prefix && nameKind[id] == NAME_NUM && nameData[id] == value) {
                return id;
            }
            slot = (slot + 1) & mask;
        }
        return newName(slot, NAME_NUM, prefix, value);
    }

    @Override
    public int nameNumBig(int prefix, byte[] digits, int off, int len) throws ExportException {
        int h = prefix * 0x9E3779B9 + len + 0x1234567;
        for (int i = 0; i < len; i++) {
            h = (h ^ digits[off + i]) * 0x01000193;
        }
        h = mix(h);
        int[] table = nameTable;
        int mask = table.length - 1;
        int slot = h & mask;
        while (true) {
            int id = table[slot];
            if (id == 0) {
                break;
            }
            if (namePrefix[id] == prefix && nameKind[id] == NAME_NUM_BIG) {
                long d = nameData[id];
                if ((int) d == len) {
                    int o = (int) (d >>> 32);
                    int i = 0;
                    while (i < len && bytes[o + i] == digits[off + i]) {
                        i++;
                    }
                    if (i == len) {
                        return id;
                    }
                }
            }
            slot = (slot + 1) & mask;
        }
        int o = appendBytes(digits, off, len);
        return newName(slot, NAME_NUM_BIG, prefix, ((long) o << 32) | len);
    }

    private int newName(int slot, int kind, int prefix, long data) throws ExportException {
        int id = nameCount + 1;
        if (id == nameKind.length) {
            int cap = grown(id, "names");
            nameKind = Arrays.copyOf(nameKind, cap);
            namePrefix = Arrays.copyOf(namePrefix, cap);
            nameData = Arrays.copyOf(nameData, cap);
            nameConst = Arrays.copyOf(nameConst, cap);
        }
        nameKind[id] = (byte) kind;
        namePrefix[id] = prefix;
        nameData[id] = data;
        nameCount = id;
        nameTable[slot] = id;
        if ((long) id * 2 > nameTable.length) {
            rehashNames();
        }
        return id;
    }

    private void rehashNames() throws ExportException {
        if (nameTable.length >= 1 << 30) {
            throw ExportException.unsupported("too many names");
        }
        int[] table = new int[nameTable.length * 2];
        int mask = table.length - 1;
        for (int id = ANONYMOUS + 1; id <= nameCount; id++) {
            int slot = nameHash(id) & mask;
            while (table[slot] != 0) {
                slot = (slot + 1) & mask;
            }
            table[slot] = id;
        }
        nameTable = table;
    }

    private int nameHash(int id) {
        int prefix = namePrefix[id];
        long d = nameData[id];
        switch (nameKind[id]) {
            case NAME_STR -> {
                int len = (int) d;
                int o = (int) (d >>> 32);
                int h = prefix * 0x9E3779B9 + len;
                for (int i = 0; i < len; i++) {
                    h = (h ^ bytes[o + i]) * 0x01000193;
                }
                return mix(h);
            }
            case NAME_NUM -> {
                return mix(prefix * 0x9E3779B9 + (int) d * 0x85EBCA6B + (int) (d >>> 32) + 0x5bd1e995);
            }
            default -> {
                int len = (int) d;
                int o = (int) (d >>> 32);
                int h = prefix * 0x9E3779B9 + len + 0x1234567;
                for (int i = 0; i < len; i++) {
                    h = (h ^ bytes[o + i]) * 0x01000193;
                }
                return mix(h);
            }
        }
    }

    private static int grown(int cap, String what) throws ExportException {
        if (cap >= Integer.MAX_VALUE - 64) {
            throw ExportException.unsupported("too many " + what);
        }
        return (int) Math.min((long) cap + (cap >> 1) + 1024, Integer.MAX_VALUE - 64);
    }

    private int appendBytes(byte[] src, int off, int len) throws ExportException {
        int o = bytesLen;
        if (o + len > bytes.length || o + len < 0) {
            long cap = Math.max((long) bytes.length * 2, (long) o + len + 1024);
            if ((long) o + len > Integer.MAX_VALUE - 64) {
                throw ExportException.unsupported("more than 2 GiB of name and literal text");
            }
            bytes = Arrays.copyOf(bytes, (int) Math.min(cap, Integer.MAX_VALUE - 64));
        }
        System.arraycopy(src, off, bytes, o, len);
        bytesLen = o + len;
        return o;
    }

    private int wideRoom(int n) throws ExportException {
        int o = wideLen;
        if (o + n > wide.length || o + n < 0) {
            if ((long) o + n > Integer.MAX_VALUE - 64) {
                throw ExportException.unsupported("side pool overflow");
            }
            long cap = Math.max((long) wide.length * 2, (long) o + n + 1024);
            wide = Arrays.copyOf(wide, (int) Math.min(cap, Integer.MAX_VALUE - 64));
        }
        wideLen = o + n;
        return o;
    }

    /** Appends {@code [n, ids...]} to the side pool; the empty list is index 0. */
    private int wideList(int[] ids, int off, int n) throws ExportException {
        if (n == 0) {
            return 0;
        }
        int o = wideRoom(n + 1);
        wide[o] = n;
        System.arraycopy(ids, off, wide, o + 1, n);
        return o;
    }

    private static int sat(long v) {
        return v > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) v;
    }

    // ------------------------------------------------------------------------------------------ levels

    private int newLevel(int kind, int a, int b) throws ExportException {
        int id = levelCount + 1;
        if (id == levelKind.length) {
            int cap = grown(id, "levels");
            levelKind = Arrays.copyOf(levelKind, cap);
            levelA = Arrays.copyOf(levelA, cap);
            levelB = Arrays.copyOf(levelB, cap);
        }
        levelKind[id] = (byte) kind;
        levelA[id] = a;
        levelB[id] = b;
        levelCount = id;
        return id;
    }

    @Override
    public int levelZero() {
        return ZERO;
    }

    @Override
    public int levelSucc(int level) throws ExportException {
        return newLevel(LEVEL_SUCC | (levelKind[level] & LEVEL_HAS_PARAM), level, 0);
    }

    @Override
    public int levelMax(int lhs, int rhs) throws ExportException {
        return newLevel(LEVEL_MAX | ((levelKind[lhs] | levelKind[rhs]) & LEVEL_HAS_PARAM), lhs, rhs);
    }

    @Override
    public int levelIMax(int lhs, int rhs) throws ExportException {
        return newLevel(LEVEL_IMAX | ((levelKind[lhs] | levelKind[rhs]) & LEVEL_HAS_PARAM), lhs, rhs);
    }

    @Override
    public int levelParam(int name) throws ExportException {
        return newLevel(LEVEL_PARAM | LEVEL_HAS_PARAM, name, 0);
    }

    // ------------------------------------------------------------------------------------- expressions

    private void growExprs() throws ExportException {
        int cap = grown(exprCap, "expressions");
        exprHead = Arrays.copyOf(exprHead, cap);
        exprA = Arrays.copyOf(exprA, cap);
        exprB = Arrays.copyOf(exprB, cap);
        if (exprMask != null) {
            exprMask = Arrays.copyOf(exprMask, cap);
        }
        if (exprBinderName != null) {
            exprBinderName = Arrays.copyOf(exprBinderName, cap);
        }
        exprCap = cap;
    }

    private int newExpr() throws ExportException {
        int id = exprCount + 1;
        if (id >= exprCap) {
            growExprs();
        }
        exprCount = id;
        return id;
    }

    private static long under(long bodyMask, int bodyLoose) {
        return (bodyMask >>> 1) | (bodyLoose > 64 ? Long.MIN_VALUE : 0);
    }

    @Override
    public int exprBVar(int index) throws ExportException {
        int id = newExpr();
        exprHead[id] = EXPR_BVAR | ((index + 1) << LOOSE_SHIFT);
        exprA[id] = index;
        if (exprMask != null) {
            exprMask[id] = 1L << Math.min(index, 63);
        }
        return id;
    }

    @Override
    public int exprSort(int level) throws ExportException {
        int id = newExpr();
        exprHead[id] = EXPR_SORT | ((levelKind[level] & LEVEL_HAS_PARAM) != 0 ? HAS_LEVEL_PARAM : 0);
        exprA[id] = level;
        return id;
    }

    @Override
    public int exprConst(int name, int[] levels, int off, int count) throws ExportException {
        int id = newExpr();
        int flags = 0;
        for (int i = 0; i < count; i++) {
            flags |= levelKind[levels[off + i]];
        }
        exprHead[id] = EXPR_CONST | ((flags & LEVEL_HAS_PARAM) != 0 ? HAS_LEVEL_PARAM : 0);
        exprA[id] = name;
        exprB[id] = wideList(levels, off, count);
        return id;
    }

    @Override
    public int exprApp(int fn, int arg) throws ExportException {
        int id = exprCount + 1;
        if (id >= exprCap) {
            growExprs();
        }
        exprCount = id;
        int[] head = exprHead;
        int hf = head[fn];
        int ha = head[arg];
        head[id] = EXPR_APP | ((hf | ha) & HAS_LEVEL_PARAM) | (Math.max(hf >>> LOOSE_SHIFT, ha >>> LOOSE_SHIFT) << LOOSE_SHIFT);
        exprA[id] = fn;
        exprB[id] = arg;
        long[] mask = exprMask;
        if (mask != null) {
            mask[id] = mask[fn] | mask[arg];
        }
        return id;
    }

    private int binder(int kind, int binderName, int binderInfo, int type, int body) throws ExportException {
        int id = exprCount + 1;
        if (id >= exprCap) {
            growExprs();
        }
        exprCount = id;
        int[] head = exprHead;
        int ht = head[type];
        int hb = head[body];
        int lb = hb >>> LOOSE_SHIFT;
        int loose = Math.max(ht >>> LOOSE_SHIFT, lb == 0 ? 0 : lb - 1);
        head[id] = kind | ((ht | hb) & HAS_LEVEL_PARAM) | (binderInfo << 8) | (loose << LOOSE_SHIFT);
        exprA[id] = type;
        exprB[id] = body;
        long[] mask = exprMask;
        if (mask != null) {
            mask[id] = mask[type] | under(mask[body], lb);
        }
        if (exprBinderName != null) {
            exprBinderName[id] = binderName;
        }
        return id;
    }

    @Override
    public int exprLam(int binderName, int binderInfo, int type, int body) throws ExportException {
        return binder(EXPR_LAM, binderName, binderInfo, type, body);
    }

    @Override
    public int exprForall(int binderName, int binderInfo, int type, int body) throws ExportException {
        return binder(EXPR_PI, binderName, binderInfo, type, body);
    }

    @Override
    public int exprLet(int binderName, int type, int value, int body, boolean nondep) throws ExportException {
        int id = newExpr();
        int ht = exprHead[type];
        int hv = exprHead[value];
        int hb = exprHead[body];
        int lb = hb >>> LOOSE_SHIFT;
        int loose = Math.max(Math.max(ht >>> LOOSE_SHIFT, hv >>> LOOSE_SHIFT), lb == 0 ? 0 : lb - 1);
        exprHead[id] = EXPR_LET | ((ht | hv | hb) & HAS_LEVEL_PARAM) | (nondep ? 1 << 8 : 0) | (loose << LOOSE_SHIFT);
        exprA[id] = type;
        int o = wideRoom(2);
        wide[o] = value;
        wide[o + 1] = body;
        exprB[id] = o;
        if (exprMask != null) {
            exprMask[id] = exprMask[type] | exprMask[value] | under(exprMask[body], lb);
        }
        if (exprBinderName != null) {
            exprBinderName[id] = binderName;
        }
        return id;
    }

    @Override
    public int exprProj(int typeName, long index, int struct) throws ExportException {
        int id = newExpr();
        exprHead[id] = EXPR_PROJ | (exprHead[struct] & (HAS_LEVEL_PARAM | (-1 << LOOSE_SHIFT)));
        exprA[id] = struct;
        int o = wideRoom(2);
        wide[o] = typeName;
        wide[o + 1] = sat(index);
        exprB[id] = o;
        if (exprMask != null) {
            exprMask[id] = exprMask[struct];
        }
        return id;
    }

    @Override
    public int exprNatLit(byte[] digits, int off, int len) throws ExportException {
        int id = newExpr();
        exprHead[id] = EXPR_NATLIT;
        exprA[id] = appendBytes(digits, off, len);
        exprB[id] = len;
        return id;
    }

    @Override
    public int exprStrLit(byte[] utf8, int off, int len) throws ExportException {
        int id = newExpr();
        exprHead[id] = EXPR_STRLIT;
        exprA[id] = appendBytes(utf8, off, len);
        exprB[id] = len;
        return id;
    }

    @Override
    public int exprMData(int expr) throws ExportException {
        int id = newExpr();
        exprHead[id] = EXPR_MDATA | (exprHead[expr] & (HAS_LEVEL_PARAM | (-1 << LOOSE_SHIFT)));
        exprA[id] = expr;
        if (exprMask != null) {
            exprMask[id] = exprMask[expr];
        }
        return id;
    }

    // ------------------------------------------------------------------------------------ declarations

    private void newDecl() throws ExportException {
        int d = declCount + 1;
        if (d == declFirstConst.length) {
            int cap = grown(d, "declarations");
            declFirstConst = Arrays.copyOf(declFirstConst, cap);
            declExprEnd = Arrays.copyOf(declExprEnd, cap);
        }
        declFirstConst[d] = constCount + 1;
        declExprEnd[d] = exprCount;
        declCount = d;
    }

    private int newConst(int kind, int name, int[] ids, int lpOff, int lpCount, int type, int value)
            throws ExportException {
        if (nameConst[name] != 0) {
            throw ExportException.malformed("Duplicate declaration: " + nameToString(name));
        }
        int c = constCount + 1;
        if (c == constKind.length) {
            int cap = grown(c, "constants");
            constKind = Arrays.copyOf(constKind, cap);
            constName = Arrays.copyOf(constName, cap);
            constType = Arrays.copyOf(constType, cap);
            constValue = Arrays.copyOf(constValue, cap);
            constLevelParams = Arrays.copyOf(constLevelParams, cap);
            constInfo = Arrays.copyOf(constInfo, cap);
            constDecl = Arrays.copyOf(constDecl, cap);
        }
        constKind[c] = (byte) kind;
        constName[c] = name;
        constType[c] = type;
        constValue[c] = value;
        constLevelParams[c] = wideList(ids, lpOff, lpCount);
        constDecl[c] = declCount;
        constCount = c;
        nameConst[name] = c;
        return c;
    }

    @Override
    public void declAxiom(int name, int[] ids, int lpOff, int lpCount, int type, boolean isUnsafe)
            throws ExportException {
        newDecl();
        int c = newConst(CONST_AXIOM, name, ids, lpOff, lpCount, type, 0);
        int o = wideRoom(1);
        wide[o] = isUnsafe ? 1 : 0;
        constInfo[c] = o;
    }

    @Override
    public void declDef(int name, int[] ids, int lpOff, int lpCount, int type, int value, int hintKind,
            int hintHeight, int safety, int allOff, int allCount) throws ExportException {
        newDecl();
        int c = newConst(CONST_DEF, name, ids, lpOff, lpCount, type, value);
        int all = wideList(ids, allOff, allCount);
        int o = wideRoom(4);
        wide[o] = hintKind;
        wide[o + 1] = hintHeight;
        wide[o + 2] = safety;
        wide[o + 3] = all;
        constInfo[c] = o;
    }

    @Override
    public void declThm(int name, int[] ids, int lpOff, int lpCount, int type, int value, int allOff, int allCount)
            throws ExportException {
        newDecl();
        int c = newConst(CONST_THM, name, ids, lpOff, lpCount, type, value);
        int all = wideList(ids, allOff, allCount);
        int o = wideRoom(1);
        wide[o] = all;
        constInfo[c] = o;
    }

    @Override
    public void declOpaque(int name, int[] ids, int lpOff, int lpCount, int type, int value, boolean isUnsafe,
            int allOff, int allCount) throws ExportException {
        newDecl();
        int c = newConst(CONST_OPAQUE, name, ids, lpOff, lpCount, type, value);
        int all = wideList(ids, allOff, allCount);
        int o = wideRoom(2);
        wide[o] = isUnsafe ? 1 : 0;
        wide[o + 1] = all;
        constInfo[c] = o;
    }

    @Override
    public void declQuot(int name, int[] ids, int lpOff, int lpCount, int type, int kind) throws ExportException {
        newDecl();
        int c = newConst(CONST_QUOT, name, ids, lpOff, lpCount, type, 0);
        int o = wideRoom(1);
        wide[o] = kind;
        constInfo[c] = o;
    }

    @Override
    public void inductBegin(int numTypes, int numCtors, int numRecs) throws ExportException {
        newDecl();
    }

    @Override
    public void inductType(int name, int[] ids, int lpOff, int lpCount, int type, long numParams, long numIndices,
            int allOff, int allCount, int ctorsOff, int ctorsCount, long numNested, boolean isRec,
            boolean isUnsafe, boolean isReflexive) throws ExportException {
        int c = newConst(CONST_INDUCT, name, ids, lpOff, lpCount, type, 0);
        int all = wideList(ids, allOff, allCount);
        int ctors = wideList(ids, ctorsOff, ctorsCount);
        int o = wideRoom(6);
        wide[o] = sat(numParams);
        wide[o + 1] = sat(numIndices);
        wide[o + 2] = sat(numNested);
        wide[o + 3] = (isRec ? 1 : 0) | (isUnsafe ? 2 : 0) | (isReflexive ? 4 : 0);
        wide[o + 4] = all;
        wide[o + 5] = ctors;
        constInfo[c] = o;
    }

    @Override
    public void inductCtor(int name, int[] ids, int lpOff, int lpCount, int type, int induct, long cidx,
            long numParams, long numFields, boolean isUnsafe) throws ExportException {
        int c = newConst(CONST_CTOR, name, ids, lpOff, lpCount, type, 0);
        int o = wideRoom(5);
        wide[o] = induct;
        wide[o + 1] = sat(cidx);
        wide[o + 2] = sat(numParams);
        wide[o + 3] = sat(numFields);
        wide[o + 4] = isUnsafe ? 1 : 0;
        constInfo[c] = o;
    }

    private int ruleCursor;

    @Override
    public void inductRec(int name, int[] ids, int lpOff, int lpCount, int type, int allOff, int allCount,
            long numParams, long numIndices, long numMotives, long numMinors, boolean k, boolean isUnsafe,
            int numRules) throws ExportException {
        int c = newConst(CONST_REC, name, ids, lpOff, lpCount, type, 0);
        int all = wideList(ids, allOff, allCount);
        int o = wideRoom(7 + 3 * numRules);
        wide[o] = sat(numParams);
        wide[o + 1] = sat(numIndices);
        wide[o + 2] = sat(numMotives);
        wide[o + 3] = sat(numMinors);
        wide[o + 4] = (k ? 1 : 0) | (isUnsafe ? 2 : 0);
        wide[o + 5] = all;
        wide[o + 6] = numRules;
        constInfo[c] = o;
        ruleCursor = o + 7;
    }

    @Override
    public void inductRecRule(int ctor, long numFields, int rhs) {
        int o = ruleCursor;
        wide[o] = ctor;
        wide[o + 1] = sat(numFields);
        wide[o + 2] = rhs;
        ruleCursor = o + 3;
    }

    @Override
    public void inductEnd() {}

    // ========================================================================================== access

    public boolean metaWellFormed() {
        return metaWellFormed;
    }

    public String exporterName() {
        return exporterName;
    }

    public String exporterVersion() {
        return exporterVersion;
    }

    public String formatVersion() {
        return formatVersion;
    }

    public String leanGithash() {
        return leanGithash;
    }

    public String leanVersion() {
        return leanVersion;
    }

    /** Largest name id (ids are {@code 1 .. nameCount}). */
    public int nameCount() {
        return nameCount;
    }

    public int levelCount() {
        return levelCount;
    }

    public int exprCount() {
        return exprCount;
    }

    public int constCount() {
        return constCount;
    }

    public int declCount() {
        return declCount;
    }

    // The columns themselves (valid indices: 1 .. count). Do not modify.

    public byte[] nameKindColumn() {
        return nameKind;
    }

    public int[] namePrefixColumn() {
        return namePrefix;
    }

    public long[] nameDataColumn() {
        return nameData;
    }

    public int[] nameConstColumn() {
        return nameConst;
    }

    public byte[] levelKindColumn() {
        return levelKind;
    }

    public int[] levelAColumn() {
        return levelA;
    }

    public int[] levelBColumn() {
        return levelB;
    }

    public int[] exprHeadColumn() {
        return exprHead;
    }

    public int[] exprAColumn() {
        return exprA;
    }

    public int[] exprBColumn() {
        return exprB;
    }

    /** {@code null} if masks were not requested. */
    public long[] exprMaskColumn() {
        return exprMask;
    }

    /** {@code null} if binder names were not requested. */
    public int[] exprBinderNameColumn() {
        return exprBinderName;
    }

    public int[] widePool() {
        return wide;
    }

    public byte[] bytesPool() {
        return bytes;
    }

    public byte[] constKindColumn() {
        return constKind;
    }

    public int[] constNameColumn() {
        return constName;
    }

    public int[] constTypeColumn() {
        return constType;
    }

    public int[] constValueColumn() {
        return constValue;
    }

    public int[] constLevelParamsColumn() {
        return constLevelParams;
    }

    public int[] constInfoColumn() {
        return constInfo;
    }

    public int[] constDeclColumn() {
        return constDecl;
    }

    public int[] declFirstConstColumn() {
        return declFirstConst;
    }

    public int[] declExprEndColumn() {
        return declExprEnd;
    }

    // Row accessors.

    public int exprKind(int id) {
        return exprHead[id] & EXPR_KIND_MASK;
    }

    /** 1 + the largest loose de Bruijn index of the expression, 0 if it is closed. */
    public int exprLoose(int id) {
        return exprHead[id] >>> LOOSE_SHIFT;
    }

    public boolean exprHasLevelParam(int id) {
        return (exprHead[id] & HAS_LEVEL_PARAM) != 0;
    }

    public int exprA(int id) {
        return exprA[id];
    }

    public int exprB(int id) {
        return exprB[id];
    }

    public long exprMask(int id) {
        return exprMask[id];
    }

    /** Binder info of a LAM or PI. */
    public int binderInfo(int id) {
        return (exprHead[id] >>> 8) & 3;
    }

    public int letValue(int id) {
        return wide[exprB[id]];
    }

    public int letBody(int id) {
        return wide[exprB[id] + 1];
    }

    public boolean letNondep(int id) {
        return (exprHead[id] & (1 << 8)) != 0;
    }

    public int projTypeName(int id) {
        return wide[exprB[id]];
    }

    public int projIndex(int id) {
        return wide[exprB[id] + 1];
    }

    public int constLevelCount(int id) {
        return wide[exprB[id]];
    }

    public int constLevel(int id, int i) {
        return wide[exprB[id] + 1 + i];
    }

    /** The digits of a NATLIT or the text of a STRLIT. */
    public String literal(int id) {
        return new String(bytes, exprA[id], exprB[id], StandardCharsets.UTF_8);
    }

    public int nameKind(int id) {
        return nameKind[id];
    }

    public int namePrefix(int id) {
        return namePrefix[id];
    }

    /** The component of a STR name. */
    public String nameString(int id) {
        long d = nameData[id];
        return new String(bytes, (int) (d >>> 32), (int) d, StandardCharsets.UTF_8);
    }

    /** The component of a NUM name. */
    public long nameNumber(int id) {
        return nameData[id];
    }

    /** Dotted rendering for messages (not injective: components may contain dots). */
    public String nameToString(int id) {
        if (id == ANONYMOUS) {
            return "[anonymous]";
        }
        StringBuilder sb = new StringBuilder();
        appendName(sb, id);
        return sb.toString();
    }

    private void appendName(StringBuilder sb, int id) {
        // iterative: collect the chain of prefixes, then print from the root
        int depth = 0;
        for (int n = id; n > ANONYMOUS; n = namePrefix[n]) {
            depth++;
        }
        int[] chain = new int[depth];
        int i = depth;
        for (int n = id; n > ANONYMOUS; n = namePrefix[n]) {
            chain[--i] = n;
        }
        for (i = 0; i < depth; i++) {
            if (i > 0) {
                sb.append('.');
            }
            int n = chain[i];
            switch (nameKind[n]) {
                case NAME_STR -> sb.append(nameString(n));
                case NAME_NUM -> sb.append(nameData[n]);
                default -> {
                    long d = nameData[n];
                    sb.append(new String(bytes, (int) (d >>> 32), (int) d, StandardCharsets.US_ASCII));
                }
            }
        }
    }

    public int levelKind(int id) {
        return levelKind[id] & 0x7F;
    }

    public boolean levelHasParam(int id) {
        return (levelKind[id] & LEVEL_HAS_PARAM) != 0;
    }

    public int levelA(int id) {
        return levelA[id];
    }

    public int levelB(int id) {
        return levelB[id];
    }

    public int constKind(int c) {
        return constKind[c];
    }

    public int constName(int c) {
        return constName[c];
    }

    public int constType(int c) {
        return constType[c];
    }

    public int constValue(int c) {
        return constValue[c];
    }

    /** Field {@code i} of the kind-specific record of constant {@code c} (see the class comment). */
    public int constField(int c, int i) {
        return wide[constInfo[c] + i];
    }

    /** Length of the list stored at pool index {@code list}. */
    public int listSize(int list) {
        return wide[list];
    }

    public int listGet(int list, int i) {
        return wide[list + 1 + i];
    }

    /** Constant declared with name {@code name}, 0 if none. */
    public int constOfName(int name) {
        return nameConst[name];
    }

    /** Bytes held by the columns (capacity, not only the used part). */
    public long memoryBytes() {
        long n = 0;
        n += nameKind.length + 4L * namePrefix.length + 8L * nameData.length + 4L * nameConst.length + 4L * nameTable.length;
        n += levelKind.length + 8L * levelA.length;
        n += 12L * exprHead.length + (exprMask != null ? 8L * exprMask.length : 0)
                + (exprBinderName != null ? 4L * exprBinderName.length : 0);
        n += 4L * wide.length + bytes.length;
        n += 25L * constKind.length + 8L * declFirstConst.length;
        return n;
    }
}
