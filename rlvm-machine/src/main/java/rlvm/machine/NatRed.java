package rlvm.machine;

import static rlvm.machine.Op.SIG;

import rlvm.api.MachineErrors;
import rlvm.nat.Nat;
import rlvm.nat.NatOp;
import rlvm.nat.NatStore;
import rlvm.store.IntLists;
import rlvm.store.Specials;

/**
 * Nat in the instruction set (ISA 2.9, 4.3; owner note 7): {@code NATOP}, {@code NATFOLD}, {@code OFFSET}.
 * Owner: M3.
 *
 * <p>Official rules: {@code reduce_nat} (R02 7.6) and {@code is_def_eq_offset} (R02 8.4). A <em>literal
 * operand</em> ({@code lit_ext}) is a Nat literal (immediate or NBIG) or the constant {@code Nat.zero} without
 * levels. The 14 binary functions are matched as the constant of that name without levels, whatever its
 * declaration kind; the prelude guards ({@code Specials}) are consulted at the moment an extension fires:
 * VALID: it fires; INVALID: {@code fault(E_PRELUDE)}, a decline; ABSENT: it does not fire (ISA 2.9;
 * {@link #guard}).
 *
 * <p>Frames: NATF[0](v), NATF[1](v), NATF[2](v, x); OFFSET[0](a, b) (it calls CONV in tail position, so it
 * has no continuation state).
 */
final class NatRed {
    private NatRed() {}

    static final int K_NATF_1 = Op.K_NATF | 1;
    static final int K_NATF_2 = Op.K_NATF | 2;

    // =====================================================================================================
    // NATOP (ISA 4.3; the table of R02 7.6)
    // =====================================================================================================

    /**
     * NATOP (ISA 4.3): one of the 14 binary accelerated functions ({@code rlvm.nat.NatOp} code) on two
     * {@code rlvm.nat} handles; the native 64-bit paths with their exact overflow guards are the inline fast
     * paths of {@code rlvm.nat.Nat} (owner note 7). {@code beq} / {@code ble} yield 1 / 0. Official: the table of R02 7.6
     * with its guards (128 MiB, exponent, shift bound).
     * @return the result handle (a number below 2^63, or {@code Nat.BIG | id}); an error handle
     *     ({@code Nat.isError}) = failure, with the code in {@code m.err} (E_NATSIZE: the official kernel throws)
     */
    static long natop(Machine m, int op, long a, long b) {
        long r;
        switch (op) {
            case NatOp.POW:
                r = Nat.pow(m.nats, a, b, Nat.OFFICIAL_MAX_BYTES);
                break;
            case NatOp.SHIFT_LEFT:
                r = Nat.shiftLeft(m.nats, a, b, Nat.OFFICIAL_MAX_BYTES);
                break;
            default:
                r = Nat.apply2(m.nats, op, a, b);
                break;
        }
        if (r < 0 && Nat.isError(r)) {
            m.err = MachineErrors.E_NATSIZE;
        }
        return r;
    }

    /**
     * The Specials index ({@code NAT_ADD .. NAT_SHIFT_RIGHT}) of a head that is one of the 14 binary functions:
     * a CONST record of that name without levels ({@code Const name []}, R02 7.6); else 0.
     */
    static int binaryOp(Machine m, int head) {
        int[] vh = m.vh;
        int o = head << Layout.REC_SHIFT;
        int w0 = vh[o];
        if ((w0 >>> Layout.KIND_SHIFT) != Layout.CONST || (w0 & Layout.CONST_NATOP_MASK) <= NatOp.SUCC) {
            return 0;
        }
        return (w0 & Layout.CONST_NATOP_MASK) - NatOp.ADD + Specials.NAT_ADD;
    }

    /** True for the constant {@code Nat.zero} without levels (the non-literal half of {@code lit_ext}). */
    static boolean isZeroConst(Machine m, int x) {
        int[] vh = m.vh;
        int o = x << Layout.REC_SHIFT;
        return (vh[o] >>> Layout.KIND_SHIFT) == Layout.CONST && vh[o + Layout.B] == IntLists.EMPTY
                && vh[o + Layout.A] == m.specials.cid(Specials.NAT_ZERO);
    }

