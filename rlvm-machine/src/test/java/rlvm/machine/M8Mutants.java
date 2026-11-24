package rlvm.machine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import rlvm.api.Outcome;
import rlvm.testkit.MutantCorpus;

/**
 * The mutant corpora with the machine ({@code rlvm-testkit}: about 17,800 seeded edits of the published
 * tests and of the prelude, each classified by the official checker). Owner: M8.
 *
 * <pre>
 * rlvm-machine/src/test/resources/m8/report.sh mutants --switches v0 --switches warm,gated,expect [--corpus tutorial] [--launcher-sample 40]
 * </pre>
 *
 * Per switch set (the generic tier and every set a package proposes for the default) and per mutant:
 * <ol>
 *   <li><b>the final verdict</b> of the real pipeline with {@code -X engine=machine -X machine.switches=..}
 *       ({@link M8Pipeline}: the launcher's entry in this JVM) against the official checker:
 *       SOUNDNESS = accepted although the official checker rejects (must be 0), COMPLETENESS = rejected
 *       although it accepts (must be 0), a decline is never wrong; and against the same pipeline with
 *       the reference engine (the final verdict must be the same mutant for mutant);
 *   <li><b>the machine's own answers before the driver's re-check</b>: from the driver's statistics
 *       MACHINE-INCOMPLETE (the machine rejected a unit the reference kernel accepts), WARM-ANOMALY and
 *       disagreements; and, because the driver trusts an ACCEPT of the machine, every declaration again
 *       in-process against the reference engine ({@link M8Lanes}): accepted where the reference rejects,
 *       rejected where it accepts, declined. Each such case is a machine bug even when the final verdict
 *       is saved by the re-check.
 * </ol>
 * {@code -Dm8.mutants.launcherSample=N} runs every N-th mutant through the real launcher as well
 * ({@code scripts/check-light.sh}, a JVM per mutant) and holds the in-process verdict against it.
 */
final class M8Mutants {
    private M8Mutants() {}

    /** Corpus name -> directory (the four default corpora of {@code testkit corpus --all}). */
    static final Map<String, String> CORPORA = corpora();

