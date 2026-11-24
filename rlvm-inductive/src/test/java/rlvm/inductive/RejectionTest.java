package rlvm.inductive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import rlvm.api.KernelException;
import rlvm.api.Reason;
import rlvm.api.ResourceException;

/** Blocks that the official kernel rejects (03 sections 6.2 - 6.6, 6.12), and the declines. */
class RejectionTest {

    private static Reason rejected(Env e, Env.Block b) {
        if (e.kernel == null) {
            e.freeze();
            for (Env.Block prior : e.blocks) {
                if (prior == b) {
                    break;
                }
                e.admit(prior);
            }
        }
        return assertThrows(KernelException.class, () -> e.generate(b)).reason();
    }

    private static Reason declined(Env e, Env.Block b) {
        e.freeze();
        for (Env.Block prior : e.blocks) {
            if (prior == b) {
                break;
            }
            e.admit(prior);
        }
        return assertThrows(ResourceException.class, () -> e.generate(b)).reason();
    }

    @Test
    void headerIsNotASort() {
        Env e = new Env();
        Fixtures.nat(e);
        Env.Block b = e.inductive("", 0).type("Bad", "Nat").ctor("Bad.mk", "Bad").end();
        assertEquals(Reason.TYPE_EXPECTED, rejected(e, b));
    }

    @Test
    void duplicateUniverseParameter() {
        Env e = new Env();
        Env.Block b = e.inductive("u u", 0).type("Bad", "Sort u").end();
        assertEquals(Reason.DUPLICATE_LEVEL_PARAM, rejected(e, b));
    }

    @Test
    void tooFewParameters() {
        Env e = new Env();
        Env.Block b = e.inductive("", 1).type("Bad", "Type").ctor("Bad.mk", "Bad").end();
        assertEquals(Reason.INDUCTIVE_INVALID, rejected(e, b));
    }

    @Test
    void absurdParameterCountAllocatesNothing() {
        Env e = new Env();
        Env.Block b = e.inductive("", 0).type("T", "Type").ctor("T.mk", "T").rec("T.rec", "u").end();
        b.in.numParams = Integer.MAX_VALUE;
        assertEquals(Reason.INDUCTIVE_INVALID, rejected(e, b));
    }

    @Test
    void parametersHiddenBehindADefinition() {
        // elim_nested's get_params is syntactic: the header must be a manifest Pi telescope up to numParams
        Env e = new Env();
        e.def("F", "", "Type 1", "Type → Type");
        Env.Block b = e.inductive("", 1).type("Bad", "F").ctor("Bad.mk", "∀ (α : Type), Bad α").rec("Bad.rec", "u").end();
        assertEquals(Reason.INDUCTIVE_INVALID, rejected(e, b));
    }

    @Test
    void constructorParameterOfAnotherType() {
        Env e = new Env();
        Env.Block b = e.inductive("", 1).type("T", "Type → Type").ctor("T.mk", "∀ (α : Prop), T α").rec("T.rec", "u").end();
        assertEquals(Reason.APP_TYPE_MISMATCH, rejected(e, b));
    }

    @Test
    void resultParametersSwapped() {
        Env e = new Env();
        Env.Block b = e.inductive("", 2).type("T", "Type → Type → Type")
                .ctor("T.mk", "∀ (α β : Type), T β α").rec("T.rec", "u").end();
        assertEquals(Reason.INDUCTIVE_INVALID, rejected(e, b));
    }

    @Test
    void resultLevelsSwapped() {
        Env e = new Env();
        Env.Block b = e.inductive("u v", 0).type("T", "Sort (max u v)")
                .ctor("T.mk", "T.{v,u}").rec("T.rec", "u_1 u v").end();
        assertEquals(Reason.INDUCTIVE_INVALID, rejected(e, b));
    }

    @Test
    void underAppliedOccurrence() {
        Env e = new Env();
        Fixtures.nat(e);
        Env.Block b = e.inductive("", 1).type("T", "Type → Type")
                .ctor("T.mk", "∀ (α : Type), ((λ (f : Type → Type), Nat) T) → T α").rec("T.rec", "u").end();
        assertEquals(Reason.INDUCTIVE_INVALID, rejected(e, b));
    }

