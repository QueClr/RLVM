package rlvm.api;

/**
 * A kernel error: the declaration being checked is not admissible. Thrown by {@link KernelOps} and by
 * admission code; engines convert it into {@link Outcome#REJECT}. Carries no stack trace (rejections
 * are ordinary results on bad inputs); the terms are for diagnostics only.
 */
public final class KernelException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final Reason reason;
    private final int term1;
    private final int term2;

    public KernelException(Reason reason) {
        this(reason, 0, 0);
    }

    public KernelException(Reason reason, int term1, int term2) {
        super(reason.name(), null, false, false);
        if (!reason.isReject()) {
            throw new IllegalArgumentException("not a reject reason: " + reason);
        }
        this.reason = reason;
        this.term1 = term1;
        this.term2 = term2;
    }

    public Reason reason() {
        return reason;
    }

    public int term1() {
        return term1;
    }

    public int term2() {
        return term2;
    }
}
