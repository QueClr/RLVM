package rlvm.api;

/**
 * The engine gives up on the current unit without a verdict (budget exhausted, input outside what is
 * implemented, guard failed). Engines convert it into {@link Outcome#DECLINE}; it must never be
 * turned into a rejection and nothing learned on the way may stay in a cache.
 */
public final class ResourceException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final Reason reason;

    public ResourceException(Reason reason) {
        super(reason.name(), null, false, false);
        if (reason.isReject()) {
            throw new IllegalArgumentException("not a decline reason: " + reason);
        }
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
