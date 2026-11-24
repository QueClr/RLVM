package rlvm.machine;

import static rlvm.machine.Op.SIG;

import rlvm.api.MachineErrors;
import rlvm.store.DeclKind;
import rlvm.store.DeclTable;
import rlvm.store.Kind;
import rlvm.store.Terms;

/**
 * Static facts (ISA 3.5): accessors, derivation ({@code SIGD}), shapes. Staging step 2; OFF in v0
 * ({@code Op.SW_FACTS}). Owner: M5.
 *
 * <h2>The record</h2>
 * One immutable record per constant in the engine's {@link FactStore} (words f0 .. of ISA 3.5, offsets in
 * {@link Layout}). It holds numbers, masks, and levels as structural encodings with name ids only, so it
 * obeys the publication rule (ISA 3.3). Level refs are always {@code POOL + offset} (never
 * {@code BASE + id}: NOTES-M5 F2); a session reads them through {@link Levels#importLevel}.
 *
 * <h2>Reading</h2>
 * {@link #facts} is the pure query (the published index or 0); every accessor takes that index, loaded
 * once, so a reader can never combine words of two records. Index 0 and flag F_NONE both mean "no facts":
 * no position is skipped, PROOF stays unknown, every spine counts as shorter than lamArity. In strict
 * mode and while {@code SW_FACTS} is off every query answers "no facts" (ISA 14.2).
 *
 * <h2>Deriving</h2>
 * {@link #sigd} is a judgment: the consumer that wants the record of a constant calls
 * {@code Facts.sigd(m, row, d + 1)} like any sub-judgment (ISA 13.4 "triggers SIGD"); it returns the index of
 * the record that is published now (this worker's or a faster worker's), 0 if the table is full.
 */
final class Facts {
    private Facts() {}

    static final int K_SIGD_1 = Op.K_SIGD | 1;
    static final int K_SIGD_2 = Op.K_SIGD | 2;
    static final int K_SIGD_3 = Op.K_SIGD | 3;
    static final int K_SIGD_4 = Op.K_SIGD | 4;

    /** The record under construction, in scratch at {@code X[xb ..]}: counts, masks, and SESSION level ids. */
    private static final int B_ARITY = 0;
    private static final int B_LAM = 1;
    private static final int B_READS = 2;
    private static final int B_UNREAD = 4;
    private static final int B_MANIFEST = 6;
    private static final int B_RLVL = 8;
    private static final int B_SHAPE = 9;
    private static final int B_SHAPE_OP = 10;
    private static final int B_SLVL = 11;
    private static final int B_DEP = B_SLVL + Layout.FACT_POSITIONS;
    /** The tick count at which the derivation started (two words): its cost is visible in {@code Infer.Ext.sigdTicks}. */
    private static final int B_T0 = B_DEP + 2 * Layout.FACT_POSITIONS;
    private static final int B_WORDS = B_T0 + 2;

    /** Indices of {@code Infer.Ext.factUse} (statistics of the consumers' queries, counted while {@code m.stats}). */
    static final int USE_PROPMASK = 0;
    static final int USE_PROPMASK_SET = 1;
    static final int USE_UNREAD = 2;
    static final int USE_UNREAD_SET = 3;
    static final int USE_PROOF = 4;
    static final int USE_PROOF_YES = 5;
    static final int USE_PROOF_NO = 6;
    static final int USE_COUNT = 7;
    /** Arity and lamArity are 16-bit fields of f1; a longer telescope gets no facts. */
    private static final int COUNT_MAX = 0xFFFF;

    // ---------------------------------------------------------------------------------------------------
    // Queries
    // ---------------------------------------------------------------------------------------------------

    /** True if fact records are read and derived in this session (step 2 on, not strict, an engine attached). */
    static boolean enabled(Machine m) {
        return (m.sw & Op.SW_FACTS) != 0 && !m.strict && m.engine != null;
    }

    /**
     * The fact record of a constant (ISA 3.5): its index in the engine's {@link FactStore}, loaded once (acquire); 0 =
     * no facts (also in strict mode and while {@code Op.SW_FACTS} is off). Derivation is requested through SIGD.
     */
    static int facts(Machine m, int row) {
        return enabled(m) ? m.engine.facts.indexOf(row) : 0;
    }

