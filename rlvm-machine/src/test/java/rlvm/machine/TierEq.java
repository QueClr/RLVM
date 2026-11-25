package rlvm.machine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import rlvm.api.Outcome;

/**
 * Tier and convention equivalence (ISA section 11; *agentic-note*: rep-stepper-convention, H12): the same value ids, memo
 * contents, tick counts, outcomes and frame trace under host depth bounds 0, 64, 128 and unbounded; the
 * regression data on call events and frames written per declaration; later K0 = K1. Owner: M8.
 *
 * <h2>Convention equivalence</h2>
 * ISA 1.3 "what is observable, and therefore identical under every host convention": the value ids
 * judgments return, the memo and pair-table contents, the tick count, the outcome and the frame trace.
 * {@link #convention} runs every declaration of an export in three machines that differ only in
 * {@code Machine.hostDepth} ({@code Op.HOST_DEPTH} and 64: the convention; 0: every non-leaf call writes its frame, the
 * worklist machine of ISA 1.3; unbounded: plain host recursion, no frame at all) and compares, per
 * declaration: the outcome and its reason, the ticks, the call events by op and their sequence (the
 * frame trace; the trace records call events only, NOTES-M6 S6), and after an ACCEPT every word of every
 * record (identity, flags, the four memo words), every word of the run slab and every pair entry. In
 * v0 a session is one declaration, so the record and run indices of a declaration start at the same
 * point in every lane and "the same ids" is "the same words at the same indices". After another outcome
 * the facade has reset the machine (ISA 14.1), so only outcome, ticks and trace are compared. It also
 * checks that the unbounded lane writes no frame at all (H1: a frame exists only when a judgment is
 * suspended).
 *
 * <h2>Any observable computation</h2>
 * {@link #compare(Machine, Machine)} and {@link #sameObservables} compare two machines directly; tests
 * of single judgments use them on fixtures ({@code M8ConventionTest} shows the pattern on the toy
 * judgments, where it also proves that the comparison finds a judgment that depends on the bound).
 */
final class TierEq {
    private TierEq() {}

    /**
     * The bounds of H12 item 1 and the test configuration without a bound: the bound in force
     * ({@code Op.HOST_DEPTH}; 128 since 2026-11-24T10:29Z, NOTES-M6 F22), 64 (the bound the convention was
     * measured with, and the one the acceptance of M8 names), 0 (every frame written) and unbounded. The
     * first is the lane the others are compared with.
     */
    static final int[] BOUNDS = Op.HOST_DEPTH == 64 ? new int[] {64, 0, Op.HOST_DEPTH_UNBOUNDED}
            : new int[] {Op.HOST_DEPTH, 64, 0, Op.HOST_DEPTH_UNBOUNDED};

    /** The result of {@link #convention} on one export. */
    static final class ConvResult {
        String path;
        int[] bounds;
        int units;
        /** Declarations that reached the engine in the first lane. */
        int checks;
        /** Of these: accepted in the first lane (records, runs and pairs were compared). */
        int accepted;
        /** Declarations whose comparison with the unbounded lane was skipped (host stack overflow there). */
        int overflowSkipped;
        /** Declarations on which the pair entries are equal as a set but were inserted in another order (not a difference). */
        int pairOrderNotes;
        long calls;
        long ticks;
        long[] frames;
        final List<String> differences = new ArrayList<>();
        String loadMessage = "";

        boolean ok() {
            return differences.isEmpty();
        }
    }

    static String boundName(int d) {
        return d == Op.HOST_DEPTH_UNBOUNDED ? "unbounded" : "D" + d;
    }

