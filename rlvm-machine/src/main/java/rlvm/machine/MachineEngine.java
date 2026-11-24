package rlvm.machine;

import rlvm.api.Engine;
import rlvm.api.EngineConfig;
import rlvm.api.EngineFactory;
import rlvm.api.MachineErrors;
import rlvm.api.Session;
import rlvm.api.StatsSink;
import rlvm.api.WorkerContext;
import rlvm.store.Store;

/**
 * The machine as an {@link Engine} (ISA 14.1): holds the store, the configuration and the engine-wide
 * tables ({@link FactStore}, {@link CodeStore}), which are functions of the store alone and obey the
 * publication rule. Owner: M6.
 *
 * <p>System properties (read once, at engine creation; all default to the v0 configuration):
 * {@code rlvm.machine.switches} = comma-separated staging switches ({@link Debug#parseSwitches}; ignored
 * by a strict engine, ISA 14.2), {@code rlvm.machine.debug} = comma-separated debug modes
 * ({@link Debug#parseModes}), {@code rlvm.machine.hostDepth} = the host depth bound (default 64; 0 = pure
 * worklist machine), {@code rlvm.machine.memocheck.every} / {@code .budget} = sampling and tick budget of
 * the memo re-computations.
 *
 * <p>Statistics ({@link #dumpStats}, ISA 14.5; only with {@code EngineConfig.stats}): sessions account
 * for themselves when they are closed; nothing is counted per step otherwise.
 */
public final class MachineEngine implements Engine {

    public static final EngineFactory FACTORY = MachineEngine::new;

    final Store store;
    final EngineConfig config;
    final FactStore facts;
    final CodeStore code;
    /** Staging switches of every session of this engine; 0 in strict mode (ISA 14.2). */
    final int switches;
    final int debug;
    final int hostDepth;

    private long statSessions;
    private long statUnits;
    private long statAccepted;
    private long statRejected;
    private long statDeclined;
    private long statTicks;
    private long statResets;
    private long statBytesAtReset;
    private long statFrames;
    private long statFrameWords;
    private long statSpills;
    private long statFlushes;
    private long statCuts;
    private long statPolls;
    private long statMaxWp;
    private long statRecords;
    private long statRunWords;
    private long statPairs;
    private long statFactsDerived;
    private long statFactsNone;
    private long statAudits;
    private long statMemoChecks;
    private long statMemoInconclusive;
    private long statMemoByConv;
    private final long[] statCalls = new long[Op.OP_COUNT];
    private final long[] statHits = new long[Op.OP_COUNT];
    private final long[] statProbes = new long[3 * Op.SHORTCUT_COUNT];
    private final long[] statElabs = new long[Op.ROLE_COUNT];
    private final long[] statStayedGeneric = new long[Op.ROLE_COUNT];
    private long statCodeEntered;
    private long statIcHits;
    private long statIcMisses;
    private final long[] statByCode = new long[MachineErrors.COUNT + 1];
    private final String[] statMessages = new String[MachineSession.KEPT_MESSAGES];
    private int statMessagesN;

    /**
     * The machine's own cap on a warm session, in bytes of {@code Machine.engineBytes()} (0 = none): see
     * {@link MachineSession#check}. 1 MiB: with warm sessions the machine's CPU time is lowest at 512 KiB to
     * 1 MiB (one and eight workers), a session of 4 MiB costs more than no warm session at eight workers and
     * one of 16 MiB 17-20 % more, although the instruction count keeps falling as the cap grows - the tables
     * leave the cache (NOTES-M6 F24). Only read when {@code Op.SW_WARM} is on.
     */
    static final long DEFAULT_SESSION_CAP = 1L << 20;

    /**
     * The machine's own tick bound per unit ({@code -X machine.unit-ticks}, 0 = none): a warm unit has no step
     * limit from the driver (RECONCILIATION section 3), and the machine has no cycle test in its delta loops,
     * so a declaration on which the official checker does not terminate (arena tests
     * either/corner-cases/eta-struct-major-no-whnf, eta-struct-major-and-no-whnf) ran until somebody's time
     * limit killed the process. Beyond the bound the unit is declined ({@code E_STEPS}, {@code BUDGET_STEPS})
     * and the reference engine, which detects the cycle, decides it. 2^27 ticks: the
     * largest unit of the eight corpora needs 5.4 M (mathlib), the largest of the 230 tests 41.9 M (perf/magma-list-deep-n36,
     * v0); the native image runs about 9 M ticks per second, so the bound is reached after about 15 s (NOTES-M6 F31).
     */
    static final long DEFAULT_UNIT_TICKS = 1L << 27;

