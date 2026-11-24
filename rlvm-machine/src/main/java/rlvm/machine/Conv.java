package rlvm.machine;

import static rlvm.machine.Layout.F;
import static rlvm.machine.Layout.NONE;
import static rlvm.machine.Layout.T;
import static rlvm.machine.Layout.UNDEF;
import static rlvm.machine.Op.MISS;
import static rlvm.machine.Op.SIG;

import rlvm.api.MachineErrors;
import rlvm.nat.NatOp;
import rlvm.store.DeclKind;
import rlvm.store.DeclTable;
import rlvm.store.IntLists;
import rlvm.store.Specials;

/**
 * Family CV (ISA 4.4): {@code CONV}, {@code CCLO}, {@code CBIND}, {@code LAZYD}, {@code PROJD}, {@code PIRR},
 * {@code ETA}, {@code ETAS}, {@code UNITL}, {@code STRX}; the family's resume switch (which also dispatches
 * the frames of {@link Cargs}). Owner: M4.
 *
 * <p>Entry steps are the frozen cross-package interface; continuation steps {@code k_*}, loop methods
 * and frame keys are this file's own (stepper-convention.md H2-H4; template in WORK-PACKAGES.md).
 *
 * <h2>What is implemented</h2>
 * v0 (all switches off): the complete procedure of ISA 6.3 rule 3: the official {@code is_def_eq} (R02 section
 * 8) transcribed to values, state for state: c0 without B12, c2 first line, c3 .. c13, with {@code CARGS} in
 * the official order and every position SAME. No speculative segment, no fact, no plan. The steps of R02
 * 8.1 and the states of ISA 4.4.1 are named in the comments ("step n" = R02 8.1, "cN" = ISA 4.4.1).
 * Step 2 ({@code Op.SW_FACTS}): static PROOF at c4 (B7), the prop mask of ETAS; the plan of CARGS is in
 * {@link Cargs}. Step 5 ({@code Op.SW_PROBES}): B12 (CCLO), B1, B2. Deviations and measurements: NOTES-M4.
 *
 * <h2>Loops</h2>
 * Three loops of the official procedure are Java loops whose body is a chain of steps (H3 "inside a
 * loop"): the restart of {@code is_def_eq_core} after the second {@code whnf_core} (c7, {@link #convLoop}),
 * the lazy delta loop ({@link #lazydLoop}) and the binder telescope ({@link #bindLoop}). A step of the
 * body that wants the next iteration returns the private result {@link #AGAIN} with the loop variables in
 * {@code m.A2}, {@code m.A3} (and {@code Ext.r3}); the loop method reads them before it calls anything. A
 * body that was resumed from a frame returns into the {@code ..Back} method of its loop, which re-enters
 * the loop method: a resumed loop costs one host frame, not one per iteration.
 *
 * <h2>Frames</h2>
 * {@code CONV[1..10]}, {@code CONV2[0..5]}: every state of a pass carries the pair the judgment was called
 * with ({@code t0}, {@code s0}: what POS / NEG are recorded for) and the pair in hand. {@code LAZYD[1..5]},
 * {@code LSTEP[0..8]} (one {@code lazy_delta_reduction_step}, shared by LAZYD and PROJD; op 0x3C of this
 * family, see NOTES-M4), {@code CBIND[1..6]}, {@code PROJD[1..3]}, {@code PIRR[1..3]}, {@code ETA[1..8]},
 * {@code ETAS[1..4]}, {@code UNITL[1..3]}, {@code STRX[1]}; {@code CARGS[1]} is in {@link Cargs}.
 */
final class Conv {
    private Conv() {}

    /**
     * Session state private to this package (caches, counters, scratch of its own): add fields here
     * instead of asking for fields in {@link Machine}. One instance per machine, field {@code m.x4}.
     */
    static final class Ext {
        /** Third loop variable of a step that returns {@link #AGAIN} (the binder count of CBIND); read at once. */
        int r3;
        /**
         * Step 5: the probe debt of the declaration: the budgets of the segments that ended in U, halved by every
         * segment that ended in T. At {@link #DEBT_MAX} no further segment is started in this declaration
         * (NOTES-M4 D25): it bounds what failing probes can cost on a deep descent. Counted in ticks, so a verdict
         * and its cost do not depend on load.
         */
        long debt;
        static final long DEBT_MAX = 1L << 17;
        /** {@link Same}: the fuel left of the running query; the nodes all queries of this machine visited (statistics). */
        int sameFuel;
        long sameNodes;
        /**
         * {@link Same}: nodes visited by the queries of this declaration that did not see identity. At
         * {@link #SAME_WASTE_MAX} no further query is started in the declaration: a failed walk is pure cost, and a
         * declaration in which they keep failing gains nothing from more of them. Counted in nodes, reset at
         * {@code beginDecl}, so costs stay functions of the declaration alone.
         */
        int sameWaste;
        static final int SAME_WASTE_MAX = 1 << 16;
        /**
         * The NON-OFFICIAL options of this package (NOTES-M4 D27): a comma list in the system property
         * {@code rlvm.machine.m4}, read once per machine. They are deliberately not staging switches: what they
         * enable is declaratively sound but is not the verdict of the official checker (owner note 11), so no
         * switch set, {@code all} included, can turn them on. Off by default; strict mode never reads them
         * ({@link #opt}). They exist to measure what the shortcuts would be worth.
         */
        int opts = parseOpts(System.getProperty("rlvm.machine.m4", ""));
        /**
         * {@code skipargs}: B3 (proof positions of CARGS skipped under the agreement rule), B4 (unread positions
         * skipped under SAMEDEF) and the Prop mask of ETAS, with {@code Op.SW_FACTS}. Declaratively sound, but NOT
         * the official verdict (finding S-F2, NOTES-M4 D27): never part of a default configuration.
         */
        static final int OPT_SKIP_ARGS = 2;
        /**
         * {@code congruence}: B1 (same-head probe), B12 (closure congruence, slots by CONV) and B2, with
         * {@code Op.SW_PROBES}. Declaratively sound, but NOT the official verdict (finding S-F1, NOTES-M4 D27):
         * never part of a default configuration.
         */
        static final int OPT_CONGRUENCE = 4;

        private static int parseOpts(String list) {
            int bits = 0;
            for (String name : list.split(",")) {
                switch (name.trim()) {
                    case "":
                        break;
                    case "skipargs":
                        bits |= OPT_SKIP_ARGS;
                        break;
                    case "congruence":
                        bits |= OPT_CONGRUENCE;
                        break;
                    default:
                        throw new IllegalArgumentException("unknown option of rlvm.machine.m4: '" + name + "'");
                }
            }
            return bits;
        }

        /** Session reset (ISA 1.6): forget everything, in O(used). */
        void reset(Machine m) {
            debt = 0;
            sameWaste = 0;
        }

        /** Start of a declaration. */
        void beginDecl(Machine m) {
            debt = 0;
            sameWaste = 0;
        }

        /** Bytes in use of this package's own session tables (part of the footprint, ISA 1.6). */
        long bytes() {
            return 0;
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // Private result codes and frame keys
    // ---------------------------------------------------------------------------------------------------

    /** Result of a loop-body step: run the loop again; the loop variables are in {@code m.A2}, {@code m.A3}. Never a result of an entry step. */
    static final int AGAIN = 4;
    /** Result of {@link #quick}: both sides are binders of one class (LAM / LAM, or PI or PII on both sides): the caller runs CBIND. */
    static final int BIND = 5;

    /**
     * LSTEP(tn, sn): one {@code lazy_delta_reduction_step} (R02 8.4), a judgment of its own because LAZYD and
     * PROJD both call it (H2: a frame names one continuation). {@code Op.LSTEP} = 0x3C (NOTES-M4 D2).
     */
    static final int LSTEP = Op.LSTEP;
    static final int K_LSTEP = Op.K_LSTEP;

    static final int K_CONV_1 = Op.K_CONV | 1;
    static final int K_CONV_2 = Op.K_CONV | 2;
    static final int K_CONV_3 = Op.K_CONV | 3;
    static final int K_CONV_4 = Op.K_CONV | 4;
    static final int K_CONV_5 = Op.K_CONV | 5;
    static final int K_CONV_6 = Op.K_CONV | 6;
    static final int K_CONV_7 = Op.K_CONV | 7;
    static final int K_CONV_8 = Op.K_CONV | 8;
    static final int K_CONV_9 = Op.K_CONV | 9;
    static final int K_CONV_10 = Op.K_CONV | 10;
    /** CONV2: the states c8 .. rec. State 0 = "the sub-judgment's result is the result" (rec). */
    static final int K_CONV2_REC = Op.K_CONV2;
    static final int K_CONV2_1 = Op.K_CONV2 | 1;
    static final int K_CONV2_2 = Op.K_CONV2 | 2;
    static final int K_CONV2_3 = Op.K_CONV2 | 3;
    static final int K_CONV2_4 = Op.K_CONV2 | 4;
    static final int K_CONV2_5 = Op.K_CONV2 | 5;
    /** Step 5: the continuations of the B12 and B1 segments. */
    static final int K_CONV2_6 = Op.K_CONV2 | 6;
    static final int K_CONV2_7 = Op.K_CONV2 | 7;
    /** Step 2: the continuation of the SIGD call of c4 (B7). */
    static final int K_CONV2_8 = Op.K_CONV2 | 8;
    static final int K_CCLO_1 = Op.K_CCLO | 1;

    static final int K_CBIND_1 = Op.K_CBIND | 1;
    static final int K_CBIND_2 = Op.K_CBIND | 2;
    static final int K_CBIND_3 = Op.K_CBIND | 3;
    static final int K_CBIND_4 = Op.K_CBIND | 4;
    static final int K_CBIND_5 = Op.K_CBIND | 5;
    static final int K_CBIND_6 = Op.K_CBIND | 6;

    static final int K_LAZYD_1 = Op.K_LAZYD | 1;
    static final int K_LAZYD_2 = Op.K_LAZYD | 2;
    static final int K_LAZYD_3 = Op.K_LAZYD | 3;
    static final int K_LAZYD_4 = Op.K_LAZYD | 4;
    static final int K_LAZYD_5 = Op.K_LAZYD | 5;

    static final int K_LSTEP_1 = K_LSTEP | 1;
    static final int K_LSTEP_2 = K_LSTEP | 2;
    static final int K_LSTEP_3 = K_LSTEP | 3;
    static final int K_LSTEP_4 = K_LSTEP | 4;
    static final int K_LSTEP_5 = K_LSTEP | 5;
    static final int K_LSTEP_6 = K_LSTEP | 6;
    static final int K_LSTEP_7 = K_LSTEP | 7;
    static final int K_LSTEP_8 = K_LSTEP | 8;

    static final int K_PROJD_1 = Op.K_PROJD | 1;
    static final int K_PROJD_2 = Op.K_PROJD | 2;
    static final int K_PROJD_3 = Op.K_PROJD | 3;

    static final int K_PIRR_1 = Op.K_PIRR | 1;
    static final int K_PIRR_2 = Op.K_PIRR | 2;
    static final int K_PIRR_3 = Op.K_PIRR | 3;

    static final int K_ETA_1 = Op.K_ETA | 1;
    static final int K_ETA_2 = Op.K_ETA | 2;
    static final int K_ETA_3 = Op.K_ETA | 3;
    static final int K_ETA_4 = Op.K_ETA | 4;
    static final int K_ETA_5 = Op.K_ETA | 5;
    static final int K_ETA_6 = Op.K_ETA | 6;
    static final int K_ETA_7 = Op.K_ETA | 7;
    static final int K_ETA_8 = Op.K_ETA | 8;

    static final int K_ETAS_1 = Op.K_ETAS | 1;
    static final int K_ETAS_2 = Op.K_ETAS | 2;
    static final int K_ETAS_3 = Op.K_ETAS | 3;
    static final int K_ETAS_4 = Op.K_ETAS | 4;
    /** Step 2: the continuation of the SIGD call of ETAS. */
    static final int K_ETAS_5 = Op.K_ETAS | 5;

    static final int K_UNITL_1 = Op.K_UNITL | 1;
    static final int K_UNITL_2 = Op.K_UNITL | 2;
    static final int K_UNITL_3 = Op.K_UNITL | 3;

    static final int K_STRX_1 = Op.K_STRX | 1;

    // ---------------------------------------------------------------------------------------------------
    // Reading values (inline helpers; no judgment)
    // ---------------------------------------------------------------------------------------------------

    private static final int W0_HCLASS_SHIFT = Layout.FLAG_SHIFT + Layout.HCLASS_SHIFT;
    private static final int W0_PROOF_SHIFT = Layout.FLAG_SHIFT + Layout.PROOF_SHIFT;

    /** Head class (ISA 2.3 HCLASS) from word 0 of a CONST or NEU record. */
    private static int hclass(int w0) {
        return (w0 >>> W0_HCLASS_SHIFT) & 7;
    }

    /** A Nat or String literal: immediate, NBIG or STR (the official expression kind Lit). */
    private static boolean isLit(int kind) {
        return kind == Layout.K_IMM || kind == Layout.NBIG || kind == Layout.STR;
    }

    /** Both PI or PII (in any combination): the Pi class of CBIND (ISA 4.4.3). */
    private static boolean isPiKind(int kind) {
        return kind == Layout.PI || kind == Layout.PII;
    }

    /**
     * Two literals that are not the same ref (ISA 2.9, 4.4.1 c0; official: Lit against Lit is decided by value,
     * R02 8.2, section 10 row 39). Immediates and two-word NBIG records are canonical; two NBIG or two STR
     * records are compared by content until canonical ids are guaranteed end to end (RECONCILIATION section 2).
     */
    private static int litEq(Machine m, int a, int b, int ka, int kb) {
        if (ka != kb || ka == Layout.K_IMM) {
            return F; // Nat against String, an immediate against an NBIG, or two different immediates
        }
        return Lits.sameContent(m, a, b) ? T : F;
    }

    /**
     * The official {@code is_delta} (R02 7.6; section 10 row 14): the row of the definition or theorem at the head of
     * {@code v} if it is applied to as many levels as it has parameters, else 0.
     */
    static int deltaRow(Machine m, int v) {
        if (v < 0) {
            return 0;
        }
        int[] vh = m.vh;
        int o = v << Layout.REC_SHIFT;
        int w = vh[o];
        int k = w >>> Layout.KIND_SHIFT;
        if ((k != Layout.NEU && k != Layout.CONST) || hclass(w) != Layout.HC_DEFN) {
            return 0;
        }
        int h = k == Layout.NEU ? vh[o + Layout.A] << Layout.REC_SHIFT : o;
        // word c of the CONST record is the substitution id of (row, levels); -1 = the level count is not the
        // row's (Values.mkConst): such a head never unfolds
        return vh[h + Layout.C] == -1 ? 0 : vh[h + Layout.A];
    }

    /** The level list of the constant at the head of a CONST or constant-headed NEU record. */
    private static int headLevels(Machine m, int v) {
        int[] vh = m.vh;
        int o = v << Layout.REC_SHIFT;
        int h = (vh[o] >>> Layout.KIND_SHIFT) == Layout.NEU ? vh[o + Layout.A] << Layout.REC_SHIFT : o;
        return vh[h + Layout.B];
    }

    /** {@code hints(c)} of R02 8.4: the definition's hint; everything else (theorems!) counts as opaque. */
    private static int hintOf(Machine m, int row) {
        DeclTable dt = m.decls;
        return dt.kind(row) == DeclKind.DEFN ? dt.hint(row) : DeclKind.HINT_OPAQUE;
    }

    private static boolean hintIsRegular(int hint) {
        return hint != DeclKind.HINT_OPAQUE && hint != DeclKind.HINT_ABBREV;
    }

    /**
     * The official {@code compare(reducibility_hints, reducibility_hints)} (declaration.cpp:19-44, R02 8.4): negative =
     * unfold the left side, positive = the right side, 0 = both. Regular: the greater height first; abbrev before
     * regular; opaque last.
     */
    static int compareHints(int h1, int h2) {
        boolean r1 = hintIsRegular(h1);
        boolean r2 = hintIsRegular(h2);
        if (r1 && r2) {
            return h1 == h2 ? 0 : h1 > h2 ? -1 : 1;
        }
        if (h1 == h2) {
            return 0;
        }
        if (h1 == DeclKind.HINT_OPAQUE) {
            return 1;
        }
        if (h2 == DeclKind.HINT_OPAQUE) {
            return -1;
        }
        return h1 == DeclKind.HINT_ABBREV ? -1 : 1;
    }

    /** {@code is_non_rec_structure} (inductive.cpp:28-33, R02 7.4): inductive, one constructor, no indices, not recursive. */
    static boolean isNonRecStructure(Machine m, int row) {
        DeclTable dt = m.decls;
        return dt.kind(row) == DeclKind.INDUCT && dt.inductCtorCount(row) == 1 && dt.inductNumIndices(row) == 0
                && (dt.inductFlags(row) & DeclTable.IF_REC) == 0;
    }

    /** True if the body of the binder closure {@code v} (LAM, PI, PII) reads its variable: bit 0 of the body's mask (ISA 2.4). */
    private static boolean readsVar(Machine m, int v) {
        return (m.terms.fvMask(m.terms.b(Values.a(m, v))) & 1L) != 0;
    }

    /**
     * {@code s == Const Bool.true []} (R02 8.1 step 2): the constant bound to the special, without levels. No prelude
     * guard: the test is sound whatever Bool.true is (the reference kernel has none either).
     */
    private static boolean isBoolTrue(Machine m, int v) {
        return m.decls.special(Values.a(m, v)) == Specials.BOOL_TRUE && Values.b(m, v) == IntLists.EMPTY;
    }

    /**
     * {@code reduce_proj_core} on a value that is already normal (R02 7.2): the field {@code i} of a constructor
     * application of structure {@code structRow}, else NONE. A constructor of another inductive type is refused
     * (R02 section 10 row 9).
     */
    private static int ctorField(Machine m, int v, int structRow, int i) {
        if (v < 0) {
            return NONE;
        }
        int[] vh = m.vh;
        int o = v << Layout.REC_SHIFT;
        int w = vh[o];
        if ((w >>> Layout.KIND_SHIFT) != Layout.NEU || hclass(w) != Layout.HC_CTOR) {
            return NONE;
        }
        int ctor = vh[(vh[o + Layout.A] << Layout.REC_SHIFT) + Layout.A];
        DeclTable dt = m.decls;
        if (dt.ctorInduct(ctor) != structRow) {
            return NONE;
        }
        int j = dt.ctorNumParams(ctor) + i;
        return i >= 0 && j < (w & Layout.AUX_MASK) ? Runs.arg(m, vh[o + Layout.B], j) : NONE;
    }

    // ---------------------------------------------------------------------------------------------------
    // CONV: entry, c0
    // ---------------------------------------------------------------------------------------------------

    /**
     * CONV(t, s) (ISA 4.4.1): the official {@code is_def_eq} (R02 8.1) transcribed to values, states c0..c13 in the
     * official order; v0 = the complete procedure (6.3 rule 3: c0 without B12, c2 first line, c3..c13, CARGS pass C
     * only). POS / NEG in the pair table (5.4). Entry frame: CONV[0](a, b).
     * @return T, F, or a signal
     */
    static int conv(Machine m, int a, int b, int d) {
        if (a == b) {
            return T; // c0: identity (I-ID); official quick_is_def_eq, t == s
        }
        int q = convFast(m, a, b);
        if (q != MISS) {
            return q;
        }
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig;
        }
        if (m.stats) {
            m.call(Op.CONV);
        }
        if (d > m.hostDepth) {
            return m.spill2(Op.K_CONV, a, b);
        }
        return convStart(m, a, b, d);
    }

