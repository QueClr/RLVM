package rlvm.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Randomised tests against the brute-force semantics (evaluate under all assignments of the
 * parameters to small naturals):
 * <ul>
 *   <li>soundness: no operation may contradict the semantics (official equivalence / ordering imply
 *       the semantic ones; normalisation, smart constructors and instantiation preserve values);
 *   <li>exactness of the complete procedures: {@code isEquiv}, {@code isGeq}, {@code canonId},
 *       {@code canonRep}, {@code isNeverZero}, {@code isAlwaysZero} agree with the semantics in both
 *       directions.
 * </ul>
 */
class SemanticsTest {

    private static Lv.Names names() {
        return new Lv.Names("w", "u", "x", "v");
    }

    @Test
    void completeComparisonsAreExactAndOfficialOnesAreSound() {
        Random rnd = new Random(11);
        Lv.Names n = names();
        LevelStore s = new LevelStore(n);
        int semEq = 0;
        int semGeq = 0;
        int officialMissedEq = 0;
        int officialMissedGeq = 0;
        for (int it = 0; it < 30000; it++) {
            int np = 1 + rnd.nextInt(3);
            RefLevel x = Lv.random(rnd, 1 + rnd.nextInt(5), np);
            RefLevel y;
            int mode = rnd.nextInt(10);
            if (mode < 4) {
                y = OfficialPortTest.mutate(rnd, x);
            } else if (mode < 6) {
                y = OfficialPortTest.mutate(rnd, OfficialPortTest.mutate(rnd, x));
            } else {
                y = Lv.random(rnd, 1 + rnd.nextInt(5), np);
            }
            int ix = Lv.toStore(s, x, n);
            int iy = Lv.toStore(s, y, n);
            boolean eq = Lv.semEq(x, y);
            boolean geq = Lv.semGeq(x, y);
            boolean leq = Lv.semGeq(y, x);
            String what = x + " vs " + y;

            // complete procedures: exact
            assertEquals(eq, s.isEquiv(ix, iy), "isEquiv " + what);
            assertEquals(eq, s.isEquiv(iy, ix), "isEquiv (sym) " + what);
            assertEquals(geq, s.isGeq(ix, iy), "isGeq " + what);
            assertEquals(leq, s.isLeq(ix, iy), "isLeq " + what);
            assertTrue(s.canonId(ix) > 0 && s.canonId(iy) > 0, "canonical form unknown: " + what);
            assertEquals(eq, s.canonId(ix) == s.canonId(iy), "canonId " + what);
            assertEquals(eq, s.canonRep(ix) == s.canonRep(iy), "canonRep " + what);
            assertTrue(s.isEquiv(ix, s.canonRep(ix)));

            // official procedures: sound (never true when the semantics says false)
            boolean oeq = s.isEquivOfficial(ix, iy);
            boolean ogeq = s.isGeqOfficial(ix, iy);
            if (oeq) {
                assertTrue(eq, "official is_equivalent unsound?! " + what);
            }
            if (ogeq) {
                assertTrue(geq, "official is_geq unsound?! " + what);
            }
            semEq += eq ? 1 : 0;
            semGeq += geq ? 1 : 0;
            officialMissedEq += eq && !oeq ? 1 : 0;
            officialMissedGeq += geq && !ogeq ? 1 : 0;
        }
        assertTrue(semEq > 3000, "generator produced too few equal pairs: " + semEq);
        assertTrue(semGeq > 8000, "generator produced too few ordered pairs: " + semGeq);
        // The official procedures are incomplete under imax; the complete ones must find more.
        assertTrue(officialMissedEq > 0 && officialMissedGeq > 0, officialMissedEq + " " + officialMissedGeq);
    }

