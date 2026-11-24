package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The gate reports {@code work/status/machine-diff-*.txt} (ISA section 11, 13.6 gates; WORK-PACKAGES section 5):
 * the machine against the reference engine, per declaration, over the tutorial tests, {@code bugs/}, all 230
 * published tests, Init (v0) and Std / the Cedar slice (v1). One method = one report = one command:
 * <pre>
 * rlvm-machine/src/test/resources/m8/report.sh tutorial | bugs | all | init | std | cedar      (the main tree)
 * scripts/machine-sandbox.sh test Mk -Dgroups=slow -DexcludedGroups= -Dtest='M8DiffReportTest#tutorial'   (a sandbox)
 * </pre>
 * A report is always written; the test fails afterwards if any verdict is wrong (SOUNDNESS or COMPLETENESS),
 * which must never happen at any commit. Declines do not fail it: they are the state of the work, and the first
 * line of the report says whether the gate is passed. Options: {@code -Dm8.hostDepth=0}, {@code -Dm8.switches=warm,gated},
 * {@code -Dm8.maxSteps=N}, {@code -Dm8.checkSeconds=N} (deadline per declaration), {@code -Dm8.fileSeconds=N},
 * {@code -Dm8.suffix=-d0} (appended to the report name).
 */
@Tag("slow")
class M8DiffReportTest {

    static void report(String label, String... roots) throws IOException {
        Diff.Report rep = Diff.report(label, roots.length == 0 ? Diff.corpus(label) : roots);
        assumeTrue(rep != null, "no export of corpus " + label);
        assertTrue(rep.sound(), "WRONG VERDICTS in " + rep.label + ": SOUNDNESS " + rep.soundness() + ", COMPLETENESS "
                + rep.completeness() + " (see work/status/machine-diff-" + rep.label + ".txt)");
    }

    /** v0 gate, first part: the 141 tutorial tests. */
    @Test
    void tutorial() throws IOException {
        report("tutorial");
    }

    /** v0 gate, first part: the {@code bugs/} tests. */
    @Test
    void bugs() throws IOException {
        report("bugs");
    }

    /** v0 gate, second part: all 230 published tests. */
    @Test
    void all() throws IOException {
        report("all");
    }

    /** v0 gate, third part: Init. */
    @Test
    void init() throws IOException {
        report("init");
    }

    /** v1 gate: Std. */
    @Test
    void std() throws IOException {
        report("std");
    }

    /** The fast Cedar-level slice (owner note 9). */
    @Test
    void cedar() throws IOException {
        report("cedar");
    }

    /** Any other corpus: {@code -Dm8.label=NAME -Dm8.paths=FILE_OR_DIR[,FILE_OR_DIR..]}. */
    @Test
    void custom() throws IOException {
        String paths = System.getProperty("m8.paths");
        assumeTrue(paths != null, "set -Dm8.paths");
        report(System.getProperty("m8.label", "custom"), paths.split(","));
    }
}
