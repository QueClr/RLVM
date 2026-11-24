package rlvm.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The cases named in docs/research/02 (section 3.6), in the arena tests {@code bugs/level-imax-leq},
 * {@code bugs/level-imax-normalization}, {@code bugs/large-elim-param}, {@code bugs/proj-of-imax-prop},
 * {@code corner-cases/imax-right-successor}, {@code corner-cases/imax-nested-absorption} and in the
 * tutorial tests 015-027. The "official" column is not from memory: every value was read from the
 * answers of leanchecker v4.36.0 in {@code official-golden.tsv} (which contains all these pairs).
 */
class CuratedCasesTest {
    private static final Lv.Names N = new Lv.Names("w", "v", "u");

    private static void eq(LevelStore s, String x, String y, boolean official, boolean semantic) {
        RefLevel rx = Lv.parse(x);
        RefLevel ry = Lv.parse(y);
        int ix = Lv.toStore(s, rx, N);
        int iy = Lv.toStore(s, ry, N);
        String what = x + " = " + y;
        assertEquals(semantic, Lv.semEq(rx, ry), "test data: " + what);
        assertEquals(official, s.isEquivOfficial(ix, iy), "official " + what);
        assertEquals(official, s.isEquivOfficial(iy, ix), "official (sym) " + what);
        assertEquals(semantic, s.isEquiv(ix, iy), "complete " + what);
        assertEquals(semantic, s.isEquiv(iy, ix), "complete (sym) " + what);
        assertTrue(!official || semantic);
    }

    private static void geq(LevelStore s, String x, String y, boolean official, boolean semantic) {
        RefLevel rx = Lv.parse(x);
        RefLevel ry = Lv.parse(y);
        int ix = Lv.toStore(s, rx, N);
        int iy = Lv.toStore(s, ry, N);
        String what = x + " >= " + y;
        assertEquals(semantic, Lv.semGeq(rx, ry), "test data: " + what);
        assertEquals(official, s.isGeqOfficial(ix, iy), "official " + what);
        assertEquals(semantic, s.isGeq(ix, iy), "complete " + what);
        assertEquals(semantic, s.isLeq(iy, ix), "complete (leq) " + what);
        assertTrue(!official || semantic);
    }

    @Test
    void tutorialLevelArithmetic() {
        LevelStore s = new LevelStore(N);
        eq(s, "(i 1 0)", "0", true, true);                         // Sort (imax 1 0) is Prop
        eq(s, "(i 0 1)", "1", true, true);
        eq(s, "(i 2 1)", "2", true, true);
        eq(s, "(i u 0)", "0", true, true);
        eq(s, "(i u u)", "u", true, true);
        eq(s, "(m u v)", "(m v u)", true, true);                   // levelMaxComm
        eq(s, "(m (m u v) w)", "(m u (m v w))", true, true);       // levelMaxAssoc
        eq(s, "(m u u)", "u", true, true);                         // levelMaxIdem
        eq(s, "(m u (m u v))", "(m u v)", true, true);             // levelMaxAbsorb
        eq(s, "(s (m u v))", "(m (s u) (s v))", true, true);
        eq(s, "(i 1 u)", "u", true, true);
    }

    @Test
    void imaxBugsMustBeRejected() {
        LevelStore s = new LevelStore(N);
        // bugs/level-imax-normalization: the offset must stay outside the imax
        eq(s, "(i 0 v)", "(s (i 0 v))", false, false);
        eq(s, "(i 0 v)", "v", true, true);
        eq(s, "(s (i 0 v))", "(s v)", true, true);
        assertEquals(Lv.parse("v"), Lv.fromStore(s, s.normalize(Lv.toStore(s, Lv.parse("(i 0 v)"), N)), N));
        assertEquals(Lv.parse("(s v)"), Lv.fromStore(s, s.normalize(Lv.toStore(s, Lv.parse("(s (i 0 v))"), N)), N));
        // bugs/level-imax-leq: imax u v + 1 <= imax u v is false (u = v = 0)
        eq(s, "(i u v)", "(s (i u v))", false, false);
        geq(s, "(i u v)", "(s (i u v))", false, false);
        geq(s, "(i u v)", "u", false, false);
        geq(s, "(i u v)", "(m u v)", false, false);
        eq(s, "(i 2 u)", "u", false, false);
        // true orderings around the same shapes
        geq(s, "(i u v)", "v", true, true);
        geq(s, "(m u v)", "(i u v)", true, true);
        geq(s, "u", "(i 1 u)", true, true);
        geq(s, "(m 2 u)", "(i 2 u)", true, true);
    }

