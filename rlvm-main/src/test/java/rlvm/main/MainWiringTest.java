package rlvm.main;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import rlvm.api.WorkerContext;
import rlvm.load.LoadOptions;
import rlvm.store.Store;

/** What the wiring hands to the driver, without a load: the store's list store and worker contexts. */
class MainWiringTest {

    @Test
    void storeUsesTheRunsListStoreAndWorkersComeAfterTheFreeze() {
        MainWiring w = new MainWiring(Path.of("unused.ndjson"), MainOptions.parse(null));
        Store store = w.newStore(1 << 20);
        assertSame(w.bindings().baseLists(), store.lists());
        assertSame(w.bindings().baseLevelInfo(), store.levelInfo());
        assertNull(w.loadReport());

        // before the load finished (the level base is not frozen) no worker context can exist
        assertThrows(IllegalStateException.class, () -> w.newWorkerContext(0, store, null, 1 << 20));
        w.bindings().freeze(); // what load() does at end of file
        WorkerContext ctx = w.newWorkerContext(0, store, null, 1 << 20);
        assertNotNull(ctx.overlay);
        assertSame(ctx.levels, ctx.lists);
        assertEquals(0, ctx.workerId);
        WorkerContext second = w.newWorkerContext(1, store, null, 1 << 20);
        assertTrue(second.overlay != ctx.overlay && second.levels != ctx.levels && second.lits != ctx.lits);
    }

    @Test
    void pipelinedLoadIsDeferred() {
        MainWiring w = new MainWiring(Path.of("unused.ndjson"), MainOptions.parse(null));
        Store store = w.newStore(0);
        LoadOptions pipelined;
        try {
            pipelined = LoadOptions.pipelined(0);
        } catch (UnsupportedOperationException e) {
            return; // the loader refuses the mode even earlier
        }
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class, () -> w.load(store, pipelined));
        assertTrue(e.getMessage().contains("pipelined"));
    }
}
