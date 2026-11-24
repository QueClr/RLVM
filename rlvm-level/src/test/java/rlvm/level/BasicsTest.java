package rlvm.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** Ids, hash-consing, lists, substitutions ids, printing, limits and the unknown-canonical-form fallback. */
class BasicsTest {

    @Test
    void constantsAndAccessors() {
        Lv.Names n = new Lv.Names("u", "v");
        LevelStore s = new LevelStore(n);
        assertEquals(1, LevelStore.ZERO);
        assertEquals(2, LevelStore.ONE);
        assertEquals(LevelStore.ZERO, s.zero());
        assertEquals(LevelStore.ONE, s.succ(LevelStore.ZERO));
        assertEquals(LevelStore.ONE, s.ofNat(1));
        assertEquals(3, s.size());
        int three = s.ofNat(3);
        assertTrue(s.isExplicit(three));
        assertEquals(3, s.offset(three));
        assertEquals(3, s.depth(three));
        assertEquals(LevelStore.ZERO, s.offsetBase(three));
        assertEquals(LevelStore.KIND_SUCC, s.kind(three));
        int u = s.param(n.id("u"));
        int v = s.param(n.id("v"));
        assertEquals(LevelStore.KIND_PARAM, s.kind(u));
        assertEquals(n.id("u"), s.paramName(u));
        assertEquals(0, s.depth(u));
        int u2 = s.succN(u, 2);
        assertEquals(u, s.offsetBase(u2));
        assertEquals(2, s.offset(u2));
        assertEquals(s.succ(u), s.succOf(u2));
        assertFalse(s.isExplicit(u2));
        int m = s.max(u2, v);
        assertEquals(u2, s.lhs(m));
        assertEquals(v, s.rhs(m));
        assertEquals(3, s.depth(m));
        assertEquals(m, s.offsetBase(m));
        assertEquals(0, s.offset(m));
        assertTrue(s.hasParam(m) && !s.hasIMax(m) && !s.hasParam(three));
        assertTrue(s.hasIMax(s.succ(s.imax(u, v))));
    }

    @Test
    void hashConsingIsStructural() {
        Lv.Names n = new Lv.Names("u", "v");
        LevelStore s = new LevelStore(n);
        int u = s.param(n.id("u"));
        int v = s.param(n.id("v"));
        assertEquals(u, s.param(n.id("u")));
        assertNotEquals(u, v);
        assertEquals(s.max(u, v), s.max(u, v));
        assertNotEquals(s.max(u, v), s.max(v, u));
        assertNotEquals(s.max(u, v), s.imax(u, v));
        // raw constructors keep what the smart constructors simplify
        int uu = s.max(u, u);
        assertNotEquals(u, uu);
        assertEquals(LevelStore.KIND_MAX, s.kind(uu));
        assertEquals(u, s.mkMax(u, u));
        assertEquals(LevelStore.KIND_IMAX, s.kind(s.imax(u, LevelStore.ZERO)));
        assertEquals(LevelStore.ZERO, s.mkIMax(u, LevelStore.ZERO));
        // a parameter whose name id is 0 or negative is an ordinary parameter
        int p0 = s.param(0);
        assertEquals(p0, s.param(0));
        assertNotEquals(p0, s.param(-1));
        assertNotEquals(LevelStore.ZERO, p0);
        assertEquals(LevelStore.KIND_PARAM, s.kind(p0));
    }