    @Test
    void sharedSubtermIsCheckedAtEveryBinderOffset() {
        Env e = new Env();
        Fixtures.nat(e);
        Env.Block b = Fixtures.sharedOccurrenceAtTwoOffsets(e);
        assertEquals(Reason.INDUCTIVE_INVALID, rejected(e, b));
    }

    @Test
    void blockTypeInsideAnIndexOfTheResult() {
        Env e = new Env();
        Fixtures.nat(e);
        Env.Block b = e.inductive("", 0).type("I", "Type → Type").ctor("I.mk", "I (I Nat)").rec("I.rec", "u").end();
        assertEquals(Reason.INDUCTIVE_INVALID, rejected(e, b));
    }

    @Test
    void negativeOccurrence() {
        Env e = new Env();
        Fixtures.nat(e);
        Env.Block b = e.inductive("", 0).type("Bad", "Type").ctor("Bad.mk", "(Bad → Nat) → Bad").rec("Bad.rec", "u").end();
        assertEquals(Reason.INDUCTIVE_INVALID, rejected(e, b));
    }

    @Test
    void negativeOccurrenceBehindAReducibleCodomain() {
        // tutorial indNegReducible: only the head of a field type is normalised
        Env e = new Env();
        Fixtures.nat(e);
        e.def("constType", "", "Type → Type → Type", "λ (a b : Type), a");
        Env.Block b = e.inductive("", 0).type("Bad", "Type")
                .ctor("Bad.mk", "(constType Nat Bad → Bad) → Bad").rec("Bad.rec", "u").end();
        assertEquals(Reason.INDUCTIVE_INVALID, rejected(e, b));
    }

    @Test
    void occurrenceUnderAnOpaqueFunction() {
        Env e = new Env();
        e.axiom("Box", "", "Type → Type");
        Env.Block b = e.inductive("", 0).type("Bad", "Type").ctor("Bad.mk", "Box Bad → Bad").rec("Bad.rec", "u").end();
        assertEquals(Reason.INDUCTIVE_INVALID, rejected(e, b));
    }

    @Test
    void fieldUniverseTooBig() {
        Env e = new Env();
        Env.Block b = e.inductive("", 0).type("T", "Type").ctor("T.mk", "Type → T").rec("T.rec", "u").end();
        assertEquals(Reason.INDUCTIVE_INVALID, rejected(e, b));
    }

    @Test
    void invalidReturnType() {
        Env e = new Env();
        Fixtures.nat(e);
        Env.Block b = e.inductive("", 0).type("T", "Type").ctor("T.mk", "Nat").rec("T.rec", "u").end();
        assertEquals(Reason.INDUCTIVE_INVALID, rejected(e, b));
    }

    @Test
    void reservedPrefix() {
        Env e = new Env();
        e.def("_nested.X", "", "Type 1", "Type");
        Env.Block b = e.inductive("", 0).type("T", "Type 1").ctor("T.mk", "_nested.X → T").rec("T.rec", "u").end();
        assertEquals(Reason.INDUCTIVE_INVALID, rejected(e, b));
    }

    @Test
    void headerMentionsItsOwnBlock() {
        Env e = new Env();
        Env.Block b = e.inductive("", 0).type("T", "T → Type").ctor("T.mk", "T").rec("T.rec", "u").end();
        assertEquals(Reason.UNKNOWN_CONSTANT, rejected(e, b));
    }

    @Test
    void constructorTypeMentionsAConstructorOfTheBlock() {
        Env e = new Env();
        Fixtures.eq(e);
        Env.Block b = e.inductive("", 0).type("T", "Type").ctor("T.a", "T")
                .ctor("T.b", "Eq.{1} T T.a T.a → T").rec("T.rec", "u").end();
        assertEquals(Reason.UNKNOWN_CONSTANT, rejected(e, b));
    }

    @Test
    void mutualTypesInDifferentUniverses() {
        Env e = new Env();
        Env.Block b = e.inductive("", 0).type("A", "Type").ctor("A.mk", "A")
                .type("B", "Type 1").ctor("B.mk", "B").rec("A.rec", "u").rec("B.rec", "u").end();
        assertEquals(Reason.INDUCTIVE_INVALID, rejected(e, b));
    }

