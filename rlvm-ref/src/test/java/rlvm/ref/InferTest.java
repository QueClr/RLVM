package rlvm.ref;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import rlvm.api.Reason;

/** One fixture per inference rule of research document 02, section 6. */
class InferTest {

    private Fixture fx;
    private RefKernel k;

    @BeforeEach
    void setUp() {
        fx = Fixture.standard();
        k = fx.k;
    }

    private void infers(String expected, String term) {
        fx.assertTerm(expected, k.infer(fx.t(term)));
    }

    private void checks(String expected, String term) {
        fx.assertTerm(expected, k.check(fx.t(term)));
    }

    // ---- 6.1 sort ------------------------------------------------------------------------------------

    @Test
    void sortHasSuccessorSort() {
        infers("(sort 1)", "Prop");
        infers("(sort 2)", "Type");
        fx.begin("u");
        infers("(sort (succ u))", "(sort u)");
        // Succ is not simplified, the level is kept as written.
        infers("(sort (succ (max u 0)))", "(sort (max u 0))");
    }

    @Test
    void sortChecksUniverseParametersInCheckModeOnly() {
        fx.begin("u");
        checks("(sort (succ u))", "(sort u)");
        fx.rejects(Reason.UNDECLARED_LEVEL_PARAM, () -> k.check(fx.t("(sort v)")));
        infers("(sort (succ v))", "(sort v)");
    }

    // ---- 6.2 const -----------------------------------------------------------------------------------

    @Test
    void constantTypeIsInstantiated() {
        infers("Type", "Nat");
        infers("(-> Nat Nat)", "Nat.succ");
        infers("(pi (α : (sort 2)) (a : α) α)", "(@ id 2)");
        infers("(pi (α : Type) (a : α) (b : α) Prop)", "(@ Eq 1)");
        fx.begin("w");
        infers("(pi (α : (sort (succ w))) (a : α) α)", "(@ id (succ w))");
    }

    @Test
    void constantInstantiationSimplifiesLevels() {
        // Prod.{u,v} : Type u -> Type v -> Type (max u v); max 0 0 simplifies to 0 (mk_max).
        infers("(pi (α : Type) (β : Type) Type)", "(@ Prod 0 0)");
        infers("(pi (α : (sort 2)) (β : Type) (sort 2))", "(@ Prod 1 0)");
    }

    @Test
    void constantLevelArityIsCheckedInBothModes() {
        fx.rejects(Reason.LEVEL_ARITY_MISMATCH, () -> k.infer(fx.t("(@ id)")));
        fx.rejects(Reason.LEVEL_ARITY_MISMATCH, () -> k.check(fx.t("(@ id 0 0)")));
        fx.rejects(Reason.LEVEL_ARITY_MISMATCH, () -> k.infer(fx.t("(@ Nat 0)")));
    }

    @Test
    void constantLevelsMustBeDeclaredInCheckMode() {
        fx.begin("u");
        fx.rejects(Reason.UNDECLARED_LEVEL_PARAM, () -> k.check(fx.t("(@ id v)")));
        checks("(pi (α : (sort u)) (a : α) α)", "(@ id u)");
    }

    @Test
    void constantOfLaterRankIsUnknown() {
        int two = fx.t("two");
        k.begin(fx.rankOf("two"), fx.names(""));
        fx.rejects(Reason.UNKNOWN_CONSTANT, () -> k.infer(two));
        fx.rejects(Reason.UNKNOWN_CONSTANT, () -> k.check(fx.t("(Nat.succ two)")));
        k.begin(fx.rankOf("two") + 1, fx.names(""));
        fx.assertTerm("Nat", k.infer(two));
    }

    // ---- 6.3 lambda ----------------------------------------------------------------------------------

    @Test
    void lambdaTelescope() {
        infers("(-> Nat Nat)", "(fun (x : Nat) x)");
        infers("(pi (α : Type) (a : α) α)", "(fun (α : Type) (a : α) a)");
        infers("(pi (x : Nat) (y : Bool) ((@ Eq 1) Nat x x))", "(fun (x : Nat) (y : Bool) ((@ Eq.refl 1) Nat x))");
        checks("(pi (α : Type) (a : α) (b : α) α)", "(fun (α : Type) (a : α) (b : α) a)");
    }

    @Test
    void lambdaDomainMustBeATypeInCheckMode() {
        fx.rejects(Reason.TYPE_EXPECTED, () -> k.check(fx.t("(fun (x : Nat.zero) x)")));
        // infer-only does not look
        infers("(-> Nat.zero Nat.zero)", "(fun (x : Nat.zero) x)");
    }

    @Test
    void lambdaDomainMayNeedWhnf() {
        checks("(-> NatAlias NatAlias)", "(fun (x : NatAlias) x)");
        checks("(-> ((@ id 2) Type Nat) Nat)", "(fun (x : ((@ id 2) Type Nat)) Nat.zero)");
    }

