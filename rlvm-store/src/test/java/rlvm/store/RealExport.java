package rlvm.store;

import java.lang.management.ManagementFactory;
import java.math.BigInteger;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import rlvm.export.ExportException;
import rlvm.export.ExportReader;
import rlvm.export.ExportSink;
import rlvm.level.LevelStore;
import rlvm.level.NameOracle;

/**
 * Test support and measuring tool: loads a real lean4export file into the store with a miniature of
 * the loader's declaration sweep (ARCHITECTURE.md 3.3: names claimed first, shared part = what the
 * type reaches, one proof block per theorem / opaque, promotion on demand), over the real reader of
 * {@code rlvm-export} and the real level store of {@code rlvm-level}; then checks the result against
 * an oracle that never touches the store: a structural digest of every expression record, computed
 * from the records as they arrive.
 *
 * <p>{@code java -cp ... rlvm.store.RealExport FILE [--no-blocks] [--decode-rounds N]} prints the
 * statistics as one line of JSON-like text.
 */
final class RealExport implements ExportSink {

    /** The level store bound to the store's two adapters. */
    static final class Levels implements IntLists, LevelInfo {
        final LevelStore ls;

        Levels(LevelStore ls) {
            this.ls = ls;
        }

        @Override
        public int intern(int[] buf, int from, int len) {
            return ls.internList(buf, from, len);
        }

        @Override
        public int length(int list) {
            return ls.listLength(list);
        }

        @Override
        public int get(int list, int index) {
            return ls.listGet(list, index);
        }

        @Override
        public void copyTo(int list, int[] dst, int dstOff) {
            int n = ls.listLength(list);
            for (int i = 0; i < n; i++) {
                dst[dstOff + i] = ls.listGet(list, i);
            }
        }

        @Override
        public boolean hasParam(int level) {
            return ls.hasParam(level);
        }

        @Override
        public int paramName(int level) {
            return ls.kind(level) == LevelStore.KIND_PARAM ? ls.paramName(level) : -1;
        }

        @Override
        public int lhs(int level) {
            int k = ls.kind(level);
            return k == LevelStore.KIND_SUCC ? ls.succOf(level) : (k == LevelStore.KIND_MAX || k == LevelStore.KIND_IMAX ? ls.lhs(level) : 0);
        }

        @Override
        public int rhs(int level) {
            int k = ls.kind(level);
            return k == LevelStore.KIND_MAX || k == LevelStore.KIND_IMAX ? ls.rhs(level) : 0;
        }

        @Override
        public int instantiate(int level, int paramNames, int levels) {
            return ls.instantiate(level, paramNames, levels);
        }

        @Override
        public int instantiateList(int levelList, int paramNames, int levels) {
            return ls.instantiateList(levelList, ls.mkSubst(paramNames, levels));
        }
    }

    final Store store;
    final StoreBuilder b;
    final LevelStore baseLevels;
    final Levels base;
    final boolean proofBlocks;
    private NameTable namesForOracle;

    // every expression record, by ordinal (1, 2, ...)
    private int[] kind = new int[1 << 16];
    private int[] xa = new int[1 << 16];
    private int[] xb = new int[1 << 16];
    private int[] xc = new int[1 << 16];
    private long[] dig = new long[1 << 16];
    /** Shared id (&gt; 0), -(global block record + 1), or 0 while the record waits in the open segment. */
    private int[] loc = new int[1 << 16];
    private int next = 1;
    private int segStart = 1;
    /** Ordinal of every block record, by global record number. */
    private int[] recOrd = new int[1 << 16];

    // constants: cid -> type / value ordinals (0 = none), and extra roots (rule right-hand sides)
    private int[] cTypeOrd = new int[1024];
    private int[] cValueOrd = new int[1024];
    private int[] ruleOrds = new int[64];
    private int[] ruleSegStart = new int[64];
    private int ruleCount;

    // the inductive record being read
    private int[] mKind = new int[16];
    private int[] mName = new int[16];
    private int[] mLparams = new int[16];
    private int[] mType = new int[16];
    private int mCount;
    private int[] mRules = new int[16];
    private int mRuleCount;

    // sweep scratch
    private byte[] mark = new byte[1024];
    private int[] localIdx = new int[1024];
    private int[] stack = new int[1024];

    long loadNanos;
    long loadCpuNanos;
    long promotions;
    /** Blocks whose root record was promoted during the load. */
    final java.util.BitSet promotedRoots = new java.util.BitSet();
    long unsafeDropped;

    RealExport(long inputBytes, boolean proofBlocks) {
        this.proofBlocks = proofBlocks;
        NameOracle oracle = new NameOracle() {
            @Override
            public int compareNames(int x, int y) {
                return namesForOracle.compare(x, y);
            }

            @Override
            public void appendName(StringBuilder sb, int nameId) {
                namesForOracle.appendTo(sb, nameId);
            }
        };
        this.baseLevels = new LevelStore(oracle);
        this.base = new Levels(baseLevels);
        this.store = new Store(inputBytes, base, base);
        this.namesForOracle = store.names();
        this.b = store.builder();
    }

