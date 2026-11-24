package rlvm.machine;

import static rlvm.machine.Op.SIG;

import rlvm.api.MachineErrors;
import rlvm.store.Kind;
import rlvm.store.Terms;

/**
 * Pattern matching of telescope domains and results (ISA 4.4.4). Owner: M7. Staging step 3.
 *
 * <p>{@code MATCH(p, g, v)} decides "{@code EVAL(p, g, ls)} is convertible with {@code v}" without
 * building the left side. It answers T or "unknown" (F), never a refutation: the caller then builds the
 * domain and runs {@code CONV} (B5 of ISA 6.3). T is sound by congruence (R02 8.6): a constant applied to
 * pairwise convertible arguments at equivalent levels is convertible with itself, whatever the constant
 * unfolds to. No judgment is called and nothing is interned; at most {@code Op.MATCH_NODES} pattern
 * nodes are visited, on an explicit stack in scratch.
 */
final class Match {
    private Match() {}

    /**
     * MATCH(p, g, v) (ISA 4.4.4, B5): decides "EVAL(p, g, ls) is convertible with v" without building the left side;
     * g = the telescope variables in X[gb .. gb + gn). At most {@code Op.MATCH_NODES} pattern nodes.
     * @return T, or F meaning unknown (never a refutation)
     */
    static int match(Machine m, int pattern, int gb, int gn, int ls, int v) {
        return matchIn(m, m.X, gb, gn, pattern, ls, v);
    }

    /**
     * {@link #match} with the telescope variables in {@code g[gb .. gb + gn)} (the window of a TELE
     * activation); de Bruijn index j of the pattern is {@code g[gb + gn - 1 - j]}.
     *
     * @return T, F (unknown), or FATAL ({@code E_MEM}: no scratch)
     */
    static int matchIn(Machine m, int[] g, int gb, int gn, int pattern, int ls, int v) {
        int cap = 2 * Op.MATCH_NODES + 2;
        if (!m.ensureScratch(cap)) {
            return m.fault(MachineErrors.E_MEM);
        }
        int xb = m.xp;
        m.xp = xb + cap; // the stack is this operation's scratch while the level operations below run
        int sp = xb;
        m.X[sp++] = pattern;
        m.X[sp++] = v;
        int budget = Op.MATCH_NODES;
        int r = Layout.T;
        while (sp > xb) {
            int[] x = m.X;
            int val = x[--sp];
            int p = x[--sp];
            int pushed = --budget < 0 ? -1 : node(m, g, gb, gn, p, ls, val, sp, xb + cap);
            if (pushed < 0) {
                r = Layout.F;
                break;
            }
            sp += pushed;
        }
        m.xp = xb;
        return r;
    }

