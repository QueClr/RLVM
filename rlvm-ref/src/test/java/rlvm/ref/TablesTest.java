package rlvm.ref;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The kernel's own tables ({@link IntIntMap}, {@link LongIntMap}, {@link PairSet}): same contents as
 * the collections of the library through any sequence of declarations (fill, clear, fill again), and
 * a table does not stay large after a declaration that hardly used it.
 */
class TablesTest {

    /** Entries per "declaration": small ones after large ones, and back. */
    private static final int[] SIZES = {3, 5000, 7, 0, 12, 40_000, 1, 0, 0, 90, 300, 70_000, 2, 2, 600, 15, 100_000, 9};

    @Test
    void intIntMapAgreesWithHashMapThroughClears() {
        Random rnd = new Random(42);
        IntIntMap map = new IntIntMap();
        for (int size : SIZES) {
            Map<Integer, Integer> ref = new HashMap<>();
            for (int i = 0; i < size; i++) {
                int key = 1 + rnd.nextInt(size * 3 + 10);
                // Values are non-zero; negative ones occur (pending application types).
                int value = rnd.nextBoolean() ? 1 + rnd.nextInt(1 << 30) : -1 - rnd.nextInt(1000);
                map.put(key, value);
                ref.put(key, value);
                if ((i & 7) == 0) {
                    int probe = 1 + rnd.nextInt(size * 3 + 10);
                    assertEquals(ref.getOrDefault(probe, 0), map.get(probe));
                }
            }
            assertEquals(ref.size(), map.size());
            for (Map.Entry<Integer, Integer> e : ref.entrySet()) {
                assertEquals(e.getValue(), map.get(e.getKey()));
            }
            Set<Integer> seen = new HashSet<>();
            for (int j = 0; j < map.size(); j++) {
                assertEquals(ref.get(map.keyAt(j)), map.valueAt(j));
                assertTrue(seen.add(map.keyAt(j)));
            }
            map.clear();
            assertEquals(0, map.size());
            for (Integer k : ref.keySet()) {
                assertEquals(0, map.get(k));
            }
        }
    }

    @Test
    void longIntMapAndPairSetAgreeWithTheLibraryThroughClears() {
        Random rnd = new Random(7);
        LongIntMap map = new LongIntMap();
        PairSet set = new PairSet();
        for (int size : SIZES) {
            Map<Long, Integer> ref = new HashMap<>();
            Set<Long> pairs = new HashSet<>();
            for (int i = 0; i < size; i++) {
                int a = 1 + rnd.nextInt(size + 10);
                int b = 1 + rnd.nextInt(size + 10);
                int value = 1 + rnd.nextInt(1 << 30);
                map.put(LongIntMap.key(a, b), value);
                ref.put(LongIntMap.key(a, b), value);
                set.add(a, b);
                pairs.add(a < b ? LongIntMap.key(a, b) : LongIntMap.key(b, a));
            }
            assertEquals(ref.size(), map.size());
            assertEquals(pairs.size(), set.size());
            for (Map.Entry<Long, Integer> e : ref.entrySet()) {
                assertEquals(e.getValue(), map.get(e.getKey()));
            }
            for (int i = 0; i < size + 20; i++) {
                int a = 1 + rnd.nextInt(size + 10);
                int b = 1 + rnd.nextInt(size + 10);
                boolean expected = pairs.contains(a < b ? LongIntMap.key(a, b) : LongIntMap.key(b, a));
                assertEquals(expected, set.contains(a, b));
                assertEquals(expected, set.contains(b, a), "pairs are unordered");
                assertEquals(ref.getOrDefault(LongIntMap.key(a, b), 0), map.get(LongIntMap.key(a, b)));
            }
            map.clear();
            set.clear();
            assertEquals(0, map.size());
            assertEquals(0, set.size());
            assertFalse(set.contains(1, 2));
            assertEquals(0, map.get(LongIntMap.key(1, 2)));
        }
    }

    @Test
    void aTableDoesNotStayLargeAfterADeclarationThatHardlyUsedIt() {
        IntIntMap map = new IntIntMap();
        LongIntMap longs = new LongIntMap();
        PairSet set = new PairSet();
        long small = map.bytes();
        long smallLongs = longs.bytes();
        long smallSet = set.bytes();
        // a large declaration (still below the size at which a table is dropped outright)
        for (int i = 1; i <= 20_000; i++) {
            map.put(i, i);
            longs.put(LongIntMap.key(i, i + 1), i);
            set.add(i, i + 1);
        }
        long large = map.bytes();
        assertTrue(large > 50 * small, small + " -> " + large);
        map.clear();
        longs.clear();
        set.clear();
        assertEquals(large, map.bytes(), "kept: the next declaration may be as large");
        // a small one: its entries are found, and afterwards the table fits the cache again
        for (int i = 1; i <= 40; i++) {
            map.put(i, -i);
            longs.put(LongIntMap.key(i, 3), i);
            set.add(i, 3);
        }
        assertEquals(-17, map.get(17));
        map.clear();
        longs.clear();
        set.clear();
        assertTrue(map.bytes() <= 2 * small, "bytes " + map.bytes());
        assertTrue(longs.bytes() <= 2 * smallLongs, "bytes " + longs.bytes());
        assertTrue(set.bytes() <= 2 * smallSet, "bytes " + set.bytes());
        // one that does not use the table at all after a large one
        for (int i = 1; i <= 20_000; i++) {
            map.put(i, i);
        }
        map.clear();
        map.clear();
        assertEquals(small, map.bytes());
        // a declaration of the same size as the one before keeps its table
        for (int round = 0; round < 3; round++) {
            for (int i = 1; i <= 3000; i++) {
                map.put(i * 7, i);
            }
            long steady = map.bytes();
            map.clear();
            assertEquals(steady, map.bytes());
        }
    }
}
