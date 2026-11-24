package rlvm.machine;

import java.util.Arrays;
import rlvm.api.KernelException;
import rlvm.api.KernelOps;
import rlvm.api.LevelOps;
import rlvm.api.MachineErrors;
import rlvm.api.Outcome;
import rlvm.api.ResourceException;
import rlvm.api.WorkerContext;
import rlvm.store.IntLists;
import rlvm.store.TermOps;
import rlvm.store.Terms;

/**
 * {@link KernelOps} served by the machine, for differential tests only (ISA 14.6): never used by
 * admission, never returned by {@code Session.ops()} in a release configuration. Term results are read
 * back with {@code Quote} (test tree, M8) through {@link #quoter}. Owner: M6.
 *
 * <p>A kernel error surfaces as {@code KernelException} with the reason of ISA 14.3, a decline as
 * {@code ResourceException}.
 *
 * <h2>Terms in, terms out</h2>
 * The machine's term language has no free-variable node; {@code KernelOps} terms have ({@code Kind.FVAR},
 * made by {@link #mkLocal}). The bridge is the usual locally-nameless one:
 * <ul>
 *   <li>{@code mkLocal(type)} makes an overlay FVAR node and opens a machine binder {@code OPEN(EVAL(type))}
 *       (ISA 2.8). The locals of the current scope are {@code locals[0 .. n)}, their machine variables
 *       {@code SC[0 .. n)}, and the environment {@code es[0 .. n)} holds the same variables, so that local k
 *       is the de Bruijn index {@code n - 1 - k}.
 *   <li>A term handed in is abstracted over the locals ({@code TermOps.abstractFVars}) and evaluated or
 *       inferred under that environment with the identity substitution ({@code ls = 0}: the term's universe
 *       parameters are those of the declaration, ISA 2.7).
 *   <li>A value handed out is read back by the quoter, which renders the variables of the open binders as
 *       loose indices (binder depth = number of open binders), and is instantiated with the FVAR nodes
 *       ({@code TermOps.instantiateRev}).
 * </ul>
 * All locals stay open until the next {@link #begin} (ISA 14.6).
 */
public final class MachineOps implements KernelOps {

    /** Readback of a value into the worker's overlay (the test tree's {@code Quote}). */
    public interface Quoter {
        int quote(Machine m, int value);
    }

    final Machine m;
    final WorkerContext ctx;
    Quoter quoter;
    private TermOps termOps;
    /** The overlay FVAR nodes of the open locals, outermost first; {@code m.SC[k]} is the variable of {@code locals[k]}. */
    private int[] locals = new int[16];
    private int n;
    private int lastLparams = IntLists.EMPTY;
    private boolean begun;

    public MachineOps(Machine m, Quoter quoter) {
        this.m = m;
        this.ctx = m.ctx;
        this.quoter = quoter;
    }

    /**
     * ISA 14.6: sets {@code decl} and applies the generation rule of 5.3; closes all locals. Without
     * {@code Op.SW_WARM} (v0) and in strict mode the machine is reset: session = one declaration.
     */
    @Override
    public void begin(int rank, int levelParams) {
        m.abandonTo(0, 0);
        n = 0;
        ctx.terms.refresh();
        if (m.strict || !m.on(Op.SW_WARM)) {
            m.reset();
        } else if (begun && !subset(lastLparams, levelParams)) {
            m.gen++;
        }
        begun = true;
        lastLparams = levelParams;
        m.beginDecl(rank, -1, Op.FUEL_UNLIMITED, Long.MAX_VALUE);
    }

