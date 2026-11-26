package rlvm.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The hybrid single entry as far as a JVM can test it: the rule, the search for a JDK, the cache
 * directory and the command line. The step itself ({@code execv}) exists in the executable only and is
 * verified on every build by {@code scripts/build.sh} (verify_handover).
 */
class HandoverTest {
    @TempDir
    Path dir;

    private static Handover.Request request() {
        Handover.Request r = new Handover.Request();
        r.platform = Handover.Platform.AUTO;
        r.image = true;
        r.embedded = true;
        r.engine = "machine";
        r.bytes = 1 << 20;
        return r;
    }

    // ------------------------------------------------------------------ the rule

    @Test
    void aPlainRunStaysInTheExecutable() {
        // Nothing said: the executable, whatever the run asks for.
        Handover.Request plain = request();
        plain.platform = Handover.Platform.DEFAULT;
        plain.passes = 3;
        assertEquals("forced", Handover.decide(plain).why);
        assertFalse(Handover.decide(plain).handover);
        // Under auto, a single pass stays.
        Handover.Decision d = Handover.decide(request());
        assertFalse(d.handover);
        assertEquals("auto:single-pass", d.why);
        // ... whatever its size, as long as the measured rule knows no size that gains.
        Handover.Request big = request();
        big.bytes = 1L << 40;
        assertEquals(Handover.AUTO_MIN_BYTES != Long.MAX_VALUE, Handover.decide(big).handover);
    }

    @Test
    void severalPassesGoToTheJvm() {
        Handover.Request r = request();
        r.passes = 3;
        Handover.Decision d = Handover.decide(r);
        assertTrue(d.handover);
        assertEquals("auto:passes=3", d.why);
    }

    @Test
    void autoHandsOverTheDefaultEnginesPlainRunsOnly() {
        Handover.Request r = request();
        r.passes = 3;
        r.engine = "reference";
        assertFalse(Handover.decide(r).handover);
        r = request();
        r.passes = 3;
        r.vmOptions = true;
        assertEquals("auto:vm-options-of-the-executable", Handover.decide(r).why);
        r = request();
        r.passes = 3;
        r.checkerNamed = true;
        assertFalse(Handover.decide(r).handover);
    }

    @Test
    void theOptionForcesEitherSide() {
        Handover.Request r = request();
        r.platform = Handover.Platform.JVM;
        assertTrue(Handover.decide(r).handover);
        r.engine = "reference";
        r.vmOptions = true;
        assertTrue(Handover.decide(r).handover, "forced: any engine, VM options are passed on");
        r = request();
        r.passes = 3;
        r.platform = Handover.Platform.IMAGE;
        assertEquals("forced", Handover.decide(r).why);
        assertFalse(Handover.decide(r).handover);
    }

    @Test
    void neverWithoutAnExecutableItsJarsOrForTheTruffleTier() {
        Handover.Request r = request();
        r.platform = Handover.Platform.JVM;
        r.image = false;
        assertFalse(Handover.decide(r).handover);
        r = request();
        r.platform = Handover.Platform.JVM;
        r.embedded = false;
        assertEquals("no-embedded-jars", Handover.decide(r).why);
        r = request();
        r.platform = Handover.Platform.JVM;
        r.truffle = true;
        assertFalse(Handover.decide(r).handover, "the embedded jars have no Truffle tier");
        assertTrue(Handover.namesTruffle(Map.of("machine.switches", "default,t2")));
        assertTrue(Handover.namesTruffle(Map.of("engine.CompilerThreads", "1")));
        assertFalse(Handover.namesTruffle(Map.of("machine.switches", "default,probes", "passes", "3")));
    }

    @Test
    void whatTheRuleReadsFromTheCommandLine() {
        assertEquals(3, Handover.passesOf(Map.of("passes", "3")));
        assertEquals(1, Handover.passesOf(Map.of()));
        assertEquals(1, Handover.passesOf(Map.of("passes", "many")));
        assertEquals("machine", Handover.engineOf(Map.of(), "machine"));
        assertEquals("reference", Handover.engineOf(Map.of("engine", "reference"), "machine"));
        assertEquals("reference", Handover.engineOf(Map.of("policy", "strict"), "machine"));
        assertEquals("reference", Handover.engineOf(Map.of("policy", "strict", "engine", "machine"), "machine"));
    }

