package rlvm.main;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import rlvm.api.EngineConfig;
import rlvm.driver.OrderPolicy;
import rlvm.driver.RunConfig;
import rlvm.export.ExportReader;
import rlvm.load.LoadOptions;

class MainOptionsTest {

    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static String usage(String... kv) {
        return assertThrows(UsageError.class, () -> MainOptions.parse(map(kv))).getMessage();
    }

    @Test
    void defaultsAreTheVerticalSlice() {
        for (Map<String, String> none : java.util.Arrays.asList(null, new HashMap<String, String>())) {
            MainOptions o = MainOptions.parse(none);
            assertNull(o.engine);
            assertFalse(o.differential);
            assertEquals(LoadOptions.Mode.BATCH, o.loadMode);
            assertEquals(ExportReader.Io.BUFFERED, o.io);
            assertEquals(0, o.scanThreads);
            assertTrue(o.recheck && o.truffle && o.proofBlocks);
            assertEquals(EngineConfig.KernelProfile.LEAN_4_36, o.profile);
            assertNull(o.stopAfter);
            assertNull(o.statsFile);
            assertTrue(o.truffleOptions.isEmpty());
        }
    }

    @Test
    void everyKnownKey() {
        MainOptions o = MainOptions.parse(map("engine", "machine", "load", "pipelined", "order", "namespace",
                "session-mib", "32", "memory-mib", "4096", "scan-threads", "3", "io", "mapped", "stop-after", "env",
                "stats-file", "/tmp/s.json", "no-recheck", "", "no-truffle", null, "no-proof-blocks", "true",
                "profile", "4.37", "engine.CompilerThreads", "1", "engine.BackgroundCompilation", ""));
        assertEquals("machine", o.engine);
        assertEquals(LoadOptions.Mode.PIPELINED, o.loadMode);
        assertEquals(OrderPolicy.Kind.NAMESPACE_WINDOW, o.order);
        assertEquals(32L << 20, o.sessionBytes);
        assertEquals(4096L << 20, o.memoryLimitBytes);
        assertEquals(3, o.scanThreads);
        assertEquals(ExportReader.Io.MAPPED, o.io);
        assertEquals("env", o.stopAfter);
        assertEquals(Path.of("/tmp/s.json"), o.statsFile);
        assertFalse(o.recheck);
        assertFalse(o.truffle);
        assertFalse(o.proofBlocks);
        assertEquals(EngineConfig.KernelProfile.LEAN_4_37, o.profile);
        assertEquals(map("engine.CompilerThreads", "1", "engine.BackgroundCompilation", ""), o.truffleOptions);

        RunConfig c = new RunConfig();
        long deep = c.deepStackBytes;
        o.applyTo(c);
        assertEquals(LoadOptions.Mode.PIPELINED, c.loadMode);
        assertEquals(OrderPolicy.Kind.NAMESPACE_WINDOW, c.order);
        assertEquals(32L << 20, c.sessionBytes);
        assertEquals(4096L << 20, c.memoryLimitBytes);
        assertEquals(3, c.scanThreads);
        assertFalse(c.proofBlocks);
        assertFalse(c.recheckRejections);
        assertFalse(c.truffleTier);
        assertFalse(c.differential);
        assertEquals("env", c.stopAfter);
        assertEquals(Path.of("/tmp/s.json"), c.statsFile);
        assertEquals(EngineConfig.KernelProfile.LEAN_4_37, c.profile);
        assertEquals(deep, c.deepStackBytes, "fields without a setting keep the driver's defaults");

        assertEquals("reference", MainOptions.parse(map("engine", "reference")).engine);
        MainOptions diff = MainOptions.parse(map("engine", "diff"));
        assertTrue(diff.differential);
        assertNull(diff.engine);
        assertEquals(ExportReader.Io.AUTO, MainOptions.parse(map("io", "auto")).io);
        assertEquals("load", MainOptions.parse(map("stop-after", "load")).stopAfter);
    }

    @Test
    void settingsThatAreNotGivenKeepTheDriversDefaults() {
        RunConfig untouched = new RunConfig();
        RunConfig c = new RunConfig();
        MainOptions.parse(null).applyTo(c);
        assertEquals(untouched.sessionBytes, c.sessionBytes);
        assertEquals(untouched.memoryLimitBytes, c.memoryLimitBytes);
        assertEquals(untouched.order, c.order);
        assertEquals(LoadOptions.Mode.BATCH, c.loadMode, "batch is this checker's default whatever the driver's is");
        assertTrue(c.recheckRejections);
        assertTrue(c.proofBlocks);
    }

