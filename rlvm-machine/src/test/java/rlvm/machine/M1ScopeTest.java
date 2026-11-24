package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import rlvm.api.MachineErrors;

/**
 * M1 acceptance: binders (ISA 2.8, 4.1 OPEN / CLOSE): a binder takes the first variable of its type that is
 * not in scope, so a re-opened binder reproduces every id below it (I-FV1, I-FV2); CLOSE restores the scope
 * state; the cut closes everything above a mark.
 */
@Tag("fast")
class M1ScopeTest {

    @Test
    void openAndCloseReproduceIds() {
        Machine m = new Machine(Machine.Sizes.tiny());
        int nat = Values.mkSort(m, 3);
        int bool = Values.mkSort(m, 4);
        int x0 = Scope.open(m, nat);
        int x1 = Scope.open(m, nat);
        int b0 = Scope.open(m, bool);
        int x2 = Scope.open(m, nat);
        assertEquals(4, m.ssp);
        assertEquals(Values.mkFVar(m, nat, 0), x0);
        assertEquals(Values.mkFVar(m, nat, 1), x1);
        assertEquals(Values.mkFVar(m, nat, 2), x2);
        assertEquals(Values.mkFVar(m, bool, 0), b0);
        assertTrue(Scope.inScope(m, x0) && Scope.inScope(m, x1) && Scope.inScope(m, x2) && Scope.inScope(m, b0));
        assertEquals(nat, Values.a(m, x1), "the type is part of the variable's identity (I-FV1)");
        assertEquals(1, Values.b(m, x1));
        Scope.close(m, 4);
        assertEquals(0, m.ssp);
        assertFalse(Scope.inScope(m, x0) || Scope.inScope(m, x1) || Scope.inScope(m, x2) || Scope.inScope(m, b0));
        // the same binders again: the same ids, no new record
        int records = m.vp;
        assertEquals(x0, Scope.open(m, nat));
        assertEquals(x1, Scope.open(m, nat));
        assertEquals(b0, Scope.open(m, bool));
        assertEquals(x2, Scope.open(m, nat));
        assertEquals(records, m.vp, "a re-opened binder allocates nothing");
        // another order of types gives the same variables per type
        Scope.close(m, 4);
        assertEquals(b0, Scope.open(m, bool));
        assertEquals(x0, Scope.open(m, nat));
        int b1 = Scope.open(m, bool);
        assertEquals(Values.mkFVar(m, bool, 1), b1);
        assertNotEquals(b0, b1, "no two open binders hold the same variable (I-FV2)");
        assertEquals(x1, Scope.open(m, nat));
        // partial close and re-open: the ids below are untouched, the ones above are reproduced
        Scope.close(m, 2);
        assertTrue(Scope.inScope(m, b0) && Scope.inScope(m, x0));
        assertFalse(Scope.inScope(m, b1) || Scope.inScope(m, x1));
        assertEquals(x1, Scope.open(m, nat));
        assertEquals(x2, Scope.open(m, nat));
        assertEquals(b1, Scope.open(m, bool));
        // the cut of a speculative segment: everything above the mark
        Scope.closeTo(m, 1);
        assertEquals(1, m.ssp);
        assertTrue(Scope.inScope(m, b0));
        assertFalse(Scope.inScope(m, x0));
        assertEquals(x0, Scope.open(m, nat));
        m.closeBindersAbove(0);
        assertEquals(0, m.ssp);
    }

    /** A random walk of opens and closes over three types against a model: the variable is always FVAR(T, #open of T). */
    @Test
    void randomScopesAgainstAModel() {
        Machine m = new Machine(Machine.Sizes.tiny());
        int[] types = {Values.mkSort(m, 1), Values.mkSort(m, 2), Values.mkSort(m, 3)};
        int[] open = new int[3];
        int[] stackType = new int[4096];
        int[] stackVar = new int[4096];
        int sp = 0;
        Random rnd = new Random(11);
        for (int step = 0; step < 20000; step++) {
            if (sp < 300 && (sp == 0 || rnd.nextInt(100) < 55)) {
                int t = rnd.nextInt(3);
                int x = Scope.open(m, types[t]);
                assertEquals(Values.mkFVar(m, types[t], open[t]), x, "step " + step);
                assertTrue(Scope.inScope(m, x));
                stackType[sp] = t;
                stackVar[sp++] = x;
                open[t]++;
            } else {
                int n = 1 + rnd.nextInt(Math.min(sp, 4));
                for (int i = 0; i < n; i++) {
                    open[stackType[--sp]]--;
                }
                Scope.close(m, n);
                assertFalse(Scope.inScope(m, stackVar[sp]));
            }
            assertEquals(sp, m.ssp);
        }
        for (int i = 0; i < sp; i++) {
            assertTrue(Scope.inScope(m, stackVar[i]));
        }
    }

    @Test
    void variablesAreReproducedAcrossDeclarationsButNotAcrossSessions() {
        Machine m = new Machine();
        int nat = Values.mkSort(m, 3);
        int x0 = Scope.open(m, nat);
        int x1 = Scope.open(m, nat);
        // a declaration that ends with open binders (a failure): beginDecl closes them
        m.beginDecl(1, 0, Op.FUEL_UNLIMITED, Long.MAX_VALUE);
        assertEquals(0, m.ssp);
        assertFalse(Scope.inScope(m, x0) || Scope.inScope(m, x1));
        assertEquals(x0, Scope.open(m, nat), "a later declaration of the session meets the same FVAR(Nat, 0)");
        assertEquals(x1, Scope.open(m, nat));
        Scope.close(m, 2);
        // a session reset forgets the records and the FV0 cache
        m.reset();
        int nat2 = Values.mkSort(m, 3);
        int y0 = Scope.open(m, nat2);
        assertEquals(Layout.FVAR, Values.kind(m, y0));
        assertEquals(nat2, Values.a(m, y0));
        assertEquals(0, Values.b(m, y0));
        assertTrue(Scope.inScope(m, y0));
        assertEquals(1, m.ssp);
    }

    @Test
    void theBinderLimitIsARecoverableLimit() {
        Machine m = new Machine();
        int nat = Values.mkSort(m, 3);
        for (int i = 0; i < Op.BINDERS_MAX; i++) {
            int x = Scope.open(m, nat);
            assertTrue(x < Op.SIG);
        }
        assertEquals(Op.ABORT, Scope.open(m, nat));
        assertEquals(MachineErrors.E_LIMIT, m.err);
        assertEquals(Op.BINDERS_MAX, m.ssp);
        Scope.closeTo(m, 0);
        assertEquals(Values.mkFVar(m, nat, 0), Scope.open(m, nat));
    }
}
