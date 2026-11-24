package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import rlvm.api.MachineErrors;
import rlvm.store.Node;

/**
 * The debug modes of ISA 5.5 as far as they do not need a judgment of another package: poison (the
 * reset rule), scopeaudit, maskaudit, sharecheck, pubcheck, and the memocheck protocol on the toy
 * judgment FIB (its fast path carries the hook every memoised judgment uses).
 */
@Tag("fast")
class M6DebugTest {

    private static int sort(Machine m, int level) {
        return m.internRecord(Layout.w0(Layout.SORT, Layout.FL_WHNF, 0), level, 0, 0);
    }

    // ---- poison -------------------------------------------------------------------------------------------

    @Test
    void poisonShiftsTheIdsOfEverySessionAndZeroesTheOldOnes() {
        Machine m = new Machine();
        m.debug = Op.DBG_POISON;
        int a = sort(m, 7);
        int b = sort(m, 8);
        int run = TestTables.internRun(m, Layout.RUN_FRAME, 0, a, b);
        assertFalse(Debug.stale(m, a));
        assertFalse(Debug.staleRun(m, run));
        long bytes = m.engineBytes();
        m.reset();
        assertEquals(0, m.engineBytes(), "the footprint counts the live session only");
        assertTrue(m.pristine());
        assertTrue(Debug.stale(m, a), "a ref kept across the reset is stale");
        assertTrue(Debug.stale(m, b));
        assertTrue(Debug.staleRun(m, run));
        assertEquals(Layout.K_SLOT, Values.kind(m, a), "and reads as kind 0, which no judgment accepts");
        assertEquals(0, m.rs[run], "an old run reads as the empty frame: every FGET is out of bounds");
        int a2 = sort(m, 7);
        assertNotEquals(a, a2, "the same construction has another id in the next session");
        assertEquals(a2, sort(m, 7), "and is hash-consed there");
        assertFalse(Debug.stale(m, a2));
        assertEquals(bytes - 4 * (2 + 2) - 4 - (Layout.REC_BYTES + 4), m.engineBytes());
        // NONE, HOLE and immediates never are stale
        assertFalse(Debug.stale(m, Layout.NONE));
        assertFalse(Debug.stale(m, Layout.HOLE));
        assertFalse(Debug.stale(m, Layout.imm(5)));
        assertFalse(Debug.staleRun(m, Layout.RUN_EMPTY));
    }

    @Test
    void poisonWrapsAroundAndGrowthAndResetStillSeeOnlyTheLiveRecords() {
        Machine m = new Machine(Machine.Sizes.tiny());
        m.debug = Op.DBG_POISON;
        for (int session = 0; session < 40; session++) {
            int n = 1 + session % 7;
            int[] ids = new int[n];
            for (int i = 0; i < n; i++) {
                ids[i] = sort(m, 100 * session + i);
                assertTrue(ids[i] >= m.vbase && ids[i] < m.vp);
            }
            int f = TestTables.internRun(m, Layout.RUN_FRAME, 0, ids[0], ids[n - 1]);
            for (int i = 0; i < n; i++) {
                assertEquals(ids[i], sort(m, 100 * session + i), "interned once, also after the table grew");
            }
            assertEquals(f, TestTables.internRun(m, Layout.RUN_FRAME, 0, ids[0], ids[n - 1]));
            assertEquals(n * (Layout.REC_BYTES + 4L) + 4 * 4 + 4, m.engineBytes());
            m.reset();
            assertTrue(m.pristine());
            MachineStateTest.assertAllZero(m.vtab, "vtab");
            MachineStateTest.assertAllZero(m.rtab, "rtab");
            MachineStateTest.assertAllZero(m.vh, "vh (every dead record is zeroed)");
            MachineStateTest.assertAllZero(m.rs, "rs");
            assertTrue(m.vbase >= Layout.FIRST_RECORD && m.vbase <= (1 << m.sizes.records));
        }
    }

