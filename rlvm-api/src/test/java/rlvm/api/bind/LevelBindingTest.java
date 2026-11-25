package rlvm.api.bind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import rlvm.api.KernelException;
import rlvm.api.LevelOps;
import rlvm.api.Reason;
import rlvm.level.LevelStore;
import rlvm.level.NameOracle;
import rlvm.store.IntLists;

/** {@link LevelBinding} over real {@code rlvm.level.LevelStore}s (base and overlay). */
class LevelBindingTest {
    private static final int U = 11;
    private static final int V = 12;
    private static final int W = 13;

    private static int list(IntLists l, int... xs) {
        return l.intern(xs, 0, xs.length);
    }

    @Test
    void constructorsAndRelationsAreTheLevelStores() {
        LevelStore store = new LevelStore(NameOracle.BY_ID);
        LevelBinding b = new LevelBinding(store);
        LevelOps ops = b;
        assertSame(store, b.store());
        assertSame(b, ops.lists());

        int zero = ops.zero();
        assertEquals(LevelStore.ZERO, zero);
        int one = ops.succ(zero);
        int u = ops.param(U);
        int v = ops.param(V);
        assertEquals(LevelOps.KIND_ZERO, ops.kind(zero));
        assertEquals(LevelOps.KIND_SUCC, ops.kind(one));
        assertEquals(LevelOps.KIND_PARAM, ops.kind(u));
        assertEquals(zero, ops.succOf(one));
        assertEquals(U, ops.paramName(u));
        assertTrue(ops.isZero(zero));
        assertFalse(ops.isZero(u));
        assertTrue(ops.hasParam(ops.succ(u)));
        assertFalse(ops.hasParam(one));

        // plain constructors keep the structure, the official ones simplify
        int plain = ops.max(u, u);
        assertEquals(LevelOps.KIND_MAX, ops.kind(plain));
        assertEquals(u, ops.lhs(plain));
        assertEquals(u, ops.rhs(plain));
        assertEquals(u, ops.mkMax(u, u));
        assertEquals(zero, ops.mkIMax(u, zero));
        assertEquals(LevelOps.KIND_IMAX, ops.kind(ops.imax(u, v)));

        assertTrue(ops.isEquiv(plain, u));
        assertTrue(ops.isGeq(ops.succ(u), u));
        assertFalse(ops.isGeq(u, ops.succ(u)));
        assertTrue(ops.isAlwaysZero(ops.imax(u, zero)));
        assertFalse(ops.isAlwaysZero(u));
        assertTrue(ops.isNeverZero(ops.succ(u)));
        assertFalse(ops.isNeverZero(u));

        // the complete relation accepts what the official normal form does not identify
        int imaxU1 = ops.imax(u, one);
        int maxU1 = ops.max(u, one);
        assertTrue(ops.isEquiv(imaxU1, maxU1));
        assertFalse(ops.isEquivOfficial(imaxU1, maxU1));
        assertTrue(ops.isGeqOfficial(ops.succ(u), u));

        int names = list(b, U, V);
        assertTrue(ops.paramsDeclared(ops.max(u, v), names));
        assertFalse(ops.paramsDeclared(ops.max(u, ops.param(W)), names));
        assertEquals(0, ops.undeclaredParam(ops.max(u, v), names));
        assertEquals(ops.param(W), ops.undeclaredParam(ops.max(u, ops.param(W)), names));

        assertTrue(ops.listsEquiv(list(b, plain, one), list(b, u, one)));
        assertFalse(ops.listsEquiv(list(b, plain, one), list(b, v, one)));
        assertTrue(ops.toString(ops.succ(u)).contains("+1"));
        assertEquals("<none>", ops.toString(0));
    }

