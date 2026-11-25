package rlvm.main;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rlvm.cli.LauncherAccess;

/**
 * The safety net of the practical policy ({@code RlvmChecker.strictRecheck}; OWNER-NOTES 13 (c); finding
 * R2-F4): a REJECT of a practical run on a file in which the loader dropped a metadata node is not
 * reported - the file is checked again under {@code policy=strict} and that verdict is the run's.
 * Through the real launcher.
 *
 * <p>The net belongs to the verification ({@code -X verify=reference}). The only engine on this module's
 * class path is the reference kernel, and a run whose primary engine is the reference kernel verifies
 * unasked, so the launcher tests below are what they were. A PLAIN run of another engine has no net (the
 * maintainer's instruction of 2026-11-25): the tests at the end run the same files with a second engine
 * that is not the reference engine ({@link PassesTest.Counting}: the reference kernel behind another
 * provider), with and without the option.
 *
 * <p>Fixtures ({@code src/test/resources/net}: hand-made exports of finding R2-F4, described in
 * *agentic-note*: rep-reference-caches, section 6.1; the verdicts are those of leanchecker
 * v4.36.0):
 * <ul>
 * <li>{@code r2-mdata-beta-arg-hidden-ok}: official ACCEPT. A metadata node around a lambda in
 * argument position; after the kernel instantiated a codomain the node is on the head path of a beta
 * redex, where the official {@code cheap_beta_reduce} stops. With the node dropped the kernel reduces,
 * records another pair of terms and later rejects. No rule on the position of the node tells this
 * file from harmless ones.</li>
 * <li>{@code r2-mdata-beta-arg-plainform-bad}: official REJECT; the same file without the node.</li>
 * <li>{@code r2-mdata-beta-nofill-ok}: official ACCEPT; a node, and nothing that depends on it.</li>
 * </ul>
 */
class SafetyNetTest {

    private static final String META = "{\"meta\":{\"exporter\":{\"name\":\"lean4export\",\"version\":\"3.1.0\"},"
            + "\"format\":{\"version\":\"3.1.0\"},\"lean\":{\"githash\":\"611c117bfaf7b7ae1cfcf246ce6fba31e8f509e9\","
            + "\"version\":\"4.36.0\"}}}\n";

    private static final String NOT_REPORTED = "NOT REPORTED";

    @TempDir
    Path dir;

    private Path fixture(String name) throws Exception {
        Path f = dir.resolve(name + ".ndjson");
        try (InputStream in = SafetyNetTest.class.getResourceAsStream("/net/" + name + ".ndjson")) {
            assertNotNull(in, "fixture " + name);
            Files.copy(in, f, StandardCopyOption.REPLACE_EXISTING);
        }
        return f;
    }

    private Path text(String name, String content) throws Exception {
        Path f = dir.resolve(name + ".ndjson");
        Files.writeString(f, content);
        return f;
    }

    private static LauncherAccess.Result run(Path file, String... options) {
        String[] args = new String[options.length + 4];
        args[0] = "--threads";
        args[1] = "1";
        args[2] = "--stats";
        System.arraycopy(options, 0, args, 3, options.length);
        args[args.length - 1] = file.toString();
        return LauncherAccess.run(new HashMap<>(), args);
    }

    /** The checker's own {@code --stats} line of a run. */
    private static String statsLine(LauncherAccess.Result r) {
        String found = null;
        for (String line : r.err.split("\\R")) {
            if (line.startsWith("rlvm: stats: outcome=")) {
                assertEquals(null, found, "one stats line of the checker: " + r.err);
                found = line;
            }
        }
        assertNotNull(found, r.err);
        return found;
    }

    /**
     * {@code def NAME : Sort 0 := Sort 1} (ill-typed) or {@code def NAME : Sort 2 := Sort 1} (well-typed);
     * with {@code node} the value is wrapped in a metadata node.
     */
    private static String def(int nameId, String name, boolean good, boolean node, int exprBase, int levelBase) {
        int l1 = levelBase;
        int l2 = levelBase + 1;
        int value = exprBase + 1;
        String s = "{\"in\":" + nameId + ",\"str\":{\"pre\":0,\"str\":\"" + name + "\"}}\n"
                + "{\"il\":" + l1 + ",\"succ\":0}\n"
                + "{\"il\":" + l2 + ",\"succ\":" + l1 + "}\n"
                + "{\"ie\":" + exprBase + ",\"sort\":" + (good ? l2 : 0) + "}\n"
                + "{\"ie\":" + value + ",\"sort\":" + l1 + "}\n";
        if (node) {
            s += "{\"ie\":" + (exprBase + 2) + ",\"mdata\":{\"data\":{},\"expr\":" + value + "}}\n";
            value = exprBase + 2;
        }
        return s + "{\"def\":{\"all\":[" + nameId + "],\"hints\":\"opaque\",\"levelParams\":[],\"name\":" + nameId
                + ",\"safety\":\"safe\",\"type\":" + exprBase + ",\"value\":" + value + "}}\n";
    }

