package rlvm.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import rlvm.store.Model.M;

/**
 * The concurrency contract of the store (*agentic-note*: design-architecture-document, 2.8): a frozen base read by several workers,
 * each with its own overlay; and readers that follow the loaded frontier while the loader still
 * writes and its arrays grow.
 */
class ConcurrencyTest {

    private static Model.Gen gen(int size, boolean closedLevels) {
        Model.Gen g = new Model.Gen();
        g.size = size;
        g.closedLevels = closedLevels;
        return g;
    }

    @Test
    void frozenBaseWithSeveralOverlays() throws Exception {
        Model fx = new Model();
        fx.declareLiteralPrerequisites(true);
        Segs segs = new Segs(fx);
        Random rnd = new Random(77);
        int decls = 120;
        int[] cids = new int[decls];
        long[] typeDigest = new long[decls];
        long[] valueDigest = new long[decls];
        List<M> earlier = new ArrayList<>();
        for (int i = 0; i < decls; i++) {
            List<M> ty = fx.random(rnd, gen(4 + rnd.nextInt(12), false));
            List<M> val = new ArrayList<>(fx.random(rnd, gen(20 + rnd.nextInt(300), false)));
            for (int k = 0; k < 4 && !earlier.isEmpty(); k++) {
                val.add(Model.app(val.get(val.size() - 1), earlier.get(rnd.nextInt(earlier.size()))));
            }
            List<M> nodes = new ArrayList<>(ty);
            nodes.addAll(val);
            M type = ty.get(ty.size() - 1);
            M value = val.get(val.size() - 1);
            Segs.Seg s = segs.write(i % 5 == 0 ? DeclKind.DEFN : DeclKind.THM, fx.name("d" + i), 0, nodes, type, value);
            cids[i] = s.cid;
            typeDigest[i] = fx.digest(type);
            valueDigest[i] = fx.digest(value);
            earlier.addAll(nodes);
        }
        fx.builder.finish();
        ArrayIntLists base = (ArrayIntLists) fx.store.lists();
        base.freeze();
        Store store = fx.store;
        DeclTable d = store.decls();
        // what one worker computes on its own: the reference for all of them
        long[] instDigest = new long[decls];
        {
            Overlay o = new Overlay(store, base.newOverlay(), fx.levels, new LitPool(store.lits()), 1L << 26);
            Terms t = new Terms(store.terms(), o);
            TermOps ops = new TermOps(t, null);
            for (int i = 0; i < decls; i++) {
                int v = d.value(cids[i]);
                int root = v > 0 ? v : o.blockRoot(-v - 1);
                instDigest[i] = fx.digest(t, ops.instantiate1(ops.liftLoose(root, 0, 1), d.type(cids[i])), new HashMap<>());
            }
        }
        int workers = 8;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] threads = new Thread[workers];
        for (int wi = 0; wi < workers; wi++) {
            final int id = wi;
            threads[wi] = new Thread(() -> {
                try {
                    Random r = new Random(1000 + id);
                    LitPool pool = new LitPool(store.lits());
                    Overlay o = new Overlay(store, base.newOverlay(), fx.levels, pool, 1L << 26);
                    Terms t = new Terms(store.terms(), o);
                    TermOps ops = new TermOps(t, null);
                    ops.setLitPool(pool);
                    for (int round = 0; round < 400; round++) {
                        int i = r.nextInt(decls);
                        int cid = cids[i];
                        int v = d.value(cid);
                        int root = v > 0 ? v : o.blockRoot(-v - 1);
                        HashMap<Integer, Long> memo = new HashMap<>();
                        assertEquals(typeDigest[i], fx.digest(t, d.type(cid), memo, pool), "type of " + i);
                        assertEquals(valueDigest[i], fx.digest(t, root, memo, pool), "value of " + i);
                        int inst = ops.instantiate1(ops.liftLoose(root, 0, 1), d.type(cid));
                        assertEquals(instDigest[i], fx.digest(t, inst, memo, pool), "instantiate on " + i);
                        assertTrue(ops.eqv(root, ops.lowerLoose(ops.liftLoose(root, 0, 2), 2, 2)));
                        // racy shared facts: every worker marks its own residue class
                        int shared = 1 + r.nextInt(store.terms().exportEnd() - 1);
                        if (shared % workers == id) {
                            store.facts().setChecked(shared);
                        }
                        if (round % 50 == 49) {
                            o.reset();
                            pool.reset();
                        }
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            }, "worker-" + wi);
            threads[wi].start();
        }
        for (Thread th : threads) {
            th.join();
        }
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        // the base is unchanged
        Terms t = new Terms(store.terms(), null);
        for (int i = 0; i < decls; i++) {
            assertEquals(typeDigest[i], fx.digest(t, d.type(cids[i])));
        }
    }

    @Test
    void readersFollowTheFrontierWhileTheLoaderWrites() throws Exception {
        Model fx = new Model(0);
        fx.declareLiteralPrerequisites(true);
        Store store = fx.store;
        DeclTable d = store.decls();
        int firstUnit = d.unitCount();
        int units = 3000;
        AtomicLongArray typeDigest = new AtomicLongArray(units);
        AtomicLongArray valueDigest = new AtomicLongArray(units);
        AtomicBoolean done = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        fx.builder.publish();

        Thread loader = new Thread(() -> {
            try {
                Segs segs = new Segs(fx);
                Random rnd = new Random(5);
                List<M> earlier = new ArrayList<>();
                for (int i = 0; i < units && failure.get() == null; i++) {
                    List<M> ty = fx.random(rnd, gen(4 + rnd.nextInt(8), true));
                    List<M> val = new ArrayList<>(fx.random(rnd, gen(10 + rnd.nextInt(i % 100 == 0 ? 20000 : 150), true)));
                    for (int k = 0; k < 3 && !earlier.isEmpty(); k++) {
                        val.add(Model.app(val.get(val.size() - 1), earlier.get(rnd.nextInt(earlier.size()))));
                    }
                    List<M> nodes = new ArrayList<>(ty);
                    nodes.addAll(val);
                    M type = ty.get(ty.size() - 1);
                    M value = val.get(val.size() - 1);
                    int name = fx.store.names().internNum(fx.name("unit"), i);
                    segs.write(i % 4 == 0 ? DeclKind.DEFN : DeclKind.THM, name, 0, nodes, type, value);
                    typeDigest.set(i, fx.digest(type));
                    valueDigest.set(i, fx.digest(value));
                    fx.clearMemos();
                    if (earlier.size() < 2000) {
                        earlier.addAll(nodes.subList(0, Math.min(nodes.size(), 40)));
                    }
                    if (i % 7 == 0) {
                        fx.builder.publish();
                    }
                }
                fx.builder.finish();
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            } finally {
                done.set(true);
            }
        }, "loader");

        int readers = 6;
        long[] checked = new long[readers];
        Thread[] threads = new Thread[readers];
        for (int ri = 0; ri < readers; ri++) {
            final int id = ri;
            threads[ri] = new Thread(() -> {
                try {
                    Random r = new Random(id);
                    // closed levels only: this reader's list store is never asked about a shared list id
                    Overlay o = new Overlay(store, new ArrayIntLists(), fx.levels, new LitPool(store.lits()), 1L << 27);
                    Terms t = new Terms(store.terms(), o);
                    int lastSeen = 0;
                    while (failure.get() == null) {
                        boolean finished = done.get();
                        int loaded = store.loadedUnits();
                        assertTrue(loaded >= lastSeen, "the frontier went back");
                        lastSeen = loaded;
                        int n = loaded - firstUnit;
                        if (n > 0) {
                            t.refresh();
                            for (int k = 0; k < 20; k++) {
                                int i = k < 4 ? n - 1 - r.nextInt(Math.min(n, 8)) : r.nextInt(n);
                                int unit = firstUnit + i;
                                assertEquals(1, d.unitSize(unit));
                                int cid = d.unitFirstCid(unit);
                                assertEquals(unit, d.unit(cid));
                                assertEquals(unit + 1, d.rank(cid));
                                assertEquals("unit." + i, store.names().toString(d.name(cid)));
                                assertEquals(cid, store.names().constOf(d.name(cid)));
                                HashMap<Integer, Long> memo = new HashMap<>();
                                assertEquals(typeDigest.get(i), fx.digest(t, d.type(cid), memo), "type of unit " + i);
                                int v = d.value(cid);
                                int root = v > 0 ? v : o.blockRoot(-v - 1);
                                assertEquals(valueDigest.get(i), fx.digest(t, root, memo), "value of unit " + i);
                                assertTrue(d.unitFirstTerm(unit) <= d.type(cid) || d.type(cid) < d.unitFirstTerm(unit));
                                checked[id]++;
                            }
                            o.reset();
                        }
                        if (finished) {
                            assertTrue(store.loadComplete());
                            assertEquals(firstUnit + units, store.loadedUnits());
                            break;
                        }
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            }, "reader-" + ri);
        }
        for (Thread th : threads) {
            th.start();
        }
        loader.start();
        loader.join();
        for (Thread th : threads) {
            th.join();
        }
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        assertNull(failure.get());
        for (int ri = 0; ri < readers; ri++) {
            assertTrue(checked[ri] > 100, "reader " + ri + " checked only " + checked[ri] + " units");
        }
        assertTrue(store.terms().exportArray().length > 8 * 1024 * 8, "the export region grew during the run");
    }
}
