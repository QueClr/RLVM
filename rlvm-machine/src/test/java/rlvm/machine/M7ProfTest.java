package rlvm.machine;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import rlvm.admit.Admission;
import rlvm.admit.Policy;
import rlvm.api.Budget;
import rlvm.api.EngineConfig;
import rlvm.api.RunSignals;
import rlvm.api.WorkerContext;

/**
 * Where the CPU time of a switch set goes: a sampling profile (JFR execution samples of the checking thread,
 * every 10 ms) of one machine session over an export, after a warm-up pass. Self time by method and
 * inclusive time by method of {@code rlvm.machine}. A tool, not a test: {@code -Dm7.file=.. -Dm7.sw=..
 * -Dm7.at=..}; the report goes to {@code work/status/machine-M7-prof-NAME.txt}. Sample shares are valid on a
 * loaded host (the absolute times are not).
 */
class M7ProfTest {

    private static void pass(M7World w, String switches, String elabAt) {
        System.setProperty("rlvm.machine.switches", switches);
        System.setProperty("rlvm.machine.elabAt", elabAt);
        try {
            WorkerContext ctx = w.bindings.newWorkerContext(9, w.store, RunSignals.NONE, 1L << 30);
            MachineEngine engine = new MachineEngine(w.store, EngineConfig.defaults());
            MachineSession s = (MachineSession) engine.newSession(ctx);
            Admission admission = new Admission(ctx, Policy.arena(), null);
            Budget budget = new Budget(2_000_000_000L, 2L << 30, Integer.MAX_VALUE);
            for (int u = 0; u < w.store.loadedUnits(); u++) {
                admission.admit(u, s, budget);
                if (s.footprintBytes() > (16L << 20)) {
                    s.reset();
                    ctx.reset();
                }
            }
            s.close();
        } finally {
            System.clearProperty("rlvm.machine.switches");
            System.clearProperty("rlvm.machine.elabAt");
        }
    }

    @Test
    @Tag("slow")
    void profile() throws Exception {
        String file = System.getProperty("m7.file", "work/exports/init.ndjson");
        String sw = System.getProperty("m7.sw", "tele");
        String at = System.getProperty("m7.at", "10");
        assumeTrue(Fixture.exists(file));
        M7World w = M7World.load(file);
        pass(w, sw, at);
        Path jfr = Files.createTempFile("m7prof", ".jfr");
        String thread = Thread.currentThread().getName();
        try (Recording r = new Recording()) {
            r.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
            r.start();
            pass(w, sw, at);
            pass(w, sw, at);
            r.stop();
            r.dump(jfr);
        }
        Map<String, Integer> self = new HashMap<>();
        Map<String, Integer> incl = new HashMap<>();
        int samples = 0;
        for (RecordedEvent e : RecordingFile.readAllEvents(jfr)) {
            if (e.getStackTrace() == null || e.getThread("sampledThread") == null
                    || !thread.equals(e.getThread("sampledThread").getJavaName())) {
                continue;
            }
            samples++;
            List<RecordedFrame> frames = e.getStackTrace().getFrames();
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < frames.size(); i++) {
                String c = frames.get(i).getMethod().getType().getName();
                String name = c.substring(c.lastIndexOf('.') + 1) + "." + frames.get(i).getMethod().getName();
                if (i == 0) {
                    self.merge(name, 1, Integer::sum);
                }
                if (c.startsWith("rlvm.machine") && seen.add(name)) {
                    incl.merge(name, 1, Integer::sum);
                }
            }
        }
        Files.deleteIfExists(jfr);
        StringBuilder sb = new StringBuilder();
        sb.append("profile of switches [").append(sw).append("] ELAB_AT ").append(at).append(" on ").append(file).append(": ")
                .append(samples).append(" samples of the checking thread (10 ms)\n");
        top(sb, "self time", self, samples, 40);
        top(sb, "inclusive time (rlvm.machine)", incl, samples, 60);
        System.out.print(sb);
        String name = file.substring(file.lastIndexOf('/') + 1).replace(".ndjson", "") + (sw.isEmpty() ? "-v0" : "-" + sw.replace(',', '+'));
        Files.writeString(Fixture.root().resolve("work/status/machine-M7-prof-" + name + ".txt"), sb.toString());
    }

    private static void top(StringBuilder sb, String title, Map<String, Integer> counts, int samples, int n) {
        List<Map.Entry<String, Integer>> es = new ArrayList<>(counts.entrySet());
        es.sort((a, b) -> b.getValue() - a.getValue());
        sb.append("-- ").append(title).append('\n');
        for (int i = 0; i < n && i < es.size(); i++) {
            sb.append(String.format("  %5.1f%%  %s%n", 100.0 * es.get(i).getValue() / Math.max(samples, 1), es.get(i).getKey()));
        }
    }
}
