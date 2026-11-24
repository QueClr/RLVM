package rlvm.load;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import rlvm.level.LevelStore;
import rlvm.store.IntLists;
import rlvm.store.Store;

/**
 * Work package L2: pipelined loading.
 *
 * <ul>
 *   <li>the store of a pipelined load equals the store of a batch load, record by record
 *       ({@link Loaded#assertSameStore}), on all 230 published tests, Init and Std;
 *   <li>reader threads that follow the frontier while the loader runs, with the reader protocol of
 *       {@link LevelChain}, never observe an unpublished datum: every unit they read has the digest
 *       ({@link StoreDigest}) of the same unit in a batch load;
 *   <li>the hand-off signal, the level chain, and the publication before a decline.
 * </ul>
 */
final class PipelinedLoadTest {

    private static final int READERS = 6;

    // ---- the hand-off signal used by the reader threads ------------------------------------------------

    /** What a driver does with {@link LoadListener}: readers wait on it instead of polling. */
    private static final class Frontier implements LoadListener {
        private int units;
        private boolean ended;
        final List<Integer> calls = new ArrayList<>();
        boolean sawComplete;

        @Override
        public synchronized void published(int loadedUnits, boolean complete) {
            calls.add(loadedUnits);
            sawComplete |= complete;
            units = loadedUnits;
            notifyAll();
        }

        /** The loader thread is gone (end of file or failure). */
        synchronized void end() {
            ended = true;
            notifyAll();
        }

        /** Waits until more than {@code have} units are announced or the loader has ended; true = ended. */
        synchronized boolean await(int have) throws InterruptedException {
            while (units <= have && !ended) {
                wait();
            }
            return ended;
        }
    }

    /** One reader thread: digests every unit as soon as it is published. */
    private static final class Reader implements Runnable {
        private final Loaded pipe;
        private final Frontier frontier;
        private final long[] expected;
        private final boolean newestFirst;
        int unitsRead;
        int rebinds;
        long nodesRead;
        Throwable failure;

        Reader(Loaded pipe, Frontier frontier, long[] expected, boolean newestFirst) {
            this.pipe = pipe;
            this.frontier = frontier;
            this.expected = expected;
            this.newestFirst = newestFirst;
        }

        @Override
        public void run() {
            try {
                Store store = pipe.store;
                StoreDigest digest = new StoreDigest(store);
                int covered = 0; // units the reader's level view covers
                LevelStore base = null; // the snapshot store the view was built on
                int next = 0;
                for (;;) {
                    int n = store.loadedUnits(); // the frontier first, then everything else
                    if (next >= n) {
                        if (frontier.await(next) && next >= store.loadedUnits()) {
                            break;
                        }
                        continue;
                    }
                    if (n > covered) {
                        LevelChain.Snapshot s = pipe.chain.current();
                        if (s == null || s.units < n) {
                            throw new AssertionError("the frontier is at " + n + " units, the level snapshot covers "
                                    + (s == null ? "nothing" : s.units + " units"));
                        }
                        if (s.levels != base) {
                            // only a snapshot with new levels or lists needs a new view
                            base = s.levels;
                            digest.bind(base.newOverlay());
                            rebinds++;
                        }
                        covered = s.units;
                    }
                    for (int i = next; i < n; i++) {
                        int u = newestFirst ? n - 1 - (i - next) : i;
                        if (u >= expected.length) {
                            throw new AssertionError("unit " + u + " is published, the batch load has only "
                                    + expected.length + " units");
                        }
                        long h = digest.unit(u);
                        if (h != expected[u]) {
                            throw new AssertionError("unit " + u + " read at frontier " + n + " differs from the batch load");
                        }
                    }
                    unitsRead += n - next;
                    next = n;
                }
                nodesRead = digest.nodesRead;
            } catch (Throwable t) {
                failure = t;
            }
        }
    }

