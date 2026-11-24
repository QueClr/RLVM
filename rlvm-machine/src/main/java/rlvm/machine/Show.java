package rlvm.machine;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import rlvm.api.LevelOps;
import rlvm.store.DeclTable;
import rlvm.store.IntLists;
import rlvm.store.Kind;
import rlvm.store.LitPool;
import rlvm.store.NameTable;
import rlvm.store.Node;
import rlvm.store.Store;
import rlvm.store.Terms;

/**
 * Pretty-printer of terms, levels, values, runs and frames, for diagnostics only ({@code lastMessage},
 * test failures, debugging). Never on a checking path: it allocates strings and recurses on the host
 * stack (bounded by a depth limit). Owner: M6.
 *
 * <p>Terms are printed in the S-expression language of the test fixture ({@code Sexp} in the test tree),
 * so that a printed term can be parsed back: bound variables as raw indices {@code #i}, binders named
 * {@code _}.
 * <pre>
 * term  ::= #n | Prop | Type | (sort l) | c | (@ c l1 .. ln) | (f a1 .. an) | (fun (_ : T) body)
 *         | (pi (_ : T) body) | (let (_ : T := v) body) | (proj S i e) | (lit n) | (str "..")
 * level ::= n | u | (succ l) | (max l l) | (imax l l)
 * </pre>
 */
public final class Show {
    private Show() {}

    private static final int DEPTH = 40;

    // ---------------------------------------------------------------------------------------------------
    // Levels
    // ---------------------------------------------------------------------------------------------------

    public static String level(LevelOps lv, NameTable names, int l) {
        StringBuilder sb = new StringBuilder();
        level(sb, lv, names, l, 0);
        return sb.toString();
    }

