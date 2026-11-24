package rlvm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/** The fail-closed protocol: only a clean {@link Verdict#ACCEPT} may become exit status 0. */
class VerdictProtocolTest {
    private static final Path EXPORT = Path.of("unused.ndjson");

    /** A checker from a lambda. */
    private interface Body {
        Verdict check(Path export, Options options) throws Exception;
    }

    private static Checker checker(Body body) {
        return new Checker() {
            @Override
            public String name() {
                return "test";
            }

            @Override
            public Verdict check(Path export, Options options) throws Exception {
                return body.check(export, options);
            }
        };
    }

    private static Options options(long stackBytes) {
        PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        return Options.builder().stackBytes(stackBytes).out(sink).err(sink).build();
    }

    private static VerdictProtocol.Outcome run(Body body) {
        return VerdictProtocol.run(checker(body), EXPORT, options(1 << 20));
    }

    // ------------------------------------------------------------------ the decision table

    @Test
    void decisionTableIsFailClosed() {
        Throwable[] throwables = {null, new RuntimeException("x")};
        Verdict[] verdicts = {null, Verdict.ACCEPT, Verdict.REJECT, Verdict.DECLINE};
        int combinations = 0;
        for (boolean returned : new boolean[] {false, true}) {
            for (Verdict verdict : verdicts) {
                for (Throwable thrown : throwables) {
                    for (Throwable uncaught : throwables) {
                        for (boolean interrupted : new boolean[] {false, true}) {
                            VerdictProtocol.Outcome o = VerdictProtocol.decide(returned, verdict, thrown, uncaught, interrupted);
                            boolean clean = returned && verdict != null && thrown == null && uncaught == null && !interrupted;
                            String what = returned + " " + verdict + " " + thrown + " " + uncaught + " " + interrupted;
                            if (clean) {
                                assertEquals(verdict.exitCode(), o.exitCode, what);
                                assertNull(o.reason, what);
                            } else {
                                assertEquals(ExitCode.INTERNAL_ERROR, o.exitCode, what);
                                assertNotNull(o.reason, what);
                            }
                            // The property the arena cares about, stated on its own.
                            if (o.exitCode == ExitCode.ACCEPT) {
                                assertTrue(clean && verdict == Verdict.ACCEPT, what);
                            }
                            combinations++;
                        }
                    }
                }
            }
        }
        assertEquals(64, combinations);
    }

    @Test
    void aUsageExceptionOfTheCheckerIsStatus64AndNothingElseIs() {
        UsageException usage = new UsageException("unknown checker setting -X nonesuch");
        Throwable other = new RuntimeException("x");
        Throwable[] throwables = {null, usage, other};
        Verdict[] verdicts = {null, Verdict.ACCEPT, Verdict.REJECT, Verdict.DECLINE};
        int usageOutcomes = 0;
        for (boolean returned : new boolean[] {false, true}) {
            for (Verdict verdict : verdicts) {
                for (Throwable thrown : throwables) {
                    for (Throwable uncaught : throwables) {
                        for (boolean interrupted : new boolean[] {false, true}) {
                            VerdictProtocol.Outcome o = VerdictProtocol.decide(returned, verdict, thrown, uncaught, interrupted);
                            String what = returned + " " + verdict + " " + thrown + " " + uncaught + " " + interrupted;
                            boolean isUsage = thrown == usage && uncaught == null && !interrupted;
                            if (isUsage) {
                                assertEquals(ExitCode.USAGE, o.exitCode, what);
                                assertEquals("unknown checker setting -X nonesuch", o.usage, what);
                                assertNull(o.reason, what);
                                assertNull(o.verdict, what);
                                usageOutcomes++;
                            } else {
                                assertNotEquals(ExitCode.USAGE, o.exitCode, what);
                                assertNull(o.usage, what);
                            }
                            if (thrown != null || uncaught != null || interrupted) {
                                // Whatever was thrown, on whichever thread: never a verdict.
                                assertTrue(o.exitCode == ExitCode.USAGE || o.exitCode == ExitCode.INTERNAL_ERROR, what);
                            }
                        }
                    }
                }
            }
        }
        assertEquals(8, usageOutcomes);
        // A usage exception that died on another thread is not the checker's answer: internal error.
        assertEquals(ExitCode.INTERNAL_ERROR, VerdictProtocol.decide(true, Verdict.ACCEPT, null, usage, false).exitCode);
    }

