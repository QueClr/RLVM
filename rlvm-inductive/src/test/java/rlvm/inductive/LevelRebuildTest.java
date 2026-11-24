package rlvm.inductive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import rlvm.api.KernelException;
import rlvm.api.Reason;

/**
 * Finding L-F1 on single blocks, on the reference kernel (official level relation): which blocks the
 * official rebuild of levels touches ({@code lastRebuildChanged}), and what it decides. The official
 * verdicts of these and of 1,468 more fixtures are in {@code OfficialDifferentialTest} and
 * {@code OfficialLevelRebuildTest}.
 */
class LevelRebuildTest {

    private static Env admitPrior(Env e, Env.Block subject) {
        e.freeze();
        for (Env.Block b : e.blocks) {
            if (b == subject) {
                break;
            }
            e.admit(b);
        }
        return e;
    }

    @Test
    void spellingsTheOfficialConstructorsProduceAreNotTouched() {
        Env e = new Env(true);
        Env.Block nat = Fixtures.nat(e);
        Env.Block list = Fixtures.list(e);
        Env.Block prod = Fixtures.prod(e);
        Env.Block tree = Fixtures.tree(e);
        Env.Block psigma = e.inductive("u v", 2)
                .type("PSigma", "∀ (α : Sort u), (α → Sort v) → Sort (max (max 1 u) v)")
                .ctor("PSigma.mk", "∀ (α : Sort u) (β : α → Sort v) (fst : α), β fst → PSigma.{u,v} α β")
                .rec("PSigma.rec", "u_1 u v").end();
        e.freeze();
        for (Env.Block b : new Env.Block[] {nat, list, prod, tree, psigma}) {
            e.admit(b);
            assertFalse(e.admission.lastRebuildChanged(), "block " + e.names.toString(b.in.typeName[0]));
        }
    }

    @Test
    void aRespelledButEquivalentLevelIsAdmitted() {
        // imax u (v+1) is rebuilt into max u (v+1): another spelling, the same official normal form
        Env e = new Env(true);
        Env.Block b = e.inductive("u v", 1).type("T", "Sort (imax u (v+1)) → Sort (imax u (v+1))")
                .ctor("T.mk", "∀ (a : Sort (imax u (v+1))), a → T.{u,v} a").rec("T.rec", "u_1 u v").end();
        admitPrior(e, b).admit(b);
        assertTrue(e.admission.lastRebuildChanged());
    }

    @Test
    void aParameterOfSortImaxU1IsRejectedAsOfficially() {
        // imax u 1 is rebuilt into max u 1, which the official normal form does not identify with it:
        // "(kernel) application type mismatch  T a  argument has type Sort (imax u 1) but function has
        // type Sort (max u 1) → Sort (max u 1)"
        Env e = new Env(true);
        Env.Block b = e.inductive("u v w", 1).type("T", "Sort (imax u 1) → Sort (imax u 1)")
                .ctor("T.mk", "∀ (a : Sort (imax u 1)), a → T.{u,v,w} a").rec("T.rec", "u_1 u v w").end();
        admitPrior(e, b);
        assertEquals(Reason.APP_TYPE_MISMATCH, assertThrows(KernelException.class, () -> e.generate(b)).reason());
        assertTrue(e.admission.lastRebuildChanged());
        // a kernel that keeps the declared spelling (the behaviour before the fix) admits the block
        e.admission.keepDeclaredLevels = true;
        e.generate(b);
        assertFalse(e.admission.lastRebuildChanged());
    }

    @Test
    void aFieldWhoseTypeCarriesTheLevelIsRejectedAsOfficially() {
        // axiom F.{u} : Type; mk : F.{imax u 1} → T: the constructor the kernel refers to has the field
        // type F.{max u 1}, and F is not unfoldable
        Env e = new Env(true);
        e.axiom("F", "u", "Type");
        Env.Block b = e.inductive("u", 0).type("T", "Type").ctor("T.mk", "F.{(imax u 1)} → T.{u}").rec("T.rec", "u_1 u").end();
        admitPrior(e, b);
        assertEquals(Reason.APP_TYPE_MISMATCH, assertThrows(KernelException.class, () -> e.generate(b)).reason());
    }

    @Test
    void aPhantomUniverseOfADefinitionIsStillEqualAfterUnfolding() {
        Env e = new Env(true);
        e.axiom("G", "", "Type");
        e.def("Fd", "u", "Type", "G");
        Env.Block b = e.inductive("u", 0).type("T", "Type").ctor("T.mk", "Fd.{(imax u 1)} → T.{u}").rec("T.rec", "u_1 u").end();
        admitPrior(e, b).admit(b);
        assertTrue(e.admission.lastRebuildChanged());
    }

    @Test
    void theResultSortAloneIsNeverCompared() {
        Env e = new Env(true);
        Env.Block b = e.inductive("u", 0).type("T", "Sort (imax u 1)").ctor("T.mk", "T.{u}").rec("T.rec", "u_1 u").end();
        admitPrior(e, b).admit(b);
        assertTrue(e.admission.lastRebuildChanged());
    }
}
