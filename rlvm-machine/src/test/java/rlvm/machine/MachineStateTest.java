package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The session state of ISA section 1: interning through the frozen table formats, growth, footprint =
 * bytes in use, reset in O(used), direct-mapped caches stamped by the epoch, binders ({@link Scope}).
 */
@Tag("fast")
class MachineStateTest {

    static int sort(Machine m, int level) {
        return m.internRecord(Layout.w0(Layout.SORT, Layout.FL_WHNF, 0), level, 0, 0);
    }

    static void assertAllZero(int[] a, String what) {
        for (int i = 0; i < a.length; i++) {
            if (a[i] != 0) {
                throw new AssertionError(what + "[" + i + "] = " + a[i] + " after reset");
            }
        }
    }

    @Test
    void recordsAreHashConsedAndFlagsAreNotIdentity() {
        Machine m = new Machine();
        int a = m.internRecord(Layout.w0(Layout.SORT, Layout.FL_WHNF, 0), 5, 0, 0);
        assertEquals(Layout.FIRST_RECORD, a, "records 0 (NONE) and 1 (HOLE) are reserved");
        assertEquals(a, m.internRecord(Layout.w0(Layout.SORT, 0, 0), 5, 0, 0), "flags are not part of the key (I-FLAG)");
        assertNotEquals(a, m.internRecord(Layout.w0(Layout.SORT, 0, 0), 6, 0, 0));
        int pi = m.internRecord(Layout.w0(Layout.PI, 0, 0), 9, 4, 1);
        int pii = m.internRecord(Layout.w0(Layout.PII, 0, 0), 9, 4, 1);
        assertNotEquals(pi, pii, "kind is part of identity (bugs/pi-closure-context)");
        assertNotEquals(pi, m.internRecord(Layout.w0(Layout.PI, 0, 0), 9, 4, 2), "ls is part of closure identity");
        int big0 = m.internRecord(Layout.w0(Layout.NBIG, 0, Layout.NBIG_INLINE), 7, 0, 0);
        int big1 = m.internRecord(Layout.w0(Layout.NBIG, 0, Layout.NBIG_STORE), 7, 0, 0);
        assertNotEquals(big0, big1, "aux is part of the key of NBIG");
        assertEquals(Layout.SORT, Values.kind(m, a));
        assertEquals(Layout.K_IMM, Values.kind(m, Layout.imm(3)));
        assertEquals(Layout.FL_WHNF, Values.flags(m, a));
        assertEquals(5, Values.a(m, a));
        Values.setMemo(m, a, 2, 77);
        assertEquals(77, Values.memo(m, a, 2));
        assertEquals(77, m.vh[(a << Layout.REC_SHIFT) + Layout.M2]);
    }

    @Test
    void footprintIsBytesInUseNotCapacity() {
        Machine m = new Machine();
        assertEquals(0, m.engineBytes());
        assertEquals(0, m.footprintBytes());
        for (int i = 0; i < 100; i++) {
            sort(m, i);
        }
        assertEquals(100 * (Layout.REC_BYTES + 4), m.engineBytes(), "32 bytes per record + its intern slot");
        int f = TestTables.internRun(m, Layout.RUN_FRAME, 0, 10, 11, 12);
        assertEquals(f, TestTables.internRun(m, Layout.RUN_FRAME, 0, 10, 11, 12));
        assertEquals(100 * 36 + 4 * (2 + 3) + 4, m.engineBytes(), "run words + its rtab slot");
        TestTables.spineRun(m, 1, 2);
        assertEquals(100 * 36 + 4 * (5 + 4) + 4, m.engineBytes(), "a spine is not in rtab");
        TestTables.pairAdd(m, 5, 9, Layout.POS);
        assertEquals(100 * 36 + 4 * 9 + 4 + 4 * (4 + 1), m.engineBytes(), "4 words per pair entry + its log entry");
        // working storage is not counted
        m.ensureScratch(1000);
        m.xp = 1000;
        m.ensureEnv(500);
        m.ep = 500;
        assertEquals(100 * 36 + 4 * 9 + 4 + 20, m.engineBytes());
        assertTrue(m.vh.length * 4L >= 32L * m.engineBytes(), "the capacity of the record slab alone is many times the footprint and is not part of it");
    }

