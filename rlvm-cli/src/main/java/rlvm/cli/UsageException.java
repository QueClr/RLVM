package rlvm.cli;

/**
 * A problem with the command line, the environment or a checker setting; ends the run with
 * {@link ExitCode#USAGE} (64).
 *
 * <p>The launcher throws it for its own options. A {@link Checker} may throw it from
 * {@link Checker#check} for a setting it does not know or cannot parse ({@code -X KEY[=VALUE]}, an
 * engine name, a thread count): the run then ends with status 64 and the message on standard error,
 * instead of 3 (internal error). It must be thrown before any checking work is done: it says "this
 * command line is wrong", never anything about the export. Like every status but 0, 1 and 2 it reads
 * as an error to the arena and cannot become a verdict.
 */
public final class UsageException extends Exception {
    private static final long serialVersionUID = 1L;

    public UsageException(String message) {
        super(message);
    }
}
