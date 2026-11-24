package rlvm.ref;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The {@code m_whnf_core} entry that follows an unfolding. Officially {@code unfold_definition} builds
 * the term {@code value a1 .. an} and {@code whnf_core} looks that term up and stores under it; the
 * kernel does not build it when the value is a lambda and keeps the entry under {@code c a1 .. an}.
 * The two must be ONE table: another constant with the same value, and the explicit redex
 * {@code value a1 .. an}, find the entry (docs/design/review/reference-caches.md, D4).
 */
class UnfoldKeyTest {

    private static final String BODY = "(fun (x : Nat) (g x two))";
    /** {@link RefKernel#retention}: entries of whnfCoreCache and of unfoldWhnfCache. */
    private static final int WHNF_CORE = 5;
    private static final int UNFOLD_WHNF = 7;

    private static Fixture fixture() {
        return Fixture.standard(fx -> {
            fx.def("c1", "", "(-> Nat Nat)", BODY, Fixture.regular(3));
            fx.def("c2", "", "(-> Nat Nat)", BODY, Fixture.regular(3));
        });
    }

    @Test
    void anotherConstantWithTheSameValueFindsTheEntry() {
        Fixture fx = fixture();
        RefKernel k = fx.k;
        fx.assertTerm("(g n0 two)", k.whnf(fx.t("(c1 n0)")));
        long entries = k.retention(0)[UNFOLD_WHNF];
        assertEquals(1, entries);
        fx.assertTerm("(g n0 two)", k.whnf(fx.t("(c2 n0)")));
        // officially both unfold to the term `(fun x => g x two) n0`: one key, one entry
        assertEquals(entries, k.retention(0)[UNFOLD_WHNF]);
        assertTrue(k.isDefEq(fx.t("(c1 n0)"), fx.t("(c2 n0)")));
        assertEquals(entries, k.retention(0)[UNFOLD_WHNF]);
    }

    @Test
    void theExplicitRedexFindsTheEntryOfTheUnfolding() {
        Fixture fx = fixture();
        RefKernel k = fx.k;
        fx.assertTerm("(g n0 two)", k.whnf(fx.t("(c1 n0)")));
        long core = k.retention(0)[WHNF_CORE];
        fx.assertTerm("(g n0 two)", k.whnfCore(fx.t("(" + BODY + " n0)")));
        // the redex is the very term the unfolding stood for: answered, nothing computed or stored
        assertEquals(core, k.retention(0)[WHNF_CORE]);
    }

    @Test
    void theUnfoldingFindsTheEntryOfTheExplicitRedex() {
        Fixture fx = fixture();
        RefKernel k = fx.k;
        fx.assertTerm("(g n0 two)", k.whnfCore(fx.t("(" + BODY + " n0)")));
        long core = k.retention(0)[WHNF_CORE];
        assertEquals(1, core);
        fx.assertTerm("(g n0 two)", k.whnf(fx.t("(c1 n0)")));
        fx.assertTerm("(g n0 two)", k.whnf(fx.t("(c2 n0)")));
        // both unfoldings were answered by the entry of the explicit term
        assertEquals(0, k.retention(0)[UNFOLD_WHNF]);
        assertEquals(core, k.retention(0)[WHNF_CORE]);
    }

    @Test
    void differentArgumentsOrValuesDoNotShare() {
        Fixture fx = Fixture.standard(f -> {
            f.def("c1", "", "(-> Nat Nat)", BODY, Fixture.regular(3));
            f.def("c3", "", "(-> Nat Nat)", "(fun (x : Nat) (g x four))", Fixture.regular(3));
        });
        RefKernel k = fx.k;
        fx.assertTerm("(g n0 two)", k.whnf(fx.t("(c1 n0)")));
        fx.assertTerm("(g (f n0) two)", k.whnf(fx.t("(c1 (f n0))")));
        fx.assertTerm("(g n0 four)", k.whnf(fx.t("(c3 n0)")));
        assertEquals(3, k.retention(0)[UNFOLD_WHNF]);
    }
}
