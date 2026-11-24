package rlvm.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import rlvm.store.Model.M;

/** WP-A1: overlay hash-consing, marks, reset, growth and the cap. */
class OverlayTest {

    private static Model fixture() {
        Model fx = new Model();
        fx.declareLiteralPrerequisites(true);
        fx.builder.finish();
        return fx;
    }

    @Test
    void equalChildrenGiveEqualIds() {
        Model fx = fixture();
        Overlay o = new Overlay(fx.store, fx.store.lists(), 1 << 22);
        Terms t = new Terms(fx.store.terms(), o);
        int s = o.mkSort(fx.levelIds[1]);
        int c = o.mkConst(fx.constCids[1], fx.levelLists[1]);
        int v = o.mkBVar(0);
        assertEquals(s, o.mkSort(fx.levelIds[1]));
        assertNotEquals(s, o.mkSort(fx.levelIds[2]));
        assertEquals(c, o.mkConst(fx.constCids[1], fx.levelLists[1]));
        assertEquals(c, o.mkConstByName(fx.constNames[1], fx.levelLists[1]));
        assertNotEquals(c, o.mkConst(fx.constCids[1], fx.levelLists[3]));
        assertNotEquals(c, o.mkConst(fx.constCids[4], fx.levelLists[1]));
        assertEquals(v, o.mkBVar(0));
        assertNotEquals(v, o.mkBVar(1));
        int app = o.mkApp(c, v);
        assertEquals(app, o.mkApp(c, v));
        assertNotEquals(app, o.mkApp(v, c));
        int lam = o.mkLam(s, app);
        int pi = o.mkPi(s, app);
        assertEquals(lam, o.mkLam(s, app));
        assertEquals(pi, o.mkPi(s, app));
        assertNotEquals(lam, pi);
        int let = o.mkLet(s, c, app);
        assertEquals(let, o.mkLet(s, c, app));
        assertNotEquals(let, o.mkLet(s, c, v));
        int proj = o.mkProj(fx.constCids[3], 1, app);
        assertEquals(proj, o.mkProj(fx.constCids[3], 1, app));
        assertNotEquals(proj, o.mkProj(fx.constCids[3], 2, app));
        assertNotEquals(proj, o.mkProj(fx.constCids[0], 1, app));
        assertEquals(o.mkNatLit(5), o.mkNatLit(5));
        assertNotEquals(o.mkNatLit(5), o.mkNatLit(5L << 32));
        assertEquals(o.mkNatLitBig(fx.bigNat), o.mkNatLitBig(fx.bigNat));
        assertNotEquals(o.mkNatLit(fx.bigNat), o.mkNatLitBig(fx.bigNat), "a small literal and a pool index never collide");
        assertEquals(o.mkStrLit(fx.strLit), o.mkStrLit(fx.strLit));
        assertNotEquals(o.mkStrLit(fx.strLit), o.mkStrLit(fx.strLit2));
        assertEquals(5, t.b(o.mkStrLit(fx.strLit2)));
        assertNotEquals(o.mkFVar(s), o.mkFVar(s));
        assertEquals(o.mkApps(c, new int[] {9, v, s, v}, 1, 3), o.mkApp(o.mkApp(o.mkApp(c, v), s), v));
        // a shared child and an overlay child with the same structure are different children
        int sharedSort = fx.builder.sort(fx.levelIds[1]);
        t.refresh();
        assertNotEquals(o.mkApp(sharedSort, v), o.mkApp(s, v));
        assertEquals(o.mkApp(sharedSort, v), o.mkApp(sharedSort, v));
        // unbound constants keep their name
        int unbound = o.mkConstByName(fx.undeclaredName, 0);
        assertEquals(~fx.undeclaredName, t.a(unbound));
        assertEquals(fx.undeclaredName, t.c(unbound));
        assertTrue((t.header(unbound) & Node.F_UNRESOLVED) != 0);
        assertThrows(IllegalArgumentException.class, () -> o.mkConst(0, 0));
        assertThrows(IllegalArgumentException.class, () -> o.mkApp(0, v));
        assertThrows(IllegalArgumentException.class, () -> o.mkApp(v, Node.OVERLAY_BIT | 999_999));
    }

