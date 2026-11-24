package rlvm.machine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import rlvm.api.Outcome;

/**
 * THE GATE: one command, one page, for one switch set (ISA section 11; WORK-PACKAGES sections 5 and 6;
 * stepper-convention H12). Owner: M8.
 *
 * <pre>
 * rlvm-machine/src/test/resources/m8/report.sh gate [--switches warm,gated,expect] [--tree work/sandbox/Mk/rlvm-machine]
 * </pre>
 *
 * For the machine with the given staging switches (none = the generic tier, v0), over the tutorial tests,
 * {@code bugs/}, all 230 published tests, Init, Std, reachability and the Cedar slice:
 * <ol>
 *   <li><b>differential</b>: every declaration against the reference engine (SOUNDNESS and COMPLETENESS must
 *       be 0; a decline is counted);
 *   <li><b>tier equivalence</b>: the generic tier gives the same outcome and reason for every declaration
 *       (only when switches are on);
 *   <li><b>convention equivalence</b>: host depth 0 (every frame written) and unbounded (plain host
 *       recursion) against the bound in force: outcome, reason, ticks, call events by op, the frame trace,
 *       and after an ACCEPT every record word, run word and pair entry;
 *   <li><b>debug audits</b>: the same outcomes under {@code poison,scopeaudit,maskaudit} and under
 *       {@code memocheck,shortcheck}, and no declaration ended by an audit ({@code E_AUDIT});
 *   <li><b>the pipeline</b>: the 230 tests and the four corpora through the real driver
 *       ({@link M8Pipeline}) with {@code -X engine=machine}: the verdict, and from the statistics no
 *       MACHINE-INCOMPLETE, no WARM-ANOMALY, no disagreement, and the declines.
 * </ol>
 * The lanes of one file run after the reference engine has run and let go ({@link M8Lanes}); files run in
 * parallel; a corpus that is one big file runs its lanes in parallel. The memory rule: a file in which the
 * Java heap answered instead of an engine is run again on its own after everything else; files known to be
 * heavy (the perf tests; {@code work/m8/heavy.txt} remembers what a run measured) run a few at a time.
 */
final class M8Gate {
    private M8Gate() {}

    /** What a gate run does. */
    static final class Plan {
        /** Name of the report: {@code work/status/machine-gate-NAME.md}. */
        String name = "v0";
        /** The staging switches under test ({@code Op.SW_*}); 0 = the generic tier. */
        int switches;
        long sessionBytes = M8Harness.SESSION_BYTES;
        List<String> corpora = new ArrayList<>(List.of("tutorial", "bugs", "all", "init", "std", "reachability", "cedar"));
        boolean conv = true;
        boolean tier = true;
        boolean audits = true;
        boolean pipeline = true;
        int[] convBounds = {0, Op.HOST_DEPTH_UNBOUNDED};
        String[] auditSets = {"poison,scopeaudit,maskaudit", "memocheck,shortcheck"};
        int jobs = defaultJobs();
        /** Deadline of one engine check: generous, the host is shared (a check the deadline ends is a decline and is not compared between lanes). */
        long checkMillis = 300_000;
        long fileMillis = 1_800_000;
        /** The command line that produced the report (for its header). */
        String command = "";

        String switchNames() {
            return switches == 0 ? "generic tier (no switch)" : Debug.switchNames(switches);
        }

        /** Every corpus and every part of the gate. */
        boolean complete() {
            return conv && (tier || switches == 0) && audits && pipeline
                    && corpora.containsAll(List.of("tutorial", "bugs", "all", "init", "std", "reachability", "cedar"));
        }

        int sessionMib() {
            return (int) (sessionBytes >> 20);
        }
    }

    /** Passes at a time unless {@code -Dm8.jobs} says otherwise: 4, the limit of one host-wide slot ({@code bench/slot.sh}). */
    static int defaultJobs() {
        return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
    }

    /** A plan from system properties: {@code m8.switches}, {@code m8.sessionMib}, {@code m8.jobs}, {@code m8.parts}, {@code m8.corpora}, {@code m8.checkSeconds}, {@code m8.fileSeconds}. */
    static Plan planFromProperties() {
        Plan p = new Plan();
        String sw = System.getProperty("m8.switches", "");
        p.switches = Debug.parseSwitches(sw);
        // the machine's warm sessions are measured and proposed with a 4 MiB cap (work/status/machine-integration.md)
        int mib = Integer.getInteger("m8.sessionMib", (p.switches & Op.SW_WARM) != 0 ? 4 : 16);
        p.sessionBytes = (long) mib << 20;
        p.jobs = Integer.getInteger("m8.jobs", p.jobs);
        p.checkMillis = 1000L * Long.getLong("m8.checkSeconds", p.checkMillis / 1000);
        p.fileMillis = 1000L * Long.getLong("m8.fileSeconds", p.fileMillis / 1000);
        String parts = System.getProperty("m8.parts");
        if (parts != null) {
            Set<String> set = new HashSet<>(List.of(parts.split(",")));
            p.conv = set.contains("conv");
            p.tier = set.contains("tier");
            p.audits = set.contains("audits");
            p.pipeline = set.contains("pipeline");
        }
        String corpora = System.getProperty("m8.corpora");
        if (corpora != null) {
            p.corpora = new ArrayList<>(List.of(corpora.split(",")));
        }
        p.name = name(p);
        return p;
    }

    /**
     * The name of a plan's report: {@code -Dm8.name}, else {@code v0} or the switches joined by {@code +}; with
     * {@code -partial} when the plan leaves out a corpus or a part, so that only the complete gate writes the plain name.
     */
    static String name(Plan p) {
        String name = p.switches == 0 ? "v0" : Debug.switchNames(p.switches).replace(',', '+');
        if (!p.complete()) {
            name += "-partial";
        }
        return Diff.reportName(System.getProperty("m8.name", name));
    }

