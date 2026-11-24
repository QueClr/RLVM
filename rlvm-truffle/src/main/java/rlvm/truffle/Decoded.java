package rlvm.truffle;

import java.util.ArrayList;
import java.util.BitSet;
import rlvm.machine.Op;
import rlvm.machine.T2Bridge;

/**
 * A code block of the machine (ISA 3.3; NOTES-M7 section 2) taken apart for the tier: its instructions,
 * their successors, which window words each reads and writes, which registers can live in frame slots
 * and which of those are live at every instruction. Plain Java, no Truffle: used on the promoter thread.
 *
 * <p>Instruction word {@code op:8 | d:8 | n:8 | x:8}, then n operand words {@code mode:4 | payload:28}.
 * An opcode this class does not know is still executable (through the stepper's own instruction body,
 * {@code Tier.step}): its successors are the fall-through and every label operand; but nothing is known
 * about the registers it writes, so a block with such an instruction keeps every register in the window.
 */
final class Decoded {

    /** How the root's loop continues after an instruction. */
    static final byte SEQ = 0;      // exec: 0 = next instruction, or the activation's signal
    static final byte JMP = 1;      // no node: the one successor
    static final byte COND = 2;     // exec: 0 = fall through, 1 = branch, or a signal
    static final byte MULTI = 3;    // exec: the index of the successor, or a signal
    static final byte EXIT = 4;     // exec: the result or signal of the activation
    static final byte STEP = 5;     // exec: the next pc (generic instruction body), or -1 = the activation ended

    static final class Insn {
        int index;
        int pc;
        int w;
        int op;
        int d;
        int n;
        int x;
        int[] o;
        int nextPc;
        byte kind;
        /** Successor pcs in selector order (SEQ / STEP: fall-through first). */
        int[] succPc = new int[0];
        int[] succ;
        /** Window words read / always written / possibly written (all registers, pinned or not). */
        int[] uses = new int[0];
        int[] defs = new int[0];
        int[] condDefs = new int[0];
        /** A class-j instruction: a judgment is called and may suspend the activation. */
        boolean classJ;
        /** True if the instruction is executed by the stepper's body through {@code Tier.step}. */
        boolean generic;
        boolean known = true;
        BitSet liveIn = new BitSet();
        BitSet liveOut = new BitSet();
    }

    final int[] code;
    final int blk;
    final int len;
    final int nreg;
    final int role;
    final int arity;
    final int flags;
    final int nentry;
    final int win;
    final int codeStart;
    final int[] entryPc;
    final Insn[] insn;
    final int[] indexOfPc;
    /** Window words that stay in the machine's window (never in a frame slot). */
    final boolean[] pinned;
    /** Non-null: the block cannot be promoted, and why. */
    String hostile;
    boolean usesCap;
    boolean usesLs;