    /** Word {@code f} of record {@code index} (index != 0). */
    static int word(Machine m, int index, int f) {
        FactStore fs = m.engine.facts;
        return fs.chunkOf(index)[fs.offsetOf(index) + f];
    }

    /** A 64-bit mask stored in words {@code f}, {@code f + 1}. */
    static long mask(Machine m, int index, int f) {
        FactStore fs = m.engine.facts;
        int[] c = fs.chunkOf(index);
        int o = fs.offsetOf(index) + f;
        return (c[o] & 0xFFFFFFFFL) | ((long) c[o + 1] << 32);
    }

    /** True if the record carries facts (not F_NONE). */
    static boolean usable(Machine m, int index) {
        return index != 0 && (word(m, index, Layout.F_FLAGS) & Layout.F_NONE) == 0;
    }

    /** Syntactic Pis of the type. */
    static int arity(Machine m, int index) {
        return word(m, index, Layout.F_ARITY) >>> 16;
    }

    /** Leading lambdas of the value (0 if none, or if the value is kept as a proof block). */
    static int lamArity(Machine m, int index) {
        return word(m, index, Layout.F_ARITY) & 0xFFFF;
    }

    /** Bit i: binder i of the type occurs in a later domain or in the result (a superset). */
    static long reads(Machine m, int index) {
        return mask(m, index, Layout.F_READS);
    }

    /** Bit i: {@code slvl[i]} is recorded (the sort of domain i was manifest). */
    static long manifest(Machine m, int index) {
        return mask(m, index, Layout.F_MANIFEST);
    }

    /** {@code dep[i]}: the binders the domain of position i reads (i < min(arity, 64)); a superset. */
    static long dep(Machine m, int index, int i) {
        int n = Math.min(arity(m, index), Layout.FACT_POSITIONS);
        return mask(m, index, Layout.F_SLVL + n + 2 * i);
    }

    /** Shape of the value ({@code Layout.SHAPE_*}) and its operand. */
    static int shape(Machine m, int index) {
        return (word(m, index, Layout.F_FLAGS) >>> Layout.F_SHAPE_SHIFT) & Layout.F_SHAPE_MASK;
    }

    static int shapeOperand(Machine m, int index) {
        return word(m, index, Layout.F_SHAPE_OPERAND);
    }

    /** The session level of {@code slvl[i]} over the constant's own parameters; 0 = unknown; or a signal. */
    static int slvl(Machine m, int index, int i) {
        return Levels.importLevel(m, index, Layout.F_SLVL + i);
    }

    /** The session level of {@code rlvl}; 0 = unknown; or a signal. */
    static int rlvl(Machine m, int index) {
        return Levels.importLevel(m, index, Layout.F_RLVL);
    }

    /**
     * Prop mask of a CONST record at its levels (ISA 3.5, cache SIGT): bit i = argument i is a proof (manifest sort,
     * always zero at these levels). 0 without facts: every position is compared.
     */
    static long propMask(Machine m, int constRef) {
        if (!enabled(m) || constRef < Layout.FIRST_RECORD) {
            return 0L;
        }
        int[] c = m.sigt;
        int cell = (Hash.cell(constRef, 0) & ((1 << Layout.SIGT_LOG2) - 1)) << Layout.CELL_SHIFT;
        long r = c[cell + Layout.SIGT_CONST] == constRef && c[cell + Layout.SIGT_EPOCH] == m.epoch
                ? (c[cell + Layout.SIGT_LO] & 0xFFFFFFFFL) | ((long) c[cell + Layout.SIGT_HI] << 32) : propMaskSlow(m, constRef, cell);
        if (m.stats) {
            used(m, USE_PROPMASK, r != 0);
        }
        return r;
    }

    /** Statistics: one query of kind {@code use}; {@code set}: it was answered from a record (a mask bit, a definite PROOF). */
    private static void used(Machine m, int use, boolean set) {
        long[] u = m.x5.factUse;
        u[use]++;
        if (set) {
            u[use + 1]++;
        }
    }

