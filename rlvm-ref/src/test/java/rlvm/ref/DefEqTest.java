package rlvm.ref;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import rlvm.api.Reason;

/** Fixtures for the steps of {@code is_def_eq_core} (research document 02, section 8). */
class DefEqTest {

    private Fixture fx;
    private RefKernel k;

    @BeforeEach
    void setUp() {
        fx = Fixture.standard();
        k = fx.k;
    }

    private void eq(String a, String b) {
        int ta = fx.t(a);
        int tb = fx.t(b);
        assertTrue(k.isDefEq(ta, tb), () -> a + " =?= " + b);
        assertTrue(k.isDefEq(tb, ta), () -> b + " =?= " + a);
    }

    private void ne(String a, String b) {
        int ta = fx.t(a);
        int tb = fx.t(b);
        assertFalse(k.isDefEq(ta, tb), () -> a + " =?= " + b);
        assertFalse(k.isDefEq(tb, ta), () -> b + " =?= " + a);
    }

    // ---- 8.2 quick ---------------------------------------------------------------------------------------

    @Test
    void identicalTerms() {
        eq("(f n0)", "(f n0)");
        eq("Nat", "Nat");
    }

    @Test
    void sortsCompareLevelsSemantically() {
        fx.begin("u v");
        eq("(sort (max u v))", "(sort (max v u))");
        eq("(sort (max u u))", "(sort u)");
        eq("(sort (max u (max u v)))", "(sort (max u v))");
        eq("(sort (imax u 0))", "Prop");
        eq("(sort (imax u u))", "(sort u)");
        eq("(sort (imax 2 1))", "(sort 2)");
        ne("(sort u)", "(sort v)");
        ne("(sort (succ u))", "(sort u)");
        ne("Prop", "Type");
        // no cumulativity
        ne("(sort 1)", "(sort 2)");
    }

    @Test
    void constantLevelsCompareSemantically() {
        fx.begin("u v");
        eq("(@ id (max u v))", "(@ id (max v u))");
        ne("(@ id u)", "(@ id v)");
    }

    @Test
    void bindersCompareDomainsAndBodies() {
        eq("(fun (x : Nat) x)", "(fun (y : NatAlias) y)");
        eq("(pi (x : Nat) ((@ Eq 1) Nat x two))", "(pi (x : NatAlias) ((@ Eq 1) Nat x (lit 2)))");
        ne("(-> Nat Nat)", "(-> Nat Bool)");
        ne("(-> Nat Nat)", "(-> Bool Nat)");
        ne("(fun (x : Nat) x)", "(fun (x : Nat) Nat.zero)");
        ne("(-> Nat Nat)", "(-> Nat Nat Nat)");
        ne("(fun (x : Nat) x)", "(-> Nat Nat)");
    }

    @Test
    void literalsCompareByValue() {
        eq("(lit 2)", "(lit 2)");
        ne("(lit 2)", "(lit 3)");
        eq("(lit 123456789012345678901234567890)", "(lit 123456789012345678901234567890)");
        ne("(lit 123456789012345678901234567890)", "(lit 123456789012345678901234567891)");
        eq("(str \"abc\")", "(str \"abc\")");
        ne("(str \"abc\")", "(str \"abd\")");
        // computed literals against written ones
        eq("(Nat.pow (lit 2) (lit 100))", "(lit 1267650600228229401496703205376)");
        ne("(Nat.pow (lit 2) (lit 100))", "(lit 1267650600228229401496703205377)");
    }

    // ---- step 2: Bool.true ----------------------------------------------------------------------------------

    @Test
    void closedTermAgainstBoolTrueIsEvaluated() {
        eq("(Nat.beq (lit 2) (lit 2))", "Bool.true");
        ne("(Nat.beq (lit 2) (lit 3))", "Bool.true");
        eq("(Nat.ble four (lit 4))", "Bool.true");
        eq("(Nat.beq (lit 2) (lit 3))", "Bool.false");
    }

    // ---- 8.3 proof irrelevance ------------------------------------------------------------------------------

    @Test
    void proofIrrelevance() {
        eq("p1", "p2");
        eq("p1", "pThm");
        // different propositions: a definitive "no"
        ne("p1", "q1");
        fx.bind("h1", k.mkLocal(fx.t("P")));
        fx.bind("h2", k.mkLocal(fx.t("P")));
        eq("$h1", "$h2");
        eq("$h1", "p1");
        // not for data
        ne("n0", "Nat.zero");
        fx.bind("x", k.mkLocal(fx.t("Nat")));
        fx.bind("y", k.mkLocal(fx.t("Nat")));
        ne("$x", "$y");
    }

    @Test
    void proofIrrelevanceThroughATypeThatNeedsWhnf() {
        // h : id Prop P ... the sort of the type is found by whnf
        fx.bind("h1", k.mkLocal(fx.t("((@ id 1) Prop P)")));
        fx.bind("h2", k.mkLocal(fx.t("P")));
        eq("$h1", "$h2");
    }

