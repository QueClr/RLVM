package rlvm.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** WP-A2: the literal pool. Interning by content (RECONCILIATION.md "Literal identity"), worker pools, code points. */
class LitPoolTest {

    private static int add(LitPool p, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        return p.addString(b, 0, b.length);
    }

    @Test
    void naturalsAreInternedByContent() {
        LitPool p = new LitPool();
        int a = p.addNat(new long[] {0, 1}, 0, 2);
        int b = p.addNat(new long[] {9, 0, 1, 7}, 1, 2);
        assertEquals(1, a);
        assertEquals(a, b, "same limbs, same index");
        int c = p.addNat(new long[] {1, 1}, 0, 2);
        assertNotEquals(a, c);
        int d = p.addNat(new long[] {0, 1, 5}, 0, 3);
        assertNotEquals(a, d);
        assertEquals(2, p.natLimbCount(a));
        assertEquals(3, p.natLimbCount(d));
        long[] out = new long[5];
        p.natLimbs(d, out, 1);
        assertArrayEquals(new long[] {0, 0, 1, 5, 0}, out);
        assertEquals(5, p.natLimb(d, 2));
        assertTrue(p.natEquals(a, b));
        assertFalse(p.natEquals(a, c));
        assertFalse(p.natEquals(a, d));
        assertThrows(IllegalArgumentException.class, () -> p.addNat(new long[] {1, 0}, 0, 2));
        assertThrows(IllegalArgumentException.class, () -> p.addNat(new long[0], 0, 0));
        // many values through the rehashes
        Random rnd = new Random(1);
        int n = 20_000;
        int[] idx = new int[n];
        long[][] vals = new long[n][];
        for (int i = 0; i < n; i++) {
            int len = 2 + rnd.nextInt(4);
            vals[i] = new long[len];
            for (int j = 0; j < len; j++) {
                vals[i][j] = rnd.nextInt(3);
            }
            vals[i][len - 1] = 1 + rnd.nextInt(2);
            idx[i] = p.addNat(vals[i], 0, len);
        }
        for (int i = 0; i < n; i++) {
            assertEquals(idx[i], p.addNat(vals[i], 0, vals[i].length));
            long[] got = new long[p.natLimbCount(idx[i])];
            p.natLimbs(idx[i], got, 0);
            assertArrayEquals(vals[i], got);
        }
        assertTrue(p.natCount() < n, "small alphabet: many duplicates were merged");
        assertThrows(IllegalStateException.class, p::reset);
    }

