package rlvm.api;

/**
 * Why a unit was rejected or declined. The first group are kernel errors (REJECT), named after the
 * official kernel's exceptions; the second group are DECLINE reasons.
 *
 * <p>Constants are only ever appended inside their group's tail (ordinals are not stable across
 * releases and must not be persisted; use {@link #name()}).
 */
public enum Reason {
    // ---- reject: the official kernel raises an error -------------------------------------------------
    MALFORMED_EXPORT(true),
    DUPLICATE_DECLARATION(true),
    DUPLICATE_LEVEL_PARAM(true),
    UNDECLARED_LEVEL_PARAM(true),
    UNKNOWN_CONSTANT(true),
    LEVEL_ARITY_MISMATCH(true),
    LOOSE_BVAR(true),
    TYPE_EXPECTED(true),
    FUNCTION_EXPECTED(true),
    APP_TYPE_MISMATCH(true),
    LET_TYPE_MISMATCH(true),
    DECL_TYPE_MISMATCH(true),
    THEOREM_NOT_PROP(true),
    INVALID_PROJECTION(true),
    NAT_LITERAL_TOO_BIG(true),
    INDUCTIVE_INVALID(true),
    RECORD_MISMATCH(true),
    QUOT_MISMATCH(true),

    // ---- decline: no verdict ------------------------------------------------------------------------
    BUDGET_STEPS(false),
    BUDGET_MEMORY(false),
    STACK_DEPTH(false),
    UNSUPPORTED_INPUT(false),
    UNSUPPORTED_PRELUDE(false),
    FORWARD_REFERENCE(false),
    ENGINE_DISAGREEMENT(false),
    UNCONFIRMED_REJECT(false),
    INTERNAL_ERROR(false),
    /**
     * The unit was abandoned because its result can no longer matter: an earlier unit already failed
     * ({@link RunSignals#firstFailure()}) or the run is shutting down (RECONCILIATION.md, facade). It
     * is never the reported reason of a run: the verdict is the status of the first non-ACCEPT unit
     * in file order, and a cancelled unit always lies behind that one.
     */
    CANCELLED(false);

    private final boolean reject;

    Reason(boolean reject) {
        this.reject = reject;
    }

    public boolean isReject() {
        return reject;
    }

    public int outcome() {
        return reject ? Outcome.REJECT : Outcome.DECLINE;
    }

    /**
     * True for decline reasons that say nothing about the unit itself, only about the attempt
     * (budget, cancellation): a retry under another budget or in a fresh session may decide the unit.
     */
    public boolean isRetryable() {
        return this == BUDGET_STEPS || this == BUDGET_MEMORY || this == STACK_DEPTH || this == CANCELLED;
    }
}
