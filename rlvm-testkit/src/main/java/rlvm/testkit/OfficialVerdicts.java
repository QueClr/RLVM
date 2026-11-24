package rlvm.testkit;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

/**
 * The official-verdict cache for a directory of arena tests and the manifest of expected outcomes.
 *
 * <p>A tests directory has the arena's layout: {@code good/**}{@code /*.ndjson} (the arena expects
 * "accept"), {@code bad/**} ("reject") and optionally {@code either/**} (both answers are fine). A
 * test's name is its path below the bucket without the extension ({@code tutorial/001_basicDef}).
 *
 * <p>Two files are produced:
 * <ul>
 *   <li>{@code verdicts.json}: per test the official checker's verdict, exit status, wall and CPU
 *       time, peak RSS, and its message when it does not accept.
 *   <li>{@code manifest.json}: per test the outcome RLVM is expected to produce, which combines the
 *       arena's classification with the official verdict, and a flag wherever the two disagree.
 * </ul>
 *
 * <p>Expected outcome of a test, as used by every differential test of this project:
 * <pre>
 * arena    official 4.36.0     expected   flag
 * accept   accept              accept
 * reject   reject              reject
 * either   accept / reject     either     (reference = the official verdict)
 * accept   reject              accept     DISAGREEMENT (the arena scores; never silently resolved)
 * reject   accept              reject     DISAGREEMENT
 * any      timeout / error     arena's    UNDECIDED (the official checker gave no answer here)
 * </pre>
 * A decline is always permitted (it is never a wrong answer), so "expected accept" means "must not
 * reject" and "expected reject" means "must not accept".
 */
public final class OfficialVerdicts {

    /** One test with what is known about it. */
    public static final class Test {
        public final String name;
        /** "good", "bad" or "either". */
        public final String bucket;
        /** "accept", "reject" or "either": the arena's classification (bucket, overridden by its metadata). */
        public String arenaOutcome;
        public final Path file;
        public final long size;
        /** The official result; null until {@link #collect} ran. */
        public Leanchecker.Result official;
        /** Status the arena published for its own "official" checker (v4.35.0), or null. */
        public String arenaOfficialStatus;

        Test(String name, String bucket, Path file, long size) {
            this.name = name;
            this.bucket = bucket;
            this.arenaOutcome = bucket.equals("good") ? "accept" : bucket.equals("bad") ? "reject" : "either";
            this.file = file;
            this.size = size;
        }

        /** "accept", "reject" or "either": what RLVM must answer (see the class comment). */
        public String expected() {
            return arenaOutcome;
        }

        /** True when the official checker answered and the answer contradicts the arena's classification. */
        public boolean disagreement() {
            if (official == null || !official.decided() || arenaOutcome.equals("either")) {
                return false;
            }
            return official.accepted() != arenaOutcome.equals("accept");
        }

        /** True when the official checker gave no answer (timeout, crash). */
        public boolean undecided() {
            return official != null && !official.decided();
        }
    }

    private OfficialVerdicts() {}

    /** Finds the tests below {@code testsDir}/{good,bad,either}, sorted by name; files above {@code maxBytes} are skipped (0 = no limit). */
    public static List<Test> discover(Path testsDir, long maxBytes) throws IOException {
        List<Test> tests = new ArrayList<>();
        for (String bucket : new String[] {"good", "bad", "either"}) {
            Path base = testsDir.resolve(bucket);
            if (!Files.isDirectory(base)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(base, java.nio.file.FileVisitOption.FOLLOW_LINKS)) {
                List<Path> files = new ArrayList<>();
                walk.filter(p -> p.getFileName().toString().endsWith(".ndjson") && Files.isRegularFile(p)).forEach(files::add);
                for (Path p : files) {
                    long size = Files.size(p);
                    if (maxBytes > 0 && size > maxBytes) {
                        continue;
                    }
                    String rel = base.relativize(p).toString().replace('\\', '/');
                    tests.add(new Test(rel.substring(0, rel.length() - ".ndjson".length()), bucket, p, size));
                }
            }
        }
        tests.sort((a, b) -> a.name.compareTo(b.name));
        return tests;
    }