    static RealExport load(Path file, boolean proofBlocks) throws Exception {
        RealExport r = new RealExport(java.nio.file.Files.size(file), proofBlocks);
        long t0 = System.nanoTime();
        long c0 = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
        ExportReader.read(file, r);
        // which block roots were promoted: asked now, the end of the load releases the promotion memo
        ProofBlocks blocks = r.store.blocks();
        for (int block = 0; block < blocks.count(); block++) {
            int root = blocks.rootRecord(block);
            if (root >= 0 && blocks.promotedId(blocks.firstRecord(block) + root) != 0) {
                r.promotedRoots.set(block);
            }
        }
        r.b.finish();
        r.loadNanos = System.nanoTime() - t0;
        r.loadCpuNanos = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime() - c0;
        return r;
    }

    // ---- digests: the oracle ---------------------------------------------------------------------

    static long mix(long h, long x) {
        h = (h + 0x9E3779B97F4A7C15L) * 0xBF58476D1CE4E5B9L;
        h ^= h >>> 31;
        h = (h + x) * 0x94D049BB133111EBL;
        return h ^ (h >>> 29);
    }

    private static long bytesDigest(byte[] s, int off, int len) {
        long h = 0x5555 + len;
        for (int i = 0; i < len; i++) {
            h = mix(h, s[off + i]);
        }
        return h;
    }

    private static long limbsDigest(long[] limbs, int n) {
        long h = 0x7777 + n;
        for (int i = 0; i < n; i++) {
            h = mix(h, limbs[i]);
        }
        return h;
    }

    /** Digest of a store node from its words and the digests of its children. */
    private long nodeDigest(Terms t, LitPool pool, int id, long da, long db, long dc) {
        int k = t.kind(id);
        switch (k) {
            case Kind.BVAR:
            case Kind.SORT:
                return mix(k, t.a(id));
            case Kind.CONST:
                return mix(mix(k, t.c(id)), t.b(id));
            case Kind.APP:
            case Kind.LAM:
            case Kind.PI:
                return mix(mix(k, da), db);
            case Kind.LET:
                return mix(mix(mix(k, da), db), dc);
            case Kind.PROJ: {
                int c = t.c(id);
                return mix(mix(mix(k, c > 0 ? store.decls().name(c) : ~c), t.b(id)), da);
            }
            case Kind.NATLIT:
                if (t.isNatBig(id)) {
                    int n = pool.natLimbCount(t.a(id));
                    long[] limbs = new long[n];
                    pool.natLimbs(t.a(id), limbs, 0);
                    return mix(k, limbsDigest(limbs, n));
                }
                return mix(mix(k, 1), t.natSmall(id));
            case Kind.STRLIT: {
                int n = pool.stringByteLength(t.a(id));
                byte[] s = new byte[n];
                pool.stringBytes(t.a(id), s, 0);
                return mix(k, bytesDigest(s, 0, n));
            }
            default:
                throw new IllegalStateException("kind " + k);
        }
    }

    // ---- records ---------------------------------------------------------------------------------

    private int record(int k, int a, int bb, int c, long d) {
        int o = next++;
        if (o == kind.length) {
            int cap = o * 2;
            kind = Arrays.copyOf(kind, cap);
            xa = Arrays.copyOf(xa, cap);
            xb = Arrays.copyOf(xb, cap);
            xc = Arrays.copyOf(xc, cap);
            dig = Arrays.copyOf(dig, cap);
            loc = Arrays.copyOf(loc, cap);
        }
        kind[o] = k;
        xa[o] = a;
        xb[o] = bb;
        xc[o] = c;
        dig[o] = d;
        return o;
    }

    @Override
    public void meta(boolean wellFormed, String exporterName, String exporterVersion, String formatVersion,
            String leanGithash, String leanVersion) {}

    @Override
    public int nameAnonymous() {
        return 0;
    }

    @Override
    public int nameStr(int prefix, byte[] utf8, int off, int len) {
        return store.names().internStr(prefix, utf8, off, len);
    }

    @Override
    public int nameNum(int prefix, long value) {
        return store.names().internNum(prefix, value);
    }

    @Override
    public int nameNumBig(int prefix, byte[] digits, int off, int len) {
        return store.names().internNumBig(prefix, digits, off, len);
    }

    @Override
    public int levelZero() {
        return baseLevels.zero();
    }

    @Override
    public int levelSucc(int level) {
        return baseLevels.succ(level);
    }

    @Override
    public int levelMax(int lhs, int rhs) {
        return baseLevels.max(lhs, rhs);
    }

    @Override
    public int levelIMax(int lhs, int rhs) {
        return baseLevels.imax(lhs, rhs);
    }

    @Override
    public int levelParam(int name) {
        return baseLevels.param(name);
    }

    @Override
    public int exprBVar(int index) {
        return record(Kind.BVAR, index, 0, 0, mix(Kind.BVAR, index));
    }

    @Override
    public int exprSort(int level) {
        return record(Kind.SORT, level, 0, 0, mix(Kind.SORT, level));
    }

    @Override
    public int exprConst(int name, int[] levels, int off, int count) {
        int list = baseLevels.internList(levels, off, count);
        return record(Kind.CONST, name, list, 0, mix(mix(Kind.CONST, name), list));
    }