    /**
     * The inline part of CONV (state c0 without calls): identity, two literals (content), POS / NEG in the pair table
     * (NEG not under ER). A SUSP side whose value is known is replaced by it first (c0 "a side is SUSP").
     * @return T, F, or {@code Op.MISS} (under the debug mode {@code memocheck} also FATAL: test {@code >= Op.MISS})
     */
    static int convFast(Machine m, int a, int b) {
        if (a == b) {
            return T;
        }
        int[] vh = m.vh;
        int ka = a < 0 ? Layout.K_IMM : vh[a << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT;
        int kb = b < 0 ? Layout.K_IMM : vh[b << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT;
        if (ka == Layout.SUSP || kb == Layout.SUSP) {
            return convFastSusp(m, a, b);
        }
        if (isLit(ka) && isLit(kb)) {
            return litEq(m, a, b, ka, kb); // official quick_is_def_eq, Lit (definitive)
        }
        if (ka == Layout.SORT && kb == Layout.SORT) {
            return MISS; // LVLEQ after the tick (c0); a pair of sorts is never in the pair table
        }
        int st = Pairs.status(m, a, b);
        return (st & (Layout.POS | Layout.NEG)) == 0 ? MISS : pairHit(m, a, b, st);
    }

    /** c0 "a side is SUSP: FORCE both, restart", as far as it needs no call (the values are known). */
    private static int convFastSusp(Machine m, int a, int b) {
        int x = Eval.forceFast(m, a);
        if (x >= MISS) {
            return MISS;
        }
        int y = Eval.forceFast(m, b);
        if (y >= MISS) {
            return MISS;
        }
        return convFast(m, x, y); // neither is a SUSP now (VAL is never a SUSP)
    }

    /**
     * The pair has POS or NEG (ISA 4.4.1 c0, 5.4). POS: T (the official m_success, R02 section 5). NEG: F, but it is
     * not read while ER is set (a comparison under eager reduction may succeed where the plain one failed).
     */
    private static int pairHit(Machine m, int a, int b, int st) {
        int hit;
        if ((st & Layout.POS) != 0) {
            hit = T;
        } else if (m.ER == 0) {
            hit = F;
        } else {
            return MISS;
        }
        if (m.stats) {
            m.hits[Op.CONV]++;
        }
        if (m.debug != 0) {
            hit = Debug.memoCheck(m, Op.CONV, a, b, hit);
        }
        return hit;
    }

    /** c0 after the tick: forces SUSP sides (FORCE is a judgment), then the pass loop. */
    private static int convStart(Machine m, int t, int s, int d) {
        if ((t >>> 1) == 0 || (s >>> 1) == 0) {
            return m.fault(MachineErrors.E_INTERNAL); // NONE or HOLE is never a side of a comparison
        }
        int fuel = (m.sw & Op.SW_M4A) != 0 ? sameShape(m, t, s) : 0;
        if (fuel != 0 && seenSame(m, t, s, fuel)) {
            // [D28] the two values stand for one expression: the official is_equiv of quick_is_def_eq
            if ((m.debug & Op.DBG_SHORTCHECK) != 0 && Debug.shortCheck(m, t, s, T) != 0) {
                return Op.FATAL; // shortcheck (ISA 5.5): the complete procedure must answer T as well
            }
            Pairs.add(m, t, s, Layout.POS);
            return T;
        }
        if (m.sw != 0 && congruence(m) && sameClosure(m, t, s)) {
            return convB12(m, t, s, d);
        }
        return convForce(m, t, s, d);
    }

    /** c0 "a side is SUSP: FORCE both, restart"; then the pass loop. */
    private static int convForce(Machine m, int t, int s, int d) {
        if (Values.kind(m, t) == Layout.SUSP) {
            int r = Eval.force(m, t, d + 1);
            return r >= SIG ? m.sig1(r, K_CONV_1, s) : k_conv1(m, s, r, d);
        }
        if (Values.kind(m, s) == Layout.SUSP) {
            int r = Eval.force(m, s, d + 1);
            return r >= SIG ? m.sig1(r, K_CONV_2, t) : k_conv2(m, t, r, d);
        }
        return convLoop(m, t, s, t, s, d);
    }

    /** CONV[1](s); r = FORCE(t). */
    static int k_conv1(Machine m, int s, int t, int d) {
        if (Values.kind(m, s) == Layout.SUSP) {
            int r = Eval.force(m, s, d + 1);
            return r >= SIG ? m.sig1(r, K_CONV_2, t) : k_conv2(m, t, r, d);
        }
        return k_conv2(m, t, s, d);
    }

    /** CONV[2](t); r = FORCE(s). Both sides are forced: c0 again on the values (no second tick), then the pass loop. */
    static int k_conv2(Machine m, int t, int s, int d) {
        int q = convFast(m, t, s);
        return q != MISS ? q : convLoop(m, t, s, t, s, d);
    }

    // ---------------------------------------------------------------------------------------------------
    // CONV: the pass loop (the tail call of is_def_eq_core after step 10 is the loop)
    // ---------------------------------------------------------------------------------------------------

    /**
     * Runs passes c0 (kinds) .. c13 over the pair in hand until one decides. {@code (t0, s0)} is the pair CONV was
     * called with (forced): POS and NEG are recorded for it (rec). After c7 found a side changed the pass returns
     * AGAIN and the new pair {@code (tnn, snn)} is in A2 / A3: the official {@code return is_def_eq_core(tnn, snn)}.
     */
    private static int convLoop(Machine m, int t0, int s0, int a, int b, int d) {
        for (;;) {
            int r = convPass(m, t0, s0, a, b, d);
            if (r != AGAIN) {
                return r;
            }
            a = m.A2;
            b = m.A3;
            r = convAgain(m, t0, s0, a, b);
            if (r != MISS) {
                return r;
            }
        }
    }

    /** A pass that was resumed from a frame ended with {@code r}: a result, or AGAIN (re-enter the loop). */
    private static int convBack(Machine m, int t0, int s0, int r) {
        if (r != AGAIN) {
            return r;
        }
        int a = m.A2;
        int b = m.A3;
        r = convAgain(m, t0, s0, a, b);
        return r != MISS ? r : convLoop(m, t0, s0, a, b, 0);
    }

    /**
     * Step 1 of the restarted {@code is_def_eq_core(tnn, snn)}: {@code quick_is_def_eq} without its binder case
     * (identity, m_success = POS, literals, sorts), then one tick for the new pass. NEG is not consulted here (ISA
     * 4.4.1 c7; the official kernel has no such table).
     * @return T / F (recorded), a signal, or MISS: run the pass
     */
    private static int convAgain(Machine m, int t0, int s0, int a, int b) {
        int q = quick(m, a, b);
        if (q == T || q == F) {
            return rec(m, t0, s0, a, b, q);
        }
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig;
        }
        return MISS;
    }

    /**
     * {@code quick_is_def_eq(a, b)} (R02 8.2) as far as it needs no judgment: identity, sorts ({@code is_equivalent}),
     * literals (by value), the success cache (POS of the exact pair).
     * @return T, F, BIND (both LAM, or both PI / PII: {@code is_def_eq_binding} decides), or MISS (undef)
     */
    static int quick(Machine m, int a, int b) {
        if (a == b) {
            return T;
        }
        int ka = Values.kind(m, a);
        int kb = Values.kind(m, b);
        if (ka == kb) {
            if (ka == Layout.SORT) {
                return Levels.lvlEq(m, Values.a(m, a), Values.a(m, b)) ? T : F;
            }
            if (ka == Layout.LAM || isPiKind(ka)) {
                return BIND;
            }
            if (isLit(ka)) {
                return litEq(m, a, b, ka, kb);
            }
        } else {
            if (isPiKind(ka) && isPiKind(kb)) {
                return BIND;
            }
            if (isLit(ka) && isLit(kb)) {
                return F;
            }
        }
        return (Pairs.status(m, a, b) & Layout.POS) != 0 ? T : MISS;
    }

    /**
     * rec (ISA 4.4.1, 5.4): {@code r} is the final T or F of the comparison of {@code (t0, s0)}; {@code (x, y)} is the
     * pair in hand when it was decided. T: POS for both pairs (exact pairs only: the official m_success, R02 section
     * 5). F: NEG for {@code (t0, s0)}, unless ER is set or a side is a LAM.
     */
    private static int rec(Machine m, int t0, int s0, int x, int y, int r) {
        if (r == T) {
            Pairs.add(m, t0, s0, Layout.POS);
            if (x != y && (x != t0 || y != s0)) {
                Pairs.add(m, x, y, Layout.POS);
            }
        } else if (r == F && m.ER == 0 && Values.kind(m, t0) != Layout.LAM && Values.kind(m, s0) != Layout.LAM) {
            Pairs.add(m, t0, s0, Layout.NEG);
        }
        return r;
    }

    /** CONV2[0](t0, s0, x, y): the result of the sub-judgment is the result of the comparison. */
    static int k_convRec(Machine m, int t0, int s0, int x, int y, int r, int d) {
        return rec(m, t0, s0, x, y, r);
    }

    // ---------------------------------------------------------------------------------------------------
    // CONV: one pass. c0 (kinds), c2, c3
    // ---------------------------------------------------------------------------------------------------

    /**
     * One pass over the pair {@code (a, b)}: neither side is a SUSP, the pair is not identical, not two literals and
     * not in the pair table. c0: sorts and binders (definitive); c2: the {@code Bool.true} shortcut (step 2); then c3.
     * @return T / F (recorded by rec), AGAIN, or a signal
     */
    private static int convPass(Machine m, int t0, int s0, int a, int b, int d) {
        int ka = Values.kind(m, a);
        int kb = Values.kind(m, b);
        if (ka == kb ? (ka == Layout.SORT || ka == Layout.LAM || isPiKind(ka)) : (isPiKind(ka) && isPiKind(kb))) {
            if (ka == Layout.SORT) {
                // official quick_is_def_eq, Sort: is_equivalent (R02 3.4), never by id alone. ISA c0: "ret LVLEQ":
                // a pair of sorts is not recorded; after a restart the pair CONV was called with is (rec).
                int r = Levels.lvlEq(m, Values.a(m, a), Values.a(m, b)) ? T : F;
                return a == t0 && b == s0 ? r : rec(m, t0, s0, a, b, r);
            }
            return convBind(m, t0, s0, a, b, d);
        }
        if (m.sw != 0 && ka == Layout.NEU && kb == Layout.NEU && congruence(m)) {
            long budget = b1Budget(m, a, b);
            if (budget != 0) {
                return convB1(m, t0, s0, a, b, budget, d);
            }
        }
        return convBoolTrue(m, t0, s0, a, b, d);
    }

    /** c2 / step 2: only s on the RIGHT is tested; t must be closed (the official hasFVar) unless ER. */
    private static int convBoolTrue(Machine m, int t0, int s0, int a, int b, int d) {
        if (Values.kind(m, b) == Layout.CONST && isBoolTrue(m, b)
                && ((Values.flags(m, a) & Layout.FL_OPEN) == 0 || m.ER != 0)) {
            int r = Reduce.whnf(m, a, d + 1);
            return r >= SIG ? m.sig4(r, K_CONV_3, t0, s0, a, b) : k_conv3(m, t0, s0, a, b, r, d);
        }
        return convCore1(m, t0, s0, a, b, d);
    }

    /** Both sides are binders of one class: CBIND is definitive (official quick_is_def_eq, Lambda / Pi). */
    private static int convBind(Machine m, int t0, int s0, int a, int b, int d) {
        int r = cbind(m, a, b, d + 1);
        return r >= SIG ? m.sig4(r, K_CONV2_REC, t0, s0, a, b) : rec(m, t0, s0, a, b, r);
    }

    /** CONV[3](t0, s0, a, b); r = WHNF(a). Step 2: {@code whnf(t)} is {@code Bool.true}: T; otherwise fall through. */
    static int k_conv3(Machine m, int t0, int s0, int a, int b, int r, int d) {
        return r == b ? rec(m, t0, s0, a, b, T) : convCore1(m, t0, s0, a, b, d);
    }

    /** c3 / step 3: {@code tn = whnf_core(t, cheap_proj = true)}. The flag WHNF answers CORE inline. */
    private static int convCore1(Machine m, int t0, int s0, int a, int b, int d) {
        if ((m.sw & Op.SW_PROBES) != 0 && a > 0 && opt(m, Ext.OPT_CONGRUENCE)
                && ((m.vh[a << Layout.REC_SHIFT] >>> W0_PROOF_SHIFT) & 3) == Layout.TRI_YES) {
            // [B2] (ISA 6.3): a known proof is compared by type before it is reduced: it enters c4 as it is
            return convIrrel(m, t0, s0, a, b, d);
        }
        int r = (Values.flags(m, a) & Layout.FL_WHNF) != 0 ? a : Reduce.core(m, a, Op.CHEAP, d + 1);
        return r >= SIG ? m.sig4(r, K_CONV_4, t0, s0, a, b) : k_conv4(m, t0, s0, a, b, r, d);
    }

    /** CONV[4](t0, s0, a, b); r = tn = CORE(a, cheap). {@code sn = whnf_core(s, cheap_proj = true)}. */
    static int k_conv4(Machine m, int t0, int s0, int a, int b, int tn, int d) {
        int r = (Values.flags(m, b) & Layout.FL_WHNF) != 0 ? b : Reduce.core(m, b, Op.CHEAP, d + 1);
        return r >= SIG ? m.sig5(r, K_CONV_5, t0, s0, a, b, tn) : k_conv5(m, t0, s0, a, b, tn, r, d);
    }

    /** CONV[5](t0, s0, a, b, tn); r = sn = CORE(b, cheap). Step 4: a side changed: quick again. */
    static int k_conv5(Machine m, int t0, int s0, int a, int b, int tn, int sn, int d) {
        if (tn != a || sn != b) {
            int q = quick(m, tn, sn);
            if (q != MISS) {
                return q == BIND ? convBind(m, t0, s0, tn, sn, d) : rec(m, t0, s0, tn, sn, q);
            }
        }
        return convIrrel(m, t0, s0, tn, sn, d);
    }

    // ---------------------------------------------------------------------------------------------------
    // CONV: c4 (proof irrelevance), c5 (lazy delta), c6 (constants, projections)
    // ---------------------------------------------------------------------------------------------------

    /**
     * c4 / step 5: {@code is_def_eq_proof_irrel(tn, sn)}, definitive in both directions (R02 8.3). The flag
     * PROOF = 1 ("not a proof", written by PIRR from the dynamic ISPROP) answers UNDEF inline; an immediate is
     * never a proof.
     */
    private static int convIrrel(Machine m, int t0, int s0, int tn, int sn, int d) {
        if (tn < 0) {
            return convLazy(m, t0, s0, tn, sn, d);
        }
        int p = (m.vh[tn << Layout.REC_SHIFT] >>> W0_PROOF_SHIFT) & 3;
        if (p == Layout.TRI_NO) {
            return convLazy(m, t0, s0, tn, sn, d);
        }
        if (p == Layout.TRI_UNKNOWN && (m.sw & Op.SW_FACTS) != 0) {
            return convStatic(m, t0, s0, tn, sn, d);
        }
        int r = pirr(m, tn, sn, d + 1);
        return r >= SIG ? m.sig4(r, K_CONV_6, t0, s0, tn, sn) : k_conv6(m, t0, s0, tn, sn, r, d);
    }

    /** The row of the constant at the head of a CONST or constant-headed NEU record, else 0. */
    private static int headRow(Machine m, int v) {
        int[] vh = m.vh;
        int o = v << Layout.REC_SHIFT;
        int k = vh[o] >>> Layout.KIND_SHIFT;
        if (k == Layout.NEU) {
            o = vh[o + Layout.A] << Layout.REC_SHIFT;
            k = vh[o] >>> Layout.KIND_SHIFT;
        }
        return k == Layout.CONST ? vh[o + Layout.A] : 0;
    }

    /**
     * [B7] (ISA 3.5, 6.3; step 2, {@code Op.SW_FACTS}): the static PROOF of {@code tn}. The fact record of its head
     * constant is derived first if it does not exist yet (SIGD, a judgment; once per constant and engine).
     */
    private static int convStatic(Machine m, int t0, int s0, int tn, int sn, int d) {
        int row = headRow(m, tn);
        if (row != 0 && Facts.facts(m, row) == 0) {
            int index = Facts.sigd(m, row, d + 1);
            return index >= SIG ? m.sig4(index, K_CONV2_8, t0, s0, tn, sn) : k_conv2_8(m, t0, s0, tn, sn, index, d);
        }
        return k_conv2_8(m, t0, s0, tn, sn, 0, d);
    }

    /**
     * CONV2[8](t0, s0, tn, sn); r = SIGD (not used: the record is read again). A static answer is memoised in the
     * flag PROOF: 1 = not a proof (sorts, Pis, literals; a saturated constant application whose result sort is
     * manifest and never zero): c4 is skipped; 2 = a proof (manifest and always zero): PIRR compares the types
     * without the dynamic ISPROP. Unknown: the dynamic test of the complete procedure.
     */
    static int k_conv2_8(Machine m, int t0, int s0, int tn, int sn, int index, int d) {
        return convStaticUse(m, t0, s0, tn, sn, d);
    }

    private static int convStaticUse(Machine m, int t0, int s0, int tn, int sn, int d) {
        int sp = Facts.staticProof(m, tn);
        if (m.stats) {
            m.probe(Op.B7, sp == Layout.TRI_UNKNOWN ? Layout.U : sp == Layout.TRI_NO ? F : T);
        }
        if (sp != Layout.TRI_UNKNOWN) {
            if ((m.debug & Op.DBG_SHORTCHECK) != 0 && staticProofCheck(m, tn, sp, d) != 0) {
                return Op.FATAL;
            }
            m.vh[tn << Layout.REC_SHIFT] |= sp << W0_PROOF_SHIFT;
            if (sp == Layout.TRI_NO) {
                return convLazy(m, t0, s0, tn, sn, d);
            }
        }
        int r = pirr(m, tn, sn, d + 1);
        return r >= SIG ? m.sig4(r, K_CONV_6, t0, s0, tn, sn) : k_conv6(m, t0, s0, tn, sn, r, d);
    }

    /**
     * shortcheck of a static PROOF answer (ISA 5.5): the dynamic test {@code ISPROP(VTYPE(tn))} of the complete
     * procedure, run to completion at depth 0 like every debug re-derivation, must give the same answer.
     * @return 0, or FATAL (E_AUDIT, or the fault of the re-derivation)
     */
    private static int staticProofCheck(Machine m, int tn, int sp, int d) {
        if (!Debug.memoEnter(m)) {
            return 0;
        }
        int sw = m.sw;
        m.sw = 0;
        int r = m.drive(Infer.vtype(m, tn, 0));
        if (r < SIG) {
            r = m.drive(Infer.isprop(m, r, 0));
        }
        m.sw = sw;
        int expect = sp == Layout.TRI_YES ? T : F;
        return Debug.memoExit(m, Op.PIRR, tn, 0, expect, r) >= SIG ? Op.FATAL : 0;
    }

    /** CONV[6](t0, s0, tn, sn); r = PIRR(tn, sn): T and F are final. */
    static int k_conv6(Machine m, int t0, int s0, int tn, int sn, int r, int d) {
        return r != UNDEF ? rec(m, t0, s0, tn, sn, r) : convLazy(m, t0, s0, tn, sn, d);
    }

    /** c5 / step 6: {@code lazy_delta_reduction(tn, sn)}; it leaves the final pair in A2 / A3. */
    private static int convLazy(Machine m, int t0, int s0, int tn, int sn, int d) {
        int r = lazyd(m, tn, sn, d + 1);
        return r >= SIG ? m.sig2(r, K_CONV_7, t0, s0) : k_conv7(m, t0, s0, r, d);
    }

    /** CONV[7](t0, s0); r = LAZYD; the final pair is read from A2 / A3 before anything is called (H7). */
    static int k_conv7(Machine m, int t0, int s0, int r, int d) {
        int tn = m.A2;
        int sn = m.A3;
        return r != UNDEF ? rec(m, t0, s0, tn, sn, r) : convSame(m, t0, s0, tn, sn, d);
    }

    /**
     * c6. Step 7: both constants, same name, levels pairwise {@code is_equivalent}: T. (Step 8, the same free
     * variable, is identity and was answered by a quick test.) Step 9: {@code proj S i a} against {@code proj S i b}:
     * {@code lazy_delta_proj_reduction}; only T decides.
     */
    private static int convSame(Machine m, int t0, int s0, int tn, int sn, int d) {
        int kt = Values.kind(m, tn);
        if (kt == Values.kind(m, sn)) {
            if (kt == Layout.CONST) {
                if (Values.a(m, tn) == Values.a(m, sn) && Levels.listsEq(m, Values.b(m, tn), Values.b(m, sn))) {
                    return rec(m, t0, s0, tn, sn, T);
                }
            } else if (kt == Layout.PROJ && Values.b(m, tn) == Values.b(m, sn) && Values.c(m, tn) == Values.c(m, sn)) {
                int r = projd(m, Values.a(m, tn), Values.a(m, sn), Values.b(m, tn), Values.c(m, tn), d + 1);
                return r >= SIG ? m.sig4(r, K_CONV_8, t0, s0, tn, sn) : k_conv8(m, t0, s0, tn, sn, r, d);
            }
        }
        return convCore2(m, t0, s0, tn, sn, d);
    }

    /** CONV[8](t0, s0, tn, sn); r = PROJD. */
    static int k_conv8(Machine m, int t0, int s0, int tn, int sn, int r, int d) {
        return r == T ? rec(m, t0, s0, tn, sn, T) : convCore2(m, t0, s0, tn, sn, d);
    }

    // ---------------------------------------------------------------------------------------------------
    // CONV: c7 (second whnf_core), c8 (application congruence)
    // ---------------------------------------------------------------------------------------------------

    /** c7 / step 10: {@code tnn = whnf_core(tn)}, full: the struct of a projection is reduced with whnf. */
    private static int convCore2(Machine m, int t0, int s0, int tn, int sn, int d) {
        int r = (Values.flags(m, tn) & Layout.FL_WHNF) != 0 ? tn : Reduce.core(m, tn, Op.FULL, d + 1);
        return r >= SIG ? m.sig4(r, K_CONV_9, t0, s0, tn, sn) : k_conv9(m, t0, s0, tn, sn, r, d);
    }

    /** CONV[9](t0, s0, tn, sn); r = tnn = CORE(tn, full). */
    static int k_conv9(Machine m, int t0, int s0, int tn, int sn, int tnn, int d) {
        int r = (Values.flags(m, sn) & Layout.FL_WHNF) != 0 ? sn : Reduce.core(m, sn, Op.FULL, d + 1);
        return r >= SIG ? m.sig5(r, K_CONV_10, t0, s0, tn, sn, tnn) : k_conv10(m, t0, s0, tn, sn, tnn, r, d);
    }

    /** CONV[10](t0, s0, tn, sn, tnn); r = snn. A side changed: restart with (tnn, snn). */
    static int k_conv10(Machine m, int t0, int s0, int tn, int sn, int tnn, int snn, int d) {
        if (tnn != tn || snn != sn) {
            m.A2 = tnn;
            m.A3 = snn;
            return AGAIN;
        }
        return convApp(m, t0, s0, tn, sn, d);
    }

    /**
     * c8 / step 11: {@code is_def_eq_app}: both applications; the heads are compared first, THEN the arities, then the
     * arguments first to last (R02 8.6). An NSUC is an application of {@code Nat.succ} officially; two of them never
     * arrive here (OFFSET is definitive), and against any other head the official answer is F without an error.
     */
    private static int convApp(Machine m, int t0, int s0, int tn, int sn, int d) {
        if (Values.kind(m, tn) == Layout.NEU && Values.kind(m, sn) == Layout.NEU) {
            int ht = Values.a(m, tn);
            int hs = Values.a(m, sn);
            int r = ht == hs ? T : conv(m, ht, hs, d + 1);
            return r >= SIG ? m.sig4(r, K_CONV2_1, t0, s0, tn, sn) : k_conv2_1(m, t0, s0, tn, sn, r, d);
        }
        return convEta(m, t0, s0, tn, sn, d);
    }

    /** CONV2[1](t0, s0, tn, sn); r = CONV(head tn, head sn). */
    static int k_conv2_1(Machine m, int t0, int s0, int tn, int sn, int r, int d) {
        if (r == T && Values.aux(m, tn) == Values.aux(m, sn)) {
            int c = Cargs.cargs(m, tn, sn, Op.POL_APP, d + 1);
            return c >= SIG ? m.sig4(c, K_CONV2_2, t0, s0, tn, sn) : k_conv2_2(m, t0, s0, tn, sn, c, d);
        }
        return convEta(m, t0, s0, tn, sn, d);
    }

    /** CONV2[2](t0, s0, tn, sn); r = CARGS(tn, sn, APP). */
    static int k_conv2_2(Machine m, int t0, int s0, int tn, int sn, int r, int d) {
        return r == T ? rec(m, t0, s0, tn, sn, T) : convEta(m, t0, s0, tn, sn, d);
    }

    // ---------------------------------------------------------------------------------------------------
    // CONV: c9 (eta), c10 (structure eta), c11 (string literal), c12 (unit-like), c13
    // ---------------------------------------------------------------------------------------------------

    /** c9 / step 12: {@code try_eta_expansion}, both directions; only when exactly one side is a lambda. */
    private static int convEta(Machine m, int t0, int s0, int tn, int sn, int d) {
        boolean lt = Values.kind(m, tn) == Layout.LAM;
        boolean ls = Values.kind(m, sn) == Layout.LAM;
        if (lt == ls) {
            return convEtaS(m, t0, s0, tn, sn, d);
        }
        int r = lt ? eta(m, tn, sn, d + 1) : eta(m, sn, tn, d + 1);
        return r >= SIG ? m.sig4(r, K_CONV2_3, t0, s0, tn, sn) : k_conv2_3(m, t0, s0, tn, sn, r, d);
    }

    /** CONV2[3](t0, s0, tn, sn); r = ETA. */
    static int k_conv2_3(Machine m, int t0, int s0, int tn, int sn, int r, int d) {
        return r == T ? rec(m, t0, s0, tn, sn, T) : convEtaS(m, t0, s0, tn, sn, d);
    }

    /** c10 / step 13: {@code try_eta_struct_core(tn, sn)}; the shape test of ETAS is inline. */
    private static int convEtaS(Machine m, int t0, int s0, int tn, int sn, int d) {
        if (etasShape(m, sn)) {
            int r = etas(m, tn, sn, d + 1);
            return r >= SIG ? m.sig4(r, K_CONV2_4, t0, s0, tn, sn) : k_conv2_4(m, t0, s0, tn, sn, r, d);
        }
        return convEtaS2(m, t0, s0, tn, sn, d);
    }

    /** CONV2[4](t0, s0, tn, sn); r = ETAS(tn, sn). */
    static int k_conv2_4(Machine m, int t0, int s0, int tn, int sn, int r, int d) {
        return r == T ? rec(m, t0, s0, tn, sn, T) : convEtaS2(m, t0, s0, tn, sn, d);
    }

    /** c10, the other direction: {@code try_eta_struct_core(sn, tn)}. */
    private static int convEtaS2(Machine m, int t0, int s0, int tn, int sn, int d) {
        if (etasShape(m, tn)) {
            int r = etas(m, sn, tn, d + 1);
            return r >= SIG ? m.sig4(r, K_CONV2_5, t0, s0, tn, sn) : k_conv2_5(m, t0, s0, tn, sn, r, d);
        }
        return convTail(m, t0, s0, tn, sn, d);
    }

    /** CONV2[5](t0, s0, tn, sn); r = ETAS(sn, tn). */
    static int k_conv2_5(Machine m, int t0, int s0, int tn, int sn, int r, int d) {
        return r == T ? rec(m, t0, s0, tn, sn, T) : convTail(m, t0, s0, tn, sn, d);
    }

    /**
     * c11 / step 14: a string literal against {@code String.ofList x}, either direction: STRX is definitive. c12 /
     * step 15: {@code is_def_eq_unit_like}. c13 / step 16: F (the F of UNITL).
     */
    private static int convTail(Machine m, int t0, int s0, int tn, int sn, int d) {
        int r;
        if (strxShape(m, tn, sn)) {
            r = strx(m, tn, sn, d + 1);
        } else if (strxShape(m, sn, tn)) {
            r = strx(m, sn, tn, d + 1);
        } else {
            r = unitl(m, tn, sn, d + 1);
        }
        return r >= SIG ? m.sig4(r, K_CONV2_REC, t0, s0, tn, sn) : rec(m, t0, s0, tn, sn, r);
    }

    // ---------------------------------------------------------------------------------------------------
    // Step 5 (Op.SW_PROBES): speculative segments B12 (CCLO), B1 (same-head probe). ISA 6.2, 6.3.
    // A segment runs under a budget (TRY, ISA 1.4; template in Frames): its outcome is T, F or U, and only T
    // decides; F and U hand over to the state the complete procedure would have entered without the probe.
    // ---------------------------------------------------------------------------------------------------

    /** True if speculative segments may be started: step 5 is on and the declaration's probe debt is below its bound. */
    static boolean probing(Machine m) {
        return (m.sw & Op.SW_PROBES) != 0 && m.x4.debt < Ext.DEBT_MAX;
    }

    /**
     * True if the congruence shortcuts B1 and B12 may start a segment: step 5 is on, the probe debt is below its bound
     * AND the non-official option {@code congruence} is given (finding S-F1: a T by congruence of arguments or frame
     * slots is not always a T of the official procedure).
     */
    static boolean congruence(Machine m) {
        return probing(m) && opt(m, Ext.OPT_CONGRUENCE);
    }

    /**
     * True if CARGS and ETAS may skip positions by the fact record (B3, B4, the Prop mask): step 2 is on AND the
     * non-official option {@code skipargs} is given (finding S-F2: the official procedure compares two proofs by
     * their types, and that comparison can fail where the agreement rule holds).
     */
    static boolean skipArgs(Machine m) {
        return (m.sw & Op.SW_FACTS) != 0 && opt(m, Ext.OPT_SKIP_ARGS);
    }

    /** True if the experiment {@code bit} of {@link Ext#opts} is on; never in strict mode (ISA 14.2). */
    static boolean opt(Machine m, int bit) {
        return (m.x4.opts & bit) != 0 && !m.strict;
    }

    /** Accounts the outcome of a segment that was given {@code budget} ticks (see {@link Ext#debt}). */
    static void settle(Machine m, int outcome, long budget) {
        if (outcome == Layout.U) {
            m.x4.debt += budget;
        } else if (outcome == T) {
            m.x4.debt >>>= 1;
        }
    }

    /**
     * The pairs for which the structural identity walk {@link Same} is tried at c0 (D28), chosen by what a T saves
     * and by the measured share of walks that see identity (NOTES-M4 W4): two suspensions (identity is decided
     * without forcing them: 40 %), two closures of one kind (no binder is opened: 80-96 % for Pi types of different
     * terms), two projections, and two applications of the SAME head and length whose head may reduce (flag ACTIVE:
     * definition, theorem, recursor, quotient, projection), where a T saves the unfolding. Applications of a rigid
     * head (constructor, inductive type, variable, axiom) are left to the complete procedure, whose c8 compares
     * their arguments one by one and meets the walk again on every closure among them; a suspension against a
     * value never was the same expression in the corpora. And only while the walks of this declaration have not
     * wasted {@code SAME_WASTE_MAX} nodes.
     * @return the fuel of the walk, 0 = no walk
     */
    private static int sameShape(Machine m, int t, int s) {
        if (t <= Layout.HOLE || s <= Layout.HOLE || m.x4.sameWaste >= Ext.SAME_WASTE_MAX) {
            return 0;
        }
        int[] vh = m.vh;
        int ot = t << Layout.REC_SHIFT;
        int os = s << Layout.REC_SHIFT;
        int wt = vh[ot];
        int kt = wt >>> Layout.KIND_SHIFT;
        if (kt != vh[os] >>> Layout.KIND_SHIFT) {
            return 0;
        }
        switch (kt) {
            case Layout.NEU:
                return (wt & Layout.W0_ACTIVE) != 0 && vh[ot + Layout.A] == vh[os + Layout.A]
                        && (wt & Layout.AUX_MASK) == (vh[os] & Layout.AUX_MASK) ? Same.FUEL_NEU : 0;
            case Layout.SUSP:
            case Layout.LAM:
            case Layout.PI:
            case Layout.PII:
            case Layout.PROJ:
                return Same.FUEL;
            default:
                return 0;
        }
    }

    /** {@link Same#same} with its accounting: the outcome as a probe event (B12's counters: T seen, U not seen). */
    private static boolean seenSame(Machine m, int t, int s, int fuel) {
        boolean r = Same.same(m, t, s, fuel);
        if (!r) {
            m.x4.sameWaste += fuel - m.x4.sameFuel;
        }
        if (m.stats) {
            m.probe(Op.B12, r ? T : Layout.U);
        }
        return r;
    }

    /**
     * The shape of B12 (ISA 4.4.1 c0): both SUSP, or both LAM, or both PI, or both PII, with the same term and the
     * same ls, neither frame far. (Same term: same mask, hence frames of the same length with HOLE at the same places.)
     */
    static boolean sameClosure(Machine m, int t, int s) {
        if (t <= Layout.HOLE || s <= Layout.HOLE) {
            return false;
        }
        int[] vh = m.vh;
        int ot = t << Layout.REC_SHIFT;
        int os = s << Layout.REC_SHIFT;
        int k = vh[ot] >>> Layout.KIND_SHIFT;
        if (k != vh[os] >>> Layout.KIND_SHIFT || (k != Layout.SUSP && k != Layout.LAM && !isPiKind(k))) {
            return false;
        }
        if (vh[ot + Layout.A] != vh[os + Layout.A] || vh[ot + Layout.C] != vh[os + Layout.C]) {
            return false;
        }
        int[] rs = m.rs;
        return ((rs[vh[ot + Layout.B]] | rs[vh[os + Layout.B]]) & Layout.RUN_FAR) == 0;
    }

    /**
     * [B12] (ISA 4.4.1 c0, 6.3): {@code r = TRY(1024) call CCLO(t, s); r == T: goto rec}. A pair that was answered
     * before has POS (the lookup of c0 comes after the forcing, which a T here avoids).
     */
    private static int convB12(Machine m, int t, int s, int d) {
        if ((Pairs.status(m, t, s) & Layout.POS) != 0) {
            return T;
        }
        int ssp = m.ssp;
        int xp = m.xp;
        int ep = m.ep;
        int rp = m.rp;
        int er = m.ER;
        long saved = m.tryEnter(Op.B_CCLO);
        int r = cclo(m, t, s, d + 1);
        r = r == Op.SUSP ? m.saveTry(saved, Op.B_CCLO, ssp, xp, ep, rp, er)
                : Frames.k_try(m, saved, Op.B_CCLO, ssp, xp, ep, rp, er, r);
        return r >= SIG ? m.sig2(r, K_CONV2_6, t, s) : k_conv2_6(m, t, s, r, d);
    }

    /** CONV2[6](t, s); r = the outcome of the B12 segment: T decides (congruence of substitution); F and U do not. */
    static int k_conv2_6(Machine m, int t, int s, int r, int d) {
        if (m.stats) {
            m.probe(Op.B12, r);
        }
        settle(m, r, Op.B_CCLO);
        if (r != T) {
            return convForce(m, t, s, d);
        }
        if ((m.debug & Op.DBG_SHORTCHECK) != 0 && Debug.shortCheck(m, t, s, T) != 0) {
            return Op.FATAL;
        }
        Pairs.add(m, t, s, Layout.POS);
        return T;
    }

    /**
     * CCLO(t, s) (ISA 4.4.1, B12): closure congruence, the frames compared slot by slot; only T decides. Step 5
     * ({@code Op.SW_PROBES}). Entry frame: CCLO[0](a, b).
     *
     * <p>T is sound by congruence of substitution (S11 of R05 section 4): the two values are the same term under
     * frames whose slots are pairwise convertible. An F (or a U of the segment around it) is not an answer of CONV.
     * @return T, F, or a signal
     */
    static int cclo(Machine m, int a, int b, int d) {
        if (!sameClosure(m, a, b)) {
            return F; // leaf: not the shape of the probe ("no answer")
        }
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig;
        }
        if (m.stats) {
            m.call(Op.CCLO);
        }
        if (d > m.hostDepth) {
            return m.spill2(Op.K_CCLO, a, b);
        }
        return ccloFrom(m, a, b, 0, d);
    }

