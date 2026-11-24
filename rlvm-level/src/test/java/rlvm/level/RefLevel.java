package rlvm.level;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Slow reference implementation for the tests: levels as immutable trees, the official algorithms
 * transcribed line by line from {@code refs/lean4/src/kernel/level.cpp} with java.util collections,
 * and the semantics ({@link #eval}). Deliberately shares no code with {@link LevelStore}.
 *
 * <p>Where the official code tests pointer equality ({@code is_eqp} in {@code update_succ} /
 * {@code update_max}) this uses structural equality, which is what pointer equality means in a
 * hash-consed store.
 */
final class RefLevel {
    static final int ZERO = 0;
    static final int SUCC = 1;
    static final int MAX = 2;
    static final int IMAX = 3;
    static final int PARAM = 4;

    final int kind;
    final RefLevel a;
    final RefLevel b;
    final String name;
    final int depth;
    final boolean hasParam;
    private final int hash;

    private RefLevel(int kind, RefLevel a, RefLevel b, String name) {
        this.kind = kind;
        this.a = a;
        this.b = b;
        this.name = name;
        switch (kind) {
            case SUCC:
                depth = a.depth + 1;
                hasParam = a.hasParam;
                break;
            case MAX:
            case IMAX:
                depth = Math.max(a.depth, b.depth) + 1;
                hasParam = a.hasParam || b.hasParam;
                break;
            case PARAM:
                depth = 0;
                hasParam = true;
                break;
            default:
                depth = 0;
                hasParam = false;
                break;
        }
        hash = Objects.hash(kind, a, b, name);
    }

    static final RefLevel Z = new RefLevel(ZERO, null, null, null);
    static final RefLevel ONE = succ(Z);

    static RefLevel succ(RefLevel x) {
        return new RefLevel(SUCC, x, null, null);
    }

    static RefLevel max(RefLevel x, RefLevel y) {
        return new RefLevel(MAX, x, y, null);
    }

    static RefLevel imax(RefLevel x, RefLevel y) {
        return new RefLevel(IMAX, x, y, null);
    }

    static RefLevel param(String name) {
        return new RefLevel(PARAM, null, null, name);
    }

    static RefLevel ofNat(int n) {
        RefLevel r = Z;
        for (int i = 0; i < n; i++) {
            r = succ(r);
        }
        return r;
    }

    static RefLevel succN(RefLevel l, int k) {
        for (int i = 0; i < k; i++) {
            l = succ(l);
        }
        return l;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RefLevel)) {
            return false;
        }
        RefLevel x = (RefLevel) o;
        return kind == x.kind && hash == x.hash && Objects.equals(name, x.name) && Objects.equals(a, x.a)
                && Objects.equals(b, x.b);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        switch (kind) {
            case ZERO:
                return "0";
            case SUCC:
                return "(s " + a + ")";
            case MAX:
                return "(m " + a + " " + b + ")";
            case IMAX:
                return "(i " + a + " " + b + ")";
            default:
                return name;
        }
    }

    // ---------------------------------------------------------------- semantics

    /** The value of the level when parameter {@code p} has value {@code env.get(p)}. */
    static long eval(RefLevel l, Map<String, Integer> env) {
        switch (l.kind) {
            case ZERO:
                return 0;
            case SUCC:
                return eval(l.a, env) + 1;
            case MAX:
                return Math.max(eval(l.a, env), eval(l.b, env));
            case IMAX: {
                long y = eval(l.b, env);
                return y == 0 ? 0 : Math.max(eval(l.a, env), y);
            }
            default: {
                Integer v = env.get(l.name);
                if (v == null) {
                    throw new IllegalArgumentException("unbound parameter " + l.name);
                }
                return v;
            }
        }
    }

    static void collectParams(RefLevel l, List<String> out) {
        switch (l.kind) {
            case SUCC:
                collectParams(l.a, out);
                break;
            case MAX:
            case IMAX:
                collectParams(l.a, out);
                collectParams(l.b, out);
                break;
            case PARAM:
                if (!out.contains(l.name)) {
                    out.add(l.name);
                }
                break;
            default:
                break;
        }
    }

    // ---------------------------------------------------------------- level.cpp

    static boolean isExplicit(RefLevel l) {
        switch (l.kind) {
            case ZERO:
                return true;
            case SUCC:
                return isExplicit(l.a);
            default:
                return false;
        }
    }

    /** to_offset: {@code [base, k]} as base level and count. */
    static RefLevel offsetBase(RefLevel l) {
        while (l.kind == SUCC) {
            l = l.a;
        }
        return l;
    }

    static int offsetK(RefLevel l) {
        int k = 0;
        while (l.kind == SUCC) {
            l = l.a;
            k++;
        }
        return k;
    }

    static boolean isZero(RefLevel l) {
        return l.kind == ZERO;
    }

    static RefLevel mkMax(RefLevel l1, RefLevel l2) {
        if (isExplicit(l1) && isExplicit(l2)) {
            return l1.depth >= l2.depth ? l1 : l2;
        } else if (l1.equals(l2)) {
            return l1;
        } else if (isZero(l1)) {
            return l2;
        } else if (isZero(l2)) {
            return l1;
        } else if (l2.kind == MAX && (l2.a.equals(l1) || l2.b.equals(l1))) {
            return l2;
        } else if (l1.kind == MAX && (l1.a.equals(l2) || l1.b.equals(l2))) {
            return l1;
        } else {
            RefLevel b1 = offsetBase(l1);
            RefLevel b2 = offsetBase(l2);
            if (b1.equals(b2)) {
                return offsetK(l1) > offsetK(l2) ? l1 : l2;
            } else {
                return max(l1, l2);
            }
        }
    }

    static RefLevel mkIMax(RefLevel l1, RefLevel l2) {
        if (isNotZero(l2)) {
            return mkMax(l1, l2);
        } else if (isZero(l2)) {
            return l2;
        } else if (isZero(l1) || l1.equals(ONE)) {
            return l2;
        } else if (l1.equals(l2)) {
            return l1;
        } else {
            return imax(l1, l2);
        }
    }

    static boolean isNotZero(RefLevel l) {
        switch (l.kind) {
            case ZERO:
            case PARAM:
                return false;
            case SUCC:
                return true;
            case MAX:
                return isNotZero(l.a) || isNotZero(l.b);
            default:
                return isNotZero(l.b);
        }
    }

    static boolean normalizesToZero(RefLevel l) {
        switch (l.kind) {
            case ZERO:
                return true;
            case PARAM:
            case SUCC:
                return false;
            case MAX:
                return normalizesToZero(l.a) && normalizesToZero(l.b);
            default:
                return normalizesToZero(l.b);
        }
    }

    /** Parameter names are ordered as Java strings (byte order for the ASCII names the tests use). */
    static boolean isNormLt(RefLevel x, RefLevel y) {
        if (x == y) {
            return false;
        }
        RefLevel l1 = offsetBase(x);
        RefLevel l2 = offsetBase(y);
        if (!l1.equals(l2)) {
            if (l1.kind != l2.kind) {
                return l1.kind < l2.kind;
            }
            switch (l1.kind) {
                case PARAM:
                    return l1.name.compareTo(l2.name) < 0;
                case MAX:
                case IMAX:
                    if (!l1.a.equals(l2.a)) {
                        return isNormLt(l1.a, l2.a);
                    } else {
                        return isNormLt(l1.b, l2.b);
                    }
                default:
                    throw new AssertionError("unreachable");
            }
        } else {
            return offsetK(x) < offsetK(y);
        }
    }

    static void pushMaxArgs(RefLevel l, List<RefLevel> r) {
        if (l.kind == MAX) {
            pushMaxArgs(l.a, r);
            pushMaxArgs(l.b, r);
        } else {
            r.add(l);
        }
    }

    static RefLevel mkMax(List<RefLevel> args) {
        int nargs = args.size();
        if (nargs == 1) {
            return args.get(0);
        } else {
            RefLevel r = mkMax(args.get(nargs - 2), args.get(nargs - 1));
            int i = nargs - 2;
            while (i > 0) {
                --i;
                r = mkMax(args.get(i), r);
            }
            return r;
        }
    }

    static RefLevel normalize(RefLevel l) {
        RefLevel r = offsetBase(l);
        int k = offsetK(l);
        switch (r.kind) {
            case ZERO:
            case PARAM:
                return l;
            case IMAX: {
                RefLevel l1 = normalize(r.a);
                RefLevel l2 = normalize(r.b);
                return succN(mkIMax(l1, l2), k);
            }
            case MAX: {
                List<RefLevel> todo = new ArrayList<>();
                List<RefLevel> args = new ArrayList<>();
                pushMaxArgs(r, todo);
                for (RefLevel x : todo) {
                    pushMaxArgs(normalize(x), args);
                }
                args.sort((p, q) -> isNormLt(p, q) ? -1 : (isNormLt(q, p) ? 1 : 0));
                List<RefLevel> rargs = new ArrayList<>();
                int i = 0;
                if (isExplicit(args.get(i))) {
                    while (i + 1 < args.size() && isExplicit(args.get(i + 1))) {
                        i++;
                    }
                    int kk = offsetK(args.get(i));
                    int j = i + 1;
                    for (; j < args.size(); j++) {
                        if (offsetK(args.get(j)) >= kk) {
                            break;
                        }
                    }
                    if (j < args.size()) {
                        i++;
                    }
                }
                rargs.add(args.get(i));
                RefLevel prevBase = offsetBase(args.get(i));
                int prevK = offsetK(args.get(i));
                i++;
                for (; i < args.size(); i++) {
                    RefLevel curBase = offsetBase(args.get(i));
                    int curK = offsetK(args.get(i));
                    if (prevBase.equals(curBase)) {
                        if (prevK < curK) {
                            prevBase = curBase;
                            prevK = curK;
                            rargs.remove(rargs.size() - 1);
                            rargs.add(args.get(i));
                        }
                    } else {
                        prevBase = curBase;
                        prevK = curK;
                        rargs.add(args.get(i));
                    }
                }
                for (int j = 0; j < rargs.size(); j++) {
                    rargs.set(j, succN(rargs.get(j), k));
                }
                return mkMax(rargs);
            }
            default:
                throw new AssertionError("unreachable");
        }
    }

    static boolean isEquivalent(RefLevel lhs, RefLevel rhs) {
        return lhs.equals(rhs) || normalize(lhs).equals(normalize(rhs));
    }

    static boolean isGeqCore(RefLevel l1, RefLevel l2) {
        if (l1.equals(l2) || isZero(l2)) {
            return true;
        }
        if (l2.kind == MAX) {
            return isGeq(l1, l2.a) && isGeq(l1, l2.b);
        }
        if (l1.kind == MAX && (isGeq(l1.a, l2) || isGeq(l1.b, l2))) {
            return true;
        }
        if (l2.kind == IMAX) {
            return isGeq(l1, l2.a) && isGeq(l1, l2.b);
        }
        if (l1.kind == IMAX) {
            return isGeq(l1.b, l2);
        }
        RefLevel b1 = offsetBase(l1);
        RefLevel b2 = offsetBase(l2);
        int k1 = offsetK(l1);
        int k2 = offsetK(l2);
        if (b1.equals(b2) || isZero(b2)) {
            return k1 >= k2;
        }
        if (k1 == k2 && k1 > 0) {
            return isGeq(b1, b2);
        }
        return false;
    }

    static boolean isGeq(RefLevel l1, RefLevel l2) {
        return isGeqCore(normalize(l1), normalize(l2));
    }

    /** instantiate via replace_level_fn / update_succ / update_max. */
    static RefLevel instantiate(RefLevel l, List<String> ps, List<RefLevel> ls) {
        if (!l.hasParam) {
            return l;
        }
        switch (l.kind) {
            case PARAM: {
                for (int i = 0; i < ps.size() && i < ls.size(); i++) {
                    if (ps.get(i).equals(l.name)) {
                        return ls.get(i);
                    }
                }
                return l;
            }
            case SUCC: {
                RefLevel na = instantiate(l.a, ps, ls);
                return na.equals(l.a) ? l : succ(na);
            }
            default: {
                RefLevel n1 = instantiate(l.a, ps, ls);
                RefLevel n2 = instantiate(l.b, ps, ls);
                if (n1.equals(l.a) && n2.equals(l.b)) {
                    return l;
                }
                return l.kind == MAX ? mkMax(n1, n2) : mkIMax(n1, n2);
            }
        }
    }

    /** get_undef_param: first undeclared parameter in pre-order, or null. */
    static String undefParam(RefLevel l, List<String> ps) {
        if (!l.hasParam) {
            return null;
        }
        switch (l.kind) {
            case PARAM:
                return ps.contains(l.name) ? null : l.name;
            case SUCC:
                return undefParam(l.a, ps);
            default: {
                String r = undefParam(l.a, ps);
                return r != null ? r : undefParam(l.b, ps);
            }
        }
    }
}