    @Test
    void manyLevelsSurviveTableGrowth() {
        LevelStore s = new LevelStore(NameOracle.BY_ID, 4);
        Random rnd = new Random(41);
        int count = 300000;
        int[] ids = new int[count];
        int[] xs = new int[count];
        int[] ys = new int[count];
        int[] kinds = new int[count];
        for (int i = 0; i < 64; i++) {
            s.param(i);
        }
        for (int i = 0; i < count; i++) {
            int x = 1 + rnd.nextInt(s.size() - 1);
            int y = 1 + rnd.nextInt(s.size() - 1);
            int k = rnd.nextInt(3);
            xs[i] = x;
            ys[i] = y;
            kinds[i] = k;
            ids[i] = k == 0 ? s.succ(x) : k == 1 ? s.max(x, y) : s.imax(x, y);
        }
        for (int i = 0; i < count; i++) {
            int again = kinds[i] == 0 ? s.succ(xs[i]) : kinds[i] == 1 ? s.max(xs[i], ys[i]) : s.imax(xs[i], ys[i]);
            assertEquals(ids[i], again);
            assertEquals(kinds[i] + 1, s.kind(again));
            assertEquals(xs[i], s.lhs(again));
        }
        // ids are dense: every id below size() is a level
        assertTrue(s.size() <= count + 67);
    }

    @Test
    void invalidIdsAreRefusedByTheConstructors() {
        LevelStore s = new LevelStore();
        int u = s.param(7);
        assertThrows(IllegalArgumentException.class, () -> s.succ(0));
        assertThrows(IllegalArgumentException.class, () -> s.succ(s.size()));
        assertThrows(IllegalArgumentException.class, () -> s.max(-1, u));
        assertThrows(IllegalArgumentException.class, () -> s.max(u, 1000000));
        assertThrows(IllegalArgumentException.class, () -> s.imax(u, 0));
        assertThrows(IllegalArgumentException.class, () -> s.succN(0, 1));
        assertEquals("<invalid level 0>", s.toString(0));
    }

    @Test
    void lists() {
        LevelStore s = new LevelStore();
        int u = s.param(1);
        int v = s.param(2);
        assertEquals(0, s.internList(new int[] {u, v}, 0, 0));
        assertEquals(0, s.listLength(0));
        int l1 = s.internList(new int[] {9, u, v, 9}, 1, 2);
        int l2 = s.internList(new int[] {u, v}, 0, 2);
        int l3 = s.internList(new int[] {v, u}, 0, 2);
        int l4 = s.internList(new int[] {u}, 0, 1);
        assertTrue(l1 > 0);
        assertEquals(l1, l2);
        assertNotEquals(l1, l3);
        assertNotEquals(l1, l4);
        assertEquals(2, s.listLength(l1));
        assertEquals(u, s.listGet(l1, 0));
        assertEquals(v, s.listGet(l1, 1));
        assertEquals(v, s.lists().get(l3, 0));
        assertFalse(s.listHasDuplicates(l1));
        assertFalse(s.listHasDuplicates(0));
        assertTrue(s.listHasDuplicates(s.internList(new int[] {5, 6, 5}, 0, 3)));
        assertEquals("{p1, p2}", s.listToString(l1));
        // growth
        Random rnd = new Random(42);
        int[][] content = new int[20000][];
        int[] ids = new int[content.length];
        for (int i = 0; i < content.length; i++) {
            content[i] = new int[1 + rnd.nextInt(6)];
            for (int j = 0; j < content[i].length; j++) {
                content[i][j] = rnd.nextInt(50);
            }
            ids[i] = s.internList(content[i], 0, content[i].length);
        }
        for (int i = 0; i < content.length; i++) {
            assertEquals(ids[i], s.internList(content[i], 0, content[i].length));
            assertEquals(content[i].length, s.listLength(ids[i]));
            for (int j = 0; j < content[i].length; j++) {
                assertEquals(content[i][j], s.listGet(ids[i], j));
            }
        }
    }

