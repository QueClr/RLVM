package rlvm.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
import rlvm.api.bind.Bindings;
import rlvm.api.bind.LevelBinding;
import rlvm.ref.RefEngine;
import rlvm.store.DeclKind;
import rlvm.store.IntLists;
import rlvm.store.Store;
import rlvm.store.StoreBuilder;

/**
 * DiffEngine against the real reference engine ({@code rlvm-ref}) over a real store
 * ({@code rlvm-store}, contexts from {@code rlvm.api.bind.Bindings}). The store is written by hand
 * through the loader-side builder: three definitions over sorts, one of them ill-typed
 * ({@code def bad : Prop := Type}, the arena's tutorial/002_badDef).
 */
class DiffEngineIntegrationTest {

    private Bindings bindings;
    private Store store;
    private int good;
    private int bad;
    private int good2;
    private final EngineConfig config = new EngineConfig(EngineConfig.KernelProfile.LEAN_4_36, false, false, false, false, 1);

    private int name(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        return store.names().internStr(0, b, 0, b.length);
    }

    @BeforeEach
    void buildStore() {
        bindings = new Bindings();
        store = new Store(0L, bindings.baseLists(), bindings.baseLevelInfo());
        bindings.bindNames(store.names());
        LevelBinding levels = bindings.baseLevels();
        int one = levels.succ(levels.zero());
        int two = levels.succ(one);
        StoreBuilder sb = store.builder();

        // def good : Sort 2 := Sort 1
        good = sb.beginDeclaration(DeclKind.DEFN, name("good"), IntLists.EMPTY);
        sb.endDeclaration(good, sb.sort(two), sb.sort(one), DeclKind.hintRegular(1));
        // def bad : Sort 0 := Sort 1        (the value has type Sort 2)
        bad = sb.beginDeclaration(DeclKind.DEFN, name("bad"), IntLists.EMPTY);
        sb.endDeclaration(bad, sb.sort(levels.zero()), sb.sort(one), DeclKind.hintRegular(1));
        // def good2 : Sort 2 := good
        good2 = sb.beginDeclaration(DeclKind.DEFN, name("good2"), IntLists.EMPTY);
        int type = sb.sort(two);
        sb.endDeclaration(good2, type, sb.constant(name("good"), IntLists.EMPTY), DeclKind.hintRegular(2));
        sb.finish();
        store.publishEnv(store.loadedUnits());
        bindings.freeze();
        assertTrue(good > 0 && bad > 0 && good2 > 0);
    }

    private WorkerContext context(int id) {
        return bindings.newWorkerContext(id, store, RunSignals.NONE, 1L << 26);
    }

    private DiffEngine.Options forked(List<WorkerContext> made) {
        DiffEngine.Options opt = new DiffEngine.Options();
        opt.fork = primary -> {
            WorkerContext c = bindings.newWorkerContext(primary.workerId, primary.store, primary.signals, 1L << 26);
            made.add(c);
            return c;
        };
        opt.label = "hand-made store";
        return opt;
    }

    /** The reference engine against itself, each side in its own context: the smoke test of the harness. */
    @Test
    void referenceAgainstItselfAgrees() {
        List<WorkerContext> made = new ArrayList<>();
        DiffEngine.Options opt = forked(made);
        DiffEngine diff = new DiffEngine(store, config, RefEngine.FACTORY, RefEngine.FACTORY, opt);
        WorkerContext ctx = context(0);
        Session s = diff.newSession(ctx);
        assertEquals(1, made.size());
        assertNotSame(ctx, made.get(0));
        assertNotSame(ctx.overlay, made.get(0).overlay);

        assertEquals(Outcome.ACCEPT, s.check(good, Budget.warm()), () -> s.lastReason() + " " + s.lastMessage());
        assertEquals(Outcome.REJECT, s.check(bad, Budget.warm()));
        assertEquals(Reason.DECL_TYPE_MISMATCH, s.lastReason(), s::lastMessage);
        assertNotNull(s.lastMessage());
        assertEquals(Outcome.ACCEPT, s.check(good2, Budget.warm()), () -> s.lastReason() + " " + s.lastMessage());
        // again after a reset of both sides and of the oracle's own context
        s.reset();
        ctx.reset();
        assertEquals(Outcome.ACCEPT, s.check(good2, Budget.warm()));
        assertEquals(Outcome.REJECT, s.check(bad, Budget.cold(1L << 30)));

        assertEquals(5, diff.checks());
        assertEquals(5, diff.agreements());
        assertEquals(0, diff.disagreementCount());
        assertEquals(0, diff.declinedByUnderTest());
        assertEquals(0, diff.declinedByOracle());
        assertTrue(opt.found.isEmpty());
        assertTrue(s.footprintBytes() >= 0);
        assertTrue(s.steps() >= 0);
        KernelOps ops = s.ops();
        assertNotNull(ops, "the reference session offers its judgments to admission code");
        s.close();
        diff.close();
    }

