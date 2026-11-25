package rlvm.api.bind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import rlvm.level.NameOracle;
import rlvm.nat.Nat;
import rlvm.nat.NatStore;
import rlvm.store.IntLists;

/**
 * The run-level factory, as far as it can be exercised without a term store: base stores, freeze
 * protocol, per-worker level and number views. {@code newWorkerContext} needs the real
 * {@code rlvm-store} and is covered by {@code StoreIntegrationTest} once that module is published.
 */
class BindingsTest {

    @Test
    void lifecycleOfABatchRun() throws Exception {
        Bindings b = new Bindings(100);
        IntLists lists = b.baseLists();
        assertSame(b.baseLevels(), lists, "one object is the level algebra and the list store of the base");
        assertSame(b.levelBase(), b.baseLevels().store());
        assertFalse(b.isFrozen());

        // loader thread: interns levels and lists before the name table is bound (no comparison needed)
        LevelBinding base = b.baseLevels();
        int u = base.param(7);
        int v = base.param(3);
        int lvl = base.max(base.succ(u), v);
        int names = lists.intern(new int[] {7, 3}, 0, 2);
        assertFalse(b.nameOrderUsedUnbound());

        // worker views do not exist before the freeze, and the freeze needs the name order
        assertThrows(IllegalStateException.class, b::newWorkerLevels);
        assertThrows(IllegalStateException.class, b::newWorkerNats);
        assertThrows(IllegalStateException.class, () -> b.newWorkerContext(0, null, null, 1 << 20));
        assertThrows(IllegalStateException.class, b::freeze, "freeze() before bindNames()");
        assertFalse(b.isFrozen());

        b.bindNameOrder(NameOracle.BY_ID);
        b.freeze();
        assertTrue(b.isFrozen());
        b.freeze(); // idempotent
        assertTrue(b.levelBase().isFrozen());
        assertTrue(b.natBase().isFrozen());
        assertEquals(0, b.natBase().count(), "the number base stays empty");
        assertFalse(b.nameOrderUsedUnbound(), "normal forms were computed after the order was bound");
        assertThrows(IllegalStateException.class, () -> lists.intern(new int[] {9, 9, 9}, 0, 3));

        // two workers: independent overlays over the same base ids
        LevelBinding w1 = b.newWorkerLevels();
        LevelBinding w2 = b.newWorkerLevels();
        assertNotSame(w1.store(), w2.store());
        assertEquals(u, w1.param(7));
        assertEquals(names, w2.intern(new int[] {7, 3}, 0, 2));
        int one = w1.succ(w1.zero());
        int s1 = w1.mkSubst(names, w1.intern(new int[] {one, one}, 0, 2));
        assertTrue(w1.isEquiv(w1.instantiate(lvl, s1), w1.succ(one)));
        int big = w2.succ(w2.succ(w2.succ(w2.succ(u))));
        assertTrue(w2.isGeq(big, u));
        w1.reset();
        assertTrue(w2.isGeq(big, u), "resetting one worker leaves the other alone");

        // shared ids (what the store's generated region may keep): the frozen base's, not a worker's own
        int mine = w2.succ(big);
        int myList = w2.intern(new int[] {7, 7, 3}, 0, 3);
        assertTrue(w2.isSharedLevel(u) && w2.isSharedLevel(lvl) && w1.isSharedLevel(lvl));
        assertFalse(w2.isSharedLevel(big) || w2.isSharedLevel(mine) || w2.isSharedLevel(0) || w2.isSharedLevel(-1));
        assertTrue(w2.isSharedList(names) && w2.isSharedList(0));
        assertFalse(w2.isSharedList(myList) || w2.isSharedList(-3));
        assertTrue(b.baseLevels().isSharedLevel(lvl) && b.baseLevels().isSharedList(names));

        NatStore n1 = b.newWorkerNats();
        NatStore n2 = b.newWorkerNats();
        assertNotSame(n1, n2);
        long h = Nat.add(n1, Long.MAX_VALUE, 5);
        assertTrue(Nat.isBig(h));
        assertEquals(1, n1.count());
        assertEquals(0, n2.count());

        // worker views may be created on other threads after the freeze
        AtomicReference<Object> seen = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                LevelBinding w = b.newWorkerLevels();
                seen.set(w.isEquiv(w.max(w.param(7), w.param(7)), w.param(7)));
            } catch (Throwable e) {
                seen.set(e);
            }
        });
        t.start();
        t.join();
        assertEquals(Boolean.TRUE, seen.get());

        assertTrue(assertThrows(UnsupportedOperationException.class, b::snapshot).getMessage().contains("pipelined"));
        assertThrows(IllegalStateException.class, () -> b.bindNameOrder(NameOracle.BY_ID), "bound once");
        assertThrows(IllegalStateException.class, () -> b.bindLevelSnapshots(() -> null), "a frozen run is a batch run");
        assertFalse(b.isPipelined());
    }

    /**
     * Pipelined mode without a term store: worker level views over a chain of frozen snapshots built
     * exactly as rlvm.load.LevelChain builds it (freeze the open store, go on in an overlay of it).
     */
    @Test
    void workerLevelViewsOverAChainOfSnapshots() {
        Bindings b = new Bindings(100);
        b.bindNameOrder(NameOracle.BY_ID);
        AtomicReference<rlvm.level.LevelStore> current = new AtomicReference<>();
        b.bindLevelSnapshots(current::get);
        assertTrue(b.isPipelined());
        assertTrue(b.natBase().isFrozen(), "the empty number base is frozen at once");
        assertThrows(IllegalStateException.class, () -> b.bindLevelSnapshots(current::get), "bound once");
        assertThrows(IllegalStateException.class, b::freeze, "the chain freezes, not the factory");
        // before the first publication there is nothing to build a view on
        assertTrue(assertThrows(IllegalStateException.class, b::newWorkerLevels).getMessage().contains("first publication"));
        assertEquals(0, b.newWorkerNats().count(), "number stores do not wait for the load");

        // loader: unit batch 1
        rlvm.level.LevelStore open = b.levelBase();
        int u = open.param(7);
        int su = open.succ(u);
        int l1 = open.internList(new int[] {u, su}, 0, 2);
        open.freeze();
        current.set(open); // snapshot 1
        rlvm.level.LevelStore w1 = open.newOverlay();

        LevelBinding worker = b.newWorkerLevels();
        assertEquals(u, worker.param(7));
        assertEquals(l1, worker.intern(new int[] {u, su}, 0, 2));
        int own = worker.succ(worker.succ(su));
        assertTrue(own >= open.size(), "a worker's own level lies above the snapshot");

        // loader: unit batch 2, in the overlay of snapshot 1
        int v = w1.param(3);
        int mx = w1.max(su, v);
        int l2 = w1.internList(new int[] {mx}, 0, 1);
        w1.freeze();
        current.set(w1); // snapshot 2

        // the old view does not contain the new ids: a rebase onto a fresh overlay of snapshot 2 does
        worker.rebase(w1.newOverlay());
        assertEquals(u, worker.param(7), "ids of snapshot 1 keep their meaning");
        assertEquals(v, worker.param(3));
        assertEquals(mx, worker.max(su, v));
        assertEquals(l2, worker.intern(new int[] {mx}, 0, 1));
        assertEquals(1, worker.length(l2));
        assertTrue(worker.isGeq(mx, su));
        int again = worker.succ(worker.succ(su));
        assertTrue(again >= w1.size(), "what the worker made before the rebase is gone; ids are reissued above snapshot 2");
        // a new view starts on the newest snapshot
        LevelBinding late = b.newWorkerLevels();
        assertEquals(mx, late.max(su, v));
        // In a pipelined run no id is called shared: a worker on an older snapshot does not have it.
        assertFalse(late.isSharedLevel(u) || late.isSharedLevel(mx) || worker.isSharedLevel(u));
        assertFalse(late.isSharedList(l1) || b.baseLevels().isSharedLevel(u));
        assertTrue(late.isSharedList(0), "the empty list is the same everywhere");
        // rebasing onto an older snapshot, a frozen store or a base is refused
        assertThrows(IllegalArgumentException.class, () -> late.rebase(open.newOverlay()));
        assertThrows(IllegalArgumentException.class, () -> late.rebase(w1));
        assertThrows(IllegalArgumentException.class, () -> late.rebase(null));
    }

    /**
     * A worker's view shares the snapshot it sits on and moves to a newer one in place
     * ({@code rebaseOnto}): the same store object, nothing of the snapshot copied, and the same ids as a
     * view made anew on that snapshot.
     */
    @Test
    void aWorkerViewMovesToANewerSnapshotInPlace() {
        Bindings b = new Bindings(100);
        b.bindNameOrder(NameOracle.BY_ID);
        AtomicReference<rlvm.level.LevelStore> current = new AtomicReference<>();
        b.bindLevelSnapshots(current::get);
        rlvm.level.LevelStore open = b.levelBase();
        int u = open.param(7);
        int su = open.succ(u);
        int names = open.internList(new int[] {7}, 0, 1);
        int l1 = open.internList(new int[] {su}, 0, 1);
        open.freeze();
        current.set(open); // snapshot 1

        LevelBinding worker = b.newWorkerLevels();
        rlvm.level.LevelStore store = worker.store();
        assertTrue(store.isSharedOverlay(), "a worker's level store shares its base");
        int ownLevel = worker.succ(worker.succ(su));
        int ownList = worker.intern(new int[] {ownLevel, u}, 0, 2);
        int ownSet = worker.unionSorted(names, worker.intern(new int[] {9}, 0, 1));
        int subst = worker.mkSubst(names, worker.intern(new int[] {ownLevel}, 0, 1));
        assertEquals(worker.succ(ownLevel), worker.instantiate(su, subst));
        assertTrue(ownLevel >= open.size() && ownList >= open.listCount() && ownSet >= open.listCount());
        assertTrue(worker.footprintBytes() > 0);

        // loader: the next piece, in a private copy of snapshot 1 (rlvm.load.LevelChain)
        rlvm.level.LevelStore w1 = open.newOverlay();
        int v = w1.param(3);
        int mx = w1.max(su, v);
        int l2 = w1.internList(new int[] {mx, v}, 0, 2);
        w1.freeze();
        current.set(w1); // snapshot 2

        worker.rebaseOnto(w1);
        assertSame(store, worker.store(), "moved in place");
        assertEquals(0L, worker.footprintBytes(), "what the worker made is forgotten");
        assertEquals(w1.size(), store.baseSize());
        assertEquals(w1.listCount(), store.baseListCount());
        LevelBinding fresh = b.newWorkerLevels();
        for (LevelBinding w : new LevelBinding[] {worker, fresh}) {
            assertEquals(u, w.param(7));
            assertEquals(mx, w.max(su, v));
            assertEquals(l1, w.intern(new int[] {su}, 0, 1));
            assertEquals(l2, w.intern(new int[] {mx, v}, 0, 2));
            assertEquals(2, w.length(l2));
            assertEquals(v, w.get(l2, 1));
            assertTrue(w.subsetOf(names, names) && w.isGeq(mx, su));
        }
        // the same operations give the same ids on the moved view and on a new one
        int a1 = worker.succ(worker.succ(mx));
        int a2 = fresh.succ(fresh.succ(mx));
        assertEquals(a2, a1);
        assertEquals(w1.size() + 1, a1, "ids are reissued above snapshot 2");
        int s1 = worker.unionSorted(names, worker.intern(new int[] {9}, 0, 1));
        int s2 = fresh.unionSorted(names, fresh.intern(new int[] {9}, 0, 1));
        assertEquals(s2, s1);
        assertEquals(2, worker.length(s1));
        assertEquals(7, worker.get(s1, 0));
        assertEquals(9, worker.get(s1, 1));
        int p1 = worker.mkSubst(names, worker.intern(new int[] {a1}, 0, 1));
        int p2 = fresh.mkSubst(names, fresh.intern(new int[] {a2}, 0, 1));
        assertEquals(p2, p1);
        assertEquals(fresh.instantiate(su, p2), worker.instantiate(su, p1));
        assertEquals(fresh.instantiateList(l1, p2), worker.instantiateList(l1, p1));
        // a view on a private copy (tools, tests) is replaced instead; an older snapshot and an open store are refused
        LevelBinding flat = new LevelBinding(open.newOverlay());
        flat.rebaseOnto(w1);
        assertTrue(flat.store().isSharedOverlay());
        assertEquals(mx, flat.max(su, v));
        assertThrows(IllegalArgumentException.class, () -> worker.rebaseOnto(open));
        assertThrows(IllegalArgumentException.class, () -> worker.rebaseOnto(open.newOverlay()));
        assertThrows(IllegalArgumentException.class, () -> worker.rebaseOnto(null));
    }
}
