package rlvm.cli;

import java.io.PrintStream;
import java.nio.file.Path;

/**
 * The fail-closed verdict protocol: runs a {@link Checker} on a large-stack thread and turns what
 * happened into an exit status.
 *
 * <p>The status starts as {@link ExitCode#INTERNAL_ERROR}. It is replaced exactly once, in
 * {@link #decide}, and only when <em>all</em> of the following hold:
 * <ol>
 * <li>{@code check} returned normally (no throwable of any kind, including {@link OutOfMemoryError} and
 * {@link StackOverflowError});</li>
 * <li>it returned a non-null {@link Verdict};</li>
 * <li>no thread of the process died of an uncaught throwable while the check was running;</li>
 * <li>the launcher thread was not interrupted while waiting.</li>
 * </ol>
 * Then the status is the verdict's: 0 only for {@link Verdict#ACCEPT}. Condition 3 also protects
 * rejections: a worker that died may have left work undone that the driver then misreads.
 *
 * <p>One more status can leave here: {@link ExitCode#USAGE} (64), when {@code check} threw a
 * {@link UsageException} (a checker setting it does not know) and neither condition 3 nor 4 is
 * violated. It is the same status the launcher gives for a bad option of its own, and like
 * {@link ExitCode#INTERNAL_ERROR} it is neither 0, 1 nor 2.
 *
 * <p>On an exhausted heap: the checker thread's catch block and the uncaught-exception handler only
 * store a reference, and an allocation failure in the protocol's own code (the {@link Outcome}, the
 * report) propagates to {@code Main.main}, whose status is still {@link ExitCode#INTERNAL_ERROR}.
 */
final class VerdictProtocol {
    private VerdictProtocol() {
    }

    /** What a run produced. */
    static final class Outcome {
        /** Process exit status; never {@link ExitCode#ACCEPT} unless the checker returned {@link Verdict#ACCEPT} cleanly. */
        final int exitCode;
        /** The verdict {@code check} returned, or null. Informational: {@link #exitCode} is what counts. */
        final Verdict verdict;
        /** The throwable that ended {@code check} or killed a thread, or null. */
        final Throwable failure;
        /** Short reason when {@link #exitCode} is {@link ExitCode#INTERNAL_ERROR}, else null. */
        final String reason;
        /** The checker's usage message when {@link #exitCode} is {@link ExitCode#USAGE}, else null. */
        final String usage;

        Outcome(int exitCode, Verdict verdict, Throwable failure, String reason) {
            this(exitCode, verdict, failure, reason, null);
        }

        Outcome(int exitCode, Verdict verdict, Throwable failure, String reason, String usage) {
            this.exitCode = exitCode;
            this.verdict = verdict;
            this.failure = failure;
            this.reason = reason;
            this.usage = usage;
        }
    }

    /** Shared between the launcher thread, the checker thread and the uncaught-exception handler. */
    private static final class Cell implements Thread.UncaughtExceptionHandler, Runnable {
        private final Checker checker;
        private final Path export;
        private final Options options;

        // Written by the checker thread, read by the launcher after join().
        private Verdict verdict;
        private Throwable thrown;
        private boolean returned;
        // Written by whichever thread dies.
        private volatile Throwable uncaught;

        Cell(Checker checker, Path export, Options options) {
            this.checker = checker;
            this.export = export;
            this.options = options;
        }

        @Override
        public void run() {
            try {
                verdict = checker.check(export, options);
                returned = true;
            } catch (Throwable t) {
                thrown = t;
            }
        }

        @Override
        public void uncaughtException(Thread thread, Throwable t) {
            // No allocation: this may be running because the heap is exhausted.
            if (uncaught == null) {
                uncaught = t;
            }
        }
    }

    /** Runs {@code checker} on a new thread with {@link Options#stackBytes()} of stack and waits for it. */
    static Outcome run(Checker checker, Path export, Options options) {
        Cell cell = new Cell(checker, export, options);
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        boolean interrupted = false;
        Thread.setDefaultUncaughtExceptionHandler(cell);
        try {
            Thread thread = new Thread(null, cell, "rlvm-checker", options.stackBytes());
            thread.setDaemon(true);
            thread.start();
            try {
                thread.join();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
        return decide(cell.returned, cell.verdict, cell.thrown, cell.uncaught, interrupted);
    }

    /** The whole decision table; a pure function so that it can be tested exhaustively. */
    static Outcome decide(boolean returned, Verdict verdict, Throwable thrown, Throwable uncaught, boolean interrupted) {
        int code = ExitCode.INTERNAL_ERROR;
        String reason;
        Throwable failure = thrown != null ? thrown : uncaught;
        if (interrupted) {
            reason = "interrupted while waiting for the checker";
        } else if (thrown instanceof UsageException && uncaught == null) {
            // A setting the checker refuses: the command line is wrong, nothing was checked.
            String message = thrown.getMessage();
            return new Outcome(ExitCode.USAGE, null, thrown, null, message == null ? "usage error" : message);
        } else if (thrown != null) {
            reason = "the checker threw";
        } else if (!returned) {
            reason = "the checker thread ended without a result";
        } else if (uncaught != null) {
            reason = "a thread died of an uncaught throwable";
        } else if (verdict == null) {
            reason = "the checker returned no verdict";
        } else {
            code = verdict.exitCode();
            reason = null;
        }
        return new Outcome(code, verdict, failure, reason);
    }

    /** Prints why a run ended with an internal error. Never throws. */
    static void report(Outcome outcome, PrintStream err) {
        if (outcome.usage != null) {
            try {
                err.println("rlvm: " + outcome.usage);
                err.println("rlvm: try 'rlvm --help'");
            } catch (Throwable ignored) {
                // As below.
            }
            return;
        }
        if (outcome.reason == null) {
            return;
        }
        try {
            err.println("rlvm: internal error: " + outcome.reason);
            Throwable t = outcome.failure;
            if (t != null) {
                printCompact(t, err);
            }
        } catch (Throwable ignored) {
            // The exit status is already fixed; a failed diagnostic must not change it.
        }
    }

    /** Stack traces can be a thousand frames (stack overflow); the arena keeps 100 lines of output. */
    private static void printCompact(Throwable t, PrintStream err) {
        final int maxFrames = 24;
        int depth = 0;
        while (t != null && depth < 4) {
            err.println((depth == 0 ? "rlvm:   " : "rlvm:   caused by: ") + t);
            StackTraceElement[] frames = t.getStackTrace();
            for (int i = 0; i < frames.length && i < maxFrames; i++) {
                err.println("rlvm:     at " + frames[i]);
            }
            if (frames.length > maxFrames) {
                err.println("rlvm:     ... " + (frames.length - maxFrames) + " more");
            }
            t = t.getCause();
            depth++;
        }
    }
}
