package rlvm.store;

import java.util.Arrays;

/**
 * Syntactic operations on terms: the substitution algebra of the official kernel
 * ({@code refs/lean4/src/kernel/instantiate.cpp}, {@code abstract.cpp}, {@code expr_eq_fn.cpp}) over
 * term ids. Results are built in the caller's {@link Overlay}; inputs may be shared or overlay ids.
 * Every operation is iterative with explicit, heap-allocated stacks: term depth is
 * attacker-controlled (19,999 in {@code perf/app-lam}). Every traversal carries a per-call memo
 * keyed by (term, binder depth), so a DAG-shaped term costs time linear in its number of nodes, and
 * prunes on the node summaries (loose range, HAS_FVAR, HAS_LPARAM, free-variable mask).
 *
 * <p>Used by the reference kernel and by inductive admission. The machine does not substitute; it
 * may use {@link #eqv} and the traversal helpers.
 *
 * <p>Not thread-safe; one instance per worker (it holds the scratch stacks and memo tables).
 */
public final class TermOps {

    private static final int M_INST = 0;
    private static final int M_INST_REV = 1;
    private static final int M_ABSTRACT = 2;
    private static final int M_LIFT = 3;
    private static final int M_LOWER = 4;
    private static final int M_LEVELS = 5;
    private static final int M_REBIND = 6;

    private final Terms terms;
    private final Overlay ov;
    private final IntLists lists;
    private final DeclTable decls;
    private final NameTable names;
    private final Specials specials;
    private LevelInfo levels;
    private LitPool lits;

    private final Rewriter main = new Rewriter();
    /** Lifts substituted terms under binders while {@link #main} runs. */
    private final Rewriter aux = new Rewriter();
    private final Memo pairs = new Memo();
    private int[] pairStack = new int[64];
    private final int[] one = new int[1];

    private int[] absKeys = new int[32];
    private int[] absVals = new int[32];

    public TermOps(Terms terms, IntLists lists) {
        this(terms, lists, null);
    }

    /**
     * @param levels the worker's level adapter, for {@link #instantiateLevelParams}; null = the one the
     *     overlay was created with
     */
    public TermOps(Terms terms, IntLists lists, LevelInfo levels) {
        this.terms = terms;
        this.ov = terms.overlay();
        this.lists = lists;
        Store store = ov != null ? ov.store() : null;
        this.decls = store != null ? store.decls() : null;
        this.names = store != null ? store.names() : null;
        this.specials = store != null ? store.specials() : null;
        this.lits = store != null ? store.lits() : null;
        this.levels = levels != null ? levels : (ov != null ? ov.ctx.levels() : null);
    }

    /** The pool that resolves the literal indices this worker uses (its worker pool); for {@link #eqv}. */
    public void setLitPool(LitPool lits) {
        this.lits = lits;
    }

    public void setLevels(LevelInfo levels) {
        this.levels = levels;
    }

    // ---- equality ----------------------------------------------------------------------------------

