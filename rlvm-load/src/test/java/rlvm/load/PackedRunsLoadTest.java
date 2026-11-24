package rlvm.load;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import rlvm.export.ExportReader;
import rlvm.store.ProofBlocks;
import rlvm.store.TermStore;

/**
 * Parallel packing (LP2): scanner threads prepare the packed form of the runs of proof-private
 * records, the loader thread links them into the proof blocks ({@link Loader#exprRun}). THE
 * INVARIANT: the store is word for word the store of the record-by-record path, on every file, and a
 * file that fails does so with the same exception, message and line, leaving the same partial store.
 *
 * <p>Every comparison here is between a load with the reader's defaults (one thread, no runs) and
 * loads of the same bytes with scanner threads, small chunks and runs: on the real store through the
 * production binding (the block writer's own {@code run}), and through a recording target (the
 * record-by-record default of {@link LoadTarget#blockRun}).
 */
class PackedRunsLoadTest {

    private static ExportReader.Options packing(LoadOptions o, int threads, int chunk) {
        ExportReader.Options ro = ExportLoader.readerOptions(o);
        assertNotNull(ro.runs, "the loader asks the reader for packed runs");
        ro.threads = threads;
        ro.parallelChunkBytes = chunk;
        return ro;
    }

    private static void sameReport(String what, LoadReport a, LoadReport b) {
        long[] x = counters(a);
        long[] y = counters(b);
        assertEquals(Arrays.toString(x), Arrays.toString(y), what + ": counters of the report");
    }

    private static long[] counters(LoadReport r) {
        return new long[] {r.nameRecords, r.levelRecords, r.exprRecords, r.exprApp, r.exprLam, r.exprForall, r.exprLet, r.exprProj,
            r.exprConst, r.exprBVar, r.exprSort, r.exprNatLit, r.exprStrLit, r.exprMData, r.declRecords, r.constants, r.units,
            r.sharedNodes, r.blockNodes, r.blocks, r.blockValues, r.blockBytes, r.promotions, r.promotedNodes, r.droppedUnsafe,
            r.unresolvedRefs, r.unresolvedBlockRefs, r.trailingExprRecords, r.largestSegment, r.publications, r.publishedUnits,
            r.complete ? 1 : 0};
    }

    /** What a failed load leaves: every word of the export region, the blocks' table, the location of every record. */
    private static void samePartialStore(String what, Loaded a, Loaded b) {
        TermStore ta = a.store.terms();
        TermStore tb = b.store.terms();
        assertEquals(ta.exportEnd(), tb.exportEnd(), what + ": export nodes");
        assertTrue(Arrays.equals(ta.exportArray(), 0, ta.exportEnd() << 3, tb.exportArray(), 0, tb.exportEnd() << 3),
                what + ": export region");
        ProofBlocks pa = a.store.blocks();
        ProofBlocks pb = b.store.blocks();
        assertEquals(pa.count(), pb.count(), what + ": blocks");
        assertEquals(pa.recordCount(), pb.recordCount(), what + ": block records");
        assertEquals(pa.promotedCount(), pb.promotedCount(), what + ": promoted records");
        for (int k = 0; k < pa.count(); k++) {
            assertEquals(pa.byteLength(k), pb.byteLength(k), what + ": bytes of block " + k);
            assertEquals(pa.nodeCount(k), pb.nodeCount(k), what + ": records of block " + k);
            assertEquals(pa.rootRecord(k), pb.rootRecord(k), what + ": root of block " + k);
            assertEquals(pa.hasUnresolved(k), pb.hasUnresolved(k), what + ": block " + k);
        }
        assertEquals(a.store.names().size(), b.store.names().size(), what + ": names");
        assertEquals(a.loader.exprCount(), b.loader.exprCount(), what + ": expression records");
        for (int i = 1, n = a.loader.exprCount(); i <= n; i++) {
            if (a.loader.locationOf(i) != b.loader.locationOf(i)) {
                assertEquals(a.loader.locationOf(i), b.loader.locationOf(i), what + ": location of record " + i);
            }
        }
    }

    /** The load with runs against the load without; returns the records that went through runs. */
    private static long compare(String what, byte[] data, Loaded plain, LoadOptions o, int threads, int chunk) {
        Loaded packed = Loaded.load(data, o, packing(o, threads, chunk));
        if (plain.failure != null) {
            assertNotNull(packed.failure, what + ": must fail like the load without runs: " + plain.failure);
            assertEquals(plain.failure.getClass(), packed.failure.getClass(), what);
            assertEquals(plain.failure.getMessage(), packed.failure.getMessage(), what);
            if (plain.failure instanceof MalformedExportException m) {
                assertEquals(m.line(), ((MalformedExportException) packed.failure).line(), what);
            }
            samePartialStore(what, plain, packed);
        } else {
            assertNull(packed.failure, what + ": " + packed.failure);
            Loaded.assertIdenticalStore(plain, packed, what);
            samePartialStore(what, plain, packed);
        }
        sameReport(what, plain.report, packed.report);
        return packed.report.packedRunRecords;
    }

