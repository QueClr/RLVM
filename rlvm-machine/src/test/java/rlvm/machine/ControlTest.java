package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import rlvm.api.MachineErrors;
import rlvm.api.RunSignals;

/**
 * The control primitives of the host convention (*agentic-note*: rep-stepper-convention, H1-H12; ISA 1.3, 1.4) on
 * the toy judgments of {@link Toy}: equivalence under every depth bound, deep recursion on a small
 * stack, budgets and TRY segments with rollback, the failure classes, polling.
 */
@Tag("fast")
class ControlTest {

    static final int[] BOUNDS = {0, 1, 2, 7, Op.HOST_DEPTH, Op.HOST_DEPTH_UNBOUNDED};

    /** Everything ISA 1.3 calls observable, after one judgment. */
    static final class Obs {
        int result;
        long ticks;
        int vp;
        int[] records;
        long[] calls;
        int[] trace;
        long fuelLeft;
        int err;

        static Obs of(Machine m, int result) {
            Obs o = new Obs();
            o.result = result;
            o.ticks = m.ticks();
            o.vp = m.vp;
            o.records = Arrays.copyOfRange(m.vh, 0, m.vp << Layout.REC_SHIFT);
            o.calls = m.calls.clone();
            o.trace = Arrays.copyOf(m.trace, m.traceN);
            o.fuelLeft = m.fuel;
            o.err = m.err;
            return o;
        }

        void assertSame(Obs other, String what) {
            assertEquals(result, other.result, what + ": result");
            assertEquals(ticks, other.ticks, what + ": ticks");
            assertEquals(vp, other.vp, what + ": records made");
            assertArrayEquals(records, other.records, what + ": record contents (ids and memo words)");
            assertArrayEquals(calls, other.calls, what + ": call events by op");
            assertArrayEquals(trace, other.trace, what + ": frame trace");
            assertEquals(fuelLeft, other.fuelLeft, what + ": fuel left");
            assertEquals(err, other.err, what + ": error code");
        }
    }

    interface Body {
        int run(Machine m);
    }

    /** Runs {@code body} on a fresh machine per depth bound and checks that every observable agrees. */
    static Obs sameUnderEveryBound(String what, Body body) {
        Obs first = null;
        for (int bound : BOUNDS) {
            Machine m = Toy.machine(bound);
            m.beginDecl(1, -1, 1_000_000_000L, Long.MAX_VALUE);
            int r = Toy.run(m, body.run(m));
            assertTrue(m.idle(), what + ": the machine is idle after the judgment (D = " + bound + ")");
            Obs o = Obs.of(m, r);
            if (first == null) {
                first = o;
            } else {
                first.assertSame(o, what + " (D = " + bound + " against D = " + BOUNDS[0] + ")");
            }
            if (bound == Op.HOST_DEPTH_UNBOUNDED) {
                assertEquals(0, m.framesWritten, what + ": plain host recursion writes no frame");
            }
        }
        return first;
    }

    @Test
    void recursionGivesTheSameResultTicksAndTraceUnderEveryDepthBound() {
        Obs o = sameUnderEveryBound("SUM(2000)", m -> Toy.sum(m, 2000, 0));
        int expected = 0;
        for (int i = 1; i <= 2000; i++) {
            expected = (expected + i) & Toy.MASK;
        }
        assertEquals(expected, o.result);
        assertEquals(2000, o.ticks, "one tick per judgment that does work, none for the leaf");
        assertEquals(2000, o.calls[Toy.SUM]);
    }

    @Test
    void depthBoundZeroIsTheWorklistMachine() {
        Machine m = Toy.machine(0);
        int r = Toy.run(m, Toy.sum(m, 100, 0));
        assertTrue(r < Op.SIG);
        // Every non-leaf call became a frame: 99 spilled entry frames and 99 continuation frames.
        assertEquals(99, m.spills);
        assertEquals(198, m.framesWritten);
        Machine h = Toy.machine(Op.HOST_DEPTH);
        Toy.run(h, Toy.sum(h, 60, 0));
        assertEquals(0, h.framesWritten, "below the bound no frame exists (H1)");
    }

