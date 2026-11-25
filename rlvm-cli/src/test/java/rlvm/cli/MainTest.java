package rlvm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Command line, environment and the route from arguments to exit status. */
class MainTest {
    @TempDir
    Path dir;

    private Path export;
    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
    private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

    @BeforeEach
    void createExport() throws Exception {
        export = dir.resolve("test.ndjson");
        Files.writeString(export, "{\"meta\":{}}\n");
        TestPluginChecker.lastExport = null;
        TestPluginChecker.lastOptions = null;
        TestPluginChecker.lastThreadName = null;
    }

    private static Map<String, String> env(String... keyValues) {
        Map<String, String> env = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            env.put(keyValues[i], keyValues[i + 1]);
        }
        return env;
    }

    private int run(Map<String, String> env, String... args) {
        return Main.run(args, env, out, err);
    }

    private String stdout() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ where the export comes from

    @Test
    void exportAsArgument() {
        assertEquals(ExitCode.DECLINE, run(env(), export.toString()));
        assertEquals(export, TestPluginChecker.lastExport);
        assertEquals("rlvm-checker", TestPluginChecker.lastThreadName);
    }

    @Test
    void exportFromEnvironment() {
        assertEquals(ExitCode.DECLINE, run(env("IN", export.toString())));
        assertEquals(export, TestPluginChecker.lastExport);
    }

    @Test
    void argumentWinsOverEnvironment() throws Exception {
        Path other = dir.resolve("other.ndjson");
        Files.writeString(other, "x\n");
        assertEquals(ExitCode.DECLINE, run(env("IN", other.toString()), export.toString()));
        assertEquals(export, TestPluginChecker.lastExport);
    }

    @Test
    void symbolicLinkIsFollowed() throws Exception {
        // The arena passes cached tests as symbolic links.
        Path link = dir.resolve("link.ndjson");
        Files.createSymbolicLink(link, export);
        assertEquals(ExitCode.DECLINE, run(env("IN", link.toString())));
        assertEquals(link, TestPluginChecker.lastExport);
    }

    @Test
    void missingExportIsAUsageError() {
        assertEquals(ExitCode.USAGE, run(env()));
        assertTrue(stderr().contains("no export given"), stderr());
        assertEquals(ExitCode.USAGE, run(env("IN", "")));
        assertEquals(ExitCode.USAGE, run(env(), ""));
    }

    @Test
    void nonexistentExportIsAUsageError() {
        assertEquals(ExitCode.USAGE, run(env(), dir.resolve("nope.ndjson").toString()));
        assertEquals(ExitCode.USAGE, run(env("IN", dir.resolve("nope.ndjson").toString())));
        assertEquals(ExitCode.USAGE, run(env(), dir.toString()), "a directory is not an export");
        assertEquals(null, TestPluginChecker.lastExport, "the checker must not have been called");
    }

    @Test
    void twoExportsAreAUsageError() {
        assertEquals(ExitCode.USAGE, run(env(), export.toString(), export.toString()));
    }

    @Test
    void doubleDashEndsOptions() throws Exception {
        Path odd = dir.resolve("--stats");
        Files.writeString(odd, "x\n");
        // Relative paths starting with '-' are only reachable after "--"; here the absolute path works anyway.
        assertEquals(ExitCode.DECLINE, run(env(), "--", odd.toString()));
        assertEquals(odd, TestPluginChecker.lastExport);
        assertFalse(TestPluginChecker.lastOptions.stats());
        assertEquals(ExitCode.USAGE, run(env(), "--", "--stats"), "'--stats' after '--' is a (missing) file");
    }

    // ------------------------------------------------------------------ options

    @Test
    void threadsInAllSpellings() {
        String in = export.toString();
        String[][] spellings = {{"-j", "4"}, {"-j4"}, {"--threads", "4"}, {"--threads=4"}};
        for (String[] s : spellings) {
            String[] args = new String[s.length + 1];
            System.arraycopy(s, 0, args, 0, s.length);
            args[s.length] = in;
            assertEquals(ExitCode.DECLINE, run(env(), args), String.join(" ", s));
            assertEquals(4, TestPluginChecker.lastOptions.threads(), String.join(" ", s));
            assertEquals(4, TestPluginChecker.lastOptions.effectiveThreads());
        }
        // Options may follow the export.
        assertEquals(ExitCode.DECLINE, run(env(), in, "-j", "3"));
        assertEquals(3, TestPluginChecker.lastOptions.threads());
    }

    @Test
    void threadsFromEnvironmentAndPrecedence() {
        String in = export.toString();
        assertEquals(ExitCode.DECLINE, run(env("RLVM_THREADS", "6"), in));
        assertEquals(6, TestPluginChecker.lastOptions.threads());
        assertEquals(ExitCode.DECLINE, run(env("RLVM_THREADS", "6"), "-j", "2", in));
        assertEquals(2, TestPluginChecker.lastOptions.threads());
        assertEquals(ExitCode.DECLINE, run(env(), in));
        assertEquals(Options.AUTO_THREADS, TestPluginChecker.lastOptions.threads());
        assertEquals(ExitCode.DECLINE, run(env(), "--threads", "auto", in));
        assertEquals(Options.AUTO_THREADS, TestPluginChecker.lastOptions.threads());
        assertEquals(ExitCode.DECLINE, run(env("RLVM_THREADS", ""), in), "an empty variable means unset");
    }

    @Test
    void badThreadCountsAreUsageErrors() {
        String in = export.toString();
        assertEquals(ExitCode.USAGE, run(env(), "-j", "many", in));
        assertEquals(ExitCode.USAGE, run(env(), "-j", "-1", in));
        assertEquals(ExitCode.USAGE, run(env(), "-j", "100000", in));
        assertEquals(ExitCode.USAGE, run(env(), in, "-j"));
        assertEquals(ExitCode.USAGE, run(env("RLVM_THREADS", "x"), in));
        assertEquals(null, TestPluginChecker.lastExport);
    }

    @Test
    void stackSize() throws Exception {
        String in = export.toString();
        assertEquals(ExitCode.DECLINE, run(env(), in));
        assertEquals(512L << 20, TestPluginChecker.lastOptions.stackBytes());
        assertEquals(ExitCode.DECLINE, run(env(), "--stack-size", "64m", in));
        assertEquals(64L << 20, TestPluginChecker.lastOptions.stackBytes());
        assertEquals(ExitCode.DECLINE, run(env("RLVM_STACK_SIZE", "2g"), in));
        assertEquals(2L << 30, TestPluginChecker.lastOptions.stackBytes());
        assertEquals(ExitCode.DECLINE, run(env("RLVM_STACK_SIZE", "2g"), "--stack-size=128m", in));
        assertEquals(128L << 20, TestPluginChecker.lastOptions.stackBytes());
        assertEquals(1L << 20, Main.parseSize("1048576", "t"));
        assertEquals(1L << 20, Main.parseSize("1024k", "t"));
        assertEquals(1L << 20, Main.parseSize("1M", "t"));
        assertEquals(1L << 20, Main.parseSize("1mb", "t"));
        assertEquals(3L << 30, Main.parseSize("3G", "t"));
        for (String bad : List.of("", "m", "12q", "-5m", "0", "1k", "100g", "1.5g", "99999999999999999999")) {
            assertThrows(UsageException.class, () -> Main.parseSize(bad, "t"), bad);
        }
        assertEquals(ExitCode.USAGE, run(env(), "--stack-size", "huge", in));
    }

    @Test
    void statsLine() {
        assertEquals(ExitCode.DECLINE, run(env(), "--stats", export.toString()));
        assertTrue(TestPluginChecker.lastOptions.stats());
        String line = stderr().lines().filter(l -> l.startsWith("rlvm: stats:")).findFirst().orElse(null);
        assertNotNull(line, stderr());
        for (String key : List.of(" result=decline", " exit=2", " checker=test-plugin", " mode=jvm", " threads=auto", " wall_ms=",
                        " cpu_user_ms=", " peak_rss_kb=", " heap_max_kb=")) {
            assertTrue(line.contains(key), key + " missing in: " + line);
        }
    }

    @Test
    void noStatsByDefault() {
        assertEquals(ExitCode.DECLINE, run(env(), export.toString()));
        assertFalse(stderr().contains("rlvm: stats:"), stderr());
        assertEquals("", stdout());
    }

    @Test
    void passThroughSettings() {
        String in = export.toString();
        assertEquals(ExitCode.ACCEPT, run(env(), "-X", "verdict=accept", "-Xtier=plain", "-X", "flag", in));
        Options o = TestPluginChecker.lastOptions;
        assertEquals("accept", o.extra("verdict", null));
        assertEquals("plain", o.extra("tier", null));
        assertEquals("true", o.extra("flag", null));
        assertEquals(List.of("verdict", "tier", "flag"), List.copyOf(o.extra().keySet()));
        assertEquals(ExitCode.REJECT, run(env(), "-X", "verdict=reject", in));
        assertEquals(ExitCode.INTERNAL_ERROR, run(env(), "-X", "verdict=garbage", in), "the plug-in throws");
        assertEquals(ExitCode.USAGE, run(env(), "-X", "=v", in));
        assertEquals(ExitCode.USAGE, run(env(), in, "-X"));
    }

    @Test
    void vmOptionsAreNotSwallowedAsSettings() {
        String in = export.toString();
        for (String vm : List.of("-Xmx1g", "-Xms256m", "-Xss4m", "-Xmn1g", "-XX:+UseSerialGC")) {
            assertEquals(ExitCode.USAGE, run(env(), vm, in), vm);
        }
        assertTrue(stderr().contains("VM option"), stderr());
        assertFalse(stderr().contains("rlvm-jvm"), "the release has one executable; no message names a second launcher: " + stderr());
        // Words that merely start like one are fine.
        assertEquals(ExitCode.DECLINE, run(env(), "-Xmsg=hello", "-Xmxyz", in));
    }

    @Test
    void unknownOptionIsAUsageError() {
        assertEquals(ExitCode.USAGE, run(env(), "--frobnicate", export.toString()));
        assertTrue(stderr().contains("unknown option '--frobnicate'"), stderr());
        assertEquals(ExitCode.USAGE, run(env(), "--stats=yes", export.toString()));
        assertEquals(null, TestPluginChecker.lastExport);
    }

    @Test
    void aSettingTheCheckerRefusesIsAUsageErrorNotAnInternalError() {
        // rlvm.cli.UsageException thrown by Checker.check: status 64 and the checker's message.
        assertEquals(ExitCode.USAGE, run(env(), "-X", "verdict=unknown-setting", export.toString()));
        assertTrue(stderr().contains("rlvm: unknown checker setting -X verdict=unknown-setting"), stderr());
        assertTrue(stderr().contains("try 'rlvm --help'"), stderr());
        assertFalse(stderr().contains("internal error"), stderr());
        // The status base of the arena run line applies to it like to every status: 64 is not 10 / 11 / 12.
        errBytes.reset();
        assertEquals(ExitCode.USAGE, run(env("RLVM_STATUS_BASE", "10"), "-X", "verdict=unknown-setting", export.toString()));
        // Any other exception of the checker stays an internal error.
        assertEquals(ExitCode.INTERNAL_ERROR, run(env(), "-X", "verdict=garbage", export.toString()));
    }

    @Test
    void quiet() {
        assertEquals(ExitCode.DECLINE, run(env(), "--checker", "placeholder", "-q", export.toString()));
        assertEquals("", stderr());
        assertEquals(ExitCode.DECLINE, run(env(), "--checker", "placeholder", export.toString()));
        assertTrue(stderr().contains("placeholder"), stderr());
    }

    // ------------------------------------------------------------------ informational modes

    @Test
    void versionAndHelp() {
        assertEquals(0, run(env(), "--version"));
        String v = stdout();
        assertTrue(v.startsWith("rlvm 0."), v);
        assertTrue(v.contains("runtime: jvm"), v);
        assertTrue(v.contains("test-plugin"), v);
        assertTrue(v.contains("placeholder"), v);
        assertFalse(v.contains("unknown"), "build.properties must be filtered by Maven: " + v);
        // What the executable is (on a JVM: the jars; an image carries scripts/build.sh's text) and what a
        // run without options does (Checker.defaults() of the checker that would run), each on one line.
        assertTrue(v.contains("\nbuild: jars on a JVM"), v);
        assertTrue(v.endsWith("default: the test double declines\n"), v);
        outBytes.reset();
        assertEquals(0, run(env("IN", export.toString()), "-V"), "IN alone does not make --version a check");
        assertEquals(null, TestPluginChecker.lastExport);
        outBytes.reset();
        assertEquals(0, run(env(), "--help"));
        assertTrue(stdout().startsWith("Usage: rlvm"), stdout());
        assertTrue(stdout().contains("--threads"), stdout());
        // The checker that would run adds its own -X settings (Checker.usage()) under its name ...
        assertTrue(stdout().contains("Settings of the checker 'test-plugin' (-X KEY[=VALUE]):\n"
                + "  verdict=accept|reject|decline   what this test double answers\n"), stdout());
        // ... a checker without any (the placeholder) adds nothing, and an unknown name does not break --help.
        outBytes.reset();
        assertEquals(0, run(env(), "--help", "--checker", "placeholder"));
        assertTrue(stdout().startsWith("Usage: rlvm") && !stdout().contains("Settings of the checker"), stdout());
        outBytes.reset();
        assertEquals(0, run(env(), "--help", "--checker", "no-such-checker"));
        assertTrue(stdout().startsWith("Usage: rlvm") && !stdout().contains("Settings of the checker"), stdout());
    }

    @Test
    void informationalModesRefuseAnExport() {
        // Otherwise `rlvm --version "$IN"` as an arena run command would accept every test.
        String in = export.toString();
        assertEquals(ExitCode.USAGE, run(env(), "--version", in));
        assertEquals(ExitCode.USAGE, run(env(), in, "--help"));
        assertEquals(ExitCode.USAGE, run(env(), "--truffle-probe", in));
        assertEquals(ExitCode.USAGE, run(env(), "--protocol-selftest", "accept", in));
        assertEquals(ExitCode.USAGE, run(env(), "--protocol-selftest", "accept", "--version"));
        assertEquals("", stdout());
    }

    // ------------------------------------------------------------------ checker selection

    @Test
    void serviceLoadedCheckerIsTheDefault() throws Exception {
        List<Checker> all = Checkers.all();
        assertEquals("test-plugin", all.get(0).name());
        assertEquals(PlaceholderChecker.NAME, all.get(all.size() - 1).name());
        assertEquals("test-plugin", Checkers.select(null).name());
        assertEquals(PlaceholderChecker.NAME, Checkers.select("placeholder").name());
        assertThrows(UsageException.class, () -> Checkers.select("nonesuch"));
        assertEquals(ExitCode.USAGE, run(env(), "--checker", "nonesuch", export.toString()));
        assertTrue(stderr().contains("available: test-plugin, placeholder"), stderr());
    }

    @Test
    void injectedCheckerIsUsed() {
        Checker accepting = new Checker() {
            @Override
            public String name() {
                return "injected";
            }

            @Override
            public Verdict check(Path e, Options o) {
                return Verdict.ACCEPT;
            }
        };
        assertEquals(ExitCode.ACCEPT, Main.run(new String[] {export.toString()}, env(), out, err, accepting));
        assertEquals(null, TestPluginChecker.lastExport);
    }

    // ------------------------------------------------------------------ status base

    @Test
    void statusBase() {
        assertEquals(0, Main.statusBase(null));
        assertEquals(0, Main.statusBase(""));
        assertEquals(0, Main.statusBase("0"));
        assertEquals(10, Main.statusBase("10"));
        assertEquals(100, Main.statusBase("100"));
        for (String bad : List.of("ten", "-1", "101", "1e1", " 10")) {
            assertThrows(IllegalArgumentException.class, () -> Main.statusBase(bad), bad);
        }
        // With base 10 the verdicts are 10, 11, 12: none collides with what a failing runtime exits
        // with on its own (1 for a VM that cannot start, 3 for ExitOnOutOfMemoryError).
        for (Verdict v : Verdict.values()) {
            int status = Main.statusBase("10") + v.exitCode();
            assertTrue(status >= 10 && status <= 12, v + " -> " + status);
        }
    }

    // ------------------------------------------------------------------ self-test modes

    @Test
    void selfTestModes() {
        for (String mode : SelfTestChecker.MODES) {
            if (mode.equals("oom-real")) {
                continue; // needs its own VM with a small heap: bench/rlvm/run.sh --selftest
            }
            int code = run(env(), "--protocol-selftest", mode, "--stack-size", "1m");
            assertEquals(SelfTestChecker.expectedExitCode(mode), code, mode);
            if (code == ExitCode.ACCEPT) {
                assertEquals("accept", mode, "only the accept mode may produce status 0");
            }
        }
        assertEquals(ExitCode.USAGE, run(env(), "--protocol-selftest", "bogus"));
        assertEquals(ExitCode.USAGE, run(env(), "--protocol-selftest"));
    }

    @Test
    void selfTestIsNotReachableThroughTheEnvironmentOrTheCheckerOption() {
        String in = export.toString();
        assertEquals(ExitCode.USAGE, run(env(), "--checker", "selftest:accept", in));
        // No environment variable selects a checker or a self-test.
        assertEquals(ExitCode.DECLINE, run(env("RLVM_CHECKER", "selftest:accept", "RLVM_PROTOCOL_SELFTEST", "accept"), in));
    }
}