    @Test
    void aPracticalRejectOnAFileWithADroppedNodeIsCheckedAgainUnderStrict() throws Exception {
        Path f = fixture("r2-mdata-beta-arg-hidden-ok");
        for (String[] mode : new String[][] {{}, {"-X", "load=batch"}, {"-X", "load=pipelined"},
            {"-X", "engine=reference"}, {"-X", "ref.exact=off"}}) {
            LauncherAccess.Result r = run(f, mode);
            String what = String.join(" ", mode) + ": " + r;
            // The official checker accepts this file. The strict mode cannot represent the node and declines.
            assertEquals(2, r.status, "the strict verdict (decline), never the practical reject: " + what);
            assertTrue(r.err.contains(NOT_REPORTED), what);
            assertTrue(r.err.contains("1 metadata node "), what);
            assertTrue(r.err.contains("practical, not reported: reject"), "the practical explanation, labelled: " + what);
            for (String line : r.err.split("\\R")) {
                assertFalse(line.startsWith("rlvm: reject"), "no line reports the practical verdict: " + what);
            }
            String stats = statsLine(r);
            assertTrue(stats.contains("outcome=decline"), stats);
            assertTrue(stats.contains("policy=strict["), stats);
            assertTrue(stats.contains("ref.exact=official"), "the second run is exact whatever the first was given: " + stats);
            assertTrue(stats.contains(" net=strict-recheck(practical=reject,metadata=1,practical_wall_ms="), stats);
        }
    }

    @Test
    void theNetGivesTheVerdictOfAStrictRun() throws Exception {
        LauncherAccess.Result strict = run(fixture("r2-mdata-beta-arg-hidden-ok"), "-X", "policy=strict");
        assertEquals(2, strict.status, strict.toString());
        assertFalse(strict.err.contains(NOT_REPORTED), "a strict run is its own last word: " + strict);
        assertFalse(statsLine(strict).contains(" net="), strict.err);
    }

    @Test
    void thePassesModeHasTheNetToo() throws Exception {
        LauncherAccess.Result r = run(fixture("r2-mdata-beta-arg-hidden-ok"), "-X", "passes=2");
        assertEquals(2, r.status, r.toString());
        assertTrue(r.err.contains("verdict=REJECT"), "the passes themselves are practical runs: " + r);
        assertTrue(r.err.contains(NOT_REPORTED), r.toString());
        assertTrue(r.err.contains("outcome=decline"), r.toString());
    }

    @Test
    void quietRunsHaveTheNetAndSayNothing() throws Exception {
        Path f = fixture("r2-mdata-beta-arg-hidden-ok");
        LauncherAccess.Result r = LauncherAccess.run(new HashMap<>(), "--threads", "1", "--quiet", f.toString());
        assertEquals(2, r.status, r.toString());
        assertFalse(r.err.contains("reject"), r.toString());
    }

    @Test
    void aRejectOfAFileWithoutMetadataIsReportedAsItIs() throws Exception {
        LauncherAccess.Result r = run(fixture("r2-mdata-beta-arg-plainform-bad"));
        assertEquals(1, r.status, r.toString());
        assertFalse(r.err.contains(NOT_REPORTED), r.toString());
        assertTrue(r.err.contains("rlvm: reject"), "the explanation of the reported verdict, unchanged: " + r);
        String stats = statsLine(r);
        assertTrue(stats.contains("outcome=reject"), stats);
        assertTrue(stats.contains("policy=practical"), stats);
        assertFalse(stats.contains(" net="), stats);
    }

    @Test
    void anAcceptOfAFileWithMetadataIsUntouched() throws Exception {
        LauncherAccess.Result r = run(fixture("r2-mdata-beta-nofill-ok"));
        assertEquals(0, r.status, r.toString());
        assertFalse(r.err.contains(NOT_REPORTED), r.toString());
        String stats = statsLine(r);
        assertTrue(stats.contains("outcome=accept"), stats);
        assertTrue(stats.contains("policy=practical"), stats);
        assertFalse(stats.contains(" net="), stats);
    }

