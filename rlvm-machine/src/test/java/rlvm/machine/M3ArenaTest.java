package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The arena tests named in the acceptance list of work package M3 (ISA 13.6), per declaration against the
 * reference engine ({@code Fixture.diffAgainstReference}): K-like recursors, Nat literals and their
 * guards, string literals. A machine decline is reported, never hidden: the assertion is on a clean
 * report (no decline, no soundness or completeness difference), and on zero wrong verdicts first.
 *
 * <p>Slow group: {@code scripts/machine-sandbox.sh test M3 -Dgroups=slow -DexcludedGroups= -Dtest='M3ArenaTest'}.
 */
@Tag("slow")
class M3ArenaTest {

    static final String T = "work/arena-site/tests/";

    private static void clean(String path) throws IOException {
        assumeTrue(Fixture.exists(path), "missing " + path);
        for (int depth : new int[] {Op.HOST_DEPTH, 0}) {
            Fixture g = Fixture.load(path);
            try {
                g.hostDepth(depth);
                Fixture.DiffReport r = g.diffAgainstReference();
                System.out.println(path + " (D = " + depth + "): " + r);
                assertEquals(0, r.soundness + r.completeness, "wrong verdicts on " + path + " (D = " + depth + "):\n" + r);
                assertEquals(0, r.declined, "machine declines on " + path + " (D = " + depth + "):\n" + r);
            } finally {
                g.close();
            }
        }
    }

    @Test
    void kRecConv() throws IOException {
        clean(T + "bad/bugs/k-rec-conv.ndjson");
    }

    @Test
    void natRecKLie() throws IOException {
        clean(T + "bad/bugs/nat-rec-k-lie.ndjson");
    }

    @Test
    void recKLie() throws IOException {
        clean(T + "bad/bugs/rec-k-lie.ndjson");
    }

    @Test
    void natRecRules() throws IOException {
        clean(T + "bad/bugs/nat-rec-rules.ndjson");
    }

    @Test
    void natShiftHuge() throws IOException {
        clean(T + "good/bugs/nat-shift-huge.ndjson");
    }

    @Test
    void strLitUtf8() throws IOException {
        clean(T + "good/bugs/str-lit-utf8.ndjson");
    }

    @Test
    void strLitUtf8Bad() throws IOException {
        clean(T + "bad/bugs/str-lit-utf8-bad.ndjson");
    }

    @Test
    void natLitAdd() throws IOException {
        clean(T + "good/other/nat-lit-add.ndjson");
    }

    @Test
    void natLitSub() throws IOException {
        clean(T + "good/other/nat-lit-sub.ndjson");
    }

    @Test
    void natLitBle() throws IOException {
        clean(T + "good/other/nat-lit-ble.ndjson");
    }

    @Test
    void natLitAddBad() throws IOException {
        clean(T + "bad/other/nat-lit-add-bad.ndjson");
    }

    @Test
    void nestedRecRuleNfields() throws IOException {
        clean(T + "bad/bugs/nested-rec-rule-nfields.ndjson");
    }
}