    @Test
    void proofIrrelevanceUnderBinders() {
        eq("(fun (x : Nat) p1)", "(fun (x : Nat) p2)");
        ne("(fun (x : Nat) n0)", "(fun (x : Nat) Nat.zero)");
    }

    // ---- 8.4 lazy delta ---------------------------------------------------------------------------------------

    @Test
    void deltaUnfolding() {
        eq("two", "(Nat.succ (Nat.succ Nat.zero))");
        eq("twoAlias", "two");
        eq("four", "(lit 4)");
        ne("four", "(lit 5)");
        eq("(Nat.add two two)", "four");
        eq("NatAlias", "Nat");
        eq("((@ id 1) Nat n0)", "n0");
        ne("((@ id 1) Nat n0)", "Nat.zero");
        eq("(f twoAlias)", "(f two)");
    }

    @Test
    void sameHeadComparesArgumentsFirst() {
        eq("(Nat.add n0 two)", "(Nat.add n0 (lit 2))");
        ne("(Nat.add n0 two)", "(Nat.add n0 (lit 3))");
        eq("(Nat.add (f two) n0)", "(Nat.add (f twoAlias) n0)");
    }

    @Test
    void opaqueConstantsDoNotUnfold() {
        ne("opaqueTwo", "two");
        ne("opaqueTwo", "(lit 2)");
        eq("opaqueTwo", "opaqueTwo");
        ne("(Nat.mul n0 (lit 2))", "n0");
    }

    @Test
    void natOffsets() {
        eq("(lit 3)", "(Nat.succ (Nat.succ (Nat.succ Nat.zero)))");
        eq("(lit 0)", "Nat.zero");
        ne("(lit 1)", "Nat.zero");
        ne("(lit 2)", "(Nat.succ (Nat.succ (Nat.succ Nat.zero)))");
        fx.bind("x", k.mkLocal(fx.t("Nat")));
        fx.bind("y", k.mkLocal(fx.t("Nat")));
        eq("(Nat.succ $x)", "(Nat.succ $x)");
        ne("(Nat.succ $x)", "(Nat.succ $y)");
        ne("(lit 5)", "(Nat.succ $x)");
        ne("(Nat.succ $x)", "Nat.zero");
        ne("(Nat.succ $x)", "(lit 0)");
        eq("(Nat.succ (Nat.succ $x))", "(Nat.add $x (lit 2))");
    }

    @Test
    void natFoldingOnClosedTerms() {
        eq("(Nat.add (lit 2) (lit 3))", "(lit 5)");
        eq("(Nat.mul (lit 4) (lit 5))", "(Nat.add (lit 10) (lit 10))");
        ne("(Nat.mul (lit 4) (lit 5))", "(Nat.add (lit 10) (lit 11))");
        eq("(Nat.succ (lit 4))", "(lit 5)");
    }

    // ---- 8.5 projections ------------------------------------------------------------------------------------

    @Test
    void projections() {
        eq("(proj Prod 0 pr)", "(proj Prod 0 pr)");
        ne("(proj Prod 0 pr)", "n0");
        eq("(proj Prod 0 prDef)", "n0");
        eq("(proj Prod 1 prDef)", "Bool.true");
        // same projection of two structures that unfold to each other
        eq("(proj Prod 0 prDef)", "(proj Prod 0 prDef2)");
        eq("(proj Prod 0 pr)", "(proj Prod 0 ((@ id 1) ((@ Prod 0 0) Nat Bool) pr))");
        ne("(proj Prod 1 prDef)", "Bool.false");
    }

    // ---- 8.6 congruence, eta ----------------------------------------------------------------------------------

    @Test
    void applicationCongruence() {
        fx.bind("x", k.mkLocal(fx.t("Nat")));
        fx.bind("y", k.mkLocal(fx.t("Nat")));
        eq("(g $x two)", "(g $x (lit 2))");
        ne("(g $x $y)", "(g $y $x)");
        ne("(g $x)", "(g $x $y)");
        ne("(f $x)", "(g $x $x)");
    }

    @Test
    void functionEta() {
        eq("f", "(fun (x : Nat) (f x))");
        eq("g", "(fun (x : Nat) (y : Nat) (g x y))");
        eq("(g n0)", "(fun (y : Nat) (g n0 y))");
        ne("f", "(fun (x : Nat) (f Nat.zero))");
        ne("f", "(fun (x : Nat) x)");
        // the other side's type needs whnf to become a Pi
        eq("fa", "(fun (x : Nat) (fa x))");
    }

