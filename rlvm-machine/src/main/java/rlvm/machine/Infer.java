package rlvm.machine;

import static rlvm.machine.Op.SIG;

import rlvm.api.KernelException;
import rlvm.api.MachineErrors;
import rlvm.store.DeclKind;
import rlvm.store.DeclTable;
import rlvm.store.IntLists;
import rlvm.store.Kind;
import rlvm.store.Node;
import rlvm.store.SharedFacts;
import rlvm.store.Specials;
import rlvm.store.TermOps;
import rlvm.store.Terms;

/**
 * Family TY (ISA 4.5): {@code INFER}, {@code CHECK}, {@code TWALK}, {@code VTYPE}, {@code IPROJ},
 * {@code ENSORT}, {@code ENPI}, {@code ISPROP}; the family's resume switch (which also dispatches
 * {@code SIGD} of {@link Facts}). Owner: M5.
 *
 * <p>Entry steps are the frozen cross-package interface; {@code TWALK} and {@code IPROJ} are internal to
 * this file; continuation steps {@code k_*}, loop methods and frame keys are this file's own
 * (*agentic-note*: rep-stepper-convention, H2-H4; template in *agentic-note*: rep-machine-work-packages).
 *
 * <h2>How the judgments are laid out</h2>
 * <pre>
 * INFER   infer -&gt; leaves inline (inferLeaf); tabling probe (suspRec, hit); one of the internal chains
 *         below; INFER[1](s, t, mode) stores TYPE / TSTAMP of the tabling record (5.3, I-MEMO)
 *           app   TWALK  frames TWALK[1..9]    inferApp, twalk (the loop), twArg, twC .. twF (one argument)
 *           pi    INFER2[1..5]                 piLoop, piIter0..3 (one binder), piBody
 *           lam   INFER2[6..9]   (CHECKED)     lamLoop, lamIter0..3, k_lam4
 *           let   INFER2[10..15]               letLoop, letIter0..5, letBind
 *           proj  IPROJ[1..14]                 inferProj, projStart, projParams, projFields, pjLast
 * CHECK   check -&gt; INFER (CHECKED) -&gt; CONV; B9 (SW_EXPECT): checkExpect, frames CHECK2[1..8], TWALK[8..9]
 * VTYPE   vtype -&gt; vtypeFast; SUSP, CONST, NEU (VTYPE[3..5]), PROJ (the projection chain), PI / PII (VTYPE2)
 * ENSORT, ENPI, ISPROP: entry step + one or two continuation steps
 * </pre>
 * TWALK and IPROJ are parts of the judgment that started them (INFER, or VTYPE for a PROJ value): they
 * charge no tick and raise no call event of their own. A loop over a telescope or a spine is a Java
 * loop; one iteration is a short chain of helper methods (bounded host depth), and every continuation
 * step finishes its iteration and re-enters the loop (H3 "inside a loop").
 */
final class Infer {
    private Infer() {}

    /**
     * Session state private to this package (caches, counters, scratch of its own): add fields here
     * instead of asking for fields in {@link Machine}. One instance per machine, field {@code m.x5}.
     */
    static final class Ext implements Machine.Counters {
        /** Names of {@link #counterValues} in the statistics ({@code machine.<name>}; M6 sums them over the sessions). */
        private static final String[] COUNTER_NAMES = {"match.tried", "match.hit", "pchk.set", "pchk.hit", "joint.binders",
            "infer.icHits", "facts.ticks", "facts.use.propMask", "facts.use.propMaskSet", "facts.use.unread", "facts.use.unreadSet",
            "facts.use.proof", "facts.use.proofYes", "facts.use.proofNo"};

        @Override
        public String[] counterNames() {
            return COUNTER_NAMES;
        }

        /** Read once, when the session is closed (no cost on a checking path). All but {@code facts.ticks} count while {@code m.stats}. */
        @Override
        public long[] counterValues() {
            long[] u = factUse;
            return new long[] {matchTried, matchHit, pchkSets, pchkHits, jointBinders, icHits, sigdTicks, u[Facts.USE_PROPMASK],
                u[Facts.USE_PROPMASK_SET], u[Facts.USE_UNREAD], u[Facts.USE_UNREAD_SET], u[Facts.USE_PROOF], u[Facts.USE_PROOF_YES],
                u[Facts.USE_PROOF_NO]};
        }

        /** {@code CONST Nat} / {@code CONST String} of this session (ISA 4.5.3, R02 6.9); 0 = not made yet. */
        int natType;
        int strType;
        /**
         * The regime of ISA 5.3 for the declaration being checked: true = its roots passed the summary test
         * ({@code depMax}, {@code lpSet}, UNRESOLVED), so CHKCONST and CHKLVL are no-ops; false = they are
         * performed per node (direct judgment tests, a root only the exact walk of admission could clear).
         */
        boolean trusted;
        boolean prevTrusted;
        /** Level-parameter names of the declaration being checked ({@code IntLists} id); -1 = none known: CHKLVL passes. */
        int declLparams = -1;
        /** Level-parameter names of the previous declaration of the session (the generation rule of 5.3). */
        int prevLparams = IntLists.EMPTY;
        /** First export term id of the unit being checked: smaller export ids are "not a term of the unit" (3.1). */
        int firstTerm;
        /** Exact occurrence walks for IPROJ (ISA 4.5.5 last comment); made at first use. */
        TermOps termOps;
        /** Assembly buffer of a fact record (SIGD, step 2); made at first use. */
        int[] factBuf;
        /** Fact records this session derived (statistics). */
        long factsDerived;
        /** Ticks spent in SIGD derivations, the lazy ones and those of DECL on acceptance (the cost of the facts; always counted). */
        long sigdTicks;
        /** Queries of the fact consumers by kind ({@code Facts.USE_*}; counted while {@code m.stats}). */
        final long[] factUse = new long[Facts.USE_COUNT];
        /**
         * INFER's front cache (NOTES-M5 C1): a direct-mapped, lossy table in front of the tabling probe. A cell
         * holds (term, epoch, frame, ls', type, stamp) of a tabling record whose TYPE is stored; a query that
         * finds its term there and whose environment agrees with the cell's frame on the term's mask is
         * answered without PRUNE and without the probe for the SUSP record. Null: off.
         */
        int[] ic;
        /** Statistics ({@code m.stats}): INFER queries answered by the front cache. */
        long icHits;
        /** The store's shared facts (PCHK, ISA 5.1; SW_PCHK); looked up at first use. */
        SharedFacts shared;
        /** Statistics ({@code m.stats}): CHECKED inferences answered in ONLY mode because of PCHK; PCHK bits set by this session. */
        long pchkHits;
        long pchkSets;
        /** Statistics ({@code m.stats}): binders that the joint telescope check of DECL opened once for type and value (SW_M5B). */
        long jointBinders;
        /** Pattern nodes the running MATCH may still visit ({@link #tmatch}). */
        int tmBudget;
        /** Statistics ({@code m.stats}): telescope domains offered to MATCH / answered by it (SW_M5A). */
        long matchTried;
        long matchHit;

        /** Session reset (ISA 1.6): forget everything, in O(used). */
        void reset(Machine m) {
            natType = 0;
            strType = 0;
            trusted = false;
            prevTrusted = false;
            declLparams = -1;
            prevLparams = IntLists.EMPTY;
            firstTerm = 0;
        }

        /**
         * Finding E1-F6 (2026-11-24): this declaration APPLIED a let variable whose declared type is not the very
         * type of its value ({@link #LET_EXACT} not set on its suspension; {@code Eval.apply}). The machine's value of
         * such an application has the let variable reduced away, the official term has not, so the type of that
         * VALUE can differ from the type of the official TERM by a convertible stand-in. The one place where the
         * official kernel types an unreduced argument inside a reduction, the K rule ({@code to_cnstr_when_K}:
         * {@code infer_type(major)}), declines from then on ({@code Reduce}).
         */
        boolean zetaInexact;

        /** Start of a declaration: until {@link Decl#check} says otherwise nothing is trusted. */
        void beginDecl(Machine m) {
            zetaInexact = false;
            trusted = false;
            declLparams = -1;
            firstTerm = 0;
            if (ic == null && icWanted && !m.strict) {
                ic = new int[(1 << Integer.getInteger("rlvm.machine.m5ic.log2", IC_LOG2)) << IC_SHIFT]; // epoch 0: no cell is valid
            }
        }

        /**
         * The front cache is an experiment without a switch of its own yet (requested of M6): it is on iff the
         * system property {@code rlvm.machine.m5ic} is {@code true} (read when the session is made), never in
         * strict mode. It changes no result, no record and no tick: it only answers what the tabling probe
         * would answer.
         */
        final boolean icWanted = Boolean.getBoolean("rlvm.machine.m5ic");