    private static long allWays(String what, byte[] data) {
        long runs = 0;
        LoadOptions batch = LoadOptions.batch(data.length);
        Loaded plain = Loaded.load(data, batch);
        if (data.length >= 512) {
            runs += compare(what + " [3 threads, 256]", data, plain, batch, 3, 256);
        }
        if (data.length >= 2 * 1777) {
            runs += compare(what + " [2 threads, 1777]", data, plain, batch, 2, 1777);
        }
        if (data.length >= 2 * 65536) {
            runs += compare(what + " [2 threads, 64 KiB]", data, plain, batch, 2, 65536);
        }
        // pipelined, a hand-off after every unit
        LoadOptions pipe = LoadOptions.pipelined(data.length).withPublishEvery(1, 1L << 40);
        if (data.length >= 2 * 700) {
            Loaded plainPipe = Loaded.load(data, pipe);
            runs += compare(what + " [pipelined, 2 threads, 700]", data, plainPipe, pipe, 2, 700);
        }
        return runs;
    }

    @Test
    void theArenaFilesLoadToTheSameStore() throws IOException {
        List<Path> files = ArenaFilesTest.files();
        long runRecords = 0;
        int failed = 0;
        for (Path file : files) {
            byte[] data = Files.readAllBytes(file);
            runRecords += allWays(ArenaFilesTest.TESTS.relativize(file).toString(), data);
            if (Loaded.load(data, LoadOptions.batch(data.length)).failure != null) {
                failed++;
            }
        }
        assertEquals(6, failed, "files rejected at load");
        assertTrue(runRecords > 10_000, "records read through runs: " + runRecords);
        System.out.println("[packed runs] 230 files: " + runRecords + " records through runs (all configurations)");
    }

    @Test
    void randomExportsLoadToTheSameStore() {
        long runRecords = 0;
        for (int seed = 0; seed < 600; seed++) {
            byte[] data = RandomExportTest.generate(seed);
            runRecords += allWays("random export " + seed, data);
        }
        // random exports name any record as type and value, drop records, have inductive blocks
        assertTrue(runRecords > 20_000, "records read through runs: " + runRecords);
    }

    /** Proof-shaped files: theorems with long proofs, definitions, dropped records, inductive blocks in between. */
    private static byte[] proofs(long seed, int decls, int size, boolean hostile) {
        Random rnd = new Random(seed);
        Nd b = new Nd();
        int u = b.name("u");
        int lu = b.param(u);
        int n0 = b.name("x");
        b.sort(0);
        b.bvar(0);
        b.sort(b.succ(0));
        for (int d = 0; d < decls; d++) {
            int name = b.str(0, "d" + d);
            int kind = rnd.nextInt(16);
            // the type's records
            int first = b.exprs + 1;
            int type = term(b, rnd, 1 + rnd.nextInt(8), n0, name, lu, false);
            // the value's records
            int value = term(b, rnd, 1 + rnd.nextInt(size), n0, name, lu, hostile);
            switch (kind) {
                case 0 -> b.axiom(name, type);
                case 1, 2 -> b.def(name, type, value);
                case 3 -> b.def(name, type, value, "\"abbrev\"", rnd.nextBoolean() ? "unsafe" : "partial"); // dropped: the records stay
                case 4 -> b.opaque(name, type, value, rnd.nextBoolean());
                case 5 -> b.thm(name, first + rnd.nextInt(b.exprs - first + 1), value); // a type in the middle of the records
                case 6 -> b.thm(name, rnd.nextInt(first), value);                        // a type from an earlier declaration
                case 7 -> b.thm(name, type, type + rnd.nextInt(b.exprs - type + 1));     // a value that is not the last record
                case 8 -> b.inductive(Nd.strs(Nd.type(name, type, 0, 0, Nd.ints(name), Nd.ints(), false)), Nd.strs(), Nd.strs());
                default -> b.thm(name, type, value);
            }
        }
        // records after the last declaration
        term(b, rnd, rnd.nextInt(30), n0, n0, lu, false);
        return b.bytes();
    }

    private static int any(Nd b, Random rnd) {
        int n = b.exprs + 1;
        return rnd.nextInt(5) == 0 ? rnd.nextInt(n) : n - 1 - rnd.nextInt(Math.min(n, 5));
    }

