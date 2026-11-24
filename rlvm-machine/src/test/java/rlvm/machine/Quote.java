package rlvm.machine;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import rlvm.api.MachineErrors;
import rlvm.api.NatOps;
import rlvm.api.bind.NatBinding;
import rlvm.nat.Nat;
import rlvm.store.IntLists;
import rlvm.store.LevelInfo;
import rlvm.store.Overlay;
import rlvm.store.Specials;
import rlvm.store.TermOps;
import rlvm.store.Terms;

/**
 * Readback of a value into the worker's overlay: a test-only {@code QUOTE}, memoised by value and depth,
 * never on a checking path (ISA section 11, "differential testing"; 14.6). Owner: M8.
 *
 * <p>The term a value stands for (ISA section 2; the official term each record kind denotes):
 * <pre>
 * immediate n, NBIG            the Nat literal                                     (2.9)
 * STR                          the String literal
 * SORT l                       Sort l
 * CONST (row, levels)          the constant at these levels
 * NEU (head, spine)            head a1 .. an
 * PROJ (s, S, i)               s.i
 * NSUC (b, k)                  Nat.succ^k b
 * FVAR                         the term its binder is mapped to (below)
 * LAM / PI (t, frame, ls)      t with its loose variables instantiated by the frame's slots and its level
 * SUSP (t, frame, ls)            parameters by the substitution ls: the delayed `instantiate` of ISA 2.2 made
 *                                explicit. A HOLE slot is never read (mask contract, 2.4); if the result
 *                                does mention it, the readback fails: the mask under-approximated
 * PII (t, frame, ls)           (x : A) -> T: A is the lambda's domain instantiated as above; T is the readback
 *                                of INST(v, x) under a binder opened for it (ISA 4.3 INST: the type of the body)
 * </pre>
 * Closures and suspensions are therefore read back by substitution, without running the machine: the
 * result is what the value <em>means</em>, independent of how well the machine evaluates, which is what a
 * differential test wants to hand to the reference kernel. Only a PII needs judgments ({@code DOM},
 * {@code OPEN}, {@code INST}); they run at host depth 0 between two judgments of the test, tick and make
 * records like any other, and are the reason this must never be used on a checking path.
 *
 * <p><b>Free variables.</b> An FVAR that was given a term with {@link #local} (for example the local
 * constant the reference kernel made for the same binder) is read back as that term. Any other FVAR
 * must be in scope and becomes the loose bound variable of its binder: the innermost open binder is
 * {@code #0} (the convention of the fixture's context, where {@code f.term(..)} sees the names of
 * {@code f.fvar(..)} as loose variables). An FVAR that is neither is an error: ISA 2.8 I-FV3 says that
 * every variable reachable from the operands of a judgment is in scope.
 *
 * <p>The memo is keyed by (value, number of open binders) for values whose OPEN flag is set and by the
 * value alone otherwise; it is dropped when the machine was reset, when the overlay shrank, when the
 * open binders are not the ones of the last call, and when the locals change.
 */
final class Quote {

    /** The readback failed: a judgment it needed ended with a signal, or the value is not a readable value. */
    static final class QuoteFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int code;

