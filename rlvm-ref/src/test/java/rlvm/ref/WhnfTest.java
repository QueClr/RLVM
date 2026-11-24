package rlvm.ref;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import rlvm.api.Reason;

/** One fixture per reduction rule of research document 02, section 7. */
class WhnfTest {

    private static final String M = "(fun (x : Nat) Nat)";
    private static final String S = "(fun (x : Nat) (ih : Nat) (f ih))";
    private static final String REC = "((@ Nat.rec 1) " + M + " n0 " + S;

    private Fixture fx;
    private RefKernel k;

    @BeforeEach
    void setUp() {
        fx = Fixture.standard();
        k = fx.k;
    }

    private void whnfs(String expected, String term) {
        fx.assertTerm(expected, k.whnf(fx.t(term)));
    }

    private void cores(String expected, String term) {
        fx.assertTerm(expected, k.whnfCore(fx.t(term)));
    }

    /** The term is in normal form: the SAME id comes back. */
    private void stuck(String term) {
        int t = fx.t(term);
        assertEquals(t, k.whnf(t), () -> fx.show(k.whnf(t)));
        assertEquals(t, k.whnfCore(t));
    }

    // ---- beta, zeta ------------------------------------------------------------------------------------

    @Test
    void beta() {
        cores("Nat.zero", "((fun (x : Nat) x) Nat.zero)");
        cores("(f Nat.zero)", "((fun (h : (-> Nat Nat)) (x : Nat) (h x)) f Nat.zero)");
        // more arguments than lambdas: the rest is re-applied, and reduction continues
        cores("(g Nat.zero n0)", "((fun (h : (-> Nat Nat Nat)) h) g Nat.zero n0)");
        cores("n0", "((fun (h : (-> Nat Nat)) h) (fun (x : Nat) x) n0)");
        // fewer arguments than lambdas
        cores("(fun (y : Nat) (g n0 y))", "((fun (x : Nat) (y : Nat) (g x y)) n0)");
        // nothing under binders or in arguments
        stuck("(fun (x : Nat) ((fun (y : Nat) y) x))");
        stuck("(f ((fun (y : Nat) y) n0))");
    }

    @Test
    void zeta() {
        cores("(Nat.succ Nat.zero)", "(let (x : Nat := Nat.zero) (Nat.succ x))");
        cores("(f n0)", "(let (x : Nat := n0) (let (y : Nat := x) (f y)))");
        cores("(g n0 n0)", "((let (x : Nat := n0) (g x)) n0)");
    }

    @Test
    void normalFormsComeBackUnchanged() {
        stuck("Nat");
        stuck("(sort 3)");
        stuck("(-> Nat Nat)");
        stuck("(fun (x : Nat) x)");
        stuck("n0");
        stuck("(f n0)");
        stuck("(lit 7)");
        stuck("(str \"abc\")");
        stuck("Nat.zero");
        stuck("opaqueTwo");
    }

    // ---- delta -----------------------------------------------------------------------------------------

    @Test
    void deltaOnlyInWhnf() {
        whnfs("Nat", "NatAlias");
        cores("NatAlias", "NatAlias");
        whnfs("(fun (α : Type) (a : α) a)", "(@ id 1)");
        whnfs("n0", "((@ id 1) Nat n0)");
        cores("((@ id 1) Nat n0)", "((@ id 1) Nat n0)");
        whnfs("(f n0)", "((@ id 1) Nat (f n0))");
        whnfs("(-> Nat Nat)", "FnAlias");
    }

    @Test
    void theoremsUnfoldOpaquesAndAxiomsDoNot() {
        whnfs("p1", "pThm");
        stuck("opaqueTwo");
        stuck("p1");
    }

    @Test
    void wrongLevelArityDoesNotUnfold() {
        stuck("(@ id)");
        stuck("((@ id 1 1) Nat n0)");
    }

    // ---- iota ------------------------------------------------------------------------------------------

    @Test
    void iotaOnConstructors() {
        cores("n0", REC + " Nat.zero)");
        cores("(f " + REC + " n0))", REC + " (Nat.succ n0))");
        stuck(REC + " n0)");
        // major premise missing
        stuck(REC + ")");
    }

    @Test
    void iotaOnLiteralsOneLayer() {
        cores("n0", REC + " (lit 0))");
        cores("(f " + REC + " (lit 1)))", REC + " (lit 2))");
        // the major is normalised with full whnf: two -> Nat.succ (Nat.succ Nat.zero) -> literal 2
        cores("(f " + REC + " (lit 1)))", REC + " two)");
        whnfs("(f " + REC + " (lit 1)))", REC + " two)");
    }

    @Test
    void iotaReappliesExtraArguments() {
        cores("(f n0)",
                "((@ Nat.rec 1) (fun (x : Nat) (-> Nat Nat)) f (fun (x : Nat) (ih : (-> Nat Nat)) ih) Nat.zero n0)");
        cores("(f n0)",
                "((@ Nat.rec 1) (fun (x : Nat) (-> Nat Nat)) f (fun (x : Nat) (ih : (-> Nat Nat)) ih) (lit 3) n0)");
    }

