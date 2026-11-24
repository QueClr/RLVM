package rlvm.machine;

import static rlvm.machine.Op.SIG;

import java.util.Arrays;
import java.util.Locale;

/**
 * What the two skeleton deviations from the measured convention cost in CPU time (NOTES-M6 S3, S4;
 * stepper-convention.md H4, H9, H11): the depth bound as a per-machine field ({@code d > m.hostDepth})
 * instead of the constant ({@code d > Op.HOST_DEPTH}), and the masked TICK line
 * ({@code (--m.fuel & POLL_MASK) == POLL_MASK && m.tickSlow()}) instead of {@code --m.fuel < 0}.
 * Owner: M8. Run by {@code rlvm-machine/src/test/resources/m8/convcost.sh}.
 *
 * <p>The same toy judgment is written five times, differing only in those two lines:
 * <pre>
 * FM   field bound, masked tick     as the machine is built
 * CM   constant bound, masked tick
 * FP   field bound, plain tick
 * CP   constant bound, plain tick   the convention as measured by spikes/stepper
 * R    no tick, no depth test, no signal test: plain host recursion (the scale)
 * </pre>
 * Two workloads: {@code calls} = a binary recursion that does nothing but call (every judgment is a
 * non-leaf with two sub-judgments and no work of its own: the convention's lines are all there is, an
 * upper bound on their relative cost); {@code intern} = the same recursion whose every judgment interns
 * one record through the reference probe (the smallest real work a judgment of the machine does; ISA 0.3:
 * a probe hit costs about 3 ns). Each variant is run for a number of rounds, interleaved, in one JVM;
 * the figure is the minimum time of a pass (the host is shared), reported relative to CP.
 *
 * <p>This is a micro-benchmark of the two lines, not of the machine: the measurement on real judgments
 * is the variant-jar run of {@code convcost.sh --corpus} once the machine accepts a corpus.
 */
final class M8ConvCost {
    private M8ConvCost() {}

    static final int K = 0x7A << 4; // a state key of family EXT; never resumed here (D = 64 is never reached by depth 30)

    // ---- workload "calls": T(n) = T(n-1) + T(n-2), leaves inline -------------------------------------