    @Test
    void largeNaturalsAreStoredOnceAndNeverCopied() {
        LitPool shared = new LitPool();
        LitPool w = new LitPool(shared);
        Random rnd = new Random(9);
        // numbers around the packing limit and well above it, many of them: the pool's footprint stays
        // close to the payload (the old doubling array needed up to three times as much)
        int n = 3000;
        long[][] vals = new long[n][];
        int[] idx = new int[n];
        long payload = 0;
        for (int i = 0; i < n; i++) {
            int len = i % 7 == 0 ? 9531 : (i % 3 == 0 ? 1023 + rnd.nextInt(3) : 2 + rnd.nextInt(400));
            vals[i] = new long[len];
            for (int j = 0; j < len; j++) {
                vals[i][j] = rnd.nextLong();
            }
            vals[i][len - 1] |= 1;
            idx[i] = w.addNat(vals[i], 0, len);
            assertEquals(-(i + 1), idx[i]);
            payload += 8L * len;
        }
        assertTrue(w.bytes() < payload + payload / 8 + (2L << 20), "footprint " + w.bytes() + " for a payload of " + payload);
        for (int i = 0; i < n; i++) {
            assertEquals(idx[i], w.addNat(vals[i], 0, vals[i].length));
            assertEquals(vals[i].length, w.natLimbCount(idx[i]));
            long[] got = new long[vals[i].length];
            w.natLimbs(idx[i], got, 0);
            assertArrayEquals(vals[i], got);
            assertEquals(vals[i][vals[i].length / 2], w.natLimb(idx[i], vals[i].length / 2));
        }
        // marks: release the tail, keep the head; the indices of the released entries are retired
        long before = w.bytes();
        long mark = w.mark();
        assertThrows(IllegalArgumentException.class, () -> w.addNat(new long[20000], 0, 1), "a leading zero limb");
        long[] huge = new long[50_000];
        Arrays.fill(huge, 7);
        int h1 = w.addNat(huge, 0, huge.length);
        int s1 = w.addNat(new long[] {5, 6}, 0, 2);
        int s2 = w.addNat(new long[] {5, 7}, 0, 2);
        byte[] str = "worker string".getBytes(StandardCharsets.UTF_8);
        int st = w.addString(str, 0, str.length);
        assertEquals(-(n + 1), h1);
        assertTrue(w.bytes() >= before + 8L * huge.length);
        w.truncateTo(mark);
        assertEquals(n, w.natLiveCount());
        assertEquals(0, w.stringLiveCount());
        assertEquals(n + 3, w.natCount(), "the released indices stay handed out");
        assertTrue(w.bytes() <= before + 64, "the released numbers were given back: " + w.bytes() + " > " + before);
        for (int i = 0; i < n; i += 97) {
            assertEquals(idx[i], w.addNat(vals[i], 0, vals[i].length), "entries before the mark survive");
        }
        for (int dead : new int[] {h1, s1, s2}) {
            assertTrue(w.natReleased(dead));
            assertThrows(IllegalStateException.class, () -> w.natLimbCount(dead), "a released entry is never read");
            assertThrows(IllegalStateException.class, () -> w.natLimb(dead, 0));
            assertThrows(IllegalStateException.class, () -> w.natLimbs(dead, new long[4], 0));
            assertThrows(IllegalStateException.class, () -> w.natEquals(dead, idx[0]));
        }
        assertTrue(w.stringReleased(st));
        assertThrows(IllegalStateException.class, () -> w.stringByteLength(st));
        assertThrows(IllegalStateException.class, () -> w.stringCodePointCount(st));
        assertThrows(IllegalStateException.class, () -> w.stringBytes(st, new byte[64], 0));
        assertFalse(w.natReleased(idx[5]));
        int again7 = w.addNat(new long[] {5, 7}, 0, 2);
        int again6 = w.addNat(new long[] {5, 6}, 0, 2);
        assertEquals(-(n + 4), again7, "a released index is not handed out again");
        assertEquals(-(n + 5), again6);
        int st2 = w.addString(str, 0, str.length);
        assertEquals(-2, st2);
        assertEquals(str.length, w.stringByteLength(st2));
        assertTrue(s1 != s2 && st < 0);
        w.truncateTo(mark);
        w.truncateTo(mark);
        assertEquals(n, w.natLiveCount(), "truncating twice is harmless");
        assertTrue(w.natReleased(again7) && w.stringReleased(st2));
        // random marks against a model: every live entry keeps index and content, no index is ever
        // issued twice in a session, released entries cannot be read, memory follows the live payload
        w.reset();
        assertEquals(0, w.natCount());
        java.util.TreeMap<Integer, long[]> live = new java.util.TreeMap<>();
        java.util.HashSet<Integer> issued = new java.util.HashSet<>();
        java.util.ArrayList<Integer> dead = new java.util.ArrayList<>();
        java.util.ArrayDeque<long[]> marks = new java.util.ArrayDeque<>();
        int serial = 0;
        for (int round = 0; round < 600; round++) {
            int what = rnd.nextInt(10);
            if (what < 6) {
                if (rnd.nextInt(3) == 0) {
                    marks.push(new long[] {w.mark(), live.isEmpty() ? 0 : live.lastKey()});
                }
                int adds = rnd.nextInt(40);
                for (int a = 0; a < adds; a++) {
                    int len = rnd.nextInt(10) == 0 ? 1024 + rnd.nextInt(2000) : 2 + rnd.nextInt(600);
                    long[] v = new long[len];
                    for (int j = 0; j < len; j++) {
                        v[j] = rnd.nextInt(4);
                    }
                    v[len - 1] = 1 + serial++;
                    int index = w.addNat(v, 0, len);
                    assertTrue(index < 0);
                    assertTrue(issued.add(index), "index " + index + " was issued before (round " + round + ")");
                    live.put(-index, v);
                }
            } else if (what < 9 && !marks.isEmpty()) {
                // back to a mark, usually the newest, sometimes an older one (the newer ones go with it)
                long[] m = marks.pop();
                while (!marks.isEmpty() && rnd.nextInt(4) == 0) {
                    m = marks.pop();
                }
                w.truncateTo(m[0]);
                while (!live.isEmpty() && live.lastKey() > m[1]) {
                    dead.add(-live.pollLastEntry().getKey());
                }
            } else if (what == 9 && rnd.nextInt(8) == 0) {
                w.reset();
                live.clear();
                issued.clear();
                dead.clear();
                marks.clear();
            }
            assertEquals(live.size(), w.natLiveCount(), "round " + round);
            long livePayload = 0;
            for (java.util.Map.Entry<Integer, long[]> en : live.entrySet()) {
                long[] v = en.getValue();
                livePayload += 8L * v.length;
                if (rnd.nextInt(4) == 0) {
                    assertEquals(-en.getKey(), w.addNat(v, 0, v.length), "round " + round);
                    assertEquals(v.length, w.natLimbCount(-en.getKey()));
                    assertEquals(v[v.length - 1], w.natLimb(-en.getKey(), v.length - 1));
                    assertEquals(v[0], w.natLimb(-en.getKey(), 0));
                }
            }
            for (int i = 0; i < dead.size(); i += 1 + rnd.nextInt(7)) {
                int d = dead.get(i);
                assertTrue(w.natReleased(d));
                assertThrows(IllegalStateException.class, () -> w.natLimbCount(d));
            }
            // one packing chunk of 512 KiB may be partly empty, and the rows of retired indices remain
            assertTrue(w.bytes() <= livePayload + (2L << 20) + 64L * issued.size(),
                    "round " + round + ": " + w.bytes() + " bytes for a live payload of " + livePayload);
        }
        assertThrows(IllegalStateException.class, () -> shared.truncateTo(0));
        assertThrows(IllegalArgumentException.class, () -> w.truncateTo(1L << 40));
    }