    /** The machine's own settings (system properties {@code rlvm.machine.*}, overridden by {@code -X machine.*}). */
    static final class Settings {
        int switches = Op.SW_DEFAULT;
        int debug;
        int hostDepth = Op.HOST_DEPTH;
        long memoEvery = 1;
        long memoBudget = Debug.MEMO_BUDGET;
        long sessionCapBytes = DEFAULT_SESSION_CAP;
        long unitTicks = DEFAULT_UNIT_TICKS;
        /** File that receives one line per non-ACCEPT outcome of the machine, or null. */
        String declines;

        /** The settings the system properties give; a value that does not parse is an {@code IllegalArgumentException} (fail closed). */
        static Settings fromProperties() {
            Settings s = new Settings();
            s.switches = Debug.parseSwitches(System.getProperty("rlvm.machine.switches", "default"));
            s.sessionCapBytes = Math.max(0, Long.getLong("rlvm.machine.session-kib", DEFAULT_SESSION_CAP >> 10)) << 10;
            s.unitTicks = Math.max(0, Long.getLong("rlvm.machine.unit-ticks", DEFAULT_UNIT_TICKS));
            String d = System.getProperty("rlvm.machine.declines", "");
            s.declines = d.isEmpty() ? null : d;
            s.debug = Debug.parseModes(System.getProperty("rlvm.machine.debug", ""));
            s.hostDepth = Math.max(0, Integer.getInteger("rlvm.machine.hostDepth", Op.HOST_DEPTH));
            s.memoEvery = Math.max(1, Long.getLong("rlvm.machine.memocheck.every", 1));
            s.memoBudget = Math.max(1, Long.getLong("rlvm.machine.memocheck.budget", Debug.MEMO_BUDGET));
            return s;
        }
    }

    final long memoEvery;
    final long memoBudget;
    /** The machine's own cap on a warm session (bytes of {@code Machine.engineBytes()}; 0 = none). */
    final long sessionCapBytes;
    /** The machine's own tick bound per unit (0 = none). */
    final long unitTicks;
    private long statUnitBoundDeclines;
    /** Path of the decline log ({@code -X machine.declines}), or null. */
    final String declinesPath;
    private java.io.Writer declinesOut;
    private boolean declinesFailed;
    private long statCapResets;
    private long statEagerDeclines;
    private long statEagerKept;
    private long statEagerRestarts;
    private long statEagerForgets;
    private long statEagerSites;
    private long statEagerEvents;
    private long statEagerNotes;
    private final long[] statEagerWhy = new long[Machine.ER_WHY_COUNT];
    /** Counters the packages' session states report ({@code Machine.Counters}), by name, in the order first seen. */
    private final java.util.LinkedHashMap<String, long[]> statPackage = new java.util.LinkedHashMap<>();
    private long statAllocGrows;
    private long statAllocGrowBytes;
    private long statAllocResets;
    private long statAllocResetBytes;

    public MachineEngine(Store store, EngineConfig config) {
        this(store, config, Settings.fromProperties());
    }

    MachineEngine(Store store, EngineConfig config, Settings settings) {
        this.store = store;
        this.config = config == null ? EngineConfig.defaults() : config;
        this.facts = new FactStore(store);
        this.code = new CodeStore(store);
        this.switches = this.config.strict ? 0 : settings.switches; // ISA 14.2: a strict engine has no switch
        this.debug = settings.debug | (this.config.memoCheck ? Op.DBG_MEMOCHECK : 0);
        this.hostDepth = settings.hostDepth;
        this.memoEvery = settings.memoEvery;
        this.memoBudget = settings.memoBudget;
        this.sessionCapBytes = settings.sessionCapBytes;
        this.unitTicks = settings.unitTicks;
        this.declinesPath = settings.declines;
    }

