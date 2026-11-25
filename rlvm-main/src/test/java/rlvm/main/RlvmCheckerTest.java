package rlvm.main;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import org.junit.jupiter.api.Test;

import rlvm.cli.Checker;
import rlvm.cli.LauncherAccess;
import rlvm.driver.RunConfig;
import rlvm.load.LoadOptions;

/** The checker as the launcher sees it: registration, priority, option mapping. */
class RlvmCheckerTest {

    @Test
    void registeredAsTheDefaultChecker() throws Exception {
        boolean found = false;
        for (Checker c : ServiceLoader.load(Checker.class)) {
            if (c instanceof RlvmChecker) {
                found = true;
                assertEquals("rlvm", c.name());
                assertEquals(100, c.priority());
            }
        }
        assertTrue(found, "META-INF/services/rlvm.cli.Checker must name rlvm.main.RlvmChecker");
        List<String> names = LauncherAccess.checkerNames();
        assertEquals("rlvm", names.get(0), "the launcher prefers the kernel to its placeholder: " + names);
        assertEquals("placeholder", names.get(names.size() - 1));
        assertEquals("rlvm", LauncherAccess.defaultCheckerName());
    }

    @Test
    void versionListsTheKernelAndItsEngines() {
        LauncherAccess.Result r = LauncherAccess.run(new HashMap<>(), "--version");
        assertEquals(0, r.status, r.toString());
        assertTrue(r.out.contains("rlvm"), r.out);
        assertTrue(r.out.contains("RLVM kernel"), r.out);
        assertTrue(r.out.contains("reference*"), "the reference engine must be listed: " + r.out);
        assertTrue(new RlvmChecker().description().contains("engines: "));
    }

    /**
     * The single executable says what a run without options is (OWNER-NOTES 16): one line made from
     * the constants that decide it. On this module's class path the reference kernel is the only
     * engine; a launcher that has {@code Checker.defaults()} prints the line as {@code default: ...}.
     */
    @Test
    void defaultsLineSaysWhatARunWithoutOptionsDoes() throws Exception {
        String d = new RlvmChecker().defaults();
        assertEquals("engine reference, policy practical; one worker per CPU, at most 8; a file of 8 MiB or more is "
                + "checked while it is read (pipelined), with 2 scanner threads from 256 MiB", d);
        boolean launcherPrintsDefaults;
        try {
            rlvm.cli.Checker.class.getMethod("defaults");
            launcherPrintsDefaults = true;
        } catch (NoSuchMethodException e) {
            launcherPrintsDefaults = false;
        }
        LauncherAccess.Result r = LauncherAccess.run(new HashMap<>(), "--version");
        assertEquals(launcherPrintsDefaults, r.out.contains("\ndefault: " + d + "\n"), r.out);
    }

    @Test
    void launcherOptionsBecomeARunConfig() {
        RlvmChecker checker = new RlvmChecker();
        Path export = Path.of("/some/export.ndjson");
        RunConfig auto = checker.configure(export, 0, false, 512L << 20, null);
        assertEquals(export, auto.input);
        assertEquals(RlvmChecker.autoWorkers(Runtime.getRuntime().availableProcessors()), auto.workers,
                "automatic is decided here and passed on like an explicit count (LoadDefaultsTest)");
        assertEquals(512L << 20, auto.deepStackBytes);
        assertTrue(auto.workerStackBytes <= 512L << 20);
        assertEquals(LoadOptions.Mode.BATCH, auto.loadMode);

        Map<String, String> extra = new HashMap<>();
        extra.put("session-mib", "8");
        extra.put("no-recheck", "");
        RunConfig c = checker.configure(export, 4, true, 16L << 20, extra);
        assertEquals(4, c.workers);
        assertEquals(16L << 20, c.deepStackBytes);
        assertEquals(16L << 20, c.workerStackBytes, "a worker never gets more stack than --stack-size");
        assertEquals(8L << 20, c.sessionBytes);
        assertFalse(c.recheckRejections);

        // The driver's own settings by name pass through (RunConfig.knows / set); its refusal is a usage error.
        Map<String, String> knobs = new HashMap<>();
        knobs.put("unit-mib", "2048");
        knobs.put("no-fallback", "true");
        knobs.put("publish-units", "512");
        RunConfig k = checker.configure(export, 1, false, 0, knobs);
        assertEquals(2048L << 20, k.unitBytes);
        assertFalse(k.referenceFallback);
        assertEquals(512, k.publishEveryUnits);
        knobs.put("unit-mib", "lots");
        assertTrue(assertThrows(UsageError.class, () -> checker.configure(export, 1, false, 0, knobs)).getMessage()
                .contains("unit-mib=lots"));

        RunConfig noStack = checker.configure(export, 2, false, 0, null);
        assertEquals(new RunConfig().deepStackBytes, noStack.deepStackBytes);

        assertThrows(UsageError.class, () -> checker.configure(export, -1, false, 0, null));
        extra.put("bogus", "1");
        assertThrows(UsageError.class, () -> checker.configure(export, 1, false, 0, extra));
    }

    /**
     * An unknown -X key, a malformed value or an engine that is not on the class path must stop the run
     * before anything is checked, with the launcher's usage status 64 (it was 3 while
     * rlvm.cli.UsageException was package-private); it can never become a verdict.
     */
    @Test
    void unknownSettingEndsTheLauncherWithTheUsageStatus() throws Exception {
        Path export = java.nio.file.Files.createTempFile("rlvm-main-test", ".ndjson");
        try {
            // Not an export at all: if the run got as far as reading it, the status would be 1 or 2.
            java.nio.file.Files.writeString(export, "this is not an export\n");
            LauncherAccess.Result r = LauncherAccess.run(new HashMap<>(), "-X", "enigne=reference", export.toString());
            assertEquals(64, r.status, r.toString());
            assertTrue(r.err.contains("unknown checker setting -X enigne=reference"), r.err);
            assertFalse(r.err.contains("internal error"), r.err);

            r = LauncherAccess.run(new HashMap<>(), "-X", "load=sideways", export.toString());
            assertEquals(64, r.status, r.toString());
            assertTrue(r.err.contains("-X load=sideways"), r.err);

            r = LauncherAccess.run(new HashMap<>(), "-X", "engine=no-such-engine", export.toString());
            assertEquals(64, r.status, r.toString());
            assertTrue(r.err.contains("no engine 'no-such-engine'"), r.err);

            // Differential mode with nothing to compare (only the reference kernel is on this class path).
            r = LauncherAccess.run(new HashMap<>(), "-X", "engine=diff", export.toString());
            assertEquals(64, r.status, r.toString());
            assertTrue(r.err.contains("needs an engine besides the reference kernel"), r.err);

            // The arena run line's status base moves 64 like every status: never 10, 11 or 12.
            Map<String, String> env = new HashMap<>();
            env.put("RLVM_STATUS_BASE", "10");
            r = LauncherAccess.run(env, "-X", "enigne=reference", export.toString());
            assertEquals(64, r.status, "Main.run returns the status without the base: " + r);
        } finally {
            java.nio.file.Files.deleteIfExists(export);
        }
    }
}