    /**
     * Structural equality, the official {@code ==} on expressions ({@code expr_eq_fn<false>}): binder
     * names and binder info do not exist here; levels are compared by id (syntactically); constants
     * and projections by name. Literals are compared by content. Id equality is the fast path;
     * otherwise a DAG walk with a pair memo.
     */
    public boolean eqv(int a, int b) {
        if (a == b) {
            return true;
        }
        Terms tm = terms;
        Memo memo = pairs;
        memo.begin();
        int[] st = pairStack;
        int sp = 0;
        st[sp++] = a;
        st[sp++] = b;
        while (sp > 0) {
            b = st[--sp];
            a = st[--sp];
            if (a == b) {
                continue;
            }
            int ha = tm.header(a);
            int hb = tm.header(b);
            // kind and loose range are functions of the structure; so is the mask
            if (((ha ^ hb) & 0xFFFF000F) != 0 || tm.fvMask(a) != tm.fvMask(b)) {
                return false;
            }
            int k = ha & 0xF;
            switch (k) {
                case Kind.BVAR:
                case Kind.SORT:
                    if (tm.a(a) != tm.a(b)) {
                        return false;
                    }
                    continue;
                case Kind.CONST:
                    if (tm.c(a) != tm.c(b) || tm.b(a) != tm.b(b)) {
                        return false;
                    }
                    continue;
                case Kind.NATLIT:
                    if (((ha ^ hb) & Node.F_NAT_BIG) != 0) {
                        return false;
                    }
                    if ((ha & Node.F_NAT_BIG) == 0) {
                        if (tm.a(a) != tm.a(b) || tm.b(a) != tm.b(b)) {
                            return false;
                        }
                    } else if (tm.a(a) != tm.a(b) && !natEquals(tm.a(a), tm.a(b))) {
                        return false;
                    }
                    continue;
                case Kind.STRLIT:
                    if (tm.a(a) != tm.a(b) && !strEquals(tm.a(a), tm.a(b))) {
                        return false;
                    }
                    continue;
                case Kind.FVAR:
                    return false;
                case Kind.PROJ:
                    if (tm.b(a) != tm.b(b) || projName(tm.c(a)) != projName(tm.c(b))) {
                        return false;
                    }
                    break;
                case Kind.APP:
                case Kind.LAM:
                case Kind.PI:
                case Kind.LET:
                    break;
                default:
                    return false;
            }
            long key = ((long) a << 32) | b;
            if (memo.get(key) != 0) {
                continue;
            }
            memo.put(key, 1);
            if (sp + 6 > st.length) {
                st = pairStack = Arrays.copyOf(st, st.length * 2);
            }
            st[sp++] = tm.a(a);
            st[sp++] = tm.a(b);
            if (k != Kind.PROJ) {
                st[sp++] = tm.b(a);
                st[sp++] = tm.b(b);
                if (k == Kind.LET) {
                    st[sp++] = tm.c(a);
                    st[sp++] = tm.c(b);
                }
            }
        }
        return true;
    }

    private int projName(int cidOrNotName) {
        return cidOrNotName > 0 ? decls.name(cidOrNotName) : ~cidOrNotName;
    }

    private boolean natEquals(int ia, int ib) {
        LitPool p = lits;
        if (p == null || ((ia < 0 || ib < 0) && !p.isWorkerPool())) {
            return false;
        }
        return p.natEquals(ia, ib);
    }

    private boolean strEquals(int ia, int ib) {
        LitPool p = lits;
        if (p == null || ((ia < 0 || ib < 0) && !p.isWorkerPool())) {
            return false;
        }
        return p.stringEquals(ia, ib);
    }

    // ---- instantiation -----------------------------------------------------------------------------

    /** {@code body[#0 := arg]}, lowering the other loose indices. */
    public int instantiate1(int body, int arg) {
        if (terms.looseRangeField(body) == 0) {
            return body;
        }
        one[0] = arg;
        return main.run(M_INST, body, one, 0, 1, 0, 0);
    }

    /**
     * The official {@code instantiate}: simultaneous instantiation of the {@code n} outermost loose
     * variables, {@code #i := args[from + i]}; the remaining loose indices are lowered by {@code n}.
     */
    public int instantiate(int body, int[] args, int from, int n) {
        if (n == 0 || terms.looseRangeField(body) == 0) {
            return body;
        }
        return main.run(M_INST, body, args, from, n, 0, 0);
    }

    /**
     * The official {@code instantiate_rev}: simultaneous instantiation of the {@code n} outermost
     * loose variables: #0 := args[from+n-1], ..., #(n-1) := args[from].
     */
    public int instantiateRev(int body, int[] args, int from, int n) {
        if (n == 0 || terms.looseRangeField(body) == 0) {
            return body;
        }
        return main.run(M_INST_REV, body, args, from, n, 0, 0);
    }

