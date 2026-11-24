package rlvm.testkit;

import java.util.ArrayList;
import java.util.List;

import rlvm.api.KernelException;
import rlvm.api.KernelOps;
import rlvm.api.ResourceException;
import rlvm.api.WorkerContext;
import rlvm.store.DeclKind;
import rlvm.store.DeclTable;
import rlvm.store.Kind;
import rlvm.store.Store;
import rlvm.store.TermOps;
import rlvm.store.Terms;

/**
 * The {@code KernelOps} sampler of ARCHITECTURE.md 9.2: compares the answers of two engines to
 * {@code infer}, {@code whnf} and {@code isDefEq} on sub-problems drawn from the type and the value of
 * a constant of a real export. It finds a wrong judgment long before the judgment changes the verdict
 * of a declaration (most wrong reductions still end in an accepted proof).
 *
 * <p>A sub-problem is a sub-term reached by a random walk from the root (seeded; a function of the
 * seed and the constant alone). The binders on the way are opened as the kernel opens them: one local
 * per {@code fun} / {@code Pi} ({@link KernelOps#mkLocal}, made by each engine for itself), the value
 * substituted for a {@code let}. So every sub-term is closed, well typed in its context (when the
 * declaration is), and each engine sees it over its own locals. Compared, with the oracle as judge:
 *
 * <pre>
 * infer     the two types are definitionally equal                                          INFER
 * isProp    the same yes / no on the type of the sub-term ("is this a proof?")              ISPROP
 * whnf,     the result of the engine under test is definitionally equal to the term         WHNF_DEFEQ
 * whnfCore  and has the head symbol of the oracle's (weak head) normal form: sort, Pi, fun,
 *           the same constant, the same local; a literal against a constructor head passes  WHNF_HEAD
 *           -- only for sub-terms that are not proofs: the kernel does not normalise proofs,
 *              and the weak head normal form of a proof can be arbitrarily expensive
 * ensurePi  of the type of the function of an application: a Pi whose domain is
 *           definitionally equal to the oracle's                                            ENSURE_PI
 * isDefEq   the same yes / no, exactly, on: the type of an argument against the domain of
 *           its function (the comparison every application check makes; yes), a term against
 *           its weak head normal form (yes), a type against the sort it lives in (no)       DEFEQ
 * a RuntimeException other than the kernel's two out of the engine under test               CRASH
 * </pre>
 * A {@code ResourceException} of either side leaves the sub-problem undecided (counted, never a
 * difference); an answer the oracle cannot give is counted under {@code oracleErrors}.
 *
 * <p>Both {@code KernelOps} must work on the same {@link WorkerContext} (one overlay, so term ids
 * mean the same to both); they are called strictly one after the other on the calling thread. The
 * sampler calls {@link KernelOps#begin} for every sub-problem and, when {@link #sample(int)} returns,
 * cuts the overlay back to where it was and resets the context's number binding ({@code NatOps.reset()}):
 * overlay ids and number handles either engine learned meanwhile are stale, so the caller resets the
 * sessions (or calls {@code begin}) before it uses them again.
 * {@code rlvm.testkit.DiffEngine} does exactly that when sampling is switched on.
 */
public final class KernelSampler {

    /** What to sample. Mutable builder-style fields, read when the sampler is created. */
    public static final class Options {
        /** Seed of the walks; the sub-problems of a constant are a function of (seed, constant id). */
        public long seed = 1;
        /** Sub-terms per sampled constant. */
        public int perConstant = 4;
        /** A walk stops before it would open more binders than this. */
        public int maxBinders = 48;
        /** Longest walk, in child steps. */
        public int maxDepth = 400;
        public boolean infer = true;
        public boolean whnf = true;
        public boolean defEq = true;
        /** Also compare {@code isProp}, {@code whnfCore} and {@code ensurePi}. */
        public boolean more = true;
        /** Also compute weak head normal forms of proofs (off: see the class comment). */
        public boolean whnfProofs = false;
        /** Nodes printed per term in a difference record. */
        public int printNodes = 40;
    }

