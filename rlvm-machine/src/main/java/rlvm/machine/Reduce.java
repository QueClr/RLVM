package rlvm.machine;

import static rlvm.machine.Op.SIG;

import rlvm.api.MachineErrors;
import rlvm.store.DeclKind;
import rlvm.store.DeclTable;

/**
 * Family RD (ISA 4.3): {@code DELTA}, {@code CORE}, {@code WHNF}, {@code IOTA}, {@code QUOTR}; the family's
 * resume switch (which also dispatches the frames of {@link NatRed} and {@link Rules}). Owner: M3.
 *
 * <p>Entry steps are the frozen cross-package interface; continuation steps {@code k_*}, loop methods
 * and frame keys are this file's own (stepper-convention.md H2-H4; template in WORK-PACKAGES.md).
 *
 * <h2>What reduces where (official rules: R02 section 7)</h2>
 * <pre>
 * CORE  = whnf_core (7.1): force a suspension, project a constructor (7.2), iota (7.4), quotients (7.5), a
 *         PROJ head that reduces is re-applied. No delta, no Nat extension. A stuck value is returned AS IT IS.
 * WHNF  = whnf (7.6): loop { CORE full; reduce_nat (NSUC and the 14 binary functions) BEFORE delta; one delta }.
 * DELTA = unfold_definition (7.6): definitions and theorems with the right number of levels.
 * IOTA  = inductive_reduce_rec (7.4): K before any reduction of the major, WHNF of the major, literal views,
 *         structure eta, rule selection by constructor, the LAST nfields arguments.
 * QUOTR = quot_reduce_rec (7.5).
 * </pre>
 * The frames of this family, as they are written when a judgment is suspended (H2: the payload of
 * {@code J[k]} is the parameter list of {@code k_Jk}):
 * <pre>
 * WHNF[0](v)   WHNF[1]()   WHNF[2](v, p0, p1)   WHNF[3..5](v, p0, p1, t1)
 * CORE[0](v, mode)   CORE[1](mode)   CORE[2](p)   CORE[3](p)   CORE[4](cur)   CORE[5](xb)   CORE[6](v, mode, cur)
 * DELTA[0](v)   DELTA[1](v, head)   DELTA[2](v, xb)   DELTA[3](v, k)
 * IOTA[0](v)   IOTA[1,2](v, major)   IOTA[3](v, major, ty, c)   IOTA[4](v, major, c)   IOTA[5,6](v)
 * IOTA[7,8](v, w)   IOTA[9](v, w, ty)   IOTA[10](v, major, rule)   IOTA[11](v, w, ty, rule)   IOTA[12](xb)
 * IOTA[13](v, xb)
 * QUOTR[0](v)   QUOTR[1](v)   QUOTR[2](xb)
 * </pre>
 */
final class Reduce {
    private Reduce() {}

    /**
     * Session state private to this package (caches, counters, scratch of its own): add fields here
     * instead of asking for fields in {@link Machine}. One instance per machine, field {@code m.x3}.
     */
    static final class Ext {
        /** Level list {@code [0]} of the worker's list store (the levels of {@code List.cons.{0}}), 0 = not made yet. */
        int zeroList;

        // ---- big-number nests (NatRed, NOTES-M3 D16) --------------------------------------------------------
        /** Slots of the lossy side memo of a nest evaluation (value ref -> handle in {@link #scratch}). */
        static final int NEST_SLOTS = 1 << 10;
        /** The store of the intermediate numbers of nest evaluations; null until the first one. */
        rlvm.nat.NatStore scratch;
        /** Stamp of the side memo; advanced whenever {@link #scratch} is emptied. Starts at 1: a zeroed slot is empty. */
        int scratchEpoch = 1;
        final int[] nestKey = new int[NEST_SLOTS];
        final int[] nestStamp = new int[NEST_SLOTS];
        final long[] nestVal = new long[NEST_SLOTS];
        /** Nest nodes evaluated by the current root (its tick charge and its work bound). */
        int nestWork;
        /**
         * A fold of the WHNF loop is evaluated as a nest on handles once the session's numbers hold this many
         * bytes (ISA 1.6 footprint): below it nothing differs from the plain judgment. Tests set it to 0.
         */
        long nestMinBytes = 16L << 20;
        /** {@link #scratch} is emptied at the next root once it holds more than this. */
        long scratchLimit = 64L << 20;
        /** Statistics: roots answered on handles, roots handed back to the judgment, scratch resets. */
        long nestRoots;
        long nestFallbacks;
        long scratchResets;

        /** Session reset (ISA 1.6): forget everything, in O(used). The worker's list overlay is reset with the session. */
        void reset(Machine m) {
            zeroList = 0;
            if (scratch != null && scratch.nextId() != scratch.firstId()) {
                scratch.reset(); // large slabs go back to the collector with the session
            }
            dropScratch();
        }

        /** Empties the scratch store (its slab is kept for the next nests) and, with the stamp, the side memo. */
        void dropScratch() {
            if (scratch != null && scratch.nextId() != scratch.firstId()) {
                scratch.truncate(scratch.firstId());
                scratchResets++;
            }
            if (++scratchEpoch == Integer.MAX_VALUE) {
                java.util.Arrays.fill(nestStamp, 0);
                scratchEpoch = 1;
            }
        }

        /** Start of a declaration. */
        void beginDecl(Machine m) {}

        /** Bytes in use of this package's own session tables (part of the footprint, ISA 1.6). */
        long bytes() {
            return scratch == null ? 0 : scratch.bytesUsed();
        }
    }

    // ---- frame keys of this family's resume states (H6) ---------------------------------------------------
    static final int K_WHNF_1 = Op.K_WHNF | 1;
    static final int K_WHNF_2 = Op.K_WHNF | 2;
    static final int K_WHNF_3 = Op.K_WHNF | 3;
    static final int K_WHNF_4 = Op.K_WHNF | 4;
    static final int K_WHNF_5 = Op.K_WHNF | 5;
    static final int K_CORE_1 = Op.K_CORE | 1;
    static final int K_CORE_2 = Op.K_CORE | 2;
    static final int K_CORE_3 = Op.K_CORE | 3;
    static final int K_CORE_4 = Op.K_CORE | 4;
    static final int K_CORE_5 = Op.K_CORE | 5;
    static final int K_CORE_6 = Op.K_CORE | 6;
    static final int K_DELTA_1 = Op.K_DELTA | 1;
    static final int K_DELTA_2 = Op.K_DELTA | 2;
    static final int K_DELTA_3 = Op.K_DELTA | 3;
    static final int K_IOTA_1 = Op.K_IOTA | 1;
    static final int K_IOTA_2 = Op.K_IOTA | 2;
    static final int K_IOTA_3 = Op.K_IOTA | 3;
    static final int K_IOTA_4 = Op.K_IOTA | 4;
    static final int K_IOTA_5 = Op.K_IOTA | 5;
    static final int K_IOTA_6 = Op.K_IOTA | 6;
    static final int K_IOTA_7 = Op.K_IOTA | 7;
    static final int K_IOTA_8 = Op.K_IOTA | 8;
    static final int K_IOTA_9 = Op.K_IOTA | 9;
    static final int K_IOTA_10 = Op.K_IOTA | 10;
    static final int K_IOTA_11 = Op.K_IOTA | 11;
    static final int K_IOTA_12 = Op.K_IOTA | 12;
    static final int K_IOTA_13 = Op.K_IOTA | 13;
    static final int K_QUOTR_1 = Op.K_QUOTR | 1;
    static final int K_QUOTR_2 = Op.K_QUOTR | 2;

    private static final int W0_HCLASS_SHIFT = Layout.FLAG_SHIFT + Layout.HCLASS_SHIFT;
    /** Head classes whose applications CORE can reduce: REC (iota), QUOT, PROJ (ISA 4.3.3). */
    private static final int CORE_HEADS = 1 << Layout.HC_REC | 1 << Layout.HC_QUOT | 1 << Layout.HC_PROJ;
    private static int hclass(int w0) {
        return (w0 >>> W0_HCLASS_SHIFT) & 7;
    }

    /**
     * A memo hit of WHNF, CORE or DELTA when statistics or a debug mode are on (ISA 5.5 {@code memocheck}; request
     * of M6): the hit is counted and, under {@code memocheck}, re-computed with the memo bypassed and compared.
     * {@code mode} is the mode the memo of CORE was computed in. A cheap CORE memo is not re-computed: its value
     * may legally depend on which full results were known when it was made (a full result answers a cheap query,
     * as in the official {@code m_whnf_core}, R02 section 5), so a second computation can differ without an error.
     * A "does not unfold" answer of DELTA is not re-computed either (the audit would erase the marker).
     */
    private static int hitSlow(Machine m, int op, int v, int mode, int hit) {
        if (m.stats) {
            m.hits[op]++;
        }
        if (m.debug != 0 && !(op == Op.CORE && mode != Op.FULL) && !(op == Op.DELTA && hit == Layout.NONE)) {
            return Debug.memoCheck(m, op, v, mode, hit);
        }
        return hit;
    }

    /** A ref that is no value reached a reduction judgment: HOLE read without a variable, NONE, a reserved kind. */
    private static int badSlow(Machine m, int v) {
        return m.fault(v == Layout.HOLE ? MachineErrors.E_HOLE : MachineErrors.E_INTERNAL);
    }

