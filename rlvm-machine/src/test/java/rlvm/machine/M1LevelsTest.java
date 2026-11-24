package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import rlvm.api.MachineErrors;
import rlvm.store.IntLists;

/**
 * M1: universe levels in the machine (ISA 2.7, 4.4 LVLEQ, 3.3): LSUB behind its cache, level and level-list
 * equivalence (never by id alone), and the structural level codec of published records (step 2).
 */
@Tag("fast")
class M1LevelsTest {

    static Fixture f;
    static Machine m;

    @BeforeAll
    static void prelude() {
        f = Fixture.prelude0();
        m = f.m;
    }

    @Test
    void lsubInstantiatesWithTheSimplifyingConstructorsBehindACache() {
        int head = Values.mkConst(m, f.cid("c1"), f.levels("1", "(succ w)"));
        int ls = Levels.substOf(m, head); // u := 1, v := w + 1
        assertTrue(ls > 0);
        assertEquals(f.level("1"), Levels.lsub(m, f.level("u"), ls));
        assertEquals(f.level("(succ w)"), Levels.lsub(m, f.level("v"), ls));
        assertEquals(f.level("(succ (succ w))"), Levels.lsub(m, f.level("(succ v)"), ls));
        assertEquals(f.level("z"), Levels.lsub(m, f.level("z"), ls), "an unknown parameter stays");
        assertEquals(f.level("3"), Levels.lsub(m, f.level("3"), ls), "a closed level is itself");
        // the official instantiate rebuilds max / imax with mk_max / mk_imax (R02 3.3): imax u (v + 1) -> max
        int inst = Levels.lsub(m, f.level("(imax u v)"), ls);
        assertEquals(m.levels.instantiate(f.level("(imax u v)"), ls), inst, "the cache is transparent");
        assertTrue(Levels.lvlEq(m, inst, f.level("(max 1 (succ w))")));
        // second calls come from the cache and are equal
        for (int i = 0; i < 3; i++) {
            assertEquals(f.level("1"), Levels.lsub(m, f.level("u"), ls));
            assertEquals(inst, Levels.lsub(m, f.level("(imax u v)"), ls));
        }
        // identity substitution
        assertEquals(f.level("(imax u v)"), Levels.lsub(m, f.level("(imax u v)"), 0));
        // lists
        int list = f.levels("u", "(succ v)", "0");
        int out = Levels.lsubList(m, list, ls);
        assertEquals(f.levels("1", "(succ (succ w))", "0"), out);
        assertEquals(out, Levels.lsubList(m, list, ls));
        assertEquals(list, Levels.lsubList(m, list, 0));
        assertEquals(IntLists.EMPTY, Levels.lsubList(m, IntLists.EMPTY, ls));
        assertEquals(Op.ABORT, Levels.lsubList(m, list, -1));
        assertEquals(MachineErrors.E_LEVEL_ARITY, m.err);
        m.err = 0;
        // KSORT / KCONST composition
        assertEquals(Values.mkSort(m, f.level("1")), Values.mkSort(m, Levels.lsub(m, f.level("u"), ls)));
        assertEquals(Values.mkConst(m, f.cid("id"), f.levels("1")),
                Values.mkConst(m, f.cid("id"), Levels.lsubList(m, f.levels("u"), ls)));
        assertEquals(Op.ABORT, Values.mkSort(m, Levels.lsub(m, f.level("u"), -1)), "the signal passes through KSORT");
        assertEquals(Op.ABORT, Values.mkConst(m, f.cid("id"), Levels.lsubList(m, f.levels("u"), -1)));
        m.err = 0;
    }

    @Test
    void lsubCacheDoesNotSurviveASessionReset() {
        Fixture g = Fixture.prelude0();
        Machine t = g.m;
        int ls = Levels.substOf(t, Values.mkConst(t, g.cid("id"), g.levels("5")));
        assertEquals(g.level("5"), Levels.lsub(t, g.level("u"), ls));
        int cellsBefore = nonEmpty(t.lsub, t.epoch);
        assertTrue(cellsBefore >= 1);
        t.reset();
        assertEquals(0, nonEmpty(t.lsub, t.epoch), "every cell is stamped with the dead epoch");
        g.close();
    }

    private static int nonEmpty(int[] cells, int epoch) {
        int n = 0;
        for (int i = 0; i < cells.length; i += Layout.CELL_WORDS) {
            if (cells[i + Layout.LSUB_EPOCH] == epoch && cells[i + Layout.LSUB_KEY] != 0) {
                n++;
            }
        }
        return n;
    }

    /** ISA 2.7 / 4.4 LVLEQ: levels and level lists are compared with isEquiv, never by id alone. */
    @Test
    void levelEquivalenceIsNotIdEquality() {
        int a = f.level("(max u v)");
        int b = f.level("(max v u)");
        assertNotEquals(a, b);
        assertTrue(Levels.lvlEq(m, a, b));
        assertTrue(Levels.lvlEq(m, a, a));
        assertFalse(Levels.lvlEq(m, a, f.level("u")));
        assertTrue(Levels.lvlEq(m, f.level("(imax u 0)"), f.level("0")), "bugs/level-imax-normalization");
        assertTrue(Levels.lvlEq(m, f.level("(max u u)"), f.level("u")));
        int la = f.levels("(max u v)", "1");
        int lb = f.levels("(max v u)", "(succ 0)");
        assertTrue(Levels.listsEq(m, la, lb));
        assertTrue(Levels.listsEq(m, la, la));
        assertFalse(Levels.listsEq(m, la, f.levels("(max v u)", "2")));
        assertFalse(Levels.listsEq(m, la, f.levels("(max v u)")), "different lengths");
        assertFalse(Levels.listsEq(m, la, IntLists.EMPTY));
        assertTrue(Levels.listsEq(m, IntLists.EMPTY, IntLists.EMPTY));
        // the SORT records differ, the levels are equivalent: CONV must use LVLEQ on them
        assertNotEquals(Values.mkSort(m, a), Values.mkSort(m, b));
    }

