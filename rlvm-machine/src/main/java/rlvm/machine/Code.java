package rlvm.machine;

import static rlvm.machine.Op.SIG;

import rlvm.api.MachineErrors;
import rlvm.api.bind.LevelBinding;
import rlvm.store.DeclKind;
import rlvm.store.DeclTable;
import rlvm.store.Kind;
import rlvm.store.Node;
import rlvm.store.Specials;
import rlvm.store.Terms;

/**
 * Family CODE (ISA 3.3, 4.1, 8.2): the plain-Java stepper of K1 code objects. Owner: M7. Staging steps 3
 * and 4; OFF in v0 ({@code Op.SW_TELE}, {@code Op.SW_CODE}, {@code Op.SW_CASETREES}): nothing in this
 * class runs unless a caller of another package asks for a block behind one of those switches, and
 * {@link #blockFor} answers 0 ("walk the term") while they are off and in strict mode.
 *
 * <h2>Activations under convention H (*agentic-note*: rep-stepper-convention, H7; NOTES-M7 D1)</h2>
 * <pre>
 * RS[rb - 3]  capRun   the frame run of the closure whose body the block is (0 for BODY / RULE / TELE / RULES)
 * RS[rb - 2]  ls       the substitution id of the activation (ISA 2.7)
 * RS[rb - 1]  xp       scratch pointer when the activation started: everything above belongs to it
 * RS[rb + 0 .. arity)             the arguments A_i (TELE: the telescope variables g_i), copied in at entry
 * RS[rb + arity .. arity + nreg)  the registers r_k (TELE: 8 site registers first, {@code S_*})
 * </pre>
 * An operand of mode R or A is an index into this window, mode C a slot of capRun. The frame of a
 * suspended activation is {@code ACT [blk, pc, dst, 0, capRun, ls, rbase]} (Op.ACT_*): {@code pc} is the
 * instruction to continue with, {@code dst} the window index that receives the awaited result, or one
 * of the {@code DST_*} continuations. {@link #run} is entry and continuation step at once (the state is
 * the pc); a class-j instruction is rule H3.
 *
 * <h2>Entry points for the other packages (all return a result or a signal)</h2>
 * {@link #blockFor} (rent-to-own lookup), {@link #closureBlock} (the hint of a binder node),
 * {@link #callX} / {@link #callRun} / {@link #callClosure} (BODY, RULE and closure blocks on arguments in
 * scratch or in a spine run), {@link #rules} (the RULES judgment of ISA 3.6), {@link #tele} / {@link #teleValues} (TELE on an
 * inference site / on argument values).
 */
final class Code {
    private Code() {}

    // ---------------------------------------------------------------------------------------------------
    // Block layout (ISA 3.3): hdr0 = len:20 | nreg:8 | role:4; hdr1 = row; hdr2 = arity:12 | flags:12 | nentry:8;
    // hdr3 = facts index (sub-block: its binder term); entry table; code.
    // ---------------------------------------------------------------------------------------------------

    /** Block flag: the block of a binder node (inner lambda or Pi); hdr3 is that term (ISA 3.3 "Finding the block"). */
    static final int BLKF_SUB = 4;
    /** Block flags bits 4..7 hold the shape of a BODY ({@code Layout.SHAPE_*}; ISA 3.5 "LEAF, shape"). */
    static final int BLKF_SHAPE_SHIFT = 4;

    static int len(int hdr0) {
        return hdr0 >>> 12;
    }

    static int nreg(int hdr0) {
        return hdr0 >>> 4 & 255;
    }

    static int role(int hdr0) {
        return hdr0 & 15;
    }

    static int arity(int hdr2) {
        return hdr2 >>> 20;
    }

    static int flags(int hdr2) {
        return hdr2 >>> 8 & 0xFFF;
    }

    static int nentry(int hdr2) {
        return hdr2 & 255;
    }

    static int hdr0(int len, int nreg, int role) {
        return len << 12 | nreg << 4 | role;
    }

    static int hdr2(int arity, int flags, int nentry) {
        return arity << 20 | flags << 8 | nentry;
    }

    // ---- activation window and frame continuations ----

    static final int HDR = 3;
    static final int W_CAP = 3;
    static final int W_LS = 2;
    static final int W_XP = 1;

    /** Frame word {@code dst}: the awaited result is the result of the activation (TAILAPPLY, TRET). */
    static final int DST_RET = 0x100;
    /** The activation was spilled before it started (H4 step 3): tick and call event are due again. */
    static final int DST_ENTRY = 0x101;
    /** The awaited result is not stored. */
    static final int DST_NONE = 0x102;
    /** The awaited result is a CONV of TARG: anything but T is {@code fail(E_APP_MISMATCH)}. */
    static final int DST_REQ_APP = 0x103;
    /**
     * The awaited result is the value of the KVAL at {@code pc - 2} (the frame's pc is the instruction after it): it
     * goes to register {@code dst & 255} AND into the inline cache of that site, as on the path without a suspension
     * (finding M8-F1: without the fill a later execution of the site evaluated the closed term again where the
     * unsuspended run had its cache entry, so the tick count depended on the host depth bound once gated tabling
     * no longer tabled the term). {@link #DST_KVAL_LS}: the cache key is the activation's level substitution.
     */
    static final int DST_KVAL = 0x200;
    static final int DST_KVAL_LS = 0x400;

    // ---- instruction sub-fields (the x byte) ----

    /** KVAL x bit 0: ARGV mode (ISA 4.2 ARGV), else EVAL. Bit 1: the term mentions a level parameter. */
    static final int KV_ARGV = 1;
    static final int KV_LPARAM = 2;
    /** MKLAM / MKPI / MKSUSP / KCONST / KSORT x bit 0: the term (level, list) mentions a level parameter. */
    static final int X_LPARAM = 1;
    /** MKSUSP x bit 1: a let value (flag LET, ISA 2.8). */
    static final int X_LET = 2;
    /** TARG phases (x bits 0..1) and the READ flag (bit 4): binder i is read by a later domain or the result. */
    static final int PH_INFER = 0;
    static final int PH_CONV = 1;
    static final int PH_BIND = 2;
    static final int PH_EXPECT = 3;
    static final int PH_MASK = 3;
    static final int X_READ = 16;

    /** The kinds BKIND treats as "neutral" (ISA 3.4: an application of such a head is inert at run time). */
    static final int NEUTRAL_KINDS = 1 << Layout.FVAR | 1 << Layout.CONST | 1 << Layout.NEU | 1 << Layout.PROJ;

    // ---- TELE: the site registers, window indices relative to arity (ISA 4.5.4 "site") ----

    static final int S_XB = 0;
    static final int S_N = 1;
    static final int S_EB = 2;
    static final int S_EN = 3;
    static final int S_LS = 4;
    static final int S_MODE = 5;
    static final int S_EXPECT = 6;
    /** The inferred type of the argument between TARG phases INFER and CONV. */
    static final int S_T = 7;
    static final int TELE_SITE = 8;
    /** Site mode beside {@code Op.ONLY} / {@code Op.CHECKED}: the arguments are values (VTYPE of a NEU). */
    static final int MODE_VALUES = 2;

    private static final int PAY = Op.OPND_PAYLOAD_MASK;
    private static final int K_ACT = Op.K_ACT;
    private static final int IC_MASK = (1 << Layout.IC_LOG2) - 1;

    /**
     * Session state private to this package (caches, counters, scratch of its own): add fields here
     * instead of asking for fields in {@link Machine}. One instance per machine, field {@code m.x7}.
     */
    static final class Ext {
        /** The elaborator's working buffers (not part of the footprint: working storage of fixed use). */
        Elab.State elab;
        /** Statistics (only meaningful with {@code m.stats}): activations entered, KVAL hits and misses. */
        long entered;
        long icHits;
        long icMisses;
        /** MATCH instructions of TELE blocks that answered T (the domain was not built) and "unknown" (B5). */
        long matchT;
        long matchF;
        /** TARG.CONV that found the argument's type identical to the domain, and that had to call CONV. */
        long targSame;
        long targConv;
        /** Elaborations by role ({@code Op.ROLE_*}) made by this session, and those that stayed generic. */
        final long[] elaborated = new long[Op.ROLE_COUNT];
        final long[] stayedGeneric = new long[Op.ROLE_COUNT];
        /**
         * Block id -> address ({@code CodeStore.address}) of the blocks this session has entered: a block never moves
         * once published, so the session keeps what the directory answered and locates a block with one plain load
         * instead of two acquire loads per lookup. Survives a reset (a function of the engine's code store);
         * working storage, at most 4 bytes per block of the engine.
         */
        int[] addr = new int[1024];
        /** The code store {@link #addr} was filled from (a machine belongs to one engine; checked at every reset). */
        CodeStore addrOf;
        /** The upper tier of this machine (class {@link Tier}; ISA 8.4), or null. Asked for once, at the first declaration. */
        Tier tier;
        boolean tierAsked;
        /** The heat accounting of the engine and this session's counters (null: off; ISA 8.3). */
        Tier.Heat heatOf;
        Tier.Counters heat;
        /** True if an activation has to go through {@code Tier.exec}: the machine has a tier or counts heat. */
        boolean hooked;
        /** Sampling counter of the timed entries; number of timed activations on the host stack; the time of the blocks nested in the current one. */
        int sample;
        int timing;
        long childNanos;
        /** {@link #resumeOne}: the result or signal of an activation that ended. */
        int tierResult;

        Elab.State elab() {
            Elab.State s = elab;
            if (s == null) {
                elab = s = new Elab.State();
            }
            return s;
        }

