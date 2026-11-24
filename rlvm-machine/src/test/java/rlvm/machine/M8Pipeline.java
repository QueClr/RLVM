package rlvm.machine;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import rlvm.api.Outcome;

/**
 * The real pipeline in this JVM: {@code rlvm.main.RlvmChecker.checkExport}, which is what the launcher
 * calls for {@code rlvm [--threads N] FILE -X key=value ..} (reader, loader, environment pass, driver,
 * workers, the primary engine found through the {@code ServiceLoader}, the reference kernel as the
 * re-check engine), with the machine classes of the tree under test on the class path. Owner: M8.
 *
 * <p>Why in-process: a mutant campaign is 17,775 small files per switch set; a JVM per file costs about
 * a second of CPU each (20 minutes per set at ten at a time), a call costs milliseconds. What differs
 * from the launcher: the JVM lives on between runs (static state would show; none is known in the
 * pipeline or the machine), the heap is the report's, and several runs share it. {@code report.sh
 * mutants} therefore holds a sample against the real launcher ({@code --launcher-sample N}).
 *
 * <p>The verdict alone proves nothing about the machine: the driver re-checks a rejection and a decline
 * of the primary engine with the reference kernel. The statistics do ({@code -X stats-file}): how many
 * engine checks the machine accepted, rejected and declined, {@code driver.machineIncomplete} (the
 * machine rejected, the reference kernel accepts), {@code driver.warmAnomalies} (rejected in a warm
 * session, accepted in a fresh one), {@code driver.disagreements}.
 */
final class M8Pipeline {
    private M8Pipeline() {}

    /** What one run showed. */
    static final class Result {
        /** {@code Outcome.*} of the run, or -1 if the run itself failed ({@link #error}). */
        int outcome = -1;
        String error = "";
        boolean hasStats;
        long machineAccepted;
        long machineRejected;
        long machineDeclined;
        /** The machine rejected a unit that the reference kernel accepts (MACHINE-INCOMPLETE). */
        long incomplete;
        long warmAnomalies;
        long disagreements;
        long nanos;

        /** Units on which the machine's own answer was not the reference kernel's verdict (a decline is not counted). */
        long machineWrong() {
            return incomplete + warmAnomalies + disagreements;
        }
    }

    private static final AtomicLong SEQ = new AtomicLong();

    // rlvm-main is not a dependency of this module (rlvm-main cannot depend on rlvm-machine either: the engine is
    // found through the ServiceLoader), so the launcher's entry is reached by name; report.sh puts the published
    // rlvm-main and rlvm-cli jars behind the tree's classes on the class path.
    private static final java.lang.reflect.Method CHECK_EXPORT = checkExport();

    private static java.lang.reflect.Method checkExport() {
        try {
            return Class.forName("rlvm.main.RlvmChecker").getMethod("checkExport", Path.class, int.class, boolean.class, long.class, Map.class);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    private static Object checker() throws ReflectiveOperationException {
        return CHECK_EXPORT.getDeclaringClass().getConstructor().newInstance();
    }

    /** True if the launcher's classes are on the class path (they are under {@code report.sh}, not under surefire). */
    static boolean available() {
        return CHECK_EXPORT != null;
    }
    private static Path tmp;
    /** The process's real standard error; {@link #quiet()} replaces {@code System.err}. */
    static PrintStream log = System.err;

    /**
     * {@code checkExport} explains every rejection on {@code System.err}; a campaign over thousands of
     * rejected mutants silences it. Progress of the reports goes to {@link #log}.
     */
    static synchronized void quiet() {
        if (System.err == log) {
            System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        }
    }

    private static synchronized Path tmp() throws IOException {
        if (tmp == null) {
            Path dir = Fixture.root().resolve("work/m8/tmp");
            Files.createDirectories(dir);
            tmp = Files.createTempDirectory(dir, "pipeline-");
            final Path t = tmp;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try (java.util.stream.Stream<Path> s = Files.list(t)) {
                    s.forEach(f -> f.toFile().delete());
                } catch (IOException | RuntimeException e) {
                    // a temporary directory that stays is no harm
                }
                t.toFile().delete();
            }));
        }
        return tmp;
    }

    /** The {@code -X} settings of a machine run: engine, switches, debug modes, session cap (0 = the driver's default). */
    static Map<String, String> machine(String switches, String debug, int sessionMib) {
        Map<String, String> x = new LinkedHashMap<>();
        x.put("engine", "machine");
        if (switches != null && !switches.isEmpty()) {
            x.put("machine.switches", switches);
        }
        if (debug != null && !debug.isEmpty()) {
            x.put("machine.debug", debug);
        }
        if (sessionMib > 0) {
            x.put("session-mib", Integer.toString(sessionMib));
        }
        return x;
    }

    /** The launcher's arguments for the same run, for a reproduction line. */
    static String commandLine(String export, int threads, Map<String, String> extra) {
        StringBuilder sb = new StringBuilder("work/dist/bin/rlvm-jvm --threads ").append(threads).append(' ').append(export);
        for (Map.Entry<String, String> e : extra.entrySet()) {
            sb.append(" -X ").append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** Runs one export (path relative to the project root, or absolute) through the pipeline. Never throws. */
    static Result check(String export, int threads, Map<String, String> extra) {
        Result r = new Result();
        long t0 = System.nanoTime();
        Path stats = null;
        try {
            stats = tmp().resolve("s" + SEQ.incrementAndGet() + ".json");
            Map<String, String> x = new LinkedHashMap<>(extra);
            x.put("stats-file", stats.toString());
            Path file = Fixture.root().resolve(export);
            r.outcome = (Integer) CHECK_EXPORT.invoke(checker(), file, threads, false, 512L << 20, x);
            if (Files.isRegularFile(stats)) {
                String s = new String(Files.readAllBytes(stats), StandardCharsets.UTF_8);
                r.hasStats = true;
                r.machineAccepted = number(s, "engine.primary.machine.accepted");
                r.machineRejected = number(s, "engine.primary.machine.rejected");
                r.machineDeclined = number(s, "engine.primary.machine.declined");
                r.incomplete = number(s, "driver.machineIncomplete");
                r.warmAnomalies = number(s, "driver.warmAnomalies");
                r.disagreements = number(s, "driver.disagreements");
            }
        } catch (Throwable e) { // a usage error, an Error out of the pipeline: the run has no verdict
            Throwable c = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            r.outcome = -1;
            r.error = CHECK_EXPORT == null ? "rlvm.main.RlvmChecker is not on the class path (run through report.sh)" : String.valueOf(c);
        } finally {
            r.nanos = System.nanoTime() - t0;
            if (stats != null) {
                stats.toFile().delete();
            }
        }
        return r;
    }

    private static long number(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : 0;
    }

    static String name(int outcome) {
        return outcome < 0 ? "ERROR" : Outcome.name(outcome);
    }
}