    /** Totals of one stress run. */
    private static final class Stress {
        Loaded pipe;
        Frontier frontier;
        long nodesRead;
        long rebinds;
        int unitsRead;
    }

    /**
     * Loads {@code data} / {@code file} in pipelined mode on a loader thread while {@code readers}
     * threads follow the frontier; every published unit must have the digest of the batch load.
     */
    private static Stress stress(String what, byte[] data, Path file, LoadOptions options, long[] expected, int readers)
            throws Exception {
        Frontier frontier = new Frontier();
        long bytes = data != null ? data.length : Files.size(file);
        Loaded pipe = Loaded.prepare(bytes, options.withListener(frontier));
        AtomicReference<Throwable> loaderError = new AtomicReference<>();
        Thread loaderThread = new Thread(null, () -> {
            try {
                if (data != null) {
                    pipe.run(data);
                } else {
                    pipe.run(file);
                }
            } catch (Throwable t) {
                loaderError.set(t);
            } finally {
                frontier.end();
            }
        }, "test-loader", 64L << 20);
        Reader[] rs = new Reader[readers];
        Thread[] ts = new Thread[readers];
        for (int i = 0; i < readers; i++) {
            rs[i] = new Reader(pipe, frontier, expected, (i & 1) == 1);
            ts[i] = new Thread(null, rs[i], "test-reader-" + i, 256L << 20);
            ts[i].start();
        }
        loaderThread.start();
        loaderThread.join();
        for (Thread t : ts) {
            t.join();
        }
        if (loaderError.get() != null) {
            throw new AssertionError(what + ": the loader thread died: " + loaderError.get(), loaderError.get());
        }
        Stress s = new Stress();
        s.pipe = pipe;
        s.frontier = frontier;
        int published = pipe.store.loadedUnits();
        for (Reader r : rs) {
            if (r.failure != null) {
                throw new AssertionError(what + ": a reader failed: " + r.failure, r.failure);
            }
            assertEquals(published, r.unitsRead, what + ": units read by a reader");
            s.nodesRead += r.nodesRead;
            s.rebinds += r.rebinds;
            s.unitsRead += r.unitsRead;
        }
        // the listener saw a monotone frontier that ends at the store's
        int last = 0;
        for (int n : frontier.calls) {
            assertTrue(n >= last, what + ": frontier went back");
            last = n;
        }
        assertEquals(published, last, what + ": last announced frontier");
        assertEquals(pipe.failure == null, frontier.sawComplete, what + ": complete announced");
        return s;
    }

