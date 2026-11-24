package rlvm.machine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import rlvm.api.MachineErrors;
import rlvm.api.Outcome;
import rlvm.store.DeclKind;
import rlvm.store.DeclTable;
import rlvm.store.IntLists;
import rlvm.store.Kind;

/**
 * Tier equivalence on values (ISA section 11 "tier equivalence", decision 6; staging step 4): for the
 * constants of an export, applied to generated arguments, the generic tier K0 (every staging switch
 * off) and a tier with switches on must return <em>the same value</em> for {@code DELTA}, {@code WHNF}
 * and {@code VTYPE} and the same answer for {@code CONV}. Owner: M8.
 *
 * <p>For a theorem only {@code VTYPE} is compared: a proof is never normalised by the kernel, and its
 * weak head normal form can be arbitrarily expensive. A limit (steps, memory, the deadline) in either
 * tier makes the constant "skipped", not different.
 *
 * <p><b>The application.</b> For a constant {@code c} with universe parameters {@code us} the value
 * {@code c.{us} a1 .. an} is built by walking the telescope of its type with the machine itself
 * ({@code VTYPE}, {@code WHNF}, {@code DOM}, {@code INST}); the argument for a binder of type {@code D} is
 * a fresh variable {@code OPEN(D)}, or, drawn by the seed when {@code WHNF(D)} is an inductive type
 * without indices, a constructor of it applied to the type's parameters and to fresh variables for its
 * fields, so that recursors, {@code casesOn} bodies and matchers have something to reduce on. Every such
 * application is well typed.
 *
 * <p><b>"The same value" across two sessions.</b> The two tiers run in two sessions of one machine
 * (a memo written by one tier must not answer the other), so record indices are not comparable. What
 * is compared is the {@link #fingerprint} of the result: a hash of the value's structure that includes
 * exactly what value identity includes (ISA 2.5 I-ID): kind, the components, and for a closure or
 * suspension its term id, its {@code ls} and the fingerprints of its frame slots (HOLE included, and the
 * REST run of a far frame). Two results with one fingerprint would have been one record in one session;
 * a tier that captures another frame, evaluates under another term id or builds another spine is caught
 * even where the read-back terms are equal; the read-back terms are printed in a difference. One
 * relaxation, counted separately and not a difference: results that are the same value <em>up to
 * PEEK</em> (one tier holds a suspension where the other holds its already known value, ISA 4.2; M2's
 * note of 08:56Z in requests.log: a known VAL is stable up to PEEK, not by id).
 */
final class M8Tiers {
    private M8Tiers() {}

    static final class Result {
        String path;
        int constants;
        /** Judgment results compared (DELTA, WHNF, VTYPE, CONV per constant). */
        int compared;
        /** Results that are the same value up to PEEK (a suspension in one tier, its value in the other): not differences. */
        int equalUpToPeek;
        /** Constants whose application could not be built or judged: the same failure in both tiers, or a limit in either. */
        int skipped;
        final Map<String, Integer> skippedBy = new java.util.TreeMap<>();
        final List<String> differences = new ArrayList<>();

        boolean ok() {
            return differences.isEmpty();
        }
    }

    /** What one tier computed for one constant. */
    private static final class Answers {
        int error; // 0, or the MachineErrors code that ended the run
        String at = "";
        long app;
        long delta;
        long whnf;
        long type;
        /** The same up to PEEK. */
        long deltaPeek;
        long whnfPeek;
        long typePeek;
        int conv;
        String appText = "";
        String deltaText = "";
        String whnfText = "";
        String typeText = "";
    }

    private static final class Failed extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int code;
        final String at;

