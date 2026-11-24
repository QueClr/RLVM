package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import rlvm.admit.Admission;
import rlvm.admit.Policy;
import rlvm.api.Budget;
import rlvm.api.EngineConfig;
import rlvm.api.Outcome;
import rlvm.api.RunSignals;
import rlvm.api.WorkerContext;

/**
 * CPU time of the machine with and without the K1 roles on a whole export (OWNER-NOTES 10: CPU time is
 * the metric): the units are checked by one machine session on one thread, the CPU time of that thread
 * is read from the JVM ({@code ThreadMXBean}), passes with the switch sets under comparison are
 * interleaved. Every pass uses a fresh engine (so code blocks are elaborated inside the measured time)
 * and must accept every unit. The first pass of each set is the warm-up of the JIT and is reported
 * separately. Host JVM, not the arena container.
 */
class M7BenchTest {

    /**
     * One pass over every unit; returns {cpu ns, units accepted, blocks, activations entered, ticks, KiB of session
     * memory made (records, runs, pairs: the memory traffic of the pass, which the host's load does not disturb)}.
     */
    private static long[] pass(M7World w, String switches, String elabAt) {
        String oldSw = System.getProperty("rlvm.machine.switches");
        String oldAt = System.getProperty("rlvm.machine.elabAt");
        System.setProperty("rlvm.machine.switches", switches);
        System.setProperty("rlvm.machine.elabAt", elabAt);
        try {
            WorkerContext ctx = w.bindings.newWorkerContext(7, w.store, RunSignals.NONE, 1L << 30);
            MachineEngine engine = new MachineEngine(w.store, EngineConfig.defaults());
            MachineSession s = (MachineSession) engine.newSession(ctx);
            Admission admission = new Admission(ctx, Policy.arena(), null);
            Budget budget = new Budget(2_000_000_000L, 2L << 30, Integer.MAX_VALUE);
            ThreadMXBean mx = ManagementFactory.getThreadMXBean();
            int units = w.store.loadedUnits();
            long accepted = 0;
            long t0 = mx.getCurrentThreadCpuTime();
            for (int u = 0; u < units; u++) {
                int o = admission.admit(u, s, budget);
                if (o == Outcome.ACCEPT) {
                    accepted++;
                } else {
                    s.reset();
                    ctx.reset();
                }
                if (s.footprintBytes() > (16L << 20)) {
                    s.reset();
                    ctx.reset();
                }
            }
            long cpu = mx.getCurrentThreadCpuTime() - t0;
            long[] r = {cpu, accepted, engine.code.blockCount(), s.m.x7.entered, s.m.ticks(),
                (s.m.bytesAtReset + s.m.engineBytes()) >> 10, s.m.x7.matchT, s.m.x7.matchF, s.m.x7.targSame, s.m.x7.targConv};
            s.close();
            return r;
        } finally {
            if (oldSw == null) {
                System.clearProperty("rlvm.machine.switches");
            } else {
                System.setProperty("rlvm.machine.switches", oldSw);
            }
            if (oldAt == null) {
                System.clearProperty("rlvm.machine.elabAt");
            } else {
                System.setProperty("rlvm.machine.elabAt", oldAt);
            }
        }
    }

    private static void bench(String name, String path, String[][] sets, int rounds) throws Exception {
        assumeTrue(Fixture.exists(path));
        assumeTrue(M7HookedTest.hooked(), "DELTA does not enter blocks in this tree");
        M7World w = M7World.load(path);
        int units = w.store.loadedUnits();
        StringBuilder sb = new StringBuilder();
        sb.append(name).append(": ").append(units).append(" units, one session, one thread, CPU time of that thread (ms)\n");
        long accepted = -1;
        long[][] best = new long[sets.length][];
        long[] first = new long[sets.length];
        for (int round = 0; round <= rounds; round++) {
            for (int i = 0; i < sets.length; i++) {
                long[] r = pass(w, sets[i][0], sets[i][1]);
                if (accepted < 0) {
                    accepted = r[1];
                }
                // every configuration accepts the same units (on a good export: all of them)
                assertEquals(accepted, r[1], "units accepted with switches '" + sets[i][0] + "'");
                if (round == 0) {
                    first[i] = r[0];
                } else if (best[i] == null || r[0] < best[i][0]) {
                    best[i] = r;
                }
            }
        }
        sb.append("  units accepted by every configuration: ").append(accepted).append('\n');
        for (int i = 0; i < sets.length; i++) {
            sb.append(String.format("  %-34s ELAB_AT %-2s  first pass %6d ms, best of %d: %6d ms (%.2fx of the first set), %d blocks, "
                    + "%d activations, %d ticks, %d KiB of session memory made (%.2fx)%n",
                    sets[i][0].isEmpty() ? "(v0: all switches off)" : sets[i][0], sets[i][1],
                    first[i] / 1_000_000, rounds, best[i][0] / 1_000_000, (double) best[i][0] / best[0][0], best[i][2], best[i][3],
                    best[i][4], best[i][5], (double) best[i][5] / best[0][5]));
            if (best[i][6] + best[i][7] + best[i][8] + best[i][9] != 0) {
                sb.append(String.format("      TELE: MATCH answered T %d times, unknown %d; the argument's type was the built domain itself %d"
                        + " times, CONV was called %d times%n", best[i][6], best[i][7], best[i][8], best[i][9]));
            }
        }
        System.out.print(sb);
        java.nio.file.Files.writeString(Fixture.root().resolve("work/status/machine-M7-bench-" + name + ".txt"), sb.toString());
    }

    private static final String[][] SETS = {
        {"", "10"},
        {"tele", "10"},
        {"code", "10"},
        {"code,casetrees", "10"},
        {"code,tele,casetrees", "10"},
        {"code,tele,casetrees", "4"},
        {"code,tele,casetrees", "1"},
    };

    @Test
    void prelude() throws Exception {
        bench("init-prelude", "work/arena-site/tests/good/init-prelude.ndjson", SETS, 3);
    }

    private static final String[][] BIG = {
        {"", "10"},
        {"tele", "10"},
        {"code,casetrees", "10"},
        {"code,tele,casetrees", "10"},
        {"code,tele,casetrees", "1"},
    };

    @Test
    @Tag("slow")
    void init() throws Exception {
        bench("init", "work/exports/init.ndjson", BIG, Integer.getInteger("m7.rounds", 2));
    }

    /** Closed evaluation (ISA 0.1: the fast benchmark of the reduction roles). */
    @Test
    @Tag("slow")
    void reachability() throws Exception {
        bench("reachability", "work/exports/reachability.ndjson", new String[][] {{"", "10"}, {"code", "10"},
            {"code,casetrees", "10"}, {"code,tele,casetrees", "10"}}, Integer.getInteger("m7.rounds", 1));
    }

    @Test
    @Tag("slow")
    void cedarHot() throws Exception {
        bench("cedar-hot-ax", "work/workloads/slices/cedar-hot-ax.ndjson", BIG, Integer.getInteger("m7.rounds", 2));
    }
}
