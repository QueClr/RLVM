package rlvm.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfficialVerdictsTest {

    @TempDir
    Path tmp;

    private void put(String rel, String content) throws Exception {
        Path p = tmp.resolve("tests").resolve(rel);
        Files.createDirectories(p.getParent());
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Json> byName(Json file) {
        Map<String, Json> m = new HashMap<>();
        Json tests = file.get("tests");
        for (int i = 0; i < tests.size(); i++) {
            m.put(tests.at(i).getText("name", ""), tests.at(i));
        }
        return m;
    }

    @Test
    void verdictsManifestAndFlags() throws Exception {
        put("good/a.ndjson", "{\"meta\":1}\n");
        put("good/sub/b.ndjson", "FAKE-REJECT\n");
        put("bad/c.ndjson", "FAKE-READER\n");
        put("bad/d.ndjson", "{\"meta\":2}\n");
        put("either/e.ndjson", "FAKE-REJECT 2\n");
        put("good/slow.ndjson", "FAKE-SLEEP\n");
        put("good/notes.txt", "not a test\n");
        Leanchecker lc = new Leanchecker(Fixtures.fakeToolchain(tmp.resolve("tc")), tmp.resolve("cache"));

        List<OfficialVerdicts.Test> tests = OfficialVerdicts.discover(tmp.resolve("tests"), 0);
        assertEquals(6, tests.size());
        assertEquals("a", tests.get(0).name);
        assertEquals("sub/b", tests.get(5).name.equals("sub/b") ? "sub/b" : tests.get(4).name);
        OfficialVerdicts.collect(lc, tests, 3, Duration.ofSeconds(2), null);

        Json verdicts = OfficialVerdicts.verdictsJson(lc, tests, tmp, Duration.ofSeconds(2));
        Json manifest = OfficialVerdicts.manifestJson(tests, tmp);
        Map<String, Json> v = byName(verdicts);
        Map<String, Json> m = byName(manifest);

        assertEquals("accept", v.get("a").getText("verdict", ""));
        assertEquals("accept", v.get("a").getText("arena_outcome", ""));
        assertEquals("tests/good/a.ndjson", v.get("a").getText("file", ""));
        assertTrue(v.get("a").get("message").isNull(), "no message on accept");
        assertTrue(v.get("a").get("agrees_with_arena").asBool());
        assertEquals(64, v.get("a").getText("sha256", "").length());

        assertEquals("reject", v.get("sub/b").getText("verdict", ""));
        assertEquals("kernel", v.get("sub/b").getText("stage", ""));
        assertTrue(v.get("sub/b").getText("message", "").contains("declaration type mismatch"));
        assertFalse(v.get("sub/b").get("agrees_with_arena").asBool());
        assertEquals("reader", v.get("c").getText("stage", ""));
        assertEquals("timeout", v.get("slow").getText("verdict", ""));
        assertEquals(-1, (int) v.get("slow").getDouble("exit_code", 0));

        // expected = the arena's classification; every contradiction is flagged, never resolved silently
        assertEquals("accept", m.get("sub/b").getText("expected", ""));
        assertEquals("DISAGREEMENT", m.get("sub/b").getText("flag", ""));
        assertEquals("reject", m.get("d").getText("expected", ""));
        assertEquals("DISAGREEMENT", m.get("d").getText("flag", ""));
        assertEquals("UNDECIDED", m.get("slow").getText("flag", ""));
        assertNull(m.get("a").getText("flag", null));
        assertNull(m.get("c").getText("flag", null));
        assertEquals("either", m.get("e").getText("expected", ""));
        assertEquals("reject", m.get("e").getText("official", ""));
        assertNull(m.get("e").getText("flag", null), "an 'either' test cannot disagree");
        assertEquals(3, manifest.get("flagged").size());

        Json s = verdicts.get("summary");
        assertEquals(2, s.getLong("disagreements", -1));
        assertEquals(1, s.getLong("undecided", -1));
        Json cross = s.get("arena_outcome/official_verdict");
        assertEquals(1, cross.getLong("accept/accept", 0));
        assertEquals(1, cross.getLong("accept/reject", 0));
        assertEquals(1, cross.getLong("accept/timeout", 0));
        assertEquals(1, cross.getLong("reject/reject", 0));
        assertEquals(1, cross.getLong("reject/accept", 0));
        assertEquals(1, cross.getLong("either/reject", 0));
    }

    @Test
    void arenaMetadataOverridesTheBucket() throws Exception {
        put("good/x.ndjson", "{\"meta\":1}\n");
        put("good/y.ndjson", "FAKE-REJECT\n");
        Path results = tmp.resolve("results.json");
        Files.write(results, ("{\"tests\":[{\"name\":\"y\",\"outcome\":\"either\"},{\"name\":\"x\",\"outcome\":\"accept\"}],"
                + "\"results\":[{\"checker\":\"official\",\"test\":\"x\",\"status\":\"accepted\"},"
                + "{\"checker\":\"other\",\"test\":\"x\",\"status\":\"rejected\"}]}").getBytes(StandardCharsets.UTF_8));
        List<OfficialVerdicts.Test> tests = OfficialVerdicts.discover(tmp.resolve("tests"), 0);
        OfficialVerdicts.annotateFromArena(tests, results);
        assertEquals("accept", tests.get(0).arenaOutcome);
        assertEquals("accepted", tests.get(0).arenaOfficialStatus);
        assertEquals("either", tests.get(1).arenaOutcome);
        assertNull(tests.get(1).arenaOfficialStatus);
        OfficialVerdicts.collect(new Leanchecker(Fixtures.fakeToolchain(tmp.resolve("tc")), null), tests, 1, Duration.ofSeconds(20), null);
        assertFalse(tests.get(1).disagreement());
        Json manifest = OfficialVerdicts.manifestJson(tests, tmp);
        assertEquals("accepted", byName(manifest).get("x").getText("arena_official_v4.35.0", ""));
    }

    @Test
    void sizeLimitSkipsLargeFilesAndRunsAccumulate() throws Exception {
        put("good/small.ndjson", "{\"meta\":1}\n");
        put("good/large.ndjson", "{\"meta\":1}\n" + "x".repeat(5000) + "\n");
        put("bad/z.ndjson", "FAKE-REJECT\n");
        Leanchecker lc = new Leanchecker(Fixtures.fakeToolchain(tmp.resolve("tc")), null);
        List<OfficialVerdicts.Test> all = OfficialVerdicts.discover(tmp.resolve("tests"), 0);
        assertEquals(3, all.size());
        List<OfficialVerdicts.Test> small = OfficialVerdicts.discover(tmp.resolve("tests"), 1000);
        assertEquals(2, small.size());

        // first run: only z; second run: only small; the file ends up with both
        Path out = tmp.resolve("out/verdicts.json");
        List<OfficialVerdicts.Test> first = OfficialVerdicts.discover(tmp.resolve("tests"), 1000);
        first.removeIf(t -> !t.name.equals("z"));
        OfficialVerdicts.collect(lc, first, 1, Duration.ofSeconds(20), null);
        Json v1 = OfficialVerdicts.verdictsJson(lc, first, tmp, Duration.ofSeconds(20));
        OfficialVerdicts.mergeOld(v1, out);
        OfficialVerdicts.writeAtomically(out, Json.pretty(v1));

        List<OfficialVerdicts.Test> second = OfficialVerdicts.discover(tmp.resolve("tests"), 1000);
        second.removeIf(t -> !t.name.equals("small"));
        OfficialVerdicts.collect(lc, second, 1, Duration.ofSeconds(20), null);
        Json v2 = OfficialVerdicts.verdictsJson(lc, second, tmp, Duration.ofSeconds(20));
        OfficialVerdicts.mergeOld(v2, out);
        OfficialVerdicts.writeAtomically(out, Json.pretty(v2));

        Json merged = Json.parse(new String(Files.readAllBytes(out), StandardCharsets.UTF_8));
        assertEquals(2, merged.get("tests").size());
        assertEquals("small", merged.get("tests").at(0).getText("name", ""));
        assertEquals("z", merged.get("tests").at(1).getText("name", ""));
        assertEquals(2, merged.get("summary").getLong("tests", 0));
        assertEquals(0, merged.get("summary").getLong("disagreements", -1));
    }
}