    private static long propMaskSlow(Machine m, int constRef, int cell) {
        int o = constRef << Layout.REC_SHIFT;
        if ((m.vh[o] >>> Layout.KIND_SHIFT) != Layout.CONST) {
            return 0L;
        }
        int index = m.engine.facts.indexOf(m.vh[o + Layout.A]);
        if (index == 0) {
            return 0L; // not derived yet: not cached, a later query may find the record
        }
        long result = 0L;
        if (usable(m, index)) {
            int ls = m.vh[o + Layout.C]; // mkSubst(lparams(row), levels) (ISA 2.7)
            long manifest = manifest(m, index);
            for (long rest = manifest; rest != 0; rest &= rest - 1) {
                int i = Long.numberOfTrailingZeros(rest);
                int l = Levels.importLevel(m, index, Layout.F_SLVL + i);
                if (l >= SIG) {
                    return 0L;
                }
                if (l != 0 && ls != 0) {
                    l = Levels.lsub(m, l, ls);
                    if (l >= SIG) {
                        return 0L;
                    }
                }
                if (l != 0 && m.levels.isAlwaysZero(l)) {
                    result |= 1L << i;
                }
            }
        }
        int[] c = m.sigt;
        c[cell + Layout.SIGT_CONST] = constRef;
        c[cell + Layout.SIGT_EPOCH] = m.epoch;
        c[cell + Layout.SIGT_LO] = (int) result;
        c[cell + Layout.SIGT_HI] = (int) (result >>> 32);
        return result;
    }

    /**
     * The {@code unread} mask of the value of a constant, 0 unless the spine covers lamArity (ISA 3.5, S1 / S4).
     */
    static long unreadMask(Machine m, int row, int spineLen) {
        int index = facts(m, row);
        long r = !usable(m, index) || spineLen < lamArity(m, index) ? 0L : mask(m, index, Layout.F_UNREAD);
        if (m.stats && index != 0) {
            used(m, USE_UNREAD, r != 0);
        }
        return r;
    }

