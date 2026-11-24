package rlvm.spike.machine;

import java.util.Arrays;
import java.util.Locale;

/** Experiment 1 driver: telescope instantiation for every constant-headed application spine of the corpus. */
public final class Exp1 {
    private Exp1() {
    }

    public static final int FP = 64;

    /** The measured loop. Returns a checksum over all outputs (ids, so comparable only within one arena history). */
    public static long drive(Instantiator v) {
        final int[] W = Workload.W;
        final int wlen = Workload.wlen;
        final int[] S = Machine.S;
        final int[] np = Workload.nparams;
        long cs = 0;
        for (int p = 0; p < wlen;) {
            int c = W[p], k = W[p + 1];
            p += 2;
            for (int i = 0; i < k; i++) {
                S[FP + i] = W[p + i];
            }
            p += k;
            v.inst(c, S, FP, k);
            int n = np[c];
            int j = k < n ? k : n;
            for (int i = 0; i < j; i++) {
                cs = cs * 31 + S[FP + n + i];
            }
            cs = cs * 31 + S[FP + 2 * n];
        }
        return cs;
    }

    /** Unmeasured: all outputs of a variant, in order. */
    public static int[] record(Instantiator v) {
        final int[] W = Workload.W;
        final int[] S = Machine.S;
        int[] out = new int[(int) (Workload.totalArgs + Workload.nSpines)];
        int o = 0;
        for (int p = 0; p < Workload.wlen;) {
            int c = W[p], k = W[p + 1];
            p += 2;
            for (int i = 0; i < k; i++) {
                S[FP + i] = W[p + i];
            }
            p += k;
            v.inst(c, S, FP, k);
            int n = Workload.nparams[c];
            int j = k < n ? k : n;
            for (int i = 0; i < j; i++) {
                out[o++] = S[FP + n + i];
            }
            out[o++] = S[FP + 2 * n];
        }
        return Arrays.copyOf(out, o);
    }

    /**
     * Match mode: the domains are not built; each one is matched against the value in ref (the argument's type in a
     * real kernel; here the domain value a previous build pass produced). Only the result type is built.
     */
    public static long driveMatch(int[] ref) {
        final int[] W = Workload.W;
        final int wlen = Workload.wlen;
        final int[] S = Machine.S;
        final int[] np = Workload.nparams;
        final int[] pat = Elab.PAT;
        long cs = 0;
        int o = 0;
        matchFailures = 0;
        for (int p = 0; p < wlen;) {
            int c = W[p], k = W[p + 1];
            p += 2;
            for (int i = 0; i < k; i++) {
                S[FP + i] = W[p + i];
            }
            p += k;
            int n = np[c];
            int j = k < n ? k : n;
            int co = Elab.codeStart[c];
            int tbl = Elab.patStart[c];
            int sp = FP + Elab.CODE[co + 1];
            for (int i = 0; i < j; i++) {
                int pp = pat[tbl + i];
                if (pat[pp] == Elab.P_EQ) { // domain is a parameter or a closed term: one comparison
                    int op = pat[pp + 1];
                    if (ref[o + i] != (op > 0 ? op : S[FP - op])) {
                        matchFailures++;
                    }
                } else if (Stepper.match(pat, pp, ref[o + i], S, FP, sp) < 0) {
                    matchFailures++;
                }
            }
            Stepper.runResult(co, S, FP, k);
            cs = cs * 31 + S[FP + 2 * n];
            if (S[FP + 2 * n] != ref[o + j]) {
                matchFailures++;
            }
            o += j + 1;
        }
        return cs;
    }

    public static long matchFailures;

    public static void runMatch(String variant, boolean matchPass, int passes) {
        Elab.PATTERNS = true;
        Instantiator b = new Instantiator.StepperInst();
        long t0 = System.nanoTime();
        int[] ref = record(b);
        long recMs = (System.nanoTime() - t0) / 1_000_000;
        long[] ms = new long[matchPass ? passes : 0];
        long cs = 0;
        for (int i = 0; i < ms.length; i++) {
            t0 = System.nanoTime();
            cs = driveMatch(ref);
            ms[i] = (System.nanoTime() - t0) / 1_000_000;
        }
        StringBuilder pc = new StringBuilder();
        for (int i = 0; i < Elab.P_NAMES.length; i++) {
            pc.append(' ').append(Elab.P_NAMES[i]).append('=').append(Elab.patCount[i]);
        }
        System.out.printf(Locale.ROOT, "RESULT exp1 variant=%s mem=%d spines=%d args=%d pass_ms=%s record_ms=%d checksum=%016x values=%d hp_words=%d table=%d match_failures=%d pattern_words=%d patterns%s | %s%n",
                        variant, Mem.MODE, Workload.nSpines, Workload.totalArgs, Arrays.toString(ms), recMs, cs, Vals.count, Vals.hp, Vals.tmask + 1, matchFailures, Elab.pp, pc, elabInfo());
    }

    public static void run(String variant, Instantiator v, int passes) {
        long[] ms = new long[passes];
        long cs = 0;
        for (int i = 0; i < passes; i++) {
            long t0 = System.nanoTime();
            cs = drive(v);
            ms[i] = (System.nanoTime() - t0) / 1_000_000;
        }
        long t0 = System.nanoTime();
        v.finish();
        long fin = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf(Locale.ROOT, "RESULT exp1 variant=%s mem=%d spines=%d args=%d pass_ms=%s finish_ms=%d checksum=%016x values=%d hp_words=%d table=%d | %s%n",
                        variant, Mem.MODE, Workload.nSpines, Workload.totalArgs, Arrays.toString(ms), fin, cs, Vals.count, Vals.hp, Vals.tmask + 1, v.info());
    }

