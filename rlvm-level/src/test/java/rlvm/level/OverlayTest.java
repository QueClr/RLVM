package rlvm.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.Test;

/** Frozen base + per-worker overlays: id layout, reset, memo and cache validity, concurrency. */
class OverlayTest {

    /** All names are registered up front: worker threads only read the oracle. */
    private static Lv.Names names() {
        return new Lv.Names("x", "w", "v", "u");
    }

    private static LevelStore randomBase(Lv.Names n, long seed, int count, List<RefLevel> out) {
        Random rnd = new Random(seed);
        LevelStore base = new LevelStore(n);
        for (int i = 0; i < count; i++) {
            RefLevel r = Lv.random(rnd, 1 + rnd.nextInt(5), 3);
            Lv.toStore(base, r, n);
            if (out != null) {
                out.add(r);
            }
        }
        return base;
    }

    @Test
    void frozenBaseIsReadOnly() {
        Lv.Names n = names();
        LevelStore base = new LevelStore(n);
        int u = base.param(n.id("u"));
        int v = base.param(n.id("v"));
        int m = base.max(u, v);
        int[] buf = {n.id("u")};
        int pu = base.internList(buf, 0, 1);
        buf[0] = v;
        int lv = base.internList(buf, 0, 1);
        assertThrows(IllegalStateException.class, base::newOverlay);
        assertThrows(IllegalStateException.class, base::reset);
        base.freeze();
        base.freeze();
        assertTrue(base.isFrozen());
        // reads and lookups of existing objects work
        assertEquals(m, base.max(u, v));
        assertEquals(u, base.param(n.id("u")));
        assertEquals(LevelStore.KIND_MAX, base.kind(m));
        assertTrue(base.isEquiv(m, base.max(u, v)));
        assertTrue(base.isGeq(m, u));
        assertFalse(base.isGeq(u, m));
        assertTrue(base.canonId(m) > 0);
        assertEquals(pu, base.internList(new int[] {n.id("u")}, 0, 1));
        assertTrue(base.paramsDeclared(u, pu));
        // anything that would create something is refused
        assertThrows(IllegalStateException.class, () -> base.succ(m));
        assertThrows(IllegalStateException.class, () -> base.max(v, u));
        assertThrows(IllegalStateException.class, () -> base.param(n.id("w")));
        assertThrows(IllegalStateException.class, () -> base.internList(new int[] {m, m}, 0, 2));
        assertThrows(IllegalStateException.class, () -> base.mkSubst(pu, lv));
        // freeze() normalised every level, so a frozen base answers for its own levels ...
        assertEquals(m, base.normalize(m));
        assertTrue(base.isEquivOfficial(m, m) && base.isGeqOfficial(m, u));
        int size = base.size();
        LevelStore ov = base.newOverlay();
        assertEquals(size, base.size());
        assertEquals(size, ov.size());
        assertThrows(IllegalStateException.class, base::reset);
        assertTrue(ov.isOverlay() && !base.isOverlay());
    }