    @Test
    void treeRecursionInternsTheSameIdsUnderEveryDepthBound() {
        Obs o = sameUnderEveryBound("TREE(17)", m -> Toy.tree(m, 17, 0));
        assertTrue(o.vp > 17);
        sameUnderEveryBound("FIB(40) with memo", m -> Toy.fib(m, 40, 0));
        sameUnderEveryBound("LOOP(200)", m -> Toy.loop(m, 200, 0));
        sameUnderEveryBound("BIG", m -> Toy.big(m, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 90, 0));
    }

    @Test
    void bindersScratchAndEnvironmentsAreRestoredUnderEveryDepthBound() {
        sameUnderEveryBound("BIND(300)", m -> {
            int type = m.internRecord(Layout.w0(Layout.SORT, Layout.FL_WHNF, 0), 7, 0, 0);
            return Toy.bind(m, type, 300, Toy.OK, 0);
        });
    }

    /** H12 item 3: 1,000,000-deep recursion on a 512 KiB thread stack, D = 64 and D = 0, also under a TRY. */
    @Test
    void aMillionDeepRecursionNeedsNoHostStack() throws InterruptedException {
        int expected = 0;
        for (int i = 1; i <= 1_000_000; i++) {
            expected = (expected + i) & Toy.MASK;
        }
        final int want = expected;
        Throwable[] failure = new Throwable[1];
        Thread t = new Thread(null, () -> {
            try {
                for (int bound : new int[] {Op.HOST_DEPTH, 0}) {
                    Machine m = Toy.machine(bound);
                    m.stats = false;
                    m.debug = 0;
                    assertEquals(want, Toy.run(m, Toy.sum(m, 1_000_000, 0)), "D = " + bound);
                    assertEquals(1_000_000, m.ticks());
                    assertTrue(m.idle());
                    // a budget that runs out inside the nest: U, W empty, the budget charged in full
                    long before = m.ticks();
                    assertEquals(Layout.U, Toy.run(m, Toy.trySum(m, 1_000_000, 400_000, 0)), "D = " + bound);
                    assertEquals(400_000, m.ticks() - before);
                    assertTrue(m.idle());
                    // and one that does not
                    assertEquals(want, Toy.run(m, Toy.trySum(m, 1_000_000, 2_000_000, 0)), "D = " + bound);
                    assertTrue(m.idle());
                }
            } catch (Throwable e) {
                failure[0] = e;
            }
        }, "deep", 512 * 1024);
        t.start();
        t.join();
        if (failure[0] != null) {
            throw new AssertionError("on a 512 KiB stack: " + failure[0], failure[0]);
        }
    }

    @Test
    void aBudgetThatExpiresInsideATryGivesUAndIsChargedExactly() {
        // ticks of TREE(12) without a budget
        Machine probe = Toy.machine(Op.HOST_DEPTH);
        int full = Toy.run(probe, Toy.tree(probe, 12, 0));
        long need = probe.ticks();
        assertTrue(need > 50);
        for (long budget = 0; budget <= need + 3; budget += budget < 20 || budget > need - 5 ? 1 : 17) {
            final long b = budget;
            Obs o = sameUnderEveryBound("TRY " + b + "; TREE(12)", m -> Toy.tryTree(m, 12, b, 0));
            if (b < need) {
                assertEquals(Layout.U, o.result, "budget " + b);
                assertEquals(b, o.ticks, "an exhausted segment is charged its whole budget");
            } else {
                assertEquals(need, o.ticks, "a completed segment is charged what it used");
                // the same record contents as the run without a budget
                assertEquals(probe.vp, o.vp);
                assertTrue(o.result < Op.SIG && o.result != Layout.U);
            }
            assertEquals(0, o.err);
        }
        assertTrue(full < Op.SIG);
    }