    /** The same without a fork: both reference sessions share the caller's context, one after the other. */
    @Test
    void referenceAgainstItselfInASharedContext() {
        DiffEngine diff = new DiffEngine(store, config, RefEngine.FACTORY, RefEngine.FACTORY);
        Session s = diff.newSession(context(0));
        for (int round = 0; round < 3; round++) {
            assertEquals(Outcome.ACCEPT, s.check(good, Budget.warm()));
            assertEquals(Outcome.REJECT, s.check(bad, Budget.warm()));
            assertEquals(Outcome.ACCEPT, s.check(good2, Budget.warm()));
        }
        assertEquals(9, diff.agreements());
        assertEquals(0, diff.disagreementCount());
        s.close();
        diff.close();
    }

    /** An engine that answers the same for every constant. */
    private static EngineFactory constant(String name, int outcome) {
        return (st, cfg) -> new Engine() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Session newSession(WorkerContext ctx) {
                return new Session() {
                    @Override
                    public int check(int cid, Budget budget) {
                        return outcome;
                    }

                    @Override
                    public Reason lastReason() {
                        return outcome == Outcome.REJECT ? Reason.APP_TYPE_MISMATCH : Reason.BUDGET_STEPS;
                    }

                    @Override
                    public String lastMessage() {
                        return name + " made this up";
                    }

                    @Override
                    public KernelOps ops() {
                        return null;
                    }

                    @Override
                    public void reset() {}

                    @Override
                    public long footprintBytes() {
                        return 0;
                    }

                    @Override
                    public long steps() {
                        return 0;
                    }

                    @Override
                    public void close() {}
                };
            }

            @Override
            public void dumpStats(StatsSink sink) {}

            @Override
            public void close() {}
        };
    }

    /** An engine that accepts everything is caught by the real reference kernel, with the constant's real name and unit. */
    @Test
    void anEngineThatAcceptsEverythingIsCaught() {
        List<WorkerContext> made = new ArrayList<>();
        DiffEngine.Options opt = forked(made);
        DiffEngine diff = new DiffEngine(store, config, constant("yes-machine", Outcome.ACCEPT), RefEngine.FACTORY, opt);
        Session s = diff.newSession(context(3));
        assertEquals(Outcome.ACCEPT, s.check(good, Budget.warm()));
        assertEquals(Outcome.DECLINE, s.check(bad, Budget.warm()));
        assertEquals(Reason.ENGINE_DISAGREEMENT, s.lastReason());
        assertEquals(Outcome.ACCEPT, s.check(good2, Budget.warm()), "the oracle still works after the disagreement and its resets");

        assertEquals(1, diff.soundnessDisagreements());
        DiffEngine.Disagreement d = diff.disagreementRecords().get(0);
        assertEquals("SOUNDNESS", d.kind);
        assertEquals("bad", d.constant, "the name comes from the store");
        assertEquals(bad, d.cid);
        assertEquals(store.decls().unit(bad), d.unit);
        assertEquals("yes-machine", d.underTestEngine);
        assertEquals("reference", d.oracleEngine);
        assertEquals(Reason.DECL_TYPE_MISMATCH, d.oracleReason);
        assertEquals("persistent", d.persistence(), "cold, the reference kernel rejects again");
        assertEquals(3, d.workerId);
        assertTrue(d.toString().contains("'bad'") && d.toString().contains("hand-made store"), d.toString());
        assertEquals(1, opt.found.size());
        s.close();
        diff.close();
    }

    /** An engine that rejects everything: completeness disagreements; under FOLLOW_ORACLE the run goes on with the oracle's verdicts. */
    @Test
    void anEngineThatRejectsEverythingIsCaught() {
        DiffEngine.Options opt = new DiffEngine.Options();
        opt.policy = DiffEngine.Policy.FOLLOW_ORACLE;
        DiffEngine diff = new DiffEngine(store, config, constant("no-machine", Outcome.REJECT), RefEngine.FACTORY, opt);
        Session s = diff.newSession(context(0));
        assertEquals(Outcome.ACCEPT, s.check(good, Budget.warm()));
        assertEquals(Outcome.REJECT, s.check(bad, Budget.warm()));
        assertEquals(Reason.DECL_TYPE_MISMATCH, s.lastReason(), "a rejection both agree on reports the oracle's reason");
        assertEquals(Outcome.ACCEPT, s.check(good2, Budget.warm()));
        assertEquals(2, diff.completenessDisagreements());
        assertEquals(0, diff.soundnessDisagreements());
        assertEquals(1, diff.agreements());
        List<String> lines = diff.disagreements();
        assertTrue(lines.get(0).contains("COMPLETENESS 'good'"), lines.get(0));
        assertTrue(lines.get(1).contains("COMPLETENESS 'good2'"), lines.get(1));
        assertFalse(lines.get(0).contains("'bad'"));
        s.close();
        diff.close();
    }

    /**
     * The sampler used directly, as a test of another engine would: two {@code KernelOps} on one
     * context, a constant of the store. The reference kernel agrees with itself; judgments that lie
     * are reported with the constant, the sub-term's place and both answers.
     */
    @Test
    void theSamplerComparesTwoKernelOpsOnOneContext() {
        WorkerContext ctx = context(0);
        Engine reference = RefEngine.FACTORY.create(store, config);
        Session one = reference.newSession(ctx);
        Session two = reference.newSession(ctx);
        KernelSampler.Options opt = new KernelSampler.Options();
        opt.perConstant = 6;
        KernelSampler sampler = new KernelSampler(store, ctx, one.ops(), two.ops(), opt);
        int nodes = ctx.overlay.nodeCount();
        assertTrue(sampler.sample(good).isEmpty());
        assertTrue(sampler.sample(good2).isEmpty(), "def good2 : Sort 2 := good");
        assertEquals(2, sampler.tally().constants);
        assertEquals(12, sampler.tally().problems);
        assertEquals(12, sampler.tally().infer);
        assertTrue(sampler.tally().whnf > 0 && sampler.tally().defEq > 0, sampler.tally().toString());
        assertEquals(0, sampler.tally().differences + sampler.tally().oracleErrors + sampler.tally().undecided);
        assertEquals(nodes, ctx.overlay.nodeCount(), "the sampler leaves the overlay as it found it");

        // the same judgments with lies: isDefEq always true, whnf the identity
        Engine broken = new BrokenEngine(RefEngine.FACTORY.create(store, config), BrokenEngine.Faults.parse("defeq-true,whnf-identity"));
        Session lying = broken.newSession(ctx);
        KernelSampler caught = new KernelSampler(store, ctx, lying.ops(), two.ops(), opt);
        List<KernelSampler.Difference> found = caught.sample(good2);
        assertFalse(found.isEmpty());
        boolean head = false;
        boolean defEq = false;
        for (KernelSampler.Difference d : found) {
            assertEquals("good2", d.constant);
            assertEquals(good2, d.cid);
            assertEquals(store.decls().unit(good2), d.unit);
            head |= d.kind.equals("WHNF_HEAD");   // whnf(good) must be Sort 1, not the constant
            defEq |= d.kind.equals("DEFEQ") && d.underTest.equals("true") && d.oracle.equals("false");
            assertTrue(d.toString().startsWith("SAMPLE " + d.kind + " 'good2'"), d.toString());
        }
        assertTrue(head, found.toString());
        assertTrue(defEq, found.toString());
        // the same seed, the same sub-problems
        List<KernelSampler.Difference> again = new KernelSampler(store, ctx, lying.ops(), two.ops(), opt).sample(good2);
        assertEquals(found.toString(), again.toString());
        one.close();
        two.close();
        lying.close();
        reference.close();
        broken.close();
    }
}
