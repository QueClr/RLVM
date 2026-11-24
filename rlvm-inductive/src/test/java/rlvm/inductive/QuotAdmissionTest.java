package rlvm.inductive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import rlvm.api.KernelException;
import rlvm.api.Reason;
import rlvm.api.ResourceException;
import rlvm.store.DeclKind;

/** The quotient package (03 section 5, checklist items 18 - 24). */
class QuotAdmissionTest {

    private static final String REL = "(α → α → Prop)";

    private static QuotAdmission ready(Env e) {
        e.freeze();
        e.admitAll();
        return new QuotAdmission(e.store, e.ops);
    }

    @Test
    void canonicalRecordsAreAdmitted() {
        Env e = new Env();
        Fixtures.eq(e);
        int[] q = Fixtures.quot(e);
        QuotAdmission qa = ready(e);
        for (int cid : q) {
            e.ops.begin(e.decls.rank(cid), e.decls.levelParams(cid));
            qa.admit(cid);
        }
        // 03 section 5, de Bruijn forms
        assertTrue(e.tops.eqv(qa.canonicalType(DeclKind.QUOT_TYPE),
                e.expect("Sort u → (#0 → #1 → Prop) → Sort u")));
        assertTrue(e.tops.eqv(qa.canonicalType(DeclKind.QUOT_MK),
                e.expect("Sort u → (#0 → #1 → Prop) → #1 → Quot.{u} #2 #1")));
        assertTrue(e.tops.eqv(qa.canonicalType(DeclKind.QUOT_LIFT), e.expect(
                "Sort u → (#0 → #1 → Prop) → Sort v → (#2 → #1) → "
                        + "(#3 → #4 → (#4 #1 #0) → Eq.{v} #4 (#3 #2) (#3 #1)) → Quot.{u} #4 #3 → #3")));
        assertTrue(e.tops.eqv(qa.canonicalType(DeclKind.QUOT_IND), e.expect(
                "Sort u → (#0 → #1 → Prop) → (Quot.{u} #1 #0 → Prop) → (#2 → #1 (Quot.mk.{u} #3 #2 #0)) → "
                        + "Quot.{u} #3 #2 → #2 #0")));
    }

    @Test
    void forgedQuotType() {
        // bugs/quot-bad-type
        Env e = new Env();
        Fixtures.eq(e);
        Fixtures.falseProp(e);
        int q = e.quot(DeclKind.QUOT_TYPE, "Quot", "u", "False");
        QuotAdmission qa = ready(e);
        assertEquals(Reason.QUOT_MISMATCH, assertThrows(KernelException.class, () -> qa.admit(q)).reason());
    }

    @Test
    void forgedMkAndUniverseNames() {
        Env e = new Env();
        Fixtures.eq(e);
        int q = e.quot(DeclKind.QUOT_TYPE, "Quot", "u", "∀ (α : Sort u), " + REL + " → Sort u");
        // r and a swapped in the result
        int mk = e.quot(DeclKind.QUOT_MK, "Quot.mk", "w", "∀ (α : Sort w) (r : " + REL + "), α → Quot.{w} α r");
        QuotAdmission qa = ready(e);
        qa.admit(q);
        assertEquals(Reason.QUOT_MISMATCH, assertThrows(KernelException.class, () -> qa.admit(mk)).reason());
    }

    @Test
    void wrongKindForTheName() {
        Env e = new Env();
        Fixtures.eq(e);
        int q = e.quot(DeclKind.QUOT_MK, "Quot", "u", "∀ (α : Sort u), " + REL + " → Sort u");
        QuotAdmission qa = ready(e);
        assertEquals(Reason.QUOT_MISMATCH, assertThrows(KernelException.class, () -> qa.admit(q)).reason());
    }

    @Test
    void eqIsRequired() {
        Env e = new Env();
        int q = e.quot(DeclKind.QUOT_TYPE, "Quot", "u", "∀ (α : Sort u), " + REL + " → Sort u");
        QuotAdmission qa = ready(e);
        assertEquals(Reason.UNKNOWN_CONSTANT, assertThrows(KernelException.class, () -> qa.admit(q)).reason());
    }

