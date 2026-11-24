package rlvm.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rlvm.testkit.MutantCorpus.Entry;
import rlvm.testkit.MutantCorpus.Judgement;

class MutantCorpusTest {

    @TempDir
    Path tmp;

    private Path toolchain;
    private Leanchecker checker;
    private MutantCorpus.Options options;

    @BeforeEach
    void setUp() throws Exception {
        Fixtures.copy("070_nRec", tmp.resolve("tests/good/tutorial"));
        Fixtures.copy("001_basicDef", tmp.resolve("tests/good/tutorial"));
        Fixtures.copy("002_badDef", tmp.resolve("tests/bad/tutorial"));
        Fixtures.copy("synthetic_literals", tmp.resolve("tests/good/other"));
        toolchain = Fixtures.fakeToolchain(tmp.resolve("tc"));
        // verdict = parity of the file's checksum: a function of the bytes alone
        Files.write(toolchain.resolve("by-checksum"), new byte[0]);
        checker = new Leanchecker(toolchain, tmp.resolve("cache"));
        options = new MutantCorpus.Options();
        options.sources = tmp.resolve("tests");
        options.only = "tutorial/*";
        options.out = tmp.resolve("corpus");
        options.perKind = 2;
        options.jobs = 3;
        options.memoryLimitBytes = 0;
        options.timeout = Duration.ofSeconds(30);
    }

    @Test
    void corpusLayoutIndexAndBuckets() throws Exception {
        List<Entry> entries = MutantCorpus.build(options, checker, null);
        assertTrue(entries.size() >= 60, "mutants: " + entries.size());
        Path corpus = options.out;
        assertTrue(Files.isRegularFile(corpus.resolve(MutantCorpus.MARKER)));
        assertFalse(Files.exists(corpus.resolve("staging")));
        Set<String> names = new HashSet<>();
        Set<String> hashes = new HashSet<>();
        int good = 0;
        int bad = 0;
        for (Entry e : entries) {
            assertTrue(names.add(e.name), "duplicate name " + e.name);
            assertTrue(hashes.add(e.sha256), "duplicate content " + e.name);
            assertTrue(e.sourceName.startsWith("tutorial/"), "the filter excludes other/: " + e.sourceName);
            Path file = corpus.resolve("suite").resolve(e.bucket).resolve(e.name + ".ndjson");
            assertTrue(Files.isRegularFile(file), "missing " + file);
            assertEquals(e.sha256, Leanchecker.sha256(file));
            // the bucket is the official verdict
            assertEquals(e.official.equals("accept") ? "good" : "bad", e.bucket, e.name);
            if (e.bucket.equals("good")) {
                good++;
            } else {
                bad++;
            }
            // anomalies: exactly the verdict-preserving mutants whose verdict differs from the source's
            boolean changed = e.verdictPreserving && !e.official.equals(e.sourceOfficial);
            assertEquals(changed ? "VERDICT_CHANGED" : null, e.anomaly, e.name);
            assertEquals(ExportMutator.Mutation.valueOf(e.kind).verdictPreserving, e.verdictPreserving);
            assertFalse(e.description.isEmpty());
        }
        assertTrue(good > 5 && bad > 5, "good " + good + ", bad " + bad);

        // index.jsonl is the same list
        List<Entry> read = MutantCorpus.readIndex(corpus);
        assertEquals(entries.size(), read.size());
        for (int i = 0; i < read.size(); i++) {
            assertEquals(entries.get(i).name, read.get(i).name);
            assertEquals(entries.get(i).bucket, read.get(i).bucket);
            assertEquals(entries.get(i).sha256, read.get(i).sha256);
            assertEquals(entries.get(i).official, read.get(i).official);
            assertEquals(entries.get(i).description, read.get(i).description);
            assertEquals(entries.get(i).anomaly, read.get(i).anomaly);
            assertEquals(entries.get(i).kind, read.get(i).kind);
            assertEquals(entries.get(i).line, read.get(i).line);
        }
        // the suite manifest is what bench/run-suite.py reads: name + outcome
        Json manifest = Json.parse(new String(Files.readAllBytes(corpus.resolve("suite/manifest.json")), StandardCharsets.UTF_8));
        assertEquals(entries.size(), manifest.get("tests").size());
        Json summary = Json.parse(new String(Files.readAllBytes(corpus.resolve("SUMMARY.json")), StandardCharsets.UTF_8));
        assertEquals(entries.size(), summary.getLong("mutants", -1));
        assertEquals(good, summary.getLong("official_accept", -1));
        assertEquals(bad, summary.getLong("official_reject", -1));
        assertTrue(Files.size(corpus.resolve("SUMMARY.md")) > 200);
    }

