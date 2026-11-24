package rlvm.testkit;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * A corpus of mutants classified by the official checker: the material for "never accept what the
 * official checker rejects".
 *
 * <pre>
 * CORPUS/
 *   index.jsonl            one JSON object per mutant: where it came from, what was changed, the
 *                          official verdict (see {@link Entry})
 *   SUMMARY.md, SUMMARY.json   counts per mutation kind and verdict, anomalies
 *   suite/good/NAME.ndjson     mutants the official checker accepts
 *   suite/bad/NAME.ndjson      mutants the official checker rejects
 *   suite/either/NAME.ndjson   mutants without an official answer (timeout, crash, resource limit of
 *                              the official kernel): any answer is fine
 *   suite/manifest.json        the outcomes in the format bench/run-suite.py reads
 * </pre>
 * {@code suite/} has the layout of an arena tests directory, so {@code bench/run-suite.py --tests
 * CORPUS/suite} scores any checker on it exactly like the arena: its "bad accepted" count is the
 * number of soundness differences. {@link #judge} does the same from Java and explains each
 * difference with the edit that made the mutant.
 */
public final class MutantCorpus {

    /** Marker file that makes a directory eligible for being rebuilt (and therefore emptied) by {@link #build}. */
    public static final String MARKER = ".rlvm-mutant-corpus";

    /** One classified mutant (a line of {@code index.jsonl}). */
    public static final class Entry {
        /** Test name inside {@code suite/}: {@code <source name>/<mutant file stem>}. */
        public String name;
        /** "good", "bad" or "either": the bucket below {@code suite/}. */
        public String bucket;
        /** Source test: name, arena bucket and official verdict. */
        public String sourceName;
        public String sourceBucket;
        public String sourceOfficial;
        /** Path of the source export as given to the builder (relative to the project root when below it). */
        public String sourceFile;
        public String kind;
        public long seed;
        public int index;
        public int line;
        public String description;
        public boolean verdictPreserving;
        public String sha256;
        public long size;
        /** accept / reject / timeout / error. */
        public String official;
        public int officialExit;
        /** kernel / reader / resource / "". */
        public String officialStage;
        public String officialMessage;
        public double wallSeconds;
        /** null, "VERDICT_CHANGED" (a verdict-preserving kind changed the official verdict) or "UNDECIDED". */
        public String anomaly;

        Json toJson() {
            Json o = Json.object();
            o.put("name", name);
            o.put("bucket", bucket);
            o.put("file", "suite/" + bucket + "/" + name + ".ndjson");
            o.put("source", sourceName);
            o.put("source_bucket", sourceBucket);
            o.put("source_official", sourceOfficial);
            o.put("source_file", sourceFile);
            o.put("kind", kind);
            o.put("seed", seed);
            o.put("index", index);
            o.put("line", line);
            o.put("description", description);
            o.put("verdict_preserving", verdictPreserving);
            o.put("sha256", sha256);
            o.put("size", size);
            o.put("official", official);
            o.put("official_exit", officialExit);
            o.put("official_stage", officialStage);
            o.put("official_message", officialMessage);
            o.put("wall_s", Leanchecker.round3(wallSeconds));
            o.put("anomaly", anomaly);
            return o;
        }

        static Entry fromJson(Json o) {
            Entry e = new Entry();
            e.name = o.getText("name", "");
            e.bucket = o.getText("bucket", "");
            e.sourceName = o.getText("source", "");
            e.sourceBucket = o.getText("source_bucket", "");
            e.sourceOfficial = o.getText("source_official", "");
            e.sourceFile = o.getText("source_file", "");
            e.kind = o.getText("kind", "");
            e.seed = o.getLong("seed", 0);
            e.index = (int) o.getLong("index", 0);
            e.line = (int) o.getLong("line", 0);
            e.description = o.getText("description", "");
            Json vp = o.get("verdict_preserving");
            e.verdictPreserving = vp != null && vp.isBool() && vp.asBool();
            e.sha256 = o.getText("sha256", "");
            e.size = o.getLong("size", 0);
            e.official = o.getText("official", "");
            e.officialExit = (int) o.getDouble("official_exit", 3);
            e.officialStage = o.getText("official_stage", "");
            e.officialMessage = o.getText("official_message", "");
            e.wallSeconds = o.getDouble("wall_s", 0);
            e.anomaly = o.getText("anomaly", null);
            return e;
        }
    }

    /** Parameters of {@link #build}. */
    public static final class Options {
        /** An arena-style tests directory ({@code good/}, {@code bad/}). */
        public Path sources;
        /** Glob on test names, e.g. {@code tutorial/*}; null = all. */
        public String only = "tutorial/*";
        public Path out;
        /** Mutants requested per (source, kind). */
        public int perKind = 4;
        public long seed = 1;
        public int jobs = 4;
        public Duration timeout = Duration.ofSeconds(60);
        /** Address-space cap for the official checker on a mutant; 0 = none. */
        public long memoryLimitBytes = 8L << 30;
        /** Sources larger than this are skipped. */
        public long maxSourceBytes = 4L << 20;
        /** False: only generate (everything lands in {@code suite/either}). */
        public boolean classify = true;
        public List<ExportMutator.Mutation> kinds = java.util.Arrays.asList(ExportMutator.Mutation.values());
    }

    private MutantCorpus() {}

    // ---- building -------------------------------------------------------------------------------------

    /** Generates, classifies and lays out the corpus; returns its entries sorted by name. */
    public static List<Entry> build(Options opt, Leanchecker checker, PrintStream progress) throws IOException {
        Path out = opt.out.toAbsolutePath().normalize();
        prepare(out);
        Path root = Leanchecker.projectRoot();
        Path staging = out.resolve("staging");
        Path suite = out.resolve("suite");

        List<OfficialVerdicts.Test> sources = OfficialVerdicts.discover(opt.sources, opt.maxSourceBytes);
        sources.removeIf(t -> !Main.glob(opt.only, t.name));
        if (opt.classify) {
            // verdicts of the sources themselves (from the cache when the oracle command ran before)
            OfficialVerdicts.collect(checker, sources, opt.jobs, Duration.ofSeconds(900), null);
        }

        ExportMutator mutator = new ExportMutator(opt.seed);
        List<Entry> entries = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int duplicates = 0;
        for (OfficialVerdicts.Test src : sources) {
            Path dir = staging.resolve(src.name);
            for (ExportMutator.Mutation kind : opt.kinds) {
                for (ExportMutator.Mutant m : mutator.mutants(src.file, kind, opt.perKind, dir)) {
                    if (!seen.add(m.sha256)) {
                        Files.delete(m.path); // the same bytes were produced by another kind
                        duplicates++;
                        continue;
                    }
                    Entry e = new Entry();
                    String file = m.path.getFileName().toString();
                    e.name = src.name + "/" + file.substring(0, file.length() - ".ndjson".length());
                    e.sourceName = src.name;
                    e.sourceBucket = src.bucket;
                    e.sourceOfficial = src.official == null ? "" : src.official.verdict.lower();
                    e.sourceFile = OfficialVerdicts.rel(root, src.file);
                    e.kind = kind.name();
                    e.seed = m.seed;
                    e.index = m.index;
                    e.line = m.line;
                    e.description = m.description;
                    e.verdictPreserving = kind.verdictPreserving;
                    e.sha256 = m.sha256;
                    e.size = m.size;
                    entries.add(e);
                    files.add(m.path);
                }
            }
        }
        if (progress != null) {
            progress.printf("corpus: %d mutants of %d sources generated (%d duplicates dropped)%n", entries.size(), sources.size(),
                    duplicates);
        }

        if (opt.classify) {
            Leanchecker limited = opt.memoryLimitBytes > 0 ? checker.withMemoryLimit(opt.memoryLimitBytes) : checker;
            classify(limited, entries, files, opt.jobs, opt.timeout, progress);
        } else {
            for (Entry e : entries) {
                e.official = "";
                e.officialStage = "";
                e.officialMessage = "";
                e.bucket = "either";
            }
        }

        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            Path target = suite.resolve(e.bucket).resolve(e.name + ".ndjson");
            Files.createDirectories(target.getParent());
            Files.move(files.get(i), target, StandardCopyOption.REPLACE_EXISTING);
        }
        deleteTree(staging);
        entries.sort(Comparator.comparing(e -> e.name));
        writeIndex(out, entries);
        writeSummary(out, entries, opt, sources.size(), duplicates);
        return entries;
    }

    /** Asks the official checker about every file; fills verdict, bucket and anomaly of the entries. */
    static void classify(Leanchecker checker, List<Entry> entries, List<Path> files, int jobs, Duration timeout,
            PrintStream progress) {
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, jobs), r -> {
            Thread t = new Thread(r, "classify");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<?>> futures = new ArrayList<>();
            int[] done = {0};
            for (int i = 0; i < entries.size(); i++) {
                Entry e = entries.get(i);
                Path f = files.get(i);
                futures.add(pool.submit(() -> {
                    apply(e, checker.check(f, timeout));
                    synchronized (done) {
                        done[0]++;
                        if (progress != null && (done[0] % 500 == 0 || done[0] == entries.size())) {
                            progress.printf("corpus: %d / %d classified%n", done[0], entries.size());
                        }
                    }
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (java.util.concurrent.ExecutionException ex) {
                    throw new IllegalStateException("classification failed", ex.getCause());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted", ex);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** Records an official result in an entry: verdict, bucket, anomaly. */
    static void apply(Entry e, Leanchecker.Result r) {
        e.official = r.verdict.lower();
        e.officialExit = r.exitCode;
        e.officialStage = r.stage;
        e.officialMessage = r.message;
        e.wallSeconds = r.wallSeconds;
        boolean hard = r.decided() && !r.stage.equals("resource");
        e.bucket = !hard ? "either" : r.accepted() ? "good" : "bad";
        if (!hard) {
            e.anomaly = "UNDECIDED";
        } else if (e.verdictPreserving && !e.sourceOfficial.isEmpty() && !e.sourceOfficial.equals(e.official)) {
            e.anomaly = "VERDICT_CHANGED";
        } else {
            e.anomaly = null;
        }
    }

    private static void prepare(Path out) throws IOException {
        if (Files.isDirectory(out)) {
            boolean empty;
            try (Stream<Path> s = Files.list(out)) {
                empty = !s.findAny().isPresent();
            }
            if (!empty && !Files.isRegularFile(out.resolve(MARKER))) {
                throw new IOException(out + " is not empty and has no " + MARKER + " file; refusing to rebuild a corpus there");
            }
            deleteTree(out.resolve("suite"));
            deleteTree(out.resolve("staging"));
        }
        Files.createDirectories(out);
        Files.write(out.resolve(MARKER), ("mutant corpus written by rlvm.testkit.MutantCorpus; safe to delete and rebuild\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> all = new ArrayList<>();
            walk.forEach(all::add);
            for (int i = all.size() - 1; i >= 0; i--) {
                Files.delete(all.get(i));
            }
        }
    }

    private static void writeIndex(Path out, List<Entry> entries) throws IOException {
        StringBuilder sb = new StringBuilder();
        Json manifest = Json.object();
        Json tests = Json.array();
        for (Entry e : entries) {
            sb.append(Json.write(e.toJson())).append('\n');
            Json t = Json.object();
            t.put("name", e.name);
            t.put("outcome", e.bucket.equals("good") ? "accept" : e.bucket.equals("bad") ? "reject" : "either");
            tests.add(t);
        }
        manifest.put("meta", Json.object().put("what", "mutants classified by the official checker " + Leanchecker.PINNED_VERSION
                + "; see ../index.jsonl"));
        manifest.put("tests", tests);
        OfficialVerdicts.writeAtomically(out.resolve("index.jsonl"), sb.toString());
        if (Files.isDirectory(out.resolve("suite"))) {
            OfficialVerdicts.writeAtomically(out.resolve("suite").resolve("manifest.json"), Json.write(manifest) + "\n");
        }
    }

    /** Reads {@code CORPUS/index.jsonl}. */
    public static List<Entry> readIndex(Path corpus) throws IOException {
        List<Entry> entries = new ArrayList<>();
        for (String line : Files.readAllLines(corpus.resolve("index.jsonl"), StandardCharsets.UTF_8)) {
            if (!line.isEmpty()) {
                entries.add(Entry.fromJson(Json.parse(line)));
            }
        }
        return entries;
    }

    private static void writeSummary(Path out, List<Entry> entries, Options opt, int sources, int duplicates) throws IOException {
        Map<String, int[]> perKind = new TreeMap<>(); // accept, reject, undecided, changed
        int[] total = new int[4];
        Map<String, Integer> perSource = new TreeMap<>();
        Map<String, Integer> stages = new TreeMap<>();
        List<Entry> anomalies = new ArrayList<>();
        for (Entry e : entries) {
            int[] c = perKind.computeIfAbsent(e.kind, k -> new int[4]);
            int col = e.bucket.equals("good") ? 0 : e.bucket.equals("bad") ? 1 : 2;
            c[col]++;
            total[col]++;
            if ("VERDICT_CHANGED".equals(e.anomaly)) {
                c[3]++;
                total[3]++;
            }
            perSource.merge(e.sourceBucket + " source, official " + e.sourceOfficial + " -> mutant " + e.official, 1, Integer::sum);
            if (e.bucket.equals("bad")) {
                stages.merge(e.officialStage, 1, Integer::sum);
            }
            if (e.anomaly != null) {
                anomalies.add(e);
            }
        }
        Json s = Json.object();
        s.put("generated_utc", Instant.now().toString());
        s.put("toolchain", Leanchecker.PINNED_VERSION);
        s.put("sources_dir", OfficialVerdicts.rel(Leanchecker.projectRoot(), opt.sources));
        s.put("only", opt.only);
        s.put("sources", sources);
        s.put("seed", opt.seed);
        s.put("per_kind", opt.perKind);
        s.put("timeout_s", opt.timeout.toMillis() / 1000.0);
        s.put("memory_limit_bytes", opt.memoryLimitBytes);
        s.put("mutants", entries.size());
        s.put("duplicates_dropped", duplicates);
        s.put("official_accept", total[0]);
        s.put("official_reject", total[1]);
        s.put("undecided", total[2]);
        s.put("verdict_changed_by_a_preserving_kind", total[3]);
        Json kinds = Json.object();
        for (Map.Entry<String, int[]> k : perKind.entrySet()) {
            int[] c = k.getValue();
            kinds.put(k.getKey(), Json.object().put("accept", c[0]).put("reject", c[1]).put("undecided", c[2]).put("verdict_changed", c[3]));
        }
        s.put("by_kind", kinds);
        Json ps = Json.object();
        for (Map.Entry<String, Integer> k : perSource.entrySet()) {
            ps.put(k.getKey(), k.getValue());
        }
        s.put("by_source_and_verdict", ps);
        Json st = Json.object();
        for (Map.Entry<String, Integer> k : stages.entrySet()) {
            st.put(k.getKey(), k.getValue());
        }
        s.put("rejections_by_stage", st);
        Json an = Json.array();
        for (Entry e : anomalies) {
            an.add(Json.object().put("name", e.name).put("anomaly", e.anomaly).put("kind", e.kind).put("description", e.description)
                    .put("source_official", e.sourceOfficial).put("official", e.official).put("official_message", e.officialMessage));
        }
        s.put("anomalies", an);
        OfficialVerdicts.writeAtomically(out.resolve("SUMMARY.json"), Json.pretty(s));

        StringBuilder md = new StringBuilder();
        md.append("# Mutant corpus\n\n");
        md.append(String.format(Locale.ROOT, "%d mutants of %d sources (`%s`, filter `%s`), seed %d, up to %d per source and kind; "
                + "%d byte-identical duplicates dropped. Classified by the official checker %s (timeout %.0f s, address space %s).%n%n",
                entries.size(), sources, s.getText("sources_dir", ""), opt.only, opt.seed, opt.perKind, duplicates,
                Leanchecker.PINNED_VERSION, opt.timeout.toMillis() / 1000.0,
                opt.memoryLimitBytes > 0 ? (opt.memoryLimitBytes >> 20) + " MiB" : "unlimited"));
        md.append("| kind | must keep the verdict | official accepts | official rejects | undecided | verdict changed |\n");
        md.append("|---|---|---:|---:|---:|---:|\n");
        for (Map.Entry<String, int[]> k : perKind.entrySet()) {
            int[] c = k.getValue();
            boolean vp = ExportMutator.Mutation.valueOf(k.getKey()).verdictPreserving;
            md.append(String.format(Locale.ROOT, "| %s | %s | %d | %d | %d | %s |%n", k.getKey(), vp ? "yes" : "", c[0], c[1], c[2],
                    vp ? String.valueOf(c[3]) : ""));
        }
        md.append(String.format(Locale.ROOT, "| **total** | | %d | %d | %d | %d |%n%n", total[0], total[1], total[2], total[3]));
        md.append("Source verdict to mutant verdict:\n\n");
        for (Map.Entry<String, Integer> k : perSource.entrySet()) {
            md.append("- ").append(k.getKey()).append(": ").append(k.getValue()).append('\n');
        }
        md.append("\nRejections by stage of the official checker: ").append(stages).append("\n\n");
        md.append("`suite/` is an arena-style tests directory: `good/` = the official checker accepts, `bad/` = it rejects, "
                + "`either/` = it gave no answer. `index.jsonl` has one line per mutant with the edit.\n\n");
        // What the official checker reads: verdicts of single-field edits of accepted sources, by field.
        Map<String, int[]> perField = new TreeMap<>();
        for (Entry e : entries) {
            if (!"accept".equals(e.sourceOfficial) || e.bucket.equals("either") || !e.description.startsWith("line ")) {
                continue;
            }
            int sp = e.description.indexOf(' ', 5);
            int colon = e.description.indexOf(": ", 5);
            if (sp < 0 || colon < 0 || colon < sp) {
                continue;
            }
            String path = e.description.substring(sp + 1, colon);
            if (path.indexOf(' ') >= 0) {
                continue; // not a field path ("declaration kind", "record dropped", ...)
            }
            path = path.replaceAll("\\[\\d+\\]", "[]").replaceFirst("^inductive\\.", "");
            int[] c = perField.computeIfAbsent(e.kind + " " + path, k -> new int[2]);
            c[e.bucket.equals("good") ? 0 : 1]++;
        }
        if (!perField.isEmpty()) {
            md.append("## Edited field against official verdict (mutants of accepted sources)\n\n");
            md.append("A field whose edits are all accepted is one the official checker does not read; a checker that "
                    + "rejects such a mutant is wrong, one that declines is merely narrower.\n\n");
            md.append("| kind | field | accepted | rejected |\n|---|---|---:|---:|\n");
            for (Map.Entry<String, int[]> k : perField.entrySet()) {
                int sp = k.getKey().indexOf(' ');
                md.append("| ").append(k.getKey(), 0, sp).append(" | `").append(k.getKey().substring(sp + 1)).append("` | ")
                        .append(k.getValue()[0]).append(" | ").append(k.getValue()[1]).append(" |\n");
            }
            md.append('\n');
        }
        if (anomalies.isEmpty()) {
            md.append("No anomalies.\n");
        } else {
            md.append("## Anomalies (").append(anomalies.size()).append(")\n\n");
            md.append("`VERDICT_CHANGED`: a mutation that should not matter changed the official verdict. "
                    + "`UNDECIDED`: no official answer (timeout, crash, resource limit).\n\n");
            int shown = 0;
            for (Entry e : anomalies) {
                if (shown++ == 200) {
                    md.append("- ... ").append(anomalies.size() - 200).append(" more in SUMMARY.json\n");
                    break;
                }
                md.append("- `").append(e.name).append("` ").append(e.anomaly).append(": ").append(e.description.replace("|", "\\|"))
                        .append("; source ").append(e.sourceOfficial).append(", mutant ").append(e.official)
                        .append(e.officialMessage.isEmpty() ? "" : " (" + truncate(e.officialMessage, 160) + ")").append('\n');
            }
        }
        OfficialVerdicts.writeAtomically(out.resolve("SUMMARY.md"), md.toString());
    }

    private static String truncate(String s, int n) {
        return s.length() > n ? s.substring(0, n - 3) + "..." : s;
    }

    /** One of the default corpora: what {@code testkit corpus --all} builds and {@code testkit judge --all} judges. */
    static final class Default {
        final String name;
        /** Directory below the project root. */
        final String dir;
        final String only;
        final int perKind;
        /** Comma list of kinds, or null for every kind. */
        final String kinds;
        final int timeoutSeconds;

        Default(String name, String dir, String only, int perKind, String kinds, int timeoutSeconds) {
            this.name = name;
            this.dir = dir;
            this.only = only;
            this.perKind = perKind;
            this.kinds = kinds;
            this.timeoutSeconds = timeoutSeconds;
        }
    }

    /**
     * The default corpora (seed 1, sources {@code work/arena-site/tests}). The first three are built
     * with every mutation kind, {@code WRAP_MDATA} included; the fourth goes deeper on metadata alone
     * (it is the regression guard of the loader's handling of {@code mdata} records).
     */
    static final Default[] DEFAULTS = {
        new Default("tutorial", "work/mutants", "tutorial/*", 5, null, 30),
        new Default("bugs", "work/mutants-bugs-other", "{bugs,other,corner-cases}/*", 3, null, 60),
        new Default("prelude", "work/mutants-prelude", "init-prelude", 40, null, 60),
        new Default("mdata", "work/mutants-mdata", "{tutorial,bugs,other,corner-cases}/*", 12, "WRAP_MDATA", 30),
    };

    private static List<ExportMutator.Mutation> parseKinds(String kinds) {
        List<ExportMutator.Mutation> list = new ArrayList<>();
        for (String k : kinds.split(",")) {
            try {
                list.add(ExportMutator.Mutation.valueOf(k.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new Main.UsageException("unknown mutation kind '" + k + "'");
            }
        }
        return list;
    }

    /**
     * {@code testkit corpus --all}: rebuilds every default corpus. Each is built next to the old one
     * ({@code DIR.rebuild}) and then moved into its place, so that a judge running on the old corpus
     * meanwhile loses no file: mutants are a function of (seed, source, kind, index), the old ones
     * come out byte for byte under the same names.
     */
    private static int buildAll(Main.Args a, PrintStream out, PrintStream err) throws IOException {
        Path root = Main.root();
        Leanchecker checker = Leanchecker.locate();
        if (!checker.available()) {
            err.println("testkit: official checker not found: " + checker.binary() + " (set RLVM_LEAN_TOOLCHAIN)");
            return 1;
        }
        String onlyCorpus = a.get("--corpus", null);
        int rc = 0;
        for (Default d : DEFAULTS) {
            if (onlyCorpus != null && !onlyCorpus.equals(d.name)) {
                continue;
            }
            Path target = root.resolve(d.dir);
            Path fresh = target.resolveSibling(target.getFileName() + ".rebuild");
            Options opt = new Options();
            opt.sources = root.resolve("work/arena-site/tests");
            opt.out = fresh;
            opt.only = d.only;
            opt.perKind = d.perKind;
            opt.seed = 1;
            opt.jobs = a.getInt("-j", 6);
            opt.timeout = Duration.ofSeconds(d.timeoutSeconds);
            if (d.kinds != null) {
                opt.kinds = parseKinds(d.kinds);
            }
            long t0 = System.nanoTime();
            List<Entry> entries = build(opt, checker, err);
            int accept = 0;
            int reject = 0;
            int undecided = 0;
            int changed = 0;
            for (Entry e : entries) {
                if (e.bucket.equals("good")) {
                    accept++;
                } else if (e.bucket.equals("bad")) {
                    reject++;
                } else {
                    undecided++;
                }
                if ("VERDICT_CHANGED".equals(e.anomaly)) {
                    changed++;
                }
            }
            swapIn(fresh, target);
            out.printf("corpus %-8s %s: %d mutants; official accepts %d, rejects %d, undecided %d; verdict changed by a"
                    + " preserving kind: %d  (%.0f s)%n", d.name, d.dir, entries.size(), accept, reject, undecided, changed,
                    (System.nanoTime() - t0) / 1e9);
            if (changed > 0) {
                rc = 1;
            }
        }
        return rc;
    }

    /** Puts a freshly built corpus in the place of the old one (two renames), then deletes the old one. */
    static void swapIn(Path fresh, Path target) throws IOException {
        Path old = target.resolveSibling(target.getFileName() + ".old");
        deleteTree(old);
        if (Files.exists(target)) {
            if (Files.isDirectory(target) && !Files.isRegularFile(target.resolve(MARKER))) {
                throw new IOException(target + " has no " + MARKER + " file; refusing to replace it");
            }
            Files.move(target, old, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        }
        Files.move(fresh, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        deleteTree(old);
    }

    static int buildCommand(Main.Args a, PrintStream out, PrintStream err) throws IOException {
        if (a.flag("--all")) {
            return buildAll(a, out, err);
        }
        Path root = Main.root();
        Options opt = new Options();
        opt.sources = Paths.get(a.get("--sources", root.resolve("work/arena-site/tests").toString()));
        opt.out = Paths.get(a.get("--out", root.resolve("work/mutants").toString()));
        opt.only = a.get("--only", "tutorial/*");
        if (opt.only.equals("*") || opt.only.equals("**")) {
            opt.only = null;
        }
        opt.perKind = a.getInt("--per-kind", 4);
        opt.seed = a.getLong("--seed", 1);
        opt.jobs = a.getInt("-j", 4);
        opt.timeout = Duration.ofSeconds(a.getLong("--timeout", 60));
        opt.memoryLimitBytes = a.getLong("--memory-limit", 8L << 30);
        opt.maxSourceBytes = a.getLong("--max-source-bytes", 4L << 20);
        opt.classify = !a.flag("--no-classify");
        String kinds = a.get("--kinds", null);
        if (kinds != null) {
            opt.kinds = parseKinds(kinds);
        }
        Leanchecker checker = Leanchecker.locate();
        if (opt.classify && !checker.available()) {
            err.println("testkit: official checker not found: " + checker.binary() + " (set RLVM_LEAN_TOOLCHAIN, or use --no-classify)");
            return 1;
        }
        List<Entry> entries = build(opt, checker, err);
        int accept = 0;
        int reject = 0;
        int undecided = 0;
        int changed = 0;
        for (Entry e : entries) {
            if (e.bucket.equals("good")) {
                accept++;
            } else if (e.bucket.equals("bad")) {
                reject++;
            } else {
                undecided++;
            }
            if ("VERDICT_CHANGED".equals(e.anomaly)) {
                changed++;
            }
        }
        out.printf("corpus %s: %d mutants; official accepts %d, rejects %d, undecided %d; verdict changed by a preserving kind: %d%n",
                opt.out, entries.size(), accept, reject, undecided, changed);
        out.println("see " + opt.out.resolve("SUMMARY.md"));
        return 0;
    }

    // ---- judging a checker ---------------------------------------------------------------------------------

    /** Result of one checker run on one mutant. */
    public static final class Judgement {
        public final Entry entry;
        /** accepted / rejected / declined / error / timeout. */
        public final String status;
        public final int exitCode;
        public final double wallSeconds;
        public final String outputTail;

        Judgement(Entry entry, String status, int exitCode, double wallSeconds, String outputTail) {
            this.entry = entry;
            this.status = status;
            this.exitCode = exitCode;
            this.wallSeconds = wallSeconds;
            this.outputTail = outputTail;
        }

        /** SOUNDNESS: accepted although the official checker rejects. COMPLETENESS: rejected although it accepts. Else null. */
        public String difference() {
            if (status.equals("accepted") && entry.bucket.equals("bad")) {
                return "SOUNDNESS";
            }
            if (status.equals("rejected") && entry.bucket.equals("good")) {
                return "COMPLETENESS";
            }
            return null;
        }
    }

    /**
     * Runs {@code command} ({@code sh -c}, export path in {@code $IN}, exit 0 accept / 1 reject / 2
     * decline, as on the arena) on every entry of the corpus.
     */
    public static List<Judgement> judge(Path corpus, List<Entry> entries, String command, Path cwd, int jobs, Duration timeout,
            PrintStream progress) {
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, jobs), r -> {
            Thread t = new Thread(r, "judge");
            t.setDaemon(true);
            return t;
        });
        List<Judgement> results = new ArrayList<>();
        try {
            List<Future<Judgement>> futures = new ArrayList<>();
            int[] done = {0};
            for (Entry e : entries) {
                futures.add(pool.submit(() -> {
                    Judgement j = runChecker(corpus, e, command, cwd, timeout);
                    synchronized (done) {
                        done[0]++;
                        if (progress != null && (done[0] % 500 == 0 || done[0] == entries.size())) {
                            progress.printf("judge: %d / %d%n", done[0], entries.size());
                        }
                    }
                    return j;
                }));
            }
            for (Future<Judgement> f : futures) {
                try {
                    results.add(f.get());
                } catch (java.util.concurrent.ExecutionException ex) {
                    throw new IllegalStateException("checker run failed", ex.getCause());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted", ex);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return results;
    }

    private static Judgement runChecker(Path corpus, Entry e, String command, Path cwd, Duration timeout) {
        Path file = corpus.resolve("suite").resolve(e.bucket).resolve(e.name + ".ndjson").toAbsolutePath();
        Path log = null;
        Process p = null;
        long t0 = System.nanoTime();
        try {
            log = Files.createTempFile("judge-", ".log");
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            pb.environment().put("IN", file.toString());
            pb.environment().put("LC_ALL", "C");
            if (cwd != null) {
                pb.directory(cwd.toFile());
            }
            pb.redirectInput(ProcessBuilder.Redirect.from(Paths.get("/dev/null").toFile()));
            pb.redirectErrorStream(true);
            pb.redirectOutput(log.toFile());
            p = pb.start();
            boolean finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                p.waitFor(10, TimeUnit.SECONDS);
            }
            double wall = (System.nanoTime() - t0) / 1e9;
            String tail = tail(log, 600);
            if (!finished) {
                return new Judgement(e, "timeout", -1, wall, tail);
            }
            int exit = p.exitValue();
            String status = exit == 0 ? "accepted" : exit == 1 ? "rejected" : exit == 2 ? "declined" : "error";
            return new Judgement(e, status, exit, wall, tail);
        } catch (IOException ex) {
            return new Judgement(e, "error", 70, 0, "cannot run the checker: " + ex.getMessage());
        } catch (InterruptedException ex) {
            if (p != null) {
                p.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            return new Judgement(e, "error", 70, 0, "interrupted");
        } finally {
            if (log != null) {
                try {
                    Files.deleteIfExists(log);
                } catch (IOException ignored) {
                    // harmless
                }
            }
        }
    }

    private static String tail(Path file, int maxChars) {
        try {
            byte[] all = Files.readAllBytes(file);
            String s = new String(all, StandardCharsets.UTF_8).trim();
            return s.length() > maxChars ? s.substring(s.length() - maxChars) : s;
        } catch (IOException e) {
            return "";
        }
    }

    /** The totals of one corpus: SOUNDNESS, COMPLETENESS, agree, declined, error or timeout, answered on an undecided mutant. */
    static final class Totals {
        final String name;
        final Path corpus;
        int mutants;
        final int[] counts = new int[6];
        double seconds;
        Path report;

        Totals(String name, Path corpus) {
            this.name = name;
            this.corpus = corpus;
        }
    }

    /**
     * {@code testkit judge}: one corpus ({@code --corpus DIR}, default work/mutants), or with
     * {@code --all} every default corpus that exists, with one summary table at the end that is also
     * appended to {@code work/status/mutants.md} ({@code --summary FILE}; {@code --no-summary}).
     */
    static int judgeCommand(Main.Args a, PrintStream out, PrintStream err) throws IOException {
        Path root = Main.root();
        if (a.rest.isEmpty()) {
            throw new Main.UsageException("judge needs a checker command after --");
        }
        String command = String.join(" ", a.rest);
        if (!a.flag("--all")) {
            Path corpus = Paths.get(a.get("--corpus", root.resolve("work/mutants").toString()));
            Totals t = judgeCorpus(root, new Totals(corpus.getFileName().toString(), corpus), command, a, out, err);
            if (t == null) {
                return 1;
            }
            return t.counts[0] > 0 || t.counts[1] > 0 ? 1 : 0;
        }
        return judgeAll(root, DEFAULTS, command, a, out, err);
    }

    /** {@code judge --all}: every corpus of {@code defaults} that exists below {@code root}, then the summary table. */
    static int judgeAll(Path root, Default[] defaults, String command, Main.Args a, PrintStream out, PrintStream err)
            throws IOException {
        List<Totals> all = new ArrayList<>();
        long t0 = System.nanoTime();
        for (Default d : defaults) {
            Path corpus = root.resolve(d.dir);
            if (!Files.isRegularFile(corpus.resolve("index.jsonl"))) {
                err.println("judge: corpus " + d.name + " (" + d.dir + ") is not built: testkit corpus --all");
                continue;
            }
            out.println();
            out.println("=== corpus " + d.name + " (" + d.dir + ")");
            Totals t = judgeCorpus(root, new Totals(d.name, corpus), command, a, out, err);
            if (t != null) {
                all.add(t);
            }
        }
        if (all.isEmpty()) {
            err.println("testkit: no corpus to judge");
            return 1;
        }
        double seconds = (System.nanoTime() - t0) / 1e9;
        int[] sum = new int[6];
        int mutants = 0;
        StringBuilder md = new StringBuilder();
        md.append("\n## ").append(Instant.now().toString().replaceAll("\\.\\d+Z$", "Z")).append("  `").append(command).append("`\n\n");
        String note = a.get("--note", null);
        String dist = distribution(root);
        md.append(note == null ? "" : note + "\n\n").append(dist == null ? "" : dist + "\n\n");
        md.append("| Corpus | Mutants | SOUNDNESS | COMPLETENESS | agree | declined | error / timeout | answered on undecided | wall s |\n");
        md.append("|---|--:|--:|--:|--:|--:|--:|--:|--:|\n");
        out.println();
        out.printf("%-34s %8s %9s %12s %7s %8s %6s %10s %7s%n", "corpus", "mutants", "SOUNDNESS", "COMPLETENESS", "agree", "declined",
                "error", "undecided", "wall s");
        for (Totals t : all) {
            int[] c = t.counts;
            out.printf("%-34s %8d %9d %12d %7d %8d %6d %10d %7.0f%n", t.name + " (" + root.relativize(t.corpus) + ")", t.mutants, c[0],
                    c[1], c[2], c[3], c[4], c[5], t.seconds);
            md.append(String.format("| %s (`%s`) | %,d | %d | %d | %,d | %,d | %d | %d | %.0f |%n", t.name, root.relativize(t.corpus),
                    t.mutants, c[0], c[1], c[2], c[3], c[4], c[5], t.seconds));
            for (int i = 0; i < 6; i++) {
                sum[i] += c[i];
            }
            mutants += t.mutants;
        }
        out.printf("%-34s %8d %9d %12d %7d %8d %6d %10d %7.0f%n", "total", mutants, sum[0], sum[1], sum[2], sum[3], sum[4], sum[5],
                seconds);
        md.append(String.format("| **total** | **%,d** | **%d** | **%d** | %,d | %,d | %d | %d | %.0f |%n", mutants, sum[0], sum[1], sum[2],
                sum[3], sum[4], sum[5], seconds));
        boolean failed = sum[0] > 0 || sum[1] > 0;
        String result = failed ? "FAILED: " + sum[0] + " mutant(s) accepted although the official checker rejects them, " + sum[1]
                + " rejected although it accepts them" : sum[4] > 0 ? "no difference, but " + sum[4]
                + " run(s) ended with an error or a timeout: not a clean result" : "PASS: no difference from the official checker";
        out.println(result);
        md.append("\n").append(result).append(". ").append(a.getInt("-j", 4)).append(" mutants at a time; reports: ");
        for (int i = 0; i < all.size(); i++) {
            md.append(i == 0 ? "" : ", ").append('`').append(root.relativize(all.get(i).report)).append('`');
        }
        md.append(".\n");
        if (!a.flag("--no-summary")) {
            Path summary = Paths.get(a.get("--summary", root.resolve("work/status/mutants.md").toString()));
            if (!Files.exists(summary)) {
                Files.createDirectories(summary.toAbsolutePath().getParent());
                Files.write(summary, ("# Mutant campaigns\n\nOne entry per `rlvm-testkit/bin/testkit judge --all -- CHECKER` (newest at the"
                        + " bottom). SOUNDNESS = a mutant accepted although the official checker (leanchecker 4.36.0) rejects it;\n"
                        + "COMPLETENESS = rejected although it accepts; a decline is never wrong. Both must be 0. An error or a timeout"
                        + " is not a verdict:\ncheck that column before reading zeros as good news.\n").getBytes(StandardCharsets.UTF_8));
            }
            Files.write(summary, md.toString().getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND);
            out.println("summary appended to " + root.relativize(summary.toAbsolutePath()));
        }
        return failed ? 1 : 0;
    }

    /** One line that identifies what was judged when the checker is RLVM's distribution: the build stamp of work/dist. */
    private static String distribution(Path root) {
        String dir = System.getenv("RLVM_DIST");
        Path info = (dir == null || dir.isEmpty() ? root.resolve("work/dist") : root.resolve(dir)).resolve("BUILD-INFO.txt");
        try {
            StringBuilder jars = new StringBuilder();
            String built = null;
            for (String line : Files.readAllLines(info, StandardCharsets.UTF_8)) {
                if (line.startsWith("built:")) {
                    built = line.substring(6).trim();
                } else if (line.startsWith("  rlvm-")) {
                    String[] f = line.trim().split("\\s+");
                    if (f.length >= 2) {
                        jars.append(jars.length() == 0 ? "" : ", ").append(f[0].substring(5)).append(' ').append(f[1]);
                    }
                }
            }
            return built == null ? null : "Distribution `" + root.relativize(info.getParent()) + "` built " + built + " (jars: " + jars + ").";
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Judges one corpus, prints its table and writes its report; null if nothing was selected. */
    private static Totals judgeCorpus(Path root, Totals totals, String command, Main.Args a, PrintStream out, PrintStream err)
            throws IOException {
        Path corpus = totals.corpus;
        int jobs = a.getInt("-j", 4);
        Duration timeout = Duration.ofSeconds(a.getLong("--timeout", 60));
        String only = a.get("--only", null);
        String kind = a.get("--kind", null);
        int limit = a.getInt("--limit", Integer.MAX_VALUE);
        String cwdOpt = a.get("--cwd", null);
        Path cwd = cwdOpt == null ? null : Paths.get(cwdOpt);
        long started = System.nanoTime();
        List<Entry> entries = readIndex(corpus);
        entries.removeIf(e -> !Main.glob(only, e.name) || (kind != null && !kind.equalsIgnoreCase(e.kind)));
        if (entries.size() > limit) {
            entries = new ArrayList<>(entries.subList(0, limit));
        }
        if (entries.isEmpty()) {
            err.println("testkit: no mutants selected in " + corpus);
            return null;
        }
        err.printf("judge: %d mutants of %s, %d at a time, timeout %d s, checker: %s%n", entries.size(), corpus, jobs,
                timeout.getSeconds(), command);
        List<Judgement> results = judge(corpus, entries, command, cwd, jobs, timeout, err);

        Map<String, int[]> perKind = new TreeMap<>(); // soundness, completeness, agree, declined, error+timeout, on-undecided
        int[] total = new int[6];
        Json rows = Json.array();
        List<Judgement> differences = new ArrayList<>();
        for (Judgement j : results) {
            int[] c = perKind.computeIfAbsent(j.entry.kind, k -> new int[6]);
            String d = j.difference();
            int col;
            if ("SOUNDNESS".equals(d)) {
                col = 0;
            } else if ("COMPLETENESS".equals(d)) {
                col = 1;
            } else if (j.status.equals("declined")) {
                col = 3;
            } else if (j.status.equals("error") || j.status.equals("timeout")) {
                col = 4;
            } else if (j.entry.bucket.equals("either")) {
                col = 5;
            } else {
                col = 2;
            }
            c[col]++;
            total[col]++;
            if (d != null) {
                differences.add(j);
            }
            Json r = Json.object();
            r.put("name", j.entry.name);
            r.put("kind", j.entry.kind);
            r.put("official", j.entry.official);
            r.put("status", j.status);
            r.put("exit_code", j.exitCode);
            r.put("wall_s", Leanchecker.round3(j.wallSeconds));
            r.put("difference", d);
            if (d != null || j.status.equals("error") || j.status.equals("timeout")) {
                r.put("description", j.entry.description);
                r.put("official_message", j.entry.officialMessage);
                r.put("output_tail", j.outputTail);
            }
            rows.add(r);
        }
        out.printf("%-20s %9s %12s %7s %8s %6s %10s%n", "kind", "SOUNDNESS", "COMPLETENESS", "agree", "declined", "error", "undecided");
        for (Map.Entry<String, int[]> k : perKind.entrySet()) {
            int[] c = k.getValue();
            out.printf("%-20s %9d %12d %7d %8d %6d %10d%n", k.getKey(), c[0], c[1], c[2], c[3], c[4], c[5]);
        }
        out.printf("%-20s %9d %12d %7d %8d %6d %10d%n", "total", total[0], total[1], total[2], total[3], total[4], total[5]);
        int shown = 0;
        for (Judgement j : differences) {
            if (shown++ == 40) {
                out.println("  ... " + (differences.size() - 40) + " more in the report");
                break;
            }
            out.printf("  %-12s %s%n               %s; official %s%s%n               reproduce: IN=%s sh -c '%s'%n", j.difference(),
                    j.entry.name, j.entry.description, j.entry.official,
                    j.entry.officialMessage.isEmpty() ? "" : ": " + truncate(j.entry.officialMessage, 200),
                    corpus.resolve("suite").resolve(j.entry.bucket).resolve(j.entry.name + ".ndjson"), command);
        }
        Json report = Json.object();
        report.put("generated_utc", Instant.now().toString());
        report.put("corpus", corpus.toString());
        report.put("command", command);
        report.put("mutants", results.size());
        report.put("soundness_differences", total[0]);
        report.put("completeness_differences", total[1]);
        report.put("agree", total[2]);
        report.put("declined", total[3]);
        report.put("error_or_timeout", total[4]);
        report.put("answered_on_undecided", total[5]);
        report.put("results", rows);
        String reportOpt = a.flag("--all") ? null : a.get("--report", null);
        Path reportFile = reportOpt != null ? Paths.get(reportOpt)
                : root.resolve("work/mutants-reports").resolve("judge-" + (a.flag("--all") ? totals.name + "-" : "")
                        + Instant.now().toString().replace(":", "") + ".json");
        OfficialVerdicts.writeAtomically(reportFile, Json.pretty(report));
        out.println("report: " + reportFile);
        if (total[0] > 0) {
            out.println("FAILED: " + total[0] + " mutant(s) accepted although the official checker rejects them");
        }
        totals.mutants = results.size();
        System.arraycopy(total, 0, totals.counts, 0, 6);
        totals.seconds = (System.nanoTime() - started) / 1e9;
        totals.report = reportFile.toAbsolutePath();
        return totals;
    }
}
