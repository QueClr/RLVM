package rlvm.machine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * Test support of M7: a loaded export with the environment pass run to the end (inductive facts and
 * recursor rules through {@code rlvm-admit} with the reference kernel) and NO declaration checked. The
 * K1 elaborator is a function of the store alone (ISA 3.4), so its tests need nothing else; one or more
 * machine sessions over the same engine can be created for the concurrency tests.
 */
final class M7World {
    Store store;
    Bindings bindings;
    WorkerContext ctx;
    MachineEngine engine;
    MachineSession session;
    Machine m;
    int envUnits;
    private int nextWorker = 2;

    /** Loads {@code path} (relative to the project root) and runs the environment pass over every unit. */
    static M7World load(String path) throws IOException {
        M7World w = new M7World();
        Path file = Fixture.root().resolve(path);
        w.bindings = new Bindings(256);
        Store st = new Store(Files.size(file), w.bindings.baseLists(), w.bindings.baseLevelInfo());
        w.bindings.bindNames(st.names());
        try {
            ExportLoader.load(file, st, new LevelStoreInterner(w.bindings.levelBase(), false), new NatStoreDecoder());
        } catch (Exception e) {
            throw new IOException("loader: " + e, e);
        }
        w.bindings.freeze();
        w.store = st;
        WorkerContext refCtx = w.bindings.newWorkerContext(1, st, RunSignals.NONE, 1L << 30);
        Engine refEngine = RefEngine.FACTORY.create(st, EngineConfig.defaults());
        Session refSession = refEngine.newSession(refCtx);
        EnvBuilder env = new EnvBuilder(st, refSession.ops(), Policy.arena(), null);
        env.run();
        w.envUnits = st.envUnits();
        refSession.close();
        refEngine.close();
        w.ctx = w.bindings.newWorkerContext(0, st, RunSignals.NONE, 1L << 30);
        w.engine = new MachineEngine(st, EngineConfig.defaults());
        w.session = (MachineSession) w.engine.newSession(w.ctx);
        w.m = w.session.m;
        w.m.beginDecl(Integer.MAX_VALUE, -1, Op.FUEL_UNLIMITED, Long.MAX_VALUE);
        return w;
    }

    /** Another session (its own worker context and machine) over the same engine. */
    Machine newMachine() {
        WorkerContext c = bindings.newWorkerContext(nextWorker++, store, RunSignals.NONE, 1L << 30);
        MachineSession s = (MachineSession) engine.newSession(c);
        s.m.beginDecl(Integer.MAX_VALUE, -1, Op.FUEL_UNLIMITED, Long.MAX_VALUE);
        return s.m;
    }

    /** Constant id of a dotted name; 0 if there is none. */
    int cid(String name) {
        int id = 0;
        for (String part : name.split("\\.")) {
            byte[] b = part.getBytes(StandardCharsets.UTF_8);
            id = store.names().findStr(id, b, 0, b.length);
            if (id == 0) {
                return 0;
            }
        }
        int c = store.names().constOf(id);
        return c <= 0 ? 0 : c;
    }

    String nameOf(int cid) {
        return store.names().toString(store.decls().name(cid));
    }

    String term(int t) {
        return Show.term(ctx.terms, store, ctx.levels, ctx.lists, ctx.lits, t);
    }
}
