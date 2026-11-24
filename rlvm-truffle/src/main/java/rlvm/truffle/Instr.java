package rlvm.truffle;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import rlvm.api.MachineErrors;
import rlvm.machine.Layout;
import rlvm.machine.Machine;
import rlvm.machine.Op;
import rlvm.machine.T2Bridge;
import rlvm.machine.Tier;

/**
 * One node per instruction of a promoted block (ISA 8.4 "nodes"). The operands are decoded once, when
 * the root is built, into final fields: under partial evaluation the opcode dispatch, the operand modes,
 * the register numbers and the successor pcs are constants. A register is a static {@code int} frame
 * slot (a value of the compiled code) unless it is pinned to the machine's window
 * ({@link Decoded#pinned}); the operations are the machine's own, behind the boundaries of
 * {@link T2Bridge}. Every node mirrors the instruction body of the stepper it replaces ({@code Code.java},
 * cited at each class) statement by statement; an instruction without a node of its own is executed BY
 * that body ({@link Step}).
 *
 * <p>No node deoptimises (ISA 8.4 "no-deopt policy"): there is no profile, no assumption, no rewriting;
 * every kind test is an explicit branch, and a rare path is a call, not a transfer to the interpreter.
 *
 * <p>{@code exec} returns what the root's loop expects for the instruction's {@link Decoded} kind.
 */
abstract class Instr extends Node {
    static final int SIG = Op.SIG;

    /** Frame slot of the captured frame run / of the level substitution of the activation. */
    final int capSlot;
    final int lsSlot;
    final int blk;
    final int pc;
    /** Slot registers to write back to the window when this instruction suspends the activation. */
    @CompilationFinal(dimensions = 1) final int[] flushOnSusp;

    Instr(BlockRoot.Build b, Decoded.Insn in) {
        this.capSlot = b.capSlot;
        this.lsSlot = b.lsSlot;
        this.blk = b.d.blk;
        this.pc = in.pc;
        this.flushOnSusp = b.flushOnSusp(in);
    }

    abstract int exec(VirtualFrame f, Machine m, int rb, int d);

    // ---------------------------------------------------------------------------------------------------
    // Operands
    // ---------------------------------------------------------------------------------------------------

    /** A decoded operand word {@code mode:4 | payload:28}; {@code pin}: the register lives in the window. */
    static final class Opnd {
        final int mode;
        final int p;
        final boolean pin;

        Opnd(int o, boolean[] pinned) {
            this.mode = o >>> Op.OPND_SHIFT;
            this.p = o & Op.OPND_PAYLOAD_MASK;
            this.pin = (mode == Op.MODE_R || mode == Op.MODE_A) && p < pinned.length && pinned[p];
        }
    }

    static Opnd[] opnds(BlockRoot.Build b, Decoded.Insn in, int from, int to) {
        Opnd[] r = new Opnd[Math.max(0, to - from + 1)];
        for (int i = 0; i < r.length; i++) {
            r[i] = new Opnd(in.o[from + i], b.d.pinned);
        }
        return r;
    }

    /** Register {@code i}: a window word or a frame slot. */
    static int reg(VirtualFrame f, Machine m, int rb, int i, boolean pin) {
        return pin ? T2Bridge.reg(m, rb, i) : f.getIntStatic(i);
    }

    static void setReg(VirtualFrame f, Machine m, int rb, int i, boolean pin, int v) {
        if (pin) {
            T2Bridge.setReg(m, rb, i, v);
        } else {
            f.setIntStatic(i, v);
        }
    }

