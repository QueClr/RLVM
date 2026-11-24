package rlvm.testkit;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Command line of the test kit ({@code rlvm-testkit/bin/testkit}).
 *
 * <pre>
 * testkit oracle  [--tests DIR] [--out DIR] [-j N] [--timeout S] [--max-bytes N] [--only GLOB] [--bucket good|bad|either]
 *                 official verdicts for a tests directory: OUT/verdicts.json and OUT/manifest.json
 *                 (entries of earlier runs for other tests are kept, so several directories accumulate)
 * testkit verdict FILE...            official verdict of single exports (cached)
 * testkit mutate  SOURCE KIND COUNT OUTDIR [--seed N]
 *                 mutants of one export (KIND = a Mutation name or "all")
 * testkit corpus  [--sources DIR] [--out DIR] [--per-kind N] [--seed N] [-j N] [--timeout S]
 *                 [--memory-limit BYTES] [--only GLOB] [--no-classify]
 *                 mutants of every export below DIR, classified by the official checker
 * testkit corpus  --all [-j N] [--corpus NAME]
 *                 rebuilds the default corpora (tutorial, bugs, prelude, mdata) in place
 * testkit judge   --corpus DIR [-j N] [--timeout S] [--report FILE] [--limit N] -- CHECKER COMMAND
 *                 runs a checker (sh -c, export path in $IN) over a classified corpus and reports
 *                 every mutant it accepts although the official checker rejects it
 * testkit judge   --all [-j N] [--timeout S] [--note TEXT] [--summary FILE | --no-summary] -- CHECKER COMMAND
 *                 the same over every default corpus, with one summary table that is appended to
 *                 work/status/mutants.md
 * testkit diff    [--engine NAME] [--cp PATH] [--threads N] [-j N] [--sample N] ... EXPORT|DIR...
 *                 differential run of two engines through the real launcher and driver, with the
 *                 KernelOps sampler on request (see {@link DiffRun})
 * </pre>
 * Exit status: 0 success; 1 a soundness difference (judge), a difference between two engines (diff),
 * a disagreement between the arena and the official checker (oracle) or a failed run; 2 usage;
 * 3 (diff) no difference but an incomplete run.
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        int rc;
        try {
            rc = run(args, System.out, System.err);
        } catch (UsageException e) {
            System.err.println("testkit: " + e.getMessage());
            System.err.println("testkit: commands: oracle, verdict, mutate, corpus, judge, diff (see rlvm-testkit/README.md)");
            rc = 2;
        } catch (IOException e) {
            System.err.println("testkit: " + e);
            rc = 1;
        }
        System.exit(rc);
    }

    static final class UsageException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
        }
    }

    /** Parsed options: {@code --key value} pairs, flags, and positional arguments. */
    static final class Args {
        final List<String> positional = new ArrayList<>();
        final java.util.Map<String, String> options = new java.util.HashMap<>();
        final List<String> rest = new ArrayList<>();

        Args(String[] args, int from, String... flags) {
            java.util.Set<String> flagSet = new java.util.HashSet<>(java.util.Arrays.asList(flags));
            for (int i = from; i < args.length; i++) {
                String a = args[i];
                if (a.equals("--")) {
                    for (int k = i + 1; k < args.length; k++) {
                        rest.add(args[k]);
                    }
                    break;
                }
                if (flagSet.contains(a)) {
                    options.put(a, "true");
                } else if (a.startsWith("-") && a.length() > 1 && !isNumber(a)) {
                    if (i + 1 >= args.length) {
                        throw new UsageException("option " + a + " needs a value");
                    }
                    options.put(a, args[++i]);
                } else {
                    positional.add(a);
                }
            }
        }

        private static boolean isNumber(String s) {
            try {
                Long.parseLong(s);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        String get(String key, String fallback) {
            String v = options.get(key);
            return v == null ? fallback : v;
        }

        int getInt(String key, int fallback) {
            String v = options.get(key);
            try {
                return v == null ? fallback : Integer.parseInt(v);
            } catch (NumberFormatException e) {
                throw new UsageException("option " + key + " needs a number, got " + v);
            }
        }

        long getLong(String key, long fallback) {
            String v = options.get(key);
            try {
                return v == null ? fallback : Long.parseLong(v);
            } catch (NumberFormatException e) {
                throw new UsageException("option " + key + " needs a number, got " + v);
            }
        }

        boolean flag(String key) {
            return options.containsKey(key);
        }
    }

    static int run(String[] args, PrintStream out, PrintStream err) throws IOException {
        if (args.length == 0) {
            throw new UsageException("no command");
        }
        switch (args[0]) {
            case "oracle":
                return oracle(new Args(args, 1), out, err);
            case "verdict":
                return verdict(new Args(args, 1), out);
            case "mutate":
                return mutate(new Args(args, 1), out);
            case "corpus":
                return MutantCorpus.buildCommand(new Args(args, 1, "--no-classify", "--all"), out, err);
            case "judge":
                return MutantCorpus.judgeCommand(new Args(args, 1, "--all", "--no-summary"), out, err);
            case "diff":
                return DiffRun.command(new Args(args, 1, "--expect-differences", "--fail-on-decline"), out, err);
            default:
                throw new UsageException("unknown command '" + args[0] + "'");
        }
    }

    static Path root() {
        Path root = Leanchecker.projectRoot();
        return root == null ? Paths.get("").toAbsolutePath() : root;
    }

    static boolean glob(String pattern, String name) {
        if (pattern == null) {
            return true;
        }
        return java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + pattern).matches(Paths.get(name));
    }

    private static int oracle(Args a, PrintStream out, PrintStream err) throws IOException {
        Path root = root();
        Path testsDir = Paths.get(a.get("--tests", root.resolve("work/arena-site/tests").toString()));
        Path outDir = Paths.get(a.get("--out", root.resolve("work/oracle").toString()));
        int jobs = a.getInt("-j", 4);
        Duration timeout = Duration.ofSeconds(a.getLong("--timeout", 900));
        long maxBytes = a.getLong("--max-bytes", 64L << 20);
        String only = a.get("--only", null);
        Path results = Paths.get(a.get("--arena-results", root.resolve("work/arena-site/results.json").toString()));

        Leanchecker checker = Leanchecker.locate();
        if (!checker.available()) {
            err.println("testkit: official checker not found: " + checker.binary() + " (set RLVM_LEAN_TOOLCHAIN)");
            return 1;
        }
        List<OfficialVerdicts.Test> tests = OfficialVerdicts.discover(testsDir, maxBytes);
        String bucket = a.get("--bucket", null);
        tests.removeIf(t -> !glob(only, t.name) || (bucket != null && !bucket.equals(t.bucket)));
        if (tests.isEmpty()) {
            err.println("testkit: no tests below " + testsDir);
            return 1;
        }
        OfficialVerdicts.annotateFromArena(tests, results);
        err.printf("testkit: %d tests from %s, %d at a time, timeout %d s, cache %s%n", tests.size(), testsDir, jobs,
                timeout.getSeconds(), checker.cacheDir());
        OfficialVerdicts.collect(checker, tests, jobs, timeout, err);

        Json verdicts = OfficialVerdicts.verdictsJson(checker, tests, root, timeout);
        Json manifest = OfficialVerdicts.manifestJson(tests, root);
        Path vFile = outDir.resolve("verdicts.json");
        Path mFile = outDir.resolve("manifest.json");
        OfficialVerdicts.mergeOld(verdicts, vFile);
        OfficialVerdicts.mergeOld(manifest, mFile);
        OfficialVerdicts.writeAtomically(vFile, Json.pretty(verdicts));
        OfficialVerdicts.writeAtomically(mFile, Json.pretty(manifest));

        Json s = OfficialVerdicts.summary(tests);
        out.println("official verdicts: " + Json.write(s));
        int flagged = 0;
        for (OfficialVerdicts.Test t : tests) {
            if (t.disagreement() || t.undecided()) {
                flagged++;
                out.printf("  %-12s %-48s arena %-7s official %s%s%n", t.disagreement() ? "DISAGREEMENT" : "UNDECIDED", t.name,
                        t.arenaOutcome, t.official.verdict.lower(), t.official.message.isEmpty() ? "" : ": " + t.official.message);
            }
        }
        out.println("written: " + vFile + " and " + mFile + (flagged == 0 ? " (no test flagged)" : " (" + flagged + " flagged)"));
        for (OfficialVerdicts.Test t : tests) {
            if (t.disagreement()) {
                return 1;
            }
        }
        return 0;
    }

    private static int verdict(Args a, PrintStream out) {
        if (a.positional.isEmpty()) {
            throw new UsageException("verdict needs at least one export file");
        }
        Leanchecker checker = Leanchecker.locate();
        Duration timeout = Duration.ofSeconds(a.getLong("--timeout", 900));
        int worst = 0;
        for (String f : a.positional) {
            Leanchecker.Result r = checker.check(Paths.get(f), timeout);
            out.printf("%-7s exit %d  %.2f s%s  %s%s%n", r.verdict.lower(), r.exitCode, r.wallSeconds, r.cached ? " (cached)" : "", f,
                    r.message.isEmpty() ? "" : "\n        " + r.message);
            if (!r.decided()) {
                worst = 1;
            }
        }
        return worst;
    }

    private static int mutate(Args a, PrintStream out) throws IOException {
        if (a.positional.size() != 4) {
            throw new UsageException("mutate needs SOURCE KIND COUNT OUTDIR");
        }
        Path source = Paths.get(a.positional.get(0));
        if (!Files.isRegularFile(source)) {
            throw new UsageException("no such file: " + source);
        }
        String kindName = a.positional.get(1);
        int count;
        try {
            count = Integer.parseInt(a.positional.get(2));
        } catch (NumberFormatException e) {
            throw new UsageException("COUNT must be a number");
        }
        Path outDir = Paths.get(a.positional.get(3));
        ExportMutator mutator = new ExportMutator(a.getLong("--seed", 1));
        List<ExportMutator.Mutation> kinds = new ArrayList<>();
        if (kindName.equalsIgnoreCase("all")) {
            kinds.addAll(java.util.Arrays.asList(ExportMutator.Mutation.values()));
        } else {
            try {
                kinds.add(ExportMutator.Mutation.valueOf(kindName.toUpperCase(java.util.Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new UsageException("unknown mutation kind '" + kindName + "'; kinds: "
                        + java.util.Arrays.toString(ExportMutator.Mutation.values()));
            }
        }
        int total = 0;
        for (ExportMutator.Mutation kind : kinds) {
            for (ExportMutator.Mutant m : mutator.mutants(source, kind, count, outDir)) {
                out.println(m.path + "\t" + m.kind + "\t" + m.description);
                total++;
            }
        }
        out.println(total + " mutant(s) written to " + outDir);
        return 0;
    }
}
