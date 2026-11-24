package rlvm.main;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Runs {@link ProbeRun} over the published arena tests and writes a report in the format of
 * {@code bench/run-suite.py} (rendered by {@code scripts/slice-report.py}). Opt-in:
 * {@code -Drlvm.probe=tutorial|all} and optionally {@code -Drlvm.probe.report=FILE}.
 */
class ProbeSliceTest {

    private static Path root() {
        Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("work/arena-site/tests"))) {
            p = p.getParent();
        }
        return p;
    }

    private static String json(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }

    /** One file: {@code -Drlvm.probe.file=PATH} (prints outcome, time, memory). */
    @Test
    void probeOneFile() throws Exception {
        String file = System.getProperty("rlvm.probe.file", "");
        assumeTrue(!file.isEmpty(), "set -Drlvm.probe.file=PATH to run");
        ProbeRun.Result r = ProbeRun.run(Path.of(file), 1L << 30, Long.getLong("rlvm.probe.timeout", 600_000L));
        System.out.println("probe-one: " + file + ": " + r.status() + " units=" + r.units + " wall_ms="
                + r.nanos / 1_000_000 + " worker_bytes=" + r.workerBytes + " shared_bytes=" + r.sharedBytes + " "
                + r.message);
    }

    @Test
    void probeThePublishedTests() throws Exception {
        String which = System.getProperty("rlvm.probe", "");
        assumeTrue(which.equals("tutorial") || which.equals("all"), "set -Drlvm.probe=tutorial|all to run");
        Path root = root();
        assumeTrue(root != null, "work/arena-site/tests not found");
        Path tests = root.resolve("work/arena-site/tests");
        Path report = Path.of(System.getProperty("rlvm.probe.report",
                root.resolve("work/rlvm/slice/probe-" + which + ".json").toString()));
        long timeout = Long.getLong("rlvm.probe.timeout", 120_000L);

        List<String> rows = new ArrayList<>();
        int n = 0;
        int correct = 0;
        int rejectWrong = 0;
        int acceptWrong = 0;
        int declined = 0;
        int errors = 0;
        int good = 0;
        int bad = 0;
        long t0 = System.nanoTime();
        for (String bucket : new String[] {"good", "bad"}) {
            Path dir = which.equals("tutorial") ? tests.resolve(bucket).resolve("tutorial") : tests.resolve(bucket);
            List<Path> files;
            try (Stream<Path> s = Files.walk(dir)) {
                files = s.filter(f -> f.toString().endsWith(".ndjson")).sorted().toList();
            }
            for (Path f : files) {
                n++;
                String expected = bucket.equals("good") ? "accept" : "reject";
                if (bucket.equals("good")) {
                    good++;
                } else {
                    bad++;
                }
                ProbeRun.Result r = ProbeRun.run(f, 1L << 30, timeout);
                String status = r.status();
                String correctness;
                if (status.equals("declined")) {
                    correctness = "declined";
                    declined++;
                } else if (status.equals("error")) {
                    correctness = "error";
                    errors++;
                } else if ((expected.equals("accept")) == status.equals("accepted")) {
                    correctness = "correct";
                    correct++;
                } else {
                    correctness = "incorrect";
                    if (expected.equals("accept")) {
                        acceptWrong++;
                    } else {
                        rejectWrong++;
                    }
                }
                String name = tests.resolve(bucket).relativize(f).toString().replace(".ndjson", "");
                rows.add("  {\"test\": " + json(name) + ", \"expected\": " + json(expected) + ", \"status\": "
                        + json(status) + ", \"correctness\": " + json(correctness) + ", \"wall_time\": "
                        + r.nanos / 1e9 + ", \"stdout\": \"\", \"stderr\": " + json(r.message.isEmpty() ? ""
                                : "rlvm: " + r.message) + "}");
            }
        }
        double elapsed = (System.nanoTime() - t0) / 1e9;
        StringBuilder sb = new StringBuilder();
        sb.append("{\n \"meta\": {\"label\": \"steward-probe\", \"command\": ")
                .append(json("in-process steward probe (rlvm.main.ProbeRun): MainWiring.load + EnvBuilder + Admission "
                        + "+ reference engine, one thread, NO driver"))
                .append(", \"elapsed\": ").append(elapsed).append(", \"jobs\": 1},\n \"summary\": {\"reject_wrong\": ")
                .append(rejectWrong).append(", \"accept_wrong\": ").append(acceptWrong).append(", \"by_status\": {")
                .append("\"declined\": ").append(declined).append(", \"error\": ").append(errors)
                .append(", \"timeout\": 0}, \"good_tests\": ").append(good).append(", \"bad_tests\": ").append(bad)
                .append(", \"cpu_time\": ").append(elapsed).append("},\n \"results\": [\n")
                .append(String.join(",\n", rows)).append("\n ]\n}\n");
        Files.createDirectories(report.getParent());
        Files.write(report, sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("probe: " + correct + "/" + n + " correct, wrong verdicts " + (rejectWrong + acceptWrong)
                + " (bad accepted " + rejectWrong + ", good rejected " + acceptWrong + "), declined " + declined
                + ", errors " + errors + "; report " + report);
        assertTrue(n > 0);
    }
}