    @Test
    void withoutPoisonTheBasesNeverMove() {
        Machine m = new Machine();
        int a = sort(m, 7);
        m.reset();
        assertEquals(Layout.FIRST_RECORD, m.vbase);
        assertEquals(Layout.FIRST_RUN, m.rbase);
        assertEquals(a, sort(m, 9), "ids are reused from the start");
    }

    // ---- scopeaudit ---------------------------------------------------------------------------------------

    @Test
    void scopeAuditFindsAVariableWhoseBinderIsClosed() {
        Machine m = new Machine();
        m.debug = Op.DBG_SCOPEAUDIT;
        m.beginDecl(1, 0, Long.MAX_VALUE, Long.MAX_VALUE);
        int ty = sort(m, 3);
        int x = Scope.open(m, ty);
        int y = Scope.open(m, ty);
        assertEquals(0, Debug.scopeAudit(m, x));
        // a closure that captured y, a neutral whose spine mentions the closure
        int frame = TestTables.internRun(m, Layout.RUN_FRAME, 0, x, Layout.HOLE, y);
        int clo = m.internRecord(Layout.w0(Layout.LAM, Layout.FL_WHNF | Layout.FL_OPEN, 0), 99, frame, 0);
        int spine = TestTables.spineRun(m, Layout.imm(4), clo);
        int neu = m.internRecord(Layout.w0(Layout.NEU, Layout.FL_OPEN, 2), x, spine, 12345);
        assertEquals(0, Debug.scopeAudit(m, neu));
        Scope.close(m, 1); // y leaves the scope; the closure still mentions it
        assertEquals(0, Debug.scopeAudit(m, x));
        assertEquals(Op.FATAL, Debug.scopeAudit(m, neu));
        assertEquals(MachineErrors.E_AUDIT, m.err);
        assertTrue(m.auditMessage.contains("I-FV3"), m.auditMessage);
        // a variable whose type mentions a closed variable
        m.beginDecl(2, 1, Long.MAX_VALUE, Long.MAX_VALUE);
        assertNull(m.auditMessage);
        int x2 = Scope.open(m, ty);
        assertEquals(x, x2, "a re-opened binder reproduces the variable");
        int depTy = m.internRecord(Layout.w0(Layout.NEU, Layout.FL_OPEN, 1), x2, TestTables.spineRun(m, Layout.imm(1)), 777);
        int z = Scope.open(m, depTy);
        assertEquals(0, Debug.scopeAudit(m, z));
        m.vh[x2 << Layout.REC_SHIFT] &= ~Layout.W0_INSCOPE; // simulate a judgment that closed x under z
        assertEquals(Op.FATAL, Debug.scopeAudit(m, z));
        m.vh[x2 << Layout.REC_SHIFT] |= Layout.W0_INSCOPE;
        // a stale ref (another session's id) is an audit failure too
        m.auditMessage = null;
        assertEquals(Op.FATAL, Debug.scopeAudit(m, m.vp + 5));
        assertTrue(m.auditMessage.contains("not a record of this session"), m.auditMessage);
        m.abandon();
    }

    // ---- maskaudit ----------------------------------------------------------------------------------------