    /**
     * Static PROOF of a saturated constant application (ISA 3.5, B7): {@code Layout.TRI_YES}, {@code TRI_NO} or
     * {@code TRI_UNKNOWN} (then the dynamic ISPROP of CONV c4 runs).
     *
     * <p>Sorts, Pis and literals are never proofs (TRI_NO). A constant applied to exactly {@code arity} arguments
     * has the type {@code R[args]} whose sort is {@code rlvl[ls]} when that sort was manifest: always zero ->
     * TRI_YES, never zero -> TRI_NO. Everything else, and every value without facts: TRI_UNKNOWN.
     */
    static int staticProof(Machine m, int v) {
        if (!enabled(m)) {
            return Layout.TRI_UNKNOWN; // v0 and strict mode: PROOF comes only from the dynamic ISPROP (ISA 14.2)
        }
        if (v < 0) {
            return Layout.TRI_NO;
        }
        int o = v << Layout.REC_SHIFT;
        int w0 = m.vh[o];
        switch (w0 >>> Layout.KIND_SHIFT) {
            case Layout.SORT:
            case Layout.PI:
            case Layout.PII:
            case Layout.NBIG:
            case Layout.NSUC:
            case Layout.STR:
                return Layout.TRI_NO;
            case Layout.CONST:
                return proofUsed(m, staticProofSlow(m, v, 0));
            case Layout.NEU: {
                int h = m.vh[o + Layout.A];
                if (h < 0 || (m.vh[h << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT) != Layout.CONST) {
                    return Layout.TRI_UNKNOWN;
                }
                return proofUsed(m, staticProofSlow(m, h, w0 & Layout.AUX_MASK));
            }
            default:
                return Layout.TRI_UNKNOWN;
        }
    }

    /** Statistics of {@link #staticProof} on a constant head: queries, and the two definite answers. */
    private static int proofUsed(Machine m, int tri) {
        if (m.stats) {
            long[] u = m.x5.factUse;
            u[USE_PROOF]++;
            if (tri == Layout.TRI_YES) {
                u[USE_PROOF_YES]++;
            } else if (tri == Layout.TRI_NO) {
                u[USE_PROOF_NO]++;
            }
        }
        return tri;
    }

    private static int staticProofSlow(Machine m, int head, int len) {
        int ho = head << Layout.REC_SHIFT;
        int index = m.engine.facts.indexOf(m.vh[ho + Layout.A]);
        if (!usable(m, index) || arity(m, index) != len) {
            return Layout.TRI_UNKNOWN;
        }
        int l = Levels.importLevel(m, index, Layout.F_RLVL);
        if (l >= SIG || l == 0) {
            return Layout.TRI_UNKNOWN;
        }
        int ls = m.vh[ho + Layout.C];
        if (ls != 0) {
            l = Levels.lsub(m, l, ls);
            if (l >= SIG) {
                return Layout.TRI_UNKNOWN;
            }
        }
        if (m.levels.isAlwaysZero(l)) {
            return Layout.TRI_YES;
        }
        return m.levels.isNeverZero(l) ? Layout.TRI_NO : Layout.TRI_UNKNOWN;
    }

    // ---------------------------------------------------------------------------------------------------
    // SIGD: derivation
    // ---------------------------------------------------------------------------------------------------

    /**
     * SIGD(row) (ISA 3.5): derives and publishes the fact record of a constant under a TRY of {@code Op.B_SIGD} ticks;
     * manifest sorts only (RECONCILIATION section 2 "Static Prop facts"); a failed derivation publishes F_NONE.
     * Staging step 2. Entry frame: SIGD[0](row).
     *
     * <p>The constant must be visible to the declaration being checked (so it is admitted and its type is
     * well typed): the type part runs INFER in ONLY mode over the leading Pis of the type under canonical
     * variables and records the level of a domain's (and of the result's) type only when that type is a SORT
     * record as returned, before any weak-head normalisation.
     * @return the record index (0 = none), or a signal
     */
    static int sigd(Machine m, int row, int d) {
        if (!enabled(m)) {
            return 0;
        }
        int index = m.engine.facts.indexOf(row);
        if (index != 0) {
            return index; // 1. inline: the record exists
        }
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig;
        }
        if (m.stats) {
            m.call(Op.SIGD);
        }
        if (d > m.hostDepth) {
            return m.spill1(Op.K_SIGD, row);
        }
        int xb = m.xp;
        if (!m.ensureScratch(B_WORDS)) {
            return m.fault(MachineErrors.E_MEM);
        }
        m.xp = xb + B_WORDS;
        boolean fits = syntaxSlow(m, row, xb);
        long t0 = m.ticks() - 1; // the derivation's own tick included
        m.X[xb + B_T0] = (int) t0;
        m.X[xb + B_T0 + 1] = (int) (t0 >>> 32);
        if (!fits) {
            return k_sigd1(m, row, xb, Layout.U, d); // a telescope beyond the 16-bit counts: F_NONE
        }
        int r = trySigType(m, row, xb, d + 1); // TRY B_SIGD; call the type part => SIGD[1]
        return r >= SIG ? m.sig2(r, K_SIGD_1, row, xb) : k_sigd1(m, row, xb, r, d);
    }

    /** SIGD[1](row, xb): r = T (the type part is in the buffer) or U (budget, or a kernel error inside): publish. */
    static int k_sigd1(Machine m, int row, int xb, int r, int d) {
        int index = publishSlow(m, row, xb, r == Layout.T);
        Infer.Ext ext = m.x5;
        ext.sigdTicks += m.ticks() - ((m.X[xb + B_T0] & 0xFFFFFFFFL) | ((long) m.X[xb + B_T0 + 1] << 32));
        m.xp = xb;
        return index;
    }

    /** The speculative segment of SIGD (ISA 1.4 "TRY b; call J"; template in {@link Frames}): T or U. */
    private static int trySigType(Machine m, int row, int xb, int d) {
        int ssp = m.ssp;
        int xp = m.xp;
        int ep = m.ep;
        int rp = m.rp;
        int er = m.ER;
        long saved = m.tryEnter(Op.B_SIGD);
        int r = sigLoop(m, xb, m.decls.type(row), 0, 0, 0, ep, ssp, d + 1);
        return r == Op.SUSP ? m.saveTry(saved, Op.B_SIGD, ssp, xp, ep, rp, er)
                : Frames.k_try(m, saved, Op.B_SIGD, ssp, xp, ep, rp, er, r);
    }

