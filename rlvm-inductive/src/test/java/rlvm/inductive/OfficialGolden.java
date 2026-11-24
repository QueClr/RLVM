package rlvm.inductive;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The answers of the official checker ({@code leanchecker --from-export}, Lean v4.36.0) to the export
 * files the differential tests of this module write, stored by the SHA-256 of the file
 * ({@code src/test/resources/rlvm/inductive/official-golden.tsv}: sha, exit status, first 600
 * characters of the output with {@code |} for line ends). The tests therefore need no checker binary:
 * a fixture whose text changes (another fixture, or other derived recursor records) has no stored
 * answer and the test FAILS until the answer is added.
 *
 * <p>How to add answers (the official checker runs inside the arena Docker image only):
 * <pre>
 *   $MVN -o -q -pl rlvm-inductive test -Dtest='Official*' -Drlvm.inductive.golden.dump=$PWD/work/a2/golden-dump
 *   rlvm-inductive/src/test/scripts/official-verdicts.sh work/a2/golden-dump work/a2/golden-new.tsv
 *   cat work/a2/golden-new.tsv >> rlvm-inductive/src/test/resources/rlvm/inductive/official-golden.tsv
 * </pre>
 * {@code -Drlvm.leanchecker=PATH} runs a checker (or a wrapper script) directly for the missing
 * fixtures and writes the new rows to {@code target/official-golden-new.tsv}.
 */
final class OfficialGolden {
    private OfficialGolden() {}

    static final class Verdict {
        int exit;
        String output;

        /** The kernel rejected a declaration while replaying it (not a record mismatch found afterwards). */
        boolean kernelRejected() {
            return exit != 0 && output.contains("(kernel)");
        }
    }

    private static final String RESOURCE = "official-golden.tsv";
    private static final HashMap<String, Verdict> GOLDEN = new HashMap<>();
    private static final ArrayList<String> MISSING = new ArrayList<>();

    static {
        try (InputStream in = OfficialGolden.class.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isEmpty() || line.charAt(0) == '#') {
                        continue;
                    }
                    String[] c = line.split("\t", 3);
                    Verdict v = new Verdict();
                    v.exit = Integer.parseInt(c[1]);
                    v.output = c.length > 2 ? c[2] : "";
                    GOLDEN.put(c[0], v);
                }
            }
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static int size() {
        return GOLDEN.size();
    }

    static String sha256(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder(64);
            for (byte x : d) {
                b.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
            }
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The official answer to the export {@code ndjson}; null if none is stored and no checker is
     * configured (the fixture is then remembered: {@link #failIfMissing}).
     */
    static synchronized Verdict official(String fixture, String ndjson) throws IOException, InterruptedException {
        String sha = sha256(ndjson);
        Verdict v = GOLDEN.get(sha);
        if (v != null) {
            return v;
        }
        String checker = System.getProperty("rlvm.leanchecker");
        if (checker != null) {
            v = run(Path.of(checker), ndjson);
            GOLDEN.put(sha, v);
            Path out = Path.of("target", "official-golden-new.tsv");
            if (Files.isDirectory(out.getParent())) {
                Files.writeString(out, sha + "\t" + v.exit + "\t" + v.output + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            return v;
        }
        String dump = System.getProperty("rlvm.inductive.golden.dump");
        if (dump != null) {
            Path dir = Path.of(dump);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(sha + ".ndjson"), ndjson, StandardCharsets.UTF_8);
        }
        MISSING.add(fixture + " (" + sha.substring(0, 12) + ")");
        return null;
    }

    /** Fails if a fixture asked about since the last call has no stored answer. */
    static synchronized void failIfMissing() {
        if (MISSING.isEmpty()) {
            return;
        }
        List<String> m = new ArrayList<>(MISSING);
        MISSING.clear();
        String dump = System.getProperty("rlvm.inductive.golden.dump");
        fail(m.size() + " fixtures have no stored answer of the official checker ("
                + (dump != null ? "their exports were written to " + dump : "see OfficialGolden for how to add them")
                + "): " + m.subList(0, Math.min(8, m.size())) + (m.size() > 8 ? " ..." : ""));
    }

    private static Verdict run(Path checker, String ndjson) throws IOException, InterruptedException {
        Path dir = Files.createTempDirectory("rlvm-inductive-diff");
        Path file = dir.resolve("fixture.ndjson");
        Path log = dir.resolve("out.txt");
        try {
            Files.write(file, ndjson.getBytes(StandardCharsets.UTF_8));
            Process p = new ProcessBuilder(checker.toString(), "--from-export", file.toString()).redirectErrorStream(true)
                    .redirectOutput(log.toFile()).start();
            if (!p.waitFor(300, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                fail("official checker timed out");
            }
            Verdict v = new Verdict();
            v.exit = p.exitValue();
            String text = new String(Files.readAllBytes(log), StandardCharsets.UTF_8).replace('\t', ' ').replace('\n', '|');
            v.output = text.length() > 600 ? text.substring(0, 600) : text;
            return v;
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(log);
            Files.deleteIfExists(dir);
        }
    }
}
