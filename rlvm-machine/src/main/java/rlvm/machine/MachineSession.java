package rlvm.machine;

import rlvm.api.Budget;
import rlvm.api.KernelOps;
import rlvm.api.MachineErrors;
import rlvm.api.Outcome;
import rlvm.api.Reason;
import rlvm.api.Session;
import rlvm.api.WorkerContext;
import rlvm.store.DeclKind;
import rlvm.store.DeclTable;

/**
 * One worker's session of the machine (ISA 14.1): owns one {@link Machine} and the worker context it was
 * given; confined to its thread. Owner: M6.
 *
 * <p>{@code check} never throws for a property of the input: a kernel error is REJECT, everything else
 * that is not ACCEPT is DECLINE (fail closed); a {@code RuntimeException} out of a judgment or of
 * {@code rlvm-level} / {@code rlvm-nat} is DECLINE(INTERNAL_ERROR) with a session reset; an
 * {@code Error} is never caught (ISA 12 R11).
 *
 * <h2>Session regimes</h2>
 * <ul>
 *   <li><b>v0</b> (no staging switch; ISA section 11 "session = one declaration"): the machine is reset
 *       before every declaration and at once after every outcome other than ACCEPT.
 *   <li><b>strict</b> ({@code EngineConfig.strict}; ISA 14.2): no switch is on whatever the system
 *       property says; a fresh machine before <em>and after</em> every declaration, so that nothing a
 *       declaration derived is ever seen by another one.
 *   <li><b>warm</b> ({@code Op.SW_WARM}, stage 1; ISA 5.3, 5.4): the machine survives an ACCEPT. It is
 *       reset after every other outcome, when the next declaration is not later in file order
 *       (I-SESSION; the driver does the same), by the driver when {@link #footprintBytes()} passes
 *       its cap, and by the session itself before a declaration once the machine's arenas hold more than
 *       the machine's own cap ({@code -X machine.session-kib}, {@code MachineEngine.sessionCapBytes}): the
 *       best cap is a property of the machine's tables (they must stay in the cache), not of the driver's
 *       memory account, so the machine is at its best cost under ANY cap of the driver. A reset of the
 *       machine alone is always legal: no machine value survives it, and the worker context's overlays
 *       (which the driver resets with {@code Session.reset()}) hold nothing that refers to the machine.
 *   <li><b>eager reduction</b> (every regime; NOTES-M6 F26): a declaration that enters eager reduction (an
 *       argument {@code eagerReduce _ _}) is DECLINED with {@code E_LIMIT} / {@code UNSUPPORTED_INPUT},
 *       whatever the DECL driver answered, and the reference engine decides it: conversion under ER
 *       depends on the mode and on the official kernel's per-declaration caches, which tables keyed by
 *       canonical values cannot reproduce ({@code Machine.erSeen}). Counted in {@code machine.eagerDeclines}.
 *       The check generation of ISA 5.3 is advanced by the DECL driver ({@code Decl.regime}, M5);
 *       every reset starts again at generation 1.
 * </ul>
 */
public final class MachineSession implements Session {

    /** The DECL driver the session runs (M5's {@code Decl.check}; tests install toy drivers). */
    interface Driver {
        /** @return {@code MachineErrors.E_NONE} = ACCEPT, else the code of the failure */
        int check(Machine m, int cid);
    }

    /** How many decline / reject messages a session keeps for {@code dumpStats} (statistics runs only). */
    static final int KEPT_MESSAGES = 4;

    final MachineEngine engine;
    final WorkerContext ctx;
    final Machine m;
    Driver driver = Decl::check;
    private boolean closed;

    private Reason lastReason;
    private String lastMessage;
    private int lastCode;
    private int lastCid;
    private RuntimeException lastCause;
    private String lastDetail;

    /** Rank of the last declaration checked in the live machine session; -1 = none. */
    private int lastRank = -1;