    Decoded(int[] code, int blk, boolean slots) {
        this.code = code;
        this.blk = blk;
        int h0 = code[Op.BLK_HDR0];
        int h2 = code[Op.BLK_HDR2];
        len = T2Bridge.blockLen(h0);
        nreg = T2Bridge.blockNreg(h0);
        role = T2Bridge.blockRole(h0);
        arity = T2Bridge.blockArity(h2);
        flags = T2Bridge.blockFlags(h2);
        nentry = T2Bridge.blockEntries(h2);
        win = arity + nreg;
        codeStart = Op.BLK_ENTRIES + nentry;
        entryPc = new int[nentry];
        indexOfPc = new int[Math.max(len, 1)];
        java.util.Arrays.fill(indexOfPc, -1);
        pinned = new boolean[win];
        ArrayList<Insn> list = new ArrayList<>();
        if (len != code.length || len < codeStart || role == Op.ROLE_CONG || role == 0) {
            hostile = "not a code block";
            insn = new Insn[0];
            return;
        }
        System.arraycopy(code, Op.BLK_ENTRIES, entryPc, 0, nentry);
        boolean unknown = false;
        int pc = codeStart;
        while (pc < len) {
            Insn in = new Insn();
            in.index = list.size();
            in.pc = pc;
            in.w = code[pc];
            in.op = in.w >>> 24;
            in.d = in.w >>> 16 & 255;
            in.n = in.w >>> 8 & 255;
            in.x = in.w & 255;
            in.nextPc = pc + 1 + in.n;
            if (in.nextPc > len) {
                hostile = "instruction at " + pc + " runs over the block";
                break;
            }
            in.o = new int[in.n + 1];
            for (int i = 1; i <= in.n; i++) {
                in.o[i] = code[pc + i];
            }
            classify(in);
            unknown |= !in.known;
            indexOfPc[pc] = in.index;
            list.add(in);
            pc = in.nextPc;
        }
        insn = list.toArray(new Insn[0]);
        if (hostile != null) {
            return;
        }
        if (insn.length == 0) {
            hostile = "no code";
            return;
        }
        // registers that stay in the window
        boolean all = !slots || unknown;
        int tele = role == Op.ROLE_TELE ? Math.min(win, arity + T2Bridge.TELE_SITE) : 0;
        for (int i = 0; i < win; i++) {
            pinned[i] = all || i < tele;
        }
        // successors as instruction indexes
        for (Insn in : insn) {
            in.succ = new int[in.succPc.length];
            for (int k = 0; k < in.succPc.length; k++) {
                int t = in.succPc[k];
                int ix = t >= 0 && t < len ? indexOfPc[t] : -1;
                if (ix < 0) {
                    if (in.known && !in.generic) {
                        hostile = "branch target " + t + " of the instruction at " + in.pc + " is not an instruction";
                        return;
                    }
                    // a label operand of a generic instruction that is not a label after all: never taken
                }
                in.succ[k] = ix;
            }
            for (int r : in.uses) {
                if (r < 0 || r >= win) {
                    hostile = "register " + r + " of the instruction at " + in.pc + " is outside the window";
                    return;
                }
            }
            for (int r : in.defs) {
                if (r < 0 || r >= win) {
                    hostile = "register " + r + " of the instruction at " + in.pc + " is outside the window";
                    return;
                }
            }
            for (int r : in.condDefs) {
                if (r < 0 || r >= win) {
                    hostile = "register " + r + " of the instruction at " + in.pc + " is outside the window";
                    return;
                }
            }
            if (in.kind == SEQ || in.kind == COND || in.kind == STEP) {
                if (in.succ.length == 0 || in.succ[0] < 0) {
                    hostile = "the instruction at " + in.pc + " falls off the block";
                    return;
                }
            }
        }
        liveness();
    }

    private static int mode(int o) {
        return o >>> Op.OPND_SHIFT;
    }

    private static int pay(int o) {
        return o & Op.OPND_PAYLOAD_MASK;
    }

    /** The window words named by the value operands {@code o[from .. to]} (modes R and A). */
    private int[] regs(Insn in, int from, int to) {
        int k = 0;
        int[] r = new int[Math.max(0, to - from + 1)];
        for (int i = from; i <= to; i++) {
            int md = mode(in.o[i]);
            if (md == Op.MODE_R || md == Op.MODE_A) {
                r[k++] = pay(in.o[i]);
            } else if (md == Op.MODE_C) {
                usesCap = true;
            }
        }
        return java.util.Arrays.copyOf(r, k);
    }

    private static int[] range(int from, int n) {
        int[] r = new int[n];
        for (int i = 0; i < n; i++) {
            r[i] = from + i;
        }
        return r;
    }

