package rlvm.ref.corpus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import rlvm.api.Budget;
import rlvm.api.Engine;
import rlvm.api.EngineConfig;
import rlvm.api.EngineFactory;
import rlvm.api.Outcome;
import rlvm.api.Reason;
import rlvm.api.Session;
import rlvm.api.StatsSink;
import rlvm.api.WorkerContext;
import rlvm.export.ExportException;
import rlvm.ref.RefEngine;
import rlvm.store.DeclKind;
import rlvm.store.DeclTable;

/**
 * Kernel-level corpus run (test tool): every axiom, definition, theorem and opaque of an export is
 * checked by the reference engine over a store filled by {@link TrustingLoader}. Inductive records
 * are trusted, so a bad test whose fault lies in an inductive record is accepted here by design; this
 * tool measures the type checker, not the pipeline.
 *
 * <pre>
 * java -cp ... rlvm.ref.corpus.CorpusRunner [--keep-going] [--stack MB] [--quiet] [--levels official|complete] FILE_OR_DIR...
 * </pre>
 */
public final class CorpusRunner {

    /** Outcome of one file. */
    public static final class Result {
        public Path file;
        public int verdict = Outcome.DECLINE;
        public Reason reason;
        public String message;
        public int constants;
        public int checked;
        public int failures;
        public long loadMillis;
        public long checkMillis;
        /** CPU time of the checking thread (deep-stack re-runs happen on another thread and are not included). */
        public long checkCpuMillis;
        public long steps;
        public long deepRuns;
        public long maxDepth;
        public long maxUnitBytes;
        /** Imports answered from the session's table, imports a declaration had to copy, keys kept. */
        public long importHits;
        public long importCopies;
        public long importPromoted;

        public boolean expectedAccept() {
            return !file.toString().contains("/bad/");
        }

        public boolean correct() {
            return verdict == (expectedAccept() ? Outcome.ACCEPT : Outcome.REJECT);
        }
    }

    private CorpusRunner() {}

    public static Result run(Path file, boolean keepGoing, boolean verbose) {
        return run(file, keepGoing, verbose, RefEngine.FACTORY);
    }

    /** As {@link #run(Path, boolean, boolean)} with the engine of {@code factory} (e.g. {@code -X levels=complete}). */
    public static Result run(Path file, boolean keepGoing, boolean verbose, EngineFactory factory) {
        return run(file, keepGoing, verbose, factory, false);
    }

    /**
     * As above; with {@code strictLoad} a file with an {@code mdata} record or a {@code letE} record
     * with {@code nondep = true} is declined, as the loader of the strict mode declines the
     * declarations that contain them (this test loader, like the store, keeps neither).
     */
    public static Result run(Path file, boolean keepGoing, boolean verbose, EngineFactory factory, boolean strictLoad) {
        Result r = new Result();
        r.file = file;
        long t0 = System.nanoTime();
        TrustingLoader loader;
        try {
            loader = TrustingLoader.load(file);
        } catch (ExportException e) {
            r.verdict = e.kind() == ExportException.Kind.MALFORMED ? Outcome.REJECT : Outcome.DECLINE;
            r.message = "load: " + e.getMessage();
            return r;
        } catch (IOException | RuntimeException e) {
            r.verdict = Outcome.DECLINE;
            r.message = "load: " + e;
            return r;
        }
        if (strictLoad && (loader.mdataRecords != 0 || loader.nondepLets != 0)) {
            r.verdict = Outcome.DECLINE;
            r.reason = Reason.UNSUPPORTED_INPUT;
            r.message = "load: " + loader.mdataRecords + " mdata record(s), " + loader.nondepLets
                    + " letE record(s) with nondep = true: part of the official term identity, not kept by the store";
            return r;
        }
        long t1 = System.nanoTime();
        long cpu1 = java.lang.management.ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
        r.loadMillis = (t1 - t0) / 1_000_000;
        DeclTable decls = loader.store.decls();
        WorkerContext ctx = loader.newWorkerContext();
        Engine engine = factory.create(loader.store, EngineConfig.defaults().withStats(true));
        Session session = engine.newSession(ctx);
        Budget budget = Budget.cold(8L << 30);
        r.constants = decls.constantCount();
        r.verdict = Outcome.ACCEPT;
        for (int cid = 1; cid <= r.constants; cid++) {
            int kind = decls.kind(cid);
            if (kind != DeclKind.AXIOM && kind != DeclKind.DEFN && kind != DeclKind.THM && kind != DeclKind.OPAQUE) {
                continue;
            }
            r.checked++;
            long c0 = verbose ? System.nanoTime() : 0;
            int out = session.check(cid, budget);
            if (verbose) {
                long ms = (System.nanoTime() - c0) / 1_000_000;
                if (ms >= 200) {
                    System.out.println("    slow: " + loader.store.names().toString(decls.name(cid)) + " " + ms + " ms");
                }
            }
            if (out != Outcome.ACCEPT) {
                r.failures++;
                if (r.verdict == Outcome.ACCEPT) {
                    r.verdict = out;
                    r.reason = session.lastReason();
                    r.message = session.lastMessage();
                }
                if (verbose) {
                    System.out.println("    " + Outcome.name(out) + ": " + session.lastMessage());
                }
                session.reset();
                ctx.reset();
                if (!keepGoing) {
                    break;
                }
            }
        }
        r.checkMillis = (System.nanoTime() - t1) / 1_000_000;
        r.checkCpuMillis = (java.lang.management.ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime() - cpu1)
                / 1_000_000;
        if (verbose) {
            System.out.println("    literal pool: " + loader.store.lits().natCount() + " big naturals, "
                    + (loader.store.lits().bytes() >> 20) + " MB; worker context " + (ctx.footprintBytes() >> 20) + " MB");
        }
        r.steps = session.steps();
        session.close();
        engine.dumpStats(new StatsSink() {
            @Override
            public void put(String key, long value) {
                if (key.equals("reference.deepStackRuns")) {
                    r.deepRuns = value;
                } else if (key.equals("reference.maxDepth")) {
                    r.maxDepth = value;
                } else if (key.equals("reference.maxUnitBytes")) {
                    r.maxUnitBytes = value;
                } else if (key.equals("reference.importHits")) {
                    r.importHits = value;
                } else if (key.equals("reference.importCopies")) {
                    r.importCopies = value;
                } else if (key.equals("reference.importPromoted")) {
                    r.importPromoted = value;
                }
            }

            @Override
            public void put(String key, double value) {}

            @Override
            public void put(String key, String value) {}

            @Override
            public void table(String key, String[] columns, long[][] rows, String[] rowLabels) {}
        });
        engine.close();
        return r;
    }

