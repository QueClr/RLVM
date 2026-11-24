package rlvm.main;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rlvm.api.EngineFactory;
import rlvm.api.EngineProvider;
import rlvm.cli.LauncherAccess;

/**
 * {@code -X policy=practical|strict} (OWNER-NOTES 13). practical, the default: the default engine by
 * priority and none of the exactness-only checks. strict: the reference engine whatever the
 * priorities say, and the policy handed to every engine that declares the key {@code policy}. An
 * unknown value, an engine that is not the reference kernel under strict and {@code engine=diff}
 * under strict are usage errors (status 64), never a verdict.
 */
class PolicyTest {

    @TempDir
    Path dir;

    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    /** A provider that records the settings its factory was made with. */
    private static class Fake implements EngineProvider {
        final String name;
        final int priority;
        final boolean reference;
        final Set<String> keys;
        Map<String, String> seen;

        Fake(String name, int priority, boolean reference, String... keys) {
            this.name = name;
            this.priority = priority;
            this.reference = reference;
            this.keys = Set.of(keys);
        }

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
        public Set<String> optionKeys() {
            return keys;
        }

        @Override
        public EngineFactory factory() {
            seen = Map.of();
            return (store, config) -> null;
        }

        @Override
        public EngineFactory factory(Map<String, String> options) {
            seen = options;
            return (store, config) -> null;
        }
    }

    @Test
    void practicalIsTheDefaultAndBothValuesParse() {
        assertFalse(MainOptions.parse(null).strict);
        assertFalse(MainOptions.parse(map()).strict);
        assertEquals("practical", MainOptions.parse(map()).policyName());
        assertFalse(MainOptions.parse(map("policy", "practical")).strict);
        MainOptions strict = MainOptions.parse(map("policy", "strict"));
        assertTrue(strict.strict);
        assertEquals("strict", strict.policyName());
        assertNull(strict.engine, "the engine of the strict mode is chosen where the providers are known");
        // Nothing but the two names is a policy.
        for (String bad : new String[] {"lenient", "STRICT", "official", "true"}) {
            String msg = assertThrows(UsageError.class, () -> MainOptions.parse(map("policy", bad))).getMessage();
            assertTrue(msg.contains("-X policy=" + bad + ": expected practical or strict"), msg);
        }
        assertTrue(assertThrows(UsageError.class, () -> MainOptions.parse(map("policy", ""))).getMessage()
                .contains("needs a value"));
        assertTrue(assertThrows(UsageError.class, () -> MainOptions.parse(map("policy", null))).getMessage()
                .contains("needs a value"));
        // The strict mode is one engine: a comparison of two contradicts it.
        String msg = assertThrows(UsageError.class, () -> MainOptions.parse(map("policy", "strict", "engine", "diff")))
                .getMessage();
        assertTrue(msg.contains("-X policy=strict") && msg.contains("engine=diff"), msg);
        assertTrue(MainOptions.parse(map("policy", "practical", "engine", "diff")).differential);
    }

    /** strict = the reference kernel whatever the priorities say; practical = the default engine by priority. */
    @Test
    void strictChecksWithTheReferenceEngineWhateverThePriorities() throws Exception {
        Path export = dir.resolve("x.ndjson");
        Files.writeString(export, "{}\n");
        RlvmChecker checker = new RlvmChecker();
        Fake ref = new Fake("reference", 1000, true);
        Fake machine = new Fake("machine", 100, false);   // at the bar: the default engine of a practical run
        List<EngineProvider> all = List.of(machine, ref);

        RlvmChecker.Setup practical = checker.setup(export, 1, false, 0, map(), all, System.err, true);
        assertSame(machine, practical.primary, "practical: the default engine by priority");
        assertFalse(practical.mo.strict);
        practical = checker.setup(export, 1, false, 0, map("policy", "practical"), all, System.err, true);
        assertSame(machine, practical.primary);

        RlvmChecker.Setup strict = checker.setup(export, 1, false, 0, map("policy", "strict"), all, System.err, true);
        assertSame(ref, strict.primary, "strict: the reference engine");
        assertSame(strict.referenceFactory, strict.primaryFactory,
                "the same factory object: the driver then confirms a rejection with one cold run");
        assertTrue(strict.mo.strict);
        // Naming the reference engine with it is allowed; naming another engine is a contradiction.
        strict = checker.setup(export, 1, false, 0, map("policy", "strict", "engine", "reference"), all, System.err, true);
        assertSame(ref, strict.primary);
        String msg = assertThrows(UsageError.class,
                () -> checker.setup(export, 1, false, 0, map("policy", "strict", "engine", "machine"), all, System.err, true))
                .getMessage();
        assertTrue(msg.contains("-X policy=strict") && msg.contains("-X engine=machine"), msg);
        // An engine that does not exist is still the usual error.
        assertTrue(assertThrows(UsageError.class,
                () -> checker.setup(export, 1, false, 0, map("policy", "strict", "engine", "nope"), all, System.err, true))
                .getMessage().contains("no engine 'nope'"));
        // With the reference kernel alone both policies check with it.
        assertSame(ref, checker.setup(export, 1, false, 0, map(), List.of(ref), System.err, true).primary);
        assertSame(ref, checker.setup(export, 1, false, 0, map("policy", "strict"), List.of(ref), System.err, true).primary);
    }