    @Test
    void theOptionAndItsEnvironmentVariable() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(sink, true, StandardCharsets.UTF_8);
        Path export = dir.resolve("x.ndjson");
        Files.writeString(export, "{}\n");
        String in = export.toString();
        assertEquals(Handover.Platform.IMAGE, Main.parse(new String[] {in}, Map.of(), ps, ps).platform, "the hand-over is an opt-in");
        assertEquals(Handover.Platform.IMAGE, Handover.Platform.DEFAULT);
        assertEquals(Handover.Platform.JVM, Main.parse(new String[] {"--platform", "jvm", in}, Map.of(), ps, ps).platform);
        assertEquals(Handover.Platform.IMAGE, Main.parse(new String[] {"--platform=image", in}, Map.of(), ps, ps).platform);
        assertEquals(Handover.Platform.JVM, Main.parse(new String[] {in}, Map.of("RLVM_PLATFORM", "jvm"), ps, ps).platform);
        assertEquals(Handover.Platform.AUTO, Main.parse(new String[] {"--platform", "auto", in}, Map.of("RLVM_PLATFORM", "jvm"), ps, ps).platform);
        assertThrows(UsageException.class, () -> Main.parse(new String[] {"--platform", "hotspot", in}, Map.of(), ps, ps));
        assertThrows(UsageException.class, () -> Main.parse(new String[] {in}, Map.of("RLVM_PLATFORM", "JVM"), ps, ps));
        // On a JVM the option changes nothing: the jars check, and the statistics say where they run.
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
        assertEquals(ExitCode.DECLINE, Main.run(new String[] {"--platform", "jvm", "--stats", in}, Map.of(), ps, err));
        assertTrue(errBytes.toString(StandardCharsets.UTF_8).contains(" platform=jvm why=started-on-a-jvm"), errBytes.toString(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ finding a JDK

    /** A directory that looks like a JDK: bin/java, release, lib/server/libjvm.so. */
    private Path fakeJdk(String name, String version, String modules, boolean vm) throws IOException {
        Path home = dir.resolve(name);
        Files.createDirectories(home.resolve("bin"));
        Path java = home.resolve("bin").resolve("java");
        Files.writeString(java, "#!/bin/sh\nexit 1\n");
        Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwxr-xr-x"));
        if (version != null) {
            Files.writeString(home.resolve("release"), "IMPLEMENTOR=\"Test\"\nJAVA_VERSION=\"" + version + "\"\nMODULES=\"" + modules + "\"\n");
        }
        if (vm) {
            Files.createDirectories(home.resolve("lib").resolve("server"));
            Files.writeString(home.resolve("lib").resolve("server").resolve("libjvm.so"), "");
        }
        return home;
    }

    private static final String MODULES = "java.base java.compiler java.management jdk.unsupported";

    @Test
    void aJdkIsJudgedByItsReleaseFile() throws Exception {
        Path ok = fakeJdk("jdk25", "25.0.5.1", MODULES, true);
        Handover.Jdk j = Handover.inspect(ok.resolve("bin/java"), "RLVM_JAVA");
        assertNotNull(j.java, j.problem);
        assertEquals("25.0.5.1", j.version);
        assertEquals(ok.resolve("bin/java").toRealPath(), j.java);
        assertNotNull(Handover.inspect(fakeJdk("jdk26", "26-ea", MODULES, true).resolve("bin/java"), "x").java);

        assertEquals("x:java-21.0.2-is-older-than-25", Handover.inspect(fakeJdk("jdk21", "21.0.2", MODULES, true).resolve("bin/java"), "x").problem);
        assertEquals("x:java-1.8.0_392-is-older-than-25", Handover.inspect(fakeJdk("jdk8", "1.8.0_392", MODULES, true).resolve("bin/java"), "x").problem);
        assertEquals("x:no-release-file", Handover.inspect(fakeJdk("norelease", null, MODULES, true).resolve("bin/java"), "x").problem);
        assertEquals("x:no-module-java.management", Handover.inspect(fakeJdk("small", "25", "java.base", true).resolve("bin/java"), "x").problem);
        assertEquals("x:no-hotspot-server-vm", Handover.inspect(fakeJdk("novm", "25", MODULES, false).resolve("bin/java"), "x").problem);
        assertNull(Handover.inspect(dir.resolve("nothing/bin/java"), "x").java);
        Path notExec = fakeJdk("notexec", "25", MODULES, true).resolve("bin/java");
        Files.setPosixFilePermissions(notExec, PosixFilePermissions.fromString("rw-r--r--"));
        assertEquals("x:not-an-executable-file", Handover.inspect(notExec, "x").problem);
        // A symbolic link (the usual /usr/bin/java) is resolved before the release file is looked for.
        Path link = dir.resolve("java-link");
        Files.createSymbolicLink(link, ok.resolve("bin/java"));
        assertEquals(ok.resolve("bin/java").toRealPath(), Handover.inspect(link, "PATH").java);

        assertEquals(25, Handover.majorOf("25.0.5.1"));
        assertEquals(26, Handover.majorOf("26-ea"));
        assertEquals(1, Handover.majorOf("1.8.0_392"));
        assertEquals(-1, Handover.majorOf("x"));
        assertEquals(-1, Handover.majorOf(null));
    }

    @Test
    void whereAJdkIsLookedFor() throws Exception {
        Path good = fakeJdk("good", "25.0.1", MODULES, true);
        Path old = fakeJdk("old", "21.0.2", MODULES, true);
        Path other = fakeJdk("other", "27", MODULES, true);
        Map<String, String> env = new HashMap<>();
        Handover.Jdk none = Handover.findJdk(env);
        assertNull(none.java);
        assertTrue(none.problem.startsWith("no-jdk("), none.problem);

        env.put("PATH", dir.resolve("empty") + ":" + good.resolve("bin") + ":" + other.resolve("bin"));
        assertEquals("PATH", Handover.findJdk(env).source);
        assertEquals("25.0.1", Handover.findJdk(env).version);

        env.put("JAVA_HOME", other.toString());
        assertEquals("JAVA_HOME", Handover.findJdk(env).source);
        assertEquals("27", Handover.findJdk(env).version);

        // JAVA_HOME too old: the java on PATH is taken.
        env.put("JAVA_HOME", old.toString());
        assertEquals("PATH", Handover.findJdk(env).source);

        // RLVM_JAVA is the user's choice for RLVM: when it is unusable nothing else is tried.
        env.put("RLVM_JAVA", old.resolve("bin/java").toString());
        Handover.Jdk j = Handover.findJdk(env);
        assertNull(j.java);
        assertEquals("RLVM_JAVA:java-21.0.2-is-older-than-25", j.problem);
        env.put("RLVM_JAVA", good.resolve("bin/java").toString());
        assertEquals("RLVM_JAVA", Handover.findJdk(env).source);

        // Only the first java on PATH counts: it is what `java` means in that shell.
        env.clear();
        env.put("PATH", old.resolve("bin") + ":" + good.resolve("bin"));
        j = Handover.findJdk(env);
        assertNull(j.java);
        assertEquals("PATH:java-21.0.2-is-older-than-25", j.problem);
    }

    // ------------------------------------------------------------------ the cache

    private static Handover.Jars jars(String... namesAndContents) throws Exception {
        int n = namesAndContents.length / 2;
        String[] names = new String[n];
        byte[][] bytes = new byte[n][];
        String[] sums = new String[n];
        for (int i = 0; i < n; i++) {
            names[i] = namesAndContents[2 * i];
            bytes[i] = namesAndContents[2 * i + 1].getBytes(StandardCharsets.UTF_8);
            sums[i] = Handover.sha256(bytes[i]);
        }
        return new Handover.Jars("0123456789ab", names, bytes, sums, "-XX:+UseSerialGC", "-Xmx7g -Xmn1g", "-Xms13g -Xmx13g -Xmn1g", "machine");
    }

    /** The directory of the test is only a place for a cache where nobody else can write above it. */
    private Path secure(Path d) throws IOException {
        try {
            return Handover.secureDir(d, Handover.ownUid());
        } catch (IOException e) {
            assumeTrue(!String.valueOf(e.getMessage()).startsWith("directory-above-the-cache-is-not-safe"), "the temporary directory is open to others");
            throw e;
        }
    }

    @Test
    void whereTheCacheIs() {
        assertEquals(Path.of("/c/abc"), Handover.cacheDir(Map.of("RLVM_CACHE_DIR", "/c", "HOME", "/h"), "abc"));
        assertEquals(Path.of("/x/rlvm/abc"), Handover.cacheDir(Map.of("XDG_CACHE_HOME", "/x", "HOME", "/h"), "abc"));
        assertEquals(Path.of("/h/.cache/rlvm/abc"), Handover.cacheDir(Map.of("HOME", "/h"), "abc"));
        assertEquals(Path.of("/h/.cache/rlvm/abc"), Handover.cacheDir(Map.of("HOME", "/h", "XDG_CACHE_HOME", "relative"), "abc"));
        assertNull(Handover.cacheDir(Map.of(), "abc"));
        assertNull(Handover.cacheDir(Map.of("RLVM_CACHE_DIR", "relative"), "abc"));
    }

    @Test
    void theCacheIsCreatedPrivateAndNeverTrusted() throws Exception {
        Handover.Jars jars = jars("a.jar", "first jar", "b.jar", "second jar");
        Path cache = secure(dir.resolve("cache").resolve(jars.id));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(cache)));
        String cp = Handover.materialise(jars, cache);
        assertEquals(cache.resolve("a.jar") + ":" + cache.resolve("b.jar"), cp);
        assertArrayEquals(jars.bytes[0], Files.readAllBytes(cache.resolve("a.jar")));
        long written = Files.getLastModifiedTime(cache.resolve("a.jar")).toMillis();

        // Found as written: nothing is rewritten.
        Handover.materialise(jars, cache);
        assertEquals(written, Files.getLastModifiedTime(cache.resolve("a.jar")).toMillis());
        try (var files = Files.list(cache)) {
            assertEquals(2, files.count(), "no temporary file is left");
        }

        // Another content of the same length, a longer file, a symbolic link to a file with the right
        // content, a directory in its place: none is used, each is replaced by the executable's bytes.
        Files.writeString(cache.resolve("a.jar"), "FIRST jar");
        Handover.materialise(jars, cache);
        assertArrayEquals(jars.bytes[0], Files.readAllBytes(cache.resolve("a.jar")));
        Files.writeString(cache.resolve("b.jar"), "second jar and more");
        Handover.materialise(jars, cache);
        assertArrayEquals(jars.bytes[1], Files.readAllBytes(cache.resolve("b.jar")));
        Path elsewhere = dir.resolve("elsewhere.jar");
        Files.writeString(elsewhere, "first jar");
        Files.delete(cache.resolve("a.jar"));
        Files.createSymbolicLink(cache.resolve("a.jar"), elsewhere);
        assertFalse(Handover.same(cache.resolve("a.jar"), jars.bytes[0]), "a link is not the file");
        Handover.materialise(jars, cache);
        assertTrue(Files.isRegularFile(cache.resolve("a.jar"), LinkOption.NOFOLLOW_LINKS));
        assertArrayEquals(jars.bytes[0], Files.readAllBytes(cache.resolve("a.jar")));
    }

