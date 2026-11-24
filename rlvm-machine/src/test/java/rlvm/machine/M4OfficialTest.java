package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import rlvm.api.Outcome;

/**
 * "Resemble official by default" (owner note 11) for the shortcuts of conversion: the exports of the soundness
 * review (findings S-F1 and S-F2, {@code work/soundness/exports/batch2.tsv} and {@code batch3.tsv}, copied to
 * {@code src/test/resources/m4/official/}) under every switch set of this package. Each export is one attack: the
 * declarative theory (proof irrelevance + congruence) equates two terms that the official {@code is_def_eq} does
 * not, because the official procedure is not a congruence where a reduction inspects a proof
 * ({@code And.rec f (And.intro ..)} reduces, {@code And.rec f h} is stuck). The machine's verdict must be the
 * official one, which the manifest records ({@code accept} / {@code reject}; confirmed by leanchecker 4.36).
 *
 * <p>With the shortcuts {@code congruence} / {@code skipargs} (named switches since 2026-11-25, before that options
 * of {@code Conv.Ext}) the machine accepts some of the {@code reject} files (kind T, OWNER-NOTES 13): that is what
 * they mean, and the last two tests pin it down.
 */
class M4OfficialTest {

    /** The switch sets under which the verdict must be the official one. */
    private static final String[] SETS = {"", "facts", "probes", "lazyeval", "facts,probes", "facts,probes,lazyeval",
        "warm,gated,expect,facts,probes", "warm,gated,expect,facts,probes,lazyeval", "m4a", "probes,m4a",
        "warm,gated,expect,facts,probes,lazyeval,m4a"};