    /**
     * *agentic-note*: design-reconciliation-document, B1: two constants share one body term but declare their universe parameters
     * in swapped order. With a bare level list as the instantiation the two would be confused; the
     * substitution ids differ and so do the instantiated levels.
     */
    @Test
    void substitutionIdsDistinguishSwappedParameterLists() {
        LevelStore base = new LevelStore(NameOracle.BY_ID);
        LevelBinding b = new LevelBinding(base);
        int u = b.param(U);
        int v = b.param(V);
        int body = b.max(b.succ(u), v); // the shared body's level: max (u+1) v
        int uv = list(b, U, V); // constant c1.{u, v}
        int vu = list(b, V, U); // constant c2.{v, u}
        int one = b.succ(b.zero());
        int three = b.succ(b.succ(one));
        int levels = list(b, one, three); // both are instantiated with [1, 3]

        int s1 = b.mkSubst(uv, levels);
        int s2 = b.mkSubst(vu, levels);
        assertTrue(s1 > 0 && s2 > 0);
        assertNotEquals(s1, s2, "same level list, different parameter order: different substitutions");
        assertEquals(uv, b.substParams(s1));
        assertEquals(levels, b.substLevels(s1));
        assertEquals(s1, b.mkSubst(uv, levels), "equal pairs give equal ids");

        int r1 = b.instantiate(body, s1); // max (1+1) 3 = 3
        int r2 = b.instantiate(body, s2); // max (3+1) 1 = 4
        assertTrue(b.isEquiv(r1, three));
        assertTrue(b.isEquiv(r2, b.succ(three)));
        assertFalse(b.isEquiv(r1, r2));
        // the three-argument form is the same function
        assertEquals(r1, b.instantiate(body, uv, levels));
        assertEquals(r2, b.instantiate(body, vu, levels));

        // lists
        int bodyList = list(b, body, u, one);
        int l1 = b.instantiateList(bodyList, s1);
        int l2 = b.instantiateList(bodyList, s2);
        assertNotEquals(l1, l2);
        assertEquals(r1, b.get(l1, 0));
        assertEquals(one, b.get(l1, 1));
        assertEquals(three, b.get(l2, 1));
        assertEquals(l1, b.instantiateList(bodyList, uv, levels));

        // identity and closed levels
        int ident = b.mkSubst(uv, list(b, u, v));
        assertEquals(0, ident);
        assertEquals(body, b.instantiate(body, 0));
        assertEquals(bodyList, b.instantiateList(bodyList, 0));
        assertEquals(three, b.instantiate(three, s1));
        assertEquals(0, b.mkSubst(0, 0));

        // wrong number of levels
        int shortList = list(b, one);
        assertEquals(-1, b.mkSubst(uv, shortList));
        assertEquals(Reason.LEVEL_ARITY_MISMATCH,
                assertThrows(KernelException.class, () -> b.instantiate(body, uv, shortList)).reason());
        assertEquals(Reason.LEVEL_ARITY_MISMATCH,
                assertThrows(KernelException.class, () -> b.instantiateList(bodyList, uv, shortList)).reason());
        assertEquals(Reason.LEVEL_ARITY_MISMATCH,
                assertThrows(KernelException.class, () -> b.instantiate(body, 0, shortList)).reason());
    }

    @Test
    void unionSortedAgainstASetModel() {
        LevelBinding b = new LevelBinding(new LevelStore(NameOracle.BY_ID));
        Random rnd = new Random(7);
        int[] ids = new int[60];
        @SuppressWarnings("unchecked")
        TreeSet<Integer>[] sets = new TreeSet[60];
        for (int i = 0; i < ids.length; i++) {
            TreeSet<Integer> s = new TreeSet<>();
            int n = rnd.nextInt(7);
            for (int k = 0; k < n; k++) {
                s.add(1 + rnd.nextInt(12));
            }
            sets[i] = s;
            ids[i] = list(b, s.stream().mapToInt(Integer::intValue).toArray());
        }
        for (int round = 0; round < 2; round++) { // the second round hits the memo
            for (int i = 0; i < ids.length; i++) {
                for (int j = 0; j < ids.length; j++) {
                    TreeSet<Integer> want = new TreeSet<>(sets[i]);
                    want.addAll(sets[j]);
                    int got = b.unionSorted(ids[i], ids[j]);
                    assertEquals(list(b, want.stream().mapToInt(Integer::intValue).toArray()), got);
                    if (sets[i].containsAll(sets[j])) {
                        assertEquals(ids[i], got);
                    }
                    assertEquals(sets[j].containsAll(sets[i]), b.subsetOf(ids[i], ids[j]),
                            sets[i] + " subset of " + sets[j]);
                }
            }
        }
        assertEquals(0, b.unionSorted(0, 0));
        assertTrue(b.subsetOf(0, 0));
        assertTrue(b.subsetOf(0, ids[1]));
        // subsetOf accepts a list in any order on the right (a declaration's parameter list)
        int unsorted = list(b, 9, 3, 5);
        assertTrue(b.subsetOf(list(b, 3, 9), unsorted));
        assertFalse(b.subsetOf(list(b, 3, 4), unsorted));
        assertFalse(b.subsetOf(list(b, 3), 0));
        assertTrue(b.hasDuplicates(list(b, 4, 5, 4)));
        assertFalse(b.hasDuplicates(unsorted));
        int[] copy = new int[5];
        b.copyTo(unsorted, copy, 1);
        assertEquals(9, copy[1]);
        assertEquals(5, copy[3]);
        assertEquals(3, b.length(unsorted));
    }

