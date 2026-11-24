package rlvm.machine;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import rlvm.api.Outcome;

/**
 * One export in detail: the reproduction command of the {@code machine-diff-*} reports. Not a test of
 * anything (it skips itself without {@code -Dm8.file}); it prints, per unit that differs (or for the one
 * unit named by {@code -Dm8.unit=N}), both outcomes with reasons and messages, the ticks, the call events
 * by op and the frames written, under the host depth bound {@code -Dm8.hostDepth} (default 64).
 * <pre>
 * scripts/machine-sandbox.sh test Mk -Dtest=M8DiffOneTest -Dm8.file=work/arena-site/tests/good/tutorial/001_basicDef.ndjson [-Dm8.unit=3]
 * </pre>
 * {@code -Dm8.all=true} prints every unit, {@code -Dm8.trace=true} adds the frame trace (the ops of the call
 * events, ISA 1.3) of the printed units.
 */
class M8DiffOneTest {

    @Test
    void one() throws IOException {
        String file = System.getProperty("m8.file");
        assumeTrue(file != null, "set -Dm8.file=EXPORT");
        int only = Integer.getInteger("m8.unit", -1);
        boolean all = Boolean.getBoolean("m8.all");
        boolean trace = Boolean.getBoolean("m8.trace");
        StringBuilder sb = new StringBuilder();
        try (M8Harness h = M8Harness.load(file)) {
            sb.append("== ").append(file).append(": ");
            if (h.loadOutcome != Outcome.ACCEPT) {
                sb.append("the loader says ").append(Outcome.name(h.loadOutcome)).append(": ").append(h.loadMessage);
                System.out.println(sb);
                return;
            }
            M8Harness.Config c = Diff.optionsFromProperties().config;
            c.trace = trace;
            c.digest = true;
            M8Harness.Lane lane = h.lane(c);
            sb.append(h.units).append(" units; machine ").append(c).append('\n');
            int differ = 0;
            while (h.next()) {
                int u = h.unit;
                boolean same = lane.outcome == h.oracle;
                if (!same) {
                    differ++;
                }
                if (only >= 0 ? u != only : !(all || !same)) {
                    continue;
                }
                sb.append(String.format(Locale.ROOT, "unit %d %s '%s': machine %s, reference %s%s%n", u, h.unitKindName(u),
                        h.unitName(u), Outcome.name(lane.outcome), Outcome.name(h.oracle),
                        same ? "" : lane.outcome == Outcome.DECLINE ? "   [declined]" : lane.outcome == Outcome.ACCEPT
                                ? "   [SOUNDNESS]" : "   [COMPLETENESS]"));
                if (h.oracle != Outcome.ACCEPT) {
                    sb.append("    reference: ").append(h.oracleReason).append(": ").append(h.oracleMessage).append('\n');
                }
                if (lane.outcome != Outcome.ACCEPT) {
                    sb.append("    machine:   ").append(lane.reason).append(": ").append(lane.message).append('\n');
                }
                M8Harness.Obs o = lane.obs;
                if (o == null) {
                    sb.append("    (the unit did not reach the engine: environment pass or structural checks)\n");
                    continue;
                }
                sb.append(String.format(Locale.ROOT, "    ticks %d, call events %d, frames written %d, spills %d, %.3f ms (reference %.3f ms)%n",
                        o.ticks, o.calls, o.framesWritten, o.spills, o.nanos / 1e6, h.oracleNanos / 1e6));
                if (o.calls > 0) {
                    sb.append("    calls by op:");
                    for (int op = 0; op < Op.OP_COUNT; op++) {
                        if (o.callsByOp[op] != 0) {
                            sb.append(' ').append(Op.name(op)).append('=').append(o.callsByOp[op]);
                        }
                    }
                    sb.append('\n');
                }
                if (o.hasDigest) {
                    sb.append(String.format(Locale.ROOT, "    session after the declaration: %d records, %d run words, %d pairs%n", o.records,
                            o.runWords, o.pairs));
                }
                if (trace && o.trace != null) {
                    sb.append("    trace:");
                    for (int i = 0; i < o.trace.length && i < 2000; i++) {
                        sb.append(' ').append(Op.name(o.trace[i]));
                    }
                    if (o.trace.length > 2000) {
                        sb.append(" ... (").append(o.trace.length).append(" events)");
                    }
                    sb.append('\n');
                }
            }
            sb.append(differ == 0 ? "DIFF-CLEAN" : differ + " units differ").append(" (").append(h.unit + 1).append(" units compared)\n");
        }
        System.out.println(sb);
    }
}
