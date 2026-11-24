package rlvm.spike.machine;

import static rlvm.spike.machine.Corpus.*;

/** Printers for diagnosing a verification mismatch (never on a measured path). */
public final class Debug {
    private Debug() {
    }

    public static String expr(int e, int depth) {
        if (depth > 12) {
            return "..";
        }
        switch (kind[e]) {
            case BVAR:
                return "#" + ea[e];
            case SORT:
                return "Sort" + ea[e];
            case CONST:
                return constOfName[ea[e]] > 0 ? constStr[constOfName[ea[e]] - 1] : "c" + ea[e];
            case APP:
                return "(" + expr(ea[e], depth + 1) + " " + expr(eb[e], depth + 1) + ")";
            case LAM:
                return "(fun " + expr(ea[e], depth + 1) + " => " + expr(eb[e], depth + 1) + ")";
            case PI:
                return "(Pi " + expr(ea[e], depth + 1) + ", " + expr(eb[e], depth + 1) + ")";
            case LET:
                return "(let " + expr(eb[e], depth + 1) + "; " + expr(ec[e], depth + 1) + ")";
            case LIT:
                return "lit" + e;
            case PROJ:
                return expr(ec[e], depth + 1) + "." + eb[e];
            default:
                return "?";
        }
    }

    public static String val(int v, int depth) {
        if (v < 0) {
            return "@" + (-v - 1);
        }
        if (v == 0) {
            return "NONE";
        }
        if (depth > 12) {
            return "..";
        }
        int hdr = Mem.h(v);
        switch (hdr & 15) {
            case Vals.K_APP:
                return "(" + val(Mem.h(v + 1), depth + 1) + " " + val(Mem.h(v + 2), depth + 1) + ")";
            case Vals.K_PROJ:
                return val(Mem.h(v + 1), depth + 1) + "." + (hdr >>> 4);
            case Vals.K_SORT:
                return "Sort" + Mem.h(v + 1);
            case Vals.K_CONST:
                return constOfName[Mem.h(v + 1)] > 0 ? constStr[constOfName[Mem.h(v + 1)] - 1] : "c" + Mem.h(v + 1);
            case Vals.K_LIT:
                return "lit" + Mem.h(v + 1);
            case Vals.K_LAM, Vals.K_PI: {
                StringBuilder sb = new StringBuilder((hdr & 15) == Vals.K_LAM ? "LAM{e" : "PI{e").append(Mem.h(v + 1)).append(' ').append(expr(Mem.h(v + 1), 8));
                for (int i = 0; i < hdr >>> 4; i++) {
                    sb.append(" | ").append(val(Mem.h(v + 2 + i), depth + 1));
                }
                return sb.append('}').toString();
            }
            default:
                return "?kind" + (hdr & 15);
        }
    }

    /** Describes the spine that produced output number q. */
    public static void spineOfOutput(int q, int[] ref, int[] out) {
        int[] W = Workload.W;
        int o = 0;
        for (int p = 0; p < Workload.wlen;) {
            int c = W[p], k = W[p + 1];
            int n = Workload.nparams[c];
            int j = Math.min(k, n);
            if (q < o + j + 1) {
                System.out.println("  constant " + constStr[c] + " params=" + n + " args=" + k + " output#" + (q - o) + " of " + (j + 1));
                System.out.println("  type: " + expr(constType[c], 0));
                StringBuilder sb = new StringBuilder("  args:");
                for (int i = 0; i < k; i++) {
                    sb.append(' ').append(val(W[p + 2 + i], 0));
                }
                System.out.println(sb);
                System.out.println("  reference: " + val(ref[q], 0));
                System.out.println("  variant:   " + val(out[q], 0));
                return;
            }
            o += j + 1;
            p += 2 + k;
        }
    }
}
