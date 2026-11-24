package rlvm.driver;

import rlvm.store.Store;

/**
 * The order in which ready units are offered to workers. Any order is sound: a unit is checked only
 * against constants of smaller rank, whatever was checked before it (ARCHITECTURE.md 6.2). The order
 * only decides how warm the session caches are.
 *
 * <p>Only {@link Kind#FILE} is implemented. Namespace grouping is deferred until the vertical slice
 * and the 230 small tests pass (RECONCILIATION.md section 3); {@link #of} throws
 * {@code UnsupportedOperationException} for it and {@link Scheduler} accepts the file order only.
 */
public interface OrderPolicy {

    enum Kind {
        /** File order (the exporter's dependency post-order). */
        FILE,
        /**
         * Inside a sliding window of ready units: group by the first two components of the namespace
         * (private prefixes stripped), groups by first appearance, file order inside a group.
         */
        NAMESPACE_WINDOW
    }

    /**
     * Permutes {@code units[from .. from+n)}, a window of consecutive ready units, into the order in
     * which they should be offered.
     */
    void arrange(int[] units, int from, int n);

    /** The file order: leaves every window as it is. */
    OrderPolicy FILE_ORDER = new OrderPolicy() {
        @Override
        public void arrange(int[] units, int from, int n) {
            // file order is the order of the window
        }

        @Override
        public String toString() {
            return "FILE";
        }
    };

    static OrderPolicy of(Kind kind, Store store) {
        if (kind == Kind.FILE) {
            return FILE_ORDER;
        }
        throw new UnsupportedOperationException(
                "rlvm-driver: order " + kind + " is deferred until after the vertical slice; use OrderPolicy.Kind.FILE");
    }
}
