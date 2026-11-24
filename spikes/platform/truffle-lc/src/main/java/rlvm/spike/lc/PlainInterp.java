package rlvm.spike.lc;

import rlvm.spike.lc.Expr.App;
import rlvm.spike.lc.Expr.Arg;
import rlvm.spike.lc.Expr.Cap;
import rlvm.spike.lc.Expr.GlobalRef;
import rlvm.spike.lc.Expr.If;
import rlvm.spike.lc.Expr.Lam;
import rlvm.spike.lc.Expr.Let;
import rlvm.spike.lc.Expr.Lit;
import rlvm.spike.lc.Expr.Local;
import rlvm.spike.lc.Expr.Prim;
import rlvm.spike.lc.Expr.Program;

/**
 * Baseline: a plain-Java "closure compiled" tree-walking interpreter with no Truffle dependency. It is
 * JIT-compiled by the host compiler (C2 / Graal) like any Java program, or AOT-compiled by
 * native-image. This is the yardstick for "is Truffle worth it" under an instruction budget.
 */
public final class PlainInterp {
    private PlainInterp() {
    }

    public abstract static class PNode {
        abstract Object exec(Object[] args, Object[] caps, Object[] locals);
    }

    public static final class PClosure {
        final PLam code;
        final Object[] caps;

        PClosure(PLam code, Object[] caps) {
            this.code = code;
            this.caps = caps;
        }

        public Object call(Object... args) {
            return code.body.exec(args, caps, code.numLocals == 0 ? null : new Object[code.numLocals]);
        }
    }

    static final class PLit extends PNode {
        final Object value;

        PLit(long v) {
            this.value = v;
        }

        @Override
        Object exec(Object[] args, Object[] caps, Object[] locals) {
            return value;
        }
    }

    static final class PArg extends PNode {
        final int index;

        PArg(int index) {
            this.index = index;
        }

        @Override
        Object exec(Object[] args, Object[] caps, Object[] locals) {
            return args[index];
        }
    }

    static final class PCap extends PNode {
        final int index;

        PCap(int index) {
            this.index = index;
        }

        @Override
        Object exec(Object[] args, Object[] caps, Object[] locals) {
            return caps[index];
        }
    }

    static final class PLocal extends PNode {
        final int slot;

        PLocal(int slot) {
            this.slot = slot;
        }

        @Override
        Object exec(Object[] args, Object[] caps, Object[] locals) {
            return locals[slot];
        }
    }

    /** Own cell per global: the plain back-end must not touch Truffle classes (no runtime init). */
    static final class PGlobal extends PNode {
        final Object[] cell;

        PGlobal(Object[] cell) {
            this.cell = cell;
        }

        @Override
        Object exec(Object[] args, Object[] caps, Object[] locals) {
            return cell[0];
        }
    }

    private static final java.util.IdentityHashMap<Global, Object[]> CELLS = new java.util.IdentityHashMap<>();

    private static Object[] cell(Global g) {
        return CELLS.computeIfAbsent(g, k -> new Object[1]);
    }

    static final class PLam extends PNode {
        final int arity;
        final int numLocals;
        final PNode[] captures;
        PNode body;

        PLam(int arity, int numLocals, PNode[] captures) {
            this.arity = arity;
            this.numLocals = numLocals;
            this.captures = captures;
        }

        @Override
        Object exec(Object[] args, Object[] caps, Object[] locals) {
            Object[] vals = new Object[captures.length];
            for (int i = 0; i < vals.length; i++) {
                vals[i] = captures[i].exec(args, caps, locals);
            }
            return new PClosure(this, vals);
        }
    }

    static final class PApp extends PNode {
        final PNode fn;
        final PNode[] argNodes;

        PApp(PNode fn, PNode[] argNodes) {
            this.fn = fn;
            this.argNodes = argNodes;
        }

        @Override
        Object exec(Object[] args, Object[] caps, Object[] locals) {
            PClosure c = (PClosure) fn.exec(args, caps, locals);
            Object[] argv = new Object[argNodes.length];
            for (int i = 0; i < argv.length; i++) {
                argv[i] = argNodes[i].exec(args, caps, locals);
            }
            PLam code = c.code;
            if (code.arity != argv.length) {
                throw new IllegalStateException("arity mismatch");
            }
            return code.body.exec(argv, c.caps, code.numLocals == 0 ? null : new Object[code.numLocals]);
        }
    }

    static final class PPrim extends PNode {
        final char op;
        final PNode left;
        final PNode right;

        PPrim(char op, PNode left, PNode right) {
            this.op = op;
            this.left = left;
            this.right = right;
        }

        @Override
        Object exec(Object[] args, Object[] caps, Object[] locals) {
            long a = (Long) left.exec(args, caps, locals);
            long b = (Long) right.exec(args, caps, locals);
            return switch (op) {
                case '+' -> a + b;
                case '-' -> a - b;
                case '*' -> a * b;
                case '<' -> a < b;
                case '=' -> a == b;
                default -> throw new IllegalStateException();
            };
        }
    }

    static final class PIf extends PNode {
        final PNode cond;
        final PNode then;
        final PNode otherwise;

        PIf(PNode cond, PNode then, PNode otherwise) {
            this.cond = cond;
            this.then = then;
            this.otherwise = otherwise;
        }

        @Override
        Object exec(Object[] args, Object[] caps, Object[] locals) {
            return (Boolean) cond.exec(args, caps, locals) ? then.exec(args, caps, locals) : otherwise.exec(args, caps, locals);
        }
    }

    static final class PLet extends PNode {
        final int slot;
        final PNode init;
        final PNode body;

        PLet(int slot, PNode init, PNode body) {
            this.slot = slot;
            this.init = init;
            this.body = body;
        }

        @Override
        Object exec(Object[] args, Object[] caps, Object[] locals) {
            locals[slot] = init.exec(args, caps, locals);
            return body.exec(args, caps, locals);
        }
    }

    static PNode compile(Expr e) {
        return switch (e) {
            case Lit l -> new PLit(l.value());
            case Arg a -> new PArg(a.index());
            case Cap c -> new PCap(c.index());
            case Local l -> new PLocal(l.slot());
            case GlobalRef g -> new PGlobal(cell(g.global()));
            case Lam l -> compileLam(l);
            case App a -> new PApp(compile(a.fn()), compileAll(a.args()));
            case Prim p -> new PPrim(p.op(), compile(p.left()), compile(p.right()));
            case If i -> new PIf(compile(i.cond()), compile(i.then()), compile(i.otherwise()));
            case Let l -> new PLet(l.slot(), compile(l.init()), compile(l.body()));
        };
    }

    static PLam compileLam(Lam l) {
        PLam p = new PLam(l.arity(), l.numLocals(), compileAll(l.captures()));
        p.body = compile(l.body());
        return p;
    }

    static PNode[] compileAll(Expr[] es) {
        PNode[] r = new PNode[es.length];
        for (int i = 0; i < es.length; i++) {
            r[i] = compile(es[i]);
        }
        return r;
    }

    /** Runs the definitions and returns the value of the result expression. */
    public static Object run(Program p) {
        for (int i = 0; i < p.globals().length; i++) {
            cell(p.globals()[i])[0] = new PClosure(compileLam(p.inits()[i]), new Object[0]).call();
        }
        return new PClosure(compileLam(p.main()), new Object[0]).call();
    }
}
