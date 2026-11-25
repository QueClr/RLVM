package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ServiceLoader;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import rlvm.api.Budget;
import rlvm.api.EngineConfig;
import rlvm.api.EngineProvider;
import rlvm.api.MachineErrors;
import rlvm.api.Outcome;
import rlvm.api.Reason;
import rlvm.api.RunSignals;
import rlvm.api.StatsSink;

/**
 * The facade of ISA section 14 as far as it does not depend on a judgment: registration, the session
 * life cycle, fail-closed outcomes, footprint and steps.
 */
@Tag("fast")
class M6FacadeTest {

    @Test
    void theProviderIsRegisteredAsMachineBesideTheReference() {
        EngineProvider machine = null;
        int references = 0;
        for (EngineProvider p : ServiceLoader.load(EngineProvider.class)) {
            if (p.name().equals("machine")) {
                machine = p;
            }
            if (p.isReference()) {
                references++;
            }
        }
        assertNotNull(machine, "META-INF/services/rlvm.api.EngineProvider names rlvm.machine.MachineProvider");
        assertFalse(machine.isReference());
        assertTrue(machine instanceof MachineProvider);
        assertEquals(1, references, "exactly one reference engine on the test class path");
        assertEquals("machine", machine.factory().create(Fixture.prelude0().store, EngineConfig.defaults()).name());
    }

    @Test
    void theProviderTakesItsSettingsFromTheCommandLine() {
        MachineProvider p = new MachineProvider();
        assertTrue(p.runDefaults(new java.util.HashMap<>()).isEmpty(), "the machine caps its sessions itself: no driver setting is needed");
        assertEquals(10, p.priority(), "not the default engine while the wrong accepts E1-F1 .. E1-F4 are open (it was 100 = Engines.DEFAULT_ENGINE_MIN_PRIORITY from 19:41Z to 20:35Z on 2026-11-24)");
        assertTrue(p.optionKeys().contains("machine.debug") && p.optionKeys().contains("machine.switches"));
        assertEquals("switches " + Debug.switchNames(Op.SW_DEFAULT), p.defaultsNote(), "'rlvm --version' names the default switch set");
        assertEquals(Op.SW_DEFAULT, Debug.parseSwitches(p.defaultsNote().substring("switches ".length())));
        Fixture f = Fixture.prelude0();
        java.util.Map<String, String> options = new java.util.HashMap<>();
        options.put("machine.debug", "memocheck,poison");
        options.put("machine.switches", "warm");
        options.put("machine.hostDepth", "0");
        options.put("machine.memocheck.every", "16");
        options.put("ref.imports", "something of another provider");
        MachineEngine e = (MachineEngine) p.factory(options).create(f.store, EngineConfig.defaults());
        assertEquals(Op.DBG_MEMOCHECK | Op.DBG_POISON, e.debug);
        assertEquals(Op.SW_WARM, e.switches);
        assertEquals(0, e.hostDepth);
        MachineSession s = (MachineSession) e.newSession(f.ctx);
        assertEquals(0, s.m.hostDepth);
        assertEquals(Op.DBG_MEMOCHECK | Op.DBG_POISON, s.m.debug);
        assertEquals(16, Debug.state(s.m).every);
        MachineEngine strict = (MachineEngine) p.factory(options).create(f.store, EngineConfig.defaults().withStrict(true));
        assertEquals(0, strict.switches, "a strict engine has no switch, whatever the command line says");
        assertEquals(Op.DBG_MEMOCHECK | Op.DBG_POISON, strict.debug, "debug modes are audits, not shortcuts: they stay");
        options.put("machine.debug", "memocheck,nonsense");
        assertThrows(IllegalArgumentException.class, () -> p.factory(options));
        options.put("machine.debug", "");
        options.put("machine.hostDepth", "-3");
        assertThrows(IllegalArgumentException.class, () -> p.factory(options));
        MachineEngine d = (MachineEngine) p.factory(java.util.Map.of()).create(f.store, EngineConfig.defaults().withMemoCheck(true));
        assertEquals(Op.DBG_MEMOCHECK, d.debug, "EngineConfig.memoCheck is the debug mode memocheck");
        assertEquals(0, d.switches);
        assertEquals(Op.HOST_DEPTH, d.hostDepth);
    }

    @Test
    void aSessionNeverThrowsAndNeverRejectsAWellTypedDeclaration() {
        Fixture f = Fixture.prelude();
        f.seal();
        MachineSession s = f.session;
        assertNull(s.ops(), "no KernelOps in release configurations (ISA 14.6)");
        long steps = s.steps();
        // a well-typed definition: ACCEPT once the judgments exist, DECLINE (never REJECT) until then
        int o = s.check(f.cid("two"), Budget.strict(1_000_000, 1L << 30));
        assertNotEquals(Outcome.REJECT, o, () -> "a good declaration was rejected: " + s.lastMessage());
        if (o == Outcome.DECLINE) {
            assertNotNull(s.lastReason());
            assertFalse(s.lastReason().isReject());
            assertTrue(s.lastMessage().contains("two"), s.lastMessage());
        }
        assertTrue(s.steps() >= steps, "steps are monotone");
        // a constant that no engine checks
        assertEquals(Outcome.DECLINE, s.check(f.cid("Nat.rec"), Budget.warm()));
        assertEquals(Reason.UNSUPPORTED_INPUT, s.lastReason());
        s.reset();
        s.reset();
        assertEquals(f.ctx.footprintBytes(), s.footprintBytes(), "after a reset only the context's overlays are in use");
        assertTrue(f.m.idle());
    }

    @Test
    void strictEnginesHaveNoSwitchAndStatisticsCostNothingWhenOff() {
        Fixture f = Fixture.prelude();
        f.seal();
        MachineEngine strict = new MachineEngine(f.store, EngineConfig.defaults().withStrict(true));
        assertEquals(0, strict.switches, "strict mode: generic tier, complete procedure, no shared facts or code");
        MachineEngine plain = new MachineEngine(f.store, EngineConfig.defaults());
        assertEquals(0, plain.switches, "v0: every staging switch is off unless -Drlvm.machine.switches says otherwise");
        assertEquals(Op.HOST_DEPTH, plain.hostDepth);
        int[] puts = new int[1];
        StatsSink sink = new StatsSink() {
            @Override
            public void put(String key, long value) {
                puts[0]++;
            }

            @Override
            public void put(String key, double value) {
                puts[0]++;
            }

            @Override
            public void put(String key, String value) {
                puts[0]++;
            }

            @Override
            public void table(String key, String[] columns, long[][] rows, String[] rowLabels) {
                puts[0]++;
            }
        };
        plain.dumpStats(sink);
        assertEquals(0, puts[0]);
        MachineEngine withStats = new MachineEngine(f.store, EngineConfig.defaults().withStats(true));
        withStats.newSession(f.ctx).close();
        withStats.dumpStats(sink);
        assertTrue(puts[0] > 5);
    }

    // ---------------------------------------------------------------------------------------------------
    // The facade with toy DECL drivers: everything of ISA 14 that does not depend on a real judgment
    // ---------------------------------------------------------------------------------------------------

    static final long BIG = 1L << 30;

    private static int sort(Machine m, int level) {
        return m.internRecord(Layout.w0(Layout.SORT, Layout.FL_WHNF, 0), level, 0, 0);
    }

    /** A sealed prelude whose session runs the toy family; the driver is installed by each test. */
    private static Fixture toys() {
        Fixture f = Fixture.prelude();
        f.seal();
        f.m.ext = Toy.RESUMER;
        return f;
    }