    private static Path dir() {
        URL u = M4OfficialTest.class.getResource("/m4/official/manifest.tsv");
        assumeTrue(u != null, "test resources m4/official are missing");
        try {
            return Path.of(u.toURI()).getParent();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Lines {@code name TAB accept|reject TAB attack}. */
    private static List<String[]> manifest() throws IOException {
        List<String[]> rows = new ArrayList<>();
        for (String line : Files.readAllLines(dir().resolve("manifest.tsv"))) {
            if (!line.isBlank()) {
                rows.add(line.split("\t", 3));
            }
        }
        return rows;
    }

    /** The machine alone over the file (verdict rule of the driver) under a switch set and options of {@code Conv.Ext}. */
    private static Fixture.Verdict verdict(String name, String switches, int opts) throws IOException {
        Fixture g = Fixture.load(dir().resolve(name + ".ndjson").toString());
        try {
            if (!switches.isEmpty()) {
                g.switches(switches);
            }
            g.m.x4.opts = opts;
            return g.checkAll();
        } finally {
            g.close();
        }
    }

    private static void official(String[] sets, int opts, String what) throws IOException {
        int n = 0;
        for (String[] row : manifest()) {
            int want = row[1].equals("accept") ? Outcome.ACCEPT : Outcome.REJECT;
            for (String set : sets) {
                Fixture.Verdict v = verdict(row[0], set, opts);
                assertEquals(Outcome.name(want), Outcome.name(v.outcome),
                        row[0] + " under [" + set + "]" + what + ": the official verdict is " + row[1] + "; machine: " + v
                                + "\n  attack: " + row[2]);
                n++;
            }
        }
        assertTrue(n >= 23, "the manifest was read: " + n + " verdicts");
    }

    /** Fast group: every export under the richest switch set of this package ({@code lazyeval}: D26). */
    @Test
    @Tag("fast")
    void theVerdictIsTheOfficialOne() throws IOException {
        official(new String[] {"warm,gated,expect,facts,probes,lazyeval,m4a"}, 0, "");
    }

    /** The whole matrix (slow group): every switch set of this package. */
    @Test
    @Tag("slow")
    void theVerdictIsTheOfficialOneUnderEverySwitchSet() throws IOException {
        official(SETS, 0, "");
    }

    /**
     * The non-official options do give the declarative answer on the attacks of the review: B1 / B12 on
     * {@code probe-b1-rec-proof-major-bad} and {@code probe-b12-closure-proof-slot-bad}, the agreement rule on
     * {@code facts-agreement-noncongruent-app-bad}. If this test fails because the machine now REJECTS, the options
     * have become official-exact and the default question may be asked again (NOTES-M4 D27).
     */
    @Test
    @Tag("fast")
    void theNonOfficialOptionsAreNotTheOfficialVerdict() throws IOException {
        assertEquals(Outcome.ACCEPT, verdict("probe-b1-rec-proof-major-bad", "probes", Conv.Ext.OPT_CONGRUENCE).outcome);
        assertEquals(Outcome.ACCEPT, verdict("probe-b12-closure-proof-slot-bad", "probes", Conv.Ext.OPT_CONGRUENCE).outcome);
        assertEquals(Outcome.ACCEPT, verdict("facts-agreement-noncongruent-app-bad", "facts", Conv.Ext.OPT_SKIP_ARGS).outcome);
        assertEquals(Outcome.REJECT, verdict("probe-b1-rec-proof-major-bad", "probes", 0).outcome);
        assertEquals(Outcome.REJECT, verdict("facts-agreement-noncongruent-app-bad", "facts", 0).outcome);
    }

    /**
     * The shortcuts as named switches (INT7, 2026-11-25; OWNER-NOTES 13): {@code congruence} and {@code skipargs}
     * give what the old options gave with {@code probes} / {@code facts}, and each BRINGS the switch it needs
     * ({@code Op.implied}); an old option without its switch stays inert; {@code all} does not contain them.
     */
    @Test
    @Tag("fast")
    void theNamedSwitchesBringWhatTheyNeed() throws IOException {
        assertEquals(Op.SW_SKIPARGS | Op.SW_FACTS, Debug.parseSwitches("skipargs"));
        assertEquals(Op.SW_CONGRUENCE | Op.SW_PROBES, Debug.parseSwitches("congruence"));
        assertEquals(Op.SW_SKIPARGS | Op.SW_FACTS, Debug.parseSwitches("skipargs,-facts"), "the implication does not depend on the order");
        assertEquals(Op.SW_CONGRUENCE | Op.SW_PROBES, Debug.parseSwitches("-probes,congruence,-probes"));
        assertEquals(0, Debug.parseSwitches("skipargs,congruence,-skipargs,-congruence"),
                "the implication is applied to the final set: a shortcut that was removed brings nothing");
        assertEquals(Op.SW_SKIPARGS | Op.SW_FACTS | Op.SW_PROBES, Op.implied(Op.SW_SKIPARGS | Op.SW_PROBES));
        assertEquals(0, Debug.parseSwitches("all") & (Op.SW_SKIPARGS | Op.SW_CONGRUENCE), "'all' never names a kind T shortcut");
        assertEquals("facts,skipargs", Debug.switchNames(Debug.parseSwitches("skipargs")));
        assertEquals("probes,congruence", Debug.switchNames(Debug.parseSwitches("congruence")));
        assertEquals(Outcome.ACCEPT, verdict("probe-b1-rec-proof-major-bad", "congruence", 0).outcome);
        assertEquals(Outcome.ACCEPT, verdict("probe-b12-closure-proof-slot-bad", "congruence", 0).outcome);
        assertEquals(Outcome.ACCEPT, verdict("facts-agreement-noncongruent-app-bad", "skipargs", 0).outcome);
        // each shortcut is its own switch: `skipargs` does not start the same-head probe (the agreement attack is
        // accepted by either: with `congruence` the two proof arguments are compared by B1 / B2)
        assertEquals(Outcome.REJECT, verdict("probe-b1-rec-proof-major-bad", "skipargs", 0).outcome);
        assertEquals(Outcome.REJECT, verdict("probe-b12-closure-proof-slot-bad", "skipargs", 0).outcome);
        // the old options are inert without facts / probes, as they always were
        assertEquals(Outcome.REJECT, verdict("probe-b1-rec-proof-major-bad", "", Conv.Ext.OPT_CONGRUENCE).outcome);
        assertEquals(Outcome.REJECT, verdict("facts-agreement-noncongruent-app-bad", "", Conv.Ext.OPT_SKIP_ARGS).outcome);
    }
}