    // =====================================================================================================
    // WHNF (ISA 4.3.4; official whnf, R02 7.6)
    // =====================================================================================================

    /**
     * WHNF(v) (ISA 4.3.4): flag / memo; loop: CORE full; NSUC folds to a literal exactly when the official whnf does
     * (RECONCILIATION section 2); NATFOLD before delta; case trees (step 4, SW_CASETREES); DELTA; one tick per
     * iteration. Official: {@code whnf} (R02 7.6). Entry frame: WHNF[0](v).
     * @return the result, or a signal ({@code >= Op.SIG}): SUSP, ABORT, FATAL
     */
    static int whnf(Machine m, int v, int d) {
        if (v < 0) {
            return v; // 1. inline answers: a small Nat literal
        }
        int o = v << Layout.REC_SHIFT;
        int w0 = m.vh[o];
        if ((w0 & Layout.W0_WHNF) != 0) {
            return v; // flag WHNF (never set on an application of one of the 14 binary Nat functions: M1, ISA 2.3)
        }
        int kind = w0 >>> Layout.KIND_SHIFT;
        if (kind >= Layout.CONST) {
            int w = m.vh[o + Layout.M_WHNF]; // CONST, NEU, PROJ, NSUC: memo m0
            if (w != 0) {
                return m.debug != 0 || m.stats ? hitSlow(m, Op.WHNF, v, Op.FULL, w) : w;
            }
            if (kind > Layout.NSUC) {
                return badSlow(m, v);
            }
        } else if (kind != Layout.SUSP) {
            return kind == Layout.K_SLOT ? badSlow(m, v) : v; // SORT, literals, closures, FVAR: normal by kind
        }
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig; // 2. TICK
        }
        if (m.stats) {
            m.call(Op.WHNF);
        }
        if (d > m.hostDepth) {
            return m.spill1(Op.K_WHNF, v); // 3. too deep: the frame WHNF[0](v)
        }
        if (kind == Layout.SUSP) {
            int f = Eval.force(m, v, d + 1); // call FORCE(v) => 1; the suspension itself has no WHNF memo (m0 is VAL)
            return f >= SIG ? m.sig0(f, K_WHNF_1) : whnf(m, f, d + 1);
        }
        return whnfLoop(m, v, v, 0, 0, 0, d);
    }

    /** Frame WHNF[1](): r = the value of the suspension; its WHNF is the result (tail). */
    static int k_whnf1(Machine m, int r, int d) {
        return whnf(m, r, d + 1);
    }

    /**
     * The loop of the official {@code whnf} (R02 7.6) from {@code cur} on: {@code t1 = whnf_core(cur)};
     * {@code reduce_nat(t1)} decides; else one delta step and again. {@code t1 != 0}: CORE of this iteration
     * is already done (a resumed iteration). {@code p0}, {@code p1}: the CORE results of the two iterations
     * before, which receive the memo with {@code v} and the last {@code t1} (ISA 4.3.4 "done"; 0 = none).
     * One tick per iteration; the first one is the judgment's own.
     */
    private static int whnfLoop(Machine m, int v, int cur, int p0, int p1, int t1, int d) {
        for (;;) {
            if (t1 == 0) {
                t1 = core(m, cur, Op.FULL, d + 1); // call CORE(cur, full) => 2
                if (t1 >= SIG) {
                    return m.sig3(t1, K_WHNF_2, v, p0, p1);
                }
            }
            int c = whnfClass(m, t1);
            if (c == C_NORMAL) {
                return whnfDone(m, v, p0, p1, t1, t1);
            }
            if (c == C_NSUC) {
                return whnfSucc(m, v, p0, p1, t1, d);
            }
            if (c == C_NATOP) {
                int r = NatRed.natFold(m, t1, d + 1); // call NATFOLD(t1) => 4: before delta (R02 7.6)
                if (r >= SIG) {
                    return m.sig4(r, K_WHNF_4, v, p0, p1, t1);
                }
                if (r != Layout.NONE) {
                    return whnfDone(m, v, p0, p1, t1, r);
                }
            }
            int t2 = Rules.unfold(m, t1, d + 1); // call DELTA(t1) => 5 (a case tree first when SW_CASETREES is on)
            if (t2 >= SIG) {
                return m.sig4(t2, K_WHNF_5, v, p0, p1, t1);
            }
            if (t2 == Layout.NONE) {
                return whnfDone(m, v, p0, p1, t1, t1);
            }
            if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
                return m.sig; // one tick per iteration
            }
            p1 = p0;
            p0 = t1;
            cur = t2;
            t1 = 0;
        }
    }

    private static final int C_NORMAL = 0;
    private static final int C_NSUC = 1;
    private static final int C_NATOP = 2;
    private static final int C_UNFOLD = 3;

    /**
     * What the WHNF loop does with a CORE result: nothing (a weak head normal form), the unary case of
     * {@code reduce_nat} (NSUC), the binary case (a NEU of two arguments whose head is one of the 14 functions),
     * or a delta step (head class DEFN).
     */
    private static int whnfClass(Machine m, int t) {
        if (t < 0) {
            return C_NORMAL;
        }
        int[] vh = m.vh;
        int o = t << Layout.REC_SHIFT;
        int w0 = vh[o];
        if ((w0 & Layout.W0_WHNF) != 0) {
            return C_NORMAL;
        }
        int kind = w0 >>> Layout.KIND_SHIFT;
        if (kind == Layout.NEU) {
            if ((w0 & Layout.AUX_MASK) == 2 && NatRed.binaryOp(m, vh[o + Layout.A]) != 0) {
                return C_NATOP;
            }
            return hclass(w0) == Layout.HC_DEFN ? C_UNFOLD : C_NORMAL;
        }
        if (kind == Layout.CONST) {
            return hclass(w0) == Layout.HC_DEFN ? C_UNFOLD : C_NORMAL;
        }
        return kind == Layout.NSUC ? C_NSUC : C_NORMAL;
    }

    /** Frame WHNF[2](v, p0, p1): r = CORE(cur, full); the iteration continues with it. */
    static int k_whnf2(Machine m, int v, int p0, int p1, int r, int d) {
        return whnfLoop(m, v, 0, p0, p1, r, d);
    }

    /**
     * {@code t1 = NSUC(b, k)}: the unary case of {@code reduce_nat} (R02 7.6 row {@code Nat.succ a}): WHNF of the
     * base; a literal or {@code Nat.zero}: the literal n + k; otherwise the ORIGINAL cell (the base is not replaced).
     */
    private static int whnfSucc(Machine m, int v, int p0, int p1, int t1, int d) {
        int w = whnf(m, m.vh[(t1 << Layout.REC_SHIFT) + Layout.A], d + 1); // call WHNF(base) => 3
        return w >= SIG ? m.sig4(w, K_WHNF_3, v, p0, p1, t1) : k_whnf3(m, v, p0, p1, t1, w, d);
    }

    /** Frame WHNF[3](v, p0, p1, t1): r = WHNF(base of the NSUC t1). */
    static int k_whnf3(Machine m, int v, int p0, int p1, int t1, int r, int d) {
        int w = NatRed.succFold(m, t1, r);
        return w >= SIG ? w : whnfDone(m, v, p0, p1, t1, w);
    }

    /** Frame WHNF[4](v, p0, p1, t1): r = NATFOLD(t1); NONE: the iteration continues with the delta step. */
    static int k_whnf4(Machine m, int v, int p0, int p1, int t1, int r, int d) {
        return r != Layout.NONE ? whnfDone(m, v, p0, p1, t1, r) : whnfUnfold(m, v, p0, p1, t1, d);
    }

    /** The delta step of a resumed iteration (the loop has the same step inline). */
    private static int whnfUnfold(Machine m, int v, int p0, int p1, int t1, int d) {
        int t2 = Rules.unfold(m, t1, d + 1); // call DELTA(t1) => 5
        return t2 >= SIG ? m.sig4(t2, K_WHNF_5, v, p0, p1, t1) : k_whnf5(m, v, p0, p1, t1, t2, d);
    }

    /** Frame WHNF[5](v, p0, p1, t1): r = DELTA(t1); NONE: t1 is the result; else the next iteration. */
    static int k_whnf5(Machine m, int v, int p0, int p1, int t1, int r, int d) {
        if (r == Layout.NONE) {
            return whnfDone(m, v, p0, p1, t1, t1);
        }
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig;
        }
        return whnfLoop(m, v, r, t1, p0, 0, d);
    }

    /**
     * The completing transition of WHNF (I-MEMO, ISA 5.4): m0 of {@code v}, of the last CORE result {@code t1} and
     * of the two CORE results before it becomes {@code w}. The official cache stores {@code m_whnf[e]} only; the
     * intermediate values have the same weak head normal form because the loop is deterministic from each of them.
     */
    private static int whnfDone(Machine m, int v, int p0, int p1, int t1, int w) {
        int[] vh = m.vh;
        vh[(v << Layout.REC_SHIFT) + Layout.M_WHNF] = w;
        if (t1 > Layout.HOLE && (vh[t1 << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT) >= Layout.CONST) {
            vh[(t1 << Layout.REC_SHIFT) + Layout.M_WHNF] = w;
        }
        if (p0 != 0) {
            vh[(p0 << Layout.REC_SHIFT) + Layout.M_WHNF] = w; // p0, p1 were unfolded: CONST or NEU records
            if (p1 != 0) {
                vh[(p1 << Layout.REC_SHIFT) + Layout.M_WHNF] = w;
            }
        }
        return w;
    }

    /**
     * The inline part of WHNF: an immediate or a record with flag WHNF: v; memo M_WHNF known: it; else {@code Op.MISS}.
     */
    static int whnfFast(Machine m, int v) {
        if (v < 0) {
            return v;
        }
        int o = v << Layout.REC_SHIFT;
        int w0 = m.vh[o];
        if ((w0 & Layout.W0_WHNF) != 0) {
            return v;
        }
        int kind = w0 >>> Layout.KIND_SHIFT;
        if (kind >= Layout.CONST) {
            int w = m.vh[o + Layout.M_WHNF];
            return w != 0 ? w : Op.MISS;
        }
        return kind == Layout.SUSP || kind == Layout.K_SLOT ? Op.MISS : v;
    }

    // =====================================================================================================
    // CORE (ISA 4.3.3; official whnf_core, R02 7.1-7.3)
    // =====================================================================================================

    /**
     * CORE(v, mode) (ISA 4.3.3), mode = {@code Op.FULL} or {@code Op.CHEAP}: flag WHNF or memo (COREFULL); SUSP: FORCE;
     * PROJ: reduce the struct (cheap: CORE, full: WHNF), stuck: the ORIGINAL cell; REC head: IOTA; QUOT head: QUOTR; PROJ
     * head: CORE of the head, then APPLY. No delta. Official: {@code whnf_core} with the flags of R02 7.3.
     * Entry frame: CORE[0](v, mode).
     * @return the result, or a signal ({@code >= Op.SIG}): SUSP, ABORT, FATAL
     */
    static int core(Machine m, int v, int mode, int d) {
        if (v < 0) {
            return v; // 1. inline answers
        }
        int o = v << Layout.REC_SHIFT;
        int w0 = m.vh[o];
        if ((w0 & Layout.W0_WHNF) != 0) {
            return v;
        }
        int kind = w0 >>> Layout.KIND_SHIFT;
        if (kind == Layout.NEU || kind == Layout.PROJ) {
            if (kind == Layout.NEU && (CORE_HEADS >>> hclass(w0) & 1) == 0) {
                return v; // a definition head: no delta here; a constructor, axiom, variable head: stuck
            }
            int c = m.vh[o + Layout.M_CORE];
            if (c != 0 && (mode != Op.FULL || (w0 & Layout.W0_COREFULL) != 0)) {
                // memo m1: a full result answers both modes, a cheap one only cheap queries
                return m.debug != 0 || m.stats ? hitSlow(m, Op.CORE, v, (w0 & Layout.W0_COREFULL) != 0 ? Op.FULL : Op.CHEAP, c) : c;
            }
        } else if (kind != Layout.SUSP) {
            return kind == Layout.K_SLOT || kind > Layout.NSUC ? badSlow(m, v) : v; // CONST, NSUC, SORT, ...
        }
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig; // 2. TICK
        }
        if (m.stats) {
            m.call(Op.CORE);
        }
        if (d > m.hostDepth) {
            return m.spill2(Op.K_CORE, v, mode); // 3. too deep
        }
        if (kind == Layout.SUSP) {
            int f = Eval.force(m, v, d + 1); // call FORCE(v) => 1
            return f >= SIG ? m.sig1(f, K_CORE_1, mode) : core(m, f, mode, d + 1);
        }
        return coreLoop(m, v, mode, v, d);
    }

    /** Frame CORE[1](mode): r = the value of the suspension (tail). */
    static int k_core1(Machine m, int mode, int r, int d) {
        return core(m, r, mode, d + 1);
    }

    /**
     * The chain of head reductions of {@code whnf_core} as a loop (the official function calls itself in tail
     * position): {@code cur} is reduced one step at a time until a step answers NONE. One tick per further step.
     */
    private static int coreLoop(Machine m, int v, int mode, int cur, int d) {
        for (;;) {
            int r = coreStep(m, cur, mode, d);
            if (r >= SIG) {
                return m.sig3(r, K_CORE_6, v, mode, cur);
            }
            if (r == Layout.NONE) {
                return coreDone(m, v, mode, cur);
            }
            if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
                return m.sig;
            }
            cur = r;
        }
    }

    /** Frame CORE[6](v, mode, cur): r = the reduct of cur, or NONE (cur is the result). */
    static int k_core6(Machine m, int v, int mode, int cur, int r, int d) {
        if (r == Layout.NONE) {
            return coreDone(m, v, mode, cur);
        }
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig;
        }
        return coreLoop(m, v, mode, r, d);
    }

    /**
     * One step of {@code whnf_core} on {@code cur} (R02 7.1): the reduct, or NONE if {@code cur} is in core normal
     * form (also: stuck). A memoised result of {@code cur} is a legal reduct. Its sub-judgments are called in tail
     * position or through the continuation steps CORE[2..5], whose result is this step's result.
     */
    private static int coreStep(Machine m, int cur, int mode, int d) {
        if (cur <= Layout.HOLE) {
            return cur < 0 ? Layout.NONE : badSlow(m, cur);
        }
        int o = cur << Layout.REC_SHIFT;
        int w0 = m.vh[o];
        if ((w0 & Layout.W0_WHNF) != 0) {
            return Layout.NONE;
        }
        int kind = w0 >>> Layout.KIND_SHIFT;
        if (kind == Layout.SUSP) {
            return Eval.force(m, cur, d + 1); // zeta / a delayed argument: the value is the reduct (tail)
        }
        if (kind != Layout.NEU && kind != Layout.PROJ) {
            return Layout.NONE;
        }
        int c = m.vh[o + Layout.M_CORE];
        if (c != 0 && (mode != Op.FULL || (w0 & Layout.W0_COREFULL) != 0)) {
            return c == cur ? Layout.NONE : c;
        }
        if (kind == Layout.PROJ) {
            return coreProj(m, cur, mode, d);
        }
        switch (hclass(w0)) {
            case Layout.HC_REC:
                return iota(m, cur, d + 1); // reduce_recursor (tail): NONE = stuck
            case Layout.HC_QUOT:
                return quotr(m, cur, d + 1);
            case Layout.HC_PROJ:
                return coreHead(m, cur, mode, d);
            default:
                return Layout.NONE; // a definition head: no delta in whnf_core
        }
    }

    /**
     * The completing transition of CORE: m1 of {@code v} (and of the result, which is its own normal form) is
     * written; COREFULL is set by a full computation; a cheap result never replaces a full one (ISA 4.3.3).
     */
    private static int coreDone(Machine m, int v, int mode, int res) {
        coreMemo(m.vh, v, mode, res);
        if (res != v && res > Layout.HOLE) {
            int[] vh = m.vh;
            int w0 = vh[res << Layout.REC_SHIFT];
            int kind = w0 >>> Layout.KIND_SHIFT;
            if ((kind == Layout.NEU || kind == Layout.PROJ) && (w0 & Layout.W0_WHNF) == 0) {
                coreMemo(vh, res, mode, res);
            }
        }
        return res;
    }

    private static void coreMemo(int[] vh, int x, int mode, int res) {
        int o = x << Layout.REC_SHIFT;
        int w0 = vh[o];
        if (mode == Op.FULL) {
            vh[o + Layout.M_CORE] = res;
            vh[o] = w0 | Layout.W0_COREFULL;
        } else if ((w0 & Layout.W0_COREFULL) == 0) {
            vh[o + Layout.M_CORE] = res;
        }
    }

    /**
     * {@code reduce_proj} (R02 7.2) on the PROJ record {@code p}: the struct is normalised with CORE in cheap mode
     * and with WHNF in full mode; then {@code reduce_proj_core}. NONE: stuck, the caller keeps the ORIGINAL cell.
     */
    private static int coreProj(Machine m, int p, int mode, int d) {
        int s = m.vh[(p << Layout.REC_SHIFT) + Layout.A];
        int sr = mode == Op.FULL ? whnf(m, s, d + 1) : core(m, s, Op.CHEAP, d + 1); // call WHNF / CORE(struct) => 2
        return sr >= SIG ? m.sig1(sr, K_CORE_2, p) : k_core2(m, p, sr, d);
    }

    /** Frame CORE[2](p): r = the normalised struct of the projection p. A string literal is viewed first (R02 7.2). */
    static int k_core2(Machine m, int p, int r, int d) {
        if (r > Layout.HOLE && (m.vh[r << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT) == Layout.STR) {
            int sv = StrRed.strView(m, r);
            if (sv >= SIG) {
                return sv;
            }
            int w = whnf(m, sv, d + 1); // call WHNF(STRVIEW(struct)) => 3 (full whnf in both modes)
            return w >= SIG ? m.sig1(w, K_CORE_3, p) : projField(m, p, w);
        }
        return projField(m, p, r);
    }

    /** Frame CORE[3](p): r = WHNF of the constructor form of a string literal struct. */
    static int k_core3(Machine m, int p, int r, int d) {
        return projField(m, p, r);
    }

    /**
     * {@code reduce_proj_core} (R02 7.2, section 10 rows 7, 9): field {@code i} of {@code s} if {@code s} is an
     * application of a constructor of structure S with more than {@code nparams + i} arguments, else NONE. A
     * constructor of another inductive type is refused. An NSUC is the application {@code Nat.succ x}.
     */
    static int projField(Machine m, int p, int s) {
        if (s <= Layout.HOLE) {
            return s < 0 ? Layout.NONE : badSlow(m, s); // a Nat literal has no constant head
        }
        int[] vh = m.vh;
        int po = p << Layout.REC_SHIFT;
        int row = vh[po + Layout.B];
        int i = vh[po + Layout.C];
        int o = s << Layout.REC_SHIFT;
        int w0 = vh[o];
        int kind = w0 >>> Layout.KIND_SHIFT;
        if (kind == Layout.NEU) {
            if (hclass(w0) != Layout.HC_CTOR || i < 0) {
                return Layout.NONE;
            }
            DeclTable decls = m.decls;
            int ctor = vh[(vh[o + Layout.A] << Layout.REC_SHIFT) + Layout.A];
            if (decls.ctorInduct(ctor) != row) {
                return Layout.NONE;
            }
            int idx = decls.ctorNumParams(ctor) + i;
            return idx >= 0 && idx < (w0 & Layout.AUX_MASK) ? Runs.arg(m, vh[o + Layout.B], idx) : Layout.NONE;
        }
        if (kind == Layout.NSUC && i == 0) {
            int succ = NatRed.succCtor(m);
            return succ > 0 && succ < SIG && m.decls.ctorInduct(succ) == row && m.decls.ctorNumParams(succ) == 0
                    ? NatRed.unsucc(m, s, 1) : Layout.NONE;
        }
        return Layout.NONE;
    }

    /**
     * A NEU whose head is a PROJ record (R02 7.1, App case): {@code f = whnf_core(head)}; unchanged: stuck (NONE,
     * the ORIGINAL cell); else the arguments are applied to it (beta if it is a closure) and reduction goes on.
     */
    private static int coreHead(Machine m, int cur, int mode, int d) {
        int h = core(m, m.vh[(cur << Layout.REC_SHIFT) + Layout.A], mode, d + 1); // call CORE(head, mode) => 4
        return h >= SIG ? m.sig1(h, K_CORE_4, cur) : k_core4(m, cur, h, d);
    }

    /** Frame CORE[4](cur): r = CORE(head of cur). */
    static int k_core4(Machine m, int cur, int r, int d) {
        if (r == m.vh[(cur << Layout.REC_SHIFT) + Layout.A]) {
            return Layout.NONE;
        }
        int xb = pushSpine(m, cur);
        if (xb < 0) {
            return m.fault(MachineErrors.E_MEM);
        }
        int a = Eval.apply(m, r, xb, m.xp - xb, d + 1); // call APPLY(head', args) => 5
        return a >= SIG ? m.sig1(a, K_CORE_5, xb) : k_core5(m, xb, a, d);
    }

    /** Frame CORE[5](xb): r = APPLY(reduced head, the arguments at X[xb ..]); the scratch is released. */
    static int k_core5(Machine m, int xb, int r, int d) {
        m.xp = xb;
        return r;
    }

    /**
     * The inline part of CORE: immediate or flag WHNF: v; M_CORE known and (COREFULL or cheap): it; else {@code Op.MISS}.
     */
    static int coreFast(Machine m, int v, int mode) {
        if (v < 0) {
            return v;
        }
        int o = v << Layout.REC_SHIFT;
        int w0 = m.vh[o];
        if ((w0 & Layout.W0_WHNF) != 0) {
            return v;
        }
        int kind = w0 >>> Layout.KIND_SHIFT;
        if (kind == Layout.NEU || kind == Layout.PROJ) {
            if (kind == Layout.NEU && (CORE_HEADS >>> hclass(w0) & 1) == 0) {
                return v;
            }
            int c = m.vh[o + Layout.M_CORE];
            return c != 0 && (mode != Op.FULL || (w0 & Layout.W0_COREFULL) != 0) ? c : Op.MISS;
        }
        return kind == Layout.SUSP || kind == Layout.K_SLOT || kind > Layout.NSUC ? Op.MISS : v;
    }

    // =====================================================================================================
    // DELTA (ISA 4.3.2; official is_delta, unfold_definition, R02 7.6)
    // =====================================================================================================

    /**
     * DELTA(v) (ISA 4.3.2): memo UNF; the head is not a definition, or the level count is wrong: NONE; else the value
     * of the constant at its levels applied to the arguments. Official: {@code is_delta}, {@code unfold_definition}
     * (R02 7.6, section 10 row 14). Entry frame: DELTA[0](v).
     * @return the unfolded value, {@code Layout.NONE} if the head does not unfold, or a signal
     */
    static int delta(Machine m, int v, int d) {
        if (v <= Layout.HOLE) {
            return Layout.NONE; // 1. inline answers
        }
        int o = v << Layout.REC_SHIFT;
        int[] vh = m.vh;
        int w0 = vh[o];
        int kind = w0 >>> Layout.KIND_SHIFT;
        if ((kind != Layout.NEU && kind != Layout.CONST) || hclass(w0) != Layout.HC_DEFN) {
            return Layout.NONE; // only definitions and theorems unfold (opaque constants are class AXIOM)
        }
        int u = vh[o + Layout.M_UNF];
        if (u != 0) {
            u = u == v ? Layout.NONE : u; // memo m3: self = does not unfold
            return m.debug != 0 || m.stats ? hitSlow(m, Op.DELTA, v, Op.FULL, u) : u;
        }
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig; // 2. TICK
        }
        if (m.stats) {
            m.call(Op.DELTA);
        }
        if (d > m.hostDepth) {
            return m.spill1(Op.K_DELTA, v); // 3. too deep
        }
        int head = kind == Layout.NEU ? vh[o + Layout.A] : v;
        if (kind == Layout.NEU && (m.sw & Op.SW_CODE) != 0) {
            int r = deltaBlock(m, v, head, d); // K1 (staging step 4): BODY(row) on the spine
            if (r != Op.MISS) {
                return r;
            }
        }
        int bv = vh[(head << Layout.REC_SHIFT) + Layout.M_UNF];
        return bv != 0 ? deltaApply(m, v, head, bv, d) : deltaHead(m, v, head, d);
    }

    /**
     * DELTA through the elaborated body (ISA 4.3.2 "n >= m and BODY(row) elaborated"; staging step 4, behind
     * {@code SW_CODE}, never in strict mode): the block reads its arity arguments from the spine run, the rest is
     * applied to its result. {@code Op.MISS}: no block for this head (not elaborated yet, a proof-block value, a
     * spine shorter than the arity, a head that does not unfold): the generic path decides. The value ids are
     * those of the generic walk (ISA 0.2 decision 6; M7's tier-equivalence test).
     */
    private static int deltaBlock(Machine m, int v, int head, int d) {
        int ls = Levels.substOf(m, head);
        if (ls < 0 || m.vh[(head << Layout.REC_SHIFT) + Layout.M_UNF] == head) {
            return Op.MISS;
        }
        int b = Code.blockFor(m, m.vh[(head << Layout.REC_SHIFT) + Layout.A], Op.ROLE_BODY);
        if (b == 0 || b >= SIG) {
            return b == 0 ? Op.MISS : b;
        }
        int k = Code.arityOf(m, b);
        int vo = v << Layout.REC_SHIFT;
        if ((m.vh[vo] & Layout.AUX_MASK) < k) {
            return Op.MISS;
        }
        int r = Code.callRun(m, b, ls, m.vh[vo + Layout.B], d + 1); // call BODY(row)(spine, ls) => 3
        return r >= SIG ? m.sig2(r, K_DELTA_3, v, k) : k_delta3(m, v, k, r, d);
    }

    /** Frame DELTA[3](v, k): r = the result of BODY(row) on the first k arguments; the others are applied to it. */
    static int k_delta3(Machine m, int v, int k, int r, int d) {
        int vo = v << Layout.REC_SHIFT;
        int n = (m.vh[vo] & Layout.AUX_MASK) - k;
        if (n == 0) {
            m.vh[vo + Layout.M_UNF] = r;
            return r;
        }
        if (!m.ensureScratch(n)) {
            return m.fault(MachineErrors.E_MEM);
        }
        int xb = m.xp;
        copyArgs(m, v, k, n, xb);
        m.xp = xb + n;
        int a = Eval.apply(m, r, xb, n, d + 1); // call APPLY(result, args[k ..]) => 2
        return a >= SIG ? m.sig2(a, K_DELTA_2, v, xb) : k_delta2(m, v, xb, a, d);
    }

    /**
     * The value of the head constant at its levels (UNF of the bare CONST record, once per session): the value
     * term, decoded from its proof block if it is one (ISA 12 R4), evaluated under the substitution of the record.
     * A head that is no definition or theorem, or has the wrong number of levels, does not unfold (R02 section 10
     * row 14): UNF = self.
     */
    private static int deltaHead(Machine m, int v, int head, int d) {
        int ho = head << Layout.REC_SHIFT;
        int row = m.vh[ho + Layout.A];
        DeclTable decls = m.decls;
        int k = decls.kind(row);
        int ls = Levels.substOf(m, head);
        if ((k != DeclKind.DEFN && k != DeclKind.THM) || ls < 0
                || m.lists.length(m.vh[ho + Layout.B]) != decls.numLevelParams(row)) {
            m.vh[ho + Layout.M_UNF] = head;
            m.vh[(v << Layout.REC_SHIFT) + Layout.M_UNF] = v;
            return Layout.NONE;
        }
        int t = decls.value(row);
        if (t < 0) {
            t = m.ctx.overlay.blockRoot(-t - 1); // a proof block: decoded into the worker's overlay, once
            m.terms.refresh(); // the overlay array may have been reallocated (ISA 12 R4)
        }
        if (t <= 0) {
            return m.fault(MachineErrors.E_INTERNAL); // a definition without a value never passes the loader
        }
        int bv = Eval.eval(m, t, m.ep, 0, ls, d + 1); // call EVAL(value term, empty, ls) => 1
        return bv >= SIG ? m.sig2(bv, K_DELTA_1, v, head) : k_delta1(m, v, head, bv, d);
    }

    /** Frame DELTA[1](v, head): r = the evaluated value of the head constant at its levels. */
    static int k_delta1(Machine m, int v, int head, int r, int d) {
        m.vh[(head << Layout.REC_SHIFT) + Layout.M_UNF] = r;
        return deltaApply(m, v, head, r, d);
    }

    /** {@code unfold_definition}: the value of the head re-applied to the arguments (beta is APPLY's). */
    private static int deltaApply(Machine m, int v, int head, int bv, int d) {
        if (bv == head) {
            m.vh[(v << Layout.REC_SHIFT) + Layout.M_UNF] = v;
            return Layout.NONE;
        }
        if (v == head) {
            return bv;
        }
        int xb = pushSpine(m, v);
        if (xb < 0) {
            return m.fault(MachineErrors.E_MEM);
        }
        int r = Eval.apply(m, bv, xb, m.xp - xb, d + 1); // call APPLY(value, args) => 2
        return r >= SIG ? m.sig2(r, K_DELTA_2, v, xb) : k_delta2(m, v, xb, r, d);
    }

    /** Frame DELTA[2](v, xb): r = the unfolded application; the scratch is released; memo UNF (I-MEMO). */
    static int k_delta2(Machine m, int v, int xb, int r, int d) {
        m.xp = xb;
        m.vh[(v << Layout.REC_SHIFT) + Layout.M_UNF] = r;
        return r;
    }

    /**
     * The inline part of DELTA: UNF known: NONE if it is v itself, else UNF; unknown: {@code Op.MISS}.
     */
    static int deltaFast(Machine m, int v) {
        if (v <= Layout.HOLE) {
            return Layout.NONE;
        }
        int o = v << Layout.REC_SHIFT;
        int w0 = m.vh[o];
        int kind = w0 >>> Layout.KIND_SHIFT;
        if ((kind != Layout.NEU && kind != Layout.CONST) || hclass(w0) != Layout.HC_DEFN) {
            return Layout.NONE;
        }
        int u = m.vh[o + Layout.M_UNF];
        return u == 0 ? Op.MISS : u == v ? Layout.NONE : u;
    }

    /**
     * Copies the arguments of NEU {@code v} to the top of scratch.
     * @return their base {@code xb} ({@code m.xp} is advanced past them), or -1 if scratch is full
     */
    static int pushSpine(Machine m, int v) {
        int o = v << Layout.REC_SHIFT;
        int n = m.vh[o] & Layout.AUX_MASK;
        if (!m.ensureScratch(n)) {
            return -1;
        }
        int xb = m.xp;
        copyArgs(m, v, 0, n, xb);
        m.xp = xb + n;
        return xb;
    }

    /** Copies arguments {@code [from, from + n)} of NEU {@code v} to {@code X[at ..]} (room was ensured). */
    static void copyArgs(Machine m, int v, int from, int n, int at) {
        int[] rs = m.rs;
        int[] x = m.X;
        int run = m.vh[(v << Layout.REC_SHIFT) + Layout.B];
        int sb = run + Layout.RUN_H + (rs[run] & Layout.RUN_FAR) + from;
        for (int i = 0; i < n; i++) {
            x[at + i] = rs[sb + i];
        }
    }

    // =====================================================================================================
    // IOTA (ISA 4.3.5; official inductive_reduce_rec, to_cnstr_when_K, to_cnstr_when_structure, R02 7.4)
    // =====================================================================================================

    /**
     * IOTA(v) (ISA 4.3.5): v is a NEU with a REC head. K (to_cnstr_when_K, before any reduction), WHNF of the major,
     * literal views (Nat literal, NSUC, STR), structure eta (to_cnstr_when_structure), rule selection by constructor,
     * "the LAST nfields arguments". Official: {@code inductive_reduce_rec} (R02 7.4). Entry frame: IOTA[0](v).
     * @return the reduct, {@code Layout.NONE} if no rule applies, or a signal
     */
    static int iota(Machine m, int v, int d) {
        if (v <= Layout.HOLE) {
            return Layout.NONE; // 1. inline answers
        }
        int o = v << Layout.REC_SHIFT;
        int[] vh = m.vh;
        int w0 = vh[o];
        if ((w0 >>> Layout.KIND_SHIFT) != Layout.NEU || hclass(w0) != Layout.HC_REC) {
            return Layout.NONE;
        }
        int head = vh[o + Layout.A];
        int rec = vh[(head << Layout.REC_SHIFT) + Layout.A];
        DeclTable decls = m.decls;
        int mi = decls.recMajorIndex(rec);
        if (mi >= (w0 & Layout.AUX_MASK)) {
            return Layout.NONE; // the major premise is not among the arguments
        }
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig; // 2. TICK
        }
        if (m.stats) {
            m.call(Op.IOTA);
        }
        if (d > m.hostDepth) {
            return m.spill1(Op.K_IOTA, v); // 3. too deep
        }
        if (Levels.substOf(m, head) < 0 || m.lists.length(vh[(head << Layout.REC_SHIFT) + Layout.B]) != decls.numLevelParams(rec)) {
            return Layout.NONE; // wrong number of levels: "no reduction" (R02 section 10 row 14)
        }
        int major = Runs.arg(m, vh[o + Layout.B], mi);
        if ((decls.recFlags(rec) & DeclTable.RF_K) != 0) {
            int tv = Infer.vtype(m, major, d + 1); // K: call VTYPE(major) => 1, BEFORE any reduction of the major
            return tv >= SIG ? m.sig2(tv, K_IOTA_1, v, major) : k_iota1(m, v, major, tv, d);
        }
        return iotaMajor(m, v, major, d);
    }

    /** The recursor row of the NEU {@code v} whose head is a recursor constant. */
    private static int recRow(Machine m, int v) {
        int[] vh = m.vh;
        return vh[(vh[(v << Layout.REC_SHIFT) + Layout.A] << Layout.REC_SHIFT) + Layout.A];
    }

    /** Frame IOTA[1](v, major): r = VTYPE(major) (to_cnstr_when_K: {@code T = whnf(infer_type(e))}). */
    static int k_iota1(Machine m, int v, int major, int r, int d) {
        int ty = whnf(m, r, d + 1); // call WHNF(type of the major) => 2
        return ty >= SIG ? m.sig2(ty, K_IOTA_2, v, major) : k_iota2(m, v, major, ty, d);
    }

    /** Frame IOTA[2](v, major): r = the weak head normal form of the major's type. */
    static int k_iota2(Machine m, int v, int major, int r, int d) {
        int c = nullaryCtor(m, v, r);
        if (c >= SIG) {
            return c;
        }
        if (c == Layout.NONE) {
            return iotaMajor(m, v, major, d); // the type's head is not the major inductive: the major stays
        }
        int tc = Infer.vtype(m, c, d + 1); // call VTYPE(constructor application) => 3
        return tc >= SIG ? m.sig4(tc, K_IOTA_3, v, major, r, c) : k_iota3(m, v, major, r, c, tc, d);
    }

    /**
     * {@code mk_nullary_cnstr} of to_cnstr_when_K (R02 7.4): the first constructor of the major inductive at the
     * levels of the type's head, applied to the first nparams arguments of the type; NONE if the head of the type
     * is not that inductive.
     */
    private static int nullaryCtor(Machine m, int v, int ty) {
        if (ty <= Layout.HOLE) {
            return Layout.NONE;
        }
        int[] vh = m.vh;
        int o = ty << Layout.REC_SHIFT;
        int w0 = vh[o];
        int kind = w0 >>> Layout.KIND_SHIFT;
        int th = kind == Layout.NEU ? vh[o + Layout.A] : ty;
        int n = kind == Layout.NEU ? w0 & Layout.AUX_MASK : 0;
        if ((kind != Layout.NEU && kind != Layout.CONST) || (vh[th << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT) != Layout.CONST) {
            return Layout.NONE;
        }
        int rec = recRow(m, v);
        DeclTable decls = m.decls;
        int ind = vh[(th << Layout.REC_SHIFT) + Layout.A];
        if (ind != decls.recMajorInduct(rec) || decls.kind(ind) != DeclKind.INDUCT || decls.inductCtorCount(ind) == 0) {
            return Layout.NONE;
        }
        int c = Values.mkConst(m, decls.inductCtor(ind, 0), vh[(th << Layout.REC_SHIFT) + Layout.B]);
        int np = Math.min(decls.recNumParams(rec), n);
        if (c >= SIG || np == 0) {
            return c;
        }
        if (!m.ensureScratch(np)) {
            return m.fault(MachineErrors.E_MEM);
        }
        int xb = m.xp;
        copyArgs(m, ty, 0, np, xb);
        m.xp = xb + np;
        c = Values.mkApp(m, c, xb, np);
        m.xp = xb;
        return c;
    }

    /** Frame IOTA[3](v, major, ty, c): r = VTYPE(c); the indices must match definitionally. */
    static int k_iota3(Machine m, int v, int major, int ty, int c, int r, int d) {
        int e = Conv.conv(m, ty, r, d + 1); // call CONV(T, type of c) => 4
        return e >= SIG ? m.sig3(e, K_IOTA_4, v, major, c) : k_iota4(m, v, major, c, e, d);
    }

    /** Frame IOTA[4](v, major, c): r = CONV(T, type of c); T: the major is replaced by the constructor application. */
    static int k_iota4(Machine m, int v, int major, int c, int r, int d) {
        return iotaMajor(m, v, r == Layout.T ? c : major, d);
    }

    /** {@code major = whnf(major)} (R02 7.4), then the views. */
    private static int iotaMajor(Machine m, int v, int major, int d) {
        int w = whnf(m, major, d + 1); // call WHNF(major) => 5
        return w >= SIG ? m.sig1(w, K_IOTA_5, v) : k_iota5(m, v, w, d);
    }

    /**
     * Frame IOTA[5](v): r = the weak head normal form of the major premise. A Nat literal is viewed as one
     * constructor layer without building it (ISA 4.3.5); a string literal through its constructor form; a
     * constructor application as it is; anything else may be eta-expanded if the major inductive is a structure.
     */
    static int k_iota5(Machine m, int v, int r, int d) {
        return iotaView(m, v, r, d);
    }

    /** The constructor view of the normalised major premise {@code r} and the rule it selects (see {@link #k_iota5}). */
    private static int iotaView(Machine m, int v, int r, int d) {
        if (r < 0) {
            return iotaRule(m, v, r, NatRed.litCtor(m, r), d);
        }
        int w0 = m.vh[r << Layout.REC_SHIFT];
        switch (w0 >>> Layout.KIND_SHIFT) {
            case Layout.NBIG:
                return iotaRule(m, v, r, NatRed.litCtor(m, r), d);
            case Layout.NSUC:
                return iotaRule(m, v, r, NatRed.succCtor(m), d);
            case Layout.STR:
                return iotaStr(m, v, r, d);
            case Layout.CONST:
            case Layout.NEU:
                if (hclass(w0) == Layout.HC_CTOR) {
                    return iotaRule(m, v, r, ctorOf(m, r, w0), d);
                }
                return iotaEta(m, v, r, d);
            case Layout.FVAR:
            case Layout.PROJ:
                return iotaEta(m, v, r, d);
            default:
                return r <= Layout.HOLE ? badSlow(m, r) : Layout.NONE; // a sort, a closure: no constructor, no structure
        }
    }

    /** The constructor row of a CONST or NEU with head class CTOR. */
    private static int ctorOf(Machine m, int w, int w0) {
        int[] vh = m.vh;
        int o = w << Layout.REC_SHIFT;
        return (w0 >>> Layout.KIND_SHIFT) == Layout.NEU ? vh[(vh[o + Layout.A] << Layout.REC_SHIFT) + Layout.A] : vh[o + Layout.A];
    }

    /** A string literal major: {@code whnf(string_lit_to_constructor(major))} (R02 7.4). */
    private static int iotaStr(Machine m, int v, int s, int d) {
        int sv = StrRed.strView(m, s);
        if (sv >= SIG) {
            return sv;
        }
        int w = whnf(m, sv, d + 1); // call WHNF(STRVIEW(major)) => 6
        return w >= SIG ? m.sig1(w, K_IOTA_6, v) : k_iota6(m, v, w, d);
    }

    /** Frame IOTA[6](v): r = WHNF of the constructor form of the string literal; the rule is chosen by its head. */
    static int k_iota6(Machine m, int v, int r, int d) {
        if (r <= Layout.HOLE) {
            return Layout.NONE;
        }
        int w0 = m.vh[r << Layout.REC_SHIFT];
        int kind = w0 >>> Layout.KIND_SHIFT;
        if (kind == Layout.NSUC) {
            return iotaRule(m, v, r, NatRed.succCtor(m), d);
        }
        if ((kind == Layout.NEU || kind == Layout.CONST) && hclass(w0) == Layout.HC_CTOR) {
            return iotaRule(m, v, r, ctorOf(m, r, w0), d);
        }
        return Layout.NONE;
    }

    /** Number of arguments of the major premise in its constructor view. */
    private static int viewArgs(Machine m, int major) {
        if (major < 0) {
            return major == Layout.IMM ? 0 : 1; // literal 0 is Nat.zero; n + 1 is Nat.succ n
        }
        int w0 = m.vh[major << Layout.REC_SHIFT];
        int kind = w0 >>> Layout.KIND_SHIFT;
        return kind == Layout.NEU ? w0 & Layout.AUX_MASK : kind == Layout.CONST ? 0 : 1; // NBIG, NSUC: one field
    }

    /**
     * Rule selection (R02 7.4): the rule of the recursor for constructor {@code ctor} (a row, 0 = none, or a
     * signal), which must not have more fields than the major has arguments; then the rule's right-hand side at
     * the levels of the recursor constant.
     */
    private static int iotaRule(Machine m, int v, int major, int ctor, int d) {
        if (ctor >= SIG || ctor <= 0) {
            return ctor >= SIG ? ctor : Layout.NONE;
        }
        int head = m.vh[(v << Layout.REC_SHIFT) + Layout.A];
        int rec = m.vh[(head << Layout.REC_SHIFT) + Layout.A];
        DeclTable decls = m.decls;
        int rule = decls.recRuleFor(rec, ctor);
        if (rule < 0 || decls.recRuleFields(rec, rule) > viewArgs(m, major)) {
            return Layout.NONE;
        }
        if ((m.sw & Op.SW_CODE) != 0) {
            int b = ruleBlock(m, rec, rule);
            if (b != 0) {
                return b >= SIG ? b : iotaBlock(m, v, b, rule, pushRuleArgs(m, v, major, rule, false), d);
            }
        }
        int rhs = Eval.eval(m, decls.recRuleRhs(rec, rule), m.ep, 0, Levels.substOf(m, head), d + 1); // call EVAL(rhs) => 10
        return rhs >= SIG ? m.sig3(rhs, K_IOTA_10, v, major, rule) : k_iota10(m, v, major, rule, rhs, d);
    }

    /**
     * Frame IOTA[10](v, major, rule): r = the value of the rule's right-hand side. It is applied to the
     * parameters, motives and minor premises (the indices are dropped), the LAST nfields arguments of the major,
     * and the arguments after the major (R02 7.4, section 10 rows 13, 34) in one APPLY.
     */
    static int k_iota10(Machine m, int v, int major, int rule, int r, int d) {
        int xb = m.xp;
        int e = pushRuleArgs(m, v, major, rule, true);
        if (e >= SIG) {
            return e;
        }
        if (m.xp == xb) {
            return r;
        }
        int a = Eval.apply(m, r, xb, m.xp - xb, d + 1); // call APPLY(rhs, args) => 12
        return a >= SIG ? m.sig1(a, K_IOTA_12, xb) : k_iota12(m, xb, a, d);
    }

    /**
     * The block RULE(rec) if rule {@code rule} has code in it (ISA 4.3.5 "call RULE(rec, j)", K1; staging step 4,
     * {@code SW_CODE}); 0 = the generic path; or a signal.
     */
    private static int ruleBlock(Machine m, int rec, int rule) {
        int b = Code.blockFor(m, rec, Op.ROLE_RULE);
        return b == 0 || b >= SIG || Code.hasEntry(m, b, rule) ? b : 0;
    }

    /**
     * IOTA through the elaborated rule: {@code e} is the result of pushing its params + motives + minors + fields
     * arguments on scratch (0, or a signal); the arguments after the major are applied to the block's result.
     */
    private static int iotaBlock(Machine m, int v, int b, int rule, int e, int d) {
        if (e >= SIG) {
            return e;
        }
        int rec = recRow(m, v);
        DeclTable decls = m.decls;
        int n = decls.recNumParams(rec) + decls.recNumMotives(rec) + decls.recNumMinors(rec) + decls.recRuleFields(rec, rule);
        int xb = m.xp - n;
        int r = Code.callRule(m, b, rule, Levels.substOf(m, m.vh[(v << Layout.REC_SHIFT) + Layout.A]), xb, n, d + 1); // => 13
        return r >= SIG ? m.sig2(r, K_IOTA_13, v, xb) : k_iota13(m, v, xb, r, d);
    }

    /** Frame IOTA[13](v, xb): r = the result of RULE(rec, j); scratch is released; the arguments after the major follow. */
    static int k_iota13(Machine m, int v, int xb, int r, int d) {
        m.xp = xb;
        int n = m.vh[v << Layout.REC_SHIFT] & Layout.AUX_MASK;
        int rest = n - m.decls.recMajorIndex(recRow(m, v)) - 1;
        if (rest == 0) {
            return r;
        }
        if (!m.ensureScratch(rest)) {
            return m.fault(MachineErrors.E_MEM);
        }
        copyArgs(m, v, n - rest, rest, xb);
        m.xp = xb + rest;
        int a = Eval.apply(m, r, xb, rest, d + 1); // call APPLY(result, args after the major) => 12
        return a >= SIG ? m.sig1(a, K_IOTA_12, xb) : k_iota12(m, xb, a, d);
    }

    /**
     * Pushes the arguments of the rule application on scratch: parameters, motives, minors, the fields of the
     * major and, if {@code withRest}, the arguments after the major. 0, or a signal.
     */
    private static int pushRuleArgs(Machine m, int v, int major, int rule, boolean withRest) {
        int rec = recRow(m, v);
        DeclTable decls = m.decls;
        int lead = decls.recNumParams(rec) + decls.recNumMotives(rec) + decls.recNumMinors(rec);
        int nf = decls.recRuleFields(rec, rule);
        int n = m.vh[v << Layout.REC_SHIFT] & Layout.AUX_MASK;
        int rest = withRest ? n - decls.recMajorIndex(rec) - 1 : 0;
        if (lead > n || !m.ensureScratch(lead + nf + rest)) {
            return m.fault(lead > n ? MachineErrors.E_INTERNAL : MachineErrors.E_MEM);
        }
        int xb = m.xp;
        copyArgs(m, v, 0, lead, xb);
        int e = nf == 0 ? 0 : pushFields(m, major, nf, xb + lead);
        if (e >= SIG) {
            return e;
        }
        copyArgs(m, v, n - rest, rest, xb + lead + nf);
        m.xp = xb + lead + nf + rest;
        return 0;
    }

    /** The last {@code nf >= 1} arguments of the major premise in its constructor view, to {@code X[at ..]}. */
    private static int pushFields(Machine m, int major, int nf, int at) {
        if (major < 0) {
            m.X[at] = major - 1; // the literal n + 1 is Nat.succ n: the predecessor immediate (nf <= 1 was checked)
            return 0;
        }
        int w0 = m.vh[major << Layout.REC_SHIFT];
        int kind = w0 >>> Layout.KIND_SHIFT;
        if (kind == Layout.NEU) {
            copyArgs(m, major, (w0 & Layout.AUX_MASK) - nf, nf, at);
            return 0;
        }
        int p = kind == Layout.NSUC ? NatRed.unsucc(m, major, 1) : NatRed.litPred(m, major);
        if (p < SIG) {
            m.X[at] = p;
        }
        return p >= SIG ? p : 0;
    }

    /** Frame IOTA[12](xb): r = the reduct; the scratch is released. */
    static int k_iota12(Machine m, int xb, int r, int d) {
        m.xp = xb;
        return r;
    }

    /** {@code is_non_rec_structure} (R02 7.4): an inductive type with one constructor, no indices, not recursive. */
    private static boolean isNonRecStructure(Machine m, int ind) {
        DeclTable decls = m.decls;
        return ind > 0 && decls.kind(ind) == DeclKind.INDUCT && decls.inductCtorCount(ind) == 1
                && decls.inductNumIndices(ind) == 0 && (decls.inductFlags(ind) & DeclTable.IF_REC) == 0;
    }

    /**
     * {@code to_cnstr_when_structure} (R02 7.4, section 10 row 12) for a major {@code w} that is no constructor
     * application: only if the major inductive is a non-recursive structure; the type of the major must be that
     * structure and must not be a proposition.
     */
    private static int iotaEta(Machine m, int v, int w, int d) {
        if (!isNonRecStructure(m, m.decls.recMajorInduct(recRow(m, v)))) {
            return Layout.NONE; // no constructor at the head: no rule
        }
        int tv = Infer.vtype(m, w, d + 1); // call VTYPE(major) => 7
        return tv >= SIG ? m.sig2(tv, K_IOTA_7, v, w) : k_iota7(m, v, w, tv, d);
    }

    /** Frame IOTA[7](v, w): r = VTYPE(w). */
    static int k_iota7(Machine m, int v, int w, int r, int d) {
        int ty = whnf(m, r, d + 1); // call WHNF(type of the major) => 8
        return ty >= SIG ? m.sig2(ty, K_IOTA_8, v, w) : k_iota8(m, v, w, ty, d);
    }

    /** Frame IOTA[8](v, w): r = the weak head normal form of the major's type; its head must be the structure. */
    static int k_iota8(Machine m, int v, int w, int r, int d) {
        if (!headIsMajorInduct(m, v, r)) {
            return Layout.NONE;
        }
        int p = Infer.isprop(m, r, d + 1); // call ISPROP(T) => 9 (it may fail, as the official is_prop throws)
        return p >= SIG ? m.sig3(p, K_IOTA_9, v, w, r) : k_iota9(m, v, w, r, p, d);
    }

    private static boolean headIsMajorInduct(Machine m, int v, int ty) {
        if (ty <= Layout.HOLE) {
            return false;
        }
        int[] vh = m.vh;
        int o = ty << Layout.REC_SHIFT;
        int kind = vh[o] >>> Layout.KIND_SHIFT;
        int th = kind == Layout.NEU ? vh[o + Layout.A] : ty;
        return (kind == Layout.NEU || kind == Layout.CONST) && (vh[th << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT) == Layout.CONST
                && vh[(th << Layout.REC_SHIFT) + Layout.A] == m.decls.recMajorInduct(recRow(m, v));
    }

    /**
     * Frame IOTA[9](v, w, ty): r = ISPROP(ty). A proof is not eta-expanded. Otherwise the major is
     * {@code I.mk params (w.0) .. (w.(nfields-1))}; only its last nfields arguments are needed, so the
     * application itself is not built.
     */
    static int k_iota9(Machine m, int v, int w, int ty, int r, int d) {
        return r != Layout.F ? Layout.NONE : iotaEtaRule(m, v, w, ty, d);
    }

    /** The rule for the constructor of the structure and its right-hand side, for an eta-expanded major. */
    private static int iotaEtaRule(Machine m, int v, int w, int ty, int d) {
        int head = m.vh[(v << Layout.REC_SHIFT) + Layout.A];
        int rec = m.vh[(head << Layout.REC_SHIFT) + Layout.A];
        DeclTable decls = m.decls;
        int ctor = decls.inductCtor(decls.recMajorInduct(rec), 0);
        int rule = decls.recRuleFor(rec, ctor);
        if (rule < 0 || decls.recRuleFields(rec, rule) > etaParams(m, ty, ctor) + decls.ctorNumFields(ctor)) {
            return Layout.NONE;
        }
        if ((m.sw & Op.SW_CODE) != 0) {
            int b = ruleBlock(m, rec, rule);
            if (b != 0) {
                return b >= SIG ? b : iotaBlock(m, v, b, rule, pushEtaArgs(m, v, w, ty, rule, false), d);
            }
        }
        int rhs = Eval.eval(m, decls.recRuleRhs(rec, rule), m.ep, 0, Levels.substOf(m, head), d + 1); // call EVAL(rhs) => 11
        return rhs >= SIG ? m.sig4(rhs, K_IOTA_11, v, w, ty, rule) : k_iota11(m, v, w, ty, rule, rhs, d);
    }

    /** Number of parameters the eta-expanded constructor application takes from the type: min(nparams, arguments of T). */
    private static int etaParams(Machine m, int ty, int ctor) {
        int w0 = m.vh[ty << Layout.REC_SHIFT];
        int n = (w0 >>> Layout.KIND_SHIFT) == Layout.NEU ? w0 & Layout.AUX_MASK : 0;
        return Math.min(m.decls.ctorNumParams(ctor), n);
    }

    /** Frame IOTA[11](v, w, ty, rule): r = the value of the rule's right-hand side (structure eta). */
    static int k_iota11(Machine m, int v, int w, int ty, int rule, int r, int d) {
        int xb = m.xp;
        int e = pushEtaArgs(m, v, w, ty, rule, true);
        if (e >= SIG) {
            return e;
        }
        int a = Eval.apply(m, r, xb, m.xp - xb, d + 1); // call APPLY(rhs, args) => 12
        return a >= SIG ? m.sig1(a, K_IOTA_12, xb) : k_iota12(m, xb, a, d);
    }

    /**
     * The arguments of the rule application for an eta-expanded major: parameters, motives, minors; the last
     * nfields arguments of {@code I.mk params (proj I 0 w) ..}; if {@code withRest}, the arguments after the
     * major. 0, or a signal.
     */
    private static int pushEtaArgs(Machine m, int v, int w, int ty, int rule, boolean withRest) {
        int rec = recRow(m, v);
        DeclTable decls = m.decls;
        int ind = decls.recMajorInduct(rec);
        int ctor = decls.inductCtor(ind, 0);
        int k = etaParams(m, ty, ctor);
        int total = k + decls.ctorNumFields(ctor);
        int lead = decls.recNumParams(rec) + decls.recNumMotives(rec) + decls.recNumMinors(rec);
        int nf = decls.recRuleFields(rec, rule);
        int n = m.vh[v << Layout.REC_SHIFT] & Layout.AUX_MASK;
        int rest = withRest ? n - decls.recMajorIndex(rec) - 1 : 0;
        if (lead > n || !m.ensureScratch(lead + nf + rest)) {
            return m.fault(lead > n ? MachineErrors.E_INTERNAL : MachineErrors.E_MEM);
        }
        int xb = m.xp;
        copyArgs(m, v, 0, lead, xb);
        for (int j = total - nf, at = xb + lead; j < total; j++, at++) {
            int f = j < k ? Runs.arg(m, m.vh[(ty << Layout.REC_SHIFT) + Layout.B], j) : Values.mkProj(m, ind, j - k, w);
            if (f >= SIG) {
                return f;
            }
            m.X[at] = f;
        }
        copyArgs(m, v, n - rest, rest, xb + lead + nf);
        m.xp = xb + lead + nf + rest;
        return 0;
    }

    // =====================================================================================================
    // QUOTR (ISA 4.3; official quot_reduce_rec, R02 7.5)
    // =====================================================================================================

    /**
     * QUOTR(v) (ISA 4.3): v is headed by Quot.lift (major 5) or Quot.ind (major 4) with enough arguments.
     * Official: {@code quot_reduce_rec} (R02 7.5). Entry frame: QUOTR[0](v).
     * @return the reduct, {@code Layout.NONE}, or a signal
     */
    static int quotr(Machine m, int v, int d) {
        int mk = quotMajor(m, v);
        if (mk < 0) {
            return Layout.NONE; // 1. inline answers: not a quotient recursor, or the major is not among the arguments
        }
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig; // 2. TICK
        }
        if (m.stats) {
            m.call(Op.QUOTR);
        }
        if (d > m.hostDepth) {
            return m.spill1(Op.K_QUOTR, v); // 3. too deep
        }
        int w = whnf(m, Runs.arg(m, m.vh[(v << Layout.REC_SHIFT) + Layout.B], mk), d + 1); // call WHNF(major) => 1
        return w >= SIG ? m.sig1(w, K_QUOTR_1, v) : k_quotr1(m, v, w, d);
    }

    /** Position of the {@code Quot.mk} argument of a NEU headed by Quot.lift (5) or Quot.ind (4); -1 if there is none. */
    private static int quotMajor(Machine m, int v) {
        if (v <= Layout.HOLE) {
            return -1;
        }
        int[] vh = m.vh;
        int o = v << Layout.REC_SHIFT;
        int w0 = vh[o];
        if ((w0 >>> Layout.KIND_SHIFT) != Layout.NEU || hclass(w0) != Layout.HC_QUOT) {
            return -1;
        }
        int k = m.decls.kind(vh[(vh[o + Layout.A] << Layout.REC_SHIFT) + Layout.A]);
        int mk = k == DeclKind.QUOT_LIFT ? 5 : k == DeclKind.QUOT_IND ? 4 : -1;
        return mk < (w0 & Layout.AUX_MASK) ? mk : -1;
    }

    /**
     * Frame QUOTR[1](v): r = WHNF of the major, which must be {@code Quot.mk} applied to exactly 3 arguments;
     * the result is {@code f a} followed by the arguments after the major ({@code f} = argument 3).
     */
    static int k_quotr1(Machine m, int v, int r, int d) {
        return isQuotMk(m, r) ? quotApply(m, v, r, d) : Layout.NONE;
    }

    /** {@code f a} followed by the arguments after the major, for the major {@code r = Quot.mk _ _ a}. */
    private static int quotApply(Machine m, int v, int r, int d) {
        int mk = quotMajor(m, v);
        int n = m.vh[v << Layout.REC_SHIFT] & Layout.AUX_MASK;
        int rest = n - mk - 1;
        if (!m.ensureScratch(1 + rest)) {
            return m.fault(MachineErrors.E_MEM);
        }
        int xb = m.xp;
        m.X[xb] = Runs.arg(m, m.vh[(r << Layout.REC_SHIFT) + Layout.B], 2);
        copyArgs(m, v, mk + 1, rest, xb + 1);
        m.xp = xb + 1 + rest;
        int a = Eval.apply(m, Runs.arg(m, m.vh[(v << Layout.REC_SHIFT) + Layout.B], 3), xb, 1 + rest, d + 1); // call APPLY => 2
        return a >= SIG ? m.sig1(a, K_QUOTR_2, xb) : k_quotr2(m, xb, a, d);
    }

    private static boolean isQuotMk(Machine m, int w) {
        if (w <= Layout.HOLE) {
            return false;
        }
        int[] vh = m.vh;
        int o = w << Layout.REC_SHIFT;
        int w0 = vh[o];
        if ((w0 >>> Layout.KIND_SHIFT) != Layout.NEU || (w0 & Layout.AUX_MASK) != 3) {
            return false;
        }
        int h = vh[o + Layout.A];
        return (vh[h << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT) == Layout.CONST
                && m.decls.kind(vh[(h << Layout.REC_SHIFT) + Layout.A]) == DeclKind.QUOT_MK;
    }

    /** Frame QUOTR[2](xb): r = the reduct; the scratch is released. */
    static int k_quotr2(Machine m, int xb, int r, int d) {
        m.xp = xb;
        return r;
    }

    // =====================================================================================================
    // The family's part of the driver
    // =====================================================================================================

    /**
     * The family's part of the driver (H6): one case per frame key, calling the step with the payload words
     * as arguments, {@code r} as the result register and d = 0. The state-0 cases (spilled entry steps) are
     * given; M3 adds one case per continuation step (at most 50 cases per method, H10: split by op).
     */
    static int resume(Machine m, int key, int[] w, int p, int r) {
        switch (key) {
            case Op.K_DELTA:
                return delta(m, w[p], 0);
            case K_DELTA_1:
                return k_delta1(m, w[p], w[p + 1], r, 0);
            case K_DELTA_2:
                return k_delta2(m, w[p], w[p + 1], r, 0);
            case K_DELTA_3:
                return k_delta3(m, w[p], w[p + 1], r, 0);
            case Op.K_CORE:
                return core(m, w[p], w[p + 1], 0);
            case K_CORE_1:
                return k_core1(m, w[p], r, 0);
            case K_CORE_2:
                return k_core2(m, w[p], r, 0);
            case K_CORE_3:
                return k_core3(m, w[p], r, 0);
            case K_CORE_4:
                return k_core4(m, w[p], r, 0);
            case K_CORE_5:
                return k_core5(m, w[p], r, 0);
            case K_CORE_6:
                return k_core6(m, w[p], w[p + 1], w[p + 2], r, 0);
            case Op.K_WHNF:
                return whnf(m, w[p], 0);
            case K_WHNF_1:
                return k_whnf1(m, r, 0);
            case K_WHNF_2:
                return k_whnf2(m, w[p], w[p + 1], w[p + 2], r, 0);
            case K_WHNF_3:
                return k_whnf3(m, w[p], w[p + 1], w[p + 2], w[p + 3], r, 0);
            case K_WHNF_4:
                return k_whnf4(m, w[p], w[p + 1], w[p + 2], w[p + 3], r, 0);
            case K_WHNF_5:
                return k_whnf5(m, w[p], w[p + 1], w[p + 2], w[p + 3], r, 0);
            case Op.K_IOTA:
                return iota(m, w[p], 0);
            case K_IOTA_1:
                return k_iota1(m, w[p], w[p + 1], r, 0);
            case K_IOTA_2:
                return k_iota2(m, w[p], w[p + 1], r, 0);
            case K_IOTA_3:
                return k_iota3(m, w[p], w[p + 1], w[p + 2], w[p + 3], r, 0);
            case K_IOTA_4:
                return k_iota4(m, w[p], w[p + 1], w[p + 2], r, 0);
            case K_IOTA_5:
                return k_iota5(m, w[p], r, 0);
            case K_IOTA_6:
                return k_iota6(m, w[p], r, 0);
            case K_IOTA_7:
                return k_iota7(m, w[p], w[p + 1], r, 0);
            case K_IOTA_8:
                return k_iota8(m, w[p], w[p + 1], r, 0);
            case K_IOTA_9:
                return k_iota9(m, w[p], w[p + 1], w[p + 2], r, 0);
            case K_IOTA_10:
                return k_iota10(m, w[p], w[p + 1], w[p + 2], r, 0);
            case K_IOTA_11:
                return k_iota11(m, w[p], w[p + 1], w[p + 2], w[p + 3], r, 0);
            case K_IOTA_12:
                return k_iota12(m, w[p], r, 0);
            case K_IOTA_13:
                return k_iota13(m, w[p], w[p + 1], r, 0);
            case Op.K_QUOTR:
                return quotr(m, w[p], 0);
            case K_QUOTR_1:
                return k_quotr1(m, w[p], r, 0);
            case K_QUOTR_2:
                return k_quotr2(m, w[p], r, 0);
            case Op.K_RULES:
                return Rules.rules(m, w[p], 0);
            case Rules.K_RULES_1:
                return Rules.k_rules1(m, w[p], r, 0);
            case Op.K_NATF:
                return NatRed.natFold(m, w[p], 0);
            case NatRed.K_NATF_1:
                return NatRed.k_natf1(m, w[p], r, 0);
            case NatRed.K_NATF_2:
                return NatRed.k_natf2(m, w[p], w[p + 1], r, 0);
            case Op.K_OFFSET:
                return NatRed.offset(m, w[p], w[p + 1], 0);
            default:
                return m.fault(MachineErrors.E_INTERNAL);
        }
    }
}
