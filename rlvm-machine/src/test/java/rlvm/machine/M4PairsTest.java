package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The pair table and UNDEC (ISA 1.5, 1.6, 5.4): one entry per unordered pair with status bits, keys
 * ordered unsigned, growth, reset in O(used), agreement with the reference probe of {@link TestTables},
 * UNDEC stamped per declaration. Owner: M4.
 */
@Tag("fast")
class M4PairsTest {

    @Test
    void statusBitsLiveInOneEntryOfTheUnorderedPair() {
        Machine m = new Machine();
        assertEquals(0, Pairs.status(m, 5, 9));
        Pairs.add(m, 5, 9, Layout.POS);
        assertEquals(Layout.POS, Pairs.status(m, 5, 9));
        assertEquals(Layout.POS, Pairs.status(m, 9, 5), "the key is unordered");
        Pairs.add(m, 9, 5, Layout.FAILED);
        assertEquals(Layout.POS | Layout.FAILED, Pairs.status(m, 5, 9));
        assertEquals(1, m.pairUsed, "one entry serves POS, NEG and FAILED");
        assertEquals(1, m.plogN);
        Pairs.add(m, 5, 10, Layout.NEG);
        assertEquals(Layout.NEG, Pairs.status(m, 10, 5));
        assertEquals(Layout.POS | Layout.FAILED, Pairs.status(m, 5, 9));
        assertEquals(0, Pairs.status(m, 9, 10));
        assertEquals(2, m.pairUsed);
    }

    /** ISA 1.5: the refs are ordered as unsigned numbers, so a pair with an immediate is a key like any other. */
    @Test
    void aPairKeyWithAnImmediate() {
        Machine m = new Machine();
        int lit3 = Layout.imm(3);
        int lit4 = Layout.imm(4);
        int rec = 7;
        Pairs.add(m, lit3, rec, Layout.NEG);
        assertEquals(Layout.NEG, Pairs.status(m, rec, lit3));
        assertEquals(Layout.NEG, Pairs.status(m, lit3, rec));
        assertEquals(0, Pairs.status(m, lit4, rec));
        assertEquals(0, Pairs.status(m, lit3, 8));
        // the record is the low word: as signed numbers the immediate would be the smaller one
        int o = (TestTables.findPair(m, lit3, rec)) << Layout.PAIR_SHIFT;
        assertEquals(rec, m.pair[o + Layout.PAIR_LO]);
        assertEquals(lit3, m.pair[o + Layout.PAIR_HI]);
        // two immediates, and the literal 0 (ref 0x80000000), are keys too
        Pairs.add(m, Layout.imm(0), lit4, Layout.POS);
        assertEquals(Layout.POS, Pairs.status(m, lit4, Layout.imm(0)));
        assertEquals(0, Pairs.status(m, Layout.imm(0), lit3));
        assertEquals(2, m.pairUsed);
    }

    @Test
    void agreesWithTheReferenceProbeOfTheFrozenFormat() {
        Machine m = new Machine(Machine.Sizes.tiny());
        Random rnd = new Random(42);
        int[][] keys = new int[500][];
        for (int i = 0; i < keys.length; i++) {
            int a = ref(rnd);
            int b = ref(rnd);
            keys[i] = new int[] {a, b, 1 << rnd.nextInt(3)};
            if ((i & 1) == 0) {
                Pairs.add(m, a, b, keys[i][2]);
            } else {
                TestTables.pairAdd(m, a, b, keys[i][2]);
            }
        }
        assertTrue(m.pairMask + 1 >= 2 * m.pairUsed, "load at most 0.5 after growth");
        for (int[] k : keys) {
            int st = Pairs.status(m, k[0], k[1]);
            assertTrue((st & k[2]) != 0, "the bits that were added are present");
            assertEquals(TestTables.pairStatus(m, k[0], k[1]), st);
            assertEquals(st, Pairs.status(m, k[1], k[0]));
        }
        assertEquals(m.pairUsed, m.plogN, "every entry is logged once");
    }

    private static int ref(Random rnd) {
        // record refs and immediates, never NONE
        return rnd.nextInt(4) == 0 ? Layout.imm(rnd.nextInt(50)) : 2 + rnd.nextInt(60);
    }

    @Test
    void resetForgetsEveryPairAndTouchesOnlyTheLoggedEntries() {
        Machine m = new Machine();
        Pairs.add(m, 11, 12, Layout.POS);
        Pairs.add(m, 12, Layout.imm(1), Layout.NEG);
        int e1 = TestTables.findPair(m, 11, 12);
        m.reset();
        assertEquals(0, m.pairUsed);
        assertEquals(0, m.plogN);
        assertEquals(0, Pairs.status(m, 11, 12));
        assertEquals(0, Pairs.status(m, Layout.imm(1), 12));
        assertEquals(0, m.pair[e1 << Layout.PAIR_SHIFT]);
        Pairs.add(m, 11, 12, Layout.FAILED);
        assertEquals(Layout.FAILED, Pairs.status(m, 12, 11), "a status of the old session does not come back");
    }

    @Test
    void noneIsNeverAKey() {
        Machine m = new Machine();
        Pairs.add(m, Layout.NONE, 9, Layout.POS);
        assertEquals(0, m.pairUsed);
        assertEquals(0, Pairs.status(m, Layout.NONE, 9));
        assertEquals(0, Pairs.status(m, 9, Layout.NONE));
    }

    /** ISA 5.4: UNDEC only remembers the largest budget of a probe that ended in U, for one declaration. */
    @Test
    void undecIsStampedByTheDeclarationAndKeepsTheLargestBudget() {
        Machine m = new Machine();
        assertEquals(0, Pairs.undec(m, 20, 21));
        Pairs.setUndec(m, 20, 21, 8);
        assertEquals(8, Pairs.undec(m, 21, 20));
        Pairs.setUndec(m, 21, 20, 14);
        assertEquals(14, Pairs.undec(m, 20, 21));
        Pairs.setUndec(m, 20, 21, 10);
        assertEquals(14, Pairs.undec(m, 20, 21), "a smaller budget does not replace a larger one");
        assertEquals(0, Pairs.undec(m, 20, 22));
        assertEquals(0, Pairs.status(m, 20, 21), "a U never enters the pair table (no FAILED, no NEG)");
        m.beginDecl(1, 0, Op.FUEL_UNLIMITED, Long.MAX_VALUE);
        assertEquals(0, Pairs.undec(m, 20, 21), "declaration scope");
        Pairs.setUndec(m, 20, 21, 8);
        assertEquals(8, Pairs.undec(m, 20, 21));
    }

    @Test
    void budgetSizeClasses() {
        assertEquals(8, Pairs.log2(Op.B_PAIR));
        assertEquals(10, Pairs.log2(Op.B_SAMEHEAD_ABBREV));
        assertEquals(14, Pairs.log2(Op.B_SAMEHEAD));
        assertEquals(11, Pairs.log2(1025));
        assertEquals(1, Pairs.log2(1));
    }
}
