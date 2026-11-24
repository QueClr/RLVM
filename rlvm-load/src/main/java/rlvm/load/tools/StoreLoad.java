package rlvm.load.tools;

import java.util.Locale;

import rlvm.level.LevelStore;
import rlvm.load.LevelChain;
import rlvm.load.LevelStoreInterner;
import rlvm.load.LevelStoreViews;
import rlvm.load.LoadListener;
import rlvm.load.LoadOptions;
import rlvm.load.Loader;
import rlvm.load.NameTableOracle;
import rlvm.load.NatStoreDecoder;
import rlvm.store.DeclTable;
import rlvm.store.LitPool;
import rlvm.store.Overlay;
import rlvm.store.Store;
import rlvm.store.Terms;

/**
 * Wiring of the real store for {@link LoadTool}: a base level store, the store over it, the loader.
 * Batch: a plain level store, frozen at the end. Pipelined: a {@link LevelChain}, and optionally
 * follower threads that do what a worker must do to keep up with the frontier.
 */
final class StoreLoad {
    private StoreLoad() {}

    static LoadTool.Loaded create(long inputBytes, LoadOptions options, int followers) {
        NameOracleHolder order = new NameOracleHolder();
        LevelStore levels = new LevelStore(order);
        boolean pipelined = options.mode == LoadOptions.Mode.PIPELINED;
        LevelChain chain = pipelined ? new LevelChain(levels) : null;
        Store store = pipelined ? new Store(inputBytes, chain.lists(), chain.info())
                : new Store(inputBytes, LevelStoreViews.lists(levels), LevelStoreViews.info(levels));
        order.target = new NameTableOracle(store.names());
        Signal signal = followers > 0 ? new Signal() : null;
        LoadOptions o = signal != null ? options.withListener(signal) : options;
        Loader loader = new Loader(store, o, pipelined ? chain : new LevelStoreInterner(levels), new NatStoreDecoder());
        Follower[] fs = new Follower[followers];
        Thread[] ts = new Thread[followers];
        for (int i = 0; i < followers; i++) {
            fs[i] = new Follower(store, chain, signal);
            ts[i] = new Thread(null, fs[i], "load-follower-" + i, 16L << 20);
            ts[i].setDaemon(true);
            ts[i].start();
        }
        return new LoadTool.Loaded(loader) {
            @Override
            void close() {
                if (signal != null) {
                    signal.end();
                    for (Thread t : ts) {
                        try {
                            t.join();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }

            @Override
            String summary() {
                LevelStore last = chain != null && chain.current() != null ? chain.current().levels : levels;
                StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                        "  store: %,d units, %,d constants, %,d names, %,d export nodes, %,d blocks (%,d records, %,d promoted), "
                                + "%,d levels, %,d lists; %.1f MB shared structures (terms %.1f, names %.1f, blocks %.1f, decls %.1f, lits %.1f)",
                        store.decls().unitCount(), store.decls().constantCount(), store.names().size(),
                        store.terms().exportEnd() - 1, store.blocks().count(), store.blocks().recordCount(),
                        store.blocks().promotedCount(), last.size() - 1, last.listCount(), store.sharedBytes() / 1e6,
                        store.terms().bytes() / 1e6, store.names().bytes() / 1e6, store.blocks().bytes() / 1e6,
                        store.decls().bytes() / 1e6, store.lits().bytes() / 1e6));
                if (chain != null) {
                    sb.append(String.format(Locale.ROOT, "%n  level chain: %d snapshot stores, %.1f ms freezing and copying",
                            chain.generations(), chain.snapshotNanos() / 1e6));
                }
                for (int i = 0; i < fs.length; i++) {
                    Follower f = fs[i];
                    sb.append(String.format(Locale.ROOT,
                            "%n  follower %d: %,d units seen in %,d wake-ups, %,d level views built in %.1f ms%s", i, f.seen,
                            f.wakeUps, f.rebinds, f.rebindNanos / 1e6, f.failure == null ? "" : ", FAILED: " + f.failure));
                }
                return sb.toString();
            }
        };
    }

    /** The hand-off signal: followers park on it. */
    private static final class Signal implements LoadListener {
        private int units;
        private boolean ended;

        @Override
        public synchronized void published(int loadedUnits, boolean complete) {
            units = loadedUnits;
            notifyAll();
        }

        synchronized void end() {
            ended = true;
            notifyAll();
        }

        /** Waits for more than {@code have} units; true once the load has ended. */
        synchronized boolean await(int have) throws InterruptedException {
            while (units <= have && !ended) {
                wait();
            }
            return ended;
        }
    }

    /**
     * What keeping up with the frontier costs a worker, without any checking: wake up at a hand-off,
     * read the frontier, take a private overlay of the newest level snapshot (and a term overlay over
     * it) when the frontier has passed the view, read the rows of the new units.
     */
    private static final class Follower implements Runnable {
        private final Store store;
        private final LevelChain chain;
        private final Signal signal;
        int seen;
        int wakeUps;
        int rebinds;
        long rebindNanos;
        long checksum;
        Throwable failure;

        Follower(Store store, LevelChain chain, Signal signal) {
            this.store = store;
            this.chain = chain;
            this.signal = signal;
        }

        @Override
        public void run() {
            try {
                DeclTable decls = store.decls();
                Terms terms = null;
                LevelStore base = null;
                int covered = 0;
                for (;;) {
                    int n = store.loadedUnits();
                    if (n <= seen) {
                        if (signal.await(seen) && store.loadedUnits() <= seen) {
                            return;
                        }
                        continue;
                    }
                    wakeUps++;
                    if (n > covered) {
                        LevelChain.Snapshot s = chain.current();
                        if (s.levels != base) {
                            // new levels or lists since the view was built: a new private overlay
                            long t0 = System.nanoTime();
                            base = s.levels;
                            LevelStore mine = base.newOverlay();
                            Overlay overlay = new Overlay(store, LevelStoreViews.lists(mine), LevelStoreViews.info(mine),
                                    new LitPool(store.lits()), 64L << 20);
                            terms = new Terms(store.terms(), overlay);
                            rebinds++;
                            rebindNanos += System.nanoTime() - t0;
                        }
                        covered = s.units;
                    }
                    terms.refresh();
                    for (int u = seen; u < n; u++) {
                        int first = decls.unitFirstCid(u);
                        if (decls.unitSize(u) > 0) {
                            checksum += terms.header(decls.type(first));
                        }
                    }
                    seen = n;
                }
            } catch (Throwable t) {
                failure = t;
            }
        }
    }

    /** The level store is created before the name table exists; the order is bound right after. */
    private static final class NameOracleHolder implements rlvm.level.NameOracle {
        rlvm.level.NameOracle target;

        @Override
        public int compareNames(int nameIdA, int nameIdB) {
            return target.compareNames(nameIdA, nameIdB);
        }

        @Override
        public void appendName(StringBuilder sb, int nameId) {
            target.appendName(sb, nameId);
        }
    }
}
