package rlvm.ref;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import rlvm.api.Budget;
import rlvm.api.EngineConfig;
import rlvm.api.Outcome;
import rlvm.api.Reason;
import rlvm.api.RunSignals;
import rlvm.api.WorkerContext;
import rlvm.store.LitPool;

/**
 * What a session keeps between declarations: the table of imported constant terms (kept copies must
 * be exactly the copies a declaration would build, and must never be used after the overlay lost
 * them) and nothing else (terms and computed literals of a declaration are gone when it ends).
 */
class ImportsTest {

    private static final int N = 24;
    private static final Budget BUDGET = Budget.cold(1L << 30);

    private Fixture fx;
    private final List<String> names = new ArrayList<>();

    @BeforeEach
    void setUp() {
        names.clear();
        fx = Fixture.standard(f -> {
            // Each declaration imports types (monomorphic and universe-polymorphic), unfolds values
            // (Nat.add, two, four, id) and uses the recursor rules of Nat.rec through Nat.add.
            for (int i = 0; i < N; i++) {
                String thm = "sum" + i;
                f.thm(thm, "", "((@ Eq 1) Nat (Nat.add two two) ((@ id 1) Nat four))",
                        "((@ Eq.refl 1) Nat (Nat.add two (Nat.succ (Nat.succ Nat.zero))))");
                names.add(thm);
                String def = "pair" + i;
                f.def(def, "", "((@ Prod 0 0) Nat Bool)",
                        "((@ Prod.rec 1 0 0) Nat Bool (fun (t : ((@ Prod 0 0) Nat Bool)) ((@ Prod 0 0) Nat Bool))"
                                + " (fun (a : Nat) (b : Bool) ((@ Prod.mk 0 0) Nat Bool (Nat.add a two) b)) prDef)",
                        Fixture.regular(3));
                names.add(def);
            }
            f.def("badMiddle", "", "Nat", "Bool.true", Fixture.regular(1));
            // 2^64 + k is computed on the way and is not a literal of the store: it lives in the worker's pool
            String m = "(lit 18446744073709551615)";
            f.thm("bigSum", "", "((@ Eq 1) Nat (Nat.sub (Nat.add " + m + " (lit 5)) " + m + ") (lit 5))",
                    "((@ Eq.refl 1) Nat (lit 5))");
            f.thm("bigSum2", "", "((@ Eq 1) Nat (Nat.sub (Nat.add " + m + " (lit 7)) " + m + ") (lit 7))",
                    "((@ Eq.refl 1) Nat (lit 7))");
            f.thm("bigSumWrong", "", "((@ Eq 1) Nat (Nat.sub (Nat.add " + m + " (lit 7)) " + m + ") (lit 5))",
                    "((@ Eq.refl 1) Nat (lit 5))");
            // 2^1000000 (15,625 limbs) is computed twice and subtracted from itself
            f.thm("hugePow", "", "((@ Eq 1) Nat (Nat.sub (Nat.pow (lit 2) (lit 1000000)) (Nat.pow (lit 2) (lit 1000000)))"
                    + " (lit 0))", "((@ Eq.refl 1) Nat (lit 0))");
        });
    }

    private static RefOptions options(long bytes, int minUses, boolean verify) {
        // the default level relation (official, see LevelsTest); the import table does not depend on it
        return new RefOptions(true, bytes, minUses, verify);
    }

    private RefEngine.RefSession session(RefOptions options, EngineConfig config, WorkerContext ctx) {
        RefEngine engine = new RefEngine(fx.store, config, options);
        return (RefEngine.RefSession) engine.newSession(ctx);
    }

    private void acceptAll(RefEngine.RefSession s) {
        for (String name : names) {
            assertEquals(Outcome.ACCEPT, s.check(fx.cid(name), BUDGET), () -> name + ": " + s.lastMessage());
        }
    }

    @Test
    void aSessionKeepsTheImportsThatSeveralDeclarationsAskFor() {
        // verify = true: every answer from the table is recomputed and compared, so a wrong id fails the check
        RefEngine.RefSession s = session(options(1L << 20, 2, true), EngineConfig.defaults(), fx.ctx);
        acceptAll(s);
        long[] st = s.kernel.importStats();
        assertTrue(st[0] > 0, "hits " + st[0]);
        assertTrue(st[2] > 0, "promoted " + st[2]);
        assertEquals(0, st[3], "the table was never dropped");
        // Almost every declaration after the second finds all it needs in the table.
        assertTrue(st[0] > 5 * st[1], "hits " + st[0] + " vs copies " + st[1]);
        // The kept copies are the only thing in the overlay between declarations.
        assertEquals(st[4], fx.ctx.overlay.nodeCount(), "kept nodes vs overlay nodes");
        s.close();
    }