    @Test
    void recordsMadeInsideAnAbandonedSegmentStayButNoMemoIsWritten() {
        Machine m = Toy.machine(Op.HOST_DEPTH);
        // FIB(30) needs 29 ticks; under a budget of 10 it is abandoned
        int ssp = m.ssp, xp = m.xp, ep = m.ep, rp = m.rp, er = m.ER;
        long saved = m.tryEnter(10);
        int r = Toy.fib(m, 30, 1);
        r = Toy.run(m, r == Op.SUSP ? m.saveTry(saved, 10, ssp, xp, ep, rp, er)
                : Frames.k_try(m, saved, 10, ssp, xp, ep, rp, er, r));
        assertEquals(Layout.U, r);
        for (int rec = Layout.FIRST_RECORD; rec < m.vp; rec++) {
            assertEquals(0, Values.memo(m, rec, 0), "I-MEMO: a cut judgment writes no memo (record " + rec + ")");
        }
        // the complete run afterwards gives what a fresh machine gives
        int v = Toy.run(m, Toy.fib(m, 30, 0));
        Machine fresh = Toy.machine(Op.HOST_DEPTH);
        Toy.run(fresh, Toy.fib(fresh, 30, 0));
        assertTrue(v < Op.SIG);
        assertEquals(29, fresh.ticks());
    }

    @Test
    void anInnerBudgetIsCappedByTheOuterOneAndExhaustsIt() {
        // TREE(12) needs `need` ticks. inner > outer: the inner budget is capped; when it runs out the outer
        // one is empty too, so the outer segment ends in U (not the inner one, whose U would be mapped to 5).
        Machine probe = Toy.machine(Op.HOST_DEPTH);
        Toy.run(probe, Toy.tree(probe, 12, 0));
        long need = probe.ticks();
        Obs o = sameUnderEveryBound("nested, capped", m -> Toy.nest(m, 12, 10_000, 20, 0));
        assertEquals(Layout.U, o.result);
        assertEquals(20, o.ticks);
        // inner < outer: the inner segment ends in U (mapped to 5 after 3 more ticks of SUM(3)); the outer continues
        o = sameUnderEveryBound("nested, inner expires", m -> Toy.nest(m, 12, 20, 10_000, 0));
        assertEquals(5, o.result);
        assertEquals(23, o.ticks);
        // both large enough: the tree
        o = sameUnderEveryBound("nested, complete", m -> Toy.nest(m, 12, 10_000, 20_000, 0));
        assertEquals(need, o.ticks);
        // inner expires and the outer has exactly nothing left for the 3 ticks that follow
        o = sameUnderEveryBound("nested, outer expires after the inner", m -> Toy.nest(m, 12, 20, 22, 0));
        assertEquals(Layout.U, o.result);
        assertEquals(22, o.ticks);
    }

    @Test
    void aKernelErrorInsideATryIsUAndTheMachineStacksAreRolledBack() {
        Obs o = sameUnderEveryBound("OUTER(FAIL)", m -> {
            int type = m.internRecord(Layout.w0(Layout.SORT, Layout.FL_WHNF, 0), 3, 0, 0);
            int r = Toy.outer(m, type, 150, Toy.FAIL, 1_000_000, 0);
            return r;
        });
        assertEquals(777, o.result, "the speculative path raised an error: U, mapped by the caller");
        assertEquals(150, o.ticks, "a kernel error inside a TRY charges what was used (ISA 1.4 cut(g - fuel))");
        assertEquals(0, o.err);
        // every variable that the abandoned segment opened is closed again
        Machine m = Toy.machine(3);
        int type = m.internRecord(Layout.w0(Layout.SORT, Layout.FL_WHNF, 0), 3, 0, 0);
        assertEquals(777, Toy.run(m, Toy.outer(m, type, 150, Toy.FAIL, 1_000_000, 0)));
        assertTrue(m.idle());
        for (int rec = Layout.FIRST_RECORD; rec < m.vp; rec++) {
            if (Values.kind(m, rec) == Layout.FVAR) {
                assertFalse(Scope.inScope(m, rec), "FVAR " + rec + " is still INSCOPE after the cut");
            }
        }
    }

