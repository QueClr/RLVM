package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
 * A tool, not a gate: the heat of the code blocks on a whole export (ISA 8.3; {@code Tier.heat}): which
 * blocks are entered how often, the sampled time per entry, and how the entries and the code size divide
 * over the roles and over "blocks entered at least N times" (the rent-to-own question of ISA 3.1).
 * {@code -Dm7.heat.export=PATH -Dm7.heat.switches=code,casetrees -Dm7.heat.elabAt=10 -Dm7.heat.top=40};
 * the report goes to {@code work/status/machine-M7-heat-<name>.txt}.
 */
class M7HeatTest {

    static String run(String path, String switches, String elabAt, int top) throws Exception {
        M7World w = M7World.load(path);
        String oldSw = System.getProperty("rlvm.machine.switches");
        String oldAt = System.getProperty("rlvm.machine.elabAt");
        System.setProperty("rlvm.machine.switches", switches);
        System.setProperty("rlvm.machine.elabAt", elabAt);
        try {
            WorkerContext ctx = w.bindings.newWorkerContext(7, w.store, RunSignals.NONE, 1L << 30);
            MachineEngine engine = new MachineEngine(w.store, EngineConfig.defaults());
            Tier.Heat heat = Tier.heat(engine);
            heat.timing(Integer.getInteger("m7.heat.timing", 16));
            MachineSession s = (MachineSession) engine.newSession(ctx);
            Admission admission = new Admission(ctx, Policy.arena(), null);
            Budget budget = new Budget(2_000_000_000L, 2L << 30, Integer.MAX_VALUE);
            int units = w.store.loadedUnits();
            long accepted = 0;
            long t0 = System.nanoTime();
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
            long wall = System.nanoTime() - t0;
            assertEquals(units, accepted, "every unit accepted");
            StringBuilder sb = new StringBuilder();
            sb.append(path).append(": switches ").append(switches).append(", ELAB_AT ").append(elabAt).append(", ").append(units)
                    .append(" units, ").append(wall / 1_000_000).append(" ms wall (one session, host JVM, with the accounting)\n");
            // by role: blocks, words, entries, estimated self time
            int n = heat.blockCount();
            String[] roles = {"?", "TELE", "BODY", "RULE", "FIELDS", "CONG", "RULES", "sub-block"};
            long[] blocks = new long[8];
            long[] words = new long[8];
            long[] entries = new long[8];
            long[] spent = new long[8];
            long[] unused = new long[8];
            long[] unusedWords = new long[8];
            int[] at = {1, 2, 4, 8, 16, 32, 64, 256, 1024, 4096};
            long[] blocksAt = new long[at.length];
            long[] wordsAt = new long[at.length];
            long[] entriesAt = new long[at.length];
            long allEntries = 0;
            long allWords = 0;
            for (int b = 1; b <= n; b++) {
                int[] code = heat.block(b);
                int role = Code.role(code[Op.BLK_HDR0]);
                if ((Code.flags(code[Op.BLK_HDR2]) & Code.BLKF_SUB) != 0) {
                    role = 7;
                }
                long e = heat.entries(b);
                blocks[role]++;
                words[role] += code.length;
                entries[role] += e;
                spent[role] += heat.spentNanos(b);
                if (e == 0) {
                    unused[role]++;
                    unusedWords[role] += code.length;
                }
                allEntries += e;
                allWords += code.length;
                for (int i = 0; i < at.length; i++) {
                    if (e >= at[i]) {
                        blocksAt[i]++;
                        wordsAt[i] += code.length;
                        entriesAt[i] += e;
                    }
                }
            }
            sb.append(String.format("%-10s %8s %10s %12s %10s %14s%n", "role", "blocks", "KiB", "entries", "self ms", "never entered"));
            for (int r = 1; r < 8; r++) {
                if (blocks[r] != 0) {
                    sb.append(String.format("%-10s %8d %10d %12d %10d %8d (%d KiB)%n", roles[r], blocks[r], words[r] >> 8, entries[r],
                            spent[r] / 1_000_000, unused[r], unusedWords[r] >> 8));
                }
            }
            sb.append("blocks entered at least N times: N, blocks, KiB of code, share of all entries\n");
            for (int i = 0; i < at.length; i++) {
                sb.append(String.format("  >= %-5d %7d blocks %8d KiB (%5.1f%% of the code) %5.1f%% of the entries%n", at[i], blocksAt[i],
                        wordsAt[i] >> 8, 100.0 * wordsAt[i] / Math.max(1, allWords), 100.0 * entriesAt[i] / Math.max(1, allEntries)));
            }
            sb.append(heat.report(top));
            s.close();
            return sb.toString();
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

    @Test
    @Tag("slow")
    void heat() throws Exception {
        String path = System.getProperty("m7.heat.export", "work/arena-site/tests/good/init-prelude.ndjson");
        assumeTrue(Fixture.exists(path));
        String sw = System.getProperty("m7.heat.switches", "code,casetrees");
        String s = run(path, sw, System.getProperty("m7.heat.elabAt", "10"), Integer.getInteger("m7.heat.top", 40));
        System.out.print(s);
        String name = java.nio.file.Path.of(path).getFileName().toString().replace(".ndjson", "") + "-" + sw.replace(',', '+');
        java.nio.file.Files.writeString(Fixture.root().resolve("work/status/machine-M7-heat-" + name + ".txt"), s);
    }
}
