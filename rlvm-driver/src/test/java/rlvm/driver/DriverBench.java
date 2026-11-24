package rlvm.driver;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import rlvm.api.EngineFactory;
import rlvm.api.EngineProvider;
import rlvm.api.Outcome;
import rlvm.ref.RefEngine;

/**
 * Runs exports through the driver with the reference kernel as primary engine (or, with
 * {@code --engine NAME}, any engine whose jar is on the class path), outside JUnit: one
 * line per file (expected verdict from a {@code good}/{@code bad} path component, verdict, deciding
 * unit, wall and CPU time of the run, explanation). For the driver's own integration measurements;
 * the shipped entry point is {@code rlvm-main}.
 *
 * <pre>
 * java -cp ... rlvm.driver.DriverBench [-j N] [--session BYTES] [--force-workers] [--compare]
 *                                      [--pipelined [--publish-units N]] [--set KEY=VALUE[,...]]
 *                                      [--engine NAME [--engine-opt KEY=VALUE]...]
 *                                      [--stats FILE] [--quiet] FILE_OR_DIR...
 * --engine:    the primary engine by the name of its rlvm.api.EngineProvider (found on the class path
 *              through java.util.ServiceLoader, as rlvm-main does), e.g. machine with the published
 *              rlvm-machine jar; the reference kernel stays the re-check engine and runs the
 *              environment pass. --engine-opt KEY=VALUE are the engine's -X settings, e.g.
 *              machine.switches=warm,gated,expect. Default: reference (primary == reference)
 * --compare:   every file in batch mode with 1, 2, 4, 8 workers and with a 1-byte session cap, and
 *              pipelined (a publication after every unit, after every 7 and every 64 units) with 1,
 *              4 and 8 workers; reports every verdict or deciding unit that differs from the first run.
 *              Counted apart ("pipelined declines"), not a difference: a pipelined run that DECLINES
 *              what the batch run decides (less complete, never another verdict)
 * --pipelined: the loader publishes while it reads and the workers check meanwhile, whatever the size
 *              of the file (RunConfig.pipelineMinBytes = 0)
 * --set:       driver settings by name (RunConfig.setAll)
 * --files-from F: one export per line of F (the invariance suite's shards)
 * --mem-trace F [--gc-every S]: every second one line "seconds accountedMB sharedMB heapUsedMB" to F;
 *              with --gc-every S a full collection every S seconds and a line "... live" right after
 *              it, i.e. what is reachable (the diagnosis of the peak resident set; costs time)
 * </pre>
 */
public final class DriverBench {
    private DriverBench() {}