    static int fm(Machine m, int n, int d) {
        if (n < 2) {
            return n;
        }
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig;
        }
        if (m.stats) {
            m.call(0x7A);
        }
        if (d > m.hostDepth) {
            return m.spill1(K, n);
        }
        int a = fm(m, n - 1, d + 1);
        return a >= SIG ? m.sig1(a, K | 1, n) : k_fm1(m, n, a, d);
    }

    static int k_fm1(Machine m, int n, int a, int d) {
        int b = fm(m, n - 2, d + 1);
        return b >= SIG ? m.sig1(b, K | 2, a) : (a + b) & 0xFFFFF;
    }

    static int cm(Machine m, int n, int d) {
        if (n < 2) {
            return n;
        }
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig;
        }
        if (m.stats) {
            m.call(0x7A);
        }
        if (d > Op.HOST_DEPTH) {
            return m.spill1(K, n);
        }
        int a = cm(m, n - 1, d + 1);
        return a >= SIG ? m.sig1(a, K | 1, n) : k_cm1(m, n, a, d);
    }

    static int k_cm1(Machine m, int n, int a, int d) {
        int b = cm(m, n - 2, d + 1);
        return b >= SIG ? m.sig1(b, K | 2, a) : (a + b) & 0xFFFFF;
    }

    static int fp(Machine m, int n, int d) {
        if (n < 2) {
            return n;
        }
        if (--m.fuel < 0 && m.tickSlow()) {
            return m.sig;
        }
        if (m.stats) {
            m.call(0x7A);
        }
        if (d > m.hostDepth) {
            return m.spill1(K, n);
        }
        int a = fp(m, n - 1, d + 1);
        return a >= SIG ? m.sig1(a, K | 1, n) : k_fp1(m, n, a, d);
    }

    static int k_fp1(Machine m, int n, int a, int d) {
        int b = fp(m, n - 2, d + 1);
        return b >= SIG ? m.sig1(b, K | 2, a) : (a + b) & 0xFFFFF;
    }

    static int cp(Machine m, int n, int d) {
        if (n < 2) {
            return n;
        }
        if (--m.fuel < 0 && m.tickSlow()) {
            return m.sig;
        }
        if (m.stats) {
            m.call(0x7A);
        }
        if (d > Op.HOST_DEPTH) {
            return m.spill1(K, n);
        }
        int a = cp(m, n - 1, d + 1);
        return a >= SIG ? m.sig1(a, K | 1, n) : k_cp1(m, n, a, d);
    }

    static int k_cp1(Machine m, int n, int a, int d) {
        int b = cp(m, n - 2, d + 1);
        return b >= SIG ? m.sig1(b, K | 2, a) : (a + b) & 0xFFFFF;
    }

    static int r(Machine m, int n) {
        if (n < 2) {
            return n;
        }
        return (r(m, n - 1) + r(m, n - 2)) & 0xFFFFF;
    }

    // ---- workload "intern": the same recursion, every judgment interns the record (n, a, b) ---------------

    private static int rec(Machine m, int n, int a, int b) {
        return m.internRecord(Layout.w0(Layout.NSUC, 0, 0), n, a & 0xFF, b & 0xFF) & 0xFFFFF;
    }

    static int ifm(Machine m, int n, int d) {
        if (n < 2) {
            return n;
        }
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig;
        }
        if (m.stats) {
            m.call(0x7A);
        }
        if (d > m.hostDepth) {
            return m.spill1(K, n);
        }
        int a = ifm(m, n - 1, d + 1);
        return a >= SIG ? m.sig1(a, K | 1, n) : k_ifm1(m, n, a, d);
    }

    static int k_ifm1(Machine m, int n, int a, int d) {
        int b = ifm(m, n - 2, d + 1);
        return b >= SIG ? m.sig1(b, K | 2, a) : rec(m, n, a, b);
    }

    static int icm(Machine m, int n, int d) {
        if (n < 2) {
            return n;
        }
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig;
        }
        if (m.stats) {
            m.call(0x7A);
        }
        if (d > Op.HOST_DEPTH) {
            return m.spill1(K, n);
        }
        int a = icm(m, n - 1, d + 1);
        return a >= SIG ? m.sig1(a, K | 1, n) : k_icm1(m, n, a, d);
    }

    static int k_icm1(Machine m, int n, int a, int d) {
        int b = icm(m, n - 2, d + 1);
        return b >= SIG ? m.sig1(b, K | 2, a) : rec(m, n, a, b);
    }

    static int ifp(Machine m, int n, int d) {
        if (n < 2) {
            return n;
        }
        if (--m.fuel < 0 && m.tickSlow()) {
            return m.sig;
        }
        if (m.stats) {
            m.call(0x7A);
        }
        if (d > m.hostDepth) {
            return m.spill1(K, n);
        }
        int a = ifp(m, n - 1, d + 1);
        return a >= SIG ? m.sig1(a, K | 1, n) : k_ifp1(m, n, a, d);
    }

    static int k_ifp1(Machine m, int n, int a, int d) {
        int b = ifp(m, n - 2, d + 1);
        return b >= SIG ? m.sig1(b, K | 2, a) : rec(m, n, a, b);
    }

    static int icp(Machine m, int n, int d) {
        if (n < 2) {
            return n;
        }
        if (--m.fuel < 0 && m.tickSlow()) {
            return m.sig;
        }
        if (m.stats) {
            m.call(0x7A);
        }
        if (d > Op.HOST_DEPTH) {
            return m.spill1(K, n);
        }
        int a = icp(m, n - 1, d + 1);
        return a >= SIG ? m.sig1(a, K | 1, n) : k_icp1(m, n, a, d);
    }

    static int k_icp1(Machine m, int n, int a, int d) {
        int b = icp(m, n - 2, d + 1);
        return b >= SIG ? m.sig1(b, K | 2, a) : rec(m, n, a, b);
    }

    static int ir(Machine m, int n) {
        if (n < 2) {
            return n;
        }
        int a = ir(m, n - 1);
        int b = ir(m, n - 2);
        return rec(m, n, a, b);
    }

    // ---- the measurement --------------------------------------------------------------------------------------

    static final String[] NAMES = {"FM", "CM", "FP", "CP", "R"};

    private static int run(int workload, int variant, Machine m, int n) {
        if (workload == 0) {
            switch (variant) {
                case 0:
                    return fm(m, n, 0);
                case 1:
                    return cm(m, n, 0);
                case 2:
                    return fp(m, n, 0);
                case 3:
                    return cp(m, n, 0);
                default:
                    return r(m, n);
            }
        }
        switch (variant) {
            case 0:
                return ifm(m, n, 0);
            case 1:
                return icm(m, n, 0);
            case 2:
                return ifp(m, n, 0);
            case 3:
                return icp(m, n, 0);
            default:
                return ir(m, n);
        }
    }

    /**
     * {@code M8ConvCost [rounds [n]]}: prints one line per workload:
     * {@code workload judgments  ns/judgment FM CM FP CP R   ratio to CP: FM CM FP R}.
     */
    public static void main(String[] args) {
        int rounds = args.length > 0 ? Integer.parseInt(args[0]) : 15;
        int n = args.length > 1 ? Integer.parseInt(args[1]) : 32;
        java.lang.management.ThreadMXBean bean = java.lang.management.ManagementFactory.getThreadMXBean();
        System.out.println("# M8ConvCost: java " + System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version") + ", "
                + java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments() + ", rounds " + rounds + ", n " + n);
        for (int workload = 0; workload < 2; workload++) {
            Machine m = new Machine();
            m.hostDepth = Op.HOST_DEPTH;
            m.beginDecl(1, -1, Op.FUEL_UNLIMITED, Long.MAX_VALUE);
            // warm-up: every variant compiled before anything is timed
            int expect = run(workload, 4, m, 20);
            for (int w = 0; w < 40; w++) {
                for (int v = 0; v < 5; v++) {
                    if (run(workload, v, m, 20) != expect) {
                        throw new IllegalStateException("variant " + NAMES[v] + " computes another result");
                    }
                }
            }
            long[] best = new long[5];
            Arrays.fill(best, Long.MAX_VALUE);
            long judgments = 0;
            for (int round = 0; round < rounds; round++) {
                for (int i = 0; i < 5; i++) {
                    int v = (i + round) % 5; // rotate the order: no variant always runs after the same one
                    long ticks0 = m.ticks();
                    long t0 = bean.getCurrentThreadCpuTime();
                    int result = run(workload, v, m, n);
                    long t = bean.getCurrentThreadCpuTime() - t0;
                    if (result >= SIG) {
                        throw new IllegalStateException("signal in variant " + NAMES[v]);
                    }
                    if (v < 4) {
                        judgments = m.ticks() - ticks0;
                    }
                    best[v] = Math.min(best[v], t);
                }
            }
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.ROOT, "%-7s %11d judgments  ns/judgment:", workload == 0 ? "calls" : "intern", judgments));
            for (int v = 0; v < 5; v++) {
                sb.append(String.format(Locale.ROOT, " %s %.3f", NAMES[v], (double) best[v] / judgments));
            }
            sb.append("   ratio to CP:");
            for (int v = 0; v < 5; v++) {
                if (v != 3) {
                    sb.append(String.format(Locale.ROOT, " %s %.3f", NAMES[v], (double) best[v] / best[3]));
                }
            }
            System.out.println(sb);
        }
    }
}
