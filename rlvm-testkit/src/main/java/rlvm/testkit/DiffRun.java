package rlvm.testkit;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * {@code testkit diff}: the differential campaign. Runs the real launcher ({@code work/dist/bin/rlvm-jvm
 * -X engine=diff}: reader, loader, environment pass, driver, workers) over exports, with the test
 * kit's {@link DiffEngine} as the primary engine, and collects what the engines disagree on.
 *
 * <pre>
 * testkit diff [options] EXPORT.ndjson | DIRECTORY | CORPUS ...  [-- extra rlvm arguments]
 *                    (CORPUS = a name in work/exports or work/workloads/slices: init, std, cedar, cedar-hot-ax, ...)
 *
 *   --engine NAME        engine under test: a registered EngineProvider (machine, reference, broken).
 *                        Default: the best non-reference engine on the class path, else the reference
 *                        engine against itself
 *   --cp PATH            class path entries for engines that are not in the distribution, e.g.
 *                        rlvm-machine/target/classes[:rlvm-machine/target/test-classes]
 *   --threads N          worker threads of each run (default 1)
 *   -j N                 exports checked at the same time (default 1)
 *   --sample N           KernelOps sampler on about one accepted unit in N (0 = off, the default)
 *   --sample-count K     sub-terms per sampled unit (default 4)
 *   --sample-unit U      sample only unit U (what a sampler difference's "reproduce" line uses)
 *   --seed S             seed of the sampler (default 1)
 *   --ops CLASS          rlvm.testkit.OpsFactory for the KernelOps of the engine under test
 *   --policy P           decline (default) | follow-oracle
 *   --broken FAULTS      faults of the engine "broken" (see BrokenEngine; default all)
 *   --only GLOB          of a directory: only the exports whose relative path matches
 *   --launcher FILE      default work/dist/bin/rlvm-jvm
 *   --out DIR            reports and logs (default work/diff-reports/TIMESTAMP)
 *   --timeout S          per export (default 3600)
 *   --max-print N        differences printed per export and kind (default 25; all are in the report)
 *   --fail-on-decline    also fail when the engine under test declined a unit (the gate of an
 *                        engine that must decide everything itself: the driver's fallback to the
 *                        reference kernel hides such declines from the exit status)
 *   --expect-differences self-test of the harness: succeed only if a difference IS found
 * </pre>
 * The exit status of each run is the reference kernel's verdict (the driver confirms every rejection
 * and re-checks every decline with it), so the result of a differential run is the report, not the
 * status: every difference in verdict is printed with the export, the unit, the constant, both
 * outcomes and a command that reproduces it.
 *
 * <p>Declines are counted per side and by reason, and the first ones are listed (unit, constant,
 * reason, message): a unit the engine under test declined was not compared.
 *
 * <p>Exit status: 0 no difference; 1 at least one difference (verdict or sampler), or a decline of
 * the engine under test with {@code --fail-on-decline}; 3 no difference
 * but a run failed (launcher missing, status other than 0 / 1 / 2, timeout) or compared nothing;
 * 2 usage. With {@code --expect-differences}: 0 if a difference was found, 1 if not.
 */
final class DiffRun {

    private DiffRun() {}

    /** What one export's run gave. */
    static final class Result {
        final Path export;
        int exit = -1;
        boolean timedOut;
        double seconds;
        Path report;
        Path log;
        /** Units both engines were asked about by the warm engine (the strict engine's re-checks excluded). */
        long checks;
        long agreeAccept;
        long agreeReject;
        long declinedUnderTest;
        long declinedOracle;
        long sampledUnits;
        long sampleProblems;
        long sampleJudgments;
        long sampleUndecided;
        long sampleOracleErrors;
        String sampleUnavailable;
        String underTest;
        String oracle;
        boolean summarised;
        /** Records of kind SOUNDNESS / COMPLETENESS, one per (kind, unit). */
        final List<Json> verdictDifferences = new ArrayList<>();
        /** Records of kind SAMPLE_*. */
        final List<Json> sampleDifferences = new ArrayList<>();
        /** Declines of the engine under test in warm sessions, by reason. */
        final java.util.Map<String, Long> declineReasons = new java.util.TreeMap<>();
        /** The first DECLINED records of the engine under test. */
        final List<Json> declines = new ArrayList<>();
        /** The first DECLINED records of the oracle. */
        final List<Json> oracleDeclines = new ArrayList<>();
        String error;

