package rlvm.spike.stepper;

import static rlvm.spike.stepper.Terms.*;
import static rlvm.spike.stepper.Vals.*;

/**
 * The inline fast paths of the worklist conventions ("J.fast" of ISA 1.3): the answer of a judgment that needs
 * no sub-judgment, or {@link #MISS}. They perform exactly the operations plain recursion performs for the same
 * judgment.
 */
final class Fast {
    private Fast() {
    }

    /** Not a value id (a variable of level 2^31 - 1 never exists), not a term id, not a truth value. */
    static final int MISS = Integer.MIN_VALUE;

    /** EVAL of a variable, sort, constant, literal, lambda or pi. */
    static int eval(int t, int env) {
        switch (kind[t]) {
            case BVAR:
                return lookup(env, ea[t]);
            case SORT:
                return mk2(K_SORT, ea[t]);
            case CONST:
                return mk2(K_CONST, ea[t]);
            case LIT:
                return mk2(K_LIT, t);
            case LAM:
                return mk3(K_LAM, t, env);
            case PI:
                return mk3(K_PI, t, env);
            default:
                return MISS;
        }
    }

    /** {@link #eval} plus inert nests, which one loop over a scratch buffer evaluates without a frame. */
    static int evalInert(int t, int env) {
        switch (kind[t]) {
            case BVAR:
                return lookup(env, ea[t]);
            case SORT:
                return mk2(K_SORT, ea[t]);
            case CONST:
                return mk2(K_CONST, ea[t]);
            case LIT:
                return mk2(K_LIT, t);
            case LAM:
                return mk3(K_LAM, t, env);
            case PI:
                return mk3(K_PI, t, env);
            default:
                return (flag[t] & INERT) != 0 ? Inert.eval(t, env) : MISS;
        }
    }

    /** READBACK of a variable, sort, constant or literal. */
    static int rb(int v, int depth) {
        if (v < 0) {
            return mk(BVAR, depth + v, 0, 0);
        }
        switch (H[v] & 15) {
            case K_SORT:
                return mk(SORT, H[v + 1], 0, 0);
            case K_CONST:
                return mk(CONST, H[v + 1], 0, 0);
            case K_LIT:
                return H[v + 1];
            default:
                return MISS;
        }
    }

    /** CONV by identity or by an immediate mismatch; MISS for two records of the same kind APP, PROJ, LAM or PI. */
    static int conv(int a, int b) {
        if (a == b) {
            return 1;
        }
        if (a < 0 || b < 0) {
            return 0;
        }
        int h = H[a];
        if (h != H[b]) {
            return 0;
        }
        int k = h & 15;
        return k == K_APP || k == K_PROJ || k == K_LAM || k == K_PI ? MISS : 0;
    }
}
