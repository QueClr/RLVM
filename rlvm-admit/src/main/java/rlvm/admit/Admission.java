package rlvm.admit;

import rlvm.api.Budget;
import rlvm.api.Outcome;
import rlvm.api.Reason;
import rlvm.api.Session;
import rlvm.api.WorkerContext;
import rlvm.store.DeclKind;
import rlvm.store.DeclTable;
import rlvm.store.NameTable;
import rlvm.store.Overlay;

/**
 * The per-unit admission flow a worker runs for axiom, definition, theorem and opaque units
 * (*agentic-note*: design-architecture-document, 4.4). Inductive and quot units are decided by the {@link EnvBuilder}; a worker
 * that draws one only reads its recorded outcome.
 *
 * <pre>
 * admit(unit):
 *   env.advanceTo(unit + 1)                       -- only with the 3-argument constructor (the -j 1 slice)
 *   skipped unit (unsafe / partial)               -> ACCEPT
 *   inductive / quot unit                         -> the outcome the environment pass recorded
 *   cid := the unit's constant
 *   r := structural.checkConstant(cid)            -- O(1); may be a reject or a decline (FORWARD_REFERENCE, ...)
 *   if r != null: return r.outcome()
 *   o := session.check(cid, budget)               -- type is a type; theorem: Prop; value : type
 *   axiom policy (only if !policy.allowAllAxioms): an accepted non-standard axiom -> DECLINE
 *   return o
 * </pre>
 * Precondition without an attached environment pass: {@code unit < Store.envUnits()}; otherwise the
 * unit is declined (INTERNAL_ERROR), never checked. The unit status column is not written here (the
 * driver owns it for worker-checked units).
 *
 * <p>One instance per worker thread.
 */
public final class Admission {

    private final WorkerContext ctx;
    private final Policy policy;
    private final EnvBuilder env;
    private final StructuralChecks structural;
    private final DeclTable decls;

    /**
     * {@code -Drlvm.admit.stats=true}: this worker times its structural checks (which include the
     * decoding of a theorem's proof block) and prints one line to standard error when it admits the
     * last unit of the file. Off by default (two clock reads per unit when on).
     */
    private final boolean stats = Boolean.getBoolean("rlvm.admit.stats");
    private long structuralNanos;
    private int structuralUnits;

    private Reason lastReason;
    private String lastMessage;
    /** Source of a lazily built message: the session whose check produced the last outcome. */
    private Session messageSession;

    public Admission(WorkerContext ctx, Policy policy) {
        this(ctx, policy, null);
    }

    /**
     * @param env an environment pass this worker drives lazily (single-worker runs): {@link #admit}
     *     first advances it past the unit; null if another thread runs the pass
     */
    public Admission(WorkerContext ctx, Policy policy, EnvBuilder env) {
        this.ctx = ctx;
        this.policy = policy;
        this.env = env;
        this.structural = new StructuralChecks(ctx.store, ctx.terms, ctx.lists, policy);
        this.decls = ctx.store.decls();
    }

    /**
     * Admits one unit with the given session.
     *
     * @return an {@code Outcome} code; on a non-ACCEPT outcome {@link #lastReason()} and
     *     {@link #lastMessage()} describe it
     */
    public int admit(int unit, Session session, Budget budget) {
        lastReason = null;
        lastMessage = null;
        messageSession = null;
        if (env != null) {
            int frontier = env.advanceTo(unit + 1);
            if (frontier <= unit) {
                return notReady(unit, frontier);
            }
        } else if (unit >= ctx.store.envUnits()) {
            return fail(Reason.INTERNAL_ERROR, "unit " + unit + " is not below the environment frontier "
                    + ctx.store.envUnits());
        }
        ctx.terms.refresh();
        int status = decls.status(unit);
        if (status == DeclKind.ST_SKIPPED || decls.unitSize(unit) == 0) {
            return Outcome.ACCEPT;
        }
        int kind = decls.unitKind(unit);
        switch (kind) {
            case DeclKind.AXIOM:
            case DeclKind.DEFN:
            case DeclKind.THM:
            case DeclKind.OPAQUE: {
                ReplayState rs = replayFor(unit);
                if (rs != null) {
                    return replayed(unit, kind, rs);
                }
                if (!stats) {
                    return checkConstant(decls.unitFirstCid(unit), kind, session, budget);
                }
                try {
                    return checkConstant(decls.unitFirstCid(unit), kind, session, budget);
                } finally {
                    if (unit == ctx.store.loadedUnits() - 1 && ctx.store.loadComplete()) {
                        System.err.println("rlvm-admit-stats: structural checks of this worker: " + structuralUnits
                                + " units in " + structuralNanos / 1000 + " us (proof blocks decoded for them included)");
                    }
                }
            }
            case DeclKind.INDUCT:
            case DeclKind.QUOT_TYPE:
            case DeclKind.QUOT_MK:
            case DeclKind.QUOT_LIFT:
            case DeclKind.QUOT_IND:
                return recorded(unit, status);
            default:
                return fail(Reason.INTERNAL_ERROR, "unit " + unit + " has the unknown kind " + kind);
        }
    }

    public Reason lastReason() {
        return lastReason;
    }

    /**
     * The replay state that decided {@code unit}, or null if the unit is checked here. The read of
     * {@code Store.envUnits()} that precedes every call (the caller's precondition, or the attached
     * pass's own advance) orders this thread after the pass's writes for every unit below the frontier.
     */
    private ReplayState replayFor(int unit) {
        // Looked up at every unit, never kept: a new pass over the store replaces what an earlier one recorded.
        ReplayState rs = env != null ? env.replayState() : ReplayState.anyPublished() ? ReplayState.find(ctx.store) : null;
        return rs != null && unit >= rs.first ? rs : null;
    }

