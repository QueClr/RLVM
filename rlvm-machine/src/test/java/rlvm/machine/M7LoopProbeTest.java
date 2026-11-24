package rlvm.machine;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A tool: one export in-process with a switch set, printing how the machine ends each unit (outcome, error
 * code, or the Throwable that escaped). {@code -Dm7.probe.export=PATH -Dm7.probe.switches=...}.
 */
class M7LoopProbeTest {
    @Test
    @Tag("slow")
    void probe() throws Exception {
        String path = System.getProperty("m7.probe.export", "work/arena-site/tests/good/perf/loop-unfolded-once.ndjson");
        assumeTrue(Fixture.exists(path));
        String oldSw = System.getProperty("rlvm.machine.switches");
        String oldAt = System.getProperty("rlvm.machine.elabAt");
        System.setProperty("rlvm.machine.switches", System.getProperty("m7.probe.switches", "tele,code,casetrees"));
        System.setProperty("rlvm.machine.elabAt", System.getProperty("m7.probe.elabAt", "1"));
        try {
            Fixture f = Fixture.load(path);
            try {
                Fixture.Verdict v = f.checkAll();
                System.out.println("PROBE " + path + ": " + v + "; last message: " + f.session.lastMessage()
                        + "; RS " + f.m.RS.length + " X " + f.m.X.length + " records " + f.m.vp + " run words " + f.m.rsp);
            } catch (Throwable t) {
                System.out.println("PROBE " + path + ": THROWN " + t);
                StackTraceElement[] st = t.getStackTrace();
                for (int i = 0; i < Math.min(st.length, 25); i++) {
                    System.out.println("    at " + st[i]);
                }
            }
            f.close();
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
}