    @Test
    void mutualTypesWithDifferentParameters() {
        Env e = new Env();
        Env.Block b = e.inductive("", 1).type("A", "Type → Type").ctor("A.mk", "∀ (α : Type), A α")
                .type("B", "Prop → Type").ctor("B.mk", "∀ (α : Prop), B α").rec("A.rec", "u").rec("B.rec", "u").end();
        assertEquals(Reason.INDUCTIVE_INVALID, rejected(e, b));
    }

    @Test
    void nestedParametersWithLocalVariables() {
        Env e = new Env();
        Fixtures.nat(e);
        e.inductive("", 2).type("Vec", "Type → Nat → Type").ctor("Vec.mk", "∀ (α : Type) (n : Nat), Vec α n")
                .rec("Vec.rec", "u").end();
        Env.Block b = e.inductive("", 0).type("T", "Type").ctor("T.mk", "∀ (n : Nat), Vec T n → T")
                .rec("T.rec", "u").rec("T.rec_1", "u").end();
        assertEquals(Reason.INDUCTIVE_INVALID, rejected(e, b));
    }

    @Test
    void nestedParameterThatVanishesIsStillChecked() {
        // bugs/nested-unused-param: the ill-typed argument of K is absent from the auxiliary block and is
        // caught by the check of `K T Nat.succ` in the final environment
        Env e = new Env();
        Fixtures.nat(e);
        e.inductive("", 2).type("K", "Type → Nat → Type").ctor("K.mk", "∀ (α : Type) (n : Nat), K α n")
                .rec("K.rec", "u").end();
        Env.Block b = e.inductive("", 0).type("T", "Type").ctor("T.mk", "K T Nat.succ → T")
                .rec("T.rec", "u").rec("T.rec_1", "u").end();
        assertEquals(Reason.APP_TYPE_MISMATCH, rejected(e, b));
    }

    @Test
    void nestedContainerWhoseIndexTypeMentionsTheBlock() {
        // the auxiliary header is checked before any type of the block exists: unknown constant
        Env e = new Env();
        e.inductive("", 1).type("Fam", "∀ (α : Type), (α → Prop) → Type")
                .ctor("Fam.mk", "∀ (α : Type) (p : α → Prop), Fam α p").rec("Fam.rec", "u").end();
        Env.Block b = e.inductive("", 0).type("T", "Type").ctor("T.mk", "∀ (p : T → Prop), Fam T p → T")
                .rec("T.rec", "u").rec("T.rec_1", "u").end();
        assertEquals(Reason.UNKNOWN_CONSTANT, rejected(e, b));
    }

    // ---- declines ----------------------------------------------------------------------------------

    @Test
    void missingRecursorRecordIsDeclinedOnlyForAValidBlock() {
        Env e = new Env();
        Env.Block ok = e.inductive("", 0).type("T", "Type").ctor("T.mk", "T").end();
        assertEquals(Reason.UNSUPPORTED_INPUT, declined(e, ok));

        Env e2 = new Env();
        Fixtures.nat(e2);
        Env.Block bad = e2.inductive("", 0).type("Bad", "Nat").end();
        assertEquals(Reason.TYPE_EXPECTED, rejected(e2, bad));
    }

    @Test
    void missingAuxiliaryRecursorRecord() {
        Env e = new Env();
        Fixtures.list(e);
        Env.Block b = e.inductive("u", 1).type("Tree", "Type u → Type u")
                .ctor("Tree.node", "∀ (α : Type u), α → List.{u} (Tree.{u} α) → Tree.{u} α")
                .rec("Tree.rec", "u_1 u").end();
        assertEquals(Reason.UNSUPPORTED_INPUT, declined(e, b));
    }

    @Test
    void misnamedRecursorRecord() {
        // tutorial misnamed_rec / dup_rec_def2: the item's recursor record is not the derived recursor
        Env e = new Env();
        Env.Block b = e.inductive("", 0).type("T", "Type").ctor("T.mk", "T").rec("T.not_rec", "u").end();
        assertEquals(Reason.RECORD_MISMATCH, rejected(e, b));
    }

