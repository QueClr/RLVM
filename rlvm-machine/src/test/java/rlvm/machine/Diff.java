package rlvm.machine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import rlvm.api.Outcome;
import rlvm.api.Reason;

/**
 * Differential runs against the reference engine over corpora (ISA section 11), per declaration, and
 * the reports the gates are read from ({@code work/status/machine-diff-*.txt}). Owner: M8.
 *
 * <p>{@link Fixture#diffAgainstReference()} compares one file in-process ({@link #file(String)});
 * {@link #run} does the same comparison through {@link M8Harness} (same rule, held against the fixture
 * by {@code M8HarnessTest}) and adds what a report over hundreds of files needs: one line per file, the
 * first difference of every file with a command that reproduces it, the reasons the machine gave for
 * its declines (which stub or judgment stopped how many declarations), a deadline per check and a hard
 * time limit per file, so that a looping or crashing judgment costs one line and not the report.
 *
 * <p>Vocabulary (the fixture's): a declaration <em>agrees</em> when machine and reference give the same
 * outcome; <em>declined</em> = the machine declined where the reference decided (incomplete, never
 * wrong); SOUNDNESS = the machine accepted what the reference rejects; COMPLETENESS = the machine
 * rejected what the reference accepts. A file is <em>clean</em> when no declaration differs. The gate
 * value of a corpus is "every file clean"; SOUNDNESS + COMPLETENESS must be 0 at every commit.
 *
 * <p>A unit on which the reference engine itself DECLINES (its byte budget, a limit) while the machine
 * decides is not a difference in either direction (there is no verdict to differ from): it is counted
 * as "reference declined", the run goes on behind it, and the file is settled by the official verdict
 * (the arena's classification, confirmed by the official checker for all 230 tests): status
 * {@code CLEAN*} if the machine's verdict of the file is the official one, {@code WRONG} if it is the
 * opposite one. The shared fixture has the same counter ({@code DiffReport.referenceDeclined}) and calls
 * such a file not clean (NOTES-M8 D8).
 */
final class Diff {
    private Diff() {}

    /** Per-declaration comparison of one export (relative to the project root), by the shared fixture. */
    static Fixture.DiffReport file(String exportPath) throws IOException {
        Fixture f = Fixture.load(exportPath);
        try {
            return f.diffAgainstReference();
        } finally {
            f.close();
        }
    }

    /** How a corpus is run. */
    static final class Options {
        M8Harness.Config config = new M8Harness.Config("machine");
        /** Hard wall-clock limit of one file (load + reference + machine); beyond it the file is reported as TIMEOUT. */
        long fileMillis = 1_800_000;
        /** Difference lines kept per file. */
        int linesPerFile = 12;
        /** Progress lines on stderr. */
        boolean progress = true;
        /** Byte budget of the reference engine per unit; 0 = {@link M8Harness#REF_BYTES}. */
        long refBytes;
        /** Test seam of {@link M8Harness#refDeclines}. */
        java.util.function.IntPredicate refDeclines;
        /**
         * Files at a time ({@code -Dm8.jobs}, default 1). Above 1 the corpus runs through {@link M8Gate#runAll}:
         * heavy files a few at a time, and a file in which the Java heap answered again on its own.
         */
        int jobs = Integer.getInteger("m8.jobs", 1);
    }

