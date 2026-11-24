package rlvm.admit;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Path;

import rlvm.api.Engine;
import rlvm.api.EngineConfig;
import rlvm.api.Session;
import rlvm.ref.RefEngine;

/**
 * Development aid: thread CPU time of the whole environment pass over an export (real loader,
 * rlvm-inductive, reference kernel), the file loaded afresh for every repetition.
 *
 * <pre>
 * java -Xss512m -Xmx20g -XX:+UseSerialGC [-Drlvm.admit.bench.step=N] [-Drlvm.admit.bench.cap=BYTES]
 *      -cp $(cat work/rlvm-admit/cp.txt) rlvm.admit.EnvPassBench FILE [REPETITIONS]
 * </pre>
 */
public final class EnvPassBench {
    private EnvPassBench() {}

    /** Units per {@code advanceTo} call (the driver's lazily driven pass: 1) and the session cap in bytes. */
    private static final int STEP = Integer.getInteger("rlvm.admit.bench.step", 4096);
    private static final long CAP = Long.getLong("rlvm.admit.bench.cap", 16L << 20);

    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]);
        int reps = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        for (int rep = 0; rep < reps; rep++) {
            ArenaFiles.Loaded l = new ArenaFiles.Loaded(file);
            int units = l.store.loadedUnits();
            try (Engine engine = RefEngine.FACTORY.create(l.store, EngineConfig.defaults());
                    Session session = engine.newSession(l.ctx)) {
                EnvBuilder env = new EnvBuilder(l.store, session.ops(), Policy.arena(), null);
                long c0 = mx.getCurrentThreadCpuTime();
                long t0 = System.nanoTime();
                while (env.envUnits() < units && env.failedUnit() < 0) {
                    env.advanceTo(Math.min(units, env.envUnits() + STEP));
                    if (session.footprintBytes() + l.ctx.footprintBytes() > CAP) {
                        session.reset();
                        l.ctx.reset();
                    }
                }
                long c1 = mx.getCurrentThreadCpuTime();
                long t1 = System.nanoTime();
                System.out.println(file.getFileName() + " rep " + rep + ": environment pass " + (c1 - c0) / 1_000_000
                        + " ms thread CPU, " + (t1 - t0) / 1_000_000 + " ms wall, " + units + " units, frontier "
                        + env.envUnits() + (env.failedUnit() >= 0 ? " FAILED at " + env.failedUnit() : ""));
            }
        }
    }
}