    // ---- 6.4 pi --------------------------------------------------------------------------------------

    @Test
    void piSortIsImaxOfDomainSorts() {
        infers("Type", "(-> Nat Nat)");
        infers("(sort 2)", "(-> Nat Type)");
        infers("(sort 2)", "(-> Type Nat)");
        // imax 1 0 = 0: a proposition
        infers("Prop", "(pi (x : Nat) ((@ Eq 1) Nat x x))");
        infers("Prop", "(-> Nat Nat P)");
        fx.begin("u v");
        infers("(sort (imax u v))", "(-> (@ PU u) (@ PU v))");
        infers("(sort u)", "(-> (@ PU u) (@ PU u))");
        infers("(sort (max u (succ v)))", "(-> (@ PU u) (@ PU (succ v)))");
        infers("Prop", "(-> (@ PU u) P)");
    }

    @Test
    void piDomainAndBodyMustBeTypes() {
        fx.rejects(Reason.TYPE_EXPECTED, () -> k.check(fx.t("(-> Nat.zero Nat)")));
        fx.rejects(Reason.TYPE_EXPECTED, () -> k.check(fx.t("(-> Nat Nat.zero)")));
        // also in infer-only mode: the sorts are needed
        fx.rejects(Reason.TYPE_EXPECTED, () -> k.infer(fx.t("(-> Nat Nat.zero)")));
    }

    // ---- 6.5 app -------------------------------------------------------------------------------------

    @Test
    void applicationInstantiatesTheCodomain() {
        checks("Nat", "(Nat.succ Nat.zero)");
        checks("((@ Eq 1) Nat Nat.zero Nat.zero)", "((@ Eq.refl 1) Nat Nat.zero)");
        checks("Nat", "((@ id 1) Nat (Nat.succ Nat.zero))");
        infers("Nat", "((@ id 1) Nat (Nat.succ Nat.zero))");
        checks("(-> Nat Nat)", "(g Nat.zero)");
    }

    @Test
    void applicationChecksTheArgumentType() {
        fx.rejects(Reason.APP_TYPE_MISMATCH, () -> k.check(fx.t("(Nat.succ Bool.true)")));
        fx.rejects(Reason.APP_TYPE_MISMATCH, () -> k.check(fx.t("((@ id 1) Nat Bool.true)")));
        fx.rejects(Reason.FUNCTION_EXPECTED, () -> k.check(fx.t("(Nat.zero Nat.zero)")));
        // infer-only walks the spine without comparing anything
        infers("Nat", "(Nat.succ Bool.true)");
        fx.rejects(Reason.FUNCTION_EXPECTED, () -> k.infer(fx.t("(Nat.zero Nat.zero)")));
    }

    @Test
    void applicationArgumentTypeUpToConversion() {
        checks("Nat", "(Nat.succ ((@ id 1) Nat two))");
        checks("NatAlias", "((fun (x : NatAlias) x) Nat.zero)");
        // the function type needs whnf to become a Pi
        checks("Nat", "(fa Nat.zero)");
        infers("Nat", "(fa Nat.zero)");
    }

    @Test
    void applicationPrefixesGetTheirTypesWhenAskedFor() {
        // The whole application first: the types of its prefixes are determined but not built.
        checks("((@ Prod 0 0) Nat Bool)", "((@ Prod.mk 0 0) Nat Bool n0 Bool.true)");
        // Each prefix on its own afterwards (the official kernel finds them in its cache).
        checks("(pi (fst : Nat) (snd : Bool) ((@ Prod 0 0) Nat Bool))", "((@ Prod.mk 0 0) Nat Bool)");
        checks("(pi (β : Type) (fst : Nat) (snd : β) ((@ Prod 0 0) Nat β))", "((@ Prod.mk 0 0) Nat)");
        checks("(pi (snd : Bool) ((@ Prod 0 0) Nat Bool))", "((@ Prod.mk 0 0) Nat Bool n0)");
        // Another application that continues a prefix whose type is still pending.
        fx.begin("");
        checks("((@ Prod 0 0) Nat Bool)", "((@ Prod.mk 0 0) Nat Bool n0 Bool.true)");
        checks("((@ Prod 0 0) Nat Bool)", "((@ Prod.mk 0 0) Nat Bool n0 Bool.false)");
        checks("((@ Prod 0 0) Nat Bool)", "((@ Prod.mk 0 0) Nat Bool (f n0) Bool.false)");
        checks("((@ Prod 0 0) Nat Nat)", "((@ Prod.mk 0 0) Nat Nat (f n0) n0)");
        fx.rejects(Reason.APP_TYPE_MISMATCH, () -> k.check(fx.t("((@ Prod.mk 0 0) Nat Bool n0 n0)")));
        fx.rejects(Reason.APP_TYPE_MISMATCH, () -> k.check(fx.t("((@ Prod.mk 0 0) Nat Bool Bool.true Bool.true)")));
        // Both modes give the same term, prefix by prefix, in either order.
        String[] terms = {
            "((@ Prod.mk 0 0) Nat Bool n0 Bool.true)", "((@ Prod.mk 0 0) Nat Bool n0)", "((@ Prod.mk 0 0) Nat Bool)",
            "((@ Prod.mk 0 0) Nat)", "((@ Eq.refl 1) Nat (f n0))", "((@ Eq.refl 1) Nat)", "((@ Eq 1) Nat (f n0))",
            "((@ id 1) (-> Nat Nat) f n0)", "((@ id 1) (-> Nat Nat) f)", "((@ id 1) (-> Nat Nat))",
            "((@ Exists.intro 1) Nat pred)", "((@ Quot.mk 1) Nat r n0)", "(Sig.mk B)", "(hr n0 (f n0))",
        };
        for (int round = 0; round < 2; round++) {
            fx.begin("");
            for (int i = 0; i < terms.length; i++) {
                String t = terms[round == 0 ? i : terms.length - 1 - i];
                assertEquals(k.infer(fx.t(t)), k.check(fx.t(t)), t);
            }
        }
    }

