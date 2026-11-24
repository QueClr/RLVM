package rlvm.level;

/**
 * Thrown when a level would exceed a hard limit of the official kernel (depth above 2^24-1, where the
 * official kernel panics with "universe level depth is too big"). The input must be rejected.
 */
public final class LevelLimitException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public LevelLimitException(String message) {
        super(message);
    }
}