    /**
     * A plain unit the environment pass admitted itself, in dependency order
     * ({@code Policy.ForwardRefs.REORDER}): only the recorded outcome is reported; no engine runs.
     */
    private int replayed(int unit, int kind, ReplayState rs) {
        if (!rs.done(unit)) {
            return fail(Reason.INTERNAL_ERROR, "unit " + unit
                    + " is admitted in dependency order by the environment pass, which has not decided it");
        }
        Reason r = rs.reasonOf(unit);
        if (r != null) {
            return fail(r, rs.messageOf(unit));
        }
        int cid = decls.unitFirstCid(unit);
        if (kind == DeclKind.AXIOM && !policy.allowAllAxioms && !isStandardAxiom(decls.name(cid))) {
            return fail(Reason.UNSUPPORTED_INPUT, "non-standard axiom '"
                    + ctx.store.names().toString(decls.name(cid)) + "' (policy: only propext, Quot.sound, "
                    + "Classical.choice and sorryAx are admitted)");
        }
        return Outcome.ACCEPT;
    }

    public String lastMessage() {
        if (lastMessage == null && messageSession != null) {
            lastMessage = messageSession.lastMessage();
            messageSession = null;
        }
        return lastMessage;
    }

    private int checkConstant(int cid, int kind, Session session, Budget budget) {
        Reason r;
        long t0 = stats ? System.nanoTime() : 0L;
        try {
            r = structural.checkConstant(cid);
        } catch (Overlay.OverlayFullException e) {
            return fail(Reason.BUDGET_MEMORY, "term overlay full while decoding the proof block of '"
                    + ctx.store.names().toString(decls.name(cid)) + "'");
        }
        if (stats) {
            structuralNanos += System.nanoTime() - t0;
            structuralUnits++;
        }
        if (r != null) {
            return fail(r, "'" + ctx.store.names().toString(decls.name(cid)) + "': " + structural.lastMessage());
        }
        int outcome = session.check(cid, budget);
        if (outcome != Outcome.ACCEPT) {
            lastReason = session.lastReason();
            if (lastReason == null || lastReason.outcome() != outcome) {
                // An engine that reports an outcome without a matching reason is not trusted with a verdict.
                return fail(Reason.INTERNAL_ERROR, "engine outcome " + Outcome.name(outcome) + " with reason " + lastReason);
            }
            messageSession = session;
            return outcome;
        }
        if (kind == DeclKind.AXIOM && !policy.allowAllAxioms && !isStandardAxiom(decls.name(cid))) {
            return fail(Reason.UNSUPPORTED_INPUT, "non-standard axiom '"
                    + ctx.store.names().toString(decls.name(cid)) + "' (policy: only propext, Quot.sound, "
                    + "Classical.choice and sorryAx are admitted)");
        }
        return Outcome.ACCEPT;
    }

    /** An inductive or quot unit: decided by the environment pass, which recorded its status. */
    private int recorded(int unit, int status) {
        switch (status) {
            case DeclKind.ST_ACCEPTED:
                return Outcome.ACCEPT;
            case DeclKind.ST_REJECTED:
            case DeclKind.ST_DECLINED:
                if (env != null && env.failedUnit() == unit) {
                    return fail(env.failureReason(), env.failureMessage());
                }
                return fail(status == DeclKind.ST_REJECTED ? Reason.INDUCTIVE_INVALID : Reason.UNSUPPORTED_INPUT,
                        "unit " + unit + " failed in the environment pass (reason reported to its listener)");
            default:
                return fail(Reason.INTERNAL_ERROR, "unit " + unit
                        + " is decided by the environment pass, which has not recorded an outcome (status " + status + ")");
        }
    }

    /** The attached environment pass did not get past {@code unit}. */
    private int notReady(int unit, int frontier) {
        if (env.failedUnit() == unit) {
            return fail(env.failureReason(), env.failureMessage());
        }
        if (env.failedUnit() < 0 && env.waitingForLoad()) {
            return fail(Reason.FORWARD_REFERENCE, "unit " + frontier + " is undecided until the file is completely loaded");
        }
        if (env.failedUnit() >= 0) {
            // A later unit can never be checked: its result could not change the first failure in file order.
            return fail(Reason.CANCELLED, "the environment pass stopped at unit " + env.failedUnit()
                    + ", below unit " + unit);
        }
        return fail(Reason.INTERNAL_ERROR, "unit " + unit + " is not loaded or the environment pass was cancelled"
                + " (frontier " + frontier + ")");
    }

    private boolean isStandardAxiom(int name) {
        NameTable names = ctx.store.names();
        int prefix = names.prefix(name);
        if (prefix == NameTable.ANONYMOUS) {
            return names.lastComponentIs(name, "propext") || names.lastComponentIs(name, "sorryAx");
        }
        if (names.prefix(prefix) != NameTable.ANONYMOUS) {
            return false;
        }
        return (names.lastComponentIs(name, "sound") && names.lastComponentIs(prefix, "Quot"))
                || (names.lastComponentIs(name, "choice") && names.lastComponentIs(prefix, "Classical"));
    }

    private int fail(Reason reason, String message) {
        lastReason = reason;
        lastMessage = message;
        return reason.outcome();
    }
}