    private boolean subset(int sub, int sup) {
        if (sub == sup || sub == IntLists.EMPTY) {
            return true;
        }
        IntLists lists = ctx.lists;
        int a = lists.length(sub);
        int b = lists.length(sup);
        for (int i = 0; i < a; i++) {
            int name = lists.get(sub, i);
            boolean found = false;
            for (int j = 0; j < b && !found; j++) {
                found = lists.get(sup, j) == name;
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void exposeUnitMembers(int kindMask) {
        throw new UnsupportedOperationException("the machine does not run inductive admission (ISA 14.6)");
    }

    // ---- the bridge -------------------------------------------------------------------------------------

    /** The term with the open locals as loose indices; the environment slab holds exactly the locals. */
    private int in(int term) {
        if (m.ssp != n) {
            throw new IllegalStateException("machine scope (" + m.ssp + " binders) and KernelOps locals (" + n + ") differ");
        }
        m.ep = 0;
        if (!m.ensureEnv(n)) {
            throw new ResourceException(MachineErrors.reason(MachineErrors.E_MEM));
        }
        System.arraycopy(m.SC, 0, m.es, 0, n);
        m.ep = n;
        return termOps().abstractFVars(term, locals, 0, n);
    }

    /** Completes a judgment called at depth 0; a signal becomes the exception of ISA 14.6. */
    private int run(int r) {
        r = m.drive(r);
        if (r >= Op.SIG) {
            int code = m.err;
            m.abandonTo(n, n);
            m.err = 0;
            if (MachineErrors.outcome(code) == Outcome.REJECT) {
                throw new KernelException(MachineErrors.reason(code));
            }
            throw new ResourceException(MachineErrors.reason(code));
        }
        return r;
    }

    private int eval(int term) {
        int t = in(term);
        return run(Eval.eval(m, t, 0, n, 0, 0));
    }

    /** Reads a value back as a term over the locals. */
    private int out(int value) {
        if (quoter == null) {
            throw new UnsupportedOperationException("MachineOps needs a Quoter (the test tree's Quote, M8) to return terms");
        }
        int t = quoter.quote(m, value);
        return termOps().instantiateRev(t, locals, 0, n);
    }

    // ---- judgments (ISA 14.6 table) -----------------------------------------------------------------------

    /** INFER in ONLY mode, read back: equal to the official result up to definitional equality. */
    @Override
    public int infer(int term) {
        int t = in(term);
        return out(run(Infer.infer(m, t, 0, n, 0, Op.ONLY, 0)));
    }

    /** INFER in CHECKED mode, read back. */
    @Override
    public int check(int term) {
        int t = in(term);
        return out(run(Infer.infer(m, t, 0, n, 0, Op.CHECKED, 0)));
    }

    /** EVAL, WHNF, readback: a weak head normal form, not the official term ({@link #whnfIsSyntactic()} is false). */
    @Override
    public int whnf(int term) {
        return out(run(Reduce.whnf(m, eval(term), 0)));
    }

    /** EVAL, CORE (full, no delta, no Nat extension), readback. */
    @Override
    public int whnfCore(int term) {
        return out(run(Reduce.core(m, eval(term), Op.FULL, 0)));
    }

    /** EVAL of both terms, CONV: the exact yes / no. */
    @Override
    public boolean isDefEq(int a, int b) {
        int va = eval(a);
        int vb = eval(b);
        int r = run(Conv.conv(m, va, vb, 0));
        if (r != Layout.T && r != Layout.F) {
            throw new ResourceException(MachineErrors.reason(MachineErrors.E_INTERNAL));
        }
        return r == Layout.T;
    }

    /** EVAL, ENSORT: the level id; a kernel error TYPE_EXPECTED otherwise. */
    @Override
    public int ensureSort(int term) {
        return run(Infer.ensort(m, eval(term), 0));
    }

    /** EVAL, ENPI, readback of the PI value. */
    @Override
    public int ensurePi(int term) {
        return out(run(Infer.enpi(m, eval(term), 0)));
    }

    /** EVAL, ISPROP: throws as the official {@code is_prop} when the sort is stuck. */
    @Override
    public boolean isProp(int type) {
        int r = run(Infer.isprop(m, eval(type), 0));
        if (r != Layout.T && r != Layout.F) {
            throw new ResourceException(MachineErrors.reason(MachineErrors.E_INTERNAL));
        }
        return r == Layout.T;
    }

    /** An overlay FVAR node mapped to {@code OPEN(EVAL(type))}; open until the next {@link #begin}. */
    @Override
    public int mkLocal(int type) {
        int ty = eval(type);
        int x = Scope.open(m, ty);
        if (x >= Op.SIG) {
            run(x);
        }
        int node = ctx.overlay.mkFVar(type);
        if (n == locals.length) {
            locals = Arrays.copyOf(locals, n << 1);
        }
        locals[n++] = node;
        return node;
    }

    /** The machine variable of a local made by {@link #mkLocal} (tests), or 0. */
    int variableOf(int local) {
        for (int k = 0; k < n; k++) {
            if (locals[k] == local) {
                return m.SC[k];
            }
        }
        return 0;
    }

    @Override
    public Terms terms() {
        return ctx.terms;
    }

    @Override
    public TermOps termOps() {
        if (termOps == null) {
            termOps = ctx.levels instanceof rlvm.store.LevelInfo ? new TermOps(ctx.terms, ctx.lists, (rlvm.store.LevelInfo) ctx.levels) : new TermOps(ctx.terms, ctx.lists);
        }
        return termOps;
    }

    @Override
    public LevelOps levels() {
        return ctx.levels;
    }

    /** The machine's value is not the official term (ISA 14.6). */
    @Override
    public boolean whnfIsSyntactic() {
        return false;
    }

    @Override
    public long steps() {
        return m.ticks();
    }
}