    /** Units accepted in the live machine session (0 = the next check is cold). */
    private long warmUnits;
    /** Resets the session made itself because the machine's arenas passed the machine's own cap. */
    long capResets;
    /**
     * Units declined because the declaration entered eager reduction ({@code Machine.erSeen}): declines BY
     * DESIGN, counted apart so that a gate can tell them from a machine that gave up (the driver's cold retry
     * of the same unit is counted again).
     */
    long eagerDeclines;
    /** The constant that was last declined for eager reduction: the driver's cold retry of it is answered at once. */
    private int eagerCid = -1;
    /** Units declined because they reached the machine's own tick bound ({@code MachineEngine.unitTicks}); the retry counts again. */
    long unitBoundDeclines;
    /** The constant that last reached the machine's own tick bound: its cold retry is answered at once. */
    private int boundCid = -1;
    /** The step budget of the run in progress is the machine's own bound, not the driver's. */
    private boolean ownBound;

    long units;
    long accepted;
    long rejected;
    long declined;
    /** Non-ACCEPT outcomes by machine error code ({@code MachineErrors.*}). */
    final long[] byCode = new long[MachineErrors.COUNT + 1];
    /** The first few non-ACCEPT messages (only kept when statistics are on). */
    final String[] kept = new String[KEPT_MESSAGES];
    int keptN;

    MachineSession(MachineEngine engine, WorkerContext ctx) {
        this.engine = engine;
        this.ctx = ctx;
        this.m = new Machine();
        m.attach(ctx, engine);
        m.sw = engine.switches;
        m.debug = engine.debug;
        m.hostDepth = engine.hostDepth;
        m.stats = engine.config.stats;
        m.strict = engine.config.strict;
    }