        Result(Path export) {
            this.export = export;
        }

        String verdict() {
            if (timedOut) {
                return "timeout";
            }
            switch (exit) {
                case 0:
                    return "accept";
                case 1:
                    return "reject";
                case 2:
                    return "decline";
                default:
                    return "error(" + exit + ")";
            }
        }

        boolean failed() {
            return timedOut || exit < 0 || exit > 2 || error != null;
        }
    }

    static int command(Main.Args a, PrintStream out, PrintStream err) throws IOException {
        Path root = Main.root();
        if (a.positional.isEmpty()) {
            throw new Main.UsageException("diff needs at least one export or directory");
        }
        String engine = a.get("--engine", null);
        int threads = a.getInt("--threads", 1);
        int jobs = Math.max(1, a.getInt("-j", 1));
        long timeout = a.getLong("--timeout", 3600);
        String only = a.get("--only", null);
        Path launcher = Paths.get(a.get("--launcher", root.resolve("work/dist/bin/rlvm-jvm").toString())).toAbsolutePath();
        if (!Files.isExecutable(launcher)) {
            err.println("testkit: " + launcher + " is not built (scripts/dev.sh dist); the differential run needs the JVM launcher");
            return 3;
        }
        String stamp = Instant.now().toString().replace(":", "").replace("-", "");
        Path outDir = Paths.get(a.get("--out", root.resolve("work/diff-reports").resolve(stamp).toString())).toAbsolutePath();
        Files.createDirectories(outDir);

        // ---- the exports
        List<Path> exports = new ArrayList<>();
        for (String p : a.positional) {
            Path path = Paths.get(p).toAbsolutePath().normalize();
            if (Files.isDirectory(path)) {
                try (Stream<Path> walk = Files.walk(path)) {
                    List<Path> found = new ArrayList<>();
                    walk.filter(f -> f.toString().endsWith(".ndjson") && Files.isRegularFile(f))
                            .filter(f -> Main.glob(only, path.relativize(f).toString())).forEach(found::add);
                    Collections.sort(found);
                    exports.addAll(found);
                }
            } else if (Files.isRegularFile(path)) {
                exports.add(path);
            } else if (Files.isRegularFile(root.resolve("work/exports").resolve(p + ".ndjson"))) {
                exports.add(root.resolve("work/exports").resolve(p + ".ndjson")); // a corpus by name: init, std, cedar, mathlib, ...
            } else if (Files.isRegularFile(root.resolve("work/workloads/slices").resolve(p + ".ndjson"))) {
                exports.add(root.resolve("work/workloads/slices").resolve(p + ".ndjson")); // cedar-hot-ax, ...
            } else {
                throw new Main.UsageException("no such export, directory or corpus: " + p);
            }
        }
        if (exports.isEmpty()) {
            err.println("testkit: no export to check");
            return 3;
        }

        // ---- the class path of the plug-ins, and the settings of the engine (environment: no quoting trouble)
        StringBuilder cp = new StringBuilder(root.resolve("rlvm-testkit/spi").toString());
        if ("broken".equals(engine)) {
            cp.append(':').append(root.resolve("rlvm-testkit/spi-broken"));
        }
        cp.append(':').append(ownCode());
        String extraCp = a.get("--cp", null);
        if (extraCp != null) {
            for (String e : extraCp.split(":")) {
                if (!e.isEmpty()) {
                    cp.append(':').append(Paths.get(e).toAbsolutePath().normalize());
                }
            }
        }
        StringBuilder repro = new StringBuilder("rlvm-testkit/bin/testkit diff");
        if (engine != null) {
            repro.append(" --engine ").append(engine);
        }
        if (extraCp != null) {
            repro.append(" --cp ").append(extraCp);
        }
        for (String key : new String[] {"--ops", "--broken", "--policy", "--launcher"}) {
            if (a.get(key, null) != null) {
                repro.append(' ').append(key).append(' ').append(a.get(key, null));
            }
        }
        repro.append(" --threads 1");
        List<String> rlvmArgs = a.rest;

        out.printf("differential run: %d export(s), engine under test %s, %d worker thread(s) each, %d at a time%s%n",
                exports.size(), engine == null ? "(default)" : engine, threads, jobs,
                a.getInt("--sample", 0) > 0 || a.get("--sample-unit", null) != null ? ", KernelOps sampler on" : "");
        out.println("reports and logs: " + rel(root, outDir));

        // ---- run
        List<Result> results = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(jobs);
        try {
            List<Future<Result>> futures = new ArrayList<>();
            int index = 0;
            for (Path export : exports) {
                final int n = index++;
                futures.add(pool.submit(() -> {
                    Result r = new Result(export);
                    r.report = outDir.resolve(String.format("%04d.jsonl", n));
                    r.log = outDir.resolve(String.format("%04d.log", n));
                    Files.deleteIfExists(r.report);
                    List<String> cmd = new ArrayList<>();
                    cmd.add(launcher.toString());
                    cmd.add("-X");
                    cmd.add("engine=diff");
                    cmd.add("--threads");
                    cmd.add(Integer.toString(threads));
                    cmd.addAll(rlvmArgs);
                    cmd.add(export.toString());
                    ProcessBuilder pb = new ProcessBuilder(cmd).directory(root.toFile()).redirectErrorStream(true)
                            .redirectOutput(r.log.toFile());
                    Map<String, String> env = pb.environment();
                    env.put("RLVM_EXTRA_CP", cp.toString());
                    env.put("RLVM_DIFF_REPORT", r.report.toString());
                    env.put("RLVM_DIFF_LABEL", rel(root, export));
                    env.put("RLVM_DIFF_REPRO", repro.toString());
                    put(env, "RLVM_DIFF_ENGINE", engine);
                    put(env, "RLVM_DIFF_POLICY", a.get("--policy", null));
                    put(env, "RLVM_DIFF_SAMPLE", a.get("--sample", null));
                    put(env, "RLVM_DIFF_SAMPLE_COUNT", a.get("--sample-count", null));
                    put(env, "RLVM_DIFF_SAMPLE_UNIT", a.get("--sample-unit", null));
                    put(env, "RLVM_DIFF_SEED", a.get("--seed", null));
                    put(env, "RLVM_DIFF_OPS", a.get("--ops", null));
                    put(env, "RLVM_BROKEN", a.get("--broken", null));
                    env.remove("RLVM_STATUS_BASE");
                    long t0 = System.nanoTime();
                    try {
                        Process p = pb.start();
                        if (!p.waitFor(timeout, TimeUnit.SECONDS)) {
                            p.destroyForcibly();
                            p.waitFor(30, TimeUnit.SECONDS);
                            r.timedOut = true;
                        } else {
                            r.exit = p.exitValue();
                        }
                    } catch (IOException e) {
                        r.error = e.toString();
                    }
                    r.seconds = (System.nanoTime() - t0) / 1e9;
                    read(r);
                    return r;
                }));
            }
            for (Future<Result> f : futures) {
                try {
                    Result r = f.get();
                    results.add(r);
                    line(root, r, out, a.getInt("--max-print", 25));
                } catch (java.util.concurrent.ExecutionException e) {
                    throw new IOException(e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted");
                }
            }
        } finally {
            pool.shutdownNow();
        }

        // ---- summary
        long checks = 0;
        long agreeAccept = 0;
        long agreeReject = 0;
        long declA = 0;
        long declB = 0;
        long soundness = 0;
        long completeness = 0;
        long warmOnly = 0;
        long samples = 0;
        long sampledUnits = 0;
        long sampleProblems = 0;
        long sampleJudgments = 0;
        long sampleUndecided = 0;
        long sampleOracleErrors = 0;
        int failed = 0;
        int nothingCompared = 0;
        int[] verdicts = new int[4];
        java.util.Map<String, Long> declineReasons = new java.util.TreeMap<>();
        Set<String> unavailable = new HashSet<>();
        Json all = Json.array();
        for (Result r : results) {
            checks += r.checks;
            agreeAccept += r.agreeAccept;
            agreeReject += r.agreeReject;
            declA += r.declinedUnderTest;
            declB += r.declinedOracle;
            for (Map.Entry<String, Long> e : r.declineReasons.entrySet()) {
                declineReasons.merge(e.getKey(), e.getValue(), Long::sum);
            }
            sampledUnits += r.sampledUnits;
            sampleProblems += r.sampleProblems;
            sampleJudgments += r.sampleJudgments;
            sampleUndecided += r.sampleUndecided;
            sampleOracleErrors += r.sampleOracleErrors;
            if (r.sampleUnavailable != null) {
                unavailable.add(r.sampleUnavailable);
            }
            verdicts[r.failed() ? 3 : r.exit]++;
            if (r.failed()) {
                failed++;
            } else if (!r.summarised) {
                nothingCompared++; // decided before any engine was asked (a reader or loader rejection, a decline at load)
            }
            for (Json d : r.verdictDifferences) {
                if ("SOUNDNESS".equals(d.getText("kind", ""))) {
                    soundness++;
                } else {
                    completeness++;
                }
                if ("warm-only".equals(d.getText("persistence", ""))) {
                    warmOnly++;
                }
                all.add(d);
            }
            samples += r.sampleDifferences.size();
            for (Json d : r.sampleDifferences) {
                all.add(d);
            }
        }
        out.println();
        out.printf("exports: %d (accept %d, reject %d, decline %d, failed %d); %d decided before any engine was asked%n",
                results.size(), verdicts[0], verdicts[1], verdicts[2], failed, nothingCompared);
        long agreed = agreeAccept + agreeReject;
        out.printf("units compared: %d of %d checks (both accept %d, both reject %d); not compared: declined by the engine under"
                + " test %d, by the oracle %d%n", agreed, checks, agreeAccept, agreeReject, declA, declB);
        if (!declineReasons.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Long> e : declineReasons.entrySet()) {
                sb.append(sb.length() == 0 ? "" : ", ").append(e.getKey()).append(' ').append(e.getValue());
            }
            out.println("declines of the engine under test, by reason: " + sb + "  (such units were not compared)");
        }
        out.printf("verdict differences: %d  (SOUNDNESS %d, COMPLETENESS %d; warm-only %d)%n", soundness + completeness, soundness,
                completeness, warmOnly);
        boolean sampling = a.getInt("--sample", 0) > 0 || a.get("--sample-unit", null) != null;
        if (sampling) {
            out.printf("KernelOps sampler: %d unit(s), %d sub-term(s), %d judgment(s) compared: %d difference(s); %d undecided,"
                    + " %d the oracle could not answer%n", sampledUnits, sampleProblems, sampleJudgments, samples, sampleUndecided,
                    sampleOracleErrors);
            for (String why : unavailable) {
                out.println("  SAMPLER NOT RUN: " + why);
            }
        }
        Json doc = Json.object();
        doc.put("utc", Instant.now().toString());
        doc.put("engine", engine);
        doc.put("threads", threads);
        doc.put("exports", results.size());
        doc.put("failed", failed);
        doc.put("units_compared", checks);
        doc.put("agree_accept", agreeAccept);
        doc.put("agree_reject", agreeReject);
        doc.put("declined_under_test", declA);
        doc.put("declined_oracle", declB);
        Json byReason = Json.object();
        for (Map.Entry<String, Long> e : declineReasons.entrySet()) {
            byReason.put(e.getKey(), e.getValue());
        }
        doc.put("declined_under_test_by_reason", byReason);
        doc.put("soundness", soundness);
        doc.put("completeness", completeness);
        doc.put("warm_only", warmOnly);
        doc.put("sampled_units", sampledUnits);
        doc.put("sample_problems", sampleProblems);
        doc.put("sample_judgments", sampleJudgments);
        doc.put("sample_differences", samples);
        doc.put("differences", all);
        Path summary = outDir.resolve("summary.json");
        Files.write(summary, (Json.pretty(doc) + "\n").getBytes(StandardCharsets.UTF_8));
        out.println("summary: " + rel(root, summary));

        long differences = soundness + completeness + samples;
        boolean samplerDead = sampling && sampledUnits == 0 && !unavailable.isEmpty();
        if (a.flag("--expect-differences")) {
            if (differences > 0) {
                out.println("RESULT: the engine under test was caught (" + differences + " difference(s)), as expected");
                return 0;
            }
            out.println("RESULT: FAILED - no difference was found although one was expected");
            return 1;
        }
        if (differences > 0) {
            out.println("RESULT: FAILED - " + differences + " difference(s) between " + (engine == null ? "the engine under test"
                    : engine) + " and the oracle");
            return 1;
        }
        if (a.flag("--fail-on-decline") && declA > 0) {
            out.println("RESULT: FAILED - no difference, but the engine under test declined " + declA + " unit(s)");
            return 1;
        }
        if (failed > 0 || agreed == 0 || samplerDead) {
            out.println("RESULT: INCOMPLETE - no difference, but " + (failed > 0 ? failed + " run(s) failed"
                    : agreed == 0 ? "no unit was compared" + (declA > 0 ? " (the engine under test declined " + declA + ")" : "")
                    : "the sampler did not run"));
            return 3;
        }
        out.println("RESULT: ok - the engines agree on all " + agreed + " unit(s) they both decided"
                + (declA > 0 ? "; NOT COMPARED: " + declA + " unit(s) the engine under test declined (--fail-on-decline makes that a failure)"
                        : ""));
        return 0;
    }