    @Test
    void aKernelErrorOutsideATryEndsTheJudgmentWithAbort() {
        for (int bound : BOUNDS) {
            Machine m = Toy.machine(bound);
            int type = m.internRecord(Layout.w0(Layout.SORT, Layout.FL_WHNF, 0), 3, 0, 0);
            int r = Toy.run(m, Toy.bind(m, type, 100, Toy.FAIL, 0));
            assertEquals(Op.ABORT, r, "D = " + bound);
            assertEquals(MachineErrors.E_TYPE_MISMATCH, m.err);
            assertEquals(rlvm.api.Outcome.REJECT, MachineErrors.outcome(m.err));
            assertTrue(m.idle(), "abandon() restored binders, scratch, environments (D = " + bound + ")");
        }
    }

    /** RECONCILIATION section 2 "Faults inside TRY"; ISA section 11: a HOLE read inside a TRY must end in DECLINE. */
    @Test
    void aFatalFaultInsideATryIsNeverTurnedIntoU() {
        Obs first = null;
        for (int bound : BOUNDS) {
            Machine m = Toy.machine(bound);
            m.beginDecl(1, -1, 1_000_000L, Long.MAX_VALUE);
            int type = m.internRecord(Layout.w0(Layout.SORT, Layout.FL_WHNF, 0), 3, 0, 0);
            int r = m.drive(Toy.outer(m, type, 200, Toy.FAULT, 1_000_000, 0));
            assertEquals(Op.FATAL, r, "D = " + bound);
            assertEquals(MachineErrors.E_HOLE, m.err);
            assertEquals(0, m.wp, "W is cut to the level of the call whatever TRY frames it held");
            assertEquals(rlvm.api.Outcome.DECLINE, MachineErrors.outcome(m.err));
            m.abandon();
            assertTrue(m.idle());
            Obs o = Obs.of(m, r);
            if (first == null) {
                first = o;
            } else {
                assertEquals(first.ticks, o.ticks, "ticks stay exact through a fatal cut (D = " + bound + ")");
                assertArrayEquals(first.records, o.records);
            }
        }
        // nested segments: still FATAL
        Machine m = Toy.machine(2);
        int ssp = m.ssp, xp = m.xp, ep = m.ep, rp = m.rp, er = m.ER;
        long saved = m.tryEnter(5000);
        int type = m.internRecord(Layout.w0(Layout.SORT, Layout.FL_WHNF, 0), 3, 0, 0);
        int r = Toy.outer(m, type, 50, Toy.FAULT, 1000, 1);
        r = m.drive(r == Op.SUSP ? m.saveTry(saved, 5000, ssp, xp, ep, rp, er)
                : Frames.k_try(m, saved, 5000, ssp, xp, ep, rp, er, r));
        assertEquals(Op.FATAL, r);
    }

    @Test
    void theDeclarationStepLimitDeclinesWithEStepsUnderEveryDepthBound() {
        for (int bound : BOUNDS) {
            Machine m = Toy.machine(bound);
            m.beginDecl(1, -1, 500, Long.MAX_VALUE);
            int r = Toy.run(m, Toy.sum(m, 5000, 0));
            assertEquals(Op.ABORT, r, "D = " + bound);
            assertEquals(MachineErrors.E_STEPS, m.err);
            assertEquals(500, m.ticks(), "exactly the budget was executed (D = " + bound + ")");
            assertEquals(rlvm.api.Outcome.DECLINE, MachineErrors.outcome(m.err));
            // the next declaration starts with a fresh budget; ticks are monotone
            m.beginDecl(2, -1, 100_000, Long.MAX_VALUE);
            assertTrue(Toy.run(m, Toy.sum(m, 5000, 0)) < Op.SIG);
            assertEquals(5500, m.ticks());
            // a segment capped by the declaration's budget passes the exhaustion on
            m.beginDecl(3, -1, 300, Long.MAX_VALUE);
            assertEquals(Op.ABORT, Toy.run(m, Toy.trySum(m, 5000, 100_000, 0)));
            assertEquals(MachineErrors.E_STEPS, m.err);
            assertEquals(5800, m.ticks());
        }
    }

