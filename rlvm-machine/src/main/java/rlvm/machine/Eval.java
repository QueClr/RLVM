package rlvm.machine;

import static rlvm.machine.Op.SIG;

import rlvm.api.MachineErrors;
import rlvm.store.Kind;
import rlvm.store.Node;
import rlvm.store.Terms;

/**
 * Family EV (ISA 4.2, 4.3.1): {@code EVAL}, {@code FORCE}, {@code APPLY}, {@code INST}, {@code ARGV},
 * {@code DOM}. Owner: M2.
 *
 * <p>Every judgment is a chain of step methods (stepper-convention.md H2-H4; the template is in
 * WORK-PACKAGES.md): the entry steps below are the cross-package interface and are frozen; continuation
 * steps {@code k_*}, loop methods and frame keys ({@code static final int K_EVAL_1 = Op.K_EVAL | 1;})
 * are this file's own.
 *
 * <h2>What the family computes</h2>
 * The official kernel substitutes ({@code instantiate}, R02 section 4) and then reduces; here a term is
 * evaluated under a working environment, which performs the substitution lazily and beta, zeta and
 * projection-of-constructor on the way (ISA decision 7: constants stay folded, no delta, no iota).
 * A value returned by a judgment of this family may be a suspension ({@code SUSP}): the delayed
 * evaluation of an active argument that was looked up through a variable or taken out of a constructor
 * application. Consumers that need the value call {@code FORCE}.
 *
 * <h2>Rules this file keeps</h2>
 * <ul>
 *   <li><b>Tabling</b> (ISA 5.1): {@code EVAL} of an application, a let or a projection is memoised in
 *       word {@code VAL} of the SUSP record of {@code (term, PRUNE(env, mask), ls')}. v0 tables every such
 *       node; with {@code SW_GATED_TABLING} only the nodes of ISA 3.1 and those deep in an inert nest
 *       ({@link #gate}); with {@code SW_M2A} never the body of a beta step. {@code VAL} is never a SUSP
 *       (2.6): a result that is still a suspension is returned and not stored ({@link #done}). A closed
 *       node under ls' = 0 is also in the closed-term memo, which the inline part reads.
 *   <li><b>B6</b>: a binder whose body does not read it (bit of the body's node mask clear; a clear bit
 *       is definite, 2.4) receives {@code HOLE}; its argument is not even built.
 *   <li><b>let</b> inside an evaluated term is zeta by sharing: the bound value is treated like the argument of a
 *       beta step (ARGV). It is <em>not</em> a {@code LET} suspension: those are the let variables of INFER (2.8), the
 *       only place where the official kernel has a free variable for a let (NOTES-M2 D9).
 *   <li><b>Inert nests</b> (ISA 8.2, section 9; convention H8 item 3): the evaluation of an inert
 *       application that {@code ARGV} performs at once is an iteration of the loop of the enclosing
 *       {@code EVAL}: it costs a tick and is not a call event. It runs by host recursion that becomes the
 *       frame {@code EVAL[IN]} beyond the depth bound ({@code SW_INERT_LOOP} selects nothing any more,
 *       NOTES-M2 D12).
 *   <li><b>Stacks</b> (H7): a judgment that entered a frame or pushed on {@code es} keeps the old
 *       {@code ep} in its payload and restores it when it completes; {@code EVAL} of an application keeps
 *       the scratch base {@code xb}.
 * </ul>
 */
final class Eval {
    private Eval() {}

    /**
     * Session state private to this package (caches, counters, scratch of its own): add fields here
     * instead of asking for fields in {@link Machine}. One instance per machine, field {@code m.x2}.
     */
    static final class Ext {
        /** Second result of {@link #peel}: the body under the peeled lambdas. */
        int body;
        /** Second result of {@link #bind}: the length of the environment it built (its base is {@code m.envBase}). */
        int en;
        /**
         * {@code SW_GATED_TABLING}: export term ids below this one belong to earlier units and are always
         * tabled (ISA 3.1). {@code Integer.MAX_VALUE} when the machine checks no unit (fixtures).
         */
        int ownFirst = Integer.MAX_VALUE;
        /** Statistics ({@code m.stats}): tabled evaluations answered by {@code VAL}; results left untabled because they were suspensions. */
        long hits;
        long lazy;
        /** Measurement bits ({@code X_SEG}, {@code X_NODEPTH}, {@code X_NOCM}, {@code X_NOINV}; system property {@code rlvm.m2.x}, default 0). */
        int xsw = Integer.getInteger("rlvm.m2.x", 0);
        /** SW_GATED_TABLING: an inert application nested this many inert levels below its evaluation root is always tabled. */
        int depthK = Math.max(1, Integer.getInteger("rlvm.m2.k", DEPTH_K));
        /**
         * Closed-term memo: cells {@code (term, epoch, value, -)}, direct-mapped by the term id. {@code value} is the
         * result of EVAL of the closed application, let or projection {@code term} under ls' = 0 (2.7), never a SUSP.
         */
        final int[] cm = new int[Layout.CELL_WORDS << CM_LOG2];
        /**
         * APPLY memo ({@code X_AMEMO}): cells {@code (closure, a0, a1, a2, epoch, j, value, -)}, direct-mapped by a hash
         * of the key {@code (closure, j, the j <= 3 bound arguments with HOLE for unread binders)}; {@code value} is
         * what EVAL of the body under those bindings returned. Lossy: a cell is overwritten, never chained.
         */
        final int[] am = new int[8 << AM_LOG2];
        /** Second result of {@link #beta}: the step missed the APPLY memo and its result is to be recorded. */
        boolean amOpen;
        /** Scratch of {@link #amKey}: the key's arguments. */
        int ak0;
        int ak1;
        int ak2;
        /** Statistics ({@code m.stats}): beta steps answered by the APPLY memo. */
        long amHits;
        /** Segment cache: pairs {@code (first id, end id)} of export segments of earlier units, direct-mapped by {@code id >>> SEG_SHIFT}. Never stale: a published unit's segment does not change. */
        final int[] seg = new int[2 << SEG_LOG2];

        /** Session reset (ISA 1.6): forget everything, in O(used) (the closed-term memo is stamped by the epoch). */
        void reset(Machine m) {
            ownFirst = Integer.MAX_VALUE;
            if (m.epoch == 1) {
                java.util.Arrays.fill(cm, 0); // the epoch counter wrapped: stale stamps could match again
                java.util.Arrays.fill(am, 0);
            }
        }

        /** Start of a declaration. */
        void beginDecl(Machine m) {
            ownFirst = m.unit >= 0 && m.decls != null ? m.decls.unitFirstTerm(m.unit) : Integer.MAX_VALUE;
        }

        /** Bytes in use of this package's own session tables (part of the footprint, ISA 1.6). */
        long bytes() {
            return 0;
        }
    }

    // ---- the evaluation economy (NOTES-M2 section 6) ----------------------------------------------------------
    // SW_M2A: X_NOBODY | X_SELECT. SW_M2B: X_AMEMO. The other bits are read from the system property rlvm.m2.x
    // (measurements; default 0) and, but for X_NOCM, act only with SW_GATED_TABLING.

    /** The body of a beta step and of INST is evaluated without a tabling record. */
    static final int X_NOBODY = 1;
    /** A body that is a projection of one of the bound arguments is answered from the arguments (no environment, no tick). */
    static final int X_SELECT = 2;
    /** A beta step of at most three binders is memoised by (closure, bound arguments) in a direct-mapped table. */
    static final int X_AMEMO = 4;
    /** An inert argument is tabled by its flags when it lies in its parent's export segment (instead of: always, when foreign). */
    static final int X_SEG = 8;
    /** Without the depth rule of inert nests (reproduces the quadratic walk of NOTES-M2 D21). */
    static final int X_NODEPTH = 16;
    /** Without the closed-term memo. */
    static final int X_NOCM = 32;
    /** INVARIANT alone does not table an inert argument. */
    static final int X_NOINV = 64;
    static final int DEPTH_K = 4;
    static final int CM_LOG2 = 12;
    static final int AM_LOG2 = 11;
    static final int AM_ARGS = 3;
    static final int SEG_LOG2 = 8;
    static final int SEG_SHIFT = 6;
    /** The debug modes that own a path this file would otherwise shorten (memo hits, variable reads); tracing is not one. */
    private static final int DBG_HOOKS = Op.DBG_MEMOCHECK | Op.DBG_SHORTCHECK | Op.DBG_SCOPEAUDIT | Op.DBG_MASKAUDIT | Op.DBG_SHARECHECK;
    /** {@code lo} of {@link #table}: the node is an evaluation root; the segment bound is the unit's own first id. */
    private static final int LO_ROOT = -1;

    /** The economy in force: {@code X_NOBODY | X_SELECT} with SW_M2A, {@code X_AMEMO} with SW_M2B. */
    private static int feat(Machine m) {
        final int sw = m.sw;
        return ((sw & Op.SW_M2A) != 0 ? X_NOBODY | X_SELECT : 0) | ((sw & Op.SW_M2B) != 0 ? X_AMEMO : 0);
    }

    // ---- frame keys of the continuation steps (the payload of J[k] is the parameter list of k_J..) ----

