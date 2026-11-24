package rlvm.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rlvm.api.Outcome;
import rlvm.main.RlvmChecker;

/**
 * {@code -X engine=diff} end to end and in process: the real reader, loader, environment pass, driver
 * and workers ({@code rlvm.main.RlvmChecker}, exactly what the launcher runs), with the test kit's
 * {@link DiffProvider} found through {@code ServiceLoader} (the test class path has the service file
 * that {@code rlvm-testkit/spi} and {@code spi-broken} hold for the launcher).
 *
 * <p>Two instances of the reference engine must agree on everything, on verdicts and on sampled
 * {@code KernelOps} answers; the deliberately broken wrapper engine must be caught in every way it
 * lies, with the unit, both outcomes and a reproduction command in the record.
 */
class DiffEndToEndTest {

    @TempDir
    Path tmp;

    private Path report;
    private final List<String> touched = new ArrayList<>();

    @BeforeEach
    void settings() {
        report = tmp.resolve("report.jsonl");
        set("rlvm.diff.report", report.toString());
    }

    @AfterEach
    void clear() {
        for (String key : touched) {
            System.clearProperty(key);
        }
    }

    private void set(String key, String value) {
        touched.add(key);
        System.setProperty(key, value);
    }

    /** Runs the whole pipeline on a fixture with {@code -X engine=diff}; returns the outcome code. */
    private int run(String fixture, int threads) throws IOException {
        Path export = Fixtures.copy(fixture, tmp);
        set("rlvm.diff.label", export.toString());
        Map<String, String> extra = new HashMap<>();
        extra.put("engine", "diff");
        return new RlvmChecker().checkExport(export, threads, false, 0L, extra);
    }

    private List<Json> records() throws IOException {
        List<Json> out = new ArrayList<>();
        if (Files.isRegularFile(report)) {
            for (String line : Files.readAllLines(report, StandardCharsets.UTF_8)) {
                if (!line.trim().isEmpty()) {
                    out.add(Json.parse(line));
                }
            }
        }
        return out;
    }

    private static List<Json> ofKind(List<Json> records, String prefix) {
        List<Json> out = new ArrayList<>();
        for (Json r : records) {
            if (r.getText("kind", "").startsWith(prefix)) {
                out.add(r);
            }
        }
        return out;
    }

    /** The SUMMARY line of the warm engine. */
    private static Json summary(List<Json> records) {
        for (Json r : records) {
            if (r.getText("kind", "").equals("SUMMARY") && !r.get("strict").asBool()) {
                return r;
            }
        }
        throw new AssertionError("no SUMMARY line of the warm engine in the report: " + records);
    }

    private static long differences(List<Json> records) {
        return records.size() - ofKind(records, "SUMMARY").size();
    }

    @Test
    void twoReferenceEnginesAgreeThroughTheRealDriver() throws IOException {
        set("rlvm.diff.engine", "reference");
        set("rlvm.diff.sample", "1");
        long judgments = 0;
        for (String fixture : new String[] {"001_basicDef", "070_nRec", "103_natLitEq", "030_peano1"}) {
            Files.deleteIfExists(report);
            assertEquals(Outcome.ACCEPT, run(fixture, 1), fixture);
            List<Json> records = records();
            assertEquals(0, differences(records), () -> fixture + ": " + records);
            Json s = summary(records);
            assertEquals("reference", s.getText("under_test_engine", null));
            assertEquals("reference", s.getText("oracle_engine", null));
            assertTrue(s.getLong("checks", 0) > 0, fixture + ": the engines were asked");
            assertEquals(s.getLong("checks", 0), s.getLong("agree_accept", -1), fixture);
            assertEquals(s.getLong("agree_accept", 0), s.getLong("sampled_units", -1), fixture + ": every accepted unit sampled");
            assertFalse(s.has("sample_unavailable") && s.get("sample_unavailable").isString(), () -> s.toString());
            Json sample = s.get("sample");
            assertEquals(0, sample.getLong("differences", -1));
            assertEquals(0, sample.getLong("oracle_errors", -1), fixture);
            judgments += sample.getLong("infer", 0) + sample.getLong("whnf", 0) + sample.getLong("def_eq", 0);
        }
        assertTrue(judgments > 50, "the sampler compared judgments: " + judgments);
    }

    @Test
    void twoReferenceEnginesAgreeOnARejectionAndTheVerdictIsReject() throws IOException {
        set("rlvm.diff.engine", "reference");
        assertEquals(Outcome.REJECT, run("002_badDef", 1));
        List<Json> records = records();
        assertEquals(0, differences(records), records::toString);
        Json s = summary(records);
        assertTrue(s.getLong("agree_reject", 0) >= 1, s::toString);
    }

