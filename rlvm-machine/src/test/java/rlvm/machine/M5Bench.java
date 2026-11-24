package rlvm.machine;

import rlvm.api.Outcome;

/**
 * A plain main for measuring the machine alone on one export (work package M5; not a unit test): loads the
 * file, runs the environment pass and checks every unit with the machine in one thread, once or several
 * times, and prints wall time, ticks and call events by op. For profiles (JFR) of INFER / CHECK.
 * <pre>
 * java -cp CLASSPATH -Drlvm.root=ROOT [-Dm5.switches=gated,expect] [-Dm5.rounds=3] [-Dm5.stats=true] rlvm.machine.M5Bench FILE
 * </pre>
 */
final class M5Bench {
    private M5Bench() {}

    public static void main(String[] args) throws Exception {
        String file = args[0];
        int rounds = Integer.getInteger("m5.rounds", 1);
        String switches = System.getProperty("m5.switches", "");
        boolean stats = Boolean.getBoolean("m5.stats");
        for (int round = 0; round < rounds; round++) {
            long t0 = System.nanoTime();
            Fixture g = Fixture.load(file);
            long t1 = System.nanoTime();
            g.switches(switches);
            g.m.stats = stats;
            g.sessionBytes = Long.getLong("m5.sessionBytes", 16L << 20);
            long ticks0 = g.m.ticks();
            java.lang.management.ThreadMXBean mx = java.lang.management.ManagementFactory.getThreadMXBean();
            long c1 = mx.getCurrentThreadCpuTime();
            Fixture.Verdict v = g.checkAll();
            long t2 = System.nanoTime();
            long c2 = mx.getCurrentThreadCpuTime();
            System.out.printf("round %d: load %.2f s, check %.2f s wall / %.2f s CPU of this thread, %s after %d units, ticks %d, "
                    + "accepted %d, rejected %d, declined %d, frames written %d, resets %d%n", round, (t1 - t0) / 1e9,
                    (t2 - t1) / 1e9, (c2 - c1) / 1e9,
                    Outcome.name(v.outcome), v.units, g.m.ticks() - ticks0, g.session.accepted, g.session.rejected,
                    g.session.declined, g.m.framesWritten, g.m.resets);
            if (v.outcome != Outcome.ACCEPT) {
                System.out.println("  " + v);
            }
            if (stats) {
                StringBuilder sb = new StringBuilder("  calls:");
                for (int op = 0; op < Op.OP_COUNT; op++) {
                    if (g.m.calls[op] != 0) {
                        sb.append(' ').append(Op.name(op)).append('=').append(g.m.calls[op]);
                    }
                }
                System.out.println(sb);
            }
            g.close();
        }
    }
}
