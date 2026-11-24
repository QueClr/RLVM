package rlvm.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Randomised differential tests: every official operation of {@link LevelStore} must produce exactly
 * the structure / answer of the line-by-line reference port {@link RefLevel}.
 */
class OfficialPortTest {

    /** Name ids deliberately in the opposite order of the names, so a comparison by id would show. */
    private static Lv.Names names() {
        return new Lv.Names("x", "w", "v", "u");
    }

    @Test
    void flagsAndAccessorsMatchReference() {
        Random rnd = new Random(1);
        Lv.Names n = names();
        LevelStore s = new LevelStore(n);
        for (int it = 0; it < 20000; it++) {
            RefLevel r = Lv.random(rnd, 1 + rnd.nextInt(6), 4);
            int l = Lv.toStore(s, r, n);
            assertEquals(r, Lv.fromStore(s, l, n));
            assertEquals(r.kind, s.kind(l));
            assertEquals(r.depth, s.depth(l));
            assertEquals(r.hasParam, s.hasParam(l));
            assertEquals(RefLevel.isExplicit(r), s.isExplicit(l));
            assertEquals(RefLevel.isNotZero(r), s.isNeverZero(l), r.toString());
            assertEquals(RefLevel.normalizesToZero(r), s.isAlwaysZero(l), r.toString());
            assertEquals(RefLevel.offsetK(r), s.offset(l));
            assertEquals(RefLevel.offsetBase(r), Lv.fromStore(s, s.offsetBase(l), n));
            assertEquals(RefLevel.isZero(r), s.isZero(l));
            // hash-consing: building the same tree again gives the same id
            assertEquals(l, Lv.toStore(s, r, n));
        }
    }

    @Test
    void smartConstructorsMatchReference() {
        Random rnd = new Random(2);
        Lv.Names n = names();
        LevelStore s = new LevelStore(n);
        for (int it = 0; it < 40000; it++) {
            RefLevel x = Lv.random(rnd, rnd.nextInt(5), 3);
            RefLevel y;
            switch (rnd.nextInt(6)) {
                case 0:
                    y = x;
                    break;
                case 1:
                    y = RefLevel.max(x, Lv.random(rnd, 2, 3));
                    break;
                case 2:
                    y = RefLevel.max(Lv.random(rnd, 2, 3), x);
                    break;
                case 3:
                    y = RefLevel.succN(RefLevel.offsetBase(x), rnd.nextInt(4));
                    break;
                default:
                    y = Lv.random(rnd, rnd.nextInt(5), 3);
                    break;
            }
            if (rnd.nextBoolean()) {
                RefLevel t = x;
                x = y;
                y = t;
            }
            int ix = Lv.toStore(s, x, n);
            int iy = Lv.toStore(s, y, n);
            assertEquals(RefLevel.mkMax(x, y), Lv.fromStore(s, s.mkMax(ix, iy), n), "mk_max " + x + " " + y);
            assertEquals(RefLevel.mkIMax(x, y), Lv.fromStore(s, s.mkIMax(ix, iy), n), "mk_imax " + x + " " + y);
        }
    }

    @Test
    void normalizeMatchesReference() {
        Random rnd = new Random(3);
        Lv.Names n = names();
        LevelStore s = new LevelStore(n);
        for (int it = 0; it < 60000; it++) {
            RefLevel r = Lv.random(rnd, 1 + rnd.nextInt(7), 1 + rnd.nextInt(4));
            int l = Lv.toStore(s, r, n);
            RefLevel expected = RefLevel.normalize(r);
            int nf = s.normalize(l);
            assertEquals(expected, Lv.fromStore(s, nf, n), "normalize " + r);
            // memoised: the second call returns the same id
            assertEquals(nf, s.normalize(l));
        }
    }

    @Test
    void normalizeDoesNotDependOnEvaluationOrder() {
        // Same levels normalised in a fresh store in reverse order: same structures (memo is pure).
        Random rnd = new Random(33);
        Lv.Names n = names();
        List<RefLevel> ls = new ArrayList<>();
        for (int it = 0; it < 3000; it++) {
            ls.add(Lv.random(rnd, 1 + rnd.nextInt(6), 3));
        }
        LevelStore s1 = new LevelStore(n);
        LevelStore s2 = new LevelStore(n);
        List<RefLevel> r1 = new ArrayList<>();
        for (RefLevel r : ls) {
            r1.add(Lv.fromStore(s1, s1.normalize(Lv.toStore(s1, r, n)), n));
        }
        for (int i = ls.size() - 1; i >= 0; i--) {
            assertEquals(r1.get(i), Lv.fromStore(s2, s2.normalize(Lv.toStore(s2, ls.get(i), n)), n));
        }
    }

    @Test
    void equivAndGeqMatchReference() {
        Random rnd = new Random(4);
        Lv.Names n = names();
        LevelStore s = new LevelStore(n);
        int eqTrue = 0;
        int geqTrue = 0;
        for (int it = 0; it < 60000; it++) {
            RefLevel x = Lv.random(rnd, 1 + rnd.nextInt(5), 1 + rnd.nextInt(3));
            RefLevel y = rnd.nextInt(4) == 0 ? mutate(rnd, x) : Lv.random(rnd, 1 + rnd.nextInt(5), 1 + rnd.nextInt(3));
            int ix = Lv.toStore(s, x, n);
            int iy = Lv.toStore(s, y, n);
            boolean e = RefLevel.isEquivalent(x, y);
            boolean g = RefLevel.isGeq(x, y);
            assertEquals(e, s.isEquivOfficial(ix, iy), "is_equivalent " + x + " " + y);
            assertEquals(g, s.isGeqOfficial(ix, iy), "is_geq " + x + " " + y);
            eqTrue += e ? 1 : 0;
            geqTrue += g ? 1 : 0;
        }
        // the generator must exercise both answers
        assertTrue(eqTrue > 1000 && geqTrue > 5000, eqTrue + " " + geqTrue);
    }