    /**
     * The value of a value operand ({@code Code.val}; ISA 3.3 modes, 4.0): read as the generic walk reads a
     * variable, PEEK(GET); HOLE through an argument or a captured slot is {@code fault(E_HOLE)}.
     *
     * @return the value, or FATAL
     */
    final int val(VirtualFrame f, Machine m, int rb, Opnd o) {
        int v;
        switch (o.mode) {
            case Op.MODE_R:
                v = reg(f, m, rb, o.p, o.pin);
                break;
            case Op.MODE_A:
                v = reg(f, m, rb, o.p, o.pin);
                if (v == Layout.HOLE) {
                    return T2Bridge.fault(m, MachineErrors.E_HOLE);
                }
                break;
            case Op.MODE_C:
                v = T2Bridge.captured(m, f.getIntStatic(capSlot), o.p);
                if (v == Layout.HOLE) {
                    return T2Bridge.fault(m, MachineErrors.E_HOLE);
                }
                break;
            case Op.MODE_I:
                return o.p | Layout.IMM;
            case Op.MODE_V:
                return o.p;
            default:
                return T2Bridge.fault(m, MachineErrors.E_INTERNAL);
        }
        if (v > Layout.HOLE && T2Bridge.kindOfRecord(m, v) == Layout.SUSP) {
            return T2Bridge.peek(m, v);
        }
        return v;
    }

    /** The operand as a slot of a capture list ({@code Code.raw}): the binding as it is. */
    final int raw(VirtualFrame f, Machine m, int rb, Opnd o) {
        switch (o.mode) {
            case Op.MODE_R:
            case Op.MODE_A:
                return reg(f, m, rb, o.p, o.pin);
            case Op.MODE_C:
                return T2Bridge.captured(m, f.getIntStatic(capSlot), o.p);
            case Op.MODE_V:
                return o.p;
            default:
                return T2Bridge.fault(m, MachineErrors.E_INTERNAL);
        }
    }

    /**
     * Evaluates {@code a} into scratch at {@code m.xp} and claims it ({@code Code.operands}).
     *
     * @return the scratch base, or a signal
     */
    @ExplodeLoop
    final int operands(VirtualFrame f, Machine m, int rb, Opnd[] a) {
        int n = a.length;
        if (!T2Bridge.scratchFits(m, n) && !T2Bridge.growScratch(m, n)) {
            return T2Bridge.fault(m, MachineErrors.E_MEM);
        }
        int xb = T2Bridge.xp(m);
        for (int i = 0; i < n; i++) {
            int v = val(f, m, rb, a[i]);
            if (v >= SIG) {
                return v;
            }
            T2Bridge.setX(m, xb + i, v);
        }
        T2Bridge.setXp(m, xb + n);
        return xb;
    }

    /** Writes the slot registers {@code regs} back to the window. */
    @ExplodeLoop
    static void flush(VirtualFrame f, Machine m, int rb, int[] regs) {
        for (int i = 0; i < regs.length; i++) {
            T2Bridge.setReg(m, rb, regs[i], f.getIntStatic(regs[i]));
        }
    }

    /** Reads the slot registers {@code regs} from the window. */
    @ExplodeLoop
    static void load(VirtualFrame f, Machine m, int rb, int[] regs) {
        for (int i = 0; i < regs.length; i++) {
            f.setIntStatic(regs[i], T2Bridge.reg(m, rb, regs[i]));
        }
    }

    /**
     * The H3 protocol of a class-j instruction whose judgment answered signal {@code sig} ({@code Code.susp};
     * ISA 8.4 "class-j instruction ... write the activation"): the registers the rest of the block reads go
     * back to the window, the frame ACT [blk, pc, dst, ..] is written, the signal is returned.
     */
    final int suspend(VirtualFrame f, Machine m, int rb, int sig, int resumePc, int dst) {
        flush(f, m, rb, flushOnSusp);
        return T2Bridge.suspend(m, sig, blk, resumePc, dst, rb);
    }

    // ---------------------------------------------------------------------------------------------------
    // Class p: value construction (ISA 4.2)
    // ---------------------------------------------------------------------------------------------------

    /** MOV d, s ({@code Code.resumeAt} case MOV). */
    static final class Mov extends Instr {
        final Opnd s;
        final int dst;
        final boolean dpin;

        Mov(BlockRoot.Build b, Decoded.Insn in) {
            super(b, in);
            s = new Opnd(in.o[1], b.d.pinned);
            dst = in.d;
            dpin = b.d.pinned[in.d];
        }

        @Override
        int exec(VirtualFrame f, Machine m, int rb, int d) {
            int v = val(f, m, rb, s);
            if (v >= SIG) {
                return v;
            }
            setReg(f, m, rb, dst, dpin, v);
            return 0;
        }
    }

