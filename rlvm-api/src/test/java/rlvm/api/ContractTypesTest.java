package rlvm.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import rlvm.store.IntLists;

/** The value types of the facade: verdict vocabulary, budgets, engine options, default methods. */
class ContractTypesTest {

    @Test
    void reasonsSplitIntoRejectAndDecline() {
        boolean declines = false;
        for (Reason r : Reason.values()) {
            if (!r.isReject()) {
                declines = true;
            } else {
                assertFalse(declines, "reject reasons come first: " + r);
            }
            assertEquals(r.isReject() ? Outcome.REJECT : Outcome.DECLINE, r.outcome());
            if (r.isRetryable()) {
                assertFalse(r.isReject(), "a retryable reason is never a rejection");
            }
        }
        assertFalse(Reason.CANCELLED.isReject());
        assertEquals(Outcome.DECLINE, Reason.CANCELLED.outcome());
        assertTrue(Reason.CANCELLED.isRetryable());
        assertTrue(Reason.BUDGET_MEMORY.isRetryable());
        assertFalse(Reason.UNSUPPORTED_PRELUDE.isRetryable());
        assertFalse(Reason.INTERNAL_ERROR.isRetryable());
        // the numbering of the first skeleton is unchanged (CANCELLED was appended)
        assertEquals(0, Reason.MALFORMED_EXPORT.ordinal());
        assertEquals(17, Reason.QUOT_MISMATCH.ordinal());
        assertEquals(18, Reason.BUDGET_STEPS.ordinal());
        assertEquals(26, Reason.INTERNAL_ERROR.ordinal());
        assertEquals(27, Reason.CANCELLED.ordinal());
    }

    @Test
    void exceptionsCarryOnlyReasonsOfTheirClass() {
        KernelException k = new KernelException(Reason.APP_TYPE_MISMATCH, 5, 6);
        assertEquals(Reason.APP_TYPE_MISMATCH, k.reason());
        assertEquals(5, k.term1());
        assertEquals(6, k.term2());
        assertEquals(0, k.getStackTrace().length, "rejections are ordinary results: no stack trace");
        assertThrows(IllegalArgumentException.class, () -> new KernelException(Reason.BUDGET_STEPS));
        assertThrows(IllegalArgumentException.class, () -> new KernelException(Reason.CANCELLED));

        ResourceException r = new ResourceException(Reason.CANCELLED);
        assertEquals(Reason.CANCELLED, r.reason());
        assertEquals(0, r.getStackTrace().length);
        assertThrows(IllegalArgumentException.class, () -> new ResourceException(Reason.NAT_LITERAL_TOO_BIG));
    }

    @Test
    void warmBudgetHasNoStepLimit() {
        Budget warm = Budget.warm();
        assertEquals(Budget.UNLIMITED, warm.maxSteps);
        assertFalse(warm.hasStepLimit());
        assertEquals(Budget.WARM_MAX_BYTES, warm.maxBytes);
        assertEquals(Budget.WARM_MAX_DEPTH, warm.maxDepth);
        assertSame(warm, Budget.warm(), "the accept path allocates nothing");

        Budget cold = Budget.cold(123);
        assertEquals(Budget.UNLIMITED, cold.maxSteps);
        assertEquals(123, cold.maxBytes);
        assertEquals(Integer.MAX_VALUE, cold.maxDepth);

        Budget strict = Budget.strict(1000, 77);
        assertTrue(strict.hasStepLimit());
        assertEquals(1000, strict.maxSteps);
        assertEquals(77, strict.maxBytes);

        Budget b = warm.withMaxSteps(5).withMaxBytes(6).withMaxDepth(7);
        assertEquals(5, b.maxSteps);
        assertEquals(6, b.maxBytes);
        assertEquals(7, b.maxDepth);
        assertEquals(Budget.UNLIMITED, warm.maxSteps, "budgets are immutable");
        assertTrue(warm.toString().contains("steps=unlimited"));
    }

