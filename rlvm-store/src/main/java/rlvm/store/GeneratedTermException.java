package rlvm.store;

/**
 * A term or a constant cannot be added to the generated (shared) region: it has a free or loose
 * variable, an unbound reference, or a level, level list or literal that exists only in the calling
 * thread's overlays ({@link GeneratedBuilder}). Nothing was added. Admission turns it into a decline:
 * the official kernel would have generated the constant, RLVM cannot represent it for every worker.
 */
public final class GeneratedTermException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public GeneratedTermException(String message) {
        super(message);
    }

    public GeneratedTermException(String message, Throwable cause) {
        super(message, cause);
    }
}