    /**
     * Owner decision of 2026-11-24 (OWNER-NOTES item 11): by default levels are compared as the official kernel
     * compares them (identity of the official normal forms: sound, incomplete); the complete relation only with
     * the switch {@code levels-complete}. The pairs are those of the arena's corner cases
     * {@code imax-right-successor} and {@code imax-nested-absorption}: semantically equal, officially different.
     */
    @Test
    void levelsAreComparedAsTheOfficialKernelDoesByDefault() {
        Fixture g = Fixture.prelude0();
        Machine t = g.m;
        String[][] pairs = {
            {"(imax u 1)", "(max u 1)"},
            {"(imax u (succ v))", "(max u (succ v))"},
            {"(imax (imax u v) v)", "(imax u v)"},
            {"(imax v (imax u v))", "(imax u v)"},
            {"(max u v)", "(max v u)"},
            {"(imax u 0)", "0"},
            {"(max u u)", "u"},
            {"(succ (max u v))", "(max (succ u) (succ v))"},
            {"u", "v"},
        };
        int officialOnlyDifferent = 0;
        for (String[] p : pairs) {
            int a = g.level(p[0]);
            int b = g.level(p[1]);
            boolean official = t.levels.isEquivOfficial(a, b);
            boolean complete = t.levels.isEquiv(a, b);
            assertTrue(complete || !official, p[0] + " ~ " + p[1] + ": the official relation is a subset of the complete one");
            assertEquals(official, Levels.lvlEq(t, a, b), p[0] + " ~ " + p[1] + ": default = official");
            assertEquals(official, Levels.listsEq(t, g.levels(p[0], "u"), g.levels(p[1], "u")), "lists, default = official");
            assertEquals(official, Levels.listsEq(t, g.levels("u", p[0]), g.levels("u", p[1])), "lists, second position");
            g.switches("levels-complete");
            assertEquals(complete, Levels.lvlEq(t, a, b), p[0] + " ~ " + p[1] + ": levels-complete = the complete relation");
            assertEquals(complete, Levels.listsEq(t, g.levels(p[0], "u"), g.levels(p[1], "u")), "lists, levels-complete");
            g.switches("none");
            assertEquals(official, Levels.lvlEq(t, a, b), "the switch is off again");
            if (complete && !official) {
                officialOnlyDifferent++;
            }
        }
        assertTrue(officialOnlyDifferent >= 1, "at least one pair of the corner cases separates the two relations");
        assertFalse(Levels.listsEq(t, g.levels("u"), g.levels("u", "u")), "different lengths");
        assertFalse(Levels.listsEq(t, g.levels("u"), IntLists.EMPTY));
        assertTrue(Levels.listsEq(t, IntLists.EMPTY, IntLists.EMPTY));
        g.close();
    }

    /** ISA 3.3 (staging step 2): a derived level is published structurally and re-interned per session. */
    @Test
    void structuralLevelCodecRoundTrips() {
        String[] levels = {"0", "1", "5", "u", "(succ u)", "(max u v)", "(imax u v)", "(max (succ u) (imax v (succ (succ w))))",
            "(imax (max u 3) (max (succ v) (imax w u)))", "(succ (succ (succ (max u (succ v)))))"};
        int[] buf = new int[256];
        int xp = m.xp;
        for (String text : levels) {
            int l = f.level(text);
            int n = Levels.encodeLevel(m, l, buf, 3);
            assertTrue(n > 1, text);
            assertEquals(n - 1, buf[3], "the first word is the length of the encoding");
            assertEquals(xp, m.xp, "scratch is restored");
            for (int i = 4; i < 3 + n; i++) {
                assertTrue(buf[i] >= 0, "an encoding holds opcodes and name ids only");
            }
            int back = Levels.decodeLevel(m, buf, 3);
            assertEquals(l, back, text + ": plain constructors rebuild the hash-consed level");
            assertEquals(xp, m.xp);
        }
        // name ids, not level ids: `u` is PARAM + its name
        int n = Levels.encodeLevel(m, f.level("u"), buf, 0);
        assertEquals(3, n);
        assertEquals(Layout.LV_PARAM, buf[1]);
        assertEquals(m.levels.paramName(f.level("u")), buf[2]);
        // too short a buffer, malformed encodings
        assertEquals(-1, Levels.encodeLevel(m, f.level("(max u v)"), new int[3], 0));
        assertEquals(xp, m.xp);
        int[] bad = {1, Layout.LV_SUCC};
        assertEquals(Op.FATAL, Levels.decodeLevel(m, bad, 0));
        assertEquals(MachineErrors.E_INTERNAL, m.err);
        int[] bad2 = {2, Layout.LV_ZERO, Layout.LV_ZERO};
        assertEquals(Op.FATAL, Levels.decodeLevel(m, bad2, 0));
        int[] bad3 = {1, 99};
        assertEquals(Op.FATAL, Levels.decodeLevel(m, bad3, 0));
        m.err = 0;
    }
}