    @Override
    public int exprApp(int fn, int arg) {
        return record(Kind.APP, fn, arg, 0, mix(mix(Kind.APP, dig[fn]), dig[arg]));
    }

    @Override
    public int exprLam(int binderName, int binderInfo, int type, int body) {
        return record(Kind.LAM, type, body, 0, mix(mix(Kind.LAM, dig[type]), dig[body]));
    }

    @Override
    public int exprForall(int binderName, int binderInfo, int type, int body) {
        return record(Kind.PI, type, body, 0, mix(mix(Kind.PI, dig[type]), dig[body]));
    }

    @Override
    public int exprLet(int binderName, int type, int value, int body, boolean nondep) {
        return record(Kind.LET, type, value, body, mix(mix(mix(Kind.LET, dig[type]), dig[value]), dig[body]));
    }

    @Override
    public int exprProj(int typeName, long index, int struct) {
        return record(Kind.PROJ, struct, (int) index, typeName, mix(mix(mix(Kind.PROJ, typeName), (int) index), dig[struct]));
    }

    @Override
    public int exprNatLit(byte[] digits, int off, int len) {
        if (len <= 19) {
            long v = 0;
            for (int i = 0; i < len; i++) {
                v = v * 10 + (digits[off + i] - '0');
            }
            return record(Kind.NATLIT, (int) v, (int) (v >>> 32), 0, mix(mix(Kind.NATLIT, 1), v));
        }
        BigInteger v = new BigInteger(new String(digits, off, len, java.nio.charset.StandardCharsets.US_ASCII));
        if (v.bitLength() <= 64) {
            long w = v.longValue();
            return record(Kind.NATLIT, (int) w, (int) (w >>> 32), 0, mix(mix(Kind.NATLIT, 1), w));
        }
        int n = (v.bitLength() + 63) / 64;
        long[] limbs = new long[n];
        for (int i = 0; i < n; i++) {
            limbs[i] = v.shiftRight(64 * i).longValue();
        }
        int idx = store.lits().addNat(limbs, 0, n);
        return record(Kind.NATLIT, idx, 0, 1, mix(Kind.NATLIT, limbsDigest(limbs, n)));
    }

    @Override
    public int exprStrLit(byte[] utf8, int off, int len) {
        int idx = store.lits().addString(utf8, off, len);
        return record(Kind.STRLIT, idx, 0, 0, mix(Kind.STRLIT, bytesDigest(utf8, off, len)));
    }

    @Override
    public int exprMData(int expr) {
        return expr;
    }

    // ---- the sweep -------------------------------------------------------------------------------

    private int sharedOf(int ord) {
        int l = loc[ord];
        if (l > 0) {
            return l;
        }
        if (l == 0) {
            throw new IllegalStateException("record " + ord + " was not written");
        }
        int s = b.promote(-l - 1);
        promotions++;
        loc[ord] = s;
        return s;
    }

    private int writeShared(int r) {
        switch (kind[r]) {
            case Kind.BVAR:
                return b.bvar(xa[r]);
            case Kind.SORT:
                return b.sort(xa[r]);
            case Kind.CONST:
                return b.constant(xa[r], xb[r]);
            case Kind.APP:
                return b.app(sharedOf(xa[r]), sharedOf(xb[r]));
            case Kind.LAM:
                return b.lam(sharedOf(xa[r]), sharedOf(xb[r]));
            case Kind.PI:
                return b.pi(sharedOf(xa[r]), sharedOf(xb[r]));
            case Kind.LET:
                return b.let(sharedOf(xa[r]), sharedOf(xb[r]), sharedOf(xc[r]));
            case Kind.PROJ:
                return b.proj(xc[r], xb[r], sharedOf(xa[r]));
            case Kind.NATLIT:
                return xc[r] == 1 ? b.natBig(xa[r]) : b.natSmall((xa[r] & 0xFFFFFFFFL) | ((long) xb[r] << 32));
            default:
                return b.str(xa[r]);
        }
    }

    private int ref(int r, int child) {
        if (child >= segStart && mark[child - segStart] == 0) {
            return ProofBlocks.Writer.local(localIdx[r - segStart] - localIdx[child - segStart]);
        }
        return ProofBlocks.Writer.shared(sharedOf(child));
    }