    /** EVAL[IN](t, lo, u, eb, en, ls): an inert application met by ARGV, beyond the depth bound. */
    static final int K_EVAL_IN = Op.K_EVAL | 1;
    /** EVAL[HEAD](s, xb, n, eb, en, ls): waits for the value of the head. */
    static final int K_EVAL_HEAD = Op.K_EVAL | 2;
    /** EVAL[HEADF](s, xb, n, eb, en, ls): waits for the forced head. */
    static final int K_EVAL_HEADF = Op.K_EVAL | 3;
    /** EVAL[ARG](s, f, xb, n, i, eb, en, ls): waits for argument i (an inert application). */
    static final int K_EVAL_ARG = Op.K_EVAL | 4;
    /** EVAL[APP](s, xb): waits for APPLY. */
    static final int K_EVAL_APP = Op.K_EVAL | 5;
    /** EVAL[BODY](s, ep0): waits for the body of a let chain. */
    static final int K_EVAL_BODY = Op.K_EVAL | 6;
    /** EVAL[PROJ](t, s): waits for the struct of a projection. */
    static final int K_EVAL_PROJ = Op.K_EVAL | 7;
    /** EVAL[LET](t, s, eb, en, ls, ep0): waits for the value bound by let node t (an inert application). */
    static final int K_EVAL_LET = Op.K_EVAL | 8;
    /** EVAL[NB](t, eb, en, ls): the entry step of a body evaluated without a tabling record (X_NOBODY), spilled. */
    static final int K_EVAL_NB = Op.K_EVAL | 9;
    /** APPLY[3](xb, n, k, ep0, g, j): as APPLY[2]; the step that ended applied closure g to j arguments (X_AMEMO). */
    static final int K_APPLY_3 = Op.K_APPLY | 3;
    /** FORCE[1](s, ep0): waits for EVAL of the suspended term. */
    static final int K_FORCE_1 = Op.K_FORCE | 1;
    /** FORCE[2](s): waits for FORCE of a result that was itself a suspension. */
    static final int K_FORCE_2 = Op.K_FORCE | 2;
    /** APPLY[1](xb, n, k): waits for FORCE of the function. */
    static final int K_APPLY_1 = Op.K_APPLY | 1;
    /** APPLY[2](xb, n, k, ep0): waits for EVAL of a body; k arguments are consumed. */
    static final int K_APPLY_2 = Op.K_APPLY | 2;
    /** INST[1](memo, ep0): waits for the codomain; memo = the PI / PII record whose COD is to be written, or 0. */
    static final int K_INST_1 = Op.K_INST | 1;
    /** INST[2](memo, xb): waits for the block of a PI closure (SW_CODE). */
    static final int K_INST_2 = Op.K_INST | 2;
    /** DOM[1](v, ep0): waits for EVAL of the domain term. */
    static final int K_DOM_1 = Op.K_DOM | 1;
    /** DOM[2](v): waits for FORCE of the domain. */
    static final int K_DOM_2 = Op.K_DOM | 2;

    // =====================================================================================================
    // EVAL
    // =====================================================================================================

    /**
     * EVAL(t, env, ls) (ISA 4.2): the value of term t under the working environment (eb, en) and substitution ls.
     * Fast: variable -> GET; const / sort / literal. Tabled on the SUSP record of (t, PRUNE(env, mask t), ls') (5.1; v0:
     * every non-leaf node). app: spine in X, constant head -> MKAPP of ARGV, else APPLY; lam / pi: closure; let: LET
     * suspensions; proj: MKPROJ. Official: {@code instantiate} + beta + zeta, delayed (R02 section 4, 7.1).
     * Entry frame: EVAL[0](t, eb, en, ls).
     * @return the result, or a signal ({@code >= Op.SIG}): SUSP, ABORT, FATAL
     */
    static int eval(Machine m, int t, int eb, int en, int ls, int d) {
        int v = atom(m, t, eb, en, ls); // 1. inline answers: leaves, closures, the closed-term memo (class p of ISA 4.2)
        if (v != Op.MISS) {
            return v;
        }
        // the tabling record (5.1), or -t: t is not tabled
        return enter(m, t, table(m, t, LO_ROOT, 0, eb, en, ls), Op.K_EVAL, eb, en, ls, d);
    }