    /**
     * Replaces the free variables {@code fvars[from..from+n)} by loose indices (inverse of
     * {@link #instantiateRev}): {@code fvars[from + i]} becomes {@code #(n - 1 - i)} at depth 0. If a
     * variable is listed twice, the last position wins, as in the official {@code abstract}.
     */
    public int abstractFVars(int term, int[] fvars, int from, int n) {
        if (n == 0 || !terms.hasFVar(term)) {
            return term;
        }
        boolean mapped = n > 8;
        if (mapped) {
            int cap = Integer.highestOneBit(n) << 2;
            if (absKeys.length < cap) {
                absKeys = new int[cap];
                absVals = new int[cap];
            }
            int mask = cap - 1;
            for (int i = 0; i < n; i++) {
                int f = fvars[from + i];
                int s = (f * 0x9E3779B1) >>> 8 & mask;
                while (absKeys[s] != 0 && absKeys[s] != f) {
                    s = (s + 1) & mask;
                }
                absKeys[s] = f;
                absVals[s] = i;
            }
            main.absMask = mask;
        } else {
            main.absMask = 0;
        }
        try {
            return main.run(M_ABSTRACT, term, fvars, from, n, 0, 0);
        } finally {
            if (mapped) {
                Arrays.fill(absKeys, 0, main.absMask + 1, 0);
            }
        }
    }

    /** The official {@code lift_loose_bvars}: loose indices {@code >= cutoff} are raised by {@code by}. */
    public int liftLoose(int term, int cutoff, int by) {
        int r = terms.looseRangeField(term);
        if (by == 0 || (r != Node.LOOSE_SAT && r <= cutoff)) {
            return term;
        }
        return main.run(M_LIFT, term, null, 0, 0, cutoff, by);
    }

    /**
     * The official {@code lower_loose_bvars}: loose indices {@code >= cutoff} are lowered by {@code by}.
     * Requires {@code cutoff >= by} and that no loose index lies in {@code [cutoff - by, cutoff)}.
     */
    public int lowerLoose(int term, int cutoff, int by) {
        if (by > cutoff) {
            throw new IllegalArgumentException("lowerLoose needs cutoff >= by");
        }
        int r = terms.looseRangeField(term);
        if (by == 0 || (r != Node.LOOSE_SAT && r <= cutoff)) {
            return term;
        }
        return main.run(M_LOWER, term, null, 0, 0, cutoff, by);
    }

    /**
     * Instantiates universe parameters: {@code paramNames} and {@code levels} are list ids of equal
     * length. Levels are rebuilt with the simplifying constructors (ARCHITECTURE.md 4.6, trap T3) by
     * the {@link LevelInfo} adapter. A term without universe parameters is returned as is.
     */
    public int instantiateLevelParams(int term, int paramNames, int levelList) {
        if (paramNames == 0 || !terms.hasLevelParam(term)) {
            return term;
        }
        if (levels == null) {
            throw new IllegalStateException("TermOps has no LevelInfo (see Store.bindLevels / the 3-argument constructor)");
        }
        return main.run(M_LEVELS, term, null, 0, 0, paramNames, levelList);
    }

    /**
     * Binds the references that were written before their constant existed: returns {@code term}
     * itself when no node below it is flagged {@link Node#F_UNRESOLVED}; otherwise a copy (in the
     * overlay, hash-consed) in which every CONST and PROJ node that is unbound ({@code ~name}) and
     * whose name has a constant NOW points to that constant, with rank, arity flag and summaries
     * derived afresh. A name that still has no constant, or was dropped, stays unbound, and a
     * reference with the wrong number of levels stays flagged: the caller looks at the result's
     * {@link Node#F_UNRESOLVED} again. This is how a declaration can use a constant that admission
     * generated after the loader wrote the declaration's terms ({@link GeneratedBuilder}).
     */
    public int rebind(int term) {
        if ((terms.header(term) & Node.F_UNRESOLVED) == 0) {
            return term;
        }
        return main.run(M_REBIND, term, null, 0, 0, 0, 0);
    }

    // ---- loose variables ---------------------------------------------------------------------------

    /** True if the term has a loose bound variable. Exact. */
    public boolean hasLooseBVars(int term) {
        return !terms.isClosed(term);
    }

