package rlvm.machine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import rlvm.admit.EnvBuilder;
import rlvm.admit.Policy;
import rlvm.api.Engine;
import rlvm.api.EngineConfig;
import rlvm.api.RunSignals;
import rlvm.api.Session;
import rlvm.api.WorkerContext;
import rlvm.api.bind.Bindings;
import rlvm.load.ExportLoader;
import rlvm.load.LevelStoreInterner;
import rlvm.load.NatStoreDecoder;
import rlvm.ref.RefEngine;
import rlvm.store.Store;

/**
 * A loaded export whose environment pass has run to the end (inductive blocks and quotients admitted by the
 * reference kernel), with as many worker contexts and machine sessions on ONE {@link MachineEngine} as a test
 * asks for. For the tests of the engine-wide fact table (ISA 3.5), which need several sessions and every
 * constant of a real file. The shared {@link Fixture} gives one session only.
 */
final class M5Env implements AutoCloseable {
    final Store store;
    private final Bindings bindings;
    private final Engine refEngine;
    private final Session refSession;
    private int nextWorker = 2;

    private M5Env(Store store, Bindings bindings, Engine refEngine, Session refSession) {
        this.store = store;
        this.bindings = bindings;
        this.refEngine = refEngine;
        this.refSession = refSession;
    }

    /** Loads {@code path} (relative to the project root) and runs the environment pass over every unit. */
    static M5Env load(String path) throws IOException {
        Path file = Fixture.root().resolve(path);
        Bindings bindings = new Bindings(256);
        Store st = new Store(Files.size(file), bindings.baseLists(), bindings.baseLevelInfo());
        bindings.bindNames(st.names());
        try {
            ExportLoader.load(file, st, new LevelStoreInterner(bindings.levelBase(), false), new NatStoreDecoder());
        } catch (Exception e) {
            throw new IOException("loader: " + e, e);
        }
        bindings.freeze();
        WorkerContext refCtx = bindings.newWorkerContext(1, st, RunSignals.NONE, 1L << 30);
        Engine ref = RefEngine.FACTORY.create(st, EngineConfig.defaults());
        Session refSession = ref.newSession(refCtx);
        EnvBuilder env = new EnvBuilder(st, refSession.ops(), Policy.arena(), null);
        env.run();
        if (env.failedUnit() >= 0) {
            throw new IOException("environment pass failed at unit " + env.failedUnit() + ": " + env.failureMessage());
        }
        return new M5Env(st, bindings, ref, refSession);
    }

    /** A new machine engine over the store, with the given staging switches for all its sessions. */
    MachineEngine engine() {
        return new MachineEngine(store, EngineConfig.defaults());
    }

    /** A new session of {@code engine} with a worker context of its own; everything is visible to it. */
    MachineSession session(MachineEngine engine, String switches) {
        WorkerContext ctx;
        synchronized (this) {
            ctx = bindings.newWorkerContext(nextWorker++, store, RunSignals.NONE, 1L << 30);
        }
        MachineSession s = (MachineSession) engine.newSession(ctx);
        s.m.sw = Debug.parseSwitches(switches);
        s.m.beginDecl(Integer.MAX_VALUE, -1, Op.FUEL_UNLIMITED, Long.MAX_VALUE);
        return s;
    }

    /** Forgets what the session and its context built (between units of a long run) and makes everything visible again. */
    static void recycle(MachineSession s) {
        s.m.abandon();
        s.m.reset();
        s.ctx.reset();
        s.m.beginDecl(Integer.MAX_VALUE, -1, Op.FUEL_UNLIMITED, Long.MAX_VALUE);
    }

    @Override
    public void close() {
        refSession.close();
        refEngine.close();
    }
}