    /** One difference between the two engines' answers. */
    public static final class Difference {
        /** INFER, ISPROP, WHNF_DEFEQ, WHNF_HEAD, ENSURE_PI, DEFEQ or CRASH. */
        public final String kind;
        public final int cid;
        public final int unit;
        public final String constant;
        /** "type" or "value". */
        public final String root;
        /** Child steps from the root to the sub-term ("" = the root): 0 / 1 of an application (function, argument), of a binder (domain, body), 0 / 1 / 2 of a let (type, value, body), 0 of a projection. */
        public final String path;
        /** The judgment and its operands, bounded. */
        public final String problem;
        public final String underTest;
        public final String oracle;
        public final long seed;

        Difference(String kind, int cid, int unit, String constant, String root, String path, String problem,
                String underTest, String oracle, long seed) {
            this.kind = kind;
            this.cid = cid;
            this.unit = unit;
            this.constant = constant;
            this.root = root;
            this.path = path;
            this.problem = problem;
            this.underTest = underTest;
            this.oracle = oracle;
            this.seed = seed;
        }

        @Override
        public String toString() {
            return "SAMPLE " + kind + " '" + constant + "' (cid " + cid + (unit >= 0 ? ", unit " + unit : "") + "), " + root
                    + (path.isEmpty() ? "" : " at " + path) + ": " + problem + " -- under test: " + underTest + "; oracle: "
                    + oracle + " [seed " + seed + "]";
        }

        Json toJson() {
            Json o = Json.object();
            o.put("kind", "SAMPLE_" + kind);
            o.put("constant", constant);
            o.put("cid", cid);
            o.put("unit", unit);
            o.put("root", root);
            o.put("path", path);
            o.put("problem", problem);
            o.put("under_test", underTest);
            o.put("oracle", oracle);
            o.put("seed", seed);
            return o;
        }
    }

    /** Counters of one sampler (one session's worth); add them up with {@link #add}. */
    public static final class Tally {
        public long constants;
        public long problems;
        public long infer;
        public long whnf;
        public long defEq;
        /** {@code isProp}, {@code whnfCore} and {@code ensurePi} answers compared. */
        public long more;
        public long differences;
        /** Judgments a side gave up on (ResourceException). */
        public long undecided;
        /** Judgments the oracle itself failed (kernel error on a sub-term, exception). */
        public long oracleErrors;
        /** Sub-problems whose context could not be opened. */
        public long skipped;
        /** Weak head normal forms where one side has a literal and the other a constructor head. */
        public long literalHeads;
        /** Sub-terms that are proofs (no whnf comparison). */
        public long proofs;

        public synchronized void add(Tally t) {
            constants += t.constants;
            problems += t.problems;
            infer += t.infer;
            whnf += t.whnf;
            defEq += t.defEq;
            more += t.more;
            differences += t.differences;
            undecided += t.undecided;
            oracleErrors += t.oracleErrors;
            skipped += t.skipped;
            literalHeads += t.literalHeads;
            proofs += t.proofs;
        }

        Json toJson() {
            Json o = Json.object();
            o.put("constants", constants);
            o.put("problems", problems);
            o.put("infer", infer);
            o.put("whnf", whnf);
            o.put("def_eq", defEq);
            o.put("more", more);
            o.put("differences", differences);
            o.put("undecided", undecided);
            o.put("oracle_errors", oracleErrors);
            o.put("skipped", skipped);
            o.put("literal_heads", literalHeads);
            o.put("proofs", proofs);
            return o;
        }

        @Override
        public String toString() {
            return constants + " constants, " + problems + " sub-terms (" + proofs + " proofs): " + infer + " infer, " + whnf
                    + " whnf, " + defEq + " isDefEq, " + more + " isProp / whnfCore / ensurePi compared; " + differences
                    + " difference(s), " + undecided + " undecided, "
                    + oracleErrors + " oracle errors, " + skipped + " skipped";
        }
    }

