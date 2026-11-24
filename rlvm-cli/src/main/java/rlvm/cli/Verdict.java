package rlvm.cli;

/**
 * The three answers a {@link Checker} may give for an export. There is deliberately no "error" constant:
 * an internal failure is signalled by throwing, and the launcher turns every throwable into
 * {@link ExitCode#INTERNAL_ERROR}.
 */
public enum Verdict {
    /** Every declaration of the export was checked and is accepted. */
    ACCEPT(ExitCode.ACCEPT),
    /** The export is invalid: some declaration does not type-check, or the file is malformed. */
    REJECT(ExitCode.REJECT),
    /** The checker cannot or will not decide (unsupported feature, resource limit, no kernel plugged in). */
    DECLINE(ExitCode.DECLINE);

    private final int exitCode;

    Verdict(int exitCode) {
        this.exitCode = exitCode;
    }

    /** Process exit status the Lean Kernel Arena expects for this verdict. */
    public int exitCode() {
        return exitCode;
    }
}
