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
        for (String k : new String[] {"rlvm.t2.slots", "rlvm.t2.nodes", "rlvm.t2.resume", "rlvm.t2.roles", "rlvm.t2.rent", "rlvm.t2.share"}) {
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
            assertEquals(0, e.deopts(), "deoptimisations\n" + e.report());
            for (T2Engine.Rec r : e.records()) {
                // a compilation Graal gives up on is not an error: the block stays in the stepper (ISA 8.6)
                if (r.failure != null) {
                    System.out.println("[t2] NOT COMPILED: " + r.what + ": " + r.failure.split("\n")[0]);
                }
            }
            System.out.println("[t2] " + path + " " + String.join(" ", t2) + " D=" + hostDepth + ": units " + tier.units + " accepted " + tier.accepted
                    + ", blocks compiled " + e.installedCount() + ", compiled activations " + e.compiledCalls() + ", not taken " + e.notTaken()
                    + ", frames written " + tier.frames + ", compile cpu " + e.compileNanos() / 1_000_000 + " ms");
            return e.compiledCalls();
        }
    }

    static final String T = "work/arena-site/tests/good/tutorial/";
    static final String P = "work/arena-site/tests/good/perf/";
    /** Small files whose declarations reduce through blocks of every role (recursor rules, case trees, telescopes, binders). */
    static final String[] SMALL = {T + "122_rtreeRecReduction.ndjson", T + "112_structEta.ndjson", T + "124_accRecReduction.ndjson",
        T + "131_quotIndReduction.ndjson"};

    private static long all(String[] files, String switches, int depth, String... t2) throws Exception {
        long calls = 0;
        for (String f : files) {
            assumeTrue(Harness.exists(f), f);
            calls += equivalent(f, switches, depth, t2);
        }
        return calls;
    }

    /** Every block compiled at its first entry: registers in frame slots, own nodes, resume entries. */
    @Test
    @Tag("fast")
    void smallFiles() throws Exception {
        assertTrue(all(SMALL, K1, -1) > 0, "compiled code ran");
    }

    /** Host depth 0: every judgment a block calls suspends it, so every class-j node writes its frame and every resume entry is entered. */
    @Test
    @Tag("fast")
    void smallFilesWorklist() throws Exception {
        assertTrue(all(new String[] {SMALL[0], SMALL[1]}, K1, 0) > 0, "compiled code ran");
    }

    /** The other shapes of a root: registers in the window, every instruction through the stepper's body, no resume entries. */
    @Test
    @Tag("fast")
    void variants() throws Exception {
        String[] f = {SMALL[0]};
        all(f, K1, -1, "rlvm.t2.slots", "false");
        all(f, K1, -1, "rlvm.t2.nodes", "false");
        all(f, K1, 0, "rlvm.t2.resume", "false");
        all(f, K1, 0, "rlvm.t2.nodes", "false");
    }

    /** Evaluation-heavy published tests (docs/research/13 section 4), every block compiled at its first entry. */
    @Test
    void perfTests() throws Exception {
        String[] files = {P + "magma-list-pair-n7.ndjson", P + "proj-app-lazy-delta.ndjson", P + "discarded-argument-match.ndjson",
            P + "fueled-chain.ndjson", P + "shared-subterm.ndjson", P + "folded-constant-first.ndjson", P + "args-before-unfold.ndjson"};
        assertTrue(all(files, "code,casetrees", -1) > 100_000, "compiled code ran");
        all(new String[] {files[2], files[4]}, K1, 0);
    }

    /** The same under the production promotion rule (rent-to-own, background compilation): blocks change tier in mid-run. */
    @Test
    void perfTestsBackground() throws Exception {
        String[] files = {P + "magma-list-pair-n7.ndjson", P + "proj-app-lazy-delta.ndjson"};
        all(files, "code,casetrees", -1, "rlvm.t2.at", "0", "rlvm.t2.sync", "false", "rlvm.t2.period", "256", "rlvm.t2.rent", "0.01", "rlvm.t2.share", "1.0");
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
