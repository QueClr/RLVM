package rlvm.truffle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import rlvm.admit.Admission;
import rlvm.admit.EnvBuilder;
import rlvm.admit.Policy;
import rlvm.api.Budget;
import rlvm.api.Engine;
import rlvm.api.EngineConfig;
import rlvm.api.Outcome;
import rlvm.api.RunSignals;
import rlvm.api.Session;
import rlvm.api.WorkerContext;
import rlvm.api.bind.Bindings;
import rlvm.load.ExportLoader;
import rlvm.load.LevelStoreInterner;
import rlvm.load.NatStoreDecoder;
import rlvm.machine.Machine;
import rlvm.machine.MachineProvider;
import rlvm.machine.T2Bridge;
import rlvm.ref.RefEngine;
import rlvm.store.Store;

/**
 * Test harness: one export loaded with the real loader, the environment pass with the reference kernel,
 * and ONE machine session over every unit in file order (as {@code rlvm-machine}'s {@code Fixture.load} /
 * {@code checkAll}), with the machine configured through the public provider
 * ({@code -X machine.switches=...}), so that the tier is found the way the launcher finds it: the switch
 * {@code t2} and the service {@code rlvm.machine.Tier$Factory}.
 *
 * <p>After every unit the harness takes a digest of everything the session has interned
 * ({@code T2Bridge.digest}: every record word including the memo words, every run word, the fill
 * pointers). Two runs with equal digests at every unit made the same value ids with the same contents.
 */
final class Harness implements AutoCloseable {

    /** The outcome of a run over a file. */
    static final class Run {
        int units;
        int[] outcome;
        long[] digest;
        long[] identity;
        long[] ticks;
        int accepted;
        int rejected;
        int declined;
        long records;
        long runWords;
        long frames;
        long entered;
        long cpuNanos;
        long wallNanos;
        String firstFailure = "";
    }

    final String path;
    final Bindings bindings;
    final Store store;
    final WorkerContext ctx;
    final WorkerContext refCtx;
    final Engine refEngine;
    final Session refSession;
    final EnvBuilder env;
    final Engine engine;
    final Session session;
    final Machine m;
    long maxSteps = 2_000_000_000L;
    long sessionBytes = 16L << 20;

    static Path root() {
        String r = System.getProperty("rlvm.root");
        return (r != null ? Path.of(r) : Path.of("..")).toAbsolutePath().normalize();
    }

    static boolean exists(String path) {
        return Files.isRegularFile(root().resolve(path));
    }

    /** Loads {@code path} (relative to the project root) and creates a machine with the given staging switches. */
    Harness(String path, String switches) throws IOException {
        this(path, switches, -1);
    }

    Harness(String path, String switches, int hostDepth) throws IOException {
        this.path = path;
        Path file = root().resolve(path);
        bindings = new Bindings(256);
        Store st = new Store(Files.size(file), bindings.baseLists(), bindings.baseLevelInfo());
        bindings.bindNames(st.names());
        ExportLoader.load(file, st, new LevelStoreInterner(bindings.levelBase(), false), new NatStoreDecoder());
        bindings.freeze();
        store = st;
        ctx = bindings.newWorkerContext(0, st, RunSignals.NONE, 1L << 30);
        refCtx = bindings.newWorkerContext(1, st, RunSignals.NONE, Math.min(6L << 30, Runtime.getRuntime().maxMemory() / 10 * 6));
        refEngine = RefEngine.FACTORY.create(st, EngineConfig.defaults());
        refSession = refEngine.newSession(refCtx);
        env = new EnvBuilder(st, refSession.ops(), Policy.arena(), null);
        Map<String, String> options = new HashMap<>();
        options.put("machine.switches", switches);
        if (hostDepth >= 0) {
            options.put("machine.hostDepth", Integer.toString(hostDepth));
        }
        engine = new MachineProvider().factory(options).create(st, EngineConfig.defaults());
        session = engine.newSession(ctx);
        m = T2Bridge.machineOf(session);
    }

    private int nextContext = 2;

    /** The machine over every unit in file order (first pass: with the environment pass). */
    Run run() {
        return pass(session, ctx, m, env, true);
    }