    private static int term(Nd b, Random rnd, int n, int binder, int cname, int level, boolean hostile) {
        for (int i = 0; i < n; i++) {
            int k = rnd.nextInt(hostile ? 60 : 50);
            if (k < 30) {
                b.app(any(b, rnd), any(b, rnd));
            } else if (k < 36) {
                b.lam(any(b, rnd), any(b, rnd));
            } else if (k < 40) {
                b.pi(any(b, rnd), any(b, rnd));
            } else if (k < 43) {
                b.let(any(b, rnd), any(b, rnd), any(b, rnd));
            } else if (k < 45) {
                b.proj(cname, "" + rnd.nextInt(3), any(b, rnd));
            } else if (k < 47) {
                b.cnst(cname, rnd.nextBoolean() ? new int[] {level} : new int[0]);
            } else if (k == 47) {
                b.bvar(rnd.nextInt(4));
            } else if (k == 48) {
                b.nat("" + rnd.nextInt(100));
            } else if (k == 49) {
                b.str(0, "n" + b.names); // a name record between expression records
            } else if (rnd.nextInt(12) != 0) {
                b.app(any(b, rnd), any(b, rnd));
            } else if (k < 53) {
                b.mdata(any(b, rnd)); // metadata: the expression ids are not dense any more
            } else if (k < 55) {
                // an expression record in another spelling (general path)
                b.raw("{ \"ie\":" + (++b.exprs) + ",\"app\":{\"fn\":" + rnd.nextInt(b.exprs) + ",\"arg\":" + rnd.nextInt(b.exprs) + "}}");
            } else if (k < 57) {
                // a declaration in another spelling: the scanner threads do not see that a segment ends
                // here, so the runs behind it were packed for the wrong segment and must be expanded
                respelledAxiom(b, rnd);
            } else if (rnd.nextInt(3) == 0) {
                // a reference to a record that does not exist yet: the reader rejects the file there
                b.app(b.exprs + 1 + rnd.nextInt(3), any(b, rnd));
            } else {
                b.app(any(b, rnd), any(b, rnd));
            }
        }
        return b.exprs;
    }

    /** {@code { "axiom":{...}}}: a declaration line that does not start the way lean4export writes it. */
    private static void respelledAxiom(Nd b, Random rnd) {
        int name = b.str(0, "ax" + b.names);
        b.raw("{ \"axiom\":{\"isUnsafe\":false,\"levelParams\":[],\"name\":" + name + ",\"type\":" + any(b, rnd) + "}}");
    }

    @Test
    void runsPackedForTheWrongSegmentAreExpanded() {
        long expanded = 0;
        long linked = 0;
        for (int seed = 0; seed < 80; seed++) {
            Random rnd = new Random(seed);
            Nd b = new Nd();
            int x = b.name("x");
            b.sort(0);
            b.bvar(0);
            for (int d = 0; d < 12; d++) {
                int name = b.str(0, "t" + d);
                int type = term(b, rnd, 3, x, name, 0, false);
                term(b, rnd, 10 + rnd.nextInt(40), x, name, 0, false);
                if (d % 2 == 0) {
                    respelledAxiom(b, rnd); // in the middle of the proof's records
                }
                int value = term(b, rnd, 10 + rnd.nextInt(40), x, name, 0, false);
                b.thm(name, type, value);
            }
            byte[] data = b.bytes();
            allWays("respelled declaration " + seed, data);
            LoadOptions o = LoadOptions.batch(data.length);
            LoadReport r = Loaded.load(data, o, packing(o, 2, 1 << 16 < data.length / 2 ? 1 << 16 : 256)).report;
            expanded += r.expandedRuns;
            linked += r.packedRuns - r.expandedRuns;
        }
        assertTrue(expanded > 100, "runs expanded: " + expanded);
        assertTrue(linked > 100, "runs linked: " + linked);
    }

    @Test
    void proofShapedFilesLoadToTheSameStore() {
        long runRecords = 0;
        long records = 0;
        for (int seed = 0; seed < 150; seed++) {
            byte[] data = proofs(seed, 20 + seed % 30, seed % 5 == 0 ? 1500 : 120, false);
            runRecords += allWays("proofs " + seed, data);
            records += Loaded.load(data, LoadOptions.batch(data.length)).report.exprRecords * 4;
        }
        assertTrue(runRecords * 4 > records, runRecords + " of " + records + " records through runs");
    }

    @Test
    void hostileFilesFailOrLoadAlike() {
        int failed = 0;
        int loaded = 0;
        long expanded = 0;
        for (int seed = 0; seed < 300; seed++) {
            byte[] data = proofs(5000 + seed, 12 + seed % 20, 60, true);
            allWays("hostile " + seed, data);
            LoadOptions o = LoadOptions.batch(data.length);
            if (Loaded.load(data, o).failure != null) {
                failed++;
            } else {
                loaded++;
            }
            if (data.length >= 512) {
                expanded += Loaded.load(data, o, packing(o, 2, 256)).report.expandedRuns;
            }
        }
        assertTrue(failed > 30 && loaded > 30, failed + " failed, " + loaded + " loaded");
        assertTrue(expanded > 20, "runs expanded: " + expanded);
    }

