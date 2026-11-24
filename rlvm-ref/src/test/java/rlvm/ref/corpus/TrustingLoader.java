package rlvm.ref.corpus;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import rlvm.api.LevelOps;
import rlvm.api.RunSignals;
import rlvm.api.WorkerContext;
import rlvm.api.bind.Bindings;
import rlvm.api.bind.LevelBinding;
import rlvm.api.bind.NatBinding;
import rlvm.api.bind.StoreLitAccess;
import rlvm.export.ExportException;
import rlvm.export.ExportReader;
import rlvm.export.ExportSink;
import rlvm.store.DeclKind;
import rlvm.store.DeclTable;
import rlvm.store.IntLists;
import rlvm.store.Kind;
import rlvm.store.LevelInfo;
import rlvm.store.Overlay;
import rlvm.store.Specials;
import rlvm.store.Store;
import rlvm.store.StoreBuilder;
import rlvm.store.Terms;

/**
 * Test-only loader for kernel-level corpus runs: reads an export straight into a {@link Store} and
 * TRUSTS what the file says about inductive types, constructors and recursors (counts, flags, rules),
 * as a checker without inductive admission would. It exists so that the reference kernel can be run
 * on whole libraries on its own; it is not the pipeline (no structural checks, no admission, no proof
 * blocks: every value is a shared term). Terms are written as the records arrive, before the
 * declaration that owns them claims its name, so references to the declaration's own block are
 * late-bound ({@code ~nameId}) and exercise the kernel's resolution by name.
 */
public final class TrustingLoader implements ExportSink {

    public final Bindings bindings = new Bindings();
    public final Store store;
    private final StoreBuilder sb;
    private final DeclTable decls;
    private final LevelBinding lv;
    private Terms shared;

    private static final class Member {
        int cid;
        int name;
        long a;
        long b;
        long c;
        long d;
        boolean flag;
        boolean flag2;
        int[] names1;
        int[] names2;
        int type;
        final List<int[]> rules = new ArrayList<>();
    }

    private final List<Member> types = new ArrayList<>();
    private final List<Member> ctors = new ArrayList<>();
    private final List<Member> recs = new ArrayList<>();
    private Member currentRec;
    private boolean blockUnsafe;
    public int dropped;
    /**
     * Records whose content the store does not keep although it is part of the official term
     * identity: {@code mdata} nodes (dropped) and {@code letE} records with {@code nondep = true}
     * (the flag is dropped). The strict loader declines the declarations that contain them.
     */
    public int mdataRecords;
    public int nondepLets;

    public TrustingLoader() {
        lv = bindings.baseLevels();
        store = new Store(0L, lv, info(lv));
        bindings.bindNames(store.names());
        sb = store.builder();
        decls = store.decls();
    }

    /** Reads a file and returns the loader with the store sealed (published, specials trusted, levels frozen). */
    public static TrustingLoader load(Path file) throws IOException, ExportException {
        TrustingLoader l = new TrustingLoader();
        ExportReader.read(file, l);
        l.seal();
        return l;
    }

    private void seal() {
        sb.finish();
        Specials sp = store.specials();
        for (int which = 0; which < Specials.COUNT; which++) {
            int name = sp.nameOf(which);
            int cid = name == 0 ? 0 : store.names().constOf(name);
            if (cid > 0) {
                sp.bind(which, cid, Specials.VALID);
            }
        }
        store.publishEnv(store.loadedUnits());
        bindings.freeze();
    }

    public WorkerContext newWorkerContext() {
        LevelBinding wl = bindings.newWorkerLevels();
        Overlay ov = new Overlay(store, wl, info(wl), store.lits(), 12L << 30);
        Terms terms = new Terms(store.terms(), ov);
        NatBinding nats = new NatBinding(new StoreLitAccess(terms, ov, store.lits()), bindings.newWorkerNats());
        return new WorkerContext(0, store, terms, ov, wl, store.lits(), wl, nats, RunSignals.NONE);
    }

