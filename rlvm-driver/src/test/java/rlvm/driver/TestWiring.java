package rlvm.driver;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Supplier;

import rlvm.api.RunSignals;
import rlvm.api.WorkerContext;
import rlvm.api.bind.Bindings;
import rlvm.export.ExportReader;
import rlvm.level.LevelStore;
import rlvm.load.ExportLoader;
import rlvm.load.LevelChain;
import rlvm.load.LevelStoreInterner;
import rlvm.load.LoadOptions;
import rlvm.load.LoadReport;
import rlvm.load.Loader;
import rlvm.load.NatStoreDecoder;
import rlvm.store.Store;

/**
 * A {@link Wiring} for the integration tests, equivalent to {@code rlvm.main.MainWiring}: the real
 * reader and loader over the real store, worker contexts from {@code rlvm.api.bind.Bindings}.
 *
 * <p>Batch: the loader interns levels in the run's base level store, which is frozen after the load.
 * Pipelined ({@code new TestWiring(file, true)}): the loader interns through an
 * {@code rlvm.load.LevelChain}, which freezes a snapshot at every publication; the store sits on the
 * chain's list and level views, worker contexts are overlays of the newest snapshot
 * ({@code Bindings.bindLevelSnapshots}), and the driver's {@code LoadOptions} (with its listener)
 * go to the loader unchanged.
 */
final class TestWiring implements Wiring {

    private final Path input;
    private final boolean pipelined;
    private final Bindings bindings = new Bindings();
    private LevelChain chain;
    private LoadReport report;
    int contexts;

    TestWiring(Path input) {
        this(input, false);
    }

    TestWiring(Path input, boolean pipelined) {
        this.input = input;
        this.pipelined = pipelined;
    }

    @Override
    public Store newStore(long inputBytes) {
        if (!pipelined) {
            Store store = new Store(inputBytes, bindings.baseLists(), bindings.baseLevelInfo());
            bindings.bindNames(store.names());
            return store;
        }
        final LevelChain c = new LevelChain(bindings.levelBase());
        chain = c;
        Store store = new Store(inputBytes, c.lists(), c.info());
        bindings.bindNames(store.names());
        bindings.bindLevelSnapshots(new Supplier<LevelStore>() {
            @Override
            public LevelStore get() {
                LevelChain.Snapshot s = c.current();
                return s == null ? null : s.levels;
            }
        });
        return store;
    }

    @Override
    public void load(Store store, LoadOptions options) throws IOException {
        if (pipelined) {
            // The options as the driver made them: mode, hand-off interval, listener. (A batch load
            // into this store works too: one snapshot at the end.)
            read(new Loader(store, options, chain, new NatStoreDecoder()));
            return;
        }
        // Like rlvm-main's wiring (requests.log 09:15Z): a load that ends with an unsupported record
        // first publishes the units before it, and the level base is frozen on that path too, so that
        // the driver checks those units (a rejection among them is the verdict, as the official
        // checker says; otherwise the run declines).
        Loader loader = new Loader(store, options.withPublishOnDecline(true),
                new LevelStoreInterner(bindings.levelBase(), false), new NatStoreDecoder());
        try {
            read(loader);
        } catch (rlvm.load.UnsupportedExportException e) {
            bindings.freeze();
            throw e;
        }
        bindings.freeze();
    }

    private void read(Loader loader) throws IOException {
        ExportReader.Options ro = new ExportReader.Options();
        ro.threads = 1;
        try {
            report = ExportLoader.read(input, loader, ro);
        } catch (RuntimeException | IOException | Error e) {
            try {
                report = loader.report();
            } catch (RuntimeException ignored) {
                report = null;
            }
            throw e;
        }
    }

    @Override
    public LoadReport loadReport() {
        return report;
    }

    @Override
    public synchronized WorkerContext newWorkerContext(int workerId, Store store, RunSignals signals,
            long overlayMaxBytes) {
        contexts++;
        return bindings.newWorkerContext(workerId, store, signals, overlayMaxBytes);
    }
}
