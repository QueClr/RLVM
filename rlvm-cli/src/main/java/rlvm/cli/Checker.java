package rlvm.cli;

import java.nio.file.Path;

/**
 * Entry point of a kernel implementation, as seen by the launcher.
 *
 * <p><b>Plugging one in.</b> Put a jar on the class path that contains an implementation with a public
 * no-argument constructor and a file {@code META-INF/services/rlvm.cli.Checker} naming it. Among all
 * implementations found the one with the highest {@link #priority()} runs, unless {@code --checker NAME}
 * selects another. {@code scripts/build.sh} puts the jar of every module of the reactor on the class path
 * of both the JVM launcher and the native image, so a new driver module needs no change here. The
 * built-in {@link PlaceholderChecker} is used when nothing else is found; it declines.
 *
 * <p><b>Contract.</b>
 * <ul>
 * <li>{@link #check} is called once, on a thread with {@link Options#stackBytes()} of stack.</li>
 * <li>Return {@link Verdict#ACCEPT} only after the <em>whole</em> export has been read and every
 * declaration has been checked. When in doubt return {@link Verdict#DECLINE} or throw.</li>
 * <li>Do not catch {@link Throwable}/{@link Error} in order to carry on: let {@link OutOfMemoryError},
 * {@link StackOverflowError} and unexpected exceptions propagate (from worker threads too, either by
 * rethrowing on the calling thread or by simply not catching: the launcher records every uncaught
 * throwable of every thread). Any of them ends the run with {@link ExitCode#INTERNAL_ERROR}; none can
 * turn into an acceptance.</li>
 * <li>Never call {@link System#exit}; the launcher owns the exit status.</li>
 * <li>A setting the checker does not know or cannot parse ({@link Options#extra()}, the thread count) is
 * reported by throwing {@link UsageException} from {@link #check} before any work is done: status 64.
 * Nothing is ignored silently: a mistyped option must not run another configuration.</li>
 * </ul>
 */
public interface Checker {
    /** Short lower-case identifier, used by {@code --checker} and shown by {@code --version}. */
    String name();

    /** One line for {@code --version}. */
    default String description() {
        return getClass().getName();
    }

    /** The implementation with the highest priority is the default. The placeholder has {@link Integer#MIN_VALUE}. */
    default int priority() {
        return 0;
    }

    /**
     * Checks one lean4export NDJSON file.
     *
     * @param export path of the export (may be a symbolic link, as in the arena's cache)
     * @param options launcher settings
     * @return the verdict; {@code null} is treated as an internal error
     * @throws UsageException a setting in {@code options} is unknown or malformed; mapped to {@link ExitCode#USAGE}
     * @throws Exception any other failure; mapped to {@link ExitCode#INTERNAL_ERROR}
     */
    Verdict check(Path export, Options options) throws Exception;
}
