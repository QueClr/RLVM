package rlvm.truffle;

import com.oracle.truffle.runtime.OptimizedCallTarget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.WeakHashMap;
import rlvm.machine.Machine;
import rlvm.machine.T2Bridge;
import rlvm.machine.Tier;

/**
 * The T2 tier of one machine engine (ISA 8.5): the heat listener, the promotion governor, the table of
 * installed blocks that the workers read, and the bookkeeping of the report.
 *
 * <h2>Promotion (rent-to-own in CPU time)</h2>
 * The machine's heat accounting ({@code Tier.Heat}) calls {@link #hot} on a worker thread every
 * {@code period} entries of a block by that worker. A block is promoted iff
 * <pre>
 *   spent(b) >= rent * cost(b)                         spent(b) = entries(b) * selfNanos(b) / timed(b), summed over the workers
 *                                                      cost(b)  = the compile CPU time expected for b: c0 + c1 * instructions,
 *                                                                 c1 learned from the compilations done so far
 *   compileCpu + pending + cost(b) <= share * processCpu      the governor: compilation never takes more than a set share
 *   promoted < max, and b is not hostile               (an undecodable block, a role that is switched off, a failed compilation)
 * </pre>
 * Promotion itself (building the root, submitting it to Graal) happens on the promoter thread of
 * {@link T2Runtime}; the one compiler thread works in the background; the block keeps running in the
 * stepper until its code is installed, and for ever if the compilation fails (ISA 8.6).
 */
final class T2Engine {

    /** What a worker needs to enter the compiled code of a block; immutable, so it can be read without synchronisation. */
    static final class Installed {
        final OptimizedCallTarget target;
        final BlockRoot root;
        /** pc -> entry number of the root, -1: no entry. */
        final short[] entryOfPc;

        Installed(OptimizedCallTarget target, BlockRoot root, int len) {
            this.target = target;
            this.root = root;
            short[] map = new short[len];
            Arrays.fill(map, (short) -1);
            for (int k = 0; k < root.entryPcs.length; k++) {
                map[root.entryPcs[k]] = (short) k;
            }
            this.entryOfPc = map;
        }
    }

    /** One promoted block, for the report. */
    static final class Rec {
        final int blk;
        final String what;
        final long entriesAtPromotion;
        final long spentAtPromotion;
        final double costEstimate;
        final long promotedAtProcessCpu;
        int instructions;
        int entryPoints;
        long compileCpuNanos = -1;
        int codeSize;
        int graphNodes;
        /** Heat of the block when its code was installed (the base tier's part). */
        long entries0;
        long self0;
        long timed0;
        long nanos0;
        long installedAtProcessCpu = -1;
        String failure;
        int deopts;
        boolean invalidated;
        /**
         * The root while its compilation is pending. The runtime's compile queue holds a call target only
         * weakly: without this reference a collection between submission and compilation drops the task
         * silently and the block would stay "pending" for ever.
         */
        volatile BlockRoot pendingRoot;

        Rec(int blk, String what, long entries, long spent, double cost, long processCpu) {
            this.blk = blk;
            this.what = what;
            this.entriesAtPromotion = entries;
            this.spentAtPromotion = spent;
            this.costEstimate = cost;
            this.promotedAtProcessCpu = processCpu;
        }
    }

    private static final byte COLD = 0;
    private static final byte PENDING = 1;
    private static final byte INSTALLED = 2;
    private static final byte FAILED = 3;

    private static final WeakHashMap<Object, T2Engine> ENGINES = new WeakHashMap<>();
    private static boolean hookInstalled;

    final Settings settings;
    final Tier.Heat heat;
    /** Block id -> installed code; replaced as a whole at every installation (copy on write); read racily by the workers. */
    Installed[] table = new Installed[0];
    private volatile int version;

    private byte[] state = new byte[256];
    private int[] instructions = new int[256];
    private final ArrayList<Rec> recs = new ArrayList<>();
    private final ArrayList<T2Tier> tiers = new ArrayList<>();
    private int promoted;
    private int installedCount;
    private int failedCount;
    private long compileNanos;
    private long compiledInstructions;
    private double pendingNanos;
    private long governorHeld;
    private long deopts;
    private volatile String disabled;

    private T2Engine(Settings settings, Tier.Heat heat) {
        this.settings = settings;
        this.heat = heat;
    }

