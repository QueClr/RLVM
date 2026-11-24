package rlvm.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.IdentityHashMap;
import java.util.TreeSet;

import rlvm.store.Model.M;

/** Test support: compares the summaries of a stored node with the naive definitions. */
final class Checks {
    private Checks() {}

    private static final IdentityHashMap<M, Boolean> SAT = new IdentityHashMap<>();
    private static final IdentityHashMap<M, Long> DEEP = new IdentityHashMap<>();

    static void clear() {
        SAT.clear();
        DEEP.clear();
    }

    /** Sticky saturation: a variable with index + 1 &gt;= 0xFFFF below, anywhere. */
    static boolean saturated(M m) {
        Boolean r = SAT.get(m);
        if (r != null) {
            return r;
        }
        boolean v;
        switch (m.kind) {
            case Kind.BVAR:
                v = m.x + 1 >= Node.LOOSE_SAT;
                break;
            case Kind.APP:
            case Kind.LAM:
            case Kind.PI:
                v = saturated(m.a) || saturated(m.b);
                break;
            case Kind.LET:
                v = saturated(m.a) || saturated(m.b) || saturated(m.c);
                break;
            case Kind.PROJ:
                v = saturated(m.a);
                break;
            default:
                v = false;
        }
        SAT.put(m, v);
        return v;
    }

    /** Largest loose range of any sub-term (the node included). */
    static long deepRange(Model fx, M m) {
        Long r = DEEP.get(m);
        if (r != null) {
            return r;
        }
        long v = fx.range(m);
        for (M c : new M[] {m.a, m.b, m.c}) {
            if (c != null) {
                v = Math.max(v, deepRange(fx, c));
            }
        }
        DEEP.put(m, v);
        return v;
    }

    static void checkNode(Model fx, Terms t, int id, M m, String where) {
        assertEquals(m.kind, t.kind(id), where + ": kind");
        int h = t.header(id);
        long range = fx.range(m);
        boolean sat = saturated(m);
        assertEquals(sat ? Node.LOOSE_SAT : range, Node.looseRange(h), where + ": looseRange field");
        assertEquals(sat, (h & Node.F_LOOSE_SAT) != 0, where + ": LOOSE_SAT");
        assertEquals(Math.min(range, Integer.MAX_VALUE), t.looseRange(id), where + ": exact looseRange");
        assertEquals(range == 0, t.isClosed(id), where + ": isClosed");
        TreeSet<Integer> free = fx.free(m);
        long exact = Model.exactMask(free);
        long mask = t.fvMask(id);
        assertEquals(exact, mask & exact, where + ": fvMask under-approximates");
        if (!sat) {
            assertEquals(range >= 64, mask < 0, where + ": bit 63 of fvMask");
        }
        if (deepRange(fx, m) <= 63) {
            assertEquals(exact, mask, where + ": fvMask not exact although no index >= 63 occurs");
        }
        if (range == 0 && !sat) {
            assertEquals(0L, mask, where + ": closed term with a mask");
        }
        assertEquals(fx.depMax(m), t.depMax(id), where + ": depMax");
        assertEquals(fx.lpSet(m), fx.setOf(t.lpSet(id)), where + ": lpSet");
        assertEquals(fx.flags(m), h & Node.F_INHERIT_MASK, where + ": inherited flags");
        assertEquals(t.depMax(id) != 0, (h & Node.F_HAS_CONST) != 0, where + ": HAS_CONST");
        assertEquals(t.lpSet(id) != 0, (h & Node.F_HAS_LPARAM) != 0, where + ": HAS_LPARAM");
        if (m.kind == Kind.APP) {
            int head = id;
            while (t.kind(head) == Kind.APP) {
                head = t.a(head);
            }
            assertEquals(head, t.c(id), where + ": spine head");
            assertEquals(head, t.head(id), where + ": head()");
        }
        if (m.kind == Kind.NATLIT) {
            assertEquals(m.y == 1, t.isNatBig(id), where + ": NAT_BIG");
            if (m.y != 1) {
                assertEquals(m.v, t.natSmall(id), where + ": literal value");
            }
        }
        assertTrue(id > 0, where + ": id");
    }
}