    /** The slots from {@code i} on: identical or convertible, HOLE only against HOLE. */
    private static int ccloFrom(Machine m, int a, int b, int i, int d) {
        int fa = Values.b(m, a);
        int fb = Values.b(m, b);
        int n = Runs.len(m, fa);
        if (n != Runs.len(m, fb)) {
            return F;
        }
        for (; i < n; i++) {
            int x = Runs.arg(m, fa, i);
            int y = Runs.arg(m, fb, i);
            if (x == y) {
                continue;
            }
            if (x == Layout.HOLE || y == Layout.HOLE) {
                return F;
            }
            int r = conv(m, x, y, d + 1);
            if (r >= SIG) {
                return m.sig3(r, K_CCLO_1, a, b, i);
            }
            if (r != T) {
                return F;
            }
        }
        return T;
    }

    /**
     * The quick refutation of a hard pair in pass A of CARGS (ISA 6.1; step 5): two literals of different content, or
     * two constructor applications with different constructors that are both known not to be proofs (PROOF = 1):
     * the complete procedure answers F on these forms. Two proofs built with different constructors are convertible.
     */
    static boolean refutes(Machine m, int x, int y) {
        int kx = Values.kind(m, x);
        int ky = Values.kind(m, y);
        if (isLit(kx) && isLit(ky)) {
            return litEq(m, x, y, kx, ky) == F;
        }
        if (x < 0 || y < 0) {
            return false;
        }
        int[] vh = m.vh;
        int wx = vh[x << Layout.REC_SHIFT];
        int wy = vh[y << Layout.REC_SHIFT];
        if ((kx != Layout.NEU && kx != Layout.CONST) || (ky != Layout.NEU && ky != Layout.CONST)
                || hclass(wx) != Layout.HC_CTOR || hclass(wy) != Layout.HC_CTOR) {
            return false;
        }
        if (((wx >>> W0_PROOF_SHIFT) & 3) != Layout.TRI_NO || ((wy >>> W0_PROOF_SHIFT) & 3) != Layout.TRI_NO) {
            return false;
        }
        return ctorOf(m, x) != ctorOf(m, y);
    }