    /**
     * Writes the open segment. With {@code typeOrd > 0} the records the type does not reach go into
     * one proof block whose root is {@code valueOrd}; returns the block index, or -1.
     */
    private int sweep(boolean block, int typeOrd, int valueOrd) {
        int n = next - segStart;
        if (mark.length < n) {
            mark = new byte[Math.max(n, mark.length * 2)];
            localIdx = new int[mark.length];
        }
        if (!block) {
            for (int r = segStart; r < next; r++) {
                loc[r] = writeShared(r);
            }
            segStart = next;
            return -1;
        }
        Arrays.fill(mark, 0, n, (byte) 0);
        int sp = 0;
        if (typeOrd >= segStart) {
            mark[typeOrd - segStart] = 1;
            stack[sp++] = typeOrd;
        }
        while (sp > 0) {
            int r = stack[--sp];
            int k = kind[r];
            if (sp + 3 > stack.length) {
                stack = Arrays.copyOf(stack, stack.length * 2);
            }
            if (k == Kind.APP || k == Kind.LAM || k == Kind.PI || k == Kind.LET || k == Kind.PROJ) {
                sp = push(xa[r], sp);
                if (k != Kind.PROJ) {
                    sp = push(xb[r], sp);
                    if (k == Kind.LET) {
                        sp = push(xc[r], sp);
                    }
                }
            }
        }
        ProofBlocks.Writer w = b.blockWriter();
        int first = store.blocks().recordCount();
        int local = 0;
        for (int r = segStart; r < next; r++) {
            if (mark[r - segStart] != 0) {
                loc[r] = writeShared(r);
                continue;
            }
            localIdx[r - segStart] = local;
            switch (kind[r]) {
                case Kind.BVAR:
                    w.bvar(xa[r]);
                    break;
                case Kind.SORT:
                    w.sort(xa[r]);
                    break;
                case Kind.CONST:
                    w.constant(b.bindName(xa[r]), xb[r], xa[r]);
                    break;
                case Kind.APP:
                    w.app(ref(r, xa[r]), ref(r, xb[r]));
                    break;
                case Kind.LAM:
                    w.lam(ref(r, xa[r]), ref(r, xb[r]));
                    break;
                case Kind.PI:
                    w.pi(ref(r, xa[r]), ref(r, xb[r]));
                    break;
                case Kind.LET:
                    w.let(ref(r, xa[r]), ref(r, xb[r]), ref(r, xc[r]));
                    break;
                case Kind.PROJ:
                    w.proj(b.bindName(xc[r]), xb[r], ref(r, xa[r]));
                    break;
                case Kind.NATLIT:
                    if (xc[r] == 1) {
                        w.natBig(xa[r]);
                    } else {
                        w.natSmall((xa[r] & 0xFFFFFFFFL) | ((long) xb[r] << 32));
                    }
                    break;
                default:
                    w.str(xa[r]);
                    break;
            }
            if (first + local >= recOrd.length) {
                recOrd = Arrays.copyOf(recOrd, Math.max(recOrd.length * 2, first + local + 1));
            }
            recOrd[first + local] = r;
            loc[r] = -(first + local) - 1;
            local++;
        }
        int rootLocal = valueOrd >= segStart && mark[valueOrd - segStart] == 0 ? localIdx[valueOrd - segStart] : -1;
        int blk = w.finish(rootLocal);
        segStart = next;
        return blk;
    }

    private int push(int child, int sp) {
        if (child >= segStart && mark[child - segStart] == 0) {
            mark[child - segStart] = 1;
            stack[sp++] = child;
        }
        return sp;
    }

    private void remember(int cid, int typeOrd, int valueOrd) {
        if (cid >= cTypeOrd.length) {
            cTypeOrd = Arrays.copyOf(cTypeOrd, cid * 2);
            cValueOrd = Arrays.copyOf(cValueOrd, cid * 2);
        }
        cTypeOrd[cid] = typeOrd;
        cValueOrd[cid] = valueOrd;
    }

    private void single(int kindOfDecl, int name, int[] ids, int lpOff, int lpCount, int type, int value, int hint)
            throws ExportException {
        int lparams = baseLevels.internList(ids, lpOff, lpCount);
        b.beginUnit(kindOfDecl);
        int cid = b.reserve(kindOfDecl, name, lparams);
        if (cid == 0) {
            throw ExportException.malformed("duplicate declaration");
        }
        boolean blocks = proofBlocks && (kindOfDecl == DeclKind.THM || kindOfDecl == DeclKind.OPAQUE);
        int blk = sweep(blocks, type, value);
        int typeId = sharedOf(type);
        int valueWord = 0;
        if (value != 0) {
            int l = loc[value];
            valueWord = l > 0 ? l : (blk >= 0 && store.blocks().blockOfRecord(-l - 1) == blk
                    && store.blocks().rootRecord(blk) == -l - 1 - store.blocks().firstRecord(blk) ? -(blk + 1) : sharedOf(value));
        }
        b.complete(cid, typeId, valueWord, hint);
        b.endUnit();
        remember(cid, type, value);
    }

    @Override
    public void declAxiom(int name, int[] ids, int lpOff, int lpCount, int type, boolean isUnsafe) throws ExportException {
        if (isUnsafe) {
            drop(name);
            return;
        }
        single(DeclKind.AXIOM, name, ids, lpOff, lpCount, type, 0, 0);
    }

    @Override
    public void declDef(int name, int[] ids, int lpOff, int lpCount, int type, int value, int hintKind, int hintHeight,
            int safety, int allOff, int allCount) throws ExportException {
        if (safety != SAFETY_SAFE) {
            drop(name);
            return;
        }
        int hint = hintKind == HINT_OPAQUE ? DeclKind.HINT_OPAQUE
                : hintKind == HINT_ABBREV ? DeclKind.HINT_ABBREV : DeclKind.hintRegular(hintHeight & 0xFFFFFFFFL);
        single(DeclKind.DEFN, name, ids, lpOff, lpCount, type, value, hint);
    }

    @Override
    public void declThm(int name, int[] ids, int lpOff, int lpCount, int type, int value, int allOff, int allCount)
            throws ExportException {
        single(DeclKind.THM, name, ids, lpOff, lpCount, type, value, 0);
    }

