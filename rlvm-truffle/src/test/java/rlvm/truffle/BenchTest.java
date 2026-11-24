package rlvm.truffle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Locale;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A measuring tool, not a test of anything but equal verdicts: several passes over one export with ONE
 * engine per configuration (pass 1 cold, later passes warm: the code store, the host JIT and the tier's
 * installed blocks are kept; OWNER-NOTES 12), the base configuration and the tier side by side, passes
 * interleaved. One worker thread; thread CPU time and wall clock per pass.
 *
 * <pre>
 * $MVN -o -q -f rlvm-truffle/pom.xml test -Dgroups=slow -DexcludedGroups= -Dtest=BenchTest \
 *     -Dt2.file=work/exports/reachability.ndjson [-Dt2.switches=code,casetrees] [-Dt2.passes=5] [-Drlvm.t2.*=..]
 * </pre>
 */
class BenchTest {

    @Test
    @Tag("slow")
    void bench() throws Exception {
        String f = System.getProperty("t2.file");
        assumeTrue(f != null && Harness.exists(f));
        String sw = System.getProperty("t2.switches", "code,casetrees");
        int passes = Integer.getInteger("t2.passes", 5);
        StringBuilder sb = new StringBuilder();
        try (Harness base = new Harness(f, sw); Harness tier = new Harness(f, sw + ",t2")) {
            double[][] cpu = new double[2][passes];
            double[][] wall = new double[2][passes];
            int accepted = -1;
            for (int p = 0; p < passes; p++) {
                for (int k = 0; k < 2; k++) {
                    Harness h = k == 0 ? base : tier;
                    Harness.Run r = p == 0 ? h.run() : h.again(false);
                    cpu[k][p] = r.cpuNanos / 1e6;
                    wall[k][p] = r.wallNanos / 1e6;
                    if (accepted < 0) {
                        accepted = r.accepted;
                    }
                    assertEquals(accepted, r.accepted, "units accepted, configuration " + k + " pass " + p);
                    if (k == 1) {
                        settle(h.t2());
                    }
                }
            }
            T2Engine e = tier.t2();
            sb.append(String.format(Locale.ROOT, "[bench] %s switches %s, %d units accepted, one worker; thread CPU ms (wall ms) per pass%n", f, sw, accepted));
            for (int k = 0; k < 2; k++) {
                sb.append(k == 0 ? "[bench]   base:" : "[bench]   t2:  ");
                for (int p = 0; p < passes; p++) {
                    sb.append(String.format(Locale.ROOT, " %8.0f (%.0f)", cpu[k][p], wall[k][p]));
                }
                sb.append('\n');
            }
            double b = best(cpu[0]);
            double t = best(cpu[1]);
            sb.append(String.format(Locale.ROOT, "[bench]   best warm pass: base %.0f ms, t2 %.0f ms, ratio %.3f | first pass: base %.0f, t2 %.0f%n", b, t, t / b, cpu[0][0],
                    cpu[1][0]));
            if (e != null) {
                sb.append(e.report());
            }
        }
        System.out.print(sb);
    }

    private static double best(double[] a) {
        double m = Double.MAX_VALUE;
        for (int i = 1; i < a.length; i++) {
            m = Math.min(m, a[i]);
        }
        return a.length > 1 ? m : a[0];
    }

    /** Waits until no compilation of the tier is pending (at most 60 s), so that the next pass is steady state. */
    static void settle(T2Engine e) throws InterruptedException {
        if (e == null) {
            return;
        }
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (e.promotedCount() > e.installedCount() + e.failedCount() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }
}