    @Test
    void resetForgetsEverythingAndLeavesEveryTableEmpty() {
        Machine m = new Machine();
        long ticksBefore;
        m.beginDecl(1, -1, 1_000_000, Long.MAX_VALUE);
        Toy.run(m, Toy.sum(m, 10, 0));
        ticksBefore = m.ticks();
        int[] recs = new int[5000];
        for (int i = 0; i < recs.length; i++) {
            recs[i] = sort(m, i);
        }
        int far = TestTables.internRun(m, Layout.RUN_REST, 0, 1, 2, 3);
        for (int i = 0; i < 2000; i++) {
            TestTables.internRun(m, Layout.RUN_FRAME, i % 3 == 0 ? far : 0, i, i + 1, i + 2);
            TestTables.spineRun(m, i, i);
            TestTables.pairAdd(m, recs[i], recs[i + 1], Layout.POS);
            TestTables.pairAdd(m, recs[i], Layout.imm(i), Layout.NEG);
        }
        int type = sort(m, 99_999);
        int x = Scope.open(m, type);
        assertTrue(Scope.inScope(m, x));
        int epoch = m.epoch;
        assertTrue(m.engineBytes() > 0);

        m.reset();

        assertEquals(0, m.engineBytes());
        assertEquals(Layout.FIRST_RECORD, m.vp);
        assertEquals(Layout.FIRST_RUN, m.rsp);
        assertEquals(0, m.pairUsed);
        assertEquals(0, m.plogN);
        assertEquals(0, m.rtabUsed);
        assertEquals(epoch + 1, m.epoch, "the direct-mapped caches are invalidated by the epoch");
        assertAllZero(m.vtab, "vtab");
        assertAllZero(m.rtab, "rtab");
        assertAllZero(m.pair, "pair");
        assertTrue(m.idle());
        assertEquals(ticksBefore, m.ticks(), "steps() is not cleared by reset (ISA 14.5)");
        // the session starts over with the same ids
        assertEquals(Layout.FIRST_RECORD, sort(m, 4711));
        assertEquals(0, TestTables.pairStatus(m, recs[0], recs[1]));
        // FV0 does not answer from the previous session: the variable is made again
        int type2 = sort(m, 99_999);
        int y = Scope.open(m, type2);
        assertEquals(Layout.FVAR, Values.kind(m, y));
        assertEquals(type2, Values.a(m, y));
        Scope.close(m, 1);
    }

    /**
     * NOTES-INT I5: intern tables that grew are pooled and reused. Every pooled table and both base tables are
     * all zero after a reset (a stale entry could name a record beyond the bump pointer), the tables are back at
     * their base size, and a grown table still finds what was interned before it grew.
     */
    @Test
    void pooledTablesAreZeroAfterGrowthAndReset() {
        for (Machine m : new Machine[] {new Machine(), new Machine(Machine.Sizes.tiny())}) {
            for (int round = 0; round < 5; round++) {
                int n = 3000 << (2 * (round % 3)); // 3,000 / 12,000 / 48,000 records and frames: several doublings
                int firstRecord = sort(m, 0);
                int firstRun = TestTables.internRun(m, Layout.RUN_FRAME, 0, 0, 1, 2);
                for (int i = 1; i < n; i++) {
                    sort(m, i);
                    TestTables.internRun(m, Layout.RUN_FRAME, 0, i, i + 1, i + 2);
                }
                assertEquals(firstRecord, sort(m, 0), "the grown vtab finds the first record");
                assertEquals(firstRun, TestTables.internRun(m, Layout.RUN_FRAME, 0, 0, 1, 2), "the grown rtab finds the first run");
                assertEquals(n, m.vp - m.vbase, "no record was made twice");
                m.reset();
                assertTrue(m.tablesClean(), "round " + round + ": a table or a pooled table is not all zero");
                assertEquals(1 << m.sizes.vtab, m.vtab.length);
                assertEquals(1 << m.sizes.rtab, m.rtab.length);
            }
        }
    }