    /** The batch load is the reference: the same file in pipelined mode, with and without readers. */
    private static long[] check(String what, byte[] data, Path file, int everyUnits, long everyBytes, int readers)
            throws Exception {
        long bytes = data != null ? data.length : Files.size(file);
        Loaded batch = data != null ? Loaded.load(data, LoadOptions.batch(bytes)) : Loaded.load(file, LoadOptions.batch(bytes));
        LoadOptions po = LoadOptions.pipelined(bytes).withPublishEvery(everyUnits, everyBytes);
        // 1. alone: identical store, identical outcome
        Loaded pipe = data != null ? Loaded.load(data, po) : Loaded.load(file, po);
        if (batch.failure != null) {
            assertNotNull(pipe.failure, what + ": the pipelined load must fail like the batch load");
            assertEquals(batch.failure.getClass(), pipe.failure.getClass(), what);
            assertEquals(batch.failure.getMessage(), pipe.failure.getMessage(), what);
            if (batch.failure instanceof MalformedExportException) {
                assertEquals(((MalformedExportException) batch.failure).line(),
                        ((MalformedExportException) pipe.failure).line(), what);
            }
            assertFalse(pipe.store.loadComplete(), what);
            assertEquals(0, batch.store.loadedUnits(), what + ": a failed batch load publishes nothing by default");
        } else {
            assertNull(pipe.failure, what + ": " + pipe.failure);
            assertTrue(pipe.store.loadComplete(), what);
            assertTrue(pipe.chain.isClosed(), what);
            assertEquals(pipe.store.decls().unitCount(), pipe.store.loadedUnits(), what);
            assertEquals(pipe.store.loadedUnits(), pipe.chain.current().units, what);
            Loaded.assertSameStore(batch, pipe, what);
            // the counters agree; block bytes may differ by a few varint bytes (level and list ids)
            String skip = "(?m)^load:.*$|(?m)^  publications.*$|\\([\\d,]+ bytes, [\\d.]+ B/record\\)";
            assertEquals(batch.report.toString().replaceAll(skip, ""), pipe.report.toString().replaceAll(skip, ""),
                    what + ": reports");
        }
        // 2. the digests of the batch store are the reference for the readers (for a failed load: of
        //    the units that were complete when it failed, read by the thread that loaded them)
        int complete = batch.store.decls().unitCount();
        long[] expected = complete == 0 || batch.base() == null ? new long[0] : batch.digests(complete);
        if (pipe.store.loadedUnits() > 0) {
            long[] mine = pipe.digests(pipe.store.loadedUnits());
            for (int u = 0; u < mine.length; u++) {
                assertEquals(expected[u], mine[u], what + ": digest of unit " + u);
            }
        }
        // 3. with readers
        if (readers > 0) {
            Stress s = stress(what, data, file, po, expected, readers);
            assertEquals(pipe.store.loadedUnits(), s.pipe.store.loadedUnits(), what + ": published units");
            if (batch.failure == null) {
                Loaded.assertSameStore(batch, s.pipe, what + " (with readers)");
            } else {
                assertNotNull(s.pipe.failure, what);
                assertEquals(batch.failure.getMessage(), s.pipe.failure.getMessage(), what);
            }
        }
        return expected;
    }

    // ---- all 230 published tests ----------------------------------------------------------------------

    @Test
    void all230FilesBatchAndPipelinedGiveTheSameStoreAndReadersSeeOnlyPublishedData() throws Exception {
        List<Path> files = ArenaFilesTest.files();
        Assumptions.assumeTrue(!files.isEmpty(), "arena tests not present");
        int failed = 0;
        long units = 0;
        for (Path file : files) {
            byte[] data = Files.readAllBytes(file);
            String rel = ArenaFilesTest.TESTS.relativize(file).toString();
            // a hand-off after every unit: the largest number of snapshots and interleavings
            long[] digests = check(rel, data, null, 1, 1L << 40, 3);
            units += digests.length;
            if (digests.length > 40) {
                // and a coarse one, by bytes
                check(rel + " (by bytes)", data, null, Integer.MAX_VALUE, 4096, 0);
            }
            Loaded b = Loaded.load(data, LoadOptions.batch(data.length));
            if (b.failure != null) {
                failed++;
            }
        }
        assertEquals(6, failed, "files rejected at load (tutorial 132-135, 138, 139)");
        System.out.println("[pipelined] 230 files: " + units + " units compared and read concurrently");
    }

    // ---- Init and Std ----------------------------------------------------------------------------------

    private static void big(String name, int everyUnits, long everyBytes, int readers) throws Exception {
        Path file = BigFilesTest.EXPORTS.resolve(name);
        Assumptions.assumeTrue(Files.isRegularFile(file), name + " not present");
        long t0 = System.nanoTime();
        long[] digests = check(name, null, file, everyUnits, everyBytes, readers);
        System.out.printf(java.util.Locale.ROOT, "[pipelined] %s: %,d units, batch = pipelined = pipelined with %d readers, %.1f s%n",
                name, digests.length, readers, (System.nanoTime() - t0) / 1e9);
    }

    @Test
    void initBatchAndPipelinedGiveTheSameStoreAndReadersSeeOnlyPublishedData() throws Exception {
        big("init.ndjson", 16, 1L << 40, READERS);
    }

