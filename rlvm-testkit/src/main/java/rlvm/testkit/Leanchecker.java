package rlvm.testkit;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The ground truth: the official checker of the pinned toolchain,
 * {@code ~/.elan/toolchains/leanprover--lean4---v4.36.0/bin/leanchecker --from-export FILE}
 * (exit 0 accepted, exit 1 rejected). Results are cached by the file's SHA-256 (one small JSON file
 * per input under the cache directory, default {@code work/oracle/cache/}) because a run on a large
 * export takes minutes and mutant campaigns ask about the same bytes again and again.
 *
 * <p>What counts as a verdict. The official checker exits with 1 when its kernel rejects a
 * declaration, when its reader throws (malformed record, undefined id, duplicate declaration) and
 * when its quotient post-check fails after the kernel accepted; all three are official rejections. It ALSO exits with 1 when it cannot read the file or its own toolchain is
 * broken; those are failures of the tool, reported as {@link Verdict#ERROR} and never cached, so that
 * an environment problem can never be recorded as "the official kernel rejects".
 *
 * <p>Thread-safe: {@link #check(Path, Duration)} may be called from many threads at once; cache
 * files are written atomically (temporary file + rename).
 */
public final class Leanchecker {

    /** The version of the pinned toolchain; part of every cache entry. */
    public static final String PINNED_VERSION = "4.36.0";

    /** How one official run ended. */
    public enum Verdict {
        /** exit 0: "Lean default kernel accepts the solution". */
        ACCEPT,
        /** exit 1 from the kernel or from the export reader. */
        REJECT,
        /** The run did not finish within the timeout; no verdict. */
        TIMEOUT,
        /** Anything else: a signal, an unexpected exit status, a tool failure; no verdict. */
        ERROR;

        public String lower() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** Verdict of one official run. */
    public static final class Result {
        /** 0 accepted, 1 rejected, -1 timeout, other values = the process exit status (128 + signal for a signal). */
        public final int exitCode;
        /**
         * The checker's message, on one line: for a kernel rejection the text after
         * "Lean default kernel rejects the solution: " (declaration and kernel error); for a reader
         * rejection the "uncaught exception: ..." line; empty on accept.
         */
        public final String message;
        /** ACCEPT / REJECT / TIMEOUT / ERROR. */
        public final Verdict verdict;
        /**
         * For REJECT: "kernel" (a declaration failed to check), "reader" (the export was refused),
         * "post-check" (a quot record differs from the kernel's own quotient constants) or "resource"
         * (the kernel gave up on a resource limit, e.g. "deep recursion detected"); else "".
         */
        public final String stage;
        /** Head of the raw output (stdout then stderr), at most {@link #OUTPUT_KEEP} characters. */
        public final String output;
        /** Wall-clock seconds of the run that produced this result (also for cached results). */
        public final double wallSeconds;
        /** User + system CPU seconds as reported by GNU time, or -1 when GNU time is not available. */
        public final double cpuSeconds;
        /** Peak resident set in KiB as reported by GNU time, or -1. */
        public final long maxRssKb;
        /** True when the result came from the cache. */
        public final boolean cached;
        /** SHA-256 of the checked file (lower-case hex). */
        public final String sha256;

        public Result(int exitCode, String message) {
            this(exitCode, message, verdictOf(exitCode, message), "", message, 0.0, -1.0, -1, false, "");
        }

        Result(int exitCode, String message, Verdict verdict, String stage, String output, double wallSeconds,
                double cpuSeconds, long maxRssKb, boolean cached, String sha256) {
            this.exitCode = exitCode;
            this.message = message;
            this.verdict = verdict;
            this.stage = stage;
            this.output = output;
            this.wallSeconds = wallSeconds;
            this.cpuSeconds = cpuSeconds;
            this.maxRssKb = maxRssKb;
            this.cached = cached;
            this.sha256 = sha256;
        }

        public boolean accepted() {
            return verdict == Verdict.ACCEPT;
        }

        public boolean rejected() {
            return verdict == Verdict.REJECT;
        }

        /** True for ACCEPT and REJECT: the official checker gave an answer. */
        public boolean decided() {
            return verdict == Verdict.ACCEPT || verdict == Verdict.REJECT;
        }

        Json toJson() {
            Json o = Json.object();
            o.put("sha256", sha256);
            o.put("verdict", verdict.lower());
            o.put("exit_code", exitCode);
            o.put("stage", stage);
            o.put("message", message);
            o.put("output", output);
            o.put("wall_s", round3(wallSeconds));
            o.put("cpu_s", round3(cpuSeconds));
            o.put("max_rss_kb", maxRssKb);
            return o;
        }

        static Result fromJson(Json o) {
            Verdict v = Verdict.valueOf(o.getText("verdict", "error").toUpperCase(java.util.Locale.ROOT));
            return new Result((int) o.getDouble("exit_code", 3), o.getText("message", ""), v, o.getText("stage", ""),
                    o.getText("output", ""), o.getDouble("wall_s", 0), o.getDouble("cpu_s", -1),
                    (long) o.getDouble("max_rss_kb", -1), true, o.getText("sha256", ""));
        }

        @Override
        public String toString() {
            return verdict.lower() + " (exit " + exitCode + ")" + (message.isEmpty() ? "" : ": " + message);
        }
    }

    /** Characters of raw output kept in a result. */
    public static final int OUTPUT_KEEP = 4096;
    /** Characters of the one-line message kept in a result. */
    public static final int MESSAGE_KEEP = 600;

    private static final String ACCEPT_LINE = "Lean default kernel accepts the solution";
    private static final String REJECT_PREFIX = "Lean default kernel rejects the solution";
    private static final String POSTCHECK_PREFIX = "Quotient post-check rejects the solution";
    private static final String READER_PREFIX = "uncaught exception: ";
    /** Bumped whenever the classification of raw results changes; older cache entries are ignored. */
    private static final int CACHE_VERSION = 2;

    private final Path toolchainHome;
    private final Path binary;
    private final Path cacheDir;
    private final long memoryLimitBytes;
    private final boolean useGnuTime;
    private final boolean usePrlimit;

    public Leanchecker(Path toolchainHome, Path cacheDir) {
        this(toolchainHome, cacheDir, 0L);
    }

    private Leanchecker(Path toolchainHome, Path cacheDir, long memoryLimitBytes) {
        this.toolchainHome = toolchainHome.toAbsolutePath().normalize();
        this.binary = this.toolchainHome.resolve("bin").resolve("leanchecker");
        this.cacheDir = cacheDir == null ? null : cacheDir.toAbsolutePath().normalize();
        this.memoryLimitBytes = memoryLimitBytes;
        this.useGnuTime = Files.isExecutable(Paths.get("/usr/bin/time"));
        this.usePrlimit = Files.isExecutable(Paths.get("/usr/bin/prlimit"));
    }

    /**
     * Locates the toolchain from {@code RLVM_LEAN_TOOLCHAIN} or the default elan path. The cache is
     * {@code RLVM_ORACLE_CACHE} if set, else {@code work/oracle/cache} below the project root (found
     * from {@code RLVM_ROOT} or by walking up from the working directory to the directory holding
     * {@code env.sh}); without a project root nothing is cached.
     */
    public static Leanchecker locate() {
        String tc = System.getenv("RLVM_LEAN_TOOLCHAIN");
        Path home = tc != null && !tc.isEmpty()
                ? Paths.get(tc)
                : Paths.get(System.getProperty("user.home"), ".elan", "toolchains", "leanprover--lean4---v" + PINNED_VERSION);
        String cache = System.getenv("RLVM_ORACLE_CACHE");
        Path cacheDir = null;
        if (cache != null && !cache.isEmpty()) {
            cacheDir = Paths.get(cache);
        } else {
            Path root = projectRoot();
            if (root != null) {
                cacheDir = root.resolve("work").resolve("oracle").resolve("cache");
            }
        }
        return new Leanchecker(home, cacheDir);
    }

    /** The project root: {@code RLVM_ROOT}, or the nearest ancestor of the working directory with {@code env.sh} and {@code pom.xml}; null if none. */
    public static Path projectRoot() {
        String env = System.getenv("RLVM_ROOT");
        if (env != null && !env.isEmpty() && Files.isDirectory(Paths.get(env))) {
            return Paths.get(env).toAbsolutePath().normalize();
        }
        Path p = Paths.get("").toAbsolutePath();
        while (p != null) {
            if (Files.isRegularFile(p.resolve("env.sh")) && Files.isRegularFile(p.resolve("pom.xml"))) {
                return p;
            }
            p = p.getParent();
        }
        return null;
    }

    /**
     * A checker that caps the address space of every run (through {@code prlimit --as}); a run that
     * hits the cap dies and is reported as ERROR. 0 = no cap. Use it for mutants, whose resource use
     * is unknown. Results obtained under a cap are cached like any other (a capped ERROR is cached
     * with its cap and re-run when a larger cap or none is requested).
     */
    public Leanchecker withMemoryLimit(long bytes) {
        return new Leanchecker(toolchainHome, cacheDir, bytes);
    }

    public boolean available() {
        return Files.isExecutable(binary);
    }

    public Path binary() {
        return binary;
    }

    public Path cacheDir() {
        return cacheDir;
    }

    /** Runs (or looks up) the official verdict for {@code export}. Never throws for a property of the input. */
    public Result check(Path export, Duration timeout) {
        Path file = export.toAbsolutePath().normalize();
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            return new Result(66, "not a readable file: " + file, Verdict.ERROR, "", "", 0, -1, -1, false, "");
        }
        if (!available()) {
            return new Result(70, "official checker not found: " + binary, Verdict.ERROR, "", "", 0, -1, -1, false, "");
        }
        String sha;
        try {
            sha = sha256(file);
        } catch (IOException e) {
            return new Result(66, "cannot read " + file + ": " + e.getMessage(), Verdict.ERROR, "", "", 0, -1, -1, false, "");
        }
        long timeoutMs = timeout == null ? 0 : timeout.toMillis();
        Result hit = cacheLookup(sha, timeoutMs);
        if (hit != null) {
            return hit;
        }
        Result r = run(file, sha, timeoutMs);
        cacheStore(r, timeoutMs);
        return r;
    }

    /** SHA-256 of a file as lower-case hex. */
    public static String sha256(Path file) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] buf = new byte[1 << 16];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return hex(md.digest());
    }

    public static String sha256(byte[] bytes) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        }
        return sb.toString();
    }

    // ---- running -------------------------------------------------------------------------------------

    private Result run(Path file, String sha, long timeoutMs) {
        Path out = null;
        Path err = null;
        Path timing = null;
        Process process = null;
        try {
            out = Files.createTempFile("leanchecker-", ".out");
            err = Files.createTempFile("leanchecker-", ".err");
            List<String> cmd = new ArrayList<>();
            if (useGnuTime) {
                timing = Files.createTempFile("leanchecker-", ".time");
                cmd.add("/usr/bin/time");
                cmd.add("-f");
                cmd.add("rlvm-time %e %U %S %M");
                cmd.add("-o");
                cmd.add(timing.toString());
                cmd.add("--");
            }
            if (memoryLimitBytes > 0 && usePrlimit) {
                cmd.add("/usr/bin/prlimit");
                cmd.add("--as=" + memoryLimitBytes);
                cmd.add("--");
            }
            cmd.add(binary.toString());
            cmd.add("--from-export");
            cmd.add(file.toString());
            ProcessBuilder pb = new ProcessBuilder(cmd);
            Map<String, String> env = pb.environment();
            env.clear();
            env.put("LC_ALL", "C");
            env.put("LEAN_SYSROOT", toolchainHome.toString());
            env.put("PATH", toolchainHome.resolve("bin") + ":/usr/bin:/bin");
            env.put("HOME", System.getProperty("user.home"));
            pb.redirectInput(ProcessBuilder.Redirect.from(Paths.get("/dev/null").toFile()));
            pb.redirectOutput(out.toFile());
            pb.redirectError(err.toFile());
            long t0 = System.nanoTime();
            process = pb.start();
            boolean finished;
            if (timeoutMs > 0) {
                finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            } else {
                process.waitFor();
                finished = true;
            }
            if (!finished) {
                killTree(process);
                process.waitFor(10, TimeUnit.SECONDS);
            }
            double wall = (System.nanoTime() - t0) / 1e9;
            String stdout = head(out, OUTPUT_KEEP);
            String stderr = head(err, OUTPUT_KEEP);
            double cpu = -1;
            long rss = -1;
            boolean signalled = false;
            if (timing != null && finished) {
                for (String line : head(timing, 2048).split("\n")) {
                    if (line.startsWith("rlvm-time ")) {
                        String[] f = line.trim().split(" ");
                        if (f.length == 5) {
                            try {
                                cpu = Double.parseDouble(f[2]) + Double.parseDouble(f[3]);
                                rss = Long.parseLong(f[4]);
                            } catch (NumberFormatException ignored) {
                                // leave unknown
                            }
                        }
                    } else if (line.startsWith("Command terminated by signal")) {
                        signalled = true;
                    }
                }
            }
            if (!finished) {
                return new Result(-1, "timeout after " + (timeoutMs / 1000.0) + " s", Verdict.TIMEOUT, "",
                        bound(stdout + stderr, OUTPUT_KEEP), wall, cpu, rss, false, sha);
            }
            int exit = process.exitValue();
            return classify(exit, signalled, stdout, stderr, wall, cpu, rss, sha);
        } catch (IOException e) {
            return new Result(70, "cannot run the official checker: " + e.getMessage(), Verdict.ERROR, "", "", 0, -1, -1,
                    false, sha);
        } catch (InterruptedException e) {
            if (process != null) {
                killTree(process);
            }
            Thread.currentThread().interrupt();
            return new Result(70, "interrupted", Verdict.ERROR, "", "", 0, -1, -1, false, sha);
        } finally {
            deleteQuietly(out);
            deleteQuietly(err);
            deleteQuietly(timing);
        }
    }

    /** Turns exit status and output into a result; package-private for the unit tests. */
    static Result classify(int exit, boolean signalled, String stdout, String stderr, double wall, double cpu, long rss,
            String sha) {
        String output = bound(stdout.isEmpty() ? stderr : stderr.isEmpty() ? stdout : stdout + "\n" + stderr, OUTPUT_KEEP);
        if (!signalled && exit == 0 && stdout.contains(ACCEPT_LINE)) {
            return new Result(0, "", Verdict.ACCEPT, "", output, wall, cpu, rss, false, sha);
        }
        if (!signalled && exit == 1) {
            int k = stdout.indexOf(REJECT_PREFIX);
            if (k >= 0) {
                String rest = stdout.substring(k + REJECT_PREFIX.length());
                if (rest.startsWith(":")) {
                    rest = rest.substring(1);
                }
                String msg = oneLine(rest);
                return new Result(1, msg, Verdict.REJECT, isResourceLimit(msg) ? "resource" : "kernel", output, wall, cpu, rss,
                        false, sha);
            }
            int q = stdout.indexOf(POSTCHECK_PREFIX);
            if (q >= 0) {
                // The kernel accepted every declaration, but a quot record of the export differs from the
                // kernel's own Quot / Quot.mk / Quot.lift / Quot.ind.
                return new Result(1, oneLine(stdout.substring(q)), Verdict.REJECT, "post-check", output, wall, cpu, rss, false,
                        sha);
            }
            int r = stderr.indexOf(READER_PREFIX);
            if (r >= 0) {
                String msg = oneLine(stderr.substring(r));
                // Failures of the tool itself look like reader exceptions; they are not verdicts.
                if (isToolFailure(msg)) {
                    return new Result(exit, msg, Verdict.ERROR, "", output, wall, cpu, rss, false, sha);
                }
                return new Result(1, msg, Verdict.REJECT, "reader", output, wall, cpu, rss, false, sha);
            }
        }
        String why = signalled ? "terminated by a signal" : "unexpected exit status " + exit;
        return new Result(exit, oneLine(why + (output.isEmpty() ? "" : ": " + output)), Verdict.ERROR, "", output, wall,
                cpu, rss, false, sha);
    }

    /**
     * Kernel errors that say "I ran out of a resource", not "this is ill-typed". The exit status is 1 like
     * any rejection, but another checker with other limits may rightly accept, so such results get the
     * stage "resource" and the corpus treats them as undecided.
     */
    static boolean isResourceLimit(String message) {
        return message.contains("deep recursion detected") || message.contains("(deterministic) timeout")
                || message.contains("maximum recursion depth") || message.contains("excessive memory consumption")
                || message.contains("stack overflow");
    }

    private static boolean isToolFailure(String message) {
        return message.contains("no such file or directory") || message.contains("failed to read file")
                || message.contains("incompatible header") || message.contains("permission denied")
                || message.contains("out of memory") || message.contains("too many open files");
    }

    private static Verdict verdictOf(int exit, String message) {
        if (exit == 0) {
            return Verdict.ACCEPT;
        }
        if (exit == 1) {
            return Verdict.REJECT;
        }
        return exit == -1 ? Verdict.TIMEOUT : Verdict.ERROR;
    }

    private static void killTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static String head(Path file, int maxChars) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = in.readNBytes(maxChars * 4);
            String s = new String(buf, StandardCharsets.UTF_8);
            return s.length() > maxChars ? s.substring(0, maxChars) : s;
        } catch (IOException e) {
            return "";
        }
    }

    private static String bound(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    static String oneLine(String s) {
        StringBuilder sb = new StringBuilder();
        boolean space = false;
        for (int i = 0; i < s.length() && sb.length() < MESSAGE_KEEP; i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t' || c == ' ') {
                space = sb.length() > 0;
            } else {
                if (space) {
                    sb.append(' ');
                    space = false;
                }
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static void deleteQuietly(Path p) {
        if (p != null) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
                // a leftover temporary file is harmless
            }
        }
    }

    static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    // ---- cache ---------------------------------------------------------------------------------------

    private Path cacheFile(String sha) {
        return cacheDir.resolve(sha.substring(0, 2)).resolve(sha + ".json");
    }

    private Result cacheLookup(String sha, long timeoutMs) {
        if (cacheDir == null) {
            return null;
        }
        Path f = cacheFile(sha);
        if (!Files.isRegularFile(f)) {
            return null;
        }
        try {
            Json o = Json.parse(new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
            if (!PINNED_VERSION.equals(o.getText("toolchain", "")) || !sha.equals(o.getText("sha256", ""))
                    || o.getLong("cache_version", 0) != CACHE_VERSION) {
                return null;
            }
            Result r = Result.fromJson(o);
            long cachedTimeout = (long) o.getDouble("timeout_ms", 0);
            long cachedLimit = (long) o.getDouble("memory_limit_bytes", 0);
            if (r.verdict == Verdict.TIMEOUT) {
                // A timeout answers only requests that would not wait longer.
                if (timeoutMs == 0 || (cachedTimeout != 0 && timeoutMs > cachedTimeout)) {
                    return null;
                }
            }
            if (r.verdict == Verdict.ERROR) {
                // An error under a memory cap answers only requests with a cap that is not larger.
                if (cachedLimit != 0 && (memoryLimitBytes == 0 || memoryLimitBytes > cachedLimit)) {
                    return null;
                }
            }
            return r;
        } catch (IOException | RuntimeException e) {
            return null; // unreadable entry: run again
        }
    }

    private void cacheStore(Result r, long timeoutMs) {
        if (cacheDir == null || r.sha256.isEmpty()) {
            return;
        }
        // Tool failures (exit 66 / 70 are ours) are not facts about the input.
        if (r.verdict == Verdict.ERROR && (r.exitCode == 66 || r.exitCode == 70 || isToolFailure(r.message))) {
            return;
        }
        try {
            Path f = cacheFile(r.sha256);
            Files.createDirectories(f.getParent());
            Json o = r.toJson();
            o.put("toolchain", PINNED_VERSION);
            o.put("cache_version", CACHE_VERSION);
            o.put("timeout_ms", timeoutMs);
            o.put("memory_limit_bytes", memoryLimitBytes);
            o.put("checked_utc", Instant.now().toString());
            Path tmp = Files.createTempFile(f.getParent(), r.sha256, ".tmp");
            Files.write(tmp, (Json.write(o) + "\n").getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // the cache is an optimisation
        }
    }
}
