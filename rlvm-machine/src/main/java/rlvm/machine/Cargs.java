package rlvm.machine;

import static rlvm.machine.Layout.F;
import static rlvm.machine.Layout.T;
import static rlvm.machine.Op.SIG;

/**
 * Spine comparison and plan execution (ISA 6.1). Owner: M4.
 *
 * <p>v0 (ISA 6.3 rule 3): every position is SAME and the pairs are compared in the official order, each by
 * the complete {@code CONV}: last to first for policy SAMEDEF ({@code is_def_eq_args}, R02 8.4), first to
 * last for policy APP ({@code is_def_eq_app}, R02 8.6). Pass A is then only the identity test of each
 * pair, which is the first line of {@code CONV} and is made inline. The quick refutation of pass A, the
 * comparison plans (step 2, {@code Op.SW_FACTS}) and pass B (step 5, {@code Op.SW_PROBES}) reorder or
 * skip comparisons and are not part of the complete procedure: a kernel error raised by a pair that the
 * official order compares first must stay a verdict (ISA 6.1). Of these, {@code Op.SW_PROBES} alone runs the
 * quick refutation and pass B (they only reorder comparisons of the complete CONV).
 *
 * <p>NOT THE OFFICIAL VERDICT (finding S-F2 of the soundness review, 2026-11-24; NOTES-M4 D27): the plan of
 * the head constant, a 64-bit mask of positions that are not compared, computed from its fact record (ISA
 * 3.5) when the comparison starts: UNREAD positions under SAMEDEF (the value's body does not read the
 * parameter; B4) and proof positions under the agreement rule (B3). The official {@code is_def_eq_args}
 * compares every position, and its comparison of two proofs (of their types) or of two unread arguments
 * followed by the unfolding can fail where the skip answers T. The plan therefore runs only with
 * {@code Op.SW_FACTS} AND the non-official option {@code skipargs} ({@code Conv.skipArgs}); no switch set
 * reaches it. Positions from 64 on, positions beyond the syntactic telescope and every position of a head
 * without facts are compared.
 *
 * <p>Frames: {@code CARGS[0](a, b, policy)}; {@code CARGS[1](a, b, k, policy, skip lo, skip hi)}: pass C,
 * k pairs (counted in the policy's order) are done and pair k is being compared; {@code CARGS[2](a, b, k,
 * skip lo, skip hi)}: pass B is probing pair k; {@code CARGS[3](a, b, policy)}: the fact record of the head
 * is being derived (SIGD).
 */
final class Cargs {
    private Cargs() {}

    static final int K_CARGS_1 = Op.K_CARGS | 1;
    /** Step 5: pass B is probing pair k. */
    static final int K_CARGS_2 = Op.K_CARGS | 2;
    /** Step 2: SIGD of the head constant. */
    static final int K_CARGS_3 = Op.K_CARGS | 3;
    /** Size class of the pass-B budget in UNDEC (log2 of 256). */
    private static final int LOG_PAIR = 8;