    private static void put(Map<String, String> env, String key, String value) {
        if (value != null) {
            env.put(key, value);
        } else {
            env.remove(key);
        }
    }

    /** Where this class was loaded from: the module's classes directory or its jar. */
    private static String ownCode() {
        try {
            return Paths.get(DiffRun.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (java.net.URISyntaxException | RuntimeException e) {
            throw new IllegalStateException("cannot locate the test kit's own classes: " + e);
        }
    }

    private static String rel(Path root, Path p) {
        Path abs = p.toAbsolutePath().normalize();
        return abs.startsWith(root) ? root.relativize(abs).toString() : abs.toString();
    }

    /** Reads the report file of one run into the result. */
    static void read(Result r) throws IOException {
        if (r.report == null || !Files.isRegularFile(r.report)) {
            return;
        }
        Set<String> seen = new HashSet<>();
        List<Json> strictOnly = new ArrayList<>();
        for (String line : Files.readAllLines(r.report, StandardCharsets.UTF_8)) {
            if (line.trim().isEmpty()) {
                continue;
            }
            Json o;
            try {
                o = Json.parse(line);
            } catch (Json.ParseException e) {
                r.error = "unreadable line in " + r.report;
                continue;
            }
            String kind = o.getText("kind", "");
            boolean strict = o.has("strict") && o.get("strict").isBool() && o.get("strict").asBool();
            if (kind.equals("SUMMARY")) {
                r.underTest = o.getText("under_test_engine", r.underTest);
                r.oracle = o.getText("oracle_engine", r.oracle);
                if (!strict) {
                    r.summarised = true;
                    r.checks += o.getLong("checks", 0);
                    r.agreeAccept += o.getLong("agree_accept", 0);
                    r.agreeReject += o.getLong("agree_reject", 0);
                    r.declinedUnderTest += o.getLong("declined_under_test", 0);
                    r.declinedOracle += o.getLong("declined_oracle", 0);
                    Json by = o.get("declined_under_test_by_reason");
                    if (by != null && by.isObject()) {
                        for (int i = 0; i < by.size(); i++) {
                            r.declineReasons.merge(by.key(i), by.at(i).asLong(), Long::sum);
                        }
                    }
                }
                r.sampledUnits += o.getLong("sampled_units", 0);
                Json s = o.get("sample");
                if (s != null && s.isObject()) {
                    r.sampleProblems += s.getLong("problems", 0);
                    r.sampleJudgments += s.getLong("infer", 0) + s.getLong("whnf", 0) + s.getLong("def_eq", 0) + s.getLong("more", 0);
                    r.sampleUndecided += s.getLong("undecided", 0);
                    r.sampleOracleErrors += s.getLong("oracle_errors", 0);
                }
                String why = o.getText("sample_unavailable", null);
                if (why != null && !why.isEmpty() && !why.equals("null")) {
                    r.sampleUnavailable = why;
                }
            } else if (kind.equals("DECLINED")) {
                boolean underTest = "under_test".equals(o.getText("side", ""));
                List<Json> list = underTest ? r.declines : r.oracleDeclines;
                if (!strict && list.size() < 5 && seen.add("DECLINED|" + underTest + "|" + o.getLong("unit", -1))) {
                    list.add(o);
                }
            } else if (kind.startsWith("SAMPLE_")) {
                if (seen.add(kind + "|" + o.getLong("unit", -1) + "|" + o.getText("root", "") + "|" + o.getText("path", "") + "|"
                        + o.getText("problem", ""))) {
                    r.sampleDifferences.add(o);
                }
            } else if (kind.equals("SOUNDNESS") || kind.equals("COMPLETENESS")) {
                // the driver re-checks: one unit can be recorded by the warm engine (twice: warm and cold budget) and by the strict one
                String key = kind + "|" + o.getLong("unit", -1) + "|" + o.getLong("cid", -1);
                if (strict) {
                    strictOnly.add(o);
                } else if (seen.add(key)) {
                    r.verdictDifferences.add(o);
                }
            }
        }
        for (Json o : strictOnly) {
            String key = o.getText("kind", "") + "|" + o.getLong("unit", -1) + "|" + o.getLong("cid", -1);
            if (seen.add(key)) {
                r.verdictDifferences.add(o);
            }
        }
    }

    private static void line(Path root, Result r, PrintStream out, int maxPrint) {
        int diffs = r.verdictDifferences.size() + r.sampleDifferences.size();
        out.printf("%-9s %-8s %7d units %6.1f s  %s%s%n", diffs > 0 ? "DIFFERENT" : r.failed() ? "FAILED" : "same", r.verdict(),
                r.checks, r.seconds, rel(root, r.export), r.failed() ? "   (log: " + rel(root, r.log) + ")" : "");
        int shown = 0;
        for (Json d : r.verdictDifferences) {
            if (shown++ == maxPrint) {
                out.printf("    ... %d more verdict difference(s) in %s%n", r.verdictDifferences.size() - maxPrint, rel(root, r.report));
                break;
            }
            out.printf("    %s unit %d '%s': %s %s%s vs %s %s%s  [%s]%n", d.getText("kind", "?"), d.getLong("unit", -1),
                    d.getText("constant", "?"), d.getText("under_test_engine", "?"), d.getText("under_test", "?").toUpperCase(),
                    reason(d, "under_test"), d.getText("oracle_engine", "?"), d.getText("oracle", "?").toUpperCase(),
                    reason(d, "oracle"), d.getText("persistence", "?"));
            out.println("        reproduce: " + d.getText("reproduce", "(export unknown)"));
        }
        for (Json d : r.declines) {
            String message = d.getText("message", "");
            out.printf("    declined by %s: unit %d '%s': %s%s%n", d.getText("engine", "the engine under test"), d.getLong("unit", -1),
                    d.getText("constant", "?"), d.getText("reason", "?"),
                    message.isEmpty() ? "" : ": " + (message.length() > 200 ? message.substring(0, 197) + "..." : message));
        }
        for (Json d : r.oracleDeclines) {
            String message = d.getText("message", "");
            out.printf("    declined by the oracle (%s), not compared: unit %d '%s': %s%s%n", d.getText("engine", "?"),
                    d.getLong("unit", -1), d.getText("constant", "?"), d.getText("reason", "?"),
                    message.isEmpty() ? "" : ": " + (message.length() > 200 ? message.substring(0, 197) + "..." : message));
        }
        if (r.declinedUnderTest > r.declines.size() && !r.declines.isEmpty()) {
            out.printf("    ... %d decline(s) of the engine under test in all; see %s%n", r.declinedUnderTest, rel(root, r.report));
        }
        shown = 0;
        for (Json d : r.sampleDifferences) {
            if (shown++ == maxPrint) {
                out.printf("    ... %d more sampler difference(s) in %s%n", r.sampleDifferences.size() - maxPrint, rel(root, r.report));
                break;
            }
            out.printf("    %s unit %d '%s', %s%s: %s%n", d.getText("kind", "?"), d.getLong("unit", -1), d.getText("constant", "?"),
                    d.getText("root", "?"), d.getText("path", "").isEmpty() ? "" : " at " + d.getText("path", ""),
                    d.getText("problem", "?"));
            out.println("        " + d.getText("under_test_engine", "under test") + ": " + d.getText("under_test", "?"));
            out.println("        " + d.getText("oracle_engine", "oracle") + ": " + d.getText("oracle", "?"));
            out.println("        reproduce: " + d.getText("reproduce", "(export unknown)"));
        }
    }

    private static String reason(Json d, String side) {
        String reason = d.getText(side + "_reason", null);
        if (reason == null || reason.equals("null")) {
            return "";
        }
        String message = d.getText(side + "_message", null);
        if (message != null && message.length() > 160) {
            message = message.substring(0, 157) + "...";
        }
        return "(" + reason + (message == null || message.equals("null") ? "" : ": " + message) + ")";
    }
}