    @Test
    void maskAuditAcceptsTheStoreMasksAndFindsAMaskThatLacksAnIndex() {
        Fixture f = Fixture.prelude0();
        f.debug("maskaudit");
        Machine m = f.m;
        int t = f.term("(fun (x : Nat) (y : Nat) (Nat.add (Nat.succ x) (Nat.add #2 y)))");
        assertEquals(0, Debug.maskAudit(m, t));
        int body = f.ctx.terms.b(f.ctx.terms.b(t)); // Nat.add (Nat.succ x) (Nat.add n y): reads x = #1, y = #0 and the loose #2
        assertEquals(0b111, Debug.naiveMask(Debug.state(m), f.ctx.terms, body));
        assertEquals(0b1, Debug.naiveMask(Debug.state(m), f.ctx.terms, t), "under two binders only the loose index is free, as index 0");
        assertEquals(0, Debug.maskAudit(m, body));
        // every declared type and value of the prelude has a covering mask
        for (int cid = 0; cid < f.store.decls().constantCount(); cid++) {
            int ty = f.store.decls().type(cid);
            int val = f.store.decls().value(cid);
            if (ty > 0) {
                assertEquals(0, Debug.maskAudit(m, ty), () -> m.auditMessage);
            }
            if (val > 0) {
                assertEquals(0, Debug.maskAudit(m, val), () -> m.auditMessage);
            }
        }
        // a let, a projection and an index beyond 62
        int far = f.term("(let (z : Nat := #70) (Nat.add z #64))");
        assertEquals(0, Debug.maskAudit(m, far), () -> m.auditMessage);
        assertEquals(Layout.MASK_FAR, Debug.naiveMask(Debug.state(m), f.ctx.terms, far));
        int edge = f.term("(fun (x : Nat) #63)"); // index 63 under one binder is index 62 outside: a near bit
        assertEquals(1L << 62, Debug.naiveMask(Debug.state(m), f.ctx.terms, edge));
        assertEquals(0, Debug.maskAudit(m, edge), () -> m.auditMessage);
        // corrupt the stored mask of an overlay node: the audit must see it (after its memo is dropped)
        int bad = f.term("(fun (x : Nat) (Nat.add x #3))");
        assertTrue(Node.isOverlay(bad));
        assertEquals(0, Debug.maskAudit(m, bad));
        int[] region = f.ctx.terms.regionOf(bad);
        int w = (bad & Node.INDEX_MASK) << 3;
        assertEquals(1 << 2, region[w + Node.W_MASK_LO]);
        region[w + Node.W_MASK_LO] = 0;
        assertEquals(Op.FATAL, Debug.maskAudit(m, bad));
        assertEquals(MachineErrors.E_AUDIT, m.err);
        assertTrue(m.auditMessage.contains("de Bruijn index 2"), m.auditMessage);
        region[w + Node.W_MASK_LO] = 1 << 2;
        f.close();
    }

    @Test
    void maskAuditWalksADeepNestWithoutHostRecursion() throws Exception {
        Fixture f = Fixture.prelude0();
        Machine m = f.m;
        Throwable[] failure = new Throwable[1];
        Thread t = new Thread(null, () -> {
            try {
                int nest = f.term("Nat.zero");
                int succ = f.term("Nat.succ");
                Sexp.Sink sink = f;
                for (int i = 0; i < 300_000; i++) {
                    nest = sink.app(succ, nest);
                }
                assertEquals(0, Debug.maskAudit(m, nest));
                assertEquals(0, Debug.naiveMask(Debug.state(m), f.ctx.terms, nest));
            } catch (Throwable e) {
                failure[0] = e;
            }
        }, "deep", 256 * 1024);
        t.start();
        t.join();
        if (failure[0] != null) {
            throw new AssertionError(failure[0]);
        }
        f.close();
    }

    // ---- sharecheck, pubcheck -------------------------------------------------------------------------------

    @Test
    void shareCheckFailsOnTheSecondWalkOfANodeUnderOneFrameInADeclaration() {
        Machine m = new Machine();
        m.debug = Op.DBG_SHARECHECK;
        m.beginDecl(1, 0, Long.MAX_VALUE, Long.MAX_VALUE);
        assertEquals(0, Debug.shareCheck(m, 100, 7));
        assertEquals(0, Debug.shareCheck(m, 100, 8), "another frame is another walk");
        assertEquals(0, Debug.shareCheck(m, 101, 7));
        assertEquals(Op.FATAL, Debug.shareCheck(m, 100, 7));
        assertEquals(MachineErrors.E_AUDIT, m.err);
        m.beginDecl(2, 1, Long.MAX_VALUE, Long.MAX_VALUE);
        assertEquals(0, Debug.shareCheck(m, 100, 7), "the set is per declaration");
        for (int i = 0; i < 10_000; i++) {
            assertEquals(0, Debug.shareCheck(m, 200 + i, i));
        }
        assertEquals(Op.FATAL, Debug.shareCheck(m, 5200, 5000));
    }

