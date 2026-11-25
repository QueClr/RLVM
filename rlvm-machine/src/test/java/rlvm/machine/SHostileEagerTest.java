package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import rlvm.api.Outcome;

/**
 * Eager reduction ON the machine ({@code Op.SW_EAGER}, switch {@code eager}; package E1,
 * {@code docs/design/review/eager-reduction.md}): the rows of the manifest that enter eager reduction (note
 * {@code eager-decline}: package S's and the exports {@code e1-*}, generator {@code src/test/resources/s/gen/batchE.py})
 * with the switch on. The verdict of the OFFICIAL checker is in the manifest. A decline of the machine is honest;
 * but a row without the note {@code eager-hard} is one the mechanism is built to DECIDE: there the machine's own
 * verdict must be the official one.
 */
class SHostileEagerTest {

    static final String[] SETS = {"eager", SHostile.STEP1 + ",eager", "default,eager"};

    /** No verdict that the official checker does not give, with the switch on. */
    @Test
    @Tag("fast")
    void theSwitchGivesTheOfficialVerdictOrDeclines() {
        StringBuilder sb = new StringBuilder();
        int unexpected = 0;
        for (String set : SETS) {
            SHostile.Result r = SHostile.run(set, row -> row.marked("eager-decline"));
            assertTrue(r.judged >= 40, "the rows that enter eager reduction were found: " + r.judged);
            unexpected += r.unexpected.size();
            if (!r.unexpected.isEmpty()) {
                sb.append('\n').append(r.report(set));
            }
        }
        assertEquals(0, unexpected, sb.toString());
    }

    /**
     * The rows that enter eager reduction and do not carry the note {@code eager-hard} are DECIDED by the machine
     * with the switch on, with the official verdict (deferred comparisons, binder scopes, recorded points whose
     * two modes agree, sessions across declarations). The rows with the note are declined by design: a recorded
     * point with two answers, or an eager-only event inside the comparison of the K rule.
     */
    @Test
    @Tag("fast")
    void theMachineDecidesTheRowsItIsBuiltFor() {
        List<String> wrong = new ArrayList<>();
        int decided = 0;
        for (String set : SETS) {
            for (SHostile.Row row : SHostile.rows()) {
                if (!row.marked("eager-decline") || row.marked("eager-hard")) {
                    continue;
                }
                Diff.Options o = new Diff.Options();
                o.progress = false;
                o.config.switches(set);
                o.config.checkMillis = 60_000;
                o.fileMillis = 180_000;
                Diff.FileResult r = Diff.compare(SHostile.export(row), o);
                decided++;
                if (r.machineVerdict != row.official || r.soundness + r.completeness > 0) {
                    wrong.add("[" + set + "] " + row.name + ": official " + Outcome.name(row.official) + ", machine "
                            + Outcome.name(r.machineVerdict) + " " + r.first);
                }
            }
        }
        assertTrue(decided >= 3 * 20, "rows the machine decides: " + decided);
        assertTrue(wrong.isEmpty(), "the machine does not decide rows it is built for: " + wrong);
    }

    /**
     * The probes of the plain phase run under a budget ({@code Machine.erProbeBudget}, {@code -X machine.eager-probe};
     * finding INT7-F1: on con-leche a fold probe evaluated an open term for 28.6 M ticks into the hard limit of a
     * session). A probe that is cut can only turn a verdict of the machine into a DECLINE: with a budget of ONE tick
     * (every probe that evaluates anything is cut) no row gets a verdict it does not allow, and no unit differs from
     * the reference engine. The cut itself is tested in {@code M6FacadeTest.aProbeThatIsCutDeclinesTheUnitIfItEntersEagerReduction}.
     */
    @Test
    @Tag("fast")
    void aBudgetOfOneTickForTheProbesNeverChangesAVerdict() {
        String key = "rlvm.machine.eager-probe";
        String old = System.getProperty(key);
        System.setProperty(key, "1");
        try {
            for (String set : SETS) {
                SHostile.Result r = SHostile.run(set, row -> row.marked("eager-decline"));
                assertTrue(r.judged >= 40, "the rows that enter eager reduction were found: " + r.judged);
                assertEquals(0, r.unexpected.size(), "a cut probe gave a verdict the row does not allow: " + r.report(set));
            }
        } finally {
            if (old == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, old);
            }
        }
    }

    /** The rows with the note {@code eager-hard} are still declined (if one is decided, the note is stale: remove it). */
    @Test
    @Tag("fast")
    void theHardRowsAreDeclined() {
        List<String> decided = new ArrayList<>();
        for (SHostile.Row row : SHostile.rows()) {
            if (!row.marked("eager-hard")) {
                continue;
            }
            Diff.Options o = new Diff.Options();
            o.progress = false;
            o.config.switches("default,eager");
            o.config.checkMillis = 60_000;
            o.fileMillis = 180_000;
            Diff.FileResult r = Diff.compare(SHostile.export(row), o);
            if (r.machineVerdict != Outcome.DECLINE) {
                decided.add(row.name + ": machine " + Outcome.name(r.machineVerdict));
            }
        }
        assertTrue(decided.isEmpty(), "rows with the note eager-hard that the machine decides now: " + decided);
    }
}