    @Test
    void applicationWhoseFunctionTypeBecomesAPiOnlyAfterSubstitution() {
        // id (Nat → Nat) f n0: after two arguments the remaining type is the bound variable α itself.
        checks("Nat", "((@ id 1) (-> Nat Nat) f n0)");
        checks("(-> Nat Nat)", "((@ id 1) (-> Nat Nat) f)");
        // ... and when it needs whnf after the substitution: id FnAlias fa n0
        checks("Nat", "((@ id 1) FnAlias fa n0)");
        checks("FnAlias", "((@ id 1) FnAlias fa)");
        fx.rejects(Reason.FUNCTION_EXPECTED, () -> k.check(fx.t("((@ id 1) Nat n0 n0)")));
        fx.rejects(Reason.APP_TYPE_MISMATCH, () -> k.check(fx.t("((@ id 1) (-> Nat Nat) f Bool.true)")));
        // The prefix types that were pending while the error was found are still right.
        checks("(-> Nat Nat)", "((@ id 1) (-> Nat Nat) f)");
        checks("(pi (a : Nat) Nat)", "((@ id 1) Nat)");
    }

    @Test
    void applicationInferOnlyInstantiatesInGroups() {
        // id (Nat -> Nat) Nat.succ Nat.zero : the type of `id _ _` is not syntactically a Pi after two
        // arguments have been consumed.
        infers("Nat", "((@ id 1) (-> Nat Nat) Nat.succ Nat.zero)");
        checks("Nat", "((@ id 1) (-> Nat Nat) Nat.succ Nat.zero)");
    }

    // ---- 6.6 let -------------------------------------------------------------------------------------

    @Test
    void letTypeDropsUnusedLets() {
        checks("Nat", "(let (x : Nat := Nat.zero) (Nat.succ x))");
        infers("Nat", "(let (x : Nat := Nat.zero) (Nat.succ x))");
    }

    @Test
    void letTypeKeepsUsedLets() {
        checks("(let (x : Nat := Nat.zero) ((@ Eq 1) Nat x x))",
                "(let (x : Nat := Nat.zero) ((@ Eq.refl 1) Nat x))");
    }

    @Test
    void letValueMustHaveTheDeclaredType() {
        fx.rejects(Reason.LET_TYPE_MISMATCH, () -> k.check(fx.t("(let (x : Nat := Bool.true) Nat.zero)")));
        fx.rejects(Reason.TYPE_EXPECTED, () -> k.check(fx.t("(let (x : Nat.zero := Nat.zero) Nat.zero)")));
        infers("Nat", "(let (x : Nat := Bool.true) Nat.zero)");
    }

    @Test
    void letBodySeesTheValue() {
        // The body is only well typed because x reduces to Nat.zero (zeta through the let variable).
        checks("(let (x : Nat := Nat.zero) ((@ Eq 1) Nat x Nat.zero))",
                "(let (x : Nat := Nat.zero) ((fun (h : ((@ Eq 1) Nat x Nat.zero)) h) ((@ Eq.refl 1) Nat Nat.zero)))");
    }

    // ---- 6.9 literals --------------------------------------------------------------------------------

    @Test
    void literals() {
        checks("Nat", "(lit 5)");
        checks("Nat", "(lit 123456789012345678901234567890)");
        checks("String", "(str \"abc\")");
        checks("Nat", "(Nat.succ (lit 5))");
    }

