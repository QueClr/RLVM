package rlvm.machine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import rlvm.api.MachineErrors;
import rlvm.api.Outcome;
import rlvm.api.Reason;

/**
 * One export, the reference engine and any number of machine configurations, run in <em>passes</em>: the
 * reference engine over every unit first, then every lane on its own, then the comparisons from what the
 * passes recorded per unit. Owner: M8.
 *
 * <p>{@link M8Harness} in lockstep keeps the reference kernel and every lane's machine alive side by side
 * for the whole file, which is what a comparison of live machines needs ({@code M8ConventionTest#oneFile},
 * {@code M8DiffOneTest}) and what a corpus report cannot afford: four lanes and the reference kernel on
 * perf/app-lam or perf/magma-list-deep-n36 do not fit into the test JVM's heap. Here the reference
 * session is emptied before the first lane starts, a lane lets go of everything when its pass ends, the
 * frame trace is kept as a digest, and per unit only a few words are kept: the outcome and reason of the
 * admission and of the engine check, ticks, call events (total and a digest by op), the digest of the
 * trace, and after an ACCEPT the sizes and digests of records, runs and pairs. So the memory needed at a
 * time is that of one engine on one declaration (lanes one after the other), or of every lane at once
 * ({@link Options#parallelLanes}: one thread per lane, for the corpora that are one big file).
 *
 * <p>What is compared:
 * <ul>
 *   <li>every lane against the reference engine, per declaration: a {@link Diff.FileResult} with the
 *       counting rule of {@link Diff} (agree, declined, SOUNDNESS, COMPLETENESS, reference declined);
 *   <li>a lane against another lane ({@link Spec#against}): {@link #CONV} = everything observable
 *       (ISA 1.3; stepper-convention H12 item 1), {@link #OUTCOME} = outcome and reason of every
 *       declaration (tier equivalence at the level of declarations; the debug audits, whose failures are
 *       also counted by their code {@code E_AUDIT}).
 * </ul>
 * A check that the Java heap ended ({@link M8Harness.Obs#memory}) is no answer of the machine: the file is
 * flagged ({@link FileRun#memory()}) and a report runs it again on its own.
 */
final class M8Lanes {
    private M8Lanes() {}

    /** The lane is only held against the reference engine. */
    static final int NONE = 0;
    /** Against another lane: outcome, reason, ticks, call events by op, the frame trace; after an ACCEPT records, runs, pairs. */
    static final int CONV = 1;
    /** Against another lane: outcome and reason of every declaration. */
    static final int OUTCOME = 2;

    /** One lane of a run. */
    static final class Spec {
        /** What the lane is for in the report: {@code diff}, {@code conv}, {@code tier}, {@code audit}, or a switch set. */
        final String role;
        final M8Harness.Config config;
        /** Index of the lane this one is compared with, or -1. */
        int against = -1;
        int how = NONE;

        Spec(String role, M8Harness.Config config) {
            this.role = role;
            this.config = config;
        }

        Spec against(int lane, int how) {
            this.against = lane;
            this.how = how;
            return this;
        }
    }

    /** How a file is run. */
    static final class Options {
        /** Wall-clock limit of one file; beyond it the passes stop between two units and the file is a TIMEOUT. */
        long fileMillis = 1_800_000;
        int linesPerFile = 12;
        /** Byte budget of the reference engine per unit; 0 = {@link M8Harness#REF_BYTES}. */
        long refBytes;
        java.util.function.IntPredicate refDeclines;
        /** One thread per lane at the same time (a corpus that is one big file); else the lanes run one after the other. */
        boolean parallelLanes;
        /**
         * Run the lanes on the calling thread (many small files, no lane without a depth bound: the caller's
         * thread has the 2 MiB stack the machine must be content with); else every lane pass has a thread of
         * its own (2 MiB; 64 MiB for a lane without a depth bound).
         */
        boolean inlineLanes;
        /** A pass (the load and the reference pass, a lane) holds one permit while it runs; null = no limit. */
        Semaphore cpu;
        /** Set from outside to stop the passes between two units. */
        volatile boolean cancel;
        /** The loaded harness, for {@link M8Harness#cancel()} from another thread (may be null). */
        M8Harness[] holder;
    }

