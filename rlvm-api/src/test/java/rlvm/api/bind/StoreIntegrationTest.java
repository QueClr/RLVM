package rlvm.api.bind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import rlvm.api.KernelException;
import rlvm.api.LevelOps;
import rlvm.api.NatOps;
import rlvm.api.Reason;
import rlvm.api.RunSignals;
import rlvm.api.WorkerContext;
import rlvm.store.Kind;
import rlvm.store.Node;
import rlvm.store.Store;
import rlvm.store.TermOps;

/**
 * The bindings against the real {@code rlvm-store}: what {@link Bindings#newWorkerContext} wires
 * together (level overlay as the overlay's list store and level adapter, worker literal pool,
 * {@link StoreLitAccess}), and {@link WorkerContext#reset()} / {@code footprintBytes()}.
 */
class StoreIntegrationTest {

    private static int name(Store store, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        return store.names().internStr(0, b, 0, b.length);
    }

    private static BigInteger value(WorkerContext ctx, int lit) {
        return new BigInteger(ctx.nats.natToString(lit));
    }

    @Test
    void workerContextOverARealStore() {
        Bindings b = new Bindings();
        Store store = new Store(0, b.baseLists(), b.baseLevelInfo());
        b.bindNames(store.names());
        assertSame(b.baseLists(), store.lists(), "the store uses the run's list store");

        // loader thread: names, levels, lists of the "export"
        int u = name(store, "u");
        int v = name(store, "v");
        LevelBinding base = b.baseLevels();
        int lu = base.param(u);
        int lv = base.param(v);
        int lmax = base.max(base.succ(lu), lv);
        int uv = base.intern(new int[] {u, v}, 0, 2);
        b.freeze();

        WorkerContext ctx = b.newWorkerContext(3, store, null, 1 << 20);
        assertEquals(3, ctx.workerId);
        assertSame(store, ctx.store);
        assertSame(RunSignals.NONE, ctx.signals);
        assertSame(ctx.levels, ctx.lists, "a worker's level overlay is its list store");
        assertNotEquals(store.lits(), ctx.lits, "a worker has its own literal pool");
        assertTrue(ctx.lits.isWorkerPool());

        // ---- levels through the store's node writer: lpSet of a SORT node, level instantiation of a term
        LevelOps lv0 = ctx.levels;
        int sort = ctx.overlay.mkSort(lmax);
        assertEquals(Kind.SORT, ctx.terms.kind(sort));
        int lp = ctx.terms.lpSet(sort);
        assertEquals(2, ctx.lists.length(lp), "lpSet of Sort (max (u+1) v) is {u, v}");
        assertTrue(ctx.lists.subsetOf(lp, uv));
        int one = lv0.succ(lv0.zero());
        int three = lv0.succ(lv0.succ(one));
        int levels = ctx.lists.intern(new int[] {one, three}, 0, 2);
        TermOps ops = new TermOps(ctx.terms, ctx.lists);
        int inst = ops.instantiateLevelParams(sort, uv, levels);
        assertEquals(Kind.SORT, ctx.terms.kind(inst));
        assertTrue(lv0.isEquiv(ctx.terms.a(inst), three), "max (1+1) 3 = 3");
        assertEquals(0, ctx.terms.lpSet(inst));

        // ---- literals: word and pooled representations, arithmetic, identity
        NatOps nat = ctx.nats;
        int five = nat.literal(5);
        assertEquals(Kind.NATLIT, ctx.terms.kind(five));
        assertEquals(0, ctx.terms.header(five) & Node.F_NAT_BIG);
        assertEquals(5, nat.smallValue(five));
        int max64 = nat.literal(-1L);
        assertFalse(nat.isSmall(max64));
        int big = nat.succ(max64); // 2^64: the first pooled number
        assertNotEquals(0, ctx.terms.header(big) & Node.F_NAT_BIG);
        assertTrue(ctx.terms.a(big) < 0, "a computed big number is an entry of the worker's pool");
        assertEquals(BigInteger.ONE.shiftLeft(64), value(ctx, big));
        assertEquals(max64, nat.pred(big), "back below 2^64: a word literal again, hash-consed");
        int sq = nat.binary(NatOps.MUL, big, big);
        assertEquals(BigInteger.ONE.shiftLeft(128), value(ctx, sq));
        assertEquals(sq, nat.binary(NatOps.SHIFT_LEFT, nat.literal(1), nat.literal(128)), "equal numbers, one term");
        assertTrue(nat.natEquals(sq, nat.binary(NatOps.POW, nat.literal(2), nat.literal(128))));
        assertTrue(nat.test(NatOps.BLE, big, sq));
        assertFalse(nat.test(NatOps.BEQ, big, sq));
        assertEquals(Reason.NAT_LITERAL_TOO_BIG, assertThrows(KernelException.class,
                () -> nat.binary(NatOps.POW, nat.literal(2), nat.binary(NatOps.SHIFT_LEFT, nat.literal(1), nat.literal(32))))
                .reason());
        nat.checkLiteralSize(sq);

        // ---- footprint and reset
        long used = ctx.footprintBytes();
        assertTrue(used > 0);
        ctx.reset();
        assertTrue(ctx.footprintBytes() < used, "reset releases overlay terms, levels, lists and numbers");
        assertEquals(0, ctx.lits.natCount());
        // shared ids keep their meaning, and the context works again
        assertEquals(lu, ctx.levels.param(u));
        int big2 = nat.binary(NatOps.ADD, nat.literal(-1L), nat.literal(7)); // 2^64 + 6
        assertEquals(BigInteger.ONE.shiftLeft(64).add(BigInteger.valueOf(6)), value(ctx, big2));
        int sort2 = ctx.overlay.mkSort(lmax);
        assertEquals(2, ctx.lists.length(ctx.terms.lpSet(sort2)));

        // a second context is independent
        WorkerContext other = b.newWorkerContext(4, store, null, 1 << 20);
        int o = other.nats.succ(other.nats.literal(-1L));
        assertEquals(BigInteger.ONE.shiftLeft(64), value(other, o));
        ctx.reset();
        assertEquals(BigInteger.ONE.shiftLeft(64), value(other, o));
        assertEquals(Integer.MAX_VALUE, ctx.coveredUnits(), "a batch context covers every unit");
    }