    @Test
    void aCacheThatOthersCanWriteIsRefused() throws Exception {
        Path cache = secure(dir.resolve("c1"));
        int uid = Handover.ownUid();
        // The directory itself open to the group or to others.
        Files.setPosixFilePermissions(cache, PosixFilePermissions.fromString("rwxrwx---"));
        assertTrue(assertThrows(IOException.class, () -> Handover.secureDir(cache, uid)).getMessage().startsWith("cache-directory-not-private"));
        Files.setPosixFilePermissions(cache, PosixFilePermissions.fromString("rwx---rwx"));
        assertThrows(IOException.class, () -> Handover.secureDir(cache, uid));
        Files.setPosixFilePermissions(cache, PosixFilePermissions.fromString("rwxr-xr-x"));
        assertEquals(cache.toRealPath(), Handover.secureDir(cache, uid), "readable by others is harmless: the jars are public");
        // Somebody else's directory.
        assertThrows(IOException.class, () -> Handover.secureDir(cache, uid + 1));
        // A file where the directory should be.
        Path file = dir.resolve("file");
        Files.writeString(file, "x");
        assertThrows(IOException.class, () -> Handover.secureDir(file.resolve("below"), uid));
    }

    @Test
    void aDirectoryAboveTheCacheThatOthersCanWriteIsRefusedUnlessTheyCannotGetThere() throws Exception {
        int uid = Handover.ownUid();
        Path open = dir.resolve("open");
        Files.createDirectory(open);
        Files.setPosixFilePermissions(open, PosixFilePermissions.fromString("rwxrwxrwx"));
        // The directory of this test is closed to everybody else (0700): what is below it is out of reach,
        // however open it is.
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
        Path below = Handover.secureDir(open.resolve("below"), uid);
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(below)));
        // Once others can pass through the directory above, the open one counts ...
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
        try {
            IOException e = assertThrows(IOException.class, () -> Handover.secureDir(below, uid));
            assertEquals("directory-above-the-cache-is-not-safe:" + open.toRealPath(), e.getMessage());
            // ... unless it is sticky, like /tmp: nobody else can rename an entry of this user there ...
            Files.setAttribute(open, "unix:mode", 01777);
            try {
                assertEquals(below, Handover.secureDir(below, uid));
            } catch (IOException above) {
                // (only where the temporary directory itself has an open directory above it)
                assumeTrue(!above.getMessage().endsWith(":" + open.toRealPath()), above.getMessage());
            }
            // ... and a directory that others cannot write is fine.
            Files.setPosixFilePermissions(open, PosixFilePermissions.fromString("rwxr-xr-x"));
            try {
                assertEquals(below, Handover.secureDir(below, uid));
            } catch (IOException above) {
                assumeTrue(!above.getMessage().endsWith(":" + open.toRealPath()), above.getMessage());
            }
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        }
    }

    // ------------------------------------------------------------------ the command line

    @Test
    void theHeapFollowsTheEngineAndTheMachinesMemory() {
        long gib = 1L << 30;
        assertEquals(7 * gib, Handover.xmxOf("-Xmx7g -Xmn1g"));
        assertEquals(-1, Handover.xmxOf("-Xmn1g"));
        assertEquals("-Xmx7g -Xmn1g", Handover.heapFlags("-Xmx7g -Xmn1g", 16 * gib, false));
        assertEquals("-Xmx7g -Xmn1g", Handover.heapFlags("-Xmx7g -Xmn1g", 8 * gib, false));
        assertEquals("-Xmx7g -Xmn1g", Handover.heapFlags("-Xmx7g -Xmn1g", -1, false), "memory unknown: the build's line");
        // Less memory than the heap and its headroom: auto stays in the executable, a forced hand-over shrinks the heap.
        assertNull(Handover.heapFlags("-Xmx7g -Xmn1g", 4 * gib, false));
        assertEquals("-Xmx3072m -Xmn768m", Handover.heapFlags("-Xmx7g -Xmn1g", 4 * gib, true));
        assertEquals("-Xmx9216m -Xmn1024m", Handover.heapFlags("-Xms13g -Xmx13g -Xmn1g", 12 * gib, true));
    }

    @Test
    void theCommandOfTheJvm() {
        String[] args = {"--threads", "8", "--platform", "jvm", "x.ndjson"};
        String[] c = Handover.command(Path.of("/jdk/bin/java"), "-XX:+UseSerialGC  -XX:CICompilerCount=8", "-Xmx7g -Xmn1g", List.of(),
                        "rule=forced", "/c/a.jar:/c/b.jar", args);
        assertEquals(List.of("/jdk/bin/java", "-XX:+UseSerialGC", "-XX:CICompilerCount=8", "-Xmx7g", "-Xmn1g", "-Drlvm.handover=rule=forced",
                        "-cp", "/c/a.jar:/c/b.jar", "rlvm.cli.Main", "--threads", "8", "--platform", "jvm", "x.ndjson"), List.of(c));
        // A heap option of the command line replaces the default heap; other VM options are added.
        c = Handover.command(Path.of("/jdk/bin/java"), "-XX:+UseSerialGC", "-Xmx7g -Xmn1g", List.of("-Xmx3g", "-Dk=v"), "i", "/c/a.jar", new String[] {"x"});
        assertEquals(List.of("/jdk/bin/java", "-XX:+UseSerialGC", "-Xmx3g", "-Dk=v", "-Drlvm.handover=i", "-cp", "/c/a.jar", "rlvm.cli.Main", "x"), List.of(c));
        assertTrue(Handover.isHeapOption("-Xmn1g"));
        assertFalse(Handover.isHeapOption("-Xmsg"));
        assertFalse(Handover.isHeapOption("-XX:+UseSerialGC"));
    }

    @Test
    void theProbeMarkerNamesTheJdkAndTheOptions() throws Exception {
        Path a = fakeJdk("a", "25.0.1", MODULES, true).resolve("bin/java").toRealPath();
        Path b = fakeJdk("b", "25.0.2", MODULES, true).resolve("bin/java").toRealPath();
        String k = Handover.probeKey(a, List.of("-Xmx7g"));
        assertTrue(k.matches("jdk-[0-9a-f]{24}\\.ok"), k);
        assertEquals(k, Handover.probeKey(a, List.of("-Xmx7g")));
        assertNotEquals(k, Handover.probeKey(a, List.of("-Xmx6g")));
        assertNotEquals(k, Handover.probeKey(b, List.of("-Xmx7g")));
        // A script that exits with 1 is a java that does not start.
        assertFalse(Handover.probe(a, List.of("-Xmx7g"), "/nowhere.jar"));
    }

    @Test
    void namesOfTheJarsCannotLeaveTheDirectory() {
        assertTrue(Handover.safeName("rlvm-cli-0.1.0-SNAPSHOT.jar"));
        assertFalse(Handover.safeName("../x.jar"));
        assertFalse(Handover.safeName("a/b.jar"));
        assertFalse(Handover.safeName(".hidden"));
        assertFalse(Handover.safeName(""));
    }

    @Test
    void onAJvmThereIsNothingToHandOver() {
        assertNull(Handover.Jars.EMBEDDED, "the jars are data of the executable only");
        assertTrue(Handover.describe(Map.of()).startsWith("jvm "));
    }
}
