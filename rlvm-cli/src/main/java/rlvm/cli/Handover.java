package rlvm.cli;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * The hybrid single entry (*agentic-note*: rep-OPT-K): the executable checks an export itself or hands the
 * run over to a JVM, when a JDK 25 or newer is found, by replacing its own process ({@code execv}) with
 * {@code java} on the jars it was built from. Those jars are part of the executable ({@link Jars}) and are
 * unpacked into a per-user cache directory, where every one of them is compared with the copy inside the
 * executable on every start.
 *
 * <p>Three rules shape the code:
 * <ol>
 * <li><b>Nothing here can fail a check.</b> Whatever goes wrong before the JVM is started - no JDK, a JDK
 * that is too old or that does not start, a cache directory that cannot be written or is not safe, an
 * {@code execv} that returns - leaves the process as it was, and the export is checked in-process. The
 * reason is said with {@code --stats} and {@code --version}, never otherwise.</li>
 * <li><b>A run that is not handed over does nothing of this.</b> The rule ({@link #decide}) reads the
 * command line and the size of the export only; the JDK is searched and the cache touched only when the
 * rule has said "hand over".</li>
 * <li><b>Both sides compute the same.</b> The JVM runs {@code rlvm.cli.Main} of the same jars with the same
 * arguments and the same environment (so the exit status protocol, {@code RLVM_STATUS_BASE} included, is
 * the JVM side's own), and a run that names what the embedded jars do not have (the Truffle tier) stays in
 * the executable.</li>
 * </ol>
 *
 * <p>This class is initialised at image build time like the rest of the package: {@link Jars#EMBEDDED} is
 * read from the class path of the image BUILD, where {@code scripts/build.sh} puts the jars and their
 * index, and is a constant of the image. On a JVM it is null: jars on a JVM hand nothing over.
 */
final class Handover {
    private Handover() {
    }

    /** {@code --platform} / {@code RLVM_PLATFORM}. */
    enum Platform {
        /** The rule decides ({@link #decide}). */
        AUTO,
        /** Always the executable's own engine. */
        IMAGE,
        /** A JVM whenever one is usable. */
        JVM;

        /**
         * What a run does when nothing is said: the executable's own engine. The hand-over is an opt-in
         * ({@code --platform auto|jvm}, {@code RLVM_PLATFORM}): measured 2026-11-25, one cold run of a
         * corpus gains nothing from it, and with {@code auto} as the default a small run would pay for
         * the jars inside the executable and gain nothing (*agentic-note*: rep-OPT-K, section 6).
         */
        static final Platform DEFAULT = IMAGE;

        static Platform parse(String text, String source) throws UsageException {
            switch (text == null ? "" : text) {
                case "auto":
                    return AUTO;
                case "image":
                    return IMAGE;
                case "jvm":
                    return JVM;
                default:
                    throw new UsageException(source + ": expected image, jvm or auto: '" + text + "'");
            }
        }
    }

    static final String ENV_PLATFORM = "RLVM_PLATFORM";
    /** The java binary to hand over to; when set it is the only candidate. */
    static final String ENV_JAVA = "RLVM_JAVA";
    /** Replaces the VM flags of the hand-over, the heap included (as for the development launcher). */
    static final String ENV_JAVA_OPTS = "RLVM_JAVA_OPTS";
    /** The cache directory's parent; default {@code $XDG_CACHE_HOME/rlvm}, else {@code $HOME/.cache/rlvm}. */
    static final String ENV_CACHE_DIR = "RLVM_CACHE_DIR";
    /** System property of the JVM side: how this run got there (for {@code --stats}). */
    static final String PROPERTY = "rlvm.handover";
    /** The oldest JDK the jars run on (their class files are of release 25). */
    static final int MIN_JAVA = 25;
    /** The modules of the JDK the jars need ({@code release} file, {@code MODULES}). */
    private static final String[] NEEDED_MODULES = {"java.base", "java.management"};

    /**
     * THE RULE OF {@code auto}, by size: an export of at least this many bytes is handed over for a single
     * pass. {@link Long#MAX_VALUE} = never. Measured 2026-11-25 (*agentic-note*: rep-OPT-K): on the eight
     * corpora up to Mathlib (6.3 GB) one cold run of the executable is not slower than one cold run of the
     * JVM, so no size we can test gains from the hand-over.
     */
    static final long AUTO_MIN_BYTES = Long.MAX_VALUE;

    /** What the JVM needs beside its heap (code cache, metaspace, stacks, the mapped export's page tables). */
    private static final long HEADROOM_BYTES = 1L << 30;

    // ---------------------------------------------------------------------------------------------
    // The rule

    /** Whether a run is handed over, and the word that says why ({@code --stats}: {@code why=...}). */
    static final class Decision {
        final boolean handover;
        final String why;

        Decision(boolean handover, String why) {
            this.handover = handover;
            this.why = why;
        }
    }

    /** What the rule looks at; everything is known from the command line and one {@code stat} of the export. */
    static final class Request {
        Platform platform = Platform.DEFAULT;
        /** True inside the executable (a native image); false on a JVM. */
        boolean image;
        /** True if the executable carries its jars ({@link Jars#EMBEDDED}). */
        boolean embedded;
        /** Size of the export in bytes; 0 = unknown. */
        long bytes;
        /** {@code -X passes=N}; 1 when not given or not a number. */
        int passes = 1;
        /** The engine of the run as far as the command line says: {@code -X engine}, strict = reference, else the default engine. */
        String engine;
        /** {@code --checker NAME} was given. */
        boolean checkerNamed;
        /** The executable consumed VM options of its own ({@code -Xmx}, {@code -XX:}, {@code -D}) before {@code main}. */
        boolean vmOptions;
        /** A setting names the Truffle tier, which the embedded jars do not carry. */
        boolean truffle;
    }

    /**
     * THE RULE. {@code image}: never. {@code jvm}: always, except for what the JVM side cannot compute alike
     * (the Truffle tier). {@code auto}: the default engine's plain runs only - no named checker, no VM option
     * addressed to the executable, the machine as engine - and of those the runs with several passes
     * ({@code -X passes=N}, N above 1: the warm metric, which is the JVM's) and the exports of
     * {@link #AUTO_MIN_BYTES} and more.
     */
    static Decision decide(Request r) {
        if (!r.image) {
            return new Decision(false, "not-an-executable");
        }
        if (r.platform == Platform.IMAGE) {
            return new Decision(false, "forced");
        }
        if (!r.embedded) {
            return new Decision(false, "no-embedded-jars");
        }
        if (r.truffle) {
            return new Decision(false, "truffle-tier-is-in-the-executable-only");
        }
        if (r.platform == Platform.JVM) {
            return new Decision(true, "forced");
        }
        if (r.checkerNamed) {
            return new Decision(false, "auto:checker-named");
        }
        if (r.vmOptions) {
            return new Decision(false, "auto:vm-options-of-the-executable");
        }
        if (!"machine".equals(r.engine)) {
            return new Decision(false, "auto:engine-" + (r.engine == null ? "unknown" : r.engine));
        }
        if (r.passes > 1) {
            return new Decision(true, "auto:passes=" + r.passes);
        }
        if (r.bytes >= AUTO_MIN_BYTES) {
            return new Decision(true, "auto:size");
        }
        return new Decision(false, "auto:single-pass");
    }

    /** The rule of {@code auto} in words, for {@code --version} and {@code --help}. */
    static String ruleText() {
        return "-X passes=N with N above 1" + (AUTO_MIN_BYTES == Long.MAX_VALUE ? ""
                        : " and exports of " + (AUTO_MIN_BYTES >> 20) + " MiB or more")
                        + " (default engine, no VM option of the executable)";
    }

    // ---------------------------------------------------------------------------------------------
    // The jars inside the executable

    /** The jars the executable was built from, as it carries them, with what the JVM is started with. */
    static final class Jars {
        /** The id of the build: the sha256 prefix over the RLVM jars that {@code BUILD-INFO.txt} names. */
        final String id;
        /** File names in class path order. */
        final String[] names;
        final byte[][] bytes;
        /** Lower-case hexadecimal sha256 of every jar, as the build recorded it. */
        final String[] sha256;
        /** The VM flags of the hand-over, without the heap. */
        final String flags;
        final String heapMachine;
        final String heapOther;
        /** The engine of a run without {@code -X engine}. */
        final String defaultEngine;

        Jars(String id, String[] names, byte[][] bytes, String[] sha256, String flags, String heapMachine, String heapOther,
                        String defaultEngine) {
            this.id = id;
            this.names = names;
            this.bytes = bytes;
            this.sha256 = sha256;
            this.flags = flags;
            this.heapMachine = heapMachine;
            this.heapOther = heapOther;
            this.defaultEngine = defaultEngine;
        }

        long totalBytes() {
            long n = 0;
            for (byte[] b : bytes) {
                n += b.length;
            }
            return n;
        }

        /** Null on a JVM and in an image built without them. A constant of the image (build-time initialisation). */
        static final Jars EMBEDDED = load();

        /**
         * Reads {@code rlvm/handover/index.properties} and the jars it lists from the class path. A jar whose
         * sha256 is not the one of the index makes the whole set unusable: the build then has no hand-over.
         */
        private static Jars load() {
            try (InputStream in = Handover.class.getResourceAsStream("/rlvm/handover/index.properties")) {
                if (in == null) {
                    return null;
                }
                Properties p = new Properties();
                p.load(in);
                String id = p.getProperty("id", "");
                String[] names = p.getProperty("jars", "").trim().split("\\s+");
                if (!id.matches("[0-9a-f]{8,64}") || names.length == 0 || names[0].isEmpty()) {
                    return null;
                }
                byte[][] bytes = new byte[names.length][];
                String[] sums = new String[names.length];
                for (int i = 0; i < names.length; i++) {
                    if (!safeName(names[i])) {
                        return null;
                    }
                    try (InputStream j = Handover.class.getResourceAsStream("/rlvm/handover/jars/" + names[i])) {
                        if (j == null) {
                            return null;
                        }
                        bytes[i] = j.readAllBytes();
                    }
                    sums[i] = sha256(bytes[i]);
                    if (!sums[i].equals(p.getProperty("sha256." + names[i]))) {
                        return null;
                    }
                }
                return new Jars(id, names, bytes, sums, p.getProperty("flags", "").trim(), p.getProperty("heap.machine", "").trim(),
                                p.getProperty("heap.other", "").trim(), p.getProperty("engine", "").trim());
            } catch (Exception e) {
                return null;
            }
        }
    }

    /** A plain file name: nothing that could leave the cache directory. */
    static boolean safeName(String name) {
        return name.matches("[A-Za-z0-9][A-Za-z0-9._-]*") && !name.contains("..");
    }

    static String sha256(byte[] data) throws java.security.NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    // ---------------------------------------------------------------------------------------------
    // Finding a JDK

    /** A JDK the run can be handed to, or the reason there is none. */
    static final class Jdk {
        /** The java binary with every symbolic link resolved; null if no JDK is usable. */
        final Path java;
        /** {@code JAVA_VERSION} of its {@code release} file. */
        final String version;
        /** Where it was found: RLVM_JAVA, JAVA_HOME or PATH. */
        final String source;
        /** Why no JDK is usable (every candidate with its reason); null when {@link #java} is not. */
        final String problem;

        Jdk(Path java, String version, String source, String problem) {
            this.java = java;
            this.version = version;
            this.source = source;
            this.problem = problem;
        }
    }

    /**
     * {@code RLVM_JAVA} if it is set, and then nothing else: it is the user's choice for RLVM. Otherwise
     * {@code $JAVA_HOME/bin/java}, then the first {@code java} on {@code PATH}; the first usable one wins.
     * A candidate is usable if {@link #inspect} says so - without starting it.
     */
    static Jdk findJdk(Map<String, String> env) {
        String named = env.get(ENV_JAVA);
        if (named != null && !named.isEmpty()) {
            return inspect(Path.of(named), ENV_JAVA);
        }
        StringBuilder problems = new StringBuilder();
        String home = env.get("JAVA_HOME");
        if (home != null && !home.isEmpty()) {
            Jdk j = inspect(Path.of(home, "bin", "java"), "JAVA_HOME");
            if (j.java != null) {
                return j;
            }
            problems.append(j.problem);
        }
        String path = env.get("PATH");
        if (path != null) {
            for (String dir : path.split(":")) {
                if (dir.isEmpty()) {
                    continue;
                }
                Path candidate;
                try {
                    candidate = Path.of(dir, "java");
                } catch (RuntimeException e) {
                    continue;
                }
                if (Files.isRegularFile(candidate)) {
                    Jdk j = inspect(candidate, "PATH");
                    if (j.java != null) {
                        return j;
                    }
                    problems.append(problems.length() == 0 ? "" : "; ").append(j.problem);
                    break; // the first java on PATH is what `java` means; a later one is not looked for
                }
            }
        }
        return new Jdk(null, null, null, problems.length() == 0 ? "no-jdk(RLVM_JAVA-unset,JAVA_HOME-"
                        + (home == null || home.isEmpty() ? "unset" : "unusable") + ",no-java-on-PATH)" : problems.toString());
    }

    /**
     * Whether {@code java} is the launcher of a JDK {@link #MIN_JAVA} or newer, decided from the files beside
     * it and without starting a VM: the binary (symbolic links resolved) is an executable regular file, the
     * {@code release} file of its home names a {@code JAVA_VERSION} of at least {@link #MIN_JAVA} and the
     * modules the jars need, and the home has HotSpot's server VM (the flags of the hand-over are HotSpot's).
     */
    static Jdk inspect(Path java, String source) {
        try {
            Path real = java.toRealPath();
            if (!Files.isRegularFile(real) || !Files.isExecutable(real)) {
                return unusable(source, java, "not-an-executable-file");
            }
            Path bin = real.getParent();
            Path home = bin == null ? null : bin.getParent();
            if (home == null) {
                return unusable(source, java, "no-java-home");
            }
            Path release = home.resolve("release");
            if (!Files.isRegularFile(release) || Files.size(release) > (1 << 20)) {
                return unusable(source, java, "no-release-file");
            }
            String version = null;
            String modules = null;
            for (String line : Files.readAllLines(release, StandardCharsets.ISO_8859_1)) {
                if (line.startsWith("JAVA_VERSION=")) {
                    version = unquote(line.substring("JAVA_VERSION=".length()));
                } else if (line.startsWith("MODULES=")) {
                    modules = " " + unquote(line.substring("MODULES=".length())) + " ";
                }
            }
            int major = majorOf(version);
            if (major < MIN_JAVA) {
                return unusable(source, java, "java-" + (version == null ? "unknown" : version) + "-is-older-than-" + MIN_JAVA);
            }
            for (String m : NEEDED_MODULES) {
                if (modules == null || !modules.contains(" " + m + " ")) {
                    return unusable(source, java, "no-module-" + m);
                }
            }
            if (!Files.isRegularFile(home.resolve("lib").resolve("server").resolve("libjvm.so"))) {
                return unusable(source, java, "no-hotspot-server-vm");
            }
            return new Jdk(real, version, source, null);
        } catch (Exception e) {
            return unusable(source, java, e.getClass().getSimpleName());
        }
    }

    private static Jdk unusable(String source, Path java, String reason) {
        return new Jdk(null, null, source, source + ":" + reason);
    }

    private static String unquote(String s) {
        s = s.trim();
        return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }

    /** {@code 25.0.5.1} -> 25, {@code 26-ea} -> 26, {@code 1.8.0_392} -> 1; -1 if there is no leading number. */
    static int majorOf(String version) {
        if (version == null) {
            return -1;
        }
        int n = 0;
        int i = 0;
        while (i < version.length() && Character.isDigit(version.charAt(i)) && i < 6) {
            n = n * 10 + (version.charAt(i) - '0');
            i++;
        }
        return i == 0 ? -1 : n;
    }

    // ---------------------------------------------------------------------------------------------
    // The cache directory

    /** The cache directory of a build, {@code <parent>/<build id>}, or null if the environment names no place. */
    static Path cacheDir(Map<String, String> env, String id) {
        try {
            String d = env.get(ENV_CACHE_DIR);
            Path parent;
            if (d != null && !d.isEmpty()) {
                parent = Path.of(d);
            } else {
                String xdg = env.get("XDG_CACHE_HOME");
                String home = env.get("HOME");
                if (xdg != null && !xdg.isEmpty() && Path.of(xdg).isAbsolute()) {
                    parent = Path.of(xdg, "rlvm");
                } else if (home != null && !home.isEmpty()) {
                    parent = Path.of(home, ".cache", "rlvm");
                } else {
                    return null;
                }
            }
            return parent.isAbsolute() ? parent.resolve(id) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The numeric user id that files of this process get: the owner of {@code /proc/self}. */
    static int ownUid() throws java.io.IOException {
        return (Integer) Files.getAttribute(Path.of("/proc/self"), "unix:uid");
    }

    /**
     * Makes the cache directory usable and returns it with every symbolic link resolved, or throws with the
     * reason. THE SECURITY ARGUMENT (*agentic-note*: rep-OPT-K): the JVM opens the jars by path, after this
     * process has compared them, so nobody but this user (and root) may be able to change what a path means
     * in between. Hence:
     * <ul>
     * <li>the directory is created with mode 0700, and it must be a directory of this user that neither the
     * group nor others can write;</li>
     * <li>every directory above it, up to the root, must belong to this user or to root, and must not be
     * writable by the group or by others - unless it is sticky (nobody else can rename or replace an entry
     * of this user there: {@code /tmp}), or unless nobody else can get to it at all, because a directory
     * above it belongs to this user or to root and is closed to the group and to others.</li>
     * </ul>
     * What is found in the directory is never trusted: {@link #materialise} compares every jar with the
     * executable's copy.
     */
    static Path secureDir(Path dir, int uid) throws java.io.IOException {
        if (!Files.isDirectory(dir)) {
            Files.createDirectories(dir, java.nio.file.attribute.PosixFilePermissions
                            .asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
        }
        Path real = dir.toRealPath();
        Path open = null; // the lowest directory above the cache that others can write, not yet known to be out of their reach
        for (Path p = real; p != null; p = p.getParent()) {
            Map<String, Object> a = Files.readAttributes(p, "unix:uid,mode", LinkOption.NOFOLLOW_LINKS);
            int owner = (Integer) a.get("uid");
            int mode = (Integer) a.get("mode");
            if ((mode & 0170000) != 0040000) {
                throw new java.io.IOException("not-a-directory:" + p);
            }
            boolean othersWrite = (mode & 022) != 0;
            if (p == real) {
                // The directory itself: this user's, closed to everybody else's writes.
                if (owner != uid || othersWrite) {
                    throw new java.io.IOException("cache-directory-not-private:" + p);
                }
            } else {
                if (owner != uid && owner != 0) {
                    throw new java.io.IOException("directory-above-the-cache-is-not-safe:" + p);
                }
                if ((mode & 011) == 0) {
                    open = null; // nobody else can pass through here: what is below is out of their reach
                } else if (othersWrite && (mode & 01000) == 0 && open == null) {
                    open = p;
                }
            }
        }
        if (open != null) {
            throw new java.io.IOException("directory-above-the-cache-is-not-safe:" + open);
        }
        return real;
    }

    /**
     * Every jar of the executable as a file of {@code dir} with exactly the executable's bytes: a file that
     * is there is read and compared, byte for byte, on every start (never trusted); one that is missing or
     * different is written beside it and renamed into place, which is atomic, so two processes that start
     * together each leave complete files and a reader never sees half of one.
     *
     * @return the class path
     */
    static String materialise(Jars jars, Path dir) throws java.io.IOException {
        StringBuilder cp = new StringBuilder();
        for (int i = 0; i < jars.names.length; i++) {
            Path f = dir.resolve(jars.names[i]);
            if (!same(f, jars.bytes[i])) {
                Path tmp = dir.resolve("." + jars.names[i] + "." + ProcessHandle.current().pid() + "." + System.nanoTime() + ".tmp");
                try {
                    Files.write(tmp, jars.bytes[i], java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
                    // A fixed time: a class-data or AOT archive of the JDK that names the jar stays valid.
                    Files.setLastModifiedTime(tmp, java.nio.file.attribute.FileTime.fromMillis(JAR_MTIME_MILLIS));
                    Files.move(tmp, f, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(tmp);
                }
                if (!same(f, jars.bytes[i])) {
                    throw new java.io.IOException("cannot-write:" + jars.names[i]);
                }
            }
            cp.append(i == 0 ? "" : ":").append(f);
        }
        return cp.toString();
    }

    /** 2026-01-01T00:00:00Z, the output time stamp of the Maven build. */
    private static final long JAR_MTIME_MILLIS = 1767225600000L;

    /** True if {@code f} is a regular file (not a link) with exactly these bytes. */
    static boolean same(Path f, byte[] expected) {
        try {
            if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS) || Files.size(f) != expected.length) {
                return false;
            }
            try (InputStream in = Files.newInputStream(f, LinkOption.NOFOLLOW_LINKS)) {
                byte[] got = in.readNBytes(expected.length + 1);
                return Arrays.equals(got, expected);
            }
        } catch (java.io.IOException | RuntimeException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The command line of the JVM

    /** Bytes of a heap option such as {@code -Xmx7g}; -1 if the line has none. */
    static long xmxOf(String flags) {
        long bytes = -1;
        for (String f : flags.trim().split("\\s+")) {
            if (f.startsWith("-Xmx") && f.length() > 4) {
                try {
                    bytes = Main.parseSize(f.substring(4), "-Xmx");
                } catch (UsageException e) {
                    // not a size this launcher reads: leave it to the VM
                }
            }
        }
        return bytes;
    }

    /** Physical memory, or the limit of the control group if that is smaller; -1 if neither can be read. */
    static long memoryBytes() {
        long best = -1;
        try {
            for (String line : Files.readAllLines(Path.of("/proc/meminfo"), StandardCharsets.ISO_8859_1)) {
                if (line.startsWith("MemTotal:")) {
                    best = Long.parseLong(line.replaceAll("[^0-9]", "")) << 10;
                    break;
                }
            }
        } catch (Exception e) {
            // unknown
        }
        for (String limit : new String[] {"/sys/fs/cgroup/memory.max", "/sys/fs/cgroup/memory/memory.limit_in_bytes"}) {
            try {
                long n = Long.parseLong(Files.readString(Path.of(limit), StandardCharsets.ISO_8859_1).trim());
                if (n > 0 && (best < 0 || n < best)) {
                    best = n;
                }
            } catch (Exception e) {
                // "max", or no such file
            }
        }
        return best;
    }

    /**
     * The heap flags of the hand-over: the build's line for the engine ({@code -Xmx7g -Xmn1g} for the
     * machine), or, on a machine with less memory than that heap and {@link #HEADROOM_BYTES}, three quarters
     * of the memory with a young generation of at most a quarter of that. Null = the default line does not
     * fit and the caller did not force the JVM: the run stays in the executable, whose heap grows as needed.
     */
    static String heapFlags(String line, long memory, boolean forced) {
        long xmx = xmxOf(line);
        if (xmx < 0 || memory < 0 || memory >= xmx + HEADROOM_BYTES) {
            return line;
        }
        if (!forced) {
            return null;
        }
        long mib = Math.max(256, (memory >> 20) * 3 / 4);
        return "-Xmx" + mib + "m -Xmn" + Math.min(1024, Math.max(64, mib / 4)) + "m";
    }

    /** What the executable took from its command line before {@code main}: {@code /proc/self/cmdline} without {@code args}. */
    static List<String> consumedVmOptions(String[] args) {
        List<String> consumed = new ArrayList<>();
        try {
            String[] all = new String(Files.readAllBytes(Path.of("/proc/self/cmdline")), StandardCharsets.UTF_8).split("\0", -1);
            List<String> rest = new ArrayList<>(Arrays.asList(args));
            for (int i = 1; i < all.length; i++) {
                if (all[i].isEmpty() && i == all.length - 1) {
                    break; // the terminator of the last argument
                }
                if (!rest.remove(all[i])) {
                    consumed.add(all[i]);
                }
            }
        } catch (Exception e) {
            // unknown: nothing is forwarded
        }
        return consumed;
    }

    static boolean isHeapOption(String a) {
        return (a.startsWith("-Xmx") || a.startsWith("-Xms") || a.startsWith("-Xmn")) && a.length() > 4 && Character.isDigit(a.charAt(4));
    }

    /**
     * {@code java FLAGS [HEAP] [VM OPTIONS OF THE COMMAND LINE] -Drlvm.handover=INFO -cp JARS rlvm.cli.Main ARGS}.
     * A heap option on the command line replaces the default heap, as for the development launcher.
     */
    static String[] command(Path java, String flags, String heap, List<String> userVm, String info, String classPath, String[] args) {
        List<String> c = new ArrayList<>();
        c.add(java.toString());
        addWords(c, flags);
        boolean userHeap = false;
        for (String u : userVm) {
            userHeap |= isHeapOption(u);
        }
        if (!userHeap) {
            addWords(c, heap);
        }
        c.addAll(userVm);
        c.add("-D" + PROPERTY + "=" + info);
        c.add("-cp");
        c.add(classPath);
        c.add("rlvm.cli.Main");
        c.addAll(Arrays.asList(args));
        return c.toArray(new String[0]);
    }

    private static void addWords(List<String> to, String words) {
        if (words != null) {
            for (String w : words.trim().split("\\s+")) {
                if (!w.isEmpty()) {
                    to.add(w);
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The probe: does this JDK start with these flags?

    /**
     * The name of the marker that says "this java started with these VM options and these jars": the java
     * binary and its VM library by path, size and time, the {@code release} file by content, the options.
     * A JDK that is updated in place, another heap or other flags give another name.
     */
    static String probeKey(Path java, List<String> vmOptions) throws Exception {
        StringBuilder s = new StringBuilder();
        Path home = java.getParent().getParent();
        for (Path p : new Path[] {java, home.resolve("lib").resolve("server").resolve("libjvm.so")}) {
            s.append(p).append('\0').append(Files.size(p)).append('\0').append(Files.getLastModifiedTime(p).toMillis()).append('\0');
        }
        s.append(Files.readString(home.resolve("release"), StandardCharsets.ISO_8859_1)).append('\0');
        s.append(String.join("\0", vmOptions));
        return "jdk-" + sha256(s.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 24) + ".ok";
    }

    /**
     * Starts {@code java VMOPTIONS -cp JARS rlvm.cli.Main --version} once as a child and says whether it
     * answered: status 0, the version line of this build and the runtime line of a JVM. This is the one JVM start that
     * the check of a JDK costs, paid once per JDK, flag line and build (the marker of {@link #probeKey});
     * without it a java that passes {@link #inspect} and does not start (a damaged installation, a refused
     * flag in {@code RLVM_JAVA_OPTS}, a heap that cannot be reserved) would end the run with the VM's status 1
     * after {@code execv}, where nothing can fall back any more.
     */
    static boolean probe(Path java, List<String> vmOptions, String classPath) {
        try {
            List<String> c = new ArrayList<>();
            c.add(java.toString());
            c.addAll(vmOptions);
            c.add("-cp");
            c.add(classPath);
            c.add("rlvm.cli.Main");
            c.add("--version");
            ProcessBuilder pb = new ProcessBuilder(c);
            pb.environment().remove(Main.ENV_STATUS_BASE);
            pb.environment().remove(Main.ENV_IN);
            pb.environment().remove(ENV_PLATFORM);
            pb.redirectErrorStream(true);
            pb.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
            Process p = pb.start();
            byte[] out;
            try (InputStream in = p.getInputStream()) {
                out = in.readNBytes(1 << 16);
            }
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            // The answer of these jars on a JVM, not merely a status: the version line and the runtime line.
            String answer = new String(out, StandardCharsets.UTF_8);
            return p.exitValue() == 0 && answer.startsWith("rlvm " + BuildInfo.VERSION + "\n") && answer.contains("\nruntime: jvm ");
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The step itself

    /** How the run that is checked in this process got here, for {@code --stats}. */
    static final class Outcome {
        final String platform;
        final String why;

        Outcome(String platform, String why) {
            this.platform = platform;
            this.why = why;
        }

        String stats() {
            return " platform=" + platform + " why=" + why;
        }
    }

    /**
     * Decides and, if the rule says so, hands the run over. Returns only when the export is to be checked in
     * this process - because the rule says so, or because the hand-over was not possible - with what
     * {@code --stats} says about it. Never throws.
     *
     * @param args the arguments of {@code main}
     * @param platform {@code --platform}, else {@code RLVM_PLATFORM}, else {@code auto} (resolved by {@code Main.parse})
     */
    static Outcome run(String[] args, Map<String, String> env, Platform platform, Path export, Options options, String checkerName) {
        if (!BuildInfo.isNativeImage()) {
            // The JVM side of a hand-over, or the development launcher.
            String how = null;
            try {
                how = System.getProperty(PROPERTY);
            } catch (RuntimeException e) {
                // as if not set
            }
            return new Outcome("jvm", how == null || how.isEmpty() ? "started-on-a-jvm" : "handed-over(" + how + ")");
        }
        String why;
        try {
            Request r = new Request();
            r.platform = platform;
            r.image = true;
            Jars jars = Jars.EMBEDDED;
            r.embedded = jars != null;
            r.passes = passesOf(options.extra());
            r.engine = engineOf(options.extra(), jars == null ? null : jars.defaultEngine);
            r.checkerNamed = checkerName != null;
            r.truffle = namesTruffle(options.extra());
            try {
                r.bytes = Files.size(export);
            } catch (java.io.IOException e) {
                r.bytes = 0;
            }
            List<String> vm = List.of();
            if (r.platform != Platform.IMAGE && r.embedded && (r.platform == Platform.JVM || r.passes > 1 || r.bytes >= AUTO_MIN_BYTES)) {
                vm = consumedVmOptions(args);
                r.vmOptions = !vm.isEmpty();
            }
            Decision d = decide(r);
            if (!d.handover) {
                return new Outcome("image", d.why);
            }
            why = "fallback(" + d.why + "):" + attempt(args, env, jars, r, vm, d.why);
        } catch (Throwable t) {
            why = "fallback:" + t.getClass().getSimpleName();
        }
        return new Outcome("image", why);
    }

    /** The hand-over; returns the reason when it did not happen. */
    private static String attempt(String[] args, Map<String, String> env, Jars jars, Request r, List<String> vm, String rule)
                    throws Exception {
        long t0 = System.nanoTime();
        Jdk jdk = findJdk(env);
        if (jdk.java == null) {
            return jdk.problem;
        }
        long tJdk = System.nanoTime();
        boolean forced = r.platform == Platform.JVM;
        String opts = env.get(ENV_JAVA_OPTS);
        String flags;
        String heap;
        if (opts != null && !opts.isBlank()) {
            flags = opts;
            heap = "";
        } else {
            flags = jars.flags;
            heap = heapFlags("machine".equals(r.engine) ? jars.heapMachine : jars.heapOther, memoryBytes(), forced);
            if (heap == null) {
                return "low-memory";
            }
        }
        Path dir = cacheDir(env, jars.id);
        if (dir == null) {
            return "no-cache-directory(HOME-unset)";
        }
        String classPath;
        try {
            dir = secureDir(dir, ownUid());
            classPath = materialise(jars, dir);
        } catch (java.io.IOException | RuntimeException e) {
            // This class's own refusals carry their reason; anything else is named by its kind.
            String m = e.getMessage();
            return "cache:" + (e.getClass() == java.io.IOException.class && m != null ? m.replace(' ', '_') : e.getClass().getSimpleName());
        }
        long tCache = System.nanoTime();
        // The VM options are everything between the binary and the property that says how the run got here.
        String[] command = command(jdk.java, flags, heap, vm, "", classPath, args);
        List<String> vmOptions = new ArrayList<>();
        int at = 1;
        while (at < command.length && !command[at].startsWith("-D" + PROPERTY + "=")) {
            vmOptions.add(command[at++]);
        }
        Path marker = dir.resolve(probeKey(jdk.java, vmOptions));
        boolean probed = false;
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
            probed = true;
            if (!probe(jdk.java, vmOptions, classPath)) {
                return "jdk-does-not-start(" + jdk.source + ")";
            }
            try {
                Files.write(marker, (String.join(" ", vmOptions) + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (java.io.IOException e) {
                // The next run probes again.
            }
        }
        // What the hand-over cost this process, in microseconds, for --stats of the JVM side: finding the
        // JDK, the cache (every jar compared), the probe (0 = the marker was there).
        long tEnd = System.nanoTime();
        command[at] = "-D" + PROPERTY + "=rule=" + rule + ",java=" + jdk.java + ",jdk=" + jdk.version + ",from=" + jdk.source + ",jars=" + dir
                        + ",find_us=" + (tJdk - t0) / 1000 + ",cache_us=" + (tCache - tJdk) / 1000 + ",probe_us=" + (probed ? (tEnd - tCache) / 1000 : 0);
        System.out.flush();
        System.err.flush();
        NativeExec.exec(jdk.java.toString(), command);
        return "exec-failed";
    }

    static int passesOf(Map<String, String> extra) {
        try {
            return Math.max(1, Integer.parseInt(extra.getOrDefault("passes", "1")));
        } catch (NumberFormatException e) {
            return 1; // the checker refuses it (status 64), in this process
        }
    }

    /** The engine as the command line names it: {@code policy=strict} is the reference engine. */
    static String engineOf(Map<String, String> extra, String defaultEngine) {
        if ("strict".equals(extra.get("policy"))) {
            return "reference";
        }
        String e = extra.get("engine");
        return e != null ? e : defaultEngine;
    }

    /** A Truffle engine option, or the machine's switch {@code t2}. */
    static boolean namesTruffle(Map<String, String> extra) {
        for (Map.Entry<String, String> e : extra.entrySet()) {
            String k = e.getKey();
            if (k.startsWith("engine.") || (k.equals("machine.switches") && e.getValue().toLowerCase(Locale.ROOT).contains("t2"))) {
                return true;
            }
        }
        return false;
    }

    /** The line of {@code --version} about the hand-over. Never throws. */
    static String describe(Map<String, String> env) {
        try {
            if (!BuildInfo.isNativeImage()) {
                return "jvm (these are the jars on a JVM: there is nothing to hand over to)";
            }
            Jars jars = Jars.EMBEDDED;
            if (jars == null) {
                return "image (this executable was built without its jars: it checks everything itself)";
            }
            Platform p = env.get(ENV_PLATFORM) == null || env.get(ENV_PLATFORM).isEmpty() ? Platform.DEFAULT
                            : Platform.parse(env.get(ENV_PLATFORM), ENV_PLATFORM);
            Jdk jdk = findJdk(env);
            Path dir = cacheDir(env, jars.id);
            String what = p == Platform.IMAGE ? "the executable checks every run itself (--platform auto hands " + ruleText() + " to a JVM, --platform jvm every run)"
                            : p == Platform.AUTO ? "the executable checks, and hands " + ruleText() + " to a JVM (--platform image|jvm forces a side)"
                                            : "every run is handed to a JVM (--platform image|auto)";
            return p.name().toLowerCase(Locale.ROOT) + " - " + what + "; a JVM is a JDK " + MIN_JAVA + " or newer (RLVM_JAVA, JAVA_HOME, java on PATH). JDK: "
                            + (jdk.java != null ? jdk.java + " (" + jdk.version + ", " + jdk.source + ")"
                                            : "none usable [" + jdk.problem + "]: the executable checks everything itself")
                            + "; jars: " + jars.names.length + " in the executable (" + jars.totalBytes() + " bytes, build " + jars.id
                            + "), unpacked to " + (dir == null ? "nowhere (HOME is not set)" : dir.toString());
        } catch (Throwable t) {
            return "unknown (" + t.getClass().getSimpleName() + ")";
        }
    }
}
