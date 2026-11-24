package rlvm.load;

/**
 * The input may be valid for the official reader but is outside what RLVM implements (exit code 2):
 * an id beyond 63 bits, more than 2^30 shared terms, a de Bruijn index beyond 2^31, a format version
 * with another major number, memory limits of the store.
 */
public final class UnsupportedExportException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public UnsupportedExportException(String message) {
        super(message, null, false, false);
    }
}