    /** CCLO[1](a, b, i); r = CONV(slot i). */
    static int k_cclo1(Machine m, int a, int b, int i, int r, int d) {
        return r != T ? F : ccloFrom(m, a, b, i + 1, d);
    }

    /**
     * The condition of [B1] (ISA 4.4.1 c1; 6.3 row B1: "same-head argument probe for any unfoldable head"): both NEU
     * (the caller tested that) of the same length whose heads are the same constant with equivalent levels, that
     * constant being unfoldable: a definition or theorem ({@code is_delta}), or a recursor or quotient eliminator
     * (NOTES-M4 D19); the pair not in FAILED and no UNDEC entry of this budget class.
     * @return the budget of the probe (16384 for a definition with a regular hint, else 1024; ISA 6.2), or 0
     */
    private static long b1Budget(Machine m, int a, int b) {
        if (Values.aux(m, a) != Values.aux(m, b)) {
            return 0;
        }
        long budget = b1Class(m, a);
        if (budget == 0 || budget != b1Class(m, b)) {
            return 0;
        }
        int ha = Values.a(m, a);
        int hb = Values.a(m, b);
        if (ha != hb && (Values.a(m, ha) != Values.a(m, hb) || !Levels.listsEq(m, Values.b(m, ha), Values.b(m, hb)))) {
            return 0;
        }
        if ((Pairs.status(m, a, b) & Layout.FAILED) != 0 || Pairs.undec(m, a, b) >= Pairs.log2(budget)) {
            return 0;
        }
        return budget;
    }