    @Test
    void severalWorkersAgreeToo() throws IOException {
        set("rlvm.diff.engine", "reference");
        set("rlvm.diff.sample", "2");
        assertEquals(Outcome.ACCEPT, run("103_natLitEq", 4));
        List<Json> records = records();
        assertEquals(0, differences(records), records::toString);
        assertTrue(summary(records).getLong("checks", 0) > 0);
    }

    @Test
    void anEngineThatAcceptsABadDeclarationIsCaughtWithUnitOutcomesAndReproduction() throws IOException {
        set("rlvm.diff.engine", "broken");
        set("rlvm.broken", "accept-rejected");
        // the verdict of the run stays the reference kernel's: the driver confirms every rejection with it
        assertEquals(Outcome.REJECT, run("002_badDef", 1));
        List<Json> found = ofKind(records(), "SOUNDNESS");
        assertFalse(found.isEmpty(), "the wrong ACCEPT must be reported");
        Json d = found.get(0);
        assertEquals("badDef", d.getText("constant", null));
        assertEquals(0, d.getLong("unit", -1));
        assertEquals("broken", d.getText("under_test_engine", null));
        assertEquals("accept", d.getText("under_test", null));
        assertEquals("reference", d.getText("oracle_engine", null));
        assertEquals("reject", d.getText("oracle", null));
        assertEquals("DECL_TYPE_MISMATCH", d.getText("oracle_reason", null));
        assertEquals("persistent", d.getText("persistence", null));
        String repro = d.getText("reproduce", null);
        assertNotNull(repro, "a reproduction command");
        assertTrue(repro.startsWith("rlvm-testkit/bin/testkit diff --engine broken") && repro.endsWith("002_badDef.ndjson"), repro);
    }

    @Test
    void anEngineThatRejectsAGoodDeclarationIsCaughtAndTheRunStillAccepts() throws IOException {
        set("rlvm.diff.engine", "broken");
        set("rlvm.broken", "reject-every=1");
        assertEquals(Outcome.ACCEPT, run("070_nRec", 1), "the reference kernel decides: the export is good");
        List<Json> found = ofKind(records(), "COMPLETENESS");
        assertFalse(found.isEmpty());
        Json d = found.get(0);
        assertEquals("reject", d.getText("under_test", null));
        assertEquals("accept", d.getText("oracle", null));
        assertEquals("persistent", d.getText("persistence", null));
        assertTrue(d.getLong("unit", -1) >= 0);
        assertNotNull(d.getText("constant", null));
        assertTrue(ofKind(records(), "SOUNDNESS").isEmpty());
    }

    @Test
    void aFaultThatNeedsAWarmSessionIsClassifiedAsWarmOnly() throws IOException {
        set("rlvm.diff.engine", "broken");
        set("rlvm.broken", "warm-reject-after=2");
        assertEquals(Outcome.ACCEPT, run("030_peano1", 1));
        List<Json> found = ofKind(records(), "COMPLETENESS");
        assertFalse(found.isEmpty(), "the second check of a session is rejected by the broken engine");
        for (Json d : found) {
            if (!d.get("strict").asBool()) {
                assertEquals("warm-only", d.getText("persistence", null), d::toString);
                assertEquals("accept", d.getText("cold_under_test", null), d::toString);
            }
        }
    }

    @Test
    void wrongKernelOpsAnswersAreCaughtBySampling() throws IOException {
        set("rlvm.diff.engine", "broken");
        set("rlvm.diff.sample", "1");
        set("rlvm.diff.sample.count", "8");
        set("rlvm.broken", "defeq-true,infer-prop");
        assertEquals(Outcome.ACCEPT, run("070_nRec", 1), "the verdicts are right; only the judgments lie");
        List<Json> records = records();
        assertTrue(ofKind(records, "SOUNDNESS").isEmpty() && ofKind(records, "COMPLETENESS").isEmpty());
        List<Json> infer = ofKind(records, "SAMPLE_INFER");
        List<Json> defEq = ofKind(records, "SAMPLE_DEFEQ");
        assertFalse(infer.isEmpty(), "infer = Prop for everything must be noticed");
        assertFalse(defEq.isEmpty(), "isDefEq = true for everything must be noticed");
        Json d = defEq.get(0);
        assertEquals("true", d.getText("under_test", null));
        assertEquals("false", d.getText("oracle", null));
        assertTrue(d.getLong("unit", -1) >= 0);
        assertNotNull(d.getText("constant", null));
        String repro = d.getText("reproduce", null);
        assertTrue(repro.contains("--sample-unit " + d.getLong("unit", -1)) && repro.contains("--seed 1")
                && repro.endsWith("070_nRec.ndjson"), repro);
        assertEquals(infer.size() + defEq.size() + ofKind(records, "SAMPLE_WHNF").size() + ofKind(records, "SAMPLE_CRASH").size(),
                summary(records).getLong("sample_differences", -1));
    }

