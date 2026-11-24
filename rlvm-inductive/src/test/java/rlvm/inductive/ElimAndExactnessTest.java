package rlvm.inductive;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import rlvm.api.Reason;
import rlvm.api.ResourceException;

/** Elimination universe (6.8), K (6.9), flags (6.5), and the exactness traps T2 - T5 of 6.13. */
class ElimAndExactnessTest {

    private static GeneratedBlock only(Env e, Env.Block b) {
        e.freeze();
        for (Env.Block prior : e.blocks) {
            if (prior == b) {
                break;
            }
            e.admit(prior);
        }
        return e.admit(b);
    }

    private static String[] lps(Env e, GeneratedBlock g) {
        return ReferenceOutputsTest.levelParams(e, g.recLevelParams);
    }

    // ---- 6.8 / 6.9 ---------------------------------------------------------------------------------

    @Test
    void trueIsKAndEliminatesEverywhere() {
        Env e = new Env();
        GeneratedBlock g = only(e, Fixtures.trueProp(e));
        assertTrue(g.k);
        assertFalse(g.elimOnlyAtZero);
        assertFalse(g.isRec);
        assertArrayEquals(new String[] {"u"}, lps(e, g));
        ReferenceOutputsTest.assertTerm(e, "∀ (motive : True → Sort u), motive True.intro → ∀ (t : True), motive t", g.recType[0]);
    }

    @Test
    void falseEliminatesEverywhereWithoutK() {
        Env e = new Env();
        GeneratedBlock g = only(e, Fixtures.falseProp(e));
        assertFalse(g.k);
        assertFalse(g.elimOnlyAtZero);
        assertEquals(0, g.recNumMinors);
        assertEquals(0, g.recRuleCount[0]);
        ReferenceOutputsTest.assertTerm(e, "∀ (motive : False → Sort u) (t : False), motive t", g.recType[0]);
    }

    @Test
    void propositionWithTwoConstructorsEliminatesIntoPropOnly() {
        Env e = new Env();
        Env.Block or = e.inductive("", 2).type("Or", "Prop → Prop → Prop")
                .ctor("Or.inl", "∀ (a b : Prop), a → Or a b").ctor("Or.inr", "∀ (a b : Prop), b → Or a b")
                .rec("Or.rec", "").end();
        GeneratedBlock g = only(e, or);
        assertTrue(g.elimOnlyAtZero);
        assertFalse(g.k);
        assertArrayEquals(new String[] {}, lps(e, g));
        ReferenceOutputsTest.assertTerm(e, "∀ (a b : Prop) (motive : Or a b → Prop), (∀ (h : a), motive (Or.inl a b h)) → "
                + "(∀ (h : b), motive (Or.inr a b h)) → ∀ (t : Or a b), motive t", g.recType[0]);
    }

    @Test
    void existsHidesItsWitness() {
        Env e = new Env();
        Env.Block ex = e.inductive("u", 2).type("Exists", "∀ (α : Sort u), (α → Prop) → Prop")
                .ctor("Exists.intro", "∀ (α : Sort u) (p : α → Prop) (w : α), p w → Exists.{u} α p")
                .rec("Exists.rec", "u").end();
        GeneratedBlock g = only(e, ex);
        assertTrue(g.elimOnlyAtZero);
        assertFalse(g.k);
        assertArrayEquals(new String[] {"u"}, lps(e, g));
    }

    @Test
    void fieldThatOccursInTheResultTypeIsNotASecret() {
        // tutorial sortElimPropRec: large elimination because the non-Prop field is an index of the result
        Env e = new Env();
        Fixtures.nat(e);
        Env.Block s = e.inductive("", 0).type("S", "Nat → Prop").ctor("S.mk", "∀ (n : Nat), S n").rec("S.rec", "u").end();
        GeneratedBlock g = only(e, s);
        assertFalse(g.elimOnlyAtZero);
        assertFalse(g.k); // one field
        assertArrayEquals(new String[] {"u"}, lps(e, g));
    }

    @Test
    void fieldThatOccursOnlyUnderAFunctionIsASecret() {
        // tutorial sortElimProp2Rec: `id b` is not the field itself
        Env e = new Env();
        Fixtures.nat(e);
        e.def("idNat", "", "Nat → Nat", "λ (n : Nat), n");
        Env.Block s = e.inductive("", 0).type("S", "Nat → Prop").ctor("S.mk", "∀ (n : Nat), S (idNat n)").rec("S.rec", "").end();
        GeneratedBlock g = only(e, s);
        assertTrue(g.elimOnlyAtZero);
    }

