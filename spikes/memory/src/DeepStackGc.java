/**
 * B5: young-GC cost as a function of live Java stack depth.
 *
 * HotSpot's Serial/Parallel/G1 young collections scan every frame of every thread stack as roots
 * (no stack watermark; only ZGC processes stacks lazily). A recursive evaluator that sits DEPTH
 * frames deep while allocating therefore pays O(DEPTH) per young collection.
 *
 * The program recurses DEPTH frames (each frame holds 3 live references, like a whnf/defeq frame),
 * then at the bottom allocates ALLOC_GB of short-lived garbage and returns.
 *
 * usage: DeepStackGc <depth> <allocGB>
 */
public final class DeepStackGc {
    static final class Node { Node a, b; int x; Node(Node a, Node b, int x) { this.a = a; this.b = b; this.x = x; } }
    static long allocBytes;

    static long churn() {
        long acc = 0; Node prev = null;
        long n = allocBytes / 24;
        for (long i = 0; i < n; i++) {
            Node m = new Node(null, null, (int) i);
            if ((i & 7) == 0) prev = m;      // tiny live set
            acc += m.x + (prev == null ? 0 : prev.x);
        }
        return acc;
    }

    static long recurse(int depth, Node env, Node t, Node u) {
        if (depth == 0) return churn();
        // three live oops per frame: env, t and a fresh frame-local value kept alive across the call
        Node local = new Node(env, t, depth);
        long r = recurse(depth - 1, local, u, t);
        return r + local.x + (env == null ? 0 : env.x);
    }

    public static void main(String[] args) throws Exception {
        int depth = Integer.parseInt(args[0]);
        allocBytes = (long) (Double.parseDouble(args[1]) * (1L << 30));
        long[] out = new long[1];
        // warm up the JIT at shallow depth
        long keep = allocBytes; allocBytes = 256L << 20;
        for (int i = 0; i < 3; i++) recurse(1000, null, null, null);
        allocBytes = keep;
        long gcc0 = Bench.gcCount(), gcm0 = Bench.gcMillis();
        long c0 = Bench.OS.getProcessCpuTime(), w0 = System.nanoTime();
        Thread t = new Thread(null, () -> out[0] = recurse(depth, null, null, null), "deep", 8L << 30);
        t.start(); t.join();
        long cpu = Bench.OS.getProcessCpuTime() - c0, wall = System.nanoTime() - w0;
        long gcs = Bench.gcCount() - gcc0, gcm = Bench.gcMillis() - gcm0;
        System.out.printf("depth %9d alloc %.0f GB : procCPU %6.2f s wall %6.2f s  GCs %4d  gcPause %6d ms  => %.3f ms per GC%n",
            depth, allocBytes / (double) (1L << 30), cpu / 1e9, wall / 1e9, gcs, gcm, gcs == 0 ? 0 : (double) gcm / gcs);
    }
}
