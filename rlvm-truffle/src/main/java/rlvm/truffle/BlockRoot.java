package rlvm.truffle;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.ExplodeLoop.LoopExplosionKind;
import com.oracle.truffle.api.nodes.RootNode;
import java.util.BitSet;
import rlvm.api.MachineErrors;
import rlvm.machine.Machine;
import rlvm.machine.Op;
import rlvm.machine.T2Bridge;

/**
 * The Truffle root of one promoted code block (ISA 8.4): one node per instruction, the registers in
 * static {@code int} frame slots, the worker's machine an argument (never a node field: a root is
 * shared by all workers).
 *
 * <h2>Calling convention</h2>
 * The one argument is the {@link T2Tier} of the calling machine; it carries the entry registers of
 * compiled code ({@code entry}, {@code rb}, {@code d}) and receives the result ({@code result}), so that
 * entering a root allocates nothing (no argument array, no boxed integers). {@code execute} returns null.
 *
 * <h2>Entries and the suspension / resume protocol (ISA 8.4)</h2>
 * A root has one entry per entry of the block's entry table and one per resume point (the instruction
 * after a class-j instruction). An entry reads the slot registers that are live there from the
 * machine's window; a class-j instruction whose judgment suspends writes the slot registers that are
 * live at its resume point back to the window, writes the frame ACT [blk, pc, dst, ..] and returns the
 * signal; the driver later stores the awaited result into the window and offers the activation again at
 * the resume pc ({@code Code.resume} / {@code Code.run} / {@code Tier.run}), which is an entry of this
 * root - or, if the root was built without resume entries, an activation the stepper continues: the
 * window is the complete state either way.
 *
 * <h2>The loop</h2>
 * {@link #run} is the bytecode-interpreter pattern: a loop over the instruction index that partial
 * evaluation unrolls with merging ({@code MERGE_EXPLODE}); the index is a constant at every point, so
 * the loop disappears and the block's control flow graph (branches, switch arms, loops of the block)
 * is the control flow graph of the compiled code.
 */
final class BlockRoot extends RootNode {

    /** The pseudo instruction indexes of the entry dispatch and of the exit. */
    private static final int START = -1;
    private static final int END = -2;

    final T2Engine owner;
    final int blk;
    final String name;
    final int instructions;
    @CompilationFinal(dimensions = 1) private final int[] code;
    @Children private final Instr[] instr;
    @CompilationFinal(dimensions = 1) private final byte[] kind;
    /** Per instruction: it calls a judgment (class j). */
    @CompilationFinal(dimensions = 1) private final boolean[] classJ;
    @CompilationFinal(dimensions = 2) private final int[][] succ;
    @CompilationFinal(dimensions = 2) private final int[][] succPc;
    /** Entry k starts at instruction {@code entryIdx[k]} after loading the slot registers {@code entryLoad[k]}. */
    @CompilationFinal(dimensions = 1) private final int[] entryIdx;
    @CompilationFinal(dimensions = 2) private final int[][] entryLoad;
    /** The pc of entry k (read by {@link T2Tier#run}, not by compiled code). */
    final int[] entryPcs;
    private final int capSlot;
    private final int lsSlot;
    private final boolean usesCap;
    private final boolean usesLs;
    private final boolean windowMode;

    // Run-ahead (NOTES-T3 section 4): before a stretch of value construction the block predicts the records and
    // frames its coming instructions will intern and loads their table slots, so that the misses overlap.
    private static final byte A_STOP = 0;
    private static final byte A_SEQ = 1;
    private static final byte A_JMP = 2;
    private static final byte A_COND = 3;
    private static final byte A_MULTI = 4;
    /** Per instruction: how the run-ahead continues after it. */
    @CompilationFinal(dimensions = 1) private final byte[] akind;
    /** Per instruction: a run-ahead starts here (an entry, or the instruction after one that called a judgment). */
    @CompilationFinal(dimensions = 1) private final boolean[] aheadAt;
    /** Per instruction with {@link #aheadAt}: the registers that are live there (copied into the predicted registers). */
    @CompilationFinal(dimensions = 2) private final int[][] aheadLive;
    private final int sbase;
    private final int slots;
    /** Number of points of this root where a run-ahead starts (report). */
    final int aheadPoints;