    /** An engine that declares the key "policy" is told the policy of every run; others see nothing. */
    @Test
    void enginesThatDeclareThePolicyKeyAreToldThePolicy() throws Exception {
        Path export = dir.resolve("x.ndjson");
        Files.writeString(export, "{}\n");
        RlvmChecker checker = new RlvmChecker();
        Fake ref = new Fake("reference", 1000, true, "policy", "levels");
        Fake machine = new Fake("machine", 100, false, "machine.switches");
        List<EngineProvider> all = List.of(machine, ref);

        checker.setup(export, 1, false, 0, map("policy", "strict"), all, System.err, true);
        assertEquals(map("policy", "strict"), ref.seen, "the reference kernel switches its exactness-only checks on");
        checker.setup(export, 1, false, 0, map(), all, System.err, true);
        assertEquals(map("policy", "practical"), ref.seen, "the effective policy, also when nobody typed it");
        assertEquals(map("policy", "practical"), machine.seen, "every engine of the run gets the run's engine settings");
        checker.setup(export, 1, false, 0, map("policy", "practical", "levels", "official"), all, System.err, true);
        assertEquals(map("policy", "practical", "levels", "official"), ref.seen);

        // No provider declares the key: nothing is handed over, the policy is the wiring's alone.
        Fake plainRef = new Fake("reference", 1000, true);
        RlvmChecker.Setup s = checker.setup(export, 1, false, 0, map("policy", "strict"), List.of(plainRef), System.err, true);
        assertEquals(Map.of(), plainRef.seen);
        assertTrue(s.mo.engineOptions.isEmpty());

        // A provider cannot make the policy one of its run defaults.
        EngineProvider pushy = new Fake("machine", 100, false) {
            @Override
            public Map<String, String> runDefaults(Map<String, String> options) {
                return Map.of("policy", "strict");
            }
        };
        assertTrue(assertThrows(IllegalStateException.class, () -> Engines.runDefaults(pushy, Map.of(), Set.of()))
                .getMessage().contains("-X policy"));
    }

    private static LauncherAccess.Result launch(Path export, String... options) {
        String[] args = new String[options.length + 1];
        System.arraycopy(options, 0, args, 0, options.length);
        args[options.length] = export.toString();
        return LauncherAccess.run(new HashMap<>(), args);
    }

    /** Through the real launcher and pipeline (the engines of this module's class path: the reference kernel). */
    @Test
    void bothPoliciesRunThroughTheLauncherAndAnUnknownOneIsAUsageError() throws Exception {
        Path good = dir.resolve("accepted.ndjson");
        Files.writeString(good, PassesTest.accepted());
        Path bad = dir.resolve("rejected.ndjson");
        Files.writeString(bad, PassesTest.rejected());

        LauncherAccess.Result r = launch(good, "--threads", "2", "--stats");
        assertEquals(0, r.status, r.toString());
        assertTrue(r.err.contains(" policy=practical "), r.err);
        r = launch(good, "--threads", "2", "--stats", "-X", "policy=practical");
        assertEquals(0, r.status, r.toString());
        assertTrue(r.err.contains(" policy=practical "), r.err);
        r = launch(good, "--threads", "2", "--stats", "-X", "policy=strict");
        assertEquals(0, r.status, r.toString());
        assertTrue(r.err.contains(" engine=reference reference=reference policy=strict "), r.err);
        r = launch(good, "--threads", "2", "--stats", "-X", "policy=strict", "-X", "engine=reference");
        assertEquals(0, r.status, r.toString());
        for (String[] policy : new String[][] {{}, {"-X", "policy=practical"}, {"-X", "policy=strict"}}) {
            String[] args = new String[policy.length + 2];
            args[0] = "--threads";
            args[1] = "2";
            System.arraycopy(policy, 0, args, 2, policy.length);
            assertEquals(1, launch(bad, args).status, "a rejected export is rejected under every policy");
        }

        // Not an export at all: if the run got as far as reading it, the status would be 1 or 2.
        Path junk = dir.resolve("junk.ndjson");
        Files.writeString(junk, "this is not an export\n");
        r = launch(junk, "-X", "policy=lenient");
        assertEquals(64, r.status, r.toString());
        assertTrue(r.err.contains("-X policy=lenient: expected practical or strict"), r.err);
        r = launch(junk, "-X", "policy");
        assertEquals(64, r.status, "a bare -X policy has the value 'true': " + r);
        r = launch(junk, "-X", "policy=strict", "-X", "engine=diff");
        assertEquals(64, r.status, r.toString());
        r = launch(junk, "-X", "policy=strict", "-X", "engine=no-such-engine");
        assertEquals(64, r.status, r.toString());

        // rlvm --version names the default engine; rlvm --help names the option (Checker.usage() of this
        // checker) when the launcher on the class path has that method (rlvm-cli of 2026-11-24T22Z on).
        r = LauncherAccess.run(new HashMap<>(), "--version");
        assertTrue(r.out.contains("default engine: reference (policy practical"), r.out);
        assertTrue(new RlvmChecker().usage().contains("policy=practical|strict"));
        boolean launcherPrintsUsage;
        try {
            rlvm.cli.Checker.class.getMethod("usage");
            launcherPrintsUsage = true;
        } catch (NoSuchMethodException e) {
            launcherPrintsUsage = false;
        }
        r = LauncherAccess.run(new HashMap<>(), "--help");
        assertEquals(0, r.status, r.toString());
        if (launcherPrintsUsage) {
            assertTrue(r.out.contains("Settings of the checker 'rlvm'") && r.out.contains("policy=practical|strict"), r.out);
        }
    }
}