    static LevelInfo info(LevelOps l) {
        return new LevelInfo() {
            @Override
            public boolean hasParam(int level) {
                return l.hasParam(level);
            }

            @Override
            public int paramName(int level) {
                return l.kind(level) == LevelOps.KIND_PARAM ? l.paramName(level) : -1;
            }

            @Override
            public int lhs(int level) {
                int kind = l.kind(level);
                return kind == LevelOps.KIND_SUCC ? l.succOf(level)
                        : kind == LevelOps.KIND_MAX || kind == LevelOps.KIND_IMAX ? l.lhs(level) : 0;
            }

            @Override
            public int rhs(int level) {
                int kind = l.kind(level);
                return kind == LevelOps.KIND_MAX || kind == LevelOps.KIND_IMAX ? l.rhs(level) : 0;
            }

            @Override
            public int instantiate(int level, int paramNames, int levels) {
                return l.instantiate(level, paramNames, levels);
            }

            @Override
            public int instantiateList(int levelList, int paramNames, int levels) {
                return l.instantiateList(levelList, paramNames, levels);
            }
        };
    }

    private int list(int[] ids, int off, int count) {
        return count == 0 ? IntLists.EMPTY : lv.intern(ids, off, count);
    }

    private static int[] copy(int[] ids, int off, int count) {
        int[] r = new int[count];
        System.arraycopy(ids, off, r, 0, count);
        return r;
    }

    private static int small(long v) throws ExportException {
        if (v < 0 || v > Integer.MAX_VALUE) {
            throw ExportException.unsupported("count out of range: " + v);
        }
        return (int) v;
    }

    private void declared(int cid) throws ExportException {
        if (cid == 0) {
            throw ExportException.malformed("duplicate declaration");
        }
    }

