package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import rlvm.api.Outcome;

/**
 * Regression test of finding S3-F7 (reviewer S3, *agentic-note*: rep-soundness-default-set, section 3): a
 * closure built inside a compiled body that captures a variable which a let or an inlined beta step binds to a small
 * numeral. The capture list then holds an immediate operand ({@code Elab.atom}); {@code Code.raw} had no case for
 * it, faulted with {@code E_INTERNAL}, and the machine DECLINED a valid unit (the reference engine decided it: no
 * wrong verdict, but reference-engine work on a shape real code has - a numeral bound by {@code let} / {@code have}
 * and used inside a match alternative).
 *
 * <p>The rows are S3's minimal exports ({@code s3-code-capture-*}: let / beta, a matcher over {@code Nat.casesOn},
 * {@code Nat.casesOn} under a function motive, the successor arm, and the controls that capture a parameter or an
 * application) and the four random programs that found it. With {@code code} on - alone, with the case trees, in the
 * default set, with {@code eager} - the machine itself must ACCEPT every unit: no decline.
 */
class M7CaptureLiteralTest {

    static final String[] SETS = {"code", "code,casetrees", "default", "default,eager"};

    @Test
    @Tag("fast")
    void aClosureThatCapturesASmallNumeralIsBuiltByTheCompiledBody() {
        List<String> wrong = new ArrayList<>();
        int judged = 0;
        for (SHostile.Row row : SHostile.rows()) {
            boolean take = row.name.startsWith("s3-code-capture-") || row.name.equals("s3-r2-005-ok") || row.name.equals("s3-r2-006-ok")
                    || row.name.equals("s3-r2-010-ok") || row.name.equals("s3-r3-001-ok");
            if (!take) {
                continue;
            }
            assertEquals(Outcome.ACCEPT, row.official, row.name);
            for (String set : SETS) {
                Diff.Options o = new Diff.Options();
                o.progress = false;
                o.config.switches(set);
                o.config.checkMillis = 60_000;
                o.fileMillis = 180_000;
                Diff.FileResult r = Diff.compare(SHostile.export(row), o);
                judged++;
                if (r.machineVerdict != Outcome.ACCEPT || r.engineDeclined != 0 || r.soundness + r.completeness != 0) {
                    wrong.add("[" + set + "] " + row.name + ": machine " + Outcome.name(r.machineVerdict) + ", " + r.engineDeclined
                            + " declined units: " + r.first);
                }
            }
        }
        assertTrue(judged >= 12 * SETS.length, "the rows of S3-F7 were found: " + judged);
        assertTrue(wrong.isEmpty(), "S3-F7: the machine does not decide a unit whose compiled body captures a numeral: " + wrong);
    }
}
