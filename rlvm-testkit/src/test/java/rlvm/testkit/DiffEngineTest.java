package rlvm.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rlvm.api.Budget;
import rlvm.api.Engine;
import rlvm.api.EngineConfig;
import rlvm.api.EngineFactory;
import rlvm.api.KernelOps;
import rlvm.api.Outcome;
import rlvm.api.Reason;
import rlvm.api.RunSignals;
import rlvm.api.Session;
import rlvm.api.StatsSink;
import rlvm.api.WorkerContext;
import rlvm.store.Store;

class DiffEngineTest {

    private static final int A = Outcome.ACCEPT;
    private static final int R = Outcome.REJECT;
    private static final int D = Outcome.DECLINE;

    /** What a fake engine answers for a constant, given how many checks its session made since the last reset. */
    interface Script {
        int outcome(int cid, long checksSinceReset);
    }

    /** A scripted engine that records everything the differential engine does with it. */
    static final class FakeEngine implements Engine {
        final String name;
        final Script script;
        final List<FakeSession> sessions = new ArrayList<>();
        final List<String> log = new ArrayList<>();
        boolean closed;
        int environmentAdvanced = -1;
        Store store;
        EngineConfig config;

        FakeEngine(String name, Script script) {
            this.name = name;
            this.script = script;
        }

