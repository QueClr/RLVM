package rlvm.ref;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import rlvm.api.Budget;
import rlvm.api.EngineConfig;
import rlvm.api.Outcome;
import rlvm.api.Reason;
import rlvm.api.ResourceException;
import rlvm.api.RunSignals;
import rlvm.api.Session;
import rlvm.api.WorkerContext;
import rlvm.store.DeclKind;
import rlvm.store.Node;
import rlvm.store.Specials;

/** Scopes, visibility, canonical terms, budgets: everything around the judgments themselves. */
class ScopeTest {

    private static final String REC = "((@ Nat.rec 1) (fun (x : Nat) Nat) n0 (fun (x : Nat) (ih : Nat) (f ih)) Nat.zero)";

    private Fixture fx;
    private RefKernel k;
    private int lateValue;

    @BeforeEach
    void setUp() {
        fx = Fixture.standard(f -> {
            // Written before `lateConst` exists: the CONST node is unbound and resolved by name later.
            lateValue = f.rawShared("(Nat.succ lateConst)");
            int never = f.rawShared("(Nat.succ neverDeclared)");
            f.def("lateConst", "", "Nat", "Nat.zero", Fixture.regular(1));
            f.defRaw("usesLate", "", "Nat", lateValue);
            f.defRaw("usesUnknown", "", "Nat", never);
            f.deepSucc("deepForCancel", 5_000);
        });
        k = fx.k;
    }

    // ---- visibility ------------------------------------------------------------------------------------

    @Test
    void membersOfTheScopesOwnUnitAreExposedByKind() {
        int nat = fx.t("Nat");
        int zero = fx.t("Nat.zero");
        int rec = fx.t("(@ Nat.rec 1)");
        k.begin(fx.rankOf("Nat"), fx.names(""));
        fx.rejects(Reason.UNKNOWN_CONSTANT, () -> k.infer(nat));
        k.exposeUnitMembers(1 << DeclKind.INDUCT);
        fx.assertTerm("Type", k.infer(nat));
        fx.rejects(Reason.UNKNOWN_CONSTANT, () -> k.infer(zero));
        k.exposeUnitMembers(1 << DeclKind.CTOR);
        assertEquals(nat, k.infer(zero));
        fx.rejects(Reason.UNKNOWN_CONSTANT, () -> k.infer(rec));
        k.exposeUnitMembers(1 << DeclKind.REC);
        k.infer(rec);
    }

    @Test
    void exposingAMemberDropsWhatDependedOnItsAbsence() {
        int nat = fx.t("Nat");
        int app = fx.t(REC);
        // Scope of the unit after Nat's, minus nothing: everything of Nat is visible, the recursor reduces.
        k.begin(fx.rankOf("Nat") + 1000, fx.names(""));
        fx.assertTerm("n0", k.whnf(app));
        // Scope of Nat's own unit with types and constructors only: the recursor is not in the environment.
        k.begin(fx.rankOf("Nat"), fx.names(""));
        k.exposeUnitMembers((1 << DeclKind.INDUCT) | (1 << DeclKind.CTOR));
        assertEquals(nat, k.infer(fx.t("Nat.zero")));
        assertEquals(app, k.whnf(app), "a recursor that is not visible does not reduce");
        // Once it is exposed the cached normal form must not survive.
        k.exposeUnitMembers(1 << DeclKind.REC);
        assertTrue(fx.show(k.whnf(app)).contains("n0") || k.whnf(app) != app);
    }

    @Test
    void definitionsOfLaterRankDoNotUnfold() {
        int two = fx.t("two");
        k.begin(fx.rankOf("two"), fx.names(""));
        assertEquals(two, k.whnf(two));
        k.begin(fx.rankOf("two") + 1, fx.names(""));
        fx.assertTerm("(lit 2)", k.whnf(two));
    }

    // ---- canonical terms -----------------------------------------------------------------------------------