    @Test
    void aCorrectRejectStaysARejectWhereTheStrictLoadReachesIt() throws Exception {
        // The ill-typed definition stands before the declaration with the node: the strict load publishes it
        // before it declines, and the rejection is the verdict (as the official checker says).
        Path f = text("bad-then-node", META + def(1, "badDef", false, false, 0, 1) + def(2, "withNode", true, true, 10, 3));
        for (String[] mode : new String[][] {{}, {"-X", "load=pipelined"}}) {
            LauncherAccess.Result r = run(f, mode);
            assertEquals(1, r.status, r.toString());
            assertTrue(r.err.contains("badDef"), r.toString());
            if (r.err.contains(NOT_REPORTED)) {
                // The load had read the node when the run ended (always in a batch load).
                assertTrue(statsLine(r).contains("policy=strict["), r.toString());
                assertTrue(statsLine(r).contains(" net=strict-recheck(practical=reject,metadata=1,"), r.toString());
            } else {
                assertTrue(mode.length > 0, "a batch load has read the whole file: " + r);
            }
        }
    }

    @Test
    void aCorrectRejectBehindADeclarationWithANodeBecomesADecline() throws Exception {
        // The price of the net: the strict load declines the declaration with the node and never reaches the
        // ill-typed definition behind it.
        Path f = text("node-then-bad", META + def(1, "withNode", true, true, 0, 1) + def(2, "badDef", false, false, 10, 3));
        LauncherAccess.Result r = run(f);
        assertEquals(2, r.status, r.toString());
        assertTrue(r.err.contains(NOT_REPORTED), r.toString());
        assertTrue(r.err.contains("practical, not reported: reject"), r.toString());
        assertTrue(statsLine(r).contains("outcome=decline"), r.toString());
        // The same two definitions without the node: rejected, no second run.
        LauncherAccess.Result plain = run(text("plain-then-bad",
                META + def(1, "withNode", true, false, 0, 1) + def(2, "badDef", false, false, 10, 3)));
        assertEquals(1, plain.status, plain.toString());
        assertFalse(plain.err.contains(NOT_REPORTED), plain.toString());
    }

    // ---- an engine that is not the reference engine: no net unless the run verifies -----------------------------

    private static final class Out {
        int outcome;
        String err;
    }

    /** One run of the checker with the engine "counting" (not the reference engine) as primary engine. */
    private static Out other(Path file, String... settings) {
        java.util.List<rlvm.api.EngineProvider> all = new java.util.ArrayList<>(Engines.providers());
        all.add(new PassesTest.Counting(Engines.reference(all).factory()));
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("engine", "counting");
        for (String s : settings) {
            int eq = s.indexOf('=');
            extra.put(eq < 0 ? s : s.substring(0, eq), eq < 0 ? "true" : s.substring(eq + 1));
        }
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        Out o = new Out();
        try (java.io.PrintStream err = new java.io.PrintStream(bytes, true, java.nio.charset.StandardCharsets.UTF_8)) {
            o.outcome = new RlvmChecker().run(file, 1, true, 0, extra, err, false, all);
        }
        o.err = bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
        return o;
    }

    /** The checker's {@code --stats} line of the run whose verdict is reported: the last one (the passes and the net add theirs before it). */
    private static String statsLine(Out o) {
        String found = null;
        for (String line : o.err.split("\\R")) {
            if (line.startsWith("rlvm: stats: outcome=")) {
                found = line;
            }
        }
        assertNotNull(found, o.err);
        return found;
    }

    @Test
    void aPlainRunOfAnotherEngineReportsItsRejectionAndHasNoNet() throws Exception {
        Path f = fixture("r2-mdata-beta-arg-hidden-ok");
        for (String[] mode : new String[][] {{}, {"load=pipelined"}, {"verify=none"}, {"no-recheck"}, {"passes=2"}}) {
            Out o = other(f, mode);
            String what = String.join(" ", mode) + ": " + o.err;
            // The official checker accepts this file: a KNOWN WRONG REJECT of a plain run (*agentic-note*: rep-MO-1).
            assertEquals(rlvm.api.Outcome.REJECT, o.outcome, what);
            assertFalse(o.err.contains(NOT_REPORTED), what);
            if (mode.length == 0 || !mode[0].startsWith("passes")) {
                assertTrue(o.err.contains("rlvm: reject"), "the rejection is explained as it is: " + what);
            }
            String stats = statsLine(o);
            assertTrue(stats.contains("outcome=reject"), stats);
            assertTrue(stats.contains(" engine=counting "), stats);
            assertTrue(stats.contains(" policy=practical verify=none fallback=reference "), stats);
            assertFalse(stats.contains(" net="), stats);
        }
        // ... and a correct rejection behind a declaration with a node is a rejection, not a decline
        Path g = text("node-then-bad-plain", META + def(1, "withNode", true, true, 0, 1) + def(2, "badDef", false, false, 10, 3));
        Out plain = other(g);
        assertEquals(rlvm.api.Outcome.REJECT, plain.outcome, plain.err);
        assertTrue(plain.err.contains("badDef"), plain.err);
    }

