package rlvm.ref;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import rlvm.api.EngineConfig;
import rlvm.store.IntLists;

/**
 * Strict mode: the judgments that admission asks through {@code KernelOps} in a scope opened by
 * {@code begin} each start with empty judgment caches, as the official {@code add_inductive} runs
 * every judgment in a fresh {@code type_checker} (finding R2-F3: an admission that keeps the caches
 * answers the parameter comparison of a constructor from the success set its type check left).
 * The practical kernel keeps them (nothing changes there), and so does a strict kernel outside such
 * a scope.
 */
class StrictScopeTest {

    private static RefKernel kernel(Fixture fx, String exact) {
        return new RefKernel(fx.ctx, EngineConfig.defaults(), RefOptions.parse(Map.of("ref.exact", exact)));
    }

    @Test
    void aStrictAdmissionScopeForgetsBetweenJudgments() {
        Fixture fx = Fixture.standard();
        RefKernel k = kernel(fx, "official");
        k.begin(Integer.MAX_VALUE, IntLists.EMPTY);
        int t = fx.t("(Nat.add two two)");
        fx.assertTerm("(lit 4)", k.whnf(t));
        long first = k.steps();
        assertTrue(first > 0);
        fx.assertTerm("(lit 4)", k.whnf(t));
        // computed again: the second judgment did not see the first one's caches
        assertEquals(2 * first, k.steps());
        // a comparison that succeeded is not remembered either: it is made again
        int a = fx.t("(Nat.add two two)");
        int b = fx.t("(lit 4)");
        assertTrue(k.isDefEq(a, b));
        long afterFirst = k.steps();
        assertTrue(k.isDefEq(a, b));
        assertTrue(k.steps() > afterFirst + 1);
    }

    @Test
    void thePracticalKernelKeepsItsCaches() {
        Fixture fx = Fixture.standard();
        RefKernel k = kernel(fx, "off");
        k.begin(Integer.MAX_VALUE, IntLists.EMPTY);
        int t = fx.t("(Nat.add two two)");
        fx.assertTerm("(lit 4)", k.whnf(t));
        long first = k.steps();
        fx.assertTerm("(lit 4)", k.whnf(t));
        assertEquals(first, k.steps());
        int a = fx.t("(Nat.add two two)");
        int b = fx.t("(lit 4)");
        assertTrue(k.isDefEq(a, b));
        long afterFirst = k.steps();
        assertTrue(k.isDefEq(a, b));
        // one step: the look-up in the success set
        assertEquals(afterFirst + 1, k.steps());
    }

    @Test
    void aStrictKernelOutsideAnAdmissionScopeKeepsItsCaches() {
        Fixture fx = Fixture.standard();
        RefKernel k = kernel(fx, "official");
        int t = fx.t("(Nat.add two two)");
        fx.assertTerm("(lit 4)", k.whnf(t));
        long first = k.steps();
        fx.assertTerm("(lit 4)", k.whnf(t));
        assertEquals(first, k.steps());
    }
}
