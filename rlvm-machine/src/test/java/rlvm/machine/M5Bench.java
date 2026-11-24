package rlvm.machine;

import rlvm.api.Outcome;

/**
 * A plain main for measuring the machine alone on one export (work package M5; not a unit test): loads the
 * file, runs the environment pass and checks every unit with the machine in one thread, once or several
 * times, and prints wall time, ticks and call events by op. For profiles (JFR) of INFER / CHECK.
 * <pre>
 * java -cp CLASSPATH -Drlvm.root=ROOT [-Dm5.switches=gated,expect] [-Dm5.rounds=3] [-Dm5.stats=true] rlvm.machine.M5Bench FILE
 * </pre>
 * A/B in one process (warm, one worker: the JIT state is shared, rounds alternate): {@code -Dm5.switchesB=..} gives the
 * odd rounds their own switch set; {@code -Dm5.icA=true|false} / {@code -Dm5.icB=..} set the system property of INFER's
 * front cache ({@code rlvm.machine.m5ic}) for the even / odd rounds. Round 0 and 1 are the warm-up of each side.
 */
final class M5Bench {
    private M5Bench() {}

    public static void main(String[] args) throws Exception {
        String file = args[0];
        int rounds = Integer.getInteger("m5.rounds", 1);
        String switchesA = System.getProperty("m5.switches", "");
        String switchesB = System.getProperty("m5.switchesB", switchesA);
        boolean stats = Boolean.getBoolean("m5.stats");
        for (int round = 0; round < rounds; round++) {
            boolean sideB = (round & 1) != 0;
            String switches = sideB ? switchesB : switchesA;
            String ic = System.getProperty(sideB ? "m5.icB" : "m5.icA");
            if (ic != null) {
                System.setProperty("rlvm.machine.m5ic", ic);
            }
            long t0 = System.nanoTime();
            Fixture g = Fixture.load(file);
            long t1 = System.nanoTime();
            g.switches(switches);
            if (!System.getProperty("m5.debug", "").isEmpty()) {
                g.debug(System.getProperty("m5.debug"));
            }
            g.m.stats = stats;
            g.sessionBytes = Long.getLong("m5.sessionBytes", 16L << 20);
            long ticks0 = g.m.ticks();
            java.lang.management.ThreadMXBean mx = java.lang.management.ManagementFactory.getThreadMXBean();
            long c1 = mx.getCurrentThreadCpuTime();
            Fixture.Verdict v = g.checkAll();
            long t2 = System.nanoTime();
            long c2 = mx.getCurrentThreadCpuTime();
            System.out.printf("round %d [%s%s]: load %.2f s, check %.2f s wall / %.2f s CPU of this thread, %s after %d units, ticks %d, "
                    + "accepted %d, rejected %d, declined %d, frames written %d, resets %d%n", round, switches,
                    ic == null ? "" : " ic=" + ic, (t1 - t0) / 1e9,
                    (t2 - t1) / 1e9, (c2 - c1) / 1e9,
                    Outcome.name(v.outcome), v.units, g.m.ticks() - ticks0, g.session.accepted, g.session.rejected,
                    g.session.declined, g.m.framesWritten, g.m.resets);
            if (v.outcome != Outcome.ACCEPT) {
                System.out.println("  " + v);
            }
            long records = g.m.recordsMade + (g.m.vp - g.m.vbase);
            long runWords = g.m.runWordsMade + (g.m.rsp - g.m.rbase);
            System.out.printf("  made: records %d (%.1f per unit), run words %d (%.1f), pairs %d, facts derived %d%n", records,
                    records / (double) Math.max(1, v.units), runWords, runWords / (double) Math.max(1, v.units),
                    g.m.pairsMade + g.m.pairUsed, g.m.x5.factsDerived);
            if (g.m.audits != 0) {
                System.out.printf("  audits %d%n", g.m.audits);
            }
            if (g.m.x5.pchkHits != 0 || g.m.x5.pchkSets != 0) {
                System.out.printf("  PCHK: %d bits set, %d CHECKED inferences answered in ONLY mode%n", g.m.x5.pchkSets, g.m.x5.pchkHits);
            }
            if (g.m.x5.factsDerived != 0) {
                long[] u = g.m.x5.factUse;
                System.out.printf("  facts: %d derived (%d F_NONE) in %d ticks (%.2f%% of all ticks); prop mask %d queries / %d with a bit; "
                        + "unread mask %d / %d; static PROOF %d queries, %d yes, %d no%n", g.m.x5.factsDerived, g.m.factsNone, g.m.x5.sigdTicks,
                        100.0 * g.m.x5.sigdTicks / Math.max(1, g.m.ticks() - ticks0), u[Facts.USE_PROPMASK], u[Facts.USE_PROPMASK_SET],
                        u[Facts.USE_UNREAD], u[Facts.USE_UNREAD_SET], u[Facts.USE_PROOF], u[Facts.USE_PROOF_YES], u[Facts.USE_PROOF_NO]);
            }
            if (g.m.x5.icHits != 0) {
                System.out.printf("  INFER front cache: %d hits%n", g.m.x5.icHits);
            }
            if (g.m.x5.jointBinders != 0) {
                System.out.printf("  joint telescope (m5b): %d binders opened once for type and value%n", g.m.x5.jointBinders);
            }
            if (g.m.x5.matchTried != 0) {
                System.out.printf("  MATCH (m5a): %d domains tried, %d answered%n", g.m.x5.matchTried, g.m.x5.matchHit);
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
