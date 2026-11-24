package rlvm.store;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;

/**
 * Test support: a naive object model of terms (trees with sharing by object identity), the naive
 * recursive definitions of everything the store derives or computes, and a small fixture store.
 */
final class Model {

    /** A model term. Fields by kind: see the factory methods. */
    static final class M {
        final int kind;
        final M a;
        final M b;
        final M c;
        final int x;
        final int y;
        final long v;

        M(int kind, M a, M b, M c, int x, int y, long v) {
            this.kind = kind;
            this.a = a;
            this.b = b;
            this.c = c;
            this.x = x;
            this.y = y;
            this.v = v;
        }
    }

    static M bvar(int i) {
        return new M(Kind.BVAR, null, null, null, i, 0, 0);
    }

    static M sort(int level) {
        return new M(Kind.SORT, null, null, null, level, 0, 0);
    }

    static M cnst(int name, int levelList) {
        return new M(Kind.CONST, null, null, null, name, levelList, 0);
    }

    static M app(M f, M x) {
        return new M(Kind.APP, f, x, null, 0, 0, 0);
    }

    static M lam(M d, M b) {
        return new M(Kind.LAM, d, b, null, 0, 0, 0);
    }

    static M pi(M d, M b) {
        return new M(Kind.PI, d, b, null, 0, 0, 0);
    }

    static M let(M t, M v, M b) {
        return new M(Kind.LET, t, v, b, 0, 0, 0);
    }

    static M proj(int structName, int index, M s) {
        return new M(Kind.PROJ, s, null, null, structName, index, 0);
    }

    static M nat(long v) {
        return new M(Kind.NATLIT, null, null, null, 0, 0, v);
    }

    static M natBig(int litIndex) {
        return new M(Kind.NATLIT, null, null, null, litIndex, 1, 0);
    }

    static M str(int litIndex) {
        return new M(Kind.STRLIT, null, null, null, litIndex, 0, 0);
    }

    /** A free variable that exists in an overlay as term {@code id}. */
    static M fvar(int id) {
        return new M(Kind.FVAR, null, null, null, id, 0, 0);
    }

    // ---- fixture -----------------------------------------------------------------------------------

    final Store store;
    final StoreBuilder builder;
    final TestLevels levels;
    final int[] paramNames;
    final int[] levelIds;
    final int[] levelLists;
    /** Names of declared constants, of a name that is never declared, and of one declared late. */
    final int[] constNames;
    final int[] constCids;
    final int undeclaredName;
    final int structName;
    int bigNat;
    int strLit;
    int strLit2;

    Model() {
        this(0);
    }

