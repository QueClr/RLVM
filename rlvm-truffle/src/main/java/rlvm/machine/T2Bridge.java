package rlvm.machine;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import rlvm.api.Engine;
import rlvm.api.MachineErrors;
import rlvm.api.Session;

/**
 * The bridge of the T2 tier into the machine (ISA 8.4; the hook {@link Tier}, level 3: "own code per
 * instruction ... a tier that ships a bridge class in package {@code rlvm.machine}"). This class is part
 * of the module {@code rlvm-truffle} (owner: T2) and lives in package {@code rlvm.machine} because the
 * operations of the machine and the fields of {@link Machine} are package-private. It has two kinds of
 * members and no semantics of its own:
 *
 * <ul>
 *   <li><b>accessors</b> without annotation: a field or array read / write of the machine, a few
 *       bytecodes. Partial evaluation inlines them into the compiled block (register traffic, the kind
 *       test of an operand, the scratch pointer);
 *   <li><b>operations</b> behind {@code @TruffleBoundary}: a call of the SAME operation the stepper's
 *       instruction body calls ({@code Values.mkApp}, {@code Eval.apply}, ...; one semantics, ISA 8.2
 *       "one operation library"). The boundary is where partial evaluation stops: {@code rlvm-machine}
 *       has no Truffle dependency and cannot draw it itself, and without it the whole machine (recursion
 *       included) would be inlined into every compiled block. An exception that crosses a boundary is
 *       passed on, not turned into a deoptimisation (ISA 8.4 "no-deopt policy").
 * </ul>
 *
 * The three composite operations ({@link #closure}, {@link #ctor}, {@link #kvalHit}) are the bodies of
 * {@code Code.mkClosure}, the selection of {@code Code.switchOn} and the cache probe of {@code Code.kval}
 * without their operand decoding; the tier-equivalence tests of {@code rlvm-truffle} compare value ids,
 * heaps and verdicts with the stepper's on every corpus.
 */
public final class T2Bridge {
    private T2Bridge() {}

    // ---------------------------------------------------------------------------------------------------
    // Constants of the hook that are package-private in the machine
    // ---------------------------------------------------------------------------------------------------

    public static final int DST_RET = Code.DST_RET;
    public static final int HDR = Code.HDR;
    public static final int TELE_SITE = Code.TELE_SITE;
    public static final int X_LPARAM = Code.X_LPARAM;
    public static final int X_LET = Code.X_LET;
    public static final int KV_ARGV = Code.KV_ARGV;
    public static final int KV_LPARAM = Code.KV_LPARAM;
    public static final int PH_MASK = Code.PH_MASK;
    public static final int PH_INFER = Code.PH_INFER;
    public static final int BLKF_SUB = Code.BLKF_SUB;
    public static final int NEUTRAL_KINDS = Code.NEUTRAL_KINDS;

    public static int blockLen(int hdr0) {
        return Code.len(hdr0);
    }

    public static int blockNreg(int hdr0) {
        return Code.nreg(hdr0);
    }

    public static int blockRole(int hdr0) {
        return Code.role(hdr0);
    }

    public static int blockArity(int hdr2) {
        return Code.arity(hdr2);
    }

    public static int blockFlags(int hdr2) {
        return Code.flags(hdr2);
    }

    public static int blockEntries(int hdr2) {
        return Code.nentry(hdr2);
    }

    // ---------------------------------------------------------------------------------------------------
    // Accessors (inlined by partial evaluation)
    // ---------------------------------------------------------------------------------------------------

    /** Window word {@code i} of the activation at {@code rb}. */
    public static int reg(Machine m, int rb, int i) {
        return m.RS[rb + i];
    }

    /**
     * The register stack / the scratch buffer, for a store by the caller ({@code regs(m)[rb + i] = v}). There
     * is deliberately NO setter method here for compiled code: a method whose whole body is one store is
     * inlined before the analysis of a native image and its deoptimisation target is then missing ("reachable
     * during compilation, but was not seen during Bytecode parsing", GraalVM CE 25.5). The scratch pointer and
     * the register stack pointer are therefore written behind the operations' boundaries ({@link #mkApp},
     * {@link #apply}, {@link #closure}) and by the host-side caller ({@code T2Tier.run}: {@link #pop}).
     */
    public static int[] regs(Machine m) {
        return m.RS;
    }