    /** The official {@code has_loose_bvar(e, i)}: the loose index {@code index} occurs in {@code term}. Exact. */
    public boolean hasLooseBVar(int term, int index) {
        Terms tm = terms;
        int r = tm.looseRangeField(term);
        if (r != Node.LOOSE_SAT) {
            if (index >= r) {
                return false;
            }
            if (index == r - 1) {
                return true;
            }
        }
        if (!maskMayHave(tm.fvMask(term), index)) {
            return false;
        }
        // Exact walk: (term, index) pairs, pruned by range and mask, each visited once.
        Memo memo = pairs;
        memo.begin();
        int[] st = pairStack;
        int sp = 0;
        st[sp++] = term;
        st[sp++] = index;
        while (sp > 0) {
            int idx = st[--sp];
            int t = st[--sp];
            int h = tm.header(t);
            int range = h >>> 16;
            if (range != Node.LOOSE_SAT && idx >= range) {
                continue;
            }
            if (!maskMayHave(tm.fvMask(t), idx)) {
                continue;
            }
            int k = h & 0xF;
            if (k == Kind.BVAR) {
                if (tm.a(t) == idx) {
                    return true;
                }
                continue;
            }
            if (range != Node.LOOSE_SAT && idx == range - 1) {
                return true;
            }
            if (k != Kind.APP && k != Kind.LAM && k != Kind.PI && k != Kind.LET && k != Kind.PROJ) {
                continue;
            }
            long key = ((long) idx << 32) | t;
            if (memo.get(key) != 0) {
                continue;
            }
            memo.put(key, 1);
            if (sp + 6 > st.length) {
                st = pairStack = Arrays.copyOf(st, st.length * 2);
            }
            st[sp++] = tm.a(t);
            st[sp++] = idx;
            if (k == Kind.APP) {
                st[sp++] = tm.b(t);
                st[sp++] = idx;
            } else if (k == Kind.LAM || k == Kind.PI) {
                if (idx < Integer.MAX_VALUE) {
                    st[sp++] = tm.b(t);
                    st[sp++] = idx + 1;
                }
            } else if (k == Kind.LET) {
                st[sp++] = tm.b(t);
                st[sp++] = idx;
                if (idx < Integer.MAX_VALUE) {
                    st[sp++] = tm.c(t);
                    st[sp++] = idx + 1;
                }
            }
        }
        return false;
    }

    private static boolean maskMayHave(long mask, int index) {
        return index >= 63 ? mask < 0 : ((mask >>> index) & 1L) != 0;
    }

    // ---- beta --------------------------------------------------------------------------------------

    /**
     * Applies {@code fn} to {@code args[from .. from+n)} and contracts the head lambdas: if {@code fn}
     * is {@code fun x_1 .. x_m => b} with {@code m >= 1}, the first {@code min(m, n)} arguments are
     * substituted into the body (official {@code apply_beta}); the remaining ones are applied.
     */
    public int beta(int fn, int[] args, int from, int n) {
        Terms tm = terms;
        int f = fn;
        int m = 0;
        while (m < n && tm.kind(f) == Kind.LAM) {
            f = tm.b(f);
            m++;
        }
        if (m == 0) {
            return ov.mkApps(fn, args, from, n);
        }
        int r = instantiateRev(f, args, from, m);
        return ov.mkApps(r, args, from + m, n - m);
    }

    /** The official {@code head_beta_reduce}: while the term is an application of a lambda, contract it. */
    public int headBeta(int term) {
        Terms tm = terms;
        int[] buf = null;
        for (;;) {
            if (tm.kind(term) != Kind.APP || tm.kind(tm.head(term)) != Kind.LAM) {
                return term;
            }
            int n = tm.spineLength(term);
            if (buf == null || buf.length < n) {
                buf = new int[Math.max(n, 8)];
            }
            int head = tm.spine(term, buf, 0);
            term = beta(head, buf, 0, n);
        }
    }