    @Test
    void pubCheckAcceptsOnlySharedIds() {
        Fixture f = Fixture.prelude0();
        Machine m = f.m;
        int shared = f.store.decls().type(f.cid("Nat.add"));
        assertEquals(0, Debug.pubTerm(m, shared));
        assertEquals(Op.FATAL, Debug.pubTerm(m, f.term("(Nat.succ Nat.zero)")), "an overlay term is never published");
        assertEquals(MachineErrors.E_AUDIT, m.err);
        assertEquals(Op.FATAL, Debug.pubTerm(m, 0));
        assertEquals(0, Debug.pubConst(m, f.cid("Nat")));
        assertEquals(Op.FATAL, Debug.pubConst(m, f.store.decls().constantCount()));
        assertEquals(0, Debug.pubLevel(m, f.ctx.levels.zero()));
        int derived = f.level("(max (succ (succ (succ 7))) u)");
        if (f.ctx.levels instanceof rlvm.api.bind.LevelBinding) {
            assertEquals(Op.FATAL, Debug.pubLevel(m, derived), "a level made by the worker is not a base id");
        }
        f.close();
    }

    // ---- memocheck ----------------------------------------------------------------------------------------

    @Test
    void memoCheckRecomputesEveryHitAndAgreesOnACorrectMemo() {
        for (int depth : new int[] {0, 2, Op.HOST_DEPTH}) {
            Machine plain = Toy.machine(depth);
            plain.beginDecl(1, 0, Long.MAX_VALUE, Long.MAX_VALUE);
            int expect = Toy.run(plain, Toy.fib(plain, 25, 0));
            long ticks = plain.ticks();
            long calls = plain.calls[Toy.FIB];

            Machine m = Toy.machine(depth);
            m.debug = Op.DBG_MEMOCHECK | Op.DBG_TRACE;
            m.beginDecl(1, 0, Long.MAX_VALUE, Long.MAX_VALUE);
            int r = Toy.run(m, Toy.fib(m, 25, 0));
            assertEquals(expect, r, "the same value id: FIB makes the same records in the same order");
            assertTrue(m.memoChecks > 0, "FIB(25) hits its memo");
            assertEquals(plain.hits[Toy.FIB], m.memoChecks, "every hit was recomputed");
            assertEquals(0, m.memoInconclusive);
            assertEquals(ticks, m.ticks(), "a re-computation is charged to nobody");
            assertEquals(calls, m.calls[Toy.FIB], "and is not a call event");
            assertEquals(plain.traceN, m.traceN);
            assertTrue(m.idle());
            assertNull(m.auditMessage);
        }
    }

    @Test
    void memoCheckFindsAWrongMemoAndDeclines() {
        for (int depth : new int[] {0, Op.HOST_DEPTH}) {
            Machine m = Toy.machine(depth);
            m.debug = Op.DBG_MEMOCHECK;
            m.beginDecl(1, 0, Long.MAX_VALUE, Long.MAX_VALUE);
            int f10 = Toy.run(m, Toy.fib(m, 10, 0));
            int f9 = Toy.run(m, Toy.fib(m, 9, 0));
            assertNotEquals(f10, f9);
            int key = m.internRecord(Layout.w0(Layout.CONST, 0, 0), 10, 0, 0);
            Values.setMemo(m, key, 0, f9); // a memo that lies
            int r = Toy.run(m, Toy.fib(m, 12, 0));
            assertEquals(Op.FATAL, r, "a failed audit is fatal");
            assertEquals(MachineErrors.E_AUDIT, m.err);
            assertNotNull(m.auditMessage);
            assertTrue(m.auditMessage.startsWith("memocheck: EXT2("), m.auditMessage);
            assertTrue(m.idle(), "the stacks are restored");
            assertEquals(0, m.dbgNest);
        }
    }