    /**
     * One line of the decline log ({@code -X machine.declines=FILE}): called by a session for every outcome of
     * the machine other than ACCEPT. The file is opened for appending at the first line; a failure to write
     * ends the log (it is a diagnostic, never a verdict) and is reported once on the standard error stream.
     */
    synchronized void logOutcome(String line) {
        if (declinesPath == null || declinesFailed) {
            return;
        }
        try {
            if (declinesOut == null) {
                declinesOut = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                        new java.io.FileOutputStream(declinesPath, true), java.nio.charset.StandardCharsets.UTF_8));
            }
            declinesOut.write(line);
            declinesOut.write('\n');
            declinesOut.flush();
        } catch (java.io.IOException | RuntimeException e) {
            declinesFailed = true;
            System.err.println("rlvm-machine: the decline log " + declinesPath + " cannot be written: " + e);
        }
    }

    @Override
    public String name() {
        return "machine";
    }

    @Override
    public Session newSession(WorkerContext ctx) {
        if (ctx.store != store) {
            throw new IllegalArgumentException("worker context of another store");
        }
        return new MachineSession(this, ctx);
    }

    /**
     * ISA 14.5: {@code call} events by op, memo and pair-table hit rates, records and runs made, resets
     * and bytes at reset, elaborations by role ({@code machine.code.*}, from M7's {@code Code.Ext} and the
     * {@code CodeStore}), facts derived and {@code F_NONE} records, probe outcomes by shortcut; plus the outcomes by machine error code and the first non-ACCEPT messages, which is
     * what a gate run reads when {@code machine.declined} is not 0.
     */
    @Override
    public void dumpStats(StatsSink sink) {
        if (!config.stats) {
            return;
        }
        synchronized (this) {
            sink.put("machine.sessions", statSessions);
            sink.put("machine.units", statUnits);
            sink.put("machine.accepted", statAccepted);
            sink.put("machine.rejected", statRejected);
            sink.put("machine.declined", statDeclined);
            for (int c = 1; c <= MachineErrors.COUNT; c++) {
                if (statByCode[c] != 0) {
                    String name = c == MachineErrors.COUNT ? "E_UNKNOWN" : MachineErrors.name(c);
                    sink.put((MachineErrors.outcome(c) == rlvm.api.Outcome.REJECT ? "machine.rejectedBy." : "machine.declinedBy.") + name,
                            statByCode[c]);
                }
            }
            for (int i = 0; i < statMessagesN; i++) {
                sink.put("machine.firstFailures." + i, statMessages[i]);
            }
            sink.put("machine.switches", Debug.switchNames(switches));
            sink.put("machine.debug", Debug.modeNames(debug));
            sink.put("machine.strict", config.strict ? 1 : 0);
            sink.put("machine.hostDepth", hostDepth);
            sink.put("machine.ticks", statTicks);
            sink.put("machine.resets", statResets);
            sink.put("machine.sessionCapBytes", sessionCapBytes);
            sink.put("machine.capResets", statCapResets);
            sink.put("machine.unitTicks", unitTicks);
            // units declined because they reached the machine's own tick bound (machine.unit-ticks); part of machine.declined
            sink.put("machine.unitBoundDeclines", statUnitBoundDeclines);
            // declines BY DESIGN: the declaration entered eager reduction (Machine.erSeen); part of machine.declined
            sink.put("machine.eagerDeclines", statEagerDeclines);
            if ((switches & Op.SW_EAGER) != 0) {
                // [eager] units that entered eager reduction and were decided by the machine; runs repeated in a fresh
                // session; sessions forgotten after an eager-only event; deferred comparisons; eager-only events;
                // the declines by reason (part of machine.eagerDeclines; the driver's cold retry counts again)
                sink.put("machine.eager.kept", statEagerKept);
                sink.put("machine.eager.restarts", statEagerRestarts);
                sink.put("machine.eager.forgets", statEagerForgets);
                sink.put("machine.eager.sites", statEagerSites);
                sink.put("machine.eager.events", statEagerEvents);
                sink.put("machine.eager.notes", statEagerNotes);
                sink.put("machine.eager.declined.plainFold", statEagerWhy[Machine.ER_WHY_FOLD]);
                sink.put("machine.eager.declined.plainBoolTrue", statEagerWhy[Machine.ER_WHY_BOOL]);
                sink.put("machine.eager.declined.kFold", statEagerWhy[Machine.ER_WHY_K_FOLD]);
                sink.put("machine.eager.declined.kBoolTrue", statEagerWhy[Machine.ER_WHY_K_BOOL]);
                sink.put("machine.eager.declined.other", statEagerWhy[0] + statEagerWhy[Machine.ER_WHY_LIMIT]);
            }
            // session arrays allocated after the machine was created: by growth (doublings) and by resets (NOTES-M6 F25)
            sink.put("machine.alloc.grows", statAllocGrows);
            sink.put("machine.alloc.growBytes", statAllocGrowBytes);
            sink.put("machine.alloc.resets", statAllocResets);
            sink.put("machine.alloc.resetBytes", statAllocResetBytes);
            sink.put("machine.bytesAtReset", statBytesAtReset);
            sink.put("machine.records", statRecords);
            sink.put("machine.runWords", statRunWords);
            sink.put("machine.pairs", statPairs);
            sink.put("machine.framesWritten", statFrames);
            sink.put("machine.frameWords", statFrameWords);
            sink.put("machine.spills", statSpills);
            sink.put("machine.flushes", statFlushes);
            sink.put("machine.cuts", statCuts);
            sink.put("machine.polls", statPolls);
            sink.put("machine.maxWorklistWords", statMaxWp);
            long calls = 0;
            for (int op = 0; op < Op.OP_COUNT; op++) {
                if (statCalls[op] != 0) {
                    sink.put("machine.calls." + Op.name(op), statCalls[op]);
                    calls += statCalls[op];
                }
            }
            sink.put("machine.calls", calls);
            if (calls != 0) {
                sink.put("machine.framesPerCall", (double) statFrames / calls);
            }
            for (int op = 0; op < Op.OP_COUNT; op++) {
                if (statHits[op] != 0) {
                    sink.put("machine.hits." + Op.name(op), statHits[op]);
                    sink.put("machine.hitRate." + Op.name(op), (double) statHits[op] / (statHits[op] + statCalls[op]));
                }
            }
            for (int b = 1; b < Op.SHORTCUT_COUNT; b++) {
                long f = statProbes[3 * b + Layout.F];
                long t = statProbes[3 * b + Layout.T];
                long u = statProbes[3 * b + Layout.U];
                if ((f | t | u) != 0) {
                    sink.put("machine.probes.B" + b + ".T", t);
                    sink.put("machine.probes.B" + b + ".F", f);
                    sink.put("machine.probes.B" + b + ".U", u);
                }
            }
            // K1 (staging steps 3-4; all zero in v0): the engine-wide block store and the sessions' counters
            // of Code.Ext (M7)
            sink.put("machine.code.blocks", code.blockCount());
            sink.put("machine.code.elabAt", code.elabAt);
            sink.put("machine.code.entered", statCodeEntered);
            sink.put("machine.code.icHits", statIcHits);
            sink.put("machine.code.icMisses", statIcMisses);
            for (int role = 1; role < Op.ROLE_COUNT; role++) {
                if (statElabs[role] != 0) {
                    sink.put("machine.code.elaborated." + roleName(role), statElabs[role]);
                }
                if (statStayedGeneric[role] != 0) {
                    sink.put("machine.code.stayedGeneric." + roleName(role), statStayedGeneric[role]);
                }
            }
            for (java.util.Map.Entry<String, long[]> e : statPackage.entrySet()) {
                sink.put("machine." + e.getKey(), e.getValue()[0]);
            }
            sink.put("machine.factsDerived", statFactsDerived);
            sink.put("machine.factsNone", statFactsNone);
            sink.put("machine.factBytes", facts.bytes());
            sink.put("machine.codeBytes", code.bytes());
            if (debug != 0) {
                sink.put("machine.audits", statAudits);
                sink.put("machine.memoChecks", statMemoChecks);
                sink.put("machine.memoInconclusive", statMemoInconclusive);
                sink.put("machine.memoByConv", statMemoByConv);
            }
        }
    }

    /** Sums the counters of a package's session state, if it reports any ({@code Machine.Counters}). Called under the engine's lock. */
    synchronized void packageCounters(Object ext) {
        if (!(ext instanceof Machine.Counters)) {
            return;
        }
        Machine.Counters c = (Machine.Counters) ext;
        String[] names = c.counterNames();
        long[] values = c.counterValues();
        if (names == null || values == null) {
            return;
        }
        for (int i = 0; i < names.length && i < values.length; i++) {
            statPackage.computeIfAbsent(names[i], k -> new long[1])[0] += values[i];
        }
    }

    private static String roleName(int role) {
        switch (role) {
            case Op.ROLE_TELE: return "TELE";
            case Op.ROLE_BODY: return "BODY";
            case Op.ROLE_RULE: return "RULE";
            case Op.ROLE_FIELDS: return "FIELDS";
            case Op.ROLE_CONG: return "CONG";
            case Op.ROLE_RULES: return "RULES";
            default: return "ROLE" + role;
        }
    }

    @Override
    public synchronized void close() {
        // Engine-wide tables are plain arrays; sessions own everything else.
        if (declinesOut != null) {
            try {
                declinesOut.close();
            } catch (java.io.IOException e) {
                // a diagnostic file
            }
            declinesOut = null;
        }
    }

    synchronized void account(MachineSession s) {
        Machine m = s.m;
        statSessions++;
        statUnits += s.units;
        statAccepted += s.accepted;
        statRejected += s.rejected;
        statDeclined += s.declined;
        statTicks += m.ticks();
        statResets += m.resets;
        statCapResets += s.capResets;
        statUnitBoundDeclines += s.unitBoundDeclines;
        statEagerDeclines += s.eagerDeclines;
        statEagerKept += s.eagerKept;
        statEagerRestarts += s.eagerRestarts;
        statEagerForgets += s.eagerForgets;
        statEagerSites += s.eagerSites;
        statEagerEvents += s.eagerEvents;
        statEagerNotes += s.eagerNotes;
        for (int i = 0; i < statEagerWhy.length; i++) {
            statEagerWhy[i] += s.eagerWhy[i];
        }
        statAllocGrows += m.allocGrows;
        statAllocGrowBytes += m.allocGrowBytes;
        statAllocResets += m.allocResets;
        statAllocResetBytes += m.allocResetBytes;
        statBytesAtReset += m.bytesAtReset;
        statFrames += m.framesWritten;
        statFrameWords += m.frameWords;
        statSpills += m.spills;
        statFlushes += m.flushes;
        statCuts += m.cuts;
        statPolls += m.polls;
        statMaxWp = Math.max(statMaxWp, m.maxWp);
        statRecords += m.recordsMade + (m.vp - m.vbase);
        statRunWords += m.runWordsMade + (m.rsp - m.rbase);
        statPairs += m.pairsMade + m.pairUsed;
        statFactsDerived += m.factsDerived;
        statFactsNone += m.factsNone;
        statAudits += m.audits;
        statMemoChecks += m.memoChecks;
        statMemoInconclusive += m.memoInconclusive;
        statMemoByConv += m.memoByConv;
        for (int op = 0; op < Op.OP_COUNT; op++) {
            statCalls[op] += m.calls[op];
            statHits[op] += m.hits[op];
        }
        for (int i = 0; i < statProbes.length; i++) {
            statProbes[i] += m.probes[i];
        }
        for (int i = 0; i < statElabs.length; i++) {
            statElabs[i] += m.elabs[i] + m.x7.elaborated[i];
            statStayedGeneric[i] += m.x7.stayedGeneric[i];
        }
        statCodeEntered += m.x7.entered;
        statIcHits += m.x7.icHits;
        statIcMisses += m.x7.icMisses;
        for (int i = 0; i < statByCode.length; i++) {
            statByCode[i] += s.byCode[i];
        }
        packageCounters(m.x1);
        packageCounters(m.x2);
        packageCounters(m.x3);
        packageCounters(m.x4);
        packageCounters(m.x5);
        packageCounters(m.x7);
        for (int i = 0; i < s.keptN && statMessagesN < statMessages.length; i++) {
            statMessages[statMessagesN++] = s.kept[i];
        }
    }
}
