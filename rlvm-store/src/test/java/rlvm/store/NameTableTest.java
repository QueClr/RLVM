package rlvm.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** WP-A2: structural interning of names, Lean's name order, the flat-namespace guard. */
class NameTableTest {

    private static int str(NameTable t, int prefix, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        return t.internStr(prefix, b, 0, b.length);
    }

    private static int big(NameTable t, int prefix, String digits) {
        byte[] b = digits.getBytes(StandardCharsets.US_ASCII);
        return t.internNumBig(prefix, b, 0, b.length);
    }

    @Test
    void internsStructurally() {
        NameTable t = new NameTable(4);
        assertEquals(1, t.size());
        int a = str(t, 0, "A");
        int ab = str(t, a, "b");
        assertEquals(a, str(t, 0, "A"));
        assertEquals(ab, str(t, a, "b"));
        assertNotEquals(ab, str(t, 0, "b"));
        assertNotEquals(ab, str(t, 0, "A.b"), "a dot inside a component is part of the component");
        assertEquals(a, t.prefix(ab));
        assertEquals(0, t.prefix(a));
        int n5 = t.internNum(a, 5);
        assertEquals(n5, t.internNum(a, 5));
        assertNotEquals(n5, t.internNum(a, 6));
        assertNotEquals(n5, str(t, a, "5"), "numeric 5 and string \"5\" differ");
        assertTrue(t.isNumeric(n5));
        assertFalse(t.isNumeric(ab));
        assertEquals(5, t.numericValue(n5));
        long huge = 0xFFFF_FFFF_FFFF_FFFFL;
        int nh = t.internNum(0, huge);
        assertEquals(huge, t.numericValue(nh));
        assertEquals("18446744073709551615", t.toString(nh));
        // decimal entry point: canonical below 2^64, digits above
        assertEquals(n5, big(t, a, "5"));
        assertEquals(n5, big(t, a, "0005"));
        assertEquals(nh, big(t, 0, "18446744073709551615"));
        int over = big(t, 0, "18446744073709551616");
        assertNotEquals(nh, over);
        assertTrue(t.isNumeric(over));
        assertTrue(t.isBigNumeric(over));
        assertEquals(over, big(t, 0, "0018446744073709551616"));
        assertEquals("18446744073709551616", t.toString(over));
        assertThrows(IllegalArgumentException.class, () -> t.numericValue(over));
        assertThrows(IllegalArgumentException.class, () -> big(t, 0, "12x"));
        assertNotEquals(over, str(t, 0, "18446744073709551616"));
        // lookups without insertion
        byte[] q = "b".getBytes(StandardCharsets.UTF_8);
        int size = t.size();
        assertEquals(ab, t.findStr(a, q, 0, 1));
        assertEquals(0, t.findStr(ab, q, 0, 1));
        assertEquals(size, t.size());
        // unicode, the empty component, long components
        int uni = str(t, a, "αé😀");
        assertEquals(uni, str(t, a, "αé😀"));
        int empty = str(t, a, "");
        assertEquals(empty, str(t, a, ""));
        assertNotEquals(a, empty);
        String longName = "x".repeat(5000);
        assertEquals(str(t, uni, longName), str(t, uni, longName));
        assertEquals("A.b", t.toString(ab));
        assertEquals("[anonymous]", t.toString(0));
        assertEquals("A.5", t.toString(n5));
        assertThrows(IllegalArgumentException.class, () -> str(t, 99999, "x"));
        assertTrue(t.bytes() > 0);
    }

    @Test
    void componentQueries() {
        NameTable t = new NameTable(16);
        int nested = str(t, 0, "_nested");
        int priv = str(t, 0, "_private");
        int a = str(t, 0, "A");
        int deep = str(t, t.internNum(str(t, nested, "x"), 3), "rec");
        assertTrue(t.hasNestedRoot(nested));
        assertTrue(t.hasNestedRoot(deep));
        assertFalse(t.hasNestedRoot(a));
        assertFalse(t.hasNestedRoot(str(t, a, "_nested")), "only the root component counts");
        assertTrue(t.hasPrivateRoot(str(t, priv, "y")));
        assertFalse(t.hasPrivateRoot(deep));
        assertTrue(t.lastComponentIs(deep, "rec"));
        assertFalse(t.lastComponentIs(deep, "re"));
        assertFalse(t.lastComponentIs(deep, "recx"));
        assertFalse(t.lastComponentIs(t.internNum(a, 1), "1"));
        assertFalse(t.lastComponentIs(0, ""));
        assertEquals(4, t.depth(deep));
        assertEquals(0, t.depth(0));
        assertEquals(nested, t.ancestor(deep, 1));
        assertEquals(deep, t.ancestor(deep, 9));
        assertEquals(0, t.ancestor(deep, 0));
        assertEquals("_nested.x.3.rec", t.toString(deep));
    }

