import java.util.Random;

/**
 * B3: object-per-node vs flat primitive columns for the immutable export DAG.
 *
 * N nodes; node i references earlier nodes (as export ids do). Kinds: 0 Var(idx) 1 Const(id)
 * 2 App(f,a) 3 Lam(ty,body) 4 Pi(ty,body). Both representations also carry a 32-bit hash and a
 * 32-bit meta word (loose-bvar range / flags) per node, as a hash-consing kernel would.
 *
 * Reports: build CPU (thread and whole process, so GC promotion work is visible), retained heap
 * after a full GC, cost of a full GC with the structure live, and two traversals
 * (sequential bottom-up fold; pointer-chasing DFS from random roots).
 *
 * usage: TermStore <obj|col|aos|chunk|seg> [N=20000000] [phases=bgd]
 */
public final class TermStore {
    static abstract class Expr { int hash; int meta; }
    static final class Var extends Expr { final int idx; Var(int i) { idx = i; } }
    static final class Const extends Expr { final int id; Const(int i) { id = i; } }
    static final class App extends Expr { final Expr f, a; App(Expr f, Expr a) { this.f = f; this.a = a; } }
    static final class Lam extends Expr { final Expr ty, body; Lam(Expr t, Expr b) { ty = t; body = b; } }
    static final class Pi extends Expr { final Expr ty, body; Pi(Expr t, Expr b) { ty = t; body = b; } }

    static int mix(int k, int x, int y) {
        int h = k * 0x9E3779B1 + x; h = (h ^ (h >>> 15)) * 0x85EBCA77 + y; return h ^ (h >>> 13);
    }

    // deterministic node generator shared by both representations
    static final class Gen {
        final Random r = new Random(7);
        int kind, x, y;
        void next(int i) {
            if (i < 16) { kind = i & 1; x = i; y = 0; return; }
            int p = r.nextInt(100);
            if (p < 12) { kind = 0; x = r.nextInt(8); y = 0; }
            else if (p < 27) { kind = 1; x = r.nextInt(200_000); y = 0; }
            else {
                kind = p < 77 ? 2 : (p < 90 ? 3 : 4);
                // children are mostly recent nodes, sometimes far back (shared library terms)
                x = i - 1 - Math.min(i - 1, r.nextInt(4) == 0 ? r.nextInt(i) : r.nextInt(64));
                y = i - 1 - Math.min(i - 1, r.nextInt(4) == 0 ? r.nextInt(i) : r.nextInt(64));
            }
        }
    }

    static Expr[] objs;
    static byte[] kindC; static int[] aC, bC, hashC, metaC;

    static void buildObj(int n) {
        Expr[] t = new Expr[n]; Gen g = new Gen();
        for (int i = 0; i < n; i++) {
            g.next(i); Expr e;
            switch (g.kind) {
                case 0: e = new Var(g.x); e.hash = mix(0, g.x, 0); e.meta = g.x + 1; break;
                case 1: e = new Const(g.x); e.hash = mix(1, g.x, 0); e.meta = 0; break;
                case 2: { Expr f = t[g.x], a = t[g.y]; e = new App(f, a); e.hash = mix(2, f.hash, a.hash); e.meta = Math.max(f.meta, a.meta); break; }
                case 3: { Expr f = t[g.x], a = t[g.y]; e = new Lam(f, a); e.hash = mix(3, f.hash, a.hash); e.meta = Math.max(f.meta, Math.max(0, a.meta - 1)); break; }
                default: { Expr f = t[g.x], a = t[g.y]; e = new Pi(f, a); e.hash = mix(4, f.hash, a.hash); e.meta = Math.max(f.meta, Math.max(0, a.meta - 1)); break; }
            }
            t[i] = e;
        }
        objs = t;
    }

    static void buildCol(int n) {
        byte[] k = new byte[n]; int[] a = new int[n], b = new int[n], h = new int[n], m = new int[n]; Gen g = new Gen();
        for (int i = 0; i < n; i++) {
            g.next(i); k[i] = (byte) g.kind; a[i] = g.x; b[i] = g.y;
            switch (g.kind) {
                case 0: h[i] = mix(0, g.x, 0); m[i] = g.x + 1; break;
                case 1: h[i] = mix(1, g.x, 0); m[i] = 0; break;
                case 2: h[i] = mix(2, h[g.x], h[g.y]); m[i] = Math.max(m[g.x], m[g.y]); break;
                case 3: h[i] = mix(3, h[g.x], h[g.y]); m[i] = Math.max(m[g.x], Math.max(0, m[g.y] - 1)); break;
                default: h[i] = mix(4, h[g.x], h[g.y]); m[i] = Math.max(m[g.x], Math.max(0, m[g.y] - 1)); break;
            }
        }
        kindC = k; aC = a; bC = b; hashC = h; metaC = m;
    }

