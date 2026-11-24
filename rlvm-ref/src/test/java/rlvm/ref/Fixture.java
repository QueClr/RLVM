package rlvm.ref;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.function.Executable;
import rlvm.api.EngineConfig;
import rlvm.api.KernelException;
import rlvm.api.LevelOps;
import rlvm.api.NatOps;
import rlvm.api.Reason;
import rlvm.api.RunSignals;
import rlvm.api.WorkerContext;
import rlvm.api.bind.Bindings;
import rlvm.api.bind.LevelBinding;
import rlvm.api.bind.NatBinding;
import rlvm.api.bind.StoreLitAccess;
import rlvm.store.DeclKind;
import rlvm.store.DeclTable;
import rlvm.store.IntLists;
import rlvm.store.Kind;
import rlvm.store.LevelInfo;
import rlvm.store.Node;
import rlvm.store.Overlay;
import rlvm.store.Specials;
import rlvm.store.Store;
import rlvm.store.StoreBuilder;
import rlvm.store.TermOps;
import rlvm.store.Terms;

/**
 * A small hand-made store for kernel tests: declarations are written through the loader-side
 * {@link StoreBuilder} from the term language of {@link Sexp}, the derived facts of inductive types
 * are set by hand (as admission would), and after {@link #seal()} a worker context and a
 * {@link RefKernel} exist and {@link #t} builds terms in the worker's overlay.
 */
final class Fixture implements Sexp.Sink {

    static final int ABBREV = DeclKind.HINT_ABBREV;
    static final int OPAQUE_HINT = DeclKind.HINT_OPAQUE;

    static int regular(int height) {
        return DeclKind.hintRegular(height);
    }

    final Bindings bindings = new Bindings();
    final Store store;
    private final StoreBuilder sb;
    private final DeclTable decls;
    private LevelOps lv;
    private IntLists ls;
    private boolean sealed;
    private boolean extraPhase;

    WorkerContext ctx;
    RefKernel k;
    RefEngine engine;
    private Overlay ov;
    private Terms terms;
    private TermOps termOps;
    private NatOps nats;

    private final Map<String, Integer> refs = new HashMap<>();
    private final Map<String, Integer> strings = new HashMap<>();
    private final List<String> checkable = new ArrayList<>();
    private final Set<String> extra = new HashSet<>();
    private final List<int[]> pendingSpecials = new ArrayList<>();

    Fixture() {
        LevelBinding base = bindings.baseLevels();
        store = new Store(0L, base, info(base));
        bindings.bindNames(store.names());
        sb = store.builder();
        decls = store.decls();
        lv = base;
        ls = base;
    }

    /** The prelude of {@link Prelude}, sealed. */
    static Fixture standard() {
        return standard(null);
    }

    /** The prelude plus the declarations {@code more} adds, sealed. */
    static Fixture standard(Consumer<Fixture> more) {
        Fixture fx = new Fixture();
        Prelude.install(fx);
        if (more != null) {
            fx.extraPhase = true;
            more.accept(fx);
        }
        fx.seal();
        return fx;
    }

    /** The store-side view of a level store (what {@code rlvm.api.bind} will provide). */
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

    // ---- names, lists ----------------------------------------------------------------------------------

    int name(String dotted) {
        int id = 0;
        for (String part : dotted.split("\\.")) {
            byte[] b = part.getBytes(StandardCharsets.UTF_8);
            id = store.names().internStr(id, b, 0, b.length);
        }
        return id;
    }