    public static String elabInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("elaborated ").append(Elab.elaborated).append(" code_words ").append(Elab.cp).append(" cse_hits ").append(Elab.cseHits);
        sb.append(" imm_operands ").append(Elab.immOperands).append(" slot_operands ").append(Elab.slotOperands).append(" instrs");
        for (int i = 0; i < Elab.OP_NAMES.length; i++) {
            sb.append(' ').append(Elab.OP_NAMES[i]).append('=').append(Elab.opCount[i]);
        }
        sb.append(" betas ").append(Generic.betas);
        return sb.toString();
    }

    /** All variants in ONE arena: because values are hash-consed, every variant must return the very same ids. */
    public static boolean verify(String[] names, Instantiator[] vs) {
        int[] ref = null;
        boolean ok = true;
        for (int i = 0; i < vs.length; i++) {
            int before = Vals.count;
            int[] out = record(vs[i]);
            vs[i].finish();
            if (ref == null) {
                ref = out;
                System.out.printf("VERIFY %s: reference, %d outputs, %d values%n", names[i], out.length, Vals.count);
            } else {
                boolean same = Arrays.equals(ref, out);
                ok &= same;
                System.out.printf("VERIFY %s: %s (%d outputs; values interned beyond the reference run: %d) | %s%n", names[i], same ? "IDENTICAL ids" : "MISMATCH", out.length,
                                Vals.count - before, vs[i].info());
                if (!same) {
                    for (int q = 0; q < Math.min(ref.length, out.length); q++) {
                        if (ref[q] != out[q]) {
                            System.out.printf("  first difference at output %d: %d vs %d%n", q, ref[q], out[q]);
                            Debug.spineOfOutput(q, ref, out);
                            break;
                        }
                    }
                }
            }
        }
        return ok;
    }

    // ------------------------------------------------------------------ heat distribution

    public static void heatReport() {
        int nc = Corpus.nConst;
        Integer[] idx = new Integer[nc];
        for (int i = 0; i < nc; i++) {
            idx[i] = i;
        }
        final int[] heat = Workload.heat;
        Arrays.sort(idx, (x, y) -> Integer.compare(heat[y], heat[x]));
        long total = Workload.nSpines;
        int used = 0;
        for (int i = 0; i < nc; i++) {
            if (heat[i] > 0) {
                used++;
            }
        }
        System.out.printf(Locale.ROOT, "HEAT exprs=%d app_nodes=%d constants=%d spines_const_head=%d spines_other_head=%d args=%d avg_args=%.2f over_applied=%d under_applied=%d max_params=%d%n",
                        Corpus.nExpr, Workload.appNodes, nc, total, Workload.spinesOther, Workload.totalArgs, (double) Workload.totalArgs / total, Workload.overApplied,
                        Workload.underApplied, Workload.maxParams);
        System.out.printf(Locale.ROOT, "HEAT constants used as a spine head: %d of %d (%.1f%%)%n", used, nc, 100.0 * used / nc);
        long acc = 0;
        int[] tops = {1, 10, 30, 100, 300, 1000, 3000, 10000, 30000};
        int ti = 0;
        double[] shares = {0.5, 0.8, 0.9, 0.95, 0.99, 0.999};
        int si = 0;
        StringBuilder a = new StringBuilder("HEAT share of all instantiations received by the hottest N constants:");
        StringBuilder b = new StringBuilder("HEAT constants needed to cover a share:");
        for (int r = 0; r < nc; r++) {
            acc += heat[idx[r]];
            if (ti < tops.length && r + 1 == tops[ti]) {
                a.append(String.format(Locale.ROOT, " top%d=%.1f%%", tops[ti], 100.0 * acc / total));
                ti++;
            }
            while (si < shares.length && acc >= shares[si] * total) {
                b.append(String.format(Locale.ROOT, " %.1f%%:%d", 100 * shares[si], r + 1));
                si++;
            }
        }
        System.out.println(a);
        System.out.println(b);
        long[] ths = {1, 10, 100, 1000, 10_000, 100_000, 1_000_000};
        for (long th : ths) {
            int cnt = 0;
            long sum = 0, post = 0;
            for (int i = 0; i < nc; i++) {
                if (heat[i] >= th) {
                    cnt++;
                    sum += heat[i];
                    post += heat[i] - th;
                }
            }
            System.out.printf(Locale.ROOT, "HEAT heat>=%d: %d constants, %.2f%% of instantiations, %.2f%% of instantiations happen after the constant crossed the threshold%n", th, cnt,
                            100.0 * sum / total, 100.0 * post / total);
        }
        for (int r = 0; r < 25 && r < nc; r++) {
            int c = idx[r];
            System.out.printf(Locale.ROOT, "HEAT #%d %s heat=%d (%.2f%%) params=%d kind=%d%n", r + 1, Corpus.constStr[c], heat[c], 100.0 * heat[c] / total, Workload.nparams[c],
                            Corpus.constKind[c]);
        }
    }
}
