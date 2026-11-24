package rlvm.machine;

import rlvm.store.Kind;
import rlvm.store.Node;
import rlvm.store.Terms;

/**
 * SAME(a, b): bounded structural identity of two values, i.e. of the TERMS they stand for (NOTES-M4 D28).
 * Owner: M4. Behind the switch {@code same} ({@code Op.SW_SAME}, the experiment bit {@code Op.SW_M4A}); nothing
 * of it runs in v0 or in strict mode. Which pairs are walked: {@code Conv.sameShape}.
 *
 * <h2>What it decides</h2>
 * The official {@code is_def_eq} starts with {@code quick_is_def_eq}, whose first test is
 * {@code is_equiv(t, s)}: structural equality of the two expressions (R02 8.2). It repeats that test after
 * every {@code whnf_core} and after every lazy-delta step. The machine has the test only as identity of
 * value refs (I-ID), which is complete for neutral values built from the same parts but not for closures
 * and suspensions: {@code SUSP(t, frame)} stands for the instantiated term {@code t[frame]}, and two
 * suspensions of different terms, or of one term under frames whose slots are different records, can
 * stand for the same expression ({@code perf/loop-unfolded-once}: after one unfolding both sides are
 * syntactically identical officially; in the machine they are two closures).
 *
 * <p>{@code same(a, b)} walks the two values, and the terms of their closures under their frames, in
 * lockstep and answers {@code true} only if it has seen the two read back to the same expression:
 * <ul>
 *   <li>values: the same ref; literals of the same content; NEU / PROJ / NSUC of the same shape whose parts
 *       are the same; closures and suspensions (not LET) whose terms under their frames are the same;
 *   <li>terms under frames: the same node kind with the same parts; a variable bound in a frame is
 *       replaced by the value of its slot; a constant by its row and its level list under {@code ls};
 *       a sort by its level under {@code ls}; a variable bound by a binder that both sides crossed
 *       together must be the same index;
 *   <li>a term against a value: an application node against a NEU (the spine argument by argument), a
 *       binder node against a closure of the same kind, an atom against the atom's record.
 * </ul>
 * No judgment is called, no value is forced, nothing is reduced and no type is looked at: the answer
 * {@code true} states a fact about syntax, the one {@code is_equiv} states, up to the normalisations that
 * the value model applies everywhere (I-ID: beta and zeta of EVAL, canonical literals, PEEK). Everything
 * the walk does not understand, a HOLE that is read, a LET suspension, a pool literal, and the end of its
 * fuel or depth, is {@code false}: "not seen to be the same", after which the caller runs the complete
 * procedure. It is therefore not one of the congruence shortcuts of finding S-F1: it never asks whether
 * two different sub-terms are convertible.
 *
 * <p>Far frames (ISA 2.4) are walked: a variable beyond the 63 near slots is looked up in the REST chain, and
 * two far frames of one term are the same if their near slots and all their outer bindings are (measured: on
 * the Cedar slice half of the closures that are one expression stand under more than 63 bindings).
 *
 * <p>Cost: at most {@link #FUEL} nodes per query and {@link #DEPTH} host frames; no allocation, except that
 * a level or level list with parameters is instantiated through the caches of {@link Levels} (the same
 * call EVAL makes for that node).
 */
final class Same {
    private Same() {}

    /**
     * Nodes (value records, term nodes; eight identical slots of a run count as one) one query may visit. nano-lean
     * {@code same}: 512; gruszka {@code closure_congr}: 16384. Measured on Init (step 1 + lazyeval): 91 % of the walks
     * that see identity need fewer than 128 nodes, and the walks that run out of 256 were 70 % of all nodes visited
     * in vain; a pair of applications therefore gets {@link #FUEL_NEU}.
     */
    static final int FUEL = 256;
    static final int FUEL_NEU = 96;
    /** Host recursion bound of one query. */
    static final int DEPTH = 40;

