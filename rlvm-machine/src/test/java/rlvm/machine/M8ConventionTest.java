package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Convention equivalence (stepper-convention.md H12 item 1; ISA 1.3 "what is observable"): the tools of
 * {@link TierEq} find every kind of difference between two machines, and the real machine gives the
 * same observables under the host depth bound in force, 64, 0 and unbounded on real declarations.
 *
 * <p>How another package uses it on one judgment of its own (two fixtures that differ in the bound):
 * <pre>
 * Fixture a = Fixture.prelude0();  Fixture b = Fixture.prelude0().hostDepth(0);
 * int va = a.whnf(a.eval(a.term(T)));  int vb = b.whnf(b.eval(b.term(T)));
 * assertEquals(va, vb);  assertNull(TierEq.compare(a.m, b.m));  assertEquals(a.m.ticks(), b.m.ticks());
 * </pre>
 */
@Tag("fast")
class M8ConventionTest {

    static final String T = "work/arena-site/tests/";

    /** The toy judgments of the control tests under bound {@code d}: records, a memo, binders, a TRY segment. */
    private static Machine toys(int d) {
        Machine m = Toy.machine(d);
        m.beginDecl(1, -1, Op.FUEL_UNLIMITED, Long.MAX_VALUE);
        int t = Toy.run(m, Toy.tree(m, 14, 0));
        int f = Toy.run(m, Toy.fib(m, 40, 0));
        int s = Toy.run(m, Toy.sum(m, 3000, 0));
        int l = Toy.run(m, Toy.loop(m, 200, 0));
        int tt = Toy.run(m, Toy.tryTree(m, 12, 50, 0));
        TestTables.pairAdd(m, t, f, Layout.POS);
        TestTables.pairAdd(m, t, Layout.imm(s & 0xFFFF), Layout.NEG);
        assertTrue(t < Op.SIG && f < Op.SIG && s < Op.SIG && l < Op.SIG, "the toy judgments complete");
        assertTrue(tt == Layout.U || tt < Op.SIG);
        return m;
    }

    @Test
    void theToyJudgmentsAreEquivalentUnderEveryBound() {
        Machine ref = toys(Op.HOST_DEPTH);
        for (int d : new int[] {0, 1, 2, 7, Op.HOST_DEPTH_UNBOUNDED}) {
            Machine m = toys(d);
            assertNull(TierEq.compare(ref, m), "D = " + d);
            assertEquals(ref.ticks(), m.ticks(), "ticks under D = " + d);
            assertNull(TierEq.traceDifference(java.util.Arrays.copyOf(ref.trace, ref.traceN), java.util.Arrays.copyOf(m.trace, m.traceN)),
                    "frame trace under D = " + d);
            if (d == 0) {
                assertTrue(m.framesWritten > ref.framesWritten, "with D = 0 every non-leaf call is a frame");
            }
            if (d == Op.HOST_DEPTH_UNBOUNDED) {
                assertEquals(0, m.framesWritten, "without a bound nothing is ever suspended");
            }
        }
    }