    private static final int OP_INFER = 0;
    private static final int OP_WHNF = 1;
    private static final int OP_DEFEQ = 2;
    private static final int OP_ENSURE_PI = 3;
    private static final int OP_IS_PROP = 4;
    private static final int OP_WHNF_CORE = 5;

    /** The answer of one judgment of one side. */
    private static final class Ans {
        int term;
        boolean bool;
        /** Kernel error (the reason), or the reason of a resource failure. */
        String error;
        boolean undecided;
        RuntimeException crash;

        boolean ok() {
            return error == null && crash == null;
        }

        String describe() {
            if (crash != null) {
                StackTraceElement[] st = crash.getStackTrace();
                return "exception " + crash + (st.length > 0 ? " at " + st[0] : "");
            }
            return (undecided ? "gave up: " : "kernel error ") + error;
        }
    }

    /** One engine's view of the current sub-problem. */
    private final class Side {
        final KernelOps ops;
        TermOps tops;
        /** One entry per binder on the path, outermost first: the local, or the value of a let. */
        int[] subst = new int[16];
        int n;
        /** The locals among them. */
        int[] locals = new int[16];
        int m;
        /** The sub-term over this side's locals. */
        int term;

        Side(KernelOps ops) {
            this.ops = ops;
        }

        /** Opens the binders along the path; false if the engine could not (the sub-problem is skipped). */
        boolean open(int rank, int lparams, int root, int[] path, int len) {
            try {
                ops.begin(rank, lparams);
                tops = ops.termOps();
                if (tops == null) {
                    tops = new TermOps(terms, ctx.lists);
                }
                if (subst.length < len + 1) {
                    subst = new int[len + 1];
                    locals = new int[len + 1];
                }
                n = 0;
                m = 0;
                int t = root;
                for (int i = 0; i < len; i++) {
                    int c = path[i];
                    switch (terms.kind(t)) {
                        case Kind.APP:
                            t = c == 0 ? terms.a(t) : terms.b(t);
                            break;
                        case Kind.LAM:
                        case Kind.PI:
                            if (c == 0) {
                                t = terms.a(t);
                            } else {
                                int domain = tops.instantiateRev(terms.a(t), subst, 0, n);
                                int body = terms.b(t);
                                int local = ops.mkLocal(domain);
                                subst[n++] = local;
                                locals[m++] = local;
                                t = body;
                            }
                            break;
                        case Kind.LET:
                            if (c == 0) {
                                t = terms.a(t);
                            } else if (c == 1) {
                                t = terms.b(t);
                            } else {
                                int value = tops.instantiateRev(terms.b(t), subst, 0, n);
                                int body = terms.c(t);
                                subst[n++] = value;
                                t = body;
                            }
                            break;
                        case Kind.PROJ:
                            t = terms.a(t);
                            break;
                        default:
                            return false;
                    }
                }
                term = tops.instantiateRev(t, subst, 0, n);
                return true;
            } catch (KernelException | ResourceException e) {
                return false;
            }
        }

        Ans call(int op, int x, int y) {
            Ans r = new Ans();
            try {
                switch (op) {
                    case OP_INFER:
                        r.term = ops.infer(x);
                        break;
                    case OP_WHNF:
                        r.term = ops.whnf(x);
                        break;
                    case OP_WHNF_CORE:
                        r.term = ops.whnfCore(x);
                        break;
                    case OP_DEFEQ:
                        r.bool = ops.isDefEq(x, y);
                        break;
                    case OP_ENSURE_PI:
                        r.term = ops.ensurePi(x);
                        break;
                    case OP_IS_PROP:
                        r.bool = ops.isProp(x);
                        break;
                    default:
                        throw new IllegalStateException("op " + op);
                }
            } catch (KernelException e) {
                r.error = e.reason().name();
            } catch (ResourceException e) {
                r.error = e.reason().name();
                r.undecided = true;
            } catch (RuntimeException e) {
                r.crash = e;
                r.error = e.toString();
            }
            return r;
        }
    }