    @Override
    public void declOpaque(int name, int[] ids, int lpOff, int lpCount, int type, int value, boolean isUnsafe, int allOff,
            int allCount) throws ExportException {
        if (isUnsafe) {
            drop(name);
            return;
        }
        single(DeclKind.OPAQUE, name, ids, lpOff, lpCount, type, value, 0);
    }

    @Override
    public void declQuot(int name, int[] ids, int lpOff, int lpCount, int type, int kindOfQuot) throws ExportException {
        int k = kindOfQuot == QUOT_TYPE ? DeclKind.QUOT_TYPE : kindOfQuot == QUOT_CTOR ? DeclKind.QUOT_MK
                : kindOfQuot == QUOT_LIFT ? DeclKind.QUOT_LIFT : DeclKind.QUOT_IND;
        single(k, name, ids, lpOff, lpCount, type, 0, 0);
    }

    private void drop(int name) throws ExportException {
        sweep(false, 0, 0);
        if (!b.dropUnsafe(name)) {
            throw ExportException.malformed("duplicate declaration");
        }
        unsafeDropped++;
    }

    @Override
    public void inductBegin(int numTypes, int numCtors, int numRecs) {
        mCount = 0;
        mRuleCount = 0;
    }

    private void member(int k, int name, int[] ids, int lpOff, int lpCount, int type) {
        if (mCount == mKind.length) {
            int cap = mCount * 2;
            mKind = Arrays.copyOf(mKind, cap);
            mName = Arrays.copyOf(mName, cap);
            mLparams = Arrays.copyOf(mLparams, cap);
            mType = Arrays.copyOf(mType, cap);
        }
        mKind[mCount] = k;
        mName[mCount] = name;
        mLparams[mCount] = baseLevels.internList(ids, lpOff, lpCount);
        mType[mCount] = type;
        mCount++;
    }

    @Override
    public void inductType(int name, int[] ids, int lpOff, int lpCount, int type, long numParams, long numIndices,
            int allOff, int allCount, int ctorsOff, int ctorsCount, long numNested, boolean isRec, boolean isUnsafe,
            boolean isReflexive) {
        member(DeclKind.INDUCT, name, ids, lpOff, lpCount, type);
    }

    @Override
    public void inductCtor(int name, int[] ids, int lpOff, int lpCount, int type, int induct, long cidx, long numParams,
            long numFields, boolean isUnsafe) {
        member(DeclKind.CTOR, name, ids, lpOff, lpCount, type);
    }

    @Override
    public void inductRec(int name, int[] ids, int lpOff, int lpCount, int type, int allOff, int allCount, long numParams,
            long numIndices, long numMotives, long numMinors, boolean k, boolean isUnsafe, int numRules) {
        member(DeclKind.REC, name, ids, lpOff, lpCount, type);
    }

    @Override
    public void inductRecRule(int ctor, long numFields, int rhs) {
        if (mRuleCount == mRules.length) {
            mRules = Arrays.copyOf(mRules, mRuleCount * 2);
        }
        mRules[mRuleCount++] = rhs;
    }

    @Override
    public void inductEnd() throws ExportException {
        b.beginUnit(DeclKind.INDUCT);
        int[] cids = new int[mCount];
        for (int i = 0; i < mCount; i++) {
            cids[i] = b.reserve(mKind[i], mName[i], mLparams[i]);
            if (cids[i] == 0) {
                throw ExportException.malformed("duplicate declaration");
            }
        }
        sweep(false, 0, 0);
        for (int i = 0; i < mCount; i++) {
            b.complete(cids[i], sharedOf(mType[i]), 0, 0);
            remember(cids[i], mType[i], 0);
        }
        for (int i = 0; i < mRuleCount; i++) {
            b.markRoot(sharedOf(mRules[i]));
            if (ruleCount == ruleOrds.length) {
                ruleOrds = Arrays.copyOf(ruleOrds, ruleCount * 2);
                ruleSegStart = Arrays.copyOf(ruleSegStart, ruleCount * 2);
            }
            ruleSegStart[ruleCount] = store.terms().segmentStart();
            ruleOrds[ruleCount++] = mRules[i];
        }
        b.endUnit();
    }

    // ---- verification ----------------------------------------------------------------------------

    /** What {@link #verify} found. */
    static final class Report {
        long exprRecords;
        long sharedNodes;
        long blockRecords;
        long blockBytes;
        long blocks;
        long promoted;
        long constants;
        long units;
        long theoremsWithBlocks;
        long rootsChecked;
        long decodedNodes;
        long decodeNanos;
        long decodeCpuNanos;
        long sharedFlagged;
        long bodyFlagged;
        long invariantFlagged;
        long maxOverlayNodes;
        /** Store differs from the export (must be null). */
        String firstError;
        /** Roots that do not pass the structural checks (expected to be none in a good, canonical file). */
        long rootProblems;
        String firstRootProblem;