    /**
     * The official {@code cheap_beta_reduce}: contracts {@code (fun xs => b) as} only when the body is
     * closed or a bound variable, i.e. when no substitution has to be performed.
     */
    public int cheapBetaReduce(int term) {
        Terms tm = terms;
        if (tm.kind(term) != Kind.APP) {
            return term;
        }
        int fn = tm.head(term);
        if (tm.kind(fn) != Kind.LAM) {
            return term;
        }
        int n = tm.spineLength(term);
        int[] args = new int[n];
        tm.spine(term, args, 0);
        int i = 0;
        while (i < n && tm.kind(fn) == Kind.LAM) {
            i++;
            fn = tm.b(fn);
        }
        if (tm.isClosed(fn)) {
            return ov.mkApps(fn, args, i, n - i);
        }
        if (tm.kind(fn) == Kind.BVAR) {
            int idx = tm.a(fn);
            if (idx < i) {
                return ov.mkApps(args[i - idx - 1], args, i, n - i);
            }
        }
        return term;
    }

    // ---- binders over free variables ---------------------------------------------------------------

    /**
     * {@code Pi (x_1 : A_1) .. (x_n : A_n), body} over free variables, using their stored types. A
     * free variable made by {@link Overlay#mkFVarLet} becomes a LET (official {@code mk_binding}).
     */
    public int mkPiOver(int[] fvars, int from, int n, int body) {
        return mkBinding(false, fvars, from, n, body);
    }

    public int mkLambdaOver(int[] fvars, int from, int n, int body) {
        return mkBinding(true, fvars, from, n, body);
    }

    private int mkBinding(boolean lambda, int[] fvars, int from, int n, int body) {
        Terms tm = terms;
        int r = abstractFVars(body, fvars, from, n);
        for (int i = n - 1; i >= 0; i--) {
            int fv = fvars[from + i];
            if (tm.kind(fv) != Kind.FVAR) {
                throw new IllegalArgumentException("not a free variable: " + Integer.toHexString(fv));
            }
            int type = abstractFVars(tm.b(fv), fvars, from, i);
            int value = tm.c(fv);
            if (value != 0) {
                r = ov.mkLet(type, abstractFVars(value, fvars, from, i), r);
            } else if (lambda) {
                r = ov.mkLam(type, r);
            } else {
                r = ov.mkPi(type, r);
            }
        }
        return r;
    }

    // ---- queries -----------------------------------------------------------------------------------

    /** True if a CONST with constant id in {@code cids[0..n)} occurs in the term (levels ignored). */
    public boolean mentionsAny(int term, int[] cids, int n) {
        if (n == 0) {
            return false;
        }
        Terms tm = terms;
        Memo memo = pairs;
        memo.begin();
        int[] st = pairStack;
        int sp = 0;
        st[sp++] = term;
        while (sp > 0) {
            int t = st[--sp];
            int h = tm.header(t);
            if ((h & Node.F_HAS_CONST) == 0) {
                continue;
            }
            int k = h & 0xF;
            if (k == Kind.CONST) {
                int cid = tm.a(t);
                for (int i = 0; i < n; i++) {
                    if (cids[i] == cid) {
                        return true;
                    }
                }
                continue;
            }
            if (k != Kind.APP && k != Kind.LAM && k != Kind.PI && k != Kind.LET && k != Kind.PROJ) {
                continue;
            }
            if (memo.get(t) != 0) {
                continue;
            }
            memo.put(t, 1);
            if (sp + 3 > st.length) {
                st = pairStack = Arrays.copyOf(st, st.length * 2);
            }
            st[sp++] = tm.a(t);
            if (k != Kind.PROJ) {
                st[sp++] = tm.b(t);
                if (k == Kind.LET) {
                    st[sp++] = tm.c(t);
                }
            }
        }
        return false;
    }

    /** Head of an application spine (the term itself if it is not an APP). */
    public int head(int term) {
        return terms.head(term);
    }

