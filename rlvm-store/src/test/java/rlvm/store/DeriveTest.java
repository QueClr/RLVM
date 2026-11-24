package rlvm.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import rlvm.store.Model.M;

/**
 * ARCHITECTURE.md 9.1 / WP-A1: node derivation (looseRange, fvMask, depMax, lpSet, flags) against a
 * naive recursive recomputation on random DAGs, for the three writers: the export region, the
 * overlay constructors and the proof-block decoder.
 */
class DeriveTest {

    private static Model.Gen gen(Random rnd, int size) {
        Model.Gen g = new Model.Gen();
        g.size = size;
        g.bigVars = rnd.nextInt(3) == 0;
        return g;
    }

    @Test
    void sharedRegionMatchesNaive() {
        for (int seed = 0; seed < 300; seed++) {
            Random rnd = new Random(seed);
            Model fx = new Model();
            if (seed % 3 != 0) {
                fx.declareLiteralPrerequisites(seed % 3 == 2);
            }
            Checks.clear();
            List<M> nodes = fx.random(rnd, gen(rnd, 10 + rnd.nextInt(60)));
            IdentityHashMap<M, Integer> memo = new IdentityHashMap<>();
            for (M m : nodes) {
                fx.toShared(m, memo);
            }
            Terms t = new Terms(fx.store.terms(), null);
            for (int i = 0; i < nodes.size(); i++) {
                Checks.checkNode(fx, t, memo.get(nodes.get(i)), nodes.get(i), "seed " + seed + " node " + i);
            }
        }
    }

    @Test
    void overlayMatchesNaive() {
        for (int seed = 0; seed < 300; seed++) {
            Random rnd = new Random(1000 + seed);
            Model fx = new Model();
            if (seed % 2 == 0) {
                fx.declareLiteralPrerequisites(true);
            }
            fx.builder.finish();
            Checks.clear();
            Overlay o = new Overlay(fx.store, fx.store.lists(), 1 << 22);
            Terms t = new Terms(fx.store.terms(), o);
            List<M> nodes = fx.random(rnd, gen(rnd, 10 + rnd.nextInt(60)));
            IdentityHashMap<M, Integer> memo = new IdentityHashMap<>();
            for (int i = 0; i < nodes.size(); i++) {
                int id = fx.toOverlay(o, nodes.get(i), memo);
                assertTrue(Node.isOverlay(id));
                Checks.checkNode(fx, t, id, nodes.get(i), "seed " + seed + " node " + i);
                assertTrue((t.header(id) & Node.F_SHARED) != 0, "hash-consed nodes are flagged SHARED");
            }
        }
    }

    @Test
    void decodedBlocksMatchNaive() {
        for (int seed = 0; seed < 300; seed++) {
            Random rnd = new Random(2000 + seed);
            Model fx = new Model();
            if (seed % 3 != 0) {
                fx.declareLiteralPrerequisites(seed % 3 == 2);
            }
            Checks.clear();
            Segs segs = new Segs(fx);
            // a few shared leaves first (a definition), then a theorem whose value is the random DAG
            List<M> first = fx.random(rnd, gen(rnd, 8));
            M type = first.get(first.size() - 1);
            segs.write(DeclKind.DEFN, fx.name("d" + seed), 0, first, type, type);
            List<M> nodes = new ArrayList<>(fx.random(rnd, gen(rnd, 10 + rnd.nextInt(60))));
            // some records reference the earlier (shared) segment
            for (int i = 0; i < 5; i++) {
                nodes.add(Model.app(nodes.get(rnd.nextInt(nodes.size())), first.get(rnd.nextInt(first.size()))));
            }
            M value = nodes.get(nodes.size() - 1);
            Segs.Seg s = segs.write(DeclKind.THM, fx.name("t" + seed), 0, nodes, type, value);
            fx.builder.finish();
            assertTrue(s.block >= 0);
            assertEquals(-(s.block + 1), fx.store.decls().value(s.cid));
            assertTrue((fx.store.decls().flags(s.cid) & DeclTable.CF_BLOCK_VALUE) != 0);
            Overlay o = new Overlay(fx.store, fx.store.lists(), 1 << 22);
            Terms t = new Terms(fx.store.terms(), o);
            int root = o.blockRoot(s.block);
            assertEquals(root, o.blockRoot(s.block), "a block is decoded once per session");
            assertEquals(nodes.size(), fx.store.blocks().nodeCount(s.block));
            assertEquals(nodes.size(), o.nodeCount());
            int base = (root & Node.INDEX_MASK) - s.at[nodes.size() - 1];
            for (int i = 0; i < nodes.size(); i++) {
                assertFalse(s.shared[i]);
                Checks.checkNode(fx, t, (base + s.at[i]) | Node.OVERLAY_BIT, nodes.get(i), "seed " + seed + " node " + i);
            }
            assertEquals(fx.digest(value), fx.digest(t, root));
        }
    }

