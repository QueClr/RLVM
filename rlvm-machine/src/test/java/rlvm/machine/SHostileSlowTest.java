package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The complete matrix of the hostile exports (package S): every export under every staging switch and the
 * usual combinations, and the strict form of the known findings. Minutes; group {@code slow}:
 * {@code scripts/machine-sandbox.sh test S -Dgroups=slow -DexcludedGroups= -Dtest=SHostileSlowTest}.
 */
@Tag("slow")
class SHostileSlowTest {

    static final String[] SETS = {
        "", "gated", "expect", "warm", "facts", "tele", "code", "casetrees", "code,casetrees", "probes", "inert",
        SHostile.STEP1, SHostile.STEP1 + ",probes", "facts,tele,code,casetrees", "all",
    };

    /** No wrong verdict that the manifest does not list as a known finding, under any switch set. */
    @Test
    void everySwitchSetOnEveryExport() {
        StringBuilder sb = new StringBuilder();
        int unexpected = 0;
        for (String set : SETS) {
            SHostile.Result r = SHostile.run(set, row -> true);
            System.out.println(r.report(set));
            if (!r.unexpected.isEmpty()) {
                unexpected += r.unexpected.size();
                sb.append('\n').append(r.report(set));
            }
        }
        assertEquals(0, unexpected, sb.toString());
    }

    /**
     * Strict: fails while a known finding of the manifest still reproduces. The owner of the switch runs it
     * after a fix; when it passes, the finding marks can be removed from {@code manifest.tsv}.
     */
    @Test
    void noKnownFindingIsLeft() {
        StringBuilder sb = new StringBuilder();
        int open = 0;
        for (String set : new String[] {"", SHostile.STEP1, "probes", SHostile.STEP1 + ",probes", "facts", "all"}) {
            SHostile.Result r = SHostile.run(set, row -> row.knownUnder(set));
            open += r.known.size() + r.unexpected.size();
            if (!r.known.isEmpty() || !r.unexpected.isEmpty()) {
                sb.append('\n').append(r.report(set));
            }
        }
        assertEquals(0, open, "known findings that still reproduce:" + sb);
    }
}