    @Test
    void eqMustHaveTheCanonicalShape() {
        // Eq with its arguments in another order
        Env e = new Env();
        e.inductive("u_1", 2).type("Eq", "∀ (α : Sort u_1), α → α → Type")
                .ctor("Eq.refl", "∀ (α : Sort u_1) (a : α), Eq.{u_1} α a a").rec("Eq.rec", "u u_1").end();
        int q = e.quot(DeclKind.QUOT_TYPE, "Quot", "u", "∀ (α : Sort u), " + REL + " → Sort u");
        QuotAdmission qa = ready(e);
        assertEquals(Reason.QUOT_MISMATCH, assertThrows(KernelException.class, () -> qa.admit(q)).reason());

        // Eq as a definition
        Env e2 = new Env();
        e2.def("Eq", "u_1", "∀ (α : Sort u_1), α → α → Prop", "λ (α : Sort u_1) (a b : α), ∀ (p : α → Prop), p a → p b");
        int q2 = e2.quot(DeclKind.QUOT_TYPE, "Quot", "u", "∀ (α : Sort u), " + REL + " → Sort u");
        QuotAdmission qa2 = ready(e2);
        assertEquals(Reason.QUOT_MISMATCH, assertThrows(KernelException.class, () -> qa2.admit(q2)).reason());
    }

    @Test
    void eqDeclaredAfterTheQuotRecordIsDeclined() {
        Env e = new Env();
        int q = e.quot(DeclKind.QUOT_TYPE, "Quot", "u", "∀ (α : Sort u), " + REL + " → Sort u");
        Fixtures.eq(e);
        QuotAdmission qa = ready(e);
        assertEquals(Reason.FORWARD_REFERENCE, assertThrows(ResourceException.class, () -> qa.admit(q)).reason());
    }

    @Test
    void scopeAboveTheLaterDeclarationsAdmitsTheRecord() {
        // rlvm-admit, admitting in dependency order: Eq (declared later in the file) is admitted first
        // and the quot record is then compared in a scope above it.
        Env e = new Env();
        int q = e.quot(DeclKind.QUOT_TYPE, "Quot", "u", "∀ (α : Sort u), " + REL + " → Sort u");
        int mk = e.quot(DeclKind.QUOT_MK, "Quot.mk", "u", "∀ (α : Sort u) (r : " + REL + "), α → Quot.{u} α r");
        Fixtures.eq(e);
        QuotAdmission qa = ready(e);
        qa.admit(q, Integer.MAX_VALUE);
        qa.admit(mk, Integer.MAX_VALUE);
        // the scope of the record itself is what admit(cid) uses
        QuotAdmission own = new QuotAdmission(e.store, e.ops);
        assertEquals(Reason.FORWARD_REFERENCE,
                assertThrows(ResourceException.class, () -> own.admit(q, e.decls.rank(q))).reason());
    }

    @Test
    void quotRecordUnderAnotherNameIsDeclined() {
        Env e = new Env();
        Fixtures.eq(e);
        int q = e.quot(DeclKind.QUOT_TYPE, "Quotient", "u", "∀ (α : Sort u), " + REL + " → Sort u");
        QuotAdmission qa = ready(e);
        assertEquals(Reason.UNSUPPORTED_INPUT, assertThrows(ResourceException.class, () -> qa.admit(q)).reason());
        // every decline states its cause
        assertTrue(qa.lastDeclineDetail().contains("a name other than Quot"), qa.lastDeclineDetail());
    }

    @Test
    void reservedNameTakenByAnotherDeclaration() {
        Env e = new Env();
        Fixtures.eq(e);
        e.def("Quot.lift", "", "Type 1", "Type");
        int q = e.quot(DeclKind.QUOT_TYPE, "Quot", "u", "∀ (α : Sort u), " + REL + " → Sort u");
        QuotAdmission qa = ready(e);
        assertEquals(Reason.QUOT_MISMATCH, assertThrows(KernelException.class, () -> qa.admit(q)).reason());
    }

    @Test
    void mkWithoutARecordForQuotIsDeclined() {
        Env e = new Env();
        Fixtures.eq(e);
        e.axiom("Q", "u", "∀ (α : Sort u), " + REL + " → Sort u");
        int mk = e.quot(DeclKind.QUOT_MK, "Quot.mk", "u", "∀ (α : Sort u) (r : " + REL + "), α → Q.{u} α r");
        QuotAdmission qa = ready(e);
        assertEquals(Reason.UNSUPPORTED_INPUT, assertThrows(ResourceException.class, () -> qa.admit(mk)).reason());
        assertTrue(qa.lastDeclineDetail().contains("no usable record for 'Quot'"), qa.lastDeclineDetail());
    }
}
