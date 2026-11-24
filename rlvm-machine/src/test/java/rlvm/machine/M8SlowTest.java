package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The corpus runs of M8 besides the diff reports ({@link M8DiffReportTest}), as tests for a sandbox:
 * <pre>
 * scripts/machine-sandbox.sh test Mk -Dgroups=slow -DexcludedGroups= -Dtest='M8SlowTest#convTutorial'
 * </pre>
 * Each writes the report the corresponding {@code report.sh} command writes ({@code report.sh conv
 * tutorial}, {@code conv bugs}, {@code frames init}, {@code sample tutorial | bugs | init}) and fails on a
 * convention difference, on frames above 0.1% of the call events, on a sampled answer the reference kernel
 * refutes. ISA section 11; stepper-convention.md H12 items 1 and 2.
 */
@Tag("slow")
class M8SlowTest {

    private static String[] corpus(String label) {
        String[] roots = Diff.corpus(label);
        assumeTrue(roots != null && !Diff.exports(roots).isEmpty(), "no export of corpus " + label);
        return roots;
    }

    /** H12 item 1: D = 0 and D = 64 against the bound in force (and unbounded) on every declaration of the tutorial tests. */
    @Test
    void convTutorial() throws IOException {
        assertTrue(TierEq.conventionReport("tutorial", corpus("tutorial")), "see work/status/machine-conv-tutorial.txt");
    }

    /** H12 item 1 on the {@code bugs/} tests. */
    @Test
    void convBugs() throws IOException {
        assertTrue(TierEq.conventionReport("bugs", corpus("bugs")), "see work/status/machine-conv-bugs.txt");
    }

    /**
     * All 230 published tests under the bounds (minutes). Since wave 6 the lanes run in passes ({@link M8Lanes}):
     * in lockstep the four lanes and the reference kernel ran out of the 6 GB of the test JVM on perf/app-lam,
     * gf3-matrix-product, magma-list-deep-n36 and magma-list-pair-n21.
     */
    @Test
    void convAll() throws IOException {
        assertTrue(TierEq.conventionReport("all", corpus("all")), "see work/status/machine-conv-all.txt");
    }

    /**
     * The gate ({@link M8Gate}) on the tutorial and {@code bugs/} tests for {@code -Dm8.switches}, without its
     * pipeline part (the launcher's classes are not on the class path of the module's tests; {@code report.sh
     * gate} has them): {@code work/status/machine-gate-NAME-partial[-Mk].md}.
     */
    @Test
    void gateTutorialAndBugs() throws IOException {
        M8Gate.Plan plan = M8Gate.planFromProperties();
        plan.corpora = new java.util.ArrayList<>(java.util.List.of("tutorial", "bugs"));
        plan.pipeline = false;
        plan.name = M8Gate.name(plan);
        plan.command = "scripts/machine-sandbox.sh test <WP> -Dgroups=slow -DexcludedGroups= -Dtest='M8SlowTest#gateTutorialAndBugs'";
        corpus("tutorial");
        M8Gate.Result res = M8Gate.report(plan);
        assertTrue(res.pass(), "see work/status/machine-gate-" + plan.name + ".md");
    }

    /** H12 item 2: frames written under the bound in force below 0.1% of the call events on Init; the call table for the +2% alert. */
    @Test
    void framesInit() throws IOException {
        assertTrue(TierEq.framesReport("init", corpus("init")), "see work/status/machine-frames-init.txt");
    }

    @Test
    void framesTutorial() throws IOException {
        assertTrue(TierEq.framesReport("tutorial", corpus("tutorial")), "see work/status/machine-frames-tutorial.txt");
    }

    /** whnf / infer of the machine read back and judged by the reference kernel, on the tutorial tests. */
    @Test
    void sampleTutorial() throws IOException {
        assertTrue(M8Sampler.report("tutorial", corpus("tutorial")), "see work/status/machine-sample-tutorial.txt");
    }

    @Test
    void sampleBugs() throws IOException {
        assertTrue(M8Sampler.report("bugs", corpus("bugs")), "see work/status/machine-sample-bugs.txt");
    }

    /** The acceptance run: sampled sub-terms of Init (about 1500 constants; {@code -Dm8.every=N} for more or fewer). */
    @Test
    void sampleInit() throws IOException {
        assertTrue(M8Sampler.report("init", corpus("init")), "see work/status/machine-sample-init.txt");
    }

    /** K0 against K1 on values: DELTA, WHNF, VTYPE, CONV of generated applications ({@code -Dm8.k1=facts,tele,code,casetrees}). */
    @Test
    void tiersValuesTutorial() throws IOException {
        assertTrue(M8Tiers.report("tutorial", corpus("tutorial")), "see work/status/machine-tiers-tutorial.txt");
    }

    @Test
    void tiersValuesInit() throws IOException {
        assertTrue(M8Tiers.report("init", corpus("init")), "see work/status/machine-tiers-init.txt");
    }

    /** K0 against K1 on the tutorial tests, declaration outcomes (staging step 4). */
    @Test
    void tiersTutorial() {
        StringBuilder sb = new StringBuilder();
        for (String p : Diff.exports(corpus("tutorial"))) {
            String d = TierEq.tierEquivalence(p, 1);
            if (!d.isEmpty()) {
                sb.append(d).append('\n');
            }
        }
        assertTrue(sb.length() == 0, sb.toString());
    }
}