    @Test
    void engineConfigWithers() {
        EngineConfig c = EngineConfig.defaults();
        assertEquals(EngineConfig.KernelProfile.LEAN_4_36, c.profile);
        assertFalse(c.strict || c.memoCheck || c.truffleTier || c.stats);
        assertEquals(1, c.workers);

        EngineConfig s = c.withStrict(true);
        assertTrue(s.strict);
        assertFalse(c.strict, "immutable");
        EngineConfig all = c.withProfile(EngineConfig.KernelProfile.LEAN_4_37).withStrict(true).withMemoCheck(true)
                .withTruffleTier(true).withStats(true).withWorkers(6);
        assertEquals(EngineConfig.KernelProfile.LEAN_4_37, all.profile);
        assertTrue(all.strict && all.memoCheck && all.truffleTier && all.stats);
        assertEquals(6, all.workers);
        // each wither changes one field only
        EngineConfig back = all.withStrict(false);
        assertTrue(back.memoCheck && back.truffleTier && back.stats);
        assertEquals(6, back.workers);
        assertEquals(EngineConfig.KernelProfile.LEAN_4_37, back.profile);
        assertTrue(all.toString().contains("strict"));
        // the six-argument constructor of the first skeleton still exists
        EngineConfig old = new EngineConfig(EngineConfig.KernelProfile.LEAN_4_36, true, false, true, false, 3);
        assertTrue(old.strict && old.truffleTier);
        assertEquals(3, old.workers);
    }

    @Test
    void outcomeCodesAreTheExitCodes() {
        assertEquals(0, Outcome.ACCEPT);
        assertEquals(1, Outcome.REJECT);
        assertEquals(2, Outcome.DECLINE);
        assertEquals(Outcome.ACCEPT, Outcome.EXIT_ACCEPT);
        assertEquals(Outcome.REJECT, Outcome.EXIT_REJECT);
        assertEquals(Outcome.DECLINE, Outcome.EXIT_DECLINE);
        assertEquals("accept", Outcome.name(0));
        assertEquals("invalid(7)", Outcome.name(7));
    }

    /**
     * The engine-option channel (wave 4): a provider written against the first skeleton (four abstract
     * methods) still compiles, declares no settings and ignores any it is handed; a provider that
     * declares keys gets the settings in factory(Map).
     */
    @Test
    void engineProviderOptionChannelIsOptional() {
        EngineFactory plain = (store, config) -> null;
        EngineProvider old = new EngineProvider() {
            @Override
            public String name() {
                return "old";
            }

            @Override
            public int priority() {
                return 0;
            }

            @Override
            public boolean isReference() {
                return false;
            }

            @Override
            public EngineFactory factory() {
                return plain;
            }
        };
        assertTrue(old.optionKeys().isEmpty());
        assertEquals(plain, old.factory(java.util.Map.of("levels", "official")), "unknown settings are ignored");
        // Added in wave 7 (request of the machine integrator: a session cap of its own): a provider
        // written before it prefers nothing, whatever the engine settings of the run are.
        assertTrue(old.runDefaults(java.util.Map.of()).isEmpty());
        assertTrue(old.runDefaults(java.util.Map.of("machine.switches", "warm")).isEmpty());

        EngineFactory tuned = (store, config) -> null;
        EngineProvider withOptions = new EngineProvider() {
            @Override
            public String name() {
                return "tunable";
            }

            @Override
            public int priority() {
                return 0;
            }

            @Override
            public boolean isReference() {
                return true;
            }

            @Override
            public EngineFactory factory() {
                return plain;
            }

            @Override
            public java.util.Set<String> optionKeys() {
                return java.util.Set.of("levels");
            }

            @Override
            public EngineFactory factory(java.util.Map<String, String> options) {
                String v = options.get("levels");
                if (v == null) {
                    return plain;
                }
                if (!v.equals("official") && !v.equals("complete")) {
                    throw new IllegalArgumentException("-X levels=" + v + ": expected official or complete");
                }
                return tuned;
            }
        };
        assertEquals(java.util.Set.of("levels"), withOptions.optionKeys());
        assertEquals(plain, withOptions.factory(java.util.Map.of()));
        assertEquals(tuned, withOptions.factory(java.util.Map.of("levels", "official", "machine.tier", "k0")));
        assertThrows(IllegalArgumentException.class, () -> withOptions.factory(java.util.Map.of("levels", "x")));
    }

