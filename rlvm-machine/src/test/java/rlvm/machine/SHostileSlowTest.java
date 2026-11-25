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

    /** The default set proposed by the integrator of wave 6 (SWITCHES.md), without {@code same}. */
    static final String CANDIDATE = SHostile.STEP1 + ",bodies,code,casetrees,lazyeval,lazycases,match,joint,pchk,directrules";

    /** The default set with `eager` and the two shortcuts: the candidate of S3's second pass ({@code soundness-default-set.md} section 10). */
    static final String SHORTCUTS = SHostile.WAVE6 + ",eager,skipargs,congruence";

    static final String[] SETS = {
        "", "gated", "expect", "warm", "facts", "tele", "code", "casetrees", "code,casetrees", "probes", "inert",
        "lazyeval", SHostile.STEP1, SHostile.STEP1 + ",lazyeval", SHostile.STEP1 + ",probes", "facts,tele,code,casetrees", "all",
        // wave 6 (the integrator): the switches that grew behind experiment bits, alone and on step 1, and the
        // candidate default set with and without `same`
        "bodies", "same", "match", "joint", "pchk", "directrules", "code,casetrees,lazycases", "k2,directrules",
        SHostile.STEP1 + ",bodies", SHostile.STEP1 + ",same", SHostile.STEP1 + ",match,joint,pchk",
        CANDIDATE, CANDIDATE + ",same", CANDIDATE + ",same,k2", "all,bodies,same,match,joint,pchk,directrules,lazycases,lazyeval",
        // package E1: eager reduction on the machine (Op.SW_EAGER), alone, on step 1, on the default set and on the
        // sets with facts / tele / probes (the other switches with it: work/e1/sets-eager.txt, 32 sets, run by E1)
        "eager", SHostile.STEP1 + ",eager", SHostile.WAVE6 + ",eager", "facts,tele,code,casetrees,eager", "all,eager",
        "all,bodies,same,match,joint,pchk,directrules,lazycases,lazyeval,eager",
        // INT7: the two proof-comparison shortcuts as named switches (kind T accepts are tolerated on rows with the
        // mark T only; `skipargs` brings facts, `congruence` brings probes), alone, on the default set, with `eager`
        // (the candidate default of reviewer S3's pass 2), on `all`, and with `convexpect`
        "skipargs", "congruence", "skipargs,congruence", SHostile.WAVE6 + ",skipargs", SHostile.WAVE6 + ",congruence",
        SHostile.WAVE6 + ",skipargs,congruence", SHostile.WAVE6 + ",eager,skipargs", SHostile.WAVE6 + ",eager,congruence", SHORTCUTS,
        "all,eager,skipargs,congruence", SHORTCUTS + ",convexpect", SHORTCUTS + ",k2",
        // the default set of 2026-11-25 by its name (whatever it is), and each of its additions taken out again
        "default", "default,-eager", "default,-skipargs", "default,-congruence", "default,convexpect", "default,k2",
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
        for (String set : new String[] {"", "warm", SHostile.STEP1, "probes", SHostile.STEP1 + ",probes", "facts", "all",
            CANDIDATE, CANDIDATE + ",same", SHORTCUTS, "default"}) {
            SHostile.Result r = SHostile.run(set, row -> row.knownUnder(set));
            open += r.known.size() + r.unexpected.size();
            if (!r.known.isEmpty() || !r.unexpected.isEmpty()) {
                sb.append('\n').append(r.report(set));
            }
        }
        assertEquals(0, open, "known findings that still reproduce:" + sb);
    }
}