    @Test
    void everyKindOfDifferenceIsFoundAndNamed() {
        Machine ref = toys(Op.HOST_DEPTH);
        // a memo word
        Machine m = toys(0);
        int r = m.vp - 3;
        m.vh[(r << Layout.REC_SHIFT) + Layout.M1] ^= 5;
        String d = TierEq.compare(ref, m);
        assertNotNull(d);
        assertTrue(d.contains("record #" + r) && d.contains("memo word m1"), d);
        // an identity word (a different value was made)
        m = toys(0);
        m.vh[(r << Layout.REC_SHIFT) + Layout.A] ^= 1;
        d = TierEq.compare(ref, m);
        assertTrue(d != null && d.contains("record #" + r) && d.contains("identity word a"), d);
        // a flag
        m = toys(0);
        m.vh[r << Layout.REC_SHIFT] ^= Layout.W0_WHNF;
        d = TierEq.compare(ref, m);
        assertTrue(d != null && d.contains("kind / flags / aux"), d);
        // one record more
        m = toys(0);
        m.internRecord(Layout.w0(Layout.NSUC, 0, 0), 77, 78, 0);
        d = TierEq.compare(ref, m);
        assertTrue(d != null && d.contains("records made"), d);
        // a run
        m = toys(0);
        Machine ref2 = toys(Op.HOST_DEPTH);
        TestTables.internRun(m, Layout.RUN_FRAME, 0, 5, 6);
        TestTables.internRun(ref2, Layout.RUN_FRAME, 0, 5, 7);
        d = TierEq.compare(ref2, m);
        assertTrue(d != null && d.contains("run slab differs"), d);
        // a pair status, a missing pair
        m = toys(0);
        TestTables.pairAdd(m, 2, 3, Layout.FAILED);
        ref2 = toys(Op.HOST_DEPTH);
        TestTables.pairAdd(ref2, 2, 3, Layout.POS);
        d = TierEq.compare(ref2, m);
        assertTrue(d != null && d.contains("pair (2, 3) has status"), d);
        ref2 = toys(Op.HOST_DEPTH);
        TestTables.pairAdd(ref2, 2, 4, Layout.POS);
        d = TierEq.compare(ref2, m);
        assertTrue(d != null && d.contains("exists in one machine only"), d);
        // the same pairs in another insertion order are the same table
        Machine p = toys(0);
        Machine q = toys(0);
        TestTables.pairAdd(p, 2, 3, Layout.POS);
        TestTables.pairAdd(p, 4, 5, Layout.NEG);
        TestTables.pairAdd(q, 4, 5, Layout.NEG);
        TestTables.pairAdd(q, 2, 3, Layout.POS);
        assertNull(TierEq.compare(p, q));
    }

    @Test
    void aJudgmentThatDependsOnTheBoundIsCaught() {
        // The same toy computation, but one machine takes one more step (as a judgment would that forgot to
        // give its tick back when it is spilled, or that branches on m.hostDepth).
        Machine a = toys(Op.HOST_DEPTH);
        Machine b = toys(0);
        Toy.run(b, Toy.tree(b, 3, 0));
        assertTrue(a.ticks() != b.ticks());
        assertNotNull(TierEq.traceDifference(java.util.Arrays.copyOf(a.trace, a.traceN), java.util.Arrays.copyOf(b.trace, b.traceN)));
    }

    @Test
    void traceDifferencesNameThePosition() {
        int[] a = {Op.INFER, Op.EVAL, Op.WHNF, Op.CONV};
        assertNull(TierEq.traceDifference(a, a.clone()));
        String d = TierEq.traceDifference(a, new int[] {Op.INFER, Op.EVAL, Op.DELTA, Op.CONV});
        assertTrue(d != null && d.contains("call event 2") && d.contains("WHNF") && d.contains("DELTA"), d);
        d = TierEq.traceDifference(a, new int[] {Op.INFER, Op.EVAL});
        assertTrue(d != null && d.contains("call event 2 of 4 / 2"), d);
    }

    @Test
    void realDeclarationsAreEquivalentUnderEveryBound() throws IOException {
        int n = 0;
        for (String file : new String[] {T + "good/tutorial/070_nRec.ndjson", T + "good/tutorial/104_proofIrrelevance.ndjson",
            T + "bad/tutorial/090_projProp2.ndjson", T + "good/bugs/nat-shift-huge.ndjson"}) {
            if (!Fixture.exists(file)) {
                continue;
            }
            TierEq.ConvResult r = TierEq.convention(file, true, TierEq.BOUNDS);
            assertTrue(r.ok(), file + ":\n" + String.join("\n", r.differences));
            assertEquals(0, r.frames[r.frames.length - 1], "the unbounded lane writes no frame");
            n++;
        }
        assumeTrue(n > 0, "no arena test present");
    }

    /** The reproduction command of the {@code machine-conv-*} reports: one file, every difference printed. */
    @Test
    void oneFile() throws IOException {
        String file = System.getProperty("m8.file");
        assumeTrue(file != null, "set -Dm8.file=EXPORT");
        TierEq.ConvResult r = TierEq.convention(file, true, TierEq.BOUNDS);
        StringBuilder frames = new StringBuilder();
        for (int i = 0; i < r.frames.length; i++) {
            frames.append(i == 0 ? "" : ", ").append(TierEq.boundName(r.bounds[i])).append(' ').append(r.frames[i]);
        }
        System.out.println("== " + file + ": " + r.checks + " declarations reached the engine, " + r.accepted + " accepted, " + r.calls
                + " call events, frames written: " + frames);
        for (String d : r.differences) {
            System.out.println(d);
        }
        assertTrue(r.ok(), r.differences.size() + " differences");
    }
}
