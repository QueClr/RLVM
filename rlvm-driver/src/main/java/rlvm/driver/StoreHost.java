package rlvm.driver;

import rlvm.admit.Admission;
import rlvm.admit.EnvBuilder;
import rlvm.admit.Policy;
import rlvm.api.Budget;
import rlvm.api.KernelOps;
import rlvm.api.Reason;
import rlvm.api.RunSignals;
import rlvm.api.Session;
import rlvm.api.WorkerContext;
import rlvm.store.DeclKind;
import rlvm.store.DeclTable;
import rlvm.store.Store;

/**
 * The production {@link RunHost}: units are the declaration records of a {@link Store}, lanes are
 * {@code WorkerContext}s from the {@link Wiring} with an {@code rlvm.admit.Admission} each, and the
 * environment pass is {@code rlvm.admit.EnvBuilder}.
 */
public final class StoreHost implements RunHost {

    private final Store store;
    private final DeclTable decls;
    private final Policy policy;
    private final Wiring wiring;

    public StoreHost(Store store, Policy policy, Wiring wiring) {
        this.store = store;
        this.decls = store.decls();
        this.policy = policy;
        this.wiring = wiring;
    }

    @Override
    public Store store() {
        return store;
    }

    @Override
    public int loadedUnits() {
        return store.loadedUnits();
    }

    @Override
    public boolean loadComplete() {
        return store.loadComplete();
    }

    @Override
    public int envUnits() {
        return store.envUnits();
    }

    @Override
    public int unitClass(int unit) {
        // A dropped unsafe or partial record is a unit without constants (or one marked SKIPPED).
        if (decls.unitSize(unit) == 0 || decls.status(unit) == DeclKind.ST_SKIPPED) {
            return SKIP;
        }
        switch (decls.unitKind(unit)) {
            case DeclKind.AXIOM:
            case DeclKind.DEFN:
            case DeclKind.THM:
            case DeclKind.OPAQUE:
                return CHECK;
            case DeclKind.INDUCT:
            case DeclKind.QUOT_TYPE:
            case DeclKind.QUOT_MK:
            case DeclKind.QUOT_LIFT:
            case DeclKind.QUOT_IND:
                return ENV;
            default:
                return UNKNOWN;
        }
    }

    @Override
    public int unitRank(int unit) {
        return decls.rank(decls.unitFirstCid(unit));
    }

    @Override
    public String unitName(int unit) {
        if (decls.unitSize(unit) == 0) {
            return null;
        }
        int cid = decls.unitFirstCid(unit);
        return cid <= 0 ? null : store.names().toString(decls.name(cid));
    }

    @Override
    public long sharedBytes() {
        return store.sharedBytes();
    }

    /**
     * Mirrors the board's status into the declaration table's status column, unless the unit already
     * has a final status there (the environment pass writes the final status of the units it decides).
     */
    @Override
    public void unitDecided(int unit, int boardStatus) {
        int target;
        switch (boardStatus) {
            case VerdictBoard.ACCEPTED:
                target = DeclKind.ST_ACCEPTED;
                break;
            case VerdictBoard.REJECTED:
                target = DeclKind.ST_REJECTED;
                break;
            case VerdictBoard.DECLINED:
                target = DeclKind.ST_DECLINED;
                break;
            default:
                return;
        }
        for (int i = 0; i < 4; i++) {
            int cur = decls.status(unit);
            if (cur >= DeclKind.ST_ACCEPTED || decls.casStatus(unit, cur, target)) {
                return;
            }
        }
    }

    /**
     * The environment pass recorded a failure for {@code unit} in the status column while the file
     * was still loading; a new {@code EnvBuilder} starts at {@code Store.envUnits()}, which is this
     * unit, and decides it again once the column says "loaded" again. Failures of later units that
     * the same attempt wrote down are erased too, and reported as {@link #RETRY_TAINTED}.
     */
    @Override
    public int retryEnvUnit(int unit) {
        // The driver asks after the end of the load, complete or not: no more of the file will come.
        if (unit != store.envUnits()) {
            return RETRY_NO;
        }
        // The pass writes failures into the status column only (rlvm-admit: a failed unit commits
        // nothing). With admission in dependency order it may have decided units beyond the frontier
        // while it worked on this one; their failures were found on the incomplete file as well.
        int n = store.loadedUnits();
        boolean collateral = false;
        for (int u = unit; u < n; u++) {
            int cur = decls.status(u);
            if (cur == DeclKind.ST_DECLINED || cur == DeclKind.ST_REJECTED) {
                if (!decls.casStatus(u, cur, DeclKind.ST_LOADED)) {
                    return RETRY_NO;
                }
                collateral |= u != unit;
            }
        }
        int own = decls.status(unit);
        if (own != DeclKind.ST_LOADED && own != DeclKind.ST_ENV_READY) {
            return RETRY_NO;
        }
        return collateral ? RETRY_TAINTED : RETRY_CLEAN;
    }

    @Override
    public Lane newLane(int workerId, RunSignals signals, long overlayMaxBytes) {
        WorkerContext ctx = wiring.newWorkerContext(workerId, store, signals, overlayMaxBytes);
        return new StoreLane(ctx, new Admission(ctx, policy));
    }

    @Override
    public EnvPass newEnvPass(Lane lane, Session referenceSession, EnvBuilder.Listener listener) {
        KernelOps ops = referenceSession.ops();
        if (ops == null) {
            throw new IllegalStateException("the reference engine offers no KernelOps for the environment pass");
        }
        // Admission in dependency order (rlvm-admit, Policy.ForwardRefs.REORDER) reads units beyond the
        // frontier once the file is complete: the pass must know how far the lane's level and list
        // view reaches (EnvBuilder.setCoverage, rlvm-admit 11:20Z). It enters dependency order only
        // when the view covers every loaded unit of the complete file: in a batch run always, in a
        // pipelined run after EnvLane has reset the lane at the end of the load.
        final EnvBuilder builder = new EnvBuilder(store, ops, policy, listener);
        final Lane view = lane;
        builder.setCoverage(new java.util.function.IntSupplier() {
            @Override
            public int getAsInt() {
                return view.coveredUnits();
            }
        });
        return new EnvPass() {
            @Override
            public int advanceTo(int unit) {
                return builder.advanceTo(unit);
            }

            @Override
            public void cancel() {
                builder.cancel();
            }
        };
    }

    private static final class StoreLane implements Lane {
        private final WorkerContext ctx;
        private final Admission admission;

        StoreLane(WorkerContext ctx, Admission admission) {
            this.ctx = ctx;
            this.admission = admission;
        }

        @Override
        public WorkerContext context() {
            return ctx;
        }

        @Override
        public void beginUnit() {
            ctx.terms.refresh();
        }

        @Override
        public int admit(int unit, Session session, Budget budget) {
            return admission.admit(unit, session, budget);
        }

        @Override
        public Reason lastReason() {
            return admission.lastReason();
        }

        @Override
        public String lastMessage() {
            return admission.lastMessage();
        }

        @Override
        public void reset() {
            ctx.reset();
        }

        @Override
        public int coveredUnits() {
            return ctx.coveredUnits();
        }

        @Override
        public long slackBytes() {
            // What the overlay holds (node array and hash index) minus the nodes in use, which the
            // session's footprint counts.
            long slack = ctx.overlay.allocatedBytes() - ctx.overlay.usedBytes();
            return slack > 0 ? slack : 0;
        }

        @Override
        public void close() {
            // a WorkerContext holds only arrays; nothing to release
        }
    }
}