    @Test
    void releasedStringsKeepTheirIndicesRetired() {
        LitPool shared = new LitPool();
        int base = add(shared, "base");
        shared.publish();
        LitPool w = new LitPool(shared);
        Random rnd = new Random(4);
        java.util.TreeMap<Integer, String> live = new java.util.TreeMap<>();
        java.util.HashSet<Integer> issued = new java.util.HashSet<>();
        java.util.ArrayDeque<long[]> marks = new java.util.ArrayDeque<>();
        int serial = 0;
        for (int round = 0; round < 400; round++) {
            if (rnd.nextInt(3) != 0) {
                if (rnd.nextBoolean()) {
                    marks.push(new long[] {w.mark(), live.isEmpty() ? 0 : live.lastKey()});
                }
                for (int a = rnd.nextInt(12); a > 0; a--) {
                    String s = (rnd.nextBoolean() ? "é世😀" : "ascii") + (serial++) + "x".repeat(rnd.nextInt(50));
                    int index = add(w, s);
                    assertTrue(index < 0 && issued.add(index), "string index " + index + " issued twice");
                    live.put(-index, s);
                }
            } else if (!marks.isEmpty()) {
                long[] m = marks.pop();
                w.truncateTo(m[0]);
                while (!live.isEmpty() && live.lastKey() > m[1]) {
                    int d = -live.pollLastEntry().getKey();
                    assertTrue(w.stringReleased(d));
                    assertThrows(IllegalStateException.class, () -> w.stringCodePointCount(d));
                    assertThrows(IllegalStateException.class, () -> w.stringEquals(d, base));
                }
            }
            assertEquals(live.size(), w.stringLiveCount());
            for (java.util.Map.Entry<Integer, String> en : live.entrySet()) {
                String s = en.getValue();
                int index = -en.getKey();
                assertEquals(index, add(w, s), "a live string is found again under its index");
                byte[] got = new byte[w.stringByteLength(index)];
                w.stringBytes(index, got, 0);
                assertEquals(s, new String(got, StandardCharsets.UTF_8));
                int[] cps = s.codePoints().toArray();
                assertEquals(cps.length, w.stringCodePointCount(index));
                int k = rnd.nextInt(cps.length);
                assertEquals(cps[k], w.stringCodePointAt(index, k));
            }
            assertEquals(base, add(w, "base"), "the shared entry keeps its positive index");
        }
    }