    @Test
    void initWithTheDefaultHandOffInterval() throws Exception {
        Path file = BigFilesTest.EXPORTS.resolve("init.ndjson");
        Assumptions.assumeTrue(Files.isRegularFile(file), "init.ndjson not present");
        LoadOptions d = LoadOptions.pipelined(Files.size(file));
        big("init.ndjson", d.publishEveryUnits, d.publishEveryBytes, 2);
    }

    @Test
    void stdBatchAndPipelinedGiveTheSameStoreAndReadersSeeOnlyPublishedData() throws Exception {
        big("std.ndjson", 256, 1L << 20, 4);
    }

    /**
     * The chain serves batch mode too: one snapshot, at the end, and the store is the very store of a
     * batch load over a plain level store, level ids and list ids included.
     */
    @Test
    void aBatchLoadOnTheChainIsBitIdentical() throws Exception {
        List<Path> files = new ArrayList<>(ArenaFilesTest.files());
        Path init = BigFilesTest.EXPORTS.resolve("init.ndjson");
        if (Files.isRegularFile(init)) {
            files.add(init);
        }
        Assumptions.assumeTrue(!files.isEmpty(), "no export present");
        for (Path file : files) {
            String rel = file.getFileName().toString();
            LoadOptions batch = LoadOptions.batch(Files.size(file));
            Loaded plain = Loaded.load(file, batch);
            Loaded chained = Loaded.loadOnChain(file, batch);
            if (plain.failure != null) {
                assertNotNull(chained.failure, rel);
                assertEquals(plain.failure.getMessage(), chained.failure.getMessage(), rel);
                assertNull(chained.chain.current(), rel + ": nothing published");
                continue;
            }
            assertNull(chained.failure, rel + ": " + chained.failure);
            assertEquals(1, chained.chain.generations(), rel);
            assertTrue(chained.chain.isClosed(), rel);
            Loaded.assertIdenticalStore(plain, chained, rel);
        }
    }

    // ---- the level chain -------------------------------------------------------------------------------