    @Test
    void resetForgetsEverything() {
        Model fx = fixture();
        Overlay o = new Overlay(fx.store, fx.store.lists(), 1L << 28);
        Terms t = new Terms(fx.store.terms(), o);
        Random rnd = new Random(3);
        for (int session = 0; session < 30; session++) {
            assertEquals(0, o.nodeCount());
            assertEquals(0, o.usedBytes());
            List<M> nodes = fx.random(rnd, gen(session == 7 ? 200_000 : 50 + rnd.nextInt(300)));
            IdentityHashMap<M, Integer> memo = new IdentityHashMap<>();
            int[] ids = new int[nodes.size()];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = fx.toOverlay(o, nodes.get(i), memo);
            }
            int count = o.nodeCount();
            assertTrue(count <= ids.length);
            assertEquals(32L * count, o.usedBytes());
            // everything is found again (the index survived its rehashes)
            IdentityHashMap<M, Integer> memo2 = new IdentityHashMap<>();
            for (int i = 0; i < ids.length; i++) {
                assertEquals(ids[i], fx.toOverlay(o, nodes.get(i), memo2));
            }
            assertEquals(count, o.nodeCount());
            long before = fx.digest(t, ids[ids.length - 1]);
            assertEquals(fx.digest(nodes.get(nodes.size() - 1)), before);
            o.reset();
        }
        assertTrue(o.allocatedBytes() <= (32L << 20) + (64L << 20), "the big session's arrays were given back");
    }

    private static Model.Gen gen(int size) {
        Model.Gen g = new Model.Gen();
        g.size = size;
        return g;
    }

    @Test
    void marksCutBackToAnEarlierState() {
        Model fx = fixture();
        // two theorems with proof blocks, to be decoded between marks
        Segs segs = new Segs(fx);
        Random rnd = new Random(5);
        List<Integer> blocks = new ArrayList<>();
        Model fx2 = new Model();
        fx2.declareLiteralPrerequisites(true);
        segs = new Segs(fx2);
        for (int i = 0; i < 4; i++) {
            List<M> ty = fx2.random(rnd, gen(6));
            List<M> val = fx2.random(rnd, gen(i == 2 ? 0 : 40));
            List<M> nodes = new ArrayList<>(ty);
            nodes.addAll(val);
            Segs.Seg s = segs.write(DeclKind.THM, fx2.name("t" + i), 0, nodes, ty.get(ty.size() - 1), val.get(val.size() - 1));
            blocks.add(s.block);
        }
        fx2.builder.finish();
        fx = fx2;
        Overlay o = new Overlay(fx.store, fx.store.lists(), 1L << 26);
        Terms t = new Terms(fx.store.terms(), o);
        for (int round = 0; round < 200; round++) {
            // phase 1: some nodes, a block, a free variable
            List<M> first = fx.random(rnd, gen(20 + rnd.nextInt(100)));
            IdentityHashMap<M, Integer> memo1 = new IdentityHashMap<>();
            int[] ids1 = new int[first.size()];
            for (int i = 0; i < ids1.length; i++) {
                ids1[i] = fx.toOverlay(o, first.get(i), memo1);
            }
            int b0 = blocks.get(rnd.nextInt(blocks.size()));
            int root0 = o.blockRoot(b0);
            int fv = o.mkFVar(ids1[0]);
            long digest0 = fx.digest(t, root0);
            long mark = o.mark();
            int count = o.nodeCount();
            // phase 2: more of everything, on top
            List<M> second = fx.random(rnd, gen(20 + rnd.nextInt(200)));
            IdentityHashMap<M, Integer> memo2 = new IdentityHashMap<>();
            int[] ids2 = new int[second.size()];
            for (int i = 0; i < ids2.length; i++) {
                ids2[i] = fx.toOverlay(o, second.get(i), memo2);
            }
            int b1 = blocks.get(rnd.nextInt(blocks.size()));
            int root1 = o.blockRoot(b1);
            int mixed = o.mkApp(o.mkApp(ids2[ids2.length - 1], root1), o.mkFVar(fv));
            assertTrue(o.nodeCount() >= count);
            o.resetTo(mark);
            assertEquals(count, o.nodeCount());
            // the first phase is intact: same ids on request, same block root, same structure
            IdentityHashMap<M, Integer> again = new IdentityHashMap<>();
            for (int i = 0; i < ids1.length; i++) {
                assertEquals(ids1[i], fx.toOverlay(o, first.get(i), again), "round " + round);
            }
            assertEquals(count, o.nodeCount(), "nothing new was made");
            assertEquals(root0, o.blockRoot(b0));
            assertEquals(digest0, fx.digest(t, root0));
            // forgotten ids are refused as children
            if (mixed != 0 && (mixed & Node.INDEX_MASK) > count) {
                final int stale = mixed;
                assertThrows(IllegalArgumentException.class, () -> o.mkApp(stale, stale));
            }
            // the second phase can be rebuilt and gets consistent ids
            IdentityHashMap<M, Integer> memo3 = new IdentityHashMap<>();
            int[] ids3 = new int[second.size()];
            for (int i = 0; i < ids3.length; i++) {
                ids3[i] = fx.toOverlay(o, second.get(i), memo3);
            }
            org.junit.jupiter.api.Assertions.assertArrayEquals(ids2, ids3, "same construction order, same ids");
            int root1b = o.blockRoot(b1);
            assertEquals(fx.digest(t, root1b), fx.digest(t, o.blockRoot(b1)));
            if (round % 20 == 19) {
                o.reset();
            } else if (round % 3 == 0) {
                o.resetTo(mark);
            }
        }
        final Overlay ov = o;
        assertThrows(IllegalArgumentException.class, () -> ov.resetTo(((long) 1 << 40) | 5));
    }

    /** A chain of {@code steps} applications over fresh literals: 2 nodes per step, none shared with earlier chains. */
    private static int chain(Overlay o, int head, int from, int steps) {
        int cur = head;
        for (int i = 0; i < steps; i++) {
            cur = o.mkApp(cur, o.mkNatLit(((long) from << 32) | i));
        }
        return cur;
    }

    @Test
    void resetToGivesBackWhatTheDeclarationBuilt() {
        Model fx = fixture();
        Overlay o = new Overlay(fx.store, fx.store.lists(), 1L << 30);
        Terms t = new Terms(fx.store.terms(), o);
        // what the session keeps: a few thousand nodes below the mark
        int c = o.mkConst(fx.constCids[0], 0);
        int kept = chain(o, c, 1, 3000);
        long keptDigest = fx.digest(t, kept);
        int keptNodes = o.nodeCount();
        long mark = o.mark();
        long baseBytes = o.allocatedBytes();
        assertTrue(baseBytes <= (33L << 20), "a small session holds at most the base arrays: " + baseBytes);
        for (int round = 0; round < 3; round++) {
            // one declaration of 1.4 M nodes (45 MB = 43 MiB): the arrays grow well beyond their base sizes
            int big = chain(o, kept, 2 + round, 700_000);
            assertEquals(703_000, t.spineLength(big));
            assertTrue(o.usedBytes() > (40L << 20));
            assertTrue(o.allocatedBytes() > (64L << 20) + (8L << 20), "grown: " + o.allocatedBytes());
            assertTrue(o.indexSlots() >= 1 << 22);
            o.resetTo(mark);
            assertEquals(keptNodes, o.nodeCount());
            assertEquals(32L * keptNodes, o.usedBytes());
            assertTrue(o.allocatedBytes() <= (32L << 20) + (1L << 20),
                    "round " + round + ": node array and index are back at their base sizes: " + o.allocatedBytes());
            assertTrue(o.indexSlots() <= 1 << 15, "the index is proportional to the " + keptNodes + " nodes kept");
            assertEquals(keptNodes, o.hashConsedCount());
            // the kept part is intact, is found again by hash-consing, and readers see the new array
            assertEquals(keptDigest, fx.digest(t, kept));
            assertEquals(kept, chain(o, c, 1, 3000));
            assertEquals(keptNodes, o.nodeCount(), "nothing was rebuilt");
            assertEquals(3000, t.spineLength(kept));
            assertEquals(c, t.head(kept));
        }
        // a declaration only a few times larger than what is kept: its entries are removed one by one,
        // and the index still follows the nodes that are left
        o.resetTo(((long) 0 << 32) | 1);
        int c2 = o.mkConst(fx.constCids[0], 0);
        int kept2 = chain(o, c2, 20, 10_000);
        int kept2Nodes = o.nodeCount();
        long mark2 = o.mark();
        int slotsBefore = o.indexSlots();
        assertTrue(slotsBefore <= 1 << 16, "20,001 nodes: " + slotsBefore + " slots");
        int medium = chain(o, kept2, 21, 30_000);
        assertEquals(40_000, t.spineLength(medium));
        assertTrue(o.indexSlots() >= 1 << 18, "80,001 nodes: " + o.indexSlots() + " slots");
        o.resetTo(mark2);
        assertEquals(kept2Nodes, o.nodeCount());
        assertEquals(kept2Nodes, o.hashConsedCount());
        assertEquals(1 << 16, o.indexSlots(), "back to the size for the 20,001 nodes kept");
        assertEquals(kept2, chain(o, c2, 20, 10_000));
        assertEquals(kept2Nodes, o.nodeCount());
        // back to nothing: everything goes, the arrays are at their base sizes
        int big = chain(o, kept2, 9, 700_000);
        assertTrue(big != 0);
        o.resetTo(((long) 0 << 32) | 1);
        assertEquals(0, o.nodeCount());
        assertEquals(0, o.hashConsedCount());
        assertTrue(o.allocatedBytes() <= (32L << 20) + 4L * Overlay.KEEP_TABLE, "base node array and base index");
        assertEquals(Kind.CONST, t.kind(o.mkConst(fx.constCids[0], 0)));
    }

    @Test
    void idsBelowAMarkAreNeverHandedOutAgain() {
        // Three ways resetTo cleans the index (remove the forgotten, re-enter the kept, drop all) against
        // one model: an id that survives a reset keeps its node, and a node made later never gets it.
        Model fx = fixture();
        Overlay o = new Overlay(fx.store, fx.store.lists(), 1L << 28);
        Terms t = new Terms(fx.store.terms(), o);
        Random rnd = new Random(77);
        java.util.ArrayList<int[]> keptIds = new java.util.ArrayList<>();      // ids alive since some round
        java.util.ArrayList<long[]> keptDigests = new java.util.ArrayList<>();
        java.util.ArrayDeque<long[]> marks = new java.util.ArrayDeque<>();     // {mark, node count, kept lists}
        for (int round = 0; round < 400; round++) {
            int what = rnd.nextInt(10);
            if (what < 5 || marks.isEmpty()) {
                // build something and keep it under a new mark
                int size = rnd.nextInt(8) == 0 ? 5000 + rnd.nextInt(20_000) : 5 + rnd.nextInt(300);
                List<M> nodes = fx.random(rnd, gen(size));
                IdentityHashMap<M, Integer> memo = new IdentityHashMap<>();
                int[] ids = new int[nodes.size()];
                long[] digests = new long[nodes.size()];
                for (int i = 0; i < ids.length; i++) {
                    ids[i] = fx.toOverlay(o, nodes.get(i), memo);
                }
                for (int i = 0; i < ids.length; i += 1 + ids.length / 16) {
                    digests[i] = fx.digest(t, ids[i]);
                }
                if (rnd.nextInt(4) == 0) {
                    ids = java.util.Arrays.copyOf(ids, ids.length + 1);
                    digests = java.util.Arrays.copyOf(digests, digests.length + 1);
                    ids[ids.length - 1] = o.mkFVar(ids[0]);
                    digests[ids.length - 1] = fx.digest(t, ids[ids.length - 1]);
                }
                keptIds.add(ids);
                keptDigests.add(digests);
                marks.push(new long[] {o.mark(), o.nodeCount(), keptIds.size()});
            } else if (what < 9) {
                // a declaration on top of the newest mark (sometimes far larger than everything kept), then back
                long[] m = marks.peek();
                if (rnd.nextInt(5) == 0) {
                    marks.pop();
                    if (!marks.isEmpty()) {
                        m = marks.peek();
                    } else {
                        m = new long[] {1L, 0, 0};
                    }
                }
                int size = rnd.nextInt(6) == 0 ? 20_000 + rnd.nextInt(60_000) : 10 + rnd.nextInt(2000);
                List<M> nodes = fx.random(rnd, gen(size));
                IdentityHashMap<M, Integer> memo = new IdentityHashMap<>();
                int floor = (int) m[1];
                for (int i = 0; i < nodes.size(); i++) {
                    int id = fx.toOverlay(o, nodes.get(i), memo);
                    if (Node.isOverlay(id) && (id & Node.INDEX_MASK) > o.nodeCount()) {
                        org.junit.jupiter.api.Assertions.fail("id beyond the node count");
                    }
                }
                o.resetTo(m[0]);
                assertEquals(floor, o.nodeCount(), "round " + round);
                while (keptIds.size() > (int) m[2]) {
                    keptIds.remove(keptIds.size() - 1);
                    keptDigests.remove(keptDigests.size() - 1);
                }
                // new nodes take ids from the mark upward only
                int probe = o.mkApp(o.mkNatLit(0x5EED0000L + round), o.mkNatLit(0x5EED0000L + round));
                assertTrue((probe & Node.INDEX_MASK) > floor, "a new node never takes a surviving id");
                o.resetTo(m[0]);
            } else {
                o.reset();
                keptIds.clear();
                keptDigests.clear();
                marks.clear();
            }
            // everything kept is what it was
            for (int k = 0; k < keptIds.size(); k++) {
                int[] ids = keptIds.get(k);
                long[] digests = keptDigests.get(k);
                for (int i = 0; i < ids.length; i += 1 + ids.length / 16) {
                    if (digests[i] != 0) {
                        assertEquals(digests[i], fx.digest(t, ids[i]), "round " + round + " list " + k + " node " + i);
                    }
                }
            }
            // and the index holds exactly the hash-consed nodes: load at most one half
            assertTrue(o.hashConsedCount() <= o.nodeCount());
            assertTrue(2L * o.hashConsedCount() <= o.indexSlots());
        }
    }

    @Test
    void resetToReleasesTheLiteralsOfTheDeclaration() {
        Model fx = fixture();
        LitPool pool = new LitPool(fx.store.lits());
        Overlay o = new Overlay(fx.store, fx.store.lists(), fx.store.levelInfo(), pool, 1L << 28);
        Terms t = new Terms(fx.store.terms(), o);
        Random rnd = new Random(21);
        // a literal the session keeps
        long[] keepLimbs = {11, 12, 13};
        int keepIdx = pool.addNat(keepLimbs, 0, 3);
        int keepNode = o.mkNatLitBig(keepIdx);
        java.util.HashSet<Integer> everIssued = new java.util.HashSet<>();
        everIssued.add(keepIdx);
        for (int round = 0; round < 200; round++) {
            long mark = o.mark();
            int nodesAtMark = o.nodeCount();
            long poolBytes = pool.bytes();
            int liveAtMark = pool.natLiveCount();
            // the declaration computes numbers, some of 80 KB, and builds terms over them
            int cur = keepNode;
            int[] made = new int[1 + rnd.nextInt(30)];
            for (int i = 0; i < made.length; i++) {
                int len = rnd.nextInt(5) == 0 ? 10_000 : 2 + rnd.nextInt(50);
                long[] v = new long[len];
                for (int j = 0; j < len; j++) {
                    v[j] = rnd.nextLong() | 1;
                }
                made[i] = pool.addNat(v, 0, len);
                assertTrue(everIssued.add(made[i]), "literal index " + made[i] + " was issued twice in one session");
                int lit = o.mkNatLitBig(made[i]);
                assertEquals(made[i], t.a(lit));
                cur = o.mkApp(cur, lit);
            }
            assertEquals(made.length, t.spineLength(cur));
            // a literal made after the mark without a node yet: the same term position, a later pool position
            o.resetTo(mark);
            assertEquals(nodesAtMark, o.nodeCount());
            assertEquals(liveAtMark, pool.natLiveCount(), "the declaration's literals were released with its terms");
            assertTrue(pool.bytes() <= poolBytes + 16L * everIssued.size(), "and their memory was given back");
            for (int idx : made) {
                assertTrue(pool.natReleased(idx));
                final int dead = idx;
                assertThrows(IllegalStateException.class, () -> o.mkNatLitBig(dead), "a released literal makes no node");
            }
            // what the session kept still reads the same
            assertEquals(keepIdx, t.a(keepNode));
            assertEquals(3, pool.natLimbCount(keepIdx));
            assertEquals(13, pool.natLimb(keepIdx, 2));
            assertEquals(keepNode, o.mkNatLitBig(pool.addNat(keepLimbs, 0, 3)));
        }
        // mark() taken after a literal exists keeps that literal: the later position wins for equal term positions
        long m1 = o.mark();
        int pending = pool.addNat(new long[] {7, 7, 7, 7}, 0, 4);
        long m2 = o.mark();
        assertEquals(m1, m2, "no node was made in between");
        int lit = o.mkNatLitBig(pending);
        o.resetTo(m2);
        assertTrue(!pool.natReleased(pending), "a literal made before the newest mark at this position is kept");
        assertEquals(lit, o.mkNatLitBig(pending), "and gets its node (the same id, the position after the mark) again");
        // a full reset of the context: the pool is the caller's to reset, the overlay forgets its marks
        o.reset();
        pool.reset();
        long m3 = o.mark();
        int afterReset = pool.addNat(new long[] {1, 2, 3}, 0, 3);
        o.mkNatLitBig(afterReset);
        o.resetTo(m3);
        assertTrue(pool.natReleased(afterReset));
        // reset() forgets the remembered marks: a position of the old session must not release literals of
        // the new one (the mark value is rebuilt by hand here; an honest caller gets it from mark())
        o.reset();
        pool.reset();
        int a0 = o.mkBVar(0);
        int a1 = o.mkApp(a0, a0);
        long oldSession = o.mark();
        assertEquals(3L, oldSession, "two nodes, no block");
        o.reset();
        pool.reset();
        int keptLit = pool.addNat(new long[] {21, 22, 23}, 0, 3);
        int keptLitNode = o.mkNatLitBig(keptLit);
        int keptApp = o.mkApp(keptLitNode, keptLitNode);
        assertEquals(2, o.nodeCount());
        o.mkApp(keptApp, keptApp);
        o.resetTo(3L);
        assertFalse(pool.natReleased(keptLit), "a literal that a kept node of this session names stays");
        assertEquals(23, pool.natLimb(t.a(t.a(keptApp)), 2));
        assertTrue(a1 != 0);
        // a pool that was reset without the overlay: the overlay's remembered marks are stale and are dropped
        long m4 = o.mark();
        o.mkNatLitBig(pool.addNat(new long[] {4, 5, 6}, 0, 3));
        pool.reset();
        o.resetTo(m4);
        int fresh = pool.addNat(new long[] {9, 9, 9}, 0, 3);
        assertEquals(-1, fresh, "indices start again only after the pool's own reset");
        long m5 = o.mark();
        o.mkNatLitBig(pool.addNat(new long[] {8, 8, 8}, 0, 3));
        o.resetTo(m5);
        assertEquals(1, pool.natLiveCount());
        assertEquals(9, pool.natLimb(fresh, 0));
        // an overlay over the shared pool never truncates it
        Overlay plain = new Overlay(fx.store, fx.store.lists(), 1L << 22);
        long pm = plain.mark();
        int sharedLit = plain.mkNatLitBig(fx.bigNat);
        plain.resetTo(pm);
        assertEquals(3, fx.store.lits().natLimbCount(fx.bigNat));
        assertTrue(sharedLit != 0);
    }

    @Test
    void growthKeepsReadersValidAndTheCapHolds() {
        Model fx = fixture();
        Overlay o = new Overlay(fx.store, fx.store.lists(), 8L << 20);
        Terms t = new Terms(fx.store.terms(), o);
        Terms second = new Terms(fx.store.terms(), o);
        int[] first = o.array();
        int c = o.mkConst(fx.constCids[0], 0);
        int cur = c;
        int n = 0;
        try {
            for (;;) {
                cur = o.mkApp(cur, o.mkNatLit(n));
                n++;
                if ((n & 0xFFF) == 0) {
                    assertEquals(n, t.spineLength(cur) & 0xFFFFFFF);
                }
            }
        } catch (Overlay.OverlayFullException full) {
            // expected: 8 MiB = 262,144 nodes, two per step
        }
        assertTrue(n > 100_000 && n < 131_072, "steps before the cap: " + n);
        assertTrue(o.usedBytes() <= 8L << 20);
        assertNotEquals(first, o.array(), "the array was reallocated");
        assertEquals(c, t.head(cur));
        assertEquals(c, second.head(cur));
        assertEquals(n, t.spineLength(cur));
        int[] args = new int[n];
        assertEquals(c, t.spine(cur, args, 0));
        for (int i = 0; i < n; i += 997) {
            assertEquals(i, t.natSmall(args[i]));
        }
        // still usable after a reset
        o.reset();
        assertEquals(0, o.nodeCount());
        int again = o.mkConst(fx.constCids[0], 0);
        assertEquals(Kind.CONST, t.kind(again));
        assertEquals(Kind.CONST, second.kind(again));
    }
}