        /** Session reset (ISA 1.6): forget everything, in O(used). The IC table is invalidated by the epoch. */
        void reset(Machine m) {
            CodeStore cs = m.engine == null ? null : m.engine.code;
            if (cs != addrOf) {
                addrOf = cs;
                java.util.Arrays.fill(addr, 0);
            }
        }

        /** Start of a declaration: the machine's tier and the engine's heat accounting (class {@link Tier}). */
        void beginDecl(Machine m) {
            Tier.beginDecl(m, this);
        }

        /** Bytes in use of this package's own session tables (part of the footprint, ISA 1.6). */
        long bytes() {
            return 0;
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // Finding blocks
    // ---------------------------------------------------------------------------------------------------

    /** The staging switch that guards a role (ISA section 11). */
    static int switchOf(int role) {
        switch (role) {
            case Op.ROLE_TELE:
            case Op.ROLE_FIELDS:
                return Op.SW_TELE;
            case Op.ROLE_CONG:
                return Op.SW_FACTS;
            case Op.ROLE_RULES:
                return Op.SW_CASETREES;
            default:
                return Op.SW_CODE;
        }
    }

    /**
     * The block of {@code (row, role)} for this session, rent-to-own (ISA 3.1): the published block if
     * there is one; otherwise the request is counted and the role is elaborated at its
     * {@code Op.ELAB_AT}-th request by any session. 0 = the role is walked by the generic tier (always
     * while the role's staging switch is off and in strict mode, ISA 14.2).
     *
     * @return the block id, 0, or FATAL ({@code E_AUDIT}: debug mode pubcheck failed)
     */
    static int blockFor(Machine m, int row, int role) {
        if (m.engine == null || m.strict || !m.on(switchOf(role))) {
            return 0;
        }
        CodeStore cs = m.engine.code;
        int b = cs.blockOf(row, role);
        if (b != 0) {
            return b;
        }
        return cs.request(row, role) ? Elab.elaborate(m, row, role) : 0;
    }

    /**
     * The block of a closure's binder term (ISA 3.3 "Finding the block of a closure", 2.6 CODE): the hint
     * in the reserved c word of the export LAM / PI node, validated against the directory. 0 = walk the
     * term. The caller keeps the answer in the closure's CODE memo ({@code Layout.NOCODE} for 0).
     */
    static int closureBlock(Machine m, int binderTerm) {
        if (m.engine == null || m.strict || !m.on(Op.SW_CODE) || binderTerm <= 0 || binderTerm >= Node.GEN_BASE) {
            return 0;
        }
        int h = m.terms.c(binderTerm);
        return h == 0 ? 0 : m.engine.code.hint(binderTerm, h);
    }

    /**
     * The address of block {@code blk} ({@code CodeStore.address}: chunk and offset; 0 = no such block), through the
     * session's cache: one plain load for a block this session has located before.
     */
    static int addressOf(Machine m, int blk) {
        int[] t = m.x7.addr;
        if (blk >= 0 && blk < t.length) {
            int a = t[blk];
            if (a != 0) {
                return a;
            }
        }
        return addressSlow(m, blk);
    }

    private static int addressSlow(Machine m, int blk) {
        Ext x = m.x7;
        if (x.addrOf != m.engine.code) {
            x.addrOf = m.engine.code;
            java.util.Arrays.fill(x.addr, 0);
        }
        int a = m.engine.code.address(blk);
        if (a != 0) {
            if (blk >= x.addr.length) {
                x.addr = java.util.Arrays.copyOf(x.addr, Math.max(x.addr.length << 1, blk + 1));
            }
            x.addr[blk] = a;
        }
        return a;
    }

    /**
     * True if entry {@code e} of block {@code blk} has code (role RULE: rule e was elaborated; an entry word
     * of 0 means that rule is walked by the generic tier).
     */
    static boolean hasEntry(Machine m, int blk, int e) {
        int a = addressOf(m, blk);
        if (a == 0) {
            return false;
        }
        int[] c = m.engine.code.chunkAt(a);
        int o = CodeStore.offsetAt(a);
        return e >= 0 && e < nentry(c[o + Op.BLK_HDR2]) && c[o + Op.BLK_ENTRIES + e] != 0;
    }

    /**
     * The entry of a closure's block for {@code n} arguments (ISA 4.3.1 APPLY "CODE(f) ? call its block at entry
     * j"): {@code j = min(n, arity)}, the number of binders the block takes from them, or 0 if that entry has no
     * code. The arity of a sub-block is at most the number of leading binders of its term (its first 16 lambdas,
     * or the one Pi), so the caller need not count them in the term.
     */
    static int entryFor(Machine m, int blk, int n) {
        int a = addressOf(m, blk);
        if (a == 0 || n < 1) {
            return 0;
        }
        int[] c = m.engine.code.chunkAt(a);
        int o = CodeStore.offsetAt(a);
        int h2 = c[o + Op.BLK_HDR2];
        int j = Math.min(n, arity(h2));
        return j <= nentry(h2) && c[o + Op.BLK_ENTRIES + j - 1] != 0 ? j : 0;
    }

    /** Number of arguments a block takes (the leading binders of its source term). */
    static int arityOf(Machine m, int blk) {
        int a = addressOf(m, blk);
        return arity(m.engine.code.chunkAt(a)[CodeStore.offsetAt(a) + Op.BLK_HDR2]);
    }

    // ---------------------------------------------------------------------------------------------------
    // Entering a block
    // ---------------------------------------------------------------------------------------------------

    /**
     * The first half of the entry step of ACT (H4; ISA 4.1 CALL, 1.4 "one tick per block entry"): TICK, the
     * call event, and the window of the block at {@code code[base ..]} on the register stack with its header
     * (captured frame {@code cap}, substitution {@code ls}, the scratch mark). The caller copies the
     * arguments in and continues with {@link #go}.
     *
     * @return the window base {@code rb}, or a signal
     */
    private static int begin(Machine m, int[] code, int base, int cap, int ls) {
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig;
        }
        if (m.stats) {
            m.call(Op.ACT);
        }
        int win = arity(code[base + Op.BLK_HDR2]) + nreg(code[base]);
        if (!m.ensureRegs(HDR + win)) {
            return m.fault(MachineErrors.E_MEM);
        }
        int rb = m.rp + HDR;
        int[] rs = m.RS;
        rs[rb - W_CAP] = cap;
        rs[rb - W_LS] = ls;
        rs[rb - W_XP] = m.xp;
        m.rp = rb + win;
        m.x7.entered++;
        return rb;
    }

    /**
     * The second half of the entry step: the depth test (too deep: the activation becomes the frame
     * {@code ACT[blk, pc, DST_ENTRY, ..]}, which is ticked again when the driver resumes it), then the
     * dispatch loop from entry {@code e}. An entry word of 0 has no code (a rule that is walked).
     */
    private static int go(Machine m, int[] code, int base, int blk, int e, int rb, int d) {
        int pc = e < nentry(code[base + Op.BLK_HDR2]) ? code[base + Op.BLK_ENTRIES + e] : 0;
        if (pc == 0) {
            return m.fault(MachineErrors.E_INTERNAL);
        }
        boolean hooked = m.x7.hooked;
        if (hooked) {
            Tier.count(m, m.x7, blk); // heat (ISA 8.3): only while the engine counts it
        }
        if (d > m.hostDepth) {
            int[] rs = m.RS;
            return m.spill7(K_ACT, blk, pc, DST_ENTRY, 0, rs[rb - W_CAP], rs[rb - W_LS], rb);
        }
        return hooked ? Tier.exec(m, code, base, blk, pc, rb, d) : resumeAt(m, code, base, blk, pc, rb, d);
    }

    /**
     * Runs entry {@code e} of block {@code blk} on the {@code n} arguments {@code src[from .. from + n)}
     * (copied into the window; {@code src} is {@code m.X} or {@code m.rs}), with captured frame
     * {@code cap} and substitution {@code ls}: the entry step of ACT.
     *
     * @return the result of the block, or a signal
     */
    static int enter(Machine m, int blk, int e, int cap, int ls, int[] src, int from, int n, int d) {
        CodeStore cs = m.engine.code;
        int a = addressOf(m, blk);
        if (a == 0) {
            return m.fault(MachineErrors.E_INTERNAL);
        }
        return enterAt(m, cs.chunkAt(a), CodeStore.offsetAt(a), blk, e, cap, ls, src, from, n, d);
    }

    /** {@link #enter} for a block that the caller located ({@code CodeStore.address}). */
    private static int enterAt(Machine m, int[] code, int base, int blk, int e, int cap, int ls, int[] src, int from, int n, int d) {
        int rb = begin(m, code, base, cap, ls);
        if (rb >= SIG) {
            return rb;
        }
        int[] rs = m.RS;
        for (int i = 0; i < n; i++) {
            rs[rb + i] = src[from + i];
        }
        return go(m, code, base, blk, e, rb, d);
    }

    /**
     * A BODY or RULE block on its arguments in scratch (ISA 4.3.5 IOTA: the rule's arguments; 4.3.2 DELTA
     * when the caller collected the arguments): {@code arity} arguments at {@code X[xb ..]}. The scratch
     * arguments stay the caller's.
     */
    static int callX(Machine m, int blk, int ls, int xb, int d) {
        return enter(m, blk, 0, 0, ls, m.X, xb, arityOf(m, blk), d);
    }

    /**
     * RULE(rec, j) on its {@code n = params + motives + minors + fields} arguments in scratch (ISA 4.3.5
     * IOTA: the arguments before the major premise, then the last nfields arguments of the major). The
     * caller tested {@link #hasEntry}.
     */
    static int callRule(Machine m, int blk, int j, int ls, int xb, int n, int d) {
        return enter(m, blk, j, 0, ls, m.X, xb, n, d);
    }