    @Test
    void snapshotsKeepIdsAndOtherThreadsSeeOnlyPublishedLevelsAndLists() throws Exception {
        LevelChain chain = new LevelChain(new LevelStore());
        IntLists lists = chain.lists();
        assertNull(chain.current());
        int u = chain.param(7);
        int su = chain.succ(u);
        int l1 = chain.internList(new int[] {u, su}, 0, 2);
        chain.publish(3);
        LevelChain.Snapshot s1 = chain.current();
        assertEquals(3, s1.units);
        assertEquals(1, s1.generation);
        assertTrue(s1.levels.isFrozen());
        // nothing new: the same store covers more units
        chain.publish(5);
        LevelChain.Snapshot s2 = chain.current();
        assertTrue(s2.levels == s1.levels);
        assertEquals(5, s2.units);
        assertEquals(1, s2.generation);
        // new levels and lists continue the numbering; old ids mean the same thing
        int m = chain.max(su, chain.param(9));
        int l2 = chain.internList(new int[] {m}, 0, 1);
        assertEquals(u, chain.param(7));
        assertEquals(l1, chain.internList(new int[] {u, su}, 0, 2));
        assertTrue(m >= s1.levels.size());
        // the loader thread reads what it wrote; another thread is refused the unpublished ids
        assertEquals(1, lists.length(l2));
        assertEquals(m, lists.get(l2, 0));
        assertEquals(su, chain.info().lhs(m));
        Throwable[] seen = new Throwable[4];
        int[] ok = new int[2];
        Thread other = new Thread(() -> {
            try {
                ok[0] = lists.length(l1); // published with snapshot 1
                ok[1] = chain.info().lhs(su);
            } catch (Throwable t) {
                seen[3] = t;
            }
            try {
                lists.length(l2);
            } catch (Throwable t) {
                seen[0] = t;
            }
            try {
                chain.info().lhs(m);
            } catch (Throwable t) {
                seen[1] = t;
            }
            try {
                lists.intern(new int[] {1, 2, 3}, 0, 3);
            } catch (Throwable t) {
                seen[2] = t;
            }
        });
        other.start();
        other.join();
        assertNull(seen[3], "published ids are readable from any thread: " + seen[3]);
        assertEquals(2, ok[0]);
        assertEquals(u, ok[1]);
        for (int i = 0; i < 3; i++) {
            assertTrue(seen[i] instanceof IllegalStateException, "case " + i + ": " + seen[i]);
        }
        chain.publish(8);
        LevelChain.Snapshot s3 = chain.current();
        assertEquals(2, s3.generation);
        assertTrue(s3.levels != s1.levels);
        assertEquals(LevelStore.KIND_MAX, s3.levels.kind(m));
        assertEquals(su, s3.levels.lhs(m));
        assertEquals(LevelStore.KIND_PARAM, s3.levels.kind(u));
        assertEquals(7, s3.levels.paramName(u));
        assertEquals(m, s3.levels.listGet(l2, 0));
        // a worker overlay of a snapshot: private levels do not disturb the chain
        LevelStore worker = s3.levels.newOverlay();
        int w = worker.succ(m);
        assertTrue(w >= s3.levels.size());
        int next = chain.succ(m);
        chain.finish(9);
        assertTrue(chain.isClosed());
        LevelChain.Snapshot s4 = chain.current();
        assertTrue(s4.last);
        assertEquals(9, s4.units);
        assertEquals(LevelStore.KIND_SUCC, s4.levels.kind(next));
        assertThrows(IllegalStateException.class, () -> chain.succ(next));
        assertThrows(IllegalArgumentException.class, () -> new LevelChain(s4.levels));
        // after the end every thread, the loader's included, reads the final snapshot
        assertEquals(1, lists.length(l2));
    }

    // ---- publication before a decline -------------------------------------------------------------------

    /** Two good declarations, an inductive record the loader declines (metadata in a constructor type), one more. */
    private static byte[] declinedInTheMiddle() {
        Nd b = new Nd();
        int type1 = b.sort(b.succ(0));
        b.axiom(b.name("a"), type1);
        b.def(b.name("d"), type1, b.sort(0));
        int t = b.name("T");
        int mk = b.str(t, "mk");
        int ct = b.cnst(t);
        String ty = Nd.type(t, type1, 0, 0, Nd.ints(t), Nd.ints(mk), false);
        String ctor = Nd.ctor(mk, b.mdata(ct), t, 0, 0, 0, false);
        b.inductive(Nd.strs(ty), Nd.strs(ctor), Nd.strs());
        b.axiom(b.name("after"), type1);
        return b.bytes();
    }

    @Test
    void aDeclineIsTheStatusOfTheFirstUnitThatWasNotLoaded() {
        byte[] data = declinedInTheMiddle();
        // batch, as before: nothing is published, the whole file is declined
        Loaded plain = Loaded.load(data, LoadOptions.batch(data.length));
        assertTrue(plain.failure instanceof UnsupportedExportException, String.valueOf(plain.failure));
        assertEquals(0, plain.store.loadedUnits());
        assertEquals(0, plain.report.publishedUnits);
        // batch with publishOnDecline: the two complete units are handed over, the load stays incomplete
        Frontier f = new Frontier();
        Loaded opt = Loaded.load(data, LoadOptions.batch(data.length).withPublishOnDecline(true).withListener(f));
        assertTrue(opt.failure instanceof UnsupportedExportException, String.valueOf(opt.failure));
        assertTrue(opt.failure.getMessage().contains("metadata (mdata) node inside an inductive record"));
        assertEquals(2, opt.store.loadedUnits());
        assertFalse(opt.store.loadComplete());
        assertFalse(opt.report.complete);
        assertEquals(2, opt.report.publishedUnits);
        assertEquals(List.of(2), f.calls);
        assertFalse(f.sawComplete);
        // pipelined: always
        Loaded pipe = Loaded.load(data, LoadOptions.pipelined(data.length));
        assertTrue(pipe.failure instanceof UnsupportedExportException);
        assertEquals(2, pipe.store.loadedUnits());
        assertFalse(pipe.store.loadComplete());
        assertEquals(2, pipe.chain.current().units);
        assertFalse(pipe.chain.isClosed());
        // the published units are the units of the complete prefix
        long[] expected = opt.digests(2);
        long[] got = pipe.digests(2);
        assertEquals(expected[0], got[0]);
        assertEquals(expected[1], got[1]);
    }