    @Test
    void substitutionIds() {
        Lv.Names n = new Lv.Names("u", "v", "w");
        LevelStore s = new LevelStore(n);
        int u = s.param(n.id("u"));
        int v = s.param(n.id("v"));
        int names2 = s.internList(new int[] {n.id("u"), n.id("v")}, 0, 2);
        int ident = s.internList(new int[] {u, v}, 0, 2);
        int swap = s.internList(new int[] {v, u}, 0, 2);
        int one = s.internList(new int[] {u}, 0, 1);
        int l = s.max(u, s.succ(v));
        // wrong number of universe levels: the caller must reject
        assertEquals(-1, s.mkSubst(names2, one));
        assertEquals(-1, s.mkSubst(names2, 0));
        assertEquals(-1, s.mkSubst(0, one));
        assertEquals(-1, s.instantiate(l, names2, one));
        // empty and identity substitutions are 0 and do nothing
        assertEquals(0, s.mkSubst(0, 0));
        assertEquals(0, s.mkSubst(names2, ident));
        assertEquals(l, s.instantiate(l, 0));
        assertEquals(l, s.instantiate(l, names2, ident));
        assertEquals(swap, s.instantiateList(swap, 0));
        int sw = s.mkSubst(names2, swap);
        assertTrue(sw > 0);
        assertEquals(sw, s.mkSubst(names2, swap));
        assertEquals(names2, s.substParams(sw));
        assertEquals(swap, s.substLevels(sw));
        assertEquals(s.max(v, s.succ(u)), s.instantiate(l, sw));
        // a list: [u, v, 3] under the swap is [v, u, 3]; a parameter-free list is itself
        int three = s.ofNat(3);
        assertEquals(s.internList(new int[] {v, u, three}, 0, 3), s.instantiateList(s.internList(new int[] {u, v, three}, 0, 3), sw));
        int closed = s.internList(new int[] {three, LevelStore.ONE}, 0, 2);
        assertEquals(closed, s.instantiateList(closed, sw));
        assertEquals(0, s.instantiateList(0, sw));
        // pairwise equivalence of level lists
        int la = s.internList(new int[] {s.max(u, v), LevelStore.ONE}, 0, 2);
        int lb = s.internList(new int[] {s.max(v, u), s.imax(LevelStore.ZERO, LevelStore.ONE)}, 0, 2);
        assertTrue(s.listsEquiv(la, lb));
        assertTrue(s.listsEquiv(la, la));
        assertFalse(s.listsEquiv(la, one));
        assertFalse(s.listsEquiv(la, s.internList(new int[] {s.max(v, u), u}, 0, 2)));
        assertTrue(s.listsEquiv(0, 0));
        assertEquals(0, s.undeclaredParamInList(la, names2));
        assertEquals(v, s.undeclaredParamInList(la, s.internList(new int[] {n.id("u")}, 0, 1)));
    }

    @Test
    void printing() {
        Lv.Names n = new Lv.Names("u", "v", "w");
        LevelStore s = new LevelStore(n);
        String[][] cases = {
            {"0", "0"}, {"2", "2"}, {"u", "u"}, {"(s u)", "u+1"}, {"(s (s u))", "u+2"},
            {"(m (s u) v)", "max (u+1) v"}, {"(i u (m v w))", "imax u (max v w)"}, {"(m u (m v w))", "max u v w"},
            {"(m (m u v) w)", "max (max u v) w"}, {"(s (m u v))", "(max u v)+1"}, {"(i 1 (i u 0))", "imax 1 u 0"},
        };
        for (String[] c : cases) {
            assertEquals(c[1], s.toString(Lv.toStore(s, Lv.parse(c[0]), n)), c[0]);
        }
    }

    @Test
    void printingWithoutNameStore() {
        LevelStore s = new LevelStore();
        int m = s.max(s.param(3), s.param(4));
        assertEquals("max p3 p4", s.toString(m));
        assertEquals("{}", s.listToString(0));
    }

