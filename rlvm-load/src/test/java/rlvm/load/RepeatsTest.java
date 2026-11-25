package rlvm.load;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import rlvm.store.DeclKind;

/** {@link Repeats}: the first unit with exactly the same key, through growth and through full buckets. */
class RepeatsTest {

    @Test
    void theKeyIsExact() {
        Repeats r = new Repeats();
        assertEquals(-1, r.firstOrAdd(false, DeclKind.THM, 7, 0, 100, 200, 0, true));
        assertEquals(0, r.firstOrAdd(false, DeclKind.THM, 7, 0, 100, 200, 1, true));
        assertEquals(0, r.firstOrAdd(false, DeclKind.THM, 7, 0, 100, 200, 2, true));
        // one word of the key differs: another declaration
        assertEquals(-1, r.firstOrAdd(false, DeclKind.OPAQUE, 7, 0, 100, 200, 3, true));
        assertEquals(-1, r.firstOrAdd(true, DeclKind.DEFN, 7, 0, 100, 200, 4, true));
        assertEquals(-1, r.firstOrAdd(true, DeclKind.DEFN, 7, 1, 100, 200, 5, true));
        assertEquals(-1, r.firstOrAdd(true, DeclKind.DEFN, 7, DeclKind.HINT_ABBREV, 100, 200, 6, true));
        assertEquals(-1, r.firstOrAdd(false, DeclKind.THM, 8, 0, 100, 200, 7, true));
        assertEquals(-1, r.firstOrAdd(false, DeclKind.THM, 7, 0, 101, 200, 8, true));
        assertEquals(-1, r.firstOrAdd(false, DeclKind.THM, 7, 0, 100, 201, 9, true));
        assertEquals(-1, r.firstOrAdd(false, DeclKind.THM, 7, 0, 200, 100, 10, true));
        // the hint of a theorem or an opaque is no part of its key; a definition's is, and an opaque
        // definition (hint 0) is not a theorem
        assertEquals(0, r.firstOrAdd(false, DeclKind.THM, 7, 99, 100, 200, 11, true));
        assertEquals(4, r.firstOrAdd(true, DeclKind.DEFN, 7, 0, 100, 200, 12, true));
        assertEquals(5, r.firstOrAdd(true, DeclKind.DEFN, 7, 1, 100, 200, 13, true));
        assertEquals(6, r.firstOrAdd(true, DeclKind.DEFN, 7, DeclKind.HINT_ABBREV, 100, 200, 14, true));
        assertEquals(3, r.firstOrAdd(false, DeclKind.OPAQUE, 7, 0, 100, 200, 15, true));
        assertEquals(9, r.size());
        assertEquals(7, r.found());
        assertThrows(IllegalArgumentException.class, () -> r.firstOrAdd(true, DeclKind.DEFN, 7, -1, 1, 2, 16, true));
    }

    @Test
    void aDeclarationWithARecordOfItsOwnSegmentIsAddedWithoutASearchAndFoundLater() {
        Repeats r = new Repeats();
        assertEquals(-1, r.firstOrAdd(false, DeclKind.THM, 0, 0, 5, 6, 0, false));
        assertEquals(0, r.firstOrAdd(false, DeclKind.THM, 0, 0, 5, 6, 1, true));
    }

    @Test
    void againstAMapThroughGrowth() {
        Random rnd = new Random(20261125);
        Repeats r = new Repeats();
        Map<List<Integer>, Integer> model = new HashMap<>();
        int found = 0;
        for (int unit = 0; unit < 200_000; unit++) {
            // few distinct records, so that buckets hold several keys and many units repeat
            int kind = DeclKind.DEFN + rnd.nextInt(3);
            boolean defn = kind == DeclKind.DEFN;
            int hint = defn ? rnd.nextInt(3) : 0;
            int lp = rnd.nextInt(3);
            int type = 1 + rnd.nextInt(300);
            int value = 1 + rnd.nextInt(300);
            Integer first = model.putIfAbsent(List.of(kind, hint, lp, type, value), unit);
            assertEquals(first == null ? -1 : first.intValue(), r.firstOrAdd(defn, kind, lp, hint, type, value, unit, true));
            if (first != null) {
                found++;
            }
        }
        assertEquals(model.size(), r.size());
        assertEquals(found, r.found());
    }
}