    /**
     * Adds the arena's own metadata from its published {@code results.json}: the declared outcome of
     * every test (it overrides the bucket, as in {@code bench/run-suite.py}) and the status its
     * "official" checker got.
     */
    public static void annotateFromArena(List<Test> tests, Path resultsJson) throws IOException {
        if (resultsJson == null || !Files.isRegularFile(resultsJson)) {
            return;
        }
        Json root = Json.parse(new String(Files.readAllBytes(resultsJson), StandardCharsets.UTF_8));
        Map<String, String> outcome = new HashMap<>();
        Json list = root.get("tests");
        if (list != null && list.isArray()) {
            for (int i = 0; i < list.size(); i++) {
                Json t = list.at(i);
                String name = t.getText("name", null);
                String o = t.getText("outcome", null);
                if (name != null && o != null) {
                    outcome.put(name, o);
                }
            }
        }
        Map<String, String> official = new HashMap<>();
        Json results = root.get("results");
        if (results != null && results.isArray()) {
            for (int i = 0; i < results.size(); i++) {
                Json r = results.at(i);
                if ("official".equals(r.getText("checker", ""))) {
                    official.put(r.getText("test", ""), r.getText("status", ""));
                }
            }
        }
        for (Test t : tests) {
            String o = outcome.get(t.name);
            if (o != null) {
                t.arenaOutcome = o;
            }
            t.arenaOfficialStatus = official.get(t.name);
        }
    }