    /** The lanes of a plan: lane 0 is the machine under test, the others are compared with it. */
    static List<M8Lanes.Spec> specs(Plan p) {
        List<M8Lanes.Spec> specs = new ArrayList<>();
        boolean warm = (p.switches & Op.SW_WARM) != 0;
        M8Harness.Config lead = new M8Harness.Config("D" + Op.HOST_DEPTH);
        lead.switches = p.switches;
        lead.sessionBytes = p.sessionBytes;
        lead.checkMillis = p.checkMillis;
        lead.trace = p.conv;
        lead.traceDigestOnly = true;
        lead.digest = p.conv;
        lead.incrementalDigest = warm;
        specs.add(new M8Lanes.Spec("diff", lead));
        if (p.conv) {
            for (int b : p.convBounds) {
                specs.add(new M8Lanes.Spec("conv", lead.copy(TierEq.boundName(b)).hostDepth(b)).against(0, M8Lanes.CONV));
            }
        }
        if (p.tier && p.switches != 0) {
            M8Harness.Config g = new M8Harness.Config("generic");
            g.checkMillis = p.checkMillis;
            g.digest = false;
            specs.add(new M8Lanes.Spec("tier", g).against(0, M8Lanes.OUTCOME));
        }
        if (p.audits) {
            for (String set : p.auditSets) {
                M8Harness.Config a = lead.copy(set);
                a.debug = Debug.parseModes(set);
                a.trace = false;
                a.digest = false;
                // a memo re-computation is work on top of the declaration's own
                a.checkMillis = 4 * p.checkMillis;
                specs.add(new M8Lanes.Spec("audit", a).against(0, M8Lanes.OUTCOME));
            }
        }
        return specs;
    }

    // ---------------------------------------------------------------------------------------------------
    // Running many files: parallel, heavy files a few at a time, memory victims again on their own
    // ---------------------------------------------------------------------------------------------------

    /** A file at least this big is a corpus of its own: its lanes run in parallel. */
    static final long BIG_BYTES = 16L << 20;
    /** A file whose lanes (or the reference engine) held this much is scheduled with the heavy ones next time. */
    static final long HEAVY_BYTES = 256L << 20;

    private static Path heavyFile() {
        return Fixture.root().resolve("work/m8/heavy.txt");
    }