    @Test
    void equalCanonicalFormsWithinBucketsOfSemanticallyEqualLevels() {
        // Many random levels over two parameters; group by their value table. Within a group all
        // canonical ids must coincide, across groups they must differ.
        Random rnd = new Random(12);
        Lv.Names n = names();
        LevelStore s = new LevelStore(n);
        List<String> ps = List.of("u", "v");
        Map<String, Integer> canonOfTable = new HashMap<>();
        Map<Integer, String> tableOfCanon = new HashMap<>();
        int bound = 8;
        for (int it = 0; it < 30000; it++) {
            RefLevel x = Lv.random(rnd, 1 + rnd.nextInt(6), 2);
            if (x.depth + 2 > bound) {
                continue;
            }
            StringBuilder table = new StringBuilder();
            Lv.forAll(ps, bound, env -> {
                table.append(RefLevel.eval(x, env)).append(',');
                return true;
            });
            int c = s.canonId(Lv.toStore(s, x, n));
            assertTrue(c > 0);
            String t = table.toString();
            Integer prev = canonOfTable.putIfAbsent(t, c);
            if (prev != null) {
                assertEquals(prev.intValue(), c, "same values, different canonical form: " + x);
            }
            String prevT = tableOfCanon.putIfAbsent(c, t);
            if (prevT != null) {
                assertEquals(prevT, t, "same canonical form, different values: " + x);
            }
        }
        assertTrue(canonOfTable.size() > 200, "too few distinct functions: " + canonOfTable.size());
    }

    @Test
    void normalizeAndSmartConstructorsPreserveValues() {
        Random rnd = new Random(13);
        Lv.Names n = names();
        LevelStore s = new LevelStore(n);
        for (int it = 0; it < 20000; it++) {
            RefLevel x = Lv.random(rnd, 1 + rnd.nextInt(5), 3);
            RefLevel y = Lv.random(rnd, 1 + rnd.nextInt(4), 3);
            int ix = Lv.toStore(s, x, n);
            int iy = Lv.toStore(s, y, n);
            RefLevel nx = Lv.fromStore(s, s.normalize(ix), n);
            assertTrue(Lv.semEq(x, nx), "normalize changed the value of " + x + ": " + nx);
            RefLevel mx = Lv.fromStore(s, s.mkMax(ix, iy), n);
            assertTrue(Lv.semEq(RefLevel.max(x, y), mx), "mk_max " + x + " " + y);
            RefLevel im = Lv.fromStore(s, s.mkIMax(ix, iy), n);
            assertTrue(Lv.semEq(RefLevel.imax(x, y), im), "mk_imax " + x + " " + y);
            // and the complete comparison sees it
            assertTrue(s.isEquiv(ix, s.normalize(ix)));
            assertTrue(s.isEquiv(s.max(ix, iy), s.mkMax(ix, iy)));
            assertTrue(s.isEquiv(s.imax(ix, iy), s.mkIMax(ix, iy)));
            assertTrue(s.isGeq(s.mkMax(ix, iy), ix) && s.isGeq(s.mkMax(ix, iy), iy));
            assertTrue(s.isGeq(s.succ(ix), ix) && !s.isGeq(ix, s.succ(ix)));
        }
    }

    @Test
    void zeroTestsAreExact() {
        Random rnd = new Random(14);
        Lv.Names n = names();
        LevelStore s = new LevelStore(n);
        int never = 0;
        int always = 0;
        for (int it = 0; it < 20000; it++) {
            RefLevel x = Lv.random(rnd, 1 + rnd.nextInt(6), 3);
            int ix = Lv.toStore(s, x, n);
            boolean nz = Lv.semNeverZero(x);
            boolean az = Lv.semAlwaysZero(x);
            assertEquals(nz, s.isNeverZero(ix), "isNeverZero " + x);
            assertEquals(az, s.isAlwaysZero(ix), "isAlwaysZero " + x);
            assertEquals(az, s.isEquiv(ix, LevelStore.ZERO));
            assertEquals(nz, s.isGeq(ix, LevelStore.ONE));
            never += nz ? 1 : 0;
            always += az ? 1 : 0;
        }
        assertTrue(never > 2000 && always > 500, never + " " + always);
    }