    /** ISA 14.3, row by row: machine code -> outcome and rlvm.api.Reason. */
    private static final Object[][] REASONS = {
        {MachineErrors.E_TYPE_EXPECTED, Outcome.REJECT, Reason.TYPE_EXPECTED},
        {MachineErrors.E_FUN_EXPECTED, Outcome.REJECT, Reason.FUNCTION_EXPECTED},
        {MachineErrors.E_APP_MISMATCH, Outcome.REJECT, Reason.APP_TYPE_MISMATCH},
        {MachineErrors.E_LET_MISMATCH, Outcome.REJECT, Reason.LET_TYPE_MISMATCH},
        {MachineErrors.E_TYPE_MISMATCH, Outcome.REJECT, Reason.DECL_TYPE_MISMATCH},
        {MachineErrors.E_NOT_PROP, Outcome.REJECT, Reason.THEOREM_NOT_PROP},
        {MachineErrors.E_PROJ, Outcome.REJECT, Reason.INVALID_PROJECTION},
        {MachineErrors.E_UNKNOWN_CONST, Outcome.REJECT, Reason.UNKNOWN_CONSTANT},
        {MachineErrors.E_LEVEL_ARITY, Outcome.REJECT, Reason.LEVEL_ARITY_MISMATCH},
        {MachineErrors.E_LEVEL_PARAM, Outcome.REJECT, Reason.UNDECLARED_LEVEL_PARAM},
        {MachineErrors.E_NATSIZE, Outcome.REJECT, Reason.NAT_LITERAL_TOO_BIG},
        {MachineErrors.E_STEPS, Outcome.DECLINE, Reason.BUDGET_STEPS},
        {MachineErrors.E_MEM, Outcome.DECLINE, Reason.BUDGET_MEMORY},
        {MachineErrors.E_STACK, Outcome.DECLINE, Reason.STACK_DEPTH},
        {MachineErrors.E_LIMIT, Outcome.DECLINE, Reason.UNSUPPORTED_INPUT},
        {MachineErrors.E_PRELUDE, Outcome.DECLINE, Reason.UNSUPPORTED_PRELUDE},
        {MachineErrors.E_HOLE, Outcome.DECLINE, Reason.INTERNAL_ERROR},
        {MachineErrors.E_VIS, Outcome.DECLINE, Reason.INTERNAL_ERROR},
        {MachineErrors.E_ILLTYPED, Outcome.DECLINE, Reason.INTERNAL_ERROR},
        {MachineErrors.E_INTERNAL, Outcome.DECLINE, Reason.INTERNAL_ERROR},
        {MachineErrors.E_AUDIT, Outcome.DECLINE, Reason.INTERNAL_ERROR},
        {MachineErrors.E_CANCEL, Outcome.DECLINE, Reason.CANCELLED},
        {77, Outcome.DECLINE, Reason.INTERNAL_ERROR}, // an unknown code declines, never rejects
        {-1, Outcome.DECLINE, Reason.INTERNAL_ERROR},
    };

    @Test
    void theReasonTableOfIsa143AndAResetAfterEveryOutcomeOtherThanAccept() {
        Fixture f = toys();
        MachineSession s = f.session;
        int cid = f.cid("two");
        for (Object[] row : REASONS) {
            int code = (Integer) row[0];
            boolean[] ran = new boolean[1];
            s.driver = (m, c) -> {
                assertTrue(m.pristine(), "v0: every declaration starts in an empty session");
                assertTrue(m.idle());
                sort(m, 5);
                TestTables.internRun(m, Layout.RUN_FRAME, 0, sort(m, 6));
                TestTables.pairAdd(m, sort(m, 5), sort(m, 6), Layout.POS);
                int x = Scope.open(m, sort(m, 5)); // a failing judgment leaves its binder, scratch and frames behind
                m.X[m.xp++] = x;
                m.es[m.ep++] = x;
                ran[0] = true;
                return code;
            };
            int outcome = s.check(cid, Budget.warm());
            assertTrue(ran[0]);
            assertEquals(row[1], outcome, () -> "outcome of " + MachineErrors.name(code));
            assertEquals(row[2], s.lastReason(), () -> "reason of " + MachineErrors.name(code));
            assertEquals(code, s.lastCode());
            assertEquals(outcome == Outcome.REJECT, s.lastReason().isReject());
            assertTrue(s.lastMessage().contains("'two'"), s.lastMessage());
            assertTrue(s.lastMessage().contains(MachineErrors.name(code)), s.lastMessage());
            assertTrue(f.m.pristine(), "nothing derived for a failed unit survives (ISA 14.1)");
            assertTrue(f.m.idle());
            assertEquals(f.ctx.footprintBytes(), s.footprintBytes());
        }
        // every code of rlvm.api.MachineErrors has a row above
        for (int code = 1; code < MachineErrors.COUNT; code++) {
            if (!MachineErrors.isDefined(code) || code == MachineErrors.E_LOOSE_BVAR) {
                continue;
            }
            boolean found = false;
            for (Object[] row : REASONS) {
                found |= (Integer) row[0] == code;
            }
            assertTrue(found, "no row for " + MachineErrors.name(code));
        }
        assertEquals(MachineErrors.E_NONE, 0, "Decl.check returns 0 for ACCEPT");
        assertEquals(REASONS.length, s.rejected + s.declined);
        assertEquals(11, s.rejected);
    }

    @Test
    void footprintIsBytesInUseAndV0KeepsAnAcceptedDeclarationUntilTheNextOne() {
        Fixture f = toys();
        MachineSession s = f.session;
        int[] made = new int[1];
        s.driver = (m, c) -> {
            assertTrue(m.pristine(), "session = one declaration (ISA section 11)");
            for (int i = 0; i < 1000; i++) {
                sort(m, 1000 + i);
            }
            made[0]++;
            return MachineErrors.E_NONE;
        };
        long before = f.ctx.footprintBytes();
        assertEquals(Outcome.ACCEPT, s.check(f.cid("two"), Budget.warm()));
        assertNull(s.lastReason());
        assertEquals(0, s.lastCode());
        assertEquals(before + 1000 * (Layout.REC_BYTES + 4), s.footprintBytes(), "bytes in use: 32 per record + its intern slot");
        assertTrue(f.m.vh.length * 4L >= 3L * 1000 * Layout.REC_BYTES, "the capacity (of the record slab alone: several times the bytes in use) is not part of it");
        assertTrue(s.footprintBytes() < 1L << 20);
        assertFalse(f.m.pristine(), "v0 keeps what an accepted declaration derived until the next declaration starts");
        assertEquals(Outcome.ACCEPT, s.check(f.cid("id"), Budget.warm()));
        assertEquals(before + 1000 * (Layout.REC_BYTES + 4), s.footprintBytes(), "not accumulated: the session was reset in between");
        assertEquals(2, made[0]);
        s.reset();
        assertEquals(f.ctx.footprintBytes(), s.footprintBytes());
        assertEquals(2, s.accepted);
    }

    @Test
    void strictModeIsAFreshSessionBeforeAndAfterEveryDeclarationAndHasNoSwitch() {
        String old = System.getProperty("rlvm.machine.switches");
        System.setProperty("rlvm.machine.switches", "warm,facts,probes");
        try {
            Fixture f = toys();
            MachineEngine strict = new MachineEngine(f.store, EngineConfig.defaults().withStrict(true));
            MachineEngine plain = new MachineEngine(f.store, EngineConfig.defaults());
            assertEquals(0, strict.switches, "ISA 14.2: generic tier, complete procedure, no shared facts or code, no TRY");
            assertEquals(Op.SW_WARM | Op.SW_FACTS | Op.SW_PROBES, plain.switches);
            MachineSession s = (MachineSession) strict.newSession(f.ctx);
            assertTrue(s.m.strict);
            assertEquals(0, s.m.sw);
            s.driver = (m, c) -> {
                assertTrue(m.pristine());
                assertTrue(m.strict && m.sw == 0 && !m.on(Op.SW_WARM));
                assertEquals(1000, m.fuel, "Budget.maxSteps applies");
                sort(m, 1);
                return MachineErrors.E_NONE;
            };
            assertEquals(Outcome.ACCEPT, s.check(f.cid("two"), Budget.strict(1000, BIG)));
            assertTrue(s.m.pristine(), "reset() before and after (ISA 14.2)");
            assertEquals(f.ctx.footprintBytes(), s.footprintBytes());
            assertEquals(Outcome.ACCEPT, s.check(f.cid("id"), Budget.strict(1000, BIG)));
            assertTrue(s.m.pristine());
            s.close();
        } finally {
            if (old == null) {
                System.clearProperty("rlvm.machine.switches");
            } else {
                System.setProperty("rlvm.machine.switches", old);
            }
        }
    }

    /** Polls of a session, with a first failure that appears at a chosen poll. */
    static final class Signals implements RunSignals {
        final Machine m;
        final int failAtPoll;
        final int failingUnit;
        int polls;
        long lastPollTicks = -1;
        long maxGap;
        long ticksAtFailure = -1;
        boolean memory;

        Signals(Machine m, int failAtPoll, int failingUnit) {
            this.m = m;
            this.failAtPoll = failAtPoll;
            this.failingUnit = failingUnit;
        }

        @Override
        public int firstFailure() {
            polls++;
            long t = m.ticks();
            if (lastPollTicks >= 0) {
                maxGap = Math.max(maxGap, t - lastPollTicks);
            }
            lastPollTicks = t;
            if (polls >= failAtPoll) {
                if (ticksAtFailure < 0) {
                    ticksAtFailure = t;
                }
                return failingUnit;
            }
            return Integer.MAX_VALUE;
        }