    Model(long inputBytes) {
        store = new Store(inputBytes);
        levels = new TestLevels(store.lists());
        store.bindLevels(levels);
        builder = store.builder();
        paramNames = new int[] {name("u"), name("v"), name("w")};
        int u = levels.param(paramNames[0]);
        int v = levels.param(paramNames[1]);
        int w = levels.param(paramNames[2]);
        levelIds = new int[] {TestLevels.ZERO, levels.succ(TestLevels.ZERO), u, v, levels.succ(u), levels.max(u, v),
            levels.imax(levels.succ(v), w), levels.max(levels.max(u, v), levels.max(u, v))};
        IntLists ls = store.lists();
        levelLists = new int[] {0, ls.intern(new int[] {u}, 0, 1), ls.intern(new int[] {v, levelIds[1]}, 0, 2),
            ls.intern(new int[] {levelIds[1]}, 0, 1), ls.intern(new int[] {levelIds[6], u}, 0, 2)};
        // constants c0 .. c5 with 0, 1, 2, 0, 1, 2 universe parameters, one unit each
        constNames = new int[6];
        constCids = new int[6];
        int type0 = builder.sort(levelIds[1]);
        for (int i = 0; i < 6; i++) {
            constNames[i] = name("c" + i);
            int np = i % 3;
            int lp = ls.intern(paramNames, 0, np);
            constCids[i] = builder.beginDeclaration(DeclKind.AXIOM, constNames[i], lp);
            builder.endDeclaration(constCids[i], type0, 0, 0);
        }
        structName = constNames[3];
        undeclaredName = name("nowhere");
        bigNat = store.lits().addNat(new long[] {5, 7, 9}, 0, 3);
        byte[] s1 = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] s2 = "hé世😀!".getBytes(StandardCharsets.UTF_8);
        strLit = store.lits().addString(s1, 0, s1.length);
        strLit2 = store.lits().addString(s2, 0, s2.length);
    }

    int name(String dotted) {
        int id = 0;
        for (String part : dotted.split("\\.")) {
            byte[] b = part.getBytes(StandardCharsets.UTF_8);
            id = store.names().internStr(id, b, 0, b.length);
        }
        return id;
    }

    /** Declares Nat, Nat.zero, Nat.succ (and with {@code strings} the string prerequisites) as axioms. */
    void declareLiteralPrerequisites(boolean strings) {
        String[] nat = {"Nat", "Nat.zero", "Nat.succ"};
        String[] str = {"String", "String.ofList", "Char", "Char.ofNat", "List", "List.nil", "List.cons"};
        int type0 = builder.sort(levelIds[1]);
        for (String n : nat) {
            int cid = builder.beginDeclaration(DeclKind.AXIOM, name(n), 0);
            builder.endDeclaration(cid, type0, 0, 0);
        }
        if (strings) {
            for (String n : str) {
                int cid = builder.beginDeclaration(DeclKind.AXIOM, name(n), 0);
                builder.endDeclaration(cid, type0, 0, 0);
            }
        }
    }

    // ---- naive summaries ---------------------------------------------------------------------------

    private final IdentityHashMap<M, Long> rangeMemo = new IdentityHashMap<>();
    private final IdentityHashMap<M, TreeSet<Integer>> freeMemo = new IdentityHashMap<>();
    private final IdentityHashMap<M, TreeSet<Integer>> lpMemo = new IdentityHashMap<>();
    private final IdentityHashMap<M, Integer> depMemo = new IdentityHashMap<>();

    void clearMemos() {
        flagsMemo.clear();
        rangeMemo.clear();
        freeMemo.clear();
        lpMemo.clear();
        depMemo.clear();
        digestMemo.clear();
    }

    /** Exact loose range: largest loose index + 1. */
    long range(M m) {
        Long r = rangeMemo.get(m);
        if (r != null) {
            return r;
        }
        long v;
        switch (m.kind) {
            case Kind.BVAR:
                v = (long) m.x + 1;
                break;
            case Kind.APP:
                v = Math.max(range(m.a), range(m.b));
                break;
            case Kind.LAM:
            case Kind.PI:
                v = Math.max(range(m.a), Math.max(range(m.b) - 1, 0));
                break;
            case Kind.LET:
                v = Math.max(Math.max(range(m.a), range(m.b)), Math.max(range(m.c) - 1, 0));
                break;
            case Kind.PROJ:
                v = range(m.a);
                break;
            default:
                v = 0;
        }
        rangeMemo.put(m, v);
        return v;
    }

    /** Exact set of free de Bruijn indices. */
    TreeSet<Integer> free(M m) {
        TreeSet<Integer> r = freeMemo.get(m);
        if (r != null) {
            return r;
        }
        r = new TreeSet<>();
        switch (m.kind) {
            case Kind.BVAR:
                r.add(m.x);
                break;
            case Kind.APP:
                r.addAll(free(m.a));
                r.addAll(free(m.b));
                break;
            case Kind.LAM:
            case Kind.PI:
                r.addAll(free(m.a));
                under(free(m.b), r);
                break;
            case Kind.LET:
                r.addAll(free(m.a));
                r.addAll(free(m.b));
                under(free(m.c), r);
                break;
            case Kind.PROJ:
                r.addAll(free(m.a));
                break;
            default:
                break;
        }
        freeMemo.put(m, r);
        return r;
    }

    private static void under(TreeSet<Integer> body, TreeSet<Integer> out) {
        for (int i : body) {
            if (i > 0) {
                out.add(i - 1);
            }
        }
    }

    /** The exact mask of a free-variable set: bit i for i &lt; 63, bit 63 for any index &gt;= 63. */
    static long exactMask(TreeSet<Integer> free) {
        long m = 0;
        for (int i : free) {
            m |= 1L << Math.min(i, 63);
        }
        return m;
    }

    TreeSet<Integer> lpSet(M m) {
        TreeSet<Integer> r = lpMemo.get(m);
        if (r != null) {
            return r;
        }
        r = new TreeSet<>();
        switch (m.kind) {
            case Kind.SORT:
                r.addAll(levels.params(m.x));
                break;
            case Kind.CONST:
                r.addAll(levels.paramsOfList(m.y));
                break;
            case Kind.APP:
            case Kind.LAM:
            case Kind.PI:
                r.addAll(lpSet(m.a));
                r.addAll(lpSet(m.b));
                break;
            case Kind.LET:
                r.addAll(lpSet(m.a));
                r.addAll(lpSet(m.b));
                r.addAll(lpSet(m.c));
                break;
            case Kind.PROJ:
                r.addAll(lpSet(m.a));
                break;
            default:
                break;
        }
        lpMemo.put(m, r);
        return r;
    }

    /** depMax with the bindings the names have NOW. */
    int depMax(M m) {
        Integer r = depMemo.get(m);
        if (r != null) {
            return r;
        }
        int v;
        DeclTable d = store.decls();
        switch (m.kind) {
            case Kind.CONST: {
                int cid = store.names().constOf(m.x);
                v = cid > 0 && store.lists().length(m.y) == d.numLevelParams(cid) ? d.rank(cid) : Node.DEP_UNRESOLVED;
                break;
            }
            case Kind.PROJ: {
                int cid = store.names().constOf(m.x);
                v = cid > 0 ? Math.max(d.rank(cid), depMax(m.a)) : Node.DEP_UNRESOLVED;
                break;
            }
            case Kind.NATLIT:
                v = store.specials().natLiteralRank();
                break;
            case Kind.STRLIT:
                v = store.specials().stringLiteralRank();
                break;
            case Kind.APP:
            case Kind.LAM:
            case Kind.PI:
                v = Math.max(depMax(m.a), depMax(m.b));
                break;
            case Kind.LET:
                v = Math.max(Math.max(depMax(m.a), depMax(m.b)), depMax(m.c));
                break;
            default:
                v = 0;
        }
        depMemo.put(m, v);
        return v;
    }

    private final IdentityHashMap<M, Integer> flagsMemo = new IdentityHashMap<>();

    /** Inherited flags, naively (memoised per model node: the DAGs of the tests are deep). */
    int flags(M m) {
        Integer known = flagsMemo.get(m);
        if (known != null) {
            return known;
        }
        int f = 0;
        switch (m.kind) {
            case Kind.CONST:
                f = Node.F_HAS_CONST | (depMax(m) == Node.DEP_UNRESOLVED ? Node.F_UNRESOLVED : 0);
                break;
            case Kind.PROJ:
                f = Node.F_HAS_CONST | Node.F_HAS_PROJ | flags(m.a)
                        | (store.names().constOf(m.x) <= 0 ? Node.F_UNRESOLVED : 0);
                break;
            case Kind.NATLIT:
            case Kind.STRLIT:
                f = Node.F_HAS_LIT | (depMax(m) != 0 ? Node.F_HAS_CONST : 0);
                break;
            case Kind.FVAR:
                f = Node.F_HAS_FVAR;
                break;
            case Kind.APP:
            case Kind.LAM:
            case Kind.PI:
                f = flags(m.a) | flags(m.b);
                break;
            case Kind.LET:
                f = Node.F_HAS_LET | flags(m.a) | flags(m.b) | flags(m.c);
                break;
            default:
                break;
        }
        if (!lpSet(m).isEmpty()) {
            f |= Node.F_HAS_LPARAM;
        }
        flagsMemo.put(m, f);
        return f;
    }

    TreeSet<Integer> setOf(int listId) {
        TreeSet<Integer> s = new TreeSet<>();
        IntLists ls = store.lists();
        for (int i = 0; i < ls.length(listId); i++) {
            s.add(ls.get(listId, i));
        }
        return s;
    }

    // ---- building ----------------------------------------------------------------------------------

    /** Writes the model term into the export region, one node per distinct model object, children first. */
    int toShared(M m, IdentityHashMap<M, Integer> memo) {
        Integer done = memo.get(m);
        if (done != null) {
            return done;
        }
        int id;
        switch (m.kind) {
            case Kind.BVAR:
                id = builder.bvar(m.x);
                break;
            case Kind.SORT:
                id = builder.sort(m.x);
                break;
            case Kind.CONST:
                id = builder.constant(m.x, m.y);
                break;
            case Kind.APP: {
                int a = toShared(m.a, memo);
                int b = toShared(m.b, memo);
                id = builder.app(a, b);
                break;
            }
            case Kind.LAM: {
                int a = toShared(m.a, memo);
                int b = toShared(m.b, memo);
                id = builder.lam(a, b);
                break;
            }
            case Kind.PI: {
                int a = toShared(m.a, memo);
                int b = toShared(m.b, memo);
                id = builder.pi(a, b);
                break;
            }
            case Kind.LET: {
                int a = toShared(m.a, memo);
                int b = toShared(m.b, memo);
                int c = toShared(m.c, memo);
                id = builder.let(a, b, c);
                break;
            }
            case Kind.PROJ:
                id = builder.proj(m.x, m.y, toShared(m.a, memo));
                break;
            case Kind.NATLIT:
                id = m.y == 1 ? builder.natBig(m.x) : builder.natSmall(m.v);
                break;
            case Kind.STRLIT:
                id = builder.str(m.x);
                break;
            default:
                throw new IllegalArgumentException("kind " + m.kind);
        }
        memo.put(m, id);
        return id;
    }

    /** Builds the model term in an overlay (hash-consed). */
    int toOverlay(Overlay o, M m, IdentityHashMap<M, Integer> memo) {
        Integer done = memo.get(m);
        if (done != null) {
            return done;
        }
        int id;
        switch (m.kind) {
            case Kind.BVAR:
                id = o.mkBVar(m.x);
                break;
            case Kind.SORT:
                id = o.mkSort(m.x);
                break;
            case Kind.CONST:
                id = o.mkConstByName(m.x, m.y);
                break;
            case Kind.APP:
                id = o.mkApp(toOverlay(o, m.a, memo), toOverlay(o, m.b, memo));
                break;
            case Kind.LAM:
                id = o.mkLam(toOverlay(o, m.a, memo), toOverlay(o, m.b, memo));
                break;
            case Kind.PI:
                id = o.mkPi(toOverlay(o, m.a, memo), toOverlay(o, m.b, memo));
                break;
            case Kind.LET:
                id = o.mkLet(toOverlay(o, m.a, memo), toOverlay(o, m.b, memo), toOverlay(o, m.c, memo));
                break;
            case Kind.PROJ: {
                int cid = store.names().constOf(m.x);
                id = o.mkProj(cid > 0 ? cid : ~m.x, m.y, toOverlay(o, m.a, memo));
                break;
            }
            case Kind.NATLIT:
                id = m.y == 1 ? o.mkNatLitBig(m.x) : o.mkNatLit(m.v);
                break;
            case Kind.STRLIT:
                id = o.mkStrLit(m.x);
                break;
            case Kind.FVAR:
                id = m.x;
                break;
            default:
                throw new IllegalArgumentException("kind " + m.kind);
        }
        memo.put(m, id);
        return id;
    }

    // ---- structural digests ------------------------------------------------------------------------

    private final IdentityHashMap<M, Long> digestMemo = new IdentityHashMap<>();

    private static long mix(long h, long x) {
        h = (h + 0x9E3779B97F4A7C15L) * 0xBF58476D1CE4E5B9L;
        h ^= h >>> 31;
        h = (h + x) * 0x94D049BB133111EBL;
        return h ^ (h >>> 29);
    }

    private long natBigDigest(int litIndex, LitPool pool) {
        long h = 0x7777;
        int n = pool.natLimbCount(litIndex);
        for (int i = 0; i < n; i++) {
            h = mix(h, pool.natLimb(litIndex, i));
        }
        return h;
    }

    private long strDigest(int litIndex, LitPool pool) {
        byte[] b = new byte[pool.stringByteLength(litIndex)];
        pool.stringBytes(litIndex, b, 0);
        long h = 0x5555;
        for (byte x : b) {
            h = mix(h, x);
        }
        return h;
    }

    /** A 64-bit digest of the structure of a model term. */
    long digest(M m) {
        Long r = digestMemo.get(m);
        if (r != null) {
            return r;
        }
        long h = m.kind;
        switch (m.kind) {
            case Kind.BVAR:
            case Kind.SORT:
            case Kind.FVAR:
                h = mix(h, m.x);
                break;
            case Kind.CONST:
                h = mix(mix(h, m.x), m.y);
                break;
            case Kind.APP:
            case Kind.LAM:
            case Kind.PI:
                h = mix(mix(h, digest(m.a)), digest(m.b));
                break;
            case Kind.LET:
                h = mix(mix(mix(h, digest(m.a)), digest(m.b)), digest(m.c));
                break;
            case Kind.PROJ:
                h = mix(mix(mix(h, m.x), m.y), digest(m.a));
                break;
            case Kind.NATLIT:
                h = m.y == 1 ? mix(h, natBigDigest(m.x, store.lits())) : mix(mix(h, 1), m.v);
                break;
            default:
                h = mix(h, strDigest(m.x, store.lits()));
                break;
        }
        digestMemo.put(m, h);
        return h;
    }

    /** The same digest for a term of the store (shared or overlay). */
    long digest(Terms t, int id, Map<Integer, Long> memo) {
        return digest(t, id, memo, store.lits());
    }

    long digest(Terms t, int id, Map<Integer, Long> memo, LitPool pool) {
        Long r = memo.get(id);
        if (r != null) {
            return r;
        }
        int k = t.kind(id);
        long h = k;
        switch (k) {
            case Kind.BVAR:
            case Kind.SORT:
                h = mix(h, t.a(id));
                break;
            case Kind.FVAR:
                h = mix(h, id);
                break;
            case Kind.CONST:
                h = mix(mix(h, t.c(id)), t.b(id));
                break;
            case Kind.APP:
            case Kind.LAM:
            case Kind.PI:
                h = mix(mix(h, digest(t, t.a(id), memo, pool)), digest(t, t.b(id), memo, pool));
                break;
            case Kind.LET:
                h = mix(mix(mix(h, digest(t, t.a(id), memo, pool)), digest(t, t.b(id), memo, pool)),
                        digest(t, t.c(id), memo, pool));
                break;
            case Kind.PROJ: {
                int c = t.c(id);
                h = mix(mix(mix(h, c > 0 ? store.decls().name(c) : ~c), t.b(id)), digest(t, t.a(id), memo, pool));
                break;
            }
            case Kind.NATLIT:
                h = t.isNatBig(id) ? mix(h, natBigDigest(t.a(id), pool)) : mix(mix(h, 1), t.natSmall(id));
                break;
            case Kind.STRLIT:
                h = mix(h, strDigest(t.a(id), pool));
                break;
            default:
                throw new IllegalStateException("kind " + k + " of term " + Integer.toHexString(id));
        }
        memo.put(id, h);
        return h;
    }

    long digest(Terms t, int id) {
        return digest(t, id, new HashMap<>());
    }

    // ---- naive operations --------------------------------------------------------------------------
    // Plain structural recursion; memoised on (node identity, binder depth) only so that the DAG-shaped
    // random terms do not take exponential time in the tests.

    private static final class OpMemo {
        private final Map<Integer, IdentityHashMap<M, M>> byDepth = new HashMap<>();

        M get(M m, int off) {
            IdentityHashMap<M, M> t = byDepth.get(off);
            return t == null ? null : t.get(m);
        }

        M put(M m, int off, M r) {
            byDepth.computeIfAbsent(off, k -> new IdentityHashMap<>()).put(m, r);
            return r;
        }
    }

    /** {@code m} with loose index {@code off + i} replaced by {@code subst[i]} lifted by {@code off}; higher ones lowered. */
    static M instantiate(M m, M[] subst, int off) {
        return instantiate(m, subst, off, new OpMemo());
    }

    private static M instantiate(M m, M[] subst, int off, OpMemo memo) {
        M done = memo.get(m, off);
        if (done != null) {
            return done;
        }
        M r;
        switch (m.kind) {
            case Kind.BVAR:
                if (m.x < off) {
                    r = m;
                } else if (m.x - off < subst.length) {
                    r = lift(subst[m.x - off], 0, off, 0);
                } else {
                    r = bvar(m.x - subst.length);
                }
                break;
            case Kind.APP:
                r = app(instantiate(m.a, subst, off, memo), instantiate(m.b, subst, off, memo));
                break;
            case Kind.LAM:
                r = lam(instantiate(m.a, subst, off, memo), instantiate(m.b, subst, off + 1, memo));
                break;
            case Kind.PI:
                r = pi(instantiate(m.a, subst, off, memo), instantiate(m.b, subst, off + 1, memo));
                break;
            case Kind.LET:
                r = let(instantiate(m.a, subst, off, memo), instantiate(m.b, subst, off, memo),
                        instantiate(m.c, subst, off + 1, memo));
                break;
            case Kind.PROJ:
                r = proj(m.x, m.y, instantiate(m.a, subst, off, memo));
                break;
            default:
                r = m;
        }
        return memo.put(m, off, r);
    }

    /** Loose indices {@code >= s + off} raised by {@code d} (negative {@code d} lowers). */
    static M lift(M m, int s, int d, int off) {
        return lift(m, s, d, off, new OpMemo());
    }

    private static M lift(M m, int s, int d, int off, OpMemo memo) {
        M done = memo.get(m, off);
        if (done != null) {
            return done;
        }
        M r;
        switch (m.kind) {
            case Kind.BVAR:
                r = m.x >= s + off ? bvar(m.x + d) : m;
                break;
            case Kind.APP:
                r = app(lift(m.a, s, d, off, memo), lift(m.b, s, d, off, memo));
                break;
            case Kind.LAM:
                r = lam(lift(m.a, s, d, off, memo), lift(m.b, s, d, off + 1, memo));
                break;
            case Kind.PI:
                r = pi(lift(m.a, s, d, off, memo), lift(m.b, s, d, off + 1, memo));
                break;
            case Kind.LET:
                r = let(lift(m.a, s, d, off, memo), lift(m.b, s, d, off, memo), lift(m.c, s, d, off + 1, memo));
                break;
            case Kind.PROJ:
                r = proj(m.x, m.y, lift(m.a, s, d, off, memo));
                break;
            default:
                r = m;
        }
        return memo.put(m, off, r);
    }

    /** Free variables {@code fvars[i]} (store ids) replaced by {@code #(off + n - 1 - i)}; last occurrence wins. */
    static M abstractFVars(M m, int[] fvars, int off) {
        return abstractFVars(m, fvars, off, new OpMemo());
    }

    private static M abstractFVars(M m, int[] fvars, int off, OpMemo memo) {
        M done = memo.get(m, off);
        if (done != null) {
            return done;
        }
        M r = m;
        switch (m.kind) {
            case Kind.FVAR:
                for (int i = fvars.length - 1; i >= 0; i--) {
                    if (fvars[i] == m.x) {
                        r = bvar(off + fvars.length - 1 - i);
                        break;
                    }
                }
                break;
            case Kind.APP:
                r = app(abstractFVars(m.a, fvars, off, memo), abstractFVars(m.b, fvars, off, memo));
                break;
            case Kind.LAM:
                r = lam(abstractFVars(m.a, fvars, off, memo), abstractFVars(m.b, fvars, off + 1, memo));
                break;
            case Kind.PI:
                r = pi(abstractFVars(m.a, fvars, off, memo), abstractFVars(m.b, fvars, off + 1, memo));
                break;
            case Kind.LET:
                r = let(abstractFVars(m.a, fvars, off, memo), abstractFVars(m.b, fvars, off, memo),
                        abstractFVars(m.c, fvars, off + 1, memo));
                break;
            case Kind.PROJ:
                r = proj(m.x, m.y, abstractFVars(m.a, fvars, off, memo));
                break;
            default:
                break;
        }
        return memo.put(m, off, r);
    }

    M instantiateLevels(M m, int paramNames, int levelList) {
        return instantiateLevels(m, paramNames, levelList, new OpMemo());
    }

    private M instantiateLevels(M m, int pn, int ll, OpMemo memo) {
        M done = memo.get(m, 0);
        if (done != null) {
            return done;
        }
        M r;
        switch (m.kind) {
            case Kind.SORT:
                r = sort(levels.instantiate(m.x, pn, ll));
                break;
            case Kind.CONST:
                r = cnst(m.x, levels.instantiateList(m.y, pn, ll));
                break;
            case Kind.APP:
                r = app(instantiateLevels(m.a, pn, ll, memo), instantiateLevels(m.b, pn, ll, memo));
                break;
            case Kind.LAM:
                r = lam(instantiateLevels(m.a, pn, ll, memo), instantiateLevels(m.b, pn, ll, memo));
                break;
            case Kind.PI:
                r = pi(instantiateLevels(m.a, pn, ll, memo), instantiateLevels(m.b, pn, ll, memo));
                break;
            case Kind.LET:
                r = let(instantiateLevels(m.a, pn, ll, memo), instantiateLevels(m.b, pn, ll, memo),
                        instantiateLevels(m.c, pn, ll, memo));
                break;
            case Kind.PROJ:
                r = proj(m.x, m.y, instantiateLevels(m.a, pn, ll, memo));
                break;
            default:
                r = m;
        }
        return memo.put(m, 0, r);
    }

    // ---- random terms ------------------------------------------------------------------------------

    /** Options of {@link #random}. */
    static final class Gen {
        int size = 30;
        int maxVar = 5;
        boolean bigVars;
        boolean literals = true;
        boolean unresolved = true;
        boolean lets = true;
        /** Only levels without parameters and constants without universe parameters (no list store traffic). */
        boolean closedLevels;
        /** Free variables (overlay ids) to sprinkle in; empty = none. */
        int[] fvars = new int[0];
    }

    /**
     * A random DAG: every new node takes its children from the nodes made so far, so sub-terms are
     * shared. Returns all nodes in creation order (children before parents); the last one is the root.
     */
    List<M> random(Random rnd, Gen g) {
        List<M> pool = new ArrayList<>();
        int leaves = 3 + rnd.nextInt(4);
        for (int i = 0; i < leaves; i++) {
            pool.add(randomLeaf(rnd, g));
        }
        while (pool.size() < g.size) {
            int k = rnd.nextInt(100);
            M n;
            if (k < 12) {
                n = randomLeaf(rnd, g);
            } else if (k < 55) {
                n = app(pick(rnd, pool), pick(rnd, pool));
            } else if (k < 72) {
                n = lam(pick(rnd, pool), pick(rnd, pool));
            } else if (k < 86) {
                n = pi(pick(rnd, pool), pick(rnd, pool));
            } else if (k < 93 && g.lets) {
                n = let(pick(rnd, pool), pick(rnd, pool), pick(rnd, pool));
            } else {
                n = proj(rnd.nextInt(8) == 0 && g.unresolved ? undeclaredName : structName, rnd.nextInt(3),
                        pick(rnd, pool));
            }
            pool.add(n);
        }
        return pool;
    }

    private static M pick(Random rnd, List<M> pool) {
        int n = pool.size();
        // biased towards recent nodes, so that terms get deep
        int back = (int) (Math.abs(rnd.nextGaussian()) * 4);
        return pool.get(Math.max(0, n - 1 - Math.min(back, n - 1)));
    }

    private M randomLeaf(Random rnd, Gen g) {
        int k = rnd.nextInt(100);
        if (k < 40) {
            if (g.bigVars && rnd.nextInt(6) == 0) {
                int[] big = {61, 62, 63, 64, 65, 70, 130, 65533, 65534, 65535, 70000};
                return bvar(big[rnd.nextInt(big.length)]);
            }
            if (g.maxVar < 0) {
                return sort(levelIds[rnd.nextInt(g.closedLevels ? 2 : levelIds.length)]);
            }
            return bvar(rnd.nextInt(g.maxVar + 1));
        }
        if (k < 50) {
            return sort(levelIds[rnd.nextInt(g.closedLevels ? 2 : levelIds.length)]);
        }
        if (k < 80 && g.closedLevels) {
            return cnst(constNames[rnd.nextBoolean() ? 0 : 3], 0);
        }
        if (k < 80) {
            if (g.unresolved && rnd.nextInt(10) == 0) {
                return cnst(undeclaredName, 0);
            }
            int c = rnd.nextInt(constNames.length);
            int np = c % 3;
            int list = np == 0 ? 0 : (np == 1 ? levelLists[rnd.nextBoolean() ? 1 : 3] : levelLists[rnd.nextBoolean() ? 2 : 4]);
            if (g.unresolved && rnd.nextInt(12) == 0) {
                list = levelLists[rnd.nextInt(levelLists.length)];
            }
            return cnst(constNames[c], list);
        }
        if (g.fvars.length > 0 && k < 92) {
            return fvar(g.fvars[rnd.nextInt(g.fvars.length)]);
        }
        if (!g.literals) {
            return sort(levelIds[rnd.nextInt(g.closedLevels ? 2 : levelIds.length)]);
        }
        switch (rnd.nextInt(4)) {
            case 0:
                return nat(rnd.nextInt(5));
            case 1:
                return nat(rnd.nextLong());
            case 2:
                return natBig(bigNat);
            default:
                return str(rnd.nextBoolean() ? strLit : strLit2);
        }
    }
}