    @Test
    void overlayIdsAndReset() {
        Lv.Names n = names();
        List<RefLevel> made = new ArrayList<>();
        LevelStore base = randomBase(n, 21, 500, made);
        base.freeze();
        int bs = base.size();
        int bl = base.listCount();
        LevelStore ov = base.newOverlay();
        assertEquals(bs, ov.baseSize());
        assertEquals(bl, ov.baseListCount());
        // every base level is the same level in the overlay, and re-interning finds the base id
        for (int id = 1; id < bs; id++) {
            RefLevel r = Lv.fromStore(base, id, n);
            assertEquals(r, Lv.fromStore(ov, id, n));
            assertEquals(id, Lv.toStore(ov, r, n));
            assertEquals(base.canonId(id), ov.canonId(id));
            assertEquals(base.depth(id), ov.depth(id));
        }
        assertEquals(bs, ov.size());
        Random rnd = new Random(22);
        List<RefLevel> fresh = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            RefLevel r = Lv.random(rnd, 2 + rnd.nextInt(5), 4);
            fresh.add(r);
            ids.add(Lv.toStore(ov, r, n));
        }
        int grown = ov.size();
        assertTrue(grown > bs);
        assertEquals(bs, base.size());
        int[] buf = {ids.get(0), ids.get(1), ids.get(2)};
        int list = ov.internList(buf, 0, 3);
        assertTrue(list >= bl);
        for (int round = 0; round < 3; round++) {
            ov.reset();
            assertEquals(bs, ov.size());
            assertEquals(bl, ov.listCount());
            // base ids still resolve, the same construction order gives the same ids again
            for (int i = 0; i < made.size(); i += 7) {
                int id = Lv.toStore(ov, made.get(i), n);
                assertTrue(id < bs);
                assertEquals(made.get(i), Lv.fromStore(ov, id, n));
            }
            assertEquals(bs, ov.size());
            for (int i = 0; i < fresh.size(); i++) {
                assertEquals(ids.get(i).intValue(), Lv.toStore(ov, fresh.get(i), n));
            }
            assertEquals(grown, ov.size());
            assertEquals(list, ov.internList(buf, 0, 3));
        }
    }

    @Test
    void twoOverlaysOfOneBaseAreIndependentAndDeterministic() {
        Lv.Names n = names();
        LevelStore base = randomBase(n, 23, 300, null);
        base.freeze();
        LevelStore o1 = base.newOverlay();
        LevelStore o2 = base.newOverlay();
        Random rnd = new Random(24);
        for (int i = 0; i < 2000; i++) {
            RefLevel r = Lv.random(rnd, 1 + rnd.nextInt(6), 4);
            int a = Lv.toStore(o1, r, n);
            int b = Lv.toStore(o2, r, n);
            assertEquals(a, b);
            assertEquals(o1.normalize(a), o2.normalize(b));
            assertEquals(o1.canonId(a), o2.canonId(b));
        }
        // different content under the same ids after diverging
        o1.reset();
        int p = o1.succ(o1.succ(o1.param(n.id("x"))));
        assertEquals(RefLevel.succ(RefLevel.succ(RefLevel.param("x"))), Lv.fromStore(o1, p, n));
        assertTrue(o2.size() > o1.size());
    }

    @Test
    void memoSlotsOfReusedIdsAreFresh() {
        Lv.Names n = names();
        LevelStore base = new LevelStore(n);
        int u = base.param(n.id("u"));
        int v = base.param(n.id("v"));
        base.freeze();
        LevelStore ov = base.newOverlay();
        int first = ov.size();
        // first tenant of the id: succ (imax u 1)
        int a = ov.succ(ov.imax(u, LevelStore.ONE));
        int na = ov.normalize(a);
        int ca = ov.canonId(a);
        assertEquals(Lv.parse("(s (m u 1))"), Lv.fromStore(ov, na, n));
        assertTrue(ca > 0);
        ov.reset();
        // second tenants: imax v u gets `first`, then max v (imax v u) gets the id `a` had
        int b0 = ov.imax(v, u);
        int b = ov.max(v, b0);
        assertEquals(first, b0);
        assertEquals(a, b);
        RefLevel rb = Lv.parse("(m v (i v u))");
        assertEquals(RefLevel.normalize(rb), Lv.fromStore(ov, ov.normalize(b), n));
        assertTrue(ov.isEquiv(b, ov.max(v, ov.imax(v, u))));
        assertTrue(Lv.semEq(rb, Lv.fromStore(ov, ov.canonRep(b), n)));
        assertFalse(ov.isEquiv(b, ov.succ(ov.max(u, LevelStore.ONE))));
        assertFalse(ov.isNeverZero(b));
        assertEquals(1, ov.depth(b0));
    }

    @Test
    void instantiateCacheDoesNotSurviveReset() {
        Lv.Names n = names();
        LevelStore base = new LevelStore(n);
        int u = base.param(n.id("u"));
        int v = base.param(n.id("v"));
        int w = base.param(n.id("w"));
        int l = base.max(u, base.succ(v));
        int[] buf = {n.id("u"), n.id("v")};
        int names2 = base.internList(buf, 0, 2);
        base.freeze();
        LevelStore ov = base.newOverlay();

        int sw = ov.succ(w);
        buf[0] = sw;
        buf[1] = w;
        int args1 = ov.internList(buf, 0, 2);
        int s1 = ov.mkSubst(names2, args1);
        assertTrue(s1 >= ov.baseListCount());
        int r1 = ov.instantiate(l, s1);
        assertEquals(Lv.parse("(s w)"), Lv.fromStore(ov, r1, n));      // max (w+1) (w+1)
        assertEquals(r1, ov.instantiate(l, s1));
        int ll = ov.internList(new int[] {l, u}, 0, 2);
        int rl1 = ov.instantiateList(ll, s1);
        assertEquals(r1, ov.listGet(rl1, 0));
        assertEquals(sw, ov.listGet(rl1, 1));
        assertEquals(rl1, ov.instantiateList(ll, s1));

        ov.reset();
        // same ids, other contents: [u := v, v := 0]
        buf[0] = v;
        buf[1] = LevelStore.ZERO;
        int args2 = ov.internList(buf, 0, 2);
        assertEquals(args1, args2);
        int s2 = ov.mkSubst(names2, args2);
        assertEquals(s1, s2);
        int r2 = ov.instantiate(l, s2);
        assertEquals(Lv.parse("(m v 1)"), Lv.fromStore(ov, r2, n));
        int ll2 = ov.internList(new int[] {l, u}, 0, 2);
        assertEquals(ll, ll2);
        int rl2 = ov.instantiateList(ll2, s2);
        assertEquals(r2, ov.listGet(rl2, 0));
        assertEquals(v, ov.listGet(rl2, 1));
    }

    @Test
    void entriesMadeOfBaseIdsSurviveReset() {
        Lv.Names n = names();
        LevelStore base = new LevelStore(n);
        int u = base.param(n.id("u"));
        int v = base.param(n.id("v"));
        int l = base.imax(u, v);
        int target = base.succ(u);
        int names1 = base.internList(new int[] {n.id("v")}, 0, 1);
        int args = base.internList(new int[] {base.succ(u)}, 0, 1);
        int subst = base.mkSubst(names1, args);
        assertTrue(subst > 0);
        base.freeze();
        LevelStore ov = base.newOverlay();
        assertEquals(subst, ov.mkSubst(names1, args));
        for (int round = 0; round < 3; round++) {
            // imax u v [v := u+1] = mk_imax(u, u+1) = mk_max(u, u+1) = u+1 (same base, larger offset): a base level
            assertEquals(target, ov.instantiate(l, subst));
            ov.succ(ov.succ(ov.succ(target)));
            ov.reset();
        }
    }

    @Test
    void declaredParameterMemoDoesNotSurviveReset() {
        Lv.Names n = names();
        LevelStore base = new LevelStore(n);
        int u = base.param(n.id("u"));
        int l = base.succ(base.max(u, u));
        base.freeze();
        LevelStore ov = base.newOverlay();
        int listU = ov.internList(new int[] {n.id("u")}, 0, 1);
        assertTrue(ov.paramsDeclared(l, listU));
        assertTrue(ov.paramsDeclared(l, listU));
        ov.reset();
        int listV = ov.internList(new int[] {n.id("v")}, 0, 1);
        assertEquals(listU, listV);
        assertFalse(ov.paramsDeclared(l, listV));
        assertEquals(u, ov.undeclaredParam(l, listV));
        // an overlay level checked against an overlay list
        int ol = ov.succ(ov.param(n.id("v")));
        assertTrue(ov.paramsDeclared(ol, listV));
        ov.reset();
        int listU2 = ov.internList(new int[] {n.id("u")}, 0, 1);
        int ol2 = ov.succ(ov.param(n.id("v")));
        assertEquals(ol, ol2);
        assertEquals(listV, listU2);
        assertFalse(ov.paramsDeclared(ol2, listU2));
    }

    @Test
    void overlayGrowsAndShrinksManyTimes() {
        Lv.Names n = names();
        List<RefLevel> made = new ArrayList<>();
        LevelStore base = randomBase(n, 25, 200, made);
        base.freeze();
        LevelStore ov = base.newOverlay();
        int bs = ov.size();
        int u = ov.param(n.id("u"));
        for (int round = 0; round < 4; round++) {
            int count = round == 2 ? 120000 : 3000;
            int[] ids = new int[count];
            int prev = u;
            for (int i = 0; i < count; i++) {
                // distinct levels: a succ chain interleaved with max nodes over it
                prev = (i & 1) == 0 ? ov.succ(prev) : ov.max(prev, ov.ofNat(i % 50));
                ids[i] = prev;
            }
            assertTrue(ov.size() >= bs + count);
            prev = u;
            for (int i = 0; i < count; i++) {
                prev = (i & 1) == 0 ? ov.succ(prev) : ov.max(prev, ov.ofNat(i % 50));
                assertEquals(ids[i], prev);
            }
            for (int i = 0; i < made.size(); i++) {
                assertTrue(Lv.toStore(ov, made.get(i), n) < bs);
            }
            int[] buf = new int[5];
            int lastList = 0;
            for (int i = 0; i + 5 <= Math.min(count, 5000); i += 5) {
                System.arraycopy(ids, i, buf, 0, 5);
                lastList = ov.internList(buf, 0, 5);
            }
            assertTrue(lastList >= ov.baseListCount());
            ov.reset();
            assertEquals(bs, ov.size());
            for (int i = 0; i < made.size(); i++) {
                int id = Lv.toStore(ov, made.get(i), n);
                assertTrue(id < bs);
                assertEquals(base.canonId(id), ov.canonId(id));
            }
            assertEquals(bs, ov.size());
        }
    }

    @Test
    void randomWorkWithResetsMatchesReference() {
        Lv.Names n = names();
        LevelStore base = randomBase(n, 26, 400, null);
        base.freeze();
        LevelStore ov = base.newOverlay();
        work(ov, n, 27, 6000);
        assertEquals(base.size(), ov.baseSize());
    }

    /** Random operations on an overlay, each checked against the reference; resets in between. */
    private static void work(LevelStore ov, Lv.Names n, long seed, int ops) {
        Random rnd = new Random(seed);
        int[] buf = new int[3];
        int pl = 0;
        for (int op = 0; op < ops; op++) {
            if (op % 150 == 0) {
                ov.reset();
                buf[0] = n.id("u");
                buf[1] = n.id("v");
                buf[2] = n.id("w");
                pl = ov.internList(buf, 0, 3);
            }
            RefLevel x = Lv.random(rnd, 1 + rnd.nextInt(5), 3);
            RefLevel y = rnd.nextInt(3) == 0 ? OfficialPortTest.mutate(rnd, x) : Lv.random(rnd, 1 + rnd.nextInt(4), 3);
            int ix = Lv.toStore(ov, x, n);
            int iy = Lv.toStore(ov, y, n);
            assertEquals(x, Lv.fromStore(ov, ix, n));
            assertEquals(RefLevel.normalize(x), Lv.fromStore(ov, ov.normalize(ix), n), "normalize " + x);
            assertEquals(RefLevel.isEquivalent(x, y), ov.isEquivOfficial(ix, iy), x + " " + y);
            assertEquals(RefLevel.isGeq(x, y), ov.isGeqOfficial(ix, iy), x + " " + y);
            assertEquals(Lv.semEqReduced(x, y), ov.isEquiv(ix, iy), x + " " + y);
            assertEquals(Lv.semGeqReduced(x, y), ov.isGeq(ix, iy), x + " " + y);
            RefLevel[] sub = {Lv.random(rnd, rnd.nextInt(3), 3), Lv.random(rnd, rnd.nextInt(3), 3), RefLevel.param("w")};
            buf[0] = Lv.toStore(ov, sub[0], n);
            buf[1] = Lv.toStore(ov, sub[1], n);
            buf[2] = Lv.toStore(ov, sub[2], n);
            int subst = ov.mkSubst(pl, ov.internList(buf, 0, 3));
            RefLevel expected = RefLevel.instantiate(x, List.of("u", "v", "w"), List.of(sub));
            assertEquals(expected, Lv.fromStore(ov, ov.instantiate(ix, subst), n), "instantiate " + x);
            assertEquals(expected, Lv.fromStore(ov, ov.instantiate(ix, subst), n), "instantiate (cached) " + x);
        }
    }

    @Test
    void concurrentWorkersOverOneFrozenBase() throws InterruptedException {
        Lv.Names n = names();
        LevelStore base = randomBase(n, 28, 3000, null);
        base.freeze();
        int bs = base.size();
        // what the base looks like before any worker runs
        RefLevel[] shape = new RefLevel[bs];
        int[] canon = new int[bs];
        int[] rep = new int[bs];
        for (int id = 1; id < bs; id++) {
            shape[id] = Lv.fromStore(base, id, n);
            canon[id] = base.canonId(id);
            rep[id] = base.canonRep(id);
            assertTrue(canon[id] > 0 && rep[id] < bs);
        }
        int lists = base.listCount();
        int workers = 8;
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        LevelStore[] overlays = new LevelStore[workers];
        Thread[] threads = new Thread[workers + 2];
        for (int t = 0; t < workers; t++) {
            int tid = t;
            threads[t] = new Thread(() -> {
                try {
                    LevelStore ov = base.newOverlay();
                    overlays[tid] = ov;
                    work(ov, n, 100 + tid, 2500);
                    // memoised answers about base levels, computed by this worker
                    Random rnd = new Random(200 + tid);
                    for (int i = 0; i < 20000; i++) {
                        int x = 1 + rnd.nextInt(bs - 1);
                        int y = 1 + rnd.nextInt(bs - 1);
                        if (ov.isEquiv(x, y) != (canon[x] == canon[y])) {
                            throw new AssertionError("isEquiv on base levels " + x + " " + y);
                        }
                        if (ov.canonRep(x) != rep[x]) {
                            throw new AssertionError("canonRep of base level " + x);
                        }
                    }
                } catch (Throwable e) {
                    failures.add(e);
                }
            }, "level-worker-" + t);
        }
        // two more threads only read the frozen base itself while the workers run
        for (int t = workers; t < workers + 2; t++) {
            int tid = t;
            threads[t] = new Thread(() -> {
                try {
                    Random rnd = new Random(300 + tid);
                    for (int i = 0; i < 200000; i++) {
                        int x = 1 + rnd.nextInt(bs - 1);
                        int y = 1 + rnd.nextInt(bs - 1);
                        if (base.isEquiv(x, y) != (canon[x] == canon[y])) {
                            throw new AssertionError("base isEquiv " + x + " " + y);
                        }
                        if (base.isGeq(x, y) && base.isGeq(y, x) && canon[x] != canon[y]) {
                            throw new AssertionError("base isGeq antisymmetry " + x + " " + y);
                        }
                        if (base.kind(x) != shape[x].kind || base.depth(x) != shape[x].depth) {
                            throw new AssertionError("base accessor " + x);
                        }
                    }
                } catch (Throwable e) {
                    failures.add(e);
                }
            }, "base-reader-" + t);
        }
        for (Thread th : threads) {
            th.start();
        }
        for (Thread th : threads) {
            th.join();
        }
        if (!failures.isEmpty()) {
            Throwable first = failures.peek();
            throw new AssertionError(failures.size() + " worker failures, first: " + first, first);
        }
        // the base is untouched
        assertEquals(bs, base.size());
        assertEquals(lists, base.listCount());
        for (int id = 1; id < bs; id++) {
            assertEquals(shape[id], Lv.fromStore(base, id, n));
            assertEquals(canon[id], base.canonId(id));
            assertEquals(rep[id], base.canonRep(id));
        }
        // every overlay still sees the same base
        for (LevelStore ov : overlays) {
            assertEquals(bs, ov.baseSize());
            for (int id = 1; id < bs; id += 13) {
                assertEquals(shape[id], Lv.fromStore(ov, id, n));
                assertEquals(canon[id], ov.canonId(id));
            }
        }
        assertNotEquals(0, overlays.length);
    }
}
