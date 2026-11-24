package rlvm.inductive;

import rlvm.api.KernelException;
import rlvm.api.KernelOps;
import rlvm.api.LevelOps;
import rlvm.api.Reason;
import rlvm.store.DeclKind;
import rlvm.store.DeclTable;
import rlvm.store.Kind;
import rlvm.store.Overlay;
import rlvm.store.Store;
import rlvm.store.TermOps;
import rlvm.store.Terms;

/**
 * A small, recursive, substitution-based type checker for the unit tests of this module: enough of
 * the official judgments to admit the fixtures (sorts, constants, applications, binders, let; beta,
 * delta and zeta in {@code whnf}; structural conversion with binder opening). No caches, no
 * projections, literals, iota, eta or proof irrelevance. It follows the official control flow where
 * admission can observe it: {@code whnf} is the substitution-based head reduction, so its output is
 * the official term.
 *
 * <p>Visibility is by rank, as in the reference kernel: a constant is known iff its rank is below
 * the scope's. The calls are counted so that tests can assert what admission asked for.
 */
final class FakeKernel implements KernelOps {

    private final Store store;
    private final Terms terms;
    private final Overlay ov;
    private final TermOps tops;
    private final LevelOps levels;
    private final DeclTable decls;

    int rank = Integer.MAX_VALUE;
    int levelParams;
    int beginCalls;
    int checkCalls;
    int exposeCalls;
    long steps;
    boolean syntactic = true;

    FakeKernel(Store store, Terms terms, TermOps tops, LevelOps levels) {
        this.store = store;
        this.terms = terms;
        this.ov = terms.overlay();
        this.tops = tops;
        this.levels = levels;
        this.decls = store.decls();
    }

    @Override
    public void begin(int rank, int levelParams) {
        this.rank = rank;
        this.levelParams = levelParams;
        beginCalls++;
    }

    @Override
    public void exposeUnitMembers(int kindMask) {
        exposeCalls++;
    }

    @Override
    public int infer(int term) {
        return inferCore(term, false);
    }

    @Override
    public int check(int term) {
        checkCalls++;
        if (terms.looseRange(term) != 0) {
            throw new KernelException(Reason.LOOSE_BVAR);
        }
        return inferCore(term, true);
    }

    private int inferCore(int e, boolean chk) {
        steps++;
        switch (terms.kind(e)) {
            case Kind.BVAR:
                throw new KernelException(Reason.LOOSE_BVAR);
            case Kind.SORT:
                if (chk && !levels.paramsDeclared(terms.a(e), levelParams)) {
                    throw new KernelException(Reason.UNDECLARED_LEVEL_PARAM);
                }
                return ov.mkSort(levels.succ(terms.a(e)));
            case Kind.CONST: {
                int cid = terms.a(e);
                if (cid <= 0 || decls.rank(cid) >= rank) {
                    throw new KernelException(Reason.UNKNOWN_CONSTANT);
                }
                int ls = terms.b(e);
                int n = levels.lists().length(ls);
                if (n != decls.numLevelParams(cid)) {
                    throw new KernelException(Reason.LEVEL_ARITY_MISMATCH);
                }
                if (chk) {
                    for (int i = 0; i < n; i++) {
                        if (!levels.paramsDeclared(levels.lists().get(ls, i), levelParams)) {
                            throw new KernelException(Reason.UNDECLARED_LEVEL_PARAM);
                        }
                    }
                }
                return tops.instantiateLevelParams(decls.type(cid), decls.levelParams(cid), ls);
            }
            case Kind.FVAR:
                return terms.b(e);
            case Kind.APP: {
                int n = terms.spineLength(e);
                int[] args = new int[n];
                int head = terms.spine(e, args, 0);
                int ft = inferCore(head, chk);
                for (int i = 0; i < n; i++) {
                    ft = ensurePi(ft);
                    if (chk) {
                        int at = inferCore(args[i], true);
                        if (!isDefEq(at, terms.a(ft))) {
                            throw new KernelException(Reason.APP_TYPE_MISMATCH, at, terms.a(ft));
                        }
                    }
                    ft = tops.instantiate1(terms.b(ft), args[i]);
                }
                return ft;
            }
            case Kind.LAM: {
                int dom = terms.a(e);
                if (chk) {
                    ensureSort(inferCore(dom, true));
                }
                int[] x = {ov.mkFVar(dom)};
                int bt = inferCore(tops.instantiate1(terms.b(e), x[0]), chk);
                return tops.mkPiOver(x, 0, 1, bt);
            }
            case Kind.PI: {
                int dom = terms.a(e);
                int l1 = ensureSort(inferCore(dom, chk));
                int x = ov.mkFVar(dom);
                int l2 = ensureSort(inferCore(tops.instantiate1(terms.b(e), x), chk));
                return ov.mkSort(levels.mkIMax(l1, l2));
            }
            case Kind.LET: {
                if (chk) {
                    ensureSort(inferCore(terms.a(e), true));
                    int vt = inferCore(terms.b(e), true);
                    if (!isDefEq(vt, terms.a(e))) {
                        throw new KernelException(Reason.LET_TYPE_MISMATCH);
                    }
                }
                return inferCore(tops.instantiate1(terms.c(e), terms.b(e)), chk);
            }
            default:
                throw new UnsupportedOperationException("FakeKernel: node kind " + terms.kind(e));
        }
    }