    public static List<Path> expand(List<String> args) throws IOException {
        List<Path> files = new ArrayList<>();
        for (String a : args) {
            Path p = Paths.get(a);
            if (Files.isDirectory(p)) {
                try (Stream<Path> s = Files.walk(p)) {
                    s.filter(f -> f.toString().endsWith(".ndjson")).forEach(files::add);
                }
            } else {
                files.add(p);
            }
        }
        Collections.sort(files);
        return files;
    }

    public static void main(String[] args) throws Exception {
        boolean keepGoing = false;
        boolean quiet = false;
        long stackMb = 0;
        String levels = null;
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--keep-going":
                    keepGoing = true;
                    break;
                case "--quiet":
                    quiet = true;
                    break;
                case "--stack":
                    stackMb = Long.parseLong(args[++i]);
                    break;
                case "--levels":
                    levels = args[++i];
                    break;
                default:
                    paths.add(args[i]);
            }
        }
        List<Path> files = expand(paths);
        boolean kg = keepGoing;
        boolean verbose = !quiet;
        int[] counts = new int[4];
        EngineFactory factory = levels == null ? RefEngine.FACTORY
                : RefEngine.factory(rlvm.ref.RefOptions.parse(java.util.Map.of(rlvm.ref.RefOptions.KEY_LEVELS, levels)));
        Runnable body = () -> {
            for (Path f : files) {
                Result r = run(f, kg, verbose, factory);
                String tag = r.correct() ? "OK   " : r.verdict == Outcome.DECLINE ? "DECL " : "WRONG";
                counts[r.correct() ? 0 : r.verdict == Outcome.DECLINE ? 1 : 2]++;
                System.out.printf("%s %-60s %-7s load %5d ms check %7d ms (cpu %7d ms)  consts %6d checked %6d fail %d steps %d depth %d deep %d mem %d MB imports %d/%d/%d%s%n",
                        tag, shorten(f), Outcome.name(r.verdict), r.loadMillis, r.checkMillis, r.checkCpuMillis, r.constants,
                        r.checked,
                        r.failures, r.steps, r.maxDepth, r.deepRuns, r.maxUnitBytes >> 20, r.importHits, r.importCopies,
                        r.importPromoted,
                        r.message == null ? "" : "  | " + (r.message.length() > 300 ? r.message.substring(0, 300) : r.message));
            }
        };
        if (stackMb > 0) {
            Thread t = new Thread(null, body, "corpus", stackMb << 20);
            t.start();
            t.join();
        } else {
            body.run();
        }
        System.out.printf("files %d: correct %d, declined %d, WRONG %d%n", files.size(), counts[0], counts[1], counts[2]);
    }

    private static String shorten(Path f) {
        String s = f.toString();
        int i = s.indexOf("/tests/");
        return i >= 0 ? s.substring(i + 7) : s;
    }
}