        @Override
        public boolean memoryExhausted() {
            return memory;
        }
    }

    @Test
    void cancellationWithin4096TicksAlsoInsideASpeculativeSegment() {
        for (int depth : new int[] {0, Op.HOST_DEPTH}) {
            Fixture f = toys();
            f.hostDepth(depth);
            MachineSession s = f.session;
            int cid = f.cid("two");
            int unit = f.store.decls().unit(cid);
            assertTrue(unit > 0);
            // the work: a loop of 400,000 iterations under a TRY whose budget never runs out
            s.driver = (m, c) -> {
                int ssp = m.ssp;
                int xp = m.xp;
                int ep = m.ep;
                int rp = m.rp;
                int er = m.ER;
                long saved = m.tryEnter(BIG);
                int r = Toy.loop(m, 400_000, 1);
                r = r == Op.SUSP ? m.saveTry(saved, BIG, ssp, xp, ep, rp, er) : Frames.k_try(m, saved, BIG, ssp, xp, ep, rp, er, r);
                r = m.drive(r);
                if (r >= Op.SIG) {
                    m.abandon();
                    return m.err;
                }
                return r == Layout.U ? MachineErrors.E_INTERNAL : MachineErrors.E_NONE;
            };
            // 1. no failure below this unit: the declaration completes, and it was polled every 4096 ticks
            Signals quiet = new Signals(f.m, Integer.MAX_VALUE, 0);
            f.m.signals = quiet;
            long t0 = s.steps();
            assertEquals(Outcome.ACCEPT, s.check(cid, Budget.warm()));
            long work = s.steps() - t0;
            assertTrue(work > 100 * 4096, "enough work: " + work + " ticks");
            // (with D = 0 a judgment that is spilled at a poll point gives its tick back and polls again when
            // the driver enters it: up to two polls per 4096 ticks)
            assertTrue(quiet.polls >= work / 4096 - 1 && quiet.polls <= (depth == 0 ? 2 : 1) * (work / 4096 + 1) + 2,
                    quiet.polls + " polls in " + work + " ticks");
            assertEquals(4096, quiet.maxGap, "never more than 4096 ticks between two polls (ISA 14.4)");
            // 2. a unit below fails while this one runs: fatal, also inside the TRY; the session is reset
            Signals failing = new Signals(f.m, 7, unit - 1);
            f.m.signals = failing;
            t0 = s.steps();
            assertEquals(Outcome.DECLINE, s.check(cid, Budget.warm()));
            assertEquals(Reason.CANCELLED, s.lastReason());
            assertEquals(MachineErrors.E_CANCEL, s.lastCode());
            assertEquals(failing.ticksAtFailure, s.steps(), "the poll that sees the failure ends the declaration: no tick after it");
            assertTrue(s.steps() - t0 <= 7 * 4096 && s.steps() - t0 >= 2 * 4096, "stopped at the 7th poll: " + (s.steps() - t0));
            assertTrue(failing.maxGap <= 4096);
            assertTrue(f.m.pristine() && f.m.idle());
            // 3. the failing unit is this one or a later one: not cancelled (the verdict rule needs this unit)
            f.m.signals = new Signals(f.m, 3, unit);
            assertEquals(Outcome.ACCEPT, s.check(cid, Budget.warm()));
            // 4. the run's memory watermark: fatal too
            Signals mem = new Signals(f.m, Integer.MAX_VALUE, 0);
            mem.memory = true;
            f.m.signals = mem;
            t0 = s.steps();
            assertEquals(Outcome.DECLINE, s.check(cid, Budget.warm()));
            assertEquals(Reason.BUDGET_MEMORY, s.lastReason());
            assertTrue(s.steps() - t0 <= 4096);
            f.close();
        }
    }

    @Test
    void aFatalFaultInsideATryDeclinesAndAKernelErrorInsideATryIsU() {
        for (int depth : new int[] {0, 3, Op.HOST_DEPTH}) {
            Fixture f = toys();
            f.hostDepth(depth);
            MachineSession s = f.session;
            int[] result = new int[1];
            int[] mode = new int[1];
            s.driver = (m, c) -> {
                int ty = sort(m, 2);
                int r = m.drive(Toy.outer(m, ty, 20, mode[0], 1 << 20, 0));
                if (r >= Op.SIG) {
                    m.abandon();
                    return m.err;
                }
                result[0] = r;
                assertTrue(m.idle(), "a completed judgment restored the stacks");
                return MachineErrors.E_NONE;
            };
            mode[0] = Toy.FAULT; // fault(E_HOLE) 20 binders deep inside the segment
            assertEquals(Outcome.DECLINE, s.check(f.cid("two"), Budget.warm()), "ISA 1.4: never turned into U");
            assertEquals(Reason.INTERNAL_ERROR, s.lastReason());
            assertEquals(MachineErrors.E_HOLE, s.lastCode());
            assertTrue(f.m.pristine() && f.m.idle(), "a fatal fault resets the session at once");
            mode[0] = Toy.FAIL; // a kernel error inside the segment: U, the declaration goes on
            assertEquals(Outcome.ACCEPT, s.check(f.cid("two"), Budget.warm()));
            assertEquals(777, result[0], "OUTER maps U to 777");
            mode[0] = Toy.OK;
            assertEquals(Outcome.ACCEPT, s.check(f.cid("two"), Budget.warm()));
            assertNotEquals(777, result[0]);
            f.close();
        }
    }

    @Test
    void stepAndByteBudgetsDeclineWithTheirReasons() {
        Fixture f = toys();
        MachineSession s = f.session;
        s.driver = (m, c) -> {
            int r = m.drive(Toy.loop(m, 1_000_000, 0));
            if (r >= Op.SIG) {
                m.abandon();
                return m.err;
            }
            return MachineErrors.E_NONE;
        };
        long t0 = s.steps();
        assertEquals(Outcome.DECLINE, s.check(f.cid("two"), Budget.strict(10_000, BIG)));
        assertEquals(Reason.BUDGET_STEPS, s.lastReason());
        assertEquals(10_000, s.steps() - t0, "steps() is the unit of Budget.maxSteps; it is monotone and not cleared by a reset");
        assertTrue(f.m.pristine());
        // a warm budget has no step limit
        assertEquals(Outcome.ACCEPT, s.check(f.cid("two"), Budget.warm()));
        assertTrue(s.steps() - t0 > 1_000_000);
        // bytes: the unit may add maxBytes to the footprint; seen at the next poll
        s.driver = (m, c) -> {
            for (int i = 0; i < 200_000; i++) {
                sort(m, i); // 36 bytes each
                if (m.tick(1) != 0) {
                    m.abandon();
                    return m.err;
                }
            }
            return MachineErrors.E_NONE;
        };
        assertEquals(Outcome.DECLINE, s.check(f.cid("two"), Budget.strict(Budget.UNLIMITED, 64 * 1024)));
        assertEquals(Reason.BUDGET_MEMORY, s.lastReason());
        assertEquals(MachineErrors.E_MEM, s.lastCode());
        assertTrue(f.m.pristine());
        assertTrue(s.steps() > 0);
        assertEquals(Outcome.ACCEPT, s.check(f.cid("two"), Budget.strict(Budget.UNLIMITED, 64L << 20)));
        f.close();
    }