    @Test
    void theTableChangesNeitherVerdictsNorSteps() {
        RefEngine.RefSession off = session(options(0L, 2, false), EngineConfig.defaults(), fx.ctx);
        acceptAll(off);
        assertEquals(Outcome.REJECT, off.check(fx.cid("badMiddle"), BUDGET));
        long stepsOff = off.steps();
        assertEquals(0, off.kernel.importStats()[0]);
        assertEquals(0, off.kernel.importStats()[2]);
        assertEquals(0, fx.ctx.overlay.nodeCount(), "nothing survives a declaration");
        off.close();

        RefEngine.RefSession on = session(options(1L << 20, 1, false), EngineConfig.defaults(), fx.ctx);
        acceptAll(on);
        assertEquals(Outcome.REJECT, on.check(fx.cid("badMiddle"), BUDGET));
        assertEquals(Reason.DECL_TYPE_MISMATCH, on.lastReason());
        assertEquals(stepsOff, on.steps(), "the kernel does the same work");
        assertTrue(on.kernel.importStats()[0] > 0);
        on.close();
    }

    @Test
    void theTableIsOffUnlessAskedFor() {
        assertEquals(0L, RefOptions.fromSystemProperties().importBytes);
        assertEquals(0L, RefOptions.parse(java.util.Map.of("ref.imports", "off")).importBytes);
        assertTrue(RefOptions.parse(java.util.Map.of("ref.imports", "on")).importBytes > 0);
        assertFalse(RefOptions.parse(java.util.Map.of("ref.imports", "on")).importVerify);
        assertTrue(RefOptions.parse(java.util.Map.of("ref.imports", "verify")).importBytes > 0);
        assertTrue(RefOptions.parse(java.util.Map.of("ref.imports", "verify")).importVerify);
        // the engine as the launcher creates it without settings
        RefEngine engine = new RefEngine(fx.store, EngineConfig.defaults());
        RefEngine.RefSession s = (RefEngine.RefSession) engine.newSession(fx.ctx);
        acceptAll(s);
        assertEquals(0, s.kernel.importStats()[0]);
        assertEquals(0, s.kernel.importStats()[2]);
        assertEquals(0, fx.ctx.overlay.nodeCount(), "nothing survives a declaration");
        s.close();
        // and when asked for through the provider
        RefEngine on = (RefEngine) new RefEngine.Provider().factory(java.util.Map.of("ref.imports", "on")).create(fx.store,
                EngineConfig.defaults());
        RefEngine.RefSession t = (RefEngine.RefSession) on.newSession(fx.ctx);
        acceptAll(t);
        assertTrue(t.kernel.importStats()[0] > 0);
        t.close();
    }

    @Test
    void strictEnginesKeepNothing() {
        RefEngine.RefSession s = session(options(1L << 20, 1, false), EngineConfig.defaults().withStrict(true), fx.ctx);
        acceptAll(s);
        long[] st = s.kernel.importStats();
        assertEquals(0, st[0]);
        assertEquals(0, st[2]);
        assertEquals(0, fx.ctx.overlay.nodeCount());
        s.close();
    }

    @Test
    void theBudgetBoundsWhatIsKept() {
        // room for 16 nodes: at most that much is promoted before the session stops
        RefEngine.RefSession s = session(options(16 * 32L, 1, true), EngineConfig.defaults(), fx.ctx);
        acceptAll(s);
        long kept = s.kernel.importStats()[4];
        assertTrue(kept > 0 && kept < 200, "kept " + kept);
        assertEquals(kept, fx.ctx.overlay.nodeCount());
        s.close();
    }

    @Test
    void aRejectedDeclarationPromotesNothingAndTheSessionGoesOn() {
        RefEngine.RefSession s = session(options(1L << 20, 1, true), EngineConfig.defaults(), fx.ctx);
        assertEquals(Outcome.REJECT, s.check(fx.cid("badMiddle"), BUDGET));
        assertEquals(0, s.kernel.importStats()[2]);
        assertEquals(0, fx.ctx.overlay.nodeCount());
        acceptAll(s);
        assertEquals(Outcome.REJECT, s.check(fx.cid("badMiddle"), BUDGET));
        acceptAll(s);
        assertTrue(s.kernel.importStats()[0] > 0);
        s.close();
    }

    @Test
    void aSessionResetForgetsTheTable() {
        RefEngine.RefSession s = session(options(1L << 20, 1, true), EngineConfig.defaults(), fx.ctx);
        acceptAll(s);
        assertTrue(s.kernel.importStats()[4] > 0);
        s.reset();
        fx.ctx.reset();
        assertEquals(0, s.kernel.importStats()[4]);
        assertEquals(1, s.kernel.importStats()[3]);
        acceptAll(s);
        s.close();
    }

