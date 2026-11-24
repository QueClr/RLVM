package rlvm.inductive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import rlvm.api.KernelException;
import rlvm.api.Reason;

/**
 * Record comparison (checklist items 49 - 51): the claims are first set to the derived values (the
 * comparison passes), then one field at a time is forged, as the arena's bad tests do
 * ({@code bugs/ctor-num-fields}, {@code rec-k-lie}, {@code nat-rec-rules}, {@code nested-rec-rule-nfields},
 * {@code orphan-rec}, {@code other/orphan-ctor}, ...).
 */
class RecordCompareTest {

    /** Makes the exported records of {@code b} say what admission derived. */
    static void claimDerived(Env e, Env.Block b, GeneratedBlock g) {
        BlockInput in = b.in;
        for (int k = 0; k < in.typeCount; k++) {
            for (int i = in.typeCtorStart[k]; i < in.typeCtorStart[k] + in.typeCtorCount[k]; i++) {
                in.ctorClaimLevelParams[i] = in.levelParams;
                in.ctorClaimInduct[i] = in.typeName[k];
                in.ctorClaimCidx[i] = g.ctorIndex[i];
                in.ctorClaimNumParams[i] = in.numParams;
                in.ctorClaimNumFields[i] = g.ctorNumFields[i];
            }
        }
        int all = e.levels.intern(in.typeName, 0, in.typeCount);
        for (int r = 0; r < in.recCount; r++) {
            int j = -1;
            for (int q = 0; q < g.recCount; q++) {
                if (e.names.prefix(in.recName[r]) == g.recNamePrefix[q]
                        && e.names.lastComponentIs(in.recName[r], g.recNameLast[q])) {
                    j = q;
                }
            }
            if (j < 0) {
                continue;
            }
            in.recType[r] = g.recType[j];
            in.recAll[r] = all;
            in.recNumParams[r] = in.numParams;
            in.recNumIndices[r] = g.recNumIndices[j];
            in.recNumMotives[r] = g.recNumMotives;
            in.recNumMinors[r] = g.recNumMinors;
            in.recK[r] = g.k;
            int n = g.recRuleCount[j];
            in.recRuleCount[r] = n;
            in.recRuleCtorName[r] = new int[n];
            in.recRuleFields[r] = new long[n];
            in.recRuleRhs[r] = new int[n];
            for (int i = 0; i < n; i++) {
                in.recRuleCtorName[r][i] = e.decls.name(g.recRuleCtor[j][i]);
                in.recRuleFields[r][i] = g.recRuleFields[j][i];
                in.recRuleRhs[r][i] = g.recRuleRhs[j][i];
            }
        }
    }

    private Env e;
    private Env.Block list;
    private Env.Block tree;
    private GeneratedBlock g;

    private void setUp(boolean nested) {
        e = new Env();
        list = Fixtures.list(e);
        tree = Fixtures.tree(e);
        e.freeze();
        g = e.admit(list);
        claimDerived(e, list, g);
        if (nested) {
            g = e.admit(tree);
            claimDerived(e, tree, g);
        }
    }

    private void mismatch(Env.Block b) {
        KernelException x = assertThrows(KernelException.class, () -> e.compare.compare(b.in, g));
        assertEquals(Reason.RECORD_MISMATCH, x.reason());
    }

    @Test
    void identicalRecordsPass() {
        setUp(false);
        assertEquals(1L, e.compare.compare(list.in, g));
        setUp(true);
        assertEquals(3L, e.compare.compare(tree.in, g));
    }

    @Test
    void recursorRecordsMayComeInAnyOrder() {
        // trap T8: bugs/nested-name-capture lists rec_1 before rec
        e = new Env();
        list = Fixtures.list(e);
        tree = e.inductive("u", 1).type("Tree", "Type u → Type u")
                .ctor("Tree.node", "∀ (α : Type u), α → List.{u} (Tree.{u} α) → Tree.{u} α")
                .rec("Tree.rec_1", "u_1 u").rec("Tree.rec", "u_1 u").end();
        e.freeze();
        e.admit(list);
        g = e.admit(tree);
        claimDerived(e, tree, g);
        assertEquals(3L, e.compare.compare(tree.in, g));
    }