    @Test
    void aRejectionPublishesNothingMoreAndIsNeverComplete() {
        Nd b = new Nd();
        int type1 = b.sort(b.succ(0));
        int a = b.name("a");
        b.axiom(a, type1);
        b.axiom(b.name("c"), type1);
        b.axiom(a, type1); // duplicate declaration: the official reader fails on the whole file
        byte[] data = b.bytes();
        for (LoadOptions o : new LoadOptions[] {LoadOptions.batch(data.length).withPublishOnDecline(true),
                LoadOptions.pipelined(data.length).withPublishEvery(100, 1L << 40)}) {
            Loaded l = Loaded.load(data, o);
            assertTrue(l.failure instanceof MalformedExportException, o.mode + ": " + l.failure);
            assertEquals(0, l.store.loadedUnits(), o.mode + ": a rejection hands over nothing");
            assertFalse(l.store.loadComplete());
        }
        // a hand-off that happened before the bad line stays (the driver's verdict is REJECT anyway)
        Loaded early = Loaded.load(data, LoadOptions.pipelined(data.length).withPublishEvery(1, 1L << 40));
        assertTrue(early.failure instanceof MalformedExportException);
        assertEquals(2, early.store.loadedUnits());
        assertFalse(early.store.loadComplete());
    }

    @Test
    void droppedRecordsAreUnitsOfTheFrontier() {
        Nd b = new Nd();
        int type1 = b.sort(b.succ(0));
        b.axiom(b.name("a"), type1);
        b.axiom(b.name("u"), type1, true); // unsafe: dropped, but a unit of the store
        b.def(b.name("d"), type1, b.sort(0));
        byte[] data = b.bytes();
        Frontier f = new Frontier();
        Loaded l = Loaded.load(data, LoadOptions.pipelined(data.length).withPublishEvery(1, 1L << 40).withListener(f));
        assertNull(l.failure);
        assertEquals(List.of(1, 2, 3, 3), f.calls);
        assertTrue(f.sawComplete);
        assertEquals(3, l.store.loadedUnits());
        assertEquals(4, l.report.publications);
        Loaded batch = Loaded.load(data, LoadOptions.batch(data.length));
        Loaded.assertSameStore(batch, l, "dropped record");
    }

    @Test
    void scanThreadsComeFromTheLoadOptions() throws IOException {
        Path file = BigFilesTest.EXPORTS.resolve("init.ndjson");
        Assumptions.assumeTrue(Files.isRegularFile(file), "init.ndjson not present");
        long bytes = Files.size(file);
        assertEquals(1, ExportLoader.readerOptions(LoadOptions.batch(bytes)).threads);
        assertEquals(3, ExportLoader.readerOptions(LoadOptions.batch(bytes).withScanThreads(3)).threads);
        Loaded one = Loaded.load(file, LoadOptions.batch(bytes));
        Loaded four = Loaded.load(file, LoadOptions.pipelined(bytes).withScanThreads(4));
        assertNull(four.failure);
        assertEquals(1, one.report.scanThreads);
        assertEquals(4, four.report.scanThreads);
        Loaded.assertSameStore(one, four, "init with 4 scanner threads, pipelined");
    }
}