    private final Store store;
    private final WorkerContext ctx;
    private final Terms terms;
    private final Options options;
    private final Side a;
    private final Side b;
    private final TermPrinter printer;
    private final Tally tally = new Tally();

    // the sub-problem being compared (for difference records)
    private int curCid;
    private String curRoot;
    private String curPath;
    private List<Difference> curOut;

    /**
     * @param ctx the context both {@code KernelOps} work on
     * @param underTest judgments of the engine under test
     * @param oracle judgments of the oracle (the reference kernel)
     */
    public KernelSampler(Store store, WorkerContext ctx, KernelOps underTest, KernelOps oracle, Options options) {
        if (underTest == null || oracle == null) {
            throw new IllegalArgumentException("the sampler needs the KernelOps of both engines");
        }
        this.store = store;
        this.ctx = ctx;
        this.terms = ctx.terms;
        this.options = options == null ? new Options() : options;
        this.a = new Side(underTest);
        this.b = new Side(oracle);
        this.printer = new TermPrinter(store, ctx.terms, ctx.levels);
    }

    public Tally tally() {
        return tally;
    }

    /** True for the constants an engine checks: axioms, definitions, theorems, opaques. */
    public static boolean samples(Store store, int cid) {
        int kind = store.decls().kind(cid);
        return kind == DeclKind.AXIOM || kind == DeclKind.DEFN || kind == DeclKind.THM || kind == DeclKind.OPAQUE;
    }

    /**
     * Compares the two engines on {@link Options#perConstant} sub-terms of the type and the value of
     * constant {@code cid}. The constant should be one both engines accept (its sub-terms are then
     * well typed).
     *
     * @return the differences found, in the order found (empty = the engines agree)
     */
    public List<Difference> sample(int cid) {
        List<Difference> out = new ArrayList<>();
        if (!samples(store, cid)) {
            return out;
        }
        DeclTable decls = store.decls();
        long mark = ctx.overlay.mark();
        try {
            ctx.terms.refresh();
            int type = decls.type(cid);
            int value = decls.value(cid);
            if (value < 0) {
                value = ctx.overlay.blockRoot(-value - 1); // a theorem's or an opaque's proof block
            }
            int rank = decls.rank(cid);
            int lparams = decls.levelParams(cid);
            Rng rng = new Rng(options.seed * 0x9E3779B97F4A7C15L + cid);
            int[] path = new int[Math.max(1, options.maxDepth)];
            tally.constants++;
            curCid = cid;
            curOut = out;
            for (int i = 0; i < options.perConstant; i++) {
                // mostly the value (the proof or the definition body); every fourth walk starts in the type
                boolean inValue = value > 0 && (i & 3) != 3;
                int root = inValue ? value : type;
                if (root <= 0) {
                    continue;
                }
                int len = walk(root, path, rng);
                curRoot = inValue ? "value" : "type";
                curPath = pathText(path, len);
                tally.problems++;
                if (!a.open(rank, lparams, root, path, len) || !b.open(rank, lparams, root, path, len) || a.m != b.m) {
                    tally.skipped++;
                    continue;
                }
                compare();
            }
        } finally {
            curOut = null;
            ctx.overlay.resetTo(mark);
            // Cutting the overlay back may release literal-pool entries (the store truncates the worker's
            // pool with the overlay), and the number binding caches by pool reference: drop that cache, as
            // every caller of resetTo must (requests.log 2026-11-24T08:18Z, rlvm-ref on NatBinding).
            if (ctx.nats != null) {
                ctx.nats.reset();
            }
            ctx.terms.refresh();
        }
        return out;
    }

    // ---- choosing a sub-term ---------------------------------------------------------------------------------