    /** Keys an engine declares are accepted and collected for EngineProvider.factory(Map); others are not. */
    @Test
    void engineSettingsAreAcceptedOnlyWhenAProviderDeclaresThem() {
        java.util.Set<String> declared = java.util.Set.of("levels", "machine.tier");
        MainOptions o = MainOptions.parse(map("levels", "official", "machine.tier", "true", "engine.CompilerThreads", "1",
                "load", "batch"), declared);
        assertEquals(map("levels", "official", "machine.tier", "true", "engine.CompilerThreads", "1"), o.engineOptions);
        assertEquals(LoadOptions.Mode.BATCH, o.loadMode);
        assertTrue(MainOptions.parse(map("load", "batch"), declared).engineOptions.isEmpty());

        // Without a provider that declares it, the same key is a usage error ...
        assertTrue(usage("levels", "official").contains("unknown checker setting -X levels=official"));
        // ... and the message lists what the engines on the class path do accept.
        String msg = assertThrows(UsageError.class, () -> MainOptions.parse(map("level", "official"), declared)).getMessage();
        assertTrue(msg.contains("engine settings: levels, machine.tier"), msg);
        // The wiring's own keys keep their meaning even if an engine declares them.
        MainOptions own = MainOptions.parse(map("load", "pipelined"), java.util.Set.of("load"));
        assertEquals(LoadOptions.Mode.PIPELINED, own.loadMode);
        assertTrue(own.engineOptions.isEmpty());
    }

    /** The driver's own knobs: a key it knows is kept and set by the driver; its refusal is a usage error. */
    @Test
    void driverSettingsAreOfferedToTheDriverBeforeTheyAreUnknown() {
        Map<String, String> seen = new HashMap<>();
        MainOptions.DriverSettings fake = new MainOptions.DriverSettings() {
            @Override
            public boolean knows(String key) {
                return key.equals("unit-mib") || key.equals("no-fallback") || key.equals("load");
            }

            @Override
            public void set(RunConfig config, String key, String value) {
                if (key.equals("unit-mib") && !value.matches("[0-9]+")) {
                    throw new IllegalArgumentException("unit-mib=" + value + ": not a number");
                }
                seen.put(key, value);
                if (key.equals("unit-mib")) {
                    config.unitBytes = Long.parseLong(value) << 20;
                }
            }
        };
        MainOptions o = MainOptions.parse(map("unit-mib", "2048", "no-fallback", "true", "session-mib", "8"),
                java.util.Set.of(), fake);
        assertEquals(map("unit-mib", "2048", "no-fallback", "true"), o.driverOptions);
        RunConfig c = new RunConfig();
        o.applyTo(c);
        assertEquals(2048L << 20, c.unitBytes);
        assertEquals(8L << 20, c.sessionBytes);
        assertEquals(map("unit-mib", "2048", "no-fallback", "true"), seen);

        // The wiring's own keys are never offered to the driver, whatever it says it knows.
        MainOptions own = MainOptions.parse(map("load", "pipelined"), java.util.Set.of(), fake);
        assertTrue(own.driverOptions.isEmpty());
        assertEquals(LoadOptions.Mode.PIPELINED, own.loadMode);

        // A value the driver refuses is a usage error with the driver's message ...
        MainOptions bad = MainOptions.parse(map("unit-mib", "lots"), java.util.Set.of(), fake);
        String msg = assertThrows(UsageError.class, () -> bad.applyTo(new RunConfig())).getMessage();
        assertTrue(msg.contains("-X unit-mib=lots: not a number"), msg);
        // ... and a key nobody knows still is one, also with a driver that has no settings by name.
        assertThrows(UsageError.class, () -> MainOptions.parse(map("unit-mibs", "1"), java.util.Set.of(), fake));
        assertTrue(usage("unit-mib", "2048").contains("unknown checker setting -X unit-mib=2048"));
    }

    @Test
    void unknownOrMalformedSettingsAreUsageErrorsNeverIgnored() {
        assertTrue(usage("enigne", "reference").contains("unknown checker setting -X enigne=reference"));
        assertTrue(usage("threads", "4").contains("unknown"));
        assertTrue(usage("no-rechek", "").contains("unknown checker setting -X no-rechek"));
        assertTrue(usage("", "x").contains("needs a key"));
        assertTrue(usage("engine", "").contains("needs a value"));
        assertTrue(usage("engine", null).contains("needs a value"));
        assertTrue(usage("load", "lazy").contains("expected batch or pipelined"));
        assertTrue(usage("order", "random").contains("expected file or namespace"));
        assertTrue(usage("profile", "4.38").contains("expected 4.36 or 4.37"));
        assertTrue(usage("io", "mmap").contains("expected buffered, mapped or auto"));
        assertTrue(usage("stop-after", "check").contains("expected load or env"));
        assertTrue(usage("session-mib", "many").contains("not a number"));
        assertTrue(usage("session-mib", "0").contains("out of range"));
        assertTrue(usage("session-mib", "-5").contains("out of range"));
        assertTrue(usage("memory-mib", "8").contains("out of range"));
        assertTrue(usage("scan-threads", "65").contains("out of range"));
        assertTrue(usage("no-recheck", "false").contains("takes no value"));
        assertTrue(usage("no-truffle", "1").contains("takes no value"));
        assertTrue(usage("stats-file", "").contains("needs a value"));
        // one bad key among good ones is still an error
        assertTrue(usage("engine", "reference", "load", "batch", "bogus", "1").contains("-X bogus=1"));
    }
}