    /** The tier of the engine of machine {@code m}; null if the engine has no heat accounting (not a machine engine). */
    static T2Engine of(Machine m) {
        Object key = T2Bridge.engine(m);
        if (key == null) {
            return null;
        }
        synchronized (ENGINES) {
            T2Engine e = ENGINES.get(key);
            if (e == null) {
                Tier.Heat heat = T2Bridge.heat(m);
                if (heat == null) {
                    return null;
                }
                Settings s = new Settings();
                e = new T2Engine(s, heat);
                heat.timing(s.timing);
                heat.listen(e::hot, s.at > 0 ? Math.min(s.period, s.at) : s.period);
                ENGINES.put(key, e);
                if ((s.trace || s.report != null) && !hookInstalled) {
                    hookInstalled = true;
                    try {
                        Runtime.getRuntime().addShutdownHook(new Thread(T2Engine::reportAll, "rlvm-t2-report"));
                    } catch (Throwable t) {
                        // no report at exit
                    }
                }
            }
            return e;
        }
    }

    void register(T2Tier t) {
        synchronized (tiers) {
            tiers.add(t);
        }
    }

    static void warn(String msg) {
        System.err.println("rlvm-t2: " + msg);
    }

    private void trace(String msg) {
        if (settings.trace) {
            System.err.println("rlvm-t2: " + msg);
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // The heat listener (worker threads)
    // ---------------------------------------------------------------------------------------------------

    /** Called by the machine's heat accounting on a worker thread, every {@code period} entries of {@code blk} by that worker. */
    void hot(Tier.Heat h, Machine m, int blk) {
        if (disabled != null) {
            return;
        }
        byte[] st = state;
        if (blk < st.length && st[blk] != COLD) {
            return;
        }
        try {
            consider(h, blk);
        } catch (Throwable t) {
            disable("the governor failed (" + t + ")");
        }
    }

    private void consider(Tier.Heat h, int blk) {
        double cost = costNanos(h, blk);
        if (cost < 0) {
            return;
        }
        long entries = h.entries(blk);
        long spent = h.spentNanos(blk);
        if (h.timed(blk) == 0) {
            // not sampled yet (one entry in `timing` is timed): the model of Settings.unit0Ns / unit1Ns
            int n;
            synchronized (this) {
                n = blk < instructions.length ? instructions[blk] : 0;
            }
            spent = (long) (entries * (settings.unit0Ns + settings.unit1Ns * n));
        }
        long process = Cpu.processNanos();
        if (settings.at > 0) {
            if (entries < settings.at) {
                return;
            }
        } else {
            if (spent < settings.rent * cost) {
                return;
            }
            synchronized (this) {
                if (compileNanos + pendingNanos + cost > settings.share * process) {
                    governorHeld++;
                    return;
                }
            }
        }
        Rec rec;
        synchronized (this) {
            if (stateOf(blk) != COLD || promoted >= settings.max) {
                return;
            }
            setState(blk, PENDING);
            promoted++;
            pendingNanos += cost;
            rec = new Rec(blk, h.describe(blk), entries, spent, cost, process);
            rec.instructions = instructions[blk];
            recs.add(rec);
        }
        trace(String.format(Locale.ROOT, "promote  %s | entries %d, spent %.2f ms, cost estimate %.2f ms, process cpu %.2f s", rec.what,
                entries, spent / 1e6, cost / 1e6, process / 1e9));
        T2Runtime.submit(this, rec, settings.sync);
    }

    /** The compile CPU time expected for {@code blk} in nanoseconds; -1: the block cannot be promoted. */
    private double costNanos(Tier.Heat h, int blk) {
        int n;
        synchronized (this) {
            if (blk >= instructions.length) {
                instructions = Arrays.copyOf(instructions, Math.max(instructions.length << 1, blk + 1));
            }
            n = instructions[blk];
        }
        if (n == 0) {
            int[] code = h.block(blk);
            Decoded d = code == null ? null : new Decoded(code, blk, settings.slots && settings.nodes);
            String why = d == null ? "no such block" : d.hostile != null ? d.hostile : !roleAllowed(d) ? "role switched off"
                    : d.insn.length > settings.maxInstr ? "too large" : d.insn.length < settings.minInstr ? "too small" : null;
            synchronized (this) {
                if (why != null) {
                    setState(blk, FAILED);
                    return -1;
                }
                n = instructions[blk] = d.insn.length;
            }
        }
        synchronized (this) {
            if (installedCount >= 3 && compiledInstructions > 0) {
                return (double) compileNanos / compiledInstructions * n;
            }
        }
        return (settings.cost0Ms + settings.cost1Ms * n) * 1e6;
    }

    private boolean roleAllowed(Decoded d) {
        if ((d.flags & T2Bridge.BLKF_SUB) != 0) {
            return (settings.roles & Settings.ROLE_SUB_BIT) != 0;
        }
        return (settings.roles >>> d.role & 1) != 0;
    }

    private byte stateOf(int blk) {
        return blk < state.length ? state[blk] : COLD;
    }

    private void setState(int blk, byte s) {
        if (blk >= state.length) {
            state = Arrays.copyOf(state, Math.max(state.length << 1, blk + 1));
        }
        state[blk] = s;
    }

    // ---------------------------------------------------------------------------------------------------
    // Outcomes of a promotion (promoter thread, compiler thread)
    // ---------------------------------------------------------------------------------------------------

    /** The compiled code of {@code root} is installed: from now on the workers enter it. */
    void installed(BlockRoot root, OptimizedCallTarget target, long cpuNanos, int codeSize, int graphNodes) {
        Rec rec = root.rec;
        synchronized (this) {
            if (stateOf(root.blk) != PENDING) {
                return;
            }
            setState(root.blk, INSTALLED);
            installedCount++;
            pendingNanos = Math.max(0, pendingNanos - rec.costEstimate);
            if (cpuNanos >= 0) {
                compileNanos += cpuNanos;
                compiledInstructions += root.instructions;
            }
            rec.compileCpuNanos = cpuNanos;
            rec.codeSize = codeSize;
            rec.graphNodes = graphNodes;
            rec.entryPoints = root.entryPcs.length;
            rec.entries0 = heat.entries(root.blk);
            rec.self0 = heat.selfNanos(root.blk);
            rec.timed0 = heat.timed(root.blk);
            rec.nanos0 = heat.nanos(root.blk);
            rec.installedAtProcessCpu = Cpu.processNanos();
            rec.pendingRoot = null; // the table holds the root from here on
            Installed[] t = Arrays.copyOf(table, Math.max(table.length, root.blk + 1));
            t[root.blk] = new Installed(target, root, root.codeLength());
            table = t;
            version++;
        }
        trace(String.format(Locale.ROOT, "compiled %s | %.2f ms cpu, %d bytes, %d graph nodes, %d instructions, %d entries", rec.what,
                cpuNanos / 1e6, codeSize, graphNodes, root.instructions, root.entryPcs.length));
        writeReport();
    }

    /** The promotion of the block of {@code rec} failed: it stays in the stepper for good (ISA 8.5 t2Hostile). */
    void failed(Rec rec, String why, long cpuNanos) {
        synchronized (this) {
            if (stateOf(rec.blk) == FAILED) {
                return;
            }
            if (stateOf(rec.blk) == INSTALLED) {
                Installed[] t = table.clone();
                if (rec.blk < t.length) {
                    t[rec.blk] = null;
                }
                table = t;
                version++;
                installedCount--;
            } else {
                pendingNanos = Math.max(0, pendingNanos - rec.costEstimate);
            }
            setState(rec.blk, FAILED);
            rec.pendingRoot = null;
            failedCount++;
            if (cpuNanos > 0) {
                compileNanos += cpuNanos;
            }
            if (rec.failure == null) {
                rec.failure = why;
            }
        }
        trace("FAILED   " + rec.what + " | " + why);
        writeReport();
    }

    void deoptimized(BlockRoot root, String reason) {
        synchronized (this) {
            deopts++;
            root.rec.deopts++;
        }
        trace("DEOPT    " + root.rec.what + " | " + reason);
    }

    void invalidated(BlockRoot root, String reason) {
        root.rec.invalidated = true;
        failed(root.rec, "invalidated: " + reason, 0);
    }

    /** No further promotion by this engine (no optimising runtime, an internal failure). Blocks already installed stay. */
    void disable(String why) {
        if (disabled == null) {
            disabled = why;
            warn(why + "; no block is promoted from here on (the machine runs them in the stepper)");
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // Report
    // ---------------------------------------------------------------------------------------------------

    private static void reportAll() {
        ArrayList<T2Engine> all;
        synchronized (ENGINES) {
            all = new ArrayList<>(ENGINES.values());
        }
        for (T2Engine e : all) {
            if (e.settings.trace) {
                System.err.print(e.report());
            }
            e.writeReport();
        }
    }

    private void writeReport() {
        if (settings.report == null) {
            return;
        }
        try {
            Files.writeString(Path.of(settings.report), report());
        } catch (IOException | RuntimeException e) {
            // a report is not worth a failure
        }
    }

    /** Activations run by compiled code so far, over all machines of the engine (racy sum). */
    long compiledCalls() {
        long s = 0;
        synchronized (tiers) {
            for (T2Tier t : tiers) {
                s += t.calls;
            }
        }
        return s;
    }

    long notTaken() {
        long s = 0;
        synchronized (tiers) {
            for (T2Tier t : tiers) {
                s += t.notTaken;
            }
        }
        return s;
    }

    synchronized int promotedCount() {
        return promoted;
    }

    synchronized int installedCount() {
        return installedCount;
    }

    synchronized int failedCount() {
        return failedCount;
    }

    synchronized long compileNanos() {
        return compileNanos;
    }

    synchronized long deopts() {
        return deopts;
    }

    synchronized ArrayList<Rec> records() {
        return new ArrayList<>(recs);
    }

    /**
     * The report of the tier (README "Report"): totals, then one line per promoted block with its compile cost,
     * the time per entry before and after the installation as the machine's heat accounting sampled it, and
     * the break-even number of executions that follows from the two.
     */
    String report() {
        StringBuilder sb = new StringBuilder();
        ArrayList<Rec> rs = records();
        long process = Cpu.processNanos();
        long calls = compiledCalls();
        synchronized (this) {
            sb.append(String.format(Locale.ROOT,
                    "rlvm-t2: promoted %d, installed %d, failed %d, pending %d | compile cpu %.1f ms (%.2f%% of process cpu %.2f s) | "
                            + "compiled activations %d, not taken %d | deopts %d | governor held %d | rent %.2f share %.3f period %d%s%n",
                    promoted, installedCount, failedCount, promoted - installedCount - failedCount, compileNanos / 1e6,
                    process == 0 ? 0.0 : 100.0 * compileNanos / process, process / 1e9, calls, notTaken(), deopts, governorHeld,
                    settings.rent, settings.share, settings.period, disabled == null ? "" : " | DISABLED: " + disabled));
        }
        sb.append("rlvm-t2: block | compile ms | code bytes | instr | entries before / after | ns per entry before -> after (self) | saved ns | break-even entries | net ms\n");
        double net = 0;
        for (Rec r : rs) {
            if (r.failure != null) {
                sb.append(String.format(Locale.ROOT, "rlvm-t2: %s | FAILED: %s%n", r.what, r.failure));
                if (r.compileCpuNanos > 0) {
                    net -= r.compileCpuNanos / 1e6;
                }
                continue;
            }
            if (r.compileCpuNanos < 0) {
                sb.append(String.format(Locale.ROOT, "rlvm-t2: %s | pending (promoted at %d entries)%n", r.what, r.entriesAtPromotion));
                continue;
            }
            long e1 = heat.entries(r.blk) - r.entries0;
            long t1 = heat.timed(r.blk) - r.timed0;
            double before = r.timed0 == 0 ? Double.NaN : (double) r.self0 / r.timed0;
            double after = t1 == 0 ? Double.NaN : (double) (heat.selfNanos(r.blk) - r.self0) / t1;
            double saved = before - after;
            double be = saved > 0 ? r.compileCpuNanos / saved : Double.POSITIVE_INFINITY;
            double gain = Double.isNaN(saved) ? Double.NaN : (saved * e1 - r.compileCpuNanos) / 1e6;
            if (!Double.isNaN(gain)) {
                net += gain;
            }
            sb.append(String.format(Locale.ROOT, "rlvm-t2: %s | %.2f | %d | %d | %d / %d | %.0f -> %.0f | %.0f | %.0f | %.2f%n", r.what,
                    r.compileCpuNanos / 1e6, r.codeSize, r.instructions, r.entries0, e1, before, after, saved, be, gain));
        }
        sb.append(String.format(Locale.ROOT, "rlvm-t2: net effect by the sampled times: %.1f ms (positive = CPU time saved; compiler included)%n", net));
        return sb.toString();
    }
}
