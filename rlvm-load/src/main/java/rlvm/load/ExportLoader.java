package rlvm.load;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import rlvm.export.ExportException;
import rlvm.export.ExportReader;
import rlvm.store.Store;
import rlvm.store.StoreLimitException;

/**
 * Reads an export file into a store: {@code rlvm.export.ExportReader} feeding a {@link Loader}, on the
 * calling thread, followed by {@link Loader#finish()}.
 *
 * <p>Failures: {@link MalformedExportException} (the official checker rejects the input: reject),
 * {@link UnsupportedExportException} (outside what RLVM represents: decline; this includes the
 * reader's {@code UNSUPPORTED} failures and the store's {@link StoreLimitException}),
 * {@link IOException}. After a failure {@link Loader#report()} still gives the counters up to the
 * failing record. A caller that drives {@code ExportReader.read(file, loader)} itself has to make the
 * same translations ({@link #translate}).
 */
public final class ExportLoader {
    private ExportLoader() {}

    /** Batch load of {@code file} into {@code store} with the reader's default options. */
    public static LoadReport load(Path file, Store store, LevelInterner levels, NatDecoder nats) throws IOException {
        Loader loader = new Loader(store, LoadOptions.batch(Files.size(file)), levels, nats);
        return read(file, loader, new ExportReader.Options());
    }

    /** Feeds {@code file} to {@code loader} with the reader options its {@link LoadOptions} ask for. */
    public static LoadReport read(Path file, Loader loader) throws IOException {
        return read(file, loader, readerOptions(loader.options()));
    }

    /**
     * The reader options a load with {@code options} wants: {@link LoadOptions#scanThreads} scanner
     * threads, the loader's packed form for the runs they prepare ({@link LoadOptions#packedRuns}),
     * everything else at the reader's defaults. A caller with reader options of its own sets
     * {@code threads} and {@code runs} the same way.
     */
    public static ExportReader.Options readerOptions(LoadOptions options) {
        ExportReader.Options ro = new ExportReader.Options();
        ro.threads = Math.max(1, options.scanThreads);
        // The scanner threads (if the caller gives the reader any) also pack the runs of proof-private
        // records in the loader's block format; a loader that cannot take them refuses each offer.
        ro.runs = options.packedRuns && options.proofBlocks ? Loader.RUN_FORMAT : null;
        return ro;
    }

    /** Feeds {@code file} to {@code loader}, finishes it and returns its report. */
    public static LoadReport read(Path file, Loader loader, ExportReader.Options readerOptions) throws IOException {
        long t0 = System.nanoTime();
        ExportReader.Result res;
        try {
            res = ExportReader.read(file, loader, forLoader(readerOptions, loader));
        } catch (ExportException e) {
            throw failed(loader, translate(e), t0);
        } catch (StoreLimitException e) {
            throw failed(loader, translate(e), t0);
        } catch (UnsupportedExportException e) {
            throw failed(loader, e, t0);
        }
        return done(loader, res, t0);
    }

    /** Feeds an export held in memory to {@code loader}, finishes it and returns its report. */
    public static LoadReport read(byte[] data, Loader loader) {
        return read(data, loader, readerOptions(loader.options()));
    }

    public static LoadReport read(byte[] data, Loader loader, ExportReader.Options readerOptions) {
        long t0 = System.nanoTime();
        ExportReader.Result res;
        try {
            res = ExportReader.read(data, loader, forLoader(readerOptions, loader));
        } catch (ExportException e) {
            throw failed(loader, translate(e), t0);
        } catch (StoreLimitException e) {
            throw failed(loader, translate(e), t0);
        } catch (UnsupportedExportException e) {
            throw failed(loader, e, t0);
        }
        return done(loader, res, t0);
    }

    /**
     * {@code o} for a read into {@code loader}: if packed runs are asked for ({@code o.runs != null}),
     * they are prepared in the loader's own format, with its table of finished records for the scanner
     * threads ({@link Loader#runFormat()}), or not at all when the loader takes none. The caller's
     * options object is not modified.
     */
    private static ExportReader.Options forLoader(ExportReader.Options o, Loader loader) {
        if (o.runs == null) {
            return o;
        }
        ExportReader.Options c = new ExportReader.Options();
        c.io = o.io;
        c.threads = o.threads;
        c.bufferBytes = o.bufferBytes;
        c.parallelChunkBytes = o.parallelChunkBytes;
        c.unloadMapped = o.unloadMapped;
        c.generalPathOnly = o.generalPathOnly;
        c.runs = loader.runFormat();
        return c;
    }

    private static LoadReport done(Loader loader, ExportReader.Result res, long t0) {
        try {
            loader.finish();
        } catch (StoreLimitException e) {
            throw failed(loader, translate(e), t0);
        } catch (UnsupportedExportException e) {
            throw failed(loader, e, t0);
        }
        LoadReport r = loader.report();
        r.inputBytes = res.bytes;
        r.lines = res.lines;
        r.generalPathLines = res.generalPathLines;
        r.sparseIds = !(res.namesDense && res.levelsDense && res.exprsDense);
        r.readerIdTables = !res.tableFree;
        r.nonCanonicalLines = res.generalPathLines > 0;
        r.scanThreads = res.threads;
        r.wallNanos = System.nanoTime() - t0;
        return r;
    }

    /**
     * The load failed with {@code failure}. A decline first publishes the units that were completely
     * loaded, if the options ask for it ({@link Loader#publishCompleted()}); a rejection publishes
     * nothing (the official reader fails on the whole file before it checks any declaration).
     */
    private static RuntimeException failed(Loader loader, RuntimeException failure, long t0) {
        if (failure instanceof UnsupportedExportException) {
            try {
                loader.publishCompleted();
            } catch (RuntimeException e) {
                failure.addSuppressed(e); // nothing more is published; the decline stands
            }
        }
        loader.report().wallNanos = System.nanoTime() - t0;
        return failure;
    }

    /** A representation limit of the store: the input is declined. */
    public static UnsupportedExportException translate(StoreLimitException e) {
        return new UnsupportedExportException("store limit: " + e.getMessage());
    }

    /** The reader's failure as the loader's exception of the same verdict. */
    public static RuntimeException translate(ExportException e) {
        if (e.kind() == ExportException.Kind.MALFORMED) {
            return new MalformedExportException(e.reason(), e.line());
        }
        return new UnsupportedExportException(e.line() > 0 ? e.reason() + " (line " + e.line() + ")" : e.reason());
    }
}