    private static Set<String> readHeavy() {
        Set<String> out = new HashSet<>();
        try {
            if (Files.isRegularFile(heavyFile())) {
                for (String l : Files.readAllLines(heavyFile(), StandardCharsets.UTF_8)) {
                    if (!l.isEmpty() && !l.startsWith("#")) {
                        out.add(l.split("\t")[0]);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // no memory of earlier runs: the guess below
        }
        return out;
    }

    private static synchronized void rememberHeavy(Map<String, M8Lanes.FileRun> runs, Set<String> retried) {
        try {
            Map<String, String> lines = new TreeMap<>();
            if (Files.isRegularFile(heavyFile())) {
                for (String l : Files.readAllLines(heavyFile(), StandardCharsets.UTF_8)) {
                    if (!l.isEmpty() && !l.startsWith("#")) {
                        lines.put(l.split("\t")[0], l);
                    }
                }
            }
            for (M8Lanes.FileRun r : runs.values()) {
                long peak = Math.max(r.peakFootprint(), r.refPeak);
                if (retried.contains(r.path) || peak > HEAVY_BYTES) {
                    lines.put(r.path, r.path + "\t" + (peak >> 20) + " MiB" + (retried.contains(r.path) ? "\tran out of heap beside others" : ""));
                } else if (r.loadOutcome == Outcome.ACCEPT && r.crash == null) {
                    lines.remove(r.path);
                }
            }
            StringBuilder sb = new StringBuilder("# files that M8Gate schedules a few at a time (peak session footprint of a lane or of the reference engine above "
                    + (HEAVY_BYTES >> 20) + " MiB, or out of heap beside others); rewritten by every run\n");
            for (String l : lines.values()) {
                sb.append(l).append('\n');
            }
            Files.createDirectories(heavyFile().getParent());
            Files.write(heavyFile(), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            // only a scheduling hint
        }
    }

    /**
     * Runs every file through the reference engine and the lanes. {@code jobs} passes run at a time.
     *
     * @return path -> run, in the order of {@code paths}
     */
    static Map<String, M8Lanes.FileRun> runAll(String what, List<String> paths, List<M8Lanes.Spec> specs, int jobs, long fileMillis) {
        final Semaphore cpu = new Semaphore(Math.max(1, jobs));
        final Map<String, M8Lanes.FileRun> done = new ConcurrentHashMap<>();
        final Set<String> knownHeavy = readHeavy();
        final boolean noMemory = knownHeavy.isEmpty();
        List<String> big = new ArrayList<>();
        final ConcurrentLinkedQueue<String> heavy = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<String> small = new ConcurrentLinkedQueue<>();
        for (String p : paths) {
            long size;
            try {
                size = Files.size(Fixture.root().resolve(p));
            } catch (IOException e) {
                size = 0;
            }
            if (size >= BIG_BYTES) {
                big.add(p);
            } else if (knownHeavy.contains(p) || (noMemory && p.contains("/perf/"))) {
                heavy.add(p);
            } else {
                small.add(p);
            }
        }
        final AtomicInteger finished = new AtomicInteger();
        final int total = paths.size();
        final long t0 = System.nanoTime();
        List<Thread> threads = new ArrayList<>();
        long heapGb = (Runtime.getRuntime().maxMemory() + (1L << 29)) >> 30;
        // big files: the loaded store of each stays in the heap while its lanes run (Std: about 1 GB), so only as
        // many at a time as a fifth of the heap in gigabytes; the lanes of one run at the same time
        final ConcurrentLinkedQueue<String> bigQueue = new ConcurrentLinkedQueue<>(big);
        int bigWorkers = big.isEmpty() ? 0 : (int) Math.max(1, Math.min(big.size(), heapGb / 5));
        for (int i = 0; i < bigWorkers; i++) {
            threads.add(worker("m8-gate-big", () -> {
                String p;
                while ((p = bigQueue.poll()) != null) {
                    M8Lanes.Options o = options(cpu, fileMillis);
                    o.parallelLanes = true;
                    M8Lanes.FileRun r = M8Lanes.run(p, specs, o);
                    done.put(p, r);
                    progress(what, finished.incrementAndGet(), total, r, t0);
                }
            }));
        }
        Runnable drainSmall = () -> {
            String p;
            while ((p = small.poll()) != null) {
                M8Lanes.Options o = options(cpu, fileMillis);
                o.inlineLanes = true;
                M8Lanes.FileRun r = M8Lanes.run(p, specs, o);
                done.put(p, r);
                progress(what, finished.incrementAndGet(), total, r, t0);
            }
        };
        Runnable drainHeavy = () -> {
            String p;
            while ((p = heavy.poll()) != null) {
                M8Lanes.Options o = options(cpu, fileMillis);
                o.inlineLanes = true;
                M8Lanes.FileRun r = M8Lanes.run(p, specs, o);
                done.put(p, r);
                progress(what, finished.incrementAndGet(), total, r, t0);
            }
        };
        int smallWorkers = Math.min(jobs, small.size());
        for (int i = 0; i < smallWorkers; i++) {
            threads.add(worker("m8-gate-small", drainSmall));
        }
        // heavy files: each may hold gigabytes (the reference kernel on perf/app-lam: 1.2 GB of overlay), so only
        // as many at a time as a fifth of the heap in gigabytes, at most a third of the jobs; in a heap below
        // 10 GB they wait until everything else is done and then run one at a time
        int heavyWorkers = heavy.isEmpty() || heapGb < 10 ? 0 : (int) Math.max(1, Math.min(Math.max(1, jobs / 3), heapGb / 5));
        for (int i = 0; i < heavyWorkers; i++) {
            threads.add(worker("m8-gate-heavy", drainHeavy));
        }
        for (Thread t : threads) {
            t.start();
        }
        for (Thread t : threads) {
            join(t);
        }
        if (!heavy.isEmpty()) {
            // (a small heap) one heavy file at a time, its lanes one after the other, nothing else running
            M8Pipeline.log.println("m8 " + what + ": " + heavy.size() + " heavy files, one at a time (heap " + heapGb + " GiB)");
            Thread t = worker("m8-gate-heavy", drainHeavy);
            t.start();
            join(t);
        }
        // the memory victims again, one at a time, lanes one after the other, nothing else running
        Set<String> retried = new HashSet<>();
        for (String p : paths) {
            M8Lanes.FileRun r = done.get(p);
            if (r != null && r.memory()) {
                retried.add(p);
                System.gc();
                M8Pipeline.log.println("m8 " + what + ": out of heap beside others, again on its own: " + Diff.shortName(p));
                M8Lanes.Options o = options(null, fileMillis);
                M8Lanes.FileRun[] out = new M8Lanes.FileRun[1];
                Thread t = worker("m8-gate-solo", () -> out[0] = M8Lanes.run(p, specs, o));
                t.start();
                join(t);
                if (out[0] != null) {
                    out[0].retried = true;
                    done.put(p, out[0]);
                }
            }
        }
        rememberHeavy(done, retried);
        Map<String, M8Lanes.FileRun> ordered = new LinkedHashMap<>();
        for (String p : paths) {
            M8Lanes.FileRun r = done.get(p);
            if (r == null) {
                r = new M8Lanes.FileRun();
                r.path = p;
                r.crash = new IllegalStateException("the file was not run (its worker thread died)");
            }
            ordered.put(p, r);
        }
        return ordered;
    }

    private static M8Lanes.Options options(Semaphore cpu, long fileMillis) {
        M8Lanes.Options o = new M8Lanes.Options();
        o.cpu = cpu;
        o.fileMillis = fileMillis;
        return o;
    }

    private static Thread worker(String name, Runnable body) {
        Thread t = new Thread(null, () -> {
            try {
                body.run();
            } catch (Throwable e) { // the files of this worker are reported as not run
                M8Pipeline.log.println("m8 gate: worker " + name + " died: " + e);
            }
        }, name, 2L << 20);
        t.setDaemon(true);
        return t;
    }

    private static void join(Thread t) {
        while (true) {
            try {
                t.join();
                return;
            } catch (InterruptedException e) {
                // a gate is not interrupted half way
            }
        }
    }

    private static void progress(String what, int n, int total, M8Lanes.FileRun r, long t0) {
        boolean bigFile = r.units > 3000;
        boolean loud = r.crash != null || r.timeout || bigFile;
        for (M8Lanes.LaneRun l : r.lanes) {
            if (l.diff != null && (l.diff.wrong() || l.diff.status.equals("CRASH") || l.diff.status.equals("TIMEOUT"))) {
                loud = true;
            }
            if (l.differing > 0 || l.audits > 0) {
                loud = true;
            }
        }
        if (loud || n % 50 == 0 || n == total) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.ROOT, "m8 %s [%d/%d, %.0f s] %s", what, n, total, (System.nanoTime() - t0) / 1e9, Diff.shortName(r.path)));
            if (r.crash != null) {
                sb.append(" CRASH ").append(r.crash);
            }
            for (M8Lanes.LaneRun l : r.lanes) {
                if (l.diff != null) {
                    sb.append(" | ").append(l.spec.config.label).append(' ').append(l.diff.status);
                    if (l.differing > 0) {
                        sb.append(" DIFFERENT ").append(l.differing);
                    }
                    if (l.audits > 0) {
                        sb.append(" AUDIT ").append(l.audits);
                    }
                    if (bigFile) {
                        sb.append(String.format(Locale.ROOT, " %.0f s", l.nanos / 1e9));
                    }
                }
            }
            M8Pipeline.log.println(sb);
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // The gate
    // ---------------------------------------------------------------------------------------------------

    /** The sums of one lane over the files of one row of the report. */
    static final class Row {
        int files;
        int clean;
        int declinedFiles;
        int wrongFiles;
        int crashes;
        int timeouts;
        long units;
        long checks;
        long accepted;
        long rejected;
        long engineDeclined;
        long agree;
        long declined;
        long soundness;
        long completeness;
        long refDeclined;
        long ticks;
        long calls;
        long frames;
        long nanos;
        // against the lead lane
        long comparedWithLead;
        long differing;
        long overflowSkipped;
        long pairOrderNotes;
        long reasonNotes;
        long audits;
        long memoryFailures;

        void add(M8Lanes.FileRun run, int lane) {
            files++;
            Diff.FileResult d = Diff.of(run, lane);
            if (d.clean()) {
                clean++;
            } else if (d.status.equals("DECLINED")) {
                declinedFiles++;
            } else if (d.status.equals("WRONG")) {
                wrongFiles++;
            } else if (d.status.equals("CRASH")) {
                crashes++;
            } else if (d.status.equals("TIMEOUT")) {
                timeouts++;
            }
            units += d.compared;
            checks += d.engineChecks;
            accepted += d.accepted;
            rejected += d.rejected;
            engineDeclined += d.engineDeclined;
            agree += d.agree;
            declined += d.declined;
            soundness += d.soundness;
            completeness += d.completeness;
            refDeclined += d.refDeclined;
            ticks += d.ticks;
            calls += d.calls;
            frames += d.framesWritten;
            nanos += d.machineNanos;
            if (lane < run.lanes.length) {
                M8Lanes.LaneRun l = run.lanes[lane];
                comparedWithLead += l.comparedWithOther;
                differing += l.differing;
                overflowSkipped += l.overflowSkipped;
                pairOrderNotes += l.pairOrderNotes;
                reasonNotes += l.reasonNotes;
                audits += l.audits;
                memoryFailures += l.memoryFailures;
            }
        }
    }

    /** What the pipeline part showed on one row of the report. */
    static final class PipeRow {
        int files;
        int right;
        int wrong;
        int declinedRuns;
        int errors;
        long machineAccepted;
        long machineRejected;
        long machineDeclined;
        long incomplete;
        long warmAnomalies;
        long disagreements;
        long nanos;
        final List<String> lines = new ArrayList<>();
    }

    /** The result of a gate run. */
    static final class Result {
        Plan plan;
        List<M8Lanes.Spec> specs;
        /** Row label -> per lane. */
        final Map<String, Row[]> rows = new LinkedHashMap<>();
        final Map<String, PipeRow> pipe = new LinkedHashMap<>();
        final List<String> problems = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        /** Table rows of the tier test on values (only for a switch set with K1 switches). */
        final List<String> valueTiers = new ArrayList<>();
        long wallNanos;
        int retried;
        boolean wrong;
        boolean different;
        boolean declines;
        boolean broken;

        /** PASS: nothing wrong, nothing different, no decline, no crash. */
        String verdict() {
            if (wrong) {
                return "FAIL: WRONG VERDICTS";
            }
            if (different || broken) {
                return "FAIL";
            }
            return declines ? "PASS WITH DECLINES (no wrong verdict, no difference; the machine did not decide everything)" : "PASS";
        }

        boolean pass() {
            return !wrong && !different && !broken;
        }
    }

    private static final String TESTS = Diff.TESTS;

    /** The row a file of the 230 published tests belongs to. */
    private static String testRow(String path) {
        if (path.startsWith(TESTS + "good/tutorial/") || path.startsWith(TESTS + "bad/tutorial/")) {
            return "tutorial";
        }
        if (path.startsWith(TESTS + "good/bugs/") || path.startsWith(TESTS + "bad/bugs/")) {
            return "bugs";
        }
        return "other tests";
    }

    static Result run(Plan plan) throws IOException {
        long t0 = System.nanoTime();
        Result res = new Result();
        res.plan = plan;
        res.specs = specs(plan);
        int lanes = res.specs.size();
        // the files: every file once, whatever corpora name it
        Map<String, String> rowOf = new LinkedHashMap<>();
        List<String> tests = new ArrayList<>();
        for (String c : plan.corpora) {
            String[] roots = Diff.corpus(c);
            if (roots == null) {
                roots = new String[] {c};
            }
            List<String> files = Diff.exports(roots);
            if (files.isEmpty()) {
                res.notes.add("corpus '" + c + "' has no export on this machine (" + String.join(", ", roots) + "): NOT RUN");
                res.broken = true;
                continue;
            }
            for (String f : files) {
                if (f.startsWith(TESTS)) {
                    if (!rowOf.containsKey(f)) {
                        tests.add(f);
                    }
                    rowOf.putIfAbsent(f, testRow(f));
                } else {
                    rowOf.putIfAbsent(f, c.equals("cedar") ? "cedar-hot-ax" : Diff.corpus(c) != null ? c : Diff.shortName(f));
                }
            }
        }
        List<String> paths = new ArrayList<>(rowOf.keySet());
        M8Pipeline.log.println("m8 gate " + plan.name + ": " + paths.size() + " files, " + lanes + " lanes, " + plan.jobs + " passes at a time, switches ["
                + Debug.switchNames(plan.switches) + "]");
        Map<String, M8Lanes.FileRun> runs = runAll("gate " + plan.name, paths, res.specs, plan.jobs, plan.fileMillis);

        boolean allTests = plan.corpora.contains("all");
        for (Map.Entry<String, M8Lanes.FileRun> e : runs.entrySet()) {
            M8Lanes.FileRun run = e.getValue();
            String row = rowOf.get(e.getKey());
            for (String label : row.equals("tutorial") || row.equals("bugs") || row.equals("other tests")
                    ? (allTests ? new String[] {row, "all 230 tests"} : new String[] {row}) : new String[] {row}) {
                Row[] rs = res.rows.computeIfAbsent(label, k -> newRows(lanes));
                for (int i = 0; i < lanes; i++) {
                    rs[i].add(run, i);
                }
            }
            if (run.retried) {
                res.retried++;
            }
            problemsOf(res, run);
        }
        // keep the order tutorial, bugs, other tests, all 230 tests, then the corpora
        if (res.rows.containsKey("all 230 tests")) {
            Map<String, Row[]> ordered = new LinkedHashMap<>();
            for (String k : new String[] {"tutorial", "bugs", "other tests", "all 230 tests"}) {
                if (res.rows.containsKey(k)) {
                    ordered.put(k, res.rows.get(k));
                }
            }
            for (Map.Entry<String, Row[]> e : res.rows.entrySet()) {
                ordered.putIfAbsent(e.getKey(), e.getValue());
            }
            res.rows.clear();
            res.rows.putAll(ordered);
        }
        for (Row[] rs : res.rows.values()) {
            for (int i = 0; i < lanes; i++) {
                Row r = rs[i];
                if (r.soundness + r.completeness > 0) {
                    res.wrong = true;
                }
                if (r.differing > 0 || r.audits > 0) {
                    res.different = true;
                }
                if (r.crashes + r.timeouts > 0) {
                    res.broken = true;
                }
                if (r.declined > 0) {
                    res.declines = true;
                }
            }
        }
        if (plan.tier && (plan.switches & TierEq.K1_SWITCHES) != 0) {
            valueTiers(plan, res, paths);
        }
        if (plan.pipeline && !M8Pipeline.available()) {
            res.notes.add("part 5 (the pipeline) NOT RUN: rlvm.main.RlvmChecker is not on the class path (run the gate through report.sh)");
            res.broken = true;
        } else if (plan.pipeline) {
            pipeline(plan, res, tests, paths);
        }
        res.wallNanos = System.nanoTime() - t0;
        return res;
    }

    private static Row[] newRows(int n) {
        Row[] rs = new Row[n];
        for (int i = 0; i < n; i++) {
            rs[i] = new Row();
        }
        return rs;
    }

    /** The lines of the report's problem list for one file: wrong verdicts first, then differences, crashes, declines. */
    private static void problemsOf(Result res, M8Lanes.FileRun run) {
        String sw = Debug.switchNames(res.plan.switches);
        String opt = (sw.isEmpty() ? "" : " -Dm8.switches=" + sw) + ((res.plan.switches & Op.SW_WARM) != 0 ? " -Dm8.sessionMib=" + res.plan.sessionMib() : "");
        if (run.crash != null) {
            res.problems.add("1 CRASH " + Diff.shortName(run.path) + ": " + run.crash + "\n    reproduce: " + Diff.reproduce(run.path, -1) + opt);
            return;
        }
        for (int i = 0; i < run.lanes.length; i++) {
            M8Lanes.LaneRun l = run.lanes[i];
            Diff.FileResult d = l.diff;
            String lane = l.spec.role + " lane " + l.spec.config.label;
            String laneOpt = opt + (l.spec.config.hostDepth != Op.HOST_DEPTH ? " -Dm8.hostDepth=" + l.spec.config.hostDepth : "")
                    + (l.spec.config.debug != 0 ? " -Dm8.debug=" + Debug.modeNames(l.spec.config.debug) : "");
            if (l.spec.role.equals("tier")) {
                laneOpt = ""; // the generic tier
            }
            if (d != null && (d.wrong() || d.status.equals("CRASH") || d.status.equals("TIMEOUT"))) {
                StringBuilder sb = new StringBuilder();
                sb.append(d.wrong() ? "0 WRONG " : "1 " + d.status + " ").append(Diff.shortName(run.path)).append(" (").append(lane).append(")");
                if (d.lines.isEmpty()) {
                    sb.append("\n    ").append(d.first);
                }
                for (int k = 0; k < d.lines.size() && k < 4; k++) {
                    sb.append("\n    ").append(d.lines.get(k));
                }
                sb.append("\n    reproduce: ").append(Diff.reproduce(run.path, d.firstUnit)).append(laneOpt);
                res.problems.add(sb.toString());
            } else if (d != null && d.declined > 0 && i == 0) {
                res.problems.add("4 declined " + Diff.shortName(run.path) + ": " + d.declined + " units; " + d.first + "\n    reproduce: "
                        + Diff.reproduce(run.path, d.firstUnit) + laneOpt);
            }
            if (l.differing > 0) {
                StringBuilder sb = new StringBuilder();
                sb.append("2 DIFFERENT ").append(l.spec.role).append(": ").append(l.differing).append(" declarations of ").append(Diff.shortName(run.path));
                for (int k = 0; k < l.differences.size() && k < 3; k++) {
                    sb.append("\n    ").append(l.differences.get(k));
                }
                sb.append("\n    reproduce: scripts/machine-sandbox.sh test <WP> -Dtest=M8ConventionTest#oneFile -Dm8.file=").append(run.path)
                        .append(opt).append(l.spec.role.equals("conv") ? "" : "   (lanes: " + run.lanes[0].spec.config + " against " + l.spec.config + ")");
                res.problems.add(sb.toString());
            }
            if (l.reasonNotes > 0) {
                StringBuilder sb = new StringBuilder();
                sb.append("5 note (").append(l.spec.role).append("): ").append(l.reasonNotes).append(" declarations of ").append(Diff.shortName(run.path))
                        .append(" have the same outcome for another reason");
                for (int k = 0; k < l.reasonLines.size() && k < 2; k++) {
                    sb.append("\n    ").append(l.reasonLines.get(k));
                }
                res.problems.add(sb.toString());
            }
            if (l.audits > 0) {
                StringBuilder sb = new StringBuilder();
                sb.append("2 AUDIT ").append(l.spec.config.label).append(": ").append(l.audits).append(" declarations of ").append(Diff.shortName(run.path))
                        .append(" ended by a debug audit (E_AUDIT)");
                for (int k = 0; k < l.auditLines.size() && k < 3; k++) {
                    sb.append("\n    ").append(M8Lanes.clip(l.auditLines.get(k), 420));
                }
                sb.append("\n    reproduce: ").append(Diff.reproduce(run.path, -1)).append(laneOpt);
                res.problems.add(sb.toString());
            }
        }
    }

    // ---- part 2, on values: K0 against the K1 switches of the set (ISA section 11 "tier equivalence") -------------

    /**
     * For a switch set with K1 switches (facts, tele, code, casetrees): DELTA, WHNF, VTYPE and CONV of generated
     * applications of the constants of the tutorial tests and of Init give the same VALUE in the generic tier and
     * under those switches ({@link M8Tiers}: structural fingerprint incl. closure identity).
     */
    private static void valueTiers(Plan plan, Result res, List<String> paths) {
        int k1 = plan.switches & TierEq.K1_SWITCHES;
        Map<String, int[]> sums = new LinkedHashMap<>();
        for (String p : paths) {
            String row = p.startsWith(TESTS) ? (testRow(p).equals("tutorial") ? "tutorial" : null) : p.equals("work/exports/init.ndjson") ? "init" : null;
            if (row == null) {
                continue;
            }
            int[] sum = sums.computeIfAbsent(row, k -> new int[4]);
            try {
                M8Tiers.Result r = M8Tiers.values(p, 1, k1, Integer.getInteger("m8.every", 0));
                sum[0] += r.constants;
                sum[1] += r.compared;
                sum[2] += r.differences.size();
                sum[3] += r.skipped;
                if (!r.differences.isEmpty()) {
                    res.different = true;
                    res.problems.add("2 DIFFERENT VALUE in the tier [" + Debug.switchNames(k1) + "]: " + r.differences.size() + " results of " + Diff.shortName(p)
                            + "\n    " + r.differences.get(0) + "\n    reproduce: rlvm-machine/src/test/resources/m8/report.sh tiers x " + p + " -Dm8.k1="
                            + Debug.switchNames(k1));
                }
            } catch (IOException | RuntimeException e) {
                sum[2]++;
                res.broken = true;
                res.problems.add("1 CRASH of the tier test on " + Diff.shortName(p) + ": " + e);
            }
        }
        for (Map.Entry<String, int[]> e : sums.entrySet()) {
            int[] v = e.getValue();
            res.valueTiers.add(String.format(Locale.ROOT, "| %s | %d | %d | %s | %d |", e.getKey(), v[0], v[1], bold(v[2]), v[3]));
        }
    }

    // ---- part 5: the pipeline ---------------------------------------------------------------------------

    private static void pipeline(Plan plan, Result res, List<String> tests, List<String> all) {
        M8Pipeline.quiet();
        final Map<String, String> x = M8Pipeline.machine(Debug.switchNames(plan.switches), "", (plan.switches & Op.SW_WARM) != 0 ? plan.sessionMib() : 0);
        final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>(tests);
        final Map<String, M8Pipeline.Result> out = new ConcurrentHashMap<>();
        List<Thread> threads = new ArrayList<>();
        // the perf tests hold gigabytes in the reference kernel's re-check lanes: half the jobs
        int workers = Math.max(1, Math.min(plan.jobs / 2, tests.size()));
        for (int i = 0; i < workers; i++) {
            Thread t = worker("m8-gate-pipeline", () -> {
                String p;
                while ((p = queue.poll()) != null) {
                    out.put(p, M8Pipeline.check(p, 1, x));
                }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            join(t);
        }
        if (!tests.isEmpty()) {
            PipeRow row = new PipeRow();
            res.pipe.put(tests.size() == 230 ? "all 230 tests (1 worker each)" : tests.size() + " tests (1 worker each)", row);
            for (String p : tests) {
                addPipe(res, row, p, out.get(p), Diff.official(p), 1, x);
            }
        }
        for (String p : all) {
            if (p.startsWith(TESTS)) {
                continue;
            }
            PipeRow row = new PipeRow();
            res.pipe.put(Diff.shortName(p) + " (4 workers)", row);
            addPipe(res, row, p, M8Pipeline.check(p, 4, x), Outcome.ACCEPT, 4, x);
        }
    }

    private static void addPipe(Result res, PipeRow row, String path, M8Pipeline.Result r, int expected, int threads, Map<String, String> x) {
        row.files++;
        if (r == null) {
            r = new M8Pipeline.Result();
            r.error = "not run";
        }
        row.nanos += r.nanos;
        row.machineAccepted += r.machineAccepted;
        row.machineRejected += r.machineRejected;
        row.machineDeclined += r.machineDeclined;
        row.incomplete += r.incomplete;
        row.warmAnomalies += r.warmAnomalies;
        row.disagreements += r.disagreements;
        String cmd = "\n    reproduce: " + M8Pipeline.commandLine(path, threads, x);
        if (r.outcome < 0) {
            row.errors++;
            res.broken = true;
            res.problems.add("1 PIPELINE ERROR " + Diff.shortName(path) + ": " + r.error + cmd);
        } else if (r.outcome == expected) {
            row.right++;
        } else if (r.outcome == Outcome.DECLINE) {
            row.declinedRuns++;
            res.declines = true;
            res.problems.add("4 pipeline run declined " + Diff.shortName(path) + " (expected " + Outcome.name(expected) + ")" + cmd);
        } else {
            row.wrong++;
            res.wrong = true;
            res.problems.add("0 WRONG VERDICT OF THE PIPELINE " + Diff.shortName(path) + ": " + Outcome.name(r.outcome) + ", expected " + Outcome.name(expected) + cmd);
        }
        if (r.machineWrong() > 0) {
            res.different = true;
            res.problems.add("2 MACHINE AGAINST THE REFERENCE KERNEL IN THE DRIVER " + Diff.shortName(path) + ": MACHINE-INCOMPLETE " + r.incomplete
                    + ", WARM-ANOMALY " + r.warmAnomalies + ", disagreements " + r.disagreements + cmd);
        }
        if (r.machineDeclined > 0) {
            res.declines = true;
            if (row.lines.size() < 6) {
                row.lines.add(Diff.shortName(path) + ": the machine declined " + r.machineDeclined + " engine checks (the reference kernel decided them)");
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // The page
    // ---------------------------------------------------------------------------------------------------

    static String render(Result res) {
        Plan p = res.plan;
        StringBuilder sb = new StringBuilder();
        sb.append("# machine-gate-").append(p.name).append(": THE GATE of engine \"machine\" with switches [").append(Debug.switchNames(p.switches)).append("]")
                .append(p.switches == 0 ? " (the generic tier, v0)" : "").append('\n');
        sb.append('\n');
        sb.append("**RESULT: ").append(res.verdict()).append(p.complete() ? "" : " -- A PARTIAL RUN, NOT THE GATE: corpora " + p.corpora
                + (p.conv ? "" : ", no convention equivalence") + (p.tier || p.switches == 0 ? "" : ", no tier equivalence") + (p.audits ? "" : ", no audits")
                + (p.pipeline ? "" : ", no pipeline")).append("**\n\n");
        sb.append("- generated ").append(Instant.now().truncatedTo(ChronoUnit.SECONDS)).append(" by `").append(p.command).append("`\n");
        sb.append("- measured: ").append(Diff.lastPublication()).append('\n');
        sb.append("- machine under test: ").append(res.specs.get(0).config).append("; ").append(res.specs.size()).append(" lanes per file; ")
                .append(p.jobs).append(" passes at a time; heap ").append(Runtime.getRuntime().maxMemory() >> 30).append(" GiB; ")
                .append(String.format(Locale.ROOT, "wall %.0f s", res.wallNanos / 1e9));
        if (res.retried > 0) {
            sb.append("; ").append(res.retried).append(" files ran out of heap beside others and were run again on their own");
        }
        sb.append('\n');
        for (String n : res.notes) {
            sb.append("- NOTE: ").append(n).append('\n');
        }
        sb.append('\n');

        // 1
        sb.append("## 1. Differential: every declaration against the reference engine\n\n");
        sb.append("| corpus | files | clean | units | engine checks | machine accepted | rejected | declined | SOUNDNESS | COMPLETENESS | reference declined | crash / timeout | ticks | lane CPU s |\n");
        sb.append("|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|\n");
        for (Map.Entry<String, Row[]> e : res.rows.entrySet()) {
            Row r = e.getValue()[0];
            sb.append(String.format(Locale.ROOT, "| %s | %d | %d | %d | %d | %d | %d | %d | %s | %s | %d | %d | %d | %.1f |%n", e.getKey(), r.files, r.clean,
                    r.units, r.checks, r.accepted, r.rejected, r.engineDeclined, bold(r.soundness), bold(r.completeness), r.refDeclined,
                    r.crashes + r.timeouts, r.ticks, r.nanos / 1e9));
        }
        sb.append("\nSOUNDNESS = the machine accepted a declaration the reference engine rejects; COMPLETENESS = it rejected one the reference accepts"
                + " (both must be 0). A unit the reference engine itself declines is settled by the official verdict of the file.\n\n");

        int lane = 1;
        // 3 (lanes follow the order of specs: conv, tier, audits)
        List<int[]> conv = new ArrayList<>();
        List<int[]> tier = new ArrayList<>();
        List<int[]> audit = new ArrayList<>();
        for (int i = 1; i < res.specs.size(); i++) {
            String role = res.specs.get(i).role;
            (role.equals("conv") ? conv : role.equals("tier") ? tier : audit).add(new int[] {i});
        }
        sb.append("## 2. Tier equivalence: the generic tier against this switch set (outcome and reason of every declaration)\n\n");
        if (tier.isEmpty()) {
            sb.append(p.switches == 0 ? "Not applicable: the machine under test is the generic tier.\n\n" : "NOT RUN (`-Dm8.parts` without `tier`).\n\n");
        } else {
            int i = tier.get(0)[0];
            sb.append("| corpus | declarations compared | different outcome | same outcome, another reason | generic tier: declined | SOUNDNESS | COMPLETENESS | lane CPU s |\n|---|--:|--:|--:|--:|--:|--:|--:|\n");
            for (Map.Entry<String, Row[]> e : res.rows.entrySet()) {
                Row r = e.getValue()[i];
                sb.append(String.format(Locale.ROOT, "| %s | %d | %s | %d | %d | %s | %s | %.1f |%n", e.getKey(), r.comparedWithLead, bold(r.differing), r.reasonNotes,
                        r.declined, bold(r.soundness), bold(r.completeness), r.nanos / 1e9));
            }
            sb.append('\n');
            if (!res.valueTiers.isEmpty()) {
                sb.append("On values (the K1 switches [").append(Debug.switchNames(p.switches & TierEq.K1_SWITCHES)).append("] against the generic tier: DELTA, WHNF,"
                        + " VTYPE and CONV of generated applications give the same value, structural fingerprint incl. closure identity):\n\n");
                sb.append("| corpus | constants | judgment results compared | different | constants skipped (a limit, or the same failure in both tiers) |\n|---|--:|--:|--:|--:|\n");
                for (String l : res.valueTiers) {
                    sb.append(l).append('\n');
                }
                sb.append('\n');
            }
        }
        sb.append("## 3. Convention equivalence: host depth ");
        for (int k = 0; k < conv.size(); k++) {
            sb.append(k == 0 ? "" : " and ").append(res.specs.get(conv.get(k)[0]).config.label);
        }
        sb.append(" against D").append(Op.HOST_DEPTH).append(" (the bound in force)\n\n");
        if (conv.isEmpty()) {
            sb.append("NOT RUN (`-Dm8.parts` without `conv`).\n\n");
        } else {
            sb.append("Compared per declaration: outcome, reason, ticks, call events by op, the frame trace; after an ACCEPT every record word (ids, flags,"
                    + " memo words), every run word, the pair entries" + ((p.switches & Op.SW_WARM) != 0
                            ? " (warm sessions: what the declaration added, and the whole session at every reset and at the end of the file)" : "")
                    + ".\n\n");
            sb.append("| corpus | lane | declarations compared | different | not compared (host stack overflow without a bound) | pair-order notes | frames written | D")
                    .append(Op.HOST_DEPTH).append(" frames / call events | lane CPU s |\n|---|---|--:|--:|--:|--:|--:|--:|--:|\n");
            for (Map.Entry<String, Row[]> e : res.rows.entrySet()) {
                Row lead = e.getValue()[0];
                for (int[] c : conv) {
                    Row r = e.getValue()[c[0]];
                    sb.append(String.format(Locale.ROOT, "| %s | %s | %d | %s | %d | %d | %d | %s | %.1f |%n", e.getKey(), res.specs.get(c[0]).config.label,
                            r.comparedWithLead, bold(r.differing), r.overflowSkipped, r.pairOrderNotes, r.frames,
                            lead.calls == 0 ? "n/a" : String.format(Locale.ROOT, "%d / %d = %.4f%%", lead.frames, lead.calls, 100.0 * lead.frames / lead.calls),
                            r.nanos / 1e9));
                }
            }
            sb.append('\n');
        }
        sb.append("## 4. Debug audits: the same outcomes under the audits, and no declaration ended by one\n\n");
        if (audit.isEmpty()) {
            sb.append("NOT RUN (`-Dm8.parts` without `audits`).\n\n");
        } else {
            sb.append("| corpus | audits | declarations compared | different outcome | ended by an audit (E_AUDIT) | same outcome, another reason | declined | SOUNDNESS | COMPLETENESS | lane CPU s |\n|---|---|--:|--:|--:|--:|--:|--:|--:|--:|\n");
            for (Map.Entry<String, Row[]> e : res.rows.entrySet()) {
                for (int[] c : audit) {
                    Row r = e.getValue()[c[0]];
                    sb.append(String.format(Locale.ROOT, "| %s | %s | %d | %s | %s | %d | %d | %s | %s | %.1f |%n", e.getKey(), res.specs.get(c[0]).config.label,
                            r.comparedWithLead, bold(r.differing), bold(r.audits), r.reasonNotes, r.declined, bold(r.soundness), bold(r.completeness), r.nanos / 1e9));
                }
            }
            sb.append('\n');
        }
        sb.append("## 5. The pipeline: the real driver in this JVM with `-X engine=machine")
                .append(p.switches == 0 ? "" : " -X machine.switches=" + Debug.switchNames(p.switches))
                .append((p.switches & Op.SW_WARM) != 0 ? " -X session-mib=" + p.sessionMib() : "").append("`\n\n");
        if (res.pipe.isEmpty()) {
            sb.append("NOT RUN (`-Dm8.parts` without `pipeline`).\n\n");
        } else {
            sb.append("| corpus | runs | verdict right | WRONG | run declined | error | machine accepted | rejected | declined | MACHINE-INCOMPLETE | WARM-ANOMALY | disagreements | s |\n");
            sb.append("|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|\n");
            for (Map.Entry<String, PipeRow> e : res.pipe.entrySet()) {
                PipeRow r = e.getValue();
                sb.append(String.format(Locale.ROOT, "| %s | %d | %d | %s | %d | %d | %d | %d | %d | %s | %s | %s | %.1f |%n", e.getKey(), r.files, r.right, bold(r.wrong),
                        r.declinedRuns, r.errors, r.machineAccepted, r.machineRejected, r.machineDeclined, bold(r.incomplete), bold(r.warmAnomalies),
                        bold(r.disagreements), r.nanos / 1e9));
            }
            sb.append("\nThe verdict is the driver's (it re-checks a rejection and a decline of the machine with the reference kernel); the machine's own"
                    + " answers are the three columns after it. MACHINE-INCOMPLETE = the machine rejected, the reference kernel accepts.\n");
            for (PipeRow r : res.pipe.values()) {
                for (String l : r.lines) {
                    sb.append("- ").append(l).append('\n');
                }
            }
            sb.append('\n');
        }
        sb.append("## Problems and notes\n\n");
        if (res.problems.isEmpty()) {
            sb.append("None.\n");
        } else {
            List<String> sorted = new ArrayList<>(res.problems);
            Collections.sort(sorted);
            sb.append("```\n");
            int shown = 0;
            for (String s : sorted) {
                if (shown++ == 40) {
                    sb.append("... ").append(sorted.size() - 40).append(" more (work/status/machine-gate-").append(p.name).append(".problems.txt)\n");
                    break;
                }
                sb.append(s.substring(2)).append('\n');
            }
            sb.append("```\n");
        }
        return sb.toString();
    }

    private static String bold(long n) {
        return n == 0 ? "0" : "**" + n + "**";
    }

    /** Runs the gate and writes {@code work/status/machine-gate-NAME.md} (and {@code .problems.txt} with every problem line). */
    static Result report(Plan plan) throws IOException {
        Result res = run(plan);
        String text = render(res);
        Path out = Fixture.root().resolve("work/status/machine-gate-" + plan.name + ".md");
        Files.createDirectories(out.getParent());
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
        Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        Path all = out.resolveSibling("machine-gate-" + plan.name + ".problems.txt");
        if (res.problems.isEmpty()) {
            Files.deleteIfExists(all);
        } else {
            List<String> sorted = new ArrayList<>(res.problems);
            Collections.sort(sorted);
            StringBuilder sb = new StringBuilder();
            for (String s : sorted) {
                sb.append(s.substring(2)).append('\n');
            }
            Files.write(all, sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        System.out.println(text);
        System.out.println("report: " + out);
        return res;
    }
}