    /**
     * Runs the official checker on every test ({@code parallelism} at a time; results come from the
     * cache where possible). Progress lines go to {@code progress} if it is not null.
     */
    public static void collect(Leanchecker checker, List<Test> tests, int parallelism, Duration timeout, PrintStream progress) {
        // Largest files first: they take the longest, and the pool drains evenly behind them.
        List<Test> order = new ArrayList<>(tests);
        order.sort((a, b) -> Long.compare(b.size, a.size));
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, parallelism), r -> {
            Thread t = new Thread(r, "oracle");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<?>> futures = new ArrayList<>();
            int[] done = {0};
            for (Test t : order) {
                futures.add(pool.submit(() -> {
                    Leanchecker.Result r = checker.check(t.file, timeout);
                    t.official = r;
                    if (progress != null) {
                        synchronized (done) {
                            done[0]++;
                            progress.printf("[%d/%d] %-52s %-7s %8.2f s%s%s%n", done[0], order.size(), t.name, r.verdict.lower(),
                                    r.wallSeconds, r.cached ? " (cached)" : "", t.disagreement() ? "  DISAGREES with the arena" : "");
                        }
                    }
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    throw new IllegalStateException("official run failed", e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted", e);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** The verdict cache as JSON (see the class comment). Paths are relative to {@code root} when below it. */
    public static Json verdictsJson(Leanchecker checker, List<Test> tests, Path root, Duration timeout) {
        Json out = Json.object();
        Json meta = Json.object();
        meta.put("what", "verdicts of the official checker (leanchecker --from-export) on arena tests");
        meta.put("toolchain", Leanchecker.PINNED_VERSION);
        meta.put("leanchecker", checker.binary().toString());
        meta.put("generated_utc", Instant.now().toString());
        meta.put("note", "runs over several directories accumulate here by test name; generated_utc is the latest run, "
                + "the timeout that applied to a test is in its entry");
        meta.put("count", tests.size());
        meta.put("exit_codes", "0 accept, 1 reject (stage: kernel, reader, post-check or resource), -1 timeout, other = error");
        out.put("meta", meta);
        out.put("summary", summary(tests));
        Json list = Json.array();
        for (Test t : tests) {
            Json e = Json.object();
            e.put("name", t.name);
            e.put("bucket", t.bucket);
            e.put("arena_outcome", t.arenaOutcome);
            e.put("file", rel(root, t.file));
            e.put("size", t.size);
            Leanchecker.Result r = t.official;
            if (r != null) {
                e.put("sha256", r.sha256);
                e.put("verdict", r.verdict.lower());
                e.put("exit_code", r.exitCode);
                e.put("wall_s", Leanchecker.round3(r.wallSeconds));
                e.put("cpu_s", Leanchecker.round3(r.cpuSeconds));
                e.put("max_rss_kb", r.maxRssKb);
                e.put("stage", r.stage);
                e.put("message", r.accepted() ? null : r.message);
                e.put("agrees_with_arena", !t.disagreement() && !t.undecided());
                e.put("timeout_s", timeout == null ? 0 : timeout.toMillis() / 1000.0);
            }
            list.add(e);
        }
        out.put("tests", list);
        return out;
    }

    /** The manifest of expected outcomes as JSON (see the class comment). */
    public static Json manifestJson(List<Test> tests, Path root) {
        Json out = Json.object();
        Json meta = Json.object();
        meta.put("what", "expected outcome per test = the arena's classification, cross-checked against the official checker "
                + Leanchecker.PINNED_VERSION);
        meta.put("rule", "expected accept: must not reject; expected reject: must not accept; either: any answer; a decline is "
                + "never wrong. 'flag' is DISAGREEMENT when the official verdict contradicts the arena, UNDECIDED when the "
                + "official checker gave no answer (timeout / error).");
        meta.put("generated_utc", Instant.now().toString());
        meta.put("count", tests.size());
        out.put("meta", meta);
        out.put("summary", summary(tests));
        Json flagged = Json.array();
        Json list = Json.array();
        for (Test t : tests) {
            Json e = Json.object();
            e.put("name", t.name);
            e.put("file", rel(root, t.file));
            e.put("expected", t.expected());
            e.put("arena", t.arenaOutcome);
            e.put("official", t.official == null ? null : t.official.verdict.lower());
            if (t.arenaOfficialStatus != null) {
                e.put("arena_official_v4.35.0", t.arenaOfficialStatus);
            }
            String flag = t.disagreement() ? "DISAGREEMENT" : t.undecided() ? "UNDECIDED" : null;
            e.put("flag", flag);
            if (t.official != null && !t.official.accepted()) {
                e.put("official_message", t.official.message);
            }
            if (t.official != null) {
                e.put("sha256", t.official.sha256);
            }
            list.add(e);
            if (flag != null) {
                Json f = Json.object();
                f.put("name", t.name);
                f.put("flag", flag);
                f.put("arena", t.arenaOutcome);
                f.put("official", t.official.verdict.lower());
                f.put("official_message", t.official.message);
                flagged.add(f);
            }
        }
        out.put("flagged", flagged);
        out.put("tests", list);
        return out;
    }

    /** Counts per (arena outcome, official verdict) and the totals. */
    public static Json summary(List<Test> tests) {
        Map<String, Integer> cross = new TreeMap<>();
        int disagreements = 0;
        int undecided = 0;
        double wall = 0;
        double cpu = 0;
        for (Test t : tests) {
            String key = t.arenaOutcome + "/" + (t.official == null ? "not-run" : t.official.verdict.lower());
            cross.merge(key, 1, Integer::sum);
            if (t.disagreement()) {
                disagreements++;
            }
            if (t.undecided()) {
                undecided++;
            }
            if (t.official != null) {
                wall += t.official.wallSeconds;
                cpu += Math.max(0, t.official.cpuSeconds);
            }
        }
        Json s = Json.object();
        s.put("tests", tests.size());
        Json c = Json.object();
        for (Map.Entry<String, Integer> e : cross.entrySet()) {
            c.put(e.getKey(), e.getValue());
        }
        s.put("arena_outcome/official_verdict", c);
        s.put("disagreements", disagreements);
        s.put("undecided", undecided);
        s.put("official_wall_s_total", Leanchecker.round3(wall));
        s.put("official_cpu_s_total", Leanchecker.round3(cpu));
        return s;
    }

    /**
     * Merges the entries of an existing verdicts or manifest file that are not in {@code fresh} (by
     * test name) into it, so that several runs over different directories accumulate in one file.
     */
    public static void mergeOld(Json fresh, Path existing) throws IOException {
        if (!Files.isRegularFile(existing)) {
            return;
        }
        Json old;
        try {
            old = Json.parse(new String(Files.readAllBytes(existing), StandardCharsets.UTF_8));
        } catch (Json.ParseException e) {
            return;
        }
        Json oldTests = old.get("tests");
        Json newTests = fresh.get("tests");
        if (oldTests == null || !oldTests.isArray()) {
            return;
        }
        java.util.Set<String> have = new java.util.HashSet<>();
        for (int i = 0; i < newTests.size(); i++) {
            have.add(newTests.at(i).getText("name", ""));
        }
        List<Json> all = new ArrayList<>();
        for (int i = 0; i < newTests.size(); i++) {
            all.add(newTests.at(i));
        }
        for (int i = 0; i < oldTests.size(); i++) {
            if (!have.contains(oldTests.at(i).getText("name", ""))) {
                all.add(oldTests.at(i));
            }
        }
        Collections.sort(all, (a, b) -> a.getText("name", "").compareTo(b.getText("name", "")));
        Json merged = Json.array();
        for (Json j : all) {
            merged.add(j);
        }
        fresh.put("tests", merged);
        Json meta = fresh.get("meta");
        if (meta != null) {
            meta.put("count", merged.size());
        }
        Json oldFlagged = old.get("flagged");
        Json newFlagged = fresh.get("flagged");
        if (oldFlagged != null && newFlagged != null) {
            for (int i = 0; i < oldFlagged.size(); i++) {
                if (!have.contains(oldFlagged.at(i).getText("name", ""))) {
                    newFlagged.add(oldFlagged.at(i));
                }
            }
        }
        // The summary of a merged file is recomputed from its entries.
        fresh.put("summary", summaryOfEntries(merged));
    }

    private static Json summaryOfEntries(Json entries) {
        Map<String, Integer> cross = new TreeMap<>();
        int disagreements = 0;
        int undecided = 0;
        for (int i = 0; i < entries.size(); i++) {
            Json e = entries.at(i);
            String arena = e.getText("arena_outcome", e.getText("arena", "?"));
            String official = e.getText("verdict", e.getText("official", "not-run"));
            cross.merge(arena + "/" + official, 1, Integer::sum);
            boolean decided = official.equals("accept") || official.equals("reject");
            if (!decided && !official.equals("not-run")) {
                undecided++;
            } else if (decided && !arena.equals("either") && !arena.equals(official)) {
                disagreements++;
            }
        }
        Json s = Json.object();
        s.put("tests", entries.size());
        Json c = Json.object();
        for (Map.Entry<String, Integer> e : cross.entrySet()) {
            c.put(e.getKey(), e.getValue());
        }
        s.put("arena_outcome/official_verdict", c);
        s.put("disagreements", disagreements);
        s.put("undecided", undecided);
        return s;
    }

    static String rel(Path root, Path file) {
        Path abs = file.toAbsolutePath().normalize();
        if (root != null && abs.startsWith(root)) {
            return root.relativize(abs).toString();
        }
        return abs.toString();
    }

    static void writeAtomically(Path file, String content) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = Files.createTempFile(file.toAbsolutePath().getParent(), file.getFileName().toString(), ".tmp");
        Files.write(tmp, content.getBytes(StandardCharsets.UTF_8));
        try {
            // temporary files are private; results are for everybody working in the tree
            Files.setPosixFilePermissions(tmp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
        } catch (UnsupportedOperationException e) {
            // not a POSIX file system
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