    @Test
    void saturationIsStickyAndExactRangeIsRecovered() {
        Model fx = new Model();
        fx.builder.finish();
        Overlay o = new Overlay(fx.store, fx.store.lists(), 1L << 26);
        Terms t = new Terms(fx.store.terms(), o);
        int dom = o.mkSort(fx.levelIds[1]);
        // #70000 under 70,001 binders is closed; the header cannot know, the exact walk can
        int body = o.mkBVar(70000);
        assertEquals(Node.LOOSE_SAT, t.looseRangeField(body));
        assertEquals(70001, t.looseRange(body));
        int cur = body;
        for (int i = 0; i < 70001; i++) {
            cur = o.mkLam(dom, cur);
            assertEquals(Node.LOOSE_SAT, t.looseRangeField(cur));
            assertTrue((t.header(cur) & Node.F_LOOSE_SAT) != 0);
            if (i % 9973 == 0 || i > 69990) {
                assertEquals(70000 - i, t.looseRange(cur), "after " + (i + 1) + " binders");
            }
        }
        assertTrue(t.isClosed(cur));
        assertTrue(t.fvMask(cur) < 0, "a saturated node keeps bit 63 (superset)");
        // the largest index that still has an exact header
        int v = o.mkBVar(Node.LOOSE_SAT - 2);
        assertEquals(Node.LOOSE_SAT - 1, t.looseRangeField(v));
        assertEquals(0, t.header(v) & Node.F_LOOSE_SAT);
        int lam = o.mkLam(dom, v);
        assertEquals(Node.LOOSE_SAT - 2, t.looseRange(lam));
        assertEquals(0, t.header(lam) & Node.F_LOOSE_SAT);
    }

    @Test
    void maskUnderBinders() {
        Model fx = new Model();
        fx.builder.finish();
        Overlay o = new Overlay(fx.store, fx.store.lists(), 1L << 24);
        Terms t = new Terms(fx.store.terms(), o);
        int dom = o.mkSort(fx.levelIds[1]);
        // exact below 63
        int b = o.mkApp(o.mkBVar(0), o.mkApp(o.mkBVar(3), o.mkBVar(62)));
        assertEquals((1L) | (1L << 3) | (1L << 62), t.fvMask(b));
        int l = o.mkLam(dom, b);
        assertEquals((1L << 2) | (1L << 61), t.fvMask(l));
        // index 63 and 64: bit 63; under a binder index 63 becomes 62 and 64 becomes 63
        int c = o.mkApp(o.mkBVar(63), o.mkBVar(64));
        assertEquals(1L << 63, t.fvMask(c));
        int lc = o.mkLam(dom, c);
        assertEquals((1L << 62) | (1L << 63), t.fvMask(lc));
        int d = o.mkLam(dom, o.mkBVar(63));
        assertEquals(1L << 62, t.fvMask(d));
        assertEquals(63, t.looseRange(d));
        // a let binds in its body only
        int let = o.mkLet(o.mkBVar(1), o.mkBVar(2), o.mkBVar(0));
        assertEquals((1L << 1) | (1L << 2), t.fvMask(let));
        assertEquals(3, t.looseRange(let));
        int let2 = o.mkLet(dom, dom, o.mkBVar(5));
        assertEquals(1L << 4, t.fvMask(let2));
        assertEquals(5, t.looseRange(let2));
    }

    @Test
    void levelParameterSetsOfDagShapedLevels() {
        Model fx = new Model();
        TestLevels lv = fx.levels;
        int u = lv.param(fx.paramNames[0]);
        int v = lv.param(fx.paramNames[1]);
        // 60 levels of max(l, l): a tree walk would take 2^60 steps
        int l = lv.max(u, lv.succ(v));
        for (int i = 0; i < 60; i++) {
            l = lv.max(l, lv.succ(l));
        }
        int s = fx.builder.sort(l);
        Terms t = new Terms(fx.store.terms(), null);
        assertEquals(fx.setOf(fx.store.lists().intern(new int[] {fx.paramNames[0], fx.paramNames[1]}, 0, 2)),
                fx.setOf(t.lpSet(s)));
        // a long successor chain
        int c = u;
        for (int i = 0; i < 200000; i++) {
            c = lv.succ(c);
        }
        // TestLevels.hasParam recurses; the store's walk does not. Use a non-recursive view of the chain.
        final int top = c;
        LevelInfo flat = new LevelInfo() {
            @Override
            public boolean hasParam(int level) {
                return true;
            }

            @Override
            public int paramName(int level) {
                return lv.paramName(level);
            }

            @Override
            public int lhs(int level) {
                return lv.lhs(level);
            }

            @Override
            public int rhs(int level) {
                return lv.rhs(level);
            }

            @Override
            public int instantiate(int level, int paramNames, int levels) {
                return level;
            }

            @Override
            public int instantiateList(int levelList, int paramNames, int levels) {
                return levelList;
            }
        };
        fx.store.bindLevels(flat);
        int s2 = fx.builder.sort(top);
        t.refresh();
        assertEquals(1, fx.store.lists().length(t.lpSet(s2)));
        assertEquals(fx.paramNames[0], fx.store.lists().get(t.lpSet(s2), 0));
        // without parameters: no set, no flag
        int s3 = fx.builder.sort(lv.succ(TestLevels.ZERO));
        t.refresh();
        assertEquals(0, t.lpSet(s3));
        assertEquals(0, t.header(s3) & Node.F_HAS_LPARAM);
    }

    @Test
    void aStoreWithoutLevelInfoFailsClosed() {
        Store store = new Store(0);
        StoreBuilder b = store.builder();
        int bv = b.bvar(0);
        assertEquals(1, bv);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> b.sort(2));
    }
}