    private static final byte CHECKED = 1;
    private static final byte DIGEST = 2;
    private static final byte OVERFLOW = 4;
    private static final byte MEMORY = 8;
    private static final byte AUDIT = 16;
    /** The check's deadline ended it ({@code Config.checkMillis}; a loaded host): no answer to compare. */
    private static final byte DEADLINE = 32;

    /** What one lane's pass recorded, and what the comparisons made of it. */
    static final class LaneRun {
        final Spec spec;
        /** Against the reference engine. */
        Diff.FileResult diff;

        // per unit
        byte[] outcome;
        byte[] reason;
        byte[] engOutcome;
        byte[] engReason;
        byte[] flags;
        long[] ticks;
        long[] calls;
        long[] opsDigest;
        long[] traceDigest;
        int[] traceLen;
        int[] frames;
        int[] records;
        int[] runWords;
        int[] pairs;
        long[] recordDigest;
        long[] runDigest;
        long[] pairDigest;
        long[] pairSetDigest;
        /** The digest of the whole session where the lane reset it after the unit (warm lanes), else 0. */
        long[] sessionDigest;
        /** The digest of the session the lane ended the file with (warm lanes that digest), else 0. */
        long finalDigest;
        /** Unit -> the normalised reason of an outcome other than ACCEPT ({@link Diff#reasonKey}). */
        final Map<Integer, String> keys = new HashMap<>();

        // against spec.against
        final List<String> differences = new ArrayList<>();
        /** Declarations that differ from the other lane (also those beyond the lines kept). */
        int differing;
        int comparedWithOther;
        int overflowSkipped;
        /** Declarations not compared because the wall-clock limit of a check ended it in one of the two lanes. */
        int deadlineSkipped;
        /** Units the lane declined by the eager rule while the lane it is held against (switch {@code eager}) decided them. */
        int eagerOnly;
        int pairOrderNotes;
        /** {@link #OUTCOME} lanes: declarations with the same outcome and another reason (another error of a wrong declaration met first): not differences. */
        int reasonNotes;
        final List<String> reasonLines = new ArrayList<>();
        /** Declarations a debug audit ended ({@code E_AUDIT}); the first messages are in {@link #auditLines}. */
        int audits;
        final List<String> auditLines = new ArrayList<>();

        // totals
        int ranUnits;
        long checks;
        long accepted;
        long rejected;
        long declined;
        long ticksTotal;
        long callsTotal;
        long framesTotal;
        long spills;
        long nanos;
        long memoryFailures;
        /** Units declined for memory (the machine's own limits or the harness's heap). */
        long memoryDeclines;
        long overflows;
        long cancelled;
        long peakFootprint;
        boolean timeout;
        /** The pass died: an {@code Error} out of a judgment (a host stack overflow under a bound, ISA 12 R11) or of the harness. */
        Throwable crash;

        LaneRun(Spec spec) {
            this.spec = spec;
        }

        private void allocate(int n) {
            outcome = new byte[n];
            reason = new byte[n];
            engOutcome = new byte[n];
            engReason = new byte[n];
            flags = new byte[n];
            ticks = new long[n];
            calls = new long[n];
            opsDigest = new long[n];
            traceDigest = new long[n];
            traceLen = new int[n];
            frames = new int[n];
            records = new int[n];
            runWords = new int[n];
            pairs = new int[n];
            recordDigest = new long[n];
            runDigest = new long[n];
            pairDigest = new long[n];
            pairSetDigest = new long[n];
            sessionDigest = new long[n];
        }