    /**
     * ISA section 9 "limits produce DECLINE": an array that cannot double inside the Java heap is E_MEM,
     * not an OutOfMemoryError (which ends the whole run with status 3 under ExitOnOutOfMemoryError; seen
     * on Std, where one declaration made the record array double to 4 GiB in a 13 GiB heap).
     */
    @Test
    void aGrowthThatDoesNotFitTheJavaHeapDeclinesWithEMem() {
        Fixture f = toys();
        MachineSession s = f.session;
        int[] what = new int[1];
        s.driver = (m, c) -> {
            m.heapCheckMin = 0;
            m.heapReserve = Long.MAX_VALUE / 4; // nothing fits
            switch (what[0]) {
                case 0: // records (and their intern table)
                    for (int i = 0; i < 400_000; i++) {
                        if (sort(m, i) >= Op.SIG) {
                            return m.err;
                        }
                    }
                    return 0;
                case 1: // run words
                    for (int i = 0; i < 400_000; i++) {
                        if (TestTables.spineRun(m, i, i, i, i) >= Op.SIG) {
                            return m.err;
                        }
                    }
                    return 0;
                case 2: // the pair table: a void method refuses with Machine.HeapLimit
                    for (int i = 2; i < 100_000; i++) {
                        TestTables.pairAdd(m, i, i + 1, Layout.POS);
                    }
                    return 0;
                case 3: // working storage
                    return m.ensureEnv(1 << 20) ? 0 : m.fault(MachineErrors.E_MEM) == Op.FATAL ? m.err : 0;
                case 4:
                    return m.ensureScratch(1 << 20) ? 0 : m.fault(MachineErrors.E_MEM) == Op.FATAL ? m.err : 0;
                default: { // the worklist: a suspension that needs W to grow
                    int r = m.drive(Toy.sum(m, 200_000, 0));
                    return r >= Op.SIG ? m.err : 0;
                }
            }
        };
        for (what[0] = 0; what[0] <= 5; what[0]++) {
            f.m.heapCheckMin = 32L << 20;
            f.m.heapReserve = 256L << 20;
            assertEquals(Outcome.DECLINE, s.check(f.cid("two"), Budget.warm()), "case " + what[0]);
            assertEquals(Reason.BUDGET_MEMORY, s.lastReason(), "case " + what[0] + ": " + s.lastMessage());
            assertEquals(MachineErrors.E_MEM, s.lastCode());
            assertTrue(s.lastMessage().contains("the Java heap"), s.lastMessage());
            assertTrue(f.m.pristine() && f.m.idle(), "the session is reset and usable");
        }
        // with the normal reserve the same work fits
        s.driver = (m, c) -> {
            for (int i = 0; i < 400_000; i++) {
                if (sort(m, i) >= Op.SIG) {
                    return m.err;
                }
            }
            for (int i = 2; i < 100_000; i++) {
                TestTables.pairAdd(m, i, i + 1, Layout.POS);
            }
            int r = m.drive(Toy.sum(m, 200_000, 0));
            return r >= Op.SIG ? m.err : 0;
        };
        f.m.heapCheckMin = 32L << 20;
        f.m.heapReserve = 256L << 20;
        assertEquals(Outcome.ACCEPT, s.check(f.cid("two"), Budget.warm()), () -> s.lastMessage());
        assertTrue(f.m.heapAllows(1L << 20) && f.m.heapAllows(64L << 20));
        assertFalse(f.m.heapAllows(Runtime.getRuntime().maxMemory()), "more than the whole heap never fits");
        f.close();
    }

    @Test
    void aRuntimeExceptionDeclinesAndAnErrorIsNeverCaught() {
        Fixture f = toys();
        MachineSession s = f.session;
        s.driver = (m, c) -> {
            sort(m, 3);
            Scope.open(m, sort(m, 4));
            throw new ArithmeticException("a declared limit of rlvm-nat");
        };
        assertEquals(Outcome.DECLINE, s.check(f.cid("two"), Budget.warm()));
        assertEquals(Reason.INTERNAL_ERROR, s.lastReason());
        assertTrue(s.lastMessage().contains("a declared limit of rlvm-nat"), s.lastMessage());
        assertTrue(f.m.pristine() && f.m.idle(), "session reset");
        s.driver = (m, c) -> {
            throw new StackOverflowError("ISA 12 R11");
        };
        assertThrows(StackOverflowError.class, () -> s.check(f.cid("two"), Budget.warm()));
        // the session is still usable after the caller dealt with the Error (the driver discards it anyway)
        s.reset();
        s.driver = (m, c) -> MachineErrors.E_NONE;
        assertEquals(Outcome.ACCEPT, s.check(f.cid("two"), Budget.warm()));
    }

    @Test
    void theMachinesOwnSessionCapEndsAWarmSessionBeforeADeclaration() {
        Fixture f = Fixture.prelude();
        for (int i = 1; i <= 6; i++) {
            f.def("cap" + i, "Type", "Prop", Fixture.regular(1));
        }
        f.seal();
        java.util.Map<String, String> options = new java.util.HashMap<>();
        options.put("machine.switches", "warm");
        options.put("machine.session-kib", "1");
        MachineEngine e = (MachineEngine) new MachineProvider().factory(options).create(f.store, EngineConfig.defaults().withStats(true));
        assertEquals(1024, e.sessionCapBytes);
        assertEquals(1L << 20, new MachineEngine(f.store, EngineConfig.defaults()).sessionCapBytes, "the default: 1 MiB (NOTES-M6 F24)");
        MachineSession s = (MachineSession) e.newSession(f.ctx);
        int[] seen = new int[1];
        int[] make = {10};
        s.driver = (m, c) -> {
            seen[0] = m.vp - m.vbase;
            for (int i = 0; i < make[0]; i++) {
                sort(m, 1000 * c + i);
            }
            return MachineErrors.E_NONE;
        };
        // 10 records are 360 bytes: three declarations pass 1 KiB, and the fourth starts in an empty session
        assertEquals(Outcome.ACCEPT, s.check(f.cid("cap1"), Budget.warm()));
        assertEquals(Outcome.ACCEPT, s.check(f.cid("cap2"), Budget.warm()));
        assertEquals(10, seen[0], "below the cap the session survives");
        assertEquals(Outcome.ACCEPT, s.check(f.cid("cap3"), Budget.warm()));
        assertEquals(20, seen[0]);
        assertEquals(0, s.capResets);
        assertEquals(Outcome.ACCEPT, s.check(f.cid("cap4"), Budget.warm()));
        assertEquals(0, seen[0], "30 records = 1080 bytes > 1 KiB: the machine forgot everything first");
        assertEquals(1, s.capResets);
        // a declaration that alone exceeds the cap is checked all the same (the cap is tested between declarations)
        make[0] = 100;
        assertEquals(Outcome.ACCEPT, s.check(f.cid("cap5"), Budget.warm()));
        assertEquals(10, seen[0]);
        assertEquals(Outcome.ACCEPT, s.check(f.cid("cap6"), Budget.warm()));
        assertEquals(0, seen[0]);
        assertEquals(2, s.capResets);
        s.close();
        java.util.Map<String, Object> stats = new java.util.HashMap<>();
        e.dumpStats(new StatsSink() {
            @Override
            public void put(String key, long value) {
                stats.put(key, value);
            }

            @Override
            public void put(String key, double value) {
                stats.put(key, value);
            }

            @Override
            public void put(String key, String value) {
                stats.put(key, value);
            }

            @Override
            public void table(String key, String[] columns, long[][] rows, String[] rowLabels) {}
        });
        assertEquals(2L, stats.get("machine.capResets"));
        assertEquals(1024L, stats.get("machine.sessionCapBytes"));
        // no cap of its own: the session lives until the driver ends it
        options.put("machine.session-kib", "0");
        MachineEngine free = (MachineEngine) new MachineProvider().factory(options).create(f.store, EngineConfig.defaults());
        MachineSession t = (MachineSession) free.newSession(f.ctx);
        t.driver = s.driver;
        make[0] = 100;
        assertEquals(Outcome.ACCEPT, t.check(f.cid("cap1"), Budget.warm()));
        assertEquals(Outcome.ACCEPT, t.check(f.cid("cap2"), Budget.warm()));
        assertEquals(Outcome.ACCEPT, t.check(f.cid("cap3"), Budget.warm()));
        assertEquals(200, seen[0]);
        assertEquals(0, t.capResets);
        t.close();
        options.put("machine.session-kib", "-1");
        assertThrows(IllegalArgumentException.class, () -> new MachineProvider().factory(options));
    }

