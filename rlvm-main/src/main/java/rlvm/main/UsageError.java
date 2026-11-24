package rlvm.main;

/**
 * A setting passed with {@code -X KEY[=VALUE]} that this checker does not know or cannot parse, or an
 * engine name without a provider. It is never ignored: a mistyped option must not silently run another
 * configuration.
 *
 * <p>Thrown only while a run is being configured, before the export is opened.
 * {@link RlvmChecker#check} turns it into the launcher's {@code rlvm.cli.UsageException}: exit status
 * 64 and the message on standard error. Programmatic callers ({@link RlvmChecker#configure},
 * {@link RlvmChecker#checkExport}) see this unchecked exception. Either way it cannot become a verdict.
 */
public final class UsageError extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public UsageError(String message) {
        super(message);
    }
}