    /** Compilation bookkeeping (promoter and compiler threads). */
    volatile long compileStartCpu;
    volatile T2Engine.Rec rec;
    volatile com.oracle.truffle.runtime.OptimizedCallTarget target;

    int codeLength() {
        return code.length;
    }

    /** What the node constructors need. */
    static final class Build {
        final Decoded d;
        final int capSlot;
        final int lsSlot;
        /** First of the frame slots of the run-ahead: the predicted registers, then nine words of its state. */
        final int sbase;
        final boolean exact;
        final boolean hashOk;

        Build(Decoded d, Settings settings) {
            this.d = d;
            this.capSlot = d.win;
            this.lsSlot = d.win + 1;
            this.sbase = d.win + 2;
            this.exact = settings.aheadExact;
            this.hashOk = settings.hashOk;
        }

        /** The slot registers an instruction writes back when it suspends: live after it, not written by it. */
        int[] flushOnSusp(Decoded.Insn in) {
            BitSet s = (BitSet) in.liveOut.clone();
            for (int r : in.defs) {
                s.clear(r);
            }
            return Decoded.list(s);
        }
    }

    BlockRoot(RlvmLanguage language, T2Engine owner, Decoded d, Settings settings, String name) {
        super(language, descriptor(d));
        this.owner = owner;
        this.blk = d.blk;
        this.name = name;
        this.code = d.code;
        this.instructions = d.insn.length;
        Build b = new Build(d, settings);
        capSlot = b.capSlot;
        lsSlot = b.lsSlot;
        usesCap = d.usesCap;
        usesLs = d.usesLs;
        boolean all = true;
        for (boolean p : d.pinned) {
            all &= p;
        }
        windowMode = all;
        int n = d.insn.length;
        instr = new Instr[n];
        kind = new byte[n];
        classJ = new boolean[n];
        succ = new int[n][];
        succPc = new int[n][];
        for (int i = 0; i < n; i++) {
            Decoded.Insn in = d.insn[i];
            kind[i] = in.kind;
            classJ[i] = in.classJ;
            succ[i] = in.succ;
            succPc[i] = in.succPc;
            instr[i] = node(b, in, settings.nodes);
            if (instr[i] instanceof Instr.Step && in.kind != Decoded.STEP && in.kind != Decoded.JMP && !(instr[i] instanceof Instr.KVal) && !(instr[i] instanceof Instr.Fields)) {
                kind[i] = Decoded.STEP; // an instruction that has a node of its own, executed by the stepper's body
            }
        }
        entryPcs = d.entryPoints(settings.resume);
        entryIdx = new int[entryPcs.length];
        entryLoad = new int[entryPcs.length][];
        for (int k = 0; k < entryPcs.length; k++) {
            entryIdx[k] = d.indexOfPc[entryPcs[k]];
            entryLoad[k] = Decoded.list(d.insn[entryIdx[k]].liveIn);
        }
        sbase = b.sbase;
        slots = 2 * d.win + 11;
        akind = new byte[n];
        aheadAt = new boolean[n];
        aheadLive = new int[n][];
        int points = 0;
        boolean anyPinned = false;
        for (boolean p : d.pinned) {
            anyPinned |= p;
        }
        if (settings.ahead > 0 && !anyPinned && n <= settings.aheadMaxInstr) {
            for (int i = 0; i < n; i++) {
                akind[i] = aheadKind(instr[i], kind[i], succ[i], i);
            }
            // where a run-ahead starts: the entries of the block, and the instruction after every instruction that
            // called a judgment (the judgment made records of its own: predictions restart from the stores as they are)
            int[] weight = new int[n];
            java.util.Arrays.fill(weight, -1);
            boolean[] start = new boolean[n];
            for (int k = 0; k < entryIdx.length; k++) {
                start[entryIdx[k]] = true;
            }
            for (int i = 0; i < n; i++) {
                if (d.insn[i].classJ && kind[i] == Decoded.SEQ && succ[i].length > 0 && succ[i][0] > i) {
                    start[succ[i][0]] = true;
                }
            }
            for (int i = 0; i < n; i++) {
                if (start[i] && probes(i, weight) >= settings.ahead) {
                    aheadAt[i] = true;
                    aheadLive[i] = Decoded.list(d.insn[i].liveIn);
                    points++;
                }
            }
        }
        aheadPoints = points;
    }