    @Test
    void structureEta() {
        eq("pr", "((@ Prod.mk 0 0) Nat Bool (proj Prod 0 pr) (proj Prod 1 pr))");
        ne("pr", "((@ Prod.mk 0 0) Nat Bool (proj Prod 0 pr) Bool.true)");
        fx.bind("p", k.mkLocal(fx.t("((@ Prod 0 0) Nat Bool)")));
        eq("$p", "((@ Prod.mk 0 0) Nat Bool (proj Prod 0 $p) (proj Prod 1 $p))");
        // a partially applied constructor is not eta-expanded
        ne("((@ Prod.mk 0 0) Nat Bool n0)", "(fun (b : Bool) pr)");
    }

    @Test
    void unitLikeTypes() {
        eq("u0", "u1");
        eq("u0", "Unit.unit");
        fx.bind("a", k.mkLocal(fx.t("Unit")));
        fx.bind("b", k.mkLocal(fx.t("Unit")));
        eq("$a", "$b");
        // an indexed family is not unit-like
        ne("iu0", "iu1");
        fx.bind("i", k.mkLocal(fx.t("(IUnit Nat.zero)")));
        fx.bind("j", k.mkLocal(fx.t("(IUnit Nat.zero)")));
        ne("$i", "$j");
    }

    // ---- 8.7 string literals ----------------------------------------------------------------------------------

    @Test
    void stringLiteralExpansion() {
        String a = "(Char.ofNat (lit 97))";
        String b = "(Char.ofNat (lit 98))";
        String nil = "((@ List.nil 0) Char)";
        eq("(str \"a\")", "(String.ofList ((@ List.cons 0) Char " + a + " " + nil + "))");
        eq("(str \"ab\")", "(String.ofList ((@ List.cons 0) Char " + a + " ((@ List.cons 0) Char " + b + " " + nil + ")))");
        ne("(str \"a\")", "(String.ofList ((@ List.cons 0) Char " + b + " " + nil + "))");
        ne("(str \"a\")", "(String.ofList " + nil + ")");
        eq("(str \"\")", "(String.ofList " + nil + ")");
        // one element per Unicode scalar value, not per byte: U+00E9 is 233
        eq("(str \"é\")", "(String.ofList ((@ List.cons 0) Char (Char.ofNat (lit 233)) " + nil + "))");
    }

    // ---- let, caches ------------------------------------------------------------------------------------------

    @Test
    void letIsReducedBySubstitution() {
        eq("(let (x : Nat := two) (Nat.succ x))", "(lit 3)");
        eq("(let (x : Nat := n0) (f x))", "(f n0)");
    }

    @Test
    void failuresAreNotCachedAsFacts() {
        // The same pair asked twice gives the same answer; an intervening success of a related pair
        // must not change it (no transitive closure).
        ne("(f Nat.zero)", "(f n0)");
        eq("(f two)", "(f (lit 2))");
        ne("(f Nat.zero)", "(f n0)");
    }

    @Test
    void errorsInsideConversionPropagate() {
        // proof irrelevance infers the type of the term: a non-function applied is an error, not "false"
        fx.rejects(Reason.FUNCTION_EXPECTED, () -> k.isDefEq(fx.t("(Nat.zero Nat.zero)"), fx.t("n0")));
    }

    @Test
    void eagerReduceLiftsTheClosedTermGuardOfNatFolding() {
        // Under the binder x, `Nat.mul ((fun y => 2) x) 3` mentions a free variable. Nat.mul is opaque in
        // this fixture, so the equation with 6 holds only by folding the literal arguments, which
        // is_def_eq does on open terms only while it compares the type of an `eagerReduce _ _` argument.
        String domain = "((@ Eq 1) Nat (Nat.mul ((fun (y : Nat) (lit 2)) x) (lit 3)) (lit 6))";
        String proofType = "((@ Eq 1) Nat (lit 6) (lit 6))";
        String proof = "((@ Eq.refl 1) Nat (lit 6))";
        fx.assertTerm("(-> Nat Nat)", k.check(fx.t("(fun (x : Nat) ((fun (h : " + domain + ") Nat.zero) ((@ eagerReduce 0) "
                + proofType + " " + proof + ")))")));
        fx.rejects(Reason.APP_TYPE_MISMATCH,
                () -> k.check(fx.t("(fun (x : Nat) ((fun (h : " + domain + ") Nat.zero) " + proof + "))")));
        // on a closed term the folding happens anyway
        fx.assertTerm("Nat", k.check(fx.t("((fun (h : ((@ Eq 1) Nat (Nat.mul (lit 2) (lit 3)) (lit 6))) Nat.zero) " + proof + ")")));
    }

    @Test
    void eagerReduceArgumentsAreChecked() {
        fx.assertTerm("Nat", k.check(fx.t("((fun (x : Nat) x) ((@ eagerReduce 1) Nat (Nat.add two two)))")));
        assertEquals(fx.t("Nat"), k.check(fx.t("((@ eagerReduce 1) Nat (lit 3))")));
    }
}