    /**
     * The type part (ISA 3.5): for each leading Pi {@code i}: {@code Ti = INFER(A_i, env, ONLY)}; a SORT record:
     * {@code slvl[i]} = its level, manifest bit i (no ENSORT, no WHNF); {@code x = OPEN(EVAL(A_i, env)); env += x}.
     * Then {@code TR = INFER(R, env, ONLY)}; a SORT record: {@code rlvl}. CLOSE all. Positions >= 64 have no facts:
     * their sorts are not inferred.
     */
    private static int sigLoop(Machine m, int xb, int cur, int eb, int en, int i, int ep0, int ssp0, int d) {
        Terms terms = m.terms;
        while (terms.kind(cur) == Kind.PI) {
            int nb = sigIter0(m, xb, cur, eb, en, i, ep0, ssp0, d);
            if (nb >= SIG) {
                return nb;
            }
            eb = nb;
            en++;
            i++;
            cur = terms.b(cur);
        }
        int tr = Infer.infer(m, cur, eb, en, 0, Op.ONLY, d + 1); // call INFER(R, ONLY) => SIGD[4]
        return tr >= SIG ? m.sig3(tr, K_SIGD_4, xb, ep0, ssp0) : k_sigd4(m, xb, ep0, ssp0, tr, d);
    }

    private static int sigIter0(Machine m, int xb, int cur, int eb, int en, int i, int ep0, int ssp0, int d) {
        if (i >= Layout.FACT_POSITIONS) {
            return sigIter1(m, xb, cur, eb, en, i, ep0, ssp0, Layout.NONE, d);
        }
        int ti = Infer.infer(m, m.terms.a(cur), eb, en, 0, Op.ONLY, d + 1); // call INFER(A_i, ONLY) => SIGD[2]
        return ti >= SIG ? m.sig7(ti, K_SIGD_2, xb, cur, eb, en, i, ep0, ssp0) : sigIter1(m, xb, cur, eb, en, i, ep0, ssp0, ti, d);
    }

