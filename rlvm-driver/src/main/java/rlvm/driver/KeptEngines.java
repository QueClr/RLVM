package rlvm.driver;

import java.util.ArrayList;
import java.util.List;

import rlvm.api.Engine;
import rlvm.api.EngineConfig;
import rlvm.api.EngineFactory;
import rlvm.api.Session;
import rlvm.api.StatsSink;
import rlvm.api.WorkerContext;
import rlvm.store.Store;

/**
 * An engine factory whose engines outlive the run that asked for them ({@code RunConfig.passesKeepEngines},
 * {@code -X passes-keep=engines}): {@link #create} answers with the engine it made before for the
 * same store and configuration, and an engine's {@code close()} does nothing until
 * {@link #closeAll()}. Every pass of {@code RunConfig.passes} then works with the engine instances
 * of pass 1 (the driver asks for the primary engine, its strict twin and the reference kernel with
 * one configuration each), so what an engine keeps per instance - a code store, compiled blocks,
 * published facts - is warm in the later passes. Sessions are new in every pass.
 *
 * <p>{@code Engine.dumpStats} is called once per engine: in the pass that is announced as the last
 * one ({@link #lastPass()}); the counters it writes are those of all passes together.
 *
 * <p>The idea and the first implementation are the agent "warm"'s (rlvm-main, 2026-11-24, parked for
 * the driver when the pass loop moved here).
 */
final class KeptEngines implements EngineFactory {

    private final EngineFactory inner;
    private final List<Kept> engines = new ArrayList<>();
    private volatile boolean lastPass;

    KeptEngines(EngineFactory inner) {
        this.inner = inner;
    }

    @Override
    public synchronized Engine create(Store store, EngineConfig config) {
        for (Kept e : engines) {
            if (e.store == store && same(e.config, config)) {
                return e;
            }
        }
        Kept e = new Kept(this, inner.create(store, config), store, config);
        engines.add(e);
        return e;
    }

    private static boolean same(EngineConfig a, EngineConfig b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.profile == b.profile && a.strict == b.strict && a.memoCheck == b.memoCheck
                && a.truffleTier == b.truffleTier && a.stats == b.stats && a.workers == b.workers;
    }

    /** The pass that starts now is the last one: its {@code dumpStats} calls reach the engines. */
    void lastPass() {
        lastPass = true;
    }

    /** Number of engine instances made so far (tests). */
    synchronized int count() {
        return engines.size();
    }

    /** Closes the engines for good; a failing close is reported to nobody (the run is over). */
    synchronized void closeAll() {
        for (Kept e : engines) {
            try {
                e.real.close();
            } catch (RuntimeException ex) {
                // nothing depends on it any more
            }
        }
        engines.clear();
    }

    private static final class Kept implements Engine {
        private final KeptEngines owner;
        final Engine real;
        final Store store;
        final EngineConfig config;

        Kept(KeptEngines owner, Engine real, Store store, EngineConfig config) {
            this.owner = owner;
            this.real = real;
            this.store = store;
            this.config = config;
        }

        @Override
        public String name() {
            return real.name();
        }

        @Override
        public Session newSession(WorkerContext ctx) {
            return real.newSession(ctx);
        }

        @Override
        public void environmentAdvanced(int units) {
            real.environmentAdvanced(units);
        }

        @Override
        public void dumpStats(StatsSink sink) {
            if (owner.lastPass) {
                real.dumpStats(sink);
            }
        }

        @Override
        public void close() {
            // The engine outlives the run: KeptEngines.closeAll() closes it after the last pass.
        }
    }
}
