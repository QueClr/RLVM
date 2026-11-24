package rlvm.api;

/**
 * What a running session must poll (every few thousand steps, never per step): cancellation and
 * memory pressure. Implemented by the driver; reads are plain volatile loads.
 */
public interface RunSignals {

    /**
     * Smallest unit index that already has a non-ACCEPT outcome, or {@code Integer.MAX_VALUE}. A
     * session checking a unit above it should stop with a {@link ResourceException}: its result can
     * no longer matter.
     */
    int firstFailure();

    /**
     * True when the run as a whole is over its memory limit: the session must give up the current
     * unit with {@code ResourceException(BUDGET_MEMORY)}.
     */
    boolean memoryExhausted();

    /** A signal source that never cancels (tests, tools). */
    RunSignals NONE = new RunSignals() {
        @Override
        public int firstFailure() {
            return Integer.MAX_VALUE;
        }

        @Override
        public boolean memoryExhausted() {
            return false;
        }
    };
}