    private static Map<String, String> corpora() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("tutorial", "work/mutants");
        m.put("bugs", "work/mutants-bugs-other");
        m.put("prelude", "work/mutants-prelude");
        m.put("mdata", "work/mutants-mdata");
        return m;
    }

    /** One switch set of a campaign. */
    static final class SwitchSet {
        final String name;
        final int switches;
        final int sessionMib;

        SwitchSet(String spec) {
            String s = spec.trim();
            if (s.equals("v0") || s.equals("generic")) {
                s = "";
            }
            this.switches = Debug.parseSwitches(s);
            this.name = switches == 0 ? "v0 (generic tier)" : Debug.switchNames(switches);
            this.sessionMib = (switches & Op.SW_WARM) != 0 ? Integer.getInteger("m8.sessionMib", 4) : 0;
        }

        Map<String, String> settings() {
            return M8Pipeline.machine(Debug.switchNames(switches), "", sessionMib);
        }
    }

    /** The sums of one switch set over one corpus. */
    static final class Tally {
        int mutants;
        // final verdict of the pipeline against the official checker
        int soundness;
        int completeness;
        int agree;
        int declined;
        int errors;
        int answeredUndecided;
        /** Mutants whose final verdict with the machine is not the one with the reference engine. */
        int notAsReference;
        // the machine's own answers in the driver
        long machineAccepted;
        long machineRejected;
        long machineDeclined;
        long incomplete;
        long warmAnomalies;
        long disagreements;
        // in-process, per declaration against the reference engine
        long units;
        long checks;
        long unitSoundness;
        long unitCompleteness;
        long unitDeclined;
        int filesMachineVerdictDiffers;
        int harnessCrashes;
        // the launcher sample
        int sampled;
        int sampleDiffers;
        final List<String> lines = new ArrayList<>();

        void add(Tally t) {
            mutants += t.mutants;
            soundness += t.soundness;
            completeness += t.completeness;
            agree += t.agree;
            declined += t.declined;
            errors += t.errors;
            answeredUndecided += t.answeredUndecided;
            notAsReference += t.notAsReference;
            machineAccepted += t.machineAccepted;
            machineRejected += t.machineRejected;
            machineDeclined += t.machineDeclined;
            incomplete += t.incomplete;
            warmAnomalies += t.warmAnomalies;
            disagreements += t.disagreements;
            units += t.units;
            checks += t.checks;
            unitSoundness += t.unitSoundness;
            unitCompleteness += t.unitCompleteness;
            unitDeclined += t.unitDeclined;
            filesMachineVerdictDiffers += t.filesMachineVerdictDiffers;
            harnessCrashes += t.harnessCrashes;
            sampled += t.sampled;
            sampleDiffers += t.sampleDiffers;
        }

        /** Nothing that must be 0 is above 0. */
        boolean ok() {
            return soundness == 0 && completeness == 0 && errors == 0 && notAsReference == 0 && incomplete == 0 && warmAnomalies == 0
                    && disagreements == 0 && unitSoundness == 0 && unitCompleteness == 0 && harnessCrashes == 0 && sampleDiffers == 0;
        }
    }

    private static synchronized void line(Tally t, String s) {
        if (t.lines.size() < 400) {
            t.lines.add(s);
        }
    }

    /** Runs the campaign from system properties and writes {@code work/status/machine-mutants[-SUFFIX].md}. */
    static boolean report() throws IOException {
        List<SwitchSet> sets = new ArrayList<>();
        for (String s : System.getProperty("m8.mutants.sets", "v0").split(";")) {
            sets.add(new SwitchSet(s));
        }
        List<String> names = new ArrayList<>();
        String only = System.getProperty("m8.mutants.corpus");
        for (String c : CORPORA.keySet()) {
            if (only == null || List.of(only.split(",")).contains(c)) {
                names.add(c);
            }
        }
        int limit = Integer.getInteger("m8.mutants.limit", Integer.MAX_VALUE);
        int sampleEvery = Integer.getInteger("m8.mutants.launcherSample", 0);
        String dist = System.getProperty("m8.mutants.dist", "");
        int jobs = Integer.getInteger("m8.jobs", M8Gate.defaultJobs());
        if (!M8Pipeline.available()) {
            System.err.println("M8Mutants: rlvm.main.RlvmChecker is not on the class path (run through report.sh)");
            return false;
        }
        M8Pipeline.quiet();
        long t0 = System.nanoTime();
        // corpus -> per set (index sets.size() = the reference engine's pipeline, the baseline)
        Map<String, Tally[]> tallies = new LinkedHashMap<>();
        Tally[] total = newTallies(sets.size() + 1);
        final Map<String, String> refSettings = Collections.singletonMap("engine", "reference");
        for (String corpus : names) {
            Path dir = Fixture.root().resolve(CORPORA.get(corpus));
            if (!Files.isRegularFile(dir.resolve("index.jsonl"))) {
                M8Pipeline.log.println("m8 mutants: no corpus " + dir + " (rlvm-testkit/bin/testkit corpus --all): skipped");
                continue;
            }
            List<MutantCorpus.Entry> entries = MutantCorpus.readIndex(dir);
            if (entries.size() > limit) {
                // an even sample over the whole corpus (every kind of edit, every source)
                List<MutantCorpus.Entry> cut = new ArrayList<>();
                for (int i = 0; i < limit; i++) {
                    cut.add(entries.get((int) ((long) i * entries.size() / limit)));
                }
                entries = cut;
            }
            final Tally[] ts = newTallies(sets.size() + 1);
            tallies.put(corpus, ts);
            final List<MutantCorpus.Entry> work = entries;
            final AtomicInteger next = new AtomicInteger();
            final AtomicInteger doneCount = new AtomicInteger();
            final long c0 = System.nanoTime();
            List<Thread> threads = new ArrayList<>();
            for (int w = 0; w < Math.min(jobs, work.size()); w++) {
                Thread t = new Thread(null, () -> {
                    int i;
                    while ((i = next.getAndIncrement()) < work.size()) {
                        try {
                            one(dir, work.get(i), sets, ts, refSettings, sampleEvery > 0 && i % sampleEvery == 0 ? dist : null);
                        } catch (Throwable e) { // counted, never lost
                            synchronized (ts) {
                                ts[0].harnessCrashes++;
                            }
                            line(ts[0], "CRASH of the campaign on " + work.get(i).name + ": " + e);
                        }
                        int n = doneCount.incrementAndGet();
                        if (n % 1000 == 0 || n == work.size()) {
                            M8Pipeline.log.println(String.format(Locale.ROOT, "m8 mutants %s [%d/%d, %.0f s]", corpus, n, work.size(), (System.nanoTime() - c0) / 1e9));
                        }
                    }
                }, "m8-mutants", 2L << 20);
                t.setDaemon(true);
                threads.add(t);
                t.start();
            }
            for (Thread t : threads) {
                try {
                    t.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            for (int i = 0; i < ts.length; i++) {
                total[i].add(ts[i]);
            }
            // what is known so far (a campaign runs for a long time on a loaded host; nothing of it is lost if it is stopped)
            Path part = Fixture.root().resolve("work/status/machine-mutants" + Diff.reportName("") + ".running.md");
            Files.createDirectories(part.getParent());
            Files.write(part, ("NOT COMPLETE: the corpora done so far (" + tallies.keySet() + " of " + names + ")\n\n"
                    + render(sets, tallies, total, (System.nanoTime() - t0) / 1e9, jobs, limit, sampleEvery)).getBytes(StandardCharsets.UTF_8));
        }
        String text = render(sets, tallies, total, (System.nanoTime() - t0) / 1e9, jobs, limit, sampleEvery);
        Path out = Fixture.root().resolve("work/status/machine-mutants" + Diff.reportName("") + ".md");
        Files.createDirectories(out.getParent());
        Files.write(out, text.getBytes(StandardCharsets.UTF_8));
        Files.deleteIfExists(out.resolveSibling("machine-mutants" + Diff.reportName("") + ".running.md"));
        System.out.println(text);
        System.out.println("report: " + out);
        boolean ok = !tallies.isEmpty();
        for (int i = 0; i < sets.size(); i++) {
            ok &= total[i].ok();
        }
        return ok;
    }

    private static Tally[] newTallies(int n) {
        Tally[] ts = new Tally[n];
        for (int i = 0; i < n; i++) {
            ts[i] = new Tally();
        }
        return ts;
    }

    private static String status(int outcome) {
        return outcome == Outcome.ACCEPT ? "accepted" : outcome == Outcome.REJECT ? "rejected" : outcome == Outcome.DECLINE ? "declined" : "error";
    }

    /** One mutant: the pipeline with the reference engine and with every switch set, then every declaration in-process. */
    private static void one(Path corpusDir, MutantCorpus.Entry e, List<SwitchSet> sets, Tally[] ts, Map<String, String> refSettings, String sampleDist) {
        Path file = corpusDir.resolve("suite").resolve(e.bucket).resolve(e.name + ".ndjson");
        String rel = Fixture.root().relativize(file.toAbsolutePath()).toString();
        int n = sets.size();
        M8Pipeline.Result ref = M8Pipeline.check(rel, 1, refSettings);
        M8Pipeline.Result[] rs = new M8Pipeline.Result[n];
        for (int i = 0; i < n; i++) {
            rs[i] = M8Pipeline.check(rel, 1, sets.get(i).settings());
        }
        // every declaration against the reference engine, one lane per switch set
        List<M8Lanes.Spec> specs = new ArrayList<>();
        for (SwitchSet s : sets) {
            M8Harness.Config c = new M8Harness.Config(s.name);
            c.switches = s.switches;
            c.digest = false;
            c.checkMillis = 60_000;
            if (s.sessionMib > 0) {
                c.sessionBytes = (long) s.sessionMib << 20;
            }
            specs.add(new M8Lanes.Spec(s.name, c));
        }
        M8Lanes.Options o = new M8Lanes.Options();
        o.inlineLanes = true;
        o.fileMillis = 300_000;
        M8Lanes.FileRun run = M8Lanes.run(rel, specs, o);
        int[] launcher = null;
        if (sampleDist != null && !sampleDist.isEmpty()) {
            launcher = new int[n];
            for (int i = 0; i < n; i++) {
                launcher[i] = launcher(sampleDist, file, sets.get(i));
            }
        }
        synchronized (ts) {
            tally(ts[n], e, ref, null, null, -2, rel, "reference", refSettings);
            for (int i = 0; i < n; i++) {
                tally(ts[i], e, rs[i], ref, Diff.of(run, i), launcher == null ? -2 : launcher[i], rel, sets.get(i).name, sets.get(i).settings());
            }
        }
    }

    private static void tally(Tally t, MutantCorpus.Entry e, M8Pipeline.Result r, M8Pipeline.Result ref, Diff.FileResult d, int launcher,
            String rel, String set, Map<String, String> settings) {
        t.mutants++;
        String st = status(r.outcome);
        String cmd = "\n    reproduce: " + M8Pipeline.commandLine(rel, 1, settings);
        if (st.equals("error")) {
            t.errors++;
            line(t, "ERROR [" + set + "] " + e.name + ": " + r.error + cmd);
        } else if (st.equals("accepted") && e.bucket.equals("bad")) {
            t.soundness++;
            line(t, "SOUNDNESS [" + set + "] " + e.name + " (" + e.kind + ": " + e.description + "): accepted, the official checker rejects (" + e.officialStage
                    + ")" + cmd);
        } else if (st.equals("rejected") && e.bucket.equals("good")) {
            t.completeness++;
            line(t, "COMPLETENESS [" + set + "] " + e.name + " (" + e.kind + ": " + e.description + "): rejected, the official checker accepts" + cmd);
        } else if (st.equals("declined")) {
            t.declined++;
        } else if (e.bucket.equals("either")) {
            t.answeredUndecided++;
        } else {
            t.agree++;
        }
        t.machineAccepted += r.machineAccepted;
        t.machineRejected += r.machineRejected;
        t.machineDeclined += r.machineDeclined;
        t.incomplete += r.incomplete;
        t.warmAnomalies += r.warmAnomalies;
        t.disagreements += r.disagreements;
        if (r.machineWrong() > 0) {
            line(t, "MACHINE AGAINST THE REFERENCE KERNEL IN THE DRIVER [" + set + "] " + e.name + ": MACHINE-INCOMPLETE " + r.incomplete + ", WARM-ANOMALY "
                    + r.warmAnomalies + ", disagreements " + r.disagreements + " (final verdict " + st + ")" + cmd);
        }
        if (ref != null && ref.outcome != r.outcome) {
            t.notAsReference++;
            line(t, "FINAL VERDICT NOT THE REFERENCE PIPELINE'S [" + set + "] " + e.name + ": " + st + " against " + status(ref.outcome) + cmd);
        }
        if (d != null) {
            if (d.status.equals("CRASH") || d.status.equals("TIMEOUT")) {
                t.harnessCrashes++;
                line(t, d.status + " in-process [" + set + "] " + e.name + ": " + d.first + "\n    reproduce: " + Diff.reproduce(rel, d.firstUnit));
            }
            t.units += d.compared;
            t.checks += d.engineChecks;
            t.unitSoundness += d.soundness;
            t.unitCompleteness += d.completeness;
            t.unitDeclined += d.declined;
            if (d.machineVerdict != d.refVerdict) {
                t.filesMachineVerdictDiffers++;
            }
            if (d.wrong()) {
                StringBuilder sb = new StringBuilder("MACHINE BUG (its own answer against the reference engine) [" + set + "] " + e.name + " (" + e.kind + ": "
                        + e.description + ")");
                for (int k = 0; k < d.lines.size() && k < 3; k++) {
                    sb.append("\n    ").append(d.lines.get(k));
                }
                sb.append("\n    reproduce: ").append(Diff.reproduce(rel, d.firstUnit));
                line(t, sb.toString());
            }
        }
        if (launcher != -2) {
            t.sampled++;
            if (launcher != r.outcome) {
                t.sampleDiffers++;
                line(t, "LAUNCHER SAMPLE DIFFERS [" + set + "] " + e.name + ": in-process " + st + ", launcher exit status " + launcher + cmd);
            }
        }
    }

    /** The real launcher on one mutant ({@code scripts/check-light.sh} with the given distribution): its exit status, -1 on a failure to run. */
    private static int launcher(String dist, Path file, SwitchSet set) {
        try {
            ProcessBuilder pb = new ProcessBuilder(Fixture.root().resolve("scripts/check-light.sh").toString(), file.toAbsolutePath().toString());
            pb.environment().put("RLVM_DIST", dist);
            pb.environment().put("RLVM_ENGINE", "machine");
            StringBuilder args = new StringBuilder();
            args.append("-X machine.switches=").append(set.switches == 0 ? "none" : Debug.switchNames(set.switches));
            if (set.sessionMib > 0) {
                args.append(" -X session-mib=").append(set.sessionMib);
            }
            String levels = System.getProperty("rlvm.levels");
            if (levels != null) {
                args.append(" -X levels=").append(levels); // the re-check engine as in this JVM (report.sh --levels)
            }
            pb.environment().put("RLVM_CHECK_ARGS", args.toString().trim());
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            if (!p.waitFor(300, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return -1;
            }
            return p.exitValue();
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private static String render(List<SwitchSet> sets, Map<String, Tally[]> tallies, Tally[] total, double wall, int jobs, int limit, int sampleEvery) {
        StringBuilder sb = new StringBuilder();
        boolean ok = !tallies.isEmpty();
        for (int i = 0; i < sets.size(); i++) {
            ok &= total[i].ok();
        }
        sb.append("# machine-mutants: the mutant corpora with engine \"machine\"\n\n");
        long machineDeclines = 0;
        for (int i = 0; i < sets.size(); i++) {
            machineDeclines = Math.max(machineDeclines, total[i].unitDeclined);
        }
        sb.append("**RESULT: ").append(ok ? "PASS: SOUNDNESS 0, COMPLETENESS 0, and the machine's own answers never differ from the reference engine's"
                + (machineDeclines == 0 ? "" : " (it DECLINED up to " + machineDeclines + " declarations per switch set, which the reference kernel then decided:"
                        + " column \"machine declines\")")
                : "FAIL (see the columns in bold and the list at the end)").append("**\n\n");
        sb.append("- generated ").append(Instant.now().truncatedTo(ChronoUnit.SECONDS)).append(" by `")
                .append(System.getProperty("m8.command", "M8Main mutants")).append("`\n");
        sb.append("- measured: ").append(Diff.lastPublication()).append('\n');
        sb.append(String.format(Locale.ROOT, "- %d mutants at a time in one JVM, wall %.0f s%s%n", jobs, wall,
                limit == Integer.MAX_VALUE ? "" : "; AN EVEN SAMPLE of at most " + limit + " mutants per corpus, not the whole corpora"));
        sb.append("- reference engine (the oracle in-process, the re-check engine of the pipeline): universe levels `")
                .append(System.getProperty("rlvm.levels", "the published default of rlvm-ref")).append("`\n");
        sb.append("- final verdict: the real pipeline (`rlvm.main.RlvmChecker.checkExport`, what the launcher runs) with `-X engine=machine`, one worker, against the"
                + " classification by the official checker (leanchecker 4.36.0). SOUNDNESS = accepted although the official checker rejects;"
                + " COMPLETENESS = rejected although it accepts; a decline is never wrong.\n");
        sb.append("- the machine's own answers: (a) the driver's statistics of the same runs (it re-checks a rejection and a decline of the machine with the"
                + " reference kernel): MACHINE-INCOMPLETE = the machine rejected, the reference kernel accepts; (b) every declaration in-process against the"
                + " reference engine (the driver trusts an ACCEPT of the machine, so only this sees a wrong one).\n\n");
        for (int i = 0; i <= sets.size(); i++) {
            boolean reference = i == sets.size();
            sb.append("## ").append(reference ? "Baseline: the same pipeline with `-X engine=reference`" : "Switch set `" + sets.get(i).name + "`"
                    + (sets.get(i).sessionMib > 0 ? " (`-X session-mib=" + sets.get(i).sessionMib + "`)" : "")).append("\n\n");
            sb.append("| corpus | mutants | SOUNDNESS | COMPLETENESS | agree | declined | error | answered on undecided |");
            if (!reference) {
                sb.append(" final verdict not the reference pipeline's | machine accepted / rejected / declined (engine checks) | MACHINE-INCOMPLETE | WARM-ANOMALY |"
                        + " disagreements | declarations compared in-process | machine accepts, reference rejects | machine rejects, reference accepts |"
                        + " machine declines | files whose machine verdict is not the reference's |");
                if (sampleEvery > 0) {
                    sb.append(" launcher sample: differs / run |");
                }
            }
            sb.append("\n|---|--:|--:|--:|--:|--:|--:|--:|").append(reference ? "" : "--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|" + (sampleEvery > 0 ? "--:|" : ""))
                    .append('\n');
            for (Map.Entry<String, Tally[]> e : tallies.entrySet()) {
                row(sb, e.getKey() + " (`" + CORPORA.get(e.getKey()) + "`)", e.getValue()[i], reference, sampleEvery > 0);
            }
            row(sb, "**total**", total[i], reference, sampleEvery > 0);
            sb.append('\n');
        }
        sb.append("Published baseline of the reference engine through the launcher, a JVM per mutant (`work/status/mutants.md`, 12:42Z): tutorial 11,847 agree /"
                + " 413 declined, bugs 2,627 / 65, prelude 839 / 8, mdata 779 / 1,197: the in-process baseline above must show the same numbers.\n\n");
        sb.append("## Differences\n\n");
        List<String> all = new ArrayList<>();
        for (Tally[] ts : tallies.values()) {
            for (int i = 0; i < sets.size(); i++) {
                all.addAll(ts[i].lines);
            }
        }
        if (all.isEmpty()) {
            sb.append("None.\n");
        } else {
            sb.append("```\n");
            int shown = 0;
            for (String l : all) {
                if (shown++ == 60) {
                    sb.append("... ").append(all.size() - 60).append(" more\n");
                    break;
                }
                sb.append(l).append('\n');
            }
            sb.append("```\n");
        }
        return sb.toString();
    }

    private static void row(StringBuilder sb, String label, Tally t, boolean reference, boolean sample) {
        sb.append(String.format(Locale.ROOT, "| %s | %d | %s | %s | %d | %d | %s | %d |", label, t.mutants, bold(t.soundness), bold(t.completeness), t.agree,
                t.declined, bold(t.errors), t.answeredUndecided));
        if (!reference) {
            sb.append(String.format(Locale.ROOT, " %s | %d / %d / %d | %s | %s | %s | %d | %s | %s | %d | %d |", bold(t.notAsReference), t.machineAccepted,
                    t.machineRejected, t.machineDeclined, bold(t.incomplete), bold(t.warmAnomalies), bold(t.disagreements), t.units, bold(t.unitSoundness),
                    bold(t.unitCompleteness), t.unitDeclined, t.filesMachineVerdictDiffers));
            if (sample) {
                sb.append(String.format(Locale.ROOT, " %s / %d |", bold(t.sampleDiffers), t.sampled));
            }
        }
        sb.append('\n');
    }

    private static String bold(long n) {
        return n == 0 ? "0" : "**" + n + "**";
    }
}
