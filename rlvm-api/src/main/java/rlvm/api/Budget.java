package rlvm.api;

/**
 * Deterministic resource limits for checking one unit. Limits are counted in engine steps and bytes,
 * never in time, so that a verdict cannot depend on machine load or on the number of workers.
 *
 * <p>*agentic-note*: design-reconciliation-document, section 3 (scheduler): a warm session has <em>no step limit</em>; the memory
 * watermark and first-failure polling ({@link RunSignals}) are what stops a unit there. Step budgets
 * exist only for strict and differential runs ({@link #strict(long, long)}).
 */
public final class Budget {

    /** No limit. */
    public static final long UNLIMITED = Long.MAX_VALUE;

    /** Default byte limit of a warm attempt. */
    public static final long WARM_MAX_BYTES = 1L << 30;

    /** Default depth limit of a warm attempt (engines with host recursion only). */
    public static final int WARM_MAX_DEPTH = 1 << 16;

    /** Engine steps (transitions of the machine; cache misses of the reference kernel). */
    public final long maxSteps;
    /** Bytes of session memory (overlay terms plus the engine's own arenas) the unit may add. */
    public final long maxBytes;
    /** Nesting depth of the engine's own recursion, if it has any. */
    public final int maxDepth;

    public Budget(long maxSteps, long maxBytes, int maxDepth) {
        this.maxSteps = maxSteps;
        this.maxBytes = maxBytes;
        this.maxDepth = maxDepth;
    }

    /**
     * First attempt in a warm session: no step limit (*agentic-note*: design-reconciliation-document: "no step limit in warm
     * sessions"), a byte limit so that a heavy unit moves to the heavy lane, and a depth limit that
     * fits a worker's stack.
     */
    public static Budget warm() {
        return WARM;
    }

    /** Final attempt in a fresh session on a deep-stack thread: only memory is limited. */
    public static Budget cold(long maxBytes) {
        return new Budget(UNLIMITED, maxBytes, Integer.MAX_VALUE);
    }

    /**
     * Strict and differential runs: the only place where steps are limited, so that two engines (or
     * two runs of one engine) that disagree about termination end deterministically.
     */
    public static Budget strict(long maxSteps, long maxBytes) {
        return new Budget(maxSteps, maxBytes, Integer.MAX_VALUE);
    }

    public Budget withMaxSteps(long steps) {
        return new Budget(steps, maxBytes, maxDepth);
    }

    public Budget withMaxBytes(long bytes) {
        return new Budget(maxSteps, bytes, maxDepth);
    }

    public Budget withMaxDepth(int depth) {
        return new Budget(maxSteps, maxBytes, depth);
    }

    public boolean hasStepLimit() {
        return maxSteps != UNLIMITED;
    }

    private static final Budget WARM = new Budget(UNLIMITED, WARM_MAX_BYTES, WARM_MAX_DEPTH);

    @Override
    public String toString() {
        return "Budget[steps=" + (maxSteps == UNLIMITED ? "unlimited" : Long.toString(maxSteps)) + ", bytes="
                + (maxBytes == UNLIMITED ? "unlimited" : Long.toString(maxBytes)) + ", depth="
                + (maxDepth == Integer.MAX_VALUE ? "unlimited" : Integer.toString(maxDepth)) + "]";
    }
}