    @Test
    void aUsageExceptionIsReportedWithItsMessage() {
        VerdictProtocol.Outcome o = run((e, opts) -> {
            throw new UsageException("-X load=sideways: expected batch or pipelined");
        });
        assertEquals(ExitCode.USAGE, o.exitCode);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        VerdictProtocol.report(o, new PrintStream(bytes, true, StandardCharsets.UTF_8));
        String text = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(text.startsWith("rlvm: -X load=sideways: expected batch or pipelined"), text);
        assertFalse(text.contains("internal error"), text);
        assertFalse(text.contains("\tat ") || text.contains("    at "), "no stack trace for a usage error: " + text);
    }

    @Test
    void exitCodesAreTheArenaOnes() {
        assertEquals(0, Verdict.ACCEPT.exitCode());
        assertEquals(1, Verdict.REJECT.exitCode());
        assertEquals(2, Verdict.DECLINE.exitCode());
        assertEquals(0, ExitCode.ACCEPT);
        assertEquals(1, ExitCode.REJECT);
        assertEquals(2, ExitCode.DECLINE);
        // Not 0, 1 or 2, and equal to what -XX:+ExitOnOutOfMemoryError makes the VM exit with.
        assertEquals(3, ExitCode.INTERNAL_ERROR);
        assertEquals(64, ExitCode.USAGE);
        assertEquals(3, Verdict.values().length, "a new verdict needs a decision about its exit status");
    }

    // ------------------------------------------------------------------ verdicts

    @Test
    void verdictsMapToTheirStatus() {
        assertEquals(0, run((e, o) -> Verdict.ACCEPT).exitCode);
        assertEquals(1, run((e, o) -> Verdict.REJECT).exitCode);
        assertEquals(2, run((e, o) -> Verdict.DECLINE).exitCode);
    }

    @Test
    void placeholderDeclines() {
        VerdictProtocol.Outcome o = VerdictProtocol.run(new PlaceholderChecker(), EXPORT, options(1 << 20));
        assertEquals(ExitCode.DECLINE, o.exitCode);
        assertSame(Verdict.DECLINE, o.verdict);
    }

    @Test
    void nullVerdictIsAnInternalError() {
        VerdictProtocol.Outcome o = run((e, opts) -> null);
        assertEquals(ExitCode.INTERNAL_ERROR, o.exitCode);
        assertNotNull(o.reason);
    }

    // ------------------------------------------------------------------ throwables

