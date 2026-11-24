/**
 * B4: CPU cost of garbage collection per allocated byte / per surviving byte for a checker-like
 * allocation profile, measured as WHOLE-PROCESS CPU time (the arena charges GC and JIT threads).
 *
 * The mutator "checks declarations": each declaration allocates DECL_BYTES of small nodes
 * (24-32 byte objects with 2 references into a recent window, like closures/thunks/spine cells).
 * A fraction KEEP of the nodes is retained in a per-declaration table (memo/cache entries) and
 * all of it is dropped when the declaration ends. Optionally a static old object graph of
 * OLD_MB megabytes stays live for the whole run (environment, global tables).
 *
 * usage: GcCost <totalGB> <declMB> <keepFraction> <oldMB> [threads=1]
 */
public final class GcCost {
    static final class Node { Node a, b; int x; Node(Node a, Node b, int x) { this.a = a; this.b = b; this.x = x; } }

    static Node[] old;

    static long work(long totalBytes, long declBytes, double keep, long seed) {
        final int W = 256;                       // window of recent nodes (young, mostly dying)
        Node[] win = new Node[W];
        int keepEvery = keep <= 0 ? Integer.MAX_VALUE : (int) Math.max(1, Math.round(1 / keep));
        int perDecl = (int) (declBytes / 24);
        int keepCap = keep <= 0 ? 1 : perDecl / keepEvery + 1;
        long acc = 0; long allocated = 0; int rnd = (int) seed | 1;
        while (allocated < totalBytes) {
            Node[] table = new Node[keepCap];    // per-declaration memo table: dies with the declaration
            int kt = 0;
            for (int i = 0; i < perDecl; i++) {
                rnd ^= rnd << 13; rnd ^= rnd >>> 17; rnd ^= rnd << 5;
                // x = depth of the node's reachable graph; capped at 6 so that liveness stays bounded
                // (a node keeps at most 2^6 others alive) - otherwise the window chain would leak everything.
                Node a = win[rnd & (W - 1)], b = win[(rnd >>> 8) & (W - 1)];
                if (a != null && a.x >= 6) a = null;
                if (b != null && b.x >= 6) b = null;
                int d = 1 + Math.max(a == null ? 0 : a.x, b == null ? 0 : b.x);
                Node n = new Node(a, b, d);
                win[i & (W - 1)] = n;
                if (i % keepEvery == 0 && kt < keepCap) table[kt++] = n;
                acc += d;
            }
            // "use" the table at the end of the declaration (cache hits)
            for (int i = 0; i < kt; i += 64) acc += table[i].x;
            allocated += (long) perDecl * 24;
        }
        return acc;
    }

    public static void main(String[] args) throws Exception {
        double totalGB = Double.parseDouble(args[0]);
        double declMB = Double.parseDouble(args[1]);
        double keep = Double.parseDouble(args[2]);
        int oldMB = Integer.parseInt(args[3]);
        int threads = args.length > 4 ? Integer.parseInt(args[4]) : 1;
        if (oldMB > 0) {
            int n = (int) ((long) oldMB * 1_000_000L / 28); // 24 B node + 4 B table slot
            old = new Node[n];
            for (int i = 0; i < n; i++) old[i] = new Node(i > 0 ? old[i - 1] : null, i > 100 ? old[i - 100] : null, i);
            System.gc();
        }
        long total = (long) (totalGB * (1L << 30)), decl = (long) (declMB * (1L << 20));
        // warm-up (JIT) on a small amount, excluded from the measurement
        work(Math.min(total, 512L << 20), decl, keep, 1);
        long gcc0 = Bench.gcCount(), gcm0 = Bench.gcMillis();
        long c0 = Bench.OS.getProcessCpuTime(), w0 = System.nanoTime();
        long[] res = new long[threads];
        final java.util.concurrent.atomic.AtomicLong mutCpu = new java.util.concurrent.atomic.AtomicLong();
        Thread[] ts = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int id = t;
            ts[t] = new Thread(() -> {
                res[id] = work(total / threads, decl, keep, 17 + id);
                mutCpu.addAndGet(Bench.TMX.getCurrentThreadCpuTime());
            });
            ts[t].start();
        }
        for (Thread t : ts) t.join();
        long cpu = Bench.OS.getProcessCpuTime() - c0, wall = System.nanoTime() - w0;
        System.out.printf("alloc %.0f GB decl %.0f MB keep %.3f old %d MB threads %d : procCPU %7.2f s = mutators %6.2f s + VM(GC/JIT/other) %5.2f s  wall %6.2f s  GCs %5d  gcPause %6d ms  => %.3f CPU-ns per allocated byte%n",
            totalGB, declMB, keep, oldMB, threads, cpu / 1e9, mutCpu.get() / 1e9, (cpu - mutCpu.get()) / 1e9, wall / 1e9,
            Bench.gcCount() - gcc0, Bench.gcMillis() - gcm0, (double) cpu / total);
    }
}