    @Test
    void iotaNeedsTheRightNumberOfLevels() {
        stuck("((@ Nat.rec) " + M + " n0 " + S + " Nat.zero)");
        stuck("((@ Nat.rec 1 1) " + M + " n0 " + S + " Nat.zero)");
    }

    @Test
    void recursorKLike() {
        String motive = "(fun (b : Nat) (h : ((@ Eq 1) Nat n0 b)) Nat)";
        // eqn : Eq n0 n0 is an axiom, not a constructor application: K replaces it by Eq.refl n0.
        cores("Nat.zero", "((@ Eq.rec 1 1) Nat n0 " + motive + " Nat.zero n0 eqn)");
        // also for a free variable
        int h = k.mkLocal(fx.t("((@ Eq 1) Nat n0 n0)"));
        fx.bind("h", h);
        cores("Nat.zero", "((@ Eq.rec 1 1) Nat n0 " + motive + " Nat.zero n0 $h)");
        // and of course for the constructor itself
        cores("Nat.zero", "((@ Eq.rec 1 1) Nat n0 " + motive + " Nat.zero n0 ((@ Eq.refl 1) Nat n0))");
    }

    @Test
    void recursorKNeedsDefinitionallyEqualIndices() {
        String motive = "(fun (b : Nat) (h : ((@ Eq 1) Nat n0 b)) Nat)";
        // eqn2 : Eq n0 (f n0): the type of Eq.refl n0 is not the major's type.
        stuck("((@ Eq.rec 1 1) Nat n0 " + motive + " Nat.zero (f n0) eqn2)");
    }

    @Test
    void structureEtaOnTheMajorPremise() {
        String rec = "((@ Prod.rec 1 0 0) Nat Bool (fun (t : ((@ Prod 0 0) Nat Bool)) Nat) (fun (a : Nat) (b : Bool) a)";
        cores("n0", rec + " ((@ Prod.mk 0 0) Nat Bool n0 Bool.true))");
        // pr is an axiom: it is expanded to Prod.mk pr.0 pr.1, the result is the stuck projection
        cores("(proj Prod 0 pr)", rec + " pr)");
        // through delta: prDef unfolds to a constructor application
        cores("n0", rec + " prDef)");
    }

    @Test
    void noStructureEtaForProofs() {
        stuck("((@ And.rec 1) P Q (fun (t : (And P Q)) Nat) (fun (l : P) (r : Q) Nat.zero) andPQ)");
        cores("Nat.zero",
                "((@ And.rec 1) P Q (fun (t : (And P Q)) Nat) (fun (l : P) (r : Q) Nat.zero) (And.intro P Q p1 q1))");
    }

    // ---- projections -----------------------------------------------------------------------------------

    @Test
    void projectionOfAConstructor() {
        cores("n0", "(proj Prod 0 ((@ Prod.mk 0 0) Nat Bool n0 Bool.true))");
        cores("Bool.true", "(proj Prod 1 ((@ Prod.mk 0 0) Nat Bool n0 Bool.true))");
        // the structure is normalised with full whnf
        cores("Bool.true", "(proj Prod 1 prDef)");
        cores("n0", "(proj Prod 0 prDef2)");
        // result is reduced further, applied projections too
        cores("(f n0)", "((proj Prod 0 ((@ Prod.mk 0 0) (-> Nat Nat) Bool f Bool.true)) n0)");
    }

    @Test
    void stuckProjectionKeepsTheOriginalTerm() {
        stuck("(proj Prod 0 pr)");
        // the structure is NOT replaced by its normal form
        stuck("(proj Prod 0 ((@ id 1) ((@ Prod 0 0) Nat Bool) pr))");
        // out of range, wrong structure
        stuck("(proj Prod 2 ((@ Prod.mk 0 0) Nat Bool n0 Bool.true))");
        stuck("(proj Sig 0 ((@ Prod.mk 0 0) Nat Bool n0 Bool.true))");
    }

    // ---- quotients -------------------------------------------------------------------------------------

    @Test
    void quotLift() {
        cores("(f n0)", "((@ Quot.lift 1 1) Nat r Nat f hr ((@ Quot.mk 1) Nat r n0))");
        // extra arguments are re-applied
        cores("(g n0 Nat.zero)", "((@ Quot.lift 1 1) Nat r (-> Nat Nat) g hr ((@ Quot.mk 1) Nat r n0) Nat.zero)");
        stuck("((@ Quot.lift 1 1) Nat r Nat f hr)");
        fx.bind("q", k.mkLocal(fx.t("((@ Quot 1) Nat r)")));
        stuck("((@ Quot.lift 1 1) Nat r Nat f hr $q)");
    }