    /** A level that is semantically equal or close to {@code x} but structurally different. */
    static RefLevel mutate(Random rnd, RefLevel x) {
        switch (rnd.nextInt(8)) {
            case 0:
                return RefLevel.max(x, x);
            case 1:
                return RefLevel.max(x, RefLevel.Z);
            case 2:
                return x.kind == RefLevel.MAX ? RefLevel.max(x.b, x.a) : RefLevel.imax(x, x);
            case 3:
                return x.kind == RefLevel.SUCC && x.a.kind == RefLevel.MAX
                        ? RefLevel.max(RefLevel.succ(x.a.a), RefLevel.succ(x.a.b))
                        : RefLevel.succ(x);
            case 4:
                return x.kind == RefLevel.IMAX ? RefLevel.max(x.a, x.b) : RefLevel.imax(RefLevel.ONE, x);
            case 5:
                return x.kind == RefLevel.MAX && x.b.kind == RefLevel.MAX
                        ? RefLevel.max(RefLevel.max(x.a, x.b.a), x.b.b)
                        : RefLevel.max(RefLevel.Z, x);
            case 6:
                return x.kind == RefLevel.IMAX ? RefLevel.imax(RefLevel.imax(x.a, x.b), x.b) : RefLevel.imax(x, RefLevel.ONE);
            default:
                return RefLevel.normalize(x);
        }
    }

    @Test
    void instantiateMatchesReference() {
        Random rnd = new Random(5);
        Lv.Names n = names();
        LevelStore s = new LevelStore(n);
        int[] buf = new int[8];
        for (int it = 0; it < 40000; it++) {
            RefLevel l = Lv.random(rnd, 1 + rnd.nextInt(6), 4);
            int np = rnd.nextInt(5);
            List<String> ps = new ArrayList<>();
            List<RefLevel> ls = new ArrayList<>();
            for (int i = 0; i < np; i++) {
                // duplicates happen: the first match must win
                String p = Lv.PARAMS[rnd.nextInt(4)];
                ps.add(p);
                ls.add(rnd.nextInt(5) == 0 ? RefLevel.param(p) : Lv.random(rnd, rnd.nextInt(4), 4));
            }
            for (int i = 0; i < np; i++) {
                buf[i] = n.id(ps.get(i));
            }
            int pl = s.internList(buf, 0, np);
            for (int i = 0; i < np; i++) {
                buf[i] = Lv.toStore(s, ls.get(i), n);
            }
            int ll = s.internList(buf, 0, np);
            int il = Lv.toStore(s, l, n);
            int subst = s.mkSubst(pl, ll);
            assertTrue(subst >= 0);
            RefLevel expected = RefLevel.instantiate(l, ps, ls);
            int r = s.instantiate(il, subst);
            assertEquals(expected, Lv.fromStore(s, r, n), "instantiate " + l + " " + ps + " := " + ls);
            // cache hit and the three-argument form agree
            assertEquals(r, s.instantiate(il, subst));
            assertEquals(r, s.instantiate(il, pl, ll));
            if (expected.equals(l)) {
                assertEquals(il, r);
            }
        }
    }

    @Test
    void undeclaredParamMatchesReference() {
        Random rnd = new Random(6);
        Lv.Names n = names();
        LevelStore s = new LevelStore(n);
        int[] buf = new int[8];
        for (int it = 0; it < 20000; it++) {
            RefLevel l = Lv.random(rnd, 1 + rnd.nextInt(6), 4);
            int np = rnd.nextInt(5);
            List<String> ps = new ArrayList<>();
            for (int i = 0; i < np; i++) {
                String p = Lv.PARAMS[rnd.nextInt(4)];
                ps.add(p);
                buf[i] = n.id(p);
            }
            int pl = s.internList(buf, 0, np);
            int il = Lv.toStore(s, l, n);
            String expected = RefLevel.undefParam(l, ps);
            for (int rep = 0; rep < 2; rep++) {
                int r = s.undeclaredParam(il, pl);
                assertEquals(expected == null, s.paramsDeclared(il, pl));
                if (expected == null) {
                    assertEquals(0, r, l + " " + ps);
                } else {
                    assertEquals(LevelStore.KIND_PARAM, s.kind(r));
                    assertEquals(expected, n.name(s.paramName(r)), l + " " + ps);
                }
            }
        }
    }

    @Test
    void referenceIsSelfConsistent() {
        // sanity of the reference itself: mk_max / mk_imax of structurally equal trees
        RefLevel u = RefLevel.param("u");
        assertSame(u, RefLevel.mkMax(u, RefLevel.Z));
        assertEquals(RefLevel.max(u, RefLevel.ONE), RefLevel.mkIMax(u, RefLevel.ONE));
        assertEquals(RefLevel.Z, RefLevel.mkIMax(u, RefLevel.Z));
        assertEquals(Lv.parse("(m 1 u)"), RefLevel.normalize(Lv.parse("(m u 1)")));
    }
}
