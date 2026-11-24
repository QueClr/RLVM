package rlvm.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class MachineErrorsTest {

    @Test
    void everyDeclaredCodeIsDefinedOnceAndNamedAfterItsField() throws Exception {
        Set<Integer> seen = new HashSet<>();
        int n = 0;
        for (Field f : MachineErrors.class.getFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != int.class || !f.getName().startsWith("E_")) {
                continue;
            }
            int code = f.getInt(null);
            assertTrue(seen.add(code), "duplicate code " + code);
            if (code == MachineErrors.E_NONE) {
                assertFalse(MachineErrors.isDefined(code));
                continue;
            }
            n++;
            assertTrue(MachineErrors.isDefined(code), f.getName());
            assertEquals(f.getName(), MachineErrors.name(code));
            assertTrue(code < MachineErrors.COUNT);
            // the three classes partition the codes
            int classes = (MachineErrors.isKernelError(code) ? 1 : 0) + (MachineErrors.isLimit(code) ? 1 : 0)
                    + (code >= MachineErrors.E_HOLE ? 1 : 0);
            assertEquals(1, classes, f.getName());
            // REJECT exactly for kernel errors
            assertEquals(MachineErrors.isKernelError(code), MachineErrors.reason(code).isReject(), f.getName());
            assertEquals(MachineErrors.isKernelError(code) ? Outcome.REJECT : Outcome.DECLINE,
                    MachineErrors.outcome(code));
            assertNotEquals(MachineErrors.isFatal(code), MachineErrors.isRecoverableInTry(code));
        }
        assertEquals(23, n);
    }

    @Test
    void mappingOfTheReconciliation() {
        assertEquals(Reason.TYPE_EXPECTED, MachineErrors.reason(MachineErrors.E_TYPE_EXPECTED));
        assertEquals(Reason.FUNCTION_EXPECTED, MachineErrors.reason(MachineErrors.E_FUN_EXPECTED));
        assertEquals(Reason.APP_TYPE_MISMATCH, MachineErrors.reason(MachineErrors.E_APP_MISMATCH));
        assertEquals(Reason.LET_TYPE_MISMATCH, MachineErrors.reason(MachineErrors.E_LET_MISMATCH));
        assertEquals(Reason.DECL_TYPE_MISMATCH, MachineErrors.reason(MachineErrors.E_TYPE_MISMATCH));
        assertEquals(Reason.INVALID_PROJECTION, MachineErrors.reason(MachineErrors.E_PROJ));
        assertEquals(Reason.UNKNOWN_CONSTANT, MachineErrors.reason(MachineErrors.E_UNKNOWN_CONST));
        assertEquals(Reason.LEVEL_ARITY_MISMATCH, MachineErrors.reason(MachineErrors.E_LEVEL_ARITY));
        assertEquals(Reason.UNDECLARED_LEVEL_PARAM, MachineErrors.reason(MachineErrors.E_LEVEL_PARAM));
        assertEquals(Reason.THEOREM_NOT_PROP, MachineErrors.reason(MachineErrors.E_NOT_PROP));
        assertEquals(Reason.LOOSE_BVAR, MachineErrors.reason(MachineErrors.E_LOOSE_BVAR));
        // The Nat-size guard mirrors the official REJECT.
        assertEquals(Reason.NAT_LITERAL_TOO_BIG, MachineErrors.reason(MachineErrors.E_NATSIZE));
        assertTrue(MachineErrors.isRecoverableInTry(MachineErrors.E_NATSIZE));

        assertEquals(Reason.BUDGET_STEPS, MachineErrors.reason(MachineErrors.E_STEPS));
        assertEquals(Reason.BUDGET_MEMORY, MachineErrors.reason(MachineErrors.E_MEM));
        assertEquals(Reason.STACK_DEPTH, MachineErrors.reason(MachineErrors.E_STACK));
        assertEquals(Reason.UNSUPPORTED_INPUT, MachineErrors.reason(MachineErrors.E_LIMIT));
        assertEquals(Reason.UNSUPPORTED_PRELUDE, MachineErrors.reason(MachineErrors.E_PRELUDE));
        assertEquals(Reason.CANCELLED, MachineErrors.reason(MachineErrors.E_CANCEL));
        for (int fatal : new int[] {MachineErrors.E_HOLE, MachineErrors.E_VIS, MachineErrors.E_ILLTYPED,
            MachineErrors.E_INTERNAL, MachineErrors.E_AUDIT}) {
            assertEquals(Reason.INTERNAL_ERROR, MachineErrors.reason(fatal));
            assertTrue(MachineErrors.isFatal(fatal));
        }

        // Faults inside TRY: recoverable = kernel fail, budget (steps), E_NATSIZE, E_LIMIT; the rest is fatal.
        assertTrue(MachineErrors.isRecoverableInTry(MachineErrors.E_STEPS));
        assertTrue(MachineErrors.isRecoverableInTry(MachineErrors.E_LIMIT));
        assertTrue(MachineErrors.isRecoverableInTry(MachineErrors.E_APP_MISMATCH));
        assertTrue(MachineErrors.isFatal(MachineErrors.E_MEM));
        assertTrue(MachineErrors.isFatal(MachineErrors.E_CANCEL));
        assertTrue(MachineErrors.isFatal(MachineErrors.E_STACK));
        assertTrue(MachineErrors.isFatal(MachineErrors.E_PRELUDE));
    }

    @Test
    void unknownCodesFailClosed() {
        for (int code : new int[] {-1, 13, 14, 15, 21, 23, MachineErrors.COUNT, 1000, Integer.MAX_VALUE,
            Integer.MIN_VALUE}) {
            assertFalse(MachineErrors.isDefined(code));
            assertEquals(Reason.INTERNAL_ERROR, MachineErrors.reason(code), "code " + code);
            assertEquals(Outcome.DECLINE, MachineErrors.outcome(code));
            assertTrue(MachineErrors.isFatal(code));
            assertFalse(MachineErrors.isKernelError(code) && MachineErrors.isDefined(code));
            assertTrue(MachineErrors.name(code).startsWith("E_?"));
        }
        assertEquals("E_NONE", MachineErrors.name(MachineErrors.E_NONE));
        assertEquals(Reason.INTERNAL_ERROR, MachineErrors.reason(MachineErrors.E_NONE));
    }
}
