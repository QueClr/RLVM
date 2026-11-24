package rlvm.testkit;

import rlvm.api.LevelOps;
import rlvm.store.Kind;
import rlvm.store.Store;
import rlvm.store.Terms;

/**
 * A bounded, one-line rendering of a term for difference reports: constants by name, {@code #i} for a
 * bound variable, {@code @n} for the free variable with serial number n, {@code ..} where the budget
 * ran out. Never throws for a property of the term: anything it cannot read is printed as {@code ?}.
 */
final class TermPrinter {

    private final Store store;
    private final Terms terms;
    private final LevelOps levels;
    private StringBuilder sb;
    private int nodes;

    TermPrinter(Store store, Terms terms, LevelOps levels) {
        this.store = store;
        this.terms = terms;
        this.levels = levels;
    }

    /** At most about {@code maxNodes} nodes and 6 levels of nesting. */
    String show(int term, int maxNodes) {
        sb = new StringBuilder();
        nodes = maxNodes;
        try {
            put(term, 0);
        } catch (RuntimeException e) {
            sb.append('?');
        }
        String s = sb.toString();
        sb = null;
        return s;
    }

    /** The head symbol of a weak head normal form: what {@link KernelSampler} compares. */
    String head(int term) {
        try {
            int h = terms.head(term);
            switch (terms.kind(h)) {
                case Kind.SORT:
                    return "sort";
                case Kind.PI:
                    return "pi";
                case Kind.LAM:
                    return "lam";
                case Kind.CONST:
                    return "const " + constName(h);
                case Kind.FVAR:
                    return "fvar " + h;
                case Kind.NATLIT:
                    return "nat-literal";
                case Kind.STRLIT:
                    return "string-literal";
                case Kind.PROJ:
                    return "proj";
                case Kind.LET:
                    return "let";
                case Kind.BVAR:
                    return "bvar";
                default:
                    return "kind " + terms.kind(h);
            }
        } catch (RuntimeException e) {
            return "?";
        }
    }

    private String constName(int node) {
        int name = terms.c(node);
        try {
            if (name > 0) {
                return store.names().toString(name);
            }
            int cid = terms.a(node);
            return cid > 0 ? store.names().toString(store.decls().name(cid)) : store.names().toString(~cid);
        } catch (RuntimeException e) {
            return "cid " + terms.a(node);
        }
    }

    private void put(int t, int depth) {
        if (t == 0) {
            sb.append("null");
            return;
        }
        if (nodes-- <= 0 || depth > 6) {
            sb.append("..");
            return;
        }
        switch (terms.kind(t)) {
            case Kind.BVAR:
                sb.append('#').append(terms.a(t));
                break;
            case Kind.FVAR:
                sb.append('@').append(terms.a(t));
                break;
            case Kind.SORT:
                sb.append("Sort ");
                try {
                    sb.append(levels.toString(terms.a(t)));
                } catch (RuntimeException e) {
                    sb.append('?');
                }
                break;
            case Kind.CONST:
                sb.append(constName(t));
                break;
            case Kind.APP: {
                int n = terms.spineLength(t);
                int[] args = new int[n];
                int head = terms.spine(t, args, 0);
                sb.append('(');
                put(head, depth + 1);
                for (int i = 0; i < n; i++) {
                    sb.append(' ');
                    if (i >= 6 && n > 7) {
                        sb.append("..+").append(n - i);
                        break;
                    }
                    put(args[i], depth + 1);
                }
                sb.append(')');
                break;
            }
            case Kind.LAM:
            case Kind.PI:
                sb.append(terms.kind(t) == Kind.LAM ? "(fun " : "(Pi ");
                put(terms.a(t), depth + 1);
                sb.append(" => ");
                put(terms.b(t), depth + 1);
                sb.append(')');
                break;
            case Kind.LET:
                sb.append("(let ");
                put(terms.a(t), depth + 1);
                sb.append(" := ");
                put(terms.b(t), depth + 1);
                sb.append("; ");
                put(terms.c(t), depth + 1);
                sb.append(')');
                break;
            case Kind.PROJ:
                sb.append('(');
                put(terms.a(t), depth + 1);
                sb.append(").").append(terms.b(t));
                break;
            case Kind.NATLIT:
                if (terms.isNatBig(t)) {
                    sb.append("<big nat>");
                } else {
                    sb.append(Long.toUnsignedString(terms.natSmall(t)));
                }
                break;
            case Kind.STRLIT:
                sb.append("<string, ").append(terms.b(t)).append(" chars>");
                break;
            default:
                sb.append("<kind ").append(terms.kind(t)).append('>');
        }
    }
}
