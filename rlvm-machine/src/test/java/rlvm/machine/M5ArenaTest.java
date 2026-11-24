package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import rlvm.api.Outcome;
import rlvm.api.Reason;

/**
 * The arena tests named in the acceptance list of M5 (WORK-PACKAGES section 7; ISA section 11), end to end
 * in-process: real loader, environment pass, the machine against the reference engine per declaration.
 * The exit status of a launcher run proves nothing (the driver falls back to the reference kernel), so
 * these rows ask the machine itself: its own verdict and its own reason.
 *
 * <p>Not in the fast group (they read files outside the module); the large one is tagged slow.
 */
class M5ArenaTest {

    static final String TESTS = "work/arena-site/tests/";

    @BeforeAll
    static void ready() {
        assumeTrue(M5Support.ready(), "EVAL / WHNF / CONV are not implemented in this tree yet");
    }

    /** The machine agrees with the reference engine on every declaration and accepts the file by itself. */
    static void good(String file) throws IOException {
        assumeTrue(Fixture.exists(TESTS + file), file + " is not there");
        Fixture g = Fixture.load(TESTS + file);
        try {
            Fixture.DiffReport r = g.diffAgainstReference();
            assertTrue(r.clean(), () -> file + ": " + r);
            Fixture.Verdict v = g.checkAll();
            assertEquals(Outcome.ACCEPT, v.outcome, () -> file + ": " + v);
            assertEquals(0, g.session.declined, file + ": no unit declined by the machine");
        } finally {
            g.close();
        }
    }

    /** The machine agrees with the reference engine and rejects the file by itself, for this reason. */
    static void bad(String file, Reason reason) throws IOException {
        assumeTrue(Fixture.exists(TESTS + file), file + " is not there");
        Fixture g = Fixture.load(TESTS + file);
        try {
            Fixture.DiffReport r = g.diffAgainstReference();
            assertTrue(r.clean(), () -> file + ": " + r);
            Fixture.Verdict v = g.checkAll();
            assertEquals(Outcome.REJECT, v.outcome, () -> file + ": " + v);
            assertEquals(reason, v.reason, () -> file + ": " + v);
            assertTrue(g.session.rejected >= 1, file + ": the rejection is the machine's own");
            assertEquals(0, g.session.declined, file);
        } finally {
            g.close();
        }
    }

    // ---- the tutorial projection tests (R02 6.8; ISA 4.5.5) ----------------------------------------------

    @Test
    void projectionsOutOfPropositionsThatAreAllowed() throws IOException {
        good("good/tutorial/089_projProp1.ndjson");
        good("good/tutorial/091_projProp3.ndjson");
        good("good/tutorial/095_projDataIndexRec.ndjson");
        good("good/tutorial/098_projRed.ndjson");
    }

    @Test
    void projectionIndexBeyondTheFields() throws IOException {
        bad("bad/tutorial/087_projOutOfRange.ndjson", Reason.INVALID_PROJECTION);
    }

    @Test
    void projectionOfSomethingThatIsNotAStructure() throws IOException {
        bad("bad/tutorial/088_projNotStruct.ndjson", Reason.INVALID_PROJECTION);
    }

    @Test
    void projectionsOutOfPropositionsThatAreRejected() throws IOException {
        bad("bad/tutorial/090_projProp2.ndjson", Reason.INVALID_PROJECTION);
        bad("bad/tutorial/092_projProp4.ndjson", Reason.INVALID_PROJECTION);
        bad("bad/tutorial/093_projProp5.ndjson", Reason.INVALID_PROJECTION);
        bad("bad/tutorial/094_projProp6.ndjson", Reason.INVALID_PROJECTION);
    }

    @Test
    void aDataFieldReadByTheIndicesOfTheResultTypeBlocksLaterFields() throws IOException {
        // projIndexData / projIndexData2: the field is read only by the constructor's RESULT type
        bad("bad/tutorial/096_projIndexData.ndjson", Reason.INVALID_PROJECTION);
        bad("bad/tutorial/097_projIndexData2.ndjson", Reason.INVALID_PROJECTION);
    }

    @Test
    void projectionBugTests() throws IOException {
        bad("bad/bugs/proj-non-structure.ndjson", Reason.INVALID_PROJECTION);
        bad("bad/bugs/proj-of-imax-prop.ndjson", Reason.INVALID_PROJECTION);
    }

    // ---- the DECL driver ------------------------------------------------------------------------------------

    @Test
    void theoremTypeIsNotProp() throws IOException {
        assumeTrue(Fixture.exists(TESTS + "bad/tutorial/012_nonPropThm.ndjson"));
        Fixture g = Fixture.load(TESTS + "bad/tutorial/012_nonPropThm.ndjson");
        try {
            assertTrue(g.diffAgainstReference().clean());
            Fixture.Verdict v = g.checkAll();
            assertEquals(Outcome.REJECT, v.outcome, v.toString());
            assertEquals(Reason.THEOREM_NOT_PROP, v.reason, v.toString());
        } finally {
            g.close();
        }
    }

    @Test
    void theFirstTutorialFilesAreAcceptedByTheMachineItself() throws IOException {
        good("good/tutorial/001_basicDef.ndjson");
        good("good/tutorial/004_dependentType.ndjson");
        good("good/tutorial/035_letRed.ndjson");
        bad("bad/tutorial/002_badDef.ndjson", Reason.DECL_TYPE_MISMATCH);
    }

    // ---- tabling ----------------------------------------------------------------------------------------------

    /**
     * perf/app-lam: 19,999 levels of {@code c (fun ..) (fun ..)} with shared arguments. Without the tabled
     * CHECKED INFER (ISA 4.5.2, 5.1) the DAG is walked as a tree (2^n); with a substitution per binder it is
     * quadratic. The machine must accept it within a step budget that only a linear walk can meet.
     */
    @Test
    @Tag("slow")
    void tabledCheckOnAppLam() throws IOException {
        String file = TESTS + "good/perf/app-lam.ndjson";
        assumeTrue(Fixture.exists(file));
        Fixture g = Fixture.load(file);
        try {
            g.maxSteps = 20_000_000L;
            Fixture.Verdict v = g.checkAll();
            assertEquals(Outcome.ACCEPT, v.outcome, v.toString());
            assertEquals(0, g.session.declined);
            assertTrue(g.m.ticks() < 5_000_000L, "ticks: " + g.m.ticks());
        } finally {
            g.close();
        }
    }
}