    @Test
    void sortPolymorphicTypeWithTwoConstructorsMayBeAProp() {
        // bugs/large-elim-param: Sort u could be Prop, so the recursor eliminates into Prop only
        Env e = new Env();
        Env.Block b = e.inductive("u", 0).type("MyBool", "Sort u").ctor("MyBool.tt", "MyBool.{u}").ctor("MyBool.ff", "MyBool.{u}")
                .rec("MyBool.rec", "u").end();
        GeneratedBlock g = only(e, b);
        assertTrue(g.elimOnlyAtZero);
        assertFalse(g.k);
        assertArrayEquals(new String[] {"u"}, lps(e, g));
    }

    @Test
    void punitGetsAFreshUniverseNameFirst() {
        // T5: PUnit.rec has [u_1, u]; not K because Sort u is not definitely Prop
        Env e = new Env();
        Env.Block b = e.inductive("u", 0).type("PUnit", "Sort u").ctor("PUnit.unit", "PUnit.{u}").rec("PUnit.rec", "u_1 u").end();
        GeneratedBlock g = only(e, b);
        assertFalse(g.elimOnlyAtZero);
        assertFalse(g.k);
        assertArrayEquals(new String[] {"u_1", "u"}, lps(e, g));
        ReferenceOutputsTest.assertTerm(e,
                "∀ (motive : PUnit.{u} → Sort u_1), motive PUnit.unit.{u} → ∀ (t : PUnit.{u}), motive t", g.recType[0]);
    }

    @Test
    void eliminationUniverseSkipsTakenNames() {
        Env e = new Env();
        Env.Block b = e.inductive("u u_1", 0).type("P2", "Sort (max u u_1)")
                .ctor("P2.mk", "P2.{u,u_1}").rec("P2.rec", "u_2 u u_1").end();
        GeneratedBlock g = only(e, b);
        assertArrayEquals(new String[] {"u_2", "u", "u_1"}, lps(e, g));
    }

    // ---- 6.5 ---------------------------------------------------------------------------------------

    @Test
    void isRecIsSyntacticAndPositivityFollowsReduction() {
        // corner-cases/vanishing-occurrence: the occurrence disappears under whnf; the flag stays
        Env e = new Env();
        Fixtures.nat(e);
        Env.Block v = e.inductive("", 0).type("V", "Type").ctor("V.mk", "((λ (x : Type), Nat) V) → V").rec("V.rec", "u").end();
        GeneratedBlock g = only(e, v);
        assertTrue(g.isRec);
        assertFalse(g.isReflexive);
        // the field is not recursive: no induction hypothesis
        ReferenceOutputsTest.assertTerm(e, "∀ (motive : V → Sort u), (∀ (a : (λ (x : Type), Nat) V), motive (V.mk a)) → "
                + "∀ (t : V), motive t", g.recType[0]);
        ReferenceOutputsTest.assertTerm(e, "λ (motive : V → Sort u) (mk : ∀ (a : (λ (x : Type), Nat) V), motive (V.mk a)) "
                + "(a : (λ (x : Type), Nat) V), mk a", g.recRuleRhs[0][0]);
    }

    // ---- T2 ----------------------------------------------------------------------------------------

    @Test
    void annotationsAreStrippedFromParameterAndFieldBinders() {
        Env e = new Env();
        Fixtures.nat(e);
        e.def("outParam", "", "Type 1 → Type 1", "λ (a : Type 1), a");
        e.def("optParam", "", "∀ (a : Type), a → Type", "λ (a : Type) (d : a), a");
        Env.Block op = e.inductive("", 1).type("OP", "outParam Type → Type")
                .ctor("OP.mk", "∀ (α : outParam Type), α → optParam Nat Nat.zero → OP α").rec("OP.rec", "u").end();
        GeneratedBlock g = only(e, op);
        assertEquals(2, g.ctorNumFields[0]);
        // the constructor keeps its annotations (it is an input); the recursor's binders lose them
        String pre = "(α : Type) (motive : OP α → Sort u) (mk : ∀ (a : α) (n : Nat), motive (OP.mk α a n))";
        ReferenceOutputsTest.assertTerm(e, "∀ " + pre + " (t : OP α), motive t", g.recType[0]);
        ReferenceOutputsTest.assertTerm(e, "λ " + pre + " (a : α) (n : Nat), mk a n", g.recRuleRhs[0][0]);
    }

    // ---- T4 ----------------------------------------------------------------------------------------

