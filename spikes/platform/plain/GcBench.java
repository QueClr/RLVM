import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;

/**
 * Allocation benchmark shaped like a proof checker's heap:
 * <ul>
 * <li>a large, long-lived, hash-consed term DAG (the "environment": LIVE million nodes, a few GB), and
 * <li>a high-rate stream of short-lived trees (instantiate/substitute style rebuilding of the top
 * levels of random terms), a small fraction of which is interned and survives.
 * </ul>
 *
 * <pre>
 * java GcBench &lt;liveMillions&gt; &lt;workMillions&gt; [depth=6] [internEvery=128]
 * </pre>
 *
 * Output: build time, transform time, allocated bytes (estimated), GC count/pause, thread vs process
 * CPU. Compare collectors with identical arguments; total allocation is deterministic (fixed seed).
 */
public final class GcBench {
    static final class Node {
        final int tag;
        final int hash;
        final Node a;
        final Node b;

        Node(int tag, Node a, Node b) {
            this.tag = tag;
            this.a = a;
            this.b = b;
            int h = tag * 0x9E3779B1;
            h = (h ^ (a == null ? 0 : a.hash)) * 0x85EBCA6B;
            h = (h ^ (b == null ? 0 : b.hash)) * 0xC2B2AE35;
            this.hash = h ^ (h >>> 15);
        }
    }

    /** Open-addressing hash-cons table (linear probing, load factor 0.5). */
    static Node[] table = new Node[1 << 16];
    static int size;

    static Node intern(int tag, Node a, Node b) {
        Node probe = new Node(tag, a, b); // allocation is escape-analysed away only when the lookup hits
        return intern(probe);
    }

    static Node intern(Node probe) {
        Node[] t = table;
        int mask = t.length - 1;
        int i = probe.hash & mask;
        while (true) {
            Node n = t[i];
            if (n == null) {
                t[i] = probe;
                if (++size * 2 > t.length) {
                    grow();
                }
                return probe;
            }
            if (n.hash == probe.hash && n.tag == probe.tag && n.a == probe.a && n.b == probe.b) {
                return n;
            }
            i = (i + 1) & mask;
        }
    }

    static void grow() {
        Node[] old = table;
        Node[] t = new Node[old.length * 2];
        int mask = t.length - 1;
        for (Node n : old) {
            if (n != null) {
                int i = n.hash & mask;
                while (t[i] != null) {
                    i = (i + 1) & mask;
                }
                t[i] = n;
            }
        }
        table = t;
    }

    static long seed = 0x2545F4914F6CDD1DL;

    static int rnd(int bound) {
        seed ^= seed << 13;
        seed ^= seed >>> 7;
        seed ^= seed << 17;
        return (int) ((seed >>> 33) % bound);
    }

    static long allocatedNodes;

    /** Rebuild the top {@code depth} levels of {@code n} with fresh, non-interned nodes (garbage). */
    static Node rebuild(Node n, int depth, int salt) {
        if (depth == 0 || n.a == null) {
            return n;
        }
        allocatedNodes++;
        return new Node(n.tag + salt, rebuild(n.a, depth - 1, salt), rebuild(n.b, depth - 1, salt));
    }

    /** Intern a freshly built tree bottom-up (the surviving fraction). */
    static Node internTree(Node n, int depth) {
        if (depth == 0 || n.a == null) {
            return n;
        }
        return intern(n.tag, internTree(n.a, depth - 1), internTree(n.b, depth - 1));
    }

    static int checksum(Node n, int depth) {
        if (depth == 0 || n.a == null) {
            return n.hash;
        }
        return n.tag + 31 * checksum(n.a, depth - 1) + 17 * checksum(n.b, depth - 1);
    }

    public static void main(String[] args) {
        int liveMillions = Integer.parseInt(args[0]);
        int workMillions = Integer.parseInt(args[1]);
        int depth = args.length > 2 ? Integer.parseInt(args[2]) : 6;
        int internEvery = args.length > 3 ? Integer.parseInt(args[3]) : 128;

        long t0 = System.nanoTime();
        int live = liveMillions * 1_000_000;
        Node[] roots = new Node[1 << 20];
        for (int i = 0; i < 64; i++) {
            roots[i] = intern(i, null, null);
        }
        int nroots = 64;
        while (size < live) {
            Node n = intern(rnd(1 << 20), roots[rnd(nroots)], roots[rnd(nroots)]);
            if (nroots < roots.length) {
                roots[nroots++] = n;
            } else {
                roots[rnd(nroots)] = n;
            }
        }
        long t1 = System.nanoTime();

        long sum = 0;
        long work = workMillions * 1_000_000L;
        int k = 0;
        while (allocatedNodes < work) {
            Node r = roots[rnd(nroots)];
            Node fresh = rebuild(r, depth, 1 + (k & 7));
            sum += checksum(fresh, depth);
            if (++k % internEvery == 0) {
                roots[rnd(nroots)] = internTree(fresh, depth);
            }
        }
        long t2 = System.nanoTime();

        long gcCount = 0;
        long gcMs = 0;
        StringBuilder gcs = new StringBuilder();
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount += Math.max(0, b.getCollectionCount());
            gcMs += Math.max(0, b.getCollectionTime());
            gcs.append(b.getName()).append('=').append(b.getCollectionCount()).append('/').append(b.getCollectionTime()).append("ms ");
        }
        long threadCpu = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime() / 1_000_000;
        long processCpu = ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime() / 1_000_000;
        long used = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20;
        System.out.printf("live %dM nodes (table %d slots) built in %d ms | transform %dM nodes in %d ms | sum %x | final interned %d | heap used %d MB | GC %d/%d ms [%s] | main-thread cpu %d ms | process cpu %d ms%n",
                        liveMillions, table.length, (t1 - t0) / 1_000_000, workMillions, (t2 - t1) / 1_000_000, sum, size, used, gcCount, gcMs, gcs.toString().trim(),
                        threadCpu, processCpu);
    }
}