        @Override
        public String toString() {
            return "{exprRecords=" + exprRecords + ", sharedNodes=" + sharedNodes + ", blockRecords=" + blockRecords
                    + ", blockBytes=" + blockBytes + ", bytesPerBlockRecord="
                    + (blockRecords == 0 ? 0 : Math.round(100.0 * blockBytes / blockRecords) / 100.0) + ", blocks=" + blocks
                    + ", promoted=" + promoted + ", constants=" + constants + ", units=" + units + ", theoremsWithBlocks="
                    + theoremsWithBlocks + ", rootsChecked=" + rootsChecked + ", decodedNodes=" + decodedNodes
                    + ", decodeNsPerNode=" + (decodedNodes == 0 ? 0 : Math.round(10.0 * decodeNanos / decodedNodes) / 10.0)
                    + ", decodeCpuNsPerNode=" + (decodedNodes == 0 ? 0 : Math.round(10.0 * decodeCpuNanos / decodedNodes) / 10.0)
                    + ", blockNodesShared=" + sharedFlagged + ", blockNodesBody=" + bodyFlagged + ", blockNodesInvariant="
                    + invariantFlagged + ", maxOverlayNodes=" + maxOverlayNodes + ", rootProblems=" + rootProblems
                    + ", firstRootProblem=" + firstRootProblem + ", firstError=" + firstError + "}";
        }
    }

    private void fail(Report rep, String message) {
        if (rep.firstError == null) {
            rep.firstError = message;
        }
    }

    /**
     * Checks everything against the oracle digests: every shared node, every record of every proof
     * block (decoded into a worker overlay over a frozen level store), every root; and the summaries
     * of every root (closed, bound, visible, universe parameters declared).
     */
    Report verify(int decodeRounds) {
        Report rep = new Report();
        DeclTable d = store.decls();
        ProofBlocks pb = store.blocks();
        rep.exprRecords = next - 1;
        rep.sharedNodes = store.terms().exportEnd() - 1;
        rep.blockRecords = pb.recordCount();
        rep.blocks = pb.count();
        rep.promoted = pb.promotedCount();
        rep.constants = d.constantCount();
        rep.units = d.unitCount();
        for (int blk = 0; blk < pb.count(); blk++) {
            rep.blockBytes += pb.byteLength(blk);
        }
        // a worker over the frozen level store
        baseLevels.freeze();
        LevelStore workerLevels = baseLevels.newOverlay();
        Levels wl = new Levels(workerLevels);
        LitPool pool = new LitPool(store.lits());
        Overlay o = new Overlay(store, wl, wl, pool, 1L << 31);
        Terms t = new Terms(store.terms(), o);

        // 1. every shared node, in id order (children first)
        int end = store.terms().exportEnd();
        long[] sd = new long[end];
        for (int id = 1; id < end; id++) {
            int k = t.kind(id);
            long da = 0;
            long db = 0;
            long dc = 0;
            if (k == Kind.APP || k == Kind.LAM || k == Kind.PI || k == Kind.LET) {
                da = sd[t.a(id)];
                db = sd[t.b(id)];
                if (k == Kind.LET) {
                    dc = sd[t.c(id)];
                }
            } else if (k == Kind.PROJ) {
                da = sd[t.a(id)];
            }
            sd[id] = nodeDigest(t, pool, id, da, db, dc);
        }
        for (int r = 1; r < next; r++) {
            int l = loc[r];
            if (l > 0 && sd[l] != dig[r]) {
                fail(rep, "shared node " + l + " differs from export record " + r);
            }
            if (l == 0) {
                fail(rep, "export record " + r + " was never written");
            }
        }
        // 2. every block, decoded; every record of it
        long[] od = new long[1024];
        for (int round = 0; round < Math.max(1, decodeRounds); round++) {
            if (round > 0) {
                // timing rounds: nothing but decoding and the session resets a worker would do
                o.reset();
                workerLevels.reset();
                long t0 = System.nanoTime();
                long c0 = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
                long nodes = 0;
                for (int blk = 0; blk < pb.count(); blk++) {
                    int n = pb.nodeCount(blk);
                    if (o.usedBytes() + 32L * n > (16L << 20)) {
                        o.reset();
                    }
                    o.blockRoot(blk);
                    nodes += n;
                }
                long cpu = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime() - c0;
                long wall = System.nanoTime() - t0;
                if (round == decodeRounds - 1) {
                    rep.decodeCpuNanos = cpu;
                    rep.decodeNanos = wall;
                    rep.decodedNodes = nodes;
                }
                continue;
            }
            for (int blk = 0; blk < pb.count(); blk++) {
                int n = pb.nodeCount(blk);
                if (o.usedBytes() + 32L * n > (64L << 20)) {
                    rep.maxOverlayNodes = Math.max(rep.maxOverlayNodes, o.nodeCount());
                    o.reset();
                    workerLevels.reset();
                }
                int before = o.nodeCount();
                int root = o.blockRoot(blk);
                if (o.nodeCount() - before != n) {
                    fail(rep, "block " + blk + " decoded to " + (o.nodeCount() - before) + " nodes, not " + n);
                }
                if (od.length < n) {
                    od = new long[n * 2];
                }
                int base = before + 1;
                int first = pb.firstRecord(blk);
                for (int k = 0; k < n; k++) {
                    int id = (base + k) | Node.OVERLAY_BIT;
                    int kd = t.kind(id);
                    long da = 0;
                    long db = 0;
                    long dc = 0;
                    if (kd == Kind.APP || kd == Kind.LAM || kd == Kind.PI || kd == Kind.LET) {
                        da = childDigest(t.a(id), base, od, sd);
                        db = childDigest(t.b(id), base, od, sd);
                        if (kd == Kind.LET) {
                            dc = childDigest(t.c(id), base, od, sd);
                        }
                    } else if (kd == Kind.PROJ) {
                        da = childDigest(t.a(id), base, od, sd);
                    }
                    od[k] = nodeDigest(t, pool, id, da, db, dc);
                    if (od[k] != dig[recOrd[first + k]]) {
                        fail(rep, "record " + k + " of block " + blk + " differs from export record " + recOrd[first + k]);
                    }
                    int h = t.header(id);
                    if ((h & Node.F_SHARED) != 0) {
                        rep.sharedFlagged++;
                    }
                    if ((h & Node.F_BODY) != 0) {
                        rep.bodyFlagged++;
                    }
                    if ((h & (Node.F_INVARIANT | Node.F_SHARED | Node.F_BODY)) == Node.F_INVARIANT) {
                        rep.invariantFlagged++;
                    }
                }
                int rootLocal = pb.rootRecord(blk);
                if (rootLocal >= 0 && (root != ((base + rootLocal) | Node.OVERLAY_BIT) || (t.header(root) & Node.F_BODY) == 0)) {
                    fail(rep, "root of block " + blk);
                }
            }
        }
        rep.maxOverlayNodes = Math.max(rep.maxOverlayNodes, o.nodeCount());
        o.reset();
        workerLevels.reset();
        // 3. every constant: roots equal the export's, and their summaries pass the structural checks
        for (int cid = 1; cid <= d.constantCount(); cid++) {
            int rank = d.rank(cid);
            int k = d.kind(cid);
            boolean member = (d.flags(cid) & DeclTable.CF_BLOCK_MEMBER) != 0;
            int type = d.type(cid);
            if (sd[type] != dig[cTypeOrd[cid]]) {
                fail(rep, "type of constant " + store.names().toString(d.name(cid)));
            }
            checkRoot(rep, t, wl, cid, type, rank, member, "type");
            int v = d.value(cid);
            if (v != 0) {
                int root;
                if (v > 0) {
                    root = v;
                    if (sd[v] != dig[cValueOrd[cid]]) {
                        fail(rep, "value of constant " + store.names().toString(d.name(cid)));
                    }
                } else {
                    if (o.usedBytes() > (32L << 20)) {
                        o.reset();
                        workerLevels.reset();
                    }
                    root = o.blockRoot(-v - 1);
                    rep.theoremsWithBlocks++;
                    if (loc[cValueOrd[cid]] != -(pb.firstRecord(-v - 1) + pb.rootRecord(-v - 1)) - 1
                            && !promotedRoots.get(-v - 1)) {
                        fail(rep, "block root of " + store.names().toString(d.name(cid)));
                    }
                }
                checkRoot(rep, t, wl, cid, root, rank, false, "value");
            } else if (k == DeclKind.DEFN || k == DeclKind.THM || k == DeclKind.OPAQUE) {
                fail(rep, "constant without a value: " + store.names().toString(d.name(cid)));
            }
        }
        for (int i = 0; i < ruleCount; i++) {
            int id = loc[ruleOrds[i]];
            if (id <= 0 || (id >= ruleSegStart[i] && (t.header(id) & Node.F_BODY) == 0)) {
                fail(rep, "rule right-hand side " + ruleOrds[i] + " is not flagged as a root");
            }
        }
        return rep;
    }

