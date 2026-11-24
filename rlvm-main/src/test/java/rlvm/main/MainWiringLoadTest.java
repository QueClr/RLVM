package rlvm.main;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import rlvm.api.WorkerContext;
import rlvm.load.LoadOptions;
import rlvm.load.LoadReport;
import rlvm.load.MalformedExportException;
import rlvm.load.UnsupportedExportException;
import rlvm.store.Store;

/**
 * The load path of the wiring on the published arena tests: reader, loader, store, level base
 * frozen at end of file, worker contexts afterwards. No engine, no admission.
 */
class MainWiringLoadTest {

    private static Path testsRoot() {
        Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("work/arena-site/tests"))) {
            p = p.getParent();
        }
        return p == null ? null : p.resolve("work/arena-site/tests");
    }

    private static List<Path> files(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(f -> f.toString().endsWith(".ndjson")).sorted().toList();
        }
    }

    @Test
    void everyPublishedTestLoadsOrIsRefusedByTheReader() throws Exception {
        Path root = testsRoot();
        assumeTrue(root != null, "work/arena-site/tests not found");
        List<String> problems = new ArrayList<>();
        int loaded = 0;
        int refused = 0;
        long units = 0;
        for (String bucket : new String[] {"good", "bad"}) {
            for (Path f : files(root.resolve(bucket))) {
                String name = root.relativize(f).toString();
                MainWiring w = new MainWiring(f, MainOptions.parse(null));
                long bytes = Files.size(f);
                Store store = w.newStore(bytes);
                try {
                    w.load(store, LoadOptions.batch(bytes));
                } catch (MalformedExportException e) {
                    refused++;
                    if (bucket.equals("good")) {
                        problems.add(name + ": a good test was refused as malformed: " + e.getMessage());
                    }
                    assertNotNull(w.loadReport(), "the counters up to the failing record: " + name);
                    continue;
                } catch (UnsupportedExportException e) {
                    problems.add(name + ": unsupported: " + e.getMessage());
                    continue;
                } catch (RuntimeException e) {
                    problems.add(name + ": " + e);
                    continue;
                }
                loaded++;
                LoadReport r = w.loadReport();
                assertNotNull(r, name);
                if (!w.bindings().isFrozen()) {
                    problems.add(name + ": level base not frozen after the load");
                    continue;
                }
                if (!store.loadComplete()) {
                    problems.add(name + ": store.loadComplete() is false after the load");
                }
                units += store.loadedUnits();
                // worker contexts exist now, and a second one is independent of the first
                WorkerContext ctx = w.newWorkerContext(0, store, null, 16L << 20);
                ctx.terms.refresh();
                assertNotNull(w.newWorkerContext(1, store, null, 16L << 20), name);
                ctx.reset();
            }
        }
        assertEquals(List.of(), problems, problems.size() + " files have problems");
        assertTrue(loaded >= 200, "loaded " + loaded);
        assertTrue(units > 0);
        // the six bad tests the official reader itself rejects, at most (a few more if the loader is stricter)
        assertTrue(refused <= 16, "refused at load: " + refused);
    }

    /** {@code -X load=pipelined} as MainOptions. */
    private static MainOptions pipelined() {
        return MainOptions.parse(java.util.Map.of("load", "pipelined"));
    }

    /**
     * Reads everything of units {@code [from, to)} that names a level or a list through the worker's
     * context: universe parameter lists, the levels of every SORT and the level lists of every CONST
     * reachable from the type roots (bounded). An id the context's snapshot does not contain throws.
     */
    private static void touchLevels(Store store, WorkerContext ctx, int from, int to) {
        rlvm.store.DeclTable decls = store.decls();
        int[] stack = new int[256];
        for (int u = from; u < to; u++) {
            int first = decls.unitFirstCid(u);
            for (int cid = first; cid < first + decls.unitSize(u); cid++) {
                int lp = decls.levelParams(cid);
                for (int i = 0, n = ctx.lists.length(lp); i < n; i++) {
                    ctx.levels.param(ctx.lists.get(lp, i));
                }
                int sp = 0;
                int budget = 4000;
                stack[sp++] = decls.type(cid);
                while (sp > 0 && budget-- > 0) {
                    int t = stack[--sp];
                    if (t <= 0) {
                        continue;
                    }
                    if (sp + 3 > stack.length) {
                        stack = java.util.Arrays.copyOf(stack, stack.length * 2);
                    }
                    switch (ctx.terms.kind(t)) {
                        case rlvm.store.Kind.SORT:
                            ctx.levels.toString(ctx.terms.a(t));
                            ctx.lists.length(ctx.terms.lpSet(t));
                            break;
                        case rlvm.store.Kind.CONST: {
                            int ls = ctx.terms.b(t);
                            for (int i = 0, n = ctx.lists.length(ls); i < n; i++) {
                                ctx.levels.toString(ctx.lists.get(ls, i));
                            }
                            break;
                        }
                        case rlvm.store.Kind.APP:
                        case rlvm.store.Kind.LAM:
                        case rlvm.store.Kind.PI:
                            stack[sp++] = ctx.terms.a(t);
                            stack[sp++] = ctx.terms.b(t);
                            break;
                        case rlvm.store.Kind.LET:
                            stack[sp++] = ctx.terms.a(t);
                            stack[sp++] = ctx.terms.b(t);
                            stack[sp++] = ctx.terms.c(t);
                            break;
                        case rlvm.store.Kind.PROJ:
                            stack[sp++] = ctx.terms.a(t);
                            break;
                        default:
                            break;
                    }
                }
            }
        }
    }

    /**
     * Pipelined mode on the published arena tests with a hand-off after EVERY unit: a second thread
     * keeps one worker context, resets it at each publication and reads the levels and lists of the
     * newly published units through it while the loader thread waits (a deterministic interleaving of
     * what a driver does). The context must cover every announced unit, and the number of loaded
     * units must be that of a batch load of the same file.
     */
    @Test
    void pipelinedLoadPublishesLevelSnapshotsThatWorkerContextsFollow() throws Exception {
        Path root = testsRoot();
        assumeTrue(root != null, "work/arena-site/tests not found");
        List<String> problems = new ArrayList<>();
        int loaded = 0;
        long publications = 0;
        java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            for (String bucket : new String[] {"good", "bad"}) {
                for (Path f : files(root.resolve(bucket))) {
                    String name = root.relativize(f).toString();
                    long bytes = Files.size(f);
                    if (bytes > (8L << 20)) {
                        continue; // the big perf tests add nothing here
                    }
                    // batch load of the same file: the unit count to compare with
                    MainWiring bw = new MainWiring(f, MainOptions.parse(null));
                    Store bs = bw.newStore(bytes);
                    int batchUnits;
                    try {
                        bw.load(bs, LoadOptions.batch(bytes));
                        batchUnits = bs.loadedUnits();
                    } catch (MalformedExportException | UnsupportedExportException e) {
                        batchUnits = -1;
                    }

                    final MainWiring w = new MainWiring(f, pipelined());
                    final Store store = w.newStore(bytes);
                    assertTrue(w.hasLevelSnapshots(), name);
                    final WorkerContext[] ctx = new WorkerContext[1];
                    final int[] seen = new int[1];
                    final long[] count = new long[1];
                    final List<String> faults = new ArrayList<>();
                    LoadOptions options = LoadOptions.pipelined(bytes).withPublishEvery(1, 1).withListener((units, complete) -> {
                        count[0]++;
                        try {
                            worker.submit(() -> {
                                if (ctx[0] == null) {
                                    ctx[0] = w.newWorkerContext(0, store, null, 16L << 20);
                                } else {
                                    ctx[0].reset();
                                }
                                if (ctx[0].coveredUnits() < units) {
                                    faults.add("covers " + ctx[0].coveredUnits() + " units, " + units + " announced");
                                }
                                touchLevels(store, ctx[0], seen[0], units);
                                seen[0] = units;
                            }).get();
                        } catch (Exception e) {
                            faults.add("at " + units + " units: " + (e.getCause() == null ? e : e.getCause()));
                        }
                    });
                    boolean ok = true;
                    try {
                        w.load(store, options);
                    } catch (MalformedExportException | UnsupportedExportException e) {
                        ok = false;
                    } catch (RuntimeException e) {
                        problems.add(name + ": " + e);
                        continue;
                    }
                    publications += count[0];
                    if (!faults.isEmpty()) {
                        problems.add(name + ": " + faults.get(0) + (faults.size() > 1 ? " (+" + (faults.size() - 1) + ")" : ""));
                    }
                    if (ok != (batchUnits >= 0)) {
                        problems.add(name + ": pipelined load " + (ok ? "succeeded" : "failed") + ", batch load did the opposite");
                    }
                    if (ok) {
                        loaded++;
                        if (!store.loadComplete() || store.loadedUnits() != batchUnits) {
                            problems.add(name + ": " + store.loadedUnits() + " units (complete: " + store.loadComplete()
                                    + "), batch load " + batchUnits);
                        }
                        if (seen[0] != store.loadedUnits()) {
                            problems.add(name + ": the last publication announced " + seen[0] + " of " + store.loadedUnits());
                        }
                        // a context created after the load sees everything
                        WorkerContext late = w.newWorkerContext(1, store, null, 16L << 20);
                        touchLevels(store, late, 0, store.loadedUnits());
                    }
                }
            }
        } finally {
            worker.shutdownNow();
        }
        assertEquals(List.of(), problems, problems.size() + " files have problems");
        assertTrue(loaded >= 180, "loaded " + loaded);
        assertTrue(publications > loaded, "hand-offs happened during the loads: " + publications);
    }

    /** A batch load into a store created for pipelined loading: one snapshot at the end, contexts work. */
    @Test
    void aBatchLoadIntoAPipelinedStoreWorksAndTheOppositeIsRefused() throws Exception {
        Path root = testsRoot();
        assumeTrue(root != null, "work/arena-site/tests not found");
        Path f = root.resolve("good/init-prelude.ndjson");
        assumeTrue(Files.isRegularFile(f));
        long bytes = Files.size(f);
        MainWiring w = new MainWiring(f, pipelined());
        Store store = w.newStore(bytes);
        w.load(store, LoadOptions.batch(bytes));
        assertTrue(store.loadComplete());
        WorkerContext ctx = w.newWorkerContext(0, store, null, 16L << 20);
        assertEquals(store.loadedUnits(), ctx.coveredUnits());
        touchLevels(store, ctx, 0, store.loadedUnits());
        ctx.reset();
        touchLevels(store, ctx, 0, store.loadedUnits());

        MainWiring batch = new MainWiring(f, MainOptions.parse(null));
        Store plain = batch.newStore(bytes);
        assertThrows(UnsupportedOperationException.class, () -> batch.load(plain, LoadOptions.pipelined(bytes)));
    }

    @Test
    void aMissingFileIsAnIoError() {
        Path missing = Path.of("/nonexistent/rlvm-main-test.ndjson");
        MainWiring w = new MainWiring(missing, MainOptions.parse(null));
        Store store = w.newStore(0);
        assertThrows(IOException.class, () -> w.load(store, LoadOptions.batch(0)));
    }
}