    @Test
    void hypothesisIsBuiltFromTheWeakHeadNormalFormOfTheFieldType() {
        // tutorial reduceCtorParamRefl2: field α → constType (T α) α, hypothesis ∀ a, motive (x a)
        Env e = new Env();
        e.def("constType", "", "Type → Type → Type", "λ (a b : Type), a");
        Env.Block t = e.inductive("", 1).type("T", "Type → Type")
                .ctor("T.mk", "∀ (α : Type), (α → constType (T α) α) → T α").rec("T.rec", "u").end();
        GeneratedBlock g = only(e, t);
        assertTrue(g.isRec);
        assertTrue(g.isReflexive);
        String pre = "(α : Type) (motive : T α → Sort u) "
                + "(mk : ∀ (x : α → constType (T α) α), (∀ (a : α), motive (x a)) → motive (T.mk α x))";
        ReferenceOutputsTest.assertTerm(e, "∀ " + pre + " (t : T α), motive t", g.recType[0]);
        ReferenceOutputsTest.assertTerm(e, "λ " + pre + " (x : α → constType (T α) α), "
                + "mk x (λ (a : α), T.rec.{u} α motive mk (x a))", g.recRuleRhs[0][0]);
    }

    @Test
    void recursiveFieldKeepsItsDeclaredType() {
        // tutorial reduceCtorParam: the field type reduces to T α; the binder shows the declared type
        Env e = new Env();
        Fixtures.nat(e);
        e.def("constType", "", "Type → Type → Type", "λ (a b : Type), a");
        Env.Block t = e.inductive("", 1).type("T", "Type → Type")
                .ctor("T.leaf", "∀ (α : Type), T α")
                .ctor("T.mk", "∀ (α : Type), constType (T α) Nat → T α").rec("T.rec", "u").end();
        GeneratedBlock g = only(e, t);
        String pre = "(α : Type) (motive : T α → Sort u) (leaf : motive (T.leaf α)) "
                + "(mk : ∀ (x : constType (T α) Nat), motive x → motive (T.mk α x))";
        ReferenceOutputsTest.assertTerm(e, "∀ " + pre + " (t : T α), motive t", g.recType[0]);
        ReferenceOutputsTest.assertTerm(e, "λ " + pre + " (x : constType (T α) Nat), mk x (T.rec.{u} α motive leaf mk x)",
                g.recRuleRhs[0][1]);
    }

    @Test
    void nonSyntacticEngineIsDeclinedWhereWhnfIsObservable() {
        org.junit.jupiter.api.Assumptions.assumeFalse(Env.USE_REF, "needs the fake kernel's switch");
        Env e = new Env();
        e.def("constType", "", "Type → Type → Type", "λ (a b : Type), a");
        Env.Block t = e.inductive("", 1).type("T", "Type → Type")
                .ctor("T.mk", "∀ (α : Type), (α → constType (T α) α) → T α").rec("T.rec", "u").end();
        e.freeze();
        e.kernel.syntactic = false;
        assertEquals(Reason.UNSUPPORTED_INPUT, assertThrows(ResourceException.class, () -> e.generate(t)).reason());

        // ... but not where whnf is the identity
        Env e2 = new Env();
        Env.Block list = Fixtures.list(e2);
        e2.freeze();
        e2.kernel.syntactic = false;
        assertEquals(1, e2.generate(list).recCount);
    }

    // ---- stand-ins ---------------------------------------------------------------------------------

    @Test
    void theEngineNeverSeesAMemberOfTheBlock() {
        Env e = new Env();
        Env.Block list = Fixtures.list(e);
        Env.Block tree = Fixtures.tree(e);
        e.freeze();
        e.admit(list);
        e.admit(tree);
        assertEquals(0, e.kernel.exposeCalls);
    }

    @Test
    void anInstanceIsReusableAfterAFailure() {
        Env e = new Env();
        Env.Block nat = Fixtures.nat(e);
        Env.Block bad = e.inductive("", 0).type("Bad", "Type").ctor("Bad.mk", "(Bad → Nat) → Bad").rec("Bad.rec", "u").end();
        Env.Block list = Fixtures.list(e);
        e.freeze();
        e.admit(nat);
        assertThrows(rlvm.api.KernelException.class, () -> e.generate(bad));
        GeneratedBlock g = e.admit(list);
        assertEquals(1, g.recCount);
        assertEquals(2, g.recNumMinors);
        // and the same block twice gives the same answer
        GeneratedBlock g2 = e.generate(list);
        assertTrue(e.tops.eqv(g.recType[0], g2.recType[0]));
    }
}
