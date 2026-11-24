package rlvm.spike.machine.truffle;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;

import rlvm.spike.machine.Elab;
import rlvm.spike.machine.Machine;
import rlvm.spike.machine.Vals;

/**
 * Variant (c): a code object promoted to Truffle. One root per constant; one node per instruction with the
 * operands as partial-evaluation constants; temporaries live in (virtual) frame slots; arguments and outputs stay
 * in the machine stack S exactly as for the plain stepper.
 */
public final class TelRoot extends RootNode {
    private final int n;
    /** The arities (number of supplied arguments, capped at n) this root was compiled for, ascending. */
    @CompilationFinal(dimensions = 1) private final int[] arities;
    private final String name;
    @Children private final Block[] dom;
    @Children private final Block[] res;

    private TelRoot(TelLanguage lang, FrameDescriptor fd, String name, int n, int[] arities, Block[] dom, Block[] res) {
        super(lang, fd);
        this.name = name;
        this.n = n;
        this.arities = arities;
        this.dom = dom;
        this.res = res;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }

    /**
     * One straight-line sequence per profiled arity: domain blocks 0..j-1, then the result block for j.
     * An arity that was never seen before promotion returns FALSE and the caller steps it (no deoptimisation).
     */
    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        final int[] S = Machine.S;
        final int fp = Machine.fp;
        final int k = Machine.k;
        final int j = k < n ? k : n;
        for (int a = 0; a < arities.length; a++) {
            if (j == arities[a]) {
                runArity(frame, S, fp, arities[a]);
                return Boolean.TRUE;
            }
        }
        return Boolean.FALSE;
    }

    @ExplodeLoop
    private void runArity(VirtualFrame frame, int[] S, int fp, int arity) {
        for (int i = 0; i < arity; i++) {
            dom[i].exec(frame, S, fp);
        }
        res[arity].exec(frame, S, fp);
    }

    // ------------------------------------------------------------------ building a root from a code object

    /**
     * @param seen arities to compile (bit j = j arguments); blocks of other arities are not even built
     * @param inline 0 = every operation is a call (call-threaded), 1 = APP/PROJ probes inlined, 2 = closures too
     */
    public static TelRoot build(TelLanguage lang, String name, int co, long seen, int inline) {
        int[] code = Elab.CODE;
        int n = code[co];
        int frameSize = code[co + 1];
        int tb = 2 * n + 1;
        FrameDescriptor.Builder fb = FrameDescriptor.newBuilder();
        fb.addSlots(frameSize - tb, FrameSlotKind.Static);
        int maxSeen = 63 - Long.numberOfLeadingZeros(seen);
        Block[] dom = new Block[maxSeen];
        for (int i = 0; i < maxSeen; i++) {
            dom[i] = block(code, code[co + 2 + i], code[co + 3 + i], tb, frameSize, inline);
        }
        Block[] res = new Block[maxSeen + 1];
        int[] arities = new int[Long.bitCount(seen)];
        int na = 0;
        for (int j = 0; j <= maxSeen; j++) {
            boolean used = ((seen >>> j) & 1) != 0;
            res[j] = used ? block(code, code[co + 3 + n + j], code[co + 4 + n + j], tb, frameSize, inline) : new Block(new Instr[0]);
            if (used) {
                arities[na++] = j;
            }
        }
        return new TelRoot(lang, fb.build(), name, n, arities, dom, res);
    }

    private static Block block(int[] code, int pc, int end, int tb, int frameSize, int inline) {
        java.util.ArrayList<Instr> list = new java.util.ArrayList<>();
        while (pc < end) {
            int w = code[pc];
            int dst = w >>> 8;
            switch (w & 0xff) {
                case Elab.OP_MOV:
                    list.add(new Mov(tb, dst, code[pc + 1]));
                    pc += 2;
                    break;
                case Elab.OP_APP1, Elab.OP_APP2, Elab.OP_APP3: {
                    int na = (w & 0xff) - Elab.OP_APP1 + 1;
                    list.add(new App(tb, dst, code[pc + 1], java.util.Arrays.copyOfRange(code, pc + 2, pc + 2 + na), inline >= 1));
                    pc += 2 + na;
                    break;
                }
                case Elab.OP_APPN: {
                    int na = code[pc + 1];
                    list.add(new App(tb, dst, code[pc + 2], java.util.Arrays.copyOfRange(code, pc + 3, pc + 3 + na), inline >= 1));
                    pc += 3 + na;
                    break;
                }
                case Elab.OP_APPD: {
                    int na = code[pc + 1];
                    list.add(new AppD(tb, dst, code[pc + 2], java.util.Arrays.copyOfRange(code, pc + 3, pc + 3 + na), inline >= 1, frameSize));
                    pc += 3 + na;
                    break;
                }
                case Elab.OP_PROJ:
                    list.add(new Proj(tb, dst, code[pc + 1], code[pc + 2], inline >= 1));
                    pc += 3;
                    break;
                case Elab.OP_CLO: {
                    int hdr = code[pc + 1];
                    int nc = hdr >>> 4;
                    list.add(new Clo(tb, dst, hdr, code[pc + 2], java.util.Arrays.copyOfRange(code, pc + 3, pc + 3 + nc), inline >= 2, frameSize));
                    pc += 3 + nc;
                    break;
                }
                default:
                    throw new IllegalStateException("bad opcode");
            }
        }
        return new Block(list.toArray(new Instr[0]));
    }

    // ------------------------------------------------------------------ nodes

    static final class Block extends Node {
        @Children private final Instr[] instrs;

        Block(Instr[] instrs) {
            this.instrs = instrs;
        }

        @ExplodeLoop
        void exec(VirtualFrame frame, int[] S, int fp) {
            for (int i = 0; i < instrs.length; i++) {
                instrs[i].exec(frame, S, fp);
            }
        }
    }

    abstract static class Instr extends Node {
        /** First temporary slot (2n + 1): slots below live in S, slots from here on in the frame. */
        final int tb;
        final int dst;

        Instr(int tb, int dst) {
            this.tb = tb;
            this.dst = dst;
        }

        abstract void exec(VirtualFrame frame, int[] S, int fp);

        final int get(VirtualFrame frame, int[] S, int fp, int op) {
            if (op > 0) {
                return op;
            }
            int s = -op;
            return s < tb ? S[fp + s] : frame.getIntStatic(s - tb);
        }

        final void put(VirtualFrame frame, int[] S, int fp, int v) {
            if (dst < tb) {
                S[fp + dst] = v;
            } else {
                frame.setIntStatic(dst - tb, v);
            }
        }
    }

    static final class Mov extends Instr {
        final int src;

        Mov(int tb, int dst, int src) {
            super(tb, dst);
            this.src = src;
        }

        @Override
        void exec(VirtualFrame frame, int[] S, int fp) {
            put(frame, S, fp, get(frame, S, fp, src));
        }
    }

    static final class App extends Instr {
        final int head;
        @CompilationFinal(dimensions = 1) final int[] args;
        final boolean inline;

        App(int tb, int dst, int head, int[] args, boolean inline) {
            super(tb, dst);
            this.head = head;
            this.args = args;
            this.inline = inline;
        }

        @Override
        @ExplodeLoop
        void exec(VirtualFrame frame, int[] S, int fp) {
            int f = get(frame, S, fp, head);
            for (int i = 0; i < args.length; i++) {
                f = TOps.app(inline, f, get(frame, S, fp, args[i]));
            }
            put(frame, S, fp, f);
        }
    }

    static final class AppD extends Instr {
        final int head;
        @CompilationFinal(dimensions = 1) final int[] args;
        final boolean inline;
        final int frameSize;

        AppD(int tb, int dst, int head, int[] args, boolean inline, int frameSize) {
            super(tb, dst);
            this.head = head;
            this.args = args;
            this.inline = inline;
            this.frameSize = frameSize;
        }

        @Override
        @ExplodeLoop
        void exec(VirtualFrame frame, int[] S, int fp) {
            int f = get(frame, S, fp, head);
            boolean mayBeLam = true;
            for (int i = 0; i < args.length; i++) {
                int x = get(frame, S, fp, args[i]);
                if (mayBeLam && f > 0 && Vals.kindOf(f) == Vals.K_LAM) {
                    f = TOps.applyLam(f, x, fp + frameSize);
                } else {
                    f = TOps.app(inline, f, x);
                    mayBeLam = false;
                }
            }
            put(frame, S, fp, f);
        }
    }

    static final class Proj extends Instr {
        final int hdr;
        final int src;
        final boolean inline;

        Proj(int tb, int dst, int hdr, int src, boolean inline) {
            super(tb, dst);
            this.hdr = hdr;
            this.src = src;
            this.inline = inline;
        }

        @Override
        void exec(VirtualFrame frame, int[] S, int fp) {
            put(frame, S, fp, TOps.proj(inline, hdr, get(frame, S, fp, src)));
        }
    }

    static final class Clo extends Instr {
        final int hdr;
        final int e;
        @CompilationFinal(dimensions = 1) final int[] caps;
        final boolean inline;
        final int frameSize;

        Clo(int tb, int dst, int hdr, int e, int[] caps, boolean inline, int frameSize) {
            super(tb, dst);
            this.hdr = hdr;
            this.e = e;
            this.caps = caps;
            this.inline = inline;
            this.frameSize = frameSize;
        }

        @Override
        @ExplodeLoop
        void exec(VirtualFrame frame, int[] S, int fp) {
            int sp = fp + frameSize;
            for (int i = 0; i < caps.length; i++) {
                S[sp + i] = get(frame, S, fp, caps[i]);
            }
            put(frame, S, fp, TOps.clo(inline, hdr, e, S, sp, caps.length));
        }
    }
}