    /**
     * NOTES-M6 F25: session arrays are pooled per machine. A slab that outgrows its kept size leaves the kept
     * array behind and the reset takes it back (no new array, no regrowth from the base); pair tables are
     * pooled by size and every pooled one is all zero; working arrays keep what they grew to up to
     * 2^keepStack words. With {@code pool = false} the same session allocates at every reset.
     */
    @Test
    void sessionArraysArePooledAcrossResets() {
        Machine.Sizes z = Machine.Sizes.tiny();
        z.keepRecords = 8;   // 256 records kept
        z.keepRunWords = 10; // 1024 run words kept
        z.keepStack = 12;
        Machine m = new Machine(z);
        // warm-up: grow to the kept sizes (these arrays stay)
        for (int i = 0; i < 200; i++) {
            sort(m, i);
            TestTables.internRun(m, Layout.RUN_FRAME, 0, i, i + 1);
        }
        m.reset();
        int[] keptRecords = m.vh;
        int[] keptRuns = m.rs;
        assertEquals(Layout.REC_WORDS << 8, keptRecords.length);
        assertEquals(1 << 10, keptRuns.length);
        long grows = m.allocGrows;
        for (int round = 0; round < 4; round++) {
            for (int i = 0; i < 5000; i++) {
                sort(m, i);
                TestTables.internRun(m, Layout.RUN_FRAME, 0, i, i + 1, i + 2);
                if (i < 2000) { // the pair log stays below 2^keepStack entries
                    TestTables.pairAdd(m, Layout.FIRST_RECORD + i, Layout.imm(i), Layout.POS);
                }
            }
            assertTrue(m.vh.length > keptRecords.length && m.rs.length > keptRuns.length);
            assertTrue(m.ensureScratch(3000) && m.ensureEnv(3000) && m.ensureRegs(3000));
            assertEquals(Layout.POS, TestTables.pairStatus(m, Layout.imm(77), Layout.FIRST_RECORD + 77));
            int[] x = m.X;
            int[] es = m.es;
            int[] rs = m.RS;
            m.reset();
            assertTrue(m.vh == keptRecords, "round " + round + ": the kept record slab came back");
            assertTrue(m.rs == keptRuns, "round " + round + ": the kept run slab came back");
            assertEquals(Layout.PAIR_WORDS << z.pairs, m.pair.length);
            assertTrue(m.X == x && m.es == es && m.RS == rs, "working arrays below 2^keepStack words are kept");
            assertTrue(m.tablesClean(), "round " + round + ": a pooled table is not all zero");
            assertEquals(0, m.allocResets, "nothing is allocated by a reset");
            assertEquals(Layout.FIRST_RECORD, sort(m, 123), "the slab is reused from its start");
            m.reset();
        }
        long perRound = (m.allocGrows - grows) / 4;
        assertTrue(perRound > 0, "the slabs beyond the kept size are still grown by doubling");
        // a working array beyond 2^keepStack words returns to its base
        assertTrue(m.ensureScratch(1 << 13));
        m.reset();
        assertEquals(1 << z.scratch, m.X.length);
        assertEquals(1, m.allocResets);
        // the same without the pool: every reset after a large session allocates
        Machine.Sizes y = Machine.Sizes.tiny();
        y.keepRecords = 8;
        y.keepRunWords = 10;
        y.pool = false;
        Machine n = new Machine(y);
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 5000; i++) {
                sort(n, i);
                TestTables.internRun(n, Layout.RUN_FRAME, 0, i, i + 1, i + 2);
                TestTables.pairAdd(n, Layout.FIRST_RECORD + i, Layout.imm(i), Layout.POS);
            }
            n.reset();
            assertTrue(n.tablesClean());
            assertEquals(Layout.REC_WORDS << y.records, n.vh.length, "without the pool the slab returns to the base");
        }
        assertTrue(n.allocResets >= 9, "records, runs and pairs at each of the three resets: " + n.allocResets);
        // under poison a kept slab that comes back holds no stale record
        Machine q = new Machine(z);
        q.debug = Op.DBG_POISON;
        for (int i = 0; i < 5000; i++) {
            sort(q, i);
        }
        int stale = sort(q, 4000);
        q.reset();
        assertEquals(0, q.vh[(Layout.FIRST_RECORD + 3) << Layout.REC_SHIFT], "the dead session's records read as kind 0");
        assertTrue(stale > 0 && q.vh.length == Layout.REC_WORDS << 8);
    }

    /** ISA 1.6: a session of one small declaration resets in time proportional to that declaration. */
    @Test
    void resetIsProportionalToWhatWasUsed() {
        Machine m = new Machine();
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 20_000; i++) {
                sort(m, i);
            }
            m.reset();
        }
        // 200,000 sessions of one record: with a full clear of the 2 MiB table (about 50 microseconds)
        // this would take 10 s; proportional to the use it takes a few milliseconds.
        long t0 = System.nanoTime();
        for (int i = 0; i < 200_000; i++) {
            sort(m, i);
            TestTables.pairAdd(m, 2, Layout.imm(i), Layout.POS);
            TestTables.internRun(m, Layout.RUN_FRAME, 0, i);
            m.reset();
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 2_000, "200,000 one-record sessions took " + ms + " ms: reset is not O(used)");
        assertAllZero(m.vtab, "vtab");
        assertAllZero(m.rtab, "rtab");
        assertAllZero(m.pair, "pair");
    }

    @Test
    void everyArrayGrowsAndReturnsToItsBaseSizeAtTheNextReset() {
        Machine.Sizes tiny = Machine.Sizes.tiny();
        Machine m = new Machine(tiny);
        int n = 20_000;
        int[] ids = new int[n];
        for (int i = 0; i < n; i++) {
            ids[i] = sort(m, i);
            assertEquals(Layout.FIRST_RECORD + i, ids[i]);
        }
        for (int i = 0; i < n; i++) {
            assertEquals(ids[i], sort(m, i), "found again after the tables grew");
        }
        assertTrue(m.vtab.length >= 2 * n, "load <= 0.5");
        int[] runs = new int[3000];
        for (int i = 0; i < runs.length; i++) {
            runs[i] = TestTables.internRun(m, Layout.RUN_FRAME, 0, i, 2 * i);
            if (i % 7 == 0) {
                TestTables.spineRun(m, i, i, i);
            }
        }
        for (int i = 0; i < runs.length; i++) {
            assertEquals(runs[i], TestTables.internRun(m, Layout.RUN_FRAME, 0, i, 2 * i));
            assertEquals(2, Runs.len(m, runs[i]));
            assertEquals(2 * i, Runs.arg(m, runs[i], 1));
            assertEquals(i, Runs.fget(m, runs[i], 1), "FGET: slot 0 is the outermost, index 0 the innermost");
        }
        for (int i = 0; i < 5000; i++) {
            TestTables.pairAdd(m, ids[i], ids[i + 1], i % 2 == 0 ? Layout.POS : Layout.NEG);
        }
        for (int i = 0; i < 5000; i++) {
            assertEquals(i % 2 == 0 ? Layout.POS : Layout.NEG, TestTables.pairStatus(m, ids[i + 1], ids[i]), "unordered key");
        }
        TestTables.pairAdd(m, ids[0], ids[1], Layout.FAILED);
        assertEquals(Layout.POS | Layout.FAILED, TestTables.pairStatus(m, ids[0], ids[1]), "one entry holds all status bits");
        assertTrue(m.ensureScratch(10_000) && m.ensureEnv(10_000) && m.ensureRegs(10_000));
        for (int i = 0; i < 2000; i++) {
            assertTrue(m.pushScope(ids[i]));
        }
        m.ssp = 0;

        m.reset();

        assertEquals(Layout.REC_WORDS << tiny.records, m.vh.length);
        assertEquals(1 << tiny.vtab, m.vtab.length);
        assertEquals(1 << tiny.runWords, m.rs.length);
        assertEquals(1 << tiny.rtab, m.rtab.length);
        assertEquals(Layout.PAIR_WORDS << tiny.pairs, m.pair.length);
        assertEquals(1 << tiny.scratch, m.X.length);
        assertEquals(1 << tiny.env, m.es.length);
        assertEquals(0, m.engineBytes());
        assertEquals(Layout.FIRST_RECORD, sort(m, 1));
    }

    /** ISA 1.5: pair keys are ordered as unsigned numbers, so a pair with an immediate is as distinct as any other. */
    @Test
    void aPairKeyWithAnImmediateIsDistinct() {
        Machine m = new Machine();
        int a = sort(m, 1);
        int b = sort(m, 2);
        TestTables.pairAdd(m, a, Layout.imm(0), Layout.POS);
        TestTables.pairAdd(m, Layout.imm(7), b, Layout.NEG);
        TestTables.pairAdd(m, Layout.imm(7), Layout.imm(8), Layout.FAILED);
        assertEquals(Layout.POS, TestTables.pairStatus(m, Layout.imm(0), a));
        assertEquals(Layout.NEG, TestTables.pairStatus(m, b, Layout.imm(7)));
        assertEquals(Layout.FAILED, TestTables.pairStatus(m, Layout.imm(8), Layout.imm(7)));
        assertEquals(0, TestTables.pairStatus(m, a, b));
        assertEquals(0, TestTables.pairStatus(m, a, Layout.imm(1)));
        int slot = TestTables.findPair(m, a, Layout.imm(0)) << Layout.PAIR_SHIFT;
        assertEquals(a, m.pair[slot + Layout.PAIR_LO], "the record ref is the unsigned-smaller one");
        assertEquals(Layout.imm(0), m.pair[slot + Layout.PAIR_HI]);
    }

    /** ISA 2.8: OPEN takes the first variable of the type that is not in scope; a re-opened binder reproduces ids. */
    @Test
    void openAndCloseReproduceVariableIds() {
        Machine m = new Machine();
        int nat = sort(m, 1);
        int bool = sort(m, 2);
        int x0 = Scope.open(m, nat);
        int x1 = Scope.open(m, nat);
        int b0 = Scope.open(m, bool);
        int x2 = Scope.open(m, nat);
        assertEquals(4, m.ssp);
        assertEquals(Values.mkFVar(m, nat, 0), x0);
        assertEquals(Values.mkFVar(m, nat, 1), x1);
        assertEquals(Values.mkFVar(m, nat, 2), x2);
        assertEquals(Values.mkFVar(m, bool, 0), b0);
        assertTrue(Scope.inScope(m, x0) && Scope.inScope(m, x1) && Scope.inScope(m, x2) && Scope.inScope(m, b0));
        assertEquals(Layout.FL_OPEN | Layout.FL_WHNF | Layout.FL_INSCOPE, Values.flags(m, x1));
        Scope.close(m, 2);
        assertFalse(Scope.inScope(m, x2) || Scope.inScope(m, b0));
        assertTrue(Scope.inScope(m, x1));
        int vpBefore = m.vp;
        assertEquals(x2, Scope.open(m, nat), "the third Nat binder gets the same variable again");
        assertEquals(b0, Scope.open(m, bool));
        Scope.close(m, 4);
        assertEquals(0, m.ssp);
        // a full cycle again: same ids, no new record, I-FV2 (no two open binders hold the same variable)
        assertEquals(x0, Scope.open(m, nat));
        assertEquals(x1, Scope.open(m, nat));
        assertEquals(x2, Scope.open(m, nat));
        int x3 = Scope.open(m, nat);
        assertEquals(Values.mkFVar(m, nat, 3), x3);
        assertEquals(vpBefore + 1, m.vp, "only FVAR(Nat, 3) is new");
        Scope.closeTo(m, 1);
        assertTrue(Scope.inScope(m, x0));
        assertFalse(Scope.inScope(m, x1));
        m.closeBindersAbove(0);
        assertEquals(x0, Scope.open(m, nat));
        Scope.close(m, 1);
    }

    @Test
    void theBinderLimitIsARecoverableLimit() {
        Machine m = new Machine();
        int t = sort(m, 1);
        for (int i = 0; i < Op.BINDERS_MAX; i++) {
            assertTrue(Scope.open(m, t) < Op.SIG);
        }
        assertEquals(Op.ABORT, Scope.open(m, t));
        assertEquals(rlvm.api.MachineErrors.E_LIMIT, m.err);
        m.abandon();
        assertTrue(m.idle());
    }

    @Test
    void getIsBoundsCheckedAndAHoleIsUnreadable() {
        Machine m = new Machine();
        m.es[0] = 20;
        m.es[1] = Layout.HOLE;
        m.es[2] = 22;
        m.ep = 3;
        assertEquals(22, Runs.get(m, 0, 3, 0), "index 0 is the innermost binding");
        assertEquals(20, Runs.get(m, 0, 3, 2));
        assertEquals(Op.FATAL, Runs.get(m, 0, 3, 1));
        assertEquals(rlvm.api.MachineErrors.E_HOLE, m.err);
        assertEquals(Op.FATAL, Runs.get(m, 0, 3, 3));
        assertEquals(rlvm.api.MachineErrors.E_INTERNAL, m.err);
        assertEquals(Op.FATAL, Runs.get(m, 0, 3, -1));
    }

    @Test
    void switchesAndDebugModesAreOffByDefaultAndParseByName() {
        Machine m = new Machine();
        assertEquals(0, m.sw, "v0: no staging switch is on");
        assertEquals(0, m.debug);
        assertEquals(Op.HOST_DEPTH, m.hostDepth);
        assertFalse(m.on(Op.SW_FACTS) || m.on(Op.SW_PROBES) || m.on(Op.SW_CODE));
        assertEquals(Op.SW_WARM | Op.SW_GATED_TABLING, Debug.parseSwitches("warm, gated"));
        assertEquals(Op.SW_PROBES, Debug.parseSwitches("probes"));
        assertEquals(Op.SW_INERT_LOOP, Debug.parseSwitches("inert"));
        assertEquals(0, Debug.parseSwitches(""));
        assertEquals(0, Debug.parseSwitches("all") & Op.SW_K2);
        assertEquals(Op.DBG_MEMOCHECK | Op.DBG_TRACE, Debug.parseModes("memocheck,trace"));
        assertEquals("memocheck,trace", Debug.modeNames(Op.DBG_MEMOCHECK | Op.DBG_TRACE));
        assertEquals("gated,warm", Debug.switchNames(Op.SW_WARM | Op.SW_GATED_TABLING));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> Debug.parseSwitches("fast"));
    }
}
