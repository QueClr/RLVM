package rlvm.machine;

import static rlvm.machine.Op.SIG;

import rlvm.api.MachineErrors;

/**
 * Toy judgments in family EXT, written exactly as the host convention prescribes
 * (*agentic-note*: rep-stepper-convention, H2-H4, H11): the tests of the control primitives run them under several
 * depth bounds. They are also the smallest complete examples of the step template.
 */
final class Toy implements Machine.Resumer {

    static final int SUM = 0x70;
    static final int TREE = 0x71;
    static final int FIB = 0x72;
    static final int LOOP = 0x73;
    static final int BIND = 0x74;
    static final int OUTER = 0x75;
    static final int NEST = 0x76;
    static final int BIG = 0x77;

    static final int K_SUM = SUM << 4;
    static final int K_SUM_1 = K_SUM | 1;
    static final int K_TREE = TREE << 4;
    static final int K_TREE_1 = K_TREE | 1;
    static final int K_TREE_2 = K_TREE | 2;
    static final int K_FIB = FIB << 4;
    static final int K_FIB_1 = K_FIB | 1;
    static final int K_FIB_2 = K_FIB | 2;
    static final int K_LOOP = LOOP << 4;
    static final int K_LOOP_1 = K_LOOP | 1;
    static final int K_BIND = BIND << 4;
    static final int K_BIND_1 = K_BIND | 1;
    static final int K_OUTER_1 = OUTER << 4 | 1;
    static final int K_NEST_1 = NEST << 4 | 1;
    static final int K_NEST_2 = NEST << 4 | 2;
    static final int K_BIG = BIG << 4;
    static final int K_BIG_1 = K_BIG | 1;

    static final int MASK = 0xFFFFF;
    /** BIND modes: what happens at the innermost level. */
    static final int OK = 0;
    static final int FAIL = 1;
    static final int FAULT = 2;

    static final Toy RESUMER = new Toy();

    /** A machine without a worker context, with the toy family installed. */
    static Machine machine(int hostDepth) {
        return machine(hostDepth, Machine.Sizes.standard());
    }

    static Machine machine(int hostDepth, Machine.Sizes sizes) {
        Machine m = new Machine(sizes);
        m.ext = RESUMER;
        m.hostDepth = hostDepth;
        m.stats = true;
        m.debug = Op.DBG_TRACE;
        return m;
    }

    /** Runs a judgment called at depth 0 to completion; after a signal the machine stacks are restored. */
    static int run(Machine m, int r) {
        r = m.drive(r);
        if (r >= SIG) {
            m.abandon();
        }
        return r;
    }

    // ---- SUM(n) = n + SUM(n - 1): a non-tail recursion of depth n ---------------------------------------

