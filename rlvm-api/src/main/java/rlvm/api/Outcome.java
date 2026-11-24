package rlvm.api;

/**
 * Result codes of checking one unit, as {@code int}s (no allocation on the accept path), and the
 * process exit codes of the arena contract.
 */
public final class Outcome {
    private Outcome() {}

    /** The unit is admitted. */
    public static final int ACCEPT = 0;
    /** The unit is definitely not admissible under the official kernel's rules. */
    public static final int REJECT = 1;
    /** The engine cannot decide (budget, unsupported input, internal error). Never a guess. */
    public static final int DECLINE = 2;

    /**
     * Process exit codes ({@code lka.py}: 0 accepted, 1 rejected, 2 declined, anything else error).
     * They equal the outcome codes. The launcher ({@code rlvm-cli}) owns the process status and adds
     * 3 = internal error (its initial value) and 64 = usage error.
     */
    public static final int EXIT_ACCEPT = 0;
    public static final int EXIT_REJECT = 1;
    public static final int EXIT_DECLINE = 2;

    public static String name(int outcome) {
        switch (outcome) {
            case ACCEPT:
                return "accept";
            case REJECT:
                return "reject";
            case DECLINE:
                return "decline";
            default:
                return "invalid(" + outcome + ")";
        }
    }
}
