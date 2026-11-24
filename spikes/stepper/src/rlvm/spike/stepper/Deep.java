package rlvm.spike.stepper;

import static rlvm.spike.stepper.Terms.*;

import java.util.Locale;

/**
 * Deep-term behaviour: synthetic closed terms with an N-deep nest, pushed through the same four judgments as
 * the benchmark. Each term contains one beta-redex at the bottom, so its normal form is a different term and the
 * conversion check has to walk instead of answering by identity.
 *
 * <pre>
 *   app-arg    fun f x => f (f (.. (f ((fun y => y) x))))        N applications nested in argument position,
 *                                                                variable head: EVAL and READBACK nest N deep
 *   app-fn     fun f x => f x x .. x ((fun y => y) x)            one spine of N arguments (N nested in function
 *                                                                position)
 *   lam        fun x1 .. xN => (fun y => y) xN                   N nested binders: READBACK and CONV nest N deep
 *   nest       c (fun z => c (fun z => .. c ((fun y => y) k)))   applications and binders alternating N deep,
 *                                                                constant heads: READBACK and CONV nest 2N deep
 *   inert      c (c (.. (c k)))                                  one inert nest N deep (no redex: identity conv)
 * </pre>
 */
final class Deep {
    private Deep() {
    }

    static boolean run(String variant, int n) {
        Engine en = Main.engine(variant);
        if (en == null) {
            return false;
        }
        // an export region of one term (id 0 = Sort 0); everything else is built in the hash-consed region
        kind = new byte[]{SORT};
        ea = new int[1];
        eb = new int[1];
        ec = new int[1];
        Terms.n = base = 1;
        derive();
        reserve(8 * n + 1024, 25);
        Vals.init(Math.max(1 << 20, 16 * n), 24);
        final int sort = 0;
        int c = mk(CONST, 7, 0, 0), k = mk(CONST, 9, 0, 0);
        int b0 = mk(BVAR, 0, 0, 0), b1 = mk(BVAR, 1, 0, 0);
        int id = mk(LAM, sort, b0, 0);

        String[] names = {"app-arg", "app-fn", "lam", "nest", "inert"};
        int[] roots = new int[names.length];
        {
            int t = mk(APP, id, b0, 0);
            for (int i = 0; i < n; i++) {
                t = mk(APP, b1, t, 0);
            }
            roots[0] = mk(LAM, sort, mk(LAM, sort, t, 0), 0);
        }
        {
            int t = b1;
            for (int i = 0; i < n; i++) {
                t = mk(APP, t, b0, 0);
            }
            t = mk(APP, t, mk(APP, id, b0, 0), 0);
            roots[1] = mk(LAM, sort, mk(LAM, sort, t, 0), 0);
        }
        {
            int t = mk(APP, id, b0, 0);
            for (int i = 0; i < n; i++) {
                t = mk(LAM, sort, t, 0);
            }
            roots[2] = t;
        }
        {
            int t = mk(APP, id, k, 0);
            for (int i = 0; i < n; i++) {
                t = mk(APP, c, mk(LAM, sort, t, 0), 0);
            }
            roots[3] = t;
        }
        {
            int t = k;
            for (int i = 0; i < n; i++) {
                t = mk(APP, c, t, 0);
            }
            roots[4] = t;
        }
        boolean all = true;
        for (int i = 0; i < roots.length; i++) {
            long t0 = System.nanoTime();
            String out;
            try {
                en.decl(roots[i]);
                out = String.format(Locale.ROOT, "nf=%d w=%d ok=%b", Engine.nf, Engine.w, Engine.ok);
                all &= Engine.ok;
            } catch (StackOverflowError e) {
                out = "STACKOVERFLOW";
                all = false;
            }
            System.out.printf(Locale.ROOT, "DEEP variant=%s n=%d term=%s %s ms=%d values=%d nf_terms=%d worklist_words=%d%n", variant, n, names[i], out, (System.nanoTime() - t0) / 1_000_000,
                            Vals.count, Terms.n - base, en.stackWords());
            if (out.equals("STACKOVERFLOW")) {
                break; // the machine state of a convention is undefined after an overflow
            }
        }
        return all;
    }
}