    /** PEEK d, s ({@code Code.peek}) and NSUCC d, s ({@code Code.nsucc}). */
    static final class Unary1 extends Instr {
        final Opnd s;
        final int dst;
        final boolean dpin;
        final boolean isPeek;

        Unary1(BlockRoot.Build b, Decoded.Insn in) {
            super(b, in);
            s = new Opnd(in.o[1], b.d.pinned);
            dst = in.d;
            dpin = b.d.pinned[in.d];
            isPeek = in.op == Op.PEEK;
        }

        @Override
        int exec(VirtualFrame f, Machine m, int rb, int d) {
            int v = val(f, m, rb, s);
            if (isPeek) {
                if (!(v >= SIG || v < 0 || v <= Layout.HOLE)) {
                    v = T2Bridge.peek(m, v);
                }
            } else if (v < SIG) {
                v = T2Bridge.nsucc(m, v);
            }
            if (v >= SIG) {
                return v;
            }
            setReg(f, m, rb, dst, dpin, v);
            return 0;
        }
    }

    /** MKPROJ d, S, i, s ({@code Code.mkProj}). */
    static final class MkProj extends Instr {
        final Opnd s;
        final int row;
        final int field;
        final int dst;
        final boolean dpin;

        MkProj(BlockRoot.Build b, Decoded.Insn in) {
            super(b, in);
            row = in.o[1] & Op.OPND_PAYLOAD_MASK;
            field = in.o[2] & Op.OPND_PAYLOAD_MASK;
            s = new Opnd(in.o[3], b.d.pinned);
            dst = in.d;
            dpin = b.d.pinned[in.d];
        }

        @Override
        int exec(VirtualFrame f, Machine m, int rb, int d) {
            int v = val(f, m, rb, s);
            if (v >= SIG) {
                return v;
            }
            v = T2Bridge.mkProj(m, row, field, v);
            if (v >= SIG) {
                return v;
            }
            setReg(f, m, rb, dst, dpin, v);
            return 0;
        }
    }

    /** MKAPP d, h, a1..an ({@code Code.mkApp}). */
    static final class MkApp extends Instr {
        final Opnd h;
        @CompilationFinal(dimensions = 1) final Opnd[] args;
        final int dst;
        final boolean dpin;

        MkApp(BlockRoot.Build b, Decoded.Insn in) {
            super(b, in);
            h = new Opnd(in.o[1], b.d.pinned);
            args = opnds(b, in, 2, in.n);
            dst = in.d;
            dpin = b.d.pinned[in.d];
        }

        @Override
        int exec(VirtualFrame f, Machine m, int rb, int d) {
            int hv = val(f, m, rb, h);
            if (hv >= SIG) {
                return hv;
            }
            int xb = operands(f, m, rb, args);
            if (xb >= SIG) {
                return xb;
            }
            int r = T2Bridge.mkApp(m, hv, xb, args.length);
            T2Bridge.setXp(m, xb);
            if (r >= SIG) {
                return r;
            }
            setReg(f, m, rb, dst, dpin, r);
            return 0;
        }
    }

    /** KCONST d, D, LL ({@code Code.kConst}), KSORT d, L ({@code Code.kSort}), KLIT d, T. */
    static final class Konst extends Instr {
        final int op;
        final int a;
        final int list;
        final boolean lparam;
        final int dst;
        final boolean dpin;

        Konst(BlockRoot.Build b, Decoded.Insn in) {
            super(b, in);
            op = in.op;
            a = in.o[1] & Op.OPND_PAYLOAD_MASK;
            list = in.op == Op.KCONST ? in.o[2] & Op.OPND_PAYLOAD_MASK : 0;
            lparam = in.op != Op.KLIT && (in.w & T2Bridge.X_LPARAM) != 0;
            dst = in.d;
            dpin = b.d.pinned[in.d];
        }

        @Override
        int exec(VirtualFrame f, Machine m, int rb, int d) {
            int v;
            if (op == Op.KCONST) {
                v = lparam ? T2Bridge.mkConstSubst(m, a, list, f.getIntStatic(lsSlot)) : T2Bridge.mkConst(m, a, list);
            } else if (op == Op.KSORT) {
                v = lparam ? T2Bridge.mkSortSubst(m, a, f.getIntStatic(lsSlot)) : T2Bridge.mkSort(m, a);
            } else {
                v = T2Bridge.klit(m, a);
            }
            if (v >= SIG) {
                return v;
            }
            setReg(f, m, rb, dst, dpin, v);
            return 0;
        }
    }