    private static void level(StringBuilder sb, LevelOps lv, NameTable names, int l, int depth) {
        if (depth > 200) {
            sb.append("..");
            return;
        }
        int succs = 0;
        int base = l;
        while (lv.kind(base) == LevelOps.KIND_SUCC) {
            base = lv.succOf(base);
            succs++;
        }
        if (lv.isZero(base)) {
            sb.append(succs);
            return;
        }
        for (int i = 0; i < succs; i++) {
            sb.append("(succ ");
        }
        int k = lv.kind(base);
        if (k == LevelOps.KIND_PARAM) {
            sb.append(names == null ? "p" + lv.paramName(base) : names.toString(lv.paramName(base)));
        } else if (k == LevelOps.KIND_MAX || k == LevelOps.KIND_IMAX) {
            sb.append(k == LevelOps.KIND_MAX ? "(max " : "(imax ");
            level(sb, lv, names, lv.lhs(base), depth + 1);
            sb.append(' ');
            level(sb, lv, names, lv.rhs(base), depth + 1);
            sb.append(')');
        } else {
            sb.append("?level").append(base);
        }
        for (int i = 0; i < succs; i++) {
            sb.append(')');
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // Terms
    // ---------------------------------------------------------------------------------------------------

    /** The whole term, parseable by the fixture's {@code Sexp} (use for small terms only). */
    public static String term(Terms terms, Store store, LevelOps lv, IntLists lists, LitPool lits, int t) {
        StringBuilder sb = new StringBuilder();
        term(sb, terms, store, lv, lists, lits, t, Integer.MAX_VALUE, new int[] {Integer.MAX_VALUE});
        return sb.toString();
    }

    /** The term of a machine's context, cut off at a depth of 40 and about 400 nodes. */
    public static String term(Machine m, int t) {
        if (m.terms == null) {
            return "t" + t;
        }
        StringBuilder sb = new StringBuilder();
        term(sb, m.terms, m.store, m.levels, m.lists, m.lits, t, DEPTH, new int[] {400});
        return sb.toString();
    }

    private static void term(StringBuilder sb, Terms terms, Store store, LevelOps lv, IntLists lists, LitPool lits, int t,
            int depth, int[] budget) {
        if (depth <= 0 || --budget[0] < 0) {
            sb.append("..");
            return;
        }
        NameTable names = store.names();
        DeclTable decls = store.decls();
        int kind = terms.kind(t);
        switch (kind) {
            case Kind.BVAR:
                sb.append('#').append(terms.a(t));
                return;
            case Kind.SORT: {
                int l = terms.a(t);
                if (lv.isZero(l)) {
                    sb.append("Prop");
                } else if (lv.kind(l) == LevelOps.KIND_SUCC && lv.isZero(lv.succOf(l))) {
                    sb.append("Type");
                } else {
                    sb.append("(sort ");
                    level(sb, lv, names, l, 0);
                    sb.append(')');
                }
                return;
            }
            case Kind.CONST: {
                int a = terms.a(t);
                int ll = terms.b(t);
                int n = lists.length(ll);
                if (n > 0) {
                    sb.append("(@ ");
                }
                constName(sb, names, decls, a);
                for (int i = 0; i < n; i++) {
                    sb.append(' ');
                    level(sb, lv, names, lists.get(ll, i), 0);
                }
                if (n > 0) {
                    sb.append(')');
                }
                return;
            }
            case Kind.APP: {
                int n = terms.spineLength(t);
                int[] args = new int[n];
                int head = terms.spine(t, args, 0);
                sb.append('(');
                term(sb, terms, store, lv, lists, lits, head, depth - 1, budget);
                for (int i = 0; i < n; i++) {
                    sb.append(' ');
                    term(sb, terms, store, lv, lists, lits, args[i], depth - 1, budget);
                }
                sb.append(')');
                return;
            }
            case Kind.LAM:
            case Kind.PI:
                sb.append(kind == Kind.LAM ? "(fun (_ : " : "(pi (_ : ");
                term(sb, terms, store, lv, lists, lits, terms.a(t), depth - 1, budget);
                sb.append(") ");
                term(sb, terms, store, lv, lists, lits, terms.b(t), depth - 1, budget);
                sb.append(')');
                return;
            case Kind.LET:
                sb.append("(let (_ : ");
                term(sb, terms, store, lv, lists, lits, terms.a(t), depth - 1, budget);
                sb.append(" := ");
                term(sb, terms, store, lv, lists, lits, terms.b(t), depth - 1, budget);
                sb.append(") ");
                term(sb, terms, store, lv, lists, lits, terms.c(t), depth - 1, budget);
                sb.append(')');
                return;
            case Kind.PROJ:
                sb.append("(proj ");
                constName(sb, names, decls, terms.c(t));
                sb.append(' ').append(terms.b(t)).append(' ');
                term(sb, terms, store, lv, lists, lits, terms.a(t), depth - 1, budget);
                sb.append(')');
                return;
            case Kind.NATLIT:
                sb.append("(lit ");
                if (terms.isNatBig(t)) {
                    sb.append(bigNat(lits, terms.a(t)));
                } else {
                    sb.append(Long.toUnsignedString(terms.natSmall(t)));
                }
                sb.append(')');
                return;
            case Kind.STRLIT:
                sb.append("(str \"").append(string(lits, terms.a(t))).append("\")");
                return;
            case Kind.FVAR:
                sb.append("$fvar").append(t & Node.INDEX_MASK);
                return;
            default:
                sb.append("?term").append(t);
        }
    }

    private static void constName(StringBuilder sb, NameTable names, DeclTable decls, int cidOrNotName) {
        if (cidOrNotName > 0) {
            names.appendTo(sb, decls.name(cidOrNotName));
        } else {
            names.appendTo(sb, ~cidOrNotName);
            sb.append("?unbound");
        }
    }

    private static String bigNat(LitPool lits, int index) {
        if (lits == null) {
            return "big" + index;
        }
        int n = lits.natLimbCount(index);
        BigInteger v = BigInteger.ZERO;
        for (int i = n - 1; i >= 0; i--) {
            long limb = lits.natLimb(index, i);
            v = v.shiftLeft(64).or(new BigInteger(Long.toUnsignedString(limb)));
        }
        return v.toString();
    }

    private static String string(LitPool lits, int index) {
        if (lits == null) {
            return "#" + index;
        }
        byte[] b = new byte[lits.stringByteLength(index)];
        lits.stringBytes(index, b, 0);
        return new String(b, StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------------------------------------------
    // Values (ISA section 2)
    // ---------------------------------------------------------------------------------------------------

    /** A value ref, to a depth of 6, e.g. {@code (Nat.add ?0:Nat 2)}. */
    public static String value(Machine m, int v) {
        StringBuilder sb = new StringBuilder();
        value(sb, m, v, 6);
        return sb.toString();
    }

    /** The value with its ref and flags: {@code #17 NEU [WHNF] (f ?0:Nat)}. */
    public static String record(Machine m, int v) {
        if (v < 0 || v < Layout.FIRST_RECORD || v >= m.vp) {
            return value(m, v);
        }
        int o = v << Layout.REC_SHIFT;
        int w0 = m.vh[o];
        StringBuilder sb = new StringBuilder();
        sb.append('#').append(v).append(' ').append(kindName(w0 >>> Layout.KIND_SHIFT)).append(' ');
        flags(sb, w0);
        sb.append(' ');
        value(sb, m, v, 6);
        sb.append("  memo[");
        for (int i = 0; i < 4; i++) {
            sb.append(i == 0 ? "" : ", ").append(m.vh[o + Layout.M0 + i]);
        }
        return sb.append(']').toString();
    }

    public static String kindName(int kind) {
        switch (kind) {
            case Layout.SORT: return "SORT";
            case Layout.NBIG: return "NBIG";
            case Layout.STR: return "STR";
            case Layout.LAM: return "LAM";
            case Layout.PI: return "PI";
            case Layout.PII: return "PII";
            case Layout.SUSP: return "SUSP";
            case Layout.FVAR: return "FVAR";
            case Layout.CONST: return "CONST";
            case Layout.NEU: return "NEU";
            case Layout.PROJ: return "PROJ";
            case Layout.NSUC: return "NSUC";
            case Layout.K_IMM: return "IMM";
            default: return "KIND" + kind;
        }
    }

    private static void flags(StringBuilder sb, int w0) {
        int f = Layout.flagsOf(w0);
        sb.append('[');
        String sep = "";
        if ((f & Layout.FL_OPEN) != 0) {
            sb.append(sep).append("OPEN");
            sep = " ";
        }
        if ((f & Layout.FL_WHNF) != 0) {
            sb.append(sep).append("WHNF");
            sep = " ";
        }
        if ((f & Layout.FL_ACTIVE) != 0) {
            sb.append(sep).append("ACTIVE");
            sep = " ";
        }
        if ((f & Layout.FL_COREFULL) != 0) {
            sb.append(sep).append("COREFULL");
            sep = " ";
        }
        int proof = (f & Layout.FL_PROOF_MASK) >>> Layout.PROOF_SHIFT;
        if (proof != 0) {
            sb.append(sep).append("PROOF=").append(proof);
            sep = " ";
        }
        int propt = (f & Layout.FL_PROPT_MASK) >>> Layout.PROPT_SHIFT;
        if (propt != 0) {
            sb.append(sep).append("PROPT=").append(propt);
            sep = " ";
        }
        if ((f & Layout.FL_INSCOPE) != 0) {
            sb.append(sep).append("INSCOPE/LET");
            sep = " ";
        }
        int hc = (f & Layout.FL_HCLASS_MASK) >>> Layout.HCLASS_SHIFT;
        if (hc != 0) {
            sb.append(sep).append("HC=").append(hc);
        }
        sb.append(']');
    }

    private static void value(StringBuilder sb, Machine m, int v, int depth) {
        if (v < 0) {
            sb.append(v & Layout.IMM_MASK);
            return;
        }
        if (v == Layout.NONE) {
            sb.append("NONE");
            return;
        }
        if (v == Layout.HOLE) {
            sb.append("HOLE");
            return;
        }
        if (v >= Op.MISS) {
            sb.append(v == Op.MISS ? "MISS" : v == Op.SUSP ? "SUSP" : v == Op.ABORT ? "ABORT" : "FATAL");
            return;
        }
        if (v >= m.vp) {
            sb.append("?ref").append(v);
            return;
        }
        if (depth <= 0) {
            sb.append("..#").append(v);
            return;
        }
        int o = v << Layout.REC_SHIFT;
        int[] vh = m.vh;
        int w0 = vh[o];
        int a = vh[o + Layout.A];
        int b = vh[o + Layout.B];
        int c = vh[o + Layout.C];
        NameTable names = m.store == null ? null : m.store.names();
        switch (w0 >>> Layout.KIND_SHIFT) {
            case Layout.SORT:
                sb.append("(sort ");
                if (m.levels != null) {
                    level(sb, m.levels, names, a, 0);
                } else {
                    sb.append('l').append(a);
                }
                sb.append(')');
                return;
            case Layout.NBIG:
                if ((w0 & Layout.AUX_MASK) == Layout.NBIG_INLINE) {
                    sb.append(Long.toUnsignedString((a & 0xFFFFFFFFL) | (long) b << 32));
                } else {
                    sb.append("(nat store#").append(a).append(')');
                }
                return;
            case Layout.STR:
                sb.append("(str \"").append(string(m.lits, a)).append("\")");
                return;
            case Layout.LAM:
            case Layout.PI:
            case Layout.PII:
            case Layout.SUSP: {
                int k = w0 >>> Layout.KIND_SHIFT;
                sb.append(k == Layout.LAM ? "(LAM " : k == Layout.PI ? "(PI " : k == Layout.PII ? "(PII " : "(SUSP ");
                if ((w0 & Layout.W0_LET) != 0 && k == Layout.SUSP) {
                    sb.append("let ");
                }
                sb.append(term(m, a));
                sb.append(" {");
                run(sb, m, b, depth - 1);
                sb.append('}');
                if (c != 0) {
                    sb.append(" ls").append(c);
                }
                sb.append(')');
                return;
            }
            case Layout.FVAR:
                sb.append('?').append(b).append(':');
                value(sb, m, a, Math.min(depth - 1, 2));
                return;
            case Layout.CONST: {
                if (m.decls != null && a > 0 && a < m.decls.constantCount() + 1) {
                    names.appendTo(sb, m.decls.name(a));
                } else {
                    sb.append("const").append(a);
                }
                if (b != 0 && m.lists != null && m.levels != null) {
                    sb.append(".{");
                    for (int i = 0, n = m.lists.length(b); i < n; i++) {
                        sb.append(i == 0 ? "" : ", ");
                        level(sb, m.levels, names, m.lists.get(b, i), 0);
                    }
                    sb.append('}');
                }
                return;
            }
            case Layout.NEU:
                sb.append('(');
                value(sb, m, a, depth - 1);
                sb.append(' ');
                run(sb, m, b, depth - 1);
                sb.append(')');
                return;
            case Layout.PROJ:
                sb.append("(proj ");
                if (m.decls != null && b > 0) {
                    names.appendTo(sb, m.decls.name(b));
                } else {
                    sb.append("struct").append(b);
                }
                sb.append(' ').append(c).append(' ');
                value(sb, m, a, depth - 1);
                sb.append(')');
                return;
            case Layout.NSUC:
                sb.append("(succ^").append(b).append(' ');
                value(sb, m, a, depth - 1);
                sb.append(')');
                return;
            default:
                sb.append("?kind").append(w0 >>> Layout.KIND_SHIFT).append('#').append(v);
        }
    }

    /** The slots of a run, space-separated; a far frame shows its REST run after {@code |}. */
    public static String run(Machine m, int run) {
        StringBuilder sb = new StringBuilder();
        run(sb, m, run, 4);
        return sb.toString();
    }

    private static void run(StringBuilder sb, Machine m, int run, int depth) {
        if (run == Layout.RUN_EMPTY) {
            return;
        }
        if (run < 0 || run >= m.rsp) {
            sb.append("?run").append(run);
            return;
        }
        int hdr = m.rs[run];
        int n = Layout.runLen(hdr);
        int base = run + Layout.runHeaderWords(hdr);
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            if (i >= 12) {
                sb.append("..(").append(n).append(')');
                break;
            }
            value(sb, m, m.rs[base + i], depth);
        }
        if ((hdr & Layout.RUN_FAR) != 0) {
            sb.append(" | ");
            run(sb, m, m.rs[run + Layout.RUN_REST_WORD], depth - 1);
        }
    }

    /** The header of a run: {@code FRAME len=3 open far rest=17}. */
    public static String runHeader(Machine m, int run) {
        if (run == Layout.RUN_EMPTY) {
            return "EMPTY";
        }
        if (run < 0 || run >= m.rsp) {
            return "?run" + run;
        }
        int hdr = m.rs[run];
        int kind = Layout.runKind(hdr);
        StringBuilder sb = new StringBuilder();
        sb.append(kind == Layout.RUN_FRAME ? "FRAME" : kind == Layout.RUN_SPINE ? "SPINE" : kind == Layout.RUN_REST ? "REST" : "KIND3");
        sb.append(" len=").append(Layout.runLen(hdr));
        if ((hdr & Layout.RUN_OPEN) != 0) {
            sb.append(" open");
        }
        if ((hdr & Layout.RUN_FAR) != 0) {
            sb.append(" far rest=").append(m.rs[run + Layout.RUN_REST_WORD]);
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------------------------------------------
    // Frames
    // ---------------------------------------------------------------------------------------------------

    /** The suspended frames of W, top first: {@code CONV[3](12, 15)}. */
    public static String worklist(Machine m) {
        StringBuilder sb = new StringBuilder();
        int p = m.wp;
        int shown = 0;
        while (p > 0 && shown++ < 50) {
            int hdr = m.W[p - 1];
            int n = hdr & Op.N_MASK;
            int key = hdr >>> Op.KEY_SHIFT;
            sb.append(Op.name(key >>> 4)).append('[').append(key & 15).append("](");
            for (int i = 0; i < n; i++) {
                sb.append(i == 0 ? "" : ", ").append(m.W[p - 1 - n + i]);
            }
            sb.append(")\n");
            p -= n + 1;
        }
        if (p > 0) {
            sb.append("... ").append(p).append(" more words\n");
        }
        return sb.toString();
    }
}