    @Test
    void stringsAndCodePoints() {
        LitPool p = new LitPool();
        int e = add(p, "");
        int h = add(p, "hello");
        assertEquals(h, add(p, "hello"));
        assertNotEquals(h, add(p, "hellp"));
        assertNotEquals(e, h);
        assertEquals(0, p.stringByteLength(e));
        assertEquals(0, p.stringCodePointCount(e));
        assertEquals(5, p.stringCodePointCount(h));
        assertEquals('e', p.stringCodePointAt(h, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> p.stringCodePointAt(h, 5));
        assertThrows(IndexOutOfBoundsException.class, () -> p.stringCodePointAt(e, 0));
        // 1-, 2-, 3- and 4-byte scalars, U+0000 included
        String mixed = "a\u0000é世😀z߿ࠀ￿𐀀􏿿";
        int m = add(p, mixed);
        int[] cps = mixed.codePoints().toArray();
        assertEquals(cps.length, p.stringCodePointCount(m));
        assertEquals(mixed.getBytes(StandardCharsets.UTF_8).length, p.stringByteLength(m));
        for (int i = cps.length - 1; i >= 0; i--) {
            assertEquals(cps[i], p.stringCodePointAt(m, i), "scalar " + i);
        }
        byte[] back = new byte[p.stringByteLength(m)];
        p.stringBytes(m, back, 0);
        assertEquals(mixed, new String(back, StandardCharsets.UTF_8));
        assertTrue(p.stringEquals(m, add(p, mixed)));
        assertFalse(p.stringEquals(m, h));
        // a long non-ASCII string, random access
        StringBuilder sb = new StringBuilder();
        Random rnd = new Random(2);
        for (int i = 0; i < 300_000; i++) {
            sb.appendCodePoint(new int[] {'x', 0xe9, 0x4e16, 0x1f600}[rnd.nextInt(4)]);
        }
        String big = sb.toString();
        int bi = add(p, big);
        int[] bcp = big.codePoints().toArray();
        assertEquals(bcp.length, p.stringCodePointCount(bi));
        for (int i = 0; i < 2000; i++) {
            int k = rnd.nextInt(bcp.length);
            assertEquals(bcp[k], p.stringCodePointAt(bi, k));
        }
        assertEquals(bi, add(p, big));
        assertTrue(p.bytes() > big.length());
    }

    @Test
    void workerPoolsShareThePublishedBase() {
        LitPool shared = new LitPool();
        int sn = shared.addNat(new long[] {1, 2}, 0, 2);
        int ss = add(shared, "shared é");
        LitPool w = new LitPool(shared);
        assertTrue(w.isWorkerPool());
        assertFalse(shared.isWorkerPool());
        // before the publication a worker does not see the entry and makes its own
        int early = w.addNat(new long[] {1, 2}, 0, 2);
        assertTrue(early < 0);
        assertTrue(w.natEquals(early, sn), "different indices, equal contents");
        shared.publish();
        w.reset();
        assertEquals(sn, w.addNat(new long[] {1, 2}, 0, 2), "a published shared value keeps its index");
        assertEquals(ss, add(w, "shared é"));
        int own = w.addNat(new long[] {3, 4, 5}, 0, 3);
        assertTrue(own < 0);
        assertEquals(own, w.addNat(new long[] {3, 4, 5}, 0, 3));
        int own2 = w.addNat(new long[] {3, 4, 6}, 0, 3);
        assertNotEquals(own, own2);
        assertEquals(3, w.natLimbCount(own));
        assertEquals(5, w.natLimb(own, 2));
        assertEquals(2, w.natLimbCount(sn), "a worker pool resolves shared indices");
        int ws = add(w, "worker 😀!");
        assertTrue(ws < 0);
        assertEquals(ws, add(w, "worker 😀!"));
        assertEquals(9, w.stringCodePointCount(ws));
        assertEquals(0x1f600, w.stringCodePointAt(ws, 7));
        assertEquals('!', w.stringCodePointAt(ws, 8));
        assertEquals(0xe9, w.stringCodePointAt(ss, 7));
        assertFalse(w.stringEquals(ws, ss));
        assertTrue(w.stringEquals(ss, ss));
        // later additions to the shared pool do not disturb the worker
        for (int i = 0; i < 5000; i++) {
            shared.addNat(new long[] {i, 9}, 0, 2);
            add(shared, "s" + i);
        }
        assertEquals(sn, w.addNat(new long[] {1, 2}, 0, 2));
        assertTrue(w.addNat(new long[] {17, 9}, 0, 2) < 0, "unpublished shared entries stay invisible");
        shared.publish();
        assertTrue(w.addNat(new long[] {18, 9}, 0, 2) > 0);
        assertTrue(add(w, "s77") > 0);
        // reset forgets the worker's entries; only a reset lets indices start again
        w.reset();
        assertEquals(0, w.natCount());
        assertEquals(0, w.stringCount());
        assertEquals(-1, w.addNat(new long[] {8, 8, 8}, 0, 3));
        assertEquals(-1, add(w, "fresh"));
        assertEquals(5, w.stringCodePointCount(-1));
        assertThrows(IllegalArgumentException.class, () -> new LitPool(w));
    }
}
