package rlvm.admit;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import rlvm.api.Outcome;

/**
 * Development aid, not a guard: the mutant corpus of {@code rlvm-testkit} through the in-module
 * pipeline of {@link ArenaPipelineTest} (real loader, {@code rlvm-inductive}, reference kernel, one
 * worker, no driver, no launcher), all files in one JVM, compared with the official verdicts of the
 * corpus index. The guard that counts is {@code rlvm-testkit/bin/testkit judge} on the distribution.
 *
 * <pre>
 * java -Xss512m -Xmx8g -cp $(cat work/rlvm-admit/cp.txt) rlvm.admit.MutantCampaignMain CORPUS_DIR [REPORT] [FILTER]
 * </pre>
 * {@code CORPUS_DIR} holds {@code index.jsonl} (fields name, file, official); {@code FILTER} is a
 * substring of the mutant name. Prints the counts and writes one line per mutant that is not an
 * agreement to {@code REPORT}. Exit status 1 if a mutant is accepted that the official checker
 * rejects or rejected that it accepts.
 */
public final class MutantCampaignMain {
    private MutantCampaignMain() {}

    private static final Pattern NAME = Pattern.compile("\"name\":\"([^\"]*)\"");
    private static final Pattern FILE = Pattern.compile("\"file\":\"([^\"]*)\"");
    private static final Pattern OFFICIAL = Pattern.compile("\"official\":\"([^\"]*)\"");
    private static final Pattern KIND = Pattern.compile("\"kind\":\"([^\"]*)\"");

    public static void main(String[] args) throws IOException {
        Path corpus = Path.of(args[0]);
        Path report = args.length > 1 ? Path.of(args[1]) : null;
        String filter = args.length > 2 ? args[2] : null;
        int agree = 0;
        int declined = 0;
        int soundness = 0;
        int completeness = 0;
        int errors = 0;
        Map<String, int[]> declinedByKind = new TreeMap<>();
        List<String> lines = new ArrayList<>();
        long t0 = System.nanoTime();
        try (BufferedReader in = Files.newBufferedReader(corpus.resolve("index.jsonl"), StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                Matcher n = NAME.matcher(line);
                Matcher f = FILE.matcher(line);
                Matcher o = OFFICIAL.matcher(line);
                Matcher k = KIND.matcher(line);
                if (!n.find() || !f.find() || !o.find()) {
                    continue;
                }
                String name = n.group(1);
                if (filter != null && !name.contains(filter)) {
                    continue;
                }
                String official = o.group(1);
                String kind = k.find() ? k.group(1) : "?";
                ArenaPipelineTest.Result r;
                try {
                    r = ArenaPipelineTest.run(corpus.resolve(f.group(1)));
                } catch (Throwable t) {
                    errors++;
                    lines.add("ERROR|" + kind + "|" + official + "|" + name + "|" + t);
                    continue;
                }
                String ours = r.outcome == Outcome.ACCEPT ? "accept" : r.outcome == Outcome.REJECT ? "reject" : "decline";
                if (ours.equals("decline")) {
                    declined++;
                    int[] c = declinedByKind.computeIfAbsent(kind, x -> new int[2]);
                    c[official.equals("accept") ? 0 : 1]++;
                    lines.add("DECLINE|" + kind + "|" + official + "|" + name + "|unit " + r.unit + " " + r.message);
                } else if (ours.equals(official)) {
                    agree++;
                } else if (ours.equals("accept")) {
                    soundness++;
                    lines.add("SOUNDNESS|" + kind + "|" + official + "|" + name + "|accepted");
                } else {
                    completeness++;
                    lines.add("COMPLETENESS|" + kind + "|" + official + "|" + name + "|unit " + r.unit + " " + r.message);
                }
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("SOUNDNESS " + soundness + ", COMPLETENESS " + completeness + ", agree " + agree + ", declined "
                + declined + ", errors " + errors + " (" + ms + " ms)");
        for (Map.Entry<String, int[]> e : declinedByKind.entrySet()) {
            System.out.println("  declined " + e.getKey() + ": official accept " + e.getValue()[0] + ", reject "
                    + e.getValue()[1]);
        }
        if (report != null) {
            try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(report, StandardCharsets.UTF_8))) {
                for (String s : lines) {
                    out.println(s.replace('\n', ' '));
                }
            }
        } else {
            for (String s : lines) {
                if (!s.startsWith("DECLINE")) {
                    System.out.println(s);
                }
            }
        }
        System.exit(soundness + completeness > 0 ? 1 : 0);
    }
}
