package rlvm.main;

import java.io.IOException;
import java.nio.file.Path;

import rlvm.api.RunSignals;
import rlvm.api.WorkerContext;
import rlvm.api.bind.Bindings;
import rlvm.driver.Wiring;
import rlvm.export.ExportReader;
import rlvm.load.ExportLoader;
import rlvm.load.LevelChain;
import rlvm.load.LevelStoreInterner;
import rlvm.load.LoadOptions;
import rlvm.load.LoadReport;
import rlvm.load.Loader;
import rlvm.load.NatStoreDecoder;
import rlvm.load.UnsupportedExportException;
import rlvm.store.Store;

/**
 * Everything the driver cannot construct itself because it sees neither the reader nor the level and
 * number modules: the store with the run's list store, the load (reader into loader into store) and
 * the per-worker contexts. One instance per run.
 *
 * <p>Load, batch mode: the calling thread reads the whole file with {@code rlvm.load.ExportLoader}
 * ({@code rlvm.export.ExportReader} feeding a {@code rlvm.load.Loader}, then {@code finish()}); the
 * loader interns levels and lists in the run's base level store through its own
 * {@code LevelStoreInterner}; when the load has returned, the base is frozen ({@link Bindings#freeze()})
 * and worker contexts can be created. Failures arrive as the loader's exceptions:
 * {@code MalformedExportException} (the official reader rejects too: REJECT),
 * {@code UnsupportedExportException} (DECLINE); an {@link IOException} passes through (DECLINE).
 *
 * <p>Load, pipelined mode ({@code -X load=pipelined}): the store is created on the loader's
 * {@code rlvm.load.LevelChain} (its list store and level view follow the chain), the loader interns
 * through the chain and publishes units while it reads; every publication freezes a snapshot of
 * the levels and lists made so far, and worker contexts are overlays of the newest snapshot
 * ({@link Bindings#bindLevelSnapshots}), re-based at {@code WorkerContext.reset()}. The base is never
 * frozen by this class then. Whether units really are published before the end of the file is the
 * choice of whoever builds the {@code LoadOptions} (the driver: mode, hand-off interval, listener);
 * a batch load into such a store works too (one snapshot, at the end).
 */
final class MainWiring implements Wiring {

    private final Path input;
    private final MainOptions options;
    private final Bindings bindings;
    /** The level store of a run whose store was created for pipelined loading; null in a batch run. */
    private LevelChain chain;
    private LoadReport report;

    MainWiring(Path input, MainOptions options) {
        this.input = input;
        this.options = options;
        this.bindings = new Bindings();
    }

    Bindings bindings() {
        return bindings;
    }

    @Override
    public Store newStore(long inputBytes) {
        if (options.loadMode == LoadOptions.Mode.PIPELINED) {
            // The store's own writer interns lists: it sits on the chain's views, which serve the
            // loader thread from the open level store and every other thread from the newest snapshot.
            final LevelChain c = new LevelChain(bindings.levelBase());
            chain = c;
            Store store = new Store(inputBytes, c.lists(), c.info());
            bindings.bindNames(store.names());
            bindings.bindLevelSnapshots(() -> {
                LevelChain.Snapshot s = c.current();
                return s == null ? null : s.levels;
            });
            return store;
        }
        Store store = new Store(inputBytes, bindings.baseLists(), bindings.baseLevelInfo());
        bindings.bindNames(store.names());
        return store;
    }

    /** True if {@link #newStore} created the store for pipelined loading (level snapshots). */
    boolean hasLevelSnapshots() {
        return chain != null;
    }

    @Override
    public void load(Store store, LoadOptions loadOptions) throws IOException {
        final LevelChain c = chain;
        if (c == null && loadOptions.mode != LoadOptions.Mode.BATCH) {
            // The store sits on the base level store, which workers cannot read before it is frozen.
            throw new UnsupportedOperationException(
                    "a pipelined load needs a store created for it: run with -X load=pipelined");
        }
        // A load-level decline is per unit (request of rlvm-load): when the loader cannot represent a
        // record it first publishes the units that were loaded completely, so that the driver still
        // checks them - a rejection among them is the verdict, as the official checker says; the
        // store stays incomplete, so the run can never be accepted. Always on in pipelined mode.
        if (!loadOptions.publishOnDecline) {
            loadOptions = loadOptions.withPublishOnDecline(true);
        }
        Loader loader;
        if (c != null) {
            // Snapshots: the chain freezes what was created so far at every publication of the loader
            // (once, at the end, if the options ask for a batch load).
            loader = new Loader(store, loadOptions, c, new NatStoreDecoder());
        } else {
            // The level store is frozen by this class after the load, not by the loader's publish():
            // the number base is frozen with it and worker views are created from the frozen pair.
            loader = new Loader(store, loadOptions, new LevelStoreInterner(bindings.levelBase(), false),
                    new NatStoreDecoder());
        }
        ExportReader.Options ro = ExportLoader.readerOptions(loadOptions);
        ro.io = options.io;
        // -X scan-threads=K: scanner threads of the reader (they only run on mapped input, -X io=mapped
        // or auto; on buffered input the reader stays on the calling thread).
        ro.threads = Math.max(1, Math.max(ro.threads, options.scanThreads));
        try {
            report = ExportLoader.read(input, loader, ro);
        } catch (RuntimeException | IOException | Error e) {
            try {
                report = loader.report(); // counters up to the failing record
            } catch (RuntimeException ignored) {
                report = null;
            }
            if (c == null && e instanceof UnsupportedExportException) {
                // The units published before the decline are checked: worker contexts need the frozen
                // base. (If the freeze fails no context can be made and the run declines anyway.)
                try {
                    bindings.freeze();
                } catch (RuntimeException f) {
                    e.addSuppressed(f);
                }
            }
            throw e;
        }
        if (c == null) {
            bindings.freeze();
        }
    }

    @Override
    public LoadReport loadReport() {
        return report;
    }

    @Override
    public WorkerContext newWorkerContext(int workerId, Store store, RunSignals signals, long overlayMaxBytes) {
        return bindings.newWorkerContext(workerId, store, signals, overlayMaxBytes);
    }
}