    @Test
    void aWhnfThatDoesNotReduceAndADefEqThatAlwaysFailsAreCaught() throws IOException {
        set("rlvm.diff.engine", "broken");
        set("rlvm.diff.sample", "1");
        set("rlvm.diff.sample.count", "16");
        set("rlvm.broken", "whnf-identity,defeq-false");
        assertEquals(Outcome.ACCEPT, run("030_peano1", 1));
        List<Json> records = records();
        assertFalse(ofKind(records, "SAMPLE_DEFEQ").isEmpty(), "isDefEq = false for everything must be noticed");
        assertFalse(ofKind(records, "SAMPLE_WHNF_HEAD").isEmpty(), "a whnf that returns its argument must be noticed");
    }

    @Test
    void aWrongIsPropIsCaught() throws IOException {
        set("rlvm.diff.engine", "broken");
        set("rlvm.diff.sample", "1");
        set("rlvm.diff.sample.count", "16");
        set("rlvm.broken", "isprop-false");
        assertEquals(Outcome.ACCEPT, run("030_peano1", 1));
        List<Json> found = ofKind(records(), "SAMPLE_ISPROP");
        assertFalse(found.isEmpty(), "isProp = false on the type of a proof must be noticed");
        assertEquals("false", found.get(0).getText("under_test", null));
        assertEquals("true", found.get(0).getText("oracle", null));
        assertTrue(summary(records()).get("sample").getLong("more", 0) > 0, "isProp / whnfCore / ensurePi answers are counted");
    }

    /** The settings as {@code -X} keys of the launcher: the provider declares them (rlvm.api.EngineProvider.optionKeys). */
    @Test
    void settingsReachTheHarnessAsLauncherOptions() throws IOException {
        Path export = Fixtures.copy("002_badDef", tmp);
        Path other = tmp.resolve("x-report.jsonl");
        Map<String, String> extra = new HashMap<>();
        extra.put("engine", "diff");
        extra.put("diff.engine", "broken");
        extra.put("broken", "accept-rejected");
        extra.put("diff.report", other.toString());
        extra.put("diff.label", "the-export");
        assertEquals(Outcome.REJECT, new RlvmChecker().checkExport(export, 1, false, 0L, extra));
        assertFalse(Files.exists(report), "the -X setting wins over the system property");
        List<Json> found = new ArrayList<>();
        for (String line : Files.readAllLines(other, StandardCharsets.UTF_8)) {
            found.add(Json.parse(line));
        }
        List<Json> sound = ofKind(found, "SOUNDNESS");
        assertFalse(sound.isEmpty());
        assertEquals("the-export", sound.get(0).getText("label", null));
        assertEquals("broken", sound.get(0).getText("under_test_engine", null));
    }

    @Test
    void theSamplerIsRepeatable() throws IOException {
        set("rlvm.diff.engine", "broken");
        set("rlvm.diff.sample", "1");
        set("rlvm.broken", "defeq-true,infer-prop");
        run("070_nRec", 1);
        List<String> first = new ArrayList<>();
        for (Json r : ofKind(records(), "SAMPLE_")) {
            first.add(r.getText("kind", "") + "|" + r.getLong("unit", -1) + "|" + r.getText("root", "") + "|" + r.getText("path", ""));
        }
        assertFalse(first.isEmpty());
        Files.delete(report);
        // the reproduction: the same seed, restricted to one unit, finds that unit's differences again
        long unit = Long.parseLong(first.get(0).split("\\|")[1]);
        set("rlvm.diff.sample.unit", Long.toString(unit));
        run("070_nRec", 1);
        List<String> second = new ArrayList<>();
        for (Json r : ofKind(records(), "SAMPLE_")) {
            second.add(r.getText("kind", "") + "|" + r.getLong("unit", -1) + "|" + r.getText("root", "") + "|" + r.getText("path", ""));
        }
        List<String> expected = new ArrayList<>();
        for (String s : first) {
            if (s.split("\\|")[1].equals(Long.toString(unit))) {
                expected.add(s);
            }
        }
        assertEquals(expected, second);
    }
}
