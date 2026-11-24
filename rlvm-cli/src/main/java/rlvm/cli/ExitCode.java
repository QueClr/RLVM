package rlvm.cli;

/**
 * Process exit statuses.
 *
 * <p>The arena reads 0 as "accepted", 1 as "rejected", 2 as "declined" and everything else as "error"
 * (scored like a decline). Two facts shape the rest of the launcher:
 * <ul>
 * <li>a JVM and a native image both exit with status <b>1</b> when the main thread dies of an uncaught
 * exception, which would read as a rejection, so nothing may escape {@code Main.main};</li>
 * <li>{@code -XX:+ExitOnOutOfMemoryError} makes the runtime itself exit with status <b>3</b>, which is why
 * {@link #INTERNAL_ERROR} is 3: both routes out of an out-of-memory situation give the same status.</li>
 * </ul>
 */
public final class ExitCode {
    /** The whole export was processed and accepted. Only {@link Verdict#ACCEPT} maps to it. */
    public static final int ACCEPT = 0;
    /** The export was rejected. */
    public static final int REJECT = 1;
    /** The checker declined to decide. */
    public static final int DECLINE = 2;
    /** Anything unexpected: a throwable, a missing verdict, a thread that died. The initial value of every run. */
    public static final int INTERNAL_ERROR = 3;
    /** Bad command line, or the export file is missing or unreadable (sysexits.h EX_USAGE). */
    public static final int USAGE = 64;

    private ExitCode() {
    }

    /** Name of a status for messages. */
    public static String describe(int code) {
        return switch (code) {
            case ACCEPT -> "accept";
            case REJECT -> "reject";
            case DECLINE -> "decline";
            case INTERNAL_ERROR -> "internal error";
            case USAGE -> "usage error";
            default -> "exit status " + code;
        };
    }
}