    /**
     * CARGS(a, b, policy) (ISA 6.1): a, b NEU with the same length and heads that agree; policy {@code Op.POL_APP} or
     * {@code Op.POL_SAMEDEF}. v0: every position SAME, pass A (identical pairs, quick refutation) and pass C in the
     * official order (last to first for SAMEDEF, first to last for APP). Plans (step 2) and pass B (step 5) behind
     * switches. Official: {@code is_def_eq_args} (R02 8.4), {@code is_def_eq_app} (8.6). Entry frame: CARGS[0](a, b, policy).
     *
     * <p>Spines of different length: under APP the answer is F before any argument is compared (the official
     * arity test of {@code is_def_eq_app}); under SAMEDEF the official {@code is_def_eq_args} walks both spines from
     * the last argument, compares the pairs it meets and answers F at the end, and so does this judgment (a kernel
     * error raised by one of those comparisons is a verdict).
     * @return T, F, or a signal
     */
    static int cargs(Machine m, int a, int b, int policy, int d) {
        int na = Values.aux(m, a);
        int nb = Values.aux(m, b);
        if (na != nb && policy == Op.POL_APP) {
            return F;
        }
        int n = Math.min(na, nb);
        int k = skipSame(m, a, b, na, nb, n, policy);
        if (k == n) {
            return na == nb ? T : F; // leaf: every pair is identical (pass A leaves nothing)
        }
        int sw = m.sw;
        if ((sw & Op.SW_PROBES) != 0 && (na != nb || refuted(m, a, b, n, k, policy))) {
            return F; // step 5: the quick refutation of pass A (inline, no frame)
        }
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig;
        }
        if (m.stats) {
            m.call(Op.CARGS);
        }
        if (d > m.hostDepth) {
            return m.spill3(Op.K_CARGS, a, b, policy);
        }
        if ((sw & Op.SW_FACTS) != 0 && na == nb && Conv.skipArgs(m)) {
            return plan(m, a, b, policy, d); // non-official option (finding S-F2): positions skipped by the fact record
        }
        return run(m, a, b, policy, 0L, d);
    }

    /** Number of leading pairs (in the policy's order) that are identical refs. */
    private static int skipSame(Machine m, int a, int b, int na, int nb, int n, int policy) {
        int[] rs = m.rs;
        int[] vh = m.vh;
        int ra = vh[(a << Layout.REC_SHIFT) + Layout.B];
        int rb = vh[(b << Layout.REC_SHIFT) + Layout.B];
        ra += Layout.RUN_H + (rs[ra] & Layout.RUN_FAR);
        rb += Layout.RUN_H + (rs[rb] & Layout.RUN_FAR);
        int k = 0;
        if (policy == Op.POL_APP) {
            while (k < n && rs[ra + k] == rs[rb + k]) {
                k++;
            }
        } else {
            while (k < n && rs[ra + na - 1 - k] == rs[rb + nb - 1 - k]) {
                k++;
            }
        }
        return k;
    }

    /** Pass B if step 5 is on and the policy is SAMEDEF (equal lengths then), else pass C. */
    private static int run(Machine m, int a, int b, int policy, long skip, int d) {
        if (policy == Op.POL_SAMEDEF && Conv.probing(m)) {
            return passB(m, a, b, 0, skip, d);
        }
        return cargsFrom(m, a, b, 0, policy, skip, d);
    }

    /**
     * Pass C from pair {@code k} on: the remaining pairs that the plan does not skip, in the official order, each by
     * the complete CONV with the caller's fuel; the first F ends the comparison.
     */
    static int cargsFrom(Machine m, int a, int b, int k, int policy, long skip, int d) {
        int na = Values.aux(m, a);
        int nb = Values.aux(m, b);
        int n = Math.min(na, nb);
        for (; k < n; k++) {
            int ia = policy == Op.POL_APP ? k : na - 1 - k;
            if (ia < Layout.FACT_POSITIONS && (skip >>> ia & 1L) != 0) {
                continue; // an unread or proof position of the plan (skip != 0 only for equal lengths)
            }
            // the run slab may be reallocated by a comparison: Runs.arg reads m.rs each time
            int x = Runs.arg(m, Values.b(m, a), ia);
            int y = Runs.arg(m, Values.b(m, b), policy == Op.POL_APP ? k : nb - 1 - k);
            if (x == y) {
                continue;
            }
            int r = Conv.conv(m, x, y, d + 1);
            if (r >= SIG) {
                return m.sig6(r, K_CARGS_1, a, b, k, policy, (int) skip, (int) (skip >>> 32));
            }
            if (r != T) {
                return F;
            }
        }
        if (na != nb) {
            return F;
        }
        if (skip != 0 && (m.debug & Op.DBG_SHORTCHECK) != 0 && Debug.shortCheck(m, a, b, T) != 0) {
            return Op.FATAL; // a T that skipped positions is re-derived by the complete procedure (ISA 5.5)
        }
        return T;
    }

    /** CARGS[1](a, b, k, policy, skip lo, skip hi); r = CONV(pair k). */
    static int k_cargs1(Machine m, int a, int b, int k, int policy, int lo, int hi, int r, int d) {
        return r != T ? F : cargsFrom(m, a, b, k + 1, policy, (lo & 0xFFFFFFFFL) | (long) hi << 32, d);
    }

    // ---------------------------------------------------------------------------------------------------
    // Step 2 (Op.SW_FACTS): the comparison plan of the head constant (ISA 6.1, 3.5; shortcuts B3, B4)
    // ---------------------------------------------------------------------------------------------------

    /**
     * The plan needs the fact record of the head constant (both heads: the same row); a record that does not exist
     * yet is derived now (SIGD, a judgment; once per constant and engine). No constant head: every position SAME.
     */
    private static int plan(Machine m, int a, int b, int policy, int d) {
        int ha = Values.a(m, a);
        int hb = Values.a(m, b);
        if (Values.kind(m, ha) != Layout.CONST || Values.kind(m, hb) != Layout.CONST || Values.a(m, ha) != Values.a(m, hb)) {
            return run(m, a, b, policy, 0L, d);
        }
        int row = Values.a(m, ha);
        int index = Facts.facts(m, row);
        if (index == 0) {
            index = Facts.sigd(m, row, d + 1);
            return index >= SIG ? m.sig3(index, K_CARGS_3, a, b, policy) : k_cargs3(m, a, b, policy, index, d);
        }
        return k_cargs3(m, a, b, policy, index, d);
    }

    /** CARGS[3](a, b, policy); r = the fact record of the head (0 = none). */
    static int k_cargs3(Machine m, int a, int b, int policy, int index, int d) {
        return run(m, a, b, policy, Facts.usable(m, index) ? skipMask(m, a, b, policy, index) : 0L, d);
    }

    /**
     * Pass A by class (ISA 6.1), as the mask of positions that are not compared:
     * <ul>
     *   <li>UNREAD (policy SAMEDEF, the spine covers lamArity): skipped; if the two arguments are not identical the
     *       position enters {@code differ}: it was skipped but may really differ (B4; S1 / S4 of R05 section 4);
     *   <li>a proof position (the sort of its domain is manifest and zero at the head's levels, {@code Facts.propMask})
     *       whose domain reads no position of {@code differ}: skipped, the agreement rule (B3; S3): when CARGS answers
     *       T every position outside {@code differ} agrees (identical, compared equal, or such a proof position, by
     *       induction from left to right over the direct dependency mask), so the two arguments are proofs of
     *       convertible propositions; a proof position that reads a differing position is compared like data;
     *   <li>everything else, positions from 64 on and positions beyond the syntactic telescope: compared.
     * </ul>
     */
    static long skipMask(Machine m, int a, int b, int policy, int index) {
        int n = Values.aux(m, a);
        int head = Values.a(m, a);
        long prop = Facts.propMask(m, head);
        long unread = policy == Op.POL_SAMEDEF ? Facts.unreadMask(m, Values.a(m, head), n) : 0L;
        if ((prop | unread) == 0) {
            return 0L;
        }
        int np = Math.min(Math.min(n, Facts.arity(m, index)), Layout.FACT_POSITIONS);
        long skip = 0L;
        long differ = 0L;
        for (int i = 0; i < np; i++) {
            long bit = 1L << i;
            if ((unread & bit) != 0) {
                skip |= bit;
                if (Runs.arg(m, Values.b(m, a), i) != Runs.arg(m, Values.b(m, b), i)) {
                    differ |= bit;
                }
            } else if ((prop & bit) != 0 && (Facts.dep(m, index, i) & differ) == 0) {
                skip |= bit;
            }
        }
        if (m.stats && skip != 0) {
            m.probe((skip & unread) != 0 ? Op.B4 : Op.B3, T);
        }
        return skip;
    }

    // ---------------------------------------------------------------------------------------------------
    // Step 5 (Op.SW_PROBES): the quick refutation of pass A and pass B (ISA 6.1). Both answer F for a pair
    // before the pairs that the official order compares first were compared, so a kernel error that one of
    // those would raise is not met: they are not part of the complete procedure.
    // ---------------------------------------------------------------------------------------------------

    /**
     * Pass A's quick refutation over the hard pairs from {@code k} on (equal lengths). It runs before the plan is
     * known: refuting on a position the plan would skip only loses the shortcut (an F of CARGS is never unsound).
     */
    private static boolean refuted(Machine m, int a, int b, int n, int k, int policy) {
        for (; k < n; k++) {
            int x = Runs.arg(m, Values.b(m, a), policy == Op.POL_APP ? k : n - 1 - k);
            int y = Runs.arg(m, Values.b(m, b), policy == Op.POL_APP ? k : n - 1 - k);
            if (x != y && Conv.refutes(m, x, y)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Pass B (policy SAMEDEF only; ISA 6.1, budget 6.2): each hard pair under TRY(256), in the official order (last
     * to first). F: ret F. T: done (CONV recorded POS, so pass C answers the pair from the table). U: left for pass C
     * and noted in UNDEC, which only suppresses another probe of this size. Then pass C with the caller's fuel.
     */
    private static int passB(Machine m, int a, int b, int k, long skip, int d) {
        int n = Values.aux(m, a);
        for (; k < n; k++) {
            int i = n - 1 - k;
            if (i < Layout.FACT_POSITIONS && (skip >>> i & 1L) != 0) {
                continue;
            }
            int x = Runs.arg(m, Values.b(m, a), i);
            int y = Runs.arg(m, Values.b(m, b), i);
            if (x == y || Pairs.undec(m, x, y) >= LOG_PAIR) {
                continue;
            }
            int r = probePair(m, x, y, d);
            if (r >= SIG) {
                return m.sig5(r, K_CARGS_2, a, b, k, (int) skip, (int) (skip >>> 32));
            }
            Conv.settle(m, r, Op.B_PAIR);
            if (r == F) {
                return F;
            }
            if (r != T) {
                Pairs.setUndec(m, x, y, LOG_PAIR);
                if (!Conv.probing(m)) {
                    break; // the declaration's probe debt is exhausted: pass C at once
                }
            }
        }
        return cargsFrom(m, a, b, 0, Op.POL_SAMEDEF, skip, d);
    }

    /** {@code TRY(256) call CONV(x, y)}: T, F or U (the template of Frames). */
    private static int probePair(Machine m, int x, int y, int d) {
        int ssp = m.ssp;
        int xp = m.xp;
        int ep = m.ep;
        int rp = m.rp;
        int er = m.ER;
        long saved = m.tryEnter(Op.B_PAIR);
        int r = Conv.conv(m, x, y, d + 1);
        return r == Op.SUSP ? m.saveTry(saved, Op.B_PAIR, ssp, xp, ep, rp, er)
                : Frames.k_try(m, saved, Op.B_PAIR, ssp, xp, ep, rp, er, r);
    }

    /** CARGS[2](a, b, k, skip lo, skip hi); r = the outcome of the probe of pair k. */
    static int k_cargs2(Machine m, int a, int b, int k, int lo, int hi, int r, int d) {
        Conv.settle(m, r, Op.B_PAIR);
        if (r == F) {
            return F;
        }
        if (r != T) {
            int i = Values.aux(m, a) - 1 - k;
            Pairs.setUndec(m, Runs.arg(m, Values.b(m, a), i), Runs.arg(m, Values.b(m, b), i), LOG_PAIR);
            if (!Conv.probing(m)) {
                return cargsFrom(m, a, b, 0, Op.POL_SAMEDEF, (lo & 0xFFFFFFFFL) | (long) hi << 32, d);
            }
        }
        return passB(m, a, b, k + 1, (lo & 0xFFFFFFFFL) | (long) hi << 32, d);
    }
}
