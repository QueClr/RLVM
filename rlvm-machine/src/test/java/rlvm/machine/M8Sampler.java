package rlvm.machine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import rlvm.api.EngineConfig;
import rlvm.api.KernelOps;
import rlvm.api.Outcome;
import rlvm.ref.RefKernel;
import rlvm.testkit.KernelSampler;

/**
 * Judgment-level differential tests against the reference kernel (ISA section 11: "per judgment on
 * sampled sub-problems, comparing the reference kernel's whnf and infer on the term with the machine's
 * value by reading the value back"; WORK-PACKAGES M8). Owner: M8.
 *
 * <p>The comparison itself is {@code rlvm.testkit.KernelSampler} (ARCHITECTURE 9.2): for a constant both
 * engines accept it walks to random sub-terms of the type and the value, opens the binders on the way
 * in both engines, and lets the reference kernel judge the answers of the engine under test:
 * <pre>
 * infer     the machine's type, read back, is definitionally equal to the reference's          INFER
 * whnf      the machine's weak head normal form, read back, is definitionally equal to the
 *           term and has the head symbol of the reference's (not for proofs)                    WHNF_DEFEQ, WHNF_HEAD
 * whnfCore  the same for CORE                                                                   WHNF_DEFEQ
 * isDefEq   the same yes / no on: argument type against the domain, a term against its whnf,
 *           a type against its sort                                                             DEFEQ
 * isProp, ensurePi   the same answer                                                            ISPROP, ENSURE_PI
 * </pre>
 * This class supplies what the sampler needs from the machine: {@code MachineOps} (M6: the machine's
 * judgments as {@code KernelOps}; terms are abstracted over its locals, evaluated under the environment
 * of the open binders, and the resulting values are read back) with {@link Quote} as its quoter, and a
 * reference kernel on the same worker context, over the lockstep run of {@link M8Harness} (a constant
 * is sampled after the reference engine accepted its unit, so its sub-terms are well typed).
 *
 * <p>An exception out of a judgment that is still a stub ("... is not implemented yet") is counted under
 * {@link Result#notImplemented}, by stub, and is not a difference: until the judgments are published the
 * report says NO DATA, not AGREE.
 */
final class M8Sampler {
    private M8Sampler() {}

    /** What is sampled. */
    static final class Options {
        /** Every {@code every}-th engine unit (axiom, definition, theorem, opaque) is sampled; 0 = about 1500 per file. */
        int every = Integer.getInteger("m8.every", 0);
        /** Only this unit (the reproduction of a difference); -1 = by {@link #every}. */
        int onlyUnit = Integer.getInteger("m8.unit", -1);
        /** Wall-clock limit of the machine on one constant's sub-problems. */
        long constantMillis = 1000L * Long.getLong("m8.checkSeconds", 60);
        final KernelSampler.Options sampler = new KernelSampler.Options();
        /** For tests of this class: the engine under test, instead of the machine (e.g. a second reference kernel). */
        java.util.function.Function<M8Harness.Lane, KernelOps> underTest;

        Options() {
            sampler.seed = Long.getLong("m8.seed", 1);
            sampler.perConstant = Integer.getInteger("m8.perConstant", 4);
            sampler.whnfProofs = Boolean.getBoolean("m8.whnfProofs");
        }
    }

    /** The result on one export. */
    static final class Result {
        String path;
        final KernelSampler.Tally tally = new KernelSampler.Tally();
        /** Real differences: an answer of the machine the reference kernel does not accept, or a crash that is not a stub. */
        final List<KernelSampler.Difference> differences = new ArrayList<>();
        /** Sub-problem judgments that ended in a stub, by stub. */
        long notImplemented;
        final Map<String, Integer> notImplementedBy = new TreeMap<>();
        int sampledConstants;
        /** Exceptions (other than stubs) while the machine opened the binders of a sub-problem: differences too. */
        long openCrashes;
        final List<String> openCrashLines = new ArrayList<>();
        String loadMessage = "";

        boolean ok() {
            return differences.isEmpty() && openCrashes == 0;
        }

        /** True if the machine answered at least one judgment (else the comparison says nothing). */
        boolean hasData() {
            return tally.problems > 0 && tally.infer + tally.whnf + tally.defEq + more(tally) > notImplemented;
        }
    }