    @Test
    void forgedConstructorRecords() {
        setUp(false);
        int cons = list.ctorIndex("List.cons");
        list.in.ctorClaimNumFields[cons] = 0; // bugs/ctor-num-fields
        mismatch(list);
        setUp(false);
        list.in.ctorClaimCidx[cons] = 0;
        mismatch(list);
        setUp(false);
        list.in.ctorClaimNumParams[cons] = 0;
        mismatch(list);
        setUp(false);
        list.in.ctorClaimInduct[cons] = list.in.ctorName[0];
        mismatch(list);
        setUp(false);
        list.in.ctorClaimLevelParams[cons] = 0;
        mismatch(list);
    }

    @Test
    void forgedRecursorFacts() {
        setUp(false);
        list.in.recK[0] = true; // bugs/rec-k-lie, nat-rec-k-lie
        mismatch(list);
        setUp(false);
        list.in.recNumParams[0] = 0;
        mismatch(list);
        setUp(false);
        list.in.recNumIndices[0] = 1;
        mismatch(list);
        setUp(false);
        list.in.recNumMotives[0] = 2;
        mismatch(list);
        setUp(false);
        list.in.recNumMinors[0] = 1;
        mismatch(list);
        setUp(false);
        list.in.recAll[0] = 0;
        mismatch(list);
        setUp(false);
        list.in.recLevelParams[0] = list.in.levelParams; // bugs/large-elim-*: the motive universe is missing
        mismatch(list);
    }

    @Test
    void forgedRecursorTypeAndRules() {
        setUp(false);
        list.in.recType[0] = g.recRuleRhs[0][0];
        mismatch(list);
        setUp(false);
        list.in.recRuleRhs[0][1] = g.recRuleRhs[0][0]; // bugs/nat-rec-rules, rec-rule-wrong-arg
        mismatch(list);
        setUp(false);
        list.in.recRuleFields[0][1] = 1; // bugs/nested-rec-rule-nfields
        mismatch(list);
        setUp(false);
        list.in.recRuleCtorName[0][1] = list.in.recRuleCtorName[0][0];
        mismatch(list);
        setUp(false);
        list.in.recRuleCount[0] = 1;
        mismatch(list);
    }

    @Test
    void ruleDroppingAnInductionHypothesisDiffers() {
        // bugs/rec-missing-ih: the rule `cons head tail` without the recursive call
        setUp(false);
        String pre = "(α : Type u) (motive : List.{u} α → Sort u_1) (nil : motive (List.nil.{u} α)) "
                + "(cons : ∀ (head : α) (tail : List.{u} α), motive tail → motive (List.cons.{u} α head tail))";
        list.in.recRuleRhs[0][1] = e.expect("λ " + pre + " (head : α) (tail : List.{u} α), cons head tail");
        mismatch(list);
    }

    @Test
    void recursorNobodyDerives() {
        // bugs/orphan-rec, other/extra-rec, tutorial misnamed_rec: generate() already rejects the item
        e = new Env();
        list = e.inductive("u", 1).type("List", "Type u → Type u")
                .ctor("List.nil", "∀ (α : Type u), List.{u} α")
                .ctor("List.cons", "∀ (α : Type u), α → List.{u} α → List.{u} α")
                .rec("List.rec", "u_1 u").rec("List.not_rec", "u_1 u").end();
        e.freeze();
        assertEquals(Reason.RECORD_MISMATCH, assertThrows(KernelException.class, () -> e.generate(list)).reason());

        // ... and so does the comparison on its own
        setUp(false);
        list.in.recName[0] = list.in.ctorName[0];
        mismatch(list);
    }

    @Test
    void constructorNobodyLists() {
        // other/orphan-ctor: a constructor record of the item that no type lists
        setUp(false);
        BlockInput in = list.in;
        int n = in.ctorCount;
        in.ctorCount = n + 1;
        in.ctorCid = java.util.Arrays.copyOf(in.ctorCid, n + 1);
        in.ctorName = java.util.Arrays.copyOf(in.ctorName, n + 1);
        in.ctorType = java.util.Arrays.copyOf(in.ctorType, n + 1);
        in.ctorCid[n] = in.recCid[0]; // any other constant of the unit
        in.ctorName[n] = in.recName[0];
        in.ctorType[n] = in.ctorType[0];
        // the comparison on its own
        g.ctorIndex = java.util.Arrays.copyOf(g.ctorIndex, n + 1);
        g.ctorIndex[n] = -1;
        mismatch(list);
        // generate() rejects the item as well
        assertEquals(Reason.RECORD_MISMATCH, assertThrows(KernelException.class, () -> e.generate(list)).reason());
    }