    @Override
    public int check(int cid, Budget budget) {
        units++;
        lastReason = null;
        lastMessage = null;
        lastCause = null;
        lastDetail = null;
        lastCode = 0;
        lastCid = cid;
        DeclTable decls = ctx.store.decls();
        int kind = decls.kind(cid);
        if (kind != DeclKind.AXIOM && kind != DeclKind.DEFN && kind != DeclKind.THM && kind != DeclKind.OPAQUE) {
            lastReason = Reason.UNSUPPORTED_INPUT;
            lastMessage = "constant " + cid + " of kind " + kind + " is not checked by an engine";
            declined++;
            byCode[MachineErrors.E_LIMIT]++;
            return Outcome.DECLINE;
        }
        int code;
        boolean eager = false;
        boolean bound = false;
        try {
            ctx.terms.refresh(); // the overlay may have been reallocated by blockRoot (ISA 12 R4)
            int rank = decls.rank(cid);
            boolean warm = !m.strict && m.on(Op.SW_WARM);
            if (!warm || rank <= lastRank) {
                // v0 and strict mode: session = one declaration (ISA section 11, 14.2); warm: a session only
                // moves forward in file order (I-SESSION, ISA 2.5)
                if (!m.fresh || !m.pristine()) {
                    m.reset();
                    warmUnits = 0;
                }
            } else if (engine.sessionCapBytes != 0 && m.engineBytes() > engine.sessionCapBytes) {
                // the machine's own cap on a warm session (the class comment)
                m.reset();
                warmUnits = 0;
                capResets++;
            }
            // (the generation rule of ISA 5.3 is applied by the DECL driver, Decl.regime, which knows whether the
            // declaration's roots pass the summary test; a reset starts a session at generation 1)
            lastRank = rank;
            if (cid == eagerCid) {
                // the driver's cold retry of a unit that was just declined for eager reduction: the same answer, at once
                eager = true;
                code = MachineErrors.E_LIMIT;
            } else if (cid == boundCid) {
                // the driver's cold retry of a unit that reached the machine's own tick bound: the same answer, at once
                bound = true;
                code = MachineErrors.E_STEPS;
            } else {
                code = run(cid, rank, decls.unit(cid), budget);
                if (code == MachineErrors.E_STEPS && ownBound && m.fuel < 0) { // the budget that ran out was the machine's own
                    bound = true;
                    boundCid = cid;
                }
                if (m.erSeen) {
                    // The declaration entered eager reduction (Machine.erSeen): whatever this run answered is
                    // not a verdict of the machine. DECLINE; the reference engine decides the unit.
                    eager = true;
                    eagerCid = cid;
                    lastCause = null;
                    code = MachineErrors.E_LIMIT;
                }
            }
        } catch (RuntimeException e) {
            // (ctx.terms.refresh, the declaration table: nothing was checked)
            lastCause = e;
            code = MachineErrors.E_INTERNAL;
        }
        if (code == MachineErrors.E_NONE) {
            accepted++;
            warmUnits++;
            if (m.strict) {
                forget(); // ISA 14.2: reset() before and after
            }
            return Outcome.ACCEPT;
        }
        long before = warmUnits;
        lastCode = code;
        lastReason = MachineErrors.reason(code);
        int outcome = MachineErrors.outcome(code);
        if (eager) {
            eagerDeclines++;
            lastDetail = Machine.ER_DETAIL;
        } else if (bound) {
            unitBoundDeclines++;
            lastDetail = "the machine's own bound of " + engine.unitTicks + " ticks per unit (-X machine.unit-ticks): a warm unit has no"
                    + " step limit of the driver's, and the machine has no cycle test; the reference engine decides this unit";
        } else if (m.auditMessage != null) {
            lastDetail = m.auditMessage;
        } else if (m.limitDetail != null && code == MachineErrors.E_LIMIT) {
            lastDetail = m.limitDetail; // which rule declined (E1-F6: the K rule after an inexact let application)
        } else if (m.limitDetail != null && (code == MachineErrors.E_MEM || code == MachineErrors.E_STACK)) {
            lastDetail = m.limitDetail + "; in use: " + (m.vp - m.vbase) + " records, " + (m.rsp - m.rbase)
                    + " run words, " + m.pairUsed + " pairs, W max " + m.maxWp + "; " + footprintParts();
        } else if (m.errA != 0 || m.errB != 0) {
            lastDetail = site(); // the two values of a mismatch, rendered before the session forgets them
        }
        byCode[code > 0 && code < MachineErrors.COUNT ? code : MachineErrors.COUNT]++;
        // Nothing derived for a failed unit survives (ISA 14.1): the machine is reset at once; the driver
        // resets the worker context together with Session.reset().
        forget();
        if (outcome == Outcome.REJECT) {
            rejected++;
        } else {
            outcome = Outcome.DECLINE;
            declined++;
        }
        if (m.stats && keptN < KEPT_MESSAGES) {
            kept[keptN++] = lastMessage();
        }
        if (engine.declinesPath != null) {
            logOutcome(outcome, code, cid, before);
        }
        return outcome;
    }