    /**
     * {@code lit_ext} (R02 7.6): the {@code rlvm.nat} handle of a Nat literal, 0 for the constant {@code Nat.zero},
     * {@code Nat.NONE} for anything else.
     */
    static long litExt(Machine m, int x) {
        if (x < 0) {
            return x & Layout.IMM_MASK;
        }
        if (x <= Layout.HOLE) {
            return Nat.NONE;
        }
        int kind = m.vh[x << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT;
        if (kind == Layout.NBIG) {
            return Lits.natHandle(m, x);
        }
        return kind == Layout.CONST && isZeroConst(m, x) ? 0 : Nat.NONE;
    }

    /**
     * The prelude guard of special {@code which} at the moment an extension is about to fire (ISA 2.9): 1 = VALID
     * (fire); 0 = ABSENT (the extension does not fire: only hand-built stores have a named but unjudged special);
     * FATAL (E_PRELUDE) = INVALID (declared with another shape: a decline, also inside a speculative segment).
     */
    static int guard(Machine m, int which) {
        int st = m.specials.state(which);
        return st == Specials.VALID ? 1 : st == Specials.ABSENT ? 0 : m.fault(MachineErrors.E_PRELUDE);
    }

    /** True if the literal operand {@code x} is the constant {@code Nat.zero} (a CONST record), not a literal. */
    private static boolean isZeroRecord(Machine m, int x) {
        return x > Layout.HOLE && (m.vh[x << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT) == Layout.CONST;
    }

    /**
     * The Nat literal of a result handle of {@code rlvm.nat}: {@code TOO_BIG} / {@code COUNT_TOO_BIG} are the
     * official kernel's exceptions (R02 7.6): ABORT with E_NATSIZE; any other non-number is FATAL (E_INTERNAL).
     */
    static int natValue(Machine m, long h) {
        if (h >= 0) {
            return h < Layout.IMM_LIMIT ? (int) h | Layout.IMM : Values.mkNat(m, h);
        }
        if (Nat.isError(h)) {
            return h == Nat.TOO_BIG || h == Nat.COUNT_TOO_BIG ? m.fail(MachineErrors.E_NATSIZE) : m.fault(MachineErrors.E_INTERNAL);
        }
        return Values.mkNat(m, h);
    }

    /** True if {@code x} (not an immediate) is a literal operand: an NBIG record or the constant {@code Nat.zero}. */
    private static boolean isLitRecord(Machine m, int x) {
        if (x <= Layout.HOLE) {
            return false;
        }
        int kind = m.vh[x << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT;
        return kind == Layout.NBIG || (kind == Layout.CONST && isZeroConst(m, x));
    }

    // =====================================================================================================
    // NATFOLD (ISA 4.3; official reduce_nat, binary case)
    // =====================================================================================================

    /**
     * NATFOLD(v) (ISA 4.3): v is a NEU with a nat-op head and exactly 2 arguments: WHNF both; both literals: NATOP.
     * Official: {@code reduce_nat} (R02 7.6). Entry frame: NATF[0](v).
     * @return the literal (or Bool constant), {@code Layout.NONE}, or a signal
     */
    static int natFold(Machine m, int v, int d) {
        if (v <= Layout.HOLE) {
            return Layout.NONE; // 1. inline answers: not an application of a binary Nat function to two arguments
        }
        int o = v << Layout.REC_SHIFT;
        int w0 = m.vh[o];
        if ((w0 & (0xF << Layout.KIND_SHIFT | Layout.AUX_MASK)) != (Layout.NEU << Layout.KIND_SHIFT | 2)
                || binaryOp(m, m.vh[o + Layout.A]) == 0) {
            return Layout.NONE;
        }
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig; // 2. TICK
        }
        if (m.stats) {
            m.call(Op.NATF);
        }
        if (d > m.hostDepth) {
            return m.spill1(Op.K_NATF, v); // 3. too deep
        }
        NatStore s = m.nats;
        if (s != null && s.bytesUsed() >= m.x3.nestMinBytes && !m.strict) {
            int r = nestRootSlow(m, v); // a session of big numbers: the whole nest on handles (NOTES-M3 D16)
            if (r != Op.MISS) {
                return r;
            }
        }
        int x = Reduce.whnf(m, Runs.arg(m, m.vh[o + Layout.B], 0), d + 1); // call WHNF(arg 0) => 1
        return x >= SIG ? m.sig1(x, K_NATF_1, v) : k_natf1(m, v, x, d);
    }

    // =====================================================================================================
    // Nests of Nat functions on handles (NOTES-M3 D16): what reduce_nat computes, without keeping intermediates
    // =====================================================================================================
    //
    // `Nat.xor (Nat.lor x (Nat.add (Nat.mul a b) ..)) ..` on 600,000-bit literals (perf/gf3-matrix-product, the
    // CFSG certificates): the judgment NATFOLD normalises every inner application with WHNF, and each result
    // becomes an NBIG record whose number stays in the session's NatStore until the reset - 3.8 GB for three
    // matrix products, as in the official kernel's caches. A fold whose operands are, all the way down,
    // applications of the binary functions to literals needs no judgment: it is arithmetic. Such a nest is
    // evaluated here by a plain recursion on rlvm.nat handles in a scratch store; only the root's result enters
    // the session's store and the value heap. The scratch store is emptied between roots when it is large.
    // Shared sub-nests are computed once per scratch epoch (side memo, lossy, keyed by the value ref).
    //
    // Exactness: the result is the number the judgment computes (the same functions of rlvm.nat on the same
    // operands, the same guards); an inner error (TOO_BIG, COUNT_TOO_BIG) is the E_NATSIZE the official kernel
    // raises while it normalises the operands; anything that is not plainly a literal nest (an operand without a
    // literal WHNF memo, a predicate inside, a guard that is not VALID, depth or size beyond the bounds) hands the
    // root back to the judgment before any arithmetic is done. Inner applications get no WHNF memo (they are
    // recomputed if asked alone). Ticks: one per node evaluated, charged when the root completes.

    /** "Not a literal nest": never a number, never an error handle of rlvm.nat's operations. */
    private static final long NEST_FAIL = Nat.NONE;
    private static final int NEST_DEPTH = 48;
    private static final int NEST_WORK = 4096;

    /**
     * The fold of the root {@code v} (a NEU of a binary Nat function on two arguments) on handles.
     * @return the literal (or Bool constant), a signal (E_NATSIZE, E_PRELUDE, E_MEM, a budget), or
     *     {@code Op.MISS}: not a literal nest, the judgment takes over
     */
    private static int nestRootSlow(Machine m, int v) {
        Reduce.Ext e = m.x3;
        int[] vh = m.vh;
        int o = v << Layout.REC_SHIFT;
        int which = binaryOp(m, vh[o + Layout.A]);
        int run = vh[o + Layout.B];
        int x = Runs.arg(m, run, 0);
        int y = Runs.arg(m, run, 1);
        NatStore s = e.scratch;
        if (s != null && s.bytesUsed() > e.scratchLimit) {
            e.dropScratch(); // between two roots: no handle of the scratch store is held anywhere but in the side memo
        }
        e.nestWork = 0;
        if (m.specials.state(which) != Specials.VALID || !nestPure(m, x, 0) || !nestPure(m, y, 0)) {
            e.nestFallbacks++;
            return Op.MISS;
        }
        if (s == null) {
            NatStore base = m.nats.base();
            s = base != null && base.isFrozen() ? base.newOverlay(m.nats.maxBytes()) : NatStore.newBase(m.nats.maxBytes());
            e.scratch = s;
        }
        e.nestWork = 1;
        long a = nestEval(m, s, x, 0);
        long b = a == NEST_FAIL || Nat.isError(a) ? a : nestEval(m, s, y, 0);
        if (a == NEST_FAIL || b == NEST_FAIL) {
            e.nestFallbacks++;
            return Op.MISS; // the bounds were passed after all (a lossy memo slot was taken): nothing was decided
        }
        long r = Nat.isError(a) ? a : Nat.isError(b) ? b : apply(s, which, a, b);
        int t = m.tick(e.nestWork);
        if (t != 0) {
            return t;
        }
        e.nestRoots++;
        if ((which == Specials.NAT_BEQ || which == Specials.NAT_BLE) && r >= 0) {
            return boolConst(m, r != 0);
        }
        return natValue(m, r >= 0 || Nat.isError(r) ? r : Nat.transfer(s, r, m.nats));
    }

    /** One of the 14 functions on two handles of store {@code s}, with the official guards (see {@link #natop}). */
    private static long apply(NatStore s, int which, long a, long b) {
        switch (which) {
            case Specials.NAT_POW:
                return Nat.pow(s, a, b, Nat.OFFICIAL_MAX_BYTES);
            case Specials.NAT_SHIFT_LEFT:
                return Nat.shiftLeft(s, a, b, Nat.OFFICIAL_MAX_BYTES);
            default:
                return Nat.apply2(s, which - Specials.NAT_ADD + NatOp.ADD, a, b);
        }
    }

    /**
     * Is {@code x} a literal nest: a literal, the constant {@code Nat.zero} (guard VALID), a value whose WHNF memo
     * is a literal, or an application of one of the 12 number-valued binary functions (guard VALID) to two such
     * operands, within the depth and size bounds? Reads records only; {@code nestWork} counts the applications
     * visited (a shared one once per path), so the walk of a root is bounded by {@code NEST_WORK} visits.
     */
    private static boolean nestPure(Machine m, int x, int depth) {
        for (;;) {
            if (x < 0) {
                return true;
            }
            if (x <= Layout.HOLE) {
                return false;
            }
            int[] vh = m.vh;
            int o = x << Layout.REC_SHIFT;
            int w0 = vh[o];
            int kind = w0 >>> Layout.KIND_SHIFT;
            if (kind == Layout.NBIG) {
                return true;
            }
            if (kind == Layout.SUSP) {
                x = vh[o + Layout.M_VAL]; // a forced suspension stands for its value
                if (x == 0) {
                    return false;
                }
                continue;
            }
            if (kind < Layout.CONST || kind > Layout.NSUC) {
                return false;
            }
            if (kind == Layout.CONST && isZeroConst(m, x)) {
                return m.specials.state(Specials.NAT_ZERO) == Specials.VALID;
            }
            int w = vh[o + Layout.M_WHNF];
            if (w != 0) {
                return w < 0 || (vh[w << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT) == Layout.NBIG;
            }
            if (kind != Layout.NEU || (w0 & Layout.AUX_MASK) != 2 || depth >= NEST_DEPTH) {
                return false;
            }
            int which = binaryOp(m, vh[o + Layout.A]);
            if (which == 0 || which == Specials.NAT_BEQ || which == Specials.NAT_BLE || m.specials.state(which) != Specials.VALID) {
                return false;
            }
            if (nestKnown(m.x3, x)) {
                return true; // evaluated in this scratch epoch
            }
            if (++m.x3.nestWork > NEST_WORK) {
                return false; // a large nest is walked by the judgment, which memoises every node
            }
            int run = vh[o + Layout.B];
            if (!nestPure(m, Runs.arg(m, run, 0), depth + 1)) {
                return false;
            }
            x = Runs.arg(m, run, 1);
            depth++;
        }
    }

    /**
     * The number of the literal nest {@code x} as a handle of the scratch store {@code s}: a number below 2^63,
     * {@code Nat.BIG | id}, an error handle of an operation, or {@link #NEST_FAIL} (the work bound).
     */
    private static long nestEval(Machine m, NatStore s, int x, int depth) {
        if (x < 0) {
            return x & Layout.IMM_MASK;
        }
        int[] vh = m.vh;
        int o = x << Layout.REC_SHIFT;
        int w0 = vh[o];
        int kind = w0 >>> Layout.KIND_SHIFT;
        if (kind == Layout.SUSP) {
            return nestEval(m, s, vh[o + Layout.M_VAL], depth);
        }
        if (kind == Layout.NBIG) {
            return (w0 & Layout.AUX_MASK) == Layout.NBIG_INLINE ? Lits.natHandle(m, x) : nestImport(m, s, x);
        }
        if (kind == Layout.CONST && isZeroConst(m, x)) {
            return 0;
        }
        int w = vh[o + Layout.M_WHNF];
        if (w != 0) {
            return nestEval(m, s, w, depth); // a literal (nestPure)
        }
        return nestNode(m, s, x, depth);
    }

    /** True if the side memo holds the number of value {@code x} for the current scratch epoch. */
    private static boolean nestKnown(Reduce.Ext e, int x) {
        int slot = Hash.fin(x) & (Reduce.Ext.NEST_SLOTS - 1);
        return e.nestKey[slot] == x && e.nestStamp[slot] == e.scratchEpoch;
    }

    /** The number of an inner application of a literal nest (see {@link #nestEval}): side memo, else the operation. */
    private static long nestNode(Machine m, NatStore s, int x, int depth) {
        Reduce.Ext e = m.x3;
        int slot = Hash.fin(x) & (Reduce.Ext.NEST_SLOTS - 1);
        if (e.nestKey[slot] == x && e.nestStamp[slot] == e.scratchEpoch) {
            return e.nestVal[slot];
        }
        if (++e.nestWork > NEST_WORK || depth >= NEST_DEPTH) {
            return NEST_FAIL;
        }
        int[] vh = m.vh;
        int o = x << Layout.REC_SHIFT;
        int run = vh[o + Layout.B];
        long a = nestEval(m, s, Runs.arg(m, run, 0), depth + 1);
        if (a == NEST_FAIL || Nat.isError(a)) {
            return a;
        }
        long b = nestEval(m, s, Runs.arg(m, run, 1), depth + 1);
        if (b == NEST_FAIL || Nat.isError(b)) {
            return b;
        }
        long r = apply(s, binaryOp(m, vh[o + Layout.A]), a, b);
        if (!Nat.isError(r)) {
            e.nestKey[slot] = x;
            e.nestStamp[slot] = e.scratchEpoch;
            e.nestVal[slot] = r;
        }
        return r;
    }

    /** A stored literal of the session (an NBIG record over {@code m.nats}) as a handle of the scratch store, memoised. */
    private static long nestImport(Machine m, NatStore s, int lit) {
        Reduce.Ext e = m.x3;
        int slot = Hash.fin(lit) & (Reduce.Ext.NEST_SLOTS - 1);
        if (e.nestKey[slot] == lit && e.nestStamp[slot] == e.scratchEpoch) {
            return e.nestVal[slot];
        }
        long h = Nat.transfer(m.nats, Lits.natHandle(m, lit), s);
        e.nestKey[slot] = lit;
        e.nestStamp[slot] = e.scratchEpoch;
        e.nestVal[slot] = h;
        return h;
    }

    /** Frame NATF[1](v): r = WHNF(arg 0); not a literal operand: NONE (the second argument is not reduced). */
    static int k_natf1(Machine m, int v, int r, int d) {
        if (r >= 0 && !isLitRecord(m, r)) {
            return Layout.NONE;
        }
        int y = Reduce.whnf(m, Runs.arg(m, m.vh[(v << Layout.REC_SHIFT) + Layout.B], 1), d + 1); // call WHNF(arg 1) => 2
        return y >= SIG ? m.sig2(y, K_NATF_2, v, r) : k_natf2(m, v, r, y, d);
    }

    /** Frame NATF[2](v, x): r = WHNF(arg 1); both are literal operands: the fold. */
    static int k_natf2(Machine m, int v, int x, int r, int d) {
        if (r >= 0 && !isLitRecord(m, r)) {
            return Layout.NONE;
        }
        return fold(m, binaryOp(m, m.vh[(v << Layout.REC_SHIFT) + Layout.A]), x, r);
    }

    /**
     * The fold of {@code reduce_nat} on two literal operands. Guards first (ISA 2.9): the function's and, if an
     * operand is the constant {@code Nat.zero}, that constant's. Two immediates are folded with native arithmetic
     * when the result is certain to be exact (owner note 7); everything else goes through {@link #natop}.
     */
    private static int fold(Machine m, int which, int x, int y) {
        int g = guard(m, which);
        if (g == 1 && (x & y) >= 0 && (isZeroRecord(m, x) || isZeroRecord(m, y))) {
            g = guard(m, Specials.NAT_ZERO);
        }
        if (g != 1) {
            return g == 0 ? Layout.NONE : g;
        }
        if ((x & y) < 0) {
            long r = foldImm(which, x & Layout.IMM_MASK, y & Layout.IMM_MASK);
            if (r >= 0) {
                if (which == Specials.NAT_BEQ || which == Specials.NAT_BLE) {
                    return boolConst(m, r != 0);
                }
                return natValue(m, r);
            }
        }
        return foldSlow(m, which, x, y);
    }

    /**
     * Native arithmetic on two operands below 2^31: the exact result (below 2^63, so no size guard can fire), or
     * -1 for the cases left to {@code rlvm.nat} ({@code pow}, {@code gcd}, a shift count of 32 or more).
     */
    private static long foldImm(int which, long a, long b) {
        switch (which) {
            case Specials.NAT_ADD:
                return a + b;
            case Specials.NAT_SUB:
                return a > b ? a - b : 0; // truncated subtraction
            case Specials.NAT_MUL:
                return a * b;
            case Specials.NAT_DIV:
                return b == 0 ? 0 : a / b; // x / 0 = 0
            case Specials.NAT_MOD:
                return b == 0 ? a : a % b; // x % 0 = x
            case Specials.NAT_BEQ:
                return a == b ? 1 : 0;
            case Specials.NAT_BLE:
                return a <= b ? 1 : 0;
            case Specials.NAT_LAND:
                return a & b;
            case Specials.NAT_LOR:
                return a | b;
            case Specials.NAT_XOR:
                return a ^ b;
            case Specials.NAT_SHIFT_RIGHT:
                return b >= 31 ? 0 : a >>> b;
            case Specials.NAT_SHIFT_LEFT:
                return b < 32 ? a << b : -1;
            default:
                return -1;
        }
    }

    /** The general fold: handles, {@link #natop}, the result as a value. */
    private static int foldSlow(Machine m, int which, int x, int y) {
        long a = litExt(m, x);
        long b = litExt(m, y);
        long r = natop(m, which - Specials.NAT_ADD + NatOp.ADD, a, b);
        if ((which == Specials.NAT_BEQ || which == Specials.NAT_BLE) && r >= 0) {
            return boolConst(m, r != 0);
        }
        return natValue(m, r);
    }

    /**
     * {@code Bool.true} / {@code Bool.false}, the results of {@code Nat.beq} and {@code Nat.ble}. The official kernel
     * answers with the constant of that name whether it is declared or not; RLVM cannot represent an undeclared
     * constant: not declared or INVALID is fault(E_PRELUDE) (as the reference kernel declines), ABSENT is NONE.
     */
    static int boolConst(Machine m, boolean value) {
        int which = value ? Specials.BOOL_TRUE : Specials.BOOL_FALSE;
        int cid = m.specials.cid(which);
        if (cid == 0) {
            return m.fault(MachineErrors.E_PRELUDE);
        }
        int g = guard(m, which);
        return g != 1 ? (g == 0 ? Layout.NONE : g) : Values.mkConst(m, cid, IntLists.EMPTY);
    }

    // =====================================================================================================
    // Nat.succ: the unary case of reduce_nat, constructor views, predecessors
    // =====================================================================================================

    /**
     * The unary case of {@code reduce_nat} on {@code nsuc = NSUC(b, k)} (ISA 4.3.4; R02 7.6 row {@code Nat.succ a}),
     * given {@code w = WHNF(b)}: a literal n or the constant {@code Nat.zero}: the literal n + k with the size check
     * (k single {@code Nat.succ} steps; their results grow, so the last check decides); anything else: the ORIGINAL
     * cell.
     * @return the value, or ABORT (E_NATSIZE) or FATAL (E_PRELUDE, E_MEM)
     */
    static int succFold(Machine m, int nsuc, int w) {
        int k = m.vh[(nsuc << Layout.REC_SHIFT) + Layout.B];
        if (w >= 0 && !isLitRecord(m, w)) {
            return nsuc;
        }
        boolean zero = w >= 0 && isZeroRecord(m, w);
        int g = guard(m, Specials.NAT_SUCC);
        if (g == 1 && zero) {
            g = guard(m, Specials.NAT_ZERO);
        }
        if (g != 1) {
            return g == 0 ? nsuc : g;
        }
        if (w < 0) {
            return natValue(m, (long) (w & Layout.IMM_MASK) + k);
        }
        return natValue(m, Nat.add(m.nats, zero ? 0 : Lits.natHandle(m, w), k)); // TOO_BIG: E_NATSIZE
    }

    /**
     * {@code nat_lit_to_constructor} without the record (ISA 4.3.5): the constructor row a Nat literal is viewed
     * with: {@code Nat.zero} for 0, {@code Nat.succ} otherwise; 0 if that constant is not declared (the official
     * kernel then finds no rule); FATAL (E_PRELUDE) if its guard is not VALID.
     */
    static int litCtor(Machine m, int lit) {
        return specialCtor(m, lit == Layout.IMM ? Specials.NAT_ZERO : Specials.NAT_SUCC);
    }

    /** The constructor row of {@code Nat.succ} (the head of an NSUC): see {@link #litCtor}. */
    static int succCtor(Machine m) {
        return specialCtor(m, Specials.NAT_SUCC);
    }

    private static int specialCtor(Machine m, int which) {
        int cid = m.specials.cid(which);
        if (cid == 0 || m.decls.rank(cid) >= m.decl) {
            return 0;
        }
        int g = guard(m, which);
        return g == 1 ? cid : g;
    }

    /**
     * {@code NSUC(b, k)} without its {@code j >= 1} outermost {@code Nat.succ} layers: {@code b} if {@code j >= k},
     * else the record {@code NSUC(b, k - j)} ({@code Values.npeel}).
     * @return the value, or FATAL (E_MEM)
     */
    static int unsucc(Machine m, int nsuc, int j) {
        int k = m.vh[(nsuc << Layout.REC_SHIFT) + Layout.B];
        return Values.npeel(m, nsuc, Math.min(j, k));
    }

    /** The predecessor of a non-zero Nat literal record (an NBIG), as a value. */
    static int litPred(Machine m, int lit) {
        return natValue(m, Nat.pred(m.nats, Lits.natHandle(m, lit)));
    }

    // =====================================================================================================
    // OFFSET (ISA 4.4, B10; official is_def_eq_offset, R02 8.4)
    // =====================================================================================================

    private static final int S_OTHER = 0;
    private static final int S_ZERO = 1;
    private static final int S_LIT = 2;
    private static final int S_NSUC = 3;

    /** Shape of a side of OFFSET: zero (literal 0 or {@code Nat.zero}), a positive literal, an NSUC, or anything else. */
    private static int shape(Machine m, int x) {
        if (x < 0) {
            return x == Layout.IMM ? S_ZERO : S_LIT;
        }
        if (x <= Layout.HOLE) {
            return S_OTHER;
        }
        int kind = m.vh[x << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT;
        return kind == Layout.NSUC ? S_NSUC : kind == Layout.NBIG ? S_LIT
                : kind == Layout.CONST && isZeroConst(m, x) ? S_ZERO : S_OTHER;
    }

    /**
     * The inline part of OFFSET: T, F or UNDEF when no comparison of a rest is needed, else {@code Op.MISS}
     * (or FATAL: E_PRELUDE).
     *
     * <p>Both zero: T. Both have a predecessor: the official kernel compares the predecessors with
     * {@code is_def_eq_core}; for two literals that is the comparison of their values, answered here. One side
     * without a predecessor: UNDEF.
     */
    static int offsetFast(Machine m, int a, int b) {
        int sa = shape(m, a);
        if (sa == S_OTHER) {
            return Layout.UNDEF;
        }
        int sb = shape(m, b);
        if (sb == S_OTHER || (sa == S_ZERO) != (sb == S_ZERO)) {
            return Layout.UNDEF;
        }
        if (sa == S_ZERO) {
            // literal 0 against the constant Nat.zero (two equal refs never get here): by the guard of Nat.zero
            int g = a == b ? 1 : guard(m, Specials.NAT_ZERO);
            return g == 1 ? Layout.T : g == 0 ? Layout.UNDEF : g;
        }
        if (sa == S_LIT && sb == S_LIT) {
            return Lits.sameContent(m, a, b) ? Layout.T : Layout.F;
        }
        return Op.MISS;
    }

    /**
     * OFFSET(a, b) (ISA 4.4, B10): both zero: T; both have a predecessor: peel min layers at once, CONV the rest
     * (definitive); else UNDEF. Official: {@code is_def_eq_offset} (R02 8.4), k single steps composed.
     * Entry frame: OFFSET[0](a, b).
     * @return T, F, UNDEF, or a signal
     */
    static int offset(Machine m, int a, int b, int d) {
        int r = offsetFast(m, a, b);
        if (r != Op.MISS) {
            return r; // 1. inline answers (also a fault)
        }
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig; // 2. TICK
        }
        if (m.stats) {
            m.call(Op.OFFSET_F);
        }
        if (d > m.hostDepth) {
            return m.spill2(Op.K_OFFSET, a, b); // 3. too deep
        }
        int g = guard(m, Specials.NAT_SUCC);
        if (g != 1) {
            return g == 0 ? Layout.UNDEF : g;
        }
        // One side is an NSUC, the other an NSUC or a positive literal: both have j = min(layers) predecessors.
        long la = layers(m, a);
        long lb = layers(m, b);
        int j = (int) Math.min(la, lb);
        int ra = peel(m, a, j);
        if (ra >= SIG) {
            return ra;
        }
        int rb = peel(m, b, j);
        if (rb >= SIG) {
            return rb;
        }
        return Conv.conv(m, ra, rb, d + 1); // tail: the answer of the rest is definitive (R02 8.4)
    }

    /** Number of {@code Nat.succ} layers of an NSUC (its k) or of a positive literal (its value, capped). */
    private static long layers(Machine m, int x) {
        if (x < 0) {
            return x & Layout.IMM_MASK;
        }
        int o = x << Layout.REC_SHIFT;
        if ((m.vh[o] >>> Layout.KIND_SHIFT) == Layout.NSUC) {
            return m.vh[o + Layout.B];
        }
        return Long.MAX_VALUE; // an NBIG is at least 2^31: more layers than any NSUC has
    }

    /** {@code x} without its {@code j} outermost successor layers (1 <= j <= layers(x)). */
    private static int peel(Machine m, int x, int j) {
        if (x < 0) {
            return x - j; // the immediate n - j
        }
        if ((m.vh[x << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT) == Layout.NSUC) {
            return unsucc(m, x, j);
        }
        return natValue(m, Nat.sub(m.nats, Lits.natHandle(m, x), j));
    }
}
