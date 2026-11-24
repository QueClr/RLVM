package rlvm.truffle;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import rlvm.admit.Admission;
import rlvm.admit.Policy;
import rlvm.api.Budget;
import rlvm.api.Outcome;
import rlvm.api.RunSignals;
import rlvm.api.Session;
import rlvm.api.WorkerContext;

/**
 * A measuring tool for the warm steady state (OWNER-NOTES 12) on both platforms, until the driver offers
 * {@code -X passes=N}: one process, one engine, several passes over one export. Pass 1 is the cold pass
 * (sequential, with the environment pass); every later pass checks all units again with FRESH sessions of
 * the SAME engine on W worker threads that claim chunks of 16 units from a shared counter (the code store,
 * the tier's installed blocks, the host JIT's code and the grown tables of the process stay warm). Not the
 * driver: no admission pipeline, no re-check, no pipelined loading - only the engine's checking work.
 *
 * <pre>
 * java -cp 'lib/*:rlvm-truffle/target/test-classes' rlvm.truffle.WarmMain FILE SWITCHES PASSES THREADS
 * </pre>
 * Per pass: wall clock, process CPU time (all threads: workers, compiler, GC), units accepted.
 */
public final class WarmMain {
    private WarmMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("usage: WarmMain FILE SWITCHES PASSES THREADS");
            System.exit(64);
        }
        String file = args[0];
        String switches = args[1].equals("-") ? "" : args[1];
        int passes = Integer.parseInt(args[2]);
        int threads = Integer.parseInt(args[3]);
        long t0 = System.nanoTime();
        Harness h = new Harness(file, switches);
        System.out.println(String.format(Locale.ROOT, "[warm] %s switches '%s' threads %d: loaded in %.0f ms", file, switches, threads, (System.nanoTime() - t0) / 1e6));
        long c0 = Cpu.processNanos();
        long w0 = System.nanoTime();
        Harness.Run first = h.run();
        long w1 = System.nanoTime();
        long c1 = Cpu.processNanos();
        System.out.println(String.format(Locale.ROOT, "[warm] pass 1 (cold, 1 thread, environment pass): wall %.0f ms, process cpu %.0f ms, accepted %d of %d%s", (w1 - w0) / 1e6,
                (c1 - c0) / 1e6, first.accepted, first.units, first.firstFailure.isEmpty() ? "" : " | " + first.firstFailure));
        Harness.settle(h.t2());
        double bestWall = Double.MAX_VALUE;
        double bestCpu = Double.MAX_VALUE;
        for (int p = 2; p <= passes; p++) {
            final int base = p * 64;
            final AtomicInteger next = new AtomicInteger();
            final AtomicInteger accepted = new AtomicInteger();
            final AtomicLong workerNanos = new AtomicLong();
            final int units = h.store.loadedUnits();
            Thread[] ts = new Thread[threads];
            c0 = Cpu.processNanos();
            w0 = System.nanoTime();
            for (int i = 0; i < threads; i++) {
                final int id = base + i;
                ts[i] = new Thread(null, () -> {
                    final long workerStart = Cpu.threadNanos();
                    WorkerContext c = h.bindings.newWorkerContext(id, h.store, RunSignals.NONE, 1L << 30);
                    Session s = h.engine.newSession(c);
                    Admission admission = new Admission(c, Policy.arena(), null);
                    Budget budget = new Budget(h.maxSteps, 2L << 30, Integer.MAX_VALUE);
                    int ok = 0;
                    for (;;) {
                        int from = next.getAndAdd(16);
                        if (from >= units) {
                            break;
                        }
                        int to = Math.min(units, from + 16);
                        for (int u = from; u < to; u++) {
                            int o;
                            try {
                                o = admission.admit(u, s, budget);
                            } catch (RuntimeException e) {
                                o = Outcome.DECLINE;
                            }
                            if (o == Outcome.ACCEPT) {
                                ok++;
                            } else {
                                s.reset();
                                c.reset();
                            }
                            if (s.footprintBytes() > h.sessionBytes) {
                                s.reset();
                                c.reset();
                            }
                        }
                    }
                    workerNanos.addAndGet(Cpu.threadNanos() - workerStart); // this thread only: no compiler, no promoter
                    accepted.addAndGet(ok);
                    s.close();
                }, "warm-worker-" + i, 64L << 20);
                ts[i].start();
            }
            for (Thread t : ts) {
                t.join();
            }
            w1 = System.nanoTime();
            c1 = Cpu.processNanos();
            double wall = (w1 - w0) / 1e6;
            double cpu = (c1 - c0) / 1e6;
            bestWall = Math.min(bestWall, wall);
            bestCpu = Math.min(bestCpu, cpu);
            System.out.println(String.format(Locale.ROOT, "[warm] pass %d (warm, %d threads): wall %.0f ms, process cpu %.0f ms, accepted %d of %d, worker cpu %.0f ms", p, threads, wall, cpu,
                    accepted.get(), units, workerNanos.get() / 1e6));
            Harness.settle(h.t2());
        }
        T2Engine e = h.t2();
        System.out.println(String.format(Locale.ROOT, "[warm] RESULT %s | switches '%s' | threads %d | cold wall %.0f ms | best warm wall %.0f ms | best warm cpu %.0f ms | accepted %d%s", file,
                switches, threads, (double) first.wallNanos / 1e6, bestWall, bestCpu, first.accepted,
                e == null ? "" : String.format(Locale.ROOT, " | t2: installed %d, failed %d, compile cpu %.0f ms, compiled activations %d, deopts %d", e.installedCount(),
                        e.failedCount(), e.compileNanos() / 1e6, e.compiledCalls(), e.deopts())));
        if (e != null && Boolean.getBoolean("rlvm.t2.aheadCheck")) {
            long[] c = e.aheadChecks();
            System.out.println(String.format(Locale.ROOT, "[warm] run-ahead: predicted records right %d, wrong %d (%.1f%% right); of the wrong ones %d were records that existed already, %d closures / suspensions", c[0], c[1],
                    c[0] + c[1] == 0 ? 0.0 : 100.0 * c[0] / (c[0] + c[1]), c[2], c[3]));
        }
        if (e != null && System.getProperty("warm.report") != null) {
            System.out.print(e.report());
        }
        if (e != null && Integer.getInteger("warm.listing", 0) > 0) {
            // the listings of the blocks with the most entries (diagnosis: what the hot blocks consist of)
            int n = e.blockCount();
            Integer[] ids = new Integer[n];
            for (int b = 1; b <= n; b++) {
                ids[b - 1] = b;
            }
            java.util.Arrays.sort(ids, (x, y) -> Long.compare(e.entriesOf(y), e.entriesOf(x)));
            for (int i = 0; i < n && i < Integer.getInteger("warm.listing", 0); i++) {
                System.out.println("[warm] LISTING entries " + e.entriesOf(ids[i]) + " | " + e.describe(ids[i]));
                System.out.println(rlvm.machine.T2Bridge.listing(h.m, ids[i]));
            }
        }
        System.exit(0);
    }
}