    /** What the comparison of one export showed. */
    static final class FileResult {
        String path;
        /** CLEAN, DECLINED (differences, none of them wrong), WRONG, LOADER (the loader decided the file), TIMEOUT, CRASH. */
        String status = "CLEAN";
        int units;
        /** Units compared (up to and including the first one the reference did not accept). */
        int compared;
        /** Units that reached the machine's {@code Session.check}. */
        int engineChecks;
        int agree;
        int declined;
        int soundness;
        int completeness;
        /**
         * Units the machine decided although the reference engine DECLINED them (its byte budget, a limit of
         * its own): no verdict to compare with. They are neither agreements nor differences; the file is then
         * settled by the official verdict (the arena's classification of the file), see {@link #settled}.
         */
        int refDeclined;
        /** For a file with {@link #refDeclined} units: how the official verdict settled it; else empty. */
        String settled = "";
        /** The verdict rule applied to each side alone: the outcome of its first unit that was not accepted. */
        int refVerdict = Outcome.ACCEPT;
        int machineVerdict = Outcome.ACCEPT;
        int machineFirstUnit = -1;
        /** Engine checks by machine outcome. */
        int accepted;
        int rejected;
        int engineDeclined;
        long ticks;
        long calls;
        long framesWritten;
        long refNanos;
        long machineNanos;
        long loadNanos;
        String loadMessage = "";
        final List<String> lines = new ArrayList<>();
        /** Unit of the first difference, or -1. */
        int firstUnit = -1;
        String first = "";
        /** Normalised machine reason -> number of declarations (declines and rejections that differ). */
        final Map<String, Integer> reasons = new TreeMap<>();
        Throwable crash;
        /** The file's wall-clock limit ended the comparison (the counts are those of the units done). */
        boolean timeout;
        /**
         * Some answer in this file is the test JVM's heap speaking (an {@code OutOfMemoryError} in a check, a
         * growth the machine's heap test refused, the reference engine out of heap): such declines are the
         * harness's, and a parallel report runs the file again on its own.
         */
        boolean memory;

        boolean clean() {
            return status.equals("CLEAN") || status.equals("LOADER") || status.equals("CLEAN*");
        }

        boolean wrong() {
            return soundness + completeness > 0;
        }
    }

    /** A corpus run. */
    static final class Report {
        String label;
        String command = "";
        Options options;
        final List<FileResult> files = new ArrayList<>();
        long wallNanos;

        int count(String status) {
            int n = 0;
            for (FileResult f : files) {
                if (f.status.equals(status)) {
                    n++;
                }
            }
            return n;
        }

        int soundness() {
            int n = 0;
            for (FileResult f : files) {
                n += f.soundness;
            }
            return n;
        }

        int completeness() {
            int n = 0;
            for (FileResult f : files) {
                n += f.completeness;
            }
            return n;
        }

        /** The gate value: every file clean. */
        boolean clean() {
            for (FileResult f : files) {
                if (!f.clean()) {
                    return false;
                }
            }
            return true;
        }