    /** Samples one export. */
    static Result sample(String exportPath, Options options) throws IOException {
        Result res = new Result();
        res.path = exportPath;
        try (M8Harness h = M8Harness.load(exportPath)) {
            if (h.loadOutcome != Outcome.ACCEPT) {
                res.loadMessage = h.loadMessage;
                return res;
            }
            M8Harness.Config c = new M8Harness.Config("sample");
            c.digest = false;
            c.switches = Debug.parseSwitches(System.getProperty("m8.switches", ""));
            c.hostDepth = Integer.getInteger("m8.hostDepth", Op.HOST_DEPTH);
            M8Harness.Lane lane = h.lane(c);
            KernelOps underTest = new Guard(options.underTest != null ? options.underTest.apply(lane) : new MachineOps(lane.m, Quote::quote), res);
            KernelOps oracle = new RefKernel(lane.ctx, EngineConfig.defaults());
            KernelSampler sampler = new KernelSampler(h.store, lane.ctx, underTest, oracle, options.sampler);
            int every = options.every > 0 ? options.every : Math.max(1, h.units / 1500);
            int seen = 0;
            while (h.next()) {
                int u = h.unit;
                if (h.oracle != Outcome.ACCEPT || !h.engineUnit(u)) {
                    continue;
                }
                if (options.onlyUnit >= 0 ? u != options.onlyUnit : seen++ % every != 0) {
                    continue;
                }
                int cid = h.store.decls().unitFirstCid(u);
                lane.deadline.arm(options.constantMillis);
                List<KernelSampler.Difference> found;
                try {
                    found = sampler.sample(cid);
                } finally {
                    lane.deadline.disarm();
                    // the sampler cut the overlay back: nothing the machine or the readback remembered may survive
                    lane.session.reset();
                }
                res.sampledConstants++;
                for (KernelSampler.Difference d : found) {
                    String stub = stubOf(d);
                    if (stub != null) {
                        res.notImplemented++;
                        res.notImplementedBy.merge(stub, 1, Integer::sum);
                    } else if (res.differences.size() < 500) {
                        res.differences.add(d);
                    }
                }
            }
            res.tally.add(sampler.tally());
            res.tally.differences -= res.notImplemented;
        }
        return res;
    }

    /**
     * The engine under test as the sampler sees it. The sampler turns an unexpected exception of a judgment
     * into a CRASH difference, but not one raised while the context of a sub-problem is opened ({@code begin},
     * {@code mkLocal}): there the exception is recorded here (a stub by name, anything else as a difference
     * line) and the sub-problem is skipped through the {@code ResourceException} the sampler understands.
     */
    private static final class Guard implements KernelOps {
        private final KernelOps ops;
        private final Result res;

        Guard(KernelOps ops, Result res) {
            this.ops = ops;
            this.res = res;
        }

        private rlvm.api.ResourceException opening(RuntimeException e) {
            String text = String.valueOf(e);
            int at = text.indexOf(" is not implemented yet");
            if (e instanceof UnsupportedOperationException && at >= 0) {
                res.notImplemented++;
                res.notImplementedBy.merge(text.substring(text.indexOf(": ") + 2, at), 1, Integer::sum);
            } else {
                res.openCrashes++;
                if (res.openCrashLines.size() < 20) {
                    StackTraceElement[] st = e.getStackTrace();
                    res.openCrashLines.add("CRASH while opening the context of a sub-problem: " + e + (st.length > 0 ? " < " + st[0] : "")
                            + (st.length > 1 ? " < " + st[1] : ""));
                }
            }
            return new rlvm.api.ResourceException(rlvm.api.Reason.INTERNAL_ERROR);
        }

        @Override
        public void begin(int rank, int levelParams) {
            try {
                ops.begin(rank, levelParams);
            } catch (rlvm.api.KernelException | rlvm.api.ResourceException e) {
                throw e;
            } catch (RuntimeException e) {
                throw opening(e);
            }
        }

        @Override
        public int mkLocal(int type) {
            try {
                return ops.mkLocal(type);
            } catch (rlvm.api.KernelException | rlvm.api.ResourceException e) {
                throw e;
            } catch (RuntimeException e) {
                throw opening(e);
            }
        }

        @Override
        public void exposeUnitMembers(int kindMask) {
            ops.exposeUnitMembers(kindMask);
        }

        @Override
        public int infer(int term) {
            return ops.infer(term);
        }

        @Override
        public int check(int term) {
            return ops.check(term);
        }

        @Override
        public int whnf(int term) {
            return ops.whnf(term);
        }

        @Override
        public int whnfCore(int term) {
            return ops.whnfCore(term);
        }

        @Override
        public boolean isDefEq(int a, int b) {
            return ops.isDefEq(a, b);
        }

        @Override
        public int ensureSort(int term) {
            return ops.ensureSort(term);
        }

        @Override
        public int ensurePi(int term) {
            return ops.ensurePi(term);
        }

        @Override
        public boolean isProp(int type) {
            return ops.isProp(type);
        }

        @Override
        public rlvm.store.Terms terms() {
            return ops.terms();
        }

        @Override
        public rlvm.store.TermOps termOps() {
            return ops.termOps();
        }

        @Override
        public rlvm.api.LevelOps levels() {
            return ops.levels();
        }

        @Override
        public boolean whnfIsSyntactic() {
            return ops.whnfIsSyntactic();
        }

        @Override
        public long steps() {
            return ops.steps();
        }
    }

    /**
     * The sampler's count of isProp / whnfCore / ensurePi comparisons. The field exists in the test kit's
     * source and not yet in the jar its owner published, so it is read reflectively: 0 if absent.
     */
    static long more(KernelSampler.Tally t) {
        try {
            return KernelSampler.Tally.class.getField("more").getLong(t);
        } catch (ReflectiveOperationException e) {
            return 0;
        }
    }

