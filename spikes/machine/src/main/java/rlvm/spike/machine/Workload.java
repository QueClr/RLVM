package rlvm.spike.machine;

import static rlvm.spike.machine.Corpus.*;

/**
 * Experiment 1 workload: every maximal application spine with a constant head, in expression-id (= file) order.
 * A spine root is an APP node referenced from at least one non-function position (argument, binder type/body,
 * let part, projection target, declaration type/value, recursor rule).
 *
 * Flat table W: [const index, k, arg_1 .. arg_k] with arguments already encoded as atom values -(expr + 1).
 */
public final class Workload {
    private Workload() {
    }

    public static int[] W;
    public static int wlen;
    public static int nSpines;
    public static long totalArgs;
    /** Static heat: number of spines per constant. */
    public static int[] heat;
    /** Telescope length (number of leading Pi binders of the constant's type). */
    public static int[] nparams;
    public static int maxParams;
    // statistics
    public static int spinesOther, overApplied, underApplied;
    public static long appNodes;

    public static void build() {
        int n = nExpr;
        boolean[] nonFn = new boolean[n];
        for (int e = 0; e < n; e++) {
            switch (kind[e]) {
                case APP -> {
                    nonFn[eb[e]] = true;
                    appNodes++;
                }
                case LAM, PI -> {
                    nonFn[ea[e]] = true;
                    nonFn[eb[e]] = true;
                }
                case LET -> {
                    nonFn[ea[e]] = true;
                    nonFn[eb[e]] = true;
                    nonFn[ec[e]] = true;
                }
                case PROJ -> nonFn[ec[e]] = true;
                default -> {
                }
            }
        }
        nparams = new int[nConst];
        for (int c = 0; c < nConst; c++) {
            nonFn[constType[c]] = true;
            if (constValue[c] >= 0) {
                nonFn[constValue[c]] = true;
            }
            int t = constType[c], np = 0;
            while (kind[t] == PI) {
                np++;
                t = eb[t];
            }
            nparams[c] = np;
            maxParams = Math.max(maxParams, np);
        }
        for (int r : extraRoots) {
            nonFn[r] = true;
        }
        heat = new int[nConst];
        int[] w = new int[1 << 20];
        int p = 0;
        for (int e = 0; e < n; e++) {
            if (kind[e] != APP || !nonFn[e]) {
                continue;
            }
            int k = 0, h = e;
            while (kind[h] == APP) {
                h = ea[h];
                k++;
            }
            int c = kind[h] == CONST ? constOfName[ea[h]] - 1 : -1;
            if (c < 0) {
                spinesOther++;
                continue;
            }
            if (p + 2 + k > w.length) {
                w = java.util.Arrays.copyOf(w, w.length * 2);
            }
            w[p] = c;
            w[p + 1] = k;
            h = e;
            for (int i = k - 1; i >= 0; i--) {
                w[p + 2 + i] = -(eb[h] + 1);
                h = ea[h];
            }
            p += 2 + k;
            heat[c]++;
            nSpines++;
            totalArgs += k;
            if (k > nparams[c]) {
                overApplied++;
            } else if (k < nparams[c]) {
                underApplied++;
            }
        }
        W = w;
        wlen = p;
        int minHeat = Integer.getInteger("rlvm.minheat", 0);
        int limit = Integer.getInteger("rlvm.limit", Integer.MAX_VALUE);
        if (minHeat > 0 || limit < nSpines) {
            filter(minHeat, limit);
        }
    }

    /** Keeps only the spines of constants with static heat >= minHeat, and at most limit spines. */
    private static void filter(int minHeat, int limit) {
        int[] w = W;
        int q = 0, kept = 0;
        long args = 0;
        int[] h2 = new int[nConst];
        for (int p = 0; p < wlen && kept < limit;) {
            int c = w[p], k = w[p + 1];
            if (heat[c] >= minHeat) {
                System.arraycopy(w, p, w, q, 2 + k);
                q += 2 + k;
                kept++;
                args += k;
                h2[c]++;
            }
            p += 2 + k;
        }
        wlen = q;
        nSpines = kept;
        totalArgs = args;
        heat = h2;
    }
}
