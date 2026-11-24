package rlvm.admit;

import rlvm.api.KernelOps;
import rlvm.api.ResourceException;
import rlvm.inductive.BlockInput;
import rlvm.inductive.GeneratedBlock;
import rlvm.inductive.InductiveAdmission;
import rlvm.inductive.QuotAdmission;
import rlvm.inductive.RecordCompare;
import rlvm.store.Store;

/**
 * The three entry points of {@code rlvm-inductive} the environment pass calls. A seam: the classes
 * behind it are final, so the unit tests of the environment pass script block admission through
 * this interface. Each method throws {@code KernelException} for a rejection and
 * {@code ResourceException} for "cannot decide".
 */
interface BlockOps {

    /** {@code InductiveAdmission.generate}: derives the block (checklist items 27 - 48). */
    void generate(BlockInput in, GeneratedBlock out);

    /** {@code RecordCompare.compare}: exported records against derived ones (items 49 - 51); bit j = recursor j is exported. */
    long compare(BlockInput in, GeneratedBlock generated);

    /** {@code QuotAdmission.admit}: the quot record {@code cid} against the canonical constant (items 18 - 24). */
    void admitQuot(int cid);

    /**
     * As {@link #admitQuot(int)} in a scope of rank {@code scopeRank} (admission in dependency order:
     * {@code Eq}, {@code Quot} and {@code Quot.mk} may come later in the file than the record).
     */
    default void admitQuot(int cid, int scopeRank) {
        admitQuot(cid);
    }

    /**
     * The cause, in words, of the {@code ResourceException} the last call of one of the methods above
     * ended with; null if that call returned, rejected, or was stopped by the kernel operations (a
     * budget, the stack) rather than by {@code rlvm-inductive}.
     */
    default String declineDetail() {
        return null;
    }

    /** Forgets the cause of an earlier decline (called before a unit is looked at). */
    default void clearDeclineDetail() {}

    /**
     * True if the last call of {@link #generate} met a universe level that the official kernel respells
     * when it refers to the block's own constants ({@code InductiveAdmission.lastRebuildChanged}); only
     * for such blocks do the checks differ from a kernel that keeps the declared spelling. Statistics.
     */
    default boolean respelled() {
        return false;
    }

    /** The real thing; the three objects are created on first use, so files without blocks never touch them. */
    final class Inductive implements BlockOps {
        private final Store store;
        private final KernelOps ops;
        private InductiveAdmission admission;
        private RecordCompare compare;
        private QuotAdmission quot;

        Inductive(Store store, KernelOps ops) {
            this.store = store;
            this.ops = ops;
        }

        /** Cause of the last decline of {@code rlvm-inductive}; cleared by every call. */
        private String detail;

        @Override
        public void generate(BlockInput in, GeneratedBlock out) {
            detail = null;
            if (admission == null) {
                admission = new InductiveAdmission(store, ops);
            }
            try {
                admission.generate(in, out);
            } catch (ResourceException e) {
                detail = admission.lastDeclineDetail();
                throw e;
            }
        }

        @Override
        public boolean respelled() {
            return admission != null && admission.lastRebuildChanged();
        }

        @Override
        public long compare(BlockInput in, GeneratedBlock generated) {
            detail = null;
            if (compare == null) {
                compare = new RecordCompare(store, ops);
            }
            try {
                return compare.compare(in, generated);
            } catch (ResourceException e) {
                detail = compare.lastDeclineDetail();
                throw e;
            }
        }

        @Override
        public void admitQuot(int cid) {
            detail = null;
            if (quot == null) {
                quot = new QuotAdmission(store, ops);
            }
            try {
                quot.admit(cid);
            } catch (ResourceException e) {
                detail = quot.lastDeclineDetail();
                throw e;
            }
        }

        @Override
        public void admitQuot(int cid, int scopeRank) {
            detail = null;
            if (quot == null) {
                quot = new QuotAdmission(store, ops);
            }
            try {
                quot.admit(cid, scopeRank);
            } catch (ResourceException e) {
                detail = quot.lastDeclineDetail();
                throw e;
            }
        }

        @Override
        public String declineDetail() {
            return detail;
        }

        @Override
        public void clearDeclineDetail() {
            detail = null;
        }
    }
}