    /** How the run-ahead continues after instruction {@code i} (never along a backward jump: it must end). */
    private static byte aheadKind(Instr node, byte kind, int[] succ, int i) {
        for (int t : succ) {
            if (t <= i) {
                return A_STOP;
            }
        }
        if (kind == Decoded.JMP) {
            return succ.length == 1 ? A_JMP : A_STOP;
        }
        if (node instanceof Instr.Mov || node instanceof Instr.Unary1 || node instanceof Instr.MkApp || node instanceof Instr.Konst
                || node instanceof Instr.Closure || node instanceof Instr.KVal || node instanceof Instr.Fields) {
            return succ.length >= 1 ? A_SEQ : A_STOP;
        }
        if (node instanceof Instr.BKind || node instanceof Instr.BEq) {
            return succ.length == 2 ? A_COND : A_STOP;
        }
        if (node instanceof Instr.Switch) {
            return A_MULTI;
        }
        return A_STOP;
    }

    /**
     * The most interning instructions (MKAPP, MKLAM, MKPI, MKSUSP) the run-ahead from instruction {@code i} can
     * predict, over its paths. Instructions, not probes: the two probes of one closure (its frame, then the record
     * over the frame) depend on each other and cannot overlap; what overlaps are the probes of different
     * instructions, so a stretch with one interning instruction gains nothing from a run-ahead.
     */
    private int probes(int i, int[] memo) {
        if (i < 0 || akind[i] == A_STOP) {
            return 0;
        }
        if (memo[i] >= 0) {
            return memo[i];
        }
        int own = instr[i] instanceof Instr.MkApp || instr[i] instanceof Instr.Closure ? 1 : 0;
        int best = 0;
        for (int t : succ[i]) {
            best = Math.max(best, probes(t, memo));
        }
        return memo[i] = own + best;
    }

    private static FrameDescriptor descriptor(Decoded d) {
        FrameDescriptor.Builder fb = FrameDescriptor.newBuilder();
        fb.addSlots(2 * d.win + 11, FrameSlotKind.Static); // registers, cap, ls; the run-ahead's predicted registers and state
        return fb.build();
    }

    /** The node of an instruction: its own code, or the stepper's body ({@code nodes} false: always the body). */
    private static Instr node(Build b, Decoded.Insn in, boolean nodes) {
        if (in.kind == Decoded.JMP) {
            return new Instr.Step(b, in); // never executed: the loop follows the one successor
        }
        if (!nodes || in.generic) {
            return new Instr.Step(b, in);
        }
        switch (in.op) {
            case Op.MOV:
                return new Instr.Mov(b, in);
            case Op.PEEK:
            case Op.NSUCC:
                return new Instr.Unary1(b, in);
            case Op.MKPROJ:
                return new Instr.MkProj(b, in);
            case Op.MKAPP:
                return new Instr.MkApp(b, in);
            case Op.KCONST:
            case Op.KSORT:
            case Op.KLIT:
                return new Instr.Konst(b, in);
            case Op.MKLAM:
            case Op.MKPI:
            case Op.MKSUSP:
                return new Instr.Closure(b, in);
            case Op.KVAL:
                return new Instr.KVal(b, in);
            case Op.FIELDS:
                return new Instr.Fields(b, in);
            case Op.BKIND:
                return new Instr.BKind(b, in);
            case Op.BEQ:
            case Op.BNE:
                return new Instr.BEq(b, in);
            case Op.SWITCH:
                return new Instr.Switch(b, in);
            case Op.RET:
            case Op.TRET:
                return new Instr.Ret(b, in);
            case Op.APPLY:
            case Op.TAILAPPLY:
                return new Instr.Apply(b, in);
            case Op.WHNF:
            case Op.FORCE:
                return new Instr.Unary(b, in);
            default:
                return new Instr.Step(b, in);
        }
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }

    /** The entry number of {@code pc}, or -1: this root has no entry there. */
    int entryOf(int pc) {
        int[] e = entryPcs;
        for (int k = 0; k < e.length; k++) {
            if (e[k] == pc) {
                return k;
            }
        }
        return -1;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        T2Tier w = CompilerDirectives.castExact(frame.getArguments()[0], T2Tier.class);
        Machine m = w.m;
        w.result = run(frame, m, w, w.rb, w.d, w.entry);
        return null;
    }

    @ExplodeLoop
    private void clear(VirtualFrame f) {
        for (int i = 0; i < slots; i++) {
            f.setIntStatic(i, 0);
        }
    }

    /** The predicted registers at the start of a run-ahead: unknown, except the live registers, which are the real ones. */
    @ExplodeLoop
    private void aheadInit(VirtualFrame f, Machine m, int[] live) {
        int win = lsSlot - 1;
        for (int i = 0; i < win; i++) {
            f.setIntStatic(sbase + i, Instr.UNK);
        }
        for (int i = 0; i < live.length; i++) {
            f.setIntStatic(sbase + live[i], f.getIntStatic(live[i]));
        }
        int vp = T2Bridge.vp(m);
        f.setIntStatic(sbase + win, vp);                      // the id the next new record gets
        f.setIntStatic(sbase + win + 1, T2Bridge.rsp(m));     // the offset the next new run gets
        f.setIntStatic(sbase + win + 2, 0);                   // the sink of the early loads
        f.setIntStatic(sbase + win + 3, vp);                  // records from here on are predictions
        f.setIntStatic(sbase + win + 4, 0);                   // new ids are still predictable
        f.setIntStatic(sbase + win + 5, 0);                   // no frame / application predicted new yet
        f.setIntStatic(sbase + win + 6, 0);
        f.setIntStatic(sbase + win + 7, 0);
        f.setIntStatic(sbase + win + 8, 0);
    }

    /**
     * The run-ahead from instruction {@code start} (NOTES-T3 section 4): walks the value construction that follows
     * with PREDICTED registers - reads of the machine only - and loads the intern-table slot of every record and
     * frame those instructions will probe, until a judgment is called, the block returns or a branch cannot be
     * predicted. The loads are independent of each other, so their cache misses overlap; when the instructions
     * then run, their probes find the slots in the cache. Nothing of the machine is written: a wrong prediction
     * costs one load and changes no value, record or tick.
     */
    @ExplodeLoop(kind = LoopExplosionKind.MERGE_EXPLODE)
    private void ahead(VirtualFrame f, Machine m, T2Tier w, int start, int[] live) {
        aheadInit(f, m, live);
        int i = start;
        outer:
        for (;;) {
            CompilerAsserts.partialEvaluationConstant(i);
            if (i == END) {
                w.sink = f.getIntStatic(lsSlot + 1 + (lsSlot - 1) + 2); // keeps the loads alive: the one store of the run-ahead
                return;
            }
            switch (akind[i]) {
                case A_SEQ: {
                    int r = instr[i].ahead(f, m);
                    if (r < 0) {
                        i = END;
                        continue outer;
                    }
                    i = succ[i][0];
                    continue outer;
                }
                case A_JMP:
                    i = succ[i][0];
                    continue outer;
                case A_COND: {
                    int r = instr[i].ahead(f, m);
                    if (r < 0) {
                        i = END;
                        continue outer;
                    }
                    if (r != 0) {
                        i = succ[i][1];
                        continue outer;
                    }
                    i = succ[i][0];
                    continue outer;
                }
                case A_MULTI: {
                    int r = instr[i].ahead(f, m);
                    int[] s = succ[i];
                    for (int k = 0; k < s.length; k++) {
                        if (r == k) {
                            i = s[k];
                            continue outer;
                        }
                    }
                    i = END;
                    continue outer;
                }
                default:
                    i = END;
                    continue outer;
            }
        }
    }