    @Test
    void everyKindOfThrowableIsAnInternalError() {
        Throwable[] kinds = {
            new RuntimeException("unchecked"),
            new Exception("checked"),
            new java.io.IOException("io"),
            new AssertionError("assertion"),
            new OutOfMemoryError("synthetic"),
            new StackOverflowError("synthetic"),
            new LinkageError("linkage"),
            new Throwable("bare throwable"),
        };
        for (Throwable kind : kinds) {
            VerdictProtocol.Outcome o = VerdictProtocol.run(checker((e, opts) -> {
                throw sneaky(kind);
            }), EXPORT, options(1 << 20));
            assertEquals(ExitCode.INTERNAL_ERROR, o.exitCode, kind.toString());
            assertSame(kind, o.failure, kind.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneaky(Throwable t) throws T {
        throw (T) t;
    }

    @Test
    void realStackOverflowIsAnInternalError() {
        VerdictProtocol.Outcome o = run((e, opts) -> descend(0) > 0 ? Verdict.ACCEPT : Verdict.ACCEPT);
        assertEquals(ExitCode.INTERNAL_ERROR, o.exitCode);
        assertTrue(o.failure instanceof StackOverflowError, String.valueOf(o.failure));
    }

    private static long descend(long depth) {
        return descend(depth + 1) + 1;
    }

    @Test
    void realOutOfMemoryIsAnInternalError() {
        // Only with a small heap (surefire runs with -Xmx1g): there each request below fails up front,
        // without touching memory. On a 30 GB default heap it would really allocate.
        org.junit.jupiter.api.Assumptions.assumeTrue(Runtime.getRuntime().maxMemory() <= (2L << 30));
        VerdictProtocol.Outcome o = run((e, opts) -> {
            long[][] hog = new long[64][];
            for (int i = 0; i < hog.length; i++) {
                hog[i] = new long[Integer.MAX_VALUE - 16];
            }
            return hog.length > 0 ? Verdict.ACCEPT : Verdict.ACCEPT;
        });
        assertEquals(ExitCode.INTERNAL_ERROR, o.exitCode);
        assertTrue(o.failure instanceof OutOfMemoryError, String.valueOf(o.failure));
    }

    @Test
    void reportNeverThrows() {
        VerdictProtocol.Outcome o = run((e, opts) -> {
            throw new IllegalStateException("boom", new RuntimeException("cause"));
        });
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        VerdictProtocol.report(o, new PrintStream(bytes, true, StandardCharsets.UTF_8));
        String text = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("internal error"), text);
        assertTrue(text.contains("boom"), text);
        assertTrue(text.contains("cause"), text);
        // A stream that fails on every write must not change anything.
        PrintStream broken = new PrintStream(new java.io.OutputStream() {
            @Override
            public void write(int b) {
                throw new IllegalStateException("broken stream");
            }
        });
        VerdictProtocol.report(o, broken);
        assertEquals(ExitCode.INTERNAL_ERROR, o.exitCode);
    }

    @Test
    void stackOverflowReportIsShort() {
        VerdictProtocol.Outcome o = run((e, opts) -> descend(0) > 0 ? Verdict.ACCEPT : Verdict.ACCEPT);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        VerdictProtocol.report(o, new PrintStream(bytes, true, StandardCharsets.UTF_8));
        long lines = bytes.toString(StandardCharsets.UTF_8).lines().count();
        assertTrue(lines <= 40, "the arena keeps 100 lines; got " + lines);
    }

    // ------------------------------------------------------------------ other threads

    @Test
    void dyingWorkerSpoilsAnAcceptance() {
        VerdictProtocol.Outcome o = run((e, opts) -> {
            Thread worker = new Thread(() -> {
                throw new IllegalStateException("worker died");
            }, "worker");
            worker.start();
            worker.join();
            return Verdict.ACCEPT;
        });
        assertEquals(ExitCode.INTERNAL_ERROR, o.exitCode);
        assertSame(Verdict.ACCEPT, o.verdict);
        assertTrue(o.failure instanceof IllegalStateException, String.valueOf(o.failure));
    }

    @Test
    void dyingWorkerSpoilsARejectionToo() {
        VerdictProtocol.Outcome o = run((e, opts) -> {
            Thread worker = new Thread(() -> {
                throw new OutOfMemoryError("worker out of memory");
            }, "worker");
            worker.start();
            worker.join();
            return Verdict.REJECT;
        });
        assertEquals(ExitCode.INTERNAL_ERROR, o.exitCode);
    }

    @Test
    void uncaughtHandlerIsRestored() {
        Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();
        Thread.UncaughtExceptionHandler mine = (t, e) -> {
        };
        Thread.setDefaultUncaughtExceptionHandler(mine);
        try {
            AtomicReference<Thread.UncaughtExceptionHandler> during = new AtomicReference<>();
            assertEquals(0, run((e, opts) -> {
                during.set(Thread.getDefaultUncaughtExceptionHandler());
                return Verdict.ACCEPT;
            }).exitCode);
            assertNotEquals(mine, during.get(), "the protocol's own handler must be active during the check");
            assertSame(mine, Thread.getDefaultUncaughtExceptionHandler());
            run((e, opts) -> {
                throw new Error("x");
            });
            assertSame(mine, Thread.getDefaultUncaughtExceptionHandler());
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before);
        }
    }

    @Test
    void interruptedLauncherDoesNotAccept() throws Exception {
        AtomicReference<VerdictProtocol.Outcome> outcome = new AtomicReference<>();
        Thread launcher = new Thread(() -> outcome.set(run((e, opts) -> {
            Thread.sleep(600);
            return Verdict.ACCEPT;
        })), "launcher");
        launcher.start();
        Thread.sleep(100);
        launcher.interrupt();
        launcher.join();
        assertEquals(ExitCode.INTERNAL_ERROR, outcome.get().exitCode);
    }

    // ------------------------------------------------------------------ the checker thread

    @Test
    void checkerRunsOnItsOwnNamedThread() {
        AtomicReference<String> name = new AtomicReference<>();
        Thread caller = Thread.currentThread();
        AtomicReference<Thread> seen = new AtomicReference<>();
        assertEquals(2, run((e, opts) -> {
            name.set(Thread.currentThread().getName());
            seen.set(Thread.currentThread());
            return Verdict.DECLINE;
        }).exitCode);
        assertEquals("rlvm-checker", name.get());
        assertNotEquals(caller, seen.get());
    }

    @Test
    void configuredStackIsActuallyAvailable() {
        // A million frames need far more than the 8 MB of a main thread or the 2 MB default of a Java
        // thread; with the default 512 MB they fit. The same depth on a 1 MB stack overflows.
        final int depth = 1_000_000;
        AtomicLong reached = new AtomicLong();
        VerdictProtocol.Outcome deep = VerdictProtocol.run(checker((e, opts) -> {
            reached.set(count(depth));
            return Verdict.DECLINE;
        }), EXPORT, options(Options.DEFAULT_STACK_BYTES));
        assertEquals(ExitCode.DECLINE, deep.exitCode, String.valueOf(deep.failure));
        assertEquals(depth, reached.get());

        VerdictProtocol.Outcome shallow = VerdictProtocol.run(checker((e, opts) -> {
            count(depth);
            return Verdict.DECLINE;
        }), EXPORT, options(1 << 20));
        assertEquals(ExitCode.INTERNAL_ERROR, shallow.exitCode);
        assertTrue(shallow.failure instanceof StackOverflowError);
    }

    /** Recursion with a frame that the JIT cannot turn into a loop cheaply. */
    private static long count(int depth) {
        if (depth == 0) {
            return 0;
        }
        long below = count(depth - 1);
        return below + 1;
    }

    @Test
    void optionsAndExportReachTheChecker() {
        Options options = Options.builder().threads(7).stats(true).stackBytes(2 << 20).extra("k", "v").build();
        AtomicReference<Options> seenOptions = new AtomicReference<>();
        AtomicReference<Path> seenExport = new AtomicReference<>();
        VerdictProtocol.run(checker((e, opts) -> {
            seenExport.set(e);
            seenOptions.set(opts);
            return Verdict.DECLINE;
        }), EXPORT, options);
        assertSame(options, seenOptions.get());
        assertSame(EXPORT, seenExport.get());
        assertEquals(7, options.threads());
        assertEquals(7, options.effectiveThreads());
        assertEquals("v", options.extra("k", "fallback"));
        assertEquals("fallback", options.extra("missing", "fallback"));
    }

    @Test
    void autoThreadsAreBounded() {
        Options auto = Options.builder().build();
        assertEquals(Options.AUTO_THREADS, auto.threads());
        int n = auto.effectiveThreads();
        assertTrue(n >= 1 && n <= Options.MAX_AUTO_THREADS, "effective threads " + n);
        assertEquals(Options.DEFAULT_STACK_BYTES, auto.stackBytes());
    }
}
