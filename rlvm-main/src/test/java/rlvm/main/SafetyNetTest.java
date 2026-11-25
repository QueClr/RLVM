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