    /**
     * Request of rlvm-ref (requests.log, 2026-11-24T08:18Z): a caller truncates the worker's literal
     * pool (LitPool.truncateTo, alone or through Overlay.resetTo) WITHOUT calling NatOps.reset(). The
     * store published at 07:16Z reissued the released references for other numbers; the number cache
     * of NatBinding is keyed by reference and must not answer with the number that had the reference
     * before: the binding notices such a truncation by the pool's entry count. The store published at
     * 10:33Z retires released references instead, which removes the hazard at its root. The test
     * passes with both and says which one it met.
     */
    @Test
    void aTruncatedLiteralPoolNeverServesAStaleNumber() {
        Bindings b = new Bindings();
        Store store = new Store(0, b.baseLists(), b.baseLevelInfo());
        b.bindNames(store.names());
        b.freeze();
        WorkerContext ctx = b.newWorkerContext(0, store, null, 1 << 20);
        NatOps nat = ctx.nats;
        NatBinding binding = (NatBinding) nat;
        BigInteger two64 = BigInteger.ONE.shiftLeft(64);

        int base = nat.succ(nat.literal(-1L)); // 2^64: the first entry of the worker's pool
        long termMark = ctx.overlay.mark();
        long litMark = ctx.lits.mark();
        int x = nat.binary(NatOps.ADD, base, nat.literal(5)); // 2^64 + 5, cached under its pool reference
        int refX = ctx.terms.a(x);
        assertTrue(refX < 0);
        assertEquals(two64.add(BigInteger.valueOf(6)), value(ctx, nat.succ(x)));
        long importsBefore = binding.imports();

        // The caller drops the terms and the pool entries made since the marks - and forgets NatOps.reset().
        ctx.overlay.resetTo(termMark);
        ctx.lits.truncateTo(litMark); // harmless if resetTo did it already (newer stores)

        // Another number is built. A store that reissues references gives it refX.
        int y = nat.binary(NatOps.MUL, base, nat.literal(3));
        assertEquals(two64.multiply(BigInteger.valueOf(3)), value(ctx, y));
        assertEquals(two64.multiply(BigInteger.valueOf(3)).add(BigInteger.ONE), value(ctx, nat.succ(y)),
                "never the number that had this reference before the truncation");
        assertTrue(nat.test(NatOps.BLE, base, y));
        assertFalse(nat.natEquals(y, base));
        if (ctx.terms.a(y) == refX) {
            // The store REISSUED the released reference (rlvm-store before 10:33Z): only an emptied cache
            // can have given the right numbers above, and emptying it means the operands were imported again.
            assertTrue(binding.imports() > importsBefore,
                    "the cache was emptied when the truncation was noticed: operands were imported again");
        } else {
            // The store RETIRES released references (rlvm-store since 10:33Z): the new number has a
            // reference of its own, no cache entry can name another number, nothing has to be emptied.
            assertTrue(ctx.terms.a(y) < refX, "a fresh reference below every released one: " + ctx.terms.a(y));
        }
        // And once more with several entries released at a time and more made than released.
        long t2 = ctx.overlay.mark();
        long l2 = ctx.lits.mark();
        int p = nat.binary(NatOps.POW, nat.literal(2), nat.literal(200));
        int q = nat.binary(NatOps.ADD, p, y);
        assertEquals(BigInteger.ONE.shiftLeft(200).add(two64.multiply(BigInteger.valueOf(3))), value(ctx, q));
        ctx.overlay.resetTo(t2);
        ctx.lits.truncateTo(l2);
        int r1 = nat.binary(NatOps.POW, nat.literal(3), nat.literal(100));
        int r2 = nat.binary(NatOps.MUL, r1, r1);
        int r3 = nat.binary(NatOps.SUB, r2, r1);
        BigInteger three100 = BigInteger.valueOf(3).pow(100);
        assertEquals(three100, value(ctx, r1));
        assertEquals(three100.multiply(three100), value(ctx, r2));
        assertEquals(three100.multiply(three100).subtract(three100), value(ctx, r3));
        assertEquals(two64.multiply(BigInteger.valueOf(3)), value(ctx, y), "entries made before the mark are untouched");
    }