    @Test
    void theSegmentLimitIsFoundAtTheSameRecord() {
        Nd b = new Nd();
        int n = b.name("t");
        int s = b.sort(0);
        int last = s;
        for (int i = 0; i < 3000; i++) {
            last = b.app(last, s);
        }
        b.thm(n, s, last);
        byte[] data = b.bytes();
        int failed = 0;
        // (the limit is enforced when the segment buffer grows: beyond its first 1,024 records)
        for (int limit : new int[] {1, 1024, 1025, 1500, 2048, 2049, 3000, 3001, 3002, 100_000}) {
            LoadOptions o = LoadOptions.batch(data.length).withMaxSegmentRecords(limit);
            Loaded plain = Loaded.load(data, o);
            if (plain.failure != null) {
                assertTrue(plain.failure instanceof UnsupportedExportException, String.valueOf(plain.failure));
                failed++;
            }
            compare("limit " + limit, data, plain, o, 2, 256);
            compare("limit " + limit, data, plain, o, 2, 20_000);
        }
        assertTrue(failed >= 4 && failed <= 8, "limits that decline the file: " + failed);
    }

    @Test
    void runsAreNotTakenWhereTheRecordsAreNeededOneByOne() {
        byte[] data = proofs(77, 40, 200, false);
        // without proof blocks a theorem's value is a shared term: no runs are asked for
        LoadOptions noBlocks = LoadOptions.batch(data.length).withProofBlocks(false);
        assertNull(ExportLoader.readerOptions(noBlocks).runs);
        // switched off: scanner threads tokenise only
        LoadOptions off = LoadOptions.batch(data.length).withPackedRuns(false);
        assertNull(ExportLoader.readerOptions(off).runs);
        ExportReader.Options ro = ExportLoader.readerOptions(off);
        ro.threads = 2;
        ro.parallelChunkBytes = 512;
        Loaded plain = Loaded.load(data, LoadOptions.batch(data.length));
        Loaded parallel = Loaded.load(data, off, ro);
        assertEquals(0, parallel.report.packedRuns);
        Loaded.assertIdenticalStore(plain, parallel, "scanner threads without runs");
        // offered to a loader that was told not to take them: refused, same store
        ro.runs = Loader.RUN_FORMAT;
        Loaded refused = Loaded.load(data, off, ro);
        assertEquals(0, refused.report.packedRuns);
        Loaded.assertIdenticalStore(plain, refused, "runs refused");
        // and taken
        LoadOptions on = LoadOptions.batch(data.length);
        Loaded taken = Loaded.load(data, on, packing(on, 2, 512));
        assertTrue(taken.report.packedRunRecords > 1000, "records through runs: " + taken.report.packedRunRecords);
        Loaded.assertIdenticalStore(plain, taken, "runs taken");
    }

    private static void big(String name, int threads, int chunk, double least) throws IOException {
        Path file = BigFilesTest.EXPORTS.resolve(name);
        Assumptions.assumeTrue(Files.isRegularFile(file), name + " not present");
        LoadOptions o = LoadOptions.batch(Files.size(file));
        Loaded plain = Loaded.load(file, o);
        assertNull(plain.failure);
        ExportReader.Options ro = ExportLoader.readerOptions(o);
        ro.threads = threads;
        if (chunk > 0) {
            ro.parallelChunkBytes = chunk;
        }
        Loaded packed = Loaded.load(file, o, ro);
        assertNull(packed.failure, String.valueOf(packed.failure));
        Loaded.assertIdenticalStore(plain, packed, name);
        samePartialStore(name, plain, packed);
        sameReport(name, plain.report, packed.report);
        LoadReport r = packed.report;
        System.out.printf(java.util.Locale.ROOT, "[packed runs] %s, %d threads, chunk %d: %,d of %,d expression records in %,d runs, %,d runs expanded%n",
                name, threads, chunk, r.packedRunRecords, r.exprRecords, r.packedRuns, r.expandedRuns);
        assertTrue(r.packedRunRecords > least * r.exprRecords, name + ": " + r.packedRunRecords + " of " + r.exprRecords);
        assertTrue(r.expandedRuns * 100 < r.packedRuns, name + ": " + r.expandedRuns + " of " + r.packedRuns + " runs expanded");
    }

    @Test
    void initLoadsToTheSameStore() throws IOException {
        big("init.ndjson", 2, 0, 0.6);
        big("init.ndjson", 3, 100_000, 0.5);
    }

    @Test
    void stdLoadsToTheSameStore() throws IOException {
        big("std.ndjson", 2, 0, 0.6);
    }
}