    private static long childDigest(int child, int base, long[] od, long[] sd) {
        return child >= Node.OVERLAY_BIT ? od[(child & Node.INDEX_MASK) - base] : sd[child];
    }

    private static void rootProblem(Report rep, String message) {
        rep.rootProblems++;
        if (rep.firstRootProblem == null) {
            rep.firstRootProblem = message;
        }
    }

    private void checkRoot(Report rep, Terms t, IntLists lists, int cid, int root, int rank, boolean ownUnit, String what) {
        rep.rootsChecked++;
        DeclTable d = store.decls();
        String name = what + " of " + store.names().toString(d.name(cid));
        int h = t.header(root);
        if (Node.looseRange(h) != 0) {
            rootProblem(rep, name + ": loose bound variable");
        }
        if (Node.looseRange(h) == 0 && t.fvMask(root) != 0) {
            fail(rep, name + ": closed root with a free-variable mask");
        }
        if ((h & Node.F_UNRESOLVED) != 0) {
            rootProblem(rep, name + ": unresolved reference");
        }
        int dep = t.depMax(root);
        if (ownUnit ? dep > rank : dep >= rank) {
            rootProblem(rep, name + ": depMax " + dep + " at rank " + rank);
        }
        if (!lists.subsetOf(t.lpSet(root), d.levelParams(cid))) {
            rootProblem(rep, name + ": universe parameter not declared");
        }
        if ((h & Node.F_BODY) == 0 && root >= d.unitFirstTerm(d.unit(cid)) && root < Node.GEN_BASE) {
            fail(rep, name + ": root of its own segment not flagged BODY");
        }
        if (((h & Node.F_HAS_LPARAM) != 0) != (t.lpSet(root) != 0) || ((h & Node.F_HAS_CONST) != 0) != (dep != 0)) {
            fail(rep, name + ": flags and summaries disagree");
        }
    }