        /** The per-unit arrays are no longer needed (the comparisons are done). */
        void forget() {
            outcome = null;
            reason = null;
            engOutcome = null;
            engReason = null;
            flags = null;
            ticks = null;
            calls = null;
            opsDigest = null;
            traceDigest = null;
            traceLen = null;
            frames = null;
            records = null;
            runWords = null;
            pairs = null;
            recordDigest = null;
            runDigest = null;
            pairDigest = null;
            pairSetDigest = null;
            sessionDigest = null;
            keys.clear();
        }

        boolean equivalent() {
            return differing == 0 && crash == null && !timeout;
        }
    }

    /** One export run through the reference engine and every lane. */
    static final class FileRun {
        String path;
        int loadOutcome = Outcome.ACCEPT;
        String loadMessage = "";
        int units;
        /** Units the reference pass ran (up to and including the first one it rejected). */
        int compared;
        long loadNanos;
        long refNanos;
        long wallNanos;
        boolean refMemory;
        /** Largest session footprint of the reference engine after a unit. */
        long refPeak;
        /** This is the second run of the file, on its own, after the Java heap had answered in a parallel run. */
        boolean retried;
        boolean timeout;
        /** Outside a lane: the load or the reference pass died. */
        Throwable crash;
        LaneRun[] lanes = new LaneRun[0];

        /** Some answer of this run is the test JVM's heap speaking, not an engine: run the file again on its own. */
        boolean memory() {
            if (refMemory || crash instanceof OutOfMemoryError) {
                return true;
            }
            for (LaneRun l : lanes) {
                if (l.memoryFailures > 0 || l.crash instanceof OutOfMemoryError) {
                    return true;
                }
            }
            return false;
        }

        long peakFootprint() {
            long p = 0;
            for (LaneRun l : lanes) {
                p = Math.max(p, l.peakFootprint);
            }
            return p;
        }