    /**
     * EVAL after its inline answers: s is the tabling record of t (5.1), or -t if t is not tabled; key is the frame
     * key of the entry step (EVAL[0], or EVAL[NB] for a body without a record).
     */
    private static int enter(Machine m, int t, int s, int key, int eb, int en, int ls, int d) {
        if (s > 0) {
            if (s >= SIG) {
                return s;
            }
            int v = m.vh[(s << Layout.REC_SHIFT) + Layout.M_VAL];
            if (v != 0) {
                return m.debug != 0 || m.stats ? hit(m, s, v) : v; // memo hit: no tick, no event, no frame
            }
        }
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig; // 2. TICK
        }
        if (m.stats) {
            m.call(Op.EVAL); // 2b. call event
        }
        if (d > m.hostDepth) {
            return m.spill4(key, t, eb, en, ls); // 3. too deep: become the frame EVAL[0] / EVAL[NB](t, eb, en, ls)
        }
        if (s <= 0 && key == Op.K_EVAL && (m.debug & Op.DBG_SHARECHECK) != 0 && walked(m, t, eb, en) != 0) {
            return Op.FATAL;
        }
        return node(m, t, s, 0, eb, en, ls, d); // 4. body
    }

    /**
     * EVAL of the body of a beta step or of INST under {@code X_NOBODY} (SW_M2A): the judgment of {@link #eval} without a
     * tabling record. A body is entered once per APPLY / INST, never through an inert nest, so no DAG is walked as a
     * tree by this (NOTES-M2 D19); the memo it gives up is the one of a second application of the same closure to the
     * same arguments. Entry frame: EVAL[NB](t, eb, en, ls).
     */
    private static int evalNT(Machine m, int t, int eb, int en, int ls, int d) {
        int v = atom(m, t, eb, en, ls);
        return v != Op.MISS ? v : enter(m, t, -t, K_EVAL_NB, eb, en, ls, d);
    }

    /**
     * EVAL(t, env, ls) for a caller that already holds the tabling record s of (t, PRUNE(env), ls') and found its VAL
     * unknown (FORCE of a plain suspension; ISA 4.3 "call EVAL(term, frame, ls), VAL := A"): the judgment of
     * {@link #eval} without the probe. If it is spilled it becomes the frame EVAL[0], which finds s again.
     */
    private static int evalAt(Machine m, int t, int s, int eb, int en, int ls, int d) {
        int v = atom(m, t, eb, en, ls);
        if (v != Op.MISS) {
            return v;
        }
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig;
        }
        if (m.stats) {
            m.call(Op.EVAL);
        }
        if (d > m.hostDepth) {
            return m.spill4(Op.K_EVAL, t, eb, en, ls);
        }
        return node(m, t, s, 0, eb, en, ls, d);
    }

    /**
     * ARGV's evaluation of an inert application t (ISA 4.2 "EVAL now (builds NEUs only)"): an iteration of the
     * inert-nest loop of the enclosing EVAL (ISA 8.2 "one EVAL frame looping", section 9; convention H8 item 3), so it
     * is tabled and ticked like any node and is not a call event. Run by host recursion; beyond the depth bound it is
     * the frame EVAL[IN](t, lo, u, eb, en, ls) (written without the event bookkeeping of {@code spillN}: there was no
     * event). This entry is the root of a nest (ARGV called from outside, a let value).
     */
    static int evalIn(Machine m, int t, int eb, int en, int ls, int d) {
        return evalInAt(m, t, LO_ROOT, 0, eb, en, ls, d);
    }

    /**
     * {@link #evalIn} for the inert application t at nesting depth u below the root of its nest; lo is the first id
     * of the export segment of its parent ({@code LO_ROOT}: no parent is known), which the tabling gate reads.
     */
    private static int evalInAt(Machine m, int t, int lo, int u, int eb, int en, int ls, int d) {
        int s = table(m, t, lo, u, eb, en, ls);
        if (s > 0) {
            if (s >= SIG) {
                return s;
            }
            int v = m.vh[(s << Layout.REC_SHIFT) + Layout.M_VAL];
            if (v != 0) {
                return m.debug != 0 || m.stats ? hit(m, s, v) : v;
            }
        }
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig; // TICK: one per node of the nest (a loop iteration, ISA 1.4)
        }
        if (d > m.hostDepth) {
            m.fuel++; // the tick is given back: the driver enters this step again
            return m.sig6(Op.SUSP, K_EVAL_IN, t, lo, u, eb, en, ls);
        }
        if (s <= 0 && (m.debug & Op.DBG_SHARECHECK) != 0 && walked(m, t, eb, en) != 0) {
            return Op.FATAL;
        }
        return node(m, t, s, u, eb, en, ls, d);
    }

    /**
     * The inline part of EVAL and ARGV (ISA 4.2, class p): variable -> PEEK(GET) (reading HOLE is fault(E_HOLE), 2.1);
     * sort -> KSORT; const -> KCONST (an unbound constant node is fault(E_INTERNAL), section 9); literal -> KLIT;
     * lam / pi -> MKLAM / MKPI over the pruned frame. Official: {@code instantiate} of a leaf, {@code instantiate_lparams}
     * on Sort and Const (R02 section 4, 3.3); a closure is the delayed {@code instantiate} of a binder.
     * @return the value; {@code Op.MISS} for an application, a let or a projection; or a signal (FATAL)
     */
    private static int atom(Machine m, int t, int eb, int en, int ls) {
        final int[] r = m.terms.regionOf(t);
        final int w = Node.wordOf(t);
        final int hdr = r[w];
        switch (hdr & 0xF) {
            case Kind.BVAR: {
                int v = Runs.get(m, eb, en, r[w + Node.W_A]); // faults on HOLE; audits the scope in debug builds
                if (v <= Layout.HOLE || v >= SIG) {
                    return v != Layout.NONE ? v : m.fault(MachineErrors.E_INTERNAL); // an immediate, or a signal
                }
                return (m.vh[v << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT) == Layout.SUSP ? Values.peek(m, v) : v;
            }
            case Kind.SORT: {
                int l = r[w + Node.W_A];
                return Values.mkSort(m, (hdr & Node.F_HAS_LPARAM) != 0 ? Levels.lsub(m, l, ls) : l);
            }
            case Kind.CONST: {
                int row = r[w + Node.W_A];
                if (row <= 0) {
                    return m.fault(MachineErrors.E_INTERNAL);
                }
                int ll = r[w + Node.W_B];
                return Values.mkConst(m, row, (hdr & Node.F_HAS_LPARAM) != 0 ? Levels.lsubList(m, ll, ls) : ll);
            }
            case Kind.NATLIT:
            case Kind.STRLIT:
                return Lits.klit(m, t);
            case Kind.LAM:
            case Kind.PI:
                return closure(m, t, hdr & 0xF, eb, en, ls);
            case Kind.APP:
            case Kind.LET:
            case Kind.PROJ:
                return (r[w + Node.W_MASK_LO] | r[w + Node.W_MASK_HI]) != 0 ? Op.MISS : closedGet(m, t, hdr, ls);
            default:
                return m.fault(MachineErrors.E_INTERNAL); // FVAR nodes are the reference kernel's; NONE is no term
        }
    }

    /** MKLAM / MKPI over the pruned frame (ISA 4.2). */
    private static int closure(Machine m, int t, int kind, int eb, int en, int ls) {
        final int fr = frame(m, t, eb, en);
        return kind == Kind.LAM ? Values.mkLam(m, t, fr, ls) : Values.mkPi(m, t, fr, ls);
    }

    /**
     * The closed-term memo (NOTES-M2 D20): the value of EVAL of the closed application, let or projection t under
     * ls' = 0 (t has no level parameter, or ls is the identity), if this session has computed it; else {@code Op.MISS}.
     * The entry is a copy of {@code VAL} of the tabling record {@code SUSP(t, EMPTY, 0)} where t is tabled (so with it
     * and without it every result is the same; it saves the probe), and the memo itself where t is not tabled.
     * Bypassed in debug builds, where {@code memocheck} owns the hit path.
     */
    private static int closedGet(Machine m, int t, int hdr, int ls) {
        if (ls != 0 && (hdr & Node.F_HAS_LPARAM) != 0) {
            return Op.MISS;
        }
        final Ext x = m.x2;
        final int[] cm = x.cm;
        final int i = (t & ((1 << CM_LOG2) - 1)) << Layout.CELL_SHIFT;
        if (cm[i] != t || cm[i + 1] != m.epoch || (m.debug & DBG_HOOKS) != 0 || (x.xsw & X_NOCM) != 0) {
            return Op.MISS;
        }
        if (m.stats) {
            x.hits++;
            m.hits[Op.EVAL]++;
        }
        return cm[i + 2];
    }

    /** {@code EVAL(t) = r} for the closed term t under ls' = 0, r not a SUSP: into the closed-term memo. */
    private static void closedPut(Machine m, int t, int r) {
        final int[] cm = m.x2.cm;
        final int i = (t & ((1 << CM_LOG2) - 1)) << Layout.CELL_SHIFT;
        cm[i] = t;
        cm[i + 1] = m.epoch;
        cm[i + 2] = r;
    }

    /**
     * {@code PRUNE(env, mask t)} (ISA 2.4): the frame of a closure, a suspension or a tabling record of term t. A closed
     * term has the empty frame. The constructors of {@code Values} pass a signal on and canonicalise ls (ls' = 0 for a
     * term without level parameter, 2.7).
     * @return the frame run, or a signal
     */
    private static int frame(Machine m, int t, int eb, int en) {
        if ((m.debug & Op.DBG_MASKAUDIT) != 0 && Debug.maskAudit(m, t) != 0) {
            return Op.FATAL; // the mask contract (ISA 2.4, 5.5)
        }
        long mask = m.terms.fvMask(t);
        return mask == 0 ? Layout.RUN_EMPTY : Runs.prune(m, eb, en, mask);
    }

    /**
     * The tabling record of EVAL(t, env, ls): SUSP(t, PRUNE(env, mask t), ls') with ls' = HAS_LPARAM(t) ? ls : 0 (ISA
     * 4.2, 5.1, 2.7). The key holds every binding t can read (5.2: the mask is a superset of what is read).
     * @param lo the first id of the export segment of the node's parent, {@code LO_ROOT} if t is an evaluation root
     * @param u the number of inert applications between t and the root of its inert nest (0: t is not in a nest)
     * @return the record; -t if t is not tabled (ISA 3.1, only with SW_GATED_TABLING); or a signal
     */
    private static int table(Machine m, int t, int lo, int u, int eb, int en, int ls) {
        if ((m.sw & Op.SW_GATED_TABLING) != 0 && !gate(m, t, lo, u)) {
            return -t;
        }
        return Values.suspRecord(m, t, frame(m, t, eb, en), ls);
    }

    /**
     * {@code tabled(t)} of ISA 3.1 as this package applies it under SW_GATED_TABLING (NOTES-M2 D14, D19): t is tabled
     * iff it is not a flat inert application and (a) one of its sharing flags is set (SHARED, BODY, INVARIANT; they
     * never under-approximate the referrers inside the node's own export segment), or (b) it is reached from outside
     * its segment (below {@code lo}: the referrers of other segments are not in its flags; a generated node always
     * counts as such), or (c) it lies {@code depthK} or more inert levels below the root of its nest (bounds the
     * walk that every outer evaluation root repeats over an untabled nest by {@code depthK} levels, NOTES-M2 D21).
     */
    private static boolean gate(Machine m, int t, int lo, int u) {
        final Ext x = m.x2;
        final int fl = (x.xsw & X_NOINV) != 0 ? Node.F_SHARED | Node.F_BODY : Node.F_TABLED_MASK;
        if ((m.terms.header(t) & fl) == 0) {
            if (lo < 0) {
                lo = x.ownFirst;
            }
            if (t >= lo && (t < Node.GEN_BASE || t >= Node.OVERLAY_BIT) && (u < x.depthK || (x.xsw & X_NODEPTH) != 0)) {
                return false;
            }
        }
        return !flat(m, t);
    }

    /**
     * The first id of the export segment that holds node t, for the gate of its children (X_SEG): a child at or
     * above it lies in the same segment, so its flags count every referrer but those of later units, which reach it
     * through this parent only if they reached the parent. The unit's own nodes and every overlay node (a decoded
     * block of the unit, ISA 3.1): the unit's first id. A generated node: no child counts as its own
     * ({@code Integer.MAX_VALUE}).
     */
    static int segLo(Machine m, int t) {
        final Ext x = m.x2;
        if (t >= Node.GEN_BASE) {
            return t >= Node.OVERLAY_BIT ? x.ownFirst : Integer.MAX_VALUE;
        }
        if (t >= x.ownFirst) {
            return x.ownFirst;
        }
        final int[] c = x.seg;
        final int i = (t >>> SEG_SHIFT & ((1 << SEG_LOG2) - 1)) << 1;
        return c[i] <= t && t < c[i + 1] ? c[i] : segSlow(m, t, i);
    }

    /** The segment of the export node t of an earlier unit, by bisection over the units; fills cell i of the cache. */
    private static int segSlow(Machine m, int t, int i) {
        final rlvm.store.DeclTable dt = m.decls;
        int hi = m.unit; // t < unitFirstTerm(m.unit) = ownFirst
        if (dt == null || hi <= 0 || t <= 0) {
            return Integer.MAX_VALUE;
        }
        int lo = 0;
        while (hi - lo > 1) { // invariant: unitFirstTerm(lo) <= t < unitFirstTerm(hi)
            int mid = (lo + hi) >>> 1;
            if (dt.unitFirstTerm(mid) <= t) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        final int first = dt.unitFirstTerm(lo);
        final int end = dt.unitFirstTerm(hi);
        if (first > t || t >= end) {
            return Integer.MAX_VALUE; // the unit table is not what this search assumes: no child counts as its own
        }
        m.x2.seg[i] = first;
        m.x2.seg[i + 1] = end;
        return first;
    }

    /**
     * A flat inert application: a constant head and only variables, constants, sorts and literals as arguments. Its
     * evaluation is one MKAPP of atoms, cheaper than its tabling probe (PRUNE and the SUSP record), and it has no
     * sub-term whose walk a memo could save: with SW_GATED_TABLING it is never tabled, whatever its sharing flags
     * say. No DAG is walked as a tree by this: a visit costs O(arguments), and the parents keep their own gate.
     */
    private static boolean flat(Machine m, int t) {
        final Terms T = m.terms;
        if (T.kind(t) != Kind.APP) {
            return false;
        }
        int u = t;
        do {
            int k = T.kind(T.b(u));
            if (k != Kind.BVAR && k != Kind.CONST && k != Kind.SORT && k != Kind.NATLIT && k != Kind.STRLIT) {
                return false;
            }
            u = T.a(u);
        } while (T.kind(u) == Kind.APP);
        return T.kind(u) == Kind.CONST;
    }

    /**
     * {@code sharecheck} (ISA 3.1, 5.5): the walk enters node t, which has no tabling record; it must not do so twice
     * under one pruned frame in a declaration. Debug builds only (it makes the frame a tabled node would have).
     * @return 0, or FATAL
     */
    private static int walked(Machine m, int t, int eb, int en) {
        if (flat(m, t)) {
            return 0; // revisited by design
        }
        int fr = frame(m, t, eb, en);
        return fr >= SIG ? fr : Debug.shareCheck(m, t, fr);
    }

    /**
     * Completion of a tabled EVAL (I-MEMO, ISA 5.4): {@code VAL(s) := r}. VAL is never a SUSP (2.6), so a result that is
     * still a suspension after PEEK (a delayed argument or a let variable that the term merely passes on) is returned
     * untabled; evaluating the term again costs one level, its sub-terms are tabled themselves.
     */
    private static int done(Machine m, int s, int r) {
        if (r == Layout.NONE || r == Layout.HOLE) {
            return m.fault(r == Layout.HOLE ? MachineErrors.E_HOLE : MachineErrors.E_INTERNAL); // not a value
        }
        if (r > Layout.HOLE && (m.vh[r << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT) == Layout.SUSP) {
            r = Values.peek(m, r);
            if (r > Layout.HOLE && (m.vh[r << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT) == Layout.SUSP) {
                if (m.stats) {
                    m.x2.lazy++;
                }
                return r;
            }
        }
        if (s > 0) {
            final int o = s << Layout.REC_SHIFT;
            final int[] vh = m.vh;
            vh[o + Layout.M_VAL] = r;
            if (vh[o + Layout.B] == Layout.RUN_EMPTY && vh[o + Layout.C] == 0 && (m.debug & DBG_HOOKS) == 0) {
                closedPut(m, vh[o + Layout.A], r); // a closed term under ls' = 0: also in the closed-term memo
            }
        } else if ((m.debug & DBG_HOOKS) == 0) {
            closedPutUntabled(m, -s, r);
        }
        return r;
    }

    /** Completion of the untabled node t: if it is closed and has no level parameter (ls' = 0), into the closed-term memo. */
    private static void closedPutUntabled(Machine m, int t, int r) {
        final int[] tr = m.terms.regionOf(t);
        final int w = Node.wordOf(t);
        if ((tr[w + Node.W_MASK_LO] | tr[w + Node.W_MASK_HI]) == 0 && (tr[w] & Node.F_HAS_LPARAM) == 0) {
            closedPut(m, t, r);
        }
    }

    /** A tabled EVAL was answered by {@code VAL(s) = v}: statistics, and the memo check of debug builds. */
    private static int hit(Machine m, int s, int v) {
        if (m.stats) {
            m.x2.hits++;
            m.hits[Op.EVAL]++;
        }
        return m.debug != 0 ? checkVal(m, s, v) : v;
    }

    /**
     * {@code memocheck} (ISA 5.5) of {@code VAL(s)} for a plain suspension s: the suspended term is evaluated again with
     * the entry bypassed and must give {@code hit}. The comparison is up to PEEK (ISA 2.1): the re-evaluation meets
     * suspensions whose value has become known since the entry was written and builds with the values
     * ({@code NEU(c, [SUSP a])} then, {@code NEU(c, [VAL a])} now): the same value, another id.
     * @return hit, or FATAL (E_AUDIT; or a fatal fault of the re-evaluation)
     */
    private static int checkVal(Machine m, int s, int hit) {
        if (!Debug.memoEnter(m)) {
            return hit;
        }
        m.vh[(s << Layout.REC_SHIFT) + Layout.M_VAL] = 0;
        int r = m.drive(force(m, s, 0));
        m.vh[(s << Layout.REC_SHIFT) + Layout.M_VAL] = hit;
        if (r < SIG && r != hit && samePeekedSlow(m, hit, r, 24)) {
            r = hit;
        }
        return Debug.memoExit(m, Op.FORCE, s, 0, hit, r);
    }

    /**
     * Debug builds: true if a and b are the same value up to PEEK (a suspension whose VAL is known stands for it),
     * compared structurally through spines and frames to the given depth; beyond it the answer is "same"
     * (inconclusive: no alarm).
     */
    static boolean samePeekedSlow(Machine m, int a, int b, int depth) {
        int x = forceFast(m, a);
        int y = forceFast(m, b);
        a = x == Op.MISS ? a : x;
        b = y == Op.MISS ? b : y;
        if (a == b || depth == 0) {
            return true;
        }
        if (a <= Layout.HOLE || b <= Layout.HOLE) {
            return false;
        }
        final int[] vh = m.vh;
        final int oa = a << Layout.REC_SHIFT;
        final int ob = b << Layout.REC_SHIFT;
        if (((vh[oa] ^ vh[ob]) & Hash.W0_KEY_MASK) != 0) {
            return false; // kind or aux (spine length, LET) differ
        }
        switch (vh[oa] >>> Layout.KIND_SHIFT) {
            case Layout.NEU:
                return samePeekedSlow(m, vh[oa + Layout.A], vh[ob + Layout.A], depth - 1)
                        && sameRunSlow(m, vh[oa + Layout.B], vh[ob + Layout.B], depth - 1);
            case Layout.LAM:
            case Layout.PI:
            case Layout.PII:
            case Layout.SUSP:
                return vh[oa + Layout.A] == vh[ob + Layout.A] && vh[oa + Layout.C] == vh[ob + Layout.C]
                        && sameRunSlow(m, vh[oa + Layout.B], vh[ob + Layout.B], depth - 1);
            case Layout.PROJ:
                return vh[oa + Layout.B] == vh[ob + Layout.B] && vh[oa + Layout.C] == vh[ob + Layout.C]
                        && samePeekedSlow(m, vh[oa + Layout.A], vh[ob + Layout.A], depth - 1);
            case Layout.NSUC:
            case Layout.FVAR:
                return vh[oa + Layout.B] == vh[ob + Layout.B]
                        && samePeekedSlow(m, vh[oa + Layout.A], vh[ob + Layout.A], depth - 1);
            default:
                return false; // SORT, CONST, literals: canonical by id
        }
    }

    /** Debug builds: two runs (spines, frames, REST runs) slot by slot up to PEEK. */
    private static boolean sameRunSlow(Machine m, int ra, int rb, int depth) {
        if (ra == rb) {
            return true;
        }
        final int[] rs = m.rs;
        final int ha = rs[ra];
        final int n = Layout.runLen(ha);
        if (n != Layout.runLen(rs[rb]) || ((ha ^ rs[rb]) & Layout.RUN_FAR) != 0) {
            return false;
        }
        if ((ha & Layout.RUN_FAR) != 0
                && !sameRunSlow(m, rs[ra + Layout.RUN_REST_WORD], rs[rb + Layout.RUN_REST_WORD], depth)) {
            return false;
        }
        final int h = Layout.runHeaderWords(ha);
        for (int i = 0; i < n; i++) {
            if (!samePeekedSlow(m, m.rs[ra + h + i], m.rs[rb + h + i], depth)) {
                return false;
            }
        }
        return true;
    }

    /** EVAL, step 4: by node kind. s is the tabling record, or -t (none); u the depth of t in its inert nest. */
    private static int node(Machine m, int t, int s, int u, int eb, int en, int ls, int d) {
        switch (m.terms.kind(t)) {
            case Kind.APP:
                return app(m, t, s, u, eb, en, ls, d);
            case Kind.LET:
                return let(m, t, s, eb, en, ls, d);
            case Kind.PROJ:
                return proj(m, t, s, eb, en, ls, d);
            default:
                return m.fault(MachineErrors.E_INTERNAL);
        }
    }

    // ---- application ----------------------------------------------------------------------------------

    /**
     * EVAL of an application (ISA 4.2 "app"): the spine is collected in X (argument terms at X[xb .. xb + n), replaced
     * one by one by their values; X[xb - 1] = u, the depth of the node in its inert nest), the head is evaluated and
     * forced, then {@link #args}. Official: the spine of {@code instantiate(e)} with head beta (R02 section 4 "Head
     * beta": m = min(leading lambdas, n) arguments at once).
     */
    private static int app(Machine m, int t, int s, int u, int eb, int en, int ls, int d) {
        final Terms T = m.terms;
        int n = 0;
        int h = t;
        do {
            h = T.a(h);
            n++;
        } while (T.kind(h) == Kind.APP);
        int xb = m.xp + 1;
        if (!m.ensureScratch(n + 1)) {
            return m.fault(MachineErrors.E_MEM);
        }
        final int[] X = m.X;
        X[xb - 1] = u;
        for (int w = t, i = xb + n; i > xb; w = T.a(w)) {
            X[--i] = T.b(w);
        }
        m.xp = xb + n;
        if (T.kind(h) == Kind.CONST) {
            int c = atom(m, h, eb, en, ls); // a constant head: MKAPP(KCONST h, ARGV(t1) .. ARGV(tn)) (ISA 4.2)
            return c >= SIG ? c : args(m, s, c, xb, n, 0, eb, en, ls, d);
        }
        int f = eval(m, h, eb, en, ls, d + 1); // call EVAL(head) => HEAD (inline for a variable, a lambda)
        return f >= SIG ? m.sig6(f, K_EVAL_HEAD, s, xb, n, eb, en, ls) : k_evalHead(m, s, xb, n, eb, en, ls, f, d);
    }

    /** EVAL[HEAD]: r = the value of the head; it is forced before the arguments are looked at (APPLY would force it first, 4.3.1). */
    static int k_evalHead(Machine m, int s, int xb, int n, int eb, int en, int ls, int r, int d) {
        int f = force(m, r, d + 1); // call FORCE(head) => HEADF (inline unless a pending suspension)
        return f >= SIG ? m.sig6(f, K_EVAL_HEADF, s, xb, n, eb, en, ls) : args(m, s, f, xb, n, 0, eb, en, ls, d);
    }

    /** EVAL[HEADF]: r = the forced head. */
    static int k_evalHeadF(Machine m, int s, int xb, int n, int eb, int en, int ls, int r, int d) {
        return args(m, s, r, xb, n, 0, eb, en, ls, d);
    }

    /**
     * The argument loop of EVAL (ISA 4.2: {@code MKAPP(KCONST h, ARGV(t1)..ARGV(tn))} / {@code APPLY(f, ARGV(ti)..)}), from
     * argument i on; f is the forced head. An argument that the closure f does not read is not built: its slot is HOLE
     * (B6; decision 8 "never evaluated when the callee's binder is unread"). Then APPLY: MKAPP for a neutral head
     * (inline), beta for a closure.
     */
    private static int args(Machine m, int s, int f, int xb, int n, int i, int eb, int en, int ls, int d) {
        final int kind = f <= Layout.HOLE ? 0 : m.vh[f << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT;
        final long skip = kind == Layout.LAM ? unread(m, f, n) : 0L;
        int lo = 0; // the segment bound of this node for the gate of its inert arguments: computed at the first one
        for (; i < n; i++) {
            int v;
            if (i < 64 && ((skip >>> i) & 1L) != 0) {
                v = Layout.HOLE;
            } else {
                v = argvFast(m, m.X[xb + i], eb, en, ls);
                if (v >= Op.MISS) {
                    if (v != Op.MISS) {
                        return v;
                    }
                    if (lo == 0) {
                        lo = (m.x2.xsw & X_SEG) != 0 ? segLo(m, s > 0 ? m.vh[(s << Layout.REC_SHIFT) + Layout.A] : -s) : LO_ROOT;
                    }
                    v = evalInAt(m, m.X[xb + i], lo, m.X[xb - 1] + 1, eb, en, ls, d + 1); // an inert application: EVAL now => ARG
                    if (v >= SIG) {
                        return m.sig8(v, K_EVAL_ARG, s, f, xb, n, i, eb, en, ls);
                    }
                }
            }
            m.X[xb + i] = v;
        }
        // call APPLY(f, args) => APP. A neutral head is MKAPP at once: f is forced, and no HOLE was written above
        // (skip = 0; a value of ARGV is never HOLE), so the HOLE test of APPLY has nothing to find.
        int r = neutral(kind) ? Values.mkApp(m, f, xb, n) : apply(m, f, xb, n, d + 1);
        return r >= SIG ? m.sig2(r, K_EVAL_APP, s, xb) : k_evalApp(m, s, xb, r, d);
    }

    /** EVAL[ARG]: r = the value of argument i; finish the iteration, re-enter the loop. */
    static int k_evalArg(Machine m, int s, int f, int xb, int n, int i, int eb, int en, int ls, int r, int d) {
        m.X[xb + i] = r;
        return args(m, s, f, xb, n, i + 1, eb, en, ls, d);
    }

    /** EVAL[APP]: r = the application; the scratch of the spine is released (I-STACKS), the result tabled. */
    static int k_evalApp(Machine m, int s, int xb, int r, int d) {
        m.xp = xb - 1;
        return done(m, s, r);
    }

    /**
     * B6 for the arguments of an application of the LAM closure f to n arguments: bit i (i &lt; 64) is set iff the
     * first j = min(leading lambdas, n) binders are bound by this application and the body under them does not read
     * binder i (ISA 4.1 BUNREAD, 4.3.1; S1 of R05 section 4). The node mask is a superset of what is read, so a clear
     * bit is definite (2.4); an index beyond the near slots counts as read.
     */
    private static long unread(Machine m, int f, int n) {
        int j = peel(m, m.vh[(f << Layout.REC_SHIFT) + Layout.A], n);
        long mask = m.terms.fvMask(m.x2.body);
        long u = 0L;
        for (int i = j > 64 ? 64 : j; --i >= 0;) {
            int idx = j - 1 - i;
            if (idx < Layout.NEAR_SLOTS && ((mask >>> idx) & 1L) == 0) {
                u |= 1L << i;
            }
        }
        return u;
    }

    /**
     * The number j of leading lambdas of the LAM term t, at most max (>= 1); the body under them is left in
     * {@code m.x2.body}. Only syntactic lambdas count: no look through let (R02 section 4, "Head beta").
     */
    private static int peel(Machine m, int t, int max) {
        final Terms T = m.terms;
        int j = 0;
        do {
            t = T.b(t);
            j++;
        } while (j < max && T.kind(t) == Kind.LAM);
        m.x2.body = t;
        return j;
    }

    // ---- let, projection ------------------------------------------------------------------------------

    /**
     * EVAL of a let chain (ISA 4.2 "let"): zeta by sharing. The official {@code whnf_core} reduces a let by
     * {@code instantiate(body, value)} (R02 7.1): the value is substituted, no variable exists. So each bound value is
     * what an argument of a beta step is (ARGV): an atom, an inert application built at once, or a plain suspension of
     * an active term - NOT a LET suspension. The LET flag is the official hasFVar of a let variable, and a let variable
     * is a free variable only inside {@code infer_let} (R02 6.6: INFER pushes the LET suspension, perf/let-fold);
     * flagging the lets of an evaluated term would make every value built from them OPEN and switch off the Nat
     * literal folding of lazy delta (R02 8.4) where the official kernel folds (NOTES-M2 D9). A let variable the body
     * does not read gets HOLE (B6). The environment is extended on {@code es}; {@code ep} is restored when the body
     * returns.
     */
    private static int let(Machine m, int t, int s, int eb, int en, int ls, int d) {
        return letFrom(m, t, s, eb, en, ls, m.ep, Op.MISS, d);
    }

    /**
     * The let chain from let node t on. {@code v} is the value of t's bound term if it is already known (the loop was
     * interrupted by its evaluation), else {@code Op.MISS}; ep0 is {@code ep} before the first PUSH.
     */
    private static int letFrom(Machine m, int t, int s, int eb, int en, int ls, int ep0, int v, int d) {
        final Terms T = m.terms;
        do {
            int body = T.c(t);
            if (v == Op.MISS) {
                v = Layout.HOLE;
                if ((T.fvMask(body) & 1L) != 0) {
                    v = argvFast(m, T.b(t), eb, en, ls);
                    if (v >= Op.MISS) {
                        if (v != Op.MISS) {
                            return v;
                        }
                        v = evalIn(m, T.b(t), eb, en, ls, d + 1); // an inert value: EVAL now => LET
                        if (v >= SIG) {
                            return m.sig6(v, K_EVAL_LET, t, s, eb, en, ls, ep0);
                        }
                    }
                }
            }
            en = Runs.push(m, eb, en, v);
            if (en < 0) {
                return m.fault(MachineErrors.E_MEM);
            }
            eb = m.envBase;
            t = body;
            v = Op.MISS;
        } while (T.kind(t) == Kind.LET);
        int r = eval(m, t, eb, en, ls, d + 1); // call EVAL(body) => BODY
        return r >= SIG ? m.sig2(r, K_EVAL_BODY, s, ep0) : k_evalBody(m, s, ep0, r, d);
    }

    /** EVAL[LET]: r = the value bound by let node t (an inert application); finish the iteration, re-enter the loop. */
    static int k_evalLet(Machine m, int t, int s, int eb, int en, int ls, int ep0, int r, int d) {
        return letFrom(m, t, s, eb, en, ls, ep0, r, d);
    }

    /** EVAL[BODY]: r = the value of the let body. */
    static int k_evalBody(Machine m, int s, int ep0, int r, int d) {
        m.ep = ep0;
        return done(m, s, r);
    }

    /**
     * EVAL of a projection (ISA 4.2 "proj": {@code MKPROJ(S, i, call EVAL(struct))}; official {@code reduce_proj_core} on a
     * syntactic constructor, R02 7.2: the struct is not reduced).
     */
    private static int proj(Machine m, int t, int s, int eb, int en, int ls, int d) {
        int r = eval(m, m.terms.a(t), eb, en, ls, d + 1); // call EVAL(struct) => PROJ
        return r >= SIG ? m.sig2(r, K_EVAL_PROJ, t, s) : k_evalProj(m, t, s, r, d);
    }

    /** EVAL[PROJ]: r = the value of the struct. An unbound structure name is fault(E_INTERNAL) (ISA section 9). */
    static int k_evalProj(Machine m, int t, int s, int r, int d) {
        final Terms T = m.terms;
        int row = T.c(t);
        if (row <= 0) {
            return m.fault(MachineErrors.E_INTERNAL);
        }
        int v = Values.mkProj(m, row, T.b(t), r);
        return v >= SIG ? v : done(m, s, v);
    }

    // =====================================================================================================
    // ARGV
    // =====================================================================================================

    /**
     * ARGV(t) (ISA 4.2): atoms inline; an application with a constant head, or with a variable head bound to a
     * neutral, is evaluated now (builds NEUs only; inert nests in one loop, H8); everything else is MKSUSP.
     * Entry frame: ARGV[0](t, eb, en, ls).
     * @return the result, or a signal ({@code >= Op.SIG}): SUSP, ABORT, FATAL
     */
    static int argv(Machine m, int t, int eb, int en, int ls, int d) {
        int v = argvFast(m, t, eb, en, ls);
        return v != Op.MISS ? v : evalIn(m, t, eb, en, ls, d);
    }

    /**
     * ARGV without its one sub-judgment (ISA 4.2): the value of an atom; MKSUSP of an active term (lambda head, let,
     * proj, a variable head bound to anything but a neutral: decision 8 "delayed as a hash-consed suspension when
     * active"); {@code Op.MISS} for an inert application, which the caller evaluates now ({@link #evalIn}).
     */
    private static int argvFast(Machine m, int t, int eb, int en, int ls) {
        int v = atom(m, t, eb, en, ls);
        if (v != Op.MISS) {
            return v;
        }
        final Terms T = m.terms;
        if (T.kind(t) == Kind.APP && inertHead(m, T.head(t), eb, en)) {
            return Op.MISS;
        }
        return suspend(m, t, eb, en, ls);
    }

    /**
     * True if an application with head term h is inert under (eb, en) (ISA 4.2 ARGV): h is a constant, or a variable
     * bound to a neutral (CONST, NEU, FVAR, PROJ; a suspension whose value is known counts by its value, PEEK).
     */
    private static boolean inertHead(Machine m, int h, int eb, int en) {
        final Terms T = m.terms;
        int k = T.kind(h);
        if (k == Kind.CONST) {
            return true;
        }
        if (k != Kind.BVAR) {
            return false;
        }
        int i = T.a(h);
        if (i < 0 || i >= en) {
            return false; // the evaluation of the head reports it
        }
        int v = m.es[eb + en - 1 - i];
        if (v <= Layout.HOLE) {
            return false;
        }
        int[] vh = m.vh;
        int kind = vh[v << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT;
        if (kind == Layout.SUSP) {
            v = Values.peek(m, v);
            if (v <= Layout.HOLE) {
                return false;
            }
            kind = m.vh[v << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT;
        }
        return neutral(kind);
    }

    /** CONST, NEU, FVAR, PROJ: the heads MKAPP accepts (ISA 4.2). */
    private static boolean neutral(int kind) {
        return kind == Layout.NEU || kind == Layout.CONST || kind == Layout.FVAR || kind == Layout.PROJ;
    }

    /**
     * MKSUSP(t, env) (ISA 4.2): the plain suspension {@code SUSP(t, PRUNE(env, mask t), ls')}; a known VAL is returned in
     * its place. Official: a delayed {@code instantiate}. (LET suspensions are made by INFER only, see {@link #let}.)
     */
    private static int suspend(Machine m, int t, int eb, int en, int ls) {
        return Values.mkSusp(m, t, frame(m, t, eb, en), ls, false);
    }

    // =====================================================================================================
    // FORCE
    // =====================================================================================================

    /**
     * FORCE(s) (ISA 4.3): not a SUSP: s; VAL known: VAL; else EVAL(term, frame, ls), VAL := result.
     * Entry frame: FORCE[0](s).
     * @return the result, or a signal ({@code >= Op.SIG}): SUSP, ABORT, FATAL
     */
    static int force(Machine m, int s, int d) {
        if (s <= Layout.HOLE) {
            return s; // an immediate (NONE and HOLE are not values; they pass through)
        }
        final int o = s << Layout.REC_SHIFT;
        final int[] vh = m.vh;
        final int w0 = vh[o];
        if ((w0 >>> Layout.KIND_SHIFT) != Layout.SUSP) {
            return s; // 1. inline answers
        }
        int v = vh[o + Layout.M_VAL];
        if (v != 0) {
            return m.debug != 0 && (w0 & Layout.W0_LET) == 0 ? checkVal(m, s, v) : v;
        }
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig; // 2. TICK
        }
        if (m.stats) {
            m.call(Op.FORCE); // 2b. call event
        }
        if (d > m.hostDepth) {
            return m.spill1(Op.K_FORCE, s); // 3. too deep: become the frame FORCE[0](s)
        }
        final int t = vh[o + Layout.A];
        final int ls = vh[o + Layout.C];
        final int ep0 = m.ep;
        int en = Runs.enter(m, vh[o + Layout.B]); // ENTER(frame)
        if (en < 0) {
            return m.fault(MachineErrors.E_MEM);
        }
        // call EVAL(term, frame, ls) => 1. A plain suspension is the tabling record of its own evaluation; a LET
        // suspension is a record of its own (2.8) and evaluates through the plain record's tabling.
        int r = (w0 & Layout.W0_LET) != 0 ? eval(m, t, m.envBase, en, ls, d + 1) : evalAt(m, t, s, m.envBase, en, ls, d + 1);
        return r >= SIG ? m.sig2(r, K_FORCE_1, s, ep0) : k_force1(m, s, ep0, r, d);
    }

    /**
     * FORCE[1]: r = the value of the suspended term. It may itself be a suspension (a delayed argument that the term
     * passes on): VAL is never a SUSP (2.6), so that one is forced too.
     */
    static int k_force1(Machine m, int s, int ep0, int r, int d) {
        m.ep = ep0;
        if (r == Layout.NONE || r == Layout.HOLE) {
            return m.fault(r == Layout.HOLE ? MachineErrors.E_HOLE : MachineErrors.E_INTERNAL); // not a value
        }
        int v = force(m, r, d + 1); // call FORCE(r) => 2 (inline unless r is a pending suspension)
        return v >= SIG ? m.sig1(v, K_FORCE_2, s) : k_force2(m, s, v, d);
    }

    /** FORCE[2]: {@code VAL(s) := v} (I-MEMO: the step that completes the judgment). */
    static int k_force2(Machine m, int s, int v, int d) {
        m.vh[(s << Layout.REC_SHIFT) + Layout.M_VAL] = v;
        return v;
    }

    /**
     * The inline part of FORCE: v if it is not a SUSP, its VAL if known, else {@code Op.MISS}. Never ticks or calls.
     */
    static int forceFast(Machine m, int v) {
        if (v <= Layout.HOLE) {
            return v;
        }
        final int o = v << Layout.REC_SHIFT;
        final int[] vh = m.vh;
        if ((vh[o] >>> Layout.KIND_SHIFT) != Layout.SUSP) {
            return v;
        }
        int x = vh[o + Layout.M_VAL];
        return x != 0 ? x : Op.MISS;
    }

    // =====================================================================================================
    // APPLY
    // =====================================================================================================

    /**
     * APPLY(f, X[xb .. xb + n)) (ISA 4.3.1), n >= 1: FORCE f; LAM: bind min(leading lambdas, remaining) arguments
     * (HOLE for unread binders, B6), EVAL the body, continue; CONST, NEU, FVAR, PROJ: MKAPP; otherwise
     * fault(E_ILLTYPED). The scratch arguments belong to the caller. Official: head beta with several arguments (R02
     * section 4). Entry frame: APPLY[0](f, xb, n).
     * @return the result, or a signal ({@code >= Op.SIG}): SUSP, ABORT, FATAL
     */
    static int apply(Machine m, int f, int xb, int n, int d) {
        int g = forceFast(m, f); // 1. inline answers: a neutral head is MKAPP (class p)
        if (g != Op.MISS) {
            int kind = Values.kind(m, g);
            if (kind != Layout.LAM) {
                return neutral(kind) ? mkApp(m, g, xb, n) : illTyped(m, g);
            }
            f = g;
        }
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig; // 2. TICK
        }
        if (m.stats) {
            m.call(Op.APPLY); // 2b. call event
        }
        if (d > m.hostDepth) {
            return m.spill3(Op.K_APPLY, f, xb, n); // 3. too deep: become the frame APPLY[0](f, xb, n)
        }
        return applyFrom(m, f, xb, n, 0, d); // 4. body
    }

    /**
     * The loop of ISA 4.3.1 with k arguments consumed: f is a closure, a pending suspension, or the value a body
     * returned. One group of leading lambdas per iteration (the official multi-argument beta).
     */
    private static int applyFrom(Machine m, int f, int xb, int n, int k, int d) {
        for (;;) {
            int g = force(m, f, d + 1); // call FORCE(f) => 1
            if (g >= SIG) {
                return m.sig3(g, K_APPLY_1, xb, n, k);
            }
            int kind = Values.kind(m, g);
            if (kind != Layout.LAM) {
                return neutral(kind) ? mkApp(m, g, xb + k, n - k) : illTyped(m, g);
            }
            final int ep0 = m.ep;
            int r;
            int j = (m.sw & Op.SW_CODE) != 0 ? codeArity(m, g, n - k) : 0;
            if (j != 0) {
                // CODE(f): the block of the closure's binder term at entry j, capRun = the frame (staging step 4)
                final int o = g << Layout.REC_SHIFT;
                r = Code.callClosure(m, m.vh[o + Layout.M_CODE], j, m.vh[o + Layout.B], m.vh[o + Layout.C], xb + k, d + 1);
                m.x2.amOpen = false;
            } else {
                j = peel(m, m.vh[(g << Layout.REC_SHIFT) + Layout.A], n - k);
                r = beta(m, g, xb + k, j, d); // => 2 (3 if the step is to be recorded in the APPLY memo)
            }
            k += j;
            if (r >= SIG) {
                return m.x2.amOpen ? m.sig6(r, K_APPLY_3, xb, n, k, ep0, g, j) : m.sig4(r, K_APPLY_2, xb, n, k, ep0);
            }
            if (m.x2.amOpen) {
                amPut(m, g, xb + k - j, j, r);
            }
            m.ep = ep0;
            if (k == n) {
                return r;
            }
            f = r;
        }
    }

    /**
     * One beta step (ISA 4.3.1): the closure g applied to the j arguments X[xa .. xa + j); {@link #peel} left the
     * body under the j lambdas in {@code m.x2.body}. A body that is one of the j variables is that argument (PEEKed):
     * what EVAL of the variable under the bound environment returns, without building the environment. With
     * SW_M2A a body that is a projection of one of the j variables is MKPROJ of that argument ({@code X_SELECT}).
     * With SW_M2B a step of at most three binders is looked up in the APPLY memo ({@code X_AMEMO}). Otherwise
     * {@code env = ENTER(frame) + arguments} and EVAL of the body (untabled with SW_M2A, {@code X_NOBODY}).
     * @return the value of the body, or a signal (SUSP: the body's frames are on the worklist)
     */
    private static int beta(Machine m, int g, int xa, int j, int d) {
        final int body = m.x2.body;
        m.x2.amOpen = false;
        final int[] tr = m.terms.regionOf(body);
        final int w = Node.wordOf(body);
        final int bk = tr[w] & 0xF;
        if (bk == Kind.BVAR) {
            final int idx = tr[w + Node.W_A];
            if (idx >= 0 && idx < j && (m.debug & DBG_HOOKS) == 0) {
                return pick(m, m.X[xa + j - 1 - idx]);
            }
        } else if (bk == Kind.PROJ && (m.debug & DBG_HOOKS) == 0 && (feat(m) & X_SELECT) != 0) {
            int v = betaProj(m, body, xa, j);
            if (v != Op.MISS) {
                return v;
            }
        }
        final int x = feat(m);
        if ((x & X_AMEMO) != 0 && j <= AM_ARGS && (m.debug & DBG_HOOKS) == 0) {
            int v = amGet(m, g, xa, j);
            if (v != Op.MISS) {
                return v;
            }
        }
        int e = bind(m, g, xa, j); // env = ENTER(frame) + j arguments
        if (e >= SIG) {
            return e;
        }
        final int ls = m.vh[(g << Layout.REC_SHIFT) + Layout.C];
        int r = (x & X_NOBODY) != 0 ? evalNT(m, body, m.envBase, m.x2.en, ls, d + 1) : eval(m, body, m.envBase, m.x2.en, ls, d + 1);
        m.x2.amOpen = (x & X_AMEMO) != 0 && j <= AM_ARGS && (m.debug & DBG_HOOKS) == 0; // set last: the body's own beta steps wrote it
        return r;
    }

    /**
     * The key of the APPLY memo for closure g applied to the j <= 3 arguments X[xa .. xa + j): the arguments as
     * {@link #bind} binds them (HOLE for a binder the body under the j lambdas does not read, B6) are left in
     * {@code m.x2.ak0 .. ak2} (NONE beyond j). The value of the step is a function of exactly this key (ISA 4.3.1: the
     * body, the frame and ls are the closure's; I-ID).
     * @return the index of the key's cell
     */
    private static int amKey(Machine m, int g, int xa, int j) {
        final Ext x = m.x2;
        peel(m, m.vh[(g << Layout.REC_SHIFT) + Layout.A], j);
        final long mask = m.terms.fvMask(x.body);
        final int[] X = m.X;
        int a0 = (mask >>> (j - 1) & 1L) != 0 ? X[xa] : Layout.HOLE;
        int a1 = j < 2 ? Layout.NONE : (mask >>> (j - 2) & 1L) != 0 ? X[xa + 1] : Layout.HOLE;
        int a2 = j < 3 ? Layout.NONE : (mask & 1L) != 0 ? X[xa + 2] : Layout.HOLE;
        x.ak0 = a0;
        x.ak1 = a1;
        x.ak2 = a2;
        int h = Hash.step(Hash.step(Hash.step(Hash.seed(g), a0), a1), a2);
        return (Hash.fin(h) & ((1 << AM_LOG2) - 1)) << 3;
    }

    /** The APPLY memo: the recorded value of closure g applied to X[xa .. xa + j) (PEEKed), or {@code Op.MISS}. */
    private static int amGet(Machine m, int g, int xa, int j) {
        final int body = m.x2.body;
        final int i = amKey(m, g, xa, j);
        final Ext x = m.x2;
        x.body = body;
        final int[] am = x.am;
        if (am[i] != g || am[i + 1] != x.ak0 || am[i + 2] != x.ak1 || am[i + 3] != x.ak2 || am[i + 4] != m.epoch || am[i + 5] != j) {
            return Op.MISS;
        }
        if (m.stats) {
            x.amHits++;
        }
        return pick(m, am[i + 6]);
    }

    /** {@code APPLY(g, X[xa .. xa + j)) = r} (one beta step, r not a signal): into the APPLY memo. */
    private static void amPut(Machine m, int g, int xa, int j, int r) {
        final int i = amKey(m, g, xa, j);
        final Ext x = m.x2;
        final int[] am = x.am;
        am[i] = g;
        am[i + 1] = x.ak0;
        am[i + 2] = x.ak1;
        am[i + 3] = x.ak2;
        am[i + 4] = m.epoch;
        am[i + 5] = j;
        am[i + 6] = r;
    }

    /**
     * {@code X_SELECT}: the projection node body, if its struct is one of the j variables bound to X[xa .. xa + j):
     * MKPROJ of that argument (PEEKed), completed like the EVAL of the node. Else {@code Op.MISS}.
     */
    private static int betaProj(Machine m, int body, int xa, int j) {
        final Terms T = m.terms;
        final int st = T.a(body);
        final int row = T.c(body);
        if (T.kind(st) != Kind.BVAR || row <= 0) {
            return Op.MISS;
        }
        final int idx = T.a(st);
        if (idx < 0 || idx >= j) {
            return Op.MISS;
        }
        int sv = pick(m, m.X[xa + j - 1 - idx]);
        if (sv >= SIG) {
            return sv;
        }
        int v = Values.mkProj(m, row, T.b(body), sv);
        return v >= SIG ? v : done(m, -body, v);
    }

    /**
     * The value EVAL returns for a variable bound to the argument v (ISA 4.2 "variable -> GET", D8): v PEEKed; reading
     * HOLE is fault(E_HOLE) (2.1), NONE is no value.
     */
    private static int pick(Machine m, int v) {
        if (v <= Layout.HOLE) {
            return v == Layout.HOLE ? m.fault(MachineErrors.E_HOLE) : v == Layout.NONE ? m.fault(MachineErrors.E_INTERNAL) : v;
        }
        if (v >= SIG) {
            return v;
        }
        return (m.vh[v << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT) == Layout.SUSP ? Values.peek(m, v) : v;
    }

    /** APPLY[1]: r = the forced function. */
    static int k_apply1(Machine m, int xb, int n, int k, int r, int d) {
        return applyFrom(m, r, xb, n, k, d);
    }

    /** APPLY[3]: r = the value of the body of closure g after its j arguments, k in all: recorded, then as APPLY[2]. */
    static int k_apply3(Machine m, int xb, int n, int k, int ep0, int g, int j, int r, int d) {
        amPut(m, g, xb + k - j, j, r);
        m.ep = ep0;
        return k == n ? r : applyFrom(m, r, xb, n, k, d);
    }

    /** APPLY[2]: r = the value of the body after k arguments. */
    static int k_apply2(Machine m, int xb, int n, int k, int ep0, int r, int d) {
        m.ep = ep0;
        return k == n ? r : applyFrom(m, r, xb, n, k, d);
    }

    /**
     * {@code env = ENTER(frame of f)}, then PUSH of the first j = min(leading lambdas of f, n) arguments X[xa ..]:
     * {@code body_j reads the i-th ? a[i] : HOLE} (ISA 4.3.1, B6). The body under the j lambdas is in
     * {@code m.x2.body} ({@link #peel}); leaves the environment at {@code m.envBase} with length {@code m.x2.en}.
     * @return j, or a signal
     */
    private static int bind(Machine m, int f, int xa, int j) {
        final int o = f << Layout.REC_SHIFT;
        final long mask = m.terms.fvMask(m.x2.body);
        int en = Runs.enter(m, m.vh[o + Layout.B]);
        if (en < 0 || !m.ensureEnv(j)) {
            return m.fault(MachineErrors.E_MEM);
        }
        final int[] es = m.es;
        final int[] X = m.X;
        final int p = m.envBase + en;
        for (int i = 0; i < j; i++) {
            int idx = j - 1 - i; // de Bruijn index of binder i in body_j
            boolean reads = idx >= Layout.NEAR_SLOTS ? mask < 0 : ((mask >>> idx) & 1L) != 0;
            es[p + i] = reads ? X[xa + i] : Layout.HOLE;
        }
        m.ep = p + j;
        m.x2.en = en + j;
        return j;
    }

    /**
     * MKAPP(f, X[xa .. xa + n)) for a neutral f (ISA 4.2). A HOLE among the arguments would enter a spine, where it
     * could be compared: the caller passed HOLE for a binder that is read after all, fault(E_HOLE).
     */
    private static int mkApp(Machine m, int f, int xa, int n) {
        final int[] X = m.X;
        for (int i = xa, e = xa + n; i < e; i++) {
            if (X[i] == Layout.HOLE) {
                return m.fault(MachineErrors.E_HOLE);
            }
        }
        return Values.mkApp(m, f, xa, n);
    }

    /** APPLY of something that is neither a closure nor a neutral (ISA 4.3.1 "otherwise"): only inside an unchecked body. */
    private static int illTyped(Machine m, int f) {
        return m.fault(f == Layout.HOLE ? MachineErrors.E_HOLE : MachineErrors.E_ILLTYPED);
    }

    /**
     * {@code CODE(f)} (ISA 2.6, 3.3 "Finding the block of a closure"; staging step 4, only with SW_CODE): the block of the
     * binder term of closure clo, looked up at its first APPLY or INST and kept in memo word CODE ({@code NOCODE}:
     * none). A hint: whether a closure runs its block or is walked changes no result (decision 6).
     * @return the block id, or 0
     */
    private static int codeOf(Machine m, int clo) {
        final int o = clo << Layout.REC_SHIFT;
        int c = m.vh[o + Layout.M_CODE];
        if (c == 0) {
            c = Code.closureBlock(m, m.vh[o + Layout.A]);
            m.vh[o + Layout.M_CODE] = c == 0 ? Layout.NOCODE : c;
        }
        return c == Layout.NOCODE ? 0 : c;
    }

    /**
     * The number j of binders of closure clo that its block takes from n arguments: min(leading lambdas, n, arity of the
     * block), if the closure has a block with that entry; else 0 (walk the term). The block is in memo word CODE then.
     */
    private static int codeArity(Machine m, int clo, int n) {
        int blk = codeOf(m, clo);
        if (blk == 0) {
            return 0;
        }
        return Code.entryFor(m, blk, n);
    }

    // =====================================================================================================
    // INST
    // =====================================================================================================

    /**
     * INST(P, x) (ISA 4.3): P is PI or PII. COD known: it. PI: EVAL(body, PUSH(ENTER(frame), reads0 ? x : HOLE), ls);
     * PII: INFER(body, PUSH(frame, x), ls, ONLY). Official: {@code instantiate(binding_body(f_type), arg)} (R02 6.5),
     * type of a lambda body (6.3). Entry frame: INST[0](pi, x).
     * @return the result, or a signal ({@code >= Op.SIG}): SUSP, ABORT, FATAL
     */
    static int inst(Machine m, int pi, int x, int d) {
        if (pi <= Layout.HOLE) {
            return m.fault(MachineErrors.E_INTERNAL);
        }
        final int o = pi << Layout.REC_SHIFT;
        final int[] vh = m.vh;
        final int kind = vh[o] >>> Layout.KIND_SHIFT;
        if (kind != Layout.PI && kind != Layout.PII) {
            return m.fault(MachineErrors.E_INTERNAL);
        }
        int c = vh[o + Layout.M_COD];
        if (c != 0) {
            return m.debug != 0 ? checkCod(m, pi, c) : c; // 1. inline answer: the codomain does not depend on the argument (2.6)
        }
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig; // 2. TICK
        }
        if (m.stats) {
            m.call(Op.INST); // 2b. call event
        }
        if (d > m.hostDepth) {
            return m.spill2(Op.K_INST, pi, x); // 3. too deep: become the frame INST[0](pi, x)
        }
        return instBody(m, pi, x, kind, d); // 4. body
    }

    /** INST, step 4: the codomain of the PI / PII record pi at x. */
    private static int instBody(Machine m, int pi, int x, int kind, int d) {
        if (kind == Layout.PI && (m.sw & Op.SW_CODE) != 0 && codeArity(m, pi, 1) == 1) {
            return instCode(m, pi, x, d);
        }
        final int o = pi << Layout.REC_SHIFT;
        final int[] vh = m.vh;
        final int body = m.terms.b(vh[o + Layout.A]);
        final int ls = vh[o + Layout.C];
        final boolean reads0 = (m.terms.fvMask(body) & 1L) != 0;
        final int ep0 = m.ep;
        int en = Runs.enter(m, vh[o + Layout.B]);
        if (en >= 0) {
            en = Runs.push(m, m.envBase, en, reads0 ? x : Layout.HOLE); // B6
        }
        if (en < 0) {
            return m.fault(MachineErrors.E_MEM);
        }
        // call EVAL / INFER(body) => 1
        int r = kind != Layout.PI ? Infer.infer(m, body, m.envBase, en, ls, Op.ONLY, d + 1)
                : (feat(m) & X_NOBODY) != 0 ? evalNT(m, body, m.envBase, en, ls, d + 1) : eval(m, body, m.envBase, en, ls, d + 1);
        int memo = reads0 ? 0 : pi;
        return r >= SIG ? m.sig2(r, K_INST_1, memo, ep0) : k_inst1(m, memo, ep0, r, d);
    }

    /**
     * INST of a PI closure that has a block (ISA 4.3 "CODE present: its block"; staging step 4, only with SW_CODE): the
     * block's entry for one binder on the argument in one scratch word, capRun = the frame.
     */
    private static int instCode(Machine m, int pi, int x, int d) {
        final int o = pi << Layout.REC_SHIFT;
        final boolean reads0 = (m.terms.fvMask(m.terms.b(m.vh[o + Layout.A])) & 1L) != 0;
        final int xb = m.xp;
        if (!m.ensureScratch(1)) {
            return m.fault(MachineErrors.E_MEM);
        }
        m.X[xb] = reads0 ? x : Layout.HOLE;
        m.xp = xb + 1;
        int r = Code.callClosure(m, m.vh[o + Layout.M_CODE], 1, m.vh[o + Layout.B], m.vh[o + Layout.C], xb, d + 1); // => 2
        int memo = reads0 ? 0 : pi;
        return r >= SIG ? m.sig2(r, K_INST_2, memo, xb) : k_inst2(m, memo, xb, r, d);
    }

    /** INST[2]: r = the codomain computed by the closure's block; the scratch word of the argument is released. */
    static int k_inst2(Machine m, int memo, int xb, int r, int d) {
        m.xp = xb;
        return k_inst1(m, memo, m.ep, r, d);
    }

    /**
     * INST[1]: r = the codomain. If the body does not read its variable the result is independent of the argument and
     * is kept in COD (ISA 2.6), unless it is still a suspension (a memo word holds a value).
     */
    static int k_inst1(Machine m, int memo, int ep0, int r, int d) {
        m.ep = ep0;
        if (r > Layout.HOLE && (m.vh[r << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT) == Layout.SUSP) {
            r = Values.peek(m, r);
            if (r > Layout.HOLE && (m.vh[r << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT) == Layout.SUSP) {
                return r;
            }
        }
        if (memo != 0) {
            m.vh[(memo << Layout.REC_SHIFT) + Layout.M_COD] = r;
        }
        return r;
    }

    /** {@code memocheck} (ISA 5.5) of {@code COD(pi)}: INST with the entry bypassed (the argument is not read: HOLE), compared up to PEEK. */
    private static int checkCod(Machine m, int pi, int hit) {
        if (!Debug.memoEnter(m)) {
            return hit;
        }
        m.vh[(pi << Layout.REC_SHIFT) + Layout.M_COD] = 0;
        int r = m.drive(inst(m, pi, Layout.HOLE, 0));
        m.vh[(pi << Layout.REC_SHIFT) + Layout.M_COD] = hit;
        if (r < SIG && r != hit && samePeekedSlow(m, hit, r, 24)) {
            r = hit;
        }
        return Debug.memoExit(m, Op.INST, pi, 0, hit, r);
    }

    /**
     * The inline part of INST: the COD memo if known (2.6), else {@code Op.MISS}.
     */
    static int instFast(Machine m, int pi, int x) {
        int c = m.vh[(pi << Layout.REC_SHIFT) + Layout.M_COD];
        return c != 0 ? c : Op.MISS;
    }

    // =====================================================================================================
    // DOM
    // =====================================================================================================

    /**
     * DOM(v) for a LAM, PI or PII record: memo M_DOM, else EVAL(domain term, frame, ls) (ISA 4.4.3, 2.6).
     * Entry frame: DOM[0](v).
     * @return the result, or a signal ({@code >= Op.SIG}): SUSP, ABORT, FATAL
     */
    static int dom(Machine m, int v, int d) {
        if (v <= Layout.HOLE) {
            return m.fault(MachineErrors.E_INTERNAL);
        }
        final int o = v << Layout.REC_SHIFT;
        final int[] vh = m.vh;
        final int kind = vh[o] >>> Layout.KIND_SHIFT;
        if (kind != Layout.LAM && kind != Layout.PI && kind != Layout.PII) {
            return m.fault(MachineErrors.E_INTERNAL);
        }
        int c = vh[o + Layout.M_DOM];
        if (c != 0) {
            return m.debug != 0 ? Debug.memoCheck(m, Op.DOM, v, 0, c) : c; // 1. inline answer
        }
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig; // 2. TICK
        }
        if (m.stats) {
            m.call(Op.DOM); // 2b. call event
        }
        if (d > m.hostDepth) {
            return m.spill1(Op.K_DOM, v); // 3. too deep: become the frame DOM[0](v)
        }
        final int ep0 = m.ep;
        final int dt = m.terms.a(vh[o + Layout.A]);
        int en = 0;
        int eb = ep0;
        if (m.terms.fvMask(dt) != 0) { // a closed domain term reads no binding: no ENTER
            en = Runs.enter(m, vh[o + Layout.B]);
            if (en < 0) {
                return m.fault(MachineErrors.E_MEM);
            }
            eb = m.envBase;
        }
        int r = eval(m, dt, eb, en, vh[o + Layout.C], d + 1); // call EVAL(domain) => 1
        return r >= SIG ? m.sig2(r, K_DOM_1, v, ep0) : k_dom1(m, v, ep0, r, d);
    }

    /**
     * DOM[1]: r = the value of the domain term. The domain of a binder is the type of the variable that OPEN picks
     * (2.8) and is compared at once by every caller, so it is forced: DOM never returns, and M_DOM never holds, a
     * suspension.
     */
    static int k_dom1(Machine m, int v, int ep0, int r, int d) {
        m.ep = ep0;
        int w = force(m, r, d + 1); // call FORCE(r) => 2 (inline unless a pending suspension)
        return w >= SIG ? m.sig1(w, K_DOM_2, v) : k_dom2(m, v, w, d);
    }

    /** DOM[2]: {@code M_DOM(v) := w} (I-MEMO). */
    static int k_dom2(Machine m, int v, int w, int d) {
        m.vh[(v << Layout.REC_SHIFT) + Layout.M_DOM] = w;
        return w;
    }

    // =====================================================================================================
    // The family's part of the driver
    // =====================================================================================================

    /**
     * The family's part of the driver (H6): one case per frame key, calling the step with the payload words
     * as arguments, {@code r} as the result register and d = 0. The state-0 cases (spilled entry steps) are
     * given; M2 adds one case per continuation step (at most 50 cases per method, H10: split by op).
     */
    static int resume(Machine m, int key, int[] w, int p, int r) {
        switch (key) {
            case Op.K_EVAL:
                return eval(m, w[p], w[p + 1], w[p + 2], w[p + 3], 0);
            case K_EVAL_IN:
                return evalInAt(m, w[p], w[p + 1], w[p + 2], w[p + 3], w[p + 4], w[p + 5], 0);
            case K_EVAL_NB:
                return evalNT(m, w[p], w[p + 1], w[p + 2], w[p + 3], 0);
            case K_EVAL_HEAD:
                return k_evalHead(m, w[p], w[p + 1], w[p + 2], w[p + 3], w[p + 4], w[p + 5], r, 0);
            case K_EVAL_HEADF:
                return k_evalHeadF(m, w[p], w[p + 1], w[p + 2], w[p + 3], w[p + 4], w[p + 5], r, 0);
            case K_EVAL_ARG:
                return k_evalArg(m, w[p], w[p + 1], w[p + 2], w[p + 3], w[p + 4], w[p + 5], w[p + 6], w[p + 7], r, 0);
            case K_EVAL_APP:
                return k_evalApp(m, w[p], w[p + 1], r, 0);
            case K_EVAL_BODY:
                return k_evalBody(m, w[p], w[p + 1], r, 0);
            case K_EVAL_PROJ:
                return k_evalProj(m, w[p], w[p + 1], r, 0);
            case K_EVAL_LET:
                return k_evalLet(m, w[p], w[p + 1], w[p + 2], w[p + 3], w[p + 4], w[p + 5], r, 0);
            case Op.K_FORCE:
                return force(m, w[p], 0);
            case K_FORCE_1:
                return k_force1(m, w[p], w[p + 1], r, 0);
            case K_FORCE_2:
                return k_force2(m, w[p], r, 0);
            case Op.K_APPLY:
                return apply(m, w[p], w[p + 1], w[p + 2], 0);
            case K_APPLY_1:
                return k_apply1(m, w[p], w[p + 1], w[p + 2], r, 0);
            case K_APPLY_2:
                return k_apply2(m, w[p], w[p + 1], w[p + 2], w[p + 3], r, 0);
            case K_APPLY_3:
                return k_apply3(m, w[p], w[p + 1], w[p + 2], w[p + 3], w[p + 4], w[p + 5], r, 0);
            case Op.K_INST:
                return inst(m, w[p], w[p + 1], 0);
            case K_INST_1:
                return k_inst1(m, w[p], w[p + 1], r, 0);
            case K_INST_2:
                return k_inst2(m, w[p], w[p + 1], r, 0);
            case Op.K_ARGV:
                return argv(m, w[p], w[p + 1], w[p + 2], w[p + 3], 0);
            case Op.K_DOM:
                return dom(m, w[p], 0);
            case K_DOM_1:
                return k_dom1(m, w[p], w[p + 1], r, 0);
            case K_DOM_2:
                return k_dom2(m, w[p], r, 0);
            default:
                return m.fault(MachineErrors.E_INTERNAL);
        }
    }
}