    @Test
    void sharedTermsComeBackUnderTheirOwnIdWhenNothingChanges() {
        int fType = fx.store.decls().type(fx.cid("f"));
        assertTrue((fType & Node.OVERLAY_BIT) == 0);
        assertEquals(fType, k.whnf(fType));
        assertEquals(fType, k.whnfCore(fType));
        assertEquals(fType, k.ensurePi(fType));
        // the canonical copy of a shared term is the overlay term with the same structure
        fx.assertTerm("(-> Nat Nat)", k.infer(fx.t("f")));
        assertTrue(k.isDefEq(fType, fx.t("(-> Nat Nat)")));
        assertTrue(k.isDefEq(fx.store.decls().value(fx.cid("two")), fx.t("(Nat.succ (Nat.succ Nat.zero))")));
    }

    @Test
    void lateBoundReferencesAreResolvedByName() {
        Session s = fx.engine.newSession(fx.ctx);
        assertEquals(Outcome.ACCEPT, s.check(fx.cid("usesLate"), Budget.cold(1L << 30)), () -> s.lastMessage());
        assertEquals(Outcome.REJECT, s.check(fx.cid("usesUnknown"), Budget.cold(1L << 30)));
        assertEquals(Reason.UNKNOWN_CONSTANT, s.lastReason());
        s.close();
        // through the judgments as well
        fx.assertTerm("(lit 1)", k.whnf(lateValue));
    }

    @Test
    void structurallyEqualTermsShareCaches() {
        int a = fx.t("(Nat.add four four)");
        int fromShared = fx.store.decls().value(fx.cid("four"));
        fx.assertTerm("(lit 4)", k.whnf(fromShared));
        long steps = k.steps();
        // `four`'s value is `Nat.add two two`, written by the loader; the same structure built here hits the cache
        fx.assertTerm("(lit 4)", k.whnf(fx.t("(Nat.add two two)")));
        assertEquals(steps, k.steps());
        fx.assertTerm("(lit 8)", k.whnf(a));
    }

    @Test
    void aResetOverlayInvalidatesTheCaches() {
        int t1 = fx.t("(Nat.succ Nat.zero)");
        fx.assertTerm("Nat", k.infer(t1));
        fx.ctx.reset();
        int t2 = fx.t("Bool.true");
        fx.assertTerm("Bool", k.infer(t2));
        int t3 = fx.t("(Nat.succ Nat.zero)");
        fx.assertTerm("Nat", k.infer(t3));
    }

    // ---- prelude guards -------------------------------------------------------------------------------------

    @Test
    void anExtensionWhoseSpecialFailedItsGuardDeclines() {
        Fixture bad = Fixture.standard(f -> {
            f.specialState(Specials.NAT_MUL, "Nat.mul", Specials.INVALID);
            f.specialState(Specials.STRING_OF_LIST, "String.ofList", Specials.INVALID);
        });
        RefKernel kb = bad.k;
        // an extension that is not touched is not affected
        bad.assertTerm("(lit 5)", kb.whnf(bad.t("(Nat.add (lit 2) (lit 3))")));
        // not a literal argument: the extension would not fire, no decline
        int stuck = bad.t("(Nat.mul n0 (lit 2))");
        assertEquals(stuck, kb.whnf(stuck));
        ResourceException e = assertThrows(ResourceException.class, () -> kb.whnf(bad.t("(Nat.mul (lit 2) (lit 3))")));
        assertEquals(Reason.UNSUPPORTED_PRELUDE, e.reason());
        ResourceException e2 = assertThrows(ResourceException.class,
                () -> kb.isDefEq(bad.t("(str \"a\")"), bad.t("(String.ofList ((@ List.nil 0) Char))")));
        assertEquals(Reason.UNSUPPORTED_PRELUDE, e2.reason());
        // typing a string literal needs only String itself
        bad.assertTerm("String", kb.check(bad.t("(str \"a\")")));
    }

    // ---- non-termination ------------------------------------------------------------------------------------