        QuoteFailure(String message, int code) {
            super(message);
            this.code = code;
        }
    }

    /** Largest {@code k} of an NSUC cell that is expanded into {@code Nat.succ} applications. */
    static final int NSUC_MAX = 1 << 16;

    private static final Map<Machine, Quote> INSTANCES = new WeakHashMap<>();

    /**
     * The term the value stands for, with the free variables of the fixture's context as loose bound
     * variables (binder depth = number of open binders).
     */
    static int quote(Machine m, int value) {
        return of(m).quote(value);
    }

    /** The readback of machine {@code m} (one per machine; it keeps the locals and the memo). */
    static synchronized Quote of(Machine m) {
        return INSTANCES.computeIfAbsent(m, Quote::new);
    }

    private final Machine m;
    private final Overlay ov;
    private final Terms terms;
    private final TermOps ops;
    private final Map<Integer, Integer> locals = new HashMap<>();
    private final Map<Long, Integer> memo = new HashMap<>();
    private long memoResets = -1;
    private int memoNodes;
    private long memoScope;
    private int hole;
    private int[] one = new int[1];
    /** Readbacks that needed machine judgments (PII), for tests. */
    long judgments;

    private Quote(Machine m) {
        if (m.ctx == null) {
            throw new IllegalArgumentException("readback needs a machine with a worker context");
        }
        this.m = m;
        this.ov = m.ctx.overlay;
        this.terms = m.ctx.terms;
        this.ops = m.ctx.levels instanceof LevelInfo ? new TermOps(terms, m.ctx.lists, (LevelInfo) m.ctx.levels)
                : new TermOps(terms, m.ctx.lists);
    }

    /** From now on FVAR {@code fvar} is read back as the closed overlay term {@code term}. */
    void local(int fvar, int term) {
        if (m.vh[fvar << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT != Layout.FVAR) {
            throw new IllegalArgumentException("not an FVAR: " + Show.value(m, fvar));
        }
        locals.put(fvar, term);
        memo.clear();
    }

    /** Forgets every {@link #local}. */
    void clearLocals() {
        locals.clear();
        memo.clear();
    }

    /** Readback of {@code value} (class comment). */
    int quote(int value) {
        validate();
        terms.refresh();
        int ssp = m.ssp;
        int xp = m.xp;
        int ep = m.ep;
        int t;
        try {
            t = q(value);
        } catch (QuoteFailure e) {
            m.closeBindersAbove(ssp);
            m.xp = xp;
            m.ep = ep;
            throw e;
        }
        if (hole != 0 && terms.hasFVar(t)) {
            one[0] = hole;
            if (ops.abstractFVars(t, one, 0, 1) != t) {
                throw new QuoteFailure("the readback of " + Show.value(m, value) + " mentions a HOLE slot: a node mask"
                        + " under-approximates what its term reads (ISA 2.4 mask contract)", MachineErrors.E_HOLE);
            }
        }
        memoNodes = ov.nodeCount();
        return t;
    }

    private void validate() {
        long scope = 0x9E3779B97F4A7C15L + m.ssp;
        for (int i = 0; i < m.ssp; i++) {
            scope = (scope ^ m.SC[i]) * 0xFF51AFD7ED558CCDL;
        }
        if (m.resets != memoResets || ov.nodeCount() < memoNodes) {
            // value ids or overlay ids were given out again
            memo.clear();
            hole = 0;
            memoResets = m.resets;
        } else if (scope != memoScope) {
            memo.clear();
        }
        memoScope = scope;
    }

    private int q(int v) {
        if (v < 0) {
            return ov.mkNatLit(v & Layout.IMM_MASK);
        }
        if (v == Layout.HOLE) {
            return holeTerm();
        }
        if (v < m.vbase || v >= m.vp) {
            throw new QuoteFailure("not a value ref: " + Show.value(m, v), MachineErrors.E_INTERNAL);
        }
        int o = v << Layout.REC_SHIFT;
        int w0 = m.vh[o];
        boolean open = (w0 & Layout.W0_OPEN) != 0;
        long key = (long) (open ? m.ssp : -1) << 32 | v;
        Integer hit = memo.get(key);
        if (hit != null) {
            return hit;
        }
        int a = m.vh[o + Layout.A];
        int b = m.vh[o + Layout.B];
        int c = m.vh[o + Layout.C];
        int t;
        switch (w0 >>> Layout.KIND_SHIFT) {
            case Layout.SORT:
                t = ov.mkSort(a);
                break;
            case Layout.NBIG:
                t = (w0 & Layout.AUX_MASK) == Layout.NBIG_INLINE ? ov.mkNatLit((a & 0xFFFFFFFFL) | (long) b << 32) : bigLiteral(v);
                break;
            case Layout.STR:
                t = ov.mkStrLit(a);
                break;
            case Layout.LAM:
            case Layout.PI:
            case Layout.SUSP:
                t = subst(a, b, c);
                break;
            case Layout.PII:
                t = pii(v, a, b, c);
                break;
            case Layout.FVAR:
                t = variable(v);
                break;
            case Layout.CONST:
                t = ov.mkConst(a, b);
                break;
            case Layout.NEU: {
                int f = q(a);
                int n = Runs.len(m, b);
                int[] args = new int[n];
                for (int i = 0; i < n; i++) {
                    args[i] = q(Runs.arg(m, b, i));
                }
                t = ov.mkApps(f, args, 0, n);
                break;
            }
            case Layout.PROJ:
                t = ov.mkProj(b, c, q(a));
                break;
            case Layout.NSUC: {
                if (b < 1 || b > NSUC_MAX) {
                    throw new QuoteFailure("NSUC with k = " + b + " is not expanded", MachineErrors.E_LIMIT);
                }
                int succ = m.specials.cid(Specials.NAT_SUCC);
                if (succ <= 0) {
                    throw new QuoteFailure("an NSUC cell without a Nat.succ constant", MachineErrors.E_PRELUDE);
                }
                int s = ov.mkConst(succ, IntLists.EMPTY);
                t = q(a);
                for (int i = 0; i < b; i++) {
                    t = ov.mkApp(s, t);
                }
                break;
            }
            default:
                throw new QuoteFailure("a record of kind " + (w0 >>> Layout.KIND_SHIFT) + " is not a value: " + Show.value(m, v),
                        MachineErrors.E_INTERNAL);
        }
        memo.put(key, t);
        return t;
    }

    /** A Nat literal at or above 2^63: the record names a number of the worker's NatStore (ISA 2.2, 2.9; NOTES-M1 D3). */
    private int bigLiteral(int v) {
        NatOps nats = m.ctx.nats;
        if (!(nats instanceof NatBinding)) {
            throw new QuoteFailure("a NatStore literal without a NatBinding", MachineErrors.E_INTERNAL);
        }
        long handle;
        try {
            handle = Lits.natHandle(m, v);
        } catch (IllegalStateException e) {
            throw new QuoteFailure("stale NatStore literal: " + e.getMessage(), MachineErrors.E_INTERNAL);
        }
        // The record's number lives in the machine's number store (m.nats), which need not be the store the
        // binding caches literals in: move it there before asking for the literal term (M6, requests.log 08:48Z).
        NatBinding b = (NatBinding) nats;
        return b.termOf(Nat.transfer(m.nats, handle, b.store()));
    }

    private int holeTerm() {
        if (hole == 0) {
            hole = ov.mkFVar(ov.mkSort(m.levels.zero()));
        }
        return hole;
    }

    private int variable(int x) {
        Integer local = locals.get(x);
        if (local != null) {
            return local;
        }
        for (int p = m.ssp - 1; p >= 0; p--) {
            if (m.SC[p] == x) {
                return ov.mkBVar(m.ssp - 1 - p);
            }
        }
        throw new QuoteFailure("the free variable " + Show.value(m, x) + " is not bound by an open binder and has no local term"
                + " (ISA 2.8 I-FV3)", MachineErrors.E_INTERNAL);
    }

    /** Number of bindings a frame holds: its near slots plus, for a far frame, those of its REST run (ISA 2.4). */
    static int frameSize(Machine m, int frame) {
        return frame == Layout.RUN_EMPTY ? 0 : Runs.envLen(m, frame);
    }

    /** The binding of de Bruijn index {@code i} in a frame; index 0 is the innermost, a far frame continues in its REST run. */
    static int frameGet(Machine m, int frame, int i) {
        int hdr = m.rs[frame];
        int n = Layout.runLen(hdr);
        if (i < n) {
            return Runs.fget(m, frame, i);
        }
        int rest = m.rs[frame + Layout.RUN_REST_WORD];
        return Runs.fget(m, rest, i - n);
    }

    /**
     * {@code term} with its level parameters instantiated by {@code ls} and its loose variable i replaced by the
     * readback of the frame's binding i. The order matters: {@code ls} is the substitution of the constant the
     * TERM belongs to (ISA 2.7); the bindings are values of other contexts, already fully instantiated, and a
     * level parameter of the same name in them is another parameter (the callee's {@code u} against the
     * caller's {@code u}: RECONCILIATION B1).
     */
    private int subst(int term, int frame, int ls) {
        int n = frameSize(m, frame);
        int t = levels(term, ls);
        if (n > 0) {
            int[] args = new int[n];
            for (int i = 0; i < n; i++) {
                args[i] = q(frameGet(m, frame, i));
            }
            t = ops.instantiate(t, args, 0, n);
        }
        return t;
    }

    private int levels(int t, int ls) {
        if (ls == 0) {
            return t;
        }
        return ops.instantiateLevelParams(t, m.levels.substParams(ls), m.levels.substLevels(ls));
    }

    /** {@code (x : A) -> T} for a PII over the lambda {@code term}: the body's type is asked from the machine. */
    private int pii(int v, int term, int frame, int ls) {
        judgments++;
        // the domain as a term: the lambda's domain under the frame (no judgment)
        int n = frameSize(m, frame);
        int[] args = new int[n];
        for (int i = 0; i < n; i++) {
            args[i] = q(frameGet(m, frame, i));
        }
        int domTerm = levels(terms.a(term), ls); // levels first: see subst
        int dom = n == 0 ? domTerm : ops.instantiate(domTerm, args, 0, n);
        int d = run(Eval.dom(m, v, 0), "DOM", v);
        int x = Scope.open(m, d);
        if (x >= Op.SIG) {
            throw new QuoteFailure("OPEN failed while reading back " + Show.value(m, v), m.err);
        }
        int body = run(Eval.inst(m, v, x, 0), "INST", v);
        int tb = q(body);
        Scope.close(m, 1);
        return ov.mkPi(dom, tb);
    }

    private int run(int r, String what, int v) {
        r = m.drive(r);
        if (r >= Op.SIG) {
            int code = m.err;
            m.err = 0;
            throw new QuoteFailure(what + " failed with " + MachineErrors.name(code) + " while reading back " + Show.value(m, v), code);
        }
        return r;
    }
}