    /** A random walk from {@code root} over the raw term; returns the number of steps written to {@code path}. */
    private int walk(int root, int[] path, Rng rng) {
        int t = root;
        int len = 0;
        int binders = 0;
        while (len < path.length) {
            int k = terms.kind(t);
            int children;
            int stop;
            switch (k) {
                case Kind.APP:
                    children = 2;
                    stop = 12;
                    break;
                case Kind.LAM:
                case Kind.PI:
                    children = 2;
                    stop = 5;
                    break;
                case Kind.LET:
                    children = 3;
                    stop = 8;
                    break;
                case Kind.PROJ:
                    children = 1;
                    stop = 30;
                    break;
                default:
                    children = 0;
                    stop = 100;
            }
            if (children == 0) {
                // a variable, a sort or a literal says little: prefer the node above it
                if (k != Kind.CONST && len > 0 && rng.below(100) < 80) {
                    len--;
                }
                break;
            }
            if (rng.below(100) < stop) {
                break;
            }
            int c = rng.below(children);
            boolean opens = (k == Kind.LAM || k == Kind.PI) ? c == 1 : (k == Kind.LET && c == 2);
            if (opens && ++binders > options.maxBinders) {
                break;
            }
            path[len++] = c;
            t = k == Kind.LET ? (c == 0 ? terms.a(t) : c == 1 ? terms.b(t) : terms.c(t)) : (c == 0 ? terms.a(t) : terms.b(t));
        }
        return len;
    }