    // ---- header, names, levels ---------------------------------------------------------------------------

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
        return lv.zero();
    }

    @Override
    public int levelSucc(int level) {
        return lv.succ(level);
    }

    @Override
    public int levelMax(int lhs, int rhs) {
        return lv.max(lhs, rhs);
    }

    @Override
    public int levelIMax(int lhs, int rhs) {
        return lv.imax(lhs, rhs);
    }

    @Override
    public int levelParam(int name) {
        return lv.param(name);
    }

    // ---- expressions ---------------------------------------------------------------------------------------

    @Override
    public int exprBVar(int index) {
        return sb.bvar(index);
    }

    @Override
    public int exprSort(int level) {
        return sb.sort(level);
    }

    @Override
    public int exprConst(int name, int[] levels, int off, int count) {
        return sb.constant(name, list(levels, off, count));
    }

    @Override
    public int exprApp(int fn, int arg) {
        return sb.app(fn, arg);
    }

    @Override
    public int exprLam(int binderName, int binderInfo, int type, int body) {
        return sb.lam(type, body);
    }

    @Override
    public int exprForall(int binderName, int binderInfo, int type, int body) {
        return sb.pi(type, body);
    }

    @Override
    public int exprLet(int binderName, int type, int value, int body, boolean nondep) {
        if (nondep) {
            nondepLets++;
        }
        return sb.let(type, value, body);
    }

    @Override
    public int exprProj(int typeName, long index, int struct) throws ExportException {
        return sb.proj(typeName, small(index), struct);
    }

    @Override
    public int exprNatLit(byte[] digits, int off, int len) {
        if (len <= 18) {
            long v = 0;
            for (int i = 0; i < len; i++) {
                v = v * 10 + (digits[off + i] - '0');
            }
            return sb.natSmall(v);
        }
        BigInteger v = new BigInteger(new String(digits, off, len, StandardCharsets.US_ASCII));
        if (v.bitLength() <= 64) {
            return sb.natSmall(v.longValue());
        }
        int n = (v.bitLength() + 63) >>> 6;
        long[] limbs = new long[n];
        for (int i = 0; i < n; i++) {
            limbs[i] = v.shiftRight(64 * i).longValue();
        }
        return sb.natBig(store.lits().addNat(limbs, 0, n));
    }

    @Override
    public int exprStrLit(byte[] utf8, int off, int len) {
        return sb.str(store.lits().addString(utf8, off, len));
    }

    @Override
    public int exprMData(int expr) {
        mdataRecords++;
        return expr;
    }

    // ---- declarations --------------------------------------------------------------------------------------

    @Override
    public void declAxiom(int name, int[] ids, int lpOff, int lpCount, int type, boolean isUnsafe)
            throws ExportException {
        if (isUnsafe) {
            sb.dropUnsafe(name);
            dropped++;
            return;
        }
        declared(sb.declare(DeclKind.AXIOM, name, list(ids, lpOff, lpCount), type, 0, 0));
    }

    @Override
    public void declDef(int name, int[] ids, int lpOff, int lpCount, int type, int value, int hintKind,
            int hintHeight, int safety, int allOff, int allCount) throws ExportException {
        if (safety != SAFETY_SAFE) {
            sb.dropUnsafe(name);
            dropped++;
            return;
        }
        int hint = hintKind == HINT_OPAQUE ? DeclKind.HINT_OPAQUE
                : hintKind == HINT_ABBREV ? DeclKind.HINT_ABBREV : DeclKind.hintRegular(hintHeight & 0xFFFFFFFFL);
        declared(sb.declare(DeclKind.DEFN, name, list(ids, lpOff, lpCount), type, value, hint));
    }

    @Override
    public void declThm(int name, int[] ids, int lpOff, int lpCount, int type, int value, int allOff, int allCount)
            throws ExportException {
        declared(sb.declare(DeclKind.THM, name, list(ids, lpOff, lpCount), type, value, 0));
    }

    @Override
    public void declOpaque(int name, int[] ids, int lpOff, int lpCount, int type, int value, boolean isUnsafe,
            int allOff, int allCount) throws ExportException {
        if (isUnsafe) {
            sb.dropUnsafe(name);
            dropped++;
            return;
        }
        declared(sb.declare(DeclKind.OPAQUE, name, list(ids, lpOff, lpCount), type, value, 0));
    }

    @Override
    public void declQuot(int name, int[] ids, int lpOff, int lpCount, int type, int kind) throws ExportException {
        int k = kind == QUOT_TYPE ? DeclKind.QUOT_TYPE
                : kind == QUOT_CTOR ? DeclKind.QUOT_MK : kind == QUOT_LIFT ? DeclKind.QUOT_LIFT : DeclKind.QUOT_IND;
        declared(sb.declare(k, name, list(ids, lpOff, lpCount), type, 0, 0));
    }

    @Override
    public void inductBegin(int numTypes, int numCtors, int numRecs) {
        types.clear();
        ctors.clear();
        recs.clear();
        currentRec = null;
        blockUnsafe = false;
    }

    @Override
    public void inductType(int name, int[] ids, int lpOff, int lpCount, int type, long numParams, long numIndices,
            int allOff, int allCount, int ctorsOff, int ctorsCount, long numNested, boolean isRec, boolean isUnsafe,
            boolean isReflexive) {
        Member m = new Member();
        m.name = name;
        m.type = type;
        m.cid = list(ids, lpOff, lpCount);
        m.a = numParams;
        m.b = numIndices;
        m.c = numNested;
        m.flag = isRec;
        m.flag2 = isReflexive;
        m.names1 = copy(ids, ctorsOff, ctorsCount);
        m.names2 = copy(ids, allOff, allCount);
        blockUnsafe |= isUnsafe;
        types.add(m);
    }

    @Override
    public void inductCtor(int name, int[] ids, int lpOff, int lpCount, int type, int induct, long cidx,
            long numParams, long numFields, boolean isUnsafe) {
        Member m = new Member();
        m.name = name;
        m.type = type;
        m.cid = list(ids, lpOff, lpCount);
        m.a = induct;
        m.b = cidx;
        m.c = numParams;
        m.d = numFields;
        blockUnsafe |= isUnsafe;
        ctors.add(m);
    }

    @Override
    public void inductRec(int name, int[] ids, int lpOff, int lpCount, int type, int allOff, int allCount,
            long numParams, long numIndices, long numMotives, long numMinors, boolean k, boolean isUnsafe,
            int numRules) {
        Member m = new Member();
        m.name = name;
        m.type = type;
        m.cid = list(ids, lpOff, lpCount);
        m.a = numParams;
        m.b = numIndices;
        m.c = numMotives;
        m.d = numMinors;
        m.flag = k;
        blockUnsafe |= isUnsafe;
        recs.add(m);
        currentRec = m;
    }

    @Override
    public void inductRecRule(int ctor, long numFields, int rhs) throws ExportException {
        currentRec.rules.add(new int[] {ctor, small(numFields), rhs});
    }

    @Override
    public void inductEnd() throws ExportException {
        if (blockUnsafe) {
            for (Member m : types) {
                sb.dropName(m.name);
            }
            for (Member m : ctors) {
                sb.dropName(m.name);
            }
            for (Member m : recs) {
                sb.dropName(m.name);
            }
            sb.dropUnsafe(types.get(0).name);
            dropped++;
            return;
        }
        sb.beginBlock();
        for (Member m : types) {
            int lp = m.cid;
            m.cid = sb.member(DeclKind.INDUCT, m.name, lp, m.type);
            declared(m.cid);
        }
        for (Member m : ctors) {
            int lp = m.cid;
            m.cid = sb.member(DeclKind.CTOR, m.name, lp, m.type);
            declared(m.cid);
        }
        for (Member m : recs) {
            int lp = m.cid;
            m.cid = sb.member(DeclKind.REC, m.name, lp, m.type);
            declared(m.cid);
        }
        sb.endBlock();
        if (shared == null) {
            shared = new Terms(store.terms(), null);
        }
        shared.refresh();
        for (Member m : types) {
            int[] ctorCids = new int[m.names1.length];
            for (int i = 0; i < ctorCids.length; i++) {
                ctorCids[i] = constOf(m.names1[i]);
            }
            int[] all = new int[m.names2.length];
            for (int i = 0; i < all.length; i++) {
                all[i] = constOf(m.names2[i]);
            }
            int numIndices = small(m.b);
            int flags = (m.flag ? DeclTable.IF_REC : 0) | (m.flag2 ? DeclTable.IF_REFLEXIVE : 0);
            if (ctorCids.length == 1) {
                flags |= DeclTable.IF_ONE_CTOR;
                if (numIndices == 0 && !m.flag) {
                    flags |= DeclTable.IF_STRUCT_LIKE;
                    for (Member c : ctors) {
                        if (c.cid == ctorCids[0] && c.d == 0) {
                            flags |= DeclTable.IF_UNIT_LIKE;
                        }
                    }
                }
            }
            decls.setInductive(m.cid, small(m.a), numIndices, ctorCids, ctorCids.length, all, all.length, flags,
                    small(m.c), 0);
        }
        for (Member m : ctors) {
            decls.setConstructor(m.cid, constOf((int) m.a), small(m.b), small(m.c), small(m.d));
        }
        for (Member m : recs) {
            int n = m.rules.size();
            int[] triples = new int[3 * n];
            for (int i = 0; i < n; i++) {
                int[] r = m.rules.get(i);
                triples[3 * i] = constOf(r[0]);
                triples[3 * i + 1] = r[1];
                triples[3 * i + 2] = r[2];
            }
            int numParams = small(m.a);
            int numIndices = small(m.b);
            int numMotives = small(m.c);
            int numMinors = small(m.d);
            decls.setRecursor(m.cid, numParams, numIndices, numMotives, numMinors, m.flag ? DeclTable.RF_K : 0, triples,
                    n, majorInduct(m.type, numParams + numMotives + numMinors + numIndices));
        }
    }

    private int constOf(int name) throws ExportException {
        int cid = store.names().constOf(name);
        if (cid <= 0) {
            throw ExportException.malformed("inductive record names an undeclared constant: "
                    + store.names().toString(name));
        }
        return cid;
    }

    /** The official {@code recursor_val::get_major_induct}: head constant of the major premise's binder type. */
    private int majorInduct(int recType, int majorIdx) throws ExportException {
        int t = recType;
        for (int i = 0; i < majorIdx; i++) {
            if (shared.kind(t) != Kind.PI) {
                throw ExportException.malformed("recursor type has too few binders");
            }
            t = shared.b(t);
        }
        if (shared.kind(t) != Kind.PI) {
            throw ExportException.malformed("recursor type has too few binders");
        }
        t = shared.a(t);
        while (shared.kind(t) == Kind.APP) {
            t = shared.a(t);
        }
        if (shared.kind(t) != Kind.CONST) {
            throw ExportException.malformed("major premise type is not headed by a constant");
        }
        int cid = shared.a(t);
        return cid > 0 ? cid : constOf(~cid);
    }
}