    static final class Signals implements RunSignals {
        volatile int first = Integer.MAX_VALUE;
        volatile boolean memory;

        @Override
        public int firstFailure() {
            return first;
        }

        @Override
        public boolean memoryExhausted() {
            return memory;
        }
    }

    /** ISA 14.4: an earlier unit failed: fault(E_CANCEL) within 4096 ticks, also inside a speculative segment. */
    @Test
    void cancellationIsPolledEvery4096TicksAndIsFatalInsideATry() {
        for (int bound : BOUNDS) {
            for (boolean inTry : new boolean[] {false, true}) {
                Machine m = Toy.machine(bound);
                Signals s = new Signals();
                m.signals = s;
                m.beginDecl(8, 7, Op.FUEL_UNLIMITED, Long.MAX_VALUE);
                assertTrue(Toy.run(m, Toy.sum(m, 10_000, 0)) < Op.SIG, "no signal: runs to the end");
                long before = m.ticks();
                s.first = 3; // unit 3 < unit 7 failed
                int r = Toy.run(m, inTry ? Toy.trySum(m, 100_000, 50_000, 0) : Toy.sum(m, 100_000, 0));
                assertEquals(Op.FATAL, r, "D = " + bound + (inTry ? " in a TRY" : ""));
                assertEquals(MachineErrors.E_CANCEL, m.err);
                assertTrue(m.ticks() - before <= 4096, "cancelled after " + (m.ticks() - before) + " ticks");
                assertTrue(m.idle());
                // a failure of a LATER unit does not cancel
                s.first = 9;
                m.beginDecl(8, 7, Op.FUEL_UNLIMITED, Long.MAX_VALUE);
                assertTrue(Toy.run(m, Toy.sum(m, 10_000, 0)) < Op.SIG);
            }
        }
    }

    @Test
    void theMemoryWatermarkAndTheByteBudgetAreFatal() {
        Machine m = Toy.machine(Op.HOST_DEPTH);
        Signals s = new Signals();
        m.signals = s;
        s.memory = true;
        assertEquals(Op.FATAL, Toy.run(m, Toy.trySum(m, 100_000, 50_000, 0)));
        assertEquals(MachineErrors.E_MEM, m.err);
        s.memory = false;
        // Budget.maxBytes: 60,000 nested binders make 60,000 FVAR records (36 bytes each); the unit may add 64 KiB
        int type = m.internRecord(Layout.w0(Layout.SORT, Layout.FL_WHNF, 0), 3, 0, 0);
        m.beginDecl(1, 0, Op.FUEL_UNLIMITED, 64 << 10);
        assertEquals(Op.FATAL, Toy.run(m, Toy.bind(m, type, 60_000, Toy.OK, 0)));
        assertEquals(MachineErrors.E_MEM, m.err);
        assertTrue(m.engineBytes() < (64 << 10) + 2 * 4096 * 36L, "stopped at the first poll past the limit: "
                + m.engineBytes());
        assertTrue(m.idle());
    }

    @Test
    void tickOfSeveralTicksAtOnce() {
        Machine m = Toy.machine(Op.HOST_DEPTH);
        m.beginDecl(1, -1, 100, Long.MAX_VALUE);
        assertEquals(0, m.tick(60));
        assertEquals(60, m.ticks());
        assertEquals(Op.ABORT, m.tick(60));
        assertEquals(MachineErrors.E_STEPS, m.err);
        assertEquals(100, m.ticks(), "the ticks that were left count as executed");
    }

