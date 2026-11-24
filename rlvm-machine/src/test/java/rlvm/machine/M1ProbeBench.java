package rlvm.machine;

/**
 * The probe cost of ISA section 11 ("the probe cost as a number: ns and instructions per mkApp hit; target at
 * most 60 instructions for the inlined probe, 45 measured in the spike"), measured as the spike's
 * {@code micro} did (ISA 0.3: mkApp of an existing value, 4096 values, everything cached).
 *
 * <pre>
 * java -cp CP rlvm.machine.M1ProbeBench MODE N [small]
 *   loop      the driver loop alone: three argument words written to scratch, an accumulator (the baseline)
 *   app1/2/3  Values.mkApp of an existing application of a free variable to 1 / 2 / 3 arguments   (hit)
 *   ext2      Values.mkApp extending an existing 1-argument NEU by 2 arguments                  (hit)
 *   lam       Values.mkLam of an existing closure                                                (hit)
 *   prune3    Runs.prune of a 3-slot environment under mask 0b101 to an existing frame           (hit)
 *   clo3      prune3 + mkLam: the closure of a binder under a 3-slot environment                 (2 hits)
 *   sort      Values.mkSort through the sort cache                                               (hit)
 *   lamc      as lam, but the key of each probe depends on the result of the previous one (latency, not throughput)
 *   lamx, lamxc   experiment: lam / lamc with a three-multiplication hash in place of Hash.record
 *   miss2     Values.mkApp of a NEW 2-argument application, in sessions of 2^17 values + reset   (miss)
 *   sess1k    the same in sessions of 1024 values + reset: the v0 regime (one declaration per session); with
 *             the third argument "fit" on tables that just fit such a session instead of the sizes of ISA 1.6
 * </pre>
 * Prints {@code RESULT mode n=N acc=A values=V cpu-ns=.. wall-ns=..}: the CPU time of the measuring thread
 * and the elapsed time per iteration of the measured pass (after a warm-up pass of the same code); on a
 * loaded host only the CPU time means something. Instructions per operation are the difference of
 * two runs with different N under {@code perf stat} (bench/measure.sh in the arena container), minus the
 * same difference of {@code loop}: see {@code src/test/resources/m1/probe-cost.sh}.
 */
public final class M1ProbeBench {
    private M1ProbeBench() {}

    static final int VALUES = 4096;

    public static void main(String[] args) {
        String mode = args.length > 0 ? args[0] : "app2";
        long n = args.length > 1 ? Long.parseLong(args[1]) : 100_000_000L;
        // "small": tables that start small and have grown to fit the 4096 values (everything in the first-level
        // cache, the spike's setting); default: the session sizes of ISA 1.6 (vtab 2 MiB, rtab 1 MiB).
        boolean small = args.length > 2 && args[2].equals("small");
        long warm = Math.min(n, 20_000_000L);
        boolean fit = args.length > 2 && args[2].equals("fit");
        Machine m = small ? new Machine(smallSizes()) : fit ? new Machine(fitSizes()) : new Machine();
        setup(m, mode);
        run(m, mode, warm); // warm-up: the JIT compiles the loop with the probe inlined
        run(m, mode, warm);
        java.lang.management.ThreadMXBean tm = java.lang.management.ManagementFactory.getThreadMXBean();
        long c0 = tm.getCurrentThreadCpuTime();
        long t0 = System.nanoTime();
        long acc = run(m, mode, n);
        long t1 = System.nanoTime();
        long c1 = tm.getCurrentThreadCpuTime();
        long done = n;
        System.out.printf("RESULT %s n=%d acc=%d values=%d vtab=%d rtab=%d cpu-ns=%.3f wall-ns=%.3f%n", mode, done, acc,
                m.vp - m.vbase, m.vtab.length, m.rtab.length, (c1 - c0) / (double) done, (t1 - t0) / (double) done);
    }

    static Machine.Sizes smallSizes() {
        Machine.Sizes s = Machine.Sizes.standard();
        s.vtab = 6;
        s.rtab = 6;
        s.records = 6;
        s.runWords = 8;
        return s;
    }

    /** Tables that just fit a session of 1024 values without growing (vtab 16 KiB, vh 64 KiB, rs 64 KiB). */
    static Machine.Sizes fitSizes() {
        Machine.Sizes s = Machine.Sizes.standard();
        s.vtab = 12;
        s.rtab = 10;
        s.records = 11;
        s.runWords = 14;
        return s;
    }

    static int x;
    static int term = 1234;