    private static int[] concat(int[] a, int[] b) {
        int[] r = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    /** The operand layout of every instruction the K1 elaborator emits (NOTES-M7 section 2; {@code Code.resumeAt}). */
    private void classify(Insn in) {
        int n = in.n;
        switch (in.op) {
            case Op.MKAPP:
                need(in, n >= 1);
                in.kind = SEQ;
                in.uses = regs(in, 1, n);
                in.defs = new int[] {in.d};
                break;
            case Op.KCONST:
                need(in, n == 2);
                in.kind = SEQ;
                in.defs = new int[] {in.d};
                usesLs |= (in.w & T2Bridge.X_LPARAM) != 0;
                break;
            case Op.KSORT:
                need(in, n == 1);
                in.kind = SEQ;
                in.defs = new int[] {in.d};
                usesLs |= (in.w & T2Bridge.X_LPARAM) != 0;
                break;
            case Op.KLIT:
                need(in, n == 1);
                in.kind = SEQ;
                in.defs = new int[] {in.d};
                break;
            case Op.KVAL:
                need(in, n == 1);
                in.kind = SEQ;
                in.defs = new int[] {in.d};
                in.classJ = true;
                usesLs |= (in.w & T2Bridge.KV_LPARAM) != 0;
                break;
            case Op.MKLAM:
            case Op.MKPI:
                need(in, n >= 2);
                in.kind = SEQ;
                in.uses = regs(in, 3, n);
                in.defs = new int[] {in.d};
                usesLs |= (in.w & T2Bridge.X_LPARAM) != 0;
                break;
            case Op.MKSUSP:
                need(in, n >= 1);
                in.kind = SEQ;
                in.uses = regs(in, 2, n);
                in.defs = new int[] {in.d};
                usesLs |= (in.w & T2Bridge.X_LPARAM) != 0;
                break;
            case Op.MKPROJ:
                need(in, n == 3);
                in.kind = SEQ;
                in.uses = regs(in, 3, 3);
                in.defs = new int[] {in.d};
                break;
            case Op.MOV:
            case Op.PEEK:
            case Op.NSUCC:
                need(in, n == 1);
                in.kind = SEQ;
                in.uses = regs(in, 1, 1);
                in.defs = new int[] {in.d};
                break;
            case Op.FIELDS:
                need(in, n == 1);
                in.kind = SEQ;
                in.uses = regs(in, 1, 1);
                in.defs = range(in.d, in.x);
                break;
            case Op.JMP:
                need(in, n == 1);
                in.kind = JMP;
                in.succPc = new int[] {pay(in.o[1])};
                return;
            case Op.BKIND:
                need(in, n == 3);
                in.kind = COND;
                in.uses = regs(in, 1, 1);
                in.succPc = new int[] {in.nextPc, pay(in.o[3])};
                return;
            case Op.BEQ:
            case Op.BNE:
                need(in, n == 3);
                in.kind = COND;
                in.uses = regs(in, 1, 2);
                in.succPc = new int[] {in.nextPc, pay(in.o[3])};
                return;
            case Op.BUNREAD:
                need(in, n == 4);
                in.kind = STEP;
                in.generic = true;
                in.uses = regs(in, 1, 1);
                in.condDefs = new int[] {in.d};
                in.succPc = new int[] {in.nextPc, pay(in.o[4])};
                return;
            case Op.SWITCH: {
                need(in, n >= 3);
                in.kind = MULTI;
                in.uses = regs(in, 1, 1);
                in.succPc = new int[Math.max(0, n - 2)];
                for (int j = 0; j < in.succPc.length; j++) {
                    in.succPc[j] = pay(in.o[3 + j]);
                }
                return;
            }
            case Op.MATCH:
                need(in, n == 4);
                in.kind = STEP;
                in.generic = true;
                // the pattern is matched against the telescope variables g_0 .. g_(n-1) of the window
                in.uses = n == 4 ? concat(regs(in, 1, 1), range(0, pay(in.o[3]))) : new int[0];
                in.succPc = new int[] {in.nextPc, n == 4 ? pay(in.o[4]) : in.nextPc};
                return;
            case Op.RET:
            case Op.TRET:
                need(in, n == 1);
                in.kind = EXIT;
                in.uses = regs(in, 1, 1);
                return;
            case Op.APPLY:
                need(in, n >= 1);
                in.kind = SEQ;
                in.uses = regs(in, 1, n);
                in.defs = new int[] {in.d};
                in.classJ = true;
                break;
            case Op.TAILAPPLY:
                need(in, n >= 1);
                in.kind = EXIT;
                in.uses = regs(in, 1, n);
                in.classJ = true;
                return;
            case Op.WHNF:
            case Op.FORCE:
                need(in, n == 1);
                in.kind = SEQ;
                in.uses = regs(in, 1, 1);
                in.defs = new int[] {in.d};
                in.classJ = true;
                break;
            case Op.TARG: {
                // Four phases (NOTES-M7 D4), all on the telescope variables and the site registers, which are
                // pinned in a TELE block; the value operands are read from the window by the body.
                in.kind = STEP;
                in.generic = true;
                in.classJ = true;
                in.uses = regs(in, 1, n);
                ArrayList<Integer> s = new ArrayList<>();
                s.add(in.nextPc);
                for (int i = 1; i <= n; i++) {
                    if (mode(in.o[i]) == Op.MODE_B) {
                        s.add(pay(in.o[i]));
                    }
                }
                if ((in.w & T2Bridge.PH_MASK) == T2Bridge.PH_INFER && in.d > 0 && in.d < nentry && entryPc[in.d] != 0) {
                    s.add(entryPc[in.d]); // "i >= n: continue at the entry that returns the remaining Pi"
                }
                in.succPc = toArray(s);
                if (role != Op.ROLE_TELE) {
                    in.known = false; // the site registers are only known to be pinned in a TELE block
                }
                return;
            }
            default: {
                // Unknown to the tier: the stepper's body executes it on the window (the whole block then keeps
                // its registers there). Successors: the fall-through and every label operand.
                in.kind = STEP;
                in.generic = true;
                in.known = false;
                in.classJ = true;
                ArrayList<Integer> s = new ArrayList<>();
                s.add(in.nextPc);
                for (int i = 1; i <= n; i++) {
                    if (mode(in.o[i]) == Op.MODE_B) {
                        s.add(pay(in.o[i]));
                    }
                }
                in.succPc = toArray(s);
                return;
            }
        }
        in.succPc = new int[] {in.nextPc};
    }

    private void need(Insn in, boolean ok) {
        if (!ok && hostile == null) {
            hostile = "malformed instruction at " + in.pc + " (op " + Integer.toHexString(in.op) + ", n " + in.n + ")";
        }
    }

    private static int[] toArray(ArrayList<Integer> s) {
        int[] r = new int[s.size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = s.get(i);
        }
        return r;
    }

    /** Backward liveness of the slot registers (pinned registers are always in the window and are left out). */
    private void liveness() {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = insn.length - 1; i >= 0; i--) {
                Insn in = insn[i];
                BitSet out = new BitSet();
                for (int s : in.succ) {
                    if (s >= 0) {
                        out.or(insn[s].liveIn);
                    }
                }
                BitSet li = (BitSet) out.clone();
                for (int r : in.defs) {
                    li.clear(r);
                }
                for (int r : in.uses) {
                    if (!pinned[r]) {
                        li.set(r);
                    }
                }
                for (int r : in.condDefs) {
                    if (!pinned[r]) {
                        li.set(r); // a register that is written on one path only keeps its old value on the other
                    }
                }
                if (!li.equals(in.liveIn) || !out.equals(in.liveOut)) {
                    in.liveIn = li;
                    in.liveOut = out;
                    changed = true;
                }
            }
        }
    }

    /** The slot registers of {@code set} as an array. */
    static int[] list(BitSet set) {
        int[] r = new int[set.cardinality()];
        int k = 0;
        for (int i = set.nextSetBit(0); i >= 0; i = set.nextSetBit(i + 1)) {
            r[k++] = i;
        }
        return r;
    }

    /** Slot registers among {@code regs}. */
    int[] slotsOf(int[] regs) {
        BitSet s = new BitSet();
        for (int r : regs) {
            if (!pinned[r]) {
                s.set(r);
            }
        }
        return list(s);
    }

    /**
     * The pcs at which an activation of this block can be entered: the entries of its entry table and, if
     * {@code resume}, the instruction after every class-j instruction (the resume pc of its frame).
     */
    int[] entryPoints(boolean resume) {
        BitSet s = new BitSet();
        for (int e : entryPc) {
            if (e != 0 && e < len && indexOfPc[e] >= 0) {
                s.set(e);
            }
        }
        if (resume) {
            for (Insn in : insn) {
                if (in.classJ) {
                    for (int t : in.succ) {
                        if (t >= 0) {
                            s.set(insn[t].pc);
                        }
                    }
                }
            }
        }
        return list(s);
    }
}