    /**
     * True if {@code a} and {@code b} were seen to stand for the same expression (see the class comment); false is
     * "not seen", never "different".
     */
    static boolean same(Machine m, int a, int b) {
        return same(m, a, b, FUEL);
    }

    /** {@link #same(Machine, int, int)} under a fuel of {@code fuel} nodes; {@code m.x4.sameFuel} is what was left. */
    static boolean same(Machine m, int a, int b, int fuel) {
        Conv.Ext x = m.x4;
        x.sameFuel = fuel;
        boolean r = val(m, a, b, 0);
        x.sameNodes += fuel - x.sameFuel;
        return r;
    }

    private static boolean spend(Machine m, int depth) {
        return --m.x4.sameFuel >= 0 && depth <= DEPTH;
    }

    // ---------------------------------------------------------------------------------------------------
    // value against value
    // ---------------------------------------------------------------------------------------------------

    private static boolean val(Machine m, int a, int b, int depth) {
        if (a == b) {
            return true;
        }
        if (a <= Layout.HOLE || b <= Layout.HOLE) {
            return false; // an immediate is canonical; NONE and HOLE are not expressions
        }
        if (!spend(m, depth)) {
            return false;
        }
        int[] vh = m.vh;
        int oa = a << Layout.REC_SHIFT;
        int ob = b << Layout.REC_SHIFT;
        int wa = vh[oa];
        int wb = vh[ob];
        int ka = wa >>> Layout.KIND_SHIFT;
        int kb = wb >>> Layout.KIND_SHIFT;
        if (ka != kb) {
            // a suspension stands for its term under its frame, whatever the other side is
            if (ka == Layout.SUSP && (wa & Layout.W0_LET) == 0) {
                return termVal(m, vh[oa + Layout.A], vh[oa + Layout.B], vh[oa + Layout.C], 0, b, depth + 1);
            }
            if (kb == Layout.SUSP && (wb & Layout.W0_LET) == 0) {
                return termVal(m, vh[ob + Layout.A], vh[ob + Layout.B], vh[ob + Layout.C], 0, a, depth + 1);
            }
            return false;
        }
        switch (ka) {
            case Layout.NEU:
                return (wa & Layout.AUX_MASK) == (wb & Layout.AUX_MASK) && val(m, vh[oa + Layout.A], vh[ob + Layout.A], depth + 1)
                        && spines(m, vh[oa + Layout.B], vh[ob + Layout.B], wa & Layout.AUX_MASK, depth + 1);
            case Layout.PROJ:
                return vh[oa + Layout.B] == vh[ob + Layout.B] && vh[oa + Layout.C] == vh[ob + Layout.C]
                        && val(m, vh[oa + Layout.A], vh[ob + Layout.A], depth + 1);
            case Layout.NSUC:
                return vh[oa + Layout.B] == vh[ob + Layout.B] && val(m, vh[oa + Layout.A], vh[ob + Layout.A], depth + 1);
            case Layout.SUSP:
                if (((wa | wb) & Layout.W0_LET) != 0) {
                    return false; // a let variable of INFER is a variable, not its value (ISA 2.8)
                }
                return closures(m, oa, ob, depth);
            case Layout.LAM:
            case Layout.PI:
                return closures(m, oa, ob, depth);
            case Layout.PII:
                // "the type of this lambda" has no term of its own: only the same lambda under the same bindings
                return vh[oa + Layout.A] == vh[ob + Layout.A] && vh[oa + Layout.C] == vh[ob + Layout.C]
                        && frames(m, vh[oa + Layout.B], vh[ob + Layout.B], depth + 1);
            case Layout.NBIG:
            case Layout.STR:
                return Lits.sameContent(m, a, b);
            default:
                return false; // SORT, FVAR, CONST are interned by content: another ref is another expression
        }
    }