    /**
     * Checks every declaration of a loaded export under the depth bounds of {@link #BOUNDS} and compares
     * the observables.
     *
     * @return the empty string if they are identical, else one line per difference
     */
    static String conventionEquivalence(String exportPath) {
        try {
            ConvResult r = convention(exportPath, true, BOUNDS);
            return String.join("\n", r.differences);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Convention equivalence of one export under the given bounds; the first bound is the lane the others
     * are compared with.
     *
     * @param trace compare the sequence of call events too (memory: 4 bytes per call event of a declaration)
     */
    static ConvResult convention(String exportPath, boolean trace, int... bounds) throws IOException {
        ConvResult res = new ConvResult();
        res.path = exportPath;
        res.bounds = bounds;
        res.frames = new long[bounds.length];
        Throwable[] failure = new Throwable[1];
        // A thread of its own with a larger stack: the lane without a bound is plain host recursion.
        Thread t = new Thread(null, () -> {
            try {
                conventionOn(exportPath, trace, bounds, res);
            } catch (Throwable e) {
                failure[0] = e;
            }
        }, "m8-conv", 64L << 20);
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (failure[0] != null) {
            if (failure[0] instanceof IOException) {
                throw (IOException) failure[0];
            }
            StackTraceElement[] st = failure[0].getStackTrace();
            res.differences.add("CRASH " + exportPath + ": " + failure[0] + (st.length > 0 ? " < " + st[0] : "")
                    + (st.length > 1 ? " < " + st[1] : ""));
        }
        return res;
    }

    private static void conventionOn(String exportPath, boolean trace, int[] bounds, ConvResult res) throws IOException {
        try (M8Harness h = M8Harness.load(exportPath)) {
            if (h.loadOutcome != Outcome.ACCEPT) {
                res.loadMessage = h.loadMessage;
                return;
            }
            res.units = h.units;
            M8Harness.Lane[] lanes = new M8Harness.Lane[bounds.length];
            for (int i = 0; i < bounds.length; i++) {
                M8Harness.Config c = new M8Harness.Config(boundName(bounds[i])).hostDepth(bounds[i]).trace(trace);
                c.maxSteps = Long.getLong("m8.maxSteps", c.maxSteps);
                c.checkMillis = 1000L * Long.getLong("m8.checkSeconds", c.checkMillis / 1000);
                c.switches = Debug.parseSwitches(System.getProperty("m8.switches", ""));
                lanes[i] = h.lane(c);
            }
            M8Harness.Lane a = lanes[0];
            while (h.next()) {
                int u = h.unit;
                if (a.obs != null) {
                    res.checks++;
                    res.calls += a.obs.calls;
                    res.ticks += a.obs.ticks;
                    if (a.obs.outcome == Outcome.ACCEPT) {
                        res.accepted++;
                    }
                }
                for (int i = 0; i < lanes.length; i++) {
                    M8Harness.Lane b = lanes[i];
                    if (b.obs != null) {
                        res.frames[i] += b.obs.framesWritten;
                        if (bounds[i] == Op.HOST_DEPTH_UNBOUNDED && b.obs.framesWritten != 0) {
                            res.differences.add(where(exportPath, h, u) + ": " + b.config.label + " wrote " + b.obs.framesWritten
                                    + " frames; without a depth bound no judgment is ever suspended (H1)");
                        }
                    }
                    if (i == 0) {
                        continue;
                    }
                    if (b.obs != null && b.obs.overflow) {
                        res.overflowSkipped++;
                        continue;
                    }
                    int before = res.differences.size();
                    String d = sameObservables(a, b);
                    if (d != null) {
                        if (d.startsWith("NOTE")) {
                            res.pairOrderNotes++;
                        } else if (res.differences.size() < 200) {
                            res.differences.add(where(exportPath, h, u) + ": " + a.config.label + " against " + b.config.label + ": " + d);
                        }
                    }
                    if (res.differences.size() > before && res.differences.size() == 200) {
                        res.differences.add("... more differences not recorded");
                    }
                }
            }
        }
    }

    private static String where(String path, M8Harness h, int u) {
        return path.replaceAll("^work/arena-site/tests/", "") + " unit " + u + " " + h.unitKindName(u) + " '" + h.unitName(u) + "'";
    }

    /**
     * Compares what two lanes observed on the unit they ran last, and (after an ACCEPT in both) their
     * live machines.
     *
     * @return null if everything observable is identical; a text that starts with "NOTE" if only the
     *     insertion order of the pair entries differs; else the first difference
     */
    static String sameObservables(M8Harness.Lane la, M8Harness.Lane lb) {
        M8Harness.Obs a = la.obs;
        M8Harness.Obs b = lb.obs;
        if (la.outcome != lb.outcome) {
            return "admission outcome " + Outcome.name(la.outcome) + " (" + la.reason + ") against " + Outcome.name(lb.outcome) + " ("
                    + lb.reason + ": " + lb.message + ")";
        }
        if (a == null || b == null) {
            return a == b ? null : "the unit reached the engine in one lane only";
        }
        if (a.outcome != b.outcome || a.reason != b.reason) {
            return "outcome " + Outcome.name(a.outcome) + " (" + a.reason + ") against " + Outcome.name(b.outcome) + " (" + b.reason + ")";
        }
        if (a.ticks != b.ticks) {
            return "ticks " + a.ticks + " against " + b.ticks + traceHint(a, b);
        }
        if (a.calls != b.calls) {
            return "call events " + a.calls + " against " + b.calls + traceHint(a, b);
        }
        for (int op = 0; op < Op.OP_COUNT; op++) {
            if (a.callsByOp[op] != b.callsByOp[op]) {
                return "call events of " + Op.name(op) + ": " + a.callsByOp[op] + " against " + b.callsByOp[op] + traceHint(a, b);
            }
        }
        if (a.trace != null && b.trace != null) {
            String t = traceDifference(a.trace, b.trace);
            if (t != null) {
                return t;
            }
        }
        if (a.hasDigest && b.hasDigest) {
            if (a.records != b.records || a.recordDigest != b.recordDigest || a.runWords != b.runWords || a.runDigest != b.runDigest
                    || a.pairs != b.pairs || a.pairSetDigest != b.pairSetDigest) {
                String d = compare(la.m, lb.m);
                return d != null ? d : "session digests differ but the live machines are equal (the machines moved on?)";
            }
            if (a.pairDigest != b.pairDigest) {
                return "NOTE the pair entries are the same set, inserted in another order";
            }
        }
        return null;
    }

    private static String traceHint(M8Harness.Obs a, M8Harness.Obs b) {
        if (a.trace == null || b.trace == null) {
            return "";
        }
        String t = traceDifference(a.trace, b.trace);
        return t == null ? "" : "; " + t;
    }

    /** The first position at which two frame traces differ, with the events around it; null if equal. */
    static String traceDifference(int[] a, int[] b) {
        int n = Math.min(a.length, b.length);
        int i = 0;
        while (i < n && a[i] == b[i]) {
            i++;
        }
        if (i == a.length && i == b.length) {
            return null;
        }
        StringBuilder sb = new StringBuilder("frame trace differs at call event " + i + " of " + a.length + " / " + b.length + ": ..");
        for (int j = Math.max(0, i - 6); j < i; j++) {
            sb.append(' ').append(Op.name(a[j]));
        }
        sb.append(" | ");
        for (int j = i; j < Math.min(a.length, i + 4); j++) {
            sb.append(Op.name(a[j])).append(' ');
        }
        sb.append("against ");
        for (int j = i; j < Math.min(b.length, i + 4); j++) {
            sb.append(Op.name(b[j])).append(' ');
        }
        return sb.toString().trim();
    }

    /**
     * Compares the session state of two machines word for word: records (identity, flags, memo words),
     * runs, and the pair entries as a set (ISA 1.3 observables; 2.2, 2.4, 1.5).
     *
     * @return null if equal, else the first difference with both sides printed
     */
    static String compare(Machine a, Machine b) {
        if (a.vbase != b.vbase || a.rbase != b.rbase) {
            return "the sessions start at different record / run indices (" + a.vbase + ", " + a.rbase + " against " + b.vbase + ", "
                    + b.rbase + "): under DBG_POISON the bases move with every reset, so the two machines were reset differently";
        }
        int n = Math.min(a.vp, b.vp);
        int[] va = a.vh;
        int[] vb = b.vh;
        for (int r = a.vbase; r < n; r++) {
            int o = r << Layout.REC_SHIFT;
            for (int w = 0; w < Layout.REC_WORDS; w++) {
                if (va[o + w] != vb[o + w]) {
                    String what = w == 0 ? "kind / flags / aux" : w <= Layout.C ? "identity word " + "abc".charAt(w - 1)
                            : "memo word m" + (w - Layout.M0);
                    return "record #" + r + " differs in " + what + " (" + va[o + w] + " against " + vb[o + w] + "): "
                            + safeRecord(a, r) + "   against   " + safeRecord(b, r);
                }
            }
        }
        if (a.vp != b.vp) {
            return "records made: " + (a.vp - a.vbase) + " against " + (b.vp - b.vbase)
                    + "; the first record only one machine has: " + (a.vp > b.vp ? safeRecord(a, n) : safeRecord(b, n));
        }
        int rn = Math.min(a.rsp, b.rsp);
        for (int i = a.rbase; i < rn; i++) {
            if (a.rs[i] != b.rs[i]) {
                return "run slab differs at word " + i + ": " + a.rs[i] + " against " + b.rs[i];
            }
        }
        if (a.rsp != b.rsp) {
            return "run words: " + (a.rsp - a.rbase) + " against " + (b.rsp - b.rbase);
        }
        if (a.plogN != b.plogN) {
            return "pair entries: " + a.plogN + " against " + b.plogN;
        }
        Map<Long, Integer> pairs = new HashMap<>();
        for (int i = 0; i < a.plogN; i++) {
            int e = a.plog[i] << Layout.PAIR_SHIFT;
            pairs.put((long) a.pair[e + Layout.PAIR_LO] << 32 | (a.pair[e + Layout.PAIR_HI] & 0xFFFFFFFFL), a.pair[e + Layout.PAIR_STATUS]);
        }
        for (int i = 0; i < b.plogN; i++) {
            int e = b.plog[i] << Layout.PAIR_SHIFT;
            int lo = b.pair[e + Layout.PAIR_LO];
            int hi = b.pair[e + Layout.PAIR_HI];
            Integer st = pairs.get((long) lo << 32 | (hi & 0xFFFFFFFFL));
            if (st == null) {
                return "pair (" + lo + ", " + hi + ") with status " + b.pair[e + Layout.PAIR_STATUS] + " exists in one machine only: "
                        + Show.value(b, lo) + "  ~  " + Show.value(b, hi);
            }
            if (st != b.pair[e + Layout.PAIR_STATUS]) {
                return "pair (" + lo + ", " + hi + ") has status " + st + " against " + b.pair[e + Layout.PAIR_STATUS] + ": "
                        + Show.value(b, lo) + "  ~  " + Show.value(b, hi);
            }
        }
        return null;
    }

    private static String safeRecord(Machine m, int r) {
        try {
            return Show.record(m, r);
        } catch (RuntimeException e) {
            return "#" + r + " (not printable: " + e + ")";
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // Reports
    // ---------------------------------------------------------------------------------------------------

    /**
     * Convention equivalence over a corpus: {@code work/status/machine-conv-LABEL.txt}. The lanes run in passes
     * ({@link M8Lanes}: the reference engine first, then one lane after the other, each letting go of everything
     * before the next starts; the trace as a digest), files in parallel ({@code -Dm8.jobs}, default 1), a file in
     * which the Java heap answered again on its own: four lanes and the reference kernel in lockstep on
     * perf/app-lam or perf/magma-list-deep-n36 do not fit into the 6 GB of the test JVM. The first difference of
     * a declaration is named by kind; {@code M8ConventionTest#oneFile} shows it on the live machines.
     *
     * @return true if no declaration differs
     */
    static boolean conventionReport(String label, String... roots) throws IOException {
        List<String> paths = Diff.exports(roots);
        String name = Diff.reportName(label);
        boolean trace = !"false".equals(System.getProperty("m8.trace"));
        int switches = Debug.parseSwitches(System.getProperty("m8.switches", ""));
        boolean warm = (switches & Op.SW_WARM) != 0;
        long t0 = System.nanoTime();
        List<M8Lanes.Spec> specs = new ArrayList<>();
        M8Harness.Config lead = new M8Harness.Config(boundName(BOUNDS[0])).hostDepth(BOUNDS[0]).trace(trace).traceDigestOnly(true);
        lead.maxSteps = Long.getLong("m8.maxSteps", lead.maxSteps);
        lead.checkMillis = 1000L * Long.getLong("m8.checkSeconds", lead.checkMillis / 1000);
        lead.switches = switches;
        lead.incrementalDigest = warm;
        if (warm) {
            lead.sessionBytes = (long) Integer.getInteger("m8.sessionMib", 4) << 20;
        }
        specs.add(new M8Lanes.Spec("conv", lead));
        for (int i = 1; i < BOUNDS.length; i++) {
            specs.add(new M8Lanes.Spec("conv", lead.copy(boundName(BOUNDS[i])).hostDepth(BOUNDS[i])).against(0, M8Lanes.CONV));
        }
        Map<String, M8Lanes.FileRun> runs = M8Gate.runAll("conv " + name, paths, specs, Integer.getInteger("m8.jobs", 1),
                1000L * Long.getLong("m8.fileSeconds", 1800));
        int files = 0;
        int bad = 0;
        long checks = 0;
        long accepted = 0;
        long calls = 0;
        long ticks = 0;
        long overflow = 0;
        long notes = 0;
        long[] frames = new long[BOUNDS.length];
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, M8Lanes.FileRun> e : runs.entrySet()) {
            M8Lanes.FileRun run = e.getValue();
            files++;
            List<String> differences = new ArrayList<>();
            if (run.crash != null) {
                StackTraceElement[] st = run.crash.getStackTrace();
                differences.add("CRASH " + e.getKey() + ": " + run.crash + (st.length > 0 ? " < " + st[0] : "") + (st.length > 1 ? " < " + st[1] : ""));
            }
            for (int i = 0; i < run.lanes.length; i++) {
                M8Lanes.LaneRun l = run.lanes[i];
                frames[i] += l.framesTotal;
                if (i == 0) {
                    checks += l.checks;
                    accepted += l.accepted;
                    calls += l.callsTotal;
                    ticks += l.ticksTotal;
                }
                overflow += l.overflowSkipped;
                notes += l.pairOrderNotes;
                if (l.crash != null) {
                    StackTraceElement[] st = l.crash.getStackTrace();
                    differences.add("CRASH " + e.getKey() + " in lane " + l.spec.config.label + ": " + l.crash + (st.length > 0 ? " < " + st[0] : "")
                            + (st.length > 1 ? " < " + st[1] : ""));
                }
                if (l.timeout) {
                    differences.add("TIMEOUT " + e.getKey() + " in lane " + l.spec.config.label + " after " + l.ranUnits + " units");
                }
                if (l.memoryFailures > 0) {
                    differences.add("NOT COMPARED " + e.getKey() + ": lane " + l.spec.config.label + " ran out of Java heap on " + l.memoryFailures
                            + " declarations although the file ran on its own (give the report more heap: report.sh --heap)");
                }
                differences.addAll(l.differences);
                if (l.differing > l.differences.size()) {
                    differences.add("... and " + (l.differing - l.differences.size()) + " more declarations that differ in lane " + l.spec.config.label);
                }
            }
            if (!differences.isEmpty()) {
                bad++;
                for (String d : differences) {
                    body.append(d).append('\n');
                }
                body.append("    reproduce: scripts/machine-sandbox.sh test <WP> -Dtest=M8ConventionTest#oneFile -Dm8.file=").append(e.getKey())
                        .append(switches == 0 ? "" : " -Dm8.switches=" + Debug.switchNames(switches)).append('\n');
                System.err.println("m8 conv " + name + ": DIFFERENT " + e.getKey() + " -- " + differences.get(0));
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# machine-conv-").append(name).append(": convention equivalence (stepper-convention H12 item 1; ISA 1.3 observables)\n");
        sb.append("# generated ").append(Instant.now().truncatedTo(ChronoUnit.SECONDS)).append(" by: rlvm-machine/src/test/resources/m8/report.sh conv ")
                .append(label).append("; ").append(Diff.lastPublication()).append('\n');
        sb.append("# machine: ").append(lead).append('\n');
        sb.append("# lanes (host depth bound):");
        for (int i = 0; i < BOUNDS.length; i++) {
            sb.append(i == 0 ? " " : ", ").append(boundName(BOUNDS[i])).append(i == 0 ? " (the bound in force; the others are compared with it)"
                    : BOUNDS[i] == 0 ? " (every frame written: the worklist machine)" : BOUNDS[i] == Op.HOST_DEPTH_UNBOUNDED
                            ? " (plain host recursion)" : "");
        }
        sb.append('\n');
        sb.append("# compared per declaration: outcome, reason, ticks, call events by op").append(trace ? ", the frame trace (as a digest)" : "")
                .append("; after an ACCEPT: every record word (ids, flags, memo), every run word, the pair entries\n");
        sb.append("#\n# RESULT ").append(name).append(": ").append(bad == 0 ? "EQUIVALENT" : "DIFFERENT in " + bad + " files").append(" -- files ")
                .append(files).append(", declarations that reached the engine ").append(checks).append(", of them accepted ").append(accepted)
                .append('\n');
        if (checks > 0 && calls == 0) {
            sb.append("# NOTE: the machine made no call event at all (every declaration ended before its first judgment): the\n"
                    + "#       comparison is vacuous until judgments are published\n");
        }
        sb.append(String.format(Locale.ROOT, "# ticks %d, call events %d; frames written:", ticks, calls));
        for (int i = 0; i < BOUNDS.length; i++) {
            sb.append(i == 0 ? " " : ", ").append(boundName(BOUNDS[i])).append(' ').append(frames[i]);
            if (BOUNDS[i] != 0 && BOUNDS[i] != Op.HOST_DEPTH_UNBOUNDED) {
                sb.append(" (").append(calls == 0 ? "n/a" : String.format(Locale.ROOT, "%.4f%%", 100.0 * frames[i] / calls)).append(" of the calls)");
            }
        }
        sb.append('\n');
        sb.append("# declarations not compared with the unbounded lane (host stack overflow there): ").append(overflow)
                .append("; pair-insertion-order notes: ").append(notes).append('\n');
        sb.append(String.format(Locale.ROOT, "# wall %.1f s%n#%n", (System.nanoTime() - t0) / 1e9));
        sb.append(body);
        Path out = Fixture.root().resolve("work/status/machine-conv-" + name + ".txt");
        Files.createDirectories(out.getParent());
        Files.write(out, sb.toString().getBytes(StandardCharsets.UTF_8));
        int cut = sb.indexOf("# wall");
        System.out.println(sb.substring(0, cut < 0 ? sb.length() : cut) + "report: " + out);
        return bad == 0;
    }

    /** The bound of H12 item 2: frames written under the host depth bound stay below 0.1% of the call events (on Init). */
    static final double FRAME_BOUND = 0.001;

    /**
     * Call events and frames written per declaration under the bound in force: {@code work/status/machine-frames-LABEL.txt},
     * and the per-declaration table {@code work/status/machine-calls-LABEL.tsv} (the regression data of ISA
     * section 11: the previous table is compared, declarations whose call events grew by more than 2% are listed).
     *
     * @return false if, on Init or Std, the frames written exceed {@link #FRAME_BOUND} of the call events
     */
    static boolean framesReport(String label, String... roots) throws IOException {
        List<String> paths = Diff.exports(roots);
        String name = Diff.reportName(label);
        Path tsv = Fixture.root().resolve("work/status/machine-calls-" + name + ".tsv");
        Map<String, long[]> previous = new HashMap<>();
        if (Files.isRegularFile(tsv)) {
            for (String line : Files.readAllLines(tsv, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t");
                if (f.length >= 5 && !line.startsWith("#")) {
                    try {
                        previous.put(f[0] + "\t" + f[1], new long[] {Long.parseLong(f[3]), Long.parseLong(f[4])});
                    } catch (NumberFormatException e) {
                        // a damaged line is not a baseline
                    }
                }
            }
        }
        StringBuilder table = new StringBuilder("# file\tdeclaration\toutcome\tcall events\tticks\tframes written\tspills\n");
        long calls = 0;
        long ticks = 0;
        long frames = 0;
        long spills = 0;
        long checks = 0;
        long accepted = 0;
        long declsWithFrames = 0;
        long grew = 0;
        long shrank = 0;
        long comparedWithPrevious = 0;
        long previousCalls = 0;
        long nowCallsOfCompared = 0;
        final long[] callsByOp = new long[Op.OP_COUNT];
        List<String> top = new ArrayList<>();
        List<long[]> topKeys = new ArrayList<>();
        List<String> alerts = new ArrayList<>();
        M8Harness.Config c = Diff.optionsFromProperties().config;
        c.digest = false;
        for (String p : paths) {
            String shortPath = p.replaceAll("^work/arena-site/tests/", "").replaceAll("^work/", "");
            try (M8Harness h = M8Harness.load(p)) {
                if (h.loadOutcome != Outcome.ACCEPT) {
                    continue;
                }
                M8Harness.Lane lane = h.lane(c);
                while (h.next()) {
                    M8Harness.Obs o = lane.obs;
                    if (o == null) {
                        continue;
                    }
                    checks++;
                    if (o.outcome == Outcome.ACCEPT) {
                        accepted++;
                    }
                    calls += o.calls;
                    ticks += o.ticks;
                    frames += o.framesWritten;
                    spills += o.spills;
                    for (int op = 0; op < Op.OP_COUNT; op++) {
                        callsByOp[op] += o.callsByOp[op];
                    }
                    String declName = h.unitName(h.unit);
                    table.append(shortPath).append('\t').append(declName).append('\t').append(Outcome.name(o.outcome)).append('\t')
                            .append(o.calls).append('\t').append(o.ticks).append('\t').append(o.framesWritten).append('\t').append(o.spills)
                            .append('\n');
                    if (o.framesWritten > 0) {
                        declsWithFrames++;
                        top.add(String.format(Locale.ROOT, "%10d frames %12d calls  %8.4f%%  %s '%s'", o.framesWritten, o.calls,
                                o.calls == 0 ? 0.0 : 100.0 * o.framesWritten / o.calls, shortPath, declName));
                        topKeys.add(new long[] {o.framesWritten, top.size() - 1});
                    }
                    long[] prev = previous.get(shortPath + "\t" + declName);
                    if (prev != null) {
                        comparedWithPrevious++;
                        previousCalls += prev[0];
                        nowCallsOfCompared += o.calls;
                        if (o.calls > prev[0] * 1.02 + 0.5) {
                            grew++;
                            if (alerts.size() < 30) {
                                alerts.add(String.format(Locale.ROOT, "%12d -> %12d call events (%+.1f%%)  %s '%s'", prev[0], o.calls,
                                        prev[0] == 0 ? 100.0 : 100.0 * (o.calls - prev[0]) / prev[0], shortPath, declName));
                            }
                        } else if (o.calls < prev[0] * 0.98 - 0.5) {
                            shrank++;
                        }
                    }
                }
            }
        }
        double ratio = calls == 0 ? 0 : (double) frames / calls;
        // H12 item 2 states the bound for Init (a library corpus); a small corpus with one deep declaration
        // (bugs/str-lit-utf8-bad) lies above it by design, so only Init and Std fail the command
        boolean enforced = label.equals("init") || label.equals("std");
        boolean below = ratio < FRAME_BOUND;
        boolean ok = below || !enforced;
        StringBuilder sb = new StringBuilder();
        sb.append("# machine-frames-").append(name).append(": call events and frames written per declaration under ").append(c).append('\n');
        sb.append("# generated ").append(Instant.now().truncatedTo(ChronoUnit.SECONDS)).append(" by: rlvm-machine/src/test/resources/m8/report.sh frames ")
                .append(label).append("; ").append(Diff.lastPublication()).append('\n');
        sb.append("#\n# RESULT ").append(name).append(": ");
        if (calls == 0) {
            sb.append("NO DATA: the machine made no call event (every declaration ended before its first judgment)\n");
        } else {
            sb.append(String.format(Locale.ROOT, "frames written / call events = %d / %d = %.5f%% -- %s the bound of %.1f%% (H12 item 2%s)%n", frames,
                    calls, 100.0 * ratio, below ? "BELOW" : "ABOVE", 100.0 * FRAME_BOUND,
                    enforced ? "" : "; the bound is stated for Init: not enforced on this corpus"));
        }
        sb.append(String.format(Locale.ROOT, "# declarations that reached the engine %d (accepted %d); ticks %d; spills (judgments that became a frame before they started) %d; declarations with any frame %d%n",
                checks, accepted, ticks, spills, declsWithFrames));
        sb.append("# call events by op:");
        for (int op = 0; op < Op.OP_COUNT; op++) {
            if (callsByOp[op] != 0) {
                sb.append(' ').append(Op.name(op)).append('=').append(callsByOp[op]);
            }
        }
        sb.append('\n');
        if (comparedWithPrevious > 0) {
            sb.append(String.format(Locale.ROOT, "# against the previous table: %d declarations in both, call events %d -> %d (%+.2f%%); %d declarations grew by more than 2%%, %d shrank by more than 2%%%n",
                    comparedWithPrevious, previousCalls, nowCallsOfCompared,
                    previousCalls == 0 ? 0.0 : 100.0 * (nowCallsOfCompared - previousCalls) / previousCalls, grew, shrank));
            if (previousCalls > 0 && nowCallsOfCompared > previousCalls * 1.02) {
                sb.append("# ALERT: call events grew by more than 2% in total (ISA section 11, cost regression)\n");
            }
        } else {
            sb.append("# no previous table to compare the call events with\n");
        }
        sb.append("# per declaration: ").append(Fixture.root().relativize(tsv)).append(" (the previous one is kept as .prev)\n#\n");
        if (!alerts.isEmpty()) {
            sb.append("# Declarations whose call events grew by more than 2% (first 30)\n");
            for (String a : alerts) {
                sb.append(a).append('\n');
            }
            sb.append("#\n");
        }
        if (!top.isEmpty()) {
            sb.append("# Declarations with the most frames written\n");
            topKeys.sort((x, y) -> Long.compare(y[0], x[0]));
            for (int i = 0; i < topKeys.size() && i < 25; i++) {
                sb.append(top.get((int) topKeys.get(i)[1])).append('\n');
            }
        }
        if (Files.isRegularFile(tsv)) {
            Files.move(tsv, tsv.resolveSibling(tsv.getFileName() + ".prev"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        Files.write(tsv, table.toString().getBytes(StandardCharsets.UTF_8));
        Path out = Fixture.root().resolve("work/status/machine-frames-" + name + ".txt");
        Files.write(out, sb.toString().getBytes(StandardCharsets.UTF_8));
        int cut = sb.indexOf("# per declaration");
        System.out.println(sb.substring(0, cut < 0 ? sb.length() : cut) + "report: " + out);
        return ok;
    }

    // ---------------------------------------------------------------------------------------------------
    // K0 = K1 (ISA section 11 "tier equivalence"; staging step 4)
    // ---------------------------------------------------------------------------------------------------

    /** The switches of the K1 tier (staging steps 2-4): facts, TELE, code objects, case trees. */
    static final int K1_SWITCHES = Op.SW_FACTS | Op.SW_TELE | Op.SW_CODE | Op.SW_CASETREES;

    /**
     * K0 against K1 on every declaration of an export: the generic tier (all switches off) and the tier
     * given by {@code -Dm8.k1=facts,tele,code,casetrees} (default: those four) must give the same outcome
     * for every declaration. See NOTES-M8 for what of ISA section 11's tier test this covers: the
     * declaration-level part now; the value-id part (DELTA, WHNF, VTYPE on random arguments) needs code
     * objects to exist and is {@link M8Sampler}'s tier mode.
     *
     * @return the empty string if the tiers agree, else one line per difference
     */
    static String tierEquivalence(String exportPath, long seed) {
        int k1 = System.getProperty("m8.k1") == null ? K1_SWITCHES : Debug.parseSwitches(System.getProperty("m8.k1"));
        StringBuilder sb = new StringBuilder();
        try (M8Harness h = M8Harness.load(exportPath)) {
            if (h.loadOutcome != Outcome.ACCEPT) {
                return "";
            }
            M8Harness.Lane a = h.lane(new M8Harness.Config("K0"));
            M8Harness.Config c = new M8Harness.Config("K1");
            c.switches = k1;
            M8Harness.Lane b = h.lane(c);
            while (h.next()) {
                if (a.outcome != b.outcome || (a.obs != null && b.obs != null && a.obs.reason != b.obs.reason)) {
                    sb.append(where(exportPath, h, h.unit)).append(": K0 ").append(Outcome.name(a.outcome)).append(" (").append(a.reason)
                            .append(") against K1 [").append(Debug.switchNames(k1)).append("] ").append(Outcome.name(b.outcome)).append(" (")
                            .append(b.reason).append(": ").append(b.message).append(")\n");
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return sb.toString().trim();
    }
}