    /**
     * The block of a closure applied to {@code j} arguments in scratch (ISA 4.3.1 APPLY "CODE(f) ? call
     * its block at entry j (capRun = fr)"; INST of a PI closure with j = 1): {@code 1 <= j <= arity};
     * {@code cap} is the closure's frame run, {@code ls} its substitution. An argument the body does not
     * read may be passed as it is or as HOLE: the block never reads it.
     */
    static int callClosure(Machine m, int blk, int j, int cap, int ls, int xb, int d) {
        return enter(m, blk, j - 1, cap, ls, m.X, xb, j, d);
    }

    /** {@link #callClosure} with one argument given directly (INST of a PI closure: no scratch). */
    static int callClosure1(Machine m, int blk, int cap, int ls, int x, int d) {
        CodeStore cs = m.engine.code;
        int a = addressOf(m, blk);
        if (a == 0) {
            return m.fault(MachineErrors.E_INTERNAL);
        }
        int[] code = cs.chunkAt(a);
        int base = CodeStore.offsetAt(a);
        int rb = begin(m, code, base, cap, ls);
        if (rb >= SIG) {
            return rb;
        }
        m.RS[rb] = x;
        return go(m, code, base, blk, 0, rb, d);
    }

    /**
     * The block of closure record {@code clo} (LAM or PI), through its CODE memo (ISA 2.6: a hint outside
     * I-FLAG, looked up at the first APPLY or INST of the record): 0 = walk the term.
     */
    static int closureOf(Machine m, int clo) {
        int o = (clo << Layout.REC_SHIFT) + Layout.M_CODE;
        int c = m.vh[o];
        if (c == 0) {
            c = closureBlock(m, m.vh[(clo << Layout.REC_SHIFT) + Layout.A]);
            m.vh[o] = c == 0 ? Layout.NOCODE : c;
        }
        return c > 0 ? c : 0;
    }

    /**
     * BODY(row) on the spine run of the unfolding application (ISA 4.3.2 DELTA): the block reads its
     * first {@code arity} arguments; the caller applies the rest.
     */
    static int callRun(Machine m, int blk, int ls, int run, int d) {
        return enter(m, blk, 0, 0, ls, m.rs, run + Layout.RUN_H + (m.rs[run] & Layout.RUN_FAR), arityOf(m, blk), d);
    }

    /**
     * The code stepper (ISA 8.2, *agentic-note*: rep-stepper-convention, H7): entry and continuation step of an activation at once (the
     * state is the pc); registers in {@code m.RS[rbase ..]}; a class-j instruction is rule H3 with the frame
     * ACT [blk, pc, dst, argRun, capRun, ls, rbase]. Staging steps 3 and 4.
     * @return the result, or a signal ({@code >= Op.SIG}): SUSP, ABORT, FATAL
     */
    static int run(Machine m, int blk, int pc, int rbase, int d) {
        CodeStore cs = m.engine.code;
        int a = addressOf(m, blk);
        if (a == 0) {
            return m.fault(MachineErrors.E_INTERNAL);
        }
        return m.x7.hooked ? Tier.exec(m, cs.chunkAt(a), CodeStore.offsetAt(a), blk, pc, rbase, d)
                : resumeAt(m, cs.chunkAt(a), CodeStore.offsetAt(a), blk, pc, rbase, d);
    }

    /** The family's part of the driver (H6): re-enters an activation; {@code r} goes to the register named by ACT_DST. */
    static int resume(Machine m, int key, int[] w, int p, int r) {
        if (key != K_ACT) {
            switch (key) {
                case K_RULES_1:
                    return k_rules1(m, w[p], w[p + 1], r, 0);
                case K_RULES_2:
                    return k_rules2(m, w[p], r, 0);
                default:
                    return m.fault(MachineErrors.E_INTERNAL);
            }
        }
        int blk = w[p + Op.ACT_BLK];
        int pc = w[p + Op.ACT_PC];
        int dst = w[p + Op.ACT_DST];
        int rb = w[p + Op.ACT_RBASE];
        int[] rs = m.RS;
        m.xp = rs[rb - W_XP]; // the awaited judgment has returned: the activation holds no scratch
        switch (dst) {
            case DST_RET:
                m.rp = rb - HDR;
                return r;
            case DST_ENTRY:
                if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
                    return m.sig;
                }
                if (m.stats) {
                    m.call(Op.ACT);
                }
                break;
            case DST_NONE:
                break;
            case DST_REQ_APP:
                if (r != Layout.T) {
                    return m.fail(MachineErrors.E_APP_MISMATCH);
                }
                break;
            default:
                if ((dst & DST_KVAL) != 0) {
                    kvalFill(m, blk << 12 | (pc - 2), pc - 2, (dst & DST_KVAL_LS) != 0 ? rs[rb - W_LS] : 0, r);
                    dst &= 255;
                }
                rs[rb + dst] = r;
                break;
        }
        return run(m, blk, pc, rb, 0);
    }

    // ---------------------------------------------------------------------------------------------------
    // The dispatch loop
    // ---------------------------------------------------------------------------------------------------

    /**
     * Runs the activation at window {@code rb} from {@code pc} until it returns or a sub-judgment is
     * suspended (ISA 8.2 "the code stepper"; the dense dispatch of an instruction word). Class p
     * instructions complete inline; class j instructions are host calls (H3); class c instructions set
     * the pc. Named {@code resume..} because it is the family's dispatch switch (H10 rule 2: such a
     * switch is exempt from the step size limit; every instruction body is a small method of its own).
     */
    static int resumeAt(Machine m, int[] code, int base, int blk, int pc, int rb, int d) {
        for (;;) {
            int at = base + pc;
            int w = code[at];
            int r;
            switch (w >>> 24) {
                case Op.MKAPP:
                    r = mkApp(m, code, at, rb, w);
                    break;
                case Op.KCONST:
                    r = kConst(m, code, at, blk, pc, rb, w);
                    break;
                case Op.KSORT:
                    r = kSort(m, code, at, rb, w);
                    break;
                case Op.KLIT:
                    r = set(m, rb, w, Lits.klit(m, code[at + 1] & PAY));
                    break;
                case Op.MKLAM:
                case Op.MKPI:
                case Op.MKSUSP:
                    r = mkClosure(m, code, at, rb, w);
                    break;
                case Op.MKPROJ:
                    r = mkProj(m, code, at, rb, w);
                    break;
                case Op.MOV:
                    r = set(m, rb, w, val(m, rb, code[at + 1]));
                    break;
                case Op.PEEK:
                    r = peek(m, code, at, rb, w);
                    break;
                case Op.NSUCC:
                    r = nsucc(m, code, at, rb, w);
                    break;
                case Op.FIELDS:
                    r = fields(m, code, at, rb, w);
                    break;
                case Op.JMP:
                    pc = code[at + 1] & PAY;
                    continue;
                case Op.BKIND:
                    pc = bkind(m, code, at, rb, pc);
                    if (pc >= SIG) {
                        return pc;
                    }
                    continue;
                case Op.BEQ:
                case Op.BNE:
                    pc = beq(m, code, at, rb, w, pc);
                    if (pc >= SIG) {
                        return pc;
                    }
                    continue;
                case Op.BUNREAD:
                    pc = bunread(m, code, at, rb, w, pc);
                    if (pc >= SIG) {
                        return pc;
                    }
                    continue;
                case Op.SWITCH:
                    pc = switchOn(m, code, at, rb, w);
                    if (pc >= SIG) {
                        return pc;
                    }
                    continue;
                case Op.MATCH:
                    pc = matchAt(m, code, at, rb, pc);
                    if (pc >= SIG) {
                        return pc;
                    }
                    continue;
                case Op.RET: {
                    int v = val(m, rb, code[at + 1]);
                    if (v < SIG) {
                        m.rp = rb - HDR;
                    }
                    return v;
                }
                case Op.KVAL:
                    r = kval(m, code, at, blk, pc, rb, w, d);
                    break;
                case Op.APPLY:
                    r = apply(m, code, at, blk, pc, rb, w, d);
                    break;
                case Op.TAILAPPLY:
                    return tailApply(m, code, at, blk, rb, w, d);
                case Op.WHNF:
                case Op.FORCE:
                    r = unary(m, code, at, blk, pc, rb, w, d);
                    break;
                case Op.TARG:
                    pc = targ(m, code, base, at, blk, pc, rb, w, d);
                    if (pc >= SIG) {
                        return pc;
                    }
                    if (pc < 0) {
                        return ~pc == 0 ? m.fault(MachineErrors.E_INTERNAL) : expectedOf(m, code, base, rb);
                    }
                    continue;
                case Op.TRET: {
                    int v = val(m, rb, code[at + 1]);
                    if (v < SIG) {
                        m.rp = rb - HDR;
                    }
                    return v;
                }
                default:
                    return m.fault(MachineErrors.E_INTERNAL);
            }
            if (r >= SIG) {
                return r;
            }
            pc += 1 + (w >>> 8 & 255);
        }
    }

    /** {@link #resumeOne}: the activation ended with result or signal {@code r}. */
    private static int done(Machine m, int r) {
        m.x7.tierResult = r;
        return Tier.DONE;
    }