    private static String pathText(int[] path, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            if (i > 0) {
                sb.append('.');
            }
            sb.append(path[i]);
        }
        return sb.toString();
    }

    // ---- comparing -------------------------------------------------------------------------------------------

    /** A term of the engine under test, over the oracle's locals. */
    private int toOracle(int termOfA) {
        if (a.m == 0) {
            return termOfA;
        }
        return b.tops.instantiateRev(b.tops.abstractFVars(termOfA, a.locals, 0, a.m), b.locals, 0, b.m);
    }

    /** A term of the oracle, over the locals of the engine under test. */
    private int fromOracle(int termOfB) {
        if (b.m == 0) {
            return termOfB;
        }
        return b.tops.instantiateRev(b.tops.abstractFVars(termOfB, b.locals, 0, b.m), a.locals, 0, a.m);
    }

    private String show(int term) {
        return printer.show(term, options.printNodes);
    }

    private void difference(String kind, String problem, String underTest, String oracle) {
        tally.differences++;
        DeclTable decls = store.decls();
        String name;
        int unit;
        try {
            name = store.names().toString(decls.name(curCid));
            unit = decls.unit(curCid);
        } catch (RuntimeException e) {
            name = "cid " + curCid;
            unit = -1;
        }
        curOut.add(new Difference(kind, curCid, unit, name, curRoot, curPath, problem, underTest, oracle, options.seed));
    }

    /** False (and counted) if the oracle's answer cannot be used. */
    private boolean usable(Ans oracle) {
        if (oracle.undecided) {
            tally.undecided++;
            return false;
        }
        if (!oracle.ok()) {
            tally.oracleErrors++;
            return false;
        }
        return true;
    }

    private void compare() {
        String subject = show(b.term);
        Ans tb = b.call(OP_INFER, b.term, 0);
        if (!usable(tb)) {
            return;
        }
        boolean proof = true;
        Ans pb = b.call(OP_IS_PROP, tb.term, 0);
        if (pb.ok()) {
            proof = pb.bool;
        }
        if (proof) {
            tally.proofs++;
        }

        if (options.infer) {
            tally.infer++;
            Ans ta = a.call(OP_INFER, a.term, 0);
            if (ta.undecided) {
                tally.undecided++;
            } else if (ta.crash != null) {
                difference("CRASH", "infer " + subject, ta.describe(), show(tb.term));
            } else if (ta.error != null) {
                difference("INFER", "infer " + subject, ta.describe(), show(tb.term));
            } else {
                int moved = toOracle(ta.term);
                Ans eq = b.call(OP_DEFEQ, moved, tb.term);
                if (usable(eq) && !eq.bool) {
                    difference("INFER", "infer " + subject, show(moved), show(tb.term));
                }
            }
        }

        if (options.more && pb.ok()) {
            tally.more++;
            Ans pa = a.call(OP_IS_PROP, fromOracle(tb.term), 0);
            if (pa.undecided) {
                tally.undecided++;
            } else if (pa.crash != null) {
                difference("CRASH", "isProp " + show(tb.term), pa.describe(), String.valueOf(pb.bool));
            } else if (pa.error != null || pa.bool != pb.bool) {
                difference("ISPROP", "isProp " + show(tb.term), pa.error != null ? pa.describe() : String.valueOf(pa.bool),
                        String.valueOf(pb.bool));
            }
        }

        if (options.more && (!proof || options.whnfProofs)) {
            reduction(OP_WHNF_CORE, "whnfCore", subject);
        }
        Ans wb = null;
        if (options.whnf && (!proof || options.whnfProofs)) {
            tally.whnf++;
            wb = b.call(OP_WHNF, b.term, 0);
            if (!usable(wb)) {
                wb = null;
            } else {
                Ans wa = a.call(OP_WHNF, a.term, 0);
                if (wa.undecided) {
                    tally.undecided++;
                } else if (wa.crash != null) {
                    difference("CRASH", "whnf " + subject, wa.describe(), show(wb.term));
                } else if (wa.error != null) {
                    difference("WHNF_DEFEQ", "whnf " + subject, wa.describe(), show(wb.term));
                } else {
                    int moved = toOracle(wa.term);
                    Ans eq = b.call(OP_DEFEQ, moved, b.term);
                    if (usable(eq)) {
                        if (!eq.bool) {
                            difference("WHNF_DEFEQ", "whnf " + subject + " (the result is not definitionally equal to the term)",
                                    show(moved), show(wb.term));
                        } else if (!sameHead(moved, wb.term)) {
                            difference("WHNF_HEAD", "whnf " + subject + " (the result is not weak head normal as the oracle's)",
                                    printer.head(moved) + ": " + show(moved), printer.head(wb.term) + ": " + show(wb.term));
                        }
                    }
                }
            }
        }

        if (options.defEq) {
            // (1) what every application check asks: the type of the argument against the domain
            if (terms.kind(b.term) == Kind.APP) {
                int fn = terms.a(b.term);
                int arg = terms.b(b.term);
                Ans ft = b.call(OP_INFER, fn, 0);
                Ans pi = ft.ok() ? b.call(OP_ENSURE_PI, ft.term, 0) : ft;
                Ans at = b.call(OP_INFER, arg, 0);
                if (pi.ok() && at.ok() && terms.kind(pi.term) == Kind.PI) {
                    defEq("argument type against the domain", at.term, terms.a(pi.term));
                }
                if (options.more && pi.ok() && terms.kind(pi.term) == Kind.PI) {
                    tally.more++;
                    String problem = "ensurePi " + show(ft.term);
                    Ans pa = a.call(OP_ENSURE_PI, fromOracle(ft.term), 0);
                    if (pa.undecided) {
                        tally.undecided++;
                    } else if (pa.crash != null) {
                        difference("CRASH", problem, pa.describe(), show(pi.term));
                    } else if (pa.error != null) {
                        difference("ENSURE_PI", problem, pa.describe(), show(pi.term));
                    } else {
                        int moved = toOracle(pa.term);
                        if (terms.kind(moved) != Kind.PI) {
                            difference("ENSURE_PI", problem + " (the result is not a Pi)", show(moved), show(pi.term));
                        } else {
                            Ans eq = b.call(OP_DEFEQ, terms.a(moved), terms.a(pi.term));
                            if (usable(eq) && !eq.bool) {
                                difference("ENSURE_PI", problem + " (another domain)", show(moved), show(pi.term));
                            }
                        }
                    }
                }
            }
            // (2) a term against its weak head normal form
            if (wb != null && wb.term != b.term) {
                defEq("term against its whnf", b.term, wb.term);
            }
            // (3) a type against the sort it lives in: never equal
            Ans sort = b.call(OP_INFER, tb.term, 0);
            if (sort.ok()) {
                defEq("type against its sort", tb.term, sort.term);
            }
        }
    }

    /** Compares one reduction judgment (whnfCore) of the two sides on the current sub-term. */
    private void reduction(int op, String name, String subject) {
        tally.more++;
        Ans rb = b.call(op, b.term, 0);
        if (!usable(rb)) {
            return;
        }
        Ans ra = a.call(op, a.term, 0);
        if (ra.undecided) {
            tally.undecided++;
        } else if (ra.crash != null) {
            difference("CRASH", name + " " + subject, ra.describe(), show(rb.term));
        } else if (ra.error != null) {
            difference("WHNF_DEFEQ", name + " " + subject, ra.describe(), show(rb.term));
        } else {
            int moved = toOracle(ra.term);
            Ans eq = b.call(OP_DEFEQ, moved, b.term);
            if (usable(eq)) {
                if (!eq.bool) {
                    difference("WHNF_DEFEQ", name + " " + subject + " (the result is not definitionally equal to the term)",
                            show(moved), show(rb.term));
                } else if (!sameHead(moved, rb.term)) {
                    difference("WHNF_HEAD", name + " " + subject + " (another head symbol than the oracle's)",
                            printer.head(moved) + ": " + show(moved), printer.head(rb.term) + ": " + show(rb.term));
                }
            }
        }
    }

    private void defEq(String what, int xb, int yb) {
        tally.defEq++;
        Ans rb = b.call(OP_DEFEQ, xb, yb);
        if (!usable(rb)) {
            return;
        }
        String problem = "isDefEq, " + what + ": " + show(xb) + " =?= " + show(yb);
        Ans ra = a.call(OP_DEFEQ, fromOracle(xb), fromOracle(yb));
        if (ra.undecided) {
            tally.undecided++;
        } else if (ra.crash != null) {
            difference("CRASH", problem, ra.describe(), String.valueOf(rb.bool));
        } else if (ra.error != null) {
            difference("DEFEQ", problem, ra.describe(), String.valueOf(rb.bool));
        } else if (ra.bool != rb.bool) {
            difference("DEFEQ", problem, String.valueOf(ra.bool), String.valueOf(rb.bool));
        }
    }

    /** The head symbols of two weak head normal forms of one term agree. */
    private boolean sameHead(int x, int y) {
        int hx = terms.head(x);
        int hy = terms.head(y);
        int kx = terms.kind(hx);
        int ky = terms.kind(hy);
        boolean lx = kx == Kind.NATLIT || kx == Kind.STRLIT;
        boolean ly = ky == Kind.NATLIT || ky == Kind.STRLIT;
        if (lx != ly && (kx == Kind.CONST || ky == Kind.CONST)) {
            tally.literalHeads++; // 5 against Nat.succ 4, "a" against String.mk ...: both are values
            return true;
        }
        if (kx != ky) {
            return false;
        }
        if (kx == Kind.CONST) {
            return terms.a(hx) == terms.a(hy);
        }
        if (kx == Kind.FVAR) {
            return hx == hy;
        }
        return true;
    }

    /** SplitMix64. */
    static final class Rng {
        private long state;

        Rng(long seed) {
            this.state = seed;
        }

        long next() {
            long z = (state += 0x9E3779B97F4A7C15L);
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return z ^ (z >>> 31);
        }

        int below(int n) {
            return (int) ((next() >>> 1) % n);
        }
    }
}
