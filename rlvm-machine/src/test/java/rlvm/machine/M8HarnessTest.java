package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import rlvm.api.MachineErrors;
import rlvm.api.Outcome;
import rlvm.api.Reason;

/**
 * The differential runner itself ({@link M8Harness}, {@link Diff}): it must count exactly as the shared
 * fixture does, never report a wrong verdict that is not there, and survive a judgment that loops.
 * Nothing here asserts what the machine answers (a stub that still throws and a judgment that works
 * are both fine): only that machine and reference are compared correctly.
 */
@Tag("fast")
class M8HarnessTest {

    static final String T = "work/arena-site/tests/";
    static final String[] FILES = {
        T + "good/tutorial/001_basicDef.ndjson", T + "good/tutorial/070_nRec.ndjson", T + "bad/tutorial/002_badDef.ndjson",
        T + "bad/bugs/unit-rec-rule.ndjson"
    };

    @Test
    void theHarnessCountsAsTheFixtureDoes() throws IOException {
        int compared = 0;
        for (String file : FILES) {
            if (!Fixture.exists(file)) {
                continue;
            }
            Fixture.DiffReport expected = Diff.file(file);
            Diff.Options o = new Diff.Options();
            o.progress = false;
            Diff.FileResult r = Diff.compare(file, o);
            assertEquals(expected.compared, r.compared, file + ": units compared");
            assertEquals(expected.agree, r.agree, file + ": agree");
            assertEquals(expected.declined, r.declined, file + ": declined");
            assertEquals(expected.soundness, r.soundness, file + ": soundness");
            assertEquals(expected.completeness, r.completeness, file + ": completeness");
            assertEquals(expected.clean(), r.clean(), file + ": clean");
            assertEquals(r.compared, r.agree + r.declined + r.soundness + r.completeness, file);
            assertTrue(r.engineChecks <= r.compared);
            // the reference's verdict of the file is the arena's classification (good / bad directory)
            assertEquals(file.contains("/good/") ? Outcome.ACCEPT : Outcome.REJECT, r.refVerdict, file + ": reference verdict");
            compared++;
        }
        assumeTrue(compared > 0, "no arena test present");
    }

    @Test
    void lanesRunInLockstepAndSeeTheSameUnits() throws IOException {
        String file = FILES[1];
        assumeTrue(Fixture.exists(file));
        try (M8Harness h = M8Harness.load(file)) {
            M8Harness.Lane a = h.lane(new M8Harness.Config("D64").trace(true));
            M8Harness.Lane b = h.lane(new M8Harness.Config("D0").hostDepth(0).trace(true));
            assertTrue(a.m != b.m && a.ctx != b.ctx && a.ctx.overlay != b.ctx.overlay, "every lane has its own machine and context");
            assertEquals(0, b.m.hostDepth);
            int n = 0;
            while (h.next()) {
                assertEquals(n, h.unit);
                assertEquals(a.outcome, b.outcome, "unit " + h.unit);
                assertEquals(a.obs == null, b.obs == null, "unit " + h.unit + ": both lanes or neither reach the engine");
                if (a.obs != null) {
                    assertEquals(h.store.decls().unitFirstCid(h.unit), a.obs.cid);
                    assertTrue(h.engineUnit(h.unit));
                }
                n++;
            }
            assertEquals(h.units, n, "a good file is compared to its end");
            assertEquals(a.checks, b.checks);
            assertFalse(h.next());
        }
    }

    @Test
    void aFileTheLoaderRejectsIsNotCompared() throws IOException {
        String file = T + "bad/tutorial/001_badFile.ndjson";
        java.nio.file.Path dir = Fixture.root().resolve(T + "bad");
        assumeTrue(java.nio.file.Files.isDirectory(dir));
        // any file of the bad tests that the loader itself refuses
        String found = null;
        for (String f : Diff.exports(T + "bad")) {
            try (M8Harness h = M8Harness.load(f)) {
                if (h.loadOutcome != Outcome.ACCEPT) {
                    found = f;
                    assertFalse(h.next());
                    break;
                }
            }
        }
        assumeTrue(found != null, "no test that the loader rejects (looked for one like " + file + ")");
        Diff.Options o = new Diff.Options();
        o.progress = false;
        Diff.FileResult r = Diff.compare(found, o);
        assertEquals("LOADER", r.status);
        assertTrue(r.clean());
        assertEquals(0, r.compared);
    }

    @Test
    void aUnitTheReferenceDeclinesIsNotAWrongVerdictOfTheMachine() {
        // When the reference engine declines a unit (its byte budget: perf/app-lam in a small heap), whatever the
        // machine says there is not held against it as SOUNDNESS or COMPLETENESS; the run goes on behind the unit
        // and the file is settled by the official verdict.
        String file = FILES[1];
        assumeTrue(Fixture.exists(file));
        Diff.Options o = new Diff.Options();
        o.progress = false;
        Diff.FileResult normal = Diff.compare(file, o);
        assumeTrue(normal.engineChecks > 0);
        o.refDeclines = u -> true;
        Diff.FileResult r = Diff.compare(file, o);
        assertEquals(normal.compared, r.compared, "the comparison goes on behind a unit the reference declines");
        assertEquals(0, r.soundness + r.completeness, String.valueOf(r.lines));
        assertEquals(r.compared, r.agree + r.declined + r.refDeclined);
        assertEquals(Outcome.DECLINE, r.refVerdict);
        // every engine unit: either both declined (agree) or the machine decided and the unit is "reference declined"
        assertEquals(normal.accepted + normal.rejected, r.refDeclined);
        if (r.refDeclined > 0 && normal.declined == 0) {
            assertEquals("CLEAN*", r.status);
            assertTrue(r.clean() && r.settled.contains("official"), r.settled);
        }
        // a machine verdict against the official one is still wrong: a good file the machine would reject
        Diff.FileResult fake = new Diff.FileResult();
        fake.path = file;
        assertEquals(Outcome.ACCEPT, Diff.official(file));
        assertEquals(Outcome.REJECT, Diff.official(T + "bad/tutorial/002_badDef.ndjson"));
    }