    // ---- 6.8 projections -----------------------------------------------------------------------------

    @Test
    void projectionOfAStructure() {
        checks("Nat", "(proj Prod 0 pr)");
        checks("Bool", "(proj Prod 1 pr)");
        checks("Nat", "(proj Prod 0 ((@ Prod.mk 0 0) Nat Bool Nat.zero Bool.true))");
        infers("Bool", "(proj Prod 1 pr)");
    }

    @Test
    void projectionWithDependentField() {
        checks("Nat", "(proj Sig 0 sg)");
        // the second field's type mentions the first field as a projection
        checks("(B (proj Sig 0 sg))", "(proj Sig 1 sg)");
    }

    @Test
    void projectionRestrictions() {
        fx.rejects(Reason.INVALID_PROJECTION, () -> k.check(fx.t("(proj Prod 2 pr)")));
        fx.rejects(Reason.INVALID_PROJECTION, () -> k.check(fx.t("(proj Nat 0 Nat.zero)")));
        fx.rejects(Reason.INVALID_PROJECTION, () -> k.check(fx.t("(proj Bool 0 Bool.true)")));
        // structure name must be the head of the type
        fx.rejects(Reason.INVALID_PROJECTION, () -> k.check(fx.t("(proj Sig 0 pr)")));
        // the structure expression itself is checked in check mode
        fx.rejects(Reason.APP_TYPE_MISMATCH, () -> k.check(
                fx.t("(proj Prod 0 ((@ Prod.mk 0 0) Nat Bool Bool.true Bool.true))")));
    }

    @Test
    void projectionOutOfAProposition() {
        checks("P", "(proj And 0 andPQ)");
        checks("Q", "(proj And 1 andPQ)");
        // the witness is data: it may not leave the proof
        fx.rejects(Reason.INVALID_PROJECTION, () -> k.check(fx.t("(proj Exists 0 ex)")));
        // the proof field depends on the witness, which is data
        fx.rejects(Reason.INVALID_PROJECTION, () -> k.check(fx.t("(proj Exists 1 ex)")));
        fx.rejects(Reason.INVALID_PROJECTION, () -> k.infer(fx.t("(proj Exists 1 ex)")));
    }

    // ---- loose bound variables, free variables ---------------------------------------------------------

    @Test
    void looseBoundVariablesAreRejected() {
        fx.rejects(Reason.LOOSE_BVAR, () -> k.infer(fx.t("#0")));
        fx.rejects(Reason.LOOSE_BVAR, () -> k.check(fx.t("(Nat.succ #3)")));
    }

    @Test
    void freeVariables() {
        int x = k.mkLocal(fx.t("Nat"));
        fx.bind("x", x);
        fx.assertTerm("Nat", k.infer(x));
        fx.assertTerm("Nat", k.check(fx.t("(Nat.succ $x)")));
        fx.assertTerm("((@ Eq 1) Nat $x $x)", k.check(fx.t("((@ Eq.refl 1) Nat $x)")));
        // lambda over a term with a free variable
        fx.assertTerm("(-> Nat ((@ Eq 1) Nat $x $x))", k.check(fx.t("(fun (y : Nat) ((@ Eq.refl 1) Nat $x))")));
    }

    // ---- ensureSort / ensurePi / isProp ----------------------------------------------------------------

    @Test
    void ensureSortAndPi() {
        assertEquals(fx.level("1"), k.ensureSort(fx.t("Type")));
        assertEquals(fx.level("1"), k.ensureSort(fx.t("((@ id 3) (sort 2) Type)")));
        fx.rejects(Reason.TYPE_EXPECTED, () -> k.ensureSort(fx.t("Nat")));
        int pi = fx.t("(-> Nat Nat)");
        assertEquals(pi, k.ensurePi(pi));
        fx.assertTerm("(-> Nat Nat)", k.ensurePi(fx.t("FnAlias")));
        fx.rejects(Reason.FUNCTION_EXPECTED, () -> k.ensurePi(fx.t("Nat")));
    }

    @Test
    void isPropNormalisesTheLevelAndThrowsOnAStuckSort() {
        assertTrue(k.isProp(fx.t("P")));
        assertTrue(k.isProp(fx.t("((@ Eq 1) Nat Nat.zero Nat.zero)")));
        assertFalse(k.isProp(fx.t("Nat")));
        assertFalse(k.isProp(fx.t("Prop")));
        // Sort (imax 1 0) is Prop
        assertTrue(k.isProp(fx.t("PI")));
        // a bare parameter is not known to be zero
        fx.begin("u");
        assertFalse(k.isProp(fx.t("(@ PU u)")));
        // the type of the type does not reduce to a sort: an error, never "false"
        fx.rejects(Reason.TYPE_EXPECTED, () -> k.isProp(fx.t("Nat.zero")));
    }
}
