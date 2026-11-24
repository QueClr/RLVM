/**
 * B9: constructor reuse (in-place update when a "unique" flag is set) vs fresh allocation on a
 * generational collector. Models an iota loop that consumes a cons cell and produces a cons cell
 * of the same shape (List.map-like), where the payload is a freshly computed boxed value.
 *
 *   fresh  : out = new Cell(new Val(f(c.val)), mapped tail)          (old list becomes garbage)
 *   reuse  : if (c.unique) { c.val = new Val(f(c.val)); out = c }    (FBIP-style reuse)
 *            the flag test is the "few-bit uniqueness" check; cells are all unique here, i.e. the
 *            BEST case for reuse.
 *
 * Two regimes: short lists that live and die in the young generation (len 1000, repeated), and a
 * long-lived list that has been promoted to the old generation (len = N), mapped ROUNDS times.
 *
 * usage: CtorReuse <youngLen> <youngReps> <oldLen> <oldRounds>
 */
public final class CtorReuse {
    static final class Val { final int x; Val(int x) { this.x = x; } }
    static final class Cell { Val val; Cell tail; boolean unique = true; Cell(Val v, Cell t) { val = v; tail = t; } }

    static Cell build(int n) { Cell c = null; for (int i = 0; i < n; i++) c = new Cell(new Val(i), c); return c; }

    // iterative map building the result in order (tail-recursion-modulo-cons style)
    static Cell mapFresh(Cell c) {
        Cell head = null, last = null;
        for (; c != null; c = c.tail) {
            Cell n = new Cell(new Val(c.val.x + 1), null);
            if (last == null) head = n; else last.tail = n;
            last = n;
        }
        return head;
    }
    static Cell mapReuse(Cell c) {
        Cell head = c;
        for (; c != null; c = c.tail) {
            if (c.unique) c.val = new Val(c.val.x + 1);
            else throw new AssertionError();
        }
        return head;
    }
    static long sum(Cell c) { long s = 0; for (; c != null; c = c.tail) s += c.val.x; return s; }

    public static void main(String[] args) {
        int youngLen = Integer.parseInt(args[0]), youngReps = Integer.parseInt(args[1]);
        int oldLen = Integer.parseInt(args[2]), oldRounds = Integer.parseInt(args[3]);
        final long[] out = new long[2];
        final String only = args.length > 4 ? args[4] : "all";   // all | none | youngfresh | youngreuse | oldfresh | oldreuse
        final boolean all = only.equals("all");
        if (all || only.equals("youngfresh")) Bench.best("young fresh  (len " + youngLen + ")", (long) youngLen * youngReps, 6, 3, () -> {
            long s = 0; for (int r = 0; r < youngReps; r++) { Cell l = build(youngLen); l = mapFresh(l); s += l.val.x; } out[0] = s; });
        if (all || only.equals("youngreuse")) Bench.best("young reuse  (len " + youngLen + ")", (long) youngLen * youngReps, 6, 3, () -> {
            long s = 0; for (int r = 0; r < youngReps; r++) { Cell l = build(youngLen); l = mapReuse(l); s += l.val.x; } out[1] = s; });
        if (all && out[0] != out[1]) throw new AssertionError();

        for (String mode : new String[] {"fresh", "reuse", "build"}) {
            if (!all && !only.equals("old" + mode)) continue;
            if (all && mode.equals("build")) continue;        // "oldbuild": baseline that only builds and promotes the list
            Cell l = build(oldLen);
            System.gc(); System.gc();           // promote the list to the old generation
            final Cell[] cur = { l };
            long g0 = Bench.gcCount(), m0 = Bench.gcMillis(), c0 = Bench.OS.getProcessCpuTime(), w0 = System.nanoTime();
            if (!mode.equals("build")) for (int r = 0; r < oldRounds; r++) cur[0] = mode.equals("fresh") ? mapFresh(cur[0]) : mapReuse(cur[0]);
            long cpu = Bench.OS.getProcessCpuTime() - c0;
            System.out.printf("old   %-6s (len %d x %d rounds)   procCPU %8.1f ms (%6.2f ns/elem)  wall %8.1f ms  GCs %d (%d ms)  check %d%n",
                mode, oldLen, oldRounds, cpu / 1e6, (double) cpu / ((long) oldLen * oldRounds), (System.nanoTime() - w0) / 1e6,
                Bench.gcCount() - g0, Bench.gcMillis() - m0, sum(cur[0]));
            cur[0] = null; l = null; System.gc();
        }
    }
}