    public static void main(String[] args) throws Exception {
        int workers = 1;
        long session = 0;
        boolean force = false;
        boolean compare = false;
        boolean quiet = false;
        boolean pipelined = false;
        int publishUnits = 0;
        String settings = null;
        Path stats = null;
        Path memTrace = null;
        int gcEvery = 0;
        String engine = null;
        java.util.Map<String, String> engineOptions = new java.util.LinkedHashMap<>();
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-j":
                    workers = Integer.parseInt(args[++i]);
                    break;
                case "--session":
                    session = Long.parseLong(args[++i]);
                    break;
                case "--force-workers":
                    force = true;
                    break;
                case "--compare":
                    compare = true;
                    break;
                case "--quiet":
                    quiet = true;
                    break;
                case "--pipelined":
                    pipelined = true;
                    break;
                case "--publish-units":
                    publishUnits = Integer.parseInt(args[++i]);
                    break;
                case "--set":
                    settings = args[++i];
                    break;
                case "--mem-trace":
                    memTrace = Path.of(args[++i]);
                    break;
                case "--engine":
                    engine = args[++i];
                    break;
                case "--engine-opt": {
                    String kv = args[++i];
                    int eq = kv.indexOf('=');
                    engineOptions.put(eq < 0 ? kv : kv.substring(0, eq), eq < 0 ? "true" : kv.substring(eq + 1));
                    break;
                }
                case "--gc-every":
                    gcEvery = Integer.parseInt(args[++i]);
                    break;
                case "--files-from":
                    for (String line : Files.readAllLines(Path.of(args[++i]), StandardCharsets.UTF_8)) {
                        if (!line.isBlank()) {
                            files.add(Path.of(line.trim()));
                        }
                    }
                    break;
                case "--stats":
                    stats = Path.of(args[++i]);
                    break;
                default:
                    Path p = Path.of(args[i]);
                    if (Files.isDirectory(p)) {
                        try (Stream<Path> s = Files.walk(p)) {
                            List<Path> found = new ArrayList<>();
                            s.filter(f -> f.toString().endsWith(".ndjson")).forEach(found::add);
                            Collections.sort(found);
                            files.addAll(found);
                        }
                    } else {
                        files.add(p);
                    }
            }
        }
        if (engine != null && !engine.equals("reference")) {
            primaryFactory = factoryOf(engine, engineOptions);
        }
        int correct = 0;
        int wrong = 0;
        int declined = 0;
        int unknown = 0;
        int different = 0;
        int failClosed = 0;
        long cpu0 = processCpuMillis();
        long wall0 = System.nanoTime();
        if (memTrace != null) {
            startMemTrace(memTrace, gcEvery);
        }
        for (Path f : files) {
            int expected = expected(f);
            long c0 = processCpuMillis();
            long t0 = System.nanoTime();
            int[] unit = new int[1];
            String[] why = new String[1];
            int verdict = run(f, workers, session, force, stats, unit, why, pipelined ? Math.max(publishUnits, 0) : -1,
                    settings);
            long wallMs = (System.nanoTime() - t0) / 1_000_000;
            long cpuMs = processCpuMillis() - c0;
            String mark;
            if (expected < 0) {
                unknown++;
                mark = "?";
            } else if (verdict == expected) {
                correct++;
                mark = "ok";
            } else if (verdict == Outcome.DECLINE) {
                declined++;
                mark = "DECLINED";
            } else {
                wrong++;
                mark = "WRONG";
            }
            if (!quiet || !mark.equals("ok")) {
                System.out.printf("%-8s %-7s unit %-7d wall %7d ms  cpu %7d ms  %s%s%n", mark, Outcome.name(verdict), unit[0],
                        wallMs, cpuMs, f, why[0] == null ? "" : "  -- " + why[0]);
            }
            if (compare) {
                int[] ws = {2, 4, 8, 1, 4, 1, 4, 8};
                long[] caps = {0, 0, 0, 1, 1, 0, 0, 0};
                int[] publish = {-1, -1, -1, -1, -1, 1, 7, 64};
                for (int i = 0; i < ws.length; i++) {
                    int[] u2 = new int[1];
                    String[] w2 = new String[1];
                    int v2 = run(f, ws[i], caps[i], true, null, u2, w2, publish[i], settings);
                    if (publish[i] > 0 && v2 == Outcome.DECLINE && verdict != Outcome.DECLINE) {
                        // Less complete, not different: a pipelined run may decline what the batch run
                        // decides (a file that is not in declaration order: rlvm-admit admits in
                        // dependency order only from a unit it meets when the file is complete).
                        failClosed++;
                        if (failClosed <= 5) {
                            System.out.printf("PIPELINED-DECLINE %s: -j %d, a publication every %d units: %s, base %s@%d%n", f,
                                    ws[i], publish[i], w2[0], Outcome.name(verdict), unit[0]);
                        }
                        continue;
                    }
                    if (v2 != verdict || u2[0] != unit[0]) {
                        different++;
                        System.out.printf("DIFFERENT %s: -j %d%s%s gives %s@%d (%s), base %s@%d%n", f, ws[i],
                                caps[i] > 0 ? " session cap " + caps[i] : "",
                                publish[i] > 0 ? " pipelined, a publication every " + publish[i] + " units" : "",
                                Outcome.name(v2), u2[0], w2[0], Outcome.name(verdict), unit[0]);
                    }
                }
            }
        }
        System.out.printf("%d files: %d correct, %d wrong, %d declined, %d without expectation%s; wall %d ms, cpu %d ms, peak RSS %d MB%n",
                files.size(), correct, wrong, declined, unknown,
                compare ? ", " + different + " schedule differences, " + failClosed + " pipelined declines" : "",
                (System.nanoTime() - wall0) / 1_000_000, processCpuMillis() - cpu0, peakRssKb() / 1024);
        System.out.flush();
        if (wrong > 0 || different > 0) {
            throw new AssertionError(wrong + " wrong verdicts, " + different + " schedule differences");
        }
    }

    static int run(Path file, int workers, long session, boolean force, Path stats, int[] unit, String[] why) {
        return run(file, workers, session, force, stats, unit, why, -1, null);
    }

    /**
     * @param publishUnits -1: batch load; 0: pipelined with the default hand-off interval; N: pipelined
     *     with a publication after every N units
     */
    static int run(Path file, int workers, long session, boolean force, Path stats, int[] unit, String[] why,
            int publishUnits, String settings) {
        RunConfig c = new RunConfig();
        c.input = file;
        c.workers = workers;
        c.explain = false;
        c.logAnomalies = false;
        c.statsFile = stats;
        if (session > 0) {
            c.sessionBytes = session;
        }
        if (settings != null) {
            c.setAll(settings);
        }
        if (publishUnits >= 0) {
            c.loadMode = rlvm.load.LoadOptions.Mode.PIPELINED;
            c.pipelineMinBytes = 0;
            if (publishUnits > 0) {
                c.publishEveryUnits = publishUnits;
            }
        }
        Run run = new Run(c, new TestWiring(file, publishUnits >= 0), primaryFactory, RefEngine.FACTORY);
        current = run;
        run.setDiagnostics(null);
        if (force) {
            run.setMinUnitsPerWorker(1);
        }
        int verdict = run.execute();
        unit[0] = run.verdicts().decidingUnit();
        why[0] = run.verdicts().explanation();
        if (run.verdicts().warmAnomalies() > 0) {
            why[0] = "WARM-ANOMALY x" + run.verdicts().warmAnomalies() + " " + why[0];
        }
        if (run.verdicts().machineIncomplete() > 0) {
            why[0] = "MACHINE-INCOMPLETE x" + run.verdicts().machineIncomplete() + " " + why[0];
        }
        return verdict;
    }

    /** The primary engine of every run: the reference kernel itself unless {@code --engine} names another. */
    private static EngineFactory primaryFactory = RefEngine.FACTORY;

    /** The engine called {@code name} among the providers on the class path, configured by its {@code -X} settings. */
    static EngineFactory factoryOf(String name, java.util.Map<String, String> options) {
        StringBuilder known = new StringBuilder();
        for (EngineProvider p : java.util.ServiceLoader.load(EngineProvider.class)) {
            if (p.name().equals(name)) {
                for (String key : options.keySet()) {
                    if (!p.optionKeys().contains(key)) {
                        throw new IllegalArgumentException("engine '" + name + "' has no setting '" + key + "' (known: "
                                + p.optionKeys() + ")");
                    }
                }
                return p.factory(java.util.Collections.unmodifiableMap(options));
            }
            known.append(known.length() == 0 ? "" : ", ").append(p.name());
        }
        throw new IllegalArgumentException("no engine '" + name + "' on the class path (found: " + known + ")");
    }

    /** The run in progress, for the memory trace. */
    private static volatile Run current;

    private static void startMemTrace(final Path file, final int gcEvery) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                final long mib = 1L << 20;
                long t0 = System.nanoTime();
                try (java.io.PrintWriter w = new java.io.PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
                    w.println("# seconds accountedMB sharedMB sessionsMB heapUsedMB rssMB [live]");
                    for (int tick = 1;; tick++) {
                        Thread.sleep(1000);
                        Run r = current;
                        MemoryAccount a = r == null ? null : r.account();
                        long accounted = a == null ? 0 : a.accountedBytes();
                        long shared = 0;
                        try {
                            shared = r == null || r.host() == null ? 0 : r.host().sharedBytes();
                        } catch (RuntimeException e) {
                            shared = 0;
                        }
                        boolean gc = gcEvery > 0 && tick % gcEvery == 0;
                        if (gc) {
                            System.gc();
                        }
                        Runtime rt = Runtime.getRuntime();
                        long used = rt.totalMemory() - rt.freeMemory();
                        w.printf("%.1f %d %d %d %d %d%s%n", (System.nanoTime() - t0) / 1e9, accounted / mib, shared / mib,
                                (accounted - shared) / mib, used / mib, rssKb() / 1024, gc ? " live" : "");
                        w.flush();
                    }
                } catch (Exception e) {
                    // the trace ends with the process
                }
            }
        }, "mem-trace");
        t.setDaemon(true);
        t.start();
    }

    static long rssKb() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"), StandardCharsets.ISO_8859_1)) {
                if (line.startsWith("VmRSS:")) {
                    return Long.parseLong(line.replaceAll("[^0-9]", ""));
                }
            }
        } catch (Exception e) {
            // not Linux
        }
        return -1;
    }

    private static int expected(Path file) {
        for (Path part : file.toAbsolutePath()) {
            String s = part.toString();
            if (s.equals("good")) {
                return Outcome.ACCEPT;
            }
            if (s.equals("bad")) {
                return Outcome.REJECT;
            }
        }
        return -1;
    }

    /** User + system CPU time of the process in milliseconds, from /proc/self/stat. */
    static long processCpuMillis() {
        try {
            String stat = Files.readString(Path.of("/proc/self/stat"), StandardCharsets.ISO_8859_1);
            String[] f = stat.substring(stat.lastIndexOf(')') + 2).trim().split(" +");
            return (Long.parseLong(f[11]) + Long.parseLong(f[12])) * 10;
        } catch (Exception e) {
            return -1;
        }
    }

    static long peakRssKb() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"), StandardCharsets.ISO_8859_1)) {
                if (line.startsWith("VmHWM:")) {
                    return Long.parseLong(line.replaceAll("[^0-9]", ""));
                }
            }
        } catch (Exception e) {
            // not Linux
        }
        return -1;
    }
}