    /**
     * Strips the outermost {@code optParam}/{@code autoParam}/{@code outParam}/{@code semiOutParam}
     * annotations, repeatedly (official {@code consumeTypeAnnotations}; trap T2): {@code optParam A d}
     * and {@code autoParam A t} (head constant with exactly two arguments) become {@code A};
     * {@code outParam A} and {@code semiOutParam A} (exactly one argument) become {@code A}. The head
     * is matched by constant name only.
     */
    public int consumeTypeAnnotations(int type) {
        Terms tm = terms;
        Specials sp = specials;
        int opt = sp.annotationName(Specials.ANN_OPT_PARAM);
        int auto = sp.annotationName(Specials.ANN_AUTO_PARAM);
        int out = sp.annotationName(Specials.ANN_OUT_PARAM);
        int semi = sp.annotationName(Specials.ANN_SEMI_OUT_PARAM);
        for (;;) {
            if (tm.kind(type) != Kind.APP) {
                return type;
            }
            int head = tm.c(type);
            if (tm.kind(head) != Kind.CONST) {
                return type;
            }
            int name = tm.c(head);
            int fn = tm.a(type);
            if (fn == head) {
                if (name != out && name != semi) {
                    return type;
                }
                type = tm.b(type);
            } else if (tm.a(fn) == head) {
                if (name != opt && name != auto) {
                    return type;
                }
                type = tm.b(fn);
            } else {
                return type;
            }
        }
    }

    // ---- the rewriting engine ----------------------------------------------------------------------

    /**
     * Post-order rewriting with an explicit stack. A frame is two ints: the term (complemented for a
     * "rebuild" frame) and the binder depth. Results of non-leaf nodes are memoised per call on
     * (term, depth); a node whose children all come back unchanged is returned as is.
     */
    private final class Rewriter {
        private int[] st = new int[256];
        private int[] vs = new int[64];
        private final Memo memo = new Memo();
        private int mode;
        private int[] subst;
        private int from;
        private int n;
        private int p1;
        private int p2;
        int absMask;

        int run(int mode, int root, int[] subst, int from, int n, int p1, int p2) {
            this.mode = mode;
            this.subst = subst;
            this.from = from;
            this.n = n;
            this.p1 = p1;
            this.p2 = p2;
            Terms tm = terms;
            Overlay o = ov;
            Memo memo = this.memo;
            memo.begin();
            int[] st = this.st;
            int[] vs = this.vs;
            int sp = 0;
            int vp = 0;
            st[sp++] = root;
            st[sp++] = 0;
            while (sp > 0) {
                int off = st[--sp];
                int t = st[--sp];
                int r;
                if (t >= 0) {
                    r = leaf(t, off);
                    if (r == 0) {
                        r = memo.get(((long) off << 32) | t);
                    }
                    if (r == 0) {
                        if (sp + 8 > st.length) {
                            st = this.st = Arrays.copyOf(st, st.length * 2);
                        }
                        st[sp++] = ~t;
                        st[sp++] = off;
                        switch (tm.kind(t)) {
                            case Kind.APP:
                                st[sp++] = tm.b(t);
                                st[sp++] = off;
                                st[sp++] = tm.a(t);
                                st[sp++] = off;
                                break;
                            case Kind.LAM:
                            case Kind.PI:
                                st[sp++] = tm.b(t);
                                st[sp++] = off + 1;
                                st[sp++] = tm.a(t);
                                st[sp++] = off;
                                break;
                            case Kind.LET:
                                st[sp++] = tm.c(t);
                                st[sp++] = off + 1;
                                st[sp++] = tm.b(t);
                                st[sp++] = off;
                                st[sp++] = tm.a(t);
                                st[sp++] = off;
                                break;
                            case Kind.PROJ:
                                st[sp++] = tm.a(t);
                                st[sp++] = off;
                                break;
                            default:
                                throw new IllegalStateException("unexpected node kind " + tm.kind(t));
                        }
                        continue;
                    }
                } else {
                    t = ~t;
                    switch (tm.kind(t)) {
                        case Kind.APP: {
                            int nb = vs[--vp];
                            int na = vs[--vp];
                            r = na == tm.a(t) && nb == tm.b(t) ? t : o.mkApp(na, nb);
                            break;
                        }
                        case Kind.LAM: {
                            int nb = vs[--vp];
                            int na = vs[--vp];
                            r = na == tm.a(t) && nb == tm.b(t) ? t : o.mkLam(na, nb);
                            break;
                        }
                        case Kind.PI: {
                            int nb = vs[--vp];
                            int na = vs[--vp];
                            r = na == tm.a(t) && nb == tm.b(t) ? t : o.mkPi(na, nb);
                            break;
                        }
                        case Kind.LET: {
                            int nc = vs[--vp];
                            int nb = vs[--vp];
                            int na = vs[--vp];
                            r = na == tm.a(t) && nb == tm.b(t) && nc == tm.c(t) ? t : o.mkLet(na, nb, nc);
                            break;
                        }
                        default: {
                            int na = vs[--vp];
                            int sc = tm.c(t);
                            if (mode == M_REBIND && sc < 0) {
                                int cid = names.constOf(~sc);
                                if (cid > 0) {
                                    sc = cid;
                                }
                            }
                            r = na == tm.a(t) && sc == tm.c(t) ? t : o.mkProj(sc, tm.b(t), na);
                            break;
                        }
                    }
                    memo.put(((long) off << 32) | t, r);
                }
                if (vp == vs.length) {
                    vs = this.vs = Arrays.copyOf(vs, vp * 2);
                }
                vs[vp++] = r;
            }
            return vs[0];
        }