    @Override
    public int whnf(int term) {
        int e = term;
        while (true) {
            steps++;
            int kind = terms.kind(e);
            if (kind == Kind.LET) {
                e = tops.instantiate1(terms.c(e), terms.b(e));
                continue;
            }
            if (kind == Kind.CONST) {
                int v = unfold(e);
                if (v == 0) {
                    return e;
                }
                e = v;
                continue;
            }
            if (kind != Kind.APP) {
                return e;
            }
            int n = terms.spineLength(e);
            int[] args = new int[n];
            int head = terms.spine(e, args, 0);
            int hk = terms.kind(head);
            if (hk == Kind.LAM) {
                int f = head;
                int m = 0;
                while (m < n && terms.kind(f) == Kind.LAM) {
                    f = terms.b(f);
                    m++;
                }
                e = ov.mkApps(tops.instantiateRev(f, args, 0, m), args, m, n - m);
                continue;
            }
            if (hk == Kind.LET) {
                e = ov.mkApps(tops.instantiate1(terms.c(head), terms.b(head)), args, 0, n);
                continue;
            }
            if (hk == Kind.CONST) {
                int v = unfold(head);
                if (v != 0) {
                    e = ov.mkApps(v, args, 0, n);
                    continue;
                }
            }
            return e;
        }
    }

    /** Delta: the instantiated value of a visible definition, or 0. */
    private int unfold(int c) {
        int cid = terms.a(c);
        if (cid <= 0 || decls.rank(cid) >= rank || decls.kind(cid) != DeclKind.DEFN) {
            return 0;
        }
        return tops.instantiateLevelParams(decls.value(cid), decls.levelParams(cid), terms.b(c));
    }

    @Override
    public int whnfCore(int term) {
        return whnf(term);
    }

    @Override
    public boolean isDefEq(int a, int b) {
        steps++;
        if (a == b || tops.eqv(a, b)) {
            return true;
        }
        a = whnf(a);
        b = whnf(b);
        if (a == b || tops.eqv(a, b)) {
            return true;
        }
        int ka = terms.kind(a);
        if (ka != terms.kind(b)) {
            return false;
        }
        switch (ka) {
            case Kind.SORT:
                return levels.isEquiv(terms.a(a), terms.a(b));
            case Kind.CONST:
                return terms.a(a) == terms.a(b) && levels.listsEquiv(terms.b(a), terms.b(b));
            case Kind.APP: {
                int n = terms.spineLength(a);
                if (n != terms.spineLength(b)) {
                    return false;
                }
                int[] xa = new int[n];
                int[] xb = new int[n];
                if (!isDefEq(terms.spine(a, xa, 0), terms.spine(b, xb, 0))) {
                    return false;
                }
                for (int i = 0; i < n; i++) {
                    if (!isDefEq(xa[i], xb[i])) {
                        return false;
                    }
                }
                return true;
            }
            case Kind.LAM:
            case Kind.PI: {
                if (!isDefEq(terms.a(a), terms.a(b))) {
                    return false;
                }
                int x = ov.mkFVar(terms.a(a));
                return isDefEq(tops.instantiate1(terms.b(a), x), tops.instantiate1(terms.b(b), x));
            }
            default:
                return false;
        }
    }

    @Override
    public int ensureSort(int term) {
        if (terms.kind(term) == Kind.SORT) {
            return terms.a(term);
        }
        int w = whnf(term);
        if (terms.kind(w) == Kind.SORT) {
            return terms.a(w);
        }
        throw new KernelException(Reason.TYPE_EXPECTED, term, 0);
    }

    @Override
    public int ensurePi(int term) {
        if (terms.kind(term) == Kind.PI) {
            return term;
        }
        int w = whnf(term);
        if (terms.kind(w) == Kind.PI) {
            return w;
        }
        throw new KernelException(Reason.FUNCTION_EXPECTED, term, 0);
    }

    @Override
    public boolean isProp(int type) {
        return levels.isAlwaysZero(ensureSort(infer(type)));
    }

    @Override
    public int mkLocal(int type) {
        return ov.mkFVar(type);
    }

    @Override
    public Terms terms() {
        return terms;
    }

    @Override
    public TermOps termOps() {
        return tops;
    }

    @Override
    public LevelOps levels() {
        return levels;
    }

    @Override
    public boolean whnfIsSyntactic() {
        return syntactic;
    }

    @Override
    public long steps() {
        return steps;
    }
}