    /** Two closures or suspensions of one kind: the same term and ls under frames with the same slots, else the terms. */
    private static boolean closures(Machine m, int oa, int ob, int depth) {
        int[] vh = m.vh;
        int ta = vh[oa + Layout.A];
        int tb = vh[ob + Layout.A];
        int fa = vh[oa + Layout.B];
        int fb = vh[ob + Layout.B];
        int la = vh[oa + Layout.C];
        int lb = vh[ob + Layout.C];
        if (ta == tb && la == lb) {
            return frames(m, fa, fb, depth + 1);
        }
        return terms(m, ta, fa, la, 0, tb, fb, lb, 0, 0, depth + 1);
    }

    /**
     * Two frames of one term (the same mask, so the same length and HOLE at the same places): slot by slot. Far
     * frames (ISA 2.4): the near slots, then the REST chains chunk by chunk from the innermost; a REST run holds
     * every outer binding, read or not, so two far frames are seen as the same only if all of them are.
     */
    private static boolean frames(Machine m, int fa, int fb, int depth) {
        if (fa == fb) {
            return true;
        }
        int[] rs = m.rs;
        int ha = rs[fa];
        int hb = rs[fb];
        if (((ha ^ hb) & Layout.RUN_FAR) != 0 || (ha >>> Layout.RUN_LEN_SHIFT) != (hb >>> Layout.RUN_LEN_SHIFT)) {
            return false;
        }
        if (!spines(m, fa, fb, ha >>> Layout.RUN_LEN_SHIFT, depth)) {
            return false;
        }
        return (ha & Layout.RUN_FAR) == 0 || rests(m, m.rs[fa + Layout.RUN_REST_WORD], m.rs[fb + Layout.RUN_REST_WORD], depth);
    }

    /** Two REST chains (ids of their last chunks; a chunk is interned with its predecessor): chunk by chunk. */
    private static boolean rests(Machine m, int ra, int rb, int depth) {
        while (ra != rb) {
            if (ra == 0 || rb == 0) {
                return false;
            }
            int ha = m.rs[ra];
            int hb = m.rs[rb];
            int n = ha >>> Layout.RUN_LEN_SHIFT;
            if (n != (hb >>> Layout.RUN_LEN_SHIFT) || !spines(m, ra, rb, n, depth)) {
                return false;
            }
            ra = (ha & Layout.RUN_FAR) != 0 ? m.rs[ra + Layout.RUN_REST_WORD] : 0;
            rb = (hb & Layout.RUN_FAR) != 0 ? m.rs[rb + Layout.RUN_REST_WORD] : 0;
        }
        return true;
    }