    @Test
    void whereTheOfficialKernelIsIncomplete() {
        LevelStore s = new LevelStore(N);
        // corner-cases/imax-right-successor: the official kernel accepts the (v+1) spelling ...
        eq(s, "(i u (s v))", "(m u (s v))", true, true);
        // ... and rejects the numeral one: normalize(imax u 1) = max u 1 is left unsorted
        eq(s, "(i u 1)", "(m u 1)", false, true);
        eq(s, "(i u 1)", "(m 1 u)", false, true);
        eq(s, "(s (i u 1))", "(s (m u 1))", false, true);
        eq(s, "(s (i u 1))", "(m 2 (s u))", false, true);
        eq(s, "(s (m u 1))", "(m 2 (s u))", true, true);
        // corner-cases/imax-nested-absorption
        eq(s, "(i u v)", "(i (i u v) v)", false, true);
        geq(s, "(i (i u v) v)", "(i u v)", false, true);
        geq(s, "(i u v)", "(i (i u v) v)", true, true);
        // distribution laws the official normal form does not know
        eq(s, "(i u (m v w))", "(m (i u v) (i u w))", false, true);
        eq(s, "(i (m u v) w)", "(m (i u w) (i v w))", false, true);
        // even l + 1 >= l fails officially when l is an imax
        geq(s, "(s (i u v))", "(i u v)", false, true);
    }

    @Test
    void officialNormalFormShapes() {
        LevelStore s = new LevelStore(N);
        String[][] cases = {
            {"(s (i u 1))", "(s (m u 1))"},          // max made by mk_imax: neither sorted nor distributed
            {"(s (m u 1))", "(m 2 (s u))"},          // offset distributed, explicit level first
            {"(m u 1)", "(m 1 u)"},
            {"(i u (s v))", "(m u (s v))"},
            {"(m w (m v u))", "(m u (m v w))"},      // parameters in Lean's name order, not in id order
            {"(m (s u) (m u 3))", "(m 3 (s u))"},
            {"(m 1 (s u))", "(s u)"},                // explicit level subsumed by an offset >= it
            {"(m 2 (s u))", "(m 2 (s u))"},
        };
        for (String[] c : cases) {
            int l = Lv.toStore(s, Lv.parse(c[0]), N);
            assertEquals(Lv.parse(c[1]), Lv.fromStore(s, s.normalize(l), N), "normalize " + c[0]);
            assertEquals(Lv.parse(c[1]), RefLevel.normalize(Lv.parse(c[0])), "reference normalize " + c[0]);
        }
    }

    @Test
    void zeroTests() {
        LevelStore s = new LevelStore(N);
        int u = s.param(N.id("u"));
        // bugs/large-elim-param: a parameter is neither "never zero" nor "always zero"
        assertFalse(s.isNeverZero(u));
        assertFalse(s.isAlwaysZero(u));
        // bugs/proj-of-imax-prop: Sort (imax 1 0) is a Prop
        assertTrue(s.isAlwaysZero(s.imax(LevelStore.ONE, LevelStore.ZERO)));
        assertFalse(s.isZero(s.imax(LevelStore.ONE, LevelStore.ZERO)));
        assertTrue(s.isAlwaysZero(s.imax(u, LevelStore.ZERO)));
        assertTrue(s.isAlwaysZero(s.max(LevelStore.ZERO, s.imax(u, LevelStore.ZERO))));
        assertTrue(s.isNeverZero(s.max(LevelStore.ONE, u)));
        assertTrue(s.isNeverZero(s.imax(u, LevelStore.ONE)));
        assertFalse(s.isNeverZero(s.imax(LevelStore.ONE, u)));
        assertFalse(s.isNeverZero(s.imax(s.succ(u), u)));
        assertTrue(s.isNeverZero(s.succ(u)));
        assertFalse(s.isAlwaysZero(s.succ(LevelStore.ZERO)));
    }

    @Test
    void instantiateSimplifies() {
        LevelStore s = new LevelStore(N);
        int l = Lv.toStore(s, Lv.parse("(i u v)"), N);
        int pv = s.internList(new int[] {N.id("v")}, 0, 1);
        // imax u v [v := w+1] = max u (w+1); [v := 0] = 0  (docs/research/02 section 3.3)
        int a1 = s.internList(new int[] {Lv.toStore(s, Lv.parse("(s w)"), N)}, 0, 1);
        assertEquals(Lv.parse("(m u (s w))"), Lv.fromStore(s, s.instantiate(l, s.mkSubst(pv, a1)), N));
        int a2 = s.internList(new int[] {LevelStore.ZERO}, 0, 1);
        assertEquals(LevelStore.ZERO, s.instantiate(l, s.mkSubst(pv, a2)));
        assertEquals(RefLevel.Z, RefLevel.instantiate(Lv.parse("(i u v)"), List.of("v"), List.of(RefLevel.Z)));
        // a raw level that the smart constructors would simplify stays as it is when nothing is substituted
        int raw = Lv.toStore(s, Lv.parse("(m u u)"), N);
        assertEquals(raw, s.instantiate(raw, s.mkSubst(pv, a1)));
        // an unknown parameter stays
        int pw = s.internList(new int[] {N.id("w")}, 0, 1);
        assertEquals(l, s.instantiate(l, s.mkSubst(pw, a2)));
    }
}