        EngineFactory factory() {
            return (s, c) -> {
                store = s;
                config = c;
                return this;
            };
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Session newSession(WorkerContext ctx) {
            FakeSession s = new FakeSession(this, ctx);
            sessions.add(s);
            return s;
        }

        @Override
        public void environmentAdvanced(int units) {
            environmentAdvanced = units;
        }

        @Override
        public void dumpStats(StatsSink sink) {
            sink.put("checks", (long) log.size());
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    static final class FakeSession implements Session {
        final FakeEngine engine;
        final WorkerContext ctx;
        long sinceReset;
        long steps;
        int resets;
        boolean closed;
        Reason reason;
        Budget lastBudget;

        FakeSession(FakeEngine engine, WorkerContext ctx) {
            this.engine = engine;
            this.ctx = ctx;
        }

        @Override
        public int check(int cid, Budget budget) {
            sinceReset++;
            steps += 10;
            lastBudget = budget;
            int o = engine.script.outcome(cid, sinceReset);
            engine.log.add(cid + ":" + Outcome.name(o));
            if (o == 99) {
                throw new IllegalStateException("boom on " + cid);
            }
            reason = o == R ? Reason.DECL_TYPE_MISMATCH : o == D ? Reason.BUDGET_STEPS : null;
            return o;
        }

        @Override
        public Reason lastReason() {
            return reason;
        }

        @Override
        public String lastMessage() {
            return engine.name + " says " + reason;
        }

        @Override
        public KernelOps ops() {
            return null;
        }

        @Override
        public void reset() {
            resets++;
            sinceReset = 0;
        }

        @Override
        public long footprintBytes() {
            return 1000;
        }

        @Override
        public long steps() {
            return steps;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    static final class MapSink implements StatsSink {
        final Map<String, Object> values = new HashMap<>();

        @Override
        public void put(String key, long value) {
            values.put(key, value);
        }

        @Override
        public void put(String key, double value) {
            values.put(key, value);
        }

        @Override
        public void put(String key, String value) {
            values.put(key, value);
        }

        @Override
        public void table(String key, String[] columns, long[][] rows, String[] rowLabels) {
            values.put(key, rows);
        }
    }

    private static WorkerContext context(int workerId) {
        return new WorkerContext(workerId, null, null, null, null, null, null, null, RunSignals.NONE);
    }

    private static final EngineConfig CONFIG = new EngineConfig(EngineConfig.KernelProfile.LEAN_4_36, false, false, false, false, 1);

    private static Script fixed(int outcome) {
        return (cid, n) -> outcome;
    }

    private static DiffEngine engine(FakeEngine underTest, FakeEngine oracle, DiffEngine.Options options) {
        return new DiffEngine(null, CONFIG, underTest.factory(), oracle.factory(), options);
    }

    // ---- the outcome table ------------------------------------------------------------------------------------

    /** Every pair of outcomes under both policies: combined outcome, reason, and what is counted. */
    @Test
    void outcomeTable() {
        int[] outcomes = {A, R, D};
        for (DiffEngine.Policy policy : DiffEngine.Policy.values()) {
            for (int x : outcomes) {
                for (int y : outcomes) {
                    FakeEngine ut = new FakeEngine("machine", fixed(x));
                    FakeEngine or = new FakeEngine("reference", fixed(y));
                    DiffEngine.Options opt = new DiffEngine.Options();
                    opt.policy = policy;
                    DiffEngine diff = engine(ut, or, opt);
                    Session s = diff.newSession(context(3));
                    int got = s.check(17, Budget.warm());
                    String what = policy + " " + Outcome.name(x) + "/" + Outcome.name(y);

                    boolean disagree = (x == A && y == R) || (x == R && y == A);
                    int expected;
                    if (x == y) {
                        expected = x;
                    } else if (disagree) {
                        expected = policy == DiffEngine.Policy.DECLINE ? D : y;
                    } else {
                        expected = x == D ? y : x; // one side declined: the other side's outcome
                    }
                    assertEquals(expected, got, what);
                    assertEquals(disagree ? 1 : 0, diff.disagreementCount(), what);
                    assertEquals(disagree ? 1 : 0, diff.disagreements().size(), what);
                    assertEquals(x == A && y == R ? 1 : 0, diff.soundnessDisagreements(), what);
                    assertEquals(x == R && y == A ? 1 : 0, diff.completenessDisagreements(), what);
                    assertEquals(x == y && x != D ? 1 : 0, diff.agreements(), what);
                    assertEquals(x == D ? 1 : 0, diff.declinedByUnderTest(), what);
                    assertEquals(y == D ? 1 : 0, diff.declinedByOracle(), what);
                    assertEquals(1, diff.checks(), what);

                    if (got != A) {
                        Reason reason = s.lastReason();
                        if (disagree && policy == DiffEngine.Policy.DECLINE) {
                            assertEquals(Reason.ENGINE_DISAGREEMENT, reason, what);
                            assertTrue(s.lastMessage().contains("cid 17"), s.lastMessage());
                        } else if (got == R) {
                            assertEquals(Reason.DECL_TYPE_MISMATCH, reason, what);
                        } else {
                            assertEquals(Reason.BUDGET_STEPS, reason, what);
                        }
                        // the reason belongs to the kind of outcome
                        assertEquals(got, reason.outcome(), what);
                    }
                    if (x == R && y == R) {
                        assertEquals("reference says DECL_TYPE_MISMATCH", s.lastMessage(), "a confirmed rejection reports the oracle");
                    }
                    s.close();
                    diff.close();
                }
            }
        }
    }

    @Test
    void soundnessDisagreementIsNeverAnAccept() {
        for (DiffEngine.Policy policy : DiffEngine.Policy.values()) {
            DiffEngine.Options opt = new DiffEngine.Options();
            opt.policy = policy;
            DiffEngine diff = engine(new FakeEngine("machine", fixed(A)), new FakeEngine("reference", fixed(R)), opt);
            Session s = diff.newSession(context(0));
            for (int cid = 1; cid <= 5; cid++) {
                assertFalse(s.check(cid, Budget.warm()) == A, "accepted what the oracle rejects under " + policy);
            }
            assertEquals(5, diff.soundnessDisagreements());
        }
    }

    // ---- the record ---------------------------------------------------------------------------------------------

    @Test
    void recordHasEverythingNeededToReproduce() {
        FakeEngine ut = new FakeEngine("machine", (cid, n) -> cid == 9 ? A : A);
        FakeEngine or = new FakeEngine("reference", (cid, n) -> cid == 9 ? R : A);
        DiffEngine.Options opt = new DiffEngine.Options();
        opt.label = "work/exports/init.ndjson";
        DiffEngine diff = engine(ut, or, opt);
        Session s = diff.newSession(context(4));
        assertEquals(A, s.check(7, Budget.warm()));
        assertEquals(A, s.check(8, Budget.warm()));
        assertEquals(D, s.check(9, Budget.warm()));

        List<DiffEngine.Disagreement> records = diff.disagreementRecords();
        assertEquals(1, records.size());
        DiffEngine.Disagreement d = records.get(0);
        assertEquals("SOUNDNESS", d.kind);
        assertEquals(9, d.cid);
        assertEquals("cid 9", d.constant);
        assertEquals("machine", d.underTestEngine);
        assertEquals("reference", d.oracleEngine);
        assertEquals(A, d.underTest);
        assertEquals(R, d.oracle);
        assertNull(d.underTestReason);
        assertEquals(Reason.DECL_TYPE_MISMATCH, d.oracleReason);
        assertEquals("reference says DECL_TYPE_MISMATCH", d.oracleMessage);
        assertEquals(4, d.workerId);
        assertEquals(3, d.warmChecks, "third check of a warm session");
        assertEquals(A, d.coldUnderTest);
        assertEquals(R, d.coldOracle);
        assertEquals("persistent", d.persistence());
        assertEquals("work/exports/init.ndjson", d.label);

        String line = diff.disagreements().get(0);
        for (String part : new String[] {"SOUNDNESS", "'cid 9'", "machine ACCEPT", "reference REJECT(DECL_TYPE_MISMATCH",
                "persistent", "worker 4", "check 3", "work/exports/init.ndjson"}) {
            assertTrue(line.contains(part), part + " missing in: " + line);
        }
        assertFalse(line.contains("\n"));
    }

    /** A difference that exists only in a warm session (a stale cache) is told apart by the cold re-run. */
    @Test
    void warmOnlyDisagreementIsDetected() {
        // the engine under test wrongly accepts constant 5 only when it has checked something before
        FakeEngine ut = new FakeEngine("machine", (cid, n) -> cid == 5 ? (n > 1 ? A : R) : A);
        FakeEngine or = new FakeEngine("reference", (cid, n) -> cid == 5 ? R : A);
        DiffEngine diff = engine(ut, or, new DiffEngine.Options());
        Session s = diff.newSession(context(0));
        assertEquals(A, s.check(4, Budget.warm()));
        assertEquals(D, s.check(5, Budget.warm()));
        DiffEngine.Disagreement d = diff.disagreementRecords().get(0);
        assertEquals("SOUNDNESS", d.kind);
        assertEquals(A, d.underTest);
        assertEquals(R, d.coldUnderTest, "cold, the engine under test rejects too");
        assertEquals("warm-only", d.persistence());
        assertTrue(d.toString().contains("warm-only: cold reject vs reject"), d.toString());
        MapSink sink = new MapSink();
        diff.dumpStats(sink);
        assertEquals(1L, sink.values.get("diff.disagree.warm_only"));
    }

    @Test
    void coldRecheckCanBeSwitchedOff() {
        FakeEngine ut = new FakeEngine("machine", fixed(R));
        FakeEngine or = new FakeEngine("reference", fixed(A));
        DiffEngine.Options opt = new DiffEngine.Options();
        opt.recheckCold = false;
        DiffEngine diff = engine(ut, or, opt);
        Session s = diff.newSession(context(0));
        assertEquals(D, s.check(1, Budget.warm()));
        DiffEngine.Disagreement d = diff.disagreementRecords().get(0);
        assertEquals("COMPLETENESS", d.kind);
        assertEquals(-1, d.coldUnderTest);
        assertEquals("not-rechecked", d.persistence());
        assertEquals(1, ut.log.size(), "one run per side");
        assertEquals(1, or.log.size());
    }

    // ---- sessions -------------------------------------------------------------------------------------------------

    /** A side is reset after any outcome of its own other than ACCEPT, as the driver would do. */
    @Test
    void sidesAreResetLikeTheDriverResetsSessions() {
        FakeEngine ut = new FakeEngine("machine", (cid, n) -> cid == 2 ? D : A);
        FakeEngine or = new FakeEngine("reference", fixed(A));
        DiffEngine diff = engine(ut, or, new DiffEngine.Options());
        Session s = diff.newSession(context(0));
        assertEquals(A, s.check(1, Budget.warm()));
        assertEquals(0, ut.sessions.get(0).resets);
        // the engine under test declines, the oracle accepts: the unit is accepted on the oracle's word
        assertEquals(A, s.check(2, Budget.warm()));
        assertEquals(1, ut.sessions.get(0).resets, "the declining side was reset although the combined outcome is ACCEPT");
        assertEquals(0, or.sessions.get(0).resets, "the accepting side keeps its caches");
        assertEquals(A, s.check(3, Budget.warm()));
        assertEquals(1, ut.sessions.get(0).sinceReset);
        assertEquals(3, or.sessions.get(0).sinceReset);

        s.reset();
        assertEquals(2, ut.sessions.get(0).resets);
        assertEquals(1, or.sessions.get(0).resets);
        assertEquals(2000, s.footprintBytes());
        assertEquals(ut.sessions.get(0).steps + or.sessions.get(0).steps, s.steps());
        assertNull(s.ops());
        s.close();
        assertTrue(ut.sessions.get(0).closed && or.sessions.get(0).closed);
    }

    @Test
    void anExceptionInOneSideIsADeclineOfThatSide() {
        FakeEngine ut = new FakeEngine("machine", fixed(99));
        FakeEngine or = new FakeEngine("reference", fixed(R));
        DiffEngine diff = engine(ut, or, new DiffEngine.Options());
        Session s = diff.newSession(context(0));
        assertEquals(R, s.check(1, Budget.warm()), "the oracle's verdict stands");
        assertEquals(1, diff.declinedByUnderTest());
        assertEquals(0, diff.disagreementCount());

        FakeEngine ut2 = new FakeEngine("machine", fixed(99));
        FakeEngine or2 = new FakeEngine("reference", fixed(99));
        DiffEngine both = engine(ut2, or2, new DiffEngine.Options());
        Session s2 = both.newSession(context(0));
        assertEquals(D, s2.check(1, Budget.warm()));
        assertEquals(Reason.INTERNAL_ERROR, s2.lastReason());
        assertTrue(s2.lastMessage().contains("boom on 1"), s2.lastMessage());
    }

    @Test
    void errorsAreNotCaught() {
        FakeEngine ut = new FakeEngine("machine", (cid, n) -> {
            throw new StackOverflowError("deep");
        });
        DiffEngine diff = engine(ut, new FakeEngine("reference", fixed(A)), new DiffEngine.Options());
        Session s = diff.newSession(context(0));
        assertThrows(StackOverflowError.class, () -> s.check(1, Budget.warm()));
    }

    @Test
    void eachSideGetsItsOwnContextWhenAForkIsGiven() {
        FakeEngine ut = new FakeEngine("machine", fixed(A));
        FakeEngine or = new FakeEngine("reference", fixed(A));
        DiffEngine.Options opt = new DiffEngine.Options();
        List<WorkerContext> forked = new ArrayList<>();
        opt.fork = primary -> {
            WorkerContext c = context(primary.workerId);
            forked.add(c);
            return c;
        };
        DiffEngine diff = engine(ut, or, opt);
        WorkerContext ctx = context(6);
        Session s = diff.newSession(ctx);
        assertSame(ctx, ut.sessions.get(0).ctx);
        assertEquals(1, forked.size());
        assertSame(forked.get(0), or.sessions.get(0).ctx);
        assertNotSame(ctx, or.sessions.get(0).ctx);
        assertEquals(A, s.check(1, Budget.warm()));
        s.reset();

        // without a fork both sides see the caller's context
        FakeEngine ut2 = new FakeEngine("machine", fixed(A));
        FakeEngine or2 = new FakeEngine("reference", fixed(A));
        DiffEngine shared = engine(ut2, or2, new DiffEngine.Options());
        shared.newSession(ctx);
        assertSame(ctx, ut2.sessions.get(0).ctx);
        assertSame(ctx, or2.sessions.get(0).ctx);
    }

    @Test
    void theOracleCanHaveItsOwnBudget() {
        FakeEngine ut = new FakeEngine("machine", fixed(A));
        FakeEngine or = new FakeEngine("reference", fixed(A));
        DiffEngine.Options opt = new DiffEngine.Options();
        opt.oracleBudget = Budget.cold(1L << 30);
        DiffEngine diff = engine(ut, or, opt);
        Session s = diff.newSession(context(0));
        Budget warm = Budget.warm();
        s.check(1, warm);
        assertSame(warm, ut.sessions.get(0).lastBudget);
        assertSame(opt.oracleBudget, or.sessions.get(0).lastBudget);
    }

    /** An oracle that runs out of a budget is asked once more, fresh and cold, when the engine under test gave a verdict. */
    @Test
    void anOracleThatRanOutOfBudgetIsRetriedCold() {
        int[] calls = new int[8];
        // the oracle declines every constant the first time it sees it; asked again it rejects 1 and accepts 2
        FakeEngine or = new FakeEngine("reference", (cid, n) -> calls[cid]++ == 0 ? D : cid == 1 ? R : A);
        FakeEngine ut = new FakeEngine("machine", (cid, n) -> cid == 3 ? D : A);
        DiffEngine.Options opt = new DiffEngine.Options();
        opt.oracleRetryBytes = 3L << 30;
        opt.recheckCold = false;
        DiffEngine diff = engine(ut, or, opt);
        Session s = diff.newSession(context(0));

        assertEquals(D, s.check(1, Budget.warm()), "machine accepts, the retried oracle rejects: a soundness disagreement");
        assertEquals(Reason.ENGINE_DISAGREEMENT, s.lastReason());
        assertEquals(1, diff.soundnessDisagreements());
        Budget retry = or.sessions.get(0).lastBudget;
        assertEquals(3L << 30, retry.maxBytes);
        assertFalse(retry.hasStepLimit());
        assertEquals(Integer.MAX_VALUE, retry.maxDepth);

        assertEquals(A, s.check(2, Budget.warm()), "retried, the oracle agrees");
        assertEquals(1, diff.agreements());
        assertEquals(2, diff.oracleRetries());
        assertEquals(0, diff.declinedByOracle(), "a decline that the retry resolved is not counted");

        // no retry when the engine under test declined too: there is nothing to compare
        assertEquals(D, s.check(3, Budget.warm()));
        assertEquals(2, diff.oracleRetries());
        assertEquals(1, diff.declinedByOracle());

        // off by default for code that builds the options itself
        int[] again = new int[8];
        FakeEngine or2 = new FakeEngine("reference", (cid, n) -> again[cid]++ == 0 ? D : R);
        DiffEngine plain = engine(new FakeEngine("machine", fixed(A)), or2, new DiffEngine.Options());
        Session p = plain.newSession(context(0));
        assertEquals(A, p.check(1, Budget.warm()), "the oracle declined: the verdict of the engine under test stands");
        assertEquals(0, plain.oracleRetries());
        assertEquals(1, plain.declinedByOracle());
        // the launcher's settings switch it on (6 GiB), and off with 0
        assertEquals(6L << 30, DiffEngine.Options.fromSettings(new HashMap<>()).oracleRetryBytes);
        Map<String, String> off = new HashMap<>();
        off.put("diff.oracle-retry-mib", "0");
        assertEquals(0, DiffEngine.Options.fromSettings(off).oracleRetryBytes);
    }

    // ---- the engine ------------------------------------------------------------------------------------------------

    @Test
    void engineLifecycleAndStatistics(@TempDir Path tmp) throws Exception {
        FakeEngine ut = new FakeEngine("machine", (cid, n) -> cid % 4 == 0 ? R : A);
        FakeEngine or = new FakeEngine("reference", (cid, n) -> cid % 6 == 0 ? R : A);
        DiffEngine.Options opt = new DiffEngine.Options();
        opt.policy = DiffEngine.Policy.FOLLOW_ORACLE;
        opt.reportFile = tmp.resolve("reports/diff.jsonl");
        opt.maxRecorded = 2;
        EngineFactory factory = DiffEngine.of(ut.factory(), or.factory(), opt);
        DiffEngine diff = (DiffEngine) factory.create(null, CONFIG);
        assertEquals("diff", diff.name());
        assertSame(CONFIG, ut.config);
        assertSame(CONFIG, or.config);
        diff.environmentAdvanced(40);
        assertEquals(40, ut.environmentAdvanced);
        assertEquals(40, or.environmentAdvanced);

        Session s = diff.newSession(context(0));
        int accepted = 0;
        int rejected = 0;
        for (int cid = 1; cid <= 24; cid++) {
            int o = s.check(cid, Budget.warm());
            // FOLLOW_ORACLE: the combined outcome is the oracle's on every unit
            assertEquals(cid % 6 == 0 ? R : A, o, "cid " + cid);
            if (o == A) {
                accepted++;
            } else {
                rejected++;
            }
        }
        assertEquals(20, accepted);
        assertEquals(4, rejected);
        // cid 4, 8, 16, 20: only the engine under test rejects; cid 6, 18: only the oracle; 12, 24: both
        assertEquals(4, diff.completenessDisagreements());
        assertEquals(2, diff.soundnessDisagreements());
        assertEquals(6, diff.disagreementCount());
        assertEquals(2, diff.disagreementRecords().size(), "the in-memory list is bounded");
        assertEquals(2, opt.found.size(), "and so is the list shared by all engines of these options");
        List<String> report = Files.readAllLines(opt.reportFile, StandardCharsets.UTF_8);
        assertEquals(6, report.size(), "the report file has every disagreement");
        Json first = Json.parse(report.get(0));
        assertEquals("COMPLETENESS", first.getText("kind", ""));
        assertEquals(4, first.getLong("cid", -1));
        assertEquals("reject", first.getText("under_test", ""));
        assertEquals("accept", first.getText("oracle", ""));

        MapSink sink = new MapSink();
        diff.dumpStats(sink);
        assertEquals(24L, sink.values.get("diff.checks"));
        assertEquals(16L, sink.values.get("diff.agree.accept"));
        assertEquals(2L, sink.values.get("diff.agree.reject"));
        assertEquals(2L, sink.values.get("diff.disagree.soundness"));
        assertEquals(4L, sink.values.get("diff.disagree.completeness"));
        assertEquals("machine", sink.values.get("diff.under_test"));
        assertEquals("reference", sink.values.get("diff.oracle"));
        assertEquals("FOLLOW_ORACLE", sink.values.get("diff.policy"));
        assertEquals("shared", sink.values.get("diff.context"));
        assertTrue(sink.values.containsKey("diff.under_test.checks"), "statistics of the sides are forwarded under a prefix");
        assertTrue(sink.values.containsKey("diff.oracle.checks"));
        assertTrue(sink.values.containsKey("diff.disagreement.0"));

        s.close();
        diff.close();
        diff.close();
        assertTrue(ut.closed && or.closed);
    }

    /** The driver makes several engines from one factory (warm, strict); a harness reads the shared list. */
    @Test
    void enginesOfOneFactoryShareTheListOfDisagreements() {
        FakeEngine ut = new FakeEngine("machine", (cid, n) -> cid == 3 ? A : A);
        FakeEngine or = new FakeEngine("reference", (cid, n) -> cid == 3 ? R : A);
        DiffEngine.Options opt = new DiffEngine.Options();
        EngineFactory factory = DiffEngine.of(ut.factory(), or.factory(), opt);
        Engine warm = factory.create(null, CONFIG);
        Engine strict = factory.create(null, CONFIG.withStrict(true));
        Session w = warm.newSession(context(0));
        assertEquals(A, w.check(2, Budget.warm()));
        assertEquals(D, w.check(3, Budget.warm()));
        Session s = strict.newSession(context(0));
        assertEquals(D, s.check(3, Budget.cold(1L << 30)));
        assertEquals(2, opt.found.size());
        assertFalse(opt.found.get(0).strict);
        assertTrue(opt.found.get(1).strict);
        assertEquals(3, opt.found.get(1).cid);
        assertTrue(opt.found.get(1).toString().contains("strict"));
        assertEquals(1, ((DiffEngine) warm).disagreementCount());
        assertEquals(1, ((DiffEngine) strict).disagreementCount());
    }

    @Test
    void twoArgumentFactoryFailsClosed() {
        FakeEngine ut = new FakeEngine("machine", fixed(A));
        FakeEngine or = new FakeEngine("reference", fixed(R));
        DiffEngine diff = (DiffEngine) DiffEngine.of(ut.factory(), or.factory()).create(null, CONFIG);
        Session s = diff.newSession(context(0));
        assertEquals(D, s.check(1, Budget.warm()));
        assertEquals(Reason.ENGINE_DISAGREEMENT, s.lastReason());
    }

    @Test
    void sessionsOfSeveralWorkersShareTheCounters() throws Exception {
        FakeEngine ut = new FakeEngine("machine", (cid, n) -> cid % 10 == 0 ? R : A);
        FakeEngine or = new FakeEngine("reference", fixed(A));
        DiffEngine.Options opt = new DiffEngine.Options();
        opt.policy = DiffEngine.Policy.FOLLOW_ORACLE;
        opt.recheckCold = false;
        // the recording fakes are not thread-safe; these stateless ones are
        DiffEngine diff = new DiffEngine(null, CONFIG, (s, c) -> new ThreadSafeEngine(ut), (s, c) -> new ThreadSafeEngine(or), opt);
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            int worker = t;
            threads[t] = new Thread(() -> {
                Session s = diff.newSession(context(worker));
                for (int cid = 1; cid <= 500; cid++) {
                    s.check(cid, Budget.warm());
                }
                s.close();
            });
            threads[t].start();
        }
        for (Thread t : threads) {
            t.join();
        }
        assertEquals(2000, diff.checks());
        assertEquals(200, diff.completenessDisagreements());
        assertEquals(1800, diff.agreements());
        assertEquals(200, diff.disagreementRecords().size());
    }

    /** Scripted engine without shared mutable state: one counter per session. */
    static final class ThreadSafeEngine implements Engine {
        final FakeEngine template;

        ThreadSafeEngine(FakeEngine template) {
            this.template = template;
        }

        @Override
        public String name() {
            return template.name;
        }

        @Override
        public Session newSession(WorkerContext ctx) {
            return new Session() {
                long n;
                Reason reason;

                @Override
                public int check(int cid, Budget budget) {
                    n++;
                    int o = template.script.outcome(cid, n);
                    reason = o == R ? Reason.DECL_TYPE_MISMATCH : o == D ? Reason.BUDGET_STEPS : null;
                    return o;
                }

                @Override
                public Reason lastReason() {
                    return reason;
                }

                @Override
                public String lastMessage() {
                    return "m";
                }

                @Override
                public KernelOps ops() {
                    return null;
                }

                @Override
                public void reset() {
                    n = 0;
                }

                @Override
                public long footprintBytes() {
                    return 0;
                }

                @Override
                public long steps() {
                    return n;
                }

                @Override
                public void close() {}
            };
        }

        @Override
        public void dumpStats(StatsSink sink) {}

        @Override
        public void close() {}
    }
}