    /**
     * The first {@code n} slots of two runs, pairwise (HOLE only against HOLE: {@link #val}). Identical slots (most
     * slots of two frames of one term are HOLE or the same record) cost one comparison each and one unit of fuel
     * per eight. The walk allocates no run, so the slab is not reallocated under it.
     */
    private static boolean spines(Machine m, int ra, int rb, int n, int depth) {
        final int[] rs = m.rs;
        int pa = ra + Layout.RUN_H + (rs[ra] & Layout.RUN_FAR);
        int pb = rb + Layout.RUN_H + (rs[rb] & Layout.RUN_FAR);
        m.x4.sameFuel -= n >>> 3;
        for (int i = 0; i < n; i++) {
            int x = rs[pa + i];
            int y = rs[pb + i];
            if (x != y && !val(m, x, y, depth)) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------------------
    // term against term
    // ---------------------------------------------------------------------------------------------------

    /**
     * The value of de Bruijn index {@code i} of a term that stands under {@code j} joint binders and {@code o}
     * binders of its own side that it does not read, in frame {@code f}: NONE if it is not a slot of the frame (a
     * binder variable, an index beyond the frame). An index beyond the 63 near slots of a far frame is looked up in
     * its REST chain, whose last chunk holds the innermost of the outer bindings (ISA 2.4, {@code Runs.enter}).
     */
    private static int slot(Machine m, int f, int i, int j, int o) {
        int k = i - j - o;
        if (k < 0 || f == Layout.RUN_EMPTY) {
            return Layout.NONE;
        }
        int[] rs = m.rs;
        int hdr = rs[f];
        int len = hdr >>> Layout.RUN_LEN_SHIFT;
        if (k < len) {
            return Runs.fget(m, f, k);
        }
        if ((hdr & Layout.RUN_FAR) == 0) {
            return Layout.NONE;
        }
        k -= len;
        for (int r = rs[f + Layout.RUN_REST_WORD]; r != 0;) {
            int h = rs[r];
            int n = h >>> Layout.RUN_LEN_SHIFT;
            if (k < n) {
                return rs[r + Layout.RUN_H + (h & Layout.RUN_FAR) + n - 1 - k];
            }
            k -= n;
            r = (h & Layout.RUN_FAR) != 0 ? rs[r + Layout.RUN_REST_WORD] : 0;
        }
        return Layout.NONE;
    }

    /**
     * {@code t1} under frame {@code f1} and substitution {@code l1} against {@code t2} under {@code f2}, {@code l2}.
     * {@code j} binders were crossed by both sides together (their variables are indices below j on both sides);
     * {@code o1} / {@code o2} are binders that only one side stands under and, by its mask, does not read.
     */
    private static boolean terms(Machine m, int t1, int f1, int l1, int o1, int t2, int f2, int l2, int o2, int j, int depth) {
        final Terms T = m.terms;
        for (;;) {
            if (!spend(m, depth)) {
                return false;
            }
            if (t1 == t2 && f1 == f2 && l1 == l2 && o1 == o2) {
                return true; // the same node under the same bindings
            }
            int k1 = T.kind(t1);
            int k2 = T.kind(t2);
            if (k1 == Kind.BVAR) {
                int i = T.a(t1);
                if (i < j) {
                    return k2 == Kind.BVAR && T.a(t2) == i; // a variable of a joint binder: the same binder
                }
                int v = slot(m, f1, i, j, o1);
                return v > Layout.HOLE || v < 0 ? termVal2(m, t2, f2, l2, o2, j, v, depth + 1) : false;
            }
            if (k2 == Kind.BVAR) {
                int i = T.a(t2);
                if (i < j) {
                    return false;
                }
                int v = slot(m, f2, i, j, o2);
                return v > Layout.HOLE || v < 0 ? termVal2(m, t1, f1, l1, o1, j, v, depth + 1) : false;
            }
            if (k1 != k2) {
                return false;
            }
            switch (k1) {
                case Kind.APP:
                    if (!terms(m, T.b(t1), f1, l1, o1, T.b(t2), f2, l2, o2, j, depth + 1)) {
                        return false;
                    }
                    t1 = T.a(t1); // the function parts: the loop (a spine of any length at one depth)
                    t2 = T.a(t2);
                    continue;
                case Kind.LAM:
                case Kind.PI:
                    if (!terms(m, T.a(t1), f1, l1, o1, T.a(t2), f2, l2, o2, j, depth + 1)) {
                        return false;
                    }
                    t1 = T.b(t1); // the bodies, under one more joint binder
                    t2 = T.b(t2);
                    j++;
                    continue;
                case Kind.LET:
                    if (!terms(m, T.a(t1), f1, l1, o1, T.a(t2), f2, l2, o2, j, depth + 1)
                            || !terms(m, T.b(t1), f1, l1, o1, T.b(t2), f2, l2, o2, j, depth + 1)) {
                        return false;
                    }
                    t1 = T.c(t1);
                    t2 = T.c(t2);
                    j++;
                    continue;
                case Kind.PROJ:
                    if (T.b(t1) != T.b(t2) || T.c(t1) != T.c(t2) || T.c(t1) <= 0) {
                        return false;
                    }
                    t1 = T.a(t1);
                    t2 = T.a(t2);
                    continue;
                default:
                    return leaves(m, t1, l1, t2, l2);
            }
        }
    }

    /** Two leaves of one kind (sort, constant, literal) under their substitutions. */
    private static boolean leaves(Machine m, int t1, int l1, int t2, int l2) {
        final Terms T = m.terms;
        switch (T.kind(t1)) {
            case Kind.SORT: {
                int a = level(m, t1, l1);
                return a < Op.SIG && a == level(m, t2, l2);
            }
            case Kind.CONST: {
                if (T.a(t1) != T.a(t2) || T.a(t1) <= 0) {
                    return false;
                }
                int a = levels(m, t1, l1);
                return a < Op.SIG && a == levels(m, t2, l2);
            }
            case Kind.NATLIT:
                // literal nodes are read, never materialised (KLIT can fail); a pool literal only as the same node
                return t1 == t2 || (!T.isNatBig(t1) && !T.isNatBig(t2) && T.natSmall(t1) == T.natSmall(t2));
            case Kind.STRLIT:
                return T.a(t1) == T.a(t2);
            default:
                return false; // FVAR nodes are the reference kernel's
        }
    }

    /** The level of a sort node under {@code ls}, as EVAL computes it (KSORT); a signal if {@code ls} is unusable. */
    private static int level(Machine m, int t, int ls) {
        final Terms T = m.terms;
        int l = T.a(t);
        if ((T.header(t) & Node.F_HAS_LPARAM) == 0) {
            return l;
        }
        return ls < 0 ? Op.SIG : Levels.lsub(m, l, ls);
    }

    /** The level list of a constant node under {@code ls}, as EVAL computes it (KCONST). */
    private static int levels(Machine m, int t, int ls) {
        final Terms T = m.terms;
        int ll = T.b(t);
        if ((T.header(t) & Node.F_HAS_LPARAM) == 0) {
            return ll;
        }
        return ls < 0 ? Op.SIG : Levels.lsubList(m, ll, ls);
    }

    // ---------------------------------------------------------------------------------------------------
    // term against value
    // ---------------------------------------------------------------------------------------------------

    /** A term that stands under no binder of the walk ({@code j = 0}, {@code o = 0}) against a value. */
    private static boolean termVal(Machine m, int t, int f, int ls, int o, int v, int depth) {
        return termVal2(m, t, f, ls, o, 0, v, depth);
    }

    /**
     * {@code t} under frame {@code f}, substitution {@code ls}, {@code j} joint binders and {@code o} binders of its
     * own, against the value {@code v}, which stands under no binder of the walk. A value cannot mention a binder
     * variable, so {@code t} must not read one (its mask); then its indices are slots of {@code f}.
     */
    private static boolean termVal2(Machine m, int t, int f, int ls, int o, int j, int v, int depth) {
        final Terms T = m.terms;
        if (!spend(m, depth)) {
            return false;
        }
        if (j + o > 0) {
            if (j + o >= Layout.NEAR_SLOTS || (T.fvMask(t) & ((1L << (j + o)) - 1)) != 0) {
                return false; // the term reads a binder of the walk: no value is that (mask bit 63 is an outer index)
            }
            o += j; // from here on they are binders the term stands under and does not read
        }
        int k = T.kind(t);
        if (k == Kind.BVAR) {
            int u = slot(m, f, T.a(t), 0, o);
            return (u > Layout.HOLE || u < 0) && val(m, u, v, depth + 1);
        }
        if (v <= Layout.HOLE) {
            // an immediate against a small literal node
            return v < 0 && k == Kind.NATLIT && !T.isNatBig(t) && T.natSmall(t) == (v & Layout.IMM_MASK);
        }
        int[] vh = m.vh;
        int ov = v << Layout.REC_SHIFT;
        int wv = vh[ov];
        int kv = wv >>> Layout.KIND_SHIFT;
        switch (k) {
            case Kind.SORT:
                return kv == Layout.SORT && vh[ov + Layout.A] == level(m, t, ls);
            case Kind.CONST:
                return kv == Layout.CONST && vh[ov + Layout.A] == T.a(t) && vh[ov + Layout.B] == levels(m, t, ls);
            case Kind.NATLIT:
                // a literal in [2^31, 2^63) is an NBIG record with the value inline; larger ones are not read here
                return kv == Layout.NBIG && (wv & Layout.AUX_MASK) == Layout.NBIG_INLINE && !T.isNatBig(t)
                        && T.natSmall(t) == ((vh[ov + Layout.A] & 0xFFFFFFFFL) | (long) vh[ov + Layout.B] << 32);
            case Kind.STRLIT:
                return kv == Layout.STR && vh[ov + Layout.A] == T.a(t);
            case Kind.APP:
                if (kv == Layout.NEU) {
                    return appNeu(m, t, f, ls, o, v, depth + 1);
                }
                break;
            case Kind.LAM:
                if (kv == Layout.LAM) {
                    return terms(m, t, f, ls, o, vh[ov + Layout.A], vh[ov + Layout.B], vh[ov + Layout.C], 0, 0, depth + 1);
                }
                return false;
            case Kind.PI:
                if (kv == Layout.PI) {
                    return terms(m, t, f, ls, o, vh[ov + Layout.A], vh[ov + Layout.B], vh[ov + Layout.C], 0, 0, depth + 1);
                }
                return false;
            case Kind.PROJ:
                if (kv == Layout.PROJ) {
                    return vh[ov + Layout.B] == T.c(t) && vh[ov + Layout.C] == T.b(t)
                            && termVal2(m, T.a(t), f, ls, o, 0, vh[ov + Layout.A], depth + 1);
                }
                break;
            default:
                break;
        }
        // an application, a projection or a let against a suspension: the two terms
        if (kv == Layout.SUSP && (wv & Layout.W0_LET) == 0) {
            return terms(m, t, f, ls, o, vh[ov + Layout.A], vh[ov + Layout.B], vh[ov + Layout.C], 0, 0, depth + 1);
        }
        return false;
    }

    /**
     * An application node against a NEU {@code head a0 .. a(n-1)}: the arguments from the last one down; what is
     * left of the term is the head, or a variable bound to a NEU that supplies the head and the first arguments.
     */
    private static boolean appNeu(Machine m, int t, int f, int ls, int o, int v, int depth) {
        final Terms T = m.terms;
        int ov = v << Layout.REC_SHIFT;
        int i = (m.vh[ov] & Layout.AUX_MASK) - 1;
        while (T.kind(t) == Kind.APP) {
            if (i < 0) {
                return false; // more arguments than the NEU has
            }
            if (!termVal2(m, T.b(t), f, ls, o, 0, Runs.arg(m, m.vh[ov + Layout.B], i), depth)) {
                return false;
            }
            i--;
            t = T.a(t);
        }
        if (i < 0) {
            return termVal2(m, t, f, ls, o, 0, m.vh[ov + Layout.A], depth); // the head
        }
        if (T.kind(t) != Kind.BVAR) {
            return false;
        }
        // i + 1 arguments are left: the variable must be bound to a NEU with this head and exactly these arguments
        int u = slot(m, f, T.a(t), 0, o);
        if (u <= Layout.HOLE) {
            return false;
        }
        int ou = u << Layout.REC_SHIFT;
        int wu = m.vh[ou];
        if ((wu >>> Layout.KIND_SHIFT) != Layout.NEU || (wu & Layout.AUX_MASK) != i + 1 || !spend(m, depth)) {
            return false;
        }
        return val(m, m.vh[ou + Layout.A], m.vh[ov + Layout.A], depth + 1)
                && spines(m, m.vh[ou + Layout.B], m.vh[ov + Layout.B], i + 1, depth + 1);
    }
}
