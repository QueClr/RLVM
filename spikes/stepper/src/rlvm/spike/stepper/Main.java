package rlvm.spike.stepper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/**
 * Launcher of the stepper-convention spike.
 *
 * <pre>
 *   parse  FILE.ndjson CACHE [MAXBYTES]   read an export (or a prefix of it) once, write the binary cache
 *   run    CACHE VARIANT                  the benchmark: every declaration type and every definition value
 *   duel   CACHE VARIANT[,VARIANT..] N    the variants take turns, N rounds of one pass each, in one process
 *   verify CACHE VARIANT[,VARIANT..]      R and each variant on every root in ONE arena: identical ids?
 *   deep   VARIANT N                      synthetic N-deep application / lambda nests
 *   try    CACHE VARIANT BUDGET           HT0 | HT64 | HTinf: CONV of every root under a TRY with this budget
 *   heavy  CACHE                          the heaviest roots under R (needs -Drlvm.stats=true)
 * </pre>
 * Variants: none, R, Ri, W0, W1, W1n, H&lt;D&gt; (H0, H4, H8, H32, H64, H256, Hinf), Hi64, HT&lt;D&gt; (HT0, HT64,
 * HTinf: H with ticks and TRY) - see {@link #engine}.
 * Properties: -Drlvm.passes=N (arena reset between passes), -Drlvm.roots=all|types|values,
 * -Drlvm.session=BYTES (drop all values and normal forms whenever their footprint passes the cap; 0 = never),
 * -Drlvm.maxnodes=N (skip roots whose normalisation visits more term nodes; selection is by a recorded list, see
 * {@link #roots}), -Drlvm.hcap=WORDS, -Drlvm.tbits=N, -Drlvm.stackmb=N / -Drlvm.stackkb=N (stack of the worker thread),
 * -Drlvm.stats=true (counters; JVM mode only).
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Throwable[] failure = new Throwable[1];
        boolean[] ok = new boolean[1];
        long stack = Long.getLong("rlvm.stackkb", Long.getLong("rlvm.stackmb", 1024L) << 10) << 10;
        Thread t = new Thread(null, () -> {
            try {
                ok[0] = run(args);
            } catch (Throwable e) {
                failure[0] = e;
            }
        }, "stepper-main", stack);
        t.start();
        t.join();
        if (failure[0] != null) {
            if (failure[0] instanceof StackOverflowError) {
                System.out.println("RESULT STACKOVERFLOW " + String.join(" ", args));
            } else {
                failure[0].printStackTrace();
            }
            System.exit(3);
        }
        if (!ok[0]) {
            System.err.println("usage: see Main.java");
            System.exit(2);
        }
    }

    static Engine engine(String v) {
        switch (v) {
            case "R":
                return new Rec();
            case "Ri":
                return new RecI();
            case "W0":
                return new W0();
            case "W1":
                return new W1();
            case "W1n":
                return new W1n();
            case "H0":
                return new Hyb0();
            case "H4":
                return new Hyb4();
            case "H8":
                return new Hyb8();
            case "H32":
                return new Hyb32();
            case "H64":
                return new Hyb64();
            case "H256":
                return new Hyb256();
            case "Hinf":
                return new HybInf();
            case "Hi64":
                return new HybI64();
            case "HT0":
                return new HybT0();
            case "HT64":
                return new HybT64();
            case "HTinf":
                return new HybTInf();
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------ set-up

    static int[] roots;

    static void setup(String cache) throws Exception {
        long t0 = System.nanoTime();
        Terms.load(Path.of(cache));
        Terms.derive();
        Terms.reserve(Integer.getInteger("rlvm.textra", 1 << 22), Integer.getInteger("rlvm.ttbits", 22));
        Vals.init(Integer.getInteger("rlvm.hcap", 1 << 24), Integer.getInteger("rlvm.tbits", 22));
        roots = roots(cache);
        System.out.printf(Locale.ROOT, "SETUP %s terms=%d constants=%d roots=%d setup_ms=%d%n", cache, Terms.base, Terms.nConst, roots.length, (System.nanoTime() - t0) / 1_000_000);
    }

    /**
     * The roots of the benchmark: the type of every constant, then the value of every definition, theorem and
     * opaque constant, in file order. Roots listed in CACHE.skip (one term id per line, written by "heavy") are
     * left out: their normal forms are too large to be walked as trees.
     */
    static int[] roots(String cache) throws Exception {
        String which = System.getProperty("rlvm.roots", "all");
        boolean[] skip = new boolean[Terms.base];
        Path sk = Path.of(cache + ".skip");
        if (Files.exists(sk)) {
            for (String line : Files.readAllLines(sk)) {
                if (!line.isBlank()) {
                    skip[Integer.parseInt(line.trim())] = true;
                }
            }
        }
        int[] r = new int[2 * Terms.nConst];
        int k = 0;
        for (int c = 0; c < Terms.nConst; c++) {
            if (!which.equals("values") && !skip[Terms.constType[c]]) {
                r[k++] = Terms.constType[c];
            }
            if (!which.equals("types") && Terms.constValue[c] >= 0 && !skip[Terms.constValue[c]]) {
                r[k++] = Terms.constValue[c];
            }
        }
        return Arrays.copyOf(r, k);
    }

    /** CPU time of the calling thread in ns (Linux schedstat), or -1. */
    static long threadCpuNs() {
        try {
            String s = Files.readString(Path.of("/proc/thread-self/schedstat"));
            return Long.parseLong(s.substring(0, s.indexOf(' ')));
        } catch (Exception e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ commands

    public static boolean run(String[] args) throws Exception {
        if (args.length < 2) {
            return false;
        }
        switch (args[0]) {
            case "parse": {
                long t0 = System.nanoTime();
                Terms.parseNdjson(Path.of(args[1]), args.length > 3 ? Long.parseLong(args[3]) : 0);
                Terms.save(Path.of(args[2]));
                System.out.printf(Locale.ROOT, "PARSE %s terms=%d constants=%d ms=%d%n", args[1], Terms.base, Terms.nConst, (System.nanoTime() - t0) / 1_000_000);
                return true;
            }
            case "run":
                setup(args[1]);
                return bench(args[1], args[2]);
            case "verify":
                setup(args[1]);
                return verify(args[2].split(","));
            case "heavy":
                setup(args[1]);
                return heavy(args[1]);
            case "deep":
                return Deep.run(args[1], Integer.parseInt(args[2]));
            case "try":
                setup(args[1]);
                return tryBench(args[2], Integer.parseInt(args[3]));
            case "duel":
                setup(args[1]);
                return duel(args[1], args[2].split(","), Integer.parseInt(args[3]));
            default:
                return false;
        }
    }

    static boolean bench(String cache, String variant) {
        int passes = Integer.getInteger("rlvm.passes", 1);
        String corpus = Path.of(cache).getFileName().toString().replace(".bin", "");
        if (variant.equals("none")) {
            System.out.printf("RESULT variant=none corpus=%s%n", corpus);
            return true;
        }
        Engine en = engine(variant);
        if (en == null) {
            return false;
        }
        long session = Long.getLong("rlvm.session", 0L);
        int resets = 0;
        long[] cpu = new long[passes], wall = new long[passes];
        long cs = 0;
        int fails = 0;
        int[] r = roots;
        for (int p = 0; p < passes; p++) {
            Vals.reset();
            Terms.reset();
            Vals.probes = Terms.probes = 0;
            Stat.reset();
            Inert.nests = Inert.nodes = 0;
            long c0 = threadCpuNs(), t0 = System.nanoTime();
            cs = 0;
            fails = 0;
            resets = 0;
            for (int i = 0; i < r.length; i++) {
                en.decl(r[i]);
                cs = (cs * 31 + Engine.nf) * 31 + Engine.w;
                if (!Engine.ok) {
                    fails++;
                }
                if (session > 0 && 4L * Vals.hp + 8L * Vals.count + 21L * (Terms.n - Terms.base) > session) {
                    Vals.reset(); // a session: values and normal forms are dropped when their footprint passes the cap
                    Terms.reset();
                    resets++;
                }
            }
            wall[p] = (System.nanoTime() - t0) / 1_000_000;
            cpu[p] = (threadCpuNs() - c0) / 1_000_000;
        }
        System.out.printf(Locale.ROOT, "RESULT variant=%s corpus=%s roots=%d passes=%d pass_cpu_ms=%s pass_wall_ms=%s checksum=%016x fails=%d session=%d resets=%d values=%d hp=%d vtable=%d nf_terms=%d ttable=%d maxW=%d%n",
                        variant, corpus, r.length, passes, Arrays.toString(cpu).replace(" ", ""), Arrays.toString(wall).replace(" ", ""), cs, fails, session, resets, Vals.count, Vals.hp, Vals.tmask + 1,
                        Terms.n - Terms.base, Terms.tableSize(), en.stackWords());
        if (Vals.STATS) {
            System.out.printf(Locale.ROOT, "STATS variant=%s corpus=%s evals=%d readbacks=%d convs=%d value_probes=%d term_probes=%d frames=%d fresh_frames=%d frame_words=%d inline=%d spills=%d inert_nests=%d inert_nodes=%d%n",
                            variant, corpus, Stat.evals, Stat.rbs, Stat.convs, Vals.probes, Terms.probes, Stat.frames, Stat.freshFrames, Stat.frameWords, Stat.inline, Stat.spills, Inert.nests, Inert.nodes);
        }
        return fails == 0;
    }

    /** One pass of {@code en} over all roots with session resets; returns the checksum, or -1 on a failed conversion. */
    static long pass(Engine en, long session) {
        int[] r = roots;
        Vals.reset();
        Terms.reset();
        long cs = 0;
        boolean bad = false;
        for (int i = 0; i < r.length; i++) {
            en.decl(r[i]);
            cs = (cs * 31 + Engine.nf) * 31 + Engine.w;
            bad |= !Engine.ok;
            if (session > 0 && 4L * Vals.hp + 8L * Vals.count + 21L * (Terms.n - Terms.base) > session) {
                Vals.reset();
                Terms.reset();
            }
        }
        return bad ? -1 : cs;
    }

    /**
     * The variants take turns in ONE process: every round runs one pass of each (the starting variant rotates).
     * The host is shared, so the same pass varies by 10-20% from minute to minute; passes that are seconds apart
     * see the same conditions, which makes the per-round ratio of two variants a much sharper estimate than the
     * ratio of two separate processes.
     */
    static boolean duel(String cache, String[] variants, int rounds) {
        String corpus = Path.of(cache).getFileName().toString().replace(".bin", "");
        long session = Long.getLong("rlvm.session", 0L);
        int n = variants.length;
        Engine[] es = new Engine[n];
        for (int i = 0; i < n; i++) {
            es[i] = engine(variants[i]);
            if (es[i] == null) {
                return false;
            }
        }
        long[][] cpu = new long[n][rounds];
        long[] cs = new long[n];
        boolean same = true;
        for (int round = 0; round < rounds; round++) {
            for (int k = 0; k < n; k++) {
                int i = (k + round) % n;
                long c0 = threadCpuNs();
                cs[i] = pass(es[i], session);
                cpu[i][round] = (threadCpuNs() - c0) / 1000;
                same &= cs[i] == cs[0] || k == 0 && round == 0;
            }
        }
        for (int i = 0; i < n; i++) {
            same &= cs[i] == cs[0] && cs[i] != -1;
            System.out.printf(Locale.ROOT, "DUEL variant=%s corpus=%s roots=%d rounds=%d checksum=%016x cpu_us=%s%n", variants[i], corpus, roots.length, rounds, cs[i],
                            Arrays.toString(cpu[i]).replace(" ", ""));
        }
        System.out.println(same ? "DUEL OK identical checksums" : "DUEL FAILED");
        return same;
    }

    /** Every root: CONV under a TRY with the given budget. Outcomes and tick counts must not depend on D. */
    static boolean tryBench(String variant, int budget) {
        Engine en = engine(variant);
        if (en == null) {
            return false;
        }
        int[] r = roots;
        long[] outcome = new long[3];
        long ticks = 0, cs = 0;
        for (int i = 0; i < r.length; i++) {
            int o = en.tryDecl(r[i], budget);
            outcome[o]++;
            ticks += Engine.tryTicks;
            cs = (cs * 31 + o) * 31 + Engine.tryTicks;
        }
        System.out.printf(Locale.ROOT, "TRY variant=%s budget=%d roots=%d T=%d F=%d U=%d ticks=%d checksum=%016x values=%d%n", variant, budget, r.length, outcome[1], outcome[0], outcome[2], ticks, cs,
                        Vals.count);
        return true;
    }

    static boolean verify(String[] variants) {
        Engine ref = new Rec();
        Engine[] es = new Engine[variants.length];
        for (int i = 0; i < es.length; i++) {
            es[i] = engine(variants[i]);
            if (es[i] == null) {
                return false;
            }
        }
        int[] r = roots;
        long[] bad = new long[es.length];
        int fails = 0;
        for (int i = 0; i < r.length; i++) {
            ref.decl(r[i]);
            int n0 = Engine.nf, w0 = Engine.w;
            boolean ok0 = Engine.ok;
            if (!ok0) {
                fails++;
            }
            for (int j = 0; j < es.length; j++) {
                es[j].decl(r[i]);
                if (Engine.nf != n0 || Engine.w != w0 || Engine.ok != ok0) {
                    if (bad[j]++ < 5) {
                        System.out.printf("MISMATCH variant=%s root=%d R=(%d,%d,%b) got=(%d,%d,%b)%n", variants[j], r[i], n0, w0, ok0, Engine.nf, Engine.w, Engine.ok);
                    }
                }
            }
        }
        boolean all = fails == 0;
        for (int j = 0; j < es.length; j++) {
            System.out.printf("VERIFY variant=%s roots=%d mismatches=%d%n", variants[j], r.length, bad[j]);
            all &= bad[j] == 0;
        }
        System.out.printf("VERIFY %s conv_failures_under_R=%d%n", all ? "OK" : "FAILED", fails);
        return all;
    }

    /** Per-root cost under R (term nodes evaluated + values read back + pairs compared); needs -Drlvm.stats=true. */
    static boolean heavy(String cache) throws Exception {
        if (!Vals.STATS) {
            System.err.println("heavy needs -Drlvm.stats=true");
            return false;
        }
        long maxNodes = Long.getLong("rlvm.maxnodes", 20_000_000L);
        Engine ref = new Rec();
        int[] r = roots;
        long[] cost = new long[r.length];
        long total = 0;
        StringBuilder skip = new StringBuilder();
        int skipped = 0;
        for (int i = 0; i < r.length; i++) {
            long before = Stat.evals + Stat.rbs + Stat.convs;
            Stat.limit = before + maxNodes;
            try {
                ref.decl(r[i]);
                cost[i] = Stat.evals + Stat.rbs + Stat.convs - before;
            } catch (Stat.Exceeded e) {
                cost[i] = -1;
                skip.append(r[i]).append('\n');
                skipped++;
            }
            if (cost[i] > 0) {
                total += cost[i];
            }
        }
        Stat.limit = Long.MAX_VALUE;
        long[] sorted = cost.clone();
        Arrays.sort(sorted);
        System.out.printf(Locale.ROOT, "HEAVY roots=%d skipped(>%d judgments)=%d total_judgments=%d median=%d p99=%d max=%d values=%d nf_terms=%d%n", r.length, maxNodes, skipped, total,
                        sorted[r.length / 2], sorted[(int) (r.length * 0.99)], sorted[r.length - 1], Vals.count, Terms.n - Terms.base);
        long acc = 0;
        for (int q = r.length - 1; q >= 0 && q >= r.length - 10; q--) {
            acc += sorted[q];
        }
        System.out.printf(Locale.ROOT, "HEAVY top10 share of the kept roots = %.1f%%%n", 100.0 * acc / Math.max(1, total));
        if (System.getProperty("rlvm.writeskip") != null) {
            Files.writeString(Path.of(cache + ".skip"), skip.toString());
            System.out.println("HEAVY wrote " + cache + ".skip");
        }
        return true;
    }
}