    @Test
    void claimIsTheFlatNamespaceGuard() {
        NameTable t = new NameTable(16);
        int a = str(t, 0, "A");
        int b = str(t, 0, "B");
        assertEquals(0, t.constOf(a));
        assertTrue(t.claim(a, 7));
        assertEquals(7, t.constOf(a));
        assertFalse(t.claim(a, 8), "second declaration of a name");
        assertEquals(7, t.constOf(a));
        assertFalse(t.claim(a, NameTable.DROPPED_UNSAFE), "a dropped record with a declared name is a duplicate");
        assertTrue(t.claim(b, NameTable.DROPPED_UNSAFE));
        assertEquals(NameTable.DROPPED_UNSAFE, t.constOf(b));
        assertFalse(t.claim(b, 9), "a declaration after a dropped record of the same name is a duplicate");
        assertFalse(t.claim(b, NameTable.DROPPED_UNSAFE));
        assertTrue(t.claim(0, 3), "the anonymous name can be declared");
        assertEquals(3, t.constOf(0));
        assertThrows(IllegalArgumentException.class, () -> t.claim(a, 0));
    }

    /** A name as a list of components, each a String or a BigInteger: the reference for the order. */
    private static int leanCmp(List<Object> x, List<Object> y) {
        int n = Math.min(x.size(), y.size());
        for (int i = 0; i < n; i++) {
            Object p = x.get(i);
            Object q = y.get(i);
            boolean ps = p instanceof String;
            boolean qs = q instanceof String;
            if (ps != qs) {
                return ps ? 1 : -1;
            }
            int c;
            if (ps) {
                byte[] a = ((String) p).getBytes(StandardCharsets.UTF_8);
                byte[] b = ((String) q).getBytes(StandardCharsets.UTF_8);
                c = java.util.Arrays.compareUnsigned(a, b);
            } else {
                c = ((BigInteger) p).compareTo((BigInteger) q);
            }
            if (c != 0) {
                return c < 0 ? -1 : 1;
            }
        }
        return Integer.compare(x.size(), y.size());
    }

    @Test
    void compareIsLeansNameOrder() {
        Random rnd = new Random(42);
        NameTable t = new NameTable(64);
        String[] words = {"", "a", "A", "ab", "b", "Nat", "rec", "_private", "x1", "x10", "é", "αβ", "😀",
            "z", "a\u0000b", "~"};
        BigInteger two64 = BigInteger.ONE.shiftLeft(64);
        List<List<Object>> names = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        names.add(new ArrayList<>());
        ids.add(0);
        Map<Integer, Integer> seen = new HashMap<>();
        for (int i = 0; i < 4000; i++) {
            int base = rnd.nextInt(names.size());
            List<Object> comps = new ArrayList<>(names.get(base));
            int id = ids.get(base);
            if (comps.size() > 7) {
                continue;
            }
            if (rnd.nextInt(3) == 0) {
                BigInteger v;
                switch (rnd.nextInt(5)) {
                    case 0:
                        v = BigInteger.valueOf(rnd.nextInt(4));
                        break;
                    case 1:
                        v = new BigInteger(64, rnd);
                        break;
                    case 2:
                        v = two64.subtract(BigInteger.valueOf(rnd.nextInt(2) + 1));
                        break;
                    case 3:
                        v = two64.add(BigInteger.valueOf(rnd.nextInt(3)));
                        break;
                    default:
                        v = new BigInteger(70 + rnd.nextInt(200), rnd).add(two64);
                        break;
                }
                comps.add(v);
                id = v.bitLength() <= 64 && rnd.nextBoolean() ? t.internNum(id, v.longValue()) : big(t, id, v.toString());
            } else {
                String w = words[rnd.nextInt(words.length)] + (rnd.nextInt(4) == 0 ? words[rnd.nextInt(words.length)] : "");
                comps.add(w);
                id = str(t, id, w);
            }
            if (seen.putIfAbsent(id, names.size()) == null) {
                names.add(comps);
                ids.add(id);
            } else {
                assertEquals(0, leanCmp(comps, names.get(seen.get(id))), "one id, two names");
            }
        }
        for (int i = 0; i < 100_000; i++) {
            int x = rnd.nextInt(names.size());
            int y = rnd.nextInt(names.size());
            int expect = leanCmp(names.get(x), names.get(y));
            int got = Integer.signum(t.compare(ids.get(x), ids.get(y)));
            if (expect != got) {
                assertEquals(expect, got, t.toString(ids.get(x)) + " vs " + t.toString(ids.get(y)));
            }
            assertEquals(expect == 0, ids.get(x).equals(ids.get(y)), "0 only for equal ids");
        }
    }

    @Test
    void growsFromATinyTable() {
        NameTable t = new NameTable(1);
        int n = 300_000;
        int[] ids = new int[n];
        for (int i = 0; i < n; i++) {
            int prefix = i < 10 ? 0 : ids[i / 10];
            ids[i] = (i & 3) == 0 ? t.internNum(prefix, i) : str(t, prefix, "name" + i);
        }
        assertEquals(n + 1, t.size());
        for (int i = 0; i < n; i += 7) {
            int prefix = i < 10 ? 0 : ids[i / 10];
            assertEquals(ids[i], (i & 3) == 0 ? t.internNum(prefix, i) : str(t, prefix, "name" + i));
            assertEquals(prefix, t.prefix(ids[i]));
        }
        assertEquals(n + 1, t.size());
    }
}
