package rlvm.cli;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The {@code rlvm} command.
 *
 * <pre>
 * rlvm [options] [EXPORT.ndjson]        the export may also be given in the environment variable IN
 * </pre>
 *
 * Exit status: 0 accept, 1 reject, 2 decline, 3 internal error, 64 usage error (see {@link ExitCode}).
 * The status of a check is produced by {@link VerdictProtocol}; {@link #main} adds the outermost guard:
 * its status variable starts as {@link ExitCode#INTERNAL_ERROR} and the process is always ended with an
 * explicit status, never by letting a throwable leave {@code main} (which would exit with 1 = reject).
 */
public final class Main {
    /** Environment variable through which the arena passes the export. */
    static final String ENV_IN = "IN";
    static final String ENV_THREADS = "RLVM_THREADS";
    static final String ENV_STACK_SIZE = "RLVM_STACK_SIZE";
    /** {@code exit} ends the process with {@link System#exit} (runs shutdown hooks); the default is {@link Runtime#halt}. */
    static final String ENV_EXIT = "RLVM_EXIT";
    /**
     * A number added to every exit status this program produces itself. The runtime underneath exits
     * with status 1, the arena's "rejected", when it fails before {@code main} runs (bad VM option, heap
     * that cannot be reserved, missing class path). With {@code RLVM_STATUS_BASE=10} a verdict leaves as
     * 10, 11 or 12, and a caller that maps exactly those back to 0, 1, 2 and everything else to 3 can no
     * longer mistake a runtime failure for a rejection. The arena run line does this.
     */
    static final String ENV_STATUS_BASE = "RLVM_STATUS_BASE";
    private static final int MAX_STATUS_BASE = 100;

    private static final int MAX_THREADS = 4096;
    private static final long MIN_STACK_BYTES = 64L << 10;
    private static final long MAX_STACK_BYTES = 64L << 30;

    private Main() {
    }

    public static void main(String[] args) {
        int code = ExitCode.INTERNAL_ERROR;
        int base = 0;
        boolean viaExit = false;
        try {
            Map<String, String> env = System.getenv();
            viaExit = "exit".equals(env.get(ENV_EXIT));
            // A malformed base throws: the status is then a plain 3, which no mapping reads as a verdict.
            base = statusBase(env.get(ENV_STATUS_BASE));
            code = run(args, env, System.out, System.err);
        } catch (Throwable t) {
            code = ExitCode.INTERNAL_ERROR;
            try {
                System.err.println("rlvm: internal error: " + t);
            } catch (Throwable ignored) {
                // Nothing more can be done; the status below is what matters.
            }
        } finally {
            end(base + code, viaExit);
        }
    }

    /** Parses {@link #ENV_STATUS_BASE}: unset or empty is 0; otherwise an integer in 0..100. */
    static int statusBase(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int base;
        try {
            base = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(ENV_STATUS_BASE + ": not a number: '" + text + "'");
        }
        if (base < 0 || base > MAX_STATUS_BASE) {
            throw new IllegalArgumentException(ENV_STATUS_BASE + ": out of range 0.." + MAX_STATUS_BASE + ": " + base);
        }
        return base;
    }

    /**
     * Ends the process. {@link Runtime#halt} skips shutdown hooks: nothing here needs them, they cost
     * instructions, and they are one more place to hang on an exhausted heap. A profiling
     * (PGO-instrumented) image writes its profile from a shutdown hook, hence {@code RLVM_EXIT=exit}.
     */
    private static void end(int code, boolean viaExit) {
        try {
            System.out.flush();
        } catch (Throwable ignored) {
            // Keep going: the status must still be delivered.
        }
        try {
            System.err.flush();
        } catch (Throwable ignored) {
            // As above.
        }
        if (viaExit) {
            try {
                System.exit(code);
            } catch (Throwable ignored) {
                // Fall through to halt.
            }
        }
        Runtime.getRuntime().halt(code);
    }

    /** What the command line asks for. */
    enum Mode {
        CHECK, VERSION, HELP, TRUFFLE_PROBE
    }

    /** A parsed command line. */
    static final class Invocation {
        Mode mode = Mode.CHECK;
        /** Null in the informational modes and with {@code --protocol-selftest}. */
        Path export;
        String checkerName;
        String selfTestMode;
        Options options;
    }

    /**
     * The whole command without ending the process; this is what the tests call.
     *
     * @return the exit status
     */
    static int run(String[] args, Map<String, String> env, PrintStream out, PrintStream err) {
        return run(args, env, out, err, null);
    }

    /**
     * As {@link #run(String[], Map, PrintStream, PrintStream)}, with the checker given instead of looked up
     * ({@code null}: look it up). For tests.
     */
    static int run(String[] args, Map<String, String> env, PrintStream out, PrintStream err, Checker injected) {
        Invocation inv;
        try {
            inv = parse(args, env, out, err);
        } catch (UsageException e) {
            err.println("rlvm: " + e.getMessage());
            err.println("rlvm: try 'rlvm --help'");
            return ExitCode.USAGE;
        }
        switch (inv.mode) {
            case HELP:
                out.print(HELP);
                out.print(checkerUsage(inv.checkerName, injected));
                return 0;
            case VERSION:
                return version(out, injected);
            case TRUFFLE_PROBE:
                // The only reference to Truffle in the launcher: nothing of it is touched on a normal run.
                return rlvm.cli.probe.TruffleProbe.run(out, inv.options.extra()) ? 0 : ExitCode.INTERNAL_ERROR;
            default:
                break;
        }

        Checker checker;
        if (inv.selfTestMode != null) {
            checker = new SelfTestChecker(inv.selfTestMode);
        } else if (injected != null) {
            checker = injected;
        } else {
            try {
                checker = Checkers.select(inv.checkerName);
            } catch (UsageException e) {
                err.println("rlvm: " + e.getMessage());
                return ExitCode.USAGE;
            }
        }

        long start = System.nanoTime();
        VerdictProtocol.Outcome outcome = VerdictProtocol.run(checker, inv.export, inv.options);
        long wallNanos = System.nanoTime() - start;
        VerdictProtocol.report(outcome, err);
        if (inv.options.stats()) {
            try {
                printStats(err, checker, inv.options, outcome, wallNanos);
            } catch (Throwable ignored) {
                // Statistics are best effort; the status is already decided.
            }
        }
        return outcome.exitCode;
    }

    /**
     * The part of {@code --help} that belongs to the checker which would run ({@code --checker NAME},
     * else the default one): its own {@code -X} settings ({@link Checker#usage()}). Empty if the
     * checker has none or cannot be found; {@code --help} never fails because of a checker.
     */
    static String checkerUsage(String checkerName, Checker injected) {
        try {
            Checker c = injected != null ? injected : Checkers.select(checkerName);
            String usage = c.usage();
            if (usage == null || usage.isBlank()) {
                return "";
            }
            return "Settings of the checker '" + c.name() + "' (-X KEY[=VALUE]):\n" + usage.stripTrailing() + "\n\n";
        } catch (UsageException | RuntimeException | java.util.ServiceConfigurationError e) {
            return "";
        }
    }

    private static int version(PrintStream out, Checker injected) {
        out.println("rlvm " + BuildInfo.VERSION);
        out.println("build: " + BuildInfo.build());
        out.println("runtime: " + BuildInfo.runtime());
        out.println("truffle: " + BuildInfo.TRUFFLE_VERSION + " (compiled against)");
        Checker dflt = injected;
        if (injected != null) {
            out.println("checker: " + injected.name() + " - " + injected.description());
        } else {
            boolean first = true;
            for (Checker c : Checkers.all()) {
                out.println((first ? "checker: " : "         ") + c.name() + " - " + c.description() + (first ? " (default)" : ""));
                if (first) {
                    dflt = c;
                }
                first = false;
            }
        }
        // What `rlvm FILE` does without any option, in the words of the checker that would run.
        try {
            String defaults = dflt == null ? null : dflt.defaults();
            if (defaults != null && !defaults.isBlank()) {
                out.println("default: " + defaults.strip());
            }
        } catch (RuntimeException | java.util.ServiceConfigurationError | LinkageError e) {
            // --version never fails because of a checker.
        }
        return 0;
    }

    private static void printStats(PrintStream err, Checker checker, Options options, VerdictProtocol.Outcome outcome, long wallNanos) {
        ProcStats p = ProcStats.read();
        Runtime rt = Runtime.getRuntime();
        StringBuilder sb = new StringBuilder("rlvm: stats:");
        sb.append(" result=").append(ExitCode.describe(outcome.exitCode).replace(' ', '-'));
        sb.append(" exit=").append(outcome.exitCode);
        sb.append(" checker=").append(checker.name());
        sb.append(" mode=").append(BuildInfo.mode());
        sb.append(" threads=").append(options.threads() == Options.AUTO_THREADS ? "auto" : Integer.toString(options.threads()));
        sb.append(" processors=").append(rt.availableProcessors());
        sb.append(" wall_ms=").append(wallNanos / 1_000_000);
        sb.append(" cpu_user_ms=").append(p.userMs);
        sb.append(" cpu_sys_ms=").append(p.sysMs);
        sb.append(" peak_rss_kb=").append(p.peakRssKb);
        sb.append(" heap_used_kb=").append((rt.totalMemory() - rt.freeMemory()) >> 10);
        sb.append(" heap_committed_kb=").append(rt.totalMemory() >> 10);
        sb.append(" heap_max_kb=").append(rt.maxMemory() >> 10);
        sb.append(" os_threads=").append(p.threads);
        err.println(sb);
    }

    // ---------------------------------------------------------------------------------------------
    // Command line

    static final String HELP = String.join("\n",
                    "Usage: rlvm [options] [EXPORT.ndjson]",
                    "",
                    "Checks a lean4export NDJSON file with the RLVM Lean 4 kernel. Without an EXPORT argument the",
                    "path is taken from the environment variable IN (the Lean Kernel Arena's convention).",
                    "A run without options is the fastest configuration: 'rlvm --version' says what it is.",
                    "",
                    "Options:",
                    "  -j N, --threads N     worker threads; 'auto' (default) lets the checker decide, up to 8",
                    "                        (environment: RLVM_THREADS)",
                    "      --stats           print statistics to standard error when done",
                    "      --stack-size S    stack of the checker thread, e.g. 512m (default), 2g (environment:",
                    "                        RLVM_STACK_SIZE)",
                    "      --checker NAME    use this checker implementation instead of the default (see --version)",
                    "  -X KEY[=VALUE]        setting passed through to the checker implementation (the settings of",
                    "                        the checker that would run are listed at the end of this text)",
                    "  -q, --quiet           print errors only",
                    "  -V, --version         print version, build, runtime, the checkers and what a run without",
                    "                        options does",
                    "  -h, --help            print this text",
                    "",
                    "Memory (the executable's own run-time options, written without a blank; defaults are baked",
                    "in at build time and shown by --version):",
                    "  -Xmx<size>            maximum heap, e.g. -Xmx8g",
                    "  -Xms<size>, -Xmn<size>  minimum heap, maximum young generation",
                    "",
                    "Diagnostics:",
                    "      --truffle-probe   verify that Truffle runtime compilation works in this build; -X options",
                    "                        become polyglot engine options, e.g. -X engine.CompilerThreads=1",
                    "      --protocol-selftest MODE",
                    "                        run a fake checker to test the exit-status protocol; MODE is one of",
                    "                        " + String.join(", ", SelfTestChecker.MODES),
                    "",
                    "Exit status: 0 accept, 1 reject, 2 decline, 3 internal error, 64 usage error (a bad option,",
                    "a missing export, or a -X setting the checker does not know).",
                    "A crash, an out-of-memory error or a stack overflow is never reported as 0.",
                    "",
                    "Environment: IN, RLVM_THREADS, RLVM_STACK_SIZE as above; RLVM_STATUS_BASE=N adds N to every exit",
                    "status (the arena run line uses 10 and maps 10/11/12 back, so that a runtime that fails to",
                    "start, which exits with 1, is not read as a rejection); RLVM_EXIT=exit runs shutdown hooks.",
                    "");

    static Invocation parse(String[] args, Map<String, String> env, PrintStream out, PrintStream err) throws UsageException {
        Invocation inv = new Invocation();
        Options.Builder options = Options.builder().out(out).err(err);
        Map<String, String> extra = new LinkedHashMap<>();
        String positional = null;
        String threads = env.get(ENV_THREADS);
        String threadsSource = ENV_THREADS;
        String stack = env.get(ENV_STACK_SIZE);
        String stackSource = ENV_STACK_SIZE;
        boolean informational = false;
        boolean optionsEnded = false;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (optionsEnded || !a.startsWith("-") || a.equals("-")) {
                if (positional != null) {
                    throw new UsageException("more than one export given: '" + positional + "' and '" + a + "'");
                }
                positional = a;
                continue;
            }
            String value = null;
            int eq = a.startsWith("--") ? a.indexOf('=') : -1;
            if (eq > 0) {
                value = a.substring(eq + 1);
                a = a.substring(0, eq);
            } else if (a.startsWith("-j") && a.length() > 2 && !a.startsWith("--")) {
                value = a.substring(2);
                a = "-j";
            } else if (a.startsWith("-X") && a.length() > 2) {
                value = a.substring(2);
                a = "-X";
            }
            switch (a) {
                case "--":
                    noValue(a, value);
                    optionsEnded = true;
                    break;
                case "-j":
                case "--threads":
                    threads = value != null ? value : next(args, ++i, a);
                    threadsSource = a;
                    break;
                case "--stack-size":
                    stack = value != null ? value : next(args, ++i, a);
                    stackSource = a;
                    break;
                case "--checker":
                    inv.checkerName = value != null ? value : next(args, ++i, a);
                    break;
                case "-X": {
                    String kv = value != null ? value : next(args, ++i, a);
                    int k = kv.indexOf('=');
                    String key = k < 0 ? kv : kv.substring(0, k);
                    if (key.isEmpty()) {
                        throw new UsageException("-X needs KEY[=VALUE]");
                    }
                    if (looksLikeVmOption(kv)) {
                        // The executable (a native image) consumes -Xmx/-Xms/-Xmn/-Xss/-XX: itself before
                        // main runs, and the development JVM launcher forwards them to java: one of them
                        // arriving here was written with a blank after -X, or went to a bare java command.
                        throw new UsageException("'-X" + kv + "' is a VM option, not a checker setting: write it without a blank "
                                        + "(-X" + kv + "), or put it before the main class on a java command line");
                    }
                    extra.put(key, k < 0 ? "true" : kv.substring(k + 1));
                    break;
                }
                case "--stats":
                    noValue(a, value);
                    options.stats(true);
                    break;
                case "-q":
                case "--quiet":
                    noValue(a, value);
                    options.quiet(true);
                    break;
                case "-V":
                case "--version":
                    noValue(a, value);
                    inv.mode = Mode.VERSION;
                    informational = true;
                    break;
                case "-h":
                case "--help":
                    noValue(a, value);
                    inv.mode = Mode.HELP;
                    informational = true;
                    break;
                case "--truffle-probe":
                    noValue(a, value);
                    inv.mode = Mode.TRUFFLE_PROBE;
                    informational = true;
                    break;
                case "--protocol-selftest":
                    inv.selfTestMode = value != null ? value : next(args, ++i, a);
                    if (!SelfTestChecker.isMode(inv.selfTestMode)) {
                        throw new UsageException("unknown --protocol-selftest mode '" + inv.selfTestMode + "' (one of: "
                                        + String.join(", ", SelfTestChecker.MODES) + ")");
                    }
                    break;
                default:
                    throw new UsageException("unknown option '" + a + "'");
            }
        }

        if (threads != null && !threads.isEmpty()) {
            options.threads(parseThreads(threads, threadsSource));
        }
        if (stack != null && !stack.isEmpty()) {
            options.stackBytes(parseSize(stack, stackSource));
        }
        for (Map.Entry<String, String> e : extra.entrySet()) {
            options.extra(e.getKey(), e.getValue());
        }
        inv.options = options.build();

        if (informational || inv.selfTestMode != null) {
            // These modes answer 0 without checking anything. Refuse to combine them with an export, so
            // that a mistyped arena run command cannot turn every test into an acceptance.
            if (positional != null) {
                throw new UsageException("an export cannot be combined with --version, --help, --truffle-probe or --protocol-selftest");
            }
            if (informational && inv.selfTestMode != null) {
                throw new UsageException("--protocol-selftest cannot be combined with --version, --help or --truffle-probe");
            }
            return inv;
        }

        String path = positional != null ? positional : env.get(ENV_IN);
        if (path == null || path.isEmpty()) {
            throw new UsageException("no export given: pass EXPORT.ndjson or set the environment variable IN");
        }
        Path export;
        try {
            export = Path.of(path);
        } catch (RuntimeException e) {
            throw new UsageException("not a valid path: '" + path + "'");
        }
        if (!Files.isRegularFile(export)) {
            throw new UsageException("export not found or not a regular file: '" + path + "'");
        }
        if (!Files.isReadable(export)) {
            throw new UsageException("export is not readable: '" + path + "'");
        }
        inv.export = export;
        return inv;
    }

    /** {@code mx512m}, {@code ms1g}, {@code mn256m}, {@code ss4m}, {@code X:+Flag}: the tail of a JVM option. */
    static boolean looksLikeVmOption(String afterX) {
        if (afterX.startsWith("X:")) {
            return true;
        }
        if (afterX.length() < 3) {
            return false;
        }
        String head = afterX.substring(0, 2);
        if (!(head.equals("mx") || head.equals("ms") || head.equals("mn") || head.equals("ss"))) {
            return false;
        }
        return Character.isDigit(afterX.charAt(2));
    }

    private static String next(String[] args, int i, String option) throws UsageException {
        if (i >= args.length) {
            throw new UsageException("option '" + option + "' needs a value");
        }
        return args[i];
    }

    private static void noValue(String option, String value) throws UsageException {
        if (value != null) {
            throw new UsageException("option '" + option + "' takes no value");
        }
    }

    static int parseThreads(String text, String source) throws UsageException {
        if (text.equalsIgnoreCase("auto")) {
            return Options.AUTO_THREADS;
        }
        int n;
        try {
            n = Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new UsageException(source + ": not a thread count: '" + text + "'");
        }
        if (n < 0 || n > MAX_THREADS) {
            throw new UsageException(source + ": thread count out of range (0 = auto, 1.." + MAX_THREADS + "): " + n);
        }
        return n;
    }

    /** Bytes from text such as {@code 1048576}, {@code 512k}, {@code 512m}, {@code 2g}. */
    static long parseSize(String text, String source) throws UsageException {
        String t = text.trim().toLowerCase(Locale.ROOT);
        long unit = 1;
        if (t.endsWith("b") && t.length() > 1 && !Character.isDigit(t.charAt(t.length() - 2))) {
            t = t.substring(0, t.length() - 1);
        }
        if (t.endsWith("k")) {
            unit = 1L << 10;
        } else if (t.endsWith("m")) {
            unit = 1L << 20;
        } else if (t.endsWith("g")) {
            unit = 1L << 30;
        }
        if (unit != 1) {
            t = t.substring(0, t.length() - 1);
        }
        long n;
        try {
            n = Long.parseLong(t);
        } catch (NumberFormatException e) {
            throw new UsageException(source + ": not a size: '" + text + "' (examples: 512m, 2g)");
        }
        if (n <= 0 || n > MAX_STACK_BYTES / unit) {
            throw new UsageException(source + ": size out of range: '" + text + "'");
        }
        long bytes = n * unit;
        if (bytes < MIN_STACK_BYTES) {
            throw new UsageException(source + ": size too small (minimum 64k): '" + text + "'");
        }
        return bytes;
    }
}