    /**
     * Another pass over every unit with a FRESH session of the SAME engine (its code store and the tier's
     * installed blocks are kept: the warm pass of OWNER-NOTES 12). {@code digests}: take the heap digests.
     */
    Run again(boolean digests) {
        WorkerContext c = bindings.newWorkerContext(nextContext++, store, RunSignals.NONE, 1L << 30);
        Session s = engine.newSession(c);
        try {
            return pass(s, c, T2Bridge.machineOf(s), null, digests);
        } finally {
            s.close();
        }
    }

    private Run pass(Session session, WorkerContext ctx, Machine m, EnvBuilder env, boolean digests) {
        Run r = new Run();
        int units = store.loadedUnits();
        r.outcome = new int[units];
        r.digest = new long[units];
        r.identity = new long[units];
        r.ticks = new long[units];
        Admission admission = new Admission(ctx, Policy.arena(), env);
        Budget budget = new Budget(maxSteps, 2L << 30, Integer.MAX_VALUE);
        long t0 = Cpu.threadNanos();
        long w0 = System.nanoTime();
        for (int u = 0; u < units; u++) {
            int o;
            try {
                o = admission.admit(u, session, budget);
            } catch (RuntimeException e) {
                o = Outcome.DECLINE;
                r.firstFailure = "unit " + u + ": exception " + e;
            }
            r.outcome[u] = o;
            if (digests) {
                r.digest[u] = T2Bridge.digest(m);
                r.identity[u] = T2Bridge.identityDigest(m);
            }
            r.ticks[u] = T2Bridge.ticks(m);
            r.units++;
            if (o == Outcome.ACCEPT) {
                r.accepted++;
            } else {
                if (o == Outcome.REJECT) {
                    r.rejected++;
                } else {
                    r.declined++;
                }
                if (r.firstFailure.isEmpty()) {
                    r.firstFailure = "unit " + u + ": " + Outcome.name(o) + " " + admission.lastReason() + " " + admission.lastMessage();
                }
                session.reset();
                ctx.reset();
                continue;
            }
            if (session.footprintBytes() > sessionBytes) {
                session.reset();
                ctx.reset();
            }
        }
        r.cpuNanos = Cpu.threadNanos() - t0;
        r.wallNanos = System.nanoTime() - w0;
        r.records = T2Bridge.recordsMade(m);
        r.runWords = T2Bridge.runWordsMade(m);
        r.frames = T2Bridge.framesWritten(m);
        r.entered = T2Bridge.entered(m);
        return r;
    }

    /** The T2 engine of this harness's machine engine, or null if the machine runs without the tier. */
    T2Engine t2() {
        rlvm.machine.Tier t = rlvm.machine.Tier.of(m);
        return t instanceof T2Tier ? ((T2Tier) t).engine : null;
    }

    @Override
    public void close() {
        session.close();
        engine.close();
        refSession.close();
        refEngine.close();
    }

    /** The first unit at which two runs differ (outcome, value heap, ticks), or -1. */
    static int firstDifference(Run a, Run b) {
        int n = Math.min(a.units, b.units);
        for (int u = 0; u < n; u++) {
            if (a.outcome[u] != b.outcome[u] || a.digest[u] != b.digest[u] || a.ticks[u] != b.ticks[u]) {
                return u;
            }
        }
        return a.units == b.units ? -1 : n;
    }

    static String describe(Run a, Run b, int u) {
        if (u < 0) {
            return "identical";
        }
        if (u >= a.units || u >= b.units) {
            return "unit counts differ: " + a.units + " / " + b.units;
        }
        return "unit " + u + ": outcome " + Outcome.name(a.outcome[u]) + " / " + Outcome.name(b.outcome[u]) + ", heap digest "
                + (a.digest[u] == b.digest[u] ? "equal" : "DIFFERENT") + ", identity digest "
                + (a.identity[u] == b.identity[u] ? "equal" : "DIFFERENT") + ", ticks " + a.ticks[u] + " / " + b.ticks[u]
                + " | " + a.firstFailure + " | " + b.firstFailure;
    }
}
