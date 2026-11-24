/**
 * Deep recursion on the host stack: maximum depth for a given thread stack size, bytes per frame,
 * time per frame, StackOverflowError recovery, and behaviour of very large (multi-GB) thread stacks.
 *
 * <pre>
 * java DeepRec.java [stackMB ...]        e.g. java DeepRec.java 1 8 64 1024 8192
 * </pre>
 */
public final class DeepRec {
    static final class Cell {
        final Cell next;
        final int v;

        Cell(Cell next, int v) {
            this.next = next;
            this.v = v;
        }
    }

    static int depthReached;

    /** A frame resembling an evaluator step: a few live locals and an allocation. */
    static long recurse(int depth, Cell env, long acc) {
        depthReached = depth;
        Cell e = new Cell(env, depth);
        long r = recurse(depth + 1, e, acc + e.v);
        return r + env.v; // keeps env and e alive across the call (non-tail)
    }

    /** Same shape as recurse() but bounded: used only to get recurse-like code compiled. */
    static long warm(int depth, int limit, Cell env, long acc) {
        if (depth == limit) {
            return acc;
        }
        Cell e = new Cell(env, depth);
        long r = warm(depth + 1, limit, e, acc + e.v);
        return r + env.v;
    }

    static long bounded(int depth, int limit, Cell env) {
        if (depth == limit) {
            return env.v;
        }
        Cell e = new Cell(env, depth);
        return bounded(depth + 1, limit, e) + env.v;
    }

    static volatile long sink;

    static void probe(long stackMb) throws InterruptedException {
        Thread t = new Thread(null, () -> {
            // warm up: make sure recurse/bounded are JIT-compiled before measuring frame sizes
            for (int i = 0; i < 6000; i++) {
                sink += bounded(0, 1_000, new Cell(null, 1));
                try {
                    if (i % 60 == 0) {
                        warm(0, 1_000, new Cell(null, 0), 0);
                    }
                } catch (StackOverflowError e) {
                    // cannot happen at this depth
                }
            }
            long t0 = System.nanoTime();
            int max = 0;
            try {
                recurse(0, new Cell(null, 0), 0);
            } catch (StackOverflowError e) {
                max = depthReached;
            }
            long t1 = System.nanoTime();
            // recovery: the thread must still be usable, repeatedly
            int again = 0;
            for (int i = 0; i < 3; i++) {
                try {
                    recurse(0, new Cell(null, 0), 0);
                } catch (StackOverflowError e) {
                    again = depthReached;
                }
            }
            long t2 = System.nanoTime();
            long r = bounded(0, Math.max(1, max / 2), new Cell(null, 1));
            long t3 = System.nanoTime();
            System.out.printf("stack %5d MB: max depth %,d (%.1f B/frame) | first overflow %d ms | 3 more overflows %d ms (depth %,d) | controlled recursion to depth %,d: %d ms (%.1f ns/frame)%n",
                            stackMb, max, max == 0 ? 0.0 : (stackMb << 20) / (double) max, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, again,
                            max / 2, (t3 - t2) / 1_000_000, max == 0 ? 0.0 : (t3 - t2) / (double) (max / 2));
            sink += r;
        }, "deep-" + stackMb, stackMb << 20);
        t.start();
        t.join();
    }

    public static void main(String[] args) throws Exception {
        System.out.println("vm: " + System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"));
        // default stack of the main thread (skipped with -Ddeeprec.skipMain=true)
        if (!Boolean.getBoolean("deeprec.skipMain")) {
            try {
                recurse(0, new Cell(null, 0), 0);
            } catch (StackOverflowError e) {
                System.out.printf("main thread (default stack): StackOverflowError at depth %,d%n", depthReached);
            }
        }
        long[] sizes = args.length == 0 ? new long[]{1, 8, 64, 1024} : java.util.Arrays.stream(args).mapToLong(Long::parseLong).toArray();
        for (long mb : sizes) {
            probe(mb);
        }
    }
}