    /** Levels beyond the canonical-form budget: the complete comparisons fall back to the official ones. */
    @Test
    void unknownCanonicalFormFallsBackToTheOfficialProcedure() {
        String[] ps = new String[70];
        for (int i = 0; i < 70; i++) {
            ps[i] = String.format("p%02d", i);
        }
        Lv.Names n = new Lv.Names(ps);
        LevelStore s = new LevelStore(n);
        // more than 64 parameters: max p00 (max p01 ...) and the same arguments nested the other way round
        int right = s.param(n.id(ps[69]));
        for (int i = 68; i >= 0; i--) {
            right = s.max(s.param(n.id(ps[i])), right);
        }
        int left = s.param(n.id(ps[69]));
        for (int i = 68; i >= 0; i--) {
            left = s.max(left, s.param(n.id(ps[i])));
        }
        assertNotEquals(right, left);
        assertEquals(LevelStore.CANON_UNKNOWN, s.canonId(right));
        assertEquals(LevelStore.CANON_UNKNOWN, s.canonId(left));
        assertEquals(right, s.canonRep(right));
        assertTrue(s.isEquiv(right, left));                    // official: flatten, sort
        assertTrue(s.isEquiv(left, right));
        assertFalse(s.isEquiv(right, s.succ(left)));
        assertTrue(s.isGeq(right, left));
        assertTrue(s.isGeq(s.succ(right), left));
        assertFalse(s.isGeq(left, s.succ(right)));
        assertTrue(s.isGeq(right, s.param(n.id(ps[40]))));
        // one known, one unknown
        int p0 = s.param(n.id(ps[0]));
        assertTrue(s.canonId(p0) > 0);
        assertFalse(s.isEquiv(right, p0));
        assertFalse(s.isGeq(p0, right));
        // too many sublevels: imax (max of 20) (max of 20) has 400 guarded products
        int a = s.param(n.id(ps[0]));
        int b = s.param(n.id(ps[20]));
        int b2 = s.param(n.id(ps[39]));
        for (int i = 1; i < 20; i++) {
            a = s.max(a, s.param(n.id(ps[i])));
            b = s.max(b, s.param(n.id(ps[20 + i])));
            b2 = s.max(b2, s.param(n.id(ps[39 - i])));
        }
        int big = s.imax(a, b);
        int big2 = s.imax(a, b2);
        assertEquals(LevelStore.CANON_UNKNOWN, s.canonId(big));
        assertTrue(s.canonId(a) > 0 && s.canonId(b) > 0);
        assertTrue(s.isEquiv(big, big2));
        assertFalse(s.isEquiv(big, a));
        assertTrue(s.isGeq(big, b));
        // the same answers through a frozen base and an overlay, also for levels made in the overlay
        s.freeze();
        assertEquals(LevelStore.CANON_UNKNOWN, s.canonId(right));
        LevelStore ov = s.newOverlay();
        assertEquals(LevelStore.CANON_UNKNOWN, ov.canonId(right));
        assertTrue(ov.isEquiv(right, left));
        assertFalse(ov.isEquiv(right, ov.succ(left)));
        int right1 = ov.succ(right);
        int left1 = ov.succ(left);
        assertEquals(LevelStore.CANON_UNKNOWN, ov.canonId(right1));
        assertTrue(ov.isEquiv(right1, left1));
        assertTrue(ov.isGeq(right1, left));
        ov.reset();
        assertTrue(ov.isEquiv(right, left));
        assertTrue(ov.isEquiv(ov.succ(left), ov.succ(right)));
    }

    /** 16.7 million nodes: only with -Drlvm.level.heavy=true. */
    @Test
    void depthLimitOfTheOfficialKernel() {
        assumeTrue(Boolean.getBoolean("rlvm.level.heavy"), "heavy test, enable with -Drlvm.level.heavy=true");
        LevelStore s = new LevelStore(NameOracle.BY_ID, LevelStore.MAX_DEPTH + 8);
        int l = s.succN(LevelStore.ZERO, LevelStore.MAX_DEPTH);
        assertEquals(LevelStore.MAX_DEPTH, s.depth(l));
        assertThrows(LevelLimitException.class, () -> s.succ(l));
        assertThrows(LevelLimitException.class, () -> s.max(l, LevelStore.ZERO));
        assertThrows(LevelLimitException.class, () -> s.imax(LevelStore.ZERO, l));
        assertEquals(l, s.succN(LevelStore.ZERO, LevelStore.MAX_DEPTH));
    }
}