    /**
     * One instruction of the activation at window {@code rb}, through the same instruction bodies as
     * {@link #resumeAt} (the compiled-tier hook, {@code Tier.step}; ISA 8.4 "call threading"): the pc of the
     * next instruction, or {@code Tier.DONE} when the activation returned, was suspended or failed; its result
     * or signal is then in {@code m.x7.tierResult}. Never called by the stepper itself.
     */
    static int resumeOne(Machine m, int[] code, int base, int blk, int pc, int rb, int d) {
        int at = base + pc;
        int w = code[at];
        int r;
        switch (w >>> 24) {
            case Op.MKAPP:
                r = mkApp(m, code, at, rb, w);
                break;
            case Op.KCONST:
                r = kConst(m, code, at, blk, pc, rb, w);
                break;
            case Op.KSORT:
                r = kSort(m, code, at, rb, w);
                break;
            case Op.KLIT:
                r = set(m, rb, w, Lits.klit(m, code[at + 1] & PAY));
                break;
            case Op.MKLAM:
            case Op.MKPI:
            case Op.MKSUSP:
                r = mkClosure(m, code, at, rb, w);
                break;
            case Op.MKPROJ:
                r = mkProj(m, code, at, rb, w);
                break;
            case Op.MOV:
                r = set(m, rb, w, val(m, rb, code[at + 1]));
                break;
            case Op.PEEK:
                r = peek(m, code, at, rb, w);
                break;
            case Op.NSUCC:
                r = nsucc(m, code, at, rb, w);
                break;
            case Op.FIELDS:
                r = fields(m, code, at, rb, w);
                break;
            case Op.JMP:
                return code[at + 1] & PAY;
            case Op.BKIND:
                r = bkind(m, code, at, rb, pc);
                return r >= SIG ? done(m, r) : r;
            case Op.BEQ:
            case Op.BNE:
                r = beq(m, code, at, rb, w, pc);
                return r >= SIG ? done(m, r) : r;
            case Op.BUNREAD:
                r = bunread(m, code, at, rb, w, pc);
                return r >= SIG ? done(m, r) : r;
            case Op.SWITCH:
                r = switchOn(m, code, at, rb, w);
                return r >= SIG ? done(m, r) : r;
            case Op.MATCH:
                r = matchAt(m, code, at, rb, pc);
                return r >= SIG ? done(m, r) : r;
            case Op.RET:
            case Op.TRET: {
                int v = val(m, rb, code[at + 1]);
                if (v < SIG) {
                    m.rp = rb - HDR;
                }
                return done(m, v);
            }
            case Op.KVAL:
                r = kval(m, code, at, blk, pc, rb, w, d);
                break;
            case Op.APPLY:
                r = apply(m, code, at, blk, pc, rb, w, d);
                break;
            case Op.TAILAPPLY:
                return done(m, tailApply(m, code, at, blk, rb, w, d));
            case Op.WHNF:
            case Op.FORCE:
                r = unary(m, code, at, blk, pc, rb, w, d);
                break;
            case Op.TARG:
                r = targ(m, code, base, at, blk, pc, rb, w, d);
                if (r >= SIG) {
                    return done(m, r);
                }
                if (r < 0) {
                    return done(m, ~r == 0 ? m.fault(MachineErrors.E_INTERNAL) : expectedOf(m, code, base, rb));
                }
                return r;
            default:
                return done(m, m.fault(MachineErrors.E_INTERNAL));
        }
        return r >= SIG ? done(m, r) : pc + 1 + (w >>> 8 & 255);
    }

    // ---------------------------------------------------------------------------------------------------
    // Operands
    // ---------------------------------------------------------------------------------------------------

