package rlvm.spike.stepper;

/**
 * One control convention. {@link #decl} runs the four judgments of the benchmark on one closed root term:
 * <pre>
 *   v  = EVAL(root, [])        NbE evaluation, no delta
 *   nf = READBACK(v, 0)        normal form, emitted into the hash-consed term region
 *   w  = EVAL(nf, [])
 *   ok = CONV(v, w, 0)         structural conversion (identity first, binders opened, spines compared)
 * </pre>
 * and leaves the results in {@link #nf}, {@link #w}, {@link #ok}. Every convention must return the same three
 * values and must call the operation library ({@link Vals}, {@link Terms#mk}) in the same order as {@link Rec},
 * so that a whole run interns the same records under the same ids.
 */
public abstract class Engine {
    public static int nf, w;
    public static boolean ok;

    public abstract void decl(int root);

    /** Ticks the last {@link #tryDecl} charged to its TRY segment. */
    public static long tryTicks;

    /** The first three judgments unbudgeted, then CONV under a TRY with this budget: 1, 0 or 2 (= U, undecided). */
    public int tryDecl(int root, int budget) {
        throw new UnsupportedOperationException();
    }

    /** Capacity the worklist grew to, in words (0 for plain recursion). */
    public int stackWords() {
        return 0;
    }
}