    // pointer-chasing DFS: from each root visit up to `budget` nodes with an explicit stack
    static long dfsObj(int roots, int budget, int n) {
        Random r = new Random(99); Expr[] st = new Expr[4096]; long acc = 0;
        for (int q = 0; q < roots; q++) {
            int sp = 0; st[sp++] = objs[n - 1 - r.nextInt(n / 2)]; int left = budget;
            while (sp > 0 && left-- > 0) {
                Expr e = st[--sp]; acc += e.hash;
                if (e instanceof App ap) { if (sp < 4094) { st[sp++] = ap.a; st[sp++] = ap.f; } }
                else if (e instanceof Lam l) { if (sp < 4094) { st[sp++] = l.body; st[sp++] = l.ty; } }
                else if (e instanceof Pi p) { if (sp < 4094) { st[sp++] = p.body; st[sp++] = p.ty; } }
                else if (e instanceof Var v) acc += v.idx;
                else acc += ((Const) e).id;
            }
        }
        return acc;
    }
    static long dfsCol(int roots, int budget, int n) {
        Random r = new Random(99); int[] st = new int[4096]; long acc = 0;
        byte[] k = kindC; int[] a = aC, b = bC, h = hashC;
        for (int q = 0; q < roots; q++) {
            int sp = 0; st[sp++] = n - 1 - r.nextInt(n / 2); int left = budget;
            while (sp > 0 && left-- > 0) {
                int e = st[--sp]; acc += h[e];
                int kk = k[e];
                if (kk >= 2) { if (sp < 4094) { st[sp++] = b[e]; st[sp++] = a[e]; } }
                else acc += a[e];
            }
        }
        return acc;
    }

    // ---- aos: one int[] with stride 4 per node: [kind | meta<<3, a, b, hash] (one cache line touch per node)
    static int[] aos;
    static void buildAos(int n) {
        int[] t = new int[n * 4]; Gen g = new Gen();
        for (int i = 0; i < n; i++) {
            g.next(i); int o = i * 4; int h, m;
            switch (g.kind) {
                case 0: h = mix(0, g.x, 0); m = g.x + 1; break;
                case 1: h = mix(1, g.x, 0); m = 0; break;
                case 2: h = mix(2, t[g.x * 4 + 3], t[g.y * 4 + 3]); m = Math.max(t[g.x * 4] >>> 3, t[g.y * 4] >>> 3); break;
                default: h = mix(g.kind, t[g.x * 4 + 3], t[g.y * 4 + 3]); m = Math.max(t[g.x * 4] >>> 3, Math.max(0, (t[g.y * 4] >>> 3) - 1)); break;
            }
            t[o] = g.kind | (m << 3); t[o + 1] = g.x; t[o + 2] = g.y; t[o + 3] = h;
        }
        aos = t;
    }
    static long dfsAos(int roots, int budget, int n) {
        Random r = new Random(99); int[] st = new int[4096]; long acc = 0; int[] t = aos;
        for (int q = 0; q < roots; q++) {
            int sp = 0; st[sp++] = n - 1 - r.nextInt(n / 2); int left = budget;
            while (sp > 0 && left-- > 0) {
                int o = st[--sp] * 4; acc += t[o + 3];
                if ((t[o] & 7) >= 2) { if (sp < 4094) { st[sp++] = t[o + 2]; st[sp++] = t[o + 1]; } }
                else acc += t[o + 1];
            }
        }
        return acc;
    }

    // ---- chunk: same record layout in int[][] chunks of 2^18 nodes (4 MB each); grows without copying
    static final int CS = 18, CM = (1 << CS) - 1;
    static int[][] chunks;
    static void buildChunk(int n) {
        int[][] c = new int[(n >>> CS) + 1][]; Gen g = new Gen();
        for (int i = 0; i < n; i++) {
            if ((i & CM) == 0) c[i >>> CS] = new int[(CM + 1) * 4];
            g.next(i); int[] t = c[i >>> CS]; int o = (i & CM) * 4; int h, m;
            switch (g.kind) {
                case 0: h = mix(0, g.x, 0); m = g.x + 1; break;
                case 1: h = mix(1, g.x, 0); m = 0; break;
                default: {
                    int[] tx = c[g.x >>> CS], ty = c[g.y >>> CS]; int ox = (g.x & CM) * 4, oy = (g.y & CM) * 4;
                    h = mix(g.kind, tx[ox + 3], ty[oy + 3]);
                    m = g.kind == 2 ? Math.max(tx[ox] >>> 3, ty[oy] >>> 3) : Math.max(tx[ox] >>> 3, Math.max(0, (ty[oy] >>> 3) - 1));
                }
            }
            t[o] = g.kind | (m << 3); t[o + 1] = g.x; t[o + 2] = g.y; t[o + 3] = h;
        }
        chunks = c;
    }
    static long dfsChunk(int roots, int budget, int n) {
        Random r = new Random(99); int[] st = new int[4096]; long acc = 0; int[][] c = chunks;
        for (int q = 0; q < roots; q++) {
            int sp = 0; st[sp++] = n - 1 - r.nextInt(n / 2); int left = budget;
            while (sp > 0 && left-- > 0) {
                int e = st[--sp]; int[] t = c[e >>> CS]; int o = (e & CM) * 4; acc += t[o + 3];
                if ((t[o] & 7) >= 2) { if (sp < 4094) { st[sp++] = t[o + 2]; st[sp++] = t[o + 1]; } }
                else acc += t[o + 1];
            }
        }
        return acc;
    }