    @Test
    void aFailedAuditInsideATryIsNotTurnedIntoU() {
        Machine m = Toy.machine(Op.HOST_DEPTH);
        m.debug = Op.DBG_MEMOCHECK;
        m.beginDecl(1, 0, Long.MAX_VALUE, Long.MAX_VALUE);
        int f10 = Toy.run(m, Toy.fib(m, 10, 0));
        int key = m.internRecord(Layout.w0(Layout.CONST, 0, 0), 9, 0, 0);
        Values.setMemo(m, key, 0, f10);
        // the speculative segment of Frames' class comment around FIB(11)
        int ssp = m.ssp;
        int xp = m.xp;
        int ep = m.ep;
        int rp = m.rp;
        int er = m.ER;
        long saved = m.tryEnter(1000);
        int r = Toy.fib(m, 11, 1);
        r = r == Op.SUSP ? m.saveTry(saved, 1000, ssp, xp, ep, rp, er) : Frames.k_try(m, saved, 1000, ssp, xp, ep, rp, er, r);
        r = Toy.run(m, r);
        assertEquals(Op.FATAL, r, "ISA 1.4: a fatal fault is never turned into U");
        assertEquals(MachineErrors.E_AUDIT, m.err);
    }

    @Test
    void aReComputationThatRunsOutOfBudgetIsInconclusiveAndChangesNothing() {
        Machine m = Toy.machine(Op.HOST_DEPTH);
        m.debug = Op.DBG_MEMOCHECK;
        m.beginDecl(1, 0, Long.MAX_VALUE, Long.MAX_VALUE);
        int f20 = Toy.run(m, Toy.fib(m, 20, 0));
        long ticks = m.ticks();
        long fuel = m.fuel;
        Debug.state(m).budget = 3;
        // forget the memos below 20 so that the re-computation of FIB(20) needs more than 3 ticks
        for (int n = 2; n < 20; n++) {
            Values.setMemo(m, m.internRecord(Layout.w0(Layout.CONST, 0, 0), n, 0, 0), 0, 0);
        }
        long before = m.memoInconclusive;
        assertEquals(f20, Toy.run(m, Toy.fib(m, 20, 0)), "the hit is used");
        assertEquals(before + 1, m.memoInconclusive);
        assertEquals(ticks, m.ticks());
        assertEquals(fuel, m.fuel);
        assertEquals(f20, Values.memo(m, m.internRecord(Layout.w0(Layout.CONST, 0, 0), 20, 0, 0), 0), "the entry is back");
        assertTrue(m.idle());
        assertEquals(0, m.err);
    }

    @Test
    void samplingChecksOneHitInN() {
        Machine m = Toy.machine(Op.HOST_DEPTH);
        m.debug = Op.DBG_MEMOCHECK;
        Debug.state(m).every = 4;
        m.beginDecl(1, 0, Long.MAX_VALUE, Long.MAX_VALUE);
        Toy.run(m, Toy.fib(m, 30, 0));
        long hits = m.hits[Toy.FIB];
        assertTrue(hits >= 20);
        assertTrue(m.memoChecks >= hits / 4 && m.memoChecks <= hits / 4 + 1, m.memoChecks + " of " + hits);
    }

    @Test
    void theModeNamesParse() {
        assertEquals(Op.DBG_MEMOCHECK | Op.DBG_POISON | Op.DBG_SCOPEAUDIT, Debug.parseModes("memocheck, poison,scopeaudit"));
        assertEquals("memocheck,poison,scopeaudit", Debug.modeNames(Op.DBG_MEMOCHECK | Op.DBG_POISON | Op.DBG_SCOPEAUDIT));
        assertEquals(0, Debug.parseModes("all") & Op.DBG_TRACE);
        assertTrue((Debug.parseModes("all") & Op.DBG_PUBCHECK) != 0);
    }
}
