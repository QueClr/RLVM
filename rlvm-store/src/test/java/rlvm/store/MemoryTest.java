package rlvm.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import rlvm.store.Model.M;

/** {@link Store#memory()} and {@link StoreBuilder#releaseLoaderState()}. */
class MemoryTest {

    private static Model.Gen gen(int size) {
        Model.Gen g = new Model.Gen();
        g.size = size;
        g.unresolved = false;
        return g;
    }

    @Test
    void theReportAddsUpAndFollowsTheData() {
        Model fx = new Model();
        fx.declareLiteralPrerequisites(true);
        Store store = fx.store;
        StoreMemory empty = store.memory();
        assertEquals(StoreMemory.REGIONS, empty.allocated.length);
        assertEquals(store.sharedBytes(), empty.totalAllocated(), "the regions are a partition of sharedBytes()");
        Segs segs = new Segs(fx);
        Random rnd = new Random(8);
        List<Integer> blocks = new ArrayList<>();
        int promotedRecord = -1;
        for (int i = 0; i < 40; i++) {
            List<M> ty = fx.random(rnd, gen(8));
            List<M> val = fx.random(rnd, gen(300));
            List<M> nodes = new ArrayList<>(ty);
            nodes.addAll(val);
            Segs.Seg s = segs.write(DeclKind.THM, fx.name("thm" + i), 0, nodes, ty.get(ty.size() - 1), val.get(val.size() - 1));
            blocks.add(s.block);
            if (i == 20) {
                promotedRecord = store.blocks().firstRecord(s.block) + 5;
                fx.builder.promote(promotedRecord);
            }
        }
        fx.builder.publish();
        StoreMemory m = store.memory();
        assertEquals(store.sharedBytes(), m.totalAllocated());
        for (int r = 0; r < StoreMemory.REGIONS; r++) {
            assertTrue(m.used[r] <= m.allocated[r], StoreMemory.name(r) + ": used " + m.used[r] + " of " + m.allocated[r]);
            assertTrue(m.used[r] >= 0 && m.count[r] >= 0);
        }
        assertEquals(store.terms().exportEnd() - 1, m.count[StoreMemory.EXPORT]);
        assertEquals(32L * store.terms().exportEnd(), m.used[StoreMemory.EXPORT]);
        assertEquals(store.blocks().recordCount(), m.count[StoreMemory.BLOCKS]);
        assertTrue(m.used[StoreMemory.BLOCKS] > empty.used[StoreMemory.BLOCKS] + 40 * 300, "the block bytes are counted");
        assertEquals(store.names().size() - 1, m.count[StoreMemory.NAMES]);
        assertEquals(store.decls().constantCount(), m.count[StoreMemory.DECLS]);
        assertTrue(m.count[StoreMemory.LOADER] >= 1, "promoted records");
        assertTrue(m.allocated[StoreMemory.LOADER] >= 8L * 64);
        assertEquals(0, m.count[StoreMemory.GENERATED]);
        assertTrue(m.totalUsed() <= m.totalAllocated());
        String text = m.toString();
        for (int r = 0; r < StoreMemory.REGIONS; r++) {
            assertTrue(text.contains(StoreMemory.name(r)));
        }
        assertTrue(text.contains("total"));

        // the end of the load releases the loader's state: decoding is unaffected, promotion is over
        Overlay o = new Overlay(store, store.lists(), 1L << 24);
        Terms t = new Terms(store.terms(), o);
        long[] digests = new long[blocks.size()];
        for (int i = 0; i < blocks.size(); i++) {
            digests[i] = fx.digest(t, o.blockRoot(blocks.get(i)));
        }
        int promoted = store.blocks().promotedCount();
        assertFalse(store.blocks().loaderStateReleased());
        fx.builder.finish();
        assertTrue(store.blocks().loaderStateReleased(), "finish() gives the loader state back");
        fx.builder.releaseLoaderState();
        fx.builder.releaseLoaderState();
        assertTrue(store.loadComplete());
        StoreMemory after = store.memory();
        assertTrue(after.allocated[StoreMemory.LOADER] < m.allocated[StoreMemory.LOADER], "the promotion memo is gone");
        assertEquals(m.allocated[StoreMemory.BLOCKS], after.allocated[StoreMemory.BLOCKS]);
        assertEquals(m.allocated[StoreMemory.EXPORT], after.allocated[StoreMemory.EXPORT]);
        assertEquals(store.sharedBytes(), after.totalAllocated());
        assertEquals(promoted, store.blocks().promotedCount(), "the count stays");
        o.reset();
        for (int i = 0; i < blocks.size(); i++) {
            assertEquals(digests[i], fx.digest(t, o.blockRoot(blocks.get(i))), "block " + i + " decodes as before");
        }
        final int rec = promotedRecord;
        assertThrows(IllegalStateException.class, () -> fx.builder.promote(rec), "nothing can be promoted any more");
        assertThrows(IllegalStateException.class, () -> store.blocks().promotedId(rec));
        ProofBlocks.Writer w = fx.builder.blockWriter();
        w.bvar(0);
        assertThrows(IllegalStateException.class, () -> w.finish(0), "no further block");
    }
}