    /**
     * NOTES-M6 F26 (findings M6-F26, S-F4, S-F5; arena test bad/bugs/rec-of-subst-prop): a declaration that
     * enters eager reduction is not checked by the machine. Whatever the DECL driver answers, the unit is
     * DECLINED (E_LIMIT, UNSUPPORTED_INPUT), the session forgets everything, the driver's cold retry is
     * answered at once, and a judgment that ticks after the mode was entered is cut by a fatal fault.
     */
    @Test
    void aDeclarationThatEntersEagerReductionIsDeclined() {
        Fixture f = Fixture.prelude();
        for (int i = 1; i <= 4; i++) {
            f.def("er" + i, "Type", "Prop", Fixture.regular(1));
        }
        f.seal();
        int eager = f.cid("er2");
        // whatever the driver answers for a declaration that entered ER - accept, a kernel error, a limit, or a
        // judgment cut at the first tick after the mode was entered (-1) -, the unit is declined
        for (String switches : new String[] {"", "warm", "warm,gated,expect"}) {
            for (int answer : new int[] {MachineErrors.E_NONE, MachineErrors.E_APP_MISMATCH, MachineErrors.E_MEM, -1}) {
                String what = "[" + switches + "], driver answer " + (answer < 0 ? "a tick after enterER" : MachineErrors.name(answer));
                MachineSession s = (MachineSession) f.engine.newSession(f.ctx); // (a session remembers the unit it declined last)
                s.m.sw = Debug.parseSwitches(switches);
                int[] runs = new int[1];
                int[] seen = new int[1];
                boolean[] cut = new boolean[1];
                s.driver = (m, c) -> {
                    runs[0]++;
                    seen[0] = m.vp - m.vbase;
                    sort(m, 500 + c);
                    assertTrue(m.erDeclines, "the facade's rule is in force while the DECL driver runs");
                    if (c != eager) {
                        return MachineErrors.E_NONE;
                    }
                    int saved = m.ER;
                    m.enterER();
                    assertEquals(1, m.ER);
                    assertTrue(m.erSeen);
                    m.ER = saved;
                    if (answer >= 0) {
                        return answer;
                    }
                    // the TICK line of a judgment: the first tick after the mode was entered takes the slow path
                    if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
                        cut[0] = m.sig == Op.FATAL && m.err == MachineErrors.E_LIMIT;
                        return m.err;
                    }
                    throw new AssertionError("a judgment ticked under eager reduction and went on");
                };
                assertEquals(Outcome.ACCEPT, s.check(f.cid("er1"), Budget.warm()), what);
                long ticks = s.steps();
                assertEquals(Outcome.DECLINE, s.check(eager, Budget.warm()), what);
                assertEquals(Reason.UNSUPPORTED_INPUT, s.lastReason(), what);
                assertEquals(MachineErrors.E_LIMIT, s.lastCode(), what);
                assertTrue(s.lastMessage().contains("eager reduction"), s.lastMessage());
                assertEquals(2, runs[0], what);
                assertEquals(answer < 0, cut[0], what + ": the tick after enterER raises fault(E_LIMIT)");
                assertEquals(answer < 0 ? ticks + 1 : ticks, s.steps(), what + ": entering the mode moves the poll point, not the tick count");
                assertTrue(s.m.pristine(), what + ": nothing of that declaration is kept");
                assertFalse(s.m.erDeclines, "the rule is the facade's: a bare machine runs ER (tests of the judgments)");
                // the driver's cold retry of the same unit: declined at once, the DECL driver is not run again
                assertEquals(Outcome.DECLINE, s.check(eager, Budget.cold(BIG)), what);
                assertEquals(2, runs[0], what);
                assertEquals(Reason.UNSUPPORTED_INPUT, s.lastReason(), what);
                assertEquals(2, s.eagerDeclines, what);
                assertEquals(2, s.declined, what);
                // other declarations are not affected, and the next one starts in an empty session
                assertEquals(Outcome.ACCEPT, s.check(f.cid("er3"), Budget.warm()), what);
                assertEquals(0, seen[0], what);
                assertFalse(s.m.erSeen, what);
                assertEquals(Outcome.ACCEPT, s.check(f.cid("er4"), Budget.warm()), what);
                assertEquals(switches.isEmpty() ? 0 : 1, seen[0], what);
                s.close();
            }
        }
        // a bare machine (no facade): the mode is entered and nothing is cut
        Machine bare = new Machine(Machine.Sizes.tiny());
        bare.beginDecl(1, 0, 1 << 20, Long.MAX_VALUE);
        long fuel = bare.fuel;
        bare.enterER();
        assertTrue(bare.erSeen && bare.ER == 1);
        assertEquals(fuel, bare.fuel);
        assertEquals(0, bare.poll());
        bare.ER = 0;
        bare.beginDecl(2, 1, 1 << 20, Long.MAX_VALUE);
        assertFalse(bare.erSeen, "a property of ONE declaration");
    }

    /** A package's session state that implements {@code Machine.Counters} is summed into the statistics as {@code machine.<name>}. */
    @Test
    void packageCountersAreSummedIntoTheStatistics() {
        Fixture f = Fixture.prelude0();
        MachineEngine e = new MachineEngine(f.store, EngineConfig.defaults().withStats(true));
        Machine.Counters one = new Machine.Counters() {
            @Override
            public String[] counterNames() {
                return new String[] {"match.tried", "match.hit"};
            }

            @Override
            public long[] counterValues() {
                return new long[] {5, 2};
            }
        };
        e.packageCounters(one);
        e.packageCounters(one);
        e.packageCounters(new Object()); // a state without counters
        java.util.Map<String, Object> stats = new java.util.HashMap<>();
        e.dumpStats(new StatsSink() {
            @Override
            public void put(String key, long value) {
                stats.put(key, value);
            }

            @Override
            public void put(String key, double value) {
                stats.put(key, value);
            }

            @Override
            public void put(String key, String value) {
                stats.put(key, value);
            }

            @Override
            public void table(String key, String[] columns, long[][] rows, String[] rowLabels) {}
        });
        assertEquals(10L, stats.get("machine.match.tried"));
        assertEquals(4L, stats.get("machine.match.hit"));
        assertEquals(0L, stats.get("machine.eagerDeclines"));
    }

    /**
     * [eager] The probes of the plain phase run under a budget ({@code -X machine.eager-probe},
     * {@code Machine.erProbeBudget}; finding INT7-F1 of 2026-11-25: on con-leche's {@code ConLeche.lpOfData_pack} a
     * fold probe - an evaluation that the official plain procedure never starts - ran for 28.6 M ticks into the hard
     * limit of a session). A probe is a segment under that budget; cut at it ({@code Machine.erProbeCut}), the plain
     * phase goes on, and the unit is DECLINED when it reaches an {@code eagerReduce _ _} argument: the reference
     * engine decides it. The driver's cold retry gets the same answer at once. A unit that never enters eager
     * reduction is not affected by a cut.
     */
    @Test
    void aProbeThatIsCutDeclinesTheUnitIfItEntersEagerReduction() {
        Fixture f = Fixture.prelude();
        for (int i = 1; i <= 3; i++) {
            f.def("pb" + i, "Type", "Prop", Fixture.regular(1));
        }
        f.seal();
        java.util.Map<String, String> options = new java.util.HashMap<>();
        options.put("machine.switches", "warm,eager");
        options.put("machine.eager-probe", "5000");
        MachineEngine e = (MachineEngine) new MachineProvider().factory(options).create(f.store, EngineConfig.defaults());
        assertEquals(5000, e.eagerProbe);
        MachineSession s = (MachineSession) e.newSession(f.ctx);
        assertEquals(5000, s.m.erProbeBudget);
        int eager = f.cid("pb2");
        int plain = f.cid("pb3");
        int[] runs = new int[1];
        long[] ticked = new long[1];
        s.driver = (m, c) -> {
            runs[0]++;
            if (c != eager && c != plain) {
                return MachineErrors.E_NONE;
            }
            // a probe of the plain phase whose evaluation never ends: a segment under the probe budget (Frames, H11)
            int ssp = m.ssp;
            int xp = m.xp;
            int ep = m.ep;
            int rp = m.rp;
            int er = m.ER;
            long budget = m.erProbeBudget;
            long saved = m.tryEnter(budget);
            int r;
            for (;;) {
                ticked[0]++;
                if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
                    r = m.sig;
                    break;
                }
            }
            r = Frames.k_try(m, saved, budget, ssp, xp, ep, rp, er, r);
            assertEquals(Layout.U, r, "the probe was cut at its budget, the declaration goes on");
            assertEquals(0, m.erProbeCut(), "not in eager reduction yet: the plain procedure goes on");
            assertTrue(m.erCut && m.erSens);
            if (c == plain) {
                return MachineErrors.E_NONE; // a declaration without an eagerReduce argument: the cut does not matter
            }
            int sgn = m.erSite(0, 0); // the first `eagerReduce _ _` argument
            assertEquals(Op.FATAL, sgn);
            return m.err;
        };
        assertEquals(Outcome.ACCEPT, s.check(f.cid("pb1"), Budget.warm()));
        assertEquals(Outcome.DECLINE, s.check(eager, Budget.warm()));
        assertEquals(5001, ticked[0], "cut at the budget of the probe, not at a bound of the unit");
        assertEquals(MachineErrors.E_LIMIT, s.lastCode());
        assertTrue(s.lastMessage().contains("-X machine.eager-probe"), s.lastMessage());
        assertEquals(1, s.eagerWhy[Machine.ER_WHY_PROBE]);
        assertEquals(1, s.m.erProbeCuts);
        // the driver's cold retry: the same answer at once
        runs[0] = 0;
        assertEquals(Outcome.DECLINE, s.check(eager, Budget.cold(BIG)));
        assertEquals(0, runs[0]);
        // a unit that never enters eager reduction is decided although its probe was cut
        ticked[0] = 0;
        assertEquals(Outcome.ACCEPT, s.check(plain, Budget.warm()));
        assertEquals(5001, ticked[0]);
        assertEquals(1, s.eagerWhy[Machine.ER_WHY_PROBE]);
        // the default: above every probe of the eight corpora that completes (the largest: 2,752,617 ticks, one unit of
        // Mathlib; statistic machine.eager.probeMaxTicks), below the probe that never does (28.6 M ticks and E_MEM)
        assertTrue(MachineEngine.DEFAULT_EAGER_PROBE >= 1L << 22 && MachineEngine.DEFAULT_EAGER_PROBE <= 1L << 24,
                "the budget of a probe: " + MachineEngine.DEFAULT_EAGER_PROBE);
        // no budget: 0
        options.put("machine.eager-probe", "0");
        assertEquals(0, ((MachineEngine) new MachineProvider().factory(options).create(f.store, EngineConfig.defaults())).eagerProbe);
    }

    /**
     * Switch {@code congruence}: the cap on the ticks its segments use in one declaration
     * ({@code -X machine.cong-spend}, {@code Machine.congSpendMax}, {@code Conv.Ext.spend}; INT7, 2026-11-25: a
     * same-head probe or a closure congruence that answers T can cost more than the unfolding it replaces - P9
     * section 4.7, con-leche). {@code Frames.k_try} reports what a segment used; at the cap {@code Conv.congruence}
     * starts no further segment; the next declaration starts at zero.
     */
    @Test
    void theCongruenceSegmentsOfADeclarationHaveATickCap() {
        Fixture f = Fixture.prelude0();
        java.util.Map<String, String> options = new java.util.HashMap<>();
        options.put("machine.switches", "congruence");
        options.put("machine.cong-spend", "100");
        MachineEngine e = (MachineEngine) new MachineProvider().factory(options).create(f.store, EngineConfig.defaults());
        assertEquals(100, e.congSpend);
        assertEquals(Op.SW_CONGRUENCE | Op.SW_PROBES, e.switches);
        MachineSession s = (MachineSession) e.newSession(f.ctx);
        Machine m = s.m;
        assertEquals(100, m.congSpendMax);
        m.beginDecl(1, 0, 1_000_000, BIG);
        m.x4.beginDecl(m);
        assertTrue(Conv.congruence(m), "below the cap: segments are started");
        // a segment that completes after 7 ticks, and one that is cut at its budget of 50
        int ssp = m.ssp;
        int xp = m.xp;
        int ep = m.ep;
        int rp = m.rp;
        int er = m.ER;
        long saved = m.tryEnter(50);
        m.fuel -= 7;
        assertEquals(Layout.T, Frames.k_try(m, saved, 50, ssp, xp, ep, rp, er, Layout.T));
        assertEquals(7, m.tryUsed);
        m.x4.spend += m.tryUsed;
        assertTrue(Conv.congruence(m));
        saved = m.tryEnter(50);
        m.fuel = -1;
        m.err = MachineErrors.E_STEPS;
        assertEquals(Layout.U, Frames.k_try(m, saved, 50, ssp, xp, ep, rp, er, Op.ABORT));
        assertEquals(50, m.tryUsed, "a segment that is cut used its whole budget");
        m.x4.spend += m.tryUsed;
        assertTrue(Conv.congruence(m), "57 of 100 ticks");
        m.x4.spend += 43;
        assertFalse(Conv.congruence(m), "at the cap: no further congruence segment in this declaration");
        m.x4.beginDecl(m);
        assertTrue(Conv.congruence(m), "the next declaration starts at zero");
        // no cap: 0; the default is a cap
        m.x4.spend = Long.MAX_VALUE >> 1;
        m.congSpendMax = 0;
        assertTrue(Conv.congruence(m));
        assertTrue(MachineEngine.DEFAULT_CONG_SPEND >= 1L << 16 && MachineEngine.DEFAULT_CONG_SPEND <= 1L << 22, "the default cap");
        m.abandon();
    }

    /**
     * The machine's own tick bound per unit ({@code -X machine.unit-ticks}; NOTES-M6 F31): a warm unit has no
     * step limit of the driver's, so a declaration that never ends (the official checker does not terminate on
     * either/corner-cases/eta-struct-major-no-whnf) is declined at the bound (BUDGET_STEPS; the reference engine
     * decides it), and the driver's cold retry is answered at once. A smaller limit of the driver's still rules.
     */
    @Test
    void theMachinesOwnTickBoundDeclinesAUnitThatNeverEnds() {
        Fixture f = Fixture.prelude();
        for (int i = 1; i <= 3; i++) {
            f.def("tb" + i, "Type", "Prop", Fixture.regular(1));
        }
        f.seal();
        java.util.Map<String, String> options = new java.util.HashMap<>();
        options.put("machine.switches", "warm");
        options.put("machine.unit-ticks", "10000");
        MachineEngine e = (MachineEngine) new MachineProvider().factory(options).create(f.store, EngineConfig.defaults());
        assertEquals(10000, e.unitTicks);
        MachineSession s = (MachineSession) e.newSession(f.ctx);
        int forever = f.cid("tb2");
        int[] runs = new int[1];
        long[] fuelAtStart = new long[1];
        long[] ticked = new long[1];
        s.driver = (m, c) -> {
            runs[0]++;
            fuelAtStart[0] = m.fuel;
            if (c != forever) {
                return MachineErrors.E_NONE;
            }
            for (;;) { // a judgment that never ends: only the TICK line stops it
                ticked[0]++;
                if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
                    return m.err;
                }
            }
        };
        assertEquals(Outcome.ACCEPT, s.check(f.cid("tb1"), Budget.warm()));
        assertEquals(10000, fuelAtStart[0], "no limit of the driver's: the machine's own bound");
        assertEquals(Outcome.DECLINE, s.check(forever, Budget.warm()));
        assertEquals(Reason.BUDGET_STEPS, s.lastReason());
        assertEquals(MachineErrors.E_STEPS, s.lastCode());
        assertTrue(s.lastMessage().contains("the machine's own bound of 10000 ticks"), s.lastMessage());
        assertEquals(10001, ticked[0], "cut at the bound");
        assertEquals(1, s.unitBoundDeclines);
        assertTrue(s.m.pristine());
        // the driver's cold retry: the same answer at once
        runs[0] = 0;
        assertEquals(Outcome.DECLINE, s.check(forever, Budget.cold(BIG)));
        assertEquals(0, runs[0]);
        assertEquals(Reason.BUDGET_STEPS, s.lastReason());
        assertEquals(2, s.unitBoundDeclines);
        // other units go on
        assertEquals(Outcome.ACCEPT, s.check(f.cid("tb3"), Budget.warm()));
        // a smaller limit of the driver's rules, and is not the machine's bound
        MachineSession t = (MachineSession) e.newSession(f.ctx);
        t.driver = s.driver;
        ticked[0] = 0;
        assertEquals(Outcome.DECLINE, t.check(forever, Budget.strict(500, BIG)));
        assertEquals(500, fuelAtStart[0]);
        assertEquals(501, ticked[0]);
        assertEquals(Reason.BUDGET_STEPS, t.lastReason());
        assertEquals(0, t.unitBoundDeclines);
        assertFalse(t.lastMessage().contains("own bound"), t.lastMessage());
        runs[0] = 0;
        assertEquals(Outcome.DECLINE, t.check(forever, Budget.strict(500, BIG)));
        assertEquals(1, runs[0], "the driver's budget may differ next time: the unit is run again");
        // no bound of its own
        options.put("machine.unit-ticks", "0");
        MachineEngine free = (MachineEngine) new MachineProvider().factory(options).create(f.store, EngineConfig.defaults());
        MachineSession u = (MachineSession) free.newSession(f.ctx);
        u.driver = s.driver;
        assertEquals(Outcome.ACCEPT, u.check(f.cid("tb1"), Budget.warm()));
        assertEquals(Op.FUEL_UNLIMITED, fuelAtStart[0]);
        assertTrue(MachineEngine.DEFAULT_UNIT_TICKS >= 1L << 26, "far above the largest unit of the corpora (mathlib: 5.4 M ticks)");
        s.close();
        t.close();
        u.close();
    }

    @Test
    void theDeclineLogHasOneLinePerOutcomeOtherThanAccept() throws java.io.IOException {
        Fixture f = Fixture.prelude();
        for (int i = 1; i <= 4; i++) {
            f.def("d" + i, "Type", "Prop", Fixture.regular(1));
        }
        f.seal();
        java.nio.file.Path log = java.nio.file.Files.createTempFile("m6-declines", ".tsv");
        try {
            java.util.Map<String, String> options = new java.util.HashMap<>();
            options.put("machine.switches", "warm");
            options.put("machine.declines", log.toString());
            MachineEngine e = (MachineEngine) new MachineProvider().factory(options).create(f.store, EngineConfig.defaults());
            MachineSession s = (MachineSession) e.newSession(f.ctx);
            int[] code = new int[1];
            s.driver = (m, c) -> {
                if (code[0] == MachineErrors.E_MEM) {
                    m.limitDetail = "a limit\twith a tab\nand a line break";
                }
                return code[0];
            };
            assertEquals(Outcome.ACCEPT, s.check(f.cid("d1"), Budget.warm()));
            assertEquals(Outcome.ACCEPT, s.check(f.cid("d2"), Budget.warm()));
            code[0] = MachineErrors.E_MEM;
            assertEquals(Outcome.DECLINE, s.check(f.cid("d3"), Budget.warm()));
            assertEquals(Outcome.DECLINE, s.check(f.cid("d3"), Budget.warm()));
            code[0] = MachineErrors.E_APP_MISMATCH;
            assertEquals(Outcome.REJECT, s.check(f.cid("d4"), Budget.warm()));
            s.close();
            e.close();
            java.util.List<String> lines = java.nio.file.Files.readAllLines(log);
            assertEquals(3, lines.size(), () -> String.valueOf(lines));
            String[] a = lines.get(0).split("\t");
            assertEquals(7, a.length, () -> lines.get(0));
            assertEquals("DECLINE", a[0]);
            assertEquals("E_MEM", a[1]);
            assertEquals(String.valueOf(f.store.decls().unit(f.cid("d3"))), a[2]);
            assertEquals(String.valueOf(f.store.decls().rank(f.cid("d3"))), a[3]);
            assertEquals("2", a[4], "two units had been accepted in the session: a warm decline");
            assertEquals("d3", a[5]);
            assertTrue(a[6].contains("BUDGET_MEMORY") && a[6].contains("a limit with a tab and a line break"), () -> a[6]);
            assertEquals("0", lines.get(1).split("\t")[4], "the second attempt was cold");
            assertTrue(lines.get(2).startsWith("REJECT\tE_APP_MISMATCH\t"), () -> lines.get(2));
            // a log that cannot be written is a lost diagnostic, never an outcome
            options.put("machine.declines", log.resolve("no/such/directory").toString());
            MachineEngine broken = (MachineEngine) new MachineProvider().factory(options).create(f.store, EngineConfig.defaults());
            MachineSession t = (MachineSession) broken.newSession(f.ctx);
            t.driver = (m, c) -> MachineErrors.E_STEPS;
            java.io.PrintStream err = System.err;
            System.setErr(new java.io.PrintStream(new java.io.ByteArrayOutputStream()));
            try {
                assertEquals(Outcome.DECLINE, t.check(f.cid("d1"), Budget.warm()));
                assertEquals(Outcome.DECLINE, t.check(f.cid("d2"), Budget.warm()));
            } finally {
                System.setErr(err);
            }
            t.close();
            broken.close();
        } finally {
            java.nio.file.Files.deleteIfExists(log);
        }
    }

    @Test
    void switchListsAreReadFromLeftToRight() {
        assertEquals(0, Debug.parseSwitches("none"));
        assertEquals(Op.SW_DEFAULT, Debug.parseSwitches("default"));
        assertEquals(Op.SW_DEFAULT | Op.SW_PROBES, Debug.parseSwitches("default,probes"));
        assertEquals(Op.SW_WARM, Debug.parseSwitches("warm,gated,-gated"));
        assertEquals(Op.SW_EXPECT, Debug.parseSwitches("warm,none,+expect"));
        assertEquals(Op.SW_ALL & ~Op.SW_WARM, Debug.parseSwitches("all,-warm"));
        assertEquals(0, Debug.parseSwitches("all") & (Op.SW_K2 | Op.SW_T2 | Op.SW_EXPERIMENTS | Op.SW_BODIES | Op.SW_DIRECT_RULES | Op.SW_SAME
                | Op.SW_MATCH | Op.SW_JOINT | Op.SW_LAZY_CASES), "all = the staging switches of ISA section 11 but k2: no experiment bit, old or new");
        assertEquals(Op.SW_CODE | Op.SW_CASETREES | Op.SW_T2, Debug.parseSwitches("code,casetrees,t2"));
        assertEquals(Op.SW_LAZY_EVAL | Op.SW_LEVELS_COMPLETE, Debug.parseSwitches("lazyeval,levels-complete"));
        assertEquals("lazyeval,levels-complete", Debug.switchNames(Op.SW_LAZY_EVAL | Op.SW_LEVELS_COMPLETE));
        assertEquals(0, Debug.parseSwitches("all") & (Op.SW_LAZY_EVAL | Op.SW_LEVELS_COMPLETE));
        assertEquals(Op.SW_M1A | Op.SW_M7B, Debug.parseSwitches("m1a,m7b"));
        assertEquals("m1a,m7b", Debug.switchNames(Op.SW_M1A | Op.SW_M7B));
        // experiments with a name of their own: the same bit, read by both names, printed by the new one
        assertEquals(Op.SW_M5A | Op.SW_M5B | Op.SW_M4A, Debug.parseSwitches("match,joint,same"));
        assertEquals(Op.SW_MATCH | Op.SW_JOINT | Op.SW_SAME, Debug.parseSwitches("m5a,m5b,m4a"));
        assertEquals("same,match,joint", Debug.switchNames(Op.SW_MATCH | Op.SW_JOINT | Op.SW_SAME));
        assertEquals(Op.SW_WARM, Debug.parseSwitches("warm,match,-match"));
        assertEquals(Op.SW_M2A | Op.SW_M3A | Op.SW_M7A, Debug.parseSwitches("bodies,directrules,lazycases"));
        assertEquals(Op.SW_BODIES | Op.SW_DIRECT_RULES | Op.SW_LAZY_CASES, Debug.parseSwitches("m2a,m3a,m7a"));
        assertEquals("bodies,directrules,lazycases", Debug.switchNames(Op.SW_BODIES | Op.SW_DIRECT_RULES | Op.SW_LAZY_CASES));
        assertEquals(Op.SW_M2A | Op.SW_M2B | Op.SW_M3A | Op.SW_M3B | Op.SW_M4A | Op.SW_M4B | Op.SW_M5A | Op.SW_M5B,
                Debug.parseSwitches("m2a,m2b,m3a,m3b,m4a,m4b,m5a,m5b"));
        assertEquals(6, Integer.bitCount(Op.SW_EXPERIMENTS), "six experiment bits are named switches of the default set now");
        assertEquals(0, Op.SW_EXPERIMENTS & Op.SW_ALL);
        assertEquals(0, Op.SW_DEFAULT & (Op.SW_EXPERIMENTS | Op.SW_K2), "no experiment is a default");
        assertEquals(Debug.parseSwitches("warm,gated,expect,bodies,pchk,match,joint,code,casetrees,lazycases,directrules,lazyeval,same"),
                Op.SW_WAVE6, "the default set of the wave-6 integration (SWITCHES.md)");
        assertEquals(Debug.parseSwitches("warm,gated,expect,bodies,pchk,match,joint,code,casetrees,lazycases,directrules,lazyeval,same,eager,skipargs,congruence"),
                Op.SW_DEFAULT, "the default set of 2026-11-25 (SWITCHES.md): wave 6 + eager, skipargs, congruence");
        assertEquals(Op.SW_DEFAULT, Op.implied(Op.SW_DEFAULT), "a shortcut in the default set has the switch it needs");
        assertEquals(0, Op.SW_DEFAULT & (Op.SW_TELE | Op.SW_T2 | Op.SW_M2B | Op.SW_LEVELS_COMPLETE | Op.SW_INERT_LOOP | Op.SW_CONV_EXPECT),
                "not in the default set (SWITCHES.md says why)");
        assertEquals("none", System.getProperty("rlvm.machine.switches", "none"), "the module's tests run v0 unless they say otherwise (pom.xml)");
        assertThrows(IllegalArgumentException.class, () -> Debug.parseSwitches("warm,-fast"));
        // without the setting an engine runs the default set; a strict engine runs none
        String old = System.getProperty("rlvm.machine.switches");
        System.clearProperty("rlvm.machine.switches");
        try {
            Fixture f = Fixture.prelude0();
            assertEquals(Op.SW_DEFAULT, new MachineEngine(f.store, EngineConfig.defaults()).switches);
            assertEquals(0, new MachineEngine(f.store, EngineConfig.defaults().withStrict(true)).switches);
            java.util.Map<String, String> options = new java.util.HashMap<>();
            assertEquals(Op.SW_DEFAULT, ((MachineEngine) new MachineProvider().factory(options).create(f.store, EngineConfig.defaults())).switches);
            options.put("machine.switches", "none");
            assertEquals(0, ((MachineEngine) new MachineProvider().factory(options).create(f.store, EngineConfig.defaults())).switches);
            // the launcher's -X levels=... reaches the machine too, and wins over machine.switches
            assertTrue(new MachineProvider().optionKeys().contains("levels"));
            options.put("levels", "complete");
            assertEquals(Op.SW_LEVELS_COMPLETE, ((MachineEngine) new MachineProvider().factory(options).create(f.store, EngineConfig.defaults())).switches);
            assertEquals(0, ((MachineEngine) new MachineProvider().factory(options).create(f.store, EngineConfig.defaults().withStrict(true))).switches,
                    "a strict engine has no switch: official levels");
            options.put("machine.switches", "warm,levels-complete");
            options.put("levels", "official");
            assertEquals(Op.SW_WARM, ((MachineEngine) new MachineProvider().factory(options).create(f.store, EngineConfig.defaults())).switches);
            options.put("levels", "both");
            assertThrows(IllegalArgumentException.class, () -> new MachineProvider().factory(options));
            options.remove("levels");
        } finally {
            if (old != null) {
                System.setProperty("rlvm.machine.switches", old);
            }
        }
    }

    @Test
    void warmSessionsSurviveAnAcceptAndMoveForwardOnly() {
        Fixture f = Fixture.prelude();
        f.def("w1", "u", "(sort (succ u))", "(sort u)", Fixture.regular(1));
        f.def("w2", "u v", "(sort (max (succ u) (succ v)))", "(-> (sort u) (sort v))", Fixture.regular(1));
        f.def("w3", "v", "(sort (succ v))", "(sort v)", Fixture.regular(1));
        f.def("w4", "Type", "Prop", Fixture.regular(1));
        f.def("w5", "Type", "Prop", Fixture.regular(1));
        f.seal();
        f.switches("warm");
        MachineSession s = f.session;
        int[] seen = new int[2];
        int[] code = new int[1];
        s.driver = (m, c) -> {
            seen[0] = m.vp - m.vbase;
            seen[1] = m.gen;
            sort(m, 100 + c);
            m.gen++; // what Decl.regime does when a declaration's parameters do not contain the previous ones
            return code[0];
        };
        s.reset();
        assertEquals(Outcome.ACCEPT, s.check(f.cid("w1"), Budget.warm()));
        assertEquals(0, seen[0]);
        assertEquals(1, seen[1], "a session starts at generation 1");
        assertEquals(Outcome.ACCEPT, s.check(f.cid("w2"), Budget.warm()));
        assertEquals(1, seen[0], "the session survived the ACCEPT");
        assertEquals(2, seen[1], "and so did its check generation (ISA 5.3; advanced by the DECL driver only)");
        assertEquals(Outcome.ACCEPT, s.check(f.cid("w3"), Budget.warm()));
        assertEquals(2, seen[0]);
        assertEquals(Outcome.ACCEPT, s.check(f.cid("w4"), Budget.warm()));
        assertEquals(Outcome.ACCEPT, s.check(f.cid("w5"), Budget.warm()));
        assertEquals(4, seen[0]);
        assertEquals(5, seen[1]);
        long warm = s.footprintBytes();
        assertEquals(f.ctx.footprintBytes() + 5 * (Layout.REC_BYTES + 4), warm);
        // a declaration that is not later in file order ends the session (I-SESSION)
        assertEquals(Outcome.ACCEPT, s.check(f.cid("w2"), Budget.warm()));
        assertEquals(0, seen[0]);
        assertEquals(1, seen[1]);
        // the same declaration again: not later either
        assertEquals(Outcome.ACCEPT, s.check(f.cid("w2"), Budget.warm()));
        assertEquals(0, seen[0]);
        // any outcome other than ACCEPT ends it too
        code[0] = MachineErrors.E_APP_MISMATCH;
        assertEquals(Outcome.REJECT, s.check(f.cid("w3"), Budget.warm()));
        assertEquals(1, seen[0]);
        assertTrue(f.m.pristine());
        code[0] = MachineErrors.E_NONE;
        assertEquals(Outcome.ACCEPT, s.check(f.cid("w4"), Budget.warm()));
        assertEquals(0, seen[0]);
        assertEquals(1, seen[1], "a new session starts at generation 1");
        // the driver's reset (footprint cap) ends it
        s.reset();
        assertEquals(Outcome.ACCEPT, s.check(f.cid("w5"), Budget.warm()));
        assertEquals(0, seen[0]);
        f.close();
    }

    @Test
    void dumpStatsReportsOutcomesCallsHitRatesAndTheFirstFailures() {
        Fixture f = toys();
        MachineEngine engine = new MachineEngine(f.store, EngineConfig.defaults().withStats(true));
        MachineSession s = (MachineSession) engine.newSession(f.ctx);
        s.m.ext = Toy.RESUMER;
        int[] code = new int[1];
        s.driver = (m, c) -> {
            int r = m.drive(Toy.fib(m, 20, 0));
            if (r >= Op.SIG) {
                m.abandon();
                return m.err;
            }
            m.probe(Op.B1, Layout.T);
            m.probe(Op.B1, Layout.U);
            return code[0] == 0 ? 0 : m.fail(code[0], r, sort(m, 9)) == Op.ABORT ? m.err : m.err;
        };
        assertEquals(Outcome.ACCEPT, s.check(f.cid("two"), Budget.warm()));
        code[0] = MachineErrors.E_TYPE_MISMATCH;
        assertEquals(Outcome.REJECT, s.check(f.cid("id"), Budget.warm()));
        assertTrue(s.lastMessage().contains("=?="), "the two values of a mismatch are rendered: " + s.lastMessage());
        assertTrue(s.lastMessage().contains("(sort "), s.lastMessage());
        code[0] = MachineErrors.E_HOLE;
        assertEquals(Outcome.DECLINE, s.check(f.cid("two"), Budget.warm()));
        java.util.Map<String, Object> out = new java.util.TreeMap<>();
        StatsSink sink = new StatsSink() {
            @Override
            public void put(String key, long value) {
                out.put(key, value);
            }

            @Override
            public void put(String key, double value) {
                out.put(key, value);
            }

            @Override
            public void put(String key, String value) {
                out.put(key, value);
            }

            @Override
            public void table(String key, String[] columns, long[][] rows, String[] rowLabels) {
                out.put(key, rows);
            }
        };
        engine.dumpStats(sink);
        assertTrue(out.isEmpty() || (Long) out.get("machine.sessions") == 0, "a session accounts for itself when it is closed");
        s.close();
        s.close();
        engine.dumpStats(sink);
        assertEquals(1L, out.get("machine.sessions"));
        assertEquals(3L, out.get("machine.units"));
        assertEquals(1L, out.get("machine.accepted"));
        assertEquals(1L, out.get("machine.rejected"));
        assertEquals(1L, out.get("machine.declined"));
        assertEquals(1L, out.get("machine.rejectedBy.E_TYPE_MISMATCH"));
        assertEquals(1L, out.get("machine.declinedBy.E_HOLE"));
        assertEquals(3 * 19L, out.get("machine.calls.EXT2"), "FIB(20) does work 19 times, in each of the three sessions");
        assertEquals(3 * 17L, out.get("machine.hits.EXT2"));
        assertEquals(17.0 / 36, (Double) out.get("machine.hitRate.EXT2"), 1e-9);
        assertEquals(3L, out.get("machine.probes.B1.T"));
        assertEquals(3L, out.get("machine.probes.B1.U"));
        assertEquals(0L, out.get("machine.probes.B1.F"));
        assertTrue((Long) out.get("machine.records") >= 3 * 19, "records made, summed over the sessions");
        assertTrue((Long) out.get("machine.resets") >= 3);
        assertTrue((Long) out.get("machine.bytesAtReset") > 0);
        assertTrue(((String) out.get("machine.firstFailures.0")).contains("'id'"), out.toString());
        assertTrue(((String) out.get("machine.firstFailures.1")).contains("E_HOLE"), out.toString());
        assertEquals("", out.get("machine.switches"));
        assertTrue(out.containsKey("machine.ticks") && out.containsKey("machine.framesWritten") && out.containsKey("machine.factsDerived"));
    }
}