    /** One line of the decline log: outcome, error code, unit, rank, units accepted in the session before it, name, message. */
    private void logOutcome(int outcome, int code, int cid, long before) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(outcome == Outcome.REJECT ? "REJECT" : "DECLINE").append('\t').append(MachineErrors.name(code)).append('\t');
        try {
            DeclTable decls = ctx.store.decls();
            sb.append(decls.unit(cid)).append('\t').append(decls.rank(cid)).append('\t').append(before).append('\t');
            ctx.store.names().appendTo(sb, decls.name(cid));
        } catch (RuntimeException e) {
            sb.append("constant ").append(cid);
        }
        sb.append('\t');
        String msg = lastMessage();
        if (msg != null) {
            for (int i = 0, n = Math.min(msg.length(), 4000); i < n; i++) {
                char c = msg.charAt(i);
                sb.append(c == '\n' || c == '\r' || c == '\t' ? ' ' : c);
            }
        }
        engine.logOutcome(sb.toString());
    }

    /** One run of the DECL driver on the machine as it is: the machine error code (0 = ACCEPT). Never throws for a property of the input. */
    private int run(int cid, int rank, int unit, Budget budget) {
        m.erDeclines = true; // a declaration that enters eager reduction is not checked by the machine (Machine.erSeen)
        // the machine's own tick bound (MachineEngine.unitTicks) where the driver gives more, or no limit at all
        long steps = budget.maxSteps;
        long own = engine.unitTicks;
        ownBound = own > 0 && (steps < 0 || steps > own);
        if (ownBound) {
            steps = own;
        }
        try {
            m.beginDecl(rank, unit, steps, budget.maxBytes);
            return driver.check(m, cid);
        } catch (Machine.HeapLimit e) {
            return MachineErrors.E_MEM; // a table could not grow in the Java heap (Machine.heapAllows)
        } catch (RuntimeException e) {
            // A declared limit of rlvm-level / rlvm-nat, an overlay that is full, or a judgment that is not
            // implemented yet: no verdict.
            lastCause = e;
            return MachineErrors.E_INTERNAL;
        } finally {
            m.erDeclines = false;
        }
    }

    /** The machine forgets everything; never throws for a machine state a failed judgment left behind. */
    private void forget() {
        lastRank = -1;
        warmUnits = 0;
        m.reset();
    }

    /** The footprint by part, for the message of a memory decline (never throws). */
    private String footprintParts() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("footprint ").append(m.footprintBytes()).append(" bytes = machine ").append(m.engineBytes());
            if (m.ownNats) {
                sb.append(" (of which numbers ").append(m.nats.bytesUsed()).append(')');
            }
            sb.append(" + context: overlay terms ").append(ctx.overlay.usedBytes()).append(", levels ")
                    .append(ctx.levels.footprintBytes()).append(", numbers ").append(ctx.nats.footprintBytes());
            if (ctx.lits != null && ctx.lits != ctx.store.lits()) {
                sb.append(", literals ").append(ctx.lits.bytes());
            }
            return sb.toString();
        } catch (RuntimeException e) {
            return "footprint not available (" + e + ")";
        }
    }

    private String site() {
        try {
            StringBuilder sb = new StringBuilder();
            if (m.errA != 0) {
                sb.append(clip(Show.value(m, m.errA)));
            }
            if (m.errB != 0) {
                sb.append("  =?=  ").append(clip(Show.value(m, m.errB)));
            }
            return sb.toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String clip(String s) {
        return s.length() > 1500 ? s.substring(0, 1500) + "..." : s;
    }

    @Override
    public Reason lastReason() {
        return lastReason;
    }

    @Override
    public String lastMessage() {
        if (lastMessage == null && lastReason != null) {
            StringBuilder sb = new StringBuilder();
            sb.append(lastReason.name()).append(" (").append(MachineErrors.name(lastCode)).append(") at '");
            try {
                ctx.store.names().appendTo(sb, ctx.store.decls().name(lastCid));
            } catch (RuntimeException e) {
                sb.append("constant ").append(lastCid);
            }
            sb.append('\'');
            if (lastDetail != null) {
                sb.append(": ").append(lastDetail);
            }
            if (lastCause != null) {
                sb.append(": ").append(lastCause);
                StackTraceElement[] st = lastCause.getStackTrace();
                for (int i = 0; i < st.length && i < 6; i++) {
                    sb.append(" < ").append(st[i]);
                }
            }
            lastMessage = sb.toString();
        }
        return lastMessage;
    }

    /** Machine error code of the last non-ACCEPT outcome ({@code MachineErrors.*}); 0 after an ACCEPT. For tests and reports. */
    int lastCode() {
        return lastCode;
    }

    /** Null in release configurations (ISA 14.6): admission uses the reference kernel. Tests use {@link MachineOps}. */
    @Override
    public KernelOps ops() {
        return null;
    }

    @Override
    public void reset() {
        forget();
    }

    /** Bytes in use (ISA 14.5): the machine's arenas plus the context's overlays, each counted once. */
    @Override
    public long footprintBytes() {
        return m.footprintBytes();
    }

    @Override
    public long steps() {
        return m.ticks();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        engine.account(this);
    }
}