        Failed(int code, String at) {
            super(MachineErrors.name(code) + " in " + at, null, false, false);
            this.code = code;
            this.at = at;
        }
    }

    private static int run(Machine m, int r, String at) {
        r = m.drive(r);
        if (r >= Op.SIG) {
            throw new Failed(m.err, at);
        }
        return r;
    }

    /** Structural hash of a value: equal for two values iff they would be one record in one session (class comment). */
    static long fingerprint(Machine m, int v, Map<Integer, Long> memo) {
        return fingerprint(m, v, memo, false);
    }

    /**
     * {@link #fingerprint(Machine, int, Map)}; with {@code peek}, a suspension whose value is known (and that is
     * not a let suspension) counts as its value everywhere inside the value (ISA 4.2 PEEK): the fingerprint of
     * "the same value up to PEEK". Use one memo per mode.
     */
    static long fingerprint(Machine m, int v, Map<Integer, Long> memo, boolean peek) {
        if (v < 0) {
            return mix(0x11, v);
        }
        if (v == Layout.HOLE || v == Layout.NONE) {
            return mix(0x12, v);
        }
        Long hit = memo.get(v);
        if (hit != null) {
            return hit;
        }
        int o = v << Layout.REC_SHIFT;
        int w0 = m.vh[o];
        if (peek && w0 >>> Layout.KIND_SHIFT == Layout.SUSP && (w0 & Layout.W0_LET) == 0 && m.vh[o + Layout.M_VAL] != 0) {
            long h = fingerprint(m, m.vh[o + Layout.M_VAL], memo, true);
            memo.put(v, h);
            return h;
        }
        int a = m.vh[o + Layout.A];
        int b = m.vh[o + Layout.B];
        int c = m.vh[o + Layout.C];
        int kind = w0 >>> Layout.KIND_SHIFT;
        long h = mix(0x13, kind);
        switch (kind) {
            case Layout.SORT:
                h = mix(h, a);
                break;
            case Layout.NBIG:
                // inline: the number; store: content hash and limb count (words b, c are functions of the number)
                h = (w0 & Layout.AUX_MASK) == Layout.NBIG_INLINE ? mix(mix(h, a), b) : mix(mix(mix(h, 0x77), b), c);
                break;
            case Layout.STR:
                h = mix(h, a);
                break;
            case Layout.LAM:
            case Layout.PI:
            case Layout.PII:
            case Layout.SUSP:
                h = mix(mix(mix(h, a), c), w0 & Layout.AUX_MASK);
                h = mix(h, (int) runPrint(m, b, memo, peek)) ^ runPrint(m, b, memo, peek) >>> 32;
                break;
            case Layout.FVAR:
                h = mix(h, b);
                h = mix(h, (int) fingerprint(m, a, memo, peek));
                break;
            case Layout.CONST:
                h = mix(mix(mix(h, a), b), c);
                break;
            case Layout.NEU:
                h = mix(h, (int) fingerprint(m, a, memo, peek));
                h = mix(h, (int) runPrint(m, b, memo, peek)) ^ runPrint(m, b, memo, peek) >>> 32;
                break;
            case Layout.PROJ:
                h = mix(mix(h, b), c);
                h = mix(h, (int) fingerprint(m, a, memo, peek));
                break;
            case Layout.NSUC:
                h = mix(h, b);
                h = mix(h, (int) fingerprint(m, a, memo, peek));
                break;
            default:
                h = mix(h, 0xBAD);
        }
        memo.put(v, h);
        return h;
    }

    private static long runPrint(Machine m, int run, Map<Integer, Long> memo, boolean peek) {
        if (run == Layout.RUN_EMPTY) {
            return 0x5151;
        }
        int hdr = m.rs[run];
        int n = Layout.runLen(hdr);
        long h = mix(0x21, n);
        for (int i = 0; i < n; i++) {
            long f = fingerprint(m, Runs.arg(m, run, i), memo, peek);
            h = mix(h, (int) f) ^ f >>> 32;
        }
        if ((hdr & Layout.RUN_FAR) != 0) {
            long r = runPrint(m, m.rs[run + Layout.RUN_REST_WORD], memo, peek);
            h = mix(h, (int) r) ^ r >>> 32;
        }
        return h;
    }

    private static long mix(long h, int x) {
        h ^= x & 0xFFFFFFFFL;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 31;
        return h;
    }

    /** SplitMix64, as the test kit's samplers: a function of (seed, constant). */
    private static final class Rng {
        private long s;

        Rng(long seed) {
            s = seed;
        }

        int below(int n) {
            s += 0x9E3779B97F4A7C15L;
            long z = s;
            z = (z ^ z >>> 30) * 0xBF58476D1CE4E5B9L;
            z = (z ^ z >>> 27) * 0x94D049BB133111EBL;
            z ^= z >>> 31;
            return (int) ((z >>> 1) % n);
        }
    }

    private static int mkApp(Machine m, int head, int[] args, int n, String at) {
        if (n == 0) {
            return head;
        }
        int xb = m.xp;
        if (!m.ensureScratch(n)) {
            throw new Failed(MachineErrors.E_MEM, at);
        }
        System.arraycopy(args, 0, m.X, xb, n);
        m.xp = xb + n;
        int r = Values.mkApp(m, head, xb, n);
        m.xp = xb;
        if (r >= Op.SIG) {
            throw new Failed(m.err, at);
        }
        return r;
    }

    /** An argument of type {@code dom}: a fresh variable, or a constructor of an index-free inductive type over fresh fields. */
    private static int argument(Machine m, int dom, Rng rng) {
        DeclTable decls = m.decls;
        if (rng.below(2) == 0) {
            int t = run(m, Reduce.whnf(m, dom, 0), "WHNF of a domain");
            int head = Values.kind(m, t) == Layout.NEU ? Values.a(m, t) : t;
            if (head > 0 && Values.kind(m, head) == Layout.CONST && Values.hclass(m, head) == Layout.HC_IND) {
                int ind = Values.a(m, head);
                int nctors = decls.inductCtorCount(ind);
                int nparams = decls.inductNumParams(ind);
                int have = Values.kind(m, t) == Layout.NEU ? Runs.len(m, Values.b(m, t)) : 0;
                if (nctors > 0 && decls.inductNumIndices(ind) == 0 && have == nparams) {
                    int ctor = decls.inductCtor(ind, rng.below(nctors));
                    int c = Values.mkConst(m, ctor, Values.b(m, head));
                    if (c >= Op.SIG) {
                        throw new Failed(m.err, "the constructor constant");
                    }
                    int nf = decls.ctorNumFields(ctor);
                    int[] args = new int[nparams + nf];
                    int p = run(m, Infer.vtype(m, c, 0), "VTYPE of a constructor");
                    for (int i = 0; i < nparams + nf; i++) {
                        int pi = run(m, Reduce.whnf(m, p, 0), "WHNF of a constructor type");
                        int k = Values.kind(m, pi);
                        if (k != Layout.PI && k != Layout.PII) {
                            throw new Failed(MachineErrors.E_FUN_EXPECTED, "the telescope of a constructor");
                        }
                        int a;
                        if (i < nparams) {
                            a = Runs.arg(m, Values.b(m, t), i);
                        } else {
                            a = Scope.open(m, run(m, Eval.dom(m, pi, 0), "DOM of a field"));
                            if (a >= Op.SIG) {
                                throw new Failed(m.err, "OPEN of a field");
                            }
                        }
                        args[i] = a;
                        p = run(m, Eval.inst(m, pi, a, 0), "INST of a constructor type");
                    }
                    return mkApp(m, c, args, args.length, "the constructor application");
                }
            }
        }
        int x = Scope.open(m, dom);
        if (x >= Op.SIG) {
            throw new Failed(m.err, "OPEN");
        }
        return x;
    }

    /** One tier on one constant, in a fresh session. */
    private static Answers tier(M8Harness.Lane lane, int cid, int switches, long seed) {
        Machine m = lane.m;
        DeclTable decls = m.decls;
        Answers ans = new Answers();
        lane.session.reset();
        m.ctx.terms.refresh();
        int saved = m.sw;
        m.sw = switches;
        m.beginDecl(Integer.MAX_VALUE, -1, 50_000_000L, 1L << 30);
        try {
            Rng rng = new Rng(seed * 0x9E3779B97F4A7C15L + cid);
            // c.{us}: the constant at its own universe parameters (the identity substitution, ISA 2.7)
            int lp = decls.levelParams(cid);
            int nlp = m.lists.length(lp);
            int[] ls = new int[nlp];
            for (int i = 0; i < nlp; i++) {
                ls[i] = m.levels.param(m.lists.get(lp, i));
            }
            int head = Values.mkConst(m, cid, nlp == 0 ? IntLists.EMPTY : m.lists.intern(ls, 0, nlp));
            if (head >= Op.SIG) {
                throw new Failed(m.err, "the constant");
            }
            // the syntactic arity of the type bounds the spine
            int arity = 0;
            for (int t = decls.type(cid); m.terms.kind(t) == Kind.PI && arity < 24; t = m.terms.b(t)) {
                arity++;
            }
            int[] args = new int[arity];
            int n = 0;
            int p = run(m, Infer.vtype(m, head, 0), "VTYPE of the constant");
            while (n < arity) {
                int pi = run(m, Reduce.whnf(m, p, 0), "WHNF of the type");
                int k = Values.kind(m, pi);
                if (k != Layout.PI && k != Layout.PII) {
                    break;
                }
                int a = argument(m, run(m, Eval.dom(m, pi, 0), "DOM"), rng);
                args[n++] = a;
                p = run(m, Eval.inst(m, pi, a, 0), "INST");
            }
            int v = mkApp(m, head, args, n, "the application");
            Quote q = Quote.of(m);
            ans.app = fingerprint(m, v, new HashMap<>(), true);
            ans.appText = Show.term(m, q.quote(v));
            int ty = run(m, Infer.vtype(m, v, 0), "VTYPE");
            // A proof is not normalised: the kernel never does (proof irrelevance comes first), and the weak head
            // normal form of a proof can be arbitrarily expensive. For a theorem only VTYPE is compared.
            boolean proof = decls.kind(cid) == DeclKind.THM;
            int d = proof ? Layout.NONE : run(m, Reduce.delta(m, v, 0), "DELTA");
            int w = proof ? v : run(m, Reduce.whnf(m, v, 0), "WHNF");
            ans.conv = proof ? Layout.T : run(m, Conv.conv(m, v, w, 0), "CONV");
            // fingerprints at the end: what is suspended and what is forced is then the same for every result
            Map<Integer, Long> memo = new HashMap<>();
            Map<Integer, Long> peek = new HashMap<>();
            ans.delta = d == Layout.NONE ? 0 : fingerprint(m, d, memo, false);
            ans.deltaPeek = d == Layout.NONE ? 0 : fingerprint(m, d, peek, true);
            ans.deltaText = d == Layout.NONE ? "NONE" : Show.term(m, q.quote(d));
            ans.whnf = fingerprint(m, w, memo, false);
            ans.whnfPeek = fingerprint(m, w, peek, true);
            ans.whnfText = Show.term(m, q.quote(w));
            ans.type = fingerprint(m, ty, memo, false);
            ans.typePeek = fingerprint(m, ty, peek, true);
            ans.typeText = Show.term(m, q.quote(ty));
        } catch (Failed e) {
            ans.error = e.code == 0 ? MachineErrors.E_INTERNAL : e.code;
            ans.at = e.at;
        } catch (Quote.QuoteFailure e) {
            ans.error = e.code == 0 ? MachineErrors.E_INTERNAL : e.code;
            ans.at = "readback: " + e.getMessage();
        } finally {
            m.err = 0;
            m.abandon();
            m.sw = saved;
        }
        return ans;
    }

    /**
     * K0 against the tier {@code switches} on every {@code every}-th definition, theorem, opaque and axiom
     * of an export the reference engine accepts.
     */
    static Result values(String exportPath, long seed, int switches, int every) throws IOException {
        Result res = new Result();
        res.path = exportPath;
        try (M8Harness h = M8Harness.load(exportPath)) {
            if (h.loadOutcome != Outcome.ACCEPT) {
                return res;
            }
            M8Harness.Config c = new M8Harness.Config("tiers");
            c.digest = false;
            M8Harness.Lane lane = h.lane(c);
            int step = every > 0 ? every : Math.max(1, h.units / 1500);
            int seen = 0;
            while (h.next()) {
                int u = h.unit;
                if (h.oracle != Outcome.ACCEPT || !h.engineUnit(u) || seen++ % step != 0) {
                    continue;
                }
                int cid = h.store.decls().unitFirstCid(u);
                String name = h.unitName(u);
                lane.deadline.arm(60_000);
                Answers k0;
                Answers k1;
                try {
                    k0 = tier(lane, cid, 0, seed);
                    k1 = tier(lane, cid, switches, seed);
                } finally {
                    lane.deadline.disarm();
                    lane.session.reset();
                }
                res.constants++;
                String where = exportPath.replaceAll("^work/arena-site/tests/", "") + " unit " + u + " '" + name + "' ["
                        + (h.store.decls().kind(cid) == DeclKind.DEFN ? "def" : M8Harness.kindName(h.store.decls().kind(cid))) + "]";
                // A limit (steps, memory, the deadline) in either tier is no answer, hence no difference: a tier may
                // well get further than the other on the same budget. Only a kernel error in one tier alone is one.
                boolean limit0 = k0.error != 0 && MachineErrors.outcome(k0.error) != Outcome.REJECT;
                boolean limit1 = k1.error != 0 && MachineErrors.outcome(k1.error) != Outcome.REJECT;
                if ((k0.error != 0 && k1.error == k0.error) || limit0 || limit1) {
                    res.skipped++;
                    Answers which = k0.error != 0 ? k0 : k1;
                    res.skippedBy.merge(MachineErrors.name(which.error) + " in " + which.at.replaceAll("^readback: .*", "readback"), 1,
                            Integer::sum);
                    continue;
                }
                if (k0.error != k1.error) {
                    res.differences.add(where + ": K0 " + (k0.error == 0 ? "completes" : "fails with " + MachineErrors.name(k0.error) + " in "
                            + k0.at) + ", the tier [" + Debug.switchNames(switches) + "] " + (k1.error == 0 ? "completes" : "fails with "
                                    + MachineErrors.name(k1.error) + " in " + k1.at) + " on " + k0.appText + k1.appText);
                    continue;
                }
                if (k0.app != k1.app) {
                    res.differences.add(where + ": the application itself differs (the telescope walk is tier-dependent): " + k0.appText
                            + "   against   " + k1.appText);
                    continue;
                }
                res.compared += 4;
                differ(res, where, "DELTA", k0.appText, k0.delta, k1.delta, k0.deltaPeek, k1.deltaPeek, k0.deltaText, k1.deltaText, switches);
                differ(res, where, "WHNF", k0.appText, k0.whnf, k1.whnf, k0.whnfPeek, k1.whnfPeek, k0.whnfText, k1.whnfText, switches);
                differ(res, where, "VTYPE", k0.appText, k0.type, k1.type, k0.typePeek, k1.typePeek, k0.typeText, k1.typeText, switches);
                if (k0.conv != k1.conv || k0.conv != Layout.T) {
                    res.differences.add(where + ": CONV(v, WHNF v) = " + k0.conv + " in K0 and " + k1.conv + " in the tier ["
                            + Debug.switchNames(switches) + "] (must be T = 1 in both) on " + k0.appText);
                }
            }
        }
        return res;
    }

    private static void differ(Result res, String where, String judgment, String app, long a, long b, long pa, long pb, String ta, String tb,
            int switches) {
        if (a == b) {
            return;
        }
        if (pa == pb) {
            // another record, the same value: one tier holds a suspension where the other holds its value (ISA 4.2 PEEK)
            res.equalUpToPeek++;
            return;
        }
        if (res.differences.size() < 300) {
            res.differences.add(where + ": " + judgment + " of " + app + " is another value in the tier [" + Debug.switchNames(switches) + "]: K0 "
                    + ta + "   against   " + tb + (ta.equals(tb) ? "   (the same term: the values differ in closure identity - term id, frame"
                            + " or ls - ISA 2.5 I-ID)" : ""));
        }
    }

    /**
     * Tier equivalence over a corpus: {@code work/status/machine-tiers-LABEL.txt}; the tier is
     * {@code -Dm8.k1=facts,tele,code,casetrees} (default: those four).
     */
    static boolean report(String label, String... roots) throws IOException {
        List<String> paths = Diff.exports(roots);
        String name = Diff.reportName(label);
        int switches = System.getProperty("m8.k1") == null ? TierEq.K1_SWITCHES : Debug.parseSwitches(System.getProperty("m8.k1"));
        long seed = Long.getLong("m8.seed", 1);
        int every = Integer.getInteger("m8.every", 0);
        int constants = 0;
        int compared = 0;
        int skipped = 0;
        int peeks = 0;
        Map<String, Integer> skippedBy = new java.util.TreeMap<>();
        List<String> lines = new ArrayList<>();
        int differences = 0;
        for (String p : paths) {
            Result r;
            try {
                r = values(p, seed, switches, every);
            } catch (RuntimeException e) {
                differences++;
                lines.add("CRASH of the tier test on " + p + ": " + e + (e.getStackTrace().length > 0 ? " < " + e.getStackTrace()[0] : ""));
                continue;
            }
            constants += r.constants;
            compared += r.compared;
            skipped += r.skipped;
            peeks += r.equalUpToPeek;
            r.skippedBy.forEach((k, v) -> skippedBy.merge(k, v, Integer::sum));
            differences += r.differences.size();
            for (String d : r.differences) {
                if (lines.size() < 300) {
                    lines.add(d);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# machine-tiers-").append(name).append(": K0 (generic tier) against the tier [").append(Debug.switchNames(switches))
                .append("]: DELTA, WHNF, VTYPE (the same value: structural fingerprint incl. closure identity) and CONV of c.{us} a1..an\n");
        sb.append("# generated ").append(Instant.now().truncatedTo(ChronoUnit.SECONDS)).append(" by: rlvm-machine/src/test/resources/m8/report.sh tiers ")
                .append(label).append("; ").append(Diff.lastPublication()).append('\n');
        sb.append("# seed ").append(seed).append("; arguments: fresh variables, or (by the seed) a constructor of an index-free inductive over fresh fields\n#\n");
        sb.append("# RESULT ").append(name).append(": ").append(differences == 0 ? (compared == 0 ? "NO DATA" : "EQUIVALENT") : differences + " DIFFERENCES")
                .append(" -- constants ").append(constants).append(", judgment results compared ").append(compared).append(" (")
                .append(peeks).append(" equal up to PEEK only), constants skipped ")
                .append(skipped).append(" (the same failure in both tiers, or a limit - steps, memory, deadline - in either: ").append(skippedBy)
                .append(")\n#\n");
        for (String l : lines) {
            sb.append(l).append('\n');
        }
        Path out = Fixture.root().resolve("work/status/machine-tiers-" + name + ".txt");
        Files.createDirectories(out.getParent());
        Files.write(out, sb.toString().getBytes(StandardCharsets.UTF_8));
        int cut = sb.indexOf("#\n", sb.indexOf("# RESULT"));
        System.out.println(sb.substring(0, cut < 0 ? sb.length() : cut) + "report: " + out);
        return differences == 0;
    }
}
