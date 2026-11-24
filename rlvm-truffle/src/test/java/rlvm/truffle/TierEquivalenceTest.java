package rlvm.truffle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tier equivalence (ISA 0.2 decision 6, 8.4): with the T2 tier on, a machine makes the same value ids,
 * with the same contents, and gives the same verdicts as without it. Every block is promoted at its
 * first entry and compiled synchronously, so that from then on every one of its activations runs in
 * compiled code - the opposite of the production setting, and the strongest test of the nodes.
 */
class TierEquivalenceTest {
    static final String K1 = "code,casetrees,tele";

    static void t2Settings(String... extra) {
        System.setProperty("rlvm.machine.elabAt", "1"); // every role is elaborated at its first use (both runs)
        System.setProperty("rlvm.t2.at", "1");
        System.setProperty("rlvm.t2.period", "1");
        System.setProperty("rlvm.t2.sync", "true");
        System.setProperty("rlvm.t2.max", "1000000");
        for (String k : new String[] {"rlvm.t2.slots", "rlvm.t2.nodes", "rlvm.t2.resume", "rlvm.t2.roles"}) {
            System.clearProperty(k);
        }
        for (int i = 0; i + 1 < extra.length; i += 2) {
            System.setProperty(extra[i], extra[i + 1]);
        }
    }

    /** Runs {@code path} without and with the tier and asserts equivalence; returns the number of compiled activations. */
    static long equivalent(String path, String switches, int hostDepth, String... t2) throws Exception {
        Harness.Run base;
        t2Settings(t2);
        try (Harness h = new Harness(path, switches, hostDepth)) {
            base = h.run();
            assertTrue(h.t2() == null, "the base run has no tier");
        }
        try (Harness h = new Harness(path, switches + ",t2", hostDepth)) {
            Harness.Run tier = h.run();
            T2Engine e = h.t2();
            assertTrue(e != null, "the machine found the tier");
            int u = Harness.firstDifference(base, tier);
            assertEquals("identical", Harness.describe(base, tier, u), path + " " + String.join(" ", t2) + "\n" + e.report());
            assertEquals(base.records, tier.records, "records made");
            assertEquals(base.runWords, tier.runWords, "run words made");
            assertEquals(0, e.failedCount(), "failed promotions\n" + e.report());
            assertEquals(0, e.deopts(), "deoptimisations\n" + e.report());
            System.out.println("[t2] " + path + " " + String.join(" ", t2) + " D=" + hostDepth + ": units " + tier.units + " accepted " + tier.accepted
                    + ", blocks compiled " + e.installedCount() + ", compiled activations " + e.compiledCalls() + ", not taken " + e.notTaken()
                    + ", frames written " + tier.frames + ", compile cpu " + e.compileNanos() / 1_000_000 + " ms");
            return e.compiledCalls();
        }
    }

    @Test
    @Tag("fast")
    void tutorialFile() throws Exception {
        String f = "work/arena-site/tests/good/tutorial/001_basicDef.ndjson";
        assumeTrue(Harness.exists(f));
        equivalent(f, K1, -1);
    }

    @Test
    @Tag("fast")
    void closedEvaluation() throws Exception {
        String f = "work/arena-site/tests/good/perf/church-numerals.ndjson";
        assumeTrue(Harness.exists(f));
        long calls = equivalent(f, K1, -1);
        assertTrue(calls > 0, "compiled code ran");
    }

    /** A tool: -Dt2.files=a.ndjson,b.ndjson [-Dt2.switches=..] [-Dt2.depth=N] [-Dt2.variant="k v k v"]. */
    @Test
    @Tag("slow")
    void files() throws Exception {
        String list = System.getProperty("t2.files");
        assumeTrue(list != null);
        String sw = System.getProperty("t2.switches", K1);
        int depth = Integer.getInteger("t2.depth", -1);
        String[] variant = System.getProperty("t2.variant", "").trim().isEmpty() ? new String[0] : System.getProperty("t2.variant").trim().split("\\s+");
        for (String f : list.split(",")) {
            assumeTrue(Harness.exists(f), f);
            long t0 = System.nanoTime();
            equivalent(f, sw, depth, variant);
            System.out.println("[t2] " + f + ": " + (System.nanoTime() - t0) / 1_000_000 + " ms");
        }
    }
}
