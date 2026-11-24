package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Predicate;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Guard of every publication (package S, the independent soundness review): the machine gives the OFFICIAL
 * verdict on the hostile exports of {@code src/test/resources/s/} - in v0 and under the candidate default
 * {@code warm,gated,expect} on every export, under the other staging switches on the exports that attack them.
 * A violation that the manifest lists as a known, reported finding of a switch is printed and does not fail
 * (see {@link SHostile}); any other wrong verdict fails the publication of whoever introduced it.
 * The complete matrix is {@link SHostileSlowTest}.
 */
@Tag("fast")
class SHostileTest {

    private static Predicate<SHostile.Row> prefixes(String... ps) {
        return row -> {
            for (String p : ps) {
                if (row.name.startsWith(p)) {
                    return true;
                }
            }
            return false;
        };
    }

    private static void strict(String switches, Predicate<SHostile.Row> select) {
        SHostile.Result r = SHostile.run(switches, select);
        if (!r.known.isEmpty() || !r.declines.isEmpty()) {
            System.out.println(r.report(switches));
        }
        assertTrue(r.judged > 0, "no hostile export selected");
        assertEquals(0, r.unexpected.size(), r.report(switches));
    }

    @Test
    void theManifestIsComplete() {
        assertTrue(SHostile.rows().size() >= 1189, "manifest.tsv lost rows");
        int kindT = 0;
        for (SHostile.Row row : SHostile.rows()) {
            if (row.marked("T")) {
                kindT++;
                assertEquals(rlvm.api.Outcome.REJECT, row.official, "a kind T row is an official REJECT: " + row.name);
                assertTrue(row.attack.contains("kind T"), "a kind T row says who gave the kind: " + row.name);
            }
            if (row.marked("recheck")) {
                assertEquals(rlvm.api.Outcome.ACCEPT, row.official, "a recheck row is an official ACCEPT: " + row.name);
            }
        }
        assertTrue(kindT >= 81, "kind T rows: " + kindT);
        for (SHostile.Row row : SHostile.rows()) {
            assertTrue(SHostile.export(row).endsWith(row.name + ".ndjson"));
        }
    }

    /** v0: no switch. Every export; the known findings that apply to v0 are {@code eager} and {@code levels}. */
    @Test
    void v0GivesTheOfficialVerdictOnEveryHostileExport() {
        strict("", row -> true);
    }

    /** The candidate default of staging step 1. */
    @Test
    void theCandidateDefaultGivesTheOfficialVerdictOnEveryHostileExport() {
        strict(SHostile.STEP1, row -> true);
    }

    /** Step 5 on the exports that attack congruence, proof irrelevance, eta, K and iota. */
    @Test
    void probesOnTheirTargets() {
        strict(SHostile.STEP1 + ",probes", prefixes("probe-", "nontransitive-", "k-", "iota-", "eta-", "unitlike-", "pirr-", "facts-", "warm-", "canon-", "krule-"));
    }

    /** Step 2 on the exports that attack masks, the static proof filter, structure eta and projections. */
    @Test
    void factsOnTheirTargets() {
        strict("facts", prefixes("facts-", "mask-", "pirr-", "probe-", "nontransitive-", "eta-struct", "proj-", "warm-", "canon-", "krule-"));
    }

    /** Steps 3 and 4 (TELE, BODY / RULE blocks, case trees) on the exports that attack them; they pass the threshold of ten uses. */
    @Test
    void k1RolesOnTheirTargets() {
        strict("facts,tele,code,casetrees", prefixes("k1-", "tele-", "quot-", "lvl-"));
    }
}