    @Test
    void reasonsAreNormalisedSoThatEqualCausesCount() {
        String msg = "INTERNAL_ERROR (E_INTERNAL) at 'Foo.bar': java.lang.UnsupportedOperationException: M5: Decl.check is not"
                + " implemented yet < rlvm.machine.Decl.check(Decl.java:20) < rlvm.machine.MachineSession.check(MachineSession.java:73)";
        assertEquals("INTERNAL_ERROR (E_INTERNAL): UnsupportedOperationException: M5: Decl.check is not implemented yet",
                Diff.reasonKey(Reason.INTERNAL_ERROR, msg, "Foo.bar"));
        assertEquals(Diff.reasonKey(Reason.INTERNAL_ERROR, msg, "Foo.bar"),
                Diff.reasonKey(Reason.INTERNAL_ERROR, msg.replace("Foo.bar", "x'y"), "x'y"));
        assertEquals("APP_TYPE_MISMATCH (E_APP_MISMATCH)", Diff.reasonKey(Reason.APP_TYPE_MISMATCH, "APP_TYPE_MISMATCH (E_APP_MISMATCH) at 'c'", "c"));
        assertEquals("null", Diff.reasonKey(null, null, "c"));
        assertEquals("BUDGET_MEMORY (E_MEM): run words: 2^28 per session (ISA section 9)", Diff.reasonKey(Reason.BUDGET_MEMORY,
                "BUDGET_MEMORY (E_MEM) at 'c': run words: 2^28 per session (ISA section 9); in use: 51995026 records, 268435454 run words", "c"));
    }

    @Test
    void theReportNamesTheGateTheCountsAndTheReproduction() {
        Diff.Report rep = new Diff.Report();
        rep.label = "unit-test";
        rep.command = "none";
        rep.options = new Diff.Options();
        Diff.FileResult clean = new Diff.FileResult();
        clean.path = T + "good/tutorial/a.ndjson";
        clean.compared = 3;
        clean.agree = 3;
        rep.files.add(clean);
        assertTrue(rep.clean() && rep.sound());
        assertTrue(Diff.render(rep).contains("# GATE unit-test: DIFF-CLEAN -- files 1: clean 1"), Diff.render(rep));
        Diff.FileResult declined = new Diff.FileResult();
        declined.path = T + "good/tutorial/b.ndjson";
        declined.status = "DECLINED";
        declined.compared = 2;
        declined.agree = 1;
        declined.declined = 1;
        declined.firstUnit = 1;
        declined.first = "declined     unit 1 def 'b': machine decline (INTERNAL_ERROR x), reference accept";
        declined.lines.add(declined.first);
        declined.reasons.put("INTERNAL_ERROR x", 1);
        rep.files.add(declined);
        assertFalse(rep.clean());
        assertTrue(rep.sound());
        String text = Diff.render(rep);
        assertTrue(text.contains("# GATE unit-test: NOT CLEAN (no wrong verdict) -- files 2: clean 1, declined 1, WRONG 0"), text);
        assertTrue(text.contains("-Dtest=M8DiffOneTest -Dm8.file=" + declined.path + " -Dm8.unit=1"), text);
        assertTrue(text.contains("INTERNAL_ERROR x"), text);
        Diff.FileResult wrong = new Diff.FileResult();
        wrong.path = T + "bad/tutorial/c.ndjson";
        wrong.status = "WRONG";
        wrong.compared = 1;
        wrong.soundness = 1;
        wrong.firstUnit = 0;
        wrong.first = "SOUNDNESS    unit 0 def 'c': machine accept, reference reject";
        wrong.lines.add(wrong.first);
        rep.files.add(wrong);
        assertFalse(rep.sound());
        text = Diff.render(rep);
        assertTrue(text.contains("# GATE unit-test: WRONG VERDICTS"), text);
        assertTrue(text.indexOf("# WRONG VERDICTS, CRASHES AND TIMEOUTS") < text.indexOf("# Every file"), text);
    }

    @Test
    void theDeadlineEndsALoopingJudgmentAtItsNextPoll() throws InterruptedException {
        Machine m = Toy.machine(Op.HOST_DEPTH);
        M8Harness.Deadline deadline = new M8Harness.Deadline();
        m.signals = deadline;
        m.beginDecl(1, 5, Op.FUEL_UNLIMITED, Long.MAX_VALUE);
        // not armed: nothing happens
        assertEquals(Toy.run(m, Toy.sum(m, 20_000, 0)), Toy.run(m, Toy.sum(m, 20_000, 0)));
        assertFalse(deadline.fired);
        deadline.arm(1);
        Thread.sleep(5);
        int r = Toy.run(m, Toy.sum(m, 20_000, 0));
        assertEquals(Op.FATAL, r, "the poll every 4096 ticks sees the deadline");
        assertEquals(MachineErrors.E_CANCEL, m.err);
        assertTrue(deadline.fired);
        deadline.disarm();
        m.beginDecl(1, 5, Op.FUEL_UNLIMITED, Long.MAX_VALUE);
        assertTrue(Toy.run(m, Toy.sum(m, 20_000, 0)) < Op.SIG);
    }
}