    /** Makes the 4096 values of the measured mode, and nothing else, so that the working set is the mode's own. */
    static void setup(Machine m, String mode) {
        m.ensureScratch(8);
        m.ensureEnv(8);
        int ty = Values.mkSort(m, 5);
        x = Values.mkFVar(m, ty, 0);
        for (int i = 0; i < VALUES; i++) {
            m.X[0] = Layout.imm(i);
            m.X[1] = Layout.imm(i * 7);
            m.X[2] = x;
            m.es[0] = Layout.imm(i);
            m.es[1] = Layout.imm(i * 7);
            m.es[2] = x;
            switch (mode) {
                case "app1":
                    Values.mkApp(m, x, 0, 1);
                    break;
                case "app2":
                    Values.mkApp(m, x, 0, 2);
                    break;
                case "app3":
                    Values.mkApp(m, x, 0, 3);
                    break;
                case "ext2": {
                    int a1 = Values.mkApp(m, x, 0, 1);
                    m.X[0] = Layout.imm(i * 7);
                    m.X[1] = x;
                    Values.mkApp(m, a1, 0, 2);
                    break;
                }
                case "lam":
                case "lamc":
                    Values.mkLam(m, term + 1 + i, 0, 0);
                    break;
                case "lamx":
                case "lamxc":
                    recAlt(m, Layout.LAM << Layout.KIND_SHIFT | Layout.W0_WHNF, term + 1 + i, 0, 0);
                    break;
                case "prune3":
                    Runs.prune(m, 0, 3, 0b101L);
                    break;
                case "clo3":
                    Values.mkLam(m, term, Runs.prune(m, 0, 3, 0b101L), 0);
                    break;
                case "sort":
                    Values.mkSort(m, 100 + i);
                    break;
                default:
                    break;
            }
        }
    }

    static long run(Machine m, String mode, long n) {
        switch (mode) {
            case "loop":
                return loop(m, n);
            case "app1":
                return app(m, n, 1);
            case "app2":
                return app(m, n, 2);
            case "app3":
                return app(m, n, 3);
            case "ext2":
                return ext2(m, n);
            case "lam":
                return lam(m, n);
            case "prune3":
                return prune3(m, n);
            case "clo3":
                return clo3(m, n);
            case "sort":
                return sort(m, n);
            case "lamc":
                return lamChained(m, n, false);
            case "lamx":
                return lamAlt(m, n, false);
            case "lamxc":
                return lamAlt(m, n, true);
            case "miss2":
                return miss2(m, n, 1 << 17);
            case "sess1k":
                return miss2(m, n, 1 << 10);
            default:
                throw new IllegalArgumentException(mode);
        }
    }

    static long loop(Machine m, long n) {
        long acc = 0;
        int[] xs = m.X;
        int xv = x;
        for (long k = 0; k < n; k++) {
            int i = (int) k & (VALUES - 1);
            xs[0] = Layout.imm(i);
            xs[1] = Layout.imm(i * 7);
            xs[2] = xv;
            acc += xs[0] + xs[1];
        }
        return acc;
    }

    static long app(Machine m, long n, int arity) {
        long acc = 0;
        int[] xs = m.X;
        int xv = x;
        for (long k = 0; k < n; k++) {
            int i = (int) k & (VALUES - 1);
            xs[0] = Layout.imm(i);
            xs[1] = Layout.imm(i * 7);
            xs[2] = xv;
            acc += Values.mkApp(m, xv, 0, arity);
        }
        return acc;
    }

    static long ext2(Machine m, long n) {
        long acc = 0;
        int[] xs = m.X;
        int xv = x;
        int[] heads = new int[VALUES];
        for (int i = 0; i < VALUES; i++) {
            xs[0] = Layout.imm(i);
            heads[i] = Values.mkApp(m, xv, 0, 1);
        }
        for (long k = 0; k < n; k++) {
            int i = (int) k & (VALUES - 1);
            xs[0] = Layout.imm(i * 7);
            xs[1] = xv;
            xs[2] = xv;
            acc += Values.mkApp(m, heads[i], 0, 2);
        }
        return acc;
    }

    static long lam(Machine m, long n) {
        long acc = 0;
        int[] xs = m.X;
        int xv = x;
        for (long k = 0; k < n; k++) {
            int i = (int) k & (VALUES - 1);
            xs[0] = Layout.imm(i);
            xs[1] = Layout.imm(i * 7);
            xs[2] = xv;
            acc += Values.mkLam(m, term + 1 + i, 0, 0);
        }
        return acc;
    }