    static int sum(Machine m, int n, int d) {
        if (n == 0) {
            return 0; // 1. inline answer: no tick, no event, no frame
        }
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig; // 2. TICK
        }
        if (m.stats) {
            m.call(SUM); // 2b. call event
        }
        if (d > m.hostDepth) {
            return m.spill1(K_SUM, n); // 3. too deep: become the frame SUM[0](n)
        }
        int r = sum(m, n - 1, d + 1); // 4. call SUM(n - 1) => 1
        return r >= SIG ? m.sig1(r, K_SUM_1, n) : k_sum1(m, n, r, d);
    }

    static int k_sum1(Machine m, int n, int r, int d) {
        return (r + n) & MASK;
    }

    // ---- TREE(n): two sub-judgments, the result is an interned record ----------------------------------

    static int tree(Machine m, int n, int d) {
        if (n < 2) {
            return Layout.imm(n);
        }
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig;
        }
        if (m.stats) {
            m.call(TREE);
        }
        if (d > m.hostDepth) {
            return m.spill1(K_TREE, n);
        }
        int a = tree(m, n - 1, d + 1);
        return a >= SIG ? m.sig1(a, K_TREE_1, n) : k_tree1(m, n, a, d);
    }

    static int k_tree1(Machine m, int n, int a, int d) {
        int b = tree(m, n - 2, d + 1);
        return b >= SIG ? m.sig1(b, K_TREE_2, a) : k_tree2(m, a, b, d);
    }

    static int k_tree2(Machine m, int a, int b, int d) {
        return m.internRecord(Layout.w0(Layout.NSUC, 0, 0), a, b, 0);
    }

    // ---- FIB(n): the same with a memo on a key record (fast path = memo hit) ----------------------------

    static int fib(Machine m, int n, int d) {
        if (n < 2) {
            return Layout.imm(n);
        }
        int key = m.internRecord(Layout.w0(Layout.CONST, 0, 0), n, 0, 0);
        if (key >= SIG) {
            return key;
        }
        int memo = Values.memo(m, key, 0);
        if (memo != 0) {
            if (m.debug != 0 && Debug.memoEnter(m)) { // memocheck (ISA 5.5): the hook of a memo hit
                Values.setMemo(m, key, 0, 0); //   bypass the entry,
                int r = m.drive(fib(m, n, 0)); //   recompute (entry step at d = 0, then the driver),
                Values.setMemo(m, key, 0, memo); //   put the entry back,
                memo = Debug.memoExit(m, FIB, key, 0, memo, r); //   compare: memo, or FATAL (E_AUDIT)
            }
            if (m.stats) {
                m.hits[FIB]++; // an inline answer from a memo (ISA 14.5 hit rates); not a call event
            }
            return memo;
        }
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig;
        }
        if (m.stats) {
            m.call(FIB);
        }
        if (d > m.hostDepth) {
            return m.spill1(K_FIB, n);
        }
        int a = fib(m, n - 1, d + 1);
        return a >= SIG ? m.sig2(a, K_FIB_1, n, key) : k_fib1(m, n, key, a, d);
    }

    static int k_fib1(Machine m, int n, int key, int a, int d) {
        int b = fib(m, n - 2, d + 1);
        return b >= SIG ? m.sig2(b, K_FIB_2, key, a) : k_fib2(m, key, a, b, d);
    }

    static int k_fib2(Machine m, int key, int a, int b, int d) {
        int v = m.internRecord(Layout.w0(Layout.NSUC, 0, 0), a, b, 0);
        if (v < SIG) {
            Values.setMemo(m, key, 0, v); // I-MEMO: written by the step that completes the judgment
        }
        return v;
    }

    // ---- LOOP(n): a loop inside one judgment (H3 "inside a loop") ---------------------------------------

    static int loop(Machine m, int n, int d) {
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig;
        }
        if (m.stats) {
            m.call(LOOP);
        }
        if (d > m.hostDepth) {
            return m.spill1(K_LOOP, n);
        }
        return loopFrom(m, n, 0, 0, d);
    }

    static int loopFrom(Machine m, int n, int i, int acc, int d) {
        for (; i < n; i++) {
            int r = sum(m, i % 5, d + 1); // call SUM => 1
            if (r >= SIG) {
                return m.sig3(r, K_LOOP_1, n, i, acc);
            }
            acc += r;
        }
        return acc & MASK;
    }

    static int k_loop1(Machine m, int n, int i, int acc, int r, int d) {
        return loopFrom(m, n, i + 1, acc + r, d); // finish the iteration, re-enter the loop
    }

    // ---- BIND(type, n, mode): n nested binders, each with one scratch word and one environment word ----

    static int bind(Machine m, int type, int n, int mode, int d) {
        if (n == 0) {
            return mode == OK ? 0 : mode == FAIL ? m.fail(MachineErrors.E_TYPE_MISMATCH) : m.fault(MachineErrors.E_HOLE);
        }
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig;
        }
        if (m.stats) {
            m.call(BIND);
        }
        if (d > m.hostDepth) {
            return m.spill3(K_BIND, type, n, mode);
        }
        int x = Scope.open(m, type);
        if (x >= SIG) {
            return x;
        }
        if (!m.ensureScratch(1) || !m.ensureEnv(1)) {
            return m.fault(MachineErrors.E_MEM);
        }
        m.X[m.xp++] = x;
        m.es[m.ep++] = x;
        int r = bind(m, type, n - 1, mode, d + 1);
        return r >= SIG ? m.sig1(r, K_BIND_1, x) : k_bind1(m, x, r, d);
    }

    static int k_bind1(Machine m, int x, int r, int d) {
        m.ep--;
        m.xp--;
        Scope.close(m, 1);
        return (r * 31 + x) & MASK;
    }

    // ---- TRY segments -------------------------------------------------------------------------------------

    /** TRY budget; TREE(n): the tree, or U. */
    static int tryTree(Machine m, int n, long budget, int d) {
        int ssp = m.ssp, xp = m.xp, ep = m.ep, rp = m.rp, er = m.ER;
        long saved = m.tryEnter(budget);
        int r = tree(m, n, d + 1);
        return r == Op.SUSP ? m.saveTry(saved, budget, ssp, xp, ep, rp, er)
                : Frames.k_try(m, saved, budget, ssp, xp, ep, rp, er, r);
    }

    /** TRY budget; SUM(n). */
    static int trySum(Machine m, int n, long budget, int d) {
        int ssp = m.ssp, xp = m.xp, ep = m.ep, rp = m.rp, er = m.ER;
        long saved = m.tryEnter(budget);
        int r = sum(m, n, d + 1);
        return r == Op.SUSP ? m.saveTry(saved, budget, ssp, xp, ep, rp, er)
                : Frames.k_try(m, saved, budget, ssp, xp, ep, rp, er, r);
    }

    /** TRY budget; BIND(type, n, mode). */
    static int tryBind(Machine m, int type, int n, int mode, long budget, int d) {
        int ssp = m.ssp, xp = m.xp, ep = m.ep, rp = m.rp, er = m.ER;
        long saved = m.tryEnter(budget);
        int r = bind(m, type, n, mode, d + 1);
        return r == Op.SUSP ? m.saveTry(saved, budget, ssp, xp, ep, rp, er)
                : Frames.k_try(m, saved, budget, ssp, xp, ep, rp, er, r);
    }

    /** OUTER(type, n, mode, budget): opens a binder of its own, runs the segment, maps U to 777, closes. */
    static int outer(Machine m, int type, int n, int mode, int budget, int d) {
        int x = Scope.open(m, type);
        if (x >= SIG) {
            return x;
        }
        int r = tryBind(m, type, n, mode, budget, d + 1);
        return r >= SIG ? m.sig0(r, K_OUTER_1) : k_outer1(m, r, d);
    }

    static int k_outer1(Machine m, int r, int d) {
        Scope.close(m, 1);
        return r == Layout.U ? 777 : r;
    }

    /** NEST(n, inner, outerBudget): TRY outerBudget { TRY inner; TREE(n) => U -> 5 } => U -> 9. */
    static int nest(Machine m, int n, int inner, int outerBudget, int d) {
        int ssp = m.ssp, xp = m.xp, ep = m.ep, rp = m.rp, er = m.ER;
        long saved = m.tryEnter(outerBudget);
        int r = nestInner(m, n, inner, d + 1);
        return r == Op.SUSP ? m.saveTry(saved, outerBudget, ssp, xp, ep, rp, er)
                : Frames.k_try(m, saved, outerBudget, ssp, xp, ep, rp, er, r);
    }

    static int nestInner(Machine m, int n, int inner, int d) {
        int r = tryTree(m, n, inner, d + 1);
        return r >= SIG ? m.sig0(r, K_NEST_1) : k_nest1(m, r, d);
    }

    static int k_nest1(Machine m, int r, int d) {
        if (r == Layout.U) {
            // the segment was abandoned; do a little more work under the enclosing budget
            int s = sum(m, 3, d + 1); // call SUM(3) => 2 (not a tail call: the result is replaced)
            return s >= SIG ? m.sig0(s, K_NEST_2) : k_nest2(m, s, d);
        }
        return r;
    }

    static int k_nest2(Machine m, int r, int d) {
        return 5;
    }

    // ---- BIG(a0..a9, n): a frame with 12 payload words, and sigX ----------------------------------------

    static int big(Machine m, int a0, int a1, int a2, int a3, int a4, int a5, int a6, int a7, int a8, int a9, int n,
            int d) {
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig;
        }
        if (m.stats) {
            m.call(BIG);
        }
        if (d > m.hostDepth) {
            return m.spill11(K_BIG, a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, n);
        }
        int r = sum(m, n, d + 1);
        return r >= SIG ? m.sig12(r, K_BIG_1, a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, n, 42)
                : k_big1(m, a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, n, 42, r, d);
    }

    static int k_big1(Machine m, int a0, int a1, int a2, int a3, int a4, int a5, int a6, int a7, int a8, int a9, int n,
            int k, int r, int d) {
        return (a0 + 2 * a1 + 3 * a2 + 4 * a3 + 5 * a4 + 6 * a5 + 7 * a6 + 8 * a7 + 9 * a8 + 10 * a9 + n + k + r) & MASK;
    }

    // ---- the family's part of the driver (H6) -----------------------------------------------------------

    @Override
    public int resume(Machine m, int key, int[] w, int p, int r) {
        switch (key) {
            case K_SUM:
                return sum(m, w[p], 0);
            case K_SUM_1:
                return k_sum1(m, w[p], r, 0);
            case K_TREE:
                return tree(m, w[p], 0);
            case K_TREE_1:
                return k_tree1(m, w[p], r, 0);
            case K_TREE_2:
                return k_tree2(m, w[p], r, 0);
            case K_FIB:
                return fib(m, w[p], 0);
            case K_FIB_1:
                return k_fib1(m, w[p], w[p + 1], r, 0);
            case K_FIB_2:
                return k_fib2(m, w[p], w[p + 1], r, 0);
            case K_LOOP:
                return loop(m, w[p], 0);
            case K_LOOP_1:
                return k_loop1(m, w[p], w[p + 1], w[p + 2], r, 0);
            case K_BIND:
                return bind(m, w[p], w[p + 1], w[p + 2], 0);
            case K_BIND_1:
                return k_bind1(m, w[p], r, 0);
            case K_OUTER_1:
                return k_outer1(m, r, 0);
            case K_NEST_1:
                return k_nest1(m, r, 0);
            case K_NEST_2:
                return k_nest2(m, r, 0);
            case K_BIG:
                return big(m, w[p], w[p + 1], w[p + 2], w[p + 3], w[p + 4], w[p + 5], w[p + 6], w[p + 7], w[p + 8], w[p + 9],
                        w[p + 10], 0);
            case K_BIG_1:
                return k_big1(m, w[p], w[p + 1], w[p + 2], w[p + 3], w[p + 4], w[p + 5], w[p + 6], w[p + 7], w[p + 8],
                        w[p + 9], w[p + 10], w[p + 11], r, 0);
            default:
                return m.fault(MachineErrors.E_INTERNAL);
        }
    }
}