    /** List id of the name ids of a space-separated list of names. */
    int names(String spaceSeparated) {
        String trimmed = spaceSeparated.trim();
        if (trimmed.isEmpty()) {
            return IntLists.EMPTY;
        }
        String[] parts = trimmed.split("\\s+");
        int[] ids = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            ids[i] = name(parts[i]);
        }
        return ls.intern(ids, 0, ids.length);
    }

    int cid(String name) {
        int c = store.names().constOf(name(name));
        if (c <= 0) {
            throw new IllegalArgumentException("no constant " + name);
        }
        return c;
    }

    int rankOf(String name) {
        return decls.rank(cid(name));
    }

    List<String> checkableNames() {
        return checkable;
    }

    boolean isExtra(String name) {
        return extra.contains(name);
    }

    // ---- declarations ----------------------------------------------------------------------------------

    int axiom(String name, String lparams, String type) {
        return declare(DeclKind.AXIOM, name, lparams, type, null, 0);
    }

    int def(String name, String lparams, String type, String value, int hint) {
        return declare(DeclKind.DEFN, name, lparams, type, value, hint);
    }

    int thm(String name, String lparams, String type, String value) {
        return declare(DeclKind.THM, name, lparams, type, value, 0);
    }

    int opaque(String name, String lparams, String type, String value) {
        return declare(DeclKind.OPAQUE, name, lparams, type, value, 0);
    }

    private int declare(int kind, String name, String lparams, String type, String value, int hint) {
        requireOpen();
        int cid = sb.beginDeclaration(kind, name(name), names(lparams));
        if (cid == 0) {
            throw new IllegalArgumentException("duplicate declaration " + name);
        }
        int ty = Sexp.term(type, this);
        int val = value == null ? 0 : Sexp.term(value, this);
        sb.endDeclaration(cid, ty, val, hint);
        note(name, kind != DeclKind.QUOT_TYPE && kind != DeclKind.QUOT_MK && kind != DeclKind.QUOT_LIFT
                && kind != DeclKind.QUOT_IND);
        return cid;
    }

    private void note(String name, boolean engineChecks) {
        if (engineChecks) {
            checkable.add(name);
        }
        if (extraPhase) {
            extra.add(name);
        }
    }

    /** A shared term written outside any unit: constants that are not declared yet stay unbound ({@code ~name}). */
    int rawShared(String sexp) {
        requireOpen();
        return Sexp.term(sexp, this);
    }

    /** A definition whose value is an already written shared term. */
    int defRaw(String name, String lparams, String type, int value) {
        requireOpen();
        int cid = sb.beginDeclaration(DeclKind.DEFN, name(name), names(lparams));
        sb.endDeclaration(cid, Sexp.term(type, this), value, regular(1));
        note(name, true);
        return cid;
    }

    /** {@code def name : Nat := Nat.succ (.. (Nat.succ Nat.zero))} with {@code depth} successors. */
    int deepSucc(String name, int depth) {
        requireOpen();
        int cid = sb.beginDeclaration(DeclKind.DEFN, name(name), IntLists.EMPTY);
        int type = sb.constant(name("Nat"), IntLists.EMPTY);
        int succ = sb.constant(name("Nat.succ"), IntLists.EMPTY);
        int t = sb.constant(name("Nat.zero"), IntLists.EMPTY);
        for (int i = 0; i < depth; i++) {
            t = sb.app(succ, t);
        }
        sb.endDeclaration(cid, type, t, regular(1));
        note(name, true);
        return cid;
    }

    /**
     * {@code theorem name : Eq Nat (fn (fn .. (fn n0 n0) ..) n0) (lit 0) := Eq.refl (lit 0)}: checking it
     * evaluates the left side with one nested whnf per application (the Nat extension).
     */
    int deepChain(String name, int depth, String fn) {
        requireOpen();
        int cid = sb.beginDeclaration(DeclKind.DEFN, name(name), IntLists.EMPTY);
        int nat = sb.constant(name("Nat"), IntLists.EMPTY);
        int f = sb.constant(name(fn), IntLists.EMPTY);
        int one = sb.natSmall(1L);
        int t = sb.natSmall(0L);
        for (int i = 0; i < depth; i++) {
            t = sb.app(sb.app(f, t), one);
        }
        // (fun (x : Bool) => Nat.zero) (Nat.beq t t): the argument is only type-checked; use Nat.ble t 0 = Bool.true
        int lvl1 = ls.intern(new int[] {lv.succ(lv.zero())}, 0, 1);
        int eq = sb.app(sb.app(sb.app(sb.constant(name("Eq"), lvl1), nat), t), sb.natSmall(depth));
        int refl = sb.app(sb.app(sb.constant(name("Eq.refl"), lvl1), nat), sb.natSmall(depth));
        int thmCidUnused = cid;
        sb.endDeclaration(thmCidUnused, eq, refl, regular(1));
        note(name, true);
        return cid;
    }

    /**
     * Two definitions {@code a := f (f .. (f n0))} and {@code b : Eq a' a'' := Eq.refl a} whose check compares
     * {@code f^n n0} with {@code f^n (id n0)} by congruence, one nested is_def_eq per application.
     */
    int deepEq(String nameA, String nameB, int depth) {
        requireOpen();
        int cidA = sb.beginDeclaration(DeclKind.DEFN, name(nameA), IntLists.EMPTY);
        int nat = sb.constant(name("Nat"), IntLists.EMPTY);
        int f = sb.constant(name("f"), IntLists.EMPTY);
        int n0 = sb.constant(name("n0"), IntLists.EMPTY);
        int lvl1 = ls.intern(new int[] {lv.succ(lv.zero())}, 0, 1);
        int idn0 = sb.app(sb.app(sb.constant(name("id"), lvl1), nat), n0);
        int a = n0;
        int b = idn0;
        for (int i = 0; i < depth; i++) {
            a = sb.app(f, a);
            b = sb.app(f, b);
        }
        sb.endDeclaration(cidA, nat, a, regular(1));
        note(nameA, true);
        int cidB = sb.beginDeclaration(DeclKind.THM, name(nameB), IntLists.EMPTY);
        int eq = sb.app(sb.app(sb.app(sb.constant(name("Eq"), lvl1), nat), a), b);
        int refl = sb.app(sb.app(sb.constant(name("Eq.refl"), lvl1), nat), a);
        sb.endDeclaration(cidB, eq, refl, 0);
        note(nameB, true);
        return cidB;
    }

    /** Registers a string literal (worker-side terms can only use registered strings). */
    void string(String value) {
        requireOpen();
        byte[] b = value.getBytes(StandardCharsets.UTF_8);
        strings.put(value, store.lits().addString(b, 0, b.length));
    }

    /** Marks special {@code which} as bound to {@code name} with a passed guard. */
    void special(int which, String name) {
        pendingSpecials.add(new int[] {which, cid(name), Specials.VALID});
    }

    /** Overrides the guard state of a special (applied at sealing, after the prelude's own bindings). */
    void specialState(int which, String name, int state) {
        pendingSpecials.add(new int[] {which, cid(name), state});
    }

    void quot() {
        declare(DeclKind.QUOT_TYPE, "Quot", "u", "(pi (α : (sort u)) (r : (-> α α Prop)) (sort u))", null, 0);
        declare(DeclKind.QUOT_MK, "Quot.mk", "u",
                "(pi (α : (sort u)) (r : (-> α α Prop)) (a : α) ((@ Quot u) α r))", null, 0);
        declare(DeclKind.QUOT_LIFT, "Quot.lift", "u v",
                "(pi (α : (sort u)) (r : (-> α α Prop)) (β : (sort v)) (f : (-> α β))"
                        + " (h : (pi (a : α) (b : α) (-> (r a b) ((@ Eq v) β (f a) (f b))))) (q : ((@ Quot u) α r)) β)",
                null, 0);
        declare(DeclKind.QUOT_IND, "Quot.ind", "u",
                "(pi (α : (sort u)) (r : (-> α α Prop)) (β : (-> ((@ Quot u) α r) Prop))"
                        + " (mk : (pi (a : α) (β ((@ Quot.mk u) α r a)))) (q : ((@ Quot u) α r)) (β q))",
                null, 0);
        special(Specials.QUOT, "Quot");
        special(Specials.QUOT_MK, "Quot.mk");
        special(Specials.QUOT_LIFT, "Quot.lift");
        special(Specials.QUOT_IND, "Quot.ind");
    }

    /** An inductive type with one recursor at most; the derived facts are given by hand. */
    final class Induct {
        private final String name;
        private final String lparams;
        private final String type;
        private final int numParams;
        private final int numIndices;
        private final boolean isRec;
        private final List<String[]> ctors = new ArrayList<>();
        private final List<Integer> ctorFields = new ArrayList<>();
        private String recName;
        private String recLparams;
        private String recType;
        private int numMotives;
        private int numMinors;
        private boolean k;
        private final List<String[]> rules = new ArrayList<>();
        private final List<Integer> ruleFields = new ArrayList<>();

        Induct(String name, String lparams, String type, int numParams, int numIndices, boolean isRec) {
            this.name = name;
            this.lparams = lparams;
            this.type = type;
            this.numParams = numParams;
            this.numIndices = numIndices;
            this.isRec = isRec;
        }

        Induct ctor(String ctorName, String ctorType, int nfields) {
            ctors.add(new String[] {ctorName, ctorType});
            ctorFields.add(nfields);
            return this;
        }

        Induct rec(String name, String lparams, String type, int numMotives, int numMinors, boolean k) {
            this.recName = name;
            this.recLparams = lparams;
            this.recType = type;
            this.numMotives = numMotives;
            this.numMinors = numMinors;
            this.k = k;
            return this;
        }

        Induct rule(String ctorName, int nfields, String rhs) {
            rules.add(new String[] {ctorName, rhs});
            ruleFields.add(nfields);
            return this;
        }

        void done() {
            requireOpen();
            sb.beginBlock();
            int lp = names(lparams);
            int ind = sb.claimMember(DeclKind.INDUCT, name(name), lp);
            int[] ctorCids = new int[ctors.size()];
            for (int i = 0; i < ctorCids.length; i++) {
                ctorCids[i] = sb.claimMember(DeclKind.CTOR, name(ctors.get(i)[0]), lp);
            }
            int rec = recName == null ? 0 : sb.claimMember(DeclKind.REC, name(recName), names(recLparams));
            sb.setMemberType(ind, Sexp.term(type, Fixture.this));
            for (int i = 0; i < ctorCids.length; i++) {
                sb.setMemberType(ctorCids[i], Sexp.term(ctors.get(i)[1], Fixture.this));
            }
            int[] ruleTriples = new int[3 * rules.size()];
            if (rec != 0) {
                sb.setMemberType(rec, Sexp.term(recType, Fixture.this));
                for (int i = 0; i < rules.size(); i++) {
                    ruleTriples[3 * i] = cid(rules.get(i)[0]);
                    ruleTriples[3 * i + 1] = ruleFields.get(i);
                    ruleTriples[3 * i + 2] = Sexp.term(rules.get(i)[1], Fixture.this);
                }
            }
            sb.endBlock();
            // What admission derives.
            int flags = isRec ? DeclTable.IF_REC : 0;
            if (ctorCids.length == 1) {
                flags |= DeclTable.IF_ONE_CTOR;
                if (numIndices == 0 && !isRec) {
                    flags |= DeclTable.IF_STRUCT_LIKE;
                    if (ctorFields.get(0) == 0) {
                        flags |= DeclTable.IF_UNIT_LIKE;
                    }
                }
            }
            decls.setInductive(ind, numParams, numIndices, ctorCids, ctorCids.length, new int[] {ind}, 1, flags, 0, rec);
            for (int i = 0; i < ctorCids.length; i++) {
                decls.setConstructor(ctorCids[i], ind, i, numParams, ctorFields.get(i));
            }
            if (rec != 0) {
                decls.setRecursor(rec, numParams, numIndices, numMotives, numMinors, k ? DeclTable.RF_K : 0,
                        ruleTriples, rules.size(), ind);
            }
            note(name, false);
        }
    }

    Induct induct(String name, String lparams, String type, int numParams, int numIndices, boolean isRec) {
        return new Induct(name, lparams, type, numParams, numIndices, isRec);
    }

    private void requireOpen() {
        if (sealed) {
            throw new IllegalStateException("fixture is sealed");
        }
    }

    // ---- sealing: publication and the worker side --------------------------------------------------------

    void seal() {
        requireOpen();
        sb.finish();
        for (int[] s : pendingSpecials) {
            store.specials().bind(s[0], s[1], s[2]);
        }
        store.publishEnv(store.loadedUnits());
        bindings.freeze();
        sealed = true;
        LevelBinding wl = bindings.newWorkerLevels();
        ov = new Overlay(store, wl, info(wl), store.lits(), 1L << 31);
        terms = new Terms(store.terms(), ov);
        NatBinding nb = new NatBinding(new StoreLitAccess(terms, ov, store.lits()), bindings.newWorkerNats());
        ctx = new WorkerContext(0, store, terms, ov, wl, store.lits(), wl, nb, RunSignals.NONE);
        lv = wl;
        ls = wl;
        nats = nb;
        termOps = new TermOps(terms, wl, info(wl));
        k = new RefKernel(ctx, EngineConfig.defaults());
        engine = new RefEngine(store, EngineConfig.defaults());
    }

    /** Opens a scope in which everything is visible and the given universe parameters are declared. */
    void begin(String lparams) {
        k.begin(Integer.MAX_VALUE, names(lparams));
    }

    /** A term in the worker's overlay. */
    int t(String sexp) {
        if (!sealed) {
            throw new IllegalStateException("seal the fixture first");
        }
        return Sexp.term(sexp, this);
    }

    int level(String sexp) {
        return Sexp.level(sexp, this);
    }

    void bind(String name, int term) {
        refs.put(name, term);
    }

    String show(int term) {
        return k.describe(term);
    }

    void assertTerm(String expected, int actual) {
        int exp = t(expected);
        if (exp == actual) {
            return;
        }
        boolean lit = (terms.header(exp) & Node.F_HAS_LIT) != 0;
        if (lit && termOps.eqv(exp, actual)) {
            return;
        }
        fail("expected " + show(exp) + " (" + expected + ") but was " + show(actual)
                + (termOps.eqv(exp, actual) ? "  [structurally equal, different ids]" : ""));
    }

    void rejects(Reason reason, Executable body) {
        KernelException e = assertThrows(KernelException.class, body);
        assertEquals(reason, e.reason());
    }

    // ---- Sexp.Sink -------------------------------------------------------------------------------------

    @Override
    public int bvar(int index) {
        return sealed ? ov.mkBVar(index) : sb.bvar(index);
    }

    @Override
    public int sort(int level) {
        return sealed ? ov.mkSort(level) : sb.sort(level);
    }

    @Override
    public int constant(String name, int[] levels) {
        int list = levels.length == 0 ? IntLists.EMPTY : ls.intern(levels, 0, levels.length);
        if (sealed) {
            return ov.mkConst(cid(name), list);
        }
        return sb.constant(name(name), list);
    }

    @Override
    public int app(int fn, int arg) {
        return sealed ? ov.mkApp(fn, arg) : sb.app(fn, arg);
    }

    @Override
    public int lam(int domain, int body) {
        return sealed ? ov.mkLam(domain, body) : sb.lam(domain, body);
    }

    @Override
    public int pi(int domain, int body) {
        return sealed ? ov.mkPi(domain, body) : sb.pi(domain, body);
    }

    @Override
    public int let(int type, int value, int body) {
        return sealed ? ov.mkLet(type, value, body) : sb.let(type, value, body);
    }

    @Override
    public int proj(String structName, int index, int struct) {
        return sealed ? ov.mkProj(cid(structName), index, struct) : sb.proj(name(structName), index, struct);
    }

    @Override
    public int natLit(String decimal) {
        BigInteger v = new BigInteger(decimal);
        if (v.bitLength() <= 64) {
            long w = v.longValue();
            return sealed ? ov.mkNatLit(w) : sb.natSmall(w);
        }
        if (!sealed) {
            int n = (v.bitLength() + 63) >>> 6;
            long[] limbs = new long[n];
            for (int i = 0; i < n; i++) {
                limbs[i] = v.shiftRight(64 * i).longValue();
            }
            return sb.natBig(store.lits().addNat(limbs, 0, n));
        }
        // Worker side: computed through the Nat operations, 32 bits at a time.
        int r = ov.mkNatLit(0L);
        int base = ov.mkNatLit(1L << 32);
        for (int i = (v.bitLength() + 31) / 32 - 1; i >= 0; i--) {
            long chunk = v.shiftRight(32 * i).longValue() & 0xFFFFFFFFL;
            r = nats.binary(NatOps.ADD, nats.binary(NatOps.MUL, r, base), ov.mkNatLit(chunk));
        }
        return r;
    }

    @Override
    public int strLit(String value) {
        Integer idx = strings.get(value);
        if (idx == null) {
            if (sealed) {
                throw new IllegalArgumentException("string \"" + value + "\" was not registered before sealing");
            }
            string(value);
            idx = strings.get(value);
        }
        return sealed ? ov.mkStrLit(idx) : sb.str(idx);
    }

    @Override
    public int levelZero() {
        return lv.zero();
    }

    @Override
    public int levelSucc(int l) {
        return lv.succ(l);
    }

    @Override
    public int levelMax(int a, int b) {
        return lv.max(a, b);
    }

    @Override
    public int levelIMax(int a, int b) {
        return lv.imax(a, b);
    }

    @Override
    public int levelParam(String name) {
        return lv.param(name(name));
    }

    @Override
    public int ref(String name) {
        Integer t = refs.get(name);
        if (t == null) {
            throw new IllegalArgumentException("unbound $" + name);
        }
        return t;
    }

    static boolean isLiteral(Terms terms, int t) {
        int kind = terms.kind(t);
        return kind == Kind.NATLIT || kind == Kind.STRLIT;
    }
}