    /**
     * The fixed-size probe in a DEPENDENT chain: the key of every probe depends on the result of the one before
     * (as in real code, where a constructed value is the operand of the next construction), so that the cost is
     * the latency of hash + table + record, not the throughput of independent probes.
     */
    static long lamChained(Machine m, long n, boolean unused) {
        long acc = 0;
        int prev = 0;
        for (long k = 0; k < n; k++) {
            int i = ((int) k + prev) & (VALUES - 1);
            prev = Values.mkLam(m, term + 1 + i, 0, 0);
            acc += prev;
        }
        return acc;
    }

    /** Experiment (NOTES-M1 section 3): the probe of Values.rec with a three-multiplication hash. */
    static int hashAlt(int w0, int a, int b, int c) {
        int h = (a ^ (w0 & Hash.W0_KEY_MASK)) * 0x9E3779B1 + b;
        h = (h ^ (h >>> 15)) * 0x85EBCA6B + c;
        h = (h ^ (h >>> 13)) * 0xC2B2AE35;
        return h ^ (h >>> 16);
    }

    static int recAlt(Machine m, int w0, int a, int b, int c) {
        int hash = hashAlt(w0, a, b, c);
        int[] tab = m.vtab;
        int[] v = m.vh;
        int mask = tab.length - 1;
        int slot = hash & mask;
        int key0 = w0 & Hash.W0_KEY_MASK;
        for (;;) {
            int e = tab[slot];
            if (e == 0) {
                return m.addRecordAt(slot, hash, w0, a, b, c); // no growth in this benchmark: the table is not rehashed
            }
            if (((e ^ hash) >>> Layout.TAB_TAG_SHIFT) == 0) {
                int o = (e & Layout.TAB_INDEX_MASK) << Layout.REC_SHIFT;
                if (v[o + Layout.A] == a && v[o + Layout.B] == b && v[o + Layout.C] == c
                        && (v[o] & Hash.W0_KEY_MASK) == key0) {
                    return e & Layout.TAB_INDEX_MASK;
                }
            }
            slot = (slot + 1) & mask;
        }
    }

    static long lamAlt(Machine m, long n, boolean chained) {
        long acc = 0;
        int prev = 0;
        int w0 = Layout.LAM << Layout.KIND_SHIFT | Layout.W0_WHNF;
        for (long k = 0; k < n; k++) {
            int i = ((int) k + (chained ? prev : 0)) & (VALUES - 1);
            prev = recAlt(m, w0, term + 1 + i, 0, 0);
            acc += prev;
        }
        return acc;
    }

    static long prune3(Machine m, long n) {
        long acc = 0;
        int[] es = m.es;
        int xv = x;
        for (long k = 0; k < n; k++) {
            int i = (int) k & (VALUES - 1);
            es[0] = Layout.imm(i);
            es[1] = Layout.imm(i * 7);
            es[2] = xv;
            acc += Runs.prune(m, 0, 3, 0b101L);
        }
        return acc;
    }

    static long clo3(Machine m, long n) {
        long acc = 0;
        int[] es = m.es;
        int xv = x;
        for (long k = 0; k < n; k++) {
            int i = (int) k & (VALUES - 1);
            es[0] = Layout.imm(i);
            es[1] = Layout.imm(i * 7);
            es[2] = xv;
            acc += Values.mkLam(m, term, Runs.prune(m, 0, 3, 0b101L), 0);
        }
        return acc;
    }

    static long sort(Machine m, long n) {
        long acc = 0;
        int[] xs = m.X;
        int xv = x;
        for (long k = 0; k < n; k++) {
            int i = (int) k & 31;
            xs[0] = Layout.imm(i);
            xs[1] = Layout.imm(i * 7);
            xs[2] = xv;
            acc += Values.mkSort(m, 100 + i);
        }
        return acc;
    }

    /**
     * New 2-argument applications: sessions of 2^17 values each, then a reset (the life of a real session: the
     * tables stay at their base size, the reset is O(used) and is part of the cost of a value).
     */
    static long miss2(Machine m, long n, int round) {
        long acc = 0;
        for (long k = 0; k < n;) {
            m.reset();
            m.ensureScratch(8);
            int ty = Values.mkSort(m, 5);
            int xv = Values.mkFVar(m, ty, 0);
            int[] xs = m.X;
            for (int j = 0; j < round && k < n; j++, k++) {
                xs[0] = Layout.imm(j);
                xs[1] = Layout.imm(j >>> 3);
                xs[2] = xv;
                acc += Values.mkApp(m, xv, 0, 2);
            }
        }
        return acc;
    }
}
