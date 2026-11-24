import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.WeakHashMap;

/**
 * B6: hash-consing table implementations. Interns N (kind, a, b) triples, ~50% duplicates.
 *
 *   ids      : open addressing int[] table of node ids, keys live in primitive columns (kind/a/b),
 *              no per-entry object, no boxing, linear probing, load <= 0.5
 *   objopen  : open addressing Node[] table over node OBJECTS (identity of children compared by ==)
 *   hashmap  : java.util.HashMap<Key, Node> with a record key (allocates key + entry)
 *   weak     : WeakHashMap<Node, WeakReference<Node>> canonicalising map (what a "GC-friendly" interner uses)
 *
 * usage: HashCons <none|ids|objopen|hashmap|weak> [N=10000000] [nogc]
 */
public final class HashCons {
    static int mix(int k, int x, int y) {
        int h = k * 0x9E3779B1 + x; h = (h ^ (h >>> 15)) * 0x85EBCA77 + y; h ^= h >>> 13; h *= 0xC2B2AE3D; return h ^ (h >>> 16);
    }

    // ---------------- ids
    static byte[] kind; static int[] ca, cb; static int count; static int[] table; static int mask;
    static int internId(int k, int a, int b) {
        int h = mix(k, a, b) & mask;
        for (;;) {
            int id = table[h];
            if (id == 0) break;
            int i = id - 1;
            if (ca[i] == a && cb[i] == b && kind[i] == k) return i;
            h = (h + 1) & mask;
        }
        int i = count++;
        kind[i] = (byte) k; ca[i] = a; cb[i] = b; table[h] = i + 1;
        return i;
    }

    // ---------------- objects
    static final class Node {
        final int k; final Node a, b; final int hash;
        Node(int k, Node a, Node b, int hash) { this.k = k; this.a = a; this.b = b; this.hash = hash; }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object o) { return o instanceof Node n && n.k == k && n.a == a && n.b == b; }
    }
    static Node[] otable; static int ocount;
    static Node internObj(int k, Node a, Node b) {
        int hh = mix(k, a == null ? 0 : a.hash, b == null ? 0 : b.hash);
        int h = hh & mask;
        for (;;) {
            Node n = otable[h];
            if (n == null) break;
            if (n.hash == hh && n.k == k && n.a == a && n.b == b) return n;
            h = (h + 1) & mask;
        }
        Node n = new Node(k, a, b, hh); otable[h] = n; ocount++;
        return n;
    }

    public static void main(String[] args) {
        String mode = args[0];
        int n = args.length > 1 ? Integer.parseInt(args[1]) : 10_000_000;
        int cap = Integer.highestOneBit(n * 2 - 1) << 1; mask = cap - 1;
        java.util.Random r = new java.util.Random(5);
        // pre-generate the request stream so that generation cost is excluded: each request picks
        // children among already interned nodes; duplicates arise by replaying earlier requests
        int[] rk = new int[n], ra = new int[n], rb = new int[n];
        for (int i = 0; i < n; i++) {
            if (i > 16 && r.nextBoolean()) { int j = r.nextInt(i); rk[i] = rk[j]; ra[i] = ra[j]; rb[i] = rb[j]; }
            else { rk[i] = 2 + r.nextInt(3); ra[i] = r.nextInt(Math.max(1, i / 2 + 1)); rb[i] = r.nextInt(Math.max(1, i / 2 + 1)); }
        }
        System.gc(); long base = Bench.oldUsed();
        Bench b = new Bench("hashcons " + mode + " N=" + n).start();
        long check = 0; int distinct;
        switch (mode) {
            case "none": distinct = 1; break;     // baseline: request generation only
            case "ids": {
                kind = new byte[n + 1]; ca = new int[n + 1]; cb = new int[n + 1]; table = new int[cap];
                int[] res = new int[n];   // request -> node id (children refer to request results)
                for (int i = 0; i < n; i++) {
                    int a = i == 0 ? 0 : res[ra[i] % i], bb = i == 0 ? 0 : res[rb[i] % i];
                    res[i] = internId(rk[i], a, bb); check += res[i];
                }
                distinct = count; break;
            }
            case "objopen": {
                otable = new Node[cap]; Node[] res = new Node[n];
                for (int i = 0; i < n; i++) {
                    Node a = i == 0 ? null : res[ra[i] % i], bb = i == 0 ? null : res[rb[i] % i];
                    res[i] = internObj(rk[i], a, bb); check += res[i].hash;
                }
                distinct = ocount; break;
            }
            case "hashmap": {
                HashMap<Node, Node> m = new HashMap<>(cap); Node[] res = new Node[n];
                for (int i = 0; i < n; i++) {
                    Node a = i == 0 ? null : res[ra[i] % i], bb = i == 0 ? null : res[rb[i] % i];
                    Node probe = new Node(rk[i], a, bb, mix(rk[i], a == null ? 0 : a.hash, bb == null ? 0 : bb.hash));
                    Node old = m.putIfAbsent(probe, probe);
                    res[i] = old == null ? probe : old; check += res[i].hash;
                }
                distinct = m.size(); break;
            }
            default: {
                WeakHashMap<Node, WeakReference<Node>> m = new WeakHashMap<>(cap); Node[] res = new Node[n];
                for (int i = 0; i < n; i++) {
                    Node a = i == 0 ? null : res[ra[i] % i], bb = i == 0 ? null : res[rb[i] % i];
                    Node probe = new Node(rk[i], a, bb, mix(rk[i], a == null ? 0 : a.hash, bb == null ? 0 : bb.hash));
                    WeakReference<Node> w = m.get(probe); Node old = w == null ? null : w.get();
                    if (old == null) { m.put(probe, new WeakReference<>(probe)); old = probe; }
                    res[i] = old; check += old.hash;
                }
                distinct = m.size(); break;
            }
        }
        b.stop(n);
        if (args.length > 2 && args[2].equals("nogc")) { System.out.println("distinct " + distinct + " check " + check); return; }
        long c0 = Bench.OS.getProcessCpuTime();
        System.gc();
        long gc1 = Bench.OS.getProcessCpuTime() - c0;
        long retained = Bench.oldUsed() - base;
        c0 = Bench.OS.getProcessCpuTime(); System.gc(); long gc2 = Bench.OS.getProcessCpuTime() - c0;
        System.out.printf("distinct %d  retained %.0f MB (%.1f B per distinct node, incl. the request->result array)  full GC cpu %d ms then %d ms  check %d%n",
            distinct, retained / 1e6, (double) retained / distinct, gc1 / 1_000_000, gc2 / 1_000_000, check);
    }
}