    @ExplodeLoop(kind = LoopExplosionKind.MERGE_EXPLODE)
    private int run(VirtualFrame f, Machine m, T2Tier w, int rb, int d, int e) {
        clear(f); // every slot is defined; the frame is virtual, so this costs nothing in compiled code
        if (usesCap) {
            f.setIntStatic(capSlot, T2Bridge.cap(m, rb));
        }
        if (usesLs) {
            f.setIntStatic(lsSlot, T2Bridge.ls(m, rb));
        }
        int i = START;
        int result = 0;
        outer:
        for (;;) {
            CompilerAsserts.partialEvaluationConstant(i);
            if (i == END) {
                // The one place an EXIT instruction leaves through. (Code after a loop exit is shared by all
                // iterations of an exploded loop: there the instruction index is no longer a constant, so an
                // instruction node must never be executed on an exit path.)
                return result;
            }
            if (i == START) {
                for (int k = 0; k < entryIdx.length; k++) {
                    if (e == k) {
                        Instr.load(f, m, rb, entryLoad[k]);
                        i = entryIdx[k];
                        if (aheadAt[i]) {
                            ahead(f, m, w, i, aheadLive[i]);
                        }
                        continue outer;
                    }
                }
                return T2Bridge.fault(m, MachineErrors.E_INTERNAL); // T2Tier.run checked the entry
            }
            switch (kind[i]) {
                case Decoded.SEQ: {
                    int r = instr[i].exec(f, m, rb, d);
                    if (r >= Op.SIG) {
                        return r;
                    }
                    boolean wasJudgment = classJ[i];
                    i = succ[i][0];
                    if (wasJudgment && aheadAt[i]) {
                        ahead(f, m, w, i, aheadLive[i]); // the judgment has returned: predict the next stretch
                    }
                    continue outer;
                }
                case Decoded.JMP:
                    i = succ[i][0];
                    continue outer;
                case Decoded.COND: {
                    int r = instr[i].exec(f, m, rb, d);
                    if (r >= Op.SIG) {
                        return r;
                    }
                    if (r != 0) {
                        i = succ[i][1];
                        continue outer;
                    }
                    i = succ[i][0];
                    continue outer;
                }
                case Decoded.MULTI: {
                    int r = instr[i].exec(f, m, rb, d);
                    if (r >= Op.SIG) {
                        return r;
                    }
                    int[] s = succ[i];
                    for (int k = 0; k < s.length; k++) {
                        if (r == k) {
                            i = s[k];
                            continue outer;
                        }
                    }
                    return T2Bridge.fault(m, MachineErrors.E_INTERNAL);
                }
                case Decoded.EXIT:
                    result = instr[i].exec(f, m, rb, d);
                    i = END;
                    continue outer;
                default: { // STEP: the stepper's body answers the next pc
                    int r = instr[i].exec(f, m, rb, d);
                    if (r < 0) {
                        return T2Bridge.stepResult(m);
                    }
                    int[] s = succ[i];
                    int[] sp = succPc[i];
                    for (int k = 0; k < s.length; k++) {
                        if (s[k] >= 0 && r == sp[k]) {
                            i = s[k];
                            continue outer;
                        }
                    }
                    // A successor the tier did not foresee. With every register in the window the stepper
                    // can take over from here; otherwise the activation is declined (never guessed).
                    if (windowMode) {
                        return T2Bridge.interpret(m, code, blk, r, rb, d);
                    }
                    return T2Bridge.fault(m, MachineErrors.E_INTERNAL);
                }
            }
        }
    }
}
