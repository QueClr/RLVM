package rlvm.store;

/**
 * A structure of the shared store reached a hard representation limit (2^28 - 1 nodes in a region,
 * 2 GiB in one pool, 65,535 universe parameters, ...). The loader maps it to a decline
 * ({@code UnsupportedExportException}); it never means the input is wrong.
 */
public final class StoreLimitException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public StoreLimitException(String message) {
        super(message, null, false, false);
    }
}
