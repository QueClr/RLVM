package rlvm.main;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import rlvm.api.EngineFactory;
import rlvm.api.EngineProvider;

class EnginesTest {

    private static EngineProvider provider(String name, int priority, boolean reference) {
        return new EngineProvider() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public int priority() {
                return priority;
            }

            @Override
            public boolean isReference() {
                return reference;
            }

            @Override
            public EngineFactory factory() {
                return (store, config) -> null;
            }
        };
    }

    @Test
    void primaryIsTheBestNonReferenceEngineElseTheReference() {
        EngineProvider ref = provider("reference", 1000, true);
        EngineProvider machine = provider("machine", 200, false);
        EngineProvider toy = provider("toy", 100, false);

        assertSame(machine, Engines.primary(null, List.of(ref, toy, machine)));
        assertSame(machine, Engines.primary(null, List.of(machine, toy, ref)));
        assertSame(ref, Engines.primary(null, List.of(ref)), "alone, the reference kernel checks everything");
        assertSame(ref, Engines.reference(List.of(toy, ref, machine)));

        assertSame(toy, Engines.primary("toy", List.of(ref, toy, machine)));
        assertSame(ref, Engines.primary("reference", List.of(ref, toy, machine)));
        UsageError e = assertThrows(UsageError.class, () -> Engines.primary("machine", List.of(ref, toy)));
        assertTrue(e.getMessage().contains("no engine 'machine'"), e.getMessage());
        assertTrue(e.getMessage().contains("reference*, toy"), e.getMessage());

        assertEquals("reference*, toy, machine", Engines.names(List.of(ref, toy, machine)));
        assertEquals("none", Engines.names(List.of()));
    }

    @Test
    void aMissingOrDuplicateReferenceEngineIsAConfigurationError() {
        EngineProvider machine = provider("machine", 10, false);
        assertTrue(assertThrows(IllegalStateException.class, () -> Engines.reference(List.of(machine))).getMessage()
                .contains("no reference engine"));
        assertThrows(IllegalStateException.class, () -> Engines.reference(List.of()));
        assertThrows(IllegalStateException.class, () -> Engines.primary(null, List.of()));
        EngineProvider r1 = provider("reference", 1, true);
        EngineProvider r2 = provider("ref2", 1, true);
        assertTrue(assertThrows(IllegalStateException.class, () -> Engines.reference(List.of(r1, machine, r2)))
                .getMessage().contains("two reference engines"));
    }

    /** rlvm-ref is a runtime dependency of this module: ServiceLoader must find its provider. */
    @Test
    void theReferenceKernelIsOnTheClassPath() {
        List<EngineProvider> all = Engines.providers();
        EngineProvider ref = Engines.reference();
        assertTrue(ref.isReference());
        assertEquals("reference", ref.name());
        assertTrue(all.stream().anyMatch(p -> p.name().equals("reference")));
        // with no machine engine published, the reference kernel is the primary engine
        EngineProvider primary = Engines.primary(null);
        assertTrue(primary == ref || !primary.isReference() || primary.name().equals(ref.name()));
        assertEquals("reference", Engines.primary("reference").name());
        assertThrows(UsageError.class, () -> Engines.primary("no-such-engine"));
    }

    /**
     * An engine below the bar (the machine registers with priority 10 until it has passed its gates) is
     * in the distribution as a candidate: named or compared, never the default.
     */
    @Test
    void anEngineBelowTheDefaultPriorityRunsOnlyWhenNamed() {
        assertEquals(100, Engines.DEFAULT_ENGINE_MIN_PRIORITY);
        EngineProvider ref = provider("reference", 1000, true);
        EngineProvider young = provider("machine", 10, false);
        EngineProvider edge = provider("edge", 99, false);
        assertSame(ref, Engines.primary(null, List.of(young, ref)), "priority 10 is not eligible as the default");
        assertSame(ref, Engines.primary(null, List.of(ref, edge, young)));
        assertSame(young, Engines.primary("machine", List.of(young, ref)), "by name it runs");
        assertSame(edge, Engines.underTest(List.of(young, ref, edge)), "the differential mode compares it");
        assertSame(young, Engines.underTest(List.of(young, ref)));
        EngineProvider ready = provider("machine", 100, false);
        assertSame(ready, Engines.primary(null, List.of(ready, ref, edge)), "at the bar it is the default");
    }

    /** -X engine=diff without the test kit: the best non-reference engine, compared by the driver. */
    @Test
    void differentialModeNeedsAnEngineBesidesTheReference() {
        EngineProvider ref = provider("reference", 1000, true);
        EngineProvider machine = provider("machine", 10, false);
        EngineProvider toy = provider("toy", 5, false);
        assertSame(machine, Engines.underTest(List.of(ref, toy, machine)));
        UsageError e = assertThrows(UsageError.class, () -> Engines.underTest(List.of(ref)));
        assertTrue(e.getMessage().contains("needs an engine besides the reference kernel"), e.getMessage());
        assertTrue(e.getMessage().contains("reference*"), e.getMessage());
    }

    /**
     * The test kit's provider "diff" (priority Integer.MIN_VALUE, not the reference) may be on the class
     * path: it is used only when named, never as the default engine and never as its own engine under test.
     */
    @Test
    void theDifferentialHarnessIsNeverTheDefaultEngine() {
        EngineProvider ref = provider("reference", 1000, true);
        EngineProvider diff = provider("diff", Integer.MIN_VALUE, false);
        EngineProvider machine = provider("machine", 100, false);
        assertSame(ref, Engines.primary(null, List.of(diff, ref)), "the harness alone does not replace the reference");
        assertSame(machine, Engines.primary(null, List.of(diff, ref, machine)));
        assertSame(diff, Engines.primary("diff", List.of(diff, ref)), "by name it is an engine like any other");
        assertSame(diff, Engines.find(Engines.DIFF, List.of(ref, diff)));
        assertThrows(UsageError.class, () -> Engines.underTest(List.of(diff, ref)));
        assertSame(machine, Engines.underTest(List.of(diff, ref, machine)));
    }

    /** A provider with settings of its own: declared keys, the values it is handed, a refused value. */
    private static final class Tunable implements EngineProvider {
        final EngineFactory plain = (store, config) -> null;
        final EngineFactory official = (store, config) -> null;
        Map<String, String> seen;

        @Override
        public String name() {
            return "tunable";
        }

        @Override
        public int priority() {
            return 1;
        }

        @Override
        public boolean isReference() {
            return true;
        }

        @Override
        public EngineFactory factory() {
            return plain;
        }

        @Override
        public Set<String> optionKeys() {
            return Set.of("levels", "tunable.depth");
        }

        @Override
        public EngineFactory factory(Map<String, String> options) {
            seen = options;
            String v = options.get("levels");
            if (v == null || v.equals("complete")) {
                return plain;
            }
            if (v.equals("official")) {
                return official;
            }
            throw new IllegalArgumentException("-X levels=" + v + ": expected official or complete");
        }
    }

    @Test
    void engineSettingsReachTheProviderThatDeclaresThem() {
        Tunable t = new Tunable();
        EngineProvider old = provider("machine", 10, false);
        assertEquals(Set.of("levels", "tunable.depth"), Engines.optionKeys(List.of(old, t)));
        assertTrue(Engines.optionKeys(List.of(old)).isEmpty());

        assertSame(t.plain, Engines.factory(t, Map.of()), "no settings: the plain factory()");
        assertEquals(null, t.seen, "factory(Map) is not called without settings");
        assertSame(t.official, Engines.factory(t, Map.of("levels", "official", "engine.CompilerThreads", "1")));
        assertEquals("official", t.seen.get("levels"));
        assertThrows(UnsupportedOperationException.class, () -> t.seen.put("x", "y"), "the map is read-only");
        // A provider written before the option channel ignores settings.
        assertTrue(Engines.factory(old, Map.of("levels", "official")) != null);

        UsageError e = assertThrows(UsageError.class, () -> Engines.factory(t, Map.of("levels", "sloppy")));
        assertTrue(e.getMessage().contains("engine 'tunable'"), e.getMessage());
        assertTrue(e.getMessage().contains("-X levels=sloppy: expected official or complete"), e.getMessage());
    }

    /** A provider with preferred run settings (EngineProvider.runDefaults), which may depend on its own settings. */
    private static EngineProvider preferring(String name, Map<String, String> always, Map<String, String> whenWarm) {
        EngineProvider base = provider(name, 10, false);
        return new EngineProvider() {
            @Override
            public String name() {
                return base.name();
            }

            @Override
            public int priority() {
                return base.priority();
            }

            @Override
            public boolean isReference() {
                return false;
            }

            @Override
            public EngineFactory factory() {
                return base.factory();
            }

            @Override
            public Set<String> optionKeys() {
                return Set.of("machine.switches");
            }

            @Override
            public Map<String, String> runDefaults(Map<String, String> options) {
                return "warm".equals(options.get("machine.switches")) ? whenWarm : always;
            }
        };
    }

    @Test
    void runDefaultsOfThePrimaryEngineAreSettingsOfTheWiringOrTheDriverOnly() {
        EngineProvider old = provider("machine", 10, false);
        assertTrue(Engines.runDefaults(old, Map.of(), Set.of()).isEmpty(), "a provider written before the method prefers nothing");
        assertTrue(Engines.runDefaults(old, null, null).isEmpty());

        EngineProvider m = preferring("machine", Map.of(), Map.of("session-mib", "4", "memory-mib", "8192"));
        Set<String> keys = Engines.optionKeys(List.of(m));
        assertTrue(Engines.runDefaults(m, Map.of(), keys).isEmpty());
        assertEquals(Map.of("session-mib", "4", "memory-mib", "8192"),
                Engines.runDefaults(m, Map.of("machine.switches", "warm"), keys), "the defaults may depend on the engine's settings");

        // The engine selection, a Truffle option and an engine's own key cannot be defaulted: a
        // broken provider is an internal error, not a usage error of the caller.
        for (String bad : new String[] {"engine", "engine.CompilerThreads", "machine.switches", ""}) {
            EngineProvider broken = preferring("machine", Map.of(bad, "x"), Map.of());
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> Engines.runDefaults(broken, Map.of(), keys), bad);
            assertTrue(e.getMessage().contains("engine provider 'machine'"), e.getMessage());
        }
    }

    @Test
    void theCommandLineWinsOverTheRunDefaultsOfTheEngine() {
        EngineProvider m = preferring("machine", Map.of("session-mib", "4"), Map.of());
        Set<String> keys = Engines.optionKeys(List.of(m));
        MainOptions.DriverSettings none = MainOptions.DriverSettings.NONE;

        // Nothing preferred: the parsed settings object itself.
        MainOptions plain = MainOptions.parse(Map.of(), keys, none);
        assertSame(plain, RlvmChecker.withRunDefaults(plain, m, Map.of(), Map.of(), keys, none));
        assertEquals(-1, plain.sessionBytes, "no default of the engine: the driver's own default stays");

        // The default applies when the command line is silent about the key ...
        Map<String, String> preferred = Engines.runDefaults(m, Map.of(), keys);
        Map<String, String> typed = Map.of("memory-mib", "2048");
        MainOptions mo = RlvmChecker.withRunDefaults(MainOptions.parse(typed, keys, none), m, preferred, typed, keys, none);
        assertEquals(4L << 20, mo.sessionBytes);
        assertEquals(2048L << 20, mo.memoryLimitBytes);
        assertEquals(Map.of("session-mib", "4"), mo.appliedRunDefaults);

        // ... and never when the user typed it.
        Map<String, String> typed2 = Map.of("session-mib", "32");
        MainOptions mo2 = RlvmChecker.withRunDefaults(MainOptions.parse(typed2, keys, none), m, preferred, typed2, keys, none);
        assertEquals(32L << 20, mo2.sessionBytes);
        assertTrue(mo2.appliedRunDefaults.isEmpty());

        // A default the checker refuses (unknown key, malformed value) is the provider's fault: not a UsageError.
        for (Map<String, String> bad : List.of(Map.of("session-mib", "many"), Map.of("no-such-setting", "1"))) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> RlvmChecker.withRunDefaults(plain, m, bad, Map.of(), keys, none), bad.toString());
            assertTrue(e.getMessage().contains("prefers a run setting the checker refuses"), e.getMessage());
        }
    }
}