    /** MKLAM / MKPI d, T, B, c0..ck and MKSUSP d, T, c0..ck ({@code Code.mkClosure}). */
    static final class Closure extends Instr {
        final int op;
        final int term;
        final int sub;
        final boolean lparam;
        final boolean let;
        @CompilationFinal(dimensions = 1) final Opnd[] caps;
        final int dst;
        final boolean dpin;

        Closure(BlockRoot.Build b, Decoded.Insn in) {
            super(b, in);
            op = in.op;
            term = in.o[1] & Op.OPND_PAYLOAD_MASK;
            sub = in.op == Op.MKSUSP ? 0 : in.o[2] & Op.OPND_PAYLOAD_MASK;
            caps = opnds(b, in, in.op == Op.MKSUSP ? 2 : 3, in.n);
            lparam = (in.w & T2Bridge.X_LPARAM) != 0;
            let = (in.w & T2Bridge.X_LET) != 0;
            dst = in.d;
            dpin = b.d.pinned[in.d];
        }

        @Override
        @ExplodeLoop
        int exec(VirtualFrame f, Machine m, int rb, int d) {
            int nc = caps.length;
            int xb = 0;
            if (nc > 0) {
                if (!T2Bridge.scratchFits(m, nc) && !T2Bridge.growScratch(m, nc)) {
                    return T2Bridge.fault(m, MachineErrors.E_MEM);
                }
                xb = T2Bridge.xp(m);
                for (int i = 0; i < nc; i++) {
                    int v = raw(f, m, rb, caps[i]);
                    if (v >= SIG) {
                        return v;
                    }
                    T2Bridge.setX(m, xb + i, v);
                }
                T2Bridge.setXp(m, xb + nc);
            }
            int v = T2Bridge.closure(m, op, term, sub, xb, nc, lparam ? f.getIntStatic(lsSlot) : 0, let);
            if (v >= SIG) {
                return v;
            }
            setReg(f, m, rb, dst, dpin, v);
            return 0;
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // Class c: control (ISA 4.1)
    // ---------------------------------------------------------------------------------------------------

    /** BKIND s, set, L ({@code Code.bkind}): 1 if the kind of the value is in the set. */
    static final class BKind extends Instr {
        final Opnd s;
        final int set;

        BKind(BlockRoot.Build b, Decoded.Insn in) {
            super(b, in);
            s = new Opnd(in.o[1], b.d.pinned);
            set = in.o[2] & Op.OPND_PAYLOAD_MASK;
        }

        @Override
        int exec(VirtualFrame f, Machine m, int rb, int d) {
            int v = val(f, m, rb, s);
            if (v >= SIG) {
                return v;
            }
            int k = v < 0 ? Layout.K_IMM : T2Bridge.kindOfRecord(m, v);
            return set >>> k & 1;
        }
    }

    /** BEQ / BNE a, b, L ({@code Code.beq}): identity of refs. */
    static final class BEq extends Instr {
        final Opnd a;
        final Opnd b;
        final boolean eq;

        BEq(BlockRoot.Build bb, Decoded.Insn in) {
            super(bb, in);
            a = new Opnd(in.o[1], bb.d.pinned);
            b = new Opnd(in.o[2], bb.d.pinned);
            eq = in.op == Op.BEQ;
        }

        @Override
        int exec(VirtualFrame f, Machine m, int rb, int d) {
            int x = val(f, m, rb, a);
            int y = val(f, m, rb, b);
            if (x >= SIG || y >= SIG) {
                return x >= SIG ? x : y;
            }
            return (x == y) == eq ? 1 : 0;
        }
    }

    /** SWITCH s, I, L0..Lk-1, Lx ({@code Code.switchOn}): the index of the arm, k for Lx. */
    static final class Switch extends Instr {
        final Opnd s;
        final int ind;
        final int k;

        Switch(BlockRoot.Build b, Decoded.Insn in) {
            super(b, in);
            s = new Opnd(in.o[1], b.d.pinned);
            ind = in.o[2] & Op.OPND_PAYLOAD_MASK;
            k = in.n - 3;
        }

        @Override
        int exec(VirtualFrame f, Machine m, int rb, int d) {
            int v = val(f, m, rb, s);
            if (v >= SIG) {
                return v;
            }
            int j = T2Bridge.ctor(m, v, ind);
            return j >= 0 && j < k ? j : k;
        }
    }

    /** RET s and TRET s ({@code Code.resumeAt} cases RET, TRET). */
    static final class Ret extends Instr {
        final Opnd s;

        Ret(BlockRoot.Build b, Decoded.Insn in) {
            super(b, in);
            s = new Opnd(in.o[1], b.d.pinned);
        }

        @Override
        int exec(VirtualFrame f, Machine m, int rb, int d) {
            int v = val(f, m, rb, s);
            if (v < SIG) {
                T2Bridge.pop(m, rb);
            }
            return v;
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // Class j: instructions that call a judgment (H3)
    // ---------------------------------------------------------------------------------------------------

    /** APPLY d, f, a1..an ({@code Code.apply}) and TAILAPPLY f, a1..an ({@code Code.tailApply}). */
    static final class Apply extends Instr {
        final Opnd fn;
        @CompilationFinal(dimensions = 1) final Opnd[] args;
        final boolean tail;
        final int dst;
        final boolean dpin;
        final int resumePc;

        Apply(BlockRoot.Build b, Decoded.Insn in) {
            super(b, in);
            fn = new Opnd(in.o[1], b.d.pinned);
            args = opnds(b, in, 2, in.n);
            tail = in.op == Op.TAILAPPLY;
            dst = in.d;
            dpin = !tail && b.d.pinned[in.d];
            resumePc = in.nextPc;
        }

        @Override
        int exec(VirtualFrame f, Machine m, int rb, int d) {
            int fv = val(f, m, rb, fn);
            if (fv >= SIG) {
                return fv;
            }
            int xb = operands(f, m, rb, args);
            if (xb >= SIG) {
                return xb;
            }
            int r = T2Bridge.apply(m, fv, xb, args.length, d + 1);
            if (r >= SIG) {
                // the arguments stay claimed: they are scratch of the suspended activation
                return tail ? suspend(f, m, rb, r, 0, T2Bridge.DST_RET) : suspend(f, m, rb, r, resumePc, dst);
            }
            T2Bridge.setXp(m, xb);
            if (tail) {
                T2Bridge.pop(m, rb);
                return r;
            }
            setReg(f, m, rb, dst, dpin, r);
            return 0;
        }
    }

    /** WHNF d, s and FORCE d, s ({@code Code.unary}). */
    static final class Unary extends Instr {
        final Opnd s;
        final boolean whnf;
        final int dst;
        final boolean dpin;
        final int resumePc;

        Unary(BlockRoot.Build b, Decoded.Insn in) {
            super(b, in);
            s = new Opnd(in.o[1], b.d.pinned);
            whnf = in.op == Op.WHNF;
            dst = in.d;
            dpin = b.d.pinned[in.d];
            resumePc = in.nextPc;
        }

        @Override
        int exec(VirtualFrame f, Machine m, int rb, int d) {
            int v = val(f, m, rb, s);
            if (v >= SIG) {
                return v;
            }
            int r = whnf ? T2Bridge.whnf(m, v, d + 1) : T2Bridge.force(m, v, d + 1);
            if (r >= SIG) {
                return suspend(f, m, rb, r, resumePc, dst);
            }
            setReg(f, m, rb, dst, dpin, r);
            return 0;
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // The stepper's own instruction body (Tier.step), for everything without a node of its own
    // ---------------------------------------------------------------------------------------------------

    /**
     * One instruction through {@code Tier.step}: the stepper's body reads its operands from the window and
     * writes its results there, so the slot registers it reads are written back before the call and the
     * ones it writes are read again after it. Used for BUNREAD, MATCH, the four phases of TARG, any opcode
     * this tier does not know, and as the slow path of {@link KVal} and {@link Fields}.
     *
     * <p>{@code exec}: the next pc, or -1: the activation ended (returned, suspended, failed) and its
     * result or signal is {@code T2Bridge.stepResult(m)}.
     */
    static class Step extends Instr {
        @CompilationFinal(dimensions = 1) final int[] code;
        @CompilationFinal(dimensions = 1) final int[] flushBefore;
        @CompilationFinal(dimensions = 1) final int[] loadAfter;

        Step(BlockRoot.Build b, Decoded.Insn in) {
            super(b, in);
            code = b.d.code;
            flushBefore = b.d.slotsOf(concat(in.uses, in.condDefs));
            loadAfter = b.d.slotsOf(concat(in.defs, in.condDefs));
        }

        private static int[] concat(int[] a, int[] b) {
            int[] r = java.util.Arrays.copyOf(a, a.length + b.length);
            System.arraycopy(b, 0, r, a.length, b.length);
            return r;
        }

        final int step(VirtualFrame f, Machine m, int rb, int d) {
            flush(f, m, rb, flushBefore);
            int npc = T2Bridge.step(m, code, blk, pc, rb, d);
            if (npc == Tier.DONE) {
                if (T2Bridge.stepResult(m) == Op.SUSP) {
                    flush(f, m, rb, flushOnSusp);
                }
                return -1;
            }
            load(f, m, rb, loadAfter);
            return npc;
        }

        @Override
        int exec(VirtualFrame f, Machine m, int rb, int d) {
            return step(f, m, rb, d);
        }
    }

    /**
     * KVAL d, K ({@code Code.kval}): the inline cache answers, or the instruction runs through the
     * stepper's body (the generic walk of the closed term, the cache fill, the suspension).
     */
    static final class KVal extends Step {
        final boolean lparam;
        final int dst;
        final boolean dpin;

        KVal(BlockRoot.Build b, Decoded.Insn in) {
            super(b, in);
            lparam = (in.w & T2Bridge.KV_LPARAM) != 0;
            dst = in.d;
            dpin = b.d.pinned[in.d];
        }

        @Override
        int exec(VirtualFrame f, Machine m, int rb, int d) {
            int v = T2Bridge.kvalHit(m, blk, pc, lparam ? f.getIntStatic(lsSlot) : 0);
            if (v != Op.MISS) {
                setReg(f, m, rb, dst, dpin, v);
                return 0;
            }
            if (step(f, m, rb, d) < 0) {
                int r = T2Bridge.stepResult(m);
                return r >= SIG ? r : T2Bridge.fault(m, MachineErrors.E_INTERNAL);
            }
            return 0;
        }
    }

    /**
     * FIELDS d0, s (x = n) ({@code Code.fields}): the last n arguments of a constructor application; the
     * literal and NSUC views of Nat go through the stepper's body.
     */
    static final class Fields extends Step {
        final Opnd s;
        final int d0;
        final int n;
        @CompilationFinal(dimensions = 1) final boolean[] dpin;

        Fields(BlockRoot.Build b, Decoded.Insn in) {
            super(b, in);
            s = new Opnd(in.o[1], b.d.pinned);
            d0 = in.d;
            n = in.x;
            dpin = new boolean[n];
            for (int i = 0; i < n; i++) {
                dpin[i] = b.d.pinned[d0 + i];
            }
        }

        @Override
        @ExplodeLoop
        int exec(VirtualFrame f, Machine m, int rb, int d) {
            int v = val(f, m, rb, s);
            if (v >= SIG) {
                return v;
            }
            int base = T2Bridge.lastArgs(m, v, n);
            if (base >= 0) {
                for (int i = 0; i < n; i++) {
                    setReg(f, m, rb, d0 + i, dpin[i], T2Bridge.runWord(m, base + i));
                }
                return 0;
            }
            if (step(f, m, rb, d) < 0) {
                int r = T2Bridge.stepResult(m);
                return r >= SIG ? r : T2Bridge.fault(m, MachineErrors.E_INTERNAL);
            }
            return 0;
        }
    }

    static boolean inCompiledCode() {
        return CompilerDirectives.inCompiledCode();
    }
}