    @Test
    void overlayResetForgetsWorkerObjectsAndTheUnionMemo() {
        LevelStore base = new LevelStore(NameOracle.BY_ID);
        LevelBinding loader = new LevelBinding(base);
        int u = loader.param(U);
        int baseSet = list(loader, 2, 4);
        int names = list(loader, U);
        base.freeze();

        // frozen base: existing objects are found, new ones are refused
        assertEquals(baseSet, loader.intern(new int[] {2, 4}, 0, 2));
        assertThrows(IllegalStateException.class, () -> loader.intern(new int[] {2, 5}, 0, 2));
        assertThrows(IllegalStateException.class, loader::reset);
        assertEquals(0, loader.footprintBytes());

        LevelBinding w = new LevelBinding(base.newOverlay());
        assertEquals(0, w.footprintBytes());
        int setA = list(w, 1, 3);
        int union1 = w.unionSorted(setA, baseSet);
        assertEquals(list(w, 1, 2, 3, 4), union1);
        int big = w.succ(w.succ(u));
        int subst = w.mkSubst(names, list(w, big));
        assertTrue(subst > 0);
        assertTrue(w.footprintBytes() > 0);

        w.reset();
        assertEquals(0, w.footprintBytes());
        // base ids keep their meaning
        assertEquals(baseSet, list(w, 2, 4));
        assertEquals(u, w.param(U));
        // the ids setA and union1 are reissued for other lists; a stale memo entry would be wrong
        int setB = list(w, 7, 8);
        assertEquals(setA, setB, "the overlay reissues list ids after a reset (precondition of this test)");
        int union2 = w.unionSorted(setB, baseSet);
        assertEquals(list(w, 2, 4, 7, 8), union2);
        // a second worker is independent
        LevelBinding w2 = new LevelBinding(base.newOverlay());
        assertEquals(list(w2, 2, 4, 9), w2.unionSorted(list(w2, 9), baseSet));
    }

    @Test
    void lateNameOracleSwitchesToTheNameOrderOnceBound() {
        NameOracles.Late late = NameOracles.late();
        assertFalse(late.isBound());
        assertEquals(0, late.compareNames(5, 5));
        assertFalse(late.usedUnbound(), "comparing a name with itself needs no table");
        assertTrue(late.compareNames(3, 9) < 0);
        assertTrue(late.usedUnbound());
        StringBuilder sb = new StringBuilder();
        late.appendName(sb, 42);
        assertEquals("p42", sb.toString());
        assertThrows(NullPointerException.class, () -> late.bind((NameOracle) null));
        assertThrows(NullPointerException.class, () -> late.bind((rlvm.store.NameTable) null));
        assertThrows(NullPointerException.class, () -> NameOracles.of(null));
        assertFalse(late.isBound());

        // reversed id order as "the name table's order"
        NameOracle reversed = new NameOracle() {
            @Override
            public int compareNames(int a, int b) {
                return Integer.compare(b, a);
            }

            @Override
            public void appendName(StringBuilder out, int id) {
                out.append("n").append(id);
            }
        };
        late.bind(reversed);
        assertTrue(late.isBound());
        assertTrue(late.compareNames(3, 9) > 0);
        assertEquals(0, late.compareNames(4, 4));
        sb.setLength(0);
        late.appendName(sb, 42);
        assertEquals("n42", sb.toString());
        assertThrows(IllegalStateException.class, () -> late.bind(reversed), "bound once");
    }
}