    /** The stub a CRASH difference ran into ("M3: Reduce.whnf"), or null if the difference is a real one. */
    static String stubOf(KernelSampler.Difference d) {
        if (!"CRASH".equals(d.kind)) {
            return null;
        }
        String t = d.underTest;
        int at = t.indexOf(" is not implemented yet");
        if (at < 0 || !t.contains("UnsupportedOperationException")) {
            return null;
        }
        int from = t.lastIndexOf(": ", at);
        int wp = from >= 3 ? t.lastIndexOf(": ", from - 1) : -1;
        return t.substring(wp >= 0 ? wp + 2 : Math.max(0, from + 2), at);
    }

    /**
     * Samples a corpus: {@code work/status/machine-sample-LABEL.txt}.
     *
     * @return true if no answer of the machine was refuted by the reference kernel
     */
    static boolean report(String label, String... roots) throws IOException {
        List<String> paths = Diff.exports(roots);
        String name = Diff.reportName(label);
        Options options = new Options();
        KernelSampler.Tally tally = new KernelSampler.Tally();
        List<String> lines = new ArrayList<>();
        Map<String, Integer> stubs = new TreeMap<>();
        Map<String, Integer> kinds = new TreeMap<>();
        long notImplemented = 0;
        int differences = 0;
        int constants = 0;
        long t0 = System.nanoTime();
        for (String p : paths) {
            Result r;
            try {
                r = sample(p, options);
            } catch (RuntimeException e) {
                differences++;
                lines.add("CRASH of the sampler on " + p + ": " + e + (e.getStackTrace().length > 0 ? " < " + e.getStackTrace()[0] : ""));
                continue;
            }
            tally.add(r.tally);
            notImplemented += r.notImplemented;
            constants += r.sampledConstants;
            r.notImplementedBy.forEach((k, v) -> stubs.merge(k, v, Integer::sum));
            for (KernelSampler.Difference d : r.differences) {
                differences++;
                kinds.merge(d.kind, 1, Integer::sum);
                if (lines.size() < 300) {
                    lines.add(d + "\n    reproduce: scripts/machine-sandbox.sh test <WP> -Dtest=M8SamplerTest#oneFile -Dm8.file=" + p
                            + " -Dm8.unit=" + d.unit + " -Dm8.seed=" + d.seed + " -Dm8.perConstant=" + options.sampler.perConstant);
                }
            }
            if (r.openCrashes > 0) {
                differences += r.openCrashes;
                kinds.merge("CRASH", (int) r.openCrashes, Integer::sum);
                for (String l : r.openCrashLines) {
                    if (lines.size() < 300) {
                        lines.add(l + "\n    reproduce: scripts/machine-sandbox.sh test <WP> -Dtest=M8SamplerTest#oneFile -Dm8.file=" + p);
                    }
                }
            }
            if (!r.ok()) {
                System.err.println("m8 sample " + name + ": " + (r.differences.size() + r.openCrashes) + " differences in " + p + " -- "
                        + (r.differences.isEmpty() ? r.openCrashLines.get(0) : r.differences.get(0).toString()));
            }
        }
        long answered = tally.infer + tally.whnf + tally.defEq + more(tally) - notImplemented;
        StringBuilder sb = new StringBuilder();
        sb.append("# machine-sample-").append(name).append(": whnf / infer / whnfCore / isDefEq / isProp / ensurePi of the machine on sampled sub-terms,\n");
        sb.append("# read back with Quote and judged by the reference kernel (rlvm.testkit.KernelSampler; MachineOps as the machine's KernelOps)\n");
        sb.append("# generated ").append(Instant.now().truncatedTo(ChronoUnit.SECONDS)).append(" by: rlvm-machine/src/test/resources/m8/report.sh sample ")
                .append(label).append("; ").append(Diff.lastPublication()).append('\n');
        sb.append("# seed ").append(options.sampler.seed).append(", ").append(options.sampler.perConstant).append(" sub-terms per constant, ")
                .append(constants).append(" constants sampled in ").append(paths.size()).append(" files\n#\n");
        sb.append("# RESULT ").append(name).append(": ");
        if (differences > 0) {
            sb.append(differences).append(" DIFFERENCES ").append(kinds);
        } else if (answered <= 0) {
            sb.append("NO DATA: every judgment the sampler asked of the machine ended in a stub");
        } else {
            sb.append("AGREE");
        }
        sb.append('\n');
        sb.append("# ").append(tally).append('\n');
        sb.append("# judgments that ended in a stub (not differences): ").append(notImplemented).append(' ').append(stubs).append('\n');
        sb.append(String.format(java.util.Locale.ROOT, "# wall %.1f s%n#%n", (System.nanoTime() - t0) / 1e9));
        for (String l : lines) {
            sb.append(l).append('\n');
        }
        Path out = Fixture.root().resolve("work/status/machine-sample-" + name + ".txt");
        Files.createDirectories(out.getParent());
        Files.write(out, sb.toString().getBytes(StandardCharsets.UTF_8));
        int cut = sb.indexOf("# wall");
        System.out.println(sb.substring(0, cut < 0 ? sb.length() : cut) + "report: " + out);
        return differences == 0;
    }
}