    /**
     * {@code ⟦o⟧} (ISA 3.3 modes, 4.0): the value of a value operand, read the way the generic walk reads a
     * variable: {@code PEEK(GET)} (ISA 4.2; {@code Eval.atom}): a plain suspension whose value has become
     * known is seen through. Reading HOLE through an argument or a captured slot is {@code fault(E_HOLE)},
     * as GET does (ISA 2.1, 2.4): a block never reads a binder its source term does not read, so this is
     * an elaborator or mask error and must not become a value. A register may hold HOLE (BUNREAD) and pass
     * it to an APPLY.
     *
     * @return the value, or FATAL
     */
    static int val(Machine m, int rb, int o) {
        int v;
        switch (o >>> Op.OPND_SHIFT) {
            case Op.MODE_R:
                v = m.RS[rb + (o & PAY)];
                break;
            case Op.MODE_A:
                v = m.RS[rb + (o & PAY)];
                if (v == Layout.HOLE) {
                    return m.fault(MachineErrors.E_HOLE);
                }
                break;
            case Op.MODE_C:
                v = m.rs[m.RS[rb - W_CAP] + Layout.RUN_H + (o & PAY)];
                if (v == Layout.HOLE) {
                    return m.fault(MachineErrors.E_HOLE);
                }
                break;
            case Op.MODE_I:
                return (o & PAY) | Layout.IMM;
            case Op.MODE_V:
                return o & PAY;
            default:
                return m.fault(MachineErrors.E_INTERNAL);
        }
        if (v > Layout.HOLE && m.vh[v << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT == Layout.SUSP) {
            return Values.peek(m, v);
        }
        return v;
    }

    /**
     * The operand as a slot of a capture list: the binding as it is, not seen through and not tested, which
     * is what {@code PRUNE} copies from the environment of the generic walk ({@code Runs.pruneNear}); the
     * frames of both tiers are then the same run (decision 6). An immediate operand is the binding of a variable
     * that a let or an inlined beta step binds to a small numeral ({@code Elab.atom}: ARGV of a numeral is the
     * immediate value, which is also what the generic walk pushes: {@code Eval.letFrom} / {@code argvFast}); until
     * 2026-11-25 this mode fell into the default branch and the unit was declined with E_INTERNAL (finding S3-F7).
     */
    static int raw(Machine m, int rb, int o) {
        switch (o >>> Op.OPND_SHIFT) {
            case Op.MODE_R:
            case Op.MODE_A:
                return m.RS[rb + (o & PAY)];
            case Op.MODE_C:
                return m.rs[m.RS[rb - W_CAP] + Layout.RUN_H + (o & PAY)];
            case Op.MODE_I:
                return (o & PAY) | Layout.IMM;
            case Op.MODE_V:
                return o & PAY;
            default:
                return m.fault(MachineErrors.E_INTERNAL);
        }
    }

    /** {@code d ← v} for a pure instruction; passes a signal on. */
    private static int set(Machine m, int rb, int w, int v) {
        if (v >= SIG) {
            return v;
        }
        m.RS[rb + (w >>> 16 & 255)] = v;
        return 0;
    }

    /**
     * Evaluates the operands {@code code[from .. from + n)} into scratch at {@code m.xp} and claims them
     * ({@code m.xp += n}); the caller releases them by restoring {@code xp}.
     *
     * @return the scratch base, or a signal
     */
    private static int operands(Machine m, int[] code, int from, int n, int rb) {
        if (!m.ensureScratch(n)) {
            return m.fault(MachineErrors.E_MEM);
        }
        int xb = m.xp;
        int[] x = m.X;
        int[] rs = m.RS;
        int[] vh = m.vh;
        for (int i = 0; i < n; i++) {
            int o = code[from + i];
            int v;
            if (o >>> Op.OPND_SHIFT <= Op.MODE_A) {
                // a register or an argument: the common operand, read as val reads it (nothing here allocates)
                v = rs[rb + (o & PAY)];
                if (v > Layout.HOLE) {
                    int w0 = vh[v << Layout.REC_SHIFT];
                    if (w0 >>> Layout.KIND_SHIFT == Layout.SUSP) {
                        v = Values.peek(m, v);
                    }
                } else if (v == Layout.HOLE && o >>> Op.OPND_SHIFT == Op.MODE_A) {
                    return m.fault(MachineErrors.E_HOLE);
                }
            } else {
                v = val(m, rb, o);
                if (v >= SIG) {
                    return v;
                }
            }
            x[xb + i] = v;
        }
        m.xp = xb + n;
        return xb;
    }

    // ---------------------------------------------------------------------------------------------------
    // Class p: value construction (ISA 4.2)
    // ---------------------------------------------------------------------------------------------------

    /** MKAPP d, h, a1..an (ISA 4.2): the folded application, through {@code Values.mkApp} (Nat.succ rule, flags). */
    private static int mkApp(Machine m, int[] code, int at, int rb, int w) {
        int n = (w >>> 8 & 255) - 1;
        int h = val(m, rb, code[at + 1]);
        if (h >= SIG) {
            return h;
        }
        int xb = operands(m, code, at + 2, n, rb);
        if (xb >= SIG) {
            return xb;
        }
        int r = Values.mkApp(m, h, xb, n);
        m.xp = xb;
        return set(m, rb, w, r);
    }

    /**
     * KCONST d, D, LL (ISA 4.2): {@code CHEAD(D, LSUB(LL, ls))}. A constant with level parameters is answered by
     * the inline cache of its site (ISA 1.5 {@code IC[site] = (epoch, ls, v)}, shared with KVAL: one probe instead
     * of the LSUB cache and the CHEAD cache); the value is the record those two give.
     */
    private static int kConst(Machine m, int[] code, int at, int blk, int pc, int rb, int w) {
        int list = code[at + 2] & PAY;
        if ((w & X_LPARAM) == 0) {
            return set(m, rb, w, Values.mkConst(m, code[at + 1] & PAY, list));
        }
        int ls = m.RS[rb - W_LS];
        if (pc >= 4096) {
            return set(m, rb, w, Values.mkConst(m, code[at + 1] & PAY, Levels.lsubList(m, list, ls)));
        }
        int site = blk << 12 | pc;
        int c = (Hash.cell(site, ls) & IC_MASK) << Layout.CELL_SHIFT;
        int[] ic = m.ic;
        if (ic[c + Layout.IC_SITE] == site && ic[c + Layout.IC_EPOCH] == m.epoch && ic[c + Layout.IC_KEY] == ls) {
            m.RS[rb + (w >>> 16 & 255)] = ic[c + Layout.IC_VALUE];
            return 0;
        }
        int v = Values.mkConst(m, code[at + 1] & PAY, Levels.lsubList(m, list, ls));
        if (v >= SIG) {
            return v;
        }
        ic[c + Layout.IC_SITE] = site;
        ic[c + Layout.IC_EPOCH] = m.epoch;
        ic[c + Layout.IC_KEY] = ls;
        ic[c + Layout.IC_VALUE] = v;
        m.RS[rb + (w >>> 16 & 255)] = v;
        return 0;
    }

    /** KSORT d, L (ISA 4.2): {@code SORT(LSUB(L, ls))}. */
    private static int kSort(Machine m, int[] code, int at, int rb, int w) {
        int l = code[at + 1] & PAY;
        if ((w & X_LPARAM) != 0) {
            l = Levels.lsub(m, l, m.RS[rb - W_LS]);
        }
        return set(m, rb, w, Values.mkSort(m, l));
    }

    /**
     * MKLAM / MKPI d, T, B, c0..ck and MKSUSP d, T, c0..ck (ISA 4.2): the closure or suspension of term T
     * over {@code FRAME(c)}, with {@code ls' = HAS_LPARAM(T) ? ls : 0}. The frame is interned by content,
     * so it is the run {@code PRUNE(env, mask T)} of the generic walk (decision 6). The block B of a
     * closure is left in its CODE memo (a hint outside I-FLAG, ISA 2.6).
     */
    private static int mkClosure(Machine m, int[] code, int at, int rb, int w) {
        int op = w >>> 24;
        int first = op == Op.MKSUSP ? at + 2 : at + 3;
        int nc = at + 1 + (w >>> 8 & 255) - first;
        int frame = Layout.RUN_EMPTY;
        if (nc > 0) {
            if (!m.ensureScratch(nc)) {
                return m.fault(MachineErrors.E_MEM);
            }
            int xb = m.xp;
            for (int i = 0; i < nc; i++) {
                int v = raw(m, rb, code[first + i]);
                if (v >= SIG) {
                    return v;
                }
                m.X[xb + i] = v;
            }
            m.xp = xb + nc;
            frame = Runs.frame(m, xb, nc, Layout.RUN_EMPTY);
            m.xp = xb;
            if (frame >= SIG) {
                return frame;
            }
        }
        int t = code[at + 1] & PAY;
        int ls = (w & X_LPARAM) != 0 ? m.RS[rb - W_LS] : 0;
        int v;
        if (op == Op.MKSUSP) {
            v = Values.mkSusp(m, t, frame, ls, (w & X_LET) != 0);
        } else {
            v = op == Op.MKLAM ? Values.mkLam(m, t, frame, ls) : Values.mkPi(m, t, frame, ls);
            int b = code[at + 2] & PAY;
            if (v < SIG && b != 0 && Values.memo(m, v, Layout.M_CODE - Layout.M0) == 0) {
                Values.setMemo(m, v, Layout.M_CODE - Layout.M0, b);
            }
        }
        return set(m, rb, w, v);
    }

    /** MKPROJ d, S, i, s (ISA 4.2). */
    private static int mkProj(Machine m, int[] code, int at, int rb, int w) {
        int s = val(m, rb, code[at + 3]);
        if (s >= SIG) {
            return s;
        }
        return set(m, rb, w, Values.mkProj(m, code[at + 1] & PAY, code[at + 2] & PAY, s));
    }

    /** PEEK d, s (ISA 4.2). */
    private static int peek(Machine m, int[] code, int at, int rb, int w) {
        int s = val(m, rb, code[at + 1]);
        return set(m, rb, w, s >= SIG || s < 0 || s <= Layout.HOLE ? s : Values.peek(m, s));
    }

    /** NSUCC d, s (ISA 4.2). */
    private static int nsucc(Machine m, int[] code, int at, int rb, int w) {
        int s = val(m, rb, code[at + 1]);
        return set(m, rb, w, s >= SIG ? s : Values.nsucc(m, s));
    }

    // ---------------------------------------------------------------------------------------------------
    // Class c: control (ISA 4.1)
    // ---------------------------------------------------------------------------------------------------

    /** BKIND s, set, L (ISA 4.1): branch if the kind of the value is in the set. Returns the next pc. */
    private static int bkind(Machine m, int[] code, int at, int rb, int pc) {
        int v = val(m, rb, code[at + 1]);
        if (v >= SIG) {
            return v;
        }
        int k = v < 0 ? Layout.K_IMM : m.vh[v << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT;
        return ((code[at + 2] & PAY) >>> k & 1) != 0 ? code[at + 3] & PAY : pc + 4;
    }

    /** BEQ / BNE a, b, L (ISA 4.1): identity of refs (I-ID). */
    private static int beq(Machine m, int[] code, int at, int rb, int w, int pc) {
        int a = val(m, rb, code[at + 1]);
        int b = val(m, rb, code[at + 2]);
        if (a >= SIG || b >= SIG) {
            return a >= SIG ? a : b;
        }
        return (a == b) == (w >>> 24 == Op.BEQ) ? code[at + 3] & PAY : pc + 4;
    }

    /**
     * BUNREAD d, f, i, n, L (ISA 4.1; S1 of R05 section 4, B6): the i-th of the n arguments that the next
     * APPLY passes to {@code f} is not built if {@code f} is a closure whose body after
     * {@code j = min(leading lambdas, n)} binders does not read its i-th binder: {@code d ← HOLE}, branch.
     * That is the test APPLY itself makes (4.3.1), on the node's mask: a clear bit is definite (2.4).
     */
    private static int bunread(Machine m, int[] code, int at, int rb, int w, int pc) {
        int f = val(m, rb, code[at + 1]);
        if (f >= SIG) {
            return f;
        }
        if (f > Layout.HOLE && m.vh[f << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT == Layout.LAM) {
            int i = code[at + 2] & PAY;
            int n = code[at + 3] & PAY;
            Terms terms = m.terms;
            int t = m.vh[(f << Layout.REC_SHIFT) + Layout.A];
            int j = 0;
            while (j < n && terms.kind(t) == Kind.LAM) {
                t = terms.b(t);
                j++;
            }
            int sh = j - 1 - i;
            if (i < j && sh < Layout.NEAR_SLOTS && (terms.fvMask(t) >>> sh & 1) == 0) {
                m.RS[rb + (w >>> 16 & 255)] = Layout.HOLE;
                return code[at + 4] & PAY;
            }
        }
        return pc + 5;
    }

    /**
     * SWITCH s, I, L0..Lk-1, Lx (ISA 4.1): the constructor index of a WHNF result selects the arm; anything
     * that is not a constructor application of inductive I (a stuck major, a literal the Nat extension
     * does not own, a string literal, a big numeral) goes to Lx, where the block returns NONE and the
     * caller takes the DELTA path (3.6: "else ret NONE"). Official: rule selection by constructor (R02 7.4).
     */
    private static int switchOn(Machine m, int[] code, int at, int rb, int w) {
        int v = val(m, rb, code[at + 1]);
        if (v >= SIG) {
            return v;
        }
        int k = (w >>> 8 & 255) - 3;
        int ind = code[at + 2] & PAY;
        int j = -1;
        if (v < 0) {
            if (isNat(m, ind)) {
                j = v == Layout.IMM ? 0 : 1;
            }
        } else if (v > Layout.HOLE) {
            j = ctorIndex(m, v, ind);
        }
        return code[at + 3 + (j >= 0 && j < k ? j : k)] & PAY;
    }

    /** True if {@code ind} is the Nat of the literal extension and its three guards are VALID (ISA 2.9 natOk). */
    static boolean isNat(Machine m, int ind) {
        Specials sp = m.specials;
        return sp.cid(Specials.NAT) == ind && sp.state(Specials.NAT) == Specials.VALID
                && sp.state(Specials.NAT_ZERO) == Specials.VALID && sp.state(Specials.NAT_SUCC) == Specials.VALID;
    }

    /**
     * The constructor index of {@code v} if it is a constructor of inductive {@code ind} applied to at least
     * its parameters and fields (or the NSUC view of {@code Nat.succ}), else -1.
     */
    static int ctorIndex(Machine m, int v, int ind) {
        int[] vh = m.vh;
        int o = v << Layout.REC_SHIFT;
        int kind = vh[o] >>> Layout.KIND_SHIFT;
        if (kind == Layout.NSUC) {
            return isNat(m, ind) ? 1 : -1;
        }
        int n = 0;
        int head = v;
        if (kind == Layout.NEU) {
            n = vh[o] & Layout.AUX_MASK;
            head = vh[o + Layout.A];
            o = head << Layout.REC_SHIFT;
            kind = head < 0 ? Layout.K_IMM : vh[o] >>> Layout.KIND_SHIFT;
        }
        if (kind != Layout.CONST) {
            return -1;
        }
        int row = vh[o + Layout.A];
        DeclTable decls = m.decls;
        if (decls.kind(row) != DeclKind.CTOR || decls.ctorInduct(row) != ind
                || n != decls.ctorNumParams(row) + decls.ctorNumFields(row)) {
            return -1;
        }
        return decls.ctorIndex(row);
    }

    /**
     * FIELDS d0, s (x = n) (ISA 4.1): after a constructor arm of SWITCH, {@code d0.. ←} the last n arguments
     * of the constructor application; the Nat literal view gives the predecessor literal, NSUC(b, k) the
     * value {@code b} for k = 1 (official: "the LAST nfields arguments", R02 7.4; views of 4.3.5).
     */
    private static int fields(Machine m, int[] code, int at, int rb, int w) {
        int v = val(m, rb, code[at + 1]);
        if (v >= SIG) {
            return v;
        }
        int n = w & 255;
        int d0 = rb + (w >>> 16 & 255);
        if (v < 0) {
            if (n != 1 || v == Layout.IMM) {
                return m.fault(MachineErrors.E_INTERNAL);
            }
            m.RS[d0] = v - 1;
            return 0;
        }
        int o = v << Layout.REC_SHIFT;
        int[] vh = m.vh;
        int kind = vh[o] >>> Layout.KIND_SHIFT;
        if (kind == Layout.NSUC) {
            int b = vh[o + Layout.A];
            if (n != 1) {
                return m.fault(MachineErrors.E_INTERNAL);
            }
            int p = vh[o + Layout.B] == 1 ? b : Values.npred(m, v);
            if (p >= SIG) {
                return p;
            }
            m.RS[d0] = p;
            return 0;
        }
        int len = kind == Layout.NEU ? vh[o] & Layout.AUX_MASK : 0;
        if (len < n) {
            return m.fault(MachineErrors.E_INTERNAL);
        }
        int run = vh[o + Layout.B];
        for (int i = 0; i < n; i++) {
            m.RS[d0 + i] = Runs.arg(m, run, len - n + i);
        }
        return 0;
    }

    /** MATCH s, T, n, L (ISA 4.4.4, B5): branch to L if the pattern T over g_0..g_(n-1) matches the value. */
    private static int matchAt(Machine m, int[] code, int at, int rb, int pc) {
        int v = val(m, rb, code[at + 1]);
        if (v >= SIG) {
            return v;
        }
        int r = Match.matchIn(m, m.RS, rb, code[at + 3] & PAY, code[at + 2] & PAY, m.RS[rb - W_LS], v);
        if (r >= SIG) {
            return r;
        }
        if (r == Layout.T) {
            m.x7.matchT++;
            return code[at + 4] & PAY;
        }
        m.x7.matchF++;
        return pc + 5;
    }

    // ---------------------------------------------------------------------------------------------------
    // Class j: instructions that call a judgment (H3)
    // ---------------------------------------------------------------------------------------------------

    /** The frame of this activation waiting at {@code pc} for a result that goes to {@code dst} (H3). */
    static int susp(Machine m, int r, int blk, int pc, int dst, int rb) {
        int[] rs = m.RS;
        return m.sig7(r, K_ACT, blk, pc, dst, 0, rs[rb - W_CAP], rs[rb - W_LS], rb);
    }

    /**
     * KVAL d, K (ISA 4.2): the value of a closed term, once per session and substitution: the inline cache
     * {@code IC[site] = (epoch, ls, v)} with {@code site = block << 12 | pc}, else the generic walk
     * ({@code EVAL}, or {@code ARGV} when the term stands in an argument position), then fill.
     */
    private static int kval(Machine m, int[] code, int at, int blk, int pc, int rb, int w, int d) {
        int key = (w & KV_LPARAM) != 0 ? m.RS[rb - W_LS] : 0;
        int site = blk << 12 | pc;
        int c = pc < 4096 ? (Hash.cell(site, key) & IC_MASK) << Layout.CELL_SHIFT : -1;
        int v = c < 0 ? Op.MISS : icGet(m, c, site, key);
        if (v == Op.MISS) {
            int t = code[at + 1] & PAY;
            v = (w & KV_ARGV) != 0 ? Eval.argv(m, t, m.ep, 0, key, d + 1) : Eval.eval(m, t, m.ep, 0, key, d + 1);
            if (v >= SIG) {
                // the value arrives through the frame: it fills the cache there (DST_KVAL), as below
                return susp(m, v, blk, pc + 2, DST_KVAL | ((w & KV_LPARAM) != 0 ? DST_KVAL_LS : 0) | (w >>> 16 & 255), rb);
            }
            if (c >= 0) {
                int[] ic = m.ic;
                ic[c + Layout.IC_SITE] = site;
                ic[c + Layout.IC_EPOCH] = m.epoch;
                ic[c + Layout.IC_KEY] = key;
                ic[c + Layout.IC_VALUE] = v;
            }
        }
        m.RS[rb + (w >>> 16 & 255)] = v;
        return 0;
    }

    /** The fill of {@link #kval} for a value that arrived through a frame ({@link #DST_KVAL}): the same cell, the same words. */
    private static void kvalFill(Machine m, int site, int pc, int key, int v) {
        if (pc < 4096) {
            int c = (Hash.cell(site, key) & IC_MASK) << Layout.CELL_SHIFT;
            int[] ic = m.ic;
            ic[c + Layout.IC_SITE] = site;
            ic[c + Layout.IC_EPOCH] = m.epoch;
            ic[c + Layout.IC_KEY] = key;
            ic[c + Layout.IC_VALUE] = v;
        }
    }

    /** The inline cache cell {@code c} of ISA 1.5 for {@code (site, key)}: its value seen through PEEK, or {@code Op.MISS}. */
    static int icGet(Machine m, int c, int site, int key) {
        int[] ic = m.ic;
        if (ic[c + Layout.IC_SITE] != site || ic[c + Layout.IC_EPOCH] != m.epoch || ic[c + Layout.IC_KEY] != key) {
            if (m.stats) {
                m.x7.icMisses++;
            }
            return Op.MISS;
        }
        if (m.stats) {
            m.x7.icHits++;
        }
        int v = ic[c + Layout.IC_VALUE];
        return v > Layout.HOLE ? Values.peek(m, v) : v;
    }

    /** APPLY d, f, a1..an (ISA 4.3.1). */
    private static int apply(Machine m, int[] code, int at, int blk, int pc, int rb, int w, int d) {
        int n = (w >>> 8 & 255) - 1;
        int f = val(m, rb, code[at + 1]);
        if (f >= SIG) {
            return f;
        }
        int xb = operands(m, code, at + 2, n, rb);
        if (xb >= SIG) {
            return xb;
        }
        int r = Eval.apply(m, f, xb, n, d + 1);
        if (r >= SIG) {
            return susp(m, r, blk, pc + 2 + n, w >>> 16 & 255, rb);
        }
        m.xp = xb;
        m.RS[rb + (w >>> 16 & 255)] = r;
        return 0;
    }

    /**
     * TAILAPPLY f, a1..an (ISA 4.1, 4.3.1): the result of the application is the result of the block.
     * The arguments are scratch of this activation, so the call is followed by a pop, not a host tail
     * call (*agentic-note*: rep-stepper-convention, H3 "tail position ... owns no scratch").
     */
    private static int tailApply(Machine m, int[] code, int at, int blk, int rb, int w, int d) {
        int n = (w >>> 8 & 255) - 1;
        int f = val(m, rb, code[at + 1]);
        if (f >= SIG) {
            return f;
        }
        int xb = operands(m, code, at + 2, n, rb);
        if (xb >= SIG) {
            return xb;
        }
        int r = Eval.apply(m, f, xb, n, d + 1);
        if (r >= SIG) {
            return susp(m, r, blk, 0, DST_RET, rb);
        }
        m.xp = xb;
        m.rp = rb - HDR;
        return r;
    }

    /** WHNF d, s and FORCE d, s (ISA 4.3). */
    private static int unary(Machine m, int[] code, int at, int blk, int pc, int rb, int w, int d) {
        int s = val(m, rb, code[at + 1]);
        if (s >= SIG) {
            return s;
        }
        int r = w >>> 24 == Op.WHNF ? Reduce.whnf(m, s, d + 1) : Eval.force(m, s, d + 1);
        if (r >= SIG) {
            return susp(m, r, blk, pc + 2, w >>> 16 & 255, rb);
        }
        m.RS[rb + (w >>> 16 & 255)] = r;
        return 0;
    }

    // ---------------------------------------------------------------------------------------------------
    // RULES (ISA 3.6, 4.3): the K1 case trees
    // ---------------------------------------------------------------------------------------------------

    /**
     * RULES(v) (ISA 3.6, 4.3): {@code v} is a NEU whose head constant has a case tree (shape CASESON or
     * MATCHER) and whose spine covers its lamArity: the tree is run on the first lamArity arguments and
     * the chosen alternative is applied to the arguments after them. Sound as a finite composition of
     * DELTA, the recursor rule and beta on the same arguments (3.6). Behind {@code Op.SW_CASETREES}.
     *
     * @return the reduct, {@code Layout.NONE} if there is no tree, the spine is too short or no arm matches, or a signal
     */
    static int rules(Machine m, int v, int d) {
        if (v <= Layout.HOLE || m.vh[v << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT != Layout.NEU) {
            return Layout.NONE;
        }
        int o = v << Layout.REC_SHIFT;
        int head = m.vh[o + Layout.A];
        if (head <= Layout.HOLE || m.vh[head << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT != Layout.CONST) {
            return Layout.NONE;
        }
        int ho = head << Layout.REC_SHIFT;
        if (m.vh[ho + Layout.C] < 0) {
            return Layout.NONE; // a head with the wrong number of levels does not unfold (R02 section 10 row 14): DELTA says so
        }
        int blk = blockFor(m, m.vh[ho + Layout.A], Op.ROLE_RULES);
        if (blk == 0 || blk >= SIG) {
            return blk;
        }
        int n = m.vh[o] & Layout.AUX_MASK;
        int k = arityOf(m, blk);
        if (n < k) {
            return Layout.NONE;
        }
        int run = m.vh[o + Layout.B];
        int r = enter(m, blk, 0, 0, m.vh[ho + Layout.C], m.rs, run + Layout.RUN_H, k, d + 1);
        return r >= SIG ? m.sig2(r, K_RULES_1, v, k) : k_rules1(m, v, k, r, d);
    }

    /**
     * RULES(v) restricted to shape CASESON, for the unfolding step of lazy delta (ISA 4.4.2 l2; official
     * {@code whnf_core(unfold_definition(t))}, R02 8.4): the head is a definition whose value is one direct
     * {@code I.rec} over its own parameters with eta-expanded minors ({@code I.casesOn} itself). On a constructor
     * major the tree's result is what DELTA, the recursor rule and beta give, and no state of the official loop
     * lies between them: the unfolding is {@code I.rec ..}, which {@code whnf_core} reduces at once. A MATCHER
     * tree is not taken here: after its first DELTA the official loop stands at {@code I.casesOn ..}, a state in
     * which it compares the two sides again (ISA 3.6 "lazy delta keeps single DELTA steps").
     * Experiment switch {@code m7a} on top of {@code SW_CASETREES}.
     *
     * @return the reduct (the caller continues with CORE, as after DELTA), {@code Layout.NONE}: use DELTA, or a signal
     */
    static int rulesCasesOn(Machine m, int v, int d) {
        if ((m.sw & (Op.SW_CASETREES | Op.SW_M7A)) != (Op.SW_CASETREES | Op.SW_M7A) || m.strict) {
            return Layout.NONE;
        }
        if (v <= Layout.HOLE || m.vh[v << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT != Layout.NEU) {
            return Layout.NONE;
        }
        int o = v << Layout.REC_SHIFT;
        if (m.vh[o + Layout.M_UNF] != 0) {
            return Layout.NONE; // unfolded before: DELTA and CORE answer from their memos
        }
        int head = m.vh[o + Layout.A];
        if (head <= Layout.HOLE || m.vh[head << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT != Layout.CONST) {
            return Layout.NONE;
        }
        int ho = head << Layout.REC_SHIFT;
        if (m.vh[ho + Layout.C] < 0) {
            return Layout.NONE; // a head with the wrong number of levels does not unfold (R02 section 10 row 14): DELTA says so
        }
        int blk = blockFor(m, m.vh[ho + Layout.A], Op.ROLE_RULES);
        if (blk == 0 || blk >= SIG) {
            return blk;
        }
        int a = addressOf(m, blk);
        int[] code = m.engine.code.chunkAt(a);
        int base = CodeStore.offsetAt(a);
        int h2 = code[base + Op.BLK_HDR2];
        int n = m.vh[o] & Layout.AUX_MASK;
        int k = arity(h2);
        if (flags(h2) >>> BLKF_SHAPE_SHIFT != Layout.SHAPE_CASESON || n < k) {
            return Layout.NONE;
        }
        int run = m.vh[o + Layout.B];
        int r = enterAt(m, code, base, blk, 0, 0, m.vh[ho + Layout.C], m.rs, run + Layout.RUN_H, k, d + 1);
        return r >= SIG ? m.sig2(r, K_RULES_1, v, k) : k_rules1(m, v, k, r, d);
    }

    static final int K_RULES_1 = Op.K_ACT | 1;

    /** ACT[1](v, k), the continuation of RULES: r = the leaf applied to the fields; the arguments after lamArity follow (ISA 3.6 "leaf"). */
    static int k_rules1(Machine m, int v, int k, int r, int d) {
        int n = (m.vh[v << Layout.REC_SHIFT] & Layout.AUX_MASK) - k;
        if (r == Layout.NONE || n == 0) {
            return r;
        }
        int xb = spineTail(m, v, k, n);
        if (xb >= SIG) {
            return xb;
        }
        int a = Eval.apply(m, r, xb, n, d + 1);
        return a >= SIG ? m.sig1(a, K_RULES_2, xb) : k_rules2(m, xb, a, d);
    }

    /** Copies the arguments {@code k .. k + n} of NEU {@code v} into scratch and claims them; returns the base or FATAL. */
    private static int spineTail(Machine m, int v, int k, int n) {
        if (!m.ensureScratch(n)) {
            return m.fault(MachineErrors.E_MEM);
        }
        int xb = m.xp;
        int run = m.vh[(v << Layout.REC_SHIFT) + Layout.B];
        for (int i = 0; i < n; i++) {
            m.X[xb + i] = Runs.arg(m, run, k + i);
        }
        m.xp = xb + n;
        return xb;
    }

    static final int K_RULES_2 = Op.K_ACT | 2;

    /** ACT[2](xb): releases the scratch of the over-application. */
    static int k_rules2(Machine m, int xb, int r, int d) {
        m.xp = xb;
        return r;
    }

    // ---------------------------------------------------------------------------------------------------
    // TELE (ISA 4.5.4): application inference specialised to a constant
    // ---------------------------------------------------------------------------------------------------

    /**
     * TELE(c) on an inference site (ISA 4.5.4): the argument terms {@code X[xb .. xb + n)} of an
     * application of constant c, under the site's environment {@code (eb, en)} and substitution
     * {@code lsSite}; {@code ls} is the substitution of the head ({@code mkSubst(lparams(c), levels)});
     * {@code mode} is {@code Op.CHECKED} or {@code Op.ONLY}. Checks (CHECKED) and binds the first
     * {@code min(n, arity)} arguments and returns the type of that application: the caller continues with
     * TWALK when {@code n > arity} ({@link #arityOf}). {@code expected}: a type the caller will compare the
     * result with (only for an untabled site with {@code n == arity}), or NONE; if the result pattern
     * matches it (B5), {@code expected} itself is returned and the result is not built.
     * Official: {@code infer_app} (R02 6.5); a mismatch is {@code fail(E_APP_MISMATCH)} at the same argument.
     */
    static int tele(Machine m, int blk, int ls, int xb, int n, int eb, int en, int lsSite, int mode, int expected, int d) {
        CodeStore cs = m.engine.code;
        int a = addressOf(m, blk);
        if (a == 0) {
            return m.fault(MachineErrors.E_INTERNAL);
        }
        int[] code = cs.chunkAt(a);
        int base = CodeStore.offsetAt(a);
        int rb = begin(m, code, base, 0, ls);
        if (rb >= SIG) {
            return rb;
        }
        int[] rs = m.RS;
        int s = rb + arity(code[base + Op.BLK_HDR2]);
        rs[s + S_XB] = xb;
        rs[s + S_N] = n;
        rs[s + S_EB] = eb;
        rs[s + S_EN] = en;
        rs[s + S_LS] = lsSite;
        rs[s + S_MODE] = mode;
        rs[s + S_EXPECT] = expected;
        return go(m, code, base, blk, 0, rb, d);
    }

    /**
     * TELE(c) on argument values (ISA 4.5.3 VTYPE of a NEU with a constant head): the first
     * {@code min(n, arity)} arguments of spine run {@code run} are the telescope variables; nothing is
     * checked; the result is the type of that application.
     */
    static int teleValues(Machine m, int blk, int ls, int run, int n, int d) {
        CodeStore cs = m.engine.code;
        int a = addressOf(m, blk);
        if (a == 0) {
            return m.fault(MachineErrors.E_INTERNAL);
        }
        int[] code = cs.chunkAt(a);
        int base = CodeStore.offsetAt(a);
        int rb = begin(m, code, base, 0, ls);
        if (rb >= SIG) {
            return rb;
        }
        int ar = arity(code[base + Op.BLK_HDR2]);
        int k = n < ar ? n : ar;
        int[] rs = m.RS;
        int from = run + Layout.RUN_H + (m.rs[run] & Layout.RUN_FAR);
        for (int i = 0; i < k; i++) {
            rs[rb + i] = m.rs[from + i];
        }
        int s = rb + ar;
        rs[s + S_N] = n;
        rs[s + S_MODE] = MODE_VALUES;
        rs[s + S_EXPECT] = Layout.NONE;
        return go(m, code, base, blk, k, rb, d);
    }

    /**
     * TARG i (ISA 4.5.4): one argument of the site, in three phases that are instructions of their own so
     * that the state of the activation is its pc (NOTES-M7 D4).
     * <pre>
     * INFER i, Lbind, Lnext   i >= n: continue at the entry that returns the remaining Pi
     *                         ONLY:   g_i = READ ? ARGV(t_i) : HOLE; continue at Lnext
     *                         CHECK:  Tt = INFER(t_i, env, CHECK); continue with the domain code
     * CONV i, D               Tt != D: ER = t_i is `eagerReduce _ _`; CONV(Tt, D) != T: fail(E_APP_MISMATCH)
     * BIND i                  g_i = READ ? ARGV(t_i) : HOLE
     * EXPECT T, n             expected given and the site is saturated and MATCH(T, g, expected): return it
     * </pre>
     *
     * @return the next pc; a signal; {@code ~1}: return the expected type; {@code ~0}: malformed
     */
    private static int targ(Machine m, int[] code, int base, int at, int blk, int pc, int rb, int w, int d) {
        int i = w >>> 16 & 255;
        int s = rb + arity(code[base + Op.BLK_HDR2]);
        switch (w & PH_MASK) {
            case PH_INFER:
                return targInfer(m, code, base, at, blk, pc, rb, w, i, s, d);
            case PH_CONV:
                return targConv(m, code, at, blk, pc, rb, i, s, d);
            case PH_BIND:
                return targBind(m, blk, pc + 1, rb, w, i, s, d);
            default: {
                int[] rs = m.RS;
                int exp = rs[s + S_EXPECT];
                if (exp != Layout.NONE && rs[s + S_N] == s - rb) {
                    int r = Match.matchIn(m, rs, rb, code[at + 2] & PAY, code[at + 1] & PAY, rs[rb - W_LS], exp);
                    if (r >= SIG) {
                        return r;
                    }
                    if (r == Layout.T) {
                        return ~1;
                    }
                }
                return pc + 3;
            }
        }
    }

    /** The saturated site's expected type matched the result pattern: it is the result (ISA 4.5.4, B5). */
    private static int expectedOf(Machine m, int[] code, int base, int rb) {
        int exp = m.RS[rb + arity(code[base + Op.BLK_HDR2]) + S_EXPECT];
        m.rp = rb - HDR;
        return exp;
    }

    private static int targInfer(Machine m, int[] code, int base, int at, int blk, int pc, int rb, int w, int i, int s, int d) {
        int[] rs = m.RS;
        if (i >= rs[s + S_N]) {
            return i == 0 ? ~0 : code[base + Op.BLK_ENTRIES + i];
        }
        int t = m.X[rs[s + S_XB] + i];
        if (rs[s + S_MODE] == Op.CHECKED) {
            int r = Infer.infer(m, t, rs[s + S_EB], rs[s + S_EN], rs[s + S_LS], Op.CHECKED, d + 1);
            if (r >= SIG) {
                return susp(m, r, blk, pc + 3, s - rb + S_T, rb);
            }
            m.RS[s + S_T] = r;
            return pc + 3;
        }
        return targBind(m, blk, code[at + 2] & PAY, rb, w, i, s, d);
    }

    /** Phase BIND (and the whole of TARG in ONLY mode): {@code g_i = READ ? ARGV(t_i) : HOLE}; continue at {@code next}. */
    private static int targBind(Machine m, int blk, int next, int rb, int w, int i, int s, int d) {
        int[] rs = m.RS;
        if ((w & X_READ) == 0) {
            rs[rb + i] = Layout.HOLE;
            return next;
        }
        int r = Eval.argv(m, m.X[rs[s + S_XB] + i], rs[s + S_EB], rs[s + S_EN], rs[s + S_LS], d + 1);
        if (r >= SIG) {
            return susp(m, r, blk, next, i, rb);
        }
        m.RS[rb + i] = r;
        return next;
    }

    private static int targConv(Machine m, int[] code, int at, int blk, int pc, int rb, int i, int s, int d) {
        int dom = val(m, rb, code[at + 1]);
        if (dom >= SIG) {
            return dom;
        }
        int[] rs = m.RS;
        int tt = rs[s + S_T];
        if (tt == dom) {
            m.x7.targSame++;
            return pc + 2;
        }
        m.x7.targConv++;
        int r;
        if (isEagerReduce(m, m.X[rs[s + S_XB] + i])) {
            if (m.erDefers()) {
                // [eager] Op.SW_EAGER: the comparison runs under ER when the plain judgments of the declaration are done
                int q = m.erSite(tt, dom);
                return q >= SIG ? q : pc + 2;
            }
            int saved = m.ER;
            m.enterER(); // M6-F26: eager reduction is entered through the facade's rule (a warm session is restarted)
            r = Conv.conv(m, tt, dom, d + 1);
            if (r >= SIG) {
                return susp(m, m.sig1(r, Op.K_ERF, saved), blk, pc + 2, DST_REQ_APP, rb);
            }
            m.ER = saved;
        } else {
            r = Conv.conv(m, tt, dom, d + 1);
            if (r >= SIG) {
                return susp(m, r, blk, pc + 2, DST_REQ_APP, rb);
            }
        }
        return r == Layout.T ? pc + 2 : m.fail(MachineErrors.E_APP_MISMATCH);
    }

    /** Is the term of the form {@code eagerReduce _ _} (R02 8.9; the reference kernel's {@code isEagerReduce})? */
    static boolean isEagerReduce(Machine m, int t) {
        Terms terms = m.terms;
        if (terms.kind(t) != Kind.APP) {
            return false;
        }
        int f = terms.a(t);
        if (terms.kind(f) != Kind.APP) {
            return false;
        }
        int h = terms.a(f);
        if (terms.kind(h) != Kind.CONST) {
            return false;
        }
        int cid = terms.a(h);
        return cid > 0 && m.specials.which(cid) == Specials.EAGER_REDUCE;
    }

    // ---------------------------------------------------------------------------------------------------
    // pubcheck (ISA 3.3 publication rule, 5.5)
    // ---------------------------------------------------------------------------------------------------

    /**
     * Asserts the publication rule of ISA 3.3 (RECONCILIATION B2) on a complete block
     * {@code b[o .. o + len)}: every T / K operand an export term id, every L / LL operand a base level /
     * level-list id, every D operand a constant row, V operands NONE or HOLE only, R / A operands inside
     * the window, labels inside the code, sub-block ids published; no value ref, run id, overlay id or
     * session level anywhere. Also checks that the instruction stream is well formed.
     *
     * @return 0 if the block obeys the rule, else the (positive) word offset of the first violation
     */
    static int pubcheckSlow(Machine m, int[] b, int o, int len) {
        int h0 = b[o + Op.BLK_HDR0];
        int h2 = b[o + Op.BLK_HDR2];
        int ne = nentry(h2);
        int codeStart = Op.BLK_ENTRIES + ne;
        if (len(h0) != len || len < codeStart || role(h0) == 0 || role(h0) >= Op.ROLE_COUNT) {
            return 1;
        }
        int rows = m.decls.constantCount();
        boolean sub = (flags(h2) & BLKF_SUB) != 0;
        if (sub ? b[o + Op.BLK_ROW] != 0 : b[o + Op.BLK_ROW] <= 0 || b[o + Op.BLK_ROW] > rows) {
            return Op.BLK_ROW;
        }
        CodeStore cs = m.engine.code;
        if ((flags(h2) & BLKF_SUB) != 0 && !exportTerm(m, b[o + Op.BLK_FACTS])) {
            return Op.BLK_FACTS;
        }
        if (role(h0) == Op.ROLE_CONG) {
            return 0; // a plan holds classes, ranks and masks only (ISA 6.1)
        }
        for (int i = 0; i < ne; i++) {
            int e = b[o + Op.BLK_ENTRIES + i];
            if ((e != 0 || role(h0) != Op.ROLE_RULE) && (e < codeStart || e >= len)) {
                return Op.BLK_ENTRIES + i;
            }
        }
        int win = arity(h2) + nreg(h0);
        int levelTop = Integer.MAX_VALUE;
        int listTop = Integer.MAX_VALUE;
        if (m.levels instanceof LevelBinding) {
            levelTop = ((LevelBinding) m.levels).store().baseSize();
            listTop = ((LevelBinding) m.levels).store().baseListCount();
        }
        int pc = codeStart;
        while (pc < len) {
            int w = b[o + pc];
            int op = w >>> 24;
            int n = w >>> 8 & 255;
            if (pc + 1 + n > len || !knownOp(op) || (w >>> 16 & 255) >= Math.max(win, 1) && writes(op)) {
                return pc;
            }
            for (int i = 1; i <= n; i++) {
                int x = b[o + pc + i];
                int p = x & PAY;
                boolean ok;
                switch (x >>> Op.OPND_SHIFT) {
                    case Op.MODE_R:
                    case Op.MODE_A:
                        ok = p < win;
                        break;
                    case Op.MODE_C:
                        ok = p < Layout.NEAR_SLOTS;
                        break;
                    case Op.MODE_K:
                    case Op.MODE_T:
                        ok = exportTerm(m, p);
                        break;
                    case Op.MODE_I:
                        ok = true;
                        break;
                    case Op.MODE_V:
                        ok = p == Layout.NONE || p == Layout.HOLE;
                        break;
                    case Op.MODE_L:
                        ok = p > 0 && p < levelTop;
                        break;
                    case Op.MODE_LL:
                        ok = p < listTop;
                        break;
                    case Op.MODE_D:
                        ok = p > 0 && p <= rows;
                        break;
                    case Op.MODE_B:
                        ok = (op == Op.MKLAM || op == Op.MKPI) && i == 2 ? p == 0 || cs.exists(p) : p >= codeStart && p < len;
                        break;
                    default:
                        ok = false;
                        break;
                }
                if (!ok) {
                    return pc + i;
                }
            }
            pc += 1 + n;
        }
        return 0;
    }

    /** True for an id of the export region that names a node (the only terms a published block may mention). */
    private static boolean exportTerm(Machine m, int t) {
        return t > 0 && t < m.store.terms().exportEnd() && m.terms.kind(t) != Kind.NONE;
    }

    /** The instructions the K1 elaborator emits and the stepper executes. */
    private static boolean knownOp(int op) {
        switch (op) {
            case Op.MKAPP:
            case Op.KCONST:
            case Op.KSORT:
            case Op.KLIT:
            case Op.KVAL:
            case Op.MKLAM:
            case Op.MKPI:
            case Op.MKSUSP:
            case Op.MKPROJ:
            case Op.MOV:
            case Op.PEEK:
            case Op.NSUCC:
            case Op.FIELDS:
            case Op.JMP:
            case Op.BKIND:
            case Op.BEQ:
            case Op.BNE:
            case Op.BUNREAD:
            case Op.SWITCH:
            case Op.MATCH:
            case Op.RET:
            case Op.APPLY:
            case Op.TAILAPPLY:
            case Op.WHNF:
            case Op.FORCE:
            case Op.TARG:
            case Op.TRET:
                return true;
            default:
                return false;
        }
    }

    /** True if the d byte of the instruction names a window word that is written. */
    private static boolean writes(int op) {
        switch (op) {
            case Op.JMP:
            case Op.BKIND:
            case Op.BEQ:
            case Op.BNE:
            case Op.SWITCH:
            case Op.MATCH:
            case Op.RET:
            case Op.TAILAPPLY:
            case Op.TRET:
                return false;
            default:
                return true;
        }
    }
}