    /**
     * Pipelined mode against the real store: a context is built on the snapshot current at its
     * creation, covers the store's frontier as read then, and moves to the newest snapshot at reset().
     * The chain is built the way rlvm.load.LevelChain builds it; the store gets a list store and a
     * level view that follow the open store (here: one binding re-pointed by the test's "loader").
     */
    @Test
    void workerContextFollowsTheSnapshotsOfAPipelinedLoad() {
        Bindings b = new Bindings();
        java.util.concurrent.atomic.AtomicReference<rlvm.level.LevelStore> current =
                new java.util.concurrent.atomic.AtomicReference<>();
        // The loader's view of the open store (LevelChain.lists() / info() in the real wiring).
        LevelBinding loaderView = b.baseLevels();
        Store store = new Store(0, loaderView, loaderView);
        b.bindNames(store.names());
        b.bindLevelSnapshots(current::get);

        int u = name(store, "u");
        rlvm.level.LevelStore open = b.levelBase();
        int lu = open.param(u);
        int su = open.succ(lu);
        assertTrue(assertThrows(IllegalStateException.class, () -> b.newWorkerContext(0, store, null, 1 << 20))
                .getMessage().contains("first publication"));

        // publication 1: levels first, then the store frontier (the loader's order)
        open.freeze();
        current.set(open);
        store.publishLoaded(0, false);
        rlvm.level.LevelStore w1 = open.newOverlay();

        WorkerContext ctx = b.newWorkerContext(1, store, null, 1 << 20);
        assertEquals(0, ctx.coveredUnits());
        int sort1 = ctx.overlay.mkSort(su);
        assertEquals(1, ctx.lists.length(ctx.terms.lpSet(sort1)));
        int mine = ctx.levels.succ(ctx.levels.succ(su));
        assertTrue(mine >= open.size());

        // The loader goes on: a level the first snapshot does not have, then publication 2.
        int v = name(store, "v");
        int lv = w1.param(v);
        int mx = w1.max(su, lv);
        w1.freeze();
        current.set(w1);

        // reset() with a newer snapshot: the level view is re-based, the context works on.
        ctx.reset();
        assertEquals(0, ctx.coveredUnits(), "the frontier is the store's, read at the reset");
        assertEquals(lv, ctx.levels.param(v), "the context sees the levels of the second snapshot");
        assertEquals(mx, ctx.levels.max(su, lv));
        int sort2 = ctx.overlay.mkSort(mx);
        assertEquals(2, ctx.lists.length(ctx.terms.lpSet(sort2)), "lpSet through the overlay's level view: {u, v}");
        assertTrue(ctx.levels.isGeq(mx, su));
        assertSame(ctx.levels, ctx.lists);
        // reset() without a newer snapshot: an ordinary reset, same snapshot, ids of it still valid.
        int own = ctx.levels.succ(mx);
        ctx.reset();
        assertEquals(mx, ctx.levels.max(su, lv));
        assertEquals(own, ctx.levels.succ(mx), "the overlay id is reissued after the reset");
        // numbers and literals work as in a batch run
        int big = ctx.nats.succ(ctx.nats.literal(-1L));
        assertEquals(BigInteger.ONE.shiftLeft(64), value(ctx, big));
    }
}