        /** No wrong verdict anywhere (must hold at every commit). */
        boolean sound() {
            return soundness() + completeness() == 0;
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // One file
    // ---------------------------------------------------------------------------------------------------

    /**
     * The comparison of one export under the given options; never throws for a property of the input, of the
     * machine or of the heap. The reference engine runs over the file first and is emptied, then the machine
     * ({@link M8Lanes}): the two never hold their memory at the same time.
     */
    static FileResult compare(String path, Options options) {
        final M8Lanes.Options lo = new M8Lanes.Options();
        lo.fileMillis = options.fileMillis;
        lo.linesPerFile = options.linesPerFile;
        lo.refBytes = options.refBytes;
        lo.refDeclines = options.refDeclines;
        lo.inlineLanes = true;
        lo.holder = new M8Harness[1];
        final List<M8Lanes.Spec> specs = Collections.singletonList(new M8Lanes.Spec("diff", options.config));
        final M8Lanes.FileRun[] out = new M8Lanes.FileRun[1];
        Thread t = new Thread(null, () -> out[0] = M8Lanes.run(path, specs, lo), "m8-diff", 2L << 20);
        t.setDaemon(true);
        t.start();
        boolean hung = false;
        try {
            // the passes stop by themselves between two units once the limit is over; the margin is for a
            // declaration that is being checked then (its own deadline is Config.checkMillis)
            t.join(options.fileMillis + Math.min(options.fileMillis, 30_000));
            if (t.isAlive()) {
                lo.cancel = true;
                M8Harness h = lo.holder[0];
                if (h != null) {
                    h.cancel(); // every engine gives up at its next poll
                }
                t.join(20_000);
                hung = t.isAlive();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            hung = true;
        }
        M8Lanes.FileRun run = out[0];
        if (hung || run == null) {
            FileResult r = new FileResult();
            r.path = path;
            r.status = "TIMEOUT";
            r.first = "no answer within " + options.fileMillis / 1000 + " s (the thread did not react to the cancellation: a loop without ticks)";
            return r;
        }
        return of(run, 0);
    }

    /** The result of lane {@code i} of a run against the reference engine; a run that died outside the lanes is a CRASH. */
    static FileResult of(M8Lanes.FileRun run, int i) {
        if (run.crash != null || run.lanes.length <= i || run.lanes[i].diff == null) {
            FileResult r = new FileResult();
            r.path = run.path;
            r.crash = run.crash != null ? run.crash : new IllegalStateException("the lane did not run");
            r.memory = run.memory();
            return finish(r);
        }
        return run.lanes[i].diff;
    }

    /** Settles a file by the official verdict where the reference engine declined, and sets its status. */
    static FileResult finish(FileResult r) {
        if (r.status.equals("LOADER")) {
            return r;
        }
        if (r.crash == null && r.refDeclined > 0) {
            settle(r);
        }
        if (r.crash != null) {
            r.status = "CRASH";
            r.first = "crash: " + r.crash + at(r.crash);
        } else if (r.wrong()) {
            r.status = "WRONG";
        } else if (r.timeout) {
            r.status = "TIMEOUT";
            r.first = "the file's time limit ended the comparison after " + r.compared + " units"
                    + (r.first.isEmpty() ? "" : "; first difference before that: " + r.first);
        } else if (r.declined > 0) {
            r.status = "DECLINED";
        } else if (r.refDeclined > 0) {
            r.status = "CLEAN*";
        }
        return r;
    }

    /** The official verdict of an export: the arena's classification, which the official checker confirms for all 230 tests (work/oracle). */
    static int official(String path) {
        return path.contains("/bad/") ? Outcome.REJECT : Outcome.ACCEPT;
    }

    /**
     * A file in which the reference engine declined units the machine decided: the machine's verdict of the
     * file (verdict rule: its first unit that is not accepted) is held against the official verdict.
     */
    private static void settle(FileResult r) {
        int expected = official(r.path);
        if (r.machineVerdict == expected) {
            r.settled = "the machine's verdict of the file (" + Outcome.name(r.machineVerdict) + ") is the official one";
        } else if (r.machineVerdict == Outcome.DECLINE) {
            r.settled = "the machine declines the file; official verdict " + Outcome.name(expected);
        } else {
            if (r.machineVerdict == Outcome.ACCEPT) {
                r.soundness++;
            } else {
                r.completeness++;
            }
            r.settled = "WRONG against the official verdict: machine " + Outcome.name(r.machineVerdict) + ", official " + Outcome.name(expected);
            String line = (r.machineVerdict == Outcome.ACCEPT ? "SOUNDNESS   " : "COMPLETENESS") + " file: " + r.settled
                    + " (the reference engine declined " + r.refDeclined + " units)";
            r.lines.add(0, line);
            r.first = line;
        }
    }

    private static String at(Throwable e) {
        StackTraceElement[] st = e.getStackTrace();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < st.length && i < 5; i++) {
            sb.append(" < ").append(st[i]);
        }
        return sb.toString();
    }

    /**
     * The machine's reason for a difference without what is particular to the declaration: the reason,
     * the machine code, and the exception that ended the check if there was one (for a stub: the name of
     * the method that is not implemented), so that equal causes can be counted.
     */
    static String reasonKey(Reason reason, String message, String declName) {
        String m = message == null ? "" : message;
        String needle = " at '" + declName + "'";
        int i = m.indexOf(needle);
        if (i >= 0) {
            m = m.substring(0, i) + m.substring(i + needle.length());
        }
        int cut = m.indexOf(" < ");
        if (cut >= 0) {
            m = m.substring(0, cut);
        }
        // what is particular to the declaration in a limit message (M6's Machine.limitDetail): the sizes in use
        int use = m.indexOf("; in use:");
        if (use >= 0) {
            m = m.substring(0, use);
        }
        m = m.replace("java.lang.", "").trim();
        String r = String.valueOf(reason);
        if (m.startsWith(r)) {
            m = m.substring(r.length()).trim();
        }
        m = clip(m, 200);
        return m.isEmpty() ? r : r + " " + m;
    }

    static String clip(String s, int n) {
        s = s.replace('\n', ' ');
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    // ---------------------------------------------------------------------------------------------------
    // Corpora
    // ---------------------------------------------------------------------------------------------------

    /** Every {@code .ndjson} below the given files and directories (relative to the project root), sorted. */
    static List<String> exports(String... roots) {
        List<String> out = new ArrayList<>();
        Path root = Fixture.root();
        for (String r : roots) {
            Path p = root.resolve(r);
            if (Files.isRegularFile(p)) {
                out.add(r);
            } else if (Files.isDirectory(p)) {
                try (Stream<Path> s = Files.walk(p)) {
                    s.filter(f -> f.toString().endsWith(".ndjson") && Files.isRegularFile(f))
                            .forEach(f -> out.add(root.relativize(f).toString()));
                } catch (IOException e) {
                    throw new IllegalStateException("cannot list " + p, e);
                }
            }
        }
        Collections.sort(out);
        return out;
    }

    static final String TESTS = "work/arena-site/tests/";

    /**
     * The corpora of the gates (ISA section 11; WORK-PACKAGES section 5) by report name: {@code tutorial}
     * and {@code bugs} (v0, first part), {@code all} (the 230 published tests), {@code init}; {@code std}
     * (v1); the Cedar-level fast benchmarks {@code cedar} (the axiomatised hot slice) and
     * {@code reachability} (owner note 9). Null for an unknown name.
     */
    static String[] corpus(String label) {
        switch (label) {
            case "tutorial":
                return new String[] {TESTS + "good/tutorial", TESTS + "bad/tutorial"};
            case "bugs":
                return new String[] {TESTS + "good/bugs", TESTS + "bad/bugs"};
            case "all":
                return new String[] {TESTS + "good", TESTS + "bad"};
            case "init":
                return new String[] {"work/exports/init.ndjson"};
            case "std":
                return new String[] {"work/exports/std.ndjson"};
            case "cedar":
                return new String[] {"work/workloads/slices/cedar-hot-ax.ndjson"};
            case "reachability":
                return new String[] {"work/exports/reachability.ndjson"};
            default:
                return null;
        }
    }

    /** The options of a report run from system properties ({@code m8.hostDepth}, {@code m8.switches}, ...; see M8Main). */
    static Options optionsFromProperties() {
        Options o = new Options();
        o.config.hostDepth = Integer.getInteger("m8.hostDepth", Op.HOST_DEPTH);
        o.config.switches = Debug.parseSwitches(System.getProperty("m8.switches", ""));
        o.config.debug = Debug.parseModes(System.getProperty("m8.debug", ""));
        o.config.maxSteps = Long.getLong("m8.maxSteps", o.config.maxSteps);
        o.config.checkMillis = 1000L * Long.getLong("m8.checkSeconds", o.config.checkMillis / 1000);
        o.fileMillis = 1000L * Long.getLong("m8.fileSeconds", o.fileMillis / 1000);
        o.config.digest = false;
        return o;
    }

    /**
     * Runs a named or ad-hoc corpus and writes {@code work/status/machine-diff-LABEL[SUFFIX].txt}
     * ({@code -Dm8.suffix}); the head of the report is printed.
     *
     * @return the report, or null if none of the roots holds an export
     */
    static Report report(String label, String... roots) throws IOException {
        List<String> paths = exports(roots);
        if (paths.isEmpty()) {
            return null;
        }
        String name = reportName(label);
        Report rep = run(name, paths, optionsFromProperties());
        rep.command = "rlvm-machine/src/test/resources/m8/report.sh " + (corpus(label) != null ? label : "diff " + label + " " + String.join(" ", roots));
        Path out = write(rep);
        String text = render(rep);
        int nl = 0;
        int cut = 0;
        while (cut < text.length() && nl < 8) {
            if (text.charAt(cut++) == '\n') {
                nl++;
            }
        }
        System.out.println(text.substring(0, cut) + "report: " + out);
        return rep;
    }

    /** Runs the comparison over the given exports. */
    static Report run(String label, List<String> paths, Options options) {
        Report rep = new Report();
        rep.label = label;
        rep.options = options;
        long t0 = System.nanoTime();
        if (options.jobs > 1 && paths.size() > 1 && options.refDeclines == null && options.refBytes == 0) {
            List<M8Lanes.Spec> specs = Collections.singletonList(new M8Lanes.Spec("diff", options.config));
            for (M8Lanes.FileRun r : M8Gate.runAll("diff " + label, paths, specs, options.jobs, options.fileMillis).values()) {
                rep.files.add(of(r, 0));
            }
            rep.wallNanos = System.nanoTime() - t0;
            return rep;
        }
        int i = 0;
        for (String p : paths) {
            FileResult f = compare(p, options);
            rep.files.add(f);
            i++;
            boolean loud = f.wrong() || f.status.equals("CRASH") || f.status.equals("TIMEOUT");
            if (options.progress && (loud || i % 25 == 0 || i == paths.size())) {
                System.err.println("m8 diff " + label + " [" + i + "/" + paths.size() + "] " + f.status + " " + p
                        + (f.first.isEmpty() ? "" : " -- " + clip(f.first, 200)));
            }
        }
        rep.wallNanos = System.nanoTime() - t0;
        return rep;
    }

    /**
     * Every {@code .ndjson} below a directory against the reference engine; the report lists every file
     * that is not diff-clean, its first difference and the command that reproduces it.
     */
    static String directory(String dir) {
        return render(run(label(dir), exports(dir), new Options()));
    }

    private static String label(String dir) {
        String s = dir.replaceAll("^work/arena-site/tests/?", "").replace('/', '-');
        return s.isEmpty() ? "all" : s;
    }

    // ---------------------------------------------------------------------------------------------------
    // The report
    // ---------------------------------------------------------------------------------------------------

    /** The report as text (the content of {@code work/status/machine-diff-LABEL.txt}). */
    static String render(Report rep) {
        StringBuilder sb = new StringBuilder();
        int files = rep.files.size();
        long units = 0;
        long checks = 0;
        long agree = 0;
        long declined = 0;
        long refDeclined = 0;
        int refDeclinedFiles = 0;
        long acc = 0;
        long rej = 0;
        long dec = 0;
        long ticks = 0;
        long calls = 0;
        long frames = 0;
        long refNanos = 0;
        long machineNanos = 0;
        Map<String, int[]> reasons = new TreeMap<>();
        for (FileResult f : rep.files) {
            units += f.compared;
            checks += f.engineChecks;
            agree += f.agree;
            declined += f.declined;
            refDeclined += f.refDeclined;
            refDeclinedFiles += f.refDeclined > 0 ? 1 : 0;
            acc += f.accepted;
            rej += f.rejected;
            dec += f.engineDeclined;
            ticks += f.ticks;
            calls += f.calls;
            frames += f.framesWritten;
            refNanos += f.refNanos;
            machineNanos += f.machineNanos;
            for (Map.Entry<String, Integer> e : f.reasons.entrySet()) {
                int[] c = reasons.computeIfAbsent(e.getKey(), k -> new int[2]);
                c[0] += e.getValue();
                c[1]++;
            }
        }
        int clean = rep.count("CLEAN") + rep.count("LOADER") + rep.count("CLEAN*");
        boolean gate = rep.clean();
        sb.append("# machine-diff-").append(rep.label).append(": engine \"machine\" against the reference engine, per declaration\n");
        sb.append("# generated ").append(Instant.now().truncatedTo(ChronoUnit.SECONDS)).append(" by: ").append(rep.command).append('\n');
        sb.append("# machine under test: ").append(rep.options.config).append("; ").append(lastPublication()).append('\n');
        sb.append("#\n");
        sb.append("# GATE ").append(rep.label).append(": ").append(gate ? "DIFF-CLEAN" : rep.sound() ? "NOT CLEAN (no wrong verdict)" : "WRONG VERDICTS")
                .append(" -- files ").append(files).append(": clean ").append(clean).append(", declined ").append(rep.count("DECLINED"))
                .append(", WRONG ").append(rep.count("WRONG")).append(", crash ").append(rep.count("CRASH")).append(", timeout ")
                .append(rep.count("TIMEOUT")).append('\n');
        sb.append("# units compared ").append(units).append(": agree ").append(agree).append(", machine declined ").append(declined)
                .append(", SOUNDNESS ").append(rep.soundness()).append(", COMPLETENESS ").append(rep.completeness()).append('\n');
        if (refDeclined > 0) {
            sb.append("# reference engine declined ").append(refDeclined).append(" units that the machine decided, in ").append(refDeclinedFiles)
                    .append(" files: not compared; these files are settled by the official verdict (status CLEAN* = the machine's\n")
                    .append("#   verdict of the file is the official one)\n");
        }
        sb.append("# engine checks (axiom / def / theorem / opaque that reached Session.check) ").append(checks).append(": machine accepted ")
                .append(acc).append(", rejected ").append(rej).append(", declined ").append(dec).append('\n');
        sb.append(String.format(Locale.ROOT,
                "# machine: %d ticks, %d call events, %d frames written (%s of the calls); in-process time: machine %.2f s, reference %.2f s, total %.1f s (not a benchmark)%n",
                ticks, calls, frames, calls == 0 ? "n/a" : String.format(Locale.ROOT, "%.4f%%", 100.0 * frames / calls),
                machineNanos / 1e9, refNanos / 1e9, rep.wallNanos / 1e9));
        sb.append("#\n");
        if (!reasons.isEmpty()) {
            sb.append("# What stopped the machine (declarations, files), most frequent first:\n");
            List<Map.Entry<String, int[]>> list = new ArrayList<>(reasons.entrySet());
            list.sort((a, b) -> b.getValue()[0] - a.getValue()[0]);
            int shown = 0;
            for (Map.Entry<String, int[]> e : list) {
                if (shown++ == 40) {
                    sb.append("#   ... ").append(list.size() - 40).append(" more reasons\n");
                    break;
                }
                sb.append(String.format(Locale.ROOT, "#   %6d %5d  %s%n", e.getValue()[0], e.getValue()[1], e.getKey()));
            }
            sb.append("#\n");
        }
        // wrong verdicts first: they are what must never be there
        boolean any = false;
        for (FileResult f : rep.files) {
            if (f.wrong() || f.status.equals("CRASH") || f.status.equals("TIMEOUT")) {
                if (!any) {
                    sb.append("# WRONG VERDICTS, CRASHES AND TIMEOUTS\n");
                    any = true;
                }
                detail(sb, f, rep);
            }
        }
        if (any) {
            sb.append("#\n");
        }
        sb.append("# Every file. Columns: status, units compared, engine checks, agree, declined, SOUNDNESS, COMPLETENESS,\n");
        sb.append("# reference declined, reference verdict, machine verdict (each side alone, verdict rule), ticks, call events, file\n");
        for (FileResult f : rep.files) {
            sb.append(String.format(Locale.ROOT, "%-8s %6d %6d %6d %6d %3d %3d %3d  %-7s %-7s %12d %12d  %s%s%n", f.status, f.compared,
                    f.engineChecks, f.agree, f.declined, f.soundness, f.completeness, f.refDeclined, Outcome.name(f.refVerdict),
                    Outcome.name(f.machineVerdict), f.ticks, f.calls, shortName(f.path), f.settled.isEmpty() ? "" : "   [" + f.settled + "]"));
        }
        sb.append("#\n# First difference of every file that is not clean (and how to reproduce it)\n");
        int shown = 0;
        for (FileResult f : rep.files) {
            if (f.clean() || f.wrong() || f.status.equals("CRASH") || f.status.equals("TIMEOUT")) {
                continue;
            }
            if (shown++ == 400) {
                sb.append("... more files not shown\n");
                break;
            }
            detail(sb, f, rep);
        }
        return sb.toString();
    }

    private static void detail(StringBuilder sb, FileResult f, Report rep) {
        sb.append(f.status).append(' ').append(shortName(f.path)).append('\n');
        if (f.lines.isEmpty()) {
            sb.append("    ").append(f.first).append('\n');
        }
        int notClean = 0;
        for (FileResult x : rep.files) {
            notClean += x.clean() ? 0 : 1;
        }
        // one line per file in a long listing; every kept line when few files are left (a corpus of one big file)
        int n = f.wrong() || notClean <= 20 ? f.lines.size() : Math.min(1, f.lines.size());
        for (int i = 0; i < n; i++) {
            sb.append("    ").append(f.lines.get(i)).append('\n');
        }
        int more = Math.max(f.lines.size(), f.declined + f.soundness + f.completeness + f.refDeclined) - n;
        if (more > 0) {
            sb.append("    ... and ").append(more).append(" more differing units\n");
        }
        sb.append("    reproduce: ").append(reproduce(f.path, f.firstUnit)).append('\n');
    }

    /** The command that shows one file (and one unit of it) in detail: {@code M8DiffOneTest}. */
    static String reproduce(String path, int unit) {
        return "scripts/machine-sandbox.sh test <WP> -Dtest=M8DiffOneTest -Dm8.file=" + path
                + (unit >= 0 ? " -Dm8.unit=" + unit : "");
    }

    static String shortName(String path) {
        return path.replaceAll("^work/arena-site/tests/", "").replaceAll("^work/", "");
    }

    /**
     * The sandbox whose code is being measured ("M5"), or null for the main tree. A report made from a
     * sandbox (through {@code report.sh --tree work/sandbox/M5/rlvm-machine}, which sets {@code m8.tree}, or
     * by a slow test run inside the sandbox) must not pass for a report on the main tree: it says so in its
     * header and gets the suffix {@code -M5} unless {@code -Dm8.suffix} names another.
     */
    static String sandbox() {
        String tree = System.getProperty("m8.tree");
        if (tree == null) {
            try {
                tree = String.valueOf(Diff.class.getProtectionDomain().getCodeSource().getLocation().getPath());
            } catch (RuntimeException e) {
                return null;
            }
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/work/sandbox/([^/]+)/").matcher(tree + "/");
        if (m.find()) {
            return m.group(1);
        }
        return System.getProperty("m8.tree") != null ? "tree" : null;
    }

    /** The name of a report: the corpus label and {@code -Dm8.suffix}, or the sandbox's default suffix ({@link #sandbox()}). */
    static String reportName(String label) {
        String suffix = System.getProperty("m8.suffix");
        if (suffix == null) {
            String sb = sandbox();
            suffix = sb == null ? "" : "-" + sb;
        }
        return label + suffix;
    }

    /** What was measured: the main tree with its last publication, or a sandbox (never to be read as the main tree). */
    static String lastPublication() {
        String sb = sandbox();
        if (sb != null) {
            String tree = System.getProperty("m8.tree");
            return "MEASURED: THE SANDBOX " + (tree != null ? tree : "work/sandbox/" + sb + "/rlvm-machine")
                    + ", NOT THE MAIN TREE (" + mainTreePublication() + ")";
        }
        return mainTreePublication();
    }

    /** The last publication into the main tree: at the moment the tree under test was copied ({@code -Dm8.published}, set by report.sh), else now. */
    static String mainTreePublication() {
        String copied = System.getProperty("m8.published");
        if (copied != null && !copied.isEmpty()) {
            return "the main tree as copied after: " + copied;
        }
        Path log = Fixture.root().resolve("work/sandbox/publish.log");
        try {
            List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            for (int i = lines.size() - 1; i >= 0; i--) {
                String l = lines.get(i);
                if (l.contains("fast tests green")) {
                    int cut = l.indexOf(';');
                    String what = cut > 0 ? l.substring(0, cut) : l;
                    // "2026-11-24T14:30:18Z publish M4: 36 files [Cargs.java Conv.java ...]": the first files are enough
                    return "last publication into the main tree: " + (what.length() > 150 ? what.substring(0, 150) + " ...]" : what);
                }
            }
        } catch (IOException | RuntimeException e) {
            // no log: say so
        }
        return "no publication log";
    }

    /** Writes the report to {@code work/status/machine-diff-LABEL.txt} and returns the file. */
    static Path write(Report rep) throws IOException {
        Path out = Fixture.root().resolve("work/status/machine-diff-" + rep.label + ".txt");
        Files.createDirectories(out.getParent());
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
        Files.write(tmp, render(rep).getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        return out;
    }
}
