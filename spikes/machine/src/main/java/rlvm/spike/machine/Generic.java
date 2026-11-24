package rlvm.spike.machine;

import static rlvm.spike.machine.Corpus.*;

/**
 * Variant (a): generic interpretation. Walks the term nodes of a constant's type with an environment, as an
 * NbE evaluator does. Environment = S[base .. base+d), de Bruijn index i lives at S[base + d - 1 - i].
 * Constants are opaque, so the only reductions are beta (a closure in head position) and zeta.
 *
 * It is also the complete fallback procedure of the machine: the elaborator emits a GEN instruction for
 * anything it does not want to compile, and uses {@link #closed} to pre-intern closed sub-terms.
 */
public final class Generic {
    private Generic() {
    }

    /** Closed-term memo on/off (variant a1 / a0). */
    public static boolean MEMO;
    /** Per expression: value of the closed term (0 = not yet evaluated). */
    public static int[] memo;
    public static long betas, lets;

    public static void init() {
        memo = new int[nExpr];
    }

    /** Telescope instantiation: domains to S[fp+n+i], result type to S[fp+2n]. */
    public static void instantiate(int c, int[] S, int fp, int k) {
        int n = Workload.nparams[c];
        int e = constType[c];
        int j = k < n ? k : n;
        int sp = fp + 2 * n + 1;
        for (int i = 0; i < j; i++) {
            S[fp + n + i] = eval(ea[e], S, fp, i, sp);
            e = eb[e];
        }
        S[fp + 2 * n] = eval(e, S, fp, j, sp);
    }

    public static int eval(int e, int[] S, int base, int d, int sp) {
        byte kd = kind[e];
        if (kd == BVAR) {
            return S[base + d - 1 - ea[e]];
        }
        if (MEMO && loose[e] == 0) {
            int m = memo[e];
            if (m == 0) {
                m = evalNode(kd, e, S, base, d, sp);
                memo[e] = m;
            }
            return m;
        }
        return evalNode(kd, e, S, base, d, sp);
    }

    /** Value of a closed term, memoised regardless of {@link #MEMO} (used at elaboration time). */
    public static int closed(int e) {
        int m = memo[e];
        if (m == 0) {
            boolean old = MEMO;
            MEMO = true;
            int sp = Machine.S.length / 2;
            m = evalNode(kind[e], e, Machine.S, sp, 0, sp);
            memo[e] = m;
            MEMO = old;
        }
        return m;
    }

    private static int evalNode(byte kd, int e, int[] S, int base, int d, int sp) {
        switch (kd) {
            case SORT:
                return Vals.mk2i(Vals.K_SORT, ea[e]);
            case CONST:
                return Vals.mk2i(Vals.K_CONST, ea[e]);
            case LIT:
                return Vals.mk2i(Vals.K_LIT, e);
            case APP: {
                // peel the spine down to the head (or to a closed prefix when the memo is on)
                int h = e, n = 0;
                do {
                    S[sp + n++] = eb[h];
                    h = ea[h];
                } while (kind[h] == APP && !(MEMO && loose[h] == 0));
                int f = eval(h, S, base, d, sp + n);
                boolean mayBeLam = kind[h] != CONST;
                for (int i = n - 1; i >= 0; i--) {
                    int x = eval(S[sp + i], S, base, d, sp + n);
                    if (mayBeLam && f > 0 && Vals.kindOf(f) == Vals.K_LAM) {
                        f = applyLam(f, x, S, sp + n);
                    } else {
                        f = Vals.mkApp(f, x);
                        mayBeLam = false;
                    }
                }
                return f;
            }
            case LAM:
                return closure(Vals.K_LAM, e, S, base, d, sp);
            case PI:
                return closure(Vals.K_PI, e, S, base, d, sp);
            case LET: {
                lets++;
                int v = eval(eb[e], S, base, d, sp);
                System.arraycopy(S, base, S, sp, d);
                S[sp + d] = v;
                return eval(ec[e], S, sp, d + 1, sp + d + 1);
            }
            case PROJ:
                return Vals.mk2i(Vals.K_PROJ | eb[e] << 4, eval(ec[e], S, base, d, sp));
            default:
                throw new IllegalStateException();
        }
    }

    /** Closure trimmed to the free variables of e (ascending de Bruijn index), see Corpus.mask. */
    private static int closure(int k, int e, int[] S, int base, int d, int sp) {
        long m = mask[e];
        int n = 0;
        long lo = m & ~FAR;
        while (lo != 0) {
            int j = Long.numberOfTrailingZeros(lo);
            if (j >= d) {
                break; // only the conservative bit 62 can be out of range
            }
            lo &= lo - 1;
            S[sp + n++] = S[base + d - 1 - j];
        }
        if (m < 0) {
            for (int j = 63; j < d; j++) {
                S[sp + n++] = S[base + d - 1 - j];
            }
        }
        return Vals.mkN(k | n << 4, e, S, sp, n);
    }

    /** Beta: rebuild a dense environment for the body from the trimmed captures. */
    public static int applyLam(int f, int x, int[] S, int sp) {
        betas++;
        int ncap = Mem.h(f) >>> 4;
        int e = Mem.h(f + 1);
        int body = eb[e];
        int m = loose[body];
        if (m > 0) {
            S[sp + m - 1] = x;
        }
        long lo = mask[e] & ~FAR;
        int ci = 0;
        while (lo != 0 && ci < ncap) {
            int j = Long.numberOfTrailingZeros(lo);
            lo &= lo - 1;
            int pos = sp + m - 2 - j; // body index j + 1
            int v = Mem.h(f + 2 + ci++);
            if (pos >= sp) {
                S[pos] = v;
            }
        }
        for (int j = 63; ci < ncap; j++) {
            int pos = sp + m - 2 - j;
            int v = Mem.h(f + 2 + ci++);
            if (pos >= sp) {
                S[pos] = v;
            }
        }
        return eval(body, S, sp, m, sp + m);
    }
}