    /** The comparison passes, but the block is not admissible here: {@code declineReason} was set. */
    private void undecidable(Env.Block b) {
        rlvm.api.ResourceException x = assertThrows(rlvm.api.ResourceException.class, () -> e.compare.compare(b.in, g));
        assertEquals(Reason.UNSUPPORTED_INPUT, x.reason());
        g.declineReason = null;
    }

    @Test
    void recordsFlaggedUnsafeAreNotCompared() {
        // The official checker does not replay unsafe constants: a constructor record flagged unsafe
        // inside a safe block gives name and type only; its claims are never compared.
        setUp(false);
        int cons = list.ctorIndex("List.cons");
        list.in.ctorUnsafe = new boolean[list.in.ctorCount];
        list.in.ctorUnsafe[cons] = true;
        list.in.ctorClaimNumFields[cons] = 0;
        list.in.ctorClaimCidx[cons] = 7;
        list.in.ctorClaimNumParams[cons] = 9;
        list.in.ctorClaimInduct[cons] = list.in.ctorName[0];
        assertEquals(1L, e.compare.compare(list.in, g));
        // ... but the row keeps the record's universe parameters, which must be the generated ones
        list.in.ctorClaimLevelParams[cons] = 0;
        undecidable(list);
        // the flag of one record does not excuse another
        setUp(false);
        list.in.ctorUnsafe = new boolean[list.in.ctorCount];
        list.in.ctorUnsafe[cons] = true;
        list.in.ctorClaimNumFields[0] = 5;
        mismatch(list);

        // A recursor record flagged unsafe is ignored officially. Identical: nothing to say.
        setUp(false);
        list.in.recUnsafe = new boolean[list.in.recCount];
        list.in.recUnsafe[0] = true;
        assertEquals(1L, e.compare.compare(list.in, g));
        // Different: not a rejection, but the row is not the constant the kernel generates.
        list.in.recK[0] = true;
        undecidable(list);
        setUp(false);
        list.in.recUnsafe = new boolean[list.in.recCount];
        list.in.recUnsafe[0] = true;
        list.in.recRuleRhs[0][1] = g.recRuleRhs[0][0];
        undecidable(list);
        setUp(false);
        list.in.recUnsafe = new boolean[list.in.recCount];
        list.in.recUnsafe[0] = true;
        list.in.recName[0] = list.in.ctorName[0]; // names no derived recursor
        undecidable(list);
    }

    @Test
    void unsafeRecursorRecordNobodyDerivesIsDeclinedNotRejected() {
        e = new Env();
        list = e.inductive("u", 1).type("List", "Type u → Type u")
                .ctor("List.nil", "∀ (α : Type u), List.{u} α")
                .ctor("List.cons", "∀ (α : Type u), α → List.{u} α → List.{u} α")
                .rec("List.rec", "u_1 u").rec("List.not_rec", "u_1 u").end();
        e.freeze();
        list.in.recUnsafe = new boolean[list.in.recCount];
        list.in.recUnsafe[1] = true;
        assertEquals(Reason.UNSUPPORTED_INPUT,
                assertThrows(rlvm.api.ResourceException.class, () -> e.generate(list)).reason());
    }

    @Test
    void nestedRuleOfTheAuxiliaryRecursorIsCompared() {
        // bugs/rec-rule-proof-irrel-nested, nested-rec-rule-nfields: rec_N records are compared too
        setUp(true);
        tree.in.recRuleFields[1][1] = 0;
        mismatch(tree);
        setUp(true);
        tree.in.recRuleRhs[1][1] = tree.in.recRuleRhs[1][0];
        mismatch(tree);
        setUp(true);
        tree.in.recRuleCtorName[1][0] = tree.in.ctorName[0];
        mismatch(tree);
    }
}
