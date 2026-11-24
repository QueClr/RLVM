package rlvm.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A checker that misbehaves on request, to test the verdict protocol of a <em>built</em> launcher (JVM
 * launcher script, native image), where unit tests cannot reach: real stack overflow on the large-stack
 * thread, real heap exhaustion, a dying worker thread.
 *
 * <p>It is not a registered {@link Checker} provider and is reachable only through the explicit
 * command-line option {@code --protocol-selftest MODE} (never through the environment), which no
 * arena run command passes. {@code bench/rlvm/run.sh --selftest} drives it.
 */
final class SelfTestChecker implements Checker {
    static final String[] MODES = {
        "accept", "reject", "decline", "null", "throw", "error", "oom", "oom-real", "stackoverflow", "thread-death",
        "usage",
    };

    private final String mode;

    SelfTestChecker(String mode) {
        this.mode = mode;
    }

    static boolean isMode(String mode) {
        for (String m : MODES) {
            if (m.equals(mode)) {
                return true;
            }
        }
        return false;
    }

    /** Exit status the launcher must produce for {@code mode}. */
    static int expectedExitCode(String mode) {
        return switch (mode) {
            case "accept" -> ExitCode.ACCEPT;
            case "reject" -> ExitCode.REJECT;
            case "decline" -> ExitCode.DECLINE;
            case "usage" -> ExitCode.USAGE;
            default -> ExitCode.INTERNAL_ERROR;
        };
    }

    @Override
    public String name() {
        return "selftest:" + mode;
    }

    @Override
    public Verdict check(Path export, Options options) throws Exception {
        switch (mode) {
            case "accept":
                return Verdict.ACCEPT;
            case "reject":
                return Verdict.REJECT;
            case "decline":
                return Verdict.DECLINE;
            case "null":
                return null;
            case "throw":
                throw new IllegalStateException("selftest: checked failure path");
            case "error":
                throw new AssertionError("selftest: error path");
            case "oom":
                throw new OutOfMemoryError("selftest: synthetic");
            case "oom-real":
                return exhaustHeap();
            case "stackoverflow":
                // The result is deliberately an acceptance: only the throwable keeps the status from being 0.
                return descend(1) > 0 ? Verdict.ACCEPT : Verdict.ACCEPT;
            case "thread-death":
                return dyingWorker(options);
            case "usage":
                // What a checker does with a setting it does not know (-X no-such-key).
                throw new UsageException("selftest: unknown checker setting");
            default:
                throw new IllegalArgumentException("unknown selftest mode: " + mode);
        }
    }

    /** Unbounded, not a tail call. */
    private static long descend(long depth) {
        return descend(depth + 1) + 1;
    }

    /**
     * Fills the heap until allocation fails. Refuses to start unless the heap is capped at 1 GiB, so that a
     * careless invocation cannot take the machine's memory (a native image defaults to 80% of RAM).
     */
    private static Verdict exhaustHeap() {
        long max = Runtime.getRuntime().maxMemory();
        if (max > (1L << 30)) {
            throw new IllegalStateException("selftest oom-real needs a heap limit of at most 1g (run with -Xmx256m); max heap is " + (max >> 20) + " MiB");
        }
        List<long[]> hold = new ArrayList<>();
        while (true) {
            hold.add(new long[1 << 20]);
            if (hold.size() < 0) {
                break;
            }
        }
        return Verdict.ACCEPT;
    }

    /** A worker dies of an uncaught exception; the "driver" does not notice and accepts. */
    private static Verdict dyingWorker(Options options) throws InterruptedException {
        Thread worker = new Thread(null, () -> {
            throw new IllegalStateException("selftest: worker died");
        }, "rlvm-selftest-worker", options.stackBytes());
        worker.start();
        worker.join();
        return Verdict.ACCEPT;
    }
}