    /**
     * One pattern node against one value (ISA 4.4.4): variable, sort, constant, or an application of a
     * constant, whose argument pairs are pushed on the stack at {@code sp}.
     *
     * @return the number of words pushed, or -1 for "unknown"
     */
    private static int node(Machine m, int[] g, int gb, int gn, int p, int ls, int v, int sp, int limit) {
        Terms terms = m.terms;
        if (v >= 0 && v <= Layout.HOLE) {
            return -1;
        }
        if (v > 0 && m.vh[v << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT == Layout.SUSP) {
            v = Values.peek(m, v);
        }
        switch (terms.kind(p)) {
            case Kind.BVAR:
                return variable(m, g, gb, gn, terms.a(p), v);
            case Kind.SORT:
                return v > 0 && m.vh[v << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT == Layout.SORT
                        && Levels.lvlEq(m, Levels.lsub(m, terms.a(p), ls), m.vh[(v << Layout.REC_SHIFT) + Layout.A]) ? 0 : -1;
            case Kind.CONST:
                return v > 0 && constMatches(m, p, ls, v) ? 0 : -1;
            case Kind.NATLIT:
                return !terms.isNatBig(p) && terms.natSmall(p) >= 0 && terms.natSmall(p) < Layout.IMM_LIMIT
                        && v == ((int) terms.natSmall(p) | Layout.IMM) ? 0 : -1;
            case Kind.APP:
                return app(m, g, gb, gn, p, ls, v, sp, limit);
            case Kind.LAM:
            case Kind.PI:
                return closure(m, g, gb, gn, p, ls, v);
            default:
                return -1;
        }
    }

    /** Variable j of the pattern: {@code g_j == v} and {@code g_j != HOLE} (ISA 4.4.4), both seen through PEEK. */
    private static int variable(Machine m, int[] g, int gb, int gn, int j, int v) {
        if (j < 0 || j >= gn) {
            return -1;
        }
        int gj = g[gb + gn - 1 - j];
        if (gj >= 0 && gj <= Layout.HOLE) {
            return -1;
        }
        if (gj > 0 && m.vh[gj << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT == Layout.SUSP) {
            gj = Values.peek(m, gj);
        }
        return gj == v ? 0 : -1;
    }

    /** Pattern constant against a CONST record: the same row and equivalent levels after LSUB. */
    private static boolean constMatches(Machine m, int p, int ls, int v) {
        int o = v << Layout.REC_SHIFT;
        int[] vh = m.vh;
        if (vh[o] >>> Layout.KIND_SHIFT != Layout.CONST || vh[o + Layout.A] != m.terms.a(p)) {
            return false;
        }
        int pl = m.terms.b(p);
        if (m.terms.hasLevelParam(p)) {
            pl = Levels.lsubList(m, pl, ls);
        }
        int vl = vh[o + Layout.B];
        return pl == vl || Levels.listsEq(m, pl, vl);
    }

    /**
     * An application against a NEU of the same length whose head matches: a constant head by row and
     * levels ({@link #constMatches}); a variable head by identity of the neutral it is bound to (the
     * application is then inert and EVAL builds exactly this NEU form, ISA 4.2 ARGV). The argument pairs
     * are pushed. {@code Nat.succ p} against an NSUC value (ISA 2.9) matches {@code p} against the
     * predecessor when that is a base value. Everything else: unknown.
     */
    private static int app(Machine m, int[] g, int gb, int gn, int p, int ls, int v, int sp, int limit) {
        Terms terms = m.terms;
        int h = terms.head(p);
        int hk = terms.kind(h);
        if (v < 0 || hk != Kind.CONST && hk != Kind.BVAR) {
            return -1;
        }
        int[] vh = m.vh;
        int o = v << Layout.REC_SHIFT;
        int kind = vh[o] >>> Layout.KIND_SHIFT;
        if (kind == Layout.NSUC) {
            return succ(m, p, h, v, sp, limit);
        }
        if (kind != Layout.NEU) {
            return -1;
        }
        int n = vh[o] & Layout.AUX_MASK;
        int head = vh[o + Layout.A];
        if (head <= Layout.HOLE || sp + 2 * n > limit) {
            return -1;
        }
        if (hk == Kind.CONST ? !constMatches(m, h, ls, head) : variable(m, g, gb, gn, terms.a(h), head) != 0) {
            return -1;
        }
        int run = vh[o + Layout.B];
        int[] x = m.X;
        int t = p;
        for (int i = n - 1; i >= 0; i--) {
            if (terms.kind(t) != Kind.APP) {
                return -1; // the pattern has fewer arguments than the value
            }
            x[sp + 2 * i] = terms.b(t);
            x[sp + 2 * i + 1] = Runs.arg(m, run, i);
            t = terms.a(t);
        }
        return t == h ? 2 * n : -1;
    }

    /** {@code Nat.succ p} against {@code NSUC(b, 1)}: the pair (p, b). Deeper offsets and literals: unknown. */
    private static int succ(Machine m, int p, int h, int v, int sp, int limit) {
        Terms terms = m.terms;
        int o = v << Layout.REC_SHIFT;
        if (terms.a(p) != h || terms.kind(h) != Kind.CONST || terms.a(h) != m.specials.cid(rlvm.store.Specials.NAT_SUCC)
                || m.vh[o + Layout.B] != 1 || sp + 2 > limit) {
            return -1;
        }
        m.X[sp] = terms.b(p);
        m.X[sp + 1] = m.vh[o + Layout.A];
        return 2;
    }

    /**
     * A binder node against a closure (ISA 2.5 I-ID): EVAL of the node under g is the record
     * {@code (kind, term, PRUNE(g, mask), ls')}, so a closure of the same kind and term with the same ls'
     * whose frame agrees with g on the node's mask is that very value.
     */
    private static int closure(Machine m, int[] g, int gb, int gn, int p, int ls, int v) {
        Terms terms = m.terms;
        if (v < 0) {
            return -1;
        }
        int[] vh = m.vh;
        int o = v << Layout.REC_SHIFT;
        int kind = vh[o] >>> Layout.KIND_SHIFT;
        long mask = terms.fvMask(p);
        if (kind != (terms.kind(p) == Kind.LAM ? Layout.LAM : Layout.PI) || vh[o + Layout.A] != p || mask < 0
                || vh[o + Layout.C] != (terms.hasLevelParam(p) ? ls : 0)) {
            return -1;
        }
        int frame = vh[o + Layout.B];
        int len = 64 - Long.numberOfLeadingZeros(mask);
        if (len > gn || (m.rs[frame] >>> Layout.RUN_LEN_SHIFT) != len && frame != Layout.RUN_EMPTY || frame == Layout.RUN_EMPTY && len != 0) {
            return -1;
        }
        for (int j = 0; j < len; j++) {
            int idx = len - 1 - j;
            if ((mask >>> idx & 1) != 0 && m.rs[frame + Layout.RUN_H + j] != g[gb + gn - 1 - idx]) {
                return -1;
            }
        }
        return 0;
    }

    /**
     * Is term {@code t} worth a MATCH instruction (ISA 3.2 "the domain as a pattern over the telescope
     * variables"): an open application of a constant? (A closed domain is a pool value compared by
     * identity; a variable is compared directly.) Static, used by the elaborator.
     */
    static boolean isPattern(Machine m, int t) {
        Terms terms = m.terms;
        if (terms.kind(t) != Kind.APP || terms.isClosed(t)) {
            return false;
        }
        int h = terms.head(t);
        return terms.kind(h) == Kind.CONST && terms.a(h) > 0 && terms.spineLength(t) <= Op.MATCH_NODES / 2;
    }
}