    /** Building twice gives the same mutants with the same classification: the campaign is reproducible. */
    @Test
    void rebuildIsIdentical() throws Exception {
        List<Entry> first = MutantCorpus.build(options, checker, null);
        String index1 = new String(Files.readAllBytes(options.out.resolve("index.jsonl")), StandardCharsets.UTF_8);
        int callsAfterFirst = Fixtures.calls(toolchain);
        List<Entry> second = MutantCorpus.build(options, checker, null);
        String index2 = new String(Files.readAllBytes(options.out.resolve("index.jsonl")), StandardCharsets.UTF_8);
        assertEquals(first.size(), second.size());
        // wall times differ between a fresh and a cached run only if the cache were bypassed
        assertEquals(index1, index2);
        assertEquals(callsAfterFirst, Fixtures.calls(toolchain), "the second build is answered by the verdict cache");

        // and without the cache: the verdicts are a function of the bytes
        MutantCorpus.Options other = options;
        other.out = tmp.resolve("corpus2");
        List<Entry> third = MutantCorpus.build(other, new Leanchecker(toolchain, null), null);
        assertEquals(first.size(), third.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).name, third.get(i).name);
            assertEquals(first.get(i).sha256, third.get(i).sha256);
            assertEquals(first.get(i).official, third.get(i).official);
            assertEquals(first.get(i).bucket, third.get(i).bucket);
            assertEquals(first.get(i).officialMessage, third.get(i).officialMessage);
        }
    }

    @Test
    void refusesToEmptyADirectoryItDidNotCreate() throws Exception {
        Path dir = tmp.resolve("precious");
        Files.createDirectories(dir.resolve("suite"));
        Files.write(dir.resolve("suite/keep.txt"), "data".getBytes(StandardCharsets.UTF_8));
        options.out = dir;
        assertThrows(IOException.class, () -> MutantCorpus.build(options, checker, null));
        assertTrue(Files.isRegularFile(dir.resolve("suite/keep.txt")));
    }

    @Test
    void undecidedOfficialResultsGoToEither() {
        Entry e = new Entry();
        e.sourceOfficial = "accept";
        e.verdictPreserving = true;
        MutantCorpus.apply(e, Leanchecker.classify(1, false,
                "Lean default kernel rejects the solution: (kernel) deep recursion detected", "", 1, 1, 1, "ab"));
        assertEquals("either", e.bucket);
        assertEquals("UNDECIDED", e.anomaly);
        assertEquals("reject", e.official);
        assertEquals("resource", e.officialStage);

        MutantCorpus.apply(e, Leanchecker.classify(139, false, "", "", 1, 1, 1, "ab"));
        assertEquals("either", e.bucket);
        assertEquals("error", e.official);

        MutantCorpus.apply(e, Leanchecker.classify(1, false, "", "uncaught exception: Expr not found 3", 1, 1, 1, "ab"));
        assertEquals("bad", e.bucket);
        assertEquals("VERDICT_CHANGED", e.anomaly);

        MutantCorpus.apply(e, Leanchecker.classify(0, false, "Lean default kernel accepts the solution", "", 1, 1, 1, "ab"));
        assertEquals("good", e.bucket);
        assertNull(e.anomaly);
    }

    @Test
    void judgeFindsWhatACheckerGetsWrong() throws Exception {
        List<Entry> entries = MutantCorpus.build(options, checker, null);
        long good = entries.stream().filter(e -> e.bucket.equals("good")).count();
        long bad = entries.stream().filter(e -> e.bucket.equals("bad")).count();
        Path corpus = options.out;

        // a checker that accepts everything: one soundness difference per official rejection
        List<Judgement> acceptAll = MutantCorpus.judge(corpus, entries, "test -r \"$IN\" && exit 0; exit 3", null, 4,
                Duration.ofSeconds(20), null);
        assertEquals(bad, acceptAll.stream().filter(j -> "SOUNDNESS".equals(j.difference())).count());
        assertEquals(0, acceptAll.stream().filter(j -> "COMPLETENESS".equals(j.difference())).count());
        assertTrue(acceptAll.stream().allMatch(j -> j.status.equals("accepted")));

        // a checker that rejects everything: one completeness difference per official acceptance
        List<Judgement> rejectAll = MutantCorpus.judge(corpus, entries, "exit 1", null, 4, Duration.ofSeconds(20), null);
        assertEquals(good, rejectAll.stream().filter(j -> "COMPLETENESS".equals(j.difference())).count());
        assertEquals(0, rejectAll.stream().filter(j -> "SOUNDNESS".equals(j.difference())).count());

        // declines, crashes and timeouts are never differences
        List<Judgement> declineAll = MutantCorpus.judge(corpus, entries, "exit 2", null, 4, Duration.ofSeconds(20), null);
        assertTrue(declineAll.stream().allMatch(j -> j.difference() == null && j.status.equals("declined")));
        List<Judgement> crash = MutantCorpus.judge(corpus, entries.subList(0, 3), "exit 137", null, 2, Duration.ofSeconds(20), null);
        assertTrue(crash.stream().allMatch(j -> j.difference() == null && j.status.equals("error")));
        List<Judgement> slow = MutantCorpus.judge(corpus, entries.subList(0, 2), "sleep 30", null, 2, Duration.ofMillis(500), null);
        assertTrue(slow.stream().allMatch(j -> j.difference() == null && j.status.equals("timeout")));

        // the classifier itself agrees with its own classification
        String self = "'" + toolchain.resolve("bin/leanchecker") + "' --from-export \"$IN\" >/dev/null 2>&1";
        List<Judgement> same = MutantCorpus.judge(corpus, entries, self, null, 4, Duration.ofSeconds(20), null);
        assertTrue(same.stream().allMatch(j -> j.difference() == null), "the oracle disagrees with itself");
        assertEquals(good, same.stream().filter(j -> j.status.equals("accepted")).count());
        assertEquals(bad, same.stream().filter(j -> j.status.equals("rejected")).count());
    }

    @Test
    void commandLineBuildsAndJudges() throws Exception {
        // The command line locates the checker from the environment, which a test cannot set; it is
        // exercised here without classification, the classified path being covered above.
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.PrintStream ps = new java.io.PrintStream(out, true, "UTF-8");
        int rc = Main.run(new String[] {"corpus", "--sources", tmp.resolve("tests").toString(), "--out", tmp.resolve("cli").toString(),
                "--per-kind", "1", "--no-classify", "--only", "*", "--kinds", "swap_child,reformat_json"}, ps, ps);
        assertEquals(0, rc, out.toString("UTF-8"));
        List<Entry> entries = MutantCorpus.readIndex(tmp.resolve("cli"));
        // four sources reformatted; only two of them have an expression with a child to swap
        assertEquals(6, entries.size(), out.toString("UTF-8"));
        assertTrue(entries.stream().allMatch(e -> e.bucket.equals("either")), "unclassified mutants have no expected outcome");
    }

    /** WRAP_MDATA is one of the kinds every default corpus is built with. */
    @Test
    void theDefaultKindsIncludeMetadataWrapping() throws Exception {
        assertTrue(new MutantCorpus.Options().kinds.contains(ExportMutator.Mutation.WRAP_MDATA));
        List<Entry> entries = MutantCorpus.build(options, checker, null);
        assertTrue(entries.stream().anyMatch(e -> e.kind.equals("WRAP_MDATA")), "no WRAP_MDATA mutant in a default build");
        // the three corpora that take every kind, and the one that goes deeper on metadata alone
        assertEquals(4, MutantCorpus.DEFAULTS.length);
        assertEquals(3, java.util.Arrays.stream(MutantCorpus.DEFAULTS).filter(d -> d.kinds == null).count());
        assertEquals("WRAP_MDATA", MutantCorpus.DEFAULTS[3].kinds);
    }

    /** A rebuilt corpus replaces the old one as a whole; the old mutants come back byte for byte. */
    @Test
    void aRebuiltCorpusIsSwappedIn() throws Exception {
        options.kinds = java.util.Arrays.asList(ExportMutator.Mutation.SWAP_CHILD, ExportMutator.Mutation.CHANGE_HINT);
        List<Entry> before = MutantCorpus.build(options, checker, null);
        Path target = options.out;
        Files.write(target.resolve("left-over.txt"), new byte[] {1});
        options.out = target.resolveSibling(target.getFileName() + ".rebuild");
        options.kinds = java.util.Arrays.asList(ExportMutator.Mutation.SWAP_CHILD, ExportMutator.Mutation.CHANGE_HINT,
                ExportMutator.Mutation.WRAP_MDATA);
        List<Entry> after = MutantCorpus.build(options, checker, null);
        MutantCorpus.swapIn(options.out, target);
        assertFalse(Files.exists(options.out), "the rebuild directory moved");
        assertFalse(Files.exists(target.resolve("left-over.txt")), "nothing of the old corpus is left");
        assertFalse(Files.exists(target.resolveSibling(target.getFileName() + ".old")));
        List<Entry> read = MutantCorpus.readIndex(target);
        assertEquals(after.size(), read.size());
        assertTrue(after.size() > before.size());
        java.util.Map<String, String> hashes = new java.util.HashMap<>();
        for (Entry e : read) {
            hashes.put(e.name, e.sha256);
            assertTrue(Files.isRegularFile(target.resolve("suite").resolve(e.bucket).resolve(e.name + ".ndjson")), e.name);
        }
        for (Entry e : before) {
            assertEquals(e.sha256, hashes.get(e.name), "an old mutant changed: " + e.name);
        }
        // a directory that is not a corpus is never replaced
        Path foreign = tmp.resolve("foreign");
        Files.createDirectories(foreign);
        Files.write(foreign.resolve("precious"), new byte[] {1});
        options.out = tmp.resolve("again.rebuild");
        MutantCorpus.build(options, checker, null);
        assertThrows(IOException.class, () -> MutantCorpus.swapIn(options.out, foreign));
        assertTrue(Files.exists(foreign.resolve("precious")));
    }

    /** judge --all: every default corpus, one table, appended to the summary file; a wrong checker fails it. */
    @Test
    void judgeAllWritesOneSummaryTable() throws Exception {
        options.kinds = java.util.Arrays.asList(ExportMutator.Mutation.SWAP_CHILD, ExportMutator.Mutation.WRAP_MDATA);
        options.out = tmp.resolve("root/work/c-one");
        List<Entry> one = MutantCorpus.build(options, checker, null);
        options.out = tmp.resolve("root/work/c-two");
        options.kinds = java.util.Arrays.asList(ExportMutator.Mutation.CHANGE_HINT);
        List<Entry> two = MutantCorpus.build(options, checker, null);
        MutantCorpus.Default[] defaults = {new MutantCorpus.Default("one", "work/c-one", "tutorial/*", 2, null, 30),
            new MutantCorpus.Default("two", "work/c-two", "tutorial/*", 2, "CHANGE_HINT", 30),
            new MutantCorpus.Default("absent", "work/c-three", "tutorial/*", 2, null, 30)};
        Path root = tmp.resolve("root");
        Path summary = root.resolve("work/status/mutants.md");
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        java.io.PrintStream ps = new java.io.PrintStream(bytes, true, "UTF-8");

        String self = "'" + toolchain.resolve("bin/leanchecker") + "' --from-export \"$IN\" >/dev/null 2>&1";
        Main.Args args = new Main.Args(new String[] {"--all", "-j", "4", "--note", "first", "--", self}, 0, "--all", "--no-summary");
        assertEquals(0, MutantCorpus.judgeAll(root, defaults, self, args, ps, ps), bytes.toString("UTF-8"));
        String text = new String(Files.readAllBytes(summary), StandardCharsets.UTF_8);
        assertTrue(text.startsWith("# Mutant campaigns"), text);
        assertTrue(text.contains("| one (`work/c-one`) | " + one.size() + " | 0 | 0 |"), text);
        assertTrue(text.contains("| two (`work/c-two`) | " + two.size() + " | 0 | 0 |"), text);
        assertTrue(text.contains("| **total** | **" + (one.size() + two.size()) + "** | **0** | **0** |"), text);
        assertTrue(text.contains("PASS") && text.contains("first"), text);
        assertFalse(text.contains("absent"), "a corpus that is not built is skipped");
        assertTrue(bytes.toString("UTF-8").contains("corpus absent (work/c-three) is not built"));

        // a checker that accepts everything: SOUNDNESS differences, exit 1, a second entry in the same file
        long bad = one.stream().filter(e -> e.bucket.equals("bad")).count() + two.stream().filter(e -> e.bucket.equals("bad")).count();
        assertTrue(bad > 0);
        Main.Args again = new Main.Args(new String[] {"--all", "--", "exit 0"}, 0, "--all", "--no-summary");
        assertEquals(1, MutantCorpus.judgeAll(root, defaults, "exit 0", again, ps, ps));
        text = new String(Files.readAllBytes(summary), StandardCharsets.UTF_8);
        assertEquals(2, text.split("\n## ", -1).length - 1, "two entries: " + text);
        assertTrue(text.contains("| **total** | **" + (one.size() + two.size()) + "** | **" + bad + "** | **0** |"), text);
        assertTrue(text.contains("FAILED: " + bad + " mutant(s) accepted"), text);

        // --no-summary leaves the file alone
        long size = Files.size(summary);
        Main.Args quiet = new Main.Args(new String[] {"--all", "--no-summary", "--", "exit 2"}, 0, "--all", "--no-summary");
        assertEquals(0, MutantCorpus.judgeAll(root, defaults, "exit 2", quiet, ps, ps), "declining everything is never wrong");
        assertEquals(size, Files.size(summary));
    }
}