    @Test
    void quotInd() {
        cores("(qm n0)", "((@ Quot.ind 1) Nat r qb qm ((@ Quot.mk 1) Nat r n0))");
        // the major is normalised with whnf
        cores("(qm n0)", "((@ Quot.ind 1) Nat r qb qm ((@ id 1) ((@ Quot 1) Nat r) ((@ Quot.mk 1) Nat r n0)))");
    }

    // ---- the Nat extension -----------------------------------------------------------------------------

    @Test
    void natArithmetic() {
        whnfs("(lit 5)", "(Nat.add (lit 2) (lit 3))");
        whnfs("(lit 0)", "(Nat.sub (lit 2) (lit 3))");
        whnfs("(lit 1)", "(Nat.sub (lit 3) (lit 2))");
        whnfs("(lit 6)", "(Nat.mul (lit 2) (lit 3))");
        whnfs("(lit 8)", "(Nat.pow (lit 2) (lit 3))");
        whnfs("(lit 1267650600228229401496703205376)", "(Nat.pow (lit 2) (lit 100))");
        whnfs("(lit 6)", "(Nat.gcd (lit 12) (lit 18))");
        whnfs("(lit 2)", "(Nat.mod (lit 17) (lit 5))");
        whnfs("(lit 17)", "(Nat.mod (lit 17) (lit 0))");
        whnfs("(lit 3)", "(Nat.div (lit 17) (lit 5))");
        whnfs("(lit 0)", "(Nat.div (lit 17) (lit 0))");
        whnfs("(lit 4)", "(Nat.land (lit 12) (lit 6))");
        whnfs("(lit 14)", "(Nat.lor (lit 12) (lit 6))");
        whnfs("(lit 10)", "(Nat.xor (lit 12) (lit 6))");
        whnfs("(lit 40)", "(Nat.shiftLeft (lit 5) (lit 3))");
        whnfs("(lit 2)", "(Nat.shiftRight (lit 20) (lit 3))");
        whnfs("(lit 18446744073709551616)", "(Nat.add (lit 18446744073709551615) (lit 1))");
    }

    @Test
    void natPredicates() {
        whnfs("Bool.true", "(Nat.beq (lit 2) (lit 2))");
        whnfs("Bool.false", "(Nat.beq (lit 2) (lit 3))");
        whnfs("Bool.true", "(Nat.ble (lit 2) (lit 3))");
        whnfs("Bool.false", "(Nat.ble (lit 3) (lit 2))");
    }

    @Test
    void natSuccAndZero() {
        whnfs("(lit 5)", "(Nat.succ (lit 4))");
        whnfs("(lit 1)", "(Nat.succ Nat.zero)");
        // Nat.zero and literal 0 are both normal forms
        stuck("Nat.zero");
        stuck("(lit 0)");
        // Nat.zero counts as a literal argument
        whnfs("(lit 3)", "(Nat.add Nat.zero (lit 3))");
    }

    @Test
    void natArgumentsAreNormalisedFirst() {
        whnfs("(lit 2)", "two");
        whnfs("(lit 4)", "(Nat.add two two)");
        whnfs("(lit 4)", "four");
        whnfs("(lit 3)", "(Nat.succ two)");
    }

    @Test
    void natExtensionIsNotPartOfWhnfCore() {
        cores("(Nat.add (lit 2) (lit 3))", "(Nat.add (lit 2) (lit 3))");
        cores("(Nat.succ (lit 4))", "(Nat.succ (lit 4))");
    }

    @Test
    void natWithANonLiteralArgumentFallsBackToDelta() {
        whnfs("(Nat.succ ((@ Nat.rec 1) (fun (x : Nat) Nat) n0 (fun (x : Nat) (ih : Nat) (Nat.succ ih)) (lit 0)))",
                "(Nat.add n0 (lit 1))");
        // a stuck opaque function with a non-literal argument
        stuck("(Nat.mul n0 (lit 2))");
        // partial application is not touched
        stuck("(Nat.mul (lit 2))");
    }

    @Test
    void natGuardsRejectTheDeclaration() {
        fx.rejects(Reason.NAT_LITERAL_TOO_BIG, () -> k.whnf(fx.t("(Nat.pow (lit 2) (lit 4294967296))")));
        fx.rejects(Reason.NAT_LITERAL_TOO_BIG, () -> k.whnf(fx.t("(Nat.shiftLeft (lit 1) (lit 4294967296))")));
        // zero shifted by anything is zero, huge right shifts are zero
        whnfs("(lit 0)", "(Nat.shiftLeft (lit 0) (lit 123456789012345678901234567890))");
        whnfs("(lit 0)", "(Nat.shiftRight (lit 5) (lit 123456789012345678901234567890))");
    }

    // ---- caches ----------------------------------------------------------------------------------------

    @Test
    void repeatedCallsGiveTheSameTerm() {
        int t = fx.t("(Nat.add two two)");
        int r1 = k.whnf(t);
        int r2 = k.whnf(t);
        assertEquals(r1, r2);
        long steps = k.steps();
        k.whnf(t);
        assertEquals(steps, k.steps(), "the third call is a cache hit");
    }
}