    /** The budget class of a same-head probe on the NEU {@code v} by its head (ISA 6.2), 0 = the head is not unfoldable. */
    private static long b1Class(Machine m, int v) {
        int hc = hclass(m.vh[v << Layout.REC_SHIFT]);
        if (hc == Layout.HC_DEFN) {
            int row = deltaRow(m, v);
            return row == 0 ? 0 : hintIsRegular(hintOf(m, row)) ? Op.B_SAMEHEAD : Op.B_SAMEHEAD_ABBREV;
        }
        return hc == Layout.HC_REC || hc == Layout.HC_QUOT ? Op.B_SAMEHEAD_ABBREV : 0;
    }

    /** [B1] (ISA 4.4.1 c1, 6.3): {@code r = TRY(budget) call CARGS(a, b, SAMEDEF)}. */
    private static int convB1(Machine m, int t0, int s0, int a, int b, long budget, int d) {
        int ssp = m.ssp;
        int xp = m.xp;
        int ep = m.ep;
        int rp = m.rp;
        int er = m.ER;
        long saved = m.tryEnter(budget);
        int r = Cargs.cargs(m, a, b, Op.POL_SAMEDEF, d + 1);
        r = r == Op.SUSP ? m.saveTry(saved, budget, ssp, xp, ep, rp, er)
                : Frames.k_try(m, saved, budget, ssp, xp, ep, rp, er, r);
        return r >= SIG ? m.sig4(r, K_CONV2_7, t0, s0, a, b) : k_conv2_7(m, t0, s0, a, b, r, d);
    }

    /**
     * CONV2[7](t0, s0, a, b); r = the outcome of the B1 segment. T: congruence, goto rec. F: the comparison completed:
     * FAILED += pair, so that lazy delta unfolds at once (the official m_failure). U: UNDEC only; U never enters FAILED
     * and lazy delta still runs the official comparison itself (ISA 5.4). Then c2.
     */
    static int k_conv2_7(Machine m, int t0, int s0, int a, int b, int r, int d) {
        if (m.stats) {
            m.probe(Op.B1, r);
        }
        settle(m, r, b1Class(m, a));
        if (r == T) {
            if ((m.debug & Op.DBG_SHORTCHECK) != 0 && Debug.shortCheck(m, a, b, T) != 0) {
                return Op.FATAL;
            }
            return rec(m, t0, s0, a, b, T);
        }
        if (r == F) {
            Pairs.add(m, a, b, Layout.FAILED);
        } else {
            Pairs.setUndec(m, a, b, Pairs.log2(b1Class(m, a)));
        }
        return convBoolTrue(m, t0, s0, a, b, d);
    }

    // ---------------------------------------------------------------------------------------------------
    // CBIND
    // ---------------------------------------------------------------------------------------------------

    /**
     * CBIND(t, s) (ISA 4.4.3): both LAM, or both PI / PII: domains, OPEN typed with s's domain, INST both, loop.
     * Official: {@code is_def_eq_binding} (R02 8.2). Entry frame: CBIND[0](a, b).
     * @return T, F, or a signal
     */
    static int cbind(Machine m, int a, int b, int d) {
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig;
        }
        if (m.stats) {
            m.call(Op.CBIND);
        }
        if (d > m.hostDepth) {
            return m.spill2(Op.K_CBIND, a, b);
        }
        return bindLoop(m, a, b, Values.kind(m, a) == Layout.LAM ? 1 : 0, d);
    }

    /**
     * The telescope loop. {@code nk = n << 1 | lam}: n binders are open (owned by this judgment, I-SCOPE), lam = the
     * starting kind is LAM (else PI / PII). One iteration = one binder of both sides.
     */
    private static int bindLoop(Machine m, int t, int s, int nk, int d) {
        for (;;) {
            int r = bindDom(m, t, s, nk, d);
            if (r != AGAIN) {
                return r;
            }
            t = m.A2;
            s = m.A3;
            nk = m.x4.r3;
        }
    }

    /** An iteration that was resumed from a frame ended with {@code r}. */
    private static int bindBack(Machine m, int r) {
        return r != AGAIN ? r : bindLoop(m, m.A2, m.A3, m.x4.r3, 0);
    }

    /** {@code Dt = DOM(t)}: memo M_DOM, else the judgment DOM. */
    private static int bindDom(Machine m, int t, int s, int nk, int d) {
        int dt = m.vh[(t << Layout.REC_SHIFT) + Layout.M_DOM];
        if (dt == 0) {
            dt = Eval.dom(m, t, d + 1);
        }
        return dt >= SIG ? m.sig3(dt, K_CBIND_1, t, s, nk) : k_cbind1(m, t, s, nk, dt, d);
    }

    /** CBIND[1](t, s, nk); r = Dt. {@code Ds = DOM(s)}. */
    static int k_cbind1(Machine m, int t, int s, int nk, int dt, int d) {
        int ds = m.vh[(s << Layout.REC_SHIFT) + Layout.M_DOM];
        if (ds == 0) {
            ds = Eval.dom(m, s, d + 1);
        }
        return ds >= SIG ? m.sig4(ds, K_CBIND_2, t, s, nk, dt) : k_cbind2(m, t, s, nk, dt, ds, d);
    }

    /** CBIND[2](t, s, nk, Dt); r = Ds. The domains must be convertible (official: only if they differ syntactically). */
    static int k_cbind2(Machine m, int t, int s, int nk, int dt, int ds, int d) {
        int c = dt == ds ? T : conv(m, dt, ds, d + 1);
        return c >= SIG ? m.sig4(c, K_CBIND_3, t, s, nk, ds) : k_cbind3(m, t, s, nk, ds, c, d);
    }

    /** CBIND[3](t, s, nk, Ds); r = CONV(Dt, Ds). */
    static int k_cbind3(Machine m, int t, int s, int nk, int ds, int c, int d) {
        if (c != T) {
            Scope.close(m, nk >>> 1);
            return F;
        }
        return bindOpen(m, t, s, nk, ds, d);
    }

    /**
     * {@code x = neither body reads its variable ? HOLE : OPEN(Ds)}: the variable is typed with s's domain (R02 8.2);
     * no variable is made for a non-dependent binder (the official dummy). Then the body of t at x: APPLY for a LAM
     * (x in one scratch word, shared by both sides), INST for a PI / PII.
     */
    private static int bindOpen(Machine m, int t, int s, int nk, int ds, int d) {
        int x = Layout.HOLE;
        if (readsVar(m, t) || readsVar(m, s)) {
            x = Scope.open(m, ds);
            if (x >= SIG) {
                return x;
            }
            nk += 2;
        }
        int r;
        if ((nk & 1) != 0) {
            if (!m.ensureScratch(1)) {
                return m.fault(MachineErrors.E_MEM);
            }
            m.X[m.xp++] = x;
            r = Eval.apply(m, t, m.xp - 1, 1, d + 1);
        } else {
            r = Eval.inst(m, t, x, d + 1);
        }
        return r >= SIG ? m.sig3(r, K_CBIND_4, s, nk, x) : k_cbind4(m, s, nk, x, r, d);
    }

    /** CBIND[4](s, nk, x); r = t' = the body of t at x. The body of s at x. */
    static int k_cbind4(Machine m, int s, int nk, int x, int t2, int d) {
        int r = (nk & 1) != 0 ? Eval.apply(m, s, m.xp - 1, 1, d + 1) : Eval.inst(m, s, x, d + 1);
        return r >= SIG ? m.sig2(r, K_CBIND_5, nk, t2) : k_cbind5(m, nk, t2, r, d);
    }

    /** CBIND[5](nk, t'); r = s'. Both are again binders of the starting kind: next binder; else compare the bodies. */
    static int k_cbind5(Machine m, int nk, int t2, int s2, int d) {
        int kt = Values.kind(m, t2);
        int ks = Values.kind(m, s2);
        if ((nk & 1) != 0) {
            m.xp--; // the scratch word of the two APPLYs
            if (kt == Layout.LAM && ks == Layout.LAM) {
                return bindNext(m, nk, t2, s2);
            }
        } else if (isPiKind(kt) && isPiKind(ks)) {
            return bindNext(m, nk, t2, s2);
        }
        return bindEnd(m, nk, t2, s2, d);
    }

    private static int bindNext(Machine m, int nk, int t2, int s2) {
        m.A2 = t2;
        m.A3 = s2;
        m.x4.r3 = nk;
        return AGAIN;
    }

    /** {@code r = CONV(t, s); CLOSE n; ret r}: a tail call when no binder is owned. */
    private static int bindEnd(Machine m, int nk, int t2, int s2, int d) {
        int n = nk >>> 1;
        if (n == 0) {
            return conv(m, t2, s2, d + 1);
        }
        int r = conv(m, t2, s2, d + 1);
        return r >= SIG ? m.sig1(r, K_CBIND_6, n) : k_cbind6(m, n, r, d);
    }

    /** CBIND[6](n); r = CONV(bodies). */
    static int k_cbind6(Machine m, int n, int r, int d) {
        Scope.close(m, n);
        return r;
    }

    // ---------------------------------------------------------------------------------------------------
    // LAZYD
    // ---------------------------------------------------------------------------------------------------

