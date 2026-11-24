package rlvm.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code testkit diff} command with a scripted launcher: what it passes to the launcher, how it
 * reads the report files, what it prints and its exit status. (The real launcher and the real engines
 * are exercised by {@link DiffEndToEndTest} in process and by the campaign itself.)
 */
class DiffRunTest {

    @TempDir
    Path tmp;

    private Path launcher;
    private Path outDir;

    /**
     * A launcher whose behaviour is chosen by the export's file name: {@code same} = the engines agree
     * on three units, {@code sound} = one soundness difference (recorded three times, as the driver's
     * re-check protocol makes it), {@code sample} = one sampler difference, {@code crash} = status 3,
     * {@code early} = rejected before any engine exists.
     */
    @BeforeEach
    void fakeLauncher() throws IOException {
        launcher = tmp.resolve("fake-rlvm");
        outDir = tmp.resolve("out");
        String summary = "{\"kind\":\"SUMMARY\",\"under_test_engine\":\"m\",\"oracle_engine\":\"reference\",\"strict\":%s,"
                + "\"checks\":%d,\"agree_accept\":%d,\"agree_reject\":0,\"declined_under_test\":0,\"declined_oracle\":0,"
                + "\"sampled_units\":%d,\"sample\":{\"problems\":4,\"infer\":4,\"whnf\":2,\"def_eq\":6,\"undecided\":0,\"oracle_errors\":0},"
                + "\"sample_unavailable\":null}";
        String sound = "{\"kind\":\"SOUNDNESS\",\"constant\":\"bad.thm\",\"cid\":7,\"unit\":5,\"under_test_engine\":\"m\","
                + "\"under_test\":\"accept\",\"under_test_reason\":null,\"oracle_engine\":\"reference\",\"oracle\":\"reject\","
                + "\"oracle_reason\":\"DECL_TYPE_MISMATCH\",\"oracle_message\":\"x vs y\",\"persistence\":\"persistent\","
                + "\"strict\":%s,\"reproduce\":\"REPRO-LINE\"}";
        String sample = "{\"kind\":\"SAMPLE_DEFEQ\",\"constant\":\"c\",\"cid\":3,\"unit\":2,\"root\":\"value\",\"path\":\"1.0\","
                + "\"problem\":\"isDefEq a =?= b\",\"under_test\":\"true\",\"oracle\":\"false\",\"under_test_engine\":\"m\","
                + "\"oracle_engine\":\"reference\",\"strict\":false,\"reproduce\":\"SAMPLE-REPRO\"}";
        List<String> script = new ArrayList<>(Arrays.asList("#!/bin/sh",
                "for a in \"$@\"; do last=\"$a\"; done",
                "echo \"args: $*\"",
                "echo \"cp: $RLVM_EXTRA_CP\"",
                "echo \"engine: ${RLVM_DIFF_ENGINE:-unset} sample: ${RLVM_DIFF_SAMPLE:-unset} label: $RLVM_DIFF_LABEL\"",
                "echo \"repro: $RLVM_DIFF_REPRO\"",
                "case \"$last\" in",
                "  *same*) echo '" + String.format(summary, "false", 3, 3, 1) + "' >> \"$RLVM_DIFF_REPORT\"; exit 0 ;;",
                "  *sound*) echo '" + String.format(sound, "false") + "' >> \"$RLVM_DIFF_REPORT\"",
                "     echo '" + String.format(sound, "false") + "' >> \"$RLVM_DIFF_REPORT\"",
                "     echo '" + String.format(sound, "true") + "' >> \"$RLVM_DIFF_REPORT\"",
                "     echo '" + String.format(summary, "true", 1, 0, 0) + "' >> \"$RLVM_DIFF_REPORT\"",
                "     echo '" + String.format(summary, "false", 6, 4, 0) + "' >> \"$RLVM_DIFF_REPORT\"; exit 1 ;;",
                "  *sample*) echo '" + sample + "' >> \"$RLVM_DIFF_REPORT\"",
                "     echo '" + String.format(summary, "false", 2, 2, 2) + "' >> \"$RLVM_DIFF_REPORT\"; exit 0 ;;",
                "  *decl*) echo '{\"kind\":\"DECLINED\",\"side\":\"under_test\",\"engine\":\"m\",\"constant\":\"heavy\",\"cid\":9,"
                        + "\"unit\":4,\"reason\":\"BUDGET_STEPS\",\"message\":\"too long\",\"strict\":false}' >> \"$RLVM_DIFF_REPORT\"",
                "     echo '{\"kind\":\"SUMMARY\",\"strict\":false,\"checks\":5,\"agree_accept\":4,\"declined_under_test\":1,"
                        + "\"declined_under_test_by_reason\":{\"BUDGET_STEPS\":1}}' >> \"$RLVM_DIFF_REPORT\"; exit 0 ;;",
                "  *crash*) exit 3 ;;",
                "  *early*) exit 1 ;;",
                "esac",
                "exit 64"));
        Files.write(launcher, script, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private Path export(String name) throws IOException {
        Path p = tmp.resolve("exports").resolve(name + ".ndjson");
        Files.createDirectories(p.getParent());
        Files.write(p, "{}\n".getBytes(StandardCharsets.UTF_8));
        return p;
    }

    private String output;

    private int diff(String... args) throws IOException {
        List<String> argv = new ArrayList<>(Arrays.asList("diff", "--launcher", launcher.toString(), "--out", outDir.toString()));
        argv.addAll(Arrays.asList(args));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, "UTF-8");
        int rc = Main.run(argv.toArray(new String[0]), out, out);
        output = bytes.toString("UTF-8");
        return rc;
    }

    @Test
    void agreementIsSuccess() throws IOException {
        assertEquals(0, diff("--engine", "m", export("same").toString()), () -> output);
        assertTrue(output.contains("units compared: 3 of 3 checks (both accept 3"), output);
        assertTrue(output.contains("RESULT: ok"), output);
        String log = new String(Files.readAllBytes(outDir.resolve("0000.log")), StandardCharsets.UTF_8);
        assertTrue(log.contains("args: -X engine=diff --threads 1 "), log);
        assertTrue(log.contains("rlvm-testkit/spi:"), "the service directory of the provider is on the class path: " + log);
        assertFalse(log.contains("spi-broken"), "the broken engine is only added when asked for: " + log);
        assertTrue(log.contains("engine: m sample: unset"), log);
        assertTrue(log.contains("repro: rlvm-testkit/bin/testkit diff --engine m --launcher " + launcher + " --threads 1"), log);
    }

    @Test
    void aVerdictDifferenceFailsAndIsPrintedOnceWithItsReproduction() throws IOException {
        assertEquals(1, diff("--engine", "m", "--threads", "4", export("same").toString(), export("sound").toString()), () -> output);
        assertEquals(1, count(output, "SOUNDNESS unit 5 'bad.thm'"), output);
        assertTrue(output.contains("m ACCEPT vs reference REJECT(DECL_TYPE_MISMATCH: x vs y)  [persistent]"), output);
        assertTrue(output.contains("reproduce: REPRO-LINE"), output);
        assertTrue(output.contains("verdict differences: 1  (SOUNDNESS 1, COMPLETENESS 0"), output);
        assertTrue(output.contains("units compared: 7 of 9 checks"), "the strict engine's re-checks are not counted twice: " + output);
        assertTrue(output.contains("RESULT: FAILED"), output);
        Json doc = Json.parse(new String(Files.readAllBytes(outDir.resolve("summary.json")), StandardCharsets.UTF_8));
        assertEquals(1, doc.getLong("soundness", -1));
        assertEquals(1, doc.get("differences").size());
    }

    @Test
    void aSamplerDifferenceFails() throws IOException {
        assertEquals(1, diff("--sample", "10", "--cp", "extra/classes", export("sample").toString()), () -> output);
        assertTrue(output.contains("SAMPLE_DEFEQ unit 2 'c', value at 1.0: isDefEq a =?= b"), output);
        assertTrue(output.contains("reproduce: SAMPLE-REPRO"), output);
        assertTrue(output.contains("KernelOps sampler: 2 unit(s), 4 sub-term(s), 12 judgment(s) compared: 1 difference(s)"), output);
        String log = new String(Files.readAllBytes(outDir.resolve("0000.log")), StandardCharsets.UTF_8);
        assertTrue(log.contains("engine: unset sample: 10"), log);
        assertTrue(log.contains("extra/classes"), log);
        assertTrue(log.contains("repro: rlvm-testkit/bin/testkit diff --cp extra/classes --launcher " + launcher + " --threads 1"), log);
    }

    @Test
    void aFailedRunOrNothingComparedIsNotSuccess() throws IOException {
        assertEquals(3, diff(export("same").toString(), export("crash").toString()), () -> output);
        assertTrue(output.contains("RESULT: INCOMPLETE") && output.contains("1 run(s) failed"), output);
        assertEquals(3, diff(export("early").toString()), () -> output);
        assertTrue(output.contains("no unit was compared"), output);
    }

    @Test
    void declinesOfTheEngineUnderTestAreListedAndFailOnRequest() throws IOException {
        assertEquals(0, diff(export("decl").toString()), () -> output);
        assertTrue(output.contains("NOT COMPARED: 1 unit(s) the engine under test declined"), output);
        assertTrue(output.contains("declined by m: unit 4 'heavy': BUDGET_STEPS: too long"), output);
        assertTrue(output.contains("declines of the engine under test, by reason: BUDGET_STEPS 1"), output);
        assertEquals(1, diff("--fail-on-decline", export("decl").toString()), () -> output);
        assertTrue(output.contains("the engine under test declined 1 unit(s)"), output);
    }

    @Test
    void expectDifferencesTurnsTheResultAround() throws IOException {
        assertEquals(0, diff("--engine", "broken", "--expect-differences", export("sound").toString()), () -> output);
        String log = new String(Files.readAllBytes(outDir.resolve("0000.log")), StandardCharsets.UTF_8);
        assertTrue(log.contains("spi-broken"), log);
        assertEquals(1, diff("--expect-differences", export("same").toString()), () -> output);
    }

    @Test
    void aDirectoryIsWalkedAndFiltered() throws IOException {
        export("a/same1");
        export("a/same2");
        export("b/sound");
        assertEquals(0, diff("--only", "a/*", "-j", "2", tmp.resolve("exports").toString()), () -> output);
        assertTrue(output.contains("exports: 2 (accept 2"), output);
        assertEquals(1, diff(tmp.resolve("exports").toString()), () -> output);
        assertTrue(output.contains("exports: 3 "), output);
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