    @Test
    void recursorNameTakenByAnotherDeclaration() {
        // the export has no recursor record, but a definition (earlier or later) is called T.rec
        Env e = new Env();
        e.def("T.rec", "", "Type 1", "Type");
        Env.Block b = e.inductive("", 0).type("T", "Prop").ctor("T.a", "T").ctor("T.b", "T").end();
        assertEquals(Reason.DUPLICATE_DECLARATION, rejected(e, b));

        Env e2 = new Env();
        Env.Block b2 = e2.inductive("", 0).type("T", "Prop").ctor("T.a", "T").ctor("T.b", "T").end();
        e2.def("T.rec", "", "Type 1", "Type");
        assertEquals(Reason.DUPLICATE_DECLARATION, rejected(e2, b2));
    }

    @Test
    void recursorRecordsWithoutTheEliminationUniverse() {
        // every exported recursor then differs from the generated one
        Env e = new Env();
        Env.Block b = e.inductive("", 0).type("T", "Type").ctor("T.mk", "T").rec("T.rec", "").end();
        assertEquals(Reason.RECORD_MISMATCH, rejected(e, b));
    }

    @Test
    void inconsistentItem() {
        Env e = new Env();
        Env.Block b = Fixtures.nat(e);
        b.in.consistent = false;
        assertEquals(Reason.UNSUPPORTED_INPUT, declined(e, b));
    }

    @Test
    void blockTypeNamedLikeATypeAnnotation() {
        Env e = new Env();
        Env.Block b = e.inductive("u", 1).type("outParam", "Sort u → Sort (max 1 u)")
                .ctor("outParam.mk", "∀ (α : Sort u), α → outParam.{u} α").rec("outParam.rec", "u_1 u").end();
        assertEquals(Reason.UNSUPPORTED_INPUT, declined(e, b));
    }

    @Test
    void nestedBlockInAFileWithADeclarationUnderTheReservedRoot() {
        // The official auxiliary names (_nested.List_1, _nested.List_1.rec, ...) are checked against the
        // environment, and the official replay order is not the file order: undecidable here, wherever
        // the declaration stands. The decline comes after the comparison, so a forgery is still rejected.
        for (boolean before : new boolean[] {true, false}) {
            Env e = new Env();
            Env.Block list = Fixtures.list(e);
            if (before) {
                e.def("_nested.List_1.rec", "", "Type 1", "Type");
            }
            Env.Block tree = Fixtures.tree(e);
            if (!before) {
                e.def("_nested.List_1.rec", "", "Type 1", "Type");
            }
            e.freeze();
            e.admit(list);
            GeneratedBlock g = e.generate(tree);
            assertEquals(Reason.UNSUPPORTED_INPUT, g.declineReason);
            RecordCompareTest.claimDerived(e, tree, g);
            assertEquals(Reason.UNSUPPORTED_INPUT,
                    assertThrows(ResourceException.class, () -> e.compare.compare(tree.in, g)).reason());
            tree.in.recRuleFields[0][0] = 0; // bugs/nested-name-capture also forges a record: rejected
            assertEquals(Reason.RECORD_MISMATCH,
                    assertThrows(KernelException.class, () -> e.compare.compare(tree.in, g)).reason());
        }
    }

    @Test
    void nestedContainerArgumentsMentioningAConstructorOfTheBlock() {
        // checked officially in the final environment, where T.leaf has reduction rules: not decidable
        // with stand-ins; the derived facts are complete and the block is declined after the comparison
        Env e = new Env();
        Env.Block foo = e.inductive("", 2).type("Foo", "∀ (α : Type), α → Type")
                .ctor("Foo.mk", "∀ (α : Type) (a : α), Foo α a").rec("Foo.rec", "u").end();
        Env.Block t = e.inductive("", 0).type("T", "Type").ctor("T.leaf", "T").ctor("T.node", "Foo T T.leaf → T")
                .rec("T.rec", "u").rec("T.rec_1", "u").end();
        e.freeze();
        e.admit(foo);
        GeneratedBlock g = e.generate(t);
        assertEquals(Reason.UNSUPPORTED_INPUT, g.declineReason);
        assertEquals(1, g.numNested);
        RecordCompareTest.claimDerived(e, t, g);
        assertEquals(Reason.UNSUPPORTED_INPUT, assertThrows(ResourceException.class, () -> e.compare.compare(t.in, g)).reason());
    }
}
