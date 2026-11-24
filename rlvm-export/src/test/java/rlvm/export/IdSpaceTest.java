package rlvm.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class IdSpaceTest {
    @Test
    void denseModeNeedsNoTable() throws ExportException {
        IdSpace s = new IdSpace("Expr");
        for (int i = 0; i < 1000; i++) {
            s.fresh(i);
            s.bind(i, i + 1);
        }
        assertTrue(s.tableFree());
        assertTrue(s.denseInOrder());
        assertEquals(1000, s.boundCount());
        assertEquals(999, s.maxBoundId());
        for (int i = 0; i < 1000; i++) {
            assertEquals(i + 1, s.ref(i));
        }
        ExportException e = assertThrows(ExportException.class, () -> s.ref(1000));
        assertEquals(ExportException.Kind.MALFORMED, e.kind());
        assertEquals("Expr not found 1000", e.reason());
        e = assertThrows(ExportException.class, () -> s.fresh(999));
        assertEquals("Expr index 999 bound twice", e.reason());
        e = assertThrows(ExportException.class, () -> s.bind(5, 77));
        assertEquals("Expr index 5 bound twice", e.reason());
        assertTrue(s.tableFree());
    }

    @Test
    void implicitZero() throws ExportException {
        IdSpace s = new IdSpace("Name");
        s.bindImplicitZero(1);
        assertEquals(1, s.ref(0));
        assertEquals(1, s.boundCount());
        assertEquals(0, s.maxBoundId());
        ExportException e = assertThrows(ExportException.class, () -> s.fresh(0));
        assertEquals("Name index 0 bound twice", e.reason());
        s.bind(1, 2);
        s.bind(2, 3);
        assertTrue(s.tableFree());
        assertEquals(3, s.ref(2));
    }

    @Test
    void aSinkThatDoesNotNumberConsecutivelyForcesATable() throws ExportException {
        IdSpace s = new IdSpace("Expr");
        s.bind(0, 10);
        s.bind(1, 11);
        s.bind(2, 11); // e.g. a hash-consing sink
        s.bind(3, 5);
        assertFalse(s.tableFree());
        assertTrue(s.denseInOrder());
        assertEquals(10, s.ref(0));
        assertEquals(11, s.ref(1));
        assertEquals(11, s.ref(2));
        assertEquals(5, s.ref(3));
        assertEquals(4, s.boundCount());
        assertThrows(ExportException.class, () -> s.ref(4));
        assertThrows(ExportException.class, () -> s.fresh(2));
    }

    @Test
    void theFirstExpressionFixesTheOffset() throws ExportException {
        IdSpace s = new IdSpace("Expr");
        assertThrows(ExportException.class, () -> s.ref(0));
        s.bind(0, 42);
        s.bind(1, 43);
        assertTrue(s.tableFree());
        assertEquals(43, s.ref(1));
        IdSpace t = new IdSpace("Expr");
        t.bind(4, 1); // the first id is not 0
        assertFalse(t.tableFree());
        assertFalse(t.denseInOrder());
        assertEquals(1, t.ref(4));
        assertThrows(ExportException.class, () -> t.ref(0));
        t.bind(0, 2);
        assertEquals(2, t.ref(0));
    }

    @Test
    void hugeIds() throws ExportException {
        IdSpace s = new IdSpace("Level");
        s.bindImplicitZero(1);
        s.bind(Long.MAX_VALUE, 7);
        s.bind(1L << 40, 8);
        assertEquals(7, s.ref(Long.MAX_VALUE));
        assertEquals(8, s.ref(1L << 40));
        assertEquals(1, s.ref(0));
        assertEquals(Long.MAX_VALUE, s.maxBoundId());
        assertEquals(3, s.boundCount());
        // ids of 2^63 and above come as keys of BIG_KEY_MAX or below, one per distinct number
        long big1 = IdSpace.BIG_KEY_MAX;
        long big2 = IdSpace.BIG_KEY_MAX - 1;
        s.fresh(big1); // not bound yet
        ExportException e = assertThrows(ExportException.class, () -> s.ref(big1));
        assertEquals(ExportException.Kind.MALFORMED, e.kind());
        s.bind(big1, 9);
        s.bind(big2, 10);
        assertEquals(9, s.ref(big1));
        assertEquals(10, s.ref(big2));
        assertEquals(7, s.ref(Long.MAX_VALUE));
        assertEquals(5, s.boundCount());
        assertEquals(Long.MAX_VALUE, s.maxBoundId());
        e = assertThrows(ExportException.class, () -> s.fresh(big1));
        assertEquals(ExportException.Kind.MALFORMED, e.kind());
        e = assertThrows(ExportException.class, () -> s.bind(big2, 11));
        assertEquals(ExportException.Kind.MALFORMED, e.kind());
        e = assertThrows(ExportException.class, () -> s.ref(IdSpace.BIG_KEY_MAX - 2));
        assertEquals(ExportException.Kind.MALFORMED, e.kind());
        // -1 is the parser's "big number without digits": never an id
        e = assertThrows(ExportException.class, () -> s.ref(-1));
        assertEquals(ExportException.Kind.MALFORMED, e.kind());
        s.fresh(-1);
        assertThrows(IllegalStateException.class, () -> s.bind(-1, 9));
        assertThrows(IllegalStateException.class, () -> s.bind(5, Integer.MIN_VALUE));
        // a big id in a space that is still dense: no confusion with the dense range
        IdSpace d = new IdSpace("Name");
        d.bindImplicitZero(0);
        d.bind(1, 1);
        d.fresh(big1);
        assertThrows(ExportException.class, () -> d.ref(big1));
        d.bind(big1, 2);
        assertEquals(2, d.ref(big1));
        assertEquals(1, d.ref(1));
        assertEquals(0, d.ref(0));
        d.bind(2, 3);
        assertEquals(3, d.ref(2));
    }

    @Test
    void randomOperationsAgainstAHashMap() throws ExportException {
        Random rnd = new Random(1);
        for (int round = 0; round < 400; round++) {
            IdSpace s = new IdSpace("Expr");
            Map<Long, Integer> model = new HashMap<>();
            List<Long> bound = new ArrayList<>();
            boolean implicit = rnd.nextBoolean();
            if (implicit) {
                s.bindImplicitZero(1);
                model.put(0L, 1);
                bound.add(0L);
            }
            int style = rnd.nextInt(4); // 0 dense+affine, 1 dense+arbitrary sink ids, 2 mostly dense, 3 sparse
            long next = implicit ? 1 : 0;
            boolean inOrder = true;
            int ops = 50 + rnd.nextInt(3000);
            for (int i = 0; i < ops; i++) {
                int op = rnd.nextInt(10);
                if (op < 6) {
                    long id;
                    if (style <= 1 || (style == 2 && rnd.nextInt(200) != 0)) {
                        id = next;
                    } else {
                        id = switch (rnd.nextInt(5)) {
                            case 0 -> rnd.nextInt(100);
                            case 1 -> rnd.nextInt(100000);
                            case 2 -> rnd.nextLong() & Long.MAX_VALUE;
                            case 3 -> next + rnd.nextInt(5);
                            default -> (1L << 31) + rnd.nextInt(1000);
                        };
                    }
                    int sinkId = style == 0 || style == 2 ? (int) (model.size() + 5) : rnd.nextInt(1000000);
                    if (model.containsKey(id)) {
                        long idf = id;
                        assertThrows(ExportException.class, () -> s.fresh(idf));
                        assertThrows(ExportException.class, () -> s.bind(idf, sinkId));
                    } else {
                        s.fresh(id);
                        s.bind(id, sinkId);
                        if (id != model.size()) {
                            inOrder = false;
                        }
                        model.put(id, sinkId);
                        bound.add(id);
                        next = Math.max(next, id + 1);
                    }
                } else if (op < 9 && !bound.isEmpty()) {
                    long id = bound.get(rnd.nextInt(bound.size()));
                    assertEquals(model.get(id), s.ref(id));
                } else {
                    long id = rnd.nextBoolean() ? rnd.nextInt(5000) : rnd.nextLong() & Long.MAX_VALUE;
                    if (model.containsKey(id)) {
                        assertEquals(model.get(id), s.ref(id));
                    } else {
                        assertThrows(ExportException.class, () -> s.ref(id));
                    }
                }
            }
            assertEquals(model.size(), s.boundCount());
            assertEquals(inOrder, s.denseInOrder(), "round " + round);
            long max = -1;
            for (long id : bound) {
                assertEquals(model.get(id), s.ref(id));
                max = Math.max(max, id);
            }
            assertEquals(max, s.maxBoundId());
        }
    }
}