    @Test
    void instantiateIsSubstitutionSemantically() {
        Random rnd = new Random(15);
        Lv.Names n = names();
        LevelStore s = new LevelStore(n);
        int[] buf = new int[4];
        for (int it = 0; it < 15000; it++) {
            RefLevel l = Lv.random(rnd, 1 + rnd.nextInt(5), 3);
            List<String> ps = List.of("u", "v", "w");
            List<RefLevel> ls = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                ls.add(Lv.random(rnd, rnd.nextInt(4), 2));
            }
            for (int i = 0; i < 3; i++) {
                buf[i] = n.id(ps.get(i));
            }
            int pl = s.internList(buf, 0, 3);
            for (int i = 0; i < 3; i++) {
                buf[i] = Lv.toStore(s, ls.get(i), n);
            }
            int ll = s.internList(buf, 0, 3);
            int subst = s.mkSubst(pl, ll);
            RefLevel r = Lv.fromStore(s, s.instantiate(Lv.toStore(s, l, n), subst), n);
            // value of the result under env == value of l under (u,v,w := values of the substituted levels under env)
            List<String> free = Lv.paramsOf(ls.get(0), ls.get(1), ls.get(2), r);
            int bound = Math.max(Math.max(ls.get(0).depth, ls.get(1).depth), ls.get(2).depth) + 2;
            boolean ok = Lv.forAll(free, bound, env -> {
                Map<String, Integer> inner = new HashMap<>();
                for (int i = 0; i < 3; i++) {
                    inner.put(ps.get(i), (int) RefLevel.eval(ls.get(i), env));
                }
                return RefLevel.eval(l, inner) == RefLevel.eval(r, env);
            });
            assertTrue(ok, "instantiate " + l + " with " + ls + " gave " + r);
        }
    }

    @Test
    void guardedSublevelsNeedTheirGuards() {
        // Hand-picked shapes where a wrong treatment of imax guards would show.
        Lv.Names n = names();
        LevelStore s = new LevelStore(n);
        String[][] equal = {
            {"(i u v)", "(i (i u v) v)"},                 // corner-cases/imax-nested-absorption
            {"(i u (s v))", "(m u (s v))"},               // corner-cases/imax-right-successor
            {"(s (i u 1))", "(m 2 (s u))"},
            {"(i u (m v w))", "(m (i u v) (i u w))"},
            {"(i u (i v w))", "(m (i u w) (i v w))"},
            {"(i (m u v) w)", "(m (i u w) (i v w))"},
            {"(i 1 u)", "u"},
            {"(i 2 u)", "(m u (i 2 u))"},
            {"(m (i 2 u) (i 2 v))", "(i 2 (m u v))"},
            {"(i (s u) u)", "(m u (i (s u) u))"},
            {"(m 1 (i (s u) u))", "(s u)"},
            {"(m u (i u v))", "(m u (i v v))"},
            {"(i (i u v) (i v u))", "(i (m u v) (i v u))"},
        };
        for (String[] p : equal) {
            RefLevel x = Lv.parse(p[0]);
            RefLevel y = Lv.parse(p[1]);
            assertTrue(Lv.semEq(x, y), "test data wrong: " + p[0] + " " + p[1]);
            assertTrue(s.isEquiv(Lv.toStore(s, x, n), Lv.toStore(s, y, n)), p[0] + " == " + p[1]);
        }
        String[][] different = {
            {"(i 0 v)", "(s (i 0 v))"},                   // bugs/level-imax-normalization
            {"(i u v)", "(s (i u v))"},                   // bugs/level-imax-leq
            {"(i 2 u)", "(m 2 u)"},
            {"(i 2 u)", "u"},
            {"(i u v)", "(m u v)"},
            {"(i u v)", "(i v u)"},
            {"(i (s u) u)", "(s u)"},
            {"(i u (i v w))", "(i (i u v) w)"},
            {"(m (i 2 u) (i 3 v))", "(i 3 (m u v))"},
            {"u", "v"},
            {"(m u 1)", "u"},
        };
        for (String[] p : different) {
            RefLevel x = Lv.parse(p[0]);
            RefLevel y = Lv.parse(p[1]);
            assertFalse(Lv.semEq(x, y), "test data wrong: " + p[0] + " " + p[1]);
            int ix = Lv.toStore(s, x, n);
            int iy = Lv.toStore(s, y, n);
            assertFalse(s.isEquiv(ix, iy), p[0] + " != " + p[1]);
            assertFalse(s.isEquivOfficial(ix, iy), p[0] + " != " + p[1]);
            assertNotEquals(s.canonId(ix), s.canonId(iy));
        }
    }
}