        /** The result for {@code t} at binder depth {@code off} if no descent is needed, else 0. */
        private int leaf(int t, int off) {
            Terms tm = terms;
            int h = tm.header(t);
            int k = h & 0xF;
            int range = h >>> 16;
            switch (mode) {
                case M_INST:
                case M_INST_REV: {
                    if (range != Node.LOOSE_SAT && range <= off) {
                        return t;
                    }
                    if (k == Kind.BVAR) {
                        int idx = tm.a(t);
                        if (idx < off) {
                            return t;
                        }
                        int rel = idx - off;
                        if (rel < n) {
                            int s = subst[mode == M_INST ? from + rel : from + n - 1 - rel];
                            if (off == 0 || tm.looseRangeField(s) == 0) {
                                return s;
                            }
                            long key = ((long) off << 32) | t;
                            int r = memo.get(key);
                            if (r == 0) {
                                r = aux.run(M_LIFT, s, null, 0, 0, 0, off);
                                memo.put(key, r);
                            }
                            return r;
                        }
                        return ov.mkBVar(idx - n);
                    }
                    break;
                }
                case M_ABSTRACT: {
                    if ((h & Node.F_HAS_FVAR) == 0) {
                        return t;
                    }
                    if (k == Kind.FVAR) {
                        int i = fvarIndex(t);
                        if (i < 0) {
                            return t;
                        }
                        return ov.mkBVar(Math.addExact(off, n - 1 - i));
                    }
                    break;
                }
                case M_LIFT: {
                    int cut = p1 + off;
                    if (range != Node.LOOSE_SAT && range <= cut) {
                        return t;
                    }
                    if (k == Kind.BVAR) {
                        int idx = tm.a(t);
                        return idx < cut ? t : ov.mkBVar(Math.addExact(idx, p2));
                    }
                    break;
                }
                case M_LOWER: {
                    int cut = p1 + off;
                    if (range != Node.LOOSE_SAT && range <= cut) {
                        return t;
                    }
                    if (k == Kind.BVAR) {
                        int idx = tm.a(t);
                        return idx < cut ? t : ov.mkBVar(idx - p2);
                    }
                    break;
                }
                case M_REBIND: {
                    if ((h & Node.F_UNRESOLVED) == 0) {
                        return t;
                    }
                    if (k == Kind.CONST) {
                        int a = tm.a(t);
                        if (a < 0) {
                            int cid = names.constOf(~a);
                            if (cid > 0) {
                                return ov.mkConstRaw(cid, tm.b(t), tm.c(t));
                            }
                        }
                        return t;
                    }
                    break;
                }
                default: {
                    if ((h & Node.F_HAS_LPARAM) == 0) {
                        return t;
                    }
                    if (k == Kind.SORT) {
                        int l = tm.a(t);
                        int nl = levels.instantiate(l, p1, p2);
                        return nl == l ? t : ov.mkSort(nl);
                    }
                    if (k == Kind.CONST) {
                        int ls = tm.b(t);
                        int nls = levels.instantiateList(ls, p1, p2);
                        return nls == ls ? t : ov.mkConstRaw(tm.a(t), nls, tm.c(t));
                    }
                    break;
                }
            }
            return k == Kind.APP || k == Kind.LAM || k == Kind.PI || k == Kind.LET || k == Kind.PROJ ? 0 : t;
        }