    /**
     * LAZYD(tn, sn) (ISA 4.4.2): the official {@code lazy_delta_reduction} (R02 8.4): OFFSET, NATFOLD, unfold by hint
     * order, same-head argument comparison, quick tests; one tick per iteration. Leaves the final pair in
     * {@code m.A2} (tn) and {@code m.A3} (sn), which the caller reads before it calls anything (H7).
     * Entry frame: LAZYD[0](a, b).
     * @return T, F, UNDEF, or a signal
     */
    static int lazyd(Machine m, int a, int b, int d) {
        if (lazyInert(m, a, b)) {
            m.A2 = a; // leaf: neither OFFSET nor NATFOLD nor an unfolding applies (the official loop returns undef)
            m.A3 = b;
            return UNDEF;
        }
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig;
        }
        if (m.stats) {
            m.call(Op.LAZYD);
        }
        if (d > m.hostDepth) {
            return m.spill2(Op.K_LAZYD, a, b);
        }
        return lazydLoop(m, a, b, d);
    }

    /** bit 0: delta class (definition or theorem head); bit 1: Nat-like (OFFSET may apply); bit 2: NATFOLD may apply. */
    private static int lazyClass(Machine m, int v) {
        if (v < 0) {
            return 2;
        }
        int[] vh = m.vh;
        int o = v << Layout.REC_SHIFT;
        int w = vh[o];
        switch (w >>> Layout.KIND_SHIFT) {
            case Layout.NBIG:
                return 2;
            case Layout.NSUC:
                return 2 | 4;
            case Layout.CONST: {
                int hc = hclass(w);
                return hc == Layout.HC_DEFN ? 1 : hc == Layout.HC_CTOR ? 2 : 0; // a constructor may be Nat.zero
            }
            case Layout.NEU: {
                int c = hclass(w) == Layout.HC_DEFN ? 1 : 0;
                if ((w & Layout.AUX_MASK) == 2) {
                    int hw = vh[vh[o + Layout.A] << Layout.REC_SHIFT];
                    if ((hw >>> Layout.KIND_SHIFT) == Layout.CONST && (hw & Layout.CONST_NATOP_MASK) >= NatOp.ADD) {
                        c |= 4;
                    }
                }
                return c;
            }
            default:
                return 0;
        }
    }

    /** True if no state of LAZYD can do anything with the pair: l0 needs two Nat-like sides, l1 a foldable one, l2 a delta head. */
    private static boolean lazyInert(Machine m, int a, int b) {
        int ca = lazyClass(m, a);
        int cb = lazyClass(m, b);
        return ((ca | cb) & 5) == 0 && (ca & cb & 2) == 0;
    }

    /** The lazy delta loop; one iteration = l0, l1, l2, l3 of ISA 4.4.2. */
    private static int lazydLoop(Machine m, int tn, int sn, int d) {
        for (;;) {
            int r = lazydOffset(m, tn, sn, d);
            if (r != AGAIN) {
                return r;
            }
            tn = m.A2;
            sn = m.A3;
        }
    }

    /** An iteration that was resumed from a frame ended with {@code r}. */
    private static int lazydBack(Machine m, int r) {
        return r != AGAIN ? r : lazydLoop(m, m.A2, m.A3, 0);
    }

    /** l0: {@code is_def_eq_offset} (R02 8.4); OFFSET needs both sides Nat-like (zero / successor forms). */
    private static int lazydOffset(Machine m, int tn, int sn, int d) {
        if ((lazyClass(m, tn) & lazyClass(m, sn) & 2) != 0) {
            int r = NatRed.offset(m, tn, sn, d + 1);
            return r >= SIG ? m.sig2(r, K_LAZYD_1, tn, sn) : k_lazyd1(m, tn, sn, r, d);
        }
        return lazydFold(m, tn, sn, d);
    }

    /** LAZYD[1](tn, sn); r = OFFSET: T and F are final. */
    static int k_lazyd1(Machine m, int tn, int sn, int r, int d) {
        if (r != UNDEF) {
            m.A2 = tn;
            m.A3 = sn;
            return r;
        }
        return lazydFold(m, tn, sn, d);
    }

    /**
     * l1: {@code (!hasFVar(tn) && !hasFVar(sn)) || eager_reduce}: {@code reduce_nat(tn)}, else {@code reduce_nat(sn)}
     * (R02 8.4; section 10 row 36: let variables count as free variables, the flag OPEN).
     */
    private static int lazydFold(Machine m, int tn, int sn, int d) {
        if (m.ER != 0 || ((Values.flags(m, tn) | Values.flags(m, sn)) & Layout.FL_OPEN) == 0) {
            if ((lazyClass(m, tn) & 4) != 0) {
                int v = foldCall(m, tn, d);
                return v >= SIG ? m.sig2(v, K_LAZYD_2, tn, sn) : k_lazyd2(m, tn, sn, v, d);
            }
            if ((lazyClass(m, sn) & 4) != 0) {
                int v = foldCall(m, sn, d);
                return v >= SIG ? m.sig2(v, K_LAZYD_4, tn, sn) : k_lazyd4(m, tn, sn, v, d);
            }
        }
        return lazydStep(m, tn, sn, d);
    }

    /**
     * {@code reduce_nat(x)} (R02 7.6) as a sub-judgment: the binary functions are NATFOLD; the unary case
     * {@code Nat.succ a} is an NSUC cell, whose WHNF is the literal exactly when the official {@code reduce_nat} fires
     * (ISA 4.3.4).
     */
    private static int foldCall(Machine m, int x, int d) {
        return Values.kind(m, x) == Layout.NSUC ? Reduce.whnf(m, x, d + 1) : NatRed.natFold(m, x, d + 1);
    }

    /** The result of {@link #foldCall} as a {@code reduce_nat} result: the literal (or Bool constant), or NONE. */
    private static int foldResult(Machine m, int x, int v) {
        if (Values.kind(m, x) != Layout.NSUC) {
            return v;
        }
        return v < 0 || Values.kind(m, v) == Layout.NBIG ? v : NONE;
    }

    /** LAZYD[2](tn, sn); r = reduce_nat(tn). Some v: {@code return is_def_eq_core(v, sn)}. */
    static int k_lazyd2(Machine m, int tn, int sn, int v, int d) {
        v = foldResult(m, tn, v);
        if (v != NONE) {
            int r = conv(m, v, sn, d + 1);
            return r >= SIG ? m.sig2(r, K_LAZYD_3, tn, sn) : k_lazyd3(m, tn, sn, r, d);
        }
        if ((lazyClass(m, sn) & 4) != 0) {
            int u = foldCall(m, sn, d);
            return u >= SIG ? m.sig2(u, K_LAZYD_4, tn, sn) : k_lazyd4(m, tn, sn, u, d);
        }
        return lazydStep(m, tn, sn, d);
    }

    /** LAZYD[3](tn, sn); r = the comparison after a Nat fold: final. The pair is left in A2 / A3 after the call. */
    static int k_lazyd3(Machine m, int tn, int sn, int r, int d) {
        m.A2 = tn;
        m.A3 = sn;
        return r;
    }

    /** LAZYD[4](tn, sn); r = reduce_nat(sn). Some v: {@code return is_def_eq_core(tn, v)}. */
    static int k_lazyd4(Machine m, int tn, int sn, int v, int d) {
        v = foldResult(m, sn, v);
        if (v != NONE) {
            int r = conv(m, tn, v, d + 1);
            return r >= SIG ? m.sig2(r, K_LAZYD_3, tn, sn) : k_lazyd3(m, tn, sn, r, d);
        }
        return lazydStep(m, tn, sn, d);
    }

    /** l2, l3: one {@code lazy_delta_reduction_step}: AGAIN (Continue), T (Equal), F (Diff) or UNDEF (Unknown); the pair is in A2 / A3. */
    private static int lazydStep(Machine m, int tn, int sn, int d) {
        int r = lstep(m, tn, sn, d + 1);
        return r >= SIG ? m.sig0(r, K_LAZYD_5) : r;
    }

    // ---------------------------------------------------------------------------------------------------
    // LSTEP: one lazy_delta_reduction_step
    // ---------------------------------------------------------------------------------------------------

    /**
     * LSTEP(tn, sn) (ISA 4.4.2 l2, l3): the official {@code lazy_delta_reduction_step} (R02 8.4). The pair after the
     * step is left in A2 / A3. Entry frame: LSTEP[0](tn, sn).
     * @return AGAIN (Continue), T (Equal), F (Diff), UNDEF (Unknown: neither side unfolds), or a signal
     */
    static int lstep(Machine m, int tn, int sn, int d) {
        int dt = deltaRow(m, tn);
        int ds = deltaRow(m, sn);
        if ((dt | ds) == 0) {
            m.A2 = tn; // leaf: neither is delta
            m.A3 = sn;
            return UNDEF;
        }
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig;
        }
        if (m.stats) {
            m.call(LSTEP);
        }
        if (d > m.hostDepth) {
            return m.spill2(K_LSTEP, tn, sn);
        }
        if (ds == 0) {
            return lstepOnlyT(m, tn, sn, d);
        }
        if (dt == 0) {
            return lstepOnlyS(m, tn, sn, d);
        }
        return lstepBoth(m, tn, sn, dt, ds, d);
    }

    /** A PROJ record, or an application whose head is one: the official {@code is_proj(get_app_fn(e))}. */
    private static boolean projHead(Machine m, int v) {
        if (v < 0) {
            return false;
        }
        int w = m.vh[v << Layout.REC_SHIFT];
        int k = w >>> Layout.KIND_SHIFT;
        return k == Layout.PROJ || (k == Layout.NEU && hclass(w) == Layout.HC_PROJ);
    }

    /**
     * Only tn unfolds. If sn is a projection application that full {@code whnf_core} can reduce, reduce it instead of
     * unfolding the other side ({@code try_unfold_proj_app}, R02 8.4 "only dt").
     */
    private static int lstepOnlyT(Machine m, int tn, int sn, int d) {
        if (projHead(m, sn)) {
            int x = Reduce.core(m, sn, Op.FULL, d + 1);
            return x >= SIG ? m.sig2(x, K_LSTEP_1, tn, sn) : k_lstep1(m, tn, sn, x, d);
        }
        if ((m.sw & Op.SW_LAZY_EVAL) != 0 && Reduce.whnfFast(m, sn) == sn) {
            return evalT(m, tn, sn, d);
        }
        return unfoldT(m, tn, sn, 0, d);
    }

    /**
     * [D26, {@code Op.SW_LAZY_EVAL}] Only tn unfolds and sn is weak-head normal (flag or memo; not a projection): the
     * official loop would now unfold tn step by step, {@code whnf_core(unfold_definition(tn))}, until it no longer
     * unfolds, with sn fixed; no same-head state can occur and an intermediate tn (delta-headed) is never
     * identical to sn. The composition of those steps is taken at once: {@code tn = WHNF(tn)}, then the quick test
     * of the step. Sound: WHNF is a composition of official reduction steps. A WHNF that does not move tn (never
     * for a delta head) falls back to the single official step.
     */
    private static int evalT(Machine m, int tn, int sn, int d) {
        int w = Reduce.whnf(m, tn, d + 1);
        if (w == tn) {
            return unfoldT(m, tn, sn, 0, d);
        }
        return w >= SIG ? m.sig2(w, K_LSTEP_5, sn, 0) : lquick(m, w, sn, d);
    }

    /** LSTEP[1](tn, sn); r = CORE(sn, full). */
    static int k_lstep1(Machine m, int tn, int sn, int x, int d) {
        return x != sn ? lquick(m, tn, x, d) : unfoldT(m, tn, sn, 0, d);
    }

    /** Only sn unfolds: symmetric. */
    private static int lstepOnlyS(Machine m, int tn, int sn, int d) {
        if (projHead(m, tn)) {
            int x = Reduce.core(m, tn, Op.FULL, d + 1);
            return x >= SIG ? m.sig2(x, K_LSTEP_2, tn, sn) : k_lstep2(m, tn, sn, x, d);
        }
        if ((m.sw & Op.SW_LAZY_EVAL) != 0 && Reduce.whnfFast(m, tn) == tn) {
            return evalS(m, tn, sn, d);
        }
        return unfoldS(m, tn, sn, d);
    }

    /** [D26, {@code Op.SW_LAZY_EVAL}] Only sn unfolds and tn is weak-head normal: {@code sn = WHNF(sn)} (see {@link #evalT}). */
    private static int evalS(Machine m, int tn, int sn, int d) {
        int w = Reduce.whnf(m, sn, d + 1);
        if (w == sn) {
            return unfoldS(m, tn, sn, d);
        }
        return w >= SIG ? m.sig1(w, K_LSTEP_7, tn) : lquick(m, tn, w, d);
    }

    /** LSTEP[2](tn, sn); r = CORE(tn, full). */
    static int k_lstep2(Machine m, int tn, int sn, int x, int d) {
        return x != tn ? lquick(m, x, sn, d) : unfoldS(m, tn, sn, d);
    }

    /**
     * Both unfold: {@code compare(hints)}: the greater height first, abbrev before regular, opaque last. Equal: the
     * same regular definition applied on both sides and the pair not in FAILED (m_failure): compare the levels and
     * the arguments (CARGS, SAMEDEF: last to first) before unfolding; a comparison that completed with F is cached
     * (FAILED; ISA 5.4). Then unfold both.
     */
    private static int lstepBoth(Machine m, int tn, int sn, int dt, int ds, int d) {
        int ht = hintOf(m, dt);
        int c = compareHints(ht, hintOf(m, ds));
        if (c < 0) {
            return unfoldT(m, tn, sn, 0, d);
        }
        if (c > 0) {
            return unfoldS(m, tn, sn, d);
        }
        if (dt == ds && hintIsRegular(ht) && Values.kind(m, tn) == Layout.NEU && Values.kind(m, sn) == Layout.NEU
                && (Pairs.status(m, tn, sn) & Layout.FAILED) == 0) {
            if (Levels.listsEq(m, headLevels(m, tn), headLevels(m, sn))) {
                int r = Cargs.cargs(m, tn, sn, Op.POL_SAMEDEF, d + 1);
                return r >= SIG ? m.sig2(r, K_LSTEP_3, tn, sn) : k_lstep3(m, tn, sn, r, d);
            }
            Pairs.add(m, tn, sn, Layout.FAILED);
        }
        return unfoldT(m, tn, sn, 1, d);
    }

    /** LSTEP[3](tn, sn); r = CARGS(tn, sn, SAMEDEF): T is Equal; a completed F enters FAILED, then both unfold. */
    static int k_lstep3(Machine m, int tn, int sn, int r, int d) {
        if (r == T) {
            m.A2 = tn;
            m.A3 = sn;
            return T;
        }
        Pairs.add(m, tn, sn, Layout.FAILED);
        return unfoldT(m, tn, sn, 1, d);
    }

    /**
     * {@code tn = whnf_core(unfold_definition(tn), cheap_proj = true)}; {@code both}: sn unfolds afterwards. The delta
     * step is {@code Rules.unfoldLazy} (M3; request of M7, 14:17Z): {@code Reduce.delta}, except that with
     * {@code casetrees,m7a} a head of shape CASESON on a constructor major gives the alternative at once: for that
     * shape the iota is part of the whnf_core of this very step, so no state of the official loop is skipped.
     */
    private static int unfoldT(Machine m, int tn, int sn, int both, int d) {
        int u = Rules.unfoldLazy(m, tn, d + 1);
        return u >= SIG ? m.sig2(u, K_LSTEP_4, sn, both) : k_lstep4(m, sn, both, u, d);
    }

    /** LSTEP[4](sn, both); r = DELTA(tn). */
    static int k_lstep4(Machine m, int sn, int both, int u, int d) {
        if (u == NONE) {
            return m.fault(MachineErrors.E_INTERNAL); // is_delta held, so DELTA must unfold (fail closed)
        }
        int x = Reduce.core(m, u, Op.CHEAP, d + 1);
        return x >= SIG ? m.sig2(x, K_LSTEP_5, sn, both) : k_lstep5(m, sn, both, x, d);
    }

    /** LSTEP[5](sn, both); r = the new tn. */
    static int k_lstep5(Machine m, int sn, int both, int tn, int d) {
        return both != 0 ? unfoldS(m, tn, sn, d) : lquick(m, tn, sn, d);
    }

    /** {@code sn = whnf_core(unfold_definition(sn), cheap_proj = true)}. */
    private static int unfoldS(Machine m, int tn, int sn, int d) {
        int u = Rules.unfoldLazy(m, sn, d + 1);
        return u >= SIG ? m.sig1(u, K_LSTEP_6, tn) : k_lstep6(m, tn, u, d);
    }

    /** LSTEP[6](tn); r = DELTA(sn). */
    static int k_lstep6(Machine m, int tn, int u, int d) {
        if (u == NONE) {
            return m.fault(MachineErrors.E_INTERNAL);
        }
        int x = Reduce.core(m, u, Op.CHEAP, d + 1);
        return x >= SIG ? m.sig1(x, K_LSTEP_7, tn) : k_lstep7(m, tn, x, d);
    }

    /** LSTEP[7](tn); r = the new sn. */
    static int k_lstep7(Machine m, int tn, int sn, int d) {
        return lquick(m, tn, sn, d);
    }

    /** l3: {@code quick_is_def_eq(tn, sn)} after the step: true = Equal, false = Diff, undef = Continue. */
    private static int lquick(Machine m, int tn, int sn, int d) {
        int q = quick(m, tn, sn);
        if (q == BIND) {
            int r = cbind(m, tn, sn, d + 1);
            return r >= SIG ? m.sig2(r, K_LSTEP_8, tn, sn) : k_lstep8(m, tn, sn, r, d);
        }
        m.A2 = tn;
        m.A3 = sn;
        return q == MISS ? AGAIN : q;
    }

    /** LSTEP[8](tn, sn); r = CBIND. The pair is left in A2 / A3 after the call. */
    static int k_lstep8(Machine m, int tn, int sn, int r, int d) {
        m.A2 = tn;
        m.A3 = sn;
        return r;
    }

    // ---------------------------------------------------------------------------------------------------
    // PROJD
    // ---------------------------------------------------------------------------------------------------

    /**
     * PROJD(a, b, S, i) (ISA 4.4): official {@code lazy_delta_proj_reduction} (R02 8.5).
     * Entry frame: PROJD[0](a, b, structRow, field).
     * @return T, F, or a signal
     */
    static int projd(Machine m, int a, int b, int structRow, int field, int d) {
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig;
        }
        if (m.stats) {
            m.call(Op.PROJD);
        }
        if (d > m.hostDepth) {
            return m.spill4(Op.K_PROJD, a, b, structRow, field);
        }
        return projdLoop(m, a, b, structRow, field, d);
    }

    /** The structures are unfolded lazily and in lockstep until neither unfolds or the step decides. */
    private static int projdLoop(Machine m, int a, int b, int structRow, int field, int d) {
        for (;;) {
            int r = lstep(m, a, b, d + 1);
            if (r >= SIG) {
                return m.sig2(r, K_PROJD_1, structRow, field);
            }
            if (r != AGAIN) {
                return projdEnd(m, structRow, field, r, d);
            }
            a = m.A2;
            b = m.A3;
        }
    }

    /** PROJD[1](S, i); r = LSTEP; the pair is in A2 / A3. */
    static int k_projd1(Machine m, int structRow, int field, int r, int d) {
        return r == AGAIN ? projdLoop(m, m.A2, m.A3, structRow, field, d) : projdEnd(m, structRow, field, r, d);
    }

    /**
     * Equal: T. Unknown or Diff: the fields if both structures are constructor applications of S
     * ({@code reduce_proj_core}), else the structures themselves. A string literal is first brought into
     * constructor form (R02 7.2).
     */
    private static int projdEnd(Machine m, int structRow, int field, int r, int d) {
        int a = m.A2;
        int b = m.A3;
        if (r == T) {
            return T;
        }
        if (Values.kind(m, a) == Layout.STR) {
            int v = StrRed.strView(m, a);
            if (v >= SIG) {
                return v;
            }
            int w = Reduce.whnf(m, v, d + 1);
            return w >= SIG ? m.sig4(w, K_PROJD_2, a, b, structRow, field) : k_projd2(m, a, b, structRow, field, w, d);
        }
        return k_projd2(m, a, b, structRow, field, a, d);
    }

    /** PROJD[2](a, b, S, i); r = the constructor form of a (a itself unless it is a string literal). */
    static int k_projd2(Machine m, int a, int b, int structRow, int field, int xa, int d) {
        int fa = ctorField(m, xa, structRow, field);
        if (fa == NONE) {
            return conv(m, a, b, d + 1);
        }
        if (Values.kind(m, b) == Layout.STR) {
            return projdStr(m, a, b, structRow, field, fa, d);
        }
        return k_projd3(m, a, b, structRow, field, fa, b, d);
    }

    private static int projdStr(Machine m, int a, int b, int structRow, int field, int fa, int d) {
        int v = StrRed.strView(m, b);
        if (v >= SIG) {
            return v;
        }
        int w = Reduce.whnf(m, v, d + 1);
        return w >= SIG ? m.sig5(w, K_PROJD_3, a, b, structRow, field, fa) : k_projd3(m, a, b, structRow, field, fa, w, d);
    }

    /** PROJD[3](a, b, S, i, fa); r = the constructor form of b. */
    static int k_projd3(Machine m, int a, int b, int structRow, int field, int fa, int xb, int d) {
        int fb = ctorField(m, xb, structRow, field);
        return fb == NONE ? conv(m, a, b, d + 1) : conv(m, fa, fb, d + 1);
    }

    // ---------------------------------------------------------------------------------------------------
    // PIRR
    // ---------------------------------------------------------------------------------------------------

    /**
     * PIRR(a, b) (ISA 4.4.1 state c4): official {@code is_def_eq_proof_irrel} (R02 8.3).
     * Entry frame: PIRR[0](a, b).
     * @return T, F, UNDEF (not proofs), or a signal
     */
    static int pirr(Machine m, int a, int b, int d) {
        if (a < 0 || ((m.vh[a << Layout.REC_SHIFT] >>> W0_PROOF_SHIFT) & 3) == Layout.TRI_NO) {
            return UNDEF; // flag PROOF = 1: is_prop(infer_type(a)) is known to be false; a Nat literal is not a proof
        }
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig;
        }
        if (m.stats) {
            m.call(Op.PIRR);
        }
        if (d > m.hostDepth) {
            return m.spill2(Op.K_PIRR, a, b);
        }
        int r = Infer.vtype(m, a, d + 1);
        return r >= SIG ? m.sig2(r, K_PIRR_1, a, b) : k_pirr1(m, a, b, r, d);
    }

    /** PIRR[1](a, b); r = Tt = VTYPE(a). {@code is_prop(Tt)}: a stuck sort fails, it is never "not a proposition". */
    static int k_pirr1(Machine m, int a, int b, int ta, int d) {
        if (((m.vh[a << Layout.REC_SHIFT] >>> W0_PROOF_SHIFT) & 3) == Layout.TRI_YES) {
            // flag PROOF = 2: is_prop(Tt) is known to hold (memoised by an earlier PIRR, or static: B7)
            int r = Infer.vtype(m, b, d + 1);
            return r >= SIG ? m.sig1(r, K_PIRR_3, ta) : k_pirr3(m, ta, r, d);
        }
        int p = Infer.isprop(m, ta, d + 1);
        return p >= SIG ? m.sig3(p, K_PIRR_2, a, b, ta) : k_pirr2(m, a, b, ta, p, d);
    }

    /** PIRR[2](a, b, Tt); r = ISPROP(Tt). The answer is memoised in the flag PROOF of a (I-MEMO: on completion). */
    static int k_pirr2(Machine m, int a, int b, int ta, int p, int d) {
        int o = a << Layout.REC_SHIFT;
        m.vh[o] = m.vh[o] & ~(3 << W0_PROOF_SHIFT) | (p == T ? Layout.TRI_YES : Layout.TRI_NO) << W0_PROOF_SHIFT;
        if (p != T) {
            return UNDEF;
        }
        int r = Infer.vtype(m, b, d + 1);
        return r >= SIG ? m.sig1(r, K_PIRR_3, ta) : k_pirr3(m, ta, r, d);
    }

    /** PIRR[3](Tt); r = VTYPE(b). {@code is_def_eq(Tt, infer_type(b))} is the final answer, T or F. */
    static int k_pirr3(Machine m, int ta, int tb, int d) {
        return conv(m, ta, tb, d + 1);
    }

    // ---------------------------------------------------------------------------------------------------
    // ETA
    // ---------------------------------------------------------------------------------------------------

    /**
     * ETA(a, b) (ISA 4.4): a is LAM, b is not. Official: {@code try_eta_expansion_core} (R02 8.6).
     * Entry frame: ETA[0](a, b).
     * @return T, F, or a signal
     */
    static int eta(Machine m, int a, int b, int d) {
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig;
        }
        if (m.stats) {
            m.call(Op.ETA);
        }
        if (d > m.hostDepth) {
            return m.spill2(Op.K_ETA, a, b);
        }
        int r = Infer.vtype(m, b, d + 1);
        return r >= SIG ? m.sig2(r, K_ETA_1, a, b) : k_eta1(m, a, b, r, d);
    }

    /** ETA[1](a, b); r = VTYPE(b). */
    static int k_eta1(Machine m, int a, int b, int tb, int d) {
        int r = Reduce.whnf(m, tb, d + 1);
        return r >= SIG ? m.sig2(r, K_ETA_2, a, b) : k_eta2(m, a, b, r, d);
    }

    /** ETA[2](a, b); r = W = WHNF(VTYPE(b)): must be a Pi, else F (not an error). */
    static int k_eta2(Machine m, int a, int b, int w, int d) {
        if (!isPiKind(Values.kind(m, w))) {
            return F;
        }
        int r = m.vh[(a << Layout.REC_SHIFT) + Layout.M_DOM];
        if (r == 0) {
            r = Eval.dom(m, a, d + 1);
        }
        return r >= SIG ? m.sig3(r, K_ETA_3, a, b, w) : k_eta3(m, a, b, w, r, d);
    }

    /** ETA[3](a, b, W); r = DOM(a). */
    static int k_eta3(Machine m, int a, int b, int w, int da, int d) {
        int r = m.vh[(w << Layout.REC_SHIFT) + Layout.M_DOM];
        if (r == 0) {
            r = Eval.dom(m, w, d + 1);
        }
        return r >= SIG ? m.sig3(r, K_ETA_4, a, b, da) : k_eta4(m, a, b, da, r, d);
    }

    /** ETA[4](a, b, Da); r = DOM(W). The binding rule: the domains must be convertible. */
    static int k_eta4(Machine m, int a, int b, int da, int dw, int d) {
        int c = da == dw ? T : conv(m, da, dw, d + 1);
        return c >= SIG ? m.sig3(c, K_ETA_5, a, b, dw) : k_eta5(m, a, b, dw, c, d);
    }

    /** ETA[5](a, b, Dw); r = CONV(domains). OPEN x typed with the domain of b's type; the body of a at x. */
    static int k_eta5(Machine m, int a, int b, int dw, int c, int d) {
        if (c != T) {
            return F;
        }
        int x = Scope.open(m, dw);
        if (x >= SIG) {
            return x;
        }
        if (!m.ensureScratch(1)) {
            return m.fault(MachineErrors.E_MEM);
        }
        m.X[m.xp++] = x;
        int r = Eval.apply(m, a, m.xp - 1, 1, d + 1);
        return r >= SIG ? m.sig1(r, K_ETA_6, b) : k_eta6(m, b, r, d);
    }

    /** ETA[6](b); r = the body of a at x. {@code b x}. */
    static int k_eta6(Machine m, int b, int ta, int d) {
        int r = Eval.apply(m, b, m.xp - 1, 1, d + 1);
        return r >= SIG ? m.sig1(r, K_ETA_7, ta) : k_eta7(m, ta, r, d);
    }

    /** ETA[7](ta); r = b x. */
    static int k_eta7(Machine m, int ta, int sb, int d) {
        m.xp--;
        int r = conv(m, ta, sb, d + 1);
        return r >= SIG ? m.sig0(r, K_ETA_8) : k_eta8(m, r, d);
    }

    /** ETA[8](); r = CONV(body of a at x, b x). */
    static int k_eta8(Machine m, int r, int d) {
        Scope.close(m, 1);
        return r;
    }

    // ---------------------------------------------------------------------------------------------------
    // ETAS
    // ---------------------------------------------------------------------------------------------------

    /**
     * The shape test of {@code try_eta_struct_core} on its second argument (R02 8.6): a constructor applied to
     * exactly nparams + nfields arguments (zero for a bare constant) whose inductive type is a non-recursive
     * structure. The counts are the recomputed ones (R02 section 10 row 22).
     */
    static boolean etasShape(Machine m, int b) {
        if (b < 0) {
            return false;
        }
        int[] vh = m.vh;
        int o = b << Layout.REC_SHIFT;
        int w = vh[o];
        int k = w >>> Layout.KIND_SHIFT;
        if ((k != Layout.NEU && k != Layout.CONST) || hclass(w) != Layout.HC_CTOR) {
            return false;
        }
        int n = k == Layout.NEU ? w & Layout.AUX_MASK : 0;
        int ctor = vh[(k == Layout.NEU ? vh[o + Layout.A] << Layout.REC_SHIFT : o) + Layout.A];
        DeclTable dt = m.decls;
        return dt.kind(ctor) == DeclKind.CTOR && n == dt.ctorNumParams(ctor) + dt.ctorNumFields(ctor)
                && isNonRecStructure(m, dt.ctorInduct(ctor));
    }

    /** The constructor row at the head of a value that passed {@link #etasShape}. */
    private static int ctorOf(Machine m, int b) {
        int[] vh = m.vh;
        int o = b << Layout.REC_SHIFT;
        return vh[((vh[o] >>> Layout.KIND_SHIFT) == Layout.NEU ? vh[o + Layout.A] << Layout.REC_SHIFT : o) + Layout.A];
    }

    /**
     * ETAS(a, b) (ISA 4.4): b is a constructor application of a non-recursive structure; the types are compared first;
     * the constructor's prop mask (step 2) is applied only after the type comparison. Official:
     * {@code try_eta_struct_core} (R02 8.6). Entry frame: ETAS[0](a, b).
     * @return T, F, or a signal
     */
    static int etas(Machine m, int a, int b, int d) {
        if (!etasShape(m, b)) {
            return F; // leaf: not a fully applied constructor of a structure
        }
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig;
        }
        if (m.stats) {
            m.call(Op.ETAS);
        }
        if (d > m.hostDepth) {
            return m.spill2(Op.K_ETAS, a, b);
        }
        int r = Infer.vtype(m, a, d + 1);
        return r >= SIG ? m.sig2(r, K_ETAS_1, a, b) : k_etas1(m, a, b, r, d);
    }

    /** ETAS[1](a, b); r = VTYPE(a). */
    static int k_etas1(Machine m, int a, int b, int ta, int d) {
        int r = Infer.vtype(m, b, d + 1);
        return r >= SIG ? m.sig3(r, K_ETAS_2, a, b, ta) : k_etas2(m, a, b, ta, r, d);
    }

    /** ETAS[2](a, b, Ta); r = VTYPE(b). {@code is_def_eq(infer_type(t), infer_type(s))} must hold. */
    static int k_etas2(Machine m, int a, int b, int ta, int tb, int d) {
        int c = ta == tb ? T : conv(m, ta, tb, d + 1);
        return c >= SIG ? m.sig2(c, K_ETAS_3, a, b) : k_etas3(m, a, b, c, d);
    }

    /** ETAS[3](a, b); r = CONV(types). Step 2: the fact record of the constructor is derived if it does not exist. */
    static int k_etas3(Machine m, int a, int b, int c, int d) {
        if (c != T) {
            return F;
        }
        if (skipArgs(m)) {
            int row = ctorOf(m, b);
            if (Facts.facts(m, row) == 0) {
                int index = Facts.sigd(m, row, d + 1);
                return index >= SIG ? m.sig2(index, K_ETAS_5, a, b) : k_etas5(m, a, b, index, d);
            }
        }
        return etasFields(m, a, b, 0, d);
    }

    /** ETAS[5](a, b); r = SIGD of the constructor. */
    static int k_etas5(Machine m, int a, int b, int index, int d) {
        return etasFields(m, a, b, 0, d);
    }

    /**
     * For each field i from {@code from}: {@code is_def_eq(proj I i a, args(b)[nparams + i])}; v0 skips no field.
     * Step 2 ({@code Op.SW_FACTS}; B3, S5 of R05 section 4): a field in the prop mask of the constructor at the
     * levels of b is not compared. The mask is applied only here, after the type comparison: the parameters agree
     * (the types are convertible) and the earlier fields agree (compared, or skipped proofs), so the two fields are
     * proofs of convertible propositions (the agreement rule of ISA 6.1 with an empty {@code differ}).
     */
    private static int etasFields(Machine m, int a, int b, int from, int d) {
        int ctor = ctorOf(m, b);
        DeclTable dt = m.decls;
        int np = dt.ctorNumParams(ctor);
        int nf = dt.ctorNumFields(ctor);
        int ind = dt.ctorInduct(ctor);
        long prop = skipArgs(m) && Values.kind(m, b) == Layout.NEU ? Facts.propMask(m, Values.a(m, b)) : 0L;
        for (int i = from; i < nf; i++) {
            if (np + i < Layout.FACT_POSITIONS && (prop >>> (np + i) & 1L) != 0) {
                continue;
            }
            int p = Values.mkProj(m, ind, i, a);
            if (p >= SIG) {
                return p;
            }
            int y = Runs.arg(m, Values.b(m, b), np + i);
            if (p == y) {
                continue;
            }
            int c = conv(m, p, y, d + 1);
            if (c >= SIG) {
                return m.sig3(c, K_ETAS_4, a, b, i);
            }
            if (c != T) {
                return F;
            }
        }
        if ((prop >>> np) != 0 && (m.debug & Op.DBG_SHORTCHECK) != 0 && Debug.shortCheck(m, a, b, T) != 0) {
            return Op.FATAL;
        }
        return T;
    }

    /** ETAS[4](a, b, i); r = CONV(field i). */
    static int k_etas4(Machine m, int a, int b, int i, int c, int d) {
        return c != T ? F : etasFields(m, a, b, i + 1, d);
    }

    // ---------------------------------------------------------------------------------------------------
    // UNITL
    // ---------------------------------------------------------------------------------------------------

    /**
     * UNITL(a, b) (ISA 4.4): official {@code is_def_eq_unit_like} (R02 8.8); the types are compared
     * (bugs/unit-rec-rule). Entry frame: UNITL[0](a, b).
     * @return T, F, or a signal
     */
    static int unitl(Machine m, int a, int b, int d) {
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig;
        }
        if (m.stats) {
            m.call(Op.UNITL);
        }
        if (d > m.hostDepth) {
            return m.spill2(Op.K_UNITL, a, b);
        }
        int r = Infer.vtype(m, a, d + 1);
        return r >= SIG ? m.sig1(r, K_UNITL_1, b) : k_unitl1(m, b, r, d);
    }

    /** UNITL[1](b); r = VTYPE(a). */
    static int k_unitl1(Machine m, int b, int ta, int d) {
        int r = Reduce.whnf(m, ta, d + 1);
        return r >= SIG ? m.sig1(r, K_UNITL_2, b) : k_unitl2(m, b, r, d);
    }

    /**
     * UNITL[2](b); r = W = WHNF(VTYPE(a)). Its head must be a non-recursive structure without indices whose
     * constructor has no fields (the recomputed count: bugs/ctor-num-fields), else F.
     */
    static int k_unitl2(Machine m, int b, int w, int d) {
        if (!unitType(m, w)) {
            return F;
        }
        int r = Infer.vtype(m, b, d + 1);
        return r >= SIG ? m.sig1(r, K_UNITL_3, w) : k_unitl3(m, w, r, d);
    }

    /** UNITL[3](W); r = VTYPE(b). {@code is_def_eq_core(W, infer_type(b))} is the answer. */
    static int k_unitl3(Machine m, int w, int tb, int d) {
        return conv(m, w, tb, d + 1);
    }

    /** The head of the type value is an inductive type that is unit-like (R02 8.8). */
    private static boolean unitType(Machine m, int w) {
        if (w < 0) {
            return false;
        }
        int[] vh = m.vh;
        int o = w << Layout.REC_SHIFT;
        int w0 = vh[o];
        int k = w0 >>> Layout.KIND_SHIFT;
        if ((k != Layout.NEU && k != Layout.CONST) || hclass(w0) != Layout.HC_IND) {
            return false;
        }
        int row = vh[(k == Layout.NEU ? vh[o + Layout.A] << Layout.REC_SHIFT : o) + Layout.A];
        DeclTable dt = m.decls;
        return isNonRecStructure(m, row) && dt.ctorNumFields(dt.inductCtor(row, 0)) == 0;
    }

    // ---------------------------------------------------------------------------------------------------
    // STRX
    // ---------------------------------------------------------------------------------------------------

    /** {@code a} is a string literal and {@code b} is {@code String.ofList x} (the constant without levels, one argument). */
    static boolean strxShape(Machine m, int a, int b) {
        if (a < 0 || b < 0) {
            return false;
        }
        int[] vh = m.vh;
        int ob = b << Layout.REC_SHIFT;
        int wb = vh[ob];
        if ((vh[a << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT) != Layout.STR || (wb >>> Layout.KIND_SHIFT) != Layout.NEU
                || (wb & Layout.AUX_MASK) != 1) {
            return false;
        }
        int h = vh[ob + Layout.A] << Layout.REC_SHIFT;
        return (vh[h] >>> Layout.KIND_SHIFT) == Layout.CONST && vh[h + Layout.B] == IntLists.EMPTY
                && m.decls.special(vh[h + Layout.A]) == Specials.STRING_OF_LIST;
    }

    /**
     * STRX(a, b) (ISA 4.4): a is STR, b is {@code String.ofList x}: CONV(WHNF(STRVIEW a), b), definitive. Official:
     * {@code try_string_lit_expansion} (R02 8.7). Entry frame: STRX[0](a, b).
     * @return T, F, or a signal
     */
    static int strx(Machine m, int a, int b, int d) {
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig;
        }
        if (m.stats) {
            m.call(Op.STRX_F);
        }
        if (d > m.hostDepth) {
            return m.spill2(Op.K_STRX, a, b);
        }
        if (Values.kind(m, a) != Layout.STR) {
            return m.fault(MachineErrors.E_INTERNAL);
        }
        int v = StrRed.strView(m, a);
        if (v >= SIG) {
            return v;
        }
        int w = Reduce.whnf(m, v, d + 1);
        return w >= SIG ? m.sig1(w, K_STRX_1, b) : k_strx1(m, b, w, d);
    }

    /** STRX[1](b); r = WHNF(STRVIEW(a)). */
    static int k_strx1(Machine m, int b, int w, int d) {
        return conv(m, w, b, d + 1);
    }

    // ---------------------------------------------------------------------------------------------------
    // The driver's part
    // ---------------------------------------------------------------------------------------------------

    /**
     * The family's part of the driver (H6): one case per frame key, calling the step with the payload words
     * as arguments, {@code r} as the result register and d = 0. Split by op (H10: at most 50 cases per method).
     */
    static int resume(Machine m, int key, int[] w, int p, int r) {
        switch (key >>> 4) {
            case Op.CONV:
                return resumeConv(m, key, w, p, r);
            case Op.CONV2:
                return resumeConv2(m, key, w, p, r);
            case Op.LAZYD:
            case LSTEP:
                return resumeLazy(m, key, w, p, r);
            case Op.CBIND:
            case Op.PROJD:
            case Op.PIRR:
                return resumeBind(m, key, w, p, r);
            default:
                return resumeRest(m, key, w, p, r);
        }
    }

    private static int resumeConv(Machine m, int key, int[] w, int p, int r) {
        switch (key) {
            case Op.K_CONV:
                return conv(m, w[p], w[p + 1], 0);
            case K_CONV_1:
                return k_conv1(m, w[p], r, 0);
            case K_CONV_2:
                return k_conv2(m, w[p], r, 0);
            case K_CONV_3:
                return convBack(m, w[p], w[p + 1], k_conv3(m, w[p], w[p + 1], w[p + 2], w[p + 3], r, 0));
            case K_CONV_4:
                return convBack(m, w[p], w[p + 1], k_conv4(m, w[p], w[p + 1], w[p + 2], w[p + 3], r, 0));
            case K_CONV_5:
                return convBack(m, w[p], w[p + 1], k_conv5(m, w[p], w[p + 1], w[p + 2], w[p + 3], w[p + 4], r, 0));
            case K_CONV_6:
                return convBack(m, w[p], w[p + 1], k_conv6(m, w[p], w[p + 1], w[p + 2], w[p + 3], r, 0));
            case K_CONV_7:
                return convBack(m, w[p], w[p + 1], k_conv7(m, w[p], w[p + 1], r, 0));
            case K_CONV_8:
                return convBack(m, w[p], w[p + 1], k_conv8(m, w[p], w[p + 1], w[p + 2], w[p + 3], r, 0));
            case K_CONV_9:
                return convBack(m, w[p], w[p + 1], k_conv9(m, w[p], w[p + 1], w[p + 2], w[p + 3], r, 0));
            case K_CONV_10:
                return convBack(m, w[p], w[p + 1], k_conv10(m, w[p], w[p + 1], w[p + 2], w[p + 3], w[p + 4], r, 0));
            default:
                return m.fault(MachineErrors.E_INTERNAL);
        }
    }

    private static int resumeConv2(Machine m, int key, int[] w, int p, int r) {
        switch (key) {
            case K_CONV2_REC:
                return k_convRec(m, w[p], w[p + 1], w[p + 2], w[p + 3], r, 0);
            case K_CONV2_1:
                return convBack(m, w[p], w[p + 1], k_conv2_1(m, w[p], w[p + 1], w[p + 2], w[p + 3], r, 0));
            case K_CONV2_2:
                return convBack(m, w[p], w[p + 1], k_conv2_2(m, w[p], w[p + 1], w[p + 2], w[p + 3], r, 0));
            case K_CONV2_3:
                return convBack(m, w[p], w[p + 1], k_conv2_3(m, w[p], w[p + 1], w[p + 2], w[p + 3], r, 0));
            case K_CONV2_4:
                return convBack(m, w[p], w[p + 1], k_conv2_4(m, w[p], w[p + 1], w[p + 2], w[p + 3], r, 0));
            case K_CONV2_5:
                return convBack(m, w[p], w[p + 1], k_conv2_5(m, w[p], w[p + 1], w[p + 2], w[p + 3], r, 0));
            case K_CONV2_6:
                return k_conv2_6(m, w[p], w[p + 1], r, 0);
            case K_CONV2_7:
                return convBack(m, w[p], w[p + 1], k_conv2_7(m, w[p], w[p + 1], w[p + 2], w[p + 3], r, 0));
            case K_CONV2_8:
                return convBack(m, w[p], w[p + 1], k_conv2_8(m, w[p], w[p + 1], w[p + 2], w[p + 3], r, 0));
            default:
                return m.fault(MachineErrors.E_INTERNAL);
        }
    }

    private static int resumeLazy(Machine m, int key, int[] w, int p, int r) {
        switch (key) {
            case Op.K_LAZYD:
                return lazyd(m, w[p], w[p + 1], 0);
            case K_LAZYD_1:
                return lazydBack(m, k_lazyd1(m, w[p], w[p + 1], r, 0));
            case K_LAZYD_2:
                return lazydBack(m, k_lazyd2(m, w[p], w[p + 1], r, 0));
            case K_LAZYD_3:
                return k_lazyd3(m, w[p], w[p + 1], r, 0);
            case K_LAZYD_4:
                return lazydBack(m, k_lazyd4(m, w[p], w[p + 1], r, 0));
            case K_LAZYD_5:
                return lazydBack(m, r);
            case K_LSTEP:
                return lstep(m, w[p], w[p + 1], 0);
            case K_LSTEP_1:
                return k_lstep1(m, w[p], w[p + 1], r, 0);
            case K_LSTEP_2:
                return k_lstep2(m, w[p], w[p + 1], r, 0);
            case K_LSTEP_3:
                return k_lstep3(m, w[p], w[p + 1], r, 0);
            case K_LSTEP_4:
                return k_lstep4(m, w[p], w[p + 1], r, 0);
            case K_LSTEP_5:
                return k_lstep5(m, w[p], w[p + 1], r, 0);
            case K_LSTEP_6:
                return k_lstep6(m, w[p], r, 0);
            case K_LSTEP_7:
                return k_lstep7(m, w[p], r, 0);
            case K_LSTEP_8:
                return k_lstep8(m, w[p], w[p + 1], r, 0);
            default:
                return m.fault(MachineErrors.E_INTERNAL);
        }
    }

    private static int resumeBind(Machine m, int key, int[] w, int p, int r) {
        switch (key) {
            case Op.K_CBIND:
                return cbind(m, w[p], w[p + 1], 0);
            case K_CBIND_1:
                return bindBack(m, k_cbind1(m, w[p], w[p + 1], w[p + 2], r, 0));
            case K_CBIND_2:
                return bindBack(m, k_cbind2(m, w[p], w[p + 1], w[p + 2], w[p + 3], r, 0));
            case K_CBIND_3:
                return bindBack(m, k_cbind3(m, w[p], w[p + 1], w[p + 2], w[p + 3], r, 0));
            case K_CBIND_4:
                return bindBack(m, k_cbind4(m, w[p], w[p + 1], w[p + 2], r, 0));
            case K_CBIND_5:
                return bindBack(m, k_cbind5(m, w[p], w[p + 1], r, 0));
            case K_CBIND_6:
                return k_cbind6(m, w[p], r, 0);
            case Op.K_PROJD:
                return projd(m, w[p], w[p + 1], w[p + 2], w[p + 3], 0);
            case K_PROJD_1:
                return k_projd1(m, w[p], w[p + 1], r, 0);
            case K_PROJD_2:
                return k_projd2(m, w[p], w[p + 1], w[p + 2], w[p + 3], r, 0);
            case K_PROJD_3:
                return k_projd3(m, w[p], w[p + 1], w[p + 2], w[p + 3], w[p + 4], r, 0);
            case Op.K_PIRR:
                return pirr(m, w[p], w[p + 1], 0);
            case K_PIRR_1:
                return k_pirr1(m, w[p], w[p + 1], r, 0);
            case K_PIRR_2:
                return k_pirr2(m, w[p], w[p + 1], w[p + 2], r, 0);
            case K_PIRR_3:
                return k_pirr3(m, w[p], r, 0);
            default:
                return m.fault(MachineErrors.E_INTERNAL);
        }
    }

    private static int resumeRest(Machine m, int key, int[] w, int p, int r) {
        switch (key) {
            case Op.K_CARGS:
                return Cargs.cargs(m, w[p], w[p + 1], w[p + 2], 0);
            case Cargs.K_CARGS_1:
                return Cargs.k_cargs1(m, w[p], w[p + 1], w[p + 2], w[p + 3], w[p + 4], w[p + 5], r, 0);
            case Cargs.K_CARGS_2:
                return Cargs.k_cargs2(m, w[p], w[p + 1], w[p + 2], w[p + 3], w[p + 4], r, 0);
            case Cargs.K_CARGS_3:
                return Cargs.k_cargs3(m, w[p], w[p + 1], w[p + 2], r, 0);
            case Op.K_CCLO:
                return cclo(m, w[p], w[p + 1], 0);
            case K_CCLO_1:
                return k_cclo1(m, w[p], w[p + 1], w[p + 2], r, 0);
            case Op.K_ETA:
                return eta(m, w[p], w[p + 1], 0);
            case K_ETA_1:
                return k_eta1(m, w[p], w[p + 1], r, 0);
            case K_ETA_2:
                return k_eta2(m, w[p], w[p + 1], r, 0);
            case K_ETA_3:
                return k_eta3(m, w[p], w[p + 1], w[p + 2], r, 0);
            case K_ETA_4:
                return k_eta4(m, w[p], w[p + 1], w[p + 2], r, 0);
            case K_ETA_5:
                return k_eta5(m, w[p], w[p + 1], w[p + 2], r, 0);
            case K_ETA_6:
                return k_eta6(m, w[p], r, 0);
            case K_ETA_7:
                return k_eta7(m, w[p], r, 0);
            case K_ETA_8:
                return k_eta8(m, r, 0);
            case Op.K_ETAS:
                return etas(m, w[p], w[p + 1], 0);
            case K_ETAS_1:
                return k_etas1(m, w[p], w[p + 1], r, 0);
            case K_ETAS_2:
                return k_etas2(m, w[p], w[p + 1], w[p + 2], r, 0);
            case K_ETAS_3:
                return k_etas3(m, w[p], w[p + 1], r, 0);
            case K_ETAS_4:
                return k_etas4(m, w[p], w[p + 1], w[p + 2], r, 0);
            case K_ETAS_5:
                return k_etas5(m, w[p], w[p + 1], r, 0);
            case Op.K_UNITL:
                return unitl(m, w[p], w[p + 1], 0);
            case K_UNITL_1:
                return k_unitl1(m, w[p], r, 0);
            case K_UNITL_2:
                return k_unitl2(m, w[p], r, 0);
            case K_UNITL_3:
                return k_unitl3(m, w[p], r, 0);
            case Op.K_STRX:
                return strx(m, w[p], w[p + 1], 0);
            case K_STRX_1:
                return k_strx1(m, w[p], r, 0);
            default:
                return m.fault(MachineErrors.E_INTERNAL);
        }
    }
}