    /**
     * WP-A1's layout question on real proofs (JVM only): decode proof blocks, then walk every block
     * from its root reading five fields per node (kind, a, b, both mask words) and the header of each
     * shared child, once over the interleaved 8-int nodes and once over a copy in parallel columns.
     * Prints nanoseconds per visited node for both.
     */
    void layoutBench(int rounds) {
        ProofBlocks pb = store.blocks();
        if (!baseLevels.isFrozen()) {
            baseLevels.freeze();
        }
        Levels wl = new Levels(baseLevels.newOverlay());
        Overlay o = new Overlay(store, wl, wl, new LitPool(store.lits()), 1L << 31);
        int maxNodes = 4_000_000;
        int count = 0;
        int[] roots = new int[pb.count()];
        for (int blk = 0; blk < pb.count() && o.nodeCount() + pb.nodeCount(blk) < maxNodes; blk++) {
            int r = o.blockRoot(blk);
            if (r != 0) {
                roots[count++] = r;
            }
        }
        int[] ov = o.array();
        int[] ex = store.terms().exportArray();
        int n = o.nodeCount() + 1;
        int[] hdr = new int[n];
        int[] ca = new int[n];
        int[] cb = new int[n];
        int[] mlo = new int[n];
        int[] mhi = new int[n];
        for (int i = 1; i < n; i++) {
            hdr[i] = ov[i << 3];
            ca[i] = ov[(i << 3) + 1];
            cb[i] = ov[(i << 3) + 2];
            mlo[i] = ov[(i << 3) + 4];
            mhi[i] = ov[(i << 3) + 5];
        }
        int end = store.terms().exportEnd();
        int[] exHdr = new int[end];
        for (int i = 1; i < end; i++) {
            exHdr[i] = ex[i << 3];
        }
        int[] seen = new int[n];
        int[] st = new int[1 << 16];
        int stamp = 0;
        for (int round = 0; round < rounds; round++) {
            for (int layout = 0; layout < 2; layout++) {
                long visits = 0;
                long sum = 0;
                long t0 = System.nanoTime();
                for (int ri = 0; ri < count; ri++) {
                    stamp++;
                    int sp = 0;
                    st[sp++] = roots[ri];
                    while (sp > 0) {
                        int id = st[--sp];
                        if (id < Node.OVERLAY_BIT) {
                            sum += layout == 0 ? ex[id << 3] : exHdr[id];
                            visits++;
                            continue;
                        }
                        int i = id & Node.INDEX_MASK;
                        if (seen[i] == stamp) {
                            continue;
                        }
                        seen[i] = stamp;
                        visits++;
                        int h;
                        int a;
                        int bb;
                        if (layout == 0) {
                            int w = i << 3;
                            h = ov[w];
                            a = ov[w + 1];
                            bb = ov[w + 2];
                            sum += ov[w + 4] + ov[w + 5];
                        } else {
                            h = hdr[i];
                            a = ca[i];
                            bb = cb[i];
                            sum += mlo[i] + mhi[i];
                        }
                        int k = h & 0xF;
                        if (sp + 3 > st.length) {
                            st = Arrays.copyOf(st, st.length * 2);
                        }
                        if (k == Kind.APP || k == Kind.LAM || k == Kind.PI) {
                            st[sp++] = bb;
                            st[sp++] = a;
                        } else if (k == Kind.LET) {
                            st[sp++] = layout == 0 ? ov[(i << 3) + 3] : ov[(i << 3) + 3];
                            st[sp++] = bb;
                            st[sp++] = a;
                        } else if (k == Kind.PROJ) {
                            st[sp++] = a;
                        }
                    }
                }
                long ns = System.nanoTime() - t0;
                if (round == rounds - 1) {
                    System.out.println("layout " + (layout == 0 ? "interleaved (8 ints per node)" : "columns (5 arrays)") + ": "
                            + Math.round(100.0 * ns / visits) / 100.0 + " ns per visit, " + visits + " visits over " + count
                            + " blocks / " + (n - 1) + " decoded nodes, checksum " + sum);
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        boolean blocks = true;
        boolean layout = false;
        int rounds = 1;
        Path file = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--no-blocks")) {
                blocks = false;
            } else if (args[i].equals("--decode-rounds")) {
                rounds = Integer.parseInt(args[++i]);
            } else if (args[i].equals("--layout")) {
                layout = true;
            } else {
                file = Paths.get(args[i]);
            }
        }
        RealExport r = load(file, blocks);
        long bytes = java.nio.file.Files.size(file);
        System.out.println("load: wallMs=" + r.loadNanos / 1_000_000 + " cpuMs=" + r.loadCpuNanos / 1_000_000
                + " (reader + sweep + store) inputBytes=" + bytes + " promoteCalls=" + r.promotions + " sharedBytes="
                + r.store.sharedBytes() + " names=" + r.store.names().size());
        if (layout) {
            r.layoutBench(8);
            return;
        }
        Report rep = r.verify(rounds);
        System.out.println(rep);
        if (rep.firstError != null || rep.rootProblems != 0) {
            System.exit(1);
        }
    }
}
