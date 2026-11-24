package rlvm.level;

/**
 * Micro-benchmark of the common level operations, made to be measured from outside with instruction
 * counters (see {@code bench/modules/rlvm-level/run-bench.sh}):
 *
 * <pre>java -cp rlvm-level/target/classes:rlvm-level/target/test-classes rlvm.level.LevelBench SCENARIO ITERATIONS</pre>
 *
 * One process runs one scenario for the given number of iterations. The cost of one operation is
 * {@code (instructions(N2) - instructions(N1)) / (N2 - N1)} minus the same quantity for the
 * {@code baseline} scenario (same loop and array loads, no operation): JVM start-up, set-up and JIT
 * compilation are the same in both runs and cancel.
 *
 * <p>Every scenario works on an overlay of a frozen base, as a checker worker would.
 */
public final class LevelBench {
    private static final int M = 1024;
    private static final int CHUNK = 1 << 16;

    private final LevelStore ov;
    private final int[] xs = new int[M];
    private final int[] ys = new int[M];
    private final int[] zs = new int[M];
    private final int u;
    private final int v;
    private final int w;
    private final int names3;

    private LevelBench() {
        // parameters 1..8; a base of small levels of the shapes found in Mathlib
        LevelStore base = new LevelStore(NameOracle.BY_ID);
        int[] p = new int[8];
        for (int i = 0; i < 8; i++) {
            p[i] = base.param(i + 1);
        }
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                base.max(p[i], p[j]);
                base.max(base.succ(p[i]), p[j]);
                base.succ(base.max(p[i], p[j]));
                base.imax(p[i], p[j]);
                base.max(base.succ(p[i]), base.succ(p[j]));
            }
            base.succ(base.succ(p[i]));
            base.max(LevelStore.ONE, p[i]);
        }
        base.ofNat(4);
        names3 = base.internList(new int[] {1, 2, 3}, 0, 3);
        u = p[0];
        v = p[1];
        w = p[2];
        base.freeze();
        ov = base.newOverlay();
    }

    public static void main(String[] args) {
        String scenario = args[0];
        long n = Long.parseLong(args[1]);
        LevelBench b = new LevelBench();
        b.prepare(scenario);
        long t0 = System.nanoTime();
        long acc = 0;
        for (long done = 0; done < n; done += CHUNK) {
            int count = (int) Math.min(CHUNK, n - done);
            acc += b.chunk(scenario, count, (int) (done / CHUNK));
        }
        long t1 = System.nanoTime();
        System.out.println(scenario + " iterations=" + n + " checksum=" + acc + " loop_ms=" + (t1 - t0) / 1000000);
    }

    /** Fills xs / ys / zs for the scenario. */
    private void prepare(String scenario) {
        LevelStore s = ov;
        java.util.Random rnd = new java.util.Random(7);
        int[] p = new int[8];
        for (int i = 0; i < 8; i++) {
            p[i] = s.param(i + 1);
        }
        for (int j = 0; j < M; j++) {
            int a = p[rnd.nextInt(8)];
            int b = p[rnd.nextInt(8)];
            while (b == a) {
                b = p[rnd.nextInt(8)];
            }
            switch (scenario) {
                case "equiv-same":
                case "normalize-memo":
                case "canon-memo":
                    xs[j] = s.max(s.succ(a), b);
                    ys[j] = xs[j];
                    break;
                case "equiv-canon":
                case "equiv-official-memo":
                    // different ids, same class; the official normal forms agree too
                    xs[j] = s.max(a, b);
                    ys[j] = s.max(b, a);
                    break;
                case "equiv-diff":
                    xs[j] = s.max(s.succ(a), b);
                    ys[j] = s.succ(s.max(a, b));
                    break;
                case "geq":
                    xs[j] = s.succ(s.max(a, b));
                    ys[j] = s.max(s.succ(a), b);
                    break;
                case "mkmax":
                    xs[j] = s.succ(a);
                    ys[j] = b;
                    break;
                case "mkimax":
                    xs[j] = a;
                    ys[j] = s.succ(b);
                    break;
                case "inst-noparam":
                    xs[j] = s.ofNat(j & 3);
                    ys[j] = -1;                      // replaced by a real substitution below
                    break;
                case "cold-intern":
                case "cold-normalize":
                case "cold-canon":
                case "cold-equiv":
                    // four consecutive j with the same (j & 3) have four different a: within one reset
                    // window of 16 iterations every level built by cold() is new
                    xs[j] = p[(j >> 2) & 7];
                    ys[j] = p[((j >> 2) + 1 + ((j >> 5) % 7)) & 7];
                    break;
                case "cold-subst":
                case "cold-instantiate":
                    // 16 consecutive j give 16 different (x, y) pairs
                    xs[j] = p[j & 7];
                    ys[j] = p[(j + 1 + ((j >> 3) % 7)) & 7];
                    break;
                case "params-declared":
                    xs[j] = s.max(s.succ(p[rnd.nextInt(3)]), p[rnd.nextInt(3)]);
                    break;
                default:
                    xs[j] = a;
                    ys[j] = b;
                    break;
            }
        }
        if (scenario.equals("inst-noparam")) {
            int subst = s.mkSubst(names3, s.internList(new int[] {s.succ(p[4]), p[5], p[6]}, 0, 3));
            if (subst <= 0) {
                throw new IllegalStateException();
            }
            java.util.Arrays.fill(ys, subst);
        }
        if (scenario.equals("mkmax") || scenario.equals("mkimax")) {
            for (int j = 0; j < M; j++) {
                // make sure the result exists: the measured path is a hash-cons hit
                if (scenario.equals("mkmax")) {
                    s.mkMax(xs[j], ys[j]);
                } else {
                    s.mkIMax(xs[j], ys[j]);
                }
            }
        }
        if (scenario.equals("equiv-canon") || scenario.equals("equiv-diff") || scenario.equals("geq")
                || scenario.equals("canon-memo")) {
            for (int j = 0; j < M; j++) {
                s.canonId(xs[j]);
                s.canonId(ys[j]);
            }
        }
        if (scenario.equals("equiv-official-memo") || scenario.equals("normalize-memo")) {
            for (int j = 0; j < M; j++) {
                s.normalize(xs[j]);
                s.normalize(ys[j]);
            }
        }
        if (scenario.equals("params-declared")) {
            for (int j = 0; j < M; j++) {
                s.undeclaredParam(xs[j], names3);
            }
        }
        if (scenario.equals("inst-hit") || scenario.equals("inst-ident") || scenario.equals("instlist-hit")) {
            prepareInstantiate(scenario.equals("instlist-hit"));
        }
    }

    /**
     * (level, substitution) pairs (or (list, substitution)) that occupy different slots of the
     * direct-mapped cache, so that every measured call is a hit.
     */
    private void prepareInstantiate(boolean lists) {
        LevelStore s = ov;
        boolean[] used = new boolean[1 << 12];
        int found = 0;
        int[] keyL = new int[64];
        int[] keyS = new int[64];
        java.util.Random rnd = new java.util.Random(9);
        int[] p = new int[8];
        for (int i = 0; i < 8; i++) {
            p[i] = s.param(i + 1);
        }
        int[] buf = new int[3];
        while (found < 64) {
            buf[0] = s.succ(p[3 + rnd.nextInt(5)]);
            buf[1] = p[3 + rnd.nextInt(5)];
            buf[2] = s.max(p[3 + rnd.nextInt(5)], p[3 + rnd.nextInt(5)]);
            int subst = s.mkSubst(names3, s.internList(buf, 0, 3));
            int l = s.max(s.succ(p[rnd.nextInt(3)]), p[rnd.nextInt(3)]);
            int key = l;
            if (lists) {
                buf[0] = l;
                buf[1] = p[rnd.nextInt(3)];
                key = s.internList(buf, 0, 2);
            }
            int slot = LevelStore.cacheSlot(((long) key << 32) | (subst & 0xFFFFFFFFL));
            if (!used[slot]) {
                used[slot] = true;
                keyL[found] = key;
                keyS[found] = subst;
                found++;
            }
        }
        for (int j = 0; j < M; j++) {
            xs[j] = keyL[j & 63];
            ys[j] = keyS[j & 63];
            zs[j] = lists ? s.instantiateList(xs[j], ys[j]) : s.instantiate(xs[j], ys[j]);
        }
        for (int j = 0; j < M; j++) {
            int r = lists ? s.instantiateList(xs[j], ys[j]) : s.instantiate(xs[j], ys[j]);
            if (r != zs[j]) {
                throw new IllegalStateException("instantiate is not stable");
            }
        }
    }

    private long chunk(String scenario, int count, int round) {
        switch (scenario) {
            case "baseline":
                return baseline(count);
            case "equiv-same":
            case "equiv-canon":
            case "equiv-diff":
                return equiv(count);
            case "equiv-official-memo":
                return equivOfficial(count);
            case "geq":
                return geq(count);
            case "normalize-memo":
                return normalizeMemo(count);
            case "canon-memo":
                return canonMemo(count);
            case "mkmax":
                return mkMax(count);
            case "mkimax":
                return mkIMax(count);
            case "inst-noparam":
            case "inst-hit":
                return instantiate(count);
            case "inst-ident":
                return instantiateIdentity(count);
            case "instlist-hit":
                return instantiateList(count);
            case "params-declared":
                return paramsDeclared(count);
            case "cold-intern":
                return cold(count, round, 0);
            case "cold-normalize":
                return cold(count, round, 1);
            case "cold-canon":
                return cold(count, round, 2);
            case "cold-equiv":
                return cold(count, round, 3);
            case "cold-subst":
                return coldInstantiate(count, round, false);
            case "cold-instantiate":
                return coldInstantiate(count, round, true);
            default:
                throw new IllegalArgumentException("unknown scenario " + scenario);
        }
    }

    private long baseline(int count) {
        long acc = 0;
        for (int i = 0; i < count; i++) {
            int j = i & (M - 1);
            acc += xs[j] + ys[j];
        }
        return acc;
    }

    private long equiv(int count) {
        LevelStore s = ov;
        long acc = 0;
        for (int i = 0; i < count; i++) {
            int j = i & (M - 1);
            acc += s.isEquiv(xs[j], ys[j]) ? 1 : 0;
        }
        return acc;
    }

    private long equivOfficial(int count) {
        LevelStore s = ov;
        long acc = 0;
        for (int i = 0; i < count; i++) {
            int j = i & (M - 1);
            acc += s.isEquivOfficial(xs[j], ys[j]) ? 1 : 0;
        }
        return acc;
    }

    private long geq(int count) {
        LevelStore s = ov;
        long acc = 0;
        for (int i = 0; i < count; i++) {
            int j = i & (M - 1);
            acc += s.isGeq(xs[j], ys[j]) ? 1 : 0;
        }
        return acc;
    }

    private long normalizeMemo(int count) {
        LevelStore s = ov;
        long acc = 0;
        for (int i = 0; i < count; i++) {
            int j = i & (M - 1);
            acc += s.normalize(xs[j]) + ys[j];
        }
        return acc;
    }

    private long canonMemo(int count) {
        LevelStore s = ov;
        long acc = 0;
        for (int i = 0; i < count; i++) {
            int j = i & (M - 1);
            acc += s.canonId(xs[j]) + ys[j];
        }
        return acc;
    }

    private long mkMax(int count) {
        LevelStore s = ov;
        long acc = 0;
        for (int i = 0; i < count; i++) {
            int j = i & (M - 1);
            acc += s.mkMax(xs[j], ys[j]);
        }
        return acc;
    }

    private long mkIMax(int count) {
        LevelStore s = ov;
        long acc = 0;
        for (int i = 0; i < count; i++) {
            int j = i & (M - 1);
            acc += s.mkIMax(xs[j], ys[j]);
        }
        return acc;
    }

    private long instantiate(int count) {
        LevelStore s = ov;
        long acc = 0;
        for (int i = 0; i < count; i++) {
            int j = i & (M - 1);
            acc += s.instantiate(xs[j], ys[j]);
        }
        return acc;
    }

    private long instantiateIdentity(int count) {
        LevelStore s = ov;
        long acc = 0;
        for (int i = 0; i < count; i++) {
            int j = i & (M - 1);
            acc += s.instantiate(xs[j], 0) + ys[j];
        }
        return acc;
    }

    private long instantiateList(int count) {
        LevelStore s = ov;
        long acc = 0;
        for (int i = 0; i < count; i++) {
            int j = i & (M - 1);
            acc += s.instantiateList(xs[j], ys[j]);
        }
        return acc;
    }

    private long paramsDeclared(int count) {
        LevelStore s = ov;
        long acc = 0;
        int list = names3;
        for (int i = 0; i < count; i++) {
            int j = i & (M - 1);
            acc += s.undeclaredParam(xs[j], list) + ys[j];
        }
        return acc;
    }

    /**
     * Cold paths: every 16 iterations the overlay is reset; each iteration builds a level that is
     * not in the base and not yet in the overlay (1 or 2 new nodes on top of base nodes:
     * {@code max (a+2) (b+1)}, {@code (max (a+2) b)+1}, {@code imax (a+2) b},
     * {@code max (a+2) (max b w)}) and then, by mode: 0 nothing more, 1 normalize, 2 canonId,
     * 3 isEquiv against a second level {@code max (b+1) (a+2)} that is interned here too (two cold
     * canonical forms; equal for one of the four shapes). Costs of modes 1-3 are differences to mode 0.
     */
    private long cold(int count, int round, int mode) {
        LevelStore s = ov;
        long acc = 0;
        for (int i = 0; i < count; i++) {
            if ((i & 15) == 0) {
                s.reset();
            }
            int j = (i + round) & (M - 1);
            int a = s.succ(s.succ(xs[j]));
            int b = ys[j];
            int l;
            switch (i & 3) {
                case 0:
                    l = s.max(a, s.succ(b));
                    break;
                case 1:
                    l = s.succ(s.max(a, b));
                    break;
                case 2:
                    l = s.imax(a, b);
                    break;
                default:
                    l = s.max(a, s.max(b, w));
                    break;
            }
            if (mode == 1) {
                acc += s.normalize(l);
            } else if (mode == 2) {
                acc += s.canonId(l);
            } else if (mode == 3) {
                acc += s.isEquiv(l, s.max(s.succ(b), a)) ? 1 : 0;
            } else {
                acc += l;
            }
        }
        return acc;
    }

    /**
     * Cold instantiation: every 16 iterations the overlay is reset; each iteration interns a
     * substitution {@code [1,2,3 := a+1, b, max a b]} (new in the overlay) and, if
     * {@code instantiate}, applies it to {@code max (u+1) v} and to {@code imax u w} (two cache
     * misses, results new). "cold-instantiate" minus "cold-subst" is the cost of two cold
     * instantiations.
     */
    private long coldInstantiate(int count, int round, boolean instantiate) {
        LevelStore s = ov;
        long acc = 0;
        int l1 = 0;
        int l2 = 0;
        int[] buf = new int[3];
        for (int i = 0; i < count; i++) {
            if ((i & 15) == 0) {
                s.reset();
                l1 = s.max(s.succ(u), v);
                l2 = s.imax(u, w);
            }
            int j = (i + round) & (M - 1);
            buf[0] = s.succ(xs[j]);
            buf[1] = ys[j];
            buf[2] = s.max(xs[j], ys[j]);
            int subst = s.mkSubst(names3, s.internList(buf, 0, 3));
            if (instantiate) {
                acc += s.instantiate(l1, subst) + s.instantiate(l2, subst);
            } else {
                acc += subst;
            }
        }
        return acc;
    }
}