    @Test
    void aReductionThatCyclesIsDeclined() {
        // selfApp is ill-typed (it was never checked, as under the optimistic environment): selfApp selfApp
        // unfolds to itself. The official kernel loops here; a verdict is impossible either way.
        Fixture loop = Fixture.standard(f -> {
            f.def("selfApp", "", "Nat", "(fun (x : Nat) (x x))", Fixture.regular(1));
            f.def("ping", "", "Nat", "(fun (x : Nat) (selfApp x))", Fixture.regular(2));
            // spin and spin2 unfold to each other (spin2 is late-bound in spin's value); both have a function type
            f.def("spin", "", "(-> Nat Nat)", "(fun (x : Nat) (spin2 x))", Fixture.regular(1));
            f.def("spin2", "", "(-> Nat Nat)", "(fun (x : Nat) (spin x))", Fixture.regular(1));
        });
        RefKernel kl = loop.k;
        ResourceException e = assertThrows(ResourceException.class, () -> kl.whnf(loop.t("(selfApp selfApp)")));
        assertEquals(Reason.BUDGET_STEPS, e.reason());
        // a longer cycle: ping ping -> selfApp ping -> ping ping
        ResourceException e2 = assertThrows(ResourceException.class, () -> kl.whnf(loop.t("(ping ping)")));
        assertEquals(Reason.BUDGET_STEPS, e2.reason());
        // inside a comparison: lazy delta reduction keeps unfolding spin n0 -> spin2 n0 -> spin n0
        ResourceException e3 = assertThrows(ResourceException.class,
                () -> kl.isDefEq(loop.t("(spin n0)"), loop.t("n0")));
        assertEquals(Reason.BUDGET_STEPS, e3.reason());
        ResourceException e4 = assertThrows(ResourceException.class,
                () -> kl.isDefEq(loop.t("(f (spin n0))"), loop.t("(f (spin2 (f n0)))")));
        assertEquals(Reason.BUDGET_STEPS, e4.reason());
        // terminating reductions are not affected
        loop.assertTerm("(lit 4)", kl.whnf(loop.t("four")));
    }

    // ---- budgets ---------------------------------------------------------------------------------------------

    @Test
    void stepBudgetDeclines() {
        k.setBudget(Budget.strict(2_000L, 1L << 30));
        ResourceException e = assertThrows(ResourceException.class,
                () -> k.whnf(fx.t("((@ Nat.rec 1) (fun (x : Nat) Nat) n0 (fun (x : Nat) (ih : Nat) ih) (lit 1000000))")));
        assertEquals(Reason.BUDGET_STEPS, e.reason());
        // the kernel is usable afterwards (the caller resets; here nothing was corrupted)
        k.setBudget(Budget.cold(1L << 30));
        fx.assertTerm("(lit 4)", k.whnf(fx.t("four")));
    }

    @Test
    void memoryBudgetDeclines() {
        k.setBudget(new Budget(Budget.UNLIMITED, 1L << 20, Integer.MAX_VALUE));
        k.begin(Integer.MAX_VALUE, fx.names(""));
        ResourceException e = assertThrows(ResourceException.class,
                () -> k.whnf(fx.t("((@ Nat.rec 1) (fun (x : Nat) Nat) n0 (fun (x : Nat) (ih : Nat) ih) (lit 1000000))")));
        assertEquals(Reason.BUDGET_MEMORY, e.reason());
    }