        /** Bytes in use of this package's own session tables (part of the footprint, ISA 1.6). */
        long bytes() {
            return ic == null ? 0 : 4L * ic.length;
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // Frame keys (the payload of J[k] is the parameter list of its continuation step, H2)
    // ---------------------------------------------------------------------------------------------------

    static final int K_INFER_1 = Op.K_INFER | 1;
    static final int K_TW_1 = Op.K_TWALK | 1;
    static final int K_TW_2 = Op.K_TWALK | 2;
    static final int K_TW_3 = Op.K_TWALK | 3;
    static final int K_TW_4 = Op.K_TWALK | 4;
    static final int K_TW_5 = Op.K_TWALK | 5;
    static final int K_TW_6 = Op.K_TWALK | 6;
    static final int K_TW_7 = Op.K_TWALK | 7;
    static final int K_TW_8 = Op.K_TWALK | 8;
    static final int K_TW_9 = Op.K_TWALK | 9;
    static final int K_PI_1 = Op.K_INFER2 | 1;
    static final int K_PI_2 = Op.K_INFER2 | 2;
    static final int K_PI_3 = Op.K_INFER2 | 3;
    static final int K_PI_4 = Op.K_INFER2 | 4;
    static final int K_PI_5 = Op.K_INFER2 | 5;
    static final int K_LAM_1 = Op.K_INFER2 | 6;
    static final int K_LAM_2 = Op.K_INFER2 | 7;
    static final int K_LAM_3 = Op.K_INFER2 | 8;
    static final int K_LAM_4 = Op.K_INFER2 | 9;
    static final int K_LET_1 = Op.K_INFER2 | 10;
    static final int K_LET_2 = Op.K_INFER2 | 11;
    static final int K_LET_3 = Op.K_INFER2 | 12;
    static final int K_LET_4 = Op.K_INFER2 | 13;
    static final int K_LET_5 = Op.K_INFER2 | 14;
    static final int K_LET_6 = Op.K_INFER2 | 15;
    static final int K_IP_1 = Op.K_IPROJ | 1;
    static final int K_IP_2 = Op.K_IPROJ | 2;
    static final int K_IP_3 = Op.K_IPROJ | 3;
    static final int K_IP_4 = Op.K_IPROJ | 4;
    static final int K_IP_5 = Op.K_IPROJ | 5;
    static final int K_IP_6 = Op.K_IPROJ | 6;
    static final int K_IP_7 = Op.K_IPROJ | 7;
    static final int K_IP_8 = Op.K_IPROJ | 8;
    static final int K_IP_9 = Op.K_IPROJ | 9;
    static final int K_IP_10 = Op.K_IPROJ | 10;
    static final int K_IP_11 = Op.K_IPROJ | 11;
    static final int K_IP_12 = Op.K_IPROJ | 12;
    static final int K_IP_13 = Op.K_IPROJ | 13;
    static final int K_IP_14 = Op.K_IPROJ | 14;
    static final int K_CHECK_1 = Op.K_CHECK | 1;
    static final int K_CHECK_2 = Op.K_CHECK | 2;
    static final int K_CK_1 = Op.K_CHECK2 | 1;
    static final int K_CK_2 = Op.K_CHECK2 | 2;
    static final int K_CK_3 = Op.K_CHECK2 | 3;
    static final int K_CK_4 = Op.K_CHECK2 | 4;
    static final int K_CK_5 = Op.K_CHECK2 | 5;
    static final int K_CK_6 = Op.K_CHECK2 | 6;
    static final int K_CK_7 = Op.K_CHECK2 | 7;
    static final int K_CK_8 = Op.K_CHECK2 | 8;
    static final int K_VT_1 = Op.K_VTYPE | 1;
    static final int K_VT_2 = Op.K_VTYPE | 2;
    static final int K_VT_3 = Op.K_VTYPE | 3;
    static final int K_VT_4 = Op.K_VTYPE | 4;
    static final int K_VT_5 = Op.K_VTYPE | 5;
    static final int K_VT_6 = Op.K_VTYPE | 6;
    static final int K_VT_7 = Op.K_VTYPE | 7;
    static final int K_VP_1 = Op.K_VTYPE2 | 1;
    static final int K_VP_2 = Op.K_VTYPE2 | 2;
    static final int K_VP_3 = Op.K_VTYPE2 | 3;
    static final int K_VP_4 = Op.K_VTYPE2 | 4;
    static final int K_VP_5 = Op.K_VTYPE2 | 5;
    static final int K_VP_6 = Op.K_VTYPE2 | 6;
    static final int K_EVD_1 = Op.K_VTYPE2 | 7;
    static final int K_ENSORT_1 = Op.K_ENSORT | 1;
    static final int K_ENPI_1 = Op.K_ENPI | 1;
    static final int K_ISPROP_1 = Op.K_ISPROP | 1;
    static final int K_ISPROP_2 = Op.K_ISPROP | 2;

    /** Registers of a running projection chain, in scratch at {@code X[xb ..]} (the frames carry {@code xb}). */
    private static final int PJ_TS = 0;
    private static final int PJ_S = 1;
    private static final int PJ_IDX = 2;
    private static final int PJ_SV = 3;
    private static final int PJ_ST = 4;
    private static final int PJ_EB = 5;
    private static final int PJ_EN = 6;
    private static final int PJ_LS = 7;
    private static final int PJ_P = 8;
    private static final int PJ_NP = 9;
    private static final int PJ_WORDS = 10;

    // ---------------------------------------------------------------------------------------------------
    // Small queries
    // ---------------------------------------------------------------------------------------------------

    /** True for a PI or PII record (never for an immediate). */
    private static boolean isPi(Machine m, int v) {
        if (v < 0) {
            return false;
        }
        int k = m.vh[v << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT;
        return k == Layout.PI || k == Layout.PII;
    }

    /**
     * Does the body of the binder term of the PI / PII record {@code p} read its variable (B6; ISA 4.5.4
     * "binder i is read later")? From the node mask: a clear bit is definite, a set bit may over-approximate.
     */
    private static boolean bodyReads0(Machine m, int p) {
        Terms terms = m.terms;
        int body = terms.b(m.vh[(p << Layout.REC_SHIFT) + Layout.A]);
        return (terms.word(body, Node.W_MASK_LO) & 1) != 0;
    }

    /**
     * Memo word {@code M1} of a LET suspension (unused by SUSP records otherwise): this value = the declared type of
     * the let variable IS the type inferred for its value (one record), so reducing the variable away loses nothing.
     * Set by the let rule in CHECKED mode; a function of the suspension's identity (value term, frame, declared type).
     */
    static final int LET_EXACT = 1;

    /** {@code Eval.apply}: is {@code f} a let variable whose value has only a convertible type (see {@link Ext#zetaInexact})? */
    static boolean inexactLet(Machine m, int f) {
        if (f <= Layout.HOLE) {
            return false;
        }
        int o = f << Layout.REC_SHIFT;
        int[] vh = m.vh;
        return (vh[o] & (0xF << Layout.KIND_SHIFT | Layout.W0_LET)) == (Layout.SUSP << Layout.KIND_SHIFT | Layout.W0_LET)
                && vh[o + Layout.M1] != LET_EXACT;
    }

    /**
     * May {@code INFER(t, ONLY)} ask for the TYPE of the variable with de Bruijn index {@code k}? (Findings E1-F2 /
     * E1-F3: only then does it matter which type the variable's binding carries.) Syntactic and conservative: true
     * when in doubt. Infer-only inference follows the head of an application (arguments are not typed), the
     * structure of a projection, the bodies of lambdas and lets, and both parts of a Pi (their sorts).
     */
    static boolean typeReads(Terms terms, int t, int k) {
        for (int fuel = 256; fuel > 0; fuel--) {
            if (k < 62 && (terms.fvMask(t) >>> k & 1L) == 0) {
                return false; // the term does not read the variable at all (a clear mask bit is definite)
            }
            switch (terms.kind(t)) {
                case Kind.BVAR:
                    return terms.a(t) == k;
                case Kind.APP:
                    t = terms.a(t);
                    break;
                case Kind.PROJ:
                    t = terms.a(t);
                    break;
                case Kind.LAM:
                    t = terms.b(t);
                    k++;
                    break;
                case Kind.LET:
                    t = terms.c(t);
                    k++;
                    break;
                case Kind.PI:
                    if (typeReads(terms, terms.a(t), k)) {
                        return true;
                    }
                    t = terms.b(t);
                    k++;
                    break;
                case Kind.SORT:
                case Kind.CONST:
                case Kind.NATLIT:
                case Kind.STRLIT:
                    return false;
                default:
                    return true;
            }
        }
        return true;
    }

    /**
     * The official {@code has_loose_bvars(binding_body(r))} of {@code infer_proj} (R02 6.8), exact (ISA 4.5.5:
     * a set mask bit is confirmed by an occurrence walk, because it can cause a rejection).
     */
    private static boolean bodyReads0ExactSlow(Machine m, int p) {
        Terms terms = m.terms;
        int body = terms.b(m.vh[(p << Layout.REC_SHIFT) + Layout.A]);
        Ext x = m.x5;
        if (x.termOps == null) {
            x.termOps = new TermOps(terms, m.lists);
        }
        return x.termOps.hasLooseBVar(body, 0);
    }

    /** {@code CONST Nat} (R02 6.9; ISA 4.5.3), or a signal: E_UNKNOWN_CONST (not declared), E_PRELUDE (guard). */
    static int natType(Machine m) {
        int c = m.x5.natType;
        return c != 0 ? c : litTypeSlow(m, Specials.NAT);
    }

    /** {@code CONST String}, or a signal. */
    static int strType(Machine m) {
        int c = m.x5.strType;
        return c != 0 ? c : litTypeSlow(m, Specials.STRING);
    }

    /**
     * The type constant of a literal. The official kernel builds {@code Const Nat []} without looking the
     * name up; as the reference engine does, a literal whose type constant is not declared (or not visible)
     * is a kernel error, and one whose prelude guard is not VALID declines (ISA 2.9).
     */
    private static int litTypeSlow(Machine m, int which) {
        int cid = m.specials.cid(which);
        if (cid == 0 || m.decls.rank(cid) >= m.decl) {
            return m.fail(MachineErrors.E_UNKNOWN_CONST);
        }
        if (m.specials.state(which) != Specials.VALID) {
            return m.fault(MachineErrors.E_PRELUDE);
        }
        int c = Values.mkConst(m, cid, IntLists.EMPTY);
        if (c < SIG) {
            if (which == Specials.NAT) {
                m.x5.natType = c;
            } else {
                m.x5.strType = c;
            }
        }
        return c;
    }

    // ---------------------------------------------------------------------------------------------------
    // Tabling (ISA 5.1, 5.3)
    // ---------------------------------------------------------------------------------------------------

    /**
     * {@code tabled(t)} of ISA 3.1 for a non-leaf node. v0: every non-leaf node. With SW_GATED_TABLING: the
     * sharing flags of the node, or a term that does not belong to the unit being checked.
     */
    private static boolean tabled(Machine m, int t, int hdr) {
        if ((m.sw & Op.SW_GATED_TABLING) == 0) {
            return true;
        }
        return (hdr & Node.F_TABLED_MASK) != 0 || t < m.x5.firstTerm
                || (t & (Node.OVERLAY_BIT | Node.GEN_BASE)) == Node.GEN_BASE;
    }

    /**
     * The tabling record {@code SUSP(t, frame, ls')} itself (ISA 5.1), also when its VAL is known: the plain SUSP
     * record of M1 ({@link Values#suspRecord}; the LET suspension of the same triple is another record). One
     * record serves EVAL (VAL), INFER and CHECK (TYPE, TSTAMP); this file only reads and writes M_TYPE and
     * M_TSTAMP.
     */
    static int suspRec(Machine m, int t, int frame, int ls) {
        return Values.suspRecord(m, t, frame, ls);
    }

    /**
     * The stored type of tabling record {@code s} if it answers a query of this mode (ISA 5.3): always in
     * ONLY mode; in CHECKED mode iff TSTAMP is PERM or not older than {@code gen}. A type inferred without
     * checking (TSTAMP 0) never answers a checking query (R02 section 5). 0 = no answer.
     */
    private static int hit(Machine m, int s, int mode) {
        int[] vh = m.vh;
        int o = s << Layout.REC_SHIFT;
        int ty = vh[o + Layout.M_TYPE];
        if (ty != 0 && (mode == Op.ONLY || vh[o + Layout.M_TSTAMP] >= m.gen)) {
            return ty;
        }
        return 0;
    }

    // ---- INFER's front cache: (term, frame) -> TYPE without PRUNE and without the record probe (NOTES-M5 C1) ----

    /** Cells of the front cache: 2^IC_LOG2 (32 bytes each); {@code rlvm.machine.m5ic.log2} overrides it for sweeps. */
    private static final int IC_LOG2 = 10;
    private static final int IC_TERM = 0;
    private static final int IC_EPOCH = 1;
    private static final int IC_FRAME = 2;
    private static final int IC_LS = 3;
    private static final int IC_TYPE = 4;
    private static final int IC_STAMP = 5;
    private static final int IC_SHIFT = 3; // 8 words per cell

    /** The cell of term {@code t} whose innermost variable read is bound to {@code v} (0 for a closed term). */
    private static int icCell(int[] ic, int t, int v) {
        int h = (t * 0x9E3779B1) ^ (v * 0x85EBCA6B);
        return ((h ^ (h >>> 15)) << IC_SHIFT) & (ic.length - 1 & -(1 << IC_SHIFT));
    }

    /**
     * The front cache's answer to INFER(t, env, ls', mode): the TYPE of the tabling record of
     * {@code (t, PRUNE(env, mask), ls')} if the cell of t holds that record's frame, i.e. a frame that agrees
     * with the environment on every index of the mask (the frame of a cell was pruned with the same mask, so
     * it has the same length and HOLE elsewhere), under the validity rule of ISA 5.3 ({@link #hit}). 0 = no
     * answer: the caller takes the tabling probe. Exact: nothing is assumed about how the environment was built.
     */
    private static int icGet(Machine m, int[] ic, int t, int ls1, int eb, int en, long mask, int mode) {
        int[] es = m.es;
        int top = eb + en - 1;
        int c;
        if (mask == 0) {
            c = icCell(ic, t, 0);
        } else {
            if (64 - Long.numberOfLeadingZeros(mask) > en) {
                return 0; // PRUNE reports it
            }
            c = icCell(ic, t, es[top - Long.numberOfTrailingZeros(mask)]);
        }
        if (ic[c + IC_TERM] != t || ic[c + IC_EPOCH] != m.epoch || ic[c + IC_LS] != ls1
                || (mode != Op.ONLY && ic[c + IC_STAMP] < m.gen)) {
            return 0;
        }
        if (mask != 0) {
            int[] rs = m.rs;
            int fb = ic[c + IC_FRAME] + Layout.RUN_H + 63 - Long.numberOfLeadingZeros(mask);
            for (long r = mask; r != 0; r &= r - 1) {
                int idx = Long.numberOfTrailingZeros(r);
                if (rs[fb - idx] != es[top - idx]) {
                    return 0;
                }
            }
        }
        return ic[c + IC_TYPE];
    }

    /** Enters the stored TYPE of tabling record {@code s} (term t, a near frame) into the front cache. */
    private static void icPut(Machine m, int[] ic, int s, int t, int type, int stamp) {
        long mask = m.terms.fvMask(t);
        if (mask < 0) {
            return;
        }
        int o = s << Layout.REC_SHIFT;
        int frame = m.vh[o + Layout.B];
        int v = 0;
        if (mask != 0) {
            int len = 64 - Long.numberOfLeadingZeros(mask);
            v = m.rs[frame + Layout.RUN_H + len - 1 - Long.numberOfTrailingZeros(mask)];
        }
        int c = icCell(ic, t, v);
        ic[c + IC_TERM] = t;
        ic[c + IC_EPOCH] = m.epoch;
        ic[c + IC_FRAME] = frame;
        ic[c + IC_LS] = m.vh[o + Layout.C];
        ic[c + IC_TYPE] = type;
        ic[c + IC_STAMP] = stamp;
    }

    /**
     * The check stamp of a CHECKED result for term {@code t} (ISA 5.3): PERM if the term is closed and has no
     * level parameter (nothing declaration-relative can be wrong with it later), else the generation.
     * {@link Decl#check} keeps the generation while consecutive declarations pass the root summaries.
     */
    private static int stamp(Machine m, int t) {
        int hdr = m.terms.header(t);
        return (hdr & Node.F_HAS_LPARAM) == 0 && Node.looseRange(hdr) == 0 ? Layout.TSTAMP_PERM : m.gen;
    }

    // ---------------------------------------------------------------------------------------------------
    // INFER
    // ---------------------------------------------------------------------------------------------------

    /**
     * INFER(t, env, ls, mode) (ISA 4.5.1), mode = {@code Op.CHECKED} or {@code Op.ONLY}: the type of t as a value.
     * variable: VTYPE of the binding; sort; const; literal (numeral bound); tabled on the SUSP record with TSTAMP (5.3);
     * app: TWALK (4.5.4; TELE in step 3); lam: PII; pi: mkImax of the sorts; let; proj: IPROJ. Official:
     * {@code infer_type_core} (R02 6.1-6.9); every throw site of section 6 is a {@code fail(E)}.
     * Entry frame: INFER[0](t, eb, en, ls, mode).
     * @return the result, or a signal ({@code >= Op.SIG}): SUSP, ABORT, FATAL
     */
    static int infer(Machine m, int t, int eb, int en, int ls, int mode, int d) {
        int[] reg = m.terms.regionOf(t);
        int o = Node.wordOf(t);
        int hdr = reg[o];
        int kind = hdr & 0xF;
        if (kind <= Kind.CONST || kind >= Kind.NATLIT) {
            return inferLeaf(m, t, kind, reg[o + Node.W_A], eb, en, ls, mode, d); // 1. inline answers
        }
        int ls1 = (hdr & Node.F_HAS_LPARAM) != 0 ? ls : 0;
        boolean tab = tabled(m, t, hdr);
        if (tab || kind == Kind.LAM) {
            long mask = (reg[o + Node.W_MASK_LO] & 0xFFFFFFFFL) | ((long) reg[o + Node.W_MASK_HI] << 32);
            return inferProbe(m, t, hdr, kind, tab, mask, ls1, eb, en, ls, mode, d);
        }
        return inferMiss(m, t, hdr, kind, 0, 0, ls1, eb, en, ls, mode, d);
    }

    /**
     * INFER of a tabled node or of a lambda (same judgment, same host depth): the front cache, PRUNE, the lambda
     * in ONLY mode (ISA 4.5.1), the tabling probe (5.1, 5.3). A memo hit is no tick, no event, no frame.
     */
    private static int inferProbe(Machine m, int t, int hdr, int kind, boolean tab, long mask, int ls1, int eb, int en, int ls,
            int mode, int d) {
        int[] ic = m.x5.ic;
        if (ic != null && tab && mask >= 0 && m.debug == 0 && !(kind == Kind.LAM && mode == Op.ONLY)) {
            int ty = icGet(m, ic, t, ls1, eb, en, mask, mode);
            if (ty != 0) {
                if (m.stats) {
                    m.x5.icHits++;
                }
                return ty; // the front cache: no PRUNE, no probe
            }
        }
        int frame = Runs.prune(m, eb, en, mask);
        if (frame >= SIG) {
            return frame;
        }
        if (kind == Kind.LAM && mode == Op.ONLY) {
            return Values.mkPii(m, t, frame, ls1); // ISA 4.5.1 lam, ONLY: nothing opened, nothing visited
        }
        int s = 0;
        if (tab) {
            s = suspRec(m, t, frame, ls1);
            if (s >= SIG) {
                return s;
            }
            int ty = hit(m, s, mode);
            if (ty != 0) {
                if (m.debug != 0) {
                    return inferHitSlow(m, s, t, eb, en, ls, mode, ty);
                }
                if (ic != null) {
                    icPut(m, ic, s, t, ty, m.vh[(s << Layout.REC_SHIFT) + Layout.M_TSTAMP]);
                }
                return ty;
            }
        }
        return inferMiss(m, t, hdr, kind, s, frame, ls1, eb, en, ls, mode, d);
    }

    /**
     * {@code memocheck} (ISA 5.5) of a tabled INFER: the stored type is recomputed with the entry bypassed (TYPE
     * and TSTAMP zeroed, put back afterwards) and compared ({@code Debug.memoEnter} / {@code memoExit}).
     */
    private static int inferHitSlow(Machine m, int s, int t, int eb, int en, int ls, int mode, int hit) {
        if (!Debug.memoEnter(m)) {
            return hit;
        }
        int o = s << Layout.REC_SHIFT;
        int stamp = m.vh[o + Layout.M_TSTAMP];
        m.vh[o + Layout.M_TYPE] = 0;
        m.vh[o + Layout.M_TSTAMP] = 0;
        int r = m.drive(infer(m, t, eb, en, ls, mode, 0));
        m.vh[o + Layout.M_TYPE] = hit;
        m.vh[o + Layout.M_TSTAMP] = stamp;
        return Debug.memoExit(m, Op.INFER, s, mode, hit, r);
    }

    /** INFER of a non-leaf node that no memo answered: a PCHK hit (SW_PCHK) runs it in ONLY mode, else the node's chain. */
    private static int inferMiss(Machine m, int t, int hdr, int kind, int s, int frame, int ls1, int eb, int en, int ls, int mode,
            int d) {
        if (mode == Op.CHECKED && (m.sw & Op.SW_PCHK) != 0 && (hdr & Node.F_HAS_LPARAM) == 0 && Node.looseRange(hdr) == 0
                && pchkKnown(m, t)) {
            return inferKnown(m, t, kind, s, frame, ls1, eb, en, ls, d); // PCHK: the closed term passed check mode before
        }
        return inferNode(m, t, kind, s, frame, ls1, eb, en, ls, mode, mode, d);
    }

    // ---- PCHK: shared check facts (ISA 5.1 "closed shared term passed check mode", 5.3; staging step 1, SW_PCHK) ----

    /**
     * PCHK(t): the store's {@code SharedFacts.CHECKED} bit of a term of the export region. It says that the
     * closed, level-parameter-free term {@code t} passed INFER in CHECKED mode once, in some declaration, on
     * some worker. That is a fact about the term and the declarations it mentions alone, and those are
     * visible here: the bit is used only while the declaration being checked passed the root summaries
     * ({@code Ext.trusted}: {@code depMax} of every sub-term of its roots is below its rank, ISA 5.3), and a
     * literal's {@code depMax} covers its prerequisite names (RECONCILIATION section 2). A term without level
     * parameter has no CHKLVL obligation. Overlay and generated terms have no bit.
     */
    private static boolean pchkKnown(Machine m, int t) {
        Ext x5 = m.x5;
        if (!x5.trusted || t <= 0 || t >= Node.GEN_BASE) {
            return false;
        }
        SharedFacts sf = x5.shared;
        if (sf == null) {
            if (m.store == null) {
                return false;
            }
            sf = m.store.facts();
            x5.shared = sf;
        }
        return sf.isChecked(t);
    }

    /** PCHK for {@link #check}: is {@code t} a closed term without level parameter whose bit is set? */
    private static boolean pchkTerm(Machine m, int t) {
        int hdr = m.terms.header(t);
        return (hdr & Node.F_HAS_LPARAM) == 0 && Node.looseRange(hdr) == 0 && pchkKnown(m, t);
    }

    /**
     * Records PCHK(t) after a CHECKED inference of the closed, level-parameter-free term {@code t} completed
     * (the caller is INFER[1], which a signal never reaches). The byte is read first: a set bit is not
     * written again (no shared cache line is dirtied by a hit).
     */
    private static void pchkSet(Machine m, int t) {
        if (t <= 0 || t >= Node.GEN_BASE || m.store == null) {
            return;
        }
        Ext x5 = m.x5;
        SharedFacts sf = x5.shared;
        if (sf == null) {
            sf = m.store.facts();
            x5.shared = sf;
        }
        if (!sf.isChecked(t)) {
            sf.setChecked(t);
            if (m.stats) {
                x5.pchkSets++;
            }
        }
    }

    /**
     * INFER in CHECKED mode of a term whose PCHK bit is set: its type is computed as in ONLY mode (the same
     * value, without visiting what was checked before) and stored with the check stamp PERM. Under
     * {@code shortcheck} (ISA 5.5) the complete procedure runs instead and must not fail.
     */
    private static int inferKnown(Machine m, int t, int kind, int s, int frame, int ls1, int eb, int en, int ls, int d) {
        if (m.stats) {
            m.x5.pchkHits++;
        }
        if ((m.debug & Op.DBG_SHORTCHECK) != 0) {
            return pchkAuditSlow(m, t, kind, s, frame, ls1, eb, en, ls);
        }
        if (kind == Kind.LAM) {
            int pii = Values.mkPii(m, t, frame, ls1); // ISA 4.5.1 lam, ONLY: nothing opened, nothing visited
            return pii >= SIG ? pii : k_infer1(m, s, t, Op.CHECKED, pii, d);
        }
        return inferNode(m, t, kind, s, frame, ls1, eb, en, ls, Op.ONLY, Op.CHECKED, d);
    }

    /** {@code shortcheck} of a PCHK hit: the CHECKED inference is run after all; a kernel error in it is an audit failure. */
    private static int pchkAuditSlow(Machine m, int t, int kind, int s, int frame, int ls1, int eb, int en, int ls) {
        int r = m.drive(inferNode(m, t, kind, s, frame, ls1, eb, en, ls, Op.CHECKED, Op.CHECKED, 0));
        m.audits++;
        return r == Op.ABORT ? m.fault(MachineErrors.E_AUDIT) : r;
    }

    /**
     * INFER of a non-leaf node that the tabling record {@code s} (0 = untabled) did not answer: steps 2-4 of the
     * entry step (same judgment, same host depth as {@link #infer}). {@code mode} is the mode the node's chain
     * runs in, {@code smode} the mode of the query, under which the result is stored: they differ only for a
     * PCHK hit (chain ONLY, query CHECKED).
     */
    private static int inferNode(Machine m, int t, int kind, int s, int frame, int ls1, int eb, int en, int ls,
            int mode, int smode, int d) {
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig; // 2. TICK
        }
        if (m.stats) {
            m.call(Op.INFER); // 2b. call event
        }
        if (d > m.hostDepth) {
            return m.spill5(Op.K_INFER, t, eb, en, ls, smode); // 3. too deep: the frame INFER[0]
        }
        int r = inferChain(m, t, kind, frame, ls1, eb, en, ls, mode, d); // 4. body: the chain of this node kind
        return r >= SIG ? m.sig3(r, K_INFER_1, s, t, smode) : k_infer1(m, s, t, smode, r, d);
    }

    /** The chain of a non-leaf node (same judgment, same host depth). */
    private static int inferChain(Machine m, int t, int kind, int frame, int ls1, int eb, int en, int ls, int mode,
            int d) {
        switch (kind) {
            case Kind.APP:
                return inferApp(m, t, eb, en, ls, mode, d);
            case Kind.LAM: {
                // CHECKED (the ONLY case was answered inline): the result is known before the telescope is
                // checked, and returned only after it (ISA 4.5.1 lam)
                int pii = Values.mkPii(m, t, frame, ls1);
                return pii >= SIG ? pii : lamLoop(m, pii, t, eb, en, ls, m.ep, m.ssp, d);
            }
            case Kind.PI:
                return piLoop(m, t, eb, en, ls, mode, m.ep, m.xp, d);
            case Kind.LET:
                return letLoop(m, t, eb, en, ls, mode, 0, m.ep, d);
            case Kind.PROJ:
                return inferProj(m, t, eb, en, ls, mode, d);
            default:
                return m.fault(MachineErrors.E_INTERNAL);
        }
    }

    /**
     * INFER[1](s, t, mode): the node's chain returned type {@code r}; it is stored in the tabling record with
     * its stamp (ISA 5.3; I-MEMO: only here, which a signal never reaches). An ONLY result never replaces a
     * stored type (it could only lose a check stamp).
     */
    static int k_infer1(Machine m, int s, int t, int mode, int r, int d) {
        if (mode == Op.CHECKED) {
            storeChecked(m, s, t, r);
        } else if (s != 0) {
            int o = s << Layout.REC_SHIFT;
            if (m.vh[o + Layout.M_TYPE] == 0) {
                storeType(m, s, t, r, Layout.TSTAMP_ONLY);
            }
        }
        return r;
    }

    /** TYPE and TSTAMP of tabling record {@code s} (I-MEMO), and the front cache's cell. */
    private static void storeType(Machine m, int s, int t, int type, int stamp) {
        int o = s << Layout.REC_SHIFT;
        int[] vh = m.vh;
        vh[o + Layout.M_TYPE] = type;
        vh[o + Layout.M_TSTAMP] = stamp;
        int[] ic = m.x5.ic;
        if (ic != null) {
            icPut(m, ic, s, t, type, stamp);
        }
    }

    /** A CHECKED inference of {@code t} completed with type {@code r}: the record (if tabled) and PCHK (if the stamp is PERM). */
    private static void storeChecked(Machine m, int s, int t, int r) {
        int st = stamp(m, t);
        if (s != 0) {
            storeType(m, s, t, r, st);
        }
        if (st == Layout.TSTAMP_PERM && (m.sw & Op.SW_PCHK) != 0 && !m.erSeen) {
            // the closed term passed check mode: a fact for every later declaration and worker. Not once the
            // declaration has compared under eager reduction: the official answer of such a comparison depends
            // on the declaration's history (M6-F26, S-F4 / S-F5), so nothing derived after it is shared
            pchkSet(m, t);
        }
    }

    /**
     * The leaf cases of INFER (ISA 4.5.1): variable, sort, constant, literal. They never become frames.
     * Official: the FVar, Sort, Const, Lit cases of {@code infer_type_core} (R02 6.1, 6.2, 6.9).
     */
    private static int inferLeaf(Machine m, int t, int kind, int a, int eb, int en, int ls, int mode, int d) {
        switch (kind) {
            case Kind.BVAR: {
                // variable i: VTYPE(GET(env, i)); an FVAR's type is a word of its record (ISA 4.5.1)
                int x = Runs.get(m, eb, en, a);
                return x >= SIG ? x : vtype(m, x, d + 1);
            }
            case Kind.SORT: {
                // Sort l : Sort (succ l); succ is not simplified (R02 6.1). CHECKED: CHKLVL(l)
                if (mode == Op.CHECKED && !m.x5.trusted) {
                    int e = chkLevel(m, a);
                    if (e != 0) {
                        return e;
                    }
                }
                int l = ls == 0 ? a : Levels.lsub(m, a, ls);
                return Values.mkSort(m, m.levels.succ(l));
            }
            case Kind.CONST:
                return inferConst(m, t, a, ls, mode, d);
            case Kind.NATLIT: {
                // infer_lit (R02 6.9): the numeral bound of 7.6, then Const Nat []
                int ty = natType(m);
                if (ty < SIG && m.terms.isNatBig(t)) {
                    int e = natSizeSlow(m, t);
                    if (e != 0) {
                        return e;
                    }
                }
                return ty;
            }
            case Kind.STRLIT:
                return strType(m);
            default:
                return m.fault(MachineErrors.E_INTERNAL); // no such node reaches the machine (FVAR nodes are overlay-only)
        }
    }

    /**
     * INFER of a constant (ISA 4.5.1 const, 4.5 CHKCONST; official {@code infer_constant}, R02 6.2): unknown row or
     * wrong level count fail in both modes, CHECKED also tests every level (CHKLVL); all of it is discharged
     * for a root that passed the summaries (5.3). An unbound constant node is a fault, never a REJECT (ISA 4.2
     * KCONST, section 9). Result: VTYPE of the CONST record = the type at these levels.
     */
    private static int inferConst(Machine m, int t, int cid, int ls, int mode, int d) {
        if (cid <= 0) {
            return m.fault(MachineErrors.E_INTERNAL);
        }
        int ll = m.terms.b(t);
        if (!m.x5.trusted) {
            int e = chkConstSlow(m, cid, ll, mode);
            if (e != 0) {
                return e;
            }
        }
        if (ls != 0 && ll != IntLists.EMPTY) {
            ll = Levels.lsubList(m, ll, ls);
        }
        int c = Values.mkConst(m, cid, ll);
        return c >= SIG ? c : vtype(m, c, d + 1);
    }

    /** CHKCONST (ISA 4.5): row visible, {@code #levels = #lparams}, CHECKED: CHKLVL on every level. 0 = passed. */
    private static int chkConstSlow(Machine m, int cid, int ll, int mode) {
        DeclTable decls = m.decls;
        if (decls.kind(cid) == DeclKind.NONE || decls.rank(cid) >= m.decl) {
            return m.fail(MachineErrors.E_UNKNOWN_CONST);
        }
        IntLists lists = m.lists;
        int n = ll == IntLists.EMPTY ? 0 : lists.length(ll);
        if (n != decls.numLevelParams(cid)) {
            return m.fail(MachineErrors.E_LEVEL_ARITY);
        }
        if (mode == Op.CHECKED) {
            for (int i = 0; i < n; i++) {
                int e = chkLevel(m, lists.get(ll, i));
                if (e != 0) {
                    return e;
                }
            }
        }
        return 0;
    }

    /** CHKLVL (ISA 4.5; official {@code check_level}, R02 6.1): every parameter of the level is declared. 0 = passed. */
    private static int chkLevel(Machine m, int level) {
        int lp = m.x5.declLparams;
        if (lp >= 0 && !m.levels.paramsDeclared(level, lp)) {
            return m.fail(MachineErrors.E_LEVEL_PARAM);
        }
        return 0;
    }

    /** The numeral bound of R02 7.6 on a literal node whose value is in the literal pool. 0 = passed. */
    private static int natSizeSlow(Machine m, int t) {
        try {
            m.ctx.nats.checkLiteralSize(t);
            return 0;
        } catch (KernelException e) {
            return m.fail(MachineErrors.E_NATSIZE);
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // TWALK: application inference (ISA 4.5.4, generic; official infer_app, R02 6.5)
    // ---------------------------------------------------------------------------------------------------
    //
    // Registers of a running TWALK, in scratch at X[xb ..] (its frames carry xb and at most four more words):
    //
    //   TW_N                        number of arguments
    //   TW_EB, TW_EN, TW_LS, TW_MODE   the site: environment of the argument terms, substitution, mode
    //   TW_PT   0 = VALUE MODE: the type of the spine prefix is the value p carried by the loop
    //           else TELESCOPE MODE: the Pi node whose binder the next argument instantiates
    //   TW_F0, TW_PLS   telescope mode: frame run and ls of the PI record the stretch started from
    //   TW_K0           telescope mode: index of the first argument of the stretch
    //   TW_ARGS + j     the term of argument j until it is done, then its value g_j (HOLE for an unread binder)
    //
    // Telescope mode is the generic tier's form of ISA 4.5.4 "while the codomain is a syntactic Pi keep
    // (body, PUSH(frame, g_i)) instead of a PI record" (R09 section 9 trick 2; the official infer-only infer_app
    // walks the syntactic Pis and instantiates once per group, R02 6.5): the type after argument i is the Pi
    // node TW_PT under the environment ENTER(F0) ++ g[K0 .. i], which is never made into a closure. A domain
    // or the final codomain is evaluated under that environment when it is needed (a closed term under the
    // empty one); the environment is built on top of es for that one EVAL and cut back after it, so the
    // environment of the argument terms stays extensible in place while arguments are inferred. The values
    // are the ones INST and DOM give: by the mask contract (ISA 2.4) a closure depends on its environment only
    // at the positions its node reads.

    private static final int TW_N = 0;
    private static final int TW_EB = 1;
    private static final int TW_EN = 2;
    private static final int TW_LS = 3;
    private static final int TW_MODE = 4;
    private static final int TW_PT = 5;
    private static final int TW_F0 = 6;
    private static final int TW_PLS = 7;
    private static final int TW_K0 = 8;
    private static final int TW_ARGS = 9;

    /**
     * app (ISA 4.5.1, 4.5.4): the spine's argument terms go to scratch, the head is inferred, then TWALK takes
     * one argument after the other, strictly left to right (6.1 last paragraph).
     */
    private static int inferApp(Machine m, int t, int eb, int en, int ls, int mode, int d) {
        Terms terms = m.terms;
        int n = terms.spineLength(t);
        int xb = m.xp;
        if (!m.ensureScratch(TW_ARGS + n)) {
            return m.fault(MachineErrors.E_MEM);
        }
        int[] x = m.X;
        int h = terms.spine(t, x, xb + TW_ARGS);
        x[xb + TW_N] = n;
        x[xb + TW_EB] = eb;
        x[xb + TW_EN] = en;
        x[xb + TW_LS] = ls;
        x[xb + TW_MODE] = mode;
        x[xb + TW_PT] = 0;
        m.xp = xb + TW_ARGS + n;
        if ((m.sw & Op.SW_TELE) != 0 && terms.kind(h) == Kind.CONST && terms.a(h) > 0) {
            // staging step 3 (M7's K1 hook): TELE(c) on the site (ISA 4.5.4); 0 = the role is walked
            int blk = Code.blockFor(m, terms.a(h), Op.ROLE_TELE);
            if (blk != 0) {
                return blk >= SIG ? blk : teleApp(m, blk, h, xb, d);
            }
        }
        int p = infer(m, h, eb, en, ls, mode, d + 1); // call INFER(head) => TWALK[1]
        return p >= SIG ? m.sig1(p, K_TW_1, xb) : twalk(m, xb, 0, p, d);
    }

    /**
     * TELE(c) on the site (ISA 4.5.4; staging step 3, SW_TELE; the block and its stepper are M7's): the head's
     * own checks (CHKCONST; its type is only needed if the head is suspended, then the generic walk resumes),
     * the block on the first {@code min(n, arity)} argument terms (E_APP_MISMATCH at the same argument as
     * TWALK), then TWALK in value mode for the arguments beyond the block's arity (frame TWALK[7], which
     * continues the loop after argument {@code arity - 1}).
     */
    private static int teleApp(Machine m, int blk, int h, int xb, int d) {
        int[] x = m.X;
        int n = x[xb + TW_N];
        int eb = x[xb + TW_EB];
        int en = x[xb + TW_EN];
        int ls = x[xb + TW_LS];
        int mode = x[xb + TW_MODE];
        int hp = infer(m, h, eb, en, ls, mode, d + 1);
        if (hp >= SIG) {
            return m.sig1(hp, K_TW_1, xb);
        }
        int c = Eval.eval(m, h, eb, en, ls, d + 1); // KCONST: inline
        if (c >= SIG) {
            return c;
        }
        int ar = Math.min(n, Code.arityOf(m, blk));
        int ep0 = m.ep;
        int r = Code.tele(m, blk, Levels.substOf(m, c), xb + TW_ARGS, n, eb, en, ls, mode, Layout.NONE, d + 1);
        return r >= SIG ? m.sig3(r, K_TW_7, xb, ar - 1, ep0) : twalk(m, xb, ar, r, d);
    }

    /** TWALK[1](xb): r = the type of the head. */
    static int k_tw1(Machine m, int xb, int r, int d) {
        return twalk(m, xb, 0, r, d);
    }

    /**
     * The TWALK loop from argument {@code i}; in value mode {@code p} is the type of the spine prefix before
     * it. Per argument (official order of {@code infer_app}, check mode, one App node at a time): ENPI(P);
     * CHECKED: INFER(t_i), CONV(type, DOM(P)) under ER for an {@code eagerReduce _ _} argument, else
     * fail(E_APP_MISMATCH); the argument value only if the binder is read (B6); P = INST(P, g_i). ONLY mode:
     * ENPI, value, INST.
     */
    private static int twalk(Machine m, int xb, int i, int p, int d) {
        int n = m.X[xb + TW_N];
        for (; i < n; i++) {
            if (m.X[xb + TW_PT] == 0) {
                if (!isPi(m, p)) {
                    p = enpi(m, p, d + 1); // call ENPI(P) => TWALK[2]
                    if (p >= SIG) {
                        return m.sig2(p, K_TW_2, xb, i);
                    }
                }
                twEnter(m, xb, i, p);
            } else {
                p = 0;
            }
            p = twArg(m, xb, i, p, d);
            if (p >= SIG) {
                return p;
            }
        }
        return twEnd(m, xb, p);
    }

    /** TWALK[2](xb, i): r = ENPI(P), a PI or PII. */
    static int k_tw2(Machine m, int xb, int i, int r, int d) {
        twEnter(m, xb, i, r);
        int p = twArg(m, xb, i, r, d);
        return p >= SIG ? p : twalk(m, xb, i + 1, p, d);
    }

    /**
     * Value mode, {@code p} a PI or PII: a PI record whose binder is read and whose codomain is again a
     * syntactic Pi starts a telescope stretch at argument i. Its first domain is still taken from the record
     * (DOM, memoised). A binder that is not read stays with INST, which answers from the COD memo (ISA 2.6).
     */
    private static void twEnter(Machine m, int xb, int i, int p) {
        int o = p << Layout.REC_SHIFT;
        int[] vh = m.vh;
        if ((vh[o] >>> Layout.KIND_SHIFT) == Layout.PI) {
            Terms terms = m.terms;
            int t = vh[o + Layout.A];
            int body = terms.b(t);
            if (terms.kind(body) == Kind.PI && (terms.word(body, Node.W_MASK_LO) & 1) != 0) {
                int[] x = m.X;
                x[xb + TW_PT] = t;
                x[xb + TW_F0] = vh[o + Layout.B];
                x[xb + TW_PLS] = vh[o + Layout.C];
                x[xb + TW_K0] = i;
            }
        }
    }

    /**
     * One argument. {@code p} != 0: the PI / PII record of the prefix's type (value mode, or the first argument
     * of a telescope stretch); {@code p} == 0: telescope mode, the binder is the node TW_PT.
     * @return the type after the argument in value mode, 0 in telescope mode, or a signal
     */
    private static int twArg(Machine m, int xb, int i, int p, int d) {
        int[] x = m.X;
        if (x[xb + TW_MODE] != Op.CHECKED) {
            return twE(m, xb, i, p, d);
        }
        if ((m.sw & Op.SW_EXPECT) != 0 && m.terms.kind(x[xb + TW_ARGS + i]) == Kind.LAM) {
            return twX(m, xb, i, p, d); // B9: expected-type propagation into a lambda argument
        }
        int tt = infer(m, x[xb + TW_ARGS + i], x[xb + TW_EB], x[xb + TW_EN], x[xb + TW_LS], Op.CHECKED, d + 1); // => TWALK[3]
        return tt >= SIG ? m.sig3(tt, K_TW_3, xb, i, p) : twC(m, xb, i, p, tt, d);
    }

    /** B9 (ISA 4.5.4 "t_i is a lam node: call CHECK(t_i, env, D)"; SW_EXPECT): the domain first, then CHECK. */
    private static int twX(Machine m, int xb, int i, int p, int d) {
        int ep0 = m.ep;
        int dom = p != 0 ? Eval.dom(m, p, d + 1) : twEval(m, xb, i, m.terms.a(m.X[xb + TW_PT]), d); // => TWALK[8]
        if (dom >= SIG) {
            return m.sig4(dom, K_TW_8, xb, i, p, ep0);
        }
        m.ep = ep0;
        return twY(m, xb, i, p, dom, d);
    }

    private static int twY(Machine m, int xb, int i, int p, int dom, int d) {
        int[] x = m.X;
        int c = check(m, x[xb + TW_ARGS + i], x[xb + TW_EB], x[xb + TW_EN], x[xb + TW_LS], dom, d + 1); // => TWALK[9]
        return c >= SIG ? m.sig3(c, K_TW_9, xb, i, p) : twE(m, xb, i, p, d);
    }

    /** TWALK[8](xb, i, p, ep0): r = the domain of the binder the lambda argument instantiates. */
    static int k_tw8(Machine m, int xb, int i, int p, int ep0, int r, int d) {
        m.ep = ep0;
        int q = twY(m, xb, i, p, r, d);
        return q >= SIG ? q : twalk(m, xb, i + 1, q, d);
    }

    /** TWALK[9](xb, i, p): the lambda argument has the domain as its type. */
    static int k_tw9(Machine m, int xb, int i, int p, int r, int d) {
        int q = twE(m, xb, i, p, d);
        return q >= SIG ? q : twalk(m, xb, i + 1, q, d);
    }

    /** TWALK[3](xb, i, p): r = the type of argument i. */
    static int k_tw3(Machine m, int xb, int i, int p, int r, int d) {
        int q = twC(m, xb, i, p, r, d);
        return q >= SIG ? q : twalk(m, xb, i + 1, q, d);
    }

    /** The domain the argument's type is compared with: DOM(P), or the domain term of TW_PT under the telescope. */
    private static int twC(Machine m, int xb, int i, int p, int tt, int d) {
        if (p == 0 && (m.sw & Op.SW_M5A) != 0) {
            int q = twMatchDom(m, xb, i, tt); // B5: MATCH(dom_i, g, Tt): the domain is not built
            if (q != Layout.F) {
                return q >= SIG ? q : twE(m, xb, i, p, d);
            }
        }
        int ep0 = m.ep;
        int dom = p != 0 ? Eval.dom(m, p, d + 1) : twEval(m, xb, i, m.terms.a(m.X[xb + TW_PT]), d); // => TWALK[4]
        if (dom >= SIG) {
            return m.sig5(dom, K_TW_4, xb, i, p, tt, ep0);
        }
        m.ep = ep0;
        return twD(m, xb, i, p, tt, dom, d);
    }

    /** TWALK[4](xb, i, p, Tt, ep0): r = the domain; the telescope's environment is cut back. */
    static int k_tw4(Machine m, int xb, int i, int p, int tt, int ep0, int r, int d) {
        m.ep = ep0;
        int q = twD(m, xb, i, p, tt, r, d);
        return q >= SIG ? q : twalk(m, xb, i + 1, q, d);
    }

    /**
     * {@code is_def_eq(a_type, d)} of {@code infer_app} (R02 6.5), with ER = 1 for the duration of this one
     * comparison iff the argument is {@code eagerReduce _ _} (R02 8.9). The frame keeps the ER to restore (the
     * ERF frame of ISA 1.3 folded into TWALK[5]); after ABORT / FATAL the TRY or DECL restores it (H7).
     */
    private static int twD(Machine m, int xb, int i, int p, int tt, int dom, int d) {
        if (tt != dom) {
            int saved = m.ER;
            if (isEagerReduce(m, m.X[xb + TW_ARGS + i])) {
                if (m.erDefers()) {
                    // [eager] Op.SW_EAGER: the comparison runs under ER when the plain judgments of the declaration are done
                    int q = m.erSite(tt, dom);
                    return q >= SIG ? q : twE(m, xb, i, p, d);
                }
                m.enterER(); // never a plain assignment: the facade must learn that this declaration used the mode (M6-F26)
            }
            int c = Conv.conv(m, tt, dom, d + 1); // call CONV(Tt, D) => TWALK[5]
            if (c >= SIG) {
                return m.sig4(c, K_TW_5, xb, i, p, saved);
            }
            m.ER = saved;
            if (c != Layout.T) {
                return m.fail(MachineErrors.E_APP_MISMATCH, tt, dom); // the argument's type and the domain
            }
        }
        return twE(m, xb, i, p, d);
    }

    /** TWALK[5](xb, i, p, savedER): r = CONV(Tt, D). */
    static int k_tw5(Machine m, int xb, int i, int p, int savedER, int r, int d) {
        m.ER = savedER;
        if (r != Layout.T) {
            return m.fail(MachineErrors.E_APP_MISMATCH);
        }
        int q = twE(m, xb, i, p, d);
        return q >= SIG ? q : twalk(m, xb, i + 1, q, d);
    }

    /** {@code g_i = binder i is read later ? ARGV(t_i) : HOLE} (ISA 4.5.4; B6, exact). */
    private static int twE(Machine m, int xb, int i, int p, int d) {
        int[] x = m.X;
        int pt = x[xb + TW_PT];
        int g = Layout.HOLE;
        if (pt != 0 ? (m.terms.word(m.terms.b(pt), Node.W_MASK_LO) & 1) != 0 : bodyReads0(m, p)) {
            g = Eval.argv(m, x[xb + TW_ARGS + i], x[xb + TW_EB], x[xb + TW_EN], x[xb + TW_LS], d + 1); // => TWALK[6]
            if (g >= SIG) {
                return m.sig3(g, K_TW_6, xb, i, p);
            }
        }
        return twF(m, xb, i, p, g, d);
    }

    /** TWALK[6](xb, i, p): r = the value of argument i. */
    static int k_tw6(Machine m, int xb, int i, int p, int r, int d) {
        int q = twF(m, xb, i, p, r, d);
        return q >= SIG ? q : twalk(m, xb, i + 1, q, d);
    }

    /**
     * {@code P = INST(P, g_i)}: the official {@code instantiate(binding_body(f_type), arg)}. Value mode: INST of
     * the record. Telescope mode: the binder is consumed; while the codomain is a syntactic Pi nothing is
     * built, otherwise the codomain is evaluated under the telescope's environment and the stretch ends.
     */
    private static int twF(Machine m, int xb, int i, int p, int g, int d) {
        int[] x = m.X;
        x[xb + TW_ARGS + i] = g; // the slot now holds the value: part of the telescope's environment
        int pt = x[xb + TW_PT];
        int ep0 = m.ep;
        int r;
        if (pt == 0) {
            r = Eval.inst(m, p, g, d + 1); // call INST(P, g) => TWALK[7]
        } else {
            Terms terms = m.terms;
            int cur = terms.b(pt);
            if (terms.kind(cur) == Kind.PI) {
                x[xb + TW_PT] = cur;
                return 0;
            }
            r = twEval(m, xb, i + 1, cur, d); // call EVAL(codomain) => TWALK[7]
        }
        if (r >= SIG) {
            return m.sig3(r, K_TW_7, xb, i, ep0);
        }
        m.ep = ep0;
        m.X[xb + TW_PT] = 0;
        return r;
    }

    /** TWALK[7](xb, i, ep0): r = the type after argument i, a value: back in value mode. */
    static int k_tw7(Machine m, int xb, int i, int ep0, int r, int d) {
        m.ep = ep0;
        m.X[xb + TW_PT] = 0;
        return twalk(m, xb, i + 1, r, d);
    }

    /**
     * Telescope mode: {@code EVAL(term, ENTER(F0) ++ g[K0 .. end), PLS)}, a judgment call of the caller. A closed
     * term needs no environment, a variable is read from its slot; otherwise the environment is built on top
     * of es (the caller cuts it back when the EVAL has returned, or its frame does).
     */
    private static int twEval(Machine m, int xb, int end, int term, int d) {
        Terms terms = m.terms;
        int pls = m.X[xb + TW_PLS];
        if (terms.fvMask(term) == 0) {
            return Eval.eval(m, term, 0, 0, pls, d + 1);
        }
        if (m.debug == 0 && terms.kind(term) == Kind.BVAR) {
            int v = twVar(m, xb, end, terms.a(term));
            if (v != Op.MISS) {
                return v;
            }
        }
        int wn = twEnv(m, xb, end);
        // the INST of a telescope: the term is entered like the body of a beta step (M2's rule: no tabling record
        // under SW_M2A, else EVAL as before; the same value either way)
        return wn < 0 ? m.fault(MachineErrors.E_MEM) : Eval.evalBody(m, term, m.envBase, wn, pls, d + 1);
    }

    /** The binding of de Bruijn index {@code k} in the telescope's environment, as EVAL of a variable gives it; MISS: not a near slot. */
    private static int twVar(Machine m, int xb, int end, int k) {
        int[] x = m.X;
        int s = end - x[xb + TW_K0];
        int v;
        if (k < s) {
            v = x[xb + TW_ARGS + end - 1 - k];
        } else {
            int f0 = x[xb + TW_F0];
            int j = k - s;
            if (f0 == Layout.RUN_EMPTY || (m.rs[f0] & Layout.RUN_FAR) != 0 || j >= Runs.len(m, f0)) {
                return Op.MISS;
            }
            v = Runs.fget(m, f0, j);
        }
        if (v == Layout.HOLE) {
            return m.fault(MachineErrors.E_HOLE);
        }
        return v > Layout.HOLE && (m.vh[v << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT) == Layout.SUSP ? Values.peek(m, v) : v;
    }

    /** Builds {@code ENTER(F0) ++ g[K0 .. end)} on top of es: its length, base in {@code m.envBase}; -1: es is full. */
    private static int twEnv(Machine m, int xb, int end) {
        int wn = Runs.enter(m, m.X[xb + TW_F0]);
        if (wn < 0) {
            return -1;
        }
        int wb = m.envBase;
        for (int j = m.X[xb + TW_K0]; j < end; j++) {
            wn = Runs.push(m, wb, wn, m.X[xb + TW_ARGS + j]);
            if (wn < 0) {
                return -1;
            }
            wb = m.envBase;
        }
        m.envBase = wb;
        return wn;
    }

    /**
     * All arguments are done. In telescope mode binders are left: the type is the Pi closure of the rest of
     * the telescope, {@code PI(TW_PT, PRUNE(env, mask), PLS)} (what EVAL of a pi node returns, ISA 4.2).
     */
    private static int twEnd(Machine m, int xb, int p) {
        int[] x = m.X;
        int pt = x[xb + TW_PT];
        if (pt != 0) {
            int ep0 = m.ep;
            int wn = twEnv(m, xb, x[xb + TW_N]);
            if (wn < 0) {
                return m.fault(MachineErrors.E_MEM);
            }
            int frame = Runs.prune(m, m.envBase, wn, m.terms.fvMask(pt));
            m.ep = ep0;
            p = Values.mkPi(m, pt, frame, m.X[xb + TW_PLS]);
        }
        m.xp = xb;
        return p;
    }

    /** Is the term {@code eagerReduce _ _} (the constant applied to exactly two arguments; R02 6.5, 8.9)? */
    private static boolean isEagerReduce(Machine m, int e) {
        int er = m.specials.cid(Specials.EAGER_REDUCE);
        if (er == 0) {
            return false;
        }
        Terms terms = m.terms;
        if (terms.kind(e) != Kind.APP) {
            return false;
        }
        int f = terms.a(e);
        if (terms.kind(f) != Kind.APP) {
            return false;
        }
        int h = terms.a(f);
        return terms.kind(h) == Kind.CONST && terms.a(h) == er;
    }

    // ---------------------------------------------------------------------------------------------------
    // MATCH on the generic tier (ISA 4.4.4, 4.5.4 "Tt != D and not MATCH(dom_i, g, Tt)", B5 of 6.3; SW_M5A):
    // compare the argument's type with the domain PATTERN instead of building the domain (rpylean's inst_eq,
    // R07 1.8). The pattern is a term of the head's type telescope; its variables are the registers of the
    // running TWALK: the environment ENTER(F0) ++ g[K0 .. end), which is never built.
    // ---------------------------------------------------------------------------------------------------

    /** Pattern nodes one MATCH may visit, and the nesting it follows; beyond either the answer is "unknown". */
    private static final int TM_NODES = Op.MATCH_NODES;
    private static final int TM_DEPTH = 16;

    /**
     * B5 for argument {@code i} of a TWALK in telescope mode: T if the argument's type {@code tt} is convertible
     * with the domain of the binder TW_PT without building that domain; F = unknown (the caller builds the
     * domain and compares, the complete procedure); or a signal (only from the audit). Leaf domains are left
     * to the caller: a variable is read from its slot and a constant is one probe. Under {@code shortcheck}
     * (ISA 5.5) a T is re-derived: the domain is built and CONV must agree.
     */
    private static int twMatchDom(Machine m, int xb, int i, int tt) {
        Terms terms = m.terms;
        int dt = terms.a(m.X[xb + TW_PT]);
        int k = terms.kind(dt);
        if ((k != Kind.APP && k != Kind.PI && k != Kind.LAM) || m.X[xb + TW_PLS] < 0) {
            return Layout.F; // (a substitution of the wrong arity is reported by the complete procedure)
        }
        Ext x5 = m.x5;
        x5.tmBudget = TM_NODES;
        boolean ok = tmatch(m, xb, i, dt, tt, 0);
        if (m.stats) {
            x5.matchTried++;
            if (ok) {
                x5.matchHit++;
            }
        }
        if (!ok) {
            return Layout.F;
        }
        return (m.debug & Op.DBG_SHORTCHECK) != 0 ? tmAuditSlow(m, xb, i, dt, tt) : Layout.T;
    }

    /** {@code shortcheck} of a MATCH that answered T: the domain is built after all and {@code CONV(tt, D)} must be T. */
    private static int tmAuditSlow(Machine m, int xb, int end, int dt, int tt) {
        int ep0 = m.ep;
        int dom = m.drive(twEval(m, xb, end, dt, 0));
        m.ep = ep0;
        if (dom >= SIG) {
            return dom;
        }
        if (dom == tt) {
            m.audits++; // the very record: nothing to convert
            return Layout.T;
        }
        int a = Debug.shortCheck(m, tt, dom, Layout.T);
        return a != 0 ? a : Layout.T;
    }

    /**
     * The binding of de Bruijn index {@code k} in the telescope's environment {@code ENTER(F0) ++ g[K0 .. end)} as it
     * is stored (not seen through PEEK); HOLE if it is not available here (a far frame, an unread binder).
     */
    private static int twRaw(Machine m, int xb, int end, int k) {
        int[] x = m.X;
        int s = end - x[xb + TW_K0];
        if (k < s) {
            return x[xb + TW_ARGS + end - 1 - k];
        }
        int f0 = x[xb + TW_F0];
        int j = k - s;
        if (f0 == Layout.RUN_EMPTY || (m.rs[f0] & Layout.RUN_FAR) != 0 || j >= Runs.len(m, f0)) {
            return Layout.HOLE;
        }
        return Runs.fget(m, f0, j);
    }

    /**
     * MATCH(p, g, v) (ISA 4.4.4): "{@code EVAL(p, ENTER(F0) ++ g[K0 .. end), PLS)} is convertible with {@code v}", decided
     * without building the left side. True is sound by congruence (R02 8.6: a constant applied to pairwise
     * convertible arguments at equivalent levels) or because {@code v} is the very record EVAL would return
     * (I-ID: a closure or a suspension of the same term under a frame that agrees with g on the term's mask).
     * False means unknown, never a refutation. No judgment is called and no record is made.
     */
    private static boolean tmatch(Machine m, int xb, int end, int p, int v, int depth) {
        if (--m.x5.tmBudget < 0 || depth > TM_DEPTH || (v >= 0 && v <= Layout.HOLE)) {
            return false;
        }
        int vk = Layout.K_IMM;
        if (v > 0) {
            vk = m.vh[v << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT;
            if (vk == Layout.SUSP) {
                int pv = Values.peek(m, v);
                if (pv == v) {
                    return tmSame(m, xb, end, p, v); // not evaluated yet: the suspension of this very term?
                }
                v = pv;
                vk = v < 0 ? Layout.K_IMM : m.vh[v << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT;
            }
        }
        return tmNode(m, xb, end, p, v, vk, depth);
    }

    /** One pattern node against a value of kind {@code vk} that is not an unevaluated suspension (the rules of {@link #tmatch}). */
    private static boolean tmNode(Machine m, int xb, int end, int p, int v, int vk, int depth) {
        Terms terms = m.terms;
        int[] reg = terms.regionOf(p);
        int o = Node.wordOf(p);
        int hdr = reg[o];
        switch (hdr & 0xF) {
            case Kind.BVAR:
                return tmVar(m, twRaw(m, xb, end, reg[o + Node.W_A]), v);
            case Kind.SORT:
                return vk == Layout.SORT && tmSort(m, reg[o + Node.W_A], hdr, m.X[xb + TW_PLS], v);
            case Kind.CONST:
                return vk == Layout.CONST && tmConst(m, reg[o + Node.W_A], reg[o + Node.W_B], hdr, m.X[xb + TW_PLS], v);
            case Kind.NATLIT:
                return tmLit(terms, p, v);
            case Kind.APP:
                return vk == Layout.NEU && tmApp(m, xb, end, p, v, depth);
            case Kind.LAM:
                return vk == Layout.LAM && tmSame(m, xb, end, p, v);
            case Kind.PI:
                return vk == Layout.PI && tmSame(m, xb, end, p, v);
            default:
                return false;
        }
    }

    /** A variable bound to {@code g} (as stored; HOLE = not available) against {@code v}: the same record, through PEEK. */
    private static boolean tmVar(Machine m, int g, int v) {
        return (g < 0 || g > Layout.HOLE) && (g > 0 ? Values.peek(m, g) : g) == v;
    }

    /** A small Nat literal node against the immediate of the same number (ISA 2.9); a pooled literal is unknown. */
    private static boolean tmLit(Terms terms, int p, int v) {
        return !terms.isNatBig(p) && terms.natSmall(p) >= 0 && terms.natSmall(p) < Layout.IMM_LIMIT
                && v == ((int) terms.natSmall(p) | Layout.IMM);
    }

    /** A sort node (level, header) under {@code pls} against the SORT record {@code v}: equivalent levels. */
    private static boolean tmSort(Machine m, int l, int hdr, int pls, int v) {
        if ((hdr & Node.F_HAS_LPARAM) != 0) {
            l = Levels.lsub(m, l, pls);
        }
        int vl = m.vh[(v << Layout.REC_SHIFT) + Layout.A];
        return l == vl || Levels.lvlEq(m, l, vl);
    }

    /** A constant node (row, level list, header) under {@code pls} against the CONST record {@code v}: same row, equivalent levels. */
    private static boolean tmConst(Machine m, int row, int ll, int hdr, int pls, int v) {
        int o = v << Layout.REC_SHIFT;
        if (m.vh[o + Layout.A] != row) {
            return false;
        }
        if ((hdr & Node.F_HAS_LPARAM) != 0 && pls != 0 && ll != IntLists.EMPTY) {
            ll = Levels.lsubList(m, ll, pls);
        }
        int vl = m.vh[o + Layout.B];
        return ll == vl || Levels.listsEq(m, ll, vl);
    }

    /**
     * An application against a NEU with as many arguments: the heads agree (a constant by row and levels; a
     * variable by identity with the head the NEU has) and the arguments match pairwise. Everything else
     * (an NSUC, a NEU that EVAL would have extended, another length) is unknown.
     */
    private static boolean tmApp(Machine m, int xb, int end, int p, int v, int depth) {
        Terms terms = m.terms;
        int o = v << Layout.REC_SHIFT;
        int n = m.vh[o] & Layout.AUX_MASK;
        int head = m.vh[o + Layout.A];
        int run = m.vh[o + Layout.B];
        int h = p;
        for (int i = 0; i < n; i++) {
            if (terms.kind(h) != Kind.APP) {
                return false; // the pattern has fewer arguments than the value
            }
            h = terms.a(h);
        }
        int[] reg = terms.regionOf(h);
        int ho = Node.wordOf(h);
        int hh = reg[ho];
        if (head <= Layout.HOLE) {
            return false;
        }
        switch (hh & 0xF) {
            case Kind.CONST:
                if ((m.vh[head << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT) != Layout.CONST
                        || !tmConst(m, reg[ho + Node.W_A], reg[ho + Node.W_B], hh, m.X[xb + TW_PLS], head)) {
                    return false;
                }
                break;
            case Kind.BVAR: {
                int g = twRaw(m, xb, end, reg[ho + Node.W_A]);
                if (g <= Layout.HOLE || Values.peek(m, g) != head) {
                    return false;
                }
                break;
            }
            default:
                return false; // more arguments than the value has, or a head that is neither
        }
        int t = p;
        for (int i = n - 1; i >= 0; i--) {
            if (!tmatch(m, xb, end, terms.b(t), Runs.arg(m, run, i), depth + 1)) {
                return false;
            }
            t = terms.a(t);
        }
        return true;
    }

    /**
     * Is the closure or suspension record {@code v} the record EVAL of binder / argument term {@code p} under the
     * telescope's environment returns (ISA 2.5 I-ID)? The same term, the same ls', and a near frame that agrees
     * with the environment on every index of the term's mask.
     */
    private static boolean tmSame(Machine m, int xb, int end, int p, int v) {
        int o = v << Layout.REC_SHIFT;
        int[] vh = m.vh;
        Terms terms = m.terms;
        if (vh[o + Layout.A] != p || vh[o + Layout.C] != (terms.hasLevelParam(p) ? m.X[xb + TW_PLS] : 0)) {
            return false;
        }
        long mask = terms.fvMask(p);
        int frame = vh[o + Layout.B];
        if (mask == 0) {
            return frame == Layout.RUN_EMPTY;
        }
        if (mask < 0 || frame == Layout.RUN_EMPTY) {
            return false;
        }
        int[] rs = m.rs;
        int fh = rs[frame];
        int len = 64 - Long.numberOfLeadingZeros(mask);
        if ((fh & Layout.RUN_FAR) != 0 || (fh >>> Layout.RUN_LEN_SHIFT) != len) {
            return false;
        }
        for (int idx = 0; idx < len; idx++) {
            if ((mask >>> idx & 1) != 0) {
                int g = twRaw(m, xb, end, idx);
                if (g == Layout.HOLE || rs[frame + Layout.RUN_H + len - 1 - idx] != g) {
                    return false;
                }
            }
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------------------
    // pi (ISA 4.5.1; official infer_pi, R02 6.4): frames INFER2[1..5]
    // ---------------------------------------------------------------------------------------------------

    /**
     * The Pi telescope from binder node {@code cur} under environment (eb, en). The sort levels of the
     * domains are kept in scratch {@code X[xb ..]} (one per open binder); {@code ep0} is the environment top to
     * restore (I-STACKS). Both modes compute every sort (R02 6.4).
     */
    private static int piLoop(Machine m, int cur, int eb, int en, int ls, int mode, int ep0, int xb, int d) {
        Terms terms = m.terms;
        while (terms.kind(cur) == Kind.PI) {
            int nb = piIter0(m, cur, eb, en, ls, mode, ep0, xb, d);
            if (nb >= SIG) {
                return nb;
            }
            eb = nb;
            en++;
            cur = terms.b(cur);
        }
        int r = infer(m, cur, eb, en, ls, mode, d + 1); // call INFER(body) => INFER2[4]
        return r >= SIG ? m.sig2(r, K_PI_4, ep0, xb) : k_pi4(m, ep0, xb, r, d);
    }

    /** One binder: {@code u = ENSORT(INFER(A)); x = OPEN(EVAL(A)); env = PUSH(env, x)}. Returns the new base. */
    private static int piIter0(Machine m, int cur, int eb, int en, int ls, int mode, int ep0, int xb, int d) {
        int r = infer(m, m.terms.a(cur), eb, en, ls, mode, d + 1); // call INFER(A) => INFER2[1]
        return r >= SIG ? m.sig7(r, K_PI_1, cur, eb, en, ls, mode, ep0, xb) : piIter1(m, cur, eb, en, ls, mode, ep0, xb, r, d);
    }

    private static int piIter1(Machine m, int cur, int eb, int en, int ls, int mode, int ep0, int xb, int r, int d) {
        int u = ensort(m, r, d + 1); // call ENSORT => INFER2[2]
        return u >= SIG ? m.sig7(u, K_PI_2, cur, eb, en, ls, mode, ep0, xb) : piIter2(m, cur, eb, en, ls, mode, ep0, xb, u, d);
    }

    private static int piIter2(Machine m, int cur, int eb, int en, int ls, int mode, int ep0, int xb, int u, int d) {
        if (!m.ensureScratch(1)) {
            return m.fault(MachineErrors.E_MEM);
        }
        m.X[m.xp++] = u;
        int dom = evalDom(m, m.terms.a(cur), eb, en, ls, d); // call EVAL(A), forced => INFER2[3]
        return dom >= SIG ? m.sig7(dom, K_PI_3, cur, eb, en, ls, mode, ep0, xb) : openPush(m, eb, en, dom);
    }

    /**
     * The value of a binder's domain term, FORCED: the type of a variable is never a suspension (M2's DOM is
     * forced as well, so INFER, CBIND and ETA give the binders of one type the same variables; ISA 2.8). A
     * step of the caller's judgment: if the EVAL is suspended, frame VTYPE2[7]() lies above the caller's.
     */
    static int evalDom(Machine m, int t, int eb, int en, int ls, int d) {
        int v = Eval.eval(m, t, eb, en, ls, d + 1); // call EVAL(A) => VTYPE2[7]
        return v >= SIG ? m.sig0(v, K_EVD_1) : k_evd1(m, v, d);
    }

    /** VTYPE2[7](): r = the value of a domain term; a suspension is forced (tail call). */
    static int k_evd1(Machine m, int r, int d) {
        return r > Layout.HOLE && (m.vh[r << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT) == Layout.SUSP ? Eval.force(m, r, d + 1) : r;
    }

    /** {@code x = OPEN(D); env = PUSH(env, x)}: the new environment base (its length is en + 1), or a signal. */
    static int openPush(Machine m, int eb, int en, int dom) {
        int x = Scope.open(m, dom);
        if (x >= SIG) {
            return x;
        }
        return Runs.push(m, eb, en, x) < 0 ? m.fault(MachineErrors.E_MEM) : m.envBase;
    }

    /** INFER2[1](cur, eb, en, ls, mode, ep0, xb): r = the type of the domain. */
    static int k_pi1(Machine m, int cur, int eb, int en, int ls, int mode, int ep0, int xb, int r, int d) {
        int nb = piIter1(m, cur, eb, en, ls, mode, ep0, xb, r, d);
        return nb >= SIG ? nb : piLoop(m, m.terms.b(cur), nb, en + 1, ls, mode, ep0, xb, d);
    }

    /** INFER2[2](..): r = the sort level of the domain. */
    static int k_pi2(Machine m, int cur, int eb, int en, int ls, int mode, int ep0, int xb, int r, int d) {
        int nb = piIter2(m, cur, eb, en, ls, mode, ep0, xb, r, d);
        return nb >= SIG ? nb : piLoop(m, m.terms.b(cur), nb, en + 1, ls, mode, ep0, xb, d);
    }

    /** INFER2[3](..): r = the domain value. */
    static int k_pi3(Machine m, int cur, int eb, int en, int ls, int mode, int ep0, int xb, int r, int d) {
        int nb = openPush(m, eb, en, r);
        return nb >= SIG ? nb : piLoop(m, m.terms.b(cur), nb, en + 1, ls, mode, ep0, xb, d);
    }

    /** INFER2[4](ep0, xb): r = the type of the body. */
    static int k_pi4(Machine m, int ep0, int xb, int r, int d) {
        int u = ensort(m, r, d + 1); // call ENSORT => INFER2[5]
        return u >= SIG ? m.sig2(u, K_PI_5, ep0, xb) : k_pi5(m, ep0, xb, u, d);
    }

    /**
     * INFER2[5](ep0, xb): r = the sort level of the body. Result {@code Sort (mkImax(u_1, mkImax(.., mkImax(u_n,
     * r))))}, built from the inside out with the simplifying mk_imax (R02 6.4); CLOSE all; stacks restored.
     */
    static int k_pi5(Machine m, int ep0, int xb, int r, int d) {
        int[] x = m.X;
        int top = m.xp;
        rlvm.api.LevelOps levels = m.levels;
        int l = r;
        for (int i = top - 1; i >= xb; i--) {
            l = levels.mkIMax(x[i], l);
        }
        Scope.close(m, top - xb);
        m.xp = xb;
        m.ep = ep0;
        return Values.mkSort(m, l);
    }

    // ---------------------------------------------------------------------------------------------------
    // lam, CHECKED (ISA 4.5.1; official infer_lambda, R02 6.3): frames INFER2[6..9]
    // ---------------------------------------------------------------------------------------------------

    /**
     * The lambda telescope in CHECKED mode: for each nested lambda {@code ENSORT(INFER(A, CHECKED)); D = EVAL(A);
     * x = OPEN(D); env = PUSH(env, x)} (the context grows after the domain was checked, R02 6.3); then the
     * body is checked; CLOSE all; the result is {@code pii} = PII(t, PRUNE(env0, mask t), ls').
     */
    private static int lamLoop(Machine m, int pii, int cur, int eb, int en, int ls, int ep0, int ssp0, int d) {
        Terms terms = m.terms;
        while (terms.kind(cur) == Kind.LAM) {
            int nb = lamIter0(m, pii, cur, eb, en, ls, ep0, ssp0, d);
            if (nb >= SIG) {
                return nb;
            }
            eb = nb;
            en++;
            cur = terms.b(cur);
        }
        int r = infer(m, cur, eb, en, ls, Op.CHECKED, d + 1); // call INFER(body, CHECKED) => INFER2[9]
        return r >= SIG ? m.sig3(r, K_LAM_4, pii, ep0, ssp0) : k_lam4(m, pii, ep0, ssp0, r, d);
    }

    private static int lamIter0(Machine m, int pii, int cur, int eb, int en, int ls, int ep0, int ssp0, int d) {
        int r = infer(m, m.terms.a(cur), eb, en, ls, Op.CHECKED, d + 1); // call INFER(A, CHECKED) => INFER2[6]
        return r >= SIG ? m.sig7(r, K_LAM_1, pii, cur, eb, en, ls, ep0, ssp0) : lamIter1(m, pii, cur, eb, en, ls, ep0, ssp0, r, d);
    }

    private static int lamIter1(Machine m, int pii, int cur, int eb, int en, int ls, int ep0, int ssp0, int r, int d) {
        int u = ensort(m, r, d + 1); // call ENSORT => INFER2[7]
        return u >= SIG ? m.sig7(u, K_LAM_2, pii, cur, eb, en, ls, ep0, ssp0) : lamIter2(m, pii, cur, eb, en, ls, ep0, ssp0, d);
    }

    private static int lamIter2(Machine m, int pii, int cur, int eb, int en, int ls, int ep0, int ssp0, int d) {
        int dom = evalDom(m, m.terms.a(cur), eb, en, ls, d); // call EVAL(A), forced => INFER2[8]
        return dom >= SIG ? m.sig7(dom, K_LAM_3, pii, cur, eb, en, ls, ep0, ssp0) : openPush(m, eb, en, dom);
    }

    /** INFER2[6](pii, cur, eb, en, ls, ep0, ssp0): r = the type of the domain. */
    static int k_lam1(Machine m, int pii, int cur, int eb, int en, int ls, int ep0, int ssp0, int r, int d) {
        int nb = lamIter1(m, pii, cur, eb, en, ls, ep0, ssp0, r, d);
        return nb >= SIG ? nb : lamLoop(m, pii, m.terms.b(cur), nb, en + 1, ls, ep0, ssp0, d);
    }

    /** INFER2[7](..): r = the sort level of the domain (only its existence matters). */
    static int k_lam2(Machine m, int pii, int cur, int eb, int en, int ls, int ep0, int ssp0, int r, int d) {
        int nb = lamIter2(m, pii, cur, eb, en, ls, ep0, ssp0, d);
        return nb >= SIG ? nb : lamLoop(m, pii, m.terms.b(cur), nb, en + 1, ls, ep0, ssp0, d);
    }

    /** INFER2[8](..): r = the domain value. */
    static int k_lam3(Machine m, int pii, int cur, int eb, int en, int ls, int ep0, int ssp0, int r, int d) {
        int nb = openPush(m, eb, en, r);
        return nb >= SIG ? nb : lamLoop(m, pii, m.terms.b(cur), nb, en + 1, ls, ep0, ssp0, d);
    }

    /**
     * INFER2[9](pii, ep0, ssp0): the body is checked; {@code r} is its type under the telescope's variables.
     *
     * <p>The PII recomputes the type of its body on demand, with one exception (RECONCILIATION section 3 "the
     * body type of a closed lambda-type closure is memoised"; gruszka's {@code lam_dm}, R05): for a single
     * lambda whose body type {@code r} is a closed value (flag OPEN clear: it mentions no variable at all, so
     * not the bound one), the codomain of the PII is {@code r} at every argument. That is the official
     * {@code instantiate(binding_body(f_type), arg)} of a body type without the bound variable (R02 6.3,
     * 6.5), and it is what keeps a ladder of beta-redexes ({@code have} chains, perf/beta-ladder) linear:
     * {@code (fun x => b) a} takes its type from COD instead of inferring {@code b} again at {@code a}.
     */
    static int k_lam4(Machine m, int pii, int ep0, int ssp0, int r, int d) {
        if (m.ssp - ssp0 == 1) {
            closedCod(m, pii, r);
        }
        Scope.closeTo(m, ssp0);
        m.ep = ep0;
        return pii;
    }

    /**
     * {@code COD(pii) = type} if the body type of the single lambda behind {@code pii} is a closed value (flag
     * OPEN clear, not a SUSP): NOTES-M5 I16. Not written while {@code memocheck} is on: M2's audit of COD
     * ({@code Eval.checkCod}) recomputes it at HOLE, which is only right for a body that does not read its
     * variable (requested of M2 on 2026-11-24, 09:05Z and 09:17Z; this guard goes when the audit takes the
     * caller's argument). The debug mode then runs without this one memo; no result depends on it.
     */
    private static void closedCod(Machine m, int pii, int type) {
        if (type >= Layout.FIRST_RECORD && (m.debug & Op.DBG_MEMOCHECK) == 0) {
            int w0 = m.vh[type << Layout.REC_SHIFT];
            if ((w0 & Layout.W0_OPEN) == 0 && (w0 >>> Layout.KIND_SHIFT) != Layout.SUSP) {
                m.vh[(pii << Layout.REC_SHIFT) + Layout.M_COD] = type;
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // let (ISA 4.5.1; official infer_let, R02 6.6): frames INFER2[10..15]
    // ---------------------------------------------------------------------------------------------------

    /**
     * The let chain from node {@code cur}: CHECKED: {@code ENSORT(INFER(A)); Tv = INFER(v); CONV(Tv, EVAL(A))}
     * else fail(E_LET_MISMATCH); both modes: {@code env = PUSH(env, MKLET(v, env, EVAL(A)))} (never a variable,
     * ISA 2.8; the suspension carries the DECLARED type {@code EVAL(A)} as the type of the let variable:
     * finding E1-F1, the official {@code infer_let} types the body under a variable of the declared type, and
     * {@code Tv} is only convertible with it); then the type of the body under the bound values, which is
     * the official let-telescope type after zeta. The frames INFER2[10..12, 14] exist in CHECKED mode only;
     * INFER2[13] (the value of the declared type) also in ONLY mode, with the word {@code Tv} = 0; the word
     * {@code exp} is the expected type of the body when the chain was started by CHECK (B9), else 0.
     */
    private static int letLoop(Machine m, int cur, int eb, int en, int ls, int mode, int exp, int ep0, int d) {
        Terms terms = m.terms;
        while (terms.kind(cur) == Kind.LET) {
            int nb = mode == Op.CHECKED ? letIter0(m, cur, eb, en, ls, exp, ep0, d) : letOnly(m, cur, eb, en, ls, exp, ep0, d);
            if (nb >= SIG) {
                return nb;
            }
            eb = nb;
            en++;
            cur = terms.c(cur);
        }
        // call INFER(body) => INFER2[15]; with an expected type (B9, ISA 4.5.2 let): call CHECK(body, env', V)
        int r = exp != 0 ? check(m, cur, eb, en, ls, exp, d + 1) : infer(m, cur, eb, en, ls, mode, d + 1);
        return r >= SIG ? m.sig1(r, K_LET_6, ep0) : k_let6(m, ep0, r, d);
    }

    /** ONLY mode: nothing is checked, but the let variable still carries its declared type: {@code EVAL(A)} => INFER2[13] with Tv = 0. */
    private static int letOnly(Machine m, int cur, int eb, int en, int ls, int exp, int ep0, int d) {
        int da = Eval.eval(m, m.terms.a(cur), eb, en, ls, d + 1);
        return da >= SIG ? m.sig7(da, K_LET_4, cur, eb, en, ls, exp, ep0, 0) : letBind(m, cur, eb, en, ls, da, false);
    }

    private static int letIter0(Machine m, int cur, int eb, int en, int ls, int exp, int ep0, int d) {
        int r = infer(m, m.terms.a(cur), eb, en, ls, Op.CHECKED, d + 1); // call INFER(A, CHECKED) => INFER2[10]
        return r >= SIG ? m.sig6(r, K_LET_1, cur, eb, en, ls, exp, ep0) : letIter1(m, cur, eb, en, ls, exp, ep0, r, d);
    }

    private static int letIter1(Machine m, int cur, int eb, int en, int ls, int exp, int ep0, int r, int d) {
        int u = ensort(m, r, d + 1); // call ENSORT => INFER2[11]
        return u >= SIG ? m.sig6(u, K_LET_2, cur, eb, en, ls, exp, ep0) : letIter2(m, cur, eb, en, ls, exp, ep0, d);
    }

    private static int letIter2(Machine m, int cur, int eb, int en, int ls, int exp, int ep0, int d) {
        int tv = infer(m, m.terms.b(cur), eb, en, ls, Op.CHECKED, d + 1); // call INFER(v, CHECKED) => INFER2[12]
        return tv >= SIG ? m.sig6(tv, K_LET_3, cur, eb, en, ls, exp, ep0) : letIter3(m, cur, eb, en, ls, exp, ep0, tv, d);
    }

    private static int letIter3(Machine m, int cur, int eb, int en, int ls, int exp, int ep0, int tv, int d) {
        int da = Eval.eval(m, m.terms.a(cur), eb, en, ls, d + 1); // call EVAL(A) => INFER2[13]
        return da >= SIG ? m.sig7(da, K_LET_4, cur, eb, en, ls, exp, ep0, tv) : letIter4(m, cur, eb, en, ls, exp, ep0, tv, da, d);
    }

    private static int letIter4(Machine m, int cur, int eb, int en, int ls, int exp, int ep0, int tv, int da, int d) {
        if (tv != da) {
            int c = Conv.conv(m, tv, da, d + 1); // call CONV(Tv, EVAL(A)) => INFER2[14]
            if (c >= SIG) {
                return m.sig7(c, K_LET_5, cur, eb, en, ls, exp, ep0, da);
            }
            if (c != Layout.T) {
                return m.fail(MachineErrors.E_LET_MISMATCH, tv, da); // the value's type and the declared type
            }
        }
        return letBind(m, cur, eb, en, ls, da, tv == da);
    }

    /**
     * {@code env = PUSH(env, MKLET(v, env, da))}: the new environment base, or a signal. {@code da} = the value of
     * the DECLARED type: it is the type of the let variable ({@code Values.mkLet}, finding E1-F1).
     */
    private static int letBind(Machine m, int cur, int eb, int en, int ls, int da, boolean exact) {
        Terms terms = m.terms;
        int v = terms.b(cur);
        int frame = Runs.prune(m, eb, en, terms.fvMask(v));
        if (frame >= SIG) {
            return frame;
        }
        int s = Values.mkLet(m, v, frame, terms.hasLevelParam(v) ? ls : 0, da);
        if (s >= SIG) {
            return s;
        }
        if (exact) {
            m.vh[(s << Layout.REC_SHIFT) + Layout.M1] = LET_EXACT; // the value's type is the declared type itself (E1-F6)
        }
        return Runs.push(m, eb, en, s) < 0 ? m.fault(MachineErrors.E_MEM) : m.envBase;
    }

    /** INFER2[10](cur, eb, en, ls, exp, ep0): r = the type of the let type. */
    static int k_let1(Machine m, int cur, int eb, int en, int ls, int exp, int ep0, int r, int d) {
        int nb = letIter1(m, cur, eb, en, ls, exp, ep0, r, d);
        return nb >= SIG ? nb : letLoop(m, m.terms.c(cur), nb, en + 1, ls, Op.CHECKED, exp, ep0, d);
    }

    /** INFER2[11](..): r = the sort level of the let type. */
    static int k_let2(Machine m, int cur, int eb, int en, int ls, int exp, int ep0, int r, int d) {
        int nb = letIter2(m, cur, eb, en, ls, exp, ep0, d);
        return nb >= SIG ? nb : letLoop(m, m.terms.c(cur), nb, en + 1, ls, Op.CHECKED, exp, ep0, d);
    }

    /** INFER2[12](..): r = the type of the value. */
    static int k_let3(Machine m, int cur, int eb, int en, int ls, int exp, int ep0, int r, int d) {
        int nb = letIter3(m, cur, eb, en, ls, exp, ep0, r, d);
        return nb >= SIG ? nb : letLoop(m, m.terms.c(cur), nb, en + 1, ls, Op.CHECKED, exp, ep0, d);
    }

    /** INFER2[13](.., Tv): r = the value of the let type. Tv = 0: the chain runs in ONLY mode ({@link #letOnly}). */
    static int k_let4(Machine m, int cur, int eb, int en, int ls, int exp, int ep0, int tv, int r, int d) {
        int nb = tv == 0 ? letBind(m, cur, eb, en, ls, r, false) : letIter4(m, cur, eb, en, ls, exp, ep0, tv, r, d);
        return nb >= SIG ? nb : letLoop(m, m.terms.c(cur), nb, en + 1, ls, tv == 0 ? Op.ONLY : Op.CHECKED, exp, ep0, d);
    }

    /** INFER2[14](.., EVAL(A)): r = CONV(Tv, EVAL(A)). */
    static int k_let5(Machine m, int cur, int eb, int en, int ls, int exp, int ep0, int da, int r, int d) {
        if (r != Layout.T) {
            return m.fail(MachineErrors.E_LET_MISMATCH);
        }
        int nb = letBind(m, cur, eb, en, ls, da, false);
        return nb >= SIG ? nb : letLoop(m, m.terms.c(cur), nb, en + 1, ls, Op.CHECKED, exp, ep0, d);
    }

    /** INFER2[15](ep0): r = the type of the body; the environment is cut back (I-STACKS). */
    static int k_let6(Machine m, int ep0, int r, int d) {
        m.ep = ep0;
        return r;
    }

    // ---------------------------------------------------------------------------------------------------
    // IPROJ (ISA 4.5.5; official infer_proj, R02 6.8): frames IPROJ[1..14]
    // ---------------------------------------------------------------------------------------------------

    /** proj (ISA 4.5.1): {@code Ts = WHNF(INFER(s, env, mode))}; CHECKED mode checks the struct (R02 6.8). */
    private static int inferProj(Machine m, int t, int eb, int en, int ls, int mode, int d) {
        int ts = infer(m, m.terms.a(t), eb, en, ls, mode, d + 1); // call INFER(struct) => IPROJ[1]
        return ts >= SIG ? m.sig4(ts, K_IP_1, t, eb, en, ls) : k_ip1(m, t, eb, en, ls, ts, d);
    }

    /** IPROJ[1](t, eb, en, ls): r = the type of the struct. */
    static int k_ip1(Machine m, int t, int eb, int en, int ls, int r, int d) {
        int w = Reduce.whnf(m, r, d + 1); // call WHNF => IPROJ[2]
        return w >= SIG ? m.sig4(w, K_IP_2, t, eb, en, ls) : k_ip2(m, t, eb, en, ls, w, d);
    }

    /** IPROJ[2](t, eb, en, ls): r = the weak head normal form of the struct's type. */
    static int k_ip2(Machine m, int t, int eb, int en, int ls, int r, int d) {
        Terms terms = m.terms;
        return projStart(m, r, terms.c(t), terms.b(t), Layout.NONE, terms.a(t), eb, en, ls, d);
    }

    /**
     * The projection chain, shared by the term form (INFER of a proj node: {@code sv} = NONE, the struct is the
     * term {@code st} under (eb, en, ls), evaluated when a later field first reads it) and the value form (VTYPE
     * of a PROJ record: {@code sv} = the struct value).
     *
     * <p>{@code ts} is the weak head normal form of the struct's type. It must be the inductive type
     * {@code structRow} applied to {@code nparams + nindices} arguments, with exactly one constructor, else
     * fail(E_PROJ). Then, as {@code infer_proj} (R02 6.8): the constructor's type at the levels of the type is
     * instantiated with the parameters; {@code p = ISPROP(ts)}; for each field j below {@code idx} whose
     * variable occurs later: if p, the field's domain must be a proposition, and the field is instantiated
     * with {@code MKPROJ(S, j, struct)}; the result is the domain of field {@code idx}, which must be a
     * proposition if p. An index beyond the fields fails with E_PROJ, because the rest of the constructor type
     * is then not a Pi (RECONCILIATION section 2 "Projections").
     */
    private static int projStart(Machine m, int ts, int structRow, int idx, int sv, int st, int eb, int en, int ls,
            int d) {
        int c = projCtor(m, ts, structRow, idx);
        if (c >= SIG) {
            return c;
        }
        int xb = m.xp;
        if (!m.ensureScratch(PJ_WORDS)) {
            return m.fault(MachineErrors.E_MEM);
        }
        int[] x = m.X;
        x[xb + PJ_TS] = ts;
        x[xb + PJ_S] = structRow;
        x[xb + PJ_IDX] = idx;
        x[xb + PJ_SV] = sv;
        x[xb + PJ_ST] = st;
        x[xb + PJ_EB] = eb;
        x[xb + PJ_EN] = en;
        x[xb + PJ_LS] = ls;
        x[xb + PJ_P] = Layout.F;
        x[xb + PJ_NP] = m.decls.inductNumParams(structRow);
        m.xp = xb + PJ_WORDS;
        int r = vtype(m, c, d + 1); // call VTYPE(constructor) => IPROJ[3]
        return r >= SIG ? m.sig1(r, K_IP_3, xb) : projParams(m, xb, 0, r, d);
    }

    /**
     * The tests of {@code infer_proj} on the struct's type (R02 6.8 rules 1 and 2): its head is the inductive
     * type named by the projection, it has exactly one constructor and {@code nparams + nindices} arguments.
     * @return the CONST record of the constructor at the levels of the type
     *     ({@code instantiate_type_lparams(ctor(I), levels(I))}), or a signal (E_PROJ)
     */
    private static int projCtor(Machine m, int ts, int structRow, int idx) {
        if (structRow <= 0) {
            return m.fault(MachineErrors.E_INTERNAL); // an unbound structure name: never a REJECT (ISA section 9)
        }
        if (idx < 0 || ts < 0) {
            return m.fail(MachineErrors.E_PROJ);
        }
        int[] vh = m.vh;
        int o = ts << Layout.REC_SHIFT;
        int kind = vh[o] >>> Layout.KIND_SHIFT;
        int head = ts;
        int nargs = 0;
        if (kind == Layout.NEU) {
            head = vh[o + Layout.A];
            nargs = vh[o] & Layout.AUX_MASK;
            kind = head < 0 ? Layout.K_IMM : vh[head << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT;
        }
        if (kind != Layout.CONST) {
            return m.fail(MachineErrors.E_PROJ);
        }
        int ho = head << Layout.REC_SHIFT;
        int row = vh[ho + Layout.A];
        DeclTable decls = m.decls;
        if (row != structRow || decls.kind(row) != DeclKind.INDUCT) {
            return m.fail(MachineErrors.E_PROJ);
        }
        if (decls.inductCtorCount(row) != 1 || nargs != decls.inductNumParams(row) + decls.inductNumIndices(row)) {
            return m.fail(MachineErrors.E_PROJ);
        }
        return Values.mkConst(m, decls.inductCtor(row, 0), vh[ho + Layout.B]);
    }

    /** IPROJ[3](xb): r = the constructor's type. */
    static int k_ip3(Machine m, int xb, int r, int d) {
        return projParams(m, xb, 0, r, d);
    }

    /** {@code for i < nparams: r = whnf(r); not Pi: throw; r = instantiate(body(r), args[i])}; then ISPROP(ts). */
    private static int projParams(Machine m, int xb, int i, int r, int d) {
        int np = m.X[xb + PJ_NP];
        for (; i < np; i++) {
            if (!isPi(m, r)) {
                r = Reduce.whnf(m, r, d + 1); // call WHNF => IPROJ[4]
                if (r >= SIG) {
                    return m.sig2(r, K_IP_4, xb, i);
                }
            }
            r = pjParam(m, xb, i, r, d);
            if (r >= SIG) {
                return r;
            }
        }
        int p = isprop(m, m.X[xb + PJ_TS], d + 1); // call ISPROP(Ts) => IPROJ[6]
        return p >= SIG ? m.sig2(p, K_IP_6, xb, r) : k_ip6(m, xb, r, p, d);
    }

    /** The parameter {@code i} of the type instantiates the constructor's type {@code r} (a Pi, or E_PROJ). */
    private static int pjParam(Machine m, int xb, int i, int r, int d) {
        if (!isPi(m, r)) {
            return m.fail(MachineErrors.E_PROJ);
        }
        int ts = m.X[xb + PJ_TS];
        int arg = Runs.arg(m, m.vh[(ts << Layout.REC_SHIFT) + Layout.B], i);
        int q = Eval.inst(m, r, arg, d + 1); // call INST => IPROJ[5]
        return q >= SIG ? m.sig2(q, K_IP_5, xb, i) : q;
    }

    /** IPROJ[4](xb, i): r = WHNF of the constructor type before parameter i. */
    static int k_ip4(Machine m, int xb, int i, int r, int d) {
        int q = pjParam(m, xb, i, r, d);
        return q >= SIG ? q : projParams(m, xb, i + 1, q, d);
    }

    /** IPROJ[5](xb, i): r = the constructor type after parameter i. */
    static int k_ip5(Machine m, int xb, int i, int r, int d) {
        return projParams(m, xb, i + 1, r, d);
    }

    /** IPROJ[6](xb, r): p = ISPROP(Ts) ({@code is_prop_type}; it may have failed, R02 6.10). */
    static int k_ip6(Machine m, int xb, int r, int p, int d) {
        m.X[xb + PJ_P] = p;
        return projFields(m, xb, 0, r, d);
    }

    /** The fields below the projected one, from field {@code j}; {@code r} = the constructor type before it. */
    private static int projFields(Machine m, int xb, int j, int r, int d) {
        int idx = m.X[xb + PJ_IDX];
        for (; j < idx; j++) {
            if (!isPi(m, r)) {
                r = Reduce.whnf(m, r, d + 1); // call WHNF => IPROJ[7]
                if (r >= SIG) {
                    return m.sig2(r, K_IP_7, xb, j);
                }
            }
            r = pjField(m, xb, j, r, d);
            if (r >= SIG) {
                return r;
            }
        }
        if (!isPi(m, r)) {
            r = Reduce.whnf(m, r, d + 1); // call WHNF => IPROJ[12]
            if (r >= SIG) {
                return m.sig1(r, K_IP_12, xb);
            }
        }
        return pjLast(m, xb, r, d);
    }

    /**
     * Field j with {@code r} its Pi: {@code has_loose_bvars(body(r))}: if the type is a proposition the field's
     * domain must be one (exact occurrence test first, because this can reject); then
     * {@code r = instantiate(body(r), proj S j struct)}; a field no later type reads gets HOLE (B6).
     */
    private static int pjField(Machine m, int xb, int j, int r, int d) {
        if (!isPi(m, r)) {
            return m.fail(MachineErrors.E_PROJ);
        }
        if (!bodyReads0(m, r)) {
            return pjInst(m, xb, j, r, Layout.HOLE, d);
        }
        if (m.X[xb + PJ_P] == Layout.T && bodyReads0ExactSlow(m, r)) {
            int dom = Eval.dom(m, r, d + 1); // call DOM => IPROJ[8]
            return dom >= SIG ? m.sig3(dom, K_IP_8, xb, j, r) : pjFieldProp(m, xb, j, r, dom, d);
        }
        return pjStruct(m, xb, j, r, d);
    }

    private static int pjFieldProp(Machine m, int xb, int j, int r, int dom, int d) {
        int q = isprop(m, dom, d + 1); // call ISPROP(dom) => IPROJ[9]
        if (q >= SIG) {
            return m.sig3(q, K_IP_9, xb, j, r);
        }
        return q != Layout.T ? m.fail(MachineErrors.E_PROJ) : pjStruct(m, xb, j, r, d);
    }

    /** The struct value, evaluated once, when the first field that is read needs it (ISA 4.5.5). */
    private static int pjStruct(Machine m, int xb, int j, int r, int d) {
        int[] x = m.X;
        int sv = x[xb + PJ_SV];
        if (sv == Layout.NONE) {
            sv = Eval.eval(m, x[xb + PJ_ST], x[xb + PJ_EB], x[xb + PJ_EN], x[xb + PJ_LS], d + 1); // call EVAL(s) => IPROJ[10]
            if (sv >= SIG) {
                return m.sig3(sv, K_IP_10, xb, j, r);
            }
            m.X[xb + PJ_SV] = sv;
        }
        int g = Values.mkProj(m, m.X[xb + PJ_S], j, sv);
        return g >= SIG ? g : pjInst(m, xb, j, r, g, d);
    }

    private static int pjInst(Machine m, int xb, int j, int r, int g, int d) {
        int q = Eval.inst(m, r, g, d + 1); // call INST => IPROJ[11]
        return q >= SIG ? m.sig2(q, K_IP_11, xb, j) : q;
    }

    /** IPROJ[7](xb, j): r = WHNF of the constructor type before field j. */
    static int k_ip7(Machine m, int xb, int j, int r, int d) {
        int q = pjField(m, xb, j, r, d);
        return q >= SIG ? q : projFields(m, xb, j + 1, q, d);
    }

    /** IPROJ[8](xb, j, pi): r = the domain of field j. */
    static int k_ip8(Machine m, int xb, int j, int pi, int r, int d) {
        int q = pjFieldProp(m, xb, j, pi, r, d);
        return q >= SIG ? q : projFields(m, xb, j + 1, q, d);
    }

    /** IPROJ[9](xb, j, pi): r = ISPROP(domain of field j). */
    static int k_ip9(Machine m, int xb, int j, int pi, int r, int d) {
        if (r != Layout.T) {
            return m.fail(MachineErrors.E_PROJ);
        }
        int q = pjStruct(m, xb, j, pi, d);
        return q >= SIG ? q : projFields(m, xb, j + 1, q, d);
    }

    /** IPROJ[10](xb, j, pi): r = the struct value. */
    static int k_ip10(Machine m, int xb, int j, int pi, int r, int d) {
        m.X[xb + PJ_SV] = r;
        int q = pjStruct(m, xb, j, pi, d);
        return q >= SIG ? q : projFields(m, xb, j + 1, q, d);
    }

    /** IPROJ[11](xb, j): r = the constructor type after field j. */
    static int k_ip11(Machine m, int xb, int j, int r, int d) {
        return projFields(m, xb, j + 1, r, d);
    }

    /** {@code r = whnf(r); not Pi: throw; r = dom(r); is_prop_type and not is_prop(r): throw; return r}. */
    private static int pjLast(Machine m, int xb, int r, int d) {
        if (!isPi(m, r)) {
            return m.fail(MachineErrors.E_PROJ);
        }
        int res = Eval.dom(m, r, d + 1); // call DOM => IPROJ[13]
        return res >= SIG ? m.sig1(res, K_IP_13, xb) : k_ip13(m, xb, res, d);
    }

    /** IPROJ[12](xb): r = WHNF of the constructor type before the projected field. */
    static int k_ip12(Machine m, int xb, int r, int d) {
        return pjLast(m, xb, r, d);
    }

    /** IPROJ[13](xb): r = the type of the projected field. */
    static int k_ip13(Machine m, int xb, int r, int d) {
        if (m.X[xb + PJ_P] == Layout.T) {
            int q = isprop(m, r, d + 1); // call ISPROP(R) => IPROJ[14]
            return q >= SIG ? m.sig2(q, K_IP_14, xb, r) : k_ip14(m, xb, r, q, d);
        }
        m.xp = xb;
        return r;
    }

    /** IPROJ[14](xb, R): r = ISPROP(R). */
    static int k_ip14(Machine m, int xb, int res, int r, int d) {
        if (r != Layout.T) {
            return m.fail(MachineErrors.E_PROJ);
        }
        m.xp = xb;
        return res;
    }

    // ---------------------------------------------------------------------------------------------------
    // CHECK
    // ---------------------------------------------------------------------------------------------------

    /**
     * CHECK(t, env, ls, V) (ISA 4.5.2): t has type V. Generic rule: INFER in CHECKED mode, then CONV(type, V) or
     * fail(E_TYPE_MISMATCH). The expected-type rules (B9) are step 1 ({@code Op.SW_EXPECT}); the expected type is never
     * stored (RECONCILIATION section 2). Official: {@code infer} + {@code is_def_eq} (admissible).
     * Entry frame: CHECK[0](t, eb, en, ls, expected).
     *
     * <p>The tabling of 4.5.2 is the tabling of the CHECKED INFER it calls: a hit answers by
     * {@code CONV(TYPE(s), V)}, and what is stored is the inferred type, never V.
     * @return T, or a signal
     */
    static int check(Machine m, int t, int eb, int en, int ls, int expected, int d) {
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig;
        }
        if (m.stats) {
            m.call(Op.CHECK);
        }
        if (d > m.hostDepth) {
            return m.spill5(Op.K_CHECK, t, eb, en, ls, expected);
        }
        if ((m.sw & Op.SW_EXPECT) != 0) {
            int kind = m.terms.kind(t);
            if ((kind == Kind.LAM || kind == Kind.LET) && ((m.sw & Op.SW_PCHK) == 0 || !pchkTerm(m, t))) {
                return checkExpect(m, t, kind, eb, en, ls, expected, d); // B9
            }
        }
        int tt = infer(m, t, eb, en, ls, Op.CHECKED, d + 1); // call INFER(t, CHECKED) => CHECK[1]
        return tt >= SIG ? m.sig1(tt, K_CHECK_1, expected) : k_check1(m, expected, tt, d);
    }

    // ---- B9: expected-type checking of lambdas and lets (ISA 4.5.2; staging step 1, SW_EXPECT): frames CHECK2[1..8]

    /**
     * CHECK of a lam or let node against V with the expected type pushed inwards (ISA 4.5.2, B9; admissible: if
     * {@code body : B[x]} and {@code D} is convertible with {@code DOM(W)} then {@code (fun x : D => body) : W}).
     * A tabled node whose checked type is stored answers by {@code CONV(TYPE(s), V)}; after the lam rule
     * succeeded, {@code TYPE(s) = PII(t, frame of s, ls')} is stored with the check stamp. V is never stored, and
     * the lam rule applies only when the lambda's domain and V's domain are one value ({@link #k_ck5}).
     */
    private static int checkExpect(Machine m, int t, int kind, int eb, int en, int ls, int expected, int d) {
        Terms terms = m.terms;
        int hdr = terms.header(t);
        int s = 0;
        if (tabled(m, t, hdr)) {
            s = suspRec(m, t, Runs.prune(m, eb, en, terms.fvMask(t)), ls);
            if (s >= SIG) {
                return s;
            }
            int ty = hit(m, s, Op.CHECKED);
            if (ty != 0) {
                return k_check1(m, expected, ty, d);
            }
        }
        if (kind == Kind.LET) {
            // bindings as INFER.let in CHECKED mode, then CHECK(body, env', V); nothing is stored for a let
            return letLoop(m, t, eb, en, ls, Op.CHECKED, expected, m.ep, d);
        }
        int w = Reduce.whnf(m, expected, d + 1); // call WHNF(V) => CHECK2[1]
        return w >= SIG ? m.sig6(w, K_CK_1, t, eb, en, ls, expected, s) : k_ck1(m, t, eb, en, ls, expected, s, w, d);
    }

    /** CHECK2[1](t, eb, en, ls, V, s): r = WHNF(V). Not a PI / PII: the generic rule. Else the domain is checked. */
    static int k_ck1(Machine m, int t, int eb, int en, int ls, int expected, int s, int r, int d) {
        if (!isPi(m, r)) {
            int tt = infer(m, t, eb, en, ls, Op.CHECKED, d + 1); // call INFER(t, CHECKED) => CHECK[1]
            return tt >= SIG ? m.sig1(tt, K_CHECK_1, expected) : k_check1(m, expected, tt, d);
        }
        int ta = infer(m, m.terms.a(t), eb, en, ls, Op.CHECKED, d + 1); // call INFER(A, CHECKED) => CHECK2[2]
        return ta >= SIG ? m.sig6(ta, K_CK_2, t, eb, en, ls, expected, r) : k_ck2(m, t, eb, en, ls, expected, r, ta, d);
    }

    /** CHECK2[2](t, eb, en, ls, V, W): r = the type of the domain. */
    static int k_ck2(Machine m, int t, int eb, int en, int ls, int expected, int w, int r, int d) {
        int u = ensort(m, r, d + 1); // call ENSORT => CHECK2[3]
        return u >= SIG ? m.sig6(u, K_CK_3, t, eb, en, ls, expected, w) : k_ck3(m, t, eb, en, ls, expected, w, u, d);
    }

    /** CHECK2[3](t, eb, en, ls, V, W): r = the sort level of the domain (only its existence matters). */
    static int k_ck3(Machine m, int t, int eb, int en, int ls, int expected, int w, int r, int d) {
        int dom = evalDom(m, m.terms.a(t), eb, en, ls, d); // call EVAL(A), forced => CHECK2[4]
        return dom >= SIG ? m.sig6(dom, K_CK_4, t, eb, en, ls, expected, w) : k_ck4(m, t, eb, en, ls, expected, w, dom, d);
    }

    /** CHECK2[4](t, eb, en, ls, V, W): r = D, the lambda's domain. */
    static int k_ck4(Machine m, int t, int eb, int en, int ls, int expected, int w, int r, int d) {
        int dw = Eval.dom(m, w, d + 1); // call DOM(W) => CHECK2[5]
        return dw >= SIG ? m.sig7(dw, K_CK_5, t, eb, en, ls, expected, w, r) : k_ck5(m, t, eb, en, ls, expected, w, r, dw, d);
    }

    /**
     * CHECK2[5](t, eb, en, ls, V, W, D): r = DOM(W). Without the switch {@code convexpect} ({@code Op.SW_CONV_EXPECT}; with it
     * {@link #ck5Conv}) B9 goes on only if the lambda's domain IS the expected domain
     * (the same value): then one variable of that domain is the variable the body is typed under AND the variable
     * the codomains are compared under, as in the official kernel. Two domains that are merely convertible need
     * two types for one binding (findings E1-F3 / E1-F4): the generic rule decides, INFER(t, CHECKED) and
     * CONV(type, V), where CBIND compares under a variable of V's domain and the lambda's own type is instantiated
     * under a typed binding ({@code Eval.instBody}).
     */
    static int k_ck5(Machine m, int t, int eb, int en, int ls, int expected, int w, int dom, int r, int d) {
        if (dom == r) {
            return k_ck6(m, t, eb, en, ls, expected, w, dom, Layout.T, d);
        }
        if ((m.sw & Op.SW_CONV_EXPECT) != 0) {
            return ck5Conv(m, t, eb, en, ls, expected, w, dom, r, d);
        }
        int tt = infer(m, t, eb, en, ls, Op.CHECKED, d + 1); // call INFER(t, CHECKED) => CHECK[1]
        return tt >= SIG ? m.sig1(tt, K_CHECK_1, expected) : k_check1(m, expected, tt, d);
    }

    /**
     * {@link #k_ck5} with the switch {@code convexpect}: the practical policy (OWNER-NOTES 13, 2026-11-24), B9 also for two
     * domains that are only convertible, as before the fix of E1-F4. The body is then typed under a variable of the
     * lambda's OWN domain, which can accept a derivable statement the official checker rejects (kind T; rows
     * e1-lam-fvar-type-in-conv-*); the restriction costs 2.4 % of the ticks of the Cedar slice.
     */
    private static int ck5Conv(Machine m, int t, int eb, int en, int ls, int expected, int w, int dom, int r, int d) {
        int c = Conv.conv(m, dom, r, d + 1); // call CONV(D, DOM(W)) => CHECK2[6]
        if (c < SIG && c != Layout.T) {
            return m.fail(MachineErrors.E_TYPE_MISMATCH, dom, r); // the lambda's domain and the expected one
        }
        return c >= SIG ? m.sig7(c, K_CK_6, t, eb, en, ls, expected, w, dom) : k_ck6(m, t, eb, en, ls, expected, w, dom, c, d);
    }

    /** The tabling record of the lambda t under the environment ({@link #checkExpect} made it; the frames carry V instead), 0 if t is not tabled. */
    private static int ckRecord(Machine m, int t, int eb, int en, int ls) {
        Terms terms = m.terms;
        return tabled(m, t, terms.header(t)) ? suspRec(m, t, Runs.prune(m, eb, en, terms.fvMask(t)), ls) : 0;
    }

    /** CHECK2[6](t, eb, en, ls, V, W, D): the domains are one value; {@code x = OPEN(D)} and INST(W, x). */
    static int k_ck6(Machine m, int t, int eb, int en, int ls, int expected, int w, int dom, int r, int d) {
        if (r != Layout.T) {
            return m.fail(MachineErrors.E_TYPE_MISMATCH);
        }
        int s = ckRecord(m, t, eb, en, ls);
        if (s >= SIG) {
            return s;
        }
        int ep0 = m.ep;
        int nb = openPush(m, eb, en, dom);
        if (nb >= SIG) {
            return nb;
        }
        int x = m.es[nb + en];
        int b = Eval.inst(m, w, x, d + 1); // call INST(W, x) => CHECK2[7]
        return b >= SIG ? m.sig6(b, K_CK_7, t, ls, s, nb, en + 1, ep0) : k_ck7(m, t, ls, s, nb, en + 1, ep0, b, d);
    }

    /** CHECK2[7](t, ls, s, eb', en', ep0): r = the expected type of the body. */
    static int k_ck7(Machine m, int t, int ls, int s, int eb, int en, int ep0, int r, int d) {
        int c = check(m, m.terms.b(t), eb, en, ls, r, d + 1); // call CHECK(body, env + x, B) => CHECK2[8]
        return c >= SIG ? m.sig4(c, K_CK_8, t, s, ep0, r) : k_ck8(m, t, s, ep0, r, c, d);
    }

    /**
     * CHECK2[8](t, s, ep0, B): the body has the expected type. CLOSE 1; the lambda's own type, its PII, is
     * stored in the tabling record with the check stamp (never V); a closed expected body type is the PII's
     * codomain at every argument (NOTES-M5 I16).
     */
    static int k_ck8(Machine m, int t, int s, int ep0, int b, int r, int d) {
        Scope.close(m, 1);
        m.ep = ep0;
        if (s != 0) {
            int o = s << Layout.REC_SHIFT;
            int pii = Values.mkPii(m, t, m.vh[o + Layout.B], m.vh[o + Layout.C]);
            if (pii >= SIG) {
                return pii;
            }
            // (finding E1-F5: the EXPECTED body type b is not stored as the PII's codomain; it is only
            // convertible with the body's own type, which INST(PII, x) infers when it is asked for)
            k_infer1(m, s, t, Op.CHECKED, pii, d);
        }
        return Layout.T;
    }

    /** CHECK[1](V): r = the inferred type; {@code Tt != V and CONV(Tt, V) != T: fail(E_TYPE_MISMATCH)}. */
    static int k_check1(Machine m, int expected, int r, int d) {
        if (r == expected) {
            return Layout.T;
        }
        int c = Conv.conv(m, r, expected, d + 1); // call CONV(Tt, V) => CHECK[2]
        if (c >= SIG) {
            return m.sig0(c, K_CHECK_2);
        }
        return c == Layout.T ? Layout.T : m.fail(MachineErrors.E_TYPE_MISMATCH, r, expected); // inferred and expected type
    }

    /** CHECK[2](): r = CONV(Tt, V). */
    static int k_check2(Machine m, int r, int d) {
        return r == Layout.T ? Layout.T : m.fail(MachineErrors.E_TYPE_MISMATCH);
    }

    // ---------------------------------------------------------------------------------------------------
    // VTYPE
    // ---------------------------------------------------------------------------------------------------

    /**
     * VTYPE(v) (ISA 4.5.3): the infer-only type of the term the value stands for; memo M_TYPE. Official: infer-only
     * {@code infer_type}. Entry frame: VTYPE[0](v).
     * @return the result, or a signal ({@code >= Op.SIG}): SUSP, ABORT, FATAL
     */
    static int vtype(Machine m, int v, int d) {
        int r = vtypeFast(m, v);
        if (r != Op.MISS) {
            return m.debug != 0 && r < SIG ? vtypeHitSlow(m, v, r) : r; // 1. inline answers (or the signal of one)
        }
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig;
        }
        if (m.stats) {
            m.call(Op.VTYPE);
        }
        if (d > m.hostDepth) {
            return m.spill1(Op.K_VTYPE, v);
        }
        return vtypeNode(m, v, d);
    }

    /**
     * {@code memocheck} (ISA 5.5) of VTYPE: M_TYPE of a CONST, NEU, PROJ or LAM record ({@code Debug.memoCheck}),
     * M_SLVL of a PI / PII (the generic form). The other inline answers are not memos.
     */
    private static int vtypeHitSlow(Machine m, int v, int hit) {
        if (v < Layout.FIRST_RECORD) {
            return hit;
        }
        int o = v << Layout.REC_SHIFT;
        switch (m.vh[o] >>> Layout.KIND_SHIFT) {
            case Layout.CONST:
            case Layout.NEU:
            case Layout.PROJ:
            case Layout.LAM:
                return m.vh[o + Layout.M_TYPE] == hit ? Debug.memoCheck(m, Op.VTYPE, v, 0, hit) : hit;
            case Layout.PI:
            case Layout.PII: {
                if (!Debug.memoEnter(m)) {
                    return hit;
                }
                int l = m.vh[o + Layout.M_SLVL];
                m.vh[o + Layout.M_SLVL] = 0;
                int r = m.drive(vtype(m, v, 0));
                m.vh[o + Layout.M_SLVL] = l;
                return Debug.memoExit(m, Op.VTYPE2, v, 0, hit, r);
            }
            default:
                return hit;
        }
    }

    /** The body of VTYPE for the kinds that need a sub-judgment (same judgment, same host depth). */
    private static int vtypeNode(Machine m, int v, int d) {
        int[] vh = m.vh;
        int o = v << Layout.REC_SHIFT;
        switch (vh[o] >>> Layout.KIND_SHIFT) {
            case Layout.SUSP: {
                // SUSP without a stored TYPE: VTYPE(FORCE(v)); nothing is stored in the tabling record
                int f = Eval.force(m, v, d + 1); // call FORCE => VTYPE[1]
                return f >= SIG ? m.sig0(f, K_VT_1) : vtype(m, f, d + 1);
            }
            case Layout.CONST: {
                // the type term of the row under the substitution of (row, levels) = word c (ISA 2.7)
                int ty = Eval.eval(m, m.decls.type(vh[o + Layout.A]), 0, 0, vh[o + Layout.C], d + 1); // => VTYPE[2]
                return ty >= SIG ? m.sig1(ty, K_VT_2, v) : k_vt2(m, v, ty, d);
            }
            case Layout.NEU: {
                if ((m.sw & Op.SW_TELE) != 0) { // staging step 3 (M7's K1 hook): TELE(c) on the argument values
                    int k1 = teleNeu(m, v, d);
                    if (k1 != Op.MISS) {
                        return k1;
                    }
                }
                int p = vtype(m, vh[o + Layout.A], d + 1); // call VTYPE(head) => VTYPE[3]
                return p >= SIG ? m.sig1(p, K_VT_3, v) : neuLoop(m, v, 0, p, d);
            }
            case Layout.PROJ: {
                int ts = vtype(m, vh[o + Layout.A], d + 1); // call VTYPE(struct) => VTYPE[6]
                return ts >= SIG ? m.sig1(ts, K_VT_6, v) : k_vt6(m, v, ts, d);
            }
            default: { // PI, PII (vtypeFast answered or faulted on every other kind)
                int dom = Eval.dom(m, v, d + 1); // call DOM => VTYPE2[1]
                return dom >= SIG ? m.sig1(dom, K_VP_1, v) : k_vp1(m, v, dom, d);
            }
        }
    }

    /**
     * The inline part of VTYPE: Nat literal / NSUC -> CONST Nat, FVAR -> its type, memo M_TYPE; else {@code Op.MISS}.
     * Also the kinds that need no sub-judgment: STR -> CONST String, SORT l -> SORT(succ l), LAM -> its PII, a PI
     * / PII whose sort level is known. May return a signal (a literal whose type constant is missing).
     */
    static int vtypeFast(Machine m, int v) {
        if (v < 0) {
            return natType(m);
        }
        int[] vh = m.vh;
        int o = v << Layout.REC_SHIFT;
        int kind = vh[o] >>> Layout.KIND_SHIFT;
        switch (kind) {
            case Layout.FVAR:
                return vh[o + Layout.A];
            case Layout.NBIG:
            case Layout.NSUC:
                return natType(m);
            case Layout.STR:
                return strType(m);
            case Layout.PI:
            case Layout.PII: {
                int l = vh[o + Layout.M_SLVL];
                return l != 0 ? Values.mkSort(m, l) : Op.MISS;
            }
            case Layout.SUSP:
            case Layout.CONST:
            case Layout.NEU:
            case Layout.PROJ: {
                int ty = vh[o + Layout.M_TYPE];
                return ty != 0 ? ty : Op.MISS;
            }
            case Layout.SORT:
            case Layout.LAM: {
                int ty = vh[o + Layout.M_TYPE];
                return ty != 0 ? ty : vtypeLeafSlow(m, v, kind);
            }
            default:
                return m.fault(v == Layout.HOLE ? MachineErrors.E_HOLE : MachineErrors.E_INTERNAL);
        }
    }

    /** {@code SORT l -> SORT(succ l)} (succ not simplified, R02 6.1); {@code LAM(t, fr, ls) -> PII(t, fr, ls)}; memoised. */
    private static int vtypeLeafSlow(Machine m, int v, int kind) {
        int o = v << Layout.REC_SHIFT;
        int[] vh = m.vh;
        int ty = kind == Layout.SORT ? Values.mkSort(m, m.levels.succ(vh[o + Layout.A]))
                : Values.mkPii(m, vh[o + Layout.A], vh[o + Layout.B], vh[o + Layout.C]);
        if (ty < SIG) {
            m.vh[o + Layout.M_TYPE] = ty;
        }
        return ty;
    }

    /** VTYPE[1](): r = the forced value of the suspension. */
    static int k_vt1(Machine m, int r, int d) {
        return vtype(m, r, d + 1);
    }

    /** VTYPE[2](v): r = the type of the CONST, NEU or PROJ record v; memo M_TYPE (I-MEMO). */
    static int k_vt2(Machine m, int v, int r, int d) {
        m.vh[(v << Layout.REC_SHIFT) + Layout.M_TYPE] = r;
        return r;
    }

    /** VTYPE[3](v): r = the type of the head of the NEU. */
    static int k_vt3(Machine m, int v, int r, int d) {
        return neuLoop(m, v, 0, r, d);
    }

    /**
     * NEU(h, args) (ISA 4.5.3): {@code P = VTYPE(h); for each argument a: P = ENPI(P); P = INST(P, a)}: the
     * infer-only {@code infer_app} (R02 6.5) on values.
     *
     * <p>As the official infer-only walk, which follows syntactic Pis and instantiates once per group: when P
     * is a PI record whose binder is read and whose codomain is again a syntactic Pi, the arguments that the
     * syntactic telescope covers are consumed in one step ({@link #neuGroup}): no PI record per argument
     * (ISA 4.5.4, R09 section 9 trick 2). Otherwise one INST, which answers a codomain that does not read
     * its variable from its COD memo and gives an unread binder HOLE itself (B6).
     */
    private static int neuLoop(Machine m, int v, int i, int p, int d) {
        int o = v << Layout.REC_SHIFT;
        int n = m.vh[o] & Layout.AUX_MASK;
        while (i < n) {
            if (!isPi(m, p)) {
                p = enpi(m, p, d + 1); // call ENPI => VTYPE[4]
                if (p >= SIG) {
                    return m.sig2(p, K_VT_4, v, i);
                }
            }
            int ep0 = m.ep;
            int next = neuStretch(m, p, i, n);
            p = next == i + 1 ? Eval.inst(m, p, Runs.arg(m, m.vh[o + Layout.B], i), d + 1) // call INST => VTYPE[5]
                    : neuGroup(m, v, p, i, next, d);
            if (p >= SIG) {
                return m.sig3(p, K_VT_5, v, next, ep0);
            }
            m.ep = ep0;
            i = next;
        }
        m.vh[o + Layout.M_TYPE] = p;
        return p;
    }

    /**
     * The index after the last argument that the syntactic Pi telescope of the PI record {@code p} covers, from
     * argument {@code i}: {@code i + 1} (one INST) unless p is a PI whose first binder is read and whose
     * codomain is a syntactic Pi.
     */
    private static int neuStretch(Machine m, int p, int i, int n) {
        int po = p << Layout.REC_SHIFT;
        if ((m.vh[po] >>> Layout.KIND_SHIFT) != Layout.PI) {
            return i + 1;
        }
        Terms terms = m.terms;
        int cur = terms.b(m.vh[po + Layout.A]);
        if (terms.kind(cur) != Kind.PI || (terms.word(cur, Node.W_MASK_LO) & 1) == 0) {
            return i + 1;
        }
        int j = i + 1;
        while (j < n && terms.kind(cur) == Kind.PI) {
            cur = terms.b(cur);
            j++;
        }
        return j;
    }

    /**
     * Arguments {@code i .. end)} of the NEU {@code v} instantiate the first {@code end - i} syntactic Pis of the
     * PI record {@code p} at once: the environment is {@code ENTER(frame p) ++ args} (HOLE for a binder its
     * codomain does not read, as INST), and the type is the rest of the telescope under it: a Pi closure if
     * binders are left, else the value of the codomain (a judgment call of the caller, which cuts es back).
     */
    private static int neuGroup(Machine m, int v, int p, int i, int end, int d) {
        Terms terms = m.terms;
        int po = p << Layout.REC_SHIFT;
        int cur = m.vh[po + Layout.A];
        int pls = m.vh[po + Layout.C];
        int wn = Runs.enter(m, m.vh[po + Layout.B]);
        int wb = m.envBase;
        int spine = m.vh[(v << Layout.REC_SHIFT) + Layout.B];
        for (int j = i; j < end && wn >= 0; j++) {
            cur = terms.b(cur);
            boolean reads = (terms.word(cur, Node.W_MASK_LO) & 1) != 0;
            wn = Runs.push(m, wb, wn, reads ? Runs.arg(m, spine, j) : Layout.HOLE);
            wb = m.envBase;
        }
        if (wn < 0) {
            return m.fault(MachineErrors.E_MEM);
        }
        if (terms.kind(cur) == Kind.PI) {
            return Values.mkPi(m, cur, Runs.prune(m, wb, wn, terms.fvMask(cur)), pls);
        }
        return terms.fvMask(cur) == 0 ? Eval.eval(m, cur, 0, 0, pls, d + 1) : Eval.eval(m, cur, wb, wn, pls, d + 1);
    }

    /**
     * VTYPE of a NEU with a constant head through TELE(c) on the spine (ISA 4.5.3; staging step 3, SW_TELE; the
     * block is M7's), then the loop for the arguments beyond its arity. MISS: the role is walked.
     */
    private static int teleNeu(Machine m, int v, int d) {
        int[] vh = m.vh;
        int o = v << Layout.REC_SHIFT;
        int h = vh[o + Layout.A];
        if (h < Layout.FIRST_RECORD || (vh[h << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT) != Layout.CONST) {
            return Op.MISS;
        }
        int ho = h << Layout.REC_SHIFT;
        int blk = Code.blockFor(m, vh[ho + Layout.A], Op.ROLE_TELE);
        if (blk == 0 || blk >= SIG) {
            return blk == 0 ? Op.MISS : blk;
        }
        int n = vh[o] & Layout.AUX_MASK;
        int ar = Math.min(n, Code.arityOf(m, blk));
        int ep0 = m.ep;
        int r = Code.teleValues(m, blk, vh[ho + Layout.C], vh[o + Layout.B], n, d + 1);
        return r >= SIG ? m.sig3(r, K_VT_5, v, ar, ep0) : neuLoop(m, v, ar, r, d);
    }

    /** VTYPE[4](v, i): r = ENPI of the type before argument i. */
    static int k_vt4(Machine m, int v, int i, int r, int d) {
        return neuLoop(m, v, i, r, d);
    }

    /** VTYPE[5](v, next, ep0): r = the type before argument {@code next}; the group's environment is cut back. */
    static int k_vt5(Machine m, int v, int next, int ep0, int r, int d) {
        m.ep = ep0;
        return neuLoop(m, v, next, r, d);
    }

    /** VTYPE[6](v): v = PROJ(s, S, i); r = the type of the struct value. */
    static int k_vt6(Machine m, int v, int r, int d) {
        int w = Reduce.whnf(m, r, d + 1); // call WHNF => VTYPE[7]
        return w >= SIG ? m.sig1(w, K_VT_7, v) : k_vt7(m, v, w, d);
    }

    /** VTYPE[7](v): r = WHNF of the struct's type; then IPROJ on the value (ISA 4.5.3). */
    static int k_vt7(Machine m, int v, int r, int d) {
        int o = v << Layout.REC_SHIFT;
        int[] vh = m.vh;
        int ty = projStart(m, r, vh[o + Layout.B], vh[o + Layout.C], vh[o + Layout.A], 0, 0, 0, 0, d);
        return ty >= SIG ? m.sig1(ty, K_VT_2, v) : k_vt2(m, v, ty, d);
    }

    // ---- PI, PII: SORT(SLVL), SLVL = mkImax(ENSORT(VTYPE(DOM)), ENSORT(VTYPE(INST(v, OPEN(DOM))))) -----------

    /** VTYPE2[1](v): r = DOM(v). */
    static int k_vp1(Machine m, int v, int r, int d) {
        int td = vtype(m, r, d + 1); // call VTYPE(DOM) => VTYPE2[2]
        return td >= SIG ? m.sig2(td, K_VP_2, v, r) : k_vp2(m, v, r, td, d);
    }

    /** VTYPE2[2](v, D): r = the type of the domain. */
    static int k_vp2(Machine m, int v, int dom, int r, int d) {
        int u = ensort(m, r, d + 1); // call ENSORT => VTYPE2[3]
        return u >= SIG ? m.sig2(u, K_VP_3, v, dom) : k_vp3(m, v, dom, u, d);
    }

    /** VTYPE2[3](v, D): r = the sort level of the domain. A binder is opened only if the body reads it (B6). */
    static int k_vp3(Machine m, int v, int dom, int r, int d) {
        int x = Layout.HOLE;
        if (bodyReads0(m, v)) {
            x = Scope.open(m, dom);
            if (x >= SIG) {
                return x;
            }
        }
        int b = Eval.inst(m, v, x, d + 1); // call INST(v, x) => VTYPE2[4]
        return b >= SIG ? m.sig2(b, K_VP_4, v, r) : k_vp4(m, v, r, b, d);
    }

    /** VTYPE2[4](v, u1): r = the codomain at the variable. */
    static int k_vp4(Machine m, int v, int u1, int r, int d) {
        int tb = vtype(m, r, d + 1); // call VTYPE(codomain) => VTYPE2[5]
        return tb >= SIG ? m.sig2(tb, K_VP_5, v, u1) : k_vp5(m, v, u1, tb, d);
    }

    /** VTYPE2[5](v, u1): r = the type of the codomain. */
    static int k_vp5(Machine m, int v, int u1, int r, int d) {
        int u2 = ensort(m, r, d + 1); // call ENSORT => VTYPE2[6]
        return u2 >= SIG ? m.sig2(u2, K_VP_6, v, u1) : k_vp6(m, v, u1, u2, d);
    }

    /** VTYPE2[6](v, u1): r = the sort level of the codomain; CLOSE; memo M_SLVL (I-MEMO). */
    static int k_vp6(Machine m, int v, int u1, int r, int d) {
        if (bodyReads0(m, v)) {
            Scope.close(m, 1);
        }
        int l = m.levels.mkIMax(u1, r);
        m.vh[(v << Layout.REC_SHIFT) + Layout.M_SLVL] = l;
        return Values.mkSort(m, l);
    }

    // ---------------------------------------------------------------------------------------------------
    // ENSORT, ENPI, ISPROP
    // ---------------------------------------------------------------------------------------------------

    /**
     * ENSORT(T) (ISA 4.5): SORT: its level; else WHNF; SORT: its level; else fail(E_TYPE_EXPECTED). Official:
     * {@code ensure_sort_core}. Entry frame: ENSORT[0](type).
     * @return the level id, or a signal
     */
    static int ensort(Machine m, int type, int d) {
        if (type >= 0) {
            int o = type << Layout.REC_SHIFT;
            int[] vh = m.vh;
            if ((vh[o] >>> Layout.KIND_SHIFT) == Layout.SORT) {
                return vh[o + Layout.A];
            }
        }
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig;
        }
        if (m.stats) {
            m.call(Op.ENSORT_F);
        }
        if (d > m.hostDepth) {
            return m.spill1(Op.K_ENSORT, type);
        }
        int w = Reduce.whnf(m, type, d + 1); // call WHNF => ENSORT[1]
        return w >= SIG ? m.sig0(w, K_ENSORT_1) : k_ensort1(m, w, d);
    }

    /** ENSORT[1](): r = WHNF(T). */
    static int k_ensort1(Machine m, int r, int d) {
        if (r >= 0) {
            int o = r << Layout.REC_SHIFT;
            int[] vh = m.vh;
            if ((vh[o] >>> Layout.KIND_SHIFT) == Layout.SORT) {
                return vh[o + Layout.A];
            }
        }
        return m.fail(MachineErrors.E_TYPE_EXPECTED, r, 0);
    }

    /**
     * ENPI(T) (ISA 4.5): PI / PII: itself; else WHNF; not PI / PII: fail(E_FUN_EXPECTED). Official:
     * {@code ensure_pi_core}. Entry frame: ENPI[0](type).
     * @return the result, or a signal ({@code >= Op.SIG}): SUSP, ABORT, FATAL
     */
    static int enpi(Machine m, int type, int d) {
        if (isPi(m, type)) {
            return type;
        }
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig;
        }
        if (m.stats) {
            m.call(Op.ENPI_F);
        }
        if (d > m.hostDepth) {
            return m.spill1(Op.K_ENPI, type);
        }
        int w = Reduce.whnf(m, type, d + 1); // call WHNF => ENPI[1]
        return w >= SIG ? m.sig0(w, K_ENPI_1) : k_enpi1(m, w, d);
    }

    /** ENPI[1](): r = WHNF(T). */
    static int k_enpi1(Machine m, int r, int d) {
        return isPi(m, r) ? r : m.fail(MachineErrors.E_FUN_EXPECTED, r, 0);
    }

    /**
     * ISPROP(T) (ISA 4.5): PROPT memo; else {@code l = ENSORT(VTYPE(T))} (a stuck sort FAILS; never "not a
     * proposition"); {@code normalizesToZero(l)}; sets PROPT. Official: {@code is_prop} (R02 6.10).
     * Entry frame: ISPROP[0](type).
     * @return T, F, or a signal
     */
    static int isprop(Machine m, int type, int d) {
        int r = ispropFast(m, type);
        if (r != Op.MISS) {
            return m.debug != 0 ? Debug.memoCheck(m, Op.ISPROP_F, type, 0, r) : r; // the PROPT memo (memocheck, ISA 5.5)
        }
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig;
        }
        if (m.stats) {
            m.call(Op.ISPROP_F);
        }
        if (d > m.hostDepth) {
            return m.spill1(Op.K_ISPROP, type);
        }
        int tt = vtype(m, type, d + 1); // call VTYPE(T) => ISPROP[1]
        return tt >= SIG ? m.sig1(tt, K_ISPROP_1, type) : k_isprop1(m, type, tt, d);
    }

    /**
     * The inline part of ISPROP: the PROPT flag: T, F, or {@code Op.MISS} when unknown.
     */
    static int ispropFast(Machine m, int type) {
        if (type < 0) {
            return Op.MISS; // a literal is no type: the complete path fails with E_TYPE_EXPECTED
        }
        int p = (m.vh[type << Layout.REC_SHIFT] >>> (Layout.FLAG_SHIFT + Layout.PROPT_SHIFT)) & 3;
        return p == Layout.TRI_UNKNOWN ? Op.MISS : p == Layout.TRI_YES ? Layout.T : Layout.F;
    }

    /** ISPROP[1](T): r = VTYPE(T). */
    static int k_isprop1(Machine m, int type, int r, int d) {
        int l = ensort(m, r, d + 1); // call ENSORT => ISPROP[2]
        return l >= SIG ? m.sig1(l, K_ISPROP_2, type) : k_isprop2(m, type, l, d);
    }

    /** ISPROP[2](T): r = the sort level of T; {@code normalizes_to_zero}; PROPT memo (a flag of the record, I-FLAG). */
    static int k_isprop2(Machine m, int type, int r, int d) {
        boolean p = m.levels.isAlwaysZero(r);
        if (type >= Layout.FIRST_RECORD) {
            Values.setPropT(m, type, p ? Layout.TRI_YES : Layout.TRI_NO);
        }
        return p ? Layout.T : Layout.F;
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
            case Op.TWALK:
                return resumeTwalk(m, key, w, p, r);
            case Op.INFER2:
                return resumeInfer2(m, key, w, p, r);
            case Op.IPROJ:
                return resumeIproj(m, key, w, p, r);
            case Op.VTYPE:
            case Op.VTYPE2:
                return resumeVtype(m, key, w, p, r);
            case Op.SIGD:
                return Facts.resume(m, key, w, p, r);
            case Op.CHECK2:
                return resumeCheck2(m, key, w, p, r);
            default:
                break;
        }
        switch (key) {
            case Op.K_INFER:
                return infer(m, w[p], w[p + 1], w[p + 2], w[p + 3], w[p + 4], 0);
            case K_INFER_1:
                return k_infer1(m, w[p], w[p + 1], w[p + 2], r, 0);
            case Op.K_CHECK:
                return check(m, w[p], w[p + 1], w[p + 2], w[p + 3], w[p + 4], 0);
            case K_CHECK_1:
                return k_check1(m, w[p], r, 0);
            case K_CHECK_2:
                return k_check2(m, r, 0);
            case Op.K_ENSORT:
                return ensort(m, w[p], 0);
            case K_ENSORT_1:
                return k_ensort1(m, r, 0);
            case Op.K_ENPI:
                return enpi(m, w[p], 0);
            case K_ENPI_1:
                return k_enpi1(m, r, 0);
            case Op.K_ISPROP:
                return isprop(m, w[p], 0);
            case K_ISPROP_1:
                return k_isprop1(m, w[p], r, 0);
            case K_ISPROP_2:
                return k_isprop2(m, w[p], r, 0);
            default:
                return m.fault(MachineErrors.E_INTERNAL);
        }
    }

    private static int resumeCheck2(Machine m, int key, int[] w, int p, int r) {
        switch (key) {
            case K_CK_1:
                return k_ck1(m, w[p], w[p + 1], w[p + 2], w[p + 3], w[p + 4], w[p + 5], r, 0);
            case K_CK_2:
                return k_ck2(m, w[p], w[p + 1], w[p + 2], w[p + 3], w[p + 4], w[p + 5], r, 0);
            case K_CK_3:
                return k_ck3(m, w[p], w[p + 1], w[p + 2], w[p + 3], w[p + 4], w[p + 5], r, 0);
            case K_CK_4:
                return k_ck4(m, w[p], w[p + 1], w[p + 2], w[p + 3], w[p + 4], w[p + 5], r, 0);
            case K_CK_5:
                return k_ck5(m, w[p], w[p + 1], w[p + 2], w[p + 3], w[p + 4], w[p + 5], w[p + 6], r, 0);
            case K_CK_6:
                return k_ck6(m, w[p], w[p + 1], w[p + 2], w[p + 3], w[p + 4], w[p + 5], w[p + 6], r, 0);
            case K_CK_7:
                return k_ck7(m, w[p], w[p + 1], w[p + 2], w[p + 3], w[p + 4], w[p + 5], r, 0);
            case K_CK_8:
                return k_ck8(m, w[p], w[p + 1], w[p + 2], w[p + 3], r, 0);
            default:
                return m.fault(MachineErrors.E_INTERNAL);
        }
    }

    private static int resumeTwalk(Machine m, int key, int[] w, int p, int r) {
        switch (key) {
            case K_TW_1:
                return k_tw1(m, w[p], r, 0);
            case K_TW_2:
                return k_tw2(m, w[p], w[p + 1], r, 0);
            case K_TW_3:
                return k_tw3(m, w[p], w[p + 1], w[p + 2], r, 0);
            case K_TW_4:
                return k_tw4(m, w[p], w[p + 1], w[p + 2], w[p + 3], w[p + 4], r, 0);
            case K_TW_5:
                return k_tw5(m, w[p], w[p + 1], w[p + 2], w[p + 3], r, 0);
            case K_TW_6:
                return k_tw6(m, w[p], w[p + 1], w[p + 2], r, 0);
            case K_TW_7:
                return k_tw7(m, w[p], w[p + 1], w[p + 2], r, 0);
            case K_TW_8:
                return k_tw8(m, w[p], w[p + 1], w[p + 2], w[p + 3], r, 0);
            case K_TW_9:
                return k_tw9(m, w[p], w[p + 1], w[p + 2], r, 0);
            default:
                return m.fault(MachineErrors.E_INTERNAL);
        }
    }

    private static int resumeInfer2(Machine m, int key, int[] w, int p, int r) {
        switch (key) {
            case K_PI_1:
                return k_pi1(m, w[p], w[p + 1], w[p + 2], w[p + 3], w[p + 4], w[p + 5], w[p + 6], r, 0);
            case K_PI_2:
                return k_pi2(m, w[p], w[p + 1], w[p + 2], w[p + 3], w[p + 4], w[p + 5], w[p + 6], r, 0);
            case K_PI_3:
                return k_pi3(m, w[p], w[p + 1], w[p + 2], w[p + 3], w[p + 4], w[p + 5], w[p + 6], r, 0);
            case K_PI_4:
                return k_pi4(m, w[p], w[p + 1], r, 0);
            case K_PI_5:
                return k_pi5(m, w[p], w[p + 1], r, 0);
            case K_LAM_1:
                return k_lam1(m, w[p], w[p + 1], w[p + 2], w[p + 3], w[p + 4], w[p + 5], w[p + 6], r, 0);
            case K_LAM_2:
                return k_lam2(m, w[p], w[p + 1], w[p + 2], w[p + 3], w[p + 4], w[p + 5], w[p + 6], r, 0);
            case K_LAM_3:
                return k_lam3(m, w[p], w[p + 1], w[p + 2], w[p + 3], w[p + 4], w[p + 5], w[p + 6], r, 0);
            case K_LAM_4:
                return k_lam4(m, w[p], w[p + 1], w[p + 2], r, 0);
            case K_LET_1:
                return k_let1(m, w[p], w[p + 1], w[p + 2], w[p + 3], w[p + 4], w[p + 5], r, 0);
            case K_LET_2:
                return k_let2(m, w[p], w[p + 1], w[p + 2], w[p + 3], w[p + 4], w[p + 5], r, 0);
            case K_LET_3:
                return k_let3(m, w[p], w[p + 1], w[p + 2], w[p + 3], w[p + 4], w[p + 5], r, 0);
            case K_LET_4:
                return k_let4(m, w[p], w[p + 1], w[p + 2], w[p + 3], w[p + 4], w[p + 5], w[p + 6], r, 0);
            case K_LET_5:
                return k_let5(m, w[p], w[p + 1], w[p + 2], w[p + 3], w[p + 4], w[p + 5], w[p + 6], r, 0);
            case K_LET_6:
                return k_let6(m, w[p], r, 0);
            default:
                return m.fault(MachineErrors.E_INTERNAL);
        }
    }

    private static int resumeIproj(Machine m, int key, int[] w, int p, int r) {
        switch (key) {
            case K_IP_1:
                return k_ip1(m, w[p], w[p + 1], w[p + 2], w[p + 3], r, 0);
            case K_IP_2:
                return k_ip2(m, w[p], w[p + 1], w[p + 2], w[p + 3], r, 0);
            case K_IP_3:
                return k_ip3(m, w[p], r, 0);
            case K_IP_4:
                return k_ip4(m, w[p], w[p + 1], r, 0);
            case K_IP_5:
                return k_ip5(m, w[p], w[p + 1], r, 0);
            case K_IP_6:
                return k_ip6(m, w[p], w[p + 1], r, 0);
            case K_IP_7:
                return k_ip7(m, w[p], w[p + 1], r, 0);
            case K_IP_8:
                return k_ip8(m, w[p], w[p + 1], w[p + 2], r, 0);
            case K_IP_9:
                return k_ip9(m, w[p], w[p + 1], w[p + 2], r, 0);
            case K_IP_10:
                return k_ip10(m, w[p], w[p + 1], w[p + 2], r, 0);
            case K_IP_11:
                return k_ip11(m, w[p], w[p + 1], r, 0);
            case K_IP_12:
                return k_ip12(m, w[p], r, 0);
            case K_IP_13:
                return k_ip13(m, w[p], r, 0);
            case K_IP_14:
                return k_ip14(m, w[p], w[p + 1], r, 0);
            default:
                return m.fault(MachineErrors.E_INTERNAL);
        }
    }

    private static int resumeVtype(Machine m, int key, int[] w, int p, int r) {
        switch (key) {
            case Op.K_VTYPE:
                return vtype(m, w[p], 0);
            case K_VT_1:
                return k_vt1(m, r, 0);
            case K_VT_2:
                return k_vt2(m, w[p], r, 0);
            case K_VT_3:
                return k_vt3(m, w[p], r, 0);
            case K_VT_4:
                return k_vt4(m, w[p], w[p + 1], r, 0);
            case K_VT_5:
                return k_vt5(m, w[p], w[p + 1], w[p + 2], r, 0);
            case K_VT_6:
                return k_vt6(m, w[p], r, 0);
            case K_VT_7:
                return k_vt7(m, w[p], r, 0);
            case K_VP_1:
                return k_vp1(m, w[p], r, 0);
            case K_VP_2:
                return k_vp2(m, w[p], w[p + 1], r, 0);
            case K_VP_3:
                return k_vp3(m, w[p], w[p + 1], r, 0);
            case K_VP_4:
                return k_vp4(m, w[p], w[p + 1], r, 0);
            case K_VP_5:
                return k_vp5(m, w[p], w[p + 1], r, 0);
            case K_VP_6:
                return k_vp6(m, w[p], w[p + 1], r, 0);
            case K_EVD_1:
                return k_evd1(m, r, 0);
            default:
                return m.fault(MachineErrors.E_INTERNAL);
        }
    }
}