        /** Some lane declined a unit for memory: the file needs room, whatever footprint is left after the decline. */
        boolean memoryDeclines() {
            for (LaneRun l : lanes) {
                if (l.memoryDeclines > 0) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final Reason[] REASONS = Reason.values();

    private static byte ord(Reason r) {
        return r == null ? 0 : (byte) (r.ordinal() + 1);
    }

    private static Reason reason(byte b) {
        return b == 0 ? null : REASONS[b - 1];
    }

    /** Runs one export. Never throws for a property of the input, of the machine or of the heap. */
    static FileRun run(String path, List<Spec> specs, Options o) {
        FileRun run = new FileRun();
        run.path = path;
        long t0 = System.nanoTime();
        long deadline = o.fileMillis <= 0 ? Long.MAX_VALUE : t0 + o.fileMillis * 1_000_000L;
        boolean permit = false;
        try {
            if (o.cpu != null) {
                o.cpu.acquireUninterruptibly();
                permit = true;
            }
            try (M8Harness h = M8Harness.load(path)) {
                if (o.holder != null) {
                    o.holder[0] = h;
                }
                run.loadNanos = h.loadNanos;
                run.lanes = new LaneRun[specs.size()];
                for (int i = 0; i < run.lanes.length; i++) {
                    run.lanes[i] = new LaneRun(specs.get(i));
                }
                if (h.loadOutcome != Outcome.ACCEPT) {
                    // The loader decides the file for every engine alike: nothing to compare.
                    run.loadOutcome = h.loadOutcome;
                    run.loadMessage = h.loadMessage;
                    for (LaneRun l : run.lanes) {
                        Diff.FileResult d = new Diff.FileResult();
                        d.path = path;
                        d.status = "LOADER";
                        d.refVerdict = h.loadOutcome;
                        d.machineVerdict = h.loadOutcome;
                        d.loadMessage = h.loadMessage;
                        d.loadNanos = h.loadNanos;
                        l.diff = d;
                    }
                    return run;
                }
                run.units = h.units;
                if (o.refBytes > 0) {
                    h.refBytes = o.refBytes;
                }
                h.refDeclines = o.refDeclines;

                // ---- pass 1: the reference engine -------------------------------------------------------
                byte[] oracle = new byte[h.units];
                byte[] oracleReason = new byte[h.units];
                Map<Integer, String> oracleMessage = new HashMap<>();
                while (!o.cancel && System.nanoTime() < deadline && h.next()) {
                    int u = h.unit;
                    oracle[u] = (byte) h.oracle;
                    if (h.oracle != Outcome.ACCEPT) {
                        oracleReason[u] = ord(h.oracleReason);
                        if (oracleMessage.size() < 4096) {
                            oracleMessage.put(u, clip(h.oracleMessage, 160));
                        }
                    }
                    run.compared = u + 1;
                }
                if (o.cancel || System.nanoTime() >= deadline) {
                    run.timeout = true;
                }
                run.refNanos = h.oracleTotalNanos;
                run.refMemory = h.refMemory;
                run.refPeak = h.refPeakFootprint;
                h.endReferencePass();

                // ---- pass 2: the lanes ------------------------------------------------------------------
                final int n = run.compared;
                if (o.parallelLanes && run.lanes.length > 1) {
                    if (permit) {
                        o.cpu.release();
                        permit = false;
                    }
                    // Two groups, one after the other (wave-6 integration): first lane 0 and the lanes held against
                    // another lane by CONV, then the rest (the generic tier, the audits). The lanes of a file are
                    // engines of their own over ONE store, and a code store leaves the id of a binder's sub-block
                    // in the node's reserved word (CodeStore.addSub -> TermStore.setBinderHint; a hint that does not
                    // name the reader's own sub-block is ignored and the closure is walked). Engines that elaborate
                    // in the same order (the conventions do) give a binder the same id; an audit lane re-computes
                    // memo hits and elaborates in another order, and its hints made the compared lanes walk terms
                    // their blocks would have run: tick differences that were the harness's, never the same twice
                    // (gate of warm,gated,expect + code,casetrees on Std and the Cedar slice, 18:35Z). A real run
                    // has one machine engine per store.
                    for (int group = 0; group < 2; group++) {
                        Thread[] threads = new Thread[run.lanes.length];
                        for (int i = 0; i < threads.length; i++) {
                            final LaneRun l = run.lanes[i];
                            if ((i == 0 || l.spec.how == CONV) != (group == 0)) {
                                continue;
                            }
                            threads[i] = laneThread(l, () -> {
                                if (o.cpu != null) {
                                    o.cpu.acquireUninterruptibly();
                                }
                                try {
                                    pass(h, l, n, o, deadline);
                                } finally {
                                    if (o.cpu != null) {
                                        o.cpu.release();
                                    }
                                }
                            });
                            threads[i].start();
                        }
                        for (Thread t : threads) {
                            if (t != null) {
                                joinQuietly(t);
                            }
                        }
                    }
                    if (o.cpu != null) {
                        o.cpu.acquireUninterruptibly();
                        permit = true;
                    }
                } else {
                    for (LaneRun l : run.lanes) {
                        if (o.inlineLanes && l.spec.config.hostDepth != Op.HOST_DEPTH_UNBOUNDED) {
                            pass(h, l, n, o, deadline);
                        } else {
                            Thread t = laneThread(l, () -> pass(h, l, n, o, deadline));
                            t.start();
                            joinQuietly(t);
                        }
                    }
                }

                // ---- the comparisons --------------------------------------------------------------------
                for (LaneRun l : run.lanes) {
                    if (l.timeout) {
                        run.timeout = true;
                    }
                    l.diff = againstReference(h, run, l, oracle, oracleReason, oracleMessage, o);
                }
                for (LaneRun l : run.lanes) {
                    if (l.spec.against >= 0 && l.spec.how != NONE) {
                        againstLane(h, run, run.lanes[l.spec.against], l);
                    }
                }
                for (LaneRun l : run.lanes) {
                    l.forget();
                }
            }
        } catch (IOException | RuntimeException | OutOfMemoryError | StackOverflowError e) {
            run.crash = e;
        } finally {
            if (permit) {
                o.cpu.release();
            }
            run.wallNanos = System.nanoTime() - t0;
        }
        return run;
    }

    private static Thread laneThread(LaneRun l, Runnable body) {
        long stack = l.spec.config.hostDepth == Op.HOST_DEPTH_UNBOUNDED ? 64L << 20 : 2L << 20;
        Thread t = new Thread(null, () -> {
            try {
                body.run();
            } catch (Throwable e) { // a report records everything, also an Error out of a judgment
                if (l.crash == null) {
                    l.crash = e;
                }
            }
        }, "m8-lane-" + l.spec.config.label, stack);
        t.setDaemon(true);
        return t;
    }

    private static void joinQuietly(Thread t) {
        boolean interrupted = false;
        while (true) {
            try {
                t.join();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** One lane over the units {@code [0, n)}. */
    private static void pass(M8Harness h, LaneRun l, int n, Options o, long deadline) {
        l.allocate(n);
        M8Harness.Lane lane = null;
        Map<String, String> intern = new HashMap<>();
        try {
            lane = h.passLane(l.spec.config);
            for (int u = 0; u < n; u++) {
                if (o.cancel || ((u & 63) == 0 && System.nanoTime() >= deadline)) {
                    l.timeout = true;
                    break;
                }
                lane.runUnit(u);
                l.outcome[u] = (byte) lane.outcome;
                l.reason[u] = ord(lane.reason);
                M8Harness.Obs ob = lane.obs;
                byte f = 0;
                if (ob != null) {
                    f |= CHECKED;
                    l.engOutcome[u] = (byte) ob.outcome;
                    l.engReason[u] = ord(ob.reason);
                    l.ticks[u] = ob.ticks;
                    l.calls[u] = ob.calls;
                    l.opsDigest[u] = digest(ob.callsByOp);
                    l.traceDigest[u] = ob.traceDigest;
                    l.traceLen[u] = ob.traceLen;
                    l.frames[u] = (int) Math.min(Integer.MAX_VALUE, ob.framesWritten);
                    if (ob.hasDigest) {
                        f |= DIGEST;
                        l.records[u] = ob.records;
                        l.runWords[u] = ob.runWords;
                        l.pairs[u] = ob.pairs;
                        l.recordDigest[u] = ob.recordDigest;
                        l.runDigest[u] = ob.runDigest;
                        l.pairDigest[u] = ob.pairDigest;
                        l.pairSetDigest[u] = ob.pairSetDigest;
                    }
                    if (ob.overflow) {
                        f |= OVERFLOW;
                    }
                    if (ob.memory) {
                        f |= MEMORY;
                    }
                    if (ob.outcome == Outcome.DECLINE && ob.reason == Reason.CANCELLED) {
                        f |= DEADLINE;
                    }
                    if (ob.code == MachineErrors.E_AUDIT) {
                        f |= AUDIT;
                        l.audits++;
                        if (l.auditLines.size() < 8) {
                            l.auditLines.add("unit " + u + " '" + h.unitName(u) + "': " + clip(lane.message, 400));
                        }
                    }
                }
                l.flags[u] = f;
                l.sessionDigest[u] = lane.sessionDigest;
                if (lane.outcome == Outcome.DECLINE && lane.reason == Reason.BUDGET_MEMORY) {
                    l.memoryDeclines++; // the unit grew until a limit said no: the file is a heavy one, whatever its footprint is now
                }
                if (lane.outcome != Outcome.ACCEPT) {
                    String key = Diff.reasonKey(lane.reason, lane.message, h.unitName(u));
                    String same = intern.putIfAbsent(key, key);
                    l.keys.put(u, same == null ? key : same);
                }
                lane.endUnit();
                l.ranUnits = u + 1;
            }
            if (l.ranUnits == n && n > 0 && l.spec.config.digest && l.spec.config.incrementalDigest && lane.m != null) {
                l.finalDigest = lane.sessionDigestNow();
            }
        } catch (RuntimeException | Error e) {
            l.crash = e;
        } finally {
            if (lane != null) {
                l.checks = lane.checks;
                l.accepted = lane.accepted;
                l.rejected = lane.rejected;
                l.declined = lane.declined;
                l.ticksTotal = lane.ticks;
                l.callsTotal = lane.calls;
                l.framesTotal = lane.framesWritten;
                l.spills = lane.spills;
                l.nanos = lane.nanos;
                l.memoryFailures = lane.memoryFailures;
                l.overflows = lane.overflows;
                l.cancelled = lane.cancelled;
                l.peakFootprint = lane.peakFootprint;
                lane.release();
            }
        }
    }

    private static long digest(long[] a) {
        long h = 0x9E3779B97F4A7C15L;
        for (long x : a) {
            h ^= x;
            h *= 0xFF51AFD7ED558CCDL;
            h ^= h >>> 29;
        }
        return h;
    }

    static String clip(String s, int n) {
        s = String.valueOf(s).replace('\n', ' ');
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    // ---------------------------------------------------------------------------------------------------
    // A lane against the reference engine: the counting rule of Diff
    // ---------------------------------------------------------------------------------------------------

    private static Diff.FileResult againstReference(M8Harness h, FileRun run, LaneRun l, byte[] oracle, byte[] oracleReason,
            Map<Integer, String> oracleMessage, Options o) {
        Diff.FileResult r = new Diff.FileResult();
        r.path = run.path;
        r.units = run.units;
        r.loadNanos = run.loadNanos;
        r.refNanos = run.refNanos;
        r.machineNanos = l.nanos;
        r.ticks = l.ticksTotal;
        r.calls = l.callsTotal;
        r.framesWritten = l.framesTotal;
        r.memory = run.refMemory || l.memoryFailures > 0 || l.crash instanceof OutOfMemoryError;
        if (l.outcome == null) {
            // the pass never started
            r.crash = l.crash != null ? l.crash : new IllegalStateException("the lane did not run");
            return Diff.finish(r);
        }
        for (int u = 0; u < l.ranUnits; u++) {
            int mine = l.outcome[u];
            int orc = oracle[u];
            r.compared++;
            if ((l.flags[u] & CHECKED) != 0) {
                r.engineChecks++;
                if (l.engOutcome[u] == Outcome.ACCEPT) {
                    r.accepted++;
                } else if (l.engOutcome[u] == Outcome.REJECT) {
                    r.rejected++;
                } else {
                    r.engineDeclined++;
                }
            }
            if (mine != Outcome.ACCEPT && r.machineFirstUnit < 0) {
                r.machineFirstUnit = u;
                r.machineVerdict = mine;
            }
            if (orc != Outcome.ACCEPT && r.refVerdict == Outcome.ACCEPT) {
                r.refVerdict = orc;
            }
            if (mine == orc) {
                r.agree++;
                continue;
            }
            if (orc == Outcome.DECLINE) {
                // the reference engine has no verdict on this unit: nothing to hold the machine's against
                r.refDeclined++;
                String line = "ref-declined unit " + u + " " + h.unitKindName(u) + " '" + h.unitName(u) + "': machine " + Outcome.name(mine)
                        + ", reference decline (" + reason(oracleReason[u]) + ": " + oracleMessage.getOrDefault(u, "") + ")";
                if (r.first.isEmpty()) {
                    r.first = line;
                    r.firstUnit = u;
                }
                if (r.lines.size() < o.linesPerFile) {
                    r.lines.add(line);
                }
                continue;
            }
            String kind;
            if (mine == Outcome.DECLINE) {
                r.declined++;
                kind = "declined    ";
            } else if (mine == Outcome.ACCEPT) {
                r.soundness++;
                kind = "SOUNDNESS   ";
            } else {
                r.completeness++;
                kind = "COMPLETENESS";
            }
            String key = l.keys.getOrDefault(u, String.valueOf(reason(l.reason[u])));
            r.reasons.merge(key, 1, Integer::sum);
            String line = kind + " unit " + u + " " + h.unitKindName(u) + " '" + h.unitName(u) + "': machine " + Outcome.name(mine)
                    + (mine == Outcome.ACCEPT ? "" : " (" + key + ")") + ", reference " + Outcome.name(orc)
                    + (orc == Outcome.ACCEPT ? "" : " (" + reason(oracleReason[u]) + ": " + oracleMessage.getOrDefault(u, "") + ")");
            if (r.firstUnit < 0) {
                r.firstUnit = u;
                r.first = line;
            }
            if (r.lines.size() < o.linesPerFile) {
                r.lines.add(line);
            }
        }
        r.crash = l.crash;
        r.timeout = l.timeout || run.timeout;
        return Diff.finish(r);
    }

    // ---------------------------------------------------------------------------------------------------
    // A lane against another lane
    // ---------------------------------------------------------------------------------------------------

    private static void againstLane(M8Harness h, FileRun run, LaneRun a, LaneRun b) {
        if (a.outcome == null || b.outcome == null) {
            return; // a pass that never started is reported as the crash it is
        }
        int n = Math.min(a.ranUnits, b.ranUnits);
        boolean unbounded = b.spec.config.hostDepth == Op.HOST_DEPTH_UNBOUNDED && a.spec.config.hostDepth != Op.HOST_DEPTH_UNBOUNDED;
        for (int u = 0; u < n; u++) {
            boolean ca = (a.flags[u] & CHECKED) != 0;
            boolean cb = (b.flags[u] & CHECKED) != 0;
            if (!ca && !cb && a.outcome[u] == b.outcome[u]) {
                continue;
            }
            if (((a.flags[u] | b.flags[u]) & MEMORY) != 0) {
                continue; // the heap answered, not the machine: the file is run again
            }
            if (((a.flags[u] | b.flags[u]) & DEADLINE) != 0) {
                b.deadlineSkipped++; // one lane ran out of its wall-clock limit (a loaded host): nothing to compare
                continue;
            }
            b.comparedWithOther++;
            String d = null;
            if (b.spec.how == CONV && unbounded && cb && b.frames[u] != 0) {
                d = b.spec.config.label + " wrote " + b.frames[u] + " frames; without a depth bound no judgment is ever suspended (H1)";
            } else if ((b.flags[u] & OVERFLOW) != 0 || (a.flags[u] & OVERFLOW) != 0) {
                b.overflowSkipped++;
                continue;
            } else if (b.spec.how == OUTCOME && b.outcome[u] == Outcome.DECLINE && a.outcome[u] != Outcome.DECLINE
                    && (a.spec.config.switches & Op.SW_EAGER) != 0 && (b.spec.config.switches & Op.SW_EAGER) == 0) {
                // package E1: the lane with the switch `eager` DECIDES a declaration that a lane without it declines
                // (M6-F26, the eager rule). That is what the switch is for; the verdict of the decided unit is held
                // against the reference engine by the differential part. Counted, not a difference.
                b.eagerOnly++;
                continue;
            } else if (a.outcome[u] != b.outcome[u]) {
                d = "admission outcome " + Outcome.name(a.outcome[u]) + " (" + reason(a.reason[u]) + ") against " + Outcome.name(b.outcome[u])
                        + " (" + clip(b.keys.getOrDefault(u, String.valueOf(reason(b.reason[u]))), 300) + ")";
            } else if (ca != cb) {
                d = "the unit reached the engine in one lane only";
            } else if (a.engOutcome[u] != b.engOutcome[u]) {
                d = "outcome " + Outcome.name(a.engOutcome[u]) + " (" + reason(a.engReason[u]) + ") against " + Outcome.name(b.engOutcome[u])
                        + " (" + clip(b.keys.getOrDefault(u, String.valueOf(reason(b.engReason[u]))), 300) + ")";
            } else if (a.engReason[u] != b.engReason[u]) {
                String text = "the same outcome " + Outcome.name(a.engOutcome[u]) + " for another reason: " + reason(a.engReason[u]) + " against "
                        + clip(b.keys.getOrDefault(u, String.valueOf(reason(b.engReason[u]))), 300);
                if (b.spec.how == CONV) {
                    d = text; // a host convention changes nothing observable, the reason included
                } else {
                    // another tier or an audit may meet another error of a wrong declaration first: reported, not a difference
                    b.reasonNotes++;
                    if (b.reasonLines.size() < 8) {
                        b.reasonLines.add(Diff.shortName(run.path) + " unit " + u + " " + h.unitKindName(u) + " '" + h.unitName(u) + "': " + a.spec.config.label
                                + " against " + b.spec.config.label + ": " + text);
                    }
                }
            } else if (b.spec.how == CONV) {
                d = observables(a, b, u);
                if (d != null && d.startsWith("NOTE")) {
                    b.pairOrderNotes++;
                    d = null;
                }
            }
            if (d != null) {
                b.differing++;
                if (b.differences.size() < 40) {
                    b.differences.add(Diff.shortName(run.path) + " unit " + u + " " + h.unitKindName(u) + " '" + h.unitName(u) + "': "
                            + a.spec.config.label + " against " + b.spec.config.label + ": " + d);
                }
            }
        }
        if (b.spec.how == CONV && a.ranUnits == b.ranUnits && a.finalDigest != b.finalDigest && a.finalDigest != 0 && b.finalDigest != 0
                && a.memoryFailures == 0 && b.memoryFailures == 0 && b.overflowSkipped == 0) {
            b.differing++;
            b.differences.add(Diff.shortName(run.path) + ": " + a.spec.config.label + " against " + b.spec.config.label
                    + ": the sessions the lanes end the file with differ (a memo word of an earlier record, or the status of an earlier pair)");
        }
    }

    /** The first observable of unit {@code u} that differs between two lanes, null if none ({@code TierEq.sameObservables} on the recorded words). */
    private static String observables(LaneRun a, LaneRun b, int u) {
        if (a.ticks[u] != b.ticks[u]) {
            return "ticks " + a.ticks[u] + " against " + b.ticks[u];
        }
        if (a.calls[u] != b.calls[u]) {
            return "call events " + a.calls[u] + " against " + b.calls[u];
        }
        if (a.opsDigest[u] != b.opsDigest[u]) {
            return "the same number of call events (" + a.calls[u] + "), distributed differently over the ops";
        }
        if (a.spec.config.trace && b.spec.config.trace && (a.traceLen[u] != b.traceLen[u] || a.traceDigest[u] != b.traceDigest[u])) {
            return "the frame trace differs (" + a.traceLen[u] + " against " + b.traceLen[u] + " call events recorded)";
        }
        if ((a.flags[u] & DIGEST) != 0 && (b.flags[u] & DIGEST) != 0) {
            if (a.records[u] != b.records[u]) {
                return "records made: " + a.records[u] + " against " + b.records[u];
            }
            if (a.recordDigest[u] != b.recordDigest[u]) {
                return "the same number of records (" + a.records[u] + ") with different words (identity, flags or memo words)";
            }
            if (a.runWords[u] != b.runWords[u]) {
                return "run words: " + a.runWords[u] + " against " + b.runWords[u];
            }
            if (a.runDigest[u] != b.runDigest[u]) {
                return "the same number of run words (" + a.runWords[u] + ") with different contents";
            }
            if (a.pairs[u] != b.pairs[u]) {
                return "pair entries: " + a.pairs[u] + " against " + b.pairs[u];
            }
            if (a.pairSetDigest[u] != b.pairSetDigest[u]) {
                return "the same number of pair entries (" + a.pairs[u] + ") with different contents";
            }
            if (a.sessionDigest[u] != b.sessionDigest[u]) {
                return a.sessionDigest[u] == 0 || b.sessionDigest[u] == 0 ? "one lane reset its session after this unit, the other did not"
                        : "the sessions differ at the reset after this unit although every declaration added the same words: a memo"
                                + " word of an earlier record, or the status of an earlier pair";
            }
            if (a.pairDigest[u] != b.pairDigest[u]) {
                return "NOTE the pair entries are the same set, inserted in another order";
            }
        }
        return null;
    }
}