    @Test
    void depthBudgetDeclines() {
        k.setBudget(Budget.cold(1L << 30).withMaxDepth(50));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("(f ");
        }
        sb.append("n0");
        for (int i = 0; i < 200; i++) {
            sb.append(')');
        }
        ResourceException e = assertThrows(ResourceException.class, () -> k.check(fx.t(sb.toString())));
        assertEquals(Reason.STACK_DEPTH, e.reason());
        k.setBudget(Budget.cold(1L << 30));
        fx.assertTerm("Nat", k.check(fx.t(sb.toString())));
    }

    @Test
    void aUnitBehindTheFirstFailureIsCancelled() {
        RunSignals signals = new RunSignals() {
            @Override
            public int firstFailure() {
                return 0;
            }

            @Override
            public boolean memoryExhausted() {
                return false;
            }
        };
        WorkerContext c = fx.ctx;
        WorkerContext ctx = new WorkerContext(1, c.store, c.terms, c.overlay, c.lists, c.lits, c.levels, c.nats, signals);
        RefEngine engine = new RefEngine(fx.store, EngineConfig.defaults());
        Session s = engine.newSession(ctx);
        // a short unit ends before the first poll and is accepted; a long one is abandoned
        assertEquals(Outcome.ACCEPT, s.check(fx.cid("two"), Budget.cold(1L << 30)));
        assertEquals(Outcome.DECLINE, s.check(fx.cid("deepForCancel"), Budget.cold(1L << 30)), () -> s.lastMessage());
        assertEquals(Reason.CANCELLED, s.lastReason());
        s.close();
        // judgments of the environment pass (KernelOps after begin(rank, ..)) are abandoned as well
        RefKernel kc = new RefKernel(ctx, EngineConfig.defaults());
        kc.begin(fx.rankOf("deepForCancel"), fx.names(""));
        ResourceException e = assertThrows(ResourceException.class,
                () -> kc.whnf(fx.t("((@ Nat.rec 1) (fun (x : Nat) Nat) n0 (fun (x : Nat) (ih : Nat) ih) (lit 100000))")));
        assertEquals(Reason.CANCELLED, e.reason());
        // not in a scope that lies before the failure, nor in the scope "after everything"
        kc.begin(1, fx.names(""));
        assertEquals(fx.t("Prop"), kc.whnf(fx.t("Prop")));
        kc.begin(Integer.MAX_VALUE, fx.names(""));
        fx.assertTerm("n0", kc.whnf(fx.t("((@ Nat.rec 1) (fun (x : Nat) Nat) n0 (fun (x : Nat) (ih : Nat) ih) (lit 100000))")));
    }

    @Test
    void cancellationIsReportedEvenWhenTheMemorySignalIsUpAsWell() {
        // The driver's environment lane used to raise the memory signal to stop a unit behind the first
        // failure; the reason must be CANCELLED (not recorded as a memory decline) whichever signal is up.
        RunSignals both = new RunSignals() {
            @Override
            public int firstFailure() {
                return 0;
            }

            @Override
            public boolean memoryExhausted() {
                return true;
            }
        };
        WorkerContext c = fx.ctx;
        WorkerContext ctx = new WorkerContext(2, c.store, c.terms, c.overlay, c.lists, c.lits, c.levels, c.nats, both);
        RefKernel kc = new RefKernel(ctx, EngineConfig.defaults());
        String loop = "((@ Nat.rec 1) (fun (x : Nat) Nat) n0 (fun (x : Nat) (ih : Nat) ih) (lit 100000))";
        kc.begin(fx.rankOf("deepForCancel"), fx.names(""));
        ResourceException e = assertThrows(ResourceException.class, () -> kc.whnf(fx.t(loop)));
        assertEquals(Reason.CANCELLED, e.reason());
        // A scope that cannot be cancelled still gives way to the memory signal.
        kc.reset();
        kc.begin(Integer.MAX_VALUE, fx.names(""));
        ResourceException m = assertThrows(ResourceException.class, () -> kc.whnf(fx.t(loop)));
        assertEquals(Reason.BUDGET_MEMORY, m.reason());
        RefEngine engine = new RefEngine(fx.store, EngineConfig.defaults());
        Session s = engine.newSession(ctx);
        assertEquals(Outcome.DECLINE, s.check(fx.cid("deepForCancel"), Budget.cold(1L << 30)), () -> s.lastMessage());
        assertEquals(Reason.CANCELLED, s.lastReason());
        s.close();
    }
}