    @Test
    void failAndFaultClassifyByTheCode() {
        Machine m = Toy.machine(Op.HOST_DEPTH);
        assertEquals(Op.ABORT, m.fail(MachineErrors.E_APP_MISMATCH));
        assertEquals(Op.ABORT, m.fail(MachineErrors.E_LIMIT));
        assertEquals(Op.ABORT, m.raise(MachineErrors.E_NATSIZE));
        assertEquals(Op.FATAL, m.fail(MachineErrors.E_MEM), "a fatal code is never recoverable, whoever raises it");
        assertEquals(Op.FATAL, m.raise(MachineErrors.E_PRELUDE));
        assertEquals(Op.FATAL, m.fault(MachineErrors.E_INTERNAL));
        assertEquals(Op.FATAL, m.raise(99), "unknown codes are fatal (fail closed)");
    }

    @Test
    void signalsAreDisjointFromEveryResultEncoding() {
        assertTrue(Op.SUSP >= Op.SIG && Op.ABORT >= Op.SIG && Op.FATAL >= Op.SIG);
        assertTrue(Op.MISS < Op.SIG && Op.MISS > Layout.REF_INDEX_MASK, "MISS is neither a signal nor a record ref");
        assertTrue(Layout.imm(Layout.IMM_MASK) < 0, "every immediate is negative, hence below SIG");
        assertNotEquals(Op.MISS, Layout.imm(Layout.IMM_MASK), "MISS is not the immediate 2^31 - 1");
        assertTrue(Layout.TSTAMP_PERM < Op.MISS);
        assertTrue(Layout.T < Op.SIG && Layout.UNDEF < Op.SIG);
        assertEquals(Op.FAM_EXT, Op.familyOf(Toy.K_SUM));
        assertEquals(Op.FAM_CTL, Op.familyOf(Op.K_TRY));
        assertEquals(Op.CONV, Op.opOf(Op.K_CONV | 7));
        assertEquals(7, Op.stateOf(Op.K_CONV | 7));
        assertEquals(3, Op.payloadWords(Op.header(Op.K_CONV | 7, 3)));
        assertEquals(Op.K_CONV | 7, Op.keyOf(Op.header(Op.K_CONV | 7, 3)));
    }

    @Test
    void sigXWritesAFrameFromScratch() {
        Machine m = Toy.machine(0);
        m.X[0] = 11;
        m.X[1] = 22;
        m.xp = 2;
        assertEquals(5, m.sigX(5, Toy.K_SUM_1, 0, 2), "a result is not a signal: nothing happens");
        assertEquals(Op.ABORT, m.sigX(Op.ABORT, Toy.K_SUM_1, 0, 2));
        assertEquals(0, m.framesWritten);
        assertEquals(Op.SUSP, m.sigX(Op.SUSP, Toy.K_LOOP_1, 0, 2));
        assertEquals(1, m.framesWritten);
        assertEquals(Op.header(Toy.K_LOOP_1, 2), m.S[m.S.length - 1]);
        assertEquals(22, m.S[m.S.length - 2]);
        assertEquals(11, m.S[m.S.length - 3]);
    }

    @Test
    void theWorklistGrowsAndTheSideBufferNeverOverflows() {
        Machine m = Toy.machine(0, Machine.Sizes.tiny());
        assertTrue(Toy.run(m, Toy.sum(m, 50_000, 0)) < Op.SIG);
        assertTrue(m.maxWp >= 2 * 49_999, "every level was a frame: maxWp = " + m.maxWp);
        assertTrue(m.idle());
        Machine h = Toy.machine(Op.HOST_DEPTH, Machine.Sizes.tiny());
        assertTrue(Toy.run(h, Toy.big(h, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 5000, 0)) < Op.SIG);
        assertTrue(h.idle());
    }
}
