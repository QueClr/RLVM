package rlvm.spike.lc;

/** Benchmark programs. Each evaluates to a one-argument function taking the size parameter. */
public final class Programs {
    private Programs() {
    }

    /** First-order recursion + integer arithmetic. fib(n). */
    public static final String FIB = """
                    (def fib (lam (n) (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2))))))
                    (lam (n) (fib n))
                    """;

    private static final String CHURCH_PRELUDE = """
                    (def zero (lam (s) (lam (z) z)))
                    (def suc (lam (n) (lam (s) (lam (z) (s ((n s) z))))))
                    (def n2 (lam (s) (lam (z) (s (s z)))))
                    (def n5 (lam (s) (lam (z) (s (s (s (s (s z))))))))
                    (def mul (lam (a) (lam (b) (lam (s) (lam (z) ((a (b s)) z))))))
                    (def n10 ((mul n2) n5))
                    (def n100 ((mul n10) n10))
                    (def n1k ((mul n100) n10))
                    (def fromInt (lam (k) (if (< k 1) zero (suc (fromInt (- k 1))))))
                    (def toInt (lam (n) ((n (lam (x) (+ x 1))) 0)))
                    """;

    /**
     * Higher-order closures, Church numerals as in AndrasKovacs/normalization-bench (Nat): computes
     * 1000 * 1000 * k by Church multiplication and reads the result back as an integer.
     */
    public static final String CHURCH = CHURCH_PRELUDE + """
                    (lam (k) (toInt ((mul n1k) ((mul n1k) (fromInt k)))))
                    """;

    /**
     * The "Tree" workload of AndrasKovacs/normalization-bench: a full binary Church tree of depth k,
     * folded with leaf = 1 and node = +, i.e. 2^k.
     */
    public static final String TREE = CHURCH_PRELUDE + """
                    (def leaf (lam (l) (lam (n) l)))
                    (def node (lam (t1) (lam (t2) (lam (l) (lam (n) ((n ((t1 l) n)) ((t2 l) n)))))))
                    (def fullTree (lam (n) ((n (lam (t) ((node t) t))) leaf)))
                    (lam (k) (((fullTree (fromInt k)) 1) (lam (a) (lam (b) (+ a b)))))
                    """;

    /**
     * Synthetic "many warm functions" program for measuring compilation cost: K distinct functions of
     * ~25 AST nodes each, f_i calling f_(i-1), driven by a non-tail-recursive counting loop, so every
     * function is equally hot. main(n) runs the chain n times.
     */
    public static String many(int k) {
        StringBuilder sb = new StringBuilder();
        sb.append("(def f0 (lam (x) (+ x 1)))\n");
        for (int i = 1; i <= k; i++) {
            sb.append("(def f").append(i).append(" (lam (x) (let a (f").append(i - 1).append(" (+ x ").append(i % 7).append(")) (let b (* a ").append(3 + i % 5).append(
                            ") (if (< b x) (+ a b) (- b (+ a ").append(i).append("))))))) \n");
        }
        sb.append("(def loop (lam (n acc) (if (< n 1) acc (loop (- n 1) (+ acc (f").append(k).append(" n))))))\n");
        sb.append("(lam (n) (loop n 0))\n");
        return sb.toString();
    }

    public static String byName(String name) {
        if (name.startsWith("many:")) {
            return many(Integer.parseInt(name.substring(5)));
        }
        return switch (name) {
            case "fib" -> FIB;
            case "church" -> CHURCH;
            case "tree" -> TREE;
            default -> throw new IllegalArgumentException("unknown program " + name + " (fib|church|tree|many:K)");
        };
    }
}
