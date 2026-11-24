package rlvm.spike.lc;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

/**
 * Launcher.
 *
 * <pre>
 * lc [--engine.X=Y ...] [--stack-mb=N] &lt;plain|ast|bc&gt; &lt;fib|church|tree&gt; &lt;n&gt; &lt;iterations&gt;
 * </pre>
 *
 * Prints wall time per iteration and, at the end, total process CPU time (all threads: the proxy for
 * the arena's instruction count) and the CPU time of the benchmark thread alone, so that
 * (process - thread) approximates JIT-compiler + GC thread overhead.
 */
public final class Main {
    private Main() {
    }

    static String compileAt;
    static boolean mem;

    static long usedHeap() {
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }

    /**
     * Explicit compilation through the (internal, not API-stable) optimized runtime:
     * com.oracle.truffle.runtime.OptimizedCallTarget#compile(boolean lastTier). Works when Truffle is on
     * the class path (JVM mode); on the module path it needs
     * --add-exports org.graalvm.truffle.runtime/com.oracle.truffle.runtime=ALL-UNNAMED.
     */
    static void compileNow(String name, boolean lastTier) throws Exception {
        Object target = LcLanguage.TARGETS.get(name);
        if (target == null) {
            throw new IllegalArgumentException("no call target named " + name + "; known: " + LcLanguage.TARGETS.keySet());
        }
        Class<?> oct = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
        long t0 = System.nanoTime();
        boolean submitted = (boolean) oct.getMethod("compile", boolean.class).invoke(target, lastTier);
        oct.getMethod("waitForCompilation").invoke(target);
        boolean valid = (boolean) oct.getMethod(lastTier ? "isValidLastTier" : "isValid").invoke(target);
        System.out.printf("explicit compile of %s (lastTier=%b): submitted=%b valid=%b in %d ms%n", name, lastTier, submitted, valid, (System.nanoTime() - t0) / 1_000_000);
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = new LinkedHashMap<>();
        int stackMb = 512;
        int i = 0;
        for (; i < args.length && args[i].startsWith("--"); i++) {
            String a = args[i].substring(2);
            int eq = a.indexOf('=');
            String k = eq < 0 ? a : a.substring(0, eq);
            String v = eq < 0 ? "true" : a.substring(eq + 1);
            if (k.equals("stack-mb")) {
                stackMb = Integer.parseInt(v);
            } else if (k.equals("compile-at")) {
                compileAt = v; // NAME:ITERATION[:1|2]  e.g. fib:3:2
            } else if (k.equals("mem")) {
                mem = true;
            } else {
                options.put(k, v);
            }
        }
        if (args.length - i < 4) {
            System.err.println("usage: lc [--engine.X=Y ...] [--stack-mb=N] <plain|ast|bc> <fib|church|tree> <n> <iterations>");
            System.exit(3);
        }
        String backend = args[i];
        String program = args[i + 1];
        long n = Long.parseLong(args[i + 2]);
        int iterations = Integer.parseInt(args[i + 3]);

        Throwable[] failure = new Throwable[1];
        Thread t = new Thread(null, () -> {
            try {
                run(backend, program, n, iterations, options);
            } catch (Throwable e) {
                failure[0] = e;
            }
        }, "lc-main", (long) stackMb << 20);
        t.start();
        t.join();
        if (failure[0] != null) {
            failure[0].printStackTrace();
            System.exit(3);
        }
    }

    private static void run(String backend, String program, long n, int iterations, Map<String, String> options) {
        String text = Programs.byName(program);
        long start = System.nanoTime();
        if (backend.equals("plain")) {
            PlainInterp.PClosure main = (PlainInterp.PClosure) PlainInterp.run(Parser.parse(text));
            loop(backend, program, n, iterations, start, () -> main.call(n));
        } else {
            String src = ";;backend=" + backend + "\n" + text;
            long h0 = mem ? usedHeap() : 0;
            try (Context ctx = Context.newBuilder(LcLanguage.ID).allowExperimentalOptions(true).options(options).build()) {
                long h1 = mem ? usedHeap() : 0;
                Value main = ctx.eval(Source.newBuilder(LcLanguage.ID, src, program + ".lc").buildLiteral());
                if (mem) {
                    long h2 = usedHeap();
                    main.execute(n);
                    long h3 = usedHeap();
                    for (int k = 0; k < 40; k++) {
                        main.execute(n);
                    }
                    long h4 = usedHeap();
                    System.out.printf("heap: engine+context %d KB | after parse +%d KB | after 1st run +%d KB | after 41 runs +%d KB (%d call targets)%n", (h1 - h0) >> 10, (h2 - h1) >> 10, (h3 - h1) >> 10,
                                    (h4 - h1) >> 10, LcLanguage.TARGETS.size());
                }
                loop(backend, program, n, iterations, start, () -> main.execute(n).as(Object.class));
            }
        }
    }

    private static void loop(String backend, String program, long n, int iterations, long start, java.util.function.Supplier<Object> body) {
        long setup = System.nanoTime() - start;
        long best = Long.MAX_VALUE;
        long total = 0;
        Object result = null;
        StringBuilder times = new StringBuilder();
        for (int it = 0; it < iterations; it++) {
            if (compileAt != null) {
                String[] parts = compileAt.split(":");
                if (it == Integer.parseInt(parts[1])) {
                    try {
                        compileNow(parts[0], parts.length < 3 || parts[2].equals("2"));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
            }
            long t0 = System.nanoTime();
            result = body.get();
            long dt = System.nanoTime() - t0;
            total += dt;
            best = Math.min(best, dt);
            if (it < 12 || it == iterations - 1) {
                times.append(dt / 1_000_000).append(' ');
            } else if (it == 12) {
                times.append("... ");
            }
        }
        long threadCpu = cpuNanos(true);
        long processCpu = cpuNanos(false);
        System.out.printf("%s %s(%d) = %s | setup %d ms | iter ms: %s| best %.1f ms | wall total %d ms | bench-thread cpu %d ms | process cpu %d ms%n",
                        backend, program, n, result, setup / 1_000_000, times, best / 1e6, total / 1_000_000, threadCpu / 1_000_000, processCpu / 1_000_000);
    }

    /** Thread or whole-process CPU time; -1 when unavailable (e.g. native image without JMX). */
    private static long cpuNanos(boolean currentThread) {
        try {
            if (currentThread) {
                return ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
            }
            return ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime();
        } catch (Throwable e) {
            return -1_000_000;
        }
    }
}