    public static int[] scratch(Machine m) {
        return m.X;
    }

    /** The captured frame run of the activation. */
    public static int cap(Machine m, int rb) {
        return m.RS[rb - Code.W_CAP];
    }

    /** The level substitution of the activation. */
    public static int ls(Machine m, int rb) {
        return m.RS[rb - Code.W_LS];
    }

    /** Slot {@code i} of captured frame run {@code cap} (operand mode C). */
    public static int captured(Machine m, int cap, int i) {
        return m.rs[cap + Layout.RUN_H + i];
    }

    /** Word {@code i} of the run slab (after {@link #lastArgs}). */
    public static int runWord(Machine m, int i) {
        return m.rs[i];
    }

    /** The kind of a record ref ({@code v > Layout.HOLE}). */
    public static int kindOfRecord(Machine m, int v) {
        return m.vh[v << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT;
    }

    /** The scratch pointer. */
    public static int xp(Machine m) {
        return m.xp;
    }

    /** True if {@code n} more scratch words fit without growth. */
    public static boolean scratchFits(Machine m, int n) {
        return m.xp + n <= m.X.length;
    }

    /** RET: pops the window. Called by the host-side caller of compiled code, never from compiled code. */
    public static void pop(Machine m, int rb) {
        m.rp = rb - Code.HDR;
    }

    /** After {@link #step} answered {@code Tier.DONE}: the result or signal of the activation. */
    public static int stepResult(Machine m) {
        return m.x7.tierResult;
    }

    public static boolean stats(Machine m) {
        return m.stats;
    }

    public static MachineEngine engine(Machine m) {
        return m.engine;
    }

    // ---------------------------------------------------------------------------------------------------
    // Operations (the boundary of partial evaluation)
    // ---------------------------------------------------------------------------------------------------

    /** {@code Machine.ensureScratch}: false = limit reached. */
    @TruffleBoundary(allowInlining = false, transferToInterpreterOnException = false)
    public static boolean growScratch(Machine m, int n) {
        return m.ensureScratch(n);
    }

    @TruffleBoundary(allowInlining = false, transferToInterpreterOnException = false)
    public static int fault(Machine m, int code) {
        return m.fault(code);
    }

    @TruffleBoundary(allowInlining = false, transferToInterpreterOnException = false)
    public static int peek(Machine m, int v) {
        return Values.peek(m, v);
    }

    @TruffleBoundary(allowInlining = false, transferToInterpreterOnException = false)
    public static int mkApp(Machine m, int h, int xb, int n) {
        m.xp = xb + n; // the operands are claimed while the operation runs (Code.operands) ...
        int r = Values.mkApp(m, h, xb, n);
        m.xp = xb; // ... and released (Code.mkApp)
        return r;
    }

    @TruffleBoundary(allowInlining = false, transferToInterpreterOnException = false)
    public static int mkConst(Machine m, int row, int levelList) {
        return Values.mkConst(m, row, levelList);
    }

    /** KCONST with a level parameter: {@code CHEAD(D, LSUB(LL, ls))}. */
    @TruffleBoundary(allowInlining = false, transferToInterpreterOnException = false)
    public static int mkConstSubst(Machine m, int row, int levelList, int ls) {
        return Values.mkConst(m, row, Levels.lsubList(m, levelList, ls));
    }

    @TruffleBoundary(allowInlining = false, transferToInterpreterOnException = false)
    public static int mkSort(Machine m, int level) {
        return Values.mkSort(m, level);
    }

    /** KSORT with a level parameter: {@code SORT(LSUB(L, ls))}. */
    @TruffleBoundary(allowInlining = false, transferToInterpreterOnException = false)
    public static int mkSortSubst(Machine m, int level, int ls) {
        return Values.mkSort(m, Levels.lsub(m, level, ls));
    }

    @TruffleBoundary(allowInlining = false, transferToInterpreterOnException = false)
    public static int klit(Machine m, int term) {
        return Lits.klit(m, term);
    }

    @TruffleBoundary(allowInlining = false, transferToInterpreterOnException = false)
    public static int mkProj(Machine m, int structRow, int field, int s) {
        return Values.mkProj(m, structRow, field, s);
    }

    @TruffleBoundary(allowInlining = false, transferToInterpreterOnException = false)
    public static int nsucc(Machine m, int v) {
        return Values.nsucc(m, v);
    }

    /**
     * The body of {@code Code.mkClosure} behind its operand decoding: the closure ({@code op} MKLAM / MKPI)
     * or suspension (MKSUSP) of term {@code t} over the frame of the {@code nc} raw bindings in scratch at
     * {@code xb} (claimed and released here), with substitution {@code ls} (0 if the term has no
     * level parameter); the sub-block {@code b} of a closure goes into its CODE memo.
     */
    @TruffleBoundary(allowInlining = false, transferToInterpreterOnException = false)
    public static int closure(Machine m, int op, int t, int b, int xb, int nc, int ls, boolean let) {
        int frame = Layout.RUN_EMPTY;
        if (nc > 0) {
            m.xp = xb + nc;
            frame = Runs.frame(m, xb, nc, Layout.RUN_EMPTY);
            m.xp = xb;
            if (frame >= Op.SIG) {
                return frame;
            }
        }
        if (op == Op.MKSUSP) {
            return Values.mkSusp(m, t, frame, ls, let);
        }
        int v = op == Op.MKLAM ? Values.mkLam(m, t, frame, ls) : Values.mkPi(m, t, frame, ls);
        if (v < Op.SIG && b != 0 && Values.memo(m, v, Layout.M_CODE - Layout.M0) == 0) {
            Values.setMemo(m, v, Layout.M_CODE - Layout.M0, b);
        }
        return v;
    }

    /**
     * The selection of {@code Code.switchOn}: the constructor index of {@code v} as a constructor of
     * inductive {@code ind} (with the Nat literal and NSUC views), or -1.
     */
    @TruffleBoundary(allowInlining = false, transferToInterpreterOnException = false)
    public static int ctor(Machine m, int v, int ind) {
        if (v < 0) {
            return Code.isNat(m, ind) ? (v == Layout.IMM ? 0 : 1) : -1;
        }
        return v > Layout.HOLE ? Code.ctorIndex(m, v, ind) : -1;
    }

    /**
     * The NEU case of {@code Code.fields}: the index in the run slab of the first of the last {@code n}
     * arguments of constructor application {@code v}, or -1 if {@code v} is not a NEU with at least n arguments
     * (a Nat literal, an NSUC cell, anything malformed: the stepper's body decides, through {@link #step}).
     */
    @TruffleBoundary(allowInlining = false, transferToInterpreterOnException = false)
    public static int lastArgs(Machine m, int v, int n) {
        if (v <= Layout.HOLE) {
            return -1;
        }
        int o = v << Layout.REC_SHIFT;
        int[] vh = m.vh;
        if (vh[o] >>> Layout.KIND_SHIFT != Layout.NEU) {
            return -1;
        }
        int len = vh[o] & Layout.AUX_MASK;
        if (len < n) {
            return -1;
        }
        int run = vh[o + Layout.B];
        return run + Layout.RUN_H + (m.rs[run] & Layout.RUN_FAR) + len - n;
    }

    /**
     * The cache probe of {@code Code.kval}: the value of the closed term of the KVAL at {@code pc} of block
     * {@code blk} under key {@code key}, or {@code Op.MISS} (the instruction then runs through {@link #step}).
     */
    @TruffleBoundary(allowInlining = false, transferToInterpreterOnException = false)
    public static int kvalHit(Machine m, int blk, int pc, int key) {
        if (pc >= 4096) {
            return Op.MISS;
        }
        int site = blk << 12 | pc;
        return Code.icGet(m, (Hash.cell(site, key) & (1 << Layout.IC_LOG2) - 1) << Layout.CELL_SHIFT, site, key);
    }

    @TruffleBoundary(allowInlining = false, transferToInterpreterOnException = false)
    public static int apply(Machine m, int f, int xb, int n, int d) {
        m.xp = xb + n; // the arguments are scratch of the activation (Code.operands) ...
        int r = Eval.apply(m, f, xb, n, d);
        if (r < Op.SIG) {
            m.xp = xb; // ... released on a result; a suspended activation keeps them (Code.apply)
        }
        return r;
    }

    @TruffleBoundary(allowInlining = false, transferToInterpreterOnException = false)
    public static int whnf(Machine m, int s, int d) {
        return Reduce.whnf(m, s, d);
    }

    @TruffleBoundary(allowInlining = false, transferToInterpreterOnException = false)
    public static int force(Machine m, int s, int d) {
        return Eval.force(m, s, d);
    }

    // ---------------------------------------------------------------------------------------------------
    // The same operations WITHOUT a boundary (experiment rlvm.t2.inline, NOTES-T2 "probes inlined"): partial
    // evaluation continues into the machine's operation, so the hash-consing probe is compiled into the block
    // with the operand count as a constant. Used only when the tier is built / started with rlvm.t2.inline > 0.
    // ---------------------------------------------------------------------------------------------------

    public static int peekInline(Machine m, int v) {
        return Values.peek(m, v);
    }

    public static int ctorInline(Machine m, int v, int ind) {
        if (v < 0) {
            return Code.isNat(m, ind) ? (v == Layout.IMM ? 0 : 1) : -1;
        }
        return v > Layout.HOLE ? Code.ctorIndex(m, v, ind) : -1;
    }

    /** {@link #lastArgs} without a boundary: array reads only. */
    public static int lastArgsInline(Machine m, int v, int n) {
        if (v <= Layout.HOLE) {
            return -1;
        }
        int o = v << Layout.REC_SHIFT;
        int[] vh = m.vh;
        if (vh[o] >>> Layout.KIND_SHIFT != Layout.NEU) {
            return -1;
        }
        int len = vh[o] & Layout.AUX_MASK;
        if (len < n) {
            return -1;
        }
        int run = vh[o + Layout.B];
        return run + Layout.RUN_H + (m.rs[run] & Layout.RUN_FAR) + len - n;
    }

    /** {@link #kvalHit} without a boundary: the probe of one cell of the inline cache (array reads; PEEK of the value). */
    public static int kvalHitInline(Machine m, int blk, int pc, int key) {
        if (pc >= 4096) {
            return Op.MISS;
        }
        int site = blk << 12 | pc;
        return Code.icGet(m, (Hash.cell(site, key) & (1 << Layout.IC_LOG2) - 1) << Layout.CELL_SHIFT, site, key);
    }

    /**
     * The hit of {@code Code.kConst} for a constant with level parameters: the value in the inline cache cell of
     * site {@code (blk, pc)} under substitution {@code ls}, or {@code Op.MISS} ({@link #kConstFill} then does what
     * the stepper does on a miss). Array reads only.
     */
    public static int kConstHit(Machine m, int blk, int pc, int ls) {
        if (pc >= 4096) {
            return Op.MISS;
        }
        int site = blk << 12 | pc;
        int c = (Hash.cell(site, ls) & (1 << Layout.IC_LOG2) - 1) << Layout.CELL_SHIFT;
        int[] ic = m.ic;
        if (ic[c + Layout.IC_SITE] == site && ic[c + Layout.IC_EPOCH] == m.epoch && ic[c + Layout.IC_KEY] == ls) {
            return ic[c + Layout.IC_VALUE];
        }
        return Op.MISS;
    }

    /** The miss of {@code Code.kConst}: {@code CHEAD(row, LSUB(list, ls))}, and the fill of the site's cache cell. */
    @TruffleBoundary(allowInlining = false, transferToInterpreterOnException = false)
    public static int kConstFill(Machine m, int row, int list, int blk, int pc, int ls) {
        int v = Values.mkConst(m, row, Levels.lsubList(m, list, ls));
        if (v >= Op.SIG || pc >= 4096) {
            return v;
        }
        int site = blk << 12 | pc;
        int c = (Hash.cell(site, ls) & (1 << Layout.IC_LOG2) - 1) << Layout.CELL_SHIFT;
        int[] ic = m.ic;
        ic[c + Layout.IC_SITE] = site;
        ic[c + Layout.IC_EPOCH] = m.epoch;
        ic[c + Layout.IC_KEY] = ls;
        ic[c + Layout.IC_VALUE] = v;
        return v;
    }

    /**
     * The hit of {@code Values.mkConst} (its cache of CONST heads in front of the record table): the record of
     * {@code CHEAD(row, levelList)} if the cell of the pair holds it for this session, else {@code Op.MISS}
     * ({@link #mkConst} then). A cell is identified by its content, so a hit is the record whatever cell index
     * {@code Values} uses; the index formula is the one of {@code Values.mkConst} so that hits are the rule.
     */
    public static int mkConstHit(Machine m, int row, int levelList) {
        int[] c = m.x1.chead;
        int i = ((row * 0x9E3779B1 ^ levelList * 0x85EBCA6B) >>> (32 - Values.Ext.CHEAD_LOG2)) << Layout.CELL_SHIFT;
        if (c[i] == row && c[i + 1] == levelList && c[i + 2] == m.epoch) {
            return c[i + 3];
        }
        return Op.MISS;
    }

    // ---------------------------------------------------------------------------------------------------
    // Run-ahead (NOTES-T3 section 4): what a compiled block needs to PREDICT the intern-table slots its coming
    // instructions will probe, so that it can load them early and the cache misses overlap. Reads only; a wrong
    // prediction loads a slot nobody needs and changes nothing. The hash formulas are those of Values.neu /
    // Runs.internRun / Values.closureProbe; rlvm.t2.aheadCheck counts how often the predicted record is the one
    // the operation then returns.
    // ---------------------------------------------------------------------------------------------------

    /** The next record id / the next run offset of the session: what a NEW record / run will get. */
    public static int vp(Machine m) {
        return m.vp;
    }

    public static int rsp(Machine m) {
        return m.rsp;
    }

    /** Word 0 / a / b of record {@code v}. */
    public static int w0(Machine m, int v) {
        return m.vh[v << Layout.REC_SHIFT];
    }

    public static int recA(Machine m, int v) {
        return m.vh[(v << Layout.REC_SHIFT) + Layout.A];
    }

    public static int recB(Machine m, int v) {
        return m.vh[(v << Layout.REC_SHIFT) + Layout.B];
    }

    /** The word of the record table / the run table where the probe sequence of {@code hash} starts. */
    public static int vtabWord(Machine m, int hash) {
        int[] t = m.vtab;
        return t[hash & t.length - 1];
    }

    public static int rtabWord(Machine m, int hash) {
        int[] t = m.rtab;
        return t[hash & t.length - 1];
    }

    /** {@code Values.argHash}: the contribution of argument {@code v} at position {@code j} to the hash of a NEU. */
    public static int argHash(int v, int j) {
        int t = (v + j * 0x9E3779B1) * 0x85EBCA6B;
        return t ^ (t >>> 15);
    }

    /** {@code Values.neuHash}: the hash of a NEU from its head and the sum of the {@link #argHash}es. */
    public static int neuHash(int head, int sum) {
        return Hash.fin(sum + head * 0xC2B2AE35 + (Layout.NEU << Layout.KIND_SHIFT));
    }

    /** The sum of the {@link #argHash}es of the {@code pn} arguments a NEU already has (spine run {@code pre}). */
    public static int prefixHash(Machine m, int pre, int pn) {
        int[] rs = m.rs;
        int pb = pre + Layout.RUN_H;
        int h = 0;
        for (int i = 0; i < pn; i++) {
            h += argHash(rs[pb + i], i);
        }
        return h;
    }

    /** {@code Runs.slotHash} / {@code Runs.runHash} of a FRAME run without REST. */
    public static int slotHash(int v, int i) {
        return Runs.slotHash(v, i);
    }

    public static int frameHash(int sum) {
        return Runs.runHash(sum, Layout.RUN_FRAME, 0);
    }

    /** The hash of the record {@code (w0, a, b, c)} of every kind but NEU ({@code Hash.record}). */
    public static int recordHash(int w0, int a, int b, int c) {
        return Hash.record(w0, a, b, c);
    }

    public static final int NATOP_MASK = Layout.CONST_NATOP_MASK;
    public static final int NATOP_SUCC = rlvm.nat.NatOp.SUCC;

    public static int mkAppInline(Machine m, int h, int xb, int n) {
        m.xp = xb + n;
        int r = Values.mkApp(m, h, xb, n);
        m.xp = xb;
        return r;
    }

    public static int mkConstInline(Machine m, int row, int levelList) {
        return Values.mkConst(m, row, levelList);
    }

    /** {@code Tier.suspend}: the frame ACT [blk, pc, dst, ..] of the activation for signal {@code sig}. */
    @TruffleBoundary(allowInlining = false, transferToInterpreterOnException = false)
    public static int suspend(Machine m, int sig, int blk, int pc, int dst, int rb) {
        return Tier.suspend(m, sig, blk, pc, dst, rb);
    }

    /** {@code Tier.step}: one instruction through the stepper's own instruction body, on the window. */
    @TruffleBoundary(allowInlining = false, transferToInterpreterOnException = false)
    public static int step(Machine m, int[] code, int blk, int pc, int rb, int d) {
        return Tier.step(m, code, blk, pc, rb, d);
    }

    /** {@code Tier.interpret}: the stepper from {@code pc}. */
    @TruffleBoundary(allowInlining = false, transferToInterpreterOnException = false)
    public static int interpret(Machine m, int[] code, int blk, int pc, int rb, int d) {
        return Tier.interpret(m, code, blk, pc, rb, d);
    }

    // ---------------------------------------------------------------------------------------------------
    // For the tier's bookkeeping and tests (never called from compiled code)
    // ---------------------------------------------------------------------------------------------------

    /** The heat accounting of the engine of {@code m} (switched on by the call), or null. */
    public static Tier.Heat heat(Machine m) {
        return m.engine == null ? null : Tier.heat(m.engine);
    }

    /** A copy of the words of block {@code blk} of machine engine {@code engine} ({@code Tier.block} without a machine), or null. */
    public static int[] blockWords(Object engine, int blk) {
        CodeStore cs = ((MachineEngine) engine).code;
        int a = cs.address(blk);
        if (a == 0) {
            return null;
        }
        int[] c = cs.chunkAt(a);
        int o = CodeStore.offsetAt(a);
        return java.util.Arrays.copyOfRange(c, o, o + Code.len(c[o + Op.BLK_HDR0]));
    }

    /** Number of blocks machine engine {@code engine} has published so far. */
    public static int blockCount(Object engine) {
        return ((MachineEngine) engine).code.blockCount();
    }

    /** One line about block {@code blk} of machine engine {@code engine} (as {@code Tier.describe}). */
    public static String describeBlock(Object engine, int blk) {
        CodeStore cs = ((MachineEngine) engine).code;
        int a = cs.address(blk);
        if (a == 0) {
            return "block " + blk + ": none";
        }
        int[] c = cs.chunkAt(a);
        int o = CodeStore.offsetAt(a);
        int h0 = c[o + Op.BLK_HDR0];
        int h2 = c[o + Op.BLK_HDR2];
        int row = c[o + Op.BLK_ROW];
        String[] roles = {"?", "TELE", "BODY", "RULE", "FIELDS", "CONG", "RULES"};
        String who = row > 0 ? cs.store().names().toString(cs.store().decls().name(row)) : "sub-block of term " + c[o + Op.BLK_FACTS];
        return "block " + blk + " " + roles[Code.role(h0) < roles.length ? Code.role(h0) : 0] + "(" + who + ") arity " + Code.arity(h2)
                + " nreg " + Code.nreg(h0) + " words " + Code.len(h0) + ((Code.flags(h2) & Op.BLKF_LEAF) != 0 ? " LEAF" : "");
    }

    /** True if machine engine {@code engine} is strict (no tier, ISA 14.2). */
    public static boolean strict(Object engine) {
        return ((MachineEngine) engine).config.strict;
    }

    /** The machine of a machine session, or null. */
    public static Machine machineOf(Session s) {
        return s instanceof MachineSession ? ((MachineSession) s).m : null;
    }

    /** True if {@code e} is a machine engine. */
    public static boolean isMachine(Engine e) {
        return e instanceof MachineEngine;
    }

    /** Ticks of the machine so far in this declaration's budget (statistics, tests). */
    public static long ticks(Machine m) {
        return m.ticks();
    }

    /**
     * A digest of everything the session of {@code m} has interned: every record word (identity and memo
     * words) and every run word, with the fill pointers. Two machines that made the same values in the
     * same order, with the same memo contents, have the same digest (tier-equivalence tests).
     */
    public static long digest(Machine m) {
        long h = 0x9E3779B97F4A7C15L ^ ((long) m.vp << 32 | m.rsp & 0xFFFFFFFFL);
        int[] vh = m.vh;
        int n = m.vp << Layout.REC_SHIFT;
        for (int i = Layout.FIRST_RECORD << Layout.REC_SHIFT; i < n; i++) {
            h = (h ^ vh[i]) * 0xFF51AFD7ED558CCDL;
            h ^= h >>> 29;
        }
        int[] rs = m.rs;
        n = m.rsp;
        for (int i = Layout.FIRST_RUN; i < n; i++) {
            h = (h ^ rs[i]) * 0xC4CEB9FE1A85EC53L;
            h ^= h >>> 31;
        }
        return h;
    }

    /** As {@link #digest} over the identity words of the records only (kind, flags, a, b, c) and the runs. */
    public static long identityDigest(Machine m) {
        long h = 0x9E3779B97F4A7C15L ^ ((long) m.vp << 32 | m.rsp & 0xFFFFFFFFL);
        int[] vh = m.vh;
        int n = m.vp;
        for (int v = Layout.FIRST_RECORD; v < n; v++) {
            int o = v << Layout.REC_SHIFT;
            for (int k = 0; k < Layout.M0; k++) {
                h = (h ^ vh[o + k]) * 0xFF51AFD7ED558CCDL;
                h ^= h >>> 29;
            }
        }
        int[] rs = m.rs;
        n = m.rsp;
        for (int i = Layout.FIRST_RUN; i < n; i++) {
            h = (h ^ rs[i]) * 0xC4CEB9FE1A85EC53L;
            h ^= h >>> 31;
        }
        return h;
    }

    /** Records made / run words made / frames written by the machine so far (statistics, tests). */
    public static long recordsMade(Machine m) {
        return m.recordsMade;
    }

    public static long runWordsMade(Machine m) {
        return m.runWordsMade;
    }

    public static long framesWritten(Machine m) {
        return m.framesWritten;
    }

    /** Activations of code blocks entered by the machine so far (statistics, tests). */
    public static long entered(Machine m) {
        return m.x7.entered;
    }

    public static int recordCount(Machine m) {
        return m.vp;
    }

    public static int runWords(Machine m) {
        return m.rsp;
    }

    public static void hostDepth(Machine m, int d) {
        m.hostDepth = d;
    }

    public static int errorHole() {
        return MachineErrors.E_HOLE;
    }

    /** The listing of published block {@code blk} as the machine prints it (reports, diagnosis). */
    public static String listing(Machine m, int blk) {
        return CodeShow.published(m, blk, false);
    }
}