    @Test
    void theVerificationBringsTheNetBackForAnotherEngine() throws Exception {
        Path f = fixture("r2-mdata-beta-arg-hidden-ok");
        for (String[] mode : new String[][] {{"verify=reference"}, {"verify=reference", "load=pipelined"},
            {"verify=reference", "passes=2"}}) {
            Out o = other(f, mode);
            String what = String.join(" ", mode) + ": " + o.err;
            assertEquals(rlvm.api.Outcome.DECLINE, o.outcome, "the strict verdict (decline), never the practical reject: " + what);
            assertTrue(o.err.contains(NOT_REPORTED), what);
            String stats = statsLine(o);
            assertTrue(stats.contains("outcome=decline"), stats);
            assertTrue(stats.contains("policy=strict["), stats);
            assertTrue(stats.contains(" verify=reference "), stats);
            assertTrue(stats.contains(" net=strict-recheck(practical=reject,metadata=1,practical_wall_ms="), stats);
        }
        // a file without metadata: the verification changes nothing, and no net is named
        Out bad = other(fixture("r2-mdata-beta-arg-plainform-bad"), "verify=reference");
        assertEquals(rlvm.api.Outcome.REJECT, bad.outcome, bad.err);
        assertTrue(statsLine(bad).contains(" policy=practical verify=reference fallback=reference "), bad.err);
        assertFalse(statsLine(bad).contains(" net="), bad.err);
    }

    @Test
    void theStatisticsLineSaysWhatVerifies() throws Exception {
        Path ok = fixture("r2-mdata-beta-nofill-ok");
        // the reference engine as primary engine verifies unasked; asked not to, it does not
        assertTrue(statsLine(run(ok)).contains(" policy=practical verify=reference fallback=reference "), run(ok).err);
        assertTrue(statsLine(run(ok, "-X", "verify=none")).contains(" policy=practical verify=none fallback=reference "));
        assertTrue(statsLine(run(ok, "-X", "policy=strict")).contains("] verify=reference fallback=reference "));
        // another engine: a plain run, the engine alone, the verified run
        assertTrue(statsLine(other(ok)).contains(" verify=none fallback=reference "));
        assertTrue(statsLine(other(ok, "fallback=none")).contains(" verify=none fallback=none "));
        assertTrue(statsLine(other(ok, "no-fallback")).contains(" verify=none fallback=none "));
        assertTrue(statsLine(other(ok, "verify=reference")).contains(" verify=reference fallback=reference "));
        // without the net a rejected file with metadata is rejected by the reference engine too, when it is told not to verify
        LauncherAccess.Result r = run(fixture("r2-mdata-beta-arg-hidden-ok"), "-X", "verify=none");
        assertEquals(1, r.status, r.toString());
        assertFalse(r.err.contains(NOT_REPORTED), r.toString());
        // the one contradiction is a usage error before anything is read
        assertEquals(64, run(ok, "-X", "no-recheck", "-X", "verify=reference").status);
        assertEquals(64, run(ok, "-X", "no-fallback", "-X", "fallback=reference").status);
    }

    @Test
    void theSecondRunTakesTheCommandLineWithoutWhatContradictsTheStrictPolicy() {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("engine", "machine");
        extra.put("passes", "3");
        extra.put("passes-mode", "recheck");
        extra.put("ref.exact", "off");
        extra.put("policy", "practical");
        extra.put("load", "pipelined");
        extra.put("session-mib", "64");
        Map<String, String> again = RlvmChecker.recheckSettings(extra);
        assertEquals(Map.of("load", "pipelined", "session-mib", "64", "policy", "strict"), again);
        assertEquals(Map.of("policy", "strict"), RlvmChecker.recheckSettings(null));
        assertEquals(-1, RlvmChecker.droppedMetadata(null));
    }
}