        /** Position of free variable {@code t} in the list being abstracted (last occurrence), or -1. */
        private int fvarIndex(int t) {
            if (absMask == 0) {
                for (int i = n - 1; i >= 0; i--) {
                    if (subst[from + i] == t) {
                        return i;
                    }
                }
                return -1;
            }
            int mask = absMask;
            int s = (t * 0x9E3779B1) >>> 8 & mask;
            for (;;) {
                int key = absKeys[s];
                if (key == t) {
                    return absVals[s];
                }
                if (key == 0) {
                    return -1;
                }
                s = (s + 1) & mask;
            }
        }
    }

    /**
     * A map from long keys to non-zero ints that is emptied in O(1): entries carry the stamp of the
     * call that made them. Each call starts on the first 64 slots and widens (re-inserting its own
     * entries) as it fills, so a small call touches a few cache lines however large an earlier call
     * was.
     */
    private static final class Memo {
        private long[] keys = new long[64];
        private int[] vals = new int[64];
        private int[] stamps = new int[64];
        private int stamp;
        private int size;
        private int cap = 64;

        void begin() {
            if (keys.length > (1 << 20)) {
                // one huge call must not pin its tables for the rest of the session
                keys = new long[64];
                vals = new int[64];
                stamps = new int[64];
                stamp = 0;
            }
            if (++stamp == 0) {
                Arrays.fill(stamps, 0);
                stamp = 1;
            }
            size = 0;
            cap = 64;
        }

        private static int slot(long key, int mask) {
            long h = key * 0x9E3779B97F4A7C15L;
            return (int) (h >>> 32) & mask;
        }

        int get(long key) {
            int mask = cap - 1;
            int i = slot(key, mask);
            for (;;) {
                if (stamps[i] != stamp) {
                    return 0;
                }
                if (keys[i] == key) {
                    return vals[i];
                }
                i = (i + 1) & mask;
            }
        }

        void put(long key, int val) {
            if ((size + 1) * 2 > cap) {
                widen();
            }
            int mask = cap - 1;
            int i = slot(key, mask);
            while (stamps[i] == stamp) {
                if (keys[i] == key) {
                    vals[i] = val;
                    return;
                }
                i = (i + 1) & mask;
            }
            stamps[i] = stamp;
            keys[i] = key;
            vals[i] = val;
            size++;
        }

        private void widen() {
            long[] k = new long[size];
            int[] v = new int[size];
            int m = 0;
            for (int i = 0; i < cap; i++) {
                if (stamps[i] == stamp) {
                    k[m] = keys[i];
                    v[m] = vals[i];
                    m++;
                }
            }
            int newCap = cap * 2;
            if (newCap > keys.length) {
                keys = new long[newCap];
                vals = new int[newCap];
                stamps = new int[newCap];
                stamp = 1;
            } else if (++stamp == 0) {
                Arrays.fill(stamps, 0);
                stamp = 1;
            }
            cap = newCap;
            int mask = newCap - 1;
            for (int j = 0; j < m; j++) {
                int i = slot(k[j], mask);
                while (stamps[i] == stamp) {
                    i = (i + 1) & mask;
                }
                stamps[i] = stamp;
                keys[i] = k[j];
                vals[i] = v[j];
            }
        }
    }
}