    @Test
    void runSignalsNoneNeverCancels() {
        assertEquals(Integer.MAX_VALUE, RunSignals.NONE.firstFailure());
        assertFalse(RunSignals.NONE.memoryExhausted());
    }

    /** A fake written against the first skeleton (only its abstract methods) must still compile and say what is missing. */
    @Test
    void addedInterfaceMethodsHaveDefaults() {
        LevelOps old = new LevelOps() {
            @Override
            public int zero() {
                return 1;
            }

            @Override
            public int succ(int level) {
                return 0;
            }

            @Override
            public int max(int a, int b) {
                return 0;
            }

            @Override
            public int imax(int a, int b) {
                return 0;
            }

            @Override
            public int param(int nameId) {
                return 0;
            }

            @Override
            public int mkMax(int a, int b) {
                return 0;
            }

            @Override
            public int mkIMax(int a, int b) {
                return 0;
            }

            @Override
            public boolean isEquiv(int a, int b) {
                return a == b;
            }

            @Override
            public boolean isGeq(int a, int b) {
                return false;
            }

            @Override
            public boolean isAlwaysZero(int level) {
                return false;
            }

            @Override
            public boolean isNeverZero(int level) {
                return false;
            }

            @Override
            public boolean listsEquiv(int listA, int listB) {
                return false;
            }

            @Override
            public int instantiate(int level, int paramNames, int levels) {
                return level;
            }

            @Override
            public int instantiateList(int levelList, int paramNames, int levels) {
                return levelList;
            }

            @Override
            public boolean paramsDeclared(int level, int paramNames) {
                return true;
            }

            @Override
            public IntLists lists() {
                return null;
            }

            @Override
            public void reset() {}
        };
        assertTrue(assertThrows(UnsupportedOperationException.class, () -> old.mkSubst(1, 2)).getMessage()
                .contains("mkSubst"));
        assertThrows(UnsupportedOperationException.class, () -> old.instantiate(1, 2));
        assertThrows(UnsupportedOperationException.class, () -> old.instantiateList(1, 2));
        assertThrows(UnsupportedOperationException.class, () -> old.kind(1));
        assertTrue(old.isZero(1));
        assertFalse(old.isZero(2));
        assertEquals("level#5", old.toString(5));
        assertEquals(0, old.footprintBytes());

        NatOps nat = new NatOps() {
            @Override
            public int binary(int op, int litA, int litB) {
                return 0;
            }

            @Override
            public boolean test(int op, int litA, int litB) {
                return false;
            }

            @Override
            public int succ(int lit) {
                return 0;
            }

            @Override
            public int pred(int lit) {
                return 0;
            }

            @Override
            public boolean isZero(int lit) {
                return false;
            }

            @Override
            public boolean natEquals(int litA, int litB) {
                return false;
            }

            @Override
            public int stringLength(int strLit) {
                return 0;
            }

            @Override
            public int stringCodePoint(int strLit, int index) {
                return 0;
            }

            @Override
            public boolean stringEquals(int strLitA, int strLitB) {
                return false;
            }
        };
        nat.checkLiteralSize(1);
        nat.reset();
        assertEquals(0, nat.footprintBytes());
        assertEquals("nat#3", nat.natToString(3));
        assertThrows(UnsupportedOperationException.class, () -> nat.literal(0));
        assertNull(old.lists());
        // operation codes are those of Specials.NAT_ADD .. NAT_SHIFT_RIGHT minus NAT_ADD
        assertEquals(rlvm.store.Specials.NAT_SUB - rlvm.store.Specials.NAT_ADD, NatOps.SUB);
        assertEquals(rlvm.store.Specials.NAT_POW - rlvm.store.Specials.NAT_ADD, NatOps.POW);
        assertEquals(rlvm.store.Specials.NAT_BLE - rlvm.store.Specials.NAT_ADD, NatOps.BLE);
        assertEquals(rlvm.store.Specials.NAT_SHIFT_RIGHT - rlvm.store.Specials.NAT_ADD, NatOps.SHIFT_RIGHT);
    }
}
