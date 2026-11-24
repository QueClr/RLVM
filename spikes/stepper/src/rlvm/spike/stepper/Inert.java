package rlvm.spike.stepper;

import static rlvm.spike.stepper.Terms.*;
import static rlvm.spike.stepper.Vals.*;

/**
 * Evaluation of an inert nest (a term whose {@link Terms#INERT} flag is set) by one loop over a scratch
 * buffer: no frame, no dispatch on a frame header, the environment is constant. The records are built in the
 * order of the recursive evaluator (function, argument, application).
 *
 * Scratch entries are pairs (payload, tag): an argument term still to be evaluated, a function value waiting
 * for its argument, or the header of a projection waiting for its structure.
 */
public final class Inert {
    private Inert() {
    }

    private static final int T_ARG = 0, T_FN = 1, T_PROJ = 2;

    static int[] X = new int[1 << 12];
    /** Inert nests evaluated and term nodes visited by them (STATS only). */
    public static long nests, nodes;

    public static int eval(int e, int env) {
        int[] x = X;
        int sp = 0;
        int v;
        if (STATS) {
            nests++;
        }
        for (;;) {
            // evaluate e: walk down the function / struct positions, remembering what is still to be done
            down: for (;;) {
                if (STATS) {
                    nodes++;
                }
                switch (kind[e]) {
                    case APP:
                        if (sp + 2 > x.length) {
                            x = X = java.util.Arrays.copyOf(x, x.length * 2);
                        }
                        x[sp] = eb[e];
                        x[sp + 1] = T_ARG;
                        sp += 2;
                        e = ea[e];
                        continue;
                    case PROJ:
                        if (sp + 2 > x.length) {
                            x = X = java.util.Arrays.copyOf(x, x.length * 2);
                        }
                        x[sp] = K_PROJ | eb[e] << 4;
                        x[sp + 1] = T_PROJ;
                        sp += 2;
                        e = ec[e];
                        continue;
                    case BVAR:
                        v = lookup(env, ea[e]);
                        break down;
                    case SORT:
                        v = mk2(K_SORT, ea[e]);
                        break down;
                    case CONST:
                        v = mk2(K_CONST, ea[e]);
                        break down;
                    case LIT:
                        v = mk2(K_LIT, e);
                        break down;
                    case LAM:
                        v = mk3(K_LAM, e, env);
                        break down;
                    case PI:
                        v = mk3(K_PI, e, env);
                        break down;
                    default:
                        throw new IllegalStateException();
                }
            }
            // return v to the pending items
            for (;;) {
                if (sp == 0) {
                    return v;
                }
                sp -= 2;
                int tag = x[sp + 1];
                if (tag == T_FN) {
                    v = mk3(K_APP, x[sp], v);
                } else if (tag == T_ARG) {
                    e = x[sp];
                    x[sp] = v;
                    x[sp + 1] = T_FN;
                    sp += 2;
                    break;
                } else {
                    v = mk2(x[sp], v);
                }
            }
        }
    }
}