    private static int sigIter1(Machine m, int xb, int cur, int eb, int en, int i, int ep0, int ssp0, int ti, int d) {
        if (ti >= Layout.FIRST_RECORD && (m.vh[ti << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT) == Layout.SORT) {
            int[] x = m.X; // a manifest sort: the type as returned is a SORT record
            x[xb + B_SLVL + i] = m.vh[(ti << Layout.REC_SHIFT) + Layout.A];
            x[xb + B_MANIFEST + (i >>> 5)] |= 1 << (i & 31);
        }
        int dom = Infer.evalDom(m, m.terms.a(cur), eb, en, 0, d); // call EVAL(A_i), forced => SIGD[3]
        return dom >= SIG ? m.sig7(dom, K_SIGD_3, xb, cur, eb, en, i, ep0, ssp0) : Infer.openPush(m, eb, en, dom);
    }

    /** SIGD[2](xb, cur, eb, en, i, ep0, ssp0): r = the type of domain i. */
    static int k_sigd2(Machine m, int xb, int cur, int eb, int en, int i, int ep0, int ssp0, int r, int d) {
        int nb = sigIter1(m, xb, cur, eb, en, i, ep0, ssp0, r, d);
        return nb >= SIG ? nb : sigLoop(m, xb, m.terms.b(cur), nb, en + 1, i + 1, ep0, ssp0, d);
    }

    /** SIGD[3](..): r = the value of domain i. */
    static int k_sigd3(Machine m, int xb, int cur, int eb, int en, int i, int ep0, int ssp0, int r, int d) {
        int nb = Infer.openPush(m, eb, en, r);
        return nb >= SIG ? nb : sigLoop(m, xb, m.terms.b(cur), nb, en + 1, i + 1, ep0, ssp0, d);
    }

    /** SIGD[4](xb, ep0, ssp0): r = the type of the result; a SORT record: {@code rlvl}. CLOSE all. */
    static int k_sigd4(Machine m, int xb, int ep0, int ssp0, int r, int d) {
        if (r >= Layout.FIRST_RECORD && (m.vh[r << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT) == Layout.SORT) {
            m.X[xb + B_RLVL] = m.vh[(r << Layout.REC_SHIFT) + Layout.A];
        }
        Scope.closeTo(m, ssp0);
        m.ep = ep0;
        return Layout.T;
    }

    /**
     * The syntactic part of the record (ISA 3.5 "value part", and the masks of the type part), into the buffer:
     * arity, reads, dep[i] from the node masks of the domains and of the result; lamArity, unread and the shape
     * from the value term (export terms only: a value kept as a proof block lives in an overlay and has no
     * value facts, 3.3).
     * @return false if a count does not fit its 16-bit field
     */
    private static boolean syntaxSlow(Machine m, int row, int xb) {
        Terms terms = m.terms;
        DeclTable decls = m.decls;
        int[] x = m.X;
        java.util.Arrays.fill(x, xb, xb + B_WORDS, 0);
        int cur = decls.type(row);
        int k = 0;
        long reads = 0L;
        while (terms.kind(cur) == Kind.PI) {
            long dep = binders(terms.fvMask(terms.a(cur)), k);
            reads |= dep;
            if (k < Layout.FACT_POSITIONS) {
                x[xb + B_DEP + 2 * k] = (int) dep;
                x[xb + B_DEP + 2 * k + 1] = (int) (dep >>> 32);
            }
            cur = terms.b(cur);
            if (++k > COUNT_MAX) {
                return false;
            }
        }
        reads |= binders(terms.fvMask(cur), k);
        x[xb + B_ARITY] = k;
        x[xb + B_READS] = (int) reads;
        x[xb + B_READS + 1] = (int) (reads >>> 32);
        int kind = decls.kind(row);
        int v = kind == DeclKind.DEFN || kind == DeclKind.THM || kind == DeclKind.OPAQUE ? decls.value(row) : 0;
        if (v > 0) {
            int lam = 0;
            cur = v;
            while (terms.kind(cur) == Kind.LAM) {
                cur = terms.b(cur);
                if (++lam > COUNT_MAX) {
                    return false;
                }
            }
            // parameter i (counted from the outside) is de Bruijn index lam - 1 - i of the innermost body
            long read = binders(terms.fvMask(cur), lam);
            long unread = lam >= 64 ? ~read : ~read & ((1L << lam) - 1);
            x[xb + B_LAM] = lam;
            x[xb + B_UNREAD] = (int) unread;
            x[xb + B_UNREAD + 1] = (int) (unread >>> 32);
            if (terms.kind(cur) == Kind.BVAR && terms.a(cur) < lam) {
                x[xb + B_SHAPE] = Layout.SHAPE_PICK; // the body is parameter j: RET Aj (ISA 3.5 last row)
                x[xb + B_SHAPE_OP] = lam - 1 - terms.a(cur);
            }
        }
        return true;
    }

    /**
     * Which of the {@code k} enclosing binders (0 = outermost) a node with free-variable mask {@code mask} may
     * read, positions 0..63 only: de Bruijn index j is binder {@code k - 1 - j}; mask bit 63 stands for every
     * index >= 63. A superset, like the mask.
     */
    private static long binders(long mask, int k) {
        long r = 0L;
        for (long rest = mask & ~Layout.MASK_FAR; rest != 0; rest &= rest - 1) {
            int i = k - 1 - Long.numberOfTrailingZeros(rest);
            if (i >= 0 && i < 64) {
                r |= 1L << i;
            }
        }
        if (mask < 0 && k >= 64) {
            int top = k - 1 - Layout.NEAR_SLOTS; // binders 0 .. top are the far ones
            r |= top >= 63 ? -1L : (1L << (top + 1)) - 1;
        }
        return r;
    }

    /**
     * Assembles the record from the buffer (levels become structural encodings in the record's pool) and
     * publishes it with one release store. {@code ok} = the type part completed; else F_NONE.
     * @return the index readers see now
     */
    private static int publishSlow(Machine m, int row, int xb, boolean ok) {
        Infer.Ext ext = m.x5;
        if (ext.factBuf == null) {
            ext.factBuf = new int[FactStore.MAX_RECORD];
        }
        int[] rec = ext.factBuf;
        int[] x = m.X;
        int arity = x[xb + B_ARITY];
        int n = Math.min(arity, Layout.FACT_POSITIONS);
        java.util.Arrays.fill(rec, 0, Layout.F_SLVL + 3 * n, 0);
        rec[Layout.F_FLAGS] = (ok ? 0 : Layout.F_NONE) | x[xb + B_SHAPE] << Layout.F_SHAPE_SHIFT;
        rec[Layout.F_ARITY] = arity << 16 | x[xb + B_LAM];
        rec[Layout.F_READS] = x[xb + B_READS];
        rec[Layout.F_READS + 1] = x[xb + B_READS + 1];
        rec[Layout.F_UNREAD] = x[xb + B_UNREAD];
        rec[Layout.F_UNREAD + 1] = x[xb + B_UNREAD + 1];
        rec[Layout.F_SHAPE_OPERAND] = x[xb + B_SHAPE_OP];
        int p = Layout.F_SLVL + 3 * n;
        for (int i = 0; i < n; i++) {
            rec[Layout.F_SLVL + n + 2 * i] = m.X[xb + B_DEP + 2 * i];
            rec[Layout.F_SLVL + n + 2 * i + 1] = m.X[xb + B_DEP + 2 * i + 1];
            if (ok && (m.X[xb + B_MANIFEST + (i >>> 5)] & 1 << (i & 31)) != 0) {
                int w = Levels.encodeLevel(m, m.X[xb + B_SLVL + i], rec, p);
                if (w > 0) { // an encoding that does not fit leaves the level UNKNOWN
                    rec[Layout.F_SLVL + i] = Layout.LREF_POOL | p;
                    rec[Layout.F_MANIFEST + (i >>> 5)] |= 1 << (i & 31);
                    p += w;
                }
            }
        }
        int rl = m.X[xb + B_RLVL];
        if (ok && rl != 0) {
            int w = Levels.encodeLevel(m, rl, rec, p);
            if (w > 0) {
                rec[Layout.F_RLVL] = Layout.LREF_POOL | p;
                p += w;
            }
        }
        ext.factsDerived++;
        m.factsDerived++; // the engine's statistics (machine.factsDerived, machine.factsNone)
        if (!ok) {
            m.factsNone++;
        }
        return m.engine.facts.publish(row, rec, p);
    }

    /** DECL accepted {@code cid} (ISA 4.5.6): derive its record if none is published yet (an optimisation only). */
    static void onAccept(Machine m, int cid) {
        if (!enabled(m) || m.engine.facts.indexOf(cid) != 0) {
            return;
        }
        int r = m.drive(sigd(m, cid, 0));
        if (r >= SIG) {
            m.abandon(); // the declaration is accepted; its facts are simply not recorded by this worker
            m.err = MachineErrors.E_NONE;
        }
    }

    /** The driver's part for the frames of SIGD (called from {@code Infer.resume}). */
    static int resume(Machine m, int key, int[] w, int p, int r) {
        switch (key) {
            case Op.K_SIGD:
                return sigd(m, w[p], 0);
            case K_SIGD_1:
                return k_sigd1(m, w[p], w[p + 1], r, 0);
            case K_SIGD_2:
                return k_sigd2(m, w[p], w[p + 1], w[p + 2], w[p + 3], w[p + 4], w[p + 5], w[p + 6], r, 0);
            case K_SIGD_3:
                return k_sigd3(m, w[p], w[p + 1], w[p + 2], w[p + 3], w[p + 4], w[p + 5], w[p + 6], r, 0);
            case K_SIGD_4:
                return k_sigd4(m, w[p], w[p + 1], w[p + 2], r, 0);
            default:
                return m.fault(MachineErrors.E_INTERNAL);
        }
    }
}