    // ---- seg: same record layout off-heap in a MemorySegment (FFM API), 16 bytes per node
    static java.lang.foreign.MemorySegment seg;
    static final java.lang.foreign.ValueLayout.OfInt I32 = java.lang.foreign.ValueLayout.JAVA_INT;
    static void buildSeg(int n) {
        java.lang.foreign.MemorySegment t = java.lang.foreign.Arena.global().allocate((long) n * 16, 8); Gen g = new Gen();
        for (int i = 0; i < n; i++) {
            g.next(i); long o = (long) i * 16; int h, m;
            switch (g.kind) {
                case 0: h = mix(0, g.x, 0); m = g.x + 1; break;
                case 1: h = mix(1, g.x, 0); m = 0; break;
                default: {
                    long ox = (long) g.x * 16, oy = (long) g.y * 16;
                    h = mix(g.kind, t.get(I32, ox + 12), t.get(I32, oy + 12));
                    m = g.kind == 2 ? Math.max(t.get(I32, ox) >>> 3, t.get(I32, oy) >>> 3) : Math.max(t.get(I32, ox) >>> 3, Math.max(0, (t.get(I32, oy) >>> 3) - 1));
                }
            }
            t.set(I32, o, g.kind | (m << 3)); t.set(I32, o + 4, g.x); t.set(I32, o + 8, g.y); t.set(I32, o + 12, h);
        }
        seg = t;
    }
    static long dfsSeg(int roots, int budget, int n) {
        Random r = new Random(99); int[] st = new int[4096]; long acc = 0; java.lang.foreign.MemorySegment t = seg;
        for (int q = 0; q < roots; q++) {
            int sp = 0; st[sp++] = n - 1 - r.nextInt(n / 2); int left = budget;
            while (sp > 0 && left-- > 0) {
                long o = (long) st[--sp] * 16; acc += t.get(I32, o + 12);
                if ((t.get(I32, o) & 7) >= 2) { if (sp < 4094) { st[sp++] = t.get(I32, o + 8); st[sp++] = t.get(I32, o + 4); } }
                else acc += t.get(I32, o + 4);
            }
        }
        return acc;
    }

    public static void main(String[] args) {
        final String mode = args[0]; boolean obj = mode.equals("obj");
        int n = args.length > 1 ? Integer.parseInt(args[1]) : 20_000_000;
        // phases: b = build only, g = also two full GCs, d = also the DFS traversal (default "bgd")
        String phases = args.length > 2 ? args[2] : "bgd";
        System.gc(); long h0 = Bench.oldUsed();
        Bench b = new Bench("build " + args[0] + " N=" + n).start();
        switch (mode) {
            case "obj": buildObj(n); break;
            case "col": buildCol(n); break;
            case "aos": buildAos(n); break;
            case "chunk": buildChunk(n); break;
            case "seg": buildSeg(n); break;
            default: throw new IllegalArgumentException(mode);
        }
        b.stop(n);
        if (phases.contains("g")) {
        long t0 = System.nanoTime(); long c0 = Bench.OS.getProcessCpuTime();
        System.gc();
        long gcCpu = Bench.OS.getProcessCpuTime() - c0; long gcWall = System.nanoTime() - t0;
        long retained = Bench.oldUsed() - h0;
        System.out.printf("retained heap %.1f MB = %.2f bytes/node; first full GC: procCPU %.0f ms wall %.0f ms%n",
            retained / 1e6, (double) retained / n, gcCpu / 1e6, gcWall / 1e6);
        t0 = System.nanoTime(); c0 = Bench.OS.getProcessCpuTime();
        System.gc();
        System.out.printf("second full GC (structure already compacted/old): procCPU %.0f ms wall %.0f ms%n",
            (Bench.OS.getProcessCpuTime() - c0) / 1e6, (System.nanoTime() - t0) / 1e6);
        }
        if (!phases.contains("d")) return;
        final int roots = 200_000, budget = 200;
        final long[] out = new long[1];
        Bench.best("dfs " + args[0] + " (" + roots + " roots x " + budget + ")", (long) roots * budget, 6, 3,
            () -> { switch (mode) {
                case "obj": out[0] = dfsObj(roots, budget, n); break;
                case "col": out[0] = dfsCol(roots, budget, n); break;
                case "aos": out[0] = dfsAos(roots, budget, n); break;
                case "chunk": out[0] = dfsChunk(roots, budget, n); break;
                default: out[0] = dfsSeg(roots, budget, n); } });
        System.out.println("checksum " + out[0]);
    }
}
