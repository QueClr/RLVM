package rlvm.load;

/**
 * The input is not a valid export in a way the official reader rejects (exit code 1): invalid JSON,
 * unknown record shape, an id used before its definition or bound twice, a declaration name seen
 * twice, a non-digit {@code natVal}, a blank line.
 */
public final class MalformedExportException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final long line;

    public MalformedExportException(String message, long line) {
        super(message, null, false, false);
        this.line = line;
    }

    /** 1-based line number, or 0 if unknown. */
    public long line() {
        return line;
    }
}