    @Test
    void anOverlayResetBehindTheSessionsBackIsNoticed() {
        RefEngine.RefSession s = session(options(1L << 20, 1, true), EngineConfig.defaults(), fx.ctx);
        acceptAll(s);
        long kept = s.kernel.importStats()[4];
        assertTrue(kept > 0);
        // The context is reset without the session, and the overlay refilled beyond the kept copies with
        // other terms: every id in the table now names a foreign node.
        fx.ctx.reset();
        int filler = fx.t("n0");
        for (int i = 0; fx.ctx.overlay.nodeCount() <= kept + 8; i++) {
            filler = fx.t("(g (f (lit " + i + ")) (f (f (lit " + i + "))))");
        }
        assertTrue(filler != 0);
        acceptAll(s);
        assertEquals(1, s.kernel.importStats()[3], "the table was dropped once");
        assertEquals(Outcome.REJECT, s.check(fx.cid("badMiddle"), BUDGET));
        // The same when the overlay is cut back into the kept copies with a mark of somebody else.
        fx.ctx.reset();
        s.reset();
        long early = fx.ctx.overlay.mark();
        acceptAll(s);
        assertTrue(s.kernel.importStats()[4] > 0);
        long drops = s.kernel.importStats()[3];
        fx.ctx.overlay.resetTo(early);
        for (int i = 0; fx.ctx.overlay.nodeCount() <= kept + 8; i++) {
            filler = fx.t("(g (f (lit " + (1000 + i) + ")) (f (f (lit " + i + "))))");
        }
        acceptAll(s);
        assertEquals(drops + 1, s.kernel.importStats()[3]);
        s.close();
    }

    @Test
    void theKernelInterfaceReadsTheTableButNeverFillsIt() {
        RefEngine.RefSession s = session(options(1L << 20, 1, true), EngineConfig.defaults(), fx.ctx);
        // judgments through KernelOps (admission) do not count towards promotion
        for (int i = 0; i < 5; i++) {
            s.kernel.begin(Integer.MAX_VALUE, fx.names(""));
            fx.k.begin(Integer.MAX_VALUE, fx.names(""));
            assertEquals(fx.k.whnf(fx.t("(Nat.add two two)")), s.kernel.whnf(fx.t("(Nat.add two two)")));
            s.kernel.reset();
        }
        assertEquals(0, s.kernel.importStats()[1]);
        assertEquals(0, s.kernel.importStats()[2]);
        // after declarations filled it, the same judgments are answered from it, with the same results
        fx.ctx.reset();
        acceptAll(s);
        long hits = s.kernel.importStats()[0];
        s.kernel.begin(Integer.MAX_VALUE, fx.names(""));
        fx.assertTerm("(lit 4)", s.kernel.whnf(fx.t("(Nat.add two two)")));
        fx.assertTerm("((@ Prod 0 0) Nat Bool)", s.kernel.infer(fx.t("((@ Prod.mk 0 0) Nat Bool n0 Bool.true)")));
        assertTrue(s.kernel.importStats()[0] > hits);
        s.kernel.endDeclaration();
        s.close();
    }

    @Test
    void literalsOfADeclarationAreGoneWhenItEnds() {
        WorkerContext ctx = fx.bindings.newWorkerContext(3, fx.store, RunSignals.NONE, 1L << 30);
        LitPool pool = ctx.lits;
        assertTrue(pool.isWorkerPool());
        RefEngine.RefSession s = session(options(1L << 20, 2, true), EngineConfig.defaults(), ctx);
        // The declaration really puts a number of 125 KB into the worker's pool (seen with a bare kernel,
        // which leaves the clean-up to its caller).
        long before = pool.bytes();
        RefKernel bare = new RefKernel(ctx, EngineConfig.defaults(), options(0L, 1, false));
        bare.setBudget(BUDGET);
        bare.checkConstant(fx.cid("hugePow"));
        assertTrue(pool.bytes() > before + 100_000, "pool bytes " + before + " -> " + pool.bytes());
        bare.reset();
        ctx.reset();
        // Through the session nothing of it is left when the declaration ends, however often it is checked,
        // and the numbers of later declarations are the right ones whatever references the pool gives them.
        for (int round = 0; round < 40; round++) {
            assertEquals(Outcome.ACCEPT, s.check(fx.cid("hugePow"), BUDGET), () -> s.lastMessage());
            assertEquals(Outcome.ACCEPT, s.check(fx.cid("bigSum"), BUDGET), () -> s.lastMessage());
            assertEquals(Outcome.ACCEPT, s.check(fx.cid("bigSum2"), BUDGET), () -> s.lastMessage());
            assertEquals(Outcome.REJECT, s.check(fx.cid("bigSumWrong"), BUDGET), () -> s.lastMessage());
            assertEquals(Reason.DECL_TYPE_MISMATCH, s.lastReason());
        }
        assertTrue(pool.bytes() < before + 100_000, "pool bytes " + before + " -> " + pool.bytes());
        assertFalse(ctx.footprintBytes() > (2L << 20), "footprint " + ctx.footprintBytes());
        s.close();
    }
}
